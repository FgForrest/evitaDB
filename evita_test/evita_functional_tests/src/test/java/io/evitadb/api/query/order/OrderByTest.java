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
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;

import static io.evitadb.api.query.QueryConstraints.attributeNatural;
import static io.evitadb.api.query.QueryConstraints.orderBy;
import static io.evitadb.api.query.order.OrderDirection.ASC;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.ORDER;

/**
 * Tests for {@link OrderBy} verifying construction, applicability, necessity, accessor methods,
 * copy/clone operations, visitor acceptance, and equality contract.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("OrderBy constraint")
@Tag(CONTRACT)
@Tag(ORDER)
class OrderByTest {

	@Nested
	@DisplayName("Construction and factory method")
	class ConstructionTest {

		@Test
		@DisplayName("should create via factory method with children")
		void shouldCreateViaFactoryMethodWithChildren() {
			final ConstraintContainer<OrderConstraint> orderBy = orderBy(
				attributeNatural("abc"),
				attributeNatural("def", DESC)
			);

			assertNotNull(orderBy);
			assertEquals(2, orderBy.getChildrenCount());
			assertEquals("abc", ((AttributeNatural) orderBy.getChildren()[0]).getAttributeName());
			assertEquals(ASC, ((AttributeNatural) orderBy.getChildren()[0]).getOrderDirection());
			assertEquals("def", ((AttributeNatural) orderBy.getChildren()[1]).getAttributeName());
			assertEquals(DESC, ((AttributeNatural) orderBy.getChildren()[1]).getOrderDirection());
		}

		@Test
		@DisplayName("should return null when factory method receives null")
		void shouldReturnNullWhenFactoryMethodReceivesNull() {
			final OrderBy result = orderBy((OrderConstraint[]) null);

			assertNull(result);
		}
	}

	@Nested
	@DisplayName("Applicability and necessity")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable when it has children")
		void shouldBeApplicableWhenItHasChildren() {
			final OrderBy orderBy = new OrderBy(attributeNatural("abc"));

			assertTrue(orderBy.isApplicable());
		}

		@Test
		@DisplayName("should not be applicable when it has no children")
		void shouldNotBeApplicableWhenItHasNoChildren() {
			final OrderBy emptyOrderBy = new OrderBy();

			assertFalse(emptyOrderBy.isApplicable());
		}

		@Test
		@DisplayName("should be necessary when applicable (single child is necessary)")
		void shouldBeNecessaryWhenApplicable() {
			final OrderBy singleChild = new OrderBy(attributeNatural("abc"));

			assertTrue(singleChild.isNecessary());
		}

		@Test
		@DisplayName("should be necessary with multiple children")
		void shouldBeNecessaryWithMultipleChildren() {
			final OrderBy multipleChildren = new OrderBy(
				attributeNatural("abc"),
				attributeNatural("xyz", DESC)
			);

			assertTrue(multipleChildren.isNecessary());
		}

		@Test
		@DisplayName("should not be necessary when not applicable")
		void shouldNotBeNecessaryWhenNotApplicable() {
			final OrderBy emptyOrderBy = new OrderBy();

			assertFalse(emptyOrderBy.isNecessary());
		}
	}

	@Nested
	@DisplayName("Accessor methods")
	class AccessorTest {

		@Test
		@DisplayName("should return null when getChild called on empty container")
		void shouldReturnNullWhenGetChildCalledOnEmptyContainer() {
			final OrderBy emptyOrderBy = new OrderBy();

			assertNull(emptyOrderBy.getChild());
		}

		@Test
		@DisplayName("should return single child from getChild")
		void shouldReturnSingleChildFromGetChild() {
			final OrderBy orderBy = new OrderBy(attributeNatural("abc"));

			final OrderConstraint child = orderBy.getChild();

			assertNotNull(child);
			assertInstanceOf(AttributeNatural.class, child);
			assertEquals("abc", ((AttributeNatural) child).getAttributeName());
		}

		@Test
		@DisplayName("should throw when getChild called with multiple children")
		void shouldThrowWhenGetChildCalledWithMultipleChildren() {
			final OrderBy orderBy = new OrderBy(
				attributeNatural("abc"),
				attributeNatural("def", DESC)
			);

			assertThrows(GenericEvitaInternalError.class, orderBy::getChild);
		}
	}

	@Nested
	@DisplayName("Type and visitor")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return OrderConstraint class as type")
		void shouldReturnOrderConstraintClassAsType() {
			final OrderBy orderBy = new OrderBy(attributeNatural("abc"));

			assertEquals(OrderConstraint.class, orderBy.getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final OrderBy orderBy = new OrderBy(
				attributeNatural("abc"),
				attributeNatural("def", DESC)
			);
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();
			orderBy.accept(new ConstraintVisitor() {
				@Override
				public void visit(@Nonnull Constraint<?> constraint) {
					visited.set(constraint);
				}
			});

			assertSame(orderBy, visited.get());
		}
	}

	@Nested
	@DisplayName("Copy and clone operations")
	class CopyAndCloneTest {

		@Test
		@DisplayName("should create copy with new children")
		void shouldCreateCopyWithNewChildren() {
			final OrderBy original = new OrderBy(attributeNatural("abc"));
			final OrderConstraint copy = original.getCopyWithNewChildren(
				new OrderConstraint[]{attributeNatural("xyz", DESC)},
				new Constraint<?>[0]
			);

			assertInstanceOf(OrderBy.class, copy);
			assertEquals(1, ((OrderBy) copy).getChildrenCount());
			assertEquals("xyz", ((AttributeNatural) ((OrderBy) copy).getChildren()[0]).getAttributeName());
		}

		@Test
		@DisplayName("should create empty copy when no children provided")
		void shouldCreateEmptyCopyWhenNoChildrenProvided() {
			final OrderBy original = new OrderBy(attributeNatural("abc"));
			final OrderConstraint copy = original.getCopyWithNewChildren(
				new OrderConstraint[0],
				new Constraint<?>[0]
			);

			assertInstanceOf(OrderBy.class, copy);
			assertFalse(((OrderBy) copy).isApplicable());
		}

		@Test
		@DisplayName("should reject non-empty additional children")
		void shouldRejectNonEmptyAdditionalChildren() {
			final OrderBy original = new OrderBy(attributeNatural("abc"));

			assertThrows(
				GenericEvitaInternalError.class,
				() -> original.getCopyWithNewChildren(
					new OrderConstraint[]{attributeNatural("xyz")},
					new Constraint<?>[]{attributeNatural("extra")}
				)
			);
		}

		@Test
		@DisplayName("should throw UnsupportedOperationException when cloning with arguments")
		void shouldThrowWhenCloningWithArguments() {
			final OrderBy orderBy = new OrderBy(attributeNatural("abc"));

			// argument-less containers throw UnsupportedOperationException by convention
			assertThrows(
				UnsupportedOperationException.class,
				() -> orderBy.cloneWithArguments(new Serializable[]{"arg"})
			);
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce expected toString format")
		void shouldProduceExpectedToStringFormat() {
			final ConstraintContainer<OrderConstraint> orderBy = orderBy(
				attributeNatural("ab'c"),
				attributeNatural("abc", DESC)
			);

			assertNotNull(orderBy);
			assertEquals(
				"orderBy(attributeNatural('ab\\'c',ASC),attributeNatural('abc',DESC))",
				orderBy.toString()
			);
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(createOrderBy("abc", "def"), createOrderBy("abc", "def"));
			assertEquals(createOrderBy("abc", "def"), createOrderBy("abc", "def"));
			assertNotEquals(createOrderBy("abc", "def"), createOrderBy("abc", "defe"));
			assertNotEquals(createOrderBy("abc", "def"), createOrderBy("abc", null));
			assertNotEquals(createOrderBy("abc", "def"), createOrderBy(null, "abc"));
			assertEquals(
				createOrderBy("abc", "def").hashCode(),
				createOrderBy("abc", "def").hashCode()
			);
			assertNotEquals(
				createOrderBy("abc", "def").hashCode(),
				createOrderBy("abc", "defe").hashCode()
			);
		}
	}

	/**
	 * Creates an {@link OrderBy} constraint containing {@link AttributeNatural} children built from the given
	 * attribute names.
	 */
	@Nullable
	private static OrderBy createOrderBy(@Nullable String... values) {
		return orderBy(
			Arrays.stream(values)
				.map(it -> attributeNatural(it))
				.toArray(OrderConstraint[]::new)
		);
	}
}
