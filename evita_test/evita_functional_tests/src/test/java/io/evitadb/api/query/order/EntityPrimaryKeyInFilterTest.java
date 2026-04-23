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

import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInFilter;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EntityPrimaryKeyInFilter} verifying construction, applicability,
 * cloning, visitor support, string representation, and equality contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("EntityPrimaryKeyInFilter constraint")
class EntityPrimaryKeyInFilterTest {

	@Nested
	@DisplayName("Construction and factory methods")
	class ConstructionTest {

		@Test
		@DisplayName("should create via no-arg factory method")
		void shouldCreateViaFactory() {
			final EntityPrimaryKeyInFilter constraint = entityPrimaryKeyInFilter();

			assertNotNull(constraint);
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should always be applicable")
		void shouldAlwaysBeApplicable() {
			assertTrue(entityPrimaryKeyInFilter().isApplicable());
		}
	}

	@Nested
	@DisplayName("Type and visitor support")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return OrderConstraint class as type")
		void shouldReturnCorrectType() {
			assertEquals(OrderConstraint.class, entityPrimaryKeyInFilter().getType());
		}

		@Test
		@DisplayName("should accept visitor and call visit method")
		void shouldAcceptVisitor() {
			final EntityPrimaryKeyInFilter constraint = entityPrimaryKeyInFilter();
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
		@DisplayName("should produce equal but not same instance via cloneWithArguments with empty args")
		void shouldCloneWithEmptyArguments() {
			final EntityPrimaryKeyInFilter original = entityPrimaryKeyInFilter();
			final OrderConstraint clone = original.cloneWithArguments(new Serializable[]{});

			assertEquals(original, clone);
			assertNotSame(original, clone);
			assertInstanceOf(EntityPrimaryKeyInFilter.class, clone);
		}

		@Test
		@DisplayName("should throw when cloneWithArguments receives non-empty arguments")
		void shouldThrowWhenCloneWithNonEmptyArgs() {
			final EntityPrimaryKeyInFilter constraint = entityPrimaryKeyInFilter();
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> constraint.cloneWithArguments(new Serializable[]{"unexpected"})
			);

			assertTrue(exception.getMessage().contains("EntityPrimaryKeyInFilter"));
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce correct string representation")
		void shouldToString() {
			assertEquals("entityPrimaryKeyInFilter()", entityPrimaryKeyInFilter().toString());
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should be equal for two instances")
		void shouldBeEqualForTwoInstances() {
			assertNotSame(entityPrimaryKeyInFilter(), entityPrimaryKeyInFilter());
			assertEquals(entityPrimaryKeyInFilter(), entityPrimaryKeyInFilter());
		}

		@Test
		@DisplayName("should have consistent hashCode")
		void shouldHaveConsistentHashCode() {
			assertEquals(entityPrimaryKeyInFilter().hashCode(), entityPrimaryKeyInFilter().hashCode());
		}
	}
}
