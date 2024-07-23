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

package io.evitadb.api.query.order;

import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.priceNatural;
import static io.evitadb.api.query.order.OrderDirection.ASC;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This tests verifies basic properties of {@link PriceNatural} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class PriceNaturalTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final PriceNatural priceNatural1 = priceNatural();
		assertEquals(ASC, priceNatural1.getOrderDirection());

		final PriceNatural priceNatural2 = priceNatural(DESC);
		assertEquals(DESC, priceNatural2.getOrderDirection());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(priceNatural().isApplicable());
		assertTrue(priceNatural(DESC).isApplicable());
		assertFalse(new PriceNatural(null).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final PriceNatural priceNatural1 = priceNatural();
		assertEquals("priceNatural(ASC)", priceNatural1.toString());

		final PriceNatural priceNatural2 = priceNatural(DESC);
		assertEquals("priceNatural(DESC)", priceNatural2.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(priceNatural(), priceNatural());
		assertEquals(priceNatural(), priceNatural());
		assertEquals(priceNatural(ASC), priceNatural());
		assertEquals(priceNatural().hashCode(), priceNatural().hashCode());
		assertEquals(priceNatural(ASC).hashCode(), priceNatural().hashCode());
	}

}
