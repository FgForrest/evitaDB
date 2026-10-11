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

package io.evitadb.core.collection;

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.exception.RollbackException;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer.Savepoint;
import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.IndexMaintainer;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReferencePartitions;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartition;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.IntFunction;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.index.virtual.VirtualPartitionInvariants.assertInvariants;
import static io.evitadb.index.virtual.VirtualPartitionInvariants.assertStoredParts;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies how the maintainer of a collection's entity indexes installs an index under a primary key its index
 * sequence handed out earlier, and how such an index is retired and installed again: the installed index is reachable
 * by its key and by its primary key, the sequence never moves, a rollback - of a warm-up savepoint, of a transaction
 * or of a transactional savepoint - takes the installation back out of both maps, and a retirement followed by a
 * re-installation under the same primary key within one flush window leaves exactly the last installed index on disk.
 *
 * Every scenario runs against a real engine. The transactional replacement is driven by real mutations: in one live
 * transaction a shared partition stored as an index object loses an owner, gains another one, loses it and gains a
 * third one, so the replay of the committed transaction - the only transaction whose changes are merged and flushed -
 * retires the stored object and installs another one under its primary key twice over: the first owner keeps the
 * partition as a row of the registry of its referenced-type entity index between the two.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Installing and retiring entity indexes under an existing primary key")
@Tag(ENGINE)
@Tag(STORAGE)
@Tag(REFERENCE)
@Tag(TRANSACTION)
class IndexInstallRetireTest implements EvitaTestSupport {
	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_CATEGORY = "category";
	private static final String REF_CATEGORIES = "categories";
	private static final String ATTRIBUTE_CODE = "code";
	/** Filterable attribute of the reference whose single-owner partitions are registry rows. */
	private static final String ATTRIBUTE_ORDER = "order";
	/** Category whose partition the scenarios retire and install again. */
	private static final int FIRST_CATEGORY_PK = 1;
	/** Category whose partition no scenario touches. */
	private static final int SECOND_CATEGORY_PK = 2;
	/** Category no product references - its partition exists only when a scenario installs it. */
	private static final int UNREFERENCED_CATEGORY_PK = 3;
	/** Product referencing {@link #FIRST_CATEGORY_PK}. */
	private static final int FIRST_PRODUCT_PK = 100;
	/** Product referencing {@link #SECOND_CATEGORY_PK}. */
	private static final int SECOND_PRODUCT_PK = 101;
	/** Product without references, upserted to give a transaction something to replay or to join a partition. */
	private static final int UNREFERENCING_PRODUCT_PK = 102;
	/** Product without references that joins a partition last. */
	private static final int LAST_JOINING_PRODUCT_PK = 103;
	private static final EntityIndexKey FIRST_PARTITION = partitionKey(FIRST_CATEGORY_PK);
	private static final EntityIndexKey UNREFERENCED_PARTITION = partitionKey(UNREFERENCED_CATEGORY_PK);
	private static final AttributeIndexKey CODE = new AttributeIndexKey(null, ATTRIBUTE_CODE, null);

	private TestPaths paths;
	private Evita evita;

	/**
	 * Creates the key of the partition of the `categories` reference for the given category in the live scope.
	 *
	 * @param categoryPk primary key of the referenced category
	 * @return the partition key
	 */
	@Nonnull
	private static EntityIndexKey partitionKey(int categoryPk) {
		return new EntityIndexKey(
			EntityIndexType.REFERENCED_ENTITY,
			Scope.LIVE,
			new RepresentativeReferenceKey(new ReferenceKey(REF_CATEGORIES, categoryPk))
		);
	}

	/**
	 * Returns the factory creating an empty partition of the given key, as the maintainer passes it the primary key.
	 *
	 * @param key the key of the partition
	 * @return the factory
	 */
	@Nonnull
	private static IntFunction<EntityIndex> emptyPartition(@Nonnull EntityIndexKey key) {
		return primaryKey -> new ReducedEntityIndex(primaryKey, ENTITY_PRODUCT, key);
	}

	/**
	 * Retires the first category's partition and installs a new one under its primary key twice over within the
	 * running flush window - the first replacement holding both products, the second, final one only the first
	 * product - and installs a partition of the unreferenced category under a primary key allocated for it.
	 *
	 * @param products the product collection
	 * @return the primary key allocated for the unreferenced category's partition
	 */
	private static int retireAndReinstallTwice(@Nonnull EntityCollection products) {
		final IndexMaintainer<EntityIndexKey, EntityIndex> maintainer = products.getEntityIndexMaintainer();
		final EntityIndex retired = products.getIndexByKeyIfExists(FIRST_PARTITION);
		assertNotNull(retired, "The first category's partition must exist before it is retired.");
		final int primaryKey = retired.getPrimaryKey();

		maintainer.removeIndex(FIRST_PARTITION);
		final EntityIndex interim = maintainer.installIndex(
			FIRST_PARTITION, primaryKey, emptyPartition(FIRST_PARTITION)
		);
		interim.insertPrimaryKeyIfMissing(FIRST_PRODUCT_PK);
		interim.insertPrimaryKeyIfMissing(SECOND_PRODUCT_PK);
		maintainer.removeIndex(FIRST_PARTITION);
		final EntityIndex last = maintainer.installIndex(
			FIRST_PARTITION, primaryKey, emptyPartition(FIRST_PARTITION)
		);
		last.insertPrimaryKeyIfMissing(FIRST_PRODUCT_PK);

		final int allocatedPrimaryKey = maintainer.allocatePartitionPrimaryKey();
		final EntityIndex unreferenced = maintainer.installIndex(
			UNREFERENCED_PARTITION, allocatedPrimaryKey, emptyPartition(UNREFERENCED_PARTITION)
		);
		unreferenced.insertPrimaryKeyIfMissing(FIRST_PRODUCT_PK);

		assertSame(last, products.getIndexByKeyIfExists(FIRST_PARTITION), "self-check: by key");
		assertSame(last, products.getIndexByPrimaryKeyIfExists(primaryKey), "self-check: by primary key");
		return allocatedPrimaryKey;
	}

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("IndexInstallRetireTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		// a savepoint a failing test left bound to this thread would fail every later test of this fork
		final WarmUpSavepoint leaked = WarmUpSavepoint.getIfOpen();
		if (leaked != null) {
			leaked.commit();
		}
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	/**
	 * Creates the schema and the baseline: two categories and two products, each referencing one of them through a
	 * reference indexed for partitioning and carrying a filterable code, so every partition persists a filter
	 * sub-index of its own. The session closing the warm-up flushes it, so the partitions are on disk before any
	 * scenario starts.
	 *
	 * @param state target catalog state
	 */
	private void prepare(@Nonnull CatalogState state) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			(Consumer<EvitaSessionContract>) session -> {
				session.defineEntitySchema(ENTITY_CATEGORY)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::filterable)
					.withReferenceToEntity(
						REF_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioning()
					)
					.updateVia(session);
				session.createNewEntity(ENTITY_CATEGORY, FIRST_CATEGORY_PK).upsertVia(session);
				session.createNewEntity(ENTITY_CATEGORY, SECOND_CATEGORY_PK).upsertVia(session);
				session.createNewEntity(ENTITY_PRODUCT, FIRST_PRODUCT_PK)
					.setAttribute(ATTRIBUTE_CODE, "code-" + FIRST_PRODUCT_PK)
					.setReference(REF_CATEGORIES, FIRST_CATEGORY_PK)
					.upsertVia(session);
				session.createNewEntity(ENTITY_PRODUCT, SECOND_PRODUCT_PK)
					.setAttribute(ATTRIBUTE_CODE, "code-" + SECOND_PRODUCT_PK)
					.setReference(REF_CATEGORIES, SECOND_CATEGORY_PK)
					.upsertVia(session);
				if (state == CatalogState.ALIVE) {
					session.goLiveAndClose();
				}
			}
		);
		assertNotNull(
			products().getIndexByKeyIfExists(FIRST_PARTITION)
				.getFilterIndex(CODE),
			"self-check: the first category's partition must hold a filter sub-index of the code"
		);
	}

	/**
	 * Creates the schema and the baseline of the scenario driving index objects through real mutations: a reference
	 * indexed for filtering, whose single-owner partitions are rows of the registry of its referenced-type entity
	 * index, with a filterable reference attribute, so an index object of a partition persists a filter sub-index of
	 * its own. The first two products share the first category, whose partition is therefore an index object, and the
	 * second product references the second category alone, whose partition is a row. The two last products reference
	 * no category. The catalog is taken live, which stores the index object.
	 */
	private void prepareSharedPartition() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			(Consumer<EvitaSessionContract>) session -> {
				session.defineEntitySchema(ENTITY_CATEGORY)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withReferenceToEntity(
						REF_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFiltering()
							.withAttribute(ATTRIBUTE_ORDER, Integer.class, thatIs -> thatIs.filterable())
					)
					.updateVia(session);
				session.createNewEntity(ENTITY_CATEGORY, FIRST_CATEGORY_PK).upsertVia(session);
				session.createNewEntity(ENTITY_CATEGORY, SECOND_CATEGORY_PK).upsertVia(session);
				session.createNewEntity(ENTITY_PRODUCT, FIRST_PRODUCT_PK)
					.setReference(
						REF_CATEGORIES, FIRST_CATEGORY_PK, ref -> ref.setAttribute(ATTRIBUTE_ORDER, FIRST_PRODUCT_PK)
					)
					.upsertVia(session);
				session.createNewEntity(ENTITY_PRODUCT, SECOND_PRODUCT_PK)
					.setReference(
						REF_CATEGORIES, FIRST_CATEGORY_PK, ref -> ref.setAttribute(ATTRIBUTE_ORDER, SECOND_PRODUCT_PK)
					)
					.setReference(
						REF_CATEGORIES, SECOND_CATEGORY_PK, ref -> ref.setAttribute(ATTRIBUTE_ORDER, SECOND_PRODUCT_PK)
					)
					.upsertVia(session);
				session.createNewEntity(ENTITY_PRODUCT, UNREFERENCING_PRODUCT_PK).upsertVia(session);
				session.createNewEntity(ENTITY_PRODUCT, LAST_JOINING_PRODUCT_PK).upsertVia(session);
				session.goLiveAndClose();
			}
		);
	}

	/**
	 * Returns the primary key of the registry row of a category's partition of the products.
	 *
	 * @param categoryPk the primary key of the category
	 * @return the primary key of the partition, `-1` when the registry holds no row of it
	 */
	private int registeredPartitionOf(int categoryPk) {
		final EntityIndex typeIndex = products().getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, REF_CATEGORIES)
		);
		final VirtualReferencePartition partition =
			typeIndex instanceof final ReferencedTypeEntityIndex referencedTypeIndex ?
				ReferencePartitions.findRegisteredForWrite(
					referencedTypeIndex, new RepresentativeReferenceKey(new ReferenceKey(REF_CATEGORIES, categoryPk))
				) :
				null;
		return partition == null ? -1 : partition.getPrimaryKey();
	}

	/**
	 * Runs the logic in a read-write session and waits until its changes are visible.
	 *
	 * @param logic the logic to run
	 */
	private void update(@Nonnull Consumer<EvitaSessionContract> logic) {
		this.evita.updateCatalog(TEST_CATALOG, logic, CommitBehavior.WAIT_FOR_CHANGES_VISIBLE);
	}

	/**
	 * Returns the current instance of the test catalog.
	 *
	 * @return the catalog
	 */
	@Nonnull
	private Catalog catalog() {
		return (Catalog) this.evita.getCatalogInstanceOrThrowException(TEST_CATALOG);
	}

	/**
	 * Returns the current instance of the product collection.
	 *
	 * @return the product collection
	 */
	@Nonnull
	private EntityCollection products() {
		return catalog().getCollectionForEntityInternal(ENTITY_PRODUCT)
			.orElseThrow(() -> new AssertionError("The test catalog holds no product collection"));
	}

	/**
	 * Closes the engine and opens it again over the same data, so every index is read back from disk.
	 */
	private void reload() {
		this.evita.close();
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.waitUntilFullyInitialized();
	}

	/**
	 * Counts the filter sub-index roots of the product collection present in its persisted record set.
	 *
	 * @return the number of persisted filter sub-index roots
	 */
	private int countPersistedFilterRoots() {
		return products().getDataStoreReader().countStorageParts(catalog().getVersion(), FilterIndexStoragePart.class);
	}

	/**
	 * Asserts that the first category's partition and the unreferenced category's partition are the ones the
	 * replacement installed last: the former under its original primary key with the first product only and none of
	 * the filter sub-index the retired partition held, the latter under the allocated primary key.
	 *
	 * @param primaryKey          the primary key of the first category's partition
	 * @param allocatedPrimaryKey the primary key allocated for the unreferenced category's partition
	 */
	private void assertReplacementInstalledLast(int primaryKey, int allocatedPrimaryKey) {
		final EntityCollection products = products();
		final EntityIndex partition = products.getIndexByKeyIfExists(FIRST_PARTITION);
		assertInstanceOf(ReducedEntityIndex.class, partition, "The first category's partition must exist.");
		assertEquals(primaryKey, partition.getPrimaryKey(), "The partition must keep its primary key.");
		assertSame(partition, products.getIndexByPrimaryKeyIfExists(primaryKey), "by key and by primary key differ");
		assertArrayEquals(
			new int[]{FIRST_PRODUCT_PK}, partition.getAllPrimaryKeys().getArray(),
			"The partition must be the one installed last, not the interim one holding both products."
		);
		assertNull(
			partition.getFilterIndex(CODE),
			"The partition must not be the retired one, which held a filter sub-index of the code."
		);

		final EntityIndex unreferenced = products.getIndexByKeyIfExists(UNREFERENCED_PARTITION);
		assertNotNull(unreferenced, "The partition installed under an allocated primary key must exist.");
		assertEquals(allocatedPrimaryKey, unreferenced.getPrimaryKey());
		assertSame(unreferenced, products.getIndexByPrimaryKeyIfExists(allocatedPrimaryKey));
		assertArrayEquals(new int[]{FIRST_PRODUCT_PK}, unreferenced.getAllPrimaryKeys().getArray());
	}

	@Nested
	@DisplayName("Installation")
	class Installation {

		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("an installed index is read by its key and by its primary key, and the sequence does not move")
		void shouldReadInstalledIndexByKeyAndByPrimaryKey(@Nonnull CatalogState state) {
			prepare(state);

			update(session -> {
				assertEquals(state == CatalogState.ALIVE, Transaction.isTransactionAvailable(), "self-check");
				final EntityCollection products = products();
				final IndexMaintainer<EntityIndexKey, EntityIndex> maintainer = products.getEntityIndexMaintainer();
				final int allocatedPrimaryKey = maintainer.allocatePartitionPrimaryKey();
				assertNull(products.getIndexByPrimaryKeyIfExists(allocatedPrimaryKey), "self-check: no index yet");

				final EntityIndex installed = maintainer.installIndex(
					UNREFERENCED_PARTITION, allocatedPrimaryKey, emptyPartition(UNREFERENCED_PARTITION)
				);

				assertEquals(allocatedPrimaryKey, installed.getPrimaryKey());
				assertSame(installed, maintainer.getIndexIfExists(UNREFERENCED_PARTITION));
				assertSame(installed, maintainer.getIndexByPrimaryKeyIfExists(allocatedPrimaryKey));
				assertSame(installed, products.getIndexByKeyIfExists(UNREFERENCED_PARTITION));
				assertSame(installed, products.getIndexByPrimaryKeyIfExists(allocatedPrimaryKey));
				assertEquals(
					allocatedPrimaryKey + 1, maintainer.allocatePartitionPrimaryKey(),
					"Installing must not advance the index sequence."
				);
			});
		}

		@Test
		@DisplayName("an index installed in warm-up stays registered under both keys and counted once flushed")
		void shouldKeepInstalledIndexRegisteredOnceWarmUpFlushed() {
			prepare(CatalogState.WARMING_UP);
			final int indexCountBefore = products().getIndexCount();
			final int[] allocatedPrimaryKey = new int[1];

			update(session -> {
				final IndexMaintainer<EntityIndexKey, EntityIndex> maintainer = products().getEntityIndexMaintainer();
				allocatedPrimaryKey[0] = maintainer.allocatePartitionPrimaryKey();
				maintainer.installIndex(
					UNREFERENCED_PARTITION, allocatedPrimaryKey[0], emptyPartition(UNREFERENCED_PARTITION)
				);
			});

			// the flush emptied the dirty buffer, so both reads below are answered by the collection's own maps
			final EntityCollection products = products();
			final EntityIndex byKey = products.getIndexByKeyIfExists(UNREFERENCED_PARTITION);
			assertNotNull(byKey, "The installed index must be registered under its key.");
			assertSame(
				byKey, products.getIndexByPrimaryKeyIfExists(allocatedPrimaryKey[0]),
				"The installed index must be registered under its primary key."
			);
			assertEquals(indexCountBefore + 1, products.getIndexCount(), "The installed index must be counted.");
		}
	}

	@Nested
	@DisplayName("Refused installation")
	class RefusedInstallation {

		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("an index is not installed under a key another index holds")
		void shouldRefuseKeyHeldByAnotherIndex(@Nonnull CatalogState state) {
			prepare(state);
			update(session -> {
				final int allocatedPrimaryKey = products().getEntityIndexMaintainer().allocatePartitionPrimaryKey();
				assertRefused(FIRST_PARTITION, allocatedPrimaryKey, emptyPartition(FIRST_PARTITION), "its key");
			});
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("an index is not installed under a primary key another index holds")
		void shouldRefusePrimaryKeyHeldByAnotherIndex(@Nonnull CatalogState state) {
			prepare(state);
			update(session -> {
				final int heldPrimaryKey = products().getIndexByKeyIfExists(FIRST_PARTITION).getPrimaryKey();
				assertRefused(
					UNREFERENCED_PARTITION, heldPrimaryKey, emptyPartition(UNREFERENCED_PARTITION), "that primary key"
				);
			});
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("an index is not installed under a primary key the sequence has not handed out")
		void shouldRefusePrimaryKeyAboveSequence(@Nonnull CatalogState state) {
			prepare(state);
			update(session -> {
				final IndexMaintainer<EntityIndexKey, EntityIndex> maintainer = products().getEntityIndexMaintainer();
				final int lastAllocated = maintainer.allocatePartitionPrimaryKey();
				assertRefused(
					UNREFERENCED_PARTITION, lastAllocated + 1, emptyPartition(UNREFERENCED_PARTITION),
					"up to " + lastAllocated + " only"
				);
				assertRefused(
					UNREFERENCED_PARTITION, 0, emptyPartition(UNREFERENCED_PARTITION),
					"up to " + lastAllocated + " only"
				);
				assertEquals(
					lastAllocated + 1, maintainer.allocatePartitionPrimaryKey(),
					"A refused installation must not consume the primary key it named."
				);
			});
		}

		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("an index the factory creates with another key or primary key is not installed")
		void shouldRefuseIndexOfAnotherIdentity(@Nonnull CatalogState state) {
			prepare(state);
			update(session -> {
				final int allocatedPrimaryKey = products().getEntityIndexMaintainer().allocatePartitionPrimaryKey();
				final int otherPrimaryKey = allocatedPrimaryKey - 1;
				assertRefused(
					UNREFERENCED_PARTITION, allocatedPrimaryKey,
					primaryKey -> new ReducedEntityIndex(otherPrimaryKey, ENTITY_PRODUCT, UNREFERENCED_PARTITION),
					"the factory created"
				);
				assertRefused(
					UNREFERENCED_PARTITION, allocatedPrimaryKey, emptyPartition(partitionKey(SECOND_CATEGORY_PK + 10)),
					"the factory created"
				);
			});
		}

		/**
		 * Asserts that the installation is refused with a message naming the key, the primary key and the reason, and
		 * that the refusal registered nothing and counted nothing.
		 *
		 * @param key        the key to install under
		 * @param primaryKey the primary key to install under
		 * @param factory    the factory of the index
		 * @param reason     a fragment of the message naming the violated premise
		 */
		private void assertRefused(
			@Nonnull EntityIndexKey key,
			int primaryKey,
			@Nonnull IntFunction<EntityIndex> factory,
			@Nonnull String reason
		) {
			final EntityCollection products = products();
			final EntityIndex heldByKey = products.getIndexByKeyIfExists(key);
			final EntityIndex heldByPrimaryKey = products.getIndexByPrimaryKeyIfExists(primaryKey);
			final int indexCountBefore = products.getIndexCount();

			final GenericEvitaInternalError refusal = assertThrows(
				GenericEvitaInternalError.class,
				() -> products.getEntityIndexMaintainer().installIndex(key, primaryKey, factory)
			);

			final String message = refusal.getMessage();
			assertTrue(message.contains(key.toString()), () -> "The refusal must name the key: " + message);
			assertTrue(
				message.contains("primary key " + primaryKey), () -> "The refusal must name the primary key: " + message
			);
			assertTrue(message.contains(reason), () -> "The refusal must name the reason: " + message);
			assertSame(heldByKey, products.getIndexByKeyIfExists(key), "A refusal must not register the key.");
			assertSame(
				heldByPrimaryKey, products.getIndexByPrimaryKeyIfExists(primaryKey),
				"A refusal must not register the primary key."
			);
			assertEquals(indexCountBefore, products.getIndexCount(), "A refusal must not count anything.");
		}
	}

	@Nested
	@DisplayName("Retirement and re-installation in one flush window")
	class Replacement {

		@Test
		@DisplayName("the index installed last survives a warm-up flush and a reload, the retired one does not")
		void shouldKeepIndexInstalledLastAfterWarmUpFlushAndReload() {
			prepare(CatalogState.WARMING_UP);
			final int primaryKey = products().getIndexByKeyIfExists(FIRST_PARTITION).getPrimaryKey();
			final int indexCountBefore = products().getIndexCount();
			final int filterRootsBefore = countPersistedFilterRoots();
			final int[] allocatedPrimaryKey = new int[1];

			update(session -> allocatedPrimaryKey[0] = retireAndReinstallTwice(products()));

			assertReplacementInstalledLast(primaryKey, allocatedPrimaryKey[0]);
			assertEquals(indexCountBefore + 1, products().getIndexCount(), "replaced one, installed one");

			reload();

			assertReplacementInstalledLast(primaryKey, allocatedPrimaryKey[0]);
			assertEquals(indexCountBefore + 1, products().getIndexCount(), "replaced one, installed one");
			assertEquals(
				filterRootsBefore - 1, countPersistedFilterRoots(),
				"The filter sub-index root of the retired partition must have been reclaimed."
			);
		}

		@Test
		@DisplayName("the object installed last under the primary key of a stored one survives a commit and a reload")
		void shouldKeepIndexObjectInstalledLastAfterCommitAndReload() {
			prepareSharedPartition();
			final EntityIndex stored = assertPartitionObject(
				products().getIndexByKeyIfExists(FIRST_PARTITION).getPrimaryKey(), SECOND_PRODUCT_PK,
				"self-check: the shared partition must be an index object"
			);
			final int primaryKey = stored.getPrimaryKey();
			final int indexCountBefore = products().getIndexCount();
			final int filterRootsBefore = countPersistedFilterRoots();

			// the replay of the committed transaction retires the stored object and installs another one under its
			// primary key, retires that one and installs the last one under it - one flush window
			update(session -> {
				assertTrue(Transaction.isTransactionAvailable(), "self-check: the steps must share one transaction");
				leaveFirstCategory(session, SECOND_PRODUCT_PK, primaryKey);
				joinFirstCategory(session, UNREFERENCING_PRODUCT_PK);
				assertPartitionObject(primaryKey, UNREFERENCING_PRODUCT_PK, "self-check: a new owner promotes the row");
				leaveFirstCategory(session, UNREFERENCING_PRODUCT_PK, primaryKey);
				joinFirstCategory(session, LAST_JOINING_PRODUCT_PK);
				assertPartitionObject(
					primaryKey, LAST_JOINING_PRODUCT_PK, "self-check: another new owner promotes the row again"
				);
			});

			assertObjectInstalledLast(primaryKey);
			assertEquals(indexCountBefore, products().getIndexCount(), "one index object in place of another");

			reload();

			assertObjectInstalledLast(primaryKey);
			assertEquals(indexCountBefore, products().getIndexCount(), "one index object in place of another");
			assertEquals(
				filterRootsBefore, countPersistedFilterRoots(),
				"Only the filter sub-index root of the object installed last may be stored in place of the retired one."
			);
			assertInvariants("after the reload", IndexInstallRetireTest.this.evita, TEST_CATALOG);
			assertStoredParts("after the reload", IndexInstallRetireTest.this.evita, TEST_CATALOG);
		}

		/**
		 * Lets a product leave the first category's partition, which the first product keeps, and asserts the
		 * partition was demoted to a row under its primary key, its index object retired.
		 *
		 * @param session    the session to write in
		 * @param product    the leaving product
		 * @param primaryKey the primary key of the partition
		 */
		private void leaveFirstCategory(@Nonnull EvitaSessionContract session, int product, int primaryKey) {
			session.getEntity(ENTITY_PRODUCT, product, entityFetchAllContent())
				.orElseThrow()
				.openForWrite()
				.removeReference(REF_CATEGORIES, FIRST_CATEGORY_PK)
				.upsertVia(session);
			assertNull(
				products().getIndexByPrimaryKeyIfExists(primaryKey),
				"self-check: an owner leaving demotes the partition, retiring its index object"
			);
			assertEquals(
				primaryKey, registeredPartitionOf(FIRST_CATEGORY_PK),
				"self-check: the demoted partition must be a row under its primary key"
			);
		}

		/**
		 * Lets a product join the first category's partition, which the first product owns, with a value of the
		 * filterable reference attribute of its own.
		 *
		 * @param session the session to write in
		 * @param product the joining product
		 */
		private static void joinFirstCategory(@Nonnull EvitaSessionContract session, int product) {
			session.getEntity(ENTITY_PRODUCT, product, entityFetchAllContent())
				.orElseThrow()
				.openForWrite()
				.setReference(REF_CATEGORIES, FIRST_CATEGORY_PK, ref -> ref.setAttribute(ATTRIBUTE_ORDER, product))
				.upsertVia(session);
		}

		/**
		 * Asserts that the first category's partition is an index object under the given primary key, owned by the
		 * first product and the given second owner.
		 *
		 * @param primaryKey  the primary key of the partition
		 * @param secondOwner the second owner
		 * @param message     the failure message
		 * @return the index object
		 */
		@Nonnull
		private EntityIndex assertPartitionObject(int primaryKey, int secondOwner, @Nonnull String message) {
			final EntityCollection products = products();
			final EntityIndex partition = products.getIndexByPrimaryKeyIfExists(primaryKey);
			assertInstanceOf(ReducedEntityIndex.class, partition, message + ": the partition must be an index object");
			assertEquals(FIRST_PARTITION, partition.getIndexKey(), message + ": the key of the index object");
			assertSame(
				partition, products.getIndexByKeyIfExists(FIRST_PARTITION), message + ": by key and by primary key"
			);
			assertArrayEquals(
				new int[]{FIRST_PRODUCT_PK, secondOwner}, partition.getAllPrimaryKeys().getArray(),
				message + ": the owners of the index object"
			);
			return partition;
		}

		/**
		 * Asserts that the first category's partition is the index object installed last: owned by the first product
		 * and the product that joined last - neither the stored object the transaction retired nor the interim one it
		 * installed and retired - with a filter sub-index holding the values of its own owners only.
		 *
		 * @param primaryKey the primary key of the partition
		 */
		private void assertObjectInstalledLast(int primaryKey) {
			final EntityIndex partition = assertPartitionObject(
				primaryKey, LAST_JOINING_PRODUCT_PK,
				"The partition must be the object installed last, not a retired one"
			);
			final SortedSet<Integer> filtered = new TreeSet<>();
			partition.forEachAttributeIndexKey(
				AttributeIndexType.FILTER,
				key -> {
					final FilterIndex filterIndex = partition.getFilterIndex(key);
					if (filterIndex != null) {
						for (final int owner : filterIndex.getAllRecords().getArray()) {
							filtered.add(owner);
						}
					}
				}
			);
			assertEquals(
				Set.of(FIRST_PRODUCT_PK, LAST_JOINING_PRODUCT_PK), filtered,
				"The filter sub-index of the object installed last must hold the values of its own owners only."
			);
		}
	}

	@Nested
	@DisplayName("Rollback")
	class Rollback {

		@Test
		@DisplayName("a warm-up savepoint rollback takes the installations back out of both maps")
		void shouldRestoreBothMapsOnWarmUpSavepointRollback() {
			prepare(CatalogState.WARMING_UP);
			final int filterRootsBefore = countPersistedFilterRoots();

			update(session -> {
				final EntityCollection products = products();
				final EntityIndex original = products.getIndexByKeyIfExists(FIRST_PARTITION);
				final int indexCountBefore = products.getIndexCount();
				final int allocatedPrimaryKey = products.getEntityIndexMaintainer().allocatePartitionPrimaryKey();

				final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
				replaceAndInstall(products, original.getPrimaryKey(), allocatedPrimaryKey);
				savepoint.rollback();

				assertRestored(products, original, allocatedPrimaryKey, indexCountBefore);
			});

			// the savepoint also took back the removals the retirement staged, so the flush closing the session above
			// reclaimed nothing of the original partition
			reload();
			final EntityIndex reloaded = products().getIndexByKeyIfExists(FIRST_PARTITION);
			assertNotNull(reloaded, "The original partition must survive the flush and the reload.");
			assertNotNull(reloaded.getFilterIndex(CODE), "The original partition must keep its filter sub-index.");
			assertEquals(filterRootsBefore, countPersistedFilterRoots(), "No filter sub-index root may be reclaimed.");
		}

		@Test
		@DisplayName("a transaction rollback leaves both maps of the collection untouched")
		void shouldLeaveBothMapsUntouchedOnTransactionRollback() {
			prepare(CatalogState.ALIVE);
			final EntityCollection products = products();
			final EntityIndex original = products.getIndexByKeyIfExists(FIRST_PARTITION);
			final int indexCountBefore = products.getIndexCount();
			final int[] allocatedPrimaryKey = new int[1];

			assertThrows(
				RollbackException.class,
				() -> update(session -> {
					final EntityCollection productsInTransaction = products();
					allocatedPrimaryKey[0] = productsInTransaction.getEntityIndexMaintainer()
						.allocatePartitionPrimaryKey();
					replaceAndInstall(productsInTransaction, original.getPrimaryKey(), allocatedPrimaryKey[0]);
					session.setRollbackOnly();
				})
			);

			assertSame(products, products(), "self-check: the rolled back transaction published no new collection");
			assertRestored(products, original, allocatedPrimaryKey[0], indexCountBefore);
		}

		@Test
		@DisplayName("a transactional savepoint rollback takes the installations back out of both maps")
		void shouldRestoreBothMapsOnTransactionalSavepointRollback() {
			prepare(CatalogState.ALIVE);

			update(session -> {
				final EntityCollection products = products();
				final EntityIndex original = products.getIndexByKeyIfExists(FIRST_PARTITION);
				final int indexCountBefore = products.getIndexCount();
				final int allocatedPrimaryKey = products.getEntityIndexMaintainer().allocatePartitionPrimaryKey();

				final TransactionalLayerMaintainer layerMaintainer = Transaction.getTransactionalLayerMaintainer();
				final Savepoint savepoint = layerMaintainer.openSavepoint();
				replaceAndInstall(products, original.getPrimaryKey(), allocatedPrimaryKey);
				layerMaintainer.rollbackSavepoint(savepoint);

				assertRestored(products, original, allocatedPrimaryKey, indexCountBefore);
			});
		}

		/**
		 * Retires the first category's partition, installs a replacement under its primary key and installs a partition
		 * of the unreferenced category under the allocated primary key, checking that both installations are visible.
		 *
		 * @param products            the product collection
		 * @param primaryKey          the primary key of the first category's partition
		 * @param allocatedPrimaryKey the primary key allocated for the unreferenced category's partition
		 */
		private void replaceAndInstall(@Nonnull EntityCollection products, int primaryKey, int allocatedPrimaryKey) {
			final IndexMaintainer<EntityIndexKey, EntityIndex> maintainer = products.getEntityIndexMaintainer();
			maintainer.removeIndex(FIRST_PARTITION);
			final EntityIndex replacement = maintainer.installIndex(
				FIRST_PARTITION, primaryKey, emptyPartition(FIRST_PARTITION)
			);
			replacement.insertPrimaryKeyIfMissing(SECOND_PRODUCT_PK);
			final EntityIndex installed = maintainer.installIndex(
				UNREFERENCED_PARTITION, allocatedPrimaryKey, emptyPartition(UNREFERENCED_PARTITION)
			);
			assertSame(replacement, products.getIndexByKeyIfExists(FIRST_PARTITION), "self-check: by key");
			assertSame(replacement, products.getIndexByPrimaryKeyIfExists(primaryKey), "self-check: by primary key");
			assertSame(installed, products.getIndexByPrimaryKeyIfExists(allocatedPrimaryKey), "self-check: allocated");
		}

		/**
		 * Asserts that the collection is back where it was before the rolled back installations: the original
		 * partition under its key and its primary key, nothing under the unreferenced category's key or the allocated
		 * primary key, the same index count, and an index sequence the installations did not advance.
		 *
		 * @param products            the product collection
		 * @param original            the original partition of the first category
		 * @param allocatedPrimaryKey the primary key allocated before the rolled back installations
		 * @param indexCountBefore    the index count before the rolled back installations
		 */
		private void assertRestored(
			@Nonnull EntityCollection products,
			@Nonnull EntityIndex original,
			int allocatedPrimaryKey,
			int indexCountBefore
		) {
			assertSame(original, products.getIndexByKeyIfExists(FIRST_PARTITION), "The key must map to the original.");
			assertSame(
				original, products.getIndexByPrimaryKeyIfExists(original.getPrimaryKey()),
				"The primary key must map to the original."
			);
			assertNull(products.getIndexByKeyIfExists(UNREFERENCED_PARTITION), "The installed key must be gone.");
			assertNull(
				products.getIndexByPrimaryKeyIfExists(allocatedPrimaryKey), "The installed primary key must be gone."
			);
			assertEquals(indexCountBefore, products.getIndexCount(), "The index count must be restored.");
			assertEquals(
				allocatedPrimaryKey + 1, products.getEntityIndexMaintainer().allocatePartitionPrimaryKey(),
				"The installations must not have advanced the index sequence."
			);
		}
	}

}
