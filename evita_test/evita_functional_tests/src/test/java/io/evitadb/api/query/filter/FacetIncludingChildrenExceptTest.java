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
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.includingChildrenExcept;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FacetIncludingChildrenExcept} verifying construction, applicability, necessity,
 * child management, cloning, visitor support, string representation, and equality contract.
 *
 * @author evitaDB
 */
@DisplayName("FacetIncludingChildrenExcept constraint")
class FacetIncludingChildrenExceptTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should create no-arg variant via direct constructor")
		void shouldCreateNoArgVariantViaDirectConstructor() {
			final FacetIncludingChildrenExcept constraint = new FacetIncludingChildrenExcept();

			assertNotNull(constraint);
			assertEquals(0, constraint.getChildren().length);
		}

		@Test
		@DisplayName("should create variant with child via factory")
		void shouldCreateVariantWithChildViaFactory() {
			final FacetIncludingChildrenExcept constraint = includingChildrenExcept(
				entityPrimaryKeyInSet(1, 5, 7)
			);

			assertEquals(1, constraint.getChildren().length);
			assertEquals(entityPrimaryKeyInSet(1, 5, 7), constraint.getChildren()[0]);
		}

		@Test
		@DisplayName("should create no-arg variant when null child passed to factory")
		void shouldCreateNoArgVariantWhenNullChildPassedToFactory() {
			final FacetIncludingChildrenExcept constraint = includingChildrenExcept(null);

			assertNotNull(constraint);
			assertEquals(0, constraint.getChildren().length);
		}
	}

	@Nested
	@DisplayName("Property accessors")
	class PropertyAccessorTest {

		@Test
		@DisplayName("should return null child for no-arg variant")
		void shouldReturnNullChildForNoArgVariant() {
			final FacetIncludingChildrenExcept constraint = new FacetIncludingChildrenExcept();

			assertNull(constraint.getChild());
		}

		@Test
		@DisplayName("should return child for variant with child")
		void shouldReturnChildForVariantWithChild() {
			final FilterConstraint child = entityPrimaryKeyInSet(1, 5, 7);
			final FacetIncludingChildrenExcept constraint = includingChildrenExcept(child);

			assertEquals(child, constraint.getChild());
		}
	}

	@Nested
	@DisplayName("Applicability and necessity")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable for no-arg variant")
		void shouldBeApplicableForNoArgVariant() {
			assertTrue(new FacetIncludingChildrenExcept().isApplicable());
		}

		@Test
		@DisplayName("should be applicable for variant with single child")
		void shouldBeApplicableForVariantWithSingleChild() {
			assertTrue(includingChildrenExcept(entityPrimaryKeyInSet(1)).isApplicable());
		}

		@Test
		@DisplayName("should be applicable for variant with complex child")
		void shouldBeApplicableForVariantWithComplexChild() {
			assertTrue(includingChildrenExcept(entityPrimaryKeyInSet(1, 5, 7)).isApplicable());
		}

		@Test
		@DisplayName("should always be necessary")
		void shouldAlwaysBeNecessary() {
			assertTrue(new FacetIncludingChildrenExcept().isNecessary());
			assertTrue(includingChildrenExcept(entityPrimaryKeyInSet(1)).isNecessary());
		}
	}

	@Nested
	@DisplayName("Copy with new children")
	class CopyWithNewChildrenTest {

		@Test
		@DisplayName("should create no-arg variant when zero children provided")
		void shouldCreateNoArgVariantWhenZeroChildrenProvided() {
			final FacetIncludingChildrenExcept original = includingChildrenExcept(
				entityPrimaryKeyInSet(1)
			);

			final FilterConstraint copy = original.getCopyWithNewChildren(
				new FilterConstraint[0], new Constraint<?>[0]
			);

			assertInstanceOf(FacetIncludingChildrenExcept.class, copy);
			assertNull(((FacetIncludingChildrenExcept) copy).getChild());
		}

		@Test
		@DisplayName("should create variant with child when one child provided")
		void shouldCreateVariantWithChildWhenOneChildProvided() {
			final FacetIncludingChildrenExcept original = new FacetIncludingChildrenExcept();
			final FilterConstraint newChild = entityPrimaryKeyInSet(2, 3);

			final FilterConstraint copy = original.getCopyWithNewChildren(
				new FilterConstraint[]{newChild}, new Constraint<?>[0]
			);

			assertInstanceOf(FacetIncludingChildrenExcept.class, copy);
			assertEquals(newChild, ((FacetIncludingChildrenExcept) copy).getChild());
		}

		@Test
		@DisplayName("should throw when more than one child provided")
		void shouldThrowWhenMoreThanOneChildProvided() {
			final FacetIncludingChildrenExcept original = new FacetIncludingChildrenExcept();

			assertThrows(
				GenericEvitaInternalError.class,
				() -> original.getCopyWithNewChildren(
					new FilterConstraint[]{entityPrimaryKeyInSet(1), entityPrimaryKeyInSet(2)},
					new Constraint<?>[0]
				)
			);
		}

		@Test
		@DisplayName("should throw when additional children provided")
		void shouldThrowWhenAdditionalChildrenProvided() {
			final FacetIncludingChildrenExcept original = new FacetIncludingChildrenExcept();

			assertThrows(
				GenericEvitaInternalError.class,
				() -> original.getCopyWithNewChildren(
					new FilterConstraint[0],
					new Constraint<?>[]{attributeEquals("x", "y")}
				)
			);
		}
	}

	@Nested
	@DisplayName("Clone with arguments")
	class CloneWithArgumentsTest {

		@Test
		@DisplayName("should throw UnsupportedOperationException since constraint has no arguments")
		void shouldThrowUnsupportedOperationException() {
			final FacetIncludingChildrenExcept constraint = new FacetIncludingChildrenExcept();

			assertThrows(
				UnsupportedOperationException.class,
				() -> constraint.cloneWithArguments(new Serializable[]{"test"})
			);
		}
	}

	@Nested
	@DisplayName("Type and visitor")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return FilterConstraint type")
		void shouldReturnFilterConstraintType() {
			assertEquals(FilterConstraint.class, new FacetIncludingChildrenExcept().getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final FacetIncludingChildrenExcept constraint = includingChildrenExcept(
				entityPrimaryKeyInSet(1)
			);
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();

			constraint.accept(c -> visited.set((Constraint<?>) c));

			assertSame(constraint, visited.get());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should format no-arg variant")
		void shouldFormatNoArgVariant() {
			assertEquals("includingChildrenExcept()", new FacetIncludingChildrenExcept().toString());
		}

		@Test
		@DisplayName("should format variant with child")
		void shouldFormatVariantWithChild() {
			assertEquals(
				"includingChildrenExcept(entityPrimaryKeyInSet(1,5,7))",
				includingChildrenExcept(entityPrimaryKeyInSet(1, 5, 7)).toString()
			);
		}
	}

	@Nested
	@DisplayName("Equals and hashCode")
	class EqualsAndHashCodeTest {

		@Test
		@DisplayName("should not equal different variants")
		void shouldNotEqualDifferentVariants() {
			assertNotSame(
				new FacetIncludingChildrenExcept(),
				includingChildrenExcept(entityPrimaryKeyInSet(1, 1, 5))
			);
			assertNotEquals(
				new FacetIncludingChildrenExcept(),
				includingChildrenExcept(entityPrimaryKeyInSet(1, 1, 5))
			);
		}

		@Test
		@DisplayName("should equal identical constraints with children")
		void shouldEqualIdenticalConstraintsWithChildren() {
			assertNotSame(
				includingChildrenExcept(entityPrimaryKeyInSet(1, 1, 5)),
				includingChildrenExcept(entityPrimaryKeyInSet(1, 1, 5))
			);
			assertEquals(
				includingChildrenExcept(entityPrimaryKeyInSet(1, 1, 5)),
				includingChildrenExcept(entityPrimaryKeyInSet(1, 1, 5))
			);
		}

		@Test
		@DisplayName("should not equal constraints with different children")
		void shouldNotEqualConstraintsWithDifferentChildren() {
			assertNotEquals(
				includingChildrenExcept(entityPrimaryKeyInSet(1, 1, 5)),
				includingChildrenExcept(entityPrimaryKeyInSet(1, 1, 6))
			);
			assertNotEquals(
				includingChildrenExcept(entityPrimaryKeyInSet(1, 1, 5)),
				includingChildrenExcept(entityPrimaryKeyInSet(1, 1))
			);
			assertNotEquals(
				includingChildrenExcept(entityPrimaryKeyInSet(1, 1, 5)),
				includingChildrenExcept(entityPrimaryKeyInSet(2, 1, 5))
			);
		}

		@Test
		@DisplayName("should have same hashCode for equal constraints")
		void shouldHaveSameHashCodeForEqualConstraints() {
			assertEquals(
				includingChildrenExcept(entityPrimaryKeyInSet(1, 1, 5)).hashCode(),
				includingChildrenExcept(entityPrimaryKeyInSet(1, 1, 5)).hashCode()
			);
		}

		@Test
		@DisplayName("should have different hashCode for different constraints")
		void shouldHaveDifferentHashCodeForDifferentConstraints() {
			assertNotEquals(
				includingChildrenExcept(entityPrimaryKeyInSet(1, 1, 5)).hashCode(),
				includingChildrenExcept(entityPrimaryKeyInSet(1, 1, 6)).hashCode()
			);
			assertNotEquals(
				includingChildrenExcept(entityPrimaryKeyInSet(1, 1, 5)).hashCode(),
				includingChildrenExcept(entityPrimaryKeyInSet(1, 1)).hashCode()
			);
		}
	}
}
