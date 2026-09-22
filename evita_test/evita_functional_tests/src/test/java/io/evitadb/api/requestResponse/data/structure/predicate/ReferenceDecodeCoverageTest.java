/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.api.requestResponse.data.structure.predicate;

import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link ReferenceDecodeCoverage} - the description of *how much* of an entity's reference set a read was
 * allowed to materialize.
 *
 * The type is small, but three of its properties are load bearing and none of them is obvious from reading a call
 * site. First, a name narrowed to a key set must never answer questions about absence: the references outside the
 * set were skipped, not found missing, and reading the difference as absence is the defect this type exists to
 * prevent. Second, {@link ReferenceDecodeCoverage#covers(ReferenceDecodeCoverage)} decides whether an enrichment may
 * skip going back to storage, so a coverage that over-reports containment silently serves an under-read entity.
 * Third, instances take part in cache record identity, so equality and hash code must be by content - including the
 * key arrays, which Java compares by identity unless asked otherwise.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(CONTRACT)
@Tag(REFERENCE)
@DisplayName("Reference decode coverage")
class ReferenceDecodeCoverageTest {

	@Nested
	@DisplayName("Completeness")
	class Completeness {

		@Test
		@DisplayName("A NULL coverage - and only a NULL coverage - means nothing was narrowed away")
		void shouldTreatNullAsTheOnlyCompleteCoverage() {
			assertTrue(ReferenceDecodeCoverage.isComplete(null));
			assertFalse(ReferenceDecodeCoverage.isComplete(ReferenceDecodeCoverage.ofNames(Set.of("products"))));
			// an empty coverage describes a read that materialized nothing - it is the narrowest value, not the widest
			assertFalse(ReferenceDecodeCoverage.isComplete(ReferenceDecodeCoverage.ofNames(Set.of())));
		}
	}

	@Nested
	@DisplayName("Admission")
	class Admission {

		@Test
		@DisplayName("A whole-decoded name admits every key and answers absence questions")
		void shouldAdmitEveryKeyOfAWholeDecodedName() {
			final ReferenceDecodeCoverage coverage = ReferenceDecodeCoverage.ofNames(Set.of("products"));
			assertTrue(coverage.isNameDecodedWhole("products"));
			assertTrue(coverage.isReferenceDecoded("products", 1));
			assertTrue(coverage.isReferenceDecoded("products", Integer.MAX_VALUE));
		}

		@Test
		@DisplayName("A key-narrowed name admits only its keys and never answers absence questions")
		void shouldRefuseAbsenceQuestionsForAKeyNarrowedName() {
			final ReferenceDecodeCoverage coverage = ReferenceDecodeCoverage.of(
				Set.of(), Map.of("products", new int[]{10, 20, 30})
			);
			assertTrue(coverage.isReferenceDecoded("products", 10));
			assertTrue(coverage.isReferenceDecoded("products", 30));
			assertFalse(coverage.isReferenceDecoded("products", 15));
			// the whole point: 15 was SKIPPED, so the coverage must not let a caller read that as "not there"
			assertFalse(coverage.isNameDecodedWhole("products"));
		}

		@Test
		@DisplayName("A name the coverage never mentions admits nothing")
		void shouldAdmitNothingForAnUnmentionedName() {
			final ReferenceDecodeCoverage coverage = ReferenceDecodeCoverage.ofNames(Set.of("products"));
			assertFalse(coverage.isNameDecodedWhole("categories"));
			assertFalse(coverage.isReferenceDecoded("categories", 1));
		}

		@Test
		@DisplayName("One name cannot be decoded both whole and by key")
		void shouldRejectANameOnBothAxes() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> ReferenceDecodeCoverage.of(Set.of("products"), Map.of("products", new int[]{1}))
			);
		}
	}

	@Nested
	@DisplayName("Containment")
	class Containment {

		@Test
		@DisplayName("Nothing narrower than a complete read covers a caller that wants everything")
		void shouldNeverCoverNull() {
			assertFalse(ReferenceDecodeCoverage.ofNames(Set.of("products")).covers(null));
			assertFalse(ReferenceDecodeCoverage.of(Set.of(), Map.of("products", new int[]{1})).covers(null));
		}

		@Test
		@DisplayName("A whole-decoded name covers any key narrowing of the same name")
		void shouldCoverAKeyNarrowingWithAWholeName() {
			final ReferenceDecodeCoverage whole = ReferenceDecodeCoverage.ofNames(Set.of("products"));
			final ReferenceDecodeCoverage byKey = ReferenceDecodeCoverage.of(
				Set.of(), Map.of("products", new int[]{7, 9})
			);
			assertTrue(whole.covers(byKey));
			// ...and the reverse never holds, whatever the key set
			assertFalse(byKey.covers(whole));
		}

		@Test
		@DisplayName("A key narrowing covers another only when it holds every wanted key")
		void shouldCompareKeySetsPerName() {
			final ReferenceDecodeCoverage wide = ReferenceDecodeCoverage.of(
				Set.of(), Map.of("products", new int[]{1, 2, 3, 4})
			);
			assertTrue(wide.covers(ReferenceDecodeCoverage.of(Set.of(), Map.of("products", new int[]{2, 4}))));
			// 5 was never read, so the enrichment must go back to storage
			assertFalse(wide.covers(ReferenceDecodeCoverage.of(Set.of(), Map.of("products", new int[]{4, 5}))));
		}

		@Test
		@DisplayName("Containment is checked per name, not on the union of all keys")
		void shouldNotLeakKeysAcrossNames() {
			final ReferenceDecodeCoverage coverage = ReferenceDecodeCoverage.of(
				Set.of(), Map.of("products", new int[]{1, 2})
			);
			// the same keys under a different name are NOT covered - the pair (name, key) is the unit
			assertFalse(coverage.covers(ReferenceDecodeCoverage.of(Set.of(), Map.of("categories", new int[]{1, 2}))));
		}

		@Test
		@DisplayName("A coverage covers itself")
		void shouldCoverItself() {
			final ReferenceDecodeCoverage coverage = ReferenceDecodeCoverage.of(
				Set.of("categories"), Map.of("products", new int[]{1, 2})
			);
			assertTrue(coverage.covers(coverage));
		}
	}

	@Nested
	@DisplayName("Key set algebra")
	class KeySetAlgebra {

		@Test
		@DisplayName("A union of two sorted key sets stays sorted and free of duplicates")
		void shouldUnionSortedKeys() {
			assertArrayEquals(
				new int[]{1, 2, 3, 4, 5},
				ReferenceDecodeCoverage.unionSortedKeys(new int[]{1, 3, 5}, new int[]{2, 4})
			);
			assertArrayEquals(
				new int[]{1, 2, 3},
				ReferenceDecodeCoverage.unionSortedKeys(new int[]{1, 2, 3}, new int[]{1, 2, 3})
			);
			assertArrayEquals(
				new int[]{7},
				ReferenceDecodeCoverage.unionSortedKeys(new int[0], new int[]{7})
			);
			assertArrayEquals(
				new int[0],
				ReferenceDecodeCoverage.unionSortedKeys(new int[0], new int[0])
			);
		}

		@Test
		@DisplayName("Two narrowings are the same only when they bind the same names to the same keys")
		void shouldCompareNarrowingsByContent() {
			assertTrue(
				ReferenceDecodeCoverage.sameNarrowing(
					Map.of("products", new int[]{1, 2}),
					Map.of("products", new int[]{1, 2})
				)
			);
			assertFalse(
				ReferenceDecodeCoverage.sameNarrowing(
					Map.of("products", new int[]{1, 2}),
					Map.of("products", new int[]{1, 3})
				)
			);
			assertFalse(
				ReferenceDecodeCoverage.sameNarrowing(
					Map.of("products", new int[]{1, 2}),
					Map.of("categories", new int[]{1, 2})
				)
			);
			assertFalse(
				ReferenceDecodeCoverage.sameNarrowing(
					Map.of("products", new int[]{1, 2}),
					Map.of("products", new int[]{1, 2}, "categories", new int[]{3})
				)
			);
		}
	}

	@Nested
	@DisplayName("Immutability and identity")
	class ImmutabilityAndIdentity {

		@Test
		@DisplayName("Keys are sorted on the way in, so an unsorted caller cannot break the binary search")
		void shouldSortKeysOnConstruction() {
			final ReferenceDecodeCoverage coverage = ReferenceDecodeCoverage.of(
				Set.of(), Map.of("products", new int[]{30, 10, 20})
			);
			assertArrayEquals(new int[]{10, 20, 30}, coverage.getNamesDecodedByKey().get("products"));
			assertTrue(coverage.isReferenceDecoded("products", 10));
			assertTrue(coverage.isReferenceDecoded("products", 20));
			assertTrue(coverage.isReferenceDecoded("products", 30));
		}

		@Test
		@DisplayName("Mutating the array the caller passed in does not change the coverage")
		void shouldDefensivelyCopyIncomingKeys() {
			final int[] callerOwned = {1, 2, 3};
			final ReferenceDecodeCoverage coverage = ReferenceDecodeCoverage.of(
				Set.of(), Map.of("products", callerOwned)
			);
			callerOwned[0] = 99;
			assertTrue(coverage.isReferenceDecoded("products", 1));
			assertFalse(coverage.isReferenceDecoded("products", 99));
		}

		@Test
		@DisplayName("Equality and hash code are by content, including the key arrays")
		void shouldCompareByContent() {
			final ReferenceDecodeCoverage left = ReferenceDecodeCoverage.of(
				Set.of("categories"), Map.of("products", new int[]{1, 2})
			);
			final ReferenceDecodeCoverage right = ReferenceDecodeCoverage.of(
				Set.of("categories"), Map.of("products", new int[]{1, 2})
			);
			assertEquals(left, right);
			// this is the part a default record equals() would get wrong - int[] compares by identity
			assertEquals(left.hashCode(), right.hashCode());

			final ReferenceDecodeCoverage differentKeys = ReferenceDecodeCoverage.of(
				Set.of("categories"), Map.of("products", new int[]{1, 3})
			);
			assertNotEquals(left, differentKeys);
		}

		@Test
		@DisplayName("Hash code does not depend on the iteration order of the narrowed names")
		void shouldHashIndependentlyOfEntryOrder() {
			final java.util.LinkedHashMap<String, int[]> oneOrder = new java.util.LinkedHashMap<>();
			oneOrder.put("products", new int[]{1});
			oneOrder.put("categories", new int[]{2});
			final java.util.LinkedHashMap<String, int[]> otherOrder = new java.util.LinkedHashMap<>();
			otherOrder.put("categories", new int[]{2});
			otherOrder.put("products", new int[]{1});

			final ReferenceDecodeCoverage left = ReferenceDecodeCoverage.of(Set.of(), oneOrder);
			final ReferenceDecodeCoverage right = ReferenceDecodeCoverage.of(Set.of(), otherOrder);
			assertEquals(left, right);
			assertEquals(left.hashCode(), right.hashCode());
		}
	}
}
