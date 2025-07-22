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

package io.evitadb.store.spi;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.EntityAlreadyRemovedException;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.structure.BinaryEntity;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.Entity.ChunkTransformerAccessor;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.predicate.AssociatedDataValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.AttributeValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.HierarchySerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.PriceContractSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceContractSerializablePredicate;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.EntityCollection;
import io.evitadb.core.buffer.DataStoreChanges;
import io.evitadb.core.buffer.DataStoreReader;
import io.evitadb.index.EntityIndex;
import io.evitadb.store.model.EntityStoragePart;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.spi.model.EntityCollectionHeader;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.OptionalInt;
import java.util.function.Function;

/**
 * This interface represents a link between {@link io.evitadb.api.EntityCollectionContract} and its persistent storage.
 * The interface contains all methods necessary for fetching or persisting entity collection contents to/from durable
 * storage. The contents represent either the entity records themselves or entity indexes and other auxiliary data
 * structures required to allow fast entity lookups in entity collection.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public non-sealed interface EntityCollectionPersistenceService extends PersistenceService {

	/**
	 * Returns underlying {@link StoragePartPersistenceService} which this instance uses for {@link StoragePart}
	 * persistence.
	 *
	 * @return underlying {@link StoragePartPersistenceService}
	 */
	@Nonnull
	StoragePartPersistenceService getStoragePartPersistenceService();

	/**
	 * Returns current instance of {@link EntityCollectionHeader}. The header is initialized in the instance constructor
	 * and (because it's immutable) is exchanged with each {@link #flushTrappedUpdates(long, DataStoreChanges)}
	 * method call.
	 */
	@Nonnull
	EntityCollectionHeader getEntityCollectionHeader();

	/**
	 * Reads entity from persistent storage by its primary key.
	 * Requirements of type {@link EntityContentRequire} in `evitaRequest` are taken into an account. Passed `fileOffsetIndex`
	 * is used for reading data from underlying data store.
	 */
	@Nullable
	EntityWithFetchCount readEntity(
		long catalogVersion,
		int entityPrimaryKey,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull EntitySchema entitySchema,
		@Nonnull DataStoreReader dataStoreReader
	);

	/**
	 * Uses already created / fetched storage parts to construct an entity object that would comply with passed requirements
	 * in `evitaRequest`. If any of storage parts is missing, it's fetched from the underlying data store.
	 *
	 * @param catalogVersion the version of the catalog
	 * @param entityPrimaryKey the primary key of the entity
	 * @param evitaRequest the request containing requirements for the entity
	 * @param entitySchema the schema of the entity
	 * @param dataStoreReader the data store reader for accessing storage parts
	 * @param storageParts the parts of the entity storage
	 * @return an object containing the entity and the fetch count
	 */
	@Nonnull
	EntityWithFetchCount toEntity(
		long catalogVersion,
		int entityPrimaryKey,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull EntitySchema entitySchema,
		@Nonnull DataStoreReader dataStoreReader,
		@Nonnull EntityStoragePart... storageParts
	);

	/**
	 * Reads entity from persistent storage by its primary key.
	 * Requirements of type {@link EntityContentRequire} in `evitaRequest` are taken into an account. Passed `fileOffsetIndex`
	 * is used for reading data from underlying data store.
	 */
	@Nullable
	BinaryEntityWithFetchCount readBinaryEntity(
		long catalogVersion,
		int entityPrimaryKey,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull EntitySchema entitySchema,
		@Nonnull EvitaSessionContract session,
		@Nonnull Function<String, EntityCollection> entityCollectionFetcher,
		@Nonnull DataStoreReader dataStoreReader
	);

	/**
	 * Loads additional data to existing entity according to requirements of type {@link EntityContentRequire}
	 * in `evitaRequest`. Passed `fileOffsetIndex` is used for reading data from underlying data store.
	 * Since entity is immutable object - enriched instance is a new instance based on previous entity that contains
	 * additional data.
	 *
	 * @throws EntityAlreadyRemovedException when the entity has been already removed
	 */
	@Nonnull
	EntityWithFetchCount enrichEntity(
		long catalogVersion,
		@Nonnull EntityDecorator entityDecorator,
		@Nonnull HierarchySerializablePredicate newHierarchyPredicate,
		@Nonnull AttributeValueSerializablePredicate newAttributePredicate,
		@Nonnull AssociatedDataValueSerializablePredicate newAssociatedDataPredicate,
		@Nonnull ReferenceContractSerializablePredicate newReferenceContractPredicate,
		@Nonnull PriceContractSerializablePredicate newPricePredicate,
		@Nonnull DataStoreReader dataStoreReader,
		@Nonnull ChunkTransformerAccessor referenceChunkTransformer
	) throws EntityAlreadyRemovedException;

	/**
	 * Returns count of entities of certain type in the target storage.
	 *
	 * <strong>Note:</strong> the count may not be accurate - it counts only already persisted containers to the
	 * persistent storage and doesn't take transactional memory into an account.
	 */
	int countEntities(
		long catalogVersion,
		@Nonnull DataStoreReader dataStoreReader
	);

	/**
	 * Returns TRUE if the storage file is effectively entity - having no active data in it.
	 */
	boolean isEmpty(
		long catalogVersion,
		@Nonnull DataStoreReader dataStoreReader
	);

	/**
	 * Loads additional data to existing entity according to requirements of type {@link EntityContentRequire}
	 * in `evitaRequest`. Passed `fileOffsetIndex` is used for reading data from underlying data store.
	 * Since entity is immutable object - enriched instance is a new instance based on previous entity that contains
	 * additional data.
	 *
	 * @throws EntityAlreadyRemovedException when the entity has been already removed
	 */
	@Nonnull
	BinaryEntityWithFetchCount enrichEntity(
		long catalogVersion,
		@Nonnull EntitySchema entitySchema,
		@Nonnull BinaryEntity entity,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull DataStoreReader dataStoreReader
	) throws EntityAlreadyRemovedException;

	/**
	 * Method reconstructs entity index from underlying containers.
	 */
	@Nullable
	EntityIndex readEntityIndex(
		long catalogVersion,
		int entityIndexId,
		@Nonnull EntitySchema entitySchema
	);

	/**
	 * Returns the size of the entity collection on disk in bytes.
	 * @return size of the entity collection on disk in bytes
	 */
	long getSizeOnDiskInBytes();

	/**
	 * Fetches the last assigned price id from the global index (if this is present in the entity collection storage
	 * file). This method is only temporary and will be removed in the future.
	 *
	 * @return the last assigned price id from the global index
	 * @deprecated connected with old storage format, will be removed in the future
	 */
	@Deprecated(since = "2024.11", forRemoval = true)
	@Nonnull
	OptionalInt fetchLastAssignedInternalPriceIdFromGlobalIndex(long catalogVersion, int entityIndexId);

	/**
	 * Closes the entity collection persistent storage. If you don't call {@link #flushTrappedUpdates(long, DataStoreChanges)}
	 * or {@link #flushTrappedUpdates(long, DataStoreChanges)}   you'll lose the data in the buffers.
	 */
	@Override
	void close();

	/**
	 * Record contains information about entity and the number of I/O operation necessary to fetch it.
	 *
	 * @param entity         fetched entity
	 * @param ioFetchCount   number of I/O operations necessary to fetch the entity
	 * @param ioFetchedBytes number of bytes fetched from underlying storage to load the entity
	 */
	record EntityWithFetchCount(
		@Nonnull Entity entity,
		int ioFetchCount,
		int ioFetchedBytes
	) {
	}

	/**
	 * Record contains information about binary entity and the number of I/O operation necessary to fetch it.
	 *
	 * @param entity         fetched binary entity
	 * @param ioFetchCount   number of I/O operations necessary to fetch the entity
	 * @param ioFetchedBytes number of bytes fetched from underlying storage to load the entity
	 */
	record BinaryEntityWithFetchCount(
		@Nonnull BinaryEntity entity,
		int ioFetchCount,
		int ioFetchedBytes
	) {
	}

}
