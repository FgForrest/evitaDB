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

package io.evitadb.core.transaction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the arithmetic behind the write activity counters and their short-window rates.
 *
 * It is pinned here rather than through a running engine for the same reason the waste accumulation rate is: every
 * interesting property of it is about *time* - how the first two commits seed the averages, what two commits inside
 * one millisecond do, and how an idle period pulls the reported rates back towards zero. A functional test would have
 * to sleep to reach any of them, and would still be measuring the engine's commit cadence rather than this rule.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Write activity accumulation")
@Tag(ENGINE)
@Tag(MANAGEMENT)
class ActivityAccumulationTest {

	@Test
	@DisplayName("All three counters add up across commits regardless of what the rates do")
	void shouldAccumulateEveryCounterAcrossCommits() {
		final ActivityAccumulation afterThree = ActivityAccumulation.NONE
			.sampled(3, 100L, 1_000L)
			.sampled(5, 200L, 2_000L)
			.sampled(7, 300L, 3_000L);

		assertEquals(3L, afterThree.transactionsCommitted());
		assertEquals(15L, afterThree.mutationsApplied());
		assertEquals(600L, afterThree.walBytesAppended());
	}

	@Test
	@DisplayName("The first commit only opens the window; the second seeds the averages with their own values")
	void shouldSeedTheAveragesWithTheSecondSampleRatherThanHalveThem() {
		final ActivityAccumulation afterFirst = ActivityAccumulation.NONE.sampled(10, 1_000L, 1_000L);
		// nothing preceded it, so there is no interval to divide by and no rate can be claimed yet - but the
		// transaction itself is counted, which is what separates "no rate yet" from "nothing happened"
		assertEquals(1L, afterFirst.transactionsCommitted());
		assertEquals(0.0d, afterFirst.transactionRatePerSecond());
		assertEquals(0.0d, afterFirst.effectiveWalBytesPerSecond(1_000L));

		// one transaction carrying 4 mutations and 500 bytes, half a second after the previous one: 2 tx/s,
		// 8 mutations/s, 1000 B/s. Each must be reported as that rather than blended into the zero above, which
		// would report half the truth for the first measurable commit of every catalog
		final ActivityAccumulation afterSecond = afterFirst.sampled(4, 500L, 1_500L);
		assertEquals(2.0d, afterSecond.transactionRatePerSecond(), 0.001d);
		assertEquals(8.0d, afterSecond.mutationRatePerSecond(), 0.001d);
		assertEquals(1_000.0d, afterSecond.walByteRatePerSecond(), 0.001d);
	}

	@Test
	@DisplayName("The three rates are independent measurements, not one number scaled three ways")
	void shouldRateMutationsAndBytesSeparatelyFromTransactions() {
		// a steady one transaction per second, but each carrying wildly different payloads
		final ActivityAccumulation steady = ActivityAccumulation.NONE
			.sampled(0, 0L, 1_000L)
			.sampled(100, 50_000L, 2_000L);

		assertEquals(1.0d, steady.transactionRatePerSecond(), 0.001d);
		assertEquals(100.0d, steady.mutationRatePerSecond(), 0.001d);
		assertEquals(50_000.0d, steady.walByteRatePerSecond(), 0.001d);
	}

	@Test
	@DisplayName("A single burst is smoothed rather than taken at face value")
	void shouldSmoothABurstAgainstTheEstablishedRate() {
		final ActivityAccumulation steady = ActivityAccumulation.NONE
			.sampled(1, 1_000L, 1_000L)
			.sampled(1, 1_000L, 2_000L);
		assertEquals(1_000.0d, steady.walByteRatePerSecond(), 0.001d);

		// a transaction ten times the size of the established one must not make the reported rate ten times as high
		final ActivityAccumulation afterBurst = steady.sampled(1, 10_000L, 3_000L);
		assertTrue(
			afterBurst.walByteRatePerSecond() > steady.walByteRatePerSecond(),
			"A larger transaction has to move the rate up: " + afterBurst
		);
		assertTrue(
			afterBurst.walByteRatePerSecond() < 10_000.0d,
			"A single burst must not be taken at face value: " + afterBurst
		);
	}

	@Test
	@DisplayName("Two commits inside one millisecond move the counters and leave the window open")
	void shouldNotDivideByAZeroInterval() {
		final ActivityAccumulation opened = ActivityAccumulation.NONE.sampled(1, 100L, 1_000L);
		final ActivityAccumulation sameMillisecond = opened.sampled(2, 200L, 1_000L);

		assertEquals(2L, sameMillisecond.transactionsCommitted());
		assertEquals(3L, sameMillisecond.mutationsApplied());
		assertEquals(300L, sameMillisecond.walBytesAppended());
		// the window is still the one the first commit opened, so a measurable interval can still be formed against it
		assertEquals(1_000L, sameMillisecond.lastSampleAtMillis());
		assertEquals(0L, sameMillisecond.lastSampleIntervalMillis());
	}

	@Test
	@DisplayName("Commits inside an already-sampled millisecond still reach the next rate")
	void shouldCarrySameMillisecondCommitsIntoTheNextSample() {
		// the window opens at 1000, then forty-nine more commits land in that very millisecond
		ActivityAccumulation burst = ActivityAccumulation.NONE.sampled(1, 100L, 1_000L);
		for (int i = 0; i < 49; i++) {
			burst = burst.sampled(1, 100L, 1_000L);
		}
		assertEquals(50L, burst.transactionsCommitted());

		// one more a tenth of a second later. Fifty commits fall inside the 100 ms window that closes here - the
		// first one opened it and is excluded - so the rate is 500/s. Counting only this last commit against the
		// full interval reported 10/s, losing forty-nine of the fifty: a bulk load looked idle
		final ActivityAccumulation measured = burst.sampled(1, 100L, 1_100L);
		assertEquals(500.0d, measured.transactionRatePerSecond(), 0.001d);
		// one mutation each, so the mutation rate tracks the commit rate; a hundred bytes each puts the log at 50 kB/s
		assertEquals(500.0d, measured.mutationRatePerSecond(), 0.001d);
		assertEquals(50_000.0d, measured.walByteRatePerSecond(), 0.001d);
	}

	@Test
	@DisplayName("An idle catalog reports rates falling towards zero, not the load it last saw")
	void shouldDecayEveryRateWhileNothingIsCommitted() {
		// one transaction per second, 10 mutations and 1000 bytes each
		final ActivityAccumulation busy = ActivityAccumulation.NONE
			.sampled(10, 1_000L, 1_000L)
			.sampled(10, 1_000L, 2_000L);

		// read at the cadence it was measured at - the measurement stands unchanged
		assertEquals(1.0d, busy.effectiveTransactionsPerSecond(3_000L), 0.001d);
		assertEquals(10.0d, busy.effectiveMutationsPerSecond(3_000L), 0.001d);
		assertEquals(1_000.0d, busy.effectiveWalBytesPerSecond(3_000L), 0.001d);

		// ten seconds of silence against a one-second cadence: every rate is a tenth of the measurement
		assertEquals(0.1d, busy.effectiveTransactionsPerSecond(12_000L), 0.001d);
		assertEquals(1.0d, busy.effectiveMutationsPerSecond(12_000L), 0.001d);
		assertEquals(100.0d, busy.effectiveWalBytesPerSecond(12_000L), 0.001d);

		// and it keeps falling rather than settling on a floor
		assertTrue(
			busy.effectiveTransactionsPerSecond(1_000_000L) < 0.01d,
			"A catalog idle for a quarter of an hour must not still claim a commit rate: " + busy
		);

		// the stored measurement is untouched by any of this - it is the undecayed number and stays comparable
		assertEquals(1.0d, busy.transactionRatePerSecond(), 0.001d);
	}

	@Test
	@DisplayName("A catalog that has never committed reports nothing rather than a rate of zero commits per second")
	void shouldReportNoRateBeforeAnythingIsCommitted() {
		assertEquals(0L, ActivityAccumulation.NONE.transactionsCommitted());
		assertEquals(0.0d, ActivityAccumulation.NONE.effectiveTransactionsPerSecond(1_000L));
		assertEquals(0.0d, ActivityAccumulation.NONE.effectiveMutationsPerSecond(1_000L));
		assertEquals(0.0d, ActivityAccumulation.NONE.effectiveWalBytesPerSecond(1_000L));
	}

}
