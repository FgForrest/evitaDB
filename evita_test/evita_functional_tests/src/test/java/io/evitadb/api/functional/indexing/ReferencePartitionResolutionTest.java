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

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.exception.RollbackException;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.ReferencePartition;
import io.evitadb.index.ReferencePartitions;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.Functions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.api.query.QueryConstraints.scope;
import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that the reference partition resolvers of {@link EntityCollection} (by primary key),
 * {@link QueryPlanningContext} and the data-store-reader overloads of {@link ReferencePartitions} (by key and by
 * target, as production reads another collection) read what the engine really holds: the partitions of a
 * reference with and without duplicates, the per-collection primary key namespace, and partitions created by a
 * mutation that has not been committed (`ALIVE`) or flushed (`WARMING_UP`) yet - and that the end-of-mutation sweep
 * retires a partition its last owner left.
 *
 * The resolution rules themselves are pinned over plain maps by `io.evitadb.index.ReferencePartitionsTest`; this test
 * is about where each surface reads from.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Reference partition resolution against a real catalog")
@Tag(INDEXING)
@Tag(REFERENCE)
@Tag(TRANSACTION)
class ReferencePartitionResolutionTest implements EvitaTestSupport {
	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_CATEGORY = "category";
	private static final String ENTITY_STORE = "store";
	/** A reference without duplicates - one partition per category. */
	private static final String REF_CATEGORIES = "categories";
	/** The reflected counterpart of {@link #REF_CATEGORIES} - partitions in the category collection. */
	private static final String REF_PRODUCTS = "products";
	/** A reference with duplicates - one partition per store and country. */
	private static final String REF_STORES = "stores";
	private static final String ATTR_COUNTRY = "country";
	private static final int CATEGORY_PK = 1;
	private static final int NEW_CATEGORY_PK = 2;
	private static final int STORE_PK = 1;

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("ReferencePartitionResolutionTest");
		this.evita = new Evita(
			newTestEvitaConfigurationBuilder(this.paths)
				.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build())
				.build()
		);
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	/**
	 * Every surface resolves the committed partitions of a reference without duplicates (one per target) and of a
	 * reference with duplicates (one per target and representative value) to the very objects the collection holds.
	 *
	 * @param state catalog state the scenario runs in
	 */
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("resolves the partitions of both cardinalities on every surface")
	void shouldResolvePartitionsOfBothCardinalitiesOnEverySurface(@Nonnull CatalogState state) {
		prepare(state);
		this.evita.updateCatalog(
			TEST_CATALOG,
			(Consumer<EvitaSessionContract>) session -> {
				final EntityCollection products = collectionOf(ENTITY_PRODUCT);
				final EntitySchemaContract productSchema = products.getSchema();
				final ReferenceSchemaContract categories = productSchema.getReferenceOrThrowException(REF_CATEGORIES);
				final ReferenceSchemaContract stores = productSchema.getReferenceOrThrowException(REF_STORES);

				final ReferencePartition category = partitionOf(
					products, Scope.LIVE, new RepresentativeReferenceKey(new ReferenceKey(REF_CATEGORIES, CATEGORY_PK))
				);
				assertNotNull(category);
				assertArrayEquals(new int[]{1, 2}, category.getAllPrimaryKeys().getArray());
				assertArrayEquals(
					new ReferencePartition[]{category},
					partitionsOfTarget(products, Scope.LIVE, categories, CATEGORY_PK)
				);
				assertSame(category, products.resolvePartitionIfExists(category.getPrimaryKey()));

				final ReferencePartition[] storePartitions = partitionsOfTarget(products, Scope.LIVE, stores, STORE_PK);
				assertEquals(2, storePartitions.length);
				assertArrayEquals(
					new Serializable[][]{{"DE"}, {"US"}},
					Arrays.stream(storePartitions)
						.map(it -> it.getRepresentativeReferenceKey().representativeAttributeValues())
						.sorted((a, b) -> ((String) a[0]).compareTo((String) b[0]))
						.toArray(Serializable[][]::new)
				);
				for (ReferencePartition storePartition : storePartitions) {
					assertSame(storePartition, products.resolvePartitionIfExists(storePartition.getPrimaryKey()));
				}
				// the group family and the other index kinds are no partitions
				assertNull(products.resolvePartitionIfExists(products.getGlobalIndex().getPrimaryKey()));

				// the planning context of a query over the products reads the same objects
				final QueryPlanningContext queryContext = planningContext(products, session);
				assertArrayEquals(
					storePartitions,
					queryContext.resolvePartitionsOfTarget(productSchema, stores, Scope.LIVE, STORE_PK)
				);
				assertArrayEquals(
					new ReferencePartition[]{category},
					queryContext.resolvePartitionsOfTarget(productSchema, categories, Scope.LIVE, CATEGORY_PK)
				);
				assertSame(category, queryContext.resolvePartition(category.getPrimaryKey()));
				assertSame(category, queryContext.resolvePartitionIfExists(category.getPrimaryKey()));
				assertSame(
					category,
					queryContext.resolvePartitionIfExists(Scope.LIVE, category.getRepresentativeReferenceKey())
				);
				assertSame(
					category,
					queryContext.resolvePartitionIfExists(
						ENTITY_PRODUCT, Scope.LIVE, category.getRepresentativeReferenceKey()
					)
				);
				// and answers a primary key of another index kind as the collection does - null or a named failure
				final int globalPrimaryKey = products.getGlobalIndex().getPrimaryKey();
				assertNull(queryContext.resolvePartitionIfExists(globalPrimaryKey));
				final GenericEvitaInternalError notAPartition = assertThrows(
					GenericEvitaInternalError.class, () -> queryContext.resolvePartition(globalPrimaryKey)
				);
				assertTrue(
					notAPartition.getPrivateMessage().contains("`" + ENTITY_PRODUCT + "`"),
					notAPartition.getPrivateMessage()
				);
				// a partition is no group index, and the typed by-primary-key path of the group family is strict
				assertThrows(
					GenericEvitaInternalError.class,
					() -> queryContext.getReducedGroupEntityIndexByPrimaryKey(category.getPrimaryKey())
				);
			}
		);
	}

	/**
	 * The partitions of an archived owner live in the archived scope, next to the live partitions of the same target,
	 * and every surface resolves each scope to its own partition - the planning context of a query over both scopes
	 * included.
	 *
	 * @param state catalog state the scenario runs in
	 */
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("resolves the archived partitions next to the live ones on every surface")
	void shouldResolveArchivedPartitionsOnEverySurface(@Nonnull CatalogState state) {
		prepare(state);
		this.evita.updateCatalog(
			TEST_CATALOG,
			(Consumer<EvitaSessionContract>) session -> {
				session.archiveEntity(ENTITY_PRODUCT, 2);
			}
		);
		this.evita.updateCatalog(
			TEST_CATALOG,
			(Consumer<EvitaSessionContract>) session -> {
				final EntityCollection products = collectionOf(ENTITY_PRODUCT);
				final EntitySchemaContract productSchema = products.getSchema();
				final ReferenceSchemaContract categories = productSchema.getReferenceOrThrowException(REF_CATEGORIES);
				final RepresentativeReferenceKey categoryKey = new RepresentativeReferenceKey(
					new ReferenceKey(REF_CATEGORIES, CATEGORY_PK)
				);

				final ReferencePartition live = partitionOf(products, Scope.LIVE, categoryKey);
				final ReferencePartition archived = partitionOf(products, Scope.ARCHIVED, categoryKey);
				assertNotNull(live);
				assertNotNull(archived);
				assertArrayEquals(new int[]{1}, live.getAllPrimaryKeys().getArray());
				assertArrayEquals(new int[]{2}, archived.getAllPrimaryKeys().getArray());
				assertEquals(Scope.ARCHIVED, archived.getIndexKey().scope());
				assertArrayEquals(
					new ReferencePartition[]{archived},
					partitionsOfTarget(products, Scope.ARCHIVED, categories, CATEGORY_PK)
				);
				assertArrayEquals(
					new ReferencePartition[]{live},
					partitionsOfTarget(products, Scope.LIVE, categories, CATEGORY_PK)
				);
				assertSame(archived, products.resolvePartitionIfExists(archived.getPrimaryKey()));

				// the planning context of a query over both scopes resolves each scope to its own partition
				final QueryPlanningContext queryContext = planningContext(
					products, session, Scope.LIVE, Scope.ARCHIVED
				);
				assertSame(archived, queryContext.resolvePartitionIfExists(Scope.ARCHIVED, categoryKey));
				assertSame(live, queryContext.resolvePartitionIfExists(Scope.LIVE, categoryKey));
				assertArrayEquals(
					new ReferencePartition[]{archived},
					queryContext.resolvePartitionsOfTarget(productSchema, categories, Scope.ARCHIVED, CATEGORY_PK)
				);
				assertArrayEquals(
					new ReferencePartition[]{live},
					queryContext.resolvePartitionsOfTarget(productSchema, categories, Scope.LIVE, CATEGORY_PK)
				);
				assertSame(archived, queryContext.resolvePartition(archived.getPrimaryKey()));
			}
		);
	}

	/**
	 * Partition primary keys come from a per-collection sequence, so one number names a partition in the product
	 * collection and a different one in the category collection. Every resolver must read the collection it is told
	 * to, and the planning context of a query must resolve the partitions of another collection in that collection
	 * rather than in its own snapshot - the primary keys the type index of another collection lists included.
	 *
	 * @param state catalog state the scenario runs in
	 */
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("resolves one primary key in the collection it is asked about")
	void shouldResolvePrimaryKeyInTheNamedCollection(@Nonnull CatalogState state) {
		prepare(state);
		this.evita.updateCatalog(
			TEST_CATALOG,
			(Consumer<EvitaSessionContract>) session -> {
				final EntityCollection products = collectionOf(ENTITY_PRODUCT);
				final EntityCollection categories = collectionOf(ENTITY_CATEGORY);
				// every new owner of the category adds a reflected partition to the category collection, which
				// numbers its indexes up to where the product collection numbers its store partitions
				for (int productPk = 10; productPk < 14; productPk++) {
					session.createNewEntity(ENTITY_PRODUCT, productPk)
						.setReference(REF_CATEGORIES, CATEGORY_PK)
						.upsertVia(session);
				}
				// find a number that names a partition in both collections - the trap needs a collision
				int sharedPrimaryKey = -1;
				for (int pk = 1; pk < 100 && sharedPrimaryKey < 0; pk++) {
					if (
						products.resolvePartitionIfExists(pk) != null &&
							categories.resolvePartitionIfExists(pk) != null
					) {
						sharedPrimaryKey = pk;
					}
				}
				assertTrue(
					sharedPrimaryKey > 0,
					"No primary key names a partition in both collections, so the test proves nothing"
				);
				final ReferencePartition productPartition = products.resolvePartitionIfExists(sharedPrimaryKey);
				final ReferencePartition categoryPartition = categories.resolvePartitionIfExists(sharedPrimaryKey);
				assertNotNull(productPartition);
				assertNotNull(categoryPartition);
				assertNotEquals(
					productPartition.getRepresentativeReferenceKey().referenceName(),
					categoryPartition.getRepresentativeReferenceKey().referenceName()
				);
				assertEquals(REF_PRODUCTS, categoryPartition.getRepresentativeReferenceKey().referenceName());

				// the planning context of each collection resolves the number in its own collection
				final QueryPlanningContext queryContext = planningContext(products, session);
				final QueryPlanningContext categoryQueryContext = planningContext(categories, session);
				assertSame(productPartition, queryContext.resolvePartition(sharedPrimaryKey));
				assertSame(categoryPartition, categoryQueryContext.resolvePartition(sharedPrimaryKey));

				// the planning context of a product query finds the partitions of the category collection there
				final EntitySchemaContract categorySchema = categories.getSchema();
				final ReferenceSchemaContract reflected = categorySchema.getReferenceOrThrowException(REF_PRODUCTS);
				final int reflectedTarget = categoryPartition.getReferenceKey().primaryKey();
				assertArrayEquals(
					partitionsOfTarget(categories, Scope.LIVE, reflected, reflectedTarget),
					queryContext.resolvePartitionsOfTarget(categorySchema, reflected, Scope.LIVE, reflectedTarget)
				);

				// and the planning context of a category query resolves the primary keys the type index of the product
				// collection lists in the product collection rather than in its own snapshot
				final EntitySchemaContract productSchema = products.getSchema();
				final ReferenceSchemaContract stores = productSchema.getReferenceOrThrowException(REF_STORES);
				final ReferencePartition[] storePartitions = partitionsOfTarget(products, Scope.LIVE, stores, STORE_PK);
				assertEquals(2, storePartitions.length);
				for (ReferencePartition storePartition : storePartitions) {
					assertNotNull(
						categories.getIndexByPrimaryKeyIfExists(storePartition.getPrimaryKey()),
						"The category collection holds no index numbered " + storePartition.getPrimaryKey() +
							", so the test proves nothing"
					);
				}
				assertArrayEquals(
					storePartitions,
					categoryQueryContext.resolvePartitionsOfTarget(productSchema, stores, Scope.LIVE, STORE_PK)
				);
			}
		);
	}

	/**
	 * A partition created by a mutation of the current session is visible to every resolver surface before the
	 * session ends. In `ALIVE` the session is rolled back and the partition must then be gone - so the positive
	 * assertions cannot have passed on committed state. `WARMING_UP` has no rollback; there the control is that the
	 * partition does not resolve before the mutation.
	 *
	 * @param state catalog state the scenario runs in
	 */
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("sees partitions created by the current, uncommitted mutation")
	void shouldSeePartitionsCreatedByUncommittedMutation(@Nonnull CatalogState state) {
		prepare(state);
		final RepresentativeReferenceKey newCategoryKey = new RepresentativeReferenceKey(
			new ReferenceKey(REF_CATEGORIES, NEW_CATEGORY_PK)
		);
		final RepresentativeReferenceKey newStoreKey = new RepresentativeReferenceKey(
			new ReferenceKey(REF_STORES, STORE_PK), new Serializable[]{"CZ"}
		);
		final RepresentativeReferenceKey reflectedKey = new RepresentativeReferenceKey(
			new ReferenceKey(REF_PRODUCTS, 3)
		);
		final Runnable scenario = () -> this.evita.updateCatalog(
			TEST_CATALOG,
			(Consumer<EvitaSessionContract>) session -> {
				final EntityCollection products = collectionOf(ENTITY_PRODUCT);
				final EntityCollection categories = collectionOf(ENTITY_CATEGORY);
				assertNull(partitionOf(products, Scope.LIVE, newCategoryKey));
				assertNull(partitionOf(products, Scope.LIVE, newStoreKey));
				assertNull(partitionOf(categories, Scope.LIVE, reflectedKey));

				session.createNewEntity(ENTITY_PRODUCT, 3)
					.setReference(REF_CATEGORIES, NEW_CATEGORY_PK)
					.setOrUpdateReference(
						REF_STORES, STORE_PK, Functions.alwaysFalse(),
						whichIs -> whichIs.setAttribute(ATTR_COUNTRY, "CZ")
					)
					.upsertVia(session);

				final EntitySchemaContract productSchema = products.getSchema();
				// by key, through the data store reader of the collection - the route of cross-collection reads
				final ReferencePartition newCategory = partitionOf(products, Scope.LIVE, newCategoryKey);
				assertNotNull(
					newCategory, "the collection's data store reader does not see the partition just created"
				);
				assertArrayEquals(new int[]{3}, newCategory.getAllPrimaryKeys().getArray());
				assertArrayEquals(
					new ReferencePartition[]{newCategory},
					partitionsOfTarget(
						products, Scope.LIVE, productSchema.getReferenceOrThrowException(REF_CATEGORIES),
						NEW_CATEGORY_PK
					)
				);
				final ReferencePartition[] storePartitions = partitionsOfTarget(
					products, Scope.LIVE, productSchema.getReferenceOrThrowException(REF_STORES), STORE_PK
				);
				assertEquals(3, storePartitions.length, "the new duplicate partition is missing from its target");
				// by primary key, through the collection itself
				assertSame(newCategory, products.resolvePartitionIfExists(newCategory.getPrimaryKey()));
				for (ReferencePartition storePartition : storePartitions) {
					assertSame(storePartition, products.resolvePartitionIfExists(storePartition.getPrimaryKey()));
				}

				// the reflected partition created in another collection by the same upsert
				final ReferencePartition reflected = partitionOf(categories, Scope.LIVE, reflectedKey);
				assertNotNull(reflected, "the reflected partition of the category collection is not visible");
				assertArrayEquals(new int[]{NEW_CATEGORY_PK}, reflected.getAllPrimaryKeys().getArray());
				assertSame(reflected, categories.resolvePartitionIfExists(reflected.getPrimaryKey()));

				// a planning context opened now reads the in-flight state of its own and of other collections
				final QueryPlanningContext queryContext = planningContext(products, session);
				assertSame(newCategory, queryContext.resolvePartition(newCategory.getPrimaryKey()));
				assertSame(newCategory, queryContext.resolvePartitionIfExists(newCategory.getPrimaryKey()));
				assertArrayEquals(
					storePartitions,
					queryContext.resolvePartitionsOfTarget(
						productSchema, productSchema.getReferenceOrThrowException(REF_STORES), Scope.LIVE, STORE_PK
					)
				);
				assertSame(
					reflected,
					queryContext.resolvePartitionIfExists(ENTITY_CATEGORY, Scope.LIVE, reflectedKey)
				);
				// by key in the queried collection - read from the query's index snapshot, which must carry the
				// partitions the session created before the query was planned
				assertSame(newCategory, queryContext.resolvePartitionIfExists(Scope.LIVE, newCategoryKey));
				assertSame(
					newCategory, queryContext.resolvePartitionIfExists(ENTITY_PRODUCT, Scope.LIVE, newCategoryKey)
				);
				assertArrayEquals(
					new ReferencePartition[]{newCategory},
					queryContext.resolvePartitionsOfTarget(
						productSchema, productSchema.getReferenceOrThrowException(REF_CATEGORIES), Scope.LIVE,
						NEW_CATEGORY_PK
					)
				);
				// and a real query answered through those partitions sees the new owner
				assertEquals(
					List.of(3),
					session.queryListOfEntityReferences(
						query(
							collection(ENTITY_PRODUCT),
							filterBy(referenceHaving(REF_CATEGORIES, entityPrimaryKeyInSet(NEW_CATEGORY_PK)))
						)
					).stream().map(EntityClassifier::getPrimaryKeyOrThrowException).toList()
				);

				if (state == CatalogState.ALIVE) {
					session.setRollbackOnly();
				}
			}
		);

		if (state == CatalogState.ALIVE) {
			// the rollback requested above surfaces as an exception once the session closes
			assertThrows(RollbackException.class, scenario::run);
			// the partitions lived in the rolled-back transaction only
			assertNull(partitionOf(collectionOf(ENTITY_PRODUCT), Scope.LIVE, newCategoryKey));
			assertNull(partitionOf(collectionOf(ENTITY_PRODUCT), Scope.LIVE, newStoreKey));
			assertNull(partitionOf(collectionOf(ENTITY_CATEGORY), Scope.LIVE, reflectedKey));
		} else {
			scenario.run();
		}
	}

	/**
	 * The end-of-mutation sweep retires a partition whose last owner has left - for partitions acquired through the
	 * partition write acquisition: a non-duplicate partition, a duplicate (representative) partition, and the
	 * reflected partition the implicit mutation of the category leaves empty in the other collection. The partitions
	 * are gone as soon as the removing upsert finished, and stay gone after the session ends.
	 *
	 * @param state catalog state the scenario runs in
	 */
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("retires a partition emptied by the removal of its last owner")
	void shouldRetirePartitionEmptiedByRemovalOfItsLastOwner(@Nonnull CatalogState state) {
		prepare(state);
		final int ownerPk = 4;
		final RepresentativeReferenceKey categoryKey = new RepresentativeReferenceKey(
			new ReferenceKey(REF_CATEGORIES, NEW_CATEGORY_PK)
		);
		final RepresentativeReferenceKey storeKey = new RepresentativeReferenceKey(
			new ReferenceKey(REF_STORES, STORE_PK), new Serializable[]{"PL"}
		);
		final RepresentativeReferenceKey reflectedKey = new RepresentativeReferenceKey(
			new ReferenceKey(REF_PRODUCTS, ownerPk)
		);
		this.evita.updateCatalog(
			TEST_CATALOG,
			(Consumer<EvitaSessionContract>) session -> {
				session.createNewEntity(ENTITY_PRODUCT, ownerPk)
					.setReference(REF_CATEGORIES, NEW_CATEGORY_PK)
					.setOrUpdateReference(
						REF_STORES, STORE_PK, Functions.alwaysFalse(),
						whichIs -> whichIs.setAttribute(ATTR_COUNTRY, "PL")
					)
					.upsertVia(session);
			}
		);
		// the premise: the only owner created all three partitions
		assertNotNull(partitionOf(collectionOf(ENTITY_PRODUCT), Scope.LIVE, categoryKey));
		assertNotNull(partitionOf(collectionOf(ENTITY_PRODUCT), Scope.LIVE, storeKey));
		assertNotNull(partitionOf(collectionOf(ENTITY_CATEGORY), Scope.LIVE, reflectedKey));

		this.evita.updateCatalog(
			TEST_CATALOG,
			(Consumer<EvitaSessionContract>) session -> {
				session.getEntity(ENTITY_PRODUCT, ownerPk, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.removeReferences(REF_CATEGORIES)
					.removeReferences(REF_STORES)
					.upsertVia(session);
				assertNull(
					partitionOf(collectionOf(ENTITY_PRODUCT), Scope.LIVE, categoryKey),
					"the emptied non-duplicate partition survived the end-of-mutation sweep"
				);
				assertNull(
					partitionOf(collectionOf(ENTITY_PRODUCT), Scope.LIVE, storeKey),
					"the emptied duplicate partition survived the end-of-mutation sweep"
				);
				assertNull(
					partitionOf(collectionOf(ENTITY_CATEGORY), Scope.LIVE, reflectedKey),
					"the emptied reflected partition survived the end-of-mutation sweep"
				);
			}
		);
		assertNull(partitionOf(collectionOf(ENTITY_PRODUCT), Scope.LIVE, categoryKey));
		assertNull(partitionOf(collectionOf(ENTITY_PRODUCT), Scope.LIVE, storeKey));
		assertNull(partitionOf(collectionOf(ENTITY_CATEGORY), Scope.LIVE, reflectedKey));
		// the shared partitions of the baseline are untouched
		assertNotNull(
			partitionOf(
				collectionOf(ENTITY_PRODUCT), Scope.LIVE,
				new RepresentativeReferenceKey(new ReferenceKey(REF_CATEGORIES, CATEGORY_PK))
			)
		);
	}

	/**
	 * A category created in the same session as a product that already references it must receive the reflected
	 * reference: its propagation reads the product collection's partition of that category, which the product's
	 * upsert created moments earlier and which is neither committed (`ALIVE`) nor flushed (`WARMING_UP`) yet. The
	 * read goes through the product collection's data store reader; a read of committed state only would find no
	 * partition and create no reflected reference.
	 *
	 * @param state catalog state the scenario runs in
	 */
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("propagates reflected references from partitions created earlier in the same session")
	void shouldPropagateReflectedReferencesFromPartitionsCreatedEarlierInSameSession(@Nonnull CatalogState state) {
		prepare(state);
		final int ownerPk = 5;
		final int newCategoryPk = 3;
		final RepresentativeReferenceKey categoryKey = new RepresentativeReferenceKey(
			new ReferenceKey(REF_CATEGORIES, newCategoryPk)
		);
		this.evita.updateCatalog(
			TEST_CATALOG,
			(Consumer<EvitaSessionContract>) session -> {
				assertNull(partitionOf(collectionOf(ENTITY_PRODUCT), Scope.LIVE, categoryKey));
				// the product references a category that does not exist yet
				session.createNewEntity(ENTITY_PRODUCT, ownerPk)
					.setReference(REF_CATEGORIES, newCategoryPk)
					.upsertVia(session);
				// the premise: the product's partition of the category exists in-flight only
				assertNotNull(partitionOf(collectionOf(ENTITY_PRODUCT), Scope.LIVE, categoryKey));

				// creating the category propagates the reflected reference from that partition
				session.createNewEntity(ENTITY_CATEGORY, newCategoryPk).upsertVia(session);
				final SealedEntity category = session.getEntity(ENTITY_CATEGORY, newCategoryPk, entityFetchAllContent())
					.orElseThrow();
				assertEquals(
					1, category.getReferences(REF_PRODUCTS).size(),
					"the reflected reference was not propagated from the partition created earlier in the session"
				);
				assertEquals(ownerPk, category.getReferences(REF_PRODUCTS).iterator().next().getReferencedPrimaryKey());
			}
		);
	}

	/**
	 * Creates the schema and the committed baseline: products 1 and 2 reference category 1, product 1 references
	 * store 1 twice (countries DE and US). The references are indexed in both scopes, so an archived owner keeps its
	 * partitions in the archived scope. In `ALIVE` the baseline is committed by going live; in `WARMING_UP` it is
	 * written by an earlier bulk session.
	 *
	 * @param state target catalog state
	 */
	private void prepare(@Nonnull CatalogState state) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			(Consumer<EvitaSessionContract>) session -> {
				session.defineEntitySchema(ENTITY_STORE).withoutGeneratedPrimaryKey().updateVia(session);
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withReferenceToEntity(
						REF_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
					)
					.withReferenceToEntity(
						REF_STORES, ENTITY_STORE, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
						whichIs -> whichIs
							.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED)
							.withAttribute(ATTR_COUNTRY, String.class, thatIs -> thatIs.filterable().representative())
					)
					.updateVia(session);
				session.defineEntitySchema(ENTITY_CATEGORY)
					.withoutGeneratedPrimaryKey()
					.withReflectedReferenceToEntity(
						REF_PRODUCTS, ENTITY_PRODUCT, REF_CATEGORIES,
						whichIs -> whichIs.withCardinality(Cardinality.ZERO_OR_MORE)
					)
					.updateVia(session);

				session.createNewEntity(ENTITY_STORE, STORE_PK).upsertVia(session);
				session.createNewEntity(ENTITY_CATEGORY, CATEGORY_PK).upsertVia(session);
				session.createNewEntity(ENTITY_CATEGORY, NEW_CATEGORY_PK).upsertVia(session);
				session.createNewEntity(ENTITY_PRODUCT, 1)
					.setReference(REF_CATEGORIES, CATEGORY_PK)
					.setOrUpdateReference(
						REF_STORES, STORE_PK, Functions.alwaysFalse(),
						whichIs -> whichIs.setAttribute(ATTR_COUNTRY, "DE")
					)
					.setOrUpdateReference(
						REF_STORES, STORE_PK, Functions.alwaysFalse(),
						whichIs -> whichIs.setAttribute(ATTR_COUNTRY, "US")
					)
					.upsertVia(session);
				session.createNewEntity(ENTITY_PRODUCT, 2)
					.setReference(REF_CATEGORIES, CATEGORY_PK)
					.upsertVia(session);

				if (state == CatalogState.ALIVE) {
					session.goLiveAndClose();
				}
			}
		);
	}

	/**
	 * Resolves the partition of the representative reference key in the collection through its data store reader -
	 * the route production reads a partition of another collection by key, the in-flight indexes of the current
	 * mutation layered over the committed maps.
	 *
	 * @param collection                 the collection owning the partition
	 * @param scope                      the scope of the partition
	 * @param representativeReferenceKey the representative reference key of the partition
	 * @return the partition or `null` when the collection holds none
	 */
	@Nullable
	private static ReferencePartition partitionOf(
		@Nonnull EntityCollection collection,
		@Nonnull Scope scope,
		@Nonnull RepresentativeReferenceKey representativeReferenceKey
	) {
		return ReferencePartitions.resolveIfExists(collection.getDataStoreReader(), scope, representativeReferenceKey);
	}

	/**
	 * Resolves every partition of the target in the collection through its data store reader - the route production
	 * reads the partitions of one target of another collection, the in-flight indexes of the current mutation layered
	 * over the committed maps.
	 *
	 * @param collection       the collection owning the partitions
	 * @param scope            the scope of the partitions
	 * @param referenceSchema  the reference of the collection's entity type
	 * @param targetPrimaryKey the primary key of the referenced entity
	 * @return the partitions of the target, possibly empty
	 */
	@Nonnull
	private static ReferencePartition[] partitionsOfTarget(
		@Nonnull EntityCollection collection,
		@Nonnull Scope scope,
		@Nonnull ReferenceSchemaContract referenceSchema,
		int targetPrimaryKey
	) {
		return ReferencePartitions.resolveOfTarget(
			collection.getDataStoreReader(), scope, referenceSchema.getName(),
			referenceSchema.getCardinality().allowsDuplicates(), targetPrimaryKey
		);
	}

	/**
	 * Builds the planning context of a query over the given collection, as the session would.
	 *
	 * @param collection the queried collection
	 * @param session    the open session
	 * @return a fresh planning context
	 */
	@Nonnull
	private static QueryPlanningContext planningContext(
		@Nonnull EntityCollection collection,
		@Nonnull EvitaSessionContract session
	) {
		return collection.createQueryContext(
			new EvitaRequest(
				query(collection(collection.getEntityType())), OffsetDateTime.now(), EntityReference.class, null
			),
			session
		);
	}

	/**
	 * Builds the planning context of a query over the given collection in the given scopes, as the session would.
	 *
	 * @param collection the queried collection
	 * @param session    the open session
	 * @param scopes     the scopes the query filters by
	 * @return a fresh planning context
	 */
	@Nonnull
	private static QueryPlanningContext planningContext(
		@Nonnull EntityCollection collection,
		@Nonnull EvitaSessionContract session,
		@Nonnull Scope... scopes
	) {
		return collection.createQueryContext(
			new EvitaRequest(
				query(collection(collection.getEntityType()), filterBy(scope(scopes))),
				OffsetDateTime.now(), EntityReference.class, null
			),
			session
		);
	}

	/**
	 * Returns the collection of the given entity type in the catalog the current session works with.
	 *
	 * @param entityType the entity type
	 * @return the collection
	 */
	@Nonnull
	private EntityCollection collectionOf(@Nonnull String entityType) {
		return ((Catalog) this.evita.getCatalogInstanceOrThrowException(TEST_CATALOG))
			.getCollectionForEntityInternal(entityType)
			.orElseThrow(() -> new AssertionError("The test catalog holds no `" + entityType + "` collection"));
	}

}
