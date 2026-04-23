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

import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.facetHaving;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FacetHaving} verifying construction, applicability, necessity, property accessors,
 * child management, cloning, visitor support, string representation, and equality contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("FacetHaving constraint")
class FacetHavingTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should create via factory with reference name and children")
		void shouldCreateViaFactoryClassWorkAsExpected() {
			final FacetHaving facetHaving = facetHaving("brand", entityPrimaryKeyInSet(1, 5, 7));

			assertEquals("brand", facetHaving.getReferenceName());
			assertEquals(1, facetHaving.getChildren().length);
			assertEquals(entityPrimaryKeyInSet(1, 5, 7), facetHaving.getChildren()[0]);
		}

		@Test
		@DisplayName("should return null when reference name is null")
		void shouldReturnNullWhenReferenceNameIsNull() {
			final FacetHaving result = facetHaving(null, entityPrimaryKeyInSet(1));

			assertNull(result);
		}

		@Test
		@DisplayName("should return null when children are null or empty")
		void shouldReturnNullWhenChildrenAreNullOrEmpty() {
			final FacetHaving result = facetHaving("brand", (FilterConstraint[]) null);

			assertNull(result);
		}
	}

	@Nested
	@DisplayName("Property accessors")
	class PropertyAccessorTest {

		@Test
		@DisplayName("should return reference name")
		void shouldReturnReferenceName() {
			final FacetHaving constraint = facetHaving("category", entityPrimaryKeyInSet(1));

			assertEquals("category", constraint.getReferenceName());
		}

		@Test
		@DisplayName("should return children array")
		void shouldReturnChildrenArray() {
			final FilterConstraint child = entityPrimaryKeyInSet(1, 5, 7);
			final FacetHaving constraint = facetHaving("brand", child);

			final FilterConstraint[] children = constraint.getChildren();
			assertEquals(1, children.length);
			assertEquals(child, children[0]);
		}
	}

	@Nested
	@DisplayName("Applicability and necessity")
	class ApplicabilityTest {

		@Test
		@DisplayName("should not be applicable with reference name only")
		void shouldNotBeApplicableWithReferenceNameOnly() {
			assertFalse(new FacetHaving("brand").isApplicable());
		}

		@Test
		@DisplayName("should be applicable with reference name and single child")
		void shouldBeApplicableWithReferenceNameAndSingleChild() {
			assertTrue(facetHaving("brand", entityPrimaryKeyInSet(1)).isApplicable());
		}

		@Test
		@DisplayName("should be applicable with reference name and multiple children")
		void shouldBeApplicableWithReferenceNameAndMultipleChildren() {
			assertTrue(facetHaving("brand", entityPrimaryKeyInSet(1, 5, 7)).isApplicable());
		}

		@Test
		@DisplayName("should not be necessary with reference name only and no children")
		void shouldNotBeNecessaryWithReferenceNameOnlyAndNoChildren() {
			assertFalse(new FacetHaving("brand").isNecessary());
		}

		@Test
		@DisplayName("should be necessary with reference name and children")
		void shouldBeNecessaryWithReferenceNameAndChildren() {
			assertTrue(facetHaving("brand", entityPrimaryKeyInSet(1)).isNecessary());
		}
	}

	@Nested
	@DisplayName("Copy with new children")
	class CopyWithNewChildrenTest {

		@Test
		@DisplayName("should create name-only variant when zero children provided")
		void shouldCreateNameOnlyVariantWhenZeroChildrenProvided() {
			final FacetHaving original = facetHaving("brand", entityPrimaryKeyInSet(1));

			final FilterConstraint copy = original.getCopyWithNewChildren(
				new FilterConstraint[0], new Constraint<?>[0]
			);

			assertInstanceOf(FacetHaving.class, copy);
			// name-only variant has no children and is not applicable
			assertEquals(0, ((FacetHaving) copy).getChildren().length);
			assertFalse(copy.isApplicable());
		}

		@Test
		@DisplayName("should throw when additional children provided")
		void shouldThrowWhenAdditionalChildrenProvided() {
			final FacetHaving original = facetHaving("brand", entityPrimaryKeyInSet(1));

			assertThrows(
				GenericEvitaInternalError.class,
				() -> original.getCopyWithNewChildren(
					new FilterConstraint[]{entityPrimaryKeyInSet(1)},
					new Constraint<?>[]{entityPrimaryKeyInSet(2)}
				)
			);
		}

		@Test
		@DisplayName("should create new instance with provided children")
		void shouldCreateNewInstanceWithProvidedChildren() {
			final FacetHaving original = facetHaving("brand", entityPrimaryKeyInSet(1));
			final FilterConstraint newChild = entityPrimaryKeyInSet(2, 3);

			final FilterConstraint copy = original.getCopyWithNewChildren(
				new FilterConstraint[]{newChild}, new Constraint<?>[0]
			);

			assertInstanceOf(FacetHaving.class, copy);
			assertEquals("brand", ((FacetHaving) copy).getReferenceName());
			assertEquals(1, ((FacetHaving) copy).getChildren().length);
			assertEquals(newChild, ((FacetHaving) copy).getChildren()[0]);
		}
	}

	@Nested
	@DisplayName("Clone with arguments")
	class CloneWithArgumentsTest {

		@Test
		@DisplayName("should clone with new arguments preserving children")
		void shouldCloneWithNewArgumentsPreservingChildren() {
			final FacetHaving original = facetHaving("brand", entityPrimaryKeyInSet(1, 5));

			final FilterConstraint cloned = original.cloneWithArguments(
				new Serializable[]{"category"}
			);

			assertInstanceOf(FacetHaving.class, cloned);
			assertNotSame(original, cloned);
			assertEquals("category", ((FacetHaving) cloned).getReferenceName());
			assertEquals(1, ((FacetHaving) cloned).getChildren().length);
		}
	}

	@Nested
	@DisplayName("Type and visitor")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return FilterConstraint type")
		void shouldReturnFilterConstraintType() {
			assertEquals(FilterConstraint.class, facetHaving("brand", entityPrimaryKeyInSet(1)).getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final FacetHaving constraint = facetHaving("brand", entityPrimaryKeyInSet(1));
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();

			constraint.accept(c -> visited.set((Constraint<?>) c));

			assertSame(constraint, visited.get());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should format with reference name and children")
		void shouldFormatWithReferenceNameAndChildren() {
			final FacetHaving facetHaving = facetHaving("brand", entityPrimaryKeyInSet(1, 5, 7));

			assertEquals("facetHaving('brand',entityPrimaryKeyInSet(1,5,7))", facetHaving.toString());
		}
	}

	@Nested
	@DisplayName("Equals and hashCode")
	class EqualsAndHashCodeTest {

		@Test
		@DisplayName("should equal identical constraint")
		void shouldEqualIdenticalConstraint() {
			assertNotSame(
				facetHaving("brand", entityPrimaryKeyInSet(1, 1, 5)),
				facetHaving("brand", entityPrimaryKeyInSet(1, 1, 5))
			);
			assertEquals(
				facetHaving("brand", entityPrimaryKeyInSet(1, 1, 5)),
				facetHaving("brand", entityPrimaryKeyInSet(1, 1, 5))
			);
		}

		@Test
		@DisplayName("should not equal constraint with different children values")
		void shouldNotEqualConstraintWithDifferentChildrenValues() {
			assertNotEquals(
				facetHaving("brand", entityPrimaryKeyInSet(1, 1, 5)),
				facetHaving("brand", entityPrimaryKeyInSet(1, 1, 6))
			);
		}

		@Test
		@DisplayName("should not equal constraint with different children count")
		void shouldNotEqualConstraintWithDifferentChildrenCount() {
			assertNotEquals(
				facetHaving("brand", entityPrimaryKeyInSet(1, 1, 5)),
				facetHaving("brand", entityPrimaryKeyInSet(1, 1))
			);
		}

		@Test
		@DisplayName("should not equal constraint with different first child value")
		void shouldNotEqualConstraintWithDifferentFirstChildValue() {
			assertNotEquals(
				facetHaving("brand", entityPrimaryKeyInSet(1, 1, 5)),
				facetHaving("brand", entityPrimaryKeyInSet(2, 1, 5))
			);
		}

		@Test
		@DisplayName("should not equal constraint with different reference name")
		void shouldNotEqualConstraintWithDifferentReferenceName() {
			assertNotEquals(
				facetHaving("brand", entityPrimaryKeyInSet(1, 1, 5)),
				facetHaving("category", entityPrimaryKeyInSet(1, 1, 6))
			);
		}

		@Test
		@DisplayName("should have same hashCode for equal constraints")
		void shouldHaveSameHashCodeForEqualConstraints() {
			assertEquals(
				facetHaving("brand", entityPrimaryKeyInSet(1, 1, 5)).hashCode(),
				facetHaving("brand", entityPrimaryKeyInSet(1, 1, 5)).hashCode()
			);
		}

		@Test
		@DisplayName("should have different hashCode for different constraints")
		void shouldHaveDifferentHashCodeForDifferentConstraints() {
			assertNotEquals(
				facetHaving("brand", entityPrimaryKeyInSet(1, 1, 5)).hashCode(),
				facetHaving("brand", entityPrimaryKeyInSet(1, 1, 6)).hashCode()
			);
			assertNotEquals(
				facetHaving("brand", entityPrimaryKeyInSet(1, 1, 5)).hashCode(),
				facetHaving("brand", entityPrimaryKeyInSet(1, 1)).hashCode()
			);
		}
	}
}
