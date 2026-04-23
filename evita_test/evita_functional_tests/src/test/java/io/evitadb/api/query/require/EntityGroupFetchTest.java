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
 * Tests for {@link EntityGroupFetch} verifying construction, applicability, combining, containment, cloning, visitor
 * support, string representation, and equality.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("EntityGroupFetch constraint")
class EntityGroupFetchTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should create empty entity group fetch via factory")
		void shouldCreateEmptyEntityGroupFetchViaFactory() {
			final EntityGroupFetch entityGroupFetch = entityGroupFetch();

			assertNotNull(entityGroupFetch);
			assertArrayEquals(new EntityContentRequire[0], entityGroupFetch.getRequirements());
		}

		@Test
		@DisplayName("should create entity group fetch with requirements via factory")
		void shouldCreateEntityGroupFetchWithRequirementsViaFactory() {
			final EntityGroupFetch entityGroupFetch = entityGroupFetch(attributeContentAll(), associatedDataContentAll());

			assertArrayEquals(new EntityContentRequire[]{attributeContentAll(), associatedDataContentAll()}, entityGroupFetch.getRequirements());
		}

		@Test
		@DisplayName("should create entity group fetch via no-arg constructor")
		void shouldCreateEntityGroupFetchViaNoArgConstructor() {
			final EntityGroupFetch entityGroupFetch = new EntityGroupFetch();

			assertNotNull(entityGroupFetch);
			assertArrayEquals(new EntityContentRequire[0], entityGroupFetch.getRequirements());
		}

		@Test
		@DisplayName("should create entity group fetch with requirements via constructor")
		void shouldCreateEntityGroupFetchWithRequirementsViaConstructor() {
			final EntityGroupFetch entityGroupFetch = new EntityGroupFetch(attributeContentAll(), associatedDataContentAll());

			assertArrayEquals(new EntityContentRequire[]{attributeContentAll(), associatedDataContentAll()}, entityGroupFetch.getRequirements());
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable for empty entity group fetch")
		void shouldBeApplicableForEmptyEntityGroupFetch() {
			assertTrue(entityGroupFetch().isApplicable());
		}

		@Test
		@DisplayName("should be applicable with requirements")
		void shouldBeApplicableWithRequirements() {
			assertTrue(entityGroupFetch(attributeContentAll()).isApplicable());
			assertTrue(entityGroupFetch(attributeContentAll(), associatedDataContentAll()).isApplicable());
		}
	}

	@Nested
	@DisplayName("Visitor support")
	class VisitorSupportTest {

		@Test
		@DisplayName("should delegate to visitor")
		void shouldDelegateToVisitor() {
			final EntityGroupFetch entityGroupFetch = entityGroupFetch(attributeContentAll());
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();

			entityGroupFetch.accept(c -> {
				visited.set(c);
			});

			assertSame(entityGroupFetch, visited.get());
		}

		@Test
		@DisplayName("should return RequireConstraint type")
		void shouldReturnRequireConstraintType() {
			final EntityGroupFetch entityGroupFetch = entityGroupFetch();

			assertEquals(RequireConstraint.class, entityGroupFetch.getType());
		}
	}

	@Nested
	@DisplayName("String representation")
	class StringRepresentationTest {

		@Test
		@DisplayName("should format toString for empty entity group fetch")
		void shouldFormatToStringForEmptyEntityGroupFetch() {
			final EntityGroupFetch entityGroupFetch = entityGroupFetch();

			assertEquals("entityGroupFetch()", entityGroupFetch.toString());
		}

		@Test
		@DisplayName("should format toString with requirements")
		void shouldFormatToStringWithRequirements() {
			final EntityGroupFetch entityGroupFetch = entityGroupFetch(attributeContentAll());

			assertEquals("entityGroupFetch(attributeContentAll())", entityGroupFetch.toString());
		}

		@Test
		@DisplayName("should format toString with multiple requirements")
		void shouldFormatToStringWithMultipleRequirements() {
			final EntityGroupFetch entityGroupFetch = entityGroupFetch(attributeContentAll(), associatedDataContentAll());

			assertEquals("entityGroupFetch(attributeContentAll(),associatedDataContentAll())", entityGroupFetch.toString());
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should be equal for same empty entity group fetches")
		void shouldBeEqualForSameEmptyEntityGroupFetches() {
			assertNotSame(entityGroupFetch(), entityGroupFetch());
			assertEquals(entityGroupFetch(), entityGroupFetch());
			assertEquals(entityGroupFetch().hashCode(), entityGroupFetch().hashCode());
		}

		@Test
		@DisplayName("should be equal for same requirements")
		void shouldBeEqualForSameRequirements() {
			assertEquals(entityGroupFetch(attributeContentAll()), entityGroupFetch(attributeContentAll()));
			assertEquals(entityGroupFetch(attributeContentAll()).hashCode(), entityGroupFetch(attributeContentAll()).hashCode());
		}

		@Test
		@DisplayName("should not be equal for different requirements")
		void shouldNotBeEqualForDifferentRequirements() {
			assertNotEquals(entityGroupFetch(), entityGroupFetch(attributeContent()));
			assertNotEquals(entityGroupFetch().hashCode(), entityGroupFetch(attributeContent()).hashCode());
		}
	}

	@Nested
	@DisplayName("Combining with other requirements")
	class CombiningTest {

		@Test
		@DisplayName("should combine with null and return self")
		void shouldCombineWithNullAndReturnSelf() {
			final EntityGroupFetch entityGroupFetch = entityGroupFetch(attributeContentAll());

			assertEquals(entityGroupFetch, entityGroupFetch.combineWith(null));
		}

		@Test
		@DisplayName("should throw exception when combining with different type")
		void shouldThrowExceptionWhenCombiningWithDifferentType() {
			final EntityGroupFetch entityGroupFetch = entityGroupFetch(attributeContentAll());

			assertThrows(GenericEvitaInternalError.class, () -> entityGroupFetch.combineWith(entityFetchAll()));
		}

		@Test
		@DisplayName("should combine empty entity group fetches")
		void shouldCombineEmptyEntityGroupFetches() {
			final EntityGroupFetch entityGroupFetch = entityGroupFetch();

			assertEquals(entityGroupFetch(), entityGroupFetch.combineWith(entityGroupFetch()));
		}

		@Test
		@DisplayName("should combine entity group fetches with same requirement types")
		void shouldCombineEntityGroupFetchesWithSameRequirementTypes() {
			final EntityGroupFetch entityGroupFetch1 = entityGroupFetch(attributeContent("code"));
			final EntityGroupFetch entityGroupFetch2 = entityGroupFetch(attributeContent("name"));
			final EntityGroupFetch combined = entityGroupFetch1.combineWith(entityGroupFetch2);

			assertNotSame(entityGroupFetch1, combined);
			assertNotSame(entityGroupFetch2, combined);
			assertEquals(entityGroupFetch(attributeContent("code", "name")), combined);
		}

		@Test
		@DisplayName("should combine entity group fetches with different requirement types")
		void shouldCombineEntityGroupFetchesWithDifferentRequirementTypes() {
			final EntityGroupFetch entityGroupFetch1 = entityGroupFetch(attributeContent("code"));
			final EntityGroupFetch entityGroupFetch2 = entityGroupFetch(associatedDataContent("name"));
			final EntityGroupFetch combined = entityGroupFetch1.combineWith(entityGroupFetch2);

			assertNotSame(entityGroupFetch1, combined);
			assertNotSame(entityGroupFetch2, combined);
			assertEquals(entityGroupFetch(attributeContent("code"), associatedDataContent("name")), combined);
		}

		@Test
		@DisplayName("should combine complex entity group fetches")
		void shouldCombineComplexEntityGroupFetches() {
			final EntityGroupFetch entityGroupFetch1 = entityGroupFetch(attributeContent("code"), associatedDataContentAll());
			final EntityGroupFetch entityGroupFetch2 = entityGroupFetch(attributeContent("name"));
			final EntityGroupFetch combined = entityGroupFetch1.combineWith(entityGroupFetch2);

			assertEquals(entityGroupFetch(attributeContent("code", "name"), associatedDataContentAll()), combined);
		}
	}

	@Nested
	@DisplayName("Containment checking")
	class ContainmentTest {

		@Test
		@DisplayName("should be fully contained within entityGroupFetchAll")
		void shouldBeFullyContainedWithinEntityGroupFetchAll() {
			final EntityGroupFetch entityGroupFetch = entityGroupFetch(attributeContent("a"), hierarchyContent());

			assertTrue(entityGroupFetch.isFullyContainedWithin(entityGroupFetchAll()));
		}

		@Test
		@DisplayName("should not be fully contained within different requirements")
		void shouldNotBeFullyContainedWithinDifferentRequirements() {
			final EntityGroupFetch entityGroupFetch = entityGroupFetch(attributeContentAll());

			assertFalse(entityGroupFetch.isFullyContainedWithin(entityGroupFetch(associatedDataContentAll())));
		}

		@Test
		@DisplayName("should be fully contained when requirements are subset")
		void shouldBeFullyContainedWhenRequirementsAreSubset() {
			final EntityGroupFetch entityGroupFetch1 = entityGroupFetch(attributeContent("code", "name"));
			final EntityGroupFetch entityGroupFetch2 = entityGroupFetch(attributeContent("code"));

			assertTrue(entityGroupFetch2.isFullyContainedWithin(entityGroupFetch1));
		}

		@Test
		@DisplayName("should not be fully contained when requirements are not subset")
		void shouldNotBeFullyContainedWhenRequirementsAreNotSubset() {
			final EntityGroupFetch entityGroupFetch1 = entityGroupFetch(attributeContent("code"));
			final EntityGroupFetch entityGroupFetch2 = entityGroupFetch(attributeContent("name"));

			assertFalse(entityGroupFetch1.isFullyContainedWithin(entityGroupFetch2));
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should clone with arguments and preserve children")
		void shouldCloneWithArgumentsAndPreserveChildren() {
			final EntityGroupFetch entityGroupFetch = entityGroupFetch(attributeContentAll());
			final EntityGroupFetch cloned = (EntityGroupFetch) entityGroupFetch.cloneWithArguments(new Serializable[]{});

			assertNotSame(entityGroupFetch, cloned);
			assertEquals(entityGroupFetch, cloned);
			assertArrayEquals(entityGroupFetch.getRequirements(), cloned.getRequirements());
		}

		@Test
		@DisplayName("should get copy with new children")
		void shouldGetCopyWithNewChildren() {
			final EntityGroupFetch entityGroupFetch = entityGroupFetch(attributeContentAll());
			final EntityGroupFetch newEntityGroupFetch = (EntityGroupFetch) entityGroupFetch.getCopyWithNewChildren(
				new RequireConstraint[]{associatedDataContentAll()},
				new Constraint<?>[]{}
			);

			assertNotSame(entityGroupFetch, newEntityGroupFetch);
			assertArrayEquals(new RequireConstraint[]{associatedDataContentAll()}, newEntityGroupFetch.getRequirements());
		}

		@Test
		@DisplayName("should get copy with empty children")
		void shouldGetCopyWithEmptyChildren() {
			final EntityGroupFetch entityGroupFetch = entityGroupFetch(attributeContentAll());
			final EntityGroupFetch newEntityGroupFetch = (EntityGroupFetch) entityGroupFetch.getCopyWithNewChildren(
				new RequireConstraint[]{},
				new Constraint<?>[]{}
			);

			assertNotSame(entityGroupFetch, newEntityGroupFetch);
			assertArrayEquals(new RequireConstraint[]{}, newEntityGroupFetch.getRequirements());
		}
	}
}
