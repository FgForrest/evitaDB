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
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.and;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link And} verifying construction, applicability, necessity, copy/clone operations,
 * visitor acceptance, and equality contract.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("And constraint")
class AndTest {

	@Nested
	@DisplayName("Construction and factory method")
	class ConstructionTest {

		@Test
		@DisplayName("should create via factory method with children")
		void shouldCreateViaFactoryClassWorkAsExpected() {
			final ConstraintContainer<FilterConstraint> and = and(
				attributeEquals("abc", "def"),
				attributeEquals("abc", "xyz")
			);

			assertNotNull(and);
			assertEquals(2, and.getChildrenCount());
			assertEquals("abc", ((AttributeEquals) and.getChildren()[0]).getAttributeName());
			assertEquals("def", ((AttributeEquals) and.getChildren()[0]).getAttributeValue());
			assertEquals("abc", ((AttributeEquals) and.getChildren()[1]).getAttributeName());
			assertEquals("xyz", ((AttributeEquals) and.getChildren()[1]).getAttributeValue());
		}
	}

	@Nested
	@DisplayName("Applicability and necessity")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable when it has children")
		void shouldRecognizeApplicability() {
			assertTrue(new And(attributeEquals("abc", "def")).isApplicable());
			assertFalse(new And().isApplicable());
		}

		@Test
		@DisplayName("should be necessary only with multiple children")
		void shouldRecognizeNecessity() {
			assertTrue(new And(attributeEquals("abc", "def"), attributeEquals("xyz", "def")).isNecessary());
			assertFalse(new And(attributeEquals("abc", "def")).isNecessary());
			assertFalse(new And().isNecessary());
		}
	}

	@Nested
	@DisplayName("Copy and clone operations")
	class CopyAndCloneTest {

		@Test
		@DisplayName("should create copy with new children")
		void shouldCreateCopyWithNewChildren() {
			final And original = new And(attributeEquals("abc", "def"), attributeEquals("xyz", "123"));
			final FilterConstraint copy = original.getCopyWithNewChildren(
				new FilterConstraint[]{attributeEquals("new", "val")},
				new Constraint<?>[0]
			);

			assertInstanceOf(And.class, copy);
			assertEquals(1, ((And) copy).getChildrenCount());
			assertEquals("new", ((AttributeEquals) ((And) copy).getChildren()[0]).getAttributeName());
		}

		@Test
		@DisplayName("should reject non-empty additional children in getCopyWithNewChildren")
		void shouldRejectNonEmptyAdditionalChildren() {
			final And original = new And(attributeEquals("abc", "def"));

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
			final And and = new And(attributeEquals("abc", "def"));

			assertThrows(
				UnsupportedOperationException.class,
				() -> and.cloneWithArguments(new Serializable[]{"arg"})
			);
		}
	}

	@Nested
	@DisplayName("Type and visitor")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return FilterConstraint class as type")
		void shouldReturnFilterConstraintClassAsType() {
			final And and = new And(attributeEquals("abc", "def"));

			assertEquals(FilterConstraint.class, and.getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final And and = new And(attributeEquals("abc", "def"));
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();
			and.accept(new ConstraintVisitor() {
				@Override
				public void visit(@Nonnull Constraint<?> constraint) {
					visited.set(constraint);
				}
			});

			assertSame(and, visited.get());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce expected toString format")
		void shouldToStringReturnExpectedFormat() {
			final ConstraintContainer<FilterConstraint> and = and(
				attributeEquals("abc", '\''),
				attributeEquals("abc", 'x')
			);

			assertNotNull(and);
			assertEquals("and(attributeEquals('abc','\\''),attributeEquals('abc','x'))", and.toString());
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(createAndConstraint("abc", "def"), createAndConstraint("abc", "def"));
			assertEquals(createAndConstraint("abc", "def"), createAndConstraint("abc", "def"));
			assertNotEquals(createAndConstraint("abc", "def"), createAndConstraint("abc", "defe"));
			assertNotEquals(createAndConstraint("abc", "def"), createAndConstraint("abc", null));
			assertNotEquals(createAndConstraint("abc", "def"), createAndConstraint(null, "abc"));
			assertEquals(
				createAndConstraint("abc", "def").hashCode(),
				createAndConstraint("abc", "def").hashCode()
			);
			assertNotEquals(
				createAndConstraint("abc", "def").hashCode(),
				createAndConstraint("abc", "defe").hashCode()
			);
			assertNotEquals(
				createAndConstraint("abc", "def").hashCode(),
				createAndConstraint("abc", null).hashCode()
			);
			assertNotEquals(
				createAndConstraint("abc", "def").hashCode(),
				createAndConstraint(null, "abc").hashCode()
			);
		}
	}

	/**
	 * Creates an {@link And} constraint containing {@link AttributeEquals} children built from the given values.
	 */
	@Nonnull
	private static And createAndConstraint(@Nullable String... values) {
		return and(
			Arrays.stream(values)
				.map(it -> attributeEquals("abc", it))
				.toArray(FilterConstraint[]::new)
		);
	}
}
