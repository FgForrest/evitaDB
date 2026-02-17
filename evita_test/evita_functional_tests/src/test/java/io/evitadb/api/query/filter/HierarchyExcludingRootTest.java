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

import static io.evitadb.api.query.QueryConstraints.excludingRoot;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HierarchyExcludingRoot} verifying construction, applicability, cloning,
 * type resolution, visitor acceptance, and equality contract.
 *
 * @author evitaDB
 */
@DisplayName("HierarchyExcludingRoot constraint")
class HierarchyExcludingRootTest {

	@Nested
	@DisplayName("Construction and factory methods")
	class ConstructionTest {

		@Test
		@DisplayName("should create via no-arg constructor")
		void shouldCreateViaNoArgConstructor() {
			final HierarchyExcludingRoot constraint = new HierarchyExcludingRoot();

			assertNotNull(constraint);
			assertArrayEquals(new Serializable[0], constraint.getArguments());
		}

		@Test
		@DisplayName("should create via factory method")
		void shouldCreateViaFactoryMethod() {
			final HierarchyExcludingRoot constraint = excludingRoot();

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
			final HierarchyExcludingRoot constraint = new HierarchyExcludingRoot();

			assertTrue(constraint.isApplicable());
		}

		@Test
		@DisplayName("should return FilterConstraint type")
		void shouldReturnFilterConstraintType() {
			final HierarchyExcludingRoot constraint = new HierarchyExcludingRoot();

			assertEquals(FilterConstraint.class, constraint.getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final HierarchyExcludingRoot constraint = new HierarchyExcludingRoot();
			final AtomicReference<Constraint<?>> captured = new AtomicReference<>();

			constraint.accept(captured::set);

			assertSame(constraint, captured.get());
		}

		@Test
		@DisplayName("should create new instance via cloneWithArguments")
		void shouldCreateNewInstanceViaCloneWithArguments() {
			final HierarchyExcludingRoot original = new HierarchyExcludingRoot();

			final FilterConstraint cloned = original.cloneWithArguments(new Serializable[0]);

			assertNotSame(original, cloned);
			assertInstanceOf(HierarchyExcludingRoot.class, cloned);
			assertEquals(original, cloned);
		}

		@Test
		@DisplayName("should produce correct toString")
		void shouldProduceCorrectToString() {
			final HierarchyExcludingRoot constraint = excludingRoot();

			assertEquals("excludingRoot()", constraint.toString());
		}
	}

	@Nested
	@DisplayName("Equals and hashCode contract")
	class EqualsAndHashCodeTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(excludingRoot(), excludingRoot());
			assertEquals(excludingRoot(), excludingRoot());
			assertEquals(excludingRoot().hashCode(), excludingRoot().hashCode());
		}
	}
}
