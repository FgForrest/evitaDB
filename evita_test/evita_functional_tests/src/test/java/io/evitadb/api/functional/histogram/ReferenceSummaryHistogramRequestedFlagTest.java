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
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.annotation.UseDataSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.function.Consumer;
import org.junit.jupiter.api.Tag;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.and;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.facetHaving;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.histogramHaving;
import static io.evitadb.api.query.QueryConstraints.histogramStatistics;
import static io.evitadb.api.query.QueryConstraints.referenceSummaryOfReferenceWithHistograms;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.userFilter;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.REFERENCE;

/**
 * Behavioral coverage of the {@code userFilter → histogramHaving} walker that flips per-bucket
 * `requested` flags on histograms returned by
 * {@code referenceSummary} / {@code referenceSummaryOfReference}. Groups the positive paths
 * (reference-attribute and referenced-entity-attribute histograms, open-ended bounds) with
 * negative paths that must not flip any bucket (mismatched histogram name, multiple independent
 * matches, coexistence with `facetHaving`).
 *
 * All tests share the {@link #REFERENCE_HISTOGRAM_SMALL} fixture.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Reference summary histogram — userFilter requested-flag behavior")
@Tag(CONTRACT)
@Tag(HISTOGRAM)
@Tag(REFERENCE)
public class ReferenceSummaryHistogramRequestedFlagTest extends AbstractReferenceSummaryHistogramFunctionalTest {

	// ==========================================================================================
	// baseline: no userFilter at all — every bucket must be flagged requested=true so clients
	// can render the slider widget from min to max without special-casing the empty state
	// ==========================================================================================

	@Nested
	@DisplayName("Requested flag — no userFilter present")
	class RequestedFlag {

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_SMALL)
		@DisplayName("should mark all `requested` true when userFilter carries no matching histogramHaving")
		void shouldMarkAllBucketsRequestedWhenUserFilterDoesNotMatch(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							require(
								referenceSummaryOfReferenceWithHistograms(
									REF_PARAM_VALUES, null, null, null,
									histogramStatistics(5, HISTOGRAM_PRICE)
								)
							)
						),
						EntityReferenceContract.class
					);
					final ReferenceSummary referenceSummary = result.getExtraResult(ReferenceSummary.class);
					assertNotNull(referenceSummary);
					final ReferenceGroupStatistics group1 = referenceSummary.getReferenceGroupStatistics(
						REF_PARAM_VALUES, 1
					);
					assertNotNull(group1);
					final HistogramContract histogram = group1.getHistogramStatistics(HISTOGRAM_PRICE);
					assertNotNull(histogram);
					for (final Bucket bucket : histogram.getBuckets()) {
						assertTrue(
							bucket.requested(),
							"Without a histogramHaving slider every bucket must have requested=true "
								+ "(no selection is rendered as full-range selection by clients)"
						);
					}
				}
			);
		}
	}

	// ==========================================================================================
	// positive flips — reference-attribute / entity-attribute / open-ended ranges
	// ==========================================================================================

	/**
	 * Verifies the positive paths where a
	 * {@code userFilter(histogramHaving(refName, histogramName, lo, hi))} subtree flips the
	 * per-bucket {@code requested} flag for buckets whose threshold lies in `[lo, hi]`.
	 * Exercises both histogram source types (REFERENCE_ATTRIBUTE and
	 * REFERENCED_ENTITY_ATTRIBUTE) and open-ended ranges.
	 */
	@Nested
	@DisplayName("Requested flag from userFilter → histogramHaving")
	class RequestedFlagFromUserFilterHistogramHaving {

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_SMALL)
		@DisplayName("should mark requested buckets for reference-attribute range")
		void shouldMarkRequestedBucketsForReferenceAttributeRange(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					// baseline: no userFilter — every bucket must be requested=true (full range
					// selected by default); capture occurrences for the range comparison below
					final EvitaResponse<EntityReferenceContract> baseline = session.query(
						query(
							collection(ENTITY_PRODUCT),
							require(
								referenceSummaryOfReferenceWithHistograms(
									REF_PARAM_VALUES, null, null, null,
									histogramStatistics(5, HISTOGRAM_MARKET_SHARE)
								)
							)
						),
						EntityReferenceContract.class
					);
					final HistogramContract baseHistogram = baseline
						.getExtraResult(ReferenceSummary.class)
						.getReferenceGroupStatistics(REF_PARAM_VALUES, 1)
						.getHistogramStatistics(HISTOGRAM_MARKET_SHARE);
					assertNotNull(baseHistogram);
					for (final Bucket b : baseHistogram.getBuckets()) {
						assertTrue(b.requested(), "Baseline buckets must all be requested=true");
					}

					// requested range [10, 50] on the reference-level histogram (REFERENCE_ATTRIBUTE source)
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(
								userFilter(
									histogramHaving(
										REF_PARAM_VALUES, HISTOGRAM_MARKET_SHARE, 10, 50
									)
								)
							),
							require(
								referenceSummaryOfReferenceWithHistograms(
									REF_PARAM_VALUES, null, null, null,
									histogramStatistics(5, HISTOGRAM_MARKET_SHARE)
								)
							)
						),
						EntityReferenceContract.class
					);
					final ReferenceSummary summary = result.getExtraResult(ReferenceSummary.class);
					assertNotNull(summary);
					final ReferenceGroupStatistics group1 = summary.getReferenceGroupStatistics(
						REF_PARAM_VALUES, 1
					);
					assertNotNull(group1);
					final HistogramContract histogram = group1.getHistogramStatistics(HISTOGRAM_MARKET_SHARE);
					assertNotNull(histogram);
					final BigDecimal lo = new BigDecimal(10);
					final BigDecimal hi = new BigDecimal(50);
					final Bucket[] buckets = histogram.getBuckets();
					final Bucket[] baseBuckets = baseHistogram.getBuckets();
					assertEquals(baseBuckets.length, buckets.length,
						"userFilter must not change the bucket count");
					for (int i = 0; i < buckets.length; i++) {
						final Bucket bucket = buckets[i];
						final BigDecimal threshold = bucket.threshold();
						final boolean inRange = threshold.compareTo(lo) >= 0
							&& threshold.compareTo(hi) <= 0;
						assertEquals(inRange, bucket.requested(),
							"Bucket at " + threshold + " must have requested=" + inRange);
						assertEquals(baseBuckets[i].occurrences(), bucket.occurrences(),
							"Bucket at " + threshold + " must have unchanged occurrences");
					}
				}
			);
		}

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_SMALL)
		@DisplayName("should mark requested buckets for referenced-entity-attribute range")
		void shouldMarkRequestedBucketsForReferencedEntityAttributeRange(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					// requested range [10, 20] on the REFERENCED_ENTITY_ATTRIBUTE histogram (sources basicUnitValue)
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(
								userFilter(
									histogramHaving(
										REF_PARAM_VALUES, HISTOGRAM_PRICE, 10, 20
									)
								)
							),
							require(
								referenceSummaryOfReferenceWithHistograms(
									REF_PARAM_VALUES, null, null, null,
									histogramStatistics(5, HISTOGRAM_PRICE)
								)
							)
						),
						EntityReferenceContract.class
					);
					final ReferenceSummary summary = result.getExtraResult(ReferenceSummary.class);
					assertNotNull(summary);
					final ReferenceGroupStatistics group1 = summary.getReferenceGroupStatistics(
						REF_PARAM_VALUES, 1
					);
					assertNotNull(group1);
					final HistogramContract histogram = group1.getHistogramStatistics(HISTOGRAM_PRICE);
					assertNotNull(histogram);
					final BigDecimal lo = new BigDecimal(10);
					final BigDecimal hi = new BigDecimal(20);
					boolean anyFlipped = false;
					for (final Bucket bucket : histogram.getBuckets()) {
						final BigDecimal threshold = bucket.threshold();
						final boolean inRange = threshold.compareTo(lo) >= 0
							&& threshold.compareTo(hi) <= 0;
						assertEquals(inRange, bucket.requested(),
							"Bucket at " + threshold + " must have requested=" + inRange);
						if (inRange) {
							anyFlipped = true;
						}
					}
					assertTrue(anyFlipped,
						"At least one bucket within [10, 20] must exist and be flipped");
				}
			);
		}

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_SMALL)
		@DisplayName("should mark buckets requested for an open-ended range with lower bound only")
		void shouldMarkRequestedBucketsForLowerBoundOnlyRange(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					// open-ended requested range [50, +∞) on reference-level histogram
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(
								userFilter(
									histogramHaving(
										REF_PARAM_VALUES, HISTOGRAM_MARKET_SHARE,
										50, (Integer) null
									)
								)
							),
							require(
								referenceSummaryOfReferenceWithHistograms(
									REF_PARAM_VALUES, null, null, null,
									histogramStatistics(5, HISTOGRAM_MARKET_SHARE)
								)
							)
						),
						EntityReferenceContract.class
					);
					final ReferenceSummary summary = result.getExtraResult(ReferenceSummary.class);
					assertNotNull(summary);
					// we don't know upfront which group has values ≥ 50 — iterate both
					final BigDecimal lo = new BigDecimal(50);
					boolean anyFlipped = false;
					for (int groupPk = 1; groupPk <= 2; groupPk++) {
						final ReferenceGroupStatistics group = summary.getReferenceGroupStatistics(
							REF_PARAM_VALUES, groupPk
						);
						if (group == null) {
							continue;
						}
						final HistogramContract histogram = group.getHistogramStatistics(
							HISTOGRAM_MARKET_SHARE
						);
						if (histogram == null) {
							continue;
						}
						for (final Bucket bucket : histogram.getBuckets()) {
							final BigDecimal threshold = bucket.threshold();
							final boolean inRange = threshold.compareTo(lo) >= 0;
							assertEquals(inRange, bucket.requested(),
								"Bucket at " + threshold + " in group " + groupPk
									+ " must have requested=" + inRange);
							if (inRange) {
								anyFlipped = true;
							}
						}
					}
					assertTrue(anyFlipped,
						"At least one bucket with threshold ≥ 50 must exist across groups");
				}
			);
		}
	}

	// ==========================================================================================
	// negative paths — mismatch, duplicates, coexistence
	// ==========================================================================================

	/**
	 * Negative / cross-scenario coverage for `userFilter → histogramHaving` behaviour: mismatched
	 * reference names, mismatched histogram names, duplicate ranges on the same (reference, histogram)
	 * pair, and coexistence with a `facetHaving` in the same `userFilter`.
	 */
	@Nested
	@DisplayName("Negative paths for userFilter → histogramHaving")
	class NegativePathsForUserFilterHistogramHaving {

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_SMALL)
		@DisplayName("should leave the unrelated histogram with all buckets requested when histogramHaving targets a different histogram")
		void shouldLeaveUnrelatedHistogramAllRequestedWhenHistogramNameDoesNotMatch(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					// Asking for histogram `priceBucket` (sources `basicUnitValue`) while the
					// histogramHaving targets the `marketShareBucket` slot — the `priceBucket`
					// histogram has no slider of its own so every bucket must be requested=true,
					// matching the "no selection == full range" convention.
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(
								userFilter(
									histogramHaving(
										REF_PARAM_VALUES, HISTOGRAM_MARKET_SHARE, 10, 50
									)
								)
							),
							require(
								referenceSummaryOfReferenceWithHistograms(
									REF_PARAM_VALUES, null, null, null,
									histogramStatistics(5, HISTOGRAM_PRICE)
								)
							)
						),
						EntityReferenceContract.class
					);
					final ReferenceSummary summary = result.getExtraResult(ReferenceSummary.class);
					assertNotNull(summary);
					final ReferenceGroupStatistics group1 = summary.getReferenceGroupStatistics(
						REF_PARAM_VALUES, 1
					);
					assertNotNull(group1);
					final HistogramContract histogram = group1.getHistogramStatistics(HISTOGRAM_PRICE);
					assertNotNull(histogram);
					for (final Bucket bucket : histogram.getBuckets()) {
						assertTrue(bucket.requested(),
							"histogramHaving targeting a different histogram leaves the unrelated "
								+ "histogram with no slider — every bucket must remain requested=true");
					}
				}
			);
		}

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_SMALL)
		@DisplayName("should throw when multiple independent matches for same histogram are present")
		void shouldThrowWhenMultipleIndependentMatchesForSameHistogram(@Nonnull Evita evita) {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> evita.queryCatalog(
					TEST_CATALOG,
					(Consumer<EvitaSessionContract>) session -> session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(
								userFilter(
									and(
										histogramHaving(
											REF_PARAM_VALUES, HISTOGRAM_MARKET_SHARE, 10, 50
										),
										histogramHaving(
											REF_PARAM_VALUES, HISTOGRAM_MARKET_SHARE, 60, 90
										)
									)
								)
							),
							require(
								referenceSummaryOfReferenceWithHistograms(
									REF_PARAM_VALUES, null, null, null,
									histogramStatistics(5, HISTOGRAM_MARKET_SHARE)
								)
							)
						),
						EntityReferenceContract.class
					)
				),
				"Multiple `histogramHaving` subtrees targeting the same (reference, histogram, group) must throw"
			);
		}

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_SMALL)
		@DisplayName("should coexist with facetHaving in the same userFilter")
		void shouldCoexistWithFacetHavingInSameUserFilter(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(
								userFilter(
									and(
										facetHaving(
											REF_PARAM_VALUES,
											entityPrimaryKeyInSet(1, 2)
										),
										histogramHaving(
											REF_PARAM_VALUES, HISTOGRAM_MARKET_SHARE, 10, 50
										)
									)
								)
							),
							require(
								referenceSummaryOfReferenceWithHistograms(
									REF_PARAM_VALUES, null, null, null,
									histogramStatistics(5, HISTOGRAM_MARKET_SHARE)
								)
							)
						),
						EntityReferenceContract.class
					);
					final ReferenceSummary summary = result.getExtraResult(ReferenceSummary.class);
					assertNotNull(summary);
					final ReferenceGroupStatistics group1 = summary.getReferenceGroupStatistics(
						REF_PARAM_VALUES, 1
					);
					assertNotNull(group1);
					final HistogramContract histogram = group1.getHistogramStatistics(HISTOGRAM_MARKET_SHARE);
					assertNotNull(histogram);
					final BigDecimal lo = new BigDecimal(10);
					final BigDecimal hi = new BigDecimal(50);
					boolean anyFlipped = false;
					for (final Bucket bucket : histogram.getBuckets()) {
						final BigDecimal threshold = bucket.threshold();
						final boolean inRange = threshold.compareTo(lo) >= 0
							&& threshold.compareTo(hi) <= 0;
						assertEquals(inRange, bucket.requested(),
							"Bucket at " + threshold + " must have requested=" + inRange
								+ " even when coexisting with facetHaving");
						if (inRange) {
							anyFlipped = true;
						}
					}
					assertTrue(anyFlipped,
						"At least one bucket in [10, 50] must flip when facetHaving coexists");
					// Base entity count reflects both constraints being applied — in particular the
					// result must be non-empty (products 1, 2, 4 in group 1 reference PV #1 or #2
					// with marketShare in {10, 20, 40, 50}).
					assertTrue(result.getTotalRecordCount() > 0,
						"Query must return entities matching both facetHaving and histogramHaving");
				}
			);
		}
	}

}
