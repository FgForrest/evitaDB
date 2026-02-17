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
import io.evitadb.api.query.filter.FilterBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HierarchyNode} verifying construction, applicability, visitor acceptance,
 * string representation, equality contract, and cloning behavior.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("HierarchyNode constraint")
class HierarchyNodeTest {

	private static final FilterBy NODE_REF = filterBy(entityPrimaryKeyInSet(1));
	private static final FilterBy NODE2_REF = filterBy(entityPrimaryKeyInSet(2));

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should create via factory method with FilterBy")
		void shouldCreateWithNodeViaFactoryClassWorkAsExpected() {
			final HierarchyNode hierarchyNode = node(NODE_REF);

			assertEquals(NODE_REF, hierarchyNode.getFilterBy());
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should not be applicable when FilterBy is null")
		void shouldNotBeApplicableWithNullFilterBy() {
			assertFalse(new HierarchyNode(null).isApplicable());
		}

		@Test
		@DisplayName("should be applicable when FilterBy is provided")
		void shouldBeApplicableWithFilterBy() {
			assertTrue(node(NODE_REF).isApplicable());
		}
	}

	@Nested
	@DisplayName("Visitor support")
	class VisitorSupportTest {

		@Test
		@DisplayName("should accept visitor and visit container and children")
		void shouldAcceptVisitor() {
			final HierarchyNode constraint = node(NODE_REF);
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();

			constraint.accept(c -> visited.set(c));

			// Last visited is the deepest child; the container is visited first
			assertNotNull(visited.get());
		}

		@Test
		@DisplayName("should return RequireConstraint type")
		void shouldReturnRequireConstraintType() {
			assertEquals(RequireConstraint.class, node(NODE_REF).getType());
		}
	}

	@Nested
	@DisplayName("String representation")
	class StringRepresentationTest {

		@Test
		@DisplayName("should produce correct toString output")
		void shouldToStringReturnExpectedFormat() {
			final HierarchyNode hierarchyNode = node(NODE_REF);

			assertEquals("node(filterBy(entityPrimaryKeyInSet(1)))", hierarchyNode.toString());
		}
	}

	@Nested
	@DisplayName("Equality")
	class EqualityTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(node(NODE_REF), node(NODE_REF));
			assertEquals(node(NODE_REF), node(NODE_REF));
			assertNotEquals(node(NODE_REF), node(NODE2_REF));
			assertEquals(node(NODE_REF).hashCode(), node(NODE_REF).hashCode());
			assertNotEquals(node(NODE_REF).hashCode(), node(NODE2_REF).hashCode());
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should return new equivalent instance from cloneWithArguments")
		void shouldReturnNewInstanceFromCloneWithArguments() {
			final HierarchyNode constraint = node(NODE_REF);

			final RequireConstraint cloned = constraint.cloneWithArguments(new Serializable[0]);
			assertNotSame(constraint, cloned);
			assertEquals(constraint, cloned);
		}

		@Test
		@DisplayName("should produce new instance from getCopyWithNewChildren")
		void shouldProduceCopyWithNewChildren() {
			final HierarchyNode constraint = node(NODE_REF);

			final RequireConstraint copy = constraint.getCopyWithNewChildren(
				new RequireConstraint[0],
				new Constraint<?>[]{NODE2_REF}
			);

			assertNotSame(constraint, copy);
			assertInstanceOf(HierarchyNode.class, copy);
			assertEquals(NODE2_REF, ((HierarchyNode) copy).getFilterBy());
		}
	}
}
