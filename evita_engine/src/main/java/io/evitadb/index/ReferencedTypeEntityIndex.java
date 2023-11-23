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

import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.Transaction;
import io.evitadb.index.attribute.AttributeIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.facet.FacetIndex;
import io.evitadb.index.hierarchy.HierarchyIndex;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.price.PriceIndexContract;
import io.evitadb.index.price.VoidPriceIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.transactionalMemory.TransactionalLayerMaintainer;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.spi.model.storageParts.accessor.EntityStoragePartAccessor;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.store.spi.model.storageParts.index.EntityIndexStoragePart;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

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
public class ReferencedTypeEntityIndex extends EntityIndex<ReferencedTypeEntityIndex> {
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
	private final TransactionalMap<Integer, Integer> primaryKeyCardinality;

	public ReferencedTypeEntityIndex(
		int primaryKey,
		@Nonnull EntityIndexKey entityIndexKey,
		@Nonnull Supplier<EntitySchema> schemaAccessor
	) {
		super(primaryKey, entityIndexKey, schemaAccessor);
		this.primaryKeyCardinality = new TransactionalMap<>(new HashMap<>());
	}

	public ReferencedTypeEntityIndex(
		int primaryKey,
		@Nonnull EntityIndexKey entityIndexKey,
		int version,
		@Nonnull Supplier<EntitySchema> schemaAccessor,
		@Nonnull Bitmap entityIds,
		@Nonnull Map<Locale, TransactionalBitmap> entityIdsByLanguage,
		@Nonnull AttributeIndex attributeIndex,
		@Nonnull HierarchyIndex hierarchyIndex,
		@Nonnull FacetIndex facetIndex,
		@Nonnull Map<Integer, Integer> primaryKeyCardinality
	) {
		super(
			primaryKey, entityIndexKey, version, schemaAccessor,
			entityIds, entityIdsByLanguage,
			attributeIndex, hierarchyIndex, facetIndex, VoidPriceIndex.INSTANCE
		);
		this.primaryKeyCardinality = new TransactionalMap<>(primaryKeyCardinality);
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
			this.primaryKeyCardinality.isEmpty();
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

	/*
		TRANSACTIONAL MEMORY IMPLEMENTATION
	 */

	@Nonnull
	@Override
	public ReferencedTypeEntityIndex createCopyWithMergedTransactionalMemory(@Nullable Void layer, @Nonnull TransactionalLayerMaintainer transactionalLayer, @Nullable Transaction transaction) {
		// we can safely throw away dirty flag now
		final Boolean wasDirty = transactionalLayer.getStateCopyWithCommittedChanges(this.dirty, transaction);
		return new ReferencedTypeEntityIndex(
			primaryKey, indexKey, version + (wasDirty ? 1 : 0), schemaAccessor,
			transactionalLayer.getStateCopyWithCommittedChanges(this.entityIds, transaction),
			transactionalLayer.getStateCopyWithCommittedChanges(this.entityIdsByLanguage, transaction),
			transactionalLayer.getStateCopyWithCommittedChanges(this.attributeIndex, transaction),
			transactionalLayer.getStateCopyWithCommittedChanges(this.hierarchyIndex, transaction),
			transactionalLayer.getStateCopyWithCommittedChanges(this.facetIndex, transaction),
			transactionalLayer.getStateCopyWithCommittedChanges(this.primaryKeyCardinality, transaction)
		);
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		super.removeTransactionalMemoryOfReferencedProducers(transactionalLayer);
		this.primaryKeyCardinality.removeLayer(transactionalLayer);
	}

	/**
	 * @see #primaryKeyCardinality
	 */
	@Override
	public boolean insertPrimaryKeyIfMissing(int entityPrimaryKey, @Nonnull EntityStoragePartAccessor entityStoragePartAccessor) {
		throw new UnsupportedOperationException(
			"ReferencedTypeEntityIndex doesn't support this operation, you need to call method: `insertPrimaryKeyIfMissing(int, int, WritableEntityStorageContainerAccessor)`"
		);
	}

	/**
	 * @see #primaryKeyCardinality
	 */
	@Override
	public boolean removePrimaryKey(int entityPrimaryKey, @Nonnull EntityStoragePartAccessor entityStoragePartAccessor) {
		throw new UnsupportedOperationException(
			"ReferencedTypeEntityIndex doesn't support this operation, you need to call method: `removePrimaryKey(int, int, WritableEntityStorageContainerAccessor)`"
		);
	}

	/**
	 * This method delegates call to {@link #insertPrimaryKeyIfMissing(int, EntityStoragePartAccessor)} but tracks
	 * the cardinality of the referenced primary key in {@link #primaryKeyCardinality}.
	 *
	 * @see #primaryKeyCardinality
	 */
	public void insertPrimaryKeyIfMissing(int entityPrimaryKey, int referencedEntityPrimaryKey, @Nonnull EntityStoragePartAccessor containerAccessor) {
		final boolean added = super.insertPrimaryKeyIfMissing(referencedEntityPrimaryKey, containerAccessor);
		if (!added) {
			this.primaryKeyCardinality.compute(
				entityPrimaryKey,
				(key, oldValue) -> {
					if (oldValue == null) {
						return 1;
					} else {
						return oldValue + 1;
					}
				}
			);
			this.dirty.setToTrue();
		}
	}

	/**
	 * This method delegates call to {@link #removePrimaryKey(int, EntityStoragePartAccessor)} but tracks the cardinality
	 * of the referenced primary key in {@link #primaryKeyCardinality} and removes the referenced primary key from
	 * {@link #entityIds} only when the cardinality reaches 0.
	 *
	 * @see #primaryKeyCardinality
	 */
	public void removePrimaryKey(int entityPrimaryKey, int referencedEntityPrimaryKey, @Nonnull EntityStoragePartAccessor containerAccessor) {
		final int result = this.primaryKeyCardinality.compute(
			entityPrimaryKey,
			(key, oldValue) -> {
				if (oldValue == null) {
					return -1;
				} else {
					return oldValue - 1;
				}
			}
		);
		if (result == -1) {
			super.removePrimaryKey(referencedEntityPrimaryKey, containerAccessor);
		} else {
			if (result == 0) {
				this.primaryKeyCardinality.remove(entityPrimaryKey);
			}
			this.dirty.setToTrue();
		}
	}
}
