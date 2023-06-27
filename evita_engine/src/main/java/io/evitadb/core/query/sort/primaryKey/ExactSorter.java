/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.query.sort.primaryKey;

import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
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

/**
 * Exact sorter outputs filtered results in an order defined by the order of input entity primary keys.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ExactSorter implements Sorter {
	private static final int[] EMPTY_RESULT = new int[0];
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
	public Sorter andThen(Sorter sorterForUnknownRecords) {
		return new ExactSorter(
			exactOrder,
			sorterForUnknownRecords
		);
	}

	@Nonnull
	@Override
	public Sorter cloneInstance() {
		return new ExactSorter(
			exactOrder,
			null
		);
	}

	@Nullable
	@Override
	public Sorter getNextSorter() {
		return unknownRecordIdsSorter;
	}

	@Nonnull
	@Override
	public int[] sortAndSlice(@Nonnull QueryContext queryContext, @Nonnull Formula input, int startIndex, int endIndex) {
		final Bitmap result = input.compute();
		if (result.isEmpty()) {
			return EMPTY_RESULT;
		} else {
			final int[] entireResult = result.getArray();
			final int length = Math.min(entireResult.length, endIndex - startIndex);
			if (length < 0) {
				throw new IndexOutOfBoundsException("Index: " + startIndex + ", Size: " + entireResult.length);
			}

			// sort the filtered entity primary keys along the exact order in input
			final int lastSortedItem = ArrayUtils.sortAlong(exactOrder, entireResult);

			// if there are no more records to sort or no additional sorter is present, return entire result
			if (lastSortedItem + 1 == result.size() || unknownRecordIdsSorter == NoSorter.INSTANCE) {
				return entireResult;
			} else {
				// otherwise, collect the not sorted record ids
				final RoaringBitmapWriter<RoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
				for (int i = lastSortedItem; i < entireResult.length; i++) {
					writer.add(entireResult[i]);
				}
				// pass them to another sorter
				final int recomputedStartIndex = Math.max(0, startIndex - lastSortedItem);
				final int recomputedEndIndex = Math.max(0, endIndex - lastSortedItem);
				final int[] unsortedResult = unknownRecordIdsSorter.sortAndSlice(
					queryContext, new ConstantFormula(new BaseBitmap(writer.get())),
					recomputedStartIndex, recomputedEndIndex
				);
				// and combine with our result
				System.arraycopy(unsortedResult, 0, entireResult, lastSortedItem, unsortedResult.length);
				return entireResult;
			}
		}
	}

}
