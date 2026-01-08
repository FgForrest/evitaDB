/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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
import io.evitadb.cron.CronSchedule;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.test.TestConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies behavior of ScheduledTask class including thread-safety,
 * exception handling, close/shutdown behavior, and edge cases.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@DisplayName("ScheduledTask functionality tests")
@Tag(LONG_RUNNING_TEST)
class ScheduledTaskTest implements TestConstants {
	private Scheduler scheduler;

	@BeforeEach
	void setUp() {
		this.scheduler = new Scheduler(
			ThreadPoolOptions.requestThreadPoolBuilder()
				.minThreadCount(4)
				.build()
		);
	}

	@AfterEach
	void tearDown() {
		this.scheduler.shutdownNow();
	}

	// ===========================================
	// BASIC SCHEDULING TESTS (existing)
	// ===========================================

	@Test
	@DisplayName("Should execute task exactly once when lambda returns negative value")
	void shouldScheduleCallOnlyOnce() throws InterruptedException, IOException {
		final AtomicInteger executed = new AtomicInteger();
		try (
			final ScheduledTask tested = new ScheduledTask(
				TEST_CATALOG, "testTask", this.scheduler,
				() -> {
					executed.incrementAndGet();
					return -1;
				},
				1, TimeUnit.MILLISECONDS, 0L
			)
		) {
			tested.schedule();
			Thread.sleep(100);
			assertEquals(1, executed.get());
		}
	}

	@Test
	@DisplayName("Should execute task multiple times when lambda returns zero")
	void shouldScheduleCallManyTimes() throws InterruptedException, IOException {
		final AtomicInteger executed = new AtomicInteger();
		try (
			final ScheduledTask tested = new ScheduledTask(
				TEST_CATALOG, "testTask", this.scheduler,
				() -> {
					executed.incrementAndGet();
					return 0;
				},
				1, TimeUnit.MILLISECONDS, 0L
			)
		) {
			tested.schedule();
			Thread.sleep(100);
			assertTrue(executed.get() > 1);
		}
	}

	@Test
	@DisplayName("Should reschedule with progressively shorter delays")
	void shouldScheduleLogNTimes() throws InterruptedException, IOException {
		final AtomicInteger executed = new AtomicInteger();
		final AtomicInteger counter = new AtomicInteger(100);
		try (
			final ScheduledTask tested = new ScheduledTask(
				TEST_CATALOG, "testTask", this.scheduler,
				() -> {
					executed.incrementAndGet();
					return counter.updateAndGet(i -> i / 2 == 0 ? -1 : i / 2);
				},
				1, TimeUnit.MILLISECONDS, 0L
			)
		) {
			tested.schedule();
			Thread.sleep(500);

			// 100 -> 50 -> 25 -> 12 -> 6 -> 3 -> 1 -> -1
			assertEquals(7, executed.get());
		}
	}

	@Test
	@DisplayName("Should respect initial delay configuration")
	void shouldScheduleLogNTimesWithDifferentInitialDelay() throws InterruptedException, IOException {
		final AtomicInteger executed = new AtomicInteger();
		final AtomicInteger counter = new AtomicInteger(8);
		try (
			final ScheduledTask tested = new ScheduledTask(
				TEST_CATALOG, "testTask", this.scheduler,
				() -> {
					executed.incrementAndGet();
					return counter.decrementAndGet() > 0 ? 180 : -1;
				},
				201, TimeUnit.MILLISECONDS, 0L
			)
		) {
			tested.schedule();
			Thread.sleep(500);

			assertEquals(8, executed.get());
		}
	}

	// ===========================================
	// THREAD-SAFETY TESTS
	// ===========================================

	@Test
	@DisplayName("Should handle concurrent schedule() calls safely")
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	void shouldHandleConcurrentScheduleCalls() throws InterruptedException, IOException {
		final int threadCount = 10;
		final CountDownLatch startLatch = new CountDownLatch(1);
		final CountDownLatch doneLatch = new CountDownLatch(threadCount);
		final AtomicInteger executed = new AtomicInteger();

		try (
			final ScheduledTask tested = new ScheduledTask(
				TEST_CATALOG, "testTask", this.scheduler,
				() -> {
					executed.incrementAndGet();
					return -1;
				},
				51, TimeUnit.MILLISECONDS, 0L
			)
		) {
			for (int i = 0; i < threadCount; i++) {
				new Thread(() -> {
					try {
						startLatch.await();
						tested.schedule();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					} finally {
						doneLatch.countDown();
					}
				}).start();
			}

			startLatch.countDown(); // Release all threads simultaneously
			doneLatch.await();
			Thread.sleep(200);

			// Should execute exactly once despite concurrent scheduling attempts
			assertEquals(1, executed.get());
		}
	}

	@Test
	@DisplayName("Should handle concurrent scheduleImmediately() calls safely")
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	void shouldHandleConcurrentScheduleImmediatelyCalls() throws InterruptedException, IOException {
		final int threadCount = 10;
		final CountDownLatch startLatch = new CountDownLatch(1);
		final CountDownLatch doneLatch = new CountDownLatch(threadCount);
		final CountDownLatch allThreadsScheduled = new CountDownLatch(threadCount);
		final CountDownLatch taskCanComplete = new CountDownLatch(1);
		final AtomicInteger executed = new AtomicInteger();

		try (
			final ScheduledTask tested = new ScheduledTask(
				TEST_CATALOG, "testTask", this.scheduler,
				() -> {
					executed.incrementAndGet();
					try {
						// Wait until all threads have attempted to schedule
						allThreadsScheduled.await();
						taskCanComplete.await();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
					return -1;
				},
				1001, TimeUnit.MILLISECONDS, 0L
			)
		) {
			for (int i = 0; i < threadCount; i++) {
				new Thread(() -> {
					try {
						startLatch.await();
						tested.scheduleImmediately();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					} finally {
						allThreadsScheduled.countDown();
						doneLatch.countDown();
					}
				}).start();
			}

			startLatch.countDown();
			doneLatch.await();
			// All threads have called scheduleImmediately(), now let task complete
			taskCanComplete.countDown();
			Thread.sleep(200);

			// Should execute exactly once despite concurrent scheduling attempts
			assertEquals(1, executed.get());
		}
	}

	@Test
	@DisplayName("Should handle schedule() during task execution via reSchedule flag")
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	void shouldHandleScheduleDuringExecution() throws InterruptedException, IOException {
		final CountDownLatch taskStarted = new CountDownLatch(1);
		final CountDownLatch scheduleCallDone = new CountDownLatch(1);
		final AtomicInteger executed = new AtomicInteger();

		try (
			final ScheduledTask tested = new ScheduledTask(
				TEST_CATALOG, "testTask", this.scheduler,
				() -> {
					final int count = executed.incrementAndGet();
					if (count == 1) {
						taskStarted.countDown();
						try {
							scheduleCallDone.await(); // Wait for schedule() to be called
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
						}
					}
					return -1; // Pause after each execution
				},
				1, TimeUnit.MILLISECONDS, 0L
			)
		) {
			tested.schedule();
			taskStarted.await(); // Wait for task to start
			tested.schedule();   // Should set reSchedule flag
			scheduleCallDone.countDown();

			Thread.sleep(200);

			// Should execute twice: once from initial, once from reSchedule
			assertEquals(2, executed.get());
		}
	}

	// ===========================================
	// CLOSE/SHUTDOWN TESTS
	// ===========================================

	@Test
	@DisplayName("Should throw GenericEvitaInternalError when scheduling on closed task")
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	void shouldThrowWhenSchedulingClosedTask() throws IOException {
		try (
			final ScheduledTask tested = new ScheduledTask(
				TEST_CATALOG, "testTask", this.scheduler,
				() -> -1, 101, TimeUnit.MILLISECONDS, 0L
			)
		) {
			tested.close();

			assertThrows(GenericEvitaInternalError.class, tested::schedule);
			assertThrows(GenericEvitaInternalError.class, tested::scheduleImmediately);
		}
	}

	@Test
	@DisplayName("Should be idempotent when close() called multiple times")
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	void shouldBeIdempotentOnMultipleClose() throws IOException {
		try (
			final ScheduledTask tested = new ScheduledTask(
				TEST_CATALOG, "testTask", this.scheduler,
				() -> -1, 101, TimeUnit.MILLISECONDS, 0L
			)
		) {
			tested.close();
			assertDoesNotThrow(tested::close); // Second close should not throw
			assertDoesNotThrow(tested::close); // Third close should not throw
		}
	}

	@Test
	@DisplayName("Should cancel pending scheduled future on close")
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	void shouldCancelPendingFutureOnClose() throws IOException, InterruptedException {
		final AtomicInteger executed = new AtomicInteger();
		try (
			final ScheduledTask tested = new ScheduledTask(
				TEST_CATALOG, "testTask", this.scheduler,
				() -> {
					executed.incrementAndGet();
					return -1;
				},
				501, TimeUnit.MILLISECONDS, 0L // Long delay
			)
		) {
			tested.schedule();
			Thread.sleep(50); // Give time to schedule
			tested.close();   // Cancel before execution
			Thread.sleep(600); // Wait past original delay

			assertEquals(0, executed.get()); // Should not have executed
		}
	}

	@Test
	@DisplayName("Should not reschedule after close even with positive return value")
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	void shouldNotRescheduleAfterClose() throws IOException, InterruptedException {
		final AtomicInteger executed = new AtomicInteger();
		final CountDownLatch taskStarted = new CountDownLatch(1);
		final CountDownLatch closeDone = new CountDownLatch(1);

		try (
			final ScheduledTask tested = new ScheduledTask(
				TEST_CATALOG, "testTask", this.scheduler,
				() -> {
					final int count = executed.incrementAndGet();
					if (count == 1) {
						taskStarted.countDown();
						try {
							closeDone.await(); // Wait for close() to be called
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
						}
					}
					return 0; // Would normally reschedule
				},
				1, TimeUnit.MILLISECONDS, 0L
			)
		) {
			tested.schedule();
			taskStarted.await();
			tested.close();
			closeDone.countDown();

			Thread.sleep(200);

			// Should have executed once, then stopped due to close
			assertEquals(1, executed.get());
		}
	}

	// ===========================================
	// EXCEPTION HANDLING TESTS
	// ===========================================

	@Test
	@DisplayName("Should automatically reschedule when task throws exception")
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	void shouldAutomaticallyRescheduleOnException() throws InterruptedException, IOException {
		final AtomicInteger executed = new AtomicInteger();
		try (
			final ScheduledTask tested = new ScheduledTask(
				TEST_CATALOG, "testTask", this.scheduler,
				() -> {
					final int count = executed.incrementAndGet();
					if (count == 1) {
						throw new RuntimeException("Test exception");
					}
					return -1;
				},
				1, TimeUnit.MILLISECONDS, 0L
			)
		) {
			tested.schedule();
			Thread.sleep(200);

			// Task should have thrown, and then automatically rescheduled and executed again
			assertEquals(2, executed.get());
		}
	}

	// ===========================================
	// BOUNDARY CONDITION TESTS
	// ===========================================

	@Test
	@DisplayName("Should never auto-schedule manual task with Long.MAX_VALUE delay")
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	void shouldNotScheduleManualTask() throws InterruptedException, IOException {
		final AtomicInteger executed = new AtomicInteger();
		try (
			final ScheduledTask tested = new ScheduledTask(
				TEST_CATALOG, "testTask", this.scheduler,
				() -> {
					executed.incrementAndGet();
					return -1;
				},
				Long.MAX_VALUE, TimeUnit.MILLISECONDS, 0L
			)
		) {
			tested.schedule(); // Should return immediately without scheduling
			Thread.sleep(100);

			assertEquals(0, executed.get());
		}
	}

	@Test
	@DisplayName("Should execute manual task when scheduleImmediately() called")
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	void shouldExecuteManualTaskImmediately() throws InterruptedException, IOException {
		final AtomicInteger executed = new AtomicInteger();
		try (
			final ScheduledTask tested = new ScheduledTask(
				TEST_CATALOG, "testTask", this.scheduler,
				() -> {
					executed.incrementAndGet();
					return -1;
				},
				Long.MAX_VALUE, TimeUnit.MILLISECONDS, 0L
			)
		) {
			tested.scheduleImmediately();
			Thread.sleep(100);

			assertEquals(1, executed.get());
		}
	}

	@Test
	@DisplayName("Should enforce minimalSchedulingGap between executions")
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	void shouldEnforceMinimalSchedulingGap() throws InterruptedException, IOException {
		final long minGap = 200L;
		final List<Long> executionTimes = Collections.synchronizedList(new ArrayList<>());
		final AtomicInteger counter = new AtomicInteger(3);

		try (
			final ScheduledTask tested = new ScheduledTask(
				TEST_CATALOG, "testTask", this.scheduler,
				() -> {
					executionTimes.add(System.currentTimeMillis());
					return counter.decrementAndGet() > 0 ? 0 : -1;
				},
				1, TimeUnit.MILLISECONDS, minGap
			)
		) {
			tested.schedule();
			Thread.sleep(800);

			assertEquals(3, executionTimes.size());
			for (int i = 1; i < executionTimes.size(); i++) {
				final long gap = executionTimes.get(i) - executionTimes.get(i - 1);
				// Allow some tolerance for timing variations
				assertTrue(gap >= minGap - 50, "Gap was " + gap + "ms, expected >= " + (minGap - 50));
			}
		}
	}

	@Test
	@DisplayName("Should handle zero minimalSchedulingGap")
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	void shouldHandleZeroMinimalSchedulingGap() throws InterruptedException, IOException {
		final AtomicInteger executed = new AtomicInteger();
		try (
			final ScheduledTask tested = new ScheduledTask(
				TEST_CATALOG, "testTask", this.scheduler,
				() -> {
					executed.incrementAndGet();
					return executed.get() < 5 ? 0 : -1;
				},
				1, TimeUnit.MILLISECONDS, 0L
			)
		) {
			tested.schedule();
			Thread.sleep(200);

			// Should have executed at least 5 times with no gap enforcement
			assertTrue(executed.get() >= 5);
		}
	}

	// ===========================================
	// SCHEDULEIMMEDIATELY() TESTS
	// ===========================================

	@Test
	@DisplayName("Should execute immediately when scheduleImmediately() called")
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	void shouldExecuteImmediately() throws InterruptedException, IOException {
		final AtomicInteger executed = new AtomicInteger();
		final long startTime = System.currentTimeMillis();
		final List<Long> executionTimes = Collections.synchronizedList(new ArrayList<>());

		try (
			final ScheduledTask tested = new ScheduledTask(
				TEST_CATALOG, "testTask", this.scheduler,
				() -> {
					executionTimes.add(System.currentTimeMillis());
					executed.incrementAndGet();
					return -1;
				},
				10001, TimeUnit.MILLISECONDS, 0L // Very long delay
			)
		) {
			tested.scheduleImmediately();
			Thread.sleep(100);

			assertEquals(1, executed.get());
			// Should have executed within 100ms, not waiting for 10s delay
			assertTrue(executionTimes.get(0) - startTime < 100);
		}
	}

	@Test
	@DisplayName("Should set reSchedule flag when scheduleImmediately() called during execution")
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	void shouldSetReScheduleFlagWhenRunning() throws InterruptedException, IOException {
		final CountDownLatch taskStarted = new CountDownLatch(1);
		final CountDownLatch scheduleCallMade = new CountDownLatch(1);
		final CountDownLatch taskCanComplete = new CountDownLatch(1);
		final CountDownLatch executedCountDown = new CountDownLatch(2);

		try (
			final ScheduledTask tested = new ScheduledTask(
				TEST_CATALOG, "testTask", this.scheduler,
				() -> {
					executedCountDown.countDown();
					if (executedCountDown.getCount() == 1) {
						taskStarted.countDown();
						try {
							// Wait for the scheduleImmediately() call to be made
							scheduleCallMade.await();
							// Then wait a bit more to ensure it completes
							taskCanComplete.await();
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
						}
					}
					return -1;
				},
				1001, TimeUnit.MILLISECONDS, 0L
			)
		) {
			tested.scheduleImmediately();
			taskStarted.await(); // Wait for task to start running
			tested.scheduleImmediately(); // Should set reSchedule flag since task is running
			scheduleCallMade.countDown(); // Signal that schedule call was made
			Thread.sleep(50); // Give time for reSchedule flag to be set
			taskCanComplete.countDown(); // Let task complete
			executedCountDown.await(); // Wait for task to execute twice
		}
	}

	// ===========================================
	// STATE MANAGEMENT TESTS
	// ===========================================

	@Test
	@DisplayName("Should reschedule when reSchedule flag is set even if task returns negative")
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	void shouldRescheduleWhenReScheduleFlagSet() throws InterruptedException, IOException {
		final CountDownLatch firstTaskStarted = new CountDownLatch(1);
		final CountDownLatch scheduleCallDone = new CountDownLatch(1);
		final AtomicInteger executed = new AtomicInteger();

		try (
			final ScheduledTask tested = new ScheduledTask(
				TEST_CATALOG, "testTask", this.scheduler,
				() -> {
					final int count = executed.incrementAndGet();
					if (count == 1) {
						firstTaskStarted.countDown();
						try {
							scheduleCallDone.await();
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
						}
					}
					return -1; // Always returns negative (pause)
				},
				1, TimeUnit.MILLISECONDS, 0L
			)
		) {
			tested.schedule();
			firstTaskStarted.await();

			// Call schedule while task is running - should set reSchedule flag
			tested.schedule();
			scheduleCallDone.countDown();

			Thread.sleep(200);

			// Should execute twice: first run, then reschedule from flag
			assertEquals(2, executed.get());
		}
	}

	@Test
	@DisplayName("Should properly track lastFinishedExecution time")
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	void shouldTrackLastFinishedExecution() throws InterruptedException, IOException {
		final AtomicInteger executed = new AtomicInteger();
		final long minGap = 100L;

		try (
			final ScheduledTask tested = new ScheduledTask(
				TEST_CATALOG, "testTask", this.scheduler,
				() -> {
					final int count = executed.incrementAndGet();
					if (count > 1) {
						// This simulates that gap enforcement is based on lastFinishedExecution
						// The second execution should wait for minGap after first finished
					}
					return count < 3 ? 0 : -1;
				},
				1, TimeUnit.MILLISECONDS, minGap
			)
		) {
			final long startTime = System.currentTimeMillis();
			tested.schedule();
			Thread.sleep(500);

			assertEquals(3, executed.get());
			// Total time should be at least 2 * minGap (for 2 gaps between 3 executions)
			final long totalTime = System.currentTimeMillis() - startTime;
			assertTrue(
				totalTime >= 2 * minGap - 50, "Total time was " + totalTime + "ms, expected >= " + (2 * minGap - 50));
		}
	}

	@Test
	@DisplayName("Should not schedule duplicate executions when schedule() called multiple times rapidly")
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	void shouldNotScheduleDuplicateExecutions() throws InterruptedException, IOException {
		final AtomicInteger executed = new AtomicInteger();

		try (
			final ScheduledTask tested = new ScheduledTask(
				TEST_CATALOG, "testTask", this.scheduler,
				() -> {
					executed.incrementAndGet();
					return -1;
				},
				101, TimeUnit.MILLISECONDS, 0L
			)
		) {
			// Call schedule() multiple times rapidly
			for (int i = 0; i < 10; i++) {
				tested.schedule();
			}

			Thread.sleep(300);

			// Should have executed only once
			assertEquals(1, executed.get());
		}
	}

	// ===========================================
	// CRON-BASED SCHEDULING TESTS
	// ===========================================

	@Test
	@DisplayName("Should auto-schedule task to next cron time on construction")
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	void shouldAutoScheduleToNextCronTimeOnConstruction() throws InterruptedException, IOException {
		final AtomicInteger executed = new AtomicInteger();
		// Cron expression: every second
		final CronSchedule cronSchedule = CronSchedule.fromExpression("*/1 * * * * *");

		try (
			final ScheduledTask tested = new ScheduledTask(
				TEST_CATALOG, "cronTask", this.scheduler,
				executed::incrementAndGet,
				cronSchedule,
				0L
			)
		) {
			// Note: we do NOT call schedule() - the constructor should auto-schedule
			Thread.sleep(1500);

			// Should have executed at least once without explicit schedule() call
			assertTrue(executed.get() >= 1, "Task should execute without calling schedule()");
		}
	}

	@Test
	@DisplayName("Should execute multiple times following cron schedule")
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	void shouldExecuteMultipleTimesPerCronSchedule() throws InterruptedException, IOException {
		final CountDownLatch executed = new CountDownLatch(3);
		// Cron expression: every second
		final CronSchedule cronSchedule = CronSchedule.fromExpression("*/1 * * * * *");

		try (
			final ScheduledTask ignored = new ScheduledTask(
				TEST_CATALOG, "cronTask", this.scheduler,
				executed::countDown,
				cronSchedule,
				0L
			)
		) {
			// Wait for approximately 3.5 seconds to allow multiple cron executions
			Thread.sleep(3500);
		}

		// Should have executed 3-4 times (once per second)
		executed.await();
	}
}
