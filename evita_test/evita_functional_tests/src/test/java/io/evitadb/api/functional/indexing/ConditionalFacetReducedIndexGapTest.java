/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.api.functional.indexing;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.CatalogState;
import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.core.Evita;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.facet.FacetGroupIndex;
import io.evitadb.index.facet.FacetIdIndex;
import io.evitadb.index.facet.FacetReferenceIndex;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.EvitaTestSupport.TestPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.facetHaving;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.userFilter;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FACET;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the invariant edee/eshop#2933 broke: a reduced entity index built for a
 * `FOR_FILTERING_AND_PARTITIONING` reference must carry the facets of **every** faceted reference its members
 * hold — including a **conditionally** faceted one (`facetedPartially`) belonging to a sibling reference.
 *
 * A conditional facet is always written by the **deferred** re-evaluation path: at `InsertReferenceMutation`
 * time the reference is not yet persisted, so a cross-entity expression such as
 * `$reference.groupEntity?.attributes[...]` cannot resolve and the synchronous path writes nothing anywhere.
 * Both deferred paths — `ReferenceIndexMutator#reEvaluateFacetExpressionsInAllIndexes` for a local change and
 * `ReevaluateExpressionExecutor#processFacetTrigger` for a cross-entity one — used to update the global index
 * plus the reduced indexes **of the reference being re-evaluated**, and nothing else, leaving the owner's
 * other partitioned references (here `categories`) without the facet.
 *
 * The gap is invisible to a plain facet summary — that is served from the global index — and shows up the
 * moment a facet is actually selected, because the selection is evaluated against the category's reduced
 * index: the group vanishes from the summary and the filtered result set collapses to zero. Each test
 * therefore asserts the global index, then the sibling reduced index, then the query, so a regression points
 * at the level that broke.
 *
 * `unconditionalFacetReachesSiblingIndex` is the guard rail in the other direction: an ordinary `faceted()`
 * reference must keep reaching the sibling index through the synchronous path
 * (`EntityIndexLocalMutationExecutor#updateReferencesInReferenceIndex`), so the fix cannot simply reroute
 * everything through the deferred one.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Conditional facet indexing — sibling reduced index gap")
@Tag(CONTRACT)
@Tag(INDEXING)
@Tag(FACET)
class ConditionalFacetReducedIndexGapTest implements EvitaTestSupport {

	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_CATEGORY = "category";
	private static final String ENTITY_PARAMETER = "parameter";
	private static final String ENTITY_PARAMETER_VALUE = "parameterValue";

	private static final String REF_CATEGORIES = "categories";
	private static final String REF_PARAMETER_VALUES = "parameterValues";
	private static final String REF_PLAIN_VALUES = "plainValues";
	private static final String ATTR_INPUT_WIDGET_TYPE = "inputWidgetType";

	private static final int PRODUCT_PK = 1;
	private static final int CATEGORY_PK = 5;
	private static final int PARAM_VALUE_PK = 10;
	private static final int PARAMETER_PK = 100;

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("ConditionalFacetReducedIndexGapTest");
		this.evita = new Evita(getEvitaConfiguration());
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return newTestEvitaConfigurationBuilder(this.paths)
			.server(
				ServerOptions.builder()
					.closeSessionsAfterSecondsOfInactivity(-1)
					.build()
			)
			.build();
	}

	/**
	 * The reported defect: the product is already a member of the category index when its conditionally
	 * faceted reference arrives, so only the global index learns about the facet.
	 */
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("facet added after the owner joined the category must reach the category index")
	void conditionalFacetAddedAfterCategoryMembership(CatalogState state) {
		prepare(state, "CHECKBOX");
		// the product enters the category reduced index carrying no parameter value yet
		tx(session -> session.createNewEntity(ENTITY_PRODUCT, PRODUCT_PK)
			.setReference(REF_CATEGORIES, CATEGORY_PK)
			.upsertVia(session));
		// ... and only afterwards receives the conditionally faceted reference
		tx(session -> session.getEntity(ENTITY_PRODUCT, PRODUCT_PK, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setReference(
				REF_PARAMETER_VALUES, PARAM_VALUE_PK,
				whichIs -> whichIs.setGroup(ENTITY_PARAMETER, PARAMETER_PK)
			)
			.upsertVia(session));

		assertTrue(isFaceted(globalIndex(), REF_PARAMETER_VALUES), "global index must carry the facet");
		assertTrue(
			isFaceted(categoryIndex(), REF_PARAMETER_VALUES),
			"category reduced index must carry the facet as well"
		);
		assertEquals(
			1, countInCategoryWithFacetSelected(),
			"selecting the facet inside the category must still return the product"
		);
	}

	/**
	 * The cross-entity variant: both references are already in place and the *group entity* only later
	 * starts to satisfy the expression. Exercises {@code ReevaluateExpressionExecutor#processFacetTrigger}.
	 */
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("group entity turning the facet on must reach the category index")
	void groupEntityTurnsFacetOnLater(CatalogState state) {
		prepare(state, "INTERVAL_INPUT");
		tx(session -> session.createNewEntity(ENTITY_PRODUCT, PRODUCT_PK)
			.setReference(REF_CATEGORIES, CATEGORY_PK)
			.setReference(
				REF_PARAMETER_VALUES, PARAM_VALUE_PK,
				whichIs -> whichIs.setGroup(ENTITY_PARAMETER, PARAMETER_PK)
			)
			.upsertVia(session));

		assertFalse(
			isFaceted(globalIndex(), REF_PARAMETER_VALUES),
			"expression does not match yet -> facet must not be indexed"
		);

		tx(session -> session.getEntity(ENTITY_PARAMETER, PARAMETER_PK, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setAttribute(ATTR_INPUT_WIDGET_TYPE, "CHECKBOX")
			.upsertVia(session));

		assertTrue(
			isFaceted(globalIndex(), REF_PARAMETER_VALUES),
			"global index must carry the facet once the group entity matches"
		);
		assertTrue(
			isFaceted(categoryIndex(), REF_PARAMETER_VALUES),
			"category reduced index must carry the facet as well"
		);
		assertEquals(
			1, countInCategoryWithFacetSelected(),
			"selecting the facet inside the category must still return the product"
		);
	}

	/**
	 * Control: writing the references in the opposite order works today, because joining the category index
	 * runs `ReferenceIndexMutator#indexAllFacets`, which reads already-persisted references and evaluates the
	 * expression correctly. Guards against a fix that would break this ordering.
	 */
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("facet present before the owner joins the category is copied in")
	void conditionalFacetPresentBeforeCategoryMembership(CatalogState state) {
		prepare(state, "CHECKBOX");
		tx(session -> session.createNewEntity(ENTITY_PRODUCT, PRODUCT_PK)
			.setReference(
				REF_PARAMETER_VALUES, PARAM_VALUE_PK,
				whichIs -> whichIs.setGroup(ENTITY_PARAMETER, PARAMETER_PK)
			)
			.upsertVia(session));
		tx(session -> session.getEntity(ENTITY_PRODUCT, PRODUCT_PK, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setReference(REF_CATEGORIES, CATEGORY_PK)
			.upsertVia(session));

		assertTrue(isFaceted(globalIndex(), REF_PARAMETER_VALUES), "global index must carry the facet");
		assertTrue(
			isFaceted(categoryIndex(), REF_PARAMETER_VALUES),
			"category reduced index must carry the facet as well"
		);
		assertEquals(1, countInCategoryWithFacetSelected(), "facet selection must match");
	}

	/**
	 * Guard rail: an unconditionally faceted reference added in exactly the same order does reach the
	 * sibling reduced index today, because the synchronous path writes it immediately. A fix must not
	 * regress this.
	 */
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("unconditional facet added after category membership reaches the category index")
	void unconditionalFacetReachesSiblingIndex(CatalogState state) {
		prepare(state, "CHECKBOX");
		tx(session -> session.createNewEntity(ENTITY_PRODUCT, PRODUCT_PK)
			.setReference(REF_CATEGORIES, CATEGORY_PK)
			.upsertVia(session));
		tx(session -> session.getEntity(ENTITY_PRODUCT, PRODUCT_PK, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setReference(
				REF_PLAIN_VALUES, PARAM_VALUE_PK,
				whichIs -> whichIs.setGroup(ENTITY_PARAMETER, PARAMETER_PK)
			)
			.upsertVia(session));

		assertTrue(isFaceted(globalIndex(), REF_PLAIN_VALUES), "global index must carry the facet");
		assertTrue(
			isFaceted(categoryIndex(), REF_PLAIN_VALUES),
			"category reduced index must carry the unconditional facet"
		);
	}

	/**
	 * Defines the customer-shaped schema and the entities the product will point at.
	 *
	 * @param session    session to define the schema and fixtures in
	 * @param widgetType initial value of the group entity's `inputWidgetType` attribute
	 */
	private void defineSchemaAndFixtures(@Nonnull EvitaSessionContract session, @Nonnull String widgetType) {
		session.defineEntitySchema(ENTITY_CATEGORY).updateVia(session);
		session.defineEntitySchema(ENTITY_PARAMETER)
			.withAttribute(ATTR_INPUT_WIDGET_TYPE, String.class, whichIs -> whichIs.filterable().nullable())
			.updateVia(session);
		session.defineEntitySchema(ENTITY_PARAMETER_VALUE).updateVia(session);

		session.defineEntitySchema(ENTITY_PRODUCT)
			// the sibling reference that owns the reduced index the facet has to reach
			.withReferenceToEntity(
				REF_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.indexedForFilteringAndPartitioning().faceted()
			)
			// the conditionally faceted reference, exactly as the customer declares it
			.withReferenceToEntity(
				REF_PARAMETER_VALUES, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFiltering()
					.indexedWithComponents(
						ReferenceIndexedComponents.REFERENCED_ENTITY,
						ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					)
					.withGroupTypeRelatedToEntity(ENTITY_PARAMETER)
					.facetedPartially(
						ExpressionFactory.parse(
							"$reference.groupEntity?.attributes['" + ATTR_INPUT_WIDGET_TYPE + "'] == 'CHECKBOX'"
						)
					)
			)
			// same shape, but unconditionally faceted - the control
			.withReferenceToEntity(
				REF_PLAIN_VALUES, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFiltering()
					.indexedWithComponents(
						ReferenceIndexedComponents.REFERENCED_ENTITY,
						ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					)
					.withGroupTypeRelatedToEntity(ENTITY_PARAMETER)
					.faceted()
			)
			.updateVia(session);

		session.createNewEntity(ENTITY_CATEGORY, CATEGORY_PK).upsertVia(session);
		session.createNewEntity(ENTITY_PARAMETER_VALUE, PARAM_VALUE_PK).upsertVia(session);
		session.createNewEntity(ENTITY_PARAMETER, PARAMETER_PK)
			.setAttribute(ATTR_INPUT_WIDGET_TYPE, widgetType)
			.upsertVia(session);
	}

	/**
	 * Creates the schema and the fixture entities, and brings the catalog to the requested state. Every later
	 * write goes through {@link #tx(Consumer)} as its own session, which is how the customer's publication
	 * pipeline writes — batching them into one session would let the mutations collapse and could mask the
	 * defect.
	 *
	 * @param state      target catalog state
	 * @param widgetType initial value of the group entity's `inputWidgetType` attribute
	 */
	private void prepare(@Nonnull CatalogState state, @Nonnull String widgetType) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			(Consumer<EvitaSessionContract>) session -> {
				defineSchemaAndFixtures(session, widgetType);
				if (state == CatalogState.ALIVE) {
					session.goLiveAndClose();
				}
			}
		);
	}

	/**
	 * Executes a single unit of write work in its own session.
	 *
	 * @param work the mutations to apply
	 */
	private void tx(@Nonnull Consumer<EvitaSessionContract> work) {
		this.evita.updateCatalog(TEST_CATALOG, work);
	}

	/**
	 * Counts products of {@link #CATEGORY_PK} that remain after selecting {@link #PARAM_VALUE_PK} as a facet.
	 * This is the query the bug report describes; it is evaluated against the category's reduced index.
	 *
	 * @return number of matching products
	 */
	private int countInCategoryWithFacetSelected() {
		return this.evita.queryCatalog(
			TEST_CATALOG,
			(Function<EvitaSessionContract, Integer>) session -> session.query(
				query(
					collection(ENTITY_PRODUCT),
					filterBy(
						referenceHaving(REF_CATEGORIES, entityPrimaryKeyInSet(CATEGORY_PK)),
						userFilter(facetHaving(REF_PARAMETER_VALUES, entityPrimaryKeyInSet(PARAM_VALUE_PK)))
					),
					require(page(1, 1))
				),
				EntityReference.class
			).getTotalRecordCount()
		);
	}

	@Nullable
	private EntityIndex globalIndex() {
		return IndexingTestSupport.getGlobalIndex(getProductCollection());
	}

	@Nullable
	private EntityIndex categoryIndex() {
		return IndexingTestSupport.getReferencedEntityIndex(getProductCollection(), REF_CATEGORIES, CATEGORY_PK);
	}

	@Nonnull
	private EntityCollectionContract getProductCollection() {
		final CatalogContract catalog = this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		return catalog.getCollectionForEntity(ENTITY_PRODUCT).orElseThrow();
	}

	/**
	 * Returns true when {@link #PRODUCT_PK} is recorded in `index` as a facet of {@link #PARAM_VALUE_PK}
	 * under group {@link #PARAMETER_PK} for the given reference.
	 *
	 * @param index         index to inspect; may be null when it does not exist at all
	 * @param referenceName reference whose facet index is inspected
	 * @return facet index membership of the product
	 */
	private static boolean isFaceted(@Nullable EntityIndex index, @Nonnull String referenceName) {
		if (index == null) {
			return false;
		}
		final FacetReferenceIndex facetRefIndex = index.getFacetingEntities().get(referenceName);
		if (facetRefIndex == null) {
			return false;
		}
		final FacetGroupIndex facetGroupIndex = facetRefIndex.getFacetsInGroup(PARAMETER_PK);
		if (facetGroupIndex == null) {
			return false;
		}
		final FacetIdIndex facetIdIndex = facetGroupIndex.getFacetIdIndex(PARAM_VALUE_PK);
		return facetIdIndex != null && facetIdIndex.getRecords().contains(PRODUCT_PK);
	}
}
