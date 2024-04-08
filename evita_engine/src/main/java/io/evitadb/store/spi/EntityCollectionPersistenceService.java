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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.EntityAlreadyRemovedException;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.structure.BinaryEntity;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.predicate.AssociatedDataValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.AttributeValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.HierarchySerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.PriceContractSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceContractSerializablePredicate;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.EntityCollection;
import io.evitadb.core.buffer.DataStoreChanges;
import io.evitadb.core.buffer.DataStoreIndexChanges;
import io.evitadb.core.buffer.DataStoreMemoryBuffer;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.price.PriceSuperIndex;
import io.evitadb.store.model.PersistentStorageDescriptor;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.spi.model.EntityCollectionHeader;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * This interface represents a link between {@link io.evitadb.api.EntityCollectionContract} and its persistent storage.
 * The interface contains all methods necessary for fetching or persisting entity collection contents to/from durable
 * storage. The contents represent either the entity records themselves or entity indexes and other auxiliary data
 * structures required to allow fast entity lookups in entity collection.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public non-sealed interface EntityCollectionPersistenceService extends PersistenceService<EntityIndexKey, EntityIndex> {

	/**
	 * Returns underlying {@link StoragePartPersistenceService} which this instance uses for {@link StoragePart}
	 * persistence.
	 * @return underlying {@link StoragePartPersistenceService}
	 */
	@Nonnull
	StoragePartPersistenceService getStoragePartPersistenceService();

	/**
	 * Returns current instance of {@link EntityCollectionHeader}. The header is initialized in the instance constructor
	 * and (because it's immutable) is exchanged with each {@link #flushTrappedUpdates(long, DataStoreIndexChanges)}
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
	Entity readEntity(
		long catalogVersion,
		int entityPrimaryKey,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull EntitySchema entitySchema,
		@Nonnull DataStoreMemoryBuffer<EntityIndexKey, EntityIndex, DataStoreChanges<EntityIndexKey, EntityIndex>> storageContainerBuffer
	);

	/**
	 * Reads entity from persistent storage by its primary key.
	 * Requirements of type {@link EntityContentRequire} in `evitaRequest` are taken into an account. Passed `fileOffsetIndex`
	 * is used for reading data from underlying data store.
	 */
	@Nullable
	BinaryEntity readBinaryEntity(
		long catalogVersion,
		int entityPrimaryKey,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull EvitaSessionContract session,
		@Nonnull Function<String, EntityCollection> entityCollectionFetcher,
		@Nonnull DataStoreMemoryBuffer<EntityIndexKey, EntityIndex, DataStoreChanges<EntityIndexKey, EntityIndex>> storageContainerBuffer
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
	Entity enrichEntity(
		long catalogVersion,
		@Nonnull EntitySchema entitySchema,
		@Nonnull EntityDecorator entityDecorator,
		@Nonnull HierarchySerializablePredicate newHierarchyPredicate,
		@Nonnull AttributeValueSerializablePredicate newAttributePredicate,
		@Nonnull AssociatedDataValueSerializablePredicate newAssociatedDataPredicate,
		@Nonnull ReferenceContractSerializablePredicate newReferenceContractPredicate,
		@Nonnull PriceContractSerializablePredicate newPricePredicate,
		@Nonnull DataStoreMemoryBuffer<EntityIndexKey, EntityIndex, DataStoreChanges<EntityIndexKey, EntityIndex>> storageContainerBuffer
	) throws EntityAlreadyRemovedException;

	/**
	 * Returns count of entities of certain type in the target storage.
	 *
	 * <strong>Note:</strong> the count may not be accurate - it counts only already persisted containers to the
	 * persistent storage and doesn't take transactional memory into an account.
	 */
	int countEntities(
		long catalogVersion,
		@Nonnull DataStoreMemoryBuffer<EntityIndexKey, EntityIndex, DataStoreChanges<EntityIndexKey, EntityIndex>> storageContainerBuffer
	);

	/**
	 * Returns TRUE if the storage file is effectively entity - having no active data in it.
	 */
	boolean isEmpty(
		long catalogVersion,
		@Nonnull DataStoreMemoryBuffer<EntityIndexKey, EntityIndex, DataStoreChanges<EntityIndexKey, EntityIndex>> storageContainerBuffer
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
	BinaryEntity enrichEntity(
		long catalogVersion,
		@Nonnull EntitySchema entitySchema,
		@Nonnull BinaryEntity entity,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull DataStoreMemoryBuffer<EntityIndexKey, EntityIndex, DataStoreChanges<EntityIndexKey, EntityIndex>> storageContainerBuffer
	) throws EntityAlreadyRemovedException;

	/**
	 * Method reconstructs entity index from underlying containers.
	 */
	@Nullable
	EntityIndex readEntityIndex(
		long catalogVersion,
		int entityIndexId,
		@Nonnull Supplier<EntitySchema> schemaSupplier,
		@Nonnull Supplier<PriceSuperIndex> temporalIndexAccessor,
		@Nonnull Supplier<PriceSuperIndex> superIndexAccessor
	);

	/**
	 * Flushes entire living data set to the target file. The file must exist and must be prepared for re-writing.
	 * File must not be used by any other process.
	 *
	 * @param newFilePath    target file
	 * @param catalogVersion new catalog version
	 */
	@Nonnull
	PersistentStorageDescriptor copySnapshotTo(@Nonnull Path newFilePath, long catalogVersion);

	/**
	 * Closes the entity collection persistent storage. If you don't call {@link #flushTrappedUpdates(long, DataStoreIndexChanges)}
	 * or {@link #flushTrappedUpdates(long, DataStoreIndexChanges)}  you'll lose the data in the buffers.
	 */
	@Override
	void close();
}
