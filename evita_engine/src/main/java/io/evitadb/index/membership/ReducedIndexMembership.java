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

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.VMLayout;

import javax.annotation.Nonnull;
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
 * The map is read as an index **selector**: it says which reduced indexes are worth probing for a given set of
 * owners, and each selected index is then asked itself who is in it. Nothing recorded here is taken as an
 * answer. So a stale entry can only send the probe at an index that turns out to hold no affected owner, an
 * entry naming an index the collection no longer holds is dropped when that index fails to resolve, and an
 * instance that decided coverage for nothing leaves every advertised index in the residual set — which is
 * precisely the walk this structure exists to save. **Slower**, never wrong.
 *
 * What the map must never do is *omit* an index its reference advertises. The residual set is probed whole,
 * but a covered index is probed only while some covered owner names it, so an owner that entered a covered
 * index unrecorded is an owner the trigger will not consider. That is why `covered ∪ residual == advertised`
 * is the invariant this structure is maintained against, why {@link #getResidualIndexPrimaryKeys()} and
 * {@link #getCoveredIndexPrimaryKeys()} are exposed as a pair, and why the whole slice is discarded by the
 * schema change that stops maintenance following its reference — see
 * `EntityCollection#discardUnmaintainedReducedIndexMemberships`.
 *
 * Note what that invariant is **not**: the resolver never re-derives the advertisement to check it. Reading it
 * would pay the very `O(total reduced indexes)` traversal the structure removes, so a slice that exists is
 * probed exactly as it stands, and an index missing from both sets is simply never visited. Nothing at read
 * time will notice. The extreme case is worth naming, because it reads as the harmless one: a slice that is
 * **present with both sets empty**, while its reference still advertises indexes, makes the trigger skip every
 * one of them — a wrong facet, not a slow one. Absence of the whole slice is the safe state; an empty slice is
 * not.
 *
 * What enforces the invariant is therefore a test, not a runtime check:
 * `ReducedIndexMembershipCompletenessTest#assertMembershipMatchesIndexes` compares `covered ∪ residual` against
 * the reference's live advertisement, for every reference in every scope, and fails outright on exactly that
 * state. **That assertion is the contract** — not the fact that
 * `ReferenceIndexMutator#seedFromAdvertisedIndexes` happens to fill a fresh slice before anything reads it. A
 * contract resting on one call site being reached is one refactor away from breaking silently, so a maintainer
 * adding another site that builds, seeds or maintains a slice has no safety net downstream of it at run time:
 * that test is what has to be made to fail first.
 *
 * The accelerator property — **slower**, never wrong — is what makes the structure safe to ship against
 * transactional memory, and that test is what holds it to it: against the reduced indexes themselves as ground
 * truth, rather than against this map's own bookkeeping.
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
	 * The two families of `REFERENCED_*_TYPE` index a reference may own. Together they define what "advertised"
	 * means for the `covered ∪ residual == advertised` invariant this structure is maintained against: both
	 * advertise reduced indexes the cross-entity facet trigger consults, and neither stands in for the other.
	 *
	 * Shared by every site that builds or seeds a lookup — `EntityCollection#rebuildReducedIndexMembership` at
	 * load and `ReferenceIndexMutator#seedFromAdvertisedIndexes` on the first write after a reference is raised
	 * — so a third family could never be added to one of them and forgotten in the other.
	 */
	public static final EntityIndexType[] REFERENCED_TYPE_INDEX_FAMILIES = {
		EntityIndexType.REFERENCED_ENTITY_TYPE, EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE
	};

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
	 * Primary keys of the reduced indexes this map covers. Exposed as a pair with
	 * {@link #residualIndexPrimaryKeys} so a caller can tell "covered" from "residual", and so the union of the
	 * two can be checked against the reference's advertisement.
	 *
	 * It is **not** a fall-back signal. A slice that exists is probed exactly as it stands — the resolver never
	 * re-derives the advertisement, because reading it would pay the very traversal this structure removes — so
	 * an index in neither set is not walked, it is never visited at all. Only the absence of the whole slice
	 * restores the walk.
	 */
	@Nonnull private final TransactionalBitmap coveredIndexPrimaryKeys;

	/**
	 * Primary keys of the reduced indexes left on the walk. An index above the threshold is always here, but the
	 * converse does not hold: size is only ONE of the reasons an index lands in this set, so reading membership
	 * of it as "this index holds more than `T` owners" is wrong.
	 *
	 * - {@link #registerIndexAsResidual} records an index whose membership the caller could not read at all, so
	 *   a slice seeded by `ReferenceIndexMutator#seedFromAdvertisedIndexes` has EVERY advertised index here
	 *   regardless of size, and so does one whose index failed to resolve at load;
	 * - {@link #registerIndex}'s third arm records an index that arrived holding nobody;
	 * - a small index registered either way stays here until it is written again — only {@link #ownerAdded} and
	 *   {@link #ownerRemoved} demote, so a slice whose indexes are never written again never converges.
	 *
	 * Iterated directly by the trigger, which is why it is materialised rather than derived by filtering the
	 * reference's full advertisement — deriving it would pay exactly the `O(total indexes)` traversal this
	 * structure exists to remove.
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
	 * An index arriving with an EMPTY membership is accounted for as residual rather than dropped — see the
	 * third arm for why that state is unreachable and why it is nevertheless handled rather than raised on.
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
		} else {
			// An advertised index holding nobody. Upstream it does not arise: `ReferenceIndexMutator
			// #referenceRemovalPerComponent` un-advertises the reduced index in the same synchronous step in
			// which its last owner leaves, because `ReferencedTypeEntityIndex` counts owners per (index,
			// referenced entity) tuple and drops the advertisement on the 1 -> 0 crossing. So an index that is
			// advertised has an owner, and this arm is dead.
			// It is accounted for rather than raised on all the same. The only caller that could reach it is the
			// load path (`EntityCollection#registerReducedIndex`), where refusing would take the whole catalog
			// offline over an index that holds nobody - and one that holds nobody can hold no affected owner
			// either, so the trigger skipping it produces no wrong answer. Recording it as residual is not a
			// silent skip: it accounts for the index, keeps `covered ∪ residual == advertised` true
			// UNCONDITIONALLY rather than by inherited argument, and leaves it on the probe, which is where it
			// was before this structure existed. Coverage would be the wrong half - an index with no owners
			// records no owner entry, so it would be selected for no probe at all. The cost when it fires is one
			// probe that yields nothing; the lockstep itself is asserted, not argued, by
			// `ReducedIndexMembershipCompletenessTest#assertMembershipMatchesIndexes`.
			this.residualIndexPrimaryKeys.add(indexPrimaryKey);
		}
	}

	/**
	 * Registers a reduced index as residual without deciding coverage for it — for the callers that cannot read
	 * the index's members and so cannot decide.
	 *
	 * Two do. Seeding a slice created after the collection was loaded
	 * (`ReferenceIndexMutator#seedFromAdvertisedIndexes`) has only the reference's advertisement to work from,
	 * and the load-time build has to record an advertised index it cannot resolve. Both need the same thing:
	 * the index accounted for, so `covered ∪ residual == advertised` holds, and left on the probe, which is
	 * where it was before this structure existed. {@link #ownerAdded} demotes such an index into coverage the
	 * next time it is written, so nothing stays needlessly residual.
	 *
	 * @param indexPrimaryKey primary key of the reduced index
	 * @throws GenericEvitaInternalError when the index is already known to this map
	 */
	public void registerIndexAsResidual(int indexPrimaryKey) {
		Assert.isPremiseValid(
			!this.coveredIndexPrimaryKeys.contains(indexPrimaryKey)
				&& !this.residualIndexPrimaryKeys.contains(indexPrimaryKey),
			() -> new GenericEvitaInternalError(
				"Reduced index " + indexPrimaryKey + " is already known to the membership map!"
			)
		);
		this.residualIndexPrimaryKeys.add(indexPrimaryKey);
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
			// A residual index grows on insert and so cannot outgrow anything - but it may be residual for a
			// reason other than size: a slice seeded from a reference's advertisement records every index as
			// residual without inspecting it, because seeding cannot resolve them, and `registerIndex` records
			// an index that arrived with no members the same way. Demoting here is what lets such a
			// slice acquire coverage as its indexes are written, and it cannot oscillate: promotion needs `T`
			// owners and demotion `T/2`, so the two boundaries never meet.
			if (size <= this.demotionThreshold) {
				this.residualIndexPrimaryKeys.remove(indexPrimaryKey);
				addCoverage(indexPrimaryKey, membersAfter);
			}
		} else {
			// first time this index is seen - decide from scratch
			registerIndex(indexPrimaryKey, membersAfter);
		}
	}

	/**
	 * Records that an owner has left a reduced index, and re-decides that index's coverage.
	 *
	 * **Unregistering on an empty `membersAfter` is correct only because of an upstream lockstep**, and the two
	 * sites do not otherwise mention each other: `ReferenceIndexMutator#referenceRemovalPerComponent`
	 * un-advertises the same reduced index a few lines before it reaches this method, on the 1 -> 0 crossing of
	 * `ReferencedTypeEntityIndex`'s per-tuple owner counter. So what is dropped here is an index the reference
	 * no longer advertises either, and `covered ∪ residual == advertised` survives the drop. A change that
	 * defers or conditions that un-advertise leaves an advertised index in neither set — after which the trigger
	 * never visits it — so it is the one remaining way to break the invariant, and
	 * `ReducedIndexMembershipCompletenessTest#assertMembershipMatchesIndexes` is what catches it. Keeping such
	 * an index accounted instead is not the answer: {@link #unregisterIndex(int, Bitmap)} has no other caller,
	 * so it would leak one residual entry per dropped index for the life of the collection.
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
		// An index in neither set falls through deliberately, and must NOT raise. This path's job is to absorb
		// drift: the map is an accelerator whose entries are allowed to be stale, so a removal naming an index
		// it never knew about has nothing to correct. Raising would convert benign staleness into a failed
		// commit, which is a strictly worse trade than the wasted call.
	}

	/**
	 * Returns `true` when this map already knows the reduced index, whether as covered or as residual. Used by
	 * the load-time build to skip a primary key the type index advertises more than once — a group reduced
	 * index is advertised once per referenced entity filed under it.
	 *
	 * @param indexPrimaryKey primary key of the reduced index
	 * @return `true` when the index is already known
	 */
	public boolean isKnown(int indexPrimaryKey) {
		return this.coveredIndexPrimaryKeys.contains(indexPrimaryKey)
			|| this.residualIndexPrimaryKeys.contains(indexPrimaryKey);
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
	 * Returns the primary keys of the reduced indexes left on the walk — those known to hold more owners than
	 * the threshold allows, AND those accounted for without their membership being readable. A caller must not
	 * infer a size from membership of this set; see {@link #residualIndexPrimaryKeys} for the full list of ways
	 * an index arrives here.
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
	 * Returns `true` when the map holds no coverage and no residual knowledge at all — the state a freshly
	 * created slice is in before it is seeded, and the state it returns to once its reference's last reduced
	 * index is dropped. A collection that never fires a cross-entity trigger holds no slice at all rather than
	 * an empty one, so this is not the predicate for "this collection pays nothing"; that answer is the absence
	 * of the whole slice, see `GlobalEntityIndex#getReducedIndexMembership`.
	 *
	 * @return `true` when nothing is known
	 */
	public boolean isEmpty() {
		return this.coveredIndexPrimaryKeys.isEmpty() && this.residualIndexPrimaryKeys.isEmpty();
	}

	/**
	 * Estimates the heap this map occupies, through {@link VMLayout} — the accounting the enclosing sum uses.
	 * The figure is added straight into `GlobalEntityIndex#getHeapSizeInBytes`, whose other terms are all
	 * {@link VMLayout}-based, so measuring this one against different header and reference widths would report
	 * a single number assembled from two models.
	 *
	 * @return estimated size in bytes
	 */
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		// the map charges its own keys: an owner primary key is boxed once per entry and held here alone
		final long boxedInteger = layout.sizeOfObject(Integer.BYTES);
		// the two thresholds, plus the indexPrimaryKeysByOwner / coveredOwners / coveredIndexPrimaryKeys /
		// residualIndexPrimaryKeys slots
		return layout.sizeOfObject(2L * Integer.BYTES + 4L * layout.referenceSize()) +
			this.indexPrimaryKeysByOwner.getHeapSizeInBytes(
				key -> boxedInteger,
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
			// Reachable and correct, so it must not raise. The promotion path is where it happens: `ownerAdded`
			// hands `dropCoverage` the membership AFTER the insert, which includes the owner that has just
			// crossed the index above the threshold - and that owner never got an entry,
			// because the promotion branch replaced the `recordMembership` call that would have made one. Every
			// other member of the set does have one. Raising here would fail the very commit that promotes an
			// index out of coverage.
			return;
		}
		existing.remove(indexPrimaryKey);
		if (existing.isEmpty()) {
			this.indexPrimaryKeysByOwner.remove(ownerPrimaryKey);
			this.coveredOwners.remove(ownerPrimaryKey);
		}
	}

}
