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
import io.evitadb.index.attribute.FilterIndexView;
import io.evitadb.index.attribute.OwnerUniqueIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.attribute.UniqueIndex;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.range.RangeIndex;
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
import java.util.Objects;

import static io.evitadb.utils.Assert.isPremiseValid;

/**
 * Reloads the four flat per-attribute maps owned by `AttributeIndex` (UNIQUE / FILTER / SORT /
 * CHAIN) from persistent storage in a single pass — all four maps are driven by the same
 * `EntityIndexStoragePart.getAttributeIndexes()` set.
 *
 * The CARDINALITY type — also stored under the same `AttributeIndexStorageKey` namespace — is
 * intentionally **not** handled here; it lives in {@link AttributeCardinalityIndexMapLoader} so
 * that subclasses without cardinality (`GlobalEntityIndex`, `ReducedEntityIndex`) need not
 * declare an empty cardinality bundle.
 */
public final class AttributeIndexLoader implements ComponentLoader {

	/**
	 * Rehydrates all four per-attribute maps in two ordered passes over the same
	 * `manifest.getAttributeIndexes()` set, returning them in a
	 * {@link LoadedComponentBundle.AttributeIndexes} bundle alongside the shared value/range trees
	 * that back the owner/view split.
	 *
	 * Order matters because SORT and UNIQUE detect "view mode" by the presence of a FILTER part
	 * under the same key:
	 *
	 * - A preliminary count pass pre-sizes each target map to avoid rehash churn.
	 * - FIRST pass — restore every FILTER part: build the owned shared value→`ValueToRecord`
	 *   tree (plus shared range) and wrap it in a {@link FilterIndexView}. This must complete
	 *   before the second pass so SORT/UNIQUE can find the shared tree and view for their key.
	 * - SECOND pass — restore UNIQUE (folded VIEW when a FILTER part exists for the key,
	 *   otherwise standalone {@link OwnerUniqueIndex}), SORT (view mode vs. standalone) and CHAIN.
	 *
	 * CARDINALITY parts share the same key namespace but are skipped here — see the class JavaDoc.
	 *
	 * @param context immutable per-call bundle of fetch dependencies
	 * @return the populated {@link LoadedComponentBundle.AttributeIndexes} bundle
	 */
	@Override
	@Nonnull
	public LoadedComponentBundle load(@Nonnull LoadContext context) {
		final EntityIndexStoragePart manifest = context.entityIndexStoragePart();
		// pre-size each map by counting per-type entries — two-pass to avoid resize overhead. UNIQUE is a standalone
		// structure (keyed by createUniqueAttributeKey): folding global-unique into the per-locale filter tree is
		// semantically impossible.
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
		final Map<AttributeIndexKey, UniqueIndex> uniqueViewIndexes = CollectionUtils.createHashMap(uniqueCount);
		final Map<AttributeIndexKey, SortIndex> sortIndexes = CollectionUtils.createHashMap(sortCount);
		final Map<AttributeIndexKey, ChainIndex> chainIndexes = CollectionUtils.createHashMap(chainCount);
		final Map<AttributeIndexKey, InvertedIndex> sharedValueIndexes = CollectionUtils.createHashMap(filterCount);
		final Map<AttributeIndexKey, RangeIndex> sharedRangeIndexes = CollectionUtils.createHashMap(filterCount);

		final StoragePartPersistenceService<?> service = context.storagePartService();
		final int entityIndexId = context.entityIndexId();
		final long catalogVersion = context.catalogVersion();
		final String entityName = context.entitySchema().getName();

		// FIRST pass: build the shared trees + filter views from FILTER parts (so SORT can discover view mode)
		for (final AttributeIndexStorageKey key : manifest.getAttributeIndexes()) {
			if (key.indexType() == AttributeIndexType.FILTER) {
				fetchFilter(catalogVersion, entityIndexId, service, filterIndexes, sharedValueIndexes, sharedRangeIndexes, key);
			}
		}
		// SECOND pass: UNIQUE (owner standalone, or folded VIEW when a FILTER part exists for the key) + SORT (view mode
		// when a FILTER part exists for the key) + CHAIN
		for (final AttributeIndexStorageKey key : manifest.getAttributeIndexes()) {
			switch (key.indexType()) {
				case UNIQUE -> fetchUnique(
					catalogVersion, entityIndexId, entityName, service,
					uniqueIndexes, uniqueViewIndexes, filterIndexes, sharedValueIndexes, key
				);
				case FILTER, CARDINALITY -> {
					// FILTER handled above; CARDINALITY handled by AttributeCardinalityIndexMapLoader
				}
				case SORT -> fetchSort(catalogVersion, entityIndexId, service, sortIndexes, sharedValueIndexes, key, context);
				case CHAIN -> fetchChain(catalogVersion, entityIndexId, service, chainIndexes, key, context);
				default -> throw new GenericEvitaInternalError(
					"Unknown attribute index type: " + key.indexType()
				);
			}
		}

		return new LoadedComponentBundle.AttributeIndexes(
			uniqueIndexes, filterIndexes, uniqueViewIndexes, sortIndexes, chainIndexes, sharedValueIndexes, sharedRangeIndexes
		);
	}

	/**
	 * Restores a single UNIQUE part and routes it to the right target map. When a shared FILTER
	 * tree already exists under the same key (foldable unique — its unique key equals its filter
	 * key), a view-mode {@link UniqueIndex} is built over the first-pass filter view and pushed
	 * into `uniqueViewIndexes`; any persisted value map / record bitmap is ignored (self-healing
	 * to a slim part on reflush). Otherwise a standalone {@link OwnerUniqueIndex} is restored from
	 * the full part into `uniqueIndexes`.
	 *
	 * @param entityType        entity type name passed to the rebuilt index
	 * @param uniqueIndexes     owner (standalone) target map, populated for non-foldable keys
	 * @param uniqueViewIndexes folded-view target map, populated for foldable keys
	 * @param filterIndexes     first-pass filter views, source of the wrapped view for foldable keys
	 * @param sharedValueIndexes first-pass shared trees, probed to detect the foldable case
	 * @param key               manifest key identifying the UNIQUE part to fetch
	 */
	private static void fetchUnique(
		long catalogVersion,
		int entityIndexId,
		@Nonnull String entityType,
		@Nonnull StoragePartPersistenceService<?> service,
		@Nonnull Map<AttributeIndexKey, UniqueIndex> uniqueIndexes,
		@Nonnull Map<AttributeIndexKey, UniqueIndex> uniqueViewIndexes,
		@Nonnull Map<AttributeIndexKey, FilterIndex> filterIndexes,
		@Nonnull Map<AttributeIndexKey, InvertedIndex> sharedValueIndexes,
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
		// structural view detection: a FILTER (shared) tree under the SAME key means this is a FOLDABLE unique attribute
		// (its unique key equals its filter key). Build a VIEW and ignore any persisted value map / record-id bitmap —
		// a slim part carries none, and a legacy full part is intentionally discarded (self-healing to slim on reflush).
		if (sharedValueIndexes.containsKey(attributeIndexKey)) {
			uniqueViewIndexes.put(
				attributeIndexKey,
				UniqueIndex.createView(
					entityType, attributeIndexKey, part.getType(),
					// the FIRST pass already built every FILTER view, so the shared filter view for this key is present
					filterIndexes.get(attributeIndexKey)
				)
			);
		} else {
			// OWNER: standalone (global-unique-localized) unique index restored from its full part
			uniqueIndexes.put(
				attributeIndexKey,
				new OwnerUniqueIndex(
					entityType,
					attributeIndexKey,
					part.getType(),
					Objects.requireNonNull(
						part.getUniqueValueToRecordId(),
						"Owner unique part must carry the value-to-record map!"
					),
					Objects.requireNonNull(
						part.getRecordIds(),
						"Owner unique part must carry the record-id bitmap!"
					)
				)
			);
		}
	}

	/**
	 * Restores a single FILTER part (first pass). Rebuilds the owned shared value→`ValueToRecord`
	 * tree from the persisted histogram points (using the canonical NFD/Instant normalizer and the
	 * key's comparator), keeps the optional shared range, and wraps both in a
	 * {@link FilterIndexView}. Populates `sharedValueIndexes`, `sharedRangeIndexes` and
	 * `filterIndexes` so the second pass can detect view mode for SORT/UNIQUE under the same key.
	 *
	 * @param filterIndexes      filter-view target map
	 * @param sharedValueIndexes shared value-tree target map (the owned tree the view wraps)
	 * @param sharedRangeIndexes shared range target map (populated only when the part carries a range)
	 * @param key                manifest key identifying the FILTER part to fetch
	 */
	private static void fetchFilter(
		long catalogVersion,
		int entityIndexId,
		@Nonnull StoragePartPersistenceService<?> service,
		@Nonnull Map<AttributeIndexKey, FilterIndex> filterIndexes,
		@Nonnull Map<AttributeIndexKey, InvertedIndex> sharedValueIndexes,
		@Nonnull Map<AttributeIndexKey, RangeIndex> sharedRangeIndexes,
		@Nonnull AttributeIndexStorageKey key
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
		final Class<?> attributeType = part.getAttributeType();
		final Class<?> plainType = attributeType.isArray() ? attributeType.getComponentType() : attributeType;
		// build the OWNED shared value→ValueToRecord tree from the persisted histogram points; the normalizer is the
		// shared NFD/Instant one so keys are canonical
		final InvertedIndex shared = new InvertedIndex(
			part.getHistogramPoints(),
			FilterIndex.getNormalizer(plainType),
			FilterIndex.getComparator(attributeIndexKey, plainType)
		);
		sharedValueIndexes.put(attributeIndexKey, shared);
		final RangeIndex rangeIndex = part.getRangeIndex();
		if (rangeIndex != null) {
			sharedRangeIndexes.put(attributeIndexKey, rangeIndex);
		}
		// the filter view wraps the shared tree (and shared range)
		filterIndexes.put(
			attributeIndexKey,
			new FilterIndexView(attributeIndexKey, shared, rangeIndex, attributeType)
		);
	}

	/**
	 * Restores a single SORT part (second pass) into `sortIndexes`. Runs in view mode when a FILTER
	 * part exists for the same key (both-flagged attribute): the persisted values/cardinalities are
	 * ignored (the slim part omits them) and cardinality is resolved from the shared tree via a
	 * supplier bound here; otherwise the standalone {@link SortIndex} keeps its own persisted data.
	 *
	 * @param sortIndexes        SORT target map
	 * @param sharedValueIndexes first-pass shared trees, probed for view mode and bound as the supplier
	 * @param key                manifest key identifying the SORT part to fetch
	 */
	private static void fetchSort(
		long catalogVersion,
		int entityIndexId,
		@Nonnull StoragePartPersistenceService<?> service,
		@Nonnull Map<AttributeIndexKey, SortIndex> sortIndexes,
		@Nonnull Map<AttributeIndexKey, InvertedIndex> sharedValueIndexes,
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
		// view mode when a FILTER part exists for the same key (both-flagged single attribute): ignore the persisted
		// values/cardinalities (the slim part omits them) and resolve cardinality from the shared tree.
		// The supplier is resolved ONCE here to bind the view's direct shared-tree reference; the loaded map is stable and
		// AttributeIndex's constructor re-binds every view to its committed shared tree anyway (deriveSortViews).
		final SortIndex sortIndex = SortIndex.create(
			part.getComparatorBase(),
			context.referenceKey(),
			attributeIndexKey,
			part.getSortedRecords(),
			part.getSortedRecordsValues(),
			part.getValueCardinalities(),
			sharedValueIndexes.containsKey(attributeIndexKey)
				? () -> sharedValueIndexes.get(attributeIndexKey)
				: null
		);
		sortIndexes.put(attributeIndexKey, sortIndex);
	}

	/**
	 * Restores a single CHAIN part (second pass) into `chainIndexes`. CHAIN has no owner/view split,
	 * so the {@link ChainIndex} is always rebuilt standalone from its persisted chains and element
	 * states.
	 *
	 * @param chainIndexes CHAIN target map
	 * @param key          manifest key identifying the CHAIN part to fetch
	 */
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
