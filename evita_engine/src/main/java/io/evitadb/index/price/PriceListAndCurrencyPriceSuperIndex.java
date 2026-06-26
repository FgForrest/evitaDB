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
import io.evitadb.index.bPlusTree.LeafPageHandle;
import io.evitadb.index.bPlusTree.TransactionalElementBPlusTree;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.map.PersistentTransactionalMap;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
			newPriceRecordTree().assembleFromSingleLeafTrees(pageTrees, orderedPageSequences);
		// clear the replay-set dirty flags and seed the live-page set so the first post-load commit suppresses every
		// untouched leaf
		final List<LeafPageHandle<PriceRecordContract>> handles = tree.leafPageHandles();
		final Set<Integer> livePages = new HashSet<>(handles.size());
		for (final LeafPageHandle<PriceRecordContract> handle : handles) {
			handle.clearDirty();
			livePages.add(handle.getPageSequence());
		}
		final PageStreamRegistry pageStreamRegistry = new PageStreamRegistry();
		pageStreamRegistry.restore(PRICE_PAGE_STREAM, highWaterPageSequence, livePages);
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
			for (final int freedPageSequence : this.pageStreamRegistry.livePageSequences(PRICE_PAGE_STREAM)) {
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
	 * Walks the price-record tree leaf-by-leaf and emits the granular `PAGED` write path for this commit: one
	 * {@link PriceListAndCurrencySuperIndexLeafPagePart} per CHANGED leaf, a
	 * {@link PriceListAndCurrencySuperIndexLeafPageRemoval} per leaf a merge dropped this commit, and the `PAGED` root
	 * carrying the stream high-water and the ordered live leaf-page list. A not-yet-paged (split-born or fresh) leaf is
	 * assigned a freshly allocated page sequence stamped onto the live node so the commit-merge carries it forward; each
	 * leaf's transaction-aware dirty flag decides whether it is re-emitted, and is cleared once its page is collected.
	 * The complete next live-page set is STAGED on the registry here and becomes live only when the commit is published.
	 *
	 * @param entityIndexPrimaryKey the owning entity index pk
	 * @param sink                  the trapped-changes accumulator for this commit
	 */
	private void appendPagedParts(int entityIndexPrimaryKey, @Nonnull TrappedChanges sink) {
		final List<LeafPageHandle<PriceRecordContract>> handles = this.priceRecords.leafPageHandles();
		final int[] orderedPageSequences = new int[handles.size()];
		final Set<Integer> nextLive = new HashSet<>(handles.size());
		int idx = 0;
		for (final LeafPageHandle<PriceRecordContract> handle : handles) {
			int pageSequence = handle.getPageSequence();
			final boolean freshLeaf = pageSequence == TransactionalElementBPlusTree.UNASSIGNED_PAGE_SEQUENCE;
			if (freshLeaf) {
				// split-born / fresh leaf: allocate a page and stamp it onto the live node (the merge carries it forward)
				pageSequence = this.pageStreamRegistry.allocate(PRICE_PAGE_STREAM);
				handle.setPageSequence(pageSequence);
			}
			orderedPageSequences[idx++] = pageSequence;
			nextLive.add(pageSequence);

			// a leaf is (re)written iff it is brand new or its transaction-aware dirty flag is set — an exact signal a
			// content hash cannot match. Once the page is collected the flag is cleared so the next commit suppresses the
			// leaf unless it is mutated again.
			if (freshLeaf || handle.isDirty()) {
				final int size = handle.size();
				final PriceRecordContract[] pageRecords = new PriceRecordContract[size];
				for (int i = 0; i < size; i++) {
					pageRecords[i] = handle.valueAt(i);
				}
				sink.addChangeToStore(
					new PriceListAndCurrencySuperIndexLeafPagePart(
						entityIndexPrimaryKey, this.priceIndexKey, pageSequence, pageRecords
					)
				);
				handle.clearDirty();
			}
		}
		// pages live in the published set but absent from this commit's live leaves were dropped by a leaf merge: they
		// must be REMOVED from storage (the append-only OffsetIndex never reclaims an unreferenced-but-never-removed
		// record — page ids are advance-only and never re-keyed)
		for (final int freedPageSequence : this.pageStreamRegistry.freedPageSequences(PRICE_PAGE_STREAM, nextLive)) {
			sink.addChangeToStore(
				new PriceListAndCurrencySuperIndexLeafPageRemoval(
					entityIndexPrimaryKey, this.priceIndexKey, freedPageSequence
				)
			);
		}
		this.pageStreamRegistry.stage(PRICE_PAGE_STREAM, nextLive);
		sink.addChangeToStore(
			PriceListAndCurrencySuperIndexStoragePart.paged(
				entityIndexPrimaryKey, this.priceIndexKey, this.validityIndex,
				this.pageStreamRegistry.highWater(PRICE_PAGE_STREAM), orderedPageSequences
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
			// surviving owner keeps the allocator + change-detection baseline the flush populated. (No discard counterpart
			// is needed: a pre-flush abort never stages, a flush failure is fatal — restart rebuilds a clean registry —
			// and a stale staged map is harmlessly replaced by the next commit's stage.)
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
