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

package io.evitadb.core.query.sort.random.sorter;

import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.sort.Sorter.SortingContext;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.test.utils.SortUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Random;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.ORDER;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RandomSorter} that drive {@link RandomSorter#sortAndSlice} directly against a hand-built
 * {@link SortingContext}, without spinning up a full Evita instance. The tests focus on the permutation guarantees of
 * the seeded shuffle, the pagination invariant that requesting consecutive slices with the same seed reconstructs one
 * stable permutation, boundary conditions and defensive behaviour on out-of-range requests.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(ENGINE)
@Tag(ORDER)
@DisplayName("RandomSorter random ordering and slicing")
class RandomSorterTest {
	/**
	 * Fixed seed used across deterministic test cases so that the produced permutation is stable and reproducible.
	 */
	private static final long SEED = 42L;

	@Nested
	@DisplayName("Construction and seeding")
	class ConstructionTest {

		@Test
		@DisplayName("Two sorters sharing a seed produce the identical permutation")
		void shouldProduceSamePermutationWhenSeedIsShared() {
			final int[] records = range(1, 20);
			final QueryExecutionContext firstContext = mockQueryContext();
			final QueryExecutionContext secondContext = mockQueryContext();

			final int[] firstResult = sortAndSlice(new RandomSorter(SEED), firstContext, records, 0, records.length);
			final int[] secondResult = sortAndSlice(new RandomSorter(SEED), secondContext, records, 0, records.length);

			assertArrayEquals(firstResult, secondResult);
		}

		@Test
		@DisplayName("Sorters seeded differently produce different orderings")
		void shouldProduceDifferentOrderWhenSeedDiffers() {
			final int[] records = range(1, 20);
			final int[] firstResult = sortAndSlice(new RandomSorter(SEED), mockQueryContext(), records, 0, records.length);
			final int[] secondResult = sortAndSlice(new RandomSorter(SEED + 1), mockQueryContext(), records, 0, records.length);

			assertIsPermutationOf(records, firstResult);
			assertIsPermutationOf(records, secondResult);
			assertFalse(Arrays.equals(firstResult, secondResult), "Different seeds must not produce the same order.");
		}
	}

	@Nested
	@DisplayName("Core slicing and permutation behaviour")
	class CoreOperationsTest {

		@Test
		@DisplayName("Full range returns a reordered permutation of the whole input set")
		void shouldReturnPermutationWhenFullRangeRequested() {
			final int[] records = range(1, 20);

			final int[] result = sortAndSlice(new RandomSorter(SEED), mockQueryContext(), records, 0, records.length);

			assertEquals(records.length, result.length);
			assertIsPermutationOf(records, result);
			// the shuffle must actually change the natural ascending order for a set of this size
			assertFalse(Arrays.equals(records, result), "Random order must differ from the ascending input order.");
		}

		@Test
		@DisplayName("End index beyond candidate count is clamped to the available records")
		void shouldClampWhenEndIndexExceedsCandidateCount() {
			final int[] records = range(1, 20);

			final int[] result = sortAndSlice(new RandomSorter(SEED), mockQueryContext(), records, 0, records.length + 50);

			assertEquals(records.length, result.length);
			assertIsPermutationOf(records, result);
		}
	}

	@Nested
	@DisplayName("Seeded pagination invariant")
	class PaginationTest {

		@Test
		@DisplayName("Consecutive seeded slices reconstruct one stable permutation without gaps or repeats")
		void shouldReconstructStablePermutationWhenPagingWithSameSeed() {
			final int[] records = range(1, 20);
			// the single, stable permutation of the whole set fetched in one go
			final int[] fullOrder = sortAndSlice(new RandomSorter(SEED), mockQueryContext(), records, 0, records.length);

			// each page uses a fresh sorter seeded identically, exactly as separate paged queries would
			final int[] firstPage = sortAndSlice(new RandomSorter(SEED), mockQueryContext(), records, 0, 7);
			final int[] secondPage = sortAndSlice(new RandomSorter(SEED), mockQueryContext(), records, 7, 14);
			final int[] thirdPage = sortAndSlice(new RandomSorter(SEED), mockQueryContext(), records, 14, 20);

			assertEquals(7, firstPage.length);
			assertEquals(7, secondPage.length);
			assertEquals(6, thirdPage.length);

			final int[] paged = concat(firstPage, secondPage, thirdPage);
			// paging through all slices must reconstruct the very same permutation returned by the single full fetch
			assertArrayEquals(fullOrder, paged, "Paged slices don't reconstruct the stable full-range permutation.");
			// and it must still contain exactly the same multiset of records as the input
			assertIsPermutationOf(records, paged);
		}
	}

	@Nested
	@DisplayName("Edge cases")
	class EdgeCaseTest {

		@Test
		@DisplayName("Empty candidate set returns an empty slice and does not throw")
		void shouldReturnEmptySliceWhenNoCandidateRecords() {
			final QueryExecutionContext context = mockQueryContext();
			final int[] result = new int[16];
			final SortingContext resultContext = new RandomSorter(SEED).sortAndSlice(
				new SortingContext(context, EmptyBitmap.INSTANCE, 0, 10, 0, 0),
				result,
				null
			);

			assertEquals(0, resultContext.peak());
			assertEquals(0, SortUtils.asResult(result, resultContext.peak()).length);
		}

		@Test
		@DisplayName("Single candidate record is returned as the only element")
		void shouldReturnSingleRecordWhenOnlyOneCandidate() {
			final int[] records = new int[]{42};

			final int[] result = sortAndSlice(new RandomSorter(SEED), mockQueryContext(), records, 0, 10);

			assertArrayEquals(records, result);
		}

		@Test
		@DisplayName("Null seed draws from the query context random and produces a permutation")
		void shouldUseQueryContextRandomWhenSeedIsNull() {
			final int[] records = range(1, 20);
			final QueryExecutionContext context = mock(QueryExecutionContext.class);
			when(context.getRandom()).thenReturn(new Random(SEED));

			final int[] result = sortAndSlice(RandomSorter.INSTANCE, context, records, 0, records.length);

			// the non-seeded path must consult the query context supplied random source
			verify(context, atLeastOnce()).getRandom();
			assertIsPermutationOf(records, result);
		}
	}

	@Nested
	@DisplayName("Out-of-range requests")
	class ErrorHandlingTest {

		@Test
		@DisplayName("Start index beyond candidate count returns an empty slice without throwing")
		void shouldReturnEmptySliceWhenStartIndexBeyondCandidateCount() {
			final int[] records = range(1, 5);
			final QueryExecutionContext context = mockQueryContext();
			final int[] result = new int[16];

			final SortingContext resultContext = new RandomSorter(SEED).sortAndSlice(
				new SortingContext(context, new BaseBitmap(records), 10, 15, 0, 0),
				result,
				null
			);

			assertEquals(0, resultContext.peak());
			assertEquals(0, SortUtils.asResult(result, resultContext.peak()).length);
		}

		@Test
		@DisplayName("Result buffer smaller than the requested slice fails fast instead of writing out of bounds")
		void shouldFailFastWhenResultBufferSmallerThanSlice() {
			final int[] records = range(1, 20);
			final QueryExecutionContext context = mockQueryContext();
			final int[] result = new int[10];

			// a terminal sorter must account for every requested record either by writing it into the result or by
			// skipping it; a destination buffer that cannot hold the requested slice makes that impossible, so the
			// call must fail fast with a descriptive internal error rather than silently dropping requested records
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new RandomSorter(SEED).sortAndSlice(
					new SortingContext(context, new BaseBitmap(records), 0, 20, 0, 0),
					result,
					null
				)
			);
		}
	}

	/**
	 * Runs {@link RandomSorter#sortAndSlice} over a freshly built {@link BaseBitmap} of the given record ids using a
	 * generously sized destination buffer and returns the produced slice capped at the written peak.
	 *
	 * @param sorter     the sorter under test
	 * @param context    the query execution context to be used as the sorting context
	 * @param recordIds  the candidate record ids forming the non-sorted key set
	 * @param startIndex the slice start index (inclusive)
	 * @param endIndex   the slice end index (exclusive)
	 * @return the produced slice of record ids
	 */
	@Nonnull
	private static int[] sortAndSlice(
		@Nonnull RandomSorter sorter,
		@Nonnull QueryExecutionContext context,
		@Nonnull int[] recordIds,
		int startIndex,
		int endIndex
	) {
		final int[] result = new int[512];
		final SortingContext resultContext = sorter.sortAndSlice(
			new SortingContext(context, new BaseBitmap(recordIds), startIndex, endIndex, 0, 0),
			result,
			null
		);
		return SortUtils.asResult(result, resultContext.peak());
	}

	/**
	 * Asserts that `actual` contains exactly the same multiset of record ids as `expectedSet`, regardless of order,
	 * by comparing both arrays after sorting a defensive copy of each.
	 *
	 * @param expectedSet the record ids expected to be present
	 * @param actual      the record ids produced by the sorter
	 */
	private static void assertIsPermutationOf(@Nonnull int[] expectedSet, @Nonnull int[] actual) {
		final int[] expectedSorted = expectedSet.clone();
		final int[] actualSorted = actual.clone();
		Arrays.sort(expectedSorted);
		Arrays.sort(actualSorted);
		assertArrayEquals(expectedSorted, actualSorted, "Result is not a permutation of the expected record set.");
	}

	/**
	 * Builds a mock {@link QueryExecutionContext} suitable for seeded sorter runs, where the context supplied random
	 * source is never consulted.
	 *
	 * @return a bare mock query execution context
	 */
	@Nonnull
	private static QueryExecutionContext mockQueryContext() {
		return mock(QueryExecutionContext.class);
	}

	/**
	 * Concatenates the provided integer arrays in order into a single array.
	 *
	 * @param arrays the arrays to concatenate
	 * @return the concatenated array
	 */
	@Nonnull
	private static int[] concat(@Nonnull int[]... arrays) {
		int totalLength = 0;
		for (final int[] array : arrays) {
			totalLength += array.length;
		}
		final int[] result = new int[totalLength];
		int offset = 0;
		for (final int[] array : arrays) {
			System.arraycopy(array, 0, result, offset, array.length);
			offset += array.length;
		}
		return result;
	}

	/**
	 * Produces a contiguous ascending array of record ids from `from` (inclusive) to `to` (inclusive).
	 *
	 * @param from the first record id (inclusive)
	 * @param to   the last record id (inclusive)
	 * @return the ascending record id array
	 */
	@Nonnull
	private static int[] range(int from, int to) {
		final int[] result = new int[to - from + 1];
		for (int i = 0; i < result.length; i++) {
			result[i] = from + i;
		}
		return result;
	}
}
