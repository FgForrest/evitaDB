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
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PickFirstByEntityProperty} verifying construction, applicability, necessity, accessor methods,
 * copy/clone operations, visitor acceptance, string representation, and equality contract.
 *
 * @author Jan Novotn&#253; (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("PickFirstByEntityProperty constraint")
class PickFirstByEntityPropertyTest implements EvitaTestSupport {

	@Nested
	@DisplayName("Construction and factory method")
	class ConstructionTest {

		@Test
		@DisplayName("should create via factory method with children")
		void shouldCreateViaFactoryMethodWithChildren() {
			final PickFirstByEntityProperty constraint = pickFirstByEntityProperty(
				attributeNatural("code")
			);

			assertNotNull(constraint);
			assertArrayEquals(
				new OrderConstraint[]{attributeNatural("code")},
				constraint.getChildren()
			);
		}

		@Test
		@DisplayName("should return null when factory receives null children")
		void shouldReturnNullWhenFactoryReceivesNullChildren() {
			final PickFirstByEntityProperty result = pickFirstByEntityProperty(
				(OrderConstraint[]) null
			);

			assertNull(result);
		}

		@Test
		@DisplayName("should return null when factory receives no children")
		void shouldReturnNullWhenFactoryReceivesNoChildren() {
			final PickFirstByEntityProperty result = pickFirstByEntityProperty();

			assertNull(result);
		}

		@Test
		@DisplayName("should create empty container via constructor")
		void shouldCreateEmptyContainerViaConstructor() {
			final PickFirstByEntityProperty constraint = new PickFirstByEntityProperty();

			assertEquals(0, constraint.getChildrenCount());
		}
	}

	@Nested
	@DisplayName("Applicability and necessity")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable when it has children")
		void shouldBeApplicableWhenItHasChildren() {
			final PickFirstByEntityProperty constraint = pickFirstByEntityProperty(
				attributeNatural("code")
			);

			assertTrue(constraint.isApplicable());
		}

		@Test
		@DisplayName("should not be applicable when it has no children")
		void shouldNotBeApplicableWhenItHasNoChildren() {
			final PickFirstByEntityProperty constraint = new PickFirstByEntityProperty();

			assertFalse(constraint.isApplicable());
		}

		@Test
		@DisplayName("should be necessary when it has at least one child")
		void shouldBeNecessaryWhenItHasAtLeastOneChild() {
			final PickFirstByEntityProperty constraint = pickFirstByEntityProperty(
				attributeNatural("code")
			);

			assertTrue(constraint.isNecessary());
		}

		@Test
		@DisplayName("should not be necessary when it has no children")
		void shouldNotBeNecessaryWhenItHasNoChildren() {
			final PickFirstByEntityProperty constraint = new PickFirstByEntityProperty();

			assertFalse(constraint.isNecessary());
		}
	}

	@Nested
	@DisplayName("Accessor methods")
	class AccessorTest {

		@Test
		@DisplayName("should return order by children via getOrderBy")
		void shouldReturnOrderByChildrenViaGetOrderBy() {
			final PickFirstByEntityProperty constraint = pickFirstByEntityProperty(
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
			final PickFirstByEntityProperty constraint = pickFirstByEntityProperty(
				attributeNatural("code")
			);

			assertInstanceOf(ReferenceOrderingSpecification.class, constraint);
		}
	}

	@Nested
	@DisplayName("Type and visitor")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return OrderConstraint class as type")
		void shouldReturnOrderConstraintClassAsType() {
			final PickFirstByEntityProperty constraint = pickFirstByEntityProperty(
				attributeNatural("code")
			);

			assertEquals(OrderConstraint.class, constraint.getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final PickFirstByEntityProperty constraint = pickFirstByEntityProperty(
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
		@DisplayName("should create copy with new children")
		void shouldCreateCopyWithNewChildren() {
			final PickFirstByEntityProperty original = pickFirstByEntityProperty(
				attributeNatural("code")
			);

			final OrderConstraint copy = original.getCopyWithNewChildren(
				new OrderConstraint[]{attributeNatural("name", DESC)},
				new Constraint<?>[0]
			);

			assertInstanceOf(PickFirstByEntityProperty.class, copy);
			assertEquals(1, ((PickFirstByEntityProperty) copy).getChildrenCount());
		}

		@Test
		@DisplayName("should reject non-empty additional children")
		void shouldRejectNonEmptyAdditionalChildren() {
			final PickFirstByEntityProperty original = pickFirstByEntityProperty(
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
		@DisplayName("should throw UnsupportedOperationException when cloning with arguments")
		void shouldThrowWhenCloningWithArguments() {
			final PickFirstByEntityProperty constraint = pickFirstByEntityProperty(
				attributeNatural("code")
			);

			// argument-less containers throw UnsupportedOperationException by convention
			final UnsupportedOperationException exception = assertThrows(
				UnsupportedOperationException.class,
				() -> constraint.cloneWithArguments(new Serializable[]{"arg"})
			);

			assertTrue(exception.getMessage().contains("PickFirstByEntityProperty"));
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce expected toString format")
		void shouldProduceExpectedToStringFormat() {
			final PickFirstByEntityProperty constraint = pickFirstByEntityProperty(
				attributeNatural("code")
			);

			assertEquals(
				"pickFirstByEntityProperty(attributeNatural('code',ASC))",
				constraint.toString()
			);
		}

		@Test
		@DisplayName("should produce expected toString format with multiple children")
		void shouldProduceExpectedToStringFormatWithMultipleChildren() {
			final PickFirstByEntityProperty constraint = pickFirstByEntityProperty(
				attributeNatural("code"),
				attributeNatural("name", DESC)
			);

			assertEquals(
				"pickFirstByEntityProperty(attributeNatural('code',ASC),attributeNatural('name',DESC))",
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
				pickFirstByEntityProperty(attributeNatural("code")),
				pickFirstByEntityProperty(attributeNatural("code"))
			);
			assertEquals(
				pickFirstByEntityProperty(attributeNatural("code")),
				pickFirstByEntityProperty(attributeNatural("code"))
			);
			assertNotEquals(
				pickFirstByEntityProperty(attributeNatural("code")),
				pickFirstByEntityProperty(attributeNatural("order"))
			);
			assertNotEquals(
				pickFirstByEntityProperty(attributeNatural("code")),
				new PickFirstByEntityProperty()
			);
			assertEquals(
				pickFirstByEntityProperty(attributeNatural("code")).hashCode(),
				pickFirstByEntityProperty(attributeNatural("code")).hashCode()
			);
			assertNotEquals(
				pickFirstByEntityProperty(attributeNatural("code")).hashCode(),
				pickFirstByEntityProperty(attributeNatural("order")).hashCode()
			);
		}
	}
}
