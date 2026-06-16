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
}
