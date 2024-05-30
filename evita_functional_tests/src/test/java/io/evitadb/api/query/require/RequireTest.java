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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.query.require;

import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.QueryConstraints;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.And;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link And} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class RequireTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final ConstraintContainer<RequireConstraint> require =
				require(
						entityFetch(),
						attributeHistogram(20, "abc")
				);
		assertNotNull(require);
		assertEquals(2, require.getChildrenCount());
		assertArrayEquals(new String[] {"abc"}, ((AttributeHistogram)require.getChildren()[1]).getAttributeNames());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(new Require(facetSummary()).isApplicable());
		assertFalse(new Require().isApplicable());
	}

	@Test
	void shouldRecognizeNecessity() {
		assertTrue(new Require(facetSummary(), attributeContentAll()).isNecessary());
		assertTrue(new Require(facetSummary()).isNecessary());
		assertFalse(new Require().isNecessary());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final ConstraintContainer<RequireConstraint> require =
				require(
					entityFetch(),
					attributeHistogram(20, "abc")
				);
		assertNotNull(require);
		assertEquals("require(entityFetch(),attributeHistogram(20,STANDARD,'abc'))", require.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(createRequireConstraint("abc", "def"), createRequireConstraint("abc", "def"));
		assertEquals(createRequireConstraint("abc", "def"), createRequireConstraint("abc", "def"));
		assertNotEquals(createRequireConstraint("abc", "def"), createRequireConstraint("abc", "defe"));
		assertNotEquals(createRequireConstraint("abc", "def"), createRequireConstraint("abc", null));
		assertNotEquals(createRequireConstraint("abc", "def"), createRequireConstraint(null, "abc"));
		assertEquals(createRequireConstraint("abc", "def").hashCode(), createRequireConstraint("abc", "def").hashCode());
		assertNotEquals(createRequireConstraint("abc", "def").hashCode(), createRequireConstraint("abc", "defe").hashCode());
		assertNotEquals(createRequireConstraint("abc", "def").hashCode(), createRequireConstraint("abc", null).hashCode());
		assertNotEquals(createRequireConstraint("abc", "def").hashCode(), createRequireConstraint(null, "abc").hashCode());
	}

	private static Require createRequireConstraint(String... values) {
		return require(
				Arrays.stream(values)
						.map(QueryConstraints::associatedDataContent)
						.toArray(RequireConstraint[]::new)
		);
	}

}
