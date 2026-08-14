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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.SessionTraits;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.dataType.Scope;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Currency;
import java.util.stream.IntStream;

import static io.evitadb.api.functional.indexing.IndexingTestSupport.getReferencedEntityIndex;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.priceBetween;
import static io.evitadb.api.query.QueryConstraints.priceContentAll;
import static io.evitadb.api.query.QueryConstraints.priceInCurrency;
import static io.evitadb.api.query.QueryConstraints.priceInPriceLists;
import static io.evitadb.api.query.QueryConstraints.referenceContentAll;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.PRICE;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Locks in the commit-time merge prune ({@code EntityCollection#pruneMergeIndexes}): a transaction rebuilds only the
 * entity indexes it actually dirtied and forwards the rest across the catalog version, instead of merging the whole
 * index forest.
 *
 * Price resolution used to be the load-bearing subtlety here and no longer is. A reduced index's price chain kept
 * a pointer to its scope's GLOBAL entity index, and because the GLOBAL is rebuilt by nearly every transaction, every
 * clean reduced index of that scope had to be re-shelled purely to refresh that pointer. The pointer is gone — the
 * GLOBAL's price index is handed in per operation by a caller already pinned to a catalog version — so a clean reduced
 * index is now carried **wholesale**, and there is no captured GLOBAL left to probe.
 *
 * What remains worth defending is observable rather than structural: an index forwarded across a catalog version must
 * still resolve prices from the CURRENT data. Each test therefore drives a price query through the carried reduced
 * index — `referenceHaving` on the brand the index is keyed by, plus a price constraint, which is what makes that
 * reduced index an eligible target for the price resolution — and, wherever a price changed, also checks that the OLD
 * value is no longer matched. The trunk write path is exercised on top of that, by mutating a price through
 * a previously carried index in a later transaction.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Commit-time merge prune carries clean indexes across the catalog version")
@Tag(INDEXING)
@Tag(REFERENCE)
@Tag(PRICE)
@Tag(TRANSACTION)
class PrunedCommitMergeIndexCarryTest implements EvitaTestSupport {

	private static final String PRICE_LIST_BASIC = "basic";
	private static final Currency CURRENCY_EUR = Currency.getInstance("EUR");
	private static final int TOUCHED_BRAND_PK = 1;
	private static final int CARRIED_BRAND_PK = 2;
	private static final int ADDED_BRAND_PK = 3;
	private static final int TOUCHED_PRODUCT_PK = 1;
	private static final int CARRIED_PRODUCT_PK = 2;

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("PrunedCommitMergeIndexCarry");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		seedAndGoLive();
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("a clean reduced index is carried wholesale even when its scope's GLOBAL is rebuilt")
	void shouldCarryACleanReducedIndexWholesaleWhenItsGlobalIsRebuilt() {
		final EntityIndex globalBefore = globalEntityIndex();
		final EntityIndex carriedBefore = reducedIndex(CARRIED_BRAND_PK);
		final EntityIndex touchedBefore = reducedIndex(TOUCHED_BRAND_PK);

		// mutate ONLY the product referencing TOUCHED_BRAND - that dirties the GLOBAL (its price super index) and the
		// reduced index of TOUCHED_BRAND, while the reduced index of CARRIED_BRAND stays clean and gets pruned out of
		// the merge
		updatePriceOf(TOUCHED_PRODUCT_PK, BigDecimal.valueOf(20));

		final EntityIndex globalAfter = globalEntityIndex();
		final EntityIndex carriedAfter = reducedIndex(CARRIED_BRAND_PK);
		final EntityIndex touchedAfter = reducedIndex(TOUCHED_BRAND_PK);

		assertNotSame(globalBefore, globalAfter, "A dirtied GLOBAL entity index must be rebuilt by the merge!");
		assertNotSame(touchedBefore, touchedAfter, "A dirtied reduced index must be rebuilt by the merge!");
		// this is what distinguishes the PRUNED merge from the full one and keeps this test honest: an unpruned merge
		// hands back a freshly merged instance for EVERY index, while the prune forwards the clean one untouched. That
		// the scope's GLOBAL was rebuilt in the meantime is irrelevant to it - a reduced index no longer holds
		// a pointer into the GLOBAL's price super indexes, so there is nothing about it a version bump could invalidate
		assertSame(
			carriedBefore, carriedAfter,
			"A clean reduced index must be carried wholesale by reference, not re-merged nor re-shelled!"
		);

		// every reduced index - rebuilt or carried - must resolve prices from the CURRENT data
		assertBrandPriceQueryReturns(TOUCHED_BRAND_PK, BigDecimal.valueOf(20), TOUCHED_PRODUCT_PK);
		assertBrandPriceQueryReturns(CARRIED_BRAND_PK, BigDecimal.TEN, CARRIED_PRODUCT_PK);
		// ... and nothing may still be answered from the retired version: the touched product's OLD price is gone
		assertBrandPriceQueryReturns(TOUCHED_BRAND_PK, BigDecimal.TEN);
		// the carried index must not have absorbed the touched product along the way either
		assertBrandPriceQueryReturns(CARRIED_BRAND_PK, BigDecimal.valueOf(20));
	}

	@Test
	@DisplayName("a carried index still serves the write path - a later price mutation through it must commit")
	void shouldKeepACarriedIndexUsableByTheTrunkWritePath() {
		// first transaction prunes the carried brand's reduced index out of the merge and forwards it untouched
		updatePriceOf(TOUCHED_PRODUCT_PK, BigDecimal.valueOf(20));

		// second transaction mutates the price of the product behind the CARRIED index. The trunk write path resolves
		// the price through the GLOBAL's price super index of the version it is pinned to, so a carry that ended up
		// paired with the wrong one throws here rather than at read time.
		assertDoesNotThrow(
			() -> updatePriceOf(CARRIED_PRODUCT_PK, BigDecimal.valueOf(30)),
			"A price mutation routed through a carried reduced index must not hit a retired price super index!"
		);

		assertEquals(
			0, BigDecimal.valueOf(30).compareTo(priceOf(CARRIED_PRODUCT_PK)),
			"The price written through the carried index must be readable afterwards!"
		);
		assertEquals(
			0, BigDecimal.valueOf(20).compareTo(priceOf(TOUCHED_PRODUCT_PK)),
			"The untouched product's price must survive the second transaction!"
		);
		// the twice-carried index must answer with what the second transaction wrote through it, and no longer with
		// the price it held when it was pruned out of the first merge
		assertBrandPriceQueryReturns(CARRIED_BRAND_PK, BigDecimal.valueOf(30), CARRIED_PRODUCT_PK);
		assertBrandPriceQueryReturns(CARRIED_BRAND_PK, BigDecimal.TEN);
		assertBrandPriceQueryReturns(TOUCHED_BRAND_PK, BigDecimal.valueOf(20), TOUCHED_PRODUCT_PK);
	}

	@Test
	@DisplayName("adding an index applies the key delta and still prunes the indexes the transaction never touched")
	void shouldPruneEvenWhenAnIndexIsAdded() {
		final EntityIndex carriedBefore = reducedIndex(CARRIED_BRAND_PK);

		// adding a reference creates a NEW reduced index, so the index MAP itself carries a diff layer - the merge has
		// to apply that key delta AND keep pruning the values the transaction never touched
		addBrandAndReferenceItFromTouchedProduct();

		final EntityIndex addedIndex = reducedIndex(ADDED_BRAND_PK);
		assertNotNull(addedIndex, "The newly referenced brand must have got its own reduced index!");

		// the brand-new index and both pre-existing ones must all resolve prices from the current data
		assertBrandPriceQueryReturns(ADDED_BRAND_PK, BigDecimal.TEN, TOUCHED_PRODUCT_PK);
		assertBrandPriceQueryReturns(CARRIED_BRAND_PK, BigDecimal.TEN, CARRIED_PRODUCT_PK);
		assertBrandPriceQueryReturns(TOUCHED_BRAND_PK, BigDecimal.TEN, TOUCHED_PRODUCT_PK);
		// the discriminator between the pruned merge and the full one: a full merge re-wraps even an untouched index's
		// entity-id bitmap into a fresh TransactionalBitmap, the prune adopts it by reference
		assertSame(
			carriedBefore.getAllPrimaryKeys(), reducedIndex(CARRIED_BRAND_PK).getAllPrimaryKeys(),
			"An index untouched by a transaction that changed the index SET must still be carried by reference!"
		);

		// and the collection must still be writable through both the new and the pre-existing indexes
		assertDoesNotThrow(
			() -> updatePriceOf(CARRIED_PRODUCT_PK, BigDecimal.valueOf(40)),
			"A price mutation must still commit after a transaction that added an index!"
		);
		assertBrandPriceQueryReturns(CARRIED_BRAND_PK, BigDecimal.valueOf(40), CARRIED_PRODUCT_PK);
		assertBrandPriceQueryReturns(CARRIED_BRAND_PK, BigDecimal.TEN);
	}

	@Test
	@DisplayName("removing an index drops it, sweeps its layer and still prunes the untouched indexes")
	void shouldPruneEvenWhenAnIndexIsRemoved() {
		// first transaction gives the third brand its own reduced index...
		addBrandAndReferenceItFromTouchedProduct();
		assertNotNull(reducedIndex(ADDED_BRAND_PK), "The fixture must have created the reduced index to be removed!");
		final EntityIndex carriedBefore = reducedIndex(CARRIED_BRAND_PK);

		// ... the second drops it again by removing the only reference that kept it alive, so the index map's diff
		// layer carries a REMOVED key this time
		try (final EvitaSessionContract session = writeSession()) {
			session.getEntity(Entities.PRODUCT, TOUCHED_PRODUCT_PK, referenceContentAll(), priceContentAll())
				.orElseThrow()
				.openForWrite()
				.removeReference(Entities.BRAND, ADDED_BRAND_PK)
				.upsertVia(session);
		}

		assertNull(
			reducedIndex(ADDED_BRAND_PK),
			"The reduced index emptied by the reference removal must be dropped from the collection!"
		);
		assertSame(
			carriedBefore.getAllPrimaryKeys(), reducedIndex(CARRIED_BRAND_PK).getAllPrimaryKeys(),
			"An index untouched by a transaction that REMOVED another index must still be carried by reference!"
		);
		// both surviving indexes must still resolve prices from the current data
		assertBrandPriceQueryReturns(CARRIED_BRAND_PK, BigDecimal.TEN, CARRIED_PRODUCT_PK);
		assertBrandPriceQueryReturns(TOUCHED_BRAND_PK, BigDecimal.TEN, TOUCHED_PRODUCT_PK);

		assertDoesNotThrow(
			() -> updatePriceOf(CARRIED_PRODUCT_PK, BigDecimal.valueOf(40)),
			"A price mutation must still commit after a transaction that removed an index!"
		);
		assertBrandPriceQueryReturns(CARRIED_BRAND_PK, BigDecimal.valueOf(40), CARRIED_PRODUCT_PK);
		assertBrandPriceQueryReturns(CARRIED_BRAND_PK, BigDecimal.TEN);
	}

	@Test
	@DisplayName("a commit that adds one index and removes another applies both halves of the key delta")
	void shouldPruneWhenATransactionBothAddsAndRemovesAnIndexInOneCommit() {
		final EntityIndex carriedBefore = reducedIndex(CARRIED_BRAND_PK);
		assertNotNull(
			reducedIndex(TOUCHED_BRAND_PK),
			"The fixture must have created the reduced index that this transaction is about to drop!"
		);

		// a single commit both ADDS a brand new reduced index (the newly referenced brand) and REMOVES an
		// existing one (the touched product's only reference to it is dropped) - modifiedKeys and removedKeys
		// of the index map's diff layer are both non-empty in this very same merge. The removal is issued
		// before the addition: setting a reference of this name first would initialize the entity builder's
		// reference bundle, tripping an assertion when a later removal targets a different, pre-existing key
		// of that same name
		try (final EvitaSessionContract session = writeSession()) {
			session.createNewEntity(Entities.BRAND, ADDED_BRAND_PK).upsertVia(session);
			session.getEntity(Entities.PRODUCT, TOUCHED_PRODUCT_PK, referenceContentAll(), priceContentAll())
				.orElseThrow()
				.openForWrite()
				.removeReference(Entities.BRAND, TOUCHED_BRAND_PK)
				.setReference(Entities.BRAND, ADDED_BRAND_PK)
				.upsertVia(session);
		}

		final EntityIndex addedIndex = reducedIndex(ADDED_BRAND_PK);
		assertNotNull(addedIndex, "The newly referenced brand must have got its own reduced index!");
		// the index the very same commit created must resolve the moved product's price
		assertBrandPriceQueryReturns(ADDED_BRAND_PK, BigDecimal.TEN, TOUCHED_PRODUCT_PK);

		assertNull(
			reducedIndex(TOUCHED_BRAND_PK),
			"The reduced index emptied by the reference removal must be dropped from the collection!"
		);
		assertSame(
			carriedBefore.getAllPrimaryKeys(), reducedIndex(CARRIED_BRAND_PK).getAllPrimaryKeys(),
			"An index untouched by an add-and-remove commit must still be carried by reference!"
		);

		assertDoesNotThrow(
			() -> updatePriceOf(CARRIED_PRODUCT_PK, BigDecimal.valueOf(40)),
			"A price mutation must still commit after a transaction that both added and removed an index!"
		);
		assertBrandPriceQueryReturns(CARRIED_BRAND_PK, BigDecimal.valueOf(40), CARRIED_PRODUCT_PK);
		assertBrandPriceQueryReturns(CARRIED_BRAND_PK, BigDecimal.TEN);
	}

	@Test
	@DisplayName("both keyings of the index forest stay in step across a commit that adds, removes and mutates")
	void shouldKeepBothIndexKeyingsInStepAcrossACommit() {
		final EntityIndex droppedBefore = reducedIndex(TOUCHED_BRAND_PK);
		assertNotNull(droppedBefore, "The fixture must have created the reduced index that this transaction drops!");
		final int droppedPk = droppedBefore.getPrimaryKey();

		// a single commit that carries every class of change the delta can hold: it CREATES a reduced index, REMOVES
		// another and mutates the GLOBAL in place, while the third reduced index stays clean and is carried untouched
		try (final EvitaSessionContract session = writeSession()) {
			session.createNewEntity(Entities.BRAND, ADDED_BRAND_PK).upsertVia(session);
			session.getEntity(Entities.PRODUCT, TOUCHED_PRODUCT_PK, referenceContentAll(), priceContentAll())
				.orElseThrow()
				.openForWrite()
				.removeReference(Entities.BRAND, TOUCHED_BRAND_PK)
				.setReference(Entities.BRAND, ADDED_BRAND_PK)
				.setPrice(
					1000 + TOUCHED_PRODUCT_PK, PRICE_LIST_BASIC, CURRENCY_EUR,
					BigDecimal.valueOf(20), BigDecimal.ZERO, BigDecimal.valueOf(20), true
				)
				.upsertVia(session);
		}

		// the collection keeps two views of one index forest - by index key and by storage primary key - and the commit
		// derives BOTH from the same transaction delta rather than rebuilding one from the other. A divergence is
		// therefore a derivation bug, and it would otherwise stay invisible until some later transaction resolved an
		// index by storage PK and got a retired instance, or none at all
		assertSameUnderBothKeyings(globalEntityIndex());
		assertSameUnderBothKeyings(reducedIndex(ADDED_BRAND_PK));
		assertSameUnderBothKeyings(reducedIndex(CARRIED_BRAND_PK));

		// ... and a dropped index must leave BOTH views, not just the one keyed by index key
		assertNull(reducedIndex(TOUCHED_BRAND_PK), "The emptied reduced index must be dropped from the collection!");
		assertNull(
			productCollection().getIndexByPrimaryKeyIfExists(droppedPk),
			"A dropped index must leave the by-primary-key view as well, or it stays reachable by storage PK!"
		);
	}

	@Test
	@DisplayName("an index re-created under the same key in one commit must not leave its retired primary key behind")
	void shouldDropTheRetiredPrimaryKeyWhenAnIndexIsReCreatedUnderTheSameKey() {
		final EntityIndexKey referencedTypeKey = new EntityIndexKey(
			EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, Entities.BRAND
		);
		final EntityIndex typeIndexBefore = productCollection().getIndexByKeyIfExists(referencedTypeKey);
		assertNotNull(typeIndexBefore, "The fixture must have created the referenced-type index for brands!");
		final int retiredPk = typeIndexBefore.getPrimaryKey();

		// ONE commit that first drops EVERY brand reference - which empties and removes the referenced-type index - and
		// then re-adds one, creating a FRESH index under the very SAME index key but with a NEW storage primary key.
		// The index map's key delta records that as a MODIFIED key rather than a removed one, because the index KEY
		// never changed - only the instance behind it, and with it the primary key the by-PK view is keyed on
		try (final EvitaSessionContract session = writeSession()) {
			session.getEntity(Entities.PRODUCT, TOUCHED_PRODUCT_PK, referenceContentAll(), priceContentAll())
				.orElseThrow()
				.openForWrite()
				.removeReference(Entities.BRAND, TOUCHED_BRAND_PK)
				.upsertVia(session);
			session.getEntity(Entities.PRODUCT, CARRIED_PRODUCT_PK, referenceContentAll(), priceContentAll())
				.orElseThrow()
				.openForWrite()
				.removeReference(Entities.BRAND, CARRIED_BRAND_PK)
				.upsertVia(session);
			session.getEntity(Entities.PRODUCT, CARRIED_PRODUCT_PK, referenceContentAll(), priceContentAll())
				.orElseThrow()
				.openForWrite()
				.setReference(Entities.BRAND, CARRIED_BRAND_PK)
				.upsertVia(session);
		}

		final EntityIndex typeIndexAfter = productCollection().getIndexByKeyIfExists(referencedTypeKey);
		assertNotNull(typeIndexAfter, "Re-adding a brand reference must have re-created the referenced-type index!");
		// guards the scenario itself: if the index were merely mutated rather than dropped and re-created, this test
		// would silently stop covering the case it exists for
		assertNotEquals(
			retiredPk, typeIndexAfter.getPrimaryKey(),
			"The re-created referenced-type index must have been assigned a NEW storage primary key!"
		);

		assertSameUnderBothKeyings(typeIndexAfter);
		// the actual defect: the by-primary-key view is keyed on something the index KEY delta cannot express, so a
		// derivation driven by that delta alone leaves the retired primary key pointing at an index that no longer
		// exists. It stays invisible until the collection header - which lists exactly these keys - is written and the
		// catalog is loaded back, at which point the retired key has no storage part to read and the load fails
		assertNull(
			productCollection().getIndexByPrimaryKeyIfExists(retiredPk),
			"The retired primary key of a re-created index must not survive in the by-primary-key view!"
		);
	}

	/**
	 * Asserts that the given index is reachable under both keyings the live collection maintains, and that each resolves
	 * to the very same instance.
	 *
	 * @param index the index expected to have survived the commit
	 */
	private void assertSameUnderBothKeyings(@Nullable EntityIndex index) {
		assertNotNull(index, "An index expected to survive the commit is missing!");
		final EntityCollection collection = productCollection();
		assertSame(
			index, collection.getIndexByKeyIfExists(index.getIndexKey()),
			"Index `" + index.getIndexKey() + "` must resolve to itself under its own index key!"
		);
		assertSame(
			index, collection.getIndexByPrimaryKeyIfExists(index.getPrimaryKey()),
			"Index `" + index.getIndexKey() + "` must resolve to the very same instance under its storage primary key `" +
				index.getPrimaryKey() + "`!"
		);
	}

	/**
	 * Builds the fixture: two brands, each referenced by one priced product, then switches the catalog to the ALIVE
	 * state so every subsequent change goes through the transactional trunk merge.
	 */
	private void seedAndGoLive() {
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.BRAND)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withPrice()
					.withReferenceToEntity(
						Entities.BRAND,
						Entities.BRAND,
						Cardinality.ZERO_OR_MORE,
						ReferenceSchemaEditor::indexedForFilteringAndPartitioning
					)
					.updateVia(session);

				session.upsertEntity(session.createNewEntity(Entities.BRAND, TOUCHED_BRAND_PK));
				session.upsertEntity(session.createNewEntity(Entities.BRAND, CARRIED_BRAND_PK));
				upsertProduct(session, TOUCHED_PRODUCT_PK, TOUCHED_BRAND_PK, BigDecimal.TEN);
				upsertProduct(session, CARRIED_PRODUCT_PK, CARRIED_BRAND_PK, BigDecimal.TEN);
			}
		);
		this.evita.updateCatalog(TEST_CATALOG, EvitaSessionContract::goLiveAndClose);
	}

	/**
	 * Creates a priced product referencing the given brand.
	 *
	 * @param session  the session to write through
	 * @param pk       the product primary key
	 * @param brandPk  the primary key of the brand to reference
	 * @param price    the price to set in {@link #PRICE_LIST_BASIC} / {@link #CURRENCY_EUR}
	 */
	private static void upsertProduct(
		@Nonnull EvitaSessionContract session,
		int pk,
		int brandPk,
		@Nonnull BigDecimal price
	) {
		session.upsertEntity(
			session.createNewEntity(Entities.PRODUCT, pk)
				.setPriceInnerRecordHandling(PriceInnerRecordHandling.NONE)
				.setPrice(1000 + pk, PRICE_LIST_BASIC, CURRENCY_EUR, price, BigDecimal.ZERO, price, true)
				.setReference(Entities.BRAND, brandPk)
		);
	}

	/**
	 * Creates {@link #ADDED_BRAND_PK} and references it from {@link #TOUCHED_PRODUCT_PK} in a single
	 * transaction. This is what gives the touched product's reduced entity index MAP a newly ADDED key,
	 * forcing the merge to apply that key delta while still pruning the indexes the transaction never touched.
	 */
	private void addBrandAndReferenceItFromTouchedProduct() {
		try (final EvitaSessionContract session = writeSession()) {
			session.createNewEntity(Entities.BRAND, ADDED_BRAND_PK).upsertVia(session);
			session.getEntity(Entities.PRODUCT, TOUCHED_PRODUCT_PK, referenceContentAll(), priceContentAll())
				.orElseThrow()
				.openForWrite()
				.setReference(Entities.BRAND, ADDED_BRAND_PK)
				.upsertVia(session);
		}
	}

	/**
	 * Rewrites a product's price inside a transaction that only completes once the change is visible in the live view,
	 * so the assertions that follow observe the merged catalog version.
	 *
	 * @param productPk the product whose price should change
	 * @param newPrice  the new price
	 */
	private void updatePriceOf(int productPk, @Nonnull BigDecimal newPrice) {
		try (final EvitaSessionContract session = writeSession()) {
			session.getEntity(Entities.PRODUCT, productPk, priceContentAll())
				.orElseThrow()
				.openForWrite()
				.setPrice(1000 + productPk, PRICE_LIST_BASIC, CURRENCY_EUR, newPrice, BigDecimal.ZERO, newPrice, true)
				.upsertVia(session);
		}
	}

	/**
	 * Opens a read-write session whose commit blocks until the change is visible in the live view.
	 *
	 * @return the write session
	 */
	@Nonnull
	private EvitaSessionContract writeSession() {
		return this.evita.createSession(
			new SessionTraits(TEST_CATALOG, CommitBehavior.WAIT_FOR_CHANGES_VISIBLE, SessionFlags.READ_WRITE)
		);
	}

	/**
	 * Reads back a product's selling price from the live view.
	 *
	 * @param productPk the product to read
	 * @return the price for sale amount
	 */
	@Nonnull
	private BigDecimal priceOf(int productPk) {
		try (final EvitaSessionContract session = this.evita.createReadOnlySession(TEST_CATALOG)) {
			final SealedEntity entity = session.getEntity(
				Entities.PRODUCT, productPk, priceContentAll()
			).orElseThrow();
			return entity.getPrices()
				.stream()
				.findFirst()
				.orElseThrow()
				.priceWithTax();
		}
	}

	/**
	 * Asserts that a price query routed through the REFERENCED_ENTITY reduced index of the given brand returns exactly
	 * `expectedPks` at the given price for sale.
	 *
	 * The `referenceHaving` half is load-bearing, not decoration: it is what makes that brand's reduced index an
	 * eligible target for the price constraint, so the price resolution under test is the one performed on the very
	 * index the merge carried across the catalog version. This replaces the former pair of wiring probes, which re-ran
	 * the production wiring check to observe which GLOBAL a reduced index's price chain had captured. There is no
	 * captured GLOBAL to observe any more - the GLOBAL's price index is handed in per operation - so what mattered
	 * about the wiring is asserted directly instead: that an index forwarded across a version bump still resolves
	 * prices from the CURRENT data. That is the stronger claim anyway, because it exercises the resolution the query
	 * path actually performs.
	 *
	 * @param brandPk     the brand whose reduced index the query must go through
	 * @param price       the price for sale the products are expected to be found at
	 * @param expectedPks the product primary keys expected back, in any order - none means the query must find nothing
	 */
	private void assertBrandPriceQueryReturns(int brandPk, @Nonnull BigDecimal price, @Nonnull int... expectedPks) {
		try (final EvitaSessionContract session = this.evita.createReadOnlySession(TEST_CATALOG)) {
			final Integer[] found = session.queryList(
					Query.query(
						collection(Entities.PRODUCT),
						filterBy(
							referenceHaving(Entities.BRAND, entityPrimaryKeyInSet(brandPk)),
							priceInPriceLists(PRICE_LIST_BASIC),
							priceInCurrency(CURRENCY_EUR),
							priceBetween(price, price)
						)
					),
					EntityReference.class
				)
				.stream()
				.map(EntityReference::getPrimaryKey)
				.sorted()
				.toArray(Integer[]::new);
			final Integer[] expected = IntStream.of(expectedPks).sorted().boxed().toArray(Integer[]::new);
			assertArrayEquals(
				expected, found,
				"Price query through brand `" + brandPk + "`'s reduced index at " + price + " must return exactly " +
					Arrays.toString(expected) + " but returned " + Arrays.toString(found) + "!"
			);
		}
	}

	/**
	 * Reaches into the live {@link Entities#PRODUCT} collection and returns the REFERENCED_ENTITY reduced index of the
	 * given brand.
	 *
	 * @param brandPk the primary key of the referenced brand
	 * @return the reduced entity index, or `null` if it has not been created yet
	 */
	private EntityIndex reducedIndex(int brandPk) {
		return getReferencedEntityIndex(productCollection(), Entities.BRAND, brandPk);
	}

	/**
	 * Reaches into the live {@link Entities#PRODUCT} collection and returns its GLOBAL entity index - the owner of the
	 * super price indexes a reduced index's price records are resolved against. It is read here only to observe that
	 * a dirtied GLOBAL really is rebuilt by the merge, which is what makes the carry of a clean reduced index across
	 * that rebuild worth asserting at all.
	 *
	 * @return the GLOBAL entity index
	 */
	@Nonnull
	private EntityIndex globalEntityIndex() {
		final EntityIndex globalIndex = productCollection().getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.GLOBAL)
		);
		assertNotNull(globalIndex, "The GLOBAL entity index must exist!");
		return globalIndex;
	}

	/**
	 * Resolves the CURRENT live {@link Entities#PRODUCT} collection - it is a different instance after every committed
	 * catalog version, so it must never be cached across a transaction.
	 *
	 * @return the product entity collection
	 */
	@Nonnull
	private EntityCollection productCollection() {
		final Catalog catalog = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		return (EntityCollection) catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();
	}

}
