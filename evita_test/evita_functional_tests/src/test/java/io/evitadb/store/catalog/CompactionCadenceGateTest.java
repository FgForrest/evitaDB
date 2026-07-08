/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.store.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Random;

import static io.evitadb.store.catalog.DefaultCatalogPersistenceService.isCompactionIntervalElapsed;
import static io.evitadb.store.catalog.DefaultCatalogPersistenceService.shouldCompact;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the compaction cadence decision functions
 * {@link DefaultCatalogPersistenceService#shouldCompact(boolean, double, double, double, boolean)} and
 * {@link DefaultCatalogPersistenceService#isCompactionIntervalElapsed(long, long, long)} that back both compaction
 * trigger sites (entity-collection flush and catalog-file bootstrap) - see
 * `docs/plans/optimizations/compaction-waste-threshold-auto-tuning.md` §3 and §7 (test gates 1-5).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(STORAGE)
@Tag(MANAGEMENT)
@DisplayName("Compaction cadence gate")
class CompactionCadenceGateTest {

	@Nested
	@DisplayName("isCompactionIntervalElapsed")
	class IntervalElapsedTest {

		@Test
		@DisplayName("should always be elapsed when the interval is disabled (0)")
		void shouldAlwaysBeElapsedWhenDisabled() {
			assertTrue(isCompactionIntervalElapsed(1_000L, 999L, 0L));
			assertTrue(isCompactionIntervalElapsed(1_000L, 1_000L, 0L));
		}

		@Test
		@DisplayName("should not be elapsed before the configured interval passes")
		void shouldNotBeElapsedBeforeInterval() {
			final long lastCompactionAtMillis = 0L;
			final long minCompactionIntervalMillis = 10_000L;
			assertFalse(isCompactionIntervalElapsed(9_999L, lastCompactionAtMillis, minCompactionIntervalMillis));
		}

		@Test
		@DisplayName("should be elapsed exactly at the configured interval boundary")
		void shouldBeElapsedAtBoundary() {
			final long lastCompactionAtMillis = 0L;
			final long minCompactionIntervalMillis = 10_000L;
			assertTrue(isCompactionIntervalElapsed(10_000L, lastCompactionAtMillis, minCompactionIntervalMillis));
		}

		@Test
		@DisplayName("should be elapsed well past the configured interval")
		void shouldBeElapsedPastInterval() {
			final long lastCompactionAtMillis = 0L;
			final long minCompactionIntervalMillis = 10_000L;
			assertTrue(isCompactionIntervalElapsed(60_000L, lastCompactionAtMillis, minCompactionIntervalMillis));
		}
	}

	@Nested
	@DisplayName("shouldCompact - trigger truth table (gate 1)")
	class TruthTableTest {

		@Test
		@DisplayName("should never compact when the file is not big enough, regardless of waste or interval")
		void shouldNotCompactWhenFileTooSmall() {
			assertFalse(shouldCompact(false, 0.0d, 0.5d, 0.5d, true));
			assertFalse(shouldCompact(false, 0.0d, 0.5d, 0.1d, true));
		}

		@Test
		@DisplayName("override branch fires before the interval elapses when waste exceeds maxWasteActiveShare")
		void shouldCompactViaOverrideBeforeIntervalElapses() {
			assertTrue(shouldCompact(true, 0.05d, 0.5d, 0.1d, false));
		}

		@Test
		@DisplayName("normal branch fires at/after the interval when active share is below minimalActiveRecordShare")
		void shouldCompactViaNormalBranchAfterInterval() {
			assertTrue(shouldCompact(true, 0.4d, 0.5d, 0.1d, true));
		}

		@Test
		@DisplayName("nothing fires when the file is big enough but not yet worth compacting")
		void shouldNotCompactWhenNotWorthwhile() {
			assertFalse(shouldCompact(true, 0.6d, 0.5d, 0.1d, true));
			assertFalse(shouldCompact(true, 0.6d, 0.5d, 0.1d, false));
		}

		@Test
		@DisplayName("nothing fires when worthwhile but the interval has not elapsed and waste is above the override")
		void shouldNotCompactWhenIntervalNotElapsedAndBelowOverride() {
			assertFalse(shouldCompact(true, 0.4d, 0.5d, 0.1d, false));
		}
	}

	@Nested
	@DisplayName("shouldCompact - BWC gate-disabled equivalence (gate 2)")
	class BackwardCompatibilityEquivalenceTest {

		@RepeatedTest(200)
		@DisplayName("with T=0 and maxWaste=A (the gate explicitly disabled), the new trigger is equivalent to the old `active < A && file > F` condition")
		void shouldBeEquivalentToOldConditionWhenGateDisabled() {
			final Random random = new Random();
			final boolean fileBigEnough = random.nextBoolean();
			final double activeRecordShare = random.nextDouble();
			final double minimalActiveRecordShare = random.nextDouble();
			// gate explicitly disabled (T=0 -> always "elapsed", override == worthwhile threshold) - note these
			// are NOT StorageOptions' shipped defaults (which enable the gate out of the box), just the mathematical
			// configuration under which the new trigger must collapse to the pre-#760 condition exactly
			final boolean intervalElapsed = isCompactionIntervalElapsed(random.nextLong(1_000_000_000L), 0L, 0L);
			final double maxWasteActiveShare = minimalActiveRecordShare;

			final boolean oldCondition = fileBigEnough && activeRecordShare < minimalActiveRecordShare;
			final boolean newCondition = shouldCompact(
				fileBigEnough, activeRecordShare, minimalActiveRecordShare, maxWasteActiveShare, intervalElapsed
			);

			assertEquals(oldCondition, newCondition);
		}
	}

	@Nested
	@DisplayName("shouldCompact - interval deferral (gate 3)")
	class IntervalDeferralTest {

		@Test
		@DisplayName("a hot file that reaches worthwhile waste before T waits until T (or the override) fires")
		void shouldDeferUntilIntervalOrOverride() {
			final double activeRecordShare = 0.3d; // < A(0.5), worthwhile, but still above maxWaste(0.1)
			// interval not yet elapsed -> deferred, no compaction despite worthwhile waste
			assertFalse(shouldCompact(true, activeRecordShare, 0.5d, 0.1d, false));
			// interval elapsed -> normal branch now fires
			assertTrue(shouldCompact(true, activeRecordShare, 0.5d, 0.1d, true));
		}
	}

	@Nested
	@DisplayName("shouldCompact - override precedence (gate 4)")
	class OverridePrecedenceTest {

		@Test
		@DisplayName("waste below maxWasteActiveShare compacts immediately, ignoring the interval")
		void shouldCompactImmediatelyRegardlessOfInterval() {
			final double activeRecordShare = 0.05d; // < maxWaste(0.1)
			assertTrue(shouldCompact(true, activeRecordShare, 0.5d, 0.1d, false));
			assertTrue(shouldCompact(true, activeRecordShare, 0.5d, 0.1d, true));
		}
	}

	@Nested
	@DisplayName("shouldCompact - cold non-over-compaction (gate 5)")
	class ColdNonOverCompactionTest {

		@Test
		@DisplayName("a near-clean file is never compacted just because T elapsed")
		void shouldNotCompactCleanFileEvenAfterIntervalElapses() {
			final double activeRecordShare = 0.9d; // >= A(0.5), not worthwhile
			assertFalse(shouldCompact(true, activeRecordShare, 0.5d, 0.1d, true));
		}
	}

	@ParameterizedTest(name = "fileBigEnough={0}, active={1}, A={2}, maxWaste={3}, intervalElapsed={4} -> {5}")
	@DisplayName("shouldCompact - explicit truth-table rows")
	@CsvSource({
		"false, 0.0,  0.5, 0.5, true,  false",
		"true,  0.05, 0.5, 0.1, false, true",
		"true,  0.4,  0.5, 0.1, true,  true",
		"true,  0.4,  0.5, 0.1, false, false",
		"true,  0.6,  0.5, 0.1, true,  false",
		"true,  0.6,  0.5, 0.1, false, false",
	})
	void shouldMatchExplicitTruthTableRows(
		boolean fileBigEnough,
		double activeRecordShare,
		double minimalActiveRecordShare,
		double maxWasteActiveShare,
		boolean intervalElapsed,
		boolean expected
	) {
		assertEquals(
			expected,
			shouldCompact(fileBigEnough, activeRecordShare, minimalActiveRecordShare, maxWasteActiveShare, intervalElapsed)
		);
	}

}
