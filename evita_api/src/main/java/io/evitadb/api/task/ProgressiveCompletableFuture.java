/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.api.task;

import io.evitadb.utils.Assert;
import io.evitadb.utils.UUIDUtil;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * This extension of {@link CompletableFuture} is used to track the progress of the task and to provide a unique id for
 * the task.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class ProgressiveCompletableFuture<T> extends CompletableFuture<T> {
	/**
	 * Unique id of the task.
	 */
	@Getter
	private final UUID id = UUIDUtil.randomUUID();
	/**
	 * This counter is used to track the progress of the task.
	 * The value -1 means that the future is still waiting for the task to start.
	 * The value 0 means that the task has started.
	 * The value 100 means that the task has finished.
	 */
	private final AtomicInteger progress = new AtomicInteger(-1);
	/**
	 * Listeners that are notified when the progress of the task changes.
	 */
	private final CopyOnWriteArrayList<IntConsumer> progressListeners = new CopyOnWriteArrayList<>();

	/**
	 * Creates a new completed instance of {@link ProgressiveCompletableFuture}.
	 */
	@Nonnull
	public static ProgressiveCompletableFuture<Void> completed() {
		final ProgressiveCompletableFuture<Void> future = new ProgressiveCompletableFuture<>();
		future.complete(null);
		return future;
	}

	/**
	 * Returns progress of the task.
	 *
	 * @return progress of the task in percents
	 */
	public int getProgress() {
		return progress.get();
	}

	/**
	 * Method updates the progress of the task.
	 *
	 * @param progress new progress of the task in percents
	 */
	public void updateProgress(int progress) {
		Assert.isPremiseValid(progress >= 0 && progress <= 100, "Progress must be in range 0-100");
		final int currentProgress = this.progress.updateAndGet(operand -> Math.max(progress, operand));
		for (IntConsumer progressListener : progressListeners) {
			progressListener.accept(currentProgress);
		}
	}

	@Override
	public boolean complete(T value) {
		updateProgress(100);
		this.progressListeners.clear();
		return super.complete(value);
	}

	@Override
	public boolean completeExceptionally(Throwable ex) {
		updateProgress(100);
		this.progressListeners.clear();
		return super.completeExceptionally(ex);
	}

	/**
	 * Returns a new {@link ProgressiveCompletableFuture} that is completed when this task completes. The progress will
	 * be correctly adjusted to reach 100% when both tasks are finished.
	 *
	 * @param nextStage function that is applied to the result of this task
	 * @return new {@link ProgressiveCompletableFuture} that is completed when this task completes
	 * @param <U> type of the result of the next task
	 */
	@Nonnull
	public <U> ProgressiveCompletableFuture<U> andThen(@Nonnull Function<T, ProgressiveCompletableFuture<U>> nextStage) {
		final ProgressiveCompletableFuture<U> combinedFuture = new ProgressiveCompletableFuture<>();
		this.addProgressListener(progress -> combinedFuture.combineProgressOfTwoSequentialParts(progress, 1))
			.whenComplete((t, throwable) -> {
			if (throwable != null) {
				combinedFuture.completeExceptionally(throwable);
			} else {
				final ProgressiveCompletableFuture<U> nextFuture = nextStage.apply(t);
				nextFuture
					.addProgressListener(progress -> combinedFuture.combineProgressOfTwoSequentialParts(progress, 2))
						.whenComplete((u, throwable1) -> {
							if (throwable1 != null) {
								combinedFuture.completeExceptionally(throwable1);
							} else {
								combinedFuture.complete(u);
							}
						});
			}
		});
		return combinedFuture;
	}

	/**
	 * The second part is expected to start when the first part is finished. The progress of the second part is combined
	 * with the progress of the first part.
	 * @param progress progress of the particular part
	 * @param part order number of the part
	 */
	private void combineProgressOfTwoSequentialParts(int progress, int part) {
		this.updateProgress(((part - 1) * 50) + progress / 2);
	}

	/**
	 * Adds a listener that is notified when the progress of the task changes.
	 *
	 * @param listener listener that is notified when the progress of the task changes
	 * @return this instance
	 */
	@Nonnull
	ProgressiveCompletableFuture<T> addProgressListener(@Nonnull IntConsumer listener) {
		progressListeners.add(listener);
		return this;
	}
}
