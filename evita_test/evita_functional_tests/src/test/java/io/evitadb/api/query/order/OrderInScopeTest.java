/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.attributeNatural;
import static io.evitadb.api.query.QueryConstraints.inScope;
import static io.evitadb.api.query.order.OrderDirection.ASC;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link OrderInScope} verifying construction, applicability, accessor methods,
 * copy/clone operations, visitor acceptance, and equality contract.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@DisplayName("OrderInScope constraint")
class OrderInScopeTest {

	@Nested
	@DisplayName("Construction and factory method")
	class ConstructionTest {

		@Test
		@DisplayName("should create with LIVE scope via factory method")
		void shouldCreateWithLiveScope() {
			final OrderInScope orderInScope = inScope(Scope.LIVE, attributeNatural("code", ASC));

			assertNotNull(orderInScope);
			assertEquals(Scope.LIVE, orderInScope.getScope());
		}

		@Test
		@DisplayName("should create with ARCHIVED scope via factory method")
		void shouldCreateWithArchivedScope() {
			final OrderInScope orderInScope = inScope(Scope.ARCHIVED, attributeNatural("code", ASC));

			assertNotNull(orderInScope);
			assertEquals(Scope.ARCHIVED, orderInScope.getScope());
		}

		@Test
		@DisplayName("should throw when null scope provided to constructor")
		void shouldThrowWhenNullScopeProvided() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new OrderInScope(null)
			);
		}

		@Test
		@DisplayName("should throw when empty ordering provided to constructor")
		void shouldThrowWhenEmptyOrderingProvided() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new OrderInScope(Scope.LIVE)
			);
		}

		@Test
		@DisplayName("should return null when factory receives null scope")
		void shouldReturnNullWhenFactoryReceivesNullScope() {
			final OrderInScope result = inScope((Scope) null, attributeNatural("code", ASC));

			assertNull(result);
		}

		@Test
		@DisplayName("should return null when factory receives null ordering")
		void shouldReturnNullWhenFactoryReceivesNullOrdering() {
			final OrderInScope result = inScope(Scope.LIVE, (OrderConstraint[]) null);

			assertNull(result);
		}
	}

	@Nested
	@DisplayName("Applicability and necessity")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable when scope and children present")
		void shouldBeApplicableWhenScopeAndChildrenPresent() {
			final OrderInScope orderInScope = inScope(Scope.LIVE, attributeNatural("code", ASC));

			assertTrue(orderInScope.isApplicable());
		}

		@Test
		@DisplayName("should be applicable with multiple children")
		void shouldBeApplicableWithMultipleChildren() {
			final OrderInScope orderInScope = inScope(
				Scope.ARCHIVED,
				attributeNatural("name", DESC),
				attributeNatural("code", ASC)
			);

			assertTrue(orderInScope.isApplicable());
		}

		@Test
		@DisplayName("should be necessary when applicable")
		void shouldBeNecessaryWhenApplicable() {
			final OrderInScope orderInScope = inScope(Scope.LIVE, attributeNatural("code", ASC));

			assertTrue(orderInScope.isNecessary());
		}
	}

	@Nested
	@DisplayName("Accessor methods")
	class AccessorTest {

		@Test
		@DisplayName("should return scope from getScope")
		void shouldReturnScopeFromGetScope() {
			final OrderInScope orderInScope = inScope(Scope.LIVE, attributeNatural("code", ASC));

			assertEquals(Scope.LIVE, orderInScope.getScope());
		}

		@Test
		@DisplayName("should return ordering constraints from getOrdering")
		void shouldReturnOrderingFromGetOrdering() {
			final OrderInScope orderInScope = inScope(Scope.LIVE, attributeNatural("code", ASC));

			final OrderConstraint[] ordering = orderInScope.getOrdering();

			assertArrayEquals(
				new OrderConstraint[]{attributeNatural("code", ASC)},
				ordering
			);
		}

		@Test
		@DisplayName("should return multiple ordering constraints")
		void shouldReturnMultipleOrderingConstraints() {
			final OrderInScope orderInScope = inScope(
				Scope.ARCHIVED,
				attributeNatural("name", DESC),
				attributeNatural("code", ASC)
			);

			final OrderConstraint[] ordering = orderInScope.getOrdering();

			assertEquals(2, ordering.length);
		}
	}

	@Nested
	@DisplayName("Type and visitor")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return OrderConstraint class as type")
		void shouldReturnOrderConstraintClassAsType() {
			final OrderInScope orderInScope = inScope(Scope.LIVE, attributeNatural("code", ASC));

			assertEquals(OrderConstraint.class, orderInScope.getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final OrderInScope orderInScope = inScope(Scope.LIVE, attributeNatural("code", ASC));
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();
			orderInScope.accept(new ConstraintVisitor() {
				@Override
				public void visit(@Nonnull Constraint<?> constraint) {
					visited.set(constraint);
				}
			});

			assertSame(orderInScope, visited.get());
		}
	}

	@Nested
	@DisplayName("Copy and clone operations")
	class CopyAndCloneTest {

		@Test
		@DisplayName("should create copy with new children preserving scope")
		void shouldCreateCopyWithNewChildrenPreservingScope() {
			final OrderInScope original = inScope(Scope.LIVE, attributeNatural("code", ASC));
			final OrderConstraint copy = original.getCopyWithNewChildren(
				new OrderConstraint[]{attributeNatural("name", DESC)},
				new Constraint<?>[0]
			);

			assertInstanceOf(OrderInScope.class, copy);
			final OrderInScope copiedInScope = (OrderInScope) copy;
			assertEquals(Scope.LIVE, copiedInScope.getScope());
			assertEquals(1, copiedInScope.getChildrenCount());
			assertEquals("name", ((AttributeNatural) copiedInScope.getChildren()[0]).getAttributeName());
		}

		@Test
		@DisplayName("should reject non-empty additional children in getCopyWithNewChildren")
		void shouldRejectNonEmptyAdditionalChildren() {
			final OrderInScope original = inScope(Scope.LIVE, attributeNatural("code", ASC));

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> original.getCopyWithNewChildren(
					new OrderConstraint[]{attributeNatural("name")},
					new Constraint<?>[]{attributeNatural("extra")}
				)
			);
		}

		@Test
		@DisplayName("should create new instance with updated scope when cloning with valid arguments")
		void shouldCreateNewInstanceWithUpdatedScopeWhenCloningWithValidArguments() {
			final OrderInScope original = inScope(Scope.LIVE, attributeNatural("code", ASC));

			final OrderConstraint cloned = original.cloneWithArguments(
				new Serializable[]{Scope.ARCHIVED}
			);

			assertNotSame(original, cloned);
			assertInstanceOf(OrderInScope.class, cloned);
			final OrderInScope clonedInScope = (OrderInScope) cloned;
			assertEquals(Scope.ARCHIVED, clonedInScope.getScope());
			assertEquals(Scope.LIVE, original.getScope());
			assertArrayEquals(original.getChildren(), clonedInScope.getChildren());
		}

		@Test
		@DisplayName("should throw when cloning with wrong argument count")
		void shouldThrowWhenCloningWithWrongArgumentCount() {
			final OrderInScope original = inScope(Scope.LIVE, attributeNatural("code", ASC));

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> original.cloneWithArguments(new Serializable[]{Scope.LIVE, Scope.ARCHIVED})
			);
		}

		@Test
		@DisplayName("should throw when cloning with wrong argument type")
		void shouldThrowWhenCloningWithWrongArgumentType() {
			final OrderInScope original = inScope(Scope.LIVE, attributeNatural("code", ASC));

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> original.cloneWithArguments(new Serializable[]{"notAScope"})
			);
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce expected toString format for LIVE scope")
		void shouldProduceExpectedToStringForLiveScope() {
			final OrderInScope orderInScope = inScope(Scope.LIVE, attributeNatural("code", ASC));

			assertEquals("inScope(LIVE,attributeNatural('code',ASC))", orderInScope.toString());
		}

		@Test
		@DisplayName("should produce expected toString format for ARCHIVED scope with multiple children")
		void shouldProduceExpectedToStringForArchivedScopeWithMultipleChildren() {
			final OrderInScope orderInScope = inScope(
				Scope.ARCHIVED,
				attributeNatural("name", DESC),
				attributeNatural("code", ASC)
			);

			assertEquals(
				"inScope(ARCHIVED,attributeNatural('name',DESC),attributeNatural('code',ASC))",
				orderInScope.toString()
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
				inScope(Scope.LIVE, attributeNatural("code", ASC)),
				inScope(Scope.LIVE, attributeNatural("code", ASC))
			);
			assertEquals(
				inScope(Scope.LIVE, attributeNatural("code", ASC)),
				inScope(Scope.LIVE, attributeNatural("code", ASC))
			);
			assertNotEquals(
				inScope(Scope.LIVE, attributeNatural("code", ASC)),
				inScope(Scope.ARCHIVED, attributeNatural("code", ASC))
			);
			assertNotEquals(
				inScope(Scope.LIVE, attributeNatural("code", ASC)),
				inScope(Scope.LIVE, attributeNatural("name", DESC))
			);
			assertEquals(
				inScope(Scope.LIVE, attributeNatural("code", ASC)).hashCode(),
				inScope(Scope.LIVE, attributeNatural("code", ASC)).hashCode()
			);
			assertNotEquals(
				inScope(Scope.LIVE, attributeNatural("code", ASC)).hashCode(),
				inScope(Scope.ARCHIVED, attributeNatural("code", ASC)).hashCode()
			);
			assertNotEquals(
				inScope(Scope.LIVE, attributeNatural("code", ASC)).hashCode(),
				inScope(Scope.LIVE, attributeNatural("name", DESC)).hashCode()
			);
		}
	}
}
