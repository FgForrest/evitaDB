/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

package io.evitadb.index;

import io.evitadb.api.requestResponse.data.Versioned;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.EntityCollection;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.locale.LocaleFormula;
import io.evitadb.index.attribute.AttributeIndex;
import io.evitadb.index.attribute.AttributeIndexContract;
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
import io.evitadb.index.price.PriceSuperIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.transactionalMemory.TransactionalLayerMaintainer;
import io.evitadb.index.transactionalMemory.TransactionalObjectVersion;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.store.spi.model.storageParts.index.EntityIndexStoragePart;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
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
public abstract class EntityIndex implements Index<EntityIndexKey>, PriceIndexContract, Versioned, IndexDataStructure {
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();

	/**
	 * This part of index collects information about filterable/unique/sortable attributes of the entities. It provides
	 * data that are necessary for constructing {@link Formula} tree for the constraints
	 * related to the attributes.
	 */
	@Delegate(types = AttributeIndexContract.class)
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
	@Getter protected final int version;
	/**
	 * Lambda that provides access to the current schema.
	 * Beware this reference changes with each entity collection exchange during transactional commit.
	 */
	protected Supplier<EntitySchema> schemaAccessor;
	/**
	 * This field captures the original state of the hierarchy index when this index was created.
	 * This information is used along with {@link #dirty} flag to determine whether {@link EntityIndexStoragePart}
	 * should be persisted.
	 */
	private final boolean originalHierarchyIndexEmpty;
	/**
	 * This field captures the original state of the price id sequence when this index was created.
	 * This information is used along with {@link #dirty} flag to determine whether {@link EntityIndexStoragePart}
	 * should be persisted.
	 */
	private final Integer originalInternalPriceIdSequence;
	/**
	 * This field captures the original state of the attribute index when this index was created.
	 * This information is used along with {@link #dirty} flag to determine whether {@link EntityIndexStoragePart}
	 * should be persisted.
	 */
	private final Set<AttributeIndexStorageKey> originalAttributeIndexes;
	/**
	 * This field captures the original state of the price indexes when this index was created.
	 * This information is used along with {@link #dirty} flag to determine whether {@link EntityIndexStoragePart}
	 * should be persisted.
	 */
	private final Set<PriceIndexKey> originalPriceIndexes;
	/**
	 * This field captures the original state of the facet indexes when this index was created.
	 * This information is used along with {@link #dirty} flag to determine whether {@link EntityIndexStoragePart}
	 * should be persisted.
	 */
	private final Set<String> originalFacetIndexes;

	protected EntityIndex(
		int primaryKey,
		@Nonnull EntityIndexKey indexKey,
		@Nonnull Supplier<EntitySchema> schemaAccessor
	) {
		this.primaryKey = primaryKey;
		this.version = 1;
		this.dirty = new TransactionalBoolean();
		this.indexKey = indexKey;
		this.schemaAccessor = schemaAccessor;
		this.entityIds = new TransactionalBitmap();
		this.entityIdsByLanguage = new TransactionalMap<>(new HashMap<>());
		this.attributeIndex = new AttributeIndex(schemaAccessor.get().getName());
		this.hierarchyIndex = new HierarchyIndex();
		this.facetIndex = new FacetIndex();
		this.originalHierarchyIndexEmpty = true;
		this.originalInternalPriceIdSequence = null;
		this.originalAttributeIndexes = Collections.emptySet();
		this.originalPriceIndexes = Collections.emptySet();
		this.originalFacetIndexes = Collections.emptySet();
	}

	protected EntityIndex(
		int primaryKey,
		@Nonnull EntityIndexKey indexKey,
		int version,
		@Nonnull Supplier<EntitySchema> schemaAccessor,
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
		this.schemaAccessor = schemaAccessor;
		this.dirty = new TransactionalBoolean();
		this.entityIds = new TransactionalBitmap(entityIds);

		final Map<Locale, TransactionalBitmap> txEntityIdsByLanguage = createHashMap(entityIdsByLanguage.size());
		for (Entry<Locale, TransactionalBitmap> entry : entityIdsByLanguage.entrySet()) {
			txEntityIdsByLanguage.put(entry.getKey(), new TransactionalBitmap(entry.getValue()));
		}
		this.entityIdsByLanguage = new TransactionalMap<>(txEntityIdsByLanguage);
		this.attributeIndex = attributeIndex;
		this.hierarchyIndex = hierarchyIndex;
		this.facetIndex = facetIndex;
		this.originalHierarchyIndexEmpty = this.hierarchyIndex.isHierarchyIndexEmpty();
		this.originalInternalPriceIdSequence = getInternalPriceIdSequence(priceIndex);
		this.originalAttributeIndexes = getAttributeIndexStorageKeys();
		this.originalPriceIndexes = getPriceIndexKeys(priceIndex);
		this.originalFacetIndexes = getFacetIndexReferencedEntities();
	}

	/**
	 * Registers new entity primary key to the superset of entity ids of this entity index.
	 */
	public boolean insertPrimaryKeyIfMissing(int entityPrimaryKey) {
		final boolean added = entityIds.add(entityPrimaryKey);
		if (added) {
			this.dirty.setToTrue();
		}
		return added;
	}

	/**
	 * Removes existing from the superset of entity ids of this entity index.
	 */
	public boolean removePrimaryKey(int entityPrimaryKey) {
		final boolean removed = entityIds.remove(entityPrimaryKey);
		if (removed) {
			this.dirty.setToTrue();
		}
		return removed;
	}

	/**
	 * Returns true if the `entityPrimaryKey` is known in the index.
	 */
	public boolean isPrimaryKeyKnown(int entityPrimaryKey) {
		return entityIds.contains(entityPrimaryKey);
	}

	/**
	 * Returns superset of all entity ids known to this entity index.
	 */
	public Formula getAllPrimaryKeysFormula() {
		return entityIds.isEmpty() ? EmptyFormula.INSTANCE : new ConstantFormula(entityIds);
	}

	/**
	 * Returns superset of all entity ids known to this entity index.
	 */
	public Bitmap getAllPrimaryKeys() {
		return entityIds;
	}

	/**
	 * Replaces reference to the schema accessor lambda in new collection. This needs to be done when transaction is
	 * committed and new EntityIndex is created with link to the original transactional EntityIndex but finally
	 * new {@link EntityCollection} is created and the new indexes linking old collection needs to be
	 * migrated to new entity collection.
	 */
	public void updateReferencesTo(@Nonnull EntityCollection newCollection) {
		this.schemaAccessor = newCollection::getInternalSchema;
	}

	/**
	 * Provides access to the entity schema via passed lambda.
	 */
	public EntitySchema getEntitySchema() {
		return schemaAccessor.get();
	}

	/**
	 * Inserts information that entity with `recordId` has localized attribute / associated data of passed `locale`.
	 * If such information is already present no changes are made.
	 */
	public void upsertLanguage(@Nonnull Locale locale, int recordId) {
		final EntitySchema schema = getEntitySchema();
		final Set<Locale> allowedLocales = schema.getLocales();
		isTrue(
			allowedLocales.contains(locale) || schema.getEvolutionMode().contains(EvolutionMode.ADDING_LOCALES),
			"Locale " + locale + " is not allowed by the schema!"
		);

		final boolean added = this.entityIdsByLanguage
			.computeIfAbsent(locale, loc -> new TransactionalBitmap())
			.add(recordId);

		if (added) {
			this.dirty.setToTrue();
		}
	}

	/**
	 * Removed information that entity with `recordId` has no longer any localized attribute / associated data of passed `language`.
	 */
	public void removeLanguage(@Nonnull Locale locale, int recordId) {
		final TransactionalBitmap recordIdsWithLanguage = this.entityIdsByLanguage.get(locale);
		Assert.isTrue(
			recordIdsWithLanguage != null && recordIdsWithLanguage.remove(recordId),
			"Entity `" + recordId + "` has unexpectedly not indexed localized data for language `" + locale + "`!"
		);
		if (recordIdsWithLanguage.isEmpty()) {
			this.entityIdsByLanguage.remove(locale);
			this.dirty.setToTrue();
			// remove the changes container - the bitmap got removed entirely
			removeTransactionalMemoryLayerIfExists(recordIdsWithLanguage);
		}
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
		return entityIds.isEmpty() &&
			attributeIndex.isAttributeIndexEmpty() &&
			hierarchyIndex.isHierarchyIndexEmpty();
	}

	/**
	 * Method returns collection of all modified parts of this index that were modified and needs to be stored.
	 */
	@Nonnull
	public Collection<StoragePart> getModifiedStorageParts() {
		final List<StoragePart> dirtyList = new LinkedList<>();
		final PriceIndexContract priceIndex = getPriceIndex();
		final boolean hierarchyIndexEmpty = this.hierarchyIndex.isHierarchyIndexEmpty();
		final Set<AttributeIndexStorageKey> attributeIndexStorageKeys = getAttributeIndexStorageKeys();
		final Set<PriceIndexKey> priceIndexKeys = getPriceIndexKeys(priceIndex);
		final Set<String> facetIndexReferencedEntities = getFacetIndexReferencedEntities();
		final Integer internalPriceIdSequence = getInternalPriceIdSequence(priceIndex);
		if (dirty.isTrue() ||
			this.originalHierarchyIndexEmpty != hierarchyIndexEmpty ||
			!Objects.equals(this.originalInternalPriceIdSequence, internalPriceIdSequence) ||
			!Objects.equals(this.originalAttributeIndexes, attributeIndexStorageKeys) ||
			!Objects.equals(this.originalPriceIndexes, priceIndexKeys) ||
			!Objects.equals(this.originalFacetIndexes, facetIndexReferencedEntities)
		) {
			dirtyList.add(
				createStoragePart(
					hierarchyIndexEmpty, internalPriceIdSequence,
					attributeIndexStorageKeys, priceIndexKeys, facetIndexReferencedEntities
				)
			);
		}
		ofNullable(hierarchyIndex.createStoragePart(primaryKey))
			.ifPresent(dirtyList::add);
		dirtyList.addAll(attributeIndex.getModifiedStorageParts(primaryKey));
		dirtyList.addAll(facetIndex.getModifiedStorageParts(primaryKey));
		return dirtyList;
	}

	@Override
	public void resetDirty() {
		this.dirty.reset();
		this.hierarchyIndex.resetDirty();
		this.attributeIndex.resetDirty();
		this.facetIndex.resetDirty();
	}

	public void removeTransactionalMemoryOfReferencedProducers(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		this.dirty.removeLayer(transactionalLayer);
		this.entityIds.removeLayer(transactionalLayer);
		this.entityIdsByLanguage.removeLayer(transactionalLayer);
		this.attributeIndex.removeLayer(transactionalLayer);
		this.hierarchyIndex.removeLayer(transactionalLayer);
		this.facetIndex.removeLayer(transactionalLayer);
	}

	@Nonnull
	public abstract <S extends PriceIndexContract> S getPriceIndex();

	/**
	 * Method creates container that is possible to serialize and store into persistent storage.
	 */
	protected StoragePart createStoragePart(
		boolean hierarchyIndexEmpty,
		@Nullable Integer internalPriceIdSequence,
		@Nonnull Set<AttributeIndexStorageKey> attributeIndexStorageKeys,
		@Nonnull Set<PriceIndexKey> priceIndexKeys,
		@Nonnull Set<String> facetIndexReferencedEntities
	) {
		return new EntityIndexStoragePart(
			primaryKey, version, indexKey,
			entityIds, entityIdsByLanguage,
			attributeIndexStorageKeys,
			internalPriceIdSequence,
			priceIndexKeys,
			!hierarchyIndexEmpty,
			facetIndexReferencedEntities
		);
	}

	/*
		PRIVATE METHODS
	 */

	@Nonnull
	private Set<String> getFacetIndexReferencedEntities() {
		return facetIndex.getReferencedEntities();
	}

	@Nonnull
	private Set<PriceIndexKey> getPriceIndexKeys(@Nonnull PriceIndexContract priceIndex) {
		return priceIndex
			.getPriceListAndCurrencyIndexes()
			.stream()
			.map(PriceListAndCurrencyPriceIndex::getPriceIndexKey)
			.collect(Collectors.toSet());
	}

	@Nullable
	private Integer getInternalPriceIdSequence(@Nonnull PriceIndexContract priceIndex) {
		return priceIndex instanceof PriceSuperIndex ? ((PriceSuperIndex) priceIndex).getLastAssignedInternalPriceId() : null;
	}

	@Nonnull
	private Set<AttributeIndexStorageKey> getAttributeIndexStorageKeys() {
		return Stream.of(
				attributeIndex.getUniqueIndexes().stream().map(it -> new AttributeIndexStorageKey(indexKey, AttributeIndexType.UNIQUE, it)),
				attributeIndex.getFilterIndexes().stream().map(it -> new AttributeIndexStorageKey(indexKey, AttributeIndexType.FILTER, it)),
				attributeIndex.getSortIndexes().stream().map(it -> new AttributeIndexStorageKey(indexKey, AttributeIndexType.SORT, it))
			)
			.flatMap(it -> it)
			.collect(Collectors.toSet());
	}

}
