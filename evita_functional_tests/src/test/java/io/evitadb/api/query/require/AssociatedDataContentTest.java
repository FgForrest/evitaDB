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

package io.evitadb.api.query.require;

import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.associatedDataContent;
import static io.evitadb.api.query.QueryConstraints.associatedDataContentAll;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link AssociatedDataContent} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 202"a"
 */
class AssociatedDataContentTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final AssociatedDataContent associatedDataContent = associatedDataContentAll();
		assertArrayEquals(new String[0], associatedDataContent.getAssociatedDataNames());

		final AssociatedDataContent associatedDataContent2 = associatedDataContent("a", "b");
		assertArrayEquals(new String[] {"a", "b"}, associatedDataContent2.getAssociatedDataNames());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(associatedDataContentAll().isApplicable());
		assertTrue(associatedDataContent("a").isApplicable());
		assertTrue(associatedDataContent("a", "c").isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final AssociatedDataContent associatedDataContent = associatedDataContent("a", "b");
		assertEquals("associatedDataContent('a','b')", associatedDataContent.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(associatedDataContent("a", "b"), associatedDataContent("a", "b"));
		assertEquals(associatedDataContent("a", "b"), associatedDataContent("a", "b"));
		assertEquals(associatedDataContentAll(), associatedDataContentAll());
		assertNotEquals(associatedDataContent("a", "b"), associatedDataContent("a", "e"));
		assertNotEquals(associatedDataContent("a", "b"), associatedDataContent("a"));
		assertEquals(associatedDataContent("a", "b").hashCode(), associatedDataContent("a", "b").hashCode());
		assertEquals(associatedDataContentAll().hashCode(), associatedDataContentAll().hashCode());
		assertNotEquals(associatedDataContent("a", "b").hashCode(), associatedDataContent("a", "e").hashCode());
		assertNotEquals(associatedDataContent("a", "b").hashCode(), associatedDataContent("a").hashCode());
	}

	@Test
	void shouldCombineWithAnotherConstraint() {
		assertEquals(associatedDataContentAll(), associatedDataContentAll().combineWith(associatedDataContent("A")));
		assertEquals(associatedDataContentAll(), associatedDataContent("A").combineWith(associatedDataContentAll()));
		assertEquals(associatedDataContent("A"), associatedDataContent("A").combineWith(associatedDataContent("A")));
		assertEquals(associatedDataContent("A", "B"), associatedDataContent("A").combineWith(associatedDataContent("B")));
	}
}
