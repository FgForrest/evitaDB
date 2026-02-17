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
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.order.OrderDirection.ASC;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ReferenceProperty} verifying construction, applicability, necessity, accessor methods,
 * copy/clone operations, visitor acceptance, string representation, and equality contract.
 *
 * @author Luk&#225;&#353; Hornych, FG Forrest a.s. (c) 2023
 */
@DisplayName("ReferenceProperty constraint")
class ReferencePropertyTest implements EvitaTestSupport {

	@Nested
	@DisplayName("Construction and factory method")
	class ConstructionTest {

		@Test
		@DisplayName("should create via factory method with reference name and children")
		void shouldCreateViaFactoryMethodWithReferenceNameAndChildren() {
			final ReferenceProperty constraint = referenceProperty(
				"parameters", attributeNatural("code")
			);

			assertNotNull(constraint);
			assertEquals("parameters", constraint.getReferenceName());
			assertArrayEquals(
				new OrderConstraint[]{attributeNatural("code")},
				constraint.getChildren()
			);
		}

		@Test
		@DisplayName("should create via factory method with multiple children")
		void shouldCreateViaFactoryMethodWithMultipleChildren() {
			final ReferenceProperty constraint = referenceProperty(
				"brand",
				attributeNatural("priority", DESC),
				attributeNatural("name")
			);

			assertNotNull(constraint);
			assertEquals("brand", constraint.getReferenceName());
			assertEquals(2, constraint.getChildrenCount());
		}

		@Test
		@DisplayName("should return null when factory receives null reference name")
		void shouldReturnNullWhenFactoryReceivesNullReferenceName() {
			final ReferenceProperty result = referenceProperty(null, attributeNatural("code"));

			assertNull(result);
		}

		@Test
		@DisplayName("should return null when factory receives null children")
		void shouldReturnNullWhenFactoryReceivesNullChildren() {
			final ReferenceProperty result = referenceProperty("parameter", (OrderConstraint[]) null);

			assertNull(result);
		}

		@Test
		@DisplayName("should return null when factory receives no children")
		void shouldReturnNullWhenFactoryReceivesNoChildren() {
			final ReferenceProperty result = referenceProperty("parameter");

			assertNull(result);
		}

		@Test
		@DisplayName("should create via public constructor with no children")
		void shouldCreateViaPublicConstructorWithNoChildren() {
			final ReferenceProperty constraint = new ReferenceProperty("parameter");

			assertEquals("parameter", constraint.getReferenceName());
			assertEquals(0, constraint.getChildrenCount());
		}
	}

	@Nested
	@DisplayName("Applicability and necessity")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable when it has children")
		void shouldBeApplicableWhenItHasChildren() {
			final ReferenceProperty constraint = referenceProperty(
				"parameter", attributeNatural("code")
			);

			assertTrue(constraint.isApplicable());
		}

		@Test
		@DisplayName("should not be applicable when it has no children")
		void shouldNotBeApplicableWhenItHasNoChildren() {
			final ReferenceProperty constraint = new ReferenceProperty("parameter");

			assertFalse(constraint.isApplicable());
		}

		@Test
		@DisplayName("should be necessary when it has at least one child")
		void shouldBeNecessaryWhenItHasAtLeastOneChild() {
			final ReferenceProperty constraint = referenceProperty(
				"parameter", attributeNatural("code")
			);

			assertTrue(constraint.isNecessary());
		}

		@Test
		@DisplayName("should not be necessary when it has no children")
		void shouldNotBeNecessaryWhenItHasNoChildren() {
			final ReferenceProperty constraint = new ReferenceProperty("parameter");

			assertFalse(constraint.isNecessary());
		}

		@Test
		@DisplayName("should be necessary with multiple children")
		void shouldBeNecessaryWithMultipleChildren() {
			final ReferenceProperty constraint = referenceProperty(
				"parameter",
				attributeNatural("code"),
				attributeNatural("name", DESC)
			);

			assertTrue(constraint.isNecessary());
		}
	}

	@Nested
	@DisplayName("Accessor methods")
	class AccessorTest {

		@Test
		@DisplayName("should return reference name")
		void shouldReturnReferenceName() {
			final ReferenceProperty constraint = referenceProperty(
				"brand", attributeNatural("order")
			);

			assertEquals("brand", constraint.getReferenceName());
		}

		@Test
		@DisplayName("should return order by children via getOrderBy")
		void shouldReturnOrderByChildrenViaGetOrderBy() {
			final ReferenceProperty constraint = referenceProperty(
				"brand",
				attributeNatural("priority"),
				attributeNatural("name", DESC)
			);

			final OrderConstraint[] orderBy = constraint.getOrderBy();

			assertEquals(2, orderBy.length);
			assertInstanceOf(AttributeNatural.class, orderBy[0]);
			assertInstanceOf(AttributeNatural.class, orderBy[1]);
		}

		@Test
		@DisplayName("should return order constraints excluding TraverseByEntityProperty")
		void shouldReturnOrderConstraintsExcludingTraverseByEntityProperty() {
			final ReferenceProperty constraint = referenceProperty(
				"categories",
				traverseByEntityProperty(attributeNatural("order")),
				attributeNatural("orderInCategory")
			);

			final List<OrderConstraint> orderConstraints = constraint.getOrderConstraints();

			assertEquals(1, orderConstraints.size());
			assertInstanceOf(AttributeNatural.class, orderConstraints.get(0));
		}

		@Test
		@DisplayName("should exclude PickFirstByEntityProperty from order constraints")
		void shouldExcludePickFirstByEntityPropertyFromOrderConstraints() {
			final ReferenceProperty constraint = referenceProperty(
				"categories",
				pickFirstByEntityProperty(attributeNatural("code")),
				attributeNatural("orderInCategory")
			);

			final List<OrderConstraint> orderConstraints = constraint.getOrderConstraints();

			assertEquals(1, orderConstraints.size());
			assertInstanceOf(AttributeNatural.class, orderConstraints.get(0));
		}

		@Test
		@DisplayName("should return empty optional when no reference ordering specification")
		void shouldReturnEmptyOptionalWhenNoReferenceOrderingSpecification() {
			final ReferenceProperty constraint = referenceProperty(
				"brand", attributeNatural("order")
			);

			final Optional<ReferenceOrderingSpecification> spec =
				constraint.getReferenceOrderingSpecification();

			assertTrue(spec.isEmpty());
		}

		@Test
		@DisplayName("should return TraverseByEntityProperty as reference ordering specification")
		void shouldReturnTraverseByEntityPropertyAsReferenceOrderingSpecification() {
			final ReferenceProperty constraint = referenceProperty(
				"categories",
				traverseByEntityProperty(attributeNatural("order")),
				attributeNatural("orderInCategory")
			);

			final Optional<ReferenceOrderingSpecification> spec =
				constraint.getReferenceOrderingSpecification();

			assertTrue(spec.isPresent());
			assertInstanceOf(TraverseByEntityProperty.class, spec.get());
		}

		@Test
		@DisplayName("should return PickFirstByEntityProperty as reference ordering specification")
		void shouldReturnPickFirstByEntityPropertyAsReferenceOrderingSpecification() {
			final ReferenceProperty constraint = referenceProperty(
				"stocks",
				pickFirstByEntityProperty(attributeNatural("code")),
				attributeNatural("quantity")
			);

			final Optional<ReferenceOrderingSpecification> spec =
				constraint.getReferenceOrderingSpecification();

			assertTrue(spec.isPresent());
			assertInstanceOf(PickFirstByEntityProperty.class, spec.get());
		}

		@Test
		@DisplayName("should throw when duplicate reference ordering specifications found")
		void shouldThrowWhenDuplicateReferenceOrderingSpecificationsFound() {
			final ReferenceProperty constraint = referenceProperty(
				"categories",
				traverseByEntityProperty(attributeNatural("order")),
				pickFirstByEntityProperty(attributeNatural("code")),
				attributeNatural("orderInCategory")
			);

			assertThrows(
				EvitaInvalidUsageException.class,
				constraint::getReferenceOrderingSpecification
			);
		}
	}

	@Nested
	@DisplayName("Type and visitor")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return OrderConstraint class as type")
		void shouldReturnOrderConstraintClassAsType() {
			final ReferenceProperty constraint = referenceProperty(
				"brand", attributeNatural("order")
			);

			assertEquals(OrderConstraint.class, constraint.getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final ReferenceProperty constraint = referenceProperty(
				"brand", attributeNatural("order")
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
		@DisplayName("should create copy with new children preserving reference name")
		void shouldCreateCopyWithNewChildrenPreservingReferenceName() {
			final ReferenceProperty original = referenceProperty(
				"brand", attributeNatural("code")
			);

			final OrderConstraint copy = original.getCopyWithNewChildren(
				new OrderConstraint[]{attributeNatural("name", DESC)},
				new Constraint<?>[0]
			);

			assertInstanceOf(ReferenceProperty.class, copy);
			final ReferenceProperty refCopy = (ReferenceProperty) copy;
			assertEquals("brand", refCopy.getReferenceName());
			assertEquals(1, refCopy.getChildrenCount());
			assertEquals("name", ((AttributeNatural) refCopy.getChildren()[0]).getAttributeName());
		}

		@Test
		@DisplayName("should clone with new arguments preserving children")
		void shouldCloneWithNewArgumentsPreservingChildren() {
			final ReferenceProperty original = referenceProperty(
				"brand", attributeNatural("code")
			);

			final OrderConstraint cloned = original.cloneWithArguments(
				new Serializable[]{"category"}
			);

			assertInstanceOf(ReferenceProperty.class, cloned);
			final ReferenceProperty refCloned = (ReferenceProperty) cloned;
			assertEquals("category", refCloned.getReferenceName());
			assertArrayEquals(original.getChildren(), refCloned.getChildren());
		}

		@Test
		@DisplayName("should produce equal but not same instance via cloneWithArguments")
		void shouldProduceEqualButNotSameInstanceViaCloneWithArguments() {
			final ReferenceProperty original = referenceProperty(
				"brand", attributeNatural("code")
			);

			final OrderConstraint cloned = original.cloneWithArguments(
				new Serializable[]{"brand"}
			);

			assertNotSame(original, cloned);
			assertEquals(original, cloned);
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce expected toString format with single child")
		void shouldProduceExpectedToStringFormatWithSingleChild() {
			final ReferenceProperty constraint = referenceProperty(
				"parameter", attributeNatural("code")
			);

			assertEquals(
				"referenceProperty('parameter',attributeNatural('code',ASC))",
				constraint.toString()
			);
		}

		@Test
		@DisplayName("should produce expected toString format with multiple children")
		void shouldProduceExpectedToStringFormatWithMultipleChildren() {
			final ReferenceProperty constraint = referenceProperty(
				"brand",
				attributeNatural("priority", DESC),
				attributeNatural("name")
			);

			assertEquals(
				"referenceProperty('brand',attributeNatural('priority',DESC),attributeNatural('name',ASC))",
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
				referenceProperty("parameter", attributeNatural("code")),
				referenceProperty("parameter", attributeNatural("code"))
			);
			assertEquals(
				referenceProperty("parameter", attributeNatural("code")),
				referenceProperty("parameter", attributeNatural("code"))
			);
			assertNotEquals(
				referenceProperty("parameter", attributeNatural("code")),
				referenceProperty("parameter", attributeNatural("order"))
			);
			assertNotEquals(
				referenceProperty("parameter", attributeNatural("code")),
				referenceProperty("groups", attributeNatural("code"))
			);
			assertNotEquals(
				referenceProperty("parameter", attributeNatural("code")),
				referenceProperty("parameter", (OrderConstraint) null)
			);
			assertEquals(
				referenceProperty("parameter", attributeNatural("code")).hashCode(),
				referenceProperty("parameter", attributeNatural("code")).hashCode()
			);
			assertNotEquals(
				referenceProperty("parameter", attributeNatural("code")).hashCode(),
				referenceProperty("parameter", attributeNatural("order")).hashCode()
			);
			assertNotEquals(
				referenceProperty("parameter", attributeNatural("code")).hashCode(),
				referenceProperty("groups", attributeNatural("code")).hashCode()
			);
		}
	}
}
