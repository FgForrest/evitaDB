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

import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.IntConsumer;

/**
 * This implementation of {@link Sorter} doesn't sort but just slices the formula output. The result ids are sorted
 * naturally - ie. from smallest to greatest id.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class NoSorter implements Sorter {
	public static final NoSorter INSTANCE = new NoSorter();

	private NoSorter() {
	}

	@Nonnull
	@Override
	public SortingContext sortAndSlice(
		@Nonnull SortingContext sortingContext,
		@Nonnull int[] result,
		@Nullable IntConsumer skippedRecordsConsumer
	) {
		final int recomputedStartIndex = sortingContext.recomputedStartIndex();
		final int recomputedEndIndex = sortingContext.recomputedEndIndex();
		final int peak = sortingContext.peak();
		final Bitmap filteredRecordIdBitmap = sortingContext.nonSortedKeys();

		final int skippedRecords = Math.min(recomputedStartIndex, filteredRecordIdBitmap.size());
		// the records preceding the page must reach the consumer - a wrapping sorter (such as a segment) relies on it
		// to exclude them from the input of the sorters that follow
		if (skippedRecordsConsumer != null && skippedRecords > 0) {
			for (int skippedRecord : filteredRecordIdBitmap.getRange(0, skippedRecords)) {
				skippedRecordsConsumer.accept(skippedRecord);
			}
		}

		final int maxLength = Math.max(0, Math.min(recomputedEndIndex - recomputedStartIndex, filteredRecordIdBitmap.size() - recomputedStartIndex));
		if (recomputedEndIndex > 0 && !filteredRecordIdBitmap.isEmpty()) {
			final int[] slice = filteredRecordIdBitmap.getRange(recomputedStartIndex, recomputedStartIndex + maxLength);
			final int copiedLength = Math.min(result.length - peak, slice.length);
			System.arraycopy(slice, 0, result, peak, copiedLength);
			return sortingContext.createResultContext(
				EmptyBitmap.INSTANCE,
				copiedLength,
				skippedRecords
			);
		} else {
			return sortingContext.createResultContext(
				EmptyBitmap.INSTANCE,
				0,
				skippedRecords
			);
		}
	}
}
