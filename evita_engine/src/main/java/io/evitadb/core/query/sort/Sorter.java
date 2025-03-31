/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
import io.evitadb.core.query.algebra.Formula;

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
	 * Creates clone of this sorter instance.
	 */
	@Nonnull
	Sorter cloneInstance();

	/**
	 * Method allows creating combined sorter from this instance and passed instance.
	 * Creates new sorter that first sorts by this instance order and for rest records, that cannot be sorted by this
	 * sorter, the passed sorter will be used.
	 */
	@Nonnull
	Sorter andThen(Sorter sorterForUnknownRecords);

	/**
	 * Method returns next sorter in the sort chain. I.e. the sorter that will be applied on entity keys, that couldn't
	 * have been sorted by this sorter (due to lack of information).
	 */
	@Nullable
	Sorter getNextSorter();

	/**
	 * Sorts the data produced by the given {@link Formula}, slices the sorted data according to the provided
	 * pagination parameters, and stores the result in the specified array.
	 *
	 * @param queryContext           The context of the query execution.
	 * @param input                  The formula whose computed results need to be sorted.
	 * @param startIndex             The starting index for the slicing (inclusive).
	 * @param endIndex               The ending index for the slicing (exclusive).
	 * @param result                 An array to store the sliced result.
	 * @param peak                   A parameter to indicate last index of the result array where the data has been written.
	 * @param skipped                A parameter to indicate the number of skipped records by previous sorters.
	 * @param skippedRecordsConsumer A consumer to handle the records that are skipped during the process.
	 * @return The number of records that were processed and stored in the result array.
	 */
	int sortAndSlice(
		@Nonnull QueryExecutionContext queryContext,
		@Nonnull Formula input,
		int startIndex,
		int endIndex,
		@Nonnull int[] result,
		int peak,
		int skipped,
		@Nullable IntConsumer skippedRecordsConsumer
	);

	/**
	 * Sorts the data produced by the given {@link Formula}, slices the sorted data according to the provided
	 * pagination parameters, and stores the result in the specified array.
	 *
	 * @param queryContext           The context of the query execution.
	 * @param input                  The formula whose computed results need to be sorted.
	 * @param startIndex             The starting index for the slicing (inclusive).
	 * @param endIndex               The ending index for the slicing (exclusive).
	 * @param result                 An array to store the sliced result.
	 * @param peak                   A parameter to indicate last index of the result array where the data has been written.
	 * @param skipped                A parameter to indicate the number of skipped records by previous sorters.
	 * @return The number of records that were processed and stored in the result array.
	 */
	default int sortAndSlice(
		@Nonnull QueryExecutionContext queryContext,
		@Nonnull Formula input,
		int startIndex,
		int endIndex,
		@Nonnull int[] result,
		int peak,
		int skipped
	) {
		return sortAndSlice(
			queryContext,
			input,
			startIndex,
			endIndex,
			result,
			peak,
			skipped,
			null
		);
	}

}
