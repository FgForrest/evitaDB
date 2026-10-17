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
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.core.buffer.DataStoreReader;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReferencePartition;
import io.evitadb.index.ReferencePartitions;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartition;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.Functions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
 * Those resolvers answer index objects only, so the partitions they are asked about have two owners each - a
 * single-owner partition of a reference indexed for filtering is a row of the registry of single-owner partitions.
 * A row is answered by the read resolvers - the read resolution of the planning context and the owner read of
 * {@link ReferencePartitions} - and by no object resolver, committed or in flight (see
 * {@link #shouldAnswerSingleOwnerPartitionThroughTheReadResolversOnly(CatalogState)}); the end-of-mutation sweep
 * and the propagation of reflected references are followed through the read resolvers, for rows and objects alike.
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
	/** A category product 1 alone references - a single-owner partition held as a registry row. */
	private static final int ROW_CATEGORY_PK = 4;

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
				// two more owners of the category, so each scope keeps a partition of two owners - an index object
				for (int productPk = 6; productPk <= 7; productPk++) {
					session.createNewEntity(ENTITY_PRODUCT, productPk)
						.setReference(REF_CATEGORIES, CATEGORY_PK)
						.upsertVia(session);
				}
			}
		);
		this.evita.updateCatalog(
			TEST_CATALOG,
			(Consumer<EvitaSessionContract>) session -> {
				session.archiveEntity(ENTITY_PRODUCT, 2);
				session.archiveEntity(ENTITY_PRODUCT, 7);
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
				assertArrayEquals(new int[]{1, 6}, live.getAllPrimaryKeys().getArray());
				assertArrayEquals(new int[]{2, 7}, archived.getAllPrimaryKeys().getArray());
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
				// every new owner of both categories adds a reflected partition of two owners - an index object - to
				// the category collection, which numbers its indexes up to where the product collection numbers its
				// store partitions
				for (int productPk = 10; productPk < 14; productPk++) {
					session.createNewEntity(ENTITY_PRODUCT, productPk)
						.setReference(REF_CATEGORIES, CATEGORY_PK)
						.setReference(REF_CATEGORIES, NEW_CATEGORY_PK)
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
	 * A partition created by the mutations of the current session is visible to every resolver surface before the
	 * session ends. The partitions are created by two owners each - the second owner promotes the row the first one
	 * entered to an index object - so the object resolvers have an object to find. In `ALIVE` the session is rolled
	 * back and the partition must then be gone - so the positive assertions cannot have passed on committed state.
	 * `WARMING_UP` has no rollback; there the control is that the partition does not resolve before the mutation.
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

				// products 3 and 6 share the new category and the new store partition; product 3 references the
				// baseline category too, so its reflected partition in the category collection has two owners as well
				for (final int productPk : new int[]{3, 6}) {
					final EntityBuilder product = session.createNewEntity(ENTITY_PRODUCT, productPk)
						.setReference(REF_CATEGORIES, NEW_CATEGORY_PK)
						.setOrUpdateReference(
							REF_STORES, STORE_PK, Functions.alwaysFalse(),
							whichIs -> whichIs.setAttribute(ATTR_COUNTRY, "CZ")
						);
					if (productPk == 3) {
						product.setReference(REF_CATEGORIES, CATEGORY_PK);
					}
					product.upsertVia(session);
				}

				final EntitySchemaContract productSchema = products.getSchema();
				// by key, through the data store reader of the collection - the route of cross-collection reads
				final ReferencePartition newCategory = partitionOf(products, Scope.LIVE, newCategoryKey);
				assertNotNull(
					newCategory, "the collection's data store reader does not see the partition just created"
				);
				assertArrayEquals(new int[]{3, 6}, newCategory.getAllPrimaryKeys().getArray());
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
				assertArrayEquals(new int[]{CATEGORY_PK, NEW_CATEGORY_PK}, reflected.getAllPrimaryKeys().getArray());
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
					queryContext.resolvePartitionForReadIfExists(ENTITY_CATEGORY, Scope.LIVE, reflectedKey)
				);
				// by key in the queried collection - read from the query's index snapshot, which must carry the
				// partitions the session created before the query was planned
				assertSame(newCategory, queryContext.resolvePartitionIfExists(Scope.LIVE, newCategoryKey));
				assertArrayEquals(
					new ReferencePartition[]{newCategory},
					queryContext.resolvePartitionsOfTarget(
						productSchema, productSchema.getReferenceOrThrowException(REF_CATEGORIES), Scope.LIVE,
						NEW_CATEGORY_PK
					)
				);
				// and a real query answered through those partitions sees the new owner
				assertEquals(
					List.of(3, 6),
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
	 * A single-owner partition is a row of the registry of single-owner partitions, and no resolver answering index
	 * objects answers it, whether it is committed or created by the current mutation: neither the resolvers of
	 * {@link ReferencePartitions} over the data store reader, by key and by target, nor the collection by primary key,
	 * nor the object resolution of the planning context. The read resolvers answer its virtual handle with its owner
	 * and its key under one primary key - the owner read over the data store reader and the read resolution of the
	 * planning context by key, by primary key and by target, where it is listed next to the index objects of the
	 * target. In `ALIVE` the session is rolled back and the row it created must then be gone, the committed one not.
	 *
	 * @param state catalog state the scenario runs in
	 */
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("answers a single-owner partition through the read resolvers only, committed and in flight")
	void shouldAnswerSingleOwnerPartitionThroughTheReadResolversOnly(@Nonnull CatalogState state) {
		prepare(state);
		// committed: product 1 alone references the row category
		this.evita.updateCatalog(
			TEST_CATALOG,
			(Consumer<EvitaSessionContract>) session -> {
				session.createNewEntity(ENTITY_CATEGORY, ROW_CATEGORY_PK).upsertVia(session);
				session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setReference(REF_CATEGORIES, ROW_CATEGORY_PK)
					.upsertVia(session);
			}
		);
		final RepresentativeReferenceKey committedKey = new RepresentativeReferenceKey(
			new ReferenceKey(REF_CATEGORIES, ROW_CATEGORY_PK)
		);
		final RepresentativeReferenceKey inFlightKey = new RepresentativeReferenceKey(
			new ReferenceKey(REF_STORES, STORE_PK), new Serializable[]{"PL"}
		);
		final Runnable scenario = () -> this.evita.updateCatalog(
			TEST_CATALOG,
			(Consumer<EvitaSessionContract>) session -> {
				assertNull(readPartitionOf(ENTITY_PRODUCT, inFlightKey));
				// in flight: product 2 alone enters the store partition of PL
				session.getEntity(ENTITY_PRODUCT, 2, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setOrUpdateReference(
						REF_STORES, STORE_PK, Functions.alwaysFalse(),
						whichIs -> whichIs.setAttribute(ATTR_COUNTRY, "PL")
					)
					.upsertVia(session);

				final EntityCollection products = collectionOf(ENTITY_PRODUCT);
				final EntitySchemaContract productSchema = products.getSchema();
				final ReferenceSchemaContract categories = productSchema.getReferenceOrThrowException(REF_CATEGORIES);
				final ReferenceSchemaContract stores = productSchema.getReferenceOrThrowException(REF_STORES);
				final QueryPlanningContext queryContext = planningContext(products, session);
				final ReferencePartition committed = assertAnsweredAsRowOnly(products, queryContext, committedKey, 1);
				final ReferencePartition inFlight = assertAnsweredAsRowOnly(products, queryContext, inFlightKey, 2);

				// by target, the object resolvers list the index objects of the target only
				assertArrayEquals(
					new ReferencePartition[0],
					partitionsOfTarget(products, Scope.LIVE, categories, ROW_CATEGORY_PK)
				);
				assertArrayEquals(
					new ReferencePartition[0],
					queryContext.resolvePartitionsOfTarget(productSchema, categories, Scope.LIVE, ROW_CATEGORY_PK)
				);
				final ReferencePartition[] storeObjects = partitionsOfTarget(products, Scope.LIVE, stores, STORE_PK);
				assertEquals(2, storeObjects.length, "the store partitions of DE and US are the index objects");
				// while the read resolution lists the rows too, next to the very index objects
				final ReferencePartition[] categoryForRead = queryContext.resolvePartitionsOfTargetForRead(
					productSchema, categories, Scope.LIVE, ROW_CATEGORY_PK
				);
				assertEquals(1, categoryForRead.length);
				assertEquals(committed.getPrimaryKey(), categoryForRead[0].getPrimaryKey());
				assertArrayEquals(new int[]{1}, categoryForRead[0].getAllPrimaryKeys().getArray());
				final ReferencePartition[] storesForRead = queryContext.resolvePartitionsOfTargetForRead(
					productSchema, stores, Scope.LIVE, STORE_PK
				);
				assertEquals(3, storesForRead.length, "the row of PL must be listed next to the index objects");
				int objectsListed = 0;
				for (final ReferencePartition storeForRead : storesForRead) {
					if (storeForRead instanceof VirtualReferencePartition) {
						assertEquals(inFlight.getPrimaryKey(), storeForRead.getPrimaryKey());
						assertArrayEquals(new int[]{2}, storeForRead.getAllPrimaryKeys().getArray());
					} else {
						assertTrue(
							Arrays.stream(storeObjects).anyMatch(it -> it == storeForRead),
							"the read resolution must list the very index objects of the target"
						);
						objectsListed++;
					}
				}
				assertEquals(2, objectsListed);

				if (state == CatalogState.ALIVE) {
					session.setRollbackOnly();
				}
			}
		);

		if (state == CatalogState.ALIVE) {
			// the rollback requested above surfaces as an exception once the session closes
			assertThrows(RollbackException.class, scenario::run);
			// the row lived in the rolled-back transaction only
			assertRetired(ENTITY_PRODUCT, inFlightKey, "the row of the rolled-back transaction survived it");
		} else {
			scenario.run();
			assertNotNull(readPartitionOf(ENTITY_PRODUCT, inFlightKey));
		}
		final ReferencePartition committedRow = readPartitionOf(ENTITY_PRODUCT, committedKey);
		assertInstanceOf(VirtualReferencePartition.class, committedRow, "the committed row must stay a row");
		assertArrayEquals(new int[]{1}, committedRow.getAllPrimaryKeys().getArray());
	}

	/**
	 * Asserts that a single-owner partition is answered as a registry row by the read resolvers and by no resolver
	 * of index objects - see {@link #shouldAnswerSingleOwnerPartitionThroughTheReadResolversOnly(CatalogState)}.
	 *
	 * @param products     the product collection
	 * @param queryContext the planning context of a query over the products
	 * @param key          the representative reference key of the partition
	 * @param owner        the only owner of the partition
	 * @return the partition as the owner read answers it
	 */
	@Nonnull
	private ReferencePartition assertAnsweredAsRowOnly(
		@Nonnull EntityCollection products,
		@Nonnull QueryPlanningContext queryContext,
		@Nonnull RepresentativeReferenceKey key,
		int owner
	) {
		// no resolver of index objects answers its key
		assertNull(partitionOf(products, Scope.LIVE, key), key + ": the object resolver over the data store reader");
		assertNull(queryContext.resolvePartitionIfExists(Scope.LIVE, key), key + ": the planning context by key");
		// the owner read answers its virtual handle
		final ReferencePartition row = readPartitionOf(ENTITY_PRODUCT, key);
		assertInstanceOf(VirtualReferencePartition.class, row, key + ": the owner read");
		assertEquals(key, row.getRepresentativeReferenceKey());
		assertArrayEquals(new int[]{owner}, row.getAllPrimaryKeys().getArray());
		// no resolver of index objects answers its primary key
		assertNull(products.resolvePartitionIfExists(row.getPrimaryKey()), key + ": the collection by primary key");
		assertNull(
			queryContext.resolvePartitionIfExists(row.getPrimaryKey()), key + ": the planning context by primary key"
		);
		// the read resolution of the planning context answers the same row by key and by primary key
		final ReferencedTypeEntityIndex typeIndex = queryContext.getIndexIfExists(
				new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, key.referenceName()),
				ReferencedTypeEntityIndex.class
			)
			.orElseThrow();
		for (final ReferencePartition forRead : new ReferencePartition[]{
			queryContext.resolvePartitionForReadIfExists(ENTITY_PRODUCT, Scope.LIVE, key),
			queryContext.resolvePartitionForReadIfExists(typeIndex, row.getPrimaryKey())
		}) {
			assertInstanceOf(VirtualReferencePartition.class, forRead, key + ": the read resolution");
			assertEquals(row.getPrimaryKey(), forRead.getPrimaryKey());
			assertEquals(key, forRead.getRepresentativeReferenceKey());
			assertArrayEquals(new int[]{owner}, forRead.getAllPrimaryKeys().getArray());
		}
		return row;
	}

	/**
	 * The end-of-mutation sweep retires a partition whose last owner has left - for partitions acquired through the
	 * partition write acquisition: a non-duplicate partition, a duplicate (representative) partition, and the
	 * reflected partition the implicit mutation of the category leaves empty in the other collection. A partition of a
	 * single owner is a registry row, and it is followed through the read resolvers, which answer rows and objects
	 * alike: the rows are gone as soon as the removing upsert finished, and stay gone after the session ends.
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
		// the premise: the only owner created all three partitions, each a registry row of its own
		assertInstanceOf(VirtualReferencePartition.class, readPartitionOf(ENTITY_PRODUCT, categoryKey));
		assertInstanceOf(VirtualReferencePartition.class, readPartitionOf(ENTITY_PRODUCT, storeKey));
		assertInstanceOf(VirtualReferencePartition.class, readPartitionOf(ENTITY_CATEGORY, reflectedKey));

		this.evita.updateCatalog(
			TEST_CATALOG,
			(Consumer<EvitaSessionContract>) session -> {
				session.getEntity(ENTITY_PRODUCT, ownerPk, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.removeReferences(REF_CATEGORIES)
					.removeReferences(REF_STORES)
					.upsertVia(session);
				assertRetired(
					ENTITY_PRODUCT, categoryKey,
					"the emptied non-duplicate partition survived the end-of-mutation sweep"
				);
				assertRetired(
					ENTITY_PRODUCT, storeKey, "the emptied duplicate partition survived the end-of-mutation sweep"
				);
				assertRetired(
					ENTITY_CATEGORY, reflectedKey, "the emptied reflected partition survived the end-of-mutation sweep"
				);
			}
		);
		assertRetired(ENTITY_PRODUCT, categoryKey, "the emptied non-duplicate partition came back");
		assertRetired(ENTITY_PRODUCT, storeKey, "the emptied duplicate partition came back");
		assertRetired(ENTITY_CATEGORY, reflectedKey, "the emptied reflected partition came back");
		// the shared partitions of the baseline are untouched
		assertNotNull(
			partitionOf(
				collectionOf(ENTITY_PRODUCT), Scope.LIVE,
				new RepresentativeReferenceKey(new ReferenceKey(REF_CATEGORIES, CATEGORY_PK))
			)
		);
	}

	/**
	 * A category created in the same session as the products that already reference it must receive the reflected
	 * references: their propagation reads the product collection's partition of that category, which the products'
	 * upserts created moments earlier and which is neither committed (`ALIVE`) nor flushed (`WARMING_UP`) yet - a
	 * registry row of one product, or an index object of two. The read goes through the product collection's data
	 * store reader; a read of committed state only would find no partition and create no reflected reference.
	 *
	 * @param state  catalog state the scenario runs in
	 * @param owners how many products reference the category before it is created
	 */
	@ParameterizedTest(name = "{0}, owners: {1}")
	@MethodSource("statesAndOwnerCounts")
	@DisplayName("propagates reflected references from partitions created earlier in the same session")
	void shouldPropagateReflectedReferencesFromPartitionsCreatedEarlierInSameSession(
		@Nonnull CatalogState state,
		int owners
	) {
		prepare(state);
		final int firstOwnerPk = 5;
		final int newCategoryPk = 3;
		final RepresentativeReferenceKey categoryKey = new RepresentativeReferenceKey(
			new ReferenceKey(REF_CATEGORIES, newCategoryPk)
		);
		this.evita.updateCatalog(
			TEST_CATALOG,
			(Consumer<EvitaSessionContract>) session -> {
				assertNull(readPartitionOf(ENTITY_PRODUCT, categoryKey));
				// the products reference a category that does not exist yet
				for (int ownerPk = firstOwnerPk; ownerPk < firstOwnerPk + owners; ownerPk++) {
					session.createNewEntity(ENTITY_PRODUCT, ownerPk)
						.setReference(REF_CATEGORIES, newCategoryPk)
						.upsertVia(session);
				}
				// the premise: the products' partition of the category exists in-flight only - a row of one owner, an
				// index object of two
				final ReferencePartition partition = readPartitionOf(ENTITY_PRODUCT, categoryKey);
				assertNotNull(partition);
				assertEquals(owners, partition.size());
				assertEquals(owners > 1, partition instanceof ReducedEntityIndex);

				// creating the category propagates the reflected references from that partition
				session.createNewEntity(ENTITY_CATEGORY, newCategoryPk).upsertVia(session);
				final SealedEntity category = session.getEntity(ENTITY_CATEGORY, newCategoryPk, entityFetchAllContent())
					.orElseThrow();
				assertEquals(
					IntStream.range(firstOwnerPk, firstOwnerPk + owners).boxed().toList(),
					category.getReferences(REF_PRODUCTS).stream()
						.map(ReferenceContract::getReferencedPrimaryKey)
						.sorted()
						.toList(),
					"the reflected references were not propagated from the partition created earlier in the session"
				);
			}
		);
	}

	/**
	 * Returns every catalog state combined with a partition of one owner - a registry row - and of two - an index
	 * object.
	 *
	 * @return the arguments
	 */
	@Nonnull
	private static Stream<Arguments> statesAndOwnerCounts() {
		return Stream.of(
			Arguments.of(CatalogState.WARMING_UP, 1),
			Arguments.of(CatalogState.WARMING_UP, 2),
			Arguments.of(CatalogState.ALIVE, 1),
			Arguments.of(CatalogState.ALIVE, 2)
		);
	}

	/**
	 * Creates the schema and the committed baseline: products 1 and 2 reference category 1 and store 1 twice
	 * (countries DE and US), so every partition of the baseline has two owners and is an index object. The references
	 * are indexed in both scopes, so an archived owner keeps its partitions in the archived scope. In `ALIVE` the
	 * baseline is committed by going live; in `WARMING_UP` it is written by an earlier bulk session.
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
					.setOrUpdateReference(
						REF_STORES, STORE_PK, Functions.alwaysFalse(),
						whichIs -> whichIs.setAttribute(ATTR_COUNTRY, "DE")
					)
					.setOrUpdateReference(
						REF_STORES, STORE_PK, Functions.alwaysFalse(),
						whichIs -> whichIs.setAttribute(ATTR_COUNTRY, "US")
					)
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
		final DataStoreReader dataStoreReader = collection.getDataStoreReader();
		return ReferencePartitions.resolveIfExists(
			key -> dataStoreReader.getIndexIfExists(key, __ -> null), scope, representativeReferenceKey
		);
	}

	/**
	 * Asserts that the live partition of the representative reference key is gone from the collection of the entity
	 * type: no read resolves it, and the registry of single-owner partitions holds no row of it - the latter read out
	 * of the rows themselves, so a row the type index no longer advertises is found as well.
	 *
	 * @param entityType                 the collection that owned the partition
	 * @param representativeReferenceKey the representative reference key of the partition
	 * @param message                    the failure message
	 */
	private void assertRetired(
		@Nonnull String entityType,
		@Nonnull RepresentativeReferenceKey representativeReferenceKey,
		@Nonnull String message
	) {
		assertNull(readPartitionOf(entityType, representativeReferenceKey), message + ": it is still resolved");
		assertFalse(
			IndexingTestSupport.hasRegistryRow(collectionOf(entityType), Scope.LIVE, representativeReferenceKey),
			message + ": its row is still in the registry"
		);
	}

	/**
	 * Resolves the live partition of the representative reference key in the collection of the entity type as an owner
	 * read does - its index object, or the virtual handle of its registry row - through the data store reader of the
	 * collection, so the in-flight state of the current mutation is read too.
	 *
	 * @param entityType                 the collection owning the partition
	 * @param representativeReferenceKey the representative reference key of the partition
	 * @return the partition or `null` when the collection holds none
	 */
	@Nullable
	private ReferencePartition readPartitionOf(
		@Nonnull String entityType,
		@Nonnull RepresentativeReferenceKey representativeReferenceKey
	) {
		return IndexingTestSupport.getReferencedEntityPartition(
			collectionOf(entityType), Scope.LIVE, representativeReferenceKey
		);
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
