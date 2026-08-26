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

import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.dataType.Scope;
import io.evitadb.index.attribute.AttributeIndex;
import io.evitadb.index.attribute.ReferenceAttributeIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.component.loader.IndexReloadPlan;
import io.evitadb.index.component.loader.LoadedComponentBundle;
import io.evitadb.index.facet.FacetIndex;
import io.evitadb.index.hierarchy.HierarchyIndex;
import io.evitadb.index.price.PriceRefIndex;
import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Reduced entity index is a "helper" index that maintains primarily bitmaps of primary keys that are connected to
 * a limited scope view of the data.
 *
 * **Sharing with {@link GlobalEntityIndex} is limited to prices.** A reduced index's price sub-index
 * ({@link io.evitadb.index.price.PriceListAndCurrencyPriceRefIndex}) refers to the immutable
 * {@link io.evitadb.index.price.model.priceRecord.PriceRecordContract} instances owned by the global super index and
 * persists ids only, re-attaching them on load. Nothing else is shared: every reduced index owns its own
 * {@link io.evitadb.index.attribute.AttributeIndex}, hence its own value trees, so the attribute values fanned out
 * into it (every filterable and sortable entity-level attribute, for a reference marked
 * `FOR_FILTERING_AND_PARTITIONING`) are replicated per index, on heap and on disk alike. On a catalog with many
 * distinct referenced entities that replication is the single largest known value duplication in the engine —
 * deduplicating it is its own line of work, whose prerequisite is the stable value id introduced on the global shared
 * value tree (see `documentation/adr/2026-08-24-fulltext-search-lucene-vs-inhouse`).
 *
 * Reduced indexes are used for handling queries that target {@link ReferenceContract}
 * of the entities. In such case we may prefer using data from reduced entity index because it may substantially limit
 * the amount of operations to answer the query.
 *
 * This class handles {@link EntityIndexType#REFERENCED_ENTITY} indexes for individual referenced entities.
 * For group-level indexes with cardinality tracking, see {@link ReducedGroupEntityIndex}.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ReducedEntityIndex extends AbstractReducedEntityIndex {

	/**
	 * Creates a new empty reduced entity index.
	 *
	 * @param primaryKey     the primary key of this index
	 * @param entityType     the type of entity being indexed
	 * @param entityIndexKey the key identifying this index
	 */
	public ReducedEntityIndex(
		int primaryKey,
		@Nonnull String entityType,
		@Nonnull EntityIndexKey entityIndexKey
	) {
		this(primaryKey, entityType, entityIndexKey, ServerOptions.DEFAULT_USAGE_STATISTICS_TRACKING);
	}

	/**
	 * Creates a fresh index, stating whether it counts its own usage.
	 *
	 * @param primaryKey              the primary key of this index
	 * @param entityType              the type of entity being indexed
	 * @param entityIndexKey          the key identifying this index
	 * @param usageStatisticsTracking whether to allocate an {@link io.evitadb.index.IndexActivity} holder for it
	 */
	public ReducedEntityIndex(
		int primaryKey,
		@Nonnull String entityType,
		@Nonnull EntityIndexKey entityIndexKey,
		boolean usageStatisticsTracking
	) {
		super(primaryKey, entityType, entityIndexKey, usageStatisticsTracking);
		Assert.isPremiseValid(
			entityIndexKey.type() == EntityIndexType.REFERENCED_ENTITY,
			() -> "ReducedEntityIndex only supports REFERENCED_ENTITY type, got: " + entityIndexKey.type()
		);
		// fresh empty index — every component contributes an empty manifest, so the baseline
		// captured here is the immutable empty set, preventing spurious manifest emits
		captureOriginalsFromComponents();
	}

	/**
	 * Creates a reduced entity index from persisted data.
	 *
	 * @param primaryKey          the primary key of this index
	 * @param entityIndexKey      the key identifying this index
	 * @param version             the version of this index
	 * @param entityIds           bitmap of entity primary keys in this index
	 * @param entityIdsByLanguage entity primary keys grouped by locale
	 * @param attributeIndex      the attribute index
	 * @param priceIndex          the price reference index
	 * @param hierarchyIndex      the hierarchy index
	 * @param facetIndex          the facet index
	 * @param activity            the activity holder to keep counting into — the copied index's own instance on the
	 *                            commit-time merge copy, a fresh one when loading from disk; see {@link IndexActivity}
	 */
	public ReducedEntityIndex(
		int primaryKey,
		@Nonnull EntityIndexKey entityIndexKey,
		int version,
		@Nonnull Bitmap entityIds,
		@Nonnull Map<Locale, TransactionalBitmap> entityIdsByLanguage,
		@Nonnull ReferenceAttributeIndex attributeIndex,
		@Nonnull PriceRefIndex priceIndex,
		@Nonnull HierarchyIndex hierarchyIndex,
		@Nonnull FacetIndex facetIndex,
		@Nullable IndexActivity activity
	) {
		super(
			primaryKey, entityIndexKey, version,
			entityIds, entityIdsByLanguage,
			attributeIndex, priceIndex, hierarchyIndex, facetIndex, activity
		);
		Assert.isPremiseValid(
			entityIndexKey.type() == EntityIndexType.REFERENCED_ENTITY,
			() -> "ReducedEntityIndex only supports REFERENCED_ENTITY type, got: " + entityIndexKey.type()
		);
		// re-capture the change-detection baseline from the components now that the price ref
		// index is registered, so the baseline includes every persisted sub-index
		captureOriginalsFromComponents();
	}

	/**
	 * Returns the read-side reload plan for `ReducedEntityIndex`. Identical to the base
	 * reduced-index plan — attribute / price-ref / hierarchy / facet — because this subclass
	 * owns no additional sub-indexes beyond the shared four. The plan is cached per JVM.
	 *
	 * @return the immutable reload plan for this subclass
	 */
	@Nonnull
	public static IndexReloadPlan reloadPlan() {
		return REDUCED_RELOAD_PLAN;
	}

	private static final IndexReloadPlan REDUCED_RELOAD_PLAN = appendCommon(IndexReloadPlan.builder())
		.build((bundles, context) -> {
			final LoadedComponentBundle.AttributeIndexes attributes =
				(LoadedComponentBundle.AttributeIndexes) bundles.get(LoadedComponentBundle.AttributeIndexes.class);
			final LoadedComponentBundle.PriceRef prices =
				(LoadedComponentBundle.PriceRef) bundles.get(LoadedComponentBundle.PriceRef.class);
			final LoadedComponentBundle.Hierarchy hierarchy =
				(LoadedComponentBundle.Hierarchy) bundles.get(LoadedComponentBundle.Hierarchy.class);
			final LoadedComponentBundle.Facet facet =
				(LoadedComponentBundle.Facet) bundles.get(LoadedComponentBundle.Facet.class);
			final io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart manifest =
				context.entityIndexStoragePart();
			final Scope scope = manifest.getEntityIndexKey().scope();
			return new ReducedEntityIndex(
				manifest.getPrimaryKey(),
				manifest.getEntityIndexKey(),
				context.version(),
				context.entityIds(),
				context.entityIdsByLanguage(),
				new ReferenceAttributeIndex(
					context.entitySchema().getName(),
					context.referenceKey(),
					attributes.uniqueIndexes(),
					attributes.filterIndexes(),
					attributes.uniqueViewIndexes(),
					attributes.sortIndexes(),
					attributes.chainIndexes(),
					attributes.sharedValueIndexes(),
					attributes.sharedRangeIndexes()
				),
				new PriceRefIndex(scope, prices.priceIndexes()),
				hierarchy.hierarchyIndex(),
				facet.facetIndex(),
				// loaded from disk — the counters start over, which is what "since catalog load" means, and are
				// not opened at all when the server does not track usage statistics
				context.createActivity()
			);
		});

	@Nonnull
	@Override
	public ReducedEntityIndex createCopyWithMergedTransactionalMemory(
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		// we can safely throw away dirty flag now
		final Boolean wasDirty = transactionalLayer.getStateCopyWithCommittedChanges(this.dirty);
		// safe: AttributeIndex#createCopy preserves the subclass identity established by EntityIndex#isReferenceScoped
		return new ReducedEntityIndex(
			this.primaryKey, this.indexKey, this.version + (wasDirty ? 1 : 0),
			transactionalLayer.getStateCopyWithCommittedChanges(this.entityIds),
			transactionalLayer.getStateCopyWithCommittedChanges(this.entityIdsByLanguage),
			(ReferenceAttributeIndex) transactionalLayer.getStateCopyWithCommittedChanges(this.attributeIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(getPriceIndex()),
			transactionalLayer.getStateCopyWithCommittedChanges(this.hierarchyIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.facetIndex),
			// the very same holder, not a copy: this is one logical index carried into the next catalog version
			getActivity()
		);
	}

	@Override
	public void insertFilterAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId,
		boolean foldedUnique
	) {
		assertPartitioningIndex(referenceSchema, attributeSchema);
		delegateInsertFilterAttribute(referenceSchema, attributeSchema, allowedLocales, locale, value, recordId, foldedUnique);
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
		delegateRemoveFilterAttribute(referenceSchema, attributeSchema, allowedLocales, locale, value, recordId);
	}

	@Override
	public void addDeltaFilterAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable[] value,
		int recordId,
		boolean foldedUnique
	) {
		assertPartitioningIndex(referenceSchema, attributeSchema);
		delegateAddDeltaFilterAttribute(referenceSchema, attributeSchema, allowedLocales, locale, value, recordId, foldedUnique);
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
		delegateRemoveDeltaFilterAttribute(referenceSchema, attributeSchema, allowedLocales, locale, value, recordId);
	}

	@Override
	public void insertSortAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		assertPartitioningIndex(referenceSchema, attributeSchema);
		delegateInsertSortAttribute(referenceSchema, attributeSchema, allowedLocales, locale, value, recordId);
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
		assertPartitioningIndex(referenceSchema, attributeSchema);
		delegateRemoveSortAttribute(referenceSchema, attributeSchema, allowedLocales, locale, value, recordId);
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
		assertPartitioningIndex(referenceSchema, compoundSchemaContract);
		delegateInsertSortAttributeCompound(
			entitySchema, referenceSchema, compoundSchemaContract, attributeTypeProvider, locale, value, recordId
		);
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
		assertPartitioningIndex(referenceSchema, compoundSchemaContract);
		delegateRemoveSortAttributeCompound(
			entitySchema, referenceSchema, compoundSchemaContract, locale, value, recordId
		);
	}

	@Override
	public AttributeIndex.UniquenessEnforcement insertUniqueAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nonnull Scope scope,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		assertPartitioningIndex(referenceSchema, attributeSchema);
		return delegateInsertUniqueAttribute(referenceSchema, attributeSchema, allowedLocales, scope, locale, value, recordId);
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
		assertPartitioningIndex(referenceSchema, attributeSchema);
		delegateRemoveUniqueAttribute(referenceSchema, attributeSchema, allowedLocales, scope, locale, value, recordId);
	}

	/**
	 * {@inheritDoc}
	 *
	 * A reduced index declares no state of its own beyond what {@link AbstractReducedEntityIndex} holds.
	 */
	@Override
	public long getHeapSizeInBytes() {
		return getReducedBaseHeapSizeInBytes(0L);
	}

	@Override
	public String toString() {
		return "ReducedEntityIndex (" + StringUtils.uncapitalize(getIndexKey().toString()) + ")";
	}

}
