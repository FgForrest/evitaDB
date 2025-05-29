/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api;

import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.function.Functions;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;

/**
 * CommitProgressRecord is an implementation of {@link CommitProgress} that represents the progress of a transaction
 * commit operation in a database. It contains multiple CompletableFuture objects that allow tracking the status of
 * various stages of the commit process.
 *
 * This implementation provides methods to complete all futures either successfully or exceptionally.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class CommitProgressRecord implements CommitProgress {

	/**
	 * Callback that is executed just before the particular stage is marked as completed.
	 */
	public final BiConsumer<CommitVersions, Throwable> terminationSequence;

	/**
	 * CompletableFuture that completes when the system verifies changes are not in conflict with
	 * changes from other transactions committed in the meantime.
	 */
	private final CompletableFuture<CommitVersions> onConflictResolved;

	/**
	 * CompletableFuture that completes when the changes are appended to the Write Ahead Log (WAL).
	 */
	private final CompletableFuture<CommitVersions> onWalAppended;

	/**
	 * CompletableFuture that completes when the changes are visible to all readers.
	 */
	private final CompletableFuture<CommitVersions> onChangesVisible;
	/**
	 * The stage of the commit that represents the termination phase of the session.
	 */
	@Getter @Setter public CommitBehavior terminationStage = CommitBehavior.WAIT_FOR_CHANGES_VISIBLE;

	/**
	 * Completes the given CompletableFuture with the provided CommitVersions object, either by scheduling the completion
	 * asynchronously in the specified executor or, in case the executor cannot accept the task, by completing it immediately.
	 *
	 * @param future a CompletableFuture to resolve when the completion is processed
	 * @param commitVersions     the CommitVersions object containing catalog and schema versions to complete the future with
	 * @param executor           the executor in which the completion should be scheduled asynchronously
	 */
	private static void completeStage(
		@Nonnull CompletableFuture<CommitVersions> future,
		@Nonnull CommitVersions commitVersions,
		@Nonnull Executor executor
	) {
		try {
			future.completeAsync(() -> commitVersions, executor)
				.whenComplete((result, throwable) -> {
					// if the future is cancelled, we complete it with the result anyway
					if (throwable instanceof CancellationException) {
						future.complete(result);
					}
				});

		} catch (RejectedExecutionException ignored) {
			// if the the executor cannot accept the task, we complete the future immediately
			future.complete(commitVersions);
		}
	}

	/**
	 * Creates a new instance of CommitProgressRecord with the specified CompletableFuture objects.
	 */
	public CommitProgressRecord() {
		this(Functions.noOpBiConsumer());
	}

	/**
	 * Creates a new instance of CommitProgressRecord with the specified CompletableFuture objects.
	 */
	public CommitProgressRecord(@Nonnull BiConsumer<CommitVersions, Throwable> terminationSequence) {
		this.terminationSequence = terminationSequence;
		this.onConflictResolved = new CompletableFuture<>();
		this.onWalAppended = new CompletableFuture<>();
		this.onChangesVisible = new CompletableFuture<>();
	}

	@Override
	@Nonnull
	public CompletionStage<CommitVersions> onConflictResolved() {
		return this.onConflictResolved;
	}

	@Override
	@Nonnull
	public CompletionStage<CommitVersions> onWalAppended() {
		return this.onWalAppended;
	}

	@Override
	@Nonnull
	public CompletionStage<CommitVersions> onChangesVisible() {
		return this.onChangesVisible;
	}

	@Override
	public boolean isCompletedSuccessfully() {
		return this.onConflictResolved.isDone() &&
			this.onWalAppended.isDone() &&
			this.onChangesVisible.isDone() &&
			!this.onConflictResolved.isCompletedExceptionally() &&
			!this.onWalAppended.isCompletedExceptionally() &&
			!this.onChangesVisible.isCompletedExceptionally();
	}

	@Override
	public boolean isCompletedExceptionally() {
		return this.onConflictResolved.isCompletedExceptionally() ||
			this.onWalAppended.isCompletedExceptionally() ||
			this.onChangesVisible.isCompletedExceptionally();
	}

	/**
	 * Completes all non-finished futures exceptionally with the specified exception.
	 *
	 * @param exception the exception to complete the futures with
	 */
	public void completeExceptionally(@Nonnull Throwable exception) {
		if (!this.onConflictResolved.isDone()) {
			if (this.terminationStage == CommitBehavior.WAIT_FOR_CONFLICT_RESOLUTION) {
				this.terminationSequence.accept(null, exception);
			}
			this.onConflictResolved.completeExceptionally(exception);
		}
		if (!this.onWalAppended.isDone()) {
			if (this.terminationStage == CommitBehavior.WAIT_FOR_WAL_PERSISTENCE) {
				this.terminationSequence.accept(null, exception);
			}
			this.onWalAppended.completeExceptionally(exception);
		}
		if (!this.onChangesVisible.isDone()) {
			if (this.terminationStage == CommitBehavior.WAIT_FOR_CHANGES_VISIBLE) {
				this.terminationSequence.accept(null, exception);
			}
			this.onChangesVisible.completeExceptionally(exception);
		}
	}

	/**
	 * Completes the appropriate CompletableFuture based on the specified commit behavior and commit versions.
	 * The method determines the kind of commit behavior and completes the corresponding CompletableFuture
	 * with the provided commit versions.
	 *
	 * @param commitBehavior the behavior determining how the commit process should proceed,
	 *                       such as waiting for conflict resolution, WAL persistence, or visibility of changes
	 * @param commitVersions the commit versions to be used for completing the futures;
	 *                       it encapsulates information such as catalog version and schema version
	 * @throws IllegalArgumentException if the specified commit behavior is unsupported
	 */
	public void complete(@Nonnull CommitBehavior commitBehavior, @Nonnull CommitVersions commitVersions) {
		switch (commitBehavior) {
			case WAIT_FOR_CONFLICT_RESOLUTION -> {
				if (!this.onConflictResolved.isDone()) {
					if (this.terminationStage == CommitBehavior.WAIT_FOR_CONFLICT_RESOLUTION) {
						this.terminationSequence.accept(commitVersions, null);
					}
					this.onConflictResolved.complete(commitVersions);
				}
			}
			case WAIT_FOR_WAL_PERSISTENCE -> {
				if (!this.onWalAppended.isDone()) {
					if (this.terminationStage == CommitBehavior.WAIT_FOR_WAL_PERSISTENCE) {
						this.terminationSequence.accept(commitVersions, null);
					}
					this.onWalAppended.complete(commitVersions);
				}
			}
			case WAIT_FOR_CHANGES_VISIBLE -> {
				if (!this.onChangesVisible.isDone()) {
					if (this.terminationStage == CommitBehavior.WAIT_FOR_CHANGES_VISIBLE) {
						this.terminationSequence.accept(commitVersions, null);
					}
					this.onChangesVisible.complete(commitVersions);
				}
			}
			default -> throw new IllegalArgumentException("Unsupported commit behavior: " + commitBehavior);
		}
	}

	/**
	 * Completes the appropriate CompletableFuture based on the specified commit behavior and commit versions.
	 * The method determines the kind of commit behavior and completes the corresponding CompletableFuture
	 * with the provided commit versions.
	 *
	 * @param commitBehavior the behavior determining how the commit process should proceed,
	 *                       such as waiting for conflict resolution, WAL persistence, or visibility of changes
	 * @param commitVersions the commit versions to be used for completing the futures;
	 *                       it encapsulates information such as catalog version and schema version
	 * @param executor       the executor to run the completion asynchronously
	 * @throws IllegalArgumentException if the specified commit behavior is unsupported
	 */
	public void complete(@Nonnull CommitBehavior commitBehavior, @Nonnull CommitVersions commitVersions, @Nonnull Executor executor) {
		switch (commitBehavior) {
			case WAIT_FOR_CONFLICT_RESOLUTION -> {
				if (!this.onConflictResolved.isDone()) {
					if (this.terminationStage == CommitBehavior.WAIT_FOR_CONFLICT_RESOLUTION) {
						this.terminationSequence.accept(commitVersions, null);
					}
					completeStage(this.onConflictResolved, commitVersions, executor);
				}
			}
			case WAIT_FOR_WAL_PERSISTENCE -> {
				if (!this.onWalAppended.isDone()) {
					if (this.terminationStage == CommitBehavior.WAIT_FOR_WAL_PERSISTENCE) {
						this.terminationSequence.accept(commitVersions, null);
					}
					completeStage(this.onWalAppended, commitVersions, executor);
				}
			}
			case WAIT_FOR_CHANGES_VISIBLE -> {
				if (!this.onChangesVisible.isDone()) {
					if (this.terminationStage == CommitBehavior.WAIT_FOR_CHANGES_VISIBLE) {
						this.terminationSequence.accept(commitVersions, null);
					}
					completeStage(this.onChangesVisible, commitVersions, executor);
				}
			}
			default -> throw new IllegalArgumentException("Unsupported commit behavior: " + commitBehavior);
		}
	}

	/**
	 * Completes all non-finished futures successfully.
	 * For onConflictResolved, it completes with null.
	 * For onWalAppended and onChangesVisible, it completes with the specified CommitVersions.
	 *
	 * @param commitVersions the result to complete the futures with
	 */
	public void complete(@Nonnull CommitVersions commitVersions) {
		if (!this.onConflictResolved.isDone()) {
			if (this.terminationStage == CommitBehavior.WAIT_FOR_CONFLICT_RESOLUTION) {
				this.terminationSequence.accept(commitVersions, null);
			}
			this.onConflictResolved.complete(commitVersions);
		}
		if (!this.onWalAppended.isDone()) {
			if (this.terminationStage == CommitBehavior.WAIT_FOR_WAL_PERSISTENCE) {
				this.terminationSequence.accept(commitVersions, null);
			}
			this.onWalAppended.complete(commitVersions);
		}
		if (!this.onChangesVisible.isDone()) {
			if (this.terminationStage == CommitBehavior.WAIT_FOR_CHANGES_VISIBLE) {
				this.terminationSequence.accept(commitVersions, null);
			}
			this.onChangesVisible.complete(commitVersions);
		}
	}
}