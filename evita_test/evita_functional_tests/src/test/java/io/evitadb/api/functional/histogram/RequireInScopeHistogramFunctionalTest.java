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

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.EntityGroupFetch;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.extraResult.AttributeHistogram;
import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.PriceHistogram;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.FacetStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor.ReferenceSchemaBuilder;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.PlanPreference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.function.Function;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.require.EmptyHierarchicalEntityBehaviour.LEAVE_EMPTY;
import static io.evitadb.api.query.require.StatisticsBase.COMPLETE_FILTER;
import static io.evitadb.api.query.require.StatisticsType.CHILDREN_COUNT;
import static io.evitadb.api.query.require.StatisticsType.QUERIED_ENTITY_COUNT;
import static io.evitadb.dataType.Scope.ARCHIVED;
import static io.evitadb.dataType.Scope.LIVE;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FACET;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.PRICE;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.utils.PlanPreference.INDEX_SCAN;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A histogram planned inside a require-level `inScope(S, ...)` of a query searching both scopes describes the
 * returned entities of scope `S`.
 *
 * Fixture: one live product (price 12.1 with tax, `metric` 1, `otherMetric` 11) and one archived product (price 121
 * with tax, `metric` 5, `otherMetric` 15). Both reference both brands (the second one archived) and the same
 * category, and both carry a `share` on their references to params and tags: 3 on param 100 for the live product,
 * 7 on param 101 for the archived one. The tags share one group, while the same pairs as `labels` sit in a group per
 * product. Each product also references a material of its own scope, whose `weight` (2 live, 8 archived) feeds a
 * referenced entity attribute histogram. Prices, attributes and references are indexed in both scopes, so every
 * histogram and the facet summary can be computed in either scope, and the unwrapped extra results of a query
 * searching both scopes describe both products.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Extra results inside a require-level inScope describe the entities of its scope only")
@Tag(CONTRACT)
@Tag(HISTOGRAM)
@Tag(PRICE)
@Tag(ATTRIBUTE)
@Tag(FACET)
@Tag(REFERENCE)
@ExtendWith(EvitaParameterResolver.class)
class RequireInScopeHistogramFunctionalTest implements EvitaTestSupport {

	private static final String FIXTURE = "requireInScopeHistogram";
	private static final String WRITABLE_FIXTURE = "requireInScopeHistogramWritable";
	private static final String COUNTED_BOUNDARY_FIXTURE = "requireInScopeHistogramCountedBoundary";
	private static final String SORTED_BOUNDARY_FIXTURE = "requireInScopeHistogramSortedBoundary";
	private static final String SCOPED_BOUNDARY_FIXTURE = "requireInScopeHistogramScopedBoundary";
	private static final String GROUP_CONTROLS_FIXTURE = "requireInScopeHistogramGroupControls";
	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_BRAND = "brand";
	private static final String REF_BRAND = "brands";
	private static final String REF_CATEGORY = "categories";
	private static final String REF_PARAM = "params";
	private static final String REF_TAG = "tags";
	private static final String REF_LABEL = "labels";
	private static final String REF_MATERIAL = "materials";
	private static final String SHARE_HISTOGRAM = "shareHistogram";
	private static final String ARCHIVED_SHARE_HISTOGRAM = "archivedShareHistogram";
	private static final String WEIGHT_HISTOGRAM = "weightHistogram";
	private static final String WEIGHT_EXPRESSION = "$reference.referencedEntity?.attributes['weight']";
	private static final String ENTITY_CATEGORY = "category";
	private static final String ENTITY_PARAM = "param";
	private static final String ENTITY_GROUP = "tagGroup";
	private static final String ENTITY_MATERIAL = "material";
	private static final String ATTRIBUTE_WEIGHT = "weight";
	private static final String ATTRIBUTE_METRIC = "metric";
	private static final String PRICE_LIST = "basic";
	private static final Currency CURRENCY = Currency.getInstance("CZK");
	private static final BigDecimal TAX_RATE = new BigDecimal("21");

	private static final int PRODUCT_LIVE_PK = 1;
	private static final int PRODUCT_ARCHIVED_PK = 2;
	private static final int BRAND_PK = 10;
	private static final int ARCHIVED_BRAND_PK = 11;
	private static final int CATEGORY_PK = 20;
	private static final int GROUP_PK = 500;
	private static final int OTHER_GROUP_PK = 501;
	private static final int LOWER_MATERIAL_PK = 299;
	private static final int LIVE_MATERIAL_PK = 300;
	private static final int ARCHIVED_MATERIAL_PK = 301;
	private static final int COUNTED_OWNER_PK = 3;
	private static final int COUNTED_PARAM_PK = 102;
	private static final int COUNTED_MATERIAL_PK = 302;

	/**
	 * Builds the fixture the read-only tests share. The catalog stays in warm-up, and the tests must not change it.
	 *
	 * @param evita the engine to build the fixture in
	 */
	@DataSet(value = FIXTURE, expectedCatalogState = CatalogState.WARMING_UP, destroyAfterClass = true)
	void setUpFixture(@Nonnull Evita evita) {
		buildFixture(evita);
	}

	/**
	 * Builds the same fixture for the tests that add or change entities; each of them gets a fresh copy.
	 *
	 * @param evita the engine to build the fixture in
	 */
	@DataSet(
		value = WRITABLE_FIXTURE, expectedCatalogState = CatalogState.WARMING_UP, readOnly = false,
		destroyAfterClass = true
	)
	void setUpWritableFixture(@Nonnull Evita evita) {
		buildFixture(evita);
	}

	/**
	 * Adds a separate LIVE owner whose reference values tie with the existing LIVE owner's values.
	 *
	 * @param evita the engine to build the fixture in
	 */
	@DataSet(value = COUNTED_BOUNDARY_FIXTURE, expectedCatalogState = CatalogState.WARMING_UP, destroyAfterClass = true)
	void setUpCountedBoundaryFixture(@Nonnull Evita evita) {
		buildFixture(evita);
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.createNewEntity(ENTITY_PARAM, COUNTED_PARAM_PK).upsertVia(session);
			session.createNewEntity(ENTITY_MATERIAL, COUNTED_MATERIAL_PK).setAttribute(ATTRIBUTE_WEIGHT, 2)
				.upsertVia(session);
			session.createNewEntity(ENTITY_PRODUCT, COUNTED_OWNER_PK)
				.setPrice(3, PRICE_LIST, CURRENCY, new BigDecimal("10"), TAX_RATE, new BigDecimal("12.1"), true)
				.setReference(REF_PARAM, COUNTED_PARAM_PK, whichIs -> whichIs.setAttribute("share", 3))
				.setReference(REF_MATERIAL, COUNTED_MATERIAL_PK)
				.upsertVia(session);
		});
	}

	/**
	 * Sets up a catalog in which the test can add ranks and a tied boundary without sharing its mutations.
	 *
	 * @param evita the engine to build the fixture in
	 */
	@DataSet(
		value = SORTED_BOUNDARY_FIXTURE, expectedCatalogState = CatalogState.WARMING_UP, readOnly = false,
		destroyAfterClass = true
	)
	void setUpSortedBoundaryFixture(@Nonnull Evita evita) {
		buildFixture(evita);
	}

	/**
	 * Ties LIVE and ARCHIVED targets at reference-share and material-weight boundaries of a LIVE product.
	 *
	 * @param evita the engine holding the catalog
	 */
	@DataSet(value = SCOPED_BOUNDARY_FIXTURE, expectedCatalogState = CatalogState.WARMING_UP, destroyAfterClass = true)
	void setUpScopedBoundaryFixture(@Nonnull Evita evita) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			defineSchema(session);
			seedData(session);
			session.getEntity(ENTITY_PARAM, 100, attributeContentAll()).orElseThrow()
				.openForWrite().setAttribute("rank", 9).upsertVia(session);
			session.getEntity(ENTITY_PARAM, 101, attributeContentAll()).orElseThrow()
				.openForWrite().setAttribute("rank", 1).upsertVia(session);
			session.createNewEntity(ENTITY_PARAM, 99).setAttribute("rank", 2).upsertVia(session);
			session.createNewEntity(ENTITY_PARAM, 102).setAttribute("rank", 8).upsertVia(session);
			session.createNewEntity(ENTITY_MATERIAL, LOWER_MATERIAL_PK).setAttribute(ATTRIBUTE_WEIGHT, 2)
				.upsertVia(session);
			session.archiveEntity(ENTITY_PARAM, 99);
			session.archiveEntity(ENTITY_PARAM, 101);
			session.getEntity(ENTITY_PRODUCT, PRODUCT_LIVE_PK, referenceContentAll()).orElseThrow()
				.openForWrite()
				.setReference(REF_PARAM, 99, reference -> reference.setAttribute("share", 3))
				.setReference(REF_PARAM, 102, reference -> reference.setAttribute("share", 3))
				.setReference(REF_TAG, 99, reference -> reference.setGroup(ENTITY_GROUP, GROUP_PK)
					.setAttribute("share", 3))
				.setReference(REF_TAG, 102, reference -> reference.setGroup(ENTITY_GROUP, GROUP_PK)
					.setAttribute("share", 3))
				.setReference(REF_MATERIAL, LOWER_MATERIAL_PK)
				.upsertVia(session);
			session.archiveEntity(ENTITY_MATERIAL, LOWER_MATERIAL_PK);
			session.getEntity(ENTITY_PRODUCT, PRODUCT_ARCHIVED_PK, referenceContentAll()).orElseThrow()
				.openForWrite()
				.setReference(REF_PARAM, 101, reference -> reference.setAttribute("share", 3))
				.setReference(REF_TAG, 101, reference -> reference.setGroup(ENTITY_GROUP, GROUP_PK)
					.setAttribute("share", 3))
				.upsertVia(session);
			session.archiveEntity(ENTITY_PRODUCT, PRODUCT_ARCHIVED_PK);
		});
	}

	/**
	 * Adds tied LIVE targets and two histogram groups with distinguishable content.
	 *
	 * @param evita the engine holding the catalog
	 */
	@DataSet(value = GROUP_CONTROLS_FIXTURE, expectedCatalogState = CatalogState.WARMING_UP, destroyAfterClass = true)
	void setUpGroupControlsFixture(@Nonnull Evita evita) {
		buildFixture(evita);
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(ENTITY_GROUP)
				.withAttribute("name", String.class, attribute -> attribute.nullable())
				.withAttribute("code", String.class, attribute -> attribute.nullable())
				.updateVia(session);
			session.getEntity(ENTITY_GROUP, GROUP_PK, attributeContentAll()).orElseThrow()
				.openForWrite().setAttribute("name", "Selected").setAttribute("code", "selected").upsertVia(session);
			session.getEntity(ENTITY_PARAM, 100, attributeContentAll()).orElseThrow()
				.openForWrite().setAttribute("rank", 1).upsertVia(session);
			session.createNewEntity(ENTITY_PARAM, 102).setAttribute("rank", 2).upsertVia(session);
			session.getEntity(ENTITY_PRODUCT, PRODUCT_LIVE_PK, referenceContentAll()).orElseThrow()
				.openForWrite()
				.setReference(REF_TAG, 102, reference -> reference.setGroup(ENTITY_GROUP, GROUP_PK)
					.setAttribute("share", 3))
				.setReference(REF_TAG, 101, reference -> reference.setGroup(ENTITY_GROUP, OTHER_GROUP_PK)
					.setAttribute("share", 6))
				.upsertVia(session);
		});
	}

	/**
	 * Creates the fixture entities and archives one product, one brand and one material.
	 *
	 * @param evita the engine to build the fixture in
	 */
	private static void buildFixture(@Nonnull Evita evita) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			defineSchema(session);
			seedData(session);
			session.archiveEntity(ENTITY_PRODUCT, PRODUCT_ARCHIVED_PK);
			session.archiveEntity(ENTITY_BRAND, ARCHIVED_BRAND_PK);
			session.archiveEntity(ENTITY_MATERIAL, ARCHIVED_MATERIAL_PK);
		});
	}

	/**
	 * Defines the referenced entity types and a product indexing in both scopes its prices, its `metric` and
	 * `otherMetric` attributes, its faceted brand reference, its category reference, the bucketed `share` histograms
	 * of its ungrouped param reference and its two grouped references, and the bucketed `weight` histogram of its
	 * material reference.
	 *
	 * @param session the session to define the schema in
	 */
	private static void defineSchema(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_BRAND)
			.withoutGeneratedPrimaryKey()
			.withAttribute("code", String.class, whichIs -> whichIs.filterableInScope(Scope.values()))
			.updateVia(session);
		session.defineEntitySchema(ENTITY_CATEGORY)
			.withoutGeneratedPrimaryKey()
			.withHierarchy()
			.withHierarchyIndexedInScope(Scope.values())
			.updateVia(session);
		session.defineEntitySchema(ENTITY_PARAM)
			.withoutGeneratedPrimaryKey()
			.withAttribute("rank", Integer.class, whichIs -> whichIs.nullable().sortableInScope(Scope.values()))
			.updateVia(session);
		session.defineEntitySchema(ENTITY_GROUP).withoutGeneratedPrimaryKey().updateVia(session);
		session.defineEntitySchema(ENTITY_MATERIAL)
			.withoutGeneratedPrimaryKey()
			.withAttribute(ATTRIBUTE_WEIGHT, Integer.class, whichIs -> whichIs.filterableInScope(Scope.values()))
			.updateVia(session);

		session.defineEntitySchema(ENTITY_PRODUCT)
			.withoutGeneratedPrimaryKey()
			.withPriceIndexedInScope(Scope.values())
			.withAttribute(
				ATTRIBUTE_METRIC, Integer.class, whichIs -> whichIs.nullable().filterableInScope(Scope.values())
			)
			.withAttribute(
				"otherMetric", Integer.class, whichIs -> whichIs.nullable().filterableInScope(Scope.values())
			)
			.withReferenceToEntity(
				REF_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioningInScope(Scope.values())
					.facetedInScope(Scope.values())
			)
			.withReferenceToEntity(
				REF_CATEGORY, ENTITY_CATEGORY, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.indexedForFilteringAndPartitioningInScope(Scope.values())
			)
			.withReferenceToEntity(REF_PARAM, ENTITY_PARAM, Cardinality.ZERO_OR_MORE,
				whichIs -> defineBucketedReference(whichIs).bucketedInScope(
					ARCHIVED, ARCHIVED_SHARE_HISTOGRAM, ExpressionFactory.parse("$reference.attributes['share']")
				))
			.withReferenceToEntity(REF_TAG, ENTITY_PARAM, Cardinality.ZERO_OR_MORE,
				whichIs -> defineBucketedReference(whichIs.withGroupTypeRelatedToEntity(ENTITY_GROUP)))
			.withReferenceToEntity(REF_LABEL, ENTITY_PARAM, Cardinality.ZERO_OR_MORE,
				whichIs -> defineBucketedReference(whichIs.withGroupTypeRelatedToEntity(ENTITY_GROUP)))
			.withReferenceToEntity(
				REF_MATERIAL, ENTITY_MATERIAL, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioningInScope(Scope.values())
					.indexedWithComponentsInScope(LIVE, ReferenceIndexedComponents.values())
					.indexedWithComponentsInScope(ARCHIVED, ReferenceIndexedComponents.values())
					.facetedInScope(Scope.values())
					.bucketedInScope(LIVE, WEIGHT_HISTOGRAM, ExpressionFactory.parse(WEIGHT_EXPRESSION))
					.bucketedInScope(ARCHIVED, WEIGHT_HISTOGRAM, ExpressionFactory.parse(WEIGHT_EXPRESSION))
			)
			.updateVia(session);
	}

	/**
	 * Defines the same histogram on a grouped or ungrouped reference.
	 *
	 * @param reference the reference schema builder
	 * @return the builder with a filterable source attribute and histogram indexes
	 */
	@Nonnull
	private static ReferenceSchemaBuilder defineBucketedReference(@Nonnull ReferenceSchemaBuilder reference) {
		return reference
			.indexedForFilteringAndPartitioningInScope(Scope.values())
			.indexedWithComponentsInScope(LIVE, ReferenceIndexedComponents.values())
			.indexedWithComponentsInScope(ARCHIVED, ReferenceIndexedComponents.values())
			.facetedInScope(Scope.values())
			.withAttribute("share", Integer.class, whichIs -> whichIs.filterableInScope(Scope.values()))
			.bucketedInScope(LIVE, SHARE_HISTOGRAM, ExpressionFactory.parse("$reference.attributes['share']"))
			.bucketedInScope(ARCHIVED, SHARE_HISTOGRAM, ExpressionFactory.parse("$reference.attributes['share']"));
	}

	/**
	 * Creates the two brands, the category, two params, two groups, two materials and the two products, all live at
	 * this point.
	 *
	 * @param session the session to write the entities in
	 */
	private static void seedData(@Nonnull EvitaSessionContract session) {
		session.createNewEntity(ENTITY_BRAND, BRAND_PK).setAttribute("code", "a").upsertVia(session);
		session.createNewEntity(ENTITY_BRAND, ARCHIVED_BRAND_PK).setAttribute("code", "b").upsertVia(session);
		session.createNewEntity(ENTITY_CATEGORY, CATEGORY_PK).upsertVia(session);
		session.createNewEntity(ENTITY_PARAM, 100).upsertVia(session);
		session.createNewEntity(ENTITY_PARAM, 101).upsertVia(session);
		session.createNewEntity(ENTITY_GROUP, GROUP_PK).upsertVia(session);
		session.createNewEntity(ENTITY_GROUP, OTHER_GROUP_PK).upsertVia(session);
		session.createNewEntity(ENTITY_MATERIAL, LIVE_MATERIAL_PK).setAttribute(ATTRIBUTE_WEIGHT, 2).upsertVia(session);
		session.createNewEntity(ENTITY_MATERIAL, ARCHIVED_MATERIAL_PK).setAttribute(ATTRIBUTE_WEIGHT, 8)
			.upsertVia(session);
		session.createNewEntity(ENTITY_PRODUCT, PRODUCT_LIVE_PK)
			.setAttribute(ATTRIBUTE_METRIC, 1)
			.setAttribute("otherMetric", 11)
			.setPrice(1, PRICE_LIST, CURRENCY, new BigDecimal("10"), TAX_RATE, new BigDecimal("12.1"), true)
			.setReference(REF_BRAND, BRAND_PK)
			.setReference(REF_BRAND, ARCHIVED_BRAND_PK)
			.setReference(REF_CATEGORY, CATEGORY_PK)
			.setReference(REF_PARAM, 100, whichIs -> whichIs.setAttribute("share", 3))
			.setReference(REF_TAG, 100, whichIs -> whichIs.setGroup(ENTITY_GROUP, GROUP_PK).setAttribute("share", 3))
			.setReference(REF_LABEL, 100, whichIs -> whichIs.setGroup(ENTITY_GROUP, GROUP_PK).setAttribute("share", 3))
			.setReference(REF_MATERIAL, LIVE_MATERIAL_PK)
			.upsertVia(session);
		session.createNewEntity(ENTITY_PRODUCT, PRODUCT_ARCHIVED_PK)
			.setAttribute(ATTRIBUTE_METRIC, 5)
			.setAttribute("otherMetric", 15)
			.setPrice(2, PRICE_LIST, CURRENCY, new BigDecimal("100"), TAX_RATE, new BigDecimal("121"), true)
			.setReference(REF_BRAND, BRAND_PK)
			.setReference(REF_BRAND, ARCHIVED_BRAND_PK)
			.setReference(REF_CATEGORY, CATEGORY_PK)
			.setReference(REF_PARAM, 101, whichIs -> whichIs.setAttribute("share", 7))
			.setReference(REF_TAG, 101, whichIs -> whichIs.setGroup(ENTITY_GROUP, GROUP_PK).setAttribute("share", 7))
			.setReference(
				REF_LABEL, 101, whichIs -> whichIs.setGroup(ENTITY_GROUP, OTHER_GROUP_PK).setAttribute("share", 7)
			)
			.setReference(REF_MATERIAL, ARCHIVED_MATERIAL_PK)
			.upsertVia(session);
	}

	/**
	 * Runs a query over products with a price in the price list, searching the given scopes.
	 *
	 * @param evita        the engine holding the fixture
	 * @param scopes       the scopes the query searches
	 * @param describer    turns the response into the compared description
	 * @param requirements the extra results to compute
	 * @return the description of the response
	 */
	@Nonnull
	private static String describe(
		@Nonnull Evita evita,
		@Nonnull Scope[] scopes,
		@Nonnull Function<EvitaResponse<EntityReferenceContract>, String> describer,
		@Nonnull RequireConstraint... requirements
	) {
		return describer.apply(execute(evita, scopes, null, null, null, requirements));
	}

	/**
	 * Queries products in the given scopes with the fixture's currency and price list.
	 *
	 * @param evita        the engine holding the fixture
	 * @param scopes       the searched scopes
	 * @param filter       a supplemental filter
	 * @param ordering     the requested ordering
	 * @param route        a preferred execution route
	 * @param requirements the extra results to compute
	 * @return the query response
	 */
	@Nonnull
	private static EvitaResponse<EntityReferenceContract> execute(
		@Nonnull Evita evita,
		@Nonnull Scope[] scopes,
		@Nullable FilterConstraint filter,
		@Nullable OrderBy ordering,
		@Nullable PlanPreference route,
		@Nonnull RequireConstraint... requirements
	) {
		final FilterConstraint[] filters = {
			scope(scopes), priceInCurrency(CURRENCY), priceInPriceLists(PRICE_LIST), filter
		};
		final RequireConstraint[] allRequirements =
			Arrays.copyOf(requirements, requirements.length + (route == null ? 1 : 2));
		allRequirements[requirements.length] = route == null ?
			debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES) :
			route.debug();
		if (route != null) {
			allRequirements[requirements.length + 1] = queryTelemetry();
		}
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.query(
					query(
						collection(ENTITY_PRODUCT),
						filterBy(route == null ? filters :
							route.filter(new int[]{PRODUCT_LIVE_PK, PRODUCT_ARCHIVED_PK}, filters)),
						ordering, require(allRequirements)
					),
					EntityReferenceContract.class
				);
			}
		);
	}

	/**
	 * Describes the price histogram of the response.
	 *
	 * @param response the query response
	 * @return the overall count, bounds and buckets of the histogram
	 */
	@Nonnull
	private static String priceHistogramOf(@Nonnull EvitaResponse<EntityReferenceContract> response) {
		return describeHistogram(response.getExtraResult(PriceHistogram.class));
	}

	/**
	 * Describes the `metric` attribute histogram of the response.
	 *
	 * @param response the query response
	 * @return the overall count, bounds and buckets of the histogram
	 */
	@Nonnull
	private static String metricHistogramOf(@Nonnull EvitaResponse<EntityReferenceContract> response) {
		final AttributeHistogram histograms = response.getExtraResult(AttributeHistogram.class);
		return describeHistogram(histograms == null ? null : histograms.getHistogram(ATTRIBUTE_METRIC));
	}

	/**
	 * Describes the count of the brand facet in the facet summary of the response.
	 *
	 * @param response the query response
	 * @return the count of the brand facet
	 */
	@Nonnull
	private static String brandFacetOf(@Nonnull EvitaResponse<EntityReferenceContract> response) {
		final FacetSummary summary = response.getExtraResult(FacetSummary.class);
		final ReferenceGroupStatistics group = summary == null ? null : summary.getFacetGroupStatistics(REF_BRAND);
		final FacetStatistics facet = group == null ? null : group.getFacetStatistics(BRAND_PK);
		return facet == null ? "no brand facet" : "brand facet count=" + facet.getCount();
	}

	/**
	 * Describes a histogram so that two histograms over the same entities describe alike.
	 *
	 * @param histogram the histogram, or `null` when the response has none
	 * @return the overall count, bounds and buckets of the histogram
	 */
	@Nonnull
	private static String describeHistogram(@Nullable HistogramContract histogram) {
		if (histogram == null) {
			return "no histogram";
		}
		final StringBuilder description = new StringBuilder(96)
			.append("count=").append(histogram.getOverallCount())
			.append(", min=").append(histogram.getMin().stripTrailingZeros().toPlainString())
			.append(", max=").append(histogram.getMax().stripTrailingZeros().toPlainString())
			.append(", buckets=");
		for (final HistogramContract.Bucket bucket : histogram.getBuckets()) {
			description.append('[').append(bucket.threshold().stripTrailingZeros().toPlainString())
				.append(": ").append(bucket.occurrences()).append(']');
		}
		return description.toString();
	}

	/**
	 * Drops the buckets from a histogram description, so that it compares only the overall count and bounds.
	 *
	 * @param description a description produced by {@link #describeHistogram(HistogramContract)}
	 * @return the description without its buckets
	 */
	@Nonnull
	private static String withoutBuckets(@Nonnull String description) {
		return description.replaceAll(", buckets=.*", "");
	}

	/**
	 * Describes a histogram over the single fixture product of one scope.
	 *
	 * @param value the value of that product
	 * @return the description of a histogram with one occurrence of the value
	 */
	@Nonnull
	private static String singleValueHistogram(@Nonnull String value) {
		return "count=1, min=" + value + ", max=" + value + ", buckets=[" + value + ": 1]";
	}

	/**
	 * Returns the price with tax of the fixture product living in the scope.
	 *
	 * @param scope the scope of the product
	 * @return the price as described by {@link #describeHistogram(HistogramContract)}
	 */
	@Nonnull
	private static String priceOf(@Nonnull Scope scope) {
		return scope == LIVE ? "12.1" : "121";
	}

	/**
	 * Returns the `metric` of the fixture product living in the scope.
	 *
	 * @param scope the scope of the product
	 * @return the metric as described by {@link #describeHistogram(HistogramContract)}
	 */
	@Nonnull
	private static String metricOf(@Nonnull Scope scope) {
		return scope == LIVE ? "1" : "5";
	}

	@Nested
	@DisplayName("Controls: the fixture is visible in each scope")
	class Controls {

		/**
		 * Checks the controls over both scopes.
		 *
		 * @param evita the engine holding the shared fixture
		 */
		@Test
		@DisplayName("Unwrapped extra results of a query searching both scopes describe both products")
		@UseDataSet(FIXTURE)
		void shouldDescribeBothProductsWhenUnwrappedExtraResultsSearchBothScopes(@Nonnull Evita evita) {
			final Scope[] both = Scope.values();
			assertAll(
				() -> assertEquals(
					"count=2, min=12.1, max=121",
					withoutBuckets(describe(evita, both, RequireInScopeHistogramFunctionalTest::priceHistogramOf,
						priceHistogram(5)))
				),
				() -> assertEquals(
					"count=2, min=1, max=5",
					withoutBuckets(describe(evita, both, RequireInScopeHistogramFunctionalTest::metricHistogramOf,
						attributeHistogram(5, ATTRIBUTE_METRIC)))
				),
				() -> assertEquals(
					"brand facet count=2",
					describe(evita, both, RequireInScopeHistogramFunctionalTest::brandFacetOf, facetSummary())
				)
			);
		}

		/**
		 * Checks the control over a single searched scope.
		 *
		 * @param scope the scope to test
		 * @param evita the engine holding the shared fixture
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(Scope.class)
		@DisplayName("Extra results of a query searching one scope describe its product only")
		@UseDataSet(FIXTURE)
		void shouldDescribeOneProductWhenTheQuerySearchesOneScope(@Nonnull Scope scope, @Nonnull Evita evita) {
			final Scope[] one = {scope};
			final String price = priceOf(scope);
			final String metric = metricOf(scope);
			assertAll(
				() -> assertEquals(
					"count=1, min=" + price + ", max=" + price,
					withoutBuckets(describe(evita, one, RequireInScopeHistogramFunctionalTest::priceHistogramOf,
						priceHistogram(5)))
				),
				() -> assertEquals(
					"count=1, min=" + metric + ", max=" + metric,
					withoutBuckets(describe(evita, one, RequireInScopeHistogramFunctionalTest::metricHistogramOf,
						attributeHistogram(5, ATTRIBUTE_METRIC)))
				),
				() -> assertEquals(
					"brand facet count=1",
					describe(evita, one, RequireInScopeHistogramFunctionalTest::brandFacetOf, facetSummary())
				)
			);
		}
	}

	@Nested
	@DisplayName("A require-level inScope in a query searching both scopes")
	class WrappedInScope {

		/**
		 * Compares the wrapped price histogram with its single-scope control.
		 *
		 * @param scope the scope to test
		 * @param evita the engine holding the shared fixture
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(Scope.class)
		@DisplayName("computes the price histogram of its scope only")
		@UseDataSet(FIXTURE)
		void shouldComputePriceHistogramOfTheWrappedScopeOnly(@Nonnull Scope scope, @Nonnull Evita evita) {
			assertEquals(
				describe(evita, new Scope[]{scope}, RequireInScopeHistogramFunctionalTest::priceHistogramOf,
					priceHistogram(5)),
				describe(evita, Scope.values(), RequireInScopeHistogramFunctionalTest::priceHistogramOf,
					inScope(scope, priceHistogram(5)))
			);
		}

		/**
		 * Compares the wrapped attribute histogram with its single-scope control.
		 *
		 * @param scope the scope to test
		 * @param evita the engine holding the shared fixture
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(Scope.class)
		@DisplayName("computes the attribute histogram of its scope only")
		@UseDataSet(FIXTURE)
		void shouldComputeAttributeHistogramOfTheWrappedScopeOnly(@Nonnull Scope scope, @Nonnull Evita evita) {
			assertEquals(
				describe(evita, new Scope[]{scope}, RequireInScopeHistogramFunctionalTest::metricHistogramOf,
					attributeHistogram(5, ATTRIBUTE_METRIC)),
				describe(evita, Scope.values(), RequireInScopeHistogramFunctionalTest::metricHistogramOf,
					inScope(scope, attributeHistogram(5, ATTRIBUTE_METRIC)))
			);
		}

		/**
		 * Compares the wrapped facet summary with its single-scope control.
		 *
		 * @param scope the scope to test
		 * @param evita the engine holding the shared fixture
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(Scope.class)
		@DisplayName("counts the facets of its scope only")
		@UseDataSet(FIXTURE)
		void shouldCountFacetsOfTheWrappedScopeOnly(@Nonnull Scope scope, @Nonnull Evita evita) {
			assertEquals(
				describe(
					evita, new Scope[]{scope}, RequireInScopeHistogramFunctionalTest::brandFacetOf, facetSummary()
				),
				describe(evita, Scope.values(), RequireInScopeHistogramFunctionalTest::brandFacetOf,
					inScope(scope, facetSummary()))
			);
		}

		/**
		 * Compares the wrapped category tree and both statistics with their single-scope control.
		 *
		 * @param scope the wrapped scope
		 * @param evita a fresh copy of the fixture
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(Scope.class)
		@DisplayName("counts matching products and children in the wrapped hierarchy")
		@UseDataSet(value = WRITABLE_FIXTURE, destroyAfterTest = true)
		void shouldComputeHierarchyStatisticsOfTheWrappedScopeOnly(@Nonnull Scope scope, @Nonnull Evita evita) {
			final int childCategoryPk = CATEGORY_PK + 1;
			evita.updateCatalog(TEST_CATALOG, session -> {
				session.createNewEntity(ENTITY_CATEGORY, childCategoryPk).setParent(CATEGORY_PK).upsertVia(session);
				if (scope == ARCHIVED) {
					assertTrue(session.archiveEntity(ENTITY_CATEGORY, CATEGORY_PK));
					assertTrue(session.archiveEntity(ENTITY_CATEGORY, childCategoryPk));
				}
			});

			final RequireConstraint hierarchy = hierarchyOfReference(
				REF_CATEGORY, LEAVE_EMPTY,
				fromRoot("tree", statistics(COMPLETE_FILTER, QUERIED_ENTITY_COUNT, CHILDREN_COUNT))
			);
			final EvitaResponse<EntityReferenceContract> single =
				execute(evita, new Scope[]{scope}, null, null, null, hierarchy);
			final EvitaResponse<EntityReferenceContract> wrapped =
				execute(evita, Scope.values(), null, null, null, inScope(scope, hierarchy));
			assertEquals(1, single.getTotalRecordCount());
			assertEquals(2, wrapped.getTotalRecordCount());

			final Hierarchy expected = single.getExtraResult(Hierarchy.class);
			assertNotNull(expected);
			assertEquals(expected, wrapped.getExtraResult(Hierarchy.class));
			final List<LevelInfo> roots = expected.getReferenceHierarchy(REF_CATEGORY, "tree");
			assertEquals(1, roots.size());
			final LevelInfo root = roots.getFirst();
			assertEquals(CATEGORY_PK, root.entity().getPrimaryKey());
			assertEquals(1, root.queriedEntityCount());
			assertEquals(1, root.childrenCount());
		}
	}

	@Nested
	@DisplayName("Repeated histogram requirements")
	class DuplicateRequests {

		/**
		 * Refuses one price histogram requested for two different scope sets.
		 *
		 * @param wrappedOnly whether both copies are wrapped
		 * @param evita       the engine holding the shared fixture
		 */
		@ParameterizedTest(name = "both copies wrapped = {0}")
		@ValueSource(booleans = {true, false})
		@DisplayName("Refuses duplicate price histograms with different scopes")
		@UseDataSet(FIXTURE)
		void shouldRefusePriceHistogramWhenScopesDiffer(boolean wrappedOnly, @Nonnull Evita evita) {
			assertConflictingScopes(
				"price histogram",
				() -> execute(
					evita, Scope.values(), null, null, null,
					inScope(LIVE, priceHistogram(5)),
					wrappedOnly ? inScope(ARCHIVED, priceHistogram(5)) : priceHistogram(5)
				),
				wrappedOnly
			);
		}

		/**
		 * Refuses one attribute histogram requested for two different scope sets.
		 *
		 * @param wrappedOnly whether both copies are wrapped
		 * @param evita       the engine holding the shared fixture
		 */
		@ParameterizedTest(name = "both copies wrapped = {0}")
		@ValueSource(booleans = {true, false})
		@DisplayName("Refuses duplicate attribute histograms with different scopes")
		@UseDataSet(FIXTURE)
		void shouldRefuseAttributeHistogramWhenScopesDiffer(boolean wrappedOnly, @Nonnull Evita evita) {
			assertConflictingScopes(
				ATTRIBUTE_METRIC,
				() -> execute(
					evita, Scope.values(), null, null, null,
					inScope(LIVE, attributeHistogram(5, ATTRIBUTE_METRIC)),
					wrappedOnly ? inScope(ARCHIVED, attributeHistogram(5, ATTRIBUTE_METRIC))
						: attributeHistogram(5, ATTRIBUTE_METRIC)
				),
				wrappedOnly
			);
		}

		/**
		 * Accepts repeated requests for the same scope set and answers them as one request.
		 *
		 * @param scope the only searched scope, also named by the wrapped copies
		 * @param evita the engine holding the shared fixture
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(Scope.class)
		@DisplayName("Accepts wrapped and unwrapped requests for the same scope set")
		@UseDataSet(FIXTURE)
		void shouldAcceptDuplicateHistogramsWhenScopesAreEqual(@Nonnull Scope scope, @Nonnull Evita evita) {
			final RequireConstraint price = priceHistogram(5);
			final RequireConstraint attribute = attributeHistogram(5, ATTRIBUTE_METRIC);
			final RequireConstraint reference = referenceHistogram(REF_PARAM);
			final Scope[] one = {scope};

			final EvitaResponse<EntityReferenceContract> single =
				execute(evita, one, null, null, null, price, attribute, reference);
			final EvitaResponse<EntityReferenceContract> repeated = execute(
				evita, one, null, null, null, price, attribute, reference, inScope(scope, price, attribute, reference)
			);

			assertEquals(singleValueHistogram(priceOf(scope)), priceHistogramOf(single));
			assertEquals(singleValueHistogram(metricOf(scope)), metricHistogramOf(single));
			assertEquals(priceHistogramOf(single), priceHistogramOf(repeated));
			assertEquals(metricHistogramOf(single), metricHistogramOf(repeated));
			assertEquals(
				describeHistogram(referenceHistogramOf(single, REF_PARAM)),
				describeHistogram(referenceHistogramOf(repeated, REF_PARAM))
			);
		}

		/**
		 * Accepts two wrapped copies naming the same scope in a query searching both scopes.
		 *
		 * @param scope the scope both copies name
		 * @param evita the engine holding the shared fixture
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(Scope.class)
		@DisplayName("Accepts two wrapped requests naming the same scope")
		@UseDataSet(FIXTURE)
		void shouldAcceptRepeatedWrappedHistogramsNamingTheSameScope(@Nonnull Scope scope, @Nonnull Evita evita) {
			final RequireConstraint price = priceHistogram(5);
			final RequireConstraint attribute = attributeHistogram(5, ATTRIBUTE_METRIC);
			final RequireConstraint reference = referenceHistogram(REF_PARAM);

			final EvitaResponse<EntityReferenceContract> response = execute(
				evita, Scope.values(), null, null, null,
				inScope(scope, price, attribute, reference), inScope(scope, price, attribute, reference)
			);

			assertEquals(singleValueHistogram(priceOf(scope)), priceHistogramOf(response));
			assertEquals(singleValueHistogram(metricOf(scope)), metricHistogramOf(response));
			final int share = scope == LIVE ? 3 : 7;
			final int boundaryPk = scope == LIVE ? 100 : 101;
			assertReferenceHistogram(
				referenceHistogramOf(response, REF_PARAM), 1, share, share, boundaryPk, boundaryPk
			);
		}

		/**
		 * Requests different attributes in two separate scopes.
		 *
		 * @param evita the engine holding the shared fixture
		 */
		@Test
		@DisplayName("Computes each attribute histogram in its own scope")
		@UseDataSet(FIXTURE)
		void shouldAcceptDifferentAttributesWhenScopesDiffer(@Nonnull Evita evita) {
			final AttributeHistogram result = execute(
				evita, Scope.values(), null, null, null,
				inScope(LIVE, attributeHistogram(5, ATTRIBUTE_METRIC)),
				inScope(ARCHIVED, attributeHistogram(5, "otherMetric"))
			).getExtraResult(AttributeHistogram.class);
			assertNotNull(result);
			assertEquals(
				"count=1, min=1, max=1, buckets=[1: 1]", describeHistogram(result.getHistogram(ATTRIBUTE_METRIC))
			);
			assertEquals(
				"count=1, min=15, max=15, buckets=[15: 1]", describeHistogram(result.getHistogram("otherMetric"))
			);
		}
	}

	@Nested
	@DisplayName("Histograms of the entities returned by cross-scope filters")
	class ReturnedEntities {

		/**
		 * Compares the price histogram to the returned live owners of an archived brand.
		 *
		 * @param evita the engine holding the shared fixture
		 */
		@Test
		@DisplayName("Counts a live owner matching through an archived brand in the price histogram")
		@UseDataSet(FIXTURE)
		void shouldCountReturnedLiveOwnerInPriceHistogram(@Nonnull Evita evita) {
			final EvitaResponse<EntityReferenceContract> response = execute(
				evita, Scope.values(), archivedBrandFilter(), null, null, inScope(LIVE, priceHistogram(5))
			);
			assertEquals(List.of(PRODUCT_LIVE_PK), returnedLiveProducts(response));
			assertEquals("count=1, min=12.1, max=12.1, buckets=[12.1: 1]", priceHistogramOf(response));
		}

		/**
		 * Compares the attribute histogram to the returned live owners of an archived brand.
		 *
		 * @param evita the engine holding the shared fixture
		 */
		@Test
		@DisplayName("Counts a live owner matching through an archived brand in the attribute histogram")
		@UseDataSet(FIXTURE)
		void shouldCountReturnedLiveOwnerInAttributeHistogram(@Nonnull Evita evita) {
			final EvitaResponse<EntityReferenceContract> response = execute(
				evita, Scope.values(), archivedBrandFilter(), null, null,
				inScope(LIVE, attributeHistogram(5, ATTRIBUTE_METRIC))
			);
			assertEquals(List.of(PRODUCT_LIVE_PK), returnedLiveProducts(response));
			assertEquals("count=1, min=1, max=1, buckets=[1: 1]", metricHistogramOf(response));
		}

		/**
		 * Pins the excluded live owner under negation of the cross-scope filter.
		 *
		 * @param evita a fresh copy of the fixture
		 */
		@Test
		@DisplayName("Omits a live owner excluded by negating the archived brand match")
		@UseDataSet(value = WRITABLE_FIXTURE, destroyAfterTest = true)
		void shouldOmitLiveOwnerWhenTheBrandMatchIsNegated(@Nonnull Evita evita) {
			evita.updateCatalog(TEST_CATALOG, session -> {
				session.createNewEntity(ENTITY_PRODUCT, 3)
					.setAttribute(ATTRIBUTE_METRIC, 2)
					.setPrice(3, PRICE_LIST, CURRENCY, new BigDecimal("20"), TAX_RATE, new BigDecimal("24.2"), true)
					.setReference(REF_BRAND, BRAND_PK)
					.upsertVia(session);
			});
			final EvitaResponse<EntityReferenceContract> response = execute(
				evita, Scope.values(), not(archivedBrandFilter()), null, null,
				inScope(LIVE, priceHistogram(5), attributeHistogram(5, ATTRIBUTE_METRIC))
			);
			assertEquals(List.of(3), returnedLiveProducts(response));
			assertEquals("count=1, min=24.2, max=24.2, buckets=[24.2: 1]", priceHistogramOf(response));
			assertEquals("count=1, min=2, max=2, buckets=[2: 1]", metricHistogramOf(response));
		}
	}

	@Nested
	@DisplayName("A histogram ignores its own interval in the user filter")
	class Relaxation {

		/**
		 * Checks that relaxing the price interval retains the scope boundary and gives the histogram of a query
		 * searching the wrapped scope alone.
		 *
		 * @param scope the wrapped scope, whose product the interval excludes
		 * @param evita the engine holding the shared fixture
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(Scope.class)
		@DisplayName("Relaxes the price interval within the wrapped scope")
		@UseDataSet(FIXTURE)
		void shouldRelaxPriceIntervalWithinWrappedScope(@Nonnull Scope scope, @Nonnull Evita evita) {
			// the interval matches only the product of the other scope
			final FilterConstraint interval = scope == LIVE ?
				userFilter(priceBetween(new BigDecimal("100"), new BigDecimal("130"))) :
				userFilter(priceBetween(new BigDecimal("10"), new BigDecimal("15")));

			final EvitaResponse<EntityReferenceContract> response = execute(
				evita, Scope.values(), interval, null, null, inScope(scope, priceHistogram(5))
			);

			assertEquals(List.of(productOutside(scope)), returnedProducts(response));
			assertEquals(singleValueHistogram(priceOf(scope)), priceHistogramOf(response));
			assertFalse(response.getExtraResult(PriceHistogram.class).getBuckets()[0].requested());
			assertEquals(
				priceHistogramOf(execute(evita, new Scope[]{scope}, interval, null, null, priceHistogram(5))),
				priceHistogramOf(response)
			);
		}

		/**
		 * Checks that relaxing the attribute interval retains the scope boundary and gives the histogram of a query
		 * searching the wrapped scope alone.
		 *
		 * @param scope the wrapped scope, whose product the interval excludes
		 * @param evita the engine holding the shared fixture
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(Scope.class)
		@DisplayName("Relaxes the attribute interval within the wrapped scope")
		@UseDataSet(FIXTURE)
		void shouldRelaxAttributeIntervalWithinWrappedScope(@Nonnull Scope scope, @Nonnull Evita evita) {
			// the interval matches only the product of the other scope
			final int otherMetric = scope == LIVE ? 5 : 1;
			final FilterConstraint interval = userFilter(attributeBetween(ATTRIBUTE_METRIC, otherMetric, otherMetric));

			final EvitaResponse<EntityReferenceContract> response = execute(
				evita, Scope.values(), interval, null, null, inScope(scope, attributeHistogram(5, ATTRIBUTE_METRIC))
			);

			assertEquals(List.of(productOutside(scope)), returnedProducts(response));
			assertEquals(singleValueHistogram(metricOf(scope)), metricHistogramOf(response));
			final AttributeHistogram histograms = response.getExtraResult(AttributeHistogram.class);
			assertNotNull(histograms);
			assertFalse(histograms.getHistogram(ATTRIBUTE_METRIC).getBuckets()[0].requested());
			assertEquals(
				metricHistogramOf(execute(
					evita, new Scope[]{scope}, interval, null, null, attributeHistogram(5, ATTRIBUTE_METRIC)
				)),
				metricHistogramOf(response)
			);
		}
	}

	@Nested
	@DisplayName("Price histogram execution")
	class PriceExecution {

		/**
		 * Exercises the per-inner-record histogram path.
		 *
		 * @param evita a fresh copy of the fixture
		 */
		@Test
		@DisplayName("Counts the selling price of each inner record in the wrapped scope")
		@UseDataSet(value = WRITABLE_FIXTURE, destroyAfterTest = true)
		void shouldRestrictLowestPriceInnerRecordsToWrappedScope(@Nonnull Evita evita) {
			evita.updateCatalog(TEST_CATALOG, session -> {
				for (final int pk : new int[]{3, 4}) {
					session.createNewEntity(ENTITY_PRODUCT, pk)
						.setPriceInnerRecordHandling(PriceInnerRecordHandling.LOWEST_PRICE)
						.setPrice(3, PRICE_LIST, CURRENCY, 1, BigDecimal.TEN, TAX_RATE, new BigDecimal("12.1"), true)
						.setPrice(
							4, PRICE_LIST, CURRENCY, 2, new BigDecimal("20"), TAX_RATE, new BigDecimal("24.2"), true
						)
						.upsertVia(session);
				}
				session.archiveEntity(ENTITY_PRODUCT, 4);
			});
			final FilterConstraint variants = entityPrimaryKeyInSet(3, 4);
			final EvitaResponse<EntityReferenceContract> single =
				execute(evita, new Scope[]{LIVE}, variants, null, null, priceHistogram(5));
			assertEquals(2, single.getExtraResult(PriceHistogram.class).getOverallCount());
			assertEquals(priceHistogramOf(single), priceHistogramOf(execute(
				evita, Scope.values(), variants, null, null, inScope(LIVE, priceHistogram(5))
			)));
		}

		/**
		 * Checks that price ordering preserves the wrapped histogram.
		 *
		 * @param evita a fresh copy of the fixture
		 */
		@Test
		@DisplayName("Keeps the price histogram scoped when sorting by price")
		@UseDataSet(value = WRITABLE_FIXTURE, destroyAfterTest = true)
		void shouldRestrictPriceHistogramWhenSortingByPrice(@Nonnull Evita evita) {
			evita.updateCatalog(TEST_CATALOG, session -> {
				session.getEntity(ENTITY_PRODUCT, PRODUCT_LIVE_PK, priceContentAll())
					.orElseThrow()
					.openForWrite()
					.setPrice(1, PRICE_LIST, CURRENCY, new BigDecimal("200"), TAX_RATE, new BigDecimal("242"), true)
					.upsertVia(session);
			});
			final EvitaResponse<EntityReferenceContract> response = execute(
				evita, Scope.values(), null, orderBy(priceNatural()), null, inScope(LIVE, priceHistogram(5))
			);
			assertEquals(List.of(PRODUCT_ARCHIVED_PK, PRODUCT_LIVE_PK), returnedProducts(response));
			assertEquals("count=1, min=242, max=242, buckets=[242: 1]", priceHistogramOf(response));
		}

		/**
		 * Checks scope restriction when a hierarchy filter supplies reduced indexes.
		 *
		 * @param scope the wrapped scope
		 * @param evita the engine holding the shared fixture
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(Scope.class)
		@DisplayName("Restricts the price histogram with a hierarchy filter")
		@UseDataSet(FIXTURE)
		void shouldRestrictPriceHistogramWhenUsingHierarchyIndexes(@Nonnull Scope scope, @Nonnull Evita evita) {
			final PlanPreference route = INDEX_SCAN;
			final FilterConstraint withinCategory = hierarchyWithin(REF_CATEGORY, entityPrimaryKeyInSet(CATEGORY_PK));
			final EvitaResponse<EntityReferenceContract> response = execute(
				evita, Scope.values(), withinCategory, null, route, inScope(scope, priceHistogram(5))
			);
			route.assertTaken(response);
			assertEquals(2, response.getTotalRecordCount());
			assertEquals(singleValueHistogram(priceOf(scope)), priceHistogramOf(response));
			// Both products reference the category, so selecting the product scope suffices for the control.
			assertEquals(
				describe(evita, new Scope[]{scope}, RequireInScopeHistogramFunctionalTest::priceHistogramOf,
					priceHistogram(5)),
				priceHistogramOf(response)
			);
		}

		/**
		 * Compares both preferred execution routes to the same price histogram.
		 *
		 * @param route the preferred route
		 * @param scope the wrapped scope
		 * @param evita the engine holding the shared fixture
		 */
		@ParameterizedTest(name = "{0} in {1}")
		@CsvSource({
			"PREFETCH,LIVE", "PREFETCH,ARCHIVED", "INDEX_SCAN,LIVE", "INDEX_SCAN,ARCHIVED"
		})
		@DisplayName("Keeps the wrapped price histogram on each execution route")
		@UseDataSet(FIXTURE)
		void shouldRestrictPriceHistogramOnEitherRoute(
			@Nonnull PlanPreference route, @Nonnull Scope scope, @Nonnull Evita evita
		) {
			final EvitaResponse<EntityReferenceContract> response = execute(
				evita, Scope.values(), null, null, route, inScope(scope, priceHistogram(5))
			);
			route.assertTaken(response);
			final String expected = describe(
				evita, new Scope[]{scope}, RequireInScopeHistogramFunctionalTest::priceHistogramOf, priceHistogram(5)
			);
			assertEquals(singleValueHistogram(priceOf(scope)), expected);
			assertEquals(expected, priceHistogramOf(response));
		}
	}

	@Nested
	@DisplayName("Reference histogram statistics")
	class ReferenceHistograms {

		/**
		 * Checks the controls for a query searching only one scope.
		 *
		 * @param referenceName the grouped or ungrouped reference
		 * @param searchedScope the scope the query searches
		 * @param value the expected bound
		 * @param boundaryPk the expected boundary entity primary key
		 * @param evita      the engine holding the shared fixture
		 */
		@ParameterizedTest(name = "{0} in {1}")
		@CsvSource({"params,LIVE,3,100", "params,ARCHIVED,7,101", "tags,LIVE,3,100", "tags,ARCHIVED,7,101"})
		@DisplayName("Returns only the histogram values of a single searched scope")
		@UseDataSet(FIXTURE)
		void shouldUseSingleQueriedScopeForReferenceHistogram(
			@Nonnull String referenceName, @Nonnull Scope searchedScope, int value, int boundaryPk, @Nonnull Evita evita
		) {
			assertReferenceHistogram(
				referenceHistogramOf(
					execute(evita, new Scope[]{searchedScope}, null, null, null, referenceHistogram(referenceName)),
					referenceName
				),
				1, value, value, boundaryPk, boundaryPk
			);
		}

		/**
		 * Checks aggregation and boundary retrieval across both scopes.
		 *
		 * @param referenceName the grouped or ungrouped reference
		 * @param evita         the engine holding the shared fixture
		 */
		@ParameterizedTest(name = "{0}")
		@ValueSource(strings = {REF_PARAM, REF_TAG})
		@DisplayName("Combines reference histogram values and boundaries from both scopes")
		@UseDataSet(FIXTURE)
		void shouldMergeReferenceHistogramAcrossSearchedScopes(@Nonnull String referenceName, @Nonnull Evita evita) {
			assertReferenceHistogram(
				referenceHistogramOf(
					execute(evita, Scope.values(), null, null, null, referenceHistogram(referenceName)), referenceName
				),
				2, 3, 7, 100, 101
			);
		}

		/**
		 * Checks isolation and boundary retrieval in the wrapped scope.
		 *
		 * @param referenceName the grouped or ungrouped reference
		 * @param wrappedScope the scope named in the requirement
		 * @param value the expected bound
		 * @param boundaryPk the expected boundary entity primary key
		 * @param evita      the engine holding the shared fixture
		 */
		@ParameterizedTest(name = "{0} in {1}")
		@CsvSource({"params,LIVE,3,100", "params,ARCHIVED,7,101", "tags,LIVE,3,100", "tags,ARCHIVED,7,101"})
		@DisplayName("Returns only the histogram values of the wrapped scope")
		@UseDataSet(FIXTURE)
		void shouldUseWrappedScopeForReferenceHistogram(
			@Nonnull String referenceName, @Nonnull Scope wrappedScope, int value, int boundaryPk, @Nonnull Evita evita
		) {
			assertReferenceHistogram(
				referenceHistogramOf(
					execute(
						evita, Scope.values(), null, null, null,
						inScope(wrappedScope, referenceHistogram(referenceName))
					),
					referenceName
				),
				1, value, value, boundaryPk, boundaryPk
			);
		}

		/**
		 * Refuses duplicate requests for a named reference histogram.
		 *
		 * @param referenceName the grouped or ungrouped reference
		 * @param wrappedOnly whether both copies are wrapped
		 * @param evita       the engine holding the shared fixture
		 */
		@ParameterizedTest(name = "{0}, both copies wrapped = {1}")
		@CsvSource({"params,false", "params,true", "tags,false", "tags,true"})
		@DisplayName("Refuses duplicate reference histograms with different scopes")
		@UseDataSet(FIXTURE)
		void shouldRefuseReferenceHistogramWhenScopesDiffer(
			@Nonnull String referenceName, boolean wrappedOnly, @Nonnull Evita evita
		) {
			final RequireConstraint histogram = referenceHistogram(referenceName);
			final RequireConstraint[] requirements = {
				inScope(LIVE, histogram), wrappedOnly ? inScope(ARCHIVED, histogram) : histogram
			};
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> execute(evita, Scope.values(), null, null, null, requirements)
			);
			assertScopeMessage(exception, SHARE_HISTOGRAM, wrappedOnly);
			assertTrue(exception.getMessage().contains(referenceName), exception::getMessage);
		}

		/**
		 * Computes a group only the live owner references and a group only the archived owner references, each from
		 * the one scope holding it.
		 *
		 * @param evita the engine holding the shared fixture
		 */
		@Test
		@DisplayName("Computes groups disjoint across the searched scopes, also the one found in the later scope only")
		@UseDataSet(FIXTURE)
		void shouldComputeGroupsDisjointAcrossSearchedScopes(@Nonnull Evita evita) {
			final EvitaResponse<EntityReferenceContract> response =
				execute(evita, Scope.values(), null, null, null, referenceHistogram(REF_LABEL));

			assertReferenceHistogram(referenceHistogramOf(response, REF_LABEL, GROUP_PK), 1, 3, 3, 100, 100);
			assertReferenceHistogram(referenceHistogramOf(response, REF_LABEL, OTHER_GROUP_PK), 1, 7, 7, 101, 101);
		}

		/**
		 * Finds each boundary entity of a referenced-entity attribute histogram in the scope of the entity: the
		 * smallest weight belongs to a live material, the largest to an archived one.
		 *
		 * @param evita the engine holding the shared fixture
		 */
		@Test
		@DisplayName("Finds the boundary entities of a referenced entity attribute in each searched scope")
		@UseDataSet(FIXTURE)
		void shouldFindReferencedEntityBoundariesInEachSearchedScope(@Nonnull Evita evita) {
			final EvitaResponse<EntityReferenceContract> response = execute(
				evita, Scope.values(), null, null, null,
				referenceSummaryOfReferenceWithHistograms(
					REF_MATERIAL, null, (EntityFetch) null, (EntityGroupFetch) null,
					histogramStatistics(10, entityFetch(), WEIGHT_HISTOGRAM)
				)
			);

			final ReferenceSummary summary = response.getExtraResult(ReferenceSummary.class);
			assertNotNull(summary);
			final ReferenceGroupStatistics group = summary.getReferenceGroupStatistics(REF_MATERIAL);
			assertNotNull(group);
			final HistogramContract histogram = group.getHistogramStatistics(WEIGHT_HISTOGRAM);
			assertNotNull(histogram);
			assertReferenceHistogram(histogram, 2, 2, 8, LIVE_MATERIAL_PK, ARCHIVED_MATERIAL_PK);
		}

		/**
		 * Ties two ungrouped boundary candidates while counting only the owner of the higher primary key.
		 *
		 * @param source the source of the histogram observation
		 * @param evita the engine holding the tied boundary fixture
		 */
		@ParameterizedTest
		@ValueSource(strings = {REF_PARAM, REF_MATERIAL})
		@DisplayName("Uses the counted owner for a tied ungrouped histogram boundary")
		@UseDataSet(COUNTED_BOUNDARY_FIXTURE)
		void shouldUseCountedOwnerForUngroupedBoundary(@Nonnull String source, @Nonnull Evita evita) {
			final boolean referenceAttribute = REF_PARAM.equals(source);
			final String histogramName = referenceAttribute ? SHARE_HISTOGRAM : WEIGHT_HISTOGRAM;
			final EvitaResponse<EntityReferenceContract> response = execute(
				evita, new Scope[]{LIVE}, entityPrimaryKeyInSet(COUNTED_OWNER_PK), null, null,
				referenceSummaryOfReferenceWithHistograms(
					source, null, (EntityFetch) null, (EntityGroupFetch) null,
					histogramStatistics(10, entityFetch(), histogramName)
				)
			);

			assertEquals(List.of(COUNTED_OWNER_PK), returnedProducts(response));
			final ReferenceSummary summary = response.getExtraResult(ReferenceSummary.class);
			assertNotNull(summary);
			final ReferenceGroupStatistics group = summary.getReferenceGroupStatistics(source);
			assertNotNull(group);
			final HistogramContract histogram = group.getHistogramStatistics(histogramName);
			assertNotNull(histogram);
			final int value = referenceAttribute ? 3 : 2;
			final int boundary = referenceAttribute ? COUNTED_PARAM_PK : COUNTED_MATERIAL_PK;
			assertReferenceHistogram(histogram, 1, value, value, boundary, boundary);
		}

		/**
		 * Ties two ungrouped boundary candidates without any boundary fetch and expects the same boundary entity as
		 * with an empty one.
		 *
		 * @param source the source of the histogram observation
		 * @param evita the engine holding the tied boundary fixture
		 */
		@ParameterizedTest
		@ValueSource(strings = {REF_PARAM, REF_MATERIAL})
		@DisplayName("Uses the counted owner for a tied ungrouped histogram boundary without a boundary fetch")
		@UseDataSet(COUNTED_BOUNDARY_FIXTURE)
		void shouldUseCountedOwnerForUngroupedBoundaryWithoutFetch(@Nonnull String source, @Nonnull Evita evita) {
			final boolean referenceAttribute = REF_PARAM.equals(source);
			final String histogramName = referenceAttribute ? SHARE_HISTOGRAM : WEIGHT_HISTOGRAM;
			final EvitaResponse<EntityReferenceContract> response = execute(
				evita, new Scope[]{LIVE}, entityPrimaryKeyInSet(COUNTED_OWNER_PK), null, null,
				referenceSummaryOfReferenceWithHistograms(
					source, null, (EntityFetch) null, (EntityGroupFetch) null,
					histogramStatistics(10, histogramName)
				)
			);

			final ReferenceSummary summary = response.getExtraResult(ReferenceSummary.class);
			assertNotNull(summary);
			final ReferenceGroupStatistics group = summary.getReferenceGroupStatistics(source);
			assertNotNull(group);
			final HistogramContract histogram = group.getHistogramStatistics(histogramName);
			assertNotNull(histogram);
			assertTrue(histogram.getMinReferencedEntity().isPresent(), "a boundary entity is returned without a fetch");
			final int value = referenceAttribute ? 3 : 2;
			final int boundary = referenceAttribute ? COUNTED_PARAM_PK : COUNTED_MATERIAL_PK;
			assertReferenceHistogram(histogram, 1, value, value, boundary, boundary);
		}

		/**
		 * Resolves tied boundary targets by requested scope, then the facet order or primary key.
		 *
		 * @param referenceName the grouped or ungrouped reference to test
		 * @param firstScope the first scope in the query filter
		 * @param sorted whether to request a facet sorter
		 * @param expectedPk the winning entity in the first scope
		 * @param evita the engine holding targets in both scopes
		 */
		@ParameterizedTest
		@CsvSource({
			REF_PARAM + ", LIVE, true, 102", REF_PARAM + ", ARCHIVED, true, 101",
			REF_PARAM + ", LIVE, false, 100", REF_PARAM + ", ARCHIVED, false, 99",
			REF_TAG + ", LIVE, true, 102", REF_TAG + ", ARCHIVED, true, 101",
			REF_TAG + ", LIVE, false, 100", REF_TAG + ", ARCHIVED, false, 99"
		})
		@DisplayName("Picks tied boundary entities in the query's scope order")
		@UseDataSet(SCOPED_BOUNDARY_FIXTURE)
		void shouldPickBoundaryByRequestedScopeOrder(
			@Nonnull String referenceName,
			@Nonnull Scope firstScope,
			boolean sorted,
			int expectedPk,
			@Nonnull Evita evita
		) {
			final Scope[] scopes = {firstScope, firstScope == LIVE ? ARCHIVED : LIVE};
			final EvitaResponse<EntityReferenceContract> response = execute(
				evita, scopes, null, null, null,
				referenceSummaryOfReferenceWithHistograms(
					referenceName, FacetStatisticsDepth.NONE, null, null,
					sorted ? orderBy(attributeNatural("rank")) : null, null, null, null,
					histogramStatistics(10, entityFetch(), SHARE_HISTOGRAM)
				)
			);
			final HistogramContract histogram = REF_TAG.equals(referenceName) ?
				referenceHistogramOf(response, referenceName, GROUP_PK) : referenceHistogramOf(response, referenceName);
			assertReferenceHistogram(histogram, 2, 3, 3, expectedPk, expectedPk);
		}

		/**
		 * Uses the query's scope order even when only LIVE products supply histogram observations.
		 *
		 * @param evita the engine with a LIVE owner referencing an ARCHIVED boundary target
		 */
		@Test
		@DisplayName("Uses the query's target scope order for a histogram wrapped in LIVE")
		@UseDataSet(SCOPED_BOUNDARY_FIXTURE)
		void shouldUseQueryScopeOrderForWrappedBoundary(@Nonnull Evita evita) {
			final RequireConstraint histogram = inScope(LIVE, referenceSummaryOfReferenceWithHistograms(
				REF_TAG, FacetStatisticsDepth.NONE, null, null, histogramStatistics(10, entityFetch(), SHARE_HISTOGRAM)
			));
			final HistogramContract liveFirst = referenceHistogramOf(
				execute(evita, new Scope[]{LIVE, ARCHIVED}, null, null, null, histogram), REF_TAG, GROUP_PK
			);
			final HistogramContract archivedFirst = referenceHistogramOf(
				execute(evita, new Scope[]{ARCHIVED, LIVE}, null, null, null, histogram), REF_TAG, GROUP_PK
			);

			assertAll(
				() -> assertReferenceHistogram(liveFirst, 1, 3, 3, 100, 100),
				() -> assertReferenceHistogram(archivedFirst, 1, 3, 3, 99, 99)
			);
		}

		/**
		 * Prefers an in-scope target when the lowest target PK belongs to an unsearched scope.
		 *
		 * @param evita the engine with a LIVE owner referencing both LIVE and ARCHIVED targets at the bound
		 */
		@Test
		@DisplayName("Picks an in-scope boundary when the query searches LIVE only")
		@UseDataSet(SCOPED_BOUNDARY_FIXTURE)
		void shouldPreferSearchedScopeForSingleScopeBoundary(@Nonnull Evita evita) {
			final EvitaResponse<EntityReferenceContract> response = execute(
				evita, new Scope[]{LIVE}, null, null, null, referenceHistogram(REF_TAG)
			);

			final HistogramContract histogram = referenceHistogramOf(response, REF_TAG, GROUP_PK);
			assertTrue(histogram.getMinReferencedEntity().isPresent(), "LIVE boundary entity must be present");
			assertReferenceHistogram(histogram, 1, 3, 3, 100, 100);
		}

		/**
		 * Prefers the LIVE material over an ARCHIVED material with the same weight and a lower primary key.
		 *
		 * @param evita the engine with a LIVE owner referencing both materials
		 */
		@Test
		@DisplayName("Prefers the LIVE material for a tied weight boundary in a LIVE-only query")
		@UseDataSet(SCOPED_BOUNDARY_FIXTURE)
		void shouldPreferSearchedScopeForReferencedEntityAttributeBoundary(@Nonnull Evita evita) {
			final EvitaResponse<EntityReferenceContract> response = execute(
				evita, new Scope[]{LIVE}, null, null, null,
				referenceSummaryOfReferenceWithHistograms(
					REF_MATERIAL, FacetStatisticsDepth.NONE, null, null,
					histogramStatistics(10, entityFetch(), WEIGHT_HISTOGRAM)
				)
			);
			final ReferenceSummary summary = response.getExtraResult(ReferenceSummary.class);
			assertNotNull(summary);
			final ReferenceGroupStatistics group = summary.getReferenceGroupStatistics(REF_MATERIAL);
			assertNotNull(group);
			final HistogramContract histogram = group.getHistogramStatistics(WEIGHT_HISTOGRAM);
			assertNotNull(histogram);
			assertReferenceHistogram(histogram, 1, 2, 2, LIVE_MATERIAL_PK, LIVE_MATERIAL_PK);
		}

		/**
		 * Applies specific ordering, filtering and group content to a histogram requested by the generic summary.
		 *
		 * @param evita the engine with tied boundaries and two groups
		 */
		@Test
		@DisplayName("Uses the reference-specific controls for a histogram requested by the generic summary")
		@UseDataSet(GROUP_CONTROLS_FIXTURE)
		void shouldUseSpecificControlsForGenericHistogram(@Nonnull Evita evita) {
			final EvitaResponse<EntityReferenceContract> response = execute(
				evita, new Scope[]{LIVE}, null, null, null,
				referenceSummaryWithHistograms(
					FacetStatisticsDepth.NONE, null, filterGroupBy(entityPrimaryKeyInSet(GROUP_PK, OTHER_GROUP_PK)),
					orderBy(entityPrimaryKeyNatural(OrderDirection.ASC)), null, null,
					entityGroupFetch(attributeContent("code")),
					histogramStatistics(10, entityFetch(), SHARE_HISTOGRAM)
				),
				referenceSummaryOfReference(
					REF_TAG, FacetStatisticsDepth.NONE, filterGroupBy(entityPrimaryKeyInSet(GROUP_PK)),
					orderBy(attributeNatural("rank", OrderDirection.DESC)), entityGroupFetch(attributeContent("name"))
				)
			);
			final ReferenceSummary summary = response.getExtraResult(ReferenceSummary.class);
			assertNotNull(summary);
			final ReferenceGroupStatistics group = summary.getReferenceGroupStatistics(REF_TAG, GROUP_PK);
			assertNotNull(group);
			final SealedEntity groupEntity = assertInstanceOf(SealedEntity.class, group.getGroupEntity());
			assertAll(
				() -> assertReferenceHistogram(group.getHistogramStatistics(SHARE_HISTOGRAM), 1, 3, 3, 102, 102),
				() -> assertNull(summary.getReferenceGroupStatistics(REF_TAG, OTHER_GROUP_PK)),
				() -> assertEquals("Selected", groupEntity.getAttribute("name")),
				() -> assertFalse(groupEntity.getAttributeNames().contains("code"))
			);
		}

		/**
		 * Keeps the LIVE histogram and its sorter independent of an ARCHIVED sibling in either position.
		 *
		 * @param evita the engine holding a writable fixture
		 */
		@Test
		@DisplayName("Keeps a histogram's boundary sort order when a sibling summary requests another scope")
		@UseDataSet(SORTED_BOUNDARY_FIXTURE)
		void shouldKeepHistogramBoundarySortOrderAcrossSiblingScopes(@Nonnull Evita evita) {
			final int sortedParam = 102;
			evita.updateCatalog(TEST_CATALOG, session -> {
				session.getEntity(ENTITY_PARAM, 100, attributeContentAll()).orElseThrow()
					.openForWrite().setAttribute("rank", 9).upsertVia(session);
				session.createNewEntity(ENTITY_PARAM, sortedParam).setAttribute("rank", 1).upsertVia(session);
				session.getEntity(ENTITY_PARAM, 101, attributeContentAll()).orElseThrow()
					.openForWrite().setAttribute("rank", 2).upsertVia(session);
				session.archiveEntity(ENTITY_PARAM, 101);
				session.getEntity(ENTITY_PRODUCT, PRODUCT_LIVE_PK, referenceContentAll()).orElseThrow()
					.openForWrite()
					.setReference(REF_PARAM, sortedParam, whichIs -> whichIs.setAttribute("share", 3))
					.upsertVia(session);
			});
			final OrderBy facetOrder = orderBy(attributeNatural("rank"));
			final RequireConstraint live = inScope(LIVE, referenceSummaryOfReferenceWithHistograms(
				REF_PARAM, null, null, null, facetOrder, null, null, null,
				histogramStatistics(10, entityFetch(), SHARE_HISTOGRAM)
			));
			final RequireConstraint archived = inScope(ARCHIVED, referenceSummaryOfReferenceWithHistograms(
				REF_PARAM, null, null, null, facetOrder, null, null, null,
				histogramStatistics(10, entityFetch(), ARCHIVED_SHARE_HISTOGRAM)
			));
			final HistogramContract single = referenceHistogramOf(
				execute(evita, Scope.values(), null, null, null, live), REF_PARAM
			);
			final HistogramContract liveFirst = referenceHistogramOf(
				execute(evita, Scope.values(), null, null, null, live, archived), REF_PARAM
			);
			final HistogramContract liveLast = referenceHistogramOf(
				execute(evita, Scope.values(), null, null, null, archived, live), REF_PARAM
			);

			assertReferenceHistogram(single, 1, 3, 3, sortedParam, sortedParam);
			assertAll(
				() -> assertArrayEquals(single.getBuckets(), liveFirst.getBuckets(), "LIVE summary listed first"),
				() -> assertArrayEquals(single.getBuckets(), liveLast.getBuckets(), "LIVE summary listed last"),
				() -> assertReferenceHistogram(liveFirst, 1, 3, 3, sortedParam, sortedParam),
				() -> assertReferenceHistogram(liveLast, 1, 3, 3, sortedParam, sortedParam)
			);
		}

	}

	/**
	 * Creates the cross-scope brand predicate.
	 *
	 * @return a filter matching product references to the archived brand
	 */
	@Nonnull
	private static FilterConstraint archivedBrandFilter() {
		return referenceHaving(REF_BRAND, entityHaving(attributeEquals("code", "b")));
	}

	/**
	 * Returns the primary keys of the returned products in the order of the response.
	 *
	 * @param response the query response
	 * @return primary keys of the returned products
	 */
	@Nonnull
	private static List<Integer> returnedProducts(@Nonnull EvitaResponse<EntityReferenceContract> response) {
		return response.getRecordData().stream().map(EntityReferenceContract::getPrimaryKey).toList();
	}

	/**
	 * Returns the fixture product that does not live in the scope.
	 *
	 * @param scope the scope the product does not live in
	 * @return the primary key of the product of the other scope
	 */
	private static int productOutside(@Nonnull Scope scope) {
		return scope == LIVE ? PRODUCT_ARCHIVED_PK : PRODUCT_LIVE_PK;
	}

	/**
	 * Restricts the returned product references to fixture products in the live scope.
	 *
	 * @param response the query response
	 * @return primary keys of the live returned products
	 */
	@Nonnull
	private static List<Integer> returnedLiveProducts(@Nonnull EvitaResponse<EntityReferenceContract> response) {
		return response.getRecordData().stream().map(EntityReferenceContract::getPrimaryKey)
			.filter(pk -> pk != PRODUCT_ARCHIVED_PK).toList();
	}

	/**
	 * Asserts that the query refuses scope conflicts for the named slot.
	 *
	 * @param slot the histogram slot expected in the message
	 * @param query the query expected to throw
	 * @param wrappedOnly whether both copies are wrapped
	 */
	private static void assertConflictingScopes(
		@Nonnull String slot, @Nonnull Executable query, boolean wrappedOnly
	) {
		assertScopeMessage(assertThrows(EvitaInvalidUsageException.class, query), slot, wrappedOnly);
	}

	/**
	 * Checks that the message identifies the histogram slot and both requested scope sets.
	 *
	 * @param exception the scope-conflict exception
	 * @param slot the histogram slot expected in the message
	 * @param wrappedOnly whether both copies are wrapped
	 */
	private static void assertScopeMessage(
		@Nonnull EvitaInvalidUsageException exception, @Nonnull String slot, boolean wrappedOnly
	) {
		final String message = exception.getMessage();
		assertTrue(message.contains(slot), () -> message);
		assertTrue(message.contains("[LIVE]"), () -> message);
		assertTrue(
			wrappedOnly ? message.contains("[ARCHIVED]") :
				message.contains("[LIVE, ARCHIVED]") || message.contains("[ARCHIVED, LIVE]"),
			() -> message
		);
	}

	/**
	 * Requests reference histogram statistics with their boundary entities.
	 *
	 * @param referenceName the grouped or ungrouped reference
	 * @return the reference summary requirement
	 */
	@Nonnull
	private static RequireConstraint referenceHistogram(@Nonnull String referenceName) {
		return referenceSummaryOfReferenceWithHistograms(
			referenceName, null, (EntityFetch) null, (EntityGroupFetch) null,
			histogramStatistics(10, entityFetch(), SHARE_HISTOGRAM)
		);
	}

	/**
	 * Extracts the requested histogram from its group: the group shared by both products for the grouped `tags`
	 * reference, the non-grouped entry for `params`.
	 *
	 * @param response the query response
	 * @param referenceName the grouped or ungrouped reference
	 * @return the histogram stored in the reference summary
	 */
	@Nonnull
	private static HistogramContract referenceHistogramOf(
		@Nonnull EvitaResponse<EntityReferenceContract> response, @Nonnull String referenceName
	) {
		return referenceHistogramOf(response, referenceName, REF_TAG.equals(referenceName) ? GROUP_PK : null);
	}

	/**
	 * Extracts the `share` histogram of one group of a reference.
	 *
	 * @param response the query response
	 * @param referenceName the grouped or ungrouped reference
	 * @param groupPk the group, or `null` for the non-grouped entry
	 * @return the histogram stored in the reference summary
	 */
	@Nonnull
	private static HistogramContract referenceHistogramOf(
		@Nonnull EvitaResponse<EntityReferenceContract> response, @Nonnull String referenceName,
		@Nullable Integer groupPk
	) {
		final ReferenceSummary summary = response.getExtraResult(ReferenceSummary.class);
		assertNotNull(summary);
		final ReferenceGroupStatistics group = groupPk == null ?
			summary.getReferenceGroupStatistics(referenceName) :
			summary.getReferenceGroupStatistics(referenceName, groupPk);
		assertNotNull(group);
		final HistogramContract histogram = group.getHistogramStatistics(SHARE_HISTOGRAM);
		assertNotNull(histogram);
		return histogram;
	}

	/**
	 * Verifies the histogram count, range and the fetched entities at the bounds.
	 *
	 * @param histogram the histogram of a reference summary
	 * @param count the total expected occurrences
	 * @param min the expected lower bound
	 * @param max the expected upper bound
	 * @param minPk the entity expected at the lower bound
	 * @param maxPk the entity expected at the upper bound
	 */
	private static void assertReferenceHistogram(
		@Nonnull HistogramContract histogram, int count, int min, int max, int minPk, int maxPk
	) {
		assertEquals(count, histogram.getOverallCount(), () -> describeHistogram(histogram));
		assertEquals(min, histogram.getMin().intValueExact());
		assertEquals(max, histogram.getMax().intValueExact());
		assertEquals(minPk, histogram.getMinReferencedEntity().orElseThrow().getPrimaryKey());
		assertEquals(maxPk, histogram.getMaxReferencedEntity().orElseThrow().getPrimaryKey());
	}
}
