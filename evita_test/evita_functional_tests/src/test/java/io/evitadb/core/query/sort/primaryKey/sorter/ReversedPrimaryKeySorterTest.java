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

package io.evitadb.core.query.sort.primaryKey.sorter;

import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.sort.Sorter.SortingContext;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.SingleRecordBitmap;
import io.evitadb.index.bitmap.SortedArrayBitmap;
import io.evitadb.test.utils.SortUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.ORDER;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Drives {@link ReversedPrimaryKeySorter#sortAndSlice} directly against hand-built {@link SortingContext} instances:
 * every slice of the descending primary key order - not only the first page - must be the matching part of that
 * order, and the records preceding the slice must be reported as skipped, because a segment excludes exactly those
 * records from the following segments.
 *
 * The sorter never touches the query execution context, so a bare mock stands in for it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(ENGINE)
@Tag(ORDER)
@DisplayName("ReversedPrimaryKeySorter slicing of the descending primary key order")
class ReversedPrimaryKeySorterTest {
	/**
	 * The records sorted by every test, in ascending order.
	 */
	private static final int[] RECORDS = {3, 8, 11, 20, 27};

	/**
	 * Runs the sorter on {@link #RECORDS} for the passed slice with nothing written or skipped before.
	 *
	 * @param startIndex     the slice start index (inclusive)
	 * @param endIndex       the slice end index (exclusive)
	 * @param skippedRecords receives the records the sorter reports as skipped
	 * @return the written slice
	 */
	@Nonnull
	private static int[] sortAndSlice(int startIndex, int endIndex, @Nonnull List<Integer> skippedRecords) {
		final int[] result = new int[endIndex - startIndex];
		final SortingContext resultContext = ReversedPrimaryKeySorter.INSTANCE.sortAndSlice(
			new SortingContext(mock(QueryExecutionContext.class), new BaseBitmap(RECORDS), startIndex, endIndex, 0, 0),
			result,
			skippedRecords::add
		);
		assertTrue(resultContext.nonSortedKeys().isEmpty(), "The sorter orders every record.");
		return SortUtils.asResult(result, resultContext.peak());
	}

	/**
	 * Returns the reported skipped records in ascending order - the sorter may report them in any order, because the
	 * segment that consumes them collects them into a bitmap.
	 *
	 * @param skippedRecords the records reported as skipped
	 * @return the same records in ascending order
	 */
	@Nonnull
	private static List<Integer> ascending(@Nonnull List<Integer> skippedRecords) {
		final List<Integer> sorted = new ArrayList<>(skippedRecords);
		sorted.sort(null);
		return sorted;
	}

	@Test
	@DisplayName("A page beyond the last record of a strict bitmap is empty and does not read past its end")
	void shouldReturnEmptyPageBeyondLastRecordOfStrictBitmap() {
		// unlike the roaring-backed bitmap, these implementations refuse a range reaching past their last record
		for (Bitmap bitmap : new Bitmap[]{new SingleRecordBitmap(8), new SortedArrayBitmap(3, 8)}) {
			final List<Integer> skipped = new ArrayList<>(2);
			final SortingContext resultContext = ReversedPrimaryKeySorter.INSTANCE.sortAndSlice(
				new SortingContext(mock(QueryExecutionContext.class), bitmap, 2, 3, 0, 0),
				new int[1],
				skipped::add
			);
			assertEquals(0, resultContext.peak(), bitmap.getClass().getSimpleName());
			assertEquals(bitmap.size(), resultContext.skipped(), bitmap.getClass().getSimpleName());
			assertEquals(bitmap.size(), skipped.size(), bitmap.getClass().getSimpleName());
		}
	}

	@Test
	@DisplayName("The first page holds the greatest primary keys in descending order")
	void shouldReturnGreatestPrimaryKeysOnFirstPage() {
		final List<Integer> skipped = new ArrayList<>(0);
		assertArrayEquals(new int[]{27, 20}, sortAndSlice(0, 2, skipped));
		assertEquals(List.of(), skipped);
	}

	@Test
	@DisplayName("A later page holds the next primary keys and reports the greater ones as skipped")
	void shouldReturnFollowingPrimaryKeysOnLaterPage() {
		final List<Integer> skipped = new ArrayList<>(2);
		assertArrayEquals(new int[]{11, 8}, sortAndSlice(2, 4, skipped));
		assertEquals(List.of(20, 27), ascending(skipped));
	}

	@Test
	@DisplayName("The last page may be shorter than the page size")
	void shouldReturnRemainderOnLastPage() {
		final List<Integer> skipped = new ArrayList<>(4);
		assertArrayEquals(new int[]{3}, sortAndSlice(4, 6, skipped));
		assertEquals(List.of(8, 11, 20, 27), ascending(skipped));
	}

	@Test
	@DisplayName("A page beyond the last record is empty and every record is skipped")
	void shouldSkipEveryRecordWhenPageIsBeyondLastRecord() {
		final List<Integer> skipped = new ArrayList<>(5);
		assertArrayEquals(new int[0], sortAndSlice(7, 9, skipped));
		assertEquals(List.of(3, 8, 11, 20, 27), ascending(skipped));
	}

	@ParameterizedTest(name = "pages of {0}")
	@ValueSource(ints = {1, 2, 3, 4, 5})
	@DisplayName("Consecutive pages concatenate to the whole descending order")
	void shouldConcatenatePagesToWholeDescendingOrder(int pageSize) {
		final List<Integer> concatenated = new ArrayList<>(RECORDS.length);
		for (int startIndex = 0; startIndex < RECORDS.length; startIndex += pageSize) {
			for (int pk : sortAndSlice(startIndex, startIndex + pageSize, new ArrayList<>(RECORDS.length))) {
				concatenated.add(pk);
			}
		}
		assertEquals(List.of(27, 20, 11, 8, 3), concatenated);
	}

	@Test
	@DisplayName("A slice after records a preceding sorter skipped starts at the right position")
	void shouldHonourRecordsSkippedByPrecedingSorter() {
		// a preceding sorter skipped one record of the requested positions [3, 5), so this sorter's descending
		// order contributes its positions 2 and 3 and skips its first two
		final int[] result = new int[2];
		final SortingContext resultContext = ReversedPrimaryKeySorter.INSTANCE.sortAndSlice(
			new SortingContext(mock(QueryExecutionContext.class), new BaseBitmap(RECORDS), 3, 5, 0, 1),
			result,
			null
		);
		assertArrayEquals(new int[]{11, 8}, SortUtils.asResult(result, resultContext.peak()));
		assertEquals(3, resultContext.skipped());
	}

}
