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
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EntityGroupProperty} verifying construction, applicability, necessity, accessor methods,
 * copy/clone operations, visitor acceptance, string representation, and equality contract.
 *
 * @author Luk&#225;&#353; Hornych, FG Forrest a.s. (c) 2023
 */
@DisplayName("EntityGroupProperty constraint")
class EntityGroupPropertyTest implements EvitaTestSupport {

	@Nested
	@DisplayName("Construction and factory method")
	class ConstructionTest {

		@Test
		@DisplayName("should create via factory method with children")
		void shouldCreateViaFactoryMethodWithChildren() {
			final EntityGroupProperty constraint = entityGroupProperty(priceNatural());

			assertNotNull(constraint);
			assertArrayEquals(
				new OrderConstraint[]{priceNatural()},
				constraint.getChildren()
			);
		}

		@Test
		@DisplayName("should create via factory method with multiple children")
		void shouldCreateViaFactoryMethodWithMultipleChildren() {
			final EntityGroupProperty constraint = entityGroupProperty(
				attributeNatural("code"),
				attributeNatural("name", DESC)
			);

			assertNotNull(constraint);
			assertEquals(2, constraint.getChildrenCount());
		}

		@Test
		@DisplayName("should return null when factory receives null")
		void shouldReturnNullWhenFactoryReceivesNull() {
			final EntityGroupProperty result = entityGroupProperty((OrderConstraint[]) null);

			assertNull(result);
		}

		@Test
		@DisplayName("should create empty container via constructor")
		void shouldCreateEmptyContainerViaConstructor() {
			final EntityGroupProperty constraint = new EntityGroupProperty();

			assertEquals(0, constraint.getChildrenCount());
		}
	}

	@Nested
	@DisplayName("Applicability and necessity")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable when it has children")
		void shouldBeApplicableWhenItHasChildren() {
			final EntityGroupProperty constraint = entityGroupProperty(priceNatural());

			assertTrue(constraint.isApplicable());
		}

		@Test
		@DisplayName("should not be applicable when it has no children")
		void shouldNotBeApplicableWhenItHasNoChildren() {
			final EntityGroupProperty constraint = entityGroupProperty();

			assertFalse(constraint.isApplicable());
		}

		@Test
		@DisplayName("should be necessary when it has at least one child")
		void shouldBeNecessaryWhenItHasAtLeastOneChild() {
			final EntityGroupProperty constraint = entityGroupProperty(priceNatural());

			assertTrue(constraint.isNecessary());
		}

		@Test
		@DisplayName("should not be necessary when it has no children")
		void shouldNotBeNecessaryWhenItHasNoChildren() {
			final EntityGroupProperty constraint = entityGroupProperty();

			assertFalse(constraint.isNecessary());
		}
	}

	@Nested
	@DisplayName("Type and visitor")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return OrderConstraint class as type")
		void shouldReturnOrderConstraintClassAsType() {
			final EntityGroupProperty constraint = entityGroupProperty(priceNatural());

			assertEquals(OrderConstraint.class, constraint.getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final EntityGroupProperty constraint = entityGroupProperty(priceNatural());
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
		@DisplayName("should create copy with new children")
		void shouldCreateCopyWithNewChildren() {
			final EntityGroupProperty original = entityGroupProperty(priceNatural());

			final OrderConstraint copy = original.getCopyWithNewChildren(
				new OrderConstraint[]{attributeNatural("name", DESC)},
				new Constraint<?>[0]
			);

			assertInstanceOf(EntityGroupProperty.class, copy);
			assertEquals(1, ((EntityGroupProperty) copy).getChildrenCount());
			assertEquals(
				"name",
				((AttributeNatural) ((EntityGroupProperty) copy).getChildren()[0]).getAttributeName()
			);
		}

		@Test
		@DisplayName("should create empty copy when no children provided")
		void shouldCreateEmptyCopyWhenNoChildrenProvided() {
			final EntityGroupProperty original = entityGroupProperty(priceNatural());

			final OrderConstraint copy = original.getCopyWithNewChildren(
				new OrderConstraint[0],
				new Constraint<?>[0]
			);

			assertInstanceOf(EntityGroupProperty.class, copy);
			assertFalse(((EntityGroupProperty) copy).isApplicable());
		}

		@Test
		@DisplayName("should clone with new arguments preserving children")
		void shouldCloneWithNewArgumentsPreservingChildren() {
			final EntityGroupProperty original = entityGroupProperty(priceNatural());

			final OrderConstraint cloned = original.cloneWithArguments(
				new Serializable[]{}
			);

			assertInstanceOf(EntityGroupProperty.class, cloned);
			assertNotSame(original, cloned);
			assertEquals(original, cloned);
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce expected toString format")
		void shouldProduceExpectedToStringFormat() {
			final EntityGroupProperty constraint = entityGroupProperty(priceNatural());

			assertEquals("entityGroupProperty(priceNatural(ASC))", constraint.toString());
		}

		@Test
		@DisplayName("should produce expected toString format with multiple children")
		void shouldProduceExpectedToStringFormatWithMultipleChildren() {
			final EntityGroupProperty constraint = entityGroupProperty(
				attributeNatural("code", DESC),
				priceNatural()
			);

			assertEquals(
				"entityGroupProperty(attributeNatural('code',DESC),priceNatural(ASC))",
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
				entityGroupProperty(priceNatural()),
				entityGroupProperty(priceNatural())
			);
			assertEquals(
				entityGroupProperty(priceNatural()),
				entityGroupProperty(priceNatural())
			);
			assertNotEquals(
				entityGroupProperty(priceNatural()),
				entityGroupProperty(attributeNatural("code"))
			);
			assertNotEquals(
				entityGroupProperty(priceNatural()),
				entityGroupProperty((OrderConstraint) null)
			);
			assertEquals(
				entityGroupProperty(priceNatural()).hashCode(),
				entityGroupProperty(priceNatural()).hashCode()
			);
			assertNotEquals(
				entityGroupProperty(priceNatural()).hashCode(),
				entityGroupProperty(attributeNatural("code")).hashCode()
			);
		}
	}
}
