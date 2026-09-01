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
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.Random;

import static io.evitadb.index.IndexHeapSizeAssertions.assertMatchesMeasuredHeap;
import static io.evitadb.index.IndexHeapSizeAssertions.readField;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertSavepointCommitKeeps;
import static io.evitadb.utils.AssertionUtils.assertSavepointRollbackRestores;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;

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
			assertInstanceOf(int[].class, posting, "at the threshold the posting must still be the compact form");
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
			assertInstanceOf(int[].class, posting, "at half the threshold the posting must be back in compact form");
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

		@Test
		@DisplayName("a bitmap-led intersection leaves every posting it read exactly as it found it")
		void shouldNotMutateThePostingsAnIntersectionReads() {
			// Postings are shared BY REFERENCE with every index version that can still read them, so the intersection
			// may own only what it allocated itself. `intersectFromBitmapPosting` folds the second posting in with the
			// STATIC `and` - documented to share nothing with either operand - and only then switches to the in-place
			// `and` on the result it now owns.
			//
			// CALIBRATION: seeding the accumulator with `postings[0]` and folding in place from the first posting
			// onwards - the obvious-looking simplification - reddens this test, because `abc` would come back holding
			// the 150 survivors instead of the 180 ids it was built with.
			final TrigramIndex index = emptyIndex();

			// `abc`, `bcd` and `cde` are the trigrams of the pattern below. Each is given members the others lack, so
			// the answer is a STRICT subset of the cheapest posting - without that, an in-place fold would write back
			// the same contents and the corruption would be invisible.
			for (int valueId = 1; valueId <= 150; valueId++) {
				index.valueCreated(valueId, "abcde");
			}
			for (int valueId = 300; valueId < 330; valueId++) {
				index.valueCreated(valueId, "abcz");
			}
			for (int valueId = 400; valueId < 500; valueId++) {
				index.valueCreated(valueId, "zbcdy");
			}
			for (int valueId = 500; valueId < 600; valueId++) {
				index.valueCreated(valueId, "zcdey");
			}

			final long abc = trigram("abc");
			final long bcd = trigram("bcd");
			final long cde = trigram("cde");

			// the fixture only tests what it is meant to if the cheapest posting is a BITMAP - the small-posting path
			// copies before it compacts and was never at risk - and if the accumulator stays above the demotion
			// threshold long enough for the in-place fold to run at all
			assertInstanceOf(
				PersistentRoaringBitmap.class, postingOf(index, abc),
				"the cheapest posting must have promoted, or the bitmap branch is never entered"
			);
			assertEquals(180, index.cardinalityOf(abc), "the cheapest posting must hold ids the answer excludes");
			assertEquals(250, index.cardinalityOf(bcd));
			assertEquals(250, index.cardinalityOf(cde));

			final int[] abcBefore = TrigramPostings.asBitmap(postingOf(index, abc)).getArray();
			final int[] bcdBefore = TrigramPostings.asBitmap(postingOf(index, bcd)).getArray();
			final int[] cdeBefore = TrigramPostings.asBitmap(postingOf(index, cde)).getArray();

			final int[] expected = new int[150];
			for (int i = 0; i < expected.length; i++) {
				expected[i] = i + 1;
			}
			assertArrayEquals(
				expected, index.resolveCandidateValueIds(TrigramCodec.extractUniqueTrigrams("abcde")),
				"the intersection must exclude the 150 ids that carry only one or two of the three trigrams"
			);

			assertArrayEquals(abcBefore, TrigramPostings.asBitmap(postingOf(index, abc)).getArray(),
				"the cheapest posting was folded into the answer, not overwritten by it");
			assertArrayEquals(bcdBefore, TrigramPostings.asBitmap(postingOf(index, bcd)).getArray());
			assertArrayEquals(cdeBefore, TrigramPostings.asBitmap(postingOf(index, cde)).getArray());

			// an independent statement of the same invariant: a corrupted posting answers a repeated query differently
			assertArrayEquals(
				expected, index.resolveCandidateValueIds(TrigramCodec.extractUniqueTrigrams("abcde")),
				"a second identical query must answer identically, which a mutated posting could not"
			);
		}

		@Test
		@DisplayName("a one-trigram pattern over a promoted posting answers it whole, in an array the caller owns")
		void shouldAnswerAOneTrigramPatternFromTheBitmapPostingAlone() {
			// A single trigram has nothing to intersect against, so the bitmap branch materializes the posting and
			// returns that - there is no accumulator for the query to own. What still has to hold is that the array
			// IS the caller's: the returned array is handed on to verification and to the caller beyond it, and if it
			// ever aliased the index's own posting, every version sharing that posting would be exposed to whoever
			// writes into it next.
			final TrigramIndex index = emptyIndex();
			final int promoted = TrigramPostings.SMALL_POSTING_THRESHOLD + 20;
			for (int valueId = 1; valueId <= promoted; valueId++) {
				index.valueCreated(valueId, "abcz");
			}
			final long abc = trigram("abc");
			assertInstanceOf(
				PersistentRoaringBitmap.class, postingOf(index, abc),
				"the posting must have promoted, or the one-trigram case of the BITMAP branch is never entered"
			);

			final int[] expected = new int[promoted];
			for (int i = 0; i < expected.length; i++) {
				expected[i] = i + 1;
			}
			final int[] candidates = index.resolveCandidateValueIds(TrigramCodec.extractUniqueTrigrams("abc"));
			assertArrayEquals(expected, candidates, "one trigram filters nothing, so the whole posting is the answer");

			// the answer is the caller's array to do as it likes with; the index must not notice
			Arrays.fill(candidates, -1);
			assertArrayEquals(
				expected, index.getValueIdsOf(abc).getArray(),
				"writing into the returned array must not reach the index's own posting"
			);
			assertArrayEquals(
				expected, index.resolveCandidateValueIds(TrigramCodec.extractUniqueTrigrams("abc")),
				"and a repeated query must answer identically, which an aliased posting could not"
			);
		}

		@Test
		@DisplayName("a bitmap-led intersection whose two cheapest postings share nothing stops without the third")
		void shouldAbandonABitmapLedIntersectionOnceTheAccumulatorIsEmpty() {
			// The intersection is run to completion on purpose - stopping early trades a Roaring `and` for a
			// verification pass, and verification is the expensive half - but an accumulator that has reached ZERO
			// has nothing left for a later posting to remove, so that one case does return early. Three postings are
			// needed to reach it at all: with two, the loop never runs and the empty answer comes back through the
			// ordinary materialization instead.
			final TrigramIndex index = emptyIndex();

			// `abc`, `bcd` and `cde` are the trigrams of `abcde`; each is planted in its own disjoint id range, so
			// the two cheapest share NO id and the accumulator is empty before the third is ever consulted
			for (int valueId = 1; valueId <= 200; valueId++) {
				index.valueCreated(valueId, "abcz");
			}
			for (int valueId = 300; valueId < 600; valueId++) {
				index.valueCreated(valueId, "zbcdz");
			}
			for (int valueId = 700; valueId < 1100; valueId++) {
				index.valueCreated(valueId, "zcdez");
			}

			assertInstanceOf(
				PersistentRoaringBitmap.class, postingOf(index, trigram("abc")),
				"the cheapest posting must be a bitmap, or this is the small-posting path instead"
			);
			assertEquals(200, index.cardinalityOf(trigram("abc")));
			assertEquals(300, index.cardinalityOf(trigram("bcd")));
			assertEquals(400, index.cardinalityOf(trigram("cde")));

			assertArrayEquals(
				new int[0], index.resolveCandidateValueIds(TrigramCodec.extractUniqueTrigrams("abcde")),
				"no value holds all three trigrams, so the honest answer is empty"
			);
		}

		@Test
		@DisplayName("a bitmap accumulator narrowed past the threshold finishes on the small-posting path")
		void shouldDemoteANarrowedBitmapAccumulatorToTheSmallPath() {
			// Once the accumulator holds no more than the small-posting threshold it is materialized once and the
			// remaining postings are applied by membership probing instead of by further Roaring `and`s. That tail is
			// the cheaper one - a bitmap `and` costs container work proportional to the WIDER side however narrow the
			// accumulator has become, and the probing path consumes an `int[]` posting as it stands.
			final TrigramIndex index = emptyIndex();

			// `abcde` is what carries all three trigrams; the other three values each add bulk to exactly one of
			// them, so the postings order abc < bcd < cde and the first `and` lands on the 100 shared ids alone
			for (int valueId = 1; valueId <= 100; valueId++) {
				index.valueCreated(valueId, "abcde");
			}
			for (int valueId = 200; valueId < 400; valueId++) {
				index.valueCreated(valueId, "abcz");
			}
			for (int valueId = 400; valueId < 700; valueId++) {
				index.valueCreated(valueId, "zbcdy");
			}
			for (int valueId = 700; valueId < 1100; valueId++) {
				index.valueCreated(valueId, "zcdey");
			}

			final long abc = trigram("abc");
			final long bcd = trigram("bcd");
			final long cde = trigram("cde");
			assertInstanceOf(
				PersistentRoaringBitmap.class, postingOf(index, abc),
				"the cheapest posting must be a bitmap, or this is the small-posting path instead"
			);
			assertEquals(300, index.cardinalityOf(abc));
			assertEquals(400, index.cardinalityOf(bcd));
			assertEquals(500, index.cardinalityOf(cde));

			// THE DEMOTION PRECONDITION, stated rather than assumed: the two cheapest postings must intersect to
			// something non-empty but no wider than the threshold, or the accumulator never demotes and this test
			// quietly stops exercising the branch it is named for - which is exactly what a later change to the
			// threshold would do to it.
			final int[] bcdIds = index.getValueIdsOf(bcd).getArray();
			int shared = 0;
			for (final int candidate : index.getValueIdsOf(abc).getArray()) {
				if (Arrays.binarySearch(bcdIds, candidate) >= 0) {
					shared++;
				}
			}
			assertTrue(
				shared > 0 && shared <= TrigramPostings.SMALL_POSTING_THRESHOLD,
				"the two cheapest postings must intersect to between 1 and " + TrigramPostings.SMALL_POSTING_THRESHOLD
					+ " ids for the accumulator to demote, but they share " + shared
			);

			final int[] expected = new int[100];
			for (int i = 0; i < expected.length; i++) {
				expected[i] = i + 1;
			}
			assertArrayEquals(
				expected, index.resolveCandidateValueIds(TrigramCodec.extractUniqueTrigrams("abcde")),
				"the demoted tail must reach the same answer the bitmap fold would have"
			);

			// the demoted tail compacts an array in place, so it is exactly where a posting handed over by mistake
			// would be corrupted
			assertArrayEquals(
				new int[]{1, 2, 3}, Arrays.copyOf(index.getValueIdsOf(cde).getArray(), 3),
				"the postings the demoted tail probed must be exactly as it found them"
			);
			assertEquals(500, index.cardinalityOf(cde));
			assertEquals(400, index.cardinalityOf(bcd));
			assertEquals(300, index.cardinalityOf(abc));
		}

		@Test
		@DisplayName("a bitmap-led intersection over dense, multi-container postings leaves them untouched too")
		void shouldNotMutateDenseMultiContainerPostingsAnIntersectionReads() {
			// The ownership claim the sibling above defends is tested there for ArrayContainer against ArrayContainer
			// inside ONE Roaring container - the narrowest shape it has. Roaring switches a container to a bitmap
			// representation past 4096 members and files ids into a new container every 65 536, and the static `and`
			// has its own code path per container-pair shape. This fixture is dense enough for bitmap containers and
			// spread widely enough to span several container keys, so the intersection meets both.
			final TrigramIndex index = emptyIndex();

			// the shared prefix of the answer: 5 000 ids in the first container, well past the 4 096 members at which
			// Roaring stops storing a container as a sorted array
			for (int valueId = 1; valueId <= 5_000; valueId++) {
				index.valueCreated(valueId, "abcde");
			}
			// each trigram then gets bulk of its own, in a container key no other trigram writes into
			for (int valueId = 100_000; valueId < 105_000; valueId++) {
				index.valueCreated(valueId, "abcz");
			}
			for (int valueId = 200_000; valueId < 207_000; valueId++) {
				index.valueCreated(valueId, "zbcdy");
			}
			for (int valueId = 300_000; valueId < 309_000; valueId++) {
				index.valueCreated(valueId, "zcdey");
			}

			final long abc = trigram("abc");
			final long bcd = trigram("bcd");
			final long cde = trigram("cde");
			assertInstanceOf(
				PersistentRoaringBitmap.class, postingOf(index, abc),
				"the cheapest posting must be a bitmap, or the bitmap branch is never entered"
			);
			assertEquals(10_000, index.cardinalityOf(abc));
			assertEquals(12_000, index.cardinalityOf(bcd));
			assertEquals(14_000, index.cardinalityOf(cde));

			final int[] abcBefore = index.getValueIdsOf(abc).getArray();
			final int[] bcdBefore = index.getValueIdsOf(bcd).getArray();
			final int[] cdeBefore = index.getValueIdsOf(cde).getArray();
			assertTrue(
				abcBefore[abcBefore.length - 1] > 0xFFFF,
				"the cheapest posting must reach past the first container, or the multi-container claim is untested"
			);

			final int[] expected = new int[5_000];
			for (int i = 0; i < expected.length; i++) {
				expected[i] = i + 1;
			}
			assertArrayEquals(expected, index.resolveCandidateValueIds(TrigramCodec.extractUniqueTrigrams("abcde")));

			assertArrayEquals(abcBefore, index.getValueIdsOf(abc).getArray(),
				"the cheapest posting was folded into the answer, not overwritten by it");
			assertArrayEquals(bcdBefore, index.getValueIdsOf(bcd).getArray());
			assertArrayEquals(cdeBefore, index.getValueIdsOf(cde).getArray());
			assertArrayEquals(
				expected, index.resolveCandidateValueIds(TrigramCodec.extractUniqueTrigrams("abcde")),
				"a second identical query must answer identically, which a mutated posting could not"
			);
		}

		@Test
		@DisplayName("the intersection agrees with a brute-force one over randomized corpora, and writes into nothing")
		void shouldAgreeWithABruteForceIntersectionOverRandomizedCorpora() {
			// Each hand-built fixture above reaches ONE branch. This drives them together - the small-led chain, the
			// bitmap-led fold, the demotion into the small path, the empty accumulator, and the 65..128 window where a
			// posting may legitimately be either representation - against an oracle built from the index's own
			// per-trigram postings, which is the definition the intersection is supposed to implement.
			//
			// Every posting each query reads is snapshotted before and after, so an in-place corruption in a container
			// shape none of the hand-built fixtures thought of is caught here. The seed is fixed, so a failure names
			// the exact corpus it happened on.
			final Random rnd = new Random(20_260_831L);
			for (int round = 0; round < 20; round++) {
				final int valueCount = 40 + rnd.nextInt(400);
				final int alphabet = 3 + rnd.nextInt(3);
				final TrigramIndex index = emptyIndex();
				final String[] values = new String[valueCount];
				for (int i = 0; i < valueCount; i++) {
					values[i] = randomWord(rnd, alphabet, 4 + rnd.nextInt(5));
					index.valueCreated(i + 1, values[i]);
				}

				for (int probe = 0; probe < 10; probe++) {
					final String pattern = rnd.nextBoolean()
						? substringOf(rnd, values[rnd.nextInt(valueCount)])
						: randomWord(rnd, alphabet, 3 + rnd.nextInt(4));
					final long[] trigrams = TrigramCodec.extractUniqueTrigrams(pattern);
					final int[][] postingsBefore = new int[trigrams.length][];
					for (int i = 0; i < trigrams.length; i++) {
						postingsBefore[i] = index.getValueIdsOf(trigrams[i]).getArray();
					}
					final int[] expected = intersectAll(postingsBefore);
					final String context = "round " + round + ", pattern `" + pattern + "`";

					assertArrayEquals(expected, index.resolveCandidateValueIds(trigrams), context);
					assertArrayEquals(
						expected, index.resolveCandidateValueIds(trigrams),
						context + " - a repeated query answered differently, so something was written into"
					);
					for (int i = 0; i < trigrams.length; i++) {
						assertArrayEquals(
							postingsBefore[i], index.getValueIdsOf(trigrams[i]).getArray(),
							context + " - posting of `" + TrigramCodec.toDisplayString(trigrams[i])
								+ "` was written into"
						);
					}
				}
			}
		}

		/**
		 * @param rnd      the workload's RNG
		 * @param alphabet how many distinct letters the word may use - a small alphabet is what makes trigrams
		 *                 collide often enough for postings to grow and promote
		 * @param length   the word's length
		 * @return a random lower-case word
		 */
		@Nonnull
		private static String randomWord(@Nonnull Random rnd, int alphabet, int length) {
			final StringBuilder word = new StringBuilder(length);
			for (int i = 0; i < length; i++) {
				word.append((char) ('a' + rnd.nextInt(alphabet)));
			}
			return word.toString();
		}

		/**
		 * @param rnd   the workload's RNG
		 * @param value the value to cut from
		 * @return a substring of `value` at least one trigram wide, so it is a pattern the index can answer
		 */
		@Nonnull
		private static String substringOf(@Nonnull Random rnd, @Nonnull String value) {
			if (value.length() <= TrigramCodec.MINIMAL_INDEXABLE_LENGTH) {
				return value;
			}
			final int length = TrigramCodec.MINIMAL_INDEXABLE_LENGTH
				+ rnd.nextInt(value.length() - TrigramCodec.MINIMAL_INDEXABLE_LENGTH + 1);
			return value.substring(rnd.nextInt(value.length() - length + 1)).substring(0, length);
		}

		/**
		 * The definition the intersection implements, written the slow and obvious way: the ids every posting holds.
		 *
		 * @param postings the ascending postings to intersect
		 * @return the ascending ids common to all of them, empty when there are none or when there is no posting
		 */
		@Nonnull
		private static int[] intersectAll(@Nonnull int[][] postings) {
			if (postings.length == 0) {
				return new int[0];
			}
			int[] surviving = postings[0].clone();
			for (int i = 1; i < postings.length; i++) {
				int kept = 0;
				for (final int candidate : surviving) {
					if (Arrays.binarySearch(postings[i], candidate) >= 0) {
						surviving[kept++] = candidate;
					}
				}
				surviving = Arrays.copyOf(surviving, kept);
			}
			return surviving;
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
		@DisplayName("an intersection run on the committed version leaves the previous version's postings untouched")
		void shouldNotLeakAnIntersectionIntoThePreviousVersion() {
			// The accumulator is owned only from the static `and` onwards precisely because `postings[0]` is shared BY
			// REFERENCE with every index version that can still read it. That hazard is cross-version, and the
			// existing leak guard covers the WRITE path - a transaction mutating a posting. This is the READ path: a
			// commit that touched an unrelated trigram carries these postings forward by reference, so both versions
			// hold the very same bitmaps while the intersection runs on the newer one.
			final TrigramIndex index = emptyIndex();
			for (int valueId = 1; valueId <= 150; valueId++) {
				index.valueCreated(valueId, "abcde");
			}
			for (int valueId = 300; valueId < 330; valueId++) {
				index.valueCreated(valueId, "abcz");
			}
			for (int valueId = 400; valueId < 500; valueId++) {
				index.valueCreated(valueId, "zbcdy");
			}
			for (int valueId = 500; valueId < 600; valueId++) {
				index.valueCreated(valueId, "zcdey");
			}
			final long abc = trigram("abc");
			final long bcd = trigram("bcd");
			final long cde = trigram("cde");
			assertInstanceOf(
				PersistentRoaringBitmap.class, postingOf(index, abc),
				"the cheapest posting must be a bitmap, or the shared-by-reference hazard does not arise"
			);
			final int[] abcBefore = index.getValueIdsOf(abc).getArray();
			final int[] bcdBefore = index.getValueIdsOf(bcd).getArray();
			final int[] cdeBefore = index.getValueIdsOf(cde).getArray();

			assertStateAfterCommit(
				index,
				// a trigram sharing nothing with the pattern below, so the postings it reads are carried forward
				original -> original.valueCreated(9_000, "wxyz"),
				(original, committed) -> {
					assertSame(
						postingOf(original, abc), postingOf(committed, abc),
						"the untouched posting must have been carried forward by reference, or the two versions do "
							+ "not share the bitmap this test is about"
					);

					final int[] expected = new int[150];
					for (int i = 0; i < expected.length; i++) {
						expected[i] = i + 1;
					}
					assertArrayEquals(
						expected, committed.resolveCandidateValueIds(TrigramCodec.extractUniqueTrigrams("abcde")),
						"the newer version must answer the intersection correctly"
					);

					assertArrayEquals(
						abcBefore, original.getValueIdsOf(abc).getArray(),
						"a query on the newer version must not have written into a posting the older one still reads"
					);
					assertArrayEquals(bcdBefore, original.getValueIdsOf(bcd).getArray());
					assertArrayEquals(cdeBefore, original.getValueIdsOf(cde).getArray());
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
				Assertions::assertSame
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
			assertInstanceOf(int[].class, posting, "at the threshold the posting must still be the compact form");
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

			assertInstanceOf(
				int[].class, postingOf(atThreshold, shared),
				"the maintained index must still be compact at the threshold"
			);
			assertInstanceOf(int[].class, postingOf(rebuiltAtThreshold, shared), "and so must the rebuilt one");
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
