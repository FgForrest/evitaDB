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

package io.evitadb.core.query.sort.primaryKey;

import io.evitadb.core.query.sort.Sorter;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.IntConsumer;

/**
 * Exact sorter outputs filtered results in reversed order of the primary keys.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ReversedPrimaryKeySorter implements Sorter {
	public static final ReversedPrimaryKeySorter INSTANCE = new ReversedPrimaryKeySorter();

	private ReversedPrimaryKeySorter() {
	}

	@Nonnull
	@Override
	public SortingContext sortAndSlice(
		@Nonnull SortingContext sortingContext,
		@Nonnull int[] result,
		@Nullable IntConsumer skippedRecordsConsumer
	) {
		final Bitmap filteredRecordIdBitmap = sortingContext.nonSortedKeys();
		final int size = filteredRecordIdBitmap.size();

		final int recomputedStartIndex = sortingContext.recomputedStartIndex();
		final int recomputedEndIndex = sortingContext.recomputedEndIndex();
		final int peak = sortingContext.peak();


		// recalculate positions for reversed order
		final int length = recomputedEndIndex - recomputedStartIndex;
		final int[] range = filteredRecordIdBitmap.getRange(
			size - Math.min(size, length),
			size - recomputedStartIndex
		);
		// and copy the range contents in reversed order
		int newPeak = peak;
		for (int i = range.length - 1; i >= 0; i--) {
			result[newPeak++] = range[i];
		}
		return sortingContext.createResultContext(
			EmptyBitmap.INSTANCE,
			newPeak - peak,
			0
		);
	}

}
