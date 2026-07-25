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
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.PriceHistogram;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Currency;
import java.util.stream.IntStream;

import static io.evitadb.api.functional.indexing.IndexingTestSupport.getGlobalIndex;
import static io.evitadb.api.functional.indexing.IndexingTestSupport.getReferencedEntityIndex;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.debug;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.api.query.QueryConstraints.priceBetween;
import static io.evitadb.api.query.QueryConstraints.priceContentAll;
import static io.evitadb.api.query.QueryConstraints.priceInCurrency;
import static io.evitadb.api.query.QueryConstraints.priceHistogram;
import static io.evitadb.api.query.QueryConstraints.priceInPriceLists;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.scope;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.PRICE;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

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
	private static final int FILLER_BRAND_PK = 3;
	private static final int LIVE_PRODUCT_PK = 1;
	private static final int ARCHIVED_PRODUCT_PK = 2;
	private static final int FILLER_PRODUCT_COUNT = 8;
	private static final int FIRST_LIVE_FILLER_PRODUCT_PK = 11;
	private static final int FIRST_ARCHIVED_FILLER_PRODUCT_PK = 21;
	private static final BigDecimal FILLER_PRICE = BigDecimal.valueOf(99);

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

		// ...and the carried ARCHIVED scope must still answer price queries from its OWN data: the archived product
		// keeps its original price, and asking for the LIVE product's NEW price in the ARCHIVED scope finds nothing.
		// Together these are what a stale cross-scope price resolution would break.
		assertPriceQueryReturns(Scope.ARCHIVED, BigDecimal.TEN, ARCHIVED_PRODUCT_PK);
		assertPriceQueryReturns(Scope.ARCHIVED, BigDecimal.valueOf(20));
		// the mutated LIVE scope reports the new price, and no longer the old one
		assertPriceQueryReturns(Scope.LIVE, BigDecimal.valueOf(20), LIVE_PRODUCT_PK);
		assertPriceQueryReturns(Scope.LIVE, BigDecimal.TEN);
		// the assertions above exercise the FILTER, which reads each reduced index's own price-record tree. The super
		// index the caller resolves per operation is consumed when price records are materialized - a histogram does
		// that - so this is what actually pins cross-scope price resolution.
		assertPriceHistogramReports(Scope.ARCHIVED, BigDecimal.TEN, FILLER_PRICE);
		assertPriceHistogramReports(Scope.LIVE, BigDecimal.valueOf(20), FILLER_PRICE);
		// ...and the same, narrowed to the brand, so the query is answered from the REDUCED index rather than the
		// GLOBAL one. That narrowing is what makes these assertions a tripwire: a reduced index holds no super price
		// index of its own, so the caller must resolve one, and only this shape exercises that resolution.
		assertReducedIndexPriceHistogramReports(Scope.ARCHIVED, ARCHIVED_BRAND_PK, BigDecimal.TEN);
		assertReducedIndexPriceHistogramReports(Scope.LIVE, LIVE_BRAND_PK, BigDecimal.valueOf(20));
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
				session.upsertEntity(session.createNewEntity(Entities.BRAND, FILLER_BRAND_PK));
				upsertProduct(session, LIVE_PRODUCT_PK, LIVE_BRAND_PK, BigDecimal.TEN);
				upsertProduct(session, ARCHIVED_PRODUCT_PK, ARCHIVED_BRAND_PK, BigDecimal.TEN);
				// the filler products exist so each observed brand owns a small MINORITY of its scope: the planner
				// discards a reduced index whose cardinality exceeds half the collection, and with a single product
				// per scope it would answer every query from the GLOBAL index instead - never resolving a super price
				// index for a reduced one, which is exactly the resolution these tests exist to pin
				for (int i = 0; i < FILLER_PRODUCT_COUNT; i++) {
					upsertProduct(session, FIRST_LIVE_FILLER_PRODUCT_PK + i, FILLER_BRAND_PK, FILLER_PRICE);
					upsertProduct(session, FIRST_ARCHIVED_FILLER_PRODUCT_PK + i, FILLER_BRAND_PK, FILLER_PRICE);
				}
			}
		);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.archiveEntity(Entities.PRODUCT, ARCHIVED_PRODUCT_PK);
				for (int i = 0; i < FILLER_PRODUCT_COUNT; i++) {
					session.archiveEntity(Entities.PRODUCT, FIRST_ARCHIVED_FILLER_PRODUCT_PK + i);
				}
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
	 * Asserts that a price query restricted to `scope` returns exactly `expectedPks` at the given price.
	 *
	 * This replaces the former pair of wiring probes, which re-ran the production wiring check to observe which GLOBAL
	 * a reduced index's price chain had captured. There is no longer a captured GLOBAL to observe: the reduced index is
	 * handed the GLOBAL's price index per operation. What matters is therefore asserted directly - that a scope carried
	 * across a version bump still answers price queries from its OWN scope's data - which is a stronger claim than the
	 * wiring probe made, because it exercises the resolution the query path actually performs.
	 *
	 * @param scope       the scope to query
	 * @param price       the price the product is expected to be found at
	 * @param expectedPks the primary keys expected back, in any order
	 */
	private void assertPriceQueryReturns(@Nonnull Scope scope, @Nonnull BigDecimal price, @Nonnull int... expectedPks) {
		try (final EvitaSessionContract session = this.evita.createReadOnlySession(TEST_CATALOG)) {
			final Integer[] found = session.queryList(
					Query.query(
						collection(Entities.PRODUCT),
						filterBy(
							scope(scope),
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
				"Price query in scope `" + scope + "` at " + price + " must return exactly " + Arrays.toString(expected) +
					" but returned " + Arrays.toString(found) + "!"
			);
		}
	}

	/**
	 * Asserts that a price histogram computed over `scope` reports exactly `expectedPrice` as both its minimum and its
	 * maximum - i.e. that the single product in that scope contributed its OWN price.
	 *
	 * It asserts the price VALUES a scope reports, which the plain `priceBetween` assertion above does not: that filter
	 * is answered from the reduced index's own price-record tree.
	 *
	 * **Known limit of this assertion, measured not assumed.** A query that names no reference is answered from the
	 * scope's GLOBAL entity index, and a GLOBAL index owns its super price indexes outright - so nothing here resolves
	 * a super price index for a reduced one, and forcing that resolution to the wrong scope leaves this assertion
	 * green. Treat it as a behaviour assertion; the tripwire for a mis-resolved super price index is
	 * {@link #assertReducedIndexPriceHistogramReports(Scope, int, BigDecimal)}, which narrows by reference and does
	 * reach it.
	 *
	 * @param scope       the scope to compute the histogram over
	 * @param expectedMin the lowest price the scope is expected to report
	 * @param expectedMax the highest price the scope is expected to report
	 */
	private void assertPriceHistogramReports(
		@Nonnull Scope scope,
		@Nonnull BigDecimal expectedMin,
		@Nonnull BigDecimal expectedMax
	) {
		try (final EvitaSessionContract session = this.evita.createReadOnlySession(TEST_CATALOG)) {
			final EvitaResponse<EntityReference> response = session.query(
				Query.query(
					collection(Entities.PRODUCT),
					filterBy(
						scope(scope),
						priceInPriceLists(PRICE_LIST_BASIC),
						priceInCurrency(CURRENCY_EUR)
					),
					require(priceHistogram(20))
				),
				EntityReference.class
			);
			final PriceHistogram histogram = response.getExtraResult(PriceHistogram.class);
			assertNotNull(histogram, "Price histogram must be computable in scope `" + scope + "`!");
			assertEquals(
				0, expectedMin.compareTo(histogram.getMin()),
				"Price histogram minimum in scope `" + scope + "` must be " + expectedMin +
					" but was " + histogram.getMin() + " - the scope resolved prices that are not its own!"
			);
			assertEquals(
				0, expectedMax.compareTo(histogram.getMax()),
				"Price histogram maximum in scope `" + scope + "` must be " + expectedMax +
					" but was " + histogram.getMax() + "!"
			);
		}
	}

	/**
	 * Asserts that a price histogram computed over the REDUCED index of `brandPk` in `scope` reports exactly
	 * `expectedPrice` as both its minimum and its maximum.
	 *
	 * The narrowing by brand is the entire point: without it the query targets the scope's GLOBAL entity index, which
	 * owns its super price indexes outright and therefore needs no resolution at all. Narrowed to a reference, the
	 * query targets a reduced index, which keeps no super price index of its own - so the caller has to resolve the
	 * GLOBAL of the SAME collection and scope before the price records can be materialized.
	 *
	 * **Verified by mutation, not assumed.** Forcing `FilterByVisitor#getSuperPriceIndex` to resolve a fixed scope
	 * makes this assertion fail: the reduced index's price ids are looked up in another scope's super index, find
	 * nothing, and no histogram can be computed at all. The narrowing is what earns that - the same mutation leaves
	 * every non-narrowed assertion in this class green.
	 *
	 * The fixture's filler products matter for the same reason: the planner discards a reduced index whose cardinality
	 * exceeds half its collection, so without them no reduced index is ever eligible and this assertion would quietly
	 * be answered from the GLOBAL index instead.
	 *
	 * @param scope         the scope to compute the histogram over
	 * @param brandPk       the brand whose reduced index should answer the query
	 * @param expectedPrice the price the single product of that scope carries
	 */
	private void assertReducedIndexPriceHistogramReports(
		@Nonnull Scope scope,
		int brandPk,
		@Nonnull BigDecimal expectedPrice
	) {
		try (final EvitaSessionContract session = this.evita.createReadOnlySession(TEST_CATALOG)) {
			final EvitaResponse<EntityReference> response = session.query(
				Query.query(
					collection(Entities.PRODUCT),
					filterBy(
						scope(scope),
						referenceHaving(Entities.BRAND, entityPrimaryKeyInSet(brandPk)),
						priceInPriceLists(PRICE_LIST_BASIC),
						priceInCurrency(CURRENCY_EUR)
					),
					require(
						priceHistogram(20),
						// evaluate EVERY eligible index alternative and compare them, so this assertion keeps
						// exercising the reduced index even if the cost model later starts preferring the GLOBAL one
						// for this shape - without it the tripwire could silently stop aiming at what it pins
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS)
					)
				),
				EntityReference.class
			);
			final PriceHistogram histogram = response.getExtraResult(PriceHistogram.class);
			assertNotNull(
				histogram,
				"Price histogram must be computable from the reduced index of brand " + brandPk +
					" in scope `" + scope + "`!"
			);
			assertEquals(
				0, expectedPrice.compareTo(histogram.getMin()),
				"Price histogram minimum resolved through the reduced index of brand " + brandPk + " in scope `" +
					scope + "` must be " + expectedPrice + " but was " + histogram.getMin() +
					" - the reduced index resolved a super price index that is not its own!"
			);
			assertEquals(
				0, expectedPrice.compareTo(histogram.getMax()),
				"Price histogram maximum resolved through the reduced index of brand " + brandPk + " in scope `" +
					scope + "` must be " + expectedPrice + " but was " + histogram.getMax() + "!"
			);
		}
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
