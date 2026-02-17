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

package io.evitadb.api.query.require;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.level;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HierarchyLevel} verifying construction, applicability, visitor acceptance,
 * string representation, equality contract, and cloning behavior.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("HierarchyLevel constraint")
class HierarchyLevelTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should create via factory method with level value")
		void shouldCreateViaFactoryClassWorkAsExpected() {
			final HierarchyLevel hierarchyLevel = level(1);

			assertEquals(1, hierarchyLevel.getLevel());
		}

		@Test
		@DisplayName("should fail to create with negative level")
		void shouldFailToCreateLevelWithNegativeNumber() {
			assertThrows(EvitaInvalidUsageException.class, () -> level(-1));
		}

		@Test
		@DisplayName("should fail to create with zero level")
		void shouldFailToCreateLevelWithZero() {
			assertThrows(EvitaInvalidUsageException.class, () -> level(0));
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable when level is provided")
		void shouldRecognizeApplicability() {
			assertTrue(new HierarchyLevel(1).isApplicable());
		}
	}

	@Nested
	@DisplayName("Visitor support")
	class VisitorSupportTest {

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final HierarchyLevel constraint = level(1);
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();

			constraint.accept(c -> visited.set(c));

			assertSame(constraint, visited.get());
		}

		@Test
		@DisplayName("should return RequireConstraint type")
		void shouldReturnRequireConstraintType() {
			assertEquals(RequireConstraint.class, level(1).getType());
		}
	}

	@Nested
	@DisplayName("String representation")
	class StringRepresentationTest {

		@Test
		@DisplayName("should produce correct toString output")
		void shouldToStringReturnExpectedFormat() {
			final HierarchyLevel hierarchyLevel = level(1);
			assertEquals("level(1)", hierarchyLevel.toString());

			final HierarchyLevel hierarchyLevel2 = level(12);
			assertEquals("level(12)", hierarchyLevel2.toString());
		}
	}

	@Nested
	@DisplayName("Equality")
	class EqualityTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(level(1), level(1));
			assertEquals(level(1), level(1));
			assertNotEquals(level(2), level(1));
			assertEquals(level(1).hashCode(), level(1).hashCode());
			assertNotEquals(level(2).hashCode(), level(1).hashCode());
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should return new instance from cloneWithArguments")
		void shouldReturnNewInstanceFromCloneWithArguments() {
			final HierarchyLevel constraint = level(1);

			final RequireConstraint cloned = constraint.cloneWithArguments(new Serializable[]{1});

			assertNotSame(constraint, cloned);
			assertEquals(constraint, cloned);
		}
	}
}
