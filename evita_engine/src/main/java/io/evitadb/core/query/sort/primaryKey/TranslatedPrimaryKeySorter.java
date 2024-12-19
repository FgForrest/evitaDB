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

import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.ArrayUtils;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.IntConsumer;

/**
 * This sorter sorts translated primary keys according to original primary keys in ascending order. It is used with
 * the special implementation in case the entity is not known in the query. In such case the primary keys are translated
 * different ids and those ids are translated back at the end of the query. Unfortunately the order of the translated
 * keys might be different than the original order of the primary keys, so we need to sort them here according to
 * their original primary keys order in ascending fashion.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class TranslatedPrimaryKeySorter implements Sorter {
	public static final Sorter INSTANCE = new TranslatedPrimaryKeySorter();

	@Nonnull
	@Override
	public Sorter cloneInstance() {
		return INSTANCE;
	}

	@Nonnull
	@Override
	public Sorter andThen(Sorter sorterForUnknownRecords) {
		return INSTANCE;
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
		@Nullable IntConsumer skippedRecordsConsumer
	) {
		final Bitmap translatedPrimaryKeysBitmap = input.compute();
		final int[] translatedPrimaryKeys = translatedPrimaryKeysBitmap.getArray();
		final int[] originalPrimaryKeys = translatedPrimaryKeysBitmap.stream().map(queryContext::translateToEntityPrimaryKey).toArray();
		// initialize order array
		final int[] order = new int[originalPrimaryKeys.length];
		for (int i = 0; i < order.length; i++) {
			order[i] = i;
		}

		ArrayUtils.sortSecondAlongFirstArray(originalPrimaryKeys, order);
		final int length = endIndex - startIndex;
		for (int i = peak; i < translatedPrimaryKeys.length && i < length; i++) {
			result[i] = translatedPrimaryKeys[order[i]];
		}
		return peak + Math.min(translatedPrimaryKeys.length, length);
	}

}
