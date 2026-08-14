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

package io.evitadb.api.functional.storage;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.SessionTraits;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.price.PriceListAndCurrencyPriceSuperIndex;
import io.evitadb.index.price.model.PriceIndexKey;
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
import java.util.List;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.priceBetween;
import static io.evitadb.api.query.QueryConstraints.priceInCurrency;
import static io.evitadb.api.query.QueryConstraints.priceInPriceLists;
import static io.evitadb.test.TestTags.PRICE;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-persistence end-to-end round-trip for the granular (PAGED) super-price-index storage.
 *
 * More than 64 distinct indexed prices in one (price list, currency) combination force the backing element-keyed
 * price-record tree of the {@link PriceListAndCurrencyPriceSuperIndex} root to become internal, which switches its
 * on-disk representation from the legacy SINGLE monolithic part to the PAGED leaf-page representation. This test
 * exercises the whole stack through the actual catalog persistence service / OffsetIndex:
 *
 * 1. a warm-up bulk insert of more than 64 distinctly-priced entities (flushed by `goLiveAndClose`), then
 * 2. a transactional commit adding more priced entities (driving the merge + page-publish handshake durably), then
 * 3. a close + reopen of the whole Evita instance, asserting the reloaded super price index is result-identical to the
 *    pre-restart one (every distinct selling price still resolves to its exact primary key) and is still PAGED, then
 * 4. a hard shrink (delete the warm-up entities) so the tree merges leaves and collapses back to the inline SINGLE
 *    shape, driving the freed-leaf-page removal path through the real persistence drain, then a final reopen.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Paged super price index persistence round-trip")
@Tag(STORAGE)
@Tag(PRICE)
@Tag(TRANSACTION)
class PriceSuperIndexPagedPersistenceTest implements EvitaTestSupport {

	private static final String PRICE_LIST_BASIC = "basic";
	private static final Currency CURRENCY_EUR = Currency.getInstance("EUR");
	/** the super-price-index identity the prices below all land in — its tree must go PAGED. */
	private static final PriceIndexKey PRICE_INDEX_KEY = new PriceIndexKey(
		PRICE_LIST_BASIC, CURRENCY_EUR, PriceInnerRecordHandling.NONE
	);
	/** distinct indexed prices inserted during warm-up; > 64 so the price-record tree root becomes internal → PAGED. */
	private static final int WARMUP_COUNT = 300;
	/** additional distinctly-priced entities inserted transactionally after go-live to exercise the page-publish path. */
	private static final int TRANSACTIONAL_COUNT = 50;
	private static final int TOTAL_COUNT = WARMUP_COUNT + TRANSACTIONAL_COUNT;

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("PriceSuperIndexPagedPersistence");
		this.evita = new Evita(configuration());
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("Persist >64 distinctly-priced entities, reopen the catalog, and read them back through the PAGED path")
	void shouldPersistAndReloadPagedSuperPriceIndex() {
		// 1) warm-up bulk insert: a product schema with prices + > 64 distinctly-priced entities, flushed on go-live
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withPrice()
					.updateVia(session);
				for (int pk = 1; pk <= WARMUP_COUNT; pk++) {
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, pk)
							.setPrice(pk, PRICE_LIST_BASIC, CURRENCY_EUR, price(pk), BigDecimal.ZERO, price(pk), true)
					);
				}
				session.goLiveAndClose();
			}
		);

		// 2) transactional commit (ALIVE catalog): add more distinctly-priced entities; WAIT_FOR_CHANGES_VISIBLE makes the
		//    commit durable + visible before the teardown, so the page-publish handshake has fully run
		try (final EvitaSessionContract session = this.evita.createSession(
			new SessionTraits(TEST_CATALOG, CommitBehavior.WAIT_FOR_CHANGES_VISIBLE, SessionFlags.READ_WRITE))) {
			for (int pk = WARMUP_COUNT + 1; pk <= TOTAL_COUNT; pk++) {
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, pk)
						.setPrice(pk, PRICE_LIST_BASIC, CURRENCY_EUR, price(pk), BigDecimal.ZERO, price(pk), true)
				);
			}
		}

		// the super price index must have crossed into the PAGED representation before the restart
		assertTrue(
			isSuperPriceIndexPaged(),
			"Pre-restart super price index should be PAGED (more than 64 distinct prices were inserted)!"
		);
		assertAllPricesResolveToTheirPrimaryKey();

		// 3) close + reopen the whole Evita instance, forcing a cold load of the PAGED leaf pages from disk
		this.evita.close();
		this.evita = new Evita(configuration());
		this.evita.waitUntilFullyInitialized();

		// the reloaded index must still be PAGED and resolve every distinct price to the exact same primary key
		assertTrue(
			isSuperPriceIndexPaged(),
			"Reloaded super price index should be PAGED after a cold load of the leaf pages!"
		);
		assertAllPricesResolveToTheirPrimaryKey();

		// 4) shrink the index hard: delete the warm-up entities so the price-record tree merges leaves (and collapses back
		//    to the inline SINGLE shape), driving the freed-leaf-page removal path through the real persistence drain. A
		//    wrong resolved key or a throwing removal would fail this commit.
		try (final EvitaSessionContract session = this.evita.createSession(
			new SessionTraits(TEST_CATALOG, CommitBehavior.WAIT_FOR_CHANGES_VISIBLE, SessionFlags.READ_WRITE))) {
			for (int pk = 1; pk <= WARMUP_COUNT; pk++) {
				session.deleteEntity(Entities.PRODUCT, pk);
			}
		}
		// the surviving transactional prices must still resolve; the deleted warm-up prices must be gone
		assertSurvivingPricesResolve();

		// 5) reopen once more: the now-smaller live leaf set (or collapsed SINGLE root) must reload cleanly — proving the
		//    freed pages were actually removed and nothing dangles
		this.evita.close();
		this.evita = new Evita(configuration());
		this.evita.waitUntilFullyInitialized();
		assertSurvivingPricesResolve();
	}

	/**
	 * Builds the per-test Evita configuration wired to the (stable across restarts) test path triplet.
	 *
	 * @return the configuration; never null
	 */
	@Nonnull
	private EvitaConfiguration configuration() {
		return newTestEvitaConfigurationBuilder(this.paths).build();
	}

	/**
	 * Produces a distinct selling price for the given primary key (the price equals the primary key), so a
	 * `priceBetween(pk, pk)` query resolves to exactly that one entity.
	 *
	 * @param pk the primary key (1-based)
	 * @return the distinct price; never null
	 */
	@Nonnull
	private static BigDecimal price(int pk) {
		return BigDecimal.valueOf(pk);
	}

	/**
	 * Reaches into the live global entity index of the {@link Entities#PRODUCT} collection and reports whether the super
	 * price index backing {@link #PRICE_INDEX_KEY} currently uses the PAGED (granular) representation.
	 *
	 * @return {@code true} when the backing price-record tree root is internal (PAGED), {@code false} otherwise
	 */
	private boolean isSuperPriceIndexPaged() {
		final Catalog catalog = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		final EntityCollection collection = (EntityCollection) catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();
		final EntityIndex globalIndex = collection.getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.GLOBAL));
		assertNotNull(globalIndex, "Global entity index must exist!");
		assertInstanceOf(GlobalEntityIndex.class, globalIndex, "Global index must be a GlobalEntityIndex!");
		final Object superPriceIndex = globalIndex.getPriceIndex(PRICE_INDEX_KEY);
		assertNotNull(superPriceIndex, "Super price index for the basic/EUR combination must exist!");
		assertInstanceOf(
			PriceListAndCurrencyPriceSuperIndex.class, superPriceIndex,
			"The global price index must be a super index!"
		);
		return ((PriceListAndCurrencyPriceSuperIndex) superPriceIndex).isPaged();
	}

	/**
	 * Asserts that every persisted price resolves, through a real `priceBetween` query at its exact value, to exactly the
	 * one primary key it was written with — the strong round-trip check for the PAGED path.
	 */
	private void assertAllPricesResolveToTheirPrimaryKey() {
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				for (int pk = 1; pk <= TOTAL_COUNT; pk++) {
					final List<EntityReferenceContract> matches = session.queryListOfEntityReferences(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								priceInPriceLists(PRICE_LIST_BASIC),
								priceInCurrency(CURRENCY_EUR),
								priceBetween(price(pk), price(pk))
							)
						)
					);
					assertEquals(1, matches.size(), "Exactly one entity should match price " + pk);
					assertEquals(pk, matches.get(0).getPrimaryKey(), "Wrong primary key for price " + pk);
				}
				return null;
			}
		);
	}

	/**
	 * Asserts that after the warm-up entities were deleted, their prices match nothing while every surviving
	 * (transactional) price still resolves to its exact primary key. Run both before and after the post-shrink reopen to
	 * prove the freed leaf pages were removed without corrupting the surviving leaf set.
	 */
	private void assertSurvivingPricesResolve() {
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				for (int pk = 1; pk <= WARMUP_COUNT; pk++) {
					assertEquals(
						0,
						session.queryListOfEntityReferences(
							query(
								collection(Entities.PRODUCT),
								filterBy(
									priceInPriceLists(PRICE_LIST_BASIC),
									priceInCurrency(CURRENCY_EUR),
									priceBetween(price(pk), price(pk))
								)
							)
						).size(),
						"Deleted price must not match: " + pk
					);
				}
				for (int pk = WARMUP_COUNT + 1; pk <= TOTAL_COUNT; pk++) {
					final List<EntityReferenceContract> matches = session.queryListOfEntityReferences(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								priceInPriceLists(PRICE_LIST_BASIC),
								priceInCurrency(CURRENCY_EUR),
								priceBetween(price(pk), price(pk))
							)
						)
					);
					assertEquals(1, matches.size(), "Exactly one entity should match surviving price " + pk);
					assertEquals(pk, matches.get(0).getPrimaryKey(), "Wrong primary key for surviving price " + pk);
				}
				return null;
			}
		);
	}

}
