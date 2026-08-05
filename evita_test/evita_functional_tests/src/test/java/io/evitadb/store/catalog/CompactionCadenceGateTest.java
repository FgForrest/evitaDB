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

import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.store.settings.StorageSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.util.Random;

import static io.evitadb.store.catalog.DefaultCatalogPersistenceService.isCompactionIntervalElapsed;
import static io.evitadb.store.catalog.DefaultCatalogPersistenceService.projectCompactionTime;
import static io.evitadb.store.catalog.DefaultCatalogPersistenceService.shouldCompact;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

	/**
	 * The forward-looking half of the same decision - *when* the predicate above will start holding. Pinned here
	 * rather than through a running engine because every case is a threshold being approached over time, which a
	 * functional test can only reach by waiting for it.
	 */
	@Nested
	@DisplayName("projectCompactionTime")
	class CompactionProjectionTest {
		private static final long NOW = 1_000_000L;

		@Test
		@DisplayName("no rate means no projection, however wasteful the file already is")
		void shouldProjectNothingWithoutARate() {
			// a distant date would render as a real answer on a management screen; there is no crossing to report,
			// because nothing is stranding bytes
			assertNull(projectCompactionTime(1_000L, 10_000L, 0.0d, settings(0.5d, 0.5d, 100L, 0L), NOW, NOW));
		}

		@Test
		@DisplayName("an empty store projects nothing even while bytes are being stranded")
		void shouldProjectNothingForAStoreWithNoLiveBytes() {
			// the crossing is derived from `liveBytes / targetShare`, which is `0` for an empty store - it would
			// project a crossing that has already happened for a file that cannot be worth compacting
			assertNull(projectCompactionTime(0L, 10_000L, 500.0d, settings(0.5d, 0.5d, 100L, 0L), NOW, NOW));
		}

		@Test
		@DisplayName("the crossing is where the growing file leaves the configured share behind")
		void shouldProjectTheShareCrossing() {
			// 1000 live bytes in a 1500-byte file is a share of 0.667; it falls to 0.5 at 2000 bytes, which 100 B/s
			// of waste reaches in five seconds
			final OffsetDateTime projected = projectCompactionTime(
				1_000L, 1_500L, 100.0d, settings(0.5d, 0.5d, 100L, 0L), NOW, NOW
			);
			assertNotNull(projected);
			assertEquals(NOW + 5_000L, projected.toInstant().toEpochMilli());
		}

		@Test
		@DisplayName("the earlier of the two thresholds wins when the cadence gate is open")
		void shouldTakeTheEarlierCrossingWhenTheGateIsOpen() {
			// the softer threshold (0.5, reached at 2000 bytes) is crossed before the hard override (0.25, reached
			// at 4000), and with the interval disabled nothing holds it back
			final OffsetDateTime projected = projectCompactionTime(
				1_000L, 1_500L, 100.0d, settings(0.5d, 0.25d, 100L, 0L), NOW, NOW
			);
			assertNotNull(projected);
			assertEquals(NOW + 5_000L, projected.toInstant().toEpochMilli());
		}

		@Test
		@DisplayName("a closed cadence gate pushes the projection out to the hard override")
		void shouldFallBackToTheHardOverrideWhileTheGateIsClosed() {
			// same two thresholds, but the softer one cannot fire for another hour - so the answer is the hard
			// override's own crossing at 4000 bytes (25 s at 100 B/s), not the softer one's five seconds
			final OffsetDateTime projected = projectCompactionTime(
				1_000L, 1_500L, 100.0d, settings(0.5d, 0.25d, 100L, 3_600_000L), NOW, NOW
			);
			assertNotNull(projected);
			assertEquals(NOW + 25_000L, projected.toInstant().toEpochMilli());
		}

		@Test
		@DisplayName("a file below the size threshold cannot trigger, however wasteful it is")
		void shouldWaitForTheFileToGrowPastTheSizeThreshold() {
			// the share is already below both thresholds, so the share crossing is "now" - but nothing triggers
			// until the file passes 10 000 bytes, which 100 B/s reaches in 85 seconds
			final OffsetDateTime projected = projectCompactionTime(
				100L, 1_500L, 100.0d, settings(0.5d, 0.5d, 10_000L, 0L), NOW, NOW
			);
			assertNotNull(projected);
			assertEquals(NOW + 85_000L, projected.toInstant().toEpochMilli());
		}

		@Test
		@DisplayName("a crossing beyond the reporting horizon is reported as no crossing at all")
		void shouldNotProjectBeyondTheHorizon() {
			// a trickle of one byte per second against a large live set puts the crossing decades out, which is
			// arithmetic rather than information
			assertNull(projectCompactionTime(
				10_000_000_000L, 1_500L, 1.0d, settings(0.5d, 0.5d, 100L, 0L), NOW, NOW
			));
		}

		/**
		 * Builds storage settings carrying only the four thresholds the projection reads.
		 *
		 * @param minimalActiveRecordShare          share below which compaction triggers once the interval elapsed
		 * @param maxWasteActiveShare               share below which compaction triggers regardless of the interval
		 * @param fileSizeCompactionThresholdBytes  size below which compaction never triggers
		 * @param minCompactionIntervalMilliseconds minimum spacing between two compactions of the same file
		 * @return settings carrying those thresholds
		 */
		@Nonnull
		private StorageSettings settings(
			double minimalActiveRecordShare,
			double maxWasteActiveShare,
			long fileSizeCompactionThresholdBytes,
			long minCompactionIntervalMilliseconds
		) {
			return new StorageSettings(
				StorageOptions.builder()
					.minimalActiveRecordShare(minimalActiveRecordShare)
					.maxWasteActiveShare(maxWasteActiveShare)
					.fileSizeCompactionThresholdBytes(fileSizeCompactionThresholdBytes)
					.minCompactionIntervalMilliseconds(minCompactionIntervalMilliseconds)
					.build(),
				TransactionOptions.builder().build()
			);
		}
	}

}
