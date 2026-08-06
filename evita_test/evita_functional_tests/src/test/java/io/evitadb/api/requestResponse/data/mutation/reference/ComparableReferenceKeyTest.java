/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.requestResponse.data.mutation.reference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.REFERENCE;

/**
 * Tests for {@link ComparableReferenceKey}, pinning down the equivalence rule that
 * {@link ComparableReferenceKey#isEquivalent(ReferenceKey, ReferenceKey)} and
 * {@link ComparableReferenceKey#containsEquivalent(Iterable, ReferenceKey)} apply: two keys with the same
 * {@link ReferenceKey#referenceName()} and {@link ReferenceKey#primaryKey()} are equivalent whenever either side
 * is {@link ReferenceKey#isUnknownReference() unknown} (internal PK exactly zero) - a narrower carve-out than
 * "either side is new" (negative internal PK), which does not trigger it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("ComparableReferenceKey")
@Tag(CONTRACT)
@Tag(REFERENCE)
class ComparableReferenceKeyTest {

	@Nested
	@DisplayName("isEquivalent")
	class IsEquivalent {

		@Test
		@DisplayName("should be equivalent when both sides are known and identical")
		void shouldBeEquivalentWhenBothKnownAndIdentical() {
			final ReferenceKey a = new ReferenceKey("brand", 10, 100);
			final ReferenceKey b = new ReferenceKey("brand", 10, 100);

			assertTrue(ComparableReferenceKey.isEquivalent(a, b));
		}

		@Test
		@DisplayName("should not be equivalent when both sides are known but internal primary keys differ")
		void shouldNotBeEquivalentWhenBothKnownButInternalPrimaryKeysDiffer() {
			final ReferenceKey a = new ReferenceKey("brand", 10, 100);
			final ReferenceKey b = new ReferenceKey("brand", 10, 200);

			assertFalse(ComparableReferenceKey.isEquivalent(a, b));
		}

		@Test
		@DisplayName("should be equivalent when one side is unknown regardless of the other side's internal primary key")
		void shouldBeEquivalentWhenOneSideIsUnknown() {
			final ReferenceKey unknown = new ReferenceKey("brand", 10);
			final ReferenceKey known = new ReferenceKey("brand", 10, 100);

			assertTrue(ComparableReferenceKey.isEquivalent(unknown, known));
			assertTrue(ComparableReferenceKey.isEquivalent(known, unknown));
		}

		@Test
		@DisplayName("should be equivalent when both sides are unknown")
		void shouldBeEquivalentWhenBothSidesAreUnknown() {
			final ReferenceKey a = new ReferenceKey("brand", 10);
			final ReferenceKey b = new ReferenceKey("brand", 10);

			assertTrue(ComparableReferenceKey.isEquivalent(a, b));
		}

		@Test
		@DisplayName("should not be equivalent when reference names differ")
		void shouldNotBeEquivalentWhenReferenceNamesDiffer() {
			final ReferenceKey a = new ReferenceKey("brand", 10);
			final ReferenceKey b = new ReferenceKey("category", 10);

			assertFalse(ComparableReferenceKey.isEquivalent(a, b));
		}

		@Test
		@DisplayName("should not be equivalent when primary keys differ")
		void shouldNotBeEquivalentWhenPrimaryKeysDiffer() {
			final ReferenceKey a = new ReferenceKey("brand", 10);
			final ReferenceKey b = new ReferenceKey("brand", 20);

			assertFalse(ComparableReferenceKey.isEquivalent(a, b));
		}

		@Test
		@DisplayName("should not be equivalent when both sides are new with different internal primary keys")
		void shouldNotBeEquivalentWhenBothAreNewWithDifferentInternalPrimaryKeys() {
			// a negative internal PK means "new", which is a different state than "unknown" (zero) -
			// neither side counts as isUnknownReference() here, so the internal PK is compared after all
			final ReferenceKey a = new ReferenceKey("brand", 10, -3);
			final ReferenceKey b = new ReferenceKey("brand", 10, -7);

			assertFalse(ComparableReferenceKey.isEquivalent(a, b));
		}

		@Test
		@DisplayName("should not be equivalent when one side is new and the other is known with a different internal primary key")
		void shouldNotBeEquivalentWhenOneIsNewAndOtherIsKnownWithDifferentInternalPrimaryKeys() {
			// "new" (negative internal PK) is not "unknown" (zero internal PK) - the carve-out does not apply,
			// so this is the discriminating case between the two concepts
			final ReferenceKey newReference = new ReferenceKey("brand", 10, -3);
			final ReferenceKey knownReference = new ReferenceKey("brand", 10, 100);

			assertFalse(ComparableReferenceKey.isEquivalent(newReference, knownReference));
			assertFalse(ComparableReferenceKey.isEquivalent(knownReference, newReference));
		}
	}

	@Nested
	@DisplayName("containsEquivalent")
	class ContainsEquivalent {

		@Test
		@DisplayName("should return false for an empty iterable")
		void shouldReturnFalseForEmptyIterable() {
			assertFalse(
				ComparableReferenceKey.containsEquivalent(
					Collections.emptyList(),
					new ReferenceKey("brand", 10)
				)
			);
		}

		@Test
		@DisplayName("should return true when the iterable contains an equivalent key")
		void shouldReturnTrueWhenIterableContainsEquivalentKey() {
			final List<ComparableReferenceKey> keys = List.of(
				new ComparableReferenceKey(new ReferenceKey("category", 1, 50)),
				new ComparableReferenceKey(new ReferenceKey("brand", 10, 100))
			);

			assertTrue(
				ComparableReferenceKey.containsEquivalent(keys, new ReferenceKey("brand", 10, 100))
			);
		}

		@Test
		@DisplayName("should return false when the iterable does not contain an equivalent key")
		void shouldReturnFalseWhenIterableDoesNotContainEquivalentKey() {
			final List<ComparableReferenceKey> keys = List.of(
				new ComparableReferenceKey(new ReferenceKey("category", 1, 50)),
				new ComparableReferenceKey(new ReferenceKey("brand", 20, 100))
			);

			assertFalse(
				ComparableReferenceKey.containsEquivalent(keys, new ReferenceKey("brand", 10, 100))
			);
		}

		@Test
		@DisplayName("should match an unknown search key against a known candidate in the iterable")
		void shouldMatchUnknownSearchKeyAgainstKnownCandidateInIterable() {
			final List<ComparableReferenceKey> keys = List.of(
				new ComparableReferenceKey(new ReferenceKey("brand", 10, 100))
			);

			assertTrue(
				ComparableReferenceKey.containsEquivalent(keys, new ReferenceKey("brand", 10))
			);
		}

		@Test
		@DisplayName("should not match a new search key against a differently-keyed known candidate in the iterable")
		void shouldNotMatchNewSearchKeyAgainstDifferentlyKeyedKnownCandidateInIterable() {
			final List<ComparableReferenceKey> keys = List.of(
				new ComparableReferenceKey(new ReferenceKey("brand", 10, 100))
			);

			assertFalse(
				ComparableReferenceKey.containsEquivalent(keys, new ReferenceKey("brand", 10, -3))
			);
		}
	}

	@Nested
	@DisplayName("equals")
	class Equals {

		@Test
		@DisplayName("should delegate to isEquivalent for the unknown-vs-known carve-out")
		void shouldDelegateToIsEquivalentForUnknownVsKnownCarveOut() {
			final ComparableReferenceKey unknown = new ComparableReferenceKey(new ReferenceKey("brand", 10));
			final ComparableReferenceKey known = new ComparableReferenceKey(new ReferenceKey("brand", 10, 100));

			assertTrue(unknown.equals(known));
			assertTrue(known.equals(unknown));
		}

		@Test
		@DisplayName("should return false when compared to null")
		void shouldReturnFalseWhenComparedToNull() {
			final ComparableReferenceKey key = new ComparableReferenceKey(new ReferenceKey("brand", 10));

			assertFalse(key.equals(null));
		}

		@Test
		@DisplayName("should return false when compared to a different type")
		void shouldReturnFalseWhenComparedToDifferentType() {
			final ComparableReferenceKey key = new ComparableReferenceKey(new ReferenceKey("brand", 10));

			//noinspection AssertBetweenInconvertibleTypes
			assertFalse(key.equals("brand: 10"));
		}
	}
}
