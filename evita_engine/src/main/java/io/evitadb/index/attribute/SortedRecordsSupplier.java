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

package io.evitadb.index.attribute;

import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.index.bitmap.Bitmap;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * Presorted array supplier. Allows really quickly provide information about record id at certain "presorted" position
 * and relatively quickly (much faster than binary search O(log n)) compute position of record with passed id.
 */
@RequiredArgsConstructor
public class SortedRecordsSupplier implements SortedRecordsProvider, Serializable {
	@Serial private static final long serialVersionUID = 6606884166778706442L;
	@Getter private final long transactionalId;
	@Getter @Nonnull private final int[] sortedRecordIds;
	@Getter @Nonnull private final int[] recordPositions;
	@Getter @Nonnull private final Bitmap allRecords;

	@Override
	public int getRecordCount() {
		return sortedRecordIds.length;
	}

}
