/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.api.task.ServerTask;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.api.task.TaskStatus.TaskSimplifiedState;
import io.evitadb.api.task.TaskStatus.TaskTrait;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TASK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link SequentialTask} — the sequence wrapper — with emphasis on what happens when a cancellation lands at
 * a step boundary.
 *
 * Sequences of a length other than two get their own nested class, because the two-step shape hides most of what the
 * general constructor does: with two steps a mean is indistinguishable from a halving, a trait union from "whatever
 * the first step declared", and the stated task type from one concatenated out of the steps.
 *
 * Both cancellation cases run single-threaded on the test thread: a step body that cancels the sequence it belongs to
 * reaches the step-boundary check without a pool or a timing window. The two are not interchangeable — one cancels
 * the sequence (which also cancels every step, so the pre-existing `QUEUED` guard would stop the remaining steps
 * anyway), the other cancels only the sequence's result future and therefore isolates the boundary check itself.
 *
 * Progress aggregation gets its own nested class, and the assertion there is deliberately an exact equality on a
 * pair of values where a sum and a bitwise OR disagree. The aggregation used to be an OR - two steps at 50 % and
 * 100 % reported 59 % - and a "between min and max" assertion would have held for both that and a correct average,
 * which is why it went unnoticed.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(ENGINE)
@Tag(TASK)
@DisplayName("Sequential task")
class SequentialTaskTest {

	/**
	 * Builds a step that records its own execution in the given list.
	 *
	 * Every step carries at least one {@link TaskTrait} because a real one does; the sequence itself tolerates a
	 * trait-less step, and {@link ProgressAggregation} pins that.
	 *
	 * @param name       the step name
	 * @param executions the list each execution appends its name to
	 * @return the step
	 */
	@Nonnull
	private static ClientRunnableTask<Void> recordingStep(@Nonnull String name, @Nonnull List<String> executions) {
		return new ClientRunnableTask<>(
			"step", name, null, () -> executions.add(name), TaskTrait.CAN_BE_CANCELLED
		);
	}

	/**
	 * Builds a step that does nothing but declare the given traits.
	 *
	 * @param name   the step name
	 * @param traits the traits the step declares
	 * @return the step
	 */
	@Nonnull
	private static ClientRunnableTask<Void> traitedStep(@Nonnull String name, @Nonnull TaskTrait... traits) {
		return new ClientRunnableTask<>("step", name, null, () -> {
		}, traits);
	}

	@Nested
	@DisplayName("Execution")
	class Execution {

		@Test
		@DisplayName("runs both steps in declaration order and reports the last step's result")
		void shouldRunBothStepsInDeclarationOrder() {
			final List<String> executions = new ArrayList<>(2);
			final ClientRunnableTask<Void> step1 = recordingStep("first", executions);
			final ClientCallableTask<Void, Integer> step2 = new ClientCallableTask<>(
				"step", "second", null,
				theTask -> {
					executions.add("second");
					return 42;
				},
				TaskTrait.CAN_BE_CANCELLED
			);
			final SequentialTask<Integer> sequence = new SequentialTask<>(null, "Sequence", step1, step2);
			sequence.transitionToIssued();

			assertEquals(42, sequence.execute(), "the sequence must report the last step's result");
			assertEquals(List.of("first", "second"), executions, "the steps ran out of order or not at all");
			assertEquals(TaskSimplifiedState.FINISHED, sequence.getStatus().simplifiedState());
			assertEquals(42, sequence.getFutureResult().getNow(null));
		}
	}

	@Nested
	@DisplayName("Progress aggregation")
	class ProgressAggregation {

		@Test
		@DisplayName("averages the steps' progress rather than OR-ing it together")
		void shouldAverageStepProgressRatherThanOrItTogether() {
			// A sequence only recomputes its progress while it is RUNNING, and it is RUNNING only while a step is
			// executing - so the reading has to be taken from inside one. The first step sets both steps'
			// percentages and samples the sequence on the spot.
			final AtomicReference<SequentialTask<Void>> holder = new AtomicReference<>();
			final AtomicInteger sampledProgress = new AtomicInteger(-1);
			final ClientRunnableTask<Void> step2 = recordingStep("second", new ArrayList<>(1));
			final ClientRunnableTask<Void> step1 = new ClientRunnableTask<>(
				"step", "first", null,
				theTask -> {
					theTask.updateProgress(100);
					step2.updateProgress(50);
					sampledProgress.set(holder.get().getStatus().progress());
				},
				TaskTrait.CAN_BE_CANCELLED
			);
			final SequentialTask<Void> sequence = new SequentialTask<>(null, "type", "Sequence", step1, step2);
			holder.set(sequence);
			sequence.transitionToIssued();

			sequence.execute();

			// 100 and 50 are chosen because a sum and a bitwise OR disagree on them: (100 + 50) / 2 = 75, while
			// (100 | 50) / 2 = 59. Reverting the aggregation to `|=` is what this assertion catches - the common
			// `100 | 0` shape the sequence used to be built in cannot tell the two apart, which is exactly why the
			// defect survived until a wider sequence needed the number to mean something.
			assertEquals(
				75, sampledProgress.get(),
				"the sequence must report the mean of its steps' progress"
			);
		}

		@Test
		@DisplayName("accepts a step that declares no traits at all")
		void shouldAcceptATraitlessStep() {
			final ClientRunnableTask<Void> traitless = new ClientRunnableTask<>(
				"step", "traitless", null, () -> {
				}
			);
			final SequentialTask<Void> sequence = new SequentialTask<>(null, "type", "Sequence", traitless);

			assertTrue(
				sequence.getStatus().traits().isEmpty(),
				"a sequence of trait-less steps must simply carry no traits"
			);
		}
	}

	@Nested
	@DisplayName("Sequences of any length")
	class AnyLengthSequences {

		@Test
		@DisplayName("reports the task type it was given rather than one derived from its steps")
		void shouldReportTheTaskTypeItWasGiven() {
			// the type and the name are adjacent String parameters: transposing them compiles, every other test
			// here still passes, and every monitoring client filtering `listTaskStatuses` by type stops finding
			// this operation
			final SequentialTask<Void> sequence = new SequentialTask<>(
				null, "aType", "A name", recordingStep("only", new ArrayList<>(1))
			);

			assertEquals("aType", sequence.getStatus().taskType());
			assertEquals("A name", sequence.getStatus().taskName());
		}

		@Test
		@DisplayName("runs a single-step sequence and reports that step's result and progress")
		void shouldRunAndReportTheResultOfASingleStepSequence() {
			// a sequence only recomputes its progress while it is RUNNING, so the reading is taken from inside the
			// step - and with one step the mean must be that step's own percentage rather than half of it
			final AtomicReference<SequentialTask<Integer>> holder = new AtomicReference<>();
			final AtomicInteger sampledProgress = new AtomicInteger(-1);
			final ClientCallableTask<Void, Integer> onlyStep = new ClientCallableTask<>(
				"step", "only", null,
				theTask -> {
					theTask.updateProgress(40);
					sampledProgress.set(holder.get().getStatus().progress());
					return 42;
				},
				TaskTrait.CAN_BE_CANCELLED
			);
			final SequentialTask<Integer> sequence = new SequentialTask<>(null, "aType", "A name", onlyStep);
			holder.set(sequence);
			sequence.transitionToIssued();

			assertEquals(42, sequence.execute(), "a one-step sequence must report its only step's result");
			assertEquals(40, sampledProgress.get(), "a one-step sequence must report its only step's progress");
			assertEquals(TaskSimplifiedState.FINISHED, sequence.getStatus().simplifiedState());
		}

		@Test
		@DisplayName("runs three steps in declaration order and reports the last one's result")
		void shouldRunThreeStepsInDeclarationOrderAndReportTheLastStepsResult() {
			final List<String> executions = new ArrayList<>(3);
			final ClientRunnableTask<Void> step1 = recordingStep("first", executions);
			final ClientRunnableTask<Void> step2 = recordingStep("second", executions);
			final ClientCallableTask<Void, String> step3 = new ClientCallableTask<>(
				"step", "third", null,
				theTask -> {
					executions.add("third");
					return "the third step's result";
				},
				TaskTrait.CAN_BE_CANCELLED
			);
			final SequentialTask<String> sequence = new SequentialTask<>(
				null, "aType", "A name", step1, step2, step3
			);
			sequence.transitionToIssued();

			assertEquals("the third step's result", sequence.execute());
			assertEquals(List.of("first", "second", "third"), executions, "the steps ran out of order");
			assertTrue(sequence.matches(task -> task == step3), "the sequence must match its third step");

			// a failure has to reach every step, not merely the two the older constructor could hold
			final ClientRunnableTask<Void> laterStep = recordingStep("third", new ArrayList<>(1));
			final SequentialTask<Void> failing = new SequentialTask<>(
				null, "aType", "A name",
				recordingStep("first", new ArrayList<>(1)), recordingStep("second", new ArrayList<>(1)), laterStep
			);

			failing.fail(new IllegalStateException("boom"));

			assertEquals(TaskSimplifiedState.FAILED, laterStep.getStatus().simplifiedState());
		}

		@Test
		@DisplayName("averages the progress across more than two steps")
		void shouldAverageProgressAcrossMoreThanTwoSteps() {
			// 90, 60 and 30 average to 60 - a number no pair of them reaches, so a divisor hard-wired to two
			// cannot produce it
			final AtomicReference<SequentialTask<Void>> holder = new AtomicReference<>();
			final AtomicInteger sampledProgress = new AtomicInteger(-1);
			final ClientRunnableTask<Void> step2 = recordingStep("second", new ArrayList<>(1));
			final ClientRunnableTask<Void> step3 = recordingStep("third", new ArrayList<>(1));
			final ClientRunnableTask<Void> step1 = new ClientRunnableTask<>(
				"step", "first", null,
				theTask -> {
					theTask.updateProgress(90);
					step2.updateProgress(60);
					step3.updateProgress(30);
					sampledProgress.set(holder.get().getStatus().progress());
				},
				TaskTrait.CAN_BE_CANCELLED
			);
			final SequentialTask<Void> sequence = new SequentialTask<>(
				null, "aType", "A name", step1, step2, step3
			);
			holder.set(sequence);
			sequence.transitionToIssued();

			sequence.execute();

			assertEquals(60, sampledProgress.get(), "the sequence must divide by the number of steps it holds");
		}

		@Test
		@DisplayName("carries the union of every step's traits")
		void shouldUnionTheTraitsOfEveryStep() {
			// every step of every other sequence here declares the same single trait, so a union is
			// indistinguishable from "whatever the first step declared" - including in production, where both real
			// steps declare the same pair
			final ClientRunnableTask<Void> startable = traitedStep("startable", TaskTrait.CAN_BE_STARTED);
			final ClientRunnableTask<Void> cancellable = traitedStep("cancellable", TaskTrait.CAN_BE_CANCELLED);

			final SequentialTask<Void> sequence = new SequentialTask<>(
				null, "aType", "A name", startable, cancellable
			);

			assertEquals(
				EnumSet.of(TaskTrait.CAN_BE_STARTED, TaskTrait.CAN_BE_CANCELLED),
				sequence.getStatus().traits(),
				"the sequence must offer every action any of its steps offers"
			);
		}

		@Test
		@DisplayName("refuses a sequence holding no steps at all")
		void shouldRefuseASequenceWithNoSteps() {
			// load-bearing rather than cosmetic: `getStatus()` divides by the step count unconditionally, so an
			// empty sequence would turn every status read into an ArithmeticException
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new SequentialTask<Void>(null, "aType", "A name")
			);
		}

		@Test
		@DisplayName("leaves the last step's type to the caller instead of checking it")
		void shouldLeaveTheLastStepsTypeToTheCaller() {
			final ClientCallableTask<Void, String> typedStep = new ClientCallableTask<>(
				"step", "typed", null, theTask -> "not a number", TaskTrait.CAN_BE_CANCELLED
			);
			final SequentialTask<Integer> sequence = new SequentialTask<>(null, "aType", "A name", typedStep);
			sequence.transitionToIssued();

			// erasure removes the `(T)` cast in `execute()`, so ordering the steps so the last one produces `T` is
			// the caller's obligation and nothing else's - which is exactly what the varargs constructor documents.
			// This pins that contract: a mis-ordered sequence completes normally and hands the wrong object back,
			// and the ClassCastException surfaces at whichever caller first uses the value as `T`, or never at all
			// when `T` is `Void` and the value is only joined for its completion. Anyone adding a runtime check
			// here - it would need a `Class<T>` to check against - must correct that javadoc in the same change.
			final SequentialTask<?> erased = sequence;
			assertEquals("not a number", erased.execute());
			assertEquals(TaskSimplifiedState.FINISHED, sequence.getStatus().simplifiedState());
		}
	}

	@Nested
	@DisplayName("Step boundary cancellation")
	class StepBoundaryCancellation {

		@Test
		@DisplayName("stops at the step boundary when the whole task was cancelled")
		void shouldStopAtStepBoundaryWhenParentTaskCancelled() {
			final AtomicReference<SequentialTask<Integer>> holder = new AtomicReference<>();
			final AtomicBoolean secondStepRan = new AtomicBoolean(false);
			final ClientRunnableTask<Void> step1 = new ClientRunnableTask<>(
				"step", "first", null, () -> holder.get().cancel(), TaskTrait.CAN_BE_CANCELLED
			);
			final ClientCallableTask<Void, Integer> step2 = new ClientCallableTask<>(
				"step", "second", null,
				theTask -> {
					secondStepRan.set(true);
					return 42;
				},
				TaskTrait.CAN_BE_CANCELLED
			);
			final SequentialTask<Integer> sequence = new SequentialTask<>(null, "Sequence", step1, step2);
			holder.set(sequence);
			sequence.transitionToIssued();

			// cancel() cancels every step too, so the pre-existing QUEUED guard would also have stopped step 2 here -
			// this case pins the whole-task route rather than the boundary break, and the status it asserts is the one
			// cancel() itself stamped. The case below isolates the break.
			assertNull(sequence.execute(), "a cancelled sequence must report no result");
			assertFalse(secondStepRan.get(), "the second step ran after the sequence was cancelled");
			assertTrue(sequence.getFutureResult().isCancelled(), "the result future was not cancelled");

			final TaskStatus<Void, Integer> status = sequence.getStatus();
			assertEquals(TaskSimplifiedState.FAILED, status.simplifiedState());
			assertEquals("Task was cancelled.", status.publicExceptionMessage());
			assertTrue(status.exceptionWithStackTrace().startsWith(CancellationException.class.getName()));
		}

		@Test
		@DisplayName("stops at the step boundary when only the result future was cancelled")
		void shouldStopAtStepBoundaryWhenResultFutureCancelledDirectly() {
			final AtomicReference<SequentialTask<Integer>> holder = new AtomicReference<>();
			final AtomicBoolean secondStepRan = new AtomicBoolean(false);
			final ClientRunnableTask<Void> step1 = new ClientRunnableTask<>(
				"step", "first", null,
				() -> holder.get().getFutureResult().cancel(true),
				TaskTrait.CAN_BE_CANCELLED
			);
			final ClientCallableTask<Void, Integer> step2 = new ClientCallableTask<>(
				"step", "second", null,
				theTask -> {
					secondStepRan.set(true);
					return 42;
				},
				TaskTrait.CAN_BE_CANCELLED
			);
			final SequentialTask<Integer> sequence = new SequentialTask<>(null, "Sequence", step1, step2);
			holder.set(sequence);
			sequence.transitionToIssued();

			assertNull(sequence.execute(), "a cancelled sequence must report no result");
			// cancelling the result future directly leaves every step untouched, so step 2 is still QUEUED and the
			// boundary check is the only thing that can stop it
			assertFalse(secondStepRan.get(), "the second step ran after the result future was cancelled");
			assertTrue(sequence.getFutureResult().isCancelled(), "the result future was not cancelled");

			// the reported state must agree with the future: cancelling the result future directly leaves every step
			// untouched, so nothing but the guard on the completion block keeps the sequence from reporting FINISHED
			// with a null result while its own future is cancelled
			final TaskStatus<Void, Integer> status = sequence.getStatus();
			assertEquals(
				TaskSimplifiedState.FAILED, status.simplifiedState(),
				"a sequence whose result future is cancelled reported a state other than FAILED"
			);
			assertEquals("Task was cancelled.", status.publicExceptionMessage());
			assertTrue(status.exceptionWithStackTrace().startsWith(CancellationException.class.getName()));
		}

		@Test
		@DisplayName("does not report FINISHED when the cancel landed after the last step completed")
		void shouldNotReportFinishedWhenCancelLandedAfterLastStep() {
			// the one window the post-loop guard cannot see: the cancel arrives after that check has already passed.
			// `complete()` then correctly no-ops on the cancelled future, but the status transition that follows it
			// was unconditional, stamping FINISHED with a real result over a future that reports cancelled.
			final AtomicReference<SequentialTask<Integer>> holder = new AtomicReference<>();
			final ClientRunnableTask<Void> step1 = recordingStep("first", new ArrayList<>(1));
			// the seam: SequentialTask reads the last step's future exactly once, between the post-loop cancellation
			// guard and `futureResult.complete(...)` - nothing else in the engine calls a step's getFutureResult()
			// during execution. Cancelling from that read lands the cancel inside the window deterministically and on
			// a single thread, where a second thread could only sweep for it probabilistically.
			final ClientCallableTask<Void, Integer> step2 = new ClientCallableTask<Void, Integer>(
				"step", "second", null, theTask -> 42, TaskTrait.CAN_BE_CANCELLED
			) {
				@Nonnull
				@Override
				public CompletableFuture<Integer> getFutureResult() {
					holder.get().cancel();
					return super.getFutureResult();
				}
			};
			final SequentialTask<Integer> sequence = new SequentialTask<>(null, "Sequence", step1, step2);
			holder.set(sequence);
			sequence.transitionToIssued();

			assertNull(sequence.execute(), "a sequence cancelled before it completed must report no result");
			assertTrue(sequence.getFutureResult().isCancelled(), "the result future was not cancelled");

			final TaskStatus<Void, Integer> status = sequence.getStatus();
			assertEquals(
				TaskSimplifiedState.FAILED, status.simplifiedState(),
				"the sequence reported FINISHED while its own future reports cancelled"
			);
			assertEquals("Task was cancelled.", status.publicExceptionMessage());
			assertTrue(status.exceptionWithStackTrace().startsWith(CancellationException.class.getName()));
		}
	}

	@Nested
	@DisplayName("Lifecycle")
	class Lifecycle {

		@Test
		@DisplayName("does not execute a task that was never issued")
		void shouldNotExecuteTaskThatWasNeverIssued() {
			final List<String> executions = new ArrayList<>(2);
			final SequentialTask<Void> sequence = new SequentialTask<>(
				null, "Sequence", recordingStep("first", executions), recordingStep("second", executions)
			);

			assertNull(sequence.execute(), "a task that is not QUEUED must not execute");
			assertTrue(executions.isEmpty(), "steps ran for a task that was never issued");
		}

		@Test
		@DisplayName("refuses to cancel an already completed task")
		void shouldRefuseToCancelAlreadyCompletedTask() {
			final List<String> executions = new ArrayList<>(2);
			final SequentialTask<Void> sequence = new SequentialTask<>(
				null, "Sequence", recordingStep("first", executions), recordingStep("second", executions)
			);
			sequence.transitionToIssued();
			sequence.execute();

			assertFalse(sequence.cancel(), "cancelling a completed sequence must answer false");
		}

		@Test
		@DisplayName("propagates a failure to every step")
		void shouldPropagateFailureToEveryStep() {
			final List<String> executions = new ArrayList<>(2);
			final ClientRunnableTask<Void> step1 = recordingStep("first", executions);
			final ClientRunnableTask<Void> step2 = recordingStep("second", executions);
			final SequentialTask<Void> sequence = new SequentialTask<>(null, "Sequence", step1, step2);

			sequence.fail(new IllegalStateException("boom"));

			assertEquals(TaskSimplifiedState.FAILED, sequence.getStatus().simplifiedState());
			assertEquals(TaskSimplifiedState.FAILED, step1.getStatus().simplifiedState());
			assertEquals(TaskSimplifiedState.FAILED, step2.getStatus().simplifiedState());
			assertTrue(sequence.getFutureResult().isCompletedExceptionally());
		}

		@Test
		@DisplayName("matches itself and any of its steps")
		void shouldMatchItselfAndAnyStep() {
			final List<String> executions = new ArrayList<>(2);
			final ClientRunnableTask<Void> step1 = recordingStep("first", executions);
			final ClientRunnableTask<Void> step2 = recordingStep("second", executions);
			final SequentialTask<Void> sequence = new SequentialTask<>(null, "Sequence", step1, step2);

			assertTrue(sequence.matches(task -> task == sequence), "the sequence must match itself");
			assertTrue(sequence.matches(task -> task == step1), "the sequence must match its first step");
			assertTrue(sequence.matches(task -> task == step2), "the sequence must match its second step");
			assertFalse(sequence.matches(task -> false), "the sequence matched a predicate nothing satisfies");
		}

		@Test
		@DisplayName("cancels a task that never received an execution handle")
		void shouldCancelTaskWithoutExecutionHandle() {
			final List<String> executions = new ArrayList<>(2);
			final SequentialTask<Void> sequence = new SequentialTask<>(
				null, "Sequence", recordingStep("first", executions), recordingStep("second", executions)
			);

			assertTrue(sequence.cancel(), "cancelling a fresh sequence must answer true");
			assertTrue(sequence.getFutureResult().isCancelled(), "the result future was not cancelled");
		}
	}

	@Nested
	@DisplayName("Through the scheduler")
	class ThroughScheduler {
		private final Scheduler scheduler = new Scheduler(
			ThreadPoolOptions
				.serviceThreadPoolBuilder()
				.build()
		);

		@AfterEach
		void tearDown() {
			this.scheduler.shutdownNow();
		}

		@Test
		@DisplayName("interrupts the in-flight step when the sequence is cancelled")
		void shouldInterruptInFlightStepWhenSequentialTaskCancelled() throws InterruptedException {
			// covers two otherwise untested things at once: the executor handle attachment on SequentialTask, and the
			// executorService.submit(task::execute) branch of Scheduler#submitTaskInQueue - SequentialTask implements
			// ServerTask but, unlike ClientCallableTask, not Callable
			final CountDownLatch started = new CountDownLatch(1);
			final CountDownLatch interrupted = new CountDownLatch(1);
			final ClientRunnableTask<Void> step1 = new ClientRunnableTask<>(
				"step", "first", null,
				() -> {
					started.countDown();
					try {
						new CountDownLatch(1).await(30, TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						interrupted.countDown();
						// a Runnable body cannot rethrow the checked exception - wrapping it is what lets the test
						// observe the real unwind path
						throw new GenericEvitaInternalError("Step interrupted.", "Step interrupted.", e);
					}
				},
				TaskTrait.CAN_BE_CANCELLED
			);
			final List<String> executions = new ArrayList<>(1);
			final SequentialTask<Void> sequence = new SequentialTask<>(
				null, "Sequence", step1, recordingStep("second", executions)
			);

			this.scheduler.submit((ServerTask<?, ?>) sequence);

			assertTrue(started.await(30, TimeUnit.SECONDS), "the first step never started");

			final PaginatedList<TaskStatus<?, ?>> statuses = this.scheduler.listTaskStatuses(1, 20, null);
			assertEquals(1, statuses.getTotalRecordCount());
			this.scheduler.cancelTask(statuses.getData().get(0).taskId());

			assertTrue(
				interrupted.await(30, TimeUnit.SECONDS),
				"the in-flight step was never interrupted - the sequence retained no executor handle"
			);
		}
	}

}
