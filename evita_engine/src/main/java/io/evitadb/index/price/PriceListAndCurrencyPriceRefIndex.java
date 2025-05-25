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
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.core.Catalog;
import io.evitadb.core.CatalogRelatedDataStructure;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.price.priceIndex.PriceIdContainerFormula;
import io.evitadb.core.query.algebra.price.priceIndex.PriceIndexContainerFormula;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.array.TransactionalObjArray;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.entityPrices.EntityPrices;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.spi.model.storageParts.index.PriceListAndCurrencyRefIndexStoragePart;
import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Objects;

import static io.evitadb.core.Transaction.isTransactionAvailable;

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
public class PriceListAndCurrencyPriceRefIndex implements
	VoidTransactionMemoryProducer<PriceListAndCurrencyPriceRefIndex>,
	CatalogRelatedDataStructure<PriceListAndCurrencyPriceRefIndex>,
	IndexDataStructure,
	Serializable,
	PriceListAndCurrencyPriceIndex<Void, PriceListAndCurrencyPriceRefIndex> {
	@Serial private static final long serialVersionUID = 182980639981206272L;
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * This is internal flag that tracks whether the index contents became dirty and needs to be persisted.
	 */
	private final TransactionalBoolean dirty;
	/**
	 * Captures the scope of the index and reflects the {@link EntityIndexKey#scope()} of the main entity index this
	 * price index is part of.
	 */
	private final Scope scope;
	/**
	 * Unique identification of this index - contains price list name and currency combination.
	 */
	@Getter private final PriceIndexKey priceIndexKey;
	/**
	 * Field contains condensed bitmap of all {@link #priceRecords} {@link PriceRecordContract#internalPriceId()}
	 * for the sake of the faster search for appropriate {@link PriceRecordContract} by the internal price id.
	 */
	private final TransactionalBitmap indexedPriceIds;
	/**
	 * Range index contains date-time validity information for each indexed price id. This index is used to process
	 * the {@link io.evitadb.api.query.filter.PriceValidIn} filtering query.
	 */
	private final RangeIndex validityIndex;
	/**
	 * Reference to the main {@link PriceListAndCurrencyPriceSuperIndex} that keeps memory expensive objects, which
	 * is initialized in {@link #attachToCatalog(String, Catalog)} callback.
	 */
	private PriceListAndCurrencyPriceSuperIndex superIndex;
	/**
	 * Bitmap contains all entity ids known to this index. This bitmap represents superset of all inner bitmaps.
	 */
	private TransactionalBitmap indexedPriceEntityIds;
	/**
	 * Array contains complete information about prices sorted by {@link PriceContract#priceId()} allowing translation
	 * of price id to {@link Entity#getPrimaryKey()} using binary search algorithm.
	 */
	private TransactionalObjArray<PriceRecordContract> priceRecords;
	/**
	 * Contains flags that makes the index terminated and unusable.
	 */
	private final TransactionalBoolean terminated;
	/**
	 * Contains cached result of {@link TransactionalBitmap#getArray()} call.
	 */
	private int[] memoizedIndexedPriceIds;

	public PriceListAndCurrencyPriceRefIndex(
		@Nonnull Scope scope,
		@Nonnull PriceIndexKey priceIndexKey
	) {
		this.dirty = new TransactionalBoolean();
		this.terminated = new TransactionalBoolean();
		this.indexedPriceEntityIds = new TransactionalBitmap();
		this.indexedPriceIds = new TransactionalBitmap();
		this.scope = scope;
		this.priceIndexKey = priceIndexKey;
		this.validityIndex = new RangeIndex();
		this.priceRecords = new TransactionalObjArray<>(new PriceRecordContract[0], Comparator.naturalOrder());
	}

	public PriceListAndCurrencyPriceRefIndex(
		@Nonnull Scope scope,
		@Nonnull PriceIndexKey priceIndexKey,
		@Nonnull RangeIndex validityIndex,
		@Nonnull int[] priceIds
	) {
		this.dirty = new TransactionalBoolean();
		this.terminated = new TransactionalBoolean();
		this.scope = scope;
		this.priceIndexKey = priceIndexKey;
		this.validityIndex = validityIndex;
		this.indexedPriceIds = new TransactionalBitmap(priceIds);
		this.memoizedIndexedPriceIds = priceIds;
	}

	private PriceListAndCurrencyPriceRefIndex(
		@Nonnull Scope scope,
		@Nonnull PriceIndexKey priceIndexKey,
		@Nonnull Bitmap indexedPriceEntityIds,
		@Nonnull Bitmap priceIds,
		@Nonnull RangeIndex validityIndex
	) {
		this.dirty = new TransactionalBoolean();
		this.terminated = new TransactionalBoolean();
		this.scope = scope;
		this.priceIndexKey = priceIndexKey;
		this.indexedPriceEntityIds = new TransactionalBitmap(indexedPriceEntityIds);
		this.indexedPriceIds = new TransactionalBitmap(priceIds);
		this.validityIndex = validityIndex;
	}

	private PriceListAndCurrencyPriceRefIndex(
		@Nonnull Scope scope,
		@Nonnull PriceIndexKey priceIndexKey,
		@Nonnull TransactionalBitmap indexedPriceEntityIds,
		@Nonnull TransactionalBitmap priceIds,
		@Nonnull RangeIndex validityIndex
	) {
		this.dirty = new TransactionalBoolean();
		this.terminated = new TransactionalBoolean();
		this.scope = scope;
		this.priceIndexKey = priceIndexKey;
		this.indexedPriceEntityIds = indexedPriceEntityIds;
		this.indexedPriceIds = priceIds;
		this.validityIndex = validityIndex;
	}

	@Override
	public void attachToCatalog(@Nullable String entityType, @Nonnull Catalog catalog) {
		assertNotTerminated();
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
		if (validity != null) {
			this.validityIndex.addRecord(validity.getFrom(), validity.getTo(), priceRecord.internalPriceId());
		} else {
			this.validityIndex.addRecord(Long.MIN_VALUE, Long.MAX_VALUE, priceRecord.internalPriceId());
		}
		// add price to the translation triple
		this.priceRecords.add(priceRecord);
		// make index dirty
		this.dirty.setToTrue();
		if (!isTransactionAvailable()) {
			this.memoizedIndexedPriceIds = null;
		}

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

		return priceRecord;
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
		return this.superIndex.getInternalPriceIdsForEntity(entityId);
	}

	@Override
	@Nullable
	public PriceRecordContract[] getLowestPriceRecordsForEntity(int entityId) {
		assertNotTerminated();
		return this.superIndex.getLowestPriceRecordsForEntity(entityId);
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

	@Override
	public boolean isTerminated() {
		return this.terminated.isTrue();
	}

	@Override
	public void terminate() {
		this.terminated.setToTrue();
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
		return StringUtils.capitalize(this.scope.name().toLowerCase()) + " " + this.priceIndexKey.toString() + (this.terminated.isTrue() ? " (TERMINATED)" : "");
	}

	@Override
	public void resetDirty() {
		assertNotTerminated();
		this.dirty.reset();
	}

	/*
		TransactionalLayerCreator implementation
	 */

	@Nonnull
	@Override
	public PriceListAndCurrencyPriceRefIndex createCopyWithMergedTransactionalMemory(@Nullable Void layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
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

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		this.dirty.removeLayer(transactionalLayer);
		this.terminated.removeLayer(transactionalLayer);
		this.priceRecords.removeLayer(transactionalLayer);
		this.indexedPriceEntityIds.removeLayer(transactionalLayer);
		this.indexedPriceIds.removeLayer(transactionalLayer);
		this.validityIndex.removeLayer(transactionalLayer);
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

}
