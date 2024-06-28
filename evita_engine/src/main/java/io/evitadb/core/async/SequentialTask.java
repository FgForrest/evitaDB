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
import io.evitadb.utils.UUIDUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Optional.ofNullable;

/**
 * This task ensures that all the steps are executed in a sequence. It is a thin wrapper around {@link Task} that
 * executes a sequence of tasks in a single background task and translates the progress of the steps to the overall
 * progress of the task.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class SequentialTask<T> implements Task<Void, T> {
	private final String taskName;
	private final AtomicReference<TaskStatus<Void, T>> status;
	private final Task<?, ?>[] steps;
	private final AtomicReference<Task<?, ?>> currentStep;
	private final CompletableFuture<T> futureResult;

	public SequentialTask(@Nullable String catalogName, @Nonnull String taskName, @Nonnull Task<?, ?> step1, @Nonnull Task<?, T> step2) {
		this.taskName = taskName;
		this.status = new AtomicReference<>(
			new TaskStatus<>(
				step1.getStatus().taskType() + ", " + step2.getStatus().taskType(),
				taskName,
				UUIDUtil.randomUUID(),
				catalogName,
				OffsetDateTime.now(),
				null,
				null,
				0,
				null,
				null,
				null
			)
		);
		this.currentStep = new AtomicReference<>();
		this.steps = new Task[]{step1, step2};
		this.futureResult = new CompletableFuture<>();
	}

	@Nonnull
	@Override
	public TaskStatus<Void, T> getStatus() {
		int overallProgress = 0;
		for (Task<?, ?> step : steps) {
			overallProgress |= step.getStatus().progress();
		}
		final String newTaskName = this.taskName + ofNullable(this.currentStep.get()).map(it -> " [" + it.getStatus().taskName() + "]").orElse("");
		final int newProgress = overallProgress / this.steps.length;
		final TaskStatus<Void, T> currentStatus = this.status.get();
		return currentStatus.state() != State.RUNNING ||
			currentStatus.progress() == newProgress ||
			!Objects.equals(currentStatus.taskName(), newTaskName) ?
				currentStatus :
				this.status.updateAndGet(current -> current.updateProgress(newProgress));
	}

	@Nonnull
	@Override
	public CompletableFuture<T> getFutureResult() {
		return this.futureResult;
	}

	@Nullable
	@Override
	public T execute() {
		if (this.status.get().state() == State.QUEUED) {
			try {
				this.status.updateAndGet(TaskStatus::transitionToStarted);

				for (Task<?, ?> step : steps) {
					if (step.getStatus().state() == State.QUEUED) {
						this.currentStep.set(step);
						step.execute();
					}
				}
				//noinspection unchecked
				final T theFinalResult = (T) this.steps[this.steps.length - 1]
					.getFutureResult()
					.getNow(null);
				this.futureResult.complete(theFinalResult);

				this.status.updateAndGet(current -> current.transitionToFinished(theFinalResult));

				return theFinalResult;
			} catch (Exception ex) {
				fail(ex);
				throw ex;
			} finally {
				this.currentStep.set(null);
			}
		} else {
			return null;
		}
	}

	@Override
	public void cancel() {
		if (!(this.futureResult.isDone() || this.futureResult.isCancelled())) {
			for (Task<?, ?> step : steps) {
				final State state = step.getStatus().state();
				if (state == State.QUEUED) {
					step.cancel();
				}
			}
			this.status.updateAndGet(
				current -> current.transitionToFailed(new CancellationException("Task was canceled."))
			);
			this.futureResult.cancel(true);
		}
	}

	@Override
	public void fail(@Nonnull Exception exception) {
		if (!(this.futureResult.isDone() || this.futureResult.isCancelled())) {
			for (Task<?, ?> step : steps) {
				final State state = step.getStatus().state();
				if (state == State.QUEUED) {
					step.fail(exception);
				}
			}
			this.status.updateAndGet(current -> current.transitionToFailed(exception));
			this.futureResult.completeExceptionally(exception);
		}
	}
}
