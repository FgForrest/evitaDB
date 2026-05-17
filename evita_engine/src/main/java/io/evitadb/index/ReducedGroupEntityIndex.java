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

package io.evitadb.index;

import io.evitadb.api.CatalogState;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.core.expression.trigger.DependencyType;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.attribute.AttributeIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.cardinality.AttributeCardinalityIndex;
import io.evitadb.index.component.AttributeCardinalityIndexMapComponent;
import io.evitadb.index.component.GroupCardinalityComponent;
import io.evitadb.index.component.HistogramIndexMapComponent;
import io.evitadb.index.facet.FacetIndex;
import io.evitadb.index.hierarchy.HierarchyIndex;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.price.PriceRefIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexStorageKey;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.StringUtils;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static io.evitadb.core.transaction.Transaction.getTransactionalLayerMaintainer;
import static io.evitadb.index.attribute.AttributeIndex.createAttributeKey;
import static java.util.Optional.ofNullable;

/**
 * Reduced group entity index is a specialization of {@link AbstractReducedEntityIndex} that adds cardinality
 * tracking for primary keys and filter attributes. This is necessary because multiple references from different
 * entities can share the same group, causing the same entity primary key and attribute values to be indexed
 * multiple times.
 *
 * Without cardinality tracking, the group-level index would fail when a sortable attribute is indexed more
 * than once for the same record (producing "Record id already present in sort index!" errors) and would
 * incorrectly remove data when only some of the duplicate references are removed.
 *
 * This class handles {@link EntityIndexType#REFERENCED_GROUP_ENTITY} indexes and uses simple inline
 * `Map<Integer, Integer>` cardinality tracking for primary keys.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ReducedGroupEntityIndex extends AbstractReducedEntityIndex implements HistogramCapableEntityIndex {

	/**
	 * Transactional flag that tracks whether the PK cardinality data has changed and needs to be persisted.
	 */
	@Nonnull private final TransactionalBoolean cardinalityDirty;
	/**
	 * This map keeps cardinality of entity primary keys. An entity can appear in a group via multiple
	 * references, so we need to track how many times each entity PK was added and only actually
	 * add/remove from the bitmap when the cardinality transitions to/from zero.
	 */
	@Nonnull private final TransactionalMap<Integer, Integer> pkCardinalities;
	/**
	 * Index that for each referenced entity primary key keeps the bitmap of all entity primary keys
	 * that reference it within this group.
	 */
	@Nonnull private final TransactionalMap<Integer, TransactionalBitmap> referencedPrimaryKeysIndex;

	/**
	 * This transactional map contains cardinality tracking for each filter attribute. When the same attribute
	 * value is indexed multiple times (from different references sharing the same group), we need to track
	 * the cardinality so that we only add/remove from the actual filter index on transitions to/from zero.
	 */
	@Nonnull private final TransactionalMap<AttributeIndexKey, AttributeCardinalityIndex> cardinalityIndexes;
	/**
	 * Per-histogram-name index storing bucketed histogram data (filter indexes + cardinality tracking)
	 * for all locale variants of each histogram definition.
	 */
	@Nonnull private final TransactionalMap<String, HistogramIndex> histogramIndexes;

	/**
	 * Creates a new empty reduced group entity index.
	 *
	 * @param primaryKey     the primary key of this index
	 * @param entityType     the type of entity being indexed
	 * @param entityIndexKey the key identifying this index
	 */
	public ReducedGroupEntityIndex(
		int primaryKey,
		@Nonnull String entityType,
		@Nonnull EntityIndexKey entityIndexKey
	) {
		super(primaryKey, entityType, entityIndexKey);
		Assert.isPremiseValid(
			entityIndexKey.type() == EntityIndexType.REFERENCED_GROUP_ENTITY,
			() -> "ReducedGroupEntityIndex only supports REFERENCED_GROUP_ENTITY type, got: " +
				entityIndexKey.type()
		);
		this.cardinalityDirty = new TransactionalBoolean();
		this.pkCardinalities = new TransactionalMap<>(CollectionUtils.createHashMap(16));
		this.referencedPrimaryKeysIndex = new TransactionalMap<>(
			CollectionUtils.createHashMap(16), TransactionalBitmap.class, TransactionalBitmap::new
		);
		this.cardinalityIndexes = new TransactionalMap<>(
			CollectionUtils.createHashMap(16), AttributeCardinalityIndex.class, Function.identity()
		);
		this.histogramIndexes = new TransactionalMap<>(
			CollectionUtils.createHashMap(4), HistogramIndex.class, Function.identity()
		);
		registerSubclassComponents();
		// fresh empty index — every component contributes an empty manifest, so the baseline
		// captured here is the immutable empty set, preventing spurious manifest emits
		captureOriginalsFromComponents();
	}

	/**
	 * Creates a reduced group entity index from persisted data.
	 *
	 * @param primaryKey                 the primary key of this index
	 * @param entityIndexKey             the key identifying this index
	 * @param version                    the version of this index
	 * @param entityIds                  bitmap of entity primary keys in this index
	 * @param entityIdsByLanguage        entity primary keys grouped by locale
	 * @param attributeIndex             the attribute index
	 * @param priceIndex                 the price reference index
	 * @param hierarchyIndex             the hierarchy index
	 * @param facetIndex                 the facet index
	 * @param pkCardinalities            cardinality tracking for entity primary keys
	 * @param referencedPrimaryKeysIndex maps referenced entity PKs to bitmaps of entity PKs
	 * @param cardinalityIndexes         cardinality tracking for filter attributes
	 * @param histogramIndexes           histogram indexes by histogram name
	 */
	public ReducedGroupEntityIndex(
		int primaryKey,
		@Nonnull EntityIndexKey entityIndexKey,
		int version,
		@Nonnull Bitmap entityIds,
		@Nonnull Map<Locale, TransactionalBitmap> entityIdsByLanguage,
		@Nonnull AttributeIndex attributeIndex,
		@Nonnull PriceRefIndex priceIndex,
		@Nonnull HierarchyIndex hierarchyIndex,
		@Nonnull FacetIndex facetIndex,
		@Nonnull Map<Integer, Integer> pkCardinalities,
		@Nonnull Map<Integer, TransactionalBitmap> referencedPrimaryKeysIndex,
		@Nonnull Map<AttributeIndexKey, AttributeCardinalityIndex> cardinalityIndexes,
		@Nonnull Map<String, HistogramIndex> histogramIndexes
	) {
		super(
			primaryKey, entityIndexKey, version,
			entityIds, entityIdsByLanguage,
			attributeIndex, priceIndex, hierarchyIndex, facetIndex
		);
		this.cardinalityDirty = new TransactionalBoolean();
		this.pkCardinalities = new TransactionalMap<>(pkCardinalities);
		this.referencedPrimaryKeysIndex = new TransactionalMap<>(
			referencedPrimaryKeysIndex, TransactionalBitmap.class, TransactionalBitmap::new
		);
		this.cardinalityIndexes = new TransactionalMap<>(
			cardinalityIndexes, AttributeCardinalityIndex.class, Function.identity()
		);
		this.histogramIndexes = new TransactionalMap<>(
			histogramIndexes, HistogramIndex.class, Function.identity()
		);
		registerSubclassComponents();
		// re-capture the change-detection baseline from the components now that every subclass
		// sub-index map is populated — this replaces the former collectAttributeIndexStorageKeys()
		// helper which only patched in CARDINALITY keys and ignored histograms
		captureOriginalsFromComponents();
	}

	/**
	 * Creates a reduced group entity index as a transactional copy. This constructor is used internally by
	 * {@link #createCopyForNewCatalogAttachment(CatalogState)} and preserves original storage part state.
	 *
	 * @param primaryKey                  the primary key of this index
	 * @param indexKey                    the key identifying this index
	 * @param version                     the version of this index
	 * @param entityIds                   transactional bitmap of entity primary keys
	 * @param entityIdsByLanguage         transactional map of entity primary keys by locale
	 * @param attributeIndex              the attribute index
	 * @param hierarchyIndex              the hierarchy index
	 * @param facetIndex                  the facet index
	 * @param originalHierarchyIndexEmpty whether the hierarchy index was originally empty
	 * @param originalAttributeIndexes    original attribute index storage keys (incl. CARDINALITY)
	 * @param originalPriceIndexes        original price index keys
	 * @param originalFacetIndexes        original facet index referenced entities
	 * @param originalHistogramKeys       original histogram index storage keys
	 * @param priceIndex                  the price reference index
	 * @param pkCardinalities             cardinality tracking for entity primary keys
	 * @param referencedPrimaryKeysIndex  maps referenced entity PKs to bitmaps of entity PKs
	 * @param cardinalityIndexes          cardinality tracking for filter attributes
	 * @param histogramIndexes            histogram indexes by histogram name
	 */
	private ReducedGroupEntityIndex(
		int primaryKey,
		@Nonnull EntityIndexKey indexKey,
		int version,
		@Nonnull TransactionalBitmap entityIds,
		@Nonnull TransactionalMap<Locale, TransactionalBitmap> entityIdsByLanguage,
		@Nonnull AttributeIndex attributeIndex,
		@Nonnull HierarchyIndex hierarchyIndex,
		@Nonnull FacetIndex facetIndex,
		boolean originalHierarchyIndexEmpty,
		@Nonnull Set<AttributeIndexStorageKey> originalAttributeIndexes,
		@Nonnull Set<PriceIndexKey> originalPriceIndexes,
		@Nonnull Set<String> originalFacetIndexes,
		@Nonnull Set<HistogramIndexStorageKey> originalHistogramKeys,
		@Nonnull PriceRefIndex priceIndex,
		@Nonnull TransactionalMap<Integer, Integer> pkCardinalities,
		@Nonnull TransactionalMap<Integer, TransactionalBitmap> referencedPrimaryKeysIndex,
		@Nonnull TransactionalMap<AttributeIndexKey, AttributeCardinalityIndex> cardinalityIndexes,
		@Nonnull TransactionalMap<String, HistogramIndex> histogramIndexes
	) {
		super(
			primaryKey, indexKey, version, entityIds,
			entityIdsByLanguage, attributeIndex, hierarchyIndex, facetIndex,
			originalHierarchyIndexEmpty,
			originalAttributeIndexes, originalPriceIndexes, originalFacetIndexes,
			priceIndex
		);
		this.cardinalityDirty = new TransactionalBoolean();
		this.pkCardinalities = pkCardinalities;
		this.referencedPrimaryKeysIndex = referencedPrimaryKeysIndex;
		this.cardinalityIndexes = cardinalityIndexes;
		this.histogramIndexes = histogramIndexes;
		// preserve the histogram baseline from the source instance — the base constructor only
		// handles UNIQUE/FILTER/SORT/CHAIN + facet + price + hierarchy baselines, so subclass-only
		// histogram keys must be propagated explicitly
		this.originalHistogramKeys = originalHistogramKeys;
		registerSubclassComponents();
		// do NOT call captureOriginalsFromComponents here — this constructor is the "preserve
		// originals" path used by catalog re-attachment, and recapturing would overwrite the
		// caller-provided baselines with the current live state, losing dirty/change tracking
	}

	@Override
	public boolean isEmpty() {
		// null check required: parent constructor calls isEmpty() before subclass fields are initialized
		return super.isEmpty() && this.pkCardinalities.isEmpty() && this.histogramIndexes.isEmpty();
	}

	@Nonnull
	@Override
	public ReducedGroupEntityIndex createCopyForNewCatalogAttachment(@Nonnull CatalogState catalogState) {
		return new ReducedGroupEntityIndex(
			this.primaryKey, this.indexKey, this.version,
			this.entityIds, this.entityIdsByLanguage,
			this.attributeIndex,
			this.hierarchyIndex,
			this.facetIndex,
			this.originalHierarchyIndexEmpty,
			this.originalAttributeIndexes,
			this.originalPriceIndexes,
			this.originalFacetIndexes,
			this.originalHistogramKeys,
			getPriceIndex().createCopyForNewCatalogAttachment(catalogState),
			this.pkCardinalities,
			this.referencedPrimaryKeysIndex,
			this.cardinalityIndexes,
			this.histogramIndexes
		);
	}

	/**
	 * Returns the set of referenced entity primary keys (facet PKs) tracked within this group index. Each key in
	 * the returned set represents a distinct facet entity within the group, and maps to a bitmap of owner entity PKs
	 * that reference it.
	 *
	 * Used by ReevaluateExpressionExecutor during {@link DependencyType#GROUP_ENTITY_ATTRIBUTE} resolution
	 * to recover facet PKs from the group index (the discriminator carries the group PK, not the facet PK).
	 *
	 * @return unmodifiable set of referenced entity primary keys within this group
	 */
	@Nonnull
	public Set<Integer> getReferencedEntityPrimaryKeys() {
		return Collections.unmodifiableSet(this.referencedPrimaryKeysIndex.keySet());
	}

	/**
	 * Returns all referenced entity (facet) primary keys tracked within this group index as a
	 * {@link Bitmap}. This is the bitmap-typed companion to {@link #getReferencedEntityPrimaryKeys()},
	 * used at query time by histogram boundary resolution to intersect with the source attribute's
	 * {@link FilterIndex#getRecordsEqualToFormula} bitmap.
	 *
	 * @return bitmap of referenced entity primary keys within this group, may be {@link EmptyBitmap#INSTANCE}
	 */
	@Nonnull
	public Bitmap getAllReferencedPrimaryKeys() {
		if (this.referencedPrimaryKeysIndex.isEmpty()) {
			return EmptyBitmap.INSTANCE;
		}
		final RoaringBitmapWriter<RoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
		for (final Integer referencedPk : this.referencedPrimaryKeysIndex.keySet()) {
			writer.add(referencedPk);
		}
		return new BaseBitmap(writer.get());
	}

	/**
	 * Returns the bitmap of owner entity PKs that reference the given entity (facet) within this group, or `null`
	 * if the referenced entity PK is not tracked in this group index.
	 *
	 * Used by ReevaluateExpressionExecutor to obtain per-facet owner PK
	 * bitmaps during {@link DependencyType#GROUP_ENTITY_ATTRIBUTE} resolution.
	 *
	 * @param referencedEntityPK the primary key of the referenced entity (facet PK)
	 * @return bitmap of owner entity PKs, or `null` if not found
	 */
	@Nullable
	public Bitmap getOwnerPKsForReferencedEntity(int referencedEntityPK) {
		return this.referencedPrimaryKeysIndex.get(referencedEntityPK);
	}

	/**
	 * Returns the subset of the given `reduced-index PKs` that are currently tracked as referenced
	 * entity primary keys in this group. Mirror of
	 * {@link io.evitadb.index.cardinality.ReferenceTypeCardinalityIndex#getReferencedPrimaryKeysForIndexPks}
	 * on RTEI, provided here for API parity so histogram boundary resolution can convert
	 * {@link FilterIndex#getRecordsEqualTo} results uniformly on both grouped and non-grouped
	 * source indexes.
	 *
	 * In RGEI the reference-attribute FilterIndex is keyed on referenced entity PK (the recordId
	 * is swapped by {@code ReferenceIndexMutator} via `executeWithDifferentPrimaryKeyToIndex`).
	 * The "reduced-index PK" thus coincides with the referenced entity PK for this index, making
	 * this method a filter over {@link #referencedPrimaryKeysIndex} keys.
	 *
	 * @param indexPrimaryKeys bitmap produced by {@link FilterIndex#getRecordsEqualTo} against the
	 *                         reference-attribute FilterIndex
	 * @return bitmap of referenced entity PKs, never {@code null}, may be {@link EmptyBitmap#INSTANCE}
	 */
	@Nonnull
	public Bitmap getReferencedPrimaryKeysForIndexPks(@Nonnull Bitmap indexPrimaryKeys) {
		if (indexPrimaryKeys.isEmpty() || this.referencedPrimaryKeysIndex.isEmpty()) {
			return EmptyBitmap.INSTANCE;
		}
		final RoaringBitmap indexPksRoaring = RoaringBitmapBackedBitmap.getRoaringBitmap(indexPrimaryKeys);
		final RoaringBitmapWriter<RoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
		for (final Integer referencedPk : this.referencedPrimaryKeysIndex.keySet()) {
			if (indexPksRoaring.contains(referencedPk)) {
				writer.add(referencedPk);
			}
		}
		final RoaringBitmap result = writer.get();
		return result.isEmpty() ? EmptyBitmap.INSTANCE : new BaseBitmap(result);
	}

	/**
	 * Registers the three subclass-owned {@link io.evitadb.index.component.IndexComponent}
	 * adapters into the parent {@link EntityIndex#addComponent} loop so cardinality / histogram /
	 * group-cardinality flush, reset and remove-layer all flow through the uniform component path.
	 *
	 * Called from every constructor right after the subclass fields are populated and before
	 * {@link #captureOriginalsFromComponents()} (when applicable). The registration order is
	 * locked to today's flush order: attribute-cardinality first, then histograms, then the
	 * group-cardinality dirty-flag-driven block.
	 */
	private void registerSubclassComponents() {
		addComponent(new AttributeCardinalityIndexMapComponent(this.cardinalityIndexes, this.indexKey));
		addComponent(new HistogramIndexMapComponent(this.histogramIndexes, this.indexKey));
		addComponent(
			new GroupCardinalityComponent(
				this.cardinalityDirty,
				this.pkCardinalities,
				this.referencedPrimaryKeysIndex,
				getRepresentativeReferenceKey().referenceName()
			)
		);
	}

	/**
	 * Single-arg version is unsupported - use {@link #insertPrimaryKeyIfMissing(int, int)} instead.
	 *
	 * @throws UnsupportedOperationException always
	 */
	@Override
	public boolean insertPrimaryKeyIfMissing(int entityPrimaryKey) {
		throw new UnsupportedOperationException(
			"Use insertPrimaryKeyIfMissing(int entityPrimaryKey, int referencedEntityPrimaryKey) instead!"
		);
	}

	/**
	 * Inserts the entity primary key into the index while tracking cardinality. The key is only actually added
	 * to the bitmap when it transitions from absent to present (cardinality 0 -> 1).
	 *
	 * @param entityPrimaryKey           the primary key of the owning entity
	 * @param referencedEntityPrimaryKey the primary key of the referenced entity whose reference leads to
	 *                                   this group
	 * @return `true` only when this insert causes the entity to enter the index for the first time
	 * (cardinality 0 -> 1); `false` for subsequent inserts from other references that already had the
	 * entity registered. Callers use the return value to gate entity-level one-shot bookkeeping (prices,
	 * entity attributes, entity locales) so that data shared across all references resolving to this
	 * group index is indexed exactly once per (entity, RGEI) pair. Per-reference data (facet entries,
	 * reference attributes) is unaffected and must continue to be indexed on every call.
	 */
	public boolean insertPrimaryKeyIfMissing(int entityPrimaryKey, int referencedEntityPrimaryKey) {
		// track the referenced entity -> entity PK mapping
		TransactionalBitmap bitmap = this.referencedPrimaryKeysIndex.get(referencedEntityPrimaryKey);
		if (bitmap == null) {
			bitmap = new TransactionalBitmap();
			this.referencedPrimaryKeysIndex.put(referencedEntityPrimaryKey, bitmap);
		}
		bitmap.add(entityPrimaryKey);

		this.cardinalityDirty.setToTrue();
		final int newCount = this.pkCardinalities.compute(
			entityPrimaryKey, (k, v) -> v == null ? 1 : v + 1
		);
		// only add to the bitmap on the first occurrence
		if (newCount == 1) {
			super.insertPrimaryKeyIfMissing(entityPrimaryKey);
			return true;
		}
		return false;
	}

	/**
	 * Single-arg version is unsupported - use {@link #removePrimaryKey(int, int)} instead.
	 *
	 * @throws UnsupportedOperationException always
	 */
	@Override
	public boolean removePrimaryKey(int entityPrimaryKey) {
		throw new UnsupportedOperationException(
			"Use removePrimaryKey(int entityPrimaryKey, int referencedEntityPrimaryKey) instead!"
		);
	}

	/**
	 * Removes the entity primary key from the index while tracking cardinality. The key is only actually removed
	 * from the bitmap when its cardinality transitions from 1 -> 0.
	 *
	 * @param entityPrimaryKey           the primary key of the owning entity
	 * @param referencedEntityPrimaryKey the primary key of the referenced entity whose reference leads to
	 *                                   this group
	 * @return `true` only when this removal causes the entity to leave the index entirely (cardinality
	 * 1 -> 0); `false` for earlier removals that still leave other references contributing. Callers use
	 * the return value to gate entity-level one-shot cleanup (prices, entity attributes, entity locales)
	 * so that data shared across all references resolving to this group index is de-indexed exactly once
	 * per (entity, RGEI) pair. Per-reference data (facet entries, reference attributes) is unaffected
	 * and must continue to be de-indexed on every call.
	 */
	public boolean removePrimaryKey(int entityPrimaryKey, int referencedEntityPrimaryKey) {
		// remove the referenced entity -> entity PK mapping
		final TransactionalBitmap bitmap = this.referencedPrimaryKeysIndex.get(referencedEntityPrimaryKey);
		Assert.isPremiseValid(
			bitmap != null,
			() -> "Referenced entity primary key " + referencedEntityPrimaryKey +
				" is unexpectedly not found in the group index!"
		);
		bitmap.remove(entityPrimaryKey);
		if (bitmap.isEmpty()) {
			final TransactionalBitmap removedBitmap = this.referencedPrimaryKeysIndex.remove(referencedEntityPrimaryKey);
			final TransactionalLayerMaintainer transactionalLayer = Transaction.getTransactionalLayerMaintainer();
			if (transactionalLayer != null && removedBitmap != null) {
				removedBitmap.removeLayer(transactionalLayer);
			}
		}

		this.cardinalityDirty.setToTrue();
		final Integer newCount = this.pkCardinalities.computeIfPresent(
			entityPrimaryKey, (k, v) -> v - 1
		);
		Assert.isPremiseValid(
			newCount != null,
			() -> "Cardinality of entity PK " + entityPrimaryKey + " is unexpectedly null!"
		);
		// only remove from the bitmap when the last occurrence is removed
		if (newCount == 0) {
			this.pkCardinalities.remove(entityPrimaryKey);
			super.removePrimaryKey(entityPrimaryKey);
			return true;
		}
		return false;
	}

	@Override
	public void insertFilterAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		assertPartitioningIndex(referenceSchema, attributeSchema);
		// first retrieve or create the cardinality index for given attribute
		final AttributeCardinalityIndex theCardinalityIndex = this.cardinalityIndexes.computeIfAbsent(
			createAttributeKey(referenceSchema, attributeSchema, allowedLocales, locale, value),
			lookupKey -> new AttributeCardinalityIndex(
				attributeSchema.getPlainType()
			)
		);
		if (value instanceof Serializable[] valueArray) {
			// for array values we need to add only new items to the index (their former cardinality was zero)
			final Serializable[] onlyNewItemsValueArray = (Serializable[]) Array.newInstance(
				valueArray.getClass().getComponentType(), valueArray.length
			);
			int onlyNewItemsValueArrayIndex = 0;
			for (Serializable valueItem : valueArray) {
				if (theCardinalityIndex.addRecord(valueItem, recordId)) {
					onlyNewItemsValueArray[onlyNewItemsValueArrayIndex++] = valueItem;
				}
			}
			if (onlyNewItemsValueArrayIndex > 0) {
				final Serializable[] delta = Arrays.copyOfRange(
					onlyNewItemsValueArray, 0, onlyNewItemsValueArrayIndex
				);
				delegateAddDeltaFilterAttribute(
					referenceSchema, attributeSchema, allowedLocales, locale, delta, recordId
				);
			}
		} else {
			// for non-array values we need to call super method only if cardinality was zero
			if (theCardinalityIndex.addRecord(value, recordId)) {
				delegateInsertFilterAttribute(
					referenceSchema, attributeSchema, allowedLocales, locale, value, recordId
				);
			}
		}
	}

	@Override
	public void removeFilterAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		assertPartitioningIndex(referenceSchema, attributeSchema);
		// first retrieve the cardinality index for given attribute
		final AttributeIndexKey attributeKey = createAttributeKey(
			referenceSchema, attributeSchema, allowedLocales, locale, value
		);
		final AttributeCardinalityIndex theCardinalityIndex = this.cardinalityIndexes.get(attributeKey);

		Assert.isPremiseValid(
			theCardinalityIndex != null,
			() -> "Cardinality index for attribute " + attributeSchema.getName() + " not found."
		);
		if (value instanceof Serializable[] valueArray) {
			// for array values we need to remove only items which cardinality reaches zero
			final Serializable[] onlyRemovedItemsValueArray = (Serializable[]) Array.newInstance(
				valueArray.getClass().getComponentType(), valueArray.length
			);
			int onlyRemovedItemsValueArrayIndex = 0;
			for (Serializable valueItem : valueArray) {
				if (theCardinalityIndex.removeRecord(valueItem, recordId)) {
					onlyRemovedItemsValueArray[onlyRemovedItemsValueArrayIndex++] = valueItem;
				}
			}
			if (onlyRemovedItemsValueArrayIndex > 0) {
				final Serializable[] delta = Arrays.copyOfRange(
					onlyRemovedItemsValueArray, 0, onlyRemovedItemsValueArrayIndex
				);
				delegateRemoveDeltaFilterAttribute(
					referenceSchema, attributeSchema, allowedLocales, locale, delta, recordId
				);
			}
		} else {
			// for non-array values we need to call super method only if cardinality reaches zero
			if (theCardinalityIndex.removeRecord(value, recordId)) {
				delegateRemoveFilterAttribute(
					referenceSchema, attributeSchema, allowedLocales, locale, value, recordId
				);
			}
		}

		if (theCardinalityIndex.isEmpty()) {
			final AttributeCardinalityIndex removedIndex = this.cardinalityIndexes.remove(attributeKey);
			if (removedIndex == null) {
				throw new GenericEvitaInternalError("AttributeCardinalityIndex for key " + attributeKey + " doesn't exists!");
			} else {
				ofNullable(getTransactionalLayerMaintainer())
					.ifPresent(removedIndex::removeLayer);
			}
		}
	}

	@Override
	public void addDeltaFilterAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable[] value,
		int recordId
	) {
		assertPartitioningIndex(referenceSchema, attributeSchema);
		delegateAddDeltaFilterAttribute(referenceSchema, attributeSchema, allowedLocales, locale, value, recordId);
	}

	@Override
	public void removeDeltaFilterAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable[] value,
		int recordId
	) {
		assertPartitioningIndex(referenceSchema, attributeSchema);
		delegateRemoveDeltaFilterAttribute(
			referenceSchema, attributeSchema, allowedLocales, locale, value, recordId
		);
	}

	// sort index of group entity index is not maintained, because the entity might reference multiple
	// entities of same group and the sort index couldn't handle multiple values

	@Override
	public void insertSortAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		// no-op: the sort index of group entity index is not maintained, because the entity might reference
		// multiple entities in the same group and the sort index couldn't handle multiple values
	}

	@Override
	public void removeSortAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		// no-op: the sort index of group entity index is not maintained
	}

	@Override
	public void insertSortAttributeCompound(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchemaContract,
		@Nonnull Function<String, Class<?>> attributeTypeProvider,
		@Nullable Locale locale,
		@Nonnull Serializable[] value,
		int recordId
	) {
		// no-op: the sort index of group entity index is not maintained
	}

	@Override
	public void removeSortAttributeCompound(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchemaContract,
		@Nullable Locale locale,
		@Nonnull Serializable[] value,
		int recordId
	) {
		// no-op: the sort index of group entity index is not maintained
	}

	@Override
	public void insertUniqueAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nonnull Scope scope,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		// no-op: unique attributes are not maintained in group entity index because multiple
		// entities can reference the same group, making uniqueness checks inappropriate
	}

	@Override
	public void removeUniqueAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nonnull Scope scope,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		// no-op: unique attributes are not maintained in group entity index
	}

	/**
	 * Returns the {@link HistogramIndex} for the given histogram name, or `null` if none exists.
	 *
	 * @param histogramName the name of the histogram definition
	 * @return the histogram index, or `null`
	 */
	@Nullable
	@Override
	public HistogramIndex getHistogramIndex(@Nonnull String histogramName) {
		return this.histogramIndexes.get(histogramName);
	}

	/**
	 * Returns the {@link FilterIndex} backing the given histogram name and locale variant in this group index.
	 * Used by {@link io.evitadb.core.query.extraResult.translator.reference.producer.ReferenceHistogramAccumulator}
	 * to obtain the source filter index for histogram bucket computation. Returns {@code null} when no histogram
	 * data has been indexed for this (histogramName, locale) combination — the accumulator treats a `null` result
	 * as "no data for this group" and skips the computation.
	 *
	 * @param histogramName the name of the histogram definition as registered on the reference schema
	 * @param locale        the locale for localized histograms, or {@code null} for non-localized attributes
	 * @return the filter index, or {@code null} if none exists for this combination
	 */
	@Nullable
	@Override
	public FilterIndex getHistogramFilterIndex(@Nonnull String histogramName, @Nullable Locale locale) {
		final HistogramIndex histogramIndex = this.histogramIndexes.get(histogramName);
		return histogramIndex == null ? null : histogramIndex.getFilterIndex(locale);
	}

	@Override
	public void insertHistogramValue(
		@Nonnull String histogramName,
		@Nullable Locale locale,
		@Nonnull Number value,
		int ownerPK,
		@Nonnull Class<? extends Serializable> valueType
	) {
		final String referenceName = getRepresentativeReferenceKey().referenceName();
		final HistogramIndex histogramIndex = this.histogramIndexes.computeIfAbsent(
			histogramName,
			k -> locale != null
				? new LocalizedHistogramIndex(histogramName, referenceName, valueType)
				: new SimpleHistogramIndex(histogramName, referenceName, valueType)
		);
		histogramIndex.insertValue(locale, value, ownerPK);
		this.dirty.setToTrue();
	}

	@Override
	public void removeHistogramValue(
		@Nonnull String histogramName,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int ownerPK
	) {
		final HistogramIndex histogramIndex = this.histogramIndexes.get(histogramName);
		Assert.isPremiseValid(
			histogramIndex != null,
			() -> "Histogram index for histogram " + histogramName + " not found."
		);
		histogramIndex.removeValue(locale, value, ownerPK);
		this.dirty.setToTrue();
		// if the histogram index is now empty, remove it from the map and clean up transactional layers
		if (histogramIndex.isEmpty()) {
			final HistogramIndex removed = this.histogramIndexes.remove(histogramName);
			if (removed != null) {
				ofNullable(getTransactionalLayerMaintainer()).ifPresent(removed::removeLayer);
			}
		}
	}

	@Nonnull
	@Override
	public ReducedGroupEntityIndex createCopyWithMergedTransactionalMemory(
		@Nullable Void layer,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		// we can safely throw away dirty flag now
		final Boolean wasDirty = transactionalLayer.getStateCopyWithCommittedChanges(this.dirty);
		// we can safely throw away the cardinality dirty flag too
		transactionalLayer.getStateCopyWithCommittedChanges(this.cardinalityDirty);

		return new ReducedGroupEntityIndex(
			this.primaryKey, this.indexKey, this.version + (wasDirty ? 1 : 0),
			transactionalLayer.getStateCopyWithCommittedChanges(this.entityIds),
			transactionalLayer.getStateCopyWithCommittedChanges(this.entityIdsByLanguage),
			transactionalLayer.getStateCopyWithCommittedChanges(this.attributeIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(getPriceIndex()),
			transactionalLayer.getStateCopyWithCommittedChanges(this.hierarchyIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.facetIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.pkCardinalities),
			transactionalLayer.getStateCopyWithCommittedChanges(this.referencedPrimaryKeysIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.cardinalityIndexes),
			transactionalLayer.getStateCopyWithCommittedChanges(this.histogramIndexes)
		);
	}

	@Override
	public String toString() {
		return "ReducedGroupEntityIndex (" + StringUtils.uncapitalize(getIndexKey().toString()) +
			", histograms=" + this.histogramIndexes.size() + ")";
	}

}
