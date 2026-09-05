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
	 * The read-only view of the record ids assigned to {@link #value}.
	 *
	 * Deliberately the VIEW rather than the `int[]` behind it: the array is the tree leaf's own storage, and holding it
	 * here would mean the tree had to hand a mutable reference to it across a package boundary. Holding the view keeps
	 * the array inside the tree, and lets {@link #getRecordIds()} answer without allocating.
	 */
	@Nonnull private final SortedArrayBitmap recordIds;

	/**
	 * Creates the flyweight over a leaf slot's record set.
	 *
	 * @param value     the value this bucket represents
	 * @param recordIds the read-only view of the bucket's record ids, as the bucket cursor produced it
	 */
	public ValueToRecordArray(@Nonnull Serializable value, @Nonnull SortedArrayBitmap recordIds) {
		this.value = value;
		this.recordIds = recordIds;
	}

	/**
	 * Creates the flyweight over record ids the CALLER owns, wrapping them in a read-only view.
	 *
	 * The bucket tree never uses this overload - it hands over the view its cursor already built, so that the leaf's
	 * own array never leaves the tree. This one exists for callers that produced the ids themselves and are content to
	 * give up ownership of the array, which the view then shares rather than copies.
	 *
	 * @param value     the value this bucket represents
	 * @param recordIds the bucket's record ids, sorted by {@link Integer#compareUnsigned} and distinct; ownership
	 *                  passes to this flyweight, so the caller must not write to the array afterwards
	 */
	public ValueToRecordArray(@Nonnull Serializable value, @Nonnull int... recordIds) {
		this(value, new SortedArrayBitmap(recordIds));
	}

	@Nonnull
	@Override
	public Serializable getValue() {
		return this.value;
	}

	@Nonnull
	@Override
	public Bitmap getRecordIds() {
		// the view the cursor already built - no copy, no allocation and no roaring bitmap, which is the whole point
		// of this type
		return this.recordIds;
	}

	@Override
	public int size() {
		return this.recordIds.size();
	}

	@Override
	public boolean isEmpty() {
		return this.recordIds.isEmpty();
	}

	@Override
	public boolean recordSetEquals(@Nullable ValueToRecord other) {
		if (other == null || other.size() != this.recordIds.size()) {
			return false;
		}
		// every Bitmap implementation enumerates in the same (unsigned ascending) order, so a positional walk is
		// representation-independent and allocates nothing
		final OfInt thisIt = this.recordIds.iterator();
		final OfInt otherIt = other.getRecordIds().iterator();
		while (thisIt.hasNext()) {
			if (thisIt.nextInt() != otherIt.nextInt()) {
				return false;
			}
		}
		return true;
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
		return "ValueToRecordArray{" +
			"value=" + this.value +
			", recordIds=" + this.recordIds +
			'}';
	}

}
