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

package io.evitadb.index.transactionalMemory.diff;

import io.evitadb.core.EntityCollection;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.Index;
import io.evitadb.index.IndexKey;
import io.evitadb.store.model.PersistedStoragePartKey;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.spi.model.storageParts.RemovedStoragePart;
import io.evitadb.store.spi.model.storageParts.StoragePartKey;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * This class keeps transactional layer changes for {@link EntityCollection}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class DataSourceChanges<IK extends IndexKey, I extends Index<IK>> {
	/**
	 * This map contains storage parts that needs to be persisted.
	 */
	private final Map<PersistedStoragePartKey, StoragePart> pendingStorageParts = new HashMap<>(64);
	/**
	 * This map contains compressed storage parts that needs to be persisted.
	 */
	private final Map<StoragePartKey, StoragePart> pendingStoragePartsWithUnknownPrimaryKey = new HashMap<>(64);
	/**
	 * This index contains relation between storagePartKeys with unknown primary key to persisted storage part keys
	 * with known primary key. There are situations that the storage parts are deleted and immediately created within
	 * the same transaction and without this index it would not be possible to link them together.
	 *
	 * Because there is no order kept in:
	 *
	 * - {@link DataSourceChanges#getModifiedStoragePartsToPersist()}
	 * - {@link DataSourceChanges#getRemovedStoragePartsToPersist()}
	 *
	 * It may happen that the storing happens after the removal even if the original order of the operations was
	 * reversed and this leads to errors. We need to remove the operations from existing maps and this link is therefore
	 * necessary.
	 */
	private final Map<StoragePartKey, PersistedStoragePartKey> notPersistedToPersistedKeysIndex = new HashMap<>(64);
	/**
	 * This map contains reference to all {@link Index} modified in this transaction.
	 */
	private final Map<IK, I> dirtyEntityIndexes = new HashMap<>(64);

	@SuppressWarnings("unchecked")
	public <T extends StoragePart> T getContainerByPrimaryKey(long primaryKey, @Nonnull Class<T> containerType) {
		return (T) pendingStorageParts.get(new PersistedStoragePartKey(primaryKey, containerType));
	}

	public <T extends StoragePart> boolean containsStoragePartToUpsert(long primaryKey, @Nonnull Class<T> entityClass) {
		return ofNullable(pendingStorageParts.get(new PersistedStoragePartKey(primaryKey, entityClass)))
			.filter(it -> !(it instanceof RemovedStoragePart))
			.isPresent();
	}

	public <T extends StoragePart> boolean isContainerRemovedByPrimaryKey(long primaryKey, @Nonnull Class<T> containerType) {
		return pendingStorageParts.get(new PersistedStoragePartKey(primaryKey, containerType)) instanceof RemovedStoragePart;
	}

	public <T extends StoragePart> boolean removeContainerByPrimaryKey(long primaryKey, @Nonnull Comparable<?> originalKey, @Nonnull Class<T> containerType) {
		final PersistedStoragePartKey persistedStoragePartKey = new PersistedStoragePartKey(primaryKey, containerType);
		this.notPersistedToPersistedKeysIndex.put(new StoragePartKey(originalKey, containerType), persistedStoragePartKey);
		return pendingStorageParts.put(persistedStoragePartKey, RemovedStoragePart.INSTANCE) != null;
	}

	public <T extends StoragePart> boolean removeContainerByPrimaryKey(long primaryKey, @Nonnull Class<T> containerType) {
		final PersistedStoragePartKey persistedStoragePartKey = new PersistedStoragePartKey(primaryKey, containerType);
		return pendingStorageParts.put(persistedStoragePartKey, RemovedStoragePart.INSTANCE) != null;
	}

	public <T extends StoragePart> void updateContainerByPrimaryKey(long primaryKey, @Nonnull T value) {
		final PersistedStoragePartKey persistedStoragePartKey = new PersistedStoragePartKey(primaryKey, value.getClass());
		this.pendingStorageParts.put(persistedStoragePartKey, value);
	}

	public <T extends StoragePart> void updateContainerByPrimaryKey(long primaryKey, @Nonnull Comparable<?> originalKey, @Nonnull T value) {
		final PersistedStoragePartKey persistedStoragePartKey = new PersistedStoragePartKey(primaryKey, value.getClass());
		this.notPersistedToPersistedKeysIndex.put(new StoragePartKey(originalKey, value.getClass()), persistedStoragePartKey);
		this.pendingStorageParts.put(persistedStoragePartKey, value);
	}

	@SuppressWarnings("unchecked")
	public <T extends StoragePart> T getContainerByOriginalKey(@Nonnull Comparable<?> originalKey, @Nonnull Class<T> containerType) {
		final StoragePartKey storagePartKey = new StoragePartKey(originalKey, containerType);
		return ofNullable((T) pendingStoragePartsWithUnknownPrimaryKey.get(storagePartKey))
			.orElseGet(
				() -> (T) ofNullable(this.notPersistedToPersistedKeysIndex.get(storagePartKey))
					.map(this.pendingStorageParts::get)
					.orElse(null)
			);
	}

	public <T extends StoragePart> boolean isContainerRemovedByOriginalKey(@Nonnull Comparable<?> originalKey, @Nonnull Class<T> containerType) {
		final StoragePartKey storagePartKey = new StoragePartKey(originalKey, containerType);
		if (pendingStoragePartsWithUnknownPrimaryKey.get(storagePartKey) instanceof RemovedStoragePart) {
			return true;
		} else {
			return ofNullable(this.notPersistedToPersistedKeysIndex.get(storagePartKey))
				.map(this.pendingStorageParts::get)
				.map(it -> it instanceof RemovedStoragePart)
				.orElse(false);
		}
	}

	public <T extends StoragePart> boolean removeContainerByOriginalKey(@Nonnull Comparable<?> originalKey, @Nonnull Class<T> containerType) {
		final StoragePartKey storagePartKey = new StoragePartKey(originalKey, containerType);
		boolean removed = this.pendingStoragePartsWithUnknownPrimaryKey.remove(storagePartKey) != null;
		final PersistedStoragePartKey persistedStoragePartKey = this.notPersistedToPersistedKeysIndex.get(storagePartKey);
		if (persistedStoragePartKey != null) {
			removed = true;
			this.pendingStorageParts.put(persistedStoragePartKey, RemovedStoragePart.INSTANCE);
		}
		return removed;
	}

	public <T extends StoragePart> void updateContainerByOriginalKey(@Nonnull Comparable<?> originalKey, @Nonnull T value) {
		final StoragePartKey storagePartKey = new StoragePartKey(originalKey, value.getClass());
		final PersistedStoragePartKey persistedStoragePartKey = this.notPersistedToPersistedKeysIndex.get(storagePartKey);
		if (persistedStoragePartKey != null) {
			this.pendingStorageParts.put(persistedStoragePartKey, value);
		} else {
			pendingStoragePartsWithUnknownPrimaryKey.put(storagePartKey, value);
		}
	}

	public Collection<StoragePart> getModifiedStoragePartsToPersist() {
		return Stream.concat(
				Stream.concat(
					pendingStoragePartsWithUnknownPrimaryKey
						.values()
						.stream(),
					pendingStorageParts
						.values()
						.stream()
						.filter(it -> !(it instanceof RemovedStoragePart))
				),
				dirtyEntityIndexes
					.values()
					.stream()
					.flatMap(it -> it.getModifiedStorageParts().stream())
			)
			.collect(Collectors.toList());
	}

	public Collection<PersistedStoragePartKey> getRemovedStoragePartsToPersist() {
		return pendingStorageParts
			.entrySet()
			.stream()
			.filter(it -> it.getValue() instanceof RemovedStoragePart)
			.map(Entry::getKey)
			.collect(Collectors.toList());
	}

	/**
	 * Method checks and returns the requested index from the local "dirty" memory. If it isn't there, it's fetched
	 * using `accessorWhenMissing` lambda and stores into the "dirty" memory before returning.
	 */
	public I getOrCreateIndexForModification(@Nonnull IK entityIndexKey, @Nonnull Function<IK, I> accessorWhenMissing) {
		return dirtyEntityIndexes.computeIfAbsent(
			entityIndexKey, accessorWhenMissing
		);
	}

	/**
	 * Method checks and returns the requested index from the local "dirty" memory. If it isn't there, it's fetched
	 * using `accessorWhenMissing` and returned without adding to "dirty" memory.
	 */
	public I getIndexIfExists(@Nonnull IK entityIndexKey, @Nonnull Function<IK, I> accessorWhenMissing) {
		return ofNullable(dirtyEntityIndexes.get(entityIndexKey))
			.orElseGet(() -> accessorWhenMissing.apply(entityIndexKey));
	}

	/**
	 * Removes {@link EntityIndex} from the change set. After removal (either successfully or unsuccessful)
	 * `removalPropagation` function is called to propagate deletion to the origin collection.
	 */
	public I removeIndex(@Nonnull IK entityIndexKey, @Nonnull Function<IK, I> removalPropagation) {
		final I dirtyIndexesRemoval = dirtyEntityIndexes.remove(entityIndexKey);
		final I baseIndexesRemoval = removalPropagation.apply(entityIndexKey);
		return ofNullable(dirtyIndexesRemoval).orElse(baseIndexesRemoval);
	}

}
