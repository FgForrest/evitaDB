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
import io.evitadb.dataType.iterator.ConstantIntIterator;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.ArrayUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.PrimitiveIterator.OfInt;

import static io.evitadb.core.Transaction.getTransactionalMemoryLayerIfExists;
import static io.evitadb.core.Transaction.isTransactionAvailable;

/**
 * This array keeps unique (distinct) integer values in unordered fashion.
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
public class TransactionalUnorderedIntArray implements TransactionalLayerProducer<UnorderedIntArrayChanges, int[]>, Serializable {
	@Serial private static final long serialVersionUID = 4753581686040233219L;
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	private final UnorderedLookup lookup;

	public TransactionalUnorderedIntArray() {
		this.lookup = new UnorderedLookup(ArrayUtils.EMPTY_INT_ARRAY);
	}

	public TransactionalUnorderedIntArray(int[] delegate) {
		this.lookup = new UnorderedLookup(delegate);
	}

	/**
	 * Returns array of positions that corresponds to the monotonic record id array {@link #getRecordIds()}.
	 */
	public int[] getPositions() {
		return this.lookup.getPositions();
	}

	/**
	 * Monotonic record array (i.e. record ids sorted by their id rather than the original order).
	 */
	public Bitmap getRecordIds() {
		return new BaseBitmap(this.lookup.getRecordIds());
	}

	/**
	 * Method returns record id on specified index of the array.
	 */
	public int get(int index) {
		final UnorderedIntArrayChanges layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.lookup.getRecordAt(index);
		} else {
			return layer.getMergedArray()[index];
		}
	}

	/**
	 * Method returns last record in the array.
	 *
	 * @return record id
	 * @throws ArrayIndexOutOfBoundsException when array is empty
	 */
	public int getLastRecordId() throws ArrayIndexOutOfBoundsException {
		final UnorderedIntArrayChanges layer = getTransactionalMemoryLayerIfExists(this);
		final int result;
		if (layer == null) {
			result = this.lookup.getLastRecordId();
		} else {
			result = layer.getLastRecordId();
		}
		return result;
	}

	/**
	 * Method returns the underlying array of record ids.
	 */
	public int[] getArray() {
		final UnorderedIntArrayChanges layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.lookup.getArray();
		} else {
			return layer.getMergedArray();
		}
	}

	/**
	 * Method returns subset of underlying array of record ids.
	 *
	 * @param startIndex inclusive
	 * @param endIndex   exclusive
	 */
	public int[] getSubArray(int startIndex, int endIndex) {
		final UnorderedIntArrayChanges layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return Arrays.copyOfRange(this.lookup.getArray(), startIndex, endIndex);
		} else {
			return Arrays.copyOfRange(layer.getMergedArray(), startIndex, endIndex);
		}
	}

	/**
	 * Method adds new record to the array, just after the record specified as `previousRecordId`.
	 */
	public void add(int previousRecordId, int recordId) {
		final UnorderedIntArrayChanges layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			this.lookup.addRecord(previousRecordId, recordId);
		} else {
			layer.addIntAfterRecord(previousRecordId, recordId);
		}
	}

	/**
	 * Method adds new record to the array on specified index.
	 */
	public void addOnIndex(int index, int recordId) {
		final UnorderedIntArrayChanges layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			this.lookup.addRecordOnIndex(index, recordId);
		} else {
			layer.addIntOnIndex(index, recordId);
		}
	}

	/**
	 * Method adds multiple record ids to the array.
	 */
	public void addAll(int previousRecordId, int... recordIds) {
		final UnorderedIntArrayChanges layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			int currentPrevRecId = previousRecordId;
			for (int recordId : recordIds) {
				this.lookup.addRecord(currentPrevRecId, recordId);
				currentPrevRecId = recordId;
			}
		} else {
			int currentPrevRecId = previousRecordId;
			for (int recordId : recordIds) {
				layer.addIntAfterRecord(currentPrevRecId, recordId);
				currentPrevRecId = recordId;
			}
		}
	}

	/**
	 * Method adds multiple record ids to the end of the array.
	 *
	 * @param recordIds record ids to add
	 */
	public void appendAll(int... recordIds) {
		final UnorderedIntArrayChanges layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			this.lookup.appendRecords(recordIds);
		} else {
			layer.appendRecords(recordIds);
		}
	}

	/**
	 * Method removes record id from the array.
	 */
	public void remove(int recordId) {
		final UnorderedIntArrayChanges layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			this.lookup.removeRecord(recordId);
		} else {
			layer.removeRecord(recordId);
		}
	}

	/**
	 * Method removes multiple record ids from the array.
	 */
	public void removeAll(int... recordIds) {
		final UnorderedIntArrayChanges layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			for (int recordId : recordIds) {
				this.lookup.removeRecord(recordId);
			}
		} else {
			for (int recordId : recordIds) {
				layer.removeRecord(recordId);
			}
		}
	}

	/**
	 * Method removes all records between two indexes.
	 *
	 * @param startIndex inclusive
	 * @param endIndex   exclusive
	 * @return removed records
	 */
	public int[] removeRange(int startIndex, int endIndex) {
		final UnorderedIntArrayChanges layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			return this.lookup.removeRange(startIndex, endIndex);
		} else {
			return layer.removeRange(startIndex, endIndex);
		}
	}

	/**
	 * Returns length of the array.
	 */
	public int getLength() {
		final UnorderedIntArrayChanges layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.lookup.size();
		} else {
			return layer.getMergedLength();
		}
	}

	/**
	 * Returns true if array contain no record ids.
	 */
	public boolean isEmpty() {
		final UnorderedIntArrayChanges layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.lookup.size() == 0;
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
		final UnorderedIntArrayChanges layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.lookup.findPosition(recordId);
		} else {
			return layer.indexOf(recordId);
		}
	}

	/**
	 * Returns true if record id is part of the array.
	 */
	public boolean contains(int recordId) {
		final UnorderedIntArrayChanges layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.lookup.findPosition(recordId) != Integer.MIN_VALUE;
		} else {
			return layer.contains(recordId);
		}
	}

	/**
	 * Returns iterator that allows to iterate through all record ids of the array.
	 */
	public OfInt iterator() {
		final UnorderedIntArrayChanges layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return new ConstantIntIterator(this.lookup.getArray());
		} else {
			return new TransactionalIntArrayIterator(this.lookup.getArray(), layer);
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
	public UnorderedIntArrayChanges createLayer() {
		return isTransactionAvailable() ? new UnorderedIntArrayChanges(this.lookup) : null;
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
	}

	@Nonnull
	@Override
	public int[] createCopyWithMergedTransactionalMemory(@Nullable UnorderedIntArrayChanges layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		if (layer == null) {
			return this.lookup.getArray();
		} else {
			return layer.getMergedArray();
		}
	}

}
