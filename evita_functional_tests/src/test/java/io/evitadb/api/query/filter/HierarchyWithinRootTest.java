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

import io.evitadb.api.query.FilterConstraint;
import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link HierarchyWithinRoot} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class HierarchyWithinRootTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final HierarchyWithinRoot hierarchyWithinRoot = hierarchyWithinRoot("brand");
		assertEquals("brand", hierarchyWithinRoot.getReferenceName().orElse(null));
		assertArrayEquals(new FilterConstraint[0], hierarchyWithinRoot.getExcludedChildrenFilter());
		assertFalse(hierarchyWithinRoot.isDirectRelation());
	}

	@Test
	void shouldCreateViaFactoryUsingExcludingSubConstraintClassWorkAsExpected() {
		final HierarchyWithinRoot hierarchyWithinRoot = hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(5, 7)));
		assertEquals("brand", hierarchyWithinRoot.getReferenceName().orElse(null));
		assertArrayEquals(new FilterConstraint[] {new EntityPrimaryKeyInSet(5, 7)}, hierarchyWithinRoot.getExcludedChildrenFilter());
		assertFalse(hierarchyWithinRoot.isDirectRelation());
	}

	@Test
	void shouldCreateViaFactoryUsingDirectRelationSubConstraintClassWorkAsExpected() {
		final HierarchyWithinRoot hierarchyWithinRoot = hierarchyWithinRootSelf(directRelation());
		assertNull(hierarchyWithinRoot.getReferenceName().orElse(null));
		assertArrayEquals(new FilterConstraint[0], hierarchyWithinRoot.getExcludedChildrenFilter());
		assertTrue(hierarchyWithinRoot.isDirectRelation());
	}

	@Test
	void shouldFailToCreateViaFactoryUsingDirectOnSelfReferencingConstraint() {
		assertThrows(IllegalArgumentException.class, () -> hierarchyWithinRoot("brand", directRelation()));
	}

	@Test
	void shouldCreateViaFactoryUsingAllPossibleSubConstraintClassWorkAsExpected() {
		final HierarchyWithinRoot hierarchyWithinRoot = hierarchyWithinRootSelf(
			excluding(entityPrimaryKeyInSet(3, 7))
		);
		assertNull(hierarchyWithinRoot.getReferenceName().orElse(null));
		assertArrayEquals(new FilterConstraint[] {new EntityPrimaryKeyInSet(3, 7)}, hierarchyWithinRoot.getExcludedChildrenFilter());
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(hierarchyWithinRootSelf().isApplicable());
		assertTrue(hierarchyWithinRoot("brand").isApplicable());
		assertTrue(hierarchyWithinRoot("brand").isApplicable());
		assertTrue(hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(5, 7))).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final HierarchyWithinRoot hierarchyWithinRoot = hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(5, 7)));
		assertEquals("hierarchyWithinRoot('brand',excluding(entityPrimaryKeyInSet(5,7)))", hierarchyWithinRoot.toString());
	}

	@Test
	void shouldToStringReturnExpectedFormatWhenUsingMultipleSubConstraints() {
		final HierarchyWithinRoot hierarchyWithinRoot = hierarchyWithinRoot(
			"brand",
			excluding(entityPrimaryKeyInSet(3, 7))
		);;
		assertEquals("hierarchyWithinRoot('brand',excluding(entityPrimaryKeyInSet(3,7)))", hierarchyWithinRoot.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(1, 5))), hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(1, 5))));
		assertEquals(hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(1, 5))), hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(1, 5))));
		assertNotEquals(hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(1, 5))), hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(1, 6))));
		assertNotEquals(hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(1, 5))), hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(1))));
		assertNotEquals(hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(1, 5))), hierarchyWithinRoot("category", excluding(entityPrimaryKeyInSet(1, 6))));
		assertNotEquals(hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(1, 5))), hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(1))));
		assertEquals(hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(1, 5))).hashCode(), hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(1, 5))).hashCode());
		assertNotEquals(hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(1, 5))).hashCode(), hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(1, 6))).hashCode());
		assertNotEquals(hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(1, 5))).hashCode(), hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(1))).hashCode());
	}

}
