/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.query.order;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.order.OrderDirection.ASC;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TraverseByEntityProperty} verifying construction, applicability, necessity, accessor methods,
 * default argument handling, copy/clone operations, visitor acceptance, string representation, and equality contract.
 *
 * @author Jan Novotn&#253; (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("TraverseByEntityProperty constraint")
class TraverseByEntityPropertyTest implements EvitaTestSupport {

	@Nested
	@DisplayName("Construction and factory method")
	class ConstructionTest {

		@Test
		@DisplayName("should create via factory method with default traversal mode")
		void shouldCreateViaFactoryMethodWithDefaultTraversalMode() {
			final TraverseByEntityProperty constraint = traverseByEntityProperty(
				attributeNatural("code")
			);

			assertNotNull(constraint);
			assertEquals(TraversalMode.DEPTH_FIRST, constraint.getTraversalMode());
			assertArrayEquals(
				new OrderConstraint[]{attributeNatural("code")},
				constraint.getChildren()
			);
		}

		@Test
		@DisplayName("should create via factory method with explicit BREADTH_FIRST mode")
		void shouldCreateViaFactoryMethodWithExplicitBreadthFirstMode() {
			final TraverseByEntityProperty constraint = traverseByEntityProperty(
				TraversalMode.BREADTH_FIRST, attributeNatural("code")
			);

			assertNotNull(constraint);
			assertEquals(TraversalMode.BREADTH_FIRST, constraint.getTraversalMode());
			assertArrayEquals(
				new OrderConstraint[]{attributeNatural("code")},
				constraint.getChildren()
			);
		}

		@Test
		@DisplayName("should create via factory method with explicit DEPTH_FIRST mode")
		void shouldCreateViaFactoryMethodWithExplicitDepthFirstMode() {
			final TraverseByEntityProperty constraint = traverseByEntityProperty(
				TraversalMode.DEPTH_FIRST, attributeNatural("code")
			);

			assertNotNull(constraint);
			assertEquals(TraversalMode.DEPTH_FIRST, constraint.getTraversalMode());
		}

		@Test
		@DisplayName("should normalize null traversal mode to DEPTH_FIRST")
		void shouldNormalizeNullTraversalModeToDepthFirst() {
			final TraverseByEntityProperty constraint = new TraverseByEntityProperty(
				null, attributeNatural("code")
			);

			assertEquals(TraversalMode.DEPTH_FIRST, constraint.getTraversalMode());
		}

		@Test
		@DisplayName("should create default instance with entityPrimaryKeyNatural when no children")
		void shouldCreateDefaultInstanceWhenNoChildren() {
			final TraverseByEntityProperty constraint = traverseByEntityProperty();

			assertEquals(TraversalMode.DEPTH_FIRST, constraint.getTraversalMode());
			assertEquals(
				traverseByEntityProperty(
					TraversalMode.DEPTH_FIRST,
					entityPrimaryKeyNatural(ASC)
				),
				constraint
			);
		}
	}

	@Nested
	@DisplayName("Applicability and necessity")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable when it has children")
		void shouldBeApplicableWhenItHasChildren() {
			final TraverseByEntityProperty constraint = traverseByEntityProperty(
				attributeNatural("code")
			);

			assertTrue(constraint.isApplicable());
		}

		@Test
		@DisplayName("should be applicable with explicit traversal mode")
		void shouldBeApplicableWithExplicitTraversalMode() {
			final TraverseByEntityProperty constraint = traverseByEntityProperty(
				TraversalMode.DEPTH_FIRST, attributeNatural("code")
			);

			assertTrue(constraint.isApplicable());
		}

		@Test
		@DisplayName("should not be applicable when it has no children")
		void shouldNotBeApplicableWhenItHasNoChildren() {
			final TraverseByEntityProperty constraint = new TraverseByEntityProperty(null);

			assertFalse(constraint.isApplicable());
		}

		@Test
		@DisplayName("should be necessary when it has arguments and children")
		void shouldBeNecessaryWhenItHasArgumentsAndChildren() {
			final TraverseByEntityProperty constraint = traverseByEntityProperty(
				attributeNatural("code")
			);

			assertTrue(constraint.isNecessary());
		}

		@Test
		@DisplayName("should not be necessary when it has no children")
		void shouldNotBeNecessaryWhenItHasNoChildren() {
			final TraverseByEntityProperty constraint = new TraverseByEntityProperty(null);

			assertFalse(constraint.isNecessary());
		}
	}

	@Nested
	@DisplayName("Accessor methods")
	class AccessorTest {

		@Test
		@DisplayName("should return traversal mode")
		void shouldReturnTraversalMode() {
			final TraverseByEntityProperty depthFirst = traverseByEntityProperty(
				attributeNatural("code")
			);
			final TraverseByEntityProperty breadthFirst = traverseByEntityProperty(
				TraversalMode.BREADTH_FIRST, attributeNatural("code")
			);

			assertEquals(TraversalMode.DEPTH_FIRST, depthFirst.getTraversalMode());
			assertEquals(TraversalMode.BREADTH_FIRST, breadthFirst.getTraversalMode());
		}

		@Test
		@DisplayName("should return order by children via getOrderBy")
		void shouldReturnOrderByChildrenViaGetOrderBy() {
			final TraverseByEntityProperty constraint = traverseByEntityProperty(
				attributeNatural("code"),
				attributeNatural("name", DESC)
			);

			final OrderConstraint[] orderBy = constraint.getOrderBy();

			assertEquals(2, orderBy.length);
			assertInstanceOf(AttributeNatural.class, orderBy[0]);
			assertInstanceOf(AttributeNatural.class, orderBy[1]);
		}

		@Test
		@DisplayName("should implement ReferenceOrderingSpecification")
		void shouldImplementReferenceOrderingSpecification() {
			final TraverseByEntityProperty constraint = traverseByEntityProperty(
				attributeNatural("code")
			);

			assertInstanceOf(ReferenceOrderingSpecification.class, constraint);
		}
	}

	@Nested
	@DisplayName("Default argument handling")
	class DefaultArgumentTest {

		@Test
		@DisplayName("should exclude DEPTH_FIRST from arguments excluding defaults")
		void shouldExcludeDepthFirstFromArgumentsExcludingDefaults() {
			final TraverseByEntityProperty constraint = traverseByEntityProperty(
				attributeNatural("code")
			);

			final Serializable[] argsExcludingDefaults =
				constraint.getArgumentsExcludingDefaults();

			assertEquals(0, argsExcludingDefaults.length);
		}

		@Test
		@DisplayName("should include BREADTH_FIRST in arguments excluding defaults")
		void shouldIncludeBreadthFirstInArgumentsExcludingDefaults() {
			final TraverseByEntityProperty constraint = traverseByEntityProperty(
				TraversalMode.BREADTH_FIRST, attributeNatural("code")
			);

			final Serializable[] argsExcludingDefaults =
				constraint.getArgumentsExcludingDefaults();

			assertEquals(1, argsExcludingDefaults.length);
			assertEquals(TraversalMode.BREADTH_FIRST, argsExcludingDefaults[0]);
		}

		@Test
		@DisplayName("should report DEPTH_FIRST as implicit argument")
		void shouldReportDepthFirstAsImplicitArgument() {
			final TraverseByEntityProperty constraint = traverseByEntityProperty(
				attributeNatural("code")
			);

			assertTrue(constraint.isArgumentImplicit(TraversalMode.DEPTH_FIRST));
			assertFalse(constraint.isArgumentImplicit(TraversalMode.BREADTH_FIRST));
		}
	}

	@Nested
	@DisplayName("Type and visitor")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return OrderConstraint class as type")
		void shouldReturnOrderConstraintClassAsType() {
			final TraverseByEntityProperty constraint = traverseByEntityProperty(
				attributeNatural("code")
			);

			assertEquals(OrderConstraint.class, constraint.getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final TraverseByEntityProperty constraint = traverseByEntityProperty(
				attributeNatural("code")
			);
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();
			constraint.accept(new ConstraintVisitor() {
				@Override
				public void visit(@Nonnull Constraint<?> c) {
					visited.set(c);
				}
			});

			assertSame(constraint, visited.get());
		}
	}

	@Nested
	@DisplayName("Copy and clone operations")
	class CopyAndCloneTest {

		@Test
		@DisplayName("should create copy with new children preserving traversal mode")
		void shouldCreateCopyWithNewChildrenPreservingTraversalMode() {
			final TraverseByEntityProperty original = traverseByEntityProperty(
				TraversalMode.BREADTH_FIRST, attributeNatural("code")
			);

			final OrderConstraint copy = original.getCopyWithNewChildren(
				new OrderConstraint[]{attributeNatural("name", DESC)},
				new Constraint<?>[0]
			);

			assertInstanceOf(TraverseByEntityProperty.class, copy);
			final TraverseByEntityProperty traverseCopy = (TraverseByEntityProperty) copy;
			assertEquals(TraversalMode.BREADTH_FIRST, traverseCopy.getTraversalMode());
			assertEquals(1, traverseCopy.getChildrenCount());
		}

		@Test
		@DisplayName("should reject non-empty additional children")
		void shouldRejectNonEmptyAdditionalChildren() {
			final TraverseByEntityProperty original = traverseByEntityProperty(
				attributeNatural("code")
			);

			assertThrows(
				Exception.class,
				() -> original.getCopyWithNewChildren(
					new OrderConstraint[]{attributeNatural("name")},
					new Constraint<?>[]{attributeNatural("extra")}
				)
			);
		}

		@Test
		@DisplayName("should clone with new arguments preserving children")
		void shouldCloneWithNewArgumentsPreservingChildren() {
			final TraverseByEntityProperty original = traverseByEntityProperty(
				attributeNatural("code")
			);

			final OrderConstraint cloned = original.cloneWithArguments(
				new Serializable[]{TraversalMode.BREADTH_FIRST}
			);

			assertInstanceOf(TraverseByEntityProperty.class, cloned);
			final TraverseByEntityProperty traverseCloned = (TraverseByEntityProperty) cloned;
			assertEquals(TraversalMode.BREADTH_FIRST, traverseCloned.getTraversalMode());
			assertArrayEquals(original.getChildren(), traverseCloned.getChildren());
		}

		@Test
		@DisplayName("should produce equal but not same instance via cloneWithArguments")
		void shouldProduceEqualButNotSameInstanceViaCloneWithArguments() {
			final TraverseByEntityProperty original = traverseByEntityProperty(
				attributeNatural("code")
			);

			final OrderConstraint cloned = original.cloneWithArguments(
				new Serializable[]{TraversalMode.DEPTH_FIRST}
			);

			assertNotSame(original, cloned);
			assertEquals(original, cloned);
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should omit implicit DEPTH_FIRST in toString")
		void shouldOmitImplicitDepthFirstInToString() {
			final TraverseByEntityProperty constraint = traverseByEntityProperty(
				attributeNatural("code")
			);

			assertEquals(
				"traverseByEntityProperty(attributeNatural('code',ASC))",
				constraint.toString()
			);
		}

		@Test
		@DisplayName("should include explicit BREADTH_FIRST in toString")
		void shouldIncludeExplicitBreadthFirstInToString() {
			final TraverseByEntityProperty constraint = traverseByEntityProperty(
				TraversalMode.BREADTH_FIRST, attributeNatural("code")
			);

			assertEquals(
				"traverseByEntityProperty(BREADTH_FIRST,attributeNatural('code',ASC))",
				constraint.toString()
			);
		}

		@Test
		@DisplayName("should produce expected toString with multiple children")
		void shouldProduceExpectedToStringWithMultipleChildren() {
			final TraverseByEntityProperty constraint = traverseByEntityProperty(
				TraversalMode.BREADTH_FIRST,
				attributeNatural("order"),
				attributeNatural("name", DESC)
			);

			assertEquals(
				"traverseByEntityProperty(BREADTH_FIRST,attributeNatural('order',ASC),attributeNatural('name',DESC))",
				constraint.toString()
			);
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(
				traverseByEntityProperty(attributeNatural("code")),
				traverseByEntityProperty(attributeNatural("code"))
			);
			assertEquals(
				traverseByEntityProperty(attributeNatural("code")),
				traverseByEntityProperty(attributeNatural("code"))
			);
			// explicit DEPTH_FIRST equals implicit DEPTH_FIRST
			assertEquals(
				traverseByEntityProperty(TraversalMode.DEPTH_FIRST, attributeNatural("code")),
				traverseByEntityProperty(attributeNatural("code"))
			);
			assertNotEquals(
				traverseByEntityProperty(TraversalMode.BREADTH_FIRST, attributeNatural("code")),
				traverseByEntityProperty(attributeNatural("code"))
			);
			assertNotEquals(
				traverseByEntityProperty(TraversalMode.DEPTH_FIRST, attributeNatural("code")),
				traverseByEntityProperty(TraversalMode.BREADTH_FIRST, attributeNatural("code"))
			);
			assertNotEquals(
				traverseByEntityProperty(attributeNatural("code")),
				traverseByEntityProperty(attributeNatural("order"))
			);
			assertNotEquals(
				traverseByEntityProperty(attributeNatural("code")),
				new TraverseByEntityProperty(null)
			);
			assertEquals(
				traverseByEntityProperty(attributeNatural("code")).hashCode(),
				traverseByEntityProperty(attributeNatural("code")).hashCode()
			);
			assertNotEquals(
				traverseByEntityProperty(attributeNatural("code")).hashCode(),
				traverseByEntityProperty(attributeNatural("order")).hashCode()
			);
		}
	}
}
