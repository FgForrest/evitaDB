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

import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.map.PersistentTransactionalMap;
import io.evitadb.index.result.CardinalityChange;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeCardinalityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;

/**
 * Represents a cardinality index that stores the cardinalities of keys.
 * The index allows adding and removing keys, and retrieving the cardinalities of all keys.
 *
 * The index allows us to track the number of occurrences of a key in indexes that allow multiple occurrences of
 * the record in the index. In order to correctly remove the key from the index, we need to know how many times
 * the key is present in the index and remove it only when the last occurrence is evicted. This is where the cardinality
 * index comes in.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class AttributeCardinalityIndex
	implements VoidTransactionMemoryProducer<AttributeCardinalityIndex>, IndexDataStructure, Serializable {
	@Serial private static final long serialVersionUID = -7416602590381722682L;
	/**
	 * Represents the type of values stored in this cardinality index.
	 */
	@Getter private final Class<? extends Serializable> valueType;
	/**
	 * This is internal flag that tracks whether the index contents became dirty and needs to be persisted.
	 */
	@Nonnull private final TransactionalBoolean dirty;
	/**
	 * A variable that holds the cardinalities of different entities.
	 *
	 * The {@link PersistentTransactionalMap} is a map-like data structure that allows concurrent access and
	 * modification of the cardinalities in a transactional manner. Each cardinality is associated with a
	 * AttributeCardinalityKey, which uniquely identifies the entity for which the cardinality is being stored.
	 * The map is plain-valued and mutated exclusively through `compute`/`computeIfPresent`/`remove`, so commit
	 * folds only the changed keys onto the persistent snapshot in `O(Δ·log N)` instead of rebuilding the whole
	 * map on every transaction.
	 */
	private final PersistentTransactionalMap<AttributeCardinalityKey, Integer> cardinalities;

	public AttributeCardinalityIndex(@Nonnull Class<? extends Serializable> valueType) {
		this.valueType = valueType;
		this.dirty = new TransactionalBoolean();
		this.cardinalities = new PersistentTransactionalMap<>(CollectionUtils.createHashMap(16));
	}

	public AttributeCardinalityIndex(
		@Nonnull Class<? extends Serializable> valueType,
		@Nonnull Map<AttributeCardinalityKey, Integer> cardinalities
	) {
		this.valueType = valueType;
		this.dirty = new TransactionalBoolean();
		this.cardinalities = new PersistentTransactionalMap<>(cardinalities);
	}

	/**
	 * Returns cardinalities of all keys in the index.
	 * @return cardinalities of all keys in the index
	 */
	@Nonnull
	public Map<AttributeCardinalityKey, Integer> getCardinalities() {
		return this.cardinalities;
	}

	/**
	 * Increases cardinality of the given value by one. If the value was not present in the index before
	 * this call, it is added with cardinality 1 and `BOUNDARY_CROSSED` is returned so callers can
	 * propagate the new entry to downstream membership-only indexes. Otherwise the existing cardinality
	 * is incremented and `NO_BOUNDARY_CROSSING` is returned.
	 *
	 * @param value    value whose cardinality should be incremented
	 * @param recordId identifier of the owning record (cardinality is tracked per record)
	 * @return `BOUNDARY_CROSSED` if the cardinality went from 0 to 1, `NO_BOUNDARY_CROSSING` otherwise
	 */
	@Nonnull
	public CardinalityChange addRecord(@Nonnull Serializable value, int recordId) {
		assertValueCompatible(value);
		this.dirty.setToTrue();
		final int newCardinality = this.cardinalities.compute(
			new AttributeCardinalityKey(recordId, value),
			(k, v) -> v == null ? 1 : v + 1
		);
		return newCardinality == 1 ? CardinalityChange.BOUNDARY_CROSSED : CardinalityChange.NO_BOUNDARY_CROSSING;
	}

	/**
	 * Decreases cardinality of the given value by one. If the cardinality reaches zero the value is
	 * removed from the index and `BOUNDARY_CROSSED` is returned so callers can propagate the removal
	 * to downstream membership-only indexes. Otherwise the cardinality is decremented and
	 * `NO_BOUNDARY_CROSSING` is returned.
	 *
	 * @param value    value whose cardinality should be decremented
	 * @param recordId identifier of the owning record (cardinality is tracked per record)
	 * @return `BOUNDARY_CROSSED` if the cardinality dropped to 0, `NO_BOUNDARY_CROSSING` otherwise
	 */
	@Nonnull
	public CardinalityChange removeRecord(@Nonnull Serializable value, int recordId) {
		assertValueCompatible(value);
		this.dirty.setToTrue();
		final AttributeCardinalityKey cardinalityKey = new AttributeCardinalityKey(recordId, value);
		final Integer newValue = this.cardinalities.computeIfPresent(
			cardinalityKey,
			(k, v) -> v - 1
		);
		if (newValue == null) {
			throw new GenericEvitaInternalError("Cardinality of value `" + value + "` for record `" + recordId + "` is null");
		} else if (newValue == 0) {
			this.cardinalities.remove(cardinalityKey);
			return CardinalityChange.BOUNDARY_CROSSED;
		} else {
			return CardinalityChange.NO_BOUNDARY_CROSSING;
		}
	}

	/**
	 * Verifies that `value` is storable in this index. A value is compatible when it is an instance of the
	 * declared {@link #valueType}, or — for a `BigDecimal`-typed index — when it is the order-preserving scaled
	 * `Integer` surrogate the filter index now uses to encode `BigDecimal` attribute values (the same idempotent
	 * contract honoured by `FilterIndex.getNormalizer`). Histogram values sourced from a `BigDecimal` attribute's
	 * filter index arrive already scaled to an `Integer`, so the index records and evicts them in that same form.
	 *
	 * @param value the value to validate
	 */
	private void assertValueCompatible(@Nonnull Serializable value) {
		Assert.isTrue(
			this.valueType.isInstance(value) ||
				(BigDecimal.class.isAssignableFrom(this.valueType) && value instanceof Integer),
			"Value of type `" + value.getClass() + "` is not compatible with this index that accepts only values of type `" + this.valueType + "`!"
		);
	}

	/**
	 * Returns TRUE if this contains no data.
	 * @return TRUE if this contains no data
	 */
	public boolean isEmpty() {
		return this.cardinalities.isEmpty();
	}

	/**
	 * Returns `true` if the index contents have been modified and need persistence.
	 */
	public boolean isDirty() {
		return this.dirty.isTrue();
	}

	/**
	 * Method creates container for storing chain index from memory to the persistent storage.
	 */
	@Nullable
	public AttributeCardinalityIndexStoragePart createStoragePart(int entityIndexPrimaryKey, @Nonnull AttributeIndexKey attribute) {
		if (this.dirty.isTrue()) {
			return new AttributeCardinalityIndexStoragePart(
				entityIndexPrimaryKey, attribute, this
			);
		} else {
			return null;
		}
	}

	/*
		TransactionalLayerProducer implementation
	 */

	@Override
	public void resetDirty() {
		this.dirty.reset();
	}

	/*
		TransactionalLayerCreator implementation
	 */

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		this.cardinalities.removeLayer(transactionalLayer);
		this.dirty.removeLayer(transactionalLayer);
	}

	@Nonnull
	@Override
	public AttributeCardinalityIndex createCopyWithMergedTransactionalMemory(
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		// we can safely throw away dirty flag now
		final boolean isDirty = transactionalLayer.getStateCopyWithCommittedChanges(this.dirty);
		if (isDirty) {
			return new AttributeCardinalityIndex(
				this.valueType,
				transactionalLayer.getStateCopyWithCommittedChanges(this.cardinalities)
			);
		} else {
			return this;
		}
	}

	/**
	 * Represents a key used to uniquely identify a record and its associated value.
	 *
	 * @param recordId ID of the record
	 * @param value value of the record
	 */
	public record AttributeCardinalityKey(
		int recordId,
		@Nonnull Serializable value
	) {

		@Nonnull
		@Override
		public String toString() {
			return String.valueOf(this.recordId) + ':' + this.value;
		}
	}

}
