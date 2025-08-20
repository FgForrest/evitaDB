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

package io.evitadb.core.buffer;

import io.evitadb.core.EntityCollection;
import io.evitadb.index.Index;
import io.evitadb.index.IndexKey;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.store.spi.StoragePartPersistenceService;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.OptionalLong;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * WarmUpDataStoreMemoryBuffer represents volatile temporal memory between the {@link EntityCollection} and persistent
 * storage that keeps frequently changed data in the {@link DataStoreIndexMemoryBuffer} buffer and flushes them at
 * the session closing to avoid persistence of large indexes with each update (which would drastically slow initial bulk
 * database setup).
 *
 * The persistence service could be swapped in case of internal store compaction. This is behavior unique for the warm-up
 * phase. In the transactional phase, the persistence service is fixed and entire instance of the buffer is exchanged
 * on the transaction commit.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class WarmUpDataStoreMemoryBuffer implements DataStoreMemoryBuffer {
	/**
	 * DTO contains all trapped changes in this memory buffer.
	 */
	@Nonnull private final DataStoreChanges dataStoreChanges;

	public WarmUpDataStoreMemoryBuffer(
		@Nonnull StoragePartPersistenceService persistenceService
	) {
		this.dataStoreChanges = new DataStoreChanges(persistenceService);
	}

	/**
	 * Allows exchanging the persistence service for this memory buffer in case of internal store compaction.
	 *
	 * @param persistenceService new persistence service to be used
	 */
	public void setPersistenceService(@Nonnull StoragePartPersistenceService persistenceService) {
		this.dataStoreChanges.setPersistenceService(persistenceService);
	}

	@Override
	public <IK extends IndexKey, I extends Index<IK>> I getOrCreateIndexForModification(@Nonnull IK entityIndexKey, @Nonnull Function<IK, I> accessorWhenMissing) {
		return this.dataStoreChanges.getOrCreateIndexForModification(entityIndexKey, accessorWhenMissing);
	}

	@Override
	public <IK extends IndexKey, I extends Index<IK>> I getIndexIfExists(@Nonnull IK entityIndexKey, @Nonnull Function<IK, I> accessorWhenMissing) {
		return this.dataStoreChanges.getIndexIfExists(entityIndexKey, accessorWhenMissing);
	}

	@Override
	public <IK extends IndexKey, I extends Index<IK>> I removeIndex(@Nonnull IK entityIndexKey, @Nonnull Function<IK, I> removalPropagation) {
		return this.dataStoreChanges.removeIndex(entityIndexKey, removalPropagation);
	}

	@Override
	public int countStorageParts(long catalogVersion, @Nonnull Class<? extends StoragePart> containerType) {
		return this.dataStoreChanges.countStorageParts(catalogVersion, containerType);
	}

	@Override
	@Nullable
	public <T extends StoragePart> T fetch(long catalogVersion, long primaryKey, @Nonnull Class<T> containerType) {
		return this.dataStoreChanges.getStoragePart(catalogVersion, primaryKey, containerType);
	}

	@Override
	@Nullable
	public <T extends StoragePart> byte[] fetchBinary(long catalogVersion, long primaryKey, @Nonnull Class<T> containerType) {
		return this.dataStoreChanges.getStoragePartAsBinary(catalogVersion, primaryKey, containerType);
	}

	@Override
	@Nullable
	public <T extends StoragePart, U extends Comparable<U>> T fetch(long catalogVersion, @Nonnull U originalKey, @Nonnull Class<T> containerType, @Nonnull BiFunction<KeyCompressor, U, OptionalLong> compressedKeyComputer) {
		final OptionalLong storagePartId = compressedKeyComputer.apply(
			this.dataStoreChanges.getReadOnlyKeyCompressor(),
			originalKey
		);
		if (storagePartId.isEmpty()) {
			// key wasn't yet assigned
			return null;
		} else {
			return this.dataStoreChanges.getStoragePart(catalogVersion, storagePartId.getAsLong(), containerType);
		}
	}

	@Override
	@Nullable
	public <T extends StoragePart, U extends Comparable<U>> byte[] fetchBinary(long catalogVersion, @Nonnull U originalKey, @Nonnull Class<T> containerType, @Nonnull BiFunction<KeyCompressor, U, OptionalLong> compressedKeyComputer) {
		final OptionalLong storagePartId = compressedKeyComputer.apply(
			this.dataStoreChanges.getReadOnlyKeyCompressor(),
			originalKey
		);
		if (storagePartId.isEmpty()) {
			// key wasn't yet assigned
			return null;
		} else {
			return this.dataStoreChanges.getStoragePartAsBinary(catalogVersion, storagePartId.getAsLong(), containerType);
		}
	}

	@Override
	public <T extends StoragePart> boolean removeByPrimaryKey(long catalogVersion, long primaryKey, @Nonnull Class<T> entityClass) {
		return this.dataStoreChanges.removeStoragePart(catalogVersion, primaryKey, entityClass);
	}

	@Override
	public <T extends StoragePart> void update(long catalogVersion, @Nonnull T value) {
		this.dataStoreChanges.putStoragePart(catalogVersion, value);
	}

	@Override
	public <T extends StoragePart> boolean trapRemoveByPrimaryKey(long catalogVersion, long primaryKey, @Nonnull Class<T> entityClass) {
		return this.dataStoreChanges.trapRemoveStoragePart(catalogVersion, primaryKey, entityClass);
	}

	@Override
	public <T extends StoragePart> void trapUpdate(long catalogVersion, @Nonnull T value) {
		this.dataStoreChanges.trapPutStoragePart(value);
	}

	@Nonnull
	@Override
	public TrappedChanges popTrappedChanges() {
		return this.dataStoreChanges.popTrappedUpdates();
	}

}
