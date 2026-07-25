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
import io.evitadb.core.query.algebra.price.priceIndex.PriceIdContainerFormula;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
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
import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;

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
 * {@link PriceRecord} and {@link EntityPrices} it looks up via {@link #superIndex} where the records are located.
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
	/**
	 * Reference to the main {@link PriceListAndCurrencyPriceSuperIndex} that keeps memory expensive objects, which
	 * is wired in by the owning entity collection through {@link #wireSuperIndex(PriceListAndCurrencyPriceSuperIndex)}.
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

	/**
	 * Copy constructor used by {@link #createCopyWithMergedTransactionalMemory} that adopts the already-merged committed
	 * {@link #priceRecords} tree BY REFERENCE. The ref tree holds the very same shared {@link PriceRecord} instances as
	 * the super index (created once in the add-price path), so a commit carries it forward instead of rebuilding it from
	 * the super index — only a disk-load attach reconstructs it (see
	 * {@link #wireSuperIndex(PriceListAndCurrencyPriceSuperIndex)}).
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
	 * Wires the memory-expensive {@link PriceListAndCurrencyPriceSuperIndex} that backs this reduced ref index. The
	 * owning entity collection resolves the super index from its own {@link GlobalEntityIndex} (same scope, same
	 * collection) and passes it here — no {@link Catalog} back-reference is retained, so this ref index can be carried
	 * across catalog versions by reference once its super instance is likewise carried.
	 *
	 * @param superIndex the super price index of the owning collection's GLOBAL entity index for this price-list /
	 *                   currency combination
	 */
	public void wireSuperIndex(@Nonnull PriceListAndCurrencyPriceSuperIndex superIndex) {
		assertNotTerminated();
		Assert.isPremiseValid(this.superIndex == null, "Super index was already wired to this index!");
		this.superIndex = superIndex;
		// the price-record tree is rebuilt from the super index ONLY on a disk-load attach, where deserialization left it
		// null (the ref index persists just its price ids + validity, never the memory-expensive PriceRecord objects, so
		// the tree must be reconstructed by pointing at the super index's existing heap instances — the dedup that collapses
		// Kryo's per-index duplicate records back onto the single shared instances). A transactional commit carries the tree
		// forward already populated with those same shared instances (createCopyWithMergedTransactionalMemory), so it needs
		// no rebuild here — and rebuilding would in fact throw, since the finalized commit-merge forbids creating a fresh
		// transactional layer for the insert loop.
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
		markDirtyAndInvalidateCache();

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
		markDirtyAndInvalidateCache();

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
	 * @param entityPrices prices of the entity whose price is being removed
	 * @return true when at least one price of the entity remains indexed here
	 */
	private boolean containsAnyPriceOf(@Nonnull EntityPrices entityPrices) {
		final int[] internalPriceIds = entityPrices.getInternalPriceIds();
		for (final int internalPriceId : internalPriceIds) {
			if (this.priceRecords.search(internalPriceId) != null) {
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

	/**
	 * Produces an unwired shallow copy of this **clean** combination index for the commit-merge prune: it adopts every
	 * sub-structure ({@link #indexedPriceEntityIds}, {@link #indexedPriceIds}, {@link #validityIndex},
	 * {@link #priceRecords}) BY REFERENCE — exactly the carry-forward the transactional merge performs, minus the layer
	 * merge (there is none to merge on a clean index). The returned copy has no super index wired ({@code superIndex ==
	 * null}); the caller must wire it to the CURRENT catalog version's super via {@link #wireSuperIndex} so its price
	 * records resolve through the live super instance.
	 *
	 * This is the price-spine half of carrying a clean reduced entity index across a catalog version whose GLOBAL was
	 * rebuilt: the reduced index cannot be shared by reference (its price chain would keep pointing at the retired
	 * GLOBAL's super), yet rebuilding the whole index is wasteful, so only the thin combo wrappers are re-shelled while
	 * the memory-expensive record tree is shared.
	 *
	 * @return a fresh, unwired combination index sharing this one's committed sub-structures by reference
	 */
	@Nonnull
	public PriceListAndCurrencyPriceRefIndex createCarryByReferenceCopy() {
		assertNotTerminated();
		return new PriceListAndCurrencyPriceRefIndex(
			this.scope,
			this.priceIndexKey,
			this.indexedPriceEntityIds,
			this.indexedPriceIds,
			this.validityIndex,
			this.priceRecords
		);
	}

}
