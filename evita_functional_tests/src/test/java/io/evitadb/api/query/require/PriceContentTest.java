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

package io.evitadb.api.query.require;

import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.priceContent;
import static io.evitadb.api.query.QueryConstraints.priceContentAll;
import static io.evitadb.api.query.QueryConstraints.priceContentRespectingFilter;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This tests verifies basic properties of {@link PriceContent} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class PriceContentTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		assertEquals(PriceContentMode.RESPECTING_FILTER, priceContentRespectingFilter().getFetchMode());
		assertEquals(PriceContentMode.ALL, priceContentAll().getFetchMode());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(priceContent(PriceContentMode.NONE).isApplicable());
		assertTrue(priceContentRespectingFilter().isApplicable());
		assertTrue(priceContentAll().isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		assertEquals("priceContent(NONE)", priceContent(PriceContentMode.NONE).toString());
		assertEquals("priceContentRespectingFilter()", priceContentRespectingFilter().toString());
		assertEquals("priceContentRespectingFilter('a','b')", priceContentRespectingFilter("a", "b").toString());
		assertEquals("priceContentAll()", priceContentAll().toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(priceContent(PriceContentMode.NONE), priceContent(PriceContentMode.NONE));
		assertEquals(priceContent(PriceContentMode.NONE), priceContent(PriceContentMode.NONE));
		assertEquals(priceContentRespectingFilter("A"), priceContentRespectingFilter("A"));
		assertNotEquals(priceContentRespectingFilter(), priceContentAll());
		assertNotEquals(priceContent(PriceContentMode.NONE), priceContentAll());
		assertNotEquals(priceContent(PriceContentMode.NONE), priceContentRespectingFilter());
		assertNotEquals(priceContentRespectingFilter(), priceContentRespectingFilter("a"));
		assertEquals(priceContent(PriceContentMode.NONE).hashCode(), priceContent(PriceContentMode.NONE).hashCode());
		assertEquals(priceContentRespectingFilter("a").hashCode(), priceContentRespectingFilter("a").hashCode());
		assertNotEquals(priceContent(PriceContentMode.NONE).hashCode(), priceContentAll().hashCode());
		assertNotEquals(priceContent(PriceContentMode.NONE).hashCode(), priceContentRespectingFilter().hashCode());
		assertNotEquals(priceContentRespectingFilter().hashCode(), priceContentRespectingFilter("a").hashCode());
		assertNotEquals(priceContentAll().hashCode(), priceContentRespectingFilter().hashCode());
	}

	@Test
	void shouldCombineWithAnotherConstraint() {
		assertEquals(priceContent(PriceContentMode.NONE), priceContent(PriceContentMode.NONE).combineWith(priceContent(PriceContentMode.NONE)));
		assertEquals(priceContent(PriceContentMode.RESPECTING_FILTER), priceContent(PriceContentMode.NONE).combineWith(priceContent(PriceContentMode.RESPECTING_FILTER)));
		assertEquals(priceContent(PriceContentMode.ALL), priceContent(PriceContentMode.RESPECTING_FILTER).combineWith(priceContent(PriceContentMode.ALL)));
		assertEquals(priceContent(PriceContentMode.ALL), priceContent(PriceContentMode.NONE).combineWith(priceContent(PriceContentMode.ALL)));
		assertEquals(priceContentRespectingFilter("a", "b"), priceContentRespectingFilter("a").combineWith(priceContentRespectingFilter("b")));
	}

}