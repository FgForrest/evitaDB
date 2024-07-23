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

import java.math.BigDecimal;

import static io.evitadb.api.query.QueryConstraints.priceBetween;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link PriceBetween} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class PriceBetweenTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final PriceBetween between = priceBetween(new BigDecimal("1"), new BigDecimal("2"));
		assertEquals(new BigDecimal("1"), between.getFrom());
		assertEquals(new BigDecimal("2"), between.getTo());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertNull(priceBetween(null, null));
		assertFalse(new PriceBetween(null, null).isApplicable());
		assertTrue(priceBetween(null, new BigDecimal("1")).isApplicable());
		assertTrue(priceBetween(new BigDecimal("1"), null).isApplicable());
		assertTrue(priceBetween(new BigDecimal("1"), new BigDecimal("2")).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		assertEquals("priceBetween(1,2)", priceBetween(new BigDecimal("1"), new BigDecimal("2")).toString());
		assertEquals("priceBetween(<NULL>,2)", priceBetween(null, new BigDecimal("2")).toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(priceBetween(new BigDecimal("1"), new BigDecimal("2")), priceBetween(new BigDecimal("1"), new BigDecimal("2")));
		assertEquals(priceBetween(new BigDecimal("1"), new BigDecimal("2")), priceBetween(new BigDecimal("1"), new BigDecimal("2")));
		assertNotEquals(priceBetween(new BigDecimal("1"), new BigDecimal("2")), priceBetween(new BigDecimal("1"), new BigDecimal("3")));
		assertNotEquals(priceBetween(new BigDecimal("1"), new BigDecimal("2")), priceBetween(null, null));
		assertNotEquals(priceBetween(new BigDecimal("1"), new BigDecimal("2")), priceBetween(null, new BigDecimal("3")));
		assertEquals(priceBetween(null, new BigDecimal("2")), priceBetween(null, new BigDecimal("2")));
		assertEquals(priceBetween(new BigDecimal("1"), new BigDecimal("2")).hashCode(), priceBetween(new BigDecimal("1"), new BigDecimal("2")).hashCode());
		assertNotEquals(priceBetween(new BigDecimal("1"), new BigDecimal("2")).hashCode(), priceBetween(new BigDecimal("1"), new BigDecimal("3")).hashCode());
		assertNotEquals(priceBetween(new BigDecimal("1"), new BigDecimal("2")).hashCode(), new PriceBetween(null, null).hashCode());
		assertNotEquals(priceBetween(new BigDecimal("1"), new BigDecimal("2")).hashCode(), priceBetween(null, new BigDecimal("3")).hashCode());
		assertEquals(priceBetween(null, new BigDecimal("2")).hashCode(), priceBetween(null, new BigDecimal("2")).hashCode());
	}

}
