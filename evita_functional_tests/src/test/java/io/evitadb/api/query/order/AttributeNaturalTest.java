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

import static io.evitadb.api.query.QueryConstraints.attributeNatural;
import static io.evitadb.api.query.order.OrderDirection.ASC;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link AttributeNatural} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
class AttributeNaturalTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final AttributeNatural attributeNatural1 = attributeNatural("age");
		assertEquals("age", attributeNatural1.getAttributeName());
		assertEquals(ASC, attributeNatural1.getOrderDirection());

		final AttributeNatural attributeNatural2 = attributeNatural("married", DESC);
		assertEquals("married", attributeNatural2.getAttributeName());
		assertEquals(DESC, attributeNatural2.getOrderDirection());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(attributeNatural("married").isApplicable());
		assertTrue(attributeNatural("age", DESC).isApplicable());
		assertFalse(new AttributeNatural(null).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final AttributeNatural attributeNatural1 = attributeNatural("married");
		assertEquals("attributeNatural('married',ASC)", attributeNatural1.toString());

		final AttributeNatural attributeNatural2 = attributeNatural("married", DESC);
		assertEquals("attributeNatural('married',DESC)", attributeNatural2.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(attributeNatural("married"), attributeNatural("married"));
		assertEquals(attributeNatural("married"), attributeNatural("married"));
		assertEquals(attributeNatural("married", ASC), attributeNatural("married"));
		assertNotEquals(attributeNatural("married"), attributeNatural("single"));
		assertNotEquals(attributeNatural("married"), attributeNatural(null));
		assertEquals(attributeNatural("married").hashCode(), attributeNatural("married").hashCode());
		assertEquals(attributeNatural("married", ASC).hashCode(), attributeNatural("married").hashCode());
		assertNotEquals(attributeNatural("married").hashCode(), attributeNatural("single").hashCode());
		assertNotEquals(attributeNatural("married").hashCode(), new AttributeNatural(null).hashCode());
	}

}
