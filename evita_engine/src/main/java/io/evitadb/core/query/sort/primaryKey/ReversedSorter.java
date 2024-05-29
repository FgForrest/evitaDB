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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
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
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.ArrayUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Exact sorter outputs filtered results in reversed order of the primary keys.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ReversedSorter implements Sorter {
	public static final ReversedSorter INSTANCE = new ReversedSorter();

	private ReversedSorter() {
	}

	@Nonnull
	@Override
	public Sorter andThen(Sorter sorterForUnknownRecords) {
		// this sorter will always sort all records
		return ReversedSorter.INSTANCE;
	}

	@Nonnull
	@Override
	public Sorter cloneInstance() {
		return ReversedSorter.INSTANCE;
	}

	@Nullable
	@Override
	public Sorter getNextSorter() {
		return null;
	}

	@Override
	public int sortAndSlice(@Nonnull QueryContext queryContext, @Nonnull Formula input, int startIndex, int endIndex, @Nonnull int[] result, int peak) {
		final Bitmap filteredRecordIdBitmap = input.compute();
		if (filteredRecordIdBitmap.isEmpty()) {
			return 0;
		} else {
			final int[] filteredRecordIds = filteredRecordIdBitmap.getArray();
			ArrayUtils.reverseInPlace(filteredRecordIds);

			// copy the sorted data to result
			final int length = endIndex - startIndex;
			final int newPeak = Math.min(filteredRecordIds.length, length);
			System.arraycopy(filteredRecordIds, startIndex, result, peak, newPeak);
			return newPeak;
		}
	}

}
