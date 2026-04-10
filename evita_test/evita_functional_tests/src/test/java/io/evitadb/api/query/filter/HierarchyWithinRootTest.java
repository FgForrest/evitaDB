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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HierarchyWithinRoot} verifying construction, applicability, necessity,
 * suffix handling, child filter extraction, cloning, visitor acceptance, and equality contract.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("HierarchyWithinRoot constraint")
class HierarchyWithinRootTest {

	@Nested
	@DisplayName("Construction and factory methods")
	class ConstructionTest {

		@Test
		@DisplayName("should create with reference name")
		void shouldCreateViaFactoryClassWorkAsExpected() {
			final HierarchyWithinRoot hierarchyWithinRoot = hierarchyWithinRoot("brand");

			assertEquals("brand", hierarchyWithinRoot.getReferenceName().orElse(null));
			assertArrayEquals(new FilterConstraint[0], hierarchyWithinRoot.getExcludedChildrenFilter());
			assertFalse(hierarchyWithinRoot.isDirectRelation());
		}

		@Test
		@DisplayName("should create with excluding sub-constraint")
		void shouldCreateViaFactoryUsingExcludingSubConstraintClassWorkAsExpected() {
			final HierarchyWithinRoot hierarchyWithinRoot =
				hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(5, 7)));

			assertEquals("brand", hierarchyWithinRoot.getReferenceName().orElse(null));
			assertArrayEquals(
				new FilterConstraint[]{new EntityPrimaryKeyInSet(5, 7)},
				hierarchyWithinRoot.getExcludedChildrenFilter()
			);
			assertFalse(hierarchyWithinRoot.isDirectRelation());
		}

		@Test
		@DisplayName("should create self-referencing variant with directRelation")
		void shouldCreateViaFactoryUsingDirectRelationSubConstraintClassWorkAsExpected() {
			final HierarchyWithinRoot hierarchyWithinRoot = hierarchyWithinRootSelf(directRelation());

			assertNull(hierarchyWithinRoot.getReferenceName().orElse(null));
			assertArrayEquals(new FilterConstraint[0], hierarchyWithinRoot.getExcludedChildrenFilter());
			assertTrue(hierarchyWithinRoot.isDirectRelation());
		}

		@Test
		@DisplayName("should reject directRelation on non-self-referencing variant")
		void shouldFailToCreateViaFactoryUsingDirectOnSelfReferencingConstraint() {
			assertThrows(
				IllegalArgumentException.class,
				() -> hierarchyWithinRoot("brand", directRelation())
			);
		}

		@Test
		@DisplayName("should create self-referencing variant with all sub-constraints")
		void shouldCreateViaFactoryUsingAllPossibleSubConstraintClassWorkAsExpected() {
			final HierarchyWithinRoot hierarchyWithinRoot = hierarchyWithinRootSelf(
				excluding(entityPrimaryKeyInSet(3, 7))
			);

			assertNull(hierarchyWithinRoot.getReferenceName().orElse(null));
			assertArrayEquals(
				new FilterConstraint[]{new EntityPrimaryKeyInSet(3, 7)},
				hierarchyWithinRoot.getExcludedChildrenFilter()
			);
		}
	}

	@Nested
	@DisplayName("Core operations")
	class CoreOperationsTest {

		@Test
		@DisplayName("should always be applicable")
		void shouldRecognizeApplicability() {
			assertTrue(hierarchyWithinRootSelf().isApplicable());
			assertTrue(hierarchyWithinRoot("brand").isApplicable());
			assertTrue(
				hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(5, 7))).isApplicable()
			);
		}

		@Test
		@DisplayName("should always be necessary")
		void shouldAlwaysBeNecessary() {
			// isNecessary() unconditionally returns true
			assertTrue(hierarchyWithinRootSelf().isNecessary());
			assertTrue(hierarchyWithinRoot("brand").isNecessary());
			assertTrue(
				hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(5, 7))).isNecessary()
			);
		}

		@Test
		@DisplayName("should return FilterConstraint type")
		void shouldReturnFilterConstraintType() {
			final HierarchyWithinRoot constraint = hierarchyWithinRoot("brand");

			assertEquals(FilterConstraint.class, constraint.getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final HierarchyWithinRoot constraint = hierarchyWithinRoot("brand");
			final AtomicReference<Constraint<?>> captured = new AtomicReference<>();

			constraint.accept(captured::set);

			assertSame(constraint, captured.get());
		}

		@Test
		@DisplayName("should return 'self' suffix for self-referencing variant")
		void shouldReturnSelfSuffixForSelfReferencingVariant() {
			final HierarchyWithinRoot constraint = hierarchyWithinRootSelf();

			final Optional<String> suffix = constraint.getSuffixIfApplied();

			assertTrue(suffix.isPresent());
			assertEquals("self", suffix.get());
		}

		@Test
		@DisplayName("should return empty suffix for reference-name variant")
		void shouldReturnEmptySuffixForReferenceNameVariant() {
			final HierarchyWithinRoot constraint = hierarchyWithinRoot("brand");

			final Optional<String> suffix = constraint.getSuffixIfApplied();

			assertTrue(suffix.isEmpty());
		}

		@Test
		@DisplayName("should extract having children filter")
		void shouldExtractHavingChildrenFilter() {
			final HierarchyWithinRoot constraint = hierarchyWithinRoot(
				"brand", having(attributeEquals("code", "a"))
			);

			final FilterConstraint[] havingFilter = constraint.getHavingChildrenFilter();

			assertEquals(1, havingFilter.length);
			assertEquals(attributeEquals("code", "a"), havingFilter[0]);
		}

		@Test
		@DisplayName("should return empty array when no having filter present")
		void shouldReturnEmptyHavingChildrenFilterWhenAbsent() {
			final HierarchyWithinRoot constraint = hierarchyWithinRoot("brand");

			final FilterConstraint[] havingFilter = constraint.getHavingChildrenFilter();

			assertEquals(0, havingFilter.length);
		}

		@Test
		@DisplayName("should extract anyHaving child filter")
		void shouldExtractHavingAnyChildFilter() {
			final HierarchyWithinRoot constraint = hierarchyWithinRoot(
				"brand", anyHaving(attributeEquals("status", "ACTIVE"))
			);

			final FilterConstraint[] anyHavingFilter = constraint.getHavingAnyChildFilter();

			assertEquals(1, anyHavingFilter.length);
			assertEquals(attributeEquals("status", "ACTIVE"), anyHavingFilter[0]);
		}

		@Test
		@DisplayName("should return empty array when no anyHaving filter present")
		void shouldReturnEmptyHavingAnyChildFilterWhenAbsent() {
			final HierarchyWithinRoot constraint = hierarchyWithinRoot("brand");

			final FilterConstraint[] anyHavingFilter = constraint.getHavingAnyChildFilter();

			assertEquals(0, anyHavingFilter.length);
		}

		@Test
		@DisplayName("should extract hierarchy specification constraints")
		void shouldExtractHierarchySpecificationConstraints() {
			final HierarchyWithinRoot constraint = hierarchyWithinRoot(
				"brand",
				excluding(entityPrimaryKeyInSet(3, 7)),
				having(attributeEquals("code", "a"))
			);

			final HierarchySpecificationFilterConstraint[] specs =
				constraint.getHierarchySpecificationConstraints();

			assertEquals(2, specs.length);
		}

		@Test
		@DisplayName("should clone with new arguments")
		void shouldCloneWithArguments() {
			final HierarchyWithinRoot original = hierarchyWithinRoot("brand");

			final FilterConstraint cloned = original.cloneWithArguments(new Serializable[]{"category"});

			assertNotSame(original, cloned);
			assertInstanceOf(HierarchyWithinRoot.class, cloned);
			assertEquals("category", ((HierarchyWithinRoot) cloned).getReferenceName().orElse(null));
		}

		@Test
		@DisplayName("should create copy with new children")
		void shouldCreateCopyWithNewChildren() {
			final HierarchyWithinRoot original = hierarchyWithinRoot(
				"brand", excluding(entityPrimaryKeyInSet(1))
			);
			final FilterConstraint[] newChildren =
				new FilterConstraint[]{excluding(entityPrimaryKeyInSet(2))};

			final FilterConstraint copy =
				original.getCopyWithNewChildren(newChildren, new Constraint<?>[0]);

			assertNotSame(original, copy);
			assertInstanceOf(HierarchyWithinRoot.class, copy);
			final HierarchyWithinRoot rootCopy = (HierarchyWithinRoot) copy;
			assertEquals("brand", rootCopy.getReferenceName().orElse(null));
			assertArrayEquals(
				new FilterConstraint[]{entityPrimaryKeyInSet(2)},
				rootCopy.getExcludedChildrenFilter()
			);
		}

		@Test
		@DisplayName("should produce correct toString")
		void shouldToStringReturnExpectedFormat() {
			final HierarchyWithinRoot hierarchyWithinRoot =
				hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(5, 7)));

			assertEquals(
				"hierarchyWithinRoot('brand',excluding(entityPrimaryKeyInSet(5,7)))",
				hierarchyWithinRoot.toString()
			);
		}

		@Test
		@DisplayName("should produce correct toString with multiple sub-constraints")
		void shouldToStringReturnExpectedFormatWhenUsingMultipleSubConstraints() {
			final HierarchyWithinRoot hierarchyWithinRoot = hierarchyWithinRoot(
				"brand",
				excluding(entityPrimaryKeyInSet(3, 7))
			);

			assertEquals(
				"hierarchyWithinRoot('brand',excluding(entityPrimaryKeyInSet(3,7)))",
				hierarchyWithinRoot.toString()
			);
		}
	}

	@Nested
	@DisplayName("Equals and hashCode contract")
	class EqualsAndHashCodeTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(
				hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(1, 5))),
				hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(1, 5)))
			);
			assertEquals(
				hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(1, 5))),
				hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(1, 5)))
			);
			assertNotEquals(
				hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(1, 5))),
				hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(1, 6)))
			);
			assertNotEquals(
				hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(1, 5))),
				hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(1)))
			);
			assertNotEquals(
				hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(1, 5))),
				hierarchyWithinRoot("category", excluding(entityPrimaryKeyInSet(1, 6)))
			);
			assertNotEquals(
				hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(1, 5))),
				hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(1)))
			);
			assertEquals(
				hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(1, 5))).hashCode(),
				hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(1, 5))).hashCode()
			);
			assertNotEquals(
				hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(1, 5))).hashCode(),
				hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(1, 6))).hashCode()
			);
			assertNotEquals(
				hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(1, 5))).hashCode(),
				hierarchyWithinRoot("brand", excluding(entityPrimaryKeyInSet(1))).hashCode()
			);
		}
	}
}
