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

package io.evitadb.index;

import io.evitadb.api.requestResponse.data.Versioned;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
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
import io.evitadb.index.facet.FacetIndex;
import io.evitadb.index.facet.FacetIndexContract;
import io.evitadb.index.hierarchy.HierarchyIndex;
import io.evitadb.index.hierarchy.HierarchyIndexContract;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.price.PriceIndexContract;
import io.evitadb.index.price.PriceListAndCurrencyPriceIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.store.spi.model.storageParts.index.EntityIndexStoragePart;
import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;
import lombok.Getter;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.core.Transaction.removeTransactionalMemoryLayerIfExists;
import static io.evitadb.utils.Assert.isTrue;
import static io.evitadb.utils.CollectionUtils.createHashMap;
import static java.util.Optional.ofNullable;

/**
 * This class represents main data structure that keeps all information connected with entity data, that could be used
 * for searching, sorting or another computational task upon these data.
 *
 * There may be multiple {@link EntityIndex} instances with different slices of the original data. There will be always
 * single {@link GlobalEntityIndex} index that contains all the data, but also several thinner
 * {@link ReducedEntityIndex indexes} that would contain only part of these. We aim to choose the smallest index
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
	 * should be persisted.
	 */
	protected final boolean originalHierarchyIndexEmpty;
	/**
	 * This field captures the original state of the attribute index when this index was created.
	 * This information is used along with {@link #dirty} flag to determine whether {@link EntityIndexStoragePart}
	 * should be persisted.
	 */
	protected final Set<AttributeIndexStorageKey> originalAttributeIndexes;
	/**
	 * This field captures the original state of the price indexes when this index was created.
	 * This information is used along with {@link #dirty} flag to determine whether {@link EntityIndexStoragePart}
	 * should be persisted.
	 */
	protected final Set<PriceIndexKey> originalPriceIndexes;
	/**
	 * This field captures the original state of the facet indexes when this index was created.
	 * This information is used along with {@link #dirty} flag to determine whether {@link EntityIndexStoragePart}
	 * should be persisted.
	 */
	protected final Set<String> originalFacetIndexes;

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
		this.entityIdsByLanguage = new TransactionalMap<>(new HashMap<>(), TransactionalBitmap.class, TransactionalBitmap::new);
		this.attributeIndex = new AttributeIndex(entityType, indexKey.discriminator() instanceof RepresentativeReferenceKey rk ? rk : null);
		this.hierarchyIndex = new HierarchyIndex();
		this.facetIndex = new FacetIndex();
		this.originalHierarchyIndexEmpty = true;
		this.originalAttributeIndexes = Collections.emptySet();
		this.originalPriceIndexes = Collections.emptySet();
		this.originalFacetIndexes = Collections.emptySet();
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
	 * @param attributeSchema The schema of the attribute for which the unique index is being retrieved. Must not be null.
	 * @param locale The locale for which the unique index is sought, can be null.
	 * @return The unique index corresponding to the specified attribute schema and locale, or null if it does not exist.
	 */
	@Nullable
	public UniqueIndex getUniqueIndex(@Nonnull AttributeSchemaContract attributeSchema, @Nullable Locale locale) {
		return this.attributeIndex.getUniqueIndex(attributeSchema, this.indexKey.scope(), locale);
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
	 */
	public void getModifiedStorageParts(@Nonnull TrappedChanges trappedChanges) {
		final PriceIndexContract priceIndex = getPriceIndex();
		final boolean hierarchyIndexEmpty = this.hierarchyIndex.isHierarchyIndexEmpty();
		final Set<AttributeIndexStorageKey> attributeIndexStorageKeys = getAttributeIndexStorageKeys();
		final Set<PriceIndexKey> priceIndexKeys = getPriceIndexKeys(priceIndex);
		final Set<String> facetIndexReferencedEntities = getFacetIndexReferencedEntities();
		if (this.dirty.isTrue() ||
			this.originalHierarchyIndexEmpty != hierarchyIndexEmpty ||
			!Objects.equals(this.originalAttributeIndexes, attributeIndexStorageKeys) ||
			!Objects.equals(this.originalPriceIndexes, priceIndexKeys) ||
			!Objects.equals(this.originalFacetIndexes, facetIndexReferencedEntities)
		) {
			trappedChanges.addChangeToStore(
				createStoragePart(
					hierarchyIndexEmpty, attributeIndexStorageKeys, priceIndexKeys, facetIndexReferencedEntities
				)
			);
		}
		ofNullable(this.hierarchyIndex.createStoragePart(this.primaryKey))
			.ifPresent(trappedChanges::addChangeToStore);
		this.attributeIndex.getModifiedStorageParts(this.primaryKey, trappedChanges);
		this.facetIndex.getModifiedStorageParts(this.primaryKey, trappedChanges);
	}

	@Override
	public void resetDirty() {
		this.dirty.reset();
		this.hierarchyIndex.resetDirty();
		this.attributeIndex.resetDirty();
		this.facetIndex.resetDirty();
	}

	/**
	 * Removes the transactional memory layers of various referenced producers associated with the given transactional
	 * layer. This method is used when index is removed to clear all orphaned transactional memory layers.
	 *
	 * @param transactionalLayer the instance of TransactionalLayerMaintainer whose layers are to be removed from the referenced producers
	 */
	public void removeTransactionalMemoryOfReferencedProducers(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		this.dirty.removeLayer(transactionalLayer);
		this.entityIds.removeLayer(transactionalLayer);
		this.entityIdsByLanguage.removeLayer(transactionalLayer);
		this.attributeIndex.removeLayer(transactionalLayer);
		this.hierarchyIndex.removeLayer(transactionalLayer);
		this.facetIndex.removeLayer(transactionalLayer);
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
	 */
	protected StoragePart createStoragePart(
		boolean hierarchyIndexEmpty,
		@Nonnull Set<AttributeIndexStorageKey> attributeIndexStorageKeys,
		@Nonnull Set<PriceIndexKey> priceIndexKeys,
		@Nonnull Set<String> facetIndexReferencedEntities
	) {
		return new EntityIndexStoragePart(
			this.primaryKey, this.version, this.indexKey,
			this.entityIds, this.entityIdsByLanguage,
			attributeIndexStorageKeys,
			priceIndexKeys,
			!hierarchyIndexEmpty,
			facetIndexReferencedEntities
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
	 * Method returns the set of attribute index storage keys.
	 *
	 * @return the set of attribute index storage keys
	 */
	@Nonnull
	private Set<AttributeIndexStorageKey> getAttributeIndexStorageKeys() {
		return getAttributeIndexStorageKeyStream()
			.collect(Collectors.toSet());
	}

	/**
	 * Method returns a stream of AttributeIndexStorageKey objects.
	 * The stream includes AttributeIndexStorageKeys of different types (UNIQUE, FILTER, SORT, CHAIN)
	 * created from attribute indexes of the attributeIndex object.
	 *
	 * The method can be overriden by descendants to provide a different stream of AttributeIndexStorageKey objects.
	 *
	 * @return a stream of AttributeIndexStorageKey objects.
	 */
	@Nonnull
	protected Stream<AttributeIndexStorageKey> getAttributeIndexStorageKeyStream() {
		return Stream.of(
				this.attributeIndex.getUniqueIndexes().stream().map(it -> new AttributeIndexStorageKey(this.indexKey, AttributeIndexType.UNIQUE, it)),
				this.attributeIndex.getFilterIndexes().stream().map(it -> new AttributeIndexStorageKey(this.indexKey, AttributeIndexType.FILTER, it)),
				this.attributeIndex.getSortIndexes().stream().map(it -> new AttributeIndexStorageKey(this.indexKey, AttributeIndexType.SORT, it)),
				this.attributeIndex.getChainIndexes().stream().map(it -> new AttributeIndexStorageKey(this.indexKey, AttributeIndexType.CHAIN, it))
			)
			.flatMap(it -> it);
	}

}
