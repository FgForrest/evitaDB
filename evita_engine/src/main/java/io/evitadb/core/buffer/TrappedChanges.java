/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.core.buffer;


import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.dataType.iterator.ContinuingIterator;
import io.evitadb.dataType.iterator.EmptyIterator;
import io.evitadb.store.model.StoragePart;

import javax.annotation.Nonnull;
import java.util.Iterator;

/**
 * TrappedChanges is a utility class that manages a collection of {@link StoragePart} changes
 * that need to be temporarily stored and processed later. This class provides a mechanism
 * to accumulate changes in a buffer-like structure before they are committed or processed.
 *
 * <p>The class supports two types of change storage:</p>
 * <ul>
 *   <li>Individual {@link StoragePart} objects added via {@link #addChangeToStore(StoragePart)}</li>
 *   <li>Iterators of {@link StoragePart} objects added via {@link #addIterator(Iterator, int)} for lazy evaluation</li>
 * </ul>
 *
 * <p>The class uses lazy initialization - the internal storage is only created when the first
 * change is added, making it memory-efficient for scenarios where no changes might occur.</p>
 *
 * <p>Key features:</p>
 * <ul>
 *   <li>Lazy initialization of the internal storage collections</li>
 *   <li>Support for both individual changes and iterator-based bulk changes</li>
 *   <li>Efficient iteration over all trapped changes using {@link ContinuingIterator}</li>
 *   <li>Memory-efficient design with zero overhead when no changes are present</li>
 * </ul>
 *
 * <p>Typical usage pattern:</p>
 * <pre>{@code
 * TrappedChanges trappedChanges = new TrappedChanges();
 *
 * // Add individual changes
 * trappedChanges.addChangeToStore(storagePart1);
 * trappedChanges.addChangeToStore(storagePart2);
 *
 * // Add iterator-based changes
 * trappedChanges.addIterator(someIterator, iteratorElementCount);
 *
 * // Process all trapped changes (both individual and from iterators)
 * Iterator<StoragePart> iterator = trappedChanges.getTrappedChangesIterator();
 * while (iterator.hasNext()) {
 *     StoragePart change = iterator.next();
 *     // Process the change
 * }
 *
 * // Get total count of all changes
 * int totalCount = trappedChanges.getTrappedChangesCount();
 * }</pre>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class TrappedChanges {
	/**
	 * A collection used to store individual {@link StoragePart} changes.
	 * Initialized lazily when the first change is added.
	 */
	private CompositeObjectArray<StoragePart> trappedChanges;
	/**
	 * A collection used to store iterators of {@link StoragePart} changes.
	 * Initialized lazily when the first iterator is added.
	 */
	private CompositeObjectArray<Iterator<StoragePart>> trappedIterators;
	/**
	 * Total number of elements across all iterators added to the store.
	 */
	private int totalIteratorElementCount;

	/**
	 * Adds a single {@link StoragePart} change to the store. If the store of trapped changes has not been initialized,
	 * a new instance is created and the change is added. Otherwise, the change is appended to the existing collection.
	 *
	 * @param change the {@link StoragePart} instance representing the change to be added to the store; must not be null
	 */
	public void addChangeToStore(@Nonnull StoragePart change) {
		if (this.trappedChanges == null) {
			this.trappedChanges = new CompositeObjectArray<>(StoragePart.class, change);
		} else {
			this.trappedChanges.add(change);
		}
	}

	/**
	 * Returns an iterator over the collection of trapped changes in the form of {@link StoragePart} objects.
	 * This includes both individual changes added via {@link #addChangeToStore(StoragePart)} and
	 * iterators added via {@link #addIterator(Iterator, int)}. If no changes are trapped, it returns an empty iterator.
	 *
	 * @return an iterator of {@link StoragePart} representing all trapped changes,
	 *         or an empty iterator if no changes are present
	 */
	@Nonnull
	public Iterator<StoragePart> getTrappedChangesIterator() {
		final Iterator<StoragePart> changesIterator = this.trappedChanges == null ?
			EmptyIterator.iteratorInstance(StoragePart.class) : this.trappedChanges.iterator();

		if (this.trappedIterators == null || this.trappedIterators.isEmpty()) {
			return changesIterator;
		} else {
			// Create array of all iterators (trapped changes + stored iterators)

			//noinspection unchecked
			final Iterator<StoragePart>[] allIterators = new Iterator[1 + this.trappedIterators.getSize()];
			allIterators[0] = changesIterator;

			// Add all stored iterators
			final Iterator<Iterator<StoragePart>> it = this.trappedIterators.iterator();
			int i = 1;
			while (it.hasNext()) {
				allIterators[i++] = it.next();
			}

			return new ContinuingIterator<>(allIterators);
		}
	}

	/**
	 * Returns the count of trapped changes currently stored. This includes both individual changes
	 * added via {@link #addChangeToStore(StoragePart)} and elements from iterators added via
	 * {@link #addIterator(Iterator, int)}. If no changes are trapped, the method returns 0.
	 *
	 * @return the total number of trapped changes, or 0 if there are no changes present
	 */
	public int getTrappedChangesCount() {
		final int directChangesCount = this.trappedChanges == null ? 0 : this.trappedChanges.getSize();
		return directChangesCount + this.totalIteratorElementCount;
	}

	/**
	 * Adds an iterator of {@link StoragePart} changes along with the total number of elements it contains.
	 * The iterator will be lazily evaluated when {@link #getTrappedChangesIterator()} is called.
	 * If the store of trapped iterators has not been initialized, a new instance is created.
	 *
	 * @param iterator the iterator containing {@link StoragePart} instances; must not be null
	 * @param size the total number of elements in the iterator; must be non-negative
	 */
	@SuppressWarnings("unchecked")
	public void addIterator(@Nonnull Iterator<StoragePart> iterator, int size) {
		if (this.trappedIterators == null) {
			this.trappedIterators = new CompositeObjectArray<>((Class<Iterator<StoragePart>>) (Class<?>) Iterator.class);
			this.trappedIterators.add(iterator);
		} else {
			this.trappedIterators.add(iterator);
		}
		this.totalIteratorElementCount += size;
	}
}
