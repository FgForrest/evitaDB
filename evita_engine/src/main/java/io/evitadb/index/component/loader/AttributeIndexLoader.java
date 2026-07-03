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
import io.evitadb.index.attribute.OwnerSortIndex;
import io.evitadb.index.attribute.OwnerUniqueIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.attribute.SortIndexView;
import io.evitadb.index.attribute.UniqueIndex;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.index.range.TransactionalRangePoint;
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.*;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.LeafStreamKey.StreamKind;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

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
				fetchFilter(
					catalogVersion, entityIndexId, service, filterIndexes,
					sharedValueIndexes, sharedRangeIndexes, key
				);
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
	 * to a slim part on reflush). Otherwise a standalone {@link OwnerUniqueIndex} is restored into
	 * `uniqueIndexes` in one of two shapes: PAGED, where the value tree's leaf pages are read in
	 * ascending key order and reassembled boundary-stable via {@link OwnerUniqueIndex#fromPersistedPages},
	 * or SINGLE, restored from the full inline part.
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
		} else if (part.isPaged()) {
			// OWNER, PAGED: standalone unique index whose value tree was persisted as individual leaf pages. Resolve the
			// stream id from the sub-index identity (registered at the first PAGED write) and read every listed leaf page
			// in ascending key order, then reassemble boundary-stable so the first post-restart commit rewrites only
			// genuinely-changed leaves.
			final int streamId = service.getReadOnlyKeyCompressor().getId(
				new LeafStreamKey(entityIndexId, new AttributeKeyWithIndexType(attributeIndexKey, AttributeIndexType.UNIQUE))
			);
			final int[] orderedPageSequences = part.getLeafPageSequences();
			final Serializable[][] perPageValues = new Serializable[orderedPageSequences.length][];
			final int[][] perPageRecordIds = new int[orderedPageSequences.length][];
			for (int i = 0; i < orderedPageSequences.length; i++) {
				final int pageSequence = orderedPageSequences[i];
				final UniqueIndexLeafPagePart leafPage = service.getStoragePart(
					catalogVersion, AbstractLeafPagePart.computeUniquePartId(streamId, pageSequence),
					UniqueIndexLeafPagePart.class
				);
				isPremiseValid(
					leafPage != null,
					"Unique index leaf page " + pageSequence + " (stream " + streamId + ") for key " + key.attribute() +
						" was not found in persistent storage!"
				);
				perPageValues[i] = leafPage.getValues();
				perPageRecordIds[i] = leafPage.getRecordIds();
			}
			uniqueIndexes.put(
				attributeIndexKey,
				OwnerUniqueIndex.fromPersistedPages(
					entityType, attributeIndexKey, part.getType(),
					orderedPageSequences, perPageValues, perPageRecordIds, part.getHighWaterPageSequence()
				)
			);
		} else {
			// OWNER, SINGLE: standalone (global-unique-localized) unique index restored from its full inline part
			uniqueIndexes.put(
				attributeIndexKey,
				new OwnerUniqueIndex(
					entityType,
					attributeIndexKey,
					part.getType(),
					Objects.requireNonNull(
						part.getValues(),
						"Owner unique part must carry the inline value column!"
					),
					Objects.requireNonNull(
						part.getRecordIds(),
						"Owner unique part must carry the inline payload column!"
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
		// the scale is frozen into the part at write time and read back verbatim (0 for non-BigDecimal attributes), so
		// reloaded keys are always interpreted at the scale they were written with — no schema re-derivation at load (see
		// the freeze rationale on FilterIndexStoragePart#indexedDecimalPlaces). A later schema change to the scale is
		// surfaced as drift on the next modification rather than silently reinterpreting the persisted keys.
		final int indexedDecimalPlaces = part.getIndexedDecimalPlaces();
		final Function<Object, Serializable> normalizer = FilterIndex.getNormalizer(plainType, indexedDecimalPlaces);
		final Comparator<?> comparator = FilterIndex.getComparator(attributeIndexKey, plainType);

		// the OWNED shared value→ValueToRecord tree (bucket axis) and the optional shared range companion (range axis)
		// are reloaded independently of each other, then wrapped together in a FilterIndexView
		final InvertedIndex shared = loadInvertedIndex(
			catalogVersion, entityIndexId, service, part, attributeIndexKey, key,
			plainType, normalizer, comparator, indexedDecimalPlaces
		);
		sharedValueIndexes.put(attributeIndexKey, shared);
		final RangeIndex rangeIndex = loadRangeIndex(
			catalogVersion, entityIndexId, service, part, attributeIndexKey, key
		);
		if (rangeIndex != null) {
			sharedRangeIndexes.put(attributeIndexKey, rangeIndex);
		}
		// the filter view wraps the shared tree (and shared range)
		filterIndexes.put(
			attributeIndexKey,
			new FilterIndexView(attributeIndexKey, shared, rangeIndex, attributeType, indexedDecimalPlaces)
		);
	}

	/**
	 * Loads the OWNED shared value→`ValueToRecord` tree (the bucket axis) of a FILTER part. A `SINGLE` part carries its
	 * buckets inline and is rebuilt via the standard buckets constructor; a `PAGED` part reads every listed leaf page in
	 * order — keyed by `pack(streamId, pageSequence)`, the stream id recomputed from the sub-index identity via the read-only
	 * compressor (it was registered at the first `PAGED` write) — and reassembles boundary-stable (one leaf per persisted
	 * page, page identities + change-detection baseline restored), so the first post-restart commit rewrites only
	 * genuinely-changed leaves rather than re-paginating the whole index.
	 *
	 * @param catalogVersion       the catalog version to read pages at
	 * @param entityIndexId        the owning entity index pk (part of the page-stream key)
	 * @param service              the storage-part persistence service to read from
	 * @param part                 the already-fetched FILTER root part
	 * @param attributeIndexKey    the sub-index identity (part of the page-stream key)
	 * @param key                  the manifest key, used only for failure messages
	 * @param plainType            the non-array value type used to build the tree
	 * @param normalizer           the canonical value normalizer (NFD / Instant / scaled-int)
	 * @param comparator           the value ordering comparator
	 * @param indexedDecimalPlaces the frozen scale for `BigDecimal` keys (0 otherwise)
	 * @return the reloaded owned value tree
	 */
	@Nonnull
	private static InvertedIndex loadInvertedIndex(
		long catalogVersion,
		int entityIndexId,
		@Nonnull StoragePartPersistenceService<?> service,
		@Nonnull FilterIndexStoragePart part,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull AttributeIndexStorageKey key,
		@Nonnull Class<?> plainType,
		@Nonnull Function<Object, Serializable> normalizer,
		@Nonnull Comparator<?> comparator,
		int indexedDecimalPlaces
	) {
		if (!part.isPaged()) {
			return new InvertedIndex(
				plainType, part.getHistogramPoints(), normalizer, comparator, indexedDecimalPlaces
			);
		}
		final int streamId = service.getReadOnlyKeyCompressor().getId(
			new LeafStreamKey(entityIndexId, new AttributeKeyWithIndexType(attributeIndexKey, AttributeIndexType.FILTER))
		);
		final int[] orderedPageSequences = part.getLeafPageSequences();
		final ValueToRecordBitmap[][] perPageBuckets = new ValueToRecordBitmap[orderedPageSequences.length][];
		for (int i = 0; i < orderedPageSequences.length; i++) {
			final int pageSequence = orderedPageSequences[i];
			final FilterIndexLeafPagePart leafPage = service.getStoragePart(
				catalogVersion, AbstractLeafPagePart.computeUniquePartId(streamId, pageSequence),
				FilterIndexLeafPagePart.class
			);
			isPremiseValid(
				leafPage != null,
				"Filter index leaf page " + pageSequence + " (stream " + streamId + ") for key " + key.attribute() +
					" was not found in persistent storage!"
			);
			perPageBuckets[i] = leafPage.getBuckets();
		}
		return InvertedIndex.fromPersistedPages(
			plainType, orderedPageSequences, perPageBuckets, part.getHighWaterPageSequence(),
			normalizer, comparator, indexedDecimalPlaces
		);
	}

	/**
	 * Loads the optional shared range companion (the range axis) of a FILTER part, mirroring {@link #loadInvertedIndex}.
	 * A non-range-paged part carries the range inline (or has none at all → `null`); a range-`PAGED` part reads every
	 * listed {@link RangeIndexLeafPagePart} in order — keyed by `join(rangeStreamId, pageSequence)`, the stream id resolved
	 * with {@link StreamKind#RANGE} — and reassembles boundary-stable.
	 *
	 * @param catalogVersion    the catalog version to read pages at
	 * @param entityIndexId     the owning entity index pk (part of the page-stream key)
	 * @param service           the storage-part persistence service to read from
	 * @param part              the already-fetched FILTER root part
	 * @param attributeIndexKey the sub-index identity (part of the page-stream key)
	 * @param key               the manifest key, used only for failure messages
	 * @return the reloaded range companion, or `null` when the attribute has none
	 */
	@Nullable
	private static RangeIndex loadRangeIndex(
		long catalogVersion,
		int entityIndexId,
		@Nonnull StoragePartPersistenceService<?> service,
		@Nonnull FilterIndexStoragePart part,
		@Nonnull AttributeIndexKey attributeIndexKey,
		@Nonnull AttributeIndexStorageKey key
	) {
		if (!part.isRangePaged()) {
			return part.getRangeIndex();
		}
		final int rangeStreamId = service.getReadOnlyKeyCompressor().getId(
			new LeafStreamKey(
				entityIndexId,
				new AttributeKeyWithIndexType(attributeIndexKey, AttributeIndexType.FILTER),
				StreamKind.RANGE
			)
		);
		final int[] rangePageSequences = part.getRangeLeafPageSequences();
		final TransactionalRangePoint[][] perPagePoints = new TransactionalRangePoint[rangePageSequences.length][];
		for (int i = 0; i < rangePageSequences.length; i++) {
			final int pageSequence = rangePageSequences[i];
			final RangeIndexLeafPagePart leafPage = service.getStoragePart(
				catalogVersion, AbstractLeafPagePart.computeUniquePartId(rangeStreamId, pageSequence),
				RangeIndexLeafPagePart.class
			);
			isPremiseValid(
				leafPage != null,
				"Range index leaf page " + pageSequence + " (stream " + rangeStreamId + ") for key " + key.attribute() +
					" was not found in persistent storage!"
			);
			perPagePoints[i] = leafPage.getPoints();
		}
		return RangeIndex.fromPersistedPages(
			rangePageSequences, perPagePoints, part.getRangeHighWaterPageSequence()
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
		// the scale is frozen into the part at write time and read back verbatim — 0 for non-BigDecimal attributes and
		// for compound sorts (which keep their exact BigDecimal natural order and are never scaled). No schema
		// re-derivation at load (see the freeze rationale on SortIndexStoragePart#indexedDecimalPlaces).
		final SortIndex.ComparatorSource[] comparatorBase = part.getComparatorBase();
		final int indexedDecimalPlaces = part.getIndexedDecimalPlaces();
		// view mode when a FILTER part exists for the same key (both-flagged single attribute): ignore the persisted
		// values/cardinalities (the slim part omits them) and resolve cardinality from the shared tree.
		// The supplier is resolved ONCE here to bind the view's direct shared-tree reference; the loaded map is stable and
		// AttributeIndex's constructor re-binds every view to its committed shared tree anyway (deriveSortViews).
		final boolean viewMode = sharedValueIndexes.containsKey(attributeIndexKey);
		final SortIndex sortIndex;
		if (viewMode) {
			// view mode: the slim part omits the positional sortedRecords; rebuild it byte-for-byte from the shared FILTER
			// tree (buckets in comparator order, ascending ids within each value). The persisted array is ignored even when
			// a legacy full part still carries one, so the index self-heals to the slim shape on the next reflush.
			final int[] sortedRecords =
				SortIndexView.reconstructSortedRecords(sharedValueIndexes.get(attributeIndexKey));
			sortIndex = SortIndex.create(
				comparatorBase, context.referenceKey(), attributeIndexKey, indexedDecimalPlaces,
				sortedRecords, part.getSortedRecordsValues(), part.getValueCardinalities(),
				() -> sharedValueIndexes.get(attributeIndexKey)
			);
		} else if (part.isPaged()) {
			// OWNER, PAGED: standalone (sort-only / compound) sort index whose owned value tree was persisted as individual
			// leaf pages. Resolve the SORT-typed stream id from the sub-index identity (registered at the first PAGED write)
			// and read every listed leaf page in ascending key order, then reassemble boundary-stable and reconstruct the
			// positional sortedRecords from the reloaded tree.
			final int streamId = service.getReadOnlyKeyCompressor().getId(
				new LeafStreamKey(entityIndexId, new AttributeKeyWithIndexType(attributeIndexKey, AttributeIndexType.SORT))
			);
			final int[] orderedPageSequences = part.getLeafPageSequencesOrThrowException();
			final ValueToRecordBitmap[][] perPageBuckets = new ValueToRecordBitmap[orderedPageSequences.length][];
			for (int i = 0; i < orderedPageSequences.length; i++) {
				final int pageSequence = orderedPageSequences[i];
				final SortIndexLeafPagePart leafPage = service.getStoragePart(
					catalogVersion, AbstractLeafPagePart.computeUniquePartId(streamId, pageSequence),
					SortIndexLeafPagePart.class
				);
				isPremiseValid(
					leafPage != null,
					"Sort index leaf page " + pageSequence + " (stream " + streamId + ") for key " + key.attribute() +
						" was not found in persistent storage!"
				);
				perPageBuckets[i] = leafPage.getBuckets();
			}
			sortIndex = OwnerSortIndex.fromPersistedPages(
				comparatorBase, context.referenceKey(), attributeIndexKey, indexedDecimalPlaces,
				orderedPageSequences, perPageBuckets, part.getHighWaterPageSequence()
			);
		} else {
			// OWNER, SINGLE / legacy: standalone sort index restored from its full inline part - adopt the persisted
			// sortedRecords directly (a migration-collapsed legacy part must NOT be reconstructed from the tree — its
			// blocks may be non-ascending)
			sortIndex = SortIndex.create(
				comparatorBase, context.referenceKey(), attributeIndexKey, indexedDecimalPlaces,
				part.getSortedRecords(), part.getSortedRecordsValues(), part.getValueCardinalities(), null
			);
		}
		sortIndexes.put(attributeIndexKey, sortIndex);
	}

	/**
	 * Restores a single CHAIN part (second pass) into `chainIndexes`. CHAIN has no owner/view split, so the
	 * {@link ChainIndex} is always rebuilt standalone in one of two shapes: PAGED, where the element tree's leaf pages are
	 * read in ascending logical order and reassembled boundary-stable via {@link ChainIndex#fromPersistedPages} (chain
	 * state / element order reconstructed from the reloaded pages), or SINGLE / legacy, restored from the full inline part.
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
		final ChainIndex chainIndex;
		if (part.isPaged()) {
			// PAGED: standalone chain index whose element tree was persisted as individual leaf pages. Resolve the
			// CHAIN-typed stream id from the sub-index identity (registered at the first PAGED write) and read every listed
			// leaf page in ascending logical order, then reassemble boundary-stable via ChainIndex.fromPersistedPages (one
			// tree leaf per persisted page - so the first post-restart commit rewrites only genuinely-changed leaves).
			final int streamId = service.getReadOnlyKeyCompressor().getId(
				new LeafStreamKey(entityIndexId, new AttributeKeyWithIndexType(attributeIndexKey, AttributeIndexType.CHAIN))
			);
			final int[] orderedPageSequences = part.getPageSequencesOrThrowException();
			final List<ChainIndexLeafPagePart> pages = new ArrayList<>(orderedPageSequences.length);
			for (final int pageSequence : orderedPageSequences) {
				final ChainIndexLeafPagePart leafPage = service.getStoragePart(
					catalogVersion, AbstractLeafPagePart.computeUniquePartId(streamId, pageSequence),
					ChainIndexLeafPagePart.class
				);
				isPremiseValid(
					leafPage != null,
					"Chain index leaf page " + pageSequence + " (stream " + streamId + ") for key " + key.attribute() +
						" was not found in persistent storage!"
				);
				pages.add(leafPage);
			}
			chainIndex = ChainIndex.fromPersistedPages(
				context.referenceKey(), attributeIndexKey, pages, part.getHighWaterPageSequence()
			);
		} else {
			// SINGLE / legacy: standalone chain index restored from its full inline part (chains + element states)
			chainIndex = new ChainIndex(
				context.referenceKey(), attributeIndexKey, part.getChains(), part.getElementStates()
			);
		}
		chainIndexes.put(attributeIndexKey, chainIndex);
	}

}
