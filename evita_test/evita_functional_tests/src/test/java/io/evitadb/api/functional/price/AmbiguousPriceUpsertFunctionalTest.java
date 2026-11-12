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

package io.evitadb.api.functional.price;

import io.evitadb.api.exception.AmbiguousPriceException;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.price.RemovePriceMutation;
import io.evitadb.api.requestResponse.data.mutation.price.SetPriceInnerRecordHandlingMutation;
import io.evitadb.api.requestResponse.data.mutation.price.UpsertPriceMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.transaction.stage.mutation.ServerEntityUpsertMutation;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.index.mutation.ConsistencyCheckingLocalMutationExecutor.ImplicitMutationBehavior;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.EnumSet;
import java.util.List;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.PRICE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies that the server refuses an upsert that would leave an entity with two prices that compete for the same
 * place - one inner record (for an entity without inner record handling: any inner record) in one price list and
 * currency, valid at a common moment - whatever way the mutations arrive, and that the refusal leaves the entity and
 * its indexes as they were. Prices an entity already holds are checked only against a price the upsert adds or moves,
 * so an entity that holds an ambiguous pair stays updatable. Every test runs in a catalog in the transactional state
 * and in a freshly defined catalog that is still bulk-loading.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Rejection of ambiguous prices on upsert")
@ExtendWith(EvitaParameterResolver.class)
@Tag(ENGINE)
@Tag(PRICE)
@Tag(TRANSACTION)
class AmbiguousPriceUpsertFunctionalTest {
	private static final String PRODUCT = "product";
	private static final String PRICE_LIST_BASIC = "basic";
	private static final String PRICE_LIST_REFERENCE = "reference";
	private static final Currency CZK = Currency.getInstance("CZK");
	/**
	 * Name of the catalog defined by the test, which stays in the bulk-loading state.
	 */
	private static final String WARM_UP_CATALOG = "warmUpCatalog";

	/**
	 * Returns the catalog the test runs in.
	 *
	 * @param alive `true` for the catalog in the transactional state, `false` for the bulk-loading one
	 * @return name of the catalog
	 */
	@Nonnull
	private static String catalog(boolean alive) {
		return alive ? TEST_CATALOG : WARM_UP_CATALOG;
	}

	/**
	 * Returns the validity spanning the year 2020.
	 *
	 * @return the validity
	 */
	@Nonnull
	private static DateTimeRange firstYear() {
		return DateTimeRange.between(
			OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC),
			OffsetDateTime.of(2020, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC)
		);
	}

	/**
	 * Returns the validity spanning the year 2021, which does not overlap {@link #firstYear()}.
	 *
	 * @return the validity
	 */
	@Nonnull
	private static DateTimeRange secondYear() {
		return DateTimeRange.between(
			OffsetDateTime.of(2021, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC),
			OffsetDateTime.of(2021, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC)
		);
	}

	/**
	 * Creates a raw upsert of an indexed price in CZK without tax.
	 *
	 * @param priceId       id of the price
	 * @param priceList     price list of the price
	 * @param innerRecordId inner record of the price, `null` for none
	 * @param amount        whole amount of the price
	 * @param validity      validity of the price, `null` for a price valid at any moment
	 * @return the mutation
	 */
	@Nonnull
	private static UpsertPriceMutation upsertPrice(
		int priceId,
		@Nonnull String priceList,
		@Nullable Integer innerRecordId,
		int amount,
		@Nullable DateTimeRange validity
	) {
		final BigDecimal value = new BigDecimal(amount).setScale(2);
		return new UpsertPriceMutation(
			priceId, priceList, CZK, innerRecordId, value, BigDecimal.ZERO, value, validity, true
		);
	}

	/**
	 * Applies the passed local mutations to product 1 in one upsert.
	 *
	 * @param evita     the evitaDB instance
	 * @param catalog   name of the catalog
	 * @param mutations the mutations to apply
	 */
	private static void upsertProduct(
		@Nonnull Evita evita,
		@Nonnull String catalog,
		@Nonnull LocalMutation<?, ?>... mutations
	) {
		evita.updateCatalog(
			catalog,
			session -> {
				session.upsertEntity(new EntityUpsertMutation(PRODUCT, 1, EntityExistence.MUST_EXIST, mutations));
			}
		);
	}

	/**
	 * Creates product 1 with the passed handling and a price of inner record 1 in the basic price list valid in 2020
	 * (price 30, selling for 100), and product 2 selling for 150. The bulk-loading catalog is defined first.
	 *
	 * @param evita    the evitaDB instance
	 * @param handling inner record handling of product 1
	 * @param alive    `true` for the catalog in the transactional state, `false` for the bulk-loading one
	 * @return name of the catalog the products were created in
	 */
	@Nonnull
	private static String createProducts(
		@Nonnull Evita evita,
		@Nonnull PriceInnerRecordHandling handling,
		boolean alive
	) {
		final String catalog = catalog(alive);
		if (!alive) {
			evita.defineCatalog(catalog);
		}
		evita.updateCatalog(
			catalog,
			session -> {
				session.defineEntitySchema(PRODUCT).withPrice().updateVia(session);
				final BigDecimal hundred = new BigDecimal("100.00");
				final EntityBuilder product = session.createNewEntity(PRODUCT, 1)
					.setPriceInnerRecordHandling(handling);
				product.setPrice(30, PRICE_LIST_BASIC, CZK, 1, hundred, BigDecimal.ZERO, hundred, firstYear(), true);
				session.upsertEntity(product);
				final BigDecimal hundredFifty = new BigDecimal("150.00");
				final EntityBuilder other = session.createNewEntity(PRODUCT, 2)
					.setPriceInnerRecordHandling(handling);
				other.setPrice(40, PRICE_LIST_BASIC, CZK, hundredFifty, BigDecimal.ZERO, hundredFifty, null, true);
				session.upsertEntity(other);
			}
		);
		return catalog;
	}

	/**
	 * Returns the ids of the prices product 1 holds, ordered by price id.
	 *
	 * @param evita   the evitaDB instance
	 * @param catalog name of the catalog
	 * @return the price ids
	 */
	@Nonnull
	private static List<Integer> queryPriceIdsOfProduct(@Nonnull Evita evita, @Nonnull String catalog) {
		return evita.queryCatalog(
			catalog,
			session -> {
				final List<Integer> priceIds = new ArrayList<>(4);
				for (final PriceContract price : session.getEntity(PRODUCT, 1, priceContentAll())
					.orElseThrow()
					.getPrices()) {
					priceIds.add(price.priceId());
				}
				priceIds.sort(Integer::compare);
				return priceIds;
			}
		);
	}

	/**
	 * Returns primary keys of the products selling for 90 to 110 in the basic price list in 2020, resolved from the
	 * indexes.
	 *
	 * @param evita   the evitaDB instance
	 * @param catalog name of the catalog
	 * @return primary keys of the matching products
	 */
	@Nonnull
	private static List<Integer> queryProductsSellingAroundHundredIn2020(
		@Nonnull Evita evita,
		@Nonnull String catalog
	) {
		return evita.queryCatalog(
			catalog,
			session -> {
				final List<EntityReferenceContract> references = session.queryListOfEntityReferences(
					query(
						collection(PRODUCT),
						filterBy(
							priceInCurrency(CZK),
							priceInPriceLists(PRICE_LIST_BASIC),
							priceValidIn(OffsetDateTime.of(2020, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC)),
							priceBetween(new BigDecimal("90"), new BigDecimal("110"))
						),
						require(debug(DebugMode.PREFER_INDEX_SCAN))
					)
				);
				final List<Integer> primaryKeys = new ArrayList<>(references.size());
				for (final EntityReferenceContract reference : references) {
					primaryKeys.add(reference.getPrimaryKey());
				}
				return primaryKeys;
			}
		);
	}

	@ParameterizedTest(name = "alive: {0}")
	@ValueSource(booleans = {false, true})
	@DisplayName("should reject an overlapping price of one inner record and keep the entity unchanged")
	void shouldRejectOverlappingPriceOfOneInnerRecordAndKeepEntityUnchanged(boolean alive, Evita evita) {
		final String catalog = createProducts(evita, PriceInnerRecordHandling.LOWEST_PRICE, alive);

		assertThrows(
			AmbiguousPriceException.class,
			() -> upsertProduct(evita, catalog, upsertPrice(10, PRICE_LIST_BASIC, 1, 105, firstYear()))
		);
		assertEquals(List.of(30), queryPriceIdsOfProduct(evita, catalog));
		assertEquals(List.of(1), queryProductsSellingAroundHundredIn2020(evita, catalog));

		// the same price valid in another year competes with nothing
		assertDoesNotThrow(
			() -> upsertProduct(evita, catalog, upsertPrice(10, PRICE_LIST_BASIC, 1, 105, secondYear()))
		);
		assertEquals(List.of(10, 30), queryPriceIdsOfProduct(evita, catalog));
	}

	@ParameterizedTest(name = "alive: {0}")
	@ValueSource(booleans = {false, true})
	@DisplayName("should reject a validity change that makes two prices of one inner record overlap")
	void shouldRejectValidityChangeThatMakesTwoPricesOfOneInnerRecordOverlap(boolean alive, Evita evita) {
		final String catalog = createProducts(evita, PriceInnerRecordHandling.LOWEST_PRICE, alive);
		upsertProduct(evita, catalog, upsertPrice(10, PRICE_LIST_BASIC, 1, 105, secondYear()));

		assertThrows(
			AmbiguousPriceException.class,
			() -> upsertProduct(evita, catalog, upsertPrice(10, PRICE_LIST_BASIC, 1, 105, null))
		);
		assertEquals(List.of(10, 30), queryPriceIdsOfProduct(evita, catalog));
	}

	@ParameterizedTest(name = "alive: {0}")
	@ValueSource(booleans = {false, true})
	@DisplayName("should reject overlapping prices of different inner records without inner record handling")
	void shouldRejectOverlappingPricesOfDifferentInnerRecordsInEntityWithoutInnerRecordHandling(
		boolean alive,
		Evita evita
	) {
		final String catalog = createProducts(evita, PriceInnerRecordHandling.NONE, alive);

		assertThrows(
			AmbiguousPriceException.class,
			() -> upsertProduct(evita, catalog, upsertPrice(10, PRICE_LIST_BASIC, 2, 105, null))
		);
		assertEquals(List.of(30), queryPriceIdsOfProduct(evita, catalog));
	}

	@ParameterizedTest(name = "alive: {0}")
	@ValueSource(booleans = {false, true})
	@DisplayName("should reject in the builder what the server rejects without inner record handling")
	void shouldRejectInBuilderOverlappingPriceOfAnotherInnerRecordInEntityWithoutInnerRecordHandling(
		boolean alive,
		Evita evita
	) {
		final String catalog = createProducts(evita, PriceInnerRecordHandling.NONE, alive);
		final BigDecimal amount = new BigDecimal("105.00");

		evita.updateCatalog(
			catalog,
			session -> {
				final EntityBuilder product = session.getEntity(PRODUCT, 1, priceContentAll())
					.orElseThrow()
					.openForWrite();
				product.setPrice(10, PRICE_LIST_BASIC, CZK, 2, amount, BigDecimal.ZERO, amount, null, true);
				assertThrows(AmbiguousPriceException.class, product::toMutation);
			}
		);
		assertEquals(List.of(30), queryPriceIdsOfProduct(evita, catalog));

		// the same price is legal once the same builder gives the entity the lowest price handling
		evita.updateCatalog(
			catalog,
			session -> {
				final EntityBuilder product = session.getEntity(PRODUCT, 1, priceContentAll())
					.orElseThrow()
					.openForWrite();
				product.setPrice(10, PRICE_LIST_BASIC, CZK, 2, amount, BigDecimal.ZERO, amount, null, true);
				product.setPriceInnerRecordHandling(PriceInnerRecordHandling.LOWEST_PRICE);
				session.upsertEntity(product);
			}
		);
		assertEquals(List.of(10, 30), queryPriceIdsOfProduct(evita, catalog));
	}

	@ParameterizedTest(name = "alive: {0}")
	@ValueSource(booleans = {false, true})
	@DisplayName("should reject a handling change that makes the stored prices overlap")
	void shouldRejectHandlingChangeThatMakesStoredPricesOverlap(boolean alive, Evita evita) {
		final String catalog = createProducts(evita, PriceInnerRecordHandling.LOWEST_PRICE, alive);
		upsertProduct(evita, catalog, upsertPrice(10, PRICE_LIST_BASIC, 2, 105, null));

		assertThrows(
			AmbiguousPriceException.class,
			() -> upsertProduct(
				evita, catalog, new SetPriceInnerRecordHandlingMutation(PriceInnerRecordHandling.NONE)
			)
		);
		assertEquals(
			PriceInnerRecordHandling.LOWEST_PRICE,
			evita.queryCatalog(
				catalog,
				session -> {
					return session.getEntity(PRODUCT, 1, priceContentAll()).orElseThrow()
						.getPriceInnerRecordHandling();
				}
			)
		);
	}

	@ParameterizedTest(name = "alive: {0}")
	@ValueSource(booleans = {false, true})
	@DisplayName("should accept an overlapping price when the upsert removes the price it overlaps")
	void shouldAcceptOverlappingPriceWhenUpsertRemovesPriceItOverlaps(boolean alive, Evita evita) {
		final String catalog = createProducts(evita, PriceInnerRecordHandling.LOWEST_PRICE, alive);

		assertDoesNotThrow(
			() -> upsertProduct(
				evita, catalog,
				upsertPrice(10, PRICE_LIST_BASIC, 1, 105, firstYear()),
				new RemovePriceMutation(30, PRICE_LIST_BASIC, CZK)
			)
		);
		assertEquals(List.of(10), queryPriceIdsOfProduct(evita, catalog));
	}

	/**
	 * An ambiguous pair stored before the server checked prices - planted here by an upsert that skips the consistency
	 * check, as the replay of such a write-ahead log does - stays: upserts that leave the validity and the inner record
	 * of both prices as they are, or that touch other prices, are accepted. Only an upsert that moves one of the pair
	 * is checked against the other.
	 */
	@ParameterizedTest(name = "alive: {0}")
	@ValueSource(booleans = {false, true})
	@DisplayName("should keep an entity holding an ambiguous pair updatable")
	void shouldKeepEntityHoldingAmbiguousPairUpdatable(boolean alive, Evita evita) {
		final String catalog = createProducts(evita, PriceInnerRecordHandling.LOWEST_PRICE, alive);
		evita.updateCatalog(
			catalog,
			session -> {
				session.upsertEntity(
					new ServerEntityUpsertMutation(
						PRODUCT, 1, EntityExistence.MUST_EXIST,
						EnumSet.allOf(ImplicitMutationBehavior.class), true, false,
						upsertPrice(10, PRICE_LIST_BASIC, 1, 105, firstYear())
					)
				);
			}
		);
		assertEquals(List.of(10, 30), queryPriceIdsOfProduct(evita, catalog));

		// the amount of one of the pair changes
		assertDoesNotThrow(
			() -> upsertProduct(evita, catalog, upsertPrice(30, PRICE_LIST_BASIC, 1, 95, firstYear()))
		);
		// another price is added
		assertDoesNotThrow(
			() -> upsertProduct(evita, catalog, upsertPrice(31, PRICE_LIST_REFERENCE, 1, 200, null))
		);
		// one of the pair moves to a validity that still overlaps the other
		assertThrows(
			AmbiguousPriceException.class,
			() -> upsertProduct(evita, catalog, upsertPrice(30, PRICE_LIST_BASIC, 1, 95, null))
		);
		// one of the pair moves away from the other
		assertDoesNotThrow(
			() -> upsertProduct(evita, catalog, upsertPrice(30, PRICE_LIST_BASIC, 1, 95, secondYear()))
		);
		assertEquals(List.of(10, 30, 31), queryPriceIdsOfProduct(evita, catalog));
	}

}
