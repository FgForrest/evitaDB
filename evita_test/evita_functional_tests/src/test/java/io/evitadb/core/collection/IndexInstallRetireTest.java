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
import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReferencePartitions;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartition;
import io.evitadb.index.attribute.FilterIndex;
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
 * Verifies how the maintainer of a collection's entity indexes installs an index object under a primary key its index
 * sequence handed out earlier, and how such an object is retired and installed again - driven by the only writes that
 * do so, the promotions and demotions of the single-owner partitions of a reference indexed for filtering. A partition
 * gaining its second owner has its index object installed under the primary key of its registry row, one left with a
 * single owner has its object retired and is kept as a row under the same primary key again.
 *
 * The installed object is reachable by its key and by its primary key, the installation does not move the sequence, a
 * replacement within one flush window leaves exactly the object installed last on disk, and a rollback - of a failed
 * upsert in warm-up, of a failed upsert inside a transaction, or of a whole transaction - takes the installation back
 * out of both maps.
 *
 * Every scenario runs against a real engine and writes through entity upserts only.
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
	/** Unique attribute of the products - a duplicate value fails an upsert after its reference writes. */
	private static final String ATTRIBUTE_CODE = "code";
	/** Filterable attribute of the reference, so an index object of a partition persists a filter sub-index. */
	private static final String ATTRIBUTE_ORDER = "order";
	/** Category shared by the first two products - its partition is an index object. */
	private static final int FIRST_CATEGORY_PK = 1;
	/** Category of the second product alone - its partition is a registry row. */
	private static final int SECOND_CATEGORY_PK = 2;
	/** Category no product references at first - a first owner mints a row for it. */
	private static final int THIRD_CATEGORY_PK = 3;
	/** Another category no product references at first. */
	private static final int FOURTH_CATEGORY_PK = 4;
	/** Product referencing {@link #FIRST_CATEGORY_PK}. */
	private static final int FIRST_PRODUCT_PK = 100;
	/** Product referencing {@link #FIRST_CATEGORY_PK} and {@link #SECOND_CATEGORY_PK}. */
	private static final int SECOND_PRODUCT_PK = 101;
	/** Product without references that joins a partition. */
	private static final int UNREFERENCING_PRODUCT_PK = 102;
	/** Product without references that joins a partition last. */
	private static final int LAST_JOINING_PRODUCT_PK = 103;
	private static final EntityIndexKey FIRST_PARTITION = partitionKey(FIRST_CATEGORY_PK);
	private static final EntityIndexKey SECOND_PARTITION = partitionKey(SECOND_CATEGORY_PK);
	private static final EntityIndexKey THIRD_PARTITION = partitionKey(THIRD_CATEGORY_PK);

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
	 * Returns the code of a product.
	 *
	 * @param product the product
	 * @return its unique code
	 */
	@Nonnull
	private static String codeOf(int product) {
		return "code-" + product;
	}

	/**
	 * Lets a product join a category's partition, with a value of the filterable reference attribute of its own.
	 *
	 * @param session  the session to write in
	 * @param product  the joining product
	 * @param category the category
	 */
	private static void joinCategory(@Nonnull EvitaSessionContract session, int product, int category) {
		session.getEntity(ENTITY_PRODUCT, product, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setReference(REF_CATEGORIES, category, ref -> ref.setAttribute(ATTRIBUTE_ORDER, product))
			.upsertVia(session);
	}

	/**
	 * Builds the upsert of the first product that joins the second category - promoting its row to an index object -
	 * and the third one - minting a row for it - each with the mandatory reference attribute, and only then sets its
	 * code. Taking the code of the second product fails the whole upsert after both reference writes. The mutations
	 * are spelled out in order, because an entity built through the fluent builder emits them in hash order.
	 *
	 * @param code the code to set last
	 * @return the upsert
	 */
	@Nonnull
	private static EntityMutation joiningUpsert(@Nonnull String code) {
		final ReferenceKey second = new ReferenceKey(REF_CATEGORIES, SECOND_CATEGORY_PK);
		final ReferenceKey third = new ReferenceKey(REF_CATEGORIES, THIRD_CATEGORY_PK);
		return new EntityUpsertMutation(
			ENTITY_PRODUCT,
			FIRST_PRODUCT_PK,
			EntityExistence.MUST_EXIST,
			new InsertReferenceMutation(second),
			new ReferenceAttributeMutation(second, new UpsertAttributeMutation(ATTRIBUTE_ORDER, FIRST_PRODUCT_PK)),
			new InsertReferenceMutation(third),
			new ReferenceAttributeMutation(third, new UpsertAttributeMutation(ATTRIBUTE_ORDER, FIRST_PRODUCT_PK)),
			new UpsertAttributeMutation(ATTRIBUTE_CODE, code)
		);
	}

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("IndexInstallRetireTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	/**
	 * Creates the schema and the baseline: a reference indexed for filtering, whose single-owner partitions are rows
	 * of the registry of its referenced-type entity index, with a filterable reference attribute, so an index object
	 * of a partition persists a filter sub-index of its own. The first two products share the first category, whose
	 * partition is therefore an index object, and the second product references the second category alone, whose
	 * partition is a row. The two last products reference no category. The session closing the set-up flushes it, so
	 * the index object is on disk before any scenario starts.
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
					.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::unique)
					.withReferenceToEntity(
						REF_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFiltering()
							.withAttribute(ATTRIBUTE_ORDER, Integer.class, AttributeSchemaEditor::filterable)
					)
					.updateVia(session);
				for (int category = FIRST_CATEGORY_PK; category <= FOURTH_CATEGORY_PK; category++) {
					session.createNewEntity(ENTITY_CATEGORY, category).upsertVia(session);
				}
				session.createNewEntity(ENTITY_PRODUCT, FIRST_PRODUCT_PK)
					.setAttribute(ATTRIBUTE_CODE, codeOf(FIRST_PRODUCT_PK))
					.setReference(
						REF_CATEGORIES, FIRST_CATEGORY_PK, ref -> ref.setAttribute(ATTRIBUTE_ORDER, FIRST_PRODUCT_PK)
					)
					.upsertVia(session);
				session.createNewEntity(ENTITY_PRODUCT, SECOND_PRODUCT_PK)
					.setAttribute(ATTRIBUTE_CODE, codeOf(SECOND_PRODUCT_PK))
					.setReference(
						REF_CATEGORIES, FIRST_CATEGORY_PK, ref -> ref.setAttribute(ATTRIBUTE_ORDER, SECOND_PRODUCT_PK)
					)
					.setReference(
						REF_CATEGORIES, SECOND_CATEGORY_PK, ref -> ref.setAttribute(ATTRIBUTE_ORDER, SECOND_PRODUCT_PK)
					)
					.upsertVia(session);
				for (final int product : new int[]{UNREFERENCING_PRODUCT_PK, LAST_JOINING_PRODUCT_PK}) {
					session.createNewEntity(ENTITY_PRODUCT, product)
						.setAttribute(ATTRIBUTE_CODE, codeOf(product))
						.upsertVia(session);
				}
				if (state == CatalogState.ALIVE) {
					session.goLiveAndClose();
				}
			}
		);
		assertPartitionObject(
			products().getIndexByKeyIfExists(FIRST_PARTITION).getPrimaryKey(), SECOND_PRODUCT_PK,
			"self-check: the shared partition must be an index object"
		);
		assertTrue(registeredPartitionOf(SECOND_CATEGORY_PK) > 0, "self-check: the single-owner partition is a row");
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
	 * Lets a product leave the first category's partition, which the first product keeps, and asserts the partition
	 * was demoted to a row under its primary key, its index object retired.
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
	 * Asserts that the first category's partition is an index object under the given primary key, owned by the first
	 * product and the given second owner.
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
	 * Asserts that the filter sub-index of an index object holds the values of the given owners exactly.
	 *
	 * @param partition the index object
	 * @param owners    the owners whose values it must hold
	 * @param message   the failure message
	 */
	private static void assertFilteredOwners(
		@Nonnull EntityIndex partition,
		@Nonnull Set<Integer> owners,
		@Nonnull String message
	) {
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
		assertEquals(owners, filtered, message);
	}

	/**
	 * Asserts that the collection holds the partitions of the baseline and nothing a rolled back write left behind:
	 * the first category's index object under its primary key, the second category's row under its primary key and no
	 * index object of it, no partition of the third category at all, and the index count of the baseline.
	 *
	 * @param firstPrimaryKey  the primary key of the first category's index object
	 * @param secondPrimaryKey the primary key of the second category's row
	 * @param indexCount       the index count of the baseline
	 */
	private void assertBaseline(int firstPrimaryKey, int secondPrimaryKey, int indexCount) {
		final EntityCollection products = products();
		final EntityIndex shared = assertPartitionObject(
			firstPrimaryKey, SECOND_PRODUCT_PK, "The shared partition must be the original index object"
		);
		assertFilteredOwners(
			shared, Set.of(FIRST_PRODUCT_PK, SECOND_PRODUCT_PK),
			"The original index object must keep the filter sub-index of its owners."
		);
		assertEquals(secondPrimaryKey, registeredPartitionOf(SECOND_CATEGORY_PK), "The row must be back.");
		assertNull(products.getIndexByKeyIfExists(SECOND_PARTITION), "The installed key must be gone.");
		assertNull(products.getIndexByPrimaryKeyIfExists(secondPrimaryKey), "The installed primary key must be gone.");
		assertEquals(-1, registeredPartitionOf(THIRD_CATEGORY_PK), "The minted row must be gone.");
		assertNull(products.getIndexByKeyIfExists(THIRD_PARTITION), "No index object may exist for the minted row.");
		assertEquals(indexCount, products.getIndexCount(), "The index count must be restored.");
	}

	@Nested
	@DisplayName("Installation")
	class Installation {

		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("a promoted index object is read by its key and its row's primary key, the sequence stays put")
		void shouldReadPromotedIndexObjectByKeyAndByPrimaryKey(@Nonnull CatalogState state) {
			prepare(state);
			final int rowPrimaryKey = registeredPartitionOf(SECOND_CATEGORY_PK);

			update(session -> {
				assertEquals(state == CatalogState.ALIVE, Transaction.isTransactionAvailable(), "self-check");
				// a row minted before the promotion and one minted after it are one primary key apart, so the
				// installation in between took none of its own
				joinCategory(session, UNREFERENCING_PRODUCT_PK, THIRD_CATEGORY_PK);
				final int mintedBefore = registeredPartitionOf(THIRD_CATEGORY_PK);
				assertTrue(mintedBefore > 0, "self-check: a first owner mints a row");

				joinCategory(session, FIRST_PRODUCT_PK, SECOND_CATEGORY_PK);
				final EntityCollection products = products();
				final EntityIndex installed = products.getIndexByKeyIfExists(SECOND_PARTITION);
				assertInstanceOf(ReducedEntityIndex.class, installed, "A second owner must promote the row.");
				assertEquals(rowPrimaryKey, installed.getPrimaryKey(), "The object takes the primary key of the row.");
				assertSame(installed, products.getIndexByPrimaryKeyIfExists(rowPrimaryKey));
				assertArrayEquals(
					new int[]{FIRST_PRODUCT_PK, SECOND_PRODUCT_PK}, installed.getAllPrimaryKeys().getArray()
				);
				assertEquals(-1, registeredPartitionOf(SECOND_CATEGORY_PK), "The promoted partition keeps no row.");

				joinCategory(session, LAST_JOINING_PRODUCT_PK, FOURTH_CATEGORY_PK);
				assertEquals(
					mintedBefore + 1, registeredPartitionOf(FOURTH_CATEGORY_PK),
					"Installing must not advance the index sequence."
				);
			});
		}

		@Test
		@DisplayName("an index object installed in warm-up stays registered under both keys and counted once flushed")
		void shouldKeepInstalledIndexObjectRegisteredOnceWarmUpFlushed() {
			prepare(CatalogState.WARMING_UP);
			final int rowPrimaryKey = registeredPartitionOf(SECOND_CATEGORY_PK);
			final int indexCountBefore = products().getIndexCount();

			update(session -> joinCategory(session, FIRST_PRODUCT_PK, SECOND_CATEGORY_PK));

			// the flush emptied the dirty buffer, so both reads below are answered by the collection's own maps
			final EntityCollection products = products();
			final EntityIndex byKey = products.getIndexByKeyIfExists(SECOND_PARTITION);
			assertNotNull(byKey, "The installed index object must be registered under its key.");
			assertEquals(rowPrimaryKey, byKey.getPrimaryKey());
			assertSame(
				byKey, products.getIndexByPrimaryKeyIfExists(rowPrimaryKey),
				"The installed index object must be registered under its primary key."
			);
			assertEquals(indexCountBefore + 1, products.getIndexCount(), "The installed index object must be counted.");
		}
	}

	@Nested
	@DisplayName("Retirement and re-installation in one flush window")
	class Replacement {

		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("the object installed last under the primary key of a stored one survives a flush and a reload")
		void shouldKeepIndexObjectInstalledLastAfterFlushAndReload(@Nonnull CatalogState state) {
			prepare(state);
			final int primaryKey = products().getIndexByKeyIfExists(FIRST_PARTITION).getPrimaryKey();
			final int indexCountBefore = products().getIndexCount();
			final int filterRootsBefore = countPersistedFilterRoots();

			// the stored object is retired and another one installed under its primary key, that one is retired and
			// the last one installed under it - in warm-up between two flushes, in a transaction by the replay of the
			// committed transaction: one flush window either way
			update(session -> {
				assertEquals(
					state == CatalogState.ALIVE, Transaction.isTransactionAvailable(),
					"self-check: in a live catalog the steps must share one transaction"
				);
				leaveFirstCategory(session, SECOND_PRODUCT_PK, primaryKey);
				joinCategory(session, UNREFERENCING_PRODUCT_PK, FIRST_CATEGORY_PK);
				assertPartitionObject(primaryKey, UNREFERENCING_PRODUCT_PK, "self-check: a new owner promotes the row");
				leaveFirstCategory(session, UNREFERENCING_PRODUCT_PK, primaryKey);
				joinCategory(session, LAST_JOINING_PRODUCT_PK, FIRST_CATEGORY_PK);
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
		 * Asserts that the first category's partition is the index object installed last: owned by the first product
		 * and the product that joined last - neither the stored object the replacement retired nor the interim one it
		 * installed and retired - with a filter sub-index holding the values of its own owners only.
		 *
		 * @param primaryKey the primary key of the partition
		 */
		private void assertObjectInstalledLast(int primaryKey) {
			final EntityIndex partition = assertPartitionObject(
				primaryKey, LAST_JOINING_PRODUCT_PK,
				"The partition must be the object installed last, not a retired one"
			);
			assertFilteredOwners(
				partition, Set.of(FIRST_PRODUCT_PK, LAST_JOINING_PRODUCT_PK),
				"The filter sub-index of the object installed last must hold the values of its own owners only."
			);
		}
	}

	@Nested
	@DisplayName("Rollback")
	class Rollback {

		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("a failed upsert takes its promotion and its minted row back out of both maps")
		void shouldTakeTheInstallationOfAFailedUpsertBack(@Nonnull CatalogState state) {
			prepare(state);
			final int firstPrimaryKey = products().getIndexByKeyIfExists(FIRST_PARTITION).getPrimaryKey();
			final int secondPrimaryKey = registeredPartitionOf(SECOND_CATEGORY_PK);
			final int indexCountBefore = products().getIndexCount();
			final int filterRootsBefore = countPersistedFilterRoots();

			// the bracket of the root mutation - a warm-up savepoint, or a savepoint of the transaction - takes back
			// what the upsert wrote before its duplicate code failed it, and the session goes on
			update(session -> {
				assertEquals(state == CatalogState.ALIVE, Transaction.isTransactionAvailable(), "self-check");
				assertThrows(
					UniqueValueViolationException.class,
					() -> session.upsertEntity(joiningUpsert(codeOf(SECOND_PRODUCT_PK)))
				);
				assertBaseline(firstPrimaryKey, secondPrimaryKey, indexCountBefore);
			});

			assertBaseline(firstPrimaryKey, secondPrimaryKey, indexCountBefore);
			reload();
			assertBaseline(firstPrimaryKey, secondPrimaryKey, indexCountBefore);
			assertEquals(filterRootsBefore, countPersistedFilterRoots(), "No filter sub-index root may change.");

			// not vacuous: the failing upsert gets as far as minting the row of the third category before its duplicate
			// code fails it - the index sequence, which the upsert does not take back, hands the row the same upsert
			// with a code of its own mints the primary key after the one the failing upsert drew; the control mint
			// right before both reads the sequence within the same session
			update(session -> {
				joinCategory(session, UNREFERENCING_PRODUCT_PK, FOURTH_CATEGORY_PK);
				final int controlPrimaryKey = registeredPartitionOf(FOURTH_CATEGORY_PK);
				assertTrue(controlPrimaryKey > 0, "self-check: the control upsert mints a row");
				assertThrows(
					UniqueValueViolationException.class,
					() -> session.upsertEntity(joiningUpsert(codeOf(SECOND_PRODUCT_PK)))
				);
				assertEquals(-1, registeredPartitionOf(THIRD_CATEGORY_PK), "The minted row must be gone.");
				session.upsertEntity(joiningUpsert(codeOf(FIRST_PRODUCT_PK)));
				final EntityIndex promoted = products().getIndexByKeyIfExists(SECOND_PARTITION);
				assertNotNull(promoted, "The reference writes of the upsert must promote the row.");
				assertEquals(secondPrimaryKey, promoted.getPrimaryKey());
				assertEquals(
					controlPrimaryKey + 2, registeredPartitionOf(THIRD_CATEGORY_PK),
					"The failing upsert must have minted a row before its code failed it."
				);
			});
		}

		@Test
		@DisplayName("a transaction rollback leaves both maps of the collection untouched")
		void shouldLeaveBothMapsUntouchedOnTransactionRollback() {
			prepare(CatalogState.ALIVE);
			final EntityCollection products = products();
			final EntityIndex original = products.getIndexByKeyIfExists(FIRST_PARTITION);
			final int secondPrimaryKey = registeredPartitionOf(SECOND_CATEGORY_PK);
			final int indexCountBefore = products.getIndexCount();

			assertThrows(
				RollbackException.class,
				() -> update(session -> {
					// a demotion retiring the shared object, a promotion installing another one and a minted row
					leaveFirstCategory(session, SECOND_PRODUCT_PK, original.getPrimaryKey());
					joinCategory(session, FIRST_PRODUCT_PK, SECOND_CATEGORY_PK);
					assertNotNull(products().getIndexByKeyIfExists(SECOND_PARTITION), "self-check: promoted");
					joinCategory(session, UNREFERENCING_PRODUCT_PK, THIRD_CATEGORY_PK);
					assertTrue(registeredPartitionOf(THIRD_CATEGORY_PK) > 0, "self-check: minted");
					session.setRollbackOnly();
				})
			);

			assertSame(products, products(), "self-check: the rolled back transaction published no new collection");
			assertSame(original, products.getIndexByKeyIfExists(FIRST_PARTITION), "The key must map to the original.");
			assertBaseline(original.getPrimaryKey(), secondPrimaryKey, indexCountBefore);
		}
	}

}
