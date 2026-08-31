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

package io.evitadb.index.trigram;

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;

import static io.evitadb.index.IndexHeapSizeAssertions.assertMatchesMeasuredHeap;
import static io.evitadb.index.IndexHeapSizeAssertions.readField;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertSavepointCommitKeeps;
import static io.evitadb.utils.AssertionUtils.assertSavepointRollbackRestores;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@link TrigramIndex} structure itself: that a value's trigrams post against its value id and stop
 * doing so when the value dies, that the posting representation switches between its two forms at the thresholds it
 * claims to, that the key table grows and reclaims dead keys, and that the whole thing behaves transactionally —
 * a writer sees its own writes, a reader of the published version does not, and a rollback leaves nothing behind.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@DisplayName("Trigram substring index")
class TrigramIndexTest {

	/**
	 * The attribute every fixture in this class indexes.
	 */
	private static final AttributeIndexKey ATTRIBUTE_KEY = new AttributeIndexKey(null, "name", null);

	/**
	 * @return a fresh, empty index
	 */
	@Nonnull
	private static TrigramIndex emptyIndex() {
		return new TrigramIndex(ATTRIBUTE_KEY);
	}

	/**
	 * Builds an index holding `values`, giving the first one the value id `1`, the second `2` and so on — the same
	 * monotonic order the shared value tree's allocator would hand out.
	 *
	 * @param values the values to index
	 * @return the populated index
	 */
	@Nonnull
	private static TrigramIndex indexOf(@Nonnull String... values) {
		final TrigramIndex index = emptyIndex();
		for (int i = 0; i < values.length; i++) {
			index.valueCreated(i + 1, values[i]);
		}
		return index;
	}

	/**
	 * @param text the three characters of the trigram
	 * @return the packed key they form
	 */
	private static long trigram(@Nonnull String text) {
		return TrigramCodec.pack(text.charAt(0), text.charAt(1), text.charAt(2));
	}

	/**
	 * Renders `ordinal` as six letters in base 26, so consecutive ordinals differ in their LEADING characters and
	 * every value contributes trigrams almost nothing else does. A decimal suffix would not: `value-1` through
	 * `value-2000` share nearly all their trigrams and a fixture built from them never grows past one leaf block.
	 *
	 * @param ordinal the value's ordinal
	 * @return a six-letter value distinct for every ordinal
	 */
	@Nonnull
	private static String distinctValue(int ordinal) {
		final StringBuilder value = new StringBuilder(6);
		int remainder = ordinal;
		for (int position = 0; position < 6; position++) {
			value.append((char) ('a' + remainder % 26));
			remainder /= 26;
		}
		return value.toString();
	}

	/**
	 * The trigram every value built by {@link #sharedTrigramValue(int)} carries, whatever its ordinal. Chosen so
	 * that {@link #distinctValue(int)} cannot also produce it — its letters need an ordinal far above any fixture
	 * here to line up.
	 */
	private static final String SHARED_TRIGRAM = "qzx";

	/**
	 * A value carrying {@link #SHARED_TRIGRAM} on top of the trigrams {@link #distinctValue(int)} makes unique to
	 * it, so one fixture holds a posting large enough to be a bitmap alongside many that stay compact.
	 *
	 * @param ordinal the value's ordinal
	 * @return the value
	 */
	@Nonnull
	private static String sharedTrigramValue(int ordinal) {
		return SHARED_TRIGRAM + distinctValue(ordinal);
	}

	/**
	 * Reaches past the index's façade to the posting itself, so a test can assert WHICH of the two representations
	 * it is in — something {@link TrigramIndex#getValueIdsOf(long)} deliberately hides by normalizing both to a
	 * {@link io.evitadb.index.bitmap.Bitmap}.
	 *
	 * @param index   the index to read from
	 * @param trigram the packed trigram
	 * @return the raw posting, `null` when the trigram holds none
	 */
	@Nullable
	private static Object postingOf(@Nonnull TrigramIndex index, long trigram) {
		return ((TrigramPostingStore) Objects.requireNonNull(readField(index, "store"))).get(trigram);
	}

	/**
	 * Builds a tree carrying value ids and fills it with `count` values from {@link #sharedTrigramValue(int)},
	 * inserted in DESCENDING ordinal order.
	 *
	 * The descending insertion is the point: a tree allocates value ids in the order values arrive, but
	 * {@link InvertedIndex#forEachValueId} later walks them in COMPARATOR order, so a rebuild meets the ids
	 * scrambled relative to the walk. A fixture inserted in ascending order would hand the rebuild ids that happen
	 * to be sorted already and would pass even if the rebuild never ordered anything.
	 *
	 * @param count      how many values to index
	 * @param maintained the index kept up to date incrementally alongside the tree
	 * @return the populated tree
	 */
	@Nonnull
	private static InvertedIndex treeWithDecorrelatedValueIds(int count, @Nonnull TrigramIndex maintained) {
		final InvertedIndex tree = new InvertedIndex(
			String.class, FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder(), 0
		);
		tree.attachValueIdConsumer(TrigramIndex.VALUE_ID_CONSUMER_NAME);
		for (int ordinal = count; ordinal >= 1; ordinal--) {
			tree.addRecord(sharedTrigramValue(ordinal), count - ordinal + 1, maintained);
		}
		return tree;
	}

	@Nested
	@DisplayName("a value's trigrams follow the value")
	class Maintenance {

		@Test
		@DisplayName("every trigram of a new value posts against its id")
		void shouldPostEveryTrigramOfANewValue() {
			final TrigramIndex index = indexOf("abcd");
			assertArrayEquals(new int[]{1}, index.getValueIdsOf(trigram("abc")).getArray());
			assertArrayEquals(new int[]{1}, index.getValueIdsOf(trigram("bcd")).getArray());
			assertEquals(2, index.getTrigramCount());
			assertFalse(index.isEmpty());
		}

		@Test
		@DisplayName("a trigram two values share posts against both")
		void shouldShareATrigramBetweenValues() {
			final TrigramIndex index = indexOf("abcd", "xabc");
			assertArrayEquals(new int[]{1, 2}, index.getValueIdsOf(trigram("abc")).getArray());
			assertArrayEquals(new int[]{1}, index.getValueIdsOf(trigram("bcd")).getArray());
			assertArrayEquals(new int[]{2}, index.getValueIdsOf(trigram("xab")).getArray());
		}

		@Test
		@DisplayName("a value below the indexable length posts nothing at all")
		void shouldPostNothingForAShortValue() {
			final TrigramIndex index = indexOf("ab");
			assertEquals(0, index.getTrigramCount());
			assertTrue(index.isEmpty());
		}

		@Test
		@DisplayName("a trigram nothing contains answers empty rather than null")
		void shouldAnswerEmptyForAnAbsentTrigram() {
			final TrigramIndex index = indexOf("abcd");
			assertEquals(0, index.cardinalityOf(trigram("zzz")));
			assertEquals(0, index.getValueIdsOf(trigram("zzz")).getArray().length);
		}

		@Test
		@DisplayName("a value that dies leaves every trigram it contributed to")
		void shouldDropADeadValueFromEveryTrigram() {
			final TrigramIndex index = indexOf("abcd");
			index.valueRemoved(1, "abcd");
			assertEquals(0, index.cardinalityOf(trigram("abc")));
			assertEquals(0, index.cardinalityOf(trigram("bcd")));
			assertTrue(index.isEmpty());
		}

		@Test
		@DisplayName("a shared trigram survives the death of one of its values")
		void shouldKeepASharedTrigramAliveAfterOneValueDies() {
			final TrigramIndex index = indexOf("abcd", "xabc");
			index.valueRemoved(1, "abcd");
			assertArrayEquals(new int[]{2}, index.getValueIdsOf(trigram("abc")).getArray());
			assertEquals(0, index.cardinalityOf(trigram("bcd")));
		}

		@Test
		@DisplayName("removing a value the index never saw is refused as a divergence")
		void shouldRefuseRemovingAValueItNeverSaw() {
			// the index and the shared value tree are maintained by the same write, so a value dying that was never
			// born means they have drifted apart - which would silently under-report on every later query
			final TrigramIndex index = indexOf("abcd");
			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> index.valueRemoved(2, "wxyz")
			);
			assertTrue(
				error.getPrivateMessage().contains("diverged"),
				"the refusal must name the divergence, but was: " + error.getPrivateMessage()
			);
		}

		@Test
		@DisplayName("removing an id that is not in a posting it should be in is refused")
		void shouldRefuseRemovingAnIdAbsentFromItsPosting() {
			final TrigramIndex index = indexOf("abcd");
			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> index.valueRemoved(9, "abcd")
			);
			assertTrue(
				error.getPrivateMessage().contains("abc"),
				"the refusal must name the trigram, but was: " + error.getPrivateMessage()
			);
		}

		@Test
		@DisplayName("a trigram with no posting at all is diagnosed as that, not as a missing id")
		void shouldNameTheMissingTrigramWhenItsPostingIsGone() {
			// the two divergences have different causes - the whole trigram key is gone, versus one id missing from a
			// live posting - and an operator reading the message can only look in the right half of the structure if
			// it tells them apart. Both are still refused, and both still throw the same type
			final TrigramIndex index = indexOf("abcd");
			final GenericEvitaInternalError noPosting = assertThrows(
				GenericEvitaInternalError.class,
				() -> index.valueRemoved(2, "wxyz")
			);
			final GenericEvitaInternalError noId = assertThrows(
				GenericEvitaInternalError.class,
				() -> index.valueRemoved(9, "abcd")
			);
			assertTrue(
				noPosting.getPrivateMessage().contains("wxy")
					&& noPosting.getPrivateMessage().contains("holds no posting at all"),
				"an absent trigram must be named as one, but was: " + noPosting.getPrivateMessage()
			);
			assertFalse(
				noPosting.getPrivateMessage().contains("absent from the posting"),
				"and must not claim a posting exists to be absent from: " + noPosting.getPrivateMessage()
			);
			assertTrue(
				noId.getPrivateMessage().contains("absent from the posting"),
				"a live posting missing one id keeps its own diagnosis, but was: " + noId.getPrivateMessage()
			);
		}

	}

	@Nested
	@DisplayName("the posting switches representation at its thresholds")
	class PostingRepresentation {

		@Test
		@DisplayName("a posting stays compact up to the threshold and promotes above it")
		void shouldPromoteAboveTheThreshold() {
			final TrigramPostingStore store = new TrigramPostingStore();
			final long key = trigram("abc");
			Object posting = null;
			for (int valueId = 1; valueId <= TrigramPostings.SMALL_POSTING_THRESHOLD; valueId++) {
				posting = TrigramPostings.add(posting, valueId);
			}
			assertTrue(posting instanceof int[], "at the threshold the posting must still be the compact form");
			posting = TrigramPostings.add(posting, TrigramPostings.SMALL_POSTING_THRESHOLD + 1);
			assertFalse(posting instanceof int[], "one past the threshold the posting must be a bitmap");
			store.put(key, posting);
			assertEquals(TrigramPostings.SMALL_POSTING_THRESHOLD + 1, TrigramPostings.cardinality(store.get(key)));
		}

		@Test
		@DisplayName("a bitmap posting demotes only at half the threshold, so the boundary cannot thrash")
		void shouldDemoteOnlyAtHalfTheThreshold() {
			Object posting = null;
			for (int valueId = 1; valueId <= TrigramPostings.SMALL_POSTING_THRESHOLD + 1; valueId++) {
				posting = TrigramPostings.add(posting, valueId);
			}
			// removing one member takes it back to the promotion threshold - and it must NOT demote there, or a value
			// oscillating across the boundary would rebuild the representation on every write
			posting = TrigramPostings.remove(posting, 1, trigram("abc"));
			assertFalse(posting instanceof int[], "demoting at the promotion threshold would thrash the boundary");
			for (int valueId = 2; valueId <= TrigramPostings.SMALL_POSTING_DEMOTION_THRESHOLD + 1; valueId++) {
				posting = TrigramPostings.remove(posting, valueId, trigram("abc"));
			}
			assertTrue(posting instanceof int[], "at half the threshold the posting must be back in compact form");
			assertEquals(TrigramPostings.SMALL_POSTING_DEMOTION_THRESHOLD, TrigramPostings.cardinality(posting));
		}

		@Test
		@DisplayName("removing an absent id from a bitmap posting is refused as loudly as from a compact one")
		void shouldRefuseRemovingAnAbsentIdFromABitmapPosting() {
			// the two representations must fail identically, or a divergence would be caught on small attributes and
			// silently tolerated on exactly the large ones where it matters most
			Object posting = null;
			for (int valueId = 1; valueId <= TrigramPostings.SMALL_POSTING_THRESHOLD + 1; valueId++) {
				posting = TrigramPostings.add(posting, valueId);
			}
			assertFalse(posting instanceof int[], "the fixture must have promoted to a bitmap");
			final Object promoted = posting;
			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> TrigramPostings.remove(promoted, 9999, trigram("abc"))
			);
			assertTrue(
				error.getPrivateMessage().contains("diverged"),
				"the refusal must name the divergence, but was: " + error.getPrivateMessage()
			);
		}

		@Test
		@DisplayName("both representations answer the same members")
		void shouldAnswerTheSameMembersInBothRepresentations() {
			final TrigramIndex compact = emptyIndex();
			final TrigramIndex promoted = emptyIndex();
			for (int valueId = 1; valueId <= TrigramPostings.SMALL_POSTING_THRESHOLD + 10; valueId++) {
				promoted.valueCreated(valueId, "abc");
				if (valueId <= 3) {
					compact.valueCreated(valueId, "abc");
				}
			}
			assertArrayEquals(new int[]{1, 2, 3}, compact.getValueIdsOf(trigram("abc")).getArray());
			assertEquals(TrigramPostings.SMALL_POSTING_THRESHOLD + 10, promoted.cardinalityOf(trigram("abc")));
			assertEquals(
				TrigramPostings.SMALL_POSTING_THRESHOLD + 10,
				promoted.getValueIdsOf(trigram("abc")).getArray().length
			);
		}

		@Test
		@DisplayName("a posting emptied by its last removal is no membership at all")
		void shouldTreatAnEmptiedPostingAsNoMembership() {
			// the store turns this posting into a real key deletion rather than parking it, so neither arm below is
			// reachable through the index itself. The heap one is the load-bearing half: the emptied posting is one
			// array shared by the whole JVM that no index owns, and charging it would inflate every index's reported
			// figure by a constant for every posting that ever died
			final Object emptied = TrigramPostings.remove(TrigramPostings.add(null, 1), 1, trigram("abc"));

			assertSame(TrigramPostings.EMPTY_POSTING, emptied);
			assertEquals(0, TrigramPostings.cardinality(emptied));
			assertSame(EmptyBitmap.INSTANCE, TrigramPostings.asBitmap(emptied));
			assertEquals(0L, TrigramPostings.heapSizeInBytes(emptied));
		}

		@Test
		@DisplayName("an intersection led by an eroded bitmap still accepts a small posting behind it")
		void shouldIntersectABitmapLedPatternWhoseLaterPostingIsStillCompact() {
			// The two thresholds differ ON PURPOSE - a posting promotes at SMALL_POSTING_THRESHOLD but demotes only
			// at half of it - so representation is NOT a function of cardinality: across 65..128 a posting may be
			// either form. This builds exactly that overlap and asks for an intersection over it.
			//
			// CALIBRATION: reverting `intersectFromBitmapPosting` to cast every later posting to a bitmap makes this
			// throw ClassCastException. It is the whole point of the test - the old code inferred the later
			// postings' representation from the cheapest one's, which this state disproves.
			final TrigramIndex index = emptyIndex();

			// `abcz` posts against `abc`: promote past the threshold, then erode back to 100 - above the demotion
			// threshold, so the posting stays a bitmap it no longer needs to be
			final int promoted = TrigramPostings.SMALL_POSTING_THRESHOLD + 1;
			for (int valueId = 1; valueId <= promoted; valueId++) {
				index.valueCreated(valueId, "abcz");
			}
			for (int valueId = 101; valueId <= promoted; valueId++) {
				index.valueRemoved(valueId, "abcz");
			}

			// `zbcd` posts against `bcd`: a compact posting that is LARGER than the eroded bitmap but never promoted
			final int compactCardinality = TrigramPostings.SMALL_POSTING_THRESHOLD - 8;
			for (int valueId = 1000; valueId < 1000 + compactCardinality; valueId++) {
				index.valueCreated(valueId, "zbcd");
			}

			assertEquals(
				100, index.cardinalityOf(trigram("abc")),
				"the eroded posting must sit above the demotion threshold"
			);
			assertEquals(compactCardinality, index.cardinalityOf(trigram("bcd")));
			assertTrue(
				compactCardinality > 100,
				"the compact posting must be the DEARER of the two, so ordering puts the bitmap first and the "
					+ "bitmap branch is the one that has to cope with it"
			);

			// `abcd` draws exactly those two trigrams; no value holds both, so the honest answer is empty
			assertArrayEquals(
				new int[0], index.resolveCandidateValueIds(TrigramCodec.extractUniqueTrigrams("abcd"))
			);

			// and the same shape must still find a real intersection rather than merely not throwing
			index.valueCreated(2000, "abcd");
			assertArrayEquals(
				new int[]{2000}, index.resolveCandidateValueIds(TrigramCodec.extractUniqueTrigrams("abcd"))
			);
		}

	}

	@Nested
	@DisplayName("the key table holds exactly the live trigrams")
	class KeyTable {

		@Test
		@DisplayName("the table holds every key it was given, however many")
		void shouldHoldEveryKey() {
			// enough keys to force the tree past a single leaf and grow an internal level, which is where a
			// mis-routed insert would start losing keys
			final TrigramPostingStore store = new TrigramPostingStore();
			final int keyCount = 2_000;
			for (int i = 0; i < keyCount; i++) {
				store.put(i, TrigramPostings.add(null, i + 1));
			}
			assertEquals(keyCount, store.liveKeyCount());
			for (int i = 0; i < keyCount; i++) {
				assertArrayEquals(new int[]{i + 1}, TrigramPostings.asBitmap(store.get(i)).getArray());
			}
		}

		@Test
		@DisplayName("a trigram that lost its last value id leaves the table entirely")
		void shouldRemoveAKeyThatLostItsLastValueId() {
			// the key is DELETED rather than parked holding an empty posting, which is what keeps the key count equal
			// to the number of trigrams some value actually contains - and what a lookup relies on to answer absent
			final TrigramPostingStore store = new TrigramPostingStore();
			for (int i = 0; i < 12; i++) {
				store.put(i, TrigramPostings.add(null, 1));
			}
			assertEquals(12, store.liveKeyCount());

			for (int i = 0; i < 12; i++) {
				store.put(i, TrigramPostings.remove(store.get(i), 1, i));
			}

			assertEquals(0, store.liveKeyCount());
			for (int i = 0; i < 12; i++) {
				assertNull(store.get(i), "a trigram nothing contains must not be found at all");
			}
		}

		@Test
		@DisplayName("a key removed and given back again answers with its new posting")
		void shouldReuseAKeyThatCameBack() {
			final TrigramPostingStore store = new TrigramPostingStore();
			store.put(42L, TrigramPostings.add(null, 1));
			store.put(42L, TrigramPostings.remove(store.get(42L), 1, 42L));
			assertNull(store.get(42L));

			store.put(42L, TrigramPostings.add(null, 7));

			assertEquals(1, store.liveKeyCount());
			assertArrayEquals(new int[]{7}, TrigramPostings.asBitmap(store.get(42L)).getArray());
		}

	}

	@Nested
	@DisplayName("the index is transactional")
	@Tag(TRANSACTION)
	class TransactionalBehaviour {

		@Test
		@DisplayName("a writer sees its own writes, and nobody else does")
		void shouldIsolateWritesInsideATransaction() {
			final TrigramIndex index = indexOf("abcd");
			final int publishedTrigramCount = index.getTrigramCount();
			assertStateAfterRollback(
				index,
				original -> {
					original.valueCreated(2, "xabc");
					// read-your-writes: the value the transaction just created must resolve for it
					assertArrayEquals(new int[]{1, 2}, original.getValueIdsOf(trigram("abc")).getArray());
					assertArrayEquals(new int[]{2}, original.getValueIdsOf(trigram("xab")).getArray());
					// the counter is transaction-aware too - it is the posting tree's own size, not a cached total.
					// `xabc` brings one key the published index does not have, `abc` being shared with `abcd`
					assertEquals(publishedTrigramCount + 1, original.getTrigramCount());
				},
				(original, committed) -> {
					// the transaction was rolled back, so the published index is exactly what it was
					assertArrayEquals(new int[]{1}, original.getValueIdsOf(trigram("abc")).getArray());
					assertEquals(0, original.cardinalityOf(trigram("xab")));
					assertEquals(publishedTrigramCount, original.getTrigramCount());
				}
			);
		}

		@Test
		@DisplayName("a commit publishes the writes into a new index version")
		void shouldPublishWritesOnCommit() {
			final TrigramIndex index = indexOf("abcd");
			assertStateAfterCommit(
				index,
				original -> original.valueCreated(2, "xabc"),
				(original, committed) -> {
					assertNotSame(original, committed, "a touched index must become a new version");
					assertArrayEquals(new int[]{1, 2}, committed.getValueIdsOf(trigram("abc")).getArray());
					assertArrayEquals(new int[]{2}, committed.getValueIdsOf(trigram("xab")).getArray());
					// the version the readers of the previous catalog version hold is untouched, which is the whole
					// reason the merge copies the table instead of mutating it
					assertArrayEquals(new int[]{1}, original.getValueIdsOf(trigram("abc")).getArray());
					assertEquals(0, original.cardinalityOf(trigram("xab")));
				}
			);
		}

		@Test
		@DisplayName("a commit publishes a removal too")
		void shouldPublishRemovalsOnCommit() {
			final TrigramIndex index = indexOf("abcd", "xabc");
			assertStateAfterCommit(
				index,
				original -> original.valueRemoved(1, "abcd"),
				(original, committed) -> {
					assertArrayEquals(new int[]{2}, committed.getValueIdsOf(trigram("abc")).getArray());
					assertEquals(0, committed.cardinalityOf(trigram("bcd")));
					// again: the previous version still answers as it did
					assertArrayEquals(new int[]{1, 2}, original.getValueIdsOf(trigram("abc")).getArray());
					assertArrayEquals(new int[]{1}, original.getValueIdsOf(trigram("bcd")).getArray());
				}
			);
		}

		@Test
		@DisplayName("a transaction mutating a BITMAP posting leaves the published one untouched")
		void shouldNotLeakABitmapPostingMutationIntoThePreviousVersion() {
			// the compact form is copied by every mutation anyway, so only a bitmap - which is mutated in place - can
			// reveal whether the diff layer really takes its own copy before writing
			final TrigramIndex index = emptyIndex();
			for (int valueId = 1; valueId <= TrigramPostings.SMALL_POSTING_THRESHOLD + 1; valueId++) {
				index.valueCreated(valueId, "abc");
			}
			final int publishedCardinality = index.cardinalityOf(trigram("abc"));

			assertStateAfterCommit(
				index,
				original -> original.valueCreated(publishedCardinality + 1, "abc"),
				(original, committed) -> {
					assertEquals(publishedCardinality + 1, committed.cardinalityOf(trigram("abc")));
					assertEquals(
						publishedCardinality, original.cardinalityOf(trigram("abc")),
						"the published bitmap must not have been mutated in place"
					);
				}
			);
		}

		@Test
		@DisplayName("an index no transaction touched is carried forward by reference")
		void shouldCarryAnUntouchedIndexForwardByReference() {
			// identity is what a dependent cache keys on, so an untouched index must keep it rather than become an
			// equal-but-different instance that invalidates every cached formula built over it.
			//
			// The first commit is not incidental: the fixture writes OUTSIDE any transaction, which is the warm-up
			// bulk path, and those writes leave the dirty flag set on the instance itself. The merge copy is born
			// with a fresh flag, so the warm-up hand-off costs exactly one rebuild and every later untouched commit
			// carries by reference - the steady state this pins. `InvertedIndex` behaves identically, for the same
			// reason: nothing in production ever calls `resetDirty`.
			final TrigramIndex[] afterWarmUp = new TrigramIndex[1];
			assertStateAfterCommit(
				indexOf("abcd"),
				original -> {
					// deliberately no write at all
				},
				(original, committed) -> afterWarmUp[0] = committed
			);

			assertStateAfterCommit(
				afterWarmUp[0],
				original -> {
					// deliberately no write at all
				},
				(original, committed) -> assertSame(original, committed)
			);
		}

		@Test
		@DisplayName("a rolled-back savepoint restores the postings it found")
		void shouldRestoreThePostingsOfARolledBackSavepoint() {
			// `bcd` belongs in the oracle rather than merely in the fixture: removing value id 1 empties its posting,
			// which DELETES the key from the table - so it is the only trigram whose restore exercises the
			// deleted-then-put-back path rather than a plain content change
			final TrigramIndex index = indexOf("abcd");
			assertSavepointRollbackRestores(
				index,
				original -> original.valueCreated(2, "xabc"),
				original -> original.getValueIdsOf(trigram("abc")).getArray().length
					+ ":" + original.getValueIdsOf(trigram("bcd")).getArray().length
					+ ":" + original.getValueIdsOf(trigram("xab")).getArray().length
					+ ":" + original.getValueIdsOf(trigram("qrs")).getArray().length,
				original -> {
					original.valueCreated(3, "qrsabc");
					original.valueRemoved(1, "abcd");
				}
			);
		}

		@Test
		@DisplayName("a rolled-back savepoint restores a BITMAP posting's content, not just its reference")
		void shouldRestoreABitmapPostingOfARolledBackSavepoint() {
			// the compact arm is copied by every mutation anyway, so a savepoint over small postings proves only that
			// the tree restores REFERENCES. A bitmap posting is the one that would come back with its content already
			// changed if the mutators wrote into the published instance
			final TrigramIndex index = emptyIndex();
			for (int valueId = 1; valueId <= TrigramPostings.SMALL_POSTING_THRESHOLD + 20; valueId++) {
				index.valueCreated(valueId, "abcd");
			}
			assertSavepointRollbackRestores(
				index,
				original -> original.valueCreated(9_001, "abcd"),
				original -> original.cardinalityOf(trigram("abc")),
				original -> {
					original.valueRemoved(9_001, "abcd");
					original.valueRemoved(1, "abcd");
				}
			);
		}

		@Test
		@DisplayName("a rolled-back savepoint restores a posting whose REPRESENTATION it changed")
		void shouldRestoreTheRepresentationOfARolledBackSavepoint() {
			// the bitmap savepoint above stays a bitmap throughout, so it never crosses the demotion threshold. Here
			// the savepoint takes the posting down through it, which REPLACES the stored bitmap with a compact `int[]`
			// holding a different object entirely - the case where restoring a reference and restoring content are
			// visibly two different things. The oracle reads the whole member list rather than its size, so a restore
			// that put the reference back over changed content could not pass by matching cardinalities
			final TrigramIndex index = emptyIndex();
			final int membersToRemove = TrigramPostings.SMALL_POSTING_THRESHOLD + 1
				- TrigramPostings.SMALL_POSTING_DEMOTION_THRESHOLD;
			for (int valueId = 1; valueId <= TrigramPostings.SMALL_POSTING_THRESHOLD + 1; valueId++) {
				index.valueCreated(valueId, "abcd");
			}
			assertSavepointRollbackRestores(
				index,
				original -> {
					// deliberately no write before the savepoint - the fixture already sits one member above the
					// promotion threshold, which is the shape under test
				},
				original -> Arrays.toString(original.getValueIdsOf(trigram("abc")).getArray()),
				original -> {
					for (int valueId = 1; valueId <= membersToRemove; valueId++) {
						original.valueRemoved(valueId, "abcd");
					}
					assertEquals(
						TrigramPostings.SMALL_POSTING_DEMOTION_THRESHOLD,
						original.cardinalityOf(trigram("abc")),
						"the savepoint must have taken the posting across the demotion threshold"
					);
				}
			);
		}

		@Test
		@DisplayName("two successive commits leave the first version's bitmap posting untouched")
		void shouldNotLeakAPostingMutationAcrossTwoCommits() {
			// the posting tree shares untouched nodes - and therefore posting REFERENCES - across every version, so
			// the aliasing a copy-on-write mistake produces reaches from v0 to v2 just as it does from v0 to v1. One
			// generation is not enough to prove the sharing is safe
			final TrigramIndex v0 = emptyIndex();
			for (int valueId = 1; valueId <= TrigramPostings.SMALL_POSTING_THRESHOLD + 1; valueId++) {
				v0.valueCreated(valueId, "abcd");
			}
			final int v0Cardinality = v0.cardinalityOf(trigram("abc"));

			final TrigramIndex[] v1 = new TrigramIndex[1];
			assertStateAfterCommit(
				v0,
				original -> original.valueCreated(v0Cardinality + 1, "abcd"),
				(original, committed) -> v1[0] = committed
			);
			assertStateAfterCommit(
				v1[0],
				original -> original.valueCreated(v0Cardinality + 2, "abcd"),
				(original, committed) -> {
					assertEquals(v0Cardinality + 2, committed.cardinalityOf(trigram("abc")));
					assertEquals(v0Cardinality + 1, original.cardinalityOf(trigram("abc")));
					assertEquals(
						v0Cardinality, v0.cardinalityOf(trigram("abc")),
						"the version two commits back must not have been written through"
					);
				}
			);
		}

		@Test
		@DisplayName("a committed savepoint keeps the postings it made")
		void shouldKeepThePostingsOfACommittedSavepoint() {
			final TrigramIndex index = indexOf("abcd");
			assertSavepointCommitKeeps(
				index,
				original -> original.valueCreated(2, "xabc"),
				original -> original.getValueIdsOf(trigram("abc")).getArray().length
					+ ":" + original.getValueIdsOf(trigram("qrs")).getArray().length,
				original -> original.valueCreated(3, "qrsabc")
			);
		}

	}

	@Nested
	@DisplayName("the index prices itself exactly")
	class HeapAccounting {

		/**
		 * The fields the JOL walk must not charge this index for: the attribute key belongs to the map that files
		 * the index under it, and the posting tree's transactional-layer wrapper is a lambda — a hidden class whose
		 * field offsets JOL cannot read, which is why the tree is built without one in the first place.
		 */
		private static final String[] NOT_OWNED = {
			"attributeIndexKey", "store.postings.transactionalLayerWrapper"
		};

		@Test
		@DisplayName("an empty index reports exactly what a JOL walk finds")
		void shouldPriceAnEmptyIndexExactly() {
			final TrigramIndex index = emptyIndex();
			assertMatchesMeasuredHeap(index.getHeapSizeInBytes(), index, NOT_OWNED);
		}

		@Test
		@DisplayName("an index holding compact postings reports exactly what a JOL walk finds")
		void shouldPriceCompactPostingsExactly() {
			final TrigramIndex index = indexOf("abcd", "xabc", "hello world");
			assertMatchesMeasuredHeap(index.getHeapSizeInBytes(), index, NOT_OWNED);
		}

		@Test
		@DisplayName("an index holding a promoted bitmap posting reports exactly what a JOL walk finds")
		void shouldPriceABitmapPostingExactly() {
			// the bitmap arm of the arithmetic is a different formula from the array arm, and it is the one that grows
			// with the data - so it is the one whose drift would be invisible until an operator reads a wrong figure
			final TrigramIndex index = emptyIndex();
			for (int valueId = 1; valueId <= TrigramPostings.SMALL_POSTING_THRESHOLD + 50; valueId++) {
				index.valueCreated(valueId, "abcd");
			}
			assertMatchesMeasuredHeap(index.getHeapSizeInBytes(), index, NOT_OWNED);
		}

		@Test
		@DisplayName("a tree deep enough to carry internal nodes reports exactly what a JOL walk finds")
		void shouldPriceAMultiLevelTreeExactly() {
			// past one leaf block the node graph gains an internal level, whose arrays the arithmetic prices by a
			// different path than a leaf's - a single-leaf fixture would leave that path unmeasured
			final TrigramIndex index = emptyIndex();
			for (int valueId = 1; valueId <= 2_000; valueId++) {
				index.valueCreated(valueId, distinctValue(valueId));
			}
			assertTrue(index.getTrigramCount() > 512, "the fixture must outgrow a single leaf block");
			assertMatchesMeasuredHeap(index.getHeapSizeInBytes(), index, NOT_OWNED);
		}

		@Test
		@DisplayName("an index rebuilt from the shared value tree reports exactly what a JOL walk finds")
		void shouldPriceARebuiltIndexExactly() {
			// every fixture above is grown one membership at a time, but the arithmetic charges a Roaring posting at
			// its ALLOCATED array lengths and reads the shared spine's length separately - and the bulk writer the
			// load path builds through leaves different slack there than repeated add() does. So the rebuilt shape is
			// reachable, priced by the same formula, and would otherwise go unmeasured
			final TrigramIndex maintained = emptyIndex();
			final InvertedIndex tree = treeWithDecorrelatedValueIds(
				4 * TrigramPostings.SMALL_POSTING_THRESHOLD, maintained
			);

			final TrigramIndex rebuilt = TrigramIndex.rebuildFrom(ATTRIBUTE_KEY, tree);

			assertFalse(
				postingOf(rebuilt, trigram(SHARED_TRIGRAM)) instanceof int[],
				"the fixture must carry at least one bitmap posting, which is the arm that grows with the data"
			);
			assertMatchesMeasuredHeap(rebuilt.getHeapSizeInBytes(), rebuilt, NOT_OWNED);
		}

	}

	@Nested
	@DisplayName("the index is derived, and comes back from the tree")
	class Rebuild {

		@Test
		@DisplayName("a rebuild from the shared value tree answers exactly as the maintained index did")
		void shouldRebuildTheSameIndexFromTheSharedValueTree() {
			// this is the whole load path: nothing of the index is persisted, so a restart must be indistinguishable
			// from having maintained it all along
			final InvertedIndex tree = new InvertedIndex(
				String.class, FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder(), 0
			);
			tree.attachValueIdConsumer(TrigramIndex.VALUE_ID_CONSUMER_NAME);
			final TrigramIndex maintained = emptyIndex();
			final String[] values = {"abcd", "xabc", "hello world", "ab"};
			for (int i = 0; i < values.length; i++) {
				tree.addRecord(values[i], i + 1, maintained);
			}

			final TrigramIndex rebuilt = TrigramIndex.rebuildFrom(ATTRIBUTE_KEY, tree);

			assertEquals(maintained.getTrigramCount(), rebuilt.getTrigramCount());
			for (final String value : values) {
				for (final long key : TrigramCodec.extractUniqueTrigrams(value)) {
					assertArrayEquals(
						maintained.getValueIdsOf(key).getArray(),
						rebuilt.getValueIdsOf(key).getArray(),
						() -> "trigram `" + TrigramCodec.toDisplayString(key) + "` must resolve identically"
					);
				}
			}
		}

		@Test
		@DisplayName("a rebuild from a tree without value ids is refused")
		void shouldRefuseRebuildingFromATreeWithoutValueIds() {
			final InvertedIndex tree = new InvertedIndex(
				String.class, FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder(), 0
			);
			tree.addRecord("abcd", 1);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> TrigramIndex.rebuildFrom(ATTRIBUTE_KEY, tree)
			);
		}

		@Test
		@DisplayName("a rebuild from a capable but empty tree yields an empty index")
		void shouldRebuildAnEmptyIndexFromAnEmptyTree() {
			// separates the two shapes a rebuild can be handed: "no ids at all" is a disagreement between the schema
			// and the persisted state and is refused above, while "ids but no values yet" is an ordinary declared
			// attribute nobody has written to, and must simply come back empty
			final InvertedIndex tree = new InvertedIndex(
				String.class, FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder(), 0
			);
			tree.attachValueIdConsumer(TrigramIndex.VALUE_ID_CONSUMER_NAME);

			final TrigramIndex rebuilt = TrigramIndex.rebuildFrom(ATTRIBUTE_KEY, tree);

			assertTrue(rebuilt.isEmpty());
			assertEquals(0, rebuilt.getTrigramCount());
		}

		@Test
		@DisplayName("a rebuild reproduces bitmap postings, in the representation the write path would have grown")
		void shouldRebuildBitmapPostingsIdenticallyToTheMaintainedIndex() {
			// the fixtures above hold four values apiece, so between them they never leave the compact
			// representation and never see a value id arrive out of order - which is most of what a real load does
			final int count = 4 * TrigramPostings.SMALL_POSTING_THRESHOLD;
			final TrigramIndex maintained = emptyIndex();
			final InvertedIndex tree = treeWithDecorrelatedValueIds(count, maintained);

			final TrigramIndex rebuilt = TrigramIndex.rebuildFrom(ATTRIBUTE_KEY, tree);

			final long shared = trigram(SHARED_TRIGRAM);
			assertFalse(
				postingOf(maintained, shared) instanceof int[],
				"the fixture must have promoted the shared posting past the compact form"
			);
			assertFalse(
				postingOf(rebuilt, shared) instanceof int[],
				"a rebuilt posting must land in the same representation the write path would have grown"
			);
			assertEquals(maintained.getTrigramCount(), rebuilt.getTrigramCount());
			for (int ordinal = 1; ordinal <= count; ordinal++) {
				for (final long key : TrigramCodec.extractUniqueTrigrams(sharedTrigramValue(ordinal))) {
					assertArrayEquals(
						maintained.getValueIdsOf(key).getArray(),
						rebuilt.getValueIdsOf(key).getArray(),
						() -> "trigram `" + TrigramCodec.toDisplayString(key) + "` must resolve identically"
					);
				}
			}
		}

		@Test
		@DisplayName("a compact posting comes back ascending though the walk hands its ids over scrambled")
		void shouldRebuildAscendingCompactPostingsFromAScrambledWalk() {
			// the compact representation IS a sorted int[] - TrigramPostings binary-searches it - so a buffer left
			// in walk order would produce an index that silently answers wrong. This pins the ordering step as
			// load-bearing rather than incidental
			final int count = TrigramPostings.SMALL_POSTING_THRESHOLD;
			final TrigramIndex maintained = emptyIndex();
			final InvertedIndex tree = treeWithDecorrelatedValueIds(count, maintained);

			final TrigramIndex rebuilt = TrigramIndex.rebuildFrom(ATTRIBUTE_KEY, tree);

			final long shared = trigram(SHARED_TRIGRAM);
			final Object posting = postingOf(rebuilt, shared);
			assertTrue(posting instanceof int[], "at the threshold the posting must still be the compact form");
			final int[] members = (int[]) posting;
			assertEquals(count, members.length);
			for (int i = 1; i < members.length; i++) {
				final int index = i;
				assertTrue(
					members[index - 1] < members[index],
					() -> "members must be strictly ascending, but " + members[index - 1] +
						" precedes " + members[index]
				);
			}
			assertArrayEquals(maintained.getValueIdsOf(shared).getArray(), rebuilt.getValueIdsOf(shared).getArray());
		}

		@Test
		@DisplayName("a rebuild switches representation at the same threshold the write path does")
		void shouldRebuildAtTheRepresentationBoundary() {
			// the bulk build restates the promotion threshold rather than arriving at it by repeated adds, so the
			// two statements of it have to be pinned together or nothing stops them drifting apart
			final TrigramIndex atThreshold = emptyIndex();
			final TrigramIndex justAbove = emptyIndex();
			final long shared = trigram(SHARED_TRIGRAM);

			final TrigramIndex rebuiltAtThreshold = TrigramIndex.rebuildFrom(
				ATTRIBUTE_KEY,
				treeWithDecorrelatedValueIds(TrigramPostings.SMALL_POSTING_THRESHOLD, atThreshold)
			);
			final TrigramIndex rebuiltJustAbove = TrigramIndex.rebuildFrom(
				ATTRIBUTE_KEY,
				treeWithDecorrelatedValueIds(TrigramPostings.SMALL_POSTING_THRESHOLD + 1, justAbove)
			);

			assertTrue(
				postingOf(atThreshold, shared) instanceof int[],
				"the maintained index must still be compact at the threshold"
			);
			assertTrue(
				postingOf(rebuiltAtThreshold, shared) instanceof int[],
				"and so must the rebuilt one"
			);
			assertFalse(
				postingOf(justAbove, shared) instanceof int[],
				"the maintained index must have promoted one past the threshold"
			);
			assertFalse(
				postingOf(rebuiltJustAbove, shared) instanceof int[],
				"and so must the rebuilt one"
			);
		}

		@Test
		@DisplayName("a posting spanning more than one Roaring container comes back whole")
		void shouldRebuildAPostingSpanningSeveralRoaringContainers() {
			// every other fixture here holds a few hundred values, so its widest posting fits inside the FIRST
			// 65 536-wide Roaring container and the bulk writer never once flushes a chunk and advances its key. That
			// is the branch that decides which container a posting's members land in, so a suite that never crosses
			// the boundary would pass just as happily if the writer filed everything under key zero
			final int count = 65_537;
			final TrigramIndex maintained = emptyIndex();
			final InvertedIndex tree = treeWithDecorrelatedValueIds(count, maintained);

			final TrigramIndex rebuilt = TrigramIndex.rebuildFrom(ATTRIBUTE_KEY, tree);

			final long shared = trigram(SHARED_TRIGRAM);
			final int[] members = rebuilt.getValueIdsOf(shared).getArray();
			assertEquals(count, members.length);
			assertTrue(
				members[members.length - 1] > 0xFFFF,
				() -> "the fixture must reach past the first container, but its highest value id is " +
					members[members.length - 1]
			);
			assertArrayEquals(
				maintained.getValueIdsOf(shared).getArray(),
				members,
				"a posting split across containers must resolve exactly as the maintained one does"
			);
			assertEquals(maintained.getTrigramCount(), rebuilt.getTrigramCount());
		}

	}

}
