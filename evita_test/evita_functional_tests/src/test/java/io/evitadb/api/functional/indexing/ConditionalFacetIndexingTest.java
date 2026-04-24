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
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaEditor;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaEditor;
import io.evitadb.core.Evita;
import io.evitadb.core.expression.query.NonTranslatableExpressionException;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Consumer;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for the conditional facet indexing infrastructure.
 * Verifies data access paths, trigger mechanisms, state transitions, fan-out,
 * and correct facet index maintenance when `facetedPartially` expressions are used.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Conditional facet indexing operations")
class ConditionalFacetIndexingTest implements EvitaTestSupport, IndexingTestSupport {

	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_PARAMETER = "parameter";
	private static final String ENTITY_PARAMETER_GROUP = "parameterGroup";
	private static final String ENTITY_TAG = "tag";

	private static final String REF_PARAM_BY_ENTITY_ATTR = "paramByEntityAttr";
	private static final String REF_PARAM_BY_REF_ATTR = "paramByRefAttr";
	private static final String REF_PARAM_BY_ASSOC_DATA = "paramByAssocData";
	private static final String REF_PARAM_BY_PARENT = "paramByParent";
	private static final String REF_PARAM_BY_GROUP_ATTR = "paramByGroupAttr";
	private static final String REF_PARAM_BY_REF_ENTITY_ATTR = "paramByRefEntityAttr";
	private static final String REF_PARAM_BY_REF_ENTITY_REF_ATTR = "paramByRefEntityRefAttr";
	private static final String REF_PARAM_BY_REF_ENTITY_SINGLE_REF_ATTR = "paramByRefEntitySingleRefAttr";
	private static final String REF_PARAM_BY_GROUP_ENTITY_REF_ATTR = "paramByGroupEntityRefAttr";
	private static final String REF_PARAM_BY_GROUP_ENTITY_SINGLE_REF_ATTR = "paramByGroupEntitySingleRefAttr";
	private static final String REF_PARAM_BY_MIXED_AND = "paramByMixedAnd";
	private static final String REF_PARAM_BY_PARENT_ATTR = "paramByParentAttr";
	private static final String REF_PARAM_BY_PARENT_REF_ATTR = "paramByParentRefAttr";
	private static final String REF_PARAM_BY_MULTI_SOURCE_OR = "paramByMultiSourceOr";
	private static final String REF_PARAM_BY_GROUP_ATTR_SECONDARY = "paramByGroupAttrSecondary";

	private static final String REF_SINGLE_TAG = "singleTag";

	private static final String ATTR_IS_ACTIVE = "isActive";
	private static final String ATTR_CODE = "code";
	private static final String ATTR_STATUS = "status";
	private static final String ATTR_PRIORITY = "priority";
	private static final String ATTR_WIDGET_TYPE = "widgetType";
	private static final String ATTR_WEIGHT = "weight";
	private static final String ASSOC_DATA_METADATA = "metadata";

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("ConditionalFacetIndexingTest");
		this.evita = new Evita(
			getEvitaConfiguration()
		);
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	/**
	 * Builds the standard Evita configuration pointing to the test directories.
	 *
	 * @return the Evita configuration for the test
	 */
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
	 * Defines all entity types and reference schemas needed for conditional facet indexing tests.
	 * Called at the beginning of each test's `updateCatalog` lambda.
	 *
	 * @param session the active evitaDB session
	 */
	private static void defineConditionalFacetSchema(@Nonnull EvitaSessionContract session) {
		// 1. Define Tag (simple entity — target for reference-on-referenced-entity tests)
		session.defineEntitySchema(ENTITY_TAG).updateVia(session);

		// 2. Define ParameterGroup (group entity)
		session.defineEntitySchema(ENTITY_PARAMETER_GROUP)
			.withAttribute(ATTR_WIDGET_TYPE, String.class, whichIs -> whichIs.filterable().nullable())
			.withReferenceToEntity(
				ENTITY_TAG, ENTITY_TAG, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFiltering()
					.withAttribute(ATTR_WEIGHT, Integer.class, whichAttr -> whichAttr.filterable().nullable())
			)
			.withReferenceToEntity(
				REF_SINGLE_TAG, ENTITY_TAG, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs
					.indexedForFiltering()
					.withAttribute(ATTR_WEIGHT, Integer.class, whichAttr -> whichAttr.filterable().nullable())
			)
			.updateVia(session);

		// 3. Define Parameter (referenced entity)
		session.defineEntitySchema(ENTITY_PARAMETER)
			.withAttribute(ATTR_STATUS, String.class, whichIs -> whichIs.filterable().nullable())
			.withAttribute(ATTR_PRIORITY, Integer.class, whichIs -> whichIs.filterable().nullable())
			.withReferenceToEntity(
				ENTITY_TAG, ENTITY_TAG, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFiltering()
					.withAttribute(ATTR_WEIGHT, Integer.class, whichAttr -> whichAttr.filterable().nullable())
			)
			.withReferenceToEntity(
				REF_SINGLE_TAG, ENTITY_TAG, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs
					.indexedForFiltering()
					.withAttribute(ATTR_WEIGHT, Integer.class, whichAttr -> whichAttr.filterable().nullable())
			)
			.updateVia(session);

		// 4. Define Product (owner entity with all reference types)
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withHierarchy()
			.withAttribute(ATTR_IS_ACTIVE, Boolean.class, whichIs -> whichIs.filterable().nullable())
			.withAttribute(ATTR_CODE, String.class, whichIs -> whichIs.filterable().nullable())
			.withAssociatedData(ASSOC_DATA_METADATA, String.class, AssociatedDataSchemaEditor::nullable)
			.withReferenceToEntity(
				REF_SINGLE_TAG, ENTITY_TAG, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs
					.indexedForFiltering()
					.withAttribute(ATTR_WEIGHT, Integer.class,
						whichAttr -> whichAttr.filterable().nullable())
			)

			// --- Single-path references ---

			// $entity.attributes['isActive'] path
			.withReferenceToEntity(
				REF_PARAM_BY_ENTITY_ATTR, ENTITY_PARAMETER, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.faceted()
					.facetedPartially(
						ExpressionFactory.parse("($entity.attributes['isActive'] ?? false) == true")
					)
			)

			// $reference.attributes['priority'] path
			.withReferenceToEntity(
				REF_PARAM_BY_REF_ATTR, ENTITY_PARAMETER, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.faceted()
					.withAttribute(ATTR_PRIORITY, Integer.class, AttributeSchemaEditor::filterable)
					.facetedPartially(
						ExpressionFactory.parse("$reference.attributes['priority'] > 0")
					)
			)

			// $entity.associatedData['metadata'] path
			.withReferenceToEntity(
				REF_PARAM_BY_ASSOC_DATA, ENTITY_PARAMETER, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.faceted()
					.facetedPartially(
						ExpressionFactory.parse("$entity.associatedData['metadata'] != null")
					)
			)

			// $entity.parentEntity path
			.withReferenceToEntity(
				REF_PARAM_BY_PARENT, ENTITY_PARAMETER, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.faceted()
					.facetedPartially(
						ExpressionFactory.parse("$entity.parentEntity != null")
					)
			)

			// $entity.parentEntity.attributes['code'] path (exercises parent body fetch)
			.withReferenceToEntity(
				REF_PARAM_BY_PARENT_ATTR, ENTITY_PARAMETER, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.faceted()
					.facetedPartially(
						ExpressionFactory.parse(
							"($entity.parentEntity?.attributes['code'] ?? '') == 'ROOT'"
						)
					)
			)

			// $entity.parentEntity.references['singleTag'].attributes['weight'] path
			// (ZERO_OR_ONE on Product — exercises parent entity proxy reference attribute access)
			.withReferenceToEntity(
				REF_PARAM_BY_PARENT_REF_ATTR, ENTITY_PARAMETER,
				Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.faceted()
					.facetedPartially(
						ExpressionFactory.parse(
							"($entity.parentEntity?.references['singleTag']"
								+ "?.attributes['weight'] ?? 0) > 5"
						)
					)
			)

			// $reference.groupEntity?.attributes['widgetType'] path
			.withReferenceToEntity(
				REF_PARAM_BY_GROUP_ATTR, ENTITY_PARAMETER, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.indexedWithComponents(ReferenceIndexedComponents.values())
					.faceted()
					.withGroupTypeRelatedToEntity(ENTITY_PARAMETER_GROUP)
					.facetedPartially(
						ExpressionFactory.parse(
							"($reference.groupEntity?.attributes['widgetType'] ?? '') == 'CHECKBOX'"
						)
					)
			)

			// $reference.referencedEntity.attributes['status'] path
			.withReferenceToEntity(
				REF_PARAM_BY_REF_ENTITY_ATTR, ENTITY_PARAMETER, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.faceted()
					.facetedPartially(
						ExpressionFactory.parse(
							"($reference.referencedEntity.attributes['status'] ?? '') == 'ACTIVE'"
						)
					)
			)

			// $reference.referencedEntity.references['tag']*.attributes['weight'] path
			.withReferenceToEntity(
				REF_PARAM_BY_REF_ENTITY_REF_ATTR, ENTITY_PARAMETER, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.faceted()
					.facetedPartially(
						ExpressionFactory.parse(
							"$reference.referencedEntity.references['tag'].any(($.attributes['weight'] ?? 0) > 5)"
						)
					)
			)

			// $reference.groupEntity?.references['tag']*.attributes['weight'] path
			.withReferenceToEntity(
				REF_PARAM_BY_GROUP_ENTITY_REF_ATTR, ENTITY_PARAMETER, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.indexedWithComponents(ReferenceIndexedComponents.values())
					.faceted()
					.withGroupTypeRelatedToEntity(ENTITY_PARAMETER_GROUP)
					.facetedPartially(
						ExpressionFactory.parse(
							"$reference.groupEntity?.references['tag']?.any(($.attributes['weight'] ?? 0) > 5)"
						)
					)
			)

			// $reference.referencedEntity.references['singleTag'].attributes['weight'] path
			// (EXACTLY_ONE cardinality — no spread operator needed)
			.withReferenceToEntity(
				REF_PARAM_BY_REF_ENTITY_SINGLE_REF_ATTR, ENTITY_PARAMETER,
				Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.faceted()
					.facetedPartially(
						ExpressionFactory.parse(
							"($reference.referencedEntity.references['singleTag']"
								+ "?.attributes['weight'] ?? 0) > 5"
						)
					)
			)

			// $reference.groupEntity?.references['singleTag'].attributes['weight'] path
			// (EXACTLY_ONE cardinality — no spread operator needed)
			.withReferenceToEntity(
				REF_PARAM_BY_GROUP_ENTITY_SINGLE_REF_ATTR, ENTITY_PARAMETER,
				Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.indexedWithComponents(ReferenceIndexedComponents.values())
					.faceted()
					.withGroupTypeRelatedToEntity(ENTITY_PARAMETER_GROUP)
					.facetedPartially(
						ExpressionFactory.parse(
							"($reference.groupEntity?.references['singleTag']"
								+ "?.attributes['weight'] ?? 0) > 5"
						)
					)
			)

			// --- Compound-expression references ---

			// AND of group entity + entity attribute
			.withReferenceToEntity(
				REF_PARAM_BY_MIXED_AND, ENTITY_PARAMETER, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.indexedWithComponents(ReferenceIndexedComponents.values())
					.faceted()
					.withGroupTypeRelatedToEntity(ENTITY_PARAMETER_GROUP)
					.facetedPartially(
						ExpressionFactory.parse(
							"($reference.groupEntity?.attributes['widgetType'] ?? '') == 'CHECKBOX'"
								+ " && ($entity.attributes['isActive'] ?? false) == true"
						)
					)
			)

			// OR of group entity + referenced entity
			.withReferenceToEntity(
				REF_PARAM_BY_MULTI_SOURCE_OR, ENTITY_PARAMETER, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.indexedWithComponents(ReferenceIndexedComponents.values())
					.faceted()
					.withGroupTypeRelatedToEntity(ENTITY_PARAMETER_GROUP)
					.facetedPartially(
						ExpressionFactory.parse(
							"($reference.groupEntity?.attributes['widgetType'] ?? '') == 'CHECKBOX'"
								+ " || ($reference.referencedEntity.attributes['status'] ?? '') == 'ACTIVE'"
						)
					)
			)

			// Second group-dependent reference (same expression as paramByGroupAttr)
			.withReferenceToEntity(
				REF_PARAM_BY_GROUP_ATTR_SECONDARY, ENTITY_PARAMETER, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.indexedWithComponents(ReferenceIndexedComponents.values())
					.faceted()
					.withGroupTypeRelatedToEntity(ENTITY_PARAMETER_GROUP)
					.facetedPartially(
						ExpressionFactory.parse(
							"($reference.groupEntity?.attributes['widgetType'] ?? '') == 'CHECKBOX'"
						)
					)
			)

			.updateVia(session);
	}

	/**
	 * Returns the Product entity collection from the current catalog.
	 *
	 * @return the Product collection
	 */
	@Nonnull
	private EntityCollectionContract getProductCollection() {
		final CatalogContract catalog = this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		return catalog.getCollectionForEntity(ENTITY_PRODUCT).orElseThrow();
	}

	/**
	 * Asserts that the specified owner entity is present in the facet index for the given
	 * reference name and facet primary key.
	 *
	 * @param collection    the entity collection to inspect
	 * @param referenceName the reference schema name
	 * @param facetPK       the primary key of the faceted entity (Parameter PK)
	 * @param groupPK       the group entity PK (null if ungrouped)
	 * @param ownerPK       the primary key of the owner entity (Product PK)
	 */
	private static void assertFacetIndexed(
		@Nonnull EntityCollectionContract collection,
		@Nonnull String referenceName,
		int facetPK,
		@Nullable Integer groupPK,
		int ownerPK
	) {
		final EntityIndex globalIndex = IndexingTestSupport.getGlobalIndex(collection);
		assertNotNull(globalIndex, "Global index must exist");

		final FacetReferenceIndex facetRefIndex = globalIndex.getFacetingEntities().get(referenceName);
		assertNotNull(
			facetRefIndex,
			"FacetReferenceIndex for '" + referenceName + "' must exist"
		);

		final FacetGroupIndex facetGroupIndex = facetRefIndex.getFacetsInGroup(groupPK);
		assertNotNull(
			facetGroupIndex,
			"FacetGroupIndex for group " + groupPK + " must exist"
		);

		final FacetIdIndex facetIdIndex = facetGroupIndex.getFacetIdIndex(facetPK);
		assertNotNull(
			facetIdIndex,
			"FacetIdIndex for facet PK " + facetPK + " must exist"
		);

		assertTrue(
			facetIdIndex.getRecords().contains(ownerPK),
			"Owner entity PK " + ownerPK + " should be in facet index for reference '"
				+ referenceName + "', facet PK " + facetPK
		);
	}

	/**
	 * Asserts that the specified owner entity is NOT present in the facet index for the given
	 * reference name and facet primary key. Handles cases where any level of the index chain
	 * may be absent (which also means "not indexed").
	 *
	 * @param collection    the entity collection to inspect
	 * @param referenceName the reference schema name
	 * @param facetPK       the primary key of the faceted entity (Parameter PK)
	 * @param groupPK       the group entity PK (null if ungrouped)
	 * @param ownerPK       the primary key of the owner entity (Product PK)
	 */
	private static void assertFacetNotIndexed(
		@Nonnull EntityCollectionContract collection,
		@Nonnull String referenceName,
		int facetPK,
		@Nullable Integer groupPK,
		int ownerPK
	) {
		final EntityIndex globalIndex = IndexingTestSupport.getGlobalIndex(collection);
		if (globalIndex == null) {
			return; // no global index = no facets at all
		}

		final FacetReferenceIndex facetRefIndex = globalIndex.getFacetingEntities().get(referenceName);
		if (facetRefIndex == null) {
			return; // no FacetReferenceIndex for this reference = not indexed
		}

		final FacetGroupIndex facetGroupIndex = facetRefIndex.getFacetsInGroup(groupPK);
		if (facetGroupIndex == null) {
			return; // no FacetGroupIndex for this group = not indexed
		}

		final FacetIdIndex facetIdIndex = facetGroupIndex.getFacetIdIndex(facetPK);
		if (facetIdIndex == null) {
			return; // no FacetIdIndex for this facet PK = not indexed
		}

		assertFalse(
			facetIdIndex.getRecords().contains(ownerPK),
			"Owner entity PK " + ownerPK + " should NOT be in facet index for reference '"
				+ referenceName + "', facet PK " + facetPK
		);
	}

	/**
	 * Asserts that the specified owner entity is still present in the reduced entity index
	 * for the given reference, confirming that reference-based filtering still works even
	 * when the facet is conditionally excluded.
	 *
	 * @param collection    the entity collection to inspect
	 * @param referenceName the reference schema name
	 * @param refPK         the primary key of the referenced entity
	 * @param ownerPK       the primary key of the owner entity (Product PK)
	 */
	private static void assertReferenceStillIndexed(
		@Nonnull EntityCollectionContract collection,
		@Nonnull String referenceName,
		int refPK,
		int ownerPK
	) {
		final EntityIndex reducedIndex = IndexingTestSupport.getReferencedEntityIndex(
			collection, referenceName, refPK
		);
		assertNotNull(
			reducedIndex,
			"Reduced entity index for reference '" + referenceName
				+ "' PK " + refPK + " must exist"
		);
		assertTrue(
			reducedIndex.getAllPrimaryKeys().contains(ownerPK),
			"Owner entity PK " + ownerPK + " should be in reduced index for reference '"
				+ referenceName + "', referenced PK " + refPK
		);
	}

	/**
	 * Executes a test in the specified catalog state. The fixture setup always runs in WARMING_UP
	 * (bulk mode), then the catalog optionally transitions to ALIVE before the test logic executes.
	 * This allows every test to verify behavior in both non-transactional and transactional modes.
	 *
	 * @param targetState  the catalog state in which the test logic should execute
	 * @param fixtureSetup schema definition and initial entity creation (always runs in WARMING_UP)
	 * @param testLogic    assertions and mutations that exercise the scenario under test
	 */
	private void withCatalogInState(
		@Nonnull CatalogState targetState,
		@Nonnull Consumer<EvitaSessionContract> fixtureSetup,
		@Nonnull Consumer<EvitaSessionContract> testLogic
	) {
		if (targetState == CatalogState.WARMING_UP) {
			this.evita.updateCatalog(TEST_CATALOG, session -> {
				fixtureSetup.accept(session);
				testLogic.accept(session);
			});
		} else {
			this.evita.updateCatalog(TEST_CATALOG, session -> {
				fixtureSetup.accept(session);
				session.goLiveAndClose();
			});
			this.evita.updateCatalog(TEST_CATALOG, testLogic);
		}
	}

	/**
	 * Tests verifying correct initial facet index state for each supported data access path
	 * (entity attribute, reference attribute, associated data, parent, group entity, referenced entity, etc.).
	 */
	@Nested
	@DisplayName("Initial indexing — one test per data access path")
	class InitialIndexingTest {

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should index facet conditionally based on entity attribute")
		void shouldIndexFacetConditionallyBasedOnEntityAttribute(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalFacetSchema(session);

					// create referenced parameter
					session.createNewEntity(ENTITY_PARAMETER, 1).upsertVia(session);

					// product with isActive=true → expression TRUE
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setAttribute(ATTR_IS_ACTIVE, true)
						.setReference(REF_PARAM_BY_ENTITY_ATTR, 1)
						.upsertVia(session);

					// product with isActive=false → expression FALSE
					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setAttribute(ATTR_IS_ACTIVE, false)
						.setReference(REF_PARAM_BY_ENTITY_ATTR, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertFacetIndexed(productCollection, REF_PARAM_BY_ENTITY_ATTR, 1, null, 1);
					assertFacetNotIndexed(productCollection, REF_PARAM_BY_ENTITY_ATTR, 1, null, 2);
					assertReferenceStillIndexed(
						productCollection, REF_PARAM_BY_ENTITY_ATTR, 1, 2
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should index facet conditionally based on reference attribute")
		void shouldIndexFacetConditionallyBasedOnReferenceAttribute(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalFacetSchema(session);

					session.createNewEntity(ENTITY_PARAMETER, 1).upsertVia(session);

					// product with ref attr priority=5 → expression TRUE
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_PARAM_BY_REF_ATTR, 1,
							whichIs -> whichIs.setAttribute(ATTR_PRIORITY, 5)
						)
						.upsertVia(session);

					// product with ref attr priority=-1 → expression FALSE
					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setReference(
							REF_PARAM_BY_REF_ATTR, 1,
							whichIs -> whichIs.setAttribute(ATTR_PRIORITY, -1)
						)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertFacetIndexed(productCollection, REF_PARAM_BY_REF_ATTR, 1, null, 1);
					assertFacetNotIndexed(productCollection, REF_PARAM_BY_REF_ATTR, 1, null, 2);
					assertReferenceStillIndexed(
						productCollection, REF_PARAM_BY_REF_ATTR, 1, 2
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should index facet conditionally based on associated data")
		void shouldIndexFacetConditionallyBasedOnAssociatedData(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalFacetSchema(session);

					session.createNewEntity(ENTITY_PARAMETER, 1).upsertVia(session);

					// product with metadata → expression TRUE
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setAssociatedData(ASSOC_DATA_METADATA, "some-value")
						.setReference(REF_PARAM_BY_ASSOC_DATA, 1)
						.upsertVia(session);

					// product without metadata → expression FALSE
					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setReference(REF_PARAM_BY_ASSOC_DATA, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertFacetIndexed(productCollection, REF_PARAM_BY_ASSOC_DATA, 1, null, 1);
					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_ASSOC_DATA, 1, null, 2
					);
					assertReferenceStillIndexed(
						productCollection, REF_PARAM_BY_ASSOC_DATA, 1, 2
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should index facet conditionally based on entity parent")
		void shouldIndexFacetConditionallyBasedOnEntityParent(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalFacetSchema(session);

					session.createNewEntity(ENTITY_PARAMETER, 1).upsertVia(session);

					// product PK=1 as hierarchy root (used as parent target)
					session.createNewEntity(ENTITY_PRODUCT, 1).upsertVia(session);

					// product with parent → expression TRUE
					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setParent(1)
						.setReference(REF_PARAM_BY_PARENT, 1)
						.upsertVia(session);

					// product without parent → expression FALSE
					session.createNewEntity(ENTITY_PRODUCT, 3)
						.setReference(REF_PARAM_BY_PARENT, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertFacetIndexed(productCollection, REF_PARAM_BY_PARENT, 1, null, 2);
					assertFacetNotIndexed(productCollection, REF_PARAM_BY_PARENT, 1, null, 3);
					assertReferenceStillIndexed(
						productCollection, REF_PARAM_BY_PARENT, 1, 3
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should index facet conditionally based on parent entity attribute")
		void shouldIndexFacetConditionallyBasedOnParentEntityAttribute(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalFacetSchema(session);

					session.createNewEntity(ENTITY_PARAMETER, 1).upsertVia(session);

					// product PK=1 as hierarchy root with matching code
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setAttribute(ATTR_CODE, "ROOT")
						.upsertVia(session);

					// product PK=2 as hierarchy root with non-matching code
					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setAttribute(ATTR_CODE, "OTHER")
						.upsertVia(session);

					// product with parent whose code is 'ROOT' → expression TRUE
					session.createNewEntity(ENTITY_PRODUCT, 3)
						.setParent(1)
						.setReference(REF_PARAM_BY_PARENT_ATTR, 1)
						.upsertVia(session);

					// product with parent whose code is 'OTHER' → expression FALSE
					session.createNewEntity(ENTITY_PRODUCT, 4)
						.setParent(2)
						.setReference(REF_PARAM_BY_PARENT_ATTR, 1)
						.upsertVia(session);

					// product without parent → expression FALSE (null-safe)
					session.createNewEntity(ENTITY_PRODUCT, 5)
						.setReference(REF_PARAM_BY_PARENT_ATTR, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertFacetIndexed(
						productCollection, REF_PARAM_BY_PARENT_ATTR, 1, null, 3
					);
					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_PARENT_ATTR, 1, null, 4
					);
					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_PARENT_ATTR, 1, null, 5
					);
					assertReferenceStillIndexed(
						productCollection, REF_PARAM_BY_PARENT_ATTR, 1, 4
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should index facet conditionally based on parent entity reference attribute")
		void shouldIndexFacetConditionallyBasedOnParentEntityReferenceAttribute(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalFacetSchema(session);

					session.createNewEntity(ENTITY_TAG, 1).upsertVia(session);
					session.createNewEntity(ENTITY_PARAMETER, 1).upsertVia(session);

					// product PK=1 (root) with singleTag weight=10 → matching
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_SINGLE_TAG, 1,
							whichIs -> whichIs.setAttribute(ATTR_WEIGHT, 10)
						)
						.upsertVia(session);

					// product PK=2 (root) with singleTag weight=2 → non-matching
					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setReference(
							REF_SINGLE_TAG, 1,
							whichIs -> whichIs.setAttribute(ATTR_WEIGHT, 2)
						)
						.upsertVia(session);

					// child of PK=1 → parent weight 10 > 5 → TRUE
					session.createNewEntity(ENTITY_PRODUCT, 3)
						.setParent(1)
						.setReference(REF_PARAM_BY_PARENT_REF_ATTR, 1)
						.upsertVia(session);

					// child of PK=2 → parent weight 2 ≤ 5 → FALSE
					session.createNewEntity(ENTITY_PRODUCT, 4)
						.setParent(2)
						.setReference(REF_PARAM_BY_PARENT_REF_ATTR, 1)
						.upsertVia(session);

					// no parent → null-safe → FALSE
					session.createNewEntity(ENTITY_PRODUCT, 5)
						.setReference(REF_PARAM_BY_PARENT_REF_ATTR, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertFacetIndexed(
						productCollection,
						REF_PARAM_BY_PARENT_REF_ATTR, 1, null, 3
					);
					assertFacetNotIndexed(
						productCollection,
						REF_PARAM_BY_PARENT_REF_ATTR, 1, null, 4
					);
					assertFacetNotIndexed(
						productCollection,
						REF_PARAM_BY_PARENT_REF_ATTR, 1, null, 5
					);
					assertReferenceStillIndexed(
						productCollection,
						REF_PARAM_BY_PARENT_REF_ATTR, 1, 4
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should index facet conditionally based on group entity attribute")
		void shouldIndexFacetConditionallyBasedOnGroupEntityAttribute(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalFacetSchema(session);

					// matching group
					session.createNewEntity(ENTITY_PARAMETER_GROUP, 1)
						.setAttribute(ATTR_WIDGET_TYPE, "CHECKBOX")
						.upsertVia(session);
					// non-matching group
					session.createNewEntity(ENTITY_PARAMETER_GROUP, 2)
						.setAttribute(ATTR_WIDGET_TYPE, "RADIO")
						.upsertVia(session);

					session.createNewEntity(ENTITY_PARAMETER, 1).upsertVia(session);

					// product with matching group → expression TRUE
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_PARAM_BY_GROUP_ATTR, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER_GROUP, 1)
						)
						.upsertVia(session);

					// product with non-matching group → expression FALSE
					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setReference(
							REF_PARAM_BY_GROUP_ATTR, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER_GROUP, 2)
						)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertFacetIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 1, 1, 1
					);
					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 1, 2, 2
					);
					assertReferenceStillIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 1, 2
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should index facet conditionally based on referenced entity attribute")
		void shouldIndexFacetConditionallyBasedOnReferencedEntityAttribute(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalFacetSchema(session);

					// parameter with matching status
					session.createNewEntity(ENTITY_PARAMETER, 1)
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.upsertVia(session);
					// parameter with non-matching status
					session.createNewEntity(ENTITY_PARAMETER, 2)
						.setAttribute(ATTR_STATUS, "INACTIVE")
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_PARAM_BY_REF_ENTITY_ATTR, 1)
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setReference(REF_PARAM_BY_REF_ENTITY_ATTR, 2)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertFacetIndexed(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR, 1, null, 1
					);
					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR, 2, null, 2
					);
					assertReferenceStillIndexed(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR, 2, 2
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should index facet conditionally based on referenced entity reference attribute")
		void shouldIndexFacetConditionallyBasedOnReferencedEntityReferenceAttribute(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalFacetSchema(session);

					session.createNewEntity(ENTITY_TAG, 1).upsertVia(session);

					// parameter with matching weight on tag reference
					session.createNewEntity(ENTITY_PARAMETER, 1)
						.setReference(
							ENTITY_TAG, 1,
							whichIs -> whichIs.setAttribute(ATTR_WEIGHT, 10)
						)
						.upsertVia(session);
					// parameter with non-matching weight
					session.createNewEntity(ENTITY_PARAMETER, 2)
						.setReference(
							ENTITY_TAG, 1,
							whichIs -> whichIs.setAttribute(ATTR_WEIGHT, 2)
						)
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_PARAM_BY_REF_ENTITY_REF_ATTR, 1)
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setReference(REF_PARAM_BY_REF_ENTITY_REF_ATTR, 2)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertFacetIndexed(
						productCollection, REF_PARAM_BY_REF_ENTITY_REF_ATTR, 1, null, 1
					);
					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_REF_ENTITY_REF_ATTR, 2, null, 2
					);
					assertReferenceStillIndexed(
						productCollection, REF_PARAM_BY_REF_ENTITY_REF_ATTR, 2, 2
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should index facet conditionally based on referenced entity single reference attribute")
		void shouldIndexFacetConditionallyBasedOnReferencedEntitySingleReferenceAttribute(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalFacetSchema(session);

					session.createNewEntity(ENTITY_TAG, 1).upsertVia(session);

					// parameter with matching weight on singleTag reference
					session.createNewEntity(ENTITY_PARAMETER, 1)
						.setReference(
							REF_SINGLE_TAG, 1,
							whichIs -> whichIs.setAttribute(ATTR_WEIGHT, 10)
						)
						.upsertVia(session);
					// parameter with non-matching weight
					session.createNewEntity(ENTITY_PARAMETER, 2)
						.setReference(
							REF_SINGLE_TAG, 1,
							whichIs -> whichIs.setAttribute(ATTR_WEIGHT, 2)
						)
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_PARAM_BY_REF_ENTITY_SINGLE_REF_ATTR, 1)
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setReference(REF_PARAM_BY_REF_ENTITY_SINGLE_REF_ATTR, 2)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertFacetIndexed(
						productCollection,
						REF_PARAM_BY_REF_ENTITY_SINGLE_REF_ATTR, 1, null, 1
					);
					assertFacetNotIndexed(
						productCollection,
						REF_PARAM_BY_REF_ENTITY_SINGLE_REF_ATTR, 2, null, 2
					);
					assertReferenceStillIndexed(
						productCollection,
						REF_PARAM_BY_REF_ENTITY_SINGLE_REF_ATTR, 2, 2
					);
				}
			);
		}
	}

	/**
	 * Tests verifying that facet index state transitions correctly when the owner entity
	 * is mutated locally (attribute change, reference attribute change, associated data change,
	 * parent change, group assignment change).
	 */
	@Nested
	@DisplayName("Local trigger — state transitions on owner entity mutations")
	class LocalTriggerTest {

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should toggle facet on entity attribute change")
		void shouldToggleFacetOnEntityAttributeChange(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalFacetSchema(session);

					session.createNewEntity(ENTITY_PARAMETER, 1).upsertVia(session);

					// start with isActive=false → not indexed
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setAttribute(ATTR_IS_ACTIVE, false)
						.setReference(REF_PARAM_BY_ENTITY_ATTR, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_ENTITY_ATTR, 1, null, 1
					);

					// mutate to true → should become indexed
					session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_IS_ACTIVE, true)
						.upsertVia(session);

					assertFacetIndexed(
						productCollection, REF_PARAM_BY_ENTITY_ATTR, 1, null, 1
					);

					// mutate back to false → should be removed
					session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_IS_ACTIVE, false)
						.upsertVia(session);

					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_ENTITY_ATTR, 1, null, 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should toggle facet on reference attribute change")
		void shouldToggleFacetOnReferenceAttributeChange(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalFacetSchema(session);

					session.createNewEntity(ENTITY_PARAMETER, 1).upsertVia(session);

					// start with priority=-1 → not indexed
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_PARAM_BY_REF_ATTR, 1,
							whichIs -> whichIs.setAttribute(ATTR_PRIORITY, -1)
						)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_REF_ATTR, 1, null, 1
					);

					// mutate priority to 5 → should become indexed
					session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setReference(
							REF_PARAM_BY_REF_ATTR, 1,
							whichIs -> whichIs.setAttribute(ATTR_PRIORITY, 5)
						)
						.upsertVia(session);

					assertFacetIndexed(
						productCollection, REF_PARAM_BY_REF_ATTR, 1, null, 1
					);

					// mutate back to -1 → should be removed
					session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setReference(
							REF_PARAM_BY_REF_ATTR, 1,
							whichIs -> whichIs.setAttribute(ATTR_PRIORITY, -1)
						)
						.upsertVia(session);

					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_REF_ATTR, 1, null, 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should toggle facet on associated data change")
		void shouldToggleFacetOnAssociatedDataChange(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalFacetSchema(session);

					session.createNewEntity(ENTITY_PARAMETER, 1).upsertVia(session);

					// start without associated data → not indexed
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_PARAM_BY_ASSOC_DATA, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_ASSOC_DATA, 1, null, 1
					);

					// add associated data → should become indexed
					session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAssociatedData(ASSOC_DATA_METADATA, "value")
						.upsertVia(session);

					assertFacetIndexed(
						productCollection, REF_PARAM_BY_ASSOC_DATA, 1, null, 1
					);

					// remove associated data → should be removed
					session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeAssociatedData(ASSOC_DATA_METADATA)
						.upsertVia(session);

					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_ASSOC_DATA, 1, null, 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should toggle facet on parent change")
		void shouldToggleFacetOnParentChange(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalFacetSchema(session);

					session.createNewEntity(ENTITY_PARAMETER, 1).upsertVia(session);

					// product PK=1 as root (parent target)
					session.createNewEntity(ENTITY_PRODUCT, 1).upsertVia(session);

					// product PK=2 without parent → not indexed
					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setReference(REF_PARAM_BY_PARENT, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_PARENT, 1, null, 2
					);

					// set parent → should become indexed
					session.getEntity(ENTITY_PRODUCT, 2, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setParent(1)
						.upsertVia(session);

					assertFacetIndexed(
						productCollection, REF_PARAM_BY_PARENT, 1, null, 2
					);

					// remove parent → should be removed
					session.getEntity(ENTITY_PRODUCT, 2, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeParent()
						.upsertVia(session);

					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_PARENT, 1, null, 2
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should toggle facet when product gains and loses parent with matching attribute")
		void shouldToggleFacetWhenProductGainsAndLosesParent(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalFacetSchema(session);

					session.createNewEntity(ENTITY_PARAMETER, 1).upsertVia(session);

					// product PK=1 as root with matching code
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setAttribute(ATTR_CODE, "ROOT")
						.upsertVia(session);

					// product PK=2 without parent → not indexed
					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setReference(REF_PARAM_BY_PARENT_ATTR, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_PARENT_ATTR, 1, null, 2
					);

					// set parent with code='ROOT' → should become indexed
					session.getEntity(ENTITY_PRODUCT, 2, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setParent(1)
						.upsertVia(session);

					assertFacetIndexed(
						productCollection, REF_PARAM_BY_PARENT_ATTR, 1, null, 2
					);

					// remove parent → should be removed
					session.getEntity(ENTITY_PRODUCT, 2, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeParent()
						.upsertVia(session);

					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_PARENT_ATTR, 1, null, 2
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should toggle facet on group assignment change")
		void shouldToggleFacetOnGroupAssignmentChange(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalFacetSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_GROUP, 1)
						.setAttribute(ATTR_WIDGET_TYPE, "CHECKBOX")
						.upsertVia(session);
					session.createNewEntity(ENTITY_PARAMETER, 1).upsertVia(session);

					// product with no group → expression evaluates to false
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_PARAM_BY_GROUP_ATTR, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 1, null, 1
					);

					// assign group → should become indexed
					session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setReference(
							REF_PARAM_BY_GROUP_ATTR, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER_GROUP, 1)
						)
						.upsertVia(session);

					assertFacetIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 1, 1, 1
					);

					// remove group → should be removed
					session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setReference(REF_PARAM_BY_GROUP_ATTR, 1)
						.upsertVia(session);

					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 1, null, 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should not reevaluate when irrelevant attribute changes")
		void shouldNotReevaluateWhenIrrelevantAttributeChanges(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalFacetSchema(session);

					session.createNewEntity(ENTITY_PARAMETER, 1).upsertVia(session);

					// product with isActive=true and code="ABC" → indexed
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setAttribute(ATTR_IS_ACTIVE, true)
						.setAttribute(ATTR_CODE, "ABC")
						.setReference(REF_PARAM_BY_ENTITY_ATTR, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertFacetIndexed(
						productCollection, REF_PARAM_BY_ENTITY_ATTR, 1, null, 1
					);

					// change irrelevant attribute → should still be indexed
					session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_CODE, "XYZ")
						.upsertVia(session);

					assertFacetIndexed(
						productCollection, REF_PARAM_BY_ENTITY_ATTR, 1, null, 1
					);
				}
			);
		}
	}

	/**
	 * Tests verifying cross-entity trigger propagation when the referenced entity is mutated
	 * (attribute change, reference attribute change, entity deletion, late entity insertion).
	 */
	@Nested
	@DisplayName("Cross-entity triggers — referenced entity mutations")
	class CrossEntityReferencedEntityTriggerTest {

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should toggle facet when referenced entity attribute changes")
		void shouldToggleFacetWhenReferencedEntityAttributeChanges(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalFacetSchema(session);

					session.createNewEntity(ENTITY_PARAMETER, 1)
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_PARAM_BY_REF_ENTITY_ATTR, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertFacetIndexed(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR, 1, null, 1
					);

					// cross-entity mutation: change parameter status to INACTIVE
					session.getEntity(ENTITY_PARAMETER, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_STATUS, "INACTIVE")
						.upsertVia(session);

					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR, 1, null, 1
					);

					// restore
					session.getEntity(ENTITY_PARAMETER, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.upsertVia(session);

					assertFacetIndexed(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR, 1, null, 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should toggle facet when referenced entity reference attribute changes")
		void shouldToggleFacetWhenReferencedEntityReferenceAttributeChanges(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalFacetSchema(session);

					session.createNewEntity(ENTITY_TAG, 1).upsertVia(session);

					session.createNewEntity(ENTITY_PARAMETER, 1)
						.setReference(
							ENTITY_TAG, 1,
							whichIs -> whichIs.setAttribute(ATTR_WEIGHT, 10)
						)
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_PARAM_BY_REF_ENTITY_REF_ATTR, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertFacetIndexed(
						productCollection, REF_PARAM_BY_REF_ENTITY_REF_ATTR, 1, null, 1
					);

					// change weight to below threshold
					session.getEntity(ENTITY_PARAMETER, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setReference(
							ENTITY_TAG, 1,
							whichIs -> whichIs.setAttribute(ATTR_WEIGHT, 2)
						)
						.upsertVia(session);

					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_REF_ENTITY_REF_ATTR, 1, null, 1
					);

					// restore weight above threshold
					session.getEntity(ENTITY_PARAMETER, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setReference(
							ENTITY_TAG, 1,
							whichIs -> whichIs.setAttribute(ATTR_WEIGHT, 10)
						)
						.upsertVia(session);

					assertFacetIndexed(
						productCollection, REF_PARAM_BY_REF_ENTITY_REF_ATTR, 1, null, 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should toggle facet when referenced entity single reference attribute changes")
		void shouldToggleFacetWhenReferencedEntitySingleReferenceAttributeChanges(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalFacetSchema(session);

					session.createNewEntity(ENTITY_TAG, 1).upsertVia(session);

					session.createNewEntity(ENTITY_PARAMETER, 1)
						.setReference(
							REF_SINGLE_TAG, 1,
							whichIs -> whichIs.setAttribute(ATTR_WEIGHT, 10)
						)
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_PARAM_BY_REF_ENTITY_SINGLE_REF_ATTR, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertFacetIndexed(
						productCollection,
						REF_PARAM_BY_REF_ENTITY_SINGLE_REF_ATTR, 1, null, 1
					);

					// change weight to below threshold
					session.getEntity(ENTITY_PARAMETER, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setReference(
							REF_SINGLE_TAG, 1,
							whichIs -> whichIs.setAttribute(ATTR_WEIGHT, 2)
						)
						.upsertVia(session);

					assertFacetNotIndexed(
						productCollection,
						REF_PARAM_BY_REF_ENTITY_SINGLE_REF_ATTR, 1, null, 1
					);

					// restore weight above threshold
					session.getEntity(ENTITY_PARAMETER, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setReference(
							REF_SINGLE_TAG, 1,
							whichIs -> whichIs.setAttribute(ATTR_WEIGHT, 10)
						)
						.upsertVia(session);

					assertFacetIndexed(
						productCollection,
						REF_PARAM_BY_REF_ENTITY_SINGLE_REF_ATTR, 1, null, 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should remove facet when referenced entity is removed")
		void shouldRemoveFacetWhenReferencedEntityIsRemoved(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalFacetSchema(session);

					session.createNewEntity(ENTITY_PARAMETER, 1)
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_PARAM_BY_REF_ENTITY_ATTR, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertFacetIndexed(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR, 1, null, 1
					);

					// delete the referenced entity
					session.deleteEntity(ENTITY_PARAMETER, 1);

					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR, 1, null, 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should index facet immediately when local expression is true even if referenced entity absent")
		void shouldIndexFacetImmediatelyWhenLocalExpressionTrueAndReferencedEntityAbsent(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalFacetSchema(session);

					// create product with isActive=true referencing non-existent parameter PK=1;
					// the expression ($entity.attributes['isActive'] ?? false) == true uses only
					// local entity data — the referenced entity's existence is irrelevant
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setAttribute(ATTR_IS_ACTIVE, true)
						.setReference(REF_PARAM_BY_ENTITY_ATTR, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					// expression evaluates to true using local data only → facet must be indexed
					assertFacetIndexed(
						productCollection, REF_PARAM_BY_ENTITY_ATTR, 1, null, 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should index facet when referenced entity with matching attributes is inserted after referencing entity")
		void shouldIndexFacetWhenReferencedEntityWithMatchingAttributesInsertedLater(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalFacetSchema(session);

					// create product referencing non-existent parameter PK=1;
					// the expression ($reference.referencedEntity.attributes['status'] ?? '') == 'ACTIVE'
					// reads the referenced entity's attribute — since parameter PK=1 doesn't exist yet,
					// the null-safe coalesce yields '' which != 'ACTIVE' → expression is false
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_PARAM_BY_REF_ENTITY_ATTR, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					// referenced entity absent → expression evaluates to false → not faceted
					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR, 1, null, 1
					);

					// late arrival: create parameter PK=1 with status='ACTIVE' —
					// cross-entity trigger fires, re-evaluates expression → now true
					session.createNewEntity(ENTITY_PARAMETER, 1)
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.upsertVia(session);

					// facet should now be indexed via the cross-entity trigger
					assertFacetIndexed(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR, 1, null, 1
					);
				}
			);
		}
	}

	/**
	 * Tests verifying cross-entity trigger propagation when the parent entity is mutated
	 * (attribute change on parent cascading to children).
	 */
	@Nested
	@DisplayName("Cross-entity triggers — parent entity mutations")
	class CrossEntityParentEntityTriggerTest {

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should toggle facet when parent entity attribute changes")
		void shouldToggleFacetWhenParentEntityAttributeChanges(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalFacetSchema(session);

					session.createNewEntity(ENTITY_PARAMETER, 1).upsertVia(session);

					// product PK=1 as root with matching code
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setAttribute(ATTR_CODE, "ROOT")
						.upsertVia(session);

					// product PK=2 child with reference → faceted
					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setParent(1)
						.setReference(REF_PARAM_BY_PARENT_ATTR, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertFacetIndexed(
						productCollection, REF_PARAM_BY_PARENT_ATTR, 1, null, 2
					);

					// cross-entity mutation: change parent code to non-matching
					session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_CODE, "OTHER")
						.upsertVia(session);

					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_PARENT_ATTR, 1, null, 2
					);

					// restore parent code
					session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_CODE, "ROOT")
						.upsertVia(session);

					assertFacetIndexed(
						productCollection, REF_PARAM_BY_PARENT_ATTR, 1, null, 2
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should cascade to multiple children when parent attribute changes")
		void shouldCascadeToMultipleChildrenWhenParentAttributeChanges(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalFacetSchema(session);

					session.createNewEntity(ENTITY_PARAMETER, 1).upsertVia(session);

					// product PK=1 as root with matching code
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setAttribute(ATTR_CODE, "ROOT")
						.upsertVia(session);

					// three children referencing same parameter
					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setParent(1)
						.setReference(REF_PARAM_BY_PARENT_ATTR, 1)
						.upsertVia(session);
					session.createNewEntity(ENTITY_PRODUCT, 3)
						.setParent(1)
						.setReference(REF_PARAM_BY_PARENT_ATTR, 1)
						.upsertVia(session);
					session.createNewEntity(ENTITY_PRODUCT, 4)
						.setParent(1)
						.setReference(REF_PARAM_BY_PARENT_ATTR, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertFacetIndexed(
						productCollection, REF_PARAM_BY_PARENT_ATTR, 1, null, 2
					);
					assertFacetIndexed(
						productCollection, REF_PARAM_BY_PARENT_ATTR, 1, null, 3
					);
					assertFacetIndexed(
						productCollection, REF_PARAM_BY_PARENT_ATTR, 1, null, 4
					);

					// cross-entity fan-out: change parent code
					session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_CODE, "OTHER")
						.upsertVia(session);

					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_PARENT_ATTR, 1, null, 2
					);
					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_PARENT_ATTR, 1, null, 3
					);
					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_PARENT_ATTR, 1, null, 4
					);

					// restore
					session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_CODE, "ROOT")
						.upsertVia(session);

					assertFacetIndexed(
						productCollection, REF_PARAM_BY_PARENT_ATTR, 1, null, 2
					);
					assertFacetIndexed(
						productCollection, REF_PARAM_BY_PARENT_ATTR, 1, null, 3
					);
					assertFacetIndexed(
						productCollection, REF_PARAM_BY_PARENT_ATTR, 1, null, 4
					);
				}
			);
		}
	}

	/**
	 * Tests verifying cross-entity trigger propagation when the group entity is mutated
	 * (attribute change, reference attribute change, entity deletion, late entity insertion).
	 */
	@Nested
	@DisplayName("Cross-entity triggers — group entity mutations")
	class CrossEntityGroupEntityTriggerTest {

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should cascade to all owner entities when group entity attribute changes")
		void shouldCascadeToAllOwnerEntitiesWhenGroupEntityAttributeChanges(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalFacetSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_GROUP, 1)
						.setAttribute(ATTR_WIDGET_TYPE, "CHECKBOX")
						.upsertVia(session);
					session.createNewEntity(ENTITY_PARAMETER, 1).upsertVia(session);

					// three products referencing same parameter via same group
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_PARAM_BY_GROUP_ATTR, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER_GROUP, 1)
						)
						.upsertVia(session);
					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setReference(
							REF_PARAM_BY_GROUP_ATTR, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER_GROUP, 1)
						)
						.upsertVia(session);
					session.createNewEntity(ENTITY_PRODUCT, 3)
						.setReference(
							REF_PARAM_BY_GROUP_ATTR, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER_GROUP, 1)
						)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertFacetIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 1, 1, 1
					);
					assertFacetIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 1, 1, 2
					);
					assertFacetIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 1, 1, 3
					);

					// cross-entity fan-out: change group widget type to non-matching
					session.getEntity(ENTITY_PARAMETER_GROUP, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_WIDGET_TYPE, "RADIO")
						.upsertVia(session);

					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 1, 1, 1
					);
					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 1, 1, 2
					);
					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 1, 1, 3
					);

					// restore
					session.getEntity(ENTITY_PARAMETER_GROUP, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_WIDGET_TYPE, "CHECKBOX")
						.upsertVia(session);

					assertFacetIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 1, 1, 1
					);
					assertFacetIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 1, 1, 2
					);
					assertFacetIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 1, 1, 3
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should toggle facet when group entity reference attribute changes")
		void shouldToggleFacetWhenGroupEntityReferenceAttributeChanges(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalFacetSchema(session);

					session.createNewEntity(ENTITY_TAG, 1).upsertVia(session);

					session.createNewEntity(ENTITY_PARAMETER_GROUP, 1)
						.setReference(
							ENTITY_TAG, 1,
							whichIs -> whichIs.setAttribute(ATTR_WEIGHT, 10)
						)
						.upsertVia(session);
					session.createNewEntity(ENTITY_PARAMETER, 1).upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_PARAM_BY_GROUP_ENTITY_REF_ATTR, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER_GROUP, 1)
						)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertFacetIndexed(
						productCollection, REF_PARAM_BY_GROUP_ENTITY_REF_ATTR, 1, 1, 1
					);

					// change weight to below threshold
					session.getEntity(ENTITY_PARAMETER_GROUP, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setReference(
							ENTITY_TAG, 1,
							whichIs -> whichIs.setAttribute(ATTR_WEIGHT, 2)
						)
						.upsertVia(session);

					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_GROUP_ENTITY_REF_ATTR, 1, 1, 1
					);

					// restore weight above threshold
					session.getEntity(ENTITY_PARAMETER_GROUP, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setReference(
							ENTITY_TAG, 1,
							whichIs -> whichIs.setAttribute(ATTR_WEIGHT, 10)
						)
						.upsertVia(session);

					assertFacetIndexed(
						productCollection, REF_PARAM_BY_GROUP_ENTITY_REF_ATTR, 1, 1, 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should toggle facet when group entity single reference attribute changes")
		void shouldToggleFacetWhenGroupEntitySingleReferenceAttributeChanges(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalFacetSchema(session);

					session.createNewEntity(ENTITY_TAG, 1).upsertVia(session);

					session.createNewEntity(ENTITY_PARAMETER_GROUP, 1)
						.setReference(
							REF_SINGLE_TAG, 1,
							whichIs -> whichIs.setAttribute(ATTR_WEIGHT, 10)
						)
						.upsertVia(session);
					session.createNewEntity(ENTITY_PARAMETER, 1).upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_PARAM_BY_GROUP_ENTITY_SINGLE_REF_ATTR, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER_GROUP, 1)
						)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertFacetIndexed(
						productCollection,
						REF_PARAM_BY_GROUP_ENTITY_SINGLE_REF_ATTR, 1, 1, 1
					);

					// change weight to below threshold
					session.getEntity(ENTITY_PARAMETER_GROUP, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setReference(
							REF_SINGLE_TAG, 1,
							whichIs -> whichIs.setAttribute(ATTR_WEIGHT, 2)
						)
						.upsertVia(session);

					assertFacetNotIndexed(
						productCollection,
						REF_PARAM_BY_GROUP_ENTITY_SINGLE_REF_ATTR, 1, 1, 1
					);

					// restore weight above threshold
					session.getEntity(ENTITY_PARAMETER_GROUP, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setReference(
							REF_SINGLE_TAG, 1,
							whichIs -> whichIs.setAttribute(ATTR_WEIGHT, 10)
						)
						.upsertVia(session);

					assertFacetIndexed(
						productCollection,
						REF_PARAM_BY_GROUP_ENTITY_SINGLE_REF_ATTR, 1, 1, 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should remove facet when group entity is removed")
		void shouldRemoveFacetWhenGroupEntityIsRemoved(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalFacetSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_GROUP, 1)
						.setAttribute(ATTR_WIDGET_TYPE, "CHECKBOX")
						.upsertVia(session);
					session.createNewEntity(ENTITY_PARAMETER, 1).upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_PARAM_BY_GROUP_ATTR, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER_GROUP, 1)
						)
						.upsertVia(session);
					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setReference(
							REF_PARAM_BY_GROUP_ATTR, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER_GROUP, 1)
						)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertFacetIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 1, 1, 1
					);
					assertFacetIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 1, 1, 2
					);

					// delete the group entity
					session.deleteEntity(ENTITY_PARAMETER_GROUP, 1);

					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 1, 1, 1
					);
					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 1, 1, 2
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should index facet when group entity is inserted after referencing entity")
		void shouldIndexFacetWhenGroupEntityIsInsertedAfterReferencingEntity(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalFacetSchema(session);

					session.createNewEntity(ENTITY_PARAMETER, 1).upsertVia(session);

					// create product referencing non-existent group
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_PARAM_BY_GROUP_ATTR, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER_GROUP, 1)
						)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 1, 1, 1
					);

					// late arrival: create the group entity with matching attribute
					session.createNewEntity(ENTITY_PARAMETER_GROUP, 1)
						.setAttribute(ATTR_WIDGET_TYPE, "CHECKBOX")
						.upsertVia(session);

					assertFacetIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 1, 1, 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should remove facet entries when product is deleted after group type change")
		void shouldRemoveFacetEntriesWhenProductDeletedAfterGroupTypeChange(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalFacetSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_GROUP, 1)
						.setAttribute(ATTR_WIDGET_TYPE, "CHECKBOX")
						.upsertVia(session);
					session.createNewEntity(ENTITY_PARAMETER, 1).upsertVia(session);
					session.createNewEntity(ENTITY_PARAMETER, 2).upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_PARAM_BY_GROUP_ATTR, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER_GROUP, 1))
						.setReference(REF_PARAM_BY_GROUP_ATTR, 2,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER_GROUP, 1))
						.upsertVia(session);
					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setReference(REF_PARAM_BY_GROUP_ATTR, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER_GROUP, 1))
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertFacetIndexed(productCollection, REF_PARAM_BY_GROUP_ATTR, 1, 1, 1);
					assertFacetIndexed(productCollection, REF_PARAM_BY_GROUP_ATTR, 2, 1, 1);
					assertFacetIndexed(productCollection, REF_PARAM_BY_GROUP_ATTR, 1, 1, 2);

					// delete product 1
					session.deleteEntity(ENTITY_PRODUCT, 1);

					assertFacetNotIndexed(productCollection, REF_PARAM_BY_GROUP_ATTR, 1, 1, 1);
					assertFacetNotIndexed(productCollection, REF_PARAM_BY_GROUP_ATTR, 2, 1, 1);
					assertFacetIndexed(productCollection, REF_PARAM_BY_GROUP_ATTR, 1, 1, 2);

					// flip group 1: CHECKBOX -> RADIO -> CHECKBOX
					session.getEntity(ENTITY_PARAMETER_GROUP, 1, entityFetchAllContent())
						.orElseThrow().openForWrite()
						.setAttribute(ATTR_WIDGET_TYPE, "RADIO")
						.upsertVia(session);
					session.getEntity(ENTITY_PARAMETER_GROUP, 1, entityFetchAllContent())
						.orElseThrow().openForWrite()
						.setAttribute(ATTR_WIDGET_TYPE, "CHECKBOX")
						.upsertVia(session);

					// product 1 must NOT reappear after group type flip
					assertFacetNotIndexed(productCollection, REF_PARAM_BY_GROUP_ATTR, 1, 1, 1);
					assertFacetNotIndexed(productCollection, REF_PARAM_BY_GROUP_ATTR, 2, 1, 1);
					assertFacetIndexed(productCollection, REF_PARAM_BY_GROUP_ATTR, 1, 1, 2);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should remove facets when group type flips and product is deleted in same session")
		void shouldRemoveFacetsWhenGroupFlipsAndProductDeletedInSameSession(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalFacetSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_GROUP, 1)
						.setAttribute(ATTR_WIDGET_TYPE, "CHECKBOX")
						.upsertVia(session);
					session.createNewEntity(ENTITY_PARAMETER, 1).upsertVia(session);
					session.createNewEntity(ENTITY_PARAMETER, 2).upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_PARAM_BY_GROUP_ATTR, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER_GROUP, 1))
						.setReference(REF_PARAM_BY_GROUP_ATTR, 2,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER_GROUP, 1))
						.upsertVia(session);
					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setReference(REF_PARAM_BY_GROUP_ATTR, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER_GROUP, 1))
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertFacetIndexed(productCollection, REF_PARAM_BY_GROUP_ATTR, 1, 1, 1);

					// flip group to RADIO, back to CHECKBOX, then delete product 1
					session.getEntity(ENTITY_PARAMETER_GROUP, 1, entityFetchAllContent())
						.orElseThrow().openForWrite()
						.setAttribute(ATTR_WIDGET_TYPE, "RADIO")
						.upsertVia(session);
					session.getEntity(ENTITY_PARAMETER_GROUP, 1, entityFetchAllContent())
						.orElseThrow().openForWrite()
						.setAttribute(ATTR_WIDGET_TYPE, "CHECKBOX")
						.upsertVia(session);
					session.deleteEntity(ENTITY_PRODUCT, 1);

					assertFacetNotIndexed(productCollection, REF_PARAM_BY_GROUP_ATTR, 1, 1, 1);
					assertFacetNotIndexed(productCollection, REF_PARAM_BY_GROUP_ATTR, 2, 1, 1);
					assertFacetIndexed(productCollection, REF_PARAM_BY_GROUP_ATTR, 1, 1, 2);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should handle multi-group product deletion with interleaved group type changes")
		void shouldHandleMultiGroupProductDeletionWithInterleavedGroupTypeChanges(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalFacetSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_GROUP, 1)
						.setAttribute(ATTR_WIDGET_TYPE, "CHECKBOX")
						.upsertVia(session);
					session.createNewEntity(ENTITY_PARAMETER_GROUP, 2)
						.setAttribute(ATTR_WIDGET_TYPE, "RADIO")
						.upsertVia(session);
					session.createNewEntity(ENTITY_PARAMETER, 1).upsertVia(session);
					session.createNewEntity(ENTITY_PARAMETER, 2).upsertVia(session);
					session.createNewEntity(ENTITY_PARAMETER, 3).upsertVia(session);

					// product 1 has refs in group 1 AND group 2
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_PARAM_BY_GROUP_ATTR, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER_GROUP, 1))
						.setReference(REF_PARAM_BY_GROUP_ATTR, 2,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER_GROUP, 2))
						.upsertVia(session);
					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setReference(REF_PARAM_BY_GROUP_ATTR, 3,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER_GROUP, 2))
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					// group 1=CHECKBOX: product 1 faceted for param 1
					assertFacetIndexed(productCollection, REF_PARAM_BY_GROUP_ATTR, 1, 1, 1);
					// group 2=RADIO: no facets
					assertFacetNotIndexed(productCollection, REF_PARAM_BY_GROUP_ATTR, 2, 2, 1);

					// flip group 2 to CHECKBOX, then delete product 2, then flip back to RADIO
					session.getEntity(ENTITY_PARAMETER_GROUP, 2, entityFetchAllContent())
						.orElseThrow().openForWrite()
						.setAttribute(ATTR_WIDGET_TYPE, "CHECKBOX")
						.upsertVia(session);
					session.deleteEntity(ENTITY_PRODUCT, 2);
					session.getEntity(ENTITY_PARAMETER_GROUP, 2, entityFetchAllContent())
						.orElseThrow().openForWrite()
						.setAttribute(ATTR_WIDGET_TYPE, "RADIO")
						.upsertVia(session);

					// product 2 deleted — no stale entries
					assertFacetNotIndexed(productCollection, REF_PARAM_BY_GROUP_ATTR, 3, 2, 2);
					// product 1 in group 2 (RADIO) — not faceted
					assertFacetNotIndexed(productCollection, REF_PARAM_BY_GROUP_ATTR, 2, 2, 1);
					// product 1 in group 1 (CHECKBOX) — still faceted
					assertFacetIndexed(productCollection, REF_PARAM_BY_GROUP_ATTR, 1, 1, 1);
				}
			);
		}
	}

	/**
	 * Tests verifying compound expressions (AND/OR across multiple sources), null-safe group entity
	 * access, shared group entity fan-out, reflected reference inheritance, no-op mutation suppression,
	 * and non-translatable expression rejection.
	 */
	@Nested
	@DisplayName("Mixed and cross-cutting expression tests")
	class MixedAndCrossCuttingTest {

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should evaluate mixed expression combining group and entity attributes")
		void shouldEvaluateMixedExpressionCombiningGroupAndEntityAttributes(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalFacetSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_GROUP, 1)
						.setAttribute(ATTR_WIDGET_TYPE, "CHECKBOX")
						.upsertVia(session);
					session.createNewEntity(ENTITY_PARAMETER, 1).upsertVia(session);

					// both conditions hold → TRUE
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setAttribute(ATTR_IS_ACTIVE, true)
						.setReference(
							REF_PARAM_BY_MIXED_AND, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER_GROUP, 1)
						)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertFacetIndexed(
						productCollection, REF_PARAM_BY_MIXED_AND, 1, 1, 1
					);

					// toggle entity attribute to false → local part breaks
					session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_IS_ACTIVE, false)
						.upsertVia(session);

					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_MIXED_AND, 1, 1, 1
					);

					// restore entity attribute, break group attribute
					session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_IS_ACTIVE, true)
						.upsertVia(session);

					session.getEntity(ENTITY_PARAMETER_GROUP, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_WIDGET_TYPE, "RADIO")
						.upsertVia(session);

					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_MIXED_AND, 1, 1, 1
					);

					// restore group attribute → both hold again
					session.getEntity(ENTITY_PARAMETER_GROUP, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_WIDGET_TYPE, "CHECKBOX")
						.upsertVia(session);

					assertFacetIndexed(
						productCollection, REF_PARAM_BY_MIXED_AND, 1, 1, 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should evaluate OR expression across multiple cross-entity sources")
		void shouldEvaluateOrExpressionAcrossMultipleCrossEntitySources(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalFacetSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_GROUP, 1)
						.setAttribute(ATTR_WIDGET_TYPE, "CHECKBOX")
						.upsertVia(session);
					session.createNewEntity(ENTITY_PARAMETER, 1)
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_PARAM_BY_MULTI_SOURCE_OR, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER_GROUP, 1)
						)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					// both branches true
					assertFacetIndexed(
						productCollection, REF_PARAM_BY_MULTI_SOURCE_OR, 1, 1, 1
					);

					// break group branch only → still true (OR)
					session.getEntity(ENTITY_PARAMETER_GROUP, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_WIDGET_TYPE, "RADIO")
						.upsertVia(session);

					assertFacetIndexed(
						productCollection, REF_PARAM_BY_MULTI_SOURCE_OR, 1, 1, 1
					);

					// also break referenced entity branch → both false
					session.getEntity(ENTITY_PARAMETER, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_STATUS, "INACTIVE")
						.upsertVia(session);

					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_MULTI_SOURCE_OR, 1, 1, 1
					);

					// restore one branch → true again
					session.getEntity(ENTITY_PARAMETER, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.upsertVia(session);

					assertFacetIndexed(
						productCollection, REF_PARAM_BY_MULTI_SOURCE_OR, 1, 1, 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should handle null-safe group entity access")
		void shouldHandleNullSafeGroupEntityAccess(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalFacetSchema(session);

					session.createNewEntity(ENTITY_PARAMETER, 1).upsertVia(session);

					// reference with non-existent group → null-safe returns null → false
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_PARAM_BY_GROUP_ATTR, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER_GROUP, 1)
						)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 1, 1, 1
					);

					// create the group entity → should now be true
					session.createNewEntity(ENTITY_PARAMETER_GROUP, 1)
						.setAttribute(ATTR_WIDGET_TYPE, "CHECKBOX")
						.upsertVia(session);

					assertFacetIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 1, 1, 1
					);

					// delete the group entity → should be false again
					session.deleteEntity(ENTITY_PARAMETER_GROUP, 1);

					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 1, 1, 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should reevaluate all reference types when shared group entity changes")
		void shouldReevaluateAllReferenceTypesWhenSharedGroupEntityChanges(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalFacetSchema(session);

					session.createNewEntity(ENTITY_PARAMETER_GROUP, 1)
						.setAttribute(ATTR_WIDGET_TYPE, "CHECKBOX")
						.upsertVia(session);
					session.createNewEntity(ENTITY_PARAMETER, 1).upsertVia(session);

					// product with two reference types using the same group
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(
							REF_PARAM_BY_GROUP_ATTR, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER_GROUP, 1)
						)
						.setReference(
							REF_PARAM_BY_GROUP_ATTR_SECONDARY, 1,
							whichIs -> whichIs.setGroup(ENTITY_PARAMETER_GROUP, 1)
						)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertFacetIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 1, 1, 1
					);
					assertFacetIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR_SECONDARY, 1, 1, 1
					);

					// cross-entity mutation → both should become false
					session.getEntity(ENTITY_PARAMETER_GROUP, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_WIDGET_TYPE, "RADIO")
						.upsertVia(session);

					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR, 1, 1, 1
					);
					assertFacetNotIndexed(
						productCollection, REF_PARAM_BY_GROUP_ATTR_SECONDARY, 1, 1, 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should reject inheriting facetedPartially via reflected reference")
		void shouldRejectInheritingFacetedPartiallyViaReflectedReference(CatalogState state) {
			assertThrows(
				InvalidSchemaMutationException.class,
				() -> withCatalogInState(
					state,
					session -> {
						session.defineEntitySchema("category")
							.withAttribute(ATTR_PRIORITY, Integer.class,
								whichIs -> whichIs.filterable().nullable()
							)
							.updateVia(session);

						session.defineEntitySchema("item")
							.withReferenceToEntity(
								"category", "category", Cardinality.ZERO_OR_MORE,
								whichIs -> whichIs
									.indexedForFilteringAndPartitioning()
									.faceted()
									.facetedPartially(
										ExpressionFactory.parse(
											"$reference.referencedEntity.attributes['priority'] > 0"
										)
									)
							)
							.updateVia(session);

						// attempting to inherit facetedPartially via reflected reference
						// must fail because the expression contains direction-specific paths
						session.defineEntitySchema("category")
							.withReflectedReferenceToEntity(
								"items", "item", "category",
								ReflectedReferenceSchemaEditor::withFacetedInherited
							)
							.updateVia(session);
					},
					session -> fail("Should not reach assertion phase")
				)
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should allow explicit faceted on reflected when source has facetedPartially")
		void shouldAllowExplicitFacetedOnReflectedWhenSourceHasFacetedPartially(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					session.defineEntitySchema("category")
						.withAttribute(ATTR_PRIORITY, Integer.class,
							whichIs -> whichIs.filterable().nullable()
						)
						.updateVia(session);

					session.defineEntitySchema("item")
						.withReferenceToEntity(
							"category", "category", Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs
								.indexedForFilteringAndPartitioning()
								.faceted()
								.facetedPartially(
									ExpressionFactory.parse(
										"$reference.referencedEntity.attributes['priority'] > 0"
									)
								)
						)
						.updateVia(session);

					// reflected reference with explicit faceted — no inheritance
					session.defineEntitySchema("category")
						.withReflectedReferenceToEntity(
							"items", "item", "category",
							whichIs -> whichIs.facetedInScope(Scope.LIVE)
						)
						.updateVia(session);

					session.createNewEntity("category", 1)
						.setAttribute(ATTR_PRIORITY, 5)
						.upsertVia(session);

					session.createNewEntity("item", 1)
						.setReference("category", 1)
						.upsertVia(session);
				},
				session -> {
					final CatalogContract catalog =
						ConditionalFacetIndexingTest.this.evita
							.getCatalogInstance(TEST_CATALOG).orElseThrow();
					// item side: conditional faceting applies
					final EntityCollectionContract itemCollection =
						catalog.getCollectionForEntity("item").orElseThrow();
					assertFacetIndexed(itemCollection, "category", 1, null, 1);

					// category side: explicit faceted — all references are faceted
					final EntityCollectionContract categoryCollection =
						catalog.getCollectionForEntity("category").orElseThrow();
					assertFacetIndexed(categoryCollection, "items", 1, null, 1);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should reject adding facetedPartially to source when reflected inherits")
		void shouldRejectAddingFacetedPartiallyToSourceWhenReflectedInherits(CatalogState state) {
			assertThrows(
				InvalidSchemaMutationException.class,
				() -> withCatalogInState(
					state,
					session -> {
						session.defineEntitySchema("category")
							.withAttribute(ATTR_PRIORITY, Integer.class,
								whichIs -> whichIs.filterable().nullable()
							)
							.updateVia(session);

						// source with simple faceted (no partial)
						session.defineEntitySchema("item")
							.withReferenceToEntity(
								"category", "category", Cardinality.ZERO_OR_MORE,
								whichIs -> whichIs
									.indexedForFilteringAndPartitioning()
									.faceted()
							)
							.updateVia(session);

						// reflected inherits — succeeds because source has no facetedPartially
						session.defineEntitySchema("category")
							.withReflectedReferenceToEntity(
								"items", "item", "category",
								ReflectedReferenceSchemaEditor::withFacetedInherited
							)
							.updateVia(session);

						// now add facetedPartially to source — triggers cascade to reflected
						// which should fail because reflected inherits
						session.defineEntitySchema("item")
							.withReferenceToEntity(
								"category", "category", Cardinality.ZERO_OR_MORE,
								whichIs -> whichIs
									.facetedPartially(
										ExpressionFactory.parse(
											"$reference.referencedEntity.attributes['priority'] > 0"
										)
									)
							)
							.updateVia(session);
					},
					session -> fail("Should not reach assertion phase")
				)
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should allow faceted inheritance when source has no facetedPartially")
		void shouldAllowFacetedInheritanceWhenSourceHasNoPartially(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					session.defineEntitySchema("category")
						.updateVia(session);

					// source with simple faceted (no partial expression)
					session.defineEntitySchema("item")
						.withReferenceToEntity(
							"category", "category", Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs
								.indexedForFilteringAndPartitioning()
								.faceted()
						)
						.updateVia(session);

					// reflected inherits — should succeed
					session.defineEntitySchema("category")
						.withReflectedReferenceToEntity(
							"items", "item", "category",
							ReflectedReferenceSchemaEditor::withFacetedInherited
						)
						.updateVia(session);

					session.createNewEntity("category", 1)
						.upsertVia(session);

					session.createNewEntity("item", 1)
						.setReference("category", 1)
						.upsertVia(session);
				},
				session -> {
					final CatalogContract catalog =
						ConditionalFacetIndexingTest.this.evita
							.getCatalogInstance(TEST_CATALOG).orElseThrow();
					// both sides should have the reference faceted
					final EntityCollectionContract itemCollection =
						catalog.getCollectionForEntity("item").orElseThrow();
					assertFacetIndexed(itemCollection, "category", 1, null, 1);

					final EntityCollectionContract categoryCollection =
						catalog.getCollectionForEntity("category").orElseThrow();
					assertFacetIndexed(categoryCollection, "items", 1, null, 1);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should not trigger reevaluation when attribute value does not actually change")
		void shouldNotTriggerReevaluationWhenAttributeValueDoesNotActuallyChange(CatalogState state) {
			withCatalogInState(
				state,
				session -> {
					defineConditionalFacetSchema(session);

					session.createNewEntity(ENTITY_PARAMETER, 1)
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.upsertVia(session);

					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setReference(REF_PARAM_BY_REF_ENTITY_ATTR, 1)
						.upsertVia(session);
				},
				session -> {
					final EntityCollectionContract productCollection = getProductCollection();
					assertFacetIndexed(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR, 1, null, 1
					);

					// no-change mutation: set the same value
					session.getEntity(ENTITY_PARAMETER, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_STATUS, "ACTIVE")
						.upsertVia(session);

					// should still be indexed — no spurious state change
					assertFacetIndexed(
						productCollection, REF_PARAM_BY_REF_ENTITY_ATTR, 1, null, 1
					);
				}
			);
		}

		@ParameterizedTest(name = "catalog state: {0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("Should reject non-translatable expression at schema time")
		void shouldRejectNonTranslatableExpressionAtSchemaTime(CatalogState state) {
			try {
				withCatalogInState(
					state,
					session -> session.defineEntitySchema(ENTITY_PARAMETER).updateVia(session),
					session -> session.defineEntitySchema("testEntity")
						.withReferenceToEntity(
							"ref", ENTITY_PARAMETER, Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs
								.indexedForFilteringAndPartitioning()
								.faceted()
								.facetedPartially(
									ExpressionFactory.parse(
										"$reference.referencedEntity.attributes['type']"
											+ " == $entity.attributes['category']"
									)
								)
						)
						.updateVia(session)
				);
				fail("Expected NonTranslatableExpressionException");
			} catch (NonTranslatableExpressionException e) {
				// expected — direct throw in WARMING_UP
			} catch (Exception e) {
				// in ALIVE mode, the exception may be wrapped by session proxy
				boolean found = false;
				Throwable cause = e;
				while (cause != null) {
					if (cause instanceof NonTranslatableExpressionException) {
						found = true;
						break;
					}
					cause = cause.getCause();
				}
				assertTrue(found, "Expected NonTranslatableExpressionException in cause chain, got: " + e);
			}
		}
	}
}
