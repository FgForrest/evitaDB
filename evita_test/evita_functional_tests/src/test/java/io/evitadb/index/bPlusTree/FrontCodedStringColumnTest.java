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

package io.evitadb.index.bPlusTree;

import io.evitadb.comparator.LocalizedStringComparator;
import io.evitadb.utils.ArrayUtils.InsertionPosition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;

import static io.evitadb.index.bPlusTree.ValueColumnTestSupport.assertTreeMatchesOracle;
import static io.evitadb.index.bPlusTree.ValueColumnTestSupport.verifyConsistent;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the front-coded {@link FrontCodedStringColumn}: its array operations and {@link String} key round-trip are
 * proven equivalent to the boxed {@link BoxedObjectColumn} (including decode across restart-block boundaries, the
 * production varint length encoding for keys longer than 255 bytes, and multi-byte UTF-8 values); the
 * {@link ValueColumnFactory} selects it for every {@link String} key regardless of the comparator; an end-to-end
 * randomized workload on a String-keyed {@link TransactionalBucketBPlusTree} matches a {@link TreeMap} oracle in both
 * natural codepoint order and locale-collation order; and the column survives an MVCC commit / rollback that splits and
 * merges leaves (so its deep-copy duplicate / range-move re-encode paths run across a real transaction layer).
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Front-coded String value column")
@Tag(INDEXING)
@Tag(ATTRIBUTE)
class FrontCodedStringColumnTest {

	private static final int BLOCK_SIZE = 8;

	/**
	 * Verifies that {@link FrontCodedStringColumn} performs the same key moves / searches as the boxed reference column.
	 */
	@Nested
	@DisplayName("FrontCodedStringColumn vs. boxed column parity")
	class ColumnParityTest {

		@Test
		@DisplayName("insert / remove / findKeyPosition / duplicate / copyRangeTo match the boxed column")
		void shouldBehaveLikeBoxedColumn() {
			final ValueColumn<String> frontCoded = new FrontCodedStringColumn<>(BLOCK_SIZE);
			final ValueColumn<String> boxed = new BoxedObjectColumn<>(String.class, BLOCK_SIZE);

			// build the same ordered prefix in both columns (shared "code-" prefix exercises prefix compression)
			final String[] inserted = {"code-10", "code-20", "code-30", "code-40"};
			for (int i = 0; i < inserted.length; i++) {
				frontCoded.insertKeyAt(i, inserted[i]);
				boxed.insertKeyAt(i, inserted[i]);
			}
			assertColumnsEqual(frontCoded, boxed, inserted.length);

			// insert "code-25" in the middle (position 2)
			final InsertionPosition fp = frontCoded.findKeyPosition("code-25", 0, inserted.length, null);
			final InsertionPosition bp = boxed.findKeyPosition("code-25", 0, inserted.length, null);
			assertEquals(bp.position(), fp.position());
			assertEquals(bp.alreadyPresent(), fp.alreadyPresent());
			assertFalse(fp.alreadyPresent());
			frontCoded.insertKeyAt(fp.position(), "code-25");
			boxed.insertKeyAt(bp.position(), "code-25");
			assertColumnsEqual(frontCoded, boxed, inserted.length + 1);

			// existing key lookup reports alreadyPresent at the same slot
			final InsertionPosition fHit = frontCoded.findKeyPosition("code-30", 0, inserted.length + 1, null);
			final InsertionPosition bHit = boxed.findKeyPosition("code-30", 0, inserted.length + 1, null);
			assertTrue(fHit.alreadyPresent());
			assertEquals(bHit.position(), fHit.position());

			// duplicate is an independent deep copy
			final ValueColumn<String> dup = frontCoded.duplicate();
			assertColumnsEqual(dup, boxed, inserted.length + 1);
			dup.insertKeyAt(0, "code-00");
			assertEquals("code-25", frontCoded.keyAt(2), "Duplicate must not alias the source");

			// remove the middle key (position 2 == "code-25") from both, releasing the freed last slot
			frontCoded.removeKeyAt(2);
			frontCoded.clearAt(inserted.length);
			boxed.removeKeyAt(2);
			boxed.clearAt(inserted.length);
			assertColumnsEqual(frontCoded, boxed, inserted.length);

			assertInstanceOf(FrontCodedStringColumn.class, frontCoded);
		}

		@Test
		@DisplayName("copyRangeTo / fillEmpty / appendKey / asBoxedArray match the boxed column")
		void shouldCopyRangeAndClearLikeBoxedColumn() {
			final ValueColumn<String> srcFront = new FrontCodedStringColumn<>(BLOCK_SIZE);
			final ValueColumn<String> srcBoxed = new BoxedObjectColumn<>(String.class, BLOCK_SIZE);
			final String[] keys = {"alpha", "alpine", "beta", "betard"};
			for (int i = 0; i < keys.length; i++) {
				srcFront.insertKeyAt(i, keys[i]);
				srcBoxed.insertKeyAt(i, keys[i]);
			}

			// copy a 3-key block into a fresh column
			final ValueColumn<String> dstFront = srcFront.allocate(BLOCK_SIZE);
			final ValueColumn<String> dstBoxed = srcBoxed.allocate(BLOCK_SIZE);
			srcFront.copyRangeTo(1, dstFront, 0, 3);
			srcBoxed.copyRangeTo(1, dstBoxed, 0, 3);
			assertColumnsEqual(dstFront, dstBoxed, 3);

			// appendKey renders each decoded key identically to the boxed column
			for (int i = 0; i < 3; i++) {
				final StringBuilder frontKey = new StringBuilder(16);
				final StringBuilder boxedKey = new StringBuilder(16);
				dstFront.appendKey(frontKey, i);
				dstBoxed.appendKey(boxedKey, i);
				assertEquals(boxedKey.toString(), frontKey.toString(), "appendKey mismatch at slot " + i);
			}

			// asBoxedArray (cold path) decodes to the same prefix and has the column's full capacity length
			final String[] frontArray = (String[]) dstFront.asBoxedArray();
			final String[] boxedArray = (String[]) dstBoxed.asBoxedArray();
			assertEquals(BLOCK_SIZE, frontArray.length);
			for (int i = 0; i < 3; i++) {
				assertEquals(boxedArray[i], frontArray[i]);
			}

			// fillEmpty truncates the live tail; the surviving prefix is unchanged and a re-insert proves the slot is free
			dstFront.fillEmpty(2, 3);
			dstBoxed.fillEmpty(2, 3);
			assertColumnsEqual(dstFront, dstBoxed, 2);
			dstFront.insertKeyAt(2, "gamma");
			dstBoxed.insertKeyAt(2, "gamma");
			assertEquals("gamma", dstFront.keyAt(2));
			assertColumnsEqual(dstFront, dstBoxed, 3);
		}

		@Test
		@DisplayName("decodes every entry across multiple restart blocks within one column")
		void shouldDecodeAcrossRestartBlocks() {
			// 40 entries in a capacity-64 column span three restart blocks (entries 0, 16, 32 are full restart points),
			// so this exercises the restart-seek-then-walk decode that a leaf smaller than the restart interval cannot
			final int capacity = 64;
			final int count = 40;
			final ValueColumn<String> column = new FrontCodedStringColumn<>(capacity);
			final String[] expected = new String[count];
			for (int i = 0; i < count; i++) {
				// zero-padded so natural order == insertion order and every key shares the long "K00" prefix
				expected[i] = String.format("K%04d", i);
				column.insertKeyAt(i, expected[i]);
			}

			// random-access decode of restart points (0, 16, 32) and mid-block entries (1, 17, 33, 39)
			for (final int probe : new int[]{0, 1, 15, 16, 17, 31, 32, 33, 39}) {
				assertEquals(expected[probe], column.keyAt(probe), "Decode mismatch at index " + probe);
			}
			// full sweep round-trips
			for (int i = 0; i < count; i++) {
				assertEquals(expected[i], column.keyAt(i), "Decode mismatch at index " + i);
			}

			// binary search over the restart-indexed entries finds present keys and the insertion slot for absent ones
			final InsertionPosition hit = column.findKeyPosition("K0020", 0, count, null);
			assertTrue(hit.alreadyPresent());
			assertEquals(20, hit.position());
			final InsertionPosition miss = column.findKeyPosition("K0040", 0, count, null);
			assertFalse(miss.alreadyPresent());
			assertEquals(count, miss.position());
		}

		@Test
		@DisplayName("round-trips keys whose shared prefix and suffix exceed the single-byte length limit")
		void shouldRoundTripVarintBoundaryKeys() {
			// length fields are varint-encoded, so a shared prefix and a suffix that each exceed the single-byte length
			// limit (255 bytes) must both round-trip correctly
			final ValueColumn<String> column = new FrontCodedStringColumn<>(BLOCK_SIZE);
			final String longPrefix = "a".repeat(300);
			final String[] keys = {longPrefix + "1", longPrefix + "2", "z".repeat(400)};
			for (int i = 0; i < keys.length; i++) {
				column.insertKeyAt(i, keys[i]);
			}
			for (int i = 0; i < keys.length; i++) {
				assertEquals(keys[i], column.keyAt(i), "Long-key round-trip mismatch at slot " + i);
			}
			// the > 255-shared-prefix pair (slots 0,1) is located correctly by search
			final InsertionPosition hit = column.findKeyPosition(longPrefix + "2", 0, keys.length, null);
			assertTrue(hit.alreadyPresent());
			assertEquals(1, hit.position());
		}

		@Test
		@DisplayName("decodes a short entry following a longer predecessor without leaking stale tail bytes")
		void shouldDecodeShortEntryAfterLongerPredecessorWithoutStaleTail() {
			// the decode reuses one scratch buffer across hops; a short entry decoded right after a much longer
			// predecessor in the same restart block must be truncated to its real length, never echoing the
			// predecessor's trailing bytes left in the shared scratch. The 60-byte key also exceeds the initial scratch
			// size, so this also exercises the grow-then-reuse path.
			final ValueColumn<String> column = new FrontCodedStringColumn<>(BLOCK_SIZE);
			final String longKey = "abc" + "x".repeat(57); // 60 bytes, forces the scratch to grow past its seed
			final String[] keys = {"aaa", longKey, "abd", "abe" + "y".repeat(40)};
			for (int i = 0; i < keys.length; i++) {
				column.insertKeyAt(i, keys[i]);
			}
			// "abd" (slot 2) shares "ab" with the 60-byte predecessor; a stale-tail bug would return "abd" + 57 'x's
			assertEquals("abd", column.keyAt(2), "short entry must not inherit the longer predecessor's tail");
			// every entry round-trips - covers the long->short and short->long transitions via decodeAt
			for (int i = 0; i < keys.length; i++) {
				assertEquals(keys[i], column.keyAt(i), "round-trip mismatch at slot " + i);
			}
			// asBoxedArray runs the decodeAll path (separate reused-scratch loop); first `keys.length` slots are live
			final String[] decoded = (String[]) column.asBoxedArray();
			for (int i = 0; i < keys.length; i++) {
				assertEquals(keys[i], decoded[i], "decodeAll mismatch at slot " + i);
			}
			// binary search (which decodes each probed candidate) still locates the short key after the long one
			final InsertionPosition hit = column.findKeyPosition("abd", 0, keys.length, null);
			assertTrue(hit.alreadyPresent());
			assertEquals(2, hit.position());
		}

		@Test
		@DisplayName("round-trips the empty key and a zero-shared key sitting among much longer keys")
		void shouldRoundTripEmptyKeyAndZeroSharedKeyAmongLongerKeys() {
			// sorted keys: the empty string (the column minimum, stored as varint(0) varint(0) with no suffix), a long
			// restart-anchoring key, and a trailing key that shares nothing with that long predecessor. Decoding the
			// trailing key reuses the scratch already filled with the 300-byte predecessor, so the shared==0 branch must
			// overwrite the suffix from offset 0 and truncate to the real length - never echoing the predecessor's tail.
			// The 300-byte key also forces the decode scratch to grow past its initial capacity, exercising grow-then-reuse.
			final ValueColumn<String> column = new FrontCodedStringColumn<>(BLOCK_SIZE);
			final String[] keys = {"", "m".repeat(300), "z"};
			for (int i = 0; i < keys.length; i++) {
				column.insertKeyAt(i, keys[i]);
			}

			// random-access decode (decodeAt) round-trips every slot, including the empty entry and the zero-shared tail
			for (int i = 0; i < keys.length; i++) {
				assertEquals(keys[i], column.keyAt(i), "keyAt round-trip mismatch at slot " + i);
			}
			// the empty key must decode to "" exactly, never inheriting any bytes from a neighbour
			assertEquals("", column.keyAt(0), "empty key must decode to the empty string");
			// the zero-shared tail must not echo the long predecessor's bytes left in the reused scratch
			assertEquals("z", column.keyAt(2), "zero-shared key must not inherit the long predecessor's tail");

			// sequential decode (decodeAll, via asBoxedArray) round-trips the same keys
			final String[] decoded = (String[]) column.asBoxedArray();
			for (int i = 0; i < keys.length; i++) {
				assertEquals(keys[i], decoded[i], "decodeAll mismatch at slot " + i);
			}

			// binary search locates each key, including the empty key at the front and the zero-shared key at the end
			for (int i = 0; i < keys.length; i++) {
				final InsertionPosition hit = column.findKeyPosition(keys[i], 0, keys.length, null);
				assertTrue(hit.alreadyPresent(), "findKeyPosition must locate key at slot " + i);
				assertEquals(i, hit.position(), "findKeyPosition returned wrong slot for key " + i);
			}
		}

		@Test
		@DisplayName("round-trips multi-byte UTF-8 keys, the empty string and a single entry")
		void shouldRoundTripUtf8AndDegenerateKeys() {
			// empty string and single entry
			final ValueColumn<String> single = new FrontCodedStringColumn<>(BLOCK_SIZE);
			single.insertKeyAt(0, "");
			assertEquals("", single.keyAt(0));
			single.insertKeyAt(1, "x");
			assertEquals("", single.keyAt(0));
			assertEquals("x", single.keyAt(1));

			// multi-byte UTF-8 (accents, Cyrillic, CJK, supplementary-plane emoji), inserted in natural order
			final ValueColumn<String> column = new FrontCodedStringColumn<>(BLOCK_SIZE);
			final String[] utf8 = {"café", "naïve", "Москва", "日本語", "😀smile"};
			Arrays.sort(utf8);
			for (int i = 0; i < utf8.length; i++) {
				column.insertKeyAt(i, utf8[i]);
			}
			for (int i = 0; i < utf8.length; i++) {
				assertEquals(utf8[i], column.keyAt(i), "UTF-8 round-trip mismatch at slot " + i);
			}
		}

		@Test
		@DisplayName("removing every entry resets the blob to empty and re-growing rebuilds it cleanly")
		void shouldRemoveDownToEmptyThenRegrow() {
			final ValueColumn<String> column = new FrontCodedStringColumn<>(BLOCK_SIZE);
			final String[] keys = {"code-10", "code-20", "code-30", "code-40"};
			for (int i = 0; i < keys.length; i++) {
				column.insertKeyAt(i, keys[i]);
			}

			// drain from the front one entry at a time, releasing the freed last slot like the leaf does on delete
			for (int remaining = keys.length; remaining > 0; remaining--) {
				column.removeKeyAt(0);
				column.clearAt(remaining - 1);
			}

			// the blob is fully reset: capacity survives and every slot decodes back to null on the cold path
			assertEquals(BLOCK_SIZE, column.capacity());
			final String[] emptied = (String[]) column.asBoxedArray();
			assertEquals(BLOCK_SIZE, emptied.length);
			for (int i = 0; i < emptied.length; i++) {
				assertNull(emptied[i], "Slot " + i + " must be empty after full drain");
			}

			// re-growing from the empty state produces the same keys as a freshly built boxed column
			final ValueColumn<String> boxed = new BoxedObjectColumn<>(String.class, BLOCK_SIZE);
			final String[] regrown = {"sku-1", "sku-2", "sku-3"};
			for (int i = 0; i < regrown.length; i++) {
				column.insertKeyAt(i, regrown[i]);
				boxed.insertKeyAt(i, regrown[i]);
			}
			assertColumnsEqual(column, boxed, regrown.length);
		}

		@Test
		@DisplayName("clearAt on a live slot truncates the live tail like a downward setPeek")
		void shouldClearLiveSlotViaClearAt() {
			// the leaf calls clearAt on a slot below size when a downward setPeek frees a still-live tail; the boxed
			// column nulls that slot, the front-coded column truncates the live prefix to it — both observe size == index
			final ValueColumn<String> column = new FrontCodedStringColumn<>(BLOCK_SIZE);
			final String[] keys = {"alpha", "alpine", "beta", "betard", "gamma"};
			for (int i = 0; i < keys.length; i++) {
				column.insertKeyAt(i, keys[i]);
			}

			final int liveSlot = 3;
			column.clearAt(liveSlot);

			// the surviving prefix [0, liveSlot) is unchanged
			final ValueColumn<String> expected = new BoxedObjectColumn<>(String.class, BLOCK_SIZE);
			for (int i = 0; i < liveSlot; i++) {
				expected.insertKeyAt(i, keys[i]);
			}
			assertColumnsEqual(column, expected, liveSlot);

			// the truncated slot is free again — a re-insert lands there and round-trips
			column.insertKeyAt(liveSlot, "delta");
			assertEquals("delta", column.keyAt(liveSlot));
		}

		@Test
		@DisplayName("copyRangeTo into self shifts an overlapping range like the boxed column")
		void shouldCopyOverlappingRangeIntoSelf() {
			// dst == this with dstPos != srcPos is the in-place steal/merge shape; the source range must be snapshotted
			// before the splice so the overlap reads pre-move values, exactly like System.arraycopy on the boxed column
			final String[] keys = {"a1", "a2", "a3", "a4", "a5", "a6"};

			// right shift (dstPos > srcPos), the stealFromLeft shape
			final ValueColumn<String> rightFront = new FrontCodedStringColumn<>(BLOCK_SIZE);
			final ValueColumn<String> rightBoxed = new BoxedObjectColumn<>(String.class, BLOCK_SIZE);
			for (int i = 0; i < keys.length; i++) {
				rightFront.insertKeyAt(i, keys[i]);
				rightBoxed.insertKeyAt(i, keys[i]);
			}
			rightFront.copyRangeTo(1, rightFront, 3, 3);
			rightBoxed.copyRangeTo(1, rightBoxed, 3, 3);
			assertColumnsEqual(rightFront, rightBoxed, keys.length);

			// left shift (dstPos < srcPos)
			final ValueColumn<String> leftFront = new FrontCodedStringColumn<>(BLOCK_SIZE);
			final ValueColumn<String> leftBoxed = new BoxedObjectColumn<>(String.class, BLOCK_SIZE);
			for (int i = 0; i < keys.length; i++) {
				leftFront.insertKeyAt(i, keys[i]);
				leftBoxed.insertKeyAt(i, keys[i]);
			}
			leftFront.copyRangeTo(3, leftFront, 1, 3);
			leftBoxed.copyRangeTo(3, leftBoxed, 1, 3);
			assertColumnsEqual(leftFront, leftBoxed, keys.length);
		}

		@Test
		@DisplayName("copyRangeTo right-shifting past the live end (merge with a larger left sibling) matches the boxed column")
		void shouldRightShiftPastLiveEndForMerge() {
			// mergeWithLeft opens room at the front for a LARGER left sibling: it right-shifts this node's keys to
			// [leftSize, leftSize + thisSize) with dstPos == leftSize > thisSize, leaving a transient gap in
			// [thisSize, leftSize) that the follow-up front-fill overwrites. The boxed column carries the gap as null
			// sentinels; the dense blob has no null slot, so before the fix this NPE'd in encode (keys[i] == null).
			final String[] thisKeys = {"m1", "m2", "m3"};               // this node: 3 keys
			final String[] leftKeys = {"l1", "l2", "l3", "l4", "l5"};   // left sibling: 5 keys (leftSize > thisSize)
			final int thisSize = thisKeys.length;
			final int leftSize = leftKeys.length;

			final ValueColumn<String> front = new FrontCodedStringColumn<>(BLOCK_SIZE);
			final ValueColumn<String> boxed = new BoxedObjectColumn<>(String.class, BLOCK_SIZE);
			final ValueColumn<String> leftFront = new FrontCodedStringColumn<>(BLOCK_SIZE);
			final ValueColumn<String> leftBoxed = new BoxedObjectColumn<>(String.class, BLOCK_SIZE);
			for (int i = 0; i < thisSize; i++) {
				front.insertKeyAt(i, thisKeys[i]);
				boxed.insertKeyAt(i, thisKeys[i]);
			}
			for (int i = 0; i < leftSize; i++) {
				leftFront.insertKeyAt(i, leftKeys[i]);
				leftBoxed.insertKeyAt(i, leftKeys[i]);
			}

			// step 1 — right-shift this node's keys to [leftSize, leftSize + thisSize); gap opens in [thisSize, leftSize)
			front.copyRangeTo(0, front, leftSize, thisSize);
			boxed.copyRangeTo(0, boxed, leftSize, thisSize);
			// step 2 — front-fill the left sibling's keys into [0, leftSize), overwriting the transient gap
			leftFront.copyRangeTo(0, front, 0, leftSize);
			leftBoxed.copyRangeTo(0, boxed, 0, leftSize);

			assertColumnsEqual(front, boxed, leftSize + thisSize);
		}

		@Test
		@DisplayName("fillEmpty is a no-op at the size boundary and truncates to empty from zero")
		void shouldHandleFillEmptyBoundaries() {
			final ValueColumn<String> column = new FrontCodedStringColumn<>(BLOCK_SIZE);
			final String[] keys = {"item-1", "item-2", "item-3", "item-4"};
			for (int i = 0; i < keys.length; i++) {
				column.insertKeyAt(i, keys[i]);
			}

			// fromInclusive == size is a no-op: every live key stays readable
			column.fillEmpty(keys.length, keys.length);
			for (int i = 0; i < keys.length; i++) {
				assertEquals(keys[i], column.keyAt(i), "fillEmpty at the size boundary must not drop slot " + i);
			}

			// fillEmpty(0, size) truncates the whole column to empty; the slots are free again
			column.fillEmpty(0, keys.length);
			final String[] emptied = (String[]) column.asBoxedArray();
			for (int i = 0; i < emptied.length; i++) {
				assertNull(emptied[i], "Slot " + i + " must be empty after truncate-to-zero");
			}
			column.insertKeyAt(0, "fresh");
			assertEquals("fresh", column.keyAt(0));
		}

		/**
		 * Asserts the two columns hold the same decoded keys in `[0, size)` and share the same capacity.
		 *
		 * @param actual   the column under test
		 * @param expected the boxed reference column
		 * @param size     the number of populated slots
		 */
		private static void assertColumnsEqual(
			@Nonnull ValueColumn<String> actual, @Nonnull ValueColumn<String> expected, int size
		) {
			assertEquals(expected.capacity(), actual.capacity());
			for (int i = 0; i < size; i++) {
				assertEquals(expected.keyAt(i), actual.keyAt(i), "Key mismatch at slot " + i);
			}
		}
	}

	/**
	 * Verifies the {@link ValueColumnFactory} selection rules and the end-to-end String-keyed tree workload.
	 */
	@Nested
	@DisplayName("ValueColumnFactory selection and tree workload")
	class FactoryAndTreeTest {

		@Test
		@DisplayName("String keys select the front-coded column for both natural order and a null comparator")
		void shouldSelectFrontCodedForNaturalOrderString() {
			assertInstanceOf(
				FrontCodedStringColumn.class,
				ValueColumnFactory.forKey(String.class, null).create(BLOCK_SIZE)
			);
			assertInstanceOf(
				FrontCodedStringColumn.class,
				ValueColumnFactory.forKey(String.class, Comparator.<String>naturalOrder()).create(BLOCK_SIZE)
			);
		}

		@Test
		@DisplayName("a localized String still selects the front-coded column (front-coding is order-agnostic)")
		void shouldSelectFrontCodedForLocalizedString() {
			assertInstanceOf(
				FrontCodedStringColumn.class,
				ValueColumnFactory.forKey(String.class, new LocalizedStringComparator(Locale.FRENCH))
					.create(BLOCK_SIZE)
			);
		}

		@Test
		@DisplayName("randomized add/remove workload on a String-keyed tree matches a TreeMap oracle")
		@SuppressWarnings({"unchecked", "rawtypes"})
		void shouldMatchOracleOnStringKeyedTree() {
			final ValueColumnFactory factory = ValueColumnFactory.forKey(String.class, null);
			assertInstanceOf(FrontCodedStringColumn.class, factory.create(BLOCK_SIZE));
			final TransactionalBucketBPlusTree<String> tree = new TransactionalBucketBPlusTree<>(
				BLOCK_SIZE, 3, 7, 3, String.class, null, factory
			);

			final TreeMap<String, TreeSet<Integer>> oracle = new TreeMap<>();
			final Random random = new Random(987654L);
			final int keyDomain = 80;

			for (int op = 0; op < 6_000; op++) {
				// prefix-heavy keys so the front-coding shares prefixes the way real codes / URLs do
				final String key = String.format("sku-%03d", random.nextInt(keyDomain));
				final int recordId = random.nextInt(1_000);
				if (random.nextInt(100) < 65) {
					tree.addRecord(key, recordId);
					oracle.computeIfAbsent(key, k -> new TreeSet<>()).add(recordId);
				} else {
					final TreeSet<Integer> set = oracle.get(key);
					if (set != null && set.contains(recordId)) {
						tree.removeRecord(key, recordId);
						set.remove(recordId);
						if (set.isEmpty()) {
							oracle.remove(key);
						}
					}
				}
				if (op % 250 == 0) {
					assertTreeMatchesOracle(tree, oracle);
				}
			}
			assertTreeMatchesOracle(tree, oracle);
		}

		@Test
		@DisplayName("a localized String tree orders buckets by collation, not by codepoint")
		@SuppressWarnings({"unchecked", "rawtypes"})
		void shouldOrderByCollatorWhenLocalized() {
			final Comparator<String> collator = new LocalizedStringComparator(Locale.FRENCH);
			final ValueColumnFactory factory = ValueColumnFactory.forKey(String.class, collator);
			assertInstanceOf(FrontCodedStringColumn.class, factory.create(BLOCK_SIZE));
			final TransactionalBucketBPlusTree<String> tree = new TransactionalBucketBPlusTree<>(
				BLOCK_SIZE, 3, 7, 3, String.class, collator, factory
			);

			// chosen so French collation order (case-insensitive, accents near base letter) differs from the natural
			// codepoint order (uppercase < lowercase < accented) — this is what proves the comparator drives ordering
			final List<String> words = new ArrayList<>(
				Arrays.asList("Zebre", "abricot", "éclair", "Mangue", "ananas")
			);
			final TreeMap<String, TreeSet<Integer>> oracle = new TreeMap<>(collator);
			final List<String> shuffled = new ArrayList<>(words);
			Collections.shuffle(shuffled, new Random(13L));
			int pk = 0;
			for (final String word : shuffled) {
				tree.addRecord(word, pk);
				oracle.computeIfAbsent(word, k -> new TreeSet<>()).add(pk);
				pk++;
			}

			// the tree enumerates in collation order (== the collator-keyed oracle order)
			assertTreeMatchesOracle(tree, oracle);
			verifyConsistent(tree);

			// sanity: collation order genuinely differs from natural codepoint order for these words, so the assertion
			// above could not have passed by accident under a natural-order tree
			final List<String> byCollation = new ArrayList<>(words);
			byCollation.sort(collator);
			final List<String> byNatural = new ArrayList<>(words);
			Collections.sort(byNatural);
			assertNotEquals(byNatural, byCollation, "Test words must distinguish collation from codepoint order");
		}

		@Test
		@DisplayName("findKeyPosition with an explicit comparator searches via the collator, not codepoint order")
		void shouldFindKeyPositionWithExplicitComparator() {
			// the column-level parity test only exercises the natural-order (null comparator) branch; this drives the
			// supplied-comparator arm directly by building the column in French collation order and searching with it
			final Comparator<String> collator = new LocalizedStringComparator(Locale.FRENCH);
			final String[] words = {"Zebre", "abricot", "éclair", "Mangue", "ananas"};
			final String[] collated = words.clone();
			Arrays.sort(collated, collator);

			final ValueColumn<String> frontCoded = new FrontCodedStringColumn<>(BLOCK_SIZE);
			final ValueColumn<String> boxed = new BoxedObjectColumn<>(String.class, BLOCK_SIZE);
			for (int i = 0; i < collated.length; i++) {
				frontCoded.insertKeyAt(i, collated[i]);
				boxed.insertKeyAt(i, collated[i]);
			}

			// an existing key is located at the same slot the boxed column reports, under the same comparator
			for (final String present : words) {
				final InsertionPosition front = frontCoded.findKeyPosition(present, 0, collated.length, collator);
				final InsertionPosition ref = boxed.findKeyPosition(present, 0, collated.length, collator);
				assertTrue(front.alreadyPresent(), "Existing key '" + present + "' must be found");
				assertEquals(ref.position(), front.position(), "Slot mismatch for '" + present + "'");
				assertTrue(ref.alreadyPresent());
			}

			// an absent key reports the same collation insert slot as the boxed reference (and is not present)
			final InsertionPosition frontMiss = frontCoded.findKeyPosition("buisson", 0, collated.length, collator);
			final InsertionPosition refMiss = boxed.findKeyPosition("buisson", 0, collated.length, collator);
			assertFalse(frontMiss.alreadyPresent());
			assertEquals(refMiss.position(), frontMiss.position());
		}

		@Test
		@DisplayName("collation-equal but byte-distinct keys share one bucket and either spelling removes correctly")
		@SuppressWarnings({"unchecked", "rawtypes"})
		void shouldCollapseCollationEqualKeysIntoOneBucket() {
			// the decode-then-compare-via-comparator search path means two byte-distinct spellings that the collator
			// perceives as equal must collapse into a single bucket — a future "compare the raw UTF-8 bytes" shortcut
			// would silently break this, so it is pinned here as the index-level contract
			final Comparator<String> collator = new LocalizedStringComparator(Locale.FRENCH);
			// precomposed é (U+00E9) vs decomposed e + combining acute (U+0301): distinct UTF-8 bytes, yet canonically
			// equivalent so the collator ties them regardless of its decomposition mode. Explicit escapes (not literal
			// accented characters) keep the two spellings provably byte-distinct however the source file is normalized
			final String precomposed = "café";
			final String decomposed = "cafe\u0301";
			assertNotEquals(precomposed, decomposed, "the two spellings must be byte-distinct");
			assertEquals(0, collator.compare(precomposed, decomposed),
				"precondition: the two spellings must collate equal (the tie under test)");

			final ValueColumnFactory factory = ValueColumnFactory.forKey(String.class, collator);
			assertInstanceOf(FrontCodedStringColumn.class, factory.create(BLOCK_SIZE));
			final TransactionalBucketBPlusTree<String> tree = new TransactionalBucketBPlusTree<>(
				BLOCK_SIZE, 3, 7, 3, String.class, collator, factory
			);

			// records added under the two distinct spellings are perceived equal and collapse into one bucket whose
			// record set holds both primary keys
			tree.addRecord(precomposed, 1);
			tree.addRecord(decomposed, 2);
			assertSingleBucketWithRecords(tree, new int[]{1, 2});

			// either spelling locates the shared bucket for removal: pk1 was added under the precomposed spelling but is
			// removed via the decomposed one, and the bucket survives with pk2 still present
			tree.removeRecord(decomposed, 1);
			assertSingleBucketWithRecords(tree, new int[]{2});

			// removing the last record (added under the decomposed spelling, removed via the precomposed one) vacates the
			// key entirely — empty-bucket cleanup
			tree.removeRecord(precomposed, 2);
			assertFalse(tree.cursor().next(), "the bucket must vacate once its last record is removed");
			verifyConsistent(tree);
		}

		/**
		 * Asserts the tree holds exactly one bucket whose record set equals `expectedRecords`, and that the tree is
		 * structurally consistent. The bucket's stored representative spelling is intentionally not asserted — it is an
		 * implementation detail (the first-inserted spelling) irrelevant to the collation-equality contract.
		 *
		 * @param tree            the tree under test
		 * @param expectedRecords the record ids expected in the single surviving bucket, ascending
		 */
		private static void assertSingleBucketWithRecords(
			@Nonnull TransactionalBucketBPlusTree<String> tree, @Nonnull int[] expectedRecords
		) {
			final TransactionalBucketBPlusTree.BucketCursor<String> cursor = tree.cursor();
			assertTrue(cursor.next(), "expected exactly one bucket, found none");
			final int[] actual = cursor.isSingle()
				? new int[]{cursor.singleRecordId()}
				: cursor.records().getArray();
			assertArrayEquals(expectedRecords, actual, "record set mismatch for the collated bucket");
			assertFalse(cursor.next(), "collation-equal keys must collapse into a single bucket");
			verifyConsistent(tree);
		}
	}

	/**
	 * Drives the String-keyed tree's {@link FrontCodedStringColumn} across a real MVCC transaction layer: the
	 * non-transactional oracle tests never run the column's {@link FrontCodedStringColumn#duplicate()} /
	 * {@code copyRangeTo} re-encode through a commit / rollback, so a layer-decoupling defect in the dense-blob copy
	 * path would be invisible without these tests (the truncate-after-duplicate fix in the leaf is exercised here).
	 */
	@Nested
	@DisplayName("String-keyed tree across an MVCC transaction")
	class TransactionalTest {

		@Test
		@DisplayName("preserves a String-keyed tree across a commit that splits and merges leaves")
		@Tag(TRANSACTION)
		@SuppressWarnings({"unchecked", "rawtypes"})
		void shouldPreserveStringKeyedTreeAcrossCommit() {
			final ValueColumnFactory factory = ValueColumnFactory.forKey(String.class, null);
			assertInstanceOf(FrontCodedStringColumn.class, factory.create(BLOCK_SIZE),
				"Factory must back the tree with the front-coded column");
			final TransactionalBucketBPlusTree<String> tree = new TransactionalBucketBPlusTree<>(
				BLOCK_SIZE, 3, 7, 3, String.class, null, factory
			);

			// commit a base layout dense enough to span several leaves
			final TreeMap<String, TreeSet<Integer>> baseOracle = new TreeMap<>();
			for (int i = 0; i <= 40; i++) {
				final String key = String.format("item-%03d", i);
				tree.addRecord(key, i + 1_000);
				baseOracle.computeIfAbsent(key, k -> new TreeSet<>()).add(i + 1_000);
			}

			assertStateAfterCommit(
				tree,
				tested -> {
					// add and remove enough buckets inside the txn to force leaf splits, steals and merges so the
					// column's duplicate() + copyRangeTo re-encode run across the transaction layer
					for (int i = 41; i <= 90; i++) {
						final String key = String.format("item-%03d", i);
						tested.addRecord(key, i + 1_000);
						tested.addRecord(key, i + 2_000);
					}
					for (int i = 0; i <= 25; i++) {
						tested.removeRecord(String.format("item-%03d", i), i + 1_000);
					}
				},
				(original, committed) -> {
					final TreeMap<String, TreeSet<Integer>> oracle = new TreeMap<>(baseOracle);
					for (int i = 41; i <= 90; i++) {
						final TreeSet<Integer> set = new TreeSet<>();
						set.add(i + 1_000);
						set.add(i + 2_000);
						oracle.put(String.format("item-%03d", i), set);
					}
					for (int i = 0; i <= 25; i++) {
						oracle.remove(String.format("item-%03d", i));
					}
					assertTreeMatchesOracle(committed, oracle);
					verifyConsistent(committed);

					// the pre-commit base tree is unchanged — the transaction layer was decoupled
					assertTreeMatchesOracle(original, baseOracle);
				}
			);
		}

		@Test
		@DisplayName("discards String-keyed mutations on rollback, leaving the base tree untouched")
		@Tag(TRANSACTION)
		@SuppressWarnings({"unchecked", "rawtypes"})
		void shouldDiscardStringKeyedMutationsOnRollback() {
			final ValueColumnFactory factory = ValueColumnFactory.forKey(String.class, null);
			final TransactionalBucketBPlusTree<String> tree = new TransactionalBucketBPlusTree<>(
				BLOCK_SIZE, 3, 7, 3, String.class, null, factory
			);

			final TreeMap<String, TreeSet<Integer>> baseOracle = new TreeMap<>();
			for (int i = 0; i <= 15; i++) {
				final String key = String.format("item-%03d", i);
				tree.addRecord(key, i + 1_000);
				baseOracle.computeIfAbsent(key, k -> new TreeSet<>()).add(i + 1_000);
			}

			assertStateAfterRollback(
				tree,
				tested -> {
					for (int i = 16; i <= 40; i++) {
						tested.addRecord(String.format("item-%03d", i), i + 1_000);
					}
					for (int i = 0; i <= 10; i++) {
						tested.removeRecord(String.format("item-%03d", i), i + 1_000);
					}
				},
				(original, discarded) -> {
					assertTreeMatchesOracle(original, baseOracle);
					verifyConsistent(original);
				}
			);
		}
	}
}
