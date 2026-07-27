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
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.HistogramBehavior;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.HistogramContract.Bucket;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.core.Evita;
import io.evitadb.test.annotation.UseDataSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Tag;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.REFERENCE;

/**
 * Core functional coverage for `referenceSummary` / `referenceSummaryOfReference` wired together
 * with `histogramStatistics`. Exercises the happy paths of both histogram domains
 * (REFERENCE_ATTRIBUTE and REFERENCED_ENTITY_ATTRIBUTE), the bucket-behavior matrix, query-time
 * narrowing via filters and user filters, the hand-computed oracle cross-check, the all-references
 * fan-out, plus the small-fixture invariants for group-filter survival and min/max anchors.
 *
 * Tests grouped by {@link Nested} so each area reads as a coherent sub-suite; each `@Test` declares
 * the dataset it needs via `@UseDataSet` and receives the shared {@link Evita} through parameter
 * injection — no more per-test spin-ups.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Reference summary histogram — core functional tests")
@Tag(CONTRACT)
@Tag(HISTOGRAM)
@Tag(REFERENCE)
public class ReferenceSummaryHistogramFunctionalTest extends AbstractReferenceSummaryHistogramFunctionalTest {

	// ==========================================================================================
	// LARGE fixture — happy path coverage across both histogram domains
	// ==========================================================================================

	@Nested
	@DisplayName("Happy path — both histogram domains (large fixture)")
	class HappyPathLarge {

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_LARGE)
		@DisplayName("should populate REFERENCED_ENTITY_ATTRIBUTE histogram per group with correct bucket totals")
		void shouldPopulateReferencedEntityAttributeHistogramPerGroup(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							require(
								page(1, Integer.MAX_VALUE),
								referenceSummaryOfReferenceWithHistograms(
									REF_PARAM_VALUES, null, null, null,
									histogramStatistics(10, HISTOGRAM_PRICE)
								)
							)
						),
						EntityReferenceContract.class
					);
					final ReferenceSummary referenceSummary = result.getExtraResult(ReferenceSummary.class);
					assertNotNull(referenceSummary);

					for (int groupPk = 1; groupPk <= GROUP_COUNT; groupPk++) {
						final ReferenceGroupStatistics group =
							referenceSummary.getReferenceGroupStatistics(REF_PARAM_VALUES, groupPk);
						assertNotNull(group, "Group " + groupPk + " must exist");
						final HistogramContract histogram = group.getHistogramStatistics(HISTOGRAM_PRICE);
						assertNotNull(histogram, "Group " + groupPk + " must carry a priceBucket histogram");
						assertTrue(histogram.getBuckets().length > 0, "Histogram must have at least one bucket");

						// min must be lower-or-equal to max and strictly less than max when there is
						// more than one distinct value in the group
						assertTrue(
							histogram.getMin().compareTo(histogram.getMax()) <= 0,
							"min must be <= max for group " + groupPk
						);

						// bucket thresholds must be strictly monotonic
						BigDecimal previousThreshold = null;
						for (final Bucket bucket : histogram.getBuckets()) {
							if (previousThreshold != null) {
								assertTrue(
									previousThreshold.compareTo(bucket.threshold()) < 0,
									"bucket thresholds must be strictly increasing, found "
										+ previousThreshold + " >= " + bucket.threshold()
								);
							}
							previousThreshold = bucket.threshold();
						}

						// overallCount must equal sum of bucket occurrences
						int bucketSum = 0;
						for (final Bucket bucket : histogram.getBuckets()) {
							bucketSum += bucket.occurrences();
						}
						assertEquals(histogram.getOverallCount(), bucketSum);
					}
				}
			);
		}

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_LARGE)
		@DisplayName("should populate REFERENCE_ATTRIBUTE histogram per group with boundary entities")
		void shouldPopulateReferenceAttributeHistogramPerGroup(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							require(
								page(1, Integer.MAX_VALUE),
								referenceSummaryOfReferenceWithHistograms(
									REF_PARAM_VALUES, null, null, null,
									histogramStatistics(
										10,
										entityFetch(attributeContent(ATTR_NAME)),
										HISTOGRAM_MARKET_SHARE
									)
								)
							)
						),
						EntityReferenceContract.class
					);
					final ReferenceSummary referenceSummary = result.getExtraResult(ReferenceSummary.class);
					assertNotNull(referenceSummary);

					for (int groupPk = 1; groupPk <= GROUP_COUNT; groupPk++) {
						final ReferenceGroupStatistics group =
							referenceSummary.getReferenceGroupStatistics(REF_PARAM_VALUES, groupPk);
						assertNotNull(group);
						final HistogramContract histogram = group.getHistogramStatistics(HISTOGRAM_MARKET_SHARE);
						assertNotNull(histogram);
						assertTrue(histogram.getBuckets().length > 0);
						// REFERENCE_ATTRIBUTE boundary resolution: the reference-attribute
						// FilterIndex on RGEI is keyed on the referenced entity PK (via
						// `executeWithDifferentPrimaryKeyToIndex` during insert), so
						// `getRecordsEqualTo(value)` resolves boundary PKs directly.
						assertTrue(histogram.getMinReferencedEntity().isPresent(),
							"REFERENCE_ATTRIBUTE histogram must populate minReferencedEntity for group " + groupPk);
						assertTrue(histogram.getMaxReferencedEntity().isPresent(),
							"REFERENCE_ATTRIBUTE histogram must populate maxReferencedEntity for group " + groupPk);
					}
				}
			);
		}

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_LARGE)
		@DisplayName("should populate both histograms in a single request")
		void shouldPopulateBothHistogramsInSingleRequest(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							require(
								page(1, Integer.MAX_VALUE),
								referenceSummaryOfReferenceWithHistograms(
									REF_PARAM_VALUES, null, null, null,
									histogramStatistics(10, HISTOGRAM_PRICE),
									histogramStatistics(10, HISTOGRAM_MARKET_SHARE)
								)
							)
						),
						EntityReferenceContract.class
					);
					final ReferenceSummary referenceSummary = result.getExtraResult(ReferenceSummary.class);
					assertNotNull(referenceSummary);
					for (int groupPk = 1; groupPk <= GROUP_COUNT; groupPk++) {
						final ReferenceGroupStatistics group =
							referenceSummary.getReferenceGroupStatistics(REF_PARAM_VALUES, groupPk);
						assertNotNull(group);
						assertEquals(
							Set.of(HISTOGRAM_PRICE, HISTOGRAM_MARKET_SHARE),
							new HashSet<>(group.getHistogramStatistics().keySet()),
							"Group " + groupPk + " must expose both histogram entries"
						);
					}
				}
			);
		}

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_LARGE)
		@DisplayName("should compute histogram via the all-references referenceSummary form")
		void shouldComputeHistogramViaAllReferencesFanOut(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							require(
								page(1, Integer.MAX_VALUE),
								referenceSummaryWithHistograms(
									null, null, null,
									histogramStatistics(10, HISTOGRAM_PRICE)
								)
							)
						),
						EntityReferenceContract.class
					);
					final ReferenceSummary referenceSummary = result.getExtraResult(ReferenceSummary.class);
					assertNotNull(referenceSummary);
					final ReferenceGroupStatistics group1 = referenceSummary.getReferenceGroupStatistics(
						REF_PARAM_VALUES, 1);
					assertNotNull(group1);
					assertNotNull(
						group1.getHistogramStatistics(HISTOGRAM_PRICE),
						"All-references fan-out must still populate the histogram for the sole reference "
							+ "that carries it"
					);
				}
			);
		}
	}

	// ==========================================================================================
	// LARGE fixture — bucket behavior matrix
	// ==========================================================================================

	@Nested
	@DisplayName("Bucket behavior matrix (large fixture)")
	class BehaviorMatrix {

		@ParameterizedTest(name = "behavior={0}")
		@EnumSource(HistogramBehavior.class)
		@UseDataSet(REFERENCE_HISTOGRAM_LARGE)
		@DisplayName("bucket occurrences must sum to overallCount for every HistogramBehavior")
		void shouldHaveBucketSumEqualOverallCountForEveryBehavior(
			@Nonnull HistogramBehavior behavior, @Nonnull Evita evita
		) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							require(
								page(1, Integer.MAX_VALUE),
								referenceSummaryOfReferenceWithHistograms(
									REF_PARAM_VALUES, null, null, null,
									histogramStatistics(8, behavior, HISTOGRAM_PRICE)
								)
							)
						),
						EntityReferenceContract.class
					);
					final ReferenceSummary referenceSummary = result.getExtraResult(ReferenceSummary.class);
					assertNotNull(referenceSummary);

					// For each group, verify that the sum of per-bucket occurrences equals
					// `overallCount`. The check runs for every HistogramBehavior value so a
					// behavior that double-counts or drops buckets would immediately fail here.
					for (int groupPk = 1; groupPk <= GROUP_COUNT; groupPk++) {
						final HistogramContract histogram = referenceSummary
							.getReferenceGroupStatistics(REF_PARAM_VALUES, groupPk)
							.getHistogramStatistics(HISTOGRAM_PRICE);
						assertNotNull(histogram, "Histogram must exist for behavior " + behavior);
						int sum = 0;
						for (final Bucket bucket : histogram.getBuckets()) {
							sum += bucket.occurrences();
						}
						assertEquals(histogram.getOverallCount(), sum,
							"Bucket sum must equal overallCount for behavior " + behavior);
					}
				}
			);
		}
	}

	// ==========================================================================================
	// LARGE fixture — query-time narrowing (base filter, user filter, pagination)
	// ==========================================================================================

	@Nested
	@DisplayName("Query-time narrowing (large fixture)")
	class QueryInteraction {

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_LARGE)
		@DisplayName("should not grow histogram overallCount when a base filter narrows the product set")
		void shouldNotGrowHistogramOverallCountWhenBaseFilterNarrows(@Nonnull Evita evita) {
			final Map<Integer, Integer> unfiltered = runAndCollectOverallCounts(evita, null);
			// Narrow via a base attribute filter (product quantity band). The histograms must
			// reflect the narrower product set — per group, the filtered count can never exceed
			// the unfiltered count. Strict shrinkage is NOT asserted because some groups may
			// contain no products inside the quantity band and simply match the unfiltered count.
			final Map<Integer, Integer> filtered = runAndCollectOverallCounts(
				evita,
				filterBy(attributeBetween(ATTR_QUANTITY, new BigDecimal("1"), new BigDecimal("50")))
			);
			for (final Entry<Integer, Integer> entry : unfiltered.entrySet()) {
				final Integer groupPk = entry.getKey();
				final Integer filteredCount = filtered.getOrDefault(groupPk, 0);
				assertTrue(
					filteredCount <= entry.getValue(),
					"Narrowed histogram overallCount must be <= unfiltered for group " + groupPk
				);
			}
		}

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_LARGE)
		@DisplayName("should produce identical histogram overallCount regardless of page size")
		void shouldProduceIdenticalOverallCountRegardlessOfPageSize(@Nonnull Evita evita) {
			final Map<Integer, Integer> page1 = runAndCollectOverallCounts(evita, null, 1, 5);
			final Map<Integer, Integer> pageAll = runAndCollectOverallCounts(evita, null, 1, Integer.MAX_VALUE);
			assertEquals(pageAll, page1,
				"Histogram overallCount per group must be identical regardless of page size");
		}

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_LARGE)
		@DisplayName("should not grow anchor-group histogram overallCount when a userFilter facet selection is applied")
		void shouldNotGrowAnchorGroupHistogramOverallCountUnderUserFilterFacet(
			@Nonnull Evita evita,
			@Nonnull List<SealedEntity> originalProducts
		) {
			// Pick any product carrying parameter-value refs and reuse its PVs as the facet
			// selection. Resolving the anchor product (and its group) from the real fixture
			// keeps the test invariant under fixture reshuffles and avoids the AND-between-
			// groups trap: every PV pulled from one product shares the same parent group, so
			// the facet filter is satisfiable by construction.
			final SealedEntity anchorProduct = originalProducts.stream()
				.filter(p -> !p.getReferences(REF_PARAM_VALUES).isEmpty())
				.findFirst()
				.orElseThrow(() -> new AssertionError(
					"Fixture must contain at least one product with a non-empty " + REF_PARAM_VALUES
						+ " reference set"));
			final Integer[] selectedPvs = anchorProduct.getReferences(REF_PARAM_VALUES).stream()
				.map(ReferenceContract::getReferencedPrimaryKey)
				.distinct()
				.toArray(Integer[]::new);
			final int anchorGroupPk = anchorProduct.getReferences(REF_PARAM_VALUES).iterator().next()
				.getGroup()
				.map(io.evitadb.api.requestResponse.data.EntityReferenceContract::getPrimaryKey)
				.orElseThrow(() -> new AssertionError("Anchor product's reference must carry a group"));

			final Map<Integer, Integer> unfiltered = runAndCollectOverallCounts(evita, null);
			final Map<Integer, Integer> withFacet = runAndCollectOverallCounts(
				evita,
				filterBy(
					userFilter(
						facetHaving(REF_PARAM_VALUES, entityPrimaryKeyInSet(selectedPvs))
					)
				)
			);

			// the anchor's group must see a narrower-or-equal overallCount; a zero on both
			// sides would silently pass, so additionally require the unfiltered count to be
			// strictly positive — confirms the assertion actually exercises a populated group
			final Integer unfilteredCount = unfiltered.get(anchorGroupPk);
			assertNotNull(unfilteredCount,
				"Unfiltered histogram must populate the anchor group " + anchorGroupPk);
			assertTrue(unfilteredCount > 0,
				"Unfiltered anchor-group histogram must carry at least one reference");
			assertTrue(
				withFacet.getOrDefault(anchorGroupPk, 0) <= unfilteredCount,
				"Facet-narrowed histogram for anchor group " + anchorGroupPk + " must be <= unfiltered"
			);
		}

		@Nonnull
		private static Map<Integer, Integer> runAndCollectOverallCounts(
			@Nonnull Evita evita, @Nullable FilterConstraint filterByConstraint
		) {
			return runAndCollectOverallCounts(evita, filterByConstraint, 1, Integer.MAX_VALUE);
		}

		@Nonnull
		private static Map<Integer, Integer> runAndCollectOverallCounts(
			@Nonnull Evita evita,
			@Nullable FilterConstraint filterByConstraint,
			int pageNumber,
			int pageSize
		) {
			return evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final Query q;
					if (filterByConstraint != null) {
						q = query(
							collection(ENTITY_PRODUCT),
							filterByConstraint instanceof FilterBy fb
								? fb
								: filterBy(filterByConstraint),
							require(
								page(pageNumber, pageSize),
								referenceSummaryOfReferenceWithHistograms(
									REF_PARAM_VALUES, null, null, null,
									histogramStatistics(10, HISTOGRAM_PRICE)
								)
							)
						);
					} else {
						q = query(
							collection(ENTITY_PRODUCT),
							require(
								page(pageNumber, pageSize),
								referenceSummaryOfReferenceWithHistograms(
									REF_PARAM_VALUES, null, null, null,
									histogramStatistics(10, HISTOGRAM_PRICE)
								)
							)
						);
					}
					final ReferenceSummary referenceSummary = session.query(q, EntityReferenceContract.class)
						.getExtraResult(ReferenceSummary.class);
					assertNotNull(referenceSummary);
					final Map<Integer, Integer> result = new LinkedHashMap<>();
					for (int groupPk = 1; groupPk <= GROUP_COUNT; groupPk++) {
						final ReferenceGroupStatistics group =
							referenceSummary.getReferenceGroupStatistics(REF_PARAM_VALUES, groupPk);
						if (group != null) {
							final HistogramContract histogram = group.getHistogramStatistics(HISTOGRAM_PRICE);
							if (histogram != null) {
								result.put(groupPk, histogram.getOverallCount());
							}
						}
					}
					return result;
				}
			);
		}
	}

	// ==========================================================================================
	// LARGE fixture — hand-computed oracle agreement
	// ==========================================================================================

	@Nested
	@DisplayName("Oracle — engine output agrees with hand-computed histogram (large fixture)")
	class Oracle {

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_LARGE)
		@DisplayName("should match hand-computed REFERENCED_ENTITY_ATTRIBUTE bucket sums and min/max per group")
		void shouldMatchHandComputedBucketSumsAndMinMax(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							require(
								page(1, Integer.MAX_VALUE),
								referenceSummaryOfReferenceWithHistograms(
									REF_PARAM_VALUES, null, null, null,
									histogramStatistics(10, HISTOGRAM_PRICE)
								)
							)
						),
						EntityReferenceContract.class
					);
					final ReferenceSummary referenceSummary = result.getExtraResult(ReferenceSummary.class);
					assertNotNull(referenceSummary);

					// expected per-group reference count = sum, over all products in that group,
					// of (number of references each product carries to parameter values of that group)
					final Map<Integer, Integer> expectedPerGroup = computeExpectedReferenceCountsPerGroup(
						session
					);

					for (int groupPk = 1; groupPk <= GROUP_COUNT; groupPk++) {
						final HistogramContract histogram = referenceSummary
							.getReferenceGroupStatistics(REF_PARAM_VALUES, groupPk)
							.getHistogramStatistics(HISTOGRAM_PRICE);
						assertNotNull(histogram);
						assertEquals(
							expectedPerGroup.get(groupPk).intValue(),
							histogram.getOverallCount(),
							"Histogram overallCount must match hand-computed count for group " + groupPk
						);

						// min / max must equal the lowest / highest price in the products'
						// referenced PVs for that group — compared by value (compareTo == 0)
						// because the engine normalizes scale to indexDecimalPlaces(2) while the
						// oracle reads raw unscaled BigDecimals straight from SealedEntity
						final List<BigDecimal> referencedPrices = collectReferencedPrices(session, groupPk);
						referencedPrices.sort(Comparator.naturalOrder());
						final BigDecimal expectedMin = referencedPrices.get(0);
						final BigDecimal expectedMax = referencedPrices.get(referencedPrices.size() - 1);
						assertEquals(0, expectedMin.compareTo(histogram.getMin()),
							"Histogram min " + histogram.getMin()
								+ " must equal lowest referenced price " + expectedMin
								+ " (by value) in group " + groupPk);
						assertEquals(0, expectedMax.compareTo(histogram.getMax()),
							"Histogram max " + histogram.getMax()
								+ " must equal highest referenced price " + expectedMax
								+ " (by value) in group " + groupPk);
					}
				}
			);
		}

		@Nonnull
		private static Map<Integer, Integer> computeExpectedReferenceCountsPerGroup(
			@Nonnull EvitaSessionContract session
		) {
			final Map<Integer, Integer> counts = new LinkedHashMap<>();
			for (int productPk = 1; productPk <= PRODUCT_COUNT; productPk++) {
				final Optional<SealedEntity> product = session.getEntity(
					ENTITY_PRODUCT, productPk,
					referenceContent(REF_PARAM_VALUES)
				);
				product.ifPresent(p ->
					p.getReferences(REF_PARAM_VALUES).forEach(r -> {
						final int groupPk = r.getGroup()
							.map(io.evitadb.api.requestResponse.data.EntityReferenceContract::getPrimaryKey)
							.orElseThrow();
						counts.merge(groupPk, 1, Integer::sum);
					})
				);
			}
			return counts;
		}

		@Nonnull
		private static List<BigDecimal> collectReferencedPrices(
			@Nonnull EvitaSessionContract session, int groupPk
		) {
			final List<BigDecimal> prices = new ArrayList<>();
			for (int productPk = 1; productPk <= PRODUCT_COUNT; productPk++) {
				final Optional<SealedEntity> product = session.getEntity(
					ENTITY_PRODUCT, productPk,
					referenceContent(REF_PARAM_VALUES)
				);
				if (product.isEmpty()) {
					continue;
				}
				for (final ReferenceContract ref : product.get().getReferences(REF_PARAM_VALUES)) {
					if (ref.getGroup().map(g -> g.getPrimaryKey() == groupPk).orElse(false)) {
						final Optional<SealedEntity> pv = session.getEntity(
							ENTITY_PARAMETER_VALUE, ref.getReferencedPrimaryKey(),
							attributeContent(ATTR_PRICE)
						);
						pv.map(p -> p.getAttribute(ATTR_PRICE, BigDecimal.class))
							.ifPresent(prices::add);
					}
				}
			}
			return prices;
		}
	}

	// ==========================================================================================
	// LARGE fixture — combined constraint (base filter + userFilter)
	// ==========================================================================================

	@Nested
	@DisplayName("Combined constraint — filter + histograms together (large fixture)")
	class Combined {

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_LARGE)
		@DisplayName("should still populate at least one group histogram under attributeBetween + userFilter facet")
		void shouldPopulateHistogramUnderCompoundAttributeAndUserFilter(
			@Nonnull Evita evita,
			@Nonnull List<SealedEntity> originalProducts
		) {
			// Pick a real product matching the base filter and reuse ITS parameter-value
			// references as the facet selection. This keeps the test invariant under fixture
			// reshuffles and guarantees the compound filter is satisfiable — picking PVs
			// across two groups would AND-between-groups to empty (facetHaving defaults to OR
			// within a group, AND between groups), zeroing every histogram.
			final BigDecimal quantityLow = new BigDecimal("1");
			final BigDecimal quantityHigh = new BigDecimal("80");
			final SealedEntity anchorProduct = originalProducts.stream()
				.filter(p -> {
					final BigDecimal q = p.getAttribute(ATTR_QUANTITY, BigDecimal.class);
					return q != null
						&& q.compareTo(quantityLow) >= 0
						&& q.compareTo(quantityHigh) <= 0
						&& !p.getReferences(REF_PARAM_VALUES).isEmpty();
				})
				.findFirst()
				.orElseThrow(() -> new AssertionError(
					"Fixture must contain at least one product with quantity in ["
						+ quantityLow + ", " + quantityHigh + "] and a non-empty " + REF_PARAM_VALUES
						+ " reference set"));
			final Integer[] selectedPvs = anchorProduct.getReferences(REF_PARAM_VALUES).stream()
				.map(ReferenceContract::getReferencedPrimaryKey)
				.distinct()
				.toArray(Integer[]::new);

			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(
								and(
									attributeBetween(ATTR_QUANTITY, quantityLow, quantityHigh),
									userFilter(
										facetHaving(REF_PARAM_VALUES, entityPrimaryKeyInSet(selectedPvs))
									)
								)
							),
							require(
								page(1, Integer.MAX_VALUE),
								referenceSummaryOfReferenceWithHistograms(
									REF_PARAM_VALUES, null, null, null,
									histogramStatistics(10, HISTOGRAM_PRICE)
								)
							)
						),
						EntityReferenceContract.class
					);
					final ReferenceSummary referenceSummary = result.getExtraResult(ReferenceSummary.class);
					assertNotNull(referenceSummary);
					// At least one group must carry a populated histogram — the anchor product
					// is in the result set by construction, so its own group's histogram cannot
					// be empty.
					boolean any = false;
					for (int groupPk = 1; groupPk <= GROUP_COUNT; groupPk++) {
						final ReferenceGroupStatistics group =
							referenceSummary.getReferenceGroupStatistics(REF_PARAM_VALUES, groupPk);
						if (group != null && group.getHistogramStatistics(HISTOGRAM_PRICE) != null) {
							any = true;
						}
					}
					assertTrue(any, "At least one group must carry a histogram under the compound filter");
				}
			);
		}
	}

	// ==========================================================================================
	// SMALL fixture — happy path assertions with exact bucket values
	// ==========================================================================================

	@Nested
	@DisplayName("Happy path (small fixture)")
	class HappyPathSmall {

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_SMALL)
		@DisplayName("should populate histogramStatistics with buckets, min, max, overallCount per group")
		void shouldPopulateHistogramStatistics(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							require(
								referenceSummaryOfReferenceWithHistograms(
									REF_PARAM_VALUES, null, null, null,
									histogramStatistics(10, HISTOGRAM_PRICE)
								)
							)
						),
						EntityReferenceContract.class
					);
					final ReferenceSummary referenceSummary = result.getExtraResult(ReferenceSummary.class);
					assertNotNull(referenceSummary, "ReferenceSummary extra result must be present");

					final ReferenceGroupStatistics group1 = referenceSummary.getReferenceGroupStatistics(
						REF_PARAM_VALUES, 1
					);
					assertNotNull(group1, "Group 1 (Width) statistics must exist");
					final HistogramContract histogram1 = group1.getHistogramStatistics(HISTOGRAM_PRICE);
					assertNotNull(histogram1, "Group 1 must carry a `priceBucket` histogram");
					assertEquals(new BigDecimal("10.00"), histogram1.getMin(),
						"Group 1 histogram min must be 10 (lowest basicUnitValue)");
					assertEquals(new BigDecimal("30.00"), histogram1.getMax(),
						"Group 1 histogram max must be 30 (highest basicUnitValue)");
					assertEquals(5, histogram1.getOverallCount(),
						"Group 1 has 5 references total — products 1, 2, 3 (one each) + product 4 "
							+ "(references PV#1 and PV#2 → two)");

					final ReferenceGroupStatistics group2 = referenceSummary.getReferenceGroupStatistics(
						REF_PARAM_VALUES, 2
					);
					assertNotNull(group2, "Group 2 (Weight) statistics must exist");
					final HistogramContract histogram2 = group2.getHistogramStatistics(HISTOGRAM_PRICE);
					assertNotNull(histogram2, "Group 2 must carry a `priceBucket` histogram");
					assertEquals(new BigDecimal("100.00"), histogram2.getMin());
					assertEquals(new BigDecimal("200.00"), histogram2.getMax());
					assertEquals(2, histogram2.getOverallCount(),
						"Group 2 has 2 references total (products 5, 6)");
					return null;
				}
			);
		}

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_SMALL)
		@DisplayName("should populate minReferencedEntity / maxReferencedEntity when entityFetch is provided")
		void shouldPopulateMinMaxReferencedEntity(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							require(
								referenceSummaryOfReferenceWithHistograms(
									REF_PARAM_VALUES, null, null, null,
									histogramStatistics(
										10,
										entityFetch(attributeContent(ATTR_NAME, ATTR_BASIC_UNIT_VALUE)),
										HISTOGRAM_PRICE
									)
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
					final HistogramContract histogram1 = group1.getHistogramStatistics(HISTOGRAM_PRICE);
					assertNotNull(histogram1);

					final Optional<SealedEntity> minEntity = histogram1.getMinReferencedEntity();
					final Optional<SealedEntity> maxEntity = histogram1.getMaxReferencedEntity();
					assertTrue(minEntity.isPresent(),
						"Min boundary entity must be resolved when entityFetch is provided");
					assertTrue(maxEntity.isPresent(),
						"Max boundary entity must be resolved when entityFetch is provided");
					assertEquals(
						1, minEntity.get().getPrimaryKey(),
						"Min entity must be PV#1 (value=10, lowest in group 1)"
					);
					assertEquals(
						3, maxEntity.get().getPrimaryKey(),
						"Max entity must be PV#3 (value=30, highest in group 1)"
					);
					assertEquals(
						"10cm", minEntity.get().getAttribute(ATTR_NAME),
						"Min entity must have its `name` attribute populated from entityFetch"
					);
					return null;
				}
			);
		}

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_SMALL)
		@DisplayName("should compute histogram via all-references referenceSummary when every reference defines it")
		void shouldComputeHistogramViaAllReferencesReferenceSummary(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							require(
								referenceSummaryWithHistograms(
									null, null, null,
									histogramStatistics(10, HISTOGRAM_PRICE)
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
					assertNotNull(group1, "Fan-out must populate group 1");
					assertNotNull(
						group1.getHistogramStatistics(HISTOGRAM_PRICE),
						"Histogram must be emitted for every reference that declares it"
					);
					return null;
				}
			);
		}
	}

	// ==========================================================================================
	// SMALL fixture — group filter survival
	// ==========================================================================================

	@Nested
	@DisplayName("Group filter survival (small fixture)")
	class GroupFilter {

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_SMALL)
		@DisplayName("every group exposes the requested histogram on an unfiltered query")
		void shouldEmitHistogramForEveryGroupWhenUnfiltered(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							require(
								referenceSummaryOfReferenceWithHistograms(
									REF_PARAM_VALUES, null, null, null,
									histogramStatistics(10, HISTOGRAM_PRICE)
								)
							)
						),
						EntityReferenceContract.class
					);
					final ReferenceSummary referenceSummary = result.getExtraResult(ReferenceSummary.class);
					assertNotNull(referenceSummary);
					// without filterBy, every reference contributes facets AND histogram data,
					// so both groups must be present and each must expose the requested histogram
					final ReferenceGroupStatistics group1 = referenceSummary.getReferenceGroupStatistics(
						REF_PARAM_VALUES, 1
					);
					final ReferenceGroupStatistics group2 = referenceSummary.getReferenceGroupStatistics(
						REF_PARAM_VALUES, 2
					);
					assertNotNull(group1, "Group 1 must appear");
					assertNotNull(group2, "Group 2 must appear");
					assertArrayEquals(
						new String[]{HISTOGRAM_PRICE},
						group1.getHistogramStatistics().keySet().toArray(new String[0]),
						"Group 1 must expose exactly one histogram entry"
					);
					assertArrayEquals(
						new String[]{HISTOGRAM_PRICE},
						group2.getHistogramStatistics().keySet().toArray(new String[0])
					);
					return null;
				}
			);
		}

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_SMALL)
		@DisplayName("filterGroupBy selecting one group must drop the other group's histogram")
		void shouldOnlyEmitHistogramForGroupSelectedByFilterGroupBy(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							require(
								referenceSummaryOfReferenceWithHistograms(
									REF_PARAM_VALUES, FacetStatisticsDepth.COUNTS,
									null,                                       // facetFilterBy
									filterGroupBy(entityPrimaryKeyInSet(1)),    // facetGroupFilterBy: only group 1
									null, null,                                 // facetOrderBy, facetGroupOrderBy
									null, null,                                 // entityFetch, entityGroupFetch
									histogramStatistics(10, HISTOGRAM_PRICE)
								)
							)
						),
						EntityReferenceContract.class
					);
					final ReferenceSummary referenceSummary = result.getExtraResult(ReferenceSummary.class);
					assertNotNull(referenceSummary);
					// filterGroupBy picks only group 1 — the histogram-stats path must honour it the
					// same way the facet path does: group 1 stays (with its histogram) and group 2 is gone
					final ReferenceGroupStatistics group1 = referenceSummary.getReferenceGroupStatistics(
						REF_PARAM_VALUES, 1
					);
					final ReferenceGroupStatistics group2 = referenceSummary.getReferenceGroupStatistics(
						REF_PARAM_VALUES, 2
					);
					assertNotNull(group1, "Group 1 must appear — it was selected by filterGroupBy");
					assertArrayEquals(
						new String[]{HISTOGRAM_PRICE},
						group1.getHistogramStatistics().keySet().toArray(new String[0]),
						"Selected group 1 must expose exactly the requested histogram entry"
					);
					assertNull(
						group2,
						"Group 2 must be absent — it was not selected by filterGroupBy"
					);
					return null;
				}
			);
		}

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_SMALL)
		@DisplayName("all-references fan-out filterGroupBy selecting one group must drop the other group's histogram")
		void shouldOnlyEmitHistogramForGroupSelectedByAllReferencesFilterGroupBy(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							require(
								referenceSummaryWithHistograms(
									FacetStatisticsDepth.COUNTS,
									null,                                       // facetFilterBy
									filterGroupBy(entityPrimaryKeyInSet(1)),    // facetGroupFilterBy: only group 1
									null, null,                                 // facetOrderBy, facetGroupOrderBy
									null, null,                                 // entityFetch, entityGroupFetch
									histogramStatistics(10, HISTOGRAM_PRICE)
								)
							)
						),
						EntityReferenceContract.class
					);
					final ReferenceSummary referenceSummary = result.getExtraResult(ReferenceSummary.class);
					assertNotNull(referenceSummary);
					// the all-references fan-out leaves the reference out of `referenceSummaryRequests`,
					// so group-predicate resolution falls to the `defaultRequest`-derived branch — that
					// branch must honour filterGroupBy identically: group 1 (with its histogram) survives
					// and group 2 is dropped
					final ReferenceGroupStatistics group1 = referenceSummary.getReferenceGroupStatistics(
						REF_PARAM_VALUES, 1
					);
					final ReferenceGroupStatistics group2 = referenceSummary.getReferenceGroupStatistics(
						REF_PARAM_VALUES, 2
					);
					assertNotNull(group1, "Group 1 must appear — it was selected by filterGroupBy");
					assertArrayEquals(
						new String[]{HISTOGRAM_PRICE},
						group1.getHistogramStatistics().keySet().toArray(new String[0]),
						"Selected group 1 must expose exactly the requested histogram entry"
					);
					assertNull(
						group2,
						"Group 2 must be absent — it was not selected by filterGroupBy"
					);
					return null;
				}
			);
		}

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_SMALL)
		@DisplayName("filterGroupBy matching no group must drop every group's histogram")
		void shouldEmitNoGroupHistogramWhenFilterGroupBySelectsNoGroup(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							require(
								referenceSummaryOfReferenceWithHistograms(
									REF_PARAM_VALUES, FacetStatisticsDepth.COUNTS,
									null,                                         // facetFilterBy
									filterGroupBy(entityPrimaryKeyInSet(999)),    // facetGroupFilterBy: no such group
									null, null,                                   // facetOrderBy, facetGroupOrderBy
									null, null,                                   // entityFetch, entityGroupFetch
									histogramStatistics(10, HISTOGRAM_PRICE)
								)
							)
						),
						EntityReferenceContract.class
					);
					final ReferenceSummary referenceSummary = result.getExtraResult(ReferenceSummary.class);
					assertNotNull(referenceSummary);
					// group 999 does not exist, so the predicate matches no group — every group's
					// histogram is dropped with no all-groups fallback and no exception
					final ReferenceGroupStatistics group1 = referenceSummary.getReferenceGroupStatistics(
						REF_PARAM_VALUES, 1
					);
					final ReferenceGroupStatistics group2 = referenceSummary.getReferenceGroupStatistics(
						REF_PARAM_VALUES, 2
					);
					assertNull(group1, "Group 1 must be absent — filterGroupBy matched no group");
					assertNull(group2, "Group 2 must be absent — filterGroupBy matched no group");
					return null;
				}
			);
		}

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_SMALL)
		@DisplayName("NONE depth + filterGroupBy selecting one group must drop the other group's histogram")
		void shouldOnlyEmitHistogramForGroupSelectedByFilterGroupByWithNoneDepth(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							require(
								referenceSummaryOfReferenceWithHistograms(
									REF_PARAM_VALUES, FacetStatisticsDepth.NONE,
									null,                                       // facetFilterBy
									filterGroupBy(entityPrimaryKeyInSet(1)),    // facetGroupFilterBy: only group 1
									null, null,                                 // facetOrderBy, facetGroupOrderBy
									null, null,                                 // entityFetch, entityGroupFetch
									histogramStatistics(10, HISTOGRAM_PRICE)
								)
							)
						),
						EntityReferenceContract.class
					);
					final ReferenceSummary referenceSummary = result.getExtraResult(ReferenceSummary.class);
					assertNotNull(referenceSummary);
					// NONE depth strips the reference from the statistics map before histogram injection,
					// so each surviving group is synthesised purely from histogram data — that synthesis must
					// still honour filterGroupBy: group 1 emerges (carrying its histogram) and group 2 is gone
					final ReferenceGroupStatistics group1 = referenceSummary.getReferenceGroupStatistics(
						REF_PARAM_VALUES, 1
					);
					final ReferenceGroupStatistics group2 = referenceSummary.getReferenceGroupStatistics(
						REF_PARAM_VALUES, 2
					);
					assertNotNull(group1, "Group 1 must appear as a histogram-only synthetic group");
					assertArrayEquals(
						new String[]{HISTOGRAM_PRICE},
						group1.getHistogramStatistics().keySet().toArray(new String[0]),
						"Selected group 1 must expose exactly the requested histogram entry"
					);
					assertNull(
						group2,
						"Group 2 must be absent — filtered out by filterGroupBy even in NONE depth"
					);
					return null;
				}
			);
		}
	}

	// ==========================================================================================
	// SMALL fixture — min / max anchor invariants
	// ==========================================================================================

	/**
	 * Symmetry assertions on the min / max histogram anchors. Tests here pin down invariants that
	 * any histogram implementation must uphold: `min` is always the first bucket's threshold,
	 * `max` is the inclusive upper bound of the last bucket, and `min <= max` for every group.
	 */
	@Nested
	@DisplayName("Min/max anchor invariants (small fixture)")
	class MinMaxInvariants {

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_SMALL)
		@DisplayName("should have min equal to first bucket threshold and max no smaller than last threshold")
		void shouldAnchorMinToFirstBucketAndMaxAtOrAboveLastBucket(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							require(
								referenceSummaryOfReferenceWithHistograms(
									REF_PARAM_VALUES, null, null, null,
									histogramStatistics(10, HISTOGRAM_PRICE)
								)
							)
						),
						EntityReferenceContract.class
					);
					final ReferenceSummary summary = result.getExtraResult(ReferenceSummary.class);
					assertNotNull(summary);

					for (int groupPk = 1; groupPk <= 2; groupPk++) {
						final ReferenceGroupStatistics group = summary.getReferenceGroupStatistics(
							REF_PARAM_VALUES, groupPk
						);
						assertNotNull(group, "Group " + groupPk + " must be present");
						final HistogramContract histogram = group.getHistogramStatistics(HISTOGRAM_PRICE);
						assertNotNull(histogram, "Group " + groupPk + " must carry histogram");

						final Bucket[] buckets = histogram.getBuckets();
						assertTrue(buckets.length > 0, "Histogram must have at least one bucket");
						// min must match the first bucket's threshold
						assertEquals(
							buckets[0].threshold(), histogram.getMin(),
							"min must equal first bucket threshold"
						);
						// max must be >= last bucket threshold (inclusive upper bound)
						assertTrue(
							buckets[buckets.length - 1].threshold().compareTo(histogram.getMax()) <= 0,
							"last bucket threshold must not exceed max"
						);
						// min <= max for every group
						assertTrue(
							histogram.getMin().compareTo(histogram.getMax()) <= 0,
							"min must never exceed max"
						);
					}
				}
			);
		}
	}

	// ==========================================================================================
	// Bespoke fixture — histogram-only group entity enrichment
	// ==========================================================================================

	/**
	 * Pins down the contract that — when `facetedPartially` and `bucketedPartially` are wired
	 * to mutually-exclusive expressions on the same reference — a group whose widget type
	 * routes it to the bucketed-only branch still receives a fully-fetched group entity in the
	 * synthesized {@link ReferenceGroupStatistics}, matching the request's `entityGroupFetch`
	 * shape. The histogram-only synthesis path inside `ReferenceHistogramAccumulator` used to
	 * emit a bare `EntityReference`, which made GraphQL `groupEntity { attributes { ... } }`
	 * selections explode with `ClassCastException` because `EntityReference` does not implement
	 * `AttributesContract`.
	 */
	@Nested
	@DisplayName("Histogram-only group entity enrichment (bespoke fixture)")
	class HistogramOnlyGroupEnrichment {

		private static final String DUAL_REF = "paramDualFacetHistogram";
		private static final String DUAL_HISTOGRAM = "dualHistogram";
		private static final String ATTR_INPUT_WIDGET_TYPE = "inputWidgetType";

		@Test
		@DisplayName("entityGroupFetch must enrich the group entity for INTERVAL groups (bucketed-only)")
		void shouldEnrichHistogramOnlyGroupEntityWithFetchedAttributes() {
			runWithInlineSchema(
				"referenceHistogramE2E_histogramOnlyEnrichment",
				HistogramOnlyGroupEnrichment::defineDualSchema,
				HistogramOnlyGroupEnrichment::seedIntervalGroup,
				evita -> evita.queryCatalog(
					TEST_CATALOG,
					session -> {
						final ReferenceSummary referenceSummary = runWithSummary(
							session,
							referenceSummaryOfReferenceWithHistograms(
								DUAL_REF, null, null,
								entityGroupFetch(attributeContent(ATTR_NAME)),
								histogramStatistics(10, DUAL_HISTOGRAM)
							)
						);
						assertGroupEntityEnriched(referenceSummary);
						return null;
					}
				)
			);
		}

		/**
		 * Twin of {@link #shouldEnrichHistogramOnlyGroupEntityWithFetchedAttributes} that exercises
		 * the all-references fan-out form ({@code referenceSummaryWithHistograms}) instead of the
		 * single-reference form. The all-references translator only registers a
		 * {@code defaultRequest} on the producer — it never populates the per-reference request
		 * map — so a histogram-only synthesized group must still pick up the request's
		 * {@code entityGroupFetch} shape via the default-fallback path inside
		 * {@code ReferenceSummaryProducer.resolveGroupEntityFetcher}.
		 */
		@Test
		@DisplayName("entityGroupFetch must enrich the group entity via the all-references fan-out form")
		void shouldEnrichHistogramOnlyGroupEntityViaAllReferencesFanOut() {
			runWithInlineSchema(
				"referenceHistogramE2E_histogramOnlyEnrichmentAllRefs",
				HistogramOnlyGroupEnrichment::defineDualSchema,
				HistogramOnlyGroupEnrichment::seedIntervalGroup,
				evita -> evita.queryCatalog(
					TEST_CATALOG,
					session -> {
						final ReferenceSummary referenceSummary = runWithSummary(
							session,
							referenceSummaryWithHistograms(
								null, null,
								entityGroupFetch(attributeContent(ATTR_NAME)),
								histogramStatistics(10, DUAL_HISTOGRAM)
							)
						);
						assertGroupEntityEnriched(referenceSummary);
						return null;
					}
				)
			);
		}

		/**
		 * Defines the dual facet/histogram schema with mutually-exclusive `facetedPartially`
		 * (CHECKBOX) and `bucketedPartially` (INTERVAL) clauses on the same reference. Shared
		 * by both tests so they exercise the same producer wiring with only the require shape
		 * (single-reference vs all-references) varying.
		 */
		private static void defineDualSchema(@Nonnull EvitaSessionContract session) {
			// parameter (group) carries the widget-type discriminator AND the
			// `name` attribute that both tests fetch via entityGroupFetch
			session.defineEntitySchema(ENTITY_PARAMETER)
				.withAttribute(
					ATTR_INPUT_WIDGET_TYPE, String.class,
					whichIs -> whichIs.filterable().nullable()
				)
				.withAttribute(ATTR_NAME, String.class, whichIs -> whichIs.filterable().nullable())
				.updateVia(session);

			session.defineEntitySchema(ENTITY_PARAMETER_VALUE)
				.withAttribute(
					ATTR_BASIC_UNIT_VALUE, BigDecimal.class,
					whichIs -> whichIs.filterable().indexDecimalPlaces(2).nullable()
				)
				.updateVia(session);

			// mutually-exclusive: facetedPartially → CHECKBOX, bucketedPartially → INTERVAL.
			// no group is ever both faceted and bucketed, so every histogram-bearing
			// group lands in the synthesis branch the fix targets.
			session.defineEntitySchema(ENTITY_PRODUCT)
				.withReferenceToEntity(
					DUAL_REF, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs
						.indexedForFilteringAndPartitioning()
						.indexedWithComponents(ReferenceIndexedComponents.values())
						.faceted()
						.withGroupTypeRelatedToEntity(ENTITY_PARAMETER)
						.facetedPartially(
							ExpressionFactory.parse(
								"($reference.groupEntity?.attributes['"
									+ ATTR_INPUT_WIDGET_TYPE + "'] ?? '') == 'CHECKBOX'"
							)
						)
						.bucketed(
							DUAL_HISTOGRAM,
							ExpressionFactory.parse(
								"$reference.referencedEntity?.attributes['"
									+ ATTR_BASIC_UNIT_VALUE + "']"
							)
						)
						.bucketedPartially(
							ExpressionFactory.parse(
								"($reference.groupEntity?.attributes['"
									+ ATTR_INPUT_WIDGET_TYPE + "'] ?? '') == 'INTERVAL'"
							)
						)
				)
				.updateVia(session);
		}

		/**
		 * Seeds an INTERVAL group — bucketed only, never faceted under the schema's
		 * `facetedPartially("CHECKBOX")` clause — together with two parameter values and two
		 * products so the histogram has at least one populated bucket.
		 */
		private static void seedIntervalGroup(@Nonnull EvitaSessionContract session) {
			session.createNewEntity(ENTITY_PARAMETER, 1)
				.setAttribute(ATTR_INPUT_WIDGET_TYPE, "INTERVAL")
				.setAttribute(ATTR_NAME, "Width")
				.upsertVia(session);

			session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
				.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("10"))
				.upsertVia(session);
			session.createNewEntity(ENTITY_PARAMETER_VALUE, 2)
				.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("20"))
				.upsertVia(session);

			session.createNewEntity(ENTITY_PRODUCT, 1)
				.setReference(
					DUAL_REF, 1,
					whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 1)
				)
				.upsertVia(session);
			session.createNewEntity(ENTITY_PRODUCT, 2)
				.setReference(
					DUAL_REF, 2,
					whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 1)
				)
				.upsertVia(session);
		}

		@Nonnull
		private static ReferenceSummary runWithSummary(
			@Nonnull EvitaSessionContract session,
			@Nonnull RequireConstraint summaryConstraint
		) {
			final EvitaResponse<EntityReferenceContract> result = session.query(
				query(
					collection(ENTITY_PRODUCT),
					require(summaryConstraint)
				),
				EntityReferenceContract.class
			);
			final ReferenceSummary referenceSummary = result.getExtraResult(ReferenceSummary.class);
			assertNotNull(referenceSummary, "ReferenceSummary extra result must be present");
			return referenceSummary;
		}

		private static void assertGroupEntityEnriched(@Nonnull ReferenceSummary referenceSummary) {
			final ReferenceGroupStatistics group = referenceSummary
				.getReferenceGroupStatistics(DUAL_REF, 1);
			assertNotNull(
				group,
				"INTERVAL group must surface in the summary via the histogram-only "
					+ "synthesis path"
			);

			final HistogramContract histogram = group.getHistogramStatistics(DUAL_HISTOGRAM);
			assertNotNull(histogram, "INTERVAL group must carry the bucketed histogram");
			assertTrue(
				histogram.getBuckets().length > 0,
				"Histogram must have at least one bucket"
			);

			// Before the fix: groupEntity was a bare EntityReference, so any consumer
			// reading `attributes` (most visibly the GraphQL AttributesDataFetcher)
			// blew up with ClassCastException because EntityReference is not an
			// AttributesContract. After the fix: groupEntity is the fully-fetched
			// SealedEntity carrying the attributes requested via entityGroupFetch.
			final EntityClassifier groupEntity = group.getGroupEntity();
			assertNotNull(
				groupEntity,
				"Synthesized histogram-only group must expose its parent group entity"
			);
			assertInstanceOf(
				SealedEntity.class, groupEntity,
				"Histogram-only group must be enriched to SealedEntity to honour "
					+ "entityGroupFetch — received " + groupEntity.getClass().getName()
			);
			assertEquals(
				"Width",
				((SealedEntity) groupEntity).getAttribute(ATTR_NAME),
				"Group entity must carry the `name` attribute requested via entityGroupFetch"
			);
		}
	}
}
