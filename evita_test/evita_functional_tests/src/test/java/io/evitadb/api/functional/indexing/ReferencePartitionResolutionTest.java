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
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.Functions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.function.Consumer;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
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
 * Proves that the reference partition resolvers of {@link EntityCollection}, {@link QueryPlanningContext} and the
 * data-store-reader overloads of {@link ReferencePartitions} read what the engine really holds: the partitions of a
 * reference with and without duplicates, the per-collection primary key namespace, and partitions created by a
 * mutation that has not been committed (`ALIVE`) or flushed (`WARMING_UP`) yet.
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

				final ReferencePartition category = products.resolvePartitionIfExists(
					Scope.LIVE, new RepresentativeReferenceKey(new ReferenceKey(REF_CATEGORIES, CATEGORY_PK))
				);
				assertNotNull(category);
				assertArrayEquals(new int[]{1, 2}, category.getAllPrimaryKeys().getArray());
				assertArrayEquals(
					new ReferencePartition[]{category},
					products.resolvePartitionsOfTarget(Scope.LIVE, categories, CATEGORY_PK)
				);
				assertSame(category, products.resolvePartition(category.getPrimaryKey()));
				assertSame(category, products.resolvePartitionIfExists(category.getPrimaryKey()));

				final ReferencePartition[] storePartitions = products.resolvePartitionsOfTarget(
					Scope.LIVE, stores, STORE_PK
				);
				assertEquals(2, storePartitions.length);
				assertArrayEquals(
					new Serializable[][]{{"DE"}, {"US"}},
					Arrays.stream(storePartitions)
						.map(it -> it.getRepresentativeReferenceKey().representativeAttributeValues())
						.sorted((a, b) -> ((String) a[0]).compareTo((String) b[0]))
						.toArray(Serializable[][]::new)
				);
				final BaseBitmap storePartitionPks = new BaseBitmap(
					storePartitions[0].getPrimaryKey(), storePartitions[1].getPrimaryKey()
				);
				final ReferencePartition[] byPrimaryKeys = products.resolvePartitions(storePartitionPks);
				assertEquals(2, byPrimaryKeys.length);
				assertTrue(byPrimaryKeys[0].getPrimaryKey() < byPrimaryKeys[1].getPrimaryKey());
				// the group family and the other index kinds are no partitions
				assertNull(products.resolvePartitionIfExists(products.getGlobalIndex().getPrimaryKey()));
				assertThrows(
					GenericEvitaInternalError.class,
					() -> products.resolvePartition(products.getGlobalIndex().getPrimaryKey())
				);

				// the data store reader layers the same indexes
				assertSame(
					category,
					ReferencePartitions.resolveIfExists(
						products.getDataStoreReader(), Scope.LIVE, category.getRepresentativeReferenceKey()
					)
				);
				assertArrayEquals(
					storePartitions,
					ReferencePartitions.resolveOfTarget(
						products.getDataStoreReader(), Scope.LIVE, REF_STORES, true, STORE_PK
					)
				);

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
				assertSame(category, queryContext.resolvePartition(ENTITY_PRODUCT, category.getPrimaryKey()));
				assertSame(
					category,
					queryContext.resolvePartitionIfExists(
						ENTITY_PRODUCT, Scope.LIVE, category.getRepresentativeReferenceKey()
					)
				);
				assertArrayEquals(byPrimaryKeys, queryContext.resolvePartitions(ENTITY_PRODUCT, storePartitionPks));
			}
		);
	}

	/**
	 * Partition primary keys come from a per-collection sequence, so one number names a partition in the product
	 * collection and a different one in the category collection. Every resolver must read the collection it is told
	 * to, and the planning context of a product query must resolve a category key in the category collection rather
	 * than in its own snapshot.
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
				final ReferencePartition productPartition = products.resolvePartition(sharedPrimaryKey);
				final ReferencePartition categoryPartition = categories.resolvePartition(sharedPrimaryKey);
				assertNotEquals(
					productPartition.getRepresentativeReferenceKey().referenceName(),
					categoryPartition.getRepresentativeReferenceKey().referenceName()
				);
				assertEquals(REF_PRODUCTS, categoryPartition.getRepresentativeReferenceKey().referenceName());

				final QueryPlanningContext queryContext = planningContext(products, session);
				assertSame(productPartition, queryContext.resolvePartition(ENTITY_PRODUCT, sharedPrimaryKey));
				assertSame(categoryPartition, queryContext.resolvePartition(ENTITY_CATEGORY, sharedPrimaryKey));
				assertSame(categoryPartition, queryContext.resolvePartitionIfExists(ENTITY_CATEGORY, sharedPrimaryKey));
				final EntitySchemaContract categorySchema = categories.getSchema();
				assertArrayEquals(
					categories.resolvePartitionsOfTarget(
						Scope.LIVE, categorySchema.getReferenceOrThrowException(REF_PRODUCTS),
						categoryPartition.getReferenceKey().primaryKey()
					),
					queryContext.resolvePartitionsOfTarget(
						categorySchema, categorySchema.getReferenceOrThrowException(REF_PRODUCTS), Scope.LIVE,
						categoryPartition.getReferenceKey().primaryKey()
					)
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
				assertNull(products.resolvePartitionIfExists(Scope.LIVE, newCategoryKey));
				assertNull(products.resolvePartitionIfExists(Scope.LIVE, newStoreKey));
				assertNull(categories.resolvePartitionIfExists(Scope.LIVE, reflectedKey));

				session.createNewEntity(ENTITY_PRODUCT, 3)
					.setReference(REF_CATEGORIES, NEW_CATEGORY_PK)
					.setOrUpdateReference(
						REF_STORES, STORE_PK, Functions.alwaysFalse(),
						whichIs -> whichIs.setAttribute(ATTR_COUNTRY, "CZ")
					)
					.upsertVia(session);

				final EntitySchemaContract productSchema = products.getSchema();
				final ReferencePartition newCategory = products.resolvePartitionIfExists(Scope.LIVE, newCategoryKey);
				assertNotNull(newCategory, "the collection resolver does not see the partition just created");
				assertArrayEquals(new int[]{3}, newCategory.getAllPrimaryKeys().getArray());
				assertSame(newCategory, products.resolvePartition(newCategory.getPrimaryKey()));
				assertArrayEquals(
					new ReferencePartition[]{newCategory},
					products.resolvePartitionsOfTarget(
						Scope.LIVE, productSchema.getReferenceOrThrowException(REF_CATEGORIES), NEW_CATEGORY_PK
					)
				);
				final ReferencePartition[] storePartitions = products.resolvePartitionsOfTarget(
					Scope.LIVE, productSchema.getReferenceOrThrowException(REF_STORES), STORE_PK
				);
				assertEquals(3, storePartitions.length, "the new duplicate partition is missing from its target");

				// the data store reader of the collection - the route of cross-collection write-path reads
				assertSame(
					newCategory,
					ReferencePartitions.resolveIfExists(products.getDataStoreReader(), Scope.LIVE, newCategoryKey)
				);
				assertArrayEquals(
					storePartitions,
					ReferencePartitions.resolveOfTarget(
						products.getDataStoreReader(), Scope.LIVE, REF_STORES, true, STORE_PK
					)
				);

				// the reflected partition created in another collection by the same upsert
				final ReferencePartition reflected = categories.resolvePartitionIfExists(Scope.LIVE, reflectedKey);
				assertNotNull(reflected, "the reflected partition of the category collection is not visible");
				assertArrayEquals(new int[]{NEW_CATEGORY_PK}, reflected.getAllPrimaryKeys().getArray());

				// a planning context opened now reads the in-flight state of its own and of other collections
				final QueryPlanningContext queryContext = planningContext(products, session);
				assertSame(newCategory, queryContext.resolvePartition(ENTITY_PRODUCT, newCategory.getPrimaryKey()));
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

				if (state == CatalogState.ALIVE) {
					session.setRollbackOnly();
				}
			}
		);

		if (state == CatalogState.ALIVE) {
			// the rollback requested above surfaces as an exception once the session closes
			assertThrows(RollbackException.class, scenario::run);
			// the partitions lived in the rolled-back transaction only
			assertNull(collectionOf(ENTITY_PRODUCT).resolvePartitionIfExists(Scope.LIVE, newCategoryKey));
			assertNull(collectionOf(ENTITY_PRODUCT).resolvePartitionIfExists(Scope.LIVE, newStoreKey));
			assertNull(collectionOf(ENTITY_CATEGORY).resolvePartitionIfExists(Scope.LIVE, reflectedKey));
		} else {
			scenario.run();
		}
	}

	/**
	 * Creates the schema and the committed baseline: products 1 and 2 reference category 1, product 1 references
	 * store 1 twice (countries DE and US). In `ALIVE` the baseline is committed by going live; in `WARMING_UP` it is
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
						whichIs -> whichIs.indexedForFiltering()
					)
					.withReferenceToEntity(
						REF_STORES, ENTITY_STORE, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
						whichIs -> whichIs
							.indexedForFiltering()
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
