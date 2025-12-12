/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.query.require.AccompanyingPriceContent;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceForSaleContextWithCachedResult;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.PricesContract;
import io.evitadb.api.requestResponse.data.PricesContract.AccompanyingPrice;
import io.evitadb.api.requestResponse.data.PricesContract.PriceForSaleWithAccompanyingPrices;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies {@link Prices} contract implementation by testing various price handling scenarios.
 * It covers different price handling strategies, price selection logic, and price comparison functionality.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Prices contract implementation tests")
class PricesContractTest extends AbstractBuilderTest {
	private static final Currency CZK = Currency.getInstance("CZK");
	private static final Currency EUR = Currency.getInstance("EUR");
	private static final Currency GBP = Currency.getInstance("GBP");
	private static final String BASIC = "basic";
	private static final String LOGGED_ONLY = "loggedOnly";
	private static final String VIP = "vip";
	private static final String REFERENCE = "reference";
	private static final OffsetDateTime MOMENT_2020 = OffsetDateTime.of(2020, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC);
	private static final OffsetDateTime MOMENT_2011 = OffsetDateTime.of(2011, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC);
	private static final DateTimeRange RANGE_2010_2012 = DateTimeRange.between(
		LocalDateTime.of(2010, 1, 1, 0, 0, 0, 0),
		LocalDateTime.of(2012, 12, 31, 23, 59, 59, 0),
		ZoneOffset.UTC
	);

	/**
	 * Verifies that the accompanying prices in the result match the expected price IDs.
	 *
	 * @param allPrice the price for sale with accompanying prices to verify
	 * @param accompanyingPriceId expected IDs of accompanying prices, -1 indicates a null price
	 */
	private static void assertAccompanyingPrices(PriceForSaleWithAccompanyingPrices allPrice, int... accompanyingPriceId) {
		final Map<String, Optional<PriceContract>> accompanyingPrices = allPrice.accompanyingPrices();
		assertEquals(accompanyingPriceId.length, accompanyingPrices.size());
		for (int i = 0; i < accompanyingPriceId.length; i++) {
			int pid = accompanyingPriceId[i];
			if (pid == -1) {
				assertNull(accompanyingPrices.get("p" + i).map(PriceContract::priceId).orElse(null));
			} else {
				assertEquals(pid, accompanyingPrices.get("p" + i).map(PriceContract::priceId).orElse(null));
			}
		}
	}

	/**
	 * Creates a standard set of prices with values multiplied by the specified multiplier.
	 * This helper method is used to generate test data with different price values.
	 *
	 * @param innerRecordId the inner record ID to use for the prices, or null if not applicable
	 * @param multiplier the multiplier to apply to all price values
	 * @return an array of price contracts with the specified inner record ID and multiplied values
	 */
	@Nonnull
	private static PriceContract[] createStandardPriceSetWithMultiplier(@Nullable Integer innerRecordId, @Nonnull BigDecimal multiplier) {
		return new PriceContract[]{
			new Price(new PriceKey(combineIntoId(innerRecordId, 1), BASIC, CZK), innerRecordId, new BigDecimal("100").multiply(multiplier), new BigDecimal("21"), new BigDecimal("121").multiply(multiplier), null, true),
			new Price(new PriceKey(combineIntoId(innerRecordId, 2), BASIC, EUR), innerRecordId, new BigDecimal("10").multiply(multiplier), new BigDecimal("21"), new BigDecimal("12.1").multiply(multiplier), null, true),
			new Price(new PriceKey(combineIntoId(innerRecordId, 3), LOGGED_ONLY, CZK), innerRecordId, new BigDecimal("80").multiply(multiplier), new BigDecimal("21"), new BigDecimal("96.8").multiply(multiplier), null, true),
			new Price(new PriceKey(combineIntoId(innerRecordId, 4), LOGGED_ONLY, EUR), innerRecordId, new BigDecimal("8").multiply(multiplier), new BigDecimal("21"), new BigDecimal("9.68").multiply(multiplier), null, true),
			new Price(new PriceKey(combineIntoId(innerRecordId, 5), VIP, CZK), innerRecordId, new BigDecimal("60").multiply(multiplier), new BigDecimal("21"), new BigDecimal("72.6").multiply(multiplier), RANGE_2010_2012, true),
			new Price(new PriceKey(combineIntoId(innerRecordId, 6), VIP, EUR), innerRecordId, new BigDecimal("6").multiply(multiplier), new BigDecimal("21"), new BigDecimal("7.26").multiply(multiplier), RANGE_2010_2012, true),
			new Price(new PriceKey(combineIntoId(innerRecordId, 7), REFERENCE, CZK), innerRecordId, new BigDecimal("140").multiply(multiplier), new BigDecimal("21"), new BigDecimal("169.4").multiply(multiplier), null, false),
			new Price(new PriceKey(combineIntoId(innerRecordId, 8), REFERENCE, EUR), innerRecordId, new BigDecimal("14").multiply(multiplier), new BigDecimal("21"), new BigDecimal("16.94").multiply(multiplier), null, false)
		};
	}

	/**
	 * Asserts that the prices contract has a price within the specified range.
	 *
	 * @param from the lower bound of the price range (inclusive)
	 * @param to the upper bound of the price range (inclusive)
	 * @param prices the prices contract to check
	 * @param currency the currency to check prices in
	 * @param moment the moment in time to check prices at
	 * @param priceLists the price lists to check prices in
	 */
	private static void assertHasPriceInRange(BigDecimal from, BigDecimal to, PricesContract prices, Currency currency, OffsetDateTime moment, String... priceLists) {
		assertTrue(prices.hasPriceInInterval(from, to, QueryPriceMode.WITHOUT_TAX, currency, moment, convertToClassifiers(priceLists)));
	}

	/**
	 * Asserts that the prices contract has a price within the specified range for each multiplier.
	 * This method tests multiple price ranges by applying different multipliers to the base range.
	 *
	 * @param from the lower bound of the price range (inclusive)
	 * @param to the upper bound of the price range (inclusive)
	 * @param multiplier array of multipliers to apply to the range bounds
	 * @param prices the prices contract to check
	 * @param currency the currency to check prices in
	 * @param moment the moment in time to check prices at
	 * @param priceLists the price lists to check prices in
	 */
	private static void assertHasPriceInRangeWithMultiplier(BigDecimal from, BigDecimal to, BigDecimal[] multiplier, PricesContract prices, Currency currency, OffsetDateTime moment, String... priceLists) {
		for (BigDecimal m : multiplier) {
			assertTrue(prices.hasPriceInInterval(from.multiply(m), to.multiply(m), QueryPriceMode.WITHOUT_TAX, currency, moment, convertToClassifiers(priceLists)));
		}
	}

	/**
	 * Asserts that the prices contract does not have a price within the specified range.
	 *
	 * @param from the lower bound of the price range (inclusive)
	 * @param to the upper bound of the price range (inclusive)
	 * @param prices the prices contract to check
	 * @param currency the currency to check prices in
	 * @param moment the moment in time to check prices at
	 * @param priceLists the price lists to check prices in
	 */
	private static void assertHasNotPriceInRange(BigDecimal from, BigDecimal to, PricesContract prices, Currency currency, OffsetDateTime moment, String... priceLists) {
		assertFalse(prices.hasPriceInInterval(from, to, QueryPriceMode.WITHOUT_TAX, currency, moment, convertToClassifiers(priceLists)));
	}

	/**
	 * Asserts that the price for sale has the expected price ID.
	 *
	 * @param expectedPriceId the expected price ID (without inner record ID component)
	 * @param innerRecordId the inner record ID to combine with the price ID, or null if not applicable
	 * @param prices the prices contract to get the price from
	 * @param currency the currency to get the price in
	 * @param moment the moment in time to get the price at
	 * @param priceLists the price lists to consider
	 */
	private static void assertPriceForSale(int expectedPriceId, @Nullable Integer innerRecordId, @Nonnull PricesContract prices, @Nonnull Currency currency, @Nullable OffsetDateTime moment, String... priceLists) {
		final PriceContract priceForSale = prices.getPriceForSale(
			currency, moment, convertToClassifiers(priceLists)
		).orElseThrow();

		assertEquals(combineIntoId(innerRecordId, expectedPriceId), priceForSale.priceId());
	}

	/**
	 * Asserts that the price for sale with accompanying prices has the expected price IDs.
	 * This method verifies both the main price and all accompanying prices.
	 *
	 * @param expectedPriceId the expected price ID for the main price
	 * @param expectedAccompaniedPriceIds the expected price IDs for accompanying prices, -1 indicates a null price
	 * @param innerRecordId the inner record ID to combine with the price IDs, or null if not applicable
	 * @param prices the prices contract to get the prices from
	 * @param currency the currency to get the prices in
	 * @param moment the moment in time to get the prices at
	 * @param priceLists the price lists to consider
	 * @param accompanyingPrices the specifications for accompanying prices to retrieve
	 */
	private static void assertPriceForSaleWithAccompanyingPrices(int expectedPriceId, int[] expectedAccompaniedPriceIds, @Nullable Integer innerRecordId, PricesContract prices, Currency currency, @Nullable OffsetDateTime moment, String[] priceLists, AccompanyingPrice[] accompanyingPrices) {
		assertEquals(expectedAccompaniedPriceIds.length, accompanyingPrices.length);

		final PriceForSaleWithAccompanyingPrices priceForSaleWithAccompanyingPrices = prices.getPriceForSaleWithAccompanyingPrices(
			currency, moment, convertToClassifiers(priceLists), accompanyingPrices
		).orElseThrow();

		assertEquals(expectedAccompaniedPriceIds.length, priceForSaleWithAccompanyingPrices.accompanyingPrices().size());
		assertEquals(combineIntoId(innerRecordId, expectedPriceId), priceForSaleWithAccompanyingPrices.priceForSale().priceId());
		for (int i = 0; i < expectedAccompaniedPriceIds.length; i++) {
			int expectedAccompaniedPriceId = expectedAccompaniedPriceIds[i];
			final AccompanyingPrice accompanyingPrice = accompanyingPrices[i];
			final Optional<PriceContract> thePrice = priceForSaleWithAccompanyingPrices.accompanyingPrices().get(accompanyingPrice.priceName());
			if (expectedAccompaniedPriceId == -1) {
				assertTrue(
					thePrice.isEmpty()
				);
			} else {
				assertEquals(
					combineIntoId(innerRecordId, expectedAccompaniedPriceId),
					thePrice.orElseThrow().priceId()
				);
			}
		}

		if (prices.getPriceInnerRecordHandling() == PriceInnerRecordHandling.NONE) {
			final List<PriceForSaleWithAccompanyingPrices> allPricesResult = prices.getAllPricesForSaleWithAccompanyingPrices(
				currency, moment, convertToClassifiers(priceLists), accompanyingPrices
			);
			assertEquals(1, allPricesResult.size());
			assertEquals(priceForSaleWithAccompanyingPrices, allPricesResult.get(0));
		}
	}

	/**
	 * Asserts that the price for sale has the expected price without VAT.
	 *
	 * @param expectedPriceWithoutVat the expected price value without VAT
	 * @param prices the prices contract to get the price from
	 * @param currency the currency to get the price in
	 * @param moment the moment in time to get the price at
	 * @param priceLists the price lists to consider
	 */
	private static void assertPriceForSale(BigDecimal expectedPriceWithoutVat, PricesContract prices, Currency currency, OffsetDateTime moment, String... priceLists) {
		final PriceContract priceForSale = prices.getPriceForSale(
			currency, moment, convertToClassifiers(priceLists)
		).orElseThrow();

		assertEquals(expectedPriceWithoutVat, priceForSale.priceWithoutTax());
	}

	/**
	 * Asserts that the price for sale with accompanying prices has the expected price values without VAT.
	 * This method verifies both the main price and all accompanying prices by their price values.
	 *
	 * @param expectedPriceWithoutVat the expected price value without VAT for the main price
	 * @param expectedAccompaniedPrices the expected price values without VAT for accompanying prices, null indicates a missing price
	 * @param prices the prices contract to get the prices from
	 * @param currency the currency to get the prices in
	 * @param moment the moment in time to get the prices at
	 * @param priceLists the price lists to consider
	 * @param accompanyingPrices the specifications for accompanying prices to retrieve
	 */
	private static void assertPriceForSaleWithAccompanyingPrices(BigDecimal expectedPriceWithoutVat, BigDecimal[] expectedAccompaniedPrices, PricesContract prices, Currency currency, OffsetDateTime moment, String[] priceLists, AccompanyingPrice[] accompanyingPrices) {
		assertEquals(expectedAccompaniedPrices.length, accompanyingPrices.length);

		final PriceForSaleWithAccompanyingPrices priceForSaleWithAccompanyingPrices = prices.getPriceForSaleWithAccompanyingPrices(
			currency, moment, convertToClassifiers(priceLists), accompanyingPrices
		).orElseThrow();

		final PriceForSaleWithAccompanyingPrices priceForSale = prices.getPriceForSaleWithAccompanyingPrices(
			currency, moment, convertToClassifiers(priceLists), PricesContract.NO_ACCOMPANYING_PRICES
		).orElseThrow();


		assertEquals(expectedAccompaniedPrices.length, priceForSaleWithAccompanyingPrices.accompanyingPrices().size());
		assertEquals(expectedPriceWithoutVat, priceForSale.priceForSale().priceWithoutTax());
		for (int i = 0; i < expectedAccompaniedPrices.length; i++) {
			BigDecimal expectedAccompaniedPrice = expectedAccompaniedPrices[i];
			final AccompanyingPrice accompanyingPrice = accompanyingPrices[i];
			if (expectedAccompaniedPrice == null) {
				assertTrue(
					priceForSaleWithAccompanyingPrices.accompanyingPrices().get(accompanyingPrice.priceName()).isEmpty()
				);
			} else {
				assertEquals(
					expectedAccompaniedPrice,
					priceForSaleWithAccompanyingPrices.accompanyingPrices().get(accompanyingPrice.priceName()).orElseThrow().priceWithoutTax()
				);
			}
		}

		final List<PriceForSaleWithAccompanyingPrices> allPricesResult = prices.getAllPricesForSaleWithAccompanyingPrices(
			currency, moment, convertToClassifiers(priceLists), accompanyingPrices
		);
		assertEquals(1, allPricesResult.size());
		assertEquals(priceForSaleWithAccompanyingPrices, allPricesResult.get(0));
	}

	/**
	 * Asserts that no price for sale is available for the given parameters.
	 *
	 * @param prices the prices contract to check
	 * @param currency the currency to check prices in
	 * @param moment the moment in time to check prices at
	 * @param priceLists the price lists to check prices in
	 */
	private static void assertNoPriceForSell(PricesContract prices, Currency currency, OffsetDateTime moment, String... priceLists) {
		final PriceContract priceForSale = prices.getPriceForSale(
			currency, moment, convertToClassifiers(priceLists)
		).orElse(null);

		assertNull(priceForSale);
	}

	/**
	 * Converts price list names to classifier strings.
	 * This is a utility method to prepare price list names for use in price queries.
	 *
	 * @param priceLists the price list names to convert
	 * @return the converted classifier strings
	 */
	@Nonnull
	private static String[] convertToClassifiers(@Nonnull String[] priceLists) {
		return Arrays.stream(priceLists).toArray(String[]::new);
	}

	/**
	 * Combines an inner record ID with a price ID to create a composite ID.
	 * If innerRecordId is null, returns the original ID.
	 * Otherwise, multiplies innerRecordId by 1,000,000 and adds the price ID.
	 *
	 * @param innerRecordId the inner record ID, or null if not applicable
	 * @param id the price ID to combine
	 * @return the combined ID
	 */
	private static int combineIntoId(Integer innerRecordId, int id) {
		return ofNullable(innerRecordId).map(it -> innerRecordId * 1_000_000 + id).orElse(id);
	}

	@Test
	void shouldReturnPriceForSaleForNoneStrategy() {
		final PricesContract prices = new Prices(
			PRODUCT_SCHEMA,
			1,
			Arrays.asList(
				createStandardPriceSetWithMultiplier(null, BigDecimal.ONE)
			),
			PriceInnerRecordHandling.NONE,
			true
		);

		// reference price is not indexed, vip price has not fulfilled validity, logged only is the first
		assertPriceForSale(3, null, prices, CZK, MOMENT_2020, REFERENCE, VIP, LOGGED_ONLY, BASIC);
		// reference price is not indexed, vip price has not fulfilled validity, basic is the first
		assertPriceForSale(1, null, prices, CZK, MOMENT_2020, REFERENCE, VIP, BASIC, LOGGED_ONLY);
		// reference price is not indexed, vip price has not fulfilled validity - no other available
		assertNoPriceForSell(prices, CZK, MOMENT_2020, REFERENCE, VIP);
		// reference price is not indexed, vip price has fulfilled validity and is first
		assertPriceForSale(5, null, prices, CZK, MOMENT_2011, REFERENCE, VIP, LOGGED_ONLY, BASIC);
		// reference price is not indexed, vip price has fulfilled validity but is last
		assertPriceForSale(3, null, prices, CZK, MOMENT_2011, REFERENCE, LOGGED_ONLY, BASIC, VIP);
		// reference price is not indexed, vip price has not fulfilled validity, logged only is the first - we ask for EUR
		assertPriceForSale(4, null, prices, EUR, MOMENT_2020, REFERENCE, VIP, LOGGED_ONLY, BASIC);
		// reference price is not indexed, vip price has not fulfilled validity, logged only is the first - we ask for GBP
		assertNoPriceForSell(prices, GBP, MOMENT_2011, REFERENCE, VIP, LOGGED_ONLY, BASIC);
	}

	/**
	 * Tests retrieval of default accompanying price when a price for sale context is available.
	 * Verifies that the correct price is returned based on the context.
	 */
	@Test
	@DisplayName("Default accompanying price retrieval")
	void shouldReturnDefaultAccompanyingPrice() {
		final PricesContract prices = Mockito.spy(
			new Prices(
				PRODUCT_SCHEMA,
				1,
				Arrays.asList(
					createStandardPriceSetWithMultiplier(null, BigDecimal.ONE)
				),
				PriceInnerRecordHandling.NONE,
				true
			)
		);
		Mockito.when(prices.getPriceForSaleContext())
			.thenReturn(
			Optional.of(
				new PriceForSaleContextWithCachedResult(
					new String[]{BASIC},
					CZK,
					MOMENT_2020,
					new AccompanyingPrice[] {
						new AccompanyingPrice(
							AccompanyingPriceContent.DEFAULT_ACCOMPANYING_PRICE,
							REFERENCE, VIP
						)
					}
				)
			)
		);

		// reference price is not indexed, vip price has not fulfilled validity, logged only is the first
		assertEquals(1, prices.getPriceForSale().orElseThrow().priceId());
		assertEquals(7, prices.getAccompanyingPrice().orElseThrow().priceId());
	}

	/**
	 * Tests retrieval of default accompanying price when a price for sale context is available.
	 * Verifies that no price is returned based on the context when not requested (even if present in cache).
	 */
	@Test
	@DisplayName("Default accompanying price retrieval")
	void shouldReturnNoAccompanyingPrice() {
		final PricesContract prices = Mockito.spy(
			new Prices(
				PRODUCT_SCHEMA,
				1,
				Arrays.asList(
					createStandardPriceSetWithMultiplier(null, BigDecimal.ONE)
				),
				PriceInnerRecordHandling.NONE,
				true
			)
		);
		Mockito.when(prices.getPriceForSaleContext())
			.thenReturn(
				Optional.of(
					new PriceForSaleContextWithCachedResult(
						new String[]{BASIC},
						CZK,
						MOMENT_2020,
						new AccompanyingPrice[] {
							new AccompanyingPrice(
								AccompanyingPriceContent.DEFAULT_ACCOMPANYING_PRICE,
								REFERENCE, VIP
							)
						}
					)
				)
			);

		// reference price is not indexed, vip price has not fulfilled validity, logged only is the first
		final PriceForSaleWithAccompanyingPrices calculatedPrice = prices.getPriceForSaleWithAccompanyingPrices(
			CZK, MOMENT_2020, new String[]{BASIC},
			PricesContract.NO_ACCOMPANYING_PRICES
		).orElseThrow();

		assertEquals(1, calculatedPrice.priceForSale().priceId());
		assertTrue(calculatedPrice.accompanyingPrices().isEmpty());
	}

	/**
	 * Tests that when a different accompanying price is requested (different from what's cached),
	 * it's calculated correctly.
	 */
	@Test
	@DisplayName("Different accompanying price calculation")
	void shouldCalculateDifferentAccompanyingPrice() {
		final PricesContract prices = Mockito.spy(
			new Prices(
				PRODUCT_SCHEMA,
				1,
				Arrays.asList(
					createStandardPriceSetWithMultiplier(null, BigDecimal.ONE)
				),
				PriceInnerRecordHandling.NONE,
				true
			)
		);
		Mockito.when(prices.getPriceForSaleContext())
			.thenReturn(
				Optional.of(
					new PriceForSaleContextWithCachedResult(
						new String[]{BASIC},
						CZK,
						MOMENT_2020,
						new AccompanyingPrice[] {
							new AccompanyingPrice(
								AccompanyingPriceContent.DEFAULT_ACCOMPANYING_PRICE,
								REFERENCE, VIP
							)
						}
					)
				)
			);

		// Request a different accompanying price than what's cached
		final PriceForSaleWithAccompanyingPrices calculatedPrice = prices.getPriceForSaleWithAccompanyingPrices(
			CZK, MOMENT_2020, new String[]{BASIC},
			new AccompanyingPrice[] {
				new AccompanyingPrice("differentPrice", LOGGED_ONLY)
			}
		).orElseThrow();

		assertEquals(1, calculatedPrice.priceForSale().priceId());
		assertEquals(1, calculatedPrice.accompanyingPrices().size());
		assertEquals(3, calculatedPrice.accompanyingPrices().get("differentPrice").orElseThrow().priceId());
	}

	/**
	 * Tests that when a different accompanying price is requested with the same name but different price lists,
	 * it's calculated correctly.
	 */
	@Test
	@DisplayName("Same name but different price lists calculation")
	void shouldCalculateSameNameDifferentPriceLists() {
		final PricesContract prices = Mockito.spy(
			new Prices(
				PRODUCT_SCHEMA,
				1,
				Arrays.asList(
					createStandardPriceSetWithMultiplier(null, BigDecimal.ONE)
				),
				PriceInnerRecordHandling.NONE,
				true
			)
		);

		// Cache has "testPrice" with REFERENCE, VIP price lists
		Mockito.when(prices.getPriceForSaleContext())
			.thenReturn(
				Optional.of(
					new PriceForSaleContextWithCachedResult(
						new String[]{BASIC},
						CZK,
						MOMENT_2020,
						new AccompanyingPrice[] {
							new AccompanyingPrice("testPrice", REFERENCE, VIP)
						}
					)
				)
			);

		// Request "testPrice" but with LOGGED_ONLY price list instead
		final PriceForSaleWithAccompanyingPrices calculatedPrice = prices.getPriceForSaleWithAccompanyingPrices(
			CZK, MOMENT_2020, new String[]{BASIC},
			new AccompanyingPrice[] {
				new AccompanyingPrice("testPrice", LOGGED_ONLY)
			}
		).orElseThrow();

		assertEquals(1, calculatedPrice.priceForSale().priceId());
		assertEquals(1, calculatedPrice.accompanyingPrices().size());
		// Should get price from LOGGED_ONLY price list (ID 3), not from REFERENCE (ID 7)
		assertEquals(3, calculatedPrice.accompanyingPrices().get("testPrice").orElseThrow().priceId());
	}

	/**
	 * Tests that when part of the cached prices and requested accompanying prices match,
	 * the cached values are reused, but missing requested accompanying prices are calculated.
	 */
	@Test
	@DisplayName("Partial cache reuse with new calculations")
	void shouldReusePartialCacheAndCalculateRest() {
		final PricesContract prices = Mockito.spy(
			new Prices(
				PRODUCT_SCHEMA,
				1,
				Arrays.asList(
					createStandardPriceSetWithMultiplier(null, BigDecimal.ONE)
				),
				PriceInnerRecordHandling.NONE,
				true
			)
		);

		// Cache has two prices: "cachedPrice1" and "cachedPrice2"
		Mockito.when(prices.getPriceForSaleContext())
			.thenReturn(
				Optional.of(
					new PriceForSaleContextWithCachedResult(
						new String[]{BASIC},
						CZK,
						MOMENT_2020,
						new AccompanyingPrice[] {
							new AccompanyingPrice("cachedPrice1", REFERENCE),
							new AccompanyingPrice("cachedPrice2", VIP)
						}
					)
				)
			);

		// Request "cachedPrice1" (should be reused from cache) and "newPrice" (should be calculated)
		final PriceForSaleWithAccompanyingPrices calculatedPrice = prices.getPriceForSaleWithAccompanyingPrices(
			CZK, MOMENT_2020, new String[]{BASIC},
			new AccompanyingPrice[] {
				new AccompanyingPrice("cachedPrice1", REFERENCE),
				new AccompanyingPrice("newPrice", LOGGED_ONLY)
			}
		).orElseThrow();

		assertEquals(1, calculatedPrice.priceForSale().priceId());
		assertEquals(2, calculatedPrice.accompanyingPrices().size());

		// cachedPrice1 should be reused from cache (REFERENCE price list, ID 7)
		assertEquals(7, calculatedPrice.accompanyingPrices().get("cachedPrice1").orElseThrow().priceId());

		// newPrice should be calculated (LOGGED_ONLY price list, ID 3)
		assertEquals(3, calculatedPrice.accompanyingPrices().get("newPrice").orElseThrow().priceId());
	}

	/**
	 * Tests retrieval of accompanying prices with NONE inner record handling strategy.
	 * Verifies that correct accompanying prices are returned for different price lists.
	 */
	@Test
	@DisplayName("Accompanying prices with NONE strategy")
	void shouldReturnAccompanyingPricesForSaleForNoneStrategy() {
		final PricesContract prices = new Prices(
			PRODUCT_SCHEMA,
			1,
			Arrays.asList(
				createStandardPriceSetWithMultiplier(null, BigDecimal.ONE)
			),
			PriceInnerRecordHandling.NONE,
			true
		);

		// reference price is not indexed, vip price has not fulfilled validity, logged only is the first
		assertPriceForSaleWithAccompanyingPrices(
			1, new int[]{7, 7, 3, -1}, null, prices, CZK, MOMENT_2020, new String[]{BASIC},
			new AccompanyingPrice[]{
				new AccompanyingPrice("p1", REFERENCE),
				new AccompanyingPrice("p2", REFERENCE, VIP),
				new AccompanyingPrice("p3", LOGGED_ONLY, VIP),
				new AccompanyingPrice("p", VIP)
			}
		);
	}

	/**
	 * Tests price selection with LOWEST_PRICE inner record handling strategy.
	 * This strategy selects the price with the lowest value among all inner records.
	 */
	@Test
	@DisplayName("Price selection with LOWEST_PRICE strategy")
	void shouldReturnPriceForSaleForLowestPrice() {
		final PricesContract prices = new Prices(
			PRODUCT_SCHEMA,
			1,
			Arrays.asList(
				ArrayUtils.mergeArrays(
					createStandardPriceSetWithMultiplier(1, BigDecimal.ONE),
					createStandardPriceSetWithMultiplier(2, new BigDecimal("2")),
					createStandardPriceSetWithMultiplier(3, new BigDecimal("0.5"))
				)
			),
			PriceInnerRecordHandling.LOWEST_PRICE,
			true
		);

		// Variant 3 has the smallest multiplier (0.5), so its prices will always be the lowest
		// and will take precedence in the LOWEST_PRICE strategy

		// reference price is not indexed, vip price has not fulfilled validity, logged only is the first
		assertPriceForSale(3, 3, prices, CZK, MOMENT_2020, REFERENCE, VIP, LOGGED_ONLY, BASIC);
		// reference price is not indexed, vip price has not fulfilled validity, basic is the first
		assertPriceForSale(1, 3, prices, CZK, MOMENT_2020, REFERENCE, VIP, BASIC, LOGGED_ONLY);
		// reference price is not indexed, vip price has not fulfilled validity - no other available
		assertNoPriceForSell(prices, CZK, MOMENT_2020, REFERENCE, VIP);
		// reference price is not indexed, vip price has fulfilled validity and is first
		assertPriceForSale(5, 3, prices, CZK, MOMENT_2011, REFERENCE, VIP, LOGGED_ONLY, BASIC);
		// reference price is not indexed, vip price has fulfilled validity but is last
		assertPriceForSale(3, 3, prices, CZK, MOMENT_2011, REFERENCE, LOGGED_ONLY, BASIC, VIP);
		// reference price is not indexed, vip price has not fulfilled validity, logged only is the first - we ask for EUR
		assertPriceForSale(4, 3, prices, EUR, MOMENT_2020, REFERENCE, VIP, LOGGED_ONLY, BASIC);
		// reference price is not indexed, vip price has not fulfilled validity, logged only is the first - we ask for GBP
		assertNoPriceForSell(prices, GBP, MOMENT_2011, REFERENCE, VIP, LOGGED_ONLY, BASIC);
	}

	/**
	 * Tests retrieval of accompanying prices with LOWEST_PRICE inner record handling strategy.
	 * Verifies that correct accompanying prices are returned for the lowest price variant.
	 */
	@Test
	@DisplayName("Accompanying prices with LOWEST_PRICE strategy")
	void shouldReturnAccompanyingPricesForSaleForLowestPriceStrategy() {
		final PricesContract prices = new Prices(
			PRODUCT_SCHEMA,
			1,
			Arrays.asList(
				ArrayUtils.mergeArrays(
					createStandardPriceSetWithMultiplier(1, BigDecimal.ONE),
					createStandardPriceSetWithMultiplier(2, new BigDecimal("2")),
					createStandardPriceSetWithMultiplier(3, new BigDecimal("0.5"))
				)
			),
			PriceInnerRecordHandling.LOWEST_PRICE,
			true
		);

		// variant 3 has smallest multiplier -> prices, it will always take precedence

		// reference price is not indexed, vip price has not fulfilled validity, logged only is the first
		assertPriceForSaleWithAccompanyingPrices(
			1, new int[]{7, 7, 3, -1}, 3, prices, CZK, MOMENT_2020, new String[]{BASIC},
			new AccompanyingPrice[]{
				new AccompanyingPrice("p1", REFERENCE),
				new AccompanyingPrice("p2", REFERENCE, VIP),
				new AccompanyingPrice("p3", LOGGED_ONLY, VIP),
				new AccompanyingPrice("p", VIP)
			}
		);

		// verify all prices for sale with accompanying prices
		final List<PriceForSaleWithAccompanyingPrices> allPrices = prices.getAllPricesForSaleWithAccompanyingPrices(
			CZK, MOMENT_2020, new String[]{BASIC},
			new AccompanyingPrice[]{
				new AccompanyingPrice("p1", REFERENCE),
				new AccompanyingPrice("p2", REFERENCE, VIP),
				new AccompanyingPrice("p3", LOGGED_ONLY, VIP),
				new AccompanyingPrice("p", VIP)
			}
		);

		assertEquals(3, allPrices.size());
		for (PriceForSaleWithAccompanyingPrices allPrice : allPrices) {
			final Map<String, Optional<PriceContract>> accompanyingPrices = allPrice.accompanyingPrices();
			assertEquals(4, accompanyingPrices.size());
			final int mainPriceId = allPrice.priceForSale().innerRecordId();
			assertEquals(1000000 * mainPriceId + 7, accompanyingPrices.get("p1").map(PriceContract::priceId).orElse(null));
			assertEquals(1000000 * mainPriceId + 7, accompanyingPrices.get("p2").map(PriceContract::priceId).orElse(null));
			assertEquals(1000000 * mainPriceId + 3, accompanyingPrices.get("p3").map(PriceContract::priceId).orElse(null));
			assertNull(accompanyingPrices.get("p").map(PriceContract::priceId).orElse(null));
		}
	}

	/**
	 * Tests retrieval of accompanying prices with LOWEST_PRICE strategy when one product has a price
	 * in a price list with higher priority but lower price value.
	 * Verifies the correct price selection based on the lowest price value.
	 */
	@Test
	@DisplayName("Accompanying prices with priority vs price value conflict")
	void shouldReturnAccompanyingPricesForSaleForLowestPriceStrategyWhenOneProductHasPriceInPriceListWithBiggerPriorityButLowerPrice() {
		final PricesContract prices = new Prices(
			PRODUCT_SCHEMA,
			1,
			Arrays.asList(
				// Inner record 1 prices
				new Price(new PriceKey(combineIntoId(1, 1), BASIC, CZK), 1, new BigDecimal("100"), new BigDecimal("21"), new BigDecimal("121"), null, true),
				new Price(new PriceKey(combineIntoId(1, 2), LOGGED_ONLY, CZK), 1, new BigDecimal("80"), new BigDecimal("21"), new BigDecimal("96.8"), null, true),
				new Price(new PriceKey(combineIntoId(1, 3), VIP, CZK), 1, new BigDecimal("140"), new BigDecimal("21"), new BigDecimal("169.4"), null, false),
				// Inner record 2 prices - has the lowest price (50) in LOGGED_ONLY price list
				new Price(new PriceKey(combineIntoId(2, 4), BASIC, CZK), 2, new BigDecimal("60"), new BigDecimal("21"), new BigDecimal("72.6"), null, true),
				new Price(new PriceKey(combineIntoId(2, 5), LOGGED_ONLY, CZK), 2, new BigDecimal("50"), new BigDecimal("21"), new BigDecimal("60.5"), null, true),
				// Inner record 3 prices
				new Price(new PriceKey(combineIntoId(3, 6), BASIC, CZK), 3, new BigDecimal("90"), new BigDecimal("21"), new BigDecimal("108.9"), null, true),
				new Price(new PriceKey(combineIntoId(3, 7), LOGGED_ONLY, CZK), 3, new BigDecimal("70"), new BigDecimal("21"), new BigDecimal("84.7"), null, true)
			),
			PriceInnerRecordHandling.LOWEST_PRICE,
			true
		);

		// Price with ID 5 is the lowest price (50) from all allowed price lists across all inner records
		assertPriceForSale(5, 2, prices, CZK, null, VIP, LOGGED_ONLY, BASIC);
	}

	/**
	 * Tests price selection with LOWEST_PRICE strategy when one product has a price
	 * in a price list with higher priority but lower price value.
	 * Verifies the correct price selection and retrieval of all prices for sale.
	 */
	@Test
	@DisplayName("Price selection with priority vs price value conflict")
	void shouldReturnPriceForSaleForLowestPriceWhenOneProductHasPriceInPriceListWithBiggerPriorityButLowerPrice() {
		final PricesContract prices = new Prices(
			PRODUCT_SCHEMA,
			1,
			Arrays.asList(
				// Inner record 1 prices
				new Price(new PriceKey(combineIntoId(1, 1), BASIC, CZK), 1, new BigDecimal("100"), new BigDecimal("21"), new BigDecimal("121"), null, true),
				new Price(new PriceKey(combineIntoId(1, 2), LOGGED_ONLY, CZK), 1, new BigDecimal("80"), new BigDecimal("21"), new BigDecimal("96.8"), null, true),
				new Price(new PriceKey(combineIntoId(1, 3), VIP, CZK), 1, new BigDecimal("140"), new BigDecimal("21"), new BigDecimal("169.4"), null, true),
				// Inner record 2 prices - has the lowest price (50) in LOGGED_ONLY price list
				new Price(new PriceKey(combineIntoId(2, 4), BASIC, CZK), 2, new BigDecimal("60"), new BigDecimal("21"), new BigDecimal("72.6"), null, true),
				new Price(new PriceKey(combineIntoId(2, 5), LOGGED_ONLY, CZK), 2, new BigDecimal("50"), new BigDecimal("21"), new BigDecimal("60.5"), null, true),
				// Inner record 3 prices
				new Price(new PriceKey(combineIntoId(3, 6), BASIC, CZK), 3, new BigDecimal("90"), new BigDecimal("21"), new BigDecimal("108.9"), null, true),
				new Price(new PriceKey(combineIntoId(3, 7), LOGGED_ONLY, CZK), 3, new BigDecimal("70"), new BigDecimal("21"), new BigDecimal("84.7"), null, true),
				// Reference prices for all inner records (all have the same value of 10)
				new Price(new PriceKey(combineIntoId(1, 8), REFERENCE, CZK), 1, new BigDecimal("10"), new BigDecimal("21"), new BigDecimal("12.1"), null, true),
				new Price(new PriceKey(combineIntoId(2, 9), REFERENCE, CZK), 2, new BigDecimal("10"), new BigDecimal("21"), new BigDecimal("12.1"), null, true),
				new Price(new PriceKey(combineIntoId(3, 10), REFERENCE, CZK), 3, new BigDecimal("10"), new BigDecimal("21"), new BigDecimal("12.1"), null, true)
			),
			PriceInnerRecordHandling.LOWEST_PRICE,
			true
		);

		// Price with ID 5 is the lowest price (50) from all allowed price lists across all inner records
		assertPriceForSaleWithAccompanyingPrices(
			5, new int[]{9, 9, 5, -1}, 2, prices, CZK, null, new String[]{VIP, LOGGED_ONLY, BASIC},
			new AccompanyingPrice[]{
				new AccompanyingPrice("p1", REFERENCE),
				new AccompanyingPrice("p2", REFERENCE, VIP),
				new AccompanyingPrice("p3", LOGGED_ONLY, VIP),
				new AccompanyingPrice("p", VIP)
			}
		);

		// verify all prices for sale with accompanying prices
		final List<PriceForSaleWithAccompanyingPrices> allPrices = prices.getAllPricesForSaleWithAccompanyingPrices(
			CZK, null, new String[]{VIP, LOGGED_ONLY, BASIC},
			new AccompanyingPrice[]{
				new AccompanyingPrice("p0", REFERENCE),
				new AccompanyingPrice("p1", REFERENCE, VIP),
				new AccompanyingPrice("p2", LOGGED_ONLY, VIP),
				new AccompanyingPrice("p3", VIP)
			}
		);

		assertEquals(3, allPrices.size());

		assertAccompanyingPrices(allPrices.get(0), combineIntoId(1, 8), combineIntoId(1, 8), combineIntoId(1, 2), combineIntoId(1, 3));
		assertAccompanyingPrices(allPrices.get(1), combineIntoId(2, 9), combineIntoId(2, 9), combineIntoId(2, 5), -1);
		assertAccompanyingPrices(allPrices.get(2), combineIntoId(3, 10), combineIntoId(3, 10), combineIntoId(3, 7), -1);
	}

	/**
	 * Tests price selection with SUM inner record handling strategy.
	 * This strategy sums up prices from all inner records to calculate the final price.
	 */
	@Test
	@DisplayName("Price selection with SUM strategy")
	void shouldReturnPriceForSaleForSum() {
		final PricesContract prices = new Prices(
			PRODUCT_SCHEMA,
			1,
			Arrays.asList(
				ArrayUtils.mergeArrays(
					createStandardPriceSetWithMultiplier(1, BigDecimal.ONE),
					createStandardPriceSetWithMultiplier(2, new BigDecimal("2")),
					createStandardPriceSetWithMultiplier(3, new BigDecimal("0.5"))
				)
			),
 		// SUM strategy adds up prices from all inner records for the final price calculation
 		PriceInnerRecordHandling.SUM,
 		true
 	);

 	// In this test case:
 	// - reference price is not indexed (sellable=false)
 	// - vip price has not fulfilled validity (outside valid time range)
 	// - logged_only is the first price list in the priority order
 	// Price calculation: 80 + 80 * 2 + 80 * 0.5 = 280 (sum of prices from all inner records)
		assertPriceForSale(new BigDecimal("280.0"), prices, CZK, MOMENT_2020, REFERENCE, VIP, LOGGED_ONLY, BASIC);
		// reference price is not indexed, vip price has not fulfilled validity, basic is the first
		// 100 + 100 * 2 + 100 * 0.5 = 350
		assertPriceForSale(new BigDecimal("350.0"), prices, CZK, MOMENT_2020, REFERENCE, VIP, BASIC, LOGGED_ONLY);
		// reference price is not indexed, vip price has not fulfilled validity - no other available
		assertNoPriceForSell(prices, CZK, MOMENT_2020, REFERENCE, VIP);
		// reference price is not indexed, vip price has fulfilled validity and is first
		// 60 + 60 * 2 + 60 * 0.5 = 210
		assertPriceForSale(new BigDecimal("210.0"), prices, CZK, MOMENT_2011, REFERENCE, VIP, LOGGED_ONLY, BASIC);
		// reference price is not indexed, vip price has fulfilled validity but is last
		// 80 + 80 * 2 + 80 * 0.5 = 280
		assertPriceForSale(new BigDecimal("280.0"), prices, CZK, MOMENT_2011, REFERENCE, LOGGED_ONLY, BASIC, VIP);
		// reference price is not indexed, vip price has not fulfilled validity, logged only is the first - we ask for EUR
		// 8 + 8 * 2 + 8 * 0.5 = 28
		assertPriceForSale(new BigDecimal("28.0"), prices, EUR, MOMENT_2020, REFERENCE, VIP, LOGGED_ONLY, BASIC);
		// reference price is not indexed, vip price has not fulfilled validity, logged only is the first - we ask for GBP
		assertNoPriceForSell(prices, GBP, MOMENT_2011, REFERENCE, VIP, LOGGED_ONLY, BASIC);
	}

	/**
	 * Tests retrieval of accompanying prices with SUM inner record handling strategy.
	 * Verifies that correct accompanying prices are returned when summing prices from all inner records.
	 */
	@Test
	@DisplayName("Accompanying prices with SUM strategy")
	void shouldReturnAccompanyingPricesForSaleForSumStrategy() {
		final PricesContract prices = new Prices(
			PRODUCT_SCHEMA,
			1,
			Arrays.asList(
				ArrayUtils.mergeArrays(
					createStandardPriceSetWithMultiplier(1, BigDecimal.ONE),
					createStandardPriceSetWithMultiplier(2, new BigDecimal("2")),
					createStandardPriceSetWithMultiplier(3, new BigDecimal("0.5"))
				)
			),
			PriceInnerRecordHandling.SUM,
			true
		);

		// reference price is not indexed, vip price has not fulfilled validity, logged only is the first
		assertPriceForSaleWithAccompanyingPrices(
			new BigDecimal("280.0"),
			new BigDecimal[]{
				// 140 + 140 * 2 + 140 * 0.5 = 490
				new BigDecimal("490.0"),
				new BigDecimal("490.0"),
				// 80 + 80 * 2 + 80 * 0.5 = 280
				new BigDecimal("280.0"),
				null
			},
			prices, CZK, MOMENT_2020,
			new String[]{REFERENCE, VIP, LOGGED_ONLY, BASIC},
			new AccompanyingPrice[]{
				new AccompanyingPrice("p1", REFERENCE),
				new AccompanyingPrice("p2", REFERENCE, VIP),
				new AccompanyingPrice("p3", LOGGED_ONLY, VIP),
				new AccompanyingPrice("p", VIP)
			}
		);
	}

	/**
	 * Tests price range checking with NONE inner record handling strategy.
	 * Verifies that the system correctly determines if prices fall within specified ranges.
	 */
	@Test
	@DisplayName("Price range checking with NONE strategy")
	void shouldResolveHavingPriceInRangeForNoneStrategy() {
		final PricesContract prices = new Prices(
			PRODUCT_SCHEMA,
			1,
			Arrays.asList(
				createStandardPriceSetWithMultiplier(null, BigDecimal.ONE)
			),
			PriceInnerRecordHandling.NONE,
			true
		);

		// reference price is not indexed, vip price has not fulfilled validity, logged only is the first
		assertHasPriceInRange(new BigDecimal("75"), new BigDecimal("85"), prices, CZK, MOMENT_2020, REFERENCE, VIP, LOGGED_ONLY, BASIC);
		assertHasNotPriceInRange(new BigDecimal("85"), new BigDecimal("200"), prices, CZK, MOMENT_2020, REFERENCE, VIP, LOGGED_ONLY, BASIC);
		// reference price is not indexed, vip price has not fulfilled validity, basic is the first
		assertHasPriceInRange(new BigDecimal("95"), new BigDecimal("105"), prices, CZK, MOMENT_2020, REFERENCE, VIP, BASIC, LOGGED_ONLY);
		assertHasNotPriceInRange(new BigDecimal("105"), new BigDecimal("200"), prices, CZK, MOMENT_2020, REFERENCE, VIP, BASIC, LOGGED_ONLY);
		// reference price is not indexed, vip price has not fulfilled validity - no other available
		assertHasNotPriceInRange(new BigDecimal("40"), new BigDecimal("80"), prices, CZK, MOMENT_2020, REFERENCE, VIP);
		// reference price is not indexed, vip price has fulfilled validity and is first
		assertHasPriceInRange(new BigDecimal("55"), new BigDecimal("65"), prices, CZK, MOMENT_2011, REFERENCE, VIP, LOGGED_ONLY, BASIC);
		assertHasNotPriceInRange(new BigDecimal("65"), new BigDecimal("75"), prices, CZK, MOMENT_2011, REFERENCE, VIP, LOGGED_ONLY, BASIC);
		// reference price is not indexed, vip price has fulfilled validity but is last
		assertHasPriceInRange(new BigDecimal("75"), new BigDecimal("85"), prices, CZK, MOMENT_2011, REFERENCE, LOGGED_ONLY, BASIC, VIP);
		assertHasNotPriceInRange(new BigDecimal("85"), new BigDecimal("100"), prices, CZK, MOMENT_2011, REFERENCE, LOGGED_ONLY, BASIC, VIP);
		// reference price is not indexed, vip price has not fulfilled validity, logged only is the first - we ask for EUR
		assertHasPriceInRange(new BigDecimal("7"), new BigDecimal("9"), prices, EUR, MOMENT_2020, REFERENCE, VIP, LOGGED_ONLY, BASIC);
		assertHasNotPriceInRange(new BigDecimal("9"), new BigDecimal("100"), prices, EUR, MOMENT_2020, REFERENCE, VIP, LOGGED_ONLY, BASIC);
		// reference price is not indexed, vip price has not fulfilled validity, logged only is the first - we ask for GBP
		assertHasNotPriceInRange(new BigDecimal("0"), new BigDecimal("500"), prices, GBP, MOMENT_2011, REFERENCE, VIP, LOGGED_ONLY, BASIC);
	}

	/**
	 * Tests price range checking with LOWEST_PRICE inner record handling strategy.
	 * Verifies that the system correctly determines if prices fall within specified ranges
	 * when using the first occurrence strategy.
	 */
	@Test
	@DisplayName("Price range checking with LOWEST_PRICE strategy")
	void shouldResolveHavingPriceInRangeForFirstOccurrence() {
		final PricesContract prices = new Prices(
			PRODUCT_SCHEMA,
			1,
			Arrays.asList(
				ArrayUtils.mergeArrays(
					createStandardPriceSetWithMultiplier(1, BigDecimal.ONE),
					createStandardPriceSetWithMultiplier(2, new BigDecimal("2")),
					createStandardPriceSetWithMultiplier(3, new BigDecimal("0.5"))
				)
			),
			PriceInnerRecordHandling.LOWEST_PRICE,
			true
		);

		// variant 3 has smallest multiplier -> prices, it will always take precedence
		final BigDecimal[] multipliers = new BigDecimal[]{
			BigDecimal.ONE,
			new BigDecimal("2"),
			new BigDecimal("0.5")
		};

		// reference price is not indexed, vip price has not fulfilled validity, logged only is the first
		assertHasPriceInRangeWithMultiplier(new BigDecimal("75"), new BigDecimal("85"), multipliers, prices, CZK, MOMENT_2020, REFERENCE, VIP, LOGGED_ONLY, BASIC);
		assertHasNotPriceInRange(new BigDecimal("85"), new BigDecimal("95"), prices, CZK, MOMENT_2020, REFERENCE, VIP, LOGGED_ONLY, BASIC);
		// reference price is not indexed, vip price has not fulfilled validity, basic is the first
		assertHasPriceInRangeWithMultiplier(new BigDecimal("95"), new BigDecimal("105"), multipliers, prices, CZK, MOMENT_2020, REFERENCE, VIP, BASIC, LOGGED_ONLY);
		assertHasNotPriceInRange(new BigDecimal("105"), new BigDecimal("110"), prices, CZK, MOMENT_2020, REFERENCE, VIP, BASIC, LOGGED_ONLY);
		// reference price is not indexed, vip price has not fulfilled validity - no other available
		assertHasNotPriceInRange(new BigDecimal("40"), new BigDecimal("80"), prices, CZK, MOMENT_2020, REFERENCE, VIP);
		// reference price is not indexed, vip price has fulfilled validity and is first
		assertHasPriceInRangeWithMultiplier(new BigDecimal("55"), new BigDecimal("65"), multipliers, prices, CZK, MOMENT_2011, REFERENCE, VIP, LOGGED_ONLY, BASIC);
		assertHasNotPriceInRange(new BigDecimal("65"), new BigDecimal("75"), prices, CZK, MOMENT_2011, REFERENCE, VIP, LOGGED_ONLY, BASIC);
		// reference price is not indexed, vip price has fulfilled validity but is last
		assertHasPriceInRangeWithMultiplier(new BigDecimal("75"), new BigDecimal("85"), multipliers, prices, CZK, MOMENT_2011, REFERENCE, LOGGED_ONLY, BASIC, VIP);
		assertHasNotPriceInRange(new BigDecimal("85"), new BigDecimal("100"), prices, CZK, MOMENT_2011, REFERENCE, LOGGED_ONLY, BASIC, VIP);
		// reference price is not indexed, vip price has not fulfilled validity, logged only is the first - we ask for EUR
		assertHasPriceInRangeWithMultiplier(new BigDecimal("7"), new BigDecimal("9"), multipliers, prices, EUR, MOMENT_2020, REFERENCE, VIP, LOGGED_ONLY, BASIC);
		assertHasNotPriceInRange(new BigDecimal("9"), new BigDecimal("12"), prices, EUR, MOMENT_2020, REFERENCE, VIP, LOGGED_ONLY, BASIC);
		// reference price is not indexed, vip price has not fulfilled validity, logged only is the first - we ask for GBP
		assertHasNotPriceInRange(new BigDecimal("0"), new BigDecimal("500"), prices, GBP, MOMENT_2011, REFERENCE, VIP, LOGGED_ONLY, BASIC);
	}

	/**
	 * Tests price range checking with SUM inner record handling strategy.
	 * Verifies that the system correctly determines if summed prices fall within specified ranges.
	 */
	@Test
	@DisplayName("Price range checking with SUM strategy")
	void shouldResolveHavingPriceInRangeForSum() {
		final PricesContract prices = new Prices(
			PRODUCT_SCHEMA,
			1,
			Arrays.asList(
				ArrayUtils.mergeArrays(
					createStandardPriceSetWithMultiplier(1, BigDecimal.ONE),
					createStandardPriceSetWithMultiplier(2, new BigDecimal("2")),
					createStandardPriceSetWithMultiplier(3, new BigDecimal("0.5"))
				)
			),
			PriceInnerRecordHandling.SUM,
			true
		);

		// reference price is not indexed, vip price has not fulfilled validity, logged only is the first
		// 80 + 80 * 2 + 80 * 0.5 = 280
		assertHasPriceInRange(new BigDecimal("275.0"), new BigDecimal("285.0"), prices, CZK, MOMENT_2020, REFERENCE, VIP, LOGGED_ONLY, BASIC);
		assertHasNotPriceInRange(new BigDecimal("285.0"), new BigDecimal("300.0"), prices, CZK, MOMENT_2020, REFERENCE, VIP, LOGGED_ONLY, BASIC);
		// reference price is not indexed, vip price has not fulfilled validity, basic is the first
		// 100 + 100 * 2 + 100 * 0.5 = 350
		assertHasPriceInRange(new BigDecimal("345.0"), new BigDecimal("355.0"), prices, CZK, MOMENT_2020, REFERENCE, VIP, BASIC, LOGGED_ONLY);
		assertHasNotPriceInRange(new BigDecimal("355.0"), new BigDecimal("370.0"), prices, CZK, MOMENT_2020, REFERENCE, VIP, BASIC, LOGGED_ONLY);
		// reference price is not indexed, vip price has not fulfilled validity - no other available
		assertHasNotPriceInRange(new BigDecimal("0"), new BigDecimal("700.0"), prices, CZK, MOMENT_2020, REFERENCE, VIP);
		// reference price is not indexed, vip price has fulfilled validity and is first
		// 60 + 60 * 2 + 60 * 0.5 = 210
		assertHasPriceInRange(new BigDecimal("205.0"), new BigDecimal("215.0"), prices, CZK, MOMENT_2011, REFERENCE, VIP, LOGGED_ONLY, BASIC);
		assertHasNotPriceInRange(new BigDecimal("215.0"), new BigDecimal("400.0"), prices, CZK, MOMENT_2011, REFERENCE, VIP, LOGGED_ONLY, BASIC);
		// reference price is not indexed, vip price has fulfilled validity but is last
		// 80 + 80 * 2 + 80 * 0.5 = 280
		assertHasPriceInRange(new BigDecimal("275.0"), new BigDecimal("285.0"), prices, CZK, MOMENT_2011, REFERENCE, LOGGED_ONLY, BASIC, VIP);
		assertHasNotPriceInRange(new BigDecimal("300.0"), new BigDecimal("500.0"), prices, CZK, MOMENT_2011, REFERENCE, LOGGED_ONLY, BASIC, VIP);
		// reference price is not indexed, vip price has not fulfilled validity, logged only is the first - we ask for EUR
		// 8 + 8 * 2 + 8 * 0.5 = 28
		assertHasPriceInRange(new BigDecimal("27.0"), new BigDecimal("29.0"), prices, EUR, MOMENT_2020, REFERENCE, VIP, LOGGED_ONLY, BASIC);
		assertHasNotPriceInRange(new BigDecimal("29.0"), new BigDecimal("200.0"), prices, EUR, MOMENT_2020, REFERENCE, VIP, LOGGED_ONLY, BASIC);
		// reference price is not indexed, vip price has not fulfilled validity, logged only is the first - we ask for GBP
		assertHasNotPriceInRange(new BigDecimal("0"), new BigDecimal("700.0"), prices, GBP, MOMENT_2011, REFERENCE, VIP, LOGGED_ONLY, BASIC);
	}

	/**
	 * Tests the ability to detect differences between price sets.
	 * Verifies that the system can correctly identify when price sets are identical
	 * and when they differ in price values or structure.
	 */
	@Test
	@DisplayName("Price difference detection")
	void shouldDetectDifferencesBetweenPrices() {
		// Create two identical price sets with exactly the same price values and structure
		final PricesContract firstPrices = new Prices(
			PRODUCT_SCHEMA,
			1,
			Arrays.asList(
				createStandardPriceSetWithMultiplier(null, BigDecimal.ONE)
			),
			PriceInnerRecordHandling.NONE,
			true
		);

		final PricesContract secondPrices = new Prices(
			PRODUCT_SCHEMA,
			1,
			Arrays.asList(
				createStandardPriceSetWithMultiplier(null, BigDecimal.ONE)
			),
			PriceInnerRecordHandling.NONE,
			true
		);

		// Test that identical price sets are correctly identified as not different
		assertFalse(PricesContract.anyPriceOrStrategyDifferBetween(firstPrices, secondPrices));

		// Create a price set with different prices
		final PricesContract differentPrices = new Prices(
			PRODUCT_SCHEMA,
			1,
			Arrays.asList(
				createStandardPriceSetWithMultiplier(null, new BigDecimal("1.1"))
			),
			PriceInnerRecordHandling.NONE,
			true
		);

		// Test different prices
		assertTrue(PricesContract.anyPriceOrStrategyDifferBetween(firstPrices, differentPrices));

		// Create a price set with different number of prices
		final PricesContract fewerPrices = new Prices(
			PRODUCT_SCHEMA,
			1,
			Arrays.asList(
				new Price(new PriceKey(1, BASIC, CZK), null, new BigDecimal("100"), new BigDecimal("21"), new BigDecimal("121"), null, true)
			),
			PriceInnerRecordHandling.NONE,
			true
		);

		// Test different number of prices
		assertTrue(PricesContract.anyPriceOrStrategyDifferBetween(firstPrices, fewerPrices));
	}

	/**
	 * Tests the availability check for prices.
	 * Verifies that the system correctly determines when prices are available
	 * based on the presence of price data and the enabled flag.
	 */
	@Test
	@DisplayName("Price availability checking")
	void shouldCheckIfPricesAreAvailable() {
		// Create a price set with prices
		final PricesContract pricesWithPrices = new Prices(
			PRODUCT_SCHEMA,
			1,
			Arrays.asList(
				createStandardPriceSetWithMultiplier(null, BigDecimal.ONE)
			),
			PriceInnerRecordHandling.NONE,
			true
		);

		// Test prices are available
		assertTrue(pricesWithPrices.pricesAvailable());

		// Create a price set with no prices
		final PricesContract pricesWithNoPrices = new Prices(
			PRODUCT_SCHEMA,
			1,
			Arrays.asList(),
			PriceInnerRecordHandling.NONE,
			true
		);

		// Test prices are available (empty list but still available)
		assertTrue(pricesWithNoPrices.pricesAvailable());

		// Create a price set with prices disabled
		final PricesContract pricesDisabled = new Prices(
			PRODUCT_SCHEMA,
			1,
			Arrays.asList(
				createStandardPriceSetWithMultiplier(null, BigDecimal.ONE)
			),
			PriceInnerRecordHandling.NONE,
			false
		);

		// Test prices are not available when disabled
		assertFalse(pricesDisabled.pricesAvailable());
	}

	/**
	 * Tests the availability check for price for sale context.
	 * Verifies that the system correctly determines when price for sale context is available.
	 */
	@Test
	@DisplayName("Price for sale context availability")
	void shouldCheckIfPriceForSaleContextIsAvailable() {
		// Create a price set with prices
		final PricesContract prices = new Prices(
			PRODUCT_SCHEMA,
			1,
			Arrays.asList(
				createStandardPriceSetWithMultiplier(null, BigDecimal.ONE)
			),
			PriceInnerRecordHandling.NONE,
			true
		);

		// Test price for sale context is not available in the Prices class
		assertFalse(prices.isPriceForSaleContextAvailable());

		// Test price for sale context is empty
		assertTrue(prices.getPriceForSaleContext().isEmpty());
	}

	/**
	 * Tests the behavior of getPriceForSaleIfAvailable method.
	 * Verifies that the method returns an empty Optional when price for sale context is not available.
	 */
	@Test
	@DisplayName("Empty optional for unavailable price context")
	void shouldReturnEmptyOptionalForPriceForSaleIfAvailable() {
		// Create a price set with prices
		final PricesContract prices = new Prices(
			PRODUCT_SCHEMA,
			1,
			Arrays.asList(
				createStandardPriceSetWithMultiplier(null, BigDecimal.ONE)
			),
			PriceInnerRecordHandling.NONE,
			true
		);

		// Test getPriceForSaleIfAvailable returns empty Optional when context is not available
		assertTrue(prices.getPriceForSaleIfAvailable().isEmpty());
	}

	/**
	 * Tests that methods requiring a price for sale context throw ContextMissingException
	 * when the context is not available.
	 * Verifies the exception handling for methods that depend on context information.
	 */
	@Test
	@DisplayName("Exception handling for missing context")
	void shouldThrowContextMissingExceptionForMethodsRequiringContext() {
		// Create a price set with prices
		final PricesContract prices = new Prices(
			PRODUCT_SCHEMA,
			1,
			Arrays.asList(
				createStandardPriceSetWithMultiplier(null, BigDecimal.ONE)
			),
			PriceInnerRecordHandling.NONE,
			true
		);

		// Test getPriceForSale throws ContextMissingException
		try {
			prices.getPriceForSale();
			// If we get here, the test has failed
			throw new AssertionError("Expected ContextMissingException was not thrown");
		} catch (ContextMissingException e) {
			// Expected exception
		}

		// Test getAllPricesForSale throws ContextMissingException
		try {
			prices.getAllPricesForSale();
			// If we get here, the test has failed
			throw new AssertionError("Expected ContextMissingException was not thrown");
		} catch (ContextMissingException e) {
			// Expected exception
		}

		// Test hasPriceInInterval throws ContextMissingException
		try {
			prices.hasPriceInInterval(BigDecimal.ONE, BigDecimal.TEN, QueryPriceMode.WITHOUT_TAX);
			// If we get here, the test has failed
			throw new AssertionError("Expected ContextMissingException was not thrown");
		} catch (ContextMissingException e) {
			// Expected exception
		}
	}

	/**
	 * Tests the non-parametrized getPriceForSaleWithAccompanyingPrices method.
	 * Verifies that the method correctly retrieves price for sale with accompanying prices
	 * when a valid price for sale context is available.
	 */
	@Test
	@DisplayName("Non-parametrized price for sale with accompanying prices retrieval")
	void shouldReturnPriceForSaleWithAccompanyingPrices() {
		final PricesContract prices = Mockito.spy(
			new Prices(
				PRODUCT_SCHEMA,
				1,
				Arrays.asList(
					createStandardPriceSetWithMultiplier(null, BigDecimal.ONE)
				),
				PriceInnerRecordHandling.NONE,
				true
			)
		);

		// Mock the price for sale context with accompanying prices
		Mockito.when(prices.getPriceForSaleContext())
			.thenReturn(
				Optional.of(
					new PriceForSaleContextWithCachedResult(
						new String[]{BASIC},
						CZK,
						MOMENT_2020,
						new AccompanyingPrice[] {
							new AccompanyingPrice("testPrice1", REFERENCE),
							new AccompanyingPrice("testPrice2", LOGGED_ONLY)
						}
					)
				)
			);

		// Test the non-parametrized method
		final PriceForSaleWithAccompanyingPrices result = prices.getPriceForSaleWithAccompanyingPrices().orElseThrow();

		// Verify the price for sale
		assertEquals(1, result.priceForSale().priceId());

		// Verify accompanying prices
		assertEquals(2, result.accompanyingPrices().size());
		assertEquals(7, result.accompanyingPrices().get("testPrice1").orElseThrow().priceId());
		assertEquals(3, result.accompanyingPrices().get("testPrice2").orElseThrow().priceId());
	}

	/**
	 * Tests that the non-parametrized getPriceForSaleWithAccompanyingPrices method
	 * throws ContextMissingException when the price for sale context is not available.
	 */
	@Test
	@DisplayName("Exception handling for missing context in non-parametrized getPriceForSaleWithAccompanyingPrices")
	void shouldThrowContextMissingExceptionForGetPriceForSaleWithAccompanyingPrices() {
		final PricesContract prices = new Prices(
			PRODUCT_SCHEMA,
			1,
			Arrays.asList(
				createStandardPriceSetWithMultiplier(null, BigDecimal.ONE)
			),
			PriceInnerRecordHandling.NONE,
			true
		);

		// Test getPriceForSaleWithAccompanyingPrices throws ContextMissingException
		try {
			prices.getPriceForSaleWithAccompanyingPrices();
			// If we get here, the test has failed
			throw new AssertionError("Expected ContextMissingException was not thrown");
		} catch (ContextMissingException e) {
			// Expected exception
		}
	}

	/**
	 * Tests the non-parametrized getAllPricesForSaleWithAccompanyingPrices method.
	 * Verifies that the method correctly retrieves all prices for sale with accompanying prices
	 * when a valid price for sale context is available.
	 */
	@Test
	@DisplayName("Non-parametrized all prices for sale with accompanying prices retrieval")
	void shouldReturnAllPricesForSaleWithAccompanyingPrices() {
		// Create a price set with multiple inner records for LOWEST_PRICE strategy
		final PricesContract prices = Mockito.spy(
			new Prices(
				PRODUCT_SCHEMA,
				1,
				Arrays.asList(
					ArrayUtils.mergeArrays(
						createStandardPriceSetWithMultiplier(1, BigDecimal.ONE),
						createStandardPriceSetWithMultiplier(2, new BigDecimal("2")),
						createStandardPriceSetWithMultiplier(3, new BigDecimal("0.5"))
					)
				),
				PriceInnerRecordHandling.LOWEST_PRICE,
				true
			)
		);

		// Mock the price for sale context with accompanying prices
		Mockito.when(prices.getPriceForSaleContext())
			.thenReturn(
				Optional.of(
					new PriceForSaleContextWithCachedResult(
						new String[]{BASIC, LOGGED_ONLY},
						CZK,
						MOMENT_2020,
						new AccompanyingPrice[] {
							new AccompanyingPrice("testPrice1", REFERENCE),
							new AccompanyingPrice("testPrice2", VIP)
						}
					)
				)
			);

		// Test the non-parametrized method
		final List<PriceForSaleWithAccompanyingPrices> results = prices.getAllPricesForSaleWithAccompanyingPrices();

		// Verify we got results for all three inner records
		assertEquals(3, results.size());

		// For each result, verify the price for sale and accompanying prices
		for (PriceForSaleWithAccompanyingPrices result : results) {
			// Get the inner record ID from the price for sale
			final int innerRecordId = result.priceForSale().innerRecordId();

			// Verify the price for sale is from the BASIC price list (since it's first in priority)
			assertEquals(1000000 * innerRecordId + 1, result.priceForSale().priceId());

			// Verify accompanying prices
			assertEquals(2, result.accompanyingPrices().size());
			assertEquals(1000000 * innerRecordId + 7, result.accompanyingPrices().get("testPrice1").orElseThrow().priceId());

			// VIP price is only valid in 2011, not in 2020, so it should be empty
			assertTrue(result.accompanyingPrices().get("testPrice2").isEmpty());
		}
	}

}
