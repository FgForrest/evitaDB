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

package io.evitadb.store.catalog;

import com.esotericsoftware.kryo.Kryo;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
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
import io.evitadb.core.buffer.DataStoreChanges;
import io.evitadb.core.buffer.DataStoreIndexChanges;
import io.evitadb.core.buffer.DataStoreMemoryBuffer;
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
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.kryo.VersionedKryo;
import io.evitadb.store.kryo.VersionedKryoFactory;
import io.evitadb.store.kryo.VersionedKryoKeyInputs;
import io.evitadb.store.model.PersistentStorageDescriptor;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.offsetIndex.OffsetIndex;
import io.evitadb.store.offsetIndex.OffsetIndexDescriptor;
import io.evitadb.store.offsetIndex.io.OffHeapMemoryManager;
import io.evitadb.store.offsetIndex.io.WriteOnlyFileHandle;
import io.evitadb.store.offsetIndex.model.OffsetIndexRecordTypeRegistry;
import io.evitadb.store.schema.SchemaKryoConfigurer;
import io.evitadb.store.service.SharedClassesConfigurer;
import io.evitadb.store.spi.EntityCollectionPersistenceService;
import io.evitadb.store.spi.StoragePartPersistenceService;
import io.evitadb.store.spi.model.EntityCollectionHeader;
import io.evitadb.store.spi.model.reference.CollectionFileReference;
import io.evitadb.store.spi.model.storageParts.index.*;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.store.wal.TransactionalStoragePartPersistenceService;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
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
	 * Contains reference to the target file where the entity collection is stored.
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
	 * Memory manager for off-heap memory.
	 */
	@Nonnull private final OffHeapMemoryManager offHeapMemoryManager;
	/**
	 * ObservableOutputKeeper is used to track {@link io.evitadb.store.kryo.ObservableOutput} tied to particular file
	 * and is propagated to internal {@link #storagePartPersistenceService}. It can be also retrieved and is shared with its
	 * wrapping implementation.
	 */
	@Nonnull @Getter
	private final ObservableOutputKeeper observableOutputKeeper;
	/**
	 * Contains information about storage configuration options.
	 */
	@Nonnull
	private final StorageOptions storageOptions;
	/**
	 * Contains information about transaction configuration options.
	 */
	@Nonnull
	private final TransactionOptions transactionOptions;
	/**
	 * Contains reference to the catalog entity header collecting all crucial information about single entity collection.
	 * The catalog entity header is loaded in constructor and because it's immutable it needs to be replaced with each
	 * {@link #flush(long, Function)} call.
	 */
	@Nonnull @Getter
	private EntityCollectionHeader catalogEntityHeader;
	/**
	 * The storage part persistence service implementation.
	 */
	@Nonnull @Getter
	private final StoragePartPersistenceService storagePartPersistenceService;

	@Nonnull
	private static Entity toEntity(
		@Nonnull EntityBodyStoragePart entityStorageContainer,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull EntitySchema entitySchema,
		@Nonnull DataStoreMemoryBuffer<EntityIndexKey, EntityIndex, DataStoreChanges<EntityIndexKey, EntityIndex>> storageContainerBuffer
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

	/**
	 * Fetches {@link io.evitadb.index.facet.FacetIndex} from the {@link OffsetIndex} and returns it.
	 */
	@Nonnull
	private static FacetIndex fetchFacetIndex(
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
				final FacetIndexStoragePart facetIndexStoragePart = persistenceService.getStoragePart(primaryKey, FacetIndexStoragePart.class);
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
	 * Fetches {@link HierarchyIndex} from the {@link OffsetIndex} and returns it.
	 */
	@Nonnull
	private static HierarchyIndex fetchHierarchyIndex(
		int entityIndexId,
		@Nonnull StoragePartPersistenceService persistenceService,
		@Nonnull EntityIndexStoragePart entityIndexCnt
	) {
		final HierarchyIndex hierarchyIndex;
		if (entityIndexCnt.isHierarchyIndex()) {
			final HierarchyIndexStoragePart hierarchyIndexStoragePart = persistenceService.getStoragePart(entityIndexId, HierarchyIndexStoragePart.class);
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
		int entityIndexId,
		@Nonnull StoragePartPersistenceService persistenceService,
		@Nonnull Map<AttributeKey, SortIndex> sortIndexes,
		@Nonnull AttributeIndexStorageKey attributeIndexKey
	) {
		final long primaryKey = AttributeIndexStoragePart.computeUniquePartId(entityIndexId, AttributeIndexType.SORT, attributeIndexKey.attribute(), persistenceService.getReadOnlyKeyCompressor());
		final SortIndexStoragePart sortIndexCnt = persistenceService.getStoragePart(primaryKey, SortIndexStoragePart.class);
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
	 * Fetches {@link ChainIndex} from the {@link OffsetIndex} and puts it into the `chainIndexes` key-value index.
	 */
	private static void fetchChainIndex(
		int entityIndexId,
		@Nonnull StoragePartPersistenceService persistenceService,
		@Nonnull Map<AttributeKey, ChainIndex> chainIndexes,
		@Nonnull AttributeIndexStorageKey attributeIndexKey
	) {
		final long primaryKey = AttributeIndexStoragePart.computeUniquePartId(entityIndexId, AttributeIndexType.CHAIN, attributeIndexKey.attribute(), persistenceService.getReadOnlyKeyCompressor());
		final ChainIndexStoragePart chainIndexCnt = persistenceService.getStoragePart(primaryKey, ChainIndexStoragePart.class);
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
	 * Fetches {@link CardinalityIndex} from the {@link OffsetIndex} and puts it into the `cardinalityIndexes` key-value index.
	 */
	private static void fetchCardinalityIndex(
		int entityIndexId,
		@Nonnull StoragePartPersistenceService persistenceService,
		@Nonnull Map<AttributeKey, CardinalityIndex> cardinalityIndexes,
		@Nonnull AttributeIndexStorageKey attributeIndexKey
	) {
		final long primaryKey = AttributeIndexStoragePart.computeUniquePartId(entityIndexId, AttributeIndexType.CARDINALITY, attributeIndexKey.attribute(), persistenceService.getReadOnlyKeyCompressor());
		final CardinalityIndexStoragePart cardinalityIndexCnt = persistenceService.getStoragePart(primaryKey, CardinalityIndexStoragePart.class);
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
		int entityIndexId,
		@Nonnull StoragePartPersistenceService persistenceService,
		@Nonnull Map<AttributeKey, FilterIndex> filterIndexes,
		@Nonnull AttributeIndexStorageKey attributeIndexKey
	) {
		final long primaryKey = AttributeIndexStoragePart.computeUniquePartId(entityIndexId, AttributeIndexType.FILTER, attributeIndexKey.attribute(), persistenceService.getReadOnlyKeyCompressor());
		final FilterIndexStoragePart filterIndexCnt = persistenceService.getStoragePart(primaryKey, FilterIndexStoragePart.class);
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
	 * Fetches {@link UniqueIndex} from the {@link OffsetIndex} and puts it into the `uniqueIndexes` key-value index.
	 */
	private static void fetchUniqueIndex(
		@Nonnull String entityType,
		int entityIndexId,
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
		final UniqueIndexStoragePart uniqueIndexCnt = persistenceService.getStoragePart(primaryKey, UniqueIndexStoragePart.class);
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
		int entityIndexId,
		@Nonnull Set<PriceIndexKey> priceIndexes,
		@Nonnull StoragePartPersistenceService persistenceService
	) {
		final Map<PriceIndexKey, PriceListAndCurrencyPriceSuperIndex> priceSuperIndexes = CollectionUtils.createHashMap(priceIndexes.size());
		for (PriceIndexKey priceIndexKey : priceIndexes) {
			final long primaryKey = computeUniquePartId(entityIndexId, priceIndexKey, persistenceService.getReadOnlyKeyCompressor());
			final PriceListAndCurrencySuperIndexStoragePart priceIndexCnt = persistenceService.getStoragePart(primaryKey, PriceListAndCurrencySuperIndexStoragePart.class);
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
		int entityIndexId,
		@Nonnull Set<PriceIndexKey> priceIndexes,
		@Nonnull StoragePartPersistenceService persistenceService,
		@Nonnull Supplier<PriceSuperIndex> superIndexAccessor
	) {
		final Map<PriceIndexKey, PriceListAndCurrencyPriceRefIndex> priceRefIndexes = CollectionUtils.createHashMap(priceIndexes.size());
		for (PriceIndexKey priceIndexKey : priceIndexes) {
			final long primaryKey = computeUniquePartId(entityIndexId, priceIndexKey, persistenceService.getReadOnlyKeyCompressor());
			final PriceListAndCurrencyRefIndexStoragePart priceIndexCnt = persistenceService.getStoragePart(primaryKey, PriceListAndCurrencyRefIndexStoragePart.class);
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
		@Nonnull Path catalogStoragePath,
		@Nonnull EntityCollectionHeader entityTypeHeader,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull OffHeapMemoryManager offHeapMemoryManager,
		@Nonnull ObservableOutputKeeper observableOutputKeeper,
		@Nonnull OffsetIndexRecordTypeRegistry offsetIndexRecordTypeRegistry
	) {
		this.entityCollectionFile = new CollectionFileReference(
			entityTypeHeader.entityType(),
			entityTypeHeader.entityTypePrimaryKey(),
			entityTypeHeader.entityTypeFileIndex(),
			entityTypeHeader.fileLocation()
		).toFilePath(catalogStoragePath);

		this.catalogEntityHeader = entityTypeHeader;
		this.offsetIndexRecordTypeRegistry = offsetIndexRecordTypeRegistry;
		this.offHeapMemoryManager = offHeapMemoryManager;
		this.observableOutputKeeper = observableOutputKeeper;
		this.storageOptions = storageOptions;
		this.transactionOptions = transactionOptions;
		this.storagePartPersistenceService = new OffsetIndexStoragePartPersistenceService(
			this.entityCollectionFile.toFile().getName(),
			transactionOptions,
			new OffsetIndex(
				new OffsetIndexDescriptor(
					entityTypeHeader,
					this.createTypeKryoInstance()
				),
				storageOptions,
				offsetIndexRecordTypeRegistry,
				new WriteOnlyFileHandle(entityCollectionFile, observableOutputKeeper)
			),
			offHeapMemoryManager,
			observableOutputKeeper,
			VERSIONED_KRYO_FACTORY
		);
	}

	public DefaultEntityCollectionPersistenceService(@Nonnull DefaultEntityCollectionPersistenceService previous) {
		this.entityCollectionFile = previous.entityCollectionFile;
		this.catalogEntityHeader = previous.catalogEntityHeader;
		this.offsetIndexRecordTypeRegistry = previous.offsetIndexRecordTypeRegistry;
		this.offHeapMemoryManager = previous.offHeapMemoryManager;
		this.observableOutputKeeper = previous.observableOutputKeeper;
		this.storageOptions = previous.storageOptions;
		this.transactionOptions = previous.transactionOptions;
		this.storagePartPersistenceService = new OffsetIndexStoragePartPersistenceService(
			this.entityCollectionFile.toFile().getName(),
			previous.transactionOptions,
			new OffsetIndex(
				new OffsetIndexDescriptor(
					previous.catalogEntityHeader,
					this.createTypeKryoInstance()
				),
				previous.storageOptions,
				offsetIndexRecordTypeRegistry,
				new WriteOnlyFileHandle(entityCollectionFile, observableOutputKeeper)
			),
			offHeapMemoryManager,
			observableOutputKeeper,
			VERSIONED_KRYO_FACTORY
		);
	}

	@Override
	public boolean isNew() {
		return this.storagePartPersistenceService.isNew();
	}

	@Override
	public void prepare() {
		observableOutputKeeper.prepare();
	}

	@Override
	public void release() {
		observableOutputKeeper.free();
	}

	/*
		PRIVATE METHODS
	 */

	@Override
	public <T> T executeWriteSafely(@Nonnull Supplier<T> lambda) {
		if (observableOutputKeeper.isPrepared()) {
			return lambda.get();
		} else {
			try {
				observableOutputKeeper.prepare();
				return lambda.get();
			} finally {
				observableOutputKeeper.free();
			}
		}
	}

	@Override
	public void flushTrappedUpdates(@Nonnull DataStoreIndexChanges<EntityIndexKey, EntityIndex> dataStoreIndexChanges) {
		// now store all entity trapped updates
		dataStoreIndexChanges.popTrappedUpdates()
			.forEach(it -> this.storagePartPersistenceService.putStoragePart(0L, it));
	}

	@Override
	public boolean isClosed() {
		return this.storagePartPersistenceService.isClosed();
	}

	@Nullable
	@Override
	public Entity readEntity(int entityPrimaryKey, @Nonnull EvitaRequest evitaRequest, @Nonnull EntitySchema entitySchema, @Nonnull DataStoreMemoryBuffer<EntityIndexKey, EntityIndex, DataStoreChanges<EntityIndexKey, EntityIndex>> storageContainerBuffer) {
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
	}

	@Nullable
	@Override
	public BinaryEntity readBinaryEntity(
		int entityPrimaryKey,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull EvitaSessionContract session,
		@Nonnull Function<String, EntityCollection> entityCollectionFetcher,
		@Nonnull DataStoreMemoryBuffer<EntityIndexKey, EntityIndex, DataStoreChanges<EntityIndexKey, EntityIndex>> storageContainerBuffer
	) {
		// provide passed schema during deserialization from binary form
		final EntitySchema entitySchema = entityCollectionFetcher.apply(evitaRequest.getEntityType()).getInternalSchema();
		return EntitySchemaContext.executeWithSchemaContext(entitySchema, () -> {
			// fetch the main entity container
			final byte[] entityStorageContainer = storageContainerBuffer.fetchBinary(
				entityPrimaryKey, EntityBodyStoragePart.class
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
		@Nonnull DataStoreMemoryBuffer<EntityIndexKey, EntityIndex, DataStoreChanges<EntityIndexKey, EntityIndex>> storageContainerBuffer
	) {
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
	}

	@Nonnull
	@Override
	public BinaryEntity enrichEntity(@Nonnull EntitySchema entitySchema, @Nonnull BinaryEntity entity, @Nonnull EvitaRequest evitaRequest, @Nonnull DataStoreMemoryBuffer<EntityIndexKey, EntityIndex, DataStoreChanges<EntityIndexKey, EntityIndex>> storageContainerBuffer) throws EntityAlreadyRemovedException {
		/* TOBEDONE https://github.com/FgForrest/evitaDB/issues/13 */
		return entity;
	}

	@Override
	public EntityIndex readEntityIndex(int entityIndexId, @Nonnull Supplier<EntitySchema> schemaSupplier, @Nonnull Supplier<PriceSuperIndex> temporalIndexAccessor, @Nonnull Supplier<PriceSuperIndex> superIndexAccessor) {
		final EntityIndexStoragePart entityIndexCnt = storagePartPersistenceService.getStoragePart(entityIndexId, EntityIndexStoragePart.class);
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
				case UNIQUE ->
					fetchUniqueIndex(schemaSupplier.get().getName(), entityIndexId, storagePartPersistenceService, uniqueIndexes, attributeIndexKey);
				case FILTER ->
					fetchFilterIndex(entityIndexId, storagePartPersistenceService, filterIndexes, attributeIndexKey);
				case SORT ->
					fetchSortIndex(entityIndexId, storagePartPersistenceService, sortIndexes, attributeIndexKey);
				case CHAIN ->
					fetchChainIndex(entityIndexId, storagePartPersistenceService, chainIndexes, attributeIndexKey);
				case CARDINALITY ->
					fetchCardinalityIndex(entityIndexId, storagePartPersistenceService, cardinalityIndexes, attributeIndexKey);
				default ->
					throw new EvitaInternalError("Unknown attribute index type: " + attributeIndexKey.indexType());
			}
		}

		final HierarchyIndex hierarchyIndex = fetchHierarchyIndex(entityIndexId, storagePartPersistenceService, entityIndexCnt);
		final FacetIndex facetIndex = fetchFacetIndex(entityIndexId, storagePartPersistenceService, entityIndexCnt);

		final EntityIndexType entityIndexType = entityIndexCnt.getEntityIndexKey().getType();
		// base on entity index type we either create GlobalEntityIndex or ReducedEntityIndex
		if (entityIndexType == EntityIndexType.GLOBAL) {
			final Map<PriceIndexKey, PriceListAndCurrencyPriceSuperIndex> priceIndexes = fetchPriceSuperIndexes(
				entityIndexId, entityIndexCnt.getPriceIndexes(), storagePartPersistenceService
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
				entityIndexId, entityIndexCnt.getPriceIndexes(), storagePartPersistenceService, temporalIndexAccessor
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
	}

	/**
	 * Returns count of entities of certain type in the target storage.
	 *
	 * <strong>Note:</strong> the count may not be accurate - it counts only already persisted containers to the
	 * {@link OffsetIndex} and doesn't take transactional memory into an account.
	 */
	@Override
	public <T extends StoragePart> int count(@Nonnull Class<T> containerClass) {
		return storagePartPersistenceService.countStorageParts(containerClass);
	}

	/**
	 * Returns iterator that goes through all containers of certain type in the target storage.
	 *
	 * <strong>Note:</strong> the list may not be accurate - it only goes through already persisted containers to the
	 * {@link OffsetIndex} and doesn't take transactional memory into an account.
	 */
	@Nonnull
	@Override
	public Iterator<Entity> entityIterator(@Nonnull EntitySchema entitySchema, @Nonnull DataStoreMemoryBuffer<EntityIndexKey, EntityIndex, DataStoreChanges<EntityIndexKey, EntityIndex>> storageContainerBuffer) {
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
		return this.storagePartPersistenceService
			.getEntryStream(EntityBodyStoragePart.class)
			.map(it -> toEntity(it, evitaRequest, entitySchema, storageContainerBuffer))
			.iterator();
	}

	@Override
	public void copySnapshotTo(@Nonnull File newFile) {
		// TODO JNO - IMPLEMENT ME
	}

	@Nonnull
	@Override
	public EntityCollectionHeader flush(long newCatalogVersion, @Nonnull Function<PersistentStorageDescriptor, EntityCollectionHeader> catalogEntityHeaderFactory) {
		final long previousVersion = this.storagePartPersistenceService.getVersion();
		final PersistentStorageDescriptor newDescriptor = this.storagePartPersistenceService.flush(newCatalogVersion);
		// when versions are equal - nothing has changed, and we can reuse old header
		if (newDescriptor.version() > previousVersion) {
			this.catalogEntityHeader = catalogEntityHeaderFactory.apply(newDescriptor);
		}

		return this.catalogEntityHeader;
	}

	@Override
	public void delete() {
		close();
		if (!this.entityCollectionFile.toFile().delete()) {
			throw new UnexpectedIOException(
				"Failed to delete file: " + this.entityCollectionFile,
				"Failed to delete file!"
			);
		}
	}

	@Override
	public void close() {
		this.storagePartPersistenceService.close();
	}

	/**
	 * Creates {@link Kryo} instance that is usable for deserializing entity instances.
	 */
	@Nonnull
	public Function<VersionedKryoKeyInputs, VersionedKryo> createTypeKryoInstance() {
		return VERSIONED_KRYO_FACTORY;
	}

	@Nonnull
	private BinaryEntity toBinaryEntity(
		@Nonnull byte[] entityStorageContainer,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull EvitaSessionContract session,
		@Nonnull Function<String, EntityCollection> entityCollectionFetcher,
		@Nonnull DataStoreMemoryBuffer<EntityIndexKey, EntityIndex, DataStoreChanges<EntityIndexKey, EntityIndex>> storageContainerBuffer
	) {
		final EntitySchema entitySchema = EntitySchemaContext.getEntitySchema();
		final EntityBodyStoragePart deserializedEntityBody = this.storagePartPersistenceService.deserializeStoragePart(
			entityStorageContainer, EntityBodyStoragePart.class
		);

		final int entityPrimaryKey = deserializedEntityBody.getPrimaryKey();
		// load additional containers only when requested
		final byte[] priceStorageContainer = fetchPrices(
			null, new PriceContractSerializablePredicate(evitaRequest, (Boolean) null),
			() -> storageContainerBuffer.fetchBinary(
				entityPrimaryKey, PricesStoragePart.class
			)
		);

		final List<byte[]> attributesStorageContainers = fetchAttributes(
			entityPrimaryKey, null, new AttributeValueSerializablePredicate(evitaRequest),
			deserializedEntityBody.getAttributeLocales(),
			attributesSetKey -> storageContainerBuffer.fetchBinary(
				attributesSetKey,
				AttributesStoragePart.class,
				AttributesStoragePart::computeUniquePartId
			)
		);
		final List<byte[]> associatedDataStorageContainers = fetchAssociatedData(
			entityPrimaryKey, null, new AssociatedDataValueSerializablePredicate(evitaRequest),
			deserializedEntityBody.getAssociatedDataKeys(),
			associatedDataKey -> storageContainerBuffer.fetchBinary(
				associatedDataKey,
				AssociatedDataStoragePart.class,
				AssociatedDataStoragePart::computeUniquePartId
			)
		);

		final Map<String, RequirementContext> referenceEntityFetch = evitaRequest.getReferenceEntityFetch();
		final AtomicReference<ReferencesStoragePart> referencesStoragePartRef = new AtomicReference<>();
		final byte[] referencesStorageContainer = fetchReferences(
			null, new ReferenceContractSerializablePredicate(evitaRequest),
			() -> {
				if (referenceEntityFetch.isEmpty()) {
					return storageContainerBuffer.fetchBinary(
						entityPrimaryKey, ReferencesStoragePart.class
					);
				} else {
					final ReferencesStoragePart fetchedPart = storageContainerBuffer.fetch(
						entityPrimaryKey, ReferencesStoragePart.class
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
								requirement -> Arrays.stream(referencesStoragePartRef.get().getReferencedIds(referenceName))
									.mapToObj(
										it -> entityCollectionFetcher.apply(referenceSchema.getReferencedEntityType())
											.getBinaryEntity(it, evitaRequest.deriveCopyWith(referenceSchema.getReferencedEntityType(), entityFetch), session)
											.orElse(null)
									)
							)
							.orElse(Stream.empty()),
						ofNullable(entityGroupFetch)
							.map(
								requirement -> Arrays.stream(referencesStoragePartRef.get().getReferencedGroupIds(referenceName))
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

}
