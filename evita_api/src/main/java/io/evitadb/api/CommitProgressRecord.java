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
import java.time.OffsetDateTime;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;

/**
 * Mutable implementation of {@link CommitProgress} that tracks commit pipeline stages via {@link CompletableFuture}s.
 *
 * **Purpose and Architecture**
 *
 * `CommitProgressRecord` is the concrete implementation used internally by evitaDB to manage commit progress. It
 * provides methods for the transaction subsystem to signal completion of each commit stage, while exposing read-only
 * {@link CompletionStage}s to client code via the {@link CommitProgress} interface.
 *
 * **Internal vs. Public API**
 *
 * - **Public API** ({@link CommitProgress}): Clients observe progress via read-only {@link CompletionStage}s
 * - **Internal API** (this class): evitaDB transaction logic completes stages via `complete()` and
 * `completeExceptionally()` methods
 *
 * **Completion Contract**
 *
 * This implementation enforces {@link CommitProgress} guarantees:
 * 1. Stages complete sequentially (conflict resolution → WAL → visibility)
 * 2. Exception in any stage immediately propagates to all later stages
 * 3. All stages eventually complete (no hanging futures)
 * 4. Idempotent completion (multiple calls to `complete()` have no effect after first completion)
 *
 * **Termination Sequence**
 *
 * The {@link #terminationSequence} callback is invoked just before marking a stage as complete, based on the
 * {@link #terminationStage} setting. This allows session cleanup logic to execute at the appropriate commit milestone
 * (e.g., close session resources after WAL persistence but before indexing).
 *
 * **Asynchronous Completion**
 *
 * The `complete(CommitBehavior, CommitVersions, Executor)` variant uses an executor to complete futures asynchronously,
 * preventing the transaction pipeline thread from blocking on client callbacks. If the executor rejects the task,
 * completion falls back to synchronous mode.
 *
 * **Thread-Safety**
 *
 * This class is thread-safe. Multiple threads can safely call completion methods and observe stages concurrently.
 * Internal {@link CompletableFuture}s handle synchronization.
 *
 * **Typical Usage (Internal)**
 *
 * ```
 * CommitProgressRecord progress = new CommitProgressRecord();
 * progress.setTerminationStage(sessionTraits.commitBehaviour());
 *
 * // Stage 1: Conflict resolution
 * try {
 * resolveConflicts(transaction);
 * progress.complete(CommitBehavior.WAIT_FOR_CONFLICT_RESOLUTION, versions, executor);
 * } catch (Exception ex) {
 * progress.completeExceptionally(ex);
 * return;
 * }
 *
 * // Stage 2: WAL persistence
 * walPersistenceService.persist(mutations)
 * .thenAccept(v -> progress.complete(CommitBehavior.WAIT_FOR_WAL_PERSISTENCE, versions, executor))
 * .exceptionally(ex -> { progress.completeExceptionally(ex); return null; });
 *
 * // Stage 3: Index updates
 * indexUpdateService.apply(mutations)
 * .thenAccept(v -> progress.complete(CommitBehavior.WAIT_FOR_CHANGES_VISIBLE, versions, executor))
 * .exceptionally(ex -> { progress.completeExceptionally(ex); return null; });
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class CommitProgressRecord implements CommitProgress {

	/**
	 * Timestamp marking when the commit process began.
	 *
	 * Captured when this `CommitProgressRecord` is created (typically when {@link EvitaSessionContract#closeNowWithProgress()}
	 * is called). Used for performance monitoring and timeout detection.
	 */
	@Getter private final OffsetDateTime commitStartTime = OffsetDateTime.now();

	/**
	 * Callback invoked just before marking a stage as complete, based on {@link #terminationStage}.
	 *
	 * This callback enables session cleanup logic to execute at the appropriate commit milestone. For example:
	 * - If `terminationStage` is {@link CommitBehavior#WAIT_FOR_WAL_PERSISTENCE}, callback runs after WAL persistence
	 * but before indexing
	 * - Session resources (connections, temp files) can be released at the configured stage
	 *
	 * **Signature**: `BiConsumer<CommitVersions, Throwable>`
	 * - First parameter: commit versions (null on exception)
	 * - Second parameter: exception (null on success)
	 *
	 * **Exception Handling**: This callback must not throw exceptions; any errors should be logged internally.
	 */
	public final BiConsumer<CommitVersions, Throwable> terminationSequence;

	/**
	 * Future that completes when optimistic lock conflict resolution finishes.
	 *
	 * Exposed publicly via {@link #onConflictResolved()}. Completed internally by evitaDB transaction logic when
	 * conflict check passes or fails.
	 */
	private final CompletableFuture<CommitVersions> onConflictResolved;

	/**
	 * Future that completes when changes are persisted to the Write-Ahead Log (WAL).
	 *
	 * Exposed publicly via {@link #onWalAppended()}. Completed internally after WAL fsync succeeds or I/O error occurs.
	 */
	private final CompletableFuture<CommitVersions> onWalAppended;

	/**
	 * Future that completes when changes are visible in all indexes.
	 *
	 * Exposed publicly via {@link #onChangesVisible()}. Completed internally after index updates finish or indexing
	 * fails.
	 */
	private final CompletableFuture<CommitVersions> onChangesVisible;

	/**
	 * The commit stage at which the {@link #terminationSequence} callback should be invoked.
	 *
	 * Defaults to {@link CommitBehavior#WAIT_FOR_CHANGES_VISIBLE}. Set to earlier stages (e.g.,
	 * {@link CommitBehavior#WAIT_FOR_WAL_PERSISTENCE}) to release session resources sooner.
	 *
	 * **Mutable**: This field is set after construction to match the session's {@link SessionTraits#commitBehaviour()}.
	 */
	@Getter @Setter public CommitBehavior terminationStage = CommitBehavior.WAIT_FOR_CHANGES_VISIBLE;

	/**
	 * Asynchronously completes a stage, preventing transaction thread from blocking on client callbacks.
	 *
	 * This method attempts to complete the future in the provided executor to offload client callback execution
	 * from the evitaDB transaction pipeline thread. If the executor rejects the task (shutdown or queue full),
	 * falls back to synchronous completion.
	 *
	 * **Cancellation Handling**: If the async completion is cancelled, the future is still completed with the result
	 * to ensure all stages eventually complete.
	 *
	 * @param thisStage      the future to complete
	 * @param commitVersions the versions to complete with
	 * @param executor       the executor to run completion asynchronously
	 */
	private static void completeStage(
		@Nonnull CompletableFuture<CommitVersions> thisStage,
		@Nonnull CommitVersions commitVersions,
		@Nonnull Executor executor
	) {
		try {
			// we prefer completing the future asynchronously in the provided executor
			// so that transactional pipeline is not blocked by the completion
			thisStage.completeAsync(() -> commitVersions, executor)
				.whenComplete((result, throwable) -> {
					// if the future is cancelled, we complete it with the result anyway
					if (throwable instanceof CancellationException) {
						thisStage.complete(result);
					} else if (throwable != null) {
						thisStage.completeExceptionally(throwable);
					}
				});

		} catch (RejectedExecutionException ignored) {
			// if the the executor cannot accept the task, we complete the future immediately
			thisStage.complete(commitVersions);
		}
	}

	/**
	 * Chains completion of this stage to the previous stage.
	 *
	 * This method synchronously completes `thisStage` when `previousStage` completes. Used for later stages
	 * (WAL, visibility) that chain off the async completion of the first stage (conflict resolution).
	 *
	 * Since the first stage is completed asynchronously, chaining here is safe and won't block the transaction thread.
	 *
	 * @param previousStage the stage that must complete first
	 * @param thisStage     the stage to complete when previous stage finishes
	 */
	private static void completeStage(
		@Nonnull CompletionStage<CommitVersions> previousStage,
		@Nonnull CompletableFuture<CommitVersions> thisStage
	) {
		// here we can just chain completion of this stage to the previous one
		// since the first stage is done asynchronously, we can be synchronous here because it'll be executed
		// in the same thread as the previous stage's completion
		previousStage.whenComplete((result, throwable) -> thisStage.complete(result));
	}

	/**
	 * Creates a new `CommitProgressRecord` with no termination callback.
	 *
	 * Equivalent to `new CommitProgressRecord(Functions.noOpBiConsumer())`. Used when no session cleanup logic
	 * is needed at commit milestones.
	 */
	public CommitProgressRecord() {
		this(Functions.noOpBiConsumer());
	}

	/**
	 * Creates a new `CommitProgressRecord` with the specified termination callback.
	 *
	 * The callback is invoked just before marking the stage configured in {@link #terminationStage} as complete.
	 * This allows session cleanup logic to execute at the appropriate commit milestone.
	 *
	 * @param terminationSequence callback invoked before completing the termination stage; must not throw exceptions
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
	public boolean isDone() {
		return this.onConflictResolved.isDone() &&
			this.onWalAppended.isDone() &&
			this.onChangesVisible.isDone();
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
	 * Fails all incomplete stages with the specified exception, propagating failure through the pipeline.
	 *
	 * This method implements the guarantee that when any stage fails, all later stages fail immediately with the same
	 * exception. Invokes {@link #terminationSequence} callback with the exception when completing the configured
	 * {@link #terminationStage}.
	 *
	 * **Idempotency**: Already-completed stages are not affected; only incomplete stages are failed.
	 *
	 * **Use Cases**
	 *
	 * - Conflict detection fails: call with `ConcurrentSchemaUpdateException`
	 * - WAL I/O error: call with `IOException`
	 * - Catalog shutdown: call with `InstanceTerminatedException`
	 *
	 * @param exception the exception to fail all incomplete stages with
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
	 * Completes the stage corresponding to the specified commit behavior synchronously.
	 *
	 * This method marks the specified stage as complete with the provided versions. If the stage is already complete,
	 * this call has no effect (idempotent). Invokes {@link #terminationSequence} callback when completing the
	 * configured {@link #terminationStage}.
	 *
	 * **Synchronous Completion**: This variant completes the future immediately on the calling thread. Client callbacks
	 * registered via `thenAccept()` etc. will execute on this thread, potentially blocking the transaction pipeline.
	 * Prefer {@link #complete(CommitBehavior, CommitVersions, Executor)} for production use.
	 *
	 * **Use Cases**
	 *
	 * - Testing: deterministic completion order
	 * - Read-only sessions: no client callbacks expected
	 * - Empty transactions: immediate completion of all stages
	 *
	 * @param commitBehavior the stage to complete ({@link CommitBehavior#WAIT_FOR_CONFLICT_RESOLUTION},
	 *                       {@link CommitBehavior#WAIT_FOR_WAL_PERSISTENCE}, or
	 *                       {@link CommitBehavior#WAIT_FOR_CHANGES_VISIBLE})
	 * @param commitVersions the versions to complete the stage with
	 * @throws IllegalArgumentException if an unsupported commit behavior is provided (should never happen)
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
	 * Completes the stage corresponding to the specified commit behavior asynchronously.
	 *
	 * This is the preferred method for production use. It completes the specified stage asynchronously in the provided
	 * executor to prevent client callbacks from blocking the transaction pipeline thread.
	 *
	 * **Asynchronous Behavior**
	 *
	 * - **WAIT_FOR_CONFLICT_RESOLUTION**: Completes `onConflictResolved` async in executor
	 * - **WAIT_FOR_WAL_PERSISTENCE**: Chains `onWalAppended` to `onConflictResolved` (synchronous chaining is safe
	 * because the first stage is already async)
	 * - **WAIT_FOR_CHANGES_VISIBLE**: Chains `onChangesVisible` to `onWalAppended` (synchronous chaining is safe)
	 *
	 * **Fallback**: If the executor rejects the task, falls back to synchronous completion (see
	 * {@link #completeStage(CompletableFuture, CommitVersions, Executor)}).
	 *
	 * **Use Cases**
	 *
	 * - Production commit pipeline: offload client callbacks from transaction threads
	 * - High-throughput scenarios: prevent slow client callbacks from blocking commit processing
	 *
	 * @param commitBehavior the stage to complete ({@link CommitBehavior#WAIT_FOR_CONFLICT_RESOLUTION},
	 *                       {@link CommitBehavior#WAIT_FOR_WAL_PERSISTENCE}, or
	 *                       {@link CommitBehavior#WAIT_FOR_CHANGES_VISIBLE})
	 * @param commitVersions the versions to complete the stage with
	 * @param executor       the executor to run completion asynchronously (usually a dedicated callback executor)
	 * @throws IllegalArgumentException if an unsupported commit behavior is provided (should never happen)
	 */
	public void complete(
		@Nonnull CommitBehavior commitBehavior, @Nonnull CommitVersions commitVersions, @Nonnull Executor executor) {
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
					completeStage(this.onConflictResolved, this.onWalAppended);
				}
			}
			case WAIT_FOR_CHANGES_VISIBLE -> {
				if (!this.onChangesVisible.isDone()) {
					if (this.terminationStage == CommitBehavior.WAIT_FOR_CHANGES_VISIBLE) {
						this.terminationSequence.accept(commitVersions, null);
					}
					completeStage(this.onWalAppended, this.onChangesVisible);
				}
			}
			default -> throw new IllegalArgumentException("Unsupported commit behavior: " + commitBehavior);
		}
	}

	/**
	 * Immediately completes all incomplete stages successfully with the provided versions.
	 *
	 * This method is a convenience for completing all stages at once, typically used for:
	 * - Read-only sessions (no actual commit processing)
	 * - Empty transactions (no mutations to process)
	 * - Test scenarios requiring deterministic completion
	 *
	 * **Behavior**: Completes all incomplete stages synchronously in sequence:
	 * 1. `onConflictResolved`
	 * 2. `onWalAppended`
	 * 3. `onChangesVisible`
	 *
	 * **Idempotency**: Already-completed stages are skipped.
	 *
	 * **Termination Callback**: Invoked when completing the stage configured in {@link #terminationStage}.
	 *
	 * @param commitVersions the versions to complete all stages with
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