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

package io.evitadb.core.query.sort.random;

import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.index.bitmap.Bitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Random;

/**
 * Random sorter outputs filtered results in a random order. Ordering is optimized to the requested slice only and doesn't
 * process positions after the requested slice. Randomization is done by random swapping positions that are requested to
 * be returned with records on random positions.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class RandomSorter implements Sorter {
	private static final int[] EMPTY_RESULT = new int[0];

	@Nonnull
	@Override
	public Sorter andThen(Sorter sorterForUnknownRecords) {
		throw new UnsupportedOperationException("Random sorter cannot be chained!");
	}

	@Nullable
	@Override
	public Sorter getNextSorter() {
		return null;
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
			final Random random = queryContext.getRandom();
			for (int i = 0; i < length; i++) {
				final int tmp = entireResult[startIndex + i];
				final int swapPosition = random.nextInt(entireResult.length);
				entireResult[startIndex + i] = entireResult[swapPosition];
				entireResult[swapPosition] = tmp;
			}
			return Arrays.copyOf(entireResult, length);
		}
	}

}
