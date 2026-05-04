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
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.HistogramContract.Bucket;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.annotation.UseDataSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Tag;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.REFERENCE;

/**
 * Validation and edge-case coverage for `referenceSummary` / `referenceSummaryOfReference`
 * histogram constraints. Asserts that unknown / partially-defined / out-of-scope histograms abort
 * query planning with {@link EvitaInvalidUsageException} and that specialized edge-case schemas
 * (non-grouped reference, LIVE-only scope, single-bucket min==max, trailing-zero decimals) are
 * handled correctly.
 *
 * Tests that can share the {@link #REFERENCE_HISTOGRAM_SMALL} fixture do so; tests that require a
 * bespoke schema spin up a dedicated Evita instance inline — none of them would fit into the
 * shared fixture without polluting its assertions.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Reference summary histogram — validation and edge cases")
@Tag(CONTRACT)
@Tag(HISTOGRAM)
@Tag(REFERENCE)
public class ReferenceSummaryHistogramValidationTest extends AbstractReferenceSummaryHistogramFunctionalTest {

	// ==========================================================================================
	// Validation — small fixture
	// ==========================================================================================

	/**
	 * Schema variant where ENTITY_PRODUCT carries TWO references — the bucketed
	 * {@link #REF_PARAM_VALUES} and an additional plain {@link #REF_CATEGORIES} that has no
	 * histogram index defined. Used by the validation test asserting strict all-references
	 * dispatch refuses to proceed when any reference in the schema lacks the requested histogram.
	 */
	private static void defineSchemaWithExtraPlainReference(@Nonnull EvitaSessionContract session) {
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
					.bucketed(
						HISTOGRAM_PRICE,
						ExpressionFactory.parse(
							"$reference.referencedEntity?.attributes['basicUnitValue']"
						)
					)
			)
			.withReferenceToEntity(
				REF_CATEGORIES, ENTITY_PARAMETER, Cardinality.ZERO_OR_MORE,
				ReferenceSchemaEditor::indexedForFilteringAndPartitioning
			)
			.updateVia(session);
	}

	// ==========================================================================================
	// Validation — multiple histograms on the same reference
	// ==========================================================================================

	/**
	 * Schema variant where the reference is indexed in both LIVE and ARCHIVED scopes but the
	 * histogram is declared only in LIVE. Used by the validation test asserting a query in the
	 * ARCHIVED scope aborts fast with a "histogram not defined on reference" error.
	 */
	private static void defineSchemaWithHistogramOnlyInLiveScope(
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
					// index and facet in both scopes so the ARCHIVED query reaches histogram
					// resolution — without this, the facet-summary pre-check fires first
					.indexedForFilteringAndPartitioningInScope(Scope.values())
					.indexedWithComponents(ReferenceIndexedComponents.values())
					.facetedInScope(Scope.values())
					.withGroupTypeRelatedToEntity(ENTITY_PARAMETER)
					// histogram is declared ONLY in LIVE — ARCHIVED query must fail fast
					.bucketedInScope(
						Scope.LIVE,
						HISTOGRAM_PRICE,
						ExpressionFactory.parse(
							"$reference.referencedEntity?.attributes['basicUnitValue']"
						)
					)
			)
			.updateVia(session);
	}

	// ==========================================================================================
	// Edge cases — bespoke inline schemas
	// ==========================================================================================

	/**
	 * Schema variant where ENTITY_PRODUCT carries a **non-grouped** reference to parameter
	 * values together with a histogram index. Non-grouped references trigger the
	 * `collectNonGroupedPending` path in the accumulator and the synthetic DTO path in
	 * `mergeWithExisting` when there are no facet statistics to merge.
	 */
	private static void defineSchemaWithNonGroupedReferenceAndHistogram(
		@Nonnull EvitaSessionContract session
	) {
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
					// `.faceted()` is required so `referenceSummaryOfReference` accepts the reference;
					// no `.withGroupTypeRelatedToEntity(...)` keeps the reference non-grouped.
					.faceted()
					.bucketed(
						HISTOGRAM_PRICE,
						ExpressionFactory.parse(
							"$reference.referencedEntity?.attributes['basicUnitValue']"
						)
					)
			)
			.updateVia(session);
	}

	// ==========================================================================================
	// Validation — large fixture
	// ==========================================================================================

	/**
	 * Seeds data for the non-grouped reference variant: a small set of parameter values with
	 * varied `basicUnitValue`s plus a handful of products each referencing one or more values.
	 */
	private static void seedNonGroupedData(@Nonnull EvitaSessionContract session) {
		session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
			.setAttribute(ATTR_NAME, "10cm")
			.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("10"))
			.upsertVia(session);
		session.createNewEntity(ENTITY_PARAMETER_VALUE, 2)
			.setAttribute(ATTR_NAME, "20cm")
			.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("20"))
			.upsertVia(session);
		session.createNewEntity(ENTITY_PARAMETER_VALUE, 3)
			.setAttribute(ATTR_NAME, "30cm")
			.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("30"))
			.upsertVia(session);

		session.createNewEntity(ENTITY_PRODUCT, 1)
			.setReference(REF_PARAM_VALUES, 1)
			.upsertVia(session);
		session.createNewEntity(ENTITY_PRODUCT, 2)
			.setReference(REF_PARAM_VALUES, 2)
			.upsertVia(session);
		session.createNewEntity(ENTITY_PRODUCT, 3)
			.setReference(REF_PARAM_VALUES, 3)
			.upsertVia(session);
	}

	// ==========================================================================================
	// Bespoke-schema helpers used by the inline tests above
	// ==========================================================================================

	@Nested
	@DisplayName("Validation — query planning guards (small fixture + bespoke schemas)")
	class ValidationSmall {

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_SMALL)
		@DisplayName("should throw when histogram index name is unknown")
		void shouldThrowForUnknownHistogramName(@Nonnull Evita evita) {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> evita.queryCatalog(
					TEST_CATALOG,
					(Consumer<EvitaSessionContract>) session -> session.query(
						query(
							collection(ENTITY_PRODUCT),
							require(
								referenceSummaryOfReferenceWithHistograms(
									REF_PARAM_VALUES, null, null, null,
									histogramStatistics(10, "nonExistentHistogram")
								)
							)
						),
						EntityReferenceContract.class
					)
				),
				"Unknown histogram index name must abort query planning"
			);
		}

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_SMALL)
		@DisplayName("should throw when any histogram name is missing in strict mode")
		void shouldThrowWhenAnyHistogramNameMissingInStrictMode(@Nonnull Evita evita) {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> evita.queryCatalog(
					TEST_CATALOG,
					(Consumer<EvitaSessionContract>) session -> session.query(
						query(
							collection(ENTITY_PRODUCT),
							require(
								referenceSummaryOfReferenceWithHistograms(
									REF_PARAM_VALUES, null, null, null,
									histogramStatistics(10, HISTOGRAM_PRICE, "missingHistogram")
								)
							)
						),
						EntityReferenceContract.class
					)
				),
				"Strict per-reference dispatch must throw when any requested histogram is undefined"
			);
		}

		@Test
		@DisplayName("should return null when no index names are supplied (constraint not applicable)")
		void shouldReturnNullWhenNoIndexNames() {
			// QueryConstraints.histogramStatistics returns null when indexNames is empty — the query is
			// still valid but carries no histogramStatistics child, so no histograms are computed.
			assertNull(histogramStatistics(10, new String[0]));
		}

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_SMALL)
		@DisplayName("should throw when all-references form requests an unknown histogram name")
		void shouldThrowForUnknownHistogramNameInAllReferencesForm(@Nonnull Evita evita) {
			// The all-references `referenceSummary` form must fail fast when the user requests a
			// histogram name that is not defined on any reference in the entity schema — the
			// constraint would otherwise silently produce no histograms, masking the typo.
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> evita.queryCatalog(
					TEST_CATALOG,
					(Consumer<EvitaSessionContract>) session -> session.query(
						query(
							collection(ENTITY_PRODUCT),
							require(
								referenceSummaryWithHistograms(
									null, null, null,
									histogramStatistics(10, "nonExistentHistogram")
								)
							)
						),
						EntityReferenceContract.class
					)
				),
				"Unknown histogram name in all-references form must abort query planning"
			);
		}

		@Test
		@DisplayName("should throw when histogram is defined only in LIVE and query targets ARCHIVED scope")
		void shouldThrowWhenHistogramNotDefinedInQueriedScope() {
			// Bucketed definitions are per-scope. A schema that declares the histogram only in
			// LIVE must surface a clear error when the query targets ARCHIVED — the resolution
			// must fail fast with a reference/scope-specific message rather than silently
			// producing an empty histogram.
			runWithInlineSchema(
				"referenceSummaryHistogramValidation_liveOnly",
				ReferenceSummaryHistogramValidationTest::defineSchemaWithHistogramOnlyInLiveScope,
				null,
				evita -> {
					final EvitaInvalidUsageException error = assertThrows(
						EvitaInvalidUsageException.class,
						() -> evita.queryCatalog(
							TEST_CATALOG,
							(Consumer<EvitaSessionContract>) session -> session.query(
								query(
									collection(ENTITY_PRODUCT),
									filterBy(scope(Scope.ARCHIVED)),
									require(
										referenceSummaryOfReferenceWithHistograms(
											REF_PARAM_VALUES, null, null, null,
											histogramStatistics(10, HISTOGRAM_PRICE)
										)
									)
								),
								EntityReferenceContract.class
							)
						),
						"ARCHIVED-scope query must abort when histogram is undeclared in that scope"
					);
					assertTrue(
						error.getMessage().contains("is not defined on reference"),
						"Error must surface the missing-histogram-in-scope contract, was: " + error.getMessage()
					);
					assertTrue(
						error.getMessage().contains("ARCHIVED"),
						"Error must name the offending scope, was: " + error.getMessage()
					);
				}
			);
		}

		@Test
		@DisplayName("should throw when a reference in the schema does not define the requested histogram (all-references form)")
		void shouldThrowWhenAnyReferenceInSchemaLacksRequestedHistogram() {
			// Strict semantics for the all-references form: every reference in the entity schema
			// must define every requested histogram in every active scope. Here REF_CATEGORIES
			// exists without any histogram, so the dispatch must refuse rather than silently skip
			// that reference and emit histograms for REF_PARAM_VALUES only.
			runWithInlineSchema(
				"referenceSummaryHistogramValidation_partialCoverage",
				ReferenceSummaryHistogramValidationTest::defineSchemaWithExtraPlainReference,
				null,
				evita -> assertThrows(
					EvitaInvalidUsageException.class,
					() -> evita.queryCatalog(
						TEST_CATALOG,
						(Consumer<EvitaSessionContract>) session -> session.query(
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
						)
					),
					"All-references form must throw when any reference lacks the requested histogram"
				)
			);
		}
	}

	/**
	 * Scenarios involving multiple histograms declared on the same reference. The engine must
	 * accept a request that asks for several histogram names in a single `histogramStatistics`
	 * call and must fail fast when any of the names is undefined on the schema.
	 */
	@Nested
	@DisplayName("Multiple histograms on same reference (small fixture)")
	class MultiHistogramOnSameReference {

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_SMALL)
		@DisplayName("should throw when mixing an existing and a missing histogram name")
		void shouldThrowWhenMixingExistingAndMissingHistogram(@Nonnull Evita evita) {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> evita.queryCatalog(
					TEST_CATALOG,
					(Consumer<EvitaSessionContract>) session -> session.query(
						query(
							collection(ENTITY_PRODUCT),
							require(
								referenceSummaryOfReferenceWithHistograms(
									REF_PARAM_VALUES, null, null, null,
									histogramStatistics(10, HISTOGRAM_PRICE, "anotherUnknown")
								)
							)
						),
						EntityReferenceContract.class
					)
				),
				"Requesting any undefined histogram must abort query planning"
			);
		}
	}

	/**
	 * Pins behaviour that is hard to exercise with the default datasets but easy to trigger with
	 * a narrowed query or a purpose-built schema variant:
	 *
	 * - one-bucket degenerate case where the histogram's `min` equals `max`;
	 * - non-grouped reference histogram — synthesizes a DTO via `mergeWithExisting` with
	 * `groupEntity == null`;
	 * - trailing-zero BigDecimal boundaries against an `indexDecimalPlaces > 0` attribute to pin
	 * the `FilterIndex`-side normalization contract.
	 */
	@Nested
	@DisplayName("Edge cases — degenerate buckets, non-grouped, decimal-places")
	class EdgeCases {

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_SMALL)
		@DisplayName("should populate min==max and boundary entity for a single-bucket histogram")
		void shouldHandleSingleBucketMinEqualsMaxCase(@Nonnull Evita evita) {
			// Narrow the query to product P5 only — its single reference (PV #4, basicUnitValue=100)
			// is the only contributor to group 2, so the histogram for group 2 has exactly one
			// distinct value. This exercises the `maxValue.compareTo(minValue) == 0` shortcut in
			// ReferenceHistogramAccumulator#resolveBoundaryPksFromReferencedEntity (line 486-488),
			// which reuses `minPk` as `maxPk` to avoid a redundant lookup.
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(entityPrimaryKeyInSet(5)),
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

					final ReferenceGroupStatistics group2 = referenceSummary
						.getReferenceGroupStatistics(REF_PARAM_VALUES, 2);
					assertNotNull(group2, "Group 2 (Weight) must be present for single-product filter");
					final HistogramContract histogram = group2.getHistogramStatistics(HISTOGRAM_PRICE);
					assertNotNull(histogram, "Group 2 must carry the priceBucket histogram");

					// min == max (single value 100.00) is the invariant being pinned
					assertEquals(
						new BigDecimal("100.00"), histogram.getMin(),
						"Histogram min must be 100 (single-value group)"
					);
					assertEquals(
						new BigDecimal("100.00"), histogram.getMax(),
						"Histogram max must also be 100 (single-value group)"
					);
					assertEquals(
						0, histogram.getMin().compareTo(histogram.getMax()),
						"min must equal max for single-value histogram"
					);

					// Both boundary entities must resolve to PV #4 because that's the only candidate
					final Optional<io.evitadb.api.requestResponse.data.SealedEntity> minEntity = histogram.getMinReferencedEntity();
					final Optional<io.evitadb.api.requestResponse.data.SealedEntity> maxEntity = histogram.getMaxReferencedEntity();
					assertTrue(minEntity.isPresent(), "Min boundary entity must resolve");
					assertTrue(maxEntity.isPresent(), "Max boundary entity must resolve");
					assertEquals(
						4, minEntity.get().getPrimaryKey(),
						"Min entity must be PV #4 (the only carrier of value 100 in group 2)"
					);
					assertEquals(
						4, maxEntity.get().getPrimaryKey(),
						"Max entity must be the same PV #4 (max==min shortcut)"
					);
					return null;
				}
			);
		}

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_SMALL)
		@DisplayName("should honor trailing-zero BigDecimal boundary values with indexDecimalPlaces > 0")
		void shouldHonorTrailingZeroBigDecimalBoundaries(@Nonnull Evita evita) {
			// ATTR_BASIC_UNIT_VALUE is defined with `indexDecimalPlaces(2)`. The
			// histogram source values are stored after the FilterIndex normalization via
			// NumberUtils.normalizeIfBigDecimal. Passing `10.00` / `30.00` (trailing zeros) into
			// the userFilter range must still match the buckets — coerceToAttributeType must NOT
			// call stripTrailingZeros itself (the rationale JavaDoc on the helper calls this out).
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(
								userFilter(
									histogramHaving(
										REF_PARAM_VALUES, HISTOGRAM_PRICE,
										new BigDecimal("10.00"),
										new BigDecimal("30.00")
									)
								)
							),
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

					final ReferenceGroupStatistics group1 = referenceSummary
						.getReferenceGroupStatistics(REF_PARAM_VALUES, 1);
					assertNotNull(group1);
					final HistogramContract histogram = group1.getHistogramStatistics(HISTOGRAM_PRICE);
					assertNotNull(histogram);

					// At least one bucket must have `requested=true` because the range [10, 30]
					// covers the entire group 1 value span {10, 20, 30}. If coerceToAttributeType
					// stripped trailing zeros (producing `1E+1` vs canonical `10`), the requested
					// predicate would never match and all buckets would be `requested=false`.
					boolean anyRequested = false;
					for (final Bucket bucket : histogram.getBuckets()) {
						if (bucket.requested()) {
							anyRequested = true;
							break;
						}
					}
					assertTrue(
						anyRequested,
						"At least one bucket must flip to requested=true for trailing-zero input "
							+ "[10.00, 30.00] on an indexDecimalPlaces(2) attribute"
					);
					return null;
				}
			);
		}

		@Test
		@DisplayName("should populate histogram for non-grouped reference via synthetic DTO path")
		void shouldPopulateHistogramForNonGroupedReference() {
			// Uses a schema variant where the parameter-value reference is NOT grouped — the
			// accumulator's `mergeWithExisting` path must synthesize a DTO with `groupEntity == null`
			// when the reference has histogram data but no facets carry it.
			runWithInlineSchema(
				"referenceSummaryHistogramValidation_nonGrouped",
				ReferenceSummaryHistogramValidationTest::defineSchemaWithNonGroupedReferenceAndHistogram,
				ReferenceSummaryHistogramValidationTest::seedNonGroupedData,
				evita -> evita.queryCatalog(
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

						// Non-grouped references surface as a single `ReferenceGroupStatistics` whose
						// `groupEntity == null`. The histogram must be attached to that DTO.
						ReferenceGroupStatistics nonGrouped = null;
						for (final ReferenceGroupStatistics group : referenceSummary.getReferenceStatistics()) {
							if (REF_PARAM_VALUES.equals(group.getReferenceName())
								&& group.getGroupEntity() == null
							) {
								nonGrouped = group;
								break;
							}
						}
						assertNotNull(
							nonGrouped,
							"Non-grouped reference must produce exactly one group-statistics entry "
								+ "with groupEntity == null"
						);
						assertNotNull(
							nonGrouped.getHistogramStatistics(HISTOGRAM_PRICE),
							"Histogram must be attached to the non-grouped (groupEntity == null) DTO"
						);
					}
				)
			);
		}
	}

	@Nested
	@DisplayName("Validation (large fixture)")
	class ValidationLarge {

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_LARGE)
		@DisplayName("should throw when referenceSummaryOfReference requests an unknown histogram name")
		void shouldThrowForUnknownHistogramNameInReferenceSummaryOfReference(@Nonnull Evita evita) {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> evita.queryCatalog(
					TEST_CATALOG,
					(Consumer<EvitaSessionContract>) session -> session.query(
						query(
							collection(ENTITY_PRODUCT),
							require(
								referenceSummaryOfReferenceWithHistograms(
									REF_PARAM_VALUES, null, null, null,
									histogramStatistics(10, "nonExistent")
								)
							)
						),
						EntityReferenceContract.class
					)
				)
			);
		}

		@Test
		@UseDataSet(REFERENCE_HISTOGRAM_LARGE)
		@DisplayName("should throw when all-references fan-out receives an unknown histogram name (strict per reference)")
		void shouldThrowForUnknownHistogramNameInAllReferencesFanOut(@Nonnull Evita evita) {
			// `ReferenceSummaryTranslator.dispatchHistogramToMatchingReferences` implements strict
			// fan-out: each reference is dispatched to `ReferenceHistogramStatisticsTranslator`, which
			// throws on the first reference that doesn't define the histogram in every active scope.
			// This test pins the as-implemented behavior; if the engine is later relaxed to skip
			// references silently, flip this assertion to assertDoesNotThrow.
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> evita.queryCatalog(
					TEST_CATALOG,
					(Consumer<EvitaSessionContract>) session -> session.query(
						query(
							collection(ENTITY_PRODUCT),
							require(
								referenceSummaryWithHistograms(
									null, null, null,
									histogramStatistics(10, "nonExistent")
								)
							)
						),
						EntityReferenceContract.class
					)
				),
				"All-references fan-out is strict — an unknown histogram name must raise immediately"
			);
		}
	}
}
