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

import static io.evitadb.api.query.QueryConstraints.attributeInSet;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link AttributeInSet} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class AttributeInSetTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final AttributeInSet attributeInSet = attributeInSet("refs", 1, 5);
		assertArrayEquals(new Comparable<?>[] {1, 5}, attributeInSet.getAttributeValues());
	}

	@Test
	void shouldCreateViaFactoryClassWorkAsExpectedNullInArray() {
		final AttributeInSet attributeInSet = attributeInSet("refs", 1, null, 5);
		assertArrayEquals(new Comparable<?>[] {1, 5}, attributeInSet.getAttributeValues());
	}

	@Test
	void shouldCreateViaFactoryClassWorkAsExpectedForNullVariable() {
		final Integer nullInteger = null;
		final AttributeInSet attributeInSet = attributeInSet("refs", nullInteger);
		assertNull(attributeInSet);
	}

	@Test
	void shouldCreateViaFactoryClassWorkAsExpectedNullValueInArray() {
		final AttributeInSet attributeInSet = attributeInSet("refs", new Integer[0]);
		assertArrayEquals(new Comparable<?>[0], attributeInSet.getAttributeValues());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertFalse(new AttributeInSet(null).isApplicable());
		assertTrue(new AttributeInSet("refs").isApplicable());
		assertTrue(attributeInSet("refs", 1).isApplicable());
		assertTrue(attributeInSet("refs", 1, 2).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final AttributeInSet attributeInSet = attributeInSet("refs", 1, 5);
		assertEquals("attributeInSet('refs',1,5)", attributeInSet.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(attributeInSet("refs", 1, 5), attributeInSet("refs", 1, 5));
		assertEquals(attributeInSet("refs", 1, 5), attributeInSet("refs", 1, 5));
		assertNotEquals(attributeInSet("refs", 1, 5), attributeInSet("refs", 1, 6));
		assertNotEquals(attributeInSet("refs", 1, 5), attributeInSet("refs", 1));
		assertNotEquals(attributeInSet("refs", 1, 5), attributeInSet("def", 1, 5));
		assertEquals(attributeInSet("refs", 1, 5).hashCode(), attributeInSet("refs", 1, 5).hashCode());
		assertNotEquals(attributeInSet("refs", 1, 5).hashCode(), attributeInSet("refs", 1, 6).hashCode());
		assertNotEquals(attributeInSet("refs", 1, 5).hashCode(), attributeInSet("refs", 1).hashCode());
	}

}
