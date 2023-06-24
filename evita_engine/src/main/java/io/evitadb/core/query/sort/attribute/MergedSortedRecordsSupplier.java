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

package io.evitadb.core.query.sort.attribute;

import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.ArrayUtils;
import lombok.Getter;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import java.util.Arrays;

/**
 * Implementation of the {@link SortedRecordsProvider} that merges multiple instances into a one discarding
 * the duplicate record primary keys. This operation is quite costly for large data sets and should be cached.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
class MergedSortedRecordsSupplier implements SortedRecordsProvider {
	@Getter private final RoaringBitmapBackedBitmap allRecords;
	@Getter private final int[] sortedRecordIds;
	@Getter private final int[] recordPositions;

	MergedSortedRecordsSupplier(@Nonnull SortedRecordsProvider[] sortedRecordsProviders) {
		final int expectedMaxLength = Arrays.stream(sortedRecordsProviders)
			.map(SortedRecordsProvider::getAllRecords)
			.mapToInt(Bitmap::size).sum();
		final RoaringBitmap mergedAllRecords = new RoaringBitmap();
		final int[] mergedSortedRecordIds = new int[expectedMaxLength];
		final int[] mergedRecordPositions = new int[expectedMaxLength];
		int writePeak = -1;

		for (final SortedRecordsProvider sortedRecordsProvider : sortedRecordsProviders) {
			final int[] instanceSortedRecordIds = sortedRecordsProvider.getSortedRecordIds();
			for (int instanceSortedRecordId : instanceSortedRecordIds) {
				if (mergedAllRecords.checkedAdd(instanceSortedRecordId)) {
					writePeak++;
					mergedSortedRecordIds[writePeak] = instanceSortedRecordId;
					mergedRecordPositions[writePeak] = writePeak;
				}
			}
		}
		this.allRecords = new BaseBitmap(mergedAllRecords);
		this.sortedRecordIds = Arrays.copyOfRange(mergedSortedRecordIds, 0, writePeak + 1);
		this.recordPositions = Arrays.copyOfRange(mergedRecordPositions, 0, writePeak + 1);
		ArrayUtils.sortSecondAlongFirstArray(
			this.sortedRecordIds,
			this.recordPositions
		);
	}

	@Override
	public int getRecordCount() {
		return allRecords.size();
	}

}
