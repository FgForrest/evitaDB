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
import io.evitadb.core.query.algebra.price.priceIndex.PriceIdContainerFormula;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
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

	/**
	 * Copy constructor used by {@link #createCopyForNewCatalogAttachment} that shares the existing
	 * {@link io.evitadb.index.bitmap.TransactionalBitmap} instances AND the derived {@link #priceRecords} tree BY
	 * REFERENCE. The in-memory re-attachment keeps the super index's {@link PriceRecord} instances, so the carried tree
	 * stays valid and attach need not rebuild it.
	 */
	private PriceListAndCurrencyPriceRefIndex(
		@Nonnull Scope scope,
		@Nonnull PriceIndexKey priceIndexKey,
		@Nonnull TransactionalBitmap indexedPriceEntityIds,
		@Nonnull TransactionalBitmap priceIds,
		@Nonnull RangeIndex validityIndex,
		@Nonnull TransactionalElementBPlusTree<PriceRecordContract> priceRecords
	) {
		super(priceIndexKey, indexedPriceEntityIds, priceIds, validityIndex, priceRecords);
		this.scope = scope;
	}

	/**
	 * Copy constructor used by {@link #createCopyWithMergedTransactionalMemory} that adopts the already-merged committed
	 * {@link #priceRecords} tree BY REFERENCE. The ref tree holds the very same shared {@link PriceRecord} instances as
	 * the super index (created once in the add-price path), so a commit carries it forward instead of rebuilding it from
	 * the super index — only a disk-load attach reconstructs it (see {@link #attachToCatalog(String, Catalog)}).
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

	@Override
	public void attachToCatalog(@Nullable String entityType, @Nonnull Catalog catalog) {
		assertNotTerminated();
		Assert.isPremiseValid(entityType != null, "Entity type must be provided!");
		Assert.isPremiseValid(this.superIndex == null, "Catalog was already attached to this index!");
		final PriceListAndCurrencyPriceIndex<?> superIndex = catalog.getEntityIndexIfExists(
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

	@Nonnull
	@Override
	public PriceListAndCurrencyPriceRefIndex createCopyForNewCatalogAttachment(@Nonnull CatalogState catalogState) {
		assertNotTerminated();
		// carry the derived price-record tree forward by reference rather than dropping it: this is a purely in-memory
		// re-attachment (goLive / persistence-service swap / collection replace), where the GLOBAL entity index — and with
		// it the super price index's PriceRecord instances — is carried by reference, NOT reloaded from disk (see
		// EntityCollection#createIndexCopiesForNewCatalogAttachment). The carried tree therefore still points at exactly the
		// instances the re-resolved super index holds, so attachToCatalog can skip the rebuild (only the disk-load path,
		// which deserializes a null tree, must reconstruct it). Dropping the tree here would force that rebuild's insert
		// loop to run during the finalized commit-merge, where #569's guard forbids creating a new transactional layer.
		return new PriceListAndCurrencyPriceRefIndex(
			this.scope,
			this.priceIndexKey,
			this.indexedPriceEntityIds,
			this.indexedPriceIds,
			this.validityIndex,
			this.priceRecords
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
		// add price to the translation tree (keyed by internal price id)
		this.priceRecords.insert(priceRecord);
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

}
