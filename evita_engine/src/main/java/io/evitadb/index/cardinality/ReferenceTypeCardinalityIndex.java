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

package io.evitadb.index.cardinality;

import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.AbstractReducedEntityIndex;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.map.PersistentTransactionalMap;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.result.CardinalityChange;
import io.evitadb.core.expression.trigger.DependencyType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ReferenceTypeCardinalityIndexStoragePart;
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
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static io.evitadb.core.transaction.Transaction.isTransactionAvailable;
import static java.util.Optional.ofNullable;

/**
 * This index is used solely in {@link ReferencedTypeEntityIndex} for storing cardinality index of referenced entity
 * primary keys and also cardinality of {@link AbstractReducedEntityIndex} primary keys. It also provides information about
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
	 * The {@link PersistentTransactionalMap} is a map-like data structure that allows concurrent access and
	 * modification of the cardinalities in a transactional manner. Each cardinality is associated with a composed
	 * long key, which uniquely identifies the entity for which the cardinality is being stored. The map is
	 * plain-valued and mutated exclusively through `compute`/`computeIfPresent`/`remove`, so commit folds only
	 * the changed keys onto the persistent snapshot in `O(Δ·log N)` instead of rebuilding the whole map on every
	 * transaction.
	 */
	private final PersistentTransactionalMap<Long, Integer> cardinalities;
	/**
	 * Index that for each referenced entity primary key keeps the bitmap of all reduced entity index primary keys that
	 * contains entity primary keys referencing this entity.
	 */
	@Nonnull @Getter private final TransactionalMap<Integer, TransactionalBitmap> referencedPrimaryKeysIndex;
	/**
	 * Helper bitmap that contains all referenced entity primary keys that are present in keys of
	 * {@link #referencedPrimaryKeysIndex}.
	 */
	@Nullable private volatile RoaringBitmap memoizedAllReferencedPrimaryKeys;

	public ReferenceTypeCardinalityIndex() {
		this.dirty = new TransactionalBoolean();
		this.cardinalities = new PersistentTransactionalMap<>(CollectionUtils.createHashMap(16));
		this.referencedPrimaryKeysIndex = new TransactionalMap<>(
			CollectionUtils.createHashMap(16), TransactionalBitmap.class, TransactionalBitmap::new);
	}

	public ReferenceTypeCardinalityIndex(
		@Nonnull Map<Long, Integer> cardinalities,
		@Nonnull Map<Integer, TransactionalBitmap> referencedPrimaryKeys
	) {
		this.dirty = new TransactionalBoolean();
		this.cardinalities = new PersistentTransactionalMap<>(cardinalities);
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
	 * Increases cardinality of the given (indexPrimaryKey, referencedEntityPrimaryKey) tuple by one.
	 * If the indexPrimaryKey was not yet tracked at all (cardinality 0 -> 1 for the whole index
	 * primary key), the method returns `BOUNDARY_CROSSED` so callers can propagate the new entry to
	 * membership-only downstream indexes. Otherwise the cardinality is incremented and
	 * `NO_BOUNDARY_CROSSING` is returned. The fine-grained bookkeeping of the referenced primary key
	 * bitmap is performed unconditionally.
	 *
	 * @param indexPrimaryKey            primary key of the entity index that tracks relation between
	 *                                   the record and the referenced entity
	 * @param referencedEntityPrimaryKey primary key of the referenced entity
	 * @return `BOUNDARY_CROSSED` if this call caused the index primary key to enter the index for
	 *         the first time, `NO_BOUNDARY_CROSSING` otherwise
	 */
	@Nonnull
	public CardinalityChange addRecord(int indexPrimaryKey, int referencedEntityPrimaryKey) {
		Assert.isPremiseValid(
			indexPrimaryKey != 0,
			"Index primary key must not be zero!"
		);

		final boolean added = addCardinality(NumberUtils.join(indexPrimaryKey, 0));
		if (addCardinality(-1L * NumberUtils.join(indexPrimaryKey, referencedEntityPrimaryKey))) {
			TransactionalBitmap indexIdBitmap = this.referencedPrimaryKeysIndex.get(referencedEntityPrimaryKey);
			if (indexIdBitmap == null) {
				indexIdBitmap = new TransactionalBitmap();
				this.referencedPrimaryKeysIndex.put(referencedEntityPrimaryKey, indexIdBitmap);
			}
			indexIdBitmap.add(indexPrimaryKey);
		}

		if (!isTransactionAvailable()) {
			this.memoizedAllReferencedPrimaryKeys = null;
		}
		this.dirty.setToTrue();
		return added ? CardinalityChange.BOUNDARY_CROSSED : CardinalityChange.NO_BOUNDARY_CROSSING;
	}

	/**
	 * Decreases cardinality of the given (indexPrimaryKey, referencedEntityPrimaryKey) tuple by one.
	 * If the cardinality of the indexPrimaryKey reaches zero overall, the tuple is removed from the
	 * index and `BOUNDARY_CROSSED` is returned so callers can propagate the removal to
	 * membership-only downstream indexes. Otherwise the cardinality is decremented and
	 * `NO_BOUNDARY_CROSSING` is returned.
	 *
	 * @param indexPrimaryKey            primary key of the entity index that tracks relation between
	 *                                   the record and the referenced entity
	 * @param referencedEntityPrimaryKey primary key of the referenced entity
	 * @return `BOUNDARY_CROSSED` if the index primary key fell out of the index entirely,
	 *         `NO_BOUNDARY_CROSSING` otherwise
	 */
	@Nonnull
	public CardinalityChange removeRecord(int indexPrimaryKey, int referencedEntityPrimaryKey) {
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
			// clean up empty bitmap to avoid memory leaks
			if (indexIdBitmap.isEmpty()) {
				final TransactionalBitmap removedBitmap = this.referencedPrimaryKeysIndex.remove(referencedEntityPrimaryKey);
				if (removedBitmap != null) {
					final TransactionalLayerMaintainer transactionalLayer = Transaction.getTransactionalLayerMaintainer();
					if (transactionalLayer != null) {
						removedBitmap.removeLayer(transactionalLayer);
					}
				}
			}
		}
		if (!isTransactionAvailable()) {
			this.memoizedAllReferencedPrimaryKeys = null;
		}
		this.dirty.setToTrue();
		return removed ? CardinalityChange.BOUNDARY_CROSSED : CardinalityChange.NO_BOUNDARY_CROSSING;
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
	 * Returns an unmodifiable view of all referenced entity primary keys tracked by this index. For a
	 * `REFERENCED_GROUP_ENTITY_TYPE` index these are the group entity PKs; for a `REFERENCED_ENTITY_TYPE`
	 * index these are the referenced (facet) entity PKs.
	 *
	 * Used by ReevaluateExpressionExecutor to iterate all groups when resolving group PKs for
	 * {@link DependencyType#REFERENCED_ENTITY_ATTRIBUTE} dependencies on grouped references.
	 *
	 * @return unmodifiable set of all tracked referenced entity primary keys
	 */
	@Nonnull
	public Set<Integer> getAllTrackedReferencedEntityPrimaryKeys() {
		return Collections.unmodifiableSet(this.referencedPrimaryKeysIndex.keySet());
	}

	/**
	 * Returns all tracked referenced entity primary keys as a {@link Bitmap}. Outside of a transactional
	 * context the underlying {@link RoaringBitmap} is memoized so repeated query-time calls (histogram
	 * boundary resolution iterates this set for every surviving histogram) do not rebuild it.
	 *
	 * **Read-only contract** — the returned bitmap aliases the memoized snapshot; callers must not
	 * mutate it. All production call sites (see
	 * {@code ReferenceHistogramAccumulator.collectGroupedPending} for iteration and
	 * {@code ReferenceHistogramAccumulator.pickBoundaryPk} for `RoaringBitmap.and` intersection) treat
	 * it as immutable. A defensive copy on every call would negate the memoization benefit.
	 *
	 * @return bitmap of referenced entity primary keys, may be {@link EmptyBitmap#INSTANCE}
	 */
	@Nonnull
	public Bitmap getAllTrackedReferencedEntityPrimaryKeysAsBitmap() {
		if (this.referencedPrimaryKeysIndex.isEmpty()) {
			return EmptyBitmap.INSTANCE;
		}
		if (Transaction.isTransactionAvailable()) {
			return new BaseBitmap(buildReferencedPrimaryKeysBitmap());
		}
		RoaringBitmap result = this.memoizedAllReferencedPrimaryKeys;
		if (result == null) {
			result = buildReferencedPrimaryKeysBitmap();
			this.memoizedAllReferencedPrimaryKeys = result;
		}
		return new BaseBitmap(result);
	}

	/**
	 * Builds a fresh {@link RoaringBitmap} snapshot from all keys currently present in
	 * {@link #referencedPrimaryKeysIndex}. Called either to populate {@link #memoizedAllReferencedPrimaryKeys}
	 * (outside a transaction) or to produce a one-shot bitmap within a transaction (where memoization is skipped
	 * because the index contents may change before the bitmap is consumed).
	 *
	 * @return a new {@link RoaringBitmap} containing all referenced entity primary keys tracked by this index
	 */
	@Nonnull
	private RoaringBitmap buildReferencedPrimaryKeysBitmap() {
		final RoaringBitmapWriter<RoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
		for (final Integer referencedEntityId : this.referencedPrimaryKeysIndex.keySet()) {
			writer.add(referencedEntityId);
		}
		return writer.get();
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
	 * Returns the set of referenced entity primary keys (i.e., the keys of the forward mapping) whose
	 * index primary key bitmaps have a non-empty intersection with the given set of index primary keys.
	 *
	 * This is the **reverse** of {@link #getIndexPrimaryKeys(RoaringBitmap)}: given a bitmap of
	 * reduced-index PKs, it identifies which referenced entity PKs are associated with them.
	 *
	 * @param indexPrimaryKeys bitmap of reduced-index primary keys to look up
	 * @return bitmap of referenced entity primary keys whose index PKs overlap with the input;
	 *         never {@code null}, may be {@link EmptyBitmap#INSTANCE}
	 */
	@Nonnull
	public Bitmap getReferencedPrimaryKeysForIndexPks(@Nonnull Bitmap indexPrimaryKeys) {
		if (indexPrimaryKeys.isEmpty()) {
			return EmptyBitmap.INSTANCE;
		}
		final RoaringBitmap indexPksBitmap = RoaringBitmapBackedBitmap.getRoaringBitmap(indexPrimaryKeys);
		final RoaringBitmapWriter<RoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
		for (Map.Entry<Integer, TransactionalBitmap> entry : this.referencedPrimaryKeysIndex.entrySet()) {
			if (
				RoaringBitmap.intersects(
					indexPksBitmap,
					RoaringBitmapBackedBitmap.getRoaringBitmap(entry.getValue())
				)
			) {
				writer.add(entry.getKey());
			}
		}
		final RoaringBitmap result = writer.get();
		return result.isEmpty() ? EmptyBitmap.INSTANCE : new BaseBitmap(result);
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
			RoaringBitmap allReferencedPrimaryKeys;
			if (Transaction.isTransactionAvailable()) {
				final RoaringBitmapWriter<RoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
				for (Integer referencedEntityId : this.referencedPrimaryKeysIndex.keySet()) {
					writer.add(referencedEntityId);
				}
				allReferencedPrimaryKeys = writer.get();
			} else {
				allReferencedPrimaryKeys = this.memoizedAllReferencedPrimaryKeys;
				if (allReferencedPrimaryKeys == null) {
					final RoaringBitmapWriter<RoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
					for (Integer referencedEntityId : this.referencedPrimaryKeysIndex.keySet()) {
						writer.add(referencedEntityId);
					}
					allReferencedPrimaryKeys = writer.get();
					this.memoizedAllReferencedPrimaryKeys = allReferencedPrimaryKeys;
				}
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
	public ReferenceTypeCardinalityIndexStoragePart createStoragePart(int entityIndexPrimaryKey, @Nonnull String referenceName) {
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
		final boolean isDirty = transactionalLayer.getStateCopyWithCommittedChanges(this.dirty);
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
