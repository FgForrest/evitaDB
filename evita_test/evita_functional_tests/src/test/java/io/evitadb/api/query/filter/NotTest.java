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

package io.evitadb.api.query.filter;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintContainer;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.not;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Not} verifying construction, applicability, necessity, copy/clone operations,
 * child access, visitor acceptance, and equality contract.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Not constraint")
class NotTest {

	@Nested
	@DisplayName("Construction and factory method")
	class ConstructionTest {

		@Test
		@DisplayName("should create via factory method with single child")
		void shouldCreateViaFactoryClassWorkAsExpected() {
			final ConstraintContainer<FilterConstraint> not = not(
				attributeEquals("abc", "def")
			);

			assertNotNull(not);
			assertEquals(1, not.getChildrenCount());
			assertEquals("abc", ((AttributeEquals) not.getChildren()[0]).getAttributeName());
			assertEquals("def", ((AttributeEquals) not.getChildren()[0]).getAttributeValue());
		}

		@Test
		@DisplayName("should return the single child via getChild()")
		void shouldReturnSingleChildViaGetChild() {
			final AttributeEquals child = new AttributeEquals("abc", "def");
			final Not not = new Not(child);

			final FilterConstraint result = not.getChild();

			assertEquals(child, result);
		}

		@Test
		@DisplayName("should throw EvitaInvalidUsageException when getChild() called on empty Not")
		void shouldThrowWhenGetChildCalledOnEmptyNot() {
			final Not emptyNot = (Not) new Not(attributeEquals("abc", "def"))
				.getCopyWithNewChildren(new FilterConstraint[0], new Constraint<?>[0]);

			assertFalse(emptyNot.isNecessary());
			assertThrows(
				EvitaInvalidUsageException.class,
				emptyNot::getChild
			);
		}
	}

	@Nested
	@DisplayName("Applicability and necessity")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable when it has a child")
		void shouldRecognizeApplicability() {
			assertTrue(new Not(attributeEquals("abc", "def")).isApplicable());

			final Not hackedCopy = (Not) new Not(attributeEquals("abc", "def"))
				.getCopyWithNewChildren(new FilterConstraint[0], new Constraint<?>[0]);
			assertFalse(hackedCopy.isApplicable());
		}

		@Test
		@DisplayName("should be necessary when it has a child")
		void shouldRecognizeNecessity() {
			assertTrue(new Not(attributeEquals("abc", "def")).isNecessary());

			final Not hackedCopy = (Not) new Not(attributeEquals("abc", "def"))
				.getCopyWithNewChildren(new FilterConstraint[0], new Constraint<?>[0]);
			assertFalse(hackedCopy.isNecessary());
		}
	}

	@Nested
	@DisplayName("Copy and clone operations")
	class CopyAndCloneTest {

		@Test
		@DisplayName("should create copy with new child")
		void shouldCreateCopyWithNewChild() {
			final Not original = new Not(attributeEquals("abc", "def"));
			final FilterConstraint copy = original.getCopyWithNewChildren(
				new FilterConstraint[]{attributeEquals("xyz", "123")},
				new Constraint<?>[0]
			);

			assertInstanceOf(Not.class, copy);
			assertEquals(1, ((Not) copy).getChildrenCount());
			assertEquals("xyz", ((AttributeEquals) ((Not) copy).getChild()).getAttributeName());
		}

		@Test
		@DisplayName("should create empty copy when no children provided")
		void shouldCreateEmptyCopyWhenNoChildrenProvided() {
			final Not original = new Not(attributeEquals("abc", "def"));
			final FilterConstraint copy = original.getCopyWithNewChildren(
				new FilterConstraint[0],
				new Constraint<?>[0]
			);

			assertInstanceOf(Not.class, copy);
			assertFalse(((Not) copy).isApplicable());
		}

		@Test
		@DisplayName("should reject non-empty additional children in getCopyWithNewChildren")
		void shouldRejectNonEmptyAdditionalChildren() {
			final Not original = new Not(attributeEquals("abc", "def"));

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> original.getCopyWithNewChildren(
					new FilterConstraint[]{attributeEquals("xyz", "123")},
					new Constraint<?>[]{new OrderBy()}
				)
			);
		}

		@Test
		@DisplayName("should throw UnsupportedOperationException when cloning with arguments")
		void shouldThrowWhenCloningWithArguments() {
			final Not not = new Not(attributeEquals("abc", "def"));

			assertThrows(
				UnsupportedOperationException.class,
				() -> not.cloneWithArguments(new Serializable[]{"arg"})
			);
		}
	}

	@Nested
	@DisplayName("Type and visitor")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return FilterConstraint class as type")
		void shouldReturnFilterConstraintClassAsType() {
			final Not not = new Not(attributeEquals("abc", "def"));

			assertEquals(FilterConstraint.class, not.getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final Not not = new Not(attributeEquals("abc", "def"));
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();
			not.accept(new ConstraintVisitor() {
				@Override
				public void visit(@Nonnull Constraint<?> constraint) {
					visited.set(constraint);
				}
			});

			assertSame(not, visited.get());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce expected toString format")
		void shouldToStringReturnExpectedFormat() {
			final ConstraintContainer<FilterConstraint> not = not(
				attributeEquals("abc", '\'')
			);

			assertNotNull(not);
			assertEquals("not(attributeEquals('abc','\\''))", not.toString());
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(createNotConstraint("def"), createNotConstraint("def"));
			assertEquals(createNotConstraint("def"), createNotConstraint("def"));
			assertNotEquals(createNotConstraint("def"), createNotConstraint("defe"));
			assertNotEquals(createNotConstraint("def"), createNotConstraint(null));
			assertEquals(
				createNotConstraint("def").hashCode(),
				createNotConstraint("def").hashCode()
			);
			assertNotEquals(
				createNotConstraint("def").hashCode(),
				createNotConstraint("defe").hashCode()
			);
			assertNotEquals(
				createNotConstraint("def").hashCode(),
				createNotConstraint(null).hashCode()
			);
		}
	}

	/**
	 * Creates a {@link Not} constraint wrapping an {@link AttributeEquals} built from the given value.
	 */
	@Nonnull
	private static Not createNotConstraint(@Nullable String value) {
		return not(new AttributeEquals("abc", value));
	}
}
