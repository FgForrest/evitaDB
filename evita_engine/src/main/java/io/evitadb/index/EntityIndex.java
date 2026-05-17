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

import io.evitadb.api.requestResponse.data.Versioned;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.locale.LocaleFormula;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.index.attribute.AttributeIndex;
import io.evitadb.index.attribute.AttributeIndexContract;
import io.evitadb.index.attribute.AttributeIndexScopeSpecificContract;
import io.evitadb.index.attribute.UniqueIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.component.AttributeIndexComponent;
import io.evitadb.index.component.EntityIndexManifest;
import io.evitadb.index.component.IndexComponent;
import io.evitadb.index.facet.FacetIndex;
import io.evitadb.index.facet.FacetIndexContract;
import io.evitadb.index.hierarchy.HierarchyIndex;
import io.evitadb.index.hierarchy.HierarchyIndexContract;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.price.PriceIndexContract;
import io.evitadb.index.price.PriceListAndCurrencyPriceIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexStorageKey;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.StringUtils;
import lombok.Getter;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static io.evitadb.core.transaction.Transaction.removeTransactionalMemoryLayerIfExists;
import static io.evitadb.utils.Assert.isTrue;
import static io.evitadb.utils.CollectionUtils.createHashMap;
import static java.util.Optional.ofNullable;

/**
 * This class represents main data structure that keeps all information connected with entity data, that could be used
 * for searching, sorting or another computational task upon these data.
 *
 * There may be multiple {@link EntityIndex} instances with different slices of the original data. There will be always
 * single {@link GlobalEntityIndex} index that contains all the data, but also several thinner
 * {@link AbstractReducedEntityIndex reduced indexes} that would contain only part of these. We aim to choose the smallest index
 * possible that can still provide correct answer for the input query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public abstract class EntityIndex implements
	Index<EntityIndexKey>,
	PriceIndexContract,
	Versioned,
	IndexDataStructure
{
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();

	/**
	 * This part of index collects information about filterable/unique/sortable attributes of the entities. It provides
	 * data that are necessary for constructing {@link Formula} tree for the constraints
	 * related to the attributes.
	 */
	@Delegate(types = AttributeIndexContract.class, excludes = AttributeIndexScopeSpecificContract.class)
	protected final AttributeIndex attributeIndex;
	/**
	 * This is internal flag that tracks whether the index contents became dirty and needs to be persisted.
	 */
	protected final TransactionalBoolean dirty;
	/**
	 * IntegerBitmap contains all entity ids known to this index. This bitmap represents superset of all inner bitmaps.
	 */
	protected final TransactionalBitmap entityIds;
	/**
	 * Map contains entity ids by their supported language.
	 */
	protected final TransactionalMap<Locale, TransactionalBitmap> entityIdsByLanguage;
	/**
	 * Type of the index.
	 */
	@Getter protected final EntityIndexKey indexKey;
	/**
	 * This part of index collects information about facets in entities. It provides data that are necessary for
	 * constructing {@link Formula} tree for the constraints related to the facets.
	 */
	@Delegate(types = FacetIndexContract.class)
	protected final FacetIndex facetIndex;
	/**
	 * This part of index collection information about hierarchy placement of the entities. It provides data that are
	 * necessary for constructing {@link Formula} tree for the constraints related to the hierarchy.
	 */
	@Delegate(types = HierarchyIndexContract.class)
	protected final HierarchyIndex hierarchyIndex;
	/**
	 * Unique id that identifies this instance of {@link EntityIndex}.
	 */
	@Getter protected final int primaryKey;
	/**
	 * Version of the entity index that gets increased with each atomic change in the index (incremented by one when
	 * transaction is committed and anything in this index was changed).
	 */
	protected final int version;
	/**
	 * This field captures the original state of the hierarchy index when this index was created.
	 * This information is used along with {@link #dirty} flag to determine whether {@link EntityIndexStoragePart}
	 * should be persisted. Mutable so subclasses can refresh the baseline from the registered
	 * {@link IndexComponent} list once all components have been registered — see
	 * {@link #captureOriginalsFromComponents()}.
	 */
	protected boolean originalHierarchyIndexEmpty;
	/**
	 * This field captures the original state of the attribute index when this index was created.
	 * This information is used along with {@link #dirty} flag to determine whether {@link EntityIndexStoragePart}
	 * should be persisted. Mutable so subclasses with additional attribute sub-index types (e.g. CARDINALITY)
	 * can refresh the baseline from their registered {@link IndexComponent} list — see
	 * {@link #captureOriginalsFromComponents()}.
	 */
	protected Set<AttributeIndexStorageKey> originalAttributeIndexes;
	/**
	 * This field captures the original state of the price indexes when this index was created.
	 * This information is used along with {@link #dirty} flag to determine whether {@link EntityIndexStoragePart}
	 * should be persisted.
	 */
	protected Set<PriceIndexKey> originalPriceIndexes;
	/**
	 * This field captures the original state of the facet indexes when this index was created.
	 * This information is used along with {@link #dirty} flag to determine whether {@link EntityIndexStoragePart}
	 * should be persisted.
	 */
	protected Set<String> originalFacetIndexes;
	/**
	 * This field captures the original state of the histogram indexes when this index was created.
	 * Subclasses that maintain histograms (e.g. {@link io.evitadb.index.HistogramCapableEntityIndex}
	 * implementors) refresh this baseline via {@link #captureOriginalsFromComponents()} after they
	 * register a {@link io.evitadb.index.component.HistogramIndexMapComponent}.
	 */
	protected Set<HistogramIndexStorageKey> originalHistogramKeys;
	/**
	 * Ordered list of self-registering sub-systems that participate in commit-time flush and
	 * transactional-layer lifecycle. Populated by the base constructors with the three intrinsic
	 * components (attribute, hierarchy, facet) and extended by subclass constructors via
	 * {@link #addComponent(IndexComponent)} — order matters for deterministic flush sequencing
	 * across releases and is locked to today's order to minimize behavioural drift.
	 */
	private final List<IndexComponent> components = new ArrayList<>(8);

	protected EntityIndex(
		int primaryKey,
		@Nonnull String entityType,
		@Nonnull EntityIndexKey indexKey
	) {
		this.primaryKey = primaryKey;
		this.version = 1;
		this.dirty = new TransactionalBoolean();
		this.indexKey = indexKey;
		this.entityIds = new TransactionalBitmap();
		this.entityIdsByLanguage = new TransactionalMap<>(new HashMap<>(16), TransactionalBitmap.class, TransactionalBitmap::new);
		this.attributeIndex = new AttributeIndex(entityType, indexKey.discriminator() instanceof RepresentativeReferenceKey rk ? rk : null);
		this.hierarchyIndex = new HierarchyIndex();
		this.facetIndex = new FacetIndex();
		this.originalHierarchyIndexEmpty = true;
		this.originalAttributeIndexes = Collections.emptySet();
		this.originalPriceIndexes = Collections.emptySet();
		this.originalFacetIndexes = Collections.emptySet();
		this.originalHistogramKeys = Collections.emptySet();
		registerBaseComponents();
	}

	protected EntityIndex(
		int primaryKey,
		@Nonnull EntityIndexKey indexKey,
		int version,
		@Nonnull Bitmap entityIds,
		@Nonnull Map<Locale, TransactionalBitmap> entityIdsByLanguage,
		@Nonnull AttributeIndex attributeIndex,
		@Nonnull HierarchyIndex hierarchyIndex,
		@Nonnull FacetIndex facetIndex,
		@Nonnull PriceIndexContract priceIndex
	) {
		this.primaryKey = primaryKey;
		this.indexKey = indexKey;
		this.version = version;
		this.dirty = new TransactionalBoolean();
		this.entityIds = new TransactionalBitmap(entityIds);

		final Map<Locale, TransactionalBitmap> txEntityIdsByLanguage = createHashMap(entityIdsByLanguage.size());
		for (Entry<Locale, TransactionalBitmap> entry : entityIdsByLanguage.entrySet()) {
			txEntityIdsByLanguage.put(entry.getKey(), new TransactionalBitmap(entry.getValue()));
		}
		this.entityIdsByLanguage = new TransactionalMap<>(txEntityIdsByLanguage, TransactionalBitmap.class, TransactionalBitmap::new);
		this.attributeIndex = attributeIndex;
		this.hierarchyIndex = hierarchyIndex;
		this.facetIndex = facetIndex;
		this.originalHierarchyIndexEmpty = this.hierarchyIndex.isHierarchyIndexEmpty();
		this.originalAttributeIndexes = getAttributeIndexStorageKeys();
		this.originalPriceIndexes = getPriceIndexKeys(priceIndex);
		this.originalFacetIndexes = getFacetIndexReferencedEntities();
		this.originalHistogramKeys = Collections.emptySet();
		registerBaseComponents();
	}

	protected EntityIndex(
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
		@Nonnull Set<String> originalFacetIndexes
	) {
		this.primaryKey = primaryKey;
		this.indexKey = indexKey;
		this.version = version;
		this.dirty = new TransactionalBoolean();
		this.entityIds = entityIds;
		this.entityIdsByLanguage = entityIdsByLanguage;
		this.attributeIndex = attributeIndex;
		this.hierarchyIndex = hierarchyIndex;
		this.facetIndex = facetIndex;
		this.originalHierarchyIndexEmpty = originalHierarchyIndexEmpty;
		this.originalAttributeIndexes = originalAttributeIndexes;
		this.originalPriceIndexes = originalPriceIndexes;
		this.originalFacetIndexes = originalFacetIndexes;
		this.originalHistogramKeys = Collections.emptySet();
		registerBaseComponents();
	}

	/**
	 * Registers new entity primary key to the superset of entity ids of this entity index.
	 */
	public boolean insertPrimaryKeyIfMissing(int entityPrimaryKey) {
		final boolean added = this.entityIds.add(entityPrimaryKey);
		if (added) {
			this.dirty.setToTrue();
		}
		return added;
	}

	/**
	 * Removes existing from the superset of entity ids of this entity index.
	 */
	public boolean removePrimaryKey(int entityPrimaryKey) {
		final boolean removed = this.entityIds.remove(entityPrimaryKey);
		if (removed) {
			this.dirty.setToTrue();
		}
		return removed;
	}

	/**
	 * Returns true if the `entityPrimaryKey` is known in the index.
	 */
	public boolean isPrimaryKeyKnown(int entityPrimaryKey) {
		return this.entityIds.contains(entityPrimaryKey);
	}

	/**
	 * Returns superset of all entity ids known to this entity index.
	 */
	@Nonnull
	public Formula getAllPrimaryKeysFormula() {
		return this.entityIds.isEmpty() ? EmptyFormula.INSTANCE : new ConstantFormula(this.entityIds);
	}

	/**
	 * Returns superset of all entity ids known to this entity index.
	 */
	@Nonnull
	public Bitmap getAllPrimaryKeys() {
		return this.entityIds;
	}

	/**
	 * Inserts information that entity with `entityPrimaryKey` has localized attribute / associated data of passed `locale`.
	 * If such information is already present no changes are made.
	 *
	 * @return true if the language was added, false if it was already present
	 */
	public boolean upsertLanguage(@Nonnull Locale locale, int entityPrimaryKey, @Nonnull EntitySchemaContract schema) {
		final Set<Locale> allowedLocales = schema.getLocales();
		isTrue(
			allowedLocales.contains(locale) || schema.getEvolutionMode().contains(EvolutionMode.ADDING_LOCALES),
			"Locale " + locale + " is not allowed by the schema!"
		);

		final boolean added = this.entityIdsByLanguage
			.computeIfAbsent(locale, loc -> new TransactionalBitmap())
			.add(entityPrimaryKey);

		if (added) {
			this.dirty.setToTrue();
		}

		return added;
	}

	/**
	 * Removed information that entity with `recordId` has no longer any localized attribute / associated data of passed `language`.
	 *
	 * @return true if the language was removed, false if it was not present
	 */
	public boolean removeLanguage(@Nonnull Locale locale, int recordId) {
		final TransactionalBitmap recordIdsWithLanguage = this.entityIdsByLanguage.get(locale);
		final boolean removed = recordIdsWithLanguage != null && recordIdsWithLanguage.remove(recordId);

		Assert.isTrue(
			!isRequireLocaleRemoval() || removed,
			"Entity `" + recordId + "` has unexpectedly not indexed localized data for language `" + locale + "`!"
		);

		if (removed) {
			this.dirty.setToTrue();
		}

		if (recordIdsWithLanguage != null && recordIdsWithLanguage.isEmpty()) {
			this.entityIdsByLanguage.remove(locale);
			this.dirty.setToTrue();
			// remove the changes container - the bitmap got removed entirely
			removeTransactionalMemoryLayerIfExists(recordIdsWithLanguage);
		}
		return true;
	}

	/**
	 * Retrieves a unique index for the given attribute schema and optional locale.
	 *
	 * @param referenceSchema The reference schema contract that is envelope for attribute schema contract.
	 *                        Can be null when attribute is defined on entity level.
	 * @param attributeSchema The schema of the attribute for which the unique index is being retrieved. Must not be null.
	 * @param locale The locale for which the unique index is sought, can be null.
	 * @return The unique index corresponding to the specified attribute schema and locale, or null if it does not exist.
	 */
	@Nullable
	public UniqueIndex getUniqueIndex(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nullable Locale locale
	) {
		return this.attributeIndex.getUniqueIndex(referenceSchema, attributeSchema, this.indexKey.scope(), locale);
	}

	/**
	 * Returns formula that computes all record ids in this index that has at least one localized attribute / associated
	 * data in passed `locale`.
	 */
	@Nonnull
	public Formula getRecordsWithLanguageFormula(@Nonnull Locale locale) {
		return ofNullable(this.entityIdsByLanguage.get(locale))
			.map(it -> (Formula) new LocaleFormula(it))
			.orElse(EmptyFormula.INSTANCE);
	}

	/**
	 * Returns collection of all languages that are present in this {@link EntityIndex}.
	 */
	@Nonnull
	public Collection<Locale> getLanguages() {
		return this.entityIdsByLanguage.keySet();
	}

	/**
	 * Returns true if index contains no data whatsoever.
	 */
	public boolean isEmpty() {
		return this.entityIds.isEmpty() &&
			this.entityIdsByLanguage.isEmpty() &&
			this.facetIndex.isEmpty() &&
			this.attributeIndex.isAttributeIndexEmpty() &&
			this.hierarchyIndex.isHierarchyIndexEmpty();
	}

	@Override
	public int version() {
		return this.version;
	}

	/**
	 * Method returns collection of all modified parts of this index that were modified and needs to be stored.
	 *
	 * The flush walks the registered {@link IndexComponent} list in order: each component emits its own
	 * modified storage parts and announces its live keys into a shared {@link EntityIndexManifest}. The
	 * collected manifest is then compared against the captured originals; on any divergence (or when the
	 * dirty flag is set) a fresh {@link EntityIndexStoragePart} is built via the {@link #createStoragePart}
	 * hook so subclasses can still augment the manifest with their own attribute index types
	 * (e.g. CARDINALITY) and histogram keys.
	 *
	 * @param trappedChanges the accumulator collecting modified storage parts for the current commit
	 */
	public final void getModifiedStorageParts(@Nonnull TrappedChanges trappedChanges) {
		final EntityIndexManifest manifest = new EntityIndexManifest();
		// walk every registered component in deterministic order — each emits its own dirty storage
		// parts and populates the manifest with the live key set it currently owns
		for (int i = 0; i < this.components.size(); i++) {
			this.components.get(i).collectModifiedStorageParts(this.primaryKey, manifest, trappedChanges);
		}

		final boolean hierarchyIndexEmpty = !manifest.isHierarchyPresent();
		final Set<AttributeIndexStorageKey> attributeIndexStorageKeys = manifest.getAttributeKeys();
		final Set<PriceIndexKey> priceIndexKeys = manifest.getPriceKeys();
		final Set<String> facetIndexReferencedEntities = manifest.getFacetReferencedEntities();
		final Set<HistogramIndexStorageKey> histogramIndexStorageKeys = manifest.getHistogramKeys();
		if (this.dirty.isTrue() ||
			this.originalHierarchyIndexEmpty != hierarchyIndexEmpty ||
			!Objects.equals(this.originalAttributeIndexes, attributeIndexStorageKeys) ||
			!Objects.equals(this.originalPriceIndexes, priceIndexKeys) ||
			!Objects.equals(this.originalFacetIndexes, facetIndexReferencedEntities) ||
			!Objects.equals(this.originalHistogramKeys, histogramIndexStorageKeys)
		) {
			// after Phase 1.3 every sub-index — including CARDINALITY and HISTOGRAM types — flows
			// through the component-populated manifest, so the base implementation now writes the
			// fully-shaped EntityIndexStoragePart without any subclass hook
			trappedChanges.addChangeToStore(
				createStoragePart(
					hierarchyIndexEmpty, attributeIndexStorageKeys, priceIndexKeys,
					facetIndexReferencedEntities, histogramIndexStorageKeys
				)
			);
		}
	}

	@Override
	public final void resetDirty() {
		this.dirty.reset();
		for (int i = 0; i < this.components.size(); i++) {
			this.components.get(i).resetDirty();
		}
	}

	/**
	 * Removes the transactional memory layers of various referenced producers associated with the given transactional
	 * layer. This method is used when index is removed to clear all orphaned transactional memory layers.
	 *
	 * @param transactionalLayer the instance of TransactionalLayerMaintainer whose layers are to be removed from the referenced producers
	 */
	public final void removeTransactionalMemoryOfReferencedProducers(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		this.dirty.removeLayer(transactionalLayer);
		this.entityIds.removeLayer(transactionalLayer);
		this.entityIdsByLanguage.removeLayer(transactionalLayer);
		for (int i = 0; i < this.components.size(); i++) {
			this.components.get(i).removeLayer(transactionalLayer);
		}
	}

	/**
	 * Registers an additional {@link IndexComponent} into the flush/reset/remove loop. Subclasses
	 * call this from their constructors to add subclass-owned sub-systems (e.g. price index) after
	 * the base components have been registered.
	 *
	 * @param component the component to register
	 */
	protected final void addComponent(@Nonnull IndexComponent component) {
		this.components.add(component);
	}

	/**
	 * Rebuilds the change-detection baseline (`originalAttributeIndexes`, `originalPriceIndexes`,
	 * `originalFacetIndexes`, `originalHistogramKeys`, `originalHierarchyIndexEmpty`) by running every
	 * registered {@link IndexComponent} once against a discardable {@link EntityIndexManifest}. The
	 * resulting manifest snapshot becomes the "what was on disk" reference against which
	 * {@link #getModifiedStorageParts(TrappedChanges)} diffs the current state to decide whether a
	 * fresh {@link EntityIndexStoragePart} must be persisted.
	 *
	 * Subclasses that own sub-indexes contributing to the manifest (e.g. CARDINALITY attribute
	 * indexes, histograms) must call this method **at the end of every constructor** — after both
	 * the super constructor and every {@link #addComponent(IndexComponent)} call — so that the
	 * baseline reflects the keys their components will advertise on the first flush.
	 *
	 * Component implementations must keep `collectModifiedStorageParts` read-only on their own
	 * state (it may emit storage parts when dirty but must not mutate dirty flags or contents) so
	 * that running them here is side-effect-free aside from the discarded {@link TrappedChanges}.
	 */
	protected final void captureOriginalsFromComponents() {
		final EntityIndexManifest baseline = new EntityIndexManifest();
		// the trapped-changes sink is intentionally discarded — only the manifest is captured;
		// any storage parts a clean (just-loaded) component would emit are dropped on the floor
		final TrappedChanges sink = new TrappedChanges();
		for (int i = 0; i < this.components.size(); i++) {
			this.components.get(i).collectModifiedStorageParts(this.primaryKey, baseline, sink);
		}
		this.originalHierarchyIndexEmpty = !baseline.isHierarchyPresent();
		this.originalAttributeIndexes = Set.copyOf(baseline.getAttributeKeys());
		this.originalPriceIndexes = Set.copyOf(baseline.getPriceKeys());
		this.originalFacetIndexes = Set.copyOf(baseline.getFacetReferencedEntities());
		this.originalHistogramKeys = Set.copyOf(baseline.getHistogramKeys());
	}

	/**
	 * Registers the three intrinsic components shared by every `EntityIndex` subclass: attribute,
	 * hierarchy, and facet. Called from every base constructor so the component list is always
	 * non-empty by the time control returns to the subclass constructor body.
	 */
	private void registerBaseComponents() {
		this.components.add(new AttributeIndexComponent(this.attributeIndex, this.indexKey));
		this.components.add(this.hierarchyIndex);
		this.components.add(this.facetIndex);
	}

	/**
	 * Retrieves the price index for the implementing entity.
	 *
	 * @return an instance of the price index conforming to the PriceIndexContract.
	 */
	@Nonnull
	public abstract <S extends PriceIndexContract> S getPriceIndex();

	/**
	 * Checks if the given primary key is present in the set of entity IDs.
	 *
	 * @param primaryKey the primary key to check for presence in the entity index
	 * @return true if the primary key is present, false otherwise
	 */
	public boolean contains(int primaryKey) {
		return this.entityIds.contains(primaryKey);
	}

	/**
	 * Method creates container that is possible to serialize and store into persistent storage.
	 * After Phase 1.3 of the index-component refactor this is **no longer a subclass override hook**
	 * — every sub-index type, including CARDINALITY (attribute keys) and HISTOGRAM, contributes its
	 * keys through the registered {@link IndexComponent} list, so the base implementation has full
	 * knowledge to construct the fully-shaped {@link EntityIndexStoragePart}.
	 *
	 * @param hierarchyIndexEmpty           `true` when the hierarchy index has no live data
	 * @param attributeIndexStorageKeys     all attribute-index storage keys gathered from components
	 * @param priceIndexKeys                all price-list-and-currency storage keys gathered from
	 *                                      components
	 * @param facetIndexReferencedEntities  all facet referenced entity types gathered from components
	 * @param histogramIndexStorageKeys     all histogram storage keys gathered from components
	 * @return the fully-shaped storage part listing every sub-index that must reload on restart
	 */
	@Nonnull
	private StoragePart createStoragePart(
		boolean hierarchyIndexEmpty,
		@Nonnull Set<AttributeIndexStorageKey> attributeIndexStorageKeys,
		@Nonnull Set<PriceIndexKey> priceIndexKeys,
		@Nonnull Set<String> facetIndexReferencedEntities,
		@Nonnull Set<HistogramIndexStorageKey> histogramIndexStorageKeys
	) {
		return new EntityIndexStoragePart(
			this.primaryKey, this.version, this.indexKey,
			this.entityIds, this.entityIdsByLanguage,
			attributeIndexStorageKeys,
			priceIndexKeys,
			!hierarchyIndexEmpty,
			facetIndexReferencedEntities,
			histogramIndexStorageKeys
		);
	}

	@Override
	public String toString() {
		return "EntityIndex (" + StringUtils.uncapitalize(getIndexKey().toString()) + ")";
	}

	/**
	 * Returns true if the index requires removal of the locale from the entityIdsByLanguage map.
	 * @return true if locale removal is required, false otherwise
	 */
	protected boolean isRequireLocaleRemoval() {
		return true;
	}

	/**
	 * Returns the set of referenced entities in the facet index.
	 *
	 * @return the set of referenced entities in the facet index
	 */
	@Nonnull
	private Set<String> getFacetIndexReferencedEntities() {
		return this.facetIndex.getReferencedEntities();
	}

	/**
	 * Retrieves the set of price index keys from a given PriceIndexContract.
	 *
	 * @param priceIndex the PriceIndexContract from which to retrieve the price index keys
	 * @return a set of PriceIndexKey objects representing the price index keys
	 */
	@Nonnull
	private static Set<PriceIndexKey> getPriceIndexKeys(@Nonnull PriceIndexContract priceIndex) {
		return priceIndex
			.getPriceListAndCurrencyIndexes()
			.stream()
			.map(PriceListAndCurrencyPriceIndex::getPriceIndexKey)
			.collect(Collectors.toSet());
	}

	/**
	 * Collects attribute index storage keys into the given set. Includes keys for UNIQUE, FILTER,
	 * SORT, and CHAIN attribute indexes. Subclasses can override to add additional keys
	 * (e.g., cardinality and histogram keys).
	 *
	 * @return the set of attribute index storage keys
	 */
	@Nonnull
	private Set<AttributeIndexStorageKey> getAttributeIndexStorageKeys() {
		final Set<AttributeIndexStorageKey> result = CollectionUtils.createHashSet(
			this.attributeIndex.getUniqueIndexes().size() +
				this.attributeIndex.getFilterIndexes().size() +
				this.attributeIndex.getSortIndexes().size() +
				this.attributeIndex.getChainIndexes().size()
		);
		for (AttributeIndexKey key : this.attributeIndex.getUniqueIndexes()) {
			result.add(new AttributeIndexStorageKey(this.indexKey, AttributeIndexType.UNIQUE, key));
		}
		for (AttributeIndexKey key : this.attributeIndex.getFilterIndexes()) {
			result.add(new AttributeIndexStorageKey(this.indexKey, AttributeIndexType.FILTER, key));
		}
		for (AttributeIndexKey key : this.attributeIndex.getSortIndexes()) {
			result.add(new AttributeIndexStorageKey(this.indexKey, AttributeIndexType.SORT, key));
		}
		for (AttributeIndexKey key : this.attributeIndex.getChainIndexes()) {
			result.add(new AttributeIndexStorageKey(this.indexKey, AttributeIndexType.CHAIN, key));
		}
		return result;
	}

}
