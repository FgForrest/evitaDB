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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.api.task.InternallyScheduledTask;
import io.evitadb.api.task.ServerTask;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.api.task.TaskStatus.TaskSimplifiedState;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TASK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * This test verifies the correct functionality of the {@link Scheduler} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Tag(ENGINE)
@Tag(TASK)
@DisplayName("Scheduler")
class SchedulerTest {
	private final Scheduler scheduler = new Scheduler(
		ThreadPoolOptions
			.serviceThreadPoolBuilder()
			.build()
	);

	@AfterEach
	void tearDown() {
		this.scheduler.shutdownNow();
	}

	/**
	 * A task whose handle attachment can be held open by the test, so a cancellation can be made to land in the window
	 * between `executorService.submit(...)` and `attachExecutionHandle(...)` deterministically.
	 *
	 * `attachExecutionHandle` is public on `AbstractServerTask`, so widening it is unnecessary — this subclass only
	 * inserts the two latches around the `super` call. Note the attach runs on the **submitting** thread, so a test
	 * using this class must submit from a thread other than the one that drives the cancellation.
	 */
	private static class LateAttachingTask extends ClientCallableTask<Void, Integer> {
		/** Counted down once the attachment is reached, i.e. once the executor has been handed the task. */
		private final CountDownLatch attachReached;
		/** Awaited before the attachment proceeds, so the test controls how long the window stays open. */
		private final CountDownLatch attachReleased;
		/** Counted down once the attachment has actually been applied. */
		private final CountDownLatch attachCompleted;

		LateAttachingTask(
			@Nonnull Function<ClientCallableTask<Void, Integer>, Integer> body,
			@Nonnull CountDownLatch attachReached,
			@Nonnull CountDownLatch attachReleased,
			@Nonnull CountDownLatch attachCompleted
		) {
			super("task", "Late attaching task", null, body);
			this.attachReached = attachReached;
			this.attachReleased = attachReleased;
			this.attachCompleted = attachCompleted;
		}

		@Override
		public void attachExecutionHandle(@Nonnull Future<?> handle) {
			this.attachReached.countDown();
			try {
				assertTrue(this.attachReleased.await(30, TimeUnit.SECONDS), "attachment was never released");
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new GenericEvitaInternalError("Attachment wait interrupted.", "Attachment failed.", e);
			}
			super.attachExecutionHandle(handle);
			this.attachCompleted.countDown();
		}
	}

	/**
	 * A task carrying `@InternallyScheduledTask`, which makes {@link Scheduler} run it inline on the submitting thread
	 * and deliberately leave it without an executor handle.
	 */
	@InternallyScheduledTask
	private static class InternallyScheduledRunnableTask extends ClientRunnableTask<Void> {

		InternallyScheduledRunnableTask(@Nonnull Runnable body) {
			super("task", "Internally scheduled task", null, body);
		}

	}

	@Nested
	@DisplayName("Task tracking")
	class TaskTracking {

		@Test
		@DisplayName("registers a submitted task in the queue")
		void shouldRegisterSubmittedTask() {
			assertEquals(0, SchedulerTest.this.scheduler.listTaskStatuses(1, 20, null).getTotalRecordCount());

			SchedulerTest.this.scheduler.submit(
				(ServerTask<?, ?>) new ClientRunnableTask<>("task", "Test task", null, () -> {
				})
			);

			assertEquals(1, SchedulerTest.this.scheduler.listTaskStatuses(1, 20, null).getTotalRecordCount());
		}

		@Test
		@DisplayName("lists tracked tasks with pagination")
		void shouldListTrackedTasksWithPagination() {
			assertEquals(0, SchedulerTest.this.scheduler.listTaskStatuses(1, 20, null).getTotalRecordCount());

			for (int i = 0; i < 10; i++) {
				SchedulerTest.this.scheduler.submit(
					(ServerTask<?, ?>) new ClientRunnableTask<>("task", "Test task", null, () -> {
					})
				);
			}

			final PaginatedList<TaskStatus<?, ?>> taskStatuses = SchedulerTest.this.scheduler.listTaskStatuses(1, 5, null);
			assertEquals(10, taskStatuses.getTotalRecordCount());
			assertEquals(5, taskStatuses.getData().size());
		}

		@Test
		@DisplayName("exposes status of a completed task filtered by type and state")
		void shouldExposeStatusOfCompletedTask() throws ExecutionException, InterruptedException {
			assertEquals(0, SchedulerTest.this.scheduler.listTaskStatuses(1, 20, null).getTotalRecordCount());

			final CompletableFuture<Integer> result = SchedulerTest.this.scheduler.submit(
				(ServerTask<?, Integer>) new ClientCallableTask<>("task", "Test task", null, () -> 5)
			);

			final PaginatedList<TaskStatus<?, ?>> jobStatuses = SchedulerTest.this.scheduler.listTaskStatuses(1, 20, null);
			assertEquals(1, jobStatuses.getTotalRecordCount());

			final PaginatedList<TaskStatus<?, ?>> typeFilteredJobStatuses = SchedulerTest.this.scheduler.listTaskStatuses(1, 20, new String[] { "task" });
			assertEquals(1, typeFilteredJobStatuses.getTotalRecordCount());

			while (SchedulerTest.this.scheduler.listTaskStatuses(1, 20, null).getData().get(0).simplifiedState() != TaskSimplifiedState.FINISHED) {
				synchronized (this) {
					wait(100);
				}
			}

			final PaginatedList<TaskStatus<?, ?>> statusFilteredJobStatuses = SchedulerTest.this.scheduler.listTaskStatuses(1, 20, null, TaskSimplifiedState.QUEUED);
			assertEquals(0, statusFilteredJobStatuses.getTotalRecordCount());

			final PaginatedList<TaskStatus<?, ?>> typeFilteredOutJobStatuses = SchedulerTest.this.scheduler.listTaskStatuses(1, 20, new String[] { "Non-existing task" });
			assertEquals(0, typeFilteredOutJobStatuses.getTotalRecordCount());

			assertEquals(5, result.get());

			final PaginatedList<TaskStatus<?, ?>> statusFilteredJobStatusesWhenDone = SchedulerTest.this.scheduler.listTaskStatuses(1, 20, null, TaskSimplifiedState.FINISHED);
			assertEquals(1, statusFilteredJobStatusesWhenDone.getTotalRecordCount());

			final PaginatedList<TaskStatus<?, ?>> nonMatchingFilteredJobStatusesWhenDone = SchedulerTest.this.scheduler.listTaskStatuses(1, 20, new String[] { "Non-existing task" }, TaskSimplifiedState.FINISHED);
			assertEquals(0, nonMatchingFilteredJobStatusesWhenDone.getTotalRecordCount());

			final Optional<TaskStatus<?, ?>> jobStatus = SchedulerTest.this.scheduler.getTaskStatus(typeFilteredJobStatuses.getData().get(0).taskId());

			assertTrue(jobStatus.isPresent());
			assertEquals("Test task", jobStatus.get().taskName());
			assertEquals(5, jobStatus.get().result());
			assertEquals(TaskSimplifiedState.FINISHED, jobStatus.get().simplifiedState());
		}

		@Test
		@DisplayName("cancels a running task")
		void shouldCancelRunningTask() throws InterruptedException {
			assertEquals(0, SchedulerTest.this.scheduler.listTaskStatuses(1, 20, null).getTotalRecordCount());

			final AtomicBoolean started = new AtomicBoolean(false);
			final AtomicBoolean interrupted = new AtomicBoolean(false);
			final CompletableFuture<Integer> result = SchedulerTest.this.scheduler.submit(
				(ServerTask<Void, Integer>) new ClientCallableTask<Void, Integer>("task", "Test task", null, theTask -> {
					started.set(true);
					for (int i = 0; i < 1_000_000_000; i++) {
						if (theTask.getFutureResult().isCancelled()) {
							interrupted.set(true);
							return -1;
						}
						Thread.onSpinWait();
					}
					return 5;
				})
			);

			final PaginatedList<TaskStatus<?, ?>> jobStatuses = SchedulerTest.this.scheduler.listTaskStatuses(1, 20, null);
			assertEquals(1, jobStatuses.getTotalRecordCount());

			final Optional<TaskStatus<?, ?>> jobStatus = SchedulerTest.this.scheduler.getTaskStatus(jobStatuses.getData().get(0).taskId());

			assertTrue(jobStatus.isPresent());
			assertEquals("Test task", jobStatus.get().taskName());

			SchedulerTest.this.scheduler.cancelTask(jobStatus.get().taskId());

			try {
				result.get();
				fail("Exception expected");
			} catch (CancellationException | ExecutionException e) {
				// expected
			}

			// wait for the task to be interrupted
			final long start = System.currentTimeMillis();
			do {
				Thread.onSpinWait();
			} while (started.get() && !interrupted.get() && System.currentTimeMillis() - start < 100_000);

			final Optional<TaskStatus<?, ?>> jobStatusAgain = SchedulerTest.this.scheduler.getTaskStatus(jobStatuses.getData().get(0).taskId());
			jobStatusAgain.ifPresent(taskStatus -> {
				assertNull(taskStatus.result());
				assertEquals(TaskSimplifiedState.FAILED, taskStatus.simplifiedState());
			});
			assertTrue(interrupted.get() || !started.get());
		}

		@Test
		@DisplayName("interrupts the worker thread of a running task on cancellation")
		void shouldInterruptWorkerThreadOfCancelledTask() throws InterruptedException {
			// deliberately observes ONLY the thread interrupt flag - never getFutureResult().isCancelled(). A task
			// polling the result future cooperates with cancellation regardless of whether the interrupt was ever
			// delivered, which is why the broken cancellation chain went unnoticed for so long: the executor
			// Future returned by submit(...) was discarded, and CompletableFuture#cancel(true) ignores
			// mayInterruptIfRunning, so nothing ever interrupted the worker.
			final CountDownLatch started = new CountDownLatch(1);
			final CountDownLatch interrupted = new CountDownLatch(1);
			final CompletableFuture<Integer> result = SchedulerTest.this.scheduler.submit(
				(ServerTask<Void, Integer>) new ClientCallableTask<Void, Integer>(
					"task", "Test task", null, theTask -> {
						started.countDown();
						try {
							// park rather than spin: a spin pins a core for the whole positive wait inside parallel
							// surefire forks and can cause flakes in sibling tests, and it observes the flag rather
							// than the delivery. A latch nobody counts down stays interruptible and costs nothing.
							new CountDownLatch(1).await(30, TimeUnit.SECONDS);
						} catch (InterruptedException e) {
							interrupted.countDown();
							// the task body is a Function and cannot rethrow a checked exception - wrapping it is
							// what lets the test observe the real unwind path through execute()
							throw new GenericEvitaInternalError("Task interrupted.", "Task interrupted.", e);
						}
						return -1;
					}
				)
			);

			// positive wait - generous, returns as soon as the task actually starts
			assertTrue(started.await(30, TimeUnit.SECONDS), "task never started");

			final PaginatedList<TaskStatus<?, ?>> jobStatuses =
				SchedulerTest.this.scheduler.listTaskStatuses(1, 20, null);
			assertEquals(1, jobStatuses.getTotalRecordCount());
			final UUID taskId = jobStatuses.getData().get(0).taskId();
			SchedulerTest.this.scheduler.cancelTask(taskId);

			// the assertion that matters: the interrupt reached the worker thread
			assertTrue(interrupted.await(30, TimeUnit.SECONDS), "worker thread was never interrupted by cancellation");

			// cancel() must cancel the result future BEFORE interrupting the worker, so the status is already stamped
			// with the CancellationException by the time the interrupt unwinds executeInternal(); execute()'s catch
			// can then only preserve it. Under the reverse order the catch overwrites both fields with the wrapped
			// InterruptedException and "Task failed for unknown reasons.". Deterministic in the green direction - the
			// stamping happens synchronously inside cancelTask(...) above - but it only catches a reversal
			// probabilistically, because the overwrite would land some time after the latch fires. The deterministic
			// guard for the early-return branch itself is AbstractServerTaskCancellationTest.
			final TaskStatus<?, ?> statusAfterCancel = SchedulerTest.this.scheduler.getTaskStatus(taskId).orElseThrow();
			assertEquals(
				"Task was cancelled.", statusAfterCancel.publicExceptionMessage(),
				"cancellation was reported as an ordinary failure - the interrupt outran the future cancellation"
			);
			assertTrue(
				statusAfterCancel.exceptionWithStackTrace().startsWith(CancellationException.class.getName()),
				"the status carries something other than the CancellationException raised by cancel()"
			);

			try {
				result.get();
				fail("Exception expected");
			} catch (CancellationException | ExecutionException e) {
				// expected - the task was cancelled
			}
		}

		@Test
		@DisplayName("interrupts the worker thread of a cancelled runnable task")
		void shouldInterruptWorkerThreadOfCancelledRunnableTask() throws InterruptedException {
			// the mirror of the callable case above for a task that implements Runnable but NOT Callable, which is
			// what routes it through the executorService.submit(task::execute) branch of Scheduler#submitTaskInQueue.
			// Both branches had to start retaining the executor handle; only the Callable one was covered.
			final CountDownLatch started = new CountDownLatch(1);
			final CountDownLatch interrupted = new CountDownLatch(1);
			SchedulerTest.this.scheduler.submit(
				(ServerTask<?, ?>) new ClientRunnableTask<Void>(
					"task", "Test task", null, () -> {
						started.countDown();
						try {
							new CountDownLatch(1).await(30, TimeUnit.SECONDS);
						} catch (InterruptedException e) {
							interrupted.countDown();
							// a Runnable body cannot rethrow the checked exception either - see the callable case
							throw new GenericEvitaInternalError("Task interrupted.", "Task interrupted.", e);
						}
					}
				)
			);

			assertTrue(started.await(30, TimeUnit.SECONDS), "task never started");

			final PaginatedList<TaskStatus<?, ?>> jobStatuses =
				SchedulerTest.this.scheduler.listTaskStatuses(1, 20, null);
			assertEquals(1, jobStatuses.getTotalRecordCount());
			SchedulerTest.this.scheduler.cancelTask(jobStatuses.getData().get(0).taskId());

			assertTrue(
				interrupted.await(30, TimeUnit.SECONDS),
				"the non-Callable submit branch retained no executor handle - cancellation cannot interrupt it"
			);
		}

		@Test
		@DisplayName("runs an internally scheduled task inline without an execution handle")
		void shouldRunInternallyScheduledTaskInlineWithoutExecutionHandle() {
			// the branch Scheduler#submitTaskInQueue carves out explicitly: an internally scheduled task runs on the
			// submitting thread, so it deliberately never receives an executor handle - interrupting from another
			// thread would land on an arbitrary caller's thread with no well-defined target
			final AtomicReference<Thread> executingThread = new AtomicReference<>();
			final InternallyScheduledRunnableTask task =
				new InternallyScheduledRunnableTask(() -> executingThread.set(Thread.currentThread()));

			SchedulerTest.this.scheduler.submit((ServerTask<?, ?>) task);

			assertSame(
				Thread.currentThread(), executingThread.get(),
				"an internally scheduled task must run inline on the submitting thread"
			);
			// no handle was attached and the future is already done, so a later cancel is a no-op rather than an NPE
			assertFalse(task.cancel(), "cancelling an already-completed internally scheduled task must answer false");
		}

		@Test
		@DisplayName("interrupts a task cancelled between submission and handle attachment")
		void shouldInterruptTaskCancelledBeforeHandleAttached() throws InterruptedException {
			// a cancel arriving after the worker started the task but before the executor handle is attached must still
			// interrupt it: cancel() cancels the result future and only then reads the handle, so an attachment that
			// publishes the handle and only then re-reads the future cannot miss it.
			//
			// The seam that makes this deterministic is entirely test-side: attachExecutionHandle is public on
			// AbstractServerTask, so a subclass can hold the attach open for exactly as long as the test needs.
			final CountDownLatch started = new CountDownLatch(1);
			final CountDownLatch interrupted = new CountDownLatch(1);
			final CountDownLatch attachReached = new CountDownLatch(1);
			final CountDownLatch attachReleased = new CountDownLatch(1);
			final CountDownLatch attachCompleted = new CountDownLatch(1);

			final LateAttachingTask task = new LateAttachingTask(
				theTask -> {
					started.countDown();
					try {
						new CountDownLatch(1).await(30, TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						interrupted.countDown();
						throw new GenericEvitaInternalError("Task interrupted.", "Task interrupted.", e);
					}
					return -1;
				},
				attachReached, attachReleased, attachCompleted
			);

			// submit(...) blocks inside the overridden attachExecutionHandle, so it cannot run on the test thread
			final Thread submitter = new Thread(
				() -> SchedulerTest.this.scheduler.submit((ServerTask<?, ?>) task), "late-attach-submitter"
			);
			submitter.setDaemon(true);
			submitter.start();

			assertTrue(started.await(30, TimeUnit.SECONDS), "task never started");
			assertTrue(attachReached.await(30, TimeUnit.SECONDS), "handle attachment was never reached");

			// the task is registered in the queue before submitTaskInQueue runs, so it is cancellable in this window
			final PaginatedList<TaskStatus<?, ?>> jobStatuses =
				SchedulerTest.this.scheduler.listTaskStatuses(1, 20, null);
			assertEquals(1, jobStatuses.getTotalRecordCount());
			// without this the absence of an interrupt below could just as well mean the cancel never found the task,
			// which would make the whole test pass for a reason its name does not claim
			assertTrue(
				SchedulerTest.this.scheduler.cancelTask(jobStatuses.getData().get(0).taskId()),
				"the cancel never reached the task - this proves nothing about the attachment window"
			);

			attachReleased.countDown();
			assertTrue(attachCompleted.await(30, TimeUnit.SECONDS), "handle attachment never completed");

			// positive wait - the attachment re-reads the already-cancelled result future and cancels the handle it
			// just published, so the interrupt is delivered even though the cancel arrived while the handle was null
			assertTrue(
				interrupted.await(30, TimeUnit.SECONDS),
				"a cancel that landed before the executor handle was attached never interrupted the running task"
			);
		}
	}

	@Nested
	@DisplayName("Submitted-task counting")
	class SubmittedTaskCounting {

		@Test
		@DisplayName("counts both one-shot schedule(...) variants")
		void shouldCountOneShotScheduledSubmissions() {
			// one-shot schedule(...) variants (Runnable and Callable) must increment the submitted-task counter
			final long before = SchedulerTest.this.scheduler.getSubmittedTaskCount();

			// schedule with a long delay so the tasks never actually run during the test
			SchedulerTest.this.scheduler.schedule((Runnable) () -> {}, 1, TimeUnit.HOURS);
			SchedulerTest.this.scheduler.schedule((Callable<Integer>) () -> 1, 1, TimeUnit.HOURS);

			assertEquals(before + 2, SchedulerTest.this.scheduler.getSubmittedTaskCount());
		}

		@Test
		@DisplayName("counts a submitted runnable")
		void shouldCountSubmittedRunnable() {
			final long before = SchedulerTest.this.scheduler.getSubmittedTaskCount();
			SchedulerTest.this.scheduler.submit(() -> {});
			assertEquals(before + 1, SchedulerTest.this.scheduler.getSubmittedTaskCount());
		}

		@Test
		@DisplayName("counts a submitted callable")
		void shouldCountSubmittedCallable() {
			final long before = SchedulerTest.this.scheduler.getSubmittedTaskCount();
			SchedulerTest.this.scheduler.submit((Callable<Integer>) () -> 1);
			assertEquals(before + 1, SchedulerTest.this.scheduler.getSubmittedTaskCount());
		}

		@Test
		@DisplayName("counts invokeAll submissions matching the returned futures")
		void shouldCountInvokeAllSubmissions() throws InterruptedException {
			final long before = SchedulerTest.this.scheduler.getSubmittedTaskCount();
			final List<Future<Integer>> futures = SchedulerTest.this.scheduler.invokeAll(
				List.of((Callable<Integer>) () -> 1, () -> 2, () -> 3)
			);
			// the counter must grow by exactly the number of futures the executor reported as submitted
			assertEquals(before + futures.size(), SchedulerTest.this.scheduler.getSubmittedTaskCount());
		}

		@Test
		@DisplayName("counts an invokeAny submission")
		void shouldCountInvokeAnySubmission() throws InterruptedException, ExecutionException {
			final long before = SchedulerTest.this.scheduler.getSubmittedTaskCount();
			final Integer result = SchedulerTest.this.scheduler.invokeAny(List.of((Callable<Integer>) () -> 42));
			assertEquals(42, result);
			assertEquals(before + 1, SchedulerTest.this.scheduler.getSubmittedTaskCount());
		}

		@Test
		@DisplayName("counts a timed invokeAny submission")
		void shouldCountTimedInvokeAnySubmission() throws InterruptedException, ExecutionException, TimeoutException {
			final long before = SchedulerTest.this.scheduler.getSubmittedTaskCount();
			final Integer result = SchedulerTest.this.scheduler.invokeAny(
				List.of((Callable<Integer>) () -> 42), 5, TimeUnit.SECONDS
			);
			assertEquals(42, result);
			assertEquals(before + 1, SchedulerTest.this.scheduler.getSubmittedTaskCount());
		}
	}

	@Nested
	@DisplayName("Exception handling")
	class ExceptionHandling {

		@Test
		@DisplayName("logs an exception thrown by a fire-and-forget execute() task")
		void shouldLogExceptionThrownViaExecute() throws InterruptedException {
			// an exception thrown by a fire-and-forget execute() task must not be silently swallowed inside the
			// discarded future - it has to be logged
			final org.slf4j.Logger slf4jLogger = LoggerFactory.getLogger(Scheduler.class);
			// the assertion captures log output through Logback's appender API; skip gracefully under a different
			// SLF4J binding rather than failing with a ClassCastException
			assumeTrue(slf4jLogger instanceof Logger, "Logback backend required to capture log output.");
			final Logger schedulerLogger = (Logger) slf4jLogger;

			// the appender releases the latch as soon as the expected error event arrives - no polling needed
			final CountDownLatch errorLogged = new CountDownLatch(1);
			final ListAppender<ILoggingEvent> appender = new ListAppender<>() {
				@Override
				protected void append(ILoggingEvent eventObject) {
					super.append(eventObject);
					if (eventObject.getLevel() == Level.ERROR && eventObject.getThrowableProxy() != null) {
						errorLogged.countDown();
					}
				}
			};
			appender.start();
			schedulerLogger.addAppender(appender);
			try {
				SchedulerTest.this.scheduler.execute(() -> {
					throw new RuntimeException("boom");
				});

				assertTrue(
					errorLogged.await(5, TimeUnit.SECONDS),
					"Expected an ERROR log entry carrying the swallowed exception."
				);
			} finally {
				schedulerLogger.detachAppender(appender);
			}
		}
	}

}
