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

import io.evitadb.api.query.FacetConstraint;
import io.evitadb.api.query.FilterConstraint;
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
		final HierarchyWithin hierarchyWithin = hierarchyWithin("brand", entityPrimaryKeyInSet(1));
		assertEquals("brand", hierarchyWithin.getReferenceName().orElse(null));
		assertEquals(entityPrimaryKeyInSet(1), hierarchyWithin.getParentFilter());
		assertArrayEquals(new FacetConstraint[0], hierarchyWithin.getExcludedChildrenFilter());
		assertFalse(hierarchyWithin.isDirectRelation());
		assertFalse(hierarchyWithin.isExcludingRoot());
	}

	@Test
	void shouldCreateViaFactoryUsingExcludingSubConstraintClassWorkAsExpected() {
		final HierarchyWithin hierarchyWithin = hierarchyWithin("brand", entityPrimaryKeyInSet(1), excluding(entityPrimaryKeyInSet(5, 7)));
		assertEquals("brand", hierarchyWithin.getReferenceName().orElse(null));
		assertEquals(entityPrimaryKeyInSet(1), hierarchyWithin.getParentFilter());
		assertArrayEquals(new FilterConstraint[] {new EntityPrimaryKeyInSet(5, 7)}, hierarchyWithin.getExcludedChildrenFilter());
		assertFalse(hierarchyWithin.isDirectRelation());
		assertFalse(hierarchyWithin.isExcludingRoot());
	}

	@Test
	void shouldCreateViaFactoryUsingExcludingRootSubConstraintClassWorkAsExpected() {
		final HierarchyWithin hierarchyWithin = hierarchyWithin("brand", entityPrimaryKeyInSet(1), excludingRoot());
		assertEquals("brand", hierarchyWithin.getReferenceName().orElse(null));
		assertEquals(entityPrimaryKeyInSet(1), hierarchyWithin.getParentFilter());
		assertArrayEquals(new FilterConstraint[0], hierarchyWithin.getExcludedChildrenFilter());
		assertFalse(hierarchyWithin.isDirectRelation());
		assertTrue(hierarchyWithin.isExcludingRoot());
	}

	@Test
	void shouldCreateViaFactoryWithoutEntityTypeClassWorkAsExpected() {
		final HierarchyWithin hierarchyWithin = hierarchyWithinSelf(entityPrimaryKeyInSet(1), excludingRoot());
		assertNull(hierarchyWithin.getReferenceName().orElse(null));
		assertEquals(entityPrimaryKeyInSet(1), hierarchyWithin.getParentFilter());
		assertArrayEquals(new FilterConstraint[0], hierarchyWithin.getExcludedChildrenFilter());
		assertFalse(hierarchyWithin.isDirectRelation());
		assertTrue(hierarchyWithin.isExcludingRoot());
	}

	@Test
	void shouldCreateViaFactoryUsingDirectRelationSubConstraintClassWorkAsExpected() {
		final HierarchyWithin hierarchyWithin = hierarchyWithin("brand", entityPrimaryKeyInSet(1), directRelation());
		assertEquals("brand", hierarchyWithin.getReferenceName().orElse(null));
		assertEquals(entityPrimaryKeyInSet(1), hierarchyWithin.getParentFilter());
		assertArrayEquals(new FilterConstraint[0], hierarchyWithin.getExcludedChildrenFilter());
		assertTrue(hierarchyWithin.isDirectRelation());
		assertFalse(hierarchyWithin.isExcludingRoot());
	}

	@Test
	void shouldCreateViaFactoryUsingAllPossibleSubConstraintClassWorkAsExpected() {
		final HierarchyWithin hierarchyWithin = hierarchyWithin(
			"brand", entityPrimaryKeyInSet(1),
			excludingRoot(),
			excluding(entityPrimaryKeyInSet(3, 7))
		);
		assertEquals("brand", hierarchyWithin.getReferenceName().orElse(null));
		assertEquals(entityPrimaryKeyInSet(1), hierarchyWithin.getParentFilter());
		assertArrayEquals(new FilterConstraint[] {new EntityPrimaryKeyInSet(3, 7)}, hierarchyWithin.getExcludedChildrenFilter());
		assertFalse(hierarchyWithin.isDirectRelation());
		assertTrue(hierarchyWithin.isExcludingRoot());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertFalse(new HierarchyWithin("brand", null).isApplicable());
		assertTrue(hierarchyWithin("brand", entityPrimaryKeyInSet(1)).isApplicable());
		assertTrue(hierarchyWithin("brand", entityPrimaryKeyInSet(1), excluding(entityPrimaryKeyInSet(5, 7))).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final HierarchyWithin hierarchyWithin = hierarchyWithin("brand", entityPrimaryKeyInSet(1), excluding(entityPrimaryKeyInSet(5, 7)));
		assertEquals("hierarchyWithin('brand',entityPrimaryKeyInSet(1),excluding(entityPrimaryKeyInSet(5,7)))", hierarchyWithin.toString());
	}

	@Test
	void shouldToStringReturnExpectedFormatWhenUsingMultipleSubConstraints() {
		final HierarchyWithin hierarchyWithin = hierarchyWithin(
			"brand", entityPrimaryKeyInSet(1),
			excludingRoot(),
			excluding(entityPrimaryKeyInSet(3, 7))
		);;
		assertEquals("hierarchyWithin('brand',entityPrimaryKeyInSet(1),excludingRoot(),excluding(entityPrimaryKeyInSet(3,7)))", hierarchyWithin.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(hierarchyWithin("brand", entityPrimaryKeyInSet(1),excluding(entityPrimaryKeyInSet(1, 5))), hierarchyWithin("brand", entityPrimaryKeyInSet(1),excluding(entityPrimaryKeyInSet(1, 5))));
		assertEquals(hierarchyWithin("brand", entityPrimaryKeyInSet(1),excluding(entityPrimaryKeyInSet(1, 5))), hierarchyWithin("brand", entityPrimaryKeyInSet(1),excluding(entityPrimaryKeyInSet(1, 5))));
		assertNotEquals(hierarchyWithin("brand", entityPrimaryKeyInSet(1),excluding(entityPrimaryKeyInSet(1, 5))), hierarchyWithin("brand", entityPrimaryKeyInSet(1),excluding(entityPrimaryKeyInSet(1, 6))));
		assertNotEquals(hierarchyWithin("brand", entityPrimaryKeyInSet(1),excluding(entityPrimaryKeyInSet(1, 5))), hierarchyWithin("brand", entityPrimaryKeyInSet(1), excluding(entityPrimaryKeyInSet(1))));
		assertNotEquals(hierarchyWithin("brand", entityPrimaryKeyInSet(1),excluding(entityPrimaryKeyInSet(1, 5))), hierarchyWithin("brand", entityPrimaryKeyInSet(2),excluding(entityPrimaryKeyInSet(1, 5))));
		assertNotEquals(hierarchyWithin("brand", entityPrimaryKeyInSet(1),excluding(entityPrimaryKeyInSet(1, 5))), hierarchyWithin("category", entityPrimaryKeyInSet(1),excluding(entityPrimaryKeyInSet(1, 6))));
		assertNotEquals(hierarchyWithin("brand", entityPrimaryKeyInSet(1),excluding(entityPrimaryKeyInSet(1, 5))), hierarchyWithin("brand", entityPrimaryKeyInSet(1), excluding(entityPrimaryKeyInSet(1))));
		assertEquals(hierarchyWithin("brand", entityPrimaryKeyInSet(1),excluding(entityPrimaryKeyInSet(1, 5))).hashCode(), hierarchyWithin("brand", entityPrimaryKeyInSet(1),excluding(entityPrimaryKeyInSet(1, 5))).hashCode());
		assertNotEquals(hierarchyWithin("brand", entityPrimaryKeyInSet(1),excluding(entityPrimaryKeyInSet(1, 5))).hashCode(), hierarchyWithin("brand", entityPrimaryKeyInSet(1),excluding(entityPrimaryKeyInSet(1, 6))).hashCode());
		assertNotEquals(hierarchyWithin("brand", entityPrimaryKeyInSet(1),excluding(entityPrimaryKeyInSet(1, 5))).hashCode(), hierarchyWithin("brand", entityPrimaryKeyInSet(1), excluding(entityPrimaryKeyInSet(1))).hashCode());
	}

}
