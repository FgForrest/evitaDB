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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.EntityAlreadyRemovedException;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.structure.BinaryEntity;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.References.ChunkTransformerAccessor;
import io.evitadb.api.requestResponse.data.structure.predicate.AssociatedDataValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.AttributeValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.HierarchySerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.PriceContractSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceContractSerializablePredicate;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.buffer.DataStoreReader;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.index.EntityIndex;
import io.evitadb.spi.store.catalog.header.model.EntityCollectionHeader;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.EntityStoragePart;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * Persistence service for a single entity collection within a catalog. It provides the full read/write surface for
 * the data stored in one entity collection data file:
 *
 * - **Entity bodies**: loading and enriching entities in both domain-object and binary (wire-format) representations
 * - **Entity indexes**: reconstructing individual {@link io.evitadb.index.EntityIndex} instances from storage parts
 * - **Bulk operations**: flushing trapped in-memory changes to persistent storage during initial load or WAL replay
 *
 * An instance is obtained via {@link CatalogPersistenceService#getOrCreateEntityCollectionPersistenceService} and
 * lives as long as the owning {@link EntityCollection}. It must be closed with {@link #close()} when the collection
 * is removed or the catalog shuts down. Unflushed in-memory changes are lost if the service is closed before
 * {@link #flushTrappedUpdates(long, io.evitadb.core.buffer.TrappedChanges, java.util.function.IntConsumer)} is called.
 *
 * @param <S> the concrete {@link StorageDescriptor} type produced by the underlying offset-index implementation
 * @param <T> the concrete {@link EntityCollectionHeader} type carrying per-collection metadata
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public non-sealed interface EntityCollectionPersistenceService<S extends StorageDescriptor, T extends EntityCollectionHeader> extends RichPersistenceService {

	/**
	 * Returns underlying {@link StoragePartPersistenceService} which this instance uses for {@link StoragePart}
	 * persistence.
	 *
	 * @return underlying {@link StoragePartPersistenceService}
	 */
	@Nonnull
	StoragePartPersistenceService<S> getStoragePartPersistenceService();

	/**
	 * Returns current instance of {@link EntityCollectionHeader}. The header is initialized in the instance constructor
	 * and (because it's immutable) is exchanged with each {@link #flushTrappedUpdates(long, TrappedChanges, IntConsumer)}
	 * method call.
	 */
	@Nonnull
	T getEntityCollectionHeader();

	/**
	 * Reads entity from persistent storage by its primary key, applying the content requirements expressed in
	 * `evitaRequest`. Only the storage parts that are required by the request are fetched from the underlying data
	 * store, so callers should pass the most restrictive request possible to avoid unnecessary I/O.
	 *
	 * The returned {@link EntityWithFetchCount} wraps the deserialized entity together with the number of I/O reads
	 * and bytes consumed during fetching — useful for query-level observability.
	 *
	 * @param catalogVersion  the catalog version from which the entity should be read
	 * @param entityPrimaryKey the primary key of the entity to load
	 * @param evitaRequest    the client request carrying {@link EntityContentRequire} constraints that control which
	 *                        entity parts (attributes, references, prices, …) are fetched
	 * @param entitySchema    the schema of the entity type; used for deserialization and validation
	 * @param dataStoreReader the low-level storage reader through which individual storage parts are accessed
	 * @return a wrapper with the deserialized entity and I/O statistics, or `null` when no entity with the given
	 *         primary key exists in the storage at the specified catalog version
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
	 * Reads entity from persistent storage by its primary key in binary (wire-format) form. Unlike
	 * {@link #readEntity} this variant skips full Java deserialization and returns the raw Kryo-serialized bytes
	 * for each requested entity part wrapped in a {@link BinaryEntity}. This is used by gRPC and other transport
	 * layers that forward the binary payload directly to the client without materializing domain objects on the
	 * server side, thereby saving CPU and heap allocation costs.
	 *
	 * @param catalogVersion          the catalog version from which the entity should be read
	 * @param entityPrimaryKey        the primary key of the entity to load
	 * @param evitaRequest            the client request carrying {@link EntityContentRequire} constraints
	 * @param entitySchema            the schema of the entity type; used for structural decisions during partial read
	 * @param session                 the active session, used to resolve locale and other session-scoped settings
	 * @param entityCollectionFetcher a function that resolves an entity collection by entity type name; used when
	 *                                referenced entity bodies are requested inline
	 * @param dataStoreReader         the low-level storage reader for accessing raw storage parts
	 * @return a wrapper with the binary entity and I/O statistics, or `null` when no entity with the given primary
	 *         key exists at the specified catalog version
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
	 * Loads additional data to an already partially-fetched entity (represented by `entityDecorator`) according to
	 * the new requirements expressed by the predicate arguments. Because entities are immutable, the result is always
	 * a freshly created {@link EntityDecorator} that wraps the original entity body together with the newly loaded
	 * parts.
	 *
	 * The predicate parameters describe the *new* (wider) set of content that should be visible after enrichment.
	 * The implementation fetches only the parts that are not yet loaded in the existing decorator and merges them
	 * into the result, avoiding redundant I/O.
	 *
	 * @param catalogVersion                the catalog version the decorator was originally loaded from
	 * @param entityDecorator               the already (partially) loaded entity to enrich
	 * @param newHierarchyPredicate         predicate controlling whether the entity's placement in hierarchy is included
	 * @param newAttributePredicate         predicate controlling which attribute values should be present after enrichment
	 * @param newAssociatedDataPredicate    predicate controlling which associated data values should be present
	 * @param newReferenceContractPredicate predicate controlling which references and their attributes are included
	 * @param newPricePredicate             predicate controlling which price records are included
	 * @param dataStoreReader               the low-level storage reader used to fetch missing parts
	 * @param referenceChunkTransformer     accessor that applies pagination / ordering to reference chunks
	 * @return the enriched entity together with I/O statistics for the enrichment operation
	 * @throws EntityAlreadyRemovedException when the entity has been removed between the initial fetch and enrichment
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
	 * Returns the count of entity primary key records persisted in the storage at the given catalog version.
	 *
	 * **Note:** the count may not be accurate — it reflects only data already written to the persistent storage and
	 * does not account for uncommitted changes held in transactional memory. Use this method for informational
	 * purposes and capacity estimation, not for precise cardinality queries.
	 *
	 * @param catalogVersion  the catalog version to query
	 * @param dataStoreReader the low-level storage reader for accessing persisted entity bodies
	 * @return the number of persisted entity body storage parts; may be lower than the true entity count if
	 *         recently added entities have not yet been flushed to disk
	 */
	int countEntities(
		long catalogVersion,
		@Nonnull DataStoreReader dataStoreReader
	);

	/**
	 * Returns `true` if the entity collection storage file contains no active (non-removed) entity data at the given
	 * catalog version. An empty collection file may still exist on disk as a placeholder; this method allows callers
	 * to detect that case without iterating over the full file.
	 *
	 * @param catalogVersion  the catalog version to inspect
	 * @param dataStoreReader the low-level storage reader used to check for active records
	 * @return `true` if there are no live entity records in the storage at the given version
	 */
	boolean isEmpty(
		long catalogVersion,
		@Nonnull DataStoreReader dataStoreReader
	);

	/**
	 * Loads additional data to an already partially-fetched binary entity according to the {@link EntityContentRequire}
	 * constraints carried in `evitaRequest`. The binary entity stays in its serialized (wire-format) byte representation
	 * throughout the operation — no full Java deserialization occurs. The result is a new {@link BinaryEntity} that
	 * merges the previously fetched parts with the newly loaded ones.
	 *
	 * @param catalogVersion  the catalog version the binary entity was originally loaded from
	 * @param entitySchema    the schema of the entity type, used for structural decisions during partial read
	 * @param entity          the already (partially) loaded binary entity to enrich
	 * @param evitaRequest    the client request specifying which additional parts should be present after enrichment
	 * @param dataStoreReader the low-level storage reader for fetching missing binary storage parts
	 * @return the enriched binary entity together with I/O statistics for the enrichment operation
	 * @throws EntityAlreadyRemovedException when the entity has been removed between the initial fetch and enrichment
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
	@Nonnull
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
	 * Closes the entity collection persistent storage. If you don't call {@link #flushTrappedUpdates(long, TrappedChanges, IntConsumer)}
	 * or {@link #flushTrappedUpdates(long, TrappedChanges, IntConsumer)}   you'll lose the data in the buffers.
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
