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

package io.evitadb.core.async;

import io.evitadb.api.task.ProgressiveCompletableFuture;
import io.evitadb.core.metric.event.system.BackgroundTaskFinishedEvent;
import io.evitadb.core.metric.event.system.BackgroundTaskStartedEvent;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.function.Function;

/**
 * Represents a task that is executed in the background. This is a thin wrapper around {@link Runnable} that emits
 * observability events before and after the task is executed.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
abstract class AbstractBackgroundTask<V> {
	/**
	 * The name of the catalog that the task belongs to (may be NULL if the task is not bound to any particular catalog).
	 */
	private final String catalogName;
	/**
	 * The name of the task.
	 */
	@Getter
	private final String taskName;
	/**
	 * The exception handler that is called when an exception is thrown during the task execution.
	 * When the exception handler doesn't throw exception and instead returns a compatible value it's considered as a
	 * successful handling of the exception and value is returned as a result of the task.
	 */
	private final Function<Throwable, V> exceptionHandler;
	/**
	 * This future can be returned to a client to join the future in its pipeline.
	 */
	private final ProgressiveCompletableFuture<V> future;

	public AbstractBackgroundTask(@Nonnull String taskName) {
		this.catalogName = null;
		this.taskName = taskName;
		this.future = new ProgressiveCompletableFuture<>();
		this.exceptionHandler = null;
	}

	public AbstractBackgroundTask(@Nonnull String catalogName, @Nonnull String taskName) {
		this.catalogName = catalogName;
		this.taskName = taskName;
		this.future = new ProgressiveCompletableFuture<>();
		this.exceptionHandler = null;
	}

	public AbstractBackgroundTask(@Nonnull String taskName, @Nonnull Function<Throwable, V> exceptionHandler) {
		this.catalogName = null;
		this.taskName = taskName;
		this.future = new ProgressiveCompletableFuture<>();
		this.exceptionHandler = exceptionHandler;
	}

	public AbstractBackgroundTask(@Nonnull String catalogName, @Nonnull String taskName, @Nonnull Function<Throwable, V> exceptionHandler) {
		this.catalogName = catalogName;
		this.taskName = taskName;
		this.future = new ProgressiveCompletableFuture<>();
		this.exceptionHandler = exceptionHandler;
	}

	/**
	 * Returns the future of the task.
	 * @return the future of the task
	 */
	@Nonnull
	public ProgressiveCompletableFuture<V> getFuture() {
		return future;
	}

	/**
	 * Returns the ID of the task.
	 * @return the ID of the task
	 */
	@Nonnull
	public UUID getId() {
		return this.future.getId();
	}

	/**
	 * Returns progress of the task.
	 * @return progress of the task in percents
	 */
	public int getProgress() {
		return this.future.getProgress();
	}

	/**
	 * Method updates the progress of the task.
	 * @param progress new progress of the task in percents
	 */
	public void updateProgress(int progress) {
		this.future.updateProgress(progress);
	}

	/**
	 * Executes the task and emits the start and finish events.
	 *
	 * @return the result of the task
	 */
	public final V execute() {
		// emit the start event
		new BackgroundTaskStartedEvent(this.catalogName, this.taskName).commit();

		// init progress information
		updateProgress(0);

		// prepare the finish event
		final BackgroundTaskFinishedEvent finishedEvent = new BackgroundTaskFinishedEvent(catalogName, taskName);
		try {
			final V result = this.executeInternal();
			this.future.complete(result);
			return result;
		} catch (Throwable e) {
			if (this.exceptionHandler != null) {
				try {
					final V defaultResult = this.exceptionHandler.apply(e);
					this.future.complete(defaultResult);
					return defaultResult;
				} catch (Throwable e2) {
					this.future.completeExceptionally(e2);
					throw e2;
				}
			} else {
				this.future.completeExceptionally(e);
				throw e;
			}
		} finally {
			// emit the finish event
			finishedEvent.finish().commit();
		}
	}

	/**
	 * Executes the task logic.
	 * @return the result of the task
	 */
	protected abstract V executeInternal();

}
