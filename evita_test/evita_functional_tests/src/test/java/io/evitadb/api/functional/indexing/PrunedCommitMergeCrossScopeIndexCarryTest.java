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
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.AbstractReducedEntityIndex;
import io.evitadb.index.EntityIndex;
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

import static io.evitadb.api.functional.indexing.IndexingTestSupport.getGlobalIndex;
import static io.evitadb.api.functional.indexing.IndexingTestSupport.getReferencedEntityIndex;
import static io.evitadb.api.query.QueryConstraints.priceContentAll;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.PRICE;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Locks in the commit-time merge prune (`EntityCollection#pruneMergeIndexes`) across **multiple
 * scopes**: the merge partitions the GLOBAL entity indexes and the re-shell decision per
 * {@link Scope}, so a transaction that dirties only the `LIVE` scope must leave every other
 * scope's GLOBAL and reduced indexes completely untouched.
 *
 * The fixture indexes both price and the BRAND reference in `LIVE` and `ARCHIVED`, then archives
 * one product so the `ARCHIVED` scope gets its own GLOBAL and reduced index. A later transaction
 * that only mutates the `LIVE` product's price must carry the `ARCHIVED` scope's indexes across
 * the commit by reference, without rebuilding or re-shelling either of them.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Commit-time merge prune carries an untouched scope's indexes across another commit")
@Tag(INDEXING)
@Tag(REFERENCE)
@Tag(PRICE)
@Tag(TRANSACTION)
class PrunedCommitMergeCrossScopeIndexCarryTest implements EvitaTestSupport {

	private static final String PRICE_LIST_BASIC = "basic";
	private static final Currency CURRENCY_EUR = Currency.getInstance("EUR");
	private static final int LIVE_BRAND_PK = 1;
	private static final int ARCHIVED_BRAND_PK = 2;
	private static final int LIVE_PRODUCT_PK = 1;
	private static final int ARCHIVED_PRODUCT_PK = 2;

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("PrunedCommitMergeCrossScopeIndexCarry");
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
	@DisplayName("mutating the LIVE product must not rebuild or re-shell the ARCHIVED scope's indexes")
	void shouldCarryTheArchivedScopeReducedIndexUntouchedWhenOnlyTheLiveScopeIsMutated() {
		final EntityIndex archivedGlobalBefore = archivedGlobalIndex();
		final EntityIndex archivedReducedBefore = archivedReducedIndex();
		final EntityIndex liveGlobalBefore = liveGlobalIndex();

		// mutate ONLY the LIVE product's price - the ARCHIVED scope's GLOBAL and reduced index must
		// stay untouched
		updatePriceOf(LIVE_PRODUCT_PK, BigDecimal.valueOf(20));

		final EntityIndex archivedGlobalAfter = archivedGlobalIndex();
		final EntityIndex archivedReducedAfter = archivedReducedIndex();
		final EntityIndex liveGlobalAfter = liveGlobalIndex();

		assertSame(
			archivedGlobalBefore, archivedGlobalAfter,
			"A scope the transaction never touched must keep the very same GLOBAL entity index instance!"
		);
		// the discriminator that proves the ARCHIVED scope was never marked as rebuilt: a re-shell
		// would produce a new reduced index instance even though its logical content never changed
		assertSame(
			archivedReducedBefore, archivedReducedAfter,
			"An untouched scope's reduced index must be carried by reference, never re-shelled!"
		);
		assertNotSame(
			liveGlobalBefore, liveGlobalAfter,
			"The dirtied LIVE scope's GLOBAL entity index must be rebuilt by the merge!"
		);

		// the carried ARCHIVED reduced index must still resolve prices through the ARCHIVED GLOBAL...
		assertWiredTo(archivedReducedAfter, archivedGlobalAfter);
		// ...and never through the LIVE scope's (rebuilt) GLOBAL
		assertNotWiredTo(archivedReducedAfter, liveGlobalAfter);
	}

	/**
	 * Builds the fixture: two brands, each referenced by one priced product, with price and the
	 * BRAND reference indexed in both `LIVE` and `ARCHIVED`. The second product is archived while
	 * the catalog is still in the warm-up state, giving `ARCHIVED` its own GLOBAL and reduced index,
	 * before the catalog is switched to the ALIVE state so every subsequent change goes through the
	 * transactional trunk merge.
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
					.withPriceInCurrencyIndexedInScope(2, new Currency[]{CURRENCY_EUR}, Scope.LIVE, Scope.ARCHIVED)
					.withReferenceToEntity(
						Entities.BRAND,
						Entities.BRAND,
						Cardinality.ZERO_OR_MORE,
						thatIs -> thatIs.indexedForFilteringAndPartitioningInScope(Scope.LIVE, Scope.ARCHIVED)
					)
					.updateVia(session);

				session.upsertEntity(session.createNewEntity(Entities.BRAND, LIVE_BRAND_PK));
				session.upsertEntity(session.createNewEntity(Entities.BRAND, ARCHIVED_BRAND_PK));
				upsertProduct(session, LIVE_PRODUCT_PK, LIVE_BRAND_PK, BigDecimal.TEN);
				upsertProduct(session, ARCHIVED_PRODUCT_PK, ARCHIVED_BRAND_PK, BigDecimal.TEN);
			}
		);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.archiveEntity(Entities.PRODUCT, ARCHIVED_PRODUCT_PK);
			}
		);
		this.evita.updateCatalog(TEST_CATALOG, EvitaSessionContract::goLiveAndClose);
	}

	/**
	 * Creates a priced product referencing the given brand.
	 *
	 * @param session the session to write through
	 * @param pk      the product primary key
	 * @param brandPk the primary key of the brand to reference
	 * @param price   the price to set in {@link #PRICE_LIST_BASIC} / {@link #CURRENCY_EUR}
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
	 * Rewrites a product's price inside a transaction that only completes once the change is
	 * visible in the live view, so the assertions that follow observe the merged catalog version.
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
	 * Asserts that the reduced index's price chain resolves through the given GLOBAL entity index.
	 * Re-running the production wiring check is the only way to observe the captured GLOBAL: on an
	 * already-wired chain it verifies identity instead of re-wiring.
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
	 * Asserts that the reduced index's price chain is NOT wired to the given (foreign) GLOBAL
	 * entity index - the negative half of {@link #assertWiredTo}, without which a wiring check that
	 * passes for every GLOBAL would look like a green test.
	 *
	 * @param index       the reduced entity index to check
	 * @param globalIndex the GLOBAL entity index it must NOT be wired to
	 */
	private static void assertNotWiredTo(
		@Nonnull EntityIndex index, @Nonnull EntityIndex globalIndex
	) {
		assertThrows(
			GenericEvitaInternalError.class,
			() -> ((AbstractReducedEntityIndex) index).getPriceIndex()
				.wireOrVerifySuperIndexes((GlobalEntityIndex) globalIndex),
			"A reduced index wired to one scope's GLOBAL must reject a different scope's GLOBAL!"
		);
	}

	/**
	 * Reaches into the live {@link Entities#PRODUCT} collection and returns its `LIVE` scope
	 * GLOBAL entity index.
	 *
	 * @return the `LIVE` GLOBAL entity index
	 */
	@Nonnull
	private EntityIndex liveGlobalIndex() {
		final EntityIndex globalIndex = getGlobalIndex(productCollection(), Scope.LIVE);
		assertNotNull(globalIndex, "The LIVE GLOBAL entity index must exist!");
		return globalIndex;
	}

	/**
	 * Reaches into the live {@link Entities#PRODUCT} collection and returns its `ARCHIVED` scope
	 * GLOBAL entity index.
	 *
	 * @return the `ARCHIVED` GLOBAL entity index
	 */
	@Nonnull
	private EntityIndex archivedGlobalIndex() {
		final EntityIndex globalIndex = getGlobalIndex(productCollection(), Scope.ARCHIVED);
		assertNotNull(globalIndex, "The ARCHIVED GLOBAL entity index must exist!");
		return globalIndex;
	}

	/**
	 * Reaches into the live {@link Entities#PRODUCT} collection and returns the `ARCHIVED` scope
	 * REFERENCED_ENTITY reduced index of the archived brand.
	 *
	 * @return the `ARCHIVED` scope reduced entity index
	 */
	@Nonnull
	private EntityIndex archivedReducedIndex() {
		final EntityIndex reducedIndex = getReferencedEntityIndex(
			productCollection(), Scope.ARCHIVED, Entities.BRAND, ARCHIVED_BRAND_PK
		);
		assertNotNull(reducedIndex, "The ARCHIVED scope's reduced index for the brand must exist!");
		return reducedIndex;
	}

	/**
	 * Resolves the CURRENT live {@link Entities#PRODUCT} collection - it is a different instance
	 * after every committed catalog version, so it must never be cached across a transaction.
	 *
	 * @return the product entity collection
	 */
	@Nonnull
	private EntityCollection productCollection() {
		final Catalog catalog = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		return (EntityCollection) catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();
	}

}
