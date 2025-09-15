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

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.spi.model.storageParts.index.CardinalityIndexStoragePart;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
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
 * TODO JNO - zjednodušit klíče na jednoduchý objekt - viz použití v ReferenceTypeIndexu
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class CardinalityIndex implements VoidTransactionMemoryProducer<CardinalityIndex>, IndexDataStructure, Serializable {
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
	 * The TransactionalMap is a map-like data structure that allows concurrent access and modification
	 * of the cardinalities in a transactional manner. Each cardinality is associated with a CardinalityKey,
	 * which uniquely identifies the entity for which the cardinality is being stored.
	 */
	private final TransactionalMap<CardinalityKey, Integer> cardinalities;

	public CardinalityIndex(@Nonnull Class<? extends Serializable> valueType) {
		this.valueType = valueType;
		this.dirty = new TransactionalBoolean();
		this.cardinalities = new TransactionalMap<>(CollectionUtils.createHashMap(16));
	}

	public CardinalityIndex(
		@Nonnull Class<? extends Serializable> valueType,
		@Nonnull Map<CardinalityKey, Integer> cardinalities
	) {
		this.valueType = valueType;
		this.dirty = new TransactionalBoolean();
		this.cardinalities = new TransactionalMap<>(cardinalities);
	}

	/**
	 * Returns cardinalities of all keys in the index.
	 * @return cardinalities of all keys in the index
	 */
	@Nonnull
	public Map<CardinalityKey, Integer> getCardinalities() {
		return this.cardinalities;
	}

	/**
	 * Increases cardinality of given key by one. If key is not present in the index, it is added with cardinality 1
	 * and TRUE is returned, otherwise existing cardinality is increased by one FALSE is returned.
	 * @param key key to be added
	 * @return TRUE if key was not present in the index, FALSE otherwise
	 */
	public boolean addRecord(@Nonnull Serializable key, int recordId) {
		this.dirty.setToTrue();
		return this.cardinalities.compute(
			new CardinalityKey(recordId, key),
			(k, v) -> v == null ? 1 : v + 1
		) == 1;
	}

	/**
	 * Decreases cardinality of given key by one. If the cardinality of the key reaches zero, the key is removed from
	 * the index and TRUE is returned, otherwise FALSE is returned.
	 *
	 * @param key key to be removed
	 * @return TRUE if key was removed from the index, FALSE otherwise
	 */
	public boolean removeRecord(@Nonnull Serializable key, int recordId) {
		this.dirty.setToTrue();
		final CardinalityKey cardinalityKey = new CardinalityKey(recordId, key);
		final Integer newValue = this.cardinalities.computeIfPresent(
			cardinalityKey,
			(k, v) -> v - 1
		);
		if (newValue == null) {
			throw new GenericEvitaInternalError("Cardinality of key `" + key + "` for record `" + recordId + "` is null");
		} else if (newValue == 0) {
			this.cardinalities.remove(cardinalityKey);
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Returns TRUE if this contains no data.
	 * @return TRUE if this contains no data
	 */
	public boolean isEmpty() {
		return this.cardinalities.isEmpty();
	}

	/**
	 * Method creates container for storing chain index from memory to the persistent storage.
	 */
	@Nullable
	public StoragePart createStoragePart(int entityIndexPrimaryKey, AttributeKey attribute) {
		if (this.dirty.isTrue()) {
			return new CardinalityIndexStoragePart(
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
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		this.cardinalities.removeLayer(transactionalLayer);
		this.dirty.removeLayer(transactionalLayer);
	}

	@Nonnull
	@Override
	public CardinalityIndex createCopyWithMergedTransactionalMemory(@Nullable Void layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		// we can safely throw away dirty flag now
		final Boolean isDirty = transactionalLayer.getStateCopyWithCommittedChanges(this.dirty);
		if (isDirty) {
			return new CardinalityIndex(
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
	public record CardinalityKey(
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
