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

package io.evitadb.api.query.require;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.attributeHistogram;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.facetSummary;
import static io.evitadb.api.query.QueryConstraints.inScope;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RequireInScope} verifying construction, applicability, necessity, copy/clone operations,
 * visitor acceptance, and equality contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@DisplayName("RequireInScope constraint")
class RequireInScopeTest {

	@Nested
	@DisplayName("Construction and factory method")
	class ConstructionTest {

		@Test
		@DisplayName("should create via factory method with scope and children")
		void shouldCreateViaFactoryClassWorkAsExpected() {
			assertEquals(Scope.LIVE, inScope(Scope.LIVE, facetSummary()).getScope());
			assertEquals(Scope.ARCHIVED, inScope(Scope.ARCHIVED, facetSummary()).getScope());
			assertArrayEquals(
				new RequireConstraint[]{facetSummary()},
				inScope(Scope.LIVE, facetSummary()).getRequire()
			);
		}

		@Test
		@DisplayName("should throw exception when scope is null")
		void shouldThrowExceptionWhenScopeIsNull() {
			assertThrows(EvitaInvalidUsageException.class, () -> new RequireInScope(null));
		}

		@Test
		@DisplayName("should throw exception when no children provided")
		void shouldThrowExceptionWhenNoChildrenProvided() {
			assertThrows(EvitaInvalidUsageException.class, () -> new RequireInScope(Scope.LIVE));
		}
	}

	@Nested
	@DisplayName("Applicability and necessity")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable when scope and children are provided")
		void shouldRecognizeApplicability() {
			assertTrue(inScope(Scope.LIVE, facetSummary()).isApplicable());
			assertTrue(inScope(Scope.ARCHIVED, attributeHistogram(10, "width"), facetSummary()).isApplicable());
		}

		@Test
		@DisplayName("should be necessary when scope and children are provided")
		void shouldRecognizeNecessity() {
			assertTrue(inScope(Scope.LIVE, facetSummary()).isNecessary());
			assertTrue(inScope(Scope.ARCHIVED, attributeHistogram(10, "width"), facetSummary()).isNecessary());
		}
	}

	@Nested
	@DisplayName("Copy and clone operations")
	class CopyAndCloneTest {

		@Test
		@DisplayName("should create copy with new children preserving scope")
		void shouldCreateCopyWithNewChildren() {
			final RequireInScope original = inScope(Scope.LIVE, facetSummary());
			final RequireConstraint copy = original.getCopyWithNewChildren(
				new RequireConstraint[]{entityFetch()},
				new Constraint<?>[0]
			);

			assertInstanceOf(RequireInScope.class, copy);
			assertEquals(Scope.LIVE, ((RequireInScope) copy).getScope());
			assertEquals(1, ((RequireInScope) copy).getChildrenCount());
			assertInstanceOf(EntityFetch.class, ((RequireInScope) copy).getChildren()[0]);
		}

		@Test
		@DisplayName("should reject non-empty additional children")
		void shouldRejectNonEmptyAdditionalChildren() {
			final RequireInScope original = inScope(Scope.LIVE, facetSummary());

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> original.getCopyWithNewChildren(
					new RequireConstraint[]{entityFetch()},
					new Constraint<?>[]{facetSummary()}
				)
			);
		}

		@Test
		@DisplayName("should create new instance when cloning with different scope")
		void shouldCreateNewInstanceWhenCloningWithDifferentScope() {
			final RequireInScope original = inScope(Scope.LIVE, facetSummary());

			final RequireConstraint cloned = original.cloneWithArguments(new Serializable[]{Scope.ARCHIVED});

			assertNotSame(original, cloned);
			assertInstanceOf(RequireInScope.class, cloned);
			assertEquals(Scope.ARCHIVED, ((RequireInScope) cloned).getScope());
			assertArrayEquals(original.getRequire(), ((RequireInScope) cloned).getRequire());
		}

		@Test
		@DisplayName("should throw exception when cloning with invalid arguments")
		void shouldThrowWhenCloningWithInvalidArguments() {
			final RequireInScope requireInScope = inScope(Scope.LIVE, facetSummary());

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> requireInScope.cloneWithArguments(new Serializable[]{"invalid"})
			);
		}
	}

	@Nested
	@DisplayName("Type and visitor")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return RequireConstraint class as type")
		void shouldReturnRequireConstraintClassAsType() {
			final RequireInScope requireInScope = inScope(Scope.LIVE, facetSummary());

			assertEquals(RequireConstraint.class, requireInScope.getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final RequireInScope requireInScope = inScope(Scope.LIVE, facetSummary(), entityFetch());
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();
			requireInScope.accept(new ConstraintVisitor() {
				@Override
				public void visit(@Nonnull Constraint<?> constraint) {
					visited.set(constraint);
				}
			});

			assertSame(requireInScope, visited.get());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce expected toString format")
		void shouldToStringReturnExpectedFormat() {
			assertEquals("inScope(LIVE,facetSummary())", inScope(Scope.LIVE, facetSummary()).toString());
			assertEquals(
				"inScope(ARCHIVED,attributeHistogram(10,'width'),facetSummary())",
				inScope(Scope.ARCHIVED, attributeHistogram(10, "width"), facetSummary()).toString()
			);
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(inScope(Scope.LIVE, facetSummary()), inScope(Scope.LIVE, facetSummary()));
			assertEquals(inScope(Scope.LIVE, facetSummary()), inScope(Scope.LIVE, facetSummary()));
			assertNotEquals(inScope(Scope.LIVE, facetSummary()), inScope(Scope.ARCHIVED, facetSummary()));
			assertNotEquals(inScope(Scope.LIVE, facetSummary()), inScope(Scope.LIVE, attributeHistogram(10, "width")));
			assertEquals(
				inScope(Scope.LIVE, facetSummary()).hashCode(),
				inScope(Scope.LIVE, facetSummary()).hashCode()
			);
			assertNotEquals(
				inScope(Scope.LIVE, facetSummary()).hashCode(),
				inScope(Scope.ARCHIVED, facetSummary()).hashCode()
			);
			assertNotEquals(
				inScope(Scope.LIVE, facetSummary()).hashCode(),
				inScope(Scope.LIVE, attributeHistogram(10, "width")).hashCode()
			);
		}
	}

}