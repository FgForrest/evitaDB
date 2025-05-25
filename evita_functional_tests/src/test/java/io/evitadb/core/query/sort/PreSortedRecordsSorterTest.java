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

package io.evitadb.core.query.sort;

import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.SharedBufferPool;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.query.sort.Sorter.SortingContext;
import io.evitadb.core.query.sort.attribute.PreSortedRecordsSorter;
import io.evitadb.core.query.sort.attribute.PreSortedRecordsSorter.MergeMode;
import io.evitadb.core.query.sort.utils.MockSortedRecordsSupplier;
import io.evitadb.core.query.sort.utils.SortUtilsTest;
import io.evitadb.index.bitmap.BaseBitmap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * This test verifies {@link PreSortedRecordsSorter} behaviour.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
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
		when(executionContext.getPrefetchedEntities()).thenReturn(null);
		Mockito.doAnswer(invocation -> SharedBufferPool.INSTANCE.obtain()).when(executionContext).borrowBuffer();
		Mockito.doNothing().when(executionContext).returnBuffer(any());
		this.bitmapSorter = new PreSortedRecordsSorterWithContext(
			new PreSortedRecordsSorter(
				MergeMode.APPEND_FIRST,
				Comparator.naturalOrder(),
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
			() -> new SortedRecordsProvider[]{sortedRecordsSupplier}
		);
		final QueryExecutionContext queryContext = Mockito.mock(QueryExecutionContext.class);
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

	private record PreSortedRecordsSorterWithContext(
		@Nonnull PreSortedRecordsSorter sorter,
		@Nonnull QueryPlanningContext context
	) {
	}

}
