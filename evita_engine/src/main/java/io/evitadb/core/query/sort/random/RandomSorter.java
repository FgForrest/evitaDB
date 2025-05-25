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

package io.evitadb.core.query.sort.random;

import io.evitadb.core.query.sort.Sorter;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Random;
import java.util.function.IntConsumer;

/**
 * Random sorter outputs filtered results in a random order. Ordering is optimized to the requested slice only and doesn't
 * process positions after the requested slice. Randomization is done by random swapping positions that are requested to
 * be returned with records on random positions.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class RandomSorter implements Sorter {
	public static final RandomSorter INSTANCE = new RandomSorter();
	private final Long seed;

	private RandomSorter() {
		this.seed = null;
	}

	public RandomSorter(long seed) {
		this.seed = seed;
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

		final int[] filteredRecordIds = filteredRecordIdBitmap.getArray();
		final int length = Math.min(filteredRecordIds.length, recomputedEndIndex - recomputedStartIndex);
		if (length < 0) {
			throw new IndexOutOfBoundsException("Index: " + recomputedStartIndex + ", Size: " + filteredRecordIds.length);
		}
		final Random random = this.seed == null ?
			sortingContext.queryContext().getRandom() : new Random(this.seed);
		for (int i = 0; i < length; i++) {
			final int tmp = filteredRecordIds[recomputedStartIndex + i];
			final int swapPosition = random.nextInt(filteredRecordIds.length);
			filteredRecordIds[recomputedStartIndex + i] = filteredRecordIds[swapPosition];
			filteredRecordIds[swapPosition] = tmp;
		}

		System.arraycopy(filteredRecordIds, recomputedStartIndex, result, peak, length);
		return sortingContext.createResultContext(
			EmptyBitmap.INSTANCE,
			length,
			0
		);
	}
}
