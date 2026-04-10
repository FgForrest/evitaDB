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
import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.List;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests verifying documented structural invariants of the evitaDB indexing system. Each test
 * validates a specific guarantee about how indexes are created, maintained, and cleaned up
 * during entity lifecycle operations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Index structural invariants")
class IndexInvariantsTest implements EvitaTestSupport, IndexingTestSupport {

	private static final String DIR_INDEX_INVARIANTS_TEST = "indexInvariantsTest";
	private static final String DIR_INDEX_INVARIANTS_TEST_EXPORT = "indexInvariantsTest_export";

	private Evita evita;

	/**
	 * Sets up a CATEGORY entity schema with hierarchy and a PRODUCT entity schema that references
	 * CATEGORY with `indexedForFilteringAndPartitioning` and `faceted`, along with EAN attribute
	 * and prices. Creates the specified number of category entities.
	 *
	 * @param session       the active session
	 * @param categoryCount the number of CATEGORY entities to create (PKs 1..categoryCount)
	 */
	private static void setupProductCategorySchema(
		@Nonnull EvitaSessionContract session,
		int categoryCount
	) {
		session
			.defineEntitySchema(Entities.CATEGORY)
			.withHierarchy()
			.updateVia(session);

		for (int i = 1; i <= categoryCount; i++) {
			session.upsertEntity(session.createNewEntity(Entities.CATEGORY, i));
		}

		session
			.defineEntitySchema(Entities.PRODUCT)
			.withAttribute(ATTRIBUTE_EAN, String.class, thatIs -> thatIs.unique().sortable())
			.withPrice()
			.withReferenceToEntity(
				Entities.CATEGORY,
				Entities.CATEGORY,
				Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.indexedForFilteringAndPartitioning().faceted()
			)
			.updateVia(session);
	}

	/**
	 * Creates a PRODUCT entity with the given primary key, an EAN attribute, a CZK indexed price,
	 * an EUR non-indexed price, and references to the specified category primary keys.
	 *
	 * @param session     the active session
	 * @param productPk   the primary key for the new product
	 * @param ean         the EAN attribute value
	 * @param categoryPks the category primary keys to reference
	 */
	private static void createProductWithReferences(
		@Nonnull EvitaSessionContract session,
		int productPk,
		@Nonnull String ean,
		@Nonnull int... categoryPks
	) {
		final EntityBuilder builder = session.createNewEntity(Entities.PRODUCT, productPk)
			.setPriceInnerRecordHandling(PriceInnerRecordHandling.NONE)
			.setPrice(
				productPk * 10, PRICE_LIST_BASIC, CURRENCY_CZK,
				BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true
			)
			.setPrice(
				productPk * 10 + 1, PRICE_LIST_BASIC, CURRENCY_EUR,
				BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, false
			)
			.setAttribute(ATTRIBUTE_EAN, ean);

		for (final int categoryPk : categoryPks) {
			builder.setReference(Entities.CATEGORY, categoryPk);
		}

		session.upsertEntity(builder);
	}

	@BeforeEach
	void setUp() {
		cleanTestSubDirectoryWithRethrow(DIR_INDEX_INVARIANTS_TEST);
		cleanTestSubDirectoryWithRethrow(DIR_INDEX_INVARIANTS_TEST_EXPORT);
		this.evita = new Evita(
			getEvitaConfiguration()
		);
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanTestSubDirectoryWithRethrow(DIR_INDEX_INVARIANTS_TEST);
		cleanTestSubDirectoryWithRethrow(DIR_INDEX_INVARIANTS_TEST_EXPORT);
	}

	/**
	 * Creates the standard Evita configuration pointing to this test's storage directories.
	 *
	 * @return the configured `EvitaConfiguration` instance
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
					.storageDirectory(getTestDirectory().resolve(DIR_INDEX_INVARIANTS_TEST))
					.build()
			)
			.export(
				FileSystemExportOptions.builder()
					.directory(getTestDirectory().resolve(DIR_INDEX_INVARIANTS_TEST_EXPORT))
					.build()
			)
			.build();
	}

	/**
	 * Obtains the product `EntityCollectionContract` from the live catalog.
	 *
	 * @return the product entity collection
	 */
	@Nonnull
	private EntityCollectionContract getProductCollection() {
		final CatalogContract catalog = this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		return catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();
	}

	@Nested
	@DisplayName("Superset invariant")
	class SupersetInvariantTest {

		@Test
		@DisplayName("Every reduced index PK should be contained in global index")
		void shouldContainEveryReducedIndexPkInGlobalIndex() {
			IndexInvariantsTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					setupProductCategorySchema(session, 3);

					// create 3 products, each referencing a different category
					createProductWithReferences(session, 1, "EAN_001", 1);
					createProductWithReferences(session, 2, "EAN_002", 2);
					createProductWithReferences(session, 3, "EAN_003", 3);

					final EntityCollectionContract productCollection = getProductCollection();
					final EntityIndex globalIndex =
						IndexingTestSupport.getGlobalIndex(productCollection);
					assertNotNull(globalIndex, "Global index must exist");

					final Bitmap globalPks = globalIndex.getAllPrimaryKeys();

					// verify each reduced index's PKs are a subset of the global index
					for (int categoryPk = 1; categoryPk <= 3; categoryPk++) {
						final EntityIndex reducedIndex =
							IndexingTestSupport.getReferencedEntityIndex(
								productCollection, Entities.CATEGORY, categoryPk
							);
						assertNotNull(
							reducedIndex,
							"Reduced index for category " + categoryPk + " must exist"
						);

						final int[] reducedPks =
							reducedIndex.getAllPrimaryKeys().getArray();
						for (final int pk : reducedPks) {
							assertTrue(
								globalPks.contains(pk),
								"Product PK " + pk + " from reduced index (category "
									+ categoryPk + ") must be in global index"
							);
						}
					}
				}
			);
		}

		@Test
		@DisplayName("Superset invariant should hold after entity deletion")
		void shouldMaintainSupersetAfterEntityDeletion() {
			IndexInvariantsTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					setupProductCategorySchema(session, 2);

					// product 1 references category 1, product 2 references both
					createProductWithReferences(session, 1, "EAN_001", 1);
					createProductWithReferences(session, 2, "EAN_002", 1, 2);

					// delete product 1
					session.deleteEntity(Entities.PRODUCT, 1);

					final EntityCollectionContract productCollection = getProductCollection();
					final EntityIndex globalIndex =
						IndexingTestSupport.getGlobalIndex(productCollection);
					assertNotNull(globalIndex, "Global index must still exist");

					final Bitmap globalPks = globalIndex.getAllPrimaryKeys();

					// product 1 should no longer be in the global index
					assertFalse(
						globalPks.contains(1),
						"Deleted product PK 1 should not be in global index"
					);
					assertTrue(
						globalPks.contains(2),
						"Product PK 2 should still be in global index"
					);

					// verify superset invariant for remaining reduced index of category 1
					final EntityIndex cat1Index =
						IndexingTestSupport.getReferencedEntityIndex(
							productCollection, Entities.CATEGORY, 1
						);
					assertNotNull(
						cat1Index,
						"Reduced index for category 1 must still exist"
					);

					final int[] cat1Pks = cat1Index.getAllPrimaryKeys().getArray();
					for (final int pk : cat1Pks) {
						assertTrue(
							globalPks.contains(pk),
							"Product PK " + pk + " from reduced index (category 1) "
								+ "must be in global index"
						);
					}

					// verify superset invariant for reduced index of category 2
					final EntityIndex cat2Index =
						IndexingTestSupport.getReferencedEntityIndex(
							productCollection, Entities.CATEGORY, 2
						);
					assertNotNull(
						cat2Index,
						"Reduced index for category 2 must still exist"
					);

					final int[] cat2Pks = cat2Index.getAllPrimaryKeys().getArray();
					for (final int pk : cat2Pks) {
						assertTrue(
							globalPks.contains(pk),
							"Product PK " + pk + " from reduced index (category 2) "
								+ "must be in global index"
						);
					}
				}
			);
		}
	}

	@Nested
	@DisplayName("Empty index lifecycle")
	class EmptyIndexLifecycleTest {

		@Test
		@DisplayName("Global index should persist even when all entities are deleted")
		void shouldKeepGlobalIndexEvenWhenEmpty() {
			IndexInvariantsTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.PRODUCT)
						.withAttribute(
							ATTRIBUTE_EAN, String.class,
							thatIs -> thatIs.unique().sortable()
						)
						.updateVia(session);

					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 1)
							.setAttribute(ATTRIBUTE_EAN, "EAN_001")
					);

					// delete the only entity
					session.deleteEntity(Entities.PRODUCT, 1);

					// global index should still exist even though collection is empty
					final EntityCollectionContract productCollection = getProductCollection();
					final EntityIndex globalIndex =
						IndexingTestSupport.getGlobalIndex(productCollection, Scope.LIVE);
					assertNotNull(
						globalIndex,
						"Global index should persist even when all entities are deleted"
					);
					assertTrue(
						globalIndex.getAllPrimaryKeys().isEmpty(),
						"Global index should be empty after all entities are deleted"
					);
				}
			);
		}

		@Test
		@DisplayName("Reduced index should be removed when last reference is removed")
		void shouldRemoveEmptyReducedIndexWhenLastReferenceRemoved() {
			IndexInvariantsTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					setupProductCategorySchema(session, 1);

					createProductWithReferences(session, 1, "EAN_001", 1);

					final EntityCollectionContract productCollection = getProductCollection();

					// reduced index for category 1 should exist
					assertNotNull(
						IndexingTestSupport.getReferencedEntityIndex(
							productCollection, Entities.CATEGORY, 1
						),
						"Reduced index for category 1 must exist after product "
							+ "references it"
					);

					// remove the reference
					session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeReference(Entities.CATEGORY, 1)
						.upsertVia(session);

					// reduced index should be removed
					assertNull(
						IndexingTestSupport.getReferencedEntityIndex(
							productCollection, Entities.CATEGORY, 1
						),
						"Reduced index for category 1 should be removed when last "
							+ "reference is removed"
					);
				}
			);
		}

		@Test
		@DisplayName("Type index should be removed when last reference of that type is removed")
		void shouldRemoveEmptyTypeIndexWhenLastReferenceRemoved() {
			IndexInvariantsTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					setupProductCategorySchema(session, 1);

					createProductWithReferences(session, 1, "EAN_001", 1);

					final EntityCollectionContract productCollection = getProductCollection();

					// type index for CATEGORY should exist
					assertNotNull(
						IndexingTestSupport.getReferencedEntityTypeIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY
						),
						"Type index for CATEGORY must exist after product references it"
					);

					// remove the only reference to CATEGORY
					session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeReference(Entities.CATEGORY, 1)
						.upsertVia(session);

					// type index should be removed
					assertNull(
						IndexingTestSupport.getReferencedEntityTypeIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY
						),
						"Type index for CATEGORY should be removed when no references "
							+ "of that type remain"
					);
				}
			);
		}

		@Test
		@DisplayName("Reduced index should be recreated when a new reference is added")
		void shouldRecreateReducedIndexWhenNewReferenceAdded() {
			IndexInvariantsTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					setupProductCategorySchema(session, 1);

					// create product with reference -> reduced index exists
					createProductWithReferences(session, 1, "EAN_001", 1);

					final EntityCollectionContract productCollection = getProductCollection();
					assertNotNull(
						IndexingTestSupport.getReferencedEntityIndex(
							productCollection, Entities.CATEGORY, 1
						),
						"Reduced index for category 1 should exist initially"
					);

					// remove reference -> reduced index removed
					session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeReference(Entities.CATEGORY, 1)
						.upsertVia(session);

					assertNull(
						IndexingTestSupport.getReferencedEntityIndex(
							productCollection, Entities.CATEGORY, 1
						),
						"Reduced index for category 1 should be removed after "
							+ "reference removal"
					);

					// add reference back -> reduced index recreated
					session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setReference(Entities.CATEGORY, 1)
						.upsertVia(session);

					final EntityIndex recreatedIndex =
						IndexingTestSupport.getReferencedEntityIndex(
							productCollection, Entities.CATEGORY, 1
						);
					assertNotNull(
						recreatedIndex,
						"Reduced index for category 1 should be recreated when "
							+ "reference is added again"
					);
					assertTrue(
						recreatedIndex.getAllPrimaryKeys().contains(1),
						"Recreated reduced index should contain the product PK"
					);
				}
			);
		}
	}

	@Nested
	@DisplayName("Data propagation between indexes")
	class DataPropagationBetweenIndexesTest {

		@Test
		@DisplayName("Entity attributes and prices should propagate to reduced index")
		void shouldPropagateEntityAttributesToReducedIndex() {
			IndexInvariantsTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					setupProductCategorySchema(session, 1);

					createProductWithReferences(session, 1, "EAN_001", 1);

					final EntityCollectionContract productCollection = getProductCollection();
					final EntityIndex categoryIndex =
						IndexingTestSupport.getReferencedEntityIndex(
							productCollection, Entities.CATEGORY, 1
						);
					assertNotNull(
						categoryIndex,
						"Reduced index for category 1 must exist"
					);

					// verify entity-level data (EAN, prices) was propagated to the
					// reduced index for category 1
					IndexingTestSupport.assertDataWasPropagated(categoryIndex, 1);
				}
			);
		}

		@Test
		@DisplayName("Only reference data should propagate for filtering-only references")
		void shouldPropagateOnlyReferenceDataToReducedIndexForFilteringOnlyReference() {
			IndexInvariantsTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.CATEGORY)
						.withHierarchy()
						.updateVia(session);

					session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 1));

					// define PRODUCT with reference to CATEGORY that is
					// indexedForFiltering only (no partitioning)
					session
						.defineEntitySchema(Entities.PRODUCT)
						.withAttribute(
							ATTRIBUTE_EAN, String.class,
							thatIs -> thatIs.unique().sortable()
						)
						.withPrice()
						.withReferenceToEntity(
							Entities.CATEGORY,
							Entities.CATEGORY,
							Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs.indexedForFiltering()
								.withAttribute(
									ATTRIBUTE_CATEGORY_PRIORITY, Long.class,
									AttributeSchemaEditor::sortable
								)
						)
						.updateVia(session);

					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 1)
							.setPriceInnerRecordHandling(PriceInnerRecordHandling.NONE)
							.setPrice(
								1, PRICE_LIST_BASIC, CURRENCY_CZK,
								BigDecimal.ONE, BigDecimal.ZERO,
								BigDecimal.ONE, true
							)
							.setAttribute(ATTRIBUTE_EAN, "EAN_001")
							.setReference(
								Entities.CATEGORY, 1,
								thatIs -> thatIs.setAttribute(
									ATTRIBUTE_CATEGORY_PRIORITY, 1L
								)
							)
					);

					final EntityCollectionContract productCollection = getProductCollection();

					// for filtering-only references, a reduced index IS created
					// but contains only entity PKs and reference attributes --
					// entity-level attributes and prices are NOT propagated
					final EntityIndex categoryIndex =
						IndexingTestSupport.getReferencedEntityIndex(
							productCollection, Entities.CATEGORY, 1
						);
					assertNotNull(
						categoryIndex,
						"REFERENCED_ENTITY index must exist for filtering-only references"
					);

					// entity PK must be present in the reduced index
					assertTrue(
						categoryIndex.getAllPrimaryKeys().contains(1),
						"Reduced index must contain the referencing entity's PK"
					);

					// reference attribute (categoryPriority) must be present
					final EntitySchemaContract productSchema =
						session.getEntitySchemaOrThrow(Entities.PRODUCT);
					final ReferenceSchemaContract categoryRef =
						productSchema.getReference(Entities.CATEGORY).orElseThrow();
					final AttributeSchemaContract priorityAttr =
						categoryRef.getAttribute(ATTRIBUTE_CATEGORY_PRIORITY).orElseThrow();
					assertNotNull(
						categoryIndex.getSortIndex(categoryRef, priorityAttr, null),
						"Reference attribute sort index must exist in reduced index"
					);
					assertTrue(
						ArrayUtils.contains(
							categoryIndex.getSortIndex(categoryRef, priorityAttr, null)
								.getSortedRecords(),
							1
						),
						"Reference attribute sort index must contain the entity PK"
					);

					// entity-level attribute (EAN) must NOT be present
					assertNull(
						categoryIndex.getUniqueIndex(
							null, ATTRIBUTE_EAN_SCHEMA, null
						),
						"Entity-level unique index must not exist in "
							+ "FOR_FILTERING reduced index"
					);
					assertNull(
						categoryIndex.getFilterIndex(
							null, ATTRIBUTE_EAN_SCHEMA, null
						),
						"Entity-level filter index must not exist in "
							+ "FOR_FILTERING reduced index"
					);
					assertNull(
						categoryIndex.getSortIndex(
							null, ATTRIBUTE_EAN_SCHEMA, null
						),
						"Entity-level sort index must not exist in "
							+ "FOR_FILTERING reduced index"
					);

					// prices must NOT be present
					assertNull(
						categoryIndex.getPriceIndex(
							PRICE_LIST_BASIC, CURRENCY_CZK,
							PriceInnerRecordHandling.NONE
						),
						"Prices must not exist in FOR_FILTERING reduced index"
					);
				}
			);
		}
	}

	@Nested
	@DisplayName("ReferencedTypeEntityIndex cardinality tracking")
	class TypeIndexCardinalityTrackingTest {

		@Test
		@DisplayName("Type index should contain one entry per distinct referenced target")
		void shouldTrackMultipleEntitiesInTypeIndex() {
			IndexInvariantsTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					setupProductCategorySchema(session, 3);

					// create 3 products each referencing a different category
					// each creates a separate ReducedEntityIndex, so the type index
					// should contain 3 entries (one ReducedEntityIndex storage PK per target)
					createProductWithReferences(session, 1, "EAN_001", 1);
					createProductWithReferences(session, 2, "EAN_002", 2);
					createProductWithReferences(session, 3, "EAN_003", 3);

					final EntityCollectionContract productCollection = getProductCollection();
					final EntityIndex typeIndex =
						IndexingTestSupport.getReferencedEntityTypeIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY
						);
					assertNotNull(typeIndex, "Type index for CATEGORY must exist");

					final Bitmap typeIndexPks = typeIndex.getAllPrimaryKeys();
					assertEquals(
						3, typeIndexPks.size(),
						"Type index should contain 3 entries (one per referenced category)"
					);
				}
			);
		}

		@Test
		@DisplayName("Entry should be removed from type index when all references to that target are removed")
		void shouldRemoveFromTypeIndexWhenReferenceRemoved() {
			IndexInvariantsTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					setupProductCategorySchema(session, 3);

					// each product references a different category
					createProductWithReferences(session, 1, "EAN_001", 1);
					createProductWithReferences(session, 2, "EAN_002", 2);
					createProductWithReferences(session, 3, "EAN_003", 3);

					final EntityCollectionContract productCollection = getProductCollection();

					// remove reference from product 1 → category 1 ReducedEntityIndex becomes
					// empty → its storage PK is removed from the type index
					session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeReference(Entities.CATEGORY, 1)
						.upsertVia(session);

					final EntityIndex typeIndexAfterFirstRemoval =
						IndexingTestSupport.getReferencedEntityTypeIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY
						);
					assertNotNull(
						typeIndexAfterFirstRemoval,
						"Type index should still exist after removing one reference"
					);
					assertEquals(
						2,
						typeIndexAfterFirstRemoval.getAllPrimaryKeys().size(),
						"Type index should contain 2 entries after one target removed"
					);

					// remove references from remaining products
					session.getEntity(Entities.PRODUCT, 2, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeReference(Entities.CATEGORY, 2)
						.upsertVia(session);

					session.getEntity(Entities.PRODUCT, 3, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeReference(Entities.CATEGORY, 3)
						.upsertVia(session);

					// type index should be removed when no references remain
					assertNull(
						IndexingTestSupport.getReferencedEntityTypeIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY
						),
						"Type index should be removed when all references are removed"
					);
				}
			);
		}

		@Test
		@DisplayName("Type index entry should persist until cardinality drops to zero")
		void shouldNotRemoveFromTypeIndexUntilCardinalityReachesZero() {
			IndexInvariantsTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					setupProductCategorySchema(session, 1);

					// 3 products all reference the SAME category → they share a single
					// ReducedEntityIndex → the type index has exactly 1 entry
					createProductWithReferences(session, 1, "EAN_001", 1);
					createProductWithReferences(session, 2, "EAN_002", 1);
					createProductWithReferences(session, 3, "EAN_003", 1);

					final EntityCollectionContract productCollection = getProductCollection();
					final EntityIndex typeIndex =
						IndexingTestSupport.getReferencedEntityTypeIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY
						);
					assertNotNull(typeIndex, "Type index for CATEGORY must exist");
					assertEquals(
						1, typeIndex.getAllPrimaryKeys().size(),
						"Type index should contain 1 entry (single ReducedEntityIndex for category 1)"
					);

					// remove reference from product 1 → cardinality drops from 3 to 2,
					// but the type index entry stays (ReducedEntityIndex still has products 2 and 3)
					session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeReference(Entities.CATEGORY, 1)
						.upsertVia(session);

					EntityIndex typeIndexAfter =
						IndexingTestSupport.getReferencedEntityTypeIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY
						);
					assertNotNull(
						typeIndexAfter,
						"Type index must still exist (cardinality 2)"
					);
					assertEquals(
						1, typeIndexAfter.getAllPrimaryKeys().size(),
						"Type index should still contain 1 entry (cardinality decreased, not entry count)"
					);

					// remove reference from product 2 → cardinality drops from 2 to 1
					session.getEntity(Entities.PRODUCT, 2, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeReference(Entities.CATEGORY, 1)
						.upsertVia(session);

					typeIndexAfter =
						IndexingTestSupport.getReferencedEntityTypeIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY
						);
					assertNotNull(
						typeIndexAfter,
						"Type index must still exist (cardinality 1)"
					);
					assertEquals(
						1, typeIndexAfter.getAllPrimaryKeys().size(),
						"Type index should still contain 1 entry (one product still references category)"
					);

					// remove reference from product 3 → cardinality drops to 0,
					// ReducedEntityIndex becomes empty → type index entry removed → type index removed
					session.getEntity(Entities.PRODUCT, 3, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeReference(Entities.CATEGORY, 1)
						.upsertVia(session);

					assertNull(
						IndexingTestSupport.getReferencedEntityTypeIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY
						),
						"Type index should be removed when all references are removed"
					);
				}
			);
		}
	}

	@Nested
	@DisplayName("Hierarchy structure")
	class HierarchyStructureTest {

		@Test
		@DisplayName("Orphan children should be resolved when parent arrives later")
		void shouldResolveOrphanChildrenWhenParentArrives() {
			IndexInvariantsTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.CATEGORY)
						.withHierarchy()
						.updateVia(session);

					// create child entity BEFORE its parent exists
					session.upsertEntity(
						session.createNewEntity(Entities.CATEGORY, 2)
							.setParent(1)
					);

					// now create the parent
					session.upsertEntity(
						session.createNewEntity(Entities.CATEGORY, 1)
					);

					// verify hierarchy: querying within parent 1 should find child
					final List<EntityReferenceContract> results = session.query(
						query(
							collection(Entities.CATEGORY),
							filterBy(
								hierarchyWithinSelf(
									entityPrimaryKeyInSet(1)
								)
							)
						),
						EntityReferenceContract.class
					).getRecordData();

					final List<Integer> resultPks = results.stream()
						.map(EntityReferenceContract::getPrimaryKey)
						.toList();

					assertTrue(
						resultPks.contains(2),
						"Child category (PK 2) should be found under parent (PK 1) "
							+ "even though the child was created first"
					);
					assertTrue(
						resultPks.contains(1),
						"Parent category (PK 1) should also appear in the results"
					);
				}
			);
		}

		@Test
		@DisplayName("Hierarchy should remain intact when a leaf node is removed")
		void shouldMaintainHierarchyWhenLeafNodeRemoved() {
			IndexInvariantsTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.CATEGORY)
						.withHierarchy()
						.updateVia(session);

					// create hierarchy: A(1) -> B(3, parent=1) -> C(5, parent=3)
					session.upsertEntity(
						session.createNewEntity(Entities.CATEGORY, 1)
					);
					session.upsertEntity(
						session.createNewEntity(Entities.CATEGORY, 3).setParent(1)
					);
					session.upsertEntity(
						session.createNewEntity(Entities.CATEGORY, 5).setParent(3)
					);

					// remove leaf node C(5)
					session.deleteEntity(Entities.CATEGORY, 5);

					// query within root A(1) -> should find B(3) but not C(5)
					final List<EntityReferenceContract> results = session.query(
						query(
							collection(Entities.CATEGORY),
							filterBy(
								hierarchyWithinSelf(
									entityPrimaryKeyInSet(1)
								)
							)
						),
						EntityReferenceContract.class
					).getRecordData();

					final List<Integer> resultPks = results.stream()
						.map(EntityReferenceContract::getPrimaryKey)
						.toList();

					assertTrue(
						resultPks.contains(1),
						"Root category A (PK 1) should be in hierarchy results"
					);
					assertTrue(
						resultPks.contains(3),
						"Child category B (PK 3) should still be found under "
							+ "parent A (PK 1) after leaf removal"
					);
					assertFalse(
						resultPks.contains(5),
						"Deleted leaf category C (PK 5) should not appear in results"
					);

					// verify the entity was actually deleted
					assertTrue(
						session.getEntity(Entities.CATEGORY, 5).isEmpty(),
						"Category C (PK 5) should not exist after deletion"
					);
				}
			);
		}
	}

	@Nested
	@DisplayName("Group entity index invariants")
	class GroupEntityIndexInvariantsTest {

		/**
		 * Sets up a CATEGORY + BRAND schema, then a PRODUCT schema that references CATEGORY
		 * with both REFERENCED_ENTITY and REFERENCED_GROUP_ENTITY components, grouped by BRAND,
		 * using `indexedForFilteringAndPartitioning`. Also adds EAN attribute and prices.
		 *
		 * @param session       the active session
		 * @param categoryCount the number of CATEGORY entities to create (PKs 1..categoryCount)
		 */
		private void setupProductCategorySchemaWithGroupComponent(
			@Nonnull EvitaSessionContract session,
			int categoryCount
		) {
			session.defineEntitySchema(Entities.BRAND).updateVia(session);
			session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));

			session
				.defineEntitySchema(Entities.CATEGORY)
				.withHierarchy()
				.updateVia(session);

			for (int i = 1; i <= categoryCount; i++) {
				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, i));
			}

			session
				.defineEntitySchema(Entities.PRODUCT)
				.withAttribute(ATTRIBUTE_EAN, String.class, thatIs -> thatIs.unique().sortable())
				.withPrice()
				.withReferenceToEntity(
					Entities.CATEGORY,
					Entities.CATEGORY,
					Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs
						.indexedWithComponents(
							ReferenceIndexedComponents.REFERENCED_ENTITY,
							ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
						)
						.indexedForFilteringAndPartitioning()
						.faceted()
						.withGroupTypeRelatedToEntity(Entities.BRAND)
						.withAttribute(
							ATTRIBUTE_CATEGORY_PRIORITY, Long.class,
							thatIs -> thatIs.filterable().sortable()
						)
				)
				.updateVia(session);
		}

		/**
		 * Creates a PRODUCT entity with the given primary key, EAN, prices, and references
		 * to categories with the specified brand group.
		 *
		 * @param session       the active session
		 * @param productPk     the product PK
		 * @param ean           the EAN value
		 * @param priority      the categoryPriority reference attribute value
		 * @param brandPk       the brand (group) PK to assign to each reference
		 * @param categoryPks   the category PKs to reference
		 */
		private void createProductWithGroupReferences(
			@Nonnull EvitaSessionContract session,
			int productPk,
			@Nonnull String ean,
			long priority,
			int brandPk,
			@Nonnull int... categoryPks
		) {
			final EntityBuilder builder = session.createNewEntity(Entities.PRODUCT, productPk)
				.setPriceInnerRecordHandling(PriceInnerRecordHandling.NONE)
				.setPrice(
					productPk * 10, PRICE_LIST_BASIC, CURRENCY_CZK,
					BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true
				)
				.setPrice(
					productPk * 10 + 1, PRICE_LIST_BASIC, CURRENCY_EUR,
					BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, false
				)
				.setAttribute(ATTRIBUTE_EAN, ean);

			for (final int categoryPk : categoryPks) {
				builder.setReference(
					Entities.CATEGORY, categoryPk,
					whichIs -> {
						whichIs.setGroup(Entities.BRAND, brandPk);
						whichIs.setAttribute(ATTRIBUTE_CATEGORY_PRIORITY, priority);
					}
				);
			}

			session.upsertEntity(builder);
		}

		@Test
		@DisplayName("Every group index PK should be contained in global index (superset invariant)")
		void shouldContainEveryGroupIndexPkInGlobalIndex() {
			IndexInvariantsTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					setupProductCategorySchemaWithGroupComponent(session, 3);

					// create brands 2 and 3 (brand 1 is already created by the setup method)
					session.upsertEntity(session.createNewEntity(Entities.BRAND, 2));
					session.upsertEntity(session.createNewEntity(Entities.BRAND, 3));

					// each product references a different category with a different brand group
					createProductWithGroupReferences(session, 1, "EAN_001", 10L, 1, 1);
					createProductWithGroupReferences(session, 2, "EAN_002", 20L, 2, 2);
					createProductWithGroupReferences(session, 3, "EAN_003", 30L, 3, 3);

					final EntityCollectionContract productCollection = getProductCollection();
					final EntityIndex globalIndex =
						IndexingTestSupport.getGlobalIndex(productCollection);
					assertNotNull(globalIndex, "Global index must exist");

					final Bitmap globalPks = globalIndex.getAllPrimaryKeys();

					// group entity indexes are keyed by (referenceName, groupPk)
					// verify each group entity index's PKs are a subset of the global index
					for (int brandPk = 1; brandPk <= 3; brandPk++) {
						final EntityIndex groupIndex =
							IndexingTestSupport.getReferencedGroupEntityIndex(
								productCollection, Scope.LIVE, Entities.CATEGORY, brandPk
							);
						assertNotNull(
							groupIndex,
							"Group entity index for brand " + brandPk + " must exist"
						);

						final int[] groupPks = groupIndex.getAllPrimaryKeys().getArray();
						for (final int pk : groupPks) {
							assertTrue(
								globalPks.contains(pk),
								"Product PK " + pk + " from group entity index (brand "
									+ brandPk + ") must be in global index"
							);
						}
					}
				}
			);
		}

		@Test
		@DisplayName("Group entity TYPE index should be removed when all references are removed")
		void shouldRemoveEmptyGroupEntityTypeIndexWhenLastReferenceRemoved() {
			IndexInvariantsTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					setupProductCategorySchemaWithGroupComponent(session, 1);

					createProductWithGroupReferences(session, 1, "EAN_001", 10L, 1, 1);

					final EntityCollectionContract productCollection = getProductCollection();

					// group entity type index should exist
					assertNotNull(
						IndexingTestSupport.getReferencedGroupEntityTypeIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY
						),
						"Group entity type index should exist after product references category"
					);

					// remove the reference
					session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeReference(Entities.CATEGORY, 1)
						.upsertVia(session);

					// group entity type index should be removed
					assertNull(
						IndexingTestSupport.getReferencedGroupEntityTypeIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY
						),
						"Group entity type index should be removed when no references remain"
					);
				}
			);
		}

		@Test
		@DisplayName("Group entity type index entry should persist until cardinality drops to zero")
		void shouldNotRemoveFromGroupTypeIndexUntilCardinalityReachesZero() {
			IndexInvariantsTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					setupProductCategorySchemaWithGroupComponent(session, 1);

					// 3 products all reference the SAME category with the same group
					createProductWithGroupReferences(session, 1, "EAN_001", 10L, 1, 1);
					createProductWithGroupReferences(session, 2, "EAN_002", 20L, 1, 1);
					createProductWithGroupReferences(session, 3, "EAN_003", 30L, 1, 1);

					final EntityCollectionContract productCollection = getProductCollection();
					EntityIndex typeIndex =
						IndexingTestSupport.getReferencedGroupEntityTypeIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY
						);
					assertNotNull(typeIndex, "Group type index must exist");
					assertEquals(
						1, typeIndex.getAllPrimaryKeys().size(),
						"Group type index should contain 1 entry (single ReducedGroupEntityIndex)"
					);

					// remove reference from product 1
					session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeReference(Entities.CATEGORY, 1)
						.upsertVia(session);

					typeIndex = IndexingTestSupport.getReferencedGroupEntityTypeIndex(
						productCollection, Scope.LIVE, Entities.CATEGORY
					);
					assertNotNull(typeIndex, "Group type index must still exist (2 products remain)");
					assertEquals(
						1, typeIndex.getAllPrimaryKeys().size(),
						"Group type index should still have 1 entry"
					);

					// remove reference from product 2
					session.getEntity(Entities.PRODUCT, 2, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeReference(Entities.CATEGORY, 1)
						.upsertVia(session);

					typeIndex = IndexingTestSupport.getReferencedGroupEntityTypeIndex(
						productCollection, Scope.LIVE, Entities.CATEGORY
					);
					assertNotNull(typeIndex, "Group type index must still exist (1 product remains)");

					// remove reference from product 3 → all gone
					session.getEntity(Entities.PRODUCT, 3, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeReference(Entities.CATEGORY, 1)
						.upsertVia(session);

					assertNull(
						IndexingTestSupport.getReferencedGroupEntityTypeIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY
						),
						"Group type index should be removed when all references are removed"
					);
				}
			);
		}

		@Test
		@DisplayName("Entity attributes and prices should propagate to group index for partitioning references")
		void shouldPropagateEntityAttributesAndPricesToGroupIndex() {
			IndexInvariantsTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					setupProductCategorySchemaWithGroupComponent(session, 1);

					createProductWithGroupReferences(session, 1, "EAN_001", 10L, 1, 1);

					final EntityCollectionContract productCollection = getProductCollection();
					final EntityIndex groupIndex =
						IndexingTestSupport.getReferencedGroupEntityIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY, 1
						);
					assertNotNull(groupIndex, "Group entity index must exist");

					// entity PK must be present
					assertTrue(
						groupIndex.getAllPrimaryKeys().contains(1),
						"Group index must contain the referencing entity's PK"
					);

					// entity-level unique index (EAN) should NOT exist
					// (ReducedGroupEntityIndex has no-op unique inserts)
					assertNull(
						groupIndex.getUniqueIndex(null, ATTRIBUTE_EAN_SCHEMA, null),
						"Entity-level unique index (EAN) must NOT exist in group index (no-op)"
					);

					// entity-level filter index (EAN) should exist
					// (partitioning reference propagates entity attributes to filter index)
					assertNotNull(
						groupIndex.getFilterIndex(null, ATTRIBUTE_EAN_SCHEMA, null),
						"Entity-level filter index (EAN) should exist in group index"
							+ " for partitioning references"
					);

					// entity-level sort index (EAN) should NOT exist
					// (ReducedGroupEntityIndex has no-op sort)
					assertNull(
						groupIndex.getSortIndex(null, ATTRIBUTE_EAN_SCHEMA, null),
						"Entity-level sort index (EAN) must NOT exist in group index (no-op)"
					);

					// prices should be present (indexed CZK price)
					assertNotNull(
						groupIndex.getPriceIndex(
							PRICE_LIST_BASIC, CURRENCY_CZK, PriceInnerRecordHandling.NONE
						),
						"CZK price index should exist in group index"
					);
					assertTrue(
						groupIndex.getPriceIndex(
							PRICE_LIST_BASIC, CURRENCY_CZK, PriceInnerRecordHandling.NONE
						).getIndexedPriceEntityIds().contains(1),
						"CZK price index should contain the entity PK"
					);

					// non-indexed EUR price should NOT be present
					assertNull(
						groupIndex.getPriceIndex(
							PRICE_LIST_BASIC, CURRENCY_EUR, PriceInnerRecordHandling.NONE
						),
						"Non-indexed EUR price should not exist in group index"
					);

					// reference attribute (categoryPriority) - filter should exist
					final EntitySchemaContract productSchema =
						session.getEntitySchemaOrThrow(Entities.PRODUCT);
					final ReferenceSchemaContract categoryRef =
						productSchema.getReference(Entities.CATEGORY).orElseThrow();
					final AttributeSchemaContract priorityAttr =
						categoryRef.getAttribute(ATTRIBUTE_CATEGORY_PRIORITY).orElseThrow();
					assertNotNull(
						groupIndex.getFilterIndex(categoryRef, priorityAttr, null),
						"Reference attribute filter index must exist in group index"
					);

					// reference attribute - sort should NOT exist (no-op)
					assertNull(
						groupIndex.getSortIndex(categoryRef, priorityAttr, null),
						"Reference attribute sort index must NOT exist in group index (no-op)"
					);
				}
			);
		}

		@Test
		@DisplayName("Only reference data should propagate to group index for filtering-only references")
		void shouldPropagateOnlyReferenceDataToGroupIndexForFilteringOnlyReference() {
			IndexInvariantsTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.BRAND).updateVia(session);
					session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));

					session
						.defineEntitySchema(Entities.CATEGORY)
						.withHierarchy()
						.updateVia(session);
					session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 1));

					// define PRODUCT with filtering-only reference + group component
					session
						.defineEntitySchema(Entities.PRODUCT)
						.withAttribute(
							ATTRIBUTE_EAN, String.class,
							thatIs -> thatIs.unique().sortable()
						)
						.withPrice()
						.withReferenceToEntity(
							Entities.CATEGORY,
							Entities.CATEGORY,
							Cardinality.ZERO_OR_MORE,
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

					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 1)
							.setPriceInnerRecordHandling(PriceInnerRecordHandling.NONE)
							.setPrice(
								1, PRICE_LIST_BASIC, CURRENCY_CZK,
								BigDecimal.ONE, BigDecimal.ZERO,
								BigDecimal.ONE, true
							)
							.setAttribute(ATTRIBUTE_EAN, "EAN_001")
							.setReference(
								Entities.CATEGORY, 1,
								thatIs -> {
									thatIs.setGroup(Entities.BRAND, 1);
									thatIs.setAttribute(ATTRIBUTE_CATEGORY_PRIORITY, 1L);
								}
							)
					);

					final EntityCollectionContract productCollection = getProductCollection();

					// group entity index should exist
					final EntityIndex groupIndex =
						IndexingTestSupport.getReferencedGroupEntityIndex(
							productCollection, Scope.LIVE, Entities.CATEGORY, 1
						);
					assertNotNull(groupIndex, "Group entity index must exist");

					// entity PK must be present
					assertTrue(
						groupIndex.getAllPrimaryKeys().contains(1),
						"Group index must contain the referencing entity's PK"
					);

					// reference attribute (categoryPriority) filter should exist
					final EntitySchemaContract productSchema =
						session.getEntitySchemaOrThrow(Entities.PRODUCT);
					final ReferenceSchemaContract categoryRef =
						productSchema.getReference(Entities.CATEGORY).orElseThrow();
					final AttributeSchemaContract priorityAttr =
						categoryRef.getAttribute(ATTRIBUTE_CATEGORY_PRIORITY).orElseThrow();
					assertNotNull(
						groupIndex.getFilterIndex(categoryRef, priorityAttr, null),
						"Reference attribute filter index must exist in group index"
					);

					// entity-level attributes must NOT be present
					// (filtering-only reference does not propagate entity attributes)
					assertNull(
						groupIndex.getUniqueIndex(null, ATTRIBUTE_EAN_SCHEMA, null),
						"Entity-level unique index must not exist in filtering-only group index"
					);
					assertNull(
						groupIndex.getFilterIndex(null, ATTRIBUTE_EAN_SCHEMA, null),
						"Entity-level filter index must not exist in filtering-only group index"
					);
					assertNull(
						groupIndex.getSortIndex(null, ATTRIBUTE_EAN_SCHEMA, null),
						"Entity-level sort index must not exist in filtering-only group index"
					);

					// prices must NOT be present
					assertNull(
						groupIndex.getPriceIndex(
							PRICE_LIST_BASIC, CURRENCY_CZK, PriceInnerRecordHandling.NONE
						),
						"Prices must not exist in filtering-only group index"
					);
				}
			);
		}
	}
}
