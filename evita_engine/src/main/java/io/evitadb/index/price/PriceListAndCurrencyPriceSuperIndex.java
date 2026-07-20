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

import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.exception.PriceAlreadyAssignedToEntityException;
import io.evitadb.core.query.algebra.price.priceIndex.PriceIdContainerFormula;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.champ.ChampMap;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.bPlusTree.TransactionalElementBPlusTree;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.map.PersistentTransactionalMap;
import io.evitadb.index.page.PageEmission;
import io.evitadb.index.page.PageStreamRegistry;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.entityPrices.EntityPrices;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.PriceListAndCurrencySuperIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.PriceListAndCurrencySuperIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.PriceListAndCurrencySuperIndexStoragePart;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.evitadb.utils.CollectionUtils.createHashMap;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * Index contains information used for filtering by price that is related to specific price list and currency combination.
 * Real world use-cases usually filter entities by price in certain currency in set of price lists, and we can greatly
 * minimize the working set by separating price indexes by this combination.
 *
 * There is exactly one super index per price list / currency combination in a {@link Catalog}; it
 * holds memory-expensive objects such as {@link PriceRecord} and {@link EntityPrices}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class PriceListAndCurrencyPriceSuperIndex
	extends AbstractPriceListAndCurrencyPriceIndex<PriceListAndCurrencyPriceSuperIndex> {

	@Serial private static final long serialVersionUID = 182980639981206272L;
	/**
	 * Contains the same information as in {@link #priceRecords}, but indexed by entityId. Backed by a persistent
	 * immutable {@link ChampMap} via {@link PersistentTransactionalMap}: the values are
	 * plain immutable {@link EntityPrices} (no nested transactional state), so commit derives the next snapshot in
	 * `O(Δ·log N)` instead of rebuilding the whole map. Mutated via `compute`/`computeIfPresent`, which the variant
	 * inherits from the {@link Map} defaults (built on `get`/`put`/`remove`) — never {@link ChampMap}'s
	 * throwing mutators.
	 */
	private final PersistentTransactionalMap<Integer, EntityPrices> entityPrices;
	/**
	 * Local id of the single page stream a super price index owns — its price-record tree. A fixed value suffices (this
	 * index has exactly one stream); the persisted, globally-unique stream id is a separate concept resolved store-side
	 * from the sub-index identity (see {@link PriceListAndCurrencySuperIndexLeafPagePart}), never this value.
	 */
	private static final int PRICE_PAGE_STREAM = 0;
	/**
	 * Owner-resident page bookkeeping for the granular price-record storage layout: the advance-only `pageSequence`
	 * allocator, the explicit high-water and the live-page set of this index's price-record tree. It lives OUTSIDE
	 * transactional memory and is carried BY REFERENCE through {@link #createCopyWithMergedTransactionalMemory} so the
	 * surviving committed owner keeps the allocator and baseline across commits (the discarded transactional copy never
	 * has its own). It is consulted only on the single-writer flush/commit path.
	 */
	@Nonnull private final PageStreamRegistry pageStreamRegistry;

	public PriceListAndCurrencyPriceSuperIndex(@Nonnull PriceIndexKey priceIndexKey) {
		super(priceIndexKey);
		this.entityPrices = new PersistentTransactionalMap<>(new HashMap<>());
		this.pageStreamRegistry = new PageStreamRegistry();
	}

	public PriceListAndCurrencyPriceSuperIndex(
		@Nonnull PriceIndexKey priceIndexKey,
		@Nonnull RangeIndex validityIndex,
		@Nonnull PriceRecordContract[] priceRecords
	) {
		super(priceIndexKey, validityIndex, priceRecords);
		// aggregate the price records into a correctly pre-sized buffer before wrapping it once: this way the
		// PersistentTransactionalMap defensive copy is sized to the distinct-entity count, instead of starting
		// at the default capacity and rehashing repeatedly as the build loop populates it
		final Map<Integer, EntityPrices> entityPricesBase = createHashMap(priceRecords.length);
		for (final PriceRecordContract priceRecord : priceRecords) {
			entityPricesBase.compute(
				priceRecord.entityPrimaryKey(),
				(entityId, existingPriceRecords) -> existingPriceRecords == null ?
					EntityPrices.create(priceRecord) :
					EntityPrices.addPriceRecord(existingPriceRecords, priceRecord)
			);
		}
		this.entityPrices = new PersistentTransactionalMap<>(entityPricesBase);
		this.pageStreamRegistry = new PageStreamRegistry();
	}

	private PriceListAndCurrencyPriceSuperIndex(
		@Nonnull PriceIndexKey priceIndexKey,
		@Nonnull Bitmap indexedPriceEntityIds,
		@Nonnull Bitmap priceIds,
		@Nonnull Map<Integer, EntityPrices> entityPrices,
		@Nonnull RangeIndex validityIndex,
		@Nonnull TransactionalElementBPlusTree<PriceRecordContract> priceRecords,
		@Nonnull PageStreamRegistry pageStreamRegistry
	) {
		super(priceIndexKey, indexedPriceEntityIds, priceIds, validityIndex, priceRecords);
		this.entityPrices = new PersistentTransactionalMap<>(entityPrices);
		this.pageStreamRegistry = pageStreamRegistry;
	}

	/**
	 * Rebuilds a `PAGED` super price index from its persisted leaf pages, preserving the original leaf boundaries and
	 * page identities. Builds one leaf per persisted page (so in-memory leaf *i* is byte-identical to persisted page
	 * *i*), stamps each leaf with its persisted page sequence, then derives the entity-id / price-id bitmaps and the
	 * `entityPrices` map from the reassembled records and restores the page-stream bookkeeping (high-water + the
	 * live-page set). The reconstructed leaves are flagged dirty by the replaying inserts and cleared afterwards because
	 * they are exactly what is already on disk — a boundary-stable reload: a subsequent no-mutation commit rewrites
	 * nothing, and the first real mutation rewrites only genuinely-changed leaves instead of re-paginating the whole
	 * index.
	 *
	 * @param priceIndexKey         the price list and currency identity
	 * @param validityIndex         the inline validity range index read from the root part
	 * @param orderedPageSequences  the persisted leaf-page sequences in ascending key order (the root's leaf list)
	 * @param perPagePriceRecords   the price records of each leaf page, positionally aligned with `orderedPageSequences`
	 * @param highWaterPageSequence the persisted stream high-water (largest page sequence ever allocated)
	 * @return the rebuilt, boundary-stable `PAGED` super price index
	 */
	@Nonnull
	public static PriceListAndCurrencyPriceSuperIndex fromPersistedPages(
		@Nonnull PriceIndexKey priceIndexKey,
		@Nonnull RangeIndex validityIndex,
		@Nonnull int[] orderedPageSequences,
		@Nonnull PriceRecordContract[][] perPagePriceRecords,
		int highWaterPageSequence
	) {
		Assert.isPremiseValid(
			orderedPageSequences.length == perPagePriceRecords.length,
			"The number of page sequences must match the number of leaf-page record arrays."
		);
		Assert.isPremiseValid(orderedPageSequences.length > 0, "A paged super price index must have at least one leaf page.");
		// build one single-leaf tree from each page's records — a page never exceeds a leaf's capacity, so no split
		final List<TransactionalElementBPlusTree<PriceRecordContract>> pageTrees =
			new ArrayList<>(orderedPageSequences.length);
		for (final PriceRecordContract[] pageRecords : perPagePriceRecords) {
			TransactionalElementBPlusTree<PriceRecordContract> pageTree = newPriceRecordTree(pageRecords);
			pageTrees.add(pageTree);
		}
		// assemble the spine over the per-page leaves, preserving boundaries and stamping each leaf's page sequence
		final TransactionalElementBPlusTree<PriceRecordContract> tree =
			newPriceRecordTree().assembleFromSingleLeafTrees(
				pageTrees, orderedPageSequences, "super price index for price list " + priceIndexKey
			);
		final PageStreamRegistry pageStreamRegistry = PageStreamRegistry.restoredFrom(
			PRICE_PAGE_STREAM, highWaterPageSequence, tree.<PriceRecordContract>leafPageHandles()
		);
		// derive the entity-id / price-id bitmaps and the entityPrices map from the reassembled records, then adopt the
		// boundary-stable tree (its leaves already carry the persisted page sequences) BY REFERENCE
		final PriceRecordContract[] records = tree.toArray();
		final Map<Integer, EntityPrices> entityPricesBase = createHashMap(records.length);
		final int[] entityIds = new int[records.length];
		final int[] priceIds = new int[records.length];
		for (int i = 0; i < records.length; i++) {
			final PriceRecordContract record = records[i];
			entityIds[i] = record.entityPrimaryKey();
			priceIds[i] = record.internalPriceId();
			entityPricesBase.compute(
				record.entityPrimaryKey(),
				(entityId, existingPriceRecords) -> existingPriceRecords == null ?
					EntityPrices.create(record) :
					EntityPrices.addPriceRecord(existingPriceRecords, record)
			);
		}
		return new PriceListAndCurrencyPriceSuperIndex(
			priceIndexKey, new BaseBitmap(entityIds), new BaseBitmap(priceIds), entityPricesBase, validityIndex, tree,
			pageStreamRegistry
		);
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
		// add price to the translation tree (keyed by internal price id)
		this.priceRecords.insert(priceRecord);
		// make index dirty
		markDirtyAndInvalidateCache();
	}

	/**
	 * Removes inner record id or entity primary key of passed values from the price index.
	 */
	public void removePrice(int entityPrimaryKey, int internalPriceId, @Nullable DateTimeRange validity) {
		assertNotTerminated();
		final PriceRecordContract priceRecord = getPriceRecord(internalPriceId);
		this.priceRecords.delete(internalPriceId);

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
				entityIndexPrimaryKey, this.priceIndexKey, this.validityIndex, this.priceRecords.toArray()
			);
		} else {
			return null;
		}
	}

	/**
	 * Emits this super price index's modified storage parts into `sink`. A clean index emits nothing. A dirty index whose
	 * price-record tree spans a single leaf emits the inline `SINGLE` root (the whole-index part); a dirty index whose
	 * tree spans multiple leaves emits the granular `PAGED` shape: one {@link PriceListAndCurrencySuperIndexLeafPagePart}
	 * per CHANGED leaf plus the `PAGED` root carrying the high-water and the ordered live leaf-page list. The leaf pages
	 * carry the sub-index identity so their stream id (and primary key) is resolved store-side at write time.
	 *
	 * @param entityIndexPrimaryKey the owning entity index pk
	 * @param sink                  the trapped-changes accumulator for this commit
	 */
	@Override
	public void appendStorageParts(int entityIndexPrimaryKey, @Nonnull TrappedChanges sink) {
		assertNotTerminated();
		if (!this.dirty.isTrue()) {
			return;
		}
		if (isPaged()) {
			appendPagedParts(entityIndexPrimaryKey, sink);
		} else {
			// SINGLE shape (possibly just collapsed from PAGED): remove every prior leaf page (the SINGLE root no longer
			// references them) BEFORE dropping the page bookkeeping, then forget the stream so a later regrow into PAGED
			// starts from a clean baseline and re-emits every leaf
			// Reclaim against what the previous flush left ON DISK: its staged set while still unpublished (a warm-up
			// flush never reaches the commit-merge that publishes), else the published set. The published set alone lags a
			// whole flush behind, so every page of the collapsed stream would leak — the append-only OffsetIndex never
			// reclaims a record that is neither superseded nor explicitly removed.
			for (final int freedPageSequence : this.pageStreamRegistry.pendingLivePageSequences(PRICE_PAGE_STREAM)) {
				sink.addChangeToStore(
					new PriceListAndCurrencySuperIndexLeafPageRemoval(
						entityIndexPrimaryKey, this.priceIndexKey, freedPageSequence
					)
				);
			}
			this.pageStreamRegistry.forget(PRICE_PAGE_STREAM);
			sink.addChangeToStore(
				new PriceListAndCurrencySuperIndexStoragePart(
					entityIndexPrimaryKey, this.priceIndexKey, this.validityIndex, this.priceRecords.toArray()
				)
			);
		}
	}

	/**
	 * Whole-index-drop reclaim: emits a {@link PriceListAndCurrencySuperIndexLeafPageRemoval} for EVERY persisted leaf
	 * page of this super index's price-record tree, as if nothing survives. Called when the owning
	 * {@link EntityIndex} is dropped: this sub-index's own {@link #appendStorageParts} will never run
	 * again, so the append-only OffsetIndex would copy every orphaned leaf page forward forever unless each is removed
	 * explicitly. The `PAGED` root itself is manifest-listed and reclaimed by `EntityIndex.emitVanishedRootRemovals`, so
	 * this method emits only leaf pages.
	 *
	 * Unlike {@link #appendStorageParts}, this enumerates the persisted page baseline unconditionally — no `dirty`
	 * guard, no `isPaged()` branch — and has NO side effects: no `forget`, no bookkeeping mutation. A SINGLE-shaped
	 * index has no persisted leaf pages, so the registry's live set is empty and nothing is emitted.
	 *
	 * @param entityIndexPrimaryKey the owning entity index pk
	 * @param sink                  the trapped-changes accumulator collecting the removal instructions
	 */
	public void emitPersistedLeafPageRemovals(int entityIndexPrimaryKey, @Nonnull TrappedChanges sink) {
		assertNotTerminated();
		// reclaim every leaf page the registry still holds ON DISK for this stream — the persisted baseline, read-only
		for (final int freedPageSequence : this.pageStreamRegistry.pendingLivePageSequences(PRICE_PAGE_STREAM)) {
			sink.addChangeToStore(
				new PriceListAndCurrencySuperIndexLeafPageRemoval(
					entityIndexPrimaryKey, this.priceIndexKey, freedPageSequence
				)
			);
		}
	}

	/**
	 * Promotes the page set staged by the PREVIOUS flush to the live change-detection baseline, so this flush's
	 * freed-page diff is taken against what disk actually holds.
	 *
	 * The registry's live set answers "which leaf pages does this stream have on disk", and the write path derives the
	 * freed-page reclaim from it — which pages a leaf merge dropped, so a {@link PriceListAndCurrencySuperIndexLeafPageRemoval}
	 * is emitted for each and their {@link PriceRecord}s stop being copied forward. It advances solely by publishing,
	 * which {@link #createCopyWithMergedTransactionalMemory} does at the commit-merge.
	 *
	 * A WARM_UP (bulk) flush never reaches a commit-merge: it runs the very same collect pipeline as a transaction, but
	 * the merge that publishes only ever runs for one. Left alone, the live set of a freshly re-indexed catalog would
	 * therefore stay EMPTY for the whole warm-up while disk moved on, making the freed-page diff of every warm-up flush
	 * of this price-record tree vacuously empty. A leaf MERGE is the one structural event that drops a page without
	 * creating one — the survivor absorbs its sibling IN PLACE, keeping its own page and dirty flag, so nothing is
	 * allocated — which leaves the dropped page unremoved and therefore ORPHANED on disk. Unlike a pure page-list root
	 * (Chain / OwnerUnique / OwnerSort / FilterIndex), this `PAGED` root can never go stale on a cold reload: it also
	 * carries the inline {@link #validityIndex}, so it is re-emitted every dirty commit regardless of whether the leaf
	 * list changed, and its `leafPageSequences` is always derived from the CURRENT tree rather than the registry — so
	 * {@link #fromPersistedPages} never re-reads the dropped page and no cross-leaf overlap can occur. The observable
	 * failure here is therefore not a reload-time corruption but a silent storage LEAK: the orphaned leaf page is
	 * unreferenced by the root yet was never explicitly removed, so the append-only OffsetIndex — which reclaims space
	 * only for records it is told to remove, never by reachability — copies it forward at every future compaction.
	 *
	 * Publishing a staged set HERE — rather than only at the merge — is correct for every path, because of one
	 * invariant: **a failed flush is never followed by another flush of the same data**. Note that this publish runs at
	 * COLLECT time, before this flush has written anything (the baseline-capture pass re-enters this pipeline), so it
	 * cannot lean on the previous flush's bytes having landed by now. It does not need to: a flush that fails during
	 * trunk incorporation SUSPENDS the catalog's transaction processing ({@code TransactionManager.suspend}), and a
	 * flush that fails on the warm-up path POISONS the collection's buffer
	 * ({@code WarmUpDataStoreMemoryBuffer.poison}), so every later collect of it refuses deterministically. Those two
	 * are the same invariant in different dresses: after a failed flush no later flush of that data ever runs, so
	 * nothing can ever diff against the baselines it left behind. A flush that does NOT fail leaves `staged` holding
	 * exactly the page set it wrote — the baseline the next flush must diff against — regardless of which path staged
	 * it, and regardless of whether a merge ever ran. (Should the process die instead, {@link #fromPersistedPages}
	 * rebuilds the registry from disk on restart — page allocation is advance-only, so a burnt id is harmless.) That is
	 * what makes this safe in its own right — not the fact that it happens to be a no-op on the transactional path
	 * (where the merge published first, leaving nothing staged). The commit handshake is untouched.
	 */
	private void publishPreviousFlush() {
		this.pageStreamRegistry.publishStaged();
	}

	/**
	 * Walks the price-record tree leaf-by-leaf and emits the granular `PAGED` write path for this commit: one
	 * {@link PriceListAndCurrencySuperIndexLeafPagePart} per CHANGED leaf, a
	 * {@link PriceListAndCurrencySuperIndexLeafPageRemoval} per leaf a merge dropped this commit, and the `PAGED` root
	 * carrying the stream high-water and the ordered live leaf-page list. A not-yet-paged (split-born or fresh) leaf is
	 * assigned a freshly allocated page sequence stamped onto the live node so the commit-merge carries it forward; each
	 * leaf's transaction-aware dirty flag decides whether it is re-emitted, and is cleared once its page is collected.
	 * The complete next live-page set is STAGED on the registry here and becomes live only when the commit is published.
	 *
	 * Before staging, any set still staged by the PREVIOUS flush is promoted to live: see
	 * {@link #publishPreviousFlush()} for why that is both necessary and safe.
	 *
	 * @param entityIndexPrimaryKey the owning entity index pk
	 * @param sink                  the trapped-changes accumulator for this commit
	 */
	private void appendPagedParts(int entityIndexPrimaryKey, @Nonnull TrappedChanges sink) {
		publishPreviousFlush();
		// the page-sequence reconciliation is shared; the builder materializes this index's leaf part per changed leaf
		final PageEmission<PriceListAndCurrencySuperIndexLeafPagePart> emission =
			this.pageStreamRegistry.collectChangedPages(
				PRICE_PAGE_STREAM, this.priceRecords.<PriceRecordContract>leafPageHandles(),
				(pageSequence, handle) -> {
					final int size = handle.size();
					final PriceRecordContract[] pageRecords = new PriceRecordContract[size];
					for (int i = 0; i < size; i++) {
						pageRecords[i] = handle.valueAt(i);
					}
					return new PriceListAndCurrencySuperIndexLeafPagePart(
						entityIndexPrimaryKey, this.priceIndexKey, pageSequence, pageRecords
					);
				}
			);
		for (final PriceListAndCurrencySuperIndexLeafPagePart page : emission.changedPages()) {
			sink.addChangeToStore(page);
		}
		// remove the leaf pages a merge dropped this commit so they don't leak (the append-only OffsetIndex never reclaims
		// an unreferenced-but-never-removed record — page ids are advance-only and never re-keyed)
		for (final int freedPageSequence : emission.freedPageSequences()) {
			sink.addChangeToStore(
				new PriceListAndCurrencySuperIndexLeafPageRemoval(
					entityIndexPrimaryKey, this.priceIndexKey, freedPageSequence
				)
			);
		}
		// NOTE: unlike the pure page-list roots (Chain / OwnerUnique / OwnerSort / FilterIndex), this root also carries
		// the inline validityIndex (a RangeIndex that changes with the prices) — so it is re-emitted every dirty commit
		// and CANNOT use the PageEmission.pageListChanged() skip. This is inherent, not a deferred optimization: every
		// addPrice/removePrice writes validityIndex unconditionally (addValidity/removeValidity emit a range on both
		// branches — a null validity still writes the MIN..MAX range — and a price change is a remove+add, never an
		// in-place update), so validityIndex moves in strict lockstep with the price-record tree. Splitting it into its
		// own sibling part would re-emit that sibling on every dirty commit anyway, gaining nothing.
		sink.addChangeToStore(
			PriceListAndCurrencySuperIndexStoragePart.paged(
				entityIndexPrimaryKey, this.priceIndexKey, this.validityIndex,
				emission.highWaterPageSequence(), emission.orderedPageSequences()
			)
		);
	}

	/**
	 * Returns whether this index's price-record tree spans more than one leaf and is therefore persisted in the granular
	 * `PAGED` shape (one record per leaf) rather than the inline `SINGLE` shape.
	 *
	 * @return true when the tree has an internal root (≥ 2 leaves)
	 */
	public boolean isPaged() {
		return this.priceRecords.isRootInternal();
	}

	/**
	 * Method returns single {@link PriceRecord} reference that match passed price id.
	 */
	@Nonnull
	public PriceRecordContract getPriceRecord(int internalPriceId) {
		assertNotTerminated();
		final PriceRecordContract priceRecord = this.priceRecords.search(internalPriceId);
		Assert.isTrue(priceRecord != null, "Price id `" + internalPriceId + "` was not found in the price super index!");
		return priceRecord;
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
			// the just-completed flush staged the next live-page set for the price-record stream; the commit is now known
			// durable, so promote it to live. The registry is then carried BY REFERENCE into the committed copy so the
			// surviving owner keeps the allocator + change-detection baseline the flush populated. This is the EARLIEST
			// publish point on the transactional path only; it is not the only one — a staged set that never reaches a
			// merge (the warm-up path has no merge at all) is published by the next flush instead, see
			// `publishPreviousFlush`. (No discard counterpart is needed: a pre-flush abort never stages, and a failed
			// flush suspends this catalog's transaction processing — on the warm-up path it poisons the collection's
			// buffer instead, the same invariant in another dress — so no later flush ever diffs against the baseline
			// a failed one left behind; restart rebuilds a clean registry from disk.)
			this.pageStreamRegistry.publishStaged();
			final TransactionalElementBPlusTree<PriceRecordContract> newTriples =
				transactionalLayer.getStateCopyWithCommittedChanges(this.priceRecords);
			return new PriceListAndCurrencyPriceSuperIndex(
				this.priceIndexKey,
				transactionalLayer.getStateCopyWithCommittedChanges(this.indexedPriceEntityIds),
				transactionalLayer.getStateCopyWithCommittedChanges(this.indexedPriceIds),
				transactionalLayer.getStateCopyWithCommittedChanges(this.entityPrices),
				transactionalLayer.getStateCopyWithCommittedChanges(this.validityIndex),
				newTriples,
				this.pageStreamRegistry
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
