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

import io.evitadb.api.CommitProgress.CommitVersions;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * GoLiveProgressRecord is an implementation of {@link GoLiveProgress} that represents the progress of a catalog
 * go-live operation in a database. The go-live operation switches a catalog from warm-up mode to transactional
 * mode.
 *
 * This implementation provides methods to track completion percentage and complete the operation either
 * successfully or exceptionally.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Slf4j
public class GoLiveProgressRecord implements GoLiveProgress {

	/**
	 * Optional observer that can be notified about progress updates.
	 */
	private final IntConsumer progressObserver;

	/**
	 * CompletableFuture that completes when the go-live operation has finished.
	 */
	private final CompletableFuture<CommitVersions> onCompletion;

	/**
	 * Atomic integer to track the percentage of completion.
	 */
	private final AtomicInteger percentCompleted;

	/**
	 * Creates a new instance of GoLiveProgressRecord with the specified termination sequence callback.
	 */
	public GoLiveProgressRecord(@Nullable IntConsumer progressObserver) {
		this.progressObserver = progressObserver;
		this.onCompletion = new CompletableFuture<>();
		this.percentCompleted = new AtomicInteger(0);
	}

	@Override
	public int percentCompleted() {
		return this.percentCompleted.get();
	}

	@Override
	@Nonnull
	public CompletionStage<CommitVersions> onCompletion() {
		return this.onCompletion;
	}

	@Override
	public boolean isCompletedSuccessfully() {
		return this.onCompletion.isDone() && !this.onCompletion.isCompletedExceptionally();
	}

	@Override
	public boolean isCompletedExceptionally() {
		return this.onCompletion.isCompletedExceptionally();
	}

	/**
	 * Updates the percentage of completion for the go-live operation.
	 *
	 * @param percentage the new percentage of completion (0-100)
	 * @throws IllegalArgumentException if percentage is not between 0 and 100
	 */
	public void updatePercentCompleted(int percentage) {
		if (percentage < 0 || percentage > 100) {
			throw new IllegalArgumentException("Percentage must be between 0 and 100, but was: " + percentage);
		}
		final int previousValue = this.percentCompleted.getAndSet(percentage);
		if (previousValue != percentage) {
			notifyClientObserver(percentage);
		}
	}

	/**
	 * Completes the go-live operation successfully.
	 */
	public void complete(@Nonnull CommitVersions commitVersions) {
		if (!this.onCompletion.isDone()) {
			this.percentCompleted.set(100);
			notifyClientObserver(100);
			this.onCompletion.complete(commitVersions);
		}
	}

	/**
	 * Completes the go-live operation exceptionally with the specified exception.
	 *
	 * @param exception the exception to complete the operation with
	 */
	public void completeExceptionally(@Nonnull Throwable exception) {
		if (!this.onCompletion.isDone()) {
			notifyClientObserver(100);
			this.onCompletion.completeExceptionally(exception);
		}
	}

	/**
	 * Notifies the client observer about the current progress by providing the specified percentage.
	 * If the observer is null or if an exception occurs during the notification process,
	 * an error is logged without propagating the exception.
	 *
	 * @param percentage the current progress percentage to be sent to the client observer
	 */
	private void notifyClientObserver(int percentage) {
		if (this.progressObserver != null) {
			try {
				this.progressObserver.accept(percentage);
			} catch (Throwable t) {
				log.error("Error notifying progress observer with percentage: " + percentage, t);
			}
		}
	}
}
