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

import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.test.TestConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TASK;

/**
 * This test verifies behavior of DelayedAsyncTask class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Tag(ENGINE)
@Tag(TASK)
class DelayedAsyncTaskTest implements TestConstants {
	private Scheduler scheduler;

	@BeforeEach
	void setUp() {
		this.scheduler = new Scheduler(
			ThreadPoolOptions.requestThreadPoolBuilder()
				.minThreadCount(1)
				.build()
		);
	}

	@AfterEach
	void tearDown() {
		this.scheduler.shutdownNow();
	}

	@Test
	void shouldScheduleCallOnlyOnce() throws InterruptedException {
		final AtomicInteger executed = new AtomicInteger();
		final CountDownLatch completionLatch = new CountDownLatch(1);
		final DelayedAsyncTask tested = new DelayedAsyncTask(
			TEST_CATALOG, "testTask", this.scheduler,
			() -> {
				executed.incrementAndGet();
				completionLatch.countDown();
				return -1;
			},
			0, TimeUnit.MILLISECONDS, 0
		);

		tested.schedule();

		assertTrue(completionLatch.await(10, TimeUnit.SECONDS), "Task did not execute in time.");
		// grace window to catch an erroneous re-run; a correct task stays paused after returning -1,
		// so this can only turn a false pass into a (correct) failure, never flake under CPU churn
		Thread.sleep(100);
		assertEquals(1, executed.get());
	}

	@Test
	void shouldScheduleCallManyTimes() throws InterruptedException {
		final AtomicInteger executed = new AtomicInteger();
		final CountDownLatch multipleExecutionsLatch = new CountDownLatch(2);
		final DelayedAsyncTask tested = new DelayedAsyncTask(
			TEST_CATALOG, "testTask", this.scheduler,
			() -> {
				executed.incrementAndGet();
				multipleExecutionsLatch.countDown();
				return 0;
			},
			0, TimeUnit.MILLISECONDS, 0
		);

		tested.schedule();

		assertTrue(
			multipleExecutionsLatch.await(10, TimeUnit.SECONDS),
			"Task did not execute more than once in time."
		);
		assertTrue(executed.get() > 1);
	}

	@Test
	void shouldScheduleLogNTimes() throws InterruptedException {
		final AtomicInteger executed = new AtomicInteger();
		final AtomicInteger counter = new AtomicInteger(100);
		final CountDownLatch completionLatch = new CountDownLatch(1);
		final DelayedAsyncTask tested = new DelayedAsyncTask(
			TEST_CATALOG, "testTask", this.scheduler,
			() -> {
				executed.incrementAndGet();
				final int planAgainIn = counter.updateAndGet(i -> i / 2 == 0 ? -1 : i / 2);
				if (planAgainIn < 0) {
					completionLatch.countDown();
				}
				return planAgainIn;
			},
			0, TimeUnit.MILLISECONDS, 0
		);

		tested.schedule();

		// 100 -> 50 -> 25 -> 12 -> 6 -> 3 -> 1 -> -1
		assertTrue(
			completionLatch.await(10, TimeUnit.SECONDS),
			"Task did not finish all scheduled executions in time."
		);
		assertEquals(7, executed.get());
	}

	@Test
	void shouldScheduleLogNTimesWithDifferentInitialDelay() throws InterruptedException {
		final int expectedExecutions = 8;
		final AtomicInteger executed = new AtomicInteger();
		final AtomicInteger counter = new AtomicInteger(expectedExecutions);
		final CountDownLatch completionLatch = new CountDownLatch(1);
		final DelayedAsyncTask tested = new DelayedAsyncTask(
			TEST_CATALOG, "testTask", this.scheduler,
			() -> {
				executed.incrementAndGet();
				final int planAgainIn = counter.decrementAndGet() > 0 ? 180 : -1;
				if (planAgainIn < 0) {
					completionLatch.countDown();
				}
				return planAgainIn;
			},
			200, TimeUnit.MILLISECONDS, 0
		);

		tested.schedule();

		// wait for the task to reach its terminal (pausing) execution instead of assuming a fixed
		// wall-clock window - the schedule is CPU-latency sensitive under contended CI runners,
		// but the task self-pauses after the expected number of executions regardless of timing
		assertTrue(
			completionLatch.await(10, TimeUnit.SECONDS),
			"Task did not finish all scheduled executions in time."
		);
		assertEquals(expectedExecutions, executed.get());
	}

}
