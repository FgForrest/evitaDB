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

import io.evitadb.core.EntityCollection;
import io.evitadb.index.Index;
import io.evitadb.index.IndexKey;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.store.spi.StoragePartPersistenceService;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * This class keeps transactional layer changes for {@link EntityCollection}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
public class DataStoreChanges<IK extends IndexKey, I extends Index<IK>> implements DataStoreIndexChanges<IK, I> {
	/**
	 * This map contains reference to all {@link Index} modified in this transaction.
	 */
	private Map<IK, I> dirtyEntityIndexes = new HashMap<>(64);
	/**
	 * Contains reference to the I/O service, that allows reading/writing records to the persistent storage.
	 */
	@Nonnull private final StoragePartPersistenceService persistenceService;

	public DataStoreChanges(@Nonnull StoragePartPersistenceService persistenceService) {
		this.persistenceService = persistenceService;
	}

	@Override
	@Nonnull
	public I getOrCreateIndexForModification(@Nonnull IK entityIndexKey, @Nonnull Function<IK, I> accessorWhenMissing) {
		return dirtyEntityIndexes.computeIfAbsent(
			entityIndexKey, accessorWhenMissing
		);
	}

	@Override
	public I getIndexIfExists(@Nonnull IK entityIndexKey, @Nonnull Function<IK, I> accessorWhenMissing) {
		return ofNullable(dirtyEntityIndexes.get(entityIndexKey))
			.orElseGet(() -> accessorWhenMissing.apply(entityIndexKey));
	}

	@Override
	@Nonnull
	public I removeIndex(@Nonnull IK entityIndexKey, @Nonnull Function<IK, I> removalPropagation) {
		final I dirtyIndexesRemoval = dirtyEntityIndexes.remove(entityIndexKey);
		final I baseIndexesRemoval = removalPropagation.apply(entityIndexKey);
		return ofNullable(dirtyIndexesRemoval).orElse(baseIndexesRemoval);
	}

	@Override
	@Nonnull
	public Stream<StoragePart> popTrappedUpdates() {
		final Map<IK, I> dirtyEntityIndexes = this.dirtyEntityIndexes;
		// this implementation can be popped only once!
		this.dirtyEntityIndexes = Collections.emptyMap();
		return dirtyEntityIndexes
			.values()
			.stream()
			.flatMap(it -> it.getModifiedStorageParts().stream());
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
