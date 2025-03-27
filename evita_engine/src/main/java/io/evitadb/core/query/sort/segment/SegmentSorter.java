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
import io.evitadb.core.query.sort.ConditionalSorter;
import io.evitadb.core.query.sort.NoSorter;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
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
	 * This sorter instance will be used for sorting entities, that cannot be sorted by this sorter.
	 * Usually the next segment sorter.
	 */
	private final Sorter unknownRecordIdsSorter;
	/**
	 * The delegate sorter instance that will be used for actual sorting.
	 */
	private final Sorter delegateSorter;
	/**
	 * The filter that will be used to filter the input records prior to sorting.
	 */
	private final Formula filter;
	/**
	 * The maximum number of records that will be sorted by this sorter - even though the input may contain more records.
	 * The value is sourced from {@link SegmentLimit} constraint.
	 */
	private final int limit;

	public SegmentSorter(@Nonnull Sorter delegateSorter, @Nullable Formula filter, int limit) {
		this.delegateSorter = delegateSorter;
		this.filter = filter;
		this.limit = limit;
		this.unknownRecordIdsSorter = NoSorter.INSTANCE;
	}

	public SegmentSorter(@Nonnull Sorter delegateSorter, @Nullable Formula filter, int limit, @Nonnull Sorter unknownRecordIdsSorter) {
		this.delegateSorter = delegateSorter;
		this.filter = filter;
		this.limit = limit;
		this.unknownRecordIdsSorter = unknownRecordIdsSorter;
	}

	@Nonnull
	@Override
	public Sorter cloneInstance() {
		return new SegmentSorter(
			this.delegateSorter.cloneInstance(),
			this.filter,
			this.limit,
			this.unknownRecordIdsSorter
		);
	}

	@Nonnull
	@Override
	public Sorter andThen(Sorter sorterForUnknownRecords) {
		return new SegmentSorter(this.delegateSorter, this.filter, this.limit, sorterForUnknownRecords);
	}

	@Nullable
	@Override
	public Sorter getNextSorter() {
		return this.unknownRecordIdsSorter;
	}

	@Override
	public int sortAndSlice(
		@Nonnull QueryExecutionContext queryContext,
		@Nonnull Formula input,
		int startIndex,
		int endIndex,
		@Nonnull int[] result,
		int peak,
		@Nullable IntConsumer skippedRecordsConsumer
	) {
		// if the filter is defined we need to narrow the input to only records that satisfy the filter
		final Formula filterFormula;
		if (this.filter == null) {
			filterFormula = input;
		} else {
			filterFormula = FormulaFactory.and(input, this.filter);
			filterFormula.initialize(queryContext);
		}
		final Bitmap filteredRecordIdBitmap = filterFormula.compute();
		// if there are no records to sort
		if (filteredRecordIdBitmap.isEmpty()) {
			// and even the non-filtered input is empty
			if (input.compute().isEmpty()) {
				return 0;
			} else {
				// otherwise, our filter is too strict and we need to pass the input to the next sorter
				return unknownRecordIdsSorter.sortAndSlice(
					queryContext,
					// we may pass the complete input, since we didn't drained any records in this segment
					input, startIndex, endIndex, result, peak
				);
			}
		} else {
			final Sorter sorterToUse = Objects.requireNonNull(ConditionalSorter.getFirstApplicableSorter(queryContext, this.delegateSorter));
			// otherwise, we need to fully calculate the segment to be able to exclude it from the next sorter
			final int physicalLimit = Math.min(filteredRecordIdBitmap.size(), this.limit);
			final int endIndexOrLimit = Math.min(physicalLimit, endIndex);

			// all the skipped records will be written to this bitmap
			final RoaringBitmapWriter<RoaringBitmap> drainedRecordPks = RoaringBitmapBackedBitmap.buildWriter();
			final int lastSortedItem = sorterToUse.sortAndSlice(
				queryContext,
				filterFormula,
				Math.min(startIndex, endIndexOrLimit),
				endIndexOrLimit,
				result,
				peak,
				drainedRecordPks::add
			);
			// now we have to manually add also all the records that were added to the result
			// since the hadn't been passed to drained records via skipped records consumer
			for (int i = peak; i < lastSortedItem; i++) {
				drainedRecordPks.add(result[i]);
			}

			// recalculate indexes for the next sorter
			final int recomputedStartIndex = Math.max(0, startIndex - physicalLimit);
			final int recomputedEndIndex = Math.max(0, endIndex - physicalLimit);

			if (recomputedStartIndex >= recomputedEndIndex || lastSortedItem - peak >= endIndex) {
				// if the next sorter would receive empty input, we can skip it
				return lastSortedItem;
			} else {
				final ConstantFormula drainedRecordsFormula = new ConstantFormula(new BaseBitmap(drainedRecordPks.get()));
				return unknownRecordIdsSorter.sortAndSlice(
					queryContext,
					// and filter the filtered input to next query to avoid records that has been already consumed
					// by this segment
					FormulaFactory.not(drainedRecordsFormula, input),
					recomputedStartIndex, recomputedEndIndex, result, lastSortedItem
				);
			}
		}
	}

}
