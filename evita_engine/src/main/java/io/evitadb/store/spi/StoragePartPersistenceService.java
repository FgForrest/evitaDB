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

package io.evitadb.store.spi;

import io.evitadb.store.model.PersistentStorageDescriptor;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.service.KeyCompressor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Closeable;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Persistent service defines shared contract for services that allow to work with persistent data storage based on
 * file offset index object. There is usually single file for one key EvitaDB object (catalog, entity-collection).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface StoragePartPersistenceService extends Closeable {

	/**
	 * Creates a transactional service for StoragePartPersistenceService.
	 * This method creates a transactional service using the provided transactionId.
	 *
	 * @param catalogVersion The version of the catalog the value is read from
	 * @param transactionId The UUID representing the transaction.
	 * @return The created StoragePartPersistenceService as a transactional service.
	 */
	@Nonnull
	StoragePartPersistenceService createTransactionalService(@Nonnull UUID transactionId);

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
	 * Reads container primarily from transactional memory and when the container is not present there (or transaction
	 * is not opened) reads it from the target {@link CatalogPersistenceService}.
	 *
	 * @param catalogVersion catalog version the value is changed in
	 * @param container      container to be stored
	 * @param <T>            type of the storage part container
	 * @return already or newly assigned {@link StoragePart#getStoragePartPK()} - primary key of the storage part
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
	PersistentStorageDescriptor flush(long catalogVersion);

	/**
	 * Flushes entire living data set to the target file. The file must exist and must be prepared for re-writing.
	 * File must not be used by any other process.
	 *
	 * @param newFilePath target file
	 */
	@Nonnull
	PersistentStorageDescriptor copySnapshotTo(@Nonnull Path newFilePath, long catalogVersion);

	/**
	 * Notifies the persistence service that the last reader that read this particular catalog version has exited.
	 * The caller also ensures there is no other reader reading the later (lower) catalog versions and thus that
	 * the entire history for this and previous versions can be purged from memory.
	 *
	 * @param minimalActiveCatalogVersion minimal catalog version that is still being used, NULL when there is no
	 *                                    active session
	 */
	void purgeHistoryEqualAndLaterThan(@Nullable Long minimalActiveCatalogVersion);

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
