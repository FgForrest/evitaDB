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

import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyExact;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EntityPrimaryKeyExact} verifying construction, applicability, property accessors,
 * cloning, visitor support, string representation, and equality contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("EntityPrimaryKeyExact constraint")
class EntityPrimaryKeyExactTest {

	@Nested
	@DisplayName("Construction and factory methods")
	class ConstructionTest {

		@Test
		@DisplayName("should create with primary key values")
		void shouldCreateWithPrimaryKeys() {
			final EntityPrimaryKeyExact constraint = entityPrimaryKeyExact(18, 45, 13);

			assertArrayEquals(new int[]{18, 45, 13}, constraint.getPrimaryKeys());
		}

		@Test
		@DisplayName("should return null from factory when no keys provided")
		void shouldReturnNullWhenNoKeys() {
			assertNull(entityPrimaryKeyExact());
		}

		@Test
		@DisplayName("should create with single primary key")
		void shouldCreateWithSingleKey() {
			final EntityPrimaryKeyExact constraint = entityPrimaryKeyExact(42);

			assertArrayEquals(new int[]{42}, constraint.getPrimaryKeys());
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable with primary key values")
		void shouldBeApplicableWithKeys() {
			assertTrue(entityPrimaryKeyExact(18, 45, 13).isApplicable());
		}

		@Test
		@DisplayName("should not be applicable when no keys provided")
		void shouldNotBeApplicableWhenEmpty() {
			assertFalse(new EntityPrimaryKeyExact().isApplicable());
		}
	}

	@Nested
	@DisplayName("Type and visitor support")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return OrderConstraint class as type")
		void shouldReturnCorrectType() {
			assertEquals(OrderConstraint.class, entityPrimaryKeyExact(1).getType());
		}

		@Test
		@DisplayName("should accept visitor and call visit method")
		void shouldAcceptVisitor() {
			final EntityPrimaryKeyExact constraint = entityPrimaryKeyExact(18, 45, 13);
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
			final EntityPrimaryKeyExact original = entityPrimaryKeyExact(18, 45, 13);
			final OrderConstraint clone = original.cloneWithArguments(new Serializable[]{18, 45, 13});

			assertEquals(original, clone);
			assertNotSame(original, clone);
			assertInstanceOf(EntityPrimaryKeyExact.class, clone);
		}

		@Test
		@DisplayName("should clone with different arguments")
		void shouldCloneWithDifferentArguments() {
			final EntityPrimaryKeyExact original = entityPrimaryKeyExact(18, 45, 13);
			final OrderConstraint clone = original.cloneWithArguments(new Serializable[]{1, 2});

			assertNotEquals(original, clone);
			assertInstanceOf(EntityPrimaryKeyExact.class, clone);
			final EntityPrimaryKeyExact cloned = (EntityPrimaryKeyExact) clone;
			assertArrayEquals(new int[]{1, 2}, cloned.getPrimaryKeys());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce correct string representation")
		void shouldToString() {
			assertEquals("entityPrimaryKeyExact(18,45,13)", entityPrimaryKeyExact(18, 45, 13).toString());
		}

		@Test
		@DisplayName("should produce correct string for single key")
		void shouldToStringForSingleKey() {
			assertEquals("entityPrimaryKeyExact(42)", entityPrimaryKeyExact(42).toString());
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should be equal for same primary keys")
		void shouldBeEqualForSameKeys() {
			assertNotSame(entityPrimaryKeyExact(18, 45, 13), entityPrimaryKeyExact(18, 45, 13));
			assertEquals(entityPrimaryKeyExact(18, 45, 13), entityPrimaryKeyExact(18, 45, 13));
		}

		@Test
		@DisplayName("should not be equal for different key counts")
		void shouldNotBeEqualForDifferentKeyCounts() {
			assertNotEquals(entityPrimaryKeyExact(18, 45, 13), entityPrimaryKeyExact(18, 45));
		}

		@Test
		@DisplayName("should not be equal for different key values")
		void shouldNotBeEqualForDifferentKeyValues() {
			assertNotEquals(entityPrimaryKeyExact(18, 45, 13), entityPrimaryKeyExact(18, 45, 99));
		}

		@Test
		@DisplayName("should have consistent hashCode for equal instances")
		void shouldHaveConsistentHashCode() {
			assertEquals(
				entityPrimaryKeyExact(18, 45, 13).hashCode(),
				entityPrimaryKeyExact(18, 45, 13).hashCode()
			);
			assertNotEquals(
				entityPrimaryKeyExact(18, 45, 13).hashCode(),
				entityPrimaryKeyExact(18, 45).hashCode()
			);
		}
	}
}
