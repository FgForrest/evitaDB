/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

import io.evitadb.core.EntityCollection;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.Index;
import io.evitadb.index.IndexKey;
import io.evitadb.index.transactionalMemory.TransactionalLayerCreator;
import io.evitadb.index.transactionalMemory.TransactionalMemory;
import io.evitadb.index.transactionalMemory.diff.DataSourceChanges;
import io.evitadb.store.exception.CompressionKeyUnknownException;
import io.evitadb.store.model.RecordWithCompressedId;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.store.spi.CatalogPersistenceService;
import io.evitadb.store.spi.EntityCollectionPersistenceService;
import io.evitadb.store.spi.PersistenceService;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.BiFunction;
import java.util.function.Function;

import static java.util.Optional.ofNullable;

/**
 * DataStoreTxMemoryBuffer represents volatile temporal memory between the {@link EntityCollection} and persistent
 * storage that takes {@link io.evitadb.index.transactionalMemory.TransactionalMemory} into an account. Even if
 * transactional memory is not available this buffer traps updates of certain objects in {@link BufferedChangeSet} to
 * avoid persistence of large indexes with each update (which would drastically slow initial bulk database setup).
 *
 * All reads-writes are primarily targeting transactional memory if it's present for the current thread. If the value
 * is not found there it's located via {@link EntityCollectionPersistenceService#getStoragePart(long, Class)}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class DataStoreTxMemoryBuffer<IK extends IndexKey, I extends Index<IK>, DSC extends DataSourceChanges<IK, I>> {
	/**
	 * Contains reference to the entity collection this buffer refers to.
	 */
	@Nonnull private final TransactionalLayerCreator<DSC> transactionalMemoryDataSource;
	/**
	 * Contains reference to the I/O service, that allows reading/writing records to the persistent storage.
	 */
	@Nonnull private PersistenceService persistenceService;
	/**
	 * DTO contains all trapped changes in this {@link DataStoreTxMemoryBuffer}.
	 */
	@Nonnull private BufferedChangeSet<IK, I> bufferedChangeSet = new BufferedChangeSet<>();

	public DataStoreTxMemoryBuffer(@Nonnull TransactionalLayerCreator<DSC> transactionalMemoryDataSource, @Nonnull PersistenceService persistenceService) {
		this.transactionalMemoryDataSource = transactionalMemoryDataSource;
		this.persistenceService = persistenceService;
	}

	/**
	 * Method allows to refresh the I/O service.
	 */
	public void setPersistenceService(@Nonnull PersistenceService persistenceService) {
		this.persistenceService = persistenceService;
	}

	/**
	 * Collects information about dirty indexes that need to be persisted. If transaction is opened, the changes
	 * are written only in the transactional layer and persisted at moment when transaction is committed. When
	 * transaction is not opened the changes are not immediately written to the persistent storage but trapped in shared
	 * memory and will be written when buffer is {@link #exchangeBuffer() exchanged}. This is usually the case when
	 * entity index is just being created for the first time and the transactions were not yet enabled on it.
	 */
	public I getOrCreateIndexForModification(@Nonnull IK entityIndexKey, @Nonnull Function<IK, I> accessorWhenMissing) {
		final DataSourceChanges<IK, I> layer = TransactionalMemory.getTransactionalMemoryLayer(transactionalMemoryDataSource);
		if (layer == null) {
			return bufferedChangeSet.getOrCreateIndexForModification(entityIndexKey, accessorWhenMissing);
		} else {
			return layer.getOrCreateIndexForModification(entityIndexKey, accessorWhenMissing);
		}
	}

	/**
	 * Returns {@link EntityIndex} by key if it already exists in change set. If the index is no present there
	 * `accessorWhenMissing` is executed to retrieve primary read-only index from the origin collection.
	 */
	public I getIndexIfExists(@Nonnull IK entityIndexKey, @Nonnull Function<IK, I> accessorWhenMissing) {
		final DataSourceChanges<IK, I> layer = TransactionalMemory.getTransactionalMemoryLayerIfExists(transactionalMemoryDataSource);
		if (layer == null) {
			return bufferedChangeSet.getIndexIfExists(entityIndexKey, accessorWhenMissing);
		} else {
			return layer.getIndexIfExists(entityIndexKey, accessorWhenMissing);
		}
	}

	/**
	 * Removes {@link EntityIndex} from the change set. After removal (either successfully or unsuccessful)
	 * `removalPropagation` function is called to propagate deletion to the origin collection.
	 */
	public I removeIndex(@Nonnull IK entityIndexKey, @Nonnull Function<IK, I> removalPropagation) {
		final DataSourceChanges<IK, I> layer = TransactionalMemory.getTransactionalMemoryLayerIfExists(transactionalMemoryDataSource);
		if (layer == null) {
			return bufferedChangeSet.removeIndex(entityIndexKey, removalPropagation);
		} else {
			return layer.removeIndex(entityIndexKey, removalPropagation);
		}
	}

	/**
	 * Reads container primarily from transactional memory and when the container is not present there (or transaction
	 * is not opened) reads it from the target {@link CatalogPersistenceService}.
	 */
	@Nullable
	public <T extends StoragePart> T fetch(long primaryKey, @Nonnull Class<T> containerType) {
		final DataSourceChanges<IK, I> layer = TransactionalMemory.getTransactionalMemoryLayerIfExists(transactionalMemoryDataSource);
		if (layer == null) {
			return persistenceService.getStoragePart(primaryKey, containerType);
		} else if (layer.isContainerRemovedByPrimaryKey(primaryKey, containerType)) {
			return null;
		} else {
			return ofNullable(layer.getContainerByPrimaryKey(primaryKey, containerType))
				.orElseGet(() -> persistenceService.getStoragePart(primaryKey, containerType));
		}
	}

	/**
	 * Reads container primarily from transactional memory and when the container is not present there (or transaction
	 * is not opened) reads it from the target {@link CatalogPersistenceService}.
	 */
	@Nullable
	public <T extends StoragePart> byte[] fetchBinary(long primaryKey, @Nonnull Class<T> containerType, @Nonnull Function<T, byte[]> serializer) {
		final DataSourceChanges<IK, I> layer = TransactionalMemory.getTransactionalMemoryLayerIfExists(transactionalMemoryDataSource);
		if (layer == null) {
			return persistenceService.getStoragePartAsBinary(primaryKey, containerType);
		} else if (layer.isContainerRemovedByPrimaryKey(primaryKey, containerType)) {
			return null;
		} else {
			return ofNullable(layer.getContainerByPrimaryKey(primaryKey, containerType))
				.map(serializer)
				.orElseGet(() -> persistenceService.getStoragePartAsBinary(primaryKey, containerType));
		}
	}

	/**
	 * Reads container primarily from transactional memory and when the container is not present there (or transaction
	 * is not opened) reads it from the target {@link CatalogPersistenceService}.
	 */
	@Nullable
	public <T extends StoragePart, U extends Comparable<U>> T fetch(@Nonnull U originalKey, @Nonnull Class<T> containerType, @Nonnull BiFunction<KeyCompressor, U, Long> compressedKeyComputer) {
		final DataSourceChanges<IK, I> layer = TransactionalMemory.getTransactionalMemoryLayerIfExists(transactionalMemoryDataSource);
		if (layer == null) {
			try {
				final long storagePartId = ofNullable(this.bufferedChangeSet.getNonFlushedCompressedId(originalKey))
					.orElseGet(() -> compressedKeyComputer.apply(this.persistenceService.getReadOnlyKeyCompressor(), originalKey));
				return persistenceService.getStoragePart(storagePartId, containerType);
			} catch (CompressionKeyUnknownException ex) {
				// key wasn't yet assigned
				return null;
			}
		} else if (layer.isContainerRemovedByOriginalKey(originalKey, containerType)) {
			return null;
		} else {
			return ofNullable(layer.getContainerByOriginalKey(originalKey, containerType))
				.orElseGet(() -> {
					try {
						final long storagePartId = ofNullable(this.bufferedChangeSet.getNonFlushedCompressedId(originalKey))
							.orElseGet(() -> compressedKeyComputer.apply(this.persistenceService.getReadOnlyKeyCompressor(), originalKey));
						final T storagePart = persistenceService.getStoragePart(storagePartId, containerType);
						return ofNullable(storagePart)
							.filter(it -> !layer.isContainerRemovedByPrimaryKey(storagePartId, containerType))
							.orElse(null);
					} catch (CompressionKeyUnknownException ex) {
						// key wasn't yet assigned
						return null;
					}
				});
		}
	}

	/**
	 * Reads container primarily from transactional memory and when the container is not present there (or transaction
	 * is not opened) reads it from the target {@link CatalogPersistenceService}.
	 */
	@Nullable
	public <T extends StoragePart, U extends Comparable<U>> byte[] fetchBinary(@Nonnull U originalKey, @Nonnull Class<T> containerType, @Nonnull BiFunction<KeyCompressor, U, Long> compressedKeyComputer, @Nonnull Function<T, byte[]> serializer) {
		final DataSourceChanges<IK, I> layer = TransactionalMemory.getTransactionalMemoryLayerIfExists(transactionalMemoryDataSource);
		if (layer == null) {
			try {
				final long nonFlushedCompressedId = ofNullable(this.bufferedChangeSet.getNonFlushedCompressedId(originalKey))
					.orElseGet(() -> compressedKeyComputer.apply(this.persistenceService.getReadOnlyKeyCompressor(), originalKey));
				return persistenceService.getStoragePartAsBinary(nonFlushedCompressedId, containerType);
			} catch (CompressionKeyUnknownException ex) {
				// key wasn't yet assigned
				return null;
			}
		} else if (layer.isContainerRemovedByOriginalKey(originalKey, containerType)) {
			return null;
		} else {
			return ofNullable(layer.getContainerByOriginalKey(originalKey, containerType))
				.map(serializer)
				.orElseGet(() -> {
					final long storagePartId = ofNullable(this.bufferedChangeSet.getNonFlushedCompressedId(originalKey))
						.orElseGet(() -> compressedKeyComputer.apply(this.persistenceService.getReadOnlyKeyCompressor(), originalKey));
					final byte[] storagePart = persistenceService.getStoragePartAsBinary(storagePartId, containerType);
					return ofNullable(storagePart)
						.filter(it -> !layer.isContainerRemovedByPrimaryKey(storagePartId, containerType))
						.orElse(null);
				});
		}
	}

	/**
	 * Removes container from the target storage. If transaction is open, it just marks the container as removed but
	 * doesn't really remove it.
	 */
	public <T extends StoragePart> boolean removeByPrimaryAndOriginalKey(long primaryKey, @Nonnull Comparable<?> originalKey, @Nonnull Class<T> entityClass) {
		final DataSourceChanges<IK, I> layer = TransactionalMemory.getTransactionalMemoryLayer(transactionalMemoryDataSource);
		if (layer == null) {
			return this.persistenceService.removeStoragePart(primaryKey, entityClass);
		} else {
			if (this.persistenceService.containsStoragePart(primaryKey, entityClass)) {
				return layer.removeContainerByPrimaryKey(primaryKey, originalKey, entityClass);
			} else {
				return false;
			}
		}
	}

	/**
	 * Removes container from the target storage. If transaction is open, it just marks the container as removed but
	 * doesn't really remove it.
	 */
	public <T extends StoragePart> boolean removeByPrimaryKey(long primaryKey, @Nonnull Class<T> entityClass) {
		final DataSourceChanges<IK, I> layer = TransactionalMemory.getTransactionalMemoryLayer(transactionalMemoryDataSource);
		if (layer == null) {
			return this.persistenceService.removeStoragePart(primaryKey, entityClass);
		} else {
			if (this.persistenceService.containsStoragePart(primaryKey, entityClass) || layer.containsStoragePartToUpsert(primaryKey, entityClass)) {
				return layer.removeContainerByPrimaryKey(primaryKey, entityClass);
			} else {
				return false;
			}
		}
	}

	/**
	 * Removes container from the transactional memory. This method should be used only for container, that has
	 * no uniqueId assigned so far (e.g. they haven't been stored yet).
	 */
	public <T extends StoragePart> boolean removeByOriginalKey(@Nonnull Comparable<?> originalKey, @Nonnull Class<T> entityClass) {
		final DataSourceChanges<IK, I> layer = TransactionalMemory.getTransactionalMemoryLayer(transactionalMemoryDataSource);
		if (layer == null) {
			return false;
		} else {
			return layer.removeContainerByOriginalKey(originalKey, entityClass);
		}
	}

	/**
	 * Inserts or updates container in the target storage. If transaction is opened, the changes are written only in
	 * the transactional layer and are not really written to the persistent storage. Changes are written at the moment
	 * when transaction is committed.
	 */
	public <T extends StoragePart> void update(@Nonnull T value) {
		final DataSourceChanges<IK, I> layer = TransactionalMemory.getTransactionalMemoryLayer(transactionalMemoryDataSource);
		if (layer == null) {
			final long partId = this.persistenceService.putStoragePart(0L, value);
			if (value instanceof RecordWithCompressedId) {
				this.bufferedChangeSet.setNonFlushedCompressedId(((RecordWithCompressedId<?>) value).getStoragePartSourceKey(), partId);
			}
		} else {
			final Long uniquePartId = value.getUniquePartId();
			if (value instanceof RecordWithCompressedId) {
				if (uniquePartId != null) {
					layer.updateContainerByPrimaryKey(uniquePartId, ((RecordWithCompressedId<?>) value).getStoragePartSourceKey(), value);
				} else {
					layer.updateContainerByOriginalKey(((RecordWithCompressedId<?>) value).getStoragePartSourceKey(), value);
				}
			} else {
				if (uniquePartId != null) {
					layer.updateContainerByPrimaryKey(uniquePartId, value);
				} else {
					throw new EvitaInternalError(
						"Stored value must either implement RecordWithCompressedId " +
							"interface or provide uniquePartId! Object " + value + " does neither!"
					);
				}
			}
		}
	}

	/**
	 * Method returns current buffer with trapped changes and creates new one, that starts fill in.
	 * This method doesn't take transactional memory into an account but contains only changes for trapped updates.
	 */
	public BufferedChangeSet<IK, I> exchangeBuffer() {
		// store current trapped updates
		final BufferedChangeSet<IK, I> oldChangeSet = this.bufferedChangeSet;
		// create new trapped updates containers - so that simultaneous updates from other threads doesn't affect this flush
		this.bufferedChangeSet = new BufferedChangeSet<>();
		// return old buffered change set
		return oldChangeSet;
	}

}
