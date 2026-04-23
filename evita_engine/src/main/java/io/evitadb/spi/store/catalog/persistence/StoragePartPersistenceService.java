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

package io.evitadb.spi.store.catalog.persistence;

import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.OutputStream;
import java.util.UUID;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

/**
 * Core CRUD interface for reading, writing, and removing {@link StoragePart} records in a single evitaDB binary data
 * file. The underlying physical storage is an offset-index file: a sequential, append-only file where each record is
 * addressed by its byte offset. The offset-index descriptor (captured as {@code S extends StorageDescriptor}) tracks
 * all live record locations and is re-persisted during every {@link #flush(long)} call.
 *
 * There is typically one `StoragePartPersistenceService` instance per key evitaDB object:
 * - one for the catalog data file (accessed via {@link CatalogStoragePartPersistenceService})
 * - one per entity collection data file (accessed via
 *   {@link EntityCollectionPersistenceService#getStoragePartPersistenceService()})
 *
 * **Read path**: methods like {@link #getStoragePart} first consult the in-progress transaction's memory buffer
 * (if a transaction is open), then fall back to the persistent offset-index file. This ensures that uncommitted
 * changes within the same transaction are visible to subsequent reads in that transaction.
 *
 * **Write path**: {@link #putStoragePart} and {@link #removeStoragePart} stage changes in the transaction's memory
 * buffer. Changes are flushed to the offset-index file by calling {@link #flush(long)}, which also returns an
 * updated {@link StorageDescriptor} for the caller to persist in the catalog bootstrap.
 *
 * **Snapshot support**: `copySnapshotTo` writes the complete live data set to an output stream, enabling backup
 * and catalog duplication.
 * writes the complete live data set to an output stream, enabling backup and catalog duplication.
 *
 * @param <S> the concrete {@link StorageDescriptor} type produced by the underlying offset-index implementation,
 *            carrying file location metadata that must be stored in the catalog header
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface StoragePartPersistenceService<S extends StorageDescriptor> extends Closeable {

	/**
	 * Creates a transactional view of this service that isolates all reads and writes to the given transaction scope.
	 * Changes written through the returned service are buffered in the transaction's memory layer and are not visible
	 * to other readers until the transaction is committed and the changes are merged into the main storage file.
	 *
	 * The returned service shares the same underlying persistent file with this instance. Closing the returned service
	 * does not close the parent service.
	 *
	 * @param transactionId the UUID that uniquely identifies the open transaction; used to scope the in-memory buffer
	 * @return a transactional wrapper around this service bound to the given transaction
	 */
	@Nonnull
	StoragePartPersistenceService<S> createTransactionalService(@Nonnull UUID transactionId);

	/**
	 * Reads container primarily from transactional memory and when the container is not present there (or transaction
	 * is not opened) reads it from the target {@link CatalogPersistenceService}.
	 *
	 * @param catalogVersion the current version of the catalog the value is read from
	 * @param storagePartPk primary key of the storage part
	 * @param containerType type of the storage part container
	 * @param <T>           type of the storage part container
	 * @return container contents in deserialized form
	 */
	@Nullable
	<T extends StoragePart> T getStoragePart(long catalogVersion, long storagePartPk, @Nonnull Class<T> containerType);

	/**
	 * Reads container primarily from transactional memory and when the container is not present there (or transaction
	 * is not opened) reads it from the target {@link CatalogPersistenceService}.
	 *
	 * @param catalogVersion the current version of the catalog the value is read from
	 * @param storagePartPk primary key of the storage part
	 * @param containerType type of the storage part container
	 * @param <T>           type of the storage part container
	 * @return container contents as byte array in binary form
	 */
	@Nullable
	<T extends StoragePart> byte[] getStoragePartAsBinary(long catalogVersion, long storagePartPk, @Nonnull Class<T> containerType);

	/**
	 * Persists the given storage part into the transaction's memory buffer (or directly into the persistent storage
	 * if no transaction is open). If the part has not yet been assigned a primary key,
	 * {@link StoragePart#computeUniquePartIdAndSet(KeyCompressor)} is called internally to assign one before writing.
	 *
	 * @param catalogVersion the catalog version in which this change is being made; used to label the written record
	 * @param container      the storage part to store; its primary key will be computed and set if not already present
	 * @param <T>            the concrete type of the storage part
	 * @return the already-assigned or newly-computed {@link StoragePart#getStoragePartPK()} of the stored part
	 */
	<T extends StoragePart> long putStoragePart(long catalogVersion, @Nonnull T container);

	/**
	 * Removes container from the transactional memory. This method should be used only for container, that has
	 * no uniqueId assigned so far (e.g. they haven't been stored yet).
	 *
	 * @param catalogVersion catalog version the value is changed in
	 * @param storagePartPk primary key of the storage part
	 * @param containerType type of the storage part container
	 * @param <T>           type of the storage part container
	 * @return true if the container was removed from the transactional memory
	 */
	<T extends StoragePart> boolean removeStoragePart(long catalogVersion, long storagePartPk, @Nonnull Class<T> containerType);

	/**
	 * Returns true if persistent storage contains non-removed storage part of particular primary key and container
	 * type.
	 *
	 * @param catalogVersion the current version of the catalog the value is read from
	 * @param primaryKey    primary key of the storage part
	 * @param containerType type of the storage part container
	 * @param <T>           type of the storage part container
	 * @return true if persistent storage contains non-removed storage part of particular primary key and container
	 */
	<T extends StoragePart> boolean containsStoragePart(long catalogVersion, long primaryKey, @Nonnull Class<T> containerType);

	/**
	 * Returns stream of all storage parts of the specified container type.
	 *
	 * @param containerType type of the storage part container
	 * @param <T>           type of the storage part container
	 * @return stream of all storage parts of the specified container type
	 */
	@Nonnull
	<T extends StoragePart> Stream<T> getEntryStream(@Nonnull Class<T> containerType);

	/**
	 * Counts the number of storage parts of the specified container type.
	 *
	 * @param catalogVersion the version of the catalog the value is read from
	 * @return the number of storage parts of the specified container type
	 */
	int countStorageParts(long catalogVersion);

	/**
	 * Counts the number of storage parts of the specified container type.
	 *
	 * @param catalogVersion the version of the catalog the value is read from
	 * @param containerType the type of the storage part containers
	 * @param <T>           the type of the storage part container
	 * @return the number of storage parts of the specified container type
	 */
	<T extends StoragePart> int countStorageParts(long catalogVersion, @Nonnull Class<T> containerType);

	/**
	 * Method serializes passed {@link StoragePart} to a byte array.
	 *
	 * @param storagePart {@link StoragePart} to be serialized
	 * @param <T>         type of the storage part container
	 * @return serialized {@link StoragePart}
	 */
	@Nonnull
	<T extends StoragePart> byte[] serializeStoragePart(@Nonnull T storagePart);

	/**
	 * Method deserializes the binary form of {@link StoragePart}.
	 *
	 * @param storagePart   binary form of {@link StoragePart}
	 * @param containerType type of the storage part container
	 * @param <T>           type of the storage part container
	 * @return deserialized {@link StoragePart}
	 */
	@Nonnull
	<T extends StoragePart> T deserializeStoragePart(@Nonnull byte[] storagePart, @Nonnull Class<T> containerType);

	/**
	 * Returns a key compressor that contains indexes of keys assigned to a key comparable objects that are expensive
	 * to be stored duplicated during serialization.
	 */
	@Nonnull
	KeyCompressor getReadOnlyKeyCompressor();

	/**
	 * Returns the version of the persistent storage stored with each flush operation.
	 *
	 * @return version of the persistent storage
	 */
	long getVersion();

	/**
	 * We need to forget all volatile data when the data written to catalog aren't going to be committed (incorporated
	 * in the final state). Usually the data are immediately written to the disk and are volatile until
	 * {@link #flush(long)} is called. But those data can be read within particular transaction from the volatile
	 * storage and we need to forget them when the transaction is rolled back.
	 */
	void forgetVolatileData();

	/**
	 * Returns the storage descriptor that contains crucial information for successful reopening of the persistent
	 * storage.
	 *
	 * @param catalogVersion current version of the catalog
	 * @return storage descriptor
	 */
	@Nonnull
	S flush(long catalogVersion);

	/**
	 * Flushes entire living data set to the target output stream. If the output stream represents a file, it must exist
	 * and must be prepared for re-writing. File must not be used by any other process.
	 *
	 * @param catalogVersion      new catalog version
	 * @param outputStream        target output stream to write data to
	 * @param updatedStorageParts storage parts that should be replaced with updated values in the snapshot
	 */
	@Nonnull
	S copySnapshotTo(
		long catalogVersion,
		@Nonnull OutputStream outputStream,
		@Nullable IntConsumer progressConsumer,
		@Nullable StoragePart... updatedStorageParts
	);

	/**
	 * Notifies the persistence service that the last reader that read this particular catalog version has exited.
	 * The caller also ensures there is no other reader reading the later (lower) catalog versions and thus that
	 * the entire history for this and previous versions can be purged from memory.
	 *
	 * @param lastKnownMinimalActiveVersion minimal catalog version that is still being used
	 */
	void purgeHistoryOlderThan(long lastKnownMinimalActiveVersion);

	/**
	 * Checks whether the persistence storage is already present and filled with data.
	 *
	 * @return true if the persistence storage is already present and filled with data
	 */
	boolean isNew();

	/**
	 * Returns true if the persistence service was closed.
	 *
	 * @return true if the persistence service was closed
	 */
	boolean isClosed();

	/**
	 * Closes the entity collection persistent storage.
	 */
	@Override
	void close();

}
