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

/**
 * A single "bucket" of an {@link InvertedIndex}: one {@link Comparable} {@link #getValue() value} together with the
 * ordered, distinct set of record ids that carry that value.
 *
 * The inverted index is almost always *mixed* - pure-unique attributes are served by a separate `UniqueIndex`, so an
 * inverted index blends low-cardinality buckets (many records per value) with the long tail of near-unique values
 * (exactly one record per value). The two implementations of this interface let the representation degrade *per
 * bucket*:
 *
 * - {@link ValueToRecordBitmap} - the multi-record, {@link io.evitadb.index.bitmap.RoaringBitmap}-backed bucket. It is
 *   *mutable*: record ids are added / removed in place, isolated transactionally by the inner
 *   {@link TransactionalBitmap}.
 * - {@link ValueToRecordPrimitive} - the single-record bucket. It stores the lone record id as a bare `int` (no
 *   {@code RoaringBitmap}, no inner transactional bitmap) and is therefore *immutable*: any change produces a brand-new
 *   instance written back through the tree updater.
 *
 * The buckets live in a {@link io.evitadb.index.bPlusTree.TransactionalObjectBPlusTree} whose leaf-commit path is gated
 * on the value being a {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer}. Both implementations
 * satisfy that gate (this interface extends {@link VoidTransactionMemoryProducer}), so the generic B+ tree needs no
 * changes to host them - commit, path-copying copy-on-write, split / merge / steal and layer discard all keep working
 * unchanged. A {@link ValueToRecordPrimitive} is a no-op producer: it owns no transactional layer, so committing an
 * unchanged primitive returns the very same instance and the leaf slot is not even rewritten.
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
	 * {@link ValueToRecordPrimitive} this is a cheap single-element view that allocates no {@code RoaringBitmap}.
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
	 * Returns a transactional id identifying the *record set* of this bucket for formula-cache purposes (the input to
	 * {@link io.evitadb.core.query.algebra.deferred.BitmapSupplier#getHash()} /
	 * {@link io.evitadb.core.query.algebra.deferred.BitmapSupplier#gatherTransactionalIds()}). For a
	 * {@link ValueToRecordBitmap} this is the inner bitmap's {@link TransactionalBitmap#getId()}; for a
	 * {@link ValueToRecordPrimitive} it is the immutable instance's own stable id. It is distinct from {@link #getId()}
	 * (the {@link VoidTransactionMemoryProducer} bucket id, which is a meaningless constant).
	 *
	 * Each distinct logical record set has a unique id that is stable while the bucket instance lives and changes when
	 * the bucket is structurally replaced (promotion, record add / remove) - mirroring the existing
	 * {@link TransactionalBitmap} semantics so the cache invalidates exactly as before.
	 */
	long getRecordSetId();

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
	 */
	int recordSetHashCode();

}
