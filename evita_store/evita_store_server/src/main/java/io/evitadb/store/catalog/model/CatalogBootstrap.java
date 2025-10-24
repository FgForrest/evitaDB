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

package io.evitadb.store.catalog.model;

import io.evitadb.store.model.FileLocation;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.spi.CatalogPersistenceService;
import io.evitadb.store.spi.model.CatalogHeader;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;

/**
 * evitaDB catalog bootstrap contains information of the bootstrap record for {@link CatalogHeader} that in return
 * contains pointer to entity collection headers which allows loading entire key catalog information into a memory.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public record CatalogBootstrap(
	int storageProtocolVersion,
	long catalogVersion,
	int catalogFileIndex,
	@Nonnull OffsetDateTime timestamp,
	@Nullable FileLocation fileLocation
) {

	/**
	 * Consists of:
	 *
	 * - 4B storage protocol version
	 * - 8B catalog version
	 * - 4B catalog file index
	 * - 8B timestamp as System.currentTimeMillis()
	 * - 8B start position of the catalog header
	 * - 4B size of the catalog header
	 */
	private static final int RECORD_SIZE = 4 + 8 + 4 + 8 + 8 + 4;

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
		return Math.max(0, fileLength - (fileLength % BOOTSTRAP_RECORD_SIZE) - BOOTSTRAP_RECORD_SIZE);
	}

	/**
	 * Calculates the position of a record in the file based on its index.
	 *
	 * @param index The index of the record.
	 * @return The position of the record in the file.
	 */
	public static long getPositionForRecord(int index) {
		return (long) index * BOOTSTRAP_RECORD_SIZE;
	}

	/**
	 * Calculates the number of records in a file based on its length.
	 *
	 * @param fileLength The length of the file in bytes.
	 * @return The number of records in the file.
	 */
	public static int getRecordCount(long fileLength) {
		return Math.toIntExact(fileLength / BOOTSTRAP_RECORD_SIZE);
	}

	public CatalogBootstrap(
		long catalogVersion,
		int catalogFileIndex,
		@Nonnull OffsetDateTime timestamp,
		@Nullable FileLocation fileLocation
	) {
		this(
			CatalogPersistenceService.STORAGE_PROTOCOL_VERSION,
			catalogVersion,
			catalogFileIndex,
			timestamp,
			fileLocation
		);
	}
}
