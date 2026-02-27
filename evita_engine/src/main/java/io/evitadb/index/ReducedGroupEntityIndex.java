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
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.TransactionalContainerChanges;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.dataType.Scope;
import io.evitadb.index.attribute.AttributeIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.cardinality.AttributeCardinalityIndex;
import io.evitadb.index.facet.FacetIndex;
import io.evitadb.index.hierarchy.HierarchyIndex;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.price.PriceRefIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GroupCardinalityIndexStoragePart;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.evitadb.core.transaction.Transaction.isTransactionAvailable;
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
 * `Map&lt;Integer, Integer&gt;` cardinality tracking for primary keys.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ReducedGroupEntityIndex extends AbstractReducedEntityIndex {

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
	 * A helper object that acts as a {@link TransactionalLayerProducer} for tracking cardinality index
	 * lifecycle changes (creations and removals) during a transaction. This is needed because newly created
	 * {@link AttributeCardinalityIndex} objects need their transactional layers properly cleaned up during
	 * commit or rollback.
	 */
	@Nonnull private final CardinalityChangeTracker changeTracker;

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
		this.changeTracker = new CardinalityChangeTracker();
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
		@Nonnull Map<AttributeIndexKey, AttributeCardinalityIndex> cardinalityIndexes
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
		this.changeTracker = new CardinalityChangeTracker();
	}

	@Override
	public void resetDirty() {
		super.resetDirty();
		this.cardinalityDirty.reset();
	}

	@Override
	public boolean isEmpty() {
		return super.isEmpty() && this.pkCardinalities.isEmpty();
	}

	@Nonnull
	@Override
	public ReducedGroupEntityIndex createCopyForNewCatalogAttachment(@Nonnull CatalogState catalogState) {
		return new ReducedGroupEntityIndex(
			this.primaryKey, this.indexKey, this.version,
			this.entityIds, this.entityIdsByLanguage,
			this.attributeIndex,
			getPriceIndex().createCopyForNewCatalogAttachment(catalogState),
			this.hierarchyIndex,
			this.facetIndex,
			this.pkCardinalities,
			this.referencedPrimaryKeysIndex,
			this.cardinalityIndexes
		);
	}

	@Nonnull
	@Override
	protected Stream<AttributeIndexStorageKey> getAttributeIndexStorageKeyStream() {
		return Stream.concat(
			super.getAttributeIndexStorageKeyStream(),
			ofNullable(this.cardinalityIndexes)
				.map(TransactionalMap::keySet)
				.stream()
				.flatMap(
					set -> set.stream()
						.map(
							attributeKey -> new AttributeIndexStorageKey(
								this.indexKey, AttributeIndexType.CARDINALITY, attributeKey
							)
						)
				)
		);
	}

	@Override
	public void getModifiedStorageParts(@Nonnull TrappedChanges trappedChanges) {
		super.getModifiedStorageParts(trappedChanges);

		if (this.cardinalityDirty.isTrue()) {
			trappedChanges.addChangeToStore(
				new GroupCardinalityIndexStoragePart(
					this.primaryKey,
					getRepresentativeReferenceKey().referenceName(),
					this.pkCardinalities,
					this.referencedPrimaryKeysIndex
				)
			);
		}

		// add all modified cardinality indexes
		for (Entry<AttributeIndexKey, AttributeCardinalityIndex> entry : this.cardinalityIndexes.entrySet()) {
			ofNullable(entry.getValue().createStoragePart(this.primaryKey, entry.getKey()))
				.ifPresent(trappedChanges::addChangeToStore);
		}
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
	 * @return always true (for API compatibility with {@link ReferencedTypeEntityIndex})
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
		}
		return true;
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
	 * @return always true (for API compatibility with {@link ReferencedTypeEntityIndex})
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
			if (transactionalLayer != null) {
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
		}
		return true;
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
			lookupKey -> {
				final AttributeCardinalityIndex newCardinalityIndex = new AttributeCardinalityIndex(
					attributeSchema.getPlainType()
				);
				ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this.changeTracker))
					.ifPresent(it -> it.addCreatedItem(newCardinalityIndex));
				return newCardinalityIndex;
			}
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
			ofNullable(Transaction.getOrCreateTransactionalMemoryLayer(this.changeTracker))
				.ifPresent(it -> it.addRemovedItem(removedIndex));
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

	@Override
	public void removeTransactionalMemoryOfReferencedProducers(
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		super.removeTransactionalMemoryOfReferencedProducers(transactionalLayer);
		this.cardinalityDirty.removeLayer(transactionalLayer);
		this.pkCardinalities.removeLayer(transactionalLayer);
		this.referencedPrimaryKeysIndex.removeLayer(transactionalLayer);
		this.cardinalityIndexes.removeLayer(transactionalLayer);
		this.changeTracker.removeLayer(transactionalLayer);
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
		final ReducedGroupEntityIndex result = new ReducedGroupEntityIndex(
			this.primaryKey, this.indexKey, this.version + (wasDirty ? 1 : 0),
			transactionalLayer.getStateCopyWithCommittedChanges(this.entityIds),
			transactionalLayer.getStateCopyWithCommittedChanges(this.entityIdsByLanguage),
			transactionalLayer.getStateCopyWithCommittedChanges(this.attributeIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(getPriceIndex()),
			transactionalLayer.getStateCopyWithCommittedChanges(this.hierarchyIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.facetIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.pkCardinalities),
			transactionalLayer.getStateCopyWithCommittedChanges(this.referencedPrimaryKeysIndex),
			transactionalLayer.getStateCopyWithCommittedChanges(this.cardinalityIndexes)
		);

		// clean up cardinality change tracking
		final ReducedGroupEntityIndexChanges changes =
			transactionalLayer.removeTransactionalMemoryLayerIfExists(this.changeTracker);
		ofNullable(changes).ifPresent(it -> it.clean(transactionalLayer));

		return result;
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		super.removeLayer(transactionalLayer);
		this.cardinalityDirty.removeLayer(transactionalLayer);
		this.pkCardinalities.removeLayer(transactionalLayer);
		this.referencedPrimaryKeysIndex.removeLayer(transactionalLayer);
		this.cardinalityIndexes.removeLayer(transactionalLayer);
		this.changeTracker.removeLayer(transactionalLayer);
	}

	@Override
	public String toString() {
		return "ReducedGroupEntityIndex (" + StringUtils.uncapitalize(getIndexKey().toString()) + ")";
	}

	/**
	 * This class collects changes to {@link #cardinalityIndexes} during a transaction, tracking newly created
	 * and removed {@link AttributeCardinalityIndex} instances so their transactional layers can be properly
	 * cleaned up on commit or rollback.
	 */
	public static class ReducedGroupEntityIndexChanges {

		/**
		 * Container that tracks created and removed cardinality indexes during a transaction.
		 */
		private final TransactionalContainerChanges<Void, AttributeCardinalityIndex, AttributeCardinalityIndex>
			cardinalityIndexChanges = new TransactionalContainerChanges<>();

		/**
		 * Registers a newly created cardinality index for cleanup tracking.
		 *
		 * @param cardinalityIndex the cardinality index to track
		 */
		public void addCreatedItem(@Nonnull AttributeCardinalityIndex cardinalityIndex) {
			this.cardinalityIndexChanges.addCreatedItem(cardinalityIndex);
		}

		/**
		 * Registers a removed cardinality index for cleanup tracking.
		 *
		 * @param cardinalityIndex the cardinality index to track
		 */
		public void addRemovedItem(@Nonnull AttributeCardinalityIndex cardinalityIndex) {
			this.cardinalityIndexChanges.addRemovedItem(cardinalityIndex);
		}

		/**
		 * Cleans up transactional layers of tracked cardinality indexes during commit.
		 *
		 * @param transactionalLayer the transactional layer maintainer
		 */
		public void clean(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			this.cardinalityIndexChanges.clean(transactionalLayer);
		}

		/**
		 * Cleans up all transactional layers of tracked cardinality indexes during rollback.
		 *
		 * @param transactionalLayer the transactional layer maintainer
		 */
		public void cleanAll(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			this.cardinalityIndexChanges.cleanAll(transactionalLayer);
		}
	}

	/**
	 * A helper object that acts as a {@link TransactionalLayerProducer} for the cardinality change tracking.
	 * Since {@link ReducedGroupEntityIndex} extends {@link AbstractReducedEntityIndex} which already implements
	 * {@link io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer}, we cannot change the layer
	 * type. This helper provides a separate transactional layer for tracking
	 * {@link AttributeCardinalityIndex} lifecycle.
	 */
	private static class CardinalityChangeTracker
		implements TransactionalLayerProducer<ReducedGroupEntityIndexChanges, CardinalityChangeTracker> {

		private final long id = TransactionalObjectVersion.SEQUENCE.nextId();

		@Override
		public long getId() {
			return this.id;
		}

		@Nullable
		@Override
		public ReducedGroupEntityIndexChanges createLayer() {
			return isTransactionAvailable() ? new ReducedGroupEntityIndexChanges() : null;
		}

		@Nonnull
		@Override
		public CardinalityChangeTracker createCopyWithMergedTransactionalMemory(
			@Nullable ReducedGroupEntityIndexChanges layer,
			@Nonnull TransactionalLayerMaintainer transactionalLayer
		) {
			// stateless - just create a new tracker
			return new CardinalityChangeTracker();
		}

		@Override
		public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			final ReducedGroupEntityIndexChanges changes =
				transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
			ofNullable(changes).ifPresent(it -> it.cleanAll(transactionalLayer));
		}
	}
}
