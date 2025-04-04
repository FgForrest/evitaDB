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

import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link HierarchyContent} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
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
		assertTrue(hierarchyContent(entityFetch(attributeContentAll())).isApplicable());
		assertTrue(hierarchyContent(stopAt(distance(1)), entityFetch(attributeContentAll())).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final HierarchyContent hierarchyContent1 = hierarchyContent();
		assertEquals("hierarchyContent()", hierarchyContent1.toString());

		final HierarchyContent hierarchyContent2 = hierarchyContent(stopAt(distance(1)));
		assertEquals("hierarchyContent(stopAt(distance(1)))", hierarchyContent2.toString());

		final HierarchyContent hierarchyContent3 = hierarchyContent(entityFetch(attributeContentAll()));
		assertEquals("hierarchyContent(entityFetch(attributeContentAll()))", hierarchyContent3.toString());

		final HierarchyContent hierarchyContent4 = hierarchyContent(stopAt(distance(1)), entityFetch(attributeContentAll()));
		assertEquals("hierarchyContent(stopAt(distance(1)),entityFetch(attributeContentAll()))", hierarchyContent4.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(hierarchyContent(), hierarchyContent());
		assertEquals(hierarchyContent(), hierarchyContent());
		assertEquals(hierarchyContent(stopAt(distance(1))), hierarchyContent(stopAt(distance(1))));
		assertEquals(hierarchyContent(entityFetch(attributeContentAll())), hierarchyContent(entityFetch(attributeContentAll())));
		assertEquals(hierarchyContent(stopAt(distance(1)), entityFetch(attributeContentAll())), hierarchyContent(stopAt(distance(1)), entityFetch(attributeContentAll())));
		assertNotEquals(hierarchyContent(), hierarchyContent(stopAt(distance(1))));
		assertNotEquals(hierarchyContent(), hierarchyContent(entityFetch(attributeContentAll())));
		assertNotEquals(hierarchyContent(stopAt(distance(1))), hierarchyContent(entityFetch(attributeContentAll())));
		assertNotEquals(hierarchyContent(stopAt(distance(1))), hierarchyContent(stopAt(distance(2))));
		assertEquals(hierarchyContent().hashCode(), hierarchyContent().hashCode());
		assertEquals(hierarchyContent(stopAt(distance(1))).hashCode(), hierarchyContent(stopAt(distance(1))).hashCode());
		assertEquals(hierarchyContent(entityFetch(attributeContentAll())).hashCode(), hierarchyContent(entityFetch(attributeContentAll())).hashCode());
		assertEquals(hierarchyContent(stopAt(distance(1)), entityFetch(attributeContentAll())).hashCode(), hierarchyContent(stopAt(distance(1)), entityFetch(attributeContentAll())).hashCode());
		assertNotEquals(hierarchyContent().hashCode(), hierarchyContent(stopAt(distance(1))).hashCode());
		assertNotEquals(hierarchyContent().hashCode(), hierarchyContent(entityFetch(attributeContentAll())).hashCode());
		assertNotEquals(hierarchyContent(stopAt(distance(1))).hashCode(), hierarchyContent(entityFetch(attributeContentAll())).hashCode());
		assertNotEquals(hierarchyContent(stopAt(distance(1))).hashCode(), hierarchyContent(stopAt(distance(2))).hashCode());
	}

	@Test
	void shouldFullyContainWhenHierarchyContentIsEmpty() {
		assertTrue(hierarchyContent().isFullyContainedWithin(hierarchyContent()));
	}

	@Test
	void shouldNotFullyContainWhenStopAtIsPresentInHierarchyContent() {
		assertFalse(hierarchyContent(stopAt(distance(1))).isFullyContainedWithin(hierarchyContent()));
	}

	@Test
	void shouldFullyContainWhenEntityFetchIsPresentInBothHierarchyContents() {
		assertTrue(hierarchyContent(entityFetch(attributeContent())).isFullyContainedWithin(hierarchyContent(entityFetchAll())));
	}

	@Test
	void shouldNotFullyContainWhenEntityFetchIsOnlyInOneHierarchyContent() {
		assertFalse(hierarchyContent(entityFetchAll()).isFullyContainedWithin(hierarchyContent()));
	}

	@Test
	void shouldReturnEmptyStopAtWhenNotPresent() {
		final HierarchyContent hierarchyContent = hierarchyContent();
		assertFalse(hierarchyContent.getStopAt().isPresent());
	}

	@Test
	void shouldReturnEmptyEntityFetchWhenNotPresent() {
		final HierarchyContent hierarchyContent = hierarchyContent();
		assertFalse(hierarchyContent.getEntityFetch().isPresent());
	}

	@Test
	void shouldReturnStopAtWhenPresent() {
		final HierarchyContent hierarchyContent = hierarchyContent(stopAt(distance(1)));
		assertTrue(hierarchyContent.getStopAt().isPresent());
		assertEquals(stopAt(distance(1)), hierarchyContent.getStopAt().orElse(null));
	}

	@Test
	void shouldReturnEntityFetchWhenPresent() {
		final HierarchyContent hierarchyContent = hierarchyContent(entityFetch(attributeContentAll()));
		assertTrue(hierarchyContent.getEntityFetch().isPresent());
		assertEquals(entityFetch(attributeContentAll()), hierarchyContent.getEntityFetch().orElse(null));
	}

	@Test
	void shouldCombineWithAnotherHierarchyContent() {
		final HierarchyContent hierarchyContent1 = hierarchyContent(entityFetch(attributeContentAll()));
		final HierarchyContent hierarchyContent2 = hierarchyContent(entityFetch(associatedDataContentAll()));
		final HierarchyContent combined = hierarchyContent1.combineWith(hierarchyContent2);
		assertTrue(combined.getEntityFetch().isPresent());
		assertEquals(entityFetch(attributeContentAll(), associatedDataContentAll()), combined.getEntityFetch().orElse(null));
	}

	@Test
	void shouldNotCombineWithDifferentRequirement() {
		final HierarchyContent hierarchyContent = hierarchyContent();
		assertThrows(GenericEvitaInternalError.class, () -> hierarchyContent.combineWith(attributeContent()));
	}

	@Test
	void shouldThrowWhenCombiningWithStopAt() {
		final HierarchyContent hierarchyContent1 = hierarchyContent(stopAt(distance(1)));
		final HierarchyContent hierarchyContent2 = hierarchyContent(stopAt(distance(2)));
		assertThrows(EvitaInvalidUsageException.class, () -> hierarchyContent1.combineWith(hierarchyContent2));
	}

}
