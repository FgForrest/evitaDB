/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.bitmap.SortedArrayBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.Serial;
import java.io.Serializable;
import java.util.PrimitiveIterator.OfInt;

/**
 * Immutable multi-record {@link ValueToRecord} bucket backed by a sorted `int[]`: the flyweight over the bucket tier
 * that keeps a small record set as a bare array rather than a
 * {@link io.evitadb.roaringbitmap.PersistentRoaringBitmap}. It sits between {@link ValueToRecordPrimitive} (one record
 * id) and {@link ValueToRecordBitmap} (a large record set), mirroring the three tiers the underlying
 * {@link io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree} leaf stores.
 *
 * # Why it exists
 *
 * Without it, every read of a small multi-record bucket through the flyweight surface - the value iterator, subset
 * building, the histogram slice - would have to wrap the array in a {@link ValueToRecordBitmap}, and that constructor
 * builds a roaring bitmap. A range query crossing a thousand small buckets would then build a thousand throwaway
 * roaring bitmaps, which is exactly the cost the array tier exists to remove.
 *
 * # Why immutable, and why it never reaches storage
 *
 * Like {@link ValueToRecordPrimitive} this is a transient, read-only projection of a leaf slot the tree owns, never
 * the authoritative storage: a change is applied to the leaf, and the flyweight is simply re-materialized. It owns no
 * transactional layer, so it commits to itself and discards nothing.
 *
 * **A persisted leaf page never contains one.** The page-emission path materializes a multi-record bucket as a
 * {@link ValueToRecordBitmap} so the wire format stays exactly what it has always been - a kind byte plus the record
 * ids - and no serializer has to learn a third shape. This flyweight is a query-path type only.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class ValueToRecordArray implements ValueToRecord {
	@Serial private static final long serialVersionUID = 4529473019745562361L;
	/**
	 * The value this bucket represents.
	 */
	private final Serializable value;
	/**
	 * The record ids assigned to {@link #value}, sorted by {@link Integer#compareUnsigned} and distinct. Owned by the
	 * tree leaf this flyweight projects and never written to.
	 */
	@Nonnull private final int[] recordIds;

	/**
	 * Creates the flyweight over a leaf slot's record array.
	 *
	 * @param value     the value this bucket represents
	 * @param recordIds the bucket's record ids, sorted by {@link Integer#compareUnsigned} and distinct; stored by
	 *                  reference and never written to
	 */
	public ValueToRecordArray(@Nonnull Serializable value, @Nonnull int... recordIds) {
		this.value = value;
		this.recordIds = recordIds;
	}

	@Nonnull
	@Override
	public Serializable getValue() {
		return this.value;
	}

	@Nonnull
	@Override
	public Bitmap getRecordIds() {
		// a read-only view sharing the array - no copy and no roaring bitmap, which is the whole point of this type
		return new SortedArrayBitmap(this.recordIds);
	}

	@Override
	public int size() {
		return this.recordIds.length;
	}

	@Override
	public boolean isEmpty() {
		return this.recordIds.length == 0;
	}

	@Override
	public boolean recordSetEquals(@Nullable ValueToRecord other) {
		if (other == null || other.size() != this.recordIds.length) {
			return false;
		}
		// every Bitmap implementation enumerates in the same (unsigned ascending) order, so a positional walk is
		// representation-independent and allocates nothing
		final OfInt otherIt = other.getRecordIds().iterator();
		for (final int recordId : this.recordIds) {
			if (recordId != otherIt.nextInt()) {
				return false;
			}
		}
		return true;
	}

	@Override
	public int recordSetHashCode() {
		if (this.recordIds.length == 1) {
			// the canonical single-record hash, shared with both sibling implementations so a one-record bucket hashes
			// identically whichever tier happens to hold it
			return 31 + this.recordIds[0];
		}
		// above cardinality one the canonical hash is PersistentRoaringBitmap's own, because ValueToRecordBitmap
		// delegates to it - and the two tiers must agree, since the promote and demote thresholds differ and the same
		// record set can therefore be found in either. This is the one operation that pays for a roaring bitmap, and
		// it is reached only by whole-index equality/hash, never by a query.
		return RoaringBitmapBackedBitmap.fromArray(this.recordIds).hashCode();
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
		if (!(o instanceof final ValueToRecordArray that)) {
			return false;
		}
		// value-only, consistent with both sibling implementations: a bucket is identified by the value it carries
		return this.value.equals(that.value);
	}

	@Override
	public int hashCode() {
		return this.value.hashCode();
	}

	@Override
	public String toString() {
		return "ValueToRecordArray{value=" + this.value + ", recordIds=" + getRecordIds() + '}';
	}

}
