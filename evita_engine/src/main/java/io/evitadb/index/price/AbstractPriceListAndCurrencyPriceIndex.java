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
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.index.range.RangeIndex;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Comparator;

import static io.evitadb.core.transaction.Transaction.isTransactionAvailable;

/**
 * Abstract base class for price list and currency price indexes. Contains shared fields and methods
 * common to both {@link PriceListAndCurrencyPriceSuperIndex} (catalog-wide, holds full data) and
 * {@link PriceListAndCurrencyPriceRefIndex} (per-scope, holds minimal data and delegates to super index).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public abstract class AbstractPriceListAndCurrencyPriceIndex<SELF extends AbstractPriceListAndCurrencyPriceIndex<SELF>>
	implements VoidTransactionMemoryProducer<SELF>,
	PriceListAndCurrencyPriceIndex<Void, SELF>,
	IndexDataStructure, Serializable {

	@Serial private static final long serialVersionUID = -4718293650182734951L;
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * This is internal flag that tracks whether the index contents became dirty and needs to be persisted.
	 */
	protected final TransactionalBoolean dirty;
	/**
	 * Unique identification of this index - contains price list name and currency combination.
	 */
	@Getter protected final PriceIndexKey priceIndexKey;
	/**
	 * Bitmap contains all entity ids known to this index. This bitmap represents superset of all inner bitmaps.
	 * May be initialized late in subclasses (e.g. during catalog attachment).
	 */
	protected TransactionalBitmap indexedPriceEntityIds;
	/**
	 * Field contains condensed bitmap of all {@link PriceRecordContract#internalPriceId()}
	 * for the sake of the faster search for appropriate {@link PriceRecordContract} by the internal price id.
	 */
	protected final TransactionalBitmap indexedPriceIds;
	/**
	 * Range index contains date-time validity information for each indexed price id. This index is used to process
	 * the {@link io.evitadb.api.query.filter.PriceValidIn} filtering query.
	 */
	protected final RangeIndex validityIndex;
	/**
	 * Array contains complete information about prices sorted by {@link io.evitadb.api.requestResponse.data.PriceContract#priceId()}
	 * allowing translation of price id to entity primary key using binary search algorithm.
	 * May be initialized late in subclasses (e.g. during catalog attachment).
	 */
	protected TransactionalObjArray<PriceRecordContract> priceRecords;
	/**
	 * Contains flags that makes the index terminated and unusable.
	 */
	protected final TransactionalBoolean terminated;
	/**
	 * Contains cached result of {@link TransactionalBitmap#getArray()} call.
	 */
	@Nullable protected int[] memoizedIndexedPriceIds;

	/**
	 * Creates an empty index for the given price index key.
	 */
	protected AbstractPriceListAndCurrencyPriceIndex(@Nonnull PriceIndexKey priceIndexKey) {
		this.dirty = new TransactionalBoolean();
		this.terminated = new TransactionalBoolean();
		this.indexedPriceEntityIds = new TransactionalBitmap();
		this.indexedPriceIds = new TransactionalBitmap();
		this.priceIndexKey = priceIndexKey;
		this.validityIndex = new RangeIndex();
		this.priceRecords = new TransactionalObjArray<>(new PriceRecordContract[0], Comparator.naturalOrder());
	}

	/**
	 * Creates an index from deserialized data with full price records.
	 * Computes entity id and price id bitmaps from the price records.
	 */
	protected AbstractPriceListAndCurrencyPriceIndex(
		@Nonnull PriceIndexKey priceIndexKey,
		@Nonnull RangeIndex validityIndex,
		@Nonnull PriceRecordContract[] priceRecords
	) {
		this.dirty = new TransactionalBoolean();
		this.terminated = new TransactionalBoolean();
		this.priceIndexKey = priceIndexKey;
		this.validityIndex = validityIndex;
		this.priceRecords = new TransactionalObjArray<>(priceRecords, Comparator.naturalOrder());

		final int[] priceIds = new int[priceRecords.length];
		final int[] entityIds = new int[priceRecords.length];
		for (int i = 0; i < priceRecords.length; i++) {
			final PriceRecordContract priceRecord = priceRecords[i];
			entityIds[i] = priceRecord.entityPrimaryKey();
			priceIds[i] = priceRecord.internalPriceId();
		}

		this.indexedPriceEntityIds = new TransactionalBitmap(entityIds);
		this.indexedPriceIds = new TransactionalBitmap(priceIds);
		this.memoizedIndexedPriceIds = priceIds;
	}

	/**
	 * Creates a minimal index from deserialized data with only price ids and validity.
	 * Entity ids and price records are left uninitialized and must be set later
	 * (e.g. during catalog attachment in {@link PriceListAndCurrencyPriceRefIndex}).
	 */
	protected AbstractPriceListAndCurrencyPriceIndex(
		@Nonnull PriceIndexKey priceIndexKey,
		@Nonnull RangeIndex validityIndex,
		@Nonnull int[] priceIds
	) {
		this.dirty = new TransactionalBoolean();
		this.terminated = new TransactionalBoolean();
		this.priceIndexKey = priceIndexKey;
		this.validityIndex = validityIndex;
		this.indexedPriceIds = new TransactionalBitmap(priceIds);
		this.memoizedIndexedPriceIds = priceIds;
	}

	/**
	 * Copy constructor for creating a new index with merged transactional memory state.
	 * Used when both entity id bitmap and price records are available.
	 */
	protected AbstractPriceListAndCurrencyPriceIndex(
		@Nonnull PriceIndexKey priceIndexKey,
		@Nonnull Bitmap indexedPriceEntityIds,
		@Nonnull Bitmap priceIds,
		@Nonnull RangeIndex validityIndex,
		@Nonnull PriceRecordContract[] priceRecords
	) {
		this.dirty = new TransactionalBoolean();
		this.terminated = new TransactionalBoolean();
		this.priceIndexKey = priceIndexKey;
		this.indexedPriceEntityIds = new TransactionalBitmap(indexedPriceEntityIds);
		this.indexedPriceIds = new TransactionalBitmap(priceIds);
		this.validityIndex = validityIndex;
		this.priceRecords = new TransactionalObjArray<>(priceRecords, Comparator.naturalOrder());
	}

	/**
	 * Copy constructor for creating a new index with merged transactional memory state.
	 * Used when price records are not maintained locally (e.g. in ref indexes).
	 */
	protected AbstractPriceListAndCurrencyPriceIndex(
		@Nonnull PriceIndexKey priceIndexKey,
		@Nonnull Bitmap indexedPriceEntityIds,
		@Nonnull Bitmap priceIds,
		@Nonnull RangeIndex validityIndex
	) {
		this.dirty = new TransactionalBoolean();
		this.terminated = new TransactionalBoolean();
		this.priceIndexKey = priceIndexKey;
		this.indexedPriceEntityIds = new TransactionalBitmap(indexedPriceEntityIds);
		this.indexedPriceIds = new TransactionalBitmap(priceIds);
		this.validityIndex = validityIndex;
	}

	/**
	 * Shallow copy constructor that preserves existing {@link TransactionalBitmap} instances without re-wrapping.
	 * Used for creating copies for new catalog attachment where the bitmaps are shared with the original.
	 */
	protected AbstractPriceListAndCurrencyPriceIndex(
		@Nonnull PriceIndexKey priceIndexKey,
		@Nonnull TransactionalBitmap indexedPriceEntityIds,
		@Nonnull TransactionalBitmap priceIds,
		@Nonnull RangeIndex validityIndex
	) {
		this.dirty = new TransactionalBoolean();
		this.terminated = new TransactionalBoolean();
		this.priceIndexKey = priceIndexKey;
		this.indexedPriceEntityIds = indexedPriceEntityIds;
		this.indexedPriceIds = priceIds;
		this.validityIndex = validityIndex;
	}

	@Nonnull
	@Override
	public Bitmap getIndexedPriceEntityIds() {
		assertNotTerminated();
		return this.indexedPriceEntityIds;
	}

	/**
	 * Method returns condensed bitmap of all {@link PriceRecordContract#internalPriceId()}
	 * that can be used for the faster search for appropriate {@link PriceRecordContract} by the internal price id.
	 */
	@Nonnull
	@Override
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

	@Override
	public void resetDirty() {
		assertNotTerminated();
		this.dirty.reset();
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		this.dirty.removeLayer(transactionalLayer);
		this.terminated.removeLayer(transactionalLayer);
		this.indexedPriceEntityIds.removeLayer(transactionalLayer);
		this.indexedPriceIds.removeLayer(transactionalLayer);
		this.validityIndex.removeLayer(transactionalLayer);
		this.priceRecords.removeLayer(transactionalLayer);
	}

	/**
	 * Adds a validity range record for the given internal price id to the validity index.
	 * If validity is null, the price is considered always valid (MIN_VALUE to MAX_VALUE).
	 */
	protected void addValidity(@Nullable DateTimeRange validity, int internalPriceId) {
		if (validity != null) {
			this.validityIndex.addRecord(validity.getFrom(), validity.getTo(), internalPriceId);
		} else {
			this.validityIndex.addRecord(Long.MIN_VALUE, Long.MAX_VALUE, internalPriceId);
		}
	}

	/**
	 * Removes a validity range record for the given internal price id from the validity index.
	 * If validity is null, removes the always-valid range (MIN_VALUE to MAX_VALUE).
	 */
	protected void removeValidity(@Nullable DateTimeRange validity, int internalPriceId) {
		if (validity != null) {
			this.validityIndex.removeRecord(validity.getFrom(), validity.getTo(), internalPriceId);
		} else {
			this.validityIndex.removeRecord(Long.MIN_VALUE, Long.MAX_VALUE, internalPriceId);
		}
	}

	/**
	 * Marks the index as dirty and invalidates the memoized price ids cache.
	 * Should be called after any mutation (add/remove price).
	 */
	protected void markDirtyAndInvalidateCache() {
		this.dirty.setToTrue();
		if (!isTransactionAvailable()) {
			this.memoizedIndexedPriceIds = null;
		}
	}

	/**
	 * Verifies that the index is not terminated.
	 *
	 * @throws PriceListAndCurrencyPriceIndexTerminated if the index has been terminated
	 */
	protected void assertNotTerminated() {
		if (this.terminated.isTrue()) {
			throw new PriceListAndCurrencyPriceIndexTerminated(
				"Price list and currency index " + this.priceIndexKey + " is terminated!"
			);
		}
	}

}
