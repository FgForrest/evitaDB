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

package io.evitadb.core.query.extraResult.translator.histogram.producer;

import io.evitadb.api.exception.InvalidHistogramBucketCountException;
import io.evitadb.api.query.require.HistogramBehavior;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogramContract.CacheableBucket;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link RangeHistogramDataCruncher} renders range-bucketed histograms with overlap
 * semantics rather than the point-histogram semantics of {@link HistogramDataCruncher}.
 *
 * The cruncher consumes the raw range sweep emitted by
 * {@link io.evitadb.index.attribute.FilterIndex#getRangeHistogramOfAllRecords(Class, int)}: one
 * {@link ValueToRecordBitmap} per range-boundary threshold whose bitmap is the rolling active set
 * (every record whose `[from, to]` covers that threshold). The cruncher must:
 *
 * - reconstruct each distinct record's `[fromValue, toValue]` interval from those snapshots,
 * - count, per output bucket, the number of DISTINCT records whose interval OVERLAPS the bucket,
 * - report `overallCount` as the number of DISTINCT records (union cardinality), never the bucket sum.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("RangeHistogramDataCruncher — overlap semantics")
@Tag(ENGINE)
@Tag(HISTOGRAM)
class RangeHistogramDataCruncherTest {

	/**
	 * Builds a single source bucket keyed by `threshold` carrying the supplied record ids in its
	 * active-set bitmap.
	 */
	@Nonnull
	private static ValueToRecordBitmap bucket(int threshold, @Nonnull int... recordIds) {
		return new ValueToRecordBitmap(threshold, new BaseBitmap(recordIds));
	}

	/**
	 * Builds a single source bucket keyed by a {@link BigDecimal} `threshold` carrying the supplied record
	 * ids in its active-set bitmap. Used by the decimal-scale tests so the cruncher exercises its
	 * `BigDecimal` threshold projection path.
	 */
	@Nonnull
	private static ValueToRecordBitmap bucket(@Nonnull BigDecimal threshold, @Nonnull int... recordIds) {
		return new ValueToRecordBitmap(threshold, new BaseBitmap(recordIds));
	}

	@Test
	@DisplayName("Single record spanning the whole range renders a solid bar with overallCount = 1")
	void shouldRenderSolidBarForSingleRecordSpanningRange() {
		// one record (R = 1) active at 12 ascending thresholds T0..T11 = 0..11 — its range is [0, 11]
		final ValueToRecordBitmap[] source = new ValueToRecordBitmap[12];
		for (int i = 0; i < 12; i++) {
			source[i] = bucket(i, 1);
		}

		final RangeHistogramDataCruncher cruncher = new RangeHistogramDataCruncher(source, 30, 0);

		final CacheableBucket[] buckets = cruncher.getHistogram();
		assertTrue(buckets.length > 0, "must produce at least one bucket");
		// solid bar: every emitted bucket overlapping [0, 11] reports occurrences = 1
		for (final CacheableBucket b : buckets) {
			assertEquals(
				1, b.occurrences(),
				"single record must overlap every bucket of its own range — bucket " + b.threshold()
			);
		}
		// distinct-record cardinality, NOT the sum of per-bucket occurrences
		assertEquals(1, cruncher.getOverallCount(), "overallCount must be the distinct record count");
		// uniform single-record histogram renders full-width bars across its whole range
		for (final CacheableBucket b : buckets) {
			assertEquals(
				0, b.relativeFrequency().compareTo(new BigDecimal("100")),
				"uniform histogram must render full-width bars (relativeFrequency = 100)"
			);
		}
	}

	@Test
	@DisplayName("Four overlapping ranges yield overlap counts 1,2,3,3,2 and overallCount = 4")
	void shouldCountDistinctOverlapsAcrossFiveUniformBuckets() {
		// R1[10,20] R2[15,25] R3[20,30] R4[25,35] — sweep over distinct endpoints 10,15,20,25,30,35
		// active sets per threshold (closed-interval semantics):
		//   10 -> {1}
		//   15 -> {1,2}
		//   20 -> {1,2,3}
		//   25 -> {2,3,4}
		//   30 -> {3,4}
		//   35 -> {4}
		final ValueToRecordBitmap[] source = {
			bucket(10, 1),
			bucket(15, 1, 2),
			bucket(20, 1, 2, 3),
			bucket(25, 2, 3, 4),
			bucket(30, 3, 4),
			bucket(35, 4)
		};

		// 5 uniform buckets over [10,35] -> B0[10,15) B1[15,20) B2[20,25) B3[25,30) B4[30,35]
		final RangeHistogramDataCruncher cruncher = new RangeHistogramDataCruncher(source, 5, 0);

		final CacheableBucket[] buckets = cruncher.getHistogram();
		assertEquals(5, buckets.length, "expected 5 uniform buckets over [10,35]");

		final int[] expectedOccurrences = {1, 2, 3, 3, 2};
		final BigDecimal[] expectedThresholds = {
			BigDecimal.valueOf(10), BigDecimal.valueOf(15), BigDecimal.valueOf(20),
			BigDecimal.valueOf(25), BigDecimal.valueOf(30)
		};
		for (int i = 0; i < buckets.length; i++) {
			assertEquals(
				0, buckets[i].threshold().compareTo(expectedThresholds[i]),
				"bucket " + i + " threshold mismatch — got " + buckets[i].threshold()
			);
			assertEquals(
				expectedOccurrences[i], buckets[i].occurrences(),
				"bucket " + i + " overlap occurrences mismatch"
			);
		}
		assertEquals(0, cruncher.getMaxValue().compareTo(BigDecimal.valueOf(35)), "max must be 35");
		// distinct records = 4, NOT Σ occurrences (1+2+3+3+2 = 11)
		assertEquals(4, cruncher.getOverallCount(), "overallCount must be the distinct record count");
		// relativeFrequency = occurrences / maxOccurrences * 100; maxOccurrences = 3
		assertEquals(
			0, buckets[2].relativeFrequency().compareTo(new BigDecimal("100")),
			"busiest bucket must render a full-width bar"
		);
	}

	@Test
	@DisplayName("Bucket count of 1 or 0 is rejected with InvalidHistogramBucketCountException")
	void shouldThrowInvalidHistogramBucketCountExceptionWhenBucketCountIsOne() {
		// the cruncher contract requires bucketCount > 1 — a single- or zero-bucket histogram is meaningless
		final ValueToRecordBitmap[] source = {bucket(0, 1), bucket(5, 1)};
		assertThrows(
			InvalidHistogramBucketCountException.class,
			() -> new RangeHistogramDataCruncher(source, 1, 0),
			"bucketCount = 1 must be rejected"
		);
		assertThrows(
			InvalidHistogramBucketCountException.class,
			() -> new RangeHistogramDataCruncher(source, 0, 0),
			"bucketCount = 0 must be rejected"
		);
	}

	@Test
	@DisplayName("Empty source data is rejected with IllegalArgumentException")
	void shouldThrowWhenSourceDataIsEmpty() {
		final ValueToRecordBitmap[] source = new ValueToRecordBitmap[0];
		final IllegalArgumentException ex = assertThrows(
			IllegalArgumentException.class,
			() -> new RangeHistogramDataCruncher(source, 5, 0)
		);
		assertTrue(
			ex.getMessage() != null && ex.getMessage().contains("must not be empty"),
			"message should explain the empty-source rejection — got: " + ex.getMessage()
		);
	}

	@Test
	@DisplayName("Degenerate single-value span (minKey == maxKey) emits one full-width bucket")
	void shouldEmitSingleBucketForDegenerateSingleValueSpan() {
		// every threshold collapses onto the same key 7 — the whole sweep covers a single value
		final ValueToRecordBitmap[] source = {
			bucket(7, 1, 2, 3)
		};

		final RangeHistogramDataCruncher cruncher = new RangeHistogramDataCruncher(source, 10, 0);

		final CacheableBucket[] buckets = cruncher.getHistogram();
		assertEquals(1, buckets.length, "a single-value span must collapse to exactly one bucket");
		assertEquals(0, buckets[0].threshold().compareTo(BigDecimal.valueOf(7)), "the sole bucket sits at key 7");
		// occurrences equals the number of distinct records — all three sit at the single value
		assertEquals(3, buckets[0].occurrences(), "the bucket must count every distinct record");
		assertEquals(
			0, buckets[0].relativeFrequency().compareTo(new BigDecimal("100")),
			"a single populated bucket is by definition the busiest — full-width bar"
		);
		assertEquals(3, cruncher.getOverallCount(), "overallCount must be the distinct record count");
		assertEquals(0, cruncher.getMaxValue().compareTo(BigDecimal.valueOf(7)), "max must be the single value");
	}

	@Test
	@DisplayName("Effective bucket count is capped to the value span when more buckets are requested")
	void shouldCapEffectiveBucketCountToValueSpanWhenBucketCountExceedsSpan() {
		// thresholds 0,1,2 -> span = 2; requesting 30 buckets must collapse to exactly 2 distinct-width buckets
		final ValueToRecordBitmap[] source = {
			bucket(0, 1),
			bucket(1, 1, 2),
			bucket(2, 2)
		};

		final RangeHistogramDataCruncher cruncher = new RangeHistogramDataCruncher(source, 30, 0);

		final CacheableBucket[] buckets = cruncher.getHistogram();
		assertEquals(2, buckets.length, "bucket count must be capped to the value span of 2");
		// thresholds must be strictly increasing so no zero-width / duplicate-threshold bucket is emitted
		for (int i = 1; i < buckets.length; i++) {
			assertTrue(
				buckets[i].threshold().compareTo(buckets[i - 1].threshold()) > 0,
				"bucket " + i + " threshold must strictly exceed bucket " + (i - 1)
			);
		}
	}

	@Test
	@DisplayName("Output bucket thresholds are strictly increasing after capping")
	void shouldProduceStrictlyIncreasingThresholdsUnderFlooringCollisions() {
		// dense small span (0..4, span 4) with a moderate request — flooring could collide adjacent lower
		// bounds; the cruncher's strict-increase fixup (post-cap) must keep thresholds monotonic
		final ValueToRecordBitmap[] source = {
			bucket(0, 1),
			bucket(1, 1),
			bucket(2, 1),
			bucket(3, 1),
			bucket(4, 1)
		};

		final RangeHistogramDataCruncher cruncher = new RangeHistogramDataCruncher(source, 4, 0);

		final CacheableBucket[] buckets = cruncher.getHistogram();
		assertTrue(buckets.length >= 2, "must emit multiple buckets for a span of 4");
		// the strict-increase guarantee holds regardless of whether capping or the fixup resolved a collision
		for (int i = 1; i < buckets.length; i++) {
			assertTrue(
				buckets[i].threshold().compareTo(buckets[i - 1].threshold()) > 0,
				"adjacent thresholds must strictly increase — collision at bucket " + i
			);
		}
	}

	@Test
	@DisplayName("Open-ended record active across every threshold renders a solid bar")
	void shouldRenderSolidBarForOpenEndedRecordActiveAcrossAllThresholds() {
		// record 9 is active at every threshold (spans the whole range); short-lived records appear locally
		final ValueToRecordBitmap[] source = {
			bucket(0, 9, 1),
			bucket(1, 9, 1),
			bucket(2, 9),
			bucket(3, 9, 2),
			bucket(4, 9, 2)
		};

		final RangeHistogramDataCruncher cruncher = new RangeHistogramDataCruncher(source, 4, 0);

		final CacheableBucket[] buckets = cruncher.getHistogram();
		assertTrue(buckets.length >= 2, "span of 4 must produce multiple buckets");
		// the spanning record overlaps every bucket, so each bucket counts at least the spanning record
		for (final CacheableBucket b : buckets) {
			assertTrue(
				b.occurrences() >= 1,
				"every bucket must include the all-spanning record — bucket " + b.threshold()
			);
		}
		// distinct records = {9, 1, 2} = 3
		assertEquals(3, cruncher.getOverallCount(), "overallCount must be the distinct record union (9,1,2)");
	}

	@Test
	@DisplayName("Disjoint records are not counted in non-overlapping buckets")
	void shouldNotCountDisjointRecordInNonOverlappingBuckets() {
		// R1 spans keys [0,2], R2 spans keys [8,10]; the value span is [0,10] and the records never overlap
		final ValueToRecordBitmap[] source = {
			bucket(0, 1),
			bucket(1, 1),
			bucket(2, 1),
			bucket(8, 2),
			bucket(9, 2),
			bucket(10, 2)
		};

		// 5 uniform buckets over span 10 -> lower bounds 0,2,4,6,8; last bucket [8,10]
		final RangeHistogramDataCruncher cruncher = new RangeHistogramDataCruncher(source, 5, 0);

		final CacheableBucket[] buckets = cruncher.getHistogram();
		assertEquals(5, buckets.length, "expected 5 uniform buckets over [0,10]");
		// lowest bucket [0,2) overlaps only R1
		assertEquals(1, buckets[0].occurrences(), "lowest bucket must count only R1");
		// a middle bucket [4,6) sits in the gap between the two records
		assertEquals(0, buckets[2].occurrences(), "middle bucket must count no record (the gap)");
		// highest bucket [8,10] overlaps only R2
		assertEquals(1, buckets[4].occurrences(), "highest bucket must count only R2");
		assertEquals(2, cruncher.getOverallCount(), "overallCount must be the two distinct records");
	}

	@Test
	@DisplayName("BigDecimal thresholds round-trip at the requested decimal scale")
	void shouldRoundTripBigDecimalThresholdsAtRequestedScale() {
		// decimalPlaces = 2 -> keys 100,150,200; span 100, request 2 buckets -> lower bounds 100 (1.00) & 150 (1.50)
		final ValueToRecordBitmap[] source = {
			bucket(new BigDecimal("1.00"), 1),
			bucket(new BigDecimal("1.50"), 1),
			bucket(new BigDecimal("2.00"), 1)
		};

		final RangeHistogramDataCruncher cruncher = new RangeHistogramDataCruncher(source, 2, 2);

		final CacheableBucket[] buckets = cruncher.getHistogram();
		assertEquals(2, buckets.length, "span 100 with request 2 must yield 2 buckets");
		// thresholds must be emitted at scale 2 and be numerically correct
		assertEquals(2, buckets[0].threshold().scale(), "first threshold must carry scale 2");
		assertEquals(0, buckets[0].threshold().compareTo(new BigDecimal("1.00")), "first threshold must equal 1.00");
		assertEquals(2, buckets[1].threshold().scale(), "second threshold must carry scale 2");
		assertEquals(0, buckets[1].threshold().compareTo(new BigDecimal("1.50")), "second threshold must equal 1.50");
		// inclusive upper bound at scale 2
		assertEquals(2, cruncher.getMaxValue().scale(), "max must carry scale 2");
		assertEquals(0, cruncher.getMaxValue().compareTo(new BigDecimal("2.00")), "max must equal 2.00");
	}

	@Test
	@DisplayName("Last bucket is closed and inclusive of the max value")
	void shouldVerifyLastBucketIsClosedInclusiveOfMaxValue() {
		// R1 spans keys [0,1] (entirely below the last bucket); R2 sits exactly at the max key 4
		final ValueToRecordBitmap[] source = {
			bucket(0, 1),
			bucket(1, 1),
			bucket(4, 2)
		};

		// span 4, request 2 -> lower bounds 0 & 2; last bucket [2,4] must include the record sitting at 4
		final RangeHistogramDataCruncher cruncher = new RangeHistogramDataCruncher(source, 2, 0);

		final CacheableBucket[] buckets = cruncher.getHistogram();
		assertEquals(2, buckets.length, "span 4 with request 2 must yield 2 buckets");
		assertEquals(0, cruncher.getMaxValue().compareTo(BigDecimal.valueOf(4)), "max must be 4");
		// the first bucket [0,2) overlaps only R1, which ends at key 1
		assertEquals(1, buckets[0].occurrences(), "first bucket must count only the record ending at key 1");
		// the last bucket [2,4] is closed at the top, so the record at key 4 must be counted there
		assertEquals(
			1, buckets[1].occurrences(),
			"the closed last bucket must include the record whose interval ends at the max value"
		);
		assertEquals(2, cruncher.getOverallCount(), "overallCount must be the two distinct records");
	}

	@Test
	@DisplayName("OPTIMIZED widens the grid to collapse a wide empty coverage gap")
	void shouldCollapseEmptyGapUnderOptimizedBehavior() {
		// R1 spans keys [0,2], R2 spans keys [18,20]; the whole middle of [0,20] is uncovered. A 10-bucket
		// STANDARD grid leaves a long run of empty buckets across the gap; OPTIMIZED must widen the grid so the
		// empty run shrinks, while preserving solid-overlap occurrences and the distinct overall count.
		final ValueToRecordBitmap[] source = {
			bucket(0, 1), bucket(1, 1), bucket(2, 1),
			bucket(18, 2), bucket(19, 2), bucket(20, 2)
		};

		final CacheableBucket[] standard = new RangeHistogramDataCruncher(
			source, 10, 0, HistogramBehavior.STANDARD
		).getHistogram();
		final RangeHistogramDataCruncher optimizedCruncher = new RangeHistogramDataCruncher(
			source, 10, 0, HistogramBehavior.OPTIMIZED
		);
		final CacheableBucket[] optimized = optimizedCruncher.getHistogram();

		assertTrue(
			optimized.length < standard.length,
			"OPTIMIZED must emit fewer buckets than the " + standard.length + "-bucket STANDARD grid"
		);
		assertTrue(
			longestEmptyRun(optimized) < longestEmptyRun(standard),
			"OPTIMIZED must shrink the longest run of empty buckets (was " + longestEmptyRun(standard) + ")"
		);
		// occurrences stay solid-overlap: both records are still present, neither dropped nor double-counted
		assertEquals(1, optimized[0].occurrences(), "first bucket must still count R1");
		assertEquals(1, optimized[optimized.length - 1].occurrences(), "last bucket must still count R2");
		assertEquals(2, optimizedCruncher.getOverallCount(), "overallCount stays the distinct record count");
		assertEquals(0, optimizedCruncher.getMaxValue().compareTo(BigDecimal.valueOf(20)), "max must stay 20");
	}

	@Test
	@DisplayName("OPTIMIZED leaves a gap-free grid identical to STANDARD")
	void shouldLeaveGapFreeGridUnchangedUnderOptimizedBehavior() {
		// the four overlapping ranges fill every bucket (occurrences 1,2,3,3,2 — no empties), so OPTIMIZED has
		// no gap to collapse and must return exactly the STANDARD grid
		final ValueToRecordBitmap[] source = {
			bucket(10, 1), bucket(15, 1, 2), bucket(20, 1, 2, 3),
			bucket(25, 2, 3, 4), bucket(30, 3, 4), bucket(35, 4)
		};

		final CacheableBucket[] standard = new RangeHistogramDataCruncher(
			source, 5, 0, HistogramBehavior.STANDARD
		).getHistogram();
		final CacheableBucket[] optimized = new RangeHistogramDataCruncher(
			source, 5, 0, HistogramBehavior.OPTIMIZED
		).getHistogram();

		assertEquals(standard.length, optimized.length, "gap-free OPTIMIZED grid must match STANDARD bucket count");
		for (int i = 0; i < standard.length; i++) {
			assertEquals(
				0, optimized[i].threshold().compareTo(standard[i].threshold()),
				"bucket " + i + " threshold must match STANDARD"
			);
			assertEquals(
				standard[i].occurrences(), optimized[i].occurrences(),
				"bucket " + i + " occurrences must match STANDARD"
			);
		}
	}

	@Test
	@DisplayName("Frequency-equalised behaviors are rejected — the equaliser serves them instead")
	void shouldRejectEqualizedBehaviors() {
		final ValueToRecordBitmap[] source = {bucket(0, 1), bucket(5, 1)};
		// EQUALIZED / EQUALIZED_OPTIMIZED are handled by EqualizedHistogramDataCruncher over the same sweep;
		// this cruncher serves only the equal-width family and must reject the equalised behaviors outright
		assertThrows(
			IllegalArgumentException.class,
			() -> new RangeHistogramDataCruncher(source, 5, 0, HistogramBehavior.EQUALIZED),
			"EQUALIZED must be rejected by the overlap cruncher"
		);
		assertThrows(
			IllegalArgumentException.class,
			() -> new RangeHistogramDataCruncher(source, 5, 0, HistogramBehavior.EQUALIZED_OPTIMIZED),
			"EQUALIZED_OPTIMIZED must be rejected by the overlap cruncher"
		);
	}

	@Test
	@DisplayName("The three-arg constructor defaults to STANDARD behavior")
	void shouldDefaultToStandardBehaviorForThreeArgConstructor() {
		final ValueToRecordBitmap[] source = {
			bucket(10, 1), bucket(15, 1, 2), bucket(20, 1, 2, 3),
			bucket(25, 2, 3, 4), bucket(30, 3, 4), bucket(35, 4)
		};
		final CacheableBucket[] threeArg = new RangeHistogramDataCruncher(source, 5, 0).getHistogram();
		final CacheableBucket[] explicitStandard = new RangeHistogramDataCruncher(
			source, 5, 0, HistogramBehavior.STANDARD
		).getHistogram();
		assertEquals(explicitStandard.length, threeArg.length, "default must equal explicit STANDARD");
		for (int i = 0; i < explicitStandard.length; i++) {
			assertEquals(
				explicitStandard[i].occurrences(), threeArg[i].occurrences(),
				"bucket " + i + " occurrences must match explicit STANDARD"
			);
		}
	}

	@Test
	@DisplayName("OPTIMIZED collapses to two buckets when the gap leaves at most two non-empty columns")
	void shouldCollapseToTwoBucketsWhenGapLeavesAtMostTwoNonEmptyColumns() {
		// R1 spans keys [0,1], R2 spans keys [20,21]; on a 10-bucket STANDARD grid only the first and last
		// buckets are non-empty, so the longest empty run leaves at most two non-empty columns and the OPTIMIZED
		// heuristic collapses the grid to exactly two buckets — one per cluster.
		final ValueToRecordBitmap[] source = {
			bucket(0, 1), bucket(1, 1),
			bucket(20, 2), bucket(21, 2)
		};

		final RangeHistogramDataCruncher optimizedCruncher = new RangeHistogramDataCruncher(
			source, 10, 0, HistogramBehavior.OPTIMIZED
		);
		final CacheableBucket[] optimized = optimizedCruncher.getHistogram();

		assertEquals(2, optimized.length, "the gap must collapse the grid to exactly two buckets");
		// each cluster lands in its own bucket, neither dropped
		assertEquals(1, optimized[0].occurrences(), "first bucket must count R1");
		assertEquals(1, optimized[1].occurrences(), "second bucket must count R2");
		assertEquals(2, optimizedCruncher.getOverallCount(), "overallCount stays the distinct record count");
		assertEquals(0, optimizedCruncher.getMaxValue().compareTo(BigDecimal.valueOf(21)), "max must stay 21");
	}

	@Test
	@DisplayName("OPTIMIZED collapses the longest of several empty runs and keeps every cluster")
	void shouldCollapseLongestOfMultipleEmptyRunsUnderOptimized() {
		// three clusters separated by two empty runs of different lengths: a short gap between R1[0,2] and
		// R2[40,42] and a longer gap between R2 and R3[80,82]. OPTIMIZED targets the longest run, so the widened
		// grid must reduce the longest empty run relative to STANDARD without dropping any cluster.
		final ValueToRecordBitmap[] source = {
			bucket(0, 1), bucket(1, 1), bucket(2, 1),
			bucket(40, 2), bucket(41, 2), bucket(42, 2),
			bucket(80, 3), bucket(81, 3), bucket(82, 3)
		};

		final CacheableBucket[] standard = new RangeHistogramDataCruncher(
			source, 20, 0, HistogramBehavior.STANDARD
		).getHistogram();
		final RangeHistogramDataCruncher optimizedCruncher = new RangeHistogramDataCruncher(
			source, 20, 0, HistogramBehavior.OPTIMIZED
		);
		final CacheableBucket[] optimized = optimizedCruncher.getHistogram();

		assertTrue(
			longestEmptyRun(optimized) < longestEmptyRun(standard),
			"OPTIMIZED must shrink the longest empty run (was " + longestEmptyRun(standard) + ")"
		);
		assertEquals(3, optimizedCruncher.getOverallCount(), "every distinct cluster record must survive");
		// the three clusters remain represented — first and last buckets stay populated
		assertTrue(optimized[0].occurrences() >= 1, "first bucket must still count R1");
		assertTrue(
			optimized[optimized.length - 1].occurrences() >= 1,
			"last bucket must still count R3"
		);
	}

	@Test
	@DisplayName("OPTIMIZED collapses a wide empty gap at BigDecimal scale 2")
	void shouldCollapseEmptyGapAtBigDecimalScaleUnderOptimized() {
		// two scale-2 clusters around 1.00 and 9.00 with a wide empty middle; decimalPlaces = 2. OPTIMIZED must
		// emit fewer buckets than STANDARD, shrink the longest empty run, and preserve scale-2, strictly
		// increasing thresholds with a scale-2 inclusive max.
		final ValueToRecordBitmap[] source = {
			bucket(new BigDecimal("1.00"), 1), bucket(new BigDecimal("1.10"), 1),
			bucket(new BigDecimal("8.90"), 2), bucket(new BigDecimal("9.00"), 2)
		};

		final CacheableBucket[] standard = new RangeHistogramDataCruncher(
			source, 20, 2, HistogramBehavior.STANDARD
		).getHistogram();
		final RangeHistogramDataCruncher optimizedCruncher = new RangeHistogramDataCruncher(
			source, 20, 2, HistogramBehavior.OPTIMIZED
		);
		final CacheableBucket[] optimized = optimizedCruncher.getHistogram();

		assertTrue(
			optimized.length < standard.length,
			"OPTIMIZED must emit fewer buckets than the " + standard.length + "-bucket STANDARD grid"
		);
		assertTrue(
			longestEmptyRun(optimized) < longestEmptyRun(standard),
			"OPTIMIZED must shrink the longest empty run (was " + longestEmptyRun(standard) + ")"
		);
		// thresholds carry scale 2 and strictly increase
		for (int i = 0; i < optimized.length; i++) {
			assertEquals(
				2, optimized[i].threshold().scale(),
				"bucket " + i + " threshold must carry scale 2 — got " + optimized[i].threshold()
			);
			if (i > 0) {
				assertTrue(
					optimized[i].threshold().compareTo(optimized[i - 1].threshold()) > 0,
					"bucket " + i + " threshold must strictly exceed bucket " + (i - 1)
				);
			}
		}
		assertEquals(2, optimizedCruncher.getMaxValue().scale(), "max must carry scale 2");
		assertEquals(
			0, optimizedCruncher.getMaxValue().compareTo(new BigDecimal("9.00")), "max must equal 9.00"
		);
		assertEquals(2, optimizedCruncher.getOverallCount(), "overallCount stays the distinct record count");
	}

	@Test
	@DisplayName("Extreme Integer value span does not overflow the grid arithmetic")
	void shouldNotOverflowSpanForExtremeIntegerRange() {
		// thresholds Integer.MIN_VALUE, 0, Integer.MAX_VALUE — the value span Integer.MAX_VALUE -
		// Integer.MIN_VALUE exceeds Integer.MAX_VALUE, so an int subtraction wraps to a negative span and
		// would crash the grid allocation. The span arithmetic must widen to long so the grid builds normally.
		final ValueToRecordBitmap[] source = {
			bucket(Integer.MIN_VALUE, 1),
			bucket(0, 1, 2),
			bucket(Integer.MAX_VALUE, 2)
		};

		final RangeHistogramDataCruncher cruncher = new RangeHistogramDataCruncher(source, 5, 0);

		final CacheableBucket[] buckets = cruncher.getHistogram();
		assertTrue(buckets.length >= 2, "an extreme span must still produce multiple buckets");
		// flooring the proportional offset in long space must keep the thresholds strictly increasing
		for (int i = 1; i < buckets.length; i++) {
			assertTrue(
				buckets[i].threshold().compareTo(buckets[i - 1].threshold()) > 0,
				"adjacent thresholds must strictly increase even across the full int range — bucket " + i
			);
		}
		assertEquals(2, cruncher.getOverallCount(), "overallCount must be the two distinct records");
		assertEquals(
			0, cruncher.getMaxValue().compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)),
			"max must equal Integer.MAX_VALUE without truncation"
		);
	}

	@Test
	@DisplayName("OPTIMIZED still emits a single bucket for a degenerate single-value span")
	void shouldEmitSingleBucketForDegenerateSpanEvenUnderOptimized() {
		// every threshold collapses onto key 7 (minKey == maxKey); the degenerate-span early return fires before
		// the OPTIMIZED heuristic, so the result is one full-width bucket regardless of behavior
		final ValueToRecordBitmap[] source = {
			bucket(7, 1, 2, 3)
		};

		final RangeHistogramDataCruncher optimizedCruncher = new RangeHistogramDataCruncher(
			source, 10, 0, HistogramBehavior.OPTIMIZED
		);
		final CacheableBucket[] optimized = optimizedCruncher.getHistogram();

		assertEquals(1, optimized.length, "a single-value span must collapse to exactly one bucket under OPTIMIZED");
		assertEquals(0, optimized[0].threshold().compareTo(BigDecimal.valueOf(7)), "the sole bucket sits at key 7");
		assertEquals(3, optimized[0].occurrences(), "the bucket must count every distinct record");
		assertEquals(3, optimizedCruncher.getOverallCount(), "overallCount must be the distinct record count");
		assertEquals(
			0, optimized[0].relativeFrequency().compareTo(new BigDecimal("100")),
			"the sole populated bucket is by definition full-width (relativeFrequency = 100)"
		);
	}

	/**
	 * Returns the length of the longest run of consecutive empty (zero-occurrence) buckets — the quantity the
	 * OPTIMIZED behavior is designed to shrink.
	 */
	private static int longestEmptyRun(@Nonnull CacheableBucket[] buckets) {
		int longest = 0;
		int current = 0;
		for (final CacheableBucket b : buckets) {
			if (b.occurrences() == 0) {
				current++;
				if (current > longest) {
					longest = current;
				}
			} else {
				current = 0;
			}
		}
		return longest;
	}
}
