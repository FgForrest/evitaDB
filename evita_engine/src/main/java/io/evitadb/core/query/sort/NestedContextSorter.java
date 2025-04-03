/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.sort.Sorter.SortingContext;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.function.Supplier;

/**
 * This class is a wrapper for {@link Sorter} along with correct nested {@link QueryPlanningContext} initialized for proper
 * query and entity type.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
public class NestedContextSorter {
	/**
	 * The context of the query execution.
	 */
	private final QueryExecutionContext context;
	/**
	 * The supplier of text that is accompanied by the step in the query execution.
	 */
	private final @Nonnull Supplier<String> stepDescriptionSupplier;
	/**
	 * The ordered list of sorters that will be applied to the input.
	 */
	private final List<Sorter> sorters;

	public NestedContextSorter(
		@Nonnull QueryExecutionContext context,
		@Nonnull Supplier<String> stepDescriptionSupplier
	) {
		this.context = context;
		this.stepDescriptionSupplier = stepDescriptionSupplier;
		this.sorters = null;
	}

	/**
	 * Sorts and slices a set of non-sorted record IDs from the provided bitmap.
	 * This method processes the input formula by applying a series of {@link Sorter} instances for sorting
	 * and slicing, and if necessary, appends unsorted records to the output.
	 *
	 * @param nonSortedRecordIds a {@link Bitmap} containing the record IDs to be sorted and sliced; must not be null
	 * @return an array of sorted and sliced record IDs
	 */
	public int[] sortAndSlice(@Nonnull Formula nonSortedRecordIds) {
		return sortAndSlice(nonSortedRecordIds.compute());
	}

	/**
	 * Sorts and slices a set of non-sorted record IDs from the provided bitmap.
	 * This method processes the input bitmap by applying a series of {@link Sorter} instances for sorting
	 * and slicing, and if necessary, appends unsorted records to the output.
	 *
	 * @param nonSortedRecordIds a {@link Bitmap} containing the record IDs to be sorted and sliced; must not be null
	 * @return an array of sorted and sliced record IDs
	 */
	@Nonnull
	public int[] sortAndSlice(@Nonnull Bitmap nonSortedRecordIds) {
		final int[] result = new int[nonSortedRecordIds.size()];

		try {
			this.context.pushStep(QueryPhase.EXECUTION_SORT_AND_SLICE, this.stepDescriptionSupplier);

			SortingContext sortingContext = new SortingContext(
				this.context,
				nonSortedRecordIds,
				0, nonSortedRecordIds.size(),
				0, 0
			);

			if (this.sorters != null) {
				for (Sorter sorter : this.sorters) {
					sortingContext = sorter.sortAndSlice(sortingContext, result, null);
				}
			}

			// append the rest of the records if not all are sorted
			if (sortingContext.peak() < result.length) {
				sortingContext = NoSorter.INSTANCE.sortAndSlice(sortingContext, result, null);
			}

			Assert.isPremiseValid(
				sortingContext.peak() == result.length,
				"Sorter produced less (" + sortingContext.peak() + ") than " + result.length + " records! This is not expected!"
			);

			return result;

		} finally {
			this.context.popStep();
		}
	}

}
