/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.api.query.filter;

import org.junit.jupiter.api.Test;

import java.util.Currency;

import static io.evitadb.api.query.QueryConstraints.priceInCurrency;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link PriceInCurrency} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class PriceInCurrencyTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final PriceInCurrency priceInCurrency = priceInCurrency("CZK");
		assertEquals(Currency.getInstance("CZK"), priceInCurrency.getCurrency());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(priceInCurrency("CZK").isApplicable());
		assertFalse(new PriceInCurrency((String)null).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final PriceInCurrency priceInCurrency = priceInCurrency("CZK");
		assertEquals("priceInCurrency('CZK')", priceInCurrency.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(priceInCurrency("CZK"), priceInCurrency("CZK"));
		assertEquals(priceInCurrency("CZK"), priceInCurrency("CZK"));
		assertNotEquals(priceInCurrency("CZK"), priceInCurrency("EUR"));
		assertNotEquals(priceInCurrency("CZK"), new PriceInCurrency((String)null));
		assertEquals(priceInCurrency("CZK").hashCode(), priceInCurrency("CZK").hashCode());
		assertNotEquals(priceInCurrency("CZK").hashCode(), priceInCurrency("EUR").hashCode());
		assertNotEquals(priceInCurrency("CZK").hashCode(), new PriceInCurrency((String)null).hashCode());
	}

}
