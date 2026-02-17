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

import static io.evitadb.api.query.QueryConstraints.attributeNatural;
import static io.evitadb.api.query.order.OrderDirection.ASC;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AttributeNatural} verifying construction, applicability, property accessors,
 * cloning, visitor support, string representation, and equality contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@DisplayName("AttributeNatural constraint")
class AttributeNaturalTest {

	@Nested
	@DisplayName("Construction and factory methods")
	class ConstructionTest {

		@Test
		@DisplayName("should create with attribute name defaulting to ASC direction")
		void shouldCreateWithDefaultAscDirection() {
			final AttributeNatural constraint = attributeNatural("age");

			assertEquals("age", constraint.getAttributeName());
			assertEquals(ASC, constraint.getOrderDirection());
		}

		@Test
		@DisplayName("should create with attribute name and explicit direction")
		void shouldCreateWithExplicitDirection() {
			final AttributeNatural constraint = attributeNatural("married", DESC);

			assertEquals("married", constraint.getAttributeName());
			assertEquals(DESC, constraint.getOrderDirection());
		}

		@Test
		@DisplayName("should normalize null direction to ASC")
		void shouldNormalizeNullDirectionToAsc() {
			final AttributeNatural constraint = attributeNatural("age", null);

			assertEquals(ASC, constraint.getOrderDirection());
		}

		@Test
		@DisplayName("should return null from factory when attribute name is null")
		void shouldReturnNullFromFactoryWhenNameIsNull() {
			assertNull(attributeNatural(null));
			assertNull(attributeNatural(null, DESC));
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable with valid attribute name and direction")
		void shouldBeApplicableWithValidArgs() {
			assertTrue(attributeNatural("married").isApplicable());
			assertTrue(attributeNatural("age", DESC).isApplicable());
		}

		@Test
		@DisplayName("should not be applicable when attribute name is null")
		void shouldNotBeApplicableWhenNameIsNull() {
			assertFalse(new AttributeNatural(null).isApplicable());
		}
	}

	@Nested
	@DisplayName("Type and visitor support")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return OrderConstraint class as type")
		void shouldReturnCorrectType() {
			assertEquals(OrderConstraint.class, attributeNatural("age").getType());
		}

		@Test
		@DisplayName("should accept visitor and call visit method")
		void shouldAcceptVisitor() {
			final AttributeNatural constraint = attributeNatural("age");
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
			final AttributeNatural original = attributeNatural("age", DESC);
			final OrderConstraint clone = original.cloneWithArguments(new Serializable[]{"age", DESC});

			assertEquals(original, clone);
			assertNotSame(original, clone);
			assertInstanceOf(AttributeNatural.class, clone);
		}

		@Test
		@DisplayName("should clone with different arguments")
		void shouldCloneWithDifferentArguments() {
			final AttributeNatural original = attributeNatural("age", ASC);
			final OrderConstraint clone = original.cloneWithArguments(new Serializable[]{"name", DESC});

			assertNotEquals(original, clone);
			assertInstanceOf(AttributeNatural.class, clone);
			final AttributeNatural clonedNatural = (AttributeNatural) clone;
			assertEquals("name", clonedNatural.getAttributeName());
			assertEquals(DESC, clonedNatural.getOrderDirection());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce correct string with default ASC direction")
		void shouldToStringWithDefaultAsc() {
			assertEquals("attributeNatural('married',ASC)", attributeNatural("married").toString());
		}

		@Test
		@DisplayName("should produce correct string with DESC direction")
		void shouldToStringWithDesc() {
			assertEquals("attributeNatural('married',DESC)", attributeNatural("married", DESC).toString());
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should be equal for same attribute name and direction")
		void shouldBeEqualForSameArgs() {
			assertNotSame(attributeNatural("married"), attributeNatural("married"));
			assertEquals(attributeNatural("married"), attributeNatural("married"));
		}

		@Test
		@DisplayName("should be equal when explicit ASC matches default ASC")
		void shouldBeEqualWhenExplicitAscMatchesDefault() {
			assertEquals(attributeNatural("married", ASC), attributeNatural("married"));
		}

		@Test
		@DisplayName("should not be equal for different attribute names")
		void shouldNotBeEqualForDifferentNames() {
			assertNotEquals(attributeNatural("married"), attributeNatural("single"));
		}

		@Test
		@DisplayName("should not be equal for different directions")
		void shouldNotBeEqualForDifferentDirections() {
			assertNotEquals(attributeNatural("age", ASC), attributeNatural("age", DESC));
		}

		@Test
		@DisplayName("should have consistent hashCode for equal instances")
		void shouldHaveConsistentHashCode() {
			assertEquals(attributeNatural("married").hashCode(), attributeNatural("married").hashCode());
			assertEquals(attributeNatural("married", ASC).hashCode(), attributeNatural("married").hashCode());
			assertNotEquals(attributeNatural("married").hashCode(), attributeNatural("single").hashCode());
		}

		@Test
		@DisplayName("should not be equal to non-applicable instance")
		void shouldNotBeEqualToNonApplicable() {
			assertNotEquals(attributeNatural("married"), new AttributeNatural(null));
			assertNotEquals(attributeNatural("married").hashCode(), new AttributeNatural(null).hashCode());
		}
	}
}
