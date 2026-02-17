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
import io.evitadb.api.query.FilterConstraint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EntityPrimaryKeyInSet} verifying construction, applicability, property accessors,
 * cloning, visitor support, string representation, and equality contract.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("EntityPrimaryKeyInSet constraint")
class EntityPrimaryKeyInSetTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should create with primary keys via factory")
		void shouldCreateWithPrimaryKeysViaFactory() {
			final EntityPrimaryKeyInSet constraint = entityPrimaryKeyInSet(1, 5, 7);

			assertArrayEquals(new int[]{1, 5, 7}, constraint.getPrimaryKeys());
		}

		@Test
		@DisplayName("should filter null values from array")
		void shouldFilterNullValuesFromArray() {
			final EntityPrimaryKeyInSet constraint = entityPrimaryKeyInSet(1, null, 7);

			assertArrayEquals(new int[]{1, 7}, constraint.getPrimaryKeys());
		}

		@Test
		@DisplayName("should return null for null variable")
		void shouldReturnNullForNullVariable() {
			final Integer nullInteger = null;
			final EntityPrimaryKeyInSet constraint = entityPrimaryKeyInSet(nullInteger);

			assertNull(constraint);
		}

		@Test
		@DisplayName("should create with empty array")
		void shouldCreateWithEmptyArray() {
			final EntityPrimaryKeyInSet constraint = entityPrimaryKeyInSet(new Integer[0]);

			assertArrayEquals(new int[0], constraint.getPrimaryKeys());
		}
	}

	@Nested
	@DisplayName("Property accessors")
	class PropertyAccessorTest {

		@Test
		@DisplayName("should return primary keys")
		void shouldReturnPrimaryKeys() {
			final EntityPrimaryKeyInSet constraint = entityPrimaryKeyInSet(10, 20, 30);

			assertArrayEquals(new int[]{10, 20, 30}, constraint.getPrimaryKeys());
		}

		@Test
		@DisplayName("should return empty primary keys for empty constructor")
		void shouldReturnEmptyPrimaryKeysForEmptyConstructor() {
			final EntityPrimaryKeyInSet constraint = new EntityPrimaryKeyInSet();

			assertArrayEquals(new int[0], constraint.getPrimaryKeys());
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should always be applicable")
		void shouldAlwaysBeApplicable() {
			assertTrue(new EntityPrimaryKeyInSet().isApplicable());
			assertTrue(entityPrimaryKeyInSet(1).isApplicable());
			assertTrue(entityPrimaryKeyInSet(1, 5, 7).isApplicable());
		}
	}

	@Nested
	@DisplayName("Clone with arguments")
	class CloneWithArgumentsTest {

		@Test
		@DisplayName("should clone with new arguments")
		void shouldCloneWithNewArguments() {
			final EntityPrimaryKeyInSet original = entityPrimaryKeyInSet(1, 2, 3);

			final FilterConstraint cloned = original.cloneWithArguments(new Serializable[]{10, 20});

			assertInstanceOf(EntityPrimaryKeyInSet.class, cloned);
			assertNotSame(original, cloned);
			assertArrayEquals(new int[]{10, 20}, ((EntityPrimaryKeyInSet) cloned).getPrimaryKeys());
		}
	}

	@Nested
	@DisplayName("Type and visitor")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return FilterConstraint type")
		void shouldReturnFilterConstraintType() {
			assertEquals(FilterConstraint.class, entityPrimaryKeyInSet(1).getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final EntityPrimaryKeyInSet constraint = entityPrimaryKeyInSet(1, 2);
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();

			constraint.accept(c -> visited.set((Constraint<?>) c));

			assertSame(constraint, visited.get());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should format with primary keys")
		void shouldFormatWithPrimaryKeys() {
			assertEquals("entityPrimaryKeyInSet(1,5,7)", entityPrimaryKeyInSet(1, 5, 7).toString());
		}
	}

	@Nested
	@DisplayName("Equals and hashCode")
	class EqualsAndHashCodeTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(entityPrimaryKeyInSet(1, 1, 5), entityPrimaryKeyInSet(1, 1, 5));
			assertEquals(entityPrimaryKeyInSet(1, 1, 5), entityPrimaryKeyInSet(1, 1, 5));
			assertNotEquals(entityPrimaryKeyInSet(1, 1, 5), entityPrimaryKeyInSet(1, 1, 6));
			assertNotEquals(entityPrimaryKeyInSet(1, 1, 5), entityPrimaryKeyInSet(1, 1));
			assertNotEquals(entityPrimaryKeyInSet(1, 1, 5), entityPrimaryKeyInSet(2, 1, 5));
			assertEquals(
				entityPrimaryKeyInSet(1, 1, 5).hashCode(),
				entityPrimaryKeyInSet(1, 1, 5).hashCode()
			);
			assertNotEquals(
				entityPrimaryKeyInSet(1, 1, 5).hashCode(),
				entityPrimaryKeyInSet(1, 1, 6).hashCode()
			);
			assertNotEquals(
				entityPrimaryKeyInSet(1, 1, 5).hashCode(),
				entityPrimaryKeyInSet(1, 1).hashCode()
			);
		}
	}
}
