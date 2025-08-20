/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.core.executor;

import io.evitadb.api.task.ServerTask;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.api.task.TaskStatus.TaskSimplifiedState;
import io.evitadb.api.task.TaskStatus.TaskTrait;
import io.evitadb.core.metric.event.system.BackgroundTaskFinishedEvent;
import io.evitadb.core.metric.event.system.BackgroundTaskStartedEvent;
import io.evitadb.utils.UUIDUtil;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Represents a task that is executed in the background. This is a thin wrapper around {@link Runnable} that emits
 * observability events before and after the task is executed.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
abstract class AbstractServerTask<S, T> implements ServerTask<S, T> {
	/**
	 * The exception handler that is called when an exception is thrown during the task execution.
	 * When the exception handler doesn't throw exception and instead returns a compatible value it's considered as a
	 * successful handling of the exception and value is returned as a result of the task.
	 */
	protected final Function<Throwable, T> exceptionHandler;
	/**
	 * This future can be returned to a client to join the future in its pipeline.
	 */
	protected final CompletableFuture<T> future;
	/**
	 * Contains the actual status of the task.
	 */
	protected final AtomicReference<TaskStatus<S, T>> status;
	/**
	 * The type of the task.
	 */
	protected final String taskType;

	protected AbstractServerTask(@Nonnull String taskType, @Nonnull String taskName, @Nonnull S settings, @Nonnull TaskTrait... traits) {
		this.taskType = taskType;
		this.future = new ServerTaskCompletableFuture<>();
		this.status = new AtomicReference<>(
			new TaskStatus<>(
				this.taskType,
				taskName,
				UUIDUtil.randomUUID(),
				null,
				OffsetDateTime.now(),
				null,
				null,
				null,
				0,
				settings,
				null,
				null,
				null,
				traits.length == 0 ? EnumSet.noneOf(TaskTrait.class) : EnumSet.copyOf(Arrays.asList(traits))
			)
		);
		this.exceptionHandler = null;
	}

	protected AbstractServerTask(@Nonnull String catalogName, @Nonnull String taskType, @Nonnull String taskName, @Nonnull S settings, @Nonnull TaskTrait... traits) {
		this.taskType = taskType;
		this.future = new ServerTaskCompletableFuture<>();
		this.status = new AtomicReference<>(
			new TaskStatus<>(
				this.taskType,
				taskName,
				UUIDUtil.randomUUID(),
				catalogName,
				OffsetDateTime.now(),
				null,
				null,
				null,
				0,
				settings,
				null,
				null,
				null,
				traits.length == 0 ? EnumSet.noneOf(TaskTrait.class) : EnumSet.copyOf(Arrays.asList(traits))
			)
		);
		this.exceptionHandler = null;
	}

	protected AbstractServerTask(@Nonnull String taskType, @Nonnull String taskName, @Nonnull S settings, @Nonnull Function<Throwable, T> exceptionHandler, @Nonnull TaskTrait... traits) {
		this.taskType = taskType;
		this.future = new ServerTaskCompletableFuture<>();
		this.status = new AtomicReference<>(
			new TaskStatus<>(
				this.taskType,
				taskName,
				UUIDUtil.randomUUID(),
				null,
				OffsetDateTime.now(),
				null,
				null,
				null,
				0,
				settings,
				null,
				null,
				null,
				traits.length == 0 ? EnumSet.noneOf(TaskTrait.class) : EnumSet.copyOf(Arrays.asList(traits))
			)
		);
		this.exceptionHandler = exceptionHandler;
	}

	protected AbstractServerTask(@Nonnull String catalogName, @Nonnull String taskType, @Nonnull String taskName, @Nonnull S settings, @Nonnull Function<Throwable, T> exceptionHandler, @Nonnull TaskTrait... traits) {
		this.taskType = taskType;
		this.future = new ServerTaskCompletableFuture<>();
		this.status = new AtomicReference<>(
			new TaskStatus<>(
				this.taskType,
				taskName,
				UUIDUtil.randomUUID(),
				catalogName,
				OffsetDateTime.now(),
				null,
				null,
				null,
				0,
				settings,
				null,
				null,
				null,
				traits.length == 0 ? EnumSet.noneOf(TaskTrait.class) : EnumSet.copyOf(Arrays.asList(traits))
			)
		);
		this.exceptionHandler = exceptionHandler;
	}

	@Nonnull
	@Override
	public TaskStatus<S, T> getStatus() {
		return this.status.get();
	}

	@Nonnull
	@Override
	public CompletableFuture<T> getFutureResult() {
		return this.future;
	}

	@Override
	public boolean cancel() {
		if (this.future.isDone() || this.future.isCancelled()) {
			return false;
		} else {
			return this.future.cancel(true);
		}
	}

	@Override
	@Nullable
	public final T execute() {
		// emit the start event
		final TaskStatus<S, T> theStatus = getStatus();

		if (theStatus.simplifiedState() == TaskSimplifiedState.QUEUED) {
			new BackgroundTaskStartedEvent(theStatus.catalogName(), theStatus.taskName()).commit();

			this.status.updateAndGet(
				TaskStatus::transitionToStarted
			);

			// prepare the finish event
			final BackgroundTaskFinishedEvent finishedEvent = new BackgroundTaskFinishedEvent(theStatus.catalogName(), theStatus.taskName());
			try {
				return executeAndCompleteFuture();
			} catch (Throwable e) {
				log.error("Task failed: {}", theStatus.taskName(), e);
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
	 *
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
	 * Updates the name of the current task if it is still in progress.
	 *
	 * @param taskName The new name to be assigned to the task. Must not be null.
	 * @param traits   The traits of the task.
	 */
	public void updateTaskNameAndTraits(@Nonnull String taskName, @Nonnull TaskTrait... traits) {
		if (!(this.future.isDone() || this.future.isCancelled())) {
			this.status.updateAndGet(
				currentStatus -> currentStatus.updateTaskNameAndTraits(taskName, traits)
			);
		}
	}

	@Override
	public void transitionToIssued() {
		if (!(this.future.isDone() || this.future.isCancelled())) {
			this.status.updateAndGet(
				TaskStatus::transitionToIssued
			);
		}
	}

	@Override
	public boolean matches(@Nonnull Predicate<ServerTask<?, ?>> taskPredicate) {
		return taskPredicate.test(this);
	}

	/**
	 * Executes the task and completes the future with the result.
	 *
	 * @return the result of the task
	 */
	@Nonnull
	protected T executeAndCompleteFuture() {
		final T result = this.executeInternal();
		if (this.future.isDone()) {
			return this.future.getNow(null);
		} else {
			this.status.updateAndGet(
				currentStatus -> currentStatus.transitionToFinished(result)
			);
			this.future.complete(result);
			return result;
		}
	}

	/**
	 * Executes the task logic.
	 *
	 * @return the result of the task
	 */
	@Nonnull
	protected abstract T executeInternal();

	/**
	 * This class is used to keep {@link ServerTask} alive as long as someone keeps a reference to the future. Task
	 * must not be ever garbage collected while the future is still referenced. That's why this inner class is not
	 * static.
	 */
	@SuppressWarnings("InnerClassMayBeStatic")
	private class ServerTaskCompletableFuture<X> extends CompletableFuture<X> {

		@Override
		public boolean cancel(boolean mayInterruptIfRunning) {
			final boolean cancelled = super.cancel(mayInterruptIfRunning);
			if (cancelled) {
				AbstractServerTask.this.status.updateAndGet(
					currentStatus -> currentStatus.transitionToFailed(
						new CancellationException("Task was canceled.")
					)
				);
			}
			return cancelled;
		}
	}

}
