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
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.HistogramContract.Bucket;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.core.Evita;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.test.annotation.UseDataSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.function.Consumer;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.and;
import static io.evitadb.api.query.QueryConstraints.attributeBetween;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityHaving;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.facetHaving;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.histogramStatistics;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.api.query.QueryConstraints.referenceSummaryOfReferenceWithHistograms;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.userFilter;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral coverage of the {@code userFilter → referenceHaving → [entityHaving →] attributeBetween}
 * walker that flips per-bucket `requested` flags on histograms returned by
 * {@code referenceSummary` / `referenceSummaryOfReference`. Groups the positive paths
 * (reference-attribute and referenced-entity-attribute ranges, open-ended bounds) with negative
 * paths that must not flip any bucket (mismatched attribute, mismatched reference, multiple
 * independent matches, coexistence with `facetHaving`).
 *
 * All but one test share the {@link #REFERENCE_HISTOGRAM_SMALL} fixture. The
 * `shouldNotFlipRequestedWhenReferenceNameDoesNotMatch` test requires a second plain reference
 * that the small fixture does not carry — it uses a bespoke schema installed into a dedicated
 * Evita instance created inline.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Reference summary histogram — userFilter requested-flag behavior")
public class ReferenceSummaryHistogramRequestedFlagTest extends AbstractReferenceSummaryHistogramFunctionalTest {

	// ==========================================================================================
	// baseline: no userFilter at all — flag must never flip
	// ==========================================================================================

	@Nested
	@DisplayName("Requested flag — no userFilter present")
	class RequestedFlag {

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_SMALL)
		@DisplayName("should leave all `requested` false when userFilter carries no matching attributeBetween")
		void shouldLeaveBucketsRequestedFalseWhenUserFilterDoesNotMatch(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				(Consumer<EvitaSessionContract>) session -> {
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
						assertFalse(
							bucket.requested(),
							"Without userFilter match every bucket must have requested=false"
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
	 * {@code userFilter(referenceHaving(refName, [entityHaving(]attributeBetween(lo, hi){)}))}
	 * subtree flips the per-bucket {@code requested} flag for buckets whose threshold lies in
	 * `[lo, hi]`. Exercises both histogram source types (REFERENCE_ATTRIBUTE and
	 * REFERENCED_ENTITY_ATTRIBUTE) and open-ended ranges.
	 */
	@Nested
	@DisplayName("Requested flag from userFilter → referenceHaving")
	class RequestedFlagFromUserFilterReferenceHaving {

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_SMALL)
		@DisplayName("should mark requested buckets for reference-attribute range")
		void shouldMarkRequestedBucketsForReferenceAttributeRange(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				(Consumer<EvitaSessionContract>) session -> {
					// baseline: no userFilter — every bucket must be requested=false, capture occurrences
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
						assertFalse(b.requested(), "Baseline buckets must all be requested=false");
					}

					// requested range [10, 50] on the reference-level attribute
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(
								userFilter(
									referenceHaving(
										REF_PARAM_VALUES,
										attributeBetween(ATTR_MARKET_SHARE, 10, 50)
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
				(Consumer<EvitaSessionContract>) session -> {
					// requested range [10, 20] on the REFERENCED_ENTITY_ATTRIBUTE `basicUnitValue`
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(
								userFilter(
									referenceHaving(
										REF_PARAM_VALUES,
										entityHaving(
											attributeBetween(ATTR_BASIC_UNIT_VALUE, 10, 20)
										)
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
		@DisplayName("should mark requested buckets for open-ended range (lower bound only)")
		void shouldMarkRequestedBucketsForOpenEndedRange(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				(Consumer<EvitaSessionContract>) session -> {
					// open-ended requested range [50, +∞) on reference-level `marketShare`
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(
								userFilter(
									referenceHaving(
										REF_PARAM_VALUES,
										attributeBetween(ATTR_MARKET_SHARE, 50, null)
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
	 * Negative / cross-scenario coverage for `userFilter → referenceHaving` behaviour: mismatched
	 * reference names, mismatched attribute names, duplicate ranges on the same (reference, histogram)
	 * pair, and coexistence with a `facetHaving` in the same `userFilter`.
	 */
	@Nested
	@DisplayName("Negative paths for userFilter → referenceHaving")
	class NegativePathsForUserFilterReferenceHaving {

		private static final String DIR_NEG = "referenceSummaryHistogramRequestedFlag_negRefName";
		private static final String DIR_NEG_EXPORT = "referenceSummaryHistogramRequestedFlag_negRefName_export";

		@Test
		@DisplayName("should not flip requested when reference name does not match")
		void shouldNotFlipRequestedWhenReferenceNameDoesNotMatch() {
			// This test needs a bespoke schema with a second plain reference `categories` so the
			// referenceHaving("otherRef", ...) subtree is a valid (non-matching) constraint rather
			// than a parse error. The small dataset does not carry that reference, so we spin up a
			// dedicated Evita instance inline.
			cleanTestSubDirectoryWithRethrow(DIR_NEG);
			cleanTestSubDirectoryWithRethrow(DIR_NEG_EXPORT);
			final Evita evita = new Evita(
				EvitaConfiguration.builder()
					.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build())
					.storage(StorageOptions.builder()
						.storageDirectory(getTestDirectory().resolve(DIR_NEG))
						.build())
					.export(FileSystemExportOptions.builder()
						.directory(getTestDirectory().resolve(DIR_NEG_EXPORT))
						.build())
					.build()
			);
			try {
				evita.defineCatalog(TEST_CATALOG);
				evita.updateCatalog(TEST_CATALOG, session -> {
					defineSchemaWithExtraPlainReferenceAndMarketShare(session);
					seedSmallData(session);
				});
				evita.queryCatalog(
					TEST_CATALOG,
					(Consumer<EvitaSessionContract>) session -> {
						final EvitaResponse<EntityReferenceContract> result = session.query(
							query(
								collection(ENTITY_PRODUCT),
								filterBy(
									userFilter(
										referenceHaving(
											REF_CATEGORIES,
											attributeBetween(ATTR_MARKET_SHARE, 10, 50)
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
						for (final Bucket bucket : histogram.getBuckets()) {
							assertFalse(bucket.requested(),
								"referenceHaving on a different reference must not flip any bucket");
						}
					}
				);
			} finally {
				evita.close();
				cleanTestSubDirectoryWithRethrow(DIR_NEG);
				cleanTestSubDirectoryWithRethrow(DIR_NEG_EXPORT);
			}
		}

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_SMALL)
		@DisplayName("should not flip requested when attribute name does not match the histogram source")
		void shouldNotFlipRequestedWhenAttributeNameDoesNotMatch(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				(Consumer<EvitaSessionContract>) session -> {
					// Asking for histogram `priceBucket` (sources `basicUnitValue`) while
					// attributeBetween targets `marketShare` — no flip expected.
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(
								userFilter(
									referenceHaving(
										REF_PARAM_VALUES,
										attributeBetween(ATTR_MARKET_SHARE, 10, 50)
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
						assertFalse(bucket.requested(),
							"attributeBetween on non-source attribute must not flip any bucket");
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
										referenceHaving(
											REF_PARAM_VALUES,
											attributeBetween(ATTR_MARKET_SHARE, 10, 50)
										),
										referenceHaving(
											REF_PARAM_VALUES,
											attributeBetween(ATTR_MARKET_SHARE, 60, 90)
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
				"Multiple `attributeBetween` subtrees targeting the same (reference, histogram) must throw"
			);
		}

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_SMALL)
		@DisplayName("should coexist with facetHaving in the same userFilter")
		void shouldCoexistWithFacetHavingInSameUserFilter(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				(Consumer<EvitaSessionContract>) session -> {
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
										referenceHaving(
											REF_PARAM_VALUES,
											attributeBetween(ATTR_MARKET_SHARE, 10, 50)
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
						"Query must return entities matching both facetHaving and referenceHaving");
				}
			);
		}
	}

	// ==========================================================================================
	// bespoke-schema helper used by `shouldNotFlipRequestedWhenReferenceNameDoesNotMatch`
	// ==========================================================================================

	/**
	 * Variant of {@link AbstractReferenceSummaryHistogramFunctionalTest#defineSmallSchema} that
	 * adds an extra plain reference {@link #REF_CATEGORIES} in addition to the marketShare-enabled
	 * {@link #REF_PARAM_VALUES}. Used by the negative test that asserts a `referenceHaving` on a
	 * different reference name does not flip the histogram `requested` flag — the "otherRef" must
	 * actually exist in the schema for the query DSL to accept the constraint.
	 */
	private static void defineSchemaWithExtraPlainReferenceAndMarketShare(
		@Nonnull EvitaSessionContract session
	) {
		session.defineEntitySchema(ENTITY_PARAMETER)
			.withAttribute(ATTR_NAME, String.class, whichIs -> whichIs.filterable().nullable())
			.updateVia(session);

		session.defineEntitySchema(ENTITY_PARAMETER_VALUE)
			.withAttribute(ATTR_NAME, String.class, whichIs -> whichIs.filterable().nullable())
			.withAttribute(
				ATTR_BASIC_UNIT_VALUE, BigDecimal.class,
				whichIs -> whichIs.filterable().indexDecimalPlaces(2).nullable()
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
					.withAttribute(
						ATTR_MARKET_SHARE, BigDecimal.class,
						thatIs -> thatIs.filterable().indexDecimalPlaces(2).nullable()
					)
					.bucketed(
						HISTOGRAM_PRICE,
						ExpressionFactory.parse(
							"$reference.referencedEntity?.attributes['basicUnitValue']"
						)
					)
					.bucketed(
						HISTOGRAM_MARKET_SHARE,
						ExpressionFactory.parse(
							"$reference.attributes['" + ATTR_MARKET_SHARE + "']"
						)
					)
			)
			.withReferenceToEntity(
				REF_CATEGORIES, ENTITY_PARAMETER, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					// reuse the same attribute name so the negative test exercises a real
					// `referenceHaving("otherRef", attributeBetween("marketShare", ...))` shape
					// rather than hitting a schema-lookup error.
					.withAttribute(
						ATTR_MARKET_SHARE, BigDecimal.class,
						thatIs -> thatIs.filterable().indexDecimalPlaces(2).nullable()
					)
			)
			.updateVia(session);
	}
}
