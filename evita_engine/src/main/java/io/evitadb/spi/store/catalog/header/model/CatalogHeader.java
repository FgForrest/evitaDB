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

package io.evitadb.spi.store.catalog.header.model;

import io.evitadb.api.CatalogState;
import io.evitadb.api.exception.CollectionNotFoundException;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.spi.store.catalog.persistence.PersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePartKey;
import io.evitadb.spi.store.catalog.shared.model.LogRecordReference;

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
 * @param collectionFileIndex            contains the mapping of entity type to {@link CollectionReference} that
 *                                       contains the information about the entity collection file
 * @param compressedKeys                 contains mapping of certain parts of {@link StoragePartKey} to an integer id
 *                                       that is used for compress of the original storage key
 * @param catalogId                      contains the unique identifier of the catalog that doesn't change with catalog rename
 * @param catalogName                    contains name of the catalog that originates in {@link CatalogSchema#getName()}
 * @param catalogState                   contains the state of the catalog that originates in {@link Catalog#getCatalogState()}
 * @param lastEntityCollectionPrimaryKey contains the last assigned {@link EntityCollection#getEntityTypePrimaryKey()}
 * @param activeRecordShare              ratio of active (non-overwritten, non-deleted) bytes to total file size
 *                                       in the catalog data file, in the range `[0.0, 1.0]`. A value of `1.0`
 *                                       means every byte in the file is live data; lower values indicate how
 *                                       much space can be reclaimed by a compaction (vacuum) pass. This value is
 *                                       used by the storage layer to decide whether compaction should be triggered.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public record CatalogHeader<S extends LogRecordReference, T extends CollectionReference>(
	int storageProtocolVersion,
	long version,
	@Nullable S walFileReference,
	@Nonnull Map<String, T> collectionFileIndex,
	@Nonnull Map<Integer, Object> compressedKeys,
	@Nonnull UUID catalogId,
	@Nonnull String catalogName,
	@Nonnull CatalogState catalogState,
	int lastEntityCollectionPrimaryKey,
	double activeRecordShare
) implements StoragePart {
	@Serial private static final long serialVersionUID = 7238461925034817563L;

	/**
	 * Convenience constructor for a brand-new, empty catalog that has never been persisted.
	 *
	 * All counters are initialised to zero, the catalog state is set to {@link CatalogState#WARMING_UP}, the
	 * `activeRecordShare` defaults to `1.0` (file is clean), and no WAL reference or collection entries are
	 * populated. The `storageProtocolVersion` is taken from {@link PersistenceService#STORAGE_PROTOCOL_VERSION} so
	 * that the header is immediately compatible with the current storage format.
	 *
	 * @param catalogId   stable unique identifier assigned at catalog creation time; must not be `null`
	 * @param catalogName human-readable catalog name matching {@link CatalogSchema#getName()}; must not be `null`
	 */
	public CatalogHeader(@Nonnull UUID catalogId, @Nonnull String catalogName) {
		this(
			PersistenceService.STORAGE_PROTOCOL_VERSION,
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

	/**
	 * Returns the fixed storage-part primary key for the catalog header.
	 *
	 * There is always exactly one `CatalogHeader` per catalog data file, so its PK is hardcoded to `1L`. The
	 * uniqueness constraint of {@link StoragePart} is satisfied because no other `StoragePart` type of this class
	 * can exist in the same file.
	 *
	 * @return always `1L`
	 */
	@Nonnull
	@Override
	public Long getStoragePartPK() {
		return 1L;
	}

	/**
	 * Assigns and returns the fixed unique part identifier for the catalog header.
	 *
	 * Because the catalog header is a singleton within its data file, no key compression is needed and the method
	 * simply returns `1L` without consulting the provided `keyCompressor`. The `keyCompressor` parameter is accepted
	 * only to satisfy the {@link StoragePart} contract.
	 *
	 * @param keyCompressor not used; present only to satisfy the {@link StoragePart} contract
	 * @return always `1L`
	 */
	@Override
	public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		return 1L;
	}

	/**
	 * Returns list of all known entity types registered in this header.
	 */
	@Nonnull
	public Collection<T> getEntityTypeFileIndexes() {
		return this.collectionFileIndex.values();
	}

	/**
	 * Returns {@link EntityCollection} file {@link CollectionReference} for specified `entityType` if it's known
	 * to this header.
	 *
	 * @throws CollectionNotFoundException if `entityType` is not known to this header
	 */
	@Nonnull
	public Optional<T> getEntityTypeFileIndexIfExists(@Nonnull String entityType) throws CollectionNotFoundException {
		return ofNullable(this.collectionFileIndex.get(entityType));
	}

	/**
	 * Returns true if catalog supports transaction.
	 */
	public boolean supportsTransaction() {
		return this.catalogState == CatalogState.ALIVE;
	}

}
