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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.attributeSetInFilter;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AttributeSetInFilter} verifying construction, applicability, property accessors,
 * cloning, visitor support, string representation, and equality contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("AttributeSetInFilter constraint")
class AttributeSetInFilterTest {

	@Nested
	@DisplayName("Construction and factory methods")
	class ConstructionTest {

		@Test
		@DisplayName("should create with attribute name")
		void shouldCreateWithName() {
			final AttributeSetInFilter constraint = attributeSetInFilter("age");

			assertNotNull(constraint);
			assertEquals("age", constraint.getAttributeName());
		}

		@Test
		@DisplayName("should return null from factory when attribute name is null")
		void shouldReturnNullWhenNameIsNull() {
			assertNull(attributeSetInFilter(null));
		}

		@Test
		@DisplayName("should return null from factory when attribute name is blank")
		void shouldReturnNullWhenNameIsBlank() {
			assertNull(attributeSetInFilter(""));
			assertNull(attributeSetInFilter("   "));
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable with valid attribute name")
		void shouldBeApplicableWithValidName() {
			assertTrue(attributeSetInFilter("age").isApplicable());
		}

		@Test
		@DisplayName("should not be applicable when name is null")
		void shouldNotBeApplicableWhenNameIsNull() {
			assertFalse(new AttributeSetInFilter(null).isApplicable());
		}
	}

	@Nested
	@DisplayName("Type and visitor support")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return OrderConstraint class as type")
		void shouldReturnCorrectType() {
			assertEquals(OrderConstraint.class, attributeSetInFilter("age").getType());
		}

		@Test
		@DisplayName("should accept visitor and call visit method")
		void shouldAcceptVisitor() {
			final AttributeSetInFilter constraint = attributeSetInFilter("age");
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
	@DisplayName("Clone operations")
	class CloningTest {

		@Test
		@DisplayName("should produce equal but not same instance via cloneWithArguments")
		void shouldCloneWithArguments() {
			final AttributeSetInFilter original = attributeSetInFilter("age");
			final OrderConstraint clone = original.cloneWithArguments(new Serializable[]{"age"});

			assertEquals(original, clone);
			assertNotSame(original, clone);
			assertInstanceOf(AttributeSetInFilter.class, clone);
		}

		@Test
		@DisplayName("should clone with different attribute name")
		void shouldCloneWithDifferentName() {
			final AttributeSetInFilter original = attributeSetInFilter("age");
			final OrderConstraint clone = original.cloneWithArguments(new Serializable[]{"name"});

			assertNotEquals(original, clone);
			assertInstanceOf(AttributeSetInFilter.class, clone);
			final AttributeSetInFilter cloned = (AttributeSetInFilter) clone;
			assertEquals("name", cloned.getAttributeName());
		}

		@Test
		@DisplayName("should throw when cloneWithArguments receives too many arguments")
		void shouldThrowWhenCloneWithTooManyArgs() {
			final AttributeSetInFilter constraint = attributeSetInFilter("age");

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> constraint.cloneWithArguments(new Serializable[]{"age", "extra"})
			);
		}

		@Test
		@DisplayName("should throw when cloneWithArguments receives empty arguments")
		void shouldThrowWhenCloneWithEmptyArgs() {
			final AttributeSetInFilter constraint = attributeSetInFilter("age");

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> constraint.cloneWithArguments(new Serializable[]{})
			);
		}

		@Test
		@DisplayName("should throw when cloneWithArguments receives non-string argument")
		void shouldThrowWhenCloneWithNonStringArg() {
			final AttributeSetInFilter constraint = attributeSetInFilter("age");

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> constraint.cloneWithArguments(new Serializable[]{42})
			);
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce correct string representation")
		void shouldToString() {
			assertEquals("attributeSetInFilter('age')", attributeSetInFilter("age").toString());
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should be equal for same attribute name")
		void shouldBeEqualForSameName() {
			assertNotSame(attributeSetInFilter("age"), attributeSetInFilter("age"));
			assertEquals(attributeSetInFilter("age"), attributeSetInFilter("age"));
		}

		@Test
		@DisplayName("should not be equal for different attribute names")
		void shouldNotBeEqualForDifferentNames() {
			assertNotEquals(attributeSetInFilter("age"), attributeSetInFilter("single"));
		}

		@Test
		@DisplayName("should have consistent hashCode for equal instances")
		void shouldHaveConsistentHashCode() {
			assertEquals(
				attributeSetInFilter("age").hashCode(),
				attributeSetInFilter("age").hashCode()
			);
			assertNotEquals(
				attributeSetInFilter("age").hashCode(),
				attributeSetInFilter("single").hashCode()
			);
		}

		@Test
		@DisplayName("should not be equal to non-applicable instance")
		void shouldNotBeEqualToNonApplicable() {
			assertNotEquals(attributeSetInFilter("age"), attributeSetInFilter(null));
			assertNotEquals(
				attributeSetInFilter("age").hashCode(),
				new AttributeSetInFilter(null).hashCode()
			);
		}
	}
}
