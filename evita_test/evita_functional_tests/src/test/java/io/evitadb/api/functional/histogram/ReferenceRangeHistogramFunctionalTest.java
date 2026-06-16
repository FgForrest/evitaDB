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

package io.evitadb.api.functional.histogram;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.HistogramContract.Bucket;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.core.Evita;
import io.evitadb.test.annotation.UseDataSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.List;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.histogramStatistics;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.referenceSummaryOfReferenceWithHistograms;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage for range-typed reference histograms.
 *
 * Consumes the shared {@link #REFERENCE_HISTOGRAM_RANGE} dataset provisioned by
 * {@link AbstractReferenceSummaryHistogramFunctionalTest} so the catalog is built once
 * and reused across read-only test methods. Two histograms are co-declared on the same
 * reference: {@link #HISTOGRAM_RANGE} (unfiltered range sweep) and
 * {@link #HISTOGRAM_RANGE_ACTIVE} (same source, partition-selector-filtered by the
 * `active` flag) — together they exercise the range-aware request-bucket walker and
 * its AND-combination with `assignedWhen`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Reference histogram — range-typed source attribute")
@Tag(CONTRACT)
@Tag(HISTOGRAM)
@Tag(REFERENCE)
public class ReferenceRangeHistogramFunctionalTest extends AbstractReferenceSummaryHistogramFunctionalTest {

	/**
	 * Issues a `referenceSummaryOfReferenceWithHistograms(..., histogramStatistics(...))`
	 * query against the supplied session and returns the histogram for
	 * {@link #RANGE_GROUP_PK} under the requested histogram name.
	 */
	@Nonnull
	private static HistogramContract queryGroupHistogram(
		@Nonnull EvitaSessionContract session,
		@Nonnull String histogramName,
		int requestedBucketCount
	) {
		final EvitaResponse<EntityReferenceContract> result = session.query(
			query(
				collection(ENTITY_PRODUCT),
				require(
					page(1, Integer.MAX_VALUE),
					referenceSummaryOfReferenceWithHistograms(
						REF_PARAM_VALUES, null, null, null,
						histogramStatistics(requestedBucketCount, histogramName)
					)
				)
			),
			EntityReferenceContract.class
		);
		final ReferenceSummary referenceSummary = result.getExtraResult(ReferenceSummary.class);
		assertNotNull(referenceSummary, "ReferenceSummary must be present in the response");
		final ReferenceGroupStatistics group =
			referenceSummary.getReferenceGroupStatistics(REF_PARAM_VALUES, RANGE_GROUP_PK);
		assertNotNull(group, "Group " + RANGE_GROUP_PK + " must exist");
		final HistogramContract histogram = group.getHistogramStatistics(histogramName);
		assertNotNull(histogram, "Histogram '" + histogramName + "' must exist for group " + RANGE_GROUP_PK);
		return histogram;
	}

	@Test
	@UseDataSet(REFERENCE_HISTOGRAM_RANGE)
	@DisplayName("should populate range histogram with bounded totals and monotonic thresholds")
	void shouldPopulateRangeHistogramWhenRefAttributeIsIntegerNumberRange(@Nonnull Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				// requesting 10 buckets covers the full distinct-endpoint span (10..35)
				// without forcing the sweep into collision mode
				final HistogramContract histogram = queryGroupHistogram(session, HISTOGRAM_RANGE, 10);

				final Bucket[] buckets = histogram.getBuckets();
				assertTrue(buckets.length > 0, "Range histogram must produce at least one bucket");

				// thresholds must be strictly monotonic in BigDecimal form
				BigDecimal prev = null;
				for (final Bucket bucket : buckets) {
					if (prev != null) {
						assertTrue(
							prev.compareTo(bucket.threshold()) < 0,
							"Bucket thresholds must be strictly increasing — found " + prev
								+ " >= " + bucket.threshold()
						);
					}
					prev = bucket.threshold();
				}

				// min must be <= max, and both must lie within the seeded range span [10, 35]
				assertTrue(
					histogram.getMin().compareTo(histogram.getMax()) <= 0,
					"min must be <= max"
				);
				assertTrue(
					histogram.getMin().compareTo(BigDecimal.valueOf(10)) >= 0,
					"min " + histogram.getMin() + " must be >= 10"
				);
				assertTrue(
					histogram.getMax().compareTo(BigDecimal.valueOf(35)) <= 0,
					"max " + histogram.getMax() + " must be <= 35"
				);

				// Range-overlap accounting: each PV's `[from, to]` renders a solid bar —
				// it contributes a single distinct occurrence to every bucket interval its range
				// overlaps. The overall count is the number of distinct seeded ranges (4), not the
				// inflated per-endpoint sum. The oracle re-derives the expected per-bucket counts
				// independently from the seeded ranges — plain integer math, never the histogram
				// engine — and asserts the engine matches bucket-for-bucket.
				assertTrue(
					histogram.getOverallCount() > 0,
					"overallCount must be positive when the histogram has buckets"
				);
				assertBucketsMatchOverlapOracle(histogram, ALL_RANGES);
			}
		);
	}

	@Test
	@UseDataSet(REFERENCE_HISTOGRAM_RANGE)
	@DisplayName("should apply assignedWhen to range histogram and combine with range sweep")
	void shouldApplyAssignedWhenToRangeHistogramAndCombine(@Nonnull Evita evita) {
		// HISTOGRAM_RANGE_ACTIVE applies `assignedWhen` =
		// `$reference.referencedEntity?.attributes['active'] == true`. PV 4 (range [25, 35])
		// is seeded with `active = false`, so the filtered histogram must not extend past
		// PV 3's upper bound (30). PVs 1–3 (active) drive the sweep over `[10, 30]`.
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HistogramContract histogram =
					queryGroupHistogram(session, HISTOGRAM_RANGE_ACTIVE, 10);

				final Bucket[] buckets = histogram.getBuckets();
				assertTrue(
					buckets.length > 0,
					"Filtered range histogram must still produce at least one bucket"
				);

				// PV 4 was excluded by the per-histogram filter, so the histogram must
				// not extend past 30 — that is the empirical proof of AND-combination.
				assertTrue(
					histogram.getMax().compareTo(BigDecimal.valueOf(30)) <= 0,
					"max " + histogram.getMax()
						+ " must not exceed 30 — PV 4 (range [25, 35]) is filtered out"
				);
				assertTrue(
					histogram.getMin().compareTo(BigDecimal.valueOf(10)) >= 0,
					"min " + histogram.getMin() + " must be >= 10 (PV 1's lower bound)"
				);

				// The oracle is fed only the active ranges (PV 4 removed), so the independently
				// derived per-bucket counts already exclude PV 4. A bucket-for-bucket match proves
				// the filter removes PV 4's contributions without dropping or double-counting the
				// surviving PVs, and the overall count drops to the 3 distinct surviving ranges.
				assertTrue(
					histogram.getOverallCount() > 0,
					"overallCount must be positive when surviving PVs cover the range"
				);
				assertBucketsMatchOverlapOracle(histogram, ACTIVE_RANGES);
			}
		);
	}

	@Test
	@UseDataSet(REFERENCE_HISTOGRAM_RANGE)
	@DisplayName("should account each range into every overlapping bucket across bucket counts")
	void shouldAccountEachRangeIntoEveryOverlappingBucket(@Nonnull Evita evita) {
		// The cruncher's threshold grid varies with the requested bucket count — natural-endpoint
		// alignment at one count, evenly sliced thresholds with zero-occurrence gaps at another.
		// The overlap oracle is independent of that grid: it reads whatever thresholds the engine
		// emits and re-buckets the seeded range endpoints into them. Asserting it across several
		// bucket counts proves the range-overlap accounting holds regardless of the bucketing
		// strategy, not just for one convenient count.
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				for (final int requested : new int[] {6, 10, 20, 50}) {
					final HistogramContract histogram =
						queryGroupHistogram(session, HISTOGRAM_RANGE, requested);
					assertBucketsMatchOverlapOracle(histogram, ALL_RANGES);
				}
			}
		);
	}

	// ---------------------------------------------------------------------
	// overlap oracle — independent re-derivation of expected bucket counts
	// ---------------------------------------------------------------------

	/**
	 * A seeded `parameterValue` range-fixture row: the inclusive integer bounds of its
	 * `validRange` attribute and whether it is flagged `active`.
	 */
	private record SeededRange(int from, int to, boolean active) {
	}

	/**
	 * The four ranges seeded by {@code AbstractReferenceSummaryHistogramFunctionalTest#seedRangeData}
	 * — duplicated here as the oracle's ground truth so expected occurrences can be re-derived
	 * without consulting the histogram engine. Must stay in sync with the fixture.
	 */
	private static final List<SeededRange> ALL_RANGES = List.of(
		new SeededRange(10, 20, true),
		new SeededRange(15, 25, true),
		new SeededRange(20, 30, true),
		new SeededRange(25, 35, false)
	);

	/**
	 * The subset of {@link #ALL_RANGES} that survives the {@link #HISTOGRAM_RANGE_ACTIVE}
	 * `assignedWhen` filter (`active == true`) — i.e. PV 4 removed.
	 */
	private static final List<SeededRange> ACTIVE_RANGES = ALL_RANGES.stream()
		.filter(SeededRange::active)
		.toList();

	/**
	 * Number of supplied ranges whose closed interval `[from, to]` OVERLAPS the bucket interval
	 * `[lower, upper)` — or, for the last bucket, the closed interval `[lower, max]`.
	 *
	 * Overlap semantics: a range `[from, to]` overlaps a half-open bucket `[lower, upper)` iff
	 * `from < upper AND to >= lower`; it overlaps the closed last bucket `[lower, max]` iff
	 * `from <= max AND to >= lower`. Each overlapping range contributes exactly one distinct
	 * occurrence to the bucket regardless of how much of the bucket it covers.
	 */
	private static int overlapCount(
		int lower, int upper, boolean lastBucket, @Nonnull List<SeededRange> ranges
	) {
		int count = 0;
		for (final SeededRange range : ranges) {
			final boolean overlaps = lastBucket
				? range.from() <= upper && range.to() >= lower
				: range.from() < upper && range.to() >= lower;
			if (overlaps) {
				count++;
			}
		}
		return count;
	}

	/**
	 * Independently re-derives the expected per-bucket occurrences from the seeded {@code ranges}
	 * and asserts the histogram the engine produced matches bucket-for-bucket.
	 *
	 * The model: each seeded range `[from, to]` renders a solid bar — it contributes a single distinct
	 * occurrence to every emitted bucket interval its range overlaps. Intervals are half-open
	 * `[thresholdᵢ, thresholdᵢ₊₁)`, except the last, which is closed `[thresholdₙ₋₁, max]`. The
	 * re-derivation uses only plain integer math and the emitted thresholds — it never calls the
	 * histogram engine — so a match proves the engine counts distinct overlapping ranges per bucket
	 * with no drops and no double-counts. The overall count must equal the number of distinct seeded
	 * ranges, NOT the sum of per-bucket occurrences.
	 */
	private static void assertBucketsMatchOverlapOracle(
		@Nonnull HistogramContract histogram,
		@Nonnull List<SeededRange> ranges
	) {
		final Bucket[] buckets = histogram.getBuckets();
		final BigDecimal max = histogram.getMax();
		for (int i = 0; i < buckets.length; i++) {
			final BigDecimal lower = buckets[i].threshold();
			final boolean last = i == buckets.length - 1;
			final BigDecimal upper = last ? max : buckets[i + 1].threshold();
			// thresholds are integer-valued for the seeded integer ranges; intValueExact guards against
			// any unexpected fractional grid emitted by the engine
			final int expected = overlapCount(
				lower.intValueExact(), upper.intValueExact(), last, ranges
			);
			assertEquals(
				expected, buckets[i].occurrences(),
				"Bucket " + (last ? "[" + lower + ", " + max + "]" : "[" + lower + ", " + upper + ")")
					+ " must count every distinct seeded range overlapping it"
			);
		}
		// distinct seeded ranges — the overlap-histogram overall count, independent of the per-bucket sum
		assertEquals(
			ranges.size(), histogram.getOverallCount(),
			"overallCount must equal the number of distinct seeded ranges"
		);
	}

}
