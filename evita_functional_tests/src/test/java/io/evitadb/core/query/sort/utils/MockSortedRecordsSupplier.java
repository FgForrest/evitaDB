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

package io.evitadb.core.query.sort.utils;

import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedComparableForwardSeeker;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.ArrayUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * Mock implementation of {@link SortedRecordsProvider} for testing purposes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class MockSortedRecordsSupplier implements SortedRecordsProvider {
	@Getter private final RoaringBitmapBackedBitmap allRecords;
	@Getter private final int[] sortedRecordIds;
	@Getter private final int[] recordPositions;

	public MockSortedRecordsSupplier(int... sortedRecordIds) {
		this.sortedRecordIds = sortedRecordIds;
		this.allRecords = new BaseBitmap(sortedRecordIds);
		this.recordPositions = new int[sortedRecordIds.length];
		for (int i = 0; i < sortedRecordIds.length; i++) {
			this.recordPositions[i] = i;
		}
		ArrayUtils.sortSecondAlongFirstArray(this.sortedRecordIds, this.recordPositions);
	}

	@Override
	public int getRecordCount() {
		return this.sortedRecordIds.length;
	}

	@Nonnull
	@Override
	public SortedComparableForwardSeeker getSortedComparableForwardSeeker() {
		return new SortedComparableForwardSeeker() {
			@Nonnull
			@Override
			public Serializable getValueToCompareOn(int position) throws ArrayIndexOutOfBoundsException {
				return MockSortedRecordsSupplier.this.recordPositions[position];
			}
		};
	}

}
