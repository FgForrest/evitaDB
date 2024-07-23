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

import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.FilterConstraint;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static io.evitadb.api.query.QueryConstraints.and;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link And} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class AndTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final ConstraintContainer<FilterConstraint> and =
				and(
					attributeEquals("abc", "def"),
					attributeEquals("abc", "xyz")
				);
		assertNotNull(and);
		assertEquals(2, and.getChildrenCount());
		assertEquals("abc", ((AttributeEquals)and.getChildren()[0]).getAttributeName());
		assertEquals("def", ((AttributeEquals)and.getChildren()[0]).getAttributeValue());
		assertEquals("abc", ((AttributeEquals)and.getChildren()[1]).getAttributeName());
		assertEquals("xyz", ((AttributeEquals)and.getChildren()[1]).getAttributeValue());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(new And(attributeEquals("abc", "def")).isApplicable());
		assertFalse(new And().isApplicable());
	}

	@Test
	void shouldRecognizeNecessity() {
		assertTrue(new And(attributeEquals("abc", "def"), attributeEquals("xyz", "def")).isNecessary());
		assertFalse(new And(attributeEquals("abc", "def")).isNecessary());
		assertFalse(new And().isNecessary());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final ConstraintContainer<FilterConstraint> and =
				and(
						attributeEquals("abc", '\''),
						attributeEquals("abc", 'x')
				);
		assertNotNull(and);
		assertEquals("and(attributeEquals('abc','\\''),attributeEquals('abc','x'))", and.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(createAndConstraint("abc", "def"), createAndConstraint("abc", "def"));
		assertEquals(createAndConstraint("abc", "def"), createAndConstraint("abc", "def"));
		assertNotEquals(createAndConstraint("abc", "def"), createAndConstraint("abc", "defe"));
		assertNotEquals(createAndConstraint("abc", "def"), createAndConstraint("abc", null));
		assertNotEquals(createAndConstraint("abc", "def"), createAndConstraint(null, "abc"));
		assertEquals(createAndConstraint("abc", "def").hashCode(), createAndConstraint("abc", "def").hashCode());
		assertNotEquals(createAndConstraint("abc", "def").hashCode(), createAndConstraint("abc", "defe").hashCode());
		assertNotEquals(createAndConstraint("abc", "def").hashCode(), createAndConstraint("abc", null).hashCode());
		assertNotEquals(createAndConstraint("abc", "def").hashCode(), createAndConstraint(null, "abc").hashCode());
	}

	private static And createAndConstraint(String... values) {
		return and(
				Arrays.stream(values)
						.map(it -> attributeEquals("abc", it))
						.toArray(FilterConstraint[]::new)
		);
	}

}
