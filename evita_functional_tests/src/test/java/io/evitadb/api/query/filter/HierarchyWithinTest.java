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

package io.evitadb.api.query.filter;

import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link HierarchyWithin} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class HierarchyWithinTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final HierarchyWithin hierarchyWithin = hierarchyWithin("brand", 1);
		assertEquals("brand", hierarchyWithin.getReferenceName());
		assertEquals(1, hierarchyWithin.getParentId());
		assertArrayEquals(new int[0], hierarchyWithin.getExcludedChildrenIds());
		assertFalse(hierarchyWithin.isDirectRelation());
		assertFalse(hierarchyWithin.isExcludingRoot());
	}

	@Test
	void shouldCreateViaFactoryUsingExcludingSubConstraintClassWorkAsExpected() {
		final HierarchyWithin hierarchyWithin = hierarchyWithin("brand", 1, excluding(5, 7));
		assertEquals("brand", hierarchyWithin.getReferenceName());
		assertEquals(1, hierarchyWithin.getParentId());
		assertArrayEquals(new int[] {5, 7}, hierarchyWithin.getExcludedChildrenIds());
		assertFalse(hierarchyWithin.isDirectRelation());
		assertFalse(hierarchyWithin.isExcludingRoot());
	}

	@Test
	void shouldCreateViaFactoryUsingExcludingRootSubConstraintClassWorkAsExpected() {
		final HierarchyWithin hierarchyWithin = hierarchyWithin("brand", 1, excludingRoot());
		assertEquals("brand", hierarchyWithin.getReferenceName());
		assertEquals(1, hierarchyWithin.getParentId());
		assertArrayEquals(new int[0], hierarchyWithin.getExcludedChildrenIds());
		assertFalse(hierarchyWithin.isDirectRelation());
		assertTrue(hierarchyWithin.isExcludingRoot());
	}

	@Test
	void shouldCreateViaFactoryWithoutEntityTypeClassWorkAsExpected() {
		final HierarchyWithin hierarchyWithin = hierarchyWithinSelf(1, excludingRoot());
		assertNull(hierarchyWithin.getReferenceName());
		assertEquals(1, hierarchyWithin.getParentId());
		assertArrayEquals(new int[0], hierarchyWithin.getExcludedChildrenIds());
		assertFalse(hierarchyWithin.isDirectRelation());
		assertTrue(hierarchyWithin.isExcludingRoot());
	}

	@Test
	void shouldCreateViaFactoryUsingDirectRelationSubConstraintClassWorkAsExpected() {
		final HierarchyWithin hierarchyWithin = hierarchyWithin("brand", 1, directRelation());
		assertEquals("brand", hierarchyWithin.getReferenceName());
		assertEquals(1, hierarchyWithin.getParentId());
		assertArrayEquals(new int[0], hierarchyWithin.getExcludedChildrenIds());
		assertTrue(hierarchyWithin.isDirectRelation());
		assertFalse(hierarchyWithin.isExcludingRoot());
	}

	@Test
	void shouldCreateViaFactoryUsingAllPossibleSubConstraintClassWorkAsExpected() {
		final HierarchyWithin hierarchyWithin = hierarchyWithin(
			"brand", 1,
			excludingRoot(),
			excluding(3, 7)
		);
		assertEquals("brand", hierarchyWithin.getReferenceName());
		assertEquals(1, hierarchyWithin.getParentId());
		assertArrayEquals(new int[] {3, 7}, hierarchyWithin.getExcludedChildrenIds());
		assertFalse(hierarchyWithin.isDirectRelation());
		assertTrue(hierarchyWithin.isExcludingRoot());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertFalse(new HierarchyWithin("brand", null).isApplicable());
		assertTrue(hierarchyWithin("brand", 1).isApplicable());
		assertTrue(hierarchyWithin("brand", 1, excluding(5, 7)).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final HierarchyWithin hierarchyWithin = hierarchyWithin("brand", 1, excluding(5, 7));
		assertEquals("hierarchyWithin('brand',1,excluding(5,7))", hierarchyWithin.toString());
	}

	@Test
	void shouldToStringReturnExpectedFormatWhenUsingMultipleSubConstraints() {
		final HierarchyWithin hierarchyWithin = hierarchyWithin(
			"brand", 1,
			excludingRoot(),
			excluding(3, 7)
		);;
		assertEquals("hierarchyWithin('brand',1,excludingRoot(),excluding(3,7))", hierarchyWithin.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(hierarchyWithin("brand", 1,excluding(1, 5)), hierarchyWithin("brand", 1,excluding(1, 5)));
		assertEquals(hierarchyWithin("brand", 1,excluding(1, 5)), hierarchyWithin("brand", 1,excluding(1, 5)));
		assertNotEquals(hierarchyWithin("brand", 1,excluding(1, 5)), hierarchyWithin("brand", 1,excluding(1, 6)));
		assertNotEquals(hierarchyWithin("brand", 1,excluding(1, 5)), hierarchyWithin("brand", 1, excluding(1)));
		assertNotEquals(hierarchyWithin("brand", 1,excluding(1, 5)), hierarchyWithin("brand", 2,excluding(1, 5)));
		assertNotEquals(hierarchyWithin("brand", 1,excluding(1, 5)), hierarchyWithin("category", 1,excluding(1, 6)));
		assertNotEquals(hierarchyWithin("brand", 1,excluding(1, 5)), hierarchyWithin("brand", 1, excluding(1)));
		assertEquals(hierarchyWithin("brand", 1,excluding(1, 5)).hashCode(), hierarchyWithin("brand", 1,excluding(1, 5)).hashCode());
		assertNotEquals(hierarchyWithin("brand", 1,excluding(1, 5)).hashCode(), hierarchyWithin("brand", 1,excluding(1, 6)).hashCode());
		assertNotEquals(hierarchyWithin("brand", 1,excluding(1, 5)).hashCode(), hierarchyWithin("brand", 1, excluding(1)).hashCode());
	}

}
