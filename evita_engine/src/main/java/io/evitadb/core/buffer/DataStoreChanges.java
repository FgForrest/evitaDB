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

package io.evitadb.core.buffer;

import io.evitadb.index.Index;
import io.evitadb.index.IndexKey;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.store.spi.StoragePartPersistenceService;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * This class is used as transactional memory of the {@link DataStoreChanges} and stores the changes of the storage
 * keys directly to the target {@link StoragePartPersistenceService}, but traps the changes in the indexes in the memory
 * buffer.
 *
 * @see DataStoreIndexMemoryBuffer
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
public class DataStoreChanges<IK extends IndexKey, I extends Index<IK>> extends DataStoreIndexMemoryBuffer<IK, I> {
	/**
	 * Contains reference to the I/O service, that allows reading/writing records to the persistent storage.
	 */
	@Nonnull private final StoragePartPersistenceService persistenceService;

	public DataStoreChanges(@Nonnull StoragePartPersistenceService persistenceService) {
		this.persistenceService = persistenceService;
	}

	@Nonnull
	public KeyCompressor getReadOnlyKeyCompressor() {
		return this.persistenceService.getReadOnlyKeyCompressor();
	}

	@Nullable
	public <T extends StoragePart> T getStoragePart(long primaryKey, @Nonnull Class<T> containerType) {
		return this.persistenceService.getStoragePart(primaryKey, containerType);
	}

	@Nullable
	public <T extends StoragePart> byte[] getStoragePartAsBinary(long primaryKey, @Nonnull Class<T> containerType) {
		return this.persistenceService.getStoragePartAsBinary(primaryKey, containerType);
	}

	public <T extends StoragePart> boolean removeStoragePart(long primaryKey, @Nonnull Class<T> entityClass) {
		return this.persistenceService.removeStoragePart(primaryKey, entityClass);
	}

	public <T extends StoragePart> void putStoragePart(long catalogVersion, @Nonnull T value) {
		this.persistenceService.putStoragePart(catalogVersion, value);
	}
}
