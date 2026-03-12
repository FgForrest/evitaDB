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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.includingChildren;
import static io.evitadb.api.query.QueryConstraints.includingChildrenHaving;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FacetIncludingChildren} verifying construction, applicability, necessity, suffix behavior,
 * child management, cloning, visitor support, string representation, and equality contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("FacetIncludingChildren constraint")
class FacetIncludingChildrenTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should create no-arg variant via factory")
		void shouldCreateNoArgVariantViaFactory() {
			final FacetIncludingChildren constraint = includingChildren();

			assertNotNull(constraint);
			assertEquals(0, constraint.getChildren().length);
		}

		@Test
		@DisplayName("should create having variant via factory with child")
		void shouldCreateHavingVariantViaFactory() {
			final FacetIncludingChildren constraint = includingChildrenHaving(
				entityPrimaryKeyInSet(1, 5, 7)
			);

			assertEquals(1, constraint.getChildren().length);
			assertEquals(entityPrimaryKeyInSet(1, 5, 7), constraint.getChildren()[0]);
		}

		@Test
		@DisplayName("should create no-arg variant when null child passed to having factory")
		void shouldCreateNoArgVariantWhenNullChildPassedToHavingFactory() {
			final FacetIncludingChildren constraint = includingChildrenHaving(null);

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
			final FacetIncludingChildren constraint = includingChildren();

			assertNull(constraint.getChild());
		}

		@Test
		@DisplayName("should return child for having variant")
		void shouldReturnChildForHavingVariant() {
			final FilterConstraint child = entityPrimaryKeyInSet(1, 5, 7);
			final FacetIncludingChildren constraint = includingChildrenHaving(child);

			assertEquals(child, constraint.getChild());
		}
	}

	@Nested
	@DisplayName("Applicability and necessity")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable for no-arg variant")
		void shouldBeApplicableForNoArgVariant() {
			assertTrue(new FacetIncludingChildren().isApplicable());
		}

		@Test
		@DisplayName("should be applicable for having variant with single child")
		void shouldBeApplicableForHavingVariantWithSingleChild() {
			assertTrue(includingChildrenHaving(entityPrimaryKeyInSet(1)).isApplicable());
		}

		@Test
		@DisplayName("should be applicable for having variant with complex child")
		void shouldBeApplicableForHavingVariantWithComplexChild() {
			assertTrue(includingChildrenHaving(entityPrimaryKeyInSet(1, 5, 7)).isApplicable());
		}

		@Test
		@DisplayName("should always be necessary")
		void shouldAlwaysBeNecessary() {
			assertTrue(includingChildren().isNecessary());
			assertTrue(includingChildrenHaving(entityPrimaryKeyInSet(1)).isNecessary());
		}
	}

	@Nested
	@DisplayName("Suffix behavior")
	class SuffixTest {

		@Test
		@DisplayName("should return empty suffix for no-arg variant")
		void shouldReturnEmptySuffixForNoArgVariant() {
			final FacetIncludingChildren constraint = includingChildren();

			assertEquals(Optional.empty(), constraint.getSuffixIfApplied());
		}

		@Test
		@DisplayName("should return 'having' suffix for variant with child")
		void shouldReturnHavingSuffixForVariantWithChild() {
			final FacetIncludingChildren constraint = includingChildrenHaving(entityPrimaryKeyInSet(1));

			assertEquals(Optional.of("having"), constraint.getSuffixIfApplied());
		}
	}

	@Nested
	@DisplayName("Copy with new children")
	class CopyWithNewChildrenTest {

		@Test
		@DisplayName("should create no-arg variant when zero children provided")
		void shouldCreateNoArgVariantWhenZeroChildrenProvided() {
			final FacetIncludingChildren original = includingChildrenHaving(entityPrimaryKeyInSet(1));

			final FilterConstraint copy = original.getCopyWithNewChildren(
				new FilterConstraint[0], new Constraint<?>[0]
			);

			assertInstanceOf(FacetIncludingChildren.class, copy);
			assertNull(((FacetIncludingChildren) copy).getChild());
		}

		@Test
		@DisplayName("should create having variant when one child provided")
		void shouldCreateHavingVariantWhenOneChildProvided() {
			final FacetIncludingChildren original = includingChildren();
			final FilterConstraint newChild = entityPrimaryKeyInSet(2, 3);

			final FilterConstraint copy = original.getCopyWithNewChildren(
				new FilterConstraint[]{newChild}, new Constraint<?>[0]
			);

			assertInstanceOf(FacetIncludingChildren.class, copy);
			assertEquals(newChild, ((FacetIncludingChildren) copy).getChild());
		}

		@Test
		@DisplayName("should throw when more than one child provided")
		void shouldThrowWhenMoreThanOneChildProvided() {
			final FacetIncludingChildren original = includingChildren();

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
			final FacetIncludingChildren original = includingChildren();

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
			final FacetIncludingChildren constraint = includingChildren();

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
			assertEquals(FilterConstraint.class, includingChildren().getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final FacetIncludingChildren constraint = includingChildrenHaving(entityPrimaryKeyInSet(1));
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
			assertEquals("includingChildren()", includingChildren().toString());
		}

		@Test
		@DisplayName("should format having variant with child")
		void shouldFormatHavingVariantWithChild() {
			assertEquals(
				"includingChildrenHaving(entityPrimaryKeyInSet(1,5,7))",
				includingChildrenHaving(entityPrimaryKeyInSet(1, 5, 7)).toString()
			);
		}
	}

	@Nested
	@DisplayName("Equals and hashCode")
	class EqualsAndHashCodeTest {

		@Test
		@DisplayName("should not equal different variants")
		void shouldNotEqualDifferentVariants() {
			assertNotSame(includingChildren(), includingChildrenHaving(entityPrimaryKeyInSet(1, 1, 5)));
			assertNotEquals(includingChildren(), includingChildrenHaving(entityPrimaryKeyInSet(1, 1, 5)));
		}

		@Test
		@DisplayName("should equal identical having constraints")
		void shouldEqualIdenticalHavingConstraints() {
			assertNotSame(
				includingChildrenHaving(entityPrimaryKeyInSet(1, 1, 5)),
				includingChildrenHaving(entityPrimaryKeyInSet(1, 1, 5))
			);
			assertEquals(
				includingChildrenHaving(entityPrimaryKeyInSet(1, 1, 5)),
				includingChildrenHaving(entityPrimaryKeyInSet(1, 1, 5))
			);
		}

		@Test
		@DisplayName("should not equal constraints with different children")
		void shouldNotEqualConstraintsWithDifferentChildren() {
			assertNotEquals(
				includingChildrenHaving(entityPrimaryKeyInSet(1, 1, 5)),
				includingChildrenHaving(entityPrimaryKeyInSet(1, 1, 6))
			);
			assertNotEquals(
				includingChildrenHaving(entityPrimaryKeyInSet(1, 1, 5)),
				includingChildrenHaving(entityPrimaryKeyInSet(1, 1))
			);
			assertNotEquals(
				includingChildrenHaving(entityPrimaryKeyInSet(1, 1, 5)),
				includingChildrenHaving(entityPrimaryKeyInSet(2, 1, 5))
			);
		}

		@Test
		@DisplayName("should have same hashCode for equal constraints")
		void shouldHaveSameHashCodeForEqualConstraints() {
			assertEquals(
				includingChildrenHaving(entityPrimaryKeyInSet(1, 1, 5)).hashCode(),
				includingChildrenHaving(entityPrimaryKeyInSet(1, 1, 5)).hashCode()
			);
		}

		@Test
		@DisplayName("should have different hashCode for different constraints")
		void shouldHaveDifferentHashCodeForDifferentConstraints() {
			assertNotEquals(
				includingChildrenHaving(entityPrimaryKeyInSet(1, 1, 5)).hashCode(),
				includingChildrenHaving(entityPrimaryKeyInSet(1, 1, 6)).hashCode()
			);
			assertNotEquals(
				includingChildrenHaving(entityPrimaryKeyInSet(1, 1, 5)).hashCode(),
				includingChildrenHaving(entityPrimaryKeyInSet(1, 1)).hashCode()
			);
		}
	}
}
