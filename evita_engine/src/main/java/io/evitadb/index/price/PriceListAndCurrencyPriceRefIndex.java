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

import io.evitadb.api.CatalogState;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.catalog.CatalogRelatedDataStructure;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.array.TransactionalObjArray;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.entityPrices.EntityPrices;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.PriceListAndCurrencyRefIndexStoragePart;
import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Objects;

/**
 * Index contains information used for filtering by price that is related to specific price list and currency combination.
 * Real world use-cases usually filter entities by price in certain currency in set of price lists, and we can greatly
 * minimize the working set by separating price indexes by this combination.
 *
 * RefIndex attempts to store minimal data set in order to save memory on heap. For memory expensive objects such as
 * {@link PriceRecord} and {@link EntityPrices} it looks up via {@link #superIndex} where the records are located.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class PriceListAndCurrencyPriceRefIndex
	extends AbstractPriceListAndCurrencyPriceIndex<PriceListAndCurrencyPriceRefIndex>
	implements CatalogRelatedDataStructure<PriceListAndCurrencyPriceRefIndex> {

	@Serial private static final long serialVersionUID = 182980639981206272L;
	/**
	 * Captures the scope of the index and reflects the {@link EntityIndexKey#scope()} of the main entity index this
	 * price index is part of.
	 */
	private final Scope scope;
	/**
	 * Reference to the main {@link PriceListAndCurrencyPriceSuperIndex} that keeps memory expensive objects, which
	 * is initialized in {@link #attachToCatalog(String, Catalog)} callback.
	 */
	private PriceListAndCurrencyPriceSuperIndex superIndex;

	public PriceListAndCurrencyPriceRefIndex(
		@Nonnull Scope scope,
		@Nonnull PriceIndexKey priceIndexKey
	) {
		super(priceIndexKey);
		this.scope = scope;
	}

	public PriceListAndCurrencyPriceRefIndex(
		@Nonnull Scope scope,
		@Nonnull PriceIndexKey priceIndexKey,
		@Nonnull RangeIndex validityIndex,
		@Nonnull int[] priceIds
	) {
		super(priceIndexKey, validityIndex, priceIds);
		this.scope = scope;
	}

	private PriceListAndCurrencyPriceRefIndex(
		@Nonnull Scope scope,
		@Nonnull PriceIndexKey priceIndexKey,
		@Nonnull Bitmap indexedPriceEntityIds,
		@Nonnull Bitmap priceIds,
		@Nonnull RangeIndex validityIndex
	) {
		super(priceIndexKey, indexedPriceEntityIds, priceIds, validityIndex);
		this.scope = scope;
	}

	private PriceListAndCurrencyPriceRefIndex(
		@Nonnull Scope scope,
		@Nonnull PriceIndexKey priceIndexKey,
		@Nonnull TransactionalBitmap indexedPriceEntityIds,
		@Nonnull TransactionalBitmap priceIds,
		@Nonnull RangeIndex validityIndex
	) {
		super(priceIndexKey, indexedPriceEntityIds, priceIds, validityIndex);
		this.scope = scope;
	}

	@Override
	public void attachToCatalog(@Nullable String entityType, @Nonnull Catalog catalog) {
		assertNotTerminated();
		Assert.isPremiseValid(entityType != null, "Entity type must be provided!");
		Assert.isPremiseValid(this.superIndex == null, "Catalog was already attached to this index!");
		final PriceListAndCurrencyPriceIndex<?, ?> superIndex = catalog.getEntityIndexIfExists(
			entityType,
			new EntityIndexKey(EntityIndexType.GLOBAL, this.scope),
			GlobalEntityIndex.class
		)
			.map(it -> it.getPriceIndex(this.priceIndexKey))
			.orElse(null);
		Assert.isPremiseValid(
			superIndex instanceof PriceListAndCurrencyPriceSuperIndex,
			() -> new GenericEvitaInternalError(
				"PriceListAndCurrencyPriceRefIndex can only be initialized with PriceListAndCurrencyPriceSuperIndex, " +
					"actual instance is `" + (this.superIndex == null ? "NULL" : this.superIndex.getClass().getName()) + "`",
				"PriceListAndCurrencyPriceRefIndex can only be initialized with PriceListAndCurrencyPriceSuperIndex"
			)
		);
		this.superIndex = (PriceListAndCurrencyPriceSuperIndex) superIndex;
		final PriceRecordContract[] priceRecords = superIndex.getPriceRecords(this.indexedPriceIds);
		this.priceRecords = new TransactionalObjArray<>(priceRecords, Comparator.naturalOrder());

		final int[] entityIds = new int[priceRecords.length];
		for (int i = 0; i < priceRecords.length; i++) {
			final PriceRecordContract priceRecord = priceRecords[i];
			entityIds[i] = priceRecord.entityPrimaryKey();
		}
		this.indexedPriceEntityIds = new TransactionalBitmap(entityIds);
	}

	@Nonnull
	@Override
	public PriceListAndCurrencyPriceRefIndex createCopyForNewCatalogAttachment(@Nonnull CatalogState catalogState) {
		assertNotTerminated();
		return new PriceListAndCurrencyPriceRefIndex(
			this.scope,
			this.priceIndexKey,
			this.indexedPriceEntityIds,
			this.indexedPriceIds,
			this.validityIndex
		);
	}

	/**
	 * Indexes inner record id or entity primary key into the price index with passed values.
	 */
	@Nonnull
	public PriceRecordContract addPrice(
		@Nonnull Integer internalPriceId,
		@Nullable DateTimeRange validity
	) {
		assertNotTerminated();
		final int ipId = Objects.requireNonNull(internalPriceId);
		final PriceRecordContract priceRecord = this.superIndex.getPriceRecord(ipId);

		// index the presence of the record
		this.indexedPriceEntityIds.add(priceRecord.entityPrimaryKey());
		this.indexedPriceIds.add(priceRecord.internalPriceId());
		// index validity
		addValidity(validity, priceRecord.internalPriceId());
		// add price to the translation triple
		this.priceRecords.add(priceRecord);
		// make index dirty
		markDirtyAndInvalidateCache();

		return priceRecord;
	}

	/**
	 * Removes inner record id or entity primary key of passed values from the price index.
	 */
	@Nonnull
	public PriceRecordContract removePrice(
		@Nonnull Integer internalPriceId,
		@Nullable DateTimeRange validity
	) {
		assertNotTerminated();
		final int ipId = Objects.requireNonNull(internalPriceId);
		final PriceRecordContract priceRecord = this.superIndex.getPriceRecord(ipId);
		final EntityPrices entityPrices = this.superIndex.getEntityPrices(priceRecord.entityPrimaryKey());

		// remove price to the translation triple
		this.priceRecords.remove(priceRecord);

		// remove the presence of the record
		this.indexedPriceIds.remove(priceRecord.internalPriceId());

		if (!entityPrices.containsAnyOf(this.priceRecords.getArray())) {
			// remove the presence of the record
			this.indexedPriceEntityIds.remove(priceRecord.entityPrimaryKey());
		}
		// remove validity
		removeValidity(validity, priceRecord.internalPriceId());
		// make index dirty
		markDirtyAndInvalidateCache();

		return priceRecord;
	}

	@Nullable
	@Override
	public int[] getInternalPriceIdsForEntity(int entityId) {
		assertNotTerminated();
		return this.superIndex.getInternalPriceIdsForEntity(entityId);
	}

	@Override
	@Nullable
	public PriceRecordContract[] getLowestPriceRecordsForEntity(int entityId) {
		assertNotTerminated();
		return this.superIndex.getLowestPriceRecordsForEntity(entityId);
	}

	@Nullable
	@Override
	public StoragePart createStoragePart(int entityIndexPrimaryKey) {
		if (this.dirty.isTrue()) {
			final int[] priceIds = new int[this.priceRecords.getLength()];
			final Iterator<PriceRecordContract> it = this.priceRecords.iterator();
			int index = 0;
			while (it.hasNext()) {
				final PriceRecordContract priceRecord = it.next();
				priceIds[index++] = priceRecord.internalPriceId();
			}
			return new PriceListAndCurrencyRefIndexStoragePart(
				entityIndexPrimaryKey, this.priceIndexKey, this.validityIndex, priceIds
			);
		} else {
			return null;
		}
	}

	@Override
	public String toString() {
		return StringUtils.capitalize(this.scope.name().toLowerCase()) + " " + this.priceIndexKey.toString() + (isTerminated() ? " (TERMINATED)" : "");
	}

	@Nonnull
	@Override
	public PriceListAndCurrencyPriceRefIndex createCopyWithMergedTransactionalMemory(
		@Nullable Void layer,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		// we can safely throw away dirty flag now
		this.dirty.removeLayer(transactionalLayer);
		this.terminated.removeLayer(transactionalLayer);
		this.priceRecords.removeLayer(transactionalLayer);
		return new PriceListAndCurrencyPriceRefIndex(
			this.scope,
			this.priceIndexKey,
			transactionalLayer.getStateCopyWithCommittedChanges(this.indexedPriceEntityIds),
			transactionalLayer.getStateCopyWithCommittedChanges(this.indexedPriceIds),
			transactionalLayer.getStateCopyWithCommittedChanges(this.validityIndex)
		);
	}

}
