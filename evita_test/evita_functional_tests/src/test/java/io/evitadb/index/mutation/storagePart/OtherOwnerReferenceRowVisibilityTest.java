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

package io.evitadb.index.mutation.storagePart;

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.core.buffer.DataStoreMemoryBuffer;
import io.evitadb.core.buffer.TransactionalDataStoreMemoryBuffer;
import io.evitadb.core.buffer.WarmUpDataStoreMemoryBuffer;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.spi.store.catalog.persistence.EntitySchemaContext;
import io.evitadb.spi.store.catalog.persistence.accessor.EntityStoragePartAccessor;
import io.evitadb.spi.store.catalog.persistence.accessor.WritableEntityStorageContainerAccessor;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.AssociatedDataStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.AttributesStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.EntityBodyStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.PricesStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.ReferencesStoragePart;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that the mutation of one entity reads **another** entity's storage parts in the state the transaction knows
 * them - including the changes applied to that entity earlier in the same root mutation, which no executor has
 * committed yet.
 *
 * A reduced entity index of a partition with a single owner may keep no stored copy of that owner's reference row.
 * When a second owner `o₂` joins such a partition, `o₂`'s mutation has to materialize a real reduced index and replay
 * the first owner `o₁`'s reference row into it; when a partition drops back to one owner, the end-of-mutation sweep
 * has to read the survivor's row. Both reads target an entity other than the one being mutated, and both must see that
 * entity's state as the transaction knows it - not as it was last committed.
 *
 * The read goes through the storage-part accessor the index executor already holds: a
 * {@link ContainerizedLocalMutationExecutor} asked for a part of another entity first asks its
 * {@link InFlightEntityAccessorLookup} whether that entity is mutated within the same root mutation, and reads through
 * the data store reader of the entity's collection only when it is not. The data store reader overlays the
 * transactional layer (and the trapped changes of committed implicit mutations) on top of the persisted storage. The
 * test builds executors with the arguments `EntityCollection#applyMutations` passes, applies their own local
 * mutations and reads the other entity from there - the state the promotion code will be in.
 *
 * One root mutation is emulated by {@link RootMutation}, which registers the executors it opens exactly like the
 * collector of a root mutation does; the collector's own registry is covered by
 * `LocalMutationExecutorCollectorInFlightRegistryTest`. Every positive assertion is paired with a negative control
 * that reads through a reader outside the root mutation, or the committed storage directly, so a test cannot pass
 * because the in-flight value happened to be visible through the buffer already.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Reading another entity's storage parts from inside a mutation")
@Tag(INDEXING)
@Tag(REFERENCE)
@Tag(TRANSACTION)
class OtherOwnerReferenceRowVisibilityTest implements EvitaTestSupport {
	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_CATEGORY = "category";
	/** Partitioned reference of the product collection - the partitions a promotion materializes. */
	private static final String REF_CATEGORIES = "categories";
	/** Reflected counterpart on the category collection, inheriting {@link #ATTR_PRIORITY}. */
	private static final String REF_PRODUCTS = "products";
	private static final String ATTR_PRIORITY = "priority";
	/** Global attribute of the product, read by the storage-part coverage test. */
	private static final String ATTR_CODE = "code";
	/** Localized attribute of the product, read by the storage-part coverage test. */
	private static final String ATTR_NAME = "name";
	/** Associated data of the product, read by the storage-part coverage test. */
	private static final String ASSOCIATED_DATA_NOTE = "note";
	private static final String PRICE_LIST_BASIC = "basic";
	private static final Currency CURRENCY_CZK = Currency.getInstance("CZK");
	private static final PriceKey IN_FLIGHT_PRICE = new PriceKey(1, PRICE_LIST_BASIC, CURRENCY_CZK);
	private static final String IN_FLIGHT_VALUE = "in-flight";
	/** The partition both owners share: `categories` -> this category. */
	private static final int CATEGORY_PK = 7;
	/** A second category, so one product upsert can add two owners to one category-side partition. */
	private static final int SECOND_CATEGORY_PK = 8;
	/** `o₁` - the owner whose row is replayed into the promoted partition. */
	private static final int FIRST_OWNER_PK = 1;
	/** `o₂` - the owner whose mutation promotes the partition. */
	private static final int SECOND_OWNER_PK = 2;
	/** A product created by a real upsert to assert the premise of the nested-mutation witness. */
	private static final int THIRD_OWNER_PK = 3;
	private static final int COMMITTED_PRIORITY = 1;
	private static final int SECOND_OWNER_PRIORITY = 5;

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("OtherOwnerReferenceRowVisibilityTest");
		this.evita = new Evita(getEvitaConfiguration());
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Nested
	@DisplayName("Entity changed by another root mutation of the same transaction")
	class AnotherRootMutation {

		/**
		 * The first owner's reference attribute is changed by an ordinary upsert earlier in the same transaction; the
		 * second owner's mutation, which adds the reference that would promote the shared partition, must read the new
		 * value.
		 *
		 * In `ALIVE` the committed storage still holds the old value, which the negative control asserts. In
		 * `WARMING_UP` a root-level write goes straight into the persistence service (there is no overlay to
		 * bypass), so the control applies to `ALIVE` only - the trapped-change variant below is the one that
		 * separates the two in both modes.
		 *
		 * @param state catalog state the scenario runs in
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("sees a row changed by an earlier upsert of the same transaction")
		void shouldSeeOtherOwnersStagedRowWhenChangedEarlierInSameTransaction(@Nonnull CatalogState state) {
			prepare(state);
			final int stagedPriority = 2;

			OtherOwnerReferenceRowVisibilityTest.this.evita.updateCatalog(
				TEST_CATALOG,
				(Consumer<EvitaSessionContract>) session -> {
					updateFirstOwnerPriority(session, stagedPriority);

					final Catalog catalog = catalog();
					final EntityCollection products = collection(catalog, ENTITY_PRODUCT);
					final ContainerizedLocalMutationExecutor secondOwner =
						openMutationOfSecondOwner(session, new RootMutation(catalog));

					assertEquals(
						stagedPriority,
						priorityOf(secondOwner.getReferencesStoragePart(ENTITY_PRODUCT, FIRST_OWNER_PK)),
						"The second owner's mutation read the first owner's row without the change staged for it " +
							"earlier in the same transaction - a promotion would replay a stale row"
					);
					if (state == CatalogState.ALIVE) {
						assertEquals(
							COMMITTED_PRIORITY,
							committedPriorityOf(catalog, products, FIRST_OWNER_PK),
							"The staged value is already in the committed storage, so the read above proves nothing"
						);
					}
				}
			);
		}

		/**
		 * The first owner's row is changed by an **implicit** mutation - a reference attribute written on the reflected
		 * side is propagated to the owner - whose storage parts are trapped in the data store buffer until the next
		 * flush rather than written through. The second owner's mutation must still read the new value, and the
		 * committed storage must not hold it in either mode.
		 *
		 * @param state catalog state the scenario runs in
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("sees a row changed by an implicit mutation still trapped in the buffer")
		void shouldSeeOtherOwnersTrappedRowWhenChangedByImplicitMutation(@Nonnull CatalogState state) {
			prepare(state);
			final int propagatedPriority = 3;

			OtherOwnerReferenceRowVisibilityTest.this.evita.updateCatalog(
				TEST_CATALOG,
				(Consumer<EvitaSessionContract>) session -> {
					// written on the category side; the engine propagates it to the product's own reference
					session.getEntity(ENTITY_CATEGORY, CATEGORY_PK, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setReference(
							REF_PRODUCTS, FIRST_OWNER_PK,
							whichIs -> whichIs.setAttribute(ATTR_PRIORITY, propagatedPriority)
						)
						.upsertVia(session);

					final Catalog catalog = catalog();
					final EntityCollection products = collection(catalog, ENTITY_PRODUCT);
					final ContainerizedLocalMutationExecutor secondOwner =
						openMutationOfSecondOwner(session, new RootMutation(catalog));

					assertEquals(
						propagatedPriority,
						priorityOf(secondOwner.getReferencesStoragePart(ENTITY_PRODUCT, FIRST_OWNER_PK)),
						"The second owner's mutation read the first owner's row without the change an implicit " +
							"mutation trapped for it - a promotion would replay a stale row"
					);
					assertNotEquals(
						propagatedPriority,
						committedPriorityOf(catalog, products, FIRST_OWNER_PK),
						"The trapped value is already in the committed storage, so the read above proves nothing"
					);
				}
			);
		}

		/**
		 * What an executor of a **different** root mutation has applied but not committed is not visible: a root
		 * mutation either commits all its executors before the next one starts, or it fails and its changes must never
		 * be seen. Only the executors of the reader's own root mutation are consulted.
		 *
		 * @param state catalog state the scenario runs in
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("does not see a row an executor of another root mutation applied but not committed")
		void shouldNotSeeRowOfUncommittedExecutorOfAnotherRootMutation(@Nonnull CatalogState state) {
			prepare(state);

			OtherOwnerReferenceRowVisibilityTest.this.evita.updateCatalog(
				TEST_CATALOG,
				(Consumer<EvitaSessionContract>) session -> {
					final Catalog catalog = catalog();
					final ContainerizedLocalMutationExecutor secondOwner =
						openMutationOfSecondOwner(session, new RootMutation(catalog));
					final ContainerizedLocalMutationExecutor firstOwner =
						new RootMutation(catalog).open(ENTITY_PRODUCT, FIRST_OWNER_PK);

					assertEquals(
						SECOND_OWNER_PRIORITY,
						priorityOf(secondOwner.getReferencesStoragePart(ENTITY_PRODUCT, SECOND_OWNER_PK)),
						"The second owner's own accessor does not see the row its mutation has just applied"
					);
					assertNull(
						priorityOf(firstOwner.getReferencesStoragePart(ENTITY_PRODUCT, SECOND_OWNER_PK)),
						"An executor of another root mutation sees a row that was applied but never committed"
					);
				}
			);
		}
	}

	@Nested
	@DisplayName("Entity changed earlier in the same root mutation")
	class SameRootMutation {

		/**
		 * The counterpart of the previous scenario: when both executors belong to the same root mutation - the first
		 * owner's executor is a nested mutation of the second owner's root one - the row the second owner's executor
		 * has applied is visible to it, served from the second owner's own executor.
		 *
		 * @param state catalog state the scenario runs in
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("sees a row another executor of the root mutation applied but not committed")
		void shouldSeeRowOfUncommittedExecutorOfSameRootMutation(@Nonnull CatalogState state) {
			prepare(state);

			OtherOwnerReferenceRowVisibilityTest.this.evita.updateCatalog(
				TEST_CATALOG,
				(Consumer<EvitaSessionContract>) session -> {
					final Catalog catalog = catalog();
					final RootMutation rootMutation = new RootMutation(catalog);
					final ContainerizedLocalMutationExecutor secondOwner =
						openMutationOfSecondOwner(session, rootMutation);
					final ContainerizedLocalMutationExecutor firstOwner =
						rootMutation.openNested(ENTITY_PRODUCT, FIRST_OWNER_PK);

					final ReferencesStoragePart seen =
						firstOwner.getReferencesStoragePart(ENTITY_PRODUCT, SECOND_OWNER_PK);
					assertEquals(
						SECOND_OWNER_PRIORITY,
						priorityOf(seen),
						"An executor of the same root mutation does not see the row another one has applied"
					);
					assertSame(
						secondOwner.getReferencesStoragePart(ENTITY_PRODUCT, SECOND_OWNER_PK),
						seen,
						"The row was not served by the executor that applied it"
					);
					assertNull(
						priorityOf(
							new RootMutation(catalog).open(ENTITY_PRODUCT, FIRST_OWNER_PK)
								.getReferencesStoragePart(ENTITY_PRODUCT, SECOND_OWNER_PK)
						),
						"The applied row is visible outside the root mutation, so the read above proves nothing"
					);
				}
			);
		}

		/**
		 * The commonest promotion trigger: ONE product upsert adding two categories makes each category receive the
		 * reflected `products` row in its own nested mutation of the same root mutation. The first nested mutation
		 * creates the category-side partition `products -> product` with one owner, the second promotes it - and has
		 * to replay the first category's row, which at that moment exists only in the first category's own, still
		 * uncommitted executor. With a clean history (neither category touched earlier in the transaction) the data
		 * store reader cannot see it, so the row must come from that executor.
		 *
		 * The premise that one upsert really puts both categories into one partition is asserted on a real upsert at
		 * the end, after the reads, so that it cannot disturb them.
		 *
		 * @param state catalog state the scenario runs in
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("sees a sibling nested mutation's row when one upsert adds two owners to a partition")
		void shouldSeeSiblingNestedRowWhenOneUpsertAddsTwoOwnersToPartition(@Nonnull CatalogState state) {
			prepare(state);

			OtherOwnerReferenceRowVisibilityTest.this.evita.updateCatalog(
				TEST_CATALOG,
				(Consumer<EvitaSessionContract>) session -> {
					final Catalog catalog = catalog();
					final ContainerizedLocalMutationExecutor[] nested =
						openNestedReflectedInserts(session, new RootMutation(catalog));

					final ReferencesStoragePart ownRow =
						nested[0].getReferencesStoragePart(ENTITY_CATEGORY, CATEGORY_PK);
					assertTrue(
						hasProductsRow(ownRow),
						"The first category's own executor does not hold the reflected row its nested mutation applied"
					);
					final ReferencesStoragePart seenBySibling =
						nested[1].getReferencesStoragePart(ENTITY_CATEGORY, CATEGORY_PK);
					assertTrue(
						hasProductsRow(seenBySibling),
						"The second nested mutation does not see the first one's uncommitted row - a promotion " +
							"would replay a partition without it"
					);
					assertSame(ownRow, seenBySibling, "The row was not served by the executor that applied it");
					assertFalse(
						hasProductsRow(
							new RootMutation(catalog).open(ENTITY_CATEGORY, SECOND_CATEGORY_PK)
								.getReferencesStoragePart(ENTITY_CATEGORY, CATEGORY_PK)
						),
						"The uncommitted row is visible outside the root mutation, so the read above proves nothing"
					);

					assertOneUpsertJoinsBothCategories(session, collection(catalog, ENTITY_CATEGORY));
				}
			);
		}

		/**
		 * The same promotion trigger, but the first category's references part is already **trapped** in the buffer -
		 * an implicit mutation touched it earlier in the transaction (here the real upsert asserting the premise). The
		 * buffer hands the trapped instance out by reference and the nested executor mutates it in place, so a reader
		 * going to the buffer would see the uncommitted row by accident of aliasing. Together with the clean-history
		 * scenario above this shows that the visibility does not depend on the part's history: in both cases the row
		 * comes from the executor that applied it.
		 *
		 * @param state catalog state the scenario runs in
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("sees a sibling nested mutation's row also when its part is already trapped in the buffer")
		void shouldSeeSiblingNestedRowWhenFirstOwnersPartIsAlreadyTrapped(@Nonnull CatalogState state) {
			prepare(state);

			OtherOwnerReferenceRowVisibilityTest.this.evita.updateCatalog(
				TEST_CATALOG,
				(Consumer<EvitaSessionContract>) session -> {
					final Catalog catalog = catalog();
					// traps both categories' references parts in the buffer
					assertOneUpsertJoinsBothCategories(session, collection(catalog, ENTITY_CATEGORY));

					final ContainerizedLocalMutationExecutor[] nested =
						openNestedReflectedInserts(session, new RootMutation(catalog));
					final ReferencesStoragePart ownRow =
						nested[0].getReferencesStoragePart(ENTITY_CATEGORY, CATEGORY_PK);
					final ReferencesStoragePart seenBySibling =
						nested[1].getReferencesStoragePart(ENTITY_CATEGORY, CATEGORY_PK);

					assertTrue(
						hasProductsRow(seenBySibling),
						"The second nested mutation does not see the first one's uncommitted row"
					);
					assertSame(ownRow, seenBySibling, "The row was not served by the executor that applied it");
				}
			);
		}

		/**
		 * Every kind of storage part of an entity mutated earlier in the same root mutation is read from that entity's
		 * executor - the body with its locales, the global and the localized attributes, the associated data, the
		 * references and the prices - also when the reader is an executor of **another collection**. The root
		 * mutation changes the second owner product; a nested mutation of a category reads the product back.
		 *
		 * The control reader belongs to no root mutation in flight and sees none of the changes, so each positive
		 * assertion fails the moment the in-flight lookup stops being consulted.
		 *
		 * @param state catalog state the scenario runs in
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("reads every storage part of an entity of another collection from its in-flight executor")
		void shouldReadEveryStoragePartOfInFlightEntityOfAnotherCollection(@Nonnull CatalogState state) {
			prepare(state);

			OtherOwnerReferenceRowVisibilityTest.this.evita.updateCatalog(
				TEST_CATALOG,
				(Consumer<EvitaSessionContract>) session -> {
					final Catalog catalog = catalog();
					final RootMutation rootMutation = new RootMutation(catalog);
					final EntityMutation productMutation = session
						.getEntity(ENTITY_PRODUCT, SECOND_OWNER_PK, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_CODE, IN_FLIGHT_VALUE)
						.setAttribute(ATTR_NAME, Locale.ENGLISH, IN_FLIGHT_VALUE)
						.setAssociatedData(ASSOCIATED_DATA_NOTE, IN_FLIGHT_VALUE)
						.setPrice(
							IN_FLIGHT_PRICE.priceId(), PRICE_LIST_BASIC, CURRENCY_CZK,
							BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, true
						)
						.setReference(
							REF_CATEGORIES, CATEGORY_PK,
							whichIs -> whichIs.setAttribute(ATTR_PRIORITY, SECOND_OWNER_PRIORITY)
						)
						.toMutation()
						.orElseThrow();
					final ContainerizedLocalMutationExecutor product =
						rootMutation.open(ENTITY_PRODUCT, SECOND_OWNER_PK);
					applyLocalPhase(product, productMutation.getLocalMutations());
					final ContainerizedLocalMutationExecutor category =
						rootMutation.openNested(ENTITY_CATEGORY, CATEGORY_PK);

					assertInFlightPartsVisible(category, product);
					assertInFlightPartsInvisible(new RootMutation(catalog).open(ENTITY_CATEGORY, CATEGORY_PK));
				}
			);
		}

		/**
		 * The accessor an executor hands out for another entity of its own type - the one through which a partition
		 * gaining its second owner reads its first owner: the executor itself for its own entity, the other entity's
		 * in-flight executor, with its pending locale changes, when the root mutation mutates that entity too, and
		 * otherwise a read-only view reading through the executor, with no pending locale change, no removal and no
		 * price identifier of its own.
		 *
		 * @param state catalog state the scenario runs in
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("hands out the accessor of another entity by whether the root mutation mutates it")
		void shouldHandOutAccessorOfOtherEntityByWhetherRootMutationMutatesIt(@Nonnull CatalogState state) {
			prepare(state);

			OtherOwnerReferenceRowVisibilityTest.this.evita.updateCatalog(
				TEST_CATALOG,
				(Consumer<EvitaSessionContract>) session -> {
					final RootMutation rootMutation = new RootMutation(catalog());
					final ContainerizedLocalMutationExecutor secondOwner =
						openMutationOfSecondOwner(session, rootMutation);
					assertSame(
						secondOwner, secondOwner.getAccessorOfOtherEntity(SECOND_OWNER_PK),
						"The executor must answer for its own entity itself"
					);

					final WritableEntityStorageContainerAccessor outside =
						secondOwner.getAccessorOfOtherEntity(FIRST_OWNER_PK);
					assertAll(
						() -> assertEquals(
							COMMITTED_PRIORITY,
							priorityOf(outside.getReferencesStoragePart(ENTITY_PRODUCT, FIRST_OWNER_PK)),
							"The view must read the committed row of an entity the root mutation does not mutate"
						),
						() -> assertEquals(0, outside.getAddedLocales().length),
						() -> assertEquals(0, outside.getRemovedLocales().length),
						() -> assertFalse(outside.isEntityRemovedEntirely()),
						() -> assertTrue(
							outside.findExistingInternalId(ENTITY_PRODUCT, FIRST_OWNER_PK, IN_FLIGHT_PRICE).isEmpty()
						),
						() -> assertThrows(
							GenericEvitaInternalError.class,
							() -> outside.registerAssignedPriceId(FIRST_OWNER_PK, IN_FLIGHT_PRICE, 1)
						),
						() -> assertSame(secondOwner, outside.getAccessorOfOtherEntity(SECOND_OWNER_PK))
					);

					final EntityMutation firstOwnerMutation = session
						.getEntity(ENTITY_PRODUCT, FIRST_OWNER_PK, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_NAME, Locale.ENGLISH, IN_FLIGHT_VALUE)
						.toMutation()
						.orElseThrow();
					final ContainerizedLocalMutationExecutor firstOwner =
						rootMutation.openNested(ENTITY_PRODUCT, FIRST_OWNER_PK);
					applyLocalPhase(firstOwner, firstOwnerMutation.getLocalMutations());
					final WritableEntityStorageContainerAccessor inFlight =
						secondOwner.getAccessorOfOtherEntity(FIRST_OWNER_PK);
					assertSame(firstOwner, inFlight, "The in-flight executor of the other entity must be handed out");
					assertEquals(
						Locale.ENGLISH, inFlight.getAddedLocales()[0].locale(),
						"The other entity's pending locale must be known through its accessor"
					);
				}
			);
		}

		/**
		 * Asserts that `reader` reads each storage part of the second owner product in the state `owner` has applied,
		 * and that it gets the very instances `owner` holds.
		 *
		 * @param reader executor of another entity issuing the reads
		 * @param owner  the in-flight executor of the second owner product
		 */
		private static void assertInFlightPartsVisible(
			@Nonnull EntityStoragePartAccessor reader,
			@Nonnull EntityStoragePartAccessor owner
		) {
			final AssociatedDataKey noteKey = new AssociatedDataKey(ASSOCIATED_DATA_NOTE);
			// one independent check per storage part kind, so a part read past the in-flight executor is reported
			// on its own rather than hidden behind the first failing one
			assertAll(
				() -> {
					final EntityBodyStoragePart body =
						reader.getEntityStoragePart(ENTITY_PRODUCT, SECOND_OWNER_PK, EntityExistence.MUST_EXIST);
					assertTrue(
						body.getLocales().contains(Locale.ENGLISH), "The entity locale added in flight is not seen"
					);
					assertTrue(
						body.getAttributeLocales().contains(Locale.ENGLISH),
						"The attribute locale added in flight is not seen"
					);
					assertSame(
						owner.getEntityStoragePart(ENTITY_PRODUCT, SECOND_OWNER_PK, EntityExistence.MUST_EXIST), body
					);
				},
				() -> {
					final AttributesStoragePart attributes =
						reader.getAttributeStoragePart(ENTITY_PRODUCT, SECOND_OWNER_PK);
					assertEquals(
						IN_FLIGHT_VALUE, attributeOf(attributes, new AttributeKey(ATTR_CODE)),
						"The global attribute set in flight is not seen"
					);
					assertSame(owner.getAttributeStoragePart(ENTITY_PRODUCT, SECOND_OWNER_PK), attributes);
				},
				() -> {
					final AttributesStoragePart attributes =
						reader.getAttributeStoragePart(ENTITY_PRODUCT, SECOND_OWNER_PK, Locale.ENGLISH);
					assertEquals(
						IN_FLIGHT_VALUE, attributeOf(attributes, new AttributeKey(ATTR_NAME, Locale.ENGLISH)),
						"The localized attribute set in flight is not seen"
					);
					assertSame(
						owner.getAttributeStoragePart(ENTITY_PRODUCT, SECOND_OWNER_PK, Locale.ENGLISH), attributes
					);
				},
				() -> {
					final AssociatedDataStoragePart associatedData =
						reader.getAssociatedDataStoragePart(ENTITY_PRODUCT, SECOND_OWNER_PK, noteKey);
					assertEquals(
						IN_FLIGHT_VALUE, associatedDataOf(associatedData),
						"The associated data set in flight are not seen"
					);
					assertSame(
						owner.getAssociatedDataStoragePart(ENTITY_PRODUCT, SECOND_OWNER_PK, noteKey), associatedData
					);
				},
				() -> {
					final ReferencesStoragePart references =
						reader.getReferencesStoragePart(ENTITY_PRODUCT, SECOND_OWNER_PK);
					assertEquals(
						SECOND_OWNER_PRIORITY, priorityOf(references), "The reference added in flight is not seen"
					);
					assertSame(owner.getReferencesStoragePart(ENTITY_PRODUCT, SECOND_OWNER_PK), references);
				},
				() -> {
					final PricesStoragePart prices = reader.getPriceStoragePart(ENTITY_PRODUCT, SECOND_OWNER_PK);
					assertNotNull(prices.getPriceByKey(IN_FLIGHT_PRICE), "The price added in flight is not seen");
					assertSame(owner.getPriceStoragePart(ENTITY_PRODUCT, SECOND_OWNER_PK), prices);
				}
			);
		}

		/**
		 * Asserts that `reader` sees none of the second owner product's in-flight changes - the negative control of
		 * {@link #assertInFlightPartsVisible}.
		 *
		 * @param reader executor of another entity outside the root mutation
		 */
		private static void assertInFlightPartsInvisible(@Nonnull EntityStoragePartAccessor reader) {
			final EntityBodyStoragePart body =
				reader.getEntityStoragePart(ENTITY_PRODUCT, SECOND_OWNER_PK, EntityExistence.MUST_EXIST);
			assertFalse(body.getLocales().contains(Locale.ENGLISH));
			assertFalse(body.getAttributeLocales().contains(Locale.ENGLISH));
			assertNull(
				attributeOf(
					reader.getAttributeStoragePart(ENTITY_PRODUCT, SECOND_OWNER_PK), new AttributeKey(ATTR_CODE)
				)
			);
			assertNull(
				attributeOf(
					reader.getAttributeStoragePart(ENTITY_PRODUCT, SECOND_OWNER_PK, Locale.ENGLISH),
					new AttributeKey(ATTR_NAME, Locale.ENGLISH)
				)
			);
			assertNull(
				associatedDataOf(
					reader.getAssociatedDataStoragePart(
						ENTITY_PRODUCT, SECOND_OWNER_PK, new AssociatedDataKey(ASSOCIATED_DATA_NOTE)
					)
				)
			);
			assertNull(priorityOf(reader.getReferencesStoragePart(ENTITY_PRODUCT, SECOND_OWNER_PK)));
			assertNull(reader.getPriceStoragePart(ENTITY_PRODUCT, SECOND_OWNER_PK).getPriceByKey(IN_FLIGHT_PRICE));
		}
	}

	@Nested
	@DisplayName("Entity the root mutation does not mutate")
	class EntityNotMutated {

		/**
		 * An entity the root mutation does not mutate cannot change while it runs, so every executor of the root
		 * mutation reading it is answered by the parts the first read fetched - the references fetched once, however
		 * many reads and executors ask for them.
		 *
		 * @param state catalog state the scenario runs in
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("reads each part of it once within the root mutation")
		void shouldReadEachPartOnceWhenTheRootMutationReadsItRepeatedly(@Nonnull CatalogState state) {
			prepare(state);

			OtherOwnerReferenceRowVisibilityTest.this.evita.updateCatalog(
				TEST_CATALOG,
				(Consumer<EvitaSessionContract>) session -> {
					final Catalog catalog = catalog();
					final RootMutation rootMutation = RootMutation.keepingForeignParts(catalog);
					final ContainerizedLocalMutationExecutor secondOwner =
						openMutationOfSecondOwner(session, rootMutation);
					final ContainerizedLocalMutationExecutor thirdOwner =
						rootMutation.openNested(ENTITY_PRODUCT, THIRD_OWNER_PK);

					final ReferencesStoragePart references =
						secondOwner.getReferencesStoragePart(ENTITY_PRODUCT, FIRST_OWNER_PK);
					assertEquals(COMMITTED_PRIORITY, priorityOf(references));
					assertAll(
						() -> assertSame(
							references, secondOwner.getReferencesStoragePart(ENTITY_PRODUCT, FIRST_OWNER_PK)
						),
						() -> assertSame(
							references, thirdOwner.getReferencesStoragePart(ENTITY_PRODUCT, FIRST_OWNER_PK),
							"another executor of the root mutation must be answered by the same read"
						),
						() -> assertSame(
							secondOwner.getEntityStoragePart(
								ENTITY_PRODUCT, FIRST_OWNER_PK, EntityExistence.MUST_EXIST
							),
							thirdOwner.getEntityStoragePart(ENTITY_PRODUCT, FIRST_OWNER_PK, EntityExistence.MUST_EXIST)
						),
						() -> assertSame(
							secondOwner.getAttributeStoragePart(ENTITY_PRODUCT, FIRST_OWNER_PK),
							thirdOwner.getAttributeStoragePart(ENTITY_PRODUCT, FIRST_OWNER_PK)
						),
						() -> assertSame(
							secondOwner.getAttributeStoragePart(ENTITY_PRODUCT, FIRST_OWNER_PK, Locale.ENGLISH),
							thirdOwner.getAttributeStoragePart(ENTITY_PRODUCT, FIRST_OWNER_PK, Locale.ENGLISH)
						)
					);
				}
			);
		}

		/**
		 * An entity read before the root mutation starts mutating it is read from its executor afterwards: the parts
		 * read before describe the entity as it was, and the executor asks whether the entity is in flight before it
		 * looks at them. The root mutation here keeps those parts on purpose, so the ordering alone is proven.
		 *
		 * @param state catalog state the scenario runs in
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("reads it from its executor once the root mutation starts mutating it")
		void shouldReadTheStagedEntityWhenTheRootMutationStartedMutatingItAfterARead(@Nonnull CatalogState state) {
			prepare(state);

			OtherOwnerReferenceRowVisibilityTest.this.evita.updateCatalog(
				TEST_CATALOG,
				(Consumer<EvitaSessionContract>) session -> {
					final RootMutation rootMutation = RootMutation.keepingForeignParts(catalog());
					final ContainerizedLocalMutationExecutor secondOwner =
						openMutationOfSecondOwner(session, rootMutation);
					final EntityBodyStoragePart bodyBefore =
						secondOwner.getEntityStoragePart(ENTITY_PRODUCT, FIRST_OWNER_PK, EntityExistence.MUST_EXIST);
					assertFalse(bodyBefore.getAttributeLocales().contains(Locale.ENGLISH));
					assertEquals(
						COMMITTED_PRIORITY,
						priorityOf(secondOwner.getReferencesStoragePart(ENTITY_PRODUCT, FIRST_OWNER_PK))
					);
					assertNull(
						attributeOf(
							secondOwner.getAttributeStoragePart(ENTITY_PRODUCT, FIRST_OWNER_PK, Locale.ENGLISH),
							new AttributeKey(ATTR_NAME, Locale.ENGLISH)
						)
					);

					final EntityMutation firstOwnerMutation = session
						.getEntity(ENTITY_PRODUCT, FIRST_OWNER_PK, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTR_NAME, Locale.ENGLISH, IN_FLIGHT_VALUE)
						.setReference(
							REF_CATEGORIES, CATEGORY_PK,
							whichIs -> whichIs.setAttribute(ATTR_PRIORITY, SECOND_OWNER_PRIORITY)
						)
						.toMutation()
						.orElseThrow();
					final ContainerizedLocalMutationExecutor firstOwner =
						rootMutation.openNested(ENTITY_PRODUCT, FIRST_OWNER_PK);
					applyLocalPhase(firstOwner, firstOwnerMutation.getLocalMutations());

					assertAll(
						() -> assertSame(
							firstOwner.getEntityStoragePart(ENTITY_PRODUCT, FIRST_OWNER_PK, EntityExistence.MUST_EXIST),
							secondOwner.getEntityStoragePart(
								ENTITY_PRODUCT, FIRST_OWNER_PK, EntityExistence.MUST_EXIST
							),
							"the body must be read from the executor of the entity"
						),
						() -> assertTrue(
							secondOwner.getEntityStoragePart(ENTITY_PRODUCT, FIRST_OWNER_PK, EntityExistence.MUST_EXIST)
								.getAttributeLocales().contains(Locale.ENGLISH),
							"the attribute locale gained in flight must be seen"
						),
						() -> assertEquals(
							SECOND_OWNER_PRIORITY,
							priorityOf(secondOwner.getReferencesStoragePart(ENTITY_PRODUCT, FIRST_OWNER_PK)),
							"the reference changed in flight must be seen"
						),
						() -> assertEquals(
							IN_FLIGHT_VALUE,
							attributeOf(
								secondOwner.getAttributeStoragePart(ENTITY_PRODUCT, FIRST_OWNER_PK, Locale.ENGLISH),
								new AttributeKey(ATTR_NAME, Locale.ENGLISH)
							),
							"the localized attribute set in flight must be seen"
						)
					);
				}
			);
		}
	}

	/**
	 * Emulates one root entity mutation the way its collector sees it: every executor opened here is registered, and
	 * every executor opened here is handed this registry as its {@link InFlightEntityAccessorLookup}. Executors are
	 * never committed, exactly as they are not while the index executors of the root mutation decide a promotion or a
	 * demotion.
	 *
	 * A root mutation created by {@link #keepingForeignParts(Catalog)} also keeps the parts its executors read of the
	 * entities it does not mutate, as the collector does - but it keeps them even after it opens an executor of such an
	 * entity, which the collector does not, so that only the order in which an executor asks the lookup keeps a read of
	 * that entity from being answered by the parts read before.
	 */
	private static final class RootMutation implements InFlightEntityAccessorLookup {
		private final Catalog catalog;
		private final Map<String, ContainerizedLocalMutationExecutor> executors = CollectionUtils.createHashMap(4);
		/**
		 * The parts read of the entities the root mutation does not mutate, `null` when it keeps none.
		 */
		@Nullable private final Map<String, ForeignEntityStorageParts> foreignParts;

		/**
		 * Creates an empty root mutation keeping no part of the entities it does not mutate.
		 *
		 * @param catalog catalog the session works with
		 */
		RootMutation(@Nonnull Catalog catalog) {
			this(catalog, null);
		}

		/**
		 * Creates an empty root mutation.
		 *
		 * @param catalog      catalog the session works with
		 * @param foreignParts the parts read of the entities the root mutation does not mutate, `null` to keep none
		 */
		private RootMutation(@Nonnull Catalog catalog, @Nullable Map<String, ForeignEntityStorageParts> foreignParts) {
			this.catalog = catalog;
			this.foreignParts = foreignParts;
		}

		/**
		 * Creates an empty root mutation keeping the parts its executors read of the entities it does not mutate.
		 *
		 * @param catalog catalog the session works with
		 * @return the root mutation
		 */
		@Nonnull
		static RootMutation keepingForeignParts(@Nonnull Catalog catalog) {
			return new RootMutation(catalog, CollectionUtils.createHashMap(4));
		}

		@Nullable
		@Override
		public WritableEntityStorageContainerAccessor findInFlightAccessor(
			@Nonnull String entityType,
			int entityPrimaryKey
		) {
			return this.executors.get(key(entityType, entityPrimaryKey));
		}

		@Nullable
		@Override
		public ForeignEntityStorageParts getForeignEntityStorageParts(
			@Nonnull String entityType,
			int entityPrimaryKey
		) {
			return this.foreignParts == null
				? null
				: this.foreignParts.computeIfAbsent(
					key(entityType, entityPrimaryKey), it -> new ForeignEntityStorageParts()
				);
		}

		/**
		 * Opens the executor of the root entity, whose changes are written through on commit.
		 *
		 * @param entityType       type of the existing entity
		 * @param entityPrimaryKey primary key of the existing entity
		 * @return the registered executor
		 */
		@Nonnull
		ContainerizedLocalMutationExecutor open(@Nonnull String entityType, int entityPrimaryKey) {
			final ContainerizedLocalMutationExecutor executor =
				openExecutor(this.catalog, collection(this.catalog, entityType), entityPrimaryKey, this);
			this.executors.put(key(entityType, entityPrimaryKey), executor);
			return executor;
		}

		/**
		 * Opens the executor of an entity of a nested (implicit or reflected) mutation, whose changes are trapped.
		 *
		 * @param entityType       type of the existing entity
		 * @param entityPrimaryKey primary key of the existing entity
		 * @return the registered executor
		 */
		@Nonnull
		ContainerizedLocalMutationExecutor openNested(@Nonnull String entityType, int entityPrimaryKey) {
			final ContainerizedLocalMutationExecutor executor = open(entityType, entityPrimaryKey);
			executor.setTrapChanges(true);
			return executor;
		}

		/**
		 * Composes the registry key of an entity.
		 *
		 * @param entityType       the entity type
		 * @param entityPrimaryKey the primary key
		 * @return the key
		 */
		@Nonnull
		private static String key(@Nonnull String entityType, int entityPrimaryKey) {
			return entityType + ':' + entityPrimaryKey;
		}
	}

	/**
	 * Creates the schema and the committed baseline: the first owner references {@link #CATEGORY_PK} with
	 * {@link #COMMITTED_PRIORITY}, the second owner exists without the reference and without any attribute, associated
	 * data or price. In `ALIVE` the baseline is committed by going live; in `WARMING_UP` it is written by an earlier
	 * bulk session.
	 *
	 * @param state target catalog state
	 */
	private void prepare(@Nonnull CatalogState state) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			(Consumer<EvitaSessionContract>) session -> {
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withLocale(Locale.ENGLISH)
					.withAttribute(ATTR_CODE, String.class, thatIs -> thatIs.nullable())
					.withAttribute(ATTR_NAME, String.class, thatIs -> thatIs.localized().nullable())
					.withAssociatedData(ASSOCIATED_DATA_NOTE, String.class, thatIs -> thatIs.nullable())
					.withPriceInCurrency(CURRENCY_CZK)
					.withReferenceToEntity(
						REF_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFilteringAndPartitioning()
							.withAttribute(ATTR_PRIORITY, Integer.class, thatIs -> thatIs.filterable().nullable())
					)
					.updateVia(session);
				session.defineEntitySchema(ENTITY_CATEGORY)
					.withoutGeneratedPrimaryKey()
					.withReflectedReferenceToEntity(
						REF_PRODUCTS, ENTITY_PRODUCT, REF_CATEGORIES,
						whichIs -> whichIs.withCardinality(Cardinality.ZERO_OR_MORE).withAttributesInherited()
					)
					.updateVia(session);

				session.createNewEntity(ENTITY_CATEGORY, CATEGORY_PK).upsertVia(session);
				session.createNewEntity(ENTITY_CATEGORY, SECOND_CATEGORY_PK).upsertVia(session);
				session.createNewEntity(ENTITY_PRODUCT, FIRST_OWNER_PK)
					.setReference(
						REF_CATEGORIES, CATEGORY_PK,
						whichIs -> whichIs.setAttribute(ATTR_PRIORITY, COMMITTED_PRIORITY)
					)
					.upsertVia(session);
				session.createNewEntity(ENTITY_PRODUCT, SECOND_OWNER_PK).upsertVia(session);
				session.createNewEntity(ENTITY_PRODUCT, THIRD_OWNER_PK).upsertVia(session);

				if (state == CatalogState.ALIVE) {
					session.goLiveAndClose();
				}
			}
		);
	}

	/**
	 * Changes the first owner's reference attribute through an ordinary root-level upsert.
	 *
	 * @param session  the open read-write session
	 * @param priority the new attribute value
	 */
	private static void updateFirstOwnerPriority(@Nonnull EvitaSessionContract session, int priority) {
		session.getEntity(ENTITY_PRODUCT, FIRST_OWNER_PK, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setReference(REF_CATEGORIES, CATEGORY_PK, whichIs -> whichIs.setAttribute(ATTR_PRIORITY, priority))
			.upsertVia(session);
	}

	/**
	 * Puts the second owner into the middle of its mutation: opens the storage-part executor its upsert would run in
	 * as the root of `rootMutation`, applies the local mutations that add the reference to the shared partition, and
	 * finishes the local execution phase - the executor is deliberately never committed, exactly as it is not while
	 * the index executor decides a promotion.
	 *
	 * @param session      the open read-write session producing the mutations
	 * @param rootMutation the root mutation the executor belongs to
	 * @return the second owner's executor with its own change applied
	 */
	@Nonnull
	private static ContainerizedLocalMutationExecutor openMutationOfSecondOwner(
		@Nonnull EvitaSessionContract session,
		@Nonnull RootMutation rootMutation
	) {
		final EntityMutation mutation = session.getEntity(ENTITY_PRODUCT, SECOND_OWNER_PK, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setReference(
				REF_CATEGORIES, CATEGORY_PK,
				whichIs -> whichIs.setAttribute(ATTR_PRIORITY, SECOND_OWNER_PRIORITY)
			)
			.toMutation()
			.orElseThrow();
		final ContainerizedLocalMutationExecutor executor = rootMutation.open(ENTITY_PRODUCT, SECOND_OWNER_PK);
		applyLocalPhase(executor, mutation.getLocalMutations());
		return executor;
	}

	/**
	 * Opens a storage-part executor for an existing entity with the arguments `EntityCollection#applyMutations` passes.
	 * The only argument it cannot take from the collection is the write buffer, which is private there; the buffer
	 * built here is of the same kind and only ever consulted by `commit()`, which this test never calls. All reads go
	 * through the collection's own data store reader unless `inFlightAccessorLookup` serves them.
	 *
	 * @param catalog                catalog the session works with
	 * @param collection             collection of the entity
	 * @param entityPrimaryKey       primary key of the entity whose mutation the executor represents
	 * @param inFlightAccessorLookup the registry of the root mutation the executor belongs to
	 * @return a fresh executor
	 */
	@Nonnull
	private static ContainerizedLocalMutationExecutor openExecutor(
		@Nonnull Catalog catalog,
		@Nonnull EntityCollection collection,
		int entityPrimaryKey,
		@Nonnull InFlightEntityAccessorLookup inFlightAccessorLookup
	) {
		final DataStoreMemoryBuffer writeBuffer = catalog.getCatalogState() == CatalogState.WARMING_UP ?
			new WarmUpDataStoreMemoryBuffer(collection.getStoragePartPersistenceService()) :
			new TransactionalDataStoreMemoryBuffer(collection, collection.getStoragePartPersistenceService());
		final AtomicInteger internalPriceIds = new AtomicInteger();
		return new ContainerizedLocalMutationExecutor(
			writeBuffer,
			collection.getDataStoreReader(),
			catalog.getVersion(),
			entityPrimaryKey,
			EntityExistence.MUST_EXIST,
			catalog::getInternalSchema,
			collection::getInternalSchema,
			entityType -> catalog.getCollectionForEntityInternal(entityType)
				.map(EntityCollection::getDataStoreReader)
				.orElse(null),
			inFlightAccessorLookup,
			internalPriceIds::incrementAndGet,
			false
		);
	}

	/**
	 * Puts one root mutation into the state in which its second nested mutation would promote a partition: the second
	 * owner product joins both categories (root executor, local phase finished, so the reference keys carry their
	 * assigned internal primary keys), and each category receives the reflected `products` row in its own nested
	 * executor - changes trapped, local phase finished, nothing committed, exactly as
	 * `LocalMutationExecutorCollector#execute` leaves executors below level 0 until the root's `finish()`.
	 *
	 * The nested mutations are written out in the shape the engine generates for them (an insert of the reflected
	 * reference carrying the source reference's internal primary key) rather than taken from `popImplicitMutations`:
	 * the engine derives them from the product-side reduced indexes the index executor has just updated, and this
	 * storage-only reproduction runs no index executor.
	 *
	 * @param session      the open read-write session
	 * @param rootMutation the root mutation all the executors belong to
	 * @return the nested executors of {@link #CATEGORY_PK} and {@link #SECOND_CATEGORY_PK}, in that order
	 */
	@Nonnull
	private static ContainerizedLocalMutationExecutor[] openNestedReflectedInserts(
		@Nonnull EvitaSessionContract session,
		@Nonnull RootMutation rootMutation
	) {
		final EntityMutation mutation = session
			.getEntity(ENTITY_PRODUCT, SECOND_OWNER_PK, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setReference(REF_CATEGORIES, CATEGORY_PK)
			.setReference(REF_CATEGORIES, SECOND_CATEGORY_PK)
			.toMutation()
			.orElseThrow();
		final ContainerizedLocalMutationExecutor root = rootMutation.open(ENTITY_PRODUCT, SECOND_OWNER_PK);
		applyLocalPhase(root, mutation.getLocalMutations());
		final ReferencesStoragePart rootReferences = root.getReferencesStoragePart(ENTITY_PRODUCT, SECOND_OWNER_PK);

		final int[] categoryPks = {CATEGORY_PK, SECOND_CATEGORY_PK};
		final ContainerizedLocalMutationExecutor[] nested = new ContainerizedLocalMutationExecutor[categoryPks.length];
		for (int i = 0; i < categoryPks.length; i++) {
			final int internalPk = rootReferences.findReference(new ReferenceKey(REF_CATEGORIES, categoryPks[i]))
				.orElseThrow()
				.getReferenceKey()
				.internalPrimaryKey();
			nested[i] = rootMutation.openNested(ENTITY_CATEGORY, categoryPks[i]);
			applyLocalPhase(
				nested[i],
				List.of(new InsertReferenceMutation(new ReferenceKey(REF_PRODUCTS, SECOND_OWNER_PK, internalPk)))
			);
		}
		return nested;
	}

	/**
	 * Runs a real upsert adding both categories to the third product and asserts that both land in the one
	 * category-side partition `products -> third product` - the premise of the promotion scenarios. As a side effect
	 * the implicit mutations it runs leave both categories' references parts trapped in the buffer.
	 *
	 * @param session    the open read-write session
	 * @param categories the category collection
	 */
	private static void assertOneUpsertJoinsBothCategories(
		@Nonnull EvitaSessionContract session,
		@Nonnull EntityCollection categories
	) {
		session.getEntity(ENTITY_PRODUCT, THIRD_OWNER_PK, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setReference(REF_CATEGORIES, CATEGORY_PK)
			.setReference(REF_CATEGORIES, SECOND_CATEGORY_PK)
			.upsertVia(session);
		final EntityIndex partition = categories.getIndexByKeyIfExists(
			new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY, Scope.LIVE,
				new RepresentativeReferenceKey(new ReferenceKey(REF_PRODUCTS, THIRD_OWNER_PK))
			)
		);
		assertNotNull(partition, "One upsert did not create the category-side partition at all");
		assertArrayEquals(
			new int[]{CATEGORY_PK, SECOND_CATEGORY_PK},
			partition.getAllPrimaryKeys().getArray(),
			"One upsert did not add both categories to the same partition"
		);
	}

	/**
	 * Applies a batch of local mutations to an executor and finishes its local execution phase, which is what
	 * `LocalMutationExecutorCollector#execute` does for every batch at every level.
	 *
	 * @param executor       the executor
	 * @param localMutations the mutations to apply
	 */
	private static void applyLocalPhase(
		@Nonnull ContainerizedLocalMutationExecutor executor,
		@Nonnull List<? extends LocalMutation<?, ?>> localMutations
	) {
		for (final LocalMutation<?, ?> localMutation : localMutations) {
			executor.applyMutation(localMutation);
		}
		executor.finishLocalMutationExecutionPhase();
	}

	/**
	 * Tells whether a category's references part holds the reflected `products` row pointing at the second owner.
	 *
	 * @param part the references part of a category
	 * @return true when the row is present
	 */
	private static boolean hasProductsRow(@Nonnull ReferencesStoragePart part) {
		return part.findReference(new ReferenceKey(REF_PRODUCTS, SECOND_OWNER_PK)).isPresent();
	}

	/**
	 * Reads the owner's row straight from the committed storage, bypassing the transactional layer and the trapped
	 * changes - the negative control of the positive assertions about earlier root mutations.
	 *
	 * @param catalog    catalog the session works with
	 * @param collection the product collection
	 * @param ownerPk    primary key of the owner
	 * @return the committed `priority` of the owner's reference to {@link #CATEGORY_PK}, or NULL when absent
	 */
	@Nullable
	private static Integer committedPriorityOf(
		@Nonnull Catalog catalog,
		@Nonnull EntityCollection collection,
		int ownerPk
	) {
		return priorityOf(
			EntitySchemaContext.executeWithSchemaContext(
				collection.getInternalSchema(),
				() -> collection.getStoragePartPersistenceService()
					.getStoragePart(catalog.getVersion(), ownerPk, ReferencesStoragePart.class)
			)
		);
	}

	/**
	 * Extracts the `priority` of the reference to {@link #CATEGORY_PK} from a references part.
	 *
	 * @param part the references part, may be NULL when the owner has none stored
	 * @return the attribute value, or NULL when the part, the reference or the attribute is absent
	 */
	@Nullable
	private static Integer priorityOf(@Nullable ReferencesStoragePart part) {
		if (part == null) {
			return null;
		}
		return part.findReference(new ReferenceKey(REF_CATEGORIES, CATEGORY_PK))
			.map(reference -> (Integer) reference.getAttribute(ATTR_PRIORITY))
			.orElse(null);
	}

	/**
	 * Extracts an attribute value from an attributes part.
	 *
	 * @param part the attributes part
	 * @param key  the attribute key
	 * @return the value, or NULL when the attribute is absent
	 */
	@Nullable
	private static Serializable attributeOf(@Nonnull AttributesStoragePart part, @Nonnull AttributeKey key) {
		final AttributeValue attributeValue = part.findAttribute(key);
		return attributeValue == null ? null : attributeValue.value();
	}

	/**
	 * Extracts the value from an associated data part.
	 *
	 * @param part the associated data part
	 * @return the value, or NULL when the part holds none
	 */
	@Nullable
	private static Serializable associatedDataOf(@Nonnull AssociatedDataStoragePart part) {
		final AssociatedDataValue value = part.getValue();
		return value == null ? null : value.value();
	}

	/**
	 * Returns the catalog the current session works with. Sessions here are opened only after the previous write
	 * became visible, so the live instance is the session's own; were it not, the collection below would carry no
	 * transactional layer and the positive assertions would fail rather than pass.
	 *
	 * @return the live catalog
	 */
	@Nonnull
	private Catalog catalog() {
		return (Catalog) this.evita.getCatalogInstanceOrThrowException(TEST_CATALOG);
	}

	/**
	 * Returns the collection of the given entity type.
	 *
	 * @param catalog    the catalog
	 * @param entityType the entity type
	 * @return the collection
	 */
	@Nonnull
	private static EntityCollection collection(@Nonnull Catalog catalog, @Nonnull String entityType) {
		return catalog.getCollectionForEntityInternal(entityType)
			.orElseThrow(() -> new AssertionError("The test catalog holds no `" + entityType + "` collection"));
	}

	/**
	 * Builds the configuration of the embedded instance this test runs against.
	 *
	 * @return configuration rooted at this test's directories
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

}
