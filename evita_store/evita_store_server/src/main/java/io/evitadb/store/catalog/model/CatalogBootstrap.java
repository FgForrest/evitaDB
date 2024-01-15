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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.store.catalog.model;

import io.evitadb.store.model.FileLocation;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.spi.model.CatalogHeader;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;

/**
 * evitaDB catalog bootstrap contains information of the bootstrap record for {@link CatalogHeader} that in return
 * contains pointer to
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public record CatalogBootstrap(
	long catalogVersion,
	int catalogFileIndex,
	@Nonnull OffsetDateTime offsetDateTime,
	@Nullable FileLocation fileLocation
) {

	/**
	 * Consists of:
	 *
	 * - 8B catalog version
	 * - 4B catalog file index
	 * - 8B timestamp as System.currentTimeMillis()
	 * - 8B start position of the catalog header
	 * - 4B size of the catalog header
	 */
	private static final int RECORD_SIZE = 8 + 4 + 8 + 8 + 4;

	/**
	 * Size of the bootstrap record including the {@link StorageRecord} overhead.
	 */
	public static final int BOOTSTRAP_RECORD_SIZE = StorageRecord.OVERHEAD_SIZE + RECORD_SIZE;

	/**
	 * Returns last meaningful position in the file. It is the last position that can be used to read the record
	 * without risk of reading incomplete record.
	 *
	 * @param fileLength length of the file
	 * @return last meaningful position
	 */
	public static long getLastMeaningfulPosition(long fileLength) {
		// removes non-divisible remainder as it might be incomplete record and returns last meaningful position
		return fileLength - (fileLength % BOOTSTRAP_RECORD_SIZE) - BOOTSTRAP_RECORD_SIZE;
	}

}
