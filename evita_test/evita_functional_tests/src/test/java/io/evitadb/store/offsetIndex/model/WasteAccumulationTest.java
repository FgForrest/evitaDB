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

package io.evitadb.store.offsetIndex.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the arithmetic behind the waste accumulation rate.
 *
 * It is pinned here rather than through a running engine because every interesting property of it is about *time*:
 * how the first two flushes seed the average, what a flush inside the same millisecond does, and how an idle period
 * pulls the reported rate back towards zero. A functional test would have to sleep to reach any of them, and would
 * still be measuring the flush cadence of the engine rather than this rule.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Waste accumulation rate")
@Tag(STORAGE)
@Tag(MANAGEMENT)
class WasteAccumulationTest {

	@Test
	@DisplayName("The counter adds up across flushes regardless of what the rate does")
	void shouldAccumulateStrandedBytesAcrossFlushes() {
		final WasteAccumulation afterThree = WasteAccumulation.NONE
			.sampled(100L, 1_000L)
			.sampled(200L, 2_000L)
			.sampled(300L, 3_000L);

		assertEquals(600L, afterThree.wasteBytesGenerated());
	}

	@Test
	@DisplayName("The first flush only opens the window; the second seeds the average with its own value")
	void shouldSeedTheAverageWithTheSecondSampleRatherThanHalveIt() {
		final WasteAccumulation afterFirst = WasteAccumulation.NONE.sampled(1_000L, 1_000L);
		// nothing preceded it, so there is no interval to divide by and no rate can be claimed yet
		assertEquals(0.0d, afterFirst.rateBytesPerSecond());
		assertEquals(0.0d, afterFirst.effectiveRateBytesPerSecond(1_000L));

		// 500 bytes over half a second is 1000 B/s, and it must be reported as that rather than blended into the
		// zero above - which would report half the truth for the first measurable flush of every data store
		final WasteAccumulation afterSecond = afterFirst.sampled(500L, 1_500L);
		assertEquals(1_000.0d, afterSecond.rateBytesPerSecond(), 0.001d);
	}

	@Test
	@DisplayName("A single burst is smoothed rather than taken at face value")
	void shouldSmoothABurstAgainstTheEstablishedRate() {
		final WasteAccumulation steady = WasteAccumulation.NONE
			.sampled(0L, 1_000L)
			.sampled(1_000L, 2_000L);
		assertEquals(1_000.0d, steady.rateBytesPerSecond(), 0.001d);

		// a flush ten times the size of the established one must not make the projection ten times as urgent
		final WasteAccumulation afterBurst = steady.sampled(10_000L, 3_000L);
		assertTrue(
			afterBurst.rateBytesPerSecond() > steady.rateBytesPerSecond(),
			"A larger flush has to move the rate up: " + afterBurst
		);
		assertTrue(
			afterBurst.rateBytesPerSecond() < 10_000.0d,
			"The burst was taken at face value instead of being smoothed: " + afterBurst
		);
	}

	@Test
	@DisplayName("Two flushes inside one millisecond move the counter without dividing by zero")
	void shouldTolerateTwoFlushesInTheSameMillisecond() {
		final WasteAccumulation steady = WasteAccumulation.NONE
			.sampled(0L, 1_000L)
			.sampled(1_000L, 2_000L);

		final WasteAccumulation sameMillisecond = steady.sampled(400L, 2_000L);
		assertEquals(1_400L, sameMillisecond.wasteBytesGenerated());
		assertEquals(steady.rateBytesPerSecond(), sameMillisecond.rateBytesPerSecond());
		assertTrue(Double.isFinite(sameMillisecond.rateBytesPerSecond()));
	}

	@Test
	@DisplayName("The reported rate decays once flushes stop arriving")
	void shouldDecayTheRateWhileNothingIsWritten() {
		final WasteAccumulation steady = WasteAccumulation.NONE
			.sampled(0L, 1_000L)
			.sampled(1_000L, 2_000L);

		// still inside the cadence the rate was measured at - the measurement stands
		assertEquals(1_000.0d, steady.effectiveRateBytesPerSecond(2_500L), 0.001d);
		assertEquals(1_000.0d, steady.effectiveRateBytesPerSecond(3_000L), 0.001d);

		// ten times the cadence with no flush is ten times less waste per second than was measured
		assertEquals(100.0d, steady.effectiveRateBytesPerSecond(12_000L), 0.001d);
		// and a store nobody has written to for a long time converges on nothing at all rather than standing behind
		// a prediction made an hour ago - which is the whole reason the decay is applied on read
		assertTrue(
			steady.effectiveRateBytesPerSecond(3_602_000L) < 1.0d,
			"An hour of silence must not leave the rate where it was"
		);
	}

	@Test
	@DisplayName("Compaction resets the counter and keeps the rate")
	void shouldCarryTheRateButNotTheCounterAcrossCompaction() {
		final WasteAccumulation before = WasteAccumulation.NONE
			.sampled(0L, 1_000L)
			.sampled(1_000L, 2_000L);

		final WasteAccumulation after = before.carriedOverToCompactedFile();
		// the compacted file holds none of the stranded bytes ...
		assertEquals(0L, after.wasteBytesGenerated());
		// ... but the workload that stranded them is unchanged, so a freshly compacted store must not report
		// "no compaction foreseeable" until it happens to have flushed twice more
		assertEquals(before.rateBytesPerSecond(), after.rateBytesPerSecond());
		assertEquals(before.effectiveRateBytesPerSecond(2_500L), after.effectiveRateBytesPerSecond(2_500L));
	}

}
