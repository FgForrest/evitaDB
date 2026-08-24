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

package io.evitadb.index.attribute;

import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory;
import io.evitadb.core.transaction.memory.Snapshotable;
import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import io.evitadb.index.array.TransactionalUnorderedIntArray;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.VMLayout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Comparator;
import java.util.function.Supplier;

import static io.evitadb.index.attribute.SortIndex.invert;

/**
 * Class contains intermediate computation data structures that speed up access to the {@link SortedRecordsSupplier}
 * implementations and also allow to modify contents of the {@link SortIndex} data. All data inside this class can be
 * safely thrown out and recreated from {@link SortIndex} internal data again.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
public class SortIndexChanges
	implements Serializable, Snapshotable<SortIndexChanges.SortIndexChangesMemento> {
	@Serial private static final long serialVersionUID = -4791973822619493092L;

	/**
	 * Reference to the {@link SortIndex} this data structure is linked to.
	 * It provides the foundational data on which sorting and modifications are based.
	 */
	private final SortIndex sortIndex;

	/**
	 * Memoized ascending-direction supplier arrays (see {@link MaterializedSortRecords}), rebuilt lazily on first
	 * request after each `sortedRecords` mutation. Transient rebuildable cache, never persisted.
	 */
	@Nullable private transient MaterializedSortRecords memoizedAscending;

	/**
	 * Memoized descending-direction supplier arrays (see {@link MaterializedSortRecords}), rebuilt lazily on first
	 * request after each `sortedRecords` mutation. Transient rebuildable cache, never persisted.
	 */
	@Nullable private transient MaterializedSortRecords memoizedDescending;

	public SortIndexChanges(@Nonnull SortIndex sortIndex) {
		this.sortIndex = sortIndex;
	}

	/**
	 * Returns {@link SortedRecordsSupplier} that contains record ids sorted by value in ascending order.
	 *
	 * The expensive materialized arrays (record-id order, record positions and the record-id bitmap — each an
	 * `O(N log N)` derivation of the owning index's `sortedRecords`) are memoized per direction and reused across calls
	 * until the next `sortedRecords` mutation invalidates them (see {@link #invalidateSupplierArrays()}). A fresh,
	 * lightweight forward seeker and provider wrapper are nonetheless built on every call: the seeker is a stateful,
	 * monotonic cursor and must never be shared between concurrent queries.
	 */
	@Nonnull
	public SortedRecordsSupplier getAscendingOrderRecordsSupplier() {
		return buildSupplier(false, this.sortIndex.createSortedComparableForwardSeeker());
	}

	/**
	 * Returns {@link SortedRecordsSupplier} that contains record ids sorted by value in descending order.
	 *
	 * Like the ascending counterpart, the materialized arrays are memoized per direction and reused until the next
	 * `sortedRecords` mutation invalidates them; the stateful seeker and provider wrapper are rebuilt on every call.
	 */
	@Nonnull
	public SortedRecordsSupplier getDescendingOrderRecordsSupplier() {
		return buildSupplier(true, this.sortIndex.createReversedSortedComparableForwardSeeker());
	}

	/**
	 * Builds a tree-backed {@link SortedRecordsSupplier} resolving positions straight from the owning index's live
	 * `sortedRecords` (`O(log N)`, no materialization). The expensive materialized arrays are exposed only lazily, via
	 * the direction-appropriate warm-memo accessors ({@link #getAscendingArrays()} / {@link #getDescendingArrays()}),
	 * so they are (re)built solely when a large-selection query falls back to the array merge-walk. A
	 * {@link ReferenceSortedRecordsProvider} is produced when the owning index carries a reference key, otherwise a
	 * plain {@link SortedRecordsSupplier}. The seeker is built fresh by the caller and passed in per request — it is a
	 * stateful, monotonic cursor and MUST NOT be memoized or shared between concurrent queries.
	 *
	 * @param descending whether the descending order is requested
	 * @param seeker     the freshly built value seeker for this direction
	 */
	@Nonnull
	private SortedRecordsSupplier buildSupplier(
		boolean descending,
		@Nonnull SortedRecordsSupplierFactory.SortedComparableForwardSeeker seeker
	) {
		final RepresentativeReferenceKey referenceKey = this.sortIndex.getReferenceKey();
		final TransactionalUnorderedIntArray sortedRecords = this.sortIndex.sortedRecords;
		final int recordCount = sortedRecords.getLength();
		// identity mirrors the former per-direction MaterializedSortRecords id
		final long transactionalId = descending ? this.sortIndex.getId() : sortedRecords.getId();
		// lazy, direction-appropriate warm-memo accessors used only by the array fallback (large selections)
		final Supplier<int[]> sortedRecordIdsSupplier = descending
			? () -> getDescendingArrays().sortedRecordIds()
			: () -> getAscendingArrays().sortedRecordIds();
		final Supplier<int[]> recordPositionsSupplier = descending
			? () -> getDescendingArrays().recordPositions()
			: () -> getAscendingArrays().recordPositions();
		final Supplier<Bitmap> allRecordsSupplier = descending
			? () -> getDescendingArrays().allRecords()
			: () -> getAscendingArrays().allRecords();
		// per-transaction layer is short-lived: a cold dense selection walks the tree (materializing nothing) rather
		// than warming arrays that this throwaway layer would rarely reuse - hence DenseSelectionWarmup.COLD_WALK.
		// The descending accessors already yield reversed/inverted arrays -> DESCENDING_OWN_ARRAYS (not mirrored).
		final SortDirectionBacking directionBacking = descending
			? SortDirectionBacking.DESCENDING_OWN_ARRAYS
			: SortDirectionBacking.ASCENDING;
		return SortedRecordsSupplier.createTreeBacked(
			transactionalId, sortedRecords, recordCount,
			sortedRecordIdsSupplier, recordPositionsSupplier, allRecordsSupplier, seeker, referenceKey,
			DenseSelectionWarmup.COLD_WALK, directionBacking
		);
	}

	/**
	 * Lazily materializes and memoizes the ascending-direction supplier arrays from the owning index's `sortedRecords`:
	 * the record-id order, the record positions and the (shared) record-id bitmap, all produced in a SINGLE tree walk +
	 * sort via {@link TransactionalUnorderedIntArray#materialize()} (see that method for why one sort yields all three),
	 * built once and reused until a mutation invalidates them via {@link #invalidateSupplierArrays()}.
	 */
	@Nonnull
	private MaterializedSortRecords getAscendingArrays() {
		if (this.memoizedAscending == null) {
			final TransactionalUnorderedIntArray.MaterializedOrder order = this.sortIndex.sortedRecords.materialize();
			this.memoizedAscending = new MaterializedSortRecords(
				this.sortIndex.sortedRecords.getId(),
				order.sortedRecordIds(),
				order.recordPositions(),
				order.allRecords()
			);
		}
		return this.memoizedAscending;
	}

	/**
	 * Lazily materializes and memoizes the descending-direction supplier arrays as pure `O(N)` derivations of the
	 * ascending arrays — the ascending record-id order reversed and the ascending positions inverted (both fresh
	 * allocations — {@link ArrayUtils#reverse(int[])} and {@link SortIndex#invert(int[])} never mutate their input, so
	 * the shared ascending arrays are untouched) — never a second tree walk + sort. The record-id bitmap is
	 * direction-independent and shared with the ascending holder.
	 */
	@Nonnull
	private MaterializedSortRecords getDescendingArrays() {
		if (this.memoizedDescending == null) {
			final MaterializedSortRecords ascending = getAscendingArrays();
			this.memoizedDescending = new MaterializedSortRecords(
				this.sortIndex.getId(),
				ArrayUtils.reverse(ascending.sortedRecordIds()),
				invert(ascending.recordPositions()),
				ascending.allRecords()
			);
		}
		return this.memoizedDescending;
	}

	/**
	 * Drops the memoized per-direction supplier arrays so the next supplier request rematerializes them from the
	 * current `sortedRecords` state (the shared record-id bitmap rides along inside {@link #memoizedAscending}). Invoked
	 * from every `sortedRecords` mutation hook and on {@link #restore(SortIndexChangesMemento)}.
	 */
	private void invalidateSupplierArrays() {
		this.memoizedAscending = null;
		this.memoizedDescending = null;
	}

	/**
	 * Notifies this helper that the sorted record order changed, so the memoized supplier arrays must be dropped.
	 * Invoked from every {@link SortIndex} mutation path.
	 */
	public void sortOrderChanged() {
		recordWarmUpSavepointTouch();
		invalidateSupplierArrays();
	}

	/**
	 * Registers this helper with the warm-up savepoint bracketing the current root entity mutation, if one is open, so
	 * that a rolled-back mutation leaves the memoized supplier arrays INVALIDATED (see {@link WarmUpSavepoint}).
	 *
	 * Outside a transaction this instance is not a diff layer but the owning {@link SortIndex}'s own long-lived
	 * scratch helper (see `SortIndex#getOrCreateSortIndexChanges`), so nothing discards it when a warm-up mutation
	 * fails. The method above already drops the arrays on the forward path; what the journal entry covers is an
	 * ORDER BY executed LATER inside the same root entity mutation, which would rematerialize them from the
	 * half-mutated `sortedRecords` and leave them stale once those are rewound.
	 *
	 * The existing {@link Snapshotable} contract is reused verbatim rather than hand-rolling an inverse: the memento
	 * carries no state and {@link #restore(SortIndexChangesMemento)} is exactly the re-invalidation wanted here.
	 *
	 * Recorded once per savepoint. Outside a savepoint it costs one {@link ThreadLocal} read returning `null`.
	 */
	private void recordWarmUpSavepointTouch() {
		final WarmUpSavepoint savepoint = WarmUpSavepoint.getIfOpen();
		if (savepoint != null) {
			savepoint.recordFirstTouch(this);
		}
	}

	/**
	 * Captures the current state of this layer for a
	 * {@link io.evitadb.core.transaction.memory.TransactionalLayerMaintainer} savepoint. Everything this layer holds
	 * is a rebuildable derived cache of the owning {@link SortIndex}, so the memento carries no state at all — it is a
	 * pure invalidation marker and {@link #restore(SortIndexChangesMemento)} simply drops the caches.
	 *
	 * @return a memento that {@link #restore(SortIndexChangesMemento)} uses to invalidate the derived caches
	 */
	@Nonnull
	@Override
	public SortIndexChangesMemento snapshot() {
		// O(1): the memoized supplier arrays are rebuildable caches and are never deep-copied — restore() drops them
		// and they are lazily rebuilt from the SortIndex
		return SortIndexChangesMemento.INSTANCE;
	}

	/**
	 * Restores this layer to the captured savepoint state by invalidating its rebuildable derived caches — the
	 * memoized per-direction supplier arrays ({@link #memoizedAscending} / {@link #memoizedDescending}).
	 *
	 * Both are pure, rebuildable caches of {@link SortIndex} state, so the correct rollback is to drop them and let
	 * {@link #getAscendingArrays()} rebuild them lazily from the (already-restored) authoritative
	 * {@link SortIndex#sortedRecords} on the next access. This is O(1), always consistent given that sibling is
	 * restored by its own {@link io.evitadb.core.transaction.memory.Snapshotable} implementation, and is safe to
	 * invoke repeatedly with the same memento (each call simply nulls the caches).
	 *
	 * @param memento a memento previously produced by {@link #snapshot()} on this same layer
	 */
	@Override
	public void restore(@Nonnull SortIndexChangesMemento memento) {
		// O(1): drop the memoized caches so they rebuild from the restored SortIndex on next access
		invalidateSupplierArrays();
	}

	/**
	 * Returns the heap this layer occupies, in bytes — its own object and whichever of the two direction caches have
	 * been materialized.
	 *
	 * {@link #sortIndex} contributes its **slot alone**: it is a back-reference to the very index that owns this
	 * layer, so following it would charge that index's whole graph a second time and recurse.
	 *
	 * The descending cache is priced **without its bitmap**. Both directions point at the same
	 * {@link MaterializedSortRecords#allRecords} instance — the record-id set does not depend on direction, and
	 * {@link #getDescendingArrays()} deliberately hands the ascending holder's bitmap straight through rather than
	 * rebuilding it. Two concurrently-live holders of one object: it is charged once, to the ascending cache, which
	 * always exists whenever the descending one does.
	 *
	 * @return the owned heap footprint in bytes, including alignment padding
	 */
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		// the sortIndex back-reference plus the two cache slots
		long size = layout.sizeOfObject(3L * layout.referenceSize());
		if (this.memoizedAscending != null) {
			size += sizeOfMaterializedSortRecords(this.memoizedAscending, true);
		}
		if (this.memoizedDescending != null) {
			size += sizeOfMaterializedSortRecords(this.memoizedDescending, false);
		}
		return size;
	}

	/**
	 * Prices one direction's supplier arrays.
	 *
	 * Package-private so {@link SortIndex} can price its own committed-snapshot cache with the same arithmetic — that
	 * one is built by an independent {@code materialize()} call and shares nothing with this layer, so it passes
	 * `true`.
	 *
	 * @param records         the materialized arrays to price
	 * @param chargeAllRecords whether the record-id bitmap belongs to this holder, or is shared with another
	 * @return the heap footprint in bytes, including alignment padding
	 */
	static long sizeOfMaterializedSortRecords(
		@Nonnull MaterializedSortRecords records,
		boolean chargeAllRecords
	) {
		final VMLayout layout = VMLayout.current();
		// id + the two array slots and the bitmap slot
		final long size = layout.sizeOfObject(Long.BYTES + 3L * layout.referenceSize())
			+ layout.sizeOfArray(records.sortedRecordIds().length, Integer.BYTES)
			+ layout.sizeOfArray(records.recordPositions().length, Integer.BYTES);
		return chargeAllRecords ? size + records.allRecords().getHeapSizeInBytes() : size;
	}

	/**
	 * Stateless memento marking the savepoint of a {@link SortIndexChanges} layer. The layer holds only rebuildable
	 * derived caches, which {@link #restore} drops unconditionally, so there is nothing to capture and a single shared
	 * instance serves every savepoint.
	 */
	public record SortIndexChangesMemento() {
		public static final SortIndexChangesMemento INSTANCE = new SortIndexChangesMemento();
	}

	/**
	 * Immutable holder of the memoized, expensive-to-build supplier arrays for one sort direction: the record-id order,
	 * the record positions and the record-id bitmap (each an `O(N log N)` derivation of the owning index's
	 * `sortedRecords`). A fresh stateful forward seeker and provider wrapper are built around these on every supplier
	 * request, so the seeker is never shared across queries.
	 *
	 * All three array/bitmap fields are READ-ONLY and shared across reader threads and (for {@link #allRecords}) across
	 * both directions — they must never be mutated in place.
	 *
	 * Package-private (not {@code private}) so {@link SortIndex} can reuse the same shape for its own
	 * committed-snapshot cache — see {@code SortIndex#getAscendingOrderRecordsSupplier()}.
	 *
	 * @param id              the transactional id carried by the produced provider
	 * @param sortedRecordIds record ids in sorted (value) order
	 * @param recordPositions record positions aligned with the sorted record ids
	 * @param allRecords      bitmap of all record ids in natural id order (direction-independent, shared)
	 */
	record MaterializedSortRecords(
		long id,
		@Nonnull int[] sortedRecordIds,
		@Nonnull int[] recordPositions,
		@Nonnull Bitmap allRecords
	) {
	}

}
