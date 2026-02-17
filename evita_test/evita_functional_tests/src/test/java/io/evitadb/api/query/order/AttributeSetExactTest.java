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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.attributeSetExact;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AttributeSetExact} verifying construction, applicability, property accessors,
 * cloning, visitor support, string representation, and equality contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("AttributeSetExact constraint")
class AttributeSetExactTest {

	@Nested
	@DisplayName("Construction and factory methods")
	class ConstructionTest {

		@Test
		@DisplayName("should create with attribute name and values")
		void shouldCreateWithNameAndValues() {
			final AttributeSetExact constraint = attributeSetExact("age", 18, 45, 13);

			assertEquals("age", constraint.getAttributeName());
			assertArrayEquals(new Serializable[]{18, 45, 13}, constraint.getAttributeValues());
		}

		@Test
		@DisplayName("should return null from factory when no attribute values provided")
		void shouldReturnNullWhenNoValues() {
			assertNull(attributeSetExact("age"));
		}

		@Test
		@DisplayName("should return null from factory when attribute name is null")
		void shouldReturnNullWhenNameIsNull() {
			assertNull(attributeSetExact(null));
		}

		@Test
		@DisplayName("should create with string attribute values")
		void shouldCreateWithStringValues() {
			final AttributeSetExact constraint = attributeSetExact("code", "t-shirt", "sweater", "pants");

			assertEquals("code", constraint.getAttributeName());
			assertArrayEquals(
				new Serializable[]{"t-shirt", "sweater", "pants"},
				constraint.getAttributeValues()
			);
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable with attribute name and values")
		void shouldBeApplicableWithNameAndValues() {
			assertTrue(attributeSetExact("age", 18, 45, 13).isApplicable());
		}

		@Test
		@DisplayName("should not be applicable with name only and no values")
		void shouldNotBeApplicableWithNameOnly() {
			assertFalse(new AttributeSetExact("married").isApplicable());
		}

		@Test
		@DisplayName("should not be applicable when name is null")
		void shouldNotBeApplicableWhenNameIsNull() {
			assertFalse(new AttributeSetExact(null).isApplicable());
		}
	}

	@Nested
	@DisplayName("Type and visitor support")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return OrderConstraint class as type")
		void shouldReturnCorrectType() {
			assertEquals(OrderConstraint.class, attributeSetExact("age", 18).getType());
		}

		@Test
		@DisplayName("should accept visitor and call visit method")
		void shouldAcceptVisitor() {
			final AttributeSetExact constraint = attributeSetExact("age", 18, 45, 13);
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
			final AttributeSetExact original = attributeSetExact("age", 18, 45, 13);
			final OrderConstraint clone = original.cloneWithArguments(new Serializable[]{"age", 18, 45, 13});

			assertEquals(original, clone);
			assertNotSame(original, clone);
			assertInstanceOf(AttributeSetExact.class, clone);
		}

		@Test
		@DisplayName("should clone with different arguments")
		void shouldCloneWithDifferentArguments() {
			final AttributeSetExact original = attributeSetExact("age", 18, 45, 13);
			final OrderConstraint clone = original.cloneWithArguments(new Serializable[]{"name", "a", "b"});

			assertNotEquals(original, clone);
			assertInstanceOf(AttributeSetExact.class, clone);
			final AttributeSetExact cloned = (AttributeSetExact) clone;
			assertEquals("name", cloned.getAttributeName());
			assertArrayEquals(new Serializable[]{"a", "b"}, cloned.getAttributeValues());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce correct string with integer values")
		void shouldToStringWithIntegers() {
			assertEquals("attributeSetExact('age',18,45,13)", attributeSetExact("age", 18, 45, 13).toString());
		}

		@Test
		@DisplayName("should produce correct string with string values")
		void shouldToStringWithStrings() {
			assertEquals(
				"attributeSetExact('code','t-shirt','sweater','pants')",
				attributeSetExact("code", "t-shirt", "sweater", "pants").toString()
			);
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should be equal for same attribute name and values")
		void shouldBeEqualForSameArgs() {
			assertNotSame(attributeSetExact("age", 18, 45, 13), attributeSetExact("age", 18, 45, 13));
			assertEquals(attributeSetExact("age", 18, 45, 13), attributeSetExact("age", 18, 45, 13));
		}

		@Test
		@DisplayName("should not be equal for different attribute names")
		void shouldNotBeEqualForDifferentNames() {
			assertNotEquals(
				attributeSetExact("age", 18, 45, 13),
				attributeSetExact("single", 18, 45, 13)
			);
		}

		@Test
		@DisplayName("should not be equal for different value counts")
		void shouldNotBeEqualForDifferentValueCounts() {
			assertNotEquals(
				attributeSetExact("age", 18, 45, 13),
				attributeSetExact("age", 18, 45)
			);
		}

		@Test
		@DisplayName("should have consistent hashCode for equal instances")
		void shouldHaveConsistentHashCode() {
			assertEquals(
				attributeSetExact("age", 18, 45, 13).hashCode(),
				attributeSetExact("age", 18, 45, 13).hashCode()
			);
			assertNotEquals(
				attributeSetExact("age", 18, 45, 13).hashCode(),
				attributeSetExact("single", 18, 45, 13).hashCode()
			);
			assertNotEquals(
				attributeSetExact("age", 18, 45, 13).hashCode(),
				attributeSetExact("age", 18, 45).hashCode()
			);
		}

		@Test
		@DisplayName("should not be equal to non-applicable instance")
		void shouldNotBeEqualToNonApplicable() {
			assertNotEquals(attributeSetExact("age", 18, 45, 13), attributeSetExact(null));
			assertNotEquals(
				attributeSetExact("age", 18, 45, 13).hashCode(),
				new AttributeSetExact(null).hashCode()
			);
		}
	}
}
