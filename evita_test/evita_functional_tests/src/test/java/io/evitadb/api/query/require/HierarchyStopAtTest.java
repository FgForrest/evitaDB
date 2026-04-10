/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.RequireConstraint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HierarchyStopAt} verifying construction, applicability, visitor acceptance,
 * string representation, equality contract, and cloning behavior.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("HierarchyStopAt constraint")
class HierarchyStopAtTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should create with HierarchyLevel child")
		void shouldCreateWithLevelViaFactoryClassWorkAsExpected() {
			final HierarchyStopAt hierarchyStopAt = stopAt(level(1));

			assertEquals(level(1), hierarchyStopAt.getLevel());
			assertNull(hierarchyStopAt.getDistance());
			assertNull(hierarchyStopAt.getNode());
		}

		@Test
		@DisplayName("should create with HierarchyDistance child")
		void shouldCreateWithDistanceViaFactoryClassWorkAsExpected() {
			final HierarchyStopAt hierarchyStopAt = stopAt(distance(1));

			assertEquals(distance(1), hierarchyStopAt.getDistance());
			assertNull(hierarchyStopAt.getLevel());
			assertNull(hierarchyStopAt.getNode());
		}

		@Test
		@DisplayName("should create with HierarchyNode child")
		void shouldCreateWithNodeViaFactoryClassWorkAsExpected() {
			final HierarchyStopAt hierarchyStopAt = stopAt(
				node(filterBy(entityPrimaryKeyInSet(1)))
			);

			assertEquals(node(filterBy(entityPrimaryKeyInSet(1))), hierarchyStopAt.getNode());
			assertNull(hierarchyStopAt.getLevel());
			assertNull(hierarchyStopAt.getDistance());
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should not be applicable when child is null")
		void shouldNotBeApplicableWithNullChild() {
			assertFalse(new HierarchyStopAt(null).isApplicable());
		}

		@Test
		@DisplayName("should be applicable with level child")
		void shouldBeApplicableWithLevel() {
			assertTrue(stopAt(level(1)).isApplicable());
		}

		@Test
		@DisplayName("should be applicable with distance child")
		void shouldBeApplicableWithDistance() {
			assertTrue(stopAt(distance(1)).isApplicable());
		}

		@Test
		@DisplayName("should be applicable with node child")
		void shouldBeApplicableWithNode() {
			assertTrue(stopAt(node(filterBy(entityPrimaryKeyInSet(1)))).isApplicable());
		}
	}

	@Nested
	@DisplayName("Visitor support")
	class VisitorSupportTest {

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final HierarchyStopAt constraint = stopAt(level(1));
			final AtomicReference<Constraint<?>> firstVisited = new AtomicReference<>();

			constraint.accept(c -> {
				if (firstVisited.get() == null) {
					firstVisited.set(c);
				}
			});

			assertSame(constraint, firstVisited.get());
		}

		@Test
		@DisplayName("should return RequireConstraint type")
		void shouldReturnRequireConstraintType() {
			assertEquals(RequireConstraint.class, stopAt(level(1)).getType());
		}
	}

	@Nested
	@DisplayName("String representation")
	class StringRepresentationTest {

		@Test
		@DisplayName("should produce correct toString with level")
		void shouldToStringReturnExpectedFormatWithLevel() {
			final HierarchyStopAt hierarchyStopAt = stopAt(level(1));

			assertEquals("stopAt(level(1))", hierarchyStopAt.toString());
		}

		@Test
		@DisplayName("should produce correct toString with distance")
		void shouldToStringReturnExpectedFormatWithDistance() {
			final HierarchyStopAt hierarchyStopAt = stopAt(distance(5));

			assertEquals("stopAt(distance(5))", hierarchyStopAt.toString());
		}
	}

	@Nested
	@DisplayName("Equality")
	class EqualityTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(stopAt(level(1)), stopAt(level(1)));
			assertEquals(stopAt(level(1)), stopAt(level(1)));
			assertNotEquals(stopAt(level(1)), stopAt(level(2)));
			assertNotEquals(stopAt(level(1)), stopAt(distance(1)));
			assertEquals(stopAt(level(1)).hashCode(), stopAt(level(1)).hashCode());
			assertNotEquals(stopAt(level(1)).hashCode(), stopAt(level(2)).hashCode());
			assertNotEquals(stopAt(level(1)).hashCode(), stopAt(distance(1)).hashCode());
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should return new equivalent instance from cloneWithArguments")
		void shouldReturnNewInstanceFromCloneWithArguments() {
			final HierarchyStopAt constraint = stopAt(level(1));

			final RequireConstraint cloned = constraint.cloneWithArguments(new Serializable[0]);
			assertNotSame(constraint, cloned);
			assertEquals(constraint, cloned);
		}

		@Test
		@DisplayName("should produce new instance from getCopyWithNewChildren")
		void shouldProduceCopyWithNewChildren() {
			final HierarchyStopAt constraint = stopAt(level(1));

			final RequireConstraint copy = constraint.getCopyWithNewChildren(
				new RequireConstraint[]{distance(3)},
				new Constraint<?>[0]
			);

			assertNotSame(constraint, copy);
			assertInstanceOf(HierarchyStopAt.class, copy);
			assertEquals(distance(3), ((HierarchyStopAt) copy).getDistance());
		}
	}
}
