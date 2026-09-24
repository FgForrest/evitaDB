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

package io.evitadb.core.query.sort.attribute.sorter;

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.SharedBufferPool;
import io.evitadb.core.query.sort.NestedContextSorter;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.query.sort.Sorter.SortingContext;
import io.evitadb.core.query.sort.attribute.sorter.PreSortedRecordsSorter.MergeMode;
import io.evitadb.core.query.sort.utils.MockSortedRecordsSupplier;
import io.evitadb.core.query.sort.utils.SortUtilsTest;
import io.evitadb.index.attribute.SortedRecordsSupplier;
import io.evitadb.index.bitmap.BaseBitmap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.ORDER;

/**
 * This test verifies {@link PreSortedRecordsSorter} behaviour.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Tag(ENGINE)
@Tag(ORDER)
class PreSortedRecordsSorterTest {
	private PreSortedRecordsSorterWithContext bitmapSorter;

	private static int findPosition(int[] sortedRecordIds, int recId) {
		for (int i = 0; i < sortedRecordIds.length; i++) {
			int sortedRecordId = sortedRecordIds[i];
			if (sortedRecordId == recId) {
				return i;
			}
		}
		return -1;
	}

	private static int[] pickRandomResults(int[] sortedRecordIds, int count) {
		final Random random = new Random();
		final int[] recs = new int[count];
		final Set<Integer> picked = new HashSet<>(count);
		int peak = 0;
		do {
			final int randomRecordId = sortedRecordIds[random.nextInt(sortedRecordIds.length)];
			if (picked.add(randomRecordId)) {
				recs[peak++] = randomRecordId;
			}
		} while (peak < count);
		Arrays.sort(recs);
		return recs;
	}

	private static int[] generateRandomSortedRecords(int recCount) {
		final Random random = new Random();
		final Set<Integer> randomRecordIds = new TreeSet<>();
		final int[] sortedRecordIds = new int[recCount];
		int peak = 0;
		do {
			final int rndRecId = random.nextInt(recCount * 2);
			if (randomRecordIds.add(rndRecId)) {
				sortedRecordIds[peak++] = rndRecId;
			}
		} while (peak < recCount);
		return sortedRecordIds;
	}

	@Nonnull
	private static BaseBitmap makeBitmap(int... recordIds) {
		return new BaseBitmap(recordIds);
	}

	/**
	 * Creates a provider holding records with explicit values, laid out as a real sort index lays them out: in the
	 * order of the values in the ordering direction, and records with equal values by their primary key in the same
	 * direction. Unlike {@link MockSortedRecordsSupplier}, which reports each record's position as its value, it can
	 * express equal values.
	 *
	 * @param id             identity of the provider
	 * @param direction      the ordering direction
	 * @param recordValues   pairs of record primary key and value
	 * @return the provider
	 */
	@Nonnull
	private static SortedRecordsSupplier createValueProvider(
		long id,
		@Nonnull OrderDirection direction,
		@Nonnull int... recordValues
	) {
		final List<int[]> pairs = new ArrayList<>(recordValues.length / 2);
		for (int i = 0; i < recordValues.length; i += 2) {
			pairs.add(new int[]{recordValues[i], recordValues[i + 1]});
		}
		final Comparator<int[]> ascending = Comparator.<int[]>comparingInt(it -> it[1]).thenComparingInt(it -> it[0]);
		pairs.sort(direction == OrderDirection.ASC ? ascending : ascending.reversed());
		final int[] sortedRecords = pairs.stream().mapToInt(it -> it[0]).toArray();
		final Integer[] values = pairs.stream().map(it -> it[1]).toArray(Integer[]::new);
		final int[] recordsAscending = Arrays.stream(sortedRecords).sorted().toArray();
		final int[] positions = new int[recordsAscending.length];
		for (int i = 0; i < recordsAscending.length; i++) {
			for (int j = 0; j < sortedRecords.length; j++) {
				if (sortedRecords[j] == recordsAscending[i]) {
					positions[i] = j;
				}
			}
		}
		return new SortedRecordsSupplier(
			id, sortedRecords, positions, makeBitmap(recordsAscending), position -> values[position]
		);
	}

	/**
	 * Sorts all passed records by merging the providers with {@link MergeMode#APPEND_FIRST} in the direction.
	 */
	@Nonnull
	private int[] mergeProviders(
		@Nonnull OrderDirection direction,
		@Nonnull int[] recordIds,
		@Nonnull SortedRecordsProvider... providers
	) {
		final PreSortedRecordsSorter sorter = new PreSortedRecordsSorter(
			MergeMode.APPEND_FIRST,
			direction == OrderDirection.ASC ? Comparator.<Integer>naturalOrder() : Comparator.<Integer>reverseOrder(),
			direction,
			() -> providers
		);
		final QueryExecutionContext executionContext = this.bitmapSorter.context().createExecutionContext();
		return SortUtilsTest.asResult(
			theArray -> sorter.sortAndSlice(
				new SortingContext(executionContext, makeBitmap(recordIds), 0, recordIds.length, 0, 0),
				theArray,
				null
			)
		);
	}

	private static void assertPageIsConsistent(int[] sortedRecordIds, PreSortedRecordsSorter sorter, QueryExecutionContext queryContext, int[] recIds, int startIndex, int endIndex) {
		final int[] sortedSlice = SortUtilsTest.asResult(
			theArray -> sorter.sortAndSlice(
				new SortingContext(
					queryContext, makeBitmap(recIds), startIndex, endIndex, 0, 0
				),
				theArray,
				null
			)
		);
		assertEquals(endIndex - startIndex, sortedSlice.length);
		int lastPosition = -1;
		for (int recId : sortedSlice) {
			assertTrue(Arrays.binarySearch(recIds, recId) >= 0, "Record must be part of filter result!");
			int positionInSortedSet = findPosition(sortedRecordIds, recId);
			assertTrue(positionInSortedSet >= lastPosition, "Order must be monotonic!");
		}
	}

	@BeforeEach
	void setUp() {
		final QueryPlanningContext planningContext = Mockito.mock(QueryPlanningContext.class);
		final QueryExecutionContext executionContext = Mockito.mock(QueryExecutionContext.class);
		when(planningContext.createExecutionContext()).thenReturn(executionContext);
		// the sorter reads the planning context for the debug sort override / telemetry; the unstubbed mock returns
		// false for isDebugModeEnabled and null for getCurrentStep -> cost-based selection with telemetry off
		when(executionContext.getQueryContext()).thenReturn(planningContext);
		when(executionContext.getPrefetchedEntities()).thenReturn(null);
		Mockito.doAnswer(invocation -> SharedBufferPool.INSTANCE.obtain()).when(executionContext).borrowBuffer();
		Mockito.doNothing().when(executionContext).returnBuffer(any());
		this.bitmapSorter = new PreSortedRecordsSorterWithContext(
			new PreSortedRecordsSorter(
				MergeMode.APPEND_FIRST,
				Comparator.naturalOrder(),
				OrderDirection.ASC,
				() -> new SortedRecordsProvider[]{new MockSortedRecordsSupplier(7, 2, 4, 1, 3, 8, 5, 9, 6)}
			),
			planningContext
		);
	}

	@Test
	void shouldReturnFullResultInExpectedOrderOnSmallData() {
		final QueryExecutionContext executionContext = this.bitmapSorter.context().createExecutionContext();
		assertArrayEquals(
			new int[]{2, 4, 1, 3},
			SortUtilsTest.asResult(
				theArray -> this.bitmapSorter.sorter().sortAndSlice(
					new SortingContext(
						executionContext,
						makeBitmap(1, 2, 3, 4),
						0, 100,
						0, 0
					),
					theArray,
					null
				)
			)
		);
		assertArrayEquals(
			new int[]{1, 3},
			SortUtilsTest.asResult(
				theArray -> this.bitmapSorter.sorter().sortAndSlice(
					new SortingContext(
						executionContext,
						makeBitmap(1, 2, 3, 4, 5, 6, 7, 8, 9), 3, 5,
						0, 0
					),
					theArray,
					null
				)
			)
		);
		assertArrayEquals(
			new int[]{7, 8, 9},
			SortUtilsTest.asResult(
				theArray -> this.bitmapSorter.sorter().sortAndSlice(
					new SortingContext(
						executionContext,
						makeBitmap(7, 8, 9),
						0, 3, 0, 0
					),
					theArray,
					null
				)
			)
		);
	}

	@Test
	void shouldReturnSortedResultEvenForMissingData() {
		final int[] actual = new NestedContextSorter(
			this.bitmapSorter.context().createExecutionContext(),
			() -> "whatever",
			List.of(this.bitmapSorter.sorter())
		).sortAndSlice(makeBitmap(0, 1, 2, 3, 4, 12, 13));
		assertArrayEquals(
			new int[]{2, 4, 1, 3, 0, 12, 13},
			actual
		);
	}

	@Test
	void shouldReturnSortedResultEvenForMissingDataWithAdditionalSorter() {
		final int[] actual = new NestedContextSorter(
			this.bitmapSorter.context().createExecutionContext(),
			() -> "whatever",
			List.of(
				this.bitmapSorter.sorter(),
				new PreSortedRecordsSorter(
					MergeMode.APPEND_FIRST,
					Comparator.naturalOrder(),
					OrderDirection.ASC,
					() -> new SortedRecordsProvider[]{new MockSortedRecordsSupplier(13, 0, 12)}
				)
			)
		).sortAndSlice(makeBitmap(0, 1, 2, 3, 4, 12, 13));

		assertArrayEquals(
			new int[]{2, 4, 1, 3, 13, 0, 12},
			actual
		);
	}

	@Test
	void shouldReturnFullResultInExpectedOrderOnLargeData() {
		final int[] sortedRecordIds = generateRandomSortedRecords(2500);
		final MockSortedRecordsSupplier sortedRecordsSupplier = new MockSortedRecordsSupplier(sortedRecordIds);

		assertArrayEquals(
			sortedRecordIds,
			sortedRecordsSupplier.getSortedRecordIds()
		);

		final PreSortedRecordsSorter sorter = new PreSortedRecordsSorter(
			MergeMode.APPEND_FIRST,
			Comparator.naturalOrder(),
			OrderDirection.ASC,
			() -> new SortedRecordsProvider[]{sortedRecordsSupplier}
		);
		final QueryExecutionContext queryContext = Mockito.mock(QueryExecutionContext.class);
		// the sorter reads the planning context for the debug sort override / telemetry; the unstubbed mock returns
		// false for isDebugModeEnabled and null for getCurrentStep -> cost-based selection with telemetry off
		when(queryContext.getQueryContext()).thenReturn(Mockito.mock(QueryPlanningContext.class));
		when(queryContext.getPrefetchedEntities()).thenReturn(null);
		Mockito.doAnswer(invocation -> SharedBufferPool.INSTANCE.obtain()).when(queryContext).borrowBuffer();
		Mockito.doNothing().when(queryContext).returnBuffer(any());

		for (int i = 0; i < 5; i++) {
			int[] recIds = pickRandomResults(sortedRecordIds, 500);
			assertPageIsConsistent(sortedRecordIds, sorter, queryContext, recIds, 0, 50);
			assertPageIsConsistent(sortedRecordIds, sorter, queryContext, recIds, 75, 125);
			assertPageIsConsistent(sortedRecordIds, sorter, queryContext, recIds, 100, 500);
		}
	}

	@Test
	void shouldBreakTiesAcrossProvidersByPrimaryKeyInOrderingDirection() {
		// value 10 is held by records 1 (first provider) and 2 (second provider), value 20 by record 4 (first) and
		// records 3 and 6 (second), so the ties span both providers and one provider at the same time
		final int[] records = {1, 2, 3, 4, 5, 6};
		for (OrderDirection direction : OrderDirection.values()) {
			final int[] actual = mergeProviders(
				direction, records,
				createValueProvider(1, direction, 1, 10, 4, 20, 5, 30),
				createValueProvider(2, direction, 2, 10, 3, 20, 6, 20)
			);
			final int[] expected = direction == OrderDirection.ASC ?
				new int[]{1, 2, 3, 4, 6, 5} : new int[]{5, 6, 4, 3, 2, 1};
			assertArrayEquals(
				expected, actual,
				() -> direction + ": expected " + Arrays.toString(expected) + " but was " + Arrays.toString(actual)
			);
		}
	}

	@Test
	void shouldCompareRecordOnValueOfFirstProviderHoldingIt() {
		// record 1 is claimed by the first provider with value 30, so its value 5 in the second one never counts
		assertArrayEquals(
			new int[]{2, 1},
			mergeProviders(
				OrderDirection.ASC, new int[]{1, 2},
				createValueProvider(1, OrderDirection.ASC, 1, 30),
				createValueProvider(2, OrderDirection.ASC, 1, 5, 2, 10)
			)
		);
	}

	private record PreSortedRecordsSorterWithContext(
		@Nonnull PreSortedRecordsSorter sorter,
		@Nonnull QueryPlanningContext context
	) {
	}

}
