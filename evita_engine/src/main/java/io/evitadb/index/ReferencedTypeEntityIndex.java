/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.core.Transaction;
import io.evitadb.core.transaction.memory.TransactionalContainerChanges;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.index.ReferencedTypeEntityIndex.ReferencedTypeEntityIndexChanges;
import io.evitadb.index.attribute.AttributeIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.cardinality.CardinalityIndex;
import io.evitadb.index.facet.FacetIndex;
import io.evitadb.index.hierarchy.HierarchyIndex;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.price.PriceIndexContract;
import io.evitadb.index.price.VoidPriceIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.store.spi.model.storageParts.index.EntityIndexStoragePart;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.evitadb.core.Transaction.isTransactionAvailable;
import static io.evitadb.index.attribute.AttributeIndex.createAttributeKey;
import static java.util.Optional.ofNullable;

/**
 * Referenced type entity index exists once per {@link EntitySchemaContract#getReference(String)} and indexes not
 * the owner entity primary key, but the referenced entity primary key with attributes that lay on the reference
 * relation. We need this index to be able to navigate to {@link ReducedEntityIndex} that were specially created to
 * speed up queries that involve the references.
 *
 * This indes doesn't maintain the prices of entities - only the attributes present on relations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ReferencedTypeEntityIndex extends EntityIndex implements
	TransactionalLayerProducer<ReferencedTypeEntityIndexChanges, ReferencedTypeEntityIndex>,
	IndexDataStructure,
	Serializable {
	/**
	 * No prices are maintained in this index.
	 */
	@Delegate(types = PriceIndexContract.class)
	private final PriceIndexContract priceIndex = VoidPriceIndex.INSTANCE;
	/**
	 * This index keeps information about cardinality of referenced primary keys for each owner entity primary key.
	 * The referenced primary keys are indexed into {@link #entityIds} but they may be added to this index multiple times.
	 * In order to know when they could be removed from {@link #entityIds} we need to know how many times they were added
	 * and this is being tracked in this data structure.
	 *
	 * In order to optimize storage we keep only cardinalities that are greater than 1. The cardinality = 1 can be
	 * determined by the presence of the referenced primary key in {@link #entityIds}.
	 */
	@Nonnull
	private final CardinalityIndex primaryKeyCardinality;
	/**
	 * This transactional map (index) contains for each attribute single instance of {@link FilterIndex}
	 * (respective single instance for each attribute-locale combination in case of language specific attribute).
	 */
	@Nonnull private final TransactionalMap<AttributeKey, CardinalityIndex> cardinalityIndexes;

	public ReferencedTypeEntityIndex(
		int primaryKey,
		@Nonnull String entityType,
		@Nonnull EntityIndexKey entityIndexKey
	) {
		super(primaryKey, entityType, entityIndexKey);
		this.primaryKeyCardinality = new CardinalityIndex(Integer.class);
		this.cardinalityIndexes = new TransactionalMap<>(new HashMap<>(), CardinalityIndex.class, Function.identity());
	}

	public ReferencedTypeEntityIndex(
		int primaryKey,
		@Nonnull EntityIndexKey entityIndexKey,
		int version,
		@Nonnull Bitmap entityIds,
		@Nonnull Map<Locale, TransactionalBitmap> entityIdsByLanguage,
		@Nonnull AttributeIndex attributeIndex,
		@Nonnull HierarchyIndex hierarchyIndex,
		@Nonnull FacetIndex facetIndex,
		@Nonnull CardinalityIndex primaryKeyCardinality,
		@Nonnull Map<AttributeKey, CardinalityIndex> cardinalityIndexes
	) {
		super(
			primaryKey, entityIndexKey, version,
			entityIds, entityIdsByLanguage,
			attributeIndex, hierarchyIndex, facetIndex, VoidPriceIndex.INSTANCE
		);
		this.primaryKeyCardinality = primaryKeyCardinality;
		this.cardinalityIndexes = new TransactionalMap<>(cardinalityIndexes, CardinalityIndex.class, Function.identity());
	}

	@Nonnull
	@Override
	public <S extends PriceIndexContract> S getPriceIndex() {
		//noinspection unchecked
		return (S) priceIndex;
	}

	@Override
	public void resetDirty() {
		super.resetDirty();
	}

	@Override
	public boolean isEmpty() {
		return super.isEmpty() &&
			this.primaryKeyCardinality.isEmpty() &&
			this.cardinalityIndexes.isEmpty();
	}

	@Override
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
			facetIndexReferencedEntities,
			primaryKeyCardinality
		);
	}

	@Nonnull
	@Override
	protected Stream<AttributeIndexStorageKey> getAttributeIndexStorageKeyStream() {
		return Stream.concat(
			super.getAttributeIndexStorageKeyStream(),
			ofNullable(cardinalityIndexes)
				.map(TransactionalMap::keySet)
				.map(set -> set.stream().map(attributeKey -> new AttributeIndexStorageKey(indexKey, AttributeIndexType.CARDINALITY, attributeKey)))
				.orElseGet(Stream::empty)
		);
	}

	@Nonnull
	@Override
	public Collection<StoragePart> getModifiedStorageParts() {
		final Collection<StoragePart> dirtyParts = super.getModifiedStorageParts();
		for (Entry<AttributeKey, CardinalityIndex> entry : cardinalityIndexes.entrySet()) {
			ofNullable(entry.getValue().createStoragePart(primaryKey, entry.getKey()))
				.ifPresent(dirtyParts::add);
		}
		return dirtyParts;
	}

	/**
	 * This method delegates call to {@link super#insertPrimaryKeyIfMissing(int)}
	 * but tracks the cardinality of the referenced primary key in {@link #primaryKeyCardinality}.
	 *
	 * @see #primaryKeyCardinality
	 */
	@Override
	public boolean insertPrimaryKeyIfMissing(int entityPrimaryKey) {
		this.dirty.setToTrue();
		if (this.primaryKeyCardinality.addRecord(entityPrimaryKey, entityPrimaryKey)) {
			return super.insertPrimaryKeyIfMissing(entityPrimaryKey);
		}
		return false;
	}

	/**
	 * This method delegates call to {@link super#removePrimaryKey(int)} but tracks
	 * the cardinality of the referenced primary key in {@link #primaryKeyCardinality} and removes the referenced
	 * primary key from {@link #entityIds} only when the cardinality reaches 0.
	 *
	 * @see #primaryKeyCardinality
	 */
	@Override
	public boolean removePrimaryKey(int entityPrimaryKey) {
		this.dirty.setToTrue();
		if (this.primaryKeyCardinality.removeRecord(entityPrimaryKey, entityPrimaryKey)) {
			return super.removePrimaryKey(entityPrimaryKey);
		}
		return false;
	}

	/**
	 * This method delegates call to {@link #insertFilterAttribute(AttributeSchemaContract, Set, Locale, Object, int)}
	 * but tracks the cardinality of the referenced primary key in {@link #cardinalityIndexes}.
	 * @param attributeSchema
	 * @param allowedLocales
	 * @param locale
	 * @param value
	 * @param recordId
	 */
	@Override
	public void insertFilterAttribute(
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Object value,
		int recordId
	) {
		// first retrieve or create the cardinality index for given attribute
		final CardinalityIndex theCardinalityIndex = this.cardinalityIndexes.computeIfAbsent(
			createAttributeKey(attributeSchema, allowedLocales, locale, value),
			lookupKey -> {
				final CardinalityIndex newCardinalityIndex = new CardinalityIndex(attributeSchema.getPlainType());
				ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
					.ifPresent(it -> it.addCreatedItem(newCardinalityIndex));
				return newCardinalityIndex;
			}
		);
		if (value instanceof Object[] valueArray) {
			// for array values we need to add only new items to the index (their former cardinality was zero)
			final Object[] onlyNewItemsValueArray = (Object[]) Array.newInstance(valueArray.getClass().getComponentType(), valueArray.length);
			int onlyNewItemsValueArrayIndex = 0;
			for (Object valueItem : valueArray) {
				if (theCardinalityIndex.addRecord((Serializable) valueItem, recordId)) {
					onlyNewItemsValueArray[onlyNewItemsValueArrayIndex++] = valueItem;
				}
			}
			if (onlyNewItemsValueArrayIndex > 0) {
				final Object[] delta = Arrays.copyOfRange(onlyNewItemsValueArray, 0, onlyNewItemsValueArrayIndex);
				super.addDeltaFilterAttribute(
					attributeSchema, allowedLocales, locale,
					delta,
					recordId
				);
			}
		} else {
			// for non-array values we need to call super method only if cardinality was zero
			if (theCardinalityIndex.addRecord((Serializable) value, recordId)) {
				super.insertFilterAttribute(attributeSchema, allowedLocales, locale, value, recordId);
			}
		}
	}

	@Override
	public void removeFilterAttribute(
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Object value,
		int recordId
	) {
		// first retrieve or create the cardinality index for given attribute
		final CardinalityIndex theCardinalityIndex = this.cardinalityIndexes.computeIfAbsent(
			createAttributeKey(attributeSchema, allowedLocales, locale, value),
			lookupKey -> {
				final CardinalityIndex newCardinalityIndex = new CardinalityIndex(attributeSchema.getPlainType());
				ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this))
					.ifPresent(it -> it.addCreatedItem(newCardinalityIndex));
				return newCardinalityIndex;
			}
		);
		if (value instanceof Object[] valueArray) {
			// for array values we need to remove only items which cardinality reaches zero
			final Object[] onlyRemovedItemsValueArray = (Object[]) Array.newInstance(valueArray.getClass().getComponentType(), valueArray.length);
			int onlyRemovedItemsValueArrayIndex = 0;
			for (Object valueItem : valueArray) {
				if (theCardinalityIndex.removeRecord((Serializable) valueItem, recordId)) {
					onlyRemovedItemsValueArray[onlyRemovedItemsValueArrayIndex++] = valueItem;
				}
			}
			if (onlyRemovedItemsValueArrayIndex > 0) {
				final Object[] delta = Arrays.copyOfRange(onlyRemovedItemsValueArray, 0, onlyRemovedItemsValueArrayIndex);
				super.removeDeltaFilterAttribute(
					attributeSchema, allowedLocales, locale,
					delta,
					recordId
				);
			}
		} else {
			// for non-array values we need to call super method only if cardinality reaches zero
			if (theCardinalityIndex.removeRecord((Serializable) value, recordId)) {
				super.insertFilterAttribute(attributeSchema, allowedLocales, locale, value, recordId);
			}
		}
	}

	@Override
	public void insertSortAttribute(AttributeSchemaContract attributeSchema, Set<Locale> allowedLocales, Locale locale, Object value, int recordId) {
		// the sort index of reference type index is not maintained, because the entity might reference multiple
		// entities and the sort index couldn't handle multiple values
	}

	@Override
	public void removeSortAttribute(AttributeSchemaContract attributeSchema, Set<Locale> allowedLocales, Locale locale, Object value, int recordId) {
		// the sort index of reference type index is not maintained, because the entity might reference multiple
		// entities and the sort index couldn't handle multiple values
	}

	@Override
	public void insertSortAttributeCompound(SortableAttributeCompoundSchemaContract compoundSchemaContract, Function<String, Class<?>> attributeTypeProvider, Locale locale, Object[] value, int recordId) {
		// the sort index of reference type index is not maintained, because the entity might reference multiple
		// entities and the sort index couldn't handle multiple values
	}

	@Override
	public void removeSortAttributeCompound(SortableAttributeCompoundSchemaContract compoundSchemaContract, Locale locale, Object[] value, int recordId) {
		// the sort index of reference type index is not maintained, because the entity might reference multiple
		// entities and the sort index couldn't handle multiple values
	}

	/*
		TransactionalLayerCreator implementation
	 */

	@Nullable
	@Override
	public ReferencedTypeEntityIndexChanges createLayer() {
		return isTransactionAvailable() ? new ReferencedTypeEntityIndexChanges() : null;
	}

	@Nonnull
	@Override
	public ReferencedTypeEntityIndex createCopyWithMergedTransactionalMemory(ReferencedTypeEntityIndexChanges layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		// we can safely throw away dirty flag now
		final Boolean wasDirty = transactionalLayer.getStateCopyWithCommittedChanges(this.dirty);
		final ReferencedTypeEntityIndex referencedTypeEntityIndex = new ReferencedTypeEntityIndex(
			this.primaryKey, this.indexKey, this.version + (wasDirty ? 1 : 0),
			transactionalLayer.getStateCopyWithCommittedChanges(this.entityIds),
			transactionalLayer.getStateCopyWithCommittedChanges(this.entityIdsByLanguage),
			transactionalLayer.getStateCopyWithCommittedChanges(this.attributeIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.hierarchyIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.facetIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.primaryKeyCardinality),
			transactionalLayer.getStateCopyWithCommittedChanges(this.cardinalityIndexes)
		);

		ofNullable(layer).ifPresent(it -> it.clean(transactionalLayer));
		return referencedTypeEntityIndex;
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		super.removeTransactionalMemoryOfReferencedProducers(transactionalLayer);
		this.primaryKeyCardinality.removeLayer(transactionalLayer);
		this.cardinalityIndexes.removeLayer(transactionalLayer);

		final ReferencedTypeEntityIndexChanges changes = transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		ofNullable(changes).ifPresent(it -> it.cleanAll(transactionalLayer));
	}

	/**
	 * This class collects changes in {@link #cardinalityIndexes} transactional maps.
	 */
	public static class ReferencedTypeEntityIndexChanges {
		private final TransactionalContainerChanges<Void, CardinalityIndex, CardinalityIndex> cardinalityIndexChanges = new TransactionalContainerChanges<>();

		public void addCreatedItem(@Nonnull CardinalityIndex cardinalityIndex) {
			cardinalityIndexChanges.addCreatedItem(cardinalityIndex);
		}

		public void addRemovedItem(@Nonnull CardinalityIndex cardinalityIndex) {
			cardinalityIndexChanges.addRemovedItem(cardinalityIndex);
		}

		public void clean(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			cardinalityIndexChanges.clean(transactionalLayer);
		}

		public void cleanAll(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			cardinalityIndexChanges.cleanAll(transactionalLayer);
		}

	}

}
