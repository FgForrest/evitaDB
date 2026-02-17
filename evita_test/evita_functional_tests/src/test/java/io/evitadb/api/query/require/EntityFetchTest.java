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
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EntityFetch} verifying construction, applicability, combining, containment, cloning, visitor support,
 * string representation, and equality.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("EntityFetch constraint")
class EntityFetchTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should create empty entity fetch via factory")
		void shouldCreateEmptyEntityFetchViaFactory() {
			final EntityFetch entityFetch = entityFetch();

			assertNotNull(entityFetch);
			assertArrayEquals(new EntityContentRequire[0], entityFetch.getRequirements());
		}

		@Test
		@DisplayName("should create entity fetch with requirements via factory")
		void shouldCreateEntityFetchWithRequirementsViaFactory() {
			final EntityFetch entityFetch = entityFetch(attributeContentAll(), associatedDataContentAll());

			assertArrayEquals(new EntityContentRequire[]{attributeContentAll(), associatedDataContentAll()}, entityFetch.getRequirements());
		}

		@Test
		@DisplayName("should create entity fetch via no-arg constructor")
		void shouldCreateEntityFetchViaNoArgConstructor() {
			final EntityFetch entityFetch = new EntityFetch();

			assertNotNull(entityFetch);
			assertArrayEquals(new EntityContentRequire[0], entityFetch.getRequirements());
		}

		@Test
		@DisplayName("should create entity fetch with requirements via constructor")
		void shouldCreateEntityFetchWithRequirementsViaConstructor() {
			final EntityFetch entityFetch = new EntityFetch(attributeContentAll(), associatedDataContentAll());

			assertArrayEquals(new EntityContentRequire[]{attributeContentAll(), associatedDataContentAll()}, entityFetch.getRequirements());
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable for empty entity fetch")
		void shouldBeApplicableForEmptyEntityFetch() {
			assertTrue(entityFetch().isApplicable());
		}

		@Test
		@DisplayName("should be applicable with requirements")
		void shouldBeApplicableWithRequirements() {
			assertTrue(entityFetch(attributeContentAll()).isApplicable());
			assertTrue(entityFetch(attributeContentAll(), associatedDataContentAll()).isApplicable());
		}
	}

	@Nested
	@DisplayName("Visitor support")
	class VisitorSupportTest {

		@Test
		@DisplayName("should delegate to visitor")
		void shouldDelegateToVisitor() {
			final EntityFetch entityFetch = entityFetch(attributeContentAll());
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();

			entityFetch.accept(c -> {
				visited.set(c);
			});

			assertSame(entityFetch, visited.get());
		}

		@Test
		@DisplayName("should return RequireConstraint type")
		void shouldReturnRequireConstraintType() {
			final EntityFetch entityFetch = entityFetch();

			assertEquals(RequireConstraint.class, entityFetch.getType());
		}
	}

	@Nested
	@DisplayName("String representation")
	class StringRepresentationTest {

		@Test
		@DisplayName("should format toString for empty entity fetch")
		void shouldFormatToStringForEmptyEntityFetch() {
			final EntityFetch entityFetch = entityFetch();

			assertEquals("entityFetch()", entityFetch.toString());
		}

		@Test
		@DisplayName("should format toString with requirements")
		void shouldFormatToStringWithRequirements() {
			final EntityFetch entityFetch = entityFetch(attributeContentAll());

			assertEquals("entityFetch(attributeContentAll())", entityFetch.toString());
		}

		@Test
		@DisplayName("should format toString with multiple requirements")
		void shouldFormatToStringWithMultipleRequirements() {
			final EntityFetch entityFetch = entityFetch(attributeContentAll(), associatedDataContentAll());

			assertEquals("entityFetch(attributeContentAll(),associatedDataContentAll())", entityFetch.toString());
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should be equal for same empty entity fetches")
		void shouldBeEqualForSameEmptyEntityFetches() {
			assertNotSame(entityFetch(), entityFetch());
			assertEquals(entityFetch(), entityFetch());
			assertEquals(entityFetch().hashCode(), entityFetch().hashCode());
		}

		@Test
		@DisplayName("should be equal for same requirements")
		void shouldBeEqualForSameRequirements() {
			assertEquals(entityFetch(attributeContentAll()), entityFetch(attributeContentAll()));
			assertEquals(entityFetch(attributeContentAll()).hashCode(), entityFetch(attributeContentAll()).hashCode());
		}

		@Test
		@DisplayName("should not be equal for different requirements")
		void shouldNotBeEqualForDifferentRequirements() {
			assertNotEquals(entityFetch(), entityFetch(attributeContentAll()));
			assertNotEquals(entityFetch().hashCode(), entityFetch(attributeContentAll()).hashCode());
		}
	}

	@Nested
	@DisplayName("Combining with other requirements")
	class CombiningTest {

		@Test
		@DisplayName("should combine with null and return self")
		void shouldCombineWithNullAndReturnSelf() {
			final EntityFetch entityFetch = entityFetch(attributeContentAll());

			assertEquals(entityFetch, entityFetch.combineWith(null));
		}

		@Test
		@DisplayName("should throw exception when combining with different type")
		void shouldThrowExceptionWhenCombiningWithDifferentType() {
			final EntityFetch entityFetch = entityFetch(attributeContentAll());

			assertThrows(GenericEvitaInternalError.class, () -> entityFetch.combineWith(entityGroupFetchAll()));
		}

		@Test
		@DisplayName("should combine empty entity fetches")
		void shouldCombineEmptyEntityFetches() {
			final EntityFetch entityFetch = entityFetch();

			assertEquals(entityFetch(), entityFetch.combineWith(entityFetch()));
		}

		@Test
		@DisplayName("should combine entity fetches with same requirement types")
		void shouldCombineEntityFetchesWithSameRequirementTypes() {
			final EntityFetch entityFetch1 = entityFetch(attributeContent("code"));
			final EntityFetch entityFetch2 = entityFetch(attributeContent("name"));
			final EntityFetch combined = entityFetch1.combineWith(entityFetch2);

			assertNotSame(entityFetch1, combined);
			assertNotSame(entityFetch2, combined);
			assertEquals(entityFetch(attributeContent("code", "name")), combined);
		}

		@Test
		@DisplayName("should combine entity fetches with different requirement types")
		void shouldCombineEntityFetchesWithDifferentRequirementTypes() {
			final EntityFetch entityFetch1 = entityFetch(attributeContent("code"));
			final EntityFetch entityFetch2 = entityFetch(associatedDataContent("name"));
			final EntityFetch combined = entityFetch1.combineWith(entityFetch2);

			assertNotSame(entityFetch1, combined);
			assertNotSame(entityFetch2, combined);
			assertEquals(entityFetch(attributeContent("code"), associatedDataContent("name")), combined);
		}

		@Test
		@DisplayName("should combine complex entity fetches")
		void shouldCombineComplexEntityFetches() {
			final EntityFetch entityFetch1 = entityFetch(attributeContent("code"), associatedDataContentAll());
			final EntityFetch entityFetch2 = entityFetch(attributeContent("name"));
			final EntityFetch combined = entityFetch1.combineWith(entityFetch2);

			assertEquals(entityFetch(attributeContent("code", "name"), associatedDataContentAll()), combined);
		}
	}

	@Nested
	@DisplayName("Containment checking")
	class ContainmentTest {

		@Test
		@DisplayName("should be fully contained within entityFetchAll")
		void shouldBeFullyContainedWithinEntityFetchAll() {
			final EntityFetch entityFetch = entityFetch(attributeContent("a"), hierarchyContent());

			assertTrue(entityFetch.isFullyContainedWithin(entityFetchAll()));
		}

		@Test
		@DisplayName("should not be fully contained within different requirements")
		void shouldNotBeFullyContainedWithinDifferentRequirements() {
			final EntityFetch entityFetch = entityFetch(attributeContentAll());

			assertFalse(entityFetch.isFullyContainedWithin(entityFetch(associatedDataContentAll())));
		}

		@Test
		@DisplayName("should be fully contained when requirements are subset")
		void shouldBeFullyContainedWhenRequirementsAreSubset() {
			final EntityFetch entityFetch1 = entityFetch(attributeContent("code", "name"));
			final EntityFetch entityFetch2 = entityFetch(attributeContent("code"));

			assertTrue(entityFetch2.isFullyContainedWithin(entityFetch1));
		}

		@Test
		@DisplayName("should not be fully contained when requirements are not subset")
		void shouldNotBeFullyContainedWhenRequirementsAreNotSubset() {
			final EntityFetch entityFetch1 = entityFetch(attributeContent("code"));
			final EntityFetch entityFetch2 = entityFetch(attributeContent("name"));

			assertFalse(entityFetch1.isFullyContainedWithin(entityFetch2));
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should clone with arguments and preserve children")
		void shouldCloneWithArgumentsAndPreserveChildren() {
			final EntityFetch entityFetch = entityFetch(attributeContentAll());
			final EntityFetch cloned = (EntityFetch) entityFetch.cloneWithArguments(new Serializable[]{});

			assertNotSame(entityFetch, cloned);
			assertEquals(entityFetch, cloned);
			assertArrayEquals(entityFetch.getRequirements(), cloned.getRequirements());
		}

		@Test
		@DisplayName("should get copy with new children")
		void shouldGetCopyWithNewChildren() {
			final EntityFetch entityFetch = entityFetch(attributeContentAll());
			final EntityFetch newEntityFetch = (EntityFetch) entityFetch.getCopyWithNewChildren(
				new RequireConstraint[]{associatedDataContentAll()},
				new Constraint<?>[]{}
			);

			assertNotSame(entityFetch, newEntityFetch);
			assertArrayEquals(new RequireConstraint[]{associatedDataContentAll()}, newEntityFetch.getRequirements());
		}

		@Test
		@DisplayName("should get copy with empty children")
		void shouldGetCopyWithEmptyChildren() {
			final EntityFetch entityFetch = entityFetch(attributeContentAll());
			final EntityFetch newEntityFetch = (EntityFetch) entityFetch.getCopyWithNewChildren(
				new RequireConstraint[]{},
				new Constraint<?>[]{}
			);

			assertNotSame(entityFetch, newEntityFetch);
			assertArrayEquals(new RequireConstraint[]{}, newEntityFetch.getRequirements());
		}
	}

}
