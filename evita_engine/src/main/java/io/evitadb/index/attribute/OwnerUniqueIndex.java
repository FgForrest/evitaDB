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

package io.evitadb.index.attribute;

import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.transaction.memory.TransactionalContainerChanges;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.map.MapChanges;
import io.evitadb.index.map.PersistentTransactionalMap;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.UniqueIndexStoragePart;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static io.evitadb.core.transaction.Transaction.isTransactionAvailable;
import static io.evitadb.utils.Assert.isTrue;
import static java.util.Optional.ofNullable;

/**
 * Owner variant of {@link UniqueIndex}. It OWNS its value→record-id mappings and the record-id bitmap, and fully
 * participates in the commit cycle. Used for global-unique-localized attributes whose locale-less uniqueness cannot be
 * folded into the per-locale shared filter tree.
 *
 * The value to record id relation is kept in a {@link PersistentTransactionalMap} (backed by a persistent immutable
 * {@link io.evitadb.dataType.champ.ChampMap}), so look-ups run in `O(log₃₂ N)` and commits derive the next snapshot
 * by path-copying only the mutated keys instead of rebuilding the whole map.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class OwnerUniqueIndex extends UniqueIndex {
	@Serial private static final long serialVersionUID = 2639205026498958517L;
	/**
	 * Internal flag that tracks whether the index contents became dirty and need to be persisted.
	 */
	@Nonnull private final TransactionalBoolean dirty;
	/**
	 * Keeps the unique value to record id mappings. A fairly large map is expected here, so it is backed by a
	 * persistent immutable {@link io.evitadb.dataType.champ.ChampMap} via {@link PersistentTransactionalMap}: the
	 * values are plain {@link Integer} record ids (no nested transactional state), so commit derives the next
	 * snapshot in `O(Δ·log N)` instead of rebuilding the whole map. Ordering is irrelevant here — record ordering
	 * is carried by {@link #recordIds}.
	 */
	@Nonnull private final PersistentTransactionalMap<Serializable, Integer> uniqueValueToRecordId;
	/**
	 * Keeps information about all record ids present in this index.
	 */
	@Nonnull private final TransactionalBitmap recordIds;
	/**
	 * This field speeds up all requests for all data in this index (which happens quite often). This formula can be
	 * computed anytime by calling `new ConstantFormula(getRecordIds())`. Original operation
	 * needs to perform costly creation of new internal bitmap that's why we memoize the result.
	 */
	@Nullable private transient Formula memoizedAllRecordsFormula;

	/**
	 * Creates an empty index for a freshly encountered attribute - the entry point when an entity introduces a
	 * unique attribute that has not been indexed yet.
	 *
	 * @param entityType        type of the entity this index belongs to
	 * @param attributeIndexKey key identifying the indexed attribute
	 * @param attributeType     declared type of the attribute value
	 */
	public OwnerUniqueIndex(@Nonnull String entityType, @Nonnull AttributeIndexKey attributeIndexKey, @Nonnull Class<? extends Serializable> attributeType) {
		super(entityType, attributeIndexKey, attributeType);
		this.dirty = new TransactionalBoolean();
		this.uniqueValueToRecordId = new PersistentTransactionalMap<>(new HashMap<>());
		this.recordIds = new TransactionalBitmap();
	}

	/**
	 * Reconstructs the index from a persisted value to record id map - the path taken when loading an index back
	 * from storage. The {@link #recordIds} bitmap is rebuilt from the map values, so no separate bitmap needs to be
	 * persisted alongside the map.
	 *
	 * @param entityType            type of the entity this index belongs to
	 * @param attributeIndexKey     key identifying the indexed attribute
	 * @param attributeType         declared type of the attribute value
	 * @param uniqueValueToRecordId restored unique value to record id mappings
	 */
	public OwnerUniqueIndex(@Nonnull String entityType, @Nonnull AttributeIndexKey attributeIndexKey, @Nonnull Class<? extends Serializable> attributeType, @Nonnull Map<Serializable, Integer> uniqueValueToRecordId) {
		super(entityType, attributeIndexKey, attributeType);
		this.dirty = new TransactionalBoolean();
		this.uniqueValueToRecordId = new PersistentTransactionalMap<>(uniqueValueToRecordId);
		this.recordIds = new TransactionalBitmap(uniqueValueToRecordId.values().stream().mapToInt(it -> it).toArray());
	}

	/**
	 * Reconstructs the index from both the value to record id map and an already-built record id bitmap - the path
	 * taken when assembling a committed snapshot in {@link #createCopyWithMergedTransactionalMemory}, where the
	 * merged bitmap is supplied directly and need not be recomputed from the map values.
	 *
	 * @param entityType            type of the entity this index belongs to
	 * @param attributeIndexKey     key identifying the indexed attribute
	 * @param attributeType         declared type of the attribute value
	 * @param uniqueValueToRecordId unique value to record id mappings
	 * @param recordIds             bitmap of all record ids contained in the map above
	 */
	public OwnerUniqueIndex(@Nonnull String entityType, @Nonnull AttributeIndexKey attributeIndexKey, @Nonnull Class<? extends Serializable> attributeType, @Nonnull Map<Serializable, Integer> uniqueValueToRecordId, @Nonnull Bitmap recordIds) {
		super(entityType, attributeIndexKey, attributeType);
		this.dirty = new TransactionalBoolean();
		this.uniqueValueToRecordId = new PersistentTransactionalMap<>(uniqueValueToRecordId);
		this.recordIds = new TransactionalBitmap(recordIds);
	}

	@Override
	public void registerUniqueKey(@Nonnull Object value, int recordId) {
		registerUniqueKeyValue(value, recordId);
	}

	@Override
	public int unregisterUniqueKey(@Nonnull Object value, int recordId) {
		return unregisterUniqueKeyValue(value, recordId);
	}

	@Nullable
	@Override
	public Integer getRecordIdByUniqueValue(@Nonnull Serializable value) {
		return this.uniqueValueToRecordId.get(value);
	}

	@Override
	public Formula getRecordIdsFormula() {
		// if there is transaction open, there might be changes in the bitmap, and we can't easily use cache
		if (isTransactionAvailable() && this.dirty.isTrue()) {
			return new ConstantFormula(this.recordIds);
		} else {
			if (this.memoizedAllRecordsFormula == null) {
				this.memoizedAllRecordsFormula = new ConstantFormula(this.recordIds);
			}
			return this.memoizedAllRecordsFormula;
		}
	}

	@Nonnull
	@Override
	public Bitmap getRecordIds() {
		return this.recordIds;
	}

	@Override
	public int size() {
		return this.recordIds.size();
	}

	@Override
	public boolean isEmpty() {
		return this.uniqueValueToRecordId.isEmpty();
	}

	@Nullable
	@Override
	public StoragePart createStoragePart(int entityIndexPrimaryKey) {
		if (this.dirty.isTrue()) {
			return new UniqueIndexStoragePart(
				entityIndexPrimaryKey,
				getAttributeIndexKey(),
				getType(),
				this.uniqueValueToRecordId,
				this.recordIds
			);
		} else {
			return null;
		}
	}

	@Override
	public void resetDirty() {
		this.dirty.reset();
	}

	@Nullable
	@Override
	public TransactionalContainerChanges<MapChanges<Serializable, Integer>, Map<Serializable, Integer>, PersistentTransactionalMap<Serializable, Integer>> createLayer() {
		return isTransactionAvailable() ? new TransactionalContainerChanges<>() : null;
	}

	/**
	 * Folds the transactional diff layer onto the shared state and returns the resulting snapshot. As an
	 * optimization, when this index was not touched during the transaction (its {@link #dirty} flag is false) the
	 * receiver is returned unchanged, avoiding any allocation; otherwise a fresh {@link OwnerUniqueIndex} carrying the
	 * committed map and bitmap is built.
	 */
	@Nonnull
	@Override
	public UniqueIndex createCopyWithMergedTransactionalMemory(
		@Nullable TransactionalContainerChanges<
			MapChanges<Serializable, Integer>,
			Map<Serializable, Integer>,
			PersistentTransactionalMap<Serializable, Integer>
			> layer,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		final boolean isDirty = transactionalLayer
			.getStateCopyWithCommittedChanges(this.dirty);
		if (isDirty) {
			final OwnerUniqueIndex uniqueKeyIndex = new OwnerUniqueIndex(
				getEntityType(), getAttributeIndexKey(), getType(),
				transactionalLayer.getStateCopyWithCommittedChanges(this.uniqueValueToRecordId),
				transactionalLayer.getStateCopyWithCommittedChanges(this.recordIds)
			);
			// we can safely throw away dirty flag now
			ofNullable(layer).ifPresent(it -> it.clean(transactionalLayer));
			return uniqueKeyIndex;
		} else {
			return this;
		}
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		this.uniqueValueToRecordId.removeLayer(transactionalLayer);
		this.recordIds.removeLayer(transactionalLayer);
		this.dirty.removeLayer(transactionalLayer);
	}

	@Nonnull
	@Override
	UniqueIndex bindFilterView(@Nullable FilterIndex committedFilterView) {
		// an owner index owns its own value→record map and reads nothing from a shared filter view — the bind target is
		// irrelevant, so it carries forward unchanged (mirrors OwnerSortIndex.bindSharedTree)
		return this;
	}

	@Nonnull
	@Override
	Map<Serializable, Integer> getUniqueValueToRecordId() {
		return Collections.unmodifiableMap(this.uniqueValueToRecordId);
	}

	/*
		PRIVATE METHODS
	 */

	/**
	 * Array-dispatching entry point for registration. When `key` is an array (an array-typed attribute), every
	 * element is first checked for a conflicting owner and only then registered, so a violation on any element
	 * aborts the whole operation before mutating the index. Scalar keys are delegated straight to the single-value
	 * overload. Finally invalidates the memoized records formula (outside transactions) and marks the index dirty.
	 *
	 * @param key      single unique value or an array of unique values to register
	 * @param recordId record id that should own the value(s)
	 * @throws UniqueValueViolationException when any value is already owned by a different record
	 */
	@SuppressWarnings("unchecked")
	private <T extends Serializable & Comparable<T>> void registerUniqueKeyValue(@Nonnull Object key, int recordId) {
		if (key instanceof @Nonnull final Object[] valueArray) {
			verifyValueArray(key);
			// first verify removed data without modifications
			for (Object valueItem : valueArray) {
				final T theValueItem = (T) valueItem;
				final Integer existingRecordId = this.uniqueValueToRecordId.get(theValueItem);
				assertUniqueKeyIsFree(theValueItem, recordId, existingRecordId);
			}
			// now perform alteration
			for (Object valueItem : valueArray) {
				//noinspection unchecked
				registerUniqueKeyValue((T) valueItem, recordId);
			}
		} else {
			verifyValue(key);
			//noinspection unchecked
			registerUniqueKeyValue((T) key, recordId);
		}

		if (!isTransactionAvailable()) {
			this.memoizedAllRecordsFormula = null;
		}

		this.dirty.setToTrue();
	}

	/**
	 * Registers a single unique value to a record id after asserting the value is free, then adds the record id to
	 * the {@link #recordIds} bitmap.
	 *
	 * @param key      unique value to register
	 * @param recordId record id that should own the value
	 * @throws UniqueValueViolationException when the value is already owned by a different record
	 */
	private <T extends Serializable & Comparable<T>> void registerUniqueKeyValue(@Nonnull T key, int recordId) {
		final Integer existingRecordId = this.uniqueValueToRecordId.get(key);
		assertUniqueKeyIsFree(key, recordId, existingRecordId);
		this.uniqueValueToRecordId.put(key, recordId);
		this.recordIds.add(recordId);
	}

	/**
	 * Array-dispatching entry point for de-registration. When `key` is an array, every element's ownership is
	 * first verified and only then removed, so a mismatch on any element aborts the operation before mutating the
	 * index; the array branch returns {@link Integer#MIN_VALUE} as a sentinel since no single record id applies.
	 * Scalar keys are delegated to the single-value overload and return the removed record id. Finally invalidates
	 * the memoized records formula (outside transactions) and marks the index dirty.
	 *
	 * @param key              single unique value or an array of unique values to unregister
	 * @param expectedRecordId record id expected to currently own the value(s)
	 * @return the removed record id for a scalar key, or {@link Integer#MIN_VALUE} for the array branch
	 * @throws io.evitadb.exception.EvitaInvalidUsageException when any value is absent or owned by a different record
	 */
	@SuppressWarnings("unchecked")
	private <T extends Serializable & Comparable<T>> int unregisterUniqueKeyValue(@Nonnull Object key, int expectedRecordId) {
		final int returnValue;
		if (key instanceof @Nonnull final Object[] valueArray) {
			verifyValueArray(key);
			// first verify removed data without modifications
			for (Object valueItem : valueArray) {
				final T theValueItem = (T) valueItem;
				final Integer existingRecordId = this.uniqueValueToRecordId.get(theValueItem);
				assertUniqueKeyOwnership(theValueItem, expectedRecordId, existingRecordId);
			}
			// now perform alteration
			for (Object valueItem : valueArray) {
				unregisterUniqueKeyValue((T) valueItem, expectedRecordId);
			}

			returnValue = Integer.MIN_VALUE;
		} else {
			verifyValue(key);
			returnValue = unregisterUniqueKeyValue((T) key, expectedRecordId);
		}

		if (!isTransactionAvailable()) {
			this.memoizedAllRecordsFormula = null;
		}

		this.dirty.setToTrue();
		return returnValue;
	}

	/**
	 * Removes a single unique value, asserting it was owned by `expectedRecordId`, and drops that record id from
	 * the {@link #recordIds} bitmap. The ownership assertion guarantees the removed mapping was non-null and equal
	 * to `expectedRecordId`, so beyond it the boxed `existingRecordId` and the primitive `expectedRecordId` are
	 * interchangeable; the primitive is used to avoid unboxing the (provably non-null) {@link Integer}.
	 *
	 * @param key              unique value to unregister
	 * @param expectedRecordId record id expected to currently own the value
	 * @return the removed record id (always equal to `expectedRecordId`)
	 * @throws io.evitadb.exception.EvitaInvalidUsageException when the value is absent or owned by a different record
	 */
	private <T extends Serializable & Comparable<T>> int unregisterUniqueKeyValue(@Nonnull T key, int expectedRecordId) {
		final Integer existingRecordId = this.uniqueValueToRecordId.remove(key);
		// this throws unless existingRecordId is non-null AND equals expectedRecordId, so past this point the two
		// are interchangeable; using the primitive expectedRecordId avoids unboxing the (provably non-null) Integer
		assertUniqueKeyOwnership(key, expectedRecordId, existingRecordId);
		this.recordIds.remove(expectedRecordId);
		return expectedRecordId;
	}

	/**
	 * Enforces the registration invariant: a value may be claimed only when it is currently unowned or already
	 * owned by the same record. An idempotent re-registration by the same record is therefore allowed.
	 *
	 * @param key              value being registered (used for the violation message)
	 * @param recordId         record id attempting to claim the value
	 * @param existingRecordId record id currently owning the value, or `null` if the value is free
	 * @throws UniqueValueViolationException when the value is already owned by a different record
	 */
	private <T extends Serializable & Comparable<T>> void assertUniqueKeyIsFree(@Nonnull T key, int recordId, @Nullable Integer existingRecordId) {
		if (!(existingRecordId == null || existingRecordId.equals(recordId))) {
			throw new UniqueValueViolationException(getAttributeIndexKey().attributeName(), getAttributeIndexKey().locale(), key, getEntityType(), existingRecordId, getEntityType(), recordId);
		}
	}

	/**
	 * Enforces the de-registration invariant: the value must currently exist and be owned by exactly
	 * `expectedRecordId`. The failure message distinguishes a missing key (`existingRecordId` is `null`) from a key
	 * owned by a different record.
	 *
	 * @param key              value being unregistered (used for the failure message)
	 * @param expectedRecordId record id expected to own the value
	 * @param existingRecordId record id actually found, or `null` if the value was absent
	 * @throws io.evitadb.exception.EvitaInvalidUsageException when the value is absent or owned by a different record
	 */
	private <T extends Serializable & Comparable<T>> void assertUniqueKeyOwnership(@Nonnull T key, int expectedRecordId, @Nullable Integer existingRecordId) {
		isTrue(
			Objects.equals(existingRecordId, expectedRecordId),
			() -> existingRecordId == null ?
				"No unique key exists for `" + getAttributeIndexKey().attributeName() + "` key: `" + key + "`" + (getAttributeIndexKey().locale() == null ? "" : " in locale `" + getAttributeIndexKey().locale().toLanguageTag() + "`") + "!" :
				"Unique key exists for `" + getAttributeIndexKey().attributeName() + "` key: `" + key + "`" + (getAttributeIndexKey().locale() == null ? "" : " in locale `" + getAttributeIndexKey().locale().toLanguageTag() + "`") + " belongs to record with id `" + existingRecordId + "` and not `" + expectedRecordId + "` as expected!"
		);
	}

}
