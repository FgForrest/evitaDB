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

package io.evitadb.index.array;

import io.evitadb.core.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.dataType.iterator.ConstantObjIterator;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.ArrayUtils.InsertionPosition;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import static io.evitadb.core.Transaction.getTransactionalMemoryLayerIfExists;
import static io.evitadb.core.Transaction.isTransactionAvailable;
import static java.util.Optional.ofNullable;

/**
 * This class envelopes simple object array and makes it transactional. This means, that the array can be updated
 * by multiple writers and also multiple readers can read from it's original array without spotting the changes made
 * in transactional access. Each transaction is bound to the same thread and different threads doesn't see changes in
 * another threads.
 *
 * Object handled by this {@link TransactionalComplexObjArray} are expected to be also {@link TransactionalObject} themselves
 * and support internal transactional memory handling.
 *
 * If no transaction is opened, changes are applied directly to the delegate array. In such case the class is not thread
 * safe for multiple writers!
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
public class TransactionalComplexObjArray<T extends TransactionalObject<T, ?> & Comparable<T>>
	implements TransactionalLayerProducer<ComplexObjArrayChanges<T>, T[]>, Serializable {
	@Serial private static final long serialVersionUID = 1929748392138616409L;
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	private final Class<T> objectType;
	private final boolean transactionalLayerProducer;
	private final BiConsumer<T, T> producer;
	private final BiConsumer<T, T> reducer;
	private final Comparator<T> comparator;
	private final BiPredicate<T, T> deepComparator;
	private final Predicate<T> obsoleteChecker;
	private T[] delegate;

	public TransactionalComplexObjArray(@Nonnull T[] delegate) {
		this(delegate, Comparator.naturalOrder());
	}

	public TransactionalComplexObjArray(@Nonnull T[] delegate, @Nonnull Comparator<T> comparator) {
		//noinspection unchecked
		this.objectType = (Class<T>) delegate.getClass().getComponentType();
		this.transactionalLayerProducer = TransactionalLayerProducer.class.isAssignableFrom(this.objectType);
		this.delegate = delegate;
		this.producer = null;
		this.reducer = null;
		this.obsoleteChecker = null;
		this.comparator = comparator;
		this.deepComparator = null;
	}

	public TransactionalComplexObjArray(
		@Nonnull T[] delegate,
		@Nonnull BiConsumer<T, T> producer,
		@Nonnull BiConsumer<T, T> reducer,
		@Nonnull Predicate<T> obsoleteChecker,
		@Nonnull BiPredicate<T, T> deepComparator
	) {
		this(
			delegate,
			producer, reducer, obsoleteChecker,
			Comparator.naturalOrder(), deepComparator
		);
	}

	public TransactionalComplexObjArray(
		@Nonnull T[] delegate,
		@Nonnull BiConsumer<T, T> producer,
		@Nonnull BiConsumer<T, T> reducer,
		@Nonnull Predicate<T> obsoleteChecker,
		@Nonnull Comparator<T> comparator,
		@Nonnull BiPredicate<T, T> deepComparator
	) {
		//noinspection unchecked
		this.objectType = (Class<T>) delegate.getClass().getComponentType();
		this.transactionalLayerProducer = TransactionalLayerProducer.class.isAssignableFrom(this.objectType);
		this.delegate = delegate;
		this.producer = producer;
		this.reducer = reducer;
		this.obsoleteChecker = obsoleteChecker;
		this.comparator = comparator;
		this.deepComparator = deepComparator;
	}

	/**
	 * Method returns record on specified index of the array.
	 */
	public T get(int index) {
		final ComplexObjArrayChanges<T> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.delegate[index];
		} else {
			return layer.getMergedArray()[index];
		}
	}

	/**
	 * Method returns the underlying array or records.
	 */
	public T[] getArray() {
		final ComplexObjArrayChanges<T> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.delegate;
		} else {
			return layer.getMergedArray();
		}
	}

	/**
	 * Method adds new record to the array.
	 */
	public void add(T recordId) {
		if (this.obsoleteChecker != null && this.obsoleteChecker.test(recordId)) {
			return;
		}

		final ComplexObjArrayChanges<T> layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		final InsertionPosition position = ArrayUtils.computeInsertPositionOfObjInOrderedArray(recordId, this.delegate, this.comparator);
		if (layer == null) {
			if (position.alreadyPresent()) {
				if (this.producer != null) {
					T original = this.delegate[position.position()];
					this.producer.accept(original, recordId);
				}
			} else {
				this.delegate = ArrayUtils.insertRecordIntoArrayOnIndex(recordId, this.delegate, position.position());
			}
		} else {
			layer.addRecordOnPosition(recordId, position.position());
		}
	}

	/**
	 * Method adds new record to the array.
	 *
	 * @return position when insertion happened
	 */
	public int addReturningIndex(T recordId) {
		if (this.obsoleteChecker != null && this.obsoleteChecker.test(recordId)) {
			return -1;
		}

		final ComplexObjArrayChanges<T> layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		final InsertionPosition position = ArrayUtils.computeInsertPositionOfObjInOrderedArray(recordId, this.delegate, this.comparator);
		if (layer == null) {
			if (position.alreadyPresent()) {
				if (this.producer != null) {
					T original = this.delegate[position.position()];
					this.producer.accept(original, recordId);
				}
			} else {
				this.delegate = ArrayUtils.insertRecordIntoArrayOnIndex(recordId, this.delegate, position.position());
			}
			return position.position();
		} else {
			return layer.addRecordOnPositionComputingIndex(recordId, position.position());
		}
	}

	/**
	 * Method adds multiple records to the array.
	 */
	public void addAll(T[] recordIds) {
		for (T recordId : recordIds) {
			add(recordId);
		}
	}

	/**
	 * Method removes record from the array.
	 *
	 * @return position where removal occurred or -1 if no removal occurred
	 */
	public int remove(T recordId) {
		if (this.obsoleteChecker != null && this.obsoleteChecker.test(recordId)) {
			return -1;
		}

		final ComplexObjArrayChanges<T> layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		final InsertionPosition position = ArrayUtils.computeInsertPositionOfObjInOrderedArray(recordId, this.delegate, this.comparator);
		if (layer == null) {
			if (position.alreadyPresent()) {
				T original = this.delegate[position.position()];
				if (this.reducer != null) {
					this.reducer.accept(original, recordId);
				}
				if (this.obsoleteChecker != null) {
					if (this.obsoleteChecker.test(original)) {
						this.delegate = ArrayUtils.removeRecordFromArrayOnIndex(this.delegate, position.position());
					}
				} else {
					this.delegate = ArrayUtils.removeRecordFromArrayOnIndex(this.delegate, position.position());
				}
				return position.position();
			}
		} else {
			return layer.removeRecordOnPosition(recordId, position.position(), position.alreadyPresent());
		}

		return -1;
	}

	/**
	 * Method removes multiple record from the array.
	 */
	public void removeAll(T[] recordIds) {
		for (T recordId : recordIds) {
			remove(recordId);
		}
	}

	/**
	 * Returns length of the array.
	 * This operation might be costly because it requires final array computation.
	 */
	public int getLength() {
		final ComplexObjArrayChanges<T> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.delegate.length;
		} else {
			return layer.getMergedArray().length;
		}
	}

	/**
	 * Returns true if array contain no records.
	 * This operation might be costly because it requires final array computation.
	 */
	public boolean isEmpty() {
		final ComplexObjArrayChanges<T> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return ArrayUtils.isEmpty(this.delegate);
		} else {
			return layer.getMergedArray().length == 0;
		}
	}

	/**
	 * Returns index (position) of the record in the array.
	 *
	 * @return negative value when record is not found, positive if found
	 */
	public int indexOf(T recordId) {
		final ComplexObjArrayChanges<T> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return Arrays.binarySearch(this.delegate, recordId);
		} else {
			return Arrays.binarySearch(getArray(), recordId);
		}
	}

	/**
	 * Returns true if record is part of the array.
	 */
	public boolean contains(T recordId) {
		final ComplexObjArrayChanges<T> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return Arrays.binarySearch(this.delegate, recordId) >= 0;
		} else {
			return layer.contains(recordId);
		}
	}

	/**
	 * Returns iterator that allows to iterate through all record of the array.
	 */
	public Iterator<T> iterator() {
		final ComplexObjArrayChanges<T> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return new ConstantObjIterator<>(this.delegate);
		} else {
			return new TransactionalComplexObjArrayIterator<>(this.delegate, layer, this.producer, this.reducer, this.obsoleteChecker);
		}
	}

	@Override
	public String toString() {
		return Arrays.toString(getArray());
	}

	/*
		TRANSACTIONAL OBJECT IMPLEMENTATION
	 */

	@Override
	public ComplexObjArrayChanges<T> createLayer() {
		if (this.producer != null) {
			return isTransactionAvailable() ? new ComplexObjArrayChanges<>(this.objectType, this.comparator, this.delegate, this.producer, this.reducer, this.obsoleteChecker, this.deepComparator) : null;
		} else {
			return isTransactionAvailable() ? new ComplexObjArrayChanges<>(this.objectType, this.comparator, this.delegate) : null;
		}
	}

	@Nonnull
	@Override
	public T[] createCopyWithMergedTransactionalMemory(@Nullable ComplexObjArrayChanges<T> layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		if (layer == null) {
			@SuppressWarnings("unchecked") final T[] copy = (T[]) Array.newInstance(this.objectType, this.delegate.length);
			for (int i = 0; i < this.delegate.length; i++) {
				T item = this.delegate[i];
				if (this.transactionalLayerProducer) {
					@SuppressWarnings("unchecked") final TransactionalLayerProducer<ComplexObjArrayChanges<T>, ?> theProducer = (TransactionalLayerProducer<ComplexObjArrayChanges<T>, ?>) item;
					//noinspection unchecked
					item = (T) theProducer.createCopyWithMergedTransactionalMemory(
						null, transactionalLayer
					);
				}
				copy[i] = item;
			}
			return copy;
		} else {
			return layer.getMergedArray(transactionalLayer);
		}
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		final ComplexObjArrayChanges<T> changes = transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		ofNullable(changes).ifPresent(it -> it.cleanAll(transactionalLayer));
	}
}
