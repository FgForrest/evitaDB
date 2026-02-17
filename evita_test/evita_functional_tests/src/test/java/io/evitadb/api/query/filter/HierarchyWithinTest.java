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
 * Tests for {@link HierarchyWithin} verifying construction, applicability, necessity,
 * suffix handling, child filter extraction, cloning, visitor acceptance, and equality contract.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("HierarchyWithin constraint")
class HierarchyWithinTest {

	@Nested
	@DisplayName("Construction and factory methods")
	class ConstructionTest {

		@Test
		@DisplayName("should create with reference name and parent filter")
		void shouldCreateViaFactoryClassWorkAsExpected() {
			final HierarchyWithin hierarchyWithin = hierarchyWithin("brand", entityPrimaryKeyInSet(1));

			assertEquals("brand", hierarchyWithin.getReferenceName().orElse(null));
			assertEquals(entityPrimaryKeyInSet(1), hierarchyWithin.getParentFilter());
			assertArrayEquals(new FilterConstraint[0], hierarchyWithin.getExcludedChildrenFilter());
			assertFalse(hierarchyWithin.isDirectRelation());
			assertFalse(hierarchyWithin.isExcludingRoot());
		}

		@Test
		@DisplayName("should create with excluding sub-constraint")
		void shouldCreateViaFactoryUsingExcludingSubConstraintClassWorkAsExpected() {
			final HierarchyWithin hierarchyWithin =
				hierarchyWithin("brand", entityPrimaryKeyInSet(1), excluding(entityPrimaryKeyInSet(5, 7)));

			assertEquals("brand", hierarchyWithin.getReferenceName().orElse(null));
			assertEquals(entityPrimaryKeyInSet(1), hierarchyWithin.getParentFilter());
			assertArrayEquals(
				new FilterConstraint[]{new EntityPrimaryKeyInSet(5, 7)},
				hierarchyWithin.getExcludedChildrenFilter()
			);
			assertFalse(hierarchyWithin.isDirectRelation());
			assertFalse(hierarchyWithin.isExcludingRoot());
		}

		@Test
		@DisplayName("should create with excludingRoot sub-constraint")
		void shouldCreateViaFactoryUsingExcludingRootSubConstraintClassWorkAsExpected() {
			final HierarchyWithin hierarchyWithin =
				hierarchyWithin("brand", entityPrimaryKeyInSet(1), excludingRoot());

			assertEquals("brand", hierarchyWithin.getReferenceName().orElse(null));
			assertEquals(entityPrimaryKeyInSet(1), hierarchyWithin.getParentFilter());
			assertArrayEquals(new FilterConstraint[0], hierarchyWithin.getExcludedChildrenFilter());
			assertFalse(hierarchyWithin.isDirectRelation());
			assertTrue(hierarchyWithin.isExcludingRoot());
		}

		@Test
		@DisplayName("should create self-referencing variant without entity type")
		void shouldCreateViaFactoryWithoutEntityTypeClassWorkAsExpected() {
			final HierarchyWithin hierarchyWithin =
				hierarchyWithinSelf(entityPrimaryKeyInSet(1), excludingRoot());

			assertNull(hierarchyWithin.getReferenceName().orElse(null));
			assertEquals(entityPrimaryKeyInSet(1), hierarchyWithin.getParentFilter());
			assertArrayEquals(new FilterConstraint[0], hierarchyWithin.getExcludedChildrenFilter());
			assertFalse(hierarchyWithin.isDirectRelation());
			assertTrue(hierarchyWithin.isExcludingRoot());
		}

		@Test
		@DisplayName("should create with directRelation sub-constraint")
		void shouldCreateViaFactoryUsingDirectRelationSubConstraintClassWorkAsExpected() {
			final HierarchyWithin hierarchyWithin =
				hierarchyWithin("brand", entityPrimaryKeyInSet(1), directRelation());

			assertEquals("brand", hierarchyWithin.getReferenceName().orElse(null));
			assertEquals(entityPrimaryKeyInSet(1), hierarchyWithin.getParentFilter());
			assertArrayEquals(new FilterConstraint[0], hierarchyWithin.getExcludedChildrenFilter());
			assertTrue(hierarchyWithin.isDirectRelation());
			assertFalse(hierarchyWithin.isExcludingRoot());
		}

		@Test
		@DisplayName("should create with all possible sub-constraints")
		void shouldCreateViaFactoryUsingAllPossibleSubConstraintClassWorkAsExpected() {
			final HierarchyWithin hierarchyWithin = hierarchyWithin(
				"brand", entityPrimaryKeyInSet(1),
				excludingRoot(),
				excluding(entityPrimaryKeyInSet(3, 7))
			);

			assertEquals("brand", hierarchyWithin.getReferenceName().orElse(null));
			assertEquals(entityPrimaryKeyInSet(1), hierarchyWithin.getParentFilter());
			assertArrayEquals(
				new FilterConstraint[]{new EntityPrimaryKeyInSet(3, 7)},
				hierarchyWithin.getExcludedChildrenFilter()
			);
			assertFalse(hierarchyWithin.isDirectRelation());
			assertTrue(hierarchyWithin.isExcludingRoot());
		}
	}

	@Nested
	@DisplayName("Core operations")
	class CoreOperationsTest {

		@Test
		@DisplayName("should recognize applicability based on children presence")
		void shouldRecognizeApplicability() {
			assertFalse(new HierarchyWithin("brand", null).isApplicable());
			assertTrue(hierarchyWithin("brand", entityPrimaryKeyInSet(1)).isApplicable());
			assertTrue(
				hierarchyWithin("brand", entityPrimaryKeyInSet(1), excluding(entityPrimaryKeyInSet(5, 7)))
					.isApplicable()
			);
		}

		@Test
		@DisplayName("should recognize necessity when applicable")
		void shouldRecognizeNecessity() {
			// isNecessary() returns super.isNecessary() || isApplicable()
			// with children present, isApplicable() is true, so isNecessary() is true
			assertTrue(hierarchyWithin("brand", entityPrimaryKeyInSet(1)).isNecessary());
			assertTrue(
				hierarchyWithin("brand", entityPrimaryKeyInSet(1), excluding(entityPrimaryKeyInSet(5, 7)))
					.isNecessary()
			);
		}

		@Test
		@DisplayName("should return FilterConstraint type")
		void shouldReturnFilterConstraintType() {
			final HierarchyWithin constraint = hierarchyWithin("brand", entityPrimaryKeyInSet(1));

			assertEquals(FilterConstraint.class, constraint.getType());
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final HierarchyWithin constraint = hierarchyWithin("brand", entityPrimaryKeyInSet(1));
			final AtomicReference<Constraint<?>> captured = new AtomicReference<>();

			constraint.accept(captured::set);

			assertSame(constraint, captured.get());
		}

		@Test
		@DisplayName("should return 'self' suffix for self-referencing variant")
		void shouldReturnSelfSuffixForSelfReferencingVariant() {
			final HierarchyWithin constraint = hierarchyWithinSelf(entityPrimaryKeyInSet(1));

			final Optional<String> suffix = constraint.getSuffixIfApplied();

			assertTrue(suffix.isPresent());
			assertEquals("self", suffix.get());
		}

		@Test
		@DisplayName("should return empty suffix for reference-name variant")
		void shouldReturnEmptySuffixForReferenceNameVariant() {
			final HierarchyWithin constraint = hierarchyWithin("brand", entityPrimaryKeyInSet(1));

			final Optional<String> suffix = constraint.getSuffixIfApplied();

			assertTrue(suffix.isEmpty());
		}

		@Test
		@DisplayName("should extract having children filter")
		void shouldExtractHavingChildrenFilter() {
			final HierarchyWithin constraint = hierarchyWithin(
				"brand", entityPrimaryKeyInSet(1),
				having(attributeEquals("code", "a"))
			);

			final FilterConstraint[] havingFilter = constraint.getHavingChildrenFilter();

			assertEquals(1, havingFilter.length);
			assertEquals(attributeEquals("code", "a"), havingFilter[0]);
		}

		@Test
		@DisplayName("should return empty array when no having filter present")
		void shouldReturnEmptyHavingChildrenFilterWhenAbsent() {
			final HierarchyWithin constraint = hierarchyWithin("brand", entityPrimaryKeyInSet(1));

			final FilterConstraint[] havingFilter = constraint.getHavingChildrenFilter();

			assertEquals(0, havingFilter.length);
		}

		@Test
		@DisplayName("should extract anyHaving child filter")
		void shouldExtractHavingAnyChildFilter() {
			final HierarchyWithin constraint = hierarchyWithin(
				"brand", entityPrimaryKeyInSet(1),
				anyHaving(attributeEquals("status", "ACTIVE"))
			);

			final FilterConstraint[] anyHavingFilter = constraint.getHavingAnyChildFilter();

			assertEquals(1, anyHavingFilter.length);
			assertEquals(attributeEquals("status", "ACTIVE"), anyHavingFilter[0]);
		}

		@Test
		@DisplayName("should return empty array when no anyHaving filter present")
		void shouldReturnEmptyHavingAnyChildFilterWhenAbsent() {
			final HierarchyWithin constraint = hierarchyWithin("brand", entityPrimaryKeyInSet(1));

			final FilterConstraint[] anyHavingFilter = constraint.getHavingAnyChildFilter();

			assertEquals(0, anyHavingFilter.length);
		}

		@Test
		@DisplayName("should extract hierarchy specification constraints")
		void shouldExtractHierarchySpecificationConstraints() {
			final HierarchyWithin constraint = hierarchyWithin(
				"brand", entityPrimaryKeyInSet(1),
				excludingRoot(),
				excluding(entityPrimaryKeyInSet(3, 7)),
				having(attributeEquals("code", "a"))
			);

			final HierarchySpecificationFilterConstraint[] specs =
				constraint.getHierarchySpecificationConstraints();

			assertEquals(3, specs.length);
		}

		@Test
		@DisplayName("should clone with new arguments for basic case")
		void shouldCloneWithArgumentsForBasicCase() {
			final HierarchyWithin original = hierarchyWithin("brand", entityPrimaryKeyInSet(1));

			final FilterConstraint cloned = original.cloneWithArguments(new Serializable[]{"category"});

			assertNotSame(original, cloned);
			assertInstanceOf(HierarchyWithin.class, cloned);
			assertEquals("category", ((HierarchyWithin) cloned).getReferenceName().orElse(null));
		}

		@Test
		@DisplayName("should clone with new arguments when excluding children are present")
		void shouldCloneWithArgumentsWhenExcludingChildrenPresent() {
			final HierarchyWithin original = hierarchyWithin(
				"brand", entityPrimaryKeyInSet(1),
				excluding(entityPrimaryKeyInSet(5, 7))
			);

			final FilterConstraint cloned = original.cloneWithArguments(new Serializable[]{"category"});

			assertNotSame(original, cloned);
			assertInstanceOf(HierarchyWithin.class, cloned);
			final HierarchyWithin clonedWithin = (HierarchyWithin) cloned;
			assertEquals("category", clonedWithin.getReferenceName().orElse(null));
			assertEquals(entityPrimaryKeyInSet(1), clonedWithin.getParentFilter());
			assertArrayEquals(
				new FilterConstraint[]{new EntityPrimaryKeyInSet(5, 7)},
				clonedWithin.getExcludedChildrenFilter()
			);
		}

		@Test
		@DisplayName("should create copy with new children")
		void shouldCreateCopyWithNewChildren() {
			final HierarchyWithin original = hierarchyWithin("brand", entityPrimaryKeyInSet(1));
			final FilterConstraint[] newChildren = new FilterConstraint[]{entityPrimaryKeyInSet(2)};

			final FilterConstraint copy = original.getCopyWithNewChildren(newChildren, new Constraint<?>[0]);

			assertNotSame(original, copy);
			assertInstanceOf(HierarchyWithin.class, copy);
			final HierarchyWithin withinCopy = (HierarchyWithin) copy;
			assertEquals("brand", withinCopy.getReferenceName().orElse(null));
			assertEquals(entityPrimaryKeyInSet(2), withinCopy.getParentFilter());
		}

		@Test
		@DisplayName("should produce correct toString")
		void shouldToStringReturnExpectedFormat() {
			final HierarchyWithin hierarchyWithin =
				hierarchyWithin("brand", entityPrimaryKeyInSet(1), excluding(entityPrimaryKeyInSet(5, 7)));

			assertEquals(
				"hierarchyWithin('brand',entityPrimaryKeyInSet(1),excluding(entityPrimaryKeyInSet(5,7)))",
				hierarchyWithin.toString()
			);
		}

		@Test
		@DisplayName("should produce correct toString with multiple sub-constraints")
		void shouldToStringReturnExpectedFormatWhenUsingMultipleSubConstraints() {
			final HierarchyWithin hierarchyWithin = hierarchyWithin(
				"brand", entityPrimaryKeyInSet(1),
				excludingRoot(),
				excluding(entityPrimaryKeyInSet(3, 7))
			);

			assertEquals(
				"hierarchyWithin('brand',entityPrimaryKeyInSet(1),excludingRoot(),excluding(entityPrimaryKeyInSet(3,7)))",
				hierarchyWithin.toString()
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
				hierarchyWithin("brand", entityPrimaryKeyInSet(1), excluding(entityPrimaryKeyInSet(1, 5))),
				hierarchyWithin("brand", entityPrimaryKeyInSet(1), excluding(entityPrimaryKeyInSet(1, 5)))
			);
			assertEquals(
				hierarchyWithin("brand", entityPrimaryKeyInSet(1), excluding(entityPrimaryKeyInSet(1, 5))),
				hierarchyWithin("brand", entityPrimaryKeyInSet(1), excluding(entityPrimaryKeyInSet(1, 5)))
			);
			assertNotEquals(
				hierarchyWithin("brand", entityPrimaryKeyInSet(1), excluding(entityPrimaryKeyInSet(1, 5))),
				hierarchyWithin("brand", entityPrimaryKeyInSet(1), excluding(entityPrimaryKeyInSet(1, 6)))
			);
			assertNotEquals(
				hierarchyWithin("brand", entityPrimaryKeyInSet(1), excluding(entityPrimaryKeyInSet(1, 5))),
				hierarchyWithin("brand", entityPrimaryKeyInSet(1), excluding(entityPrimaryKeyInSet(1)))
			);
			assertNotEquals(
				hierarchyWithin("brand", entityPrimaryKeyInSet(1), excluding(entityPrimaryKeyInSet(1, 5))),
				hierarchyWithin("brand", entityPrimaryKeyInSet(2), excluding(entityPrimaryKeyInSet(1, 5)))
			);
			assertNotEquals(
				hierarchyWithin("brand", entityPrimaryKeyInSet(1), excluding(entityPrimaryKeyInSet(1, 5))),
				hierarchyWithin("category", entityPrimaryKeyInSet(1), excluding(entityPrimaryKeyInSet(1, 6)))
			);
			assertNotEquals(
				hierarchyWithin("brand", entityPrimaryKeyInSet(1), excluding(entityPrimaryKeyInSet(1, 5))),
				hierarchyWithin("brand", entityPrimaryKeyInSet(1), excluding(entityPrimaryKeyInSet(1)))
			);
			assertEquals(
				hierarchyWithin("brand", entityPrimaryKeyInSet(1), excluding(entityPrimaryKeyInSet(1, 5))).hashCode(),
				hierarchyWithin("brand", entityPrimaryKeyInSet(1), excluding(entityPrimaryKeyInSet(1, 5))).hashCode()
			);
			assertNotEquals(
				hierarchyWithin("brand", entityPrimaryKeyInSet(1), excluding(entityPrimaryKeyInSet(1, 5))).hashCode(),
				hierarchyWithin("brand", entityPrimaryKeyInSet(1), excluding(entityPrimaryKeyInSet(1, 6))).hashCode()
			);
			assertNotEquals(
				hierarchyWithin("brand", entityPrimaryKeyInSet(1), excluding(entityPrimaryKeyInSet(1, 5))).hashCode(),
				hierarchyWithin("brand", entityPrimaryKeyInSet(1), excluding(entityPrimaryKeyInSet(1))).hashCode()
			);
		}
	}
}
