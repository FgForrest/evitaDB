/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.core.query.sort.segment;


import io.evitadb.api.query.order.SegmentLimit;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * The SegmentSorter class is a specialized sorter that sorts only a single output segment of the query result defined
 * by a {@link SegmentLimit} constraint - extracted to {@link #limit}. The sorter will delegate sorting to another sorter,
 * and when the limit is reached, it excludes all primary keys that has been already sorted by this segment and passes
 * the rest to another sorter in the chain (probably another segment sorter).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class SegmentSorter implements Sorter {
	/**
	 * The delegate sorter instance that will be used for actual sorting.
	 */
	private final List<Sorter> embeddedSorters;
	/**
	 * The filter that will be used to filter the input records prior to sorting.
	 */
	private final Formula filter;
	/**
	 * The maximum number of records that will be sorted by this sorter - even though the input may contain more records.
	 * The value is sourced from {@link SegmentLimit} constraint.
	 */
	private final int limit;

	public SegmentSorter(@Nonnull List<Sorter> embeddedSorters, @Nullable Formula filter, int limit) {
		this.embeddedSorters = embeddedSorters;
		this.filter = filter;
		this.limit = limit;
	}

	@Nonnull
	@Override
	public SortingContext sortAndSlice(
		@Nonnull SortingContext sortingContext,
		@Nonnull int[] result,
		@Nullable IntConsumer skippedRecordsConsumer
	) {
		final QueryExecutionContext queryContext = sortingContext.queryContext();
		final Bitmap nonSortedKeys = sortingContext.nonSortedKeys();
		final ConstantFormula nonSortedKeysFormula = new ConstantFormula(nonSortedKeys);
		final Bitmap keysToSort;

		// if the filter is defined we need to narrow the input to only records that satisfy the filter
		final Formula filterFormula;
		if (this.filter == null) {
			keysToSort = nonSortedKeys;
		} else {
			filterFormula = FormulaFactory.and(
				nonSortedKeysFormula,
				this.filter
			);
			filterFormula.initialize(queryContext);
			keysToSort = filterFormula.compute();
		}
		// if there are no records to sort
		if (keysToSort.isEmpty()) {
			// continue with the next sorter
			return sortingContext;
		} else {
			// otherwise, we need to fully calculate the segment to be able to exclude it from the next sorter
			final int physicalLimit = Math.min(keysToSort.size(), this.limit);
			final int recomputedEndIndex = Math.min(sortingContext.startIndex() + result.length, sortingContext.skipped() + sortingContext.peak() + physicalLimit);

			// all the skipped records will be written to this bitmap
			final RoaringBitmapWriter<RoaringBitmap> drainedRecordPks = RoaringBitmapBackedBitmap.buildWriter();
			SortingContext subResult = new SortingContext(
				queryContext,
				keysToSort,
				Math.min(sortingContext.startIndex(), recomputedEndIndex),
				recomputedEndIndex,
				sortingContext.peak(),
				sortingContext.skipped(),
				sortingContext.referenceKey()
			);
			for (Sorter embeddedSorter : this.embeddedSorters) {
				subResult = embeddedSorter.sortAndSlice(
					subResult,
					result,
					skippedRecordsConsumer == null ?
						drainedRecordPks::add :
						pk -> {
							skippedRecordsConsumer.accept(pk);
							drainedRecordPks.add(pk);
						}
				);
				if (result.length == subResult.peak()) {
					break;
				}
			}

			if (result.length == subResult.peak()) {
				// if the next sorter would receive empty input, we can skip it
				return sortingContext.createResultContext(
					EmptyBitmap.INSTANCE,
					subResult.peak() - sortingContext.peak(),
					subResult.skipped() - sortingContext.skipped()
				);
			} else {
				// now we have to manually add also all the records that were added to the result
				// since they hadn't been passed to drained records via skipped records consumer
				for (int i = sortingContext.peak(); i < subResult.peak(); i++) {
					drainedRecordPks.add(result[i]);
				}

				final int writtenRecords = subResult.peak() - sortingContext.peak();
				final RoaringBitmap drainedRecordIds = drainedRecordPks.get();
				int skippedRecords = drainedRecordIds.getCardinality() - (writtenRecords);
				// and filter the filtered input to next query to avoid records that has been already consumed
				// by this segment
				final Bitmap nextInput = drainedRecordIds.isEmpty() ?
					nonSortedKeys :
					FormulaFactory.not(
						new ConstantFormula(new BaseBitmap(drainedRecordIds)),
						nonSortedKeysFormula
					).compute();
				return sortingContext.createResultContext(
					nextInput.isEmpty() ?
						EmptyBitmap.INSTANCE : nextInput,
					writtenRecords,
					skippedRecords
				);
			}
		}
	}

}
