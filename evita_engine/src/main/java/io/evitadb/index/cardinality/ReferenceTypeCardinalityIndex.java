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

package io.evitadb.index.cardinality;

import io.evitadb.core.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.spi.model.storageParts.index.ReferenceTypeCardinalityIndexStoragePart;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NumberUtils;
import lombok.Getter;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Map;
import java.util.Objects;

import static java.util.Optional.ofNullable;

/**
 * This index is used solely in {@link ReferencedTypeEntityIndex} for storing cardinality index of referenced entity
 * primary keys and also cardinality of {@link ReducedEntityIndex} primary keys. It also provides information about
 * set of index primary keys for each referenced entity primary key that are present in the index.
 *
 * The index allows adding and removing keys, and retrieving the cardinalities of all keys.
 *
 * The index allows us to track the number of occurrences of a key in indexes that allow multiple occurrences of
 * the record in the index. In order to correctly remove the key from the index, we need to know how many times
 * the key is present in the index and remove it only when the last occurrence is evicted. This is where the cardinality
 * index comes in.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ReferenceTypeCardinalityIndex
	implements VoidTransactionMemoryProducer<ReferenceTypeCardinalityIndex>, IndexDataStructure, Serializable {
	@Serial private static final long serialVersionUID = -7416602590381722682L;
	/**
	 * This is internal flag that tracks whether the index contents became dirty and needs to be persisted.
	 */
	@Nonnull private final TransactionalBoolean dirty;
	/**
	 * A variable that holds the cardinalities of different entities.
	 *
	 * The TransactionalMap is a map-like data structure that allows concurrent access and modification
	 * of the cardinalities in a transactional manner. Each cardinality is associated with a AttributeCardinalityKey,
	 * which uniquely identifies the entity for which the cardinality is being stored.
	 */
	private final TransactionalMap<Long, Integer> cardinalities;
	/**
	 * Index that for each referenced entity primary key keeps the bitmap of all reduced entity index primary keys that
	 * contains entity primary keys referencing this entity.
	 */
	@Nonnull @Getter private final TransactionalMap<Integer, TransactionalBitmap> referencedPrimaryKeysIndex;
	/**
	 * Helper bitmap that contains all referenced entity primary keys that are present in keys of
	 * {@link #referencedPrimaryKeysIndex}.
	 */
	private RoaringBitmap memoizedAllReferencedPrimaryKeys;

	public ReferenceTypeCardinalityIndex() {
		this.dirty = new TransactionalBoolean();
		this.cardinalities = new TransactionalMap<>(CollectionUtils.createHashMap(16));
		this.referencedPrimaryKeysIndex = new TransactionalMap<>(
			CollectionUtils.createHashMap(16), TransactionalBitmap.class, TransactionalBitmap::new);
	}

	public ReferenceTypeCardinalityIndex(
		@Nonnull Map<Long, Integer> cardinalities,
		@Nonnull Map<Integer, TransactionalBitmap> referencedPrimaryKeys
	) {
		this.dirty = new TransactionalBoolean();
		this.cardinalities = new TransactionalMap<>(cardinalities);
		this.referencedPrimaryKeysIndex = new TransactionalMap<>(
			referencedPrimaryKeys, TransactionalBitmap.class, TransactionalBitmap::new);
	}

	/**
	 * Returns cardinalities of all keys in the index.
	 *
	 * @return cardinalities of all keys in the index
	 */
	@Nonnull
	public Map<Long, Integer> getCardinalities() {
		return this.cardinalities;
	}

	/**
	 * Increases cardinality of given value by one. If value is not present in the index, it is added with cardinality 1
	 * and TRUE is returned, otherwise existing cardinality is increased by one FALSE is returned.
	 *
	 * @param indexPrimaryKey            primary key of the entity index that tracks relation between the record and the referenced entity
	 * @param referencedEntityPrimaryKey primary key of the referenced entity
	 * @return TRUE if value was not present in the index, FALSE otherwise
	 */
	public boolean addRecord(int indexPrimaryKey, int referencedEntityPrimaryKey) {
		Assert.isPremiseValid(
			indexPrimaryKey != 0,
			"Index primary key must not be zero!"
		);

		boolean added = addCardinality(NumberUtils.join(indexPrimaryKey, 0));
		if (addCardinality(-1L * NumberUtils.join(indexPrimaryKey, referencedEntityPrimaryKey))) {
			TransactionalBitmap indexIdBitmap = this.referencedPrimaryKeysIndex.get(referencedEntityPrimaryKey);
			if (indexIdBitmap == null) {
				indexIdBitmap = new TransactionalBitmap();
				this.referencedPrimaryKeysIndex.put(referencedEntityPrimaryKey, indexIdBitmap);
			}
			indexIdBitmap.add(indexPrimaryKey);
		}

		this.dirty.setToTrue();
		return added;
	}

	/**
	 * Decreases cardinality of given value by one. If the cardinality of the value reaches zero, the value is removed from
	 * the index and TRUE is returned, otherwise FALSE is returned.
	 *
	 * @param indexPrimaryKey            primary key of the entity index that tracks relation between the record and the referenced entity
	 * @param referencedEntityPrimaryKey primary key of the referenced entity
	 * @return TRUE if value was removed from the index, FALSE otherwise
	 */
	public boolean removeRecord(int indexPrimaryKey, int referencedEntityPrimaryKey) {
		Assert.isPremiseValid(
			indexPrimaryKey != 0,
			"Index primary key must not be zero!"
		);

		final boolean removed = removeCardinality(NumberUtils.join(indexPrimaryKey, 0));
		if (removeCardinality(-1L * NumberUtils.join(indexPrimaryKey, referencedEntityPrimaryKey))) {
			final TransactionalBitmap indexIdBitmap = this.referencedPrimaryKeysIndex.get(referencedEntityPrimaryKey);
			Assert.isPremiseValid(
				indexIdBitmap != null,
				() -> new GenericEvitaInternalError(
					"Referenced entity primary key " + referencedEntityPrimaryKey + " is unexpectedly not found in the index!")
			);
			// remove the index primary key from the bitmap
			indexIdBitmap.remove(indexPrimaryKey);
		}
		this.dirty.setToTrue();
		return removed;
	}

	/**
	 * Returns TRUE if this contains no data.
	 *
	 * @return TRUE if this contains no data
	 */
	public boolean isEmpty() {
		return this.cardinalities.isEmpty();
	}

	/**
	 * Retrieves all reference indexes associated with the given referenced entity primary key.
	 *
	 * @param referencedEntityPrimaryKey the primary key of the referenced entity for which the indexes are to be retrieved
	 * @return an array of all reference indexes primary keys associated with the specified referenced entity primary key
	 */
	public int[] getAllReferenceIndexes(int referencedEntityPrimaryKey) {
		return ofNullable(this.referencedPrimaryKeysIndex.get(referencedEntityPrimaryKey))
			.map(TransactionalBitmap::getArray)
			.orElse(ArrayUtils.EMPTY_INT_ARRAY);
	}

	/**
	 * Constructs a Formula representing the intersection of the primary keys managed by this index
	 * and the referenced entity primary keys provided as input.
	 *
	 * @param referencedEntityPrimaryKeys an array of referenced entity primary keys to be intersected with
	 *                                    the primary keys managed by this index
	 * @return a Formula representing the intersection of the primary keys; returns an empty formula if
	 *         the input array is empty
	 */
	@Nonnull
	public Bitmap getIndexPrimaryKeys(@Nonnull RoaringBitmap referencedEntityPrimaryKeys) {
		if (referencedEntityPrimaryKeys.isEmpty()) {
			return EmptyBitmap.INSTANCE;
		} else {
			final RoaringBitmap allReferencedPrimaryKeys;
			if (Transaction.isTransactionAvailable()) {
				final RoaringBitmapWriter<RoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
				for (Integer referencedEntityId : this.referencedPrimaryKeysIndex.keySet()) {
					writer.add(referencedEntityId);
				}
				allReferencedPrimaryKeys = writer.get();
			} else {
				if (this.memoizedAllReferencedPrimaryKeys == null) {
					final RoaringBitmapWriter<RoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
					for (Integer referencedEntityId : this.referencedPrimaryKeysIndex.keySet()) {
						writer.add(referencedEntityId);
					}
					this.memoizedAllReferencedPrimaryKeys = writer.get();
				}
				allReferencedPrimaryKeys = this.memoizedAllReferencedPrimaryKeys;
			}
			final RoaringBitmap matchingReferencedEntityPks = RoaringBitmap.and(
				allReferencedPrimaryKeys,
				referencedEntityPrimaryKeys
			);
			final RoaringBitmapWriter<RoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
			for (Integer matchingReferencedEntityPk : matchingReferencedEntityPks) {
				final TransactionalBitmap indexIds = Objects.requireNonNull(
					this.referencedPrimaryKeysIndex.get(matchingReferencedEntityPk)
				);
				indexIds.forEach(writer::add);
			}
			return new BaseBitmap(writer.get());
		}
	}

	/**
	 * Method creates container for storing chain index from memory to the persistent storage.
	 */
	@Nullable
	public StoragePart createStoragePart(int entityIndexPrimaryKey, @Nonnull String referenceName) {
		if (this.dirty.isTrue()) {
			return new ReferenceTypeCardinalityIndexStoragePart(
				entityIndexPrimaryKey, referenceName, this
			);
		} else {
			return null;
		}
	}

	@Override
	public void resetDirty() {
		this.dirty.reset();
	}

	/*
		TransactionalLayerProducer implementation
	 */

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		this.cardinalities.removeLayer(transactionalLayer);
		this.referencedPrimaryKeysIndex.removeLayer(transactionalLayer);
		this.dirty.removeLayer(transactionalLayer);
	}

	/*
		TransactionalLayerCreator implementation
	 */

	@Nonnull
	@Override
	public ReferenceTypeCardinalityIndex createCopyWithMergedTransactionalMemory(
		@Nullable Void layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		// we can safely throw away dirty flag now
		final Boolean isDirty = transactionalLayer.getStateCopyWithCommittedChanges(this.dirty);
		if (isDirty) {
			return new ReferenceTypeCardinalityIndex(
				transactionalLayer.getStateCopyWithCommittedChanges(this.cardinalities),
				transactionalLayer.getStateCopyWithCommittedChanges(this.referencedPrimaryKeysIndex)
			);
		} else {
			return this;
		}
	}

	/**
	 * Increases the cardinality of the given primary key by one. If the primary key is not present
	 * in the index, it is added with a cardinality of 1 and the method returns true.
	 * Otherwise, the existing cardinality is increased by one, and the method returns false.
	 *
	 * @param composedKey the primary key of the entity index for which the cardinality is to be updated
	 * @return true if the primary key was not already present in the index, false otherwise
	 */
	private boolean addCardinality(long composedKey) {
		return this.cardinalities.compute(
			composedKey,
			(k, v) -> v == null ? 1 : v + 1
		) == 1;
	}

	/**
	 * Decreases the cardinality associated with the given primary key by one.
	 * If the cardinality reaches zero, the key is removed from the index, and the method returns true.
	 * If the key does not exist in the index, an exception is thrown. Otherwise, the method returns false.
	 *
	 * @param composedKey the primary key whose cardinality is to be updated
	 * @return true if the key was removed from the index, false otherwise
	 * @throws GenericEvitaInternalError if the cardinality of the given key is null
	 */
	private boolean removeCardinality(long composedKey) {
		final Integer newValue = this.cardinalities.computeIfPresent(
			composedKey,
			(k, v) -> v - 1
		);
		if (newValue == null) {
			throw new GenericEvitaInternalError(
				"Cardinality of index PK `" + composedKey + "` is null"
			);
		} else if (newValue == 0) {
			this.cardinalities.remove(composedKey);
			return true;
		} else {
			return false;
		}
	}

}
