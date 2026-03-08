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

import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.exception.PriceAlreadyAssignedToEntityException;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.entityPrices.EntityPrices;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.PriceListAndCurrencySuperIndexStoragePart;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.HashMap;
import java.util.Map;

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
public class PriceListAndCurrencyPriceSuperIndex
	extends AbstractPriceListAndCurrencyPriceIndex<PriceListAndCurrencyPriceSuperIndex> {

	@Serial private static final long serialVersionUID = 182980639981206272L;
	/**
	 * Contains the same information as in {@link #priceRecords}, but indexed by entityId.
	 */
	private final TransactionalMap<Integer, EntityPrices> entityPrices;

	public PriceListAndCurrencyPriceSuperIndex(@Nonnull PriceIndexKey priceIndexKey) {
		super(priceIndexKey);
		this.entityPrices = new TransactionalMap<>(new HashMap<>());
	}

	public PriceListAndCurrencyPriceSuperIndex(
		@Nonnull PriceIndexKey priceIndexKey,
		@Nonnull RangeIndex validityIndex,
		@Nonnull PriceRecordContract[] priceRecords
	) {
		super(priceIndexKey, validityIndex, priceRecords);
		this.entityPrices = new TransactionalMap<>(createHashMap(priceRecords.length));
		for (final PriceRecordContract priceRecord : priceRecords) {
			addEntityPrice(priceRecord);
		}
	}

	private PriceListAndCurrencyPriceSuperIndex(
		@Nonnull PriceIndexKey priceIndexKey,
		@Nonnull Bitmap indexedPriceEntityIds,
		@Nonnull Bitmap priceIds,
		@Nonnull Map<Integer, EntityPrices> entityPrices,
		@Nonnull RangeIndex validityIndex,
		@Nonnull PriceRecordContract[] priceRecords
	) {
		super(priceIndexKey, indexedPriceEntityIds, priceIds, validityIndex, priceRecords);
		this.entityPrices = new TransactionalMap<>(entityPrices);
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
		addValidity(validity, priceRecord.internalPriceId());
		// index prices with entity
		addEntityPrice(priceRecord);
		// add price to the translation triple
		this.priceRecords.add(priceRecord);
		// make index dirty
		markDirtyAndInvalidateCache();
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
		removeValidity(validity, priceRecord.internalPriceId());
		// make index dirty
		markDirtyAndInvalidateCache();
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
	public String toString() {
		return this.priceIndexKey.toString() + (isTerminated() ? " (TERMINATED)" : "");
	}

	@Nonnull
	@Override
	public PriceListAndCurrencyPriceSuperIndex createCopyWithMergedTransactionalMemory(
		@Nullable Void layer,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		assertNotTerminated();
		// we can safely throw away dirty flag now
		final boolean isDirty = transactionalLayer.getStateCopyWithCommittedChanges(this.dirty);
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

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		super.removeLayer(transactionalLayer);
		this.entityPrices.removeLayer(transactionalLayer);
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

	@Nullable
	private EntityPrices removeEntityPrice(@Nonnull PriceRecordContract priceRecord) {
		return this.entityPrices.computeIfPresent(
			priceRecord.entityPrimaryKey(),
			(entityId, existingPriceRecords) -> EntityPrices.removePrice(existingPriceRecords, priceRecord)
		);
	}

}
