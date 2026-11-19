/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.ReferenceAttributeIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.component.HistogramIndexMapComponent;
import io.evitadb.index.component.loader.AttributeIndexLoader;
import io.evitadb.index.component.loader.FacetIndexLoader;
import io.evitadb.index.component.loader.HierarchyIndexLoader;
import io.evitadb.index.component.loader.HistogramIndexMapLoader;
import io.evitadb.index.component.loader.IndexReloadPlan;
import io.evitadb.index.component.loader.LoadContext;
import io.evitadb.index.component.loader.LoadedComponentBundle;
import io.evitadb.index.facet.FacetIndex;
import io.evitadb.index.hierarchy.HierarchyIndex;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.price.PriceIndexContract;
import io.evitadb.index.price.VoidPriceIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.StringUtils;
import io.evitadb.utils.VMLayout;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import static io.evitadb.core.transaction.Transaction.getTransactionalLayerMaintainer;

/**
 * The histogram-only store of one referenced group: the {@link EntityIndexType#REFERENCED_GROUP_HISTOGRAM} index of
 * one reference name, scope and group entity primary key. It holds the bucketed reference histograms of the group as
 * a whole - a {@link HistogramIndex} per histogram name, counting owner entity primary keys once per (value, owner) -
 * and nothing else.
 *
 * **It holds no rows.** Its entity primary key and language bitmaps stay empty for its whole life, and so do its
 * attribute, facet and hierarchy indexes and its price index ({@link VoidPriceIndex}). The row mutators of
 * {@link EntityIndex} refuse, {@link #isEmpty()} fails when a base structure has nevertheless been filled, and its
 * reload plan refuses a persisted membership or a manifest naming anything but histograms. A store is therefore
 * empty exactly when its histogram map is.
 *
 * **It is never advertised.** The {@link EntityIndexType#REFERENCED_GROUP_ENTITY_TYPE} index tracks the group itself
 * and lists only the reduced group indexes; a store is addressed solely by its key, which {@link #keyOf} builds.
 *
 * **Its histogram writes set the inherited {@link #dirty} flag.** That flag also gates the sibling entity-id part of
 * the flush, and since a store's membership is empty its manifest is never considered "already persisted" (the
 * `previouslyPersisted` proxy of {@link EntityIndex} reads a non-empty membership). Reusing the base flag is what
 * makes a store created and emptied before its first flush still write its manifest - with an own flag it would
 * write nothing while the collection header already lists its primary key, and the next load would find no manifest
 * to read. The price of it is a no-op removal of the never-written entity-id part on every flush of a dirty store.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class GroupHistogramEntityIndex extends EntityIndex implements
	VoidTransactionMemoryProducer<GroupHistogramEntityIndex>,
	HistogramCapableEntityIndex
{
	/**
	 * The read-side reload plan of a store, cached per JVM. The attribute, hierarchy and facet loaders are present
	 * because the base registers those three components on every entity index; the finalizer refuses whatever they
	 * load unless it is empty, see {@link #assertPersistedStoreHoldsHistogramsOnly(LoadContext)}.
	 */
	private static final IndexReloadPlan GROUP_HISTOGRAM_RELOAD_PLAN = IndexReloadPlan.builder()
		.add(new AttributeIndexLoader())
		.add(new HierarchyIndexLoader())
		.add(new FacetIndexLoader())
		.add(new HistogramIndexMapLoader())
		.build((bundles, context) -> {
			// validated before the store is constructed: a constructor fed the persisted membership would carry it on
			// silently, and one fed empty bitmaps instead would erase it before anything could notice
			assertPersistedStoreHoldsHistogramsOnly(context);
			final LoadedComponentBundle.AttributeIndexes attributes =
				(LoadedComponentBundle.AttributeIndexes) bundles.get(LoadedComponentBundle.AttributeIndexes.class);
			final LoadedComponentBundle.Hierarchy hierarchy =
				(LoadedComponentBundle.Hierarchy) bundles.get(LoadedComponentBundle.Hierarchy.class);
			final LoadedComponentBundle.Facet facet =
				(LoadedComponentBundle.Facet) bundles.get(LoadedComponentBundle.Facet.class);
			final LoadedComponentBundle.Histograms histograms =
				(LoadedComponentBundle.Histograms) bundles.get(LoadedComponentBundle.Histograms.class);
			final EntityIndexStoragePart manifest = context.entityIndexStoragePart();
			return new GroupHistogramEntityIndex(
				manifest.getPrimaryKey(),
				manifest.getEntityIndexKey(),
				context.version(),
				// the validated (empty) bitmaps of the context, handed through rather than replaced
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
				hierarchy.hierarchyIndex(),
				facet.facetIndex(),
				histograms.histogramIndexes(),
				// loaded from disk - the counters start over, which is what "since catalog load" means, and are not
				// opened at all when the server does not track usage statistics
				context.createActivity()
			);
		});

	/**
	 * No prices are maintained in this index.
	 */
	@Delegate(types = PriceIndexContract.class)
	private final PriceIndexContract priceIndex = VoidPriceIndex.INSTANCE;
	/**
	 * Per-histogram-name index storing the bucketed histogram data (filter indexes + cardinality tracking) of the whole
	 * group for all locale variants of each histogram definition. An emptied histogram leaves the map, so the map is
	 * empty exactly when the store holds nothing.
	 */
	@Nonnull private final TransactionalMap<String, HistogramIndex> histogramIndexes;
	/**
	 * The {@link HistogramIndexMapComponent} registered over {@link #histogramIndexes}, held here **only** so
	 * {@link #getHeapSizeInBytes()} can ask it what it weighs - see the identically-named field of
	 * {@link ReferencedTypeEntityIndex} for why this one component is priced through itself.
	 */
	@Nonnull private final HistogramIndexMapComponent histogramComponent;

	/**
	 * Builds the key of the store of one group. It is the only way a store key is built, so that every writer and
	 * reader addresses the same store: the discriminator carries the reference name and the group entity primary key,
	 * and never a representative attribute tuple.
	 *
	 * @param referenceName the name of the grouped reference whose histograms the store holds
	 * @param groupPk       the primary key of the group entity
	 * @param scope         the scope of the store
	 * @return the key of the store
	 */
	@Nonnull
	public static EntityIndexKey keyOf(@Nonnull String referenceName, int groupPk, @Nonnull Scope scope) {
		return new EntityIndexKey(
			EntityIndexType.REFERENCED_GROUP_HISTOGRAM,
			scope,
			new RepresentativeReferenceKey(new ReferenceKey(referenceName, groupPk))
		);
	}

	/**
	 * Returns the read-side reload plan of the store: the three loaders of the components every entity index
	 * registers, and the histogram map loader. Its finalizer refuses a persisted membership, a persisted language
	 * bitmap and a manifest naming an attribute, price, facet or hierarchy sub-index before the store is constructed.
	 * The loaders run before it, so a manifest naming a `UNIQUE`, `FILTER`, `SORT` or `CHAIN` attribute sub-index, a
	 * facet or a hierarchy whose part is missing fails earlier, inside the loader that reads that part. A key no loader
	 * of this plan reads - an attribute `CARDINALITY` key, which `AttributeIndexLoader` skips and for which the plan
	 * has no loader, or a price key - reaches the finalizer, whose own refusal names the store.
	 *
	 * @return the immutable reload plan of this class
	 */
	@Nonnull
	public static IndexReloadPlan reloadPlan() {
		return GROUP_HISTOGRAM_RELOAD_PLAN;
	}

	/**
	 * Creates a fresh, empty store.
	 *
	 * @param primaryKey              the primary key of this index
	 * @param entityType              the type of the owner entities
	 * @param entityIndexKey          the key of the store, as {@link #keyOf} builds it
	 * @param usageStatisticsTracking whether to allocate an {@link IndexActivity} holder for it
	 */
	public GroupHistogramEntityIndex(
		int primaryKey,
		@Nonnull String entityType,
		@Nonnull EntityIndexKey entityIndexKey,
		boolean usageStatisticsTracking
	) {
		super(primaryKey, entityType, entityIndexKey, usageStatisticsTracking);
		assertStoreKey(entityIndexKey);
		this.histogramIndexes = new TransactionalMap<>(
			CollectionUtils.createHashMap(4), HistogramIndex.class, Function.identity()
		);
		this.histogramComponent = new HistogramIndexMapComponent(this.histogramIndexes, this.indexKey);
		addComponent(this.histogramComponent);
		// fresh empty index - every component contributes an empty manifest, so the baseline captured here is the
		// immutable empty set, preventing spurious manifest emits
		captureOriginalsFromComponents();
	}

	/**
	 * Reconstructs a store from persisted state, or builds the committed copy of one. Every structure is handed in
	 * and kept as it is - nothing is replaced by an empty one, so a base structure that is not empty survives to be
	 * refused by {@link #isEmpty()} rather than being erased here.
	 *
	 * @param primaryKey          the primary key of this index
	 * @param entityIndexKey      the key of the store
	 * @param version             the version of this index
	 * @param entityIds           the entity primary keys of this index, always empty for a valid store
	 * @param entityIdsByLanguage the entity primary keys by locale, always empty for a valid store
	 * @param attributeIndex      the attribute index, always empty for a valid store
	 * @param hierarchyIndex      the hierarchy index, always empty for a valid store
	 * @param facetIndex          the facet index, always empty for a valid store
	 * @param histogramIndexes    the histogram indexes by histogram name
	 * @param activity            the activity holder to keep counting into - the copied index's own instance on the
	 *                            commit-time merge copy, a fresh one when loading from disk; see {@link IndexActivity}
	 */
	public GroupHistogramEntityIndex(
		int primaryKey,
		@Nonnull EntityIndexKey entityIndexKey,
		int version,
		@Nonnull Bitmap entityIds,
		@Nonnull Map<Locale, TransactionalBitmap> entityIdsByLanguage,
		@Nonnull ReferenceAttributeIndex attributeIndex,
		@Nonnull HierarchyIndex hierarchyIndex,
		@Nonnull FacetIndex facetIndex,
		@Nonnull Map<String, HistogramIndex> histogramIndexes,
		@Nullable IndexActivity activity
	) {
		super(
			primaryKey, entityIndexKey, version,
			entityIds, entityIdsByLanguage,
			attributeIndex, hierarchyIndex, facetIndex, activity
		);
		assertStoreKey(entityIndexKey);
		this.histogramIndexes = new TransactionalMap<>(
			histogramIndexes, HistogramIndex.class, Function.identity()
		);
		this.histogramComponent = new HistogramIndexMapComponent(this.histogramIndexes, this.indexKey);
		addComponent(this.histogramComponent);
		// re-capture the change-detection baseline now that the histogram map is populated
		captureOriginalsFromComponents();
	}

	/**
	 * Refuses a persisted store that holds anything but histograms. Runs in the reload finalizer, before the store is
	 * constructed, against the membership the context resolved from the sibling entity-id part and against the
	 * manifest. Nothing writes either into a store, so finding one means the persisted store is not what this engine
	 * wrote, and loading it would either carry rows into a structure that never reads them or erase them unseen.
	 *
	 * @param context the reload context of the store
	 * @throws GenericEvitaInternalError naming the primary key of the store, when anything but histograms is persisted
	 */
	private static void assertPersistedStoreHoldsHistogramsOnly(@Nonnull LoadContext context) {
		final EntityIndexStoragePart manifest = context.entityIndexStoragePart();
		final int primaryKey = manifest.getPrimaryKey();
		Assert.isPremiseValid(
			context.entityIds().isEmpty(),
			() -> "The group histogram store with primary key " + primaryKey + " (`" + manifest.getEntityIndexKey() +
				"`) was persisted with " + context.entityIds().size() + " entity primary key(s), but a store never " +
				"holds any - refusing to load it!"
		);
		Assert.isPremiseValid(
			context.entityIdsByLanguage().isEmpty(),
			() -> "The group histogram store with primary key " + primaryKey + " (`" + manifest.getEntityIndexKey() +
				"`) was persisted with entity primary keys of locale(s) " + context.entityIdsByLanguage().keySet() +
				", but a store never holds any - refusing to load it!"
		);
		Assert.isPremiseValid(
			manifest.getAttributeIndexes().isEmpty() && manifest.getPriceIndexes().isEmpty() &&
				manifest.getFacetIndexes().isEmpty() && !manifest.isHierarchyIndex(),
			() -> "The group histogram store with primary key " + primaryKey + " (`" + manifest.getEntityIndexKey() +
				"`) was persisted with a manifest naming " + manifest.getAttributeIndexes().size() +
				" attribute, " + manifest.getPriceIndexes().size() + " price and " +
				manifest.getFacetIndexes().size() + " facet sub-index(es)" +
				(manifest.isHierarchyIndex() ? " and a hierarchy" : "") +
				", but a store holds histograms only - refusing to load it!"
		);
	}

	/**
	 * Verifies that the key handed to a constructor is the key of a store.
	 *
	 * @param entityIndexKey the key to verify
	 */
	private static void assertStoreKey(@Nonnull EntityIndexKey entityIndexKey) {
		Assert.isPremiseValid(
			entityIndexKey.type() == EntityIndexType.REFERENCED_GROUP_HISTOGRAM,
			() -> "GroupHistogramEntityIndex only supports the " + EntityIndexType.REFERENCED_GROUP_HISTOGRAM +
				" type, got: " + entityIndexKey.type()
		);
	}

	/**
	 * {@inheritDoc}
	 *
	 * A store is empty exactly when its histogram map is. The base structures are not consulted for the answer, they
	 * are asserted empty: a store whose rows, languages, attributes, facets or hierarchy are not empty has been
	 * written by something that must never reach it, and answering either way would hide that.
	 *
	 * @throws GenericEvitaInternalError when a base structure of the store is not empty
	 */
	@Override
	public boolean isEmpty() {
		Assert.isPremiseValid(
			super.isEmpty(),
			() -> "The group histogram store with primary key " + this.primaryKey + " (`" + this.indexKey +
				"`) holds entity primary keys, languages, attributes, facets or a hierarchy, but a store holds " +
				"histograms only!"
		);
		return this.histogramIndexes.isEmpty();
	}

	/**
	 * A store holds no entity primary keys.
	 *
	 * @throws GenericEvitaInternalError always
	 */
	@Override
	public boolean insertPrimaryKeyIfMissing(int entityPrimaryKey) {
		throw rowWriteRefused("insert the entity primary key " + entityPrimaryKey);
	}

	/**
	 * A store holds no entity primary keys.
	 *
	 * @throws GenericEvitaInternalError always
	 */
	@Override
	public boolean removePrimaryKey(int entityPrimaryKey) {
		throw rowWriteRefused("remove the entity primary key " + entityPrimaryKey);
	}

	/**
	 * A store holds no languages.
	 *
	 * @throws GenericEvitaInternalError always
	 */
	@Override
	public boolean upsertLanguage(@Nonnull Locale locale, int entityPrimaryKey, @Nonnull EntitySchemaContract schema) {
		throw rowWriteRefused("add the locale `" + locale + "` of the entity primary key " + entityPrimaryKey);
	}

	/**
	 * A store holds no languages.
	 *
	 * @throws GenericEvitaInternalError always
	 */
	@Override
	public boolean removeLanguage(@Nonnull Locale locale, int recordId) {
		throw rowWriteRefused("remove the locale `" + locale + "` of the entity primary key " + recordId);
	}

	@Nonnull
	@Override
	public <S extends PriceIndexContract> S getPriceIndex() {
		//noinspection unchecked
		return (S) this.priceIndex;
	}

	@Nonnull
	@Override
	protected Class<? extends StoragePart> getPriceRootStoragePartType() {
		// a store carries a VoidPriceIndex, so its persisted price baseline is always empty and no price-root removal
		// can ever be emitted for it - reaching this point means that invariant broke, and answering with a plausible
		// container type would target the wrong record on removal
		throw new GenericEvitaInternalError(
			"A group histogram store never persists a price sub-index, so its price root storage part type must " +
				"never be requested!"
		);
	}

	@Nullable
	@Override
	public HistogramIndex getHistogramIndex(@Nonnull String histogramName) {
		return this.histogramIndexes.get(histogramName);
	}

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
		@Nonnull Serializable value,
		int ownerPK,
		@Nonnull Class<? extends Serializable> valueType,
		int indexedDecimalPlaces
	) {
		// the inherited dirty flag, not an own one - see the class javadoc for why a store created and emptied before
		// its first flush depends on it
		HistogramIndexOperations.insertHistogramValue(
			this.histogramIndexes, this.dirty, Objects.requireNonNull(this.indexKey.referenceName()),
			histogramName, locale, value, ownerPK, valueType, indexedDecimalPlaces
		);
	}

	@Override
	public void removeHistogramValue(
		@Nonnull String histogramName,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int ownerPK,
		int indexedDecimalPlaces
	) {
		HistogramIndexOperations.removeHistogramValue(
			this.histogramIndexes, this.dirty, getTransactionalLayerMaintainer(),
			histogramName, locale, value, ownerPK, indexedDecimalPlaces
		);
	}

	@Nonnull
	@Override
	public GroupHistogramEntityIndex createCopyWithMergedTransactionalMemory(
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		// we can safely throw away dirty flag now
		final Boolean wasDirty = transactionalLayer.getStateCopyWithCommittedChanges(this.dirty);
		// every structure is carried over as committed, the empty base ones included - a base structure that is not
		// empty must survive the copy to be refused by isEmpty(), not be dropped here
		return new GroupHistogramEntityIndex(
			this.primaryKey, this.indexKey, this.version + (wasDirty ? 1 : 0),
			transactionalLayer.getStateCopyWithCommittedChanges(this.entityIds),
			transactionalLayer.getStateCopyWithCommittedChanges(this.entityIdsByLanguage),
			(ReferenceAttributeIndex) transactionalLayer.getStateCopyWithCommittedChanges(this.attributeIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.hierarchyIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.facetIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.histogramIndexes),
			// the very same holder, not a copy: this is one logical index carried into the next catalog version
			getActivity()
		);
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		// the base loop covers the attribute, hierarchy, facet and histogram components
		super.removeTransactionalMemoryOfReferencedProducers(transactionalLayer);
	}

	/**
	 * {@inheritDoc}
	 *
	 * {@link #priceIndex} is {@link VoidPriceIndex#INSTANCE} - one instance for the whole JVM - so only its slot is
	 * charged. The histogram map is keyed by the schema's histogram names and charges slots alone. The store registers
	 * no component but the histogram one, which prices itself.
	 */
	@Override
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		// the priceIndex / histogramIndexes / histogramComponent slots
		return getBaseHeapSizeInBytes(3L * layout.referenceSize())
			+ this.histogramIndexes.getHeapSizeInBytes(
				histogramName -> 0L, HistogramIndex::getHeapSizeInBytes
			)
			// alongside its map and the index key the component holds the on-disk leaf-page baseline of the last flush,
			// which no field of this index points at - see `ReferencedTypeEntityIndex` for the same charge
			+ this.histogramComponent.getHeapSizeInBytes();
	}

	@Override
	public String toString() {
		return "GroupHistogramEntityIndex (" + StringUtils.uncapitalize(getIndexKey().toString()) +
			", histograms=" + this.histogramIndexes.size() + ")";
	}

	/**
	 * Builds the exception a refused row write throws.
	 *
	 * @param operation what the caller tried to do, for the message
	 * @return the exception to throw
	 */
	@Nonnull
	private GenericEvitaInternalError rowWriteRefused(@Nonnull String operation) {
		return new GenericEvitaInternalError(
			"Cannot " + operation + " in the group histogram store with primary key " + this.primaryKey + " (`" +
				this.indexKey + "`): a store holds histograms only!"
		);
	}

}
