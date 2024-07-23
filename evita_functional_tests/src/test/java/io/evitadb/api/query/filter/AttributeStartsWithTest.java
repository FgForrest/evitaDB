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

import static io.evitadb.api.query.QueryConstraints.attributeStartsWith;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link AttributeStartsWith} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class AttributeStartsWithTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final AttributeStartsWith attributeStartsWith = attributeStartsWith("abc", "def");
		assertEquals("abc", attributeStartsWith.getAttributeName());
		assertEquals("def", attributeStartsWith.getTextToSearch());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertFalse(new AttributeStartsWith("abc", null).isApplicable());
		assertFalse(new AttributeStartsWith(null, "abc").isApplicable());
		assertFalse(new AttributeStartsWith(null, null).isApplicable());
		assertTrue(attributeStartsWith("abc", "def").isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final AttributeStartsWith attributeStartsWith = attributeStartsWith("abc", "def");
		assertEquals("attributeStartsWith('abc','def')", attributeStartsWith.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(attributeStartsWith("abc", "def"), attributeStartsWith("abc", "def"));
		assertEquals(attributeStartsWith("abc", "def"), attributeStartsWith("abc", "def"));
		assertNotEquals(attributeStartsWith("abc", "def"), attributeStartsWith("abc", "defe"));
		assertNotEquals(attributeStartsWith("abc", "def"), new AttributeStartsWith("abc", null));
		assertNotEquals(attributeStartsWith("abc", "def"), new AttributeStartsWith(null, "abc"));
		assertEquals(attributeStartsWith("abc", "def").hashCode(), attributeStartsWith("abc", "def").hashCode());
		assertNotEquals(attributeStartsWith("abc", "def").hashCode(), attributeStartsWith("abc", "defe").hashCode());
		assertNotEquals(attributeStartsWith("abc", "def").hashCode(), new AttributeStartsWith("abc", null).hashCode());
		assertNotEquals(attributeStartsWith("abc", "def").hashCode(), new AttributeStartsWith(null, "abc").hashCode());
	}

}
