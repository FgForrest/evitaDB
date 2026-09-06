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

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import org.junit.jupiter.api.Tag;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.PRICE;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.REQUIRE;

/**
 * Tests for {@link DefaultPrefetchRequirementCollector} verifying construction, adding requirements, combining logic,
 * and retrieval operations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("DefaultPrefetchRequirementCollector")
@Tag(CONTRACT)
@Tag(REQUIRE)
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

	@Nested
	@DisplayName("Reference content merging")
	@Tag(REFERENCE)
	class ReferenceContentMergingTest {

		@Test
		@DisplayName("should drop a name specific requirement contained within the one for all references")
		void shouldDropSpecificReferenceContentContainedWithinAllReferences() {
			final DefaultPrefetchRequirementCollector collector = new DefaultPrefetchRequirementCollector();

			collector.addRequirementsToPrefetch(referenceContentAll());
			collector.addRequirementsToPrefetch(referenceContent("category"));

			final EntityContentRequire[] requirements = collector.getRequirementsToPrefetch();
			assertEquals(1, requirements.length);
			assertEquals(referenceContentAll(), requirements[0]);
		}

		@Test
		@DisplayName("should keep a requirement for all references added after a name specific one")
		void shouldKeepAllReferencesRequirementAddedAfterSpecificOne() {
			final DefaultPrefetchRequirementCollector collector = new DefaultPrefetchRequirementCollector();

			// the union is order sensitive: a requirement for all references is not contained within a name
			// specific one and the two do not share a key, so both survive
			collector.addRequirementsToPrefetch(referenceContent("category"));
			collector.addRequirementsToPrefetch(referenceContentAll());

			assertEquals(2, collector.getRequirementsToPrefetch().length);
		}

		@Test
		@DisplayName("should combine two reference contents naming the same set of references")
		void shouldCombineTwoReferenceContentsWithIdenticalNameSets() {
			final DefaultPrefetchRequirementCollector collector = new DefaultPrefetchRequirementCollector();

			collector.addRequirementsToPrefetch(
				referenceContent(new String[]{"a", "b"}, entityFetch(attributeContent("code")))
			);
			collector.addRequirementsToPrefetch(
				referenceContent(new String[]{"b", "a"}, entityFetch(attributeContent("name")))
			);

			final EntityContentRequire[] requirements = collector.getRequirementsToPrefetch();
			assertEquals(1, requirements.length);
			assertEquals(
				referenceContent(new String[]{"a", "b"}, entityFetch(attributeContent("code", "name"))),
				requirements[0]
			);
		}

		@Test
		@DisplayName("should keep reference contents whose name sets merely overlap apart")
		void shouldKeepReferenceContentsWithOverlappingNameSetsApart() {
			final DefaultPrefetchRequirementCollector collector = new DefaultPrefetchRequirementCollector();

			collector.addRequirementsToPrefetch(referenceContent("a", "b"));
			collector.addRequirementsToPrefetch(referenceContent("b", "c"));

			assertEquals(2, collector.getRequirementsToPrefetch().length);
		}

		/**
		 * The prefetch asks what must be **loaded**, and a filter, an order and a page only decide how the loaded
		 * references are projected into the response. They are therefore stripped at the door, both from the
		 * requirements the constructor seeds and from the ones added later.
		 */
		@Test
		@DisplayName("should strip the filter, the order and the chunking of an entering requirement")
		void shouldStripOutputRestrictionsOfEnteringRequirement() {
			final DefaultPrefetchRequirementCollector collector = new DefaultPrefetchRequirementCollector(
				entityFetch(
					referenceContent(
						"a",
						filterBy(entityPrimaryKeyInSet(5)),
						orderBy(entityPrimaryKeyNatural(OrderDirection.DESC)),
						entityFetch(attributeContent("code")),
						page(1, 1)
					)
				)
			);

			final EntityContentRequire[] requirements = collector.getRequirementsToPrefetch();
			assertEquals(1, requirements.length);
			assertEquals(referenceContent("a", entityFetch(attributeContent("code"))), requirements[0]);
		}

		/**
		 * The pair the client-facing fold refuses - one sibling filtering the reference, the other one not - is
		 * exactly the pair the query planner produces on its own whenever a filtered reference is also named by
		 * `referenceHaving`. The collector must accept it, and it does because neither side reaches the merge
		 * carrying a filter.
		 */
		@Test
		@DisplayName("should accept a one sided filter the client facing fold refuses")
		void shouldAcceptOneSidedFilterRefusedByClientFacingFold() {
			final ReferenceContent filtered = referenceContent("a", filterBy(entityPrimaryKeyInSet(5)));
			final ReferenceContent bare = referenceContent("a");

			// the very same pair, judged by the rule that shapes the response, has no union
			assertThrows(EvitaInvalidUsageException.class, () -> filtered.combineWith(bare));

			final DefaultPrefetchRequirementCollector collector = new DefaultPrefetchRequirementCollector();
			collector.addRequirementsToPrefetch(filtered);
			collector.addRequirementsToPrefetch(bare);

			final EntityContentRequire[] requirements = collector.getRequirementsToPrefetch();
			assertEquals(1, requirements.length);
			assertEquals(referenceContent("a"), requirements[0]);
		}

		/**
		 * The shape the query planner actually builds: the client's filtered `referenceContent` meets the
		 * `referenceContentWithAttributes` the sort translator contributes for the very same reference. Both enter
		 * unrestricted, so the merge unites their bodies instead of refusing them.
		 */
		@Test
		@DisplayName("should unite a filtered client requirement with the planner's sort attribute requirement")
		void shouldUniteFilteredClientRequirementWithPlannerSortAttributeRequirement() {
			final DefaultPrefetchRequirementCollector collector = new DefaultPrefetchRequirementCollector();

			collector.addRequirementsToPrefetch(
				new ReferenceContent("a", attributeContent("priority"))
			);
			collector.addRequirementsToPrefetch(
				referenceContent(
					"a",
					filterBy(entityPrimaryKeyInSet(5)),
					entityFetch(attributeContent("code"))
				)
			);

			final EntityContentRequire[] requirements = collector.getRequirementsToPrefetch();
			assertEquals(1, requirements.length);
			final ReferenceContent merged = (ReferenceContent) requirements[0];
			assertTrue(merged.getFilterBy().isEmpty());
			assertArrayEquals(
				new String[]{"priority"},
				merged.getAttributeContent().orElseThrow().getAttributeNames()
			);
			assertEquals(entityFetch(attributeContent("code")), merged.getEntityRequirement().orElseThrow());
		}

		/**
		 * Two different filters are no longer a disagreement once both are stripped - the prefetch loads every
		 * reference of that name and lets the response projection pick the slice each requirement asked for.
		 */
		@Test
		@DisplayName("should accept two differently filtered requirements for one reference")
		void shouldAcceptTwoDifferentlyFilteredRequirementsForOneReference() {
			final DefaultPrefetchRequirementCollector collector = new DefaultPrefetchRequirementCollector();

			collector.addRequirementsToPrefetch(referenceContent("a", filterBy(entityPrimaryKeyInSet(1, 2))));
			collector.addRequirementsToPrefetch(referenceContent("a", filterBy(entityPrimaryKeyInSet(3, 4))));

			final EntityContentRequire[] requirements = collector.getRequirementsToPrefetch();
			assertEquals(1, requirements.length);
			assertEquals(referenceContent("a"), requirements[0]);
		}
	}

	@Nested
	@DisplayName("Accompanying price merging")
	@Tag(PRICE)
	class AccompanyingPriceMergingTest {

		@Test
		@DisplayName("should keep accompanying prices of different names apart")
		void shouldKeepAccompanyingPricesOfDifferentNamesApart() {
			final DefaultPrefetchRequirementCollector collector = new DefaultPrefetchRequirementCollector();

			collector.addRequirementsToPrefetch(accompanyingPriceContent("a", "basic"));
			collector.addRequirementsToPrefetch(accompanyingPriceContent("b", "reference"));

			final EntityContentRequire[] requirements = collector.getRequirementsToPrefetch();
			assertEquals(2, requirements.length);
		}

		@Test
		@DisplayName("should refuse two accompanying prices of one name computed from different price lists")
		void shouldRefuseAccompanyingPricesOfOneNameWithDifferentPriceLists() {
			final DefaultPrefetchRequirementCollector collector = new DefaultPrefetchRequirementCollector();

			collector.addRequirementsToPrefetch(accompanyingPriceContent("a", "basic"));

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> collector.addRequirementsToPrefetch(accompanyingPriceContent("a", "reference"))
			);
		}
	}

}
