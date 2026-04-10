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

package io.evitadb.spi.store.catalog.persistence;

import io.evitadb.api.CatalogState;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.spi.store.catalog.header.model.CatalogHeader;
import io.evitadb.spi.store.catalog.header.model.CollectionReference;
import io.evitadb.spi.store.catalog.shared.model.LogRecordReference;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * Extends {@link StoragePartPersistenceService} with catalog-specific header operations. While the base interface
 * handles generic {@link io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart} read/write operations,
 * this interface adds the ability to read and write the {@link io.evitadb.spi.store.catalog.header.model.CatalogHeader}
 * — the top-level descriptor that records which entity collections exist, where the WAL file lives, and what catalog
 * state (version, primary key sequences, …) is current.
 *
 * An instance is obtained via {@link CatalogPersistenceService#getStoragePartPersistenceService(long)} and is
 * associated with exactly one catalog data file generation. When the catalog data file is rotated (compacted) a new
 * instance is created for the new file while the old instance remains readable until no readers reference it.
 *
 * @param <S> the concrete {@link LogRecordReference} type that identifies WAL file locations
 * @param <T> the concrete {@link CollectionReference} type that describes entity collection file locations
 * @param <U> the concrete {@link StorageDescriptor} type produced by the underlying offset-index implementation
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface CatalogStoragePartPersistenceService<S extends LogRecordReference, T extends CollectionReference, U extends StorageDescriptor> extends StoragePartPersistenceService<U> {

	/**
	 * Retrieves the catalog header, which contains crucial information to read data from a single data storage file.
	 * The catalog header is represented by the {@link CatalogHeader} class and maps the data maintained
	 * by the {@link Catalog} object.
	 *
	 * @return The catalog header object.
	 */
	@Nonnull
	CatalogHeader<S, T> getCatalogHeader(long catalogVersion);

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
		@Nullable S walFileLocation,
		@Nonnull Map<String, T> collectionFileReferenceIndex,
		@Nonnull UUID catalogId,
		@Nonnull String catalogName,
		@Nonnull CatalogState catalogState,
		int lastEntityCollectionPrimaryKey
	);

}
