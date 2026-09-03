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

import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.index.Index;
import io.evitadb.index.IndexKey;
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Supplier;
import java.util.OptionalLong;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * WarmUpDataStoreMemoryBuffer represents volatile temporal memory between the {@link EntityCollection} and persistent
 * storage that keeps frequently changed data in the {@link DataStoreMemoryBuffer} buffer and flushes them at
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
		this(persistenceService, null);
	}

	/**
	 * Creates a warm-up buffer whose savepoint pre-image reads can deserialize entity storage parts.
	 *
	 * @param persistenceService   the I/O service records are read from / written to
	 * @param entitySchemaSupplier supplies the schema those reads deserialize against, or `null` for the catalog-level
	 *                             buffer, whose parts carry neither references nor prices and need none
	 */
	public WarmUpDataStoreMemoryBuffer(
		@Nonnull StoragePartPersistenceService persistenceService,
		@Nullable Supplier<EntitySchema> entitySchemaSupplier
	) {
		this.dataStoreChanges = new DataStoreChanges(persistenceService, false, entitySchemaSupplier);
	}

	/**
	 * Allows exchanging the persistence service for this memory buffer in case of internal store compaction.
	 *
	 * @param persistenceService new persistence service to be used
	 */
	public void setPersistenceService(@Nonnull StoragePartPersistenceService persistenceService) {
		this.dataStoreChanges.setPersistenceService(persistenceService);
	}

	@Nonnull
	@Override
	public <IK extends IndexKey, I extends Index<IK>> I getOrCreateIndexForModification(@Nonnull IK entityIndexKey, @Nonnull Function<IK, I> accessorWhenMissing) {
		return this.dataStoreChanges.getOrCreateIndexForModification(entityIndexKey, accessorWhenMissing);
	}

	@Override
	public <IK extends IndexKey, I extends Index<IK>> I getIndexIfExists(@Nonnull IK entityIndexKey, @Nonnull Function<IK, I> accessorWhenMissing) {
		return this.dataStoreChanges.getIndexIfExists(entityIndexKey, accessorWhenMissing);
	}

	@Nullable
	@Override
	public <IK extends IndexKey, I extends Index<IK>> I getIndexIfExists(int entityIndexPrimaryKey, @Nonnull IntFunction<I> accessorWhenMissing) {
		return this.dataStoreChanges.getIndexIfExists(entityIndexPrimaryKey, accessorWhenMissing);
	}

	@Nonnull
	@Override
	public <IK extends IndexKey, I extends Index<IK>> I getOrCreateIndexForModification(
		int entityIndexPrimaryKey,
		@Nonnull IntFunction<I> accessorWhenMissing
	) {
		return this.dataStoreChanges.getIndexForModification(entityIndexPrimaryKey, accessorWhenMissing);
	}

	@Nullable
	@Override
	public <IK extends IndexKey, I extends Index<IK>> I removeIndex(long catalogVersion, @Nonnull IK entityIndexKey, @Nonnull Function<IK, I> removalPropagation) {
		return this.dataStoreChanges.removeIndex(catalogVersion, entityIndexKey, removalPropagation);
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
		// DESTRUCTIVE, and that is what makes a failed flush terminal for the catalog: this hands the pending parts
		// over AND advances every index's change-detection baseline, so nothing collected here can be collected
		// again. A flush that fails after this point therefore cannot be retried - the refusal that follows it lives
		// on the catalog (`Catalog#markUnpublishable`), because publication is catalog-wide and publication is the
		// only thing that has to be stopped.
		//
		// NOT guarded against running mid-savepoint, deliberately. Draining here while a WarmUpSavepoint were open
		// would hand the storage layer state the journal may still rewind, but the two cannot interleave: every caller
		// of this method is a flush entry point (EntityCollection#createFlushFuture / #flush / #flush(long),
		// Catalog#flush / #flush(long)), and a savepoint exists only for the duration of one root entity mutation
		// inside LocalMutationExecutorCollector#execute, which reaches no flush entry point. A guard here would
		// therefore be unreachable code asserting an invariant that holds one level up - so the invariant is recorded
		// rather than enforced. Should a flush ever become reachable from inside a mutation, this is the site to
		// revisit first.
		return this.dataStoreChanges.popTrappedUpdates();
	}

}
