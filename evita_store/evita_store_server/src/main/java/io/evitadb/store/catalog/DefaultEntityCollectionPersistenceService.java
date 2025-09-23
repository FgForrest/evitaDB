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

package io.evitadb.store.catalog;

import com.esotericsoftware.kryo.Kryo;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.exception.AttributeNotFoundException;
import io.evitadb.api.exception.EntityAlreadyRemovedException;
import io.evitadb.api.exception.EntityMissingException;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.EntityGroupFetch;
import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaRequest.RequirementContext;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.structure.BinaryEntity;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.References.ChunkTransformerAccessor;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.data.structure.predicate.AssociatedDataValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.AttributeValueSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.HierarchySerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.PriceContractSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceContractSerializablePredicate;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.core.Catalog;
import io.evitadb.core.CatalogConsumersListener;
import io.evitadb.core.EntityCollection;
import io.evitadb.core.buffer.DataStoreChanges.RemovedStoragePart;
import io.evitadb.core.buffer.DataStoreReader;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.metric.event.storage.DataFileCompactEvent;
import io.evitadb.core.metric.event.storage.FileType;
import io.evitadb.core.metric.event.storage.OffsetIndexHistoryKeptEvent;
import io.evitadb.core.metric.event.storage.OffsetIndexNonFlushedEvent;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
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
import io.evitadb.store.entity.EntityFactory;
import io.evitadb.store.entity.EntityStoragePartConfigurer;
import io.evitadb.store.entity.model.entity.AssociatedDataStoragePart;
import io.evitadb.store.entity.model.entity.AssociatedDataStoragePart.EntityAssociatedDataKey;
import io.evitadb.store.entity.model.entity.AttributesStoragePart;
import io.evitadb.store.entity.model.entity.AttributesStoragePart.EntityAttributesSetKey;
import io.evitadb.store.entity.model.entity.EntityBodyStoragePart;
import io.evitadb.store.entity.model.entity.PricesStoragePart;
import io.evitadb.store.entity.model.entity.ReferencesStoragePart;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.kryo.VersionedKryo;
import io.evitadb.store.kryo.VersionedKryoFactory;
import io.evitadb.store.kryo.VersionedKryoKeyInputs;
import io.evitadb.store.model.EntityStoragePart;
import io.evitadb.store.model.PersistentStorageDescriptor;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.offsetIndex.OffsetIndex;
import io.evitadb.store.offsetIndex.OffsetIndex.NonFlushedBlock;
import io.evitadb.store.offsetIndex.OffsetIndexDescriptor;
import io.evitadb.store.offsetIndex.io.CatalogOffHeapMemoryManager;
import io.evitadb.store.offsetIndex.io.WriteOnlyFileHandle;
import io.evitadb.store.offsetIndex.model.OffsetIndexRecordTypeRegistry;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.schema.SchemaKryoConfigurer;
import io.evitadb.store.service.SharedClassesConfigurer;
import io.evitadb.store.spi.EntityCollectionPersistenceService;
import io.evitadb.store.spi.HeaderInfoSupplier;
import io.evitadb.store.spi.StoragePartPersistenceService;
import io.evitadb.store.spi.chunk.ServerChunkTransformerAccessor;
import io.evitadb.store.spi.model.EntityCollectionHeader;
import io.evitadb.store.spi.model.reference.CollectionFileReference;
import io.evitadb.store.spi.model.storageParts.index.*;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.store.wal.TransactionalStoragePartPersistenceService;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.store.spi.CatalogPersistenceService.getEntityCollectionDataStoreFileNamePattern;
import static io.evitadb.store.spi.model.storageParts.index.PriceListAndCurrencySuperIndexStoragePart.computeUniquePartId;
import static io.evitadb.utils.Assert.isPremiseValid;
import static io.evitadb.utils.Assert.notNull;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * DefaultEntityCollectionPersistenceService class encapsulates {@link Catalog} serialization to persistent storage and
 * also deserializing the catalog contents back.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Slf4j
public class DefaultEntityCollectionPersistenceService implements EntityCollectionPersistenceService, CatalogConsumersListener {
	public static final byte[][] BYTE_TWO_DIMENSIONAL_ARRAY = new byte[0][];
	/**
	 * Factory function that configures new instance of the versioned kryo factory.
	 */
	private static final Function<VersionedKryoKeyInputs, VersionedKryo> VERSIONED_KRYO_FACTORY = kryoKeyInputs -> VersionedKryoFactory.createKryo(
		kryoKeyInputs.version(),
		SchemaKryoConfigurer.INSTANCE
			.andThen(SharedClassesConfigurer.INSTANCE)
			.andThen(SchemaKryoConfigurer.INSTANCE)
			.andThen(new IndexStoragePartConfigurer(kryoKeyInputs.keyCompressor()))
			.andThen(new EntityStoragePartConfigurer(kryoKeyInputs.keyCompressor()))
	);
	/**
	 * The pre-initialized empty storage parts array.
	 */
	private static final EntityStoragePart[] EMPTY_STORAGE_PARTS = new EntityStoragePart[0];

	/**
	 * Contains reference to the target file where the entity collection is stored.
	 */
	private final CollectionFileReference entityCollectionFileReference;
	/**
	 * Contains path to the target file where the entity collection is stored.
	 */
	@Nonnull
	private final Path entityCollectionFile;
	/**
	 * Represents a registry for offset index record types and is propagated to internal {@link #storagePartPersistenceService}.
	 * It can be also retrieved and is shared with its wrapping implementation.
	 *
	 * @see TransactionalStoragePartPersistenceService
	 */
	@Nonnull @Getter
	private final OffsetIndexRecordTypeRegistry offsetIndexRecordTypeRegistry;
	/**
	 * ObservableOutputKeeper is used to track {@link io.evitadb.store.kryo.ObservableOutput} tied to particular file
	 * and is propagated to internal {@link #storagePartPersistenceService}. It can be also retrieved and is shared with its
	 * wrapping implementation.
	 */
	@Nonnull @Getter
	private final ObservableOutputKeeper observableOutputKeeper;
	/**
	 * The storage part persistence service implementation.
	 */
	@Nonnull @Getter
	private final OffsetIndexStoragePartPersistenceService storagePartPersistenceService;
	/**
	 * Contains reference to the catalog entity header collecting all crucial information about a single entity collection.
	 * The catalog entity header is loaded in the constructor, and because it's immutable it needs to be replaced with
	 * each {@link #flush(long, HeaderInfoSupplier)}  call.
	 */
	@Nonnull @Getter
	private EntityCollectionHeader entityCollectionHeader;
	/**
	 * Contains information about the time the non-flushed block was reported.
	 */
	private long lastReportTimestamp;

	@Nonnull
	private static Optional<EntityWithFetchCount> toEntity(
		long catalogVersion,
		int entityPrimaryKey,
		@Nonnull EntityStoragePart[] storageParts,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull EntitySchema entitySchema,
		@Nonnull DataStoreReader dataStoreReader
	) {
		// fetch the main entity container
		final EntityBodyStoragePart entityStorageContainer = Arrays.stream(storageParts)
			.filter(EntityBodyStoragePart.class::isInstance)
			.map(EntityBodyStoragePart.class::cast)
			.findFirst()
			.orElseGet(() -> dataStoreReader.fetch(
				catalogVersion, entityPrimaryKey, EntityBodyStoragePart.class
			));
		if (entityStorageContainer == null || entityStorageContainer.isMarkedForRemoval()) {
			// return null if not found
			return empty();
		} else {
			// the initial value is 1 because we've already fetched the `entityStorageContainer`
			final IoFetchStatistics ioFetchStatistics = new IoFetchStatistics();
			ioFetchStatistics.record(entityStorageContainer);

			// load additional containers only when requested
			final ReferencesStoragePart referencesStorageContainer = fetchReferences(
				null, new ReferenceContractSerializablePredicate(evitaRequest),
				() -> Arrays.stream(storageParts)
					.filter(ReferencesStoragePart.class::isInstance)
					.map(ReferencesStoragePart.class::cast)
					.findFirst()
					.orElseGet(
						() -> ioFetchStatistics.record(
							dataStoreReader.fetch(catalogVersion, entityPrimaryKey, ReferencesStoragePart.class)
						)
					)
			);
			final PricesStoragePart priceStorageContainer = fetchPrices(
				null, new PriceContractSerializablePredicate(evitaRequest, (Boolean) null),
				() -> Arrays.stream(storageParts)
					.filter(PricesStoragePart.class::isInstance)
					.map(PricesStoragePart.class::cast)
					.findFirst()
					.orElseGet(
						() -> ioFetchStatistics.record(
							dataStoreReader.fetch(catalogVersion, entityPrimaryKey, PricesStoragePart.class)
						)
					)
			);

			final List<AttributesStoragePart> attributesStorageContainers = fetchAttributes(
				entityPrimaryKey, null, new AttributeValueSerializablePredicate(evitaRequest),
				entityStorageContainer.getAttributeLocales(),
				key -> Arrays.stream(storageParts)
					.filter(AttributesStoragePart.class::isInstance)
					.map(AttributesStoragePart.class::cast)
					.filter(it -> it.getAttributeSetKey().equals(key))
					.findFirst()
					.orElseGet(
						() -> ioFetchStatistics.record(
							dataStoreReader.fetch(
								catalogVersion, key, AttributesStoragePart.class,
								AttributesStoragePart::computeUniquePartId
							)
						)
					)
			);
			final List<AssociatedDataStoragePart> associatedDataStorageContainers = fetchAssociatedData(
				entityPrimaryKey, null, new AssociatedDataValueSerializablePredicate(evitaRequest),
				entityStorageContainer.getAssociatedDataKeys(),
				key -> Arrays.stream(storageParts)
					.filter(AssociatedDataStoragePart.class::isInstance)
					.map(AssociatedDataStoragePart.class::cast)
					.filter(it -> it.getAssociatedDataKey().equals(key))
					.findFirst()
					.orElseGet(
						() -> ioFetchStatistics.record(
							dataStoreReader.fetch(
								catalogVersion, key,
								AssociatedDataStoragePart.class, AssociatedDataStoragePart::computeUniquePartId
							)
						)
					)
			);

			// and build the entity
			return of(
				new EntityWithFetchCount(
					EntityFactory.createEntityFrom(
						entitySchema,
						entityStorageContainer,
						attributesStorageContainers,
						associatedDataStorageContainers,
						referencesStorageContainer,
						priceStorageContainer,
						new ServerChunkTransformerAccessor(evitaRequest)
					),
					ioFetchStatistics.getIoFetchCount(),
					ioFetchStatistics.getIoFetchedBytes()
				)
			);
		}
	}

	/**
	 * Fetches {@link io.evitadb.index.facet.FacetIndex} from the {@link OffsetIndex} and returns it.
	 */
	@Nonnull
	private static FacetIndex fetchFacetIndex(
		long catalogVersion,
		int entityIndexId,
		@Nonnull StoragePartPersistenceService persistenceService,
		@Nonnull EntityIndexStoragePart entityIndexCnt) {
		final FacetIndex facetIndex;
		final Set<String> facetIndexes = entityIndexCnt.getFacetIndexes();
		if (facetIndexes.isEmpty()) {
			facetIndex = new FacetIndex();
		} else {
			final List<FacetIndexStoragePart> facetIndexParts = new ArrayList<>(facetIndexes.size());
			for (String referencedEntityType : facetIndexes) {
				final long primaryKey = FacetIndexStoragePart.computeUniquePartId(entityIndexId, referencedEntityType, persistenceService.getReadOnlyKeyCompressor());
				final FacetIndexStoragePart facetIndexStoragePart = persistenceService.getStoragePart(catalogVersion, primaryKey, FacetIndexStoragePart.class);
				isPremiseValid(
					facetIndexStoragePart != null,
					"Facet index with id " + entityIndexId + " (id=" + primaryKey + ") and key " + referencedEntityType + " was not found in mem table!"
				);
				facetIndexParts.add(facetIndexStoragePart);
			}
			facetIndex = new FacetIndex(facetIndexParts);
		}
		return facetIndex;
	}

	/**
	 * Fetches {@link HierarchyIndex} from the {@link OffsetIndex} and returns it.
	 */
	@Nonnull
	private static HierarchyIndex fetchHierarchyIndex(
		long catalogVersion,
		int entityIndexId,
		@Nonnull StoragePartPersistenceService persistenceService,
		@Nonnull EntityIndexStoragePart entityIndexCnt
	) {
		final HierarchyIndex hierarchyIndex;
		if (entityIndexCnt.isHierarchyIndex()) {
			final HierarchyIndexStoragePart hierarchyIndexStoragePart = persistenceService.getStoragePart(catalogVersion, entityIndexId, HierarchyIndexStoragePart.class);
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
	 * Fetches {@link SortIndex} from the {@link OffsetIndex} and puts it into the `sortIndexes` key-value index.
	 */
	private static void fetchSortIndex(
		long catalogVersion,
		int entityIndexId,
		@Nonnull StoragePartPersistenceService persistenceService,
		@Nonnull Map<AttributeKey, SortIndex> sortIndexes,
		@Nullable RepresentativeReferenceKey referenceKey,
		@Nonnull AttributeIndexStorageKey attributeIndexKey
	) {
		final long primaryKey = AttributeIndexStoragePart.computeUniquePartId(entityIndexId, AttributeIndexType.SORT, attributeIndexKey.attribute(), persistenceService.getReadOnlyKeyCompressor());
		final SortIndexStoragePart sortIndexCnt = persistenceService.getStoragePart(catalogVersion, primaryKey, SortIndexStoragePart.class);
		isPremiseValid(
			sortIndexCnt != null,
			"Sort index with id " + entityIndexId + " with key " + attributeIndexKey.attribute() + " was not found in mem table!"
		);
		final AttributeKey attributeKey = sortIndexCnt.getAttributeKey();
		sortIndexes.put(
			attributeKey,
			new SortIndex(
				sortIndexCnt.getComparatorBase(),
				referenceKey,
				sortIndexCnt.getAttributeKey(),
				sortIndexCnt.getSortedRecords(),
				sortIndexCnt.getSortedRecordsValues(),
				sortIndexCnt.getValueCardinalities()
			)
		);
	}

	/**
	 * Fetches {@link ChainIndex} from the {@link OffsetIndex} and puts it into the `chainIndexes` key-value index.
	 */
	private static void fetchChainIndex(
		long catalogVersion,
		int entityIndexId,
		@Nonnull StoragePartPersistenceService persistenceService,
		@Nonnull Map<AttributeKey, ChainIndex> chainIndexes,
		@Nullable RepresentativeReferenceKey referenceKey,
		@Nonnull AttributeIndexStorageKey attributeIndexKey
	) {
		final long primaryKey = AttributeIndexStoragePart.computeUniquePartId(entityIndexId, AttributeIndexType.CHAIN, attributeIndexKey.attribute(), persistenceService.getReadOnlyKeyCompressor());
		final ChainIndexStoragePart chainIndexCnt = persistenceService.getStoragePart(catalogVersion, primaryKey, ChainIndexStoragePart.class);
		isPremiseValid(
			chainIndexCnt != null,
			"Chain index with id " + entityIndexId + " with key " + attributeIndexKey.attribute() + " was not found in mem table!"
		);
		final AttributeKey attributeKey = chainIndexCnt.getAttributeKey();
		chainIndexes.put(
			attributeKey,
			new ChainIndex(
				referenceKey,
				chainIndexCnt.getAttributeKey(),
				chainIndexCnt.getChains(),
				chainIndexCnt.getElementStates()
			)
		);
	}

	/**
	 * Fetches {@link CardinalityIndex} from the {@link OffsetIndex} and puts it into the `cardinalityIndexes` key-value index.
	 */
	private static void fetchCardinalityIndex(
		long catalogVersion,
		int entityIndexId,
		@Nonnull StoragePartPersistenceService persistenceService,
		@Nonnull Map<AttributeKey, CardinalityIndex> cardinalityIndexes,
		@Nonnull AttributeIndexStorageKey attributeIndexKey
	) {
		final long primaryKey = AttributeIndexStoragePart.computeUniquePartId(entityIndexId, AttributeIndexType.CARDINALITY, attributeIndexKey.attribute(), persistenceService.getReadOnlyKeyCompressor());
		final CardinalityIndexStoragePart cardinalityIndexCnt = persistenceService.getStoragePart(catalogVersion, primaryKey, CardinalityIndexStoragePart.class);
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
	 * Fetches {@link FilterIndex} from the {@link OffsetIndex} and puts it into the `filterIndexes` key-value index.
	 */
	private static void fetchFilterIndex(
		long catalogVersion,
		int entityIndexId,
		@Nonnull StoragePartPersistenceService persistenceService,
		@Nonnull Map<AttributeKey, FilterIndex> filterIndexes,
		@Nonnull AttributeIndexStorageKey attributeIndexKey,
		@SuppressWarnings("rawtypes")
		@Nonnull Function<AttributeKey, Class> attributeTypeSupplier
	) {
		final long primaryKey = AttributeIndexStoragePart.computeUniquePartId(entityIndexId, AttributeIndexType.FILTER, attributeIndexKey.attribute(), persistenceService.getReadOnlyKeyCompressor());
		final FilterIndexStoragePart filterIndexCnt = persistenceService.getStoragePart(catalogVersion, primaryKey, FilterIndexStoragePart.class);
		isPremiseValid(
			filterIndexCnt != null,
			"Filter index with id " + entityIndexId + " with key " + attributeIndexKey.attribute() + " was not found in mem table!"
		);
		final AttributeKey attributeKey = filterIndexCnt.getAttributeKey();
		/* TOBEDONE #538 - remove with new versions */
		//noinspection unchecked
		final Class<?> attributeType = ofNullable(filterIndexCnt.getAttributeType())
			.orElseGet(() -> attributeTypeSupplier.apply(attributeKey));
		filterIndexes.put(
			attributeKey,
			new FilterIndex(
				filterIndexCnt.getAttributeKey(),
				filterIndexCnt.getHistogramPoints(),
				filterIndexCnt.getRangeIndex(),
				attributeType,
				filterIndexCnt.getAttributeType() == null
			)
		);
	}

	/**
	 * Fetches {@link UniqueIndex} from the {@link OffsetIndex} and puts it into the `uniqueIndexes` key-value index.
	 */
	private static void fetchUniqueIndex(
		long catalogVersion,
		int entityIndexId,
		@Nonnull String entityType,
		@Nonnull StoragePartPersistenceService persistenceService,
		@Nonnull Map<AttributeKey, UniqueIndex> uniqueIndexes,
		@Nonnull AttributeIndexStorageKey attributeIndexKey
	) {
		final long primaryKey = AttributeIndexStoragePart.computeUniquePartId(
			entityIndexId,
			AttributeIndexType.UNIQUE,
			attributeIndexKey.attribute(),
			persistenceService.getReadOnlyKeyCompressor()
		);
		final UniqueIndexStoragePart uniqueIndexCnt = persistenceService.getStoragePart(catalogVersion, primaryKey, UniqueIndexStoragePart.class);
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
	 * Fetches {@link PriceListAndCurrencyPriceSuperIndex price indexes} from the {@link OffsetIndex} and returns key-value
	 * index of them.
	 */
	private static Map<PriceIndexKey, PriceListAndCurrencyPriceSuperIndex> fetchPriceSuperIndexes(
		long catalogVersion,
		int entityIndexId,
		@Nonnull Set<PriceIndexKey> priceIndexes,
		@Nonnull StoragePartPersistenceService persistenceService
	) {
		final Map<PriceIndexKey, PriceListAndCurrencyPriceSuperIndex> priceSuperIndexes = CollectionUtils.createHashMap(priceIndexes.size());
		for (PriceIndexKey priceIndexKey : priceIndexes) {
			final long primaryKey = computeUniquePartId(entityIndexId, priceIndexKey, persistenceService.getReadOnlyKeyCompressor());
			final PriceListAndCurrencySuperIndexStoragePart priceIndexCnt = persistenceService.getStoragePart(catalogVersion, primaryKey, PriceListAndCurrencySuperIndexStoragePart.class);
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
	 * Fetches {@link PriceListAndCurrencyPriceRefIndex price indexes} from the {@link OffsetIndex} and returns key-value
	 * index of them.
	 */
	private static Map<PriceIndexKey, PriceListAndCurrencyPriceRefIndex> fetchPriceRefIndexes(
		long catalogVersion,
		int entityIndexId,
		@Nonnull Scope scope,
		@Nonnull Set<PriceIndexKey> priceIndexes,
		@Nonnull StoragePartPersistenceService persistenceService
	) {
		final Map<PriceIndexKey, PriceListAndCurrencyPriceRefIndex> priceRefIndexes = CollectionUtils.createHashMap(priceIndexes.size());
		for (PriceIndexKey priceIndexKey : priceIndexes) {
			final long primaryKey = computeUniquePartId(entityIndexId, priceIndexKey, persistenceService.getReadOnlyKeyCompressor());
			final PriceListAndCurrencyRefIndexStoragePart priceIndexCnt = persistenceService.getStoragePart(catalogVersion, primaryKey, PriceListAndCurrencyRefIndexStoragePart.class);
			isPremiseValid(
				priceIndexCnt != null,
				"Price index with id " + entityIndexId + " with key " + priceIndexKey + " was not found in mem table!"
			);
			priceRefIndexes.put(
				priceIndexKey,
				new PriceListAndCurrencyPriceRefIndex(
					scope,
					priceIndexKey,
					priceIndexCnt.getValidityIndex(),
					priceIndexCnt.getPriceIds()
				)
			);
		}
		return priceRefIndexes;
	}

	/**
	 * Fetches reference container from OffsetIndex if it hasn't been already loaded before.
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
	 * Fetches prices container from OffsetIndex if it hasn't been already loaded before.
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
	 * Fetches attributes container from OffsetIndex if it hasn't been already loaded before.
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
	 * Fetches associated data container(s) from OffsetIndex if it hasn't (they haven't) been already loaded before.
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

	public DefaultEntityCollectionPersistenceService(
		long catalogVersion,
		@Nonnull String catalogName,
		@Nonnull Path catalogStoragePath,
		@Nonnull EntityCollectionHeader entityTypeHeader,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull CatalogOffHeapMemoryManager offHeapMemoryManager,
		@Nonnull ObservableOutputKeeper observableOutputKeeper,
		@Nonnull OffsetIndexRecordTypeRegistry offsetIndexRecordTypeRegistry
	) {
		this.entityCollectionFileReference = new CollectionFileReference(
			entityTypeHeader.entityType(),
			entityTypeHeader.entityTypePrimaryKey(),
			entityTypeHeader.entityTypeFileIndex(),
			entityTypeHeader.fileLocation()
		);
		this.entityCollectionFile = this.entityCollectionFileReference.toFilePath(catalogStoragePath);
		this.entityCollectionHeader = entityTypeHeader;
		this.offsetIndexRecordTypeRegistry = offsetIndexRecordTypeRegistry;
		this.observableOutputKeeper = observableOutputKeeper;
		final WriteOnlyFileHandle writeHandle = new WriteOnlyFileHandle(
			catalogName,
			FileType.ENTITY_COLLECTION,
			this.entityCollectionFileReference.entityType(),
			storageOptions,
			this.entityCollectionFile,
			observableOutputKeeper
		);
		try {
			this.storagePartPersistenceService = new OffsetIndexStoragePartPersistenceService(
				catalogVersion,
				catalogName,
				this.entityCollectionFileReference.entityType(),
				FileType.ENTITY_COLLECTION,
				transactionOptions,
				new OffsetIndex(
					catalogVersion,
					new OffsetIndexDescriptor(
						entityTypeHeader,
						this.createTypeKryoInstance(),
						// we don't know here yet - this will be recomputed on first flush
						entityTypeHeader.activeRecordShare(),
						this.entityCollectionFile.toFile().length()
					),
					storageOptions,
					offsetIndexRecordTypeRegistry,
					writeHandle,
					nonFlushedBlock -> reportNonFlushedContents(catalogName, nonFlushedBlock),
					oldestHistoricalRecord -> reportOldestHistoricalRecord(catalogName, oldestHistoricalRecord.orElse(null))
				),
				offHeapMemoryManager,
				observableOutputKeeper,
				VERSIONED_KRYO_FACTORY
			);
		} catch (RuntimeException ex) {
			// close the handler in case of exception
			writeHandle.close();
			throw ex;
		}
	}

	/**
	 * This is a special constructor used only when catalog is renamed. It builds on previous instance of the service
	 * and reuses all data present in memory. Except the placement on disk nothing else is actually changed.
	 */
	public DefaultEntityCollectionPersistenceService(
		long catalogVersion,
		@Nonnull String catalogName,
		@Nonnull Path catalogStoragePath,
		@Nonnull DefaultEntityCollectionPersistenceService previous,
		@Nonnull StorageOptions storageOptions,
		@Nonnull OffsetIndexRecordTypeRegistry recordRegistry
	) {
		final EntityCollectionHeader entityTypeHeader = previous.getEntityCollectionHeader();
		this.entityCollectionFileReference = new CollectionFileReference(
			entityTypeHeader.entityType(),
			entityTypeHeader.entityTypePrimaryKey(),
			entityTypeHeader.entityTypeFileIndex(),
			entityTypeHeader.fileLocation()
		);
		this.entityCollectionFile = this.entityCollectionFileReference.toFilePath(catalogStoragePath);
		this.entityCollectionHeader = entityTypeHeader;
		this.offsetIndexRecordTypeRegistry = previous.offsetIndexRecordTypeRegistry;
		this.observableOutputKeeper = previous.observableOutputKeeper;

		final OffsetIndexStoragePartPersistenceService previousStoragePartService = previous.storagePartPersistenceService;
		final OffsetIndex previousOffsetIndex = previousStoragePartService.offsetIndex;
		this.storagePartPersistenceService = new OffsetIndexStoragePartPersistenceService(
			catalogVersion,
			catalogName,
			this.entityCollectionFileReference.entityType(),
			FileType.ENTITY_COLLECTION,
			previousStoragePartService.transactionOptions,
			new OffsetIndex(
				catalogVersion,
				this.entityCollectionFile,
				storageOptions,
				recordRegistry,
				new WriteOnlyFileHandle(
					catalogName,
					FileType.ENTITY_COLLECTION,
					this.entityCollectionFileReference.entityType(),
					storageOptions,
					this.entityCollectionFile,
					this.observableOutputKeeper
				),
				nonFlushedBlock -> reportNonFlushedContents(catalogName, nonFlushedBlock),
				oldestHistoricalRecord -> reportOldestHistoricalRecord(catalogName, oldestHistoricalRecord.orElse(null)),
				previousOffsetIndex,
				new OffsetIndexDescriptor(
					previousOffsetIndex.getVersion(),
					previousOffsetIndex.getFileOffsetIndexLocation(),
					entityTypeHeader.compressedKeys(),
					VERSIONED_KRYO_FACTORY,
					previousOffsetIndex.getActiveRecordShare(previousOffsetIndex.getTotalSizeBytes()),
					this.entityCollectionFile.toFile().length()
				)
			),
			previousStoragePartService.offHeapMemoryManager,
			this.observableOutputKeeper,
			VERSIONED_KRYO_FACTORY
		);
	}

	@Override
	public boolean isNew() {
		return this.storagePartPersistenceService.isNew();
	}

	@Override
	public void flushTrappedUpdates(
		long catalogVersion,
		@Nonnull TrappedChanges trappedChanges,
		@Nonnull IntConsumer trappedUpdatedProgress
	) {
		final int[] counter = {0};
		final int division = Math.max(200, trappedChanges.getTrappedChangesCount() / 100);

		// now store all entity trapped updates
		final Iterator<StoragePart> it = trappedChanges.getTrappedChangesIterator();
		while (it.hasNext()) {
			StoragePart storagePart = it.next();
			if (storagePart instanceof RemovedStoragePart removedStoragePart) {
				this.storagePartPersistenceService.removeStoragePart(
					catalogVersion,
					removedStoragePart.getStoragePartPKOrElseThrowException(),
					removedStoragePart.containerType()
				);
			} else {
				this.storagePartPersistenceService.putStoragePart(catalogVersion, storagePart);
			}

			// Increment the counter and update progress every X items
			if (++counter[0] % division == 0) {
				trappedUpdatedProgress.accept(counter[0]);
			}
		}

		// Final progress update if there are remaining items
		if (counter[0] % division != 0) {
			trappedUpdatedProgress.accept(counter[0]);
		}
	}

	@Override
	public boolean isClosed() {
		return this.storagePartPersistenceService.isClosed();
	}

	@Override
	public void consumersLeft(long lastKnownMinimalActiveVersion) {
		this.storagePartPersistenceService.purgeHistoryOlderThan(lastKnownMinimalActiveVersion);
	}

	@Nullable
	@Override
	public EntityWithFetchCount readEntity(
		long catalogVersion,
		int entityPrimaryKey,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull EntitySchema entitySchema,
		@Nonnull DataStoreReader dataStoreReader
	) {
		return toEntity(
			catalogVersion, entityPrimaryKey, EMPTY_STORAGE_PARTS,
			evitaRequest, entitySchema, dataStoreReader
		).orElse(null);
	}

	@Nonnull
	@Override
	public EntityWithFetchCount toEntity(
		long catalogVersion,
		int entityPrimaryKey,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull EntitySchema entitySchema,
		@Nonnull DataStoreReader dataStoreReader,
		@Nonnull EntityStoragePart... storageParts
	) {
		return toEntity(
			catalogVersion,
			entityPrimaryKey,
			storageParts,
			evitaRequest,
			entitySchema,
			dataStoreReader
		)
			.orElseThrow(
				() -> new EntityMissingException(
					entitySchema.getName(), new int[]{ entityPrimaryKey },
					"Entity cannot be completed from the passed data and data stored in storage."
				)
			);
	}

	@Nullable
	@Override
	public BinaryEntityWithFetchCount readBinaryEntity(
		long catalogVersion,
		int entityPrimaryKey,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull EntitySchema entitySchema,
		@Nonnull EvitaSessionContract session,
		@Nonnull Function<String, EntityCollection> entityCollectionFetcher,
		@Nonnull DataStoreReader dataStoreReader
	) {
		// fetch the main entity container
		final byte[] entityStorageContainer = dataStoreReader.fetchBinary(
			catalogVersion, entityPrimaryKey, EntityBodyStoragePart.class
		);
		if (entityStorageContainer == null) {
			// return null if not found
			return null;
		} else {
			return toBinaryEntity(
				catalogVersion, entityStorageContainer, evitaRequest, entitySchema,
				session, entityCollectionFetcher, dataStoreReader
			);
		}
	}

	@Nonnull
	@Override
	public EntityWithFetchCount enrichEntity(
		long catalogVersion,
		@Nonnull EntityDecorator entityDecorator,
		@Nonnull HierarchySerializablePredicate newHierarchyPredicate,
		@Nonnull AttributeValueSerializablePredicate newAttributePredicate,
		@Nonnull AssociatedDataValueSerializablePredicate newAssociatedDataPredicate,
		@Nonnull ReferenceContractSerializablePredicate newReferenceContractPredicate,
		@Nonnull PriceContractSerializablePredicate newPricePredicate,
		@Nonnull DataStoreReader dataStoreReader,
		@Nonnull ChunkTransformerAccessor referenceChunkTransformer
	) {
		final int entityPrimaryKey = Objects.requireNonNull(entityDecorator.getPrimaryKey());
		final IoFetchStatistics ioFetchStatistics = new IoFetchStatistics();

		// body part is fetched everytime - we need to at least test the version
		final EntityBodyStoragePart bodyPart = ioFetchStatistics.record(
			dataStoreReader.fetch(
				catalogVersion, entityPrimaryKey, EntityBodyStoragePart.class
			)
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
			() -> ioFetchStatistics.record(
				dataStoreReader.fetch(catalogVersion, entityPrimaryKey, ReferencesStoragePart.class)
			)
		);
		final PricesStoragePart priceStorageContainer = fetchPrices(
			versionDiffers ? null : entityDecorator.getPricePredicate(),
			newPricePredicate,
			() -> ioFetchStatistics.record(
				dataStoreReader.fetch(catalogVersion, entityPrimaryKey, PricesStoragePart.class)
			)
		);

		final List<AttributesStoragePart> attributesStorageContainers = fetchAttributes(
			entityPrimaryKey,
			versionDiffers ? null : entityDecorator.getAttributePredicate(),
			newAttributePredicate,
			bodyPart.getAttributeLocales(),
			key -> ioFetchStatistics.record(
				dataStoreReader.fetch(catalogVersion, key, AttributesStoragePart.class, AttributesStoragePart::computeUniquePartId)
			)
		);
		final List<AssociatedDataStoragePart> associatedDataStorageContainers = fetchAssociatedData(
			entityPrimaryKey,
			versionDiffers ? null : entityDecorator.getAssociatedDataPredicate(),
			newAssociatedDataPredicate,
			bodyPart.getAssociatedDataKeys(),
			key -> ioFetchStatistics.record(
				dataStoreReader.fetch(catalogVersion, key, AssociatedDataStoragePart.class, AssociatedDataStoragePart::computeUniquePartId)
			)
		);

		// if anything was fetched from the persistent storage
		if (versionDiffers) {
			// build the enriched entity from scratch
			return new EntityWithFetchCount(
				EntityFactory.createEntityFrom(
					entityDecorator.getDelegate().getSchema(),
					bodyPart,
					attributesStorageContainers,
					associatedDataStorageContainers,
					referencesStorageContainer,
					priceStorageContainer,
					referenceChunkTransformer
				),
				ioFetchStatistics.getIoFetchCount(),
				ioFetchStatistics.getIoFetchedBytes()
			);
		} else if (referencesStorageContainer != null || priceStorageContainer != null ||
			!attributesStorageContainers.isEmpty() || !associatedDataStorageContainers.isEmpty()) {
			// and build the enriched entity as a new instance
			return new EntityWithFetchCount(
				EntityFactory.createEntityFrom(
					entityDecorator.getDelegate().getSchema(),
					entityDecorator.getDelegate(),
					bodyPart,
					attributesStorageContainers,
					associatedDataStorageContainers,
					referencesStorageContainer,
					priceStorageContainer
				),
				ioFetchStatistics.getIoFetchCount(),
				ioFetchStatistics.getIoFetchedBytes()
			);
		} else {
			// return original entity - nothing has been fetched
			return new EntityWithFetchCount(
				entityDecorator.getDelegate(),
				ioFetchStatistics.getIoFetchCount(),
				ioFetchStatistics.getIoFetchedBytes()
			);
		}
	}

	@Override
	public int countEntities(
		long catalogVersion,
		@Nonnull DataStoreReader dataStoreReader
	) {
		return dataStoreReader.countStorageParts(
			catalogVersion, EntityBodyStoragePart.class
		);
	}

	@Override
	public boolean isEmpty(
		long catalogVersion,
		@Nonnull DataStoreReader dataStoreReader
	) {
		return dataStoreReader.countStorageParts(
			catalogVersion, EntityBodyStoragePart.class
		) == 0;
	}

	@Nonnull
	@Override
	public BinaryEntityWithFetchCount enrichEntity(
		long catalogVersion,
		@Nonnull EntitySchema entitySchema,
		@Nonnull BinaryEntity entity,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull DataStoreReader dataStoreReader
	) throws EntityAlreadyRemovedException {
		/* TOBEDONE https://github.com/FgForrest/evitaDB/issues/13 */
		return new BinaryEntityWithFetchCount(
			entity, 0, 0
		);
	}

	@Nonnull
	@Override
	public EntityIndex readEntityIndex(long catalogVersion, int entityIndexId, @Nonnull EntitySchema entitySchema) {
		final EntityIndexStoragePart entityIndexCnt = this.storagePartPersistenceService.getStoragePart(catalogVersion, entityIndexId, EntityIndexStoragePart.class);
		isPremiseValid(
			entityIndexCnt != null,
			"Entity index with PK `" + entityIndexId + "` was unexpectedly not found in the mem table!"
		);

		int uniqueIndexCount = 0;
		int filterIndexCount = 0;
		int sortIndexCount = 0;
		int chainIndexCount = 0;
		int cardinalityIndexCount = 0;
		for (AttributeIndexStorageKey attributeIndex : entityIndexCnt.getAttributeIndexes()) {
			switch (attributeIndex.indexType()) {
				case UNIQUE -> uniqueIndexCount++;
				case FILTER -> filterIndexCount++;
				case SORT -> sortIndexCount++;
				case CHAIN -> chainIndexCount++;
				case CARDINALITY -> cardinalityIndexCount++;
				default -> throw new GenericEvitaInternalError("Unknown attribute index type: " + attributeIndex.indexType());
			}
		}

		final Map<AttributeKey, UniqueIndex> uniqueIndexes = CollectionUtils.createHashMap(uniqueIndexCount);
		final Map<AttributeKey, FilterIndex> filterIndexes = CollectionUtils.createHashMap(filterIndexCount);
		final Map<AttributeKey, SortIndex> sortIndexes = CollectionUtils.createHashMap(sortIndexCount);
		final Map<AttributeKey, ChainIndex> chainIndexes = CollectionUtils.createHashMap(chainIndexCount);
		final Map<AttributeKey, CardinalityIndex> cardinalityIndexes = CollectionUtils.createHashMap(cardinalityIndexCount);

		/* TOBEDONE #538 - REMOVE IN FUTURE VERSIONS */
		//noinspection rawtypes
		final Function<AttributeKey, Class> attributeTypeFetcher;
		final EntityIndexKey entityIndexKey = entityIndexCnt.getEntityIndexKey();
		final RepresentativeReferenceKey referenceKey;
		if (entityIndexKey.type() == EntityIndexType.GLOBAL) {
			referenceKey = null;
			attributeTypeFetcher = attributeKey -> entitySchema
				.getAttribute(attributeKey.attributeName())
				.map(AttributeSchemaContract::getType)
				.orElseThrow(() -> new AttributeNotFoundException(attributeKey.attributeName(), entitySchema));
		} else {
			final String referenceName;
			if (entityIndexKey.type() == EntityIndexType.REFERENCED_ENTITY_TYPE) {
				referenceKey = null;
				referenceName = Objects.requireNonNull((String) entityIndexKey.discriminator());
			} else {
				referenceKey = Objects.requireNonNull((RepresentativeReferenceKey) entityIndexKey.discriminator());
				referenceName = referenceKey.referenceName();
			}
			final ReferenceSchema referenceSchema = entitySchema
				.getReferenceOrThrowException(referenceName);
			attributeTypeFetcher = attributeKey -> referenceSchema
				.getAttribute(attributeKey.attributeName())
				.or(() -> entitySchema.getAttribute(attributeKey.attributeName()))
				.map(AttributeSchemaContract::getType)
				.orElseThrow(() -> new AttributeNotFoundException(attributeKey.attributeName(), referenceSchema, entitySchema));
		}

		for (AttributeIndexStorageKey attributeIndexKey : entityIndexCnt.getAttributeIndexes()) {
			switch (attributeIndexKey.indexType()) {
				case UNIQUE ->
					fetchUniqueIndex(catalogVersion, entityIndexId, entitySchema.getName(), this.storagePartPersistenceService, uniqueIndexes, attributeIndexKey);
				case FILTER ->
					fetchFilterIndex(catalogVersion, entityIndexId, this.storagePartPersistenceService, filterIndexes, attributeIndexKey, attributeTypeFetcher);
				case SORT ->
					fetchSortIndex(catalogVersion, entityIndexId, this.storagePartPersistenceService, sortIndexes, referenceKey, attributeIndexKey);
				case CHAIN ->
					fetchChainIndex(catalogVersion, entityIndexId, this.storagePartPersistenceService, chainIndexes, referenceKey, attributeIndexKey);
				case CARDINALITY ->
					fetchCardinalityIndex(catalogVersion, entityIndexId, this.storagePartPersistenceService, cardinalityIndexes, attributeIndexKey);
				default ->
					throw new GenericEvitaInternalError("Unknown attribute index type: " + attributeIndexKey.indexType());
			}
		}

		final HierarchyIndex hierarchyIndex = fetchHierarchyIndex(catalogVersion, entityIndexId, this.storagePartPersistenceService, entityIndexCnt);
		final FacetIndex facetIndex = fetchFacetIndex(catalogVersion, entityIndexId, this.storagePartPersistenceService, entityIndexCnt);

		final EntityIndexType entityIndexType = entityIndexKey.type();
		// base on entity index type we either create GlobalEntityIndex or ReducedEntityIndex
		if (entityIndexType == EntityIndexType.GLOBAL) {
			final Map<PriceIndexKey, PriceListAndCurrencyPriceSuperIndex> priceIndexes = fetchPriceSuperIndexes(
				catalogVersion, entityIndexId, entityIndexCnt.getPriceIndexes(), this.storagePartPersistenceService
			);
			return new GlobalEntityIndex(
				entityIndexCnt.getPrimaryKey(),
				entityIndexKey,
				entityIndexCnt.getVersion(),
				entityIndexCnt.getEntityIds(),
				entityIndexCnt.getEntityIdsByLanguage(),
				new AttributeIndex(
					entitySchema.getName(), null,
					uniqueIndexes, filterIndexes, sortIndexes, chainIndexes
				),
				new PriceSuperIndex(priceIndexes),
				hierarchyIndex,
				facetIndex
			);
		} else if (entityIndexType == EntityIndexType.REFERENCED_ENTITY_TYPE) {
			return new ReferencedTypeEntityIndex(
				entityIndexCnt.getPrimaryKey(),
				entityIndexKey,
				entityIndexCnt.getVersion(),
				entityIndexCnt.getEntityIds(),
				entityIndexCnt.getEntityIdsByLanguage(),
				new AttributeIndex(
					entitySchema.getName(),
					referenceKey,
					uniqueIndexes, filterIndexes, sortIndexes, chainIndexes
				),
				hierarchyIndex,
				facetIndex,
				entityIndexCnt.getIndexPrimaryKeyCardinality(),
				cardinalityIndexes,
				entityIndexCnt.getReferencedPrimaryKeysIndex()
			);
		} else {
			final Scope scope = entityIndexKey.scope();
			final Map<PriceIndexKey, PriceListAndCurrencyPriceRefIndex> priceIndexes = fetchPriceRefIndexes(
				catalogVersion, entityIndexId, scope, entityIndexCnt.getPriceIndexes(), this.storagePartPersistenceService
			);
			return new ReducedEntityIndex(
				entityIndexCnt.getPrimaryKey(),
				entityIndexKey,
				entityIndexCnt.getVersion(),
				entityIndexCnt.getEntityIds(),
				entityIndexCnt.getEntityIdsByLanguage(),
				new AttributeIndex(
					entitySchema.getName(),
					referenceKey,
					uniqueIndexes, filterIndexes, sortIndexes, chainIndexes
				),
				new PriceRefIndex(scope, priceIndexes),
				hierarchyIndex,
				facetIndex
			);
		}
	}

	@Override
	public long getSizeOnDiskInBytes() {
		final Pattern pattern = getEntityCollectionDataStoreFileNamePattern(
			this.entityCollectionFileReference.entityType(),
			this.entityCollectionFileReference.entityTypePrimaryKey()
		);
		return Arrays.stream(
			Objects.requireNonNull(
				this.entityCollectionFile.getParent().toFile().listFiles(
					(dir, name) -> pattern.matcher(name).matches()
				)
			)
		).mapToLong(File::length).sum();
	}

	@Nonnull
	@Override
	public OptionalInt fetchLastAssignedInternalPriceIdFromGlobalIndex(long catalogVersion, int entityIndexId) {
		return ofNullable(
			this.storagePartPersistenceService.getStoragePart(
				catalogVersion,
				entityIndexId,
				EntityIndexStoragePart.class
			)
		)
			.filter(EntityIndexStoragePartDeprecated.class::isInstance)
			.map(EntityIndexStoragePartDeprecated.class::cast)
			.stream()
			.mapToInt(EntityIndexStoragePartDeprecated::getInternalPriceIdSequence)
			.findFirst();
	}

	@Override
	public void close() {
		this.storagePartPersistenceService.close();
	}

	@Nonnull
	public OffsetIndexDescriptor flush(long newCatalogVersion, @Nonnull HeaderInfoSupplier headerInfoSupplier) {
		final long previousVersion = this.storagePartPersistenceService.getVersion();
		final OffsetIndexDescriptor newDescriptor = this.storagePartPersistenceService.flush(newCatalogVersion);
		// when versions are equal - nothing has changed, and we can reuse old header
		if (newDescriptor.version() > previousVersion) {
			final Path catalogStoragePath = this.entityCollectionFile.getParent();
			this.entityCollectionHeader = createEntityCollectionHeader(newCatalogVersion, catalogStoragePath, newDescriptor, headerInfoSupplier, this.entityCollectionFileReference);
		}
		return newDescriptor;
	}

	@Nonnull
	public EntityCollectionHeader compact(@Nonnull String catalogName, long catalogVersion, @Nonnull HeaderInfoSupplier headerInfoSupplier) {
		final DataFileCompactEvent event = new DataFileCompactEvent(
			catalogName,
			FileType.ENTITY_COLLECTION,
			this.entityCollectionFileReference.entityType()
		);
		final CollectionFileReference newReference = this.entityCollectionFileReference.incrementAndGet();
		final Path catalogStoragePath = this.entityCollectionFile.getParent();
		final Path newFilePath = newReference.toFilePath(catalogStoragePath);
		final OffsetIndexDescriptor offsetIndexDescriptor;
		try (final FileOutputStream fos = new FileOutputStream(newFilePath.toFile())) {
			offsetIndexDescriptor = this.storagePartPersistenceService.copySnapshotTo(catalogVersion, fos, null);
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Error occurred while compacting entity " + this.entityCollectionFile + " data file: " + e.getMessage(),
				"Error occurred while compacting entity data file.",
				e
			);
		}
		final EntityCollectionHeader newCollectionHeader = createEntityCollectionHeader(catalogVersion, catalogStoragePath, offsetIndexDescriptor, headerInfoSupplier, newReference);
		// emit event
		event.finish().commit();
		log.info(
			"Compaction of catalog `{}` entity collection `{}` finished, current size is `{}` and active record share is `{}`%, " +
				"entity collection files on disk consume `{}` bytes.",
			catalogName,
			this.entityCollectionFileReference.entityType(),
			offsetIndexDescriptor.getFileSize(),
			Math.round(offsetIndexDescriptor.getActiveRecordShare() * 100.0D),
			this.getSizeOnDiskInBytes()
		);
		return newCollectionHeader;
	}

	/**
	 * Creates {@link Kryo} instance that is usable for deserializing entity instances.
	 */
	@Nonnull
	public Function<VersionedKryoKeyInputs, VersionedKryo> createTypeKryoInstance() {
		return VERSIONED_KRYO_FACTORY;
	}

	/**
	 * Flushes entire living data set to the target output stream. If the output stream represents a file, the file must
	 * exist and must be prepared for re-writing. File must not be used by any other process.
	 *
	 * @param outputStream   output stream to write the data to
	 * @param catalogVersion new catalog version
	 */
	@Nonnull
	public EntityCollectionHeader copySnapshotTo(
		long catalogVersion,
		@Nonnull CollectionFileReference fileReference,
		@Nonnull OutputStream outputStream,
		@Nullable IntConsumer progressConsumer
	) {
		final OffsetIndexDescriptor offsetIndexDescriptor = getStoragePartPersistenceService().copySnapshotTo(catalogVersion, outputStream, progressConsumer);
		final EntityCollectionHeader currentHeader = getEntityCollectionHeader();
		final Path catalogStoragePath = this.entityCollectionFile.getParent();
		return createEntityCollectionHeader(
			catalogVersion,
			catalogStoragePath,
			offsetIndexDescriptor,
			new CopyingHeaderInfoSupplier(currentHeader),
			new CollectionFileReference(
				fileReference.entityType(),
				fileReference.entityTypePrimaryKey(),
				fileReference.fileIndex(),
				offsetIndexDescriptor.fileLocation()
			)
		);
	}

	/*
		PRIVATE METHODS
	*/

	/**
	 * Reports changes in non-flushed record size every second.
	 *
	 * @param catalogName     name of the catalog
	 * @param nonFlushedBlock non-flushed block information
	 */
	private void reportNonFlushedContents(@Nonnull String catalogName, @Nonnull NonFlushedBlock nonFlushedBlock) {
		final long now = System.currentTimeMillis();
		if (this.lastReportTimestamp < now - 1000 || nonFlushedBlock.recordCount() == 0) {
			this.lastReportTimestamp = now;
			new OffsetIndexNonFlushedEvent(
				catalogName,
				FileType.ENTITY_COLLECTION,
				this.entityCollectionFileReference.entityType(),
				nonFlushedBlock.recordCount(),
				nonFlushedBlock.estimatedMemorySizeInBytes()
			).commit();
		}
	}

	/**
	 * Reports changes in historical records kept.
	 *
	 * @param catalogName            name of the catalog
	 * @param oldestHistoricalRecord oldest historical record
	 */
	private void reportOldestHistoricalRecord(@Nonnull String catalogName, @Nullable OffsetDateTime oldestHistoricalRecord) {
		new OffsetIndexHistoryKeptEvent(
			catalogName,
			FileType.ENTITY_COLLECTION,
			this.entityCollectionFileReference.entityType(),
			oldestHistoricalRecord
		).commit();
	}

	/**
	 * Method creates a function that allows to create new {@link EntityCollectionHeader} instance from
	 * {@link PersistentStorageDescriptor} DTO. The catalog entity header contains additional information from this
	 * entity collection instance we need to keep and propagate to next immutable catalog entity header object.
	 */
	@Nonnull
	private EntityCollectionHeader createEntityCollectionHeader(
		long catalogVersion,
		@Nonnull Path catalogStoragePath,
		@Nonnull PersistentStorageDescriptor newDescriptor,
		@Nonnull HeaderInfoSupplier headerInfoSupplier,
		@Nonnull CollectionFileReference collectionFileReference
	) {
		return new EntityCollectionHeader(
			collectionFileReference.entityType(),
			collectionFileReference.entityTypePrimaryKey(),
			collectionFileReference.fileIndex(),
			getStoragePartPersistenceService().countStorageParts(catalogVersion, EntityBodyStoragePart.class),
			headerInfoSupplier.getLastAssignedPrimaryKey(),
			headerInfoSupplier.getLastAssignedIndexKey(),
			headerInfoSupplier.getLastAssignedInternalPriceId(),
			getStoragePartPersistenceService().offsetIndex.getActiveRecordShare(collectionFileReference.toFilePath(catalogStoragePath).toFile().length()),
			newDescriptor,
			headerInfoSupplier.getGlobalIndexPrimaryKey().isPresent() ?
				headerInfoSupplier.getGlobalIndexPrimaryKey().getAsInt() : null,
			headerInfoSupplier.getIndexPrimaryKeys()
		);
	}

	/**
	 * Converts the given entity and its associated data, attributes, prices, and references into a BinaryEntity object.
	 *
	 * @param entityStorageContainer  The entity storage container.
	 * @param evitaRequest            The Evita request.
	 * @param session                 The Evita session.
	 * @param entityCollectionFetcher The function used to fetch entity collections.
	 * @param dataStoreReader         The buffer for storing the storage containers.
	 * @return The BinaryEntity object representing the converted entity.
	 */
	@Nonnull
	private BinaryEntityWithFetchCount toBinaryEntity(
		long catalogVersion,
		@Nonnull byte[] entityStorageContainer,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull EntitySchema entitySchema,
		@Nonnull EvitaSessionContract session,
		@Nonnull Function<String, EntityCollection> entityCollectionFetcher,
		@Nonnull DataStoreReader dataStoreReader
	) {
		final IoFetchStatistics ioFetchStatistics = new IoFetchStatistics();
		final EntityBodyStoragePart deserializedEntityBody = this.storagePartPersistenceService.deserializeStoragePart(
			entityStorageContainer, EntityBodyStoragePart.class
		);

		final int entityPrimaryKey = deserializedEntityBody.getPrimaryKey();
		// load additional containers only when requested
		final byte[] priceStorageContainer = fetchPrices(
			null, new PriceContractSerializablePredicate(evitaRequest, (Boolean) null),
			() -> ioFetchStatistics.record(
				dataStoreReader.fetchBinary(
					catalogVersion, entityPrimaryKey, PricesStoragePart.class
				)
			)
		);

		final List<byte[]> attributesStorageContainers = fetchAttributes(
			entityPrimaryKey, null, new AttributeValueSerializablePredicate(evitaRequest),
			deserializedEntityBody.getAttributeLocales(),
			attributesSetKey -> ioFetchStatistics.record(
				dataStoreReader.fetchBinary(
					catalogVersion,
					attributesSetKey,
					AttributesStoragePart.class,
					AttributesStoragePart::computeUniquePartId
				)
			)
		);
		final List<byte[]> associatedDataStorageContainers = fetchAssociatedData(
			entityPrimaryKey, null, new AssociatedDataValueSerializablePredicate(evitaRequest),
			deserializedEntityBody.getAssociatedDataKeys(),
			associatedDataKey -> ioFetchStatistics.record(dataStoreReader.fetchBinary(
					catalogVersion,
					associatedDataKey,
					AssociatedDataStoragePart.class,
					AssociatedDataStoragePart::computeUniquePartId
				)
			)
		);

		final Map<String, RequirementContext> referenceEntityFetch = evitaRequest.getReferenceEntityFetch();
		final AtomicReference<ReferencesStoragePart> referencesStoragePartRef = new AtomicReference<>();
		final byte[] referencesStorageContainer = fetchReferences(
			null, new ReferenceContractSerializablePredicate(evitaRequest),
			() -> {
				if (referenceEntityFetch.isEmpty()) {
					return ioFetchStatistics.record(
						dataStoreReader.fetchBinary(
							catalogVersion, entityPrimaryKey, ReferencesStoragePart.class
						)
					);
				} else {
					final ReferencesStoragePart fetchedPart = ioFetchStatistics.record(
						dataStoreReader.fetch(
							catalogVersion, entityPrimaryKey, ReferencesStoragePart.class
						)
					);
					if (fetchedPart == null) {
						return null;
					} else {
						referencesStoragePartRef.set(fetchedPart);
						return this.storagePartPersistenceService.serializeStoragePart(fetchedPart);
					}
				}
			}
		);

		final BinaryEntity[] referencedEntities = referencesStoragePartRef.get() == null ?
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
								requirement -> Arrays.stream(referencesStoragePartRef.get().getDistinctReferencedIds(referenceName))
									.mapToObj(
										it -> entityCollectionFetcher.apply(referenceSchema.getReferencedEntityType())
											.fetchBinaryEntity(it, evitaRequest.deriveCopyWith(referenceSchema.getReferencedEntityType(), entityFetch), session)
											.orElse(null)
									)
							)
							.orElse(Stream.empty()),
						ofNullable(entityGroupFetch)
							.map(
								requirement -> Arrays.stream(referencesStoragePartRef.get().getDistinctReferencedGroupIds(referenceName))
									.mapToObj(
										it -> entityCollectionFetcher.apply(referenceSchema.getReferencedGroupType())
											.fetchBinaryEntity(it, evitaRequest.deriveCopyWith(referenceSchema.getReferencedGroupType(), entityGroupFetch), session)
											.orElse(null)
									)
							)
							.orElse(Stream.empty())
					);
				})
				.filter(Objects::nonNull)
				.toArray(BinaryEntity[]::new);

		// and build the entity
		return new BinaryEntityWithFetchCount(
			new BinaryEntity(
				entitySchema,
				entityPrimaryKey,
				entityStorageContainer,
				attributesStorageContainers.toArray(BYTE_TWO_DIMENSIONAL_ARRAY),
				associatedDataStorageContainers.toArray(BYTE_TWO_DIMENSIONAL_ARRAY),
				priceStorageContainer,
				referencesStorageContainer,
				referencedEntities
			),
			ioFetchStatistics.getIoFetchCount(),
			ioFetchStatistics.getIoFetchedBytes()
		);
	}

	/**
	 * Collects the information about fetched data.
	 */
	@Getter
	private static final class IoFetchStatistics {
		private int ioFetchCount;
		private int ioFetchedBytes;

		/**
		 * Records the I/O fetch with particular size in Bytes.
		 *
		 * @param storagePart The storage part that was fetched.
		 */
		@Nullable
		public <T extends EntityStoragePart> T record(@Nullable T storagePart) {
			if (storagePart == null) {
				return null;
			} else {
				this.ioFetchCount++;
				// we need to count the overhead size of the storage part and serialUUID header along with the storage part itself
				this.ioFetchedBytes += StorageRecord.getOverheadSize() + 8 + storagePart.sizeInBytes().orElse(0);
				return storagePart;
			}
		}

		/**
		 * Records the I/O fetch with particular size in Bytes.
		 *
		 * @param storagePart The storage part that was fetched.
		 */
		@Nullable
		public byte[] record(@Nullable byte[] storagePart) {
			if (storagePart == null) {
				return null;
			} else {
				this.ioFetchCount++;
				// we need to count the overhead size of the storage part and serialUUID header along with the storage part itself
				this.ioFetchedBytes += StorageRecord.getOverheadSize() + 8 + storagePart.length;
				return storagePart;
			}
		}

	}

	/**
	 * Internal implementation of the {@link HeaderInfoSupplier} that copies information from the previous header.
	 */
	@RequiredArgsConstructor
	private static class CopyingHeaderInfoSupplier implements HeaderInfoSupplier {
		private final EntityCollectionHeader currentHeader;

		@Override
		public int getLastAssignedPrimaryKey() {
			return this.currentHeader.lastPrimaryKey();
		}

		@Override
		public int getLastAssignedIndexKey() {
			return this.currentHeader.lastEntityIndexPrimaryKey();
		}

		@Override
		public int getLastAssignedInternalPriceId() {
			return this.currentHeader.lastInternalPriceId();
		}

		@Nonnull
		@Override
		public OptionalInt getGlobalIndexPrimaryKey() {
			return this.currentHeader.globalEntityIndexPrimaryKey() == null ?
				OptionalInt.empty() : OptionalInt.of(this.currentHeader.globalEntityIndexPrimaryKey());
		}

		@Nonnull
		@Override
		public List<Integer> getIndexPrimaryKeys() {
			return this.currentHeader.usedEntityIndexPrimaryKeys();
		}
	}
}
