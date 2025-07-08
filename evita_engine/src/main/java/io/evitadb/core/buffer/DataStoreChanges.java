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

import com.carrotsearch.hppc.LongObjectHashMap;
import com.carrotsearch.hppc.LongObjectMap;
import com.carrotsearch.hppc.ObjectContainer;
import com.carrotsearch.hppc.cursors.LongObjectCursor;
import com.carrotsearch.hppc.cursors.ObjectCursor;
import io.evitadb.core.EntityCollection;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.Index;
import io.evitadb.index.IndexKey;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.store.spi.StoragePartPersistenceService;
import io.evitadb.store.spi.model.storageParts.StoragePartKey;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import static java.util.Optional.ofNullable;

/**
 * This class is used as transactional memory of the {@link DataStoreChanges} and stores the changes of the storage
 * keys directly to the target {@link StoragePartPersistenceService}, but traps the changes in the indexes in the memory
 * buffer. It provides methods to get, create, remove, and track modifications to indexes. The changes are cached in
 * memory and can be persisted to the storage using the {@link #popTrappedUpdates()} method.
 *
 * This mechanism allows to buffer frequent changes in indexes whose persistence is costly and flush the changes once in
 * a while to the persistent storage.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 * @see DataStoreMemoryBuffer
 */
@NotThreadSafe
public class DataStoreChanges {
	/**
	 * This map contains index of "dirty" entity indexes - i.e. subset of {@link EntityCollection indexes} that were
	 * modified and not yet persisted.
	 */
	private Map<IndexKey, Index<? extends IndexKey>> dirtyEntityIndexes = new HashMap<>(64);
	/**
	 * Contains reference to the I/O service, that allows reading/writing records to the persistent storage.
	 */
	@Nonnull private StoragePartPersistenceService persistenceService;
	/**
	 * This map contains index of "dirty" storage parts - i.e. subset of {@link StoragePart storage parts} that were
	 * modified and not yet persisted. Usually the storage parts are stored directly in the persistent storage but
	 * the <a href="https://github.com/FgForrest/evitaDB/issues/689">issue #689</a> revealed that it's beneficial to
	 * store some of them in memory and flush them once in a while to the persistent storage.
	 */
	@Nullable private Map<Class<? extends StoragePart>, LongObjectMap<StoragePart>> trappedChanges;

	public DataStoreChanges(@Nonnull StoragePartPersistenceService persistenceService) {
		this.persistenceService = persistenceService;
	}

	/**
	 * Allows exchanging the persistence service for this memory buffer in case of internal store compaction.
	 *
	 * @param persistenceService the persistence service to be used for storing data
	 */
	public void setPersistenceService(@Nonnull StoragePartPersistenceService persistenceService) {
		this.persistenceService = persistenceService;
	}

	/**
	 * Returns set containing {@link StoragePartKey keys} that lead to the data structures in memory that were modified
	 * (are dirty) and needs to be persisted into the persistent storage. This is performance optimization that minimizes
	 * I/O operations for frequently changed data structures such as indexes and these are stored once in a while in
	 * the moments when it has a sense.
	 */
	@Nonnull
	public TrappedChanges popTrappedUpdates() {
		final TrappedChanges trappedChanges = new TrappedChanges();

		final Map<IndexKey, Index<? extends IndexKey>> theDirtyEntityIndexes = this.dirtyEntityIndexes;
		this.dirtyEntityIndexes = new HashMap<>(64);
		for (Index<? extends IndexKey> index : theDirtyEntityIndexes.values()) {
			index.getModifiedStorageParts(trappedChanges);
		}
		final Map<Class<? extends StoragePart>, LongObjectMap<StoragePart>> theTrappedChanges = this.trappedChanges;
		this.trappedChanges = null;
		if (theTrappedChanges != null) {
			for (LongObjectMap<StoragePart> changesIndex : theTrappedChanges.values()) {
				final ObjectContainer<StoragePart> values = changesIndex.values();
				trappedChanges.addIterator(new LongObjectIterator<>(values.iterator()), values.size());
			}
		}

		return trappedChanges;
	}

	/**
	 * Returns a KeyCompressor that contains indexes of keys assigned to key-comparable objects which are expensive
	 * to store redundantly during serialization.
	 *
	 * @return a read-only KeyCompressor instance to be used for key compress.
	 */
	@Nonnull
	public KeyCompressor getReadOnlyKeyCompressor() {
		return this.persistenceService.getReadOnlyKeyCompressor();
	}

	/**
	 * Retrieves a storage part from the local trapped changes cache if available, otherwise fetches it from the persistence service.
	 *
	 * @param catalogVersion the current version of the catalog to read from
	 * @param primaryKey primary key of the storage part to retrieve
	 * @param containerType class type of the storage part container
	 * @param <T> type of the storage part container
	 * @return the storage part if found, otherwise null
	 */
	@Nullable
	public <T extends StoragePart> T getStoragePart(long catalogVersion, long primaryKey, @Nonnull Class<T> containerType) {
		if (this.trappedChanges != null) {
			final LongObjectMap<StoragePart> trappedChanges = this.trappedChanges.get(containerType);
			if (trappedChanges != null) {
				final StoragePart storagePart = trappedChanges.get(primaryKey);
				if (storagePart != null) {
					return storagePart instanceof RemovedStoragePart ?
						null :
						containerType.cast(storagePart);
				}
			}
		}
		return this.persistenceService.getStoragePart(catalogVersion, primaryKey, containerType);
	}

	/**
	 * Retrieves a storage part as a binary array. The storage part is first searched for in the local trapped changes
	 * cache. If found, it is serialized and returned unless it is a {@link RemovedStoragePart}; in which case, null is returned.
	 * If not found in the cache, it fetches the storage part from the persistence service and returns it as a binary array.
	 *
	 * @param catalogVersion the current version of the catalog to read from
	 * @param primaryKey primary key of the storage part to retrieve
	 * @param containerType class type of the storage part container
	 * @param <T> type of the storage part container
	 * @return byte array representing the storage part if found, otherwise null
	 */
	@Nullable
	public <T extends StoragePart> byte[] getStoragePartAsBinary(long catalogVersion, long primaryKey, @Nonnull Class<T> containerType) {
		if (this.trappedChanges != null) {
			final LongObjectMap<StoragePart> trappedChanges = this.trappedChanges.get(containerType);
			if (trappedChanges != null) {
				final StoragePart storagePart = trappedChanges.get(primaryKey);
				if (storagePart != null) {
					return storagePart instanceof RemovedStoragePart ?
						null :
						this.persistenceService.serializeStoragePart(storagePart);
				}
			}
		}
		return this.persistenceService.getStoragePartAsBinary(catalogVersion, primaryKey, containerType);
	}

	/**
	 * Removes a storage part identified by the given catalog version, primary key, and entity class.
	 *
	 * @param catalogVersion the version of the catalog to modify
	 * @param primaryKey the primary key of the storage part to remove
	 * @param entityClass the class type of the storage part to remove
	 * @param <T> the type of the storage part
	 * @return true if the storage part was successfully removed, false otherwise
	 */
	public <T extends StoragePart> boolean removeStoragePart(long catalogVersion, long primaryKey, @Nonnull Class<T> entityClass) {
		if (this.trappedChanges != null) {
			ofNullable(this.trappedChanges.get(entityClass)).ifPresent(it -> it.remove(primaryKey));
		}
		return this.persistenceService.removeStoragePart(catalogVersion, primaryKey, entityClass);
	}

	public <T extends StoragePart> boolean trapRemoveStoragePart(long catalogVersion, long primaryKey, @Nonnull Class<T> entityClass) {
		this.trappedChanges = this.trappedChanges == null ? new HashMap<>(64) : this.trappedChanges;
		if (this.persistenceService.containsStoragePart(catalogVersion, primaryKey, entityClass)) {
			this.trappedChanges.computeIfAbsent(entityClass, aClass -> new LongObjectHashMap<>(256))
				.put(
					primaryKey,
					new RemovedStoragePart(entityClass, primaryKey)
				);
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Stores the provided storage part and manages any trapped changes related to it.
	 *
	 * @param catalogVersion the current version of the catalog to write to
	 * @param value the storage part to store, must not be null
	 * @param <T> the type of the storage part
	 */
	public <T extends StoragePart> void putStoragePart(long catalogVersion, @Nonnull T value) {
		if (this.trappedChanges != null) {
			ofNullable(this.trappedChanges.get(value.getClass()))
				.ifPresent(it -> it.remove(value.getStoragePartPKOrElseThrowException()));
		}
		this.persistenceService.putStoragePart(catalogVersion, value);
	}

	/**
	 * Adds the specified storage part to the local trapped changes cache.
	 *
	 * @param <T> the type of the storage part
	 * @param value the storage part to be added, must not be null
	 */
	public <T extends StoragePart> void trapPutStoragePart(@Nonnull T value) {
		this.trappedChanges = this.trappedChanges == null ? new HashMap<>(64) : this.trappedChanges;
		final long storagePartPK = value.getStoragePartPKOrElseThrowException();
		final Class<? extends StoragePart> containerType = value.getClass();
		this.trappedChanges.computeIfAbsent(containerType, aClass -> new LongObjectHashMap<>(256))
			.put(storagePartPK, value);
	}

	/**
	 * Counts the total number of storage parts of a specific type in a catalog version,
	 * accounting for trapped changes such as insertions and removals.
	 *
	 * @param catalogVersion the version of the catalog to count storage parts from
	 * @param containerType the class type of the storage part containers to count
	 * @return the total number of storage parts, adjusted for trapped changes
	 */
	public int countStorageParts(long catalogVersion, Class<? extends StoragePart> containerType) {
		final int storedCount = this.persistenceService.countStorageParts(catalogVersion, containerType);
		if (this.trappedChanges == null || this.trappedChanges.isEmpty()) {
			return storedCount;
		} else {
			final LongObjectMap<StoragePart> trappedChanges = this.trappedChanges.get(containerType);
			if (trappedChanges == null) {
				return storedCount;
			} else {
				int inserts = 0;
				int removals = 0;
				for (LongObjectCursor<StoragePart> trappedChange : trappedChanges) {
					if (trappedChange.value instanceof RemovedStoragePart) {
						removals++;
					} else if (!this.persistenceService.containsStoragePart(catalogVersion, trappedChange.key, containerType)) {
						inserts++;
					}
				}
				return storedCount + inserts - removals;
			}
		}
	}

	/**
	 * Method checks and returns the requested index from the local "dirty" memory. If it isn't there, it's fetched
	 * using `accessorWhenMissing` lambda and stores into the "dirty" memory before returning.
	 */
	@Nonnull
	public <IK extends IndexKey, I extends Index<IK>> I getOrCreateIndexForModification(@Nonnull IK indexKey, @Nonnull Function<IK, I> accessorWhenMissing) {
		//noinspection unchecked,rawtypes
		return (I) this.dirtyEntityIndexes.computeIfAbsent(
			indexKey, (Function) accessorWhenMissing
		);
	}

	/**
	 * Method checks and returns the requested index from the local "dirty" memory. If it isn't there, it's fetched
	 * using `accessorWhenMissing` and returned without adding to "dirty" memory.
	 */
	@Nullable
	public <IK extends IndexKey, I extends Index<IK>> I getIndexIfExists(@Nonnull IK indexKey, @Nonnull Function<IK, I> accessorWhenMissing) {
		//noinspection unchecked
		return ofNullable((I) this.dirtyEntityIndexes.get(indexKey))
			.orElseGet(() -> accessorWhenMissing.apply(indexKey));
	}

	/**
	 * Removes {@link EntityIndex} from the change set. After removal (either successfully or unsuccessful)
	 * `removalPropagation` function is called to propagate deletion to the origin collection.
	 */
	@Nonnull
	public <IK extends IndexKey, I extends Index<IK>> I removeIndex(@Nonnull IK entityIndexKey, @Nonnull Function<IK, I> removalPropagation) {
		//noinspection unchecked
		final I dirtyIndexesRemoval = (I) this.dirtyEntityIndexes.remove(entityIndexKey);
		final I baseIndexesRemoval = removalPropagation.apply(entityIndexKey);
		return ofNullable(dirtyIndexesRemoval).orElse(baseIndexesRemoval);
	}

	/**
	 * RemovedStoragePart is a specific implementation of the StoragePart interface which represents a part of storage
	 * that should be removed.
	 *
	 * @param containerType the type of the container that was removed
	 * @param storagePartPK the primary key of the storage part that was removed
	 */
	public record RemovedStoragePart(
		@Nonnull Class<? extends StoragePart> containerType,
		long storagePartPK
	) implements StoragePart {
		@Serial private static final long serialVersionUID = -3939591252705809288L;

		@Nonnull
		@Override
		public Long getStoragePartPK() {
			return this.storagePartPK;
		}

		@Override
		public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
			return this.storagePartPK;
		}
	}

	/**
	 * An iterator implementation for iterating over elements of type T, backed by an iterator
	 * of {@link ObjectCursor} objects for efficient traversal.
	 *
	 * @param <T> the type of elements returned by this iterator
	 */
	@RequiredArgsConstructor
	private static class LongObjectIterator<T> implements Iterator<T> {
		private final Iterator<ObjectCursor<T>> iterator;

		@Override
		public boolean hasNext() {
			return this.iterator.hasNext();
		}

		@Override
		public T next() {
			return this.iterator.next().value;
		}

	}
}
