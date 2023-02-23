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

package io.evitadb.core.query.sort.utils;

import org.roaringbitmap.BatchIterator;
import org.roaringbitmap.ImmutableBitmapDataProvider;

/**
 * Class contains utility methods shared across multiple sorter implementations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class SortUtils {

	private SortUtils() {
	}

	/**
	 * Method copies part of `roaringBitmap` in the `result` starting from `resultPeak` index (inclusive). Copied part
	 * of the `rest` starts on `startIndex` (inclusive) and ends on `endIndex` (exclusive)
	 *
	 * @return number of records copies (usually `endIndex` - `startIndex`, if there is enough space left in result array)
	 */
	public static int appendNotFoundResult(int[] result, int resultPeak, int startIndex, int endIndex, int[] rest) {
		final int length = Math.min(Math.min(endIndex - startIndex, rest.length - startIndex), result.length - resultPeak);
		final int safeStart = Math.min(startIndex, rest.length - 1);
		System.arraycopy(rest, safeStart, result, resultPeak, length);
		return resultPeak + length;
	}

	/**
	 * Method copies part of `roaringBitmap` in the `result` starting from `resultPeak` index (inclusive). Copied part
	 * of the `roaringBitmap` starts on `startIndex` (inclusive) and ends on `endIndex` (exclusive)
	 *
	 * @return number of records copied (usually `endIndex` - `startIndex`, if there is enough space left in result array)
	 */
	public static int appendNotFoundResult(int[] result, int resultPeak, int startIndex, int endIndex, ImmutableBitmapDataProvider roaringBitmap) {
		final int[] buffer = new int[512];

		final BatchIterator unsortedRecordIdsIt = roaringBitmap.getBatchIterator();
		int readAcc = 0;
		int actualPeak = resultPeak;
		while (unsortedRecordIdsIt.hasNext()) {
			final int read = unsortedRecordIdsIt.nextBatch(buffer);
			final int prevReadAcc = readAcc;
			readAcc += read;
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
}
