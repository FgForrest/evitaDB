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

import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyNatural;
import static io.evitadb.api.query.order.OrderDirection.ASC;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EntityPrimaryKeyNatural} verifying construction, applicability, property accessors,
 * cloning, visitor support, string representation, and equality contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("EntityPrimaryKeyNatural constraint")
class EntityPrimaryKeyNaturalTest {

	@Nested
	@DisplayName("Construction and factory methods")
	class ConstructionTest {

		@Test
		@DisplayName("should create with explicit direction")
		void shouldCreateWithExplicitDirection() {
			final EntityPrimaryKeyNatural constraint = entityPrimaryKeyNatural(DESC);

			assertEquals(DESC, constraint.getOrderDirection());
		}

		@Test
		@DisplayName("should normalize null direction to ASC")
		void shouldNormalizeNullDirectionToAsc() {
			final EntityPrimaryKeyNatural constraint = entityPrimaryKeyNatural(null);

			assertNotNull(constraint);
			assertEquals(ASC, constraint.getOrderDirection());
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable with ASC direction")
		void shouldBeApplicableWithAsc() {
			assertTrue(entityPrimaryKeyNatural(ASC).isApplicable());
		}

		@Test
		@DisplayName("should be applicable with DESC direction")
		void shouldBeApplicableWithDesc() {
			assertTrue(entityPrimaryKeyNatural(DESC).isApplicable());
		}

		@Test
		@DisplayName("should be applicable when null direction normalized to ASC")
		void shouldBeApplicableWhenNullNormalized() {
			assertTrue(entityPrimaryKeyNatural(null).isApplicable());
		}
	}

	@Nested
	@DisplayName("Type and visitor support")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return OrderConstraint class as type")
		void shouldReturnCorrectType() {
			assertEquals(OrderConstraint.class, entityPrimaryKeyNatural(ASC).getType());
		}

		@Test
		@DisplayName("should accept visitor and call visit method")
		void shouldAcceptVisitor() {
			final EntityPrimaryKeyNatural constraint = entityPrimaryKeyNatural(DESC);
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
			final EntityPrimaryKeyNatural original = entityPrimaryKeyNatural(DESC);
			final OrderConstraint clone = original.cloneWithArguments(new Serializable[]{DESC});

			assertEquals(original, clone);
			assertNotSame(original, clone);
			assertInstanceOf(EntityPrimaryKeyNatural.class, clone);
		}

		@Test
		@DisplayName("should clone with different direction")
		void shouldCloneWithDifferentDirection() {
			final EntityPrimaryKeyNatural original = entityPrimaryKeyNatural(ASC);
			final OrderConstraint clone = original.cloneWithArguments(new Serializable[]{DESC});

			assertNotEquals(original, clone);
			assertInstanceOf(EntityPrimaryKeyNatural.class, clone);
			final EntityPrimaryKeyNatural cloned = (EntityPrimaryKeyNatural) clone;
			assertEquals(DESC, cloned.getOrderDirection());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce correct string with ASC direction")
		void shouldToStringWithAsc() {
			assertEquals("entityPrimaryKeyNatural(ASC)", entityPrimaryKeyNatural(null).toString());
		}

		@Test
		@DisplayName("should produce correct string with DESC direction")
		void shouldToStringWithDesc() {
			assertEquals("entityPrimaryKeyNatural(DESC)", entityPrimaryKeyNatural(DESC).toString());
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should be equal for same direction")
		void shouldBeEqualForSameDirection() {
			assertNotSame(entityPrimaryKeyNatural(null), entityPrimaryKeyNatural(null));
			assertEquals(entityPrimaryKeyNatural(null), entityPrimaryKeyNatural(null));
			assertEquals(entityPrimaryKeyNatural(ASC), entityPrimaryKeyNatural(null));
		}

		@Test
		@DisplayName("should not be equal for different directions")
		void shouldNotBeEqualForDifferentDirections() {
			assertNotEquals(entityPrimaryKeyNatural(ASC), entityPrimaryKeyNatural(DESC));
		}

		@Test
		@DisplayName("should have consistent hashCode for equal instances")
		void shouldHaveConsistentHashCode() {
			assertEquals(
				entityPrimaryKeyNatural(null).hashCode(),
				entityPrimaryKeyNatural(null).hashCode()
			);
			assertEquals(
				entityPrimaryKeyNatural(ASC).hashCode(),
				entityPrimaryKeyNatural(null).hashCode()
			);
			assertNotEquals(
				entityPrimaryKeyNatural(ASC).hashCode(),
				entityPrimaryKeyNatural(DESC).hashCode()
			);
		}
	}
}
