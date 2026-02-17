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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Locale;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DefaultPrefetchRequirementCollector} verifying construction, adding requirements, combining logic,
 * and retrieval operations.
 *
 * @author evitaDB
 */
@DisplayName("DefaultPrefetchRequirementCollector")
class DefaultPrefetchRequirementCollectorTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should create empty collector with no-arg constructor")
		void shouldCreateEmptyCollectorWithNoArgConstructor() {
			final DefaultPrefetchRequirementCollector collector = new DefaultPrefetchRequirementCollector();

			assertTrue(collector.isEmpty());
			assertNull(collector.getEntityFetch());
			assertArrayEquals(DefaultPrefetchRequirementCollector.EMPTY_REQUIREMENTS, collector.getRequirementsToPrefetch());
		}

		@Test
		@DisplayName("should create collector with EntityFetch requirements")
		void shouldCreateCollectorWithEntityFetchRequirements() {
			final EntityFetch entityFetch = entityFetch(
				attributeContent("code", "name"),
				associatedDataContent("description")
			);
			final DefaultPrefetchRequirementCollector collector = new DefaultPrefetchRequirementCollector(entityFetch);

			assertFalse(collector.isEmpty());
			assertEquals(2, collector.getRequirementsToPrefetch().length);
		}

		@Test
		@DisplayName("should create empty collector when EntityFetch is null")
		void shouldCreateEmptyCollectorWhenEntityFetchIsNull() {
			final DefaultPrefetchRequirementCollector collector = new DefaultPrefetchRequirementCollector(null);

			assertTrue(collector.isEmpty());
			assertNull(collector.getEntityFetch());
		}
	}

	@Nested
	@DisplayName("Adding requirements")
	class AddingRequirementsTest {

		@Test
		@DisplayName("should add single requirement to empty collector")
		void shouldAddSingleRequirementToEmptyCollector() {
			final DefaultPrefetchRequirementCollector collector = new DefaultPrefetchRequirementCollector();

			collector.addRequirementsToPrefetch(attributeContent("code"));

			assertFalse(collector.isEmpty());
			assertEquals(1, collector.getRequirementsToPrefetch().length);
			assertInstanceOf(AttributeContent.class, collector.getRequirementsToPrefetch()[0]);
		}

		@Test
		@DisplayName("should add multiple requirements to empty collector")
		void shouldAddMultipleRequirementsToEmptyCollector() {
			final DefaultPrefetchRequirementCollector collector = new DefaultPrefetchRequirementCollector();

			collector.addRequirementsToPrefetch(
				attributeContent("code"),
				associatedDataContent("description")
			);

			assertFalse(collector.isEmpty());
			assertEquals(2, collector.getRequirementsToPrefetch().length);
		}

		@Test
		@DisplayName("should add requirements to non-empty collector")
		void shouldAddRequirementsToNonEmptyCollector() {
			final DefaultPrefetchRequirementCollector collector = new DefaultPrefetchRequirementCollector(
				entityFetch(attributeContent("code"))
			);

			collector.addRequirementsToPrefetch(associatedDataContent("description"));

			assertEquals(2, collector.getRequirementsToPrefetch().length);
		}
	}

	@Nested
	@DisplayName("Combining requirements")
	class CombiningRequirementsTest {

		@Test
		@DisplayName("should combine attribute content requirements of same type")
		void shouldCombineAttributeContentRequirementsOfSameType() {
			final DefaultPrefetchRequirementCollector collector = new DefaultPrefetchRequirementCollector();

			collector.addRequirementsToPrefetch(attributeContent("code"));
			collector.addRequirementsToPrefetch(attributeContent("name"));

			final EntityContentRequire[] requirements = collector.getRequirementsToPrefetch();
			assertEquals(1, requirements.length);
			assertInstanceOf(AttributeContent.class, requirements[0]);
			final AttributeContent combined = (AttributeContent) requirements[0];
			assertArrayEquals(new String[]{"code", "name"}, combined.getAttributeNames());
		}

		@Test
		@DisplayName("should keep separate requirements of different types")
		void shouldKeepSeparateRequirementsOfDifferentTypes() {
			final DefaultPrefetchRequirementCollector collector = new DefaultPrefetchRequirementCollector();

			collector.addRequirementsToPrefetch(
				attributeContent("code"),
				associatedDataContent("description")
			);

			final EntityContentRequire[] requirements = collector.getRequirementsToPrefetch();
			assertEquals(2, requirements.length);
		}

		@Test
		@DisplayName("should not duplicate requirement when fully contained within existing")
		void shouldNotDuplicateRequirementWhenFullyContainedWithinExisting() {
			final DefaultPrefetchRequirementCollector collector = new DefaultPrefetchRequirementCollector();

			collector.addRequirementsToPrefetch(attributeContentAll());
			collector.addRequirementsToPrefetch(attributeContent("code"));

			final EntityContentRequire[] requirements = collector.getRequirementsToPrefetch();
			assertEquals(1, requirements.length);
			assertInstanceOf(AttributeContent.class, requirements[0]);
			assertTrue(((AttributeContent) requirements[0]).isAllRequested());
		}

		@Test
		@DisplayName("should add multiple non-combinable requirements of same type")
		void shouldAddMultipleNonCombinableRequirementsOfSameType() {
			final DefaultPrefetchRequirementCollector collector = new DefaultPrefetchRequirementCollector();

			// DataInLocales requirements are combinable, but let's use them to test the array expansion
			collector.addRequirementsToPrefetch(dataInLocales(Locale.ENGLISH));
			collector.addRequirementsToPrefetch(dataInLocales(new Locale("cs")));

			final EntityContentRequire[] requirements = collector.getRequirementsToPrefetch();
			assertEquals(1, requirements.length);
			assertInstanceOf(DataInLocales.class, requirements[0]);
		}
	}

	@Nested
	@DisplayName("Retrieval operations")
	class RetrievalOperationsTest {

		@Test
		@DisplayName("should return empty array when collector is empty")
		void shouldReturnEmptyArrayWhenCollectorIsEmpty() {
			final DefaultPrefetchRequirementCollector collector = new DefaultPrefetchRequirementCollector();

			final EntityContentRequire[] requirements = collector.getRequirementsToPrefetch();

			assertNotNull(requirements);
			assertEquals(0, requirements.length);
			assertSame(DefaultPrefetchRequirementCollector.EMPTY_REQUIREMENTS, requirements);
		}

		@Test
		@DisplayName("should return all requirements as flat array")
		void shouldReturnAllRequirementsAsFlatArray() {
			final DefaultPrefetchRequirementCollector collector = new DefaultPrefetchRequirementCollector();

			collector.addRequirementsToPrefetch(
				attributeContent("code"),
				associatedDataContent("description"),
				dataInLocales(Locale.ENGLISH)
			);

			final EntityContentRequire[] requirements = collector.getRequirementsToPrefetch();

			assertEquals(3, requirements.length);
		}

		@Test
		@DisplayName("should return EntityFetch wrapping all requirements")
		void shouldReturnEntityFetchWrappingAllRequirements() {
			final DefaultPrefetchRequirementCollector collector = new DefaultPrefetchRequirementCollector();

			collector.addRequirementsToPrefetch(
				attributeContent("code"),
				associatedDataContent("description")
			);

			final EntityFetch entityFetch = collector.getEntityFetch();

			assertNotNull(entityFetch);
			assertEquals(2, entityFetch.getRequirements().length);
		}

		@Test
		@DisplayName("should return null when getting EntityFetch from empty collector")
		void shouldReturnNullWhenGettingEntityFetchFromEmptyCollector() {
			final DefaultPrefetchRequirementCollector collector = new DefaultPrefetchRequirementCollector();

			assertNull(collector.getEntityFetch());
		}
	}

	@Nested
	@DisplayName("isEmpty behavior")
	class IsEmptyBehaviorTest {

		@Test
		@DisplayName("should return true for newly created empty collector")
		void shouldReturnTrueForNewlyCreatedEmptyCollector() {
			final DefaultPrefetchRequirementCollector collector = new DefaultPrefetchRequirementCollector();

			assertTrue(collector.isEmpty());
		}

		@Test
		@DisplayName("should return false after adding requirements")
		void shouldReturnFalseAfterAddingRequirements() {
			final DefaultPrefetchRequirementCollector collector = new DefaultPrefetchRequirementCollector();

			collector.addRequirementsToPrefetch(attributeContent("code"));

			assertFalse(collector.isEmpty());
		}

		@Test
		@DisplayName("should return false when created with EntityFetch")
		void shouldReturnFalseWhenCreatedWithEntityFetch() {
			final DefaultPrefetchRequirementCollector collector = new DefaultPrefetchRequirementCollector(
				entityFetch(attributeContent("code"))
			);

			assertFalse(collector.isEmpty());
		}
	}

	/**
	 * Helper method to create an EntityFetch with given requirements.
	 *
	 * @param requirements the requirements to include in the EntityFetch
	 * @return an EntityFetch instance containing the given requirements
	 */
	@Nonnull
	private static EntityFetch createEntityFetch(@Nonnull EntityContentRequire... requirements) {
		return entityFetch(requirements);
	}

}
