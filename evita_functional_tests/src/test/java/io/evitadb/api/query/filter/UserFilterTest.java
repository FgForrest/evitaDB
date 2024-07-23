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
import static io.evitadb.api.query.QueryConstraints.userFilter;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link UserFilter} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class UserFilterTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final ConstraintContainer<FilterConstraint> userFilter =
				userFilter(
					attributeEquals("abc", "def"),
					attributeEquals("abc", "xyz")
				);
		assertNotNull(userFilter);
		assertEquals(2, userFilter.getChildrenCount());
		assertEquals("abc", ((AttributeEquals)userFilter.getChildren()[0]).getAttributeName());
		assertEquals("def", ((AttributeEquals)userFilter.getChildren()[0]).getAttributeValue());
		assertEquals("abc", ((AttributeEquals)userFilter.getChildren()[1]).getAttributeName());
		assertEquals("xyz", ((AttributeEquals)userFilter.getChildren()[1]).getAttributeValue());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(new UserFilter(attributeEquals("abc", "def")).isApplicable());
		assertFalse(new UserFilter().isApplicable());
	}

	@Test
	void shouldRecognizeNecessity() {
		assertTrue(new UserFilter(attributeEquals("abc", "def"), attributeEquals("xyz", "def")).isNecessary());
		assertTrue(new UserFilter(attributeEquals("abc", "def")).isNecessary());
		assertFalse(new UserFilter().isNecessary());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final ConstraintContainer<FilterConstraint> userFilter =
				userFilter(
						attributeEquals("abc", '\''),
						attributeEquals("abc", 'x')
				);
		assertNotNull(userFilter);
		assertEquals("userFilter(attributeEquals('abc','\\''),attributeEquals('abc','x'))", userFilter.toString());
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

	private static UserFilter createAndConstraint(String... values) {
		return userFilter(
				Arrays.stream(values)
						.map(it -> new AttributeEquals("abc", it))
						.toArray(FilterConstraint[]::new)
		);
	}

}
