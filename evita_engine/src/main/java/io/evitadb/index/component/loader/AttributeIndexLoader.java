/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.index.component.loader;

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.attribute.ChainIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.attribute.UniqueIndex;
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ChainIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.UniqueIndexStoragePart;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.util.Map;

import static io.evitadb.utils.Assert.isPremiseValid;
import static java.util.Optional.ofNullable;

/**
 * Reloads the four flat per-attribute maps owned by `AttributeIndex` (UNIQUE / FILTER / SORT /
 * CHAIN) from persistent storage. Mirrors the legacy `fetchUniqueIndex` / `fetchFilterIndex` /
 * `fetchSortIndex` / `fetchChainIndex` helpers in
 * `DefaultEntityCollectionPersistenceService`, fused into one loader because all four maps are
 * driven by the same `EntityIndexStoragePart.getAttributeIndexes()` set.
 *
 * The legacy CARDINALITY type — also stored under the same `AttributeIndexStorageKey` namespace
 * — is intentionally **not** handled here; it lives in
 * {@link AttributeCardinalityIndexMapLoader} so that subclasses without cardinality
 * (`GlobalEntityIndex`, `ReducedEntityIndex`) need not declare an empty cardinality bundle.
 */
public final class AttributeIndexLoader implements ComponentLoader {

	@Override
	@Nonnull
	public LoadedComponentBundle load(@Nonnull LoadContext context) {
		final EntityIndexStoragePart manifest = context.entityIndexStoragePart();
		// pre-size each map by counting per-type entries — mirrors the legacy two-pass approach
		int uniqueCount = 0;
		int filterCount = 0;
		int sortCount = 0;
		int chainCount = 0;
		for (final AttributeIndexStorageKey key : manifest.getAttributeIndexes()) {
			switch (key.indexType()) {
				case UNIQUE -> uniqueCount++;
				case FILTER -> filterCount++;
				case SORT -> sortCount++;
				case CHAIN -> chainCount++;
				case CARDINALITY -> {
					// handled by AttributeCardinalityIndexMapLoader
				}
				default -> throw new GenericEvitaInternalError(
					"Unknown attribute index type: " + key.indexType()
				);
			}
		}

		final Map<AttributeIndexKey, UniqueIndex> uniqueIndexes = CollectionUtils.createHashMap(uniqueCount);
		final Map<AttributeIndexKey, FilterIndex> filterIndexes = CollectionUtils.createHashMap(filterCount);
		final Map<AttributeIndexKey, SortIndex> sortIndexes = CollectionUtils.createHashMap(sortCount);
		final Map<AttributeIndexKey, ChainIndex> chainIndexes = CollectionUtils.createHashMap(chainCount);

		final StoragePartPersistenceService<?> service = context.storagePartService();
		final int entityIndexId = context.entityIndexId();
		final long catalogVersion = context.catalogVersion();
		final String entityName = context.entitySchema().getName();

		for (final AttributeIndexStorageKey key : manifest.getAttributeIndexes()) {
			switch (key.indexType()) {
				case UNIQUE -> fetchUnique(catalogVersion, entityIndexId, entityName, service, uniqueIndexes, key);
				case FILTER -> fetchFilter(catalogVersion, entityIndexId, service, filterIndexes, key, context);
				case SORT -> fetchSort(catalogVersion, entityIndexId, service, sortIndexes, key, context);
				case CHAIN -> fetchChain(catalogVersion, entityIndexId, service, chainIndexes, key, context);
				case CARDINALITY -> {
					// handled by AttributeCardinalityIndexMapLoader
				}
				default -> throw new GenericEvitaInternalError(
					"Unknown attribute index type: " + key.indexType()
				);
			}
		}

		return new LoadedComponentBundle.AttributeIndexes(uniqueIndexes, filterIndexes, sortIndexes, chainIndexes);
	}

	private static void fetchUnique(
		long catalogVersion,
		int entityIndexId,
		@Nonnull String entityType,
		@Nonnull StoragePartPersistenceService<?> service,
		@Nonnull Map<AttributeIndexKey, UniqueIndex> uniqueIndexes,
		@Nonnull AttributeIndexStorageKey key
	) {
		final long primaryKey = AttributeIndexStoragePart.computeUniquePartId(
			entityIndexId, AttributeIndexType.UNIQUE, key.attribute(), service.getReadOnlyKeyCompressor()
		);
		final UniqueIndexStoragePart part = service.getStoragePart(
			catalogVersion, primaryKey, UniqueIndexStoragePart.class
		);
		isPremiseValid(
			part != null,
			"Unique index with id " + entityIndexId + " with key " + key.attribute() +
				" was not found in persistent storage!"
		);
		final AttributeIndexKey attributeIndexKey = part.getAttributeIndexKey();
		uniqueIndexes.put(
			attributeIndexKey,
			new UniqueIndex(
				entityType,
				attributeIndexKey,
				part.getType(),
				part.getUniqueValueToRecordId(),
				part.getRecordIds()
			)
		);
	}

	private static void fetchFilter(
		long catalogVersion,
		int entityIndexId,
		@Nonnull StoragePartPersistenceService<?> service,
		@Nonnull Map<AttributeIndexKey, FilterIndex> filterIndexes,
		@Nonnull AttributeIndexStorageKey key,
		@Nonnull LoadContext context
	) {
		final long primaryKey = AttributeIndexStoragePart.computeUniquePartId(
			entityIndexId, AttributeIndexType.FILTER, key.attribute(), service.getReadOnlyKeyCompressor()
		);
		final FilterIndexStoragePart part = service.getStoragePart(
			catalogVersion, primaryKey, FilterIndexStoragePart.class
		);
		isPremiseValid(
			part != null,
			"Filter index with id " + entityIndexId + " with key " + key.attribute() +
				" was not found in persistent storage!"
		);
		final AttributeIndexKey attributeIndexKey = part.getAttributeIndexKey();
		// TOBEDONE #538 - remove when legacy null-attributeType storage parts are gone
		//noinspection unchecked
		final Class<?> attributeType = ofNullable(part.getAttributeType())
			.orElseGet(() -> context.attributeTypeFetcher().apply(attributeIndexKey));
		filterIndexes.put(
			attributeIndexKey,
			new FilterIndex(
				part.getAttributeIndexKey(),
				part.getHistogramPoints(),
				part.getRangeIndex(),
				attributeType,
				part.getAttributeType() == null
			)
		);
	}

	private static void fetchSort(
		long catalogVersion,
		int entityIndexId,
		@Nonnull StoragePartPersistenceService<?> service,
		@Nonnull Map<AttributeIndexKey, SortIndex> sortIndexes,
		@Nonnull AttributeIndexStorageKey key,
		@Nonnull LoadContext context
	) {
		final long primaryKey = AttributeIndexStoragePart.computeUniquePartId(
			entityIndexId, AttributeIndexType.SORT, key.attribute(), service.getReadOnlyKeyCompressor()
		);
		final SortIndexStoragePart part = service.getStoragePart(
			catalogVersion, primaryKey, SortIndexStoragePart.class
		);
		isPremiseValid(
			part != null,
			"Sort index with id " + entityIndexId + " with key " + key.attribute() +
				" was not found in persistent storage!"
		);
		final AttributeIndexKey attributeIndexKey = part.getAttributeIndexKey();
		sortIndexes.put(
			attributeIndexKey,
			new SortIndex(
				part.getComparatorBase(),
				context.referenceKey(),
				part.getAttributeIndexKey(),
				part.getSortedRecords(),
				part.getSortedRecordsValues(),
				part.getValueCardinalities()
			)
		);
	}

	private static void fetchChain(
		long catalogVersion,
		int entityIndexId,
		@Nonnull StoragePartPersistenceService<?> service,
		@Nonnull Map<AttributeIndexKey, ChainIndex> chainIndexes,
		@Nonnull AttributeIndexStorageKey key,
		@Nonnull LoadContext context
	) {
		final long primaryKey = AttributeIndexStoragePart.computeUniquePartId(
			entityIndexId, AttributeIndexType.CHAIN, key.attribute(), service.getReadOnlyKeyCompressor()
		);
		final ChainIndexStoragePart part = service.getStoragePart(
			catalogVersion, primaryKey, ChainIndexStoragePart.class
		);
		isPremiseValid(
			part != null,
			"Chain index with id " + entityIndexId + " with key " + key.attribute() +
				" was not found in persistent storage!"
		);
		final AttributeIndexKey attributeIndexKey = part.getAttributeIndexKey();
		chainIndexes.put(
			attributeIndexKey,
			new ChainIndex(
				context.referenceKey(),
				part.getAttributeIndexKey(),
				part.getChains(),
				part.getElementStates()
			)
		);
	}

}
