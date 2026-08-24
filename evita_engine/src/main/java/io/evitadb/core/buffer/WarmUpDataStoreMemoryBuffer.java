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

import io.evitadb.core.collection.EntityCollection;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.Index;
import io.evitadb.index.IndexKey;
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
	/**
	 * The failure of a previous flush, or {@code null} while this buffer is healthy. Declared {@code volatile} because
	 * the catalog-level buffer is poisoned from inside a flush-future completion callback that may run on a worker
	 * thread, while {@link #popTrappedChanges()} reads this field on the catalog writer thread — a plain field would not
	 * guarantee the writer observes the poison.
	 */
	@Nullable private volatile Throwable flushFailure;

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
		// every warm-up collect passes through here, whatever triggered it - a session close, a collection being
		// created / removed / replaced, going live or terminating - so this is the single point at which a warm-up
		// buffer whose flush failed can be stopped before it writes again (this buffer backs both an entity collection
		// and the catalog, so the refusal is phrased for either)
		//
		// NOT guarded against running mid-savepoint, deliberately. Draining here while a WarmUpSavepoint were open
		// would hand the storage layer state the journal may still rewind, but the two cannot interleave: every caller
		// of this method is a flush entry point (EntityCollection#createFlushFuture / #flush / #flush(long),
		// Catalog#flush / #flush(long)), and a savepoint exists only for the duration of one root entity mutation
		// inside LocalMutationExecutorCollector#execute, which reaches no flush entry point. A guard here would
		// therefore be unreachable code asserting an invariant that holds one level up - so the invariant is recorded
		// rather than enforced. Should a flush ever become reachable from inside a mutation, this is the site to
		// revisit first.
		final Throwable theFlushFailure = this.flushFailure;
		if (theFlushFailure != null) {
			throw new GenericEvitaInternalError(
				"Cannot collect changes: a previous warm-up flush failed, so the changes it had already collected are " +
					"lost and the persisted state is incomplete. Reload the catalog from disk to recover.",
				theFlushFailure
			);
		}
		return this.dataStoreChanges.popTrappedUpdates();
	}

	@Override
	public void poison(@Nonnull Throwable cause) {
		// keep the FIRST failure: it is the one that actually lost the collected changes, and every later refusal is
		// merely its consequence
		if (this.flushFailure == null) {
			this.flushFailure = cause;
		}
	}

}
