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

import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.REQUIRE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the static requirement fold {@link EntityFetchRequire#combineDuplicateRequirements(EntityContentRequire[])}
 * that both {@link EntityFetch} and {@link EntityGroupFetch} delegate their own reduction to. Verifies the identity
 * guarantee of the duplicate-free case, the first-appearance ordering of a combined requirement, the n-ary fold, the
 * per-key independence of the fold and its deliberate refusal to consult containment.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("EntityFetchRequire contract")
@Tag(CONTRACT)
@Tag(REQUIRE)
class EntityFetchRequireTest {

	@Nested
	@DisplayName("Duplicate requirement folding")
	class DuplicateRequirementFoldingTest {

		@Test
		@DisplayName("an array shorter than two requirements is handed back untouched")
		void shouldReturnSameArrayWhenFewerThanTwoRequirements() {
			final EntityContentRequire[] empty = EntityContentRequire.EMPTY_ARRAY;
			assertSame(empty, EntityFetchRequire.combineDuplicateRequirements(empty));

			final EntityContentRequire[] single = new EntityContentRequire[]{attributeContent("code")};
			assertSame(single, EntityFetchRequire.combineDuplicateRequirements(single));
		}

		@Test
		@DisplayName("an array with no repeated kind is handed back untouched")
		void shouldReturnSameArrayWhenNoKindRepeats() {
			final EntityContentRequire[] requirements = new EntityContentRequire[]{
				attributeContent("code"),
				priceContentAll(),
				referenceContent("category")
			};

			assertSame(requirements, EntityFetchRequire.combineDuplicateRequirements(requirements));
		}

		@Test
		@DisplayName("a repeated kind whose members do not combine is handed back untouched")
		void shouldReturnSameArrayWhenRepeatedKindIsNotCombinable() {
			final EntityContentRequire[] requirements = new EntityContentRequire[]{
				referenceContent("a"),
				referenceContent("b")
			};

			// the pre-check sees a repeated class and the fold allocates, yet nothing combines - the original
			// instance still has to come back, because callers compare the result with `==`
			assertSame(requirements, EntityFetchRequire.combineDuplicateRequirements(requirements));
		}

		@Test
		@DisplayName("a combined requirement keeps the position of its first appearance")
		void shouldKeepCombinedRequirementAtFirstAppearancePosition() {
			final EntityContentRequire[] reduced = EntityFetchRequire.combineDuplicateRequirements(
				new EntityContentRequire[]{
					attributeContent("code"),
					referenceContent("category"),
					attributeContent("name")
				}
			);

			assertEquals(2, reduced.length);
			assertInstanceOf(AttributeContent.class, reduced[0]);
			assertEquals(Set.of("code", "name"), ((AttributeContent) reduced[0]).getAttributeNamesAsSet());
			assertEquals(referenceContent("category"), reduced[1]);
		}

		@Test
		@DisplayName("three siblings of one kind fold into a single requirement")
		void shouldFoldThreeSiblingsOfOneKindIntoOne() {
			final EntityContentRequire[] reduced = EntityFetchRequire.combineDuplicateRequirements(
				new EntityContentRequire[]{
					attributeContent("code"),
					attributeContent("name"),
					attributeContent("url")
				}
			);

			assertEquals(1, reduced.length);
			assertInstanceOf(AttributeContent.class, reduced[0]);
			assertEquals(Set.of("code", "name", "url"), ((AttributeContent) reduced[0]).getAttributeNamesAsSet());
		}

		@Test
		@DisplayName("each reference key folds independently")
		@Tag(REFERENCE)
		void shouldFoldEachKeyIndependently() {
			final EntityContentRequire[] reduced = EntityFetchRequire.combineDuplicateRequirements(
				new EntityContentRequire[]{
					referenceContent("a", entityFetch(attributeContent("code"))),
					referenceContent("b", entityFetch(attributeContent("code"))),
					referenceContent("a", entityFetch(attributeContent("name")))
				}
			);

			assertEquals(2, reduced.length);
			assertEquals(
				referenceContent("a", entityFetch(attributeContent("code", "name"))),
				reduced[0]
			);
			assertEquals(
				referenceContent("b", entityFetch(attributeContent("code"))),
				reduced[1]
			);
		}

		@Test
		@DisplayName("a name specific reference content is not collapsed into the one for all references")
		@Tag(REFERENCE)
		void shouldNotCollapseSpecificReferenceIntoDefaultOne() {
			final EntityContentRequire[] requirements = new EntityContentRequire[]{
				referenceContentAll(),
				referenceContent("category")
			};

			// containment is deliberately not consulted by the fold - the two requirements are resolved through
			// different lookups and the name specific one wins over the default one
			final EntityContentRequire[] reduced = EntityFetchRequire.combineDuplicateRequirements(requirements);

			assertSame(requirements, reduced);
			assertEquals(2, reduced.length);
		}

		@Test
		@DisplayName("a conflict between two siblings propagates out of the fold")
		@Tag(REFERENCE)
		void shouldPropagateConflictFromCombining() {
			final EntityContentRequire[] requirements = new EntityContentRequire[]{
				referenceContent("a", filterBy(attributeEquals("code", "x"))),
				referenceContent("a", filterBy(attributeEquals("code", "y")))
			};

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> EntityFetchRequire.combineDuplicateRequirements(requirements)
			);
		}

	}

}
