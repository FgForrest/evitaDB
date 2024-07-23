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

import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.or;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link Or} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class OrTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final ConstraintContainer<FilterConstraint> or =
				or(
					attributeEquals("abc", "def"),
					attributeEquals("abc", "xyz")
				);
		assertNotNull(or);
		assertEquals(2, or.getChildrenCount());
		assertEquals("abc", ((AttributeEquals)or.getChildren()[0]).getAttributeName());
		assertEquals("def", ((AttributeEquals)or.getChildren()[0]).getAttributeValue());
		assertEquals("abc", ((AttributeEquals)or.getChildren()[1]).getAttributeName());
		assertEquals("xyz", ((AttributeEquals)or.getChildren()[1]).getAttributeValue());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(new Or(attributeEquals("abc", "def")).isApplicable());
		assertFalse(new Or().isApplicable());
	}

	@Test
	void shouldRecognizeNecessity() {
		assertTrue(new Or(attributeEquals("abc", "def"), attributeEquals("xyz", "def")).isNecessary());
		assertFalse(new Or(attributeEquals("abc", "def")).isNecessary());
		assertFalse(new Or().isNecessary());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final ConstraintContainer<FilterConstraint> or =
				or(
						attributeEquals("abc", '\''),
						attributeEquals("abc", 'x')
				);
		assertNotNull(or);
		assertEquals("or(attributeEquals('abc','\\''),attributeEquals('abc','x'))", or.toString());
	}

	@Test
	void shouldConformToEqualsOrHashContract() {
		assertNotSame(createOrConstraint("abc", "def"), createOrConstraint("abc", "def"));
		assertEquals(createOrConstraint("abc", "def"), createOrConstraint("abc", "def"));
		assertNotEquals(createOrConstraint("abc", "def"), createOrConstraint("abc", "defe"));
		assertNotEquals(createOrConstraint("abc", "def"), createOrConstraint("abc", null));
		assertNotEquals(createOrConstraint("abc", "def"), createOrConstraint(null, "abc"));
		assertEquals(createOrConstraint("abc", "def").hashCode(), createOrConstraint("abc", "def").hashCode());
		assertNotEquals(createOrConstraint("abc", "def").hashCode(), createOrConstraint("abc", "defe").hashCode());
		assertNotEquals(createOrConstraint("abc", "def").hashCode(), createOrConstraint("abc", null).hashCode());
		assertNotEquals(createOrConstraint("abc", "def").hashCode(), createOrConstraint(null, "abc").hashCode());
	}

	private static Or createOrConstraint(String... values) {
		return or(
				Arrays.stream(values)
						.map(it -> attributeEquals("abc", it))
						.toArray(FilterConstraint[]::new)
		);
	}

}
