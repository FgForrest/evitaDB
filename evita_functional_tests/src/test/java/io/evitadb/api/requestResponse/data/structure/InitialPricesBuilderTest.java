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

import io.evitadb.api.exception.AmbiguousPriceException;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.PricesContract;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Currency;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies contract of {@link InitialEntityBuilder}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class InitialPricesBuilderTest extends AbstractBuilderTest {
	public static final Currency CZK = Currency.getInstance("CZK");
	public static final Currency EUR = Currency.getInstance("EUR");
	private final InitialPricesBuilder builder = new InitialPricesBuilder(PRODUCT_SCHEMA);

	@Test
	void shouldCreateEntityWithPrices() {
		final PricesContract prices = builder.setPriceInnerRecordHandling(PriceInnerRecordHandling.LOWEST_PRICE)
				.setPrice(1, "basic", CZK, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
				.setPrice(2, "reference", CZK, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, false)
				.setPrice(3, "basic", EUR, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
				.setPrice(4, "reference", EUR, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, false)
				.build();
		assertEquals(PriceInnerRecordHandling.LOWEST_PRICE, prices.getPriceInnerRecordHandling());
		assertEquals(4, prices.getPrices().size());
		assertPrice(prices.getPrice(1, "basic", CZK), BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true);
		assertPrice(prices.getPrice(2, "reference", CZK), BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, false);
		assertPrice(prices.getPrice(3, "basic", EUR), BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true);
		assertPrice(prices.getPrice(4, "reference", EUR), BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, false);
	}

	@Test
	void shouldOverwriteIdenticalPrice() {
		final PricesContract prices = builder
				.setPrice(1, "basic", CZK, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
				.setPrice(1, "basic", CZK, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, true)
				.build();

		assertEquals(1, prices.getPrices().size());
		assertPrice(prices.getPrice(1, "basic", CZK), BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, true);
	}

	@Test
	void shouldRefuseAddingConflictingPrice() {
		assertThrows(AmbiguousPriceException.class, () -> {
			final PricesContract prices = builder
				.setPrice(1, "basic", CZK, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
				.setPrice(2, "basic", CZK, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, true)
				.build();
		});
	}

	@Test
	void shouldAllowAddingConflictingPriceForDifferentInnerRecordId() {
		final PricesContract prices = builder
			.setPrice(1, "basic", CZK, 1, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
			.setPrice(2, "basic", CZK, 2, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, true)
			.build();

		final Collection<PriceContract> basicPrices = prices.getPrices(CZK, "basic");
		assertEquals(2, basicPrices.size());
		assertTrue(basicPrices.stream().anyMatch(it -> it.priceId() == 1));
		assertTrue(basicPrices.stream().anyMatch(it -> it.priceId() == 2));
	}

	@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
	public static void assertPrice(Optional<PriceContract> price, BigDecimal priceWithoutTax, BigDecimal taxRate, BigDecimal priceWithTax, boolean indexed) {
		assertTrue(price.isPresent());
		assertPrice(price.orElseThrow(), priceWithoutTax, taxRate, priceWithTax, indexed);
	}

	public static void assertPrice(PriceContract price, BigDecimal priceWithoutTax, BigDecimal taxRate, BigDecimal priceWithTax, boolean indexed) {
		assertNotNull(price);
		assertEquals(priceWithoutTax, price.priceWithoutTax());
		assertEquals(taxRate, price.taxRate());
		assertEquals(priceWithTax, price.priceWithTax());
		assertEquals(indexed, price.sellable());
	}
}
