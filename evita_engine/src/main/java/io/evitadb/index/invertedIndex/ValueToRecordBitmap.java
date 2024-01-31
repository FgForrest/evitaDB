/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.index.invertedIndex;

import io.evitadb.core.transaction.memory.TransactionalCreatorMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerCreator;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.index.array.TransactionalObject;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;

/**
 * Histogram point represents single "bucket" in {@link InvertedIndex} representing single {@link Comparable} {@link #value}
 * and bitmap (ordered and distinct) of record ids.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
public class ValueToRecordBitmap<T extends Comparable<T>> implements TransactionalObject<ValueToRecordBitmap<T>, Void>,
	VoidTransactionMemoryProducer<ValueToRecordBitmap<T>>,
	TransactionalLayerProducer<Void, ValueToRecordBitmap<T>>,
	TransactionalCreatorMaintainer,
	Comparable<ValueToRecordBitmap<T>>,
	Serializable {
	@Serial private static final long serialVersionUID = 8584161806399686698L;
	/**
	 * The value.
	 */
	private final T value;
	/**
	 * Bitmap of all records ids that has this value.
	 */
	private final TransactionalBitmap recordIds;

	public ValueToRecordBitmap(@Nonnull T value) {
		this.value = value;
		this.recordIds = new TransactionalBitmap(EmptyBitmap.INSTANCE);
	}

	public ValueToRecordBitmap(@Nonnull T value, @Nonnull Bitmap recordIds) {
		this.value = value;
		this.recordIds = new TransactionalBitmap(recordIds);
	}

	public ValueToRecordBitmap(@Nonnull T value, @Nonnull TransactionalBitmap recordIds) {
		this.value = value;
		this.recordIds = recordIds;
	}

	public ValueToRecordBitmap(@Nonnull T value, @Nonnull int... recordIds) {
		this.value = value;
		this.recordIds = new TransactionalBitmap(new BaseBitmap(recordIds));
	}

	/**
	 * Returns comparable value that represents this bucket.
	 */
	@Nonnull
	public T getValue() {
		return value;
	}

	/**
	 * Registers new record ids for histogram point value.
	 * Already present record ids are silently skipped.
	 */
	public void addRecord(@Nonnull int... recordId) {
		this.recordIds.addAll(recordId);
	}

	/**
	 * Unregisters existing record ids from histogram point value.
	 * Non present record ids are silently skipped.
	 */
	public void removeRecord(@Nonnull int... recordId) {
		this.recordIds.removeAll(recordId);
	}

	/**
	 * Merges record ids of passed histogram point to this histogram point.
	 * Histogram point in the argument is required to have same value as this point.
	 */
	public void add(@Nonnull ValueToRecordBitmap<T> histogramBucket) {
		Assert.isTrue(value.compareTo(histogramBucket.value) == 0, "Values of the histogram point differs: " + value + " vs. " + histogramBucket.value);
		this.recordIds.addAll(histogramBucket.getRecordIds());
	}

	/**
	 * Subtracts record ids of passed histogram point from this histogram point.
	 * Histogram point in the argument is required to have same value as this point.
	 */
	public void remove(@Nonnull ValueToRecordBitmap<T> histogramBucket) {
		Assert.isTrue(value.compareTo(histogramBucket.value) == 0, "Values of the histogram point differs: " + value + " vs. " + histogramBucket.value);
		this.recordIds.removeAll(histogramBucket.getRecordIds());
	}

	/**
	 * Returns ordered array of distinct record ids of this histogram point.
	 */
	@Nonnull
	public TransactionalBitmap getRecordIds() {
		return this.recordIds;
	}

	/**
	 * Returns true if this histogram point contains no record ids.
	 */
	public boolean isEmpty() {
		return this.recordIds.isEmpty();
	}

	/**
	 * Compares {@link #value} of this and passed histogram point.
	 */
	@Override
	public int compareTo(@Nonnull ValueToRecordBitmap<T> o) {
		return value.compareTo(o.value);
	}

	/**
	 * Returns true if passed histogram bucket is equal not only by value but also by all assigned record ids.
	 */
	public boolean deepEquals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		@SuppressWarnings("unchecked") final ValueToRecordBitmap<T> that = (ValueToRecordBitmap<T>) o;
		return value.equals(that.value) && recordIds.equals(that.recordIds);
	}

	@Override
	public int hashCode() {
		return Objects.hash(value);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		@SuppressWarnings("unchecked") final ValueToRecordBitmap<T> that = (ValueToRecordBitmap<T>) o;
		return value.compareTo(that.value) == 0;
	}

	@Override
	public String toString() {
		return "ValueToRecordBitmap{" +
			"value=" + value +
			", recordIds=" + recordIds +
			'}';
	}

	/*
		TransactionalObject implementation
	 */

	@Nonnull
	@Override
	public ValueToRecordBitmap<T> createCopyWithMergedTransactionalMemory(Void layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		return new ValueToRecordBitmap<>(
			value,
			transactionalLayer.getStateCopyWithCommittedChanges(recordIds)
		);
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		this.recordIds.removeLayer(transactionalLayer);
	}

	@Nonnull
	@Override
	public ValueToRecordBitmap<T> makeClone() {
		return new ValueToRecordBitmap<>(value, new TransactionalBitmap(recordIds));
	}

	@Nonnull
	@Override
	public Collection<TransactionalLayerCreator<?>> getMaintainedTransactionalCreators() {
		return Collections.singleton(recordIds);
	}
}
