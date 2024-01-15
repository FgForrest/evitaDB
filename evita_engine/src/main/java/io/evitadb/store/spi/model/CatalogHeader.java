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
import io.evitadb.store.spi.model.reference.WalFileReference;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import static java.util.Optional.ofNullable;

/**
 * Catalog header contains crucial information to read data from a single data storage file. The catalog header needs
 * to be stored in {@link CatalogHeader} and maps the data maintained by {@link Catalog} object.
 *
 * TODO JNO - UPDATE DOCUMENTATION
 *
 * @param catalogName contains name of the catalog that originates in {@link CatalogSchema#getName()}
 * @param lastEntityCollectionPrimaryKey contains the last assigned {@link EntityCollection#getEntityTypePrimaryKey()}
 *
 * @see PersistentStorageHeader
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public record CatalogHeader(
	int storageProtocolVersion,
	long version,
	/* TODO JNO - tady bude odkaz do WAL logu, na místo poslední kompletně zpracované transakce */
	@Nullable WalFileReference walFileReference,
	@Nonnull Map<String, CollectionFileReference> collectionFileIndex,
	@Nonnull Map<Integer, Object> compressedKeys,
	@Nonnull String catalogName,
	@Nonnull CatalogState catalogState,
	int lastEntityCollectionPrimaryKey
) implements StoragePart {
	@Serial private static final long serialVersionUID = -3595987669559870397L;

	public CatalogHeader(@Nonnull String catalogName) {
		this(
			CatalogPersistenceService.STORAGE_PROTOCOL_VERSION,
			0L,
			null,
			Map.of(),
			Map.of(),
			catalogName,
			CatalogState.WARMING_UP,
			0
		);
	}

	@Nullable
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
	public Collection<CollectionFileReference> getEntityTypeFileIndexes() {
		return collectionFileIndex.values();
	}

	/**
	 * Returns {@link EntityCollection} file {@link CollectionFileReference} for specified `entityType` if it's known to this header.
	 */
	@Nonnull
	public Optional<CollectionFileReference> getEntityTypeFileIndexIfExists(@Nonnull String entityType) throws CollectionNotFoundException {
		return ofNullable(collectionFileIndex.get(entityType));
	}

	/**
	 * Returns {@link EntityCollection} file {@link CollectionFileReference} for specified `entityType` if it's known to this header.
	 *
	 * @throws CollectionNotFoundException if the `entityType` is not known to this header.
	 */
	@Nonnull
	public CollectionFileReference getEntityTypeFileIndex(@Nonnull String entityType) throws CollectionNotFoundException {
		return getEntityTypeFileIndexIfExists(entityType)
			.orElseThrow(() -> new CollectionNotFoundException(entityType));
	}

	/**
	 * Returns {@link EntityCollection} file path for specified `entityType` if it's known to this header.
	 *
	 * @throws CollectionNotFoundException if the `entityType` is not known to this header.
	 */
	@Nonnull
	public Path getEntityTypeFilePath(@Nonnull String entityType, @Nonnull Path catalogFolder) throws CollectionNotFoundException {
		return getEntityTypeFileIndex(entityType).toFilePath(catalogFolder);
	}

	/**
	 * Returns true if catalog supports transactions.
	 */
	public boolean supportsTransaction() {
		return catalogState == CatalogState.ALIVE;
	}

}
