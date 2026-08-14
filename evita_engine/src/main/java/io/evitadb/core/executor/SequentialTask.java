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
import java.util.concurrent.Future;
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
public class SequentialTask<T> implements ServerTask<Void, T>, InterruptibleServerTask {
	/**
	 * The name of this task, as displayed to clients; exposed publicly via the Lombok-generated getter.
	 */
	@Getter private final String taskName;
	/**
	 * The current status of this task, updated atomically as the steps progress and as the task moves between
	 * lifecycle states.
	 */
	private final AtomicReference<TaskStatus<Void, T>> status;
	/**
	 * The two steps executed in sequence by {@link #execute()}, in declaration order. The last step's result becomes
	 * this task's result.
	 */
	private final ServerTask<?, ?>[] steps;
	/**
	 * The step currently being executed, set right before {@link Task#execute()} is invoked on it and cleared again
	 * in {@link #execute()}'s `finally` block - only meaningful while {@link #execute()} is on the call stack.
	 * Nothing in this class currently reads it back; it is written but never queried.
	 */
	private final AtomicReference<Task<?, ?>> currentStep;
	/**
	 * The result of this task, exposed via {@link #getFutureResult()}. A plain {@link CompletableFuture} - unlike
	 * {@link AbstractServerTask}'s status-aware future, cancelling it does not by itself update {@link #status}:
	 * {@link #cancel()} stamps the status directly, and {@link #execute()} reconciles it whenever it observes this
	 * future already cancelled. A direct {@link CompletableFuture#cancel(boolean)} arriving after the sequence has
	 * already finished is never reconciled, because {@link #execute()} does not run again.
	 */
	private final CompletableFuture<T> futureResult;
	/**
	 * The executor's handle for this task, attached by {@link Scheduler#submitTaskInQueue} after submission. The steps
	 * run inline on this task's own worker thread, so interrupting it through this handle is what actually stops the
	 * step currently in flight — see {@link InterruptibleServerTask}.
	 */
	@Nullable private volatile Future<?> executionHandle;

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
					// stop at the first step boundary after a cancellation instead of walking the remaining steps
					if (this.futureResult.isCancelled()) {
						break;
					}
					if (step.getStatus().simplifiedState() == TaskSimplifiedState.QUEUED) {
						this.currentStep.set(step);
						step.execute();
					}
				}
				if (this.futureResult.isCancelled()) {
					// a cancelled sequence must never reach the completion block below. Falling into it is harmless
					// only while `getNow(null)` happens to raise CancellationException on an already-cancelled step;
					// where it does not - a direct `getFutureResult().cancel(true)` leaves every step untouched - it
					// hands back null and `transitionToFinished` records the sequence as FINISHED while this task's
					// own future reports cancelled. Cancellation is stamped here rather than left to that exception,
					// because the project does not route control flow through exceptions.
					this.status.updateAndGet(
						current -> current.simplifiedState() == TaskSimplifiedState.FAILED ?
							current : current.transitionToFailed(new CancellationException("Task was canceled."))
					);
					return null;
				}
				//noinspection unchecked
				final T theFinalResult = (T) this.steps[this.steps.length - 1]
					.getFutureResult()
					.getNow(null);
				this.futureResult.complete(theFinalResult);

				this.status.updateAndGet(current -> current.transitionToFinished(theFinalResult));

				return theFinalResult;
			} catch (Exception ex) {
				if (this.futureResult.isCancelled()) {
					// cancellation unwound the sequence - the requested outcome, not a failure. The status already
					// carries the CancellationException set by cancel(), so leave it alone.
					return null;
				}
				fail(ex);
				throw ex;
			} finally {
				this.currentStep.set(null);
			}
		} else {
			return null;
		}
	}

	/**
	 * Attaches the executor handle that {@link #cancel()} uses to interrupt the worker thread currently executing
	 * this task's steps.
	 *
	 * Called by {@link Scheduler#submitTaskInQueue} right after submission - which means the step loop in
	 * {@link #execute()} may already be running, or even finished, by the time this arrives. {@link #cancel()}
	 * cancels {@link #futureResult} first and only then reads the handle; this method publishes the handle first
	 * and only then re-reads {@link #futureResult}, cancelling the handle if it finds the task already cancelled.
	 * Touching the same two locations in opposite order means at least one side observes the other, so a cancel
	 * racing this call is never lost.
	 *
	 * @param handle the executor's handle for the submitted task
	 */
	@Override
	public void attachExecutionHandle(@Nonnull Future<?> handle) {
		// publish the handle first, then re-read the result future - the mirror image of cancel(), which cancels the
		// result future first and only then reads the handle. Both sides touch the same two volatile locations in
		// opposite order, so at least one of them observes the other and a cancel racing the attachment cannot be lost
		this.executionHandle = handle;
		if (this.futureResult.isCancelled()) {
			handle.cancel(true);
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
			// the steps run inline on this task's worker thread, so only the executor handle can interrupt the step
			// currently in flight - the futures cancelled above cannot (see InterruptibleServerTask)
			final Future<?> handle = this.executionHandle;
			if (handle != null) {
				handle.cancel(true);
			}
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
