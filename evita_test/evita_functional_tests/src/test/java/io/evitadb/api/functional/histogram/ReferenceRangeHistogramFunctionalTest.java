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
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.query.require.HistogramBehavior;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.HistogramContract.Bucket;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.core.Evita;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.test.annotation.UseDataSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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

	/**
	 * Behavior-aware sibling of {@link #queryGroupHistogram(EvitaSessionContract, String, int)}: threads an explicit
	 * {@link HistogramBehavior} into the `histogramStatistics(int, HistogramBehavior, String...)` require clause so the
	 * caller can probe the routing fork inside
	 * {@code AttributeHistogramComputer} — `STANDARD`/`OPTIMIZED` reach `RangeHistogramDataCruncher` (distinct-overlap
	 * bars) while `EQUALIZED`/`EQUALIZED_OPTIMIZED` reach `EqualizedHistogramDataCruncher` fed the same range sweep.
	 */
	@Nonnull
	private static HistogramContract queryGroupHistogram(
		@Nonnull EvitaSessionContract session,
		@Nonnull String histogramName,
		int requestedBucketCount,
		@Nonnull HistogramBehavior behavior
	) {
		final EvitaResponse<EntityReferenceContract> result = session.query(
			query(
				collection(ENTITY_PRODUCT),
				require(
					page(1, Integer.MAX_VALUE),
					referenceSummaryOfReferenceWithHistograms(
						REF_PARAM_VALUES, null, null, null,
						histogramStatistics(requestedBucketCount, behavior, histogramName)
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
				assertEquals(
					ALL_RANGES.size(), histogram.getOverallCount(),
					"overallCount must equal the " + ALL_RANGES.size() + " distinct seeded ranges"
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
				assertEquals(
					ACTIVE_RANGES.size(), histogram.getOverallCount(),
					"overallCount must drop to the " + ACTIVE_RANGES.size()
						+ " distinct surviving ranges after PV 4 is deactivated"
				);
				assertBucketsMatchOverlapOracle(histogram, ACTIVE_RANGES);
			}
		);
	}

	@Test
	@DisplayName("should render BigDecimalNumberRange thresholds at the schema indexed scale")
	void shouldRenderBigDecimalRangeHistogramAtSchemaScaleWhenValueScaleIsLower() {
		// A referenced entity carries a `BigDecimalNumberRange` attribute declared with
		// `indexDecimalPlaces(4)`, but the seeded values have a lower natural scale (integer bounds →
		// effective retained scale 0). The regular attribute index normalizes the range to the schema
		// scale before storing; the histogram-index population path must do the same — otherwise the
		// histogram FilterIndex stores comparable longs at the value's intrinsic scale (0) while the
		// read side decodes them at the schema scale (4), shrinking every threshold by 10^4.
		final int indexedDecimalPlaces = 4;
		final String rangeAttribute = "rangeValue";
		final String rangeHistogram = "bigDecimalRangeBucket";
		runWithInlineSchema(
			"bigDecimalRangeHistogramScale",
			session -> {
				session.defineEntitySchema(ENTITY_PARAMETER)
					.updateVia(session);
				session.defineEntitySchema(ENTITY_PARAMETER_VALUE)
					.withAttribute(
						rangeAttribute, BigDecimalNumberRange.class,
						whichIs -> whichIs.filterable().indexDecimalPlaces(indexedDecimalPlaces).nullable()
					)
					.updateVia(session);
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withReferenceToEntity(
						REF_PARAM_VALUES, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFilteringAndPartitioning()
							.indexedWithComponents(ReferenceIndexedComponents.values())
							.faceted()
							.withGroupTypeRelatedToEntity(ENTITY_PARAMETER)
							.bucketed(
								rangeHistogram,
								ExpressionFactory.parse(
									"$reference.referencedEntity?.attributes['" + rangeAttribute + "']"
								)
							)
					)
					.updateVia(session);
			},
			session -> {
				session.createNewEntity(ENTITY_PARAMETER, RANGE_GROUP_PK)
					.upsertVia(session);
				// integer-valued bounds → effective retained scale 0, below the schema's
				// indexDecimalPlaces(4): the scale mismatch the histogram-index path must normalize away
				createParameterValueWithBigDecimalRange(session, 1, rangeAttribute, "1", "10");
				createParameterValueWithBigDecimalRange(session, 2, rangeAttribute, "20", "88");
				session.createNewEntity(ENTITY_PRODUCT, 100)
					.setReference(
						REF_PARAM_VALUES, 1,
						whichIs -> whichIs.setGroup(ENTITY_PARAMETER, RANGE_GROUP_PK)
					)
					.upsertVia(session);
				session.createNewEntity(ENTITY_PRODUCT, 101)
					.setReference(
						REF_PARAM_VALUES, 2,
						whichIs -> whichIs.setGroup(ENTITY_PARAMETER, RANGE_GROUP_PK)
					)
					.upsertVia(session);
			},
			evita -> evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final HistogramContract histogram =
						queryGroupHistogram(session, rangeHistogram, 10);
					final Bucket[] buckets = histogram.getBuckets();
					assertTrue(buckets.length > 0, "Range histogram must produce at least one bucket");

					// the seeded ranges span [1, 88]; correct thresholds must lie in that band, never
					// be shrunk to sub-unit fractions like 0.0001 (the 10^4-too-small defect signature)
					assertTrue(
						histogram.getMin().compareTo(BigDecimal.ONE) >= 0,
						"min " + histogram.getMin() + " must be >= 1 (seeded lower bound) — a value "
							+ "below 1 proves the threshold was decoded at the wrong scale"
					);
					assertTrue(
						histogram.getMax().compareTo(BigDecimal.valueOf(88)) <= 0,
						"max " + histogram.getMax() + " must be <= 88 (seeded upper bound)"
					);
					for (final Bucket bucket : buckets) {
						assertTrue(
							bucket.threshold().compareTo(BigDecimal.ONE) >= 0,
							"threshold " + bucket.threshold() + " must be >= 1 — a sub-unit threshold "
								+ "is the 10^" + indexedDecimalPlaces + "-too-small scale defect"
						);
					}
				}
			)
		);
	}

	@Test
	@DisplayName("should round a scalar BigDecimal histogram value whose scale exceeds the schema indexed scale")
	void shouldRoundScalarBigDecimalHistogramValueWhenValueScaleExceedsIndexedScale() {
		// Scalar sibling of shouldRenderBigDecimalRangeHistogramAtSchemaScaleWhenValueScaleIsLower: a referenced
		// entity carries a plain (non-range) `BigDecimal` attribute declared with `indexDecimalPlaces(1)`, but the
		// seeded values have a HIGHER natural scale (8.25, 9.45 → scale 2). The scalar histogram-index path stores
		// the value verbatim (NumberUtils.normalizeForIndexing only strips trailing zeros for scalar BigDecimals),
		// so the histogram value→int converter in AttributeHistogramComputer receives a value whose scale exceeds
		// indexedDecimalPlaces. It must round to the indexed grid (HALF_UP), exactly like the sort/filter index
		// encoding (NumberUtils.convertToInt) — not throw `ArithmeticException: Rounding necessary` from
		// longValueExact(). Reproduces the crash reported on #1156's referenceSummaryOfReferenceWithHistograms path.
		final int indexedDecimalPlaces = 1;
		final String scalarAttribute = "unitValue";
		final String scalarHistogram = "bigDecimalScalarBucket";
		runWithInlineSchema(
			"bigDecimalScalarHistogramScale",
			session -> {
				session.defineEntitySchema(ENTITY_PARAMETER)
					.updateVia(session);
				session.defineEntitySchema(ENTITY_PARAMETER_VALUE)
					.withAttribute(
						scalarAttribute, BigDecimal.class,
						whichIs -> whichIs.filterable().indexDecimalPlaces(indexedDecimalPlaces).nullable()
					)
					.updateVia(session);
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withReferenceToEntity(
						REF_PARAM_VALUES, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFilteringAndPartitioning()
							.indexedWithComponents(ReferenceIndexedComponents.values())
							.faceted()
							.withGroupTypeRelatedToEntity(ENTITY_PARAMETER)
							.bucketed(
								scalarHistogram,
								ExpressionFactory.parse(
									"$reference.referencedEntity?.attributes['" + scalarAttribute + "']"
								)
							)
					)
					.updateVia(session);
			},
			session -> {
				session.createNewEntity(ENTITY_PARAMETER, RANGE_GROUP_PK)
					.upsertVia(session);
				// scale-2 values above the schema's indexDecimalPlaces(1): 8.25 → 8.3, 9.45 → 9.5 once rounded.
				// The pre-fix converter throws on the leftover fractional digit instead of rounding.
				createParameterValueWithScalarBigDecimal(session, 1, scalarAttribute, "8.25");
				createParameterValueWithScalarBigDecimal(session, 2, scalarAttribute, "9.45");
				session.createNewEntity(ENTITY_PRODUCT, 100)
					.setReference(
						REF_PARAM_VALUES, 1,
						whichIs -> whichIs.setGroup(ENTITY_PARAMETER, RANGE_GROUP_PK)
					)
					.upsertVia(session);
				session.createNewEntity(ENTITY_PRODUCT, 101)
					.setReference(
						REF_PARAM_VALUES, 2,
						whichIs -> whichIs.setGroup(ENTITY_PARAMETER, RANGE_GROUP_PK)
					)
					.upsertVia(session);
			},
			evita -> evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					// Before the fix this throws `ArithmeticException: Rounding necessary` inside
					// AttributeHistogramComputer.createNumberToIntegerConverter for the scale-2 scalar value.
					final HistogramContract histogram =
						queryGroupHistogram(session, scalarHistogram, 10);
					final Bucket[] buckets = histogram.getBuckets();
					assertTrue(buckets.length > 0, "Scalar histogram must produce at least one bucket");
					// exact HALF_UP grid values pin the rounding mode: 8.25 → 8.3, 9.45 → 9.5 at
					// indexDecimalPlaces(1). Loose bounds would also pass under FLOOR (8.2 / 9.4), so
					// assert exact equality (compareTo == 0 to ignore trailing-zero scale differences)
					assertEquals(
						0, histogram.getMin().compareTo(new BigDecimal("8.3")),
						"min " + histogram.getMin() + " must be 8.3 (8.25 rounded HALF_UP to indexDecimalPlaces(1))"
					);
					assertEquals(
						0, histogram.getMax().compareTo(new BigDecimal("9.5")),
						"max " + histogram.getMax() + " must be 9.5 (9.45 rounded HALF_UP to indexDecimalPlaces(1))"
					);
				}
			)
		);
	}

	@Test
	@DisplayName("should drop a BigDecimalNumberRange histogram entry on removal despite a lower value scale")
	void shouldRemoveBigDecimalRangeHistogramEntryAtSchemaScaleWhenValueScaleIsLower() {
		// Removal-path companion to shouldRenderBigDecimalRangeHistogramAtSchemaScaleWhenValueScaleIsLower.
		// The existence guard that runs before a histogram-index remove compares its probe against the STORED
		// bucket value, which the write path normalized to the schema scale (4). A raw probe carrying the
		// value's intrinsic scale (0) would never match its re-scaled stored counterpart, so the removal would
		// be silently suppressed and the histogram would keep a phantom entry that can never be reclaimed. The
		// guard must therefore normalize its probe with the same scale; this test fails if it does not.
		final int indexedDecimalPlaces = 4;
		final String rangeAttribute = "rangeValue";
		final String rangeHistogram = "bigDecimalRangeBucket";
		runWithInlineSchema(
			"bigDecimalRangeHistogramScaleRemoval",
			session -> {
				session.defineEntitySchema(ENTITY_PARAMETER)
					.updateVia(session);
				session.defineEntitySchema(ENTITY_PARAMETER_VALUE)
					.withAttribute(
						rangeAttribute, BigDecimalNumberRange.class,
						whichIs -> whichIs.filterable().indexDecimalPlaces(indexedDecimalPlaces).nullable()
					)
					.updateVia(session);
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withReferenceToEntity(
						REF_PARAM_VALUES, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFilteringAndPartitioning()
							.indexedWithComponents(ReferenceIndexedComponents.values())
							.faceted()
							.withGroupTypeRelatedToEntity(ENTITY_PARAMETER)
							.bucketed(
								rangeHistogram,
								ExpressionFactory.parse(
									"$reference.referencedEntity?.attributes['" + rangeAttribute + "']"
								)
							)
					)
					.updateVia(session);
			},
			session -> {
				session.createNewEntity(ENTITY_PARAMETER, RANGE_GROUP_PK)
					.upsertVia(session);
				// scale-0 bounds, below the schema's indexDecimalPlaces(4) — the same scale mismatch the
				// removal guard must normalize away to match the stored (scale-4) bucket value
				createParameterValueWithBigDecimalRange(session, 1, rangeAttribute, "1", "10");
				createParameterValueWithBigDecimalRange(session, 2, rangeAttribute, "20", "88");
				session.createNewEntity(ENTITY_PRODUCT, 100)
					.setReference(
						REF_PARAM_VALUES, 1,
						whichIs -> whichIs.setGroup(ENTITY_PARAMETER, RANGE_GROUP_PK)
					)
					.upsertVia(session);
				session.createNewEntity(ENTITY_PRODUCT, 101)
					.setReference(
						REF_PARAM_VALUES, 2,
						whichIs -> whichIs.setGroup(ENTITY_PARAMETER, RANGE_GROUP_PK)
					)
					.upsertVia(session);
			},
			evita -> {
				// baseline: both ranges contribute, so the group histogram spans the full [1, 88] band
				evita.queryCatalog(
					TEST_CATALOG,
					session -> {
						final HistogramContract histogram =
							queryGroupHistogram(session, rangeHistogram, 10);
						assertTrue(
							histogram.getMax().compareTo(BigDecimal.valueOf(88)) >= 0,
							"baseline max " + histogram.getMax() + " must reach the seeded upper bound 88"
						);
					}
				);
				// remove the product contributing the [20, 88] range — drives the histogram-index removal
				// path through the existence guard whose probe must be normalized to the schema scale
				evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						assertTrue(
							session.deleteEntity(ENTITY_PRODUCT, 101),
							"product 101 must exist and be deleted"
						);
					}
				);
				// only the [1, 10] range survives; the upper bound must collapse to <= 10. A max still near 88
				// proves the [20, 88] removal was suppressed by a scale-mismatched guard probe (phantom entry).
				evita.queryCatalog(
					TEST_CATALOG,
					session -> {
						final HistogramContract histogram =
							queryGroupHistogram(session, rangeHistogram, 10);
						assertTrue(
							histogram.getMin().compareTo(BigDecimal.ONE) >= 0,
							"surviving min " + histogram.getMin() + " must stay >= 1 — scale must remain correct"
						);
						assertTrue(
							histogram.getMax().compareTo(BigDecimal.valueOf(10)) <= 0,
							"surviving max " + histogram.getMax() + " must collapse to <= 10 after the [20, 88] "
								+ "range is removed — a value near 88 means the removal was silently suppressed"
						);
					}
				);
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

	@Test
	@UseDataSet(REFERENCE_HISTOGRAM_RANGE)
	@DisplayName("should report distinct overallCount under STANDARD but stop-sum under EQUALIZED")
	void shouldReportDistinctOverallCountForRangeStandardButStopSumForEqualized(@Nonnull Evita evita) {
		// Probes the routing fork in AttributeHistogramComputer over the IDENTICAL source histogram and bucket
		// count. STANDARD reaches RangeHistogramDataCruncher (overlap bars): overallCount is the count of DISTINCT
		// seeded ranges (4) and the per-bucket overlap sum exceeds it because every range is counted in each bucket
		// it spans. EQUALIZED reaches EqualizedHistogramDataCruncher fed the same range sweep: each range is
		// accounted at every global stop its `[from, to]` covers (the rolling active set), so overallCount is the
		// stop-sum and equals the per-bucket occurrence sum. The value span `[10, 35]` is identical across both
		// because both consume the same sweep endpoints.
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HistogramContract standard =
					queryGroupHistogram(session, HISTOGRAM_RANGE, 10, HistogramBehavior.STANDARD);
				final HistogramContract equalized =
					queryGroupHistogram(session, HISTOGRAM_RANGE, 10, HistogramBehavior.EQUALIZED);

				// STANDARD — distinct-overlap contract
				assertEquals(
					ALL_RANGES.size(), standard.getOverallCount(),
					"STANDARD overallCount must equal the " + ALL_RANGES.size() + " distinct seeded ranges"
				);
				assertTrue(
					occurrenceSum(standard) > standard.getOverallCount(),
					"STANDARD overlap bars must inflate the per-bucket sum (" + occurrenceSum(standard)
						+ ") above the distinct overallCount (" + standard.getOverallCount() + ")"
				);

				// EQUALIZED — stop-sum / point contract
				assertEquals(
					RANGE_STOP_SUM, equalized.getOverallCount(),
					"EQUALIZED overallCount must equal the range-sweep stop-sum " + RANGE_STOP_SUM
				);
				assertEquals(
					equalized.getOverallCount(), occurrenceSum(equalized),
					"EQUALIZED per-bucket occurrences must sum to its stop-sum overallCount"
				);

				// the value span is identical — both behaviors consume the same sweep endpoints [10, 35]
				assertEquals(
					0, standard.getMin().compareTo(equalized.getMin()),
					"min must be identical across STANDARD and EQUALIZED — both span the same sweep endpoints"
				);
				assertEquals(
					0, standard.getMax().compareTo(equalized.getMax()),
					"max must be identical across STANDARD and EQUALIZED — both span the same sweep endpoints"
				);
				assertEquals(
					0, standard.getMin().compareTo(BigDecimal.valueOf(10)), "span must start at 10"
				);
				assertEquals(
					0, standard.getMax().compareTo(BigDecimal.valueOf(35)), "span must end at 35"
				);
			}
		);
	}

	@ParameterizedTest(name = "behavior={0}")
	@EnumSource(HistogramBehavior.class)
	@UseDataSet(REFERENCE_HISTOGRAM_RANGE)
	@DisplayName("should apply the per-behavior overallCount rule for the range source")
	void shouldApplyPerBehaviorOverallCountRuleForRangeSource(
		@Nonnull HistogramBehavior behavior, @Nonnull Evita evita
	) {
		// Sweeps every HistogramBehavior over the same range source and bucket count, asserting the routing fork's
		// per-behavior overallCount rule: the equal-width family (STANDARD/OPTIMIZED) renders distinct-overlap bars
		// whose overallCount is the DISTINCT seeded-range count (4), whereas the frequency-equalised family
		// (EQUALIZED/EQUALIZED_OPTIMIZED) accounts each range at every covered stop, yielding the stop-sum (12). The
		// equalised family additionally satisfies the point-histogram invariant sum(occurrences) == overallCount;
		// the overlap family does NOT and is intentionally exempt. The gap-dropping *_OPTIMIZED behaviors
		// (OPTIMIZED / EQUALIZED_OPTIMIZED) may emit fewer buckets, so only the overallCount rule (not a fixed
		// bucket count) is asserted.
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HistogramContract histogram =
					queryGroupHistogram(session, HISTOGRAM_RANGE, 10, behavior);
				assertEqualisedAware(histogram, behavior);
			}
		);
	}

	/**
	 * Creates a single `parameterValue` entity carrying a `BigDecimalNumberRange` attribute whose
	 * bounds are parsed from the supplied decimal strings. The bounds are intentionally given
	 * integer string forms so the range's effective retained scale is `0`, lower than the schema's
	 * `indexDecimalPlaces`, exercising the scale-normalization gap on the histogram-index path.
	 */
	private static void createParameterValueWithBigDecimalRange(
		@Nonnull EvitaSessionContract session,
		int pk,
		@Nonnull String attributeName,
		@Nonnull String from, @Nonnull String to
	) {
		session.createNewEntity(ENTITY_PARAMETER_VALUE, pk)
			.setAttribute(
				attributeName,
				BigDecimalNumberRange.between(new BigDecimal(from), new BigDecimal(to))
			)
			.upsertVia(session);
	}

	/**
	 * Creates a single `parameterValue` entity carrying a scalar `BigDecimal` attribute parsed from the
	 * supplied decimal string. The value is intentionally given at a scale HIGHER than the schema's
	 * `indexDecimalPlaces`, exercising the histogram value→int rounding contract on the scalar path.
	 */
	private static void createParameterValueWithScalarBigDecimal(
		@Nonnull EvitaSessionContract session,
		int pk,
		@Nonnull String attributeName,
		@Nonnull String value
	) {
		session.createNewEntity(ENTITY_PARAMETER_VALUE, pk)
			.setAttribute(attributeName, new BigDecimal(value))
			.upsertVia(session);
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
	 * Independently re-derived stop-sum for the frequency-equalised range path: the sum, over every distinct
	 * range-sweep stop, of the number of seeded ranges whose closed interval `[from, to]` covers that stop.
	 *
	 * The seeded ranges are `[10, 20]`, `[15, 25]`, `[20, 30]`, `[25, 35]`, giving the distinct stops
	 * `10, 15, 20, 25, 30, 35` with active-set sizes `1, 2, 3, 3, 2, 1` respectively, so the stop-sum is
	 * `1 + 2 + 3 + 3 + 2 + 1 = 12`. This is the `overallCount` the EQUALIZED / EQUALIZED_OPTIMIZED behaviors must
	 * report (each range accounted at every covered stop), as opposed to the distinct-range count `4` reported by
	 * the STANDARD / OPTIMIZED overlap path. Derived here by hand, never read from the histogram engine.
	 */
	private static final int RANGE_STOP_SUM = 12;

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

	/**
	 * Sums the per-bucket occurrences of `histogram`.
	 */
	private static int occurrenceSum(@Nonnull HistogramContract histogram) {
		int sum = 0;
		for (final Bucket bucket : histogram.getBuckets()) {
			sum += bucket.occurrences();
		}
		return sum;
	}

	/**
	 * Asserts the per-behavior `overallCount` rule for the range source, branching on the histogram family rather
	 * than applying one oracle to both.
	 *
	 * - Equal-width family (`STANDARD`, `OPTIMIZED`) — DISTINCT-OVERLAP semantics: `overallCount` equals the number
	 *   of distinct seeded ranges ({@link #ALL_RANGES}`.size()` = 4) and the per-bucket occurrence sum is allowed
	 *   to exceed it (each range is counted in every bucket it overlaps). The distinct-overlap bucket oracle
	 *   {@link #assertBucketsMatchOverlapOracle} is applied here.
	 * - Frequency-equalised family (`EQUALIZED`, `EQUALIZED_OPTIMIZED`) — STOP-SUM / point semantics: `overallCount`
	 *   equals {@link #RANGE_STOP_SUM} (12) and the per-bucket occurrences sum exactly to it. The overlap oracle is
	 *   deliberately NOT applied — it encodes distinct-overlap semantics and would falsely fail the equalised path.
	 */
	private static void assertEqualisedAware(
		@Nonnull HistogramContract histogram, @Nonnull HistogramBehavior behavior
	) {
		final boolean equalised = behavior == HistogramBehavior.EQUALIZED
			|| behavior == HistogramBehavior.EQUALIZED_OPTIMIZED;
		if (equalised) {
			assertEquals(
				RANGE_STOP_SUM, histogram.getOverallCount(),
				"equalised behavior " + behavior + " overallCount must equal the stop-sum " + RANGE_STOP_SUM
			);
			assertEquals(
				histogram.getOverallCount(), occurrenceSum(histogram),
				"equalised behavior " + behavior + " per-bucket occurrences must sum to overallCount"
			);
		} else {
			assertEquals(
				ALL_RANGES.size(), histogram.getOverallCount(),
				"overlap behavior " + behavior + " overallCount must equal the " + ALL_RANGES.size()
					+ " distinct seeded ranges"
			);
			assertBucketsMatchOverlapOracle(histogram, ALL_RANGES);
		}
	}

}
