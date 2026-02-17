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
import static io.evitadb.api.query.QueryConstraints.attributeGreaterThan;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ReferenceHaving} verifying construction, applicability, necessity, property accessors,
 * child management, cloning, visitor support, string representation, and equality contract.
 *
 * @author evitaDB
 */
@DisplayName("ReferenceHaving constraint")
class ReferenceHavingTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should create with reference name only via factory")
		void shouldCreateWithReferenceNameOnlyViaFactory() {
			final ReferenceHaving constraint = referenceHaving("brand");

			assertNotNull(constraint);
			assertEquals("brand", constraint.getReferenceName());
			assertEquals(0, constraint.getChildrenCount());
		}

		@Test
		@DisplayName("should create with reference name and children via factory")
		void shouldCreateWithReferenceNameAndChildrenViaFactory() {
			final ReferenceHaving constraint = referenceHaving(
				"brand",
				attributeEquals("code", "apple")
			);

			assertNotNull(constraint);
			assertEquals("brand", constraint.getReferenceName());
			assertEquals(1, constraint.getChildrenCount());
			assertEquals("code", ((AttributeEquals) constraint.getChildren()[0]).getAttributeName());
		}

		@Test
		@DisplayName("should return null when reference name is null")
		void shouldReturnNullWhenReferenceNameIsNull() {
			final ReferenceHaving constraint = referenceHaving(null);

			assertNull(constraint);
		}

		@Test
		@DisplayName("should create with multiple children")
		void shouldCreateWithMultipleChildren() {
			final ReferenceHaving constraint = referenceHaving(
				"brand",
				attributeEquals("code", "apple"),
				attributeGreaterThan("priority", 5)
			);

			assertNotNull(constraint);
			assertEquals("brand", constraint.getReferenceName());
			assertEquals(2, constraint.getChildrenCount());
		}
	}

	@Nested
	@DisplayName("Property accessors")
	class PropertyAccessorTest {

		@Test
		@DisplayName("should return reference name")
		void shouldReturnReferenceName() {
			final ReferenceHaving constraint = referenceHaving("category");

			assertEquals("category", constraint.getReferenceName());
		}

		@Test
		@DisplayName("should return children array")
		void shouldReturnChildrenArray() {
			final FilterConstraint child = attributeEquals("code", "test");
			final ReferenceHaving constraint = referenceHaving("brand", child);

			final FilterConstraint[] children = constraint.getChildren();
			assertEquals(1, children.length);
			assertSame(child, children[0]);
		}
	}

	@Nested
	@DisplayName("Applicability and necessity")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable with reference name")
		void shouldBeApplicableWithReferenceName() {
			assertTrue(referenceHaving("brand").isApplicable());
		}

		@Test
		@DisplayName("should be applicable with reference name and children")
		void shouldBeApplicableWithReferenceNameAndChildren() {
			assertTrue(referenceHaving("brand", attributeEquals("x", "y")).isApplicable());
		}

		@Test
		@DisplayName("should be necessary with reference name only")
		void shouldBeNecessaryWithReferenceNameOnly() {
			assertTrue(referenceHaving("brand").isNecessary());
		}

		@Test
		@DisplayName("should be necessary with reference name and children")
		void shouldBeNecessaryWithReferenceNameAndChildren() {
			assertTrue(referenceHaving("brand", attributeEquals("x", "y")).isNecessary());
		}
	}

	@Nested
	@DisplayName("Copy with new children")
	class CopyWithNewChildrenTest {

		@Test
		@DisplayName("should create copy with no children preserving reference name")
		void shouldCreateCopyWithNoChildrenPreservingReferenceName() {
			final ReferenceHaving original = referenceHaving("brand", attributeEquals("x", "y"));

			final FilterConstraint copy = original.getCopyWithNewChildren(
				new FilterConstraint[0], new Constraint<?>[0]
			);

			assertInstanceOf(ReferenceHaving.class, copy);
			assertEquals("brand", ((ReferenceHaving) copy).getReferenceName());
			assertEquals(0, ((ReferenceHaving) copy).getChildrenCount());
		}

		@Test
		@DisplayName("should create copy with single child preserving reference name")
		void shouldCreateCopyWithSingleChildPreservingReferenceName() {
			final ReferenceHaving original = referenceHaving("brand");
			final FilterConstraint newChild = attributeEquals("code", "test");

			final FilterConstraint copy = original.getCopyWithNewChildren(
				new FilterConstraint[]{newChild}, new Constraint<?>[0]
			);

			assertInstanceOf(ReferenceHaving.class, copy);
			assertEquals("brand", ((ReferenceHaving) copy).getReferenceName());
			assertEquals(1, ((ReferenceHaving) copy).getChildrenCount());
		}

		@Test
		@DisplayName("should create copy with multiple children preserving reference name")
		void shouldCreateCopyWithMultipleChildrenPreservingReferenceName() {
			final ReferenceHaving original = referenceHaving("brand");
			final FilterConstraint child1 = attributeEquals("code", "a");
			final FilterConstraint child2 = attributeEquals("name", "b");

			final FilterConstraint copy = original.getCopyWithNewChildren(
				new FilterConstraint[]{child1, child2}, new Constraint<?>[0]
			);

			assertInstanceOf(ReferenceHaving.class, copy);
			assertEquals("brand", ((ReferenceHaving) copy).getReferenceName());
			assertEquals(2, ((ReferenceHaving) copy).getChildrenCount());
		}

		@Test
		@DisplayName("should throw when additional children are provided")
		void shouldThrowWhenAdditionalChildrenAreProvided() {
			final ReferenceHaving original = referenceHaving("brand");
			final FilterConstraint child = attributeEquals("code", "a");
			final Constraint<?> additionalChild = attributeEquals("extra", "b");

			assertThrows(
				Exception.class,
				() -> original.getCopyWithNewChildren(
					new FilterConstraint[]{child},
					new Constraint<?>[]{additionalChild}
				)
			);
		}
	}

	@Nested
	@DisplayName("Clone with arguments")
	class CloneWithArgumentsTest {

		@Test
		@DisplayName("should clone with new arguments preserving children")
		void shouldCloneWithNewArgumentsPreservingChildren() {
			final ReferenceHaving original = referenceHaving("brand", attributeEquals("x", "y"));

			final FilterConstraint cloned = original.cloneWithArguments(new Serializable[]{"category"});

			assertInstanceOf(ReferenceHaving.class, cloned);
			assertNotSame(original, cloned);
			assertEquals("category", ((ReferenceHaving) cloned).getReferenceName());
			assertEquals(1, ((ReferenceHaving) cloned).getChildrenCount());
		}
	}

	@Nested
	@DisplayName("Type and visitor")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return FilterConstraint type")
		void shouldReturnFilterConstraintType() {
			assertEquals(FilterConstraint.class, referenceHaving("brand").getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final ReferenceHaving constraint = referenceHaving("brand");
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();

			constraint.accept(c -> visited.set((Constraint<?>) c));

			assertSame(constraint, visited.get());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should format with reference name only")
		void shouldFormatWithReferenceNameOnly() {
			assertEquals("referenceHaving('brand')", referenceHaving("brand").toString());
		}

		@Test
		@DisplayName("should format with reference name and child")
		void shouldFormatWithReferenceNameAndChild() {
			final ReferenceHaving constraint = referenceHaving(
				"brand",
				attributeEquals("code", "apple")
			);
			assertEquals(
				"referenceHaving('brand',attributeEquals('code','apple'))",
				constraint.toString()
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
				referenceHaving("brand", attributeEquals("x", "y")),
				referenceHaving("brand", attributeEquals("x", "y"))
			);
			assertEquals(
				referenceHaving("brand", attributeEquals("x", "y")),
				referenceHaving("brand", attributeEquals("x", "y"))
			);
		}

		@Test
		@DisplayName("should not equal constraint with different reference name")
		void shouldNotEqualConstraintWithDifferentReferenceName() {
			assertNotEquals(
				referenceHaving("brand", attributeEquals("x", "y")),
				referenceHaving("category", attributeEquals("x", "y"))
			);
		}

		@Test
		@DisplayName("should not equal constraint with different children")
		void shouldNotEqualConstraintWithDifferentChildren() {
			assertNotEquals(
				referenceHaving("brand", attributeEquals("x", "y")),
				referenceHaving("brand", attributeEquals("x", "z"))
			);
		}

		@Test
		@DisplayName("should have same hashCode for equal constraints")
		void shouldHaveSameHashCodeForEqualConstraints() {
			assertEquals(
				referenceHaving("brand", attributeEquals("x", "y")).hashCode(),
				referenceHaving("brand", attributeEquals("x", "y")).hashCode()
			);
		}

		@Test
		@DisplayName("should have different hashCode for different constraints")
		void shouldHaveDifferentHashCodeForDifferentConstraints() {
			assertNotEquals(
				referenceHaving("brand", attributeEquals("x", "y")).hashCode(),
				referenceHaving("category", attributeEquals("x", "y")).hashCode()
			);
		}
	}
}
