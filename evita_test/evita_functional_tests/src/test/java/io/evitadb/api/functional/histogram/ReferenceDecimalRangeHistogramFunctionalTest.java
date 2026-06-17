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
import java.util.NavigableSet;
import java.util.TreeSet;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.histogramStatistics;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.referenceSummaryOfReferenceWithHistograms;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage for reference histograms whose source attribute is a
 * `BigDecimalNumberRange` declared with `indexDecimalPlaces > 0`, where every seeded range
 * carries an INTRINSIC scale smaller than that schema scale.
 *
 * The catalog stores the ranges, indexes them, and the query reconstructs the histogram
 * bucket boundaries — this proves that the range thresholds are encoded at the schema's
 * `indexedDecimalPlaces` at indexing time and decoded at the same scale at query time. When
 * the two scales disagree the emitted boundaries collapse by a power of ten (`1.50` becomes
 * `0.15`), so the exact-threshold assertions below are the discriminating check.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Reference histogram — BigDecimalNumberRange source attribute")
@Tag(ENGINE)
@Tag(HISTOGRAM)
@Tag(REFERENCE)
public class ReferenceDecimalRangeHistogramFunctionalTest
	extends AbstractReferenceSummaryHistogramFunctionalTest {

	/**
	 * The four `BigDecimalNumberRange` values seeded by
	 * {@code AbstractReferenceSummaryHistogramFunctionalTest#seedDecimalRangeData} —
	 * duplicated here as the oracle's ground truth so expected occurrences can be re-derived
	 * without consulting the histogram engine. Must stay in sync with the fixture.
	 */
	private static final List<SeededDecimalRange> SEEDED_RANGES = List.of(
		new SeededDecimalRange(new BigDecimal("1.5"), new BigDecimal("2.5")),
		new SeededDecimalRange(new BigDecimal("2.0"), new BigDecimal("3.0")),
		new SeededDecimalRange(new BigDecimal("2.5"), new BigDecimal("3.5")),
		new SeededDecimalRange(new BigDecimal("3.0"), new BigDecimal("4.0"))
	);

	/**
	 * Issues a `referenceSummaryOfReferenceWithHistograms(..., histogramStatistics(...))`
	 * query against the supplied session and returns the {@link #HISTOGRAM_RANGE} histogram
	 * for {@link #RANGE_GROUP_PK}.
	 */
	@Nonnull
	private static HistogramContract queryGroupHistogram(
		@Nonnull EvitaSessionContract session,
		int requestedBucketCount
	) {
		final EvitaResponse<EntityReferenceContract> result = session.query(
			query(
				collection(ENTITY_PRODUCT),
				require(
					page(1, Integer.MAX_VALUE),
					referenceSummaryOfReferenceWithHistograms(
						REF_PARAM_VALUES, null, null, null,
						histogramStatistics(requestedBucketCount, HISTOGRAM_RANGE)
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
		final HistogramContract histogram = group.getHistogramStatistics(HISTOGRAM_RANGE);
		assertNotNull(
			histogram, "Histogram '" + HISTOGRAM_RANGE + "' must exist for group " + RANGE_GROUP_PK
		);
		return histogram;
	}

	/**
	 * Rescales the supplied value to {@link #DECIMAL_RANGE_PLACES} — the exact scale the
	 * histogram bucket boundaries must carry once they are reconstructed at the schema's
	 * `indexedDecimalPlaces`.
	 */
	@Nonnull
	private static BigDecimal scaleToSchema(@Nonnull BigDecimal value) {
		return value.setScale(DECIMAL_RANGE_PLACES, java.math.RoundingMode.UNNECESSARY);
	}

	@Test
	@UseDataSet(REFERENCE_HISTOGRAM_DECIMAL_RANGE)
	@DisplayName("should reconstruct decimal range bucket boundaries at the schema scale")
	void shouldReconstructDecimalRangeBoundariesAtSchemaScale(@Nonnull Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HistogramContract histogram = queryGroupHistogram(session, 20);

				final Bucket[] buckets = histogram.getBuckets();
				assertTrue(buckets.length > 0, "Range histogram must produce at least one bucket");

				// the decisive check: the histogram span must be reconstructed at the schema scale.
				// Pre-fix the range thresholds were stored at the value's intrinsic scale (1) but
				// decoded at the schema scale (2), collapsing every boundary by a power of ten — the
				// seeded span [1.50, 4.00] would surface as [0.15, 0.40].
				assertEquals(
					0, scaleToSchema(new BigDecimal("1.5")).compareTo(histogram.getMin()),
					"min must be 1.50 (the lowest seeded lower bound) — found " + histogram.getMin()
						+ " (a value collapsed by a power of ten signals the scale mismatch bug)"
				);
				assertEquals(
					0, scaleToSchema(new BigDecimal("4.0")).compareTo(histogram.getMax()),
					"max must be 4.00 (the highest seeded upper bound) — found " + histogram.getMax()
						+ " (a value collapsed by a power of ten signals the scale mismatch bug)"
				);

				// every seeded endpoint must fall within the emitted [min, max] span at the schema
				// scale, and the first / last thresholds must coincide with min / max — together these
				// pin the threshold grid to the correct magnitude, not just its endpoints
				assertEquals(
					0, histogram.getMin().compareTo(buckets[0].threshold()),
					"first bucket threshold must equal the histogram min"
				);
				BigDecimal previous = null;
				for (final Bucket bucket : buckets) {
					final BigDecimal threshold = bucket.threshold();
					assertTrue(
						threshold.compareTo(histogram.getMin()) >= 0
							&& threshold.compareTo(histogram.getMax()) <= 0,
						"threshold " + threshold + " must lie within [" + histogram.getMin() + ", "
							+ histogram.getMax() + "]"
					);
					if (previous != null) {
						assertTrue(
							previous.compareTo(threshold) < 0,
							"thresholds must be strictly increasing — found " + previous + " >= " + threshold
						);
					}
					previous = threshold;
				}

				// every seeded endpoint, taken at the schema scale, must be reproducible by the
				// reconstructed grid — i.e. it lands at or above min and at or below max
				for (final SeededDecimalRange range : SEEDED_RANGES) {
					for (final BigDecimal endpoint : List.of(range.from(), range.to())) {
						final BigDecimal scaled = scaleToSchema(endpoint);
						assertTrue(
							scaled.compareTo(histogram.getMin()) >= 0
								&& scaled.compareTo(histogram.getMax()) <= 0,
							"seeded endpoint " + scaled
								+ " must lie within the reconstructed span [" + histogram.getMin()
								+ ", " + histogram.getMax() + "]"
						);
					}
				}
			}
		);
	}

	@Test
	@UseDataSet(REFERENCE_HISTOGRAM_DECIMAL_RANGE)
	@DisplayName("should account each decimal range into every overlapping bucket across bucket counts")
	void shouldAccountEachDecimalRangeIntoEveryOverlappingBucket(@Nonnull Evita evita) {
		// the emitted threshold grid varies with the requested bucket count; the overlap oracle is
		// independent of that grid — it reads whatever thresholds the engine emits and re-buckets the
		// seeded range endpoints into them. Asserting it across several counts proves the range-overlap
		// accounting holds whatever bucketing strategy the cruncher picks.
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				for (final int requested : new int[] {6, 10, 20, 50}) {
					final HistogramContract histogram = queryGroupHistogram(session, requested);
					assertBucketsMatchOverlapOracle(histogram);
				}
			}
		);
	}

	// ---------------------------------------------------------------------
	// overlap oracle — independent re-derivation of expected bucket counts
	// ---------------------------------------------------------------------

	/**
	 * A seeded `parameterValue` decimal-range-fixture row: the inclusive `BigDecimal` bounds
	 * of its `validRange` attribute.
	 */
	private record SeededDecimalRange(@Nonnull BigDecimal from, @Nonnull BigDecimal to) {
	}

	/**
	 * Number of supplied ranges whose closed interval `[from, to]` contains `endpoint`.
	 * Mirrors the closed-interval semantics of `FilterIndex#getRangeHistogramOfAllRecords`: a
	 * record's `starts` join the active set before the threshold bucket is snapshotted and its
	 * `ends` leave only afterwards, so a range is counted at both its lower and upper endpoint.
	 */
	private static int overlapCount(@Nonnull BigDecimal endpoint) {
		int count = 0;
		for (final SeededDecimalRange range : SEEDED_RANGES) {
			if (range.from().compareTo(endpoint) <= 0 && endpoint.compareTo(range.to()) <= 0) {
				count++;
			}
		}
		return count;
	}

	/**
	 * Independently re-derives the expected per-bucket occurrences from {@link #SEEDED_RANGES}
	 * and asserts the histogram the engine produced matches bucket-for-bucket.
	 *
	 * Each distinct range endpoint `E` carries weight {@link #overlapCount(BigDecimal)} (how
	 * many ranges cover it); the cruncher sums those weights into whichever emitted bucket
	 * interval contains `E`. Intervals are half-open `[thresholdᵢ, thresholdᵢ₊₁)` except the
	 * last, which is closed `[thresholdₙ₋₁, max]`. The re-bucketing uses only `BigDecimal`
	 * comparison and the emitted thresholds — it never calls the histogram engine — so a match
	 * proves every range was accounted into every bucket its endpoints fall in, with no drops
	 * and no double-counts. Because the endpoints are compared against the emitted thresholds,
	 * the oracle also fails if those thresholds are collapsed by a power of ten.
	 */
	private static void assertBucketsMatchOverlapOracle(@Nonnull HistogramContract histogram) {
		final Bucket[] buckets = histogram.getBuckets();
		final BigDecimal max = histogram.getMax();
		// distinct endpoints of the seeded ranges, ascending, at the schema scale
		final NavigableSet<BigDecimal> endpoints = new TreeSet<>();
		for (final SeededDecimalRange range : SEEDED_RANGES) {
			endpoints.add(scaleToSchema(range.from()));
			endpoints.add(scaleToSchema(range.to()));
		}
		int independentTotal = 0;
		for (int i = 0; i < buckets.length; i++) {
			final BigDecimal lower = buckets[i].threshold();
			final boolean last = i == buckets.length - 1;
			final BigDecimal upper = last ? max : buckets[i + 1].threshold();
			int expected = 0;
			for (final BigDecimal endpoint : endpoints) {
				final boolean atOrAboveLower = endpoint.compareTo(lower) >= 0;
				// half-open upper bound for every bucket but the last, which owns `max` inclusively
				final boolean belowUpper = last
					? endpoint.compareTo(upper) <= 0
					: endpoint.compareTo(upper) < 0;
				if (atOrAboveLower && belowUpper) {
					expected += overlapCount(endpoint);
				}
			}
			assertEquals(
				expected, buckets[i].occurrences(),
				"Bucket " + (last ? "[" + lower + ", " + max + "]" : "[" + lower + ", " + upper + ")")
					+ " must count every seeded range overlapping each endpoint it contains"
			);
			independentTotal += expected;
		}
		assertTrue(
			independentTotal > 0,
			"the seeded ranges must contribute at least one attribution"
		);
		assertEquals(
			independentTotal, histogram.getOverallCount(),
			"overallCount must equal the independently derived total attribution count"
		);
	}

}
