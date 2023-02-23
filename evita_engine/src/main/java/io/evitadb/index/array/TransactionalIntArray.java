/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.index.array;

import io.evitadb.core.Transaction;
import io.evitadb.index.iterator.ConstantIntIterator;
import io.evitadb.index.transactionalMemory.TransactionalLayerMaintainer;
import io.evitadb.index.transactionalMemory.TransactionalLayerProducer;
import io.evitadb.index.transactionalMemory.TransactionalObjectVersion;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.ArrayUtils.InsertionPosition;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.PrimitiveIterator.OfInt;

import static io.evitadb.index.transactionalMemory.TransactionalMemory.getTransactionalMemoryLayer;
import static io.evitadb.index.transactionalMemory.TransactionalMemory.getTransactionalMemoryLayerIfExists;
import static io.evitadb.index.transactionalMemory.TransactionalMemory.isTransactionalMemoryAvailable;

/**
 * This array keeps unique (distinct) integer values in strictly ordered fashion (naturally ordered - ascending).
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
public class TransactionalIntArray implements TransactionalLayerProducer<IntArrayChanges, int[]>, Serializable {
	@Serial private static final long serialVersionUID = 7259098757116568796L;
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	private int[] delegate;

	public TransactionalIntArray() {
		this.delegate = new int[0];
	}

	public TransactionalIntArray(int[] delegate) {
		this.delegate = delegate;
	}

	/**
	 * Method returns record id on specified index of the array.
	 */
	public int get(int index) {
		final IntArrayChanges layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.delegate[index];
		} else {
			return layer.getMergedArray()[index];
		}
	}

	/**
	 * Method returns the underlying array or record ids.
	 */
	public int[] getArray() {
		final IntArrayChanges layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.delegate;
		} else {
			return layer.getMergedArray();
		}
	}

	/**
	 * Method adds new record to the array.
	 */
	public void add(int recordId) {
		final IntArrayChanges layer = getTransactionalMemoryLayer(this);
		if (layer == null) {
			this.delegate = ArrayUtils.insertIntIntoOrderedArray(recordId, this.delegate);
		} else {
			layer.addRecordId(recordId);
		}
	}

	/**
	 * Method adds new record to the array and returns the index where record was placed.
	 */
	public int addReturningIndex(int recordId) {
		final IntArrayChanges layer = getTransactionalMemoryLayer(this);
		if (layer == null) {
			final InsertionPosition insertionPosition = ArrayUtils.computeInsertPositionOfIntInOrderedArray(recordId, this.delegate);
			if (!insertionPosition.alreadyPresent()) {
				this.delegate = ArrayUtils.insertIntIntoArrayOnIndex(recordId, this.delegate, insertionPosition.position());
			}
			return insertionPosition.position();
		} else {
			layer.addRecordId(recordId);
			return layer.getIndexOf(recordId);
		}
	}

	/**
	 * Method adds multiple record ids to the array.
	 */
	public void addAll(int[] recordIds) {
		for (int recordId : recordIds) {
			add(recordId);
		}
	}

	/**
	 * Method removes record id from the array.
	 */
	public void remove(int recordId) {
		final IntArrayChanges layer = getTransactionalMemoryLayer(this);
		if (layer == null) {
			this.delegate = ArrayUtils.removeIntFromOrderedArray(recordId, this.delegate);
		} else {
			layer.removeRecordId(recordId);
		}
	}

	/**
	 * Method removes multiple record ids from the array.
	 */
	public void removeAll(int[] recordIds) {
		for (int recordId : recordIds) {
			remove(recordId);
		}
	}

	/**
	 * Returns length of the array.
	 */
	public int getLength() {
		final IntArrayChanges layer = getTransactionalMemoryLayerIfExists(this);
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
		final IntArrayChanges layer = getTransactionalMemoryLayerIfExists(this);
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
	public int indexOf(int recordId) {
		final IntArrayChanges layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return Arrays.binarySearch(this.delegate, recordId);
		} else {
			return layer.getIndexOf(recordId);
		}
	}

	/**
	 * Returns true if record id is part of the array.
	 */
	public boolean contains(int recordId) {
		final IntArrayChanges layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return Arrays.binarySearch(this.delegate, recordId) >= 0;
		} else {
			return layer.contains(recordId);
		}
	}

	/**
	 * Returns iterator that allows to iterate through all record ids of the array.
	 */
	public OfInt iterator() {
		final IntArrayChanges layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return new ConstantIntIterator(this.delegate);
		} else {
			return new TransactionalIntArrayIterator(this.delegate, layer);
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

	@Override
	public IntArrayChanges createLayer() {
		return isTransactionalMemoryAvailable() ? new IntArrayChanges(this.delegate) : null;
	}

	@Nonnull
	@Override
	public int[] createCopyWithMergedTransactionalMemory(@Nullable IntArrayChanges layer, @Nonnull TransactionalLayerMaintainer transactionalLayer, @Nullable Transaction transaction) {
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
