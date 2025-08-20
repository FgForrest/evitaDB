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

package io.evitadb.index.price;

import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.core.Catalog;
import io.evitadb.core.exception.PriceAlreadyAssignedToEntityException;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.price.priceIndex.PriceIdContainerFormula;
import io.evitadb.core.query.algebra.price.priceIndex.PriceIndexContainerFormula;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.array.TransactionalObjArray;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.entityPrices.EntityPrices;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.spi.model.storageParts.index.PriceListAndCurrencySuperIndexStoragePart;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import static io.evitadb.core.Transaction.isTransactionAvailable;
import static io.evitadb.utils.CollectionUtils.createHashMap;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * Index contains information used for filtering by price that is related to specific price list and currency combination.
 * Real world use-cases usually filter entities by price in certain currency in set of price lists, and we can greatly
 * minimize the working set by separating price indexes by this combination.
 *
 * This super index is exactly one per {@link Catalog} and contains memory expensive object such as
 * {@link PriceRecord} and {@link EntityPrices}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class PriceListAndCurrencyPriceSuperIndex implements VoidTransactionMemoryProducer<PriceListAndCurrencyPriceSuperIndex>, PriceListAndCurrencyPriceIndex<Void, PriceListAndCurrencyPriceSuperIndex>, IndexDataStructure, Serializable {
	@Serial private static final long serialVersionUID = 182980639981206272L;
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * This is internal flag that tracks whether the index contents became dirty and needs to be persisted.
	 */
	private final TransactionalBoolean dirty;
	/**
	 * Unique identification of this index - contains price list name and currency combination.
	 */
	@Getter private final PriceIndexKey priceIndexKey;
	/**
	 * Bitmap contains all entity ids known to this index. This bitmap represents superset of all inner bitmaps.
	 */
	private final TransactionalBitmap indexedPriceEntityIds;
	/**
	 * Field contains condensed bitmap of all {@link #priceRecords} {@link PriceRecordContract#internalPriceId()}
	 * for the sake of the faster search for appropriate {@link PriceRecordContract} by the internal price id.
	 */
	private final TransactionalBitmap indexedPriceIds;
	/**
	 * Contains the same information as in {@link #priceRecords}, but indexed by entityId.
	 */
	private final TransactionalMap<Integer, EntityPrices> entityPrices;
	/**
	 * Range index contains date-time validity information for each indexed price id. This index is used to process
	 * the {@link io.evitadb.api.query.filter.PriceValidIn} filtering query.
	 */
	private final RangeIndex validityIndex;
	/**
	 * Array contains complete information about prices sorted by {@link PriceContract#priceId()} allowing translation
	 * of price id to {@link Entity#getPrimaryKey()} using binary search algorithm.
	 */
	private final TransactionalObjArray<PriceRecordContract> priceRecords;
	/**
	 * Contains flags that makes the index terminated and unusable.
	 */
	private final TransactionalBoolean terminated;
	/**
	 * Contains cached result of {@link TransactionalBitmap#getArray()} call.
	 */
	private int[] memoizedIndexedPriceIds;

	public PriceListAndCurrencyPriceSuperIndex(@Nonnull PriceIndexKey priceIndexKey) {
		this.dirty = new TransactionalBoolean();
		this.terminated = new TransactionalBoolean();
		this.indexedPriceEntityIds = new TransactionalBitmap();
		this.indexedPriceIds = new TransactionalBitmap();
		this.priceIndexKey = priceIndexKey;
		this.validityIndex = new RangeIndex();
		this.priceRecords = new TransactionalObjArray<>(new PriceRecordContract[0], Comparator.naturalOrder());
		this.entityPrices = new TransactionalMap<>(new HashMap<>());
	}

	public PriceListAndCurrencyPriceSuperIndex(
		@Nonnull PriceIndexKey priceIndexKey,
		@Nonnull RangeIndex validityIndex,
		@Nonnull PriceRecordContract[] priceRecords
	) {
		this.dirty = new TransactionalBoolean();
		this.terminated = new TransactionalBoolean();
		this.priceIndexKey = priceIndexKey;
		this.validityIndex = validityIndex;
		this.priceRecords = new TransactionalObjArray<>(priceRecords, Comparator.naturalOrder());
		this.entityPrices = new TransactionalMap<>(createHashMap(priceRecords.length));

		final int[] priceIds = new int[priceRecords.length];
		final int[] entityIds = new int[priceRecords.length];
		for (int i = 0; i < priceRecords.length; i++) {
			final PriceRecordContract priceRecord = priceRecords[i];
			entityIds[i] = priceRecord.entityPrimaryKey();
			priceIds[i] = priceRecord.internalPriceId();
			addEntityPrice(priceRecord);
		}

		this.indexedPriceEntityIds = new TransactionalBitmap(entityIds);
		this.indexedPriceIds = new TransactionalBitmap(priceIds);
		this.memoizedIndexedPriceIds = priceIds;
	}

	private PriceListAndCurrencyPriceSuperIndex(
		@Nonnull PriceIndexKey priceIndexKey,
		@Nonnull Bitmap indexedPriceEntityIds,
		@Nonnull Bitmap priceIds,
		@Nonnull Map<Integer, EntityPrices> entityPrices,
		@Nonnull RangeIndex validityIndex,
		@Nonnull PriceRecordContract[] priceRecords
	) {
		this.dirty = new TransactionalBoolean();
		this.terminated = new TransactionalBoolean();
		this.priceIndexKey = priceIndexKey;
		this.indexedPriceEntityIds = new TransactionalBitmap(indexedPriceEntityIds);
		this.indexedPriceIds = new TransactionalBitmap(priceIds);
		this.entityPrices = new TransactionalMap<>(entityPrices);
		this.validityIndex = validityIndex;
		this.priceRecords = new TransactionalObjArray<>(priceRecords, Comparator.naturalOrder());
	}

	/**
	 * Indexes inner record id or entity primary key into the price index with passed values.
	 */
	public void addPrice(@Nonnull PriceRecordContract priceRecord, @Nullable DateTimeRange validity) {
		assertNotTerminated();
		if (isPriceRecordKnown(priceRecord.entityPrimaryKey(), priceRecord.priceId())) {
			throw new PriceAlreadyAssignedToEntityException(
				priceRecord.priceId(),
				priceRecord.entityPrimaryKey(),
				of(priceRecord.innerRecordId()).filter(it -> it == 0).orElse(null)
			);
		}
		// index the presence of the record
		this.indexedPriceEntityIds.add(priceRecord.entityPrimaryKey());
		this.indexedPriceIds.add(priceRecord.internalPriceId());
		// index validity
		if (validity != null) {
			this.validityIndex.addRecord(validity.getFrom(), validity.getTo(), priceRecord.internalPriceId());
		} else {
			this.validityIndex.addRecord(Long.MIN_VALUE, Long.MAX_VALUE, priceRecord.internalPriceId());
		}
		// index prices with entity
		addEntityPrice(priceRecord);
		// add price to the translation triple
		this.priceRecords.add(priceRecord);
		// make index dirty
		this.dirty.setToTrue();
		if (!isTransactionAvailable()) {
			this.memoizedIndexedPriceIds = null;
		}
	}

	/**
	 * Removes inner record id or entity primary key of passed values from the price index.
	 */
	public void removePrice(int entityPrimaryKey, int internalPriceId, @Nullable DateTimeRange validity) {
		assertNotTerminated();
		final PriceRecordContract priceRecord = getPriceRecord(internalPriceId);
		this.priceRecords.remove(priceRecord);

		// remove the presence of the record
		this.indexedPriceIds.remove(priceRecord.internalPriceId());

		// remove price from entity
		final EntityPrices updatedEntityPrices = removeEntityPrice(priceRecord);
		Assert.notNull(updatedEntityPrices, "No entity prices found in index " + this.priceIndexKey + " for entity with id: " + entityPrimaryKey);

		if (updatedEntityPrices.isEmpty()) {
			// remove the presence of the record
			this.indexedPriceEntityIds.remove(priceRecord.entityPrimaryKey());
			// remove entity prices entirely
			this.entityPrices.remove(entityPrimaryKey);
		}
		// remove validity
		if (validity != null) {
			this.validityIndex.removeRecord(validity.getFrom(), validity.getTo(), priceRecord.internalPriceId());
		} else {
			this.validityIndex.removeRecord(Long.MIN_VALUE, Long.MAX_VALUE, priceRecord.internalPriceId());
		}
		// make index dirty
		this.dirty.setToTrue();
		if (!isTransactionAvailable()) {
			this.memoizedIndexedPriceIds = null;
		}
	}

	@Nonnull
	@Override
	public Bitmap getIndexedPriceEntityIds() {
		assertNotTerminated();
		return this.indexedPriceEntityIds;
	}

	/**
	 * Method returns condensed bitmap of all {@link #priceRecords} {@link PriceRecordContract#internalPriceId()}
	 * that can be used for the faster search for appropriate {@link PriceRecordContract} by the internal price id.
	 */
	@Nonnull
	public int[] getIndexedPriceIds() {
		assertNotTerminated();
		// if there is transaction open, there might be changes in the histogram data, and we can't easily use cache
		if (isTransactionAvailable() && this.dirty.isTrue()) {
			return this.indexedPriceIds.getArray();
		} else {
			if (this.memoizedIndexedPriceIds == null) {
				this.memoizedIndexedPriceIds = this.indexedPriceIds.getArray();
			}
			return this.memoizedIndexedPriceIds;
		}
	}

	@Nonnull
	@Override
	public Formula getIndexedPriceEntityIdsFormula() {
		assertNotTerminated();
		if (this.indexedPriceEntityIds.isEmpty()) {
			return EmptyFormula.INSTANCE;
		} else {
			return new ConstantFormula(this.indexedPriceEntityIds);
		}
	}

	@Nonnull
	@Override
	public PriceIdContainerFormula getIndexedRecordIdsValidInFormula(@Nonnull OffsetDateTime theMoment) {
		assertNotTerminated();
		final long thePoint = DateTimeRange.toComparableLong(theMoment);
		return new PriceIdContainerFormula(
			this, this.validityIndex.getRecordsEnvelopingInclusive(thePoint)
		);
	}

	@Nullable
	@Override
	public int[] getInternalPriceIdsForEntity(int entityId) {
		assertNotTerminated();
		return ofNullable(this.entityPrices.get(entityId)).map(EntityPrices::getInternalPriceIds).orElse(null);
	}

	@Nullable
	@Override
	public PriceRecordContract[] getLowestPriceRecordsForEntity(int entityId) {
		assertNotTerminated();
		return ofNullable(this.entityPrices.get(entityId)).map(EntityPrices::getLowestPriceRecords).orElse(null);
	}

	@Nonnull
	@Override
	public PriceRecordContract[] getPriceRecords() {
		assertNotTerminated();
		return this.priceRecords.getArray();
	}

	@Nonnull
	@Override
	public Formula createPriceIndexFormulaWithAllRecords() {
		assertNotTerminated();
		return new PriceIndexContainerFormula(this, this.getIndexedPriceEntityIdsFormula());
	}

	@Override
	public boolean isEmpty() {
		assertNotTerminated();
		return this.indexedPriceEntityIds.isEmpty();
	}

	@Nullable
	@Override
	public StoragePart createStoragePart(int entityIndexPrimaryKey) {
		assertNotTerminated();
		if (this.dirty.isTrue()) {
			return new PriceListAndCurrencySuperIndexStoragePart(
				entityIndexPrimaryKey, this.priceIndexKey, this.validityIndex, this.priceRecords.getArray()
			);
		} else {
			return null;
		}
	}

	/**
	 * Method returns single {@link PriceRecord} reference that match passed price id.
	 */
	@Nonnull
	public PriceRecordContract getPriceRecord(int internalPriceId) {
		assertNotTerminated();
		final PriceRecordContract[] priceRecords = this.priceRecords.getArray();
		final int position = this.indexedPriceIds.indexOf(internalPriceId);
		Assert.isTrue(position >= 0, "Price id `" + internalPriceId + "` was not found in the price super index!");
		return priceRecords[position];
	}

	/**
	 * Method returns single {@link EntityPrices} record matching passed entity primary key.
	 */
	@Nonnull
	public EntityPrices getEntityPrices(int entityPrimaryKey) {
		assertNotTerminated();
		final EntityPrices theEntityPrices = this.entityPrices.get(entityPrimaryKey);
		Assert.isPremiseValid(theEntityPrices != null, "Entity prices for " + entityPrimaryKey + " unexpectedly not found!");
		return theEntityPrices;
	}

	@Override
	public boolean isTerminated() {
		return this.terminated.isTrue();
	}

	@Override
	public void terminate() {
		this.terminated.setToTrue();
	}

	@Override
	public String toString() {
		return this.priceIndexKey.toString() + (this.terminated.isTrue() ? " (TERMINATED)" : "");
	}

	@Override
	public void resetDirty() {
		assertNotTerminated();
		this.dirty.reset();
	}

	@Nonnull
	@Override
	public PriceListAndCurrencyPriceSuperIndex createCopyWithMergedTransactionalMemory(@Nullable Void layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		assertNotTerminated();
		// we can safely throw away dirty flag now
		final Boolean isDirty = transactionalLayer.getStateCopyWithCommittedChanges(this.dirty);
		this.terminated.removeLayer(transactionalLayer);
		if (isDirty) {
			final PriceRecordContract[] newTriples = transactionalLayer.getStateCopyWithCommittedChanges(this.priceRecords);
			return new PriceListAndCurrencyPriceSuperIndex(
				this.priceIndexKey,
				transactionalLayer.getStateCopyWithCommittedChanges(this.indexedPriceEntityIds),
				transactionalLayer.getStateCopyWithCommittedChanges(this.indexedPriceIds),
				transactionalLayer.getStateCopyWithCommittedChanges(this.entityPrices),
				transactionalLayer.getStateCopyWithCommittedChanges(this.validityIndex),
				newTriples
			);
		} else {
			return this;
		}
	}

	/*
		TransactionalLayerCreator implementation
	 */

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		this.dirty.removeLayer(transactionalLayer);
		this.terminated.removeLayer(transactionalLayer);
		this.indexedPriceEntityIds.removeLayer(transactionalLayer);
		this.indexedPriceIds.removeLayer(transactionalLayer);
		this.entityPrices.removeLayer(transactionalLayer);
		this.validityIndex.removeLayer(transactionalLayer);
		this.priceRecords.removeLayer(transactionalLayer);
	}

	/*
		PRIVATE METHODS
	*/

	/**
	 * Verifies that the index is not terminated.
	 */
	private void assertNotTerminated() {
		if (this.terminated.isTrue()) {
			throw new PriceListAndCurrencyPriceIndexTerminated(
				"Price list and currency index " + this.priceIndexKey + " is terminated!"
			);
		}
	}

	private boolean isPriceRecordKnown(int entityPrimaryKey, int priceId) {
		final EntityPrices theEntityPrices = this.entityPrices.get(entityPrimaryKey);
		if (theEntityPrices != null) {
			return theEntityPrices.containsPriceRecord(priceId);
		}
		return false;
	}

	private void addEntityPrice(@Nonnull PriceRecordContract priceRecord) {
		this.entityPrices.compute(
			priceRecord.entityPrimaryKey(),
			(entityId, existingPriceRecords) -> {
				if (existingPriceRecords == null) {
					return EntityPrices.create(priceRecord);
				} else {
					return EntityPrices.addPriceRecord(existingPriceRecords, priceRecord);
				}
			}
		);
	}

	@Nonnull
	private EntityPrices removeEntityPrice(@Nonnull PriceRecordContract priceRecord) {
		return this.entityPrices.computeIfPresent(
			priceRecord.entityPrimaryKey(),
			(entityId, existingPriceRecords) -> EntityPrices.removePrice(existingPriceRecords, priceRecord)
		);
	}

}
