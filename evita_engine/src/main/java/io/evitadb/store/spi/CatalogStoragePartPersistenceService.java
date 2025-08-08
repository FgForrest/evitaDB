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

package io.evitadb.store.spi;

import io.evitadb.api.CatalogState;
import io.evitadb.core.Catalog;
import io.evitadb.store.spi.model.CatalogHeader;
import io.evitadb.store.spi.model.reference.CollectionFileReference;
import io.evitadb.store.spi.model.reference.LogFileRecordReference;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * A sub-interface of StoragePartPersistenceService that represents the persistence service extension for catalog.
 * This interface provides methods for reading and writing catalog headers.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface CatalogStoragePartPersistenceService extends StoragePartPersistenceService {

	/**
	 * Retrieves the catalog header, which contains crucial information to read data from a single data storage file.
	 * The catalog header is represented by the {@link CatalogHeader} class and maps the data maintained
	 * by the {@link Catalog} object.
	 *
	 * @return The catalog header object.
	 */
	@Nonnull
	CatalogHeader getCatalogHeader(long catalogVersion);

	/**
	 * Writes the catalog header with the specified information to the catalog.
	 *
	 * @param storageProtocolVersion         The storage protocol version.
	 * @param catalogVersion                 The catalog version.
	 * @param catalogStoragePath             The catalog storage path, must not be null.
	 * @param walFileLocation                The WAL file location, may be null.
	 * @param collectionFileReferenceIndex   The collection file reference index, must not be null.
	 * @param catalogId                      The catalog ID, which doesn't change with the catalog rename.
	 * @param catalogName                    The catalog name, must not be null.
	 * @param catalogState                   The catalog state, must not be null.
	 * @param lastEntityCollectionPrimaryKey The last entity collection primary key.
	 */
	void writeCatalogHeader(
		int storageProtocolVersion,
		long catalogVersion,
		@Nonnull Path catalogStoragePath,
		@Nullable LogFileRecordReference walFileLocation,
		@Nonnull Map<String, CollectionFileReference> collectionFileReferenceIndex,
		@Nonnull UUID catalogId,
		@Nonnull String catalogName,
		@Nonnull CatalogState catalogState,
		int lastEntityCollectionPrimaryKey
	);

}
