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
	 * The maximum number of records that will be sorted by this sorter - even though the input may contain more records.
	 * The value is sourced from {@link SegmentLimit} constraint.
	 */
	private final int limit;

	public SegmentSorter(@Nonnull Sorter delegateSorter, int limit) {
		this.delegateSorter = delegateSorter;
		this.limit = limit;
		this.unknownRecordIdsSorter = NoSorter.INSTANCE;
	}

	public SegmentSorter(@Nonnull Sorter delegateSorter, int limit, @Nonnull Sorter unknownRecordIdsSorter) {
		this.delegateSorter = delegateSorter;
		this.limit = limit;
		this.unknownRecordIdsSorter = unknownRecordIdsSorter;
	}

	@Nonnull
	@Override
	public Sorter cloneInstance() {
		return new SegmentSorter(
			this.delegateSorter.cloneInstance(),
			this.limit,
			this.unknownRecordIdsSorter
		);
	}

	@Nonnull
	@Override
	public Sorter andThen(Sorter sorterForUnknownRecords) {
		return new SegmentSorter(this.delegateSorter, this.limit, sorterForUnknownRecords);
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
		int peak
	) {
		final Bitmap filteredRecordIdBitmap = input.compute();
		if (filteredRecordIdBitmap.isEmpty()) {
			return 0;
		} else {
			final Sorter sorterToUse = ConditionalSorter.getFirstApplicableSorter(queryContext, this.delegateSorter);
			// if the end index is below the limit
			if (endIndex <= this.limit) {
				// this segment will be the last we need to calculate
				return sorterToUse.sortAndSlice(
					queryContext,
					input,
					startIndex,
					endIndex,
					result,
					peak
				);
			} else {
				/* TODO JNO - až tohle bude fungovat, tak do `sortAndSlice` přidat podporu pro IntConsumer pro přeskočené položky */
				// otherwise, we need to fully calculate the segment to be able to exclude it from the next sorter
				final int endIndexOrLimit = Math.min(this.limit, endIndex);
				final int[] tmpArray = new int[endIndexOrLimit];
				final int sortedCount = sorterToUse.sortAndSlice(
					queryContext,
					input,
					/* TODO JNO - a tady pak můžeme použít startIndex */
					0,
					endIndexOrLimit,
					tmpArray,
					0
				);
				final int appended = Math.max(0, sortedCount - startIndex);
				final int lastSortedItem = peak + appended;
				for (int i = startIndex; i < sortedCount; i++) {
					result[peak++] = tmpArray[i];
				}

				// if there are no more records to sort or no additional sorter is present, return entire result
				if (lastSortedItem == filteredRecordIdBitmap.size()) {
					return lastSortedItem;
				} else {
					// collect all "sorted" record ids by this segment
					final RoaringBitmapWriter<RoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
					for (int i = 0; i < sortedCount; i++) {
						writer.add(tmpArray[i]);
					}

					// recalculate indexes for the next sorter
					final int recomputedStartIndex = Math.max(0, startIndex - this.limit);
					final int recomputedEndIndex = Math.max(0, endIndex - this.limit);

					return unknownRecordIdsSorter.sortAndSlice(
						queryContext,
						// and filter the filtered input to next query to avoid records that has been already consumed
						// by this segment
						FormulaFactory.not(new ConstantFormula(new BaseBitmap(writer.get())), input),
						recomputedStartIndex, recomputedEndIndex, result, lastSortedItem
					);
				}
			}
		}
	}
}
