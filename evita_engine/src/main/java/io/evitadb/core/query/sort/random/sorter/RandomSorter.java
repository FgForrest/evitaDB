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

package io.evitadb.core.query.sort.random.sorter;

import io.evitadb.core.query.sort.Sorter;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Random;
import java.util.function.IntConsumer;

/**
 * Random sorter outputs filtered results in a random order. Ordering is optimized to the requested
 * slice only and doesn't process positions after the requested slice. Randomization is done via a
 * partial Fisher-Yates shuffle that always starts from the very first position - this guarantees
 * that a given seed produces a single, stable permutation of the whole filtered set, so that
 * requesting subsequent pages (offset/limit) with the same seed yields disjoint, continuous slices
 * of that permutation instead of repeating the same records.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class RandomSorter implements Sorter {
	public static final RandomSorter INSTANCE = new RandomSorter();
	/**
	 * The seed the random permutation is derived from. When `null` (the {@link #INSTANCE} singleton),
	 * each call falls back to `sortingContext.queryContext().getRandom()`, which may hand out a fresh,
	 * non-deterministic `Random` per call - no cross-call ordering stability is guaranteed in that
	 * case. When set, it fixes the permutation of the whole filtered set deterministically, so
	 * repeated calls (e.g. subsequent pages of the same query) reproduce the same shuffle.
	 */
	private final Long seed;

	private RandomSorter() {
		this.seed = null;
	}

	public RandomSorter(long seed) {
		this.seed = seed;
	}

	/**
	 * Shuffles the prefix `[0, end)` of the non-sorted keys with a partial Fisher-Yates shuffle and
	 * copies the requested slice `[recomputedStartIndex, recomputedEndIndex)` into `result`.
	 *
	 * Unlike a plain "shuffle only what you need" optimization, this implementation always draws
	 * randomness for the *entire* prefix up to `end`, regardless of `recomputedStartIndex`. This is
	 * essential when {@link #seed} is set: the same seed must consume exactly the same sequence of
	 * random draws on every invocation, independently of the requested offset. Consequently, calling
	 * this method repeatedly with the same seed and increasing offsets (i.e. paging through results)
	 * yields disjoint, continuous slices of one single stable permutation instead of independently
	 * reshuffled - and therefore overlapping or repeating - pages.
	 *
	 * When no seed is set ({@link #INSTANCE}), no such cross-call stability is guaranteed, since
	 * `queryContext().getRandom()` may return a different `Random` instance for each call.
	 *
	 * Out-of-range requests are handled defensively rather than throwing raw array-bounds exceptions:
	 * a start index beyond the candidate count yields an empty slice, mirroring the behavior of the
	 * sibling {@link io.evitadb.core.query.sort.NoSorter}. A destination `result` buffer smaller than
	 * the computed slice still fails fast, but with a descriptive internal error instead of a raw
	 * array-bounds exception, since silently returning fewer records than requested would be data
	 * loss.
	 */
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
		final int end = Math.min(filteredRecordIds.length, recomputedEndIndex);
		final int length = Math.max(0, end - recomputedStartIndex);
		// nothing to shuffle or copy for this page - skip the O(end) shuffle entirely instead of
		// paying its cost just to discard the result (happens when the requested start index is at
		// or beyond the available candidates for this sorter)
		if (length == 0) {
			return sortingContext.createResultContext(
				EmptyBitmap.INSTANCE,
				0,
				Math.min(recomputedStartIndex, filteredRecordIdBitmap.size())
			);
		}
		final Random random = this.seed == null ?
			sortingContext.queryContext().getRandom() : new Random(this.seed);
		// a seeded shuffle must produce the same permutation regardless of the requested page, so the
		// whole prefix [0, end) has to be shuffled (consuming the same random draws every time) instead
		// of only [start, end) - otherwise every page would reuse the same draws and return the same
		// records
		for (int i = 0; i < end; i++) {
			final int swapPosition = i + random.nextInt(filteredRecordIds.length - i);
			final int tmp = filteredRecordIds[i];
			filteredRecordIds[i] = filteredRecordIds[swapPosition];
			filteredRecordIds[swapPosition] = tmp;
		}

		// clamp the copied length to the remaining destination capacity so we never write past the
		// buffer end
		final int copiedLength = Math.min(result.length - peak, length);
		// clamp the source read position too - JDK arraycopy rejects a source offset beyond the array
		// bounds even when the copied length is zero (happens when the requested start index exceeds
		// the candidate count)
		final int copySourceIndex = Math.min(recomputedStartIndex, filteredRecordIds.length);
		System.arraycopy(filteredRecordIds, copySourceIndex, result, peak, copiedLength);
		return sortingContext.createResultContext(
			EmptyBitmap.INSTANCE,
			copiedLength,
			Math.min(recomputedStartIndex, filteredRecordIdBitmap.size())
		);
	}
}
