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
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;

import static io.evitadb.core.Transaction.getTransactionalMemoryLayerIfExists;
import static io.evitadb.core.Transaction.isTransactionAvailable;


/**
 * This array keeps unique (distinct) Comparable values in strictly ordered fashion (naturally ordered - ascending).
 *
 * This class envelopes simple primitive int array and makes it transactional. This means, that the array can be updated
 * by multiple writers and also multiple readers can read from it's original array without spotting the changes made
 * in transactional access. Each transaction is bound to the same thread and different threads doesn't see changes in
 * another threads.
 *
 * If no transaction is opened, changes are applied directly to the delegate array. In such case the class is not thread
 * safe for multiple writers!
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
@ThreadSafe
public class TransactionalObjArray<T> implements TransactionalLayerProducer<ObjArrayChanges<T>, T[]>, Serializable {
	@Serial private static final long serialVersionUID = 3207853222537134300L;
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	@Nonnull private T[] delegate;
	@Nonnull private final Comparator<T> comparator;

	public TransactionalObjArray(@Nonnull T[] delegate, @Nonnull Comparator<T> comparator) {
		this.delegate = delegate;
		this.comparator = comparator;
	}

	/**
	 * Method returns record id on specified index of the array.
	 */
	public T get(int index) {
		final ObjArrayChanges<T> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.delegate[index];
		} else {
			return layer.getMergedArray()[index];
		}
	}

	/**
	 * Method returns the underlying array or record ids.
	 */
	@Nonnull
	public T[] getArray() {
		final ObjArrayChanges<T> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.delegate;
		} else {
			return layer.getMergedArray();
		}
	}

	/**
	 * Method adds new record to the array.
	 */
	public void add(@Nonnull T recordId) {
		final ObjArrayChanges<T> layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			this.delegate = ArrayUtils.insertRecordIntoOrderedArray(recordId, this.delegate, this.comparator);
		} else {
			layer.addRecordId(recordId, this.comparator);
		}
	}

	/**
	 * Method adds multiple record ids to the array.
	 */
	public void addAll(@Nonnull T[] recordIds) {
		for (T recordId : recordIds) {
			add(recordId);
		}
	}

	/**
	 * Method removes record id from the array.
	 */
	public void remove(@Nonnull T recordId) {
		final ObjArrayChanges<T> layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			this.delegate = ArrayUtils.removeRecordFromOrderedArray(recordId, this.delegate, this.comparator);
		} else {
			layer.removeRecordId(recordId, this.comparator);
		}
	}

	/**
	 * Method removes multiple record ids from the array.
	 */
	public void removeAll(@Nonnull T[] recordIds) {
		for (T recordId : recordIds) {
			remove(recordId);
		}
	}

	/**
	 * Returns length of the array.
	 */
	public int getLength() {
		final ObjArrayChanges<T> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.delegate.length;
		} else {
			return layer.getMergedLength();
		}
	}

	/**
	 * Returns true if array contain no record ids.
	 */
	public boolean isEmpty() {
		final ObjArrayChanges<T> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return ArrayUtils.isEmpty(this.delegate);
		} else {
			return layer.getMergedLength() == 0;
		}
	}

	/**
	 * Returns index (position) of the record id in the array.
	 *
	 * @return negative value when record is not found, positive if found
	 */
	public int indexOf(@Nonnull T recordId) {
		final ObjArrayChanges<T> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return Arrays.binarySearch(this.delegate, recordId, this.comparator);
		} else {
			return layer.indexOf(recordId, this.comparator);
		}
	}

	/**
	 * Returns true if record id is part of the array.
	 */
	public boolean contains(@Nonnull T recordId) {
		final ObjArrayChanges<T> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return Arrays.binarySearch(this.delegate, recordId, this.comparator) >= 0;
		} else {
			return layer.contains(recordId, this.comparator);
		}
	}

	/**
	 * Returns iterator that allows to iterate through all record ids of the array.
	 */
	@Nonnull
	public Iterator<T> iterator() {
		final ObjArrayChanges<T> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return new ConstantObjIterator<>(this.delegate);
		} else {
			return new TransactionalObjArrayIterator<>(this.delegate, layer);
		}
	}

	@Override
	public int hashCode() {
		/* we deliberately want Object.hashCode() default implementation */
		return super.hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		/* we deliberately want Object.equals() default implementation */
		return super.equals(obj);
	}

	@Override
	public String toString() {
		return Arrays.toString(getArray());
	}

	/*
		TRANSACTIONAL OBJECT IMPLEMENTATION
	 */

	@Nullable
	@Override
	public ObjArrayChanges<T> createLayer() {
		return isTransactionAvailable() ? new ObjArrayChanges<>(this.delegate) : null;
	}

	@Nonnull
	@Override
	public T[] createCopyWithMergedTransactionalMemory(@Nullable ObjArrayChanges<T> layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		if (layer == null) {
			return this.delegate;
		} else {
			return layer.getMergedArray();
		}
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
	}
}
