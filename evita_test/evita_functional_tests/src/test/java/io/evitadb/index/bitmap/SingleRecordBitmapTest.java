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

package io.evitadb.index.bitmap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.PrimitiveIterator.OfInt;
import java.util.Spliterator;

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
 * Verifies the lean, immutable single-record {@link SingleRecordBitmap}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(INDEXING)
@Tag(DATA_TYPE)
@DisplayName("Single-record bitmap")
class SingleRecordBitmapTest {

	@Nested
	@DisplayName("Read accessors")
	class ReadAccessorsTest {

		@Test
		@DisplayName("Reports a single non-empty record and exposes it via all read accessors")
		void shouldExposeTheSingleRecord() {
			final SingleRecordBitmap bitmap = new SingleRecordBitmap(42);

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
		@DisplayName("get throws for any index other than zero")
		void shouldThrowForOutOfBoundsGet() {
			final SingleRecordBitmap bitmap = new SingleRecordBitmap(7);

			assertThrows(IndexOutOfBoundsException.class, () -> bitmap.get(1));
			assertThrows(IndexOutOfBoundsException.class, () -> bitmap.get(-1));
		}

		@Test
		@DisplayName("getArray returns a fresh array on each call (no shared mutable state)")
		void shouldReturnFreshArrayOnEachGetArrayCall() {
			final SingleRecordBitmap bitmap = new SingleRecordBitmap(7);

			final int[] first = bitmap.getArray();
			final int[] second = bitmap.getArray();

			assertNotSame(first, second);
			assertArrayEquals(first, second);
			// mutating the returned array must not leak back into the bitmap
			first[0] = 999;
			assertArrayEquals(new int[]{7}, bitmap.getArray());
			assertEquals(7, bitmap.getFirst());
		}
	}

	@Nested
	@DisplayName("indexOf binary-search contract")
	class IndexOfTest {

		@Test
		@DisplayName("indexOf follows the Arrays.binarySearch contract")
		void shouldReturnBinarySearchStyleIndex() {
			final SingleRecordBitmap bitmap = new SingleRecordBitmap(10);

			assertEquals(0, bitmap.indexOf(10));     // present
			assertEquals(-1, bitmap.indexOf(5));     // insertion point 0 -> -(0)-1
			assertEquals(-2, bitmap.indexOf(15));    // insertion point 1 -> -(1)-1
		}

		@Test
		@DisplayName("indexOf reports insertion point -1 for queries below MIN-valued record")
		void shouldReturnInsertionPointZeroForQueryBelowMinValuedRecord() {
			final SingleRecordBitmap bitmap = new SingleRecordBitmap(Integer.MIN_VALUE + 1);

			// anything smaller than the stored id has insertion point 0 -> -1
			assertEquals(-1, bitmap.indexOf(Integer.MIN_VALUE));
			assertEquals(0, bitmap.indexOf(Integer.MIN_VALUE + 1));
		}

		@Test
		@DisplayName("indexOf reports insertion point -2 for queries above MAX-valued record")
		void shouldReturnInsertionPointOneForQueryAboveMaxValuedRecord() {
			final SingleRecordBitmap bitmap = new SingleRecordBitmap(Integer.MAX_VALUE - 1);

			// anything larger than the stored id has insertion point 1 -> -2
			assertEquals(-2, bitmap.indexOf(Integer.MAX_VALUE));
			assertEquals(0, bitmap.indexOf(Integer.MAX_VALUE - 1));
		}

		@Test
		@DisplayName("indexOf uses signed comparison around zero")
		void shouldUseSignedComparisonAroundZero() {
			final SingleRecordBitmap zero = new SingleRecordBitmap(0);

			// negative query sorts before zero -> insertion point 0 -> -1
			assertEquals(-1, zero.indexOf(-1));
			// positive query sorts after zero -> insertion point 1 -> -2
			assertEquals(-2, zero.indexOf(1));
			assertEquals(0, zero.indexOf(0));
		}
	}

	@Nested
	@DisplayName("getRange bounds")
	class GetRangeTest {

		@Test
		@DisplayName("getRange honours inclusive-start / exclusive-end bounds")
		void shouldReturnRangeSlices() {
			final SingleRecordBitmap bitmap = new SingleRecordBitmap(7);

			assertArrayEquals(new int[]{7}, bitmap.getRange(0, 1));
			assertArrayEquals(new int[0], bitmap.getRange(0, 0));
			assertArrayEquals(new int[0], bitmap.getRange(1, 1));
		}

		@Test
		@DisplayName("getRange throws when bounds are out of range or inverted")
		void shouldThrowForInvalidRangeBounds() {
			final SingleRecordBitmap bitmap = new SingleRecordBitmap(7);

			assertThrows(IndexOutOfBoundsException.class, () -> bitmap.getRange(0, 2));
			assertThrows(IndexOutOfBoundsException.class, () -> bitmap.getRange(-1, 1));
			// start greater than end (inverted range) must be rejected
			assertThrows(IndexOutOfBoundsException.class, () -> bitmap.getRange(1, 0));
			// large end well beyond the single slot
			assertThrows(IndexOutOfBoundsException.class, () -> bitmap.getRange(0, Integer.MAX_VALUE));
		}
	}

	@Nested
	@DisplayName("Iterator and stream")
	class IteratorAndStreamTest {

		@Test
		@DisplayName("Iterator yields the record exactly once")
		void shouldIterateOnce() {
			final OfInt it = new SingleRecordBitmap(99).iterator();

			assertTrue(it.hasNext());
			assertEquals(99, it.nextInt());
			assertFalse(it.hasNext());
			assertThrows(NoSuchElementException.class, it::nextInt);
		}

		@Test
		@DisplayName("Two iterators from the same bitmap are independent")
		void shouldProvideIndependentIterators() {
			final SingleRecordBitmap bitmap = new SingleRecordBitmap(99);

			final OfInt first = bitmap.iterator();
			final OfInt second = bitmap.iterator();

			assertNotSame(first, second);
			// exhausting the first must leave the second untouched
			assertEquals(99, first.nextInt());
			assertFalse(first.hasNext());
			assertTrue(second.hasNext());
			assertEquals(99, second.nextInt());
			assertFalse(second.hasNext());
		}

		@Test
		@DisplayName("forEachRemaining visits the single record exactly once")
		void shouldVisitSingleRecordViaForEachRemaining() {
			final OfInt it = new SingleRecordBitmap(99).iterator();
			final List<Integer> collected = new ArrayList<>(1);

			it.forEachRemaining((int value) -> collected.add(value));

			assertEquals(List.of(99), collected);
			assertFalse(it.hasNext());
		}

		@Test
		@DisplayName("Stream produces the single record")
		void shouldStreamTheSingleRecord() {
			assertArrayEquals(new int[]{3}, new SingleRecordBitmap(3).stream().toArray());
		}

		@Test
		@DisplayName("Stream reports ORDERED, DISTINCT, SORTED and IMMUTABLE characteristics")
		void shouldExposeSortedStreamCharacteristics() {
			final Spliterator.OfInt spliterator = new SingleRecordBitmap(3).stream().spliterator();

			assertTrue(spliterator.hasCharacteristics(Spliterator.ORDERED));
			assertTrue(spliterator.hasCharacteristics(Spliterator.DISTINCT));
			assertTrue(spliterator.hasCharacteristics(Spliterator.SORTED));
			assertTrue(spliterator.hasCharacteristics(Spliterator.IMMUTABLE));
		}
	}

	@Nested
	@DisplayName("Immutability")
	class ImmutabilityTest {

		@Test
		@DisplayName("All mutation methods are rejected (immutable)")
		void shouldRejectMutation() {
			final SingleRecordBitmap bitmap = new SingleRecordBitmap(1);

			assertThrows(UnsupportedOperationException.class, () -> bitmap.add(2));
			assertThrows(UnsupportedOperationException.class, () -> bitmap.addAll(2, 3));
			assertThrows(UnsupportedOperationException.class, () -> bitmap.addAll(new BaseBitmap(2)));
			assertThrows(UnsupportedOperationException.class, () -> bitmap.remove(1));
			assertThrows(UnsupportedOperationException.class, () -> bitmap.removeAll(1));
			assertThrows(UnsupportedOperationException.class, () -> bitmap.removeAll(new BaseBitmap(1)));
		}
	}

	@Nested
	@DisplayName("Equals and hashCode")
	class EqualsAndHashCodeTest {

		@Test
		@DisplayName("equals / hashCode are type-sensitive and content-based")
		void shouldImplementEqualsAndHashCode() {
			final SingleRecordBitmap five = new SingleRecordBitmap(5);

			assertEquals(new SingleRecordBitmap(5), five);
			assertEquals(five.hashCode(), new SingleRecordBitmap(5).hashCode());
			assertNotEquals(new SingleRecordBitmap(6), five);
			// type-sensitive, consistent with BaseBitmap/ArrayBitmap: a different impl with the
			// same content is not equal
			assertNotEquals(new BaseBitmap(5), five);
		}

		@Test
		@DisplayName("equals is reflexive and rejects null")
		void shouldBeReflexiveAndRejectNull() {
			final SingleRecordBitmap five = new SingleRecordBitmap(5);

			assertEquals(five, five);
			assertNotEquals(null, five);
		}

		@Test
		@DisplayName("equals is symmetric for equal content")
		void shouldBeSymmetricForEqualContent() {
			final SingleRecordBitmap one = new SingleRecordBitmap(5);
			final SingleRecordBitmap other = new SingleRecordBitmap(5);

			assertEquals(one, other);
			assertEquals(other, one);
		}
	}

	@Nested
	@DisplayName("Boundary record ids")
	class BoundaryRecordIdsTest {

		@ParameterizedTest(name = "record id = {0}")
		@ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE})
		@DisplayName("All accessors agree on the stored record id at signed boundaries")
		void shouldExposeBoundaryRecordIdConsistently(int recordId) {
			final SingleRecordBitmap bitmap = new SingleRecordBitmap(recordId);

			assertFalse(bitmap.isEmpty());
			assertEquals(1, bitmap.size());
			assertTrue(bitmap.contains(recordId));
			assertEquals(0, bitmap.indexOf(recordId));
			assertEquals(recordId, bitmap.get(0));
			assertEquals(recordId, bitmap.getFirst());
			assertEquals(recordId, bitmap.getLast());
			assertArrayEquals(new int[]{recordId}, bitmap.getArray());
			assertArrayEquals(new int[]{recordId}, bitmap.getRange(0, 1));
			assertEquals("[" + recordId + "]", bitmap.toString());
		}

		@Test
		@DisplayName("contains rejects neighbouring ids at the signed extremes")
		void shouldRejectNeighbouringIdsAtExtremes() {
			final SingleRecordBitmap min = new SingleRecordBitmap(Integer.MIN_VALUE);
			final SingleRecordBitmap max = new SingleRecordBitmap(Integer.MAX_VALUE);

			assertFalse(min.contains(Integer.MIN_VALUE + 1));
			assertFalse(min.contains(0));
			assertFalse(max.contains(Integer.MAX_VALUE - 1));
			assertFalse(max.contains(0));
		}
	}

	@Nested
	@DisplayName("Parity with BaseBitmap")
	class BaseBitmapParityTest {

		@Test
		@DisplayName("Produces the same record set as the equivalent BaseBitmap")
		void shouldMatchBaseBitmapContent() {
			assertArrayEquals(new BaseBitmap(123).getArray(), new SingleRecordBitmap(123).getArray());
		}

		@ParameterizedTest(name = "record id = {0}")
		@ValueSource(ints = {Integer.MIN_VALUE, -7, 0, 7, Integer.MAX_VALUE})
		@DisplayName("Matches the equivalent BaseBitmap across accessors and boundary ids")
		void shouldMatchBaseBitmapAcrossAccessors(int recordId) {
			final SingleRecordBitmap single = new SingleRecordBitmap(recordId);
			final BaseBitmap base = new BaseBitmap(recordId);

			assertEquals(base.size(), single.size());
			assertEquals(base.isEmpty(), single.isEmpty());
			assertEquals(base.getFirst(), single.getFirst());
			assertEquals(base.getLast(), single.getLast());
			assertEquals(base.indexOf(recordId), single.indexOf(recordId));
			assertEquals(base.contains(recordId), single.contains(recordId));
			assertArrayEquals(base.getArray(), single.getArray());
			assertArrayEquals(base.stream().toArray(), single.stream().toArray());
		}
	}
}
