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

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.Versioned;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.core.buffer.DataStoreChanges.RemovedStoragePart;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.locale.LocaleFormula;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.dataType.Scope;
import io.evitadb.index.attribute.AttributeIndex;
import io.evitadb.index.attribute.AttributeIndexContract;
import io.evitadb.index.attribute.AttributeIndexEditorContract;
import io.evitadb.index.attribute.EntityAttributeIndex;
import io.evitadb.index.attribute.ReferenceAttributeIndex;
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
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexRootRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FacetIndexRootRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HierarchyIndexRootRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramRootRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.PriceIndexRootRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIdsStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexStorageKey;
import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;
import io.evitadb.utils.VMLayout;
import lombok.Getter;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
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
import java.util.function.Function;

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
	AttributeIndexEditorContract,
	PriceIndexContract,
	Versioned,
	IndexDataStructure
{
	/**
	 * Capacity the {@link #components} list is pre-sized to — chosen to hold the three intrinsic components plus
	 * every extension a subclass registers without a single grow. Named because the heap estimate models the backing
	 * array from it: an unread capacity would have to be guessed.
	 */
	private static final int INITIAL_COMPONENT_CAPACITY = 8;

	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();

	/**
	 * This part of index collects information about filterable/unique/sortable attributes of the entities. It provides
	 * data that are necessary for constructing {@link Formula} tree for the constraints
	 * related to the attributes.
	 */
	@Delegate(types = AttributeIndexContract.class)
	protected final AttributeIndex attributeIndex;
	/**
	 * Internal flag that tracks whether the entity-id membership bitmaps ({@link #entityIds} /
	 * {@link #entityIdsByLanguage}) changed and must be re-persisted. It drives two things: the index
	 * {@link #version} bump on commit, and the emission of the sibling {@link EntityIdsStoragePart}
	 * during flush. Manifest ({@link EntityIndexStoragePart}) re-emission, by contrast, is decided by the
	 * structural diff against the captured `original*` baselines — so a pure membership change no longer
	 * forces the bulky manifest to be rewritten.
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
	 * should be persisted.
	 */
	protected boolean originalHierarchyIndexEmpty;
	/**
	 * This field captures the original state of the attribute index when this index was created.
	 * This information is used along with {@link #dirty} flag to determine whether {@link EntityIndexStoragePart}
	 * should be persisted.
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
	 * This information is used along with {@link #dirty} flag to determine whether {@link EntityIndexStoragePart}
	 * should be persisted.
	 */
	protected Set<HistogramIndexStorageKey> originalHistogramKeys;
	/**
	 * Whether this index already existed in persistent storage when it was constructed (proxied by a
	 * committed entity-id bitmaps part, i.e. a non-empty `entityIds`). It guarantees the manifest is
	 * written at least once even for an index whose only change is entity membership (no sub-index ever
	 * appearing): such a never-persisted index would otherwise emit a bitmaps part with no manifest and
	 * become unreloadable — see {@link #getModifiedStorageParts(TrappedChanges)}. The value is derived
	 * from the non-emptiness of the supplied `entityIds` at construction, so it self-heals across the
	 * transactional copy: once the first bitmaps part is committed, the next copy is built from a
	 * non-empty bitmap and observes `true`.
	 */
	protected final boolean previouslyPersisted;
	/**
	 * Ordered list of self-registering sub-systems that participate in commit-time flush and
	 * transactional-layer lifecycle. Populated by the base constructors with the three intrinsic
	 * components (attribute, hierarchy, facet) and extended by subclass constructors via
	 * {@link #addComponent(IndexComponent)} — order matters for deterministic flush sequencing.
	 */
	private final List<IndexComponent> components = new ArrayList<>(INITIAL_COMPONENT_CAPACITY);
	/**
	 * Query / update counters and last-activity stamps of this index — see {@link IndexActivity}.
	 *
	 * Threaded **by reference** through the reconstruction constructor, so the commit-time merge copy keeps counting
	 * into the very same holder while a reload from disk starts a fresh one. It is the one piece of state here that is
	 * neither transactional nor persisted.
	 */
	@Nonnull private final IndexActivity activity;

	/**
	 * Read-only accessor exposed for `EntityIndexReloadPlanSymmetryTest`. Returns an unmodifiable
	 * view of the registered {@link IndexComponent}s so the test can verify that every write-side
	 * component has a matching read-side
	 * {@link io.evitadb.index.component.loader.ComponentLoader} in the subclass's `reloadPlan()`.
	 *
	 * @return an unmodifiable list of registered components in registration order
	 */
	@Nonnull
	public List<IndexComponent> getRegisteredComponents() {
		return Collections.unmodifiableList(this.components);
	}

	/**
	 * Creates a brand-new, empty entity index at version 1. Fresh attribute, hierarchy, and facet
	 * sub-indexes are allocated and registered as the three intrinsic {@link IndexComponent}s. The
	 * change-detection baseline (`original*` fields) is initialized to empty placeholders — no
	 * snapshot is captured here because terminal subclasses still need to register their own
	 * components (price, cardinality, histogram, etc.). Each terminal subclass constructor must
	 * therefore invoke {@link #captureOriginalsFromComponents()} as its final step.
	 *
	 * The attribute sub-index variant is chosen from `indexKey`: a {@link ReferenceAttributeIndex}
	 * for reference-scoped indexes (see {@link #isReferenceScoped}), a plain {@link EntityAttributeIndex}
	 * for the global index.
	 *
	 * @param primaryKey the unique identifier of this index instance within the catalog
	 * @param entityType the entity type this index belongs to
	 * @param indexKey   the key (type + discriminator) describing what slice of data this index covers
	 */
	protected EntityIndex(
		int primaryKey,
		@Nonnull String entityType,
		@Nonnull EntityIndexKey indexKey
	) {
		this.primaryKey = primaryKey;
		this.version = 1;
		this.dirty = new TransactionalBoolean();
		this.indexKey = indexKey;
		// a brand-new index has been neither queried nor updated yet
		this.activity = new IndexActivity();
		this.entityIds = new TransactionalBitmap();
		// a fresh index has no persisted bitmaps yet
		this.previouslyPersisted = false;
		this.entityIdsByLanguage = new TransactionalMap<>(new HashMap<>(16), TransactionalBitmap.class, TransactionalBitmap::new);
		final RepresentativeReferenceKey discriminatorRefKey =
			indexKey.discriminator() instanceof RepresentativeReferenceKey rk ? rk : null;
		this.attributeIndex = isReferenceScoped(indexKey)
			? new ReferenceAttributeIndex(entityType, discriminatorRefKey)
			: new EntityAttributeIndex(entityType);
		this.hierarchyIndex = new HierarchyIndex();
		this.facetIndex = new FacetIndex();
		this.originalHierarchyIndexEmpty = true;
		this.originalAttributeIndexes = Collections.emptySet();
		this.originalPriceIndexes = Collections.emptySet();
		this.originalFacetIndexes = Collections.emptySet();
		this.originalHistogramKeys = Collections.emptySet();
		registerBaseComponents();
	}

	/**
	 * Reconstructs an entity index from persisted state. The attribute, hierarchy, and facet
	 * sub-indexes are supplied by the caller (already populated from disk) and registered as the
	 * three intrinsic {@link IndexComponent}s. The supplied `entityIds` bitmap and
	 * `entityIdsByLanguage` map are copied into transactional wrappers so subsequent mutations stay
	 * confined to the transactional layer.
	 *
	 * The change-detection baseline is left as empty placeholders; terminal subclasses must call
	 * {@link #captureOriginalsFromComponents()} as the final step of their constructor — only by
	 * then is every subclass-owned component (price, cardinality, histogram, etc.) registered, so the
	 * captured manifest reflects the full on-disk state and dirty-tracking can correctly skip
	 * persisting unchanged sub-indexes.
	 *
	 * @param primaryKey          the unique identifier of this index instance within the catalog
	 * @param indexKey            the key describing what slice of data this index covers
	 * @param version             the index version loaded from disk
	 * @param entityIds           bitmap of all entity primary keys known to this index
	 * @param entityIdsByLanguage entity primary keys partitioned by locale
	 * @param attributeIndex      the attribute sub-index reconstructed from persisted parts
	 * @param hierarchyIndex      the hierarchy sub-index reconstructed from persisted parts
	 * @param facetIndex          the facet sub-index reconstructed from persisted parts
	 * @param activity            the activity holder this index continues counting into — the **same instance** the
	 *                            copied index held when the caller is the commit-time merge copy, and a fresh one when
	 *                            the index is being loaded from disk. It is a required parameter precisely so that a
	 *                            future copy site has to state which of the two it is; see {@link IndexActivity}
	 */
	protected EntityIndex(
		int primaryKey,
		@Nonnull EntityIndexKey indexKey,
		int version,
		@Nonnull Bitmap entityIds,
		@Nonnull Map<Locale, TransactionalBitmap> entityIdsByLanguage,
		@Nonnull AttributeIndex attributeIndex,
		@Nonnull HierarchyIndex hierarchyIndex,
		@Nonnull FacetIndex facetIndex,
		@Nonnull IndexActivity activity
	) {
		this.primaryKey = primaryKey;
		this.indexKey = indexKey;
		this.version = version;
		this.dirty = new TransactionalBoolean();
		this.activity = activity;
		this.entityIds = new TransactionalBitmap(entityIds);
		// reloaded / transactionally-copied indexes already carry persisted bitmaps when non-empty;
		// this self-heals across the commit copy, which is built from the committed (non-empty) bitmap
		this.previouslyPersisted = !entityIds.isEmpty();

		final Map<Locale, TransactionalBitmap> txEntityIdsByLanguage = createHashMap(entityIdsByLanguage.size());
		for (Entry<Locale, TransactionalBitmap> entry : entityIdsByLanguage.entrySet()) {
			txEntityIdsByLanguage.put(entry.getKey(), new TransactionalBitmap(entry.getValue()));
		}
		this.entityIdsByLanguage = new TransactionalMap<>(txEntityIdsByLanguage, TransactionalBitmap.class, TransactionalBitmap::new);
		this.attributeIndex = attributeIndex;
		this.hierarchyIndex = hierarchyIndex;
		this.facetIndex = facetIndex;
		// baseline initialization is deferred — every terminal subclass constructor calls
		// captureOriginalsFromComponents() after registering its own price / cardinality /
		// histogram components, so the captured manifest covers the full component tree
		this.originalHierarchyIndexEmpty = true;
		this.originalAttributeIndexes = Collections.emptySet();
		this.originalPriceIndexes = Collections.emptySet();
		this.originalFacetIndexes = Collections.emptySet();
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

	// Granular per-structure attribute write primitives. These are intentionally NOT part of the public
	// AttributeIndexEditorContract — the mutation layer drives the index through the coarse upsert / remove /
	// applyDelta operations below. They are exposed as concrete, overridable methods purely so those coarse operations
	// can dispatch through `this`, letting the referenced-type / group subclasses override them to gate writes on a
	// cardinality boundary or to no-op the structures they do not maintain. Each one forwards to the attribute sub-index.

	/**
	 * Registers `value` for `recordId` in the unique structure of `attributeSchema`. Overridable primitive — see the
	 * note above. Returns where this value's uniqueness is enforced (an index that does not maintain unique attributes
	 * overrides this to return {@link AttributeIndex.UniquenessEnforcement#NONE}).
	 *
	 * @return where this value's uniqueness is enforced
	 * @throws io.evitadb.api.exception.UniqueValueViolationException when value is not unique
	 */
	public AttributeIndex.UniquenessEnforcement insertUniqueAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nonnull Scope scope,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		return this.attributeIndex.insertUniqueAttribute(referenceSchema, attributeSchema, allowedLocales, scope, locale, value, recordId);
	}

	/**
	 * Drops `value` for `recordId` from the unique structure of `attributeSchema`. Overridable primitive — see the
	 * note above.
	 */
	public void removeUniqueAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nonnull Scope scope,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		this.attributeIndex.removeUniqueAttribute(referenceSchema, attributeSchema, allowedLocales, scope, locale, value, recordId);
	}

	/**
	 * Adds `value` for `recordId` to the filter structure of `attributeSchema`. Overridable primitive — see the note
	 * above. When `foldedUnique` is set the write enforces folded per-value uniqueness and registers the folded view.
	 */
	public void insertFilterAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId,
		boolean foldedUnique
	) {
		this.attributeIndex.insertFilterAttribute(referenceSchema, attributeSchema, allowedLocales, locale, value, recordId, foldedUnique);
	}

	/**
	 * Removes `value` for `recordId` from the filter structure of `attributeSchema`. Overridable primitive — see the
	 * note above.
	 */
	public void removeFilterAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		this.attributeIndex.removeFilterAttribute(referenceSchema, attributeSchema, allowedLocales, locale, value, recordId);
	}

	/**
	 * Adds the array delta `value` for `recordId` to the filter structure (array attributes only). Overridable
	 * primitive — see the note above. When `foldedUnique` is set every new element is verified and the view registered.
	 */
	public void addDeltaFilterAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable[] value,
		int recordId,
		boolean foldedUnique
	) {
		this.attributeIndex.addDeltaFilterAttribute(referenceSchema, attributeSchema, allowedLocales, locale, value, recordId, foldedUnique);
	}

	/**
	 * Removes the array delta `value` for `recordId` from the filter structure (array attributes only). Overridable
	 * primitive — see the note above.
	 */
	public void removeDeltaFilterAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable[] value,
		int recordId
	) {
		this.attributeIndex.removeDeltaFilterAttribute(referenceSchema, attributeSchema, allowedLocales, locale, value, recordId);
	}

	/**
	 * Adds `value` for `recordId` to the sort structure of `attributeSchema`. Overridable primitive — see the note
	 * above.
	 */
	public void insertSortAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		this.attributeIndex.insertSortAttribute(referenceSchema, attributeSchema, allowedLocales, locale, value, recordId);
	}

	/**
	 * Removes `value` for `recordId` from the sort structure of `attributeSchema`. Overridable primitive — see the
	 * note above.
	 */
	public void removeSortAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		this.attributeIndex.removeSortAttribute(referenceSchema, attributeSchema, allowedLocales, locale, value, recordId);
	}

	/**
	 * Adds the compound `value` for `recordId` to the sort structure described by `compoundSchema`. Overridable
	 * primitive — see the note above.
	 */
	public void insertSortAttributeCompound(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchema,
		@Nonnull Function<String, Class<?>> attributeTypeProvider,
		@Nullable Locale locale,
		@Nonnull Serializable[] value,
		int recordId
	) {
		this.attributeIndex.insertSortAttributeCompound(entitySchema, referenceSchema, compoundSchema, attributeTypeProvider, locale, value, recordId);
	}

	/**
	 * Removes the compound `value` for `recordId` from the sort structure described by `compoundSchema`. Overridable
	 * primitive — see the note above.
	 */
	public void removeSortAttributeCompound(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchema,
		@Nullable Locale locale,
		@Nonnull Serializable[] value,
		int recordId
	) {
		this.attributeIndex.removeSortAttributeCompound(entitySchema, referenceSchema, compoundSchema, locale, value, recordId);
	}

	/**
	 * Inserts the attribute `value` for `recordId` into every index structure the `attributeSchema` enables in
	 * `scope` — the unique, filter and sort sub-indexes — in one schema-driven operation. This is the insert half
	 * of an attribute upsert: callers pair it with {@link #removeAttribute} for the previous value (the two halves
	 * may target different index instances during a reference group reassignment).
	 *
	 * The per-structure primitives ({@link #insertUniqueAttribute}, {@link #insertFilterAttribute},
	 * {@link #insertSortAttribute}) are invoked on `this`, so subclass overrides (cardinality gating in the
	 * referenced-type / group indexes, no-op suppression of sort and unique there) compose automatically without
	 * the caller needing to know which structures a particular index maintains.
	 *
	 * A unique attribute that is not separately filterable still shadows its value into the filter index, because
	 * the shared value tree that backs unique reads is the filter structure.
	 *
	 * @param referenceSchema the reference schema owning the attribute, or `null` for entity-level attributes
	 * @param attributeSchema the schema of the attribute being inserted
	 * @param allowedLocales  the set of locales permitted by the entity schema
	 * @param scope           the scope of the target index, deciding which structures are maintained
	 * @param locale          the locale of the value, or `null` for language-agnostic attributes
	 * @param value           the attribute value to insert (a `Serializable[]` for array attributes)
	 * @param recordId        the primary key the value is attributed to
	 */
	@Override
	public void upsertAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nonnull Scope scope,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		final boolean unique = attributeSchema.isUniqueInScope(scope);
		final boolean filterable = attributeSchema.isFilterableInScope(scope);
		final boolean sortable = attributeSchema.isSortableInScope(scope);
		// SORT before FILTER: a view-mode sort index reads the shared value tree in its pre-insert state to compute
		// the new record's position, so the FILTER write (which mutates that tree) must run afterwards
		if (sortable) {
			insertSortAttribute(referenceSchema, attributeSchema, allowedLocales, locale, value, recordId);
		}
		// account for the value's uniqueness (standalone owner store, or — for a folded unique — nothing here) and learn
		// WHERE it is enforced: the primitive reports this explicitly (a sub-index that suppresses unique maintenance
		// returns NONE), so the decision flows by data, not by shared-map state
		AttributeIndex.UniquenessEnforcement uniqueEnforcement = AttributeIndex.UniquenessEnforcement.NONE;
		if (unique) {
			uniqueEnforcement = insertUniqueAttribute(referenceSchema, attributeSchema, allowedLocales, scope, locale, value, recordId);
		}
		if (unique || filterable) {
			// a unique attribute that is not separately filterable still shadows its value into the filter index, because
			// the shared value tree that backs unique reads IS the filter structure. The filter write owns folded-unique
			// enforcement + view registration iff the uniqueness is enforced BY_FILTER_WRITE (the folded representation)
			insertFilterAttribute(
				referenceSchema, attributeSchema, allowedLocales, locale, value, recordId,
				uniqueEnforcement == AttributeIndex.UniquenessEnforcement.BY_FILTER_WRITE
			);
		}
	}

	/**
	 * Removes the attribute `value` for `recordId` from every index structure the `attributeSchema` enables in
	 * `scope` — the unique, filter and sort sub-indexes — in one schema-driven operation. This is both the remove
	 * half of an attribute upsert and the operation used for outright attribute removal.
	 *
	 * Like {@link #upsertAttribute}, the per-structure primitives are invoked on `this` so subclass overrides
	 * compose automatically. The catalog-level global-unique index is intentionally NOT touched here — it is a
	 * separate index object maintained directly by the mutation executor.
	 *
	 * @param referenceSchema the reference schema owning the attribute, or `null` for entity-level attributes
	 * @param attributeSchema the schema of the attribute being removed
	 * @param allowedLocales  the set of locales permitted by the entity schema
	 * @param scope           the scope of the target index, deciding which structures are maintained
	 * @param locale          the locale of the value, or `null` for language-agnostic attributes
	 * @param value           the attribute value to remove (a `Serializable[]` for array attributes)
	 * @param recordId        the primary key the value was attributed to
	 */
	@Override
	public void removeAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nonnull Scope scope,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	) {
		final boolean unique = attributeSchema.isUniqueInScope(scope);
		final boolean filterable = attributeSchema.isFilterableInScope(scope);
		final boolean sortable = attributeSchema.isSortableInScope(scope);
		// SORT before FILTER: a view-mode sort index reads the shared value tree in its pre-removal state (the
		// record is still present), so the FILTER removal (which drops it from that tree) must run afterwards
		if (sortable) {
			removeSortAttribute(referenceSchema, attributeSchema, allowedLocales, locale, value, recordId);
		}
		if (unique) {
			removeUniqueAttribute(referenceSchema, attributeSchema, allowedLocales, scope, locale, value, recordId);
		}
		if (unique || filterable) {
			// mirror of the upsert shadow: drop the value (real or shadowed) from the filter index last
			removeFilterAttribute(referenceSchema, attributeSchema, allowedLocales, locale, value, recordId);
		}
	}

	/**
	 * Transitions the attribute for `recordId` from `oldValue` to `newValue` across every index structure the
	 * `attributeSchema` enables in `scope`. Unlike an upsert, a delta mutation never reassigns the record between
	 * index instances, so the old-value removal and new-value insertion always target the same index and primary
	 * key and can be applied as one operation.
	 *
	 * The per-structure primitives are invoked on `this` so subclass overrides compose automatically. As in
	 * {@link #upsertAttribute}, a unique attribute that is not separately filterable shadows the transition into
	 * the filter index.
	 *
	 * @param referenceSchema the reference schema owning the attribute, or `null` for entity-level attributes
	 * @param attributeSchema the schema of the attribute being modified
	 * @param allowedLocales  the set of locales permitted by the entity schema
	 * @param scope           the scope of the target index, deciding which structures are maintained
	 * @param locale          the locale of the value, or `null` for language-agnostic attributes
	 * @param oldValue        the current attribute value to remove
	 * @param newValue        the new attribute value to insert
	 * @param recordId        the primary key the value is attributed to
	 */
	@Override
	public void applyAttributeDelta(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nonnull Scope scope,
		@Nullable Locale locale,
		@Nonnull Serializable oldValue,
		@Nonnull Serializable newValue,
		int recordId
	) {
		// a delta is a same-index, same-pk transition: remove the old value from every structure (sort-before-filter)
		// then insert the new value the same way. Composing the two coarse halves keeps the ordering invariant and
		// the shadow/uniqueness handling in one place.
		removeAttribute(referenceSchema, attributeSchema, allowedLocales, scope, locale, oldValue, recordId);
		upsertAttribute(referenceSchema, attributeSchema, allowedLocales, scope, locale, newValue, recordId);
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
	 * {@inheritDoc}
	 *
	 * Deliberately `final`: the throwing stubs produced by {@link GlobalEntityIndex#createThrowingStub} and
	 * {@link ReferencedTypeEntityIndex#createThrowingStub} are ByteBuddy proxies whose catch-all classification throws
	 * for every method they can override, and a final method is not one of them. A stub therefore answers with the
	 * (never-read) holder its real super instance allocated rather than raising - the same treatment `getIndexKey` gets
	 * through an explicit pass-through classification.
	 */
	@Nonnull
	@Override
	public final IndexActivity getActivity() {
		return this.activity;
	}

	/**
	 * Returns the heap this index occupies, in bytes — its entity-id bitmaps, every sub-index it owns, and the
	 * persisted-baseline manifest it keeps between flushes.
	 *
	 * This is the figure `IndexDetail#heapSizeInBytes` reports, and the reason that call describes one
	 * named index rather than a whole collection: it walks the whole index tree, so it is `O(contents)` and must never
	 * be called from a query path.
	 *
	 * @return the owned heap footprint in bytes, including alignment padding
	 */
	public abstract long getHeapSizeInBytes();

	/**
	 * Returns the heap this base occupies, in bytes — everything an implementation inherits from it, so a subclass
	 * adds only what it declares itself.
	 *
	 * # What is charged, and what is not
	 *
	 * {@link #indexKey} is **not** charged. The enclosing collection files this index in a map keyed by the very
	 * instance handed to the constructor, so that map owns it and the index pays for its reference slot alone —
	 * the same ruling {@link io.evitadb.index.price.AbstractPriceListAndCurrencyPriceIndex} makes for its own key.
	 *
	 * {@link #components} is the flush ordering, and its slots hold two different kinds of thing. `hierarchyIndex`
	 * and `facetIndex` register **themselves**, so those slots point at structures charged above and following them
	 * would bill the index tree twice. Every other slot holds a **dedicated wrapper** — an
	 * {@link io.evitadb.index.component.AttributeIndexComponent} here, a
	 * {@link io.evitadb.index.component.PriceIndexComponent} and the cardinality and histogram components in the
	 * subclasses — which is an object of its own that nothing else holds, and which must be charged for its shell.
	 * Charging it is not optional: they are small, but there is one per index and a catalog has hundreds of
	 * thousands. Each is charged **by the class that constructs it**, so a component added tomorrow is priced
	 * where it is registered rather than silently going free.
	 *
	 * The four `original*` baselines are charged for their sets and for the storage-key records the last flush
	 * minted, but not for what those records point at: an {@link EntityIndexKey} is this index's own, an
	 * {@link io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey} belongs to the attribute
	 * index that minted it, and reference names, histogram names and locales all belong to the schema. A fresh index
	 * parks all four on {@link Collections#emptySet()} and is charged nothing for them.
	 *
	 * {@link #entityIdsByLanguage} is keyed by {@link Locale}, which the schema shares with every index it touches,
	 * so only the entry slots are charged for the keys.
	 *
	 * {@link #activity} is charged **here, in full**, even though the holder is shared with the superseded versions of
	 * this same logical index: only one version of an index is ever walked, and the predecessor is garbage-in-waiting,
	 * so reporting the four longs as shared would show them belonging to nobody (accounting rule 2).
	 *
	 * @param ownFieldBytes the field bytes the concrete subclass adds to the base's own
	 * @return the owned heap footprint of the inherited state, in bytes, including alignment padding
	 */
	protected final long getBaseHeapSizeInBytes(long ownFieldBytes) {
		final VMLayout layout = VMLayout.current();
		// id, primaryKey, version and the two booleans, then the attributeIndex / dirty / entityIds
		// / entityIdsByLanguage / indexKey / facetIndex / hierarchyIndex / originalAttributeIndexes
		// / originalPriceIndexes / originalFacetIndexes / originalHistogramKeys / components / activity slots, plus
		// whatever the concrete subclass declares - the instance carries ONE header, so the whole hierarchy's fields
		// are sized in a single call
		long size = layout.sizeOfObject(
			Long.BYTES + 2L * Integer.BYTES + 2L + 13L * layout.referenceSize() + ownFieldBytes
		);
		// the activity holder: five longs and nothing else, since its CAS updaters are static
		size += layout.sizeOfObject(5L * Long.BYTES);
		size += this.dirty.getHeapSizeInBytes();
		size += this.entityIds.getHeapSizeInBytes();
		size += this.entityIdsByLanguage.getHeapSizeInBytes(
			locale -> 0L, TransactionalBitmap::getHeapSizeInBytes
		);
		size += this.attributeIndex.getHeapSizeInBytes();
		size += this.facetIndex.getHeapSizeInBytes();
		size += this.hierarchyIndex.getHeapSizeInBytes();
		// the flush ordering: spine and slots, since two of the slots hold the sub-indexes charged above
		size += layout.sizeOfObject(2L * Integer.BYTES + layout.referenceSize())
			+ layout.sizeOfArray(Math.max(INITIAL_COMPONENT_CAPACITY, this.components.size()), layout.referenceSize());
		// the one wrapper this base registers itself, holding the attribute index and the index key
		size += layout.sizeOfObject(2L * layout.referenceSize());
		size += IndexHeapSize.immutableSetSizeInBytes(
			this.originalAttributeIndexes,
			key -> layout.sizeOfObject(3L * layout.referenceSize())
		);
		size += IndexHeapSize.immutableSetSizeInBytes(
			// the price list name and the currency are the schema's, the record handling an enum constant
			this.originalPriceIndexes,
			key -> layout.sizeOfObject(3L * layout.referenceSize() + Integer.BYTES)
		);
		// reference names, owned by the schema that named them
		size += IndexHeapSize.immutableSetSizeInBytes(this.originalFacetIndexes, referenceName -> 0L);
		size += IndexHeapSize.immutableSetSizeInBytes(
			this.originalHistogramKeys,
			key -> layout.sizeOfObject(3L * layout.referenceSize())
		);
		return size;
	}

	/**
	 * Method returns collection of all modified parts of this index that were modified and needs to be stored.
	 *
	 * The flush walks the registered {@link IndexComponent} list in order: each component emits its own
	 * modified storage parts and announces its live keys into a shared {@link EntityIndexManifest}. The
	 * collected manifest is then compared against the captured originals; on any divergence a fresh
	 * {@link EntityIndexStoragePart} is built listing every sub-index that must reload on restart.
	 *
	 * The entity-id membership bitmaps are persisted independently of the manifest: when {@link #dirty}
	 * is set they are re-emitted as a sibling {@link EntityIdsStoragePart} (or removed when the index
	 * emptied out), so a pure membership change no longer rewrites the bulky manifest. The one coupling
	 * is the first write — see the body for why a never-persisted index still emits its manifest.
	 *
	 * @param trappedChanges the accumulator collecting modified storage parts for the current commit
	 */
	public final void getModifiedStorageParts(@Nonnull TrappedChanges trappedChanges) {
		final EntityIndexManifest manifest = new EntityIndexManifest();
		// walk every registered component in deterministic order — each emits its own dirty storage
		// parts and populates the manifest with the live key set it currently owns
		for (IndexComponent component : this.components) {
			component.collectModifiedStorageParts(this.primaryKey, manifest, trappedChanges);
		}

		final boolean hierarchyIndexEmpty = !manifest.isHierarchyPresent();
		final Set<AttributeIndexStorageKey> attributeIndexStorageKeys = manifest.getAttributeKeys();
		final Set<PriceIndexKey> priceIndexKeys = manifest.getPriceKeys();
		final Set<String> facetIndexReferencedEntities = manifest.getFacetReferencedEntities();
		final Set<HistogramIndexStorageKey> histogramIndexStorageKeys = manifest.getHistogramKeys();

		final boolean bitmapsDirty = this.dirty.isTrue();
		final boolean manifestStructurallyChanged =
			this.originalHierarchyIndexEmpty != hierarchyIndexEmpty ||
				!Objects.equals(this.originalAttributeIndexes, attributeIndexStorageKeys) ||
				!Objects.equals(this.originalPriceIndexes, priceIndexKeys) ||
				!Objects.equals(this.originalFacetIndexes, facetIndexReferencedEntities) ||
				!Objects.equals(this.originalHistogramKeys, histogramIndexStorageKeys);

		// The bulky manifest (the sub-index reference sets) is re-emitted only when its own content
		// changed. The extra `bitmapsDirty && !previouslyPersisted` term guarantees the manifest is
		// written at least once: a fresh index whose only change is entity membership (no sub-index ever
		// appearing) must still persist a manifest, or it would be unreloadable (a bitmaps part with no
		// manifest). After the first commit the transactional copy observes a non-empty bitmap and flips
		// `previouslyPersisted` to true, so subsequent membership-only commits skip the manifest.
		if (manifestStructurallyChanged || (bitmapsDirty && !this.previouslyPersisted)) {
			trappedChanges.addChangeToStore(
				createStoragePart(
					hierarchyIndexEmpty, attributeIndexStorageKeys, priceIndexKeys,
					facetIndexReferencedEntities, histogramIndexStorageKeys
				)
			);
		}

		// The entity-id bitmaps live in their own sibling part, re-emitted on any membership change.
		if (bitmapsDirty) {
			if (this.entityIds.isEmpty() && this.entityIdsByLanguage.isEmpty()) {
				// the index emptied out — drop the sibling part so compaction can reclaim it (a no-op
				// when none was ever written, e.g. a fresh index emptied before its first flush)
				trappedChanges.addChangeToStore(
					new RemovedStoragePart(EntityIdsStoragePart.class, this.primaryKey)
				);
			} else {
				trappedChanges.addChangeToStore(createBitmapsPart());
			}
		}

		// Reclaim the roots of sub-indexes that vanished this commit (churn-vanish): when a sub-index is emptied
		// out of its family while this entity index survives, its leaf pages are reclaimed by that family's own
		// page reclaim, but its stable-keyed root part is neither superseded (never re-flushed) nor removed, so it
		// would be copied forward by every compaction forever. The manifest baseline is the only place that tracks
		// SINGLE-shaped roots (which own no leaf pages), so the diff must run here, not in the leaf-page reclaim.
		emitVanishedRootRemovals(
			attributeIndexStorageKeys, priceIndexKeys, facetIndexReferencedEntities,
			histogramIndexStorageKeys, !hierarchyIndexEmpty, trappedChanges
		);
	}

	/**
	 * Emits a root-part removal for every persisted sub-index whose manifest key is in the change-detection
	 * baseline ({@code original*}) but not in the passed surviving set — i.e. a sub-index that was on disk and has
	 * now vanished. Called two ways: from {@link #getModifiedStorageParts(TrappedChanges)} with the freshly
	 * collected manifest sets (churn-vanish of one sub-index while the index survives) and from
	 * {@link #emitFootprintRemovals(TrappedChanges)} with empty surviving sets (the whole index is dropped, so
	 * every persisted root vanishes). Reads only the persisted baseline, never live/transactional state.
	 *
	 * @param survivingAttributes the attribute sub-index keys that remain after this commit
	 * @param survivingPrices     the price sub-index keys that remain
	 * @param survivingFacets     the facet referenced-entity types that remain
	 * @param survivingHistograms the histogram sub-index keys that remain
	 * @param hierarchyPresent    whether a hierarchy is still present
	 * @param sink                the accumulator collecting the removal instructions
	 */
	private void emitVanishedRootRemovals(
		@Nonnull Set<AttributeIndexStorageKey> survivingAttributes,
		@Nonnull Set<PriceIndexKey> survivingPrices,
		@Nonnull Set<String> survivingFacets,
		@Nonnull Set<HistogramIndexStorageKey> survivingHistograms,
		boolean hierarchyPresent,
		@Nonnull TrappedChanges sink
	) {
		for (final AttributeIndexStorageKey key : this.originalAttributeIndexes) {
			if (!survivingAttributes.contains(key)) {
				sink.addChangeToStore(new AttributeIndexRootRemoval(this.primaryKey, key.attribute(), key.indexType()));
			}
		}
		for (final PriceIndexKey key : this.originalPriceIndexes) {
			if (!survivingPrices.contains(key)) {
				sink.addChangeToStore(new PriceIndexRootRemoval(this.primaryKey, key, getPriceRootStoragePartType()));
			}
		}
		for (final String referencedEntityType : this.originalFacetIndexes) {
			if (!survivingFacets.contains(referencedEntityType)) {
				sink.addChangeToStore(new FacetIndexRootRemoval(this.primaryKey, referencedEntityType));
			}
		}
		for (final HistogramIndexStorageKey key : this.originalHistogramKeys) {
			if (!survivingHistograms.contains(key)) {
				sink.addChangeToStore(new HistogramRootRemoval(this.primaryKey, key.histogramName(), key.locale()));
			}
		}
		// a hierarchy root was persisted (baseline non-empty) and is now gone
		if (!this.originalHierarchyIndexEmpty && !hierarchyPresent) {
			sink.addChangeToStore(new HierarchyIndexRootRemoval(this.primaryKey));
		}
	}

	/**
	 * Emits removal instructions reclaiming the ENTIRE persisted footprint of this index when it is dropped: every
	 * persisted sub-index root (via {@link #emitVanishedRootRemovals} against empty surviving sets) plus every
	 * persisted leaf page and every non-manifest sub-index root (e.g. reference-type cardinality) via each
	 * {@link IndexComponent#emitPersistedFootprintRemovals(int, TrappedChanges)}. The manifest and membership
	 * bitmaps are reclaimed separately by the caller (they are primary-key-addressable). Reads only persisted
	 * baselines, so it is safe whether or not the transactional layers are still attached.
	 *
	 * @param sink the accumulator collecting the removal instructions
	 */
	public final void emitFootprintRemovals(@Nonnull TrappedChanges sink) {
		emitVanishedRootRemovals(
			Collections.emptySet(), Collections.emptySet(), Collections.emptySet(),
			Collections.emptySet(), false, sink
		);
		for (final IndexComponent component : this.components) {
			component.emitPersistedFootprintRemovals(this.primaryKey, sink);
		}
	}

	/**
	 * @return the concrete price root storage-part class this index persists its price sub-indexes under (the super
	 * variant for the global index, the reference variant for reduced indexes). Consulted only to target the correct
	 * record type when reclaiming a dropped price sub-index, so it is never called on an index that persisted none.
	 */
	@Nonnull
	protected abstract Class<? extends StoragePart> getPriceRootStoragePartType();

	/**
	 * Advances the change-detection baseline to the state that was just persisted. Invoked by the flush
	 * pipeline ({@link io.evitadb.core.buffer.DataStoreChanges#popTrappedUpdates()}) once, immediately after
	 * {@link #getModifiedStorageParts(TrappedChanges)} has collected this index's parts for the commit, so it
	 * runs exactly when the parts are actually written — and never on the incidental
	 * {@code getModifiedStorageParts} calls made by tests or diagnostics (which is why the baseline refresh
	 * lives here rather than inside the collect method, keeping that method a pure, idempotent read).
	 *
	 * After the flush the on-disk manifest reflects the current sub-index key sets, so the baseline the NEXT
	 * flush diffs against must be those same sets. Without this advance an index first flushed in warm-up
	 * (bulk) mode keeps its empty construction-time baseline while disk already holds the emitted sub-index
	 * keys; the same instance is then reused after {@code goLive}, and a later transactional commit that drops
	 * those sub-indexes (current key set shrinks back to the empty stale baseline) is mis-detected as
	 * "unchanged". The stale manifest and its now-removed sub-index parts are then never rewritten/removed,
	 * while the membership bitmap IS dropped — so on reload the index rebuilds a price/sort sub-index the
	 * membership no longer backs, failing with "Price with id N was not found in the same index!" / the NULL
	 * super-index variant / "Record id N is already present in the sort index!". Pure transactional commits
	 * already refresh the baseline through the merge-copy constructor (which calls
	 * {@link #captureOriginalsFromComponents()}); routing the refresh through this flush hook closes the same
	 * gap on the warm-up -> transactional hand-off where the instance is reused rather than copied.
	 */
	@Override
	public final void notifyFlushed() {
		captureOriginalsFromComponents();
	}

	@Override
	public final void resetDirty() {
		this.dirty.reset();
		for (IndexComponent component : this.components) {
			component.resetDirty();
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
		for (IndexComponent component : this.components) {
			component.removeLayer(transactionalLayer);
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
	 * resulting snapshot is the "what was on disk" reference against which
	 * {@link #getModifiedStorageParts(TrappedChanges)} diffs current state.
	 *
	 * The base {@link EntityIndex} constructors initialize all `original*` fields to empty placeholders
	 * and do not capture any baseline themselves — the component list is only partially populated at
	 * that point. Every terminal subclass constructor must invoke this method as its final step, after
	 * the super constructor has run and after every subclass-owned `addComponent(...)` call. Any future
	 * constructor that instead receives pre-computed baselines from its caller must **not** call this
	 * method, since doing so would overwrite those baselines with current state and silently lose the
	 * dirty-tracking information they carry.
	 */
	protected final void captureOriginalsFromComponents() {
		final EntityIndexManifest baseline = new EntityIndexManifest();
		// the trapped-changes sink is intentionally discarded — only the manifest is captured;
		// any storage parts a clean (just-loaded) component would emit are dropped on the floor
		final TrappedChanges sink = new TrappedChanges();
		for (IndexComponent component : this.components) {
			component.collectModifiedStorageParts(this.primaryKey, baseline, sink);
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
	 * Returns `true` when the supplied {@link EntityIndexKey} designates a reference-scoped index —
	 * any {@link EntityIndexType} other than {@link EntityIndexType#GLOBAL}.
	 *
	 * @param indexKey the index key to classify
	 * @return `true` if the key belongs to a reference-scoped index
	 */
	private static boolean isReferenceScoped(@Nonnull EntityIndexKey indexKey) {
		return indexKey.type() != EntityIndexType.GLOBAL;
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
	 * Builds the {@link EntityIndexStoragePart} listing every sub-index that must reload on restart.
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
			attributeIndexStorageKeys,
			priceIndexKeys,
			!hierarchyIndexEmpty,
			facetIndexReferencedEntities,
			histogramIndexStorageKeys
		);
	}

	/**
	 * Builds the sibling {@link EntityIdsStoragePart} carrying this index's entity-id membership bitmaps
	 * ({@link #entityIds} and {@link #entityIdsByLanguage}). Emitted from
	 * {@link #getModifiedStorageParts(TrappedChanges)} whenever the membership changed.
	 *
	 * @return the storage part holding the entity-id superset bitmap and the per-locale bitmaps
	 */
	@Nonnull
	private StoragePart createBitmapsPart() {
		return new EntityIdsStoragePart(
			this.primaryKey, this.version, this.entityIds, this.entityIdsByLanguage
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

}
