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

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This tests verifies basic properties of {@link HierarchyContent} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 202"a"
 */
class HierarchyContentTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final HierarchyContent hierarchyContent1 = hierarchyContent();
		assertTrue(hierarchyContent1.getStopAt().isEmpty());
		assertTrue(hierarchyContent1.getEntityFetch().isEmpty());

		final HierarchyContent hierarchyContent2 = hierarchyContent(stopAt(distance(1)));
		assertEquals(stopAt(distance(1)), hierarchyContent2.getStopAt().orElse(null));
		assertTrue(hierarchyContent2.getEntityFetch().isEmpty());

		final HierarchyContent hierarchyContent3 = hierarchyContent(entityFetch());
		assertTrue(hierarchyContent3.getStopAt().isEmpty());
		assertEquals(entityFetch(), hierarchyContent3.getEntityFetch().orElse(null));

		final HierarchyContent hierarchyContent4 = hierarchyContent(
			stopAt(distance(1)), entityFetch()
		);
		assertEquals(stopAt(distance(1)), hierarchyContent4.getStopAt().orElse(null));
		assertEquals(entityFetch(), hierarchyContent4.getEntityFetch().orElse(null));
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(hierarchyContent().isApplicable());
		assertTrue(hierarchyContent(stopAt(distance(1))).isApplicable());
		assertTrue(hierarchyContent(entityFetch(attributeContent())).isApplicable());
		assertTrue(hierarchyContent(stopAt(distance(1)), entityFetch(attributeContent())).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final HierarchyContent hierarchyContent1 = hierarchyContent();
		assertEquals("hierarchyContent()", hierarchyContent1.toString());

		final HierarchyContent hierarchyContent2 = hierarchyContent(stopAt(distance(1)));
		assertEquals("hierarchyContent(stopAt(distance(1)))", hierarchyContent2.toString());

		final HierarchyContent hierarchyContent3 = hierarchyContent(entityFetch(attributeContent()));
		assertEquals("hierarchyContent(entityFetch(attributeContent()))", hierarchyContent3.toString());

		final HierarchyContent hierarchyContent4 = hierarchyContent(stopAt(distance(1)), entityFetch(attributeContent()));
		assertEquals("hierarchyContent(stopAt(distance(1)),entityFetch(attributeContent()))", hierarchyContent4.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(hierarchyContent(), hierarchyContent());
		assertEquals(hierarchyContent(), hierarchyContent());
		assertEquals(hierarchyContent(stopAt(distance(1))), hierarchyContent(stopAt(distance(1))));
		assertEquals(hierarchyContent(entityFetch(attributeContent())), hierarchyContent(entityFetch(attributeContent())));
		assertEquals(hierarchyContent(stopAt(distance(1)), entityFetch(attributeContent())), hierarchyContent(stopAt(distance(1)), entityFetch(attributeContent())));
		assertNotEquals(hierarchyContent(), hierarchyContent(stopAt(distance(1))));
		assertNotEquals(hierarchyContent(), hierarchyContent(entityFetch(attributeContent())));
		assertNotEquals(hierarchyContent(stopAt(distance(1))), hierarchyContent(entityFetch(attributeContent())));
		assertNotEquals(hierarchyContent(stopAt(distance(1))), hierarchyContent(stopAt(distance(2))));
		assertEquals(hierarchyContent().hashCode(), hierarchyContent().hashCode());
		assertEquals(hierarchyContent(stopAt(distance(1))).hashCode(), hierarchyContent(stopAt(distance(1))).hashCode());
		assertEquals(hierarchyContent(entityFetch(attributeContent())).hashCode(), hierarchyContent(entityFetch(attributeContent())).hashCode());
		assertEquals(hierarchyContent(stopAt(distance(1)), entityFetch(attributeContent())).hashCode(), hierarchyContent(stopAt(distance(1)), entityFetch(attributeContent())).hashCode());
		assertNotEquals(hierarchyContent().hashCode(), hierarchyContent(stopAt(distance(1))).hashCode());
		assertNotEquals(hierarchyContent().hashCode(), hierarchyContent(entityFetch(attributeContent())).hashCode());
		assertNotEquals(hierarchyContent(stopAt(distance(1))).hashCode(), hierarchyContent(entityFetch(attributeContent())).hashCode());
		assertNotEquals(hierarchyContent(stopAt(distance(1))).hashCode(), hierarchyContent(stopAt(distance(2))).hashCode());
	}

}