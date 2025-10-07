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

package io.evitadb.index.attribute;

import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.transaction.memory.TransactionalContainerChanges;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.map.MapChanges;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexKey;
import io.evitadb.store.spi.model.storageParts.index.UniqueIndexStoragePart;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static io.evitadb.core.Transaction.isTransactionAvailable;
import static io.evitadb.utils.Assert.isTrue;
import static io.evitadb.utils.StringUtils.unknownToString;
import static java.util.Optional.ofNullable;

/**
 * Unique index maintains information about single unique attribute - its value to record id relation.
 * It protects duplicate unique attribute insertion and allows to easily translate unique attribute value to record id
 * that occupies it.
 *
 * It uses simple {@link HashMap} data structure to keep the data. This means that look-ups are retrieved with O(1)
 * complexity.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
public class UniqueIndex implements TransactionalLayerProducer<TransactionalContainerChanges<MapChanges<Serializable, Integer>, Map<Serializable, Integer>, TransactionalMap<Serializable, Integer>>, UniqueIndex>, IndexDataStructure, Serializable {
	@Serial private static final long serialVersionUID = 2639205026498958516L;
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * Contains type of the entity this index belongs to.
	 */
	@Getter private final String entityType;
	/**
	 * Contains key identifying the attribute.
	 */
	@Getter private final AttributeIndexKey attributeIndexKey;
	/**
	 * Contains type of the attribute.
	 */
	@Getter private final Class<? extends Serializable> type;
	/**
	 * This is internal flag that tracks whether the index contents became dirty and needs to be persisted.
	 */
	@Nonnull private final TransactionalBoolean dirty;
	/**
	 * Keeps the unique value to record id mappings. Fairly large HashMap is expected here.
	 */
	@Nonnull private final TransactionalMap<Serializable, Integer> uniqueValueToRecordId;
	/**
	 * Keeps information about all record ids resent in this index.
	 */
	@Nonnull private final TransactionalBitmap recordIds;
	/**
	 * This field speeds up all requests for all data in this index (which happens quite often). This formula can be
	 * computed anytime by calling `new ConstantFormula(getRecordIds())`. Original operation
	 * needs to perform costly creation of new internal bitmap that's why we memoize the result.
	 */
	@Nullable private transient Formula memoizedAllRecordsFormula;

	static void verifyValueArray(@Nonnull Object value) {
		isTrue(Serializable.class.isAssignableFrom(value.getClass().getComponentType()), "Value `" + unknownToString(value) + "` is expected to be Serializable but it is not!");
		isTrue(Comparable.class.isAssignableFrom(value.getClass().getComponentType()), "Value `" + unknownToString(value) + "` is expected to be Comparable but it is not!");
	}

	static void verifyValue(@Nonnull Object value) {
		isTrue(value instanceof Serializable, "Value `" + unknownToString(value) + "` is expected to be Serializable but it is not!");
		isTrue(value instanceof Comparable, "Value `" + unknownToString(value) + "` is expected to be Comparable but it is not!");
	}

	public UniqueIndex(@Nonnull String entityType, @Nonnull AttributeIndexKey attributeIndexKey, @Nonnull Class<? extends Serializable> attributeType) {
		this.dirty = new TransactionalBoolean();
		this.entityType = entityType;
		this.attributeIndexKey = attributeIndexKey;
		this.type = attributeType;
		this.uniqueValueToRecordId = new TransactionalMap<>(new HashMap<>());
		this.recordIds = new TransactionalBitmap();
	}

	public UniqueIndex(@Nonnull String entityType, @Nonnull AttributeIndexKey attributeIndexKey, @Nonnull Class<? extends Serializable> attributeType, @Nonnull Map<Serializable, Integer> uniqueValueToRecordId) {
		this.dirty = new TransactionalBoolean();
		this.entityType = entityType;
		this.attributeIndexKey = attributeIndexKey;
		this.type = attributeType;
		this.uniqueValueToRecordId = new TransactionalMap<>(new HashMap<>(uniqueValueToRecordId));
		this.recordIds = new TransactionalBitmap(uniqueValueToRecordId.values().stream().mapToInt(it -> it).toArray());
	}

	public UniqueIndex(@Nonnull String entityType, @Nonnull AttributeIndexKey attributeIndexKey, @Nonnull Class<? extends Serializable> attributeType, @Nonnull Map<Serializable, Integer> uniqueValueToRecordId, @Nonnull Bitmap recordIds) {
		this.dirty = new TransactionalBoolean();
		this.entityType = entityType;
		this.attributeIndexKey = attributeIndexKey;
		this.type = attributeType;
		this.uniqueValueToRecordId = new TransactionalMap<>(uniqueValueToRecordId);
		this.recordIds = new TransactionalBitmap(recordIds);
	}

	/**
	 * Registers new record id to a single unique value.
	 *
	 * @throws UniqueValueViolationException when value is not unique
	 */
	public void registerUniqueKey(@Nonnull Object value, int recordId) {
		registerUniqueKeyValue(value, recordId);
	}

	/**
	 * Unregisters new record id from a single unique value.
	 *
	 * @return removed record id relation
	 */
	public int unregisterUniqueKey(@Nonnull Object value, int recordId) {
		return unregisterUniqueKeyValue(value, recordId);
	}

	/**
	 * Returns record id by its unique value.
	 */
	@Nullable
	public Integer getRecordIdByUniqueValue(@Nonnull Serializable value) {
		return this.uniqueValueToRecordId.get(value);
	}

	/**
	 * Returns formula that contains all records (and memoized result).
	 */
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

	/**
	 * Returns bitmap with all record ids registered in this unique index.
	 */
	@Nonnull
	public Bitmap getRecordIds() {
		return this.recordIds;
	}

	/**
	 * Returns number of records in this index.
	 */
	public int size() {
		return this.recordIds.size();
	}

	/**
	 * Returns true if index is empty.
	 */
	public boolean isEmpty() {
		return this.uniqueValueToRecordId.isEmpty();
	}

	/**
	 * Method creates container for storing unique index from memory to the persistent storage.
	 */
	@Nullable
	public StoragePart createStoragePart(int entityIndexPrimaryKey) {
		if (this.dirty.isTrue()) {
			return new UniqueIndexStoragePart(
				entityIndexPrimaryKey,
				this.attributeIndexKey,
				this.type,
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

	/*
		TransactionalLayerCreator implementation
	 */

	@Nullable
	@Override
	public TransactionalContainerChanges<MapChanges<Serializable, Integer>, Map<Serializable, Integer>, TransactionalMap<Serializable, Integer>> createLayer() {
		return isTransactionAvailable() ? new TransactionalContainerChanges<>() : null;
	}

	@Nonnull
	@Override
	public UniqueIndex createCopyWithMergedTransactionalMemory(@Nullable TransactionalContainerChanges<MapChanges<Serializable, Integer>, Map<Serializable, Integer>, TransactionalMap<Serializable, Integer>> layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		final Boolean isDirty = transactionalLayer.getStateCopyWithCommittedChanges(this.dirty);
		if (isDirty) {
			final UniqueIndex uniqueKeyIndex = new UniqueIndex(
				this.entityType, this.attributeIndexKey, this.type,
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

	/**
	 * Returns index of unique values mapped to record ids.
	 */
	@Nonnull
	Map<Serializable, Integer> getUniqueValueToRecordId() {
		return Collections.unmodifiableMap(this.uniqueValueToRecordId);
	}

	/*
		PRIVATE METHODS
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

	private <T extends Serializable & Comparable<T>> void registerUniqueKeyValue(@Nonnull T key, int recordId) {
		final Integer existingRecordId = this.uniqueValueToRecordId.get(key);
		assertUniqueKeyIsFree(key, recordId, existingRecordId);
		this.uniqueValueToRecordId.put(key, recordId);
		this.recordIds.add(recordId);
	}

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

	private <T extends Serializable & Comparable<T>> int unregisterUniqueKeyValue(@Nonnull T key, int expectedRecordId) {
		final Integer existingRecordId = this.uniqueValueToRecordId.remove(key);
		assertUniqueKeyOwnership(key, expectedRecordId, existingRecordId);
		this.recordIds.remove(existingRecordId);
		return existingRecordId;
	}

	private <T extends Serializable & Comparable<T>> void assertUniqueKeyIsFree(@Nonnull T key, int recordId, @Nullable Integer existingRecordId) {
		if (!(existingRecordId == null || existingRecordId.equals(recordId))) {
			throw new UniqueValueViolationException(this.attributeIndexKey.attributeName(), this.attributeIndexKey.locale(), key, this.entityType, existingRecordId, this.entityType, recordId);
		}
	}

	private <T extends Serializable & Comparable<T>> void assertUniqueKeyOwnership(@Nonnull T key, int expectedRecordId, @Nullable Integer existingRecordId) {
		isTrue(
			Objects.equals(existingRecordId, expectedRecordId),
			() -> existingRecordId == null ?
				"No unique key exists for `" + this.attributeIndexKey.attributeName() + "` key: `" + key + "`" + (this.attributeIndexKey.locale() == null ? "" : " in locale `" + this.attributeIndexKey.locale().toLanguageTag() + "`") + "!" :
				"Unique key exists for `" + this.attributeIndexKey.attributeName() + "` key: `" + key + "`" + (this.attributeIndexKey.locale() == null ? "" : " in locale `" + this.attributeIndexKey.locale().toLanguageTag() + "`") + " belongs to record with id `" + existingRecordId + "` and not `" + expectedRecordId + "` as expected!"
		);
	}

}
