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
import io.evitadb.core.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerCreator;
import io.evitadb.index.Index;
import io.evitadb.index.IndexKey;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.store.spi.StoragePartPersistenceService;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.BiFunction;
import java.util.function.Function;

import static io.evitadb.core.Transaction.getTransactionalMemoryLayerIfExists;

/**
 * TransactionalDataStoreMemoryBuffer represents volatile temporal memory between the {@link EntityCollection} and persistent
 * storage that takes {@link io.evitadb.core.Transaction} into an account. Even if transactional memory is not available
 * this buffer traps updates of certain objects in {@link DataStoreIndexMemoryBuffer} to avoid persistence of large
 * indexes with each update (which would drastically slow initial bulk database setup).
 *
 * All reads-writes are primarily targeting transactional memory if it's present for the current thread. If the value
 * is not found there it's located via {@link StoragePartPersistenceService#getStoragePart(long, long, Class)}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class TransactionalDataStoreMemoryBuffer<IK extends IndexKey, I extends Index<IK>, DSC extends DataStoreChanges<IK, I>> implements DataStoreMemoryBuffer<IK, I> {
	/**
	 * Contains reference to the entity collection this buffer refers to.
	 */
	@Nonnull private final TransactionalLayerCreator<DSC> transactionalMemoryDataSource;
	/**
	 * DTO contains all trapped changes in this memory buffer.
	 */
	@Nonnull private final DataStoreIndexChanges<IK, I> dataStoreIndexChanges = new DataStoreIndexMemoryBuffer<>();
	/**
	 * Contains reference to the I/O service, that allows reading/writing records to the persistent storage.
	 */
	@Nonnull private final StoragePartPersistenceService persistenceService;

	public TransactionalDataStoreMemoryBuffer(
		@Nonnull TransactionalLayerCreator<DSC> transactionalMemoryDataSource,
		@Nonnull StoragePartPersistenceService persistenceService
	) {
		this.transactionalMemoryDataSource = transactionalMemoryDataSource;
		this.persistenceService = persistenceService;
	}

	@Override
	public I getOrCreateIndexForModification(@Nonnull IK entityIndexKey, @Nonnull Function<IK, I> accessorWhenMissing) {
		final DataStoreChanges<IK, I> layer = Transaction.getOrCreateTransactionalMemoryLayer(transactionalMemoryDataSource);
		if (layer == null) {
			return dataStoreIndexChanges.getOrCreateIndexForModification(entityIndexKey, accessorWhenMissing);
		} else {
			return layer.getOrCreateIndexForModification(entityIndexKey, accessorWhenMissing);
		}
	}

	@Override
	public I getIndexIfExists(@Nonnull IK entityIndexKey, @Nonnull Function<IK, I> accessorWhenMissing) {
		final DataStoreChanges<IK, I> layer = getTransactionalMemoryLayerIfExists(transactionalMemoryDataSource);
		if (layer == null) {
			return dataStoreIndexChanges.getIndexIfExists(entityIndexKey, accessorWhenMissing);
		} else {
			return layer.getIndexIfExists(entityIndexKey, accessorWhenMissing);
		}
	}

	@Override
	public I removeIndex(@Nonnull IK entityIndexKey, @Nonnull Function<IK, I> removalPropagation) {
		final DataStoreChanges<IK, I> layer = getTransactionalMemoryLayerIfExists(transactionalMemoryDataSource);
		if (layer == null) {
			return dataStoreIndexChanges.removeIndex(entityIndexKey, removalPropagation);
		} else {
			return layer.removeIndex(entityIndexKey, removalPropagation);
		}
	}

	@Override
	public int countStorageParts(long catalogVersion, @Nonnull Class<? extends StoragePart> containerType) {
		final DataStoreChanges<IK, I> layer = getTransactionalMemoryLayerIfExists(transactionalMemoryDataSource);
		if (layer == null) {
			return persistenceService.countStorageParts(catalogVersion, containerType);
		} else {
			return layer.countStorageParts(catalogVersion, containerType);
		}
	}

	@Override
	@Nullable
	public <T extends StoragePart> T fetch(long catalogVersion, long primaryKey, @Nonnull Class<T> containerType) {
		final DataStoreChanges<IK, I> layer = getTransactionalMemoryLayerIfExists(transactionalMemoryDataSource);
		if (layer == null) {
			return persistenceService.getStoragePart(catalogVersion, primaryKey, containerType);
		} else {
			return layer.getStoragePart(catalogVersion, primaryKey, containerType);
		}
	}

	@Override
	@Nullable
	public <T extends StoragePart> byte[] fetchBinary(long catalogVersion, long primaryKey, @Nonnull Class<T> containerType) {
		final DataStoreChanges<IK, I> layer = getTransactionalMemoryLayerIfExists(transactionalMemoryDataSource);
		if (layer == null) {
			return persistenceService.getStoragePartAsBinary(catalogVersion, primaryKey, containerType);
		} else {
			return layer.getStoragePartAsBinary(catalogVersion, primaryKey, containerType);
		}
	}

	@Override
	@Nullable
	public <T extends StoragePart, U extends Comparable<U>> T fetch(long catalogVersion, @Nonnull U originalKey, @Nonnull Class<T> containerType, @Nonnull BiFunction<KeyCompressor, U, OptionalLong> compressedKeyComputer) {
		final DataStoreChanges<IK, I> layer = getTransactionalMemoryLayerIfExists(this.transactionalMemoryDataSource);
		final OptionalLong storagePartId = compressedKeyComputer.apply(
			layer == null ? this.persistenceService.getReadOnlyKeyCompressor() : layer.getReadOnlyKeyCompressor(),
			originalKey
		);
		if (storagePartId.isEmpty()) {
			// key wasn't yet assigned
			return null;
		} else {
			return layer == null ?
				this.persistenceService.getStoragePart(catalogVersion, storagePartId.getAsLong(), containerType) :
				layer.getStoragePart(catalogVersion, storagePartId.getAsLong(), containerType);
		}
	}

	@Override
	@Nullable
	public <T extends StoragePart, U extends Comparable<U>> byte[] fetchBinary(long catalogVersion, @Nonnull U originalKey, @Nonnull Class<T> containerType, @Nonnull BiFunction<KeyCompressor, U, OptionalLong> compressedKeyComputer) {
		final DataStoreChanges<IK, I> layer = getTransactionalMemoryLayerIfExists(transactionalMemoryDataSource);
		final OptionalLong storagePartId = compressedKeyComputer.apply(
			layer == null ? this.persistenceService.getReadOnlyKeyCompressor() : layer.getReadOnlyKeyCompressor(),
			originalKey
		);
		if (storagePartId.isEmpty()) {
			// key wasn't yet assigned
			return null;
		} else {
			return layer == null ?
				this.persistenceService.getStoragePartAsBinary(catalogVersion, storagePartId.getAsLong(), containerType) :
				layer.getStoragePartAsBinary(catalogVersion, storagePartId.getAsLong(), containerType);
		}
	}

	@Override
	public <T extends StoragePart> boolean removeByPrimaryKey(long catalogVersion, long primaryKey, @Nonnull Class<T> entityClass) {
		final DataStoreChanges<IK, I> layer = Transaction.getOrCreateTransactionalMemoryLayer(transactionalMemoryDataSource);
		if (layer == null) {
			return this.persistenceService.removeStoragePart(catalogVersion, primaryKey, entityClass);
		} else {
			return layer.removeStoragePart(catalogVersion, primaryKey, entityClass);
		}
	}

	@Override
	public <T extends StoragePart> void update(long catalogVersion, @Nonnull T value) {
		final DataStoreChanges<IK, I> layer = Transaction.getOrCreateTransactionalMemoryLayer(transactionalMemoryDataSource);
		if (layer == null) {
			this.persistenceService.putStoragePart(catalogVersion, value);
		} else {
			layer.putStoragePart(catalogVersion, value);
		}
	}

	@Override
	public DataStoreIndexChanges<IK, I> getTrappedIndexChanges() {
		final DataStoreChanges<IK, I> layer = Transaction.getTransactionalMemoryLayerIfExists(transactionalMemoryDataSource);
		// return current transactional layer that contains trapped updates
		// or fallback to shared memory buffer with trapped updates
		return Objects.requireNonNullElse(layer, this.dataStoreIndexChanges);
	}

}
