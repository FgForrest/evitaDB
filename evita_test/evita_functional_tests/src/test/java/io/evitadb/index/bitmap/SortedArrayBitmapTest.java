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

package io.evitadb.index.bitmap;

import net.openhft.hashing.LongHashFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.PrimitiveIterator.OfInt;
import java.util.Spliterator;
import java.util.function.IntConsumer;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the read-only, array-backed {@link SortedArrayBitmap} - the multi-record sibling of
 * {@link SingleRecordBitmap} that shares the record array it wraps instead of copying it.
 *
 * The class carries two contracts that are easy to break by accident and that nothing else in the suite pins: the
 * split between the **unsigned** order it enumerates in and the **signed** order {@link SortedArrayBitmap#getArray()}
 * answers in, and the fact that the wrapped array is borrowed rather than owned.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(DATA_TYPE)
@DisplayName("Sorted array bitmap")
class SortedArrayBitmapTest {
	/**
	 * A record set holding both signs, in the unsigned order the class contracts for: every negative id sits in the
	 * suffix, because it is unsigned-greater than every non-negative one.
	 */
	private static final int[] MIXED_SIGN_IDS = {0, 5, Integer.MAX_VALUE, Integer.MIN_VALUE, -1};

	/**
	 * @param ids the record ids, unsigned-sorted and distinct
	 * @return the ids drained out of a fresh iterator, in the order the bitmap enumerates them
	 */
	@Nonnull
	private static int[] drainIterator(@Nonnull int... ids) {
		final SortedArrayBitmap bitmap = new SortedArrayBitmap(ids);
		final int[] drained = new int[ids.length];
		int index = 0;
		final OfInt iterator = bitmap.iterator();
		while (iterator.hasNext()) {
			drained[index++] = iterator.nextInt();
		}
		assertEquals(ids.length, index, "the iterator must yield exactly one value per record id");
		return drained;
	}

	@Nested
	@DisplayName("Read accessors")
	class ReadAccessorsTest {

		@Test
		@DisplayName("An empty view reports no records and refuses every positional accessor")
		void shouldExposeAnEmptyRecordSet() {
			final SortedArrayBitmap bitmap = new SortedArrayBitmap();

			assertTrue(bitmap.isEmpty());
			assertEquals(0, bitmap.size());
			assertFalse(bitmap.contains(0));
			assertFalse(bitmap.contains(-1));
			assertArrayEquals(new int[0], bitmap.getArray());
			assertArrayEquals(new int[0], bitmap.getRange(0, 0));
			assertEquals("[]", bitmap.toString());
			assertFalse(bitmap.iterator().hasNext());
			assertThrows(IndexOutOfBoundsException.class, bitmap::getFirst);
			assertThrows(IndexOutOfBoundsException.class, bitmap::getLast);
			assertThrows(IndexOutOfBoundsException.class, () -> bitmap.get(0));
		}

		@Test
		@DisplayName("A single-element view answers the one id through every accessor")
		void shouldExposeASingleRecord() {
			final SortedArrayBitmap bitmap = new SortedArrayBitmap(42);

			assertFalse(bitmap.isEmpty());
			assertEquals(1, bitmap.size());
			assertTrue(bitmap.contains(42));
			assertFalse(bitmap.contains(41));
			assertEquals(42, bitmap.get(0));
			assertEquals(42, bitmap.getFirst());
			assertEquals(42, bitmap.getLast());
			assertArrayEquals(new int[]{42}, bitmap.getArray());
			assertEquals("[42]", bitmap.toString());
		}

		@Test
		@DisplayName("A multi-element view answers first, last and membership in unsigned order")
		void shouldExposeAMultiRecordSet() {
			final SortedArrayBitmap bitmap = new SortedArrayBitmap(MIXED_SIGN_IDS);

			assertEquals(5, bitmap.size());
			assertEquals(0, bitmap.getFirst());
			assertEquals(-1, bitmap.getLast(), "under unsigned ordering -1 is the greatest id there is");
			assertEquals(Integer.MIN_VALUE, bitmap.get(3));
			assertTrue(bitmap.contains(Integer.MIN_VALUE));
			assertTrue(bitmap.contains(Integer.MAX_VALUE));
			assertFalse(bitmap.contains(-2));
			assertEquals("[0, 5, 2147483647, -2147483648, -1]", bitmap.toString());
		}
	}

	@Nested
	@DisplayName("indexOf binary-search contract")
	class IndexOfTest {

		@Test
		@DisplayName("indexOf answers the exact insertion point of every gap")
		void shouldReturnTheExactInsertionPoint() {
			// the insertion point is not decoration - OverflowRecords.add uses `-position - 1` directly as the index
			// it inserts the new record id at, so an off-by-one here corrupts a bucket's ordering
			final SortedArrayBitmap bitmap = new SortedArrayBitmap(2, 4, 6);

			assertEquals(0, bitmap.indexOf(2));
			assertEquals(1, bitmap.indexOf(4));
			assertEquals(2, bitmap.indexOf(6));
			assertEquals(-1, bitmap.indexOf(1));
			assertEquals(-2, bitmap.indexOf(3));
			assertEquals(-3, bitmap.indexOf(5));
			assertEquals(-4, bitmap.indexOf(7));
		}

		@Test
		@DisplayName("indexOf on an empty view reports insertion point zero")
		void shouldReportInsertionPointZeroWhenEmpty() {
			assertEquals(-1, new SortedArrayBitmap().indexOf(7));
		}

		@Test
		@DisplayName("indexOf compares unsigned, so a negative id is found in the suffix")
		void shouldSearchAcrossTheSignBoundary() {
			final SortedArrayBitmap bitmap = new SortedArrayBitmap(MIXED_SIGN_IDS);

			assertEquals(0, bitmap.indexOf(0));
			assertEquals(1, bitmap.indexOf(5));
			assertEquals(2, bitmap.indexOf(Integer.MAX_VALUE));
			assertEquals(3, bitmap.indexOf(Integer.MIN_VALUE));
			assertEquals(4, bitmap.indexOf(-1));
			// -2 is unsigned-between MIN_VALUE and -1, so it belongs at index 4
			assertEquals(-5, bitmap.indexOf(-2));
			// 1 is unsigned-between 0 and 5
			assertEquals(-2, bitmap.indexOf(1));
		}
	}

	@Nested
	@DisplayName("Negative-suffix split")
	class FirstNegativeIndexTest {

		@Test
		@DisplayName("An empty array has its negative suffix at index zero")
		void shouldReportZeroForAnEmptyArray() {
			assertEquals(0, SortedArrayBitmap.firstNegativeIndex(new int[0]));
		}

		@Test
		@DisplayName("An array without negatives has its suffix start past the end")
		void shouldReportTheLengthWhenNoIdIsNegative() {
			assertEquals(3, SortedArrayBitmap.firstNegativeIndex(new int[]{0, 5, Integer.MAX_VALUE}));
		}

		@Test
		@DisplayName("An all-negative array has its suffix start at index zero")
		void shouldReportZeroWhenEveryIdIsNegative() {
			assertEquals(0, SortedArrayBitmap.firstNegativeIndex(new int[]{Integer.MIN_VALUE, -7, -1}));
		}

		@Test
		@DisplayName("The split lands on the first negative id whether or not MIN_VALUE is present")
		void shouldSplitAtTheFirstNegativeId() {
			// MIN_VALUE present: the search finds it exactly
			assertEquals(3, SortedArrayBitmap.firstNegativeIndex(MIXED_SIGN_IDS));
			// MIN_VALUE absent: the search misses and the insertion point is the boundary
			assertEquals(2, SortedArrayBitmap.firstNegativeIndex(new int[]{1, 2, -9, -1}));
		}
	}

	@Nested
	@DisplayName("Signed array projection")
	class GetArrayTest {

		@Test
		@DisplayName("A set holding negatives comes back negatives-first")
		void shouldRotateTheNegativeSuffixToTheFront() {
			final SortedArrayBitmap bitmap = new SortedArrayBitmap(MIXED_SIGN_IDS);

			assertArrayEquals(
				new int[]{Integer.MIN_VALUE, -1, 0, 5, Integer.MAX_VALUE}, bitmap.getArray(),
				"getArray answers in signed order, the order every consumer of a bucket's record array expects"
			);
		}

		@Test
		@DisplayName("A set without negatives comes back unchanged")
		void shouldLeaveANonNegativeSetInPlace() {
			assertArrayEquals(new int[]{0, 5, Integer.MAX_VALUE}, new SortedArrayBitmap(0, 5, Integer.MAX_VALUE).getArray());
		}

		@Test
		@DisplayName("Each call hands out a fresh array that cannot reach the wrapped one")
		void shouldReturnAFreshArrayOnEachCall() {
			final int[] backing = {1, 2, 3};
			final SortedArrayBitmap bitmap = new SortedArrayBitmap(backing);

			final int[] first = bitmap.getArray();
			final int[] second = bitmap.getArray();

			assertNotSame(first, second);
			assertNotSame(backing, first);
			first[0] = 999;
			assertArrayEquals(new int[]{1, 2, 3}, backing);
			assertArrayEquals(new int[]{1, 2, 3}, bitmap.getArray());
		}

		@Test
		@DisplayName("The projection equals the one an equivalent BaseBitmap answers, both signs included")
		void shouldMatchTheEquivalentBaseBitmap() {
			assertArrayEquals(
				new BaseBitmap(MIXED_SIGN_IDS).getArray(), new SortedArrayBitmap(MIXED_SIGN_IDS).getArray()
			);
			final int[] nonNegative = {0, 5, 9, Integer.MAX_VALUE};
			assertArrayEquals(
				new BaseBitmap(nonNegative).getArray(), new SortedArrayBitmap(nonNegative).getArray()
			);
		}
	}

	@Nested
	@DisplayName("getRange bounds")
	class GetRangeTest {

		@Test
		@DisplayName("A valid slice comes back in unsigned order")
		void shouldSliceInUnsignedOrder() {
			final SortedArrayBitmap bitmap = new SortedArrayBitmap(MIXED_SIGN_IDS);

			assertArrayEquals(new int[]{0, 5}, bitmap.getRange(0, 2));
			assertArrayEquals(new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE}, bitmap.getRange(2, 4));
			assertArrayEquals(MIXED_SIGN_IDS, bitmap.getRange(0, 5));
		}

		@Test
		@DisplayName("An empty slice at the end of the array is legal")
		void shouldAllowAnEmptySliceAtTheEnd() {
			final SortedArrayBitmap bitmap = new SortedArrayBitmap(1, 2, 3);

			assertArrayEquals(new int[0], bitmap.getRange(3, 3));
			assertArrayEquals(new int[0], bitmap.getRange(1, 1));
		}

		@Test
		@DisplayName("Out-of-bounds and inverted bounds are refused")
		void shouldRefuseInvalidBounds() {
			final SortedArrayBitmap bitmap = new SortedArrayBitmap(1, 2, 3);

			assertThrows(IndexOutOfBoundsException.class, () -> bitmap.getRange(-1, 2));
			assertThrows(IndexOutOfBoundsException.class, () -> bitmap.getRange(0, 4));
			assertThrows(IndexOutOfBoundsException.class, () -> bitmap.getRange(2, 1));
		}
	}

	@Nested
	@DisplayName("Immutability")
	class ImmutabilityTest {

		@Test
		@DisplayName("Every mutator is refused, so a borrowed array can never be written through the view")
		void shouldRejectEveryMutation() {
			final SortedArrayBitmap bitmap = new SortedArrayBitmap(1, 2, 3);
			final Bitmap other = new BaseBitmap(4, 5);

			assertThrows(UnsupportedOperationException.class, () -> bitmap.add(4));
			assertThrows(UnsupportedOperationException.class, () -> bitmap.addAll(4, 5));
			assertThrows(UnsupportedOperationException.class, () -> bitmap.addAll(other));
			assertThrows(UnsupportedOperationException.class, () -> bitmap.remove(1));
			assertThrows(UnsupportedOperationException.class, () -> bitmap.removeAll(1, 2));
			assertThrows(UnsupportedOperationException.class, () -> bitmap.removeAll(other));
			assertArrayEquals(new int[]{1, 2, 3}, bitmap.getArray(), "a refused mutation must change nothing");
		}
	}

	@Nested
	@DisplayName("Iterator and stream")
	class IteratorAndStreamTest {

		@Test
		@DisplayName("The iterator enumerates in unsigned order")
		void shouldIterateInUnsignedOrder() {
			assertArrayEquals(MIXED_SIGN_IDS, drainIterator(MIXED_SIGN_IDS));
		}

		@Test
		@DisplayName("Two iterators over the same view advance independently")
		void shouldProvideIndependentIterators() {
			final SortedArrayBitmap bitmap = new SortedArrayBitmap(1, 2, 3);

			final OfInt first = bitmap.iterator();
			final OfInt second = bitmap.iterator();
			assertEquals(1, first.nextInt());
			assertEquals(2, first.nextInt());

			assertEquals(1, second.nextInt(), "the second iterator must start from the beginning");
			assertEquals(3, first.nextInt());
			assertFalse(first.hasNext());
			assertTrue(second.hasNext());
		}

		@Test
		@DisplayName("Reading past the end throws rather than answering a stale value")
		void shouldThrowWhenExhausted() {
			final OfInt iterator = new SortedArrayBitmap(7).iterator();

			assertEquals(7, iterator.nextInt());
			assertFalse(iterator.hasNext());
			assertThrows(NoSuchElementException.class, iterator::nextInt);
		}

		@Test
		@DisplayName("forEachRemaining visits every record exactly once")
		void shouldVisitEveryRecordOnce() {
			final List<Integer> visited = new ArrayList<>(3);

			new SortedArrayBitmap(1, 2, 3).iterator().forEachRemaining((IntConsumer) visited::add);

			assertEquals(List.of(1, 2, 3), visited);
		}

		@Test
		@DisplayName("The stream carries the wrapped order")
		void shouldStreamInUnsignedOrder() {
			assertArrayEquals(MIXED_SIGN_IDS, new SortedArrayBitmap(MIXED_SIGN_IDS).stream().toArray());
		}

		@Test
		@DisplayName("The stream reports ORDERED, DISTINCT, SORTED and IMMUTABLE characteristics")
		void shouldExposeSortedStreamCharacteristics() {
			final Spliterator.OfInt spliterator = new SortedArrayBitmap(1, 2, 3).stream().spliterator();

			assertTrue(spliterator.hasCharacteristics(Spliterator.ORDERED));
			assertTrue(spliterator.hasCharacteristics(Spliterator.DISTINCT));
			assertTrue(spliterator.hasCharacteristics(Spliterator.SORTED));
			assertTrue(spliterator.hasCharacteristics(Spliterator.IMMUTABLE));
		}
	}

	@Nested
	@DisplayName("Equals and hashCode")
	class EqualsAndHashCodeTest {

		@Test
		@DisplayName("equals and hashCode are content-based and type-sensitive")
		void shouldCompareByContentWithinTheType() {
			final SortedArrayBitmap bitmap = new SortedArrayBitmap(1, 2, 3);

			assertEquals(bitmap, new SortedArrayBitmap(1, 2, 3));
			assertEquals(bitmap.hashCode(), new SortedArrayBitmap(1, 2, 3).hashCode());
			assertNotEquals(bitmap, new SortedArrayBitmap(1, 2, 4));
			assertNotEquals(bitmap, new SortedArrayBitmap(1, 2));
			assertNotEquals(bitmap, new BaseBitmap(1, 2, 3), "content equality across representations is getContentHash's job");
		}

		@Test
		@DisplayName("equals is reflexive and rejects null")
		void shouldBeReflexiveAndRejectNull() {
			final SortedArrayBitmap bitmap = new SortedArrayBitmap(1, 2, 3);

			assertEquals(bitmap, bitmap);
			assertNotEquals(null, bitmap);
		}

		@Test
		@DisplayName("Two views over different arrays of equal content are equal")
		void shouldEqualAcrossDistinctBackingArrays() {
			final int[] left = {1, 2, 3};
			final int[] right = {1, 2, 3};
			final SortedArrayBitmap leftView = new SortedArrayBitmap(left);
			final SortedArrayBitmap rightView = new SortedArrayBitmap(right);

			assertNotSame(left, right);
			assertEquals(leftView, rightView);
			assertEquals(rightView, leftView);
			assertEquals(leftView.hashCode(), rightView.hashCode());
		}
	}

	@Nested
	@DisplayName("Content hash parity")
	class ContentHashParityTest {

		@Test
		@DisplayName("The content hash matches the equivalent BaseBitmap, with and without negative ids")
		void shouldHashLikeAnEquivalentBaseBitmap() {
			// getContentHash is a discriminating part of a formula-cache key, and two representations of the same
			// record set must land on the same cache entry. Parity holds only because getArray performs the signed
			// rotation, so this pins the two halves of that contract together
			final LongHashFunction hashFunction = LongHashFunction.xx3();

			final int[] nonNegative = {0, 5, 9, Integer.MAX_VALUE};
			assertEquals(
				new BaseBitmap(nonNegative).getContentHash(hashFunction),
				new SortedArrayBitmap(nonNegative).getContentHash(hashFunction)
			);
			assertEquals(
				new BaseBitmap(MIXED_SIGN_IDS).getContentHash(hashFunction),
				new SortedArrayBitmap(MIXED_SIGN_IDS).getContentHash(hashFunction)
			);
		}
	}

	@Nested
	@DisplayName("Array ownership")
	class OwnershipTest {

		@Test
		@DisplayName("The wrapped array is borrowed, not copied, so the producer must never write to it")
		void shouldShareTheWrappedArrayWithItsProducer() {
			// the documented contract, and the reason getArray has to copy: a producer that changes a record set
			// builds a different array rather than writing into this one
			final int[] backing = {1, 2, 3};
			final SortedArrayBitmap bitmap = new SortedArrayBitmap(backing);

			backing[2] = 9;

			assertEquals(9, bitmap.getLast());
			assertTrue(bitmap.contains(9));
			assertFalse(bitmap.contains(3));
		}
	}

}
