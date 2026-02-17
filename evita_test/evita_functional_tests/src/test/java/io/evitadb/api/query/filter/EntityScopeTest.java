/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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
import io.evitadb.dataType.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.scope;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EntityScope} verifying construction, applicability, property accessors,
 * cloning, visitor support, string representation, and equality contract.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@DisplayName("EntityScope constraint")
class EntityScopeTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should create with single scope via factory")
		void shouldCreateWithSingleScopeViaFactory() {
			assertEquals(EnumSet.of(Scope.LIVE), scope(Scope.LIVE).getScope());
			assertEquals(EnumSet.of(Scope.ARCHIVED), scope(Scope.ARCHIVED).getScope());
		}

		@Test
		@DisplayName("should create with multiple scopes via factory")
		void shouldCreateWithMultipleScopesViaFactory() {
			assertEquals(
				EnumSet.of(Scope.LIVE, Scope.ARCHIVED),
				scope(Scope.LIVE, Scope.ARCHIVED).getScope()
			);
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should not be applicable with no arguments")
		void shouldNotBeApplicableWithNoArguments() {
			assertFalse(new EntityScope().isApplicable());
		}

		@Test
		@DisplayName("should be applicable with single scope")
		void shouldBeApplicableWithSingleScope() {
			assertTrue(scope(Scope.LIVE).isApplicable());
			assertTrue(scope(Scope.ARCHIVED).isApplicable());
		}
	}

	@Nested
	@DisplayName("Clone with arguments")
	class CloneWithArgumentsTest {

		@Test
		@DisplayName("should clone with new scope arguments preserving constraint name")
		void shouldCloneWithNewScopeArgumentsPreservingConstraintName() {
			final EntityScope original = scope(Scope.LIVE);

			final FilterConstraint cloned = original.cloneWithArguments(
				new Serializable[]{Scope.ARCHIVED}
			);

			assertInstanceOf(EntityScope.class, cloned);
			assertNotSame(original, cloned);
			assertEquals(EnumSet.of(Scope.ARCHIVED), ((EntityScope) cloned).getScope());
			// the cloned constraint name should be "scope", not "entityScope"
			assertTrue(cloned.toString().startsWith("scope("),
				"Expected toString to start with 'scope(' but was: " + cloned.toString());
		}
	}

	@Nested
	@DisplayName("Type and visitor")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return FilterConstraint type")
		void shouldReturnFilterConstraintType() {
			assertEquals(FilterConstraint.class, scope(Scope.LIVE).getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final EntityScope constraint = scope(Scope.LIVE);
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();

			constraint.accept(c -> visited.set((Constraint<?>) c));

			assertSame(constraint, visited.get());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should format with single scope")
		void shouldFormatWithSingleScope() {
			assertEquals("scope(LIVE)", scope(Scope.LIVE).toString());
			assertEquals("scope(ARCHIVED)", scope(Scope.ARCHIVED).toString());
		}

		@Test
		@DisplayName("should format with multiple scopes")
		void shouldFormatWithMultipleScopes() {
			assertEquals("scope(LIVE,ARCHIVED)", scope(Scope.LIVE, Scope.ARCHIVED).toString());
		}
	}

	@Nested
	@DisplayName("Equals and hashCode")
	class EqualsAndHashCodeTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(scope(Scope.LIVE), scope(Scope.LIVE));
			assertEquals(scope(Scope.LIVE), scope(Scope.LIVE));
			assertNotEquals(scope(Scope.LIVE), scope(Scope.ARCHIVED));
			// order of scopes should not matter for equality
			assertEquals(scope(Scope.ARCHIVED, Scope.LIVE), scope(Scope.LIVE, Scope.ARCHIVED));
			assertNotEquals(scope(Scope.LIVE), scope(Scope.LIVE, Scope.ARCHIVED));
			assertEquals(scope(Scope.LIVE).hashCode(), scope(Scope.LIVE).hashCode());
			assertNotEquals(scope(Scope.LIVE).hashCode(), scope(Scope.ARCHIVED).hashCode());
			assertEquals(
				scope(Scope.ARCHIVED, Scope.LIVE).hashCode(),
				scope(Scope.LIVE, Scope.ARCHIVED).hashCode()
			);
			assertNotEquals(
				scope(Scope.LIVE).hashCode(),
				scope(Scope.LIVE, Scope.ARCHIVED).hashCode()
			);
		}
	}
}
