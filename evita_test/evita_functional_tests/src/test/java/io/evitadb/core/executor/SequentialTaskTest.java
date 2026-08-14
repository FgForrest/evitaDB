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
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TASK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link SequentialTask} — the two-step sequence wrapper — with emphasis on what happens when a cancellation
 * lands at a step boundary.
 *
 * Both cancellation cases run single-threaded on the test thread: a step body that cancels the sequence it belongs to
 * reaches the step-boundary check without a pool or a timing window. The two are not interchangeable — one cancels
 * the sequence (which also cancels every step, so the pre-existing `QUEUED` guard would stop the remaining steps
 * anyway), the other cancels only the sequence's result future and therefore isolates the boundary check itself.
 *
 * No assertion in this class touches {@link SequentialTask#getStatus()}'s progress value: it aggregates step progress
 * with a bitwise OR rather than a sum, so two steps at 50 % and 100 % report 59 %. A "between min and max" assertion
 * would hold for both that and a correct average, and would read to the next person as deliberate coverage of
 * behaviour nobody actually asserted.
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
	 * Every step carries at least one {@link TaskTrait}, because {@link SequentialTask}'s constructor funnels the union
	 * of its steps' traits through `EnumSet.copyOf(Collection)`, which rejects an empty collection.
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
