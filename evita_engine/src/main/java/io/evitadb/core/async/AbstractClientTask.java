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

import io.evitadb.api.task.Task;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.api.task.TaskStatus.State;
import io.evitadb.core.metric.event.system.BackgroundTaskFinishedEvent;
import io.evitadb.core.metric.event.system.BackgroundTaskStartedEvent;
import io.evitadb.utils.UUIDUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Represents a task that is executed in the background. This is a thin wrapper around {@link Runnable} that emits
 * observability events before and after the task is executed.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
abstract class AbstractClientTask<S, T> implements Task<S, T> {
	/**
	 * Contains the actual status of the task.
	 */
	private final AtomicReference<TaskStatus<S, T>> status;
	/**
	 * The exception handler that is called when an exception is thrown during the task execution.
	 * When the exception handler doesn't throw exception and instead returns a compatible value it's considered as a
	 * successful handling of the exception and value is returned as a result of the task.
	 */
	private final Function<Throwable, T> exceptionHandler;
	/**
	 * This future can be returned to a client to join the future in its pipeline.
	 */
	private final CompletableFuture<T> future;

	public AbstractClientTask(@Nonnull String taskName, @Nullable S settings) {
		this.future = new CompletableFuture<>();
		this.status = new AtomicReference<>(
			new TaskStatus<>(
				this.getClass().getSimpleName(),
				taskName,
				UUIDUtil.randomUUID(),
				null,
				OffsetDateTime.now(),
				null,
				null,
				0,
				settings,
				null,
				null
			)
		);
		this.exceptionHandler = null;
	}

	public AbstractClientTask(@Nonnull String catalogName, @Nonnull String taskName, @Nullable S settings) {
		this.future = new CompletableFuture<>();
		this.status = new AtomicReference<>(
			new TaskStatus<>(
				this.getClass().getSimpleName(),
				taskName,
				UUIDUtil.randomUUID(),
				catalogName,
				OffsetDateTime.now(),
				null,
				null,
				0,
				settings,
				null,
				null
			)
		);
		this.exceptionHandler = null;
	}

	public AbstractClientTask(@Nonnull String taskName, @Nullable S settings, @Nonnull Function<Throwable, T> exceptionHandler) {
		this.future = new CompletableFuture<>();
		this.status = new AtomicReference<>(
			new TaskStatus<>(
				this.getClass().getSimpleName(),
				taskName,
				UUIDUtil.randomUUID(),
				null,
				OffsetDateTime.now(),
				null,
				null,
				0,
				settings,
				null,
				null
			)
		);
		this.exceptionHandler = exceptionHandler;
	}

	public AbstractClientTask(@Nonnull String catalogName, @Nonnull String taskName, @Nullable S settings, @Nonnull Function<Throwable, T> exceptionHandler) {
		this.future = new CompletableFuture<>();
		this.status = new AtomicReference<>(
			new TaskStatus<>(
				this.getClass().getSimpleName(),
				taskName,
				UUIDUtil.randomUUID(),
				catalogName,
				OffsetDateTime.now(),
				null,
				null,
				0,
				settings,
				null,
				null
			)
		);
		this.exceptionHandler = exceptionHandler;
	}

	@Nonnull
	@Override
	public TaskStatus<S, T> getStatus() {
		return status.get();
	}

	@Nonnull
	@Override
	public CompletableFuture<T> getFutureResult() {
		return this.future;
	}

	@Override
	@Nullable
	public final T execute() {
		// emit the start event
		final TaskStatus<S, T> theStatus = getStatus();

		if (theStatus.state() == State.QUEUED) {
			new BackgroundTaskStartedEvent(theStatus.catalogName(), theStatus.taskName()).commit();

			this.status.updateAndGet(
				TaskStatus::transitionToStarted
			);

			// prepare the finish event
			final BackgroundTaskFinishedEvent finishedEvent = new BackgroundTaskFinishedEvent(theStatus.catalogName(), theStatus.taskName());
			try {
				final T result = this.executeInternal();
				if (this.future.isDone() || this.future.isCancelled()) {
					return null;
				} else {
					this.future.complete(result);
					this.status.updateAndGet(
						currentStatus -> currentStatus.transitionToFinished(result)
					);
					return result;
				}
			} catch (Throwable e) {
				this.status.updateAndGet(
					currentStatus -> currentStatus.transitionToFailed(e)
				);
				if (this.exceptionHandler != null) {
					try {
						final T defaultResult = this.exceptionHandler.apply(e);
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
		} else {
			return null;
		}
	}

	@Override
	public void cancel() {
		if (!(this.future.isDone() || this.future.isCancelled())) {
			this.future.cancel(true);
			this.status.updateAndGet(
				currentStatus -> currentStatus.transitionToFailed(
					new CancellationException("Task was canceled.")
				)
			);
		}
	}

	@Override
	public void fail(@Nonnull Exception exception) {
		if (!(this.future.isDone() || this.future.isCancelled())) {
			this.future.completeExceptionally(exception);
			this.status.updateAndGet(
				currentStatus -> currentStatus.transitionToFailed(exception)
			);
		}
	}

	/**
	 * Method updates the progress of the task.
	 * @param progress new progress of the task in percents
	 */
	public void updateProgress(int progress) {
		if (!(this.future.isDone() || this.future.isCancelled())) {
			this.status.updateAndGet(
				currentStatus -> currentStatus.updateProgress(progress)
			);
		}
	}

	/**
	 * Executes the task logic.
	 * @return the result of the task
	 */
	protected abstract T executeInternal();

}
