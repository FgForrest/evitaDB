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


package io.evitadb.index.attribute;

import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.range.RangePoint;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what a {@link FilterIndex} must do when ONE record's array attribute carries several elements that
 * canonicalize onto the SAME index key.
 *
 * # The invariant being pinned
 *
 * The bucket axis is a **set**: `InvertedIndex` holds one bitmap per key, so a record is in a bucket or it is
 * not — never in it twice. A write therefore has to say "here is the set of DISTINCT keys this record maps to",
 * and an array of raw elements is not that set until it has been folded.
 *
 * The two sides are asymmetric today, which is why only removal misbehaves: adding a record id to a bitmap twice
 * is a no-op, while removal asserts membership BEFORE it removes, so the second element of a colliding pair
 * finds the record already gone.
 *
 * # Why `equals` is the right measure of "one key"
 *
 * The index normalizes a value and then descends a tree ordered by its comparator, so bucket identity is really
 * `comparator.compare(normalizer(a), normalizer(b)) == 0`. The fold uses `equals` of the normalized values,
 * which is exact only because every comparator in play is consistent with equals — natural order always, and
 * `LocalizedStringComparator` because of the `compareTo` tie-break pinned by `CollatedKeyIdentityTest`. The
 * localized cases below keep that dependency visible from this side.
 *
 * # Why the range axis is in here too
 *
 * `addRecord` / `removeRecord` drive two structures in one call. The range companion already folds correctly —
 * `addRange` / `removeRange` consolidate the normalized ranges — so the fix must leave the two axes agreeing
 * about the same write. `Range-axis synchrony` is the nested class that attacks exactly that, because it is the
 * hazard the fix itself introduces rather than one it repairs.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("FilterIndex — array elements that fold onto one index key")
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@Tag(FILTER)
class FilterIndexArrayFoldTest {

	/** Precomposed `é` — one code point, U+00E9. */
	private static final String PRECOMPOSED = "café";
	/** NFD decomposition of the same word — `e` plus a combining acute accent. */
	private static final String DECOMPOSED = "café";
	/** A plain two-letter string, and the same string carrying a zero-width space a collator ignores entirely. */
	private static final String PLAIN = "ab";
	private static final String ZERO_WIDTH_SPACED = "a\u200Bb";
	private static final int RECORD = 1;
	private static final int OTHER_RECORD = 2;

	@Nested
	@DisplayName("Whole-value writes")
	class WholeValue {

		@Test
		@DisplayName("removing an array of two canonically-equivalent strings must not fail the index premise")
		void shouldRemoveAnArrayFoldingOntoOneStringKey() {
			final OwnerFilterIndex index = stringIndex();
			index.addRecord(RECORD, new String[]{PRECOMPOSED, DECOMPOSED});

			assertDoesNotThrow(
				() -> index.removeRecord(RECORD, new String[]{PRECOMPOSED, DECOMPOSED}),
				"both spellings share ONE bucket, so the record leaves it once - removing per raw element takes " +
					"the record out on the first and then cannot find it on the second"
			);
			assertTrue(index.isEmpty(), "the record held nothing else, so the index must be empty");
		}

		@Test
		@DisplayName("removing an array of two BigDecimals that scale onto one int key must not fail the premise")
		void shouldRemoveAnArrayFoldingOntoOneScaledKey() {
			final OwnerFilterIndex index = new OwnerFilterIndex(key("qty"), BigDecimal.class, 0);
			final BigDecimal[] colliding = {new BigDecimal("1.2"), new BigDecimal("1.4")};
			index.addRecord(RECORD, colliding);

			assertDoesNotThrow(() -> index.removeRecord(RECORD, colliding), "1.2 and 1.4 both scale to the key 1");
			assertTrue(index.isEmpty());
		}

		@Test
		@DisplayName("removing an array of two offsets denoting one instant must not fail the premise")
		void shouldRemoveAnArrayFoldingOntoOneInstant() {
			final OwnerFilterIndex index = new OwnerFilterIndex(key("stockedAt"), OffsetDateTime.class);
			final OffsetDateTime[] colliding = {
				OffsetDateTime.of(2026, 9, 21, 12, 0, 0, 0, ZoneOffset.ofHours(2)),
				OffsetDateTime.of(2026, 9, 21, 10, 0, 0, 0, ZoneOffset.UTC)
			};
			assertNotEquals(colliding[0], colliding[1], "premise: the two values must be DISTINCT to the API");
			index.addRecord(RECORD, colliding);

			assertDoesNotThrow(() -> index.removeRecord(RECORD, colliding), "the index key discards the offset");
			assertTrue(index.isEmpty());
		}

		@Test
		@DisplayName("a colliding array puts the record in ONE bucket, not two")
		void shouldPlaceACollidingArrayInASingleBucket() {
			final OwnerFilterIndex index = stringIndex();
			index.addRecord(RECORD, new String[]{PRECOMPOSED, DECOMPOSED});

			assertEquals(
				1, index.getDistinctValueCount(),
				"canonically-equivalent spellings are one entry in the shared value tree"
			);
			assertTrue(index.getRecordsEqualTo(PRECOMPOSED).contains(RECORD));
			assertTrue(index.getRecordsEqualTo(DECOMPOSED).contains(RECORD), "either spelling must find it");
		}

		@Test
		@DisplayName("a failing removal must not evict another record that shares the folded bucket")
		void shouldNotDisturbAnotherRecordSharingTheBucket() {
			final OwnerFilterIndex index = stringIndex();
			index.addRecord(RECORD, new String[]{PRECOMPOSED, DECOMPOSED});
			index.addRecord(OTHER_RECORD, new String[]{PRECOMPOSED});

			index.removeRecord(RECORD, new String[]{PRECOMPOSED, DECOMPOSED});

			assertFalse(index.getRecordsEqualTo(DECOMPOSED).contains(RECORD), "the record that left is gone");
			assertTrue(
				index.getRecordsEqualTo(DECOMPOSED).contains(OTHER_RECORD),
				"the bystander still holds the value and must survive the other record's departure"
			);
		}

		@Test
		@DisplayName("a LOCALIZED array folding onto one NFD key must not fail the premise either")
		void shouldRemoveAnArrayFoldingOntoOneKeyOnALocalizedIndex() {
			// the localized flavour has its own comparator, so it gets its own pass over the fold - the normalizer
			// is what merges these two, exactly as on the non-localized index
			final OwnerFilterIndex index = localizedStringIndex();
			index.addRecord(RECORD, new String[]{PRECOMPOSED, DECOMPOSED});
			assertEquals(1, index.getDistinctValueCount(), "premise: NFD merges the two spellings into one bucket");

			assertDoesNotThrow(() -> index.removeRecord(RECORD, new String[]{PRECOMPOSED, DECOMPOSED}));
			assertTrue(index.isEmpty());
		}

		@Test
		@DisplayName("two strings a bare COLLATOR would equate are NOT folded — they are separate buckets")
		void shouldKeepCollatorEquatedStringsApart() {
			// `LocalizedStringComparator` breaks the collation tie with `compareTo`, so bucket identity agrees with
			// equals and these two are two keys. This is what lets the fold measure identity with `equals` at all -
			// see CollatedKeyIdentityTest for the tie-break itself
			final OwnerFilterIndex index = localizedStringIndex();
			final String[] distinct = {PLAIN, ZERO_WIDTH_SPACED};
			assertNotEquals(distinct[0], distinct[1], "premise: the two values must be DISTINCT to the API");
			index.addRecord(RECORD, distinct);

			assertEquals(2, index.getDistinctValueCount(), "the fold must not merge what the tree keeps apart");
			assertDoesNotThrow(() -> index.removeRecord(RECORD, distinct));
			assertTrue(index.isEmpty());
		}

		@Test
		@DisplayName("negative control — a localized array of genuinely different strings keeps its buckets apart")
		void shouldKeepCollatedArrayElementsApartWhenTheyDiffer() {
			final OwnerFilterIndex index = localizedStringIndex();
			index.addRecord(RECORD, new String[]{"alpha", "beta"});

			assertEquals(
				2, index.getDistinctValueCount(),
				"a collator distinguishes these, so the fold must not touch them"
			);
			assertDoesNotThrow(() -> index.removeRecord(RECORD, new String[]{"alpha", "beta"}));
			assertTrue(index.isEmpty());
		}

		@Test
		@DisplayName("THREE elements folding onto one key round-trip, not just two")
		void shouldRemoveAnArrayOfThreeElementsFoldingOntoOneKey() {
			// the fold's bookkeeping is N-way, not pairwise: it keeps the accepted elements as a prefix of the
			// caller's array until the first collision, then compacts into a copy. Two elements exercise only the
			// FIRST collision; three exercise a collision against an already-compacted prefix
			final OwnerFilterIndex index = new OwnerFilterIndex(key("qty"), BigDecimal.class, 0);
			final BigDecimal[] colliding = {
				new BigDecimal("1.2"), new BigDecimal("1.4"), new BigDecimal("1.1")
			};
			index.addRecord(RECORD, colliding);
			assertEquals(1, index.getDistinctValueCount(), "all three scale to the key 1");

			assertDoesNotThrow(() -> index.removeRecord(RECORD, colliding));
			assertTrue(index.isEmpty());
		}

		@Test
		@DisplayName("collisions INTERLEAVED with distinct keys compact to the right survivors")
		void shouldFoldACollidingArrayInterleavedWithDistinctKeys() {
			// THIS is the case that discriminates, and element count is not what makes it so. Once the fold has
			// compacted, every further accepted element is written at an index that has drifted from its source
			// position - so only an array that ALTERNATES accepted and dropped elements exercises that write.
			// Proven: deleting the compacted-array write leaves this test the ONLY red one in the class, while
			// every two-element case, and even a four-element case where everything collides, stays green
			final OwnerFilterIndex index = stringIndex();
			final String[] mixed = {PRECOMPOSED, "alpha", DECOMPOSED, "beta", PRECOMPOSED, "alpha"};
			index.addRecord(RECORD, mixed);

			assertEquals(3, index.getDistinctValueCount(), "café, alpha and beta - three distinct keys");
			assertTrue(index.getRecordsEqualTo(DECOMPOSED).contains(RECORD), "the folded key kept the record");
			assertTrue(index.getRecordsEqualTo("alpha").contains(RECORD));
			assertTrue(index.getRecordsEqualTo("beta").contains(RECORD));

			assertDoesNotThrow(() -> index.removeRecord(RECORD, mixed));
			assertTrue(index.isEmpty(), "and every one of them leaves exactly once");
		}

		@Test
		@DisplayName("an array of FOUR elements all folding onto one key leaves the bucket once")
		void shouldRemoveAnArrayOfFourIdenticalKeys() {
			final OwnerFilterIndex index = stringIndex();
			final String[] allOneKey = {PRECOMPOSED, DECOMPOSED, PRECOMPOSED, DECOMPOSED};
			index.addRecord(RECORD, allOneKey);
			index.addRecord(OTHER_RECORD, new String[]{PRECOMPOSED});

			assertEquals(1, index.getDistinctValueCount());
			assertDoesNotThrow(() -> index.removeRecord(RECORD, allOneKey));
			assertTrue(
				index.getRecordsEqualTo(PRECOMPOSED).contains(OTHER_RECORD),
				"the bystander survives a four-way fold just as it survives a two-way one"
			);
		}

		@Test
		@DisplayName("negative control — an array whose elements do NOT collide keeps one bucket each")
		void shouldKeepNonCollidingArrayElementsApart() {
			final OwnerFilterIndex index = stringIndex();
			index.addRecord(RECORD, new String[]{"alpha", "beta"});

			assertEquals(2, index.getDistinctValueCount(), "distinct keys must never be folded together");
			assertDoesNotThrow(() -> index.removeRecord(RECORD, new String[]{"alpha", "beta"}));
			assertTrue(index.isEmpty());
		}
	}

	@Nested
	@DisplayName("Delta writes")
	class Delta {

		@Test
		@DisplayName("a delta array carrying two elements with the same index key is a caller error, reported as one")
		void shouldRejectACollidingRemoveDelta() {
			final OwnerFilterIndex index = stringIndex();
			index.addRecord(RECORD, new String[]{PRECOMPOSED});

			// a delta states WHICH keys are leaving the record entirely, so the same key twice is a contradiction -
			// folding it silently would accept a broken contract, and would still be wrong for a genuine partial
			// delta where the record keeps the bucket through an element the delta does not mention
			assertThrows(
				GenericEvitaInternalError.class,
				() -> index.removeRecordDelta(RECORD, new String[]{PRECOMPOSED, DECOMPOSED}),
				"the precondition must fail loudly rather than corrupt the bucket axis"
			);
		}

		@Test
		@DisplayName("an add delta carrying two elements with the same index key is reported the same way")
		void shouldRejectACollidingAddDelta() {
			final OwnerFilterIndex index = stringIndex();

			assertThrows(
				GenericEvitaInternalError.class,
				() -> index.addRecordDelta(RECORD, new String[]{PRECOMPOSED, DECOMPOSED}),
				"symmetry with the removal: a delta names distinct keys or it is malformed"
			);
		}

		@Test
		@DisplayName("a delta colliding on a LOCALIZED index is rejected on the same grounds")
		void shouldRejectACollidingRemoveDeltaOnALocalizedIndex() {
			final OwnerFilterIndex index = localizedStringIndex();
			index.addRecord(RECORD, new String[]{PRECOMPOSED});

			assertThrows(
				GenericEvitaInternalError.class,
				() -> index.removeRecordDelta(RECORD, new String[]{PRECOMPOSED, DECOMPOSED}),
				"the localized flavour carries the same premise as every other"
			);
		}

		@Test
		@DisplayName("negative control — a delta of two collator-equated strings is NOT a collision")
		void shouldAcceptADeltaOfCollatorEquatedStrings() {
			final OwnerFilterIndex index = localizedStringIndex();
			index.addRecord(RECORD, new String[]{PLAIN, ZERO_WIDTH_SPACED});

			assertDoesNotThrow(
				() -> index.removeRecordDelta(RECORD, new String[]{ZERO_WIDTH_SPACED}),
				"they are two buckets, so a delta naming one of them names a single distinct key"
			);
			assertEquals(1, index.getDistinctValueCount(), "only the delta's own key may leave");
		}

		@Test
		@DisplayName("negative control — a delta of distinct keys still applies on both sides")
		void shouldApplyADeltaOfDistinctKeys() {
			final OwnerFilterIndex index = stringIndex();
			index.addRecord(RECORD, new String[]{"alpha"});

			assertDoesNotThrow(() -> index.addRecordDelta(RECORD, new String[]{"beta"}));
			assertEquals(2, index.getDistinctValueCount());
			assertDoesNotThrow(() -> index.removeRecordDelta(RECORD, new String[]{"beta"}));
			assertEquals(1, index.getDistinctValueCount(), "only the delta's own key may leave");
		}
	}

	@Nested
	@DisplayName("Range-axis synchrony")
	class RangeAxis {

		@Test
		@DisplayName("a range array that rescales onto one bucket key round-trips on BOTH axes")
		void shouldLeaveBothAxesCleanForACollidingRangeArray() {
			// the same mathematical range written at two precisions: two DISTINCT stored values that rescale onto
			// one bucket key at the index scale. The bucket axis folds them; the range axis consolidates them - and
			// this is the hazard the fold itself introduces, because the two axes run in one call and must agree
			final OwnerFilterIndex index = new OwnerFilterIndex(key("validity"), BigDecimalNumberRange.class, 2);
			final BigDecimalNumberRange[] colliding = {
				BigDecimalNumberRange.between(new BigDecimal("1.5"), new BigDecimal("2.5")),
				BigDecimalNumberRange.between(new BigDecimal("1.50"), new BigDecimal("2.50"))
			};
			assertNotEquals(colliding[0], colliding[1], "premise: distinct on disk - NumberRange equality is scaled");

			final RangePoint<?>[] before = index.getRangeIndex().getRanges();
			index.addRecord(RECORD, colliding);
			assertDoesNotThrow(() -> index.removeRecord(RECORD, colliding), "neither axis may fail the round trip");

			assertTrue(index.isEmpty(), "the bucket axis must be empty again");
			assertArrayEquals(
				stringify(before), stringify(index.getRangeIndex().getRanges()),
				"and the range axis must retire exactly the thresholds it added - a fold applied to one axis but " +
					"not the other would leave a threshold behind"
			);
		}

		@Test
		@DisplayName("negative control — a range array of distinct ranges round-trips unchanged")
		void shouldRoundTripDistinctRanges() {
			final OwnerFilterIndex index = new OwnerFilterIndex(key("validity"), BigDecimalNumberRange.class, 2);
			final BigDecimalNumberRange[] distinct = {
				BigDecimalNumberRange.between(new BigDecimal("1.50"), new BigDecimal("2.50")),
				BigDecimalNumberRange.between(new BigDecimal("8.00"), new BigDecimal("9.00"))
			};
			final RangePoint<?>[] before = index.getRangeIndex().getRanges();

			index.addRecord(RECORD, distinct);
			assertEquals(2, index.getDistinctValueCount(), "distinct ranges keep their own buckets");
			index.removeRecord(RECORD, distinct);

			assertTrue(index.isEmpty());
			assertArrayEquals(stringify(before), stringify(index.getRangeIndex().getRanges()));
		}

		@Nonnull
		private String[] stringify(@Nonnull RangePoint<?>[] ranges) {
			final String[] result = new String[ranges.length];
			for (int i = 0; i < ranges.length; i++) {
				result[i] = String.valueOf(ranges[i]);
			}
			return result;
		}
	}

	@Nonnull
	private static OwnerFilterIndex stringIndex() {
		return new OwnerFilterIndex(key("labels"), String.class);
	}

	/**
	 * A localized String index - the one flavour whose value tree is ordered by a {@code Collator} rather than by
	 * natural order, so that bucket identity stops agreeing with `equals` of the normalized value.
	 */
	@Nonnull
	private static OwnerFilterIndex localizedStringIndex() {
		return new OwnerFilterIndex(
			new AttributeIndexKey(null, "labels", Locale.ENGLISH), String.class
		);
	}

	@Nonnull
	private static AttributeIndexKey key(@Nonnull String attributeName) {
		return new AttributeIndexKey(null, attributeName, null);
	}

}
