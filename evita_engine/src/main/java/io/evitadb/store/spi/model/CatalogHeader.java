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

package io.evitadb.store.spi.model;

import io.evitadb.api.CatalogState;
import io.evitadb.api.exception.CollectionNotFoundException;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.core.Catalog;
import io.evitadb.core.EntityCollection;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.store.spi.CatalogPersistenceService;
import io.evitadb.store.spi.model.reference.CollectionFileReference;
import io.evitadb.store.spi.model.reference.LogFileRecordReference;
import io.evitadb.store.spi.model.storageParts.StoragePartKey;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static java.util.Optional.ofNullable;

/**
 * Catalog header contains all information necessary to fully restore catalog state and indexes in memory from
 * persistent storage. It contains leading information to all entity collections and their indexes in
 * {@link #collectionFileIndex} and also information about compressed keys in {@link #compressedKeys} necessary for
 * correct deserialization.
 *
 * @param storageProtocolVersion         contains the version of the storage protocol that is incremented with each
 *                                       backward incompatible change
 * @param version                        contains the version of the catalog that is incremented with transaction
 * @param walFileReference               contains the reference to the current WAL file related to this catalog and last
 *                                       processed transaction
 * @param collectionFileIndex            contains the mapping of entity type to {@link CollectionFileReference} that
 *                                       contains the information about the entity collection file
 * @param compressedKeys                 contains mapping of certain parts of {@link StoragePartKey} to an integer id
 *                                       that is used for compress of the original storage key
 * @param catalogId                      contains the unique identifier of the catalog that doesn't change with catalog rename
 * @param catalogName                    contains name of the catalog that originates in {@link CatalogSchema#getName()}
 * @param catalogState                   contains the state of the catalog that originates in {@link Catalog#getCatalogState()}
 * @param lastEntityCollectionPrimaryKey contains the last assigned {@link EntityCollection#getEntityTypePrimaryKey()}
 * @param activeRecordShare              contains the share of active records in the catalog that is used for
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 * @see PersistentStorageHeader
 */
public record CatalogHeader(
	int storageProtocolVersion,
	long version,
	@Nullable LogFileRecordReference walFileReference,
	@Nonnull Map<String, CollectionFileReference> collectionFileIndex,
	@Nonnull Map<Integer, Object> compressedKeys,
	@Nonnull UUID catalogId,
	@Nonnull String catalogName,
	@Nonnull CatalogState catalogState,
	int lastEntityCollectionPrimaryKey,
	double activeRecordShare
) implements StoragePart {
	@Serial private static final long serialVersionUID = 4115945765677481853L;

	public CatalogHeader(@Nonnull UUID catalogId, @Nonnull String catalogName) {
		this(
			CatalogPersistenceService.STORAGE_PROTOCOL_VERSION,
			0L,
			null,
			Map.of(),
			Map.of(),
			catalogId,
			catalogName,
			CatalogState.WARMING_UP,
			0,
			1.0
		);
	}

	@Nonnull
	@Override
	public Long getStoragePartPK() {
		return 1L;
	}

	@Override
	public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		return 1L;
	}

	/**
	 * Returns list of all known entity types registered in this header.
	 */
	@Nonnull
	public Collection<CollectionFileReference> getEntityTypeFileIndexes() {
		return this.collectionFileIndex.values();
	}

	/**
	 * Returns {@link EntityCollection} file {@link CollectionFileReference} for specified `entityType` if it's known
	 * to this header.
	 *
	 * @throws CollectionNotFoundException if `entityType` is not known to this header
	 */
	@Nonnull
	public Optional<CollectionFileReference> getEntityTypeFileIndexIfExists(@Nonnull String entityType) throws CollectionNotFoundException {
		return ofNullable(this.collectionFileIndex.get(entityType));
	}

	/**
	 * Returns true if catalog supports transaction.
	 */
	public boolean supportsTransaction() {
		return this.catalogState == CatalogState.ALIVE;
	}

}
