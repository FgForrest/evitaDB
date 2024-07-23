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

import static io.evitadb.api.query.QueryConstraints.attributeContains;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link AttributeContains} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class AttributeContainsTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final AttributeContains attributeContains = attributeContains("abc", "def");
		assertEquals("abc", attributeContains.getAttributeName());
		assertEquals("def", attributeContains.getTextToSearch());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertFalse(new AttributeContains("abc", null).isApplicable());
		assertFalse(new AttributeContains(null, "abc").isApplicable());
		assertFalse(new AttributeContains(null, null).isApplicable());
		assertTrue(attributeContains("abc", "def").isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final AttributeContains attributeContains = attributeContains("abc", "def");
		assertEquals("attributeContains('abc','def')", attributeContains.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(attributeContains("abc", "def"), attributeContains("abc", "def"));
		assertEquals(attributeContains("abc", "def"), attributeContains("abc", "def"));
		assertNotEquals(attributeContains("abc", "def"), attributeContains("abc", "defe"));
		assertNotEquals(attributeContains("abc", "def"), new AttributeContains("abc", null));
		assertNotEquals(attributeContains("abc", "def"), new AttributeContains(null, "abc"));
		assertEquals(attributeContains("abc", "def").hashCode(), attributeContains("abc", "def").hashCode());
		assertNotEquals(attributeContains("abc", "def").hashCode(), attributeContains("abc", "defe").hashCode());
		assertNotEquals(attributeContains("abc", "def").hashCode(), new AttributeContains("abc", null).hashCode());
		assertNotEquals(attributeContains("abc", "def").hashCode(), new AttributeContains(null, "abc").hashCode());
	}

}
