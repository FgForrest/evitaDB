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
import io.evitadb.api.task.ServerTask;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.api.task.TaskStatus.TaskSimplifiedState;
import io.evitadb.dataType.PaginatedList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TASK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
				protected void append(@Nonnull ILoggingEvent eventObject) {
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
