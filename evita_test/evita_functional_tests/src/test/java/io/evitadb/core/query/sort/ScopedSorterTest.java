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

package io.evitadb.core.query.sort;

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.sort.Sorter.SortingContext;
import io.evitadb.core.query.sort.primaryKey.sorter.ExactSorter;
import io.evitadb.core.query.sort.primaryKey.sorter.ReversedPrimaryKeySorter;
import io.evitadb.index.bitmap.BaseBitmap;
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
import static org.mockito.Mockito.mock;

/**
 * Drives {@link ScopedSorter#sortAndSlice} directly: the records of the scope are ordered by the delegate - page by
 * page, reporting the records the page skips - and the records of the other scopes are passed on to the next sorter
 * untouched, together with the records of the scope the delegate passes on itself.
 *
 * Neither the sorter nor its delegates touch the query execution context, so a bare mock stands in for it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(ENGINE)
@Tag(ORDER)
@DisplayName("ScopedSorter ordering of the records of one scope")
class ScopedSorterTest {
	/**
	 * All records to sort, in ascending order.
	 */
	private static final int[] RECORDS = {1, 2, 3, 4, 5, 6};
	/**
	 * The records of the ordered scope.
	 */
	private static final int[] SCOPE_RECORDS = {2, 3, 4, 6};

	/**
	 * Runs the scoped primary key order for the passed slice with nothing written or skipped before.
	 *
	 * @param direction      the direction of the primary key order
	 * @param startIndex     the slice start index (inclusive)
	 * @param endIndex       the slice end index (exclusive)
	 * @param skippedRecords receives the records the sorter reports as skipped
	 * @param passedOn       receives the records left for the next sorter
	 * @return the written slice
	 */
	@Nonnull
	private static int[] sortAndSlice(
		@Nonnull OrderDirection direction,
		int startIndex,
		int endIndex,
		@Nonnull List<Integer> skippedRecords,
		@Nonnull List<Integer> passedOn
	) {
		return sortAndSlice(
			direction == OrderDirection.DESC ? ReversedPrimaryKeySorter.INSTANCE : NoSorter.INSTANCE,
			startIndex, endIndex, skippedRecords, passedOn
		);
	}

	/**
	 * Runs the passed delegate restricted to the scope for the passed slice with nothing written or skipped before.
	 *
	 * @param delegate       the sorter ordering the records of the scope
	 * @param startIndex     the slice start index (inclusive)
	 * @param endIndex       the slice end index (exclusive)
	 * @param skippedRecords receives the records the sorter reports as skipped
	 * @param passedOn       receives the records left for the next sorter
	 * @return the written slice
	 */
	@Nonnull
	private static int[] sortAndSlice(
		@Nonnull Sorter delegate,
		int startIndex,
		int endIndex,
		@Nonnull List<Integer> skippedRecords,
		@Nonnull List<Integer> passedOn
	) {
		final int[] result = new int[endIndex - startIndex];
		final SortingContext resultContext = new ScopedSorter(new BaseBitmap(SCOPE_RECORDS), delegate)
			.sortAndSlice(
				new SortingContext(
					mock(QueryExecutionContext.class), new BaseBitmap(RECORDS), startIndex, endIndex, 0, 0
				),
				result,
				skippedRecords::add
			);
		for (int record : resultContext.nonSortedKeys().getArray()) {
			passedOn.add(record);
		}
		return SortUtils.asResult(result, resultContext.peak());
	}

	@Test
	@DisplayName("The records of the scope are ordered and the other ones are passed on")
	void shouldOrderRecordsOfScopeAndPassOnTheOthers() {
		final List<Integer> passedOn = new ArrayList<>(2);
		assertArrayEquals(new int[]{2, 3, 4, 6}, sortAndSlice(OrderDirection.ASC, 0, 6, new ArrayList<>(0), passedOn));
		assertEquals(List.of(1, 5), passedOn);
		passedOn.clear();
		assertArrayEquals(new int[]{6, 4, 3, 2}, sortAndSlice(OrderDirection.DESC, 0, 6, new ArrayList<>(0), passedOn));
		assertEquals(List.of(1, 5), passedOn);
	}

	@ParameterizedTest(name = "pages of {0}")
	@ValueSource(ints = {1, 2, 3})
	@DisplayName("Consecutive pages concatenate to the order of the scope and report the skipped records")
	void shouldConcatenatePagesAndReportSkippedRecords(int pageSize) {
		for (OrderDirection direction : OrderDirection.values()) {
			final List<Integer> concatenated = new ArrayList<>(SCOPE_RECORDS.length);
			for (int startIndex = 0; startIndex < SCOPE_RECORDS.length; startIndex += pageSize) {
				final List<Integer> skipped = new ArrayList<>(startIndex);
				for (int pk : sortAndSlice(direction, startIndex, startIndex + pageSize, skipped, new ArrayList<>(2))) {
					concatenated.add(pk);
				}
				assertEquals(startIndex, skipped.size(), direction + ", page starting at " + startIndex);
			}
			assertEquals(
				direction == OrderDirection.ASC ? List.of(2, 3, 4, 6) : List.of(6, 4, 3, 2), concatenated,
				direction + " by pages of " + pageSize
			);
		}
	}

	@Test
	@DisplayName("The records of the scope the delegate leaves unsorted are passed on with the other scopes")
	void shouldPassOnRecordsOfScopeTheDelegateLeavesUnsorted() {
		final List<Integer> passedOn = new ArrayList<>(4);
		assertArrayEquals(
			new int[]{6, 2},
			sortAndSlice(new ExactSorter(new int[]{6, 5, 2}), 0, 6, new ArrayList<>(0), passedOn)
		);
		assertEquals(List.of(1, 3, 4, 5), passedOn);
	}

}
