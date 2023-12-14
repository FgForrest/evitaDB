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

package io.evitadb.store.catalog;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.ByteBufferOutput;
import com.esotericsoftware.kryo.io.Input;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.exception.EntityAlreadyRemovedException;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.EntityGroupFetch;
import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaRequest.RequirementContext;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.structure.BinaryEntity;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.predicate.AssociatedDataValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.AttributeValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.HierarchySerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.PriceContractSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceContractSerializablePredicate;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.core.Catalog;
import io.evitadb.core.EntityCollection;
import io.evitadb.core.buffer.BufferedChangeSet;
import io.evitadb.core.buffer.DataStoreTxMemoryBuffer;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.attribute.AttributeIndex;
import io.evitadb.index.attribute.ChainIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.attribute.UniqueIndex;
import io.evitadb.index.cardinality.CardinalityIndex;
import io.evitadb.index.facet.FacetIndex;
import io.evitadb.index.hierarchy.HierarchyIndex;
import io.evitadb.index.price.PriceListAndCurrencyPriceRefIndex;
import io.evitadb.index.price.PriceListAndCurrencyPriceSuperIndex;
import io.evitadb.index.price.PriceRefIndex;
import io.evitadb.index.price.PriceSuperIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.transactionalMemory.diff.DataSourceChanges;
import io.evitadb.store.entity.EntityFactory;
import io.evitadb.store.entity.EntityStoragePartConfigurer;
import io.evitadb.store.entity.model.entity.AssociatedDataStoragePart;
import io.evitadb.store.entity.model.entity.AssociatedDataStoragePart.EntityAssociatedDataKey;
import io.evitadb.store.entity.model.entity.AttributesStoragePart;
import io.evitadb.store.entity.model.entity.AttributesStoragePart.EntityAttributesSetKey;
import io.evitadb.store.entity.model.entity.EntityBodyStoragePart;
import io.evitadb.store.entity.model.entity.PricesStoragePart;
import io.evitadb.store.entity.model.entity.ReferencesStoragePart;
import io.evitadb.store.entity.serializer.EntitySchemaContext;
import io.evitadb.store.fileOffsetIndex.FileOffsetIndex;
import io.evitadb.store.fileOffsetIndex.FileOffsetIndexDescriptor;
import io.evitadb.store.fileOffsetIndex.model.FileOffsetIndexRecordTypeRegistry;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.kryo.VersionedKryo;
import io.evitadb.store.kryo.VersionedKryoFactory;
import io.evitadb.store.kryo.VersionedKryoKeyInputs;
import io.evitadb.store.model.PersistentStorageDescriptor;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.schema.SchemaKryoConfigurer;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.store.service.SharedClassesConfigurer;
import io.evitadb.store.spi.EntityCollectionPersistenceService;
import io.evitadb.store.spi.exception.PersistenceServiceClosed;
import io.evitadb.store.spi.model.EntityCollectionHeader;
import io.evitadb.store.spi.model.storageParts.index.*;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetchAll;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.store.spi.model.storageParts.index.PriceListAndCurrencySuperIndexStoragePart.computeUniquePartId;
import static io.evitadb.utils.Assert.isPremiseValid;
import static io.evitadb.utils.Assert.notNull;
import static java.util.Optional.ofNullable;

/**
 * DefaultEntityCollectionPersistenceService class encapsulates {@link Catalog} serialization to persistent storage and also deserializing
 * the catalog contents back.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Slf4j
public class DefaultEntityCollectionPersistenceService implements EntityCollectionPersistenceService {
	public static final byte[][] BYTE_TWO_DIMENSIONAL_ARRAY = new byte[0][];

	/**
	 * Memory key-value store for entities.
	 */
	private final FileOffsetIndex fileOffsetIndex;
	/**
	 * Contains reference to the catalog entity header collecting all crucial information about single entity collection.
	 * The catalog entity header is loaded in constructor and because it's immutable it needs to be replaced with each
	 * {@link #flush(long, Function)} call.
	 */
	@Nonnull @Getter
	private EntityCollectionHeader catalogEntityHeader;

	public DefaultEntityCollectionPersistenceService(
		@Nonnull Path entityCollectionFile,
		@Nonnull EntityCollectionHeader entityHeader,
		@Nonnull StorageOptions storageOptions,
		@Nonnull ObservableOutputKeeper observableOutputKeeper,
		@Nonnull FileOffsetIndexRecordTypeRegistry fileOffsetIndexRecordTypeRegistry,
		boolean supportsTransactions
	) {
		this.catalogEntityHeader = entityHeader;
		this.fileOffsetIndex = new FileOffsetIndex(
			entityCollectionFile,
			new FileOffsetIndexDescriptor(
				entityHeader,
				this.createTypeKryoInstance(),
				supportsTransactions
			),
			storageOptions,
			fileOffsetIndexRecordTypeRegistry,
			observableOutputKeeper
		);
	}

	public DefaultEntityCollectionPersistenceService(
		@Nonnull DefaultEntityCollectionPersistenceService previous,
		@Nonnull StorageOptions storageOptions,
		@Nonnull ObservableOutputKeeper observableOutputKeeper,
		@Nonnull FileOffsetIndexRecordTypeRegistry fileOffsetIndexRecordTypeRegistry,
		boolean supportsTransactions
	) {
		this.catalogEntityHeader = previous.catalogEntityHeader;
		this.fileOffsetIndex = new FileOffsetIndex(
			previous.fileOffsetIndex.getTargetFile(),
			new FileOffsetIndexDescriptor(
				previous.catalogEntityHeader,
				this.createTypeKryoInstance(),
				supportsTransactions
			),
			storageOptions,
			fileOffsetIndexRecordTypeRegistry,
			observableOutputKeeper
		);
	}

	@Override
	public boolean isNew() {
		return this.fileOffsetIndex.getFileOffsetIndexLocation() == null;
	}

	@Nullable
	@Override
	public Entity readEntity(int entityPrimaryKey, @Nonnull EvitaRequest evitaRequest, @Nonnull EntitySchema entitySchema, @Nonnull DataStoreTxMemoryBuffer<EntityIndexKey, EntityIndex, DataSourceChanges<EntityIndexKey, EntityIndex>> storageContainerBuffer) {
		if (fileOffsetIndex.isOperative()) {
			// provide passed schema during deserialization from binary form
			return EntitySchemaContext.executeWithSchemaContext(entitySchema, () -> {
				// fetch the main entity container
				final EntityBodyStoragePart entityStorageContainer = storageContainerBuffer.fetch(
					entityPrimaryKey, EntityBodyStoragePart.class
				);
				if (entityStorageContainer == null || entityStorageContainer.isMarkedForRemoval()) {
					// return null if not found
					return null;
				} else {
					return toEntity(entityStorageContainer, evitaRequest, entitySchema, storageContainerBuffer);
				}
			});
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	@Nullable
	@Override
	public BinaryEntity readBinaryEntity(
		int entityPrimaryKey,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull EvitaSessionContract session,
		@Nonnull Function<String, EntityCollection> entityCollectionFetcher,
		@Nonnull DataStoreTxMemoryBuffer<EntityIndexKey, EntityIndex, DataSourceChanges<EntityIndexKey, EntityIndex>> storageContainerBuffer
	) {
		if (fileOffsetIndex.isOperative()) {
			// provide passed schema during deserialization from binary form
			final EntitySchema entitySchema = entityCollectionFetcher.apply(evitaRequest.getEntityType()).getInternalSchema();
			return EntitySchemaContext.executeWithSchemaContext(entitySchema, () -> {
				// fetch the main entity container
				final byte[] entityStorageContainer = storageContainerBuffer.fetchBinary(
					entityPrimaryKey, EntityBodyStoragePart.class,
					this::serializeStoragePart
				);
				if (entityStorageContainer == null) {
					// return null if not found
					return null;
				} else {
					return toBinaryEntity(
						entityStorageContainer, evitaRequest, session, entityCollectionFetcher, storageContainerBuffer
					);
				}
			});
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	@Nonnull
	@Override
	public Entity enrichEntity(
		@Nonnull EntitySchema entitySchema,
		@Nonnull EntityDecorator entityDecorator,
		@Nonnull HierarchySerializablePredicate newHierarchyPredicate,
		@Nonnull AttributeValueSerializablePredicate newAttributePredicate,
		@Nonnull AssociatedDataValueSerializablePredicate newAssociatedDataPredicate,
		@Nonnull ReferenceContractSerializablePredicate newReferenceContractPredicate,
		@Nonnull PriceContractSerializablePredicate newPricePredicate,
		@Nonnull DataStoreTxMemoryBuffer<EntityIndexKey, EntityIndex, DataSourceChanges<EntityIndexKey, EntityIndex>> storageContainerBuffer
	) {
		if (fileOffsetIndex.isOperative()) {
			// provide passed schema during deserialization from binary form
			return EntitySchemaContext.executeWithSchemaContext(entitySchema, () -> {
				final int entityPrimaryKey = Objects.requireNonNull(entityDecorator.getPrimaryKey());

				// body part is fetched everytime - we need to at least test the version
				final EntityBodyStoragePart bodyPart = storageContainerBuffer.fetch(
					entityPrimaryKey, EntityBodyStoragePart.class
				);

				if (bodyPart == null || bodyPart.isMarkedForRemoval()) {
					throw new EntityAlreadyRemovedException(
						entityDecorator.getType(), entityPrimaryKey
					);
				}

				final boolean versionDiffers = bodyPart.getVersion() != entityDecorator.version();

				// fetch additional data if requested and not already present
				final ReferencesStoragePart referencesStorageContainer = fetchReferences(
					versionDiffers ? null : entityDecorator.getReferencePredicate(),
					newReferenceContractPredicate,
					() -> storageContainerBuffer.fetch(entityPrimaryKey, ReferencesStoragePart.class)
				);
				final PricesStoragePart priceStorageContainer = fetchPrices(
					versionDiffers ? null : entityDecorator.getPricePredicate(),
					newPricePredicate,
					() -> storageContainerBuffer.fetch(entityPrimaryKey, PricesStoragePart.class)
				);

				final List<AttributesStoragePart> attributesStorageContainers = fetchAttributes(
					entityPrimaryKey,
					versionDiffers ? null : entityDecorator.getAttributePredicate(),
					newAttributePredicate,
					bodyPart.getAttributeLocales(),
					key -> storageContainerBuffer.fetch(key, AttributesStoragePart.class, AttributesStoragePart::computeUniquePartId)
				);
				final List<AssociatedDataStoragePart> associatedDataStorageContainers = fetchAssociatedData(
					entityPrimaryKey,
					versionDiffers ? null : entityDecorator.getAssociatedDataPredicate(),
					newAssociatedDataPredicate,
					bodyPart.getAssociatedDataKeys(),
					key -> storageContainerBuffer.fetch(key, AssociatedDataStoragePart.class, AssociatedDataStoragePart::computeUniquePartId)
				);

				// if anything was fetched from the persistent storage
				if (versionDiffers) {
					// build the enriched entity from scratch
					return EntityFactory.createEntityFrom(
						entityDecorator.getDelegate().getSchema(),
						bodyPart,
						attributesStorageContainers,
						associatedDataStorageContainers,
						referencesStorageContainer,
						priceStorageContainer
					);
				} else if (referencesStorageContainer != null || priceStorageContainer != null ||
					!attributesStorageContainers.isEmpty() || !associatedDataStorageContainers.isEmpty()) {
					// and build the enriched entity as a new instance
					return EntityFactory.createEntityFrom(
						entityDecorator.getDelegate().getSchema(),
						entityDecorator.getDelegate(),
						bodyPart,
						attributesStorageContainers,
						associatedDataStorageContainers,
						referencesStorageContainer,
						priceStorageContainer
					);
				} else {
					// return original entity - nothing has been fetched
					return entityDecorator.getDelegate();
				}
			});
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	@Nonnull
	@Override
	public BinaryEntity enrichEntity(@Nonnull EntitySchema entitySchema, @Nonnull BinaryEntity entity, @Nonnull EvitaRequest evitaRequest, @Nonnull DataStoreTxMemoryBuffer<EntityIndexKey, EntityIndex, DataSourceChanges<EntityIndexKey, EntityIndex>> storageContainerBuffer) throws EntityAlreadyRemovedException {
		/* TOBEDONE https://github.com/FgForrest/evitaDB/issues/13 */
		return entity;
	}

	@Override
	public EntityIndex readEntityIndex(int entityIndexId, @Nonnull Supplier<EntitySchema> schemaSupplier, @Nonnull Supplier<PriceSuperIndex> temporalIndexAccessor, @Nonnull Supplier<PriceSuperIndex> superIndexAccessor) {
		if (fileOffsetIndex.isOperative()) {
			final EntityIndexStoragePart entityIndexCnt = fileOffsetIndex.get(entityIndexId, EntityIndexStoragePart.class);
			isPremiseValid(
				entityIndexCnt != null,
				"Entity index with PK `" + entityIndexId + "` was unexpectedly not found in the mem table!"
			);

			final Map<AttributeKey, UniqueIndex> uniqueIndexes = new HashMap<>();
			final Map<AttributeKey, FilterIndex> filterIndexes = new HashMap<>();
			final Map<AttributeKey, SortIndex> sortIndexes = new HashMap<>();
			final Map<AttributeKey, ChainIndex> chainIndexes = new HashMap<>();
			final Map<AttributeKey, CardinalityIndex> cardinalityIndexes = new HashMap<>();
			for (AttributeIndexStorageKey attributeIndexKey : entityIndexCnt.getAttributeIndexes()) {
				switch (attributeIndexKey.indexType()) {
					case UNIQUE -> fetchUniqueIndex(schemaSupplier.get().getName(), entityIndexId, fileOffsetIndex, uniqueIndexes, attributeIndexKey);
					case FILTER -> fetchFilterIndex(entityIndexId, fileOffsetIndex, filterIndexes, attributeIndexKey);
					case SORT -> fetchSortIndex(entityIndexId, fileOffsetIndex, sortIndexes, attributeIndexKey);
					case CHAIN -> fetchChainIndex(entityIndexId, fileOffsetIndex, chainIndexes, attributeIndexKey);
					case CARDINALITY -> fetchCardinalityIndex(entityIndexId, fileOffsetIndex, cardinalityIndexes, attributeIndexKey);
					default -> throw new EvitaInternalError("Unknown attribute index type: " + attributeIndexKey.indexType());
				}
			}

			final HierarchyIndex hierarchyIndex = fetchHierarchyIndex(entityIndexId, fileOffsetIndex, entityIndexCnt);
			final FacetIndex facetIndex = fetchFacetIndex(entityIndexId, fileOffsetIndex, entityIndexCnt);

			final EntityIndexType entityIndexType = entityIndexCnt.getEntityIndexKey().getType();
			// base on entity index type we either create GlobalEntityIndex or ReducedEntityIndex
			if (entityIndexType == EntityIndexType.GLOBAL) {
				final Map<PriceIndexKey, PriceListAndCurrencyPriceSuperIndex> priceIndexes = fetchPriceSuperIndexes(
					entityIndexId, entityIndexCnt.getPriceIndexes(), fileOffsetIndex
				);
				return new GlobalEntityIndex(
					entityIndexCnt.getPrimaryKey(),
					entityIndexCnt.getEntityIndexKey(),
					entityIndexCnt.getVersion(),
					schemaSupplier,
					entityIndexCnt.getEntityIds(),
					entityIndexCnt.getEntityIdsByLanguage(),
					new AttributeIndex(
						schemaSupplier.get().getName(), 
						uniqueIndexes, filterIndexes, sortIndexes, chainIndexes
					),
					new PriceSuperIndex(
						Objects.requireNonNull(entityIndexCnt.getInternalPriceIdSequence()),
						priceIndexes
					),
					hierarchyIndex,
					facetIndex
				);
			} else if (entityIndexType == EntityIndexType.REFERENCED_ENTITY_TYPE) {
				return new ReferencedTypeEntityIndex(
					entityIndexCnt.getPrimaryKey(),
					entityIndexCnt.getEntityIndexKey(),
					entityIndexCnt.getVersion(),
					schemaSupplier,
					entityIndexCnt.getEntityIds(),
					entityIndexCnt.getEntityIdsByLanguage(),
					new AttributeIndex(
						schemaSupplier.get().getName(), 
						uniqueIndexes, filterIndexes, sortIndexes, chainIndexes
					),
					hierarchyIndex,
					facetIndex,
					entityIndexCnt.getPrimaryKeyCardinality(),
					cardinalityIndexes
				);
			} else {
				final Map<PriceIndexKey, PriceListAndCurrencyPriceRefIndex> priceIndexes = fetchPriceRefIndexes(
					entityIndexId, entityIndexCnt.getPriceIndexes(), fileOffsetIndex, temporalIndexAccessor
				);
				return new ReducedEntityIndex(
					entityIndexCnt.getPrimaryKey(),
					entityIndexCnt.getEntityIndexKey(),
					entityIndexCnt.getVersion(),
					schemaSupplier,
					entityIndexCnt.getEntityIds(),
					entityIndexCnt.getEntityIdsByLanguage(),
					new AttributeIndex(
						schemaSupplier.get().getName(), uniqueIndexes, filterIndexes, sortIndexes, chainIndexes
					),
					new PriceRefIndex(priceIndexes, superIndexAccessor),
					hierarchyIndex,
					facetIndex
				);
			}
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	/**
	 * Returns count of entities of certain type in the target storage.
	 *
	 * <strong>Note:</strong> the count may not be accurate - it counts only already persisted containers to the
	 * {@link FileOffsetIndex} and doesn't take transactional memory into an account.
	 */
	@Override
	public <T extends StoragePart> int count(@Nonnull Class<T> containerClass) {
		if (fileOffsetIndex.isOperative()) {
			return fileOffsetIndex.count(containerClass);
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	/**
	 * Returns iterator that goes through all containers of certain type in the target storage.
	 *
	 * <strong>Note:</strong> the list may not be accurate - it only goes through already persisted containers to the
	 * {@link FileOffsetIndex} and doesn't take transactional memory into an account.
	 */
	@Override
	public @Nonnull Iterator<Entity> entityIterator(@Nonnull EntitySchema entitySchema, @Nonnull DataStoreTxMemoryBuffer<EntityIndexKey, EntityIndex, DataSourceChanges<EntityIndexKey, EntityIndex>> storageContainerBuffer) {
		if (fileOffsetIndex.isOperative()) {
			final EvitaRequest evitaRequest = new EvitaRequest(
				Query.query(
					collection(entitySchema.getName()),
					require(entityFetchAll())
				),
				OffsetDateTime.now(),
				Entity.class,
				null,
				EvitaRequest.CONVERSION_NOT_SUPPORTED
			);
			final byte recType = fileOffsetIndex.getIdForRecordType(EntityBodyStoragePart.class);
			return fileOffsetIndex
				.getEntries()
				.stream()
				.filter(it -> it.getKey().recordType() == recType)
				.map(it -> fileOffsetIndex.get(it.getValue(), EntityBodyStoragePart.class))
				.filter(Objects::nonNull)
				.map(it -> toEntity(it, evitaRequest, entitySchema, storageContainerBuffer))
				.iterator();
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	@Nonnull
	@Override
	public EntityCollectionHeader flush(long transactionId, @Nonnull Function<PersistentStorageDescriptor, EntityCollectionHeader> catalogEntityHeaderFactory) {
		if (fileOffsetIndex.isOperative()) {
			final long previousVersion = this.fileOffsetIndex.getVersion();
			final FileOffsetIndexDescriptor newDescriptor = this.fileOffsetIndex.flush(transactionId);
			// when versions are equal - nothing has changed, and we can reuse old header
			if (newDescriptor.getVersion() > previousVersion) {
				this.catalogEntityHeader = catalogEntityHeaderFactory.apply(newDescriptor);
			}

			return this.catalogEntityHeader;
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	@Override
	public void flushTrappedUpdates(@Nonnull BufferedChangeSet<EntityIndexKey, EntityIndex> bufferedChangeSet) {
		if (fileOffsetIndex.isOperative()) {
			// now store all entity trapped updates
			bufferedChangeSet.getTrappedUpdates()
				.forEach(it -> fileOffsetIndex.put(0L, it));
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	@Override
	public void delete() {
		if (fileOffsetIndex.isOperative()) {
			this.fileOffsetIndex.close();
			if (!this.fileOffsetIndex.getTargetFile().toFile().delete()) {
				throw new UnexpectedIOException(
					"Failed to delete file: " + this.fileOffsetIndex.getTargetFile(),
					"Failed to delete file!"
				);
			}
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	@Override
	public void close() {
		if (this.fileOffsetIndex.isOperative()) {
			this.fileOffsetIndex.close();
		}
	}

	@Override
	public boolean isClosed() {
		return !this.fileOffsetIndex.isOperative();
	}

	/**
	 * Creates {@link Kryo} instance that is usable for deserializing entity instances.
	 */
	public Function<VersionedKryoKeyInputs, VersionedKryo> createTypeKryoInstance() {
		return kryoKeyInputs -> VersionedKryoFactory.createKryo(
			kryoKeyInputs.version(),
			SchemaKryoConfigurer.INSTANCE
				.andThen(SharedClassesConfigurer.INSTANCE)
				.andThen(SchemaKryoConfigurer.INSTANCE)
				.andThen(new IndexStoragePartConfigurer(kryoKeyInputs.keyCompressor()))
				.andThen(new EntityStoragePartConfigurer(kryoKeyInputs.keyCompressor()))
		);
	}

	@Override
	public <T extends StoragePart> T getStoragePart(long storagePartPk, @Nonnull Class<T> containerType) {
		if (fileOffsetIndex.isOperative()) {
			return this.fileOffsetIndex.get(storagePartPk, containerType);
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	@Nullable
	@Override
	public <T extends StoragePart> byte[] getStoragePartAsBinary(long storagePartPk, @Nonnull Class<T> containerType) {
		if (fileOffsetIndex.isOperative()) {
			return this.fileOffsetIndex.getBinary(storagePartPk, containerType);
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	@Override
	public <T extends StoragePart> long putStoragePart(long storagePartPk, @Nonnull T container) {
		if (fileOffsetIndex.isOperative()) {
			return this.fileOffsetIndex.put(storagePartPk, container);
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	@Override
	public <T extends StoragePart> boolean removeStoragePart(long storagePartPk, @Nonnull Class<T> containerType) {
		if (fileOffsetIndex.isOperative()) {
			return fileOffsetIndex.remove(storagePartPk, containerType);
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	@Override
	public <T extends StoragePart> boolean containsStoragePart(long primaryKey, @Nonnull Class<T> containerType) {
		if (fileOffsetIndex.isOperative()) {
			return fileOffsetIndex.contains(primaryKey, containerType);
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	@Nonnull
	@Override
	public KeyCompressor getReadOnlyKeyCompressor() {
		if (fileOffsetIndex.isOperative()) {
			return this.fileOffsetIndex.getReadOnlyKeyCompressor();
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	/*
		PRIVATE METHODS
	 */

	@Nonnull
	private static Entity toEntity(
		@Nonnull EntityBodyStoragePart entityStorageContainer,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull EntitySchema entitySchema,
		@Nonnull DataStoreTxMemoryBuffer<EntityIndexKey, EntityIndex, DataSourceChanges<EntityIndexKey, EntityIndex>> storageContainerBuffer
	) {
		final int entityPrimaryKey = entityStorageContainer.getPrimaryKey();
		// load additional containers only when requested
		final ReferencesStoragePart referencesStorageContainer = fetchReferences(
			null, new ReferenceContractSerializablePredicate(evitaRequest),
			() -> storageContainerBuffer.fetch(entityPrimaryKey, ReferencesStoragePart.class)
		);
		final PricesStoragePart priceStorageContainer = fetchPrices(
			null, new PriceContractSerializablePredicate(evitaRequest, (Boolean) null),
			() -> storageContainerBuffer.fetch(entityPrimaryKey, PricesStoragePart.class)
		);

		final List<AttributesStoragePart> attributesStorageContainers = fetchAttributes(
			entityPrimaryKey, null, new AttributeValueSerializablePredicate(evitaRequest),
			entityStorageContainer.getAttributeLocales(),
			key -> storageContainerBuffer.fetch(key, AttributesStoragePart.class, AttributesStoragePart::computeUniquePartId)
		);
		final List<AssociatedDataStoragePart> associatedDataStorageContainers = fetchAssociatedData(
			entityPrimaryKey, null, new AssociatedDataValueSerializablePredicate(evitaRequest),
			entityStorageContainer.getAssociatedDataKeys(),
			key -> storageContainerBuffer.fetch(key, AssociatedDataStoragePart.class, AssociatedDataStoragePart::computeUniquePartId)
		);

		// and build the entity
		return EntityFactory.createEntityFrom(
			entitySchema,
			entityStorageContainer,
			attributesStorageContainers,
			associatedDataStorageContainers,
			referencesStorageContainer,
			priceStorageContainer
		);
	}

	@Nonnull
	private BinaryEntity toBinaryEntity(
		@Nonnull byte[] entityStorageContainer,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull EvitaSessionContract session,
		@Nonnull Function<String, EntityCollection> entityCollectionFetcher,
		@Nonnull DataStoreTxMemoryBuffer<EntityIndexKey, EntityIndex, DataSourceChanges<EntityIndexKey, EntityIndex>> storageContainerBuffer
	) {
		final EntitySchema entitySchema = EntitySchemaContext.getEntitySchema();
		final EntityBodyStoragePart deserializedEntityBody = deserialize(
			entityStorageContainer
		);

		final int entityPrimaryKey = deserializedEntityBody.getPrimaryKey();
		// load additional containers only when requested
		final byte[] priceStorageContainer = fetchPrices(
			null, new PriceContractSerializablePredicate(evitaRequest, (Boolean) null),
			() -> storageContainerBuffer.fetchBinary(
				entityPrimaryKey,
				PricesStoragePart.class,
				this::serializeStoragePart
			)
		);

		final List<byte[]> attributesStorageContainers = fetchAttributes(
			entityPrimaryKey, null, new AttributeValueSerializablePredicate(evitaRequest),
			deserializedEntityBody.getAttributeLocales(),
			attributesSetKey -> storageContainerBuffer.fetchBinary(
				attributesSetKey,
				AttributesStoragePart.class,
				AttributesStoragePart::computeUniquePartId,
				this::serializeStoragePart
			)
		);
		final List<byte[]> associatedDataStorageContainers = fetchAssociatedData(
			entityPrimaryKey, null, new AssociatedDataValueSerializablePredicate(evitaRequest),
			deserializedEntityBody.getAssociatedDataKeys(),
			associatedDataKey -> storageContainerBuffer.fetchBinary(
				associatedDataKey,
				AssociatedDataStoragePart.class,
				AssociatedDataStoragePart::computeUniquePartId,
				this::serializeStoragePart
			)
		);

		final Map<String, RequirementContext> referenceEntityFetch = evitaRequest.getReferenceEntityFetch();
		final AtomicReference<ReferencesStoragePart> referencesStoragePart = new AtomicReference<>();
		final byte[] referencesStorageContainer = fetchReferences(
			null, new ReferenceContractSerializablePredicate(evitaRequest),
			() -> {
				if (referenceEntityFetch.isEmpty()) {
					return storageContainerBuffer.fetchBinary(
						entityPrimaryKey, ReferencesStoragePart.class, this::serializeStoragePart
					);
				} else {
					final ReferencesStoragePart fetchedPart = storageContainerBuffer.fetch(
						entityPrimaryKey, ReferencesStoragePart.class
					);
					if (fetchedPart == null) {
						return null;
					} else {
						referencesStoragePart.set(fetchedPart);
						return serializeStoragePart(fetchedPart);
					}
				}
			}
		);

		final BinaryEntity[] referencedEntities = referencesStoragePart.get() == null ?
			new BinaryEntity[0] :
			referenceEntityFetch
				.entrySet()
				.stream()
				.flatMap(entry -> {
					final String referenceName = entry.getKey();
					final ReferenceSchema referenceSchema = entitySchema.getReferenceOrThrowException(referenceName);
					final RequirementContext requirementTuple = entry.getValue();
					final EntityFetch entityFetch = referenceSchema.isReferencedEntityTypeManaged() ?
						requirementTuple.entityFetch() : null;
					final EntityGroupFetch entityGroupFetch = referenceSchema.isReferencedGroupTypeManaged() && referenceSchema.getReferencedGroupType() != null ?
						requirementTuple.entityGroupFetch() : null;
					return Stream.concat(
						ofNullable(entityFetch)
							.map(
								requirement -> Arrays.stream(referencesStoragePart.get().getReferencedIds(referenceName))
									.mapToObj(
										it -> entityCollectionFetcher.apply(referenceSchema.getReferencedEntityType())
											.getBinaryEntity(it, evitaRequest.deriveCopyWith(referenceSchema.getReferencedEntityType(), entityFetch), session)
											.orElse(null)
									)
							)
							.orElse(Stream.empty()),
						ofNullable(entityGroupFetch)
							.map(
								requirement -> Arrays.stream(referencesStoragePart.get().getReferencedGroupIds(referenceName))
									.mapToObj(
										it -> entityCollectionFetcher.apply(referenceSchema.getReferencedGroupType())
											.getBinaryEntity(it, evitaRequest.deriveCopyWith(referenceSchema.getReferencedGroupType(), entityGroupFetch), session)
											.orElse(null)
									)
							)
							.orElse(Stream.empty())
					);
				})
				.filter(Objects::nonNull)
				.toArray(BinaryEntity[]::new);

		// and build the entity
		return new BinaryEntity(
			entitySchema,
			entityPrimaryKey,
			entityStorageContainer,
			attributesStorageContainers.toArray(BYTE_TWO_DIMENSIONAL_ARRAY),
			associatedDataStorageContainers.toArray(BYTE_TWO_DIMENSIONAL_ARRAY),
			priceStorageContainer,
			referencesStorageContainer,
			referencedEntities
		);
	}

	/**
	 * Method serializes passed {@link StoragePart} to a byte array.
	 */
	@Nonnull
	private <T extends StoragePart> byte[] serializeStoragePart(@Nonnull T storagePart) {
		return fileOffsetIndex.executeWithKryo(
			kryo -> {
				final ByteBufferOutput output = new ByteBufferOutput(8192, -1);
				kryo.writeObject(output, storagePart);
				return output.toBytes();
			}
		);
	}

	/**
	 * Method deserializes the binary form of {@link EntityBodyStoragePart}.
	 */
	@Nonnull
	private EntityBodyStoragePart deserialize(@Nonnull byte[] storagePart) {
		return fileOffsetIndex.executeWithKryo(
			kryo -> kryo.readObject(
				new Input(storagePart), EntityBodyStoragePart.class
			)
		);
	}

	/**
	 * Fetches {@link io.evitadb.index.facet.FacetIndex} from the {@link FileOffsetIndex} and returns it.
	 */
	@Nonnull
	private static FacetIndex fetchFacetIndex(int entityIndexId, @Nonnull FileOffsetIndex fileOffsetIndex, @Nonnull EntityIndexStoragePart entityIndexCnt) {
		final FacetIndex facetIndex;
		final Set<String> facetIndexes = entityIndexCnt.getFacetIndexes();
		if (facetIndexes.isEmpty()) {
			facetIndex = new FacetIndex();
		} else {
			final List<FacetIndexStoragePart> facetIndexParts = new ArrayList<>(facetIndexes.size());
			for (String referencedEntityType : facetIndexes) {
				final long primaryKey = FacetIndexStoragePart.computeUniquePartId(entityIndexId, referencedEntityType, fileOffsetIndex.getReadOnlyKeyCompressor());
				final FacetIndexStoragePart facetIndexStoragePart = fileOffsetIndex.get(primaryKey, FacetIndexStoragePart.class);
				isPremiseValid(
					facetIndexStoragePart != null,
					"Facet index with id " + entityIndexId + " (upid=" + primaryKey + ") and key " + referencedEntityType + " was not found in mem table!"
				);
				facetIndexParts.add(facetIndexStoragePart);
			}
			facetIndex = new FacetIndex(facetIndexParts);
		}
		return facetIndex;
	}

	/**
	 * Fetches {@link HierarchyIndex} from the {@link FileOffsetIndex} and returns it.
	 */
	@Nonnull
	private static HierarchyIndex fetchHierarchyIndex(int entityIndexId, @Nonnull FileOffsetIndex fileOffsetIndex, @Nonnull EntityIndexStoragePart entityIndexCnt) {
		final HierarchyIndex hierarchyIndex;
		if (entityIndexCnt.isHierarchyIndex()) {
			final HierarchyIndexStoragePart hierarchyIndexStoragePart = fileOffsetIndex.get(entityIndexId, HierarchyIndexStoragePart.class);
			isPremiseValid(
				hierarchyIndexStoragePart != null,
				"Hierarchy index with id " + entityIndexId + " was not found in mem table!"
			);
			hierarchyIndex = new HierarchyIndex(
				hierarchyIndexStoragePart.getRoots(),
				hierarchyIndexStoragePart.getLevelIndex(),
				hierarchyIndexStoragePart.getItemIndex(),
				hierarchyIndexStoragePart.getOrphans()
			);
		} else {
			hierarchyIndex = new HierarchyIndex();
		}
		return hierarchyIndex;
	}

	/**
	 * Fetches {@link SortIndex} from the {@link FileOffsetIndex} and puts it into the `sortIndexes` key-value index.
	 */
	private static void fetchSortIndex(int entityIndexId, @Nonnull FileOffsetIndex fileOffsetIndex, @Nonnull Map<AttributeKey, SortIndex> sortIndexes, @Nonnull AttributeIndexStorageKey attributeIndexKey) {
		final long primaryKey = AttributeIndexStoragePart.computeUniquePartId(entityIndexId, AttributeIndexType.SORT, attributeIndexKey.attribute(), fileOffsetIndex.getReadOnlyKeyCompressor());
		final SortIndexStoragePart sortIndexCnt = fileOffsetIndex.get(primaryKey, SortIndexStoragePart.class);
		isPremiseValid(
			sortIndexCnt != null,
			"Sort index with id " + entityIndexId + " with key " + attributeIndexKey.attribute() + " was not found in mem table!"
		);
		final AttributeKey attributeKey = sortIndexCnt.getAttributeKey();
		sortIndexes.put(
			attributeKey,
			new SortIndex(
				sortIndexCnt.getComparatorBase(),
				sortIndexCnt.getAttributeKey(),
				sortIndexCnt.getSortedRecords(),
				sortIndexCnt.getSortedRecordsValues(),
				sortIndexCnt.getValueCardinalities()
			)
		);
	}

	/**
	 * Fetches {@link ChainIndex} from the {@link FileOffsetIndex} and puts it into the `chainIndexes` key-value index.
	 */
	private static void fetchChainIndex(int entityIndexId, @Nonnull FileOffsetIndex fileOffsetIndex, @Nonnull Map<AttributeKey, ChainIndex> chainIndexes, @Nonnull AttributeIndexStorageKey attributeIndexKey) {
		final long primaryKey = AttributeIndexStoragePart.computeUniquePartId(entityIndexId, AttributeIndexType.CHAIN, attributeIndexKey.attribute(), fileOffsetIndex.getReadOnlyKeyCompressor());
		final ChainIndexStoragePart chainIndexCnt = fileOffsetIndex.get(primaryKey, ChainIndexStoragePart.class);
		isPremiseValid(
			chainIndexCnt != null,
			"Chain index with id " + entityIndexId + " with key " + attributeIndexKey.attribute() + " was not found in mem table!"
		);
		final AttributeKey attributeKey = chainIndexCnt.getAttributeKey();
		chainIndexes.put(
			attributeKey,
			new ChainIndex(
				chainIndexCnt.getAttributeKey(),
				chainIndexCnt.getChains(),
				chainIndexCnt.getElementStates()
			)
		);
	}

	/**
	 * Fetches {@link CardinalityIndex} from the {@link FileOffsetIndex} and puts it into the `cardinalityIndexes` key-value index.
	 */
	private static void fetchCardinalityIndex(int entityIndexId, @Nonnull FileOffsetIndex fileOffsetIndex, @Nonnull Map<AttributeKey, CardinalityIndex> cardinalityIndexes, @Nonnull AttributeIndexStorageKey attributeIndexKey) {
		final long primaryKey = AttributeIndexStoragePart.computeUniquePartId(entityIndexId, AttributeIndexType.CARDINALITY, attributeIndexKey.attribute(), fileOffsetIndex.getReadOnlyKeyCompressor());
		final CardinalityIndexStoragePart cardinalityIndexCnt = fileOffsetIndex.get(primaryKey, CardinalityIndexStoragePart.class);
		isPremiseValid(
			cardinalityIndexCnt != null,
			"Cardinality index with id " + entityIndexId + " with key " + attributeIndexKey.attribute() + " was not found in mem table!"
		);
		final AttributeKey attributeKey = cardinalityIndexCnt.getAttributeKey();
		cardinalityIndexes.put(
			attributeKey,
			cardinalityIndexCnt.getCardinalityIndex()
		);
	}

	/**
	 * Fetches {@link FilterIndex} from the {@link FileOffsetIndex} and puts it into the `filterIndexes` key-value index.
	 */
	private static void fetchFilterIndex(int entityIndexId, @Nonnull FileOffsetIndex fileOffsetIndex, @Nonnull Map<AttributeKey, FilterIndex> filterIndexes, @Nonnull AttributeIndexStorageKey attributeIndexKey) {
		final long primaryKey = AttributeIndexStoragePart.computeUniquePartId(entityIndexId, AttributeIndexType.FILTER, attributeIndexKey.attribute(), fileOffsetIndex.getReadOnlyKeyCompressor());
		final FilterIndexStoragePart filterIndexCnt = fileOffsetIndex.get(primaryKey, FilterIndexStoragePart.class);
		isPremiseValid(
			filterIndexCnt != null,
			"Filter index with id " + entityIndexId + " with key " + attributeIndexKey.attribute() + " was not found in mem table!"
		);
		final AttributeKey attributeKey = filterIndexCnt.getAttributeKey();
		filterIndexes.put(
			attributeKey,
			new FilterIndex(
				filterIndexCnt.getAttributeKey(),
				filterIndexCnt.getHistogram(),
				filterIndexCnt.getRangeIndex()
			)
		);
	}

	/**
	 * Fetches {@link UniqueIndex} from the {@link FileOffsetIndex} and puts it into the `uniqueIndexes` key-value index.
	 */
	private static void fetchUniqueIndex(@Nonnull String entityType, int entityIndexId, @Nonnull FileOffsetIndex fileOffsetIndex, @Nonnull Map<AttributeKey, UniqueIndex> uniqueIndexes, @Nonnull AttributeIndexStorageKey attributeIndexKey) {
		final long primaryKey = AttributeIndexStoragePart.computeUniquePartId(entityIndexId, AttributeIndexType.UNIQUE, attributeIndexKey.attribute(), fileOffsetIndex.getReadOnlyKeyCompressor());
		final UniqueIndexStoragePart uniqueIndexCnt = fileOffsetIndex.get(primaryKey, UniqueIndexStoragePart.class);
		isPremiseValid(
			uniqueIndexCnt != null,
			"Unique index with id " + entityIndexId + " with key " + attributeIndexKey.attribute() + " was not found in mem table!"
		);
		final AttributeKey attributeKey = uniqueIndexCnt.getAttributeKey();
		uniqueIndexes.put(
			attributeKey,
			new UniqueIndex(
				entityType, attributeKey,
				uniqueIndexCnt.getType(),
				uniqueIndexCnt.getUniqueValueToRecordId(),
				uniqueIndexCnt.getRecordIds()
			)
		);
	}

	/**
	 * Fetches {@link PriceListAndCurrencyPriceSuperIndex price indexes} from the {@link FileOffsetIndex} and returns key-value
	 * index of them.
	 */
	private static Map<PriceIndexKey, PriceListAndCurrencyPriceSuperIndex> fetchPriceSuperIndexes(int entityIndexId, @Nonnull Set<PriceIndexKey> priceIndexes, @Nonnull FileOffsetIndex fileOffsetIndex) {
		final Map<PriceIndexKey, PriceListAndCurrencyPriceSuperIndex> priceSuperIndexes = CollectionUtils.createHashMap(priceIndexes.size());
		for (PriceIndexKey priceIndexKey : priceIndexes) {
			final long primaryKey = computeUniquePartId(entityIndexId, priceIndexKey, fileOffsetIndex.getReadOnlyKeyCompressor());
			final PriceListAndCurrencySuperIndexStoragePart priceIndexCnt = fileOffsetIndex.get(primaryKey, PriceListAndCurrencySuperIndexStoragePart.class);
			isPremiseValid(
				priceIndexCnt != null,
				"Price index with id " + entityIndexId + " with key " + priceIndexKey + " was not found in mem table!"
			);
			priceSuperIndexes.put(
				priceIndexKey,
				new PriceListAndCurrencyPriceSuperIndex(
					priceIndexKey,
					priceIndexCnt.getValidityIndex(),
					priceIndexCnt.getPriceRecords()
				)
			);
		}
		return priceSuperIndexes;
	}

	/**
	 * Fetches {@link PriceListAndCurrencyPriceRefIndex price indexes} from the {@link FileOffsetIndex} and returns key-value
	 * index of them.
	 */
	private static Map<PriceIndexKey, PriceListAndCurrencyPriceRefIndex> fetchPriceRefIndexes(int entityIndexId, @Nonnull Set<PriceIndexKey> priceIndexes, @Nonnull FileOffsetIndex fileOffsetIndex, @Nonnull Supplier<PriceSuperIndex> superIndexAccessor) {
		final Map<PriceIndexKey, PriceListAndCurrencyPriceRefIndex> priceRefIndexes = CollectionUtils.createHashMap(priceIndexes.size());
		for (PriceIndexKey priceIndexKey : priceIndexes) {
			final long primaryKey = computeUniquePartId(entityIndexId, priceIndexKey, fileOffsetIndex.getReadOnlyKeyCompressor());
			final PriceListAndCurrencyRefIndexStoragePart priceIndexCnt = fileOffsetIndex.get(primaryKey, PriceListAndCurrencyRefIndexStoragePart.class);
			isPremiseValid(
				priceIndexCnt != null,
				"Price index with id " + entityIndexId + " with key " + priceIndexKey + " was not found in mem table!"
			);
			priceRefIndexes.put(
				priceIndexKey,
				new PriceListAndCurrencyPriceRefIndex(
					priceIndexKey,
					priceIndexCnt.getValidityIndex(),
					priceIndexCnt.getPriceIds(),
					pik -> superIndexAccessor.get().getPriceIndex(pik)
				)
			);
		}
		return priceRefIndexes;
	}

	/**
	 * Fetches reference container from FileOffsetIndex if it hasn't been already loaded before.
	 */
	@Nullable
	private static <T> T fetchReferences(
		@Nullable ReferenceContractSerializablePredicate previousReferenceContractPredicate,
		@Nonnull ReferenceContractSerializablePredicate newReferenceContractPredicate,
		@Nonnull Supplier<T> fetcher
	) {
		if ((previousReferenceContractPredicate == null || !previousReferenceContractPredicate.isRequiresEntityReferences()) &&
			newReferenceContractPredicate.isRequiresEntityReferences()
		) {
			return fetcher.get();
		} else {
			return null;
		}
	}

	/**
	 * Fetches prices container from FileOffsetIndex if it hasn't been already loaded before.
	 */
	@Nullable
	private static <T> T fetchPrices(
		@Nullable PriceContractSerializablePredicate previousPricePredicate,
		@Nonnull PriceContractSerializablePredicate newPricePredicate,
		@Nonnull Supplier<T> fetcher
	) {
		if ((previousPricePredicate == null || previousPricePredicate.getPriceContentMode() == PriceContentMode.NONE) &&
			newPricePredicate.getPriceContentMode() != PriceContentMode.NONE) {
			return fetcher.get();
		} else {
			return null;
		}
	}

	/**
	 * Fetches attributes container from FileOffsetIndex if it hasn't been already loaded before.
	 */
	@Nonnull
	private static <T> List<T> fetchAttributes(
		int entityPrimaryKey,
		@Nullable AttributeValueSerializablePredicate previousAttributePredicate,
		@Nonnull AttributeValueSerializablePredicate newAttributePredicate,
		@Nonnull Set<Locale> allAvailableLocales,
		@Nonnull Function<EntityAttributesSetKey, T> fetcher
	) {
		final List<T> attributesStorageContainers = new LinkedList<>();
		if (newAttributePredicate.isRequiresEntityAttributes()) {
			// we need to load global attributes' container (i.e. attributes not linked to any locale)
			final boolean firstRequest = previousAttributePredicate == null || !previousAttributePredicate.isRequiresEntityAttributes();
			if (firstRequest) {
				final EntityAttributesSetKey globalAttributeSetKey = new EntityAttributesSetKey(entityPrimaryKey, null);
				ofNullable(fetcher.apply(globalAttributeSetKey))
					.ifPresent(attributesStorageContainers::add);
			}
			// go through all alreadyFetchedLocales entity is known to have
			final Set<Locale> previouslyFetchedLanguages = ofNullable(previousAttributePredicate).map(AttributeValueSerializablePredicate::getAllLocales).orElse(null);
			final Set<Locale> newlyFetchedLanguages = newAttributePredicate.getAllLocales();
			final Predicate<Locale> fetchedPreviously = firstRequest ?
				locale -> false :
				locale -> previouslyFetchedLanguages != null && (previouslyFetchedLanguages.isEmpty() || previouslyFetchedLanguages.contains(locale));
			final Predicate<Locale> fetchedNewly = locale -> newlyFetchedLanguages != null && (newlyFetchedLanguages.isEmpty() || newlyFetchedLanguages.contains(locale));
			allAvailableLocales
				.stream()
				// filter them according to language (if no language is requested, all languages match)
				.filter(it -> !fetchedPreviously.test(it) && fetchedNewly.test(it))
				// now fetch it from the storage
				.map(it -> {
					final EntityAttributesSetKey localeSpecificAttributeSetKey = new EntityAttributesSetKey(entityPrimaryKey, it);
					// there may be no attributes in specified language
					return fetcher.apply(localeSpecificAttributeSetKey);
				})
				// filter out null values (of non-existent containers)
				.filter(Objects::nonNull)
				// non null values add to output list
				.forEach(attributesStorageContainers::add);
		}
		return attributesStorageContainers;
	}

	/**
	 * Fetches associated data container(s) from FileOffsetIndex if it hasn't (they haven't) been already loaded before.
	 */
	@Nonnull
	private static <T> List<T> fetchAssociatedData(
		int entityPrimaryKey,
		@Nullable AssociatedDataValueSerializablePredicate previousAssociatedDataValuePredicate,
		@Nonnull AssociatedDataValueSerializablePredicate newAssociatedDataValuePredicate,
		@Nonnull Set<AssociatedDataKey> allAssociatedDataKeys,
		@Nonnull Function<EntityAssociatedDataKey, T> fetcher
	) {
		// if there is single request for associated data
		if (newAssociatedDataValuePredicate.isRequiresEntityAssociatedData()) {
			final Set<AssociatedDataKey> missingAssociatedDataSet = new HashSet<>(allAssociatedDataKeys.size());
			final Set<Locale> requestedLocales = newAssociatedDataValuePredicate.getLocales();
			final Set<String> requestedAssociatedDataSet = newAssociatedDataValuePredicate.getAssociatedDataSet();
			final Predicate<AssociatedDataKey> wasNotFetched =
				associatedDataKey -> previousAssociatedDataValuePredicate == null || !previousAssociatedDataValuePredicate.wasFetched(associatedDataKey);
			// construct missing associated data keys
			if (requestedAssociatedDataSet.isEmpty()) {
				// add all not yet loaded keys
				allAssociatedDataKeys
					.stream()
					.filter(associatedDataKey -> !associatedDataKey.localized() || (requestedLocales != null && (requestedLocales.isEmpty() || requestedLocales.contains(associatedDataKey.locale()))))
					.filter(wasNotFetched)
					.forEach(missingAssociatedDataSet::add);
			} else {
				for (String associatedDataName : requestedAssociatedDataSet) {
					final AssociatedDataKey globalKey = new AssociatedDataKey(associatedDataName);
					if (allAssociatedDataKeys.contains(globalKey) && wasNotFetched.test(globalKey)) {
						missingAssociatedDataSet.add(globalKey);
					}
					if (requestedLocales != null) {
						for (Locale requestedLocale : requestedLocales) {
							final AssociatedDataKey localizedKey = new AssociatedDataKey(associatedDataName, requestedLocale);
							if (allAssociatedDataKeys.contains(localizedKey) && wasNotFetched.test(localizedKey)) {
								missingAssociatedDataSet.add(localizedKey);
							}
						}
					}
				}
			}

			return missingAssociatedDataSet
				.stream()
				.map(it -> {
					// fetch missing associated data from underlying storage
					final T associatedData = fetcher.apply(
						new EntityAssociatedDataKey(entityPrimaryKey, it.associatedDataName(), it.locale())
					);
					// since we know all available keys from the entity header there always should be looked up container
					notNull(associatedData, "Associated data " + it + " was expected in the storage, but none was found!");
					return associatedData;
				})
				.collect(Collectors.toList());
		}
		return Collections.emptyList();
	}

}
