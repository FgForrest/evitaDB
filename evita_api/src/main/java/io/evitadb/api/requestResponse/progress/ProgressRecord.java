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

package io.evitadb.api.requestResponse.progress;

import io.evitadb.function.Functions;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * ProgressRecord is an implementation of {@link Progress} that represents the progress of a catalog
 * operation in a database. The operation switches a catalog from warm-up mode to transactional
 * mode.
 *
 * This implementation provides methods to track completion percentage and complete the operation either
 * successfully or exceptionally.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Slf4j
public class ProgressRecord<T> implements Progress<T> {

	/**
	 * Represents the name of the operation being tracked.
	 */
	private final String operationName;

	/**
	 * Optional observer that can be notified about progress updates.
	 */
	private final List<IntConsumer> progressObservers = new CopyOnWriteArrayList<>();

	/**
	 * CompletableFuture that completes when the operation has finished.
	 */
	private final CompletableFuture<T> onCompletion;

	/**
	 * Atomic integer to track the percentage of completion.
	 */
	private final AtomicInteger percentCompleted;

	/**
	 * Timestamp of the last logged progress update.
	 * This is used to avoid flooding the logs with too frequent updates.
	 */
	private long lastLoggedTime;

	/**
	 * Creates a completed progress instance for a specific operation with a given result.
	 * The operation is marked as successfully completed, and the progress percentage
	 * is set to 100 immediately.
	 *
	 * @param <T>           the type of result associated with the completed operation
	 * @param operationName the name of the operation being completed; must not be null
	 * @param result        the result of the completed operation; can be null
	 * @return a completed progress instance representing the operation's completion
	 */
	@Nonnull
	public static <T> Progress<T> completed(@Nonnull String operationName, @Nullable T result) {
		final ProgressRecord<T> completedRecord = new ProgressRecord<>(operationName, null);
		completedRecord.complete(result);
		return completedRecord;
	}

	/**
	 * Creates a new instance of ProgressRecord with the specified termination sequence callback.
	 */
	public ProgressRecord(
		@Nonnull String operationName,
		@Nullable IntConsumer progressObserver
	) {
		this.operationName = operationName;
		if (progressObserver != null) {
			this.progressObservers.add(progressObserver);
		}
		this.onCompletion = new CompletableFuture<>();
		this.percentCompleted = new AtomicInteger(-1);
	}

	/**
	 * Creates a new instance of ProgressRecord with the specified termination sequence callback.
	 */
	public ProgressRecord(
		@Nonnull String operationName,
		@Nullable IntConsumer progressObserver,
		@Nonnull ProgressingFuture<T> progressingFuture,
		@Nonnull Executor executor
	) {
		this(
			operationName,
			progressObserver,
			progressingFuture,
			Functions.noOpConsumer(),
			Functions.noOpConsumer(),
			executor
		);
	}

	/**
	 * Creates a new instance of ProgressRecord with the specified termination sequence callback.
	 */
	public ProgressRecord(
		@Nonnull String operationName,
		@Nullable IntConsumer progressObserver,
		@Nonnull ProgressingFuture<T> progressingFuture,
		@Nonnull Consumer<ProgressRecord<T>> onProgressExecution,
		@Nonnull Consumer<ProgressRecord<T>> onProgressCompletion,
		@Nonnull Executor executor
	) {
		this.operationName = operationName;
		if (progressObserver != null) {
			this.progressObservers.add(progressObserver);
		}
		this.onCompletion = progressingFuture.whenComplete(
			(t, throwable) -> onProgressCompletion.accept(this)
		);
		this.percentCompleted = new AtomicInteger(-1);
		progressingFuture.setProgressConsumer(
			(stepsDone, totalSteps) -> this.updatePercentCompleted(
				(int) (((double) stepsDone / totalSteps) * 100d)
			)
		);
		updatePercentCompleted(0);
		onProgressExecution.accept(this);
		progressingFuture.execute(executor);
	}

	@Override
	public int percentCompleted() {
		return Math.max(0, this.percentCompleted.get());
	}

	@Override
	@Nonnull
	public CompletionStage<T> onCompletion() {
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
	 * Updates the percentage of completion for the operation.
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
			if (percentage == 100 || this.lastLoggedTime + 1000 < System.currentTimeMillis()) {
				// Log the progress update every second to avoid flooding the logs
				log.info("{} is at {}%", this.operationName, percentage);
				this.lastLoggedTime = System.currentTimeMillis();
			}
			notifyClientObserver(percentage);
		}
	}

	/**
	 * Completes the operation successfully.
	 */
	public void complete(@Nullable T result) {
		if (!this.onCompletion.isDone()) {
			log.info("{} has been successfully completed.", this.operationName);
			updatePercentCompleted(100);
			this.onCompletion.complete(result);
		}
	}

	/**
	 * Completes the operation exceptionally with the specified exception.
	 *
	 * @param exception the exception to complete the operation with
	 */
	public void completeExceptionally(@Nonnull Throwable exception) {
		if (!this.onCompletion.isDone()) {
			log.error("{} has failed with error: {}", this.operationName, exception.getMessage(), exception);
			notifyClientObserver(100);
			this.onCompletion.completeExceptionally(exception);
		}
	}

	@Override
	public void addProgressListener(@Nonnull IntConsumer intConsumer) {
		this.progressObservers.add(intConsumer);
	}

	@Override
	public void removeProgressListener(@Nonnull IntConsumer intConsumer) {
		this.progressObservers.remove(intConsumer);
	}

	/**
	 * Notifies the client observer about the current progress by providing the specified percentage.
	 * If the observer is null or if an exception occurs during the notification process,
	 * an error is logged without propagating the exception.
	 *
	 * @param percentage the current progress percentage to be sent to the client observer
	 */
	private void notifyClientObserver(int percentage) {
		for (IntConsumer progressObserver : this.progressObservers) {
			try {
				progressObserver.accept(percentage);
			} catch (Throwable t) {
				log.error("Error notifying progress observer with percentage: " + percentage, t);
			}
		}
	}
}
