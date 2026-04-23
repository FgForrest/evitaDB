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
import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.OrderConstraint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.attributeNatural;
import static io.evitadb.api.query.QueryConstraints.orderGroupBy;
import static io.evitadb.api.query.order.OrderDirection.ASC;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link OrderGroupBy} verifying construction, applicability, necessity, accessor methods,
 * copy/clone operations, visitor acceptance, and equality contract.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("OrderGroupBy constraint")
class OrderGroupByTest {

	@Nested
	@DisplayName("Construction and factory method")
	class ConstructionTest {

		@Test
		@DisplayName("should create via factory method with children")
		void shouldCreateViaFactoryMethodWithChildren() {
			final ConstraintContainer<OrderConstraint> orderGroupBy = orderGroupBy(
				attributeNatural("abc"),
				attributeNatural("def", DESC)
			);

			assertNotNull(orderGroupBy);
			assertEquals(2, orderGroupBy.getChildrenCount());
			assertEquals("abc", ((AttributeNatural) orderGroupBy.getChildren()[0]).getAttributeName());
			assertEquals(ASC, ((AttributeNatural) orderGroupBy.getChildren()[0]).getOrderDirection());
			assertEquals("def", ((AttributeNatural) orderGroupBy.getChildren()[1]).getAttributeName());
			assertEquals(DESC, ((AttributeNatural) orderGroupBy.getChildren()[1]).getOrderDirection());
		}

		@Test
		@DisplayName("should return null when factory method receives null")
		void shouldReturnNullWhenFactoryMethodReceivesNull() {
			final OrderGroupBy result = orderGroupBy((OrderConstraint[]) null);

			assertNull(result);
		}
	}

	@Nested
	@DisplayName("Applicability and necessity")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable when it has children")
		void shouldBeApplicableWhenItHasChildren() {
			final OrderGroupBy orderGroupBy = new OrderGroupBy(attributeNatural("abc"));

			assertTrue(orderGroupBy.isApplicable());
		}

		@Test
		@DisplayName("should not be applicable when it has no children")
		void shouldNotBeApplicableWhenItHasNoChildren() {
			final OrderGroupBy emptyOrderGroupBy = new OrderGroupBy();

			assertFalse(emptyOrderGroupBy.isApplicable());
		}

		@Test
		@DisplayName("should be necessary when applicable")
		void shouldBeNecessaryWhenApplicable() {
			final OrderGroupBy singleChild = new OrderGroupBy(attributeNatural("abc"));

			assertTrue(singleChild.isNecessary());
		}

		@Test
		@DisplayName("should not be necessary when not applicable")
		void shouldNotBeNecessaryWhenNotApplicable() {
			final OrderGroupBy emptyOrderGroupBy = new OrderGroupBy();

			assertFalse(emptyOrderGroupBy.isNecessary());
		}
	}

	@Nested
	@DisplayName("Accessor methods")
	class AccessorTest {

		@Test
		@DisplayName("should return null when getChild called on empty container")
		void shouldReturnNullWhenGetChildCalledOnEmptyContainer() {
			final OrderGroupBy emptyOrderGroupBy = new OrderGroupBy();

			assertNull(emptyOrderGroupBy.getChild());
		}

		@Test
		@DisplayName("should return single child from getChild")
		void shouldReturnSingleChildFromGetChild() {
			final OrderGroupBy orderGroupBy = new OrderGroupBy(attributeNatural("abc"));

			final OrderConstraint child = orderGroupBy.getChild();

			assertNotNull(child);
			assertInstanceOf(AttributeNatural.class, child);
			assertEquals("abc", ((AttributeNatural) child).getAttributeName());
		}

		@Test
		@DisplayName("should return first child when getChild called with multiple children")
		void shouldReturnFirstChildWhenGetChildCalledWithMultipleChildren() {
			// Unlike OrderBy, OrderGroupBy returns first child silently when multiple children present
			final OrderGroupBy orderGroupBy = new OrderGroupBy(
				attributeNatural("abc"),
				attributeNatural("def", DESC)
			);

			final OrderConstraint child = orderGroupBy.getChild();

			assertNotNull(child);
			assertInstanceOf(AttributeNatural.class, child);
			assertEquals("abc", ((AttributeNatural) child).getAttributeName());
		}
	}

	@Nested
	@DisplayName("Type and visitor")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return OrderConstraint class as type")
		void shouldReturnOrderConstraintClassAsType() {
			final OrderGroupBy orderGroupBy = new OrderGroupBy(attributeNatural("abc"));

			assertEquals(OrderConstraint.class, orderGroupBy.getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final OrderGroupBy orderGroupBy = new OrderGroupBy(
				attributeNatural("abc"),
				attributeNatural("def", DESC)
			);
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();
			orderGroupBy.accept(new ConstraintVisitor() {
				@Override
				public void visit(@Nonnull Constraint<?> constraint) {
					visited.set(constraint);
				}
			});

			assertSame(orderGroupBy, visited.get());
		}
	}

	@Nested
	@DisplayName("Copy and clone operations")
	class CopyAndCloneTest {

		@Test
		@DisplayName("should create copy with new children")
		void shouldCreateCopyWithNewChildren() {
			final OrderGroupBy original = new OrderGroupBy(attributeNatural("abc"));
			final OrderConstraint copy = original.getCopyWithNewChildren(
				new OrderConstraint[]{attributeNatural("xyz", DESC)},
				new Constraint<?>[0]
			);

			assertInstanceOf(OrderGroupBy.class, copy);
			assertEquals(1, ((OrderGroupBy) copy).getChildrenCount());
			assertEquals("xyz", ((AttributeNatural) ((OrderGroupBy) copy).getChildren()[0]).getAttributeName());
		}

		@Test
		@DisplayName("should silently ignore additional children in getCopyWithNewChildren")
		void shouldSilentlyIgnoreAdditionalChildren() {
			// Unlike OrderBy, OrderGroupBy does NOT reject additionalChildren
			final OrderGroupBy original = new OrderGroupBy(attributeNatural("abc"));
			final OrderConstraint copy = original.getCopyWithNewChildren(
				new OrderConstraint[]{attributeNatural("xyz")},
				new Constraint<?>[]{attributeNatural("extra")}
			);

			assertInstanceOf(OrderGroupBy.class, copy);
			assertEquals(1, ((OrderGroupBy) copy).getChildrenCount());
		}

		@Test
		@DisplayName("should throw UnsupportedOperationException when cloning with arguments")
		void shouldThrowWhenCloningWithArguments() {
			final OrderGroupBy orderGroupBy = new OrderGroupBy(attributeNatural("abc"));

			// argument-less containers throw UnsupportedOperationException by convention
			assertThrows(
				UnsupportedOperationException.class,
				() -> orderGroupBy.cloneWithArguments(new Serializable[]{"arg"})
			);
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce expected toString format")
		void shouldProduceExpectedToStringFormat() {
			final ConstraintContainer<OrderConstraint> orderGroupBy = orderGroupBy(
				attributeNatural("ab'c"),
				attributeNatural("abc", DESC)
			);

			assertNotNull(orderGroupBy);
			assertEquals(
				"orderGroupBy(attributeNatural('ab\\'c',ASC),attributeNatural('abc',DESC))",
				orderGroupBy.toString()
			);
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(createOrderGroupBy("abc", "def"), createOrderGroupBy("abc", "def"));
			assertEquals(createOrderGroupBy("abc", "def"), createOrderGroupBy("abc", "def"));
			assertNotEquals(createOrderGroupBy("abc", "def"), createOrderGroupBy("abc", "defe"));
			assertNotEquals(createOrderGroupBy("abc", "def"), createOrderGroupBy("abc", null));
			assertNotEquals(createOrderGroupBy("abc", "def"), createOrderGroupBy(null, "abc"));
			assertEquals(
				createOrderGroupBy("abc", "def").hashCode(),
				createOrderGroupBy("abc", "def").hashCode()
			);
			assertNotEquals(
				createOrderGroupBy("abc", "def").hashCode(),
				createOrderGroupBy("abc", "defe").hashCode()
			);
		}
	}

	/**
	 * Creates an {@link OrderGroupBy} constraint containing {@link AttributeNatural} children built from the given
	 * attribute names.
	 */
	@Nullable
	private static OrderGroupBy createOrderGroupBy(@Nullable String... values) {
		return orderGroupBy(
			Arrays.stream(values)
				.map(it -> attributeNatural(it))
				.toArray(OrderConstraint[]::new)
		);
	}
}
