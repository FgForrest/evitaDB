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
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.AbstractReducedEntityIndex;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Currency;

import static io.evitadb.api.query.QueryConstraints.priceContentAll;
import static io.evitadb.api.query.QueryConstraints.referenceContentAll;
import static io.evitadb.api.functional.indexing.IndexingTestSupport.getReferencedEntityIndex;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.PRICE;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Locks in the commit-time merge prune ({@code EntityCollection#pruneMergeIndexes}): a transaction rebuilds only the
 * entity indexes it actually dirtied and forwards the rest across the catalog version, instead of merging the whole
 * index forest.
 *
 * The load-bearing subtlety is price wiring. A reduced index's price chain captures its scope's GLOBAL entity index
 * directly (through a `SuperIndexResolver`), and the GLOBAL is rebuilt by nearly every transaction — so a clean reduced
 * index cannot simply be shared wholesale, it must be re-shelled onto the CURRENT version's GLOBAL. Getting that wrong
 * is not a read-path curiosity: the trunk write path consults the reduced index's super wiring on the next price
 * mutation, so a stale carry surfaces as `Price id ... not found in the price super index` on a LATER transaction.
 * These tests therefore assert the wiring identity directly AND drive a follow-up price mutation through the carried
 * index.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Commit-time merge prune carries clean indexes and re-wires them to the current GLOBAL")
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
	@DisplayName("a clean reduced index is re-shelled onto the rebuilt GLOBAL, never left pointing at the retired one")
	void shouldReshellACleanReducedIndexOntoTheRebuiltGlobal() {
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
		// the clean index cannot be shared wholesale here - its price chain captured the now-retired GLOBAL
		assertNotSame(
			carriedBefore, carriedAfter,
			"A clean reduced index whose GLOBAL was rebuilt must be re-shelled, not shared wholesale!"
		);
		assertEquals(
			carriedBefore.getPrimaryKey(), carriedAfter.getPrimaryKey(),
			"The re-shelled index must remain the very same logical index!"
		);
		// this is what distinguishes the PRUNED merge from the full one and keeps this test honest: the re-shell
		// adopts the entity-id bitmap by reference, whereas the unpruned merge re-wraps it into a fresh
		// TransactionalBitmap. Without this assertion every check here would pass with the prune disabled.
		assertSame(
			carriedBefore.getAllPrimaryKeys(), carriedAfter.getAllPrimaryKeys(),
			"The re-shelled index must adopt the clean entity-id bitmap by reference, not re-copy it!"
		);

		// every reduced index - rebuilt or re-shelled - must resolve prices through the CURRENT version's GLOBAL
		assertWiredTo(carriedAfter, globalAfter);
		assertWiredTo(touchedAfter, globalAfter);
		assertNotWiredTo(carriedAfter, globalBefore);
	}

	@Test
	@DisplayName("a carried index still serves the write path - a later price mutation through it must commit")
	void shouldKeepACarriedIndexUsableByTheTrunkWritePath() {
		// first transaction prunes the carried brand's reduced index out of the merge and re-shells it
		updatePriceOf(TOUCHED_PRODUCT_PK, BigDecimal.valueOf(20));

		// second transaction mutates the price of the product behind the CARRIED index. The trunk write path resolves
		// the price through that index's super wiring, so a stale carry throws here rather than at read time.
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
		assertWiredTo(reducedIndex(CARRIED_BRAND_PK), globalEntityIndex());
		assertWiredTo(reducedIndex(TOUCHED_BRAND_PK), globalEntityIndex());
	}

	@Test
	@DisplayName("adding an index applies the key delta and still prunes the indexes the transaction never touched")
	void shouldPruneEvenWhenAnIndexIsAdded() {
		final EntityIndex carriedBefore = reducedIndex(CARRIED_BRAND_PK);

		// adding a reference creates a NEW reduced index, so the index MAP itself carries a diff layer - the merge has
		// to apply that key delta AND keep pruning the values the transaction never touched
		addBrandAndReferenceItFromTouchedProduct();

		final EntityIndex globalAfter = globalEntityIndex();
		final EntityIndex addedIndex = reducedIndex(ADDED_BRAND_PK);
		assertNotNull(addedIndex, "The newly referenced brand must have got its own reduced index!");

		assertWiredTo(addedIndex, globalAfter);
		assertWiredTo(reducedIndex(CARRIED_BRAND_PK), globalAfter);
		assertWiredTo(reducedIndex(TOUCHED_BRAND_PK), globalAfter);
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
		assertWiredTo(reducedIndex(CARRIED_BRAND_PK), globalEntityIndex());
		assertWiredTo(reducedIndex(TOUCHED_BRAND_PK), globalEntityIndex());

		assertDoesNotThrow(
			() -> updatePriceOf(CARRIED_PRODUCT_PK, BigDecimal.valueOf(40)),
			"A price mutation must still commit after a transaction that removed an index!"
		);
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

		final EntityIndex globalAfter = globalEntityIndex();
		final EntityIndex addedIndex = reducedIndex(ADDED_BRAND_PK);
		assertNotNull(addedIndex, "The newly referenced brand must have got its own reduced index!");
		assertWiredTo(addedIndex, globalAfter);

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
	 * Asserts that the reduced index's price chain resolves through the given GLOBAL entity index. Re-running the
	 * production wiring check is the only way to observe the captured GLOBAL: on an already-wired chain it verifies
	 * identity instead of re-wiring.
	 *
	 * @param index       the reduced entity index to check
	 * @param globalIndex the GLOBAL entity index it is expected to be wired to
	 */
	private static void assertWiredTo(@Nonnull EntityIndex index, @Nonnull EntityIndex globalIndex) {
		assertDoesNotThrow(
			() -> ((AbstractReducedEntityIndex) index).getPriceIndex()
				.wireOrVerifySuperIndexes((GlobalEntityIndex) globalIndex),
			"The reduced index's price chain must be wired to the expected GLOBAL entity index!"
		);
	}

	/**
	 * Asserts that the reduced index's price chain is NOT wired to the given (retired) GLOBAL entity index - the
	 * negative half of {@link #assertWiredTo}, without which a wiring check that passes for every GLOBAL would look
	 * like a green test.
	 *
	 * @param index       the reduced entity index to check
	 * @param globalIndex the GLOBAL entity index it must NOT be wired to
	 */
	private static void assertNotWiredTo(@Nonnull EntityIndex index, @Nonnull EntityIndex globalIndex) {
		assertThrows(
			GenericEvitaInternalError.class,
			() -> ((AbstractReducedEntityIndex) index).getPriceIndex()
				.wireOrVerifySuperIndexes((GlobalEntityIndex) globalIndex),
			"A carried reduced index still wired to a retired GLOBAL entity index must be rejected!"
		);
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
	 * Reaches into the live {@link Entities#PRODUCT} collection and returns its GLOBAL entity index (the owner of the
	 * super price indexes that back every reduced index's price ref chain).
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
