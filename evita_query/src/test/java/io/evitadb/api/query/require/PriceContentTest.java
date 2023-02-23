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
		assertEquals(PriceContentMode.RESPECTING_FILTER, priceContent().getFetchMode());
		assertEquals(PriceContentMode.ALL, priceContentAll().getFetchMode());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(priceContent().isApplicable());
		assertTrue(priceContentAll().isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		assertEquals("priceContent(RESPECTING_FILTER)", priceContent().toString());
		assertEquals("priceContent(ALL)", priceContentAll().toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(priceContent(), priceContent());
		assertEquals(priceContent(), priceContent());
		assertNotEquals(priceContent(), priceContentAll());
		assertEquals(priceContent().hashCode(), priceContent().hashCode());
		assertNotEquals(priceContent().hashCode(), priceContentAll().hashCode());
	}

	@Test
	void shouldCombineWithAnotherConstraint() {
		assertEquals(priceContent(), priceContent().combineWith(priceContent()));
		assertEquals(priceContentAll(), priceContent().combineWith(priceContentAll()));
		assertEquals(priceContentAll(), priceContentAll().combineWith(priceContent()));
	}

}