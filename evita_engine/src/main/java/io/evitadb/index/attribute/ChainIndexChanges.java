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
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedComparableForwardSeeker;
import io.evitadb.core.transaction.memory.Snapshotable;
import io.evitadb.index.array.TransactionalUnorderedIntArray;
import io.evitadb.index.array.UnorderedLookup;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.VMLayout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.function.Supplier;

import static io.evitadb.index.attribute.SortIndex.invert;

/**
 * Class contains intermediate computation data structures that speed up access to the {@link SortedRecordsSupplier}
 * implementations and also allow to modify contents of the {@link ChainIndex} data. All data inside this class can be
 * safely thrown out and recreated from {@link ChainIndex} internal data again.
 *
 * The produced sorted-records suppliers come in two flavours, chosen per request by the owning index's consistency:
 *
 * - **tree-backed** (the common, {@link ChainIndex#isConsistent() consistent} case, `chains.size() <= 1`): the single
 *   chain is a head-first run of the whole {@link ChainIndex#elements} order-statistic tree, so that tree order IS the
 *   ascending sorted order. The supplier resolves positions straight from the live {@link TransactionalUnorderedIntArray}
 *   (no `int[N]` / bitmap materialization on the read path); the descending order mirrors it via `N - 1 - position`.
 * - **array-backed** (the rare {@link ChainIndex#isConsistent() inconsistent} case, multiple runs): the runs must be
 *   re-ordered by their `(state tier, length)` into the semi-consistent order (see {@link ChainIndex#getUnorderedLookup()}),
 *   which the tree order cannot express directly, so the flattened arrays are materialized and the legacy array
 *   merge-walk is used. The flattened lookup and its record-id bitmap are the only memoized derived caches.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
public class ChainIndexChanges
	implements Serializable, Snapshotable<ChainIndexChanges.ChainIndexChangesMemento> {

	@Serial private static final long serialVersionUID = -1108329020855413122L;
	/**
	 * Default implementation of {@link SortedComparableForwardSeeker} for chainable types. It makes no sense to
	 * attempt to compare two chainable types, so this implementation throws an exception.
	 */
	private static final SortedComparableForwardSeeker THROWING_COMPARABLE_SEEKER = position -> {
		throw new UnsupportedOperationException("Chainable types are not comparable one with another!");
	};
	/**
	 * Reference to the {@link ChainIndex} this data structure is linked to.
	 */
	private final ChainIndex chainIndex;
	/**
	 * Cached flattening of the chain runs in the semi-consistent order — built and memoized only when the owning index is
	 * {@link ChainIndex#isConsistent() inconsistent} and the array-backed supplier is therefore taken. It is invalidated
	 * together with {@link #recordIds} by {@link #reset()} on every mutation. A consistent index never populates it: its
	 * tree-backed supplier reads {@link ChainIndex#elements} directly.
	 */
	@Nullable private UnorderedLookup unorderedLookup;
	/**
	 * Cached {@link Bitmap} of all record ids in ascending id order, shared by both directions of the array-backed
	 * supplier. Populated lazily alongside {@link #unorderedLookup} (inconsistent case only) and invalidated with it.
	 */
	@Nullable private Bitmap recordIds;

	public ChainIndexChanges(@Nonnull ChainIndex chainIndex) {
		this.chainIndex = chainIndex;
	}

	/**
	 * Resets the internally cached data.
	 */
	public void reset() {
		this.unorderedLookup = null;
		this.recordIds = null;
	}

	/**
	 * Returns the heap this layer occupies, in bytes — its own object and whichever of its two derived caches have
	 * been materialized.
	 *
	 * {@link #chainIndex} contributes its **slot alone**: it is a back-reference to the index that owns this layer,
	 * so following it would charge that index's whole graph again and recurse.
	 *
	 * Both caches are charged in full and neither shares with the other: the bitmap is built through
	 * {@code new BaseBitmap(int...)}, which constructs a roaring bitmap from the ids rather than retaining the array
	 * it was handed. Both stay `null` for a {@link ChainIndex#isConsistent() consistent} index, whose tree-backed
	 * supplier never populates them — so this term appears only once a chain has gone inconsistent and been read.
	 *
	 * @return the owned heap footprint in bytes, including alignment padding
	 */
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		// the chainIndex back-reference plus the two cache slots
		long size = layout.sizeOfObject(3L * layout.referenceSize());
		if (this.unorderedLookup != null) {
			size += this.unorderedLookup.getHeapSizeInBytes();
		}
		if (this.recordIds != null) {
			size += this.recordIds.getHeapSizeInBytes();
		}
		return size;
	}

	/**
	 * Captures the two memoized derived caches by reference into an immutable memento.
	 *
	 * This class carries no authoritative transactional diff state of its own — it is a lazily-computed,
	 * recomputable read cache over the chain index sorted-records views (its own class JavaDoc states all
	 * data here can be safely thrown out and recreated from {@link ChainIndex} data). The genuine per-entity
	 * rollback of the chain index is delivered by the {@code elements}/{@code chains}/{@code predecessors}/
	 * {@code dirty} transactional members of {@link ChainIndex}, each its own {@link Snapshotable}; this
	 * memento merely preserves (or, on restore, restores) the memoized caches across a savepoint.
	 *
	 * A shallow (reference) capture honors the memento-independence invariant because both cached values are
	 * built from freshly-allocated arrays and never mutated in place afterwards ({@link UnorderedLookup} wraps a
	 * fresh flattening each rebuild, {@link BaseBitmap} a fresh bitmap); on recompute the field is
	 * reference-reassigned to a new object rather than mutated, so the memento's captured reference can never be
	 * corrupted. The final back reference to {@link ChainIndex} is intentionally excluded from the memento, and
	 * the sorted-records suppliers are deliberately not captured — they are rebuilt on demand from the
	 * (already-rolled-back) authoritative structures on the next read.
	 *
	 * @return an immutable memento holding the current cache references
	 */
	@Nonnull
	@Override
	public ChainIndexChangesMemento snapshot() {
		return new ChainIndexChangesMemento(this.unorderedLookup, this.recordIds);
	}

	/**
	 * Restores the two memoized derived caches from the given memento, reverting every cache (in)validation that
	 * happened since the memento was produced. Each field is reference-reassigned out of the memento, so the same
	 * memento can be restored repeatedly and stays a faithful snapshot of that moment. The sorted-records
	 * suppliers are not part of the memento; they re-derive lazily from the (already-rolled-back) {@link ChainIndex}
	 * on the next supplier read.
	 *
	 * @param memento a memento previously produced by {@link #snapshot()} on this same instance
	 */
	@Override
	public void restore(@Nonnull ChainIndexChangesMemento memento) {
		this.unorderedLookup = memento.unorderedLookup();
		this.recordIds = memento.recordIds();
	}

	/**
	 * Returns {@link SortedRecordsSupplier} that contains records ids chained by value in ascending order. A fresh
	 * lightweight supplier wrapper is built on every call (its inner materialized arrays, if the array fallback fires,
	 * are memoized on the wrapper instance and must never be shared between concurrent queries).
	 */
	@Nonnull
	public SortedRecordsSupplier getAscendingOrderRecordsSupplier() {
		return this.chainIndex.isConsistent()
			? buildTreeBackedSupplier(false)
			: buildArrayBackedSupplier(false);
	}

	/**
	 * Returns {@link SortedRecordsSupplier} that contains records ids chained by value in descending order. Like the
	 * ascending counterpart, a fresh supplier wrapper is built on every call.
	 */
	@Nonnull
	public SortedRecordsSupplier getDescendingOrderRecordsSupplier() {
		return this.chainIndex.isConsistent()
			? buildTreeBackedSupplier(true)
			: buildArrayBackedSupplier(true);
	}

	/**
	 * Builds a tree-backed supplier resolving positions straight from the owning index's live {@link ChainIndex#elements}
	 * (`O(log N)`, no materialization). The consistent index's single chain is a head-first run spanning the whole tree,
	 * so the tree order is exactly the ascending sorted order; the descending order mirrors it via `N - 1 - position`
	 * (no reversed copy). The expensive flattened arrays are exposed only lazily, via the direction-appropriate
	 * accessors, so they are (re)built solely when a large-selection query falls back to the array merge-walk (or the
	 * debug `PREFER_PRESORT_ARRAYS` override forces it). A {@link ReferenceSortedRecordsProvider} is produced when the
	 * owning index carries a reference key, otherwise a plain {@link SortedRecordsSupplier}.
	 *
	 * The per-direction transactional identity mirrors the historical assignment (ascending keyed by
	 * {@code predecessors.getId()}, descending by {@code chainIndex.getId()}) so the two orderings never share a
	 * downstream cache slot; the same asymmetry that {@link SortIndexChanges} uses (asc = child id, desc = parent id).
	 *
	 * @param descending whether the descending order is requested
	 */
	@Nonnull
	private SortedRecordsSupplier buildTreeBackedSupplier(boolean descending) {
		final TransactionalUnorderedIntArray elements = this.chainIndex.elements;
		final int recordCount = elements.getLength();
		final RepresentativeReferenceKey referenceKey = this.chainIndex.getReferenceKey();
		final long transactionalId = descending ? this.chainIndex.getId() : this.chainIndex.predecessors.getId();
		// lazy, direction-appropriate warm-memo accessors used ONLY by the array fallback: a consistent chain's single
		// `elements` run IS the ascending sorted order, so the ascending arrays come straight from it and the descending
		// ones are its reverse / inverse (fresh allocations that never mutate the shared ascending arrays)
		final Supplier<int[]> sortedRecordIdsSupplier = descending
			? () -> ArrayUtils.reverse(elements.getArray())
			: elements::getArray;
		final Supplier<int[]> recordPositionsSupplier = descending
			? () -> invert(elements.getPositions())
			: elements::getPositions;
		final Supplier<Bitmap> allRecordsSupplier = elements::getRecordIds;
		// per-transaction change layer is short-lived: a cold dense selection walks the tree (materializing nothing)
		// rather than warming arrays it would rarely reuse - hence DenseSelectionWarmup.COLD_WALK
		// the descending accessors already yield reversed/inverted arrays (direction-correct), so this supplier indexes
		// them directly -> DESCENDING_OWN_ARRAYS (not mirrored)
		final SortDirectionBacking directionBacking = descending
			? SortDirectionBacking.DESCENDING_OWN_ARRAYS
			: SortDirectionBacking.ASCENDING;
		return SortedRecordsSupplier.createTreeBacked(
			transactionalId, elements, recordCount,
			sortedRecordIdsSupplier, recordPositionsSupplier, allRecordsSupplier, THROWING_COMPARABLE_SEEKER,
			referenceKey, DenseSelectionWarmup.COLD_WALK, directionBacking
		);
	}

	/**
	 * Builds an array-backed supplier for the {@link ChainIndex#isConsistent() inconsistent} case: the multiple chain
	 * runs must be re-ordered into the semi-consistent order (see {@link ChainIndex#getUnorderedLookup()}), which the
	 * raw tree order cannot express, so the flattened record-id order / positions and the record-id bitmap are
	 * materialized (memoized in {@link #unorderedLookup} / {@link #recordIds} and shared across both directions and
	 * repeat calls until the next mutation). The descending order is the ascending flattening reversed / inverted.
	 *
	 * @param descending whether the descending order is requested
	 */
	@Nonnull
	private SortedRecordsSupplier buildArrayBackedSupplier(boolean descending) {
		final UnorderedLookup lookup = ensureUnorderedLookup();
		final Bitmap allRecords = ensureRecordIds(lookup);
		final RepresentativeReferenceKey referenceKey = this.chainIndex.getReferenceKey();
		final long transactionalId = descending
			? this.chainIndex.getId()
			: this.chainIndex.predecessors.getId();
		final int[] sortedRecordIds = descending
			? ArrayUtils.reverse(lookup.getArray())
			: lookup.getArray();
		final int[] recordPositions = descending
			? invert(lookup.getPositions())
			: lookup.getPositions();
		return referenceKey != null
			? new ReferenceSortedRecordsProvider(
				transactionalId, sortedRecordIds, recordPositions, allRecords, THROWING_COMPARABLE_SEEKER, referenceKey
			)
			: new SortedRecordsSupplier(
				transactionalId, sortedRecordIds, recordPositions, allRecords, THROWING_COMPARABLE_SEEKER
			);
	}

	/**
	 * Lazily flattens and memoizes the chain runs into the semi-consistent order for the array-backed (inconsistent)
	 * path; reused by both directions and repeat calls until {@link #reset()} invalidates it on the next mutation.
	 */
	@Nonnull
	private UnorderedLookup ensureUnorderedLookup() {
		UnorderedLookup lookup = this.unorderedLookup;
		if (lookup == null) {
			lookup = this.chainIndex.getUnorderedLookup();
			this.unorderedLookup = lookup;
		}
		return lookup;
	}

	/**
	 * Lazily materializes and memoizes the direction-independent record-id bitmap (all record ids in ascending id order)
	 * from the flattened lookup; shared by both directions of the array-backed supplier and invalidated with
	 * {@link #unorderedLookup}. It is READ-ONLY — never mutate it.
	 */
	@Nonnull
	private Bitmap ensureRecordIds(@Nonnull UnorderedLookup lookup) {
		Bitmap ids = this.recordIds;
		if (ids == null) {
			ids = new BaseBitmap(lookup.getRecordIds());
			this.recordIds = ids;
		}
		return ids;
	}

	/**
	 * Immutable memento carrying the two memoized derived caches of {@link ChainIndexChanges}.
	 *
	 * Both components are captured and restored by reference (no array/bitmap cloning): they are freshly-allocated,
	 * never-mutated-in-place values ({@link UnorderedLookup}, {@link Bitmap}). The owning {@link ChainIndex} back
	 * reference is deliberately not part of the memento because it is final and shared, and the sorted-records
	 * suppliers are not captured because they are rebuilt on demand from the (already-rolled-back) authoritative
	 * structures on the next read. Either component may be {@code null} when the corresponding cache was not (yet)
	 * computed — which is the norm for a {@link ChainIndex#isConsistent() consistent} index whose tree-backed supplier
	 * never populates them.
	 *
	 * @param unorderedLookup memoized flattened chain-run lookup (inconsistent case), or {@code null}
	 * @param recordIds       memoized ascending record-id bitmap, or {@code null}
	 */
	public record ChainIndexChangesMemento(
		@Nullable UnorderedLookup unorderedLookup,
		@Nullable Bitmap recordIds
	) {
	}

}
