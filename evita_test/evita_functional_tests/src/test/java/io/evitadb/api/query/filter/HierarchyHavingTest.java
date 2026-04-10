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

import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.having;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HierarchyHaving} verifying construction, applicability, necessity,
 * cloning, visitor acceptance, and equality contract.
 *
 * @author Lukas Hornych, FG Forrest a.s. (c) 2023
 */
@DisplayName("HierarchyHaving constraint")
class HierarchyHavingTest {

	@Nested
	@DisplayName("Construction and factory methods")
	class ConstructionTest {

		@Test
		@DisplayName("should create via factory method with filtering child")
		void shouldCreateViaFactoryClassWorkAsExpected() {
			final HierarchyHaving having = having(attributeEquals("code", "a"));

			assertArrayEquals(new FilterConstraint[]{attributeEquals("code", "a")}, having.getFiltering());
		}
	}

	@Nested
	@DisplayName("Core operations")
	class CoreOperationsTest {

		@Test
		@DisplayName("should recognize applicability based on children presence")
		void shouldRecognizeApplicability() {
			assertFalse(new HierarchyHaving().isApplicable());
			assertTrue(having(entityPrimaryKeyInSet(1)).isApplicable());
		}

		@Test
		@DisplayName("should recognize necessity based on children presence")
		void shouldRecognizeNecessity() {
			assertFalse(new HierarchyHaving().isNecessary());
			assertTrue(having(entityPrimaryKeyInSet(1)).isNecessary());
		}

		@Test
		@DisplayName("should return FilterConstraint type")
		void shouldReturnFilterConstraintType() {
			final HierarchyHaving constraint = having(entityPrimaryKeyInSet(1));

			assertEquals(FilterConstraint.class, constraint.getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final HierarchyHaving constraint = having(entityPrimaryKeyInSet(1));
			final AtomicReference<Constraint<?>> captured = new AtomicReference<>();

			constraint.accept(captured::set);

			assertSame(constraint, captured.get());
		}

		@Test
		@DisplayName("should create new instance from cloneWithArguments")
		void shouldCreateNewInstanceFromCloneWithArguments() {
			final HierarchyHaving original = having(entityPrimaryKeyInSet(1));

			final FilterConstraint cloned = original.cloneWithArguments(new Serializable[0]);

			assertNotSame(original, cloned);
			assertEquals(original, cloned);
			assertInstanceOf(HierarchyHaving.class, cloned);
			assertArrayEquals(original.getFiltering(), ((HierarchyHaving) cloned).getFiltering());
		}

		@Test
		@DisplayName("should create copy with new children")
		void shouldCreateCopyWithNewChildren() {
			final HierarchyHaving original = having(entityPrimaryKeyInSet(1));
			final FilterConstraint[] newChildren = new FilterConstraint[]{attributeEquals("code", "b")};

			final FilterConstraint copy = original.getCopyWithNewChildren(newChildren, new Constraint<?>[0]);

			assertNotSame(original, copy);
			assertInstanceOf(HierarchyHaving.class, copy);
			assertArrayEquals(newChildren, ((HierarchyHaving) copy).getFiltering());
		}

		@Test
		@DisplayName("should reject non-empty additional children in getCopyWithNewChildren")
		void shouldRejectNonEmptyAdditionalChildren() {
			final HierarchyHaving original = having(entityPrimaryKeyInSet(1));

			assertThrows(
				IllegalArgumentException.class,
				() -> original.getCopyWithNewChildren(
					new FilterConstraint[]{entityPrimaryKeyInSet(2)},
					new Constraint<?>[]{entityPrimaryKeyInSet(3)}
				)
			);
		}

		@Test
		@DisplayName("should produce correct toString")
		void shouldToStringReturnExpectedFormat() {
			final HierarchyHaving having = having(attributeEquals("code", "a"));

			assertEquals("having(attributeEquals('code','a'))", having.toString());
		}
	}

	@Nested
	@DisplayName("Equals and hashCode contract")
	class EqualsAndHashCodeTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(having(attributeEquals("code", "a")), having(attributeEquals("code", "a")));
			assertEquals(having(attributeEquals("code", "a")), having(attributeEquals("code", "a")));
			assertNotEquals(having(attributeEquals("code", "a")), having(entityPrimaryKeyInSet(1)));
			assertEquals(
				having(attributeEquals("code", "a")).hashCode(),
				having(attributeEquals("code", "a")).hashCode()
			);
			assertNotEquals(
				having(attributeEquals("code", "a")).hashCode(),
				having(entityPrimaryKeyInSet(1)).hashCode()
			);
		}
	}
}
