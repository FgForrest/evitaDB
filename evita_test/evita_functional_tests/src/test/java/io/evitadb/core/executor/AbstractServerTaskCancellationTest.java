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

import io.evitadb.api.task.TaskStatus;
import io.evitadb.api.task.TaskStatus.TaskSimplifiedState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TASK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the early-return-on-cancellation branch of `AbstractServerTask#execute()` — the branch that distinguishes
 * "the caller stopped this on purpose" from "this failed", and that must not run the task's exception handler.
 *
 * Every case here runs single-threaded on the test thread: a task body that cancels its own task reaches both branches
 * without a pool, a latch or a timing window, which makes these the deterministic counterpart to the scheduler-level
 * cancellation tests in {@link SchedulerTest}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(ENGINE)
@Tag(TASK)
@DisplayName("Server task cancellation")
class AbstractServerTaskCancellationTest {

	@Nested
	@DisplayName("Cancelled task")
	class CancelledTask {

		@Test
		@DisplayName("does not invoke the exception handler when the task was cancelled")
		void shouldNotInvokeExceptionHandlerWhenTaskWasCancelled() {
			final AtomicBoolean handlerInvoked = new AtomicBoolean(false);
			// the Function-based constructor deliberately bypasses wrapCallable, so nothing between the body and
			// execute() swallows, logs or rewraps what the body does
			final ClientCallableTask<Void, Integer> task = new ClientCallableTask<Void, Integer>(
				"task", "Self-cancelling task", null,
				theTask -> {
					theTask.cancel();
					// returning normally is the realistic route into the branch: the cancelled result future makes
					// executeAndCompleteFuture's getNow(null) raise the CancellationException that execute() catches
					return 42;
				},
				throwable -> {
					handlerInvoked.set(true);
					return -1;
				}
			);
			// execute() gates on QUEUED and a freshly built status is WAITING_FOR_PRECONDITION
			task.transitionToIssued();

			assertNull(task.execute(), "a cancelled task must report no result");
			assertFalse(handlerInvoked.get(), "the exception handler ran for a deliberately cancelled task");
			assertTrue(task.getFutureResult().isCancelled(), "the result future was not cancelled");

			final TaskStatus<Void, Integer> status = task.getStatus();
			assertEquals(TaskSimplifiedState.FAILED, status.simplifiedState());
			assertEquals(
				"Task was cancelled.", status.publicExceptionMessage(),
				"cancellation was overwritten and reported as an ordinary failure"
			);
			assertTrue(
				status.exceptionWithStackTrace().startsWith(CancellationException.class.getName()),
				"the status carries something other than the CancellationException raised by cancel()"
			);
		}

		@Test
		@DisplayName("invokes the exception handler when the task failed without being cancelled")
		void shouldInvokeExceptionHandlerWhenTaskFailedWithoutCancellation() {
			// the counterfactual that keeps the case above from being a tautology: the same fixture, the same failure
			// route into execute()'s catch, and the handler DOES run because nothing was cancelled
			final AtomicBoolean handlerInvoked = new AtomicBoolean(false);
			final ClientCallableTask<Void, Integer> task = new ClientCallableTask<Void, Integer>(
				"task", "Failing task", null,
				theTask -> {
					throw new IllegalStateException("boom");
				},
				throwable -> {
					handlerInvoked.set(true);
					return -1;
				}
			);
			task.transitionToIssued();

			assertEquals(-1, task.execute(), "the handler's default result was not returned");
			assertTrue(handlerInvoked.get(), "the exception handler never ran for a genuine failure");
			assertFalse(task.getFutureResult().isCancelled(), "the result future must not be cancelled");
			assertTrue(task.getFutureResult().isDone(), "the result future was never completed");

			final TaskStatus<Void, Integer> status = task.getStatus();
			assertEquals(TaskSimplifiedState.FAILED, status.simplifiedState());
			assertTrue(
				status.exceptionWithStackTrace().startsWith(IllegalStateException.class.getName()),
				"the status does not carry the exception the body threw"
			);
		}
	}

	@Nested
	@DisplayName("Task that was never submitted")
	class NeverSubmittedTask {

		@Test
		@DisplayName("cancels a task that never received an execution handle")
		void shouldCancelTaskThatWasNeverSubmitted() {
			// the documented contract of AbstractServerTask#cancel: the executor handle is absent for any task that
			// never went through the scheduler, and cancel() must tolerate that rather than dereference null
			final ClientCallableTask<Void, Integer> task = new ClientCallableTask<Void, Integer>(
				"task", "Never submitted task", null, theTask -> 42
			);

			assertTrue(task.cancel(), "cancelling a fresh task must answer true");
			assertTrue(task.getFutureResult().isCancelled(), "the result future was not cancelled");
			// the cancel moved the status out of QUEUED, so the body can no longer run
			assertNull(task.execute(), "a cancelled task must not execute its body");
		}

		@Test
		@DisplayName("refuses to cancel a task that already completed")
		void shouldRefuseToCancelCompletedTask() {
			final ClientCallableTask<Void, Integer> task = new ClientCallableTask<Void, Integer>(
				"task", "Completed task", null, theTask -> 42
			);
			task.transitionToIssued();

			assertEquals(42, task.execute());
			assertFalse(task.cancel(), "cancelling an already-completed task must answer false");
			assertEquals(TaskSimplifiedState.FINISHED, task.getStatus().simplifiedState());
		}
	}

}
