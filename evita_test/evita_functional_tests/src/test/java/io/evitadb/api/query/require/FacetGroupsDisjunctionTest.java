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
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.RequireConstraint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.require.FacetGroupRelationLevel.WITH_DIFFERENT_FACETS_IN_GROUP;
import static io.evitadb.api.query.require.FacetGroupRelationLevel.WITH_DIFFERENT_GROUPS;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FacetGroupsDisjunction} verifying construction, applicability, getters,
 * copy/clone operations, visitor acceptance, and equality contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("FacetGroupsDisjunction constraint")
class FacetGroupsDisjunctionTest {

	@Nested
	@DisplayName("Construction and factory methods")
	class ConstructionTest {

		@Test
		@DisplayName("should create with default relation level")
		void shouldCreateWithDefaultRelationLevel() {
			final FacetGroupsDisjunction constraint = facetGroupsDisjunction(
				"brand", filterBy(entityPrimaryKeyInSet(1, 5, 7))
			);

			assertEquals("brand", constraint.getReferenceName());
			assertEquals(WITH_DIFFERENT_FACETS_IN_GROUP, constraint.getFacetGroupRelationLevel());
			assertEquals(filterBy(entityPrimaryKeyInSet(1, 5, 7)), constraint.getFacetGroups().orElseThrow());
		}

		@Test
		@DisplayName("should create with explicit relation level")
		void shouldCreateWithExplicitRelationLevel() {
			final FacetGroupsDisjunction constraint = facetGroupsDisjunction(
				"brand", WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(1, 5, 7))
			);

			assertEquals("brand", constraint.getReferenceName());
			assertEquals(WITH_DIFFERENT_GROUPS, constraint.getFacetGroupRelationLevel());
			assertEquals(filterBy(entityPrimaryKeyInSet(1, 5, 7)), constraint.getFacetGroups().orElseThrow());
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable when reference name is non-null")
		void shouldBeApplicableWithReferenceName() {
			assertTrue(new FacetGroupsDisjunction("brand", null).isApplicable());
			assertTrue(new FacetGroupsDisjunction("brand", WITH_DIFFERENT_GROUPS, null).isApplicable());
			assertTrue(facetGroupsDisjunction("brand", filterBy(entityPrimaryKeyInSet(1, 5, 7))).isApplicable());
		}

		@Test
		@DisplayName("should not be applicable when reference name is null")
		void shouldNotBeApplicableWithNullReferenceName() {
			assertFalse(new FacetGroupsDisjunction(null, null).isApplicable());
			assertFalse(new FacetGroupsDisjunction(null, WITH_DIFFERENT_GROUPS, null).isApplicable());
		}
	}

	@Nested
	@DisplayName("Type and visitor")
	class TypeAndVisitorTest {

		@Test
		@DisplayName("should return RequireConstraint class as type")
		void shouldReturnRequireConstraintClassAsType() {
			assertEquals(
				RequireConstraint.class,
				facetGroupsDisjunction("brand", filterBy(entityPrimaryKeyInSet(1))).getType()
			);
		}

		@Test
		@DisplayName("should accept visitor")
		void shouldAcceptVisitor() {
			final FacetGroupsDisjunction constraint = facetGroupsDisjunction(
				"brand", filterBy(entityPrimaryKeyInSet(1))
			);
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();
			constraint.accept(new ConstraintVisitor() {
				@Override
				public void visit(@Nonnull Constraint<?> constraint) {
					visited.set(constraint);
				}
			});

			assertSame(constraint, visited.get());
		}
	}

	@Nested
	@DisplayName("Copy and clone operations")
	class CopyAndCloneTest {

		@Test
		@DisplayName("should create copy with new additional children")
		void shouldCreateCopyWithNewAdditionalChildren() {
			final FacetGroupsDisjunction original = facetGroupsDisjunction(
				"brand", filterBy(entityPrimaryKeyInSet(1))
			);
			final RequireConstraint copy = original.getCopyWithNewChildren(
				new RequireConstraint[0],
				new Constraint<?>[]{filterBy(entityPrimaryKeyInSet(2, 3))}
			);

			assertNotSame(original, copy);
			assertInstanceOf(FacetGroupsDisjunction.class, copy);
			assertEquals("brand", ((FacetGroupsDisjunction) copy).getReferenceName());
		}

		@Test
		@DisplayName("should clone with new arguments preserving additional children")
		void shouldCloneWithNewArguments() {
			final FacetGroupsDisjunction original = facetGroupsDisjunction(
				"brand", filterBy(entityPrimaryKeyInSet(1))
			);
			final RequireConstraint cloned = original.cloneWithArguments(
				new Serializable[]{"category", WITH_DIFFERENT_GROUPS}
			);

			assertNotSame(original, cloned);
			assertInstanceOf(FacetGroupsDisjunction.class, cloned);
			assertEquals("category", ((FacetGroupsDisjunction) cloned).getReferenceName());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce expected toString with default relation level")
		void shouldProduceToStringWithDefaultLevel() {
			assertEquals(
				"facetGroupsDisjunction('brand',filterBy(entityPrimaryKeyInSet(1,5,7)))",
				facetGroupsDisjunction("brand", filterBy(entityPrimaryKeyInSet(1, 5, 7))).toString()
			);
		}

		@Test
		@DisplayName("should produce expected toString with non-default relation level")
		void shouldProduceToStringWithNonDefaultLevel() {
			assertEquals(
				"facetGroupsDisjunction('brand',WITH_DIFFERENT_GROUPS,filterBy(entityPrimaryKeyInSet(1,5,7)))",
				facetGroupsDisjunction(
					"brand", WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(1, 5, 7))
				).toString()
			);
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(
				facetGroupsDisjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))),
				facetGroupsDisjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5)))
			);
			assertEquals(
				facetGroupsDisjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))),
				facetGroupsDisjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5)))
			);
			assertEquals(
				facetGroupsDisjunction("brand", WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(1, 1, 5))),
				facetGroupsDisjunction("brand", WITH_DIFFERENT_GROUPS, filterBy(entityPrimaryKeyInSet(1, 1, 5)))
			);
			assertNotEquals(
				facetGroupsDisjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))),
				facetGroupsDisjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 6)))
			);
			assertNotEquals(
				facetGroupsDisjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))),
				facetGroupsDisjunction("category", filterBy(entityPrimaryKeyInSet(1, 1, 5)))
			);
			assertEquals(
				facetGroupsDisjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))).hashCode(),
				facetGroupsDisjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))).hashCode()
			);
			assertNotEquals(
				facetGroupsDisjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 5))).hashCode(),
				facetGroupsDisjunction("brand", filterBy(entityPrimaryKeyInSet(1, 1, 6))).hashCode()
			);
		}
	}
}
