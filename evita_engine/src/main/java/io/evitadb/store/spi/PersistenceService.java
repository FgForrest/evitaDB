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

package io.evitadb.store.spi;

import io.evitadb.exception.EvitaInternalError;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Closeable;
import java.util.List;

/**
 * Persistent service defines shared contract for services that allow to work with persistent data storage based on
 * file offset index object. There is usually single file for one key EvitaDB object (catalog, entity-collection).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface PersistenceService extends Closeable {

	/**
	 * Reads container primarily from transactional memory and when the container is not present there (or transaction
	 * is not opened) reads it from the target {@link CatalogPersistenceService}.
	 */
	@Nullable
	<T extends StoragePart> T getStoragePart(long primaryKey, @Nonnull Class<T> containerType);

	/**
	 * Reads container primarily from transactional memory and when the container is not present there (or transaction
	 * is not opened) reads it from the target {@link CatalogPersistenceService}.
	 */
	@Nullable
	<T extends StoragePart> byte[] getStoragePartAsBinary(long primaryKey, @Nonnull Class<T> containerType);

	/**
	 * Reads container primarily from transactional memory and when the container is not present there (or transaction
	 * is not opened) reads it from the target {@link CatalogPersistenceService}.
	 *
	 * @return already or newly assigned {@link StoragePart#getUniquePartId()} - primary key of the storage part
	 */
	<T extends StoragePart> long putStoragePart(long transactionId, @Nonnull T container);

	/**
	 * Removes container from the transactional memory. This method should be used only for container, that has
	 * no uniqueId assigned so far (e.g. they haven't been stored yet).
	 */
	<T extends StoragePart> boolean removeStoragePart(long primaryKey, @Nonnull Class<T> containerType);

	/**
	 * Returns true if persistent storage contains non-removed storage part of particular primary key and container
	 * type.
	 */
	<T extends StoragePart> boolean containsStoragePart(long primaryKey, @Nonnull Class<T> containerType);

	/**
	 * Method applies all deferredOperations from the passed list on current data storage.
	 */
	default void applyUpdates(@Nonnull String owner, long transactionId, @Nonnull List<DeferredStorageOperation<?>> deferredOperations) {
		for (final DeferredStorageOperation<?> deferredOperation : deferredOperations) {
			Assert.isPremiseValid(
				deferredOperation.getRequiredPersistenceServiceType().isInstance(this),
				() -> new EvitaInternalError("Incompatible deferred operation!")
			);
			//noinspection unchecked,rawtypes
			((DeferredStorageOperation)deferredOperation).execute(owner, transactionId, this);
		}
	}

	/**
	 * Returns a key compressor that contains indexes of keys assigned to a key comparable objects that are expensive
	 * to be stored duplicated during serialization.
	 */
	@Nonnull
	KeyCompressor getReadOnlyKeyCompressor();

	/**
	 * Returns true if the persistence service was closed.
	 */
	boolean isClosed();

	/**
	 * Closes the entity collection persistent storage.
	 */
	@Override
	void close();
}
