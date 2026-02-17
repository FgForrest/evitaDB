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

import static io.evitadb.api.query.QueryConstraints.directRelation;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HierarchyDirectRelation} verifying construction, applicability, cloning,
 * type resolution, visitor acceptance, and equality contract.
 *
 * @author evitaDB
 */
@DisplayName("HierarchyDirectRelation constraint")
class HierarchyDirectRelationTest {

	@Nested
	@DisplayName("Construction and factory methods")
	class ConstructionTest {

		@Test
		@DisplayName("should create via no-arg constructor")
		void shouldCreateViaNoArgConstructor() {
			final HierarchyDirectRelation constraint = new HierarchyDirectRelation();

			assertNotNull(constraint);
			assertArrayEquals(new Serializable[0], constraint.getArguments());
		}

		@Test
		@DisplayName("should create via factory method")
		void shouldCreateViaFactoryMethod() {
			final HierarchyDirectRelation constraint = directRelation();

			assertNotNull(constraint);
			assertArrayEquals(new Serializable[0], constraint.getArguments());
		}
	}

	@Nested
	@DisplayName("Core operations")
	class CoreOperationsTest {

		@Test
		@DisplayName("should always be applicable")
		void shouldAlwaysBeApplicable() {
			final HierarchyDirectRelation constraint = new HierarchyDirectRelation();

			assertTrue(constraint.isApplicable());
		}

		@Test
		@DisplayName("should return FilterConstraint type")
		void shouldReturnFilterConstraintType() {
			final HierarchyDirectRelation constraint = new HierarchyDirectRelation();

			assertEquals(FilterConstraint.class, constraint.getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final HierarchyDirectRelation constraint = new HierarchyDirectRelation();
			final AtomicReference<Constraint<?>> captured = new AtomicReference<>();

			constraint.accept(captured::set);

			assertSame(constraint, captured.get());
		}

		@Test
		@DisplayName("should create new instance via cloneWithArguments")
		void shouldCreateNewInstanceViaCloneWithArguments() {
			final HierarchyDirectRelation original = new HierarchyDirectRelation();

			final FilterConstraint cloned = original.cloneWithArguments(new Serializable[0]);

			assertNotSame(original, cloned);
			assertInstanceOf(HierarchyDirectRelation.class, cloned);
			assertEquals(original, cloned);
		}

		@Test
		@DisplayName("should produce correct toString")
		void shouldProduceCorrectToString() {
			final HierarchyDirectRelation constraint = directRelation();

			assertEquals("directRelation()", constraint.toString());
		}
	}

	@Nested
	@DisplayName("Equals and hashCode contract")
	class EqualsAndHashCodeTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(directRelation(), directRelation());
			assertEquals(directRelation(), directRelation());
			assertEquals(directRelation().hashCode(), directRelation().hashCode());
		}
	}
}
