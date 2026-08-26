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
import java.util.function.IntSupplier;
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
	 * Switches this tree into id-carrying mode: every bucket gains a stable **value id** that names its distinct value
	 * independently of where that value currently sits in the tree. Buckets already present are back-filled.
	 *
	 * The tree is told only how to MINT an id, never who owns the id space — that ownership sits with the index above
	 * it. Re-installing over a tree that already carries ids replaces the minting operation and back-fills nothing,
	 * which is exactly what a commit needs when it re-points a surviving tree at a surviving allocator.
	 *
	 * @param valueIdMinter mints the id of a value the tree has never held before
	 */
	void installValueIdMinter(@Nonnull IntSupplier valueIdMinter);

	/**
	 * Persisted-id variant of {@link #installValueIdMinter(IntSupplier)}: stamps the ids the tree carried when it was
	 * written — taken in ascending key order — onto the values already present, instead of minting fresh ones. Used by
	 * the load path of an index persisted in the inline shape, whose buckets are replayed through the ordinary insert
	 * path and therefore arrive without ids.
	 *
	 * @param valueIdMinter     mints the id of a value the tree has never held before, from now on
	 * @param persistedValueIds the ids of the values already present, in ascending key order, or `null` to mint fresh
	 *                          ones
	 */
	void installValueIdMinter(@Nonnull IntSupplier valueIdMinter, @Nullable int[] persistedValueIds);

	/**
	 * Switches this tree out of id-carrying mode, dropping every id it minted. Any structure still keyed by those ids
	 * must be discarded together with them.
	 */
	void removeValueIdMinter();

	/**
	 * @return `true` when every bucket in this tree carries a stable value id
	 */
	boolean carriesValueIds();

	/**
	 * Returns the stable id of a distinct value, in a single tree descent.
	 *
	 * @param value the bucket value to resolve (may be null ⇒ unassigned)
	 * @return the value's stable id, or `0` (the "unassigned" sentinel) when this tree carries no value ids or holds
	 *         no bucket for that value
	 */
	int valueIdOf(@Nullable K value);

	/**
	 * (Re)builds the `valueId -> value` directory against this tree's current committed content. Call it once per
	 * published version — after a commit merge, after a load, and when value ids are first switched on.
	 */
	void rebuildValueIdDirectory();

	/**
	 * Commit-merge variant of {@link #rebuildValueIdDirectory()} that re-stamps only the leaves the merge actually
	 * rebuilt. Valid ONLY straight after a commit merge — an in-place mutation changes a leaf's content without
	 * changing its instance, which this variant would skip.
	 */
	void rebuildValueIdDirectoryAfterMerge();

	/**
	 * Resolves a stable value id back to the distinct value it names — the reverse of {@link #valueIdOf}.
	 *
	 * **The caller owes the transaction check; this method does not make it.** The directory answers from the last
	 * PUBLISHED version of the tree and carries no diff layer, so while a transaction is open on the calling thread
	 * its own writes are invisible in both directions: an id minted inside the transaction has no entry at all, and
	 * an entry made before it addresses a slot the transaction may since have moved that value out of. Both come back
	 * `null`, which for a candidate-verifying consumer means quietly matching fewer entities than the query asked for.
	 * `InvertedIndex#getValueById` is the guarded entry point and refuses outright rather than under-report; anything
	 * reaching this method directly must take the same check, or a scan fallback, for itself.
	 *
	 * @param valueId the id to resolve
	 * @return the value that id names, or `null` when this tree carries no value ids, the directory has not been
	 *         built for the current version, or the id names nothing live
	 */
	@Nullable
	K valueOf(int valueId);

	/**
	 * Returns the heap the value id directory's location array occupies, in bytes; `0` when the tree carries none.
	 *
	 * @return the directory's dominant heap term
	 */
	long getValueIdDirectoryHeapSizeInBytes();

	/**
	 * Returns the next stable leaf id this tree would hand out — one more than the number of leaves it has ever
	 * created. Leaf-id stability has no behavioural symptom when lost, so this counter is what pins it.
	 *
	 * @return the next leaf id to be minted
	 */
	long getNextLeafId();

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
	 * `O(entries / blockSize)`. It belongs to the index detail call, which is opt-in and documented expensive, and must
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
