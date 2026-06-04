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

import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.SingleRecordBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;

/**
 * Immutable single-record {@link ValueToRecord} bucket: it stores the lone record id as a bare `int`, with no
 * {@link org.roaringbitmap.RoaringBitmap} and no inner {@link io.evitadb.index.bitmap.TransactionalBitmap}. This
 * is the long-tail / near-unique case of a (mixed) inverted index; it costs roughly an order of magnitude less heap
 * than the {@link ValueToRecordBitmap} representation of the same single id.
 *
 * # Why immutable
 *
 * Buckets are shared by reference across transactional leaf layers (the B+ tree decouples a leaf with a *shallow* array
 * copy). A {@link ValueToRecordBitmap} can be mutated in place safely only because its inner
 * {@link io.evitadb.index.bitmap.TransactionalBitmap} isolates the change in its own transactional layer. This bucket
 * has no such layer, so it must never be mutated in place - any change (adding a second record, removing the only
 * record) is performed by the {@link InvertedIndex} updater, which writes back a *new* instance (a
 * {@link ValueToRecordBitmap} on promotion) or deletes the bucket entirely. The immutability is what makes it a safe,
 * shareable, no-op transactional producer.
 *
 * # Transactional behaviour
 *
 * This is a no-op {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer}: it owns no transactional
 * layer. {@link #createCopyWithMergedTransactionalMemory(Void, TransactionalLayerMaintainer)} returns `this`, so on
 * commit the leaf slot is not rewritten and an unchanged primitive is free. {@link #removeLayer(TransactionalLayerMaintainer)}
 * is a no-op because there is nothing to discard.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ValueToRecordPrimitive implements ValueToRecord {
	@Serial private static final long serialVersionUID = -2787508697765140172L;
	/**
	 * Stable, unique id of this immutable record set, used for formula-cache identity / staleness via
	 * {@link #getRecordSetId()}. A fresh id is minted per instance, so a structural change (promotion, record add /
	 * remove all produce a new instance) yields a new id and invalidates the cache exactly like a replaced
	 * {@link io.evitadb.index.bitmap.TransactionalBitmap} would.
	 */
	private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * The value this bucket represents.
	 */
	private final Serializable value;
	/**
	 * The single record id assigned to {@link #value}.
	 */
	private final int recordId;

	public ValueToRecordPrimitive(@Nonnull Serializable value, int recordId) {
		this.value = value;
		this.recordId = recordId;
	}

	/**
	 * Returns the single record id held by this bucket.
	 */
	public int getRecordId() {
		return this.recordId;
	}

	@Nonnull
	@Override
	public Serializable getValue() {
		return this.value;
	}

	@Nonnull
	@Override
	public Bitmap getRecordIds() {
		// leanest possible view: one int, no backing array / RoaringBitmap; allocated on demand for the query path
		return new SingleRecordBitmap(this.recordId);
	}

	@Override
	public int size() {
		return 1;
	}

	@Override
	public boolean isEmpty() {
		return false;
	}

	@Override
	public long getRecordSetId() {
		return this.id;
	}

	@Override
	public boolean recordSetEquals(@Nullable ValueToRecord other) {
		if (other == null || other.size() != 1) {
			return false;
		}
		// single id comparison - allocation-free regardless of the other bucket's representation
		return other.getRecordIds().contains(this.recordId);
	}

	@Override
	public int recordSetHashCode() {
		// canonical single-record hash shared with ValueToRecordBitmap: 31 * 1 + id, so a primitive {5} and a
		// cardinality-1 bitmap {5} hash identically. A bucket is the primitive representation only when it holds a
		// single record, which is exactly the cardinality at which ValueToRecordBitmap mirrors this same formula.
		return 31 + this.recordId;
	}

	@Override
	public int compareTo(@Nonnull ValueToRecord o) {
		//noinspection unchecked,rawtypes
		return ((Comparable) this.value).compareTo(o.getValue());
	}

	/*
		TransactionalLayerProducer implementation - this bucket is an immutable, layer-less no-op producer
	 */

	@Nonnull
	@Override
	public ValueToRecord createCopyWithMergedTransactionalMemory(
		@Nullable Void layer,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		// immutable and layer-less: the committed copy is the very same instance (free on commit)
		return this;
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		// no-op: this bucket never opens a transactional layer, so there is nothing to discard
	}

	@Override
	public boolean equals(@Nullable Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof final ValueToRecordPrimitive that)) {
			return false;
		}
		return this.recordId == that.recordId && this.value.equals(that.value);
	}

	@Override
	public int hashCode() {
		return this.value.hashCode();
	}

	@Override
	public String toString() {
		return "ValueToRecordPrimitive{" +
			"value=" + this.value +
			", recordId=" + this.recordId +
			'}';
	}
}
