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

package io.evitadb.test.utils;

import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.ORDER;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the record-copying and skipped-record reporting contract of {@link SortUtils}, with
 * emphasis on the boundary behaviour of `appendNotFoundResult`: the skipped-record consumer must
 * receive every record positioned before the requested window exactly once — including the last one
 * when `startIndex` equals the source length, and without double-counting across bitmap batches.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(ENGINE)
@Tag(ORDER)
@DisplayName("SortUtils record slicing and skipped-record reporting")
class SortUtilsTest {

	/**
	 * Builds an ascending array holding the values `1..count` (value `i + 1` at index `i`).
	 *
	 * @param count number of records to generate
	 * @return ascending int array of the requested length
	 */
	@Nonnull
	private static int[] ascendingArray(int count) {
		final int[] array = new int[count];
		for (int i = 0; i < count; i++) {
			array[i] = i + 1;
		}
		return array;
	}

	/**
	 * Builds an ascending {@link PersistentRoaringBitmap} holding the values `1..count`.
	 *
	 * @param count number of records to add
	 * @return bitmap containing the values `1..count` in ascending order
	 */
	@Nonnull
	private static PersistentRoaringBitmap ascendingBitmap(int count) {
		final PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
		for (int i = 1; i <= count; i++) {
			bitmap.add(i);
		}
		return bitmap;
	}

	/**
	 * Collects the skipped records reported by the int-array overload of
	 * {@link SortUtils#appendNotFoundResult}. Because the skip set depends only on `startIndex` and
	 * the source array, this doubles as the oracle for the bitmap overload's skip reporting.
	 *
	 * @param rest       source records
	 * @param startIndex first requested (0-based) position; records before it are skipped
	 * @param endIndex   exclusive upper bound of the requested window
	 * @param resultSize capacity of the throw-away target buffer
	 * @return the skipped records in the order they were reported
	 */
	@Nonnull
	private static List<Integer> collectSkippedFromArray(
		@Nonnull int[] rest, int startIndex, int endIndex, int resultSize
	) {
		final List<Integer> skipped = new ArrayList<>();
		SortUtils.appendNotFoundResult(
			new int[resultSize], 0, startIndex, endIndex, rest, value -> skipped.add(value)
		);
		return skipped;
	}

	@Nested
	@Tag(ENGINE)
	@Tag(ORDER)
	@DisplayName("asResult")
	class AsResult {

		@Test
		@DisplayName("returns the shared empty array instance when the peak is zero")
		void shouldReturnEmptyArrayWhenPeakIsZero() {
			final int[] sortedEntities = {1, 2, 3, 4, 5};

			final int[] result = SortUtils.asResult(sortedEntities, 0);

			assertSame(ArrayUtils.EMPTY_INT_ARRAY, result);
			assertEquals(0, result.length);
		}

		@Test
		@DisplayName("returns the very same array instance when the peak equals the length")
		void shouldReturnSameArrayWhenPeakEqualsLength() {
			final int[] sortedEntities = {1, 2, 3, 4, 5};

			final int[] result = SortUtils.asResult(sortedEntities, sortedEntities.length);

			assertSame(sortedEntities, result);
		}

		@Test
		@DisplayName("returns a truncated copy when the peak is smaller than the length")
		void shouldReturnTruncatedCopyWhenPeakSmallerThanLength() {
			final int[] sortedEntities = {1, 2, 3, 4, 5};

			final int[] result = SortUtils.asResult(sortedEntities, 3);

			assertArrayEquals(new int[]{1, 2, 3}, result);
			assertNotSame(sortedEntities, result);
		}
	}

	@Nested
	@Tag(ENGINE)
	@Tag(ORDER)
	@DisplayName("appendNotFoundResult (int array source)")
	class AppendFromArray {

		@Test
		@DisplayName("copies the requested window starting at the result peak")
		void shouldCopyRequestedWindowAtResultPeak() {
			final int[] rest = {10, 20, 30, 40, 50};
			final int[] result = new int[10];

			final int written = SortUtils.appendNotFoundResult(result, 3, 1, 4, rest, null);

			assertEquals(6, written);
			assertArrayEquals(new int[]{0, 0, 0, 20, 30, 40, 0, 0, 0, 0}, result);
		}

		@Test
		@DisplayName("caps the copied length to the remaining result capacity")
		void shouldCapLengthToRemainingResultCapacity() {
			final int[] rest = ascendingArray(10);
			final int[] result = new int[5];

			final int written = SortUtils.appendNotFoundResult(result, 2, 0, 10, rest, null);

			assertEquals(result.length, written);
			assertArrayEquals(new int[]{0, 0, 1, 2, 3}, result);
		}

		@Test
		@DisplayName("feeds every record before startIndex to the consumer exactly once")
		void shouldReportRecordsBeforeStartIndexExactlyOnce() {
			final int[] rest = {5, 6, 7, 8, 9};
			final int[] result = new int[10];
			final List<Integer> skipped = new ArrayList<>();
			final IntConsumer consumer = value -> skipped.add(value);

			final int written = SortUtils.appendNotFoundResult(result, 0, 3, 5, rest, consumer);

			assertEquals(2, written);
			assertArrayEquals(new int[]{8, 9, 0, 0, 0, 0, 0, 0, 0, 0}, result);
			assertEquals(List.of(5, 6, 7), skipped);
		}

		@Test
		@DisplayName("reports the last record as skipped when startIndex equals the source length")
		void shouldReportLastRecordSkippedWhenStartIndexEqualsSourceLength() {
			final int[] rest = {1, 2, 3, 4};
			final int[] result = new int[10];
			final List<Integer> skipped = new ArrayList<>();
			final IntConsumer consumer = value -> skipped.add(value);

			final int written = SortUtils.appendNotFoundResult(result, 0, 4, 4, rest, consumer);

			assertEquals(0, written);
			// the just-fixed edge case: all four records — including the last one — are skipped
			assertEquals(List.of(1, 2, 3, 4), skipped);
			assertArrayEquals(new int[10], result);
		}

		@Test
		@DisplayName("tolerates a null consumer while still copying the window")
		void shouldTolerateNullConsumer() {
			final int[] rest = ascendingArray(5);
			final int[] result = new int[10];

			final int written = SortUtils.appendNotFoundResult(result, 0, 2, 5, rest, null);

			assertEquals(3, written);
			assertArrayEquals(new int[]{3, 4, 5, 0, 0, 0, 0, 0, 0, 0}, result);
		}
	}

	@Nested
	@Tag(ENGINE)
	@Tag(ORDER)
	@DisplayName("appendNotFoundResult (roaring bitmap source)")
	class AppendFromBitmap {

		@Test
		@DisplayName("copies the requested window respecting the start and end index")
		void shouldCopyWindowRespectingStartAndEndIndex() {
			final PersistentRoaringBitmap bitmap = ascendingBitmap(1000);
			final int[] result = new int[10];
			final int[] buffer = new int[16];

			final int written = SortUtils.appendNotFoundResult(
				result, 0, 20, 30, bitmap, buffer, null
			);

			assertEquals(10, written);
			assertArrayEquals(
				new int[]{21, 22, 23, 24, 25, 26, 27, 28, 29, 30}, result
			);
		}

		@Test
		@DisplayName("stops copying once the result buffer is full")
		void shouldStopCopyingWhenResultBufferIsFull() {
			final PersistentRoaringBitmap bitmap = ascendingBitmap(1000);
			final int[] result = new int[10];
			final int[] buffer = new int[16];

			final int written = SortUtils.appendNotFoundResult(
				result, 0, 0, 1000, bitmap, buffer, null
			);

			assertEquals(result.length, written);
			assertArrayEquals(new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, result);
		}

		@Test
		@DisplayName("reports skipped records exactly once across multiple batches")
		void shouldReportSkippedRecordsExactlyOnceAcrossBatches() {
			final PersistentRoaringBitmap bitmap = ascendingBitmap(1000);
			final int[] result = new int[1000];
			// buffer smaller than the number of skipped records forces the skip window to span
			// several batches; the fixed code must not re-report records from earlier batches
			final int[] buffer = new int[16];
			final List<Integer> skipped = new ArrayList<>();
			final IntConsumer consumer = value -> skipped.add(value);

			final int written = SortUtils.appendNotFoundResult(
				result, 0, 50, 1000, bitmap, buffer, consumer
			);

			// oracle: the int-array overload reports exactly the records before startIndex
			final List<Integer> expectedSkipped = collectSkippedFromArray(
				ascendingArray(1000), 50, 1000, result.length
			);
			assertEquals(expectedSkipped, skipped);
			assertEquals(50, skipped.size());

			assertEquals(950, written);
			final int[] expected = new int[1000];
			for (int i = 0; i < 950; i++) {
				expected[i] = 51 + i;
			}
			assertArrayEquals(expected, result);
		}

		@Test
		@DisplayName("skips the leading window exactly once even with a tiny buffer")
		void shouldSkipLeadingWindowExactlyOnceWithTinyBuffer() {
			final PersistentRoaringBitmap bitmap = ascendingBitmap(1000);
			final int[] result = new int[10];
			final int[] buffer = new int[16];
			final List<Integer> skipped = new ArrayList<>();
			final IntConsumer consumer = value -> skipped.add(value);

			SortUtils.appendNotFoundResult(result, 0, 20, 30, bitmap, buffer, consumer);

			final List<Integer> expectedSkipped = collectSkippedFromArray(
				ascendingArray(1000), 20, 30, result.length
			);
			assertEquals(expectedSkipped, skipped);
			assertEquals(20, skipped.size());
			// every skipped value must be unique (no double-counting across batches)
			assertEquals(skipped.size(), skipped.stream().distinct().count());
			assertTrue(skipped.stream().allMatch(value -> value <= 20));
		}
	}
}
