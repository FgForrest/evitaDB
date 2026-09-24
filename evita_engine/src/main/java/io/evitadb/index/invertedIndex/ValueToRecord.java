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

import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.PrimitiveIterator.OfInt;

/**
 * A single "bucket" of an {@link InvertedIndex}: one {@link Comparable} {@link #getValue() value} together with the
 * ordered, distinct set of record ids that carry that value.
 *
 * The inverted index is almost always *mixed* - pure-unique attributes are served by a separate `UniqueIndex`, so an
 * inverted index blends low-cardinality buckets (many records per value) with the long tail of near-unique values
 * (exactly one record per value). The two implementations of this interface let the representation degrade *per
 * bucket*:
 *
 * - {@link ValueToRecordBitmap} - the multi-record, {@link io.evitadb.roaringbitmap.PersistentRoaringBitmap}-backed bucket. It is
 *   *mutable*: record ids are added / removed in place, isolated transactionally by the inner
 *   {@link TransactionalBitmap}.
 * - {@link ValueToRecordArray} - the small multi-record bucket. It stores the ids as a sorted `int[]` (again no
 *   {@code PersistentRoaringBitmap}), and is *immutable* for the same reason.
 * - {@link ValueToRecordPrimitive} - the single-record bucket. It stores the lone record id as a bare `int` (no
 *   {@code PersistentRoaringBitmap}, no inner transactional bitmap) and is therefore *immutable*: any change produces a brand-new
 *   instance.
 *
 * This hierarchy is **not** how the buckets are stored. They live columnar-ly in a
 * {@link io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree}: the {@link #getValue() value} is the tree key, the
 * single-record case keeps its record id in a primitive `int` column, and the multi-record case spills into an overflow
 * {@link TransactionalBitmap}. A {@code ValueToRecord} is therefore a transient *flyweight* materialized on demand over
 * a leaf slot - the per-bucket projection that lets callers (serializer DTO, iterator bridge) read a bucket through one
 * uniform interface while the storage stays decomposed. {@link ValueToRecordPrimitive} is the flyweight over the
 * `int`-column case, {@link ValueToRecordArray} over the small-array overflow slot and {@link ValueToRecordBitmap}
 * over the bitmap one. A bucket's tier is not a function of its cardinality (the promote and demote thresholds
 * differ), so a consumer must dispatch on the implementation it is handed, never on {@link #size()}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface ValueToRecord
	extends VoidTransactionMemoryProducer<ValueToRecord>, Comparable<ValueToRecord>, Serializable {

	/**
	 * Returns the comparable value that this bucket represents.
	 */
	@Nonnull
	Serializable getValue();

	/**
	 * Returns the record ids assigned to {@link #getValue()} as an ordered, distinct {@link Bitmap}. For a
	 * {@link ValueToRecordPrimitive} this is a cheap single-element view that allocates no {@code PersistentRoaringBitmap}.
	 */
	@Nonnull
	Bitmap getRecordIds();

	/**
	 * Returns the number of record ids assigned to {@link #getValue()}. Allocation-free for both implementations
	 * (a primitive answers `1` directly without materializing its {@link #getRecordIds() view}).
	 */
	int size();

	/**
	 * Returns true when this bucket holds no record id.
	 */
	boolean isEmpty();

	/**
	 * Content-based equality of the *record set* of two buckets, independent of representation. A
	 * {@link ValueToRecordPrimitive} `{5}` and a {@link ValueToRecordBitmap} `{5}` are equal here even though their
	 * underlying {@link Bitmap} implementations are type-sensitive in their own `equals`. Allocation-free: compares
	 * {@link #size()} first, then iterates ids.
	 */
	boolean recordSetEquals(@Nullable ValueToRecord other);

	/**
	 * Content-based hash of the *record set* of this bucket, consistent with {@link #recordSetEquals(ValueToRecord)}
	 * and independent of representation.
	 *
	 * # Why this is a fold over the ids and not a bitmap's own hash
	 *
	 * The obvious implementation - delegate to {@link io.evitadb.roaringbitmap.PersistentRoaringBitmap#hashCode()} for
	 * the bitmap-backed buckets - is unusable here, and the reason is written on that method: it deliberately violates
	 * the hash/equals contract, guaranteeing equal hashes only for bitmaps that agree on `hasRunCompression()`. Its
	 * `equals`, by contrast, IS encoding-independent. Two buckets holding the same record set can therefore compare
	 * equal and hash differently whenever their container encodings differ.
	 *
	 * That is not hypothetical across the bucket tiers. A record set is held as a sorted `int[]` up to the promotion
	 * threshold and as a roaring bitmap above it, and because the demotion threshold is deliberately lower than the
	 * promotion one, a set at a cardinality inside that hysteresis window legitimately sits in either tier. The bitmap
	 * arm is run-optimised when read through a transactional layer while the array arm is not, so a contiguous run -
	 * exactly the shape run compression targets - would hash two ways for one set.
	 *
	 * Folding `31 * result + id` over the ids removes the representation from the answer entirely. It is
	 * {@link java.util.Arrays#hashCode(int[])}'s recurrence, so a one-element set yields `31 + id` - the canonical
	 * single-record hash the primitive bucket and the inverted index's own single-bucket short-circuit already use,
	 * preserved here by construction rather than by three hand-maintained special cases.
	 *
	 * Implementations must not override this: one definition is what makes the tiers agree.
	 *
	 * @return the representation-independent content hash of this bucket's record set
	 */
	default int recordSetHashCode() {
		int result = 1;
		final OfInt recordIdIterator = getRecordIds().iterator();
		while (recordIdIterator.hasNext()) {
			result = 31 * result + recordIdIterator.nextInt();
		}
		return result;
	}

}
