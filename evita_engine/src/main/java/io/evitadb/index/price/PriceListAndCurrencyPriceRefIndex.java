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

import io.evitadb.core.query.algebra.price.priceIndex.PriceIdContainerFormula;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.bPlusTree.TransactionalElementBPlusTree;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.entityPrices.EntityPrices;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.PriceListAndCurrencyRefIndexStoragePart;
import io.evitadb.utils.StringUtils;
import io.evitadb.utils.VMLayout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Index contains information used for filtering by price that is related to specific price list and currency combination.
 * Real world use-cases usually filter entities by price in certain currency in set of price lists, and we can greatly
 * minimize the working set by separating price indexes by this combination.
 *
 * RefIndex attempts to store minimal data set in order to save memory on heap. For memory expensive objects such as
 * {@link PriceRecord} and {@link EntityPrices} it relies on the {@link PriceListAndCurrencyPriceSuperIndex} of the same
 * combination, which the caller supplies per operation. This index keeps no pointer to *the super index object*, so it
 * carries no catalog-version pin and can be forwarded across catalog versions by reference.
 *
 * **That is a statement about the super index, not about the records.** This index does hold the shared payload:
 * {@link AbstractPriceListAndCurrencyPriceIndex#priceRecords} is its own tree, but its elements are the very same
 * {@link PriceRecord} instances the super index owns - created once on the add-price path, carried forward by the copy
 * constructor, and reconstructed onto those same instances by
 * {@link #restorePriceRecordsFrom(PriceListAndCurrencyPriceSuperIndex)} after a disk-load attach. Reading "keeps no
 * pointer" as "shares nothing" is the trap, and it is load-bearing for heap accounting: a reduced index owns its tree
 * **spine** - the nodes and the reference slots - while the price bodies belong to the super index alone, so only the
 * super index may ever charge them. Counting them here too would multiply the whole price payload by the number of
 * reference-reduced indexes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class PriceListAndCurrencyPriceRefIndex
	extends AbstractPriceListAndCurrencyPriceIndex<PriceListAndCurrencyPriceRefIndex> {

	@Serial private static final long serialVersionUID = 182980639981206272L;
	/**
	 * Captures the scope of the index and reflects the {@link EntityIndexKey#scope()} of the main entity index this
	 * price index is part of.
	 */
	private final Scope scope;

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

	/**
	 * Copy constructor used by {@link #createCopyWithMergedTransactionalMemory} that adopts the already-merged committed
	 * {@link #priceRecords} tree BY REFERENCE. The ref tree holds the very same shared {@link PriceRecord} instances as
	 * the super index (created once in the add-price path), so a commit carries it forward instead of rebuilding it from
	 * the super index — only a disk-load attach reconstructs it (see
	 * {@link #restorePriceRecordsFrom(PriceListAndCurrencyPriceSuperIndex)}).
	 */
	private PriceListAndCurrencyPriceRefIndex(
		@Nonnull Scope scope,
		@Nonnull PriceIndexKey priceIndexKey,
		@Nonnull Bitmap indexedPriceEntityIds,
		@Nonnull Bitmap priceIds,
		@Nonnull RangeIndex validityIndex,
		@Nonnull TransactionalElementBPlusTree<PriceRecordContract> priceRecords
	) {
		super(priceIndexKey, indexedPriceEntityIds, priceIds, validityIndex, priceRecords);
		this.scope = scope;
	}

	/**
	 * Reconstructs the price-record tree of a ref index that has just been **deserialized from disk**, by pointing it at
	 * the {@link PriceRecord} instances the passed super index already holds on the heap.
	 *
	 * The ref index persists only its price ids and validity, never the memory-expensive records, so a disk-load attach
	 * leaves the tree `null` and it has to be rebuilt - the same step that collapses Kryo's per-index duplicate records
	 * back onto the single shared instances.
	 *
	 * The `null` guard is load-bearing, not defensive: on every other path the tree arrives already populated with those
	 * same shared instances (see {@link #createCopyWithMergedTransactionalMemory}), and rebuilding it there would in fact
	 * throw, because the finalized commit-merge forbids opening a fresh transactional layer for the insert loop.
	 *
	 * @param superIndex the super price index of the owning collection's GLOBAL entity index for this price-list /
	 *                   currency combination
	 */
	public void restorePriceRecordsFrom(@Nonnull PriceListAndCurrencyPriceSuperIndex superIndex) {
		assertNotTerminated();
		if (this.priceRecords == null) {
			final PriceRecordContract[] priceRecords = superIndex.getPriceRecords(this.indexedPriceIds);
			this.priceRecords = newPriceRecordTree(priceRecords);

			final int[] entityIds = new int[priceRecords.length];
			for (int i = 0; i < priceRecords.length; i++) {
				final PriceRecordContract priceRecord = priceRecords[i];
				entityIds[i] = priceRecord.entityPrimaryKey();
			}
			this.indexedPriceEntityIds = new TransactionalBitmap(entityIds);
		}
	}

	/**
	 * Resolves the index answering {@link #getLowestPriceRecordsForEntity(int)} for this combination: the super index of
	 * the very same price-list / currency combination, taken from the GLOBAL price index the caller supplies.
	 */
	@Nonnull
	@Override
	protected PriceListAndCurrencyPriceSuperIndex resolveLowestPriceRecordsSource(@Nonnull PriceSuperIndex superPriceIndex) {
		return superPriceIndex.getPriceIndexOrThrow(this.priceIndexKey);
	}

	/**
	 * Indexes inner record id or entity primary key into the price index with passed values.
	 *
	 * @param superIndex the super index of this price-list / currency combination, holding the shared
	 *                   {@link PriceRecord} instances this index only references
	 */
	@Nonnull
	public PriceRecordContract addPrice(
		@Nonnull Integer internalPriceId,
		@Nullable DateTimeRange validity,
		@Nonnull PriceListAndCurrencyPriceSuperIndex superIndex
	) {
		assertNotTerminated();
		final int ipId = Objects.requireNonNull(internalPriceId);
		final PriceRecordContract priceRecord = superIndex.getPriceRecord(ipId);

		// index the presence of the record
		this.indexedPriceEntityIds.add(priceRecord.entityPrimaryKey());
		this.indexedPriceIds.add(priceRecord.internalPriceId());
		// index validity
		addValidity(validity, priceRecord.internalPriceId());
		// add price to the translation tree (keyed by internal price id)
		this.priceRecords.insert(priceRecord);
		// make index dirty
		markDirty();

		return priceRecord;
	}

	/**
	 * Removes inner record id or entity primary key of passed values from the price index.
	 *
	 * @param superIndex the super index of this price-list / currency combination, holding the shared
	 *                   {@link PriceRecord} and {@link EntityPrices} instances this index only references
	 */
	@Nonnull
	public PriceRecordContract removePrice(
		@Nonnull Integer internalPriceId,
		@Nullable DateTimeRange validity,
		@Nonnull PriceListAndCurrencyPriceSuperIndex superIndex
	) {
		assertNotTerminated();
		final int ipId = Objects.requireNonNull(internalPriceId);
		final PriceRecordContract priceRecord = superIndex.getPriceRecord(ipId);
		final EntityPrices entityPrices = superIndex.getEntityPrices(priceRecord.entityPrimaryKey());

		// remove price from the translation tree (keyed by internal price id)
		this.priceRecords.delete(priceRecord.internalPriceId());

		// remove the presence of the record
		this.indexedPriceIds.remove(priceRecord.internalPriceId());

		if (!containsAnyPriceOf(entityPrices)) {
			// remove the presence of the record
			this.indexedPriceEntityIds.remove(priceRecord.entityPrimaryKey());
		}
		// remove validity
		removeValidity(validity, priceRecord.internalPriceId());
		// make index dirty
		markDirty();

		return priceRecord;
	}

	/**
	 * Tells whether any price of `entityPrices` is still present in this index's price-record tree.
	 *
	 * The entity holds a handful of prices while the tree holds every price record of the whole
	 * price-list/currency combination, so the containment is resolved by probing the tree for each of
	 * the entity's internal price ids rather than by materializing the tree and scanning it - the
	 * latter is O(tree size) in both time and allocation on a path that runs once per removed price.
	 *
	 * The ids are read one at a time through {@link EntityPrices#getInternalPriceId(int)} rather than through
	 * {@link EntityPrices#getInternalPriceIds()}, because the single-price holder - the shape almost every entity
	 * has - keeps its id as a plain field and would have to build a one-element array to answer the array form.
	 *
	 * @param entityPrices prices of the entity whose price is being removed
	 * @return true when at least one price of the entity remains indexed here
	 */
	private boolean containsAnyPriceOf(@Nonnull EntityPrices entityPrices) {
		final int priceCount = entityPrices.getSize();
		for (int i = 0; i < priceCount; i++) {
			if (this.priceRecords.search(entityPrices.getInternalPriceId(i)) != null) {
				return true;
			}
		}
		return false;
	}

	@Nonnull
	@Override
	public PriceIdContainerFormula getIndexedRecordIdsValidNowFormula(@Nonnull OffsetDateTime theMoment) {
		assertNotTerminated();
		final long thePoint = DateTimeRange.toComparableLong(theMoment);
		return new PriceIdContainerFormula(
			this, this.validityIndex.getRecordsValidNowFormula(thePoint)
		);
	}

	/**
	 * Neither of the two entity-keyed lookups can be answered by a reduced index: both need the entity-to-prices
	 * mapping, which only a {@link PriceListAndCurrencyPriceSuperIndex} owns. They used to be forwarded to a super
	 * index this index held a pointer to; that pointer is gone, and the caller now resolves the super index itself
	 * (see {@link #resolveLowestPriceRecordsSource(PriceSuperIndex)}, which is what
	 * {@link #createPriceIndexFormulaWithAllRecords(PriceSuperIndex)} routes the lookup through).
	 *
	 * Reaching this method therefore means a caller obtained the lowest-price lookup from the reduced index rather
	 * than from the super index that backs it - a programming error, not a state to work around.
	 *
	 * The streaming variant {@link #forEachLowestPriceRecordOfEntity(int, java.util.function.Consumer)} is deliberately
	 * left at its interface default, which routes through the array form below and therefore rejects the caller with
	 * exactly the same error.
	 */
	@Nullable
	@Override
	public int[] getInternalPriceIdsForEntity(int entityId) {
		throw new GenericEvitaInternalError(
			"Reduced price index `" + this.priceIndexKey + "` cannot resolve prices of an entity - the entity-to-prices " +
				"mapping lives in the super price index, which the caller must resolve and query directly!"
		);
	}

	@Override
	@Nullable
	public PriceRecordContract[] getLowestPriceRecordsForEntity(int entityId) {
		throw new GenericEvitaInternalError(
			"Reduced price index `" + this.priceIndexKey + "` cannot resolve the lowest prices of an entity - the " +
				"entity-to-prices mapping lives in the super price index, which the caller must resolve and query directly!"
		);
	}

	@Nullable
	@Override
	public StoragePart createStoragePart(int entityIndexPrimaryKey) {
		if (this.dirty.isTrue()) {
			// the indexed price-id bitmap is kept in lockstep with the price-record tree (every add/remove updates both),
			// so it already holds exactly the internal price ids to persist, ascending — no need to walk the tree
			return new PriceListAndCurrencyRefIndexStoragePart(
				entityIndexPrimaryKey, this.priceIndexKey, this.validityIndex, this.indexedPriceIds.getArray()
			);
		} else {
			return null;
		}
	}

	@Override
	public String toString() {
		return StringUtils.capitalize(this.scope.name().toLowerCase()) + " " + this.priceIndexKey.toString() + (isTerminated() ? " (TERMINATED)" : "");
	}

	/**
	 * Returns the heap this index occupies, in bytes — its tree **spine only**, never the price record bodies.
	 *
	 * This is the canonical borrowed-structure case. A reference index is built by copying references out of the
	 * {@link PriceListAndCurrencyPriceSuperIndex} for its price list and currency, so its `priceRecords` tree holds the
	 * very same {@link io.evitadb.index.price.model.priceRecord.PriceRecordContract} instances the super index owns and
	 * charges. Pricing them here as well would bill every price record once more for each scope and reduced index that
	 * references it — a figure that would grow with the number of *views* of the data rather than with the data.
	 *
	 * {@link #scope} is an enum constant owned by the JVM for the lifetime of its class loader, so it contributes its
	 * slot alone.
	 *
	 * Like every tree walk this is `O(nodes)` rather than `O(1)`, so it belongs to the index detail call and must never
	 * be called from a query path.
	 *
	 * @return the owned heap footprint in bytes, including alignment padding
	 */
	public long getHeapSizeInBytes() {
		// the scope slot, on top of the base's own fields
		return getBaseHeapSizeInBytes(priceRecord -> 0L, VMLayout.current().referenceSize());
	}

	@Nonnull
	@Override
	public PriceListAndCurrencyPriceRefIndex createCopyWithMergedTransactionalMemory(
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		// we can safely throw away dirty flag now
		this.dirty.removeLayer(transactionalLayer);
		this.terminated.removeLayer(transactionalLayer);
		// carry the price-record tree forward by adopting its own committed merge rather than dropping it: the ref tree
		// already holds the SAME shared PriceRecord instances as the super index (the add-price path created each record
		// once and inserted that single reference into both indexes), so it stays correct across the commit with no
		// rebuild-from-super. This both removes the wasteful per-commit insert-loop rebuild and keeps clear of the
		// finalized-transaction guard, since getStateCopyWithCommittedChanges only MERGES the existing layer (it never
		// creates a fresh one the way the rebuild's inserts would).
		final TransactionalElementBPlusTree<PriceRecordContract> committedPriceRecords =
			transactionalLayer.getStateCopyWithCommittedChanges(this.priceRecords);
		return new PriceListAndCurrencyPriceRefIndex(
			this.scope,
			this.priceIndexKey,
			transactionalLayer.getStateCopyWithCommittedChanges(this.indexedPriceEntityIds),
			transactionalLayer.getStateCopyWithCommittedChanges(this.indexedPriceIds),
			transactionalLayer.getStateCopyWithCommittedChanges(this.validityIndex),
			committedPriceRecords
		);
	}

}
