/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.index.membership;

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.MemoryMeasuringConstants;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.PrimitiveIterator.OfInt;

/**
 * Reverse lookup answering "which reduced indexes of ONE reference hold this owner entity?", for the reduced
 * indexes small enough to be worth covering.
 *
 * # What it is for
 *
 * `ReevaluateExpressionExecutor#collectOwnersOfReducedIndexes` answers that question by walking **every**
 * reduced index a reference advertises and intersecting each one's member bitmap against the affected owners.
 * That costs `O(total reduced indexes)` on every cross-entity conditional-facet trigger, regardless of how
 * many owners the trigger touches — measured at 0.78 ms on a production catalog as configured, and 81-113 ms
 * one schema flag away (issue #1529). This structure removes that walk for the indexes it covers.
 *
 * # Why only SOME indexes are covered
 *
 * Cost is per **membership** while benefit is per **index**: covering a reduced index holding 21,467 owners
 * costs 21,467 entries and saves exactly one probe. Covering everything measured at 274.7 MiB against
 * 36.3 MiB for a size threshold that still removes 96 % of the walk. So an index is covered only while it
 * holds at most {@link #getCoverageThreshold()} owners; larger ones stay {@link #getResidualIndexPrimaryKeys()
 * on the walk}, and references whose indexes are all large disqualify themselves automatically without any
 * per-reference heuristic.
 *
 * Promotion out of coverage happens above the threshold and demotion back only at half of it. The hysteresis
 * is what makes a crossing `O(1)` amortised: an index cannot cross upwards again until half a threshold's
 * worth of owners have been added, so the `O(T)` rebuild of its entries is paid at most once per `T/2`
 * writes. In `WARM_UP` indexes only grow, so each crosses at most once.
 *
 * # The safety property — this structure is an ACCELERATOR, never an AUTHORITY
 *
 * A reduced index is consulted through this map **only** when it is covered; every other index the reference
 * advertises must still be walked. Consequently a missing entry, a stale entry or an entirely unpopulated
 * instance can only make the trigger **slower**, never wrong — the walk still finds what the map does not.
 * The single way to produce a wrong answer is to claim coverage this map cannot honour, which is why
 * {@link #getResidualIndexPrimaryKeys()} and {@link #getCoveredIndexPrimaryKeys()} are exposed as a pair and
 * why every caller is expected to treat "advertised but in neither" as *walk it*.
 *
 * That property is what makes the structure safe to ship against transactional memory, and it is what
 * `ReducedIndexMembershipCompletenessTest` asserts — against the reduced indexes themselves as ground truth,
 * rather than against this map's own bookkeeping.
 *
 * # Derived state
 *
 * Nothing here is persisted: it is a function of the reduced indexes' membership bitmaps, and is rebuilt when
 * a collection is loaded. See {@link io.evitadb.index.component.ReducedIndexMembershipMapComponent} for the
 * transactional-lifecycle half of that arrangement.
 *
 * @author Claude (issue #1529 sibling-resolver optimization), FG Forrest a.s. (c) 2026
 */
public class ReducedIndexMembership implements VoidTransactionMemoryProducer<ReducedIndexMembership> {

	/**
	 * Default maximum number of owners a reduced index may hold and still be covered by the reverse map.
	 *
	 * Measured on a production catalog: at this value the map covers 96 % of the walk for 13 % of the memory a
	 * blanket structure would cost. The curve is steeply concave — `T`=1 already removes 86 % — so the exact
	 * value is not delicate; what matters is that it is small.
	 */
	public static final int DEFAULT_COVERAGE_THRESHOLD = 16;

	/**
	 * Maximum owners a covered reduced index may hold. Crossing above it promotes the index to the residual
	 * set.
	 */
	private final int coverageThreshold;

	/**
	 * Owner count at or below which a residual reduced index is demoted back into coverage. Half of
	 * {@link #coverageThreshold}, which is what makes a crossing `O(1)` amortised.
	 */
	private final int demotionThreshold;

	/**
	 * Owner primary key to the primary keys of the covered reduced indexes holding it. Holds an entry only
	 * for owners that belong to at least one covered index.
	 */
	@Nonnull private final TransactionalMap<Integer, TransactionalBitmap> indexPrimaryKeysByOwner;

	/**
	 * Union of {@link #indexPrimaryKeysByOwner}'s keys, kept as a bitmap so the affected-owner set can be
	 * intersected against it once per reference instead of probing the map once per affected owner. That
	 * single intersection is what makes splitting the structure per reference cost 0.4 % in the sparse shape
	 * rather than a multiple of the map lookups.
	 */
	@Nonnull private final TransactionalBitmap coveredOwners;

	/**
	 * Primary keys of the reduced indexes this map covers. Exposed so a caller can tell "covered" from
	 * "never seen" — the latter must be walked.
	 */
	@Nonnull private final TransactionalBitmap coveredIndexPrimaryKeys;

	/**
	 * Primary keys of the reduced indexes known to be above the threshold. Iterated directly by the trigger,
	 * which is why it is materialised rather than derived by filtering the reference's full advertisement —
	 * deriving it would pay exactly the `O(total indexes)` traversal this structure exists to remove.
	 */
	@Nonnull private final TransactionalBitmap residualIndexPrimaryKeys;

	/**
	 * Creates an empty membership map with the default threshold.
	 */
	public ReducedIndexMembership() {
		this(DEFAULT_COVERAGE_THRESHOLD);
	}

	/**
	 * Creates an empty membership map.
	 *
	 * @param coverageThreshold maximum owners a covered reduced index may hold; must be positive
	 */
	public ReducedIndexMembership(int coverageThreshold) {
		this.coverageThreshold = coverageThreshold;
		this.demotionThreshold = Math.max(1, coverageThreshold / 2);
		this.indexPrimaryKeysByOwner = new TransactionalMap<>(
			CollectionUtils.createHashMap(64), TransactionalBitmap.class, TransactionalBitmap::new
		);
		this.coveredOwners = new TransactionalBitmap();
		this.coveredIndexPrimaryKeys = new TransactionalBitmap();
		this.residualIndexPrimaryKeys = new TransactionalBitmap();
	}

	/**
	 * Reconstructs the map from already-merged state. Used by
	 * {@link #createCopyWithMergedTransactionalMemory(TransactionalLayerMaintainer)}.
	 *
	 * @param coverageThreshold       maximum owners a covered reduced index may hold
	 * @param indexPrimaryKeysByOwner merged owner to covered-index map
	 * @param coveredOwners           merged covered-owner set
	 * @param coveredIndexPrimaryKeys merged covered-index set
	 * @param residualIndexPrimaryKeys merged residual-index set
	 */
	private ReducedIndexMembership(
		int coverageThreshold,
		@Nonnull Map<Integer, TransactionalBitmap> indexPrimaryKeysByOwner,
		@Nonnull Bitmap coveredOwners,
		@Nonnull Bitmap coveredIndexPrimaryKeys,
		@Nonnull Bitmap residualIndexPrimaryKeys
	) {
		this.coverageThreshold = coverageThreshold;
		this.demotionThreshold = Math.max(1, coverageThreshold / 2);
		this.indexPrimaryKeysByOwner = new TransactionalMap<>(
			indexPrimaryKeysByOwner, TransactionalBitmap.class, TransactionalBitmap::new
		);
		this.coveredOwners = new TransactionalBitmap(coveredOwners);
		this.coveredIndexPrimaryKeys = new TransactionalBitmap(coveredIndexPrimaryKeys);
		this.residualIndexPrimaryKeys = new TransactionalBitmap(residualIndexPrimaryKeys);
	}

	/**
	 * Returns the maximum number of owners a reduced index may hold and still be covered.
	 *
	 * @return the coverage threshold
	 */
	public int getCoverageThreshold() {
		return this.coverageThreshold;
	}

	/**
	 * Registers a reduced index whose membership is already known in full — the load path, where indexes
	 * arrive populated, and the only place coverage is decided for more than one owner at a time.
	 *
	 * The index must not already be known. Re-registering a covered index would have to forget its previous
	 * entries, and the only membership available to do that with is the one being passed in — which is the
	 * NEW truth, so any owner that left in between would keep an entry naming an index it no longer belongs
	 * to. Rather than leave that silently stale, this refuses: a caller that wants to re-decide an index's
	 * coverage must {@link #unregisterIndex(int, Bitmap) unregister} it first, with the membership it had.
	 *
	 * @param indexPrimaryKey primary key of the reduced index
	 * @param members         the owners the reduced index currently holds
	 * @throws GenericEvitaInternalError when the index is already known to this map
	 */
	public void registerIndex(int indexPrimaryKey, @Nonnull Bitmap members) {
		Assert.isPremiseValid(
			!this.coveredIndexPrimaryKeys.contains(indexPrimaryKey)
				&& !this.residualIndexPrimaryKeys.contains(indexPrimaryKey),
			() -> new GenericEvitaInternalError(
				"Reduced index " + indexPrimaryKey + " is already known to the membership map - re-registering " +
					"it would silently strand the entries built from its previous membership. Unregister it " +
					"first, passing the membership it had."
			)
		);
		if (members.size() > this.coverageThreshold) {
			this.residualIndexPrimaryKeys.add(indexPrimaryKey);
		} else if (!members.isEmpty()) {
			addCoverage(indexPrimaryKey, members);
		}
	}

	/**
	 * Forgets a reduced index entirely — it was dropped from the collection. The index leaves both the
	 * covered and the residual set, so a later caller sees it as "never known" and walks it if it ever
	 * reappears advertised.
	 *
	 * @param indexPrimaryKey primary key of the reduced index that was dropped
	 * @param members         the owners it held, so its entries can be forgotten in one pass over them rather
	 *                        than a scan of every owner the reference knows
	 */
	public void unregisterIndex(int indexPrimaryKey, @Nonnull Bitmap members) {
		dropCoverage(indexPrimaryKey, members);
		this.residualIndexPrimaryKeys.remove(indexPrimaryKey);
	}

	/**
	 * Records that an owner has entered a reduced index, and re-decides that index's coverage.
	 *
	 * @param indexPrimaryKey primary key of the reduced index the owner entered
	 * @param ownerPrimaryKey primary key of the owner entity
	 * @param membersAfter    the owners the reduced index holds AFTER the insert, used both for the size
	 *                        decision and, on a crossing, to rebuild the index's entries
	 */
	public void ownerAdded(int indexPrimaryKey, int ownerPrimaryKey, @Nonnull Bitmap membersAfter) {
		final int size = membersAfter.size();
		if (this.coveredIndexPrimaryKeys.contains(indexPrimaryKey)) {
			if (size > this.coverageThreshold) {
				// promotion: the index has outgrown coverage, so its entries are removed in one pass over its
				// own members - bounded by the threshold plus one, never by the reference's owner count
				dropCoverage(indexPrimaryKey, membersAfter);
				this.residualIndexPrimaryKeys.add(indexPrimaryKey);
			} else {
				recordMembership(ownerPrimaryKey, indexPrimaryKey);
			}
		} else if (this.residualIndexPrimaryKeys.contains(indexPrimaryKey)) {
			// a residual index only grows on insert, so it can never demote here
			this.residualIndexPrimaryKeys.add(indexPrimaryKey);
		} else {
			// first time this index is seen - decide from scratch
			registerIndex(indexPrimaryKey, membersAfter);
		}
	}

	/**
	 * Records that an owner has left a reduced index, and re-decides that index's coverage.
	 *
	 * @param indexPrimaryKey primary key of the reduced index the owner left
	 * @param ownerPrimaryKey primary key of the owner entity
	 * @param membersAfter    the owners the reduced index holds AFTER the removal
	 */
	public void ownerRemoved(int indexPrimaryKey, int ownerPrimaryKey, @Nonnull Bitmap membersAfter) {
		if (this.coveredIndexPrimaryKeys.contains(indexPrimaryKey)) {
			forgetMembership(ownerPrimaryKey, indexPrimaryKey);
			if (membersAfter.isEmpty()) {
				unregisterIndex(indexPrimaryKey, membersAfter);
			}
		} else if (this.residualIndexPrimaryKeys.contains(indexPrimaryKey)) {
			if (membersAfter.isEmpty()) {
				unregisterIndex(indexPrimaryKey, membersAfter);
			} else if (membersAfter.size() <= this.demotionThreshold) {
				// demotion: shrunk far enough below the threshold that the hysteresis band is cleared
				this.residualIndexPrimaryKeys.remove(indexPrimaryKey);
				addCoverage(indexPrimaryKey, membersAfter);
			}
		}
	}

	/**
	 * Returns the primary keys of the reduced indexes this map covers.
	 *
	 * @return covered reduced-index primary keys; never `null`
	 */
	@Nonnull
	public Bitmap getCoveredIndexPrimaryKeys() {
		return this.coveredIndexPrimaryKeys;
	}

	/**
	 * Returns the primary keys of the reduced indexes left on the walk because they hold more owners than the
	 * threshold allows.
	 *
	 * @return residual reduced-index primary keys; never `null`
	 */
	@Nonnull
	public Bitmap getResidualIndexPrimaryKeys() {
		return this.residualIndexPrimaryKeys;
	}

	/**
	 * Returns the owners that belong to at least one covered reduced index, so the affected set can be
	 * narrowed by a single intersection before any map lookup happens.
	 *
	 * @return covered owner primary keys; never `null`
	 */
	@Nonnull
	public Bitmap getCoveredOwners() {
		return this.coveredOwners;
	}

	/**
	 * Returns the covered reduced indexes holding the given owner.
	 *
	 * @param ownerPrimaryKey primary key of the owner entity
	 * @return the covered reduced-index primary keys, or {@link EmptyBitmap#INSTANCE} when the owner belongs
	 * to no covered index
	 */
	@Nonnull
	public Bitmap getIndexPrimaryKeys(int ownerPrimaryKey) {
		final TransactionalBitmap indexes = this.indexPrimaryKeysByOwner.get(ownerPrimaryKey);
		return indexes == null ? EmptyBitmap.INSTANCE : indexes;
	}

	/**
	 * Returns `true` when the map holds no coverage and no residual knowledge at all, which is how a
	 * collection that never fired a cross-entity trigger stays free of charge.
	 *
	 * @return `true` when nothing is known
	 */
	public boolean isEmpty() {
		return this.coveredIndexPrimaryKeys.isEmpty() && this.residualIndexPrimaryKeys.isEmpty();
	}

	/**
	 * Estimates the heap this map occupies, using the engine's own accounting so the figure is comparable
	 * with every other index structure's.
	 *
	 * @return estimated size in bytes
	 */
	public long getHeapSizeInBytes() {
		return MemoryMeasuringConstants.OBJECT_HEADER_SIZE +
			2 * MemoryMeasuringConstants.INT_SIZE +
			4 * MemoryMeasuringConstants.REFERENCE_SIZE +
			this.indexPrimaryKeysByOwner.getHeapSizeInBytes(
				key -> (long) MemoryMeasuringConstants.OBJECT_HEADER_SIZE + MemoryMeasuringConstants.INT_SIZE,
				TransactionalBitmap::getHeapSizeInBytes
			) +
			this.coveredOwners.getHeapSizeInBytes() +
			this.coveredIndexPrimaryKeys.getHeapSizeInBytes() +
			this.residualIndexPrimaryKeys.getHeapSizeInBytes();
	}

	@Nonnull
	@Override
	public ReducedIndexMembership createCopyWithMergedTransactionalMemory(
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		return new ReducedIndexMembership(
			this.coverageThreshold,
			transactionalLayer.getStateCopyWithCommittedChanges(this.indexPrimaryKeysByOwner),
			transactionalLayer.getStateCopyWithCommittedChanges(this.coveredOwners),
			transactionalLayer.getStateCopyWithCommittedChanges(this.coveredIndexPrimaryKeys),
			transactionalLayer.getStateCopyWithCommittedChanges(this.residualIndexPrimaryKeys)
		);
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		this.indexPrimaryKeysByOwner.removeLayer(transactionalLayer);
		this.coveredOwners.removeLayer(transactionalLayer);
		this.coveredIndexPrimaryKeys.removeLayer(transactionalLayer);
		this.residualIndexPrimaryKeys.removeLayer(transactionalLayer);
	}

	/*
		PRIVATE METHODS
	 */

	/**
	 * Brings a reduced index into coverage, recording one membership per owner it holds.
	 *
	 * @param indexPrimaryKey primary key of the reduced index
	 * @param members         the owners it holds
	 */
	private void addCoverage(int indexPrimaryKey, @Nonnull Bitmap members) {
		final OfInt it = members.iterator();
		while (it.hasNext()) {
			recordMembership(it.nextInt(), indexPrimaryKey);
		}
		this.coveredIndexPrimaryKeys.add(indexPrimaryKey);
	}

	/**
	 * Removes a reduced index from coverage, forgetting every membership naming it.
	 *
	 * Iterates the index's OWN members rather than the reference's owners, which is what keeps a promotion
	 * bounded by the threshold instead of by the size of the whole slice. The caller must therefore pass the
	 * membership the coverage was built from — every entry this map holds for the index names an owner in
	 * that set, because entries are only ever created from it and are removed in lockstep by
	 * {@link #ownerRemoved(int, int, Bitmap)}.
	 *
	 * @param indexPrimaryKey primary key of the reduced index
	 * @param members         the owners the index holds
	 */
	private void dropCoverage(int indexPrimaryKey, @Nonnull Bitmap members) {
		if (!this.coveredIndexPrimaryKeys.contains(indexPrimaryKey)) {
			return;
		}
		final OfInt it = members.iterator();
		while (it.hasNext()) {
			forgetMembership(it.nextInt(), indexPrimaryKey);
		}
		this.coveredIndexPrimaryKeys.remove(indexPrimaryKey);
	}

	/**
	 * Records that an owner belongs to a covered reduced index.
	 *
	 * @param ownerPrimaryKey primary key of the owner entity
	 * @param indexPrimaryKey primary key of the covered reduced index
	 */
	private void recordMembership(int ownerPrimaryKey, int indexPrimaryKey) {
		final TransactionalBitmap existing = this.indexPrimaryKeysByOwner.get(ownerPrimaryKey);
		if (existing == null) {
			this.indexPrimaryKeysByOwner.put(
				ownerPrimaryKey, new TransactionalBitmap(new BaseBitmap(indexPrimaryKey))
			);
			this.coveredOwners.add(ownerPrimaryKey);
		} else {
			existing.add(indexPrimaryKey);
		}
	}

	/**
	 * Forgets that an owner belongs to a covered reduced index, dropping the owner entirely when it was its
	 * last one.
	 *
	 * @param ownerPrimaryKey primary key of the owner entity
	 * @param indexPrimaryKey primary key of the covered reduced index
	 */
	private void forgetMembership(int ownerPrimaryKey, int indexPrimaryKey) {
		final TransactionalBitmap existing = this.indexPrimaryKeysByOwner.get(ownerPrimaryKey);
		if (existing == null) {
			return;
		}
		existing.remove(indexPrimaryKey);
		if (existing.isEmpty()) {
			this.indexPrimaryKeysByOwner.remove(ownerPrimaryKey);
			this.coveredOwners.remove(ownerPrimaryKey);
		}
	}

}
