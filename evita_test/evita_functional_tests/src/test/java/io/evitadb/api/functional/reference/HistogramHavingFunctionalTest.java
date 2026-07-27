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

package io.evitadb.api.functional.reference;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.query.filter.HistogramHaving;
import io.evitadb.api.query.filter.UserFilter;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.AttributeHistogram;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.HistogramContract.Bucket;
import io.evitadb.api.requestResponse.extraResult.PriceHistogram;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.FacetStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.RequestImpact;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor;
import io.evitadb.core.Evita;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.EvitaTestSupport.TestPaths;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.function.Consumer;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeBetween;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.attributeHistogram;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityHaving;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.facetHaving;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.groupHaving;
import static io.evitadb.api.query.QueryConstraints.histogramHaving;
import static io.evitadb.api.query.QueryConstraints.histogramStatistics;
import static io.evitadb.api.query.QueryConstraints.priceBetween;
import static io.evitadb.api.query.QueryConstraints.priceHistogram;
import static io.evitadb.api.query.QueryConstraints.priceInCurrency;
import static io.evitadb.api.query.QueryConstraints.priceInPriceLists;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.api.query.QueryConstraints.referenceSummary;
import static io.evitadb.api.query.QueryConstraints.referenceSummaryOfReferenceWithHistograms;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.userFilter;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.HISTOGRAM;

/**
 * End-to-end regression suite for the cross-cutting `histogramHaving` behaviour. The fundamental
 * invariant every test here guards is that a slider moved by the user must not contract its own
 * `[min, max]` span — and must still honour the other two cross-influencing group selections
 * (attribute-family histograms, facet impact, price histogram).
 *
 * The suite hosts a hand-crafted fixture rather than reusing `AbstractReferenceSummaryHistogram
 * FunctionalTest` because the "no self-contraction" invariants require a per-group value
 * distribution that cannot be expressed from the existing generator: group `height` owns the low
 * end of `basicUnitValue` and group `weight` owns the high end, so moving one slider into its
 * own band verifiably leaves the sibling's catalog-wide span untouched.
 *
 * Each `@Nested` class covers one scenario: two sliders on the same reference, attributeHistogram
 * sibling interactions, price-histogram self-contraction, facet impact, cross-group mutual
 * visibility, non-range userFilter children, referenceHaving rejection, and descriptor errors.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ExtendWith(EvitaParameterResolver.class)
@DisplayName("histogramHaving — cross-cutting regression suite")
@Slf4j
@Tag(CONTRACT)
@Tag(REFERENCE)
@Tag(HISTOGRAM)
public class HistogramHavingFunctionalTest implements EvitaTestSupport {

	/** Shared read-only fixture that seeds the cross-cutting schema once per run. */
	public static final String HISTOGRAM_HAVING_DATA_SET = "histogramHavingDataSet";

	// ---------------------------------------------------------------------
	// entity / reference / attribute / histogram names
	// ---------------------------------------------------------------------

	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_PARAMETER = "parameter";
	private static final String ENTITY_PARAMETER_VALUE = "parameterValue";
	private static final String ENTITY_BRAND = "brand";

	private static final String REF_PARAM_VALUES = "parameterValues";
	private static final String REF_BRAND = "brand";

	private static final String ATTR_CODE = "code";
	private static final String ATTR_IN_STOCK = "inStock";
	private static final String ATTR_BASIC_UNIT_VALUE = "basicUnitValue";

	/**
	 * Direct numeric attributes on `product` used by the attributeHistogram sibling scenarios to
	 * drive a real `attributeHistogram` over two siblings inside `userFilter`. Catalog-wide spans
	 * are `dimensionA ∈ [10, 80]` and `dimensionB ∈ [200, 330]`; per-product values are arranged
	 * so a slider on either attribute narrows to the same subset `{1, 2, 3}` — letting a single
	 * AND assert the result count and still discriminate correct group-wide exclusion (both spans
	 * stay catalog-wide) from the buggy per-attribute exclusion (sibling A would contract B and
	 * vice versa).
	 */
	private static final String ATTR_DIMENSION_A = "dimensionA";
	private static final String ATTR_DIMENSION_B = "dimensionB";

	/** `dimensionA` values per product PK 1..8 (parallel to product seed loop). */
	private static final int[] PRODUCT_DIMENSION_A = {10, 20, 30, 40, 50, 60, 70, 80};

	/** `dimensionB` values per product PK 1..8 (parallel to product seed loop). */
	private static final int[] PRODUCT_DIMENSION_B = {200, 210, 240, 270, 290, 300, 320, 330};

	private static final String HISTOGRAM_PARAM_VALUES = "basicUnitValueBucket";

	/** Group PK / code for the "height" parameter (low-end values 10..120). */
	private static final int GROUP_HEIGHT_PK = 1;
	private static final String GROUP_HEIGHT_CODE = "height";

	/** Group PK / code for the "weight" parameter (high-end values 130..260). */
	private static final int GROUP_WEIGHT_PK = 2;
	private static final String GROUP_WEIGHT_CODE = "weight";

	// ---------------------------------------------------------------------
	// parameter-value catalog layout — intentionally disjoint ranges per group
	// ---------------------------------------------------------------------

	/** Primary keys of parameter values assigned to group `height`. Values: 10, 50, 100, 120. */
	private static final int[] HEIGHT_PV_PKS = {11, 12, 13, 14};

	/** Primary keys of parameter values assigned to group `weight`. Values: 130, 170, 210, 260. */
	private static final int[] WEIGHT_PV_PKS = {21, 22, 23, 24};

	/** `basicUnitValue` values for height PVs (parallel to `HEIGHT_PV_PKS`). */
	private static final int[] HEIGHT_VALUES = {10, 50, 100, 120};

	/** `basicUnitValue` values for weight PVs (parallel to `WEIGHT_PV_PKS`). */
	private static final int[] WEIGHT_VALUES = {130, 170, 210, 260};

	// ---------------------------------------------------------------------
	// brand facet layout
	// ---------------------------------------------------------------------

	private static final int BRAND_A_PK = 101;
	private static final int BRAND_B_PK = 102;
	private static final int BRAND_C_PK = 103;

	/** Price list / currency used by product prices. */
	private static final String PRICE_LIST_BASIC = "basic";
	private static final Currency CURRENCY_EUR = Currency.getInstance("EUR");

	/**
	 * Defines the cross-cutting schema: `parameter` (group) + `parameterValue` + `brand` + `product`.
	 * The `parameterValues` reference is faceted, grouped, and hosts the `basicUnitValueBucket`
	 * histogram sourced from the referenced entity's `basicUnitValue` attribute (attribute-family
	 * carrier). The `brand` reference is faceted and ungrouped (facet carrier). Product carries a
	 * `priceBetween`-compatible price stream (price-range carrier) plus an `inStock` boolean
	 * filterable attribute used by the non-range-child scenario.
	 */
	private static void defineSchema(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_PARAMETER)
			.withAttribute(
				ATTR_CODE, String.class,
				AttributeSchemaEditor::unique
			)
			.updateVia(session);

		// parameterValue carries a `parameter` reference so each PV is linked to its group entity;
		// the schema-level group type on the `parameterValues` reference (configured below on
		// PRODUCT) is what `groupHaving(...)` filters against — this back-reference is the natural
		// data model, not a workaround.
		session.defineEntitySchema(ENTITY_PARAMETER_VALUE)
			.withAttribute(
				ATTR_CODE, String.class,
				AttributeSchemaEditor::unique
			)
			.withAttribute(
				ATTR_BASIC_UNIT_VALUE, BigDecimal.class,
				whichIs -> whichIs.filterable().indexDecimalPlaces(2).nullable()
			)
			.withReferenceToEntity(
				ENTITY_PARAMETER, ENTITY_PARAMETER, Cardinality.EXACTLY_ONE,
				ReferenceSchemaEditor::indexedForFilteringAndPartitioning
			)
			.updateVia(session);

		session.defineEntitySchema(ENTITY_BRAND)
			.withAttribute(
				ATTR_CODE, String.class,
				AttributeSchemaEditor::unique
			)
			.updateVia(session);

		session.defineEntitySchema(ENTITY_PRODUCT)
			.withAttribute(
				ATTR_IN_STOCK, Boolean.class,
				whichIs -> whichIs.filterable().nullable()
			)
			.withAttribute(
				ATTR_DIMENSION_A, Integer.class,
				AttributeSchemaEditor::filterable
			)
			.withAttribute(
				ATTR_DIMENSION_B, Integer.class,
				AttributeSchemaEditor::filterable
			)
			.withPrice()
			.withReferenceToEntity(
				REF_PARAM_VALUES, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.indexedWithComponents(ReferenceIndexedComponents.values())
					.faceted()
					.withGroupTypeRelatedToEntity(ENTITY_PARAMETER)
					.bucketed(
						HISTOGRAM_PARAM_VALUES,
						ExpressionFactory.parse(
							"$reference.referencedEntity?.attributes['" + ATTR_BASIC_UNIT_VALUE + "']"
						)
					)
			)
			.withReferenceToEntity(
				REF_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.indexedWithComponents(ReferenceIndexedComponents.values())
					.faceted()
			)
			.updateVia(session);
	}

	/**
	 * Seeds the hand-crafted catalog:
	 *
	 * - 2 parameter groups (`height` PK=1, `weight` PK=2);
	 * - 8 parameter values — four per group with disjoint `basicUnitValue` ranges so a height
	 *   `histogramHaving(10, 50, ...)` never touches the weight histogram's span;
	 * - 3 brands (A/B/C) used as a facet reference;
	 * - 8 products — one per PV — each referencing its PV (with its group) plus one brand and
	 *   carrying one basic EUR price. The `inStock` flag flips per PK parity so a narrowing
	 *   `attributeEquals("inStock", true)` leaves exactly half the catalog.
	 */
	private static void seedData(@Nonnull EvitaSessionContract session) {
		session.createNewEntity(ENTITY_PARAMETER, GROUP_HEIGHT_PK)
			.setAttribute(ATTR_CODE, GROUP_HEIGHT_CODE).upsertVia(session);
		session.createNewEntity(ENTITY_PARAMETER, GROUP_WEIGHT_PK)
			.setAttribute(ATTR_CODE, GROUP_WEIGHT_CODE).upsertVia(session);

		for (int i = 0; i < HEIGHT_PV_PKS.length; i++) {
			session.createNewEntity(ENTITY_PARAMETER_VALUE, HEIGHT_PV_PKS[i])
				.setAttribute(ATTR_CODE, "h-" + HEIGHT_VALUES[i])
				.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal(HEIGHT_VALUES[i]))
				.setReference(ENTITY_PARAMETER, GROUP_HEIGHT_PK)
				.upsertVia(session);
		}
		for (int i = 0; i < WEIGHT_PV_PKS.length; i++) {
			session.createNewEntity(ENTITY_PARAMETER_VALUE, WEIGHT_PV_PKS[i])
				.setAttribute(ATTR_CODE, "w-" + WEIGHT_VALUES[i])
				.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal(WEIGHT_VALUES[i]))
				.setReference(ENTITY_PARAMETER, GROUP_WEIGHT_PK)
				.upsertVia(session);
		}

		session.createNewEntity(ENTITY_BRAND, BRAND_A_PK)
			.setAttribute(ATTR_CODE, "brand-A").upsertVia(session);
		session.createNewEntity(ENTITY_BRAND, BRAND_B_PK)
			.setAttribute(ATTR_CODE, "brand-B").upsertVia(session);
		session.createNewEntity(ENTITY_BRAND, BRAND_C_PK)
			.setAttribute(ATTR_CODE, "brand-C").upsertVia(session);

		// 8 products — one per PV. Product PK = i+1 (1..8). Height products 1..4, weight 5..8.
		// Brands A/B/C round-robin so every brand carries both height and weight products.
		// Prices are set so that a `priceBetween(40, 140)` slider captures a non-empty subset
		// without swallowing the entire catalog — needed so price-histogram self-contraction is
		// observable.
		final int[] allPvPks = combine(HEIGHT_PV_PKS, WEIGHT_PV_PKS);
		final int[] allGroupPks = new int[]{
			GROUP_HEIGHT_PK, GROUP_HEIGHT_PK, GROUP_HEIGHT_PK, GROUP_HEIGHT_PK,
			GROUP_WEIGHT_PK, GROUP_WEIGHT_PK, GROUP_WEIGHT_PK, GROUP_WEIGHT_PK
		};
		final int[] productPrices = {30, 60, 110, 130, 150, 180, 220, 260};
		final int[] productBrands = {BRAND_A_PK, BRAND_B_PK, BRAND_C_PK, BRAND_A_PK,
			BRAND_B_PK, BRAND_C_PK, BRAND_A_PK, BRAND_B_PK};

		for (int i = 0; i < allPvPks.length; i++) {
			final int productPk = i + 1;
			final int pvPk = allPvPks[i];
			final int groupPk = allGroupPks[i];
			final int brandPk = productBrands[i];
			final boolean inStock = productPk % 2 == 1;
			final BigDecimal price = new BigDecimal(productPrices[i]);

			final EntityBuilder builder = session.createNewEntity(ENTITY_PRODUCT, productPk)
				.setAttribute(ATTR_IN_STOCK, inStock)
				.setAttribute(ATTR_DIMENSION_A, PRODUCT_DIMENSION_A[i])
				.setAttribute(ATTR_DIMENSION_B, PRODUCT_DIMENSION_B[i])
				.setPrice(
					1, PRICE_LIST_BASIC, CURRENCY_EUR,
					price, BigDecimal.ZERO, price, true
				);
			builder.setReference(
				REF_PARAM_VALUES, pvPk,
				whichIs -> whichIs.setGroup(ENTITY_PARAMETER, groupPk)
			);
			builder.setReference(REF_BRAND, brandPk);
			builder.upsertVia(session);
		}
	}

	/**
	 * Concatenates two `int[]` arrays into one.
	 *
	 * @param a first array
	 * @param b second array
	 * @return a new array containing `a` followed by `b`
	 */
	@Nonnull
	private static int[] combine(@Nonnull int[] a, @Nonnull int[] b) {
		final int[] out = new int[a.length + b.length];
		System.arraycopy(a, 0, out, 0, a.length);
		System.arraycopy(b, 0, out, a.length, b.length);
		return out;
	}

	/**
	 * Installs schema + data once and exposes the catalog via `@UseDataSet`.
	 *
	 * @param evita the shared evitaDB instance
	 * @return empty data carrier; tests read the catalog through the session
	 */
	@DataSet(HISTOGRAM_HAVING_DATA_SET)
	DataCarrier setUp(@Nonnull Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG, session -> {
				defineSchema(session);
				seedData(session);
			}
		);
		return new DataCarrier();
	}

	// =============================================================================================
	// Two sliders on the same reference, no contraction
	// =============================================================================================

	/**
	 * Two `histogramHaving` siblings on the same reference, one per parameter group. Moving the
	 * `height` slider into `[10, 50]` must not shrink the `weight` histogram's catalog-wide
	 * `[130, 260]` span; likewise for the reverse. The `requested=true` flag flips only on the
	 * bucket whose threshold falls into the active slider's range.
	 */
	@Nested
	@DisplayName("Two sliders on the same reference, no contraction")
	class ReferenceHistogramTwoSlidersNoContraction {

		@Test
		@UseDataSet(HISTOGRAM_HAVING_DATA_SET)
		@DisplayName("baseline histograms span the catalog-wide range per group")
		void shouldPopulateBaselineHistogramsSpanningCatalogWideRange(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final ReferenceSummary summary = querySummary(session, null);
					final HistogramContract heightBaseline = getHistogram(summary, GROUP_HEIGHT_PK);
					final HistogramContract weightBaseline = getHistogram(summary, GROUP_WEIGHT_PK);
					assertEquals(0, new BigDecimal("10.00").compareTo(heightBaseline.getMin()));
					assertEquals(0, new BigDecimal("120.00").compareTo(heightBaseline.getMax()));
					assertEquals(0, new BigDecimal("130.00").compareTo(weightBaseline.getMin()));
					assertEquals(0, new BigDecimal("260.00").compareTo(weightBaseline.getMax()));
					assertAllBucketsRequested(heightBaseline);
					assertAllBucketsRequested(weightBaseline);
				}
			);
		}

		@Test
		@UseDataSet(HISTOGRAM_HAVING_DATA_SET)
		@DisplayName("moving the height slider does not contract the weight histogram's [min, max]")
		void shouldLeaveWeightSpanUnchangedWhenHeightSliderIsMoved(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final UserFilter heightSlider = userFilter(
						histogramHavingForGroup(GROUP_HEIGHT_CODE, 10, 50)
					);
					final ReferenceSummary summary = querySummary(session, heightSlider);
					final HistogramContract height = getHistogram(summary, GROUP_HEIGHT_PK);
					final HistogramContract weight = getHistogram(summary, GROUP_WEIGHT_PK);

					// Height's OWN baseline must NOT contract — the slider carrier is peeled before
					// the histogram's [min, max] is computed.
					assertEquals(0, new BigDecimal("10.00").compareTo(height.getMin()));
					assertEquals(0, new BigDecimal("120.00").compareTo(height.getMax()));
					// The sibling (weight) histogram span is unaffected — confirms the
					// attribute-family relaxation is not triggered cross-group either.
					assertEquals(0, new BigDecimal("130.00").compareTo(weight.getMin()));
					assertEquals(0, new BigDecimal("260.00").compareTo(weight.getMax()));
					assertAnyBucketRequestedWithin(height, 10, 50);
					assertAllBucketsRequested(weight);
				}
			);
		}

		@Test
		@UseDataSet(HISTOGRAM_HAVING_DATA_SET)
		@DisplayName("moving the weight slider does not contract the height histogram's [min, max]")
		void shouldLeaveHeightSpanUnchangedWhenWeightSliderIsMoved(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final UserFilter weightSlider = userFilter(
						histogramHavingForGroup(GROUP_WEIGHT_CODE, 130, 170)
					);
					final ReferenceSummary summary = querySummary(session, weightSlider);
					final HistogramContract height = getHistogram(summary, GROUP_HEIGHT_PK);
					final HistogramContract weight = getHistogram(summary, GROUP_WEIGHT_PK);
					// weight's OWN baseline must NOT contract
					assertEquals(0, new BigDecimal("130.00").compareTo(weight.getMin()));
					assertEquals(0, new BigDecimal("260.00").compareTo(weight.getMax()));
					// sibling (height) is unaffected
					assertEquals(0, new BigDecimal("10.00").compareTo(height.getMin()));
					assertEquals(0, new BigDecimal("120.00").compareTo(height.getMax()));
					assertAnyBucketRequestedWithin(weight, 130, 170);
					assertAllBucketsRequested(height);
				}
			);
		}

		@Test
		@UseDataSet(HISTOGRAM_HAVING_DATA_SET)
		@DisplayName("result set reflects the histogramHaving slider narrowing")
		void shouldNarrowResultSetByHistogramHavingSlider(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					// height 10..50 matches products with height PV#11 (value 10) and PV#12 (value 50) — PKs 1, 2.
					// weight 130..170 matches products with weight PV#21 (130) and PV#22 (170) — PKs 5, 6.
					// Since every product has exactly ONE parameterValues reference, the AND narrowing
					// over the two independent group matches yields the UNION on ENTITIES (each product
					// is in exactly one group), so both branches collapse to 0 entities — use a broader
					// range that still stays within each group's disjoint band.
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(
								userFilter(
									histogramHavingForGroup(GROUP_HEIGHT_CODE, 10, 120)
								)
							)
						),
						EntityReferenceContract.class
					);
					// all 4 height products (PK 1..4) match a height PV in [10, 120]
					assertEquals(4, result.getTotalRecordCount());
				}
			);
		}

		/**
		 * When `userFilter(histogramHaving(...))` is the entire `filterBy`, attribute-family
		 * relaxation strips the slider and the relaxed tree collapses to the
		 * {@link io.evitadb.core.query.algebra.base.EmptyFormula#INSTANCE} sentinel — which
		 * `ReferenceSummaryProducer` must interpret as "no mandatory filter remains / all records
		 * pass", never as "empty result". The histogram baseline is therefore computed against the
		 * catalog-wide entity superset, so the slider's own `[min, max]` span stays catalog-wide
		 * even without any outer carrier to keep the filter tree alive after relaxation.
		 */
		@Test
		@UseDataSet(HISTOGRAM_HAVING_DATA_SET)
		@DisplayName("histogram baseline survives when userFilter is the only filter content")
		void shouldComputeHistogramBaselineWhenUserFilterIsTheOnlyFilterContent(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(
								userFilter(
									histogramHavingForGroup(GROUP_HEIGHT_CODE, 10, 50)
								)
							),
							require(
								referenceSummaryOfReferenceWithHistograms(
									REF_PARAM_VALUES, null, null, null,
									histogramStatistics(10, HISTOGRAM_PARAM_VALUES)
								)
							)
						),
						EntityReferenceContract.class
					);

					assertEquals(
						2, result.getTotalRecordCount(),
						"slider narrows to height-PV in [10, 50] → products {1, 2}"
					);

					final ReferenceSummary summary = result.getExtraResult(ReferenceSummary.class);
					assertNotNull(summary, "ReferenceSummary must be present");
					final HistogramContract heightHist = getHistogram(summary, GROUP_HEIGHT_PK);
					assertEquals(
						0, new BigDecimal("10.00").compareTo(heightHist.getMin()),
						"height histogram min must be catalog-wide 10 — relaxer must NOT collapse "
							+ "the baseline when userFilter is the only filter content"
					);
					assertEquals(
						0, new BigDecimal("120.00").compareTo(heightHist.getMax()),
						"height histogram max must be catalog-wide 120 — same constraint as above"
					);
					assertAnyBucketRequestedWithin(heightHist, 10, 50);
				}
			);
		}
	}

	// =============================================================================================
	// histogramHaving outside userFilter narrows the result set
	// =============================================================================================

	/**
	 * `histogramHaving` used OUTSIDE `userFilter` is a pure narrowing constraint — the
	 * `HistogramHavingFormula` wrapper is pass-through when there is no `UserFilter` to peel it
	 * from, so no range-carrier semantics kick in. This nest pins the narrowing invariant: a
	 * `groupSelector` must strictly narrow the result set vs the no-selector form, and the
	 * matched products must all belong to the selected group.
	 */
	@Nested
	@DisplayName("histogramHaving outside userFilter narrows the result set")
	class NarrowingOutsideUserFilter {

		@Test
		@UseDataSet(HISTOGRAM_HAVING_DATA_SET)
		@DisplayName("groupSelector narrows the result strictly more than no-selector form")
		void shouldNarrowMoreWithGroupSelectorThanWithout(@Nonnull Evita evita) {
			// Outside `userFilter`, `histogramHaving` is documented as a pure narrowing constraint —
			// adding a `groupSelector` must strictly narrow the result set vs the same query without
			// the selector. We exercise both shapes and assert the narrowing invariant directly,
			// rather than pinning specific PK sets that depend on the histogram's internal index
			// layout (the index may not surface every value in `[from, to]` if it falls into a
			// bucket-edge).
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					// Slider [10, 50] without group narrowing — covers everything under the height
					// group's value distribution; weight values (≥ 130) are outside this range.
					final EvitaResponse<EntityReferenceContract> withoutGroup = session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(
								histogramHaving(
									REF_PARAM_VALUES, HISTOGRAM_PARAM_VALUES,
									10, 50
								)
							)
						),
						EntityReferenceContract.class
					);

					// Same slider, but narrowed to a single group via the `groupHaving(...)`
					// selector. The selector picks the `height` group — which is the only group
					// hosting values in `[10, 50]`, so the expected effect is "narrow to height-only,
					// keep the same value range" — count must be ≤ the no-selector form, and every
					// matched product's PV must belong to `height`.
					final EvitaResponse<EntityReferenceContract> withGroup = session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(
								histogramHaving(
									REF_PARAM_VALUES, HISTOGRAM_PARAM_VALUES,
									10, 50,
									groupHaving(attributeEquals(ATTR_CODE, GROUP_HEIGHT_CODE))
								)
							)
						),
						EntityReferenceContract.class
					);

					assertTrue(
						withoutGroup.getTotalRecordCount() > 0,
						"baseline `histogramHaving` (no group selector) must match at least one product"
					);
					assertTrue(
						withGroup.getTotalRecordCount() > 0,
						"`histogramHaving` with group selector must match at least one product (the "
							+ "selector picks the only group covering the chosen range)"
					);
					assertTrue(
						withGroup.getTotalRecordCount() <= withoutGroup.getTotalRecordCount(),
						"`groupSelector` must not introduce records the unrestricted form does not "
							+ "already produce — got " + withGroup.getTotalRecordCount() +
							" with selector vs " + withoutGroup.getTotalRecordCount() + " without"
					);
					// Every matched product must belong to the height group — proves the selector
					// actually filters on the group rather than being silently dropped (this is the
					// key regression: the GraphQL/REST resolver path previously discarded the
					// selector silently, leaving the engine with the no-selector shape).
					for (final EntityReferenceContract ref : withGroup.getRecordData()) {
						final int productPk = ref.getPrimaryKey();
						assertTrue(
							productPk >= 1 && productPk <= 4,
							"product " + productPk + " is in the weight group but matched a "
								+ "histogramHaving constrained to the height group"
						);
					}
				}
			);
		}
	}

	// =============================================================================================
	// attributeHistogram sibling non-contraction (KEY REGRESSION for AttributeHistogramComputer)
	// =============================================================================================

	/**
	 * Two `attributeBetween` siblings inside `userFilter`, driving a real `attributeHistogram`
	 * over the directly-attached `dimensionA` / `dimensionB` product attributes. A previous
	 * implementation of `AttributeHistogramComputer` stripped only the attribute's **own**
	 * carrier; sibling carriers narrowed the baseline, contracting the histogram. The
	 * group-parameterised relaxer must strip **every** attribute-family carrier — both own and
	 * siblings — so each histogram retains its catalog-wide span when any combination of sibling
	 * sliders is dragged.
	 *
	 * Per-product values (parallel to PK 1..8): `dimensionA = {10, 20, 30, 40, 50, 60, 70, 80}`,
	 * `dimensionB = {200, 210, 240, 270, 290, 300, 320, 330}`. A slider on either attribute
	 * narrowing to `{1, 2, 3}` lets us assert a single AND-narrowed result count of 3 while
	 * concretely distinguishing correct group-wide exclusion (both spans stay catalog-wide)
	 * from the buggy per-attribute exclusion (each sibling carrier still narrows the other).
	 */
	@Nested
	@DisplayName("attributeHistogram siblings don't contract each other (KEY REGRESSION)")
	class AttributeHistogramSiblingsNoContraction {

		@Test
		@UseDataSet(HISTOGRAM_HAVING_DATA_SET)
		@DisplayName("baseline attributeHistograms span the catalog-wide ranges")
		void shouldComputeBaselineAttributeHistogramSpans(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(entityPrimaryKeyInSet(ALL_PRODUCT_PKS)),
							require(
								attributeHistogram(20, ATTR_DIMENSION_A, ATTR_DIMENSION_B)
							)
						),
						EntityReferenceContract.class
					);
					final AttributeHistogram histograms = result.getExtraResult(AttributeHistogram.class);
					assertNotNull(histograms, "AttributeHistogram extra result must be present");
					final HistogramContract a = histograms.getHistogram(ATTR_DIMENSION_A);
					final HistogramContract b = histograms.getHistogram(ATTR_DIMENSION_B);
					assertNotNull(a, "dimensionA histogram must be emitted");
					assertNotNull(b, "dimensionB histogram must be emitted");
					assertEquals(0, BigDecimal.valueOf(10).compareTo(a.getMin()));
					assertEquals(0, BigDecimal.valueOf(80).compareTo(a.getMax()));
					assertEquals(0, BigDecimal.valueOf(200).compareTo(b.getMin()));
					assertEquals(0, BigDecimal.valueOf(330).compareTo(b.getMax()));
					// NOTE: `attributeHistogram` defaults the per-bucket `requested` predicate to
					// `Functions::alwaysTrue` for any attribute that has NO `attributeBetween` in
					// userFilter (see `AttributeHistogramProducer.java:406`). So a baseline query
					// with no slider gets every bucket flagged `requested=true` — a producer-level
					// quirk orthogonal to the attribute-histogram relaxation under test. We therefore
					// skip the requested-flag check for slider-less attributes throughout this class.
				}
			);
		}

		@Test
		@UseDataSet(HISTOGRAM_HAVING_DATA_SET)
		@DisplayName("own attributeBetween slider does not self-contract its histogram")
		void shouldNotContractOwnAttributeHistogramWhenOnlyOwnSliderApplied(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(
								entityPrimaryKeyInSet(ALL_PRODUCT_PKS),
								userFilter(
									attributeBetween(ATTR_DIMENSION_A, 10, 30)
								)
							),
							require(
								attributeHistogram(20, ATTR_DIMENSION_A, ATTR_DIMENSION_B)
							)
						),
						EntityReferenceContract.class
					);

					assertEquals(
						3, result.getTotalRecordCount(),
						"dimensionA in [10, 30] narrows the main result to {1, 2, 3}"
					);

					final AttributeHistogram histograms = result.getExtraResult(AttributeHistogram.class);
					assertNotNull(histograms);
					final HistogramContract a = histograms.getHistogram(ATTR_DIMENSION_A);
					final HistogramContract b = histograms.getHistogram(ATTR_DIMENSION_B);
					assertNotNull(a);
					assertNotNull(b);
					assertEquals(
						0, BigDecimal.valueOf(10).compareTo(a.getMin()),
						"dimensionA min must equal catalog-wide min — own carrier is relaxed"
					);
					assertEquals(
						0, BigDecimal.valueOf(80).compareTo(a.getMax()),
						"dimensionA max must equal catalog-wide max — own carrier is relaxed"
					);
					assertEquals(
						0, BigDecimal.valueOf(200).compareTo(b.getMin()),
						"dimensionB has no slider — its baseline is the catalog min"
					);
					assertEquals(
						0, BigDecimal.valueOf(330).compareTo(b.getMax()),
						"dimensionB has no slider — its baseline is the catalog max"
					);
					assertAnyBucketRequestedWithin(a, 10, 30);
					// (b) has no slider → by the "no selection == full range" convention every
					// bucket carries requested=true; asserted explicitly to lock the contract in.
					assertAllBucketsRequested(b);
				}
			);
		}

		@Test
		@UseDataSet(HISTOGRAM_HAVING_DATA_SET)
		@DisplayName("KEY REGRESSION — sibling attributeBetween carrier does NOT contract neighbour histogram")
		void shouldNotContractSiblingAttributeHistogramWhenBothSlidersApplied(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					// Both sliders applied — `dimensionA in [10, 30]` and `dimensionB in [200, 240]`.
					// Each on its own narrows to products {1, 2, 3} (rows where A∈{10,20,30} and
					// rows where B∈{200,210,240}); their AND is also {1, 2, 3}. The relaxer must
					// strip BOTH siblings before each histogram is computed, otherwise:
					//   - `dimensionA` baseline contracts to A-values of {1,2,3} → max=30 (BUG)
					//   - `dimensionB` baseline contracts to B-values of {1,2,3} → max=240 (BUG)
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(
								entityPrimaryKeyInSet(ALL_PRODUCT_PKS),
								userFilter(
									attributeBetween(ATTR_DIMENSION_A, 10, 30),
									attributeBetween(ATTR_DIMENSION_B, 200, 240)
								)
							),
							require(
								attributeHistogram(20, ATTR_DIMENSION_A, ATTR_DIMENSION_B)
							)
						),
						EntityReferenceContract.class
					);

					assertEquals(
						3, result.getTotalRecordCount(),
						"AND of two sibling attribute-family ranges must narrow to {1, 2, 3}"
					);

					final AttributeHistogram histograms = result.getExtraResult(AttributeHistogram.class);
					assertNotNull(histograms);
					final HistogramContract a = histograms.getHistogram(ATTR_DIMENSION_A);
					final HistogramContract b = histograms.getHistogram(ATTR_DIMENSION_B);
					assertNotNull(a);
					assertNotNull(b);
					assertEquals(
						0, BigDecimal.valueOf(10).compareTo(a.getMin()),
						"dimensionA min must stay catalog-wide — sibling B carrier must be stripped"
					);
					assertEquals(
						0, BigDecimal.valueOf(80).compareTo(a.getMax()),
						"dimensionA max must stay catalog-wide — sibling B carrier must be stripped "
							+ "(if max=30, the buggy per-attribute exclusion has regressed)"
					);
					assertEquals(
						0, BigDecimal.valueOf(200).compareTo(b.getMin()),
						"dimensionB min must stay catalog-wide — sibling A carrier must be stripped"
					);
					assertEquals(
						0, BigDecimal.valueOf(330).compareTo(b.getMax()),
						"dimensionB max must stay catalog-wide — sibling A carrier must be stripped "
							+ "(if max=240, the buggy per-attribute exclusion has regressed)"
					);
					assertAnyBucketRequestedWithin(a, 10, 30);
					assertAnyBucketRequestedWithin(b, 200, 240);
				}
			);
		}

		@Test
		@UseDataSet(HISTOGRAM_HAVING_DATA_SET)
		@DisplayName("non-range userFilter child still narrows the attributeHistogram baseline")
		void shouldLetNonCarrierUserFilterChildNarrowAttributeHistogramBaseline(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					// `attributeEquals(inStock, true)` keeps odd-PK products → {1, 3, 5, 7}.
					// dimensionA values for {1,3,5,7} = {10, 30, 50, 70} → min=10, max=70.
					// dimensionB values for {1,3,5,7} = {200, 240, 290, 320} → min=200, max=320.
					// The relaxer must NOT strip `attributeEquals` (non-carrier), so both
					// histograms reflect the narrowed product set.
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(
								entityPrimaryKeyInSet(ALL_PRODUCT_PKS),
								userFilter(
									attributeEquals(ATTR_IN_STOCK, Boolean.TRUE)
								)
							),
							require(
								attributeHistogram(20, ATTR_DIMENSION_A, ATTR_DIMENSION_B)
							)
						),
						EntityReferenceContract.class
					);
					assertEquals(4, result.getTotalRecordCount(), "inStock=true narrows to {1,3,5,7}");

					final AttributeHistogram histograms = result.getExtraResult(AttributeHistogram.class);
					assertNotNull(histograms);
					final HistogramContract a = histograms.getHistogram(ATTR_DIMENSION_A);
					final HistogramContract b = histograms.getHistogram(ATTR_DIMENSION_B);
					assertNotNull(a);
					assertNotNull(b);
					assertEquals(0, BigDecimal.valueOf(10).compareTo(a.getMin()));
					assertEquals(0, BigDecimal.valueOf(70).compareTo(a.getMax()),
						"dimensionA max must reflect inStock-narrowing (non-carrier is NOT relaxed)");
					assertEquals(0, BigDecimal.valueOf(200).compareTo(b.getMin()));
					assertEquals(0, BigDecimal.valueOf(320).compareTo(b.getMax()),
						"dimensionB max must reflect inStock-narrowing (non-carrier is NOT relaxed)");
				}
			);
		}
	}

	// =============================================================================================
	// Price histogram, no self-contraction
	// =============================================================================================

	/**
	 * With `userFilter(priceBetween(low, high))`, the returned price histogram must still span the
	 * catalog-wide `[min, max]` (self-contraction is the bug). The `low..high` bucket carries
	 * `requested=true`.
	 */
	@Nested
	@DisplayName("Price histogram, no self-contraction")
	class PriceHistogramNoSelfContraction {

		@Test
		@UseDataSet(HISTOGRAM_HAVING_DATA_SET)
		@DisplayName("price histogram still spans catalog-wide [min, max] under priceBetween slider")
		void shouldKeepPriceHistogramSpanCatalogWideWhenPriceBetweenIsApplied(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					// baseline — no userFilter
					final EvitaResponse<EntityReferenceContract> baseline = session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(
								priceInCurrency(CURRENCY_EUR),
								priceInPriceLists(PRICE_LIST_BASIC)
							),
							require(priceHistogram(10))
						),
						EntityReferenceContract.class
					);
					final PriceHistogram basePh = baseline.getExtraResult(PriceHistogram.class);
					assertNotNull(basePh);

					// narrowed — price slider inside userFilter
					final EvitaResponse<EntityReferenceContract> narrowed = session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(
								priceInCurrency(CURRENCY_EUR),
								priceInPriceLists(PRICE_LIST_BASIC),
								userFilter(
									priceBetween(new BigDecimal("40"), new BigDecimal("140"))
								)
							),
							require(priceHistogram(10))
						),
						EntityReferenceContract.class
					);
					final PriceHistogram narrowedPh = narrowed.getExtraResult(PriceHistogram.class);
					assertNotNull(narrowedPh);

					// price-range relaxation invariant — the price histogram spans the catalog-wide
					// range regardless of the priceBetween slider.
					assertEquals(0, basePh.getMin().compareTo(narrowedPh.getMin()),
						"priceHistogram min must not contract under userFilter(priceBetween)");
					assertEquals(0, basePh.getMax().compareTo(narrowedPh.getMax()),
						"priceHistogram max must not contract under userFilter(priceBetween)");
					assertEquals(basePh.getOverallCount(), narrowedPh.getOverallCount(),
						"priceHistogram overallCount is the baseline count too "
							+ "(price-range relaxation)");

					// within the narrowed slider range [40, 140] at least one bucket carries requested=true
					final BigDecimal lo = new BigDecimal("40");
					final BigDecimal hi = new BigDecimal("140");
					boolean anyFlipped = false;
					for (final Bucket bucket : narrowedPh.getBuckets()) {
						final BigDecimal threshold = bucket.threshold();
						if (threshold.compareTo(lo) >= 0 && threshold.compareTo(hi) <= 0
							&& bucket.requested()) {
							anyFlipped = true;
							break;
						}
					}
					assertTrue(anyFlipped, "at least one bucket in [40, 140] must carry requested=true");
				}
			);
		}
	}

	// =============================================================================================
	// Facet impact, no self-contraction
	// =============================================================================================

	/**
	 * `userFilter(facetHaving('brand', pk=A))` — impact for other brand facets is computed against
	 * the baseline WITHOUT brand=A applied. Equivalent observable: the baseline facet summary
	 * count for brand B equals the number of products referencing brand B in the whole catalog;
	 * selecting brand A keeps brand A visible with `requested=true` and a count matching the
	 * subset of products in the narrowed result. Proves the facet summary honours the user's
	 * selection on the result set while computing impact for OTHER facets against the
	 * facet-impact-relaxed baseline (the "count vs. impact" baseline split).
	 */
	@Nested
	@DisplayName("Facet impact, no self-contraction")
	class FacetImpactNoSelfContraction {

		@Test
		@UseDataSet(HISTOGRAM_HAVING_DATA_SET)
		@DisplayName("selecting a facet narrows the main result to products carrying that facet")
		void shouldNarrowResultByFacetSelectionInUserFilter(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					// baseline — every product visible
					final EvitaResponse<EntityReferenceContract> baseline = session.query(
						query(collection(ENTITY_PRODUCT)),
						EntityReferenceContract.class
					);
					assertEquals(
						8, baseline.getTotalRecordCount(),
						"baseline must see every seeded product"
					);

					// select brand A: narrows to products {1, 4, 7}
					final EvitaResponse<EntityReferenceContract> selected = session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(
								userFilter(
									facetHaving(REF_BRAND, entityPrimaryKeyInSet(BRAND_A_PK))
								)
							)
						),
						EntityReferenceContract.class
					);
					assertEquals(
						3, selected.getTotalRecordCount(),
						"facet selection must narrow the main result to brand-A products {1, 4, 7}"
					);

					// select brand A + brand B: narrows to products {1, 2, 4, 5, 7, 8} — i.e. disjunction
					// over the same reference slot (the facet selection is an OR within a facet group)
					final EvitaResponse<EntityReferenceContract> twoBrands = session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(
								userFilter(
									facetHaving(
										REF_BRAND,
										entityPrimaryKeyInSet(BRAND_A_PK, BRAND_B_PK)
									)
								)
							)
						),
						EntityReferenceContract.class
					);
					assertEquals(
						6, twoBrands.getTotalRecordCount(),
						"OR selection across two brands must widen the main result to 6 products"
					);
				}
			);
		}

		@Test
		@UseDataSet(HISTOGRAM_HAVING_DATA_SET)
		@DisplayName("KEY REGRESSION — facet IMPACT respects attribute-family + price carriers but strips facetHaving")
		void shouldComputeFacetImpactWithFacetGroupRelaxationOnly(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					// userFilter combines all three group carriers:
					//   - facetHaving(brand, B)                  — facet-impact carrier
					//   - histogramHaving(height, 10..50)        — attribute-family carrier
					//   - priceBetween(40, 200)                  — price-range carrier
					//
					// Current result intersect:
					//   brand B = {2, 5, 8}
					//   height-PV in [10, 50] = PVs {11(10), 12(50)} → products {1, 2}
					//   price in [40, 200] = prices {60, 110, 130, 150, 180} → products {2, 3, 4, 5, 6}
					//   AND = {2}
					//
					// Facet-impact baseline (facet stripped, attribute-family + price applied):
					//   {1, 2} ∩ {2, 3, 4, 5, 6} = {2}
					//
					// Hovering brand A or C means selection = OR({B, X}) within the brand group:
					//   - brand_in_(A, B) = {1, 2, 4, 5, 7, 8} ∩ {2} = {2} → matchCount = 1
					//   - brand_in_(B, C) = {2, 3, 5, 6, 8}    ∩ {2} = {2} → matchCount = 1
					//
					// Old buggy (whole `userFilter` stripped from impact baseline):
					//   - baseline = full 8-product catalog
					//   - hovering A: matchCount = |brand_in_(A, B)| = 6
					//   - hovering C: matchCount = |brand_in_(B, C)| = 5
					// So matchCount=1 sharply discriminates the bug fix from the buggy behaviour.
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(
								priceInCurrency(CURRENCY_EUR),
								priceInPriceLists(PRICE_LIST_BASIC),
								userFilter(
									facetHaving(REF_BRAND, entityPrimaryKeyInSet(BRAND_B_PK)),
									histogramHavingForGroup(GROUP_HEIGHT_CODE, 10, 50),
									priceBetween(new BigDecimal("40"), new BigDecimal("200"))
								)
							),
							require(referenceSummary(FacetStatisticsDepth.IMPACT))
						),
						EntityReferenceContract.class
					);

					assertEquals(
						1, result.getTotalRecordCount(),
						"current result is {2} — brand B ∩ height ∩ price"
					);

					final ReferenceSummary summary = result.getExtraResult(ReferenceSummary.class);
					assertNotNull(summary);

					// brand reference is ungrouped → single-arg accessor returns the non-grouped slot
					final ReferenceGroupStatistics brandStats =
						summary.getReferenceGroupStatistics(REF_BRAND);
					assertNotNull(brandStats, "brand reference statistics must be present");

					final FacetStatistics brandA = brandStats.getFacetStatistics(BRAND_A_PK);
					final FacetStatistics brandB = brandStats.getFacetStatistics(BRAND_B_PK);
					final FacetStatistics brandC = brandStats.getFacetStatistics(BRAND_C_PK);
					assertNotNull(brandA, "facet stats for brand A must be emitted under IMPACT depth");
					assertNotNull(brandB, "facet stats for brand B must be emitted");
					assertNotNull(brandC, "facet stats for brand C must be emitted");

					assertTrue(brandB.isRequested(), "brand B is in userFilter → requested=true");
					assertFalse(brandA.isRequested(), "brand A is NOT in userFilter → requested=false");
					assertFalse(brandC.isRequested(), "brand C is NOT in userFilter → requested=false");

					final RequestImpact impactA = brandA.getImpact();
					final RequestImpact impactC = brandC.getImpact();
					assertNotNull(impactA, "impact must be emitted for unselected brand A");
					assertNotNull(impactC, "impact must be emitted for unselected brand C");

					assertEquals(
						1, impactA.matchCount(),
						"brand A impact.matchCount must be 1 — facet-impact relaxation preserves "
							+ "attribute-family + price carriers (buggy whole-strip would give 6)"
					);
					assertEquals(
						1, impactC.matchCount(),
						"brand C impact.matchCount must be 1 — facet-impact relaxation preserves "
							+ "attribute-family + price carriers (buggy whole-strip would give 5)"
					);
				}
			);
		}

		/**
		 * Count and impact use different baselines:
		 *
		 *   - **count** = `mandatory-filter ∩ {entities carrying this facet alone}` — i.e. the
		 *     `filterBy` content **outside** `userFilter` intersected with the facet's own matching
		 *     set, AS IF this facet were the only `userFilter` child ("what if I selected only
		 *     this one facet"). Count is the classic facet-search widget display: "N items match
		 *     this checkbox if you tick it alone".
		 *   - **impact.matchCount** = facet-impact-relaxed filter (`facetHaving` stripped,
		 *     attribute-family + price carriers kept) intersected with this facet ADDED to the
		 *     currently selected set — i.e. "if you also tick this box, the full result expands
		 *     to N items".
		 *
		 * Setup — `filterBy(entityPrimaryKeyInSet(1..5), userFilter(facetHaving(brand, B),
		 * histogramHaving(height, 10..50)))`:
		 *
		 *   - mandatory R_m (filterBy without userFilter)       = {1, 2, 3, 4, 5}
		 *   - full main result R                                = R_m ∩ {2,5,8} ∩ {1,2} = {2}
		 *   - facet-impact-relaxed R'                           = R_m ∩ {1, 2}         = {1, 2}
		 *
		 *   Brand memberships in the catalog: A={1,4,7}, B={2,5,8}, C={3,6}.
		 *
		 * Expected per-brand observations:
		 *   - count(A) = |R_m ∩ brand_A|   = |{1,2,3,4,5} ∩ {1,4,7}|   = **2**   (discriminates
		 *       against (i) full-filter reading → |R ∩ A| = 0, (ii) relaxed-for-count reading
		 *       → |R' ∩ A| = 1, (iii) unfiltered catalog reading → |A| = 3)
		 *   - count(B) = |R_m ∩ brand_B|   = |{1,2,3,4,5} ∩ {2,5,8}|   = **2**
		 *   - count(C) = |R_m ∩ brand_C|   = |{1,2,3,4,5} ∩ {3,6}|     = **1**
		 *   - impact(A).matchCount = |R' ∩ brand_in(A,B)|
		 *                          = |{1,2} ∩ {1,2,4,5,7,8}|          = **2**
		 *   - impact(C).matchCount = |R' ∩ brand_in(B,C)|
		 *                          = |{1,2} ∩ {2,3,5,6,8}|            = **1**
		 *
		 * The mandatory PK filter is essential — without it, `R_m` is the whole catalog and count
		 * degenerates to "all brand-X products", which cannot be distinguished from the buggy
		 * "no filter at all" reading.
		 */
		@Test
		@UseDataSet(HISTOGRAM_HAVING_DATA_SET)
		@DisplayName("facet count uses `mandatory-filter ∩ this-facet-alone`; impact uses facet-impact-relaxed")
		void shouldComputeFacetCountWithMandatoryFilterAndImpactWithRelaxedFilter(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(
								entityPrimaryKeyInSet(1, 2, 3, 4, 5),
								userFilter(
									facetHaving(REF_BRAND, entityPrimaryKeyInSet(BRAND_B_PK)),
									histogramHavingForGroup(GROUP_HEIGHT_CODE, 10, 50)
								)
							),
							require(referenceSummary(FacetStatisticsDepth.IMPACT))
						),
						EntityReferenceContract.class
					);

					assertEquals(
						1, result.getTotalRecordCount(),
						"full-filter main result = R_m ∩ brand_B ∩ height[10,50] = {2}"
					);

					final ReferenceSummary summary = result.getExtraResult(ReferenceSummary.class);
					assertNotNull(summary);

					final ReferenceGroupStatistics brandStats =
						summary.getReferenceGroupStatistics(REF_BRAND);
					assertNotNull(brandStats, "brand reference statistics must be present");

					final FacetStatistics brandA = brandStats.getFacetStatistics(BRAND_A_PK);
					final FacetStatistics brandB = brandStats.getFacetStatistics(BRAND_B_PK);
					final FacetStatistics brandC = brandStats.getFacetStatistics(BRAND_C_PK);
					assertNotNull(brandA, "brand A facet stats must be emitted under IMPACT depth");
					assertNotNull(brandB, "brand B facet stats must be emitted");
					assertNotNull(brandC, "brand C facet stats must be emitted");

					// --- COUNT path: `mandatory-filter ∩ this-facet-alone` ---
					assertEquals(
						2, brandA.getCount(),
						"count(A) = |R_m ∩ {1,4,7}| = 2. Alternatives ruled out by this number: "
							+ "full-filter reading = 0, facet-impact-relaxed-for-count = 1, "
							+ "catalog-wide = 3"
					);
					assertEquals(
						2, brandB.getCount(),
						"count(B) = |R_m ∩ {2,5,8}| = 2 — selected facets use the same count formula"
					);
					assertEquals(
						1, brandC.getCount(),
						"count(C) = |R_m ∩ {3,6}| = 1 — proves mandatory-filter is applied (catalog-wide "
							+ "would give 2); full-filter would give 0"
					);

					// --- PRESENCE (hasAnyResults, derived from count > 0) ---
					assertTrue(
						brandA.getCount() > 0,
						"brand A presence must be true — count > 0 after mandatory narrowing"
					);

					// --- IMPACT path: facet-impact-relaxed filter (facetHaving stripped,
					//                  attribute-family + price carriers kept) ---
					final RequestImpact impactA = brandA.getImpact();
					final RequestImpact impactC = brandC.getImpact();
					assertNotNull(impactA, "impact must be emitted for unselected brand A");
					assertNotNull(impactC, "impact must be emitted for unselected brand C");

					assertEquals(
						2, impactA.matchCount(),
						"impact(A) = |R' ∩ brand_in(A,B)| = |{1,2} ∩ {1,2,4,5,7,8}| = 2. "
							+ "Rules out: using the count formula (which gives 2 too — but shape differs), "
							+ "using the full filter (gives 1 — only product 2 carries brand B)"
					);
					assertEquals(
						1, impactC.matchCount(),
						"impact(C) = |R' ∩ brand_in(B,C)| = |{1,2} ∩ {2,3,5,6,8}| = 1"
					);
				}
			);
		}
	}

	// =============================================================================================
	// Cross-group mutual visibility
	// =============================================================================================

	/**
	 * Combined `userFilter(facetHaving('brand', pk=A), histogramHaving(..., height, 10..50,
	 * height-group), priceBetween(40, 140))`. Each group's self-computation relaxes only its own
	 * carriers and keeps the other two groups' carriers applied. The main result set reflects the
	 * AND of all three narrowings.
	 */
	@Nested
	@DisplayName("Cross-group mutual visibility")
	class CrossGroupMutualVisibility {

		@Test
		@UseDataSet(HISTOGRAM_HAVING_DATA_SET)
		@DisplayName("each group's self-computation relaxes only its own carriers (concrete min/max)")
		void shouldRelaxOnlyOwnCarriersPerGroup(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					// Cross-group userFilter combining one carrier from each of the three groups,
					// chosen so EVERY observable assertion below is concrete:
					//
					//   brand B                  → products {2, 5, 8}
					//   price [40, 200]          → products {2, 3, 4, 5, 6}
					//   height-slider [10, 120]  → height-group PVs {11..14} (val ∈ [10, 120])
					//                              → products {1, 2, 3, 4}
					//
					// Main result (all three applied): {2, 5, 8} ∩ {2..6} ∩ {1..4} = {2}.
					//
					// Reference histogram (own attribute-family carrier stripped, facet + price kept):
					//   facet+price baseline = brand B ∩ price [40, 200] = {2, 5}
					//   height-group hist from {2} (val 50)   → min=max=50
					//   weight-group hist from {5} (val 130)  → min=max=130
					//
					// Price histogram (own price-range carrier stripped, facet + attribute-family kept):
					//   attribute-family+facet baseline = brand B ∩ height-slider [10, 120] = {2}
					//   prices for {2} = {60} → min=max=60
					//
					// Buggy "whole-userFilter-stripped" baseline would give catalog-wide spans:
					//   - height histogram = [10, 120] (correct: 50/50)   ← sharp on min AND max
					//   - weight histogram = [130, 260] (correct: 130/130) ← sharp on max
					//   - price histogram  = [30, 260] (correct: 60/60)    ← sharp on min AND max
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(
								priceInCurrency(CURRENCY_EUR),
								priceInPriceLists(PRICE_LIST_BASIC),
								userFilter(
									facetHaving(REF_BRAND, entityPrimaryKeyInSet(BRAND_B_PK)),
									histogramHavingForGroup(GROUP_HEIGHT_CODE, 10, 120),
									priceBetween(new BigDecimal("40"), new BigDecimal("200"))
								)
							),
							require(
								priceHistogram(10),
								referenceSummaryOfReferenceWithHistograms(
									REF_PARAM_VALUES, null, null, null,
									histogramStatistics(10, HISTOGRAM_PARAM_VALUES)
								)
							)
						),
						EntityReferenceContract.class
					);

					assertEquals(
						1, result.getTotalRecordCount(),
						"main result is {2} — brand B ∩ price [40, 200] ∩ height-slider [10, 120]"
					);

					final ReferenceSummary refSummary = result.getExtraResult(ReferenceSummary.class);
					assertNotNull(refSummary);

					final HistogramContract heightHist = getHistogram(refSummary, GROUP_HEIGHT_PK);
					assertEquals(
						0, new BigDecimal("50.00").compareTo(heightHist.getMin()),
						"height histogram min=50 — facet + price carriers narrow baseline to {2, 5}; "
							+ "product 2 contributes height val 50 (own attribute-range carrier is "
							+ "stripped, so the slider [10, 120] does NOT further narrow this baseline)"
					);
					assertEquals(
						0, new BigDecimal("50.00").compareTo(heightHist.getMax()),
						"height histogram max=50 — same constraint as above"
					);

					final HistogramContract weightHist = getHistogram(refSummary, GROUP_WEIGHT_PK);
					assertEquals(
						0, new BigDecimal("130.00").compareTo(weightHist.getMin()),
						"weight histogram min=130 — product 5 contributes weight val 130 to the "
							+ "facet+price baseline; weight max staying 130 (NOT 260) proves price "
							+ "narrowing is honoured"
					);
					assertEquals(
						0, new BigDecimal("130.00").compareTo(weightHist.getMax()),
						"weight histogram max=130 — sharp discriminator vs the buggy whole-strip 260"
					);

					final PriceHistogram priceHist = result.getExtraResult(PriceHistogram.class);
					assertNotNull(priceHist);
					assertEquals(
						0, new BigDecimal("60").compareTo(priceHist.getMin()),
						"priceHistogram min=60 — price baseline = attribute-family+facet narrowed = {2}; "
							+ "product 2's price"
					);
					assertEquals(
						0, new BigDecimal("60").compareTo(priceHist.getMax()),
						"priceHistogram max=60 — same constraint; max=60 (NOT 260) proves facet "
							+ "narrowing is honoured by the price histogram path"
					);
				}
			);
		}

		@Test
		@UseDataSet(HISTOGRAM_HAVING_DATA_SET)
		@DisplayName("result-set count reflects the AND of all three narrowings")
		void shouldReflectAndOfAllThreeNarrowingsInResultSet(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					// brand=A → products {1, 4, 7}; height 10..50 → products {1, 2}; priceBetween 40..140
					// → products with price in [40, 140] → {2, 3, 4} (prices 60, 110, 130).
					// AND of {1, 4, 7} ∩ {1, 2} ∩ {2, 3, 4} = {} → but moving brand to B → products
					// {2, 5, 8}; ∩ {1, 2} = {2}; ∩ {2, 3, 4} = {2}. Use brand B for a non-empty match.
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(
								priceInCurrency(CURRENCY_EUR),
								priceInPriceLists(PRICE_LIST_BASIC),
								userFilter(
									facetHaving(REF_BRAND, entityPrimaryKeyInSet(BRAND_B_PK)),
									histogramHavingForGroup(GROUP_HEIGHT_CODE, 10, 50),
									priceBetween(new BigDecimal("40"), new BigDecimal("140"))
								)
							)
						),
						EntityReferenceContract.class
					);
					assertEquals(1, result.getTotalRecordCount(),
						"AND of all three cross-group carriers must yield exactly product #2");
				}
			);
		}
	}

	// =============================================================================================
	// Non-range / unknown userFilter children always applied
	// =============================================================================================

	/**
	 * `userFilter(attributeEquals('inStock', true), histogramHaving(...))`. The `attributeEquals`
	 * is not a range carrier, so no group strips it — it narrows everything (main result,
	 * histograms, facet impact, price histogram). The histogram range stays relaxed only within
	 * the attribute-family self-computation.
	 */
	@Nested
	@DisplayName("Non-range userFilter children always applied")
	class NonRangeChildrenAlwaysApplied {

		@Test
		@UseDataSet(HISTOGRAM_HAVING_DATA_SET)
		@DisplayName("attributeEquals narrows the main result set and the reference histogram span")
		void shouldApplyAttributeEqualsToMainResultAndHistograms(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<EntityReferenceContract> result = session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(
								userFilter(
									attributeEquals(ATTR_IN_STOCK, Boolean.TRUE),
									histogramHavingForGroup(GROUP_HEIGHT_CODE, 10, 50)
								)
							),
							require(
								referenceSummaryOfReferenceWithHistograms(
									REF_PARAM_VALUES, null, null, null,
									histogramStatistics(10, HISTOGRAM_PARAM_VALUES)
								)
							)
						),
						EntityReferenceContract.class
					);

					// inStock=true holds for odd-PK products → {1, 3, 5, 7}.
					// histogramHaving(height, 10..50) narrows main result to products with a height PV
					// in [10, 50] → PV#11 (value 10) or PV#12 (value 50) → products {1, 2}.
					// AND of {1, 3, 5, 7} ∩ {1, 2} = {1} → main result size must be 1.
					assertEquals(
						1, result.getTotalRecordCount(),
						"main result must apply BOTH attributeEquals(inStock, true) and "
							+ "histogramHaving(height, 10..50) — intersecting odd-PK height products "
							+ "with height PVs in [10, 50] leaves exactly product #1"
					);
				}
			);
		}

		@Test
		@UseDataSet(HISTOGRAM_HAVING_DATA_SET)
		@DisplayName("non-carrier userFilter child narrows reference histogram baseline")
		void shouldLetNonCarrierUserFilterChildNarrowHistogramBaseline(@Nonnull Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					// With only `inStock=true` under userFilter, the reference histogram must reflect
					// the narrowed product set — odd-PK products — which in the height group is {1, 3}
					// → PVs {11 (value 10), 13 (value 100)}. Min=10, max=100 (NOT 120 as baseline).
					final UserFilter inStockOnly = userFilter(
						attributeEquals(ATTR_IN_STOCK, Boolean.TRUE)
					);
					final ReferenceSummary summary = querySummary(session, inStockOnly);
					final HistogramContract height = getHistogram(summary, GROUP_HEIGHT_PK);
					assertEquals(0, new BigDecimal("10.00").compareTo(height.getMin()));
					assertEquals(0, new BigDecimal("100.00").compareTo(height.getMax()),
						"non-carrier userFilter child must narrow the histogram baseline (NOT relaxed)");
				}
			);
		}
	}

	// =============================================================================================
	// ReferenceHaving rejection inside userFilter
	// =============================================================================================

	/**
	 * `userFilter(referenceHaving(...))` must fail at constructor time: `ReferenceHaving` is
	 * listed in `UserFilter.FORBIDDEN_CHILDREN`.
	 */
	@Nested
	@DisplayName("ReferenceHaving rejection inside userFilter")
	class ReferenceHavingRejection {

		@Test
		@DisplayName("should throw EvitaInvalidUsageException at constructor time")
		void shouldThrowEvitaInvalidUsageExceptionAtConstructorTime() {
			final EvitaInvalidUsageException thrown = assertThrows(
				EvitaInvalidUsageException.class,
				() -> userFilter(
					referenceHaving(
						REF_PARAM_VALUES,
						entityHaving(attributeEquals(ATTR_CODE, "h-10"))
					)
				),
				"userFilter must reject `referenceHaving` children via FORBIDDEN_CHILDREN"
			);
			assertTrue(
				thrown.getMessage().contains("forbidden"),
				"error message must indicate a forbidden child, got: " + thrown.getMessage()
			);
			assertTrue(
				thrown.getMessage().toLowerCase().contains("referencehaving"),
				"error message must name the `referenceHaving` child, got: " + thrown.getMessage()
			);
		}

		/**
		 * `histogramHaving` nested inside `userFilter(userFilter(...))` must be rejected at
		 * construction time — specifically because the outer `userFilter` enforces
		 * `UserFilter.FORBIDDEN_CHILDREN`, which contains `UserFilter.class`. The
		 * `histogramHaving` itself is fine; what trips the rejection is the nested `userFilter`
		 * wrapper. This is a sanity guard against accidental nesting when clients mistakenly
		 * combine carriers.
		 */
		@Test
		@DisplayName("nested userFilter wrapping histogramHaving is rejected at construction time")
		void shouldRejectHistogramHavingInsideNestedUserFilter() {
			final EvitaInvalidUsageException thrown = assertThrows(
				EvitaInvalidUsageException.class,
				() -> userFilter(
					userFilter(
						histogramHavingForGroup(GROUP_HEIGHT_CODE, 10, 50)
					)
				),
				"userFilter must reject a nested userFilter wrapping histogramHaving"
			);
			assertTrue(
				thrown.getMessage().toLowerCase().contains("forbidden"),
				"error message must indicate forbidden child, got: " + thrown.getMessage()
			);
			assertTrue(
				thrown.getMessage().toLowerCase().contains("userfilter"),
				"error message must name the nested `userFilter`, got: " + thrown.getMessage()
			);
		}

		/**
		 * `histogramHaving` used inside `not(...)` inside `userFilter` is a pathological shape —
		 * a range carrier wrapped in a logical negation loses its meaning as a slider ("not in
		 * range" is not a slider UI widget). While outside `userFilter` `not(histogramHaving(...))`
		 * is legitimate (behaves as `not(referenceHaving rewrite)`), inside `userFilter` the
		 * translator should either reject it or refuse to treat it as a range carrier. This test
		 * asserts that the engine rejects the combination at translation time (an
		 * `EvitaInvalidUsageException` with an actionable message) rather than silently emitting
		 * a histogram whose baseline is computed with a negated slider that was never peeled.
		 */
		@Test
		@UseDataSet(HISTOGRAM_HAVING_DATA_SET)
		@DisplayName("histogramHaving inside `not` inside `userFilter` is rejected at translation time")
		void shouldRejectHistogramHavingInsideNotInsideUserFilter(@Nonnull Evita evita) {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> evita.queryCatalog(
					TEST_CATALOG,
					(Consumer<EvitaSessionContract>) session -> session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(
								userFilter(
									io.evitadb.api.query.QueryConstraints.not(
										histogramHavingForGroup(GROUP_HEIGHT_CODE, 10, 50)
									)
								)
							)
						),
						EntityReferenceContract.class
					)
				),
				"histogramHaving wrapped in `not` inside userFilter must not be accepted silently"
			);
		}
	}

	// =============================================================================================
	// Descriptor resolution errors on ambiguous histograms
	// =============================================================================================

	/**
	 * `histogramHaving('refName')` with an omitted `histogramName` when the reference hosts more
	 * than one histogram → actionable error. The shared fixture's `parameterValues` reference
	 * hosts a single histogram, so the ambiguity is forced with a transient schema extension
	 * inside a fresh isolated Evita instance.
	 */
	@Nested
	@DisplayName("Descriptor resolution errors")
	class DescriptorResolutionErrors {

		@Test
		@DisplayName("should throw when histogramName is omitted and the reference hosts multiple histograms")
		void shouldThrowWhenHistogramNameOmittedAndMultipleHistogramsExist() {
			final TestPaths paths = createTestPaths("HistogramHavingFunctionalTest_ambiguous");
			try (Evita evita = new Evita(
				getEvitaConfiguration(paths)
			)) {
				evita.defineCatalog(TEST_CATALOG);
				evita.updateCatalog(
					TEST_CATALOG, session -> {
						// dual-histogram schema — the ambiguity fires on bare `histogramHaving(refName)`
						session.defineEntitySchema(ENTITY_PARAMETER)
							.withAttribute(ATTR_CODE, String.class, AttributeSchemaEditor::unique)
							.updateVia(session);
						session.defineEntitySchema(ENTITY_PARAMETER_VALUE)
							.withAttribute(ATTR_CODE, String.class, AttributeSchemaEditor::unique)
							.withAttribute(
								ATTR_BASIC_UNIT_VALUE, BigDecimal.class,
								whichIs -> whichIs.filterable().indexDecimalPlaces(2)
							)
							.withReferenceToEntity(
								ENTITY_PARAMETER, ENTITY_PARAMETER, Cardinality.EXACTLY_ONE,
								ReferenceSchemaEditor::indexedForFilteringAndPartitioning
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
										"marketShare", BigDecimal.class,
										thatIs -> thatIs.filterable().indexDecimalPlaces(2)
									)
									.bucketed(
										"basicUnitValueBucket",
										ExpressionFactory.parse(
											"$reference.referencedEntity?.attributes['"
												+ ATTR_BASIC_UNIT_VALUE + "']"
										)
									)
									.bucketed(
										"marketShareBucket",
										ExpressionFactory.parse(
											"$reference.attributes['marketShare']"
										)
									)
							)
							.updateVia(session);
						session.createNewEntity(ENTITY_PARAMETER, 1)
							.setAttribute(ATTR_CODE, "grp").upsertVia(session);
						session.createNewEntity(ENTITY_PARAMETER_VALUE, 1)
							.setAttribute(ATTR_CODE, "pv-1")
							.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal("10"))
							.setReference(ENTITY_PARAMETER, 1)
							.upsertVia(session);
						session.createNewEntity(ENTITY_PRODUCT, 1)
							.setReference(
								REF_PARAM_VALUES, 1,
								whichIs -> whichIs.setGroup(ENTITY_PARAMETER, 1)
									.setAttribute("marketShare", new BigDecimal("20"))
							)
							.upsertVia(session);
					}
				);

				final EvitaInvalidUsageException thrown = assertThrows(
					EvitaInvalidUsageException.class,
					() -> evita.queryCatalog(
						TEST_CATALOG,
						(Consumer<EvitaSessionContract>) session -> session.query(
							query(
								collection(ENTITY_PRODUCT),
								filterBy(
									userFilter(
										// explicitly omit histogramName — must throw because the
										// reference hosts two histograms
										new HistogramHaving(
											REF_PARAM_VALUES, null,
											new BigDecimal("0"), new BigDecimal("1000"),
											null
										)
									)
								)
							),
							EntityReference.class
						)
					),
					"omitted histogramName on a multi-histogram reference must throw an actionable error"
				);
				assertTrue(
					thrown.getMessage().toLowerCase().contains("histogramname")
						|| thrown.getMessage().toLowerCase().contains("multiple histograms"),
					"error message must name the ambiguity, got: " + thrown.getMessage()
				);
			} finally {
				cleanupTestPaths(paths);
			}
		}

		// NOTE: duplicate-detection test follows below (placed inside this class for the isolated
		// fixture's descriptor-error neighbourhood).

		/**
		 * Two `histogramHaving` constraints with identical `(referenceName, histogramName, groupPk)`
		 * tuples must be rejected — either at planning time or during translation — because the
		 * histogram slot can carry only one range. The duplicate is detected after the
		 * group-selector is resolved to a concrete group PK, so the two constraints do NOT need
		 * to use literally identical `groupSelector` constraints to conflict — resolving to the
		 * same group is enough. This test uses the simpler path of two literally identical
		 * selectors; a production fix for this test should still catch the "different selector,
		 * same resolved PK" case.
		 */
		@Test
		@UseDataSet(HISTOGRAM_HAVING_DATA_SET)
		@DisplayName("duplicate histogramHaving on same (ref, histogram, group) tuple must be rejected")
		void shouldRejectDuplicateHistogramHavingOnSameSlot(@Nonnull Evita evita) {
			final EvitaInvalidUsageException thrown = assertThrows(
				EvitaInvalidUsageException.class,
				() -> evita.queryCatalog(
					TEST_CATALOG,
					(Consumer<EvitaSessionContract>) session -> session.query(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(
								userFilter(
									// two ranges targeting the same (parameterValues, basicUnitValueBucket, height)
									// tuple — the histogram slot can only carry one
									histogramHavingForGroup(GROUP_HEIGHT_CODE, 10, 50),
									histogramHavingForGroup(GROUP_HEIGHT_CODE, 60, 100)
								)
							),
							require(
								referenceSummaryOfReferenceWithHistograms(
									REF_PARAM_VALUES, null, null, null,
									histogramStatistics(10, HISTOGRAM_PARAM_VALUES)
								)
							)
						),
						EntityReferenceContract.class
					)
				),
				"two histogramHaving on the same (reference, histogram, group) tuple must throw"
			);
			final String lowered = thrown.getMessage() == null
				? ""
				: thrown.getMessage().toLowerCase();
			assertTrue(
				lowered.contains("duplicate")
					|| lowered.contains("already")
					|| lowered.contains("multiple")
					|| lowered.contains("more than one"),
				"error message must describe the duplicate slot, got: " + thrown.getMessage()
			);
		}
	}

	// =============================================================================================
	// Two sliders on the same reference, non-empty AND on multi-PV products
	// =============================================================================================

	/**
	 * The earlier two-sliders suite asserts that moving a single `histogramHaving` slider does
	 * not contract its sibling histograms — but the shared fixture assigns exactly one
	 * `parameterValues` reference per product, so the AND of two `histogramHaving`s is always
	 * empty and the emitted-summary path cannot be observed.
	 *
	 * This class rebuilds a minimal isolated catalog where each product carries **both** a
	 * `height` and a `weight` parameterValue reference, so the two sliders intersect to a
	 * non-empty product set. With both sliders active we assert:
	 *   - main result reflects the AND of both narrowings (sharp numeric discriminator)
	 *   - BOTH group histograms still span the catalog-wide `[min, max]` (own-carrier + sibling
	 *     carrier are both stripped by the attribute-histogram relaxer)
	 *   - BOTH `requested` flags flip (one bucket in the active range on each histogram)
	 */
	@Nested
	@DisplayName("Two sliders, non-empty AND, both baselines & requested flags")
	class TwoSlidersNonEmptyAndMultiPv {

		@Test
		@DisplayName("both baselines stay catalog-wide and both requested flags flip")
		void shouldKeepBothBaselinesAndFlipBothRequestedFlagsWhenBothSlidersApplied() {
			final TestPaths paths = createTestPaths("HistogramHavingFunctionalTest_multiPv");
			try (Evita evita = new Evita(
				getEvitaConfiguration(paths)
			)) {
				evita.defineCatalog(TEST_CATALOG);
				evita.updateCatalog(
					TEST_CATALOG, session -> {
						// --- schema (single histogram, grouped reference) -------------------------
						session.defineEntitySchema(ENTITY_PARAMETER)
							.withAttribute(ATTR_CODE, String.class, AttributeSchemaEditor::unique)
							.updateVia(session);
						session.defineEntitySchema(ENTITY_PARAMETER_VALUE)
							.withAttribute(ATTR_CODE, String.class, AttributeSchemaEditor::unique)
							.withAttribute(
								ATTR_BASIC_UNIT_VALUE, BigDecimal.class,
								whichIs -> whichIs.filterable().indexDecimalPlaces(2)
							)
							.withReferenceToEntity(
								ENTITY_PARAMETER, ENTITY_PARAMETER, Cardinality.EXACTLY_ONE,
								ReferenceSchemaEditor::indexedForFilteringAndPartitioning
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
										HISTOGRAM_PARAM_VALUES,
										ExpressionFactory.parse(
											"$reference.referencedEntity?.attributes['"
												+ ATTR_BASIC_UNIT_VALUE + "']"
										)
									)
							)
							.updateVia(session);

						// --- groups ------------------------------------------------------------
						session.createNewEntity(ENTITY_PARAMETER, GROUP_HEIGHT_PK)
							.setAttribute(ATTR_CODE, GROUP_HEIGHT_CODE).upsertVia(session);
						session.createNewEntity(ENTITY_PARAMETER, GROUP_WEIGHT_PK)
							.setAttribute(ATTR_CODE, GROUP_WEIGHT_CODE).upsertVia(session);

						// --- parameter values (4 per group, disjoint bands) --------------------
						// heights: PVs 11..14 carry values 10, 50, 100, 120
						// weights: PVs 21..24 carry values 130, 170, 210, 260
						final int[] heightValues = {10, 50, 100, 120};
						final int[] weightValues = {130, 170, 210, 260};
						for (int i = 0; i < heightValues.length; i++) {
							session.createNewEntity(ENTITY_PARAMETER_VALUE, 11 + i)
								.setAttribute(ATTR_CODE, "h-" + heightValues[i])
								.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal(heightValues[i]))
								.setReference(ENTITY_PARAMETER, GROUP_HEIGHT_PK)
								.upsertVia(session);
							session.createNewEntity(ENTITY_PARAMETER_VALUE, 21 + i)
								.setAttribute(ATTR_CODE, "w-" + weightValues[i])
								.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal(weightValues[i]))
								.setReference(ENTITY_PARAMETER, GROUP_WEIGHT_PK)
								.upsertVia(session);
						}

						// --- products (each carries BOTH a height PV and a weight PV) -----------
						// Products 1..4 pair the i-th height PV with the i-th weight PV, so:
						//   P1 → (height=10,  weight=130)
						//   P2 → (height=50,  weight=170)
						//   P3 → (height=100, weight=210)
						//   P4 → (height=120, weight=260)
						for (int i = 0; i < 4; i++) {
							final int heightPv = 11 + i;
							final int weightPv = 21 + i;
							final int productPk = i + 1;
							session.createNewEntity(ENTITY_PRODUCT, productPk)
								.setReference(
									REF_PARAM_VALUES, heightPv,
									whichIs -> whichIs.setGroup(ENTITY_PARAMETER, GROUP_HEIGHT_PK)
								)
								.setReference(
									REF_PARAM_VALUES, weightPv,
									whichIs -> whichIs.setGroup(ENTITY_PARAMETER, GROUP_WEIGHT_PK)
								)
								.upsertVia(session);
						}
					}
				);

				evita.queryCatalog(
					TEST_CATALOG,
					session -> {
						// Slider bands:
						//   height [10, 50]   → matches products with height-PV value ∈ [10, 50] → {P1, P2}
						//   weight [130, 170] → matches products with weight-PV value ∈ [130, 170] → {P1, P2}
						// AND: {P1, P2} → non-empty, size 2.
						final HistogramHaving heightSlider = histogramHaving(
							REF_PARAM_VALUES, HISTOGRAM_PARAM_VALUES,
							10, 50,
							groupHaving(attributeEquals(ATTR_CODE, GROUP_HEIGHT_CODE))
						);
						final HistogramHaving weightSlider = histogramHaving(
							REF_PARAM_VALUES, HISTOGRAM_PARAM_VALUES,
							130, 170,
							groupHaving(attributeEquals(ATTR_CODE, GROUP_WEIGHT_CODE))
						);
						final EvitaResponse<EntityReferenceContract> result = session.query(
							query(
								collection(ENTITY_PRODUCT),
								filterBy(userFilter(heightSlider, weightSlider)),
								require(
									referenceSummaryOfReferenceWithHistograms(
										REF_PARAM_VALUES, null, null, null,
										histogramStatistics(10, HISTOGRAM_PARAM_VALUES)
									)
								)
							),
							EntityReferenceContract.class
						);
						assertEquals(
							2, result.getTotalRecordCount(),
							"main result must reflect the AND of both narrowings → {P1, P2}"
						);

						final ReferenceSummary summary =
							result.getExtraResult(ReferenceSummary.class);
						assertNotNull(summary, "referenceSummary must be present");

						final HistogramContract height = getHistogram(summary, GROUP_HEIGHT_PK);
						final HistogramContract weight = getHistogram(summary, GROUP_WEIGHT_PK);

						// --- baselines: both stay catalog-wide (both attribute-range carriers peeled) ---
						assertEquals(
							0, new BigDecimal("10.00").compareTo(height.getMin()),
							"height min must stay catalog-wide (own carrier peeled)"
						);
						assertEquals(
							0, new BigDecimal("120.00").compareTo(height.getMax()),
							"height max must stay catalog-wide — sibling weight carrier must ALSO "
								+ "be peeled (group-wide attribute-range strip, not per-histogram)"
						);
						assertEquals(
							0, new BigDecimal("130.00").compareTo(weight.getMin()),
							"weight min must stay catalog-wide (own carrier peeled)"
						);
						assertEquals(
							0, new BigDecimal("260.00").compareTo(weight.getMax()),
							"weight max must stay catalog-wide — sibling height carrier ALSO peeled"
						);

						// --- requested flags: both flip in their respective ranges -------------
						assertAnyBucketRequestedWithin(height, 10, 50);
						assertAnyBucketRequestedWithin(weight, 130, 170);
					}
				);
			} finally {
				cleanupTestPaths(paths);
			}
		}
	}

	// =============================================================================================
	// shared helpers — fixture queries, histogram / facet accessors, range assertions
	// =============================================================================================

	/**
	 * Produces an `Evita` configuration rooted at the given {@link TestPaths}. Used by tests that
	 * provision a fresh isolated instance.
	 *
	 * @param paths collision-free path triplet, typically produced by {@link #createTestPaths(String)}
	 * @return a configured `EvitaConfiguration` with session timeouts disabled
	 */
	@Nonnull
	private io.evitadb.api.configuration.EvitaConfiguration getEvitaConfiguration(@Nonnull TestPaths paths) {
		return newTestEvitaConfigurationBuilder(paths)
			.server(io.evitadb.api.configuration.ServerOptions.builder()
				.closeSessionsAfterSecondsOfInactivity(-1).build())
			.build();
	}

	/**
	 * Primary keys of every product seeded by the fixture. Used by the sibling `attributeHistogram`
	 * tests as an outer filter carrier to pin the tested entity set to "all products" — purely a
	 * positive assertion baseline, never a workaround for a collapse-on-relaxation footgun.
	 */
	private static final Integer[] ALL_PRODUCT_PKS = {1, 2, 3, 4, 5, 6, 7, 8};

	/**
	 * Issues the shared `referenceSummaryOfReferenceWithHistograms` query for both parameter
	 * groups, optionally wrapped in an incoming `userFilter`. No outer filter carrier is injected —
	 * `ReferenceSummaryProducer` correctly translates the `UserFilterRelaxer` collapse sentinel to
	 * "no mandatory filter remains / all records pass", so a `userFilter(histogramHaving(...))`
	 * that is the entire `filterBy` yields the catalog-wide baseline on its own.
	 *
	 * @param session   the read session
	 * @param userFilter optional `userFilter` container to apply (null for no filter)
	 * @return the reference summary extra result — never null
	 */
	@Nonnull
	private static ReferenceSummary querySummary(
		@Nonnull EvitaSessionContract session, @Nullable UserFilter userFilter
	) {
		final EvitaResponse<EntityReferenceContract> result = session.query(
			userFilter == null
				? query(
					collection(ENTITY_PRODUCT),
					require(
						referenceSummaryOfReferenceWithHistograms(
							REF_PARAM_VALUES, null, null, null,
							histogramStatistics(10, HISTOGRAM_PARAM_VALUES)
						)
					)
				)
				: query(
					collection(ENTITY_PRODUCT),
					filterBy(userFilter),
					require(
						referenceSummaryOfReferenceWithHistograms(
							REF_PARAM_VALUES, null, null, null,
							histogramStatistics(10, HISTOGRAM_PARAM_VALUES)
						)
					)
				),
			EntityReferenceContract.class
		);
		final ReferenceSummary summary = result.getExtraResult(ReferenceSummary.class);
		assertNotNull(summary, "referenceSummary must be present in the response");
		return summary;
	}

	/**
	 * Returns the `basicUnitValueBucket` histogram for the supplied parameter group. Fails the
	 * test if either the group statistics or the histogram are missing.
	 *
	 * @param summary the reference summary
	 * @param groupPk the parameter group primary key
	 * @return the non-null histogram
	 */
	@Nonnull
	private static HistogramContract getHistogram(@Nonnull ReferenceSummary summary, int groupPk) {
		final ReferenceGroupStatistics group =
			summary.getReferenceGroupStatistics(REF_PARAM_VALUES, groupPk);
		assertNotNull(group, "group " + groupPk + " statistics must exist");
		final HistogramContract histogram = group.getHistogramStatistics(HISTOGRAM_PARAM_VALUES);
		assertNotNull(histogram, "histogram must be emitted for group " + groupPk);
		return histogram;
	}

	/**
	 * Builds a `histogramHaving` constraint targeting the `parameterValues` reference, scoped to
	 * the parameter group identified by its `code` attribute.
	 *
	 * @param groupCode parameter group code (`height` / `weight`)
	 * @param from      inclusive range lower bound
	 * @param to        inclusive range upper bound
	 * @return a newly constructed `histogramHaving` constraint; never null
	 */
	@Nonnull
	private static HistogramHaving histogramHavingForGroup(
		@Nonnull String groupCode, int from, int to
	) {
		final HistogramHaving result = histogramHaving(
			REF_PARAM_VALUES, HISTOGRAM_PARAM_VALUES,
			from, to,
			groupHaving(attributeEquals(ATTR_CODE, groupCode))
		);
		assertNotNull(result, "factory must produce a non-null histogramHaving");
		return result;
	}

	/**
	 * Asserts every bucket of the supplied histogram carries `requested=true`. This is the expected
	 * shape for a histogram whose `userFilter` has no `histogramHaving` slider for it — the engine
	 * treats "no selection" as "everything selected" so that clients can render a min-to-max slider
	 * widget without special-casing the empty state. The implementation avoids streams for the sake
	 * of explicit control flow.
	 *
	 * @param histogram the histogram to inspect
	 */
	private static void assertAllBucketsRequested(@Nonnull HistogramContract histogram) {
		for (final Bucket bucket : histogram.getBuckets()) {
			assertTrue(bucket.requested(), "bucket at " + bucket.threshold() + " must be requested");
		}
	}

	/**
	 * Asserts that at least one bucket whose threshold falls in the inclusive range `[lo, hi]`
	 * carries `requested=true`. Used to confirm `histogramHaving` flipped the slider's bucket
	 * while leaving the span unchanged.
	 *
	 * @param histogram the histogram to inspect
	 * @param lo        inclusive lower bound of the requested range
	 * @param hi        inclusive upper bound of the requested range
	 */
	private static void assertAnyBucketRequestedWithin(
		@Nonnull HistogramContract histogram, int lo, int hi
	) {
		final BigDecimal loBd = new BigDecimal(lo);
		final BigDecimal hiBd = new BigDecimal(hi);
		boolean any = false;
		for (final Bucket bucket : histogram.getBuckets()) {
			final BigDecimal threshold = bucket.threshold();
			if (threshold.compareTo(loBd) >= 0 && threshold.compareTo(hiBd) <= 0
				&& bucket.requested()) {
				any = true;
				break;
			}
		}
		assertTrue(
			any, "at least one bucket in [" + lo + ", " + hi + "] must carry requested=true"
		);
	}

}
