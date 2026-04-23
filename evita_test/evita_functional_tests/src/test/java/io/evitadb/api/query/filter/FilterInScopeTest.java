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
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.entityLocaleEquals;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.inScope;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FilterInScope} verifying construction, applicability, necessity, property accessors,
 * child management, cloning, visitor support, string representation, and equality contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("FilterInScope constraint")
class FilterInScopeTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should create via factory with LIVE scope and single child")
		void shouldCreateViaFactoryWithLiveScopeAndSingleChild() {
			final FilterInScope constraint = inScope(Scope.LIVE, entityPrimaryKeyInSet(1));

			assertEquals(Scope.LIVE, constraint.getScope());
			assertArrayEquals(
				new FilterConstraint[]{entityPrimaryKeyInSet(1)},
				constraint.getFiltering()
			);
		}

		@Test
		@DisplayName("should create via factory with ARCHIVED scope")
		void shouldCreateViaFactoryWithArchivedScope() {
			final FilterInScope constraint = inScope(Scope.ARCHIVED, entityPrimaryKeyInSet(1));

			assertEquals(Scope.ARCHIVED, constraint.getScope());
		}

		@Test
		@DisplayName("should create via factory with multiple children")
		void shouldCreateViaFactoryWithMultipleChildren() {
			final FilterInScope constraint = inScope(
				Scope.ARCHIVED,
				entityLocaleEquals(Locale.ENGLISH),
				entityPrimaryKeyInSet(1)
			);

			assertEquals(2, constraint.getFiltering().length);
		}

		@Test
		@DisplayName("should throw when null scope provided")
		void shouldThrowWhenNullScopeProvided() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new FilterInScope(null)
			);
		}

		@Test
		@DisplayName("should throw when scope provided without children")
		void shouldThrowWhenScopeProvidedWithoutChildren() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new FilterInScope(Scope.LIVE)
			);
		}

		@Test
		@DisplayName("should return null from factory when scope is null")
		void shouldReturnNullFromFactoryWhenScopeIsNull() {
			final FilterInScope result = inScope(null, entityPrimaryKeyInSet(1));

			assertNull(result);
		}

		@Test
		@DisplayName("should return null from factory when children are null")
		void shouldReturnNullFromFactoryWhenChildrenAreNull() {
			final FilterInScope result = inScope(Scope.LIVE, (FilterConstraint[]) null);

			assertNull(result);
		}
	}

	@Nested
	@DisplayName("Property accessors")
	class PropertyAccessorTest {

		@Test
		@DisplayName("should return scope")
		void shouldReturnScope() {
			final FilterInScope constraint = inScope(Scope.LIVE, entityPrimaryKeyInSet(1));

			assertEquals(Scope.LIVE, constraint.getScope());
		}

		@Test
		@DisplayName("should return filtering children")
		void shouldReturnFilteringChildren() {
			final FilterConstraint child1 = entityLocaleEquals(Locale.ENGLISH);
			final FilterConstraint child2 = entityPrimaryKeyInSet(1);
			final FilterInScope constraint = inScope(Scope.LIVE, child1, child2);

			final FilterConstraint[] filtering = constraint.getFiltering();
			assertEquals(2, filtering.length);
			assertEquals(child1, filtering[0]);
			assertEquals(child2, filtering[1]);
		}
	}

	@Nested
	@DisplayName("Applicability and necessity")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable with scope and children")
		void shouldBeApplicableWithScopeAndChildren() {
			assertTrue(inScope(Scope.LIVE, entityPrimaryKeyInSet(1)).isApplicable());
		}

		@Test
		@DisplayName("should be applicable with multiple children")
		void shouldBeApplicableWithMultipleChildren() {
			assertTrue(
				inScope(
					Scope.ARCHIVED,
					entityLocaleEquals(Locale.ENGLISH),
					entityPrimaryKeyInSet(1)
				).isApplicable()
			);
		}

		@Test
		@DisplayName("should be necessary with scope and children")
		void shouldBeNecessaryWithScopeAndChildren() {
			assertTrue(inScope(Scope.LIVE, entityPrimaryKeyInSet(1)).isNecessary());
		}
	}

	@Nested
	@DisplayName("Copy with new children")
	class CopyWithNewChildrenTest {

		@Test
		@DisplayName("should create copy with new children preserving scope")
		void shouldCreateCopyWithNewChildrenPreservingScope() {
			final FilterInScope original = inScope(Scope.LIVE, entityPrimaryKeyInSet(1));
			final FilterConstraint newChild = entityLocaleEquals(Locale.ENGLISH);

			final FilterConstraint copy = original.getCopyWithNewChildren(
				new FilterConstraint[]{newChild}, new Constraint<?>[0]
			);

			assertInstanceOf(FilterInScope.class, copy);
			assertEquals(Scope.LIVE, ((FilterInScope) copy).getScope());
			assertEquals(1, ((FilterInScope) copy).getFiltering().length);
			assertEquals(newChild, ((FilterInScope) copy).getFiltering()[0]);
		}

		@Test
		@DisplayName("should create copy with empty children")
		void shouldCreateCopyWithEmptyChildren() {
			final FilterInScope original = inScope(Scope.LIVE, entityPrimaryKeyInSet(1));

			final FilterConstraint copy = original.getCopyWithNewChildren(
				new FilterConstraint[0], new Constraint<?>[0]
			);

			assertInstanceOf(FilterInScope.class, copy);
			assertEquals(Scope.LIVE, ((FilterInScope) copy).getScope());
			assertEquals(0, ((FilterInScope) copy).getFiltering().length);
		}

		@Test
		@DisplayName("should throw when additional children provided")
		void shouldThrowWhenAdditionalChildrenProvided() {
			final FilterInScope original = inScope(Scope.LIVE, entityPrimaryKeyInSet(1));

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> original.getCopyWithNewChildren(
					new FilterConstraint[]{entityPrimaryKeyInSet(1)},
					new Constraint<?>[]{entityPrimaryKeyInSet(2)}
				)
			);
		}
	}

	@Nested
	@DisplayName("Clone with arguments")
	class CloneWithArgumentsTest {

		@Test
		@DisplayName("should create new instance with different scope preserving children")
		void shouldCreateNewInstanceWithDifferentScopePreservingChildren() {
			final FilterInScope original = inScope(Scope.LIVE, entityPrimaryKeyInSet(1));

			final FilterConstraint cloned = original.cloneWithArguments(
				new Serializable[]{Scope.ARCHIVED}
			);

			assertNotSame(original, cloned);
			assertInstanceOf(FilterInScope.class, cloned);
			assertEquals(Scope.ARCHIVED, ((FilterInScope) cloned).getScope());
			assertArrayEquals(original.getFiltering(), ((FilterInScope) cloned).getFiltering());
		}

		@Test
		@DisplayName("should throw when wrong argument type provided")
		void shouldThrowWhenWrongArgumentTypeProvided() {
			final FilterInScope original = inScope(Scope.LIVE, entityPrimaryKeyInSet(1));

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> original.cloneWithArguments(new Serializable[]{"notAScope"})
			);
		}

		@Test
		@DisplayName("should throw when multiple arguments provided")
		void shouldThrowWhenMultipleArgumentsProvided() {
			final FilterInScope original = inScope(Scope.LIVE, entityPrimaryKeyInSet(1));

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> original.cloneWithArguments(new Serializable[]{Scope.LIVE, Scope.ARCHIVED})
			);
		}

		@Test
		@DisplayName("should throw when no arguments provided")
		void shouldThrowWhenNoArgumentsProvided() {
			final FilterInScope original = inScope(Scope.LIVE, entityPrimaryKeyInSet(1));

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> original.cloneWithArguments(new Serializable[0])
			);
		}
	}

	@Nested
	@DisplayName("Type and visitor")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return FilterConstraint type")
		void shouldReturnFilterConstraintType() {
			assertEquals(
				FilterConstraint.class,
				inScope(Scope.LIVE, entityPrimaryKeyInSet(1)).getType()
			);
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final FilterInScope constraint = inScope(Scope.LIVE, entityPrimaryKeyInSet(1));
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();

			constraint.accept(c -> visited.set((Constraint<?>) c));

			assertSame(constraint, visited.get());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should format with LIVE scope and single child")
		void shouldFormatWithLiveScopeAndSingleChild() {
			assertEquals(
				"inScope(LIVE,entityPrimaryKeyInSet(1))",
				inScope(Scope.LIVE, entityPrimaryKeyInSet(1)).toString()
			);
		}

		@Test
		@DisplayName("should format with ARCHIVED scope and multiple children")
		void shouldFormatWithArchivedScopeAndMultipleChildren() {
			assertEquals(
				"inScope(ARCHIVED,entityLocaleEquals('en'),entityPrimaryKeyInSet(1))",
				inScope(Scope.ARCHIVED, entityLocaleEquals(Locale.ENGLISH), entityPrimaryKeyInSet(1)).toString()
			);
		}
	}

	@Nested
	@DisplayName("Equals and hashCode")
	class EqualsAndHashCodeTest {

		@Test
		@DisplayName("should equal identical constraint")
		void shouldEqualIdenticalConstraint() {
			assertNotSame(
				inScope(Scope.LIVE, entityPrimaryKeyInSet(1)),
				inScope(Scope.LIVE, entityPrimaryKeyInSet(1))
			);
			assertEquals(
				inScope(Scope.LIVE, entityPrimaryKeyInSet(1)),
				inScope(Scope.LIVE, entityPrimaryKeyInSet(1))
			);
		}

		@Test
		@DisplayName("should not equal constraint with different scope")
		void shouldNotEqualConstraintWithDifferentScope() {
			assertNotEquals(
				inScope(Scope.LIVE, entityPrimaryKeyInSet(1)),
				inScope(Scope.ARCHIVED, entityPrimaryKeyInSet(1))
			);
		}

		@Test
		@DisplayName("should not equal constraint with different children")
		void shouldNotEqualConstraintWithDifferentChildren() {
			assertNotEquals(
				inScope(Scope.LIVE, entityPrimaryKeyInSet(1)),
				inScope(Scope.LIVE, entityLocaleEquals(Locale.ENGLISH))
			);
		}

		@Test
		@DisplayName("should have same hashCode for equal constraints")
		void shouldHaveSameHashCodeForEqualConstraints() {
			assertEquals(
				inScope(Scope.LIVE, entityPrimaryKeyInSet(1)).hashCode(),
				inScope(Scope.LIVE, entityPrimaryKeyInSet(1)).hashCode()
			);
		}

		@Test
		@DisplayName("should have different hashCode for different scope")
		void shouldHaveDifferentHashCodeForDifferentScope() {
			assertNotEquals(
				inScope(Scope.LIVE, entityPrimaryKeyInSet(1)).hashCode(),
				inScope(Scope.ARCHIVED, entityPrimaryKeyInSet(1)).hashCode()
			);
		}

		@Test
		@DisplayName("should have different hashCode for different children")
		void shouldHaveDifferentHashCodeForDifferentChildren() {
			assertNotEquals(
				inScope(Scope.LIVE, entityPrimaryKeyInSet(1)).hashCode(),
				inScope(Scope.LIVE, entityLocaleEquals(Locale.ENGLISH)).hashCode()
			);
		}
	}
}
