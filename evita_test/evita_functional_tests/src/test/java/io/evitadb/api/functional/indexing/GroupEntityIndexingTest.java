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

package io.evitadb.api.functional.indexing;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.ReducedGroupEntityIndex;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for group entity reference indexing operations introduced by `REFERENCED_GROUP_ENTITY_TYPE` and
 * `REFERENCED_GROUP_ENTITY` entity index types. These indexes are controlled by the
 * {@link ReferenceIndexedComponents#REFERENCED_GROUP_ENTITY} enum value and enable the `groupHaving` filtering
 * constraint to operate on dedicated per-group indexes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Group entity reference indexing operations")
class GroupEntityIndexingTest implements EvitaTestSupport, IndexingTestSupport {
	private static final String DIR_GROUP_ENTITY_INDEXING_TEST = "groupEntityIndexingTest";
	private static final String DIR_GROUP_ENTITY_INDEXING_TEST_EXPORT = "groupEntityIndexingTest_export";
	private Evita evita;

	@BeforeEach
	void setUp() {
		cleanTestSubDirectoryWithRethrow(DIR_GROUP_ENTITY_INDEXING_TEST);
		cleanTestSubDirectoryWithRethrow(DIR_GROUP_ENTITY_INDEXING_TEST_EXPORT);
		this.evita = new Evita(
			getEvitaConfiguration()
		);
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanTestSubDirectoryWithRethrow(DIR_GROUP_ENTITY_INDEXING_TEST);
		cleanTestSubDirectoryWithRethrow(DIR_GROUP_ENTITY_INDEXING_TEST_EXPORT);
	}

	/**
	 * Builds the standard Evita configuration pointing to the test directories.
	 *
	 * @return the Evita configuration for the test
	 */
	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return EvitaConfiguration.builder()
			.server(
				ServerOptions.builder()
					.closeSessionsAfterSecondsOfInactivity(-1)
					.build()
			)
			.storage(
				StorageOptions.builder()
					.storageDirectory(getTestDirectory().resolve(DIR_GROUP_ENTITY_INDEXING_TEST))
					.build()
			)
			.export(
				FileSystemExportOptions.builder()
					.directory(getTestDirectory().resolve(DIR_GROUP_ENTITY_INDEXING_TEST_EXPORT))
					.build()
			)
			.build();
	}

	@Nested
	@DisplayName("Group entity index lifecycle")
	class GroupEntityIndexLifecycleTest {

		@Test
		@DisplayName("Should create group entity indexes when reference has group and component enabled")
		void shouldCreateGroupEntityIndexWhenReferenceHasGroupAndComponentEnabled() {
			GroupEntityIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					// define schemas for the group and referenced entity types
					session.defineEntitySchema(Entities.BRAND).updateVia(session);
					session.defineEntitySchema(Entities.CATEGORY).updateVia(session);

					// define product schema with CATEGORY reference grouped by BRAND
					session.defineEntitySchema(Entities.PRODUCT)
						.withReferenceToEntity(
							Entities.CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs
								.indexedWithComponents(
									ReferenceIndexedComponents.REFERENCED_ENTITY,
									ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
								)
								.withGroupTypeRelatedToEntity(Entities.BRAND)
						)
						.updateVia(session);

					// create the referenced entities
					session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));
					session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 1));

					// create a product with a reference to CATEGORY with group BRAND
					session.createNewEntity(Entities.PRODUCT, 1)
						.setReference(
							Entities.CATEGORY, 1,
							whichIs -> whichIs.setGroup(Entities.BRAND, 1)
						)
						.upsertVia(session);

					// verify the indexes were created
					final CatalogContract catalog = GroupEntityIndexingTest.this.evita
						.getCatalogInstance(TEST_CATALOG).orElseThrow();
					final EntityCollectionContract productCollection =
						catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();

					// group entity index should exist
					final EntityIndex groupEntityIndex = IndexingTestSupport
						.getReferencedGroupEntityIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY, 1
						);
					assertNotNull(
						groupEntityIndex,
						"REFERENCED_GROUP_ENTITY index should exist for CATEGORY ref with group PK 1"
					);
					assertTrue(
						groupEntityIndex.getAllPrimaryKeys().contains(1),
						"Group entity index should contain the product PK"
					);

					// group entity type index should exist
					assertNotNull(
						IndexingTestSupport.getReferencedGroupEntityTypeIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY
						),
						"REFERENCED_GROUP_ENTITY_TYPE index should exist for CATEGORY reference"
					);

					// regular entity indexes should also exist (both components enabled)
					assertNotNull(
						IndexingTestSupport.getReferencedEntityIndex(
							productCollection, Entities.CATEGORY, 1
						),
						"REFERENCED_ENTITY index should also exist when both components are enabled"
					);
					assertNotNull(
						IndexingTestSupport.getReferencedEntityTypeIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY
						),
						"REFERENCED_ENTITY_TYPE index should exist when both components are enabled"
					);
				}
			);
		}

		@Test
		@DisplayName("Should not create group entity indexes when only entity component is enabled")
		void shouldNotCreateGroupEntityIndexWhenOnlyEntityComponentEnabled() {
			GroupEntityIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.BRAND).updateVia(session);
					session.defineEntitySchema(Entities.CATEGORY).updateVia(session);

					// use default indexedForFiltering which enables only REFERENCED_ENTITY
					session.defineEntitySchema(Entities.PRODUCT)
						.withReferenceToEntity(
							Entities.CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs
								.indexedForFiltering()
								.withGroupTypeRelatedToEntity(Entities.BRAND)
						)
						.updateVia(session);

					session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));
					session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 1));

					session.createNewEntity(Entities.PRODUCT, 1)
						.setReference(
							Entities.CATEGORY, 1,
							whichIs -> whichIs.setGroup(Entities.BRAND, 1)
						)
						.upsertVia(session);

					final CatalogContract catalog = GroupEntityIndexingTest.this.evita
						.getCatalogInstance(TEST_CATALOG).orElseThrow();
					final EntityCollectionContract productCollection =
						catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();

					// group entity indexes should NOT exist
					assertNull(
						IndexingTestSupport.getReferencedGroupEntityIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY, 1
						),
						"REFERENCED_GROUP_ENTITY index should NOT exist with default indexing"
					);
					assertNull(
						IndexingTestSupport.getReferencedGroupEntityTypeIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY
						),
						"REFERENCED_GROUP_ENTITY_TYPE index should NOT exist with default indexing"
					);

					// regular entity index should exist
					assertNotNull(
						IndexingTestSupport.getReferencedEntityIndex(
							productCollection, Entities.CATEGORY, 1
						),
						"REFERENCED_ENTITY index should exist for indexed reference"
					);
				}
			);
		}

		@Test
		@DisplayName("Should remove group entity index when last entity reference is removed")
		void shouldRemoveGroupEntityIndexWhenLastEntityReferenceRemoved() {
			GroupEntityIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.BRAND).updateVia(session);
					session.defineEntitySchema(Entities.CATEGORY).updateVia(session);

					session.defineEntitySchema(Entities.PRODUCT)
						.withReferenceToEntity(
							Entities.CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs
								.indexedWithComponents(
									ReferenceIndexedComponents.REFERENCED_ENTITY,
									ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
								)
								.withGroupTypeRelatedToEntity(Entities.BRAND)
						)
						.updateVia(session);

					session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));
					session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 1));

					// create a product with a reference including a group
					session.createNewEntity(Entities.PRODUCT, 1)
						.setReference(
							Entities.CATEGORY, 1,
							whichIs -> whichIs.setGroup(Entities.BRAND, 1)
						)
						.upsertVia(session);

					final CatalogContract catalog = GroupEntityIndexingTest.this.evita
						.getCatalogInstance(TEST_CATALOG).orElseThrow();
					final EntityCollectionContract productCollection =
						catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();

					// verify group index exists before removal
					assertNotNull(
						IndexingTestSupport.getReferencedGroupEntityIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY, 1
						),
						"Group entity index should exist before reference removal"
					);

					// remove the reference from the product
					session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeReference(Entities.CATEGORY, 1)
						.upsertVia(session);

					// verify group index is removed after reference removal
					assertNull(
						IndexingTestSupport.getReferencedGroupEntityIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY, 1
						),
						"Group entity index should be removed after last reference is removed"
					);
				}
			);
		}
	}

	@Nested
	@DisplayName("Group entity index data")
	class GroupEntityIndexDataTest {

		@Test
		@DisplayName("Should index reference attributes in group entity index")
		void shouldIndexReferenceAttributesInGroupEntityIndex() {
			GroupEntityIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.BRAND).updateVia(session);
					session.defineEntitySchema(Entities.CATEGORY).updateVia(session);

					session.defineEntitySchema(Entities.PRODUCT)
						.withReferenceToEntity(
							Entities.CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs
								.indexedWithComponents(
									ReferenceIndexedComponents.REFERENCED_ENTITY,
									ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
								)
								.withGroupTypeRelatedToEntity(Entities.BRAND)
								.withAttribute(
									ATTRIBUTE_CATEGORY_PRIORITY, Long.class,
									thatIs -> thatIs.filterable().sortable()
								)
						)
						.updateVia(session);

					session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));
					session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 1));

					// create a product with a reference attribute and a group
					session.createNewEntity(Entities.PRODUCT, 1)
						.setReference(
							Entities.CATEGORY, 1,
							whichIs -> {
								whichIs.setGroup(Entities.BRAND, 1);
								whichIs.setAttribute(ATTRIBUTE_CATEGORY_PRIORITY, 42L);
							}
						)
						.upsertVia(session);

					final CatalogContract catalog = GroupEntityIndexingTest.this.evita
						.getCatalogInstance(TEST_CATALOG).orElseThrow();
					final EntityCollectionContract productCollection =
						catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();

					// verify the group entity index contains the product PK
					final EntityIndex groupEntityIndex = IndexingTestSupport
						.getReferencedGroupEntityIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY, 1
						);
					assertNotNull(groupEntityIndex, "Group entity index should exist");
					assertInstanceOf(
						ReducedGroupEntityIndex.class, groupEntityIndex,
						"REFERENCED_GROUP_ENTITY index must be backed by ReducedGroupEntityIndex"
					);
					assertTrue(
						groupEntityIndex.getAllPrimaryKeys().contains(1),
						"Group entity index should contain the product PK"
					);

					// get schema references for attribute assertions
					final ReferenceSchemaContract categoryRef =
						session.getEntitySchemaOrThrow(Entities.PRODUCT)
							.getReference(Entities.CATEGORY).orElseThrow();
					final AttributeSchemaContract priorityAttr =
						categoryRef.getAttribute(ATTRIBUTE_CATEGORY_PRIORITY).orElseThrow();

					// filter index for categoryPriority MUST exist in group entity index
					assertNotNull(
						groupEntityIndex.getFilterIndex(categoryRef, priorityAttr, null),
						"Filter index for categoryPriority must exist in group entity index"
					);
					assertTrue(
						groupEntityIndex.getFilterIndex(categoryRef, priorityAttr, null)
							.getAllRecords().contains(1),
						"Filter index in group entity index must contain the product PK"
					);

					// sort index for categoryPriority must NOT exist (no-op in group entity index)
					assertNull(
						groupEntityIndex.getSortIndex(categoryRef, priorityAttr, null),
						"Sort index must NOT exist in group entity index (no-op)"
					);

					// also verify via the entity-level index for comparison
					final EntityIndex entityIndex = IndexingTestSupport
						.getReferencedEntityIndex(
							productCollection, Entities.CATEGORY, 1
						);
					assertNotNull(entityIndex, "Entity index should also exist");
					assertTrue(
						entityIndex.getAllPrimaryKeys().contains(1),
						"Entity index should contain the product PK"
					);

					// entity-level index SHOULD have sort index (contrast with group index)
					assertNotNull(
						entityIndex.getSortIndex(categoryRef, priorityAttr, null),
						"Sort index must exist in regular entity index"
					);
					assertTrue(
						ArrayUtils.contains(
							entityIndex.getSortIndex(categoryRef, priorityAttr, null)
								.getSortedRecords(),
							1
						),
						"Sort index in entity index must contain the product PK"
					);
				}
			);
		}

		@Test
		@DisplayName("Should propagate entity PKs to group entity index from multiple products")
		void shouldPropagateEntityPrimaryKeyToGroupEntityIndex() {
			GroupEntityIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.BRAND).updateVia(session);
					session.defineEntitySchema(Entities.CATEGORY).updateVia(session);

					session.defineEntitySchema(Entities.PRODUCT)
						.withReferenceToEntity(
							Entities.CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs
								.indexedWithComponents(
									ReferenceIndexedComponents.REFERENCED_ENTITY,
									ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
								)
								.withGroupTypeRelatedToEntity(Entities.BRAND)
						)
						.updateVia(session);

					session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));
					session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 1));

					// create two products referencing the same CATEGORY with the same group
					session.createNewEntity(Entities.PRODUCT, 1)
						.setReference(
							Entities.CATEGORY, 1,
							whichIs -> whichIs.setGroup(Entities.BRAND, 1)
						)
						.upsertVia(session);

					session.createNewEntity(Entities.PRODUCT, 2)
						.setReference(
							Entities.CATEGORY, 1,
							whichIs -> whichIs.setGroup(Entities.BRAND, 1)
						)
						.upsertVia(session);

					final CatalogContract catalog = GroupEntityIndexingTest.this.evita
						.getCatalogInstance(TEST_CATALOG).orElseThrow();
					final EntityCollectionContract productCollection =
						catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();

					// the group entity index should contain both product PKs
					final EntityIndex groupEntityIndex = IndexingTestSupport
						.getReferencedGroupEntityIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY, 1
						);
					assertNotNull(groupEntityIndex, "Group entity index should exist");
					assertTrue(
						groupEntityIndex.getAllPrimaryKeys().contains(1),
						"Group entity index should contain product PK 1"
					);
					assertTrue(
						groupEntityIndex.getAllPrimaryKeys().contains(2),
						"Group entity index should contain product PK 2"
					);
					assertEquals(
						2,
						groupEntityIndex.getAllPrimaryKeys().size(),
						"Group entity index should contain exactly 2 product PKs"
					);
				}
			);
		}

		@Test
		@DisplayName("Should not create unique index in group entity index (no-op)")
		void shouldNotCreateUniqueIndexInGroupEntityIndex() {
			GroupEntityIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.BRAND).updateVia(session);
					session.defineEntitySchema(Entities.CATEGORY).updateVia(session);

					session.defineEntitySchema(Entities.PRODUCT)
						.withReferenceToEntity(
							Entities.CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs
								.indexedWithComponents(
									ReferenceIndexedComponents.REFERENCED_ENTITY,
									ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
								)
								.withGroupTypeRelatedToEntity(Entities.BRAND)
								.withAttribute(
									ATTRIBUTE_CATEGORY_PRIORITY, Long.class,
									// unique implies filterable; separate filterable() is not needed
									thatIs -> thatIs.unique()
								)
						)
						.updateVia(session);

					session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));
					session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 1));

					session.createNewEntity(Entities.PRODUCT, 1)
						.setReference(
							Entities.CATEGORY, 1,
							whichIs -> {
								whichIs.setGroup(Entities.BRAND, 1);
								whichIs.setAttribute(ATTRIBUTE_CATEGORY_PRIORITY, 42L);
							}
						)
						.upsertVia(session);

					final CatalogContract catalog = GroupEntityIndexingTest.this.evita
						.getCatalogInstance(TEST_CATALOG).orElseThrow();
					final EntityCollectionContract productCollection =
						catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();

					final ReferenceSchemaContract categoryRef =
						session.getEntitySchemaOrThrow(Entities.PRODUCT)
							.getReference(Entities.CATEGORY).orElseThrow();
					final AttributeSchemaContract priorityAttr =
						categoryRef.getAttribute(ATTRIBUTE_CATEGORY_PRIORITY).orElseThrow();

					// entity index should have unique index
					final EntityIndex entityIndex = IndexingTestSupport
						.getReferencedEntityIndex(
							productCollection, Entities.CATEGORY, 1
						);
					assertNotNull(entityIndex, "Entity index should exist");
					assertNotNull(
						entityIndex.getUniqueIndex(categoryRef, priorityAttr, null),
						"Unique index must exist in regular entity index"
					);

					// group entity index should NOT have unique index (no-op)
					final EntityIndex groupEntityIndex = IndexingTestSupport
						.getReferencedGroupEntityIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY, 1
						);
					assertNotNull(groupEntityIndex, "Group entity index should exist");
					assertNull(
						groupEntityIndex.getUniqueIndex(categoryRef, priorityAttr, null),
						"Unique index must NOT exist in group entity index (no-op)"
					);

					// filter index SHOULD exist in group entity index
					// (unique implies filterable, and filter indexes are maintained in group index)
					assertNotNull(
						groupEntityIndex.getFilterIndex(categoryRef, priorityAttr, null),
						"Filter index must exist in group entity index"
					);
				}
			);
		}
	}

	@Nested
	@DisplayName("Group index PK lifecycle")
	class GroupIndexPkLifecycleTest {

		@Test
		@DisplayName("Should retain other product in group index when one product's reference is removed")
		void shouldRetainEntityInGroupIndexWhenOneReferenceRemoved() {
			GroupEntityIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.BRAND).updateVia(session);
					session.defineEntitySchema(Entities.CATEGORY).updateVia(session);

					session.defineEntitySchema(Entities.PRODUCT)
						.withReferenceToEntity(
							Entities.CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs
								.indexedWithComponents(
									ReferenceIndexedComponents.REFERENCED_ENTITY,
									ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
								)
								.withGroupTypeRelatedToEntity(Entities.BRAND)
						)
						.updateVia(session);

					session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));
					session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 1));

					// create two products referencing the same category with the same group
					session.createNewEntity(Entities.PRODUCT, 1)
						.setReference(
							Entities.CATEGORY, 1,
							whichIs -> whichIs.setGroup(Entities.BRAND, 1)
						)
						.upsertVia(session);

					session.createNewEntity(Entities.PRODUCT, 2)
						.setReference(
							Entities.CATEGORY, 1,
							whichIs -> whichIs.setGroup(Entities.BRAND, 1)
						)
						.upsertVia(session);

					final CatalogContract catalog = GroupEntityIndexingTest.this.evita
						.getCatalogInstance(TEST_CATALOG).orElseThrow();
					final EntityCollectionContract productCollection =
						catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();

					// both products should be in the group entity index
					EntityIndex groupEntityIndex = IndexingTestSupport
						.getReferencedGroupEntityIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY, 1
						);
					assertNotNull(groupEntityIndex, "Group entity index should exist");
					assertEquals(
						2, groupEntityIndex.getAllPrimaryKeys().size(),
						"Group entity index should contain 2 product PKs"
					);

					// remove only product 1's reference
					session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeReference(Entities.CATEGORY, 1)
						.upsertVia(session);

					// group entity index should still exist and contain product 2
					groupEntityIndex = IndexingTestSupport
						.getReferencedGroupEntityIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY, 1
						);
					assertNotNull(
						groupEntityIndex,
						"Group entity index should still exist after removing one product's reference"
					);
					assertFalse(
						groupEntityIndex.getAllPrimaryKeys().contains(1),
						"Product 1 should be removed from group entity index"
					);
					assertTrue(
						groupEntityIndex.getAllPrimaryKeys().contains(2),
						"Product 2 should still be in group entity index"
					);
					assertEquals(
						1, groupEntityIndex.getAllPrimaryKeys().size(),
						"Group entity index should contain exactly 1 product PK"
					);

					// remove product 2's reference → group entity index should be removed
					session.getEntity(Entities.PRODUCT, 2, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeReference(Entities.CATEGORY, 1)
						.upsertVia(session);

					assertNull(
						IndexingTestSupport.getReferencedGroupEntityIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY, 1
						),
						"Group entity index should be removed after all references are removed"
					);
				}
			);
		}
	}

	@Nested
	@DisplayName("Group reassignment")
	class GroupReassignmentTest {

		@Test
		@DisplayName("Should keep entity in same group index when group changes (index keyed by referenced entity PK)")
		void shouldMoveEntityBetweenGroupIndexesOnGroupChange() {
			GroupEntityIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.BRAND).updateVia(session);
					session.defineEntitySchema(Entities.CATEGORY).updateVia(session);

					session.defineEntitySchema(Entities.PRODUCT)
						.withReferenceToEntity(
							Entities.CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs
								.indexedWithComponents(
									ReferenceIndexedComponents.REFERENCED_ENTITY,
									ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
								)
								.withGroupTypeRelatedToEntity(Entities.BRAND)
						)
						.updateVia(session);

					session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));
					session.upsertEntity(session.createNewEntity(Entities.BRAND, 2));
					session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 1));

					// create a product referencing CATEGORY 1 with group BRAND 1
					session.createNewEntity(Entities.PRODUCT, 1)
						.setReference(
							Entities.CATEGORY, 1,
							whichIs -> whichIs.setGroup(Entities.BRAND, 1)
						)
						.upsertVia(session);

					final CatalogContract catalog = GroupEntityIndexingTest.this.evita
						.getCatalogInstance(TEST_CATALOG).orElseThrow();
					final EntityCollectionContract productCollection =
						catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();

					// verify the group entity index exists for referenced entity CATEGORY 1
					final EntityIndex groupIndexBeforeReassignment = IndexingTestSupport
						.getReferencedGroupEntityIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY, 1
						);
					assertNotNull(
						groupIndexBeforeReassignment,
						"Group entity index for CATEGORY 1 should exist"
					);
					assertTrue(
						groupIndexBeforeReassignment.getAllPrimaryKeys().contains(1),
						"Group entity index should contain the product PK before reassignment"
					);

					// change the group from BRAND 1 to BRAND 2
					session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setReference(
							Entities.CATEGORY, 1,
							whichIs -> whichIs.setGroup(Entities.BRAND, 2)
						)
						.upsertVia(session);

					// the group entity index is keyed by referenced entity PK (CATEGORY 1),
					// not by group PK — so it persists after a group reassignment
					final EntityIndex groupIndexAfterReassignment = IndexingTestSupport
						.getReferencedGroupEntityIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY, 1
						);
					assertNotNull(
						groupIndexAfterReassignment,
						"Group entity index for CATEGORY 1 should still exist after group reassignment"
					);
					assertTrue(
						groupIndexAfterReassignment.getAllPrimaryKeys().contains(1),
						"Group entity index should still contain the product PK after group reassignment"
					);
				}
			);
		}

		@Test
		@DisplayName("Should remove from group index when reference is removed entirely")
		void shouldRemoveFromGroupIndexWhenGroupCleared() {
			GroupEntityIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.BRAND).updateVia(session);
					session.defineEntitySchema(Entities.CATEGORY).updateVia(session);

					session.defineEntitySchema(Entities.PRODUCT)
						.withReferenceToEntity(
							Entities.CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs
								.indexedWithComponents(
									ReferenceIndexedComponents.REFERENCED_ENTITY,
									ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
								)
								.withGroupTypeRelatedToEntity(Entities.BRAND)
						)
						.updateVia(session);

					session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));
					session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 1));

					// create a product with a reference including a group
					session.createNewEntity(Entities.PRODUCT, 1)
						.setReference(
							Entities.CATEGORY, 1,
							whichIs -> whichIs.setGroup(Entities.BRAND, 1)
						)
						.upsertVia(session);

					final CatalogContract catalog = GroupEntityIndexingTest.this.evita
						.getCatalogInstance(TEST_CATALOG).orElseThrow();
					final EntityCollectionContract productCollection =
						catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();

					// verify group index exists
					assertNotNull(
						IndexingTestSupport.getReferencedGroupEntityIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY, 1
						),
						"Group entity index should exist before reference removal"
					);

					// remove the entire reference
					session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeReference(Entities.CATEGORY, 1)
						.upsertVia(session);

					// group index should be cleaned up
					assertNull(
						IndexingTestSupport.getReferencedGroupEntityIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY, 1
						),
						"Group entity index should be removed after reference removal"
					);
				}
			);
		}
	}

	@Nested
	@DisplayName("ReferenceIndexedComponents configuration")
	class ReferenceIndexedComponentsConfigurationTest {

		@Test
		@DisplayName("Should index both entity and group components")
		void shouldIndexBothEntityAndGroupComponents() {
			GroupEntityIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.BRAND).updateVia(session);
					session.defineEntitySchema(Entities.CATEGORY).updateVia(session);

					session.defineEntitySchema(Entities.PRODUCT)
						.withReferenceToEntity(
							Entities.CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs
								.indexedWithComponents(
									ReferenceIndexedComponents.REFERENCED_ENTITY,
									ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
								)
								.withGroupTypeRelatedToEntity(Entities.BRAND)
						)
						.updateVia(session);

					session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));
					session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 1));

					session.createNewEntity(Entities.PRODUCT, 1)
						.setReference(
							Entities.CATEGORY, 1,
							whichIs -> whichIs.setGroup(Entities.BRAND, 1)
						)
						.upsertVia(session);

					final CatalogContract catalog = GroupEntityIndexingTest.this.evita
						.getCatalogInstance(TEST_CATALOG).orElseThrow();
					final EntityCollectionContract productCollection =
						catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();

					// both entity and group indexes should exist
					assertNotNull(
						IndexingTestSupport.getReferencedEntityIndex(
							productCollection, Entities.CATEGORY, 1
						),
						"REFERENCED_ENTITY index should exist"
					);
					assertNotNull(
						IndexingTestSupport.getReferencedEntityTypeIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY
						),
						"REFERENCED_ENTITY_TYPE index should exist"
					);
					assertNotNull(
						IndexingTestSupport.getReferencedGroupEntityIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY, 1
						),
						"REFERENCED_GROUP_ENTITY index should exist"
					);
					assertNotNull(
						IndexingTestSupport.getReferencedGroupEntityTypeIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY
						),
						"REFERENCED_GROUP_ENTITY_TYPE index should exist"
					);
				}
			);
		}

		@Test
		@DisplayName("Should index only group component without entity component")
		void shouldIndexOnlyGroupComponentWithoutEntityComponent() {
			GroupEntityIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.BRAND).updateVia(session);
					session.defineEntitySchema(Entities.CATEGORY).updateVia(session);

					// configure only the REFERENCED_GROUP_ENTITY component
					session.defineEntitySchema(Entities.PRODUCT)
						.withReferenceToEntity(
							Entities.CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs
								.indexedWithComponents(
									ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
								)
								.withGroupTypeRelatedToEntity(Entities.BRAND)
						)
						.updateVia(session);

					session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));
					session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 1));

					session.createNewEntity(Entities.PRODUCT, 1)
						.setReference(
							Entities.CATEGORY, 1,
							whichIs -> whichIs.setGroup(Entities.BRAND, 1)
						)
						.upsertVia(session);

					final CatalogContract catalog = GroupEntityIndexingTest.this.evita
						.getCatalogInstance(TEST_CATALOG).orElseThrow();
					final EntityCollectionContract productCollection =
						catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();

					// group indexes should exist
					assertNotNull(
						IndexingTestSupport.getReferencedGroupEntityIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY, 1
						),
						"REFERENCED_GROUP_ENTITY index should exist"
					);
					assertNotNull(
						IndexingTestSupport.getReferencedGroupEntityTypeIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY
						),
						"REFERENCED_GROUP_ENTITY_TYPE index should exist"
					);

					// entity (reduced) indexes should NOT exist
					assertNull(
						IndexingTestSupport.getReferencedEntityIndex(
							productCollection, Entities.CATEGORY, 1
						),
						"REFERENCED_ENTITY index should NOT exist when only group component is enabled"
					);
					assertNull(
						IndexingTestSupport.getReferencedEntityTypeIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY
						),
						"REFERENCED_ENTITY_TYPE index should NOT exist with only group component"
					);
				}
			);
		}

		@Test
		@DisplayName("Should respect per-scope indexed components configuration")
		void shouldRespectPerScopeIndexedComponentsConfiguration() {
			GroupEntityIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.BRAND).updateVia(session);
					session.defineEntitySchema(Entities.CATEGORY).updateVia(session);

					// LIVE: entity only; ARCHIVED: entity + group
					session.defineEntitySchema(Entities.PRODUCT)
						.withReferenceToEntity(
							Entities.CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs
								.indexedWithComponentsInScope(
									Scope.LIVE,
									ReferenceIndexedComponents.REFERENCED_ENTITY
								)
								.indexedWithComponentsInScope(
									Scope.ARCHIVED,
									ReferenceIndexedComponents.REFERENCED_ENTITY,
									ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
								)
								.withGroupTypeRelatedToEntity(Entities.BRAND)
						)
						.updateVia(session);

					session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));
					session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 1));

					session.createNewEntity(Entities.PRODUCT, 1)
						.setReference(
							Entities.CATEGORY, 1,
							whichIs -> whichIs.setGroup(Entities.BRAND, 1)
						)
						.upsertVia(session);

					// verify the schema configuration was applied correctly
					final SealedEntitySchema productSchema =
						session.getEntitySchema(Entities.PRODUCT).orElseThrow();
					final ReferenceSchemaContract categoryRefSchema =
						productSchema.getReference(Entities.CATEGORY).orElseThrow();

					// LIVE scope should have only REFERENCED_ENTITY
					assertTrue(
						categoryRefSchema.getIndexedComponents(Scope.LIVE)
							.contains(ReferenceIndexedComponents.REFERENCED_ENTITY),
						"LIVE: schema should contain REFERENCED_ENTITY component"
					);
					assertFalse(
						categoryRefSchema.getIndexedComponents(Scope.LIVE)
							.contains(ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY),
						"LIVE: schema should NOT contain REFERENCED_GROUP_ENTITY component"
					);

					// ARCHIVED scope should have both
					assertTrue(
						categoryRefSchema.getIndexedComponents(Scope.ARCHIVED)
							.contains(ReferenceIndexedComponents.REFERENCED_ENTITY),
						"ARCHIVED: schema should contain REFERENCED_ENTITY component"
					);
					assertTrue(
						categoryRefSchema.getIndexedComponents(Scope.ARCHIVED)
							.contains(ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY),
						"ARCHIVED: schema should contain REFERENCED_GROUP_ENTITY component"
					);

					// verify LIVE scope index behavior
					final CatalogContract catalog = GroupEntityIndexingTest.this.evita
						.getCatalogInstance(TEST_CATALOG).orElseThrow();
					final EntityCollectionContract productCollection =
						catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();

					// LIVE scope: entity indexes should exist, group indexes should NOT
					assertNotNull(
						IndexingTestSupport.getReferencedEntityIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY, 1
						),
						"LIVE: REFERENCED_ENTITY index should exist"
					);
					assertNull(
						IndexingTestSupport.getReferencedGroupEntityIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY, 1
						),
						"LIVE: REFERENCED_GROUP_ENTITY index should NOT exist"
					);
					assertNull(
						IndexingTestSupport.getReferencedGroupEntityTypeIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY
						),
						"LIVE: REFERENCED_GROUP_ENTITY_TYPE index should NOT exist"
					);
				}
			);
		}
	}
}
