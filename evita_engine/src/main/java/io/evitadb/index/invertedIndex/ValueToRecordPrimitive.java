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
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.SingleRecordBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;

/**
 * Immutable single-record {@link ValueToRecord} bucket: it stores the lone record id as a bare `int`, with no
 * {@link io.evitadb.roaringbitmap.PersistentRoaringBitmap} and no inner {@link io.evitadb.index.bitmap.TransactionalBitmap}. This
 * is the long-tail / near-unique case of a (mixed) inverted index; it costs roughly an order of magnitude less heap
 * than the {@link ValueToRecordBitmap} representation of the same single id.
 *
 * # Why immutable
 *
 * This is a transient *flyweight* constructed on demand from a single-record leaf of the columnar
 * {@link io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree}: it is a read-only projection of the bucket's
 * primitive `int` column, never the authoritative storage. It is immutable because the column it reflects is owned by
 * the tree leaf; a change (adding a second record, removing the only record) is applied to the leaf by the
 * {@link InvertedIndex}, not to the flyweight, which is simply re-materialized (or promoted to a
 * {@link ValueToRecordBitmap} once the bucket spills to multiple records).
 *
 * # Transactional behaviour
 *
 * This is a no-op {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer}: it owns no transactional
 * layer (the column it projects is committed by the tree leaf, not by the flyweight).
 * {@link #createCopyWithMergedTransactionalMemory(Void, TransactionalLayerMaintainer)} returns `this` and
 * {@link #removeLayer(TransactionalLayerMaintainer)} is a no-op because there is nothing to discard.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ValueToRecordPrimitive implements ValueToRecord {
	@Serial private static final long serialVersionUID = -2787508697765140172L;
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
		// leanest possible view: one int, no backing array / PersistentRoaringBitmap; allocated on demand for the query path
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
