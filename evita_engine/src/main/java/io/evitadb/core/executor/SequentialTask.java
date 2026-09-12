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
import io.evitadb.api.task.TaskStatus.TaskTrait;
import io.evitadb.utils.Assert;
import io.evitadb.utils.UUIDUtil;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.Arrays;
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
	 * The steps executed in sequence by {@link #execute()}, in declaration order. The last step's result becomes
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

	/**
	 * Unions the traits of every step into a single set.
	 *
	 * Built by accumulation rather than by {@link EnumSet#copyOf(java.util.Collection)}, which refuses an empty
	 * collection - a sequence whose steps happen to declare no traits at all is legal and must not blow up in
	 * a constructor.
	 *
	 * @param steps the steps whose traits to union
	 * @return the union, possibly empty
	 */
	@Nonnull
	private static EnumSet<TaskTrait> collectTraits(@Nonnull ServerTask<?, ?>[] steps) {
		final EnumSet<TaskTrait> traits = EnumSet.noneOf(TaskTrait.class);
		for (ServerTask<?, ?> step : steps) {
			traits.addAll(step.getStatus().traits());
		}
		return traits;
	}

	public SequentialTask(@Nullable String catalogName, @Nonnull String taskName, @Nonnull ServerTask<?, ?> step1, @Nonnull ServerTask<?, T> step2) {
		this(
			catalogName,
			step1.getStatus().taskType() + ", " + step2.getStatus().taskType(),
			taskName,
			step1, step2
		);
	}

	/**
	 * Creates a sequence of an arbitrary number of steps under an explicitly stated task type.
	 *
	 * The task type is named rather than derived from the steps, because a sequence assembled from several
	 * heterogeneous steps is one *operation* as far as a client is concerned - and
	 * {@link io.evitadb.api.EvitaManagementContract#listTaskStatuses} filters by that type. A type concatenated
	 * from the steps changes whenever the composition does, which would silently break every client filtering
	 * on it.
	 *
	 * The result of the **last** step becomes the result of this task, so that step must produce `T`. This cannot
	 * be expressed in a varargs signature and is therefore checked at runtime by {@link #execute()}, which casts
	 * the last step's result - the two-step constructor above is the type-safe form and stays the one to prefer
	 * where a sequence really is two steps.
	 *
	 * @param catalogName name of the catalog this sequence operates on, or `null` when it is instance-wide
	 * @param taskType    stable type identifier clients filter by
	 * @param taskName    human-readable name displayed to clients
	 * @param steps       the steps to execute, in order; at least one is required
	 */
	public SequentialTask(
		@Nullable String catalogName,
		@Nonnull String taskType,
		@Nonnull String taskName,
		@Nonnull ServerTask<?, ?>... steps
	) {
		Assert.isPremiseValid(steps.length > 0, "At least one step is required to form a sequential task!");
		this.taskName = taskName;
		this.status = new AtomicReference<>(
			new TaskStatus<>(
				taskType,
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
				collectTraits(steps)
			)
		);
		this.currentStep = new AtomicReference<>();
		this.steps = Arrays.copyOf(steps, steps.length, ServerTask[].class);
		this.futureResult = new CompletableFuture<>();
	}

	@Nonnull
	@Override
	public TaskStatus<Void, T> getStatus() {
		// summed, not OR-ed: the steps' percentages are numbers, and `|` is not addition. Two steps at 100 % and
		// 50 % used to report `(100 | 50) / 2` = 59 % instead of 75 %, and the error grows with the step count -
		// it only stayed invisible while the common case was `100 | 0`, where OR and addition happen to agree.
		// Covered by SequentialTaskTest#shouldAverageStepProgressRatherThanOrItTogether
		int overallProgress = 0;
		for (Task<?, ?> step : this.steps) {
			overallProgress += step.getStatus().progress();
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
				// `complete` is the arbiter of the one window the guard above cannot see - a cancel landing after
				// that check has already passed. It returns false there, having lost to `cancel()`. The status
				// transition must therefore follow the future instead of running unconditionally:
				// `transitionToFinished` does not inspect the state it replaces, so a cancel arriving here used to be
				// stamped back to FINISHED, reporting a real result on a task whose own future says cancelled - the
				// same future/status mismatch this class's cancellation guard exists to rule out. `cancel()` has
				// already recorded the CancellationException, so nothing needs stamping on this path.
				// Covered by SequentialTaskTest#shouldNotReportFinishedWhenCancelLandedAfterLastStep
				if (!this.futureResult.complete(theFinalResult)) {
					return null;
				}

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
