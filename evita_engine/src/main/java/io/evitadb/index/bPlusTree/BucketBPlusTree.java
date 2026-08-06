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

package io.evitadb.index.bPlusTree;

import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.dataType.ConsistencySensitiveDataStructure;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BucketCursor;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.LeafPageHandle;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * The payload-agnostic surface shared by both bucket-tree flavours: the read-only navigation, sizing and page-stream
 * introspection that does not depend on whether a key maps to an `int` record set or a single `long` payload.
 *
 * The mutually-exclusive write surfaces live on the two sub-interfaces {@link IntRecordBucketTree} (the default `int`
 * record-set tree) and {@link LongPayloadBucketTree} (the UNIQUE single-`long` tree). A caller is handed one of those
 * narrow types by the factory that built the tree, so the payload mode is a compile-time property of the reference
 * rather than something to be re-checked at every call site — the wrong-mode write method is simply not visible.
 *
 * @param <K> the key (bucket value) type
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface BucketBPlusTree<K extends Comparable<K>> extends
	TransactionalLayerProducer<Void, TransactionalBucketBPlusTree<K>>,
	ConsistencySensitiveDataStructure,
	Serializable {

	/**
	 * Returns a forward {@link BucketCursor} positioned before the first bucket, walking the buckets in ascending key
	 * order.
	 *
	 * @return a fresh forward cursor over all buckets
	 */
	@Nonnull
	BucketCursor<K> cursor();

	/**
	 * Returns a forward {@link BucketCursor} positioned at (or, when absent, at the insertion point of) the given value,
	 * walking the buckets in ascending key order from there.
	 *
	 * @param value the value to position the cursor at
	 * @return a fresh forward cursor positioned at the value
	 */
	@Nonnull
	BucketCursor<K> cursor(@Nonnull K value);

	/**
	 * Returns a reverse {@link BucketCursor} positioned after the last bucket, walking the buckets in descending key
	 * order.
	 *
	 * @return a fresh reverse cursor over all buckets
	 */
	@Nonnull
	BucketCursor<K> reverseCursor();

	/**
	 * Tells whether the tree root is an internal node (the tree has more than a single leaf).
	 *
	 * @return true when the root is an internal node, false when the root is the sole leaf
	 */
	boolean isRootInternal();

	/**
	 * Enumerates the tree's leaf page handles in ascending key order — the page-emission foundation of the granular
	 * storage layout.
	 *
	 * @return the ordered list of leaf page handles; never empty
	 */
	@Nonnull
	List<LeafPageHandle<K>> leafPageHandles();

	/**
	 * Returns the number of buckets (distinct keys) in the tree.
	 *
	 * @return the bucket count
	 */
	int size();

	/**
	 * Returns the number of buckets (distinct keys) in the tree — an alias of {@link #size()} used where the bucket
	 * dimension is named explicitly alongside {@link #recordCount()}.
	 *
	 * @return the bucket count
	 */
	int bucketCount();

	/**
	 * Returns the total number of records across all buckets.
	 *
	 * @return the record count
	 */
	int recordCount();

	/**
	 * Returns the number of records associated with the given value without materializing a bitmap.
	 *
	 * @param value the value to look up (may be null ⇒ 0)
	 * @return the cardinality of the bucket
	 */
	int cardinalityOf(@Nullable K value);

	/**
	 * Tells whether a bucket with the given value exists.
	 *
	 * @param value the value to look up (may be null ⇒ false)
	 * @return true when a bucket with the value exists
	 */
	boolean contains(@Nullable K value);

	/**
	 * Returns the heap this tree occupies, in bytes, **including every boxed key it owns**, each priced by `keySizer`.
	 *
	 * # There is deliberately no sizer-less overload
	 *
	 * A tree always holds boxed keys: an internal node's separator array is `M[]` whatever the leaves chose, so even
	 * a tree keeping its keys inline as `long`s boxes one key per separator. A sizer-less form would have to price
	 * those at zero, and would then under-report by one box per separator — a shortfall that **grows with the tree**,
	 * which is the one direction a memory reading must never fail in. Passing a sizer is therefore mandatory.
	 *
	 * # Where the sizer is consulted, and where it is not
	 *
	 * The tree decides, because ownership depends on the column {@link ValueColumnFactory#forKey} picked and that is
	 * invisible from outside:
	 *
	 * - **Leaves that chose {@link BoxedObjectColumn}** own their keys, and a separator above them is the *identical
	 *   instance* — a split promotes the right leaf's first key by reference. The leaf keys are priced and the
	 *   separators are not, so one key is never counted twice. Any key type with neither a {@link LongKeyCodec} nor a
	 *   front-coded form — a `UUID`, for instance — lands here.
	 * - **Every other column kind** stores its keys as *values* — a front-coded byte block for `String`, parallel
	 *   primitive arrays for the integral, temporal and scaled-`BigDecimal` types — and ignores the sizer entirely.
	 *   The separators above them are boxes nothing else holds, so those are priced.
	 *
	 * What the caller still owns is whether a key is *this structure's* at all: return `0` for one it merely borrows
	 * from a longer-lived owner, and its real footprint for one it holds outright
	 * ({@link io.evitadb.index.IndexHeapSize#OWNED_KEY_SIZER} is the latter, shared by every index built on such a
	 * tree).
	 *
	 * # Cost
	 *
	 * Unlike every other statistics reading, this one is **not** `O(1)`: it walks every node, so the cost is
	 * `O(entries / blockSize)`. It belongs to `MEMORY_FOOTPRINT`, which is opt-in and documented expensive, and must
	 * never be called from a query path. Measured at production block sizes, a 10M-bucket tree of single-record
	 * buckets walks in ~2 ms; the same tree with a multi-record overflow bitmap per bucket takes ~300 ms, where
	 * roughly three quarters of the time is cache misses reaching 10M scattered bitmaps rather than the arithmetic
	 * itself (see `BucketBPlusTreeHeapSizeBenchmark` and `BitmapHeapSizeCostBenchmark`).
	 *
	 * @param keySizer prices a single boxed key; must return `0` for keys this tree does not own
	 * @return the heap footprint in bytes, including alignment padding
	 */
	long getHeapSizeInBytes(@Nonnull ToLongFunction<Object> keySizer);

}
