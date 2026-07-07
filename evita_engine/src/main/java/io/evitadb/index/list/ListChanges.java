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

package io.evitadb.index.list;

import io.evitadb.core.transaction.memory.Snapshotable;
import io.evitadb.core.transaction.memory.TransactionalLayerCreator;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.UndoJournal;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Transactional diff layer for {@link TransactionalList} that accumulates mutations (insertions and removals)
 * made within a single transaction without touching the original delegate list.
 *
 * When a transaction reads from the list, this layer merges its recorded changes on top of the immutable
 * delegate, presenting a consistent view. On commit, the merged state is applied; on rollback, this object
 * is simply discarded, leaving the delegate untouched.
 *
 * Removed positions are tracked as a sorted set of original delegate indexes. Inserted elements are tracked
 * in a `TreeMap` keyed by their effective logical index in the merged view.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2017
 */
@RequiredArgsConstructor
@NotThreadSafe
class ListChanges<V> implements Serializable, Snapshotable<ListChanges.ListChangesMemento<V>> {
	@Serial private static final long serialVersionUID = -4217133814767167202L;
	/**
	 * Original immutable list.
	 */
	@Getter private final List<V> listDelegate;
	/**
	 * Set of removed positions in a list (indexes).
	 */
	@Getter private final TreeSet<Integer> removedItems = new TreeSet<>();
	/**
	 * Map of added items on certain indexes.
	 */
	@Getter private final Map<Integer, V> addedItems = new TreeMap<>();
	/**
	 * Undo journal recording the inverse of every {@link #add}/{@link #remove}/{@link #cleanAll} while a savepoint is
	 * open, enabling an `O(1)` {@link #snapshot()} and an `O(intra-savepoint-ops)` {@link #restore(ListChangesMemento)}
	 * instead of deep-copying the whole accumulated diff. Because the index-shift helpers re-key many entries per op,
	 * each inverse precisely reverses its op; replayed in strict reverse order they exactly rewind the `+1 / -1`
	 * re-keying. Lazily allocated on the first {@link #snapshot()} (null for non-savepoint transactions, which pay
	 * nothing) and drained back to null when the savepoint commits (see {@link #releaseMemento(ListChangesMemento)}).
	 */
	@Nullable private UndoJournal undoJournal;

	/**
	 * Returns count of elements in the list with applied changes.
	 */
	public int size() {
		return this.listDelegate.size() - this.removedItems.size() + this.addedItems.size();
	}

	/**
	 * Returns true if list with applied changes is empty.
	 */
	public boolean isEmpty() {
		return (this.listDelegate.size() - this.removedItems.size() == 0) && this.addedItems.isEmpty();
	}

	/**
	 * Returns true if the list with applied changes contains the specified `obj` value.
	 */
	public boolean contains(@Nonnull Object obj) {
		// scan original contents of the list and compare them
		for (int i = 0; i < this.listDelegate.size(); i++) {
			final V examinedValue = this.listDelegate.get(i);
			// avoid items that are known to be removed
			if (!this.removedItems.contains(i) && Objects.equals(obj, examinedValue)) {
				return true;
			}
		}
		// scan newly added items of the list
		//noinspection SuspiciousMethodCalls
		return this.addedItems.containsValue(obj);
	}

	/**
	 * Adds new element on specified position.
	 */
	public void add(int index, @Nonnull V element) {
		if (index > size()) {
			throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size());
		}
		if (this.undoJournal != null) {
			// inverse of (increase >= index; put index): drop the inserted entry and un-shift the entries after it
			this.undoJournal.push(() -> {
				this.addedItems.remove(index);
				lowerIndexesGreaterThan(index);
			});
		}
		// increase indexes of all existing insertions after the modified index
		increaseIndexesGreaterThanOrEquals(index);
		// and add new element at specified index
		this.addedItems.put(index, element);
	}

	/**
	 * Removes the object from the list taking already made updates in the account.
	 */
	public boolean remove(@Nonnull Object obj) {
		// find first position of the added item that equals to passed argument
		Integer addedNewPosition = null;
		for (Entry<Integer, V> entry : this.addedItems.entrySet()) {
			if (Objects.equals(obj, entry.getValue())) {
				addedNewPosition = entry.getKey();
				break;
			}
		}
		// find first position of the existing (non-removed) item that equals to passed argument - counting in added elements
		Integer indexToRemove = null;
		int removedExistingPosition = -1;
		for (int j = 0; j < size(); j++) {
			do {
				if (!this.addedItems.containsKey(j)) {
					removedExistingPosition++;
				}
			} while (this.removedItems.contains(removedExistingPosition));
			if (removedExistingPosition > -1 && Objects.equals(obj, this.listDelegate.get(removedExistingPosition))) {
				indexToRemove = j;
				break;
			}
		}

		if (addedNewPosition == null && indexToRemove == null) {
			// no match was found
			return false;
		} else if (indexToRemove == null || (addedNewPosition != null && addedNewPosition < indexToRemove)) {
			// added item was found first - just replace it on specified position
			final int position = addedNewPosition;
			final V removedValue = this.addedItems.get(position);
			if (this.undoJournal != null) {
				// inverse of (remove position; lower > position): un-shift, then reinstate the dropped insertion
				this.undoJournal.push(() -> {
					increaseIndexesGreaterThanOrEquals(position);
					this.addedItems.put(position, removedValue);
				});
			}
			this.addedItems.remove(position);
			lowerIndexesGreaterThan(position);
			return true;
		} else {
			// existing item was found first - add the proper position to removed and lower insertion indexes of new items after it
			final int tombstonedDelegateIndex = removedExistingPosition;
			final int loweredFrom = indexToRemove;
			if (this.undoJournal != null) {
				// inverse of (add tombstone; lower > indexToRemove): un-shift, then drop the tombstone
				this.undoJournal.push(() -> {
					increaseIndexesGreaterThanOrEquals(loweredFrom);
					this.removedItems.remove(tombstonedDelegateIndex);
				});
			}
			this.removedItems.add(tombstonedDelegateIndex);
			lowerIndexesGreaterThan(loweredFrom);
			return true;
		}
	}

	/**
	 * Returns object on specified index taking changes into the account.
	 */
	@Nonnull
	public V get(int index) {
		if (index < 0 || index >= size()) {
			throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size());
		}
		// first try to find index in newly added elements
		if (this.addedItems.containsKey(index)) {
			return this.addedItems.get(index);
		} else {
			// when not found iterate through original list
			int examinedIndex = -1;
			for (int j = 0; j <= index; j++) {
				// skip added items - these were already looked up
				if (this.addedItems.containsKey(j)) {
					continue;
				}
				// skip removed items as well
				do {
					examinedIndex++;
				} while (this.removedItems.contains(examinedIndex));
				// when arrived on proper index return element
				if (j == index) {
					return this.listDelegate.get(examinedIndex);
				}
			}
			// unreachable: the bounds check above guarantees a valid index is always resolved above
			throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size());
		}
	}

	/**
	 * Removes object on specified position.
	 */
	@Nullable
	V remove(int index) {
		if (index >= size()) {
			throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size());
		}

		// first find the position in the added elements
		if (this.addedItems.containsKey(index)) {
			// if found remove it and lower indexes of all following new elements
			final V result = this.addedItems.remove(index);
			if (this.undoJournal != null) {
				// inverse of (remove index; lower > index): un-shift, then reinstate the dropped insertion
				this.undoJournal.push(() -> {
					increaseIndexesGreaterThanOrEquals(index);
					this.addedItems.put(index, result);
				});
			}
			lowerIndexesGreaterThan(index);
			return result;
		}

		// iterate through existing elements
		int examinedIndex = -1;
		for (int j = 0; j <= index; j++) {
			do {
				// increase existing index only when the new index doesn't match added element
				if (!this.addedItems.containsKey(j)) {
					examinedIndex++;
				}
				// and skip already removed elements
			} while (this.removedItems.contains(examinedIndex));
			// if index was found (should be)
			if (j == index) {
				// add the index of the underlying delegate list to the set of removed indexes
				final int tombstonedDelegateIndex = examinedIndex;
				if (this.undoJournal != null) {
					// inverse of (add tombstone; lower > index): un-shift, then drop the tombstone
					this.undoJournal.push(() -> {
						increaseIndexesGreaterThanOrEquals(index);
						this.removedItems.remove(tombstonedDelegateIndex);
					});
				}
				this.removedItems.add(examinedIndex);
				final V result = this.listDelegate.get(examinedIndex);
				// lower all indexes of newly added elements greater than the new index
				lowerIndexesGreaterThan(index);
				return result;
			}
		}

		return null;
	}

	/**
	 * Clears all changes recorded in this diff layer.
	 */
	void cleanAll(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		if (this.undoJournal != null) {
			// bulk op: capture both containers so a savepoint rollback can reinstate the pre-cleanAll diff wholesale
			final TreeMap<Integer, V> addedCopy = new TreeMap<>(this.addedItems);
			final TreeSet<Integer> removedCopy = new TreeSet<>(this.removedItems);
			this.undoJournal.push(() -> {
				this.addedItems.clear();
				this.addedItems.putAll(addedCopy);
				this.removedItems.clear();
				this.removedItems.addAll(removedCopy);
			});
		}
		// remove all added elements
		final Iterator<Entry<Integer, V>> it = this.addedItems.entrySet().iterator();
		while (it.hasNext()) {
			final Entry<Integer, V> entry = it.next();
			if (entry.getValue() instanceof TransactionalLayerCreator<?> transactionalLayerCreator) {
				transactionalLayerCreator.removeLayer(transactionalLayer);
			}
			it.remove();
		}
		// add all list delegate elements to removed set
		this.removedItems.clear();
		for (int i = 0; i < this.listDelegate.size(); i++) {
			this.removedItems.add(i);
		}
	}

	/**
	 * Decreases by one the logical indexes of all inserted items whose index is strictly greater than `position`.
	 * Called after an element is removed to keep the index map consistent.
	 */
	private void lowerIndexesGreaterThan(int position) {
		final Map<Integer, V> items = new HashMap<>();
		final Iterator<Entry<Integer, V>> it = this.addedItems.entrySet().iterator();
		while (it.hasNext()) {
			final Entry<Integer, V> entry = it.next();
			if (entry.getKey() > position) {
				Assert.isTrue(
					entry.getKey() - 1 > -1,
					"Illegal state - attempt to lower index of element that is at the start of the list!"
				);
				items.put(entry.getKey() - 1, entry.getValue());
				it.remove();
			}
		}
		this.addedItems.putAll(items);
	}

	/**
	 * Increases by one the logical indexes of all inserted items whose index is greater than or equal to `position`.
	 * Called before a new element is inserted to make room at `position`.
	 */
	private void increaseIndexesGreaterThanOrEquals(int position) {
		final Map<Integer, V> items = new HashMap<>();
		final Iterator<Entry<Integer, V>> it = this.addedItems.entrySet().iterator();
		while (it.hasNext()) {
			final Entry<Integer, V> entry = it.next();
			if (entry.getKey() >= position) {
				items.put(entry.getKey() + 1, entry.getValue());
				it.remove();
			}
		}
		this.addedItems.putAll(items);
	}

	/**
	 * Captures the current diff state ({@link #removedItems} and {@link #addedItems}) into a memento. Both collections
	 * are shallow-copied into fresh ordered containers so a later {@link #add(int, Object)} / {@link #remove(int)} /
	 * {@link #cleanAll(TransactionalLayerMaintainer)} (or the internal index-shift helpers) cannot mutate the captured
	 * state (memento-independence invariant). The immutable {@link #listDelegate} baseline is the shared read-only
	 * source and is therefore deliberately not captured. The element references stored as {@link #addedItems} values are
	 * copied by reference only: when an element is itself a nested transactional producer it owns its own diff layer,
	 * rolled back by its own {@link Snapshotable} at the maintainer level (nested-layer-boundary invariant).
	 *
	 * @return a memento holding independent copies of the current removedItems and addedItems deltas
	 */
	@Nonnull
	@Override
	public ListChangesMemento<V> snapshot() {
		if (this.undoJournal == null) {
			this.undoJournal = new UndoJournal();
		}
		return new ListChangesMemento<>(this.undoJournal.mark());
	}

	/**
	 * Resets this diff layer back to the state captured by the given memento, discarding any insertions / removals
	 * recorded since the snapshot. Because {@link #removedItems} and {@link #addedItems} are final, their contents are
	 * cleared and refilled in place from the memento (copying out of the memento, so the same memento may be restored
	 * repeatedly). The shared immutable {@link #listDelegate} baseline is never touched, and {@link #size()} /
	 * {@link #isEmpty()} are computed on the fly from the restored collections, so no derived state needs fixing up.
	 *
	 * @param memento a memento previously produced by {@link #snapshot()} on this same layer
	 */
	@Override
	public void restore(@Nonnull ListChangesMemento<V> memento) {
		UndoJournal.assertRestorable(this.undoJournal, memento.mark());
		// replay the recorded inverse operations in reverse down to the mark; the tombstoned delegate indexes and
		// inserted elements are rewound together as a consistent pair, so the +1 / -1 re-keying stays intact
		if (this.undoJournal != null) {
			this.undoJournal.rollbackTo(memento.mark());
		}
	}

	/**
	 * Releases a closed savepoint's memento (see {@link Snapshotable#releaseMemento(Object)}) - on commit the changes are kept,
	 * on rollback {@link #restore} has already rewound them -
	 * so the journal entries recorded since the mark are discarded (never replayed). When the journal drains empty it is
	 * nulled out, restoring the allocation-free fast path for the rest of the transaction.
	 *
	 * @param memento the committed memento previously produced by {@link #snapshot()}
	 */
	@Override
	public void releaseMemento(@Nonnull ListChangesMemento<V> memento) {
		if (this.undoJournal != null) {
			this.undoJournal.releaseFrom(memento.mark());
			if (this.undoJournal.isEmpty()) {
				this.undoJournal = null;
			}
		}
	}

	/**
	 * Immutable, `O(1)` marker of a {@link ListChanges} diff snapshot: it holds only the {@link UndoJournal#mark()} to
	 * rewind the layer to on restore. The actual removedItems / addedItems deltas are rewound by replaying the journal's
	 * inverse operations, not copied here; the immutable {@code listDelegate} baseline (shared read-only source) is
	 * likewise not carried. Element references inside {@code addedItems} are never touched — a nested producer element
	 * owns its own diff layer, rolled back by its own {@link Snapshotable} at the maintainer level.
	 *
	 * @param mark the {@link UndoJournal#mark()} to rewind the layer to on restore
	 * @param <V>  the element type of the underlying list
	 */
	public record ListChangesMemento<V>(
		int mark
	) {
	}

}
