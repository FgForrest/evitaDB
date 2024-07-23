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

import static io.evitadb.api.query.QueryConstraints.attributeEndsWith;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link AttributeEndsWith} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class AttributeEndsWithTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final AttributeEndsWith attributeEndsWith = attributeEndsWith("abc", "def");
		assertEquals("abc", attributeEndsWith.getAttributeName());
		assertEquals("def", attributeEndsWith.getTextToSearch());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertFalse(new AttributeEndsWith("abc", null).isApplicable());
		assertFalse(new AttributeEndsWith(null, "abc").isApplicable());
		assertFalse(new AttributeEndsWith(null, null).isApplicable());
		assertTrue(attributeEndsWith("abc", "def").isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final AttributeEndsWith attributeEndsWith = attributeEndsWith("abc", "def");
		assertEquals("attributeEndsWith('abc','def')", attributeEndsWith.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(attributeEndsWith("abc", "def"), attributeEndsWith("abc", "def"));
		assertEquals(attributeEndsWith("abc", "def"), attributeEndsWith("abc", "def"));
		assertNotEquals(attributeEndsWith("abc", "def"), attributeEndsWith("abc", "defe"));
		assertNotEquals(attributeEndsWith("abc", "def"), new AttributeEndsWith("abc", null));
		assertNotEquals(attributeEndsWith("abc", "def"), new AttributeEndsWith(null, "abc"));
		assertEquals(attributeEndsWith("abc", "def").hashCode(), attributeEndsWith("abc", "def").hashCode());
		assertNotEquals(attributeEndsWith("abc", "def").hashCode(), attributeEndsWith("abc", "defe").hashCode());
		assertNotEquals(attributeEndsWith("abc", "def").hashCode(), new AttributeEndsWith("abc", null).hashCode());
		assertNotEquals(attributeEndsWith("abc", "def").hashCode(), new AttributeEndsWith(null, "abc").hashCode());
	}

}
