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
import io.evitadb.api.task.Task;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.api.task.TaskStatus.TaskSimplifiedState;
import io.evitadb.utils.UUIDUtil;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * This task ensures that all the steps are executed in a sequence. It is a thin wrapper around {@link Task} that
 * executes a sequence of tasks in a single background task and translates the progress of the steps to the overall
 * progress of the task.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class SequentialTask<T> implements ServerTask<Void, T> {
	@Getter private final String taskName;
	private final AtomicReference<TaskStatus<Void, T>> status;
	private final ServerTask<?, ?>[] steps;
	private final AtomicReference<Task<?, ?>> currentStep;
	private final CompletableFuture<T> futureResult;

	public SequentialTask(@Nullable String catalogName, @Nonnull String taskName, @Nonnull ServerTask<?, ?> step1, @Nonnull ServerTask<?, T> step2) {
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
				null,
				0,
				null,
				null,
				null,
				null,
				EnumSet.copyOf(
					Stream.concat(
						step1.getStatus().traits().stream(),
						step2.getStatus().traits().stream()
					).toList()
				)
			)
		);
		this.currentStep = new AtomicReference<>();
		this.steps = new ServerTask[]{step1, step2};
		this.futureResult = new CompletableFuture<>();
	}

	@Nonnull
	@Override
	public TaskStatus<Void, T> getStatus() {
		int overallProgress = 0;
		for (Task<?, ?> step : this.steps) {
			overallProgress |= step.getStatus().progress();
		}
		final int newProgress = overallProgress / this.steps.length;
		final TaskStatus<Void, T> currentStatus = this.status.get();
		return currentStatus.simplifiedState() != TaskSimplifiedState.RUNNING ||
			currentStatus.progress() == newProgress ?
				currentStatus :
				this.status.updateAndGet(current -> current.updateProgress(newProgress));
	}

	/**
	 * Transitions the task to the issued state.
	 */
	@Override
	public void transitionToIssued() {
		this.status.updateAndGet(TaskStatus::transitionToIssued);
		for (ServerTask<?, ?> step : this.steps) {
			step.transitionToIssued();
		}
	}

	@Override
	public boolean matches(@Nonnull Predicate<ServerTask<?, ?>> taskPredicate) {
		return taskPredicate.test(this) || Stream.of(this.steps).anyMatch(taskPredicate);
	}

	@Nonnull
	@Override
	public CompletableFuture<T> getFutureResult() {
		return this.futureResult;
	}

	@Nullable
	@Override
	public T execute() {
		if (this.status.get().simplifiedState() == TaskSimplifiedState.QUEUED) {
			try {
				this.status.updateAndGet(TaskStatus::transitionToStarted);

				for (ServerTask<?, ?> step : this.steps) {
					if (step.getStatus().simplifiedState() == TaskSimplifiedState.QUEUED) {
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
	public boolean cancel() {
		if (!(this.futureResult.isDone() || this.futureResult.isCancelled())) {
			boolean canceled = false;
			for (Task<?, ?> step : this.steps) {
				//noinspection NonShortCircuitBooleanExpression
				canceled |= step.cancel();
			}
			this.status.updateAndGet(
				current -> current.transitionToFailed(new CancellationException("Task was canceled."))
			);
			this.futureResult.cancel(true);
			return canceled;
		} else {
			return false;
		}
	}

	@Override
	public void fail(@Nonnull Exception exception) {
		if (!(this.futureResult.isDone() || this.futureResult.isCancelled())) {
			for (ServerTask<?, ?> step : this.steps) {
				step.fail(exception);
			}
			this.status.updateAndGet(current -> current.transitionToFailed(exception));
			this.futureResult.completeExceptionally(exception);
		}
	}
}
