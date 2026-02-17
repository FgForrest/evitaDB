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

import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.or;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Or} verifying construction, applicability, necessity, copy/clone operations,
 * visitor acceptance, and equality contract.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Or constraint")
class OrTest {

	@Nested
	@DisplayName("Construction and factory method")
	class ConstructionTest {

		@Test
		@DisplayName("should create via factory method with children")
		void shouldCreateViaFactoryClassWorkAsExpected() {
			final ConstraintContainer<FilterConstraint> or = or(
				attributeEquals("abc", "def"),
				attributeEquals("abc", "xyz")
			);

			assertNotNull(or);
			assertEquals(2, or.getChildrenCount());
			assertEquals("abc", ((AttributeEquals) or.getChildren()[0]).getAttributeName());
			assertEquals("def", ((AttributeEquals) or.getChildren()[0]).getAttributeValue());
			assertEquals("abc", ((AttributeEquals) or.getChildren()[1]).getAttributeName());
			assertEquals("xyz", ((AttributeEquals) or.getChildren()[1]).getAttributeValue());
		}
	}

	@Nested
	@DisplayName("Applicability and necessity")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable when it has children")
		void shouldRecognizeApplicability() {
			assertTrue(new Or(attributeEquals("abc", "def")).isApplicable());
			assertFalse(new Or().isApplicable());
		}

		@Test
		@DisplayName("should be necessary only with multiple children")
		void shouldRecognizeNecessity() {
			assertTrue(new Or(attributeEquals("abc", "def"), attributeEquals("xyz", "def")).isNecessary());
			assertFalse(new Or(attributeEquals("abc", "def")).isNecessary());
			assertFalse(new Or().isNecessary());
		}
	}

	@Nested
	@DisplayName("Copy and clone operations")
	class CopyAndCloneTest {

		@Test
		@DisplayName("should create copy with new children")
		void shouldCreateCopyWithNewChildren() {
			final Or original = new Or(attributeEquals("abc", "def"), attributeEquals("xyz", "123"));
			final FilterConstraint copy = original.getCopyWithNewChildren(
				new FilterConstraint[]{attributeEquals("new", "val")},
				new Constraint<?>[0]
			);

			assertInstanceOf(Or.class, copy);
			assertEquals(1, ((Or) copy).getChildrenCount());
			assertEquals("new", ((AttributeEquals) ((Or) copy).getChildren()[0]).getAttributeName());
		}

		@Test
		@DisplayName("should reject non-empty additional children in getCopyWithNewChildren")
		void shouldRejectNonEmptyAdditionalChildren() {
			final Or original = new Or(attributeEquals("abc", "def"));

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
			final Or or = new Or(attributeEquals("abc", "def"));

			assertThrows(
				UnsupportedOperationException.class,
				() -> or.cloneWithArguments(new Serializable[]{"arg"})
			);
		}
	}

	@Nested
	@DisplayName("Type and visitor")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return FilterConstraint class as type")
		void shouldReturnFilterConstraintClassAsType() {
			final Or or = new Or(attributeEquals("abc", "def"));

			assertEquals(FilterConstraint.class, or.getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final Or or = new Or(attributeEquals("abc", "def"));
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();
			or.accept(new ConstraintVisitor() {
				@Override
				public void visit(@Nonnull Constraint<?> constraint) {
					visited.set(constraint);
				}
			});

			assertSame(or, visited.get());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce expected toString format")
		void shouldToStringReturnExpectedFormat() {
			final ConstraintContainer<FilterConstraint> or = or(
				attributeEquals("abc", '\''),
				attributeEquals("abc", 'x')
			);

			assertNotNull(or);
			assertEquals("or(attributeEquals('abc','\\''),attributeEquals('abc','x'))", or.toString());
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(createOrConstraint("abc", "def"), createOrConstraint("abc", "def"));
			assertEquals(createOrConstraint("abc", "def"), createOrConstraint("abc", "def"));
			assertNotEquals(createOrConstraint("abc", "def"), createOrConstraint("abc", "defe"));
			assertNotEquals(createOrConstraint("abc", "def"), createOrConstraint("abc", null));
			assertNotEquals(createOrConstraint("abc", "def"), createOrConstraint(null, "abc"));
			assertEquals(
				createOrConstraint("abc", "def").hashCode(),
				createOrConstraint("abc", "def").hashCode()
			);
			assertNotEquals(
				createOrConstraint("abc", "def").hashCode(),
				createOrConstraint("abc", "defe").hashCode()
			);
			assertNotEquals(
				createOrConstraint("abc", "def").hashCode(),
				createOrConstraint("abc", null).hashCode()
			);
			assertNotEquals(
				createOrConstraint("abc", "def").hashCode(),
				createOrConstraint(null, "abc").hashCode()
			);
		}
	}

	/**
	 * Creates an {@link Or} constraint containing {@link AttributeEquals} children built from the given values.
	 */
	@Nonnull
	private static Or createOrConstraint(@Nullable String... values) {
		return or(
			Arrays.stream(values)
				.map(it -> attributeEquals("abc", it))
				.toArray(FilterConstraint[]::new)
		);
	}
}
