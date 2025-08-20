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
public class ValueToRecordBitmap implements TransactionalObject<ValueToRecordBitmap, Void>,
	VoidTransactionMemoryProducer<ValueToRecordBitmap>,
	TransactionalLayerProducer<Void, ValueToRecordBitmap>,
	TransactionalCreatorMaintainer,
	Comparable<ValueToRecordBitmap>,
	Serializable {
	@Serial private static final long serialVersionUID = 8584161806399686698L;
	/**
	 * The value.
	 */
	private final Serializable value;
	/**
	 * Bitmap of all records ids that has this value.
	 */
	private final TransactionalBitmap recordIds;

	public ValueToRecordBitmap(@Nonnull Serializable value) {
		this.value = value;
		this.recordIds = new TransactionalBitmap(EmptyBitmap.INSTANCE);
	}

	public ValueToRecordBitmap(@Nonnull Serializable value, @Nonnull Bitmap recordIds) {
		this.value = value;
		this.recordIds = new TransactionalBitmap(recordIds);
	}

	public ValueToRecordBitmap(@Nonnull Serializable value, @Nonnull TransactionalBitmap recordIds) {
		this.value = value;
		this.recordIds = recordIds;
	}

	public ValueToRecordBitmap(@Nonnull Serializable value, @Nonnull int... recordIds) {
		this.value = value;
		this.recordIds = new TransactionalBitmap(new BaseBitmap(recordIds));
	}

	/**
	 * Returns comparable value that represents this bucket.
	 */
	@Nonnull
	public Serializable getValue() {
		return this.value;
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
	public void add(@Nonnull ValueToRecordBitmap histogramBucket) {
		Assert.isTrue(this.value.equals(histogramBucket.value), "Values of the histogram point differs: " + this.value + " vs. " + histogramBucket.value);
		this.recordIds.addAll(histogramBucket.getRecordIds());
	}

	/**
	 * Subtracts record ids of passed histogram point from this histogram point.
	 * Histogram point in the argument is required to have same value as this point.
	 */
	public void remove(@Nonnull ValueToRecordBitmap histogramBucket) {
		Assert.isTrue(this.value.equals(histogramBucket.value), "Values of the histogram point differs: " + this.value + " vs. " + histogramBucket.value);
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
	public int compareTo(@Nonnull ValueToRecordBitmap o) {
		//noinspection unchecked,rawtypes
		return ((Comparable) this.value).compareTo(o.value);
	}

	/**
	 * Returns true if passed histogram bucket is equal not only by value but also by all assigned record ids.
	 */
	public boolean deepEquals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		final ValueToRecordBitmap that = (ValueToRecordBitmap) o;
		return this.value.equals(that.value) && this.recordIds.equals(that.recordIds);
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.value);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		final ValueToRecordBitmap that = (ValueToRecordBitmap) o;
		return this.value.equals(that.value);
	}

	@Override
	public String toString() {
		return "ValueToRecordBitmap{" +
			"value=" + this.value +
			", recordIds=" + this.recordIds +
			'}';
	}

	/*
		TransactionalObject implementation
	 */

	@Nonnull
	@Override
	public ValueToRecordBitmap createCopyWithMergedTransactionalMemory(Void layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		return new ValueToRecordBitmap(
			this.value,
			transactionalLayer.getStateCopyWithCommittedChanges(this.recordIds)
		);
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		this.recordIds.removeLayer(transactionalLayer);
	}

	@Nonnull
	@Override
	public ValueToRecordBitmap makeClone() {
		return new ValueToRecordBitmap(this.value, new TransactionalBitmap(this.recordIds));
	}

	@Nonnull
	@Override
	public Collection<TransactionalLayerCreator<?>> getMaintainedTransactionalCreators() {
		return Collections.singleton(this.recordIds);
	}
}
