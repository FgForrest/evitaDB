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
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords;
import io.evitadb.core.query.algebra.price.priceIndex.PriceIdContainerFormula;
import io.evitadb.core.query.algebra.price.priceIndex.PriceIndexContainerFormula;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.bPlusTree.TransactionalElementBPlusTree;
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
import java.util.Iterator;
import java.util.PrimitiveIterator.OfInt;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.ToIntFunction;

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
	PriceListAndCurrencyPriceIndex<SELF>,
	IndexDataStructure, Serializable {

	/**
	 * Shared empty array reused by callers that need to return "no price records" without per-call
	 * allocation. Package-private so siblings in this package (notably
	 * {@link FilteredPriceRecords#mergePerInnerRecordHistogramRecords}) can hand it back from the
	 * zero-total fast path.
	 */
	public static final PriceRecordContract[] EMPTY_PRICE_RECORDS = new PriceRecordContract[0];
	@Serial private static final long serialVersionUID = -4718293650182734951L;
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * Derives the ordering / lookup key of a price record — its {@link PriceRecordContract#internalPriceId()} — for the
	 * element-keyed {@link #priceRecords} tree.
	 */
	private static final ToIntFunction<PriceRecordContract> PRICE_RECORD_KEY = PriceRecordContract::internalPriceId;
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
	 * Element-keyed B+ tree holding complete information about prices ordered by
	 * {@link PriceRecordContract#internalPriceId()}, allowing translation of an internal price id to its price record
	 * (and entity primary key) by a `O(log n)` tree lookup. Unlike a flat array it persists as individual leaf pages
	 * (only the changed leaves are rewritten per commit — see the granular page-emission framework on the super index)
	 * instead of one monolithic blob. May be initialized late in subclasses (e.g. during catalog attachment).
	 */
	protected TransactionalElementBPlusTree<PriceRecordContract> priceRecords;
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
		this.priceRecords = newPriceRecordTree();
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
		this.priceRecords = newPriceRecordTree(priceRecords);

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
	 * Copy constructor for creating a new index with merged transactional memory state, adopting the already-merged
	 * committed {@link #priceRecords} tree BY REFERENCE (the tree performed its own `O(Δ)` commit-merge). Used by
	 * {@link PriceListAndCurrencyPriceSuperIndex#createCopyWithMergedTransactionalMemory} so the surviving committed
	 * owner keeps the same tree instance (and its owner-resident page bookkeeping) across commits.
	 */
	protected AbstractPriceListAndCurrencyPriceIndex(
		@Nonnull PriceIndexKey priceIndexKey,
		@Nonnull Bitmap indexedPriceEntityIds,
		@Nonnull Bitmap priceIds,
		@Nonnull RangeIndex validityIndex,
		@Nonnull TransactionalElementBPlusTree<PriceRecordContract> priceRecords
	) {
		this.dirty = new TransactionalBoolean();
		this.terminated = new TransactionalBoolean();
		this.priceIndexKey = priceIndexKey;
		this.indexedPriceEntityIds = new TransactionalBitmap(indexedPriceEntityIds);
		this.indexedPriceIds = new TransactionalBitmap(priceIds);
		this.validityIndex = validityIndex;
		this.priceRecords = priceRecords;
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

	/**
	 * Shallow copy constructor that preserves the existing {@link TransactionalBitmap} instances AND the already-built
	 * {@link #priceRecords} tree BY REFERENCE (no re-wrapping, no rebuild). Used for new catalog attachment of a ref index
	 * where the re-attachment is purely in-memory and the super index's {@link PriceRecordContract} instances are carried
	 * forward, so the derived tree stays valid and need not be reconstructed. Mirrors the no-tree shallow constructor
	 * above; sharing the transactional structures by reference matches how the bitmaps are carried on this path.
	 */
	protected AbstractPriceListAndCurrencyPriceIndex(
		@Nonnull PriceIndexKey priceIndexKey,
		@Nonnull TransactionalBitmap indexedPriceEntityIds,
		@Nonnull TransactionalBitmap priceIds,
		@Nonnull RangeIndex validityIndex,
		@Nonnull TransactionalElementBPlusTree<PriceRecordContract> priceRecords
	) {
		this.dirty = new TransactionalBoolean();
		this.terminated = new TransactionalBoolean();
		this.priceIndexKey = priceIndexKey;
		this.indexedPriceEntityIds = indexedPriceEntityIds;
		this.indexedPriceIds = priceIds;
		this.validityIndex = validityIndex;
		this.priceRecords = priceRecords;
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
		return this.priceRecords.toArray();
	}

	/**
	 * Streams the price records for the passed ascending bitmap of internal price ids, picking the strategy by the
	 * filter's selectivity — without ever materializing the whole price array. A SPARSE filter (`m · log n < n`, the
	 * typical price case where the matched internal price ids are scattered) resolves each id by a direct `O(log n)` tree
	 * search; a DENSE filter is resolved by a single forward merge-join that walks the tree and the ascending ids in
	 * lockstep (`O(n + m)`). Found records are reported to `priceFoundCallback` in ascending key order; ids absent from
	 * this index are reported to `priceIdNotFoundCallback`. Overrides the array-positional default in
	 * {@link PriceListAndCurrencyPriceIndex} now that the records live in a tree rather than a flat array.
	 */
	@Override
	public void forEachPriceRecord(
		@Nonnull Bitmap priceIds,
		@Nonnull Consumer<PriceRecordContract> priceFoundCallback,
		@Nonnull IntConsumer priceIdNotFoundCallback
	) throws PriceListAndCurrencyPriceIndexTerminated {
		assertNotTerminated();
		if (priceIds.isEmpty()) {
			return;
		}
		final int filterSize = priceIds.size();
		final int indexSize = this.indexedPriceIds.size();
		final int log2IndexSize = Math.max(1, 32 - Integer.numberOfLeadingZeros(Math.max(1, indexSize)));
		final OfInt idIterator = priceIds.iterator();
		if ((long) filterSize * log2IndexSize < indexSize) {
			// SPARSE filter: m direct point lookups (O(m · log n)) beat a full merge-join walk that would touch every one
			// of the n records to find only m of them — the common price case, since the matched internal price ids are
			// scattered across the id space (price-value order is unrelated to internal-price-id order)
			while (idIterator.hasNext()) {
				final int wantedId = idIterator.nextInt();
				final PriceRecordContract record = this.priceRecords.search(wantedId);
				if (record != null) {
					priceFoundCallback.accept(record);
				} else {
					priceIdNotFoundCallback.accept(wantedId);
				}
			}
		} else {
			// DENSE filter: one forward merge-join over the tree, zipping the ascending tree walk against the ascending
			// filtered ids so each side is visited at most once (O(n + m))
			// seed the tree walk at the first wanted id (skips every record below it in O(log n))
			int wantedId = idIterator.nextInt();
			final Iterator<PriceRecordContract> recordIterator = this.priceRecords.greaterOrEqualValueIterator(
				wantedId);
			PriceRecordContract current = recordIterator.hasNext() ? recordIterator.next() : null;
			boolean hasWanted = true;
			while (hasWanted) {
				// advance the tree cursor to the first record whose key is >= the wanted id
				while (current != null && current.internalPriceId() < wantedId) {
					current = recordIterator.hasNext() ? recordIterator.next() : null;
				}
				if (current != null && current.internalPriceId() == wantedId) {
					// match: the next wanted id is strictly greater, so the inner loop advances `current` past it next round
					priceFoundCallback.accept(current);
				} else {
					priceIdNotFoundCallback.accept(wantedId);
				}
				if (idIterator.hasNext()) {
					wantedId = idIterator.nextInt();
				} else {
					hasWanted = false;
				}
			}
		}
	}

	@Nonnull
	@Override
	public Formula createPriceIndexFormulaWithAllRecords(@Nonnull PriceSuperIndex superPriceIndex) {
		assertNotTerminated();
		return new PriceIndexContainerFormula(
			this, resolveLowestPriceRecordsSource(superPriceIndex), this.getIndexedPriceEntityIdsFormula()
		);
	}

	/**
	 * Resolves the index that answers {@link #getLowestPriceRecordsForEntity(int)} for this combination - always a
	 * {@link PriceListAndCurrencyPriceSuperIndex}, since the entity-to-prices mapping the lowest-price computation needs
	 * lives only there.
	 *
	 * @param superPriceIndex the price index of the GLOBAL entity index of this index's collection and scope
	 * @return the super index of this price-list / currency combination
	 */
	@Nonnull
	protected abstract PriceListAndCurrencyPriceSuperIndex resolveLowestPriceRecordsSource(
		@Nonnull PriceSuperIndex superPriceIndex
	);

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
	 * Builds an empty element-keyed B+ tree backing {@link #priceRecords}, keyed by
	 * {@link PriceRecordContract#internalPriceId()}.
	 *
	 * @return a fresh empty price-record tree
	 */
	@Nonnull
	protected static TransactionalElementBPlusTree<PriceRecordContract> newPriceRecordTree() {
		return new TransactionalElementBPlusTree<>(PriceRecordContract.class, PRICE_RECORD_KEY);
	}

	/**
	 * Builds the element-keyed B+ tree backing {@link #priceRecords} from a (deserialized) price-record array, inserting
	 * each record under its internal price id. The array is expected ascending by internal price id, so the inserts land
	 * at the right edge and never need to re-sort.
	 *
	 * @param priceRecords the price records to seed the tree with
	 * @return a price-record tree holding every passed record
	 */
	@Nonnull
	protected static TransactionalElementBPlusTree<PriceRecordContract> newPriceRecordTree(
		@Nonnull PriceRecordContract[] priceRecords
	) {
		final TransactionalElementBPlusTree<PriceRecordContract> tree = newPriceRecordTree();
		for (final PriceRecordContract priceRecord : priceRecords) {
			tree.insert(priceRecord);
		}
		return tree;
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
