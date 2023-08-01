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

import static io.evitadb.api.query.QueryConstraints.attributeContent;
import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link AttributeContent} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class AttributeContentTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final AttributeContent attributeContent = attributeContentAll();
		assertArrayEquals(new String[0], attributeContent.getAttributeNames());

		final AttributeContent attributeContent2 = attributeContent("a", "b");
		assertArrayEquals(new String[] {"a", "b"}, attributeContent2.getAttributeNames());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(attributeContentAll().isApplicable());
		assertTrue(attributeContentAll().isApplicable());
		assertTrue(attributeContent("a").isApplicable());
		assertTrue(attributeContent("a", "b").isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final AttributeContent attributeContent = attributeContent("a", "b");
		assertEquals("attributeContent('a','b')", attributeContent.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(attributeContent("a", "b"), attributeContent("a", "b"));
		assertEquals(attributeContent("a", "b"), attributeContent("a", "b"));
		assertEquals(attributeContentAll(), attributeContentAll());
		assertNotEquals(attributeContent("a", "e"), attributeContent("a", "b"));
		assertNotEquals(attributeContent("a", "e"), attributeContent("a"));
		assertEquals(attributeContent("a", "b").hashCode(), attributeContent("a", "b").hashCode());
		assertEquals(attributeContentAll().hashCode(), attributeContentAll().hashCode());
		assertNotEquals(attributeContent("a", "e").hashCode(), attributeContent("a", "b").hashCode());
		assertNotEquals(attributeContent("a", "e").hashCode(), attributeContent("a").hashCode());
	}

	@Test
	void shouldCombineWithAnotherConstraint() {
		assertEquals(attributeContentAll(), attributeContentAll().combineWith(attributeContent("A")));
		assertEquals(attributeContentAll(), attributeContent("A").combineWith(attributeContentAll()));
		assertEquals(attributeContent("A"), attributeContent("A").combineWith(attributeContent("A")));
		assertEquals(attributeContent("A", "B"), attributeContent("A").combineWith(attributeContent("B")));
	}

}