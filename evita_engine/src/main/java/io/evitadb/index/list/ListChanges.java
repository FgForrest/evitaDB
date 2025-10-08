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

import io.evitadb.core.transaction.memory.TransactionalLayerCreator;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
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
 * Transactional overlay data object for {@link TransactionalList} that keeps track of removed and added items
 * in the list.
 *
 * Contains combination of changes in a List and removals made upon it. There is no other possible way
 * how to track removals in a list than to keep an collection of removed ones.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2017
 */
@RequiredArgsConstructor
@NotThreadSafe
class ListChanges<V> implements Serializable {
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
			V examinedValue = this.listDelegate.get(i);
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
			this.addedItems.remove(addedNewPosition);
			lowerIndexesGreaterThan(addedNewPosition);
			return true;
		} else {
			// existing item was found first - add the proper position to removed and lower insertion indexes of new items after it
			this.removedItems.add(removedExistingPosition);
			lowerIndexesGreaterThan(indexToRemove);
			return true;
		}
	}

	/**
	 * Returns object on specified index taking changes into the account.
	 */
	public V get(int index) {
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
			return null;
		}
	}

	/**
	 * Removes object on specified position.
	 */
	V remove(int index) {
		if (index > size()) {
			throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size());
		}

		// first find the position in the added elements
		if (this.addedItems.containsKey(index)) {
			// if found remove it and lower indexes of all following new elements
			V result = this.addedItems.remove(index);
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
				this.removedItems.add(examinedIndex);
				V result = this.listDelegate.get(examinedIndex);
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
	 * Decreases indexes of all items above (excluding) passed position by one.
	 */
	private void lowerIndexesGreaterThan(Integer position) {
		final Map<Integer, V> items = new HashMap<>();
		final Iterator<Entry<Integer, V>> it = this.addedItems.entrySet().iterator();
		while (it.hasNext()) {
			final Entry<Integer, V> removedPosition = it.next();
			if (removedPosition.getKey() > position) {
				Assert.isTrue(
					removedPosition.getKey() - 1 > -1,
					"Illegal state - attempt to lower index of element that is at the start of the list!"
				);
				items.put(removedPosition.getKey() - 1, removedPosition.getValue());
				it.remove();
			}
		}
		this.addedItems.putAll(items);
	}

	/**
	 * Increased indexes of all items above (including) passed position by one.
	 */
	private void increaseIndexesGreaterThanOrEquals(Integer position) {
		final Map<Integer, V> items = new HashMap<>();
		final Iterator<Entry<Integer, V>> it = this.addedItems.entrySet().iterator();
		while (it.hasNext()) {
			final Entry<Integer, V> removedPosition = it.next();
			if (removedPosition.getKey() >= position) {
				items.put(removedPosition.getKey() + 1, removedPosition.getValue());
				it.remove();
			}
		}
		this.addedItems.putAll(items);
	}

}
