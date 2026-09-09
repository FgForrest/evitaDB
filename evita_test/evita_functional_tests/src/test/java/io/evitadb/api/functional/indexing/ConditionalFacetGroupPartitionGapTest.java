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
import io.evitadb.dataType.Scope;
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
import static io.evitadb.api.query.QueryConstraints.groupHaving;
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
 * Guards the **group** half of the reduced-index invariant for a conditionally faceted reference that is
 * itself `FOR_FILTERING_AND_PARTITIONING` and grouped.
 *
 * Such a reference owns two independent families of reduced indexes, selected by
 * {@link ReferenceIndexedComponents}: the entity-side one keyed by the referenced entity
 * (`REFERENCED_ENTITY`, reached through the `REFERENCED_ENTITY_TYPE` index keyed by the referenced entity's
 * primary key) and the group-side one keyed by the group (`REFERENCED_GROUP_ENTITY`, reached through the
 * `REFERENCED_GROUP_ENTITY_TYPE` index keyed by the **group** primary key). They are not interchangeable —
 * one group spans many referenced entities — so a facet decision has to be written to both.
 *
 * The cross-entity re-evaluation path resolved only the entity-side family for the reference it was firing
 * for, which left the group-side partitions of that very reference stale in both directions: a facet turned
 * **on** never appeared there (false negative), and a facet turned **off** stayed behind (false positive).
 * Queries planned through `referenceHaving(..., groupHaving(...))` read exactly those partitions, so the
 * staleness is user-visible while a plain facet summary — served from the global index — looks correct. This
 * is the same failure class as [[ConditionalFacetReducedIndexGapTest]] (edee/eshop#2933), one index family
 * over.
 *
 * `unconditionalFacetReachesOwnGroupPartition` is the guard rail in the other direction: an ordinary
 * `faceted()` reference of the same shape must keep reaching its group partition through the synchronous
 * path, so a fix cannot simply reroute everything through the deferred one.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Conditional facet indexing — own group partition gap")
@Tag(CONTRACT)
@Tag(INDEXING)
@Tag(FACET)
class ConditionalFacetGroupPartitionGapTest implements EvitaTestSupport {

	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_PARAMETER = "parameter";
	private static final String ENTITY_PARAMETER_VALUE = "parameterValue";

	private static final String REF_PARAMETER_VALUES = "parameterValues";
	private static final String REF_PLAIN_VALUES = "plainValues";
	private static final String ATTR_INPUT_WIDGET_TYPE = "inputWidgetType";

	private static final int PRODUCT_PK = 1;
	private static final int PARAM_VALUE_PK = 10;
	private static final int PARAMETER_PK = 100;

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("ConditionalFacetGroupPartitionGapTest");
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
	 * False negative: the group entity starts matching only after the reference exists, so the decision is
	 * taken by {@code ReevaluateExpressionExecutor#processFacetTrigger} and has to reach the reference's own
	 * group partition, not just its entity partition.
	 */
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("cross-entity facet turned on must reach the reference's own group partition")
	void crossEntityFacetOnMustReachOwnGroupPartition(CatalogState state) {
		prepare(state, "INTERVAL_INPUT");
		tx(session -> session.createNewEntity(ENTITY_PRODUCT, PRODUCT_PK)
			.setReference(
				REF_PARAMETER_VALUES, PARAM_VALUE_PK,
				whichIs -> whichIs.setGroup(ENTITY_PARAMETER, PARAMETER_PK)
			)
			.upsertVia(session));

		assertFalse(
			isFaceted(globalIndex(), REF_PARAMETER_VALUES),
			"expression does not match yet -> facet must not be indexed"
		);

		turnWidgetTypeInto("CHECKBOX");

		assertTrue(isFaceted(globalIndex(), REF_PARAMETER_VALUES), "global index must carry the facet");
		assertTrue(
			isFaceted(valuePartition(REF_PARAMETER_VALUES), REF_PARAMETER_VALUES),
			"entity-side partition must carry the facet"
		);
		assertTrue(
			isFaceted(groupPartition(REF_PARAMETER_VALUES), REF_PARAMETER_VALUES),
			"group-side partition of the very same reference must carry the facet too"
		);
		assertEquals(
			1, countInGroupWithFacetSelected(),
			"selecting the facet inside the group must return the product"
		);
	}

	/**
	 * False positive, the mirror image: the group entity stops matching, so the facet must disappear from the
	 * group partition as well — a stale `true` there keeps returning a product that no longer qualifies.
	 */
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("cross-entity facet turned off must clear the reference's own group partition")
	void crossEntityFacetOffMustClearOwnGroupPartition(CatalogState state) {
		prepare(state, "CHECKBOX");
		tx(session -> session.createNewEntity(ENTITY_PRODUCT, PRODUCT_PK)
			.setReference(
				REF_PARAMETER_VALUES, PARAM_VALUE_PK,
				whichIs -> whichIs.setGroup(ENTITY_PARAMETER, PARAMETER_PK)
			)
			.upsertVia(session));

		assertTrue(
			isFaceted(groupPartition(REF_PARAMETER_VALUES), REF_PARAMETER_VALUES),
			"precondition: the deferred local path indexes the facet in the group partition"
		);

		turnWidgetTypeInto("INTERVAL_INPUT");

		assertFalse(isFaceted(globalIndex(), REF_PARAMETER_VALUES), "global index must drop the facet");
		assertFalse(
			isFaceted(valuePartition(REF_PARAMETER_VALUES), REF_PARAMETER_VALUES),
			"entity-side partition must drop the facet"
		);
		assertFalse(
			isFaceted(groupPartition(REF_PARAMETER_VALUES), REF_PARAMETER_VALUES),
			"group-side partition of the very same reference must drop the facet too"
		);
		assertEquals(
			0, countInGroupWithFacetSelected(),
			"the product no longer qualifies, so the facet selection must return nothing"
		);
	}

	/**
	 * Guard rail: the same shape without a condition is written synchronously and already reaches the group
	 * partition today. A fix must not regress it.
	 */
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("unconditional facet reaches the reference's own group partition")
	void unconditionalFacetReachesOwnGroupPartition(CatalogState state) {
		prepare(state, "CHECKBOX");
		tx(session -> session.createNewEntity(ENTITY_PRODUCT, PRODUCT_PK)
			.setReference(
				REF_PLAIN_VALUES, PARAM_VALUE_PK,
				whichIs -> whichIs.setGroup(ENTITY_PARAMETER, PARAMETER_PK)
			)
			.upsertVia(session));

		assertTrue(isFaceted(globalIndex(), REF_PLAIN_VALUES), "global index must carry the facet");
		assertTrue(
			isFaceted(valuePartition(REF_PLAIN_VALUES), REF_PLAIN_VALUES),
			"entity-side partition must carry the facet"
		);
		assertTrue(
			isFaceted(groupPartition(REF_PLAIN_VALUES), REF_PLAIN_VALUES),
			"group-side partition must carry the facet"
		);
	}

	/**
	 * Defines the schema: both references are partitioned and grouped, and index both components, so each
	 * owns an entity-side and a group-side family of reduced indexes.
	 *
	 * @param session    session to define the schema and fixtures in
	 * @param widgetType initial value of the group entity's `inputWidgetType` attribute
	 */
	private void defineSchemaAndFixtures(@Nonnull EvitaSessionContract session, @Nonnull String widgetType) {
		session.defineEntitySchema(ENTITY_PARAMETER)
			.withAttribute(ATTR_INPUT_WIDGET_TYPE, String.class, whichIs -> whichIs.filterable().nullable())
			.updateVia(session);
		session.defineEntitySchema(ENTITY_PARAMETER_VALUE).updateVia(session);

		session.defineEntitySchema(ENTITY_PRODUCT)
			// conditionally faceted, partitioned, grouped - both index families exist
			.withReferenceToEntity(
				REF_PARAMETER_VALUES, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
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
			// same shape, unconditionally faceted - the control
			.withReferenceToEntity(
				REF_PLAIN_VALUES, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.indexedWithComponents(
						ReferenceIndexedComponents.REFERENCED_ENTITY,
						ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					)
					.withGroupTypeRelatedToEntity(ENTITY_PARAMETER)
					.faceted()
			)
			.updateVia(session);

		session.createNewEntity(ENTITY_PARAMETER_VALUE, PARAM_VALUE_PK).upsertVia(session);
		session.createNewEntity(ENTITY_PARAMETER, PARAMETER_PK)
			.setAttribute(ATTR_INPUT_WIDGET_TYPE, widgetType)
			.upsertVia(session);
	}

	/**
	 * Creates the schema and the fixture entities and brings the catalog to the requested state.
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
	 * Rewrites the group entity's attribute, which is what fires the cross-entity facet trigger.
	 *
	 * @param widgetType new value of the group entity's `inputWidgetType` attribute
	 */
	private void turnWidgetTypeInto(@Nonnull String widgetType) {
		tx(session -> session.getEntity(ENTITY_PARAMETER, PARAMETER_PK, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setAttribute(ATTR_INPUT_WIDGET_TYPE, widgetType)
			.upsertVia(session));
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
	 * Counts products that survive selecting {@link #PARAM_VALUE_PK} as a facet inside group
	 * {@link #PARAMETER_PK}. `groupHaving` is what makes the query plan read the group partition.
	 *
	 * @return number of matching products
	 */
	private int countInGroupWithFacetSelected() {
		return this.evita.queryCatalog(
			TEST_CATALOG,
			(Function<EvitaSessionContract, Integer>) session -> session.query(
				query(
					collection(ENTITY_PRODUCT),
					filterBy(
						referenceHaving(
							REF_PARAMETER_VALUES,
							groupHaving(entityPrimaryKeyInSet(PARAMETER_PK))
						),
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

	/**
	 * The entity-side reduced index of the given reference, keyed by the referenced entity.
	 *
	 * @param referenceName reference whose partition is looked up
	 * @return the partition, or null when it does not exist
	 */
	@Nullable
	private EntityIndex valuePartition(@Nonnull String referenceName) {
		return IndexingTestSupport.getReferencedEntityIndex(
			getProductCollection(), referenceName, PARAM_VALUE_PK
		);
	}

	/**
	 * The group-side reduced index of the given reference, keyed by the group entity.
	 *
	 * @param referenceName reference whose partition is looked up
	 * @return the partition, or null when it does not exist
	 */
	@Nullable
	private EntityIndex groupPartition(@Nonnull String referenceName) {
		return IndexingTestSupport.getReferencedGroupEntityIndex(
			getProductCollection(), Scope.LIVE, referenceName, PARAMETER_PK
		);
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
