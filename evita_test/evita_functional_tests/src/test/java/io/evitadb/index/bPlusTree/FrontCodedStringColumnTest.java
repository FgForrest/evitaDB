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
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.ArrayUtils.InsertionPosition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
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
import static io.evitadb.index.bPlusTree.ValueColumnTestSupport.describe;
import static io.evitadb.index.bPlusTree.ValueColumnTestSupport.verifyConsistent;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.COMPARATOR;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the front-coded {@link FrontCodedStringColumn}: its array operations and {@link String} key round-trip are
 * proven equivalent to the boxed {@link BoxedObjectColumn} (including decode across restart-block boundaries, the
 * production varint length encoding for keys longer than 255 bytes, and multi-byte UTF-8 values); the
 * {@link ValueColumnFactory} selects it for every {@link String} key regardless of the comparator; an end-to-end
 * randomized workload on a String-keyed {@link TransactionalBucketBPlusTree} matches a {@link TreeMap} oracle in both
 * natural codepoint order and locale-collation order; the column survives an MVCC commit / rollback that splits and
 * merges leaves (so its deep-copy duplicate / range-move re-encode paths run across a real transaction layer); and an
 * unpaired UTF-16 surrogate, which the column's WTF-8 storage keeps distinct from its old {@code '?'}-substituted
 * value, round-trips and orders correctly across every one of those same paths.
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
			final ValueColumn<String> frontCoded = new FrontCodedStringColumn<>(BLOCK_SIZE, true);
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

			// duplicate is an independent copy: mutating it must not affect the source
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
		@DisplayName("duplicate() shares the blob until the first write, so mutating the source afterward must not "
			+ "leak into the duplicate")
		void shouldNotLeakSourceMutationsIntoDuplicate() {
			// duplicate() structurally shares the backing blob/restart-index rather than deep-copying it (the blob is
			// safe to alias because every mutator whole-reference-replaces it via encode()); this pins that invariant
			// from the direction the parity test above does not cover — mutating the ORIGINAL after duplicating must
			// leave the DUPLICATE observing its pre-mutation snapshot, proving encode() never edits the shared arrays
			// in place
			final ValueColumn<String> original = new FrontCodedStringColumn<>(BLOCK_SIZE, true);
			final String[] keys = {"alpha", "alpine", "beta", "betard"};
			for (int i = 0; i < keys.length; i++) {
				original.insertKeyAt(i, keys[i]);
			}

			final ValueColumn<String> dup = original.duplicate();
			// mutate the original: insert, then remove, so both encode() call sites run
			original.insertKeyAt(2, "azure");
			original.removeKeyAt(0);

			// the duplicate must still report the pre-mutation snapshot
			for (int i = 0; i < keys.length; i++) {
				assertEquals(keys[i], dup.keyAt(i), "Duplicate must not observe the source's post-duplicate mutation");
			}
			// and the original must reflect its own mutations
			final String[] expectedOriginal = {"alpine", "azure", "beta", "betard"};
			for (int i = 0; i < expectedOriginal.length; i++) {
				assertEquals(expectedOriginal[i], original.keyAt(i), "Original mutation mismatch at slot " + i);
			}
		}

		@Test
		@DisplayName("copyRangeTo / fillEmpty / appendKey / asBoxedArray match the boxed column")
		void shouldCopyRangeAndClearLikeBoxedColumn() {
			final ValueColumn<String> srcFront = new FrontCodedStringColumn<>(BLOCK_SIZE, true);
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
			final ValueColumn<String> column = new FrontCodedStringColumn<>(capacity, true);
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
			final ValueColumn<String> column = new FrontCodedStringColumn<>(BLOCK_SIZE, true);
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
			final ValueColumn<String> column = new FrontCodedStringColumn<>(BLOCK_SIZE, true);
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
			final ValueColumn<String> column = new FrontCodedStringColumn<>(BLOCK_SIZE, true);
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
			final ValueColumn<String> single = new FrontCodedStringColumn<>(BLOCK_SIZE, true);
			single.insertKeyAt(0, "");
			assertEquals("", single.keyAt(0));
			single.insertKeyAt(1, "x");
			assertEquals("", single.keyAt(0));
			assertEquals("x", single.keyAt(1));

			// multi-byte UTF-8 (accents, Cyrillic, CJK, supplementary-plane emoji), inserted in natural order
			final ValueColumn<String> column = new FrontCodedStringColumn<>(BLOCK_SIZE, true);
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
			final ValueColumn<String> column = new FrontCodedStringColumn<>(BLOCK_SIZE, true);
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
			final ValueColumn<String> column = new FrontCodedStringColumn<>(BLOCK_SIZE, true);
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
			final ValueColumn<String> rightFront = new FrontCodedStringColumn<>(BLOCK_SIZE, true);
			final ValueColumn<String> rightBoxed = new BoxedObjectColumn<>(String.class, BLOCK_SIZE);
			for (int i = 0; i < keys.length; i++) {
				rightFront.insertKeyAt(i, keys[i]);
				rightBoxed.insertKeyAt(i, keys[i]);
			}
			rightFront.copyRangeTo(1, rightFront, 3, 3);
			rightBoxed.copyRangeTo(1, rightBoxed, 3, 3);
			assertColumnsEqual(rightFront, rightBoxed, keys.length);

			// left shift (dstPos < srcPos)
			final ValueColumn<String> leftFront = new FrontCodedStringColumn<>(BLOCK_SIZE, true);
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

			final ValueColumn<String> front = new FrontCodedStringColumn<>(BLOCK_SIZE, true);
			final ValueColumn<String> boxed = new BoxedObjectColumn<>(String.class, BLOCK_SIZE);
			final ValueColumn<String> leftFront = new FrontCodedStringColumn<>(BLOCK_SIZE, true);
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
		@DisplayName("copyRangeTo across restart-block boundaries (capacity 64, >16 live entries) matches the boxed column")
		void shouldCopyRangeAcrossRestartBoundaries() {
			// every other copyRangeTo test in this class uses BLOCK_SIZE (8), which never exceeds one restart block
			// (RESTART_INTERVAL == 16), so the moved range never straddles a restart point - decodeRangeToFlat's
			// restart-seek-then-walk (base = restart of srcPos, then rebase the caller's index against that base) is
			// otherwise untested. This drives it at capacity 64 with 40 live entries (restarts at 0, 16, 32), picking
			// src/dst ranges that straddle those boundaries, for both dst == this and dst != this.
			final int capacity = 64;
			final int count = 40;
			final String[] keys = new String[count];
			for (int i = 0; i < count; i++) {
				// zero-padded so natural order == insertion order, matching shouldDecodeAcrossRestartBlocks
				keys[i] = String.format("K%04d", i);
			}

			// dst == this, right shift: src [10, 22) straddles restart 16; dst [25, 37) straddles restart 32
			{
				final ValueColumn<String> front = new FrontCodedStringColumn<>(capacity, true);
				final ValueColumn<String> boxed = new BoxedObjectColumn<>(String.class, capacity);
				for (int i = 0; i < count; i++) {
					front.insertKeyAt(i, keys[i]);
					boxed.insertKeyAt(i, keys[i]);
				}
				front.copyRangeTo(10, front, 25, 12);
				boxed.copyRangeTo(10, boxed, 25, 12);
				assertColumnsEqual(front, boxed, count);
			}

			// dst == this, left shift: src [25, 37) straddles restart 32; dst [8, 20) straddles restart 16
			{
				final ValueColumn<String> front = new FrontCodedStringColumn<>(capacity, true);
				final ValueColumn<String> boxed = new BoxedObjectColumn<>(String.class, capacity);
				for (int i = 0; i < count; i++) {
					front.insertKeyAt(i, keys[i]);
					boxed.insertKeyAt(i, keys[i]);
				}
				front.copyRangeTo(25, front, 8, 12);
				boxed.copyRangeTo(25, boxed, 8, 12);
				assertColumnsEqual(front, boxed, count);
			}

			// dst != this, cross-leaf into an empty column: src [12, 34) straddles both restart 16 and restart 32
			{
				final ValueColumn<String> srcFront = new FrontCodedStringColumn<>(capacity, true);
				final ValueColumn<String> srcBoxed = new BoxedObjectColumn<>(String.class, capacity);
				for (int i = 0; i < count; i++) {
					srcFront.insertKeyAt(i, keys[i]);
					srcBoxed.insertKeyAt(i, keys[i]);
				}
				final ValueColumn<String> dstFront = srcFront.allocate(capacity);
				final ValueColumn<String> dstBoxed = srcBoxed.allocate(capacity);
				srcFront.copyRangeTo(12, dstFront, 0, 22);
				srcBoxed.copyRangeTo(12, dstBoxed, 0, 22);
				assertColumnsEqual(dstFront, dstBoxed, 22);
			}

			// dst != this, cross-leaf into a NON-empty column: exercises prefix + straddling slice + suffix together
			// (dst already holds 40 keys under a different prefix, so this also proves the assembly buffer isn't
			// confused between the two columns' distinct key spaces)
			{
				final ValueColumn<String> srcFront = new FrontCodedStringColumn<>(capacity, true);
				final ValueColumn<String> srcBoxed = new BoxedObjectColumn<>(String.class, capacity);
				final ValueColumn<String> dstFront = new FrontCodedStringColumn<>(capacity, true);
				final ValueColumn<String> dstBoxed = new BoxedObjectColumn<>(String.class, capacity);
				for (int i = 0; i < count; i++) {
					srcFront.insertKeyAt(i, keys[i]);
					srcBoxed.insertKeyAt(i, keys[i]);
					final String dstKey = "Z" + keys[i];
					dstFront.insertKeyAt(i, dstKey);
					dstBoxed.insertKeyAt(i, dstKey);
				}
				// overwrite [15, 33) of dst (straddling dst's restart 16 and 32) with src's [5, 23) (straddling
				// src's restart 16), leaving dst's [0, 15) prefix and [33, 40) suffix unchanged
				srcFront.copyRangeTo(5, dstFront, 15, 18);
				srcBoxed.copyRangeTo(5, dstBoxed, 15, 18);
				assertColumnsEqual(dstFront, dstBoxed, count);
			}
		}

		@Test
		@DisplayName("fillEmpty is a no-op at the size boundary and truncates to empty from zero")
		void shouldHandleFillEmptyBoundaries() {
			final ValueColumn<String> column = new FrontCodedStringColumn<>(BLOCK_SIZE, true);
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

		@Test
		@DisplayName("interleaved mutations on two columns sharing the thread-local scratch stay independent")
		@SuppressWarnings("unchecked")
		void shouldNotBleedScratchBetweenColumnsSharingTheThread() {
			// the decode/encode scratch is a single thread-local reused by EVERY column on the thread; mutating two
			// columns alternately means each op overwrites the scratch the other column just used. Distinct per-column
			// key prefixes make any scratch bleed (or an encode buffer wrongly adopted into `data`) surface as the wrong
			// column's prefix, and every op re-asserts BOTH columns against their own boxed oracle.
			final int cap = 32;
			final ValueColumn<String>[] front = new ValueColumn[]{
				new FrontCodedStringColumn<>(cap, true), new FrontCodedStringColumn<>(cap, true)
			};
			final ValueColumn<String>[] boxed = new ValueColumn[]{
				new BoxedObjectColumn<>(String.class, cap), new BoxedObjectColumn<>(String.class, cap)
			};
			final String[] prefix = {"aaa-", "zzzzz-"};
			final int[] size = new int[2];
			final Random rnd = new Random(4242L);
			for (int op = 0; op < 3_000; op++) {
				final int c = rnd.nextInt(2);
				final boolean insert = size[c] == 0 || (size[c] < cap && rnd.nextInt(100) < 60);
				if (insert) {
					final int pos = rnd.nextInt(size[c] + 1);
					final String key = prefix[c] + rnd.nextInt(1_000);
					front[c].insertKeyAt(pos, key);
					boxed[c].insertKeyAt(pos, key);
					size[c]++;
				} else {
					final int pos = rnd.nextInt(size[c]);
					front[c].removeKeyAt(pos);
					boxed[c].removeKeyAt(pos);
					size[c]--;
					front[c].clearAt(size[c]);
					boxed[c].clearAt(size[c]);
				}
				// both columns must always match their own oracle, regardless of which one was just mutated
				for (int k = 0; k < 2; k++) {
					for (int i = 0; i < size[k]; i++) {
						assertEquals(boxed[k].keyAt(i), front[k].keyAt(i),
							"column " + k + " slot " + i + " diverged after op " + op);
					}
				}
			}
		}

		@Test
		@DisplayName("randomized insert/remove workload matches the boxed column across restart blocks and long keys")
		void shouldMatchBoxedColumnUnderRandomizedMutations() {
			// capacity 64 crosses the 16-entry restart interval; the stress keys mix the empty string, > 255-byte keys
			// (multi-byte varint), short keys (long->short scratch-reuse transitions) and multi-byte UTF-8
			assertRandomizedMutationParity(20_260_708L, 64, 5_000);
		}

		@Test
		@DisplayName("thread-local scratch stays isolated across concurrent columns on different threads")
		void shouldKeepScratchIsolatedAcrossThreads() throws InterruptedException {
			// each thread churns its own column against its own oracle; a shared decode/encode buffer that was NOT
			// truly thread-local (e.g. demoted to a plain static field by a later edit) would cross-corrupt and one of
			// the threads would observe a mismatch
			final int threadCount = 8;
			final List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
			final Thread[] pool = new Thread[threadCount];
			for (int t = 0; t < threadCount; t++) {
				final long seed = 1_000L + t;
				pool[t] = new Thread(() -> {
					try {
						assertRandomizedMutationParity(seed, 64, 2_000);
					} catch (Throwable ex) {
						failures.add(ex);
					}
				}, "fc-scratch-" + t);
			}
			for (final Thread thread : pool) {
				thread.start();
			}
			for (final Thread thread : pool) {
				thread.join();
			}
			assertTrue(failures.isEmpty(), () -> "concurrent parity failures: " + failures);
		}

		/**
		 * Runs a seeded randomized insert/remove workload on a fresh {@link FrontCodedStringColumn} and an equivalent
		 * {@link BoxedObjectColumn} oracle, asserting every live slot matches after every mutation. Throws an
		 * {@link AssertionError} carrying the seed / op / slot on the first divergence so a concurrent failure is
		 * reproducible.
		 *
		 * @param seed     the RNG seed (also the reproduction handle)
		 * @param capacity the column block size (use {@code >= 64} to cross the restart interval)
		 * @param ops      the number of mutations to apply
		 */
		private static void assertRandomizedMutationParity(long seed, int capacity, int ops) {
			final ValueColumn<String> front = new FrontCodedStringColumn<>(capacity, true);
			final ValueColumn<String> boxed = new BoxedObjectColumn<>(String.class, capacity);
			final Random rnd = new Random(seed);
			int size = 0;
			for (int op = 0; op < ops; op++) {
				final boolean insert = size == 0 || (size < capacity && rnd.nextInt(100) < 60);
				if (insert) {
					final int pos = rnd.nextInt(size + 1);
					final String key = stressKey(rnd, op);
					front.insertKeyAt(pos, key);
					boxed.insertKeyAt(pos, key);
					size++;
				} else {
					final int pos = rnd.nextInt(size);
					front.removeKeyAt(pos);
					boxed.removeKeyAt(pos);
					size--;
					front.clearAt(size);
					boxed.clearAt(size);
				}
				for (int i = 0; i < size; i++) {
					final String expected = boxed.keyAt(i);
					final String actual = front.keyAt(i);
					if (!expected.equals(actual)) {
						throw new AssertionError(
							"seed=" + seed + " op=" + op + " slot=" + i + " expected=[" + expected
								+ "] actual=[" + actual + "]");
					}
				}
			}
		}

		/**
		 * Produces a stress key spanning the corner cases of the front-coded encoder: the empty string, a long shared
		 * prefix, a suffix beyond the single-byte varint limit, a short key that forces a long-to-short scratch-reuse
		 * transition, and multi-byte UTF-8.
		 *
		 * @param rnd  the RNG
		 * @param salt an op-unique salt so long keys stay distinct
		 * @return the generated key
		 */
		@Nonnull
		private static String stressKey(@Nonnull Random rnd, int salt) {
			return switch (rnd.nextInt(6)) {
				case 0 -> "";
				case 1 -> "shared-prefix-" + String.format("%05d", rnd.nextInt(1_000));
				case 2 -> "x".repeat(260 + rnd.nextInt(60)) + salt; // > 255-byte suffix -> multi-byte varint
				case 3 -> "s" + rnd.nextInt(100);                   // short, forces long->short transitions
				case 4 -> "café-" + rnd.nextInt(100);               // multi-byte UTF-8
				default -> "code-" + String.format("%03d", rnd.nextInt(500));
			};
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

			final ValueColumn<String> frontCoded = new FrontCodedStringColumn<>(BLOCK_SIZE, true);
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
		@DisplayName("preserves keys carrying an unpaired surrogate across the same commit")
		@Tag(TRANSACTION)
		@SuppressWarnings({"unchecked", "rawtypes"})
		void shouldPreserveSurrogateKeyedTreeAcrossCommit() {
			// The transactional path re-encodes through `duplicate()` and `copyRangeTo`, so a leaf that splits, steals
			// or merges builds its new blob out of raw bytes that never pass back through a `String`. Every other
			// surrogate case in this class hands the column a `String` first, which is why this is the only one that
			// exercises a column ACQUIRING an encoding it was not told about - and it does so across a real
			// transaction layer, against the same `TreeMap` oracle the ordinary commit case is checked with.
			final ValueColumnFactory factory = ValueColumnFactory.forKey(String.class, null);
			final TransactionalBucketBPlusTree<String> tree = new TransactionalBucketBPlusTree<>(
				BLOCK_SIZE, 3, 7, 3, String.class, null, factory
			);

			final List<String> baseKeys = surrogateKeys(0, 40);
			final TreeMap<String, TreeSet<Integer>> baseOracle = new TreeMap<>();
			for (int i = 0; i < baseKeys.size(); i++) {
				tree.addRecord(baseKeys.get(i), i + 1_000);
				baseOracle.computeIfAbsent(baseKeys.get(i), k -> new TreeSet<>()).add(i + 1_000);
			}

			final List<String> addedKeys = surrogateKeys(41, 90);
			assertStateAfterCommit(
				tree,
				tested -> {
					for (int i = 0; i < addedKeys.size(); i++) {
						tested.addRecord(addedKeys.get(i), i + 5_000);
						tested.addRecord(addedKeys.get(i), i + 6_000);
					}
					for (int i = 0; i < 26; i++) {
						tested.removeRecord(baseKeys.get(i), i + 1_000);
					}
				},
				(original, committed) -> {
					final TreeMap<String, TreeSet<Integer>> oracle = new TreeMap<>(baseOracle);
					for (int i = 0; i < addedKeys.size(); i++) {
						final TreeSet<Integer> set = new TreeSet<>();
						set.add(i + 5_000);
						set.add(i + 6_000);
						oracle.put(addedKeys.get(i), set);
					}
					for (int i = 0; i < 26; i++) {
						oracle.remove(baseKeys.get(i));
					}
					assertTreeMatchesOracle(committed, oracle);
					verifyConsistent(committed);

					// the pre-commit base tree is unchanged - the transaction layer was decoupled
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

		/**
		 * Builds the ascending key sequence the surrogate commit case drives, for the numbers `from` to `to` inclusive.
		 *
		 * Every fourth number contributes two keys beyond the plain one: the same stem followed by U+D7FF and by
		 * U+D800. Those two encode to `... ED 9F BF` and `... ED A0 80`, so they sort adjacent and front-code with the
		 * shared prefix ending INSIDE the three-byte sequence. Spreading them across the whole range puts one in most
		 * leaves, so the splits, steals and merges the commit forces really do move them.
		 *
		 * @param from the first number, inclusive
		 * @param to   the last number, inclusive
		 * @return the keys in ascending natural order
		 */
		@Nonnull
		private static List<String> surrogateKeys(int from, int to) {
			final List<String> keys = new ArrayList<>((to - from + 1) * 2);
			for (int i = from; i <= to; i++) {
				keys.add(String.format("item-%03d", i));
				if (i % 4 == 0) {
					keys.add(String.format("item-%03d\ud7ff", i));
					keys.add(String.format("item-%03d\ud800", i));
				}
			}
			return keys;
		}
	}

	/**
	 * Verifies the BMP-safe byte-compare fast path {@link FrontCodedStringColumn#findKeyPosition} takes when the
	 * corpus, the tree's comparator, and the probe are all provably BMP-only, and that anything outside that
	 * predicate (a supplementary character anywhere, a localized comparator) correctly falls back to the always-
	 * correct {@link String} comparison path.
	 */
	@Nested
	@DisplayName("BMP-safe byte-compare fast path")
	@Tag(COMPARATOR)
	class BmpSafeByteCompareTest {

		@Test
		@DisplayName("a corpus containing a supplementary-plane key falls back to String order, not raw UTF-8 byte order")
		void shouldFallBackToStringOrderWhenCorpusHasSupplementaryCharacter() {
			// U+E000 (private-use, BMP) vs U+10000 (supplementary): String.compareTo compares UTF-16 code UNITS, so
			// U+E000 (a single char 0xE000) sorts AFTER U+10000's high surrogate (0xD800) - String order puts the
			// supplementary character first. Raw UTF-8 byte order disagrees: U+E000 encodes to EE 80 80, U+10000
			// encodes to F0 90 80 80 - 0xEE < 0xF0, so byte order would put the private-use character first. A column
			// that used byte order here would return the wrong slot for either key; this is the exact case the
			// BMP-safe guard (no suffix byte >= 0xF0) exists to prevent.
			final String privateUse = "";
			final String supplementary = "𐀀"; // U+10000
			assertTrue(supplementary.compareTo(privateUse) < 0,
				"precondition: String order must put the supplementary character first");

			final ValueColumn<String> front = new FrontCodedStringColumn<>(BLOCK_SIZE, true);
			final ValueColumn<String> boxed = new BoxedObjectColumn<>(String.class, BLOCK_SIZE);
			// insert in String.compareTo order, exactly as a real tree's findKeyPosition-driven insert would place them
			front.insertKeyAt(0, supplementary);
			front.insertKeyAt(1, privateUse);
			boxed.insertKeyAt(0, supplementary);
			boxed.insertKeyAt(1, privateUse);

			for (final String key : new String[]{supplementary, privateUse}) {
				final InsertionPosition frontPos = front.findKeyPosition(key, 0, 2, null);
				final InsertionPosition boxedPos = boxed.findKeyPosition(key, 0, 2, null);
				assertTrue(frontPos.alreadyPresent(), "Key U+" + Integer.toHexString(key.codePointAt(0)) + " must be found");
				assertEquals(boxedPos.position(), frontPos.position(),
					"Slot mismatch for U+" + Integer.toHexString(key.codePointAt(0)));
			}
		}

		@Test
		@DisplayName("an all-BMP accented-Latin corpus under natural order matches the boxed column")
		void shouldMatchBoxedColumnForAccentedBmpCorpusUnderNaturalOrder() {
			final String[] keys = {"abricot", "ananas", "café", "éclair", "zèbre"};
			final String[] sorted = keys.clone();
			Arrays.sort(sorted); // natural (codepoint) order - what the fast path's gate requires
			final ValueColumn<String> front = new FrontCodedStringColumn<>(BLOCK_SIZE, true);
			final ValueColumn<String> boxed = new BoxedObjectColumn<>(String.class, BLOCK_SIZE);
			for (int i = 0; i < sorted.length; i++) {
				front.insertKeyAt(i, sorted[i]);
				boxed.insertKeyAt(i, sorted[i]);
			}
			for (final String key : keys) {
				final InsertionPosition frontPos = front.findKeyPosition(key, 0, sorted.length, null);
				final InsertionPosition boxedPos = boxed.findKeyPosition(key, 0, sorted.length, null);
				assertTrue(frontPos.alreadyPresent());
				assertEquals(boxedPos.position(), frontPos.position());
			}
			final InsertionPosition frontMiss = front.findKeyPosition("bergamote", 0, sorted.length, null);
			final InsertionPosition boxedMiss = boxed.findKeyPosition("bergamote", 0, sorted.length, null);
			assertEquals(boxedMiss.alreadyPresent(), frontMiss.alreadyPresent());
			assertEquals(boxedMiss.position(), frontMiss.position());
		}

		@Test
		@DisplayName("a probe that is a full byte-prefix of another key resolves via the shorter-key length tiebreak")
		void shouldResolveSharedPrefixDifferentLengthProbesViaTheFastPath() {
			// "cafe"'s entire encoded byte sequence is also "cafeteria"'s leading prefix, so comparing the two exhausts
			// the shared min-length run without a byte difference and falls through to compareUnsignedBytes's
			// `aLen - bLen` tiebreak — this pins that branch directly, both probing the shorter key against the corpus
			// holding the longer one and vice versa
			final String[] keys = {"cafe", "cafeteria"};
			final ValueColumn<String> front = new FrontCodedStringColumn<>(BLOCK_SIZE, true);
			final ValueColumn<String> boxed = new BoxedObjectColumn<>(String.class, BLOCK_SIZE);
			for (int i = 0; i < keys.length; i++) {
				front.insertKeyAt(i, keys[i]);
				boxed.insertKeyAt(i, keys[i]);
			}

			for (final String probe : keys) {
				final InsertionPosition frontPos = front.findKeyPosition(probe, 0, keys.length, null);
				final InsertionPosition boxedPos = boxed.findKeyPosition(probe, 0, keys.length, null);
				assertTrue(frontPos.alreadyPresent(), "Key '" + probe + "' must be found");
				assertEquals(boxedPos.position(), frontPos.position(), "Slot mismatch for '" + probe + "'");
			}

			// an absent probe strictly between the two in length ("cafe" < "cafes" < "cafeteria") still resolves to the
			// same slot as the boxed reference
			final InsertionPosition frontMiss = front.findKeyPosition("cafes", 0, keys.length, null);
			final InsertionPosition boxedMiss = boxed.findKeyPosition("cafes", 0, keys.length, null);
			assertEquals(boxedMiss.alreadyPresent(), frontMiss.alreadyPresent());
			assertEquals(boxedMiss.position(), frontMiss.position());
		}

		@Test
		@DisplayName("a corpus containing the empty string resolves the empty and smallest non-empty probes correctly")
		void shouldResolveEmptyStringKeyViaTheFastPath() {
			// the empty string is a valid front-coded entry (zero-length suffix) and a valid probe (zero-length byte
			// range) — this exercises isBmpSafe / compareUnsignedBytes with a genuinely empty range on both sides
			final String[] keys = {"", "alpha", "bravo"};
			final ValueColumn<String> front = new FrontCodedStringColumn<>(BLOCK_SIZE, true);
			final ValueColumn<String> boxed = new BoxedObjectColumn<>(String.class, BLOCK_SIZE);
			for (int i = 0; i < keys.length; i++) {
				front.insertKeyAt(i, keys[i]);
				boxed.insertKeyAt(i, keys[i]);
			}

			for (final String probe : new String[]{"", "alpha"}) {
				final InsertionPosition frontPos = front.findKeyPosition(probe, 0, keys.length, null);
				final InsertionPosition boxedPos = boxed.findKeyPosition(probe, 0, keys.length, null);
				assertTrue(frontPos.alreadyPresent(), "Key '" + probe + "' must be found");
				assertEquals(boxedPos.position(), frontPos.position(), "Slot mismatch for '" + probe + "'");
			}
		}

		@Test
		@DisplayName("a localized comparator tree never takes the byte-compare fast path, even for an all-BMP corpus")
		@SuppressWarnings({"unchecked", "rawtypes"})
		void shouldNeverTakeFastPathUnderLocalizedComparator() {
			// "Zebre" (byte order starts 0x5A) vs "abricot" (0x61): raw byte order puts "Zebre" first (uppercase <
			// lowercase in ASCII); French collation is case-insensitive and orders "abricot" first - if the fast path
			// incorrectly fired here (naturalOrderSafe wrongly true, or the per-call comparator-identity check
			// skipped), this would locate the wrong slot despite every key being BMP-safe
			final Comparator<String> collator = new LocalizedStringComparator(Locale.FRENCH);
			final String[] words = {"Zebre", "abricot", "éclair", "Mangue", "ananas"};
			final String[] collated = words.clone();
			Arrays.sort(collated, collator);

			final ValueColumnFactory factory = ValueColumnFactory.forKey(String.class, collator);
			final ValueColumn<String> front = factory.create(BLOCK_SIZE);
			final ValueColumn<String> boxed = new BoxedObjectColumn<>(String.class, BLOCK_SIZE);
			for (int i = 0; i < collated.length; i++) {
				front.insertKeyAt(i, collated[i]);
				boxed.insertKeyAt(i, collated[i]);
			}
			for (final String word : words) {
				final InsertionPosition frontPos = front.findKeyPosition(word, 0, collated.length, collator);
				final InsertionPosition boxedPos = boxed.findKeyPosition(word, 0, collated.length, collator);
				assertTrue(frontPos.alreadyPresent());
				assertEquals(boxedPos.position(), frontPos.position());
			}
		}

		@Test
		@DisplayName("a supplementary-plane probe against a BMP-safe corpus falls back to String order")
		void shouldFallBackWhenProbeItselfIsSupplementary() {
			// corpus is entirely BMP-safe (natural order == byte order here), but the PROBE carries a supplementary
			// character - the fast path must reject it via the probe-side BMP check, not just the corpus-side one
			final String[] keys = {"alpha", "bravo", "charlie", "delta", "echo"};
			final ValueColumn<String> front = new FrontCodedStringColumn<>(BLOCK_SIZE, true);
			final ValueColumn<String> boxed = new BoxedObjectColumn<>(String.class, BLOCK_SIZE);
			for (int i = 0; i < keys.length; i++) {
				front.insertKeyAt(i, keys[i]);
				boxed.insertKeyAt(i, keys[i]);
			}
			final String supplementaryProbe = "delta-😀"; // shares "delta"'s prefix, sorts right after it
			final InsertionPosition frontPos = front.findKeyPosition(supplementaryProbe, 0, keys.length, null);
			final InsertionPosition boxedPos = boxed.findKeyPosition(supplementaryProbe, 0, keys.length, null);
			assertEquals(boxedPos.alreadyPresent(), frontPos.alreadyPresent());
			assertEquals(boxedPos.position(), frontPos.position());
		}

		@Test
		@DisplayName("fires under the exact natural-order singleton production wires into every attribute tree")
		@SuppressWarnings({"unchecked", "rawtypes"})
		void shouldFireFastPathUnderTheNaturalOrderSingletonUsedByProduction() {
			// production never passes a null comparator for a natural-order attribute tree - FilterIndex.DEFAULT_COMPARATOR
			// and UniqueIndexBPlusTreeSupport.NATURAL_ORDER both wire the Comparator.naturalOrder() singleton itself, and
			// the fast path's gate (ValueColumnFactory.isNaturalOrder) is an identity check against that very singleton -
			// a null comparator alone (already covered elsewhere in this file) does not exercise that identity-check
			// branch, so this drives it directly with the exact singleton instance production threads through
			final Comparator<String> naturalOrder = Comparator.naturalOrder();
			final String[] probeKeys = {"alpha", "bravo", "charlie", "delta", "echo"};
			final ValueColumn<String> probeFront = new FrontCodedStringColumn<>(BLOCK_SIZE, true);
			final ValueColumn<String> probeBoxed = new BoxedObjectColumn<>(String.class, BLOCK_SIZE);
			for (int i = 0; i < probeKeys.length; i++) {
				probeFront.insertKeyAt(i, probeKeys[i]);
				probeBoxed.insertKeyAt(i, probeKeys[i]);
			}
			for (final String key : probeKeys) {
				final InsertionPosition frontPos = probeFront.findKeyPosition(key, 0, probeKeys.length, naturalOrder);
				final InsertionPosition boxedPos = probeBoxed.findKeyPosition(key, 0, probeKeys.length, naturalOrder);
				assertTrue(frontPos.alreadyPresent(), "Key '" + key + "' must be found");
				assertEquals(boxedPos.position(), frontPos.position(), "Slot mismatch for '" + key + "'");
			}

			final ValueColumnFactory factory = ValueColumnFactory.forKey(String.class, naturalOrder);
			assertInstanceOf(FrontCodedStringColumn.class, factory.create(BLOCK_SIZE));
			final TransactionalBucketBPlusTree<String> tree = new TransactionalBucketBPlusTree<>(
				BLOCK_SIZE, 3, 7, 3, String.class, naturalOrder, factory
			);

			// well beyond one leaf's BLOCK_SIZE (8), so at least one split runs allocate()'s naturalOrderSafe threading
			// through the newly created sibling leaves' columns too, not just the original root leaf's column
			final TreeMap<String, TreeSet<Integer>> oracle = new TreeMap<>();
			for (int i = 0; i < 40; i++) {
				final String key = String.format("code-%03d", i);
				tree.addRecord(key, i + 1_000);
				oracle.computeIfAbsent(key, k -> new TreeSet<>()).add(i + 1_000);
			}

			assertTreeMatchesOracle(tree, oracle);
			verifyConsistent(tree);
		}

		@Test
		@DisplayName("randomized tree workload mixing occasional supplementary-plane keys matches a TreeMap oracle")
		@SuppressWarnings({"unchecked", "rawtypes"})
		void shouldMatchOracleWhenCorpusMixesSupplementaryPlaneKeys() {
			// bmpSafe flips true/false across mutations as supplementary keys come and go (it self-heals on every
			// re-encode) - this drives findKeyPosition through both the fast and fallback paths across one workload,
			// the strongest general regression guard for the BMP-safe predicate
			final ValueColumnFactory factory = ValueColumnFactory.forKey(String.class, null);
			final TransactionalBucketBPlusTree<String> tree = new TransactionalBucketBPlusTree<>(
				BLOCK_SIZE, 3, 7, 3, String.class, null, factory
			);
			final TreeMap<String, TreeSet<Integer>> oracle = new TreeMap<>();
			final Random random = new Random(20260709L);
			final int keyDomain = 60;

			for (int op = 0; op < 8_000; op++) {
				final String key = fuzzKeyMaybeSupplementary(random, keyDomain);
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
				if (op % 200 == 0) {
					assertTreeMatchesOracle(tree, oracle);
				}
			}
			assertTreeMatchesOracle(tree, oracle);
			verifyConsistent(tree);
		}

		@Test
		@DisplayName("a lone unpaired-surrogate probe matches String order via the fallback path")
		void shouldMatchStringOrderForLoneSurrogateProbeViaFallback() {
			// a Java String may legally hold a lone (unpaired) UTF-16 surrogate code unit - String.compareTo (the tree's
			// real natural order) compares it at its true numeric value (0xD800-0xDFFF), which sorts after every plain
			// ASCII/BMP letter. The probe-side BMP check must reject the fast path for such a probe (scanning its UTF-16
			// chars directly, not its post-encode UTF-8 bytes - getBytes(UTF_8) would silently substitute the lone
			// surrogate with the replacement byte '?', which is BMP-range and would wrongly pass a byte-based check), so
			// the search falls through to the always-correct String comparison and agrees with the boxed reference.
			final String[] keys = {"Xa", "Xz"};
			final ValueColumn<String> front = new FrontCodedStringColumn<>(BLOCK_SIZE, true);
			final ValueColumn<String> boxed = new BoxedObjectColumn<>(String.class, BLOCK_SIZE);
			for (int i = 0; i < keys.length; i++) {
				front.insertKeyAt(i, keys[i]);
				boxed.insertKeyAt(i, keys[i]);
			}

			final String probe = "X" + (char) 0xD800; // a lone high surrogate, unpaired
			final InsertionPosition boxedPos = boxed.findKeyPosition(probe, 0, keys.length, null);
			final InsertionPosition frontPos = front.findKeyPosition(probe, 0, keys.length, null);

			assertEquals(boxedPos.alreadyPresent(), frontPos.alreadyPresent());
			assertEquals(boxedPos.position(), frontPos.position());
		}

		/**
		 * Produces a fuzz key that is usually BMP-safe (plain ASCII / 2-byte UTF-8 / BMP private-use) but sometimes
		 * carries a genuine supplementary-plane character (4-byte UTF-8, {@code >= 0xF0} lead byte), so a randomized
		 * workload exercises both {@link FrontCodedStringColumn#bmpSafe} states.
		 *
		 * @param rnd       the RNG
		 * @param keyDomain the number of distinct numeric suffixes, bounding cardinality
		 * @return the generated key
		 */
		@Nonnull
		private static String fuzzKeyMaybeSupplementary(@Nonnull Random rnd, int keyDomain) {
			final int bucket = rnd.nextInt(keyDomain);
			return switch (rnd.nextInt(5)) {
				case 0 -> "sku-" + String.format("%03d", bucket);   // plain ASCII, BMP-safe
				case 1 -> "café-" + bucket;                         // 2-byte UTF-8, BMP-safe
				case 2 -> "-" + bucket;                       // BMP private-use, BMP-safe
				case 3 -> "😀-" + bucket;                 // supplementary U+1F600, NOT BMP-safe
				default -> "𐀀-" + bucket;                // supplementary U+10000, NOT BMP-safe
			};
		}
	}

	/**
	 * Verifies {@link FrontCodedStringColumn#containsUtf8At}: the byte-level containment test the substring path
	 * uses in place of decoding a candidate's key into a {@link String}. What it has to be right about is that byte
	 * containment of two well-formed UTF-8 encodings IS code-point containment, that the decode it reads is bounded
	 * by the key's own length rather than by whatever the reused scratch buffer still holds, and that a column which
	 * does not store UTF-8 refuses the question instead of guessing at it.
	 */
	@Nested
	@DisplayName("matching a pattern against the stored bytes")
	class Utf8Matching {

		/**
		 * Keys chosen to cover what the byte comparison has to be right about: plain ASCII, a precomposed accent and
		 * the SAME text decomposed (NFD, which is the form the index actually stores), a supplementary character
		 * encoded as a surrogate pair, and keys sharing long prefixes so the front coding really front-codes.
		 */
		private static final String[] KEYS = {
			"", "A", "abc", "abcabc", "aXbc",
			"code-0001", "code-0002", "code-000200", "code-01",
			"cafe\u0301 latte",                 // NFD: e + combining acute
			"caf\u00e9 latte",                  // NFC: precomposed e-acute
			"na\u00efve", "na\u0131ve",
			"emoji \uD83D\uDE00 tail",         // supplementary, as a surrogate pair
			"\uD83D\uDE00\uD83D\uDE01",      // two supplementary characters, nothing else
			"\u4f60\u597d\u4e16\u754c"       // three-byte sequences throughout
		};

		/**
		 * Patterns deliberately including ones that share a first byte with a key but do not occur, an empty pattern,
		 * a pattern longer than every key, and multi-byte characters on their own - a combining mark and a
		 * supplementary character - which can only be found whole, at a character boundary, because UTF-8 is
		 * self-synchronizing. (A pattern that is HALF of a character is not expressible here and never will be: every
		 * entry is a well-formed Java `String`.)
		 */
		private static final String[] PATTERNS = {
			"", "a", "abc", "bca", "abcd", "X", "code-", "0002", "00020", "-0001",
			"\u0301", "e\u0301", "\u00e9", "caf", " latte",
			"\uD83D\uDE00", "\uD83D\uDE01", "emoji", "\u597d", "\u4e16\u754c",
			"this pattern is longer than any key in the fixture"
		};

		@Test
		@DisplayName("the byte match answers exactly what String#contains answers, for every key and pattern")
		void shouldAgreeWithStringContains() {
			// The whole optimization rests on one claim - byte containment of two well-formed UTF-8 encodings is the
			// same predicate as code-point containment, because UTF-8 is self-synchronizing and a continuation byte
			// can never begin a sequence. This asserts that claim over the cross product rather than arguing it.
			final String[] sorted = KEYS.clone();
			Arrays.sort(sorted);
			final ValueColumn<String> column = new FrontCodedStringColumn<>(sorted.length, true);
			for (int i = 0; i < sorted.length; i++) {
				column.insertKeyAt(i, sorted[i]);
			}
			assertTrue(column.supportsUtf8Matching(), "the front-coded column is the one that can match bytes");

			for (final String pattern : PATTERNS) {
				final byte[] patternBytes = pattern.getBytes(StandardCharsets.UTF_8);
				for (int slot = 0; slot < sorted.length; slot++) {
					assertEquals(
						sorted[slot].contains(pattern), column.containsUtf8At(slot, patternBytes),
						() -> "byte match diverged from String#contains for pattern [" + pattern + "]"
					);
				}
			}
		}

		@Test
		@DisplayName("a key longer than the decode scratch is matched against the grown buffer, not the discarded one")
		void shouldMatchAKeyThatOutgrowsTheDecodeScratch() throws InterruptedException {
			// `decodeAtBytes` REPLACES `scratch.cur` when a key outgrows it, so a matcher that read the buffer
			// reference before the call would search the discarded array. The decode scratch starts at 48 bytes; these
			// keys are far past that, and the needle sits at the very end so a short/stale buffer cannot contain it.
			//
			// Run on a FRESH thread, and that is load-bearing rather than tidy: the scratch is a static
			// `ThreadLocal` that keeps whatever buffer it has grown to, across columns and across tests. Any earlier
			// test on this thread that decoded a key this long would leave the buffer already large enough, the
			// replacement would never happen, and this test would silently guard nothing while staying green.
			runOnAFreshThread(() -> {
				final int scratchBefore = decodeScratchLength();
				final String longKey = "x".repeat(400) + "NEEDLE";
				final String[] sorted = {longKey, "y" + "z".repeat(500)};
				Arrays.sort(sorted);
				final ValueColumn<String> column = new FrontCodedStringColumn<>(sorted.length, true);
				for (int i = 0; i < sorted.length; i++) {
					column.insertKeyAt(i, sorted[i]);
				}
				final int longKeySlot = Arrays.asList(sorted).indexOf(longKey);
				assertTrue(
					column.containsUtf8At(longKeySlot, "NEEDLE".getBytes(StandardCharsets.UTF_8)),
					"the needle sits beyond the initial scratch capacity and must still be found"
				);
				assertFalse(column.containsUtf8At(longKeySlot, "ABSENT".getBytes(StandardCharsets.UTF_8)));
				assertTrue(
					decodeScratchLength() > scratchBefore,
					"the buffer must really have been replaced, or the defect this test is named for cannot occur "
						+ "and the assertions above prove nothing"
				);
			});
		}

		@Test
		@DisplayName("a short key does not match a pattern left in the scratch by a longer predecessor")
		void shouldNotMatchStaleTailBytesOfALongerPredecessor() {
			// A slot is decoded by walking forward from its restart point, so reading slot 1 first decodes slot 0 into
			// the same buffer. The two keys share no prefix, so the short one is written over the leading bytes only
			// and the long one's tail is still sitting behind it - `indexOfBytes` is bounded by the decoded LENGTH and
			// must never see it. This is the byte-path analogue of the stale-tail guard the `String` path carries.
			final String longKey = "a" + "q".repeat(400) + "TAILNEEDLE";
			final String shortKey = "b-short";
			final ValueColumn<String> column = new FrontCodedStringColumn<>(BLOCK_SIZE, true);
			column.insertKeyAt(0, longKey);
			column.insertKeyAt(1, shortKey);

			final byte[] needle = "TAILNEEDLE".getBytes(StandardCharsets.UTF_8);
			assertTrue(column.containsUtf8At(0, needle), "the long key really does end with the needle");
			assertFalse(
				column.containsUtf8At(1, needle),
				"the short key must be searched to its own length, not into the tail its predecessor left behind"
			);
			assertEquals(shortKey, column.keyAt(1), "and the key itself must be unaffected either way");
		}

		@Test
		@DisplayName("byte matching still agrees with String#contains after removals have re-encoded the blob")
		void shouldAgreeWithStringContainsAfterMutations() {
			// Every other case here reads a column built by consecutive inserts alone. A removal re-encodes the blob
			// and rebuilds the restart offsets, and the byte path resolves a slot against those offsets on every
			// call - so a column that has been mutated is the shape most likely to expose a stale resolution.
			final String[] patterns = {"sku-", "-01", "café", "😀", "zzz"};
			final ValueColumn<String> front = new FrontCodedStringColumn<>(64, true);
			final ValueColumn<String> oracle = new BoxedObjectColumn<>(String.class, 64);
			final Random rnd = new Random(20_260_831L);
			int size = 0;
			for (int op = 0; op < 400; op++) {
				final boolean insert = size == 0 || (size < 64 && rnd.nextInt(100) < 60);
				if (insert) {
					final int pos = rnd.nextInt(size + 1);
					final String key = mutationKey(rnd);
					front.insertKeyAt(pos, key);
					oracle.insertKeyAt(pos, key);
					size++;
				} else {
					final int pos = rnd.nextInt(size);
					front.removeKeyAt(pos);
					oracle.removeKeyAt(pos);
					size--;
					front.clearAt(size);
					oracle.clearAt(size);
				}
				for (final String pattern : patterns) {
					final byte[] patternBytes = pattern.getBytes(StandardCharsets.UTF_8);
					for (int slot = 0; slot < size; slot++) {
						final int reportedSlot = slot;
						final int reportedOp = op;
						assertEquals(
							oracle.keyAt(slot).contains(pattern), front.containsUtf8At(slot, patternBytes),
							() -> "byte match diverged from String#contains at slot " + reportedSlot + " after op "
								+ reportedOp + " for pattern [" + pattern + "]"
						);
					}
				}
			}
		}

		/**
		 * @param rnd the workload's RNG
		 * @return a key mixing ASCII, a two-byte accent, a supplementary character and long shared prefixes, so the
		 * front coding really front-codes and the byte comparison meets every sequence length
		 */
		@Nonnull
		private static String mutationKey(@Nonnull Random rnd) {
			final int bucket = rnd.nextInt(200);
			return switch (rnd.nextInt(4)) {
				case 0 -> "sku-" + String.format("%03d", bucket);
				case 1 -> "café-" + bucket;
				case 2 -> "😀-" + bucket;
				default -> "shared-prefix-" + String.format("%03d", bucket) + "-tail";
			};
		}

		/**
		 * Runs `body` on a thread of its own and joins it, rethrowing whatever it threw.
		 *
		 * The decode scratch is a static {@link ThreadLocal} that keeps the buffer it has grown to for the life of the
		 * thread, so a test about the buffer being REPLACED can only observe that on a thread whose scratch is still
		 * the initial one.
		 *
		 * @param body the assertions to run in isolation
		 * @throws InterruptedException when the join is interrupted
		 */
		private static void runOnAFreshThread(@Nonnull Runnable body) throws InterruptedException {
			final Throwable[] failure = new Throwable[1];
			final Thread thread = new Thread(
				() -> {
					try {
						body.run();
					} catch (Throwable ex) {
						failure[0] = ex;
					}
				},
				"fc-fresh-scratch"
			);
			thread.start();
			thread.join();
			if (failure[0] instanceof final AssertionError assertionError) {
				throw assertionError;
			} else if (failure[0] != null) {
				throw new GenericEvitaInternalError("the isolated body failed", failure[0]);
			}
		}

		/**
		 * @return the current capacity of the calling thread's decode scratch buffer
		 */
		private static int decodeScratchLength() {
			try {
				final Field scratchHolder = FrontCodedStringColumn.class.getDeclaredField("SCRATCH");
				scratchHolder.setAccessible(true);
				final Object scratch = ((ThreadLocal<?>) scratchHolder.get(null)).get();
				final Field buffer = scratch.getClass().getDeclaredField("cur");
				buffer.setAccessible(true);
				return ((byte[]) buffer.get(scratch)).length;
			} catch (ReflectiveOperationException ex) {
				throw new GenericEvitaInternalError(
					"the decode scratch is no longer shaped as this test reads it, so it can no longer tell a grown "
						+ "buffer from an initial one.", ex
				);
			}
		}

		@Test
		@DisplayName("every restart block offset decodes and matches, not only the restart points themselves")
		void shouldMatchAtEveryOffsetWithinARestartBlock() {
			// a slot is decoded by seeking its restart point and walking forward, so the walk length varies from 0 to
			// RESTART_INTERVAL - 1 across the block; a matcher fed a partially-walked buffer would pass at offset 0
			// and fail further in
			final int count = 40;
			final String[] keys = new String[count];
			for (int i = 0; i < count; i++) {
				keys[i] = String.format("shared-prefix-%03d-suffix", i);
			}
			final ValueColumn<String> column = new FrontCodedStringColumn<>(count, true);
			for (int i = 0; i < count; i++) {
				column.insertKeyAt(i, keys[i]);
			}
			for (int i = 0; i < count; i++) {
				final String own = String.format("-%03d-", i);
				assertTrue(
					column.containsUtf8At(i, own.getBytes(StandardCharsets.UTF_8)),
					"slot " + i + " must contain its own ordinal"
				);
				assertFalse(
					column.containsUtf8At(i, String.format("-%03d-", (i + 1) % count).getBytes(StandardCharsets.UTF_8)),
					"slot " + i + " must not contain a neighbour's ordinal"
				);
			}
		}

		@Test
		@DisplayName("no other column claims to store WTF-8, and each refuses rather than guessing")
		void shouldRefuseByteMatchingWhereKeysAreNotUtf8() {
			// `ValueColumn` is sealed and the front-coded one is the only implementation holding its keys as WTF-8,
			// so the capability default and the refusing default apply to every OTHER implementation. Each is checked
			// here rather than one standing in for the rest: they are what the fallback to the predicate rests on.
			final ValueColumn<String> boxed = new BoxedObjectColumn<>(String.class, BLOCK_SIZE);
			boxed.insertKeyAt(0, "abc");
			final ValueColumn<?>[] columns = {
				boxed,
				new IntValueColumn<Integer>(BLOCK_SIZE),
				new LongValueColumn<Integer>(LongKeyCodec.forType(Integer.class), BLOCK_SIZE),
				new InstantValueColumn<Instant>(BLOCK_SIZE)
			};
			final byte[] pattern = "abc".getBytes(StandardCharsets.UTF_8);
			for (final ValueColumn<?> column : columns) {
				final String name = column.getClass().getSimpleName();
				assertFalse(column.supportsUtf8Matching(), name + " stores no UTF-8 keys and must say so");
				assertThrows(
					GenericEvitaInternalError.class,
					() -> column.containsUtf8At(0, pattern),
					"the default must throw rather than silently answer, so a caller that skips the capability check "
						+ "fails loudly instead of returning a wrong answer - " + name
				);
			}
		}

	}

	/**
	 * Verifies that an unpaired UTF-16 surrogate — legal in a Java {@link String}, unrepresentable in UTF-8 — round-
	 * trips through {@link FrontCodedStringColumn} instead of being silently replaced by {@code '?'}, stays distinct
	 * from a value that genuinely contains one, orders exactly as {@link String#compareTo} does, and keeps decoding
	 * correctly across every path the column's WTF-8 flags have to survive: a shared-prefix boundary that splits the
	 * surrogate's own byte sequence, MVCC duplication, range moves, truncation, and a restart-block boundary.
	 */
	@Nested
	@DisplayName("unpaired surrogates survive the column")
	class UnpairedSurrogates {

		/**
		 * A lone high surrogate. Legal in a Java `String`, and something a client can genuinely send - a UTF-16 string
		 * truncated mid-pair by an upstream system is the usual way one arrives.
		 */
		private static final String LONE_SURROGATE_VALUE = "a\uD800c";
		/** The value the column used to store instead, when it encoded its keys as UTF-8. */
		private static final String SUBSTITUTED_VALUE = "a?c";

		@Test
		@DisplayName("a stored key comes back as the same string")
		void shouldRoundTripAnUnpairedSurrogate() {
			// UTF-8 has no representation for half a surrogate pair and `String#getBytes` substitutes `0x3F` ('?') for
			// one without saying so, which used to make this column a lossy container: the key came back as a DIFFERENT
			// string from the one inserted. That is why `InvertedIndex#notifyValueCreated` then failed to resolve the id
			// of the bucket it had just created - it re-probes with the original string, which was no longer what the
			// tree held. The column now encodes with WTF-8, which differs from UTF-8 on exactly this input.
			final ValueColumn<String> column = new FrontCodedStringColumn<>(BLOCK_SIZE, true);
			column.insertKeyAt(0, LONE_SURROGATE_VALUE);

			assertEquals(
				LONE_SURROGATE_VALUE, column.keyAt(0),
				"the column must return the key it was given"
			);
		}

		@Test
		@DisplayName("the stored key is not silently altered")
		void shouldNotSilentlyAlterAStoredKey() {
			// Pinned separately from the round trip because the two failed for reasons worth telling apart: a round trip
			// can be satisfied by refusing the value outright, whereas this one insists that whatever comes back is not
			// some OTHER value the caller never supplied.
			final ValueColumn<String> column = new FrontCodedStringColumn<>(BLOCK_SIZE, true);
			column.insertKeyAt(0, LONE_SURROGATE_VALUE);

			assertNotEquals(
				SUBSTITUTED_VALUE, column.keyAt(0),
				"an unpaired surrogate must not be replaced by '?' - a value the caller never supplied, stored without "
					+ "any error, and indistinguishable afterwards from a value that really did contain a question mark"
			);
		}

		@Test
		@DisplayName("a surrogate value and a question-mark value stay two distinct keys")
		void shouldKeepASurrogateValueDistinctFromItsSubstitution() {
			// The sharpest consequence of the old encoding, and the one a unique index would have surfaced as a false
			// duplicate: two values that are not equal both encoded to the same bytes. Ordering matters here - "a?c"
			// sorts first because '?' (0x3F) is below the surrogate's WTF-8 lead byte.
			final ValueColumn<String> column = new FrontCodedStringColumn<>(BLOCK_SIZE, true);
			column.insertKeyAt(0, SUBSTITUTED_VALUE);
			column.insertKeyAt(1, LONE_SURROGATE_VALUE);

			assertEquals(SUBSTITUTED_VALUE, column.keyAt(0));
			assertEquals(LONE_SURROGATE_VALUE, column.keyAt(1));
			assertNotEquals(
				column.keyAt(0), column.keyAt(1),
				"the two keys must not have collapsed into one another"
			);
		}

		@Test
		@DisplayName("every shape of unpaired surrogate round-trips, alone and among ordinary keys")
		void shouldRoundTripEveryShapeOfUnpairedSurrogate() {
			// The five shapes `Wtf8#hasUnpairedSurrogate` has to tell apart, plus two well-formed controls that must NOT
			// take the by-hand encoder: a real emoji (a proper pair, 4-byte supplementary form) and plain ASCII.
			final String[] shapes = {
				"\uD800",                 // lone high, whole value
				"\uDC00",                 // lone low, whole value
				"a\uD800",                // lone high, at the very end
				"\uD800a",                // lone high, at the very start
				"a\uD800b\uDC00c",        // one of each, interleaved with ordinary text
				"a\uD83D\uDE00c",         // a WELL-FORMED pair - must keep the 4-byte supplementary form
				"plain"                   // ASCII control
			};
			for (final String shape : shapes) {
				final ValueColumn<String> column = new FrontCodedStringColumn<>(BLOCK_SIZE, true);
				column.insertKeyAt(0, shape);
				assertEquals(shape, column.keyAt(0), "insertKeyAt must round-trip " + describe(shape));

				final ValueColumn<String> bulk = new FrontCodedStringColumn<>(BLOCK_SIZE, true);
				bulk.bulkLoad(new Object[]{shape}, 1);
				assertEquals(shape, bulk.keyAt(0), "bulkLoad must round-trip " + describe(shape));
			}
		}

		@Test
		@DisplayName("a surrogate wholly inside the shared prefix survives")
		void shouldRoundTripWhenTheSurrogateSitsWhollyInsideTheSharedPrefix() {
			// Front coding stores most keys as (shared prefix length, remaining bytes). Here the sequence sits
			// ENTIRELY inside the shared prefix - every key shares the complete "a\uD800" and differs only in the
			// trailing character - so only the restart entry carries it in a suffix of its own. The neighbouring case,
			// where the boundary falls INSIDE the three-byte sequence, is a different and much sharper one and is
			// pinned separately by `shouldRoundTripWhenAPrefixBoundarySplitsASurrogateSequence`.
			final String[] keys = {"a\uD800a", "a\uD800b", "a\uD800c", "a\uD800d"};
			final ValueColumn<String> column = new FrontCodedStringColumn<>(BLOCK_SIZE, true);
			column.bulkLoad(keys, keys.length);

			for (int i = 0; i < keys.length; i++) {
				assertEquals(keys[i], column.keyAt(i), "prefix-shared key " + i + " must round-trip");
			}
		}

		@Test
		@DisplayName("a prefix boundary that splits a surrogate sequence still round-trips")
		void shouldRoundTripWhenAPrefixBoundarySplitsASurrogateSequence() {
			// U+D7FF and U+D800 are adjacent code points that encode to `ED 9F BF` and `ED A0 80` - they SHARE the
			// lead byte and differ in the second, which is the byte that decides whether the sequence is a surrogate
			// at all. Front coding therefore ends the shared prefix INSIDE the three-byte sequence: the second key's
			// suffix is just `A0 80`, holding no `ED` of its own, while the first key's suffix holds an `ED` that is
			// legitimately not a surrogate. A scan that looks only at suffixes sees no surrogate anywhere and leaves
			// the column's decode gate closed, so the key decodes through the JDK's UTF-8 decoder and comes back as
			// U+FFFD - the exact corruption this codec exists to remove, reintroduced through a narrower door.
			final String[] keys = {"a\ud7ff", "a\ud800"};
			final ValueColumn<String> column = new FrontCodedStringColumn<>(BLOCK_SIZE, true);
			column.bulkLoad(keys, keys.length);

			assertEquals(keys[0], column.keyAt(0), "the non-surrogate neighbour must round-trip");
			assertEquals(
				keys[1], column.keyAt(1),
				"the surrogate must round-trip even though the shared prefix ends between its lead and second byte"
			);

			// decoding is only half of the contract. The production symptom was the tree re-probing with the value it
			// had just inserted and being told the key is absent, so the lookup this shape breaks is asserted too - a
			// change that restored correct decoding through a path that still misled the binary search would otherwise
			// leave this test green
			for (int i = 0; i < keys.length; i++) {
				final InsertionPosition found = column.findKeyPosition(keys[i], 0, keys.length, null);
				assertTrue(found.alreadyPresent(), "lookup must find " + describe(keys[i]));
				assertEquals(i, found.position(), "lookup must land on the right slot for " + describe(keys[i]));
			}
		}

		@Test
		@DisplayName("a surrogate key is found by lookup, and orders as String#compareTo does")
		void shouldFindAndOrderASurrogateKeyExactlyAsStringComparisonWould() {
			// The reason the fast path may keep a lone surrogate: over the BMP, WTF-8 byte order IS code-point order IS
			// UTF-16 code-unit order. The oracle is a plain sort - if byte order and String order disagreed anywhere,
			// the physical order below would differ from it.
			final String[] keys = {"a?c", "a\uD800c", "a\uE000c", "abc"};
			final String[] expected = keys.clone();
			Arrays.sort(expected);

			final ValueColumn<String> column = new FrontCodedStringColumn<>(BLOCK_SIZE, true);
			column.bulkLoad(expected, expected.length);

			for (int i = 0; i < expected.length; i++) {
				assertEquals(expected[i], column.keyAt(i), "slot " + i + " must hold the naturally-ordered key");
				final InsertionPosition found = column.findKeyPosition(expected[i], 0, expected.length, null);
				assertTrue(found.alreadyPresent(), "lookup must find " + describe(expected[i]));
				assertEquals(i, found.position(), "lookup must land on the right slot for " + describe(expected[i]));
			}

			// a probe that is ABSENT takes the insertion-position branch instead of the hit branch, and takes it while
			// binary-searching PAST a stored surrogate - which the fast path really does compare by raw bytes, since an
			// encoded lone surrogate sits below the column's `>= 0xF0` supplementary threshold
			final String absentKey = "a\uD800b";
			final InsertionPosition absent = column.findKeyPosition(absentKey, 0, expected.length, null);
			assertFalse(absent.alreadyPresent(), describe(absentKey) + " is not in the corpus");
			assertEquals(
				-Arrays.binarySearch(expected, absentKey) - 1, absent.position(),
				"the insertion point must be the one a plain sorted array would report"
			);
		}

		@Test
		@DisplayName("the surrogate flag is carried into a duplicated column and cleared when the key leaves")
		void shouldMaintainTheSurrogateFlagAcrossDuplicationAndRemoval() {
			// The decode side is gated on a per-column flag rather than a byte scan, so the flag has to travel with the
			// blob through the MVCC duplicate path - a duplicate that lost it would decode the shared bytes with the
			// JDK's decoder and hand back U+FFFD instead of the surrogate.
			final ValueColumn<String> column = new FrontCodedStringColumn<>(BLOCK_SIZE, true);
			column.insertKeyAt(0, "abc");
			column.insertKeyAt(1, LONE_SURROGATE_VALUE);

			final ValueColumn<String> copy = column.duplicate();
			assertEquals(
				LONE_SURROGATE_VALUE, copy.keyAt(1),
				"a duplicated column must decode the shared blob exactly as its original does"
			);

			// and once the only surrogate key is gone the remaining keys must still decode. Note what this half can
			// and cannot fail on: a flag left stale at `true` merely decodes "abc" the slow way and still returns
			// "abc", so the direction that a stale `false` would break is pinned separately, by
			// `shouldKeepASurrogateKeyDecodableWhenAnOrdinaryKeyIsRemoved`.
			column.removeKeyAt(1);
			assertEquals("abc", column.keyAt(0), "the surviving key must be unaffected by the removal");
			assertEquals(
				LONE_SURROGATE_VALUE, copy.keyAt(1),
				"the duplicate must not observe the original's removal"
			);
		}

		@Test
		@DisplayName("asBoxedArray decodes a surrogate too, not only keyAt")
		void shouldRoundTripASurrogateThroughAsBoxedArray() {
			// `decodeAll` is the SECOND gated decode site - it reaches callers through `asBoxedArray`, used by the
			// consistency verification and `toString`. Deleting its gate leaves every other test in this file green,
			// so without this one the branch is guarded by nothing at all.
			final String[] keys = {"a\ud7ff", "a\ud800"};
			final ValueColumn<String> column = new FrontCodedStringColumn<>(BLOCK_SIZE, true);
			column.bulkLoad(keys, keys.length);

			final String[] boxed = column.asBoxedArray();
			assertEquals(keys[0], boxed[0], "the ordinary neighbour must box intact");
			assertEquals(
				keys[1], boxed[1],
				"asBoxedArray must decode the surrogate rather than substituting U+FFFD"
			);
		}

		@Test
		@DisplayName("range moves and truncation leave surviving surrogate keys decodable")
		void shouldKeepSurrogateKeysDecodableThroughRangeMovesAndTruncation() {
			// `copyRangeTo` rebuilds the DESTINATION's blob from the moved slice, so the destination has to derive
			// the decode gate for itself rather than inherit it; `fillEmpty` and `clearAt` re-encode what is left,
			// so a gate that was not recomputed would strand the survivors. These are the leaf split / merge / steal
			// paths, reached here directly instead of through a tree.
			final String[] keys = {"a\ud7ff", "a\ud800", "a\udfff", "b"};
			final ValueColumn<String> source = new FrontCodedStringColumn<>(BLOCK_SIZE, true);
			source.bulkLoad(keys, keys.length);

			final ValueColumn<String> destination = source.allocate(BLOCK_SIZE);
			source.copyRangeTo(0, destination, 0, keys.length);
			for (int i = 0; i < keys.length; i++) {
				assertEquals(keys[i], destination.keyAt(i), "copyRangeTo must carry key " + i + " intact");
			}

			// truncate away the tail, keeping one surrogate behind - a gate left stale by the re-encode would decode
			// the survivor through the JDK's UTF-8 decoder and hand back U+FFFD
			destination.fillEmpty(2, keys.length);
			assertEquals(keys[0], destination.keyAt(0), "the ordinary neighbour must survive fillEmpty");
			assertEquals(keys[1], destination.keyAt(1), "the surviving surrogate must still decode after fillEmpty");

			source.clearAt(2);
			assertEquals(keys[1], source.keyAt(1), "the surviving surrogate must still decode after clearAt");
		}

		@Test
		@DisplayName("removing an ordinary key leaves a surviving surrogate key decodable")
		void shouldKeepASurrogateKeyDecodableWhenAnOrdinaryKeyIsRemoved() {
			// The removal the case above makes takes the ONLY surrogate key out, which cannot fail whichever way the
			// flag was recomputed. This is the other direction - the surrogate STAYS, and the re-encode the removal
			// forces has to go on saying so, or the survivor decodes through the JDK's decoder and comes back as
			// U+FFFD.
			final ValueColumn<String> column = new FrontCodedStringColumn<>(BLOCK_SIZE, true);
			column.bulkLoad(new Object[]{"abc", LONE_SURROGATE_VALUE, "zzz"}, 3);
			column.removeKeyAt(0);

			assertEquals(LONE_SURROGATE_VALUE, column.keyAt(0), "the surviving surrogate key must still decode");
			assertEquals("zzz", column.keyAt(1), "the surviving ordinary key must be unaffected");
		}

		@Test
		@DisplayName("a corpus larger than one restart block round-trips on both sides of the boundary")
		void shouldRoundTripASurrogateCorpusAcrossARestartBoundary() {
			// Every sixteenth entry is a restart point, stored in full rather than front-coded against its predecessor
			// - which is where the encode-time argument about a shared prefix hiding a lead byte terminates. No other
			// surrogate case here reaches that far: they are four keys long at most. These twenty pair each stem with
			// U+D7FF and U+D800, so EVERY odd slot is a boundary that falls inside a three-byte sequence, slot 16 is a
			// restart entry, and surrogate keys sit on both sides of it.
			final String[] keys = new String[20];
			for (int i = 0; i < keys.length; i++) {
				keys[i] = String.format("k%02d", i / 2) + (i % 2 == 0 ? "\ud7ff" : "\ud800");
			}

			final ValueColumn<String> column = new FrontCodedStringColumn<>(64, true);
			column.bulkLoad(keys, keys.length);

			for (int i = 0; i < keys.length; i++) {
				assertEquals(keys[i], column.keyAt(i), "slot " + i + " must round-trip " + describe(keys[i]));
			}
		}

		@Test
		@DisplayName("normalization is the wrong lever - NFC and NFD both preserve the surrogate")
		void shouldShowNormalizationDoesNotAffectTheDefect() {
			// Recorded because it is the natural first guess and it is wrong. An unpaired surrogate participates in no
			// canonical composition or decomposition, so neither form touches it; the loss happened strictly at the
			// encoding step below normalization. Changing the tree's normalization form would not have helped.
			assertEquals(
				LONE_SURROGATE_VALUE, Normalizer.normalize(LONE_SURROGATE_VALUE, Normalizer.Form.NFD),
				"NFD must leave a lone surrogate untouched"
			);
			assertEquals(
				LONE_SURROGATE_VALUE, Normalizer.normalize(LONE_SURROGATE_VALUE, Normalizer.Form.NFC),
				"NFC must leave a lone surrogate untouched"
			);
		}
	}

}
