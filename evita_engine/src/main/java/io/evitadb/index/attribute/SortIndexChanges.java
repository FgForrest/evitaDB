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

import io.evitadb.core.transaction.memory.Snapshotable;
import io.evitadb.dataType.bPlusTree.CumulativeWeightBPlusTree;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.ArrayUtils.InsertionPosition;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Comparator;

import static io.evitadb.index.attribute.SortIndex.invert;
import static java.util.Optional.ofNullable;

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
	 * The comparator used to compare values in the sort index.
	 */
	@SuppressWarnings("rawtypes") private final Comparator valueComparator;

	/**
	 * Maps each distinct value to the start offset of its record-id block within {@link SortIndex#sortedRecords} — the
	 * cumulative weight (rank) of all strictly-smaller values. This intermediate structure is used only when contents of
	 * the {@link SortIndex} are modified. The sort index itself avoids holding this data for memory optimization.
	 *
	 * It is a {@link CumulativeWeightBPlusTree} keyed by value (using {@link #valueComparator}) whose per-key weight is
	 * the value's cardinality: {@link CumulativeWeightBPlusTree#rankOf} yields a block start offset in `O(log V)`, and
	 * inserting / removing a value or adjusting a cardinality is `O(log V)` — replacing the former flat prefix-sum array
	 * that re-stamped every following offset on each mutation. The cardinalities are sourced mode-agnostically from the
	 * owning {@link SortIndex} (owner mode: its `sortedValues` tree; view mode: the shared inverted index) when the tree
	 * is first built — see {@link #getValueTree()}. Transient: it is a rebuildable cache, never persisted.
	 */
	@Nullable private transient CumulativeWeightBPlusTree<Serializable> valueLocationTree;

	public SortIndexChanges(
		@Nonnull SortIndex sortIndex,
		@SuppressWarnings("rawtypes") @Nonnull Comparator valueComparator
	) {
		this.sortIndex = sortIndex;
		this.valueComparator = valueComparator;
	}

	/**
	 * Returns {@link SortedRecordsSupplier} that contains records ids sorted by value in ascending order.
	 * Result of the method is cached and additional calls obtain memoized result.
	 */
	@Nonnull
	public SortedRecordsSupplier getAscendingOrderRecordsSupplier() {
		return ofNullable(this.sortIndex.getReferenceKey())
			.map(
				referenceKey -> (SortedRecordsSupplier) new ReferenceSortedRecordsProvider(
					this.sortIndex.sortedRecords.getId(),
					this.sortIndex.sortedRecords.getArray(),
					this.sortIndex.sortedRecords.getPositions(),
					this.sortIndex.sortedRecords.getRecordIds(),
					this.sortIndex.createSortedComparableForwardSeeker(),
					referenceKey
				)
			)
			.orElseGet(
				() -> new SortedRecordsSupplier(
					this.sortIndex.sortedRecords.getId(),
					this.sortIndex.sortedRecords.getArray(),
					this.sortIndex.sortedRecords.getPositions(),
					this.sortIndex.sortedRecords.getRecordIds(),
					this.sortIndex.createSortedComparableForwardSeeker()
				)
			);
	}

	/**
	 * Returns {@link SortedRecordsSupplier} that contains records ids sorted by value in descending order.
	 * Result of the method is cached and additional calls obtain memoized result.
	 */
	@Nonnull
	public SortedRecordsSupplier getDescendingOrderRecordsSupplier() {
		return ofNullable(this.sortIndex.getReferenceKey())
			.map(
				referenceKey -> (SortedRecordsSupplier) new ReferenceSortedRecordsProvider(
					this.sortIndex.getId(),
					ArrayUtils.reverse(this.sortIndex.sortedRecords.getArray()),
					invert(this.sortIndex.sortedRecords.getPositions()),
					this.sortIndex.sortedRecords.getRecordIds(),
					this.sortIndex.createReversedSortedComparableForwardSeeker(),
					referenceKey
				)
			)
			.orElseGet(
				() -> new SortedRecordsSupplier(
					this.sortIndex.getId(),
					ArrayUtils.reverse(this.sortIndex.sortedRecords.getArray()),
					invert(this.sortIndex.sortedRecords.getPositions()),
					this.sortIndex.sortedRecords.getRecordIds(),
					this.sortIndex.createReversedSortedComparableForwardSeeker()
				)
			);
	}

	/**
	 * Computes record id of the record id that should precede currently inserted record that is associated with passed
	 * `value`. When record id should be placed on the first index {@link Integer#MIN_VALUE} is returned. This aligns
	 * with {@link io.evitadb.index.array.TransactionalUnorderedIntArray#add(int, int)} contract.
	 */
	public int computePreviousRecord(@Nonnull Serializable value, int recordId) {
		final CumulativeWeightBPlusTree<Serializable> valueTree = getValueTree();
		// block start = cumulative weight (rank) of all strictly-smaller values
		final int blockStart = valueTree.rankOf(value);
		if (valueTree.containsKey(value)) {
			// the value already owns a record block; its size equals the value's cardinality (its weight)
			final int blockEnd = blockStart + valueTree.weightOf(value);
			final int[] allRecordIds = this.sortIndex.sortedRecords.getArray();
			final int[] recordIdsInBlock = Arrays.copyOfRange(allRecordIds, blockStart, blockEnd);
			// within the block record ids are sorted in natural integer order
			final InsertionPosition recordInsertionPosition =
				ArrayUtils.computeInsertPositionOfIntInOrderedArray(recordId, recordIdsInBlock);
			// the target record id position is block start + relative position in the block, minus one
			final int recordPosition = blockStart + recordInsertionPosition.position() - 1;
			// a negative position means the record should be placed as the very first record of the sort index
			return recordPosition >= 0 ? allRecordIds[recordPosition] : Integer.MIN_VALUE;
		} else {
			// the value is absent and starts a fresh block at `blockStart`; the predecessor is the record immediately
			// before that offset, or none when the value sorts before every present value
			return blockStart == 0
				? Integer.MIN_VALUE
				: this.sortIndex.sortedRecords.get(blockStart - 1);
		}
	}

	/**
	 * Method alters internal data structures when new value (that was not present before) is inserted in the {@link SortIndex}.
	 */
	public void valueAdded(@Nonnull Serializable value) {
		// a value's FIRST record: insert it with cardinality one (the tree rejects an already-present value)
		getValueTree().insert(value, 1);
	}

	/**
	 * Method alters internal data structures when existing value cardinality is incremented in the {@link SortIndex}.
	 */
	public void valueCardinalityIncreased(@Nonnull Serializable value) {
		// one more record for an already-present value: bump its weight (the tree rejects an absent value)
		getValueTree().updateWeight(value, 1);
	}

	/**
	 * Method prepares value index if it hasn't exist yet. It needs to be called before anything in {@link SortIndex}
	 * is changed.
	 */
	public void prepare() {
		// force computation of the value tree
		getValueTree();
	}

	/**
	 * Method alters internal data structures when existing value is removed entirely from the {@link SortIndex}.
	 */
	public void valueRemoved(@Nonnull Serializable value) {
		// the value's LAST record was removed: drop it entirely (the tree rejects an absent value)
		getValueTree().remove(value);
	}

	/**
	 * Method alters internal data structures when existing value cardinality is decremented in the {@link SortIndex}.
	 */
	public void valueCardinalityDecreased(@Nonnull Serializable value) {
		// one fewer record for a value that keeps at least one: drop its weight by one
		getValueTree().updateWeight(value, -1);
	}

	/**
	 * Builds the value→cardinality tree lazily (memoized) if it does not yet exist. The owning index's ordered
	 * `(value, cardinality)` cursor — owner mode over its `sortedValues` tree, view mode over the shared inverted
	 * index's buckets — yields each distinct value in ascending order with its current cardinality (`>= 1`), which is
	 * inserted as the value's weight. {@link CumulativeWeightBPlusTree#rankOf} then answers a value's block start offset
	 * in `O(log V)`.
	 */
	@Nonnull
	CumulativeWeightBPlusTree<Serializable> getValueTree() {
		if (this.valueLocationTree == null) {
			@SuppressWarnings("unchecked")
			final CumulativeWeightBPlusTree<Serializable> theTree =
				new CumulativeWeightBPlusTree<>((Comparator<? super Serializable>) this.valueComparator);
			final SortIndex.ValueCardinalityCursor cursor = this.sortIndex.valueCursor();
			while (cursor.hasNext()) {
				final Serializable value = cursor.next();
				theTree.insert(value, cursor.cardinality());
			}
			this.valueLocationTree = theTree;
		}
		return this.valueLocationTree;
	}

	/**
	 * Returns the start offset of `value`'s record-id block within {@link SortIndex#sortedRecords} — the cumulative
	 * weight (rank) of all strictly-smaller values. Used by {@link SortIndex#getRecordsEqualToInternal(Serializable)} to slice the
	 * records associated with a value.
	 *
	 * @param value the value whose block start offset is computed
	 * @return the block start offset (`0` when `value` sorts before every present value)
	 */
	int computeBlockStart(@Nonnull Serializable value) {
		return getValueTree().rankOf(value);
	}

	/**
	 * Captures the current state of this layer for a
	 * {@link io.evitadb.core.transaction.memory.TransactionalLayerMaintainer} savepoint. Because
	 * {@link #valueLocationTree} is a rebuildable derived cache of the owning {@link SortIndex} (the class
	 * JavaDoc notes all data here can be safely thrown out and recreated), the memento is a cheap invalidation
	 * marker rather than a deep copy: it captures only the current tree reference (possibly `null` if the tree
	 * was never built) by reference. No B+ tree nodes are deep-copied; on rollback the tree is rebuilt from the
	 * already-restored authoritative sibling structures.
	 *
	 * @return a memento that {@link #restore(SortIndexChangesMemento)} uses to invalidate the cache
	 */
	@Nonnull
	@Override
	public SortIndexChangesMemento snapshot() {
		// O(1): capture the current cache reference (possibly null); the tree is a rebuildable cache and is
		// never deep-copied — restore() drops it and getValueTree() lazily rebuilds it from the SortIndex
		return new SortIndexChangesMemento(this.valueLocationTree);
	}

	/**
	 * Restores this layer to the captured savepoint state by invalidating the memoized
	 * {@link #valueLocationTree}.
	 *
	 * The value tree is a pure, rebuildable cache of {@link SortIndex} state, so the correct rollback is to drop
	 * the cache and let {@link #getValueTree()} rebuild it lazily from the (already-restored) authoritative
	 * sibling structures — {@link SortIndex#sortedRecords} and, in owner mode, the value-side tree — on the next
	 * access. This is O(1), always consistent given those siblings are restored by their own
	 * {@link io.evitadb.core.transaction.memory.Snapshotable} implementations, and is safe to invoke repeatedly
	 * with the same memento (each call simply nulls the cache).
	 *
	 * @param memento a memento previously produced by {@link #snapshot()} on this same layer
	 */
	@Override
	public void restore(@Nonnull SortIndexChangesMemento memento) {
		// O(1): drop the memoized cache so getValueTree() rebuilds it from the restored SortIndex on next access
		this.valueLocationTree = null;
	}

	/**
	 * Immutable memento carrying the savepoint state of a {@link SortIndexChanges} layer. The single captured
	 * value is the memoized {@link SortIndexChanges#valueLocationTree} reference at snapshot time (held by
	 * reference only, never deep-copied, possibly `null`). Because the tree is a rebuildable derived cache,
	 * {@link #restore} ignores the captured tree and simply invalidates the live cache; the field is retained to
	 * faithfully record the snapshot moment and keep the memento a self-describing carrier of the layer's sole
	 * mutable field.
	 *
	 * @param valueLocationTree the memoized value tree reference at snapshot time, or `null` if not yet built
	 */
	public record SortIndexChangesMemento(
		@Nullable CumulativeWeightBPlusTree<Serializable> valueLocationTree
	) {
	}

}
