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

package io.evitadb.core.query.sort.utils;

import io.evitadb.utils.ArrayUtils;
import org.roaringbitmap.BatchIterator;
import org.roaringbitmap.ImmutableBitmapDataProvider;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.function.IntConsumer;

/**
 * Class contains utility methods shared across multiple sorter implementations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class SortUtils {

	/**
	 * Method returns the array of sorted entities capped at the peak index.
	 *
	 * @param sortedEntities     sorted entities
	 * @param sortedEntitiesPeak peak index
	 * @return sorted entities capped at the peak index
	 */
	public static int[] asResult(int[] sortedEntities, int sortedEntitiesPeak) {
		if (sortedEntitiesPeak == 0) {
			return ArrayUtils.EMPTY_INT_ARRAY;
		}
		return sortedEntities.length == sortedEntitiesPeak ?
			sortedEntities : Arrays.copyOfRange(sortedEntities, 0, sortedEntitiesPeak);
	}

	/**
	 * Method copies part of `roaringBitmap` in the `result` starting from `resultPeak` index (inclusive). Copied part
	 * of the `rest` starts on `startIndex` (inclusive) and ends on `endIndex` (exclusive)
	 *
	 * @return number of records copies (usually `endIndex` - `startIndex`, if there is enough space left in result array)
	 */
	public static int appendNotFoundResult(
		@Nonnull int[] result, int resultPeak, int startIndex, int endIndex,
		@Nonnull int[] rest,
		@Nullable IntConsumer skippedRecordsConsumer
	) {
		final int length = Math.min(Math.min(endIndex - startIndex, rest.length - startIndex), result.length - resultPeak);
		final int safeStart = Math.min(startIndex, rest.length - 1);
		if (safeStart > 0 && skippedRecordsConsumer != null) {
			for (int i = 0; i < safeStart; i++) {
				skippedRecordsConsumer.accept(rest[i]);
			}
		}
		System.arraycopy(rest, safeStart, result, resultPeak, length);
		return resultPeak + length;
	}

	/**
	 * Method copies part of `roaringBitmap` in the `result` starting from `resultPeak` index (inclusive). Copied part
	 * of the `roaringBitmap` starts on `startIndex` (inclusive) and ends on `endIndex` (exclusive)
	 *
	 * @return number of records copied (usually `endIndex` - `startIndex`, if there is enough space left in result array)
	 */
	public static int appendNotFoundResult(
		@Nonnull int[] result, int resultPeak, int startIndex, int endIndex,
		@Nonnull ImmutableBitmapDataProvider roaringBitmap,
		@Nonnull int[] buffer,
		@Nullable IntConsumer skippedRecordsConsumer
	) {
		final BatchIterator unsortedRecordIdsIt = roaringBitmap.getBatchIterator();
		int readAcc = 0;
		int actualPeak = resultPeak;
		while (unsortedRecordIdsIt.hasNext()) {
			final int read = unsortedRecordIdsIt.nextBatch(buffer);
			final int prevReadAcc = readAcc;
			readAcc += read;
			if (skippedRecordsConsumer != null) {
				for (int i = 0; i < Math.min(read, startIndex); i++) {
					skippedRecordsConsumer.accept(buffer[i]);
				}
			}
			if (readAcc > startIndex) {
				final int si = startIndex > prevReadAcc && startIndex - prevReadAcc <= read ? startIndex - prevReadAcc : 0;
				for (int i = si; i < read && actualPeak < result.length; i++) {
					result[actualPeak++] = buffer[i];
				}
				if (actualPeak >= result.length || readAcc >= endIndex) {
					break;
				}
			}
		}
		return actualPeak;
	}

	private SortUtils() {
	}
}
