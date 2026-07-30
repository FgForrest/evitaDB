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

package io.evitadb.core.cache;

import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.core.executor.Scheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TASK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies that the periodic collation-key cache decay is armed, keeps re-arming itself and stops when
 * closed. The decay *semantics* (which keys survive a sweep) are verified by
 * `io.evitadb.comparator.LocalizedStringComparatorTest.CacheDecay` - this class covers only the scheduling around them.
 *
 * Note that the caches themselves are process-wide and shared with every other test in this JVM, so the assertions here
 * deliberately avoid claiming anything about the number of keys released.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Collation key cache sweeper")
@Tag(ENGINE)
@Tag(CACHE)
@Tag(TASK)
class CollationKeyCacheSweeperTest {
	/**
	 * A period long enough that the task provably cannot fire during a test that only wants to observe the arming.
	 */
	private static final int NEVER_WITHIN_THIS_TEST = 3600;
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
	@DisplayName("should arm a single scheduled task when created")
	void shouldArmSingleScheduledTaskWhenCreated() {
		final long submittedBefore = this.scheduler.getSubmittedTaskCount();

		final CollationKeyCacheSweeper sweeper = new CollationKeyCacheSweeper(
			NEVER_WITHIN_THIS_TEST, this.scheduler
		);
		try {
			assertEquals(
				submittedBefore + 1, this.scheduler.getSubmittedTaskCount(),
				"Creating the sweeper must plan exactly one sweep."
			);
		} finally {
			sweeper.close();
		}
	}

	@Test
	@DisplayName("should keep re-arming itself after every sweep")
	void shouldKeepReArmingItselfAfterEverySweep() throws InterruptedException {
		// two sweeps prove the task re-arms - a task that fired once and stopped would time out here
		final CountDownLatch twoSweepsLatch = new CountDownLatch(2);
		final CollationKeyCacheSweeper sweeper = new CollationKeyCacheSweeper(1, this.scheduler) {
			@Override
			public int sweep() {
				final int released = super.sweep();
				twoSweepsLatch.countDown();
				return released;
			}
		};
		try {
			assertTrue(
				twoSweepsLatch.await(10, TimeUnit.SECONDS),
				"The sweeper did not perform two sweeps in time."
			);
		} finally {
			sweeper.close();
		}
	}

	@Test
	@DisplayName("should tolerate repeated close and stay usable on demand")
	void shouldTolerateRepeatedCloseAndStayUsableOnDemand() {
		final CollationKeyCacheSweeper sweeper = new CollationKeyCacheSweeper(
			NEVER_WITHIN_THIS_TEST, this.scheduler
		);

		sweeper.close();
		// closing twice must not throw - the engine closes its resources on several paths
		sweeper.close();

		// the periodic schedule is gone, but an on-demand sweep is still a valid operation (this is what makes it
		// possible to release the caches at a known moment, e.g. when a catalog leaves its bulk-indexing phase)
		assertReleasedCountIsPlausible(sweeper.sweep());
	}

	/**
	 * Asserts the only thing a sweep can promise while the caches are shared with the rest of the JVM: that it reports
	 * a non-negative number of released keys.
	 *
	 * @param released number of keys the sweep reported as released
	 */
	private static void assertReleasedCountIsPlausible(int released) {
		assertTrue(released >= 0, "A sweep cannot release a negative number of keys, got " + released + ".");
	}

}
