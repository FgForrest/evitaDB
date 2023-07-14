/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.PricesContract;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Currency;

import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies {@link Prices} contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class PricesContractTest {
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

	@Test
	void shouldReturnPriceForSaleForNoneStrategy() {
		final PricesContract prices = new Prices(
			1,
			Arrays.asList(
				createStandardPriceSetWithMultiplier(null, BigDecimal.ONE)
			),
			PriceInnerRecordHandling.NONE
		);

		// reference price is not indexed, vip price has not fulfilled validity, logged only is the first
		assertPriceForSell(3, null, prices, CZK, MOMENT_2020, REFERENCE, VIP, LOGGED_ONLY, BASIC);
		// reference price is not indexed, vip price has not fulfilled validity, basic is the first
		assertPriceForSell(1, null, prices, CZK, MOMENT_2020, REFERENCE, VIP, BASIC, LOGGED_ONLY);
		// reference price is not indexed, vip price has not fulfilled validity - no other available
		assertNoPriceForSell(prices, CZK, MOMENT_2020, REFERENCE, VIP);
		// reference price is not indexed, vip price has fulfilled validity and is first
		assertPriceForSell(5, null, prices, CZK, MOMENT_2011, REFERENCE, VIP, LOGGED_ONLY, BASIC);
		// reference price is not indexed, vip price has fulfilled validity but is last
		assertPriceForSell(3, null, prices, CZK, MOMENT_2011, REFERENCE, LOGGED_ONLY, BASIC, VIP);
		// reference price is not indexed, vip price has not fulfilled validity, logged only is the first - we ask for EUR
		assertPriceForSell(4, null, prices, EUR, MOMENT_2020, REFERENCE, VIP, LOGGED_ONLY, BASIC);
		// reference price is not indexed, vip price has not fulfilled validity, logged only is the first - we ask for GBP
		assertNoPriceForSell(prices, GBP, MOMENT_2011, REFERENCE, VIP, LOGGED_ONLY, BASIC);
	}

	@Test
	void shouldReturnPriceForSaleForFirstOccurrence() {
		final PricesContract prices = new Prices(
			1,
			Arrays.asList(
				ArrayUtils.mergeArrays(
					createStandardPriceSetWithMultiplier(1, BigDecimal.ONE),
					createStandardPriceSetWithMultiplier(2, new BigDecimal("2")),
					createStandardPriceSetWithMultiplier(3, new BigDecimal("0.5"))
				)
			),
			PriceInnerRecordHandling.FIRST_OCCURRENCE
		);

		// variant 3 has smallest multiplier -> prices, it will always take precedence

		// reference price is not indexed, vip price has not fulfilled validity, logged only is the first
		assertPriceForSell(3, 3, prices, CZK, MOMENT_2020, REFERENCE, VIP, LOGGED_ONLY, BASIC);
		// reference price is not indexed, vip price has not fulfilled validity, basic is the first
		assertPriceForSell(1, 3, prices, CZK, MOMENT_2020, REFERENCE, VIP, BASIC, LOGGED_ONLY);
		// reference price is not indexed, vip price has not fulfilled validity - no other available
		assertNoPriceForSell(prices, CZK, MOMENT_2020, REFERENCE, VIP);
		// reference price is not indexed, vip price has fulfilled validity and is first
		assertPriceForSell(5, 3, prices, CZK, MOMENT_2011, REFERENCE, VIP, LOGGED_ONLY, BASIC);
		// reference price is not indexed, vip price has fulfilled validity but is last
		assertPriceForSell(3, 3, prices, CZK, MOMENT_2011, REFERENCE, LOGGED_ONLY, BASIC, VIP);
		// reference price is not indexed, vip price has not fulfilled validity, logged only is the first - we ask for EUR
		assertPriceForSell(4, 3, prices, EUR, MOMENT_2020, REFERENCE, VIP, LOGGED_ONLY, BASIC);
		// reference price is not indexed, vip price has not fulfilled validity, logged only is the first - we ask for GBP
		assertNoPriceForSell(prices, GBP, MOMENT_2011, REFERENCE, VIP, LOGGED_ONLY, BASIC);
	}

	@Test
	void shouldReturnPriceForSaleForFirstOccurrenceWhenOneProductHasPriceInPriceListWithBiggerPriorityButLowerPrice() {
		final PricesContract prices = new Prices(
			1,
			Arrays.asList(
				new Price(new PriceKey(combineIntoId(1, 1), BASIC, CZK), 1, new BigDecimal("100"), new BigDecimal("21"), new BigDecimal("121"), null, true),
				new Price(new PriceKey(combineIntoId(1, 2), LOGGED_ONLY, CZK), 1, new BigDecimal("80"), new BigDecimal("21"), new BigDecimal("96.8"), null, true),
				new Price(new PriceKey(combineIntoId(1, 3), VIP, CZK), 1, new BigDecimal("140"), new BigDecimal("21"), new BigDecimal("169.4"), null, false),
				new Price(new PriceKey(combineIntoId(2, 4), BASIC, CZK), 2, new BigDecimal("60"), new BigDecimal("21"), new BigDecimal("72.6"), null, true),
				new Price(new PriceKey(combineIntoId(2, 5), LOGGED_ONLY, CZK), 2, new BigDecimal("50"), new BigDecimal("21"), new BigDecimal("60.5"), null, true),
				new Price(new PriceKey(combineIntoId(3, 6), BASIC, CZK), 3, new BigDecimal("90"), new BigDecimal("21"), new BigDecimal("108.9"), null, true),
				new Price(new PriceKey(combineIntoId(3, 7), LOGGED_ONLY, CZK), 3, new BigDecimal("70"), new BigDecimal("21"), new BigDecimal("84.7"), null, true)
			),
			PriceInnerRecordHandling.FIRST_OCCURRENCE
		);

		// price 5 is the lowest price from all allowed price lists over the inner record id
		assertPriceForSell(5, 2, prices, CZK, null, VIP, LOGGED_ONLY, BASIC);
	}

	@Test
	void shouldReturnPriceForSaleForSum() {
		final PricesContract prices = new Prices(
			1,
			Arrays.asList(
				ArrayUtils.mergeArrays(
					createStandardPriceSetWithMultiplier(1, BigDecimal.ONE),
					createStandardPriceSetWithMultiplier(2, new BigDecimal("2")),
					createStandardPriceSetWithMultiplier(3, new BigDecimal("0.5"))
				)
			),
			PriceInnerRecordHandling.SUM
		);

		// reference price is not indexed, vip price has not fulfilled validity, logged only is the first
		// 80 + 80 * 2 + 80 * 0.5 = 280
		assertPriceForSell(new BigDecimal("280.0"), prices, CZK, MOMENT_2020, REFERENCE, VIP, LOGGED_ONLY, BASIC);
		// reference price is not indexed, vip price has not fulfilled validity, basic is the first
		// 100 + 100 * 2 + 100 * 0.5 = 350
		assertPriceForSell(new BigDecimal("350.0"), prices, CZK, MOMENT_2020, REFERENCE, VIP, BASIC, LOGGED_ONLY);
		// reference price is not indexed, vip price has not fulfilled validity - no other available
		assertNoPriceForSell(prices, CZK, MOMENT_2020, REFERENCE, VIP);
		// reference price is not indexed, vip price has fulfilled validity and is first
		// 60 + 60 * 2 + 60 * 0.5 = 210
		assertPriceForSell(new BigDecimal("210.0"), prices, CZK, MOMENT_2011, REFERENCE, VIP, LOGGED_ONLY, BASIC);
		// reference price is not indexed, vip price has fulfilled validity but is last
		// 80 + 80 * 2 + 80 * 0.5 = 280
		assertPriceForSell(new BigDecimal("280.0"), prices, CZK, MOMENT_2011, REFERENCE, LOGGED_ONLY, BASIC, VIP);
		// reference price is not indexed, vip price has not fulfilled validity, logged only is the first - we ask for EUR
		// 8 + 8 * 2 + 8 * 0.5 = 28
		assertPriceForSell(new BigDecimal("28.0"), prices, EUR, MOMENT_2020, REFERENCE, VIP, LOGGED_ONLY, BASIC);
		// reference price is not indexed, vip price has not fulfilled validity, logged only is the first - we ask for GBP
		assertNoPriceForSell(prices, GBP, MOMENT_2011, REFERENCE, VIP, LOGGED_ONLY, BASIC);
	}

	@Test
	void shouldResolveHavingPriceInRangeForNoneStrategy() {
		final PricesContract prices = new Prices(
			1,
			Arrays.asList(
				createStandardPriceSetWithMultiplier(null, BigDecimal.ONE)
			),
			PriceInnerRecordHandling.NONE
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

	@Test
	void shouldResolveHavingPriceInRangeForFirstOccurrence() {
		final PricesContract prices = new Prices(
			1,
			Arrays.asList(
				ArrayUtils.mergeArrays(
					createStandardPriceSetWithMultiplier(1, BigDecimal.ONE),
					createStandardPriceSetWithMultiplier(2, new BigDecimal("2")),
					createStandardPriceSetWithMultiplier(3, new BigDecimal("0.5"))
				)
			),
			PriceInnerRecordHandling.FIRST_OCCURRENCE
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

	@Test
	void shouldResolveHavingPriceInRangeForSum() {
		final PricesContract prices = new Prices(
			1,
			Arrays.asList(
				ArrayUtils.mergeArrays(
					createStandardPriceSetWithMultiplier(1, BigDecimal.ONE),
					createStandardPriceSetWithMultiplier(2, new BigDecimal("2")),
					createStandardPriceSetWithMultiplier(3, new BigDecimal("0.5"))
				)
			),
			PriceInnerRecordHandling.SUM
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

	@Nonnull
	private static PriceContract[] createStandardPriceSetWithMultiplier(Integer innerRecordId, BigDecimal multiplier) {
		return new PriceContract[]{
			new Price(new PriceKey(combineIntoId(innerRecordId, 1), BASIC, CZK), innerRecordId, new BigDecimal("100").multiply(multiplier), new BigDecimal("21"), new BigDecimal("121").multiply(multiplier), null, true),
			new Price(new PriceKey(combineIntoId(innerRecordId, 2), BASIC, EUR), innerRecordId, new BigDecimal("10").multiply(multiplier), new BigDecimal("21"), new BigDecimal("12.1").multiply(multiplier), null, true),
			new Price(new PriceKey(combineIntoId(innerRecordId, 3), LOGGED_ONLY, CZK), innerRecordId, new BigDecimal("80").multiply(multiplier), new BigDecimal("21"), new BigDecimal("96.8").multiply(multiplier), null, true),
			new Price(new PriceKey(combineIntoId(innerRecordId, 4), LOGGED_ONLY, EUR), innerRecordId, new BigDecimal("8").multiply(multiplier), new BigDecimal("21"), new BigDecimal("9.68").multiply(multiplier), null, true),
			new Price(new PriceKey(combineIntoId(innerRecordId, 5), VIP, CZK), innerRecordId, new BigDecimal("60").multiply(multiplier), new BigDecimal("21"), new BigDecimal("72.6").multiply(multiplier), RANGE_2010_2012, true),
			new Price(new PriceKey(combineIntoId(innerRecordId, 6), VIP, EUR), innerRecordId, new BigDecimal("6").multiply(multiplier), new BigDecimal("21"), new BigDecimal("7.26").multiply(multiplier), RANGE_2010_2012, true),
			new Price(new PriceKey(combineIntoId(innerRecordId, 7), VIP, CZK), innerRecordId, new BigDecimal("140").multiply(multiplier), new BigDecimal("21"), new BigDecimal("169.4").multiply(multiplier), null, false),
			new Price(new PriceKey(combineIntoId(innerRecordId, 8), VIP, EUR), innerRecordId, new BigDecimal("14").multiply(multiplier), new BigDecimal("21"), new BigDecimal("16.94").multiply(multiplier), null, false)
		};
	}

	private static void assertHasPriceInRange(BigDecimal from, BigDecimal to, PricesContract prices, Currency currency, OffsetDateTime moment, String... priceLists) {
		assertTrue(prices.hasPriceInInterval(from, to, QueryPriceMode.WITHOUT_TAX, currency, moment, convertToClassifiers(priceLists)));
	}

	private static void assertHasPriceInRangeWithMultiplier(BigDecimal from, BigDecimal to, BigDecimal[] multiplier, PricesContract prices, Currency currency, OffsetDateTime moment, String... priceLists) {
		for (BigDecimal m : multiplier) {
			assertTrue(prices.hasPriceInInterval(from.multiply(m), to.multiply(m), QueryPriceMode.WITHOUT_TAX, currency, moment, convertToClassifiers(priceLists)));
		}
	}

	private static void assertHasNotPriceInRange(BigDecimal from, BigDecimal to, PricesContract prices, Currency currency, OffsetDateTime moment, String... priceLists) {
		assertFalse(prices.hasPriceInInterval(from, to, QueryPriceMode.WITHOUT_TAX, currency, moment, convertToClassifiers(priceLists)));
	}

	private static void assertPriceForSell(int expectedPriceId, Integer innerRecordId, PricesContract prices, Currency currency, OffsetDateTime moment, String... priceLists) {
		final PriceContract priceForSale = prices.getPriceForSale(
			currency, moment, convertToClassifiers(priceLists)
		).orElseThrow();

		assertEquals(combineIntoId(innerRecordId, expectedPriceId), priceForSale.priceId());
	}

	private static void assertPriceForSell(BigDecimal expectedPriceWithoutVat, PricesContract prices, Currency currency, OffsetDateTime moment, String... priceLists) {
		final PriceContract priceForSale = prices.getPriceForSale(
			currency, moment, convertToClassifiers(priceLists)
		).orElseThrow();
		
		assertEquals(expectedPriceWithoutVat, priceForSale.priceWithoutTax());
	}

	private static void assertNoPriceForSell(PricesContract prices, Currency currency, OffsetDateTime moment, String... priceLists) {
		final PriceContract priceForSale = prices.getPriceForSale(
			currency, moment, convertToClassifiers(priceLists)
		).orElse(null);

		assertNull(priceForSale);
	}

	@Nonnull
	private static String[] convertToClassifiers(@Nonnull String[] priceLists) {
		return Arrays.stream(priceLists).toArray(String[]::new);
	}

	private static int combineIntoId(Integer innerRecordId, int id) {
		return ofNullable(innerRecordId).map(it -> innerRecordId * 1_000_000 + id).orElse(id);
	}

}