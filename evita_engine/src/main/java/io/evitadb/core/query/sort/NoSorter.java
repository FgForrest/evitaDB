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

import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.index.bitmap.Bitmap;

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
	public Sorter andThen(Sorter sorterForUnknownRecords) {
		throw new UnsupportedOperationException("NoSorter cannot be chained!");
	}

	@Nonnull
	@Override
	public Sorter cloneInstance() {
		return this;
	}

	@Nullable
	@Override
	public Sorter getNextSorter() {
		return null;
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
		final int recomputedStartIndex = Math.max(0, startIndex - peak - skipped);
		final int recomputedEndIndex = Math.max(0, endIndex - peak - skipped);

		final Bitmap filteredRecordIdBitmap = input.compute();
		final int maxLength = Math.max(0, Math.min(recomputedEndIndex - recomputedStartIndex, filteredRecordIdBitmap.size() - recomputedStartIndex));
		if (recomputedEndIndex > 0 && !filteredRecordIdBitmap.isEmpty()) {
			final int[] slice = filteredRecordIdBitmap.getRange(recomputedStartIndex, recomputedStartIndex + maxLength);
			System.arraycopy(slice, 0, result, peak, Math.min(result.length - peak, slice.length));
			return peak + slice.length;
		} else {
			return 0;
		}
	}
}
