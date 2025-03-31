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

package io.evitadb.core.query.sort.primaryKey;

import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.sort.NoSorter;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.ArrayUtils;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.IntConsumer;

/**
 * Exact sorter outputs filtered results in an order defined by the order of input entity primary keys.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ExactSorter implements Sorter {
	/**
	 * The entity primary keys whose order must be maintained in the sorted result.
	 */
	private final int[] exactOrder;
	/**
	 * This sorter instance will be used for sorting entities, that cannot be sorted by this sorter.
	 */
	private final Sorter unknownRecordIdsSorter;

	public ExactSorter(@Nonnull int[] exactOrder) {
		this.exactOrder = exactOrder;
		this.unknownRecordIdsSorter = NoSorter.INSTANCE;
	}

	public ExactSorter(@Nonnull int[] exactOrder, @Nonnull Sorter unknownRecordIdsSorter) {
		this.exactOrder = exactOrder;
		this.unknownRecordIdsSorter = unknownRecordIdsSorter;
	}

	@Nonnull
	@Override
	public Sorter andThen(@Nonnull Sorter sorterForUnknownRecords) {
		return new ExactSorter(
			this.exactOrder,
			sorterForUnknownRecords
		);
	}

	@Nonnull
	@Override
	public Sorter cloneInstance() {
		return new ExactSorter(
			this.exactOrder,
			NoSorter.INSTANCE
		);
	}

	@Nullable
	@Override
	public Sorter getNextSorter() {
		return unknownRecordIdsSorter;
	}

	@Override
	public int sortAndSlice(
		@Nonnull QueryExecutionContext queryContext,
		@Nonnull Formula input,
		int startIndex,
		int endIndex,
		@Nonnull int[] result,
		int peak,
		int skipped,
		@Nullable IntConsumer skippedRecordsConsumer
	) {
		final Bitmap filteredRecordIdBitmap = input.compute();
		if (filteredRecordIdBitmap.isEmpty()) {
			return 0;
		} else {
			final int recomputedStartIndex = Math.max(0, startIndex - peak - skipped);
			final int recomputedEndIndex = Math.max(0, endIndex - peak - skipped);

			final int[] filteredRecordIds = filteredRecordIdBitmap.getArray();
			final int length = Math.min(filteredRecordIds.length, recomputedEndIndex - recomputedStartIndex);
			if (length < 0) {
				throw new IndexOutOfBoundsException("Index: " + recomputedStartIndex + ", Size: " + filteredRecordIds.length);
			}

			// sort the filtered entity primary keys along the exact order in input
			final int lastSortedItem = ArrayUtils.sortAlong(this.exactOrder, filteredRecordIds);

			// copy the sorted data to result
			final int toAppend = Math.min(lastSortedItem - recomputedStartIndex, recomputedEndIndex - recomputedStartIndex);
			System.arraycopy(filteredRecordIds, recomputedStartIndex, result, peak, toAppend);

			int skippedRecords = Math.min(recomputedStartIndex, filteredRecordIds.length);
			if (skippedRecordsConsumer != null) {
				for (int i = 0; i < skippedRecords; i++) {
					skippedRecordsConsumer.accept(filteredRecordIds[i]);
				}
			}

			// if there are no more records to sort or no additional sorter is present, return entire result
			if (lastSortedItem == filteredRecordIdBitmap.size()) {
				return peak + toAppend;
			} else {
				// otherwise, collect the not sorted record ids
				final RoaringBitmapWriter<RoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
				for (int i = lastSortedItem; i < filteredRecordIds.length; i++) {
					writer.add(filteredRecordIds[i]);
				}

				final RoaringBitmap outputBitmap = writer.get();
				return this.unknownRecordIdsSorter.sortAndSlice(
					queryContext, outputBitmap.isEmpty() ? EmptyFormula.INSTANCE : new ConstantFormula(new BaseBitmap(outputBitmap)),
					startIndex, endIndex, result, peak + toAppend, skipped + skippedRecords
				);
			}
		}
	}

}
