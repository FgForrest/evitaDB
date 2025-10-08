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

import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.IntConsumer;

/**
 * Sorter implementation allows to convert and slice result of the {@link Formula#compute()} to final slice of data
 * ({@link java.util.List}) that respects pagination settings.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface Sorter {

	/**
	 * Sorts the data produced by the given {@link Formula}, slices the sorted data according to the provided
	 * pagination parameters, and stores the result in the specified array.
	 *
	 * @param result                 An array to store the sliced result.
	 * @param skippedRecordsConsumer A consumer to handle the records that are skipped during the process.
	 * @return The number of records that were processed and stored in the result array.
	 */
	@Nonnull
	SortingContext sortAndSlice(
		@Nonnull SortingContext sortingContext,
		@Nonnull int[] result,
		@Nullable IntConsumer skippedRecordsConsumer
	);

	/**
	 * Context for sorting and slicing operations by a single sorter. The sorter is expected to create another instance
	 * of context as its result.
	 *
	 * @param queryContext  The context of the query execution.
	 * @param nonSortedKeys The formula whose computed results need to be sorted.
	 * @param startIndex    The starting index for the slicing (inclusive).
	 * @param endIndex      The ending index for the slicing (exclusive).
	 * @param peak          A parameter to indicate last index of the result array where the data has been written.
	 * @param skipped       A parameter to indicate the number of skipped records by previous sorters.
	 */
	record SortingContext(
		@Nonnull QueryExecutionContext queryContext,
		@Nonnull Bitmap nonSortedKeys,
		int startIndex,
		int endIndex,
		int peak,
		int skipped,
		@Nullable RepresentativeReferenceKey referenceKey
	) {

		public SortingContext(
			@Nonnull QueryExecutionContext queryContext,
			@Nonnull Bitmap nonSortedKeys,
			int startIndex, int endIndex, int peak, int skipped
		) {
			this(
				queryContext,
				nonSortedKeys,
				startIndex,
				endIndex,
				peak,
				skipped,
				null
			);
		}

		/**
		 * Creates and returns a new instance of the {@link SortingContext} using the current query context,
		 * updated non-sorted keys, start and end indexes, and the specified sortedCount and skippedCount values.
		 *
		 * @param nonSortedKeys the formula containing results that need to be sorted
		 * @param sortedCount count of the records sorted by this sorter
		 * @param skippedCount count of the records skipped by this sorter
		 * @return a new instance of the {@link SortingContext} with updated parameters
		 */
		@Nonnull
		public SortingContext createResultContext(
			@Nonnull Bitmap nonSortedKeys,
			int sortedCount,
			int skippedCount
		) {
			Assert.isPremiseValid(
				// we've reached expected end index
				(this.peak + sortedCount == Math.min(this.peak + this.skipped + this.nonSortedKeys.size(), this.endIndex) - this.startIndex) ||
					// the number of keys to store is equivalent to original number of keys minus sorted and skipped records
					(nonSortedKeys.size() == this.nonSortedKeys.size() - sortedCount - skippedCount),
				"Some records were not sorted or skipped!"
			);

			return new SortingContext(
				this.queryContext,
				nonSortedKeys,
				this.startIndex,
				this.endIndex,
				this.peak + sortedCount,
				this.skipped + skippedCount,
				this.referenceKey
			);
		}

		/**
		 * Returns calculated start index taking into account the already written and skipped records.
		 * @return the calculated start index
		 */
		public int recomputedStartIndex() {
			return Math.max(0, this.startIndex - this.peak - this.skipped);
		}

		/**
		 * Returns calculated end index taking into account the already written and skipped records.
		 * @return the calculated end index
		 */
		public int recomputedEndIndex() {
			return Math.max(0, this.endIndex - this.peak - this.skipped);
		}

	}

}
