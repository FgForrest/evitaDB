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

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.deferred.DeferredFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.response.TransactionalDataRelatedStructure;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.dataType.ConsistencySensitiveDataStructure;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.IndexHeapSize;
import io.evitadb.index.bPlusTree.BucketBPlusTree;
import io.evitadb.index.bPlusTree.IntRecordBucketTree;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BucketCursor;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.LeafPageHandle;
import io.evitadb.index.bPlusTree.ValueColumnFactory;
import io.evitadb.index.page.PageEmission;
import io.evitadb.index.page.PageStreamRegistry;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.SingleRecordBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.invertedIndex.suppliers.HistogramBitmapSupplier;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.VMLayout;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.ObjIntConsumer;
import java.util.function.Predicate;

/**
 * Histogram index is based on <a href="https://en.wikipedia.org/wiki/Histogram">Histogram data structure</a>. It's
 * organized as a set of "buckets" ordered from minimal to maximal {@link Comparable} value. Each bucket has assigned
 * bitmap (ordered distinct set of primitive integer values) that are assigned to bucket {@link ValueToRecord#getValue()}.
 *
 * Search in histogram is possible via. binary search with O(log n) complexity due its sorted nature. Set of records
 * are easily available as the set assigned to that value. Range look-ups are also available as boolean OR of all bitmaps
 * from / to looked up value threshold.
 *
 * The buckets are stored in a {@link TransactionalBucketBPlusTree} keyed by the (normalized) bucket value and ordered
 * by the supplied {@link Comparator}. A write therefore touches only the affected leaf and its ancestors (path-copying)
 * instead of reallocating the whole structure - this is the key write-latency improvement of this representation.
 *
 * The tree stores each bucket in a columnar leaf: the value is the tree key, single-record buckets keep their lone id
 * in a primitive `int` column (no {@link PersistentRoaringBitmap}), and multi-record buckets keep a mutable
 * {@link TransactionalBitmap} in a sparse overflow column. A single-record bucket promotes to a
 * bitmap when a second distinct record id is added; the reverse demotion (a multi bucket churned back down to a single
 * record) is deferred to the leaf commit-merge so a bucket never thrashes its representation within one transaction.
 * The {@link ValueToRecord} hierarchy survives only as a transient flyweight materialized on demand (serializer DTO +
 * iterator bridge); the tree never stores a per-bucket {@link ValueToRecord} object.
 *
 * Histogram MUST NOT contain same record id in multiple buckets. This prerequisite is not checked internally by this
 * data structure and client code must this ensure by its internal logic! If this prerequisite is not met, histogram
 * may return confusing results.
 *
 * Thread safety:
 *
 * Histogram supports transaction memory. This means, that the histogram can be updated by multiple writers and also
 * multiple readers can read from its original tree without spotting the changes made in transactional access. Each
 * transaction is bound to the same thread and different threads don't see changes in other threads.
 *
 * If no transaction is opened, changes are applied directly to the delegate tree. In such case the class is not thread
 * safe for multiple writers!
 *
 * The value id directory is the one piece of state a READER may write: {@link #getValueById(int)} catches the
 * warm-up path's writes up before it answers. That catch-up is single-flight and its completion is published through
 * the volatile {@link #valueIdDirectoryStale}, so concurrent readers cannot rebuild over one another; and the
 * directory itself is published as one immutable unit, so a reader already past the flag resolves through the
 * generation it read rather than through one being rebuilt around it. See {@link #refreshValueIdDirectory()}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
@SuppressWarnings({"rawtypes", "unchecked"})
@ThreadSafe
@Slf4j
public class InvertedIndex implements
	IndexDataStructure,
	ConsistencySensitiveDataStructure,
	VoidTransactionMemoryProducer<InvertedIndex>,
	Serializable {
	@Serial private static final long serialVersionUID = 3019703951858227807L;

	/**
	 * Leaf block size of the value → record-set tree. The inverted-index workload is point-lookup + bounded-range +
	 * write heavy, so block size is a read-vs-write trade-off: larger leaves give fewer, more sequential scans (and a
	 * shallower tree) but a larger array to copy on every in-leaf insert. Benchmarking
	 * (`InvertedIndexBlockSizeBenchmark`; results and analysis under
	 * `documentation/performance/individual/InvertedIndexBlockSizeBenchmark/`) puts the knee at `256` — versus the tree
	 * default `64` it cuts bounded-range and full-sweep latency by ~25% at scale, while the point-lookup cost stays flat
	 * and neither commit nor bulk-load regresses; `512`+ only helps the full-ordered sweep, which is not this index's
	 * dominant pattern. It is a runtime-only parameter — it does not affect the persisted form, which is rebuilt into the
	 * tree on load.
	 */
	private static final int VALUE_BLOCK_SIZE = 256;
	private static final int MIN_VALUE_BLOCK_SIZE = VALUE_BLOCK_SIZE / 2 - 1;
	private static final int MIN_INTERNAL_NODE_BLOCK_SIZE = (int) (Math.ceil(MIN_VALUE_BLOCK_SIZE / 2.0) - 1);
	/**
	 * This lambda lay out records by {@link ValueToRecord#getValue()} one after another.
	 */
	private static final BiFunction<long[], ValueToRecord[], Formula> UNSORTED_AGGREGATION_LAMBDA = (indexTransactionIds, histogramBuckets) -> new DeferredFormula(
		new HistogramBitmapSupplier(indexTransactionIds, histogramBuckets)
	);
	/**
	 * This lambda lay out records in natural ascending order.
	 */
	private static final BiFunction<long[], ValueToRecord[], Formula> SORTED_AGGREGATION_LAMBDA = (indexTransactionIds, histogramBuckets) -> {
		final Bitmap[] bitmaps = new Bitmap[histogramBuckets.length];
		for (int i = 0; i < histogramBuckets.length; i++) {
			bitmaps[i] = histogramBuckets[i].getRecordIds();
		}
		if (bitmaps.length == 0) {
			return EmptyFormula.INSTANCE;
		} else if (bitmaps.length == 1) {
			return new ConstantFormula(bitmaps[0]);
		} else {
			return new OrFormula(indexTransactionIds, bitmaps);
		}
	};
	/**
	 * Unique transactional id for this tree instance. Overrides the {@link VoidTransactionMemoryProducer} default
	 * (the constant `1L`) so that a consumer keying a cache on the tree's identity — notably a
	 * {@link io.evitadb.index.attribute.FilterIndexView} folded over this tree, whose `getId()` delegates here — gets a
	 * value that is UNIQUE per tree yet STABLE across commits that did not touch the tree: an untouched tree is carried
	 * forward by reference from {@link #createCopyWithMergedTransactionalMemory} (preserving its id), while a mutated
	 * tree becomes a fresh instance with a fresh id (correctly invalidating the dependent cache). This is a runtime-only
	 * field, regenerated on load — it is never persisted.
	 */
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * This is internal flag that tracks whether the index contents became dirty and needs to be persisted.
	 */
	private final TransactionalBoolean dirty;
	/**
	 * The buckets contain ordered comparable values with bitmaps of all records with such value. The tree is keyed by
	 * the (normalized) bucket value and ordered by {@link #comparator}. Each bucket is stored in a columnar leaf (single
	 * id as a primitive column, multi-record buckets as a sparse overflow bitmap column); the per-bucket
	 * {@link ValueToRecord} object is never stored, only materialized as a flyweight on demand.
	 */
	private final IntRecordBucketTree buckets;
	/**
	 * Normalizer is used to convert objects to serializable form.
	 */
	@Nonnull private final Function<Object, Serializable> normalizer;
	/**
	 * Instance of comparator that should be used for values in {@link #buckets}
	 */
	@Nonnull @Getter private final Comparator comparator;
	/**
	 * The plain (non-array) declared type of the indexed attribute. It drives the leaf key-column selection
	 * ({@link ValueColumnFactory#forKey}): an integral / temporal type under natural order stores its keys in a
	 * primitive `long[]` column, otherwise the universal boxed column is used.
	 */
	@Nonnull private final Class<?> plainType;
	/**
	 * The decimal-places scale this tree's `BigDecimal` keys are encoded at, frozen when the tree is created. The scale
	 * is baked into {@link #normalizer} at construction (see `FilterIndex.getNormalizer`) and the tree is never
	 * re-scaled in place, so this value records the scale every key already stored in {@link #buckets} was encoded with.
	 * It exists purely as a consistency witness: a caller that owns the current attribute schema can compare its
	 * {@link io.evitadb.api.requestResponse.schema.AttributeSchemaContract#getIndexedDecimalPlaces()} against this frozen
	 * value and refuse to modify a tree whose scale has drifted (which would otherwise silently mix two scales). It is
	 * `0` for non-`BigDecimal` attributes and a runtime-only field — it is re-resolved from the schema on load, never
	 * persisted.
	 */
	@Getter private final int indexedDecimalPlaces;
	/**
	 * Local stream key used with {@link #pageStreamRegistry}. An {@code InvertedIndex} owns exactly one page stream (its
	 * bucket tree), so a single fixed key suffices; the persisted, globally-unique stream id is a separate concept
	 * resolved store-side from the sub-index identity (see `FilterIndexLeafPagePart`), never this value.
	 */
	private static final int BUCKET_PAGE_STREAM = 0;
	/**
	 * Owner-resident page bookkeeping for the granular FilterIndex storage layout: the advance-only
	 * `pageSequence` allocator, the explicit high-water and the `pageSequence -> nodeId` change-detection baseline of this
	 * index's bucket tree. It lives OUTSIDE transactional memory and is carried BY REFERENCE through
	 * {@link #createCopyWithMergedTransactionalMemory} so the surviving committed owner keeps the allocator and baseline
	 * across commits (the discarded transactional copy never has its own). It is consulted only on the single-writer
	 * flush/commit path.
	 */
	@Nonnull @Getter private final PageStreamRegistry pageStreamRegistry;
	/**
	 * The per-tree value id allocator, or `null` while this tree carries no value ids at all — which is the state
	 * every tree is born in and the state the overwhelming majority of trees stay in.
	 *
	 * Non-null exactly when {@link #buckets} carries the id column; the two are switched on and off together and
	 * {@link #carriesValueIds()} reads the pair as one. Unlike {@link #pageStreamRegistry} the allocator is
	 * transactional — ids are minted during a transaction rather than on the flush path — so it is MERGED across a
	 * commit rather than carried by reference, and the surviving tree is re-pointed at the surviving allocator.
	 */
	@Nullable private ValueIdAllocator valueIdAllocator;
	/**
	 * Which subsystems currently need this tree's value ids. `null` until the first consumer registers.
	 *
	 * Owner-resident and NOT transactional, like {@link #pageStreamRegistry}: registering a consumer is a structural
	 * decision about the tree, not a data change. A loaded tree can legitimately have ids (they came back with its
	 * pages) and no registered consumer yet — consumers re-register on first use after a restart.
	 */
	@Nullable private ValueIdConsumerRegistry valueIdConsumers;
	/**
	 * The value id high-water mark the last emitted `FilterIndexStoragePart` root carried — the change-detection
	 * baseline that keeps the persisted high-water from going stale.
	 *
	 * It exists because the root part is deliberately NOT rewritten on a commit that changed no leaf-page list (see
	 * `FilterIndex#appendStorageParts`), and a commit can mint ids without ever allocating or freeing a page. Without
	 * this baseline the persisted high-water would lag behind the ids already written into the leaf pages, and a
	 * restart would re-mint ids that are in use. Owner-resident and non-transactional, like
	 * {@link #pageStreamRegistry}.
	 */
	private int emittedNextValueId = ValueIdAllocator.UNASSIGNED_VALUE_ID;
	/**
	 * Whether a leaf of the published tree has been mutated IN PLACE since its value id directory was last built.
	 *
	 * The directory is normally rebuilt at a publication point — a commit merge, a load, or the moment ids are
	 * switched on. The warm-up path has no such point: it mutates this very instance outside any transaction and
	 * never reaches a merge, so without this flag the first query after a bulk load would resolve against a directory
	 * built when the tree was still empty. Setting it costs one field write per mutation; acting on it costs one
	 * change-detecting walk, and only on a read that follows a write.
	 *
	 * Only writes made OUTSIDE a transaction raise it — see {@link #markValueIdDirectoryStale()} for why a
	 * transactional write leaves the published leaves untouched, and why the narrowed meaning is what lets
	 * {@link #createCopyWithMergedTransactionalMemory} decide which of the tree's two rebuilds the commit merge may
	 * take.
	 *
	 * `volatile` because the catch-up it drives happens on the READ path, where several query threads meet it at once
	 * — see {@link #refreshValueIdDirectory()}. The flag is cleared only after the rebuild has finished, so a reader
	 * that observes `false` has a happens-before edge with that rebuild and sees the directory whole rather than the
	 * three fields it is made of in whatever order they happened to land.
	 */
	private volatile boolean valueIdDirectoryStale;

	/**
	 * Creates a fresh, empty tree ordered by the passed comparator. The leaf key-column kind is chosen from the
	 * attribute's plain type and the comparator: a numeric / temporal attribute under natural order uses a primitive
	 * `long[]` column, otherwise the universal boxed column.
	 *
	 * @param plainType  the plain (non-array) declared attribute type
	 * @param comparator the value order
	 * @return the fresh empty bucket tree
	 */
	@Nonnull
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static TransactionalBucketBPlusTree createEmptyTree(
		@Nonnull Class<?> plainType,
		@Nonnull Comparator comparator
	) {
		// the tree is raw-keyed by Comparable.class here; the factory's wildcard return is fed in as a raw type
		final ValueColumnFactory factory = ValueColumnFactory.forKey(plainType, comparator);
		return new TransactionalBucketBPlusTree<>(
			VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_INTERNAL_NODE_BLOCK_SIZE,
			Comparable.class,
			comparator,
			factory
		);
	}

	/**
	 * Materializes the bucket at the cursor's CURRENT position into a transient {@link ValueToRecord} flyweight. A
	 * single-record bucket becomes a compact {@link ValueToRecordPrimitive}; a multi-record bucket becomes a
	 * {@link ValueToRecordBitmap} sharing the very same {@link TransactionalBitmap} instance
	 * (no copy), which preserves the record-set hash/equals parity the formula cache relies on. Valid only after a
	 * {@link BucketCursor#next()} that returned true.
	 *
	 * @param cursor the cursor positioned at the bucket to materialize
	 * @return the bucket as a {@link ValueToRecord} flyweight
	 */
	@Nonnull
	private static ValueToRecord materializeBucket(@Nonnull BucketCursor cursor) {
		final Serializable value = (Serializable) cursor.value();
		if (cursor.isSingle()) {
			return new ValueToRecordPrimitive(value, cursor.singleRecordId());
		}
		// the multi overload shares the same TransactionalBitmap instance (no copy) so record-set identity is preserved
		return new ValueToRecordBitmap(value, (TransactionalBitmap) cursor.records());
	}

	/**
	 * Bridges a {@link BucketCursor} into an {@link Iterator} of {@link ValueToRecord} flyweights, materializing each
	 * bucket lazily as it is consumed. Used by the value-iterator surface, subset building and
	 * {@link #getValueToRecordBitmap()}; the both-flagged sort view does NOT use this (it reads value/cardinality off
	 * the cursor directly and so allocates nothing on its hot path).
	 *
	 * @param cursor the cursor to bridge
	 * @return an iterator over the buckets as {@link ValueToRecord} flyweights
	 */
	@Nonnull
	private static Iterator<ValueToRecord> cursorAsValueToRecordIterator(@Nonnull BucketCursor cursor) {
		return new Iterator<>() {
			private boolean advanced;
			private boolean hasNext;

			@Override
			public boolean hasNext() {
				if (!this.advanced) {
					this.hasNext = cursor.next();
					this.advanced = true;
				}
				return this.hasNext;
			}

			@Nonnull
			@Override
			public ValueToRecord next() {
				if (!this.hasNext()) {
					throw new NoSuchElementException();
				}
				this.advanced = false;
				return materializeBucket(cursor);
			}
		};
	}

	/**
	 * Representation-independent equality of the record sets at the two cursors' CURRENT positions: a single `{5}` bucket
	 * and a multi `{5}` bucket compare equal. Allocation-free for the single/single case (a bare int compare); the mixed
	 * / multi case delegates to the flyweight {@link ValueToRecord#recordSetEquals}, which compares the transaction-aware
	 * (merged) bitmap views and is representation-independent (a direct {@link TransactionalBitmap}
	 * equals is unusable here - it is type-sensitive and ignores in-flight transactional changes).
	 *
	 * @param a the first cursor (positioned at a bucket)
	 * @param b the second cursor (positioned at a bucket)
	 * @return true if the two record sets are content-equal
	 */
	private static boolean recordSetEquals(
		@Nonnull BucketCursor a,
		@Nonnull BucketCursor b
	) {
		if (a.isSingle() && b.isSingle()) {
			return a.singleRecordId() == b.singleRecordId();
		}
		return materializeBucket(a).recordSetEquals(materializeBucket(b));
	}

	/**
	 * Representation-independent record-set hash of the bucket at the cursor's CURRENT position. A single bucket hashes
	 * `31 + pk`; a multi bucket delegates to its bitmap, which special-cases cardinality 1 to the very same formula so
	 * cross-representation parity holds.
	 *
	 * @param cursor the cursor positioned at a bucket
	 * @return the record-set hash of the current bucket
	 */
	private static int recordSetHashCode(@Nonnull BucketCursor cursor) {
		if (cursor.isSingle()) {
			return 31 + cursor.singleRecordId();
		}
		// delegate to the flyweight: it hashes the transaction-aware (merged) bitmap and special-cases cardinality 1 to
		// the same `31 + first` formula as a single bucket, preserving cross-representation parity
		return materializeBucket(cursor).recordSetHashCode();
	}

	/**
	 * Creates a fresh, empty inverted index whose bucket tree uses the boxed key column. Kept for callers that have no
	 * attribute type at hand (e.g. the generic Kryo deserializer): it behaves exactly like the universal boxed index.
	 *
	 * @param normalizer the value normalizer
	 * @param comparator the value order
	 */
	public InvertedIndex(
		@Nonnull Function<Object, Serializable> normalizer,
		@Nonnull Comparator comparator
	) {
		this(Comparable.class, normalizer, comparator);
	}

	/**
	 * Creates a fresh, empty inverted index, selecting the leaf key-column kind from the attribute's plain type: an
	 * integral / temporal type under natural order stores its keys in a primitive `long[]` column.
	 *
	 * @param plainType  the plain (non-array) declared attribute type
	 * @param normalizer the value normalizer
	 * @param comparator the value order
	 */
	public InvertedIndex(
		@Nonnull Class<?> plainType,
		@Nonnull Function<Object, Serializable> normalizer,
		@Nonnull Comparator comparator
	) {
		this(plainType, normalizer, comparator, 0);
	}

	/**
	 * Creates a fresh, empty inverted index, selecting the leaf key-column kind from the attribute's plain type: an
	 * integral / temporal type under natural order stores its keys in a primitive `long[]` column. The
	 * `indexedDecimalPlaces` scale is frozen into the index as the consistency witness described on
	 * {@link #getIndexedDecimalPlaces()}.
	 *
	 * @param plainType            the plain (non-array) declared attribute type
	 * @param normalizer           the value normalizer
	 * @param comparator           the value order
	 * @param indexedDecimalPlaces decimal-places scale the `BigDecimal` keys are encoded at (0 for other types)
	 */
	public InvertedIndex(
		@Nonnull Class<?> plainType,
		@Nonnull Function<Object, Serializable> normalizer,
		@Nonnull Comparator comparator,
		int indexedDecimalPlaces
	) {
		this.plainType = plainType;
		this.buckets = createEmptyTree(plainType, comparator);
		this.normalizer = normalizer;
		this.comparator = comparator;
		this.indexedDecimalPlaces = indexedDecimalPlaces;
		this.dirty = new TransactionalBoolean(false);
		this.pageStreamRegistry = new PageStreamRegistry();
	}

	/**
	 * Creates an inverted index rebuilt from persisted buckets, using the boxed key column. Kept for callers (the Kryo
	 * deserializer) that have no attribute type at hand.
	 *
	 * @param buckets    the persisted buckets (unique & monotonic by value)
	 * @param normalizer the value normalizer
	 * @param comparator the value order
	 */
	public InvertedIndex(
		@Nonnull ValueToRecordBitmap[] buckets,
		@Nonnull Function<Object, Serializable> normalizer,
		@Nonnull Comparator comparator
	) {
		this(Comparable.class, buckets, normalizer, comparator);
	}

	/**
	 * Creates an inverted index rebuilt from persisted buckets, selecting the leaf key-column kind from the attribute's
	 * plain type: an integral / temporal type under natural order stores its keys in a primitive `long[]` column.
	 *
	 * @param plainType  the plain (non-array) declared attribute type
	 * @param buckets    the persisted buckets (unique & monotonic by value)
	 * @param normalizer the value normalizer
	 * @param comparator the value order
	 */
	public InvertedIndex(
		@Nonnull Class<?> plainType,
		@Nonnull ValueToRecordBitmap[] buckets,
		@Nonnull Function<Object, Serializable> normalizer,
		@Nonnull Comparator comparator
	) {
		this(plainType, buckets, normalizer, comparator, 0);
	}

	/**
	 * Creates an inverted index rebuilt from persisted buckets, selecting the leaf key-column kind from the attribute's
	 * plain type: an integral / temporal type under natural order stores its keys in a primitive `long[]` column. The
	 * `indexedDecimalPlaces` scale is frozen into the index as the consistency witness described on
	 * {@link #getIndexedDecimalPlaces()}.
	 *
	 * @param plainType            the plain (non-array) declared attribute type
	 * @param buckets              the persisted buckets (unique & monotonic by value)
	 * @param normalizer           the value normalizer
	 * @param comparator           the value order
	 * @param indexedDecimalPlaces decimal-places scale the `BigDecimal` keys are encoded at (0 for other types)
	 */
	public InvertedIndex(
		@Nonnull Class<?> plainType,
		@Nonnull ValueToRecordBitmap[] buckets,
		@Nonnull Function<Object, Serializable> normalizer,
		@Nonnull Comparator comparator,
		int indexedDecimalPlaces
	) {
		this.plainType = plainType;
		final TransactionalBucketBPlusTree tree = createEmptyTree(plainType, comparator);
		// rebuild the tree from the deserialized snapshot by inserting all buckets (values are unique & monotonic).
		// a single-record bucket lands as a primitive column entry, a multi-record bucket as an overflow bitmap entry,
		// so the columnar heap win survives a reload without ever allocating a ValueToRecord wrapper.
		for (final ValueToRecordBitmap bucket : buckets) {
			final Bitmap recordIds = bucket.getRecordIds();
			final Comparable value = (Comparable) bucket.getValue();
			if (recordIds.size() == 1) {
				//noinspection unchecked
				tree.addRecord(value, recordIds.getFirst());
			} else {
				//noinspection unchecked
				tree.addRecord(value, recordIds.getArray());
			}
		}
		this.buckets = tree;
		this.normalizer = normalizer;
		this.comparator = comparator;
		this.indexedDecimalPlaces = indexedDecimalPlaces;
		this.dirty = new TransactionalBoolean(false);
		this.pageStreamRegistry = new PageStreamRegistry();
	}

	/**
	 * Rebuilds a `PAGED` inverted index from its persisted leaf pages, preserving the original leaf boundaries and page
	 * identities. Unlike the bucket-replaying constructor, this builds one leaf per persisted page (so
	 * in-memory leaf *i* is byte-identical to persisted page *i*), stamps each leaf with its persisted page sequence, and
	 * restores the page-stream bookkeeping (high-water + the live-page set). Reconstruction replays the buckets through
	 * the leaf's mutation path, which flags the freshly built leaves dirty; they are cleared afterwards because they are
	 * exactly what is already on disk. The result is a boundary-stable reload: a subsequent no-mutation commit rewrites
	 * nothing (every leaf is clean), and the first real mutation rewrites only genuinely-changed leaves instead of
	 * re-paginating the whole index.
	 *
	 * @param plainType            the plain (non-array) declared attribute type
	 * @param orderedPageSequences      the persisted leaf-page sequences in ascending key order (the root's leaf list)
	 * @param perPageBuckets       the buckets of each leaf page, positionally aligned with `orderedPageSequences`
	 * @param perPageValueIds      the persisted value ids of each leaf page, positionally aligned with
	 *                             `orderedPageSequences`, or `null` when the tree carries no value ids
	 * @param highWaterPageSequence     the persisted stream high-water (largest page sequence ever allocated)
	 * @param normalizer           the value normalizer
	 * @param comparator           the value order
	 * @param indexedDecimalPlaces the frozen decimal-places scale (0 for non-`BigDecimal` types)
	 * @return the rebuilt, boundary-stable `PAGED` inverted index
	 */
	@Nonnull
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static InvertedIndex fromPersistedPages(
		@Nonnull Class<?> plainType,
		@Nonnull int[] orderedPageSequences,
		@Nonnull ValueToRecord[][] perPageBuckets,
		@Nullable int[][] perPageValueIds,
		int highWaterPageSequence,
		@Nonnull Function<Object, Serializable> normalizer,
		@Nonnull Comparator comparator,
		int indexedDecimalPlaces
	) {
		Assert.isPremiseValid(
			orderedPageSequences.length == perPageBuckets.length,
			"The number of page sequences must match the number of leaf-page bucket arrays."
		);
		Assert.isPremiseValid(orderedPageSequences.length > 0, "A paged inverted index must have at least one leaf page.");
		Assert.isPremiseValid(
			perPageValueIds == null || perPageValueIds.length == orderedPageSequences.length,
			"The per-page value id columns must align with the page sequences one for one."
		);
		final List<TransactionalBucketBPlusTree> pageTrees = new ArrayList<>(orderedPageSequences.length);
		for (int i = 0; i < orderedPageSequences.length; i++) {
			final ValueToRecord[] buckets = perPageBuckets[i];
			// build a single-leaf tree from this page's buckets in one bulk pass — a page never exceeds a leaf's
			// capacity, so no split — instead of `buckets.length` sequential addRecord calls, which would otherwise
			// re-decode/re-encode a front-coded String column's whole blob per call; see bulkLoadPage's javadoc
			final TransactionalBucketBPlusTree pageTree = createEmptyTree(plainType, comparator);
			final Object[] keys = new Object[buckets.length];
			final long[] payloads = new long[buckets.length];
			TransactionalBitmap[] overflow = null;
			for (int j = 0; j < buckets.length; j++) {
				final ValueToRecord bucket = buckets[j];
				final Bitmap recordIds = bucket.getRecordIds();
				keys[j] = bucket.getValue();
				if (recordIds.size() == 1) {
					payloads[j] = recordIds.getFirst();
				} else {
					if (overflow == null) {
						overflow = new TransactionalBitmap[buckets.length];
					}
					overflow[j] = new TransactionalBitmap(recordIds);
				}
			}
			pageTree.bulkLoadPage(
				keys, payloads, overflow, perPageValueIds == null ? null : perPageValueIds[i], buckets.length
			);
			pageTrees.add(pageTree);
		}
		// assemble the spine over the per-page leaves, preserving boundaries and stamping each leaf's page sequence
		final TransactionalBucketBPlusTree tree =
			createEmptyTree(plainType, comparator).assembleFromSingleLeafTrees(
				pageTrees, orderedPageSequences, "inverted index for type `" + plainType.getName() + "`"
			);
		final PageStreamRegistry pageStreamRegistry = PageStreamRegistry.restoredFrom(
			BUCKET_PAGE_STREAM, highWaterPageSequence, tree.leafPageHandles()
		);
		return new InvertedIndex(
			plainType, tree, normalizer, comparator, indexedDecimalPlaces, pageStreamRegistry
		);
	}

	/**
	 * Private constructor used by {@link #createCopyWithMergedTransactionalMemory} to wrap an already committed tree.
	 * The committed tree already carries the chosen column kind, so {@code plainType} is only propagated for parity.
	 *
	 * @param plainType            the plain (non-array) declared attribute type, carried forward from the source index
	 * @param committedTree        the tree obtained from the committed transactional state
	 * @param normalizer           the normalizer of the source index
	 * @param comparator           the comparator of the source index
	 * @param indexedDecimalPlaces the frozen decimal-places scale carried forward from the source index
	 * @param pageStreamRegistry   the owner-resident page bookkeeping carried BY REFERENCE from the source index
	 */
	private InvertedIndex(
		@Nonnull Class<?> plainType,
		@Nonnull TransactionalBucketBPlusTree committedTree,
		@Nonnull Function<Object, Serializable> normalizer,
		@Nonnull Comparator comparator,
		int indexedDecimalPlaces,
		@Nonnull PageStreamRegistry pageStreamRegistry
	) {
		this.plainType = plainType;
		this.buckets = committedTree;
		this.normalizer = normalizer;
		this.comparator = comparator;
		this.indexedDecimalPlaces = indexedDecimalPlaces;
		this.dirty = new TransactionalBoolean(false);
		this.pageStreamRegistry = pageStreamRegistry;
	}

	@Nonnull
	@Override
	public ConsistencyReport getConsistencyReport() {
		return this.buckets.getConsistencyReport();
	}

	/**
	 * Registers `consumerName` as needing stable value ids on this tree, switching the tree into id-carrying mode the
	 * first time any consumer does so. Idempotent: registering a name that is already registered changes nothing.
	 *
	 * The gate on the id column is this registration and nothing else — never an attribute schema flag. See
	 * {@link ValueIdConsumerRegistry} for why the distinction matters.
	 *
	 * The first registration is only accepted while the tree is still EMPTY — see {@link #enableValueIds} for why an
	 * already-populated tree cannot be switched on. Registering onto a tree that already carries ids is unrestricted,
	 * because it changes nothing about the ids themselves.
	 *
	 * Attaching and detaching are structural decisions taken by the single writer that owns this tree, so this method
	 * must never be called from a query or background thread, nor concurrently with another writer on the same index.
	 * {@link ValueIdConsumerRegistry} names the two moments at which it legitimately happens — the entity write path
	 * when the tree is first created, and the catalog load path — and why neither of them is the schema mutation that
	 * declared the accelerator.
	 *
	 * @param consumerName the consumer's stable name, e.g. `trigram-substring-index`
	 * @see #detachValueIdConsumer(String)
	 */
	public void attachValueIdConsumer(@Nonnull String consumerName) {
		// the tree is switched on BEFORE the name is recorded, so a refused attach leaves the registry untouched and
		// the tree and its registry keep agreeing about whether ids exist
		enableValueIds(new ValueIdAllocator());
		if (this.valueIdConsumers == null) {
			this.valueIdConsumers = new ValueIdConsumerRegistry();
		}
		this.valueIdConsumers.register(consumerName);
	}

	/**
	 * Unregisters `consumerName`. When it was the last consumer AND the tree is still empty, the tree also leaves
	 * id-carrying mode and every id it ever minted is discarded — any structure still keyed by them must be discarded
	 * with them.
	 *
	 * ## Why a POPULATED tree keeps its id column after the last consumer leaves
	 *
	 * Dropping the columns of a populated tree clears them in memory but marks no leaf page dirty, so the columns
	 * already written would survive on disk while the persisted root's high-water mark returned to
	 * {@link ValueIdAllocator#UNASSIGNED_VALUE_ID}. `AttributeIndexLoader` refuses precisely that pairing, and the
	 * catalog would not open at all. The obvious repair — dirty every live leaf so the pages are rewritten without the
	 * column — is not available where this is actually called from: the withdrawal takes effect on an ordinary entity
	 * write, inside a transaction, and the id-column walk writes through the BASE leaves (which is why
	 * {@link TransactionalBucketBPlusTree#removeValueIdMinter()} refuses to run there at all). It would also rewrite
	 * every page of the attribute's index during one entity upsert.
	 *
	 * So the drop is deliberately partial: the CONSUMER goes, the column stays. What that leaves behind is one `int`
	 * per distinct value on disk and a mint on each newly created bucket — nothing reads either, since a reader
	 * reaches the ids only through a consumer's structure. The tree keeps minting rather than stopping, because a
	 * column with a hole in it is what would really break the loader.
	 *
	 * The residue is collected on its own: a tree that empties out is dropped whole, and the accelerator can only be
	 * re-declared on an empty collection (`EntityCollection#verifyNoAcceleratorAddedToNonEmptyCollection` refuses
	 * additions, and `AttributeFilterAcceleratorRefusalTest#shouldAllowRemovingCapabilityFromPopulatedCollection`
	 * pins that removals stay legal), so a re-attach always meets a tree whose column is empty anyway.
	 *
	 * Unregistering a consumer that is not the last one is unrestricted — the tree keeps its ids and nothing
	 * structural happens.
	 *
	 * The same single-writer obligation as {@link #attachValueIdConsumer(String)} applies.
	 *
	 * @param consumerName the consumer's stable name
	 * @see #attachValueIdConsumer(String)
	 */
	public void detachValueIdConsumer(@Nonnull String consumerName) {
		if (this.valueIdConsumers == null) {
			return;
		}
		// `unregister` reports the transition to an unclaimed column, so it already answers "was that the last one?"
		if (this.valueIdConsumers.unregister(consumerName)
			&& this.buckets.size() == 0
			&& !Transaction.isTransactionAvailable()) {
			// only an empty tree can give the column back - see the section above for what a populated one does
			// instead, and why it is not merely a deferral.
			//
			// The transaction test guards the OTHER half: this field and the tree's minter are owner-resident, so
			// clearing them writes straight through to the live index rather than into the transaction's layer. An
			// abort would restore the trigram index that asked for the drop and leave the tree without the ids it
			// posts against - a mismatch the next value born would raise on an ordinary upsert. Keeping the column of
			// an empty tree costs nothing, so the transactional case simply keeps it
			this.buckets.removeValueIdMinter();
			this.valueIdAllocator = null;
		}
	}

	/**
	 * Switches this tree into id-carrying mode around the given allocator. A no-op when the tree already carries ids,
	 * so the passed allocator is used only on the first call — which is why callers that must restore a specific
	 * high-water mark go through {@link #restoreValueIds(int)} instead. The load path does not pass through here at
	 * all.
	 *
	 * The tree must still be EMPTY when ids are switched on. The tree itself would happily back-fill the values already
	 * present, but that back-fill would live in memory only: it writes the id columns of leaves nothing marks dirty, so
	 * the emitter never rewrites their pages, the ids never reach disk, and a reload would mint different ids for
	 * exactly the values a consumer had already recorded ids for. The constraint costs nothing in practice because a
	 * filter accelerator cannot be declared on a collection that already holds entities
	 * (`EntityCollection#verifyNoAcceleratorAddedToNonEmptyCollection`), so the tree a consumer attaches to has
	 * nothing in it yet.
	 *
	 * @param allocator the allocator to mint from
	 */
	private void enableValueIds(@Nonnull ValueIdAllocator allocator) {
		if (this.valueIdAllocator == null) {
			Assert.isPremiseValid(
				this.buckets.size() == 0,
				"Value ids can only be switched on while the tree is still empty - back-filling the values already " +
					"present dirties no leaf page, so the ids would never reach disk and a reload would hand those " +
					"values different ones. A filter accelerator cannot be declared on a collection that already " +
					"holds entities, so a consumer always attaches to an empty tree."
			);
			this.valueIdAllocator = allocator;
			this.buckets.installValueIdMinter(this.valueIdAllocator::allocate);
			this.buckets.rebuildValueIdDirectory();
			this.valueIdDirectoryStale = false;
		}
	}

	/**
	 * Restores id-carrying mode on a tree just rebuilt from persisted pages: the ids themselves came back inside the
	 * pages, and this re-attaches the allocator at the persisted high-water mark so the next minted id continues where
	 * the previous run left off. Continuing the sequence rather than restarting it is what makes the ids stable across
	 * a restart, which is the whole reason the allocator is persisted at all.
	 *
	 * @param nextValueId the persisted high-water mark
	 */
	public void restoreValueIds(int nextValueId) {
		restoreValueIds(nextValueId, null);
	}

	/**
	 * Restores id-carrying mode on a tree just rebuilt from persistence, together with the ids of the values it already
	 * holds.
	 *
	 * The two shapes reach this differently. A `PAGED` index gets its ids back inside each leaf page, so it passes
	 * `null` here and the ids are already in place. A `SINGLE` (inline) index replays its buckets through the ordinary
	 * insert path, which cannot carry ids, so it passes the persisted inline column and the tree stamps it in ascending
	 * key order.
	 *
	 * @param nextValueId       the persisted high-water mark
	 * @param persistedValueIds the ids of the values already present in ascending key order, or `null` when they came
	 *                          back with the pages
	 */
	public void restoreValueIds(int nextValueId, @Nullable int[] persistedValueIds) {
		Assert.isPremiseValid(
			this.valueIdAllocator == null,
			"Value ids have already been enabled on this tree — they cannot be restored over."
		);
		this.valueIdAllocator = new ValueIdAllocator(nextValueId);
		this.buckets.installValueIdMinter(this.valueIdAllocator::allocate, persistedValueIds);
		// the directory is derived state and is NOT persisted - it is rebuilt here from the reloaded tree, which is
		// what keeps the value id feature's storage surface to the id column alone
		this.buckets.rebuildValueIdDirectory();
		this.valueIdDirectoryStale = false;
		// what was just restored is by definition what is on disk, so the root needs no rewrite until the next mint
		this.emittedNextValueId = nextValueId;
	}

	/**
	 * Tells whether the value id high-water mark has MOVED since the last root part was emitted, and the root must
	 * therefore be rewritten even though no leaf page was allocated or freed this commit.
	 *
	 * Moved in either direction. Advancing is the common case — a commit can mint ids into an existing leaf without
	 * touching any page list. But dropping the ids altogether moves the mark back to
	 * {@link ValueIdAllocator#UNASSIGNED_VALUE_ID}, and that has to force the root out just as hard: a persisted root
	 * still claiming a high-water its leaf pages no longer carry is precisely the pairing
	 * `AttributeIndexLoader#loadInvertedIndex` refuses, so leaving it behind does not merely lose the mark — it stops
	 * the catalog from opening.
	 *
	 * @return `true` when the persisted high-water would otherwise disagree with the tree
	 */
	public boolean isValueIdHighWaterDirty() {
		return getNextValueId() != this.emittedNextValueId;
	}

	/**
	 * Records that a root part carrying the current high-water mark has just been emitted, so the next commit that
	 * mints nothing leaves the root alone.
	 *
	 * ## Why this may advance from inside the collect
	 *
	 * {@link io.evitadb.index.Index#getModifiedStorageParts} is documented as a pure, idempotent read, with the
	 * baseline advance deliberately relocated to `notifyFlushed` — so a baseline that moves here looks, at first
	 * glance, like it is in the wrong place. It is not, and the reason is worth stating because it is not local: in
	 * production `EntityIndex#getModifiedStorageParts` is reached from exactly two places, and both are accounted for.
	 * `DataStoreChanges#popTrappedUpdates` collects the parts that are then written, and `notifyFlushed` immediately
	 * re-runs the same collect through `captureOriginalsFromComponents` into a sink it discards. The second pass
	 * finds this mark already advanced and the page list unchanged, so it emits no root at all — pinned by
	 * `FilterIndexValueIdRootEmissionTest#shouldNotDisturbValueIdHighWaterWhenBaselineCaptureRepeatsTheCollect`.
	 *
	 * A third caller that collected and threw the result away WOULD strand the mark: the next real flush would find
	 * it clean, skip the root, and leave the persisted high-water behind ids the leaf pages already carry, which a
	 * restart resolves by handing one id to two values. No such caller exists, and the page-stream registry beside
	 * this one makes exactly the same assumption — {@link #collectChangedPages()} publishes the previous flush's
	 * staged page set and clears each leaf's dirty flag on the way through, so a discarded collect loses leaf pages
	 * outright, before this mark is even reached. Adding one is therefore not a licence to stage this mark instead;
	 * it is a change that has to be weighed against that whole contract at once.
	 */
	public void markValueIdHighWaterEmitted() {
		this.emittedNextValueId = getNextValueId();
	}

	/**
	 * @return `true` when every distinct value in this tree carries a stable id
	 */
	public boolean carriesValueIds() {
		return this.valueIdAllocator != null;
	}

	/**
	 * Returns the high-water mark that must be persisted alongside this tree's pages, so
	 * {@link #restoreValueIds(int)} can continue the sequence after a restart.
	 *
	 * @return the id the next mint would hand out, or {@link ValueIdAllocator#UNASSIGNED_VALUE_ID} when this tree
	 *         carries no value ids
	 */
	public int getNextValueId() {
		return this.valueIdAllocator == null
			? ValueIdAllocator.UNASSIGNED_VALUE_ID : this.valueIdAllocator.getNextValueId();
	}

	/**
	 * Returns the names of the subsystems this tree's id column is being paid for — diagnostics only.
	 *
	 * @return the registered consumer names, empty when none are registered
	 */
	@Nonnull
	public Set<String> getValueIdConsumerNames() {
		return this.valueIdConsumers == null ? Set.of() : this.valueIdConsumers.getConsumerNames();
	}

	/**
	 * Resolves the stable id of a distinct value, in a single tree descent.
	 *
	 * @param value the value to resolve; it is normalized here exactly as {@link #addRecord(Serializable, int)}
	 *              normalizes it, so callers pass the raw attribute value
	 * @return the value's stable id, or {@link ValueIdAllocator#UNASSIGNED_VALUE_ID} when this tree carries no value
	 *         ids or holds no bucket for that value
	 */
	public int getValueId(@Nullable Serializable value) {
		if (value == null || this.valueIdAllocator == null) {
			return ValueIdAllocator.UNASSIGNED_VALUE_ID;
		}
		return this.buckets.valueIdOf((Comparable) this.normalizer.apply(value));
	}

	/**
	 * Resolves a stable value id back to the distinct value it names — the reverse of {@link #getValueId}.
	 *
	 * This is the probe a consumer performs once per candidate: the trigram substring index intersects its postings
	 * down to a set of candidate value ids and then verifies each one by resolving it here. It answers in `O(1)`
	 * through the tree's `valueId -> (leafId, slot)` directory rather than by searching, because value ids are
	 * allocation-ordered and therefore not searchable in the tree's key order at all.
	 *
	 * The returned value is the NORMALIZED form the tree stores, which is the form a consumer must verify against —
	 * it is what {@link #getValueId} was given after normalization.
	 *
	 * ## Committed state only
	 *
	 * This answers from the last published version of the tree, and REFUSES to answer at all while a transaction is
	 * open on the calling thread. The directory is built once per published version and carries no diff layer — that is
	 * what buys MVCC here without one — so a transaction's own writes are invisible to it in both directions: an id
	 * minted inside the transaction has no entry at all, and an entry made before it addresses a leaf and slot the
	 * transaction may since have moved that value out of. Both resolve to `null`, so the probe would report "no such
	 * value" for values the collection does hold. For the candidate-verification consumer this reverse lookup exists
	 * for, that means quietly matching fewer entities than the query asked for — a silent under-report is worse than a
	 * refusal, and evitaDB guarantees a transaction sees its own writes.
	 *
	 * The first production consumer — the trigram substring index and its translator — settled this by taking the
	 * SCAN FALLBACK rather than by making the lookup transaction-aware: `TrigramSubstringSearch` tests
	 * {@link Transaction#isTransactionAvailable()} before it enters the accelerated path at all, and a query running
	 * inside a transaction is answered by the same bucket scan that served it before the index existed. A
	 * transaction-local overlay would have to hold every value the transaction touched to be correct, and it would buy
	 * an acceleration only for the write session itself — which is a fraction of a percent of substring queries, and
	 * the one context in which the scan's cost is already dwarfed by the write it accompanies. Any FUTURE caller must
	 * make the same check and take its own fallback; this method refuses rather than under-report.
	 *
	 * @param valueId the id to resolve
	 * @return the normalized value that id names, or `null` when this tree carries no value ids or the id names
	 *         nothing live
	 * @throws GenericEvitaInternalError when a transaction is open on the current thread
	 */
	@Nullable
	public Serializable getValueById(int valueId) {
		Assert.isPremiseValid(
			!Transaction.isTransactionAvailable(),
			"A value id cannot be resolved back to its value while a transaction is open on this thread - the " +
				"directory addresses the last published version of the tree while the leaves it reads are the " +
				"transaction's own, so the probe would silently under-report. Resolve against the committed index, " +
				"or take the scan fallback until the transactional overlay this needs exists."
		);
		if (this.valueIdAllocator == null) {
			return null;
		}
		// catch up the warm-up path's writes. The premise above has already established there is no transaction on this
		// thread, which is what makes the rebuild safe: `enumerateLeaves` reads the transaction-aware root, so
		// rebuilding inside a transaction would fold one transaction's uncommitted leaves into the directory every
		// other reader shares
		if (this.valueIdDirectoryStale) {
			refreshValueIdDirectory();
		}
		return (Serializable) this.buckets.valueOf(valueId);
	}

	/**
	 * Marks the value id directory as needing a rebuild before the next probe reads it — and, equivalently, records
	 * that a leaf of the published tree has been mutated IN PLACE.
	 *
	 * ## Why a transactional write does not raise it
	 *
	 * Every node of this tree is created with its transactional layer enabled, so a write made with a transaction
	 * bound to the thread lands in that transaction's own layer and leaves the published leaves — the ones the
	 * directory addresses — byte-for-byte as they were. The directory therefore does not go stale: it keeps
	 * describing exactly the version every reader outside that transaction still sees, and the readers inside it are
	 * refused outright by {@link #getValueById(int)} and {@link #getRecordsOfValueIdsMatching}.
	 *
	 * Raising the flag there was not merely redundant, it was expensive in the one place that cannot afford it. Trunk
	 * incorporation of a transaction that inserts N distinct values would flip the volatile N times on the shared live
	 * instance, and every read-only accelerated query arriving between two of those writes would enter the
	 * synchronized {@link #refreshValueIdDirectory()} and copy the whole location array — an `O(V)` walk on the QUERY
	 * path, repeated up to N times, to rebuild a directory that had not changed.
	 *
	 * ## What the narrowed meaning buys the commit merge
	 *
	 * With transactional writes excluded, this flag says precisely *"a leaf changed content without changing its
	 * instance identity"*, which is the ONE condition under which
	 * {@link TransactionalBucketBPlusTree#rebuildValueIdDirectoryAfterMerge()} may not be used — that variant reuses
	 * the entries of every leaf whose version token is unchanged, and an in-place mutation keeps the token. The merge
	 * in {@link #createCopyWithMergedTransactionalMemory} reads the flag to choose between the two rebuilds.
	 *
	 * Guarded on the allocator so that the volatile store - a StoreLoad barrier, drained on every write - is paid only
	 * by trees that actually carry value ids. Most inverted indexes never do: ids are switched on only where a filter
	 * accelerator is declared, so every other attribute would otherwise pay a barrier on every single record write to
	 * invalidate a directory it will never build. This is the same cost promise {@link #removeRecord} states for the
	 * lifecycle sink, applied to the flag.
	 *
	 * Nothing is lost by the guard: ids are only ever switched on while the tree is still empty (see
	 * {@link #enableValueIds}), and every route that enables them - that one, {@link #restoreValueIds(int, int[])} and
	 * the commit merge - rebuilds the directory and clears this flag itself.
	 */
	private void markValueIdDirectoryStale() {
		if (this.valueIdAllocator != null && !Transaction.isTransactionAvailable()) {
			this.valueIdDirectoryStale = true;
		}
	}

	/**
	 * Rebuilds the value id directory once, however many readers arrive to find it stale.
	 *
	 * The catch-up this performs is a WRITE made from the read path, and the rebuild behind it is emphatically not
	 * re-entrant: it advances the tree's plain leaf-id counter and calls `assignLeafId`, whose premise refuses a
	 * second assignment outright. Two query threads that both saw the stale flag would therefore either fail that
	 * premise — an internal error raised on a query — or race each other's writes into the location array and leave
	 * live values resolving to nothing, which is the silent under-report {@link #getValueById(int)}'s own transaction
	 * premise exists to rule out. Hence single-flight: the lock admits one rebuilder and the re-check inside it makes
	 * every thread that queued behind them return without doing the work a second time.
	 *
	 * ## The read-versus-rebuild window, and how it is closed
	 *
	 * A reader already past the flag on the fast path — having seen it `false` — can still be inside
	 * `BucketBPlusTree#valueOf` while a later writer marks the directory stale and the next reader rebuilds it. That
	 * is closed on the tree side rather than here: the directory is a single immutable `ValueIdDirectory` behind one
	 * volatile field, filled into a FRESH location array and published whole, so such a reader keeps resolving through
	 * the generation it read and never observes a half-stamped one. Serializing the readers here would not have
	 * sufficed — the rebuild is not what the overtaken reader is holding.
	 *
	 * What the lock still buys is the rebuild's own non-re-entrancy: it advances the plain leaf-id counter and calls
	 * `assignLeafId`, whose premise refuses a second assignment outright, so two rebuilders remain forbidden.
	 *
	 * ## If you change this method, run the stress test that guards it
	 *
	 * `LongRunningValueIdDirectoryConcurrencyTest` is the only thing that covers the single-flight claim above; it is
	 * `@Disabled` and lives in `evita_test/evita_long_running_tests`, so nothing runs it for you:
	 *
	 * ```
	 * mvn -pl evita_test/evita_functional_tests,evita_test/evita_long_running_tests test -P longRunning
	 * ```
	 *
	 * It carries a recorded calibration — the counterfactual is removing the `synchronized` below — and that has to be
	 * re-measured too, not merely the green run. **Making this method faster narrows the window the test races in**, so
	 * an optimization elsewhere can leave the test passing while it has stopped proving anything; that has already
	 * happened once. The same obligation applies to `BucketBPlusTree#rebuildValueIdDirectory`.
	 */
	private synchronized void refreshValueIdDirectory() {
		if (this.valueIdDirectoryStale) {
			this.buckets.rebuildValueIdDirectory();
			// cleared LAST, so the volatile write publishes the finished directory to every reader that takes the
			// fast path afterwards
			this.valueIdDirectoryStale = false;
		}
	}

	/**
	 * Returns the next stable leaf id the shared tree would hand out — one more than the number of leaves it has ever
	 * created. Leaf-id stability across a commit is an invariant with no behavioural symptom (losing it burns the id
	 * space and bloats the directory rather than producing wrong answers), so this is what pins it.
	 *
	 * @return the next leaf id to be minted
	 */
	public long getNextLeafId() {
		return this.buckets.getNextLeafId();
	}

	/**
	 * Returns the heap the value id directory occupies, in bytes — reported apart from
	 * {@link #getHeapSizeInBytes()} because it is derived bookkeeping rebuilt on load, like the page-stream registry
	 * beside it, rather than data this index owns.
	 *
	 * @return the directory's dominant heap term, or `0` when this tree carries no value ids
	 */
	public long getValueIdDirectoryHeapSizeInBytes() {
		return this.buckets.getValueIdDirectoryHeapSizeInBytes();
	}

	/**
	 * Adds single record id into the bucket with specified `value`. If no bucket with this value exists, it is
	 * automatically created as a compact single-record column entry. A single-record bucket promotes to a multi-record
	 * bitmap when a second distinct record id is added; an add of the id it already holds is a no-op. A bitmap bucket is
	 * mutated in place so its transactional diff layer is preserved.
	 */
	public void addRecord(@Nonnull Serializable value, int recordId) {
		addRecord(value, recordId, null);
	}

	/**
	 * Value-lifecycle-reporting variant of {@link #addRecord(Serializable, int)}: `sink` is notified when — and only
	 * when — this write brought a distinct value into existence, i.e. created a bucket and minted its value id.
	 *
	 * @param value    the value to index
	 * @param recordId the record id to associate with it
	 * @param sink     learns about a value born by this write, or `null` when nobody is interested
	 */
	public void addRecord(@Nonnull Serializable value, int recordId, @Nullable ValueLifecycleSink sink) {
		final Comparable normalizedValue = (Comparable) this.normalizer.apply(value);
		if (sink == null) {
			this.buckets.addRecord(normalizedValue, recordId);
		} else {
			// the id rides back out of the insert's own descent, exactly as the dying one does out of the removal's -
			// see `notifyValueCreated` for what the alternative costs
			final int bornValueId = this.buckets.addRecordReportingValueBirth(normalizedValue, recordId);
			if (bornValueId != TransactionalBucketBPlusTree.NO_CREATED_BUCKET) {
				notifyValueCreated(sink, bornValueId, normalizedValue);
			}
		}
		this.dirty.setToTrue();
		markValueIdDirectoryStale();
	}

	/**
	 * Adds multiple records id into the bucket with specified `value`. If no bucket with this value exists, it is
	 * automatically created (a single-record column entry for a single id, a multi-record bitmap otherwise). A
	 * single-record bucket promotes to a bitmap unless the only id being added is the one it already holds. A bitmap
	 * bucket is mutated in place so its transactional diff layer is preserved.
	 */
	public void addRecord(@Nonnull Serializable value, int... recordId) {
		addRecord(value, null, recordId);
	}

	/**
	 * Value-lifecycle-reporting variant of {@link #addRecord(Serializable, int...)}. However many record ids are
	 * added, they all land in ONE bucket, so `sink` is notified at most once.
	 *
	 * @param value    the value to index
	 * @param sink     learns about a value born by this write, or `null` when nobody is interested
	 * @param recordId the record ids to associate with it
	 */
	public void addRecord(@Nonnull Serializable value, @Nullable ValueLifecycleSink sink, @Nonnull int... recordId) {
		Assert.isTrue(!ArrayUtils.isEmpty(recordId), "Record ids must be not null and non-empty!");
		final Comparable normalizedValue = (Comparable) this.normalizer.apply(value);
		if (sink == null) {
			this.buckets.addRecord(normalizedValue, recordId);
		} else {
			// see the single-record twin above: the birth is reported by the insert itself rather than detected
			final int bornValueId = this.buckets.addRecordReportingValueBirth(normalizedValue, recordId);
			if (bornValueId != TransactionalBucketBPlusTree.NO_CREATED_BUCKET) {
				notifyValueCreated(sink, bornValueId, normalizedValue);
			}
		}
		this.dirty.setToTrue();
		markValueIdDirectoryStale();
	}

	/**
	 * Removes one or multiple record ids from the bucket with specified `value`. If no bucket with this value exists,
	 * nothing happens. If the bucket contains no record id that match passed record id, nothing happens. If removal
	 * of the record ids leaves the bucket empty, it's entirely removed (the tree releases the value's transactional
	 * layer). A bitmap bucket is mutated in place; a single-record bucket can only be deleted (it holds a single
	 * id, so removing that id empties it). The dirty flag is always raised to mirror the historical behaviour.
	 */
	public void removeRecord(@Nonnull Serializable value, int... recordId) {
		removeRecord(value, null, recordId);
	}

	/**
	 * Value-lifecycle-reporting variant of {@link #removeRecord(Serializable, int...)}: `sink` is notified when — and
	 * only when — this write took a distinct value out of existence, i.e. drained its bucket and deleted it.
	 *
	 * A sink costs this path nothing on the common write, the one that leaves the value alive: the dying id rides back
	 * out of the removal's own descent rather than being resolved by a second one, so the only branch that pays for
	 * the reporting is the death itself. That is the half of the value-id design's cost promise this method upholds;
	 * {@link #notifyValueCreated} states the other.
	 *
	 * @param value    the value to remove records from
	 * @param sink     learns about a value that died in this write, or `null` when nobody is interested
	 * @param recordId the record ids to disassociate from it
	 */
	public void removeRecord(@Nonnull Serializable value, @Nullable ValueLifecycleSink sink, @Nonnull int... recordId) {
		Assert.isTrue(!ArrayUtils.isEmpty(recordId), "Record ids must be not null and non-empty!");
		// historical quirk: the dirty flag is raised unconditionally BEFORE the lookup, even on a no-op remove
		this.dirty.setToTrue();
		final Comparable normalizedValue = (Comparable) this.normalizer.apply(value);
		if (sink == null) {
			this.buckets.removeRecord(normalizedValue, recordId);
		} else {
			// the id has to be read while the bucket is still there — once the removal has deleted it there is nothing
			// left to read it from, and it is precisely what the sink needs to drop the value from its structures. The
			// tree reads it off the slot its own descent resolved, so a removal that reports nothing (the common one,
			// where the value survives) pays neither a descent nor a bucket count for the sink's benefit
			final int dyingValueId = this.buckets.removeRecordReportingValueDeath(normalizedValue, recordId);
			if (dyingValueId != TransactionalBucketBPlusTree.NO_DELETED_BUCKET) {
				Assert.isPremiseValid(
					dyingValueId != ValueIdAllocator.UNASSIGNED_VALUE_ID,
					() -> "The bucket of value `" + normalizedValue + "` was deleted but carried no value id — a " +
						"value lifecycle sink can only be attached to a tree that carries them, and every bucket " +
						"of such a tree is stamped when it is created."
				);
				sink.valueRemoved(dyingValueId, (Serializable) normalizedValue);
			}
		}
		markValueIdDirectoryStale();
	}

	/**
	 * Reports a value that has just come into existence to `sink`.
	 *
	 * The id is the one the insert minted, handed back by
	 * {@link TransactionalBucketBPlusTree#addRecordReportingValueBirth(Comparable, int)} out of the descent that
	 * created the bucket — the birth branch therefore costs nothing beyond the notification itself, and an insert
	 * that joins an existing value costs not even a bucket count. That is the property the whole value-id design
	 * rests on, and it is now symmetric with the removal path, whose dying id likewise rides out of the removal's own
	 * descent — see {@link #removeRecord(Serializable, ValueLifecycleSink, int...)}. Resolving it afterwards instead
	 * cost a full root-to-leaf descent plus a leaf binary search over front-coded keys, once per distinct value of a
	 * bulk import.
	 *
	 * @param sink            the sink to notify
	 * @param valueId         the id the insert minted for the value
	 * @param normalizedValue the value the insert created a bucket for, already normalized
	 */
	private void notifyValueCreated(
		@Nonnull ValueLifecycleSink sink,
		int valueId,
		@Nonnull Comparable normalizedValue
	) {
		Assert.isPremiseValid(
			valueId != ValueIdAllocator.UNASSIGNED_VALUE_ID,
			() -> "The bucket freshly created for value `" + normalizedValue + "` carries no value id — a value " +
				"lifecycle sink can only be attached to a tree that carries them, and this one does not."
		);
		sink.valueCreated(valueId, (Serializable) normalizedValue);
	}

	/**
	 * Method returns ture if histogram contains no records (i.e. no, or empty buckets).
	 */
	public boolean isEmpty() {
		// the tree deletes a bucket on drain-to-zero, so bucketCount()==0 is equivalent to "no bucket holds records"
		return this.buckets.bucketCount() == 0;
	}

	/**
	 * Returns true if there is a bucket related to passed `value`.
	 */
	public boolean contains(@Nullable Serializable value) {
		if (value == null) {
			return false;
		}
		return this.buckets.contains((Comparable) this.normalizer.apply(value));
	}

	/**
	 * Returns records associated with the given already-normalized value via direct lookup in the backing tree. Unlike
	 * position-based access, this method is safe to call even when a cached position map may be stale due to concurrent
	 * transactional modifications.
	 *
	 * @param normalizedValue the value already normalized by the caller (via FilterIndex normalizer)
	 */
	@Nonnull
	public Bitmap getRecordsEqualTo(@Nullable Serializable normalizedValue) {
		// the tree returns EmptyBitmap.INSTANCE for an absent or null key - byte-identical to the historical behaviour
		return this.buckets.getRecordsEqualTo((Comparable) normalizedValue);
	}

	/**
	 * Returns the number of records associated with the given already-normalized value, read directly from the bucket
	 * without materializing the record bitmap. This is the allocation-free cardinality read used on hot sort paths:
	 * {@link #getRecordsEqualTo} on a single-record {@link SingleRecordBitmap}-backed bucket would otherwise allocate a
	 * bitmap per probe, whereas the tree resolves the cardinality inline on the leaf column (the allocation-free
	 * cardinality read used by the both-flagged sort view).
	 *
	 * @param normalizedValue the value already normalized by the caller (via the shared normalizer)
	 * @return cardinality of the bucket, or {@code 0} when no such bucket exists
	 */
	public int cardinalityOf(@Nullable Serializable normalizedValue) {
		return this.buckets.cardinalityOf((Comparable) normalizedValue);
	}

	/**
	 * Computes the record id after which `recordId` (associated with the given already-normalized value) belongs in
	 * the global sort order of this index — buckets ascend by value, records within a bucket ascend by id — or
	 * {@link Integer#MIN_VALUE} when it belongs to the very first position. Answered bucket-locally in a single tree
	 * descent, without any rank computation.
	 *
	 * @param normalizedValue the value already normalized by the caller (via the shared normalizer)
	 * @param recordId        the record id being inserted
	 * @return the record id to insert after, or {@link Integer#MIN_VALUE} when the record belongs first
	 */
	public int computePreviousRecord(@Nonnull Serializable normalizedValue, int recordId) {
		return this.buckets.computePreviousRecord((Comparable) normalizedValue, recordId);
	}

	/**
	 * Returns the normalizer this inverted index applies to incoming values before they become bucket keys. Exposed so
	 * co-owning role-views (the unique check and the both-flagged sort view) can assert they read/write the shared tree
	 * through the very same normalizer instance — a normalizer asymmetry would cause silent lookup misses.
	 */
	@Nonnull
	public Function<Object, Serializable> getNormalizer() {
		return this.normalizer;
	}

	/**
	 * Returns array of "buckets" ordered by {@link ValueToRecord#getValue()} that contain record ids assigned in them.
	 * Single-record primitive buckets are materialized to {@link ValueToRecordBitmap} at this boundary so the
	 * serialization / external-consumer surface stays unchanged.
	 */
	@Nonnull
	public ValueToRecordBitmap[] getValueToRecordBitmap() {
		// This materializes the whole bucket array and feeds the SINGLE (whole-index, inline)
		// serialization route only. It is intentionally retained: small inverted indexes stay inline in
		// the FilterIndexStoragePart root and are cheaper to rewrite whole than to maintain per-leaf page
		// bookkeeping. Large inverted indexes persist granularly via the page tree (collectChangedPages /
		// FilterIndexLeafPagePart), which bypasses this method entirely and writes only the leaves a
		// transaction actually changed.
		final List<ValueToRecordBitmap> result = new ArrayList<>(this.buckets.bucketCount());
		final BucketCursor cursor = this.buckets.cursor();
		while (cursor.next()) {
			final Serializable value = (Serializable) cursor.value();
			if (cursor.isSingle()) {
				result.add(new ValueToRecordBitmap(value, cursor.singleRecordId()));
			} else {
				// share the live TransactionalBitmap (no copy) - this is the serializer's read-only snapshot boundary
				result.add(new ValueToRecordBitmap(value, (TransactionalBitmap) cursor.records()));
			}
		}
		return result.toArray(ValueToRecordBitmap[]::new);
	}

	/**
	 * Returns the stable value id of every bucket, in the same ascending value order as
	 * {@link #getValueToRecordBitmap()} — its parallel column for the `SINGLE` (inline) serialization route, where the
	 * whole index rides the root part rather than per-leaf pages.
	 *
	 * @return the ids aligned with the inline bucket array, or `null` when this tree carries no value ids
	 */
	@Nullable
	public int[] getValueIds() {
		if (this.valueIdAllocator == null) {
			return null;
		}
		final CompositeIntArray result = new CompositeIntArray();
		final BucketCursor cursor = this.buckets.cursor();
		while (cursor.next()) {
			result.add(cursor.valueId());
		}
		return result.toArray();
	}

	/**
	 * Hands every distinct value together with its stable id to `consumer`, in ascending value order.
	 *
	 * This is how a consumer rebuilds a value-id-keyed structure of its own from a tree that has just come back from
	 * disk — the ids came back inside the pages, so the pairs handed out here are exactly the ones that were handed
	 * out while the catalog was last running. It walks the tree's cursor directly rather than materializing the
	 * buckets, so it allocates nothing per value; it is nevertheless `O(values)` and belongs to load and diagnostics,
	 * never to a query path.
	 *
	 * @param consumer receives each normalized value and the id naming it
	 * @throws GenericEvitaInternalError when this tree carries no value ids at all
	 */
	public void forEachValueId(@Nonnull ObjIntConsumer<Serializable> consumer) {
		Assert.isPremiseValid(
			this.valueIdAllocator != null,
			"This shared value tree carries no value ids, so there is nothing to walk - a consumer must attach " +
				"before it can rebuild anything from the ids."
		);
		final BucketCursor cursor = this.buckets.cursor();
		while (cursor.next()) {
			consumer.accept((Serializable) cursor.value(), cursor.valueId());
		}
	}

	/**
	 * Returns a transaction-aware iterator over ALL buckets ordered ascending by {@link #comparator}. Used by the
	 * both-flagged sort view to derive the ordered `(value, cardinality)` stream that segments the sort index's
	 * record blocks — each bucket yields its value via {@link ValueToRecord#getValue()} and its cardinality via the
	 * allocation-free {@link ValueToRecord#size()}.
	 */
	@Nonnull
	public Iterator<ValueToRecord> getValueIterator() {
		return cursorAsValueToRecordIterator(this.buckets.cursor());
	}

	/**
	 * Returns a transaction-aware iterator over ALL buckets ordered descending by {@link #comparator}. Reverse
	 * counterpart of {@link #getValueIterator()} used by the both-flagged sort view's reversed seeker.
	 */
	@Nonnull
	public Iterator<ValueToRecord> getValueReverseIterator() {
		return cursorAsValueToRecordIterator(this.buckets.reverseCursor());
	}

	/**
	 * Returns entire content of this histogram as "subset" that allows easy access to the record ids inside.
	 * Records returned by this {@link InvertedIndexSubSet} are sorted by the order of the bucket
	 * {@link ValueToRecord#getValue()}.
	 *
	 * This histogram:
	 * A: [1, 4]
	 * B: [2, 9]
	 * C: [3]
	 *
	 * Will return subset providing record ids bitmap in form of: [3, 2, 9, 1, 4]
	 */
	@Nonnull
	public InvertedIndexSubSet getRecords() {
		return getRecords(null, null);
	}

	/**
	 * Returns subset of this histogram with buckets between `moreThanEq` and `lessThanEq` (i.e. inclusive subset).
	 * Records returned by this {@link InvertedIndexSubSet} are sorted by the order of the bucket
	 * {@link ValueToRecord#getValue()}.
	 *
	 * @see #getRecords()
	 */
	public InvertedIndexSubSet getRecords(@Nullable Serializable moreThanEq, @Nullable Serializable lessThanEq) {
		return convertToUnSortedResult(getRecordsInternal(moreThanEq, lessThanEq, BoundsHandling.INCLUSIVE));
	}

	/**
	 * Returns entire content of this histogram as "subset" that allows easy access to the record ids inside.
	 * Records returned by this {@link InvertedIndexSubSet} are sorted by record id value.
	 *
	 * This histogram:
	 * A: [1, 4]
	 * B: [2, 9]
	 * C: [3]
	 *
	 * Will return subset providing record ids bitmap in form of: [1, 2, 3, 4, 9]
	 */
	@Nonnull
	public InvertedIndexSubSet getSortedRecords() {
		return getSortedRecords(null, null);
	}

	/**
	 * Returns subset of this histogram with buckets between `moreThanEq` and `lessThanEq` (i.e. inclusive subset).
	 * Records returned by this {@link InvertedIndexSubSet} are sorted by record id value.
	 *
	 * @see #getSortedRecords()
	 */
	@Nonnull
	public InvertedIndexSubSet getSortedRecords(@Nullable Serializable moreThanEq, @Nullable Serializable lessThanEq) {
		return convertToSortedResult(getRecordsInternal(moreThanEq, lessThanEq, BoundsHandling.INCLUSIVE));
	}

	/**
	 * Returns subset of this histogram with buckets between `moreThan` and `lessThan` (i.e. exclusive subset).
	 * Records returned by this {@link InvertedIndexSubSet} are sorted by record id value.
	 *
	 * @see #getSortedRecords()
	 */
	@Nonnull
	public InvertedIndexSubSet getSortedRecordsExclusive(
		@Nullable Serializable moreThan, @Nullable Serializable lessThan) {
		return convertToSortedResult(getRecordsInternal(moreThan, lessThan, BoundsHandling.EXCLUSIVE));
	}

	/**
	 * Returns a formula over the record ids of every bucket whose value matches `valuePredicate`, evaluated in a single
	 * forward pass over all buckets. The matching record-set bitmaps are read straight off the {@link BucketCursor}
	 * (a single-record bucket yields a {@link SingleRecordBitmap}, a multi-record bucket its live
	 * {@link TransactionalBitmap}) and folded into one OR formula without ever materializing a {@link ValueToRecord}
	 * flyweight or an intermediate {@link InvertedIndexSubSet}. The result is the natural ascending union of the matched
	 * buckets - the allocation-lean equivalent of the former `getSortedRecordsMatching(predicate).getFormula()` path.
	 *
	 * @param valuePredicate tests each (already-normalized) bucket value; a bucket is included when it returns true
	 * @return OR formula of the matched buckets' record ids, seeded with the leaf-version token set of the leaves the
	 * matched buckets live in so the formula cache keys on the pages actually read
	 */
	@Nonnull
	public Formula getRecordsMatchingFormula(@Nonnull Predicate<Serializable> valuePredicate) {
		final List<Bitmap> bitmaps = new ArrayList<>(64);
		final LeafVersionAccumulator leafVersions = new LeafVersionAccumulator();
		final BucketCursor cursor = this.buckets.cursor();
		while (cursor.next()) {
			if (valuePredicate.test((Serializable) cursor.value())) {
				// record the leaf of each matched bucket so the folded formula keys on the leaves it actually read
				leafVersions.accept(cursor.currentLeafId());
				// read the record set straight off the cursor - no ValueToRecord flyweight is materialized
				bitmaps.add(cursor.records());
			}
		}
		return toSortedOrFormula(bitmaps, leafVersions.toTokenSet());
	}

	/**
	 * Returns the record sets of every bucket named by one of `candidateValueIds` whose value passes `valuePredicate`
	 * — the reverse-lookup counterpart of {@link #getRecordsMatchingFormula(Predicate)}, and the verification half of
	 * the trigram substring path.
	 *
	 * Where the scan visits every bucket in key order, this visits only the buckets a candidate generator nominated,
	 * in whatever order it nominated them. Each candidate costs ONE `O(1)` directory probe, whether it matches or not:
	 * {@link IntRecordBucketTree#recordsOfMatchingValueId} answers the value, the leaf version token and the record set
	 * off the single slot that probe resolves, so a match no longer pays a second probe and a root-to-leaf descent to
	 * re-find a bucket already located. Candidates that resolve to nothing live
	 * are SKIPPED rather than refused: the trigram postings are keyed by value id and a value can die between the
	 * posting being read and this verification running, which is an ordinary race rather than a divergence.
	 *
	 * ## Buckets, not a formula
	 *
	 * This deliberately stops at the matched buckets rather than folding them into a {@link Formula}. What the answer
	 * is folded into — an eagerly materialized disjunction, or a lazily evaluated one — is the CALLER's decision, and
	 * the two shapes want different things: an eager caller consumes {@link MatchedBuckets#leafVersionIds()} as its
	 * staleness set, while a lazy one cannot (the leaves are only known once verification has already run, which is
	 * the very thing it defers) and ignores them.
	 *
	 * ## Committed state only
	 *
	 * Carries the same premise as {@link #getValueById(int)} and for the same reason — the directory has no diff
	 * layer, so answering inside a transaction would silently under-report. The caller must test for an open
	 * transaction and take its own fallback; this refuses.
	 *
	 * @param candidateValueIds the value ids to verify, in any order; entries beyond `candidateCount` are ignored
	 * @param candidateCount    how many leading entries of `candidateValueIds` are live
	 * @param valuePredicate    tests each candidate's (already-normalized) value, or `null` when the caller has
	 *                          established that EVERY candidate matches and no value need be decoded to prove it; a
	 *                          bucket is included when the predicate accepts the value its id names
	 * @return the matched buckets' record sets in candidate order, with the leaf-version token set of the leaves
	 * those buckets live in
	 * @throws GenericEvitaInternalError when a transaction is open on the current thread
	 */
	@Nonnull
	public MatchedBuckets getRecordsOfValueIdsMatching(
		@Nonnull int[] candidateValueIds,
		int candidateCount,
		@Nullable Predicate<Serializable> valuePredicate
	) {
		return getRecordsOfValueIdsMatching(candidateValueIds, candidateCount, valuePredicate, null);
	}

	/**
	 * The {@link #getRecordsOfValueIdsMatching(int[], int, Predicate)} above, additionally offering the verification
	 * a form of the test it can apply WITHOUT decoding each candidate's key into a `String`.
	 *
	 * `containsPatternUtf8` is the pattern's UTF-8 bytes, and is used only where the bucket tree's key column stores
	 * its keys as UTF-8 too. It must be the same question `valuePredicate` asks - plain containment, and a pattern
	 * that survives UTF-8 encoding unchanged - because where it applies it REPLACES the predicate rather than
	 * pre-filtering for it. `valuePredicate` is still required, and still answers for every column that cannot match
	 * bytes.
	 *
	 * **A null predicate with a non-null pattern is refused**, because the two say opposite things: the null predicate
	 * asserts every candidate is already known to match, while the pattern asks for each of them to be re-tested - and
	 * a column that cannot match bytes would then fall back to a predicate that is not there. There is no reading of
	 * that pair that both arguments agree on, so it is a caller error rather than a shorthand.
	 *
	 * @param candidateValueIds   the candidate ids to resolve
	 * @param candidateCount      how many leading entries of `candidateValueIds` are live
	 * @param valuePredicate      tests each candidate's (already-normalized) value, or `null` when every candidate is
	 *                            known to match - which requires `containsPatternUtf8` to be `null` too
	 * @param containsPatternUtf8 the containment pattern's UTF-8 bytes, or `null` to always take the predicate
	 * @return the matched buckets' record sets in candidate order, with the leaf-version token set of their leaves
	 * @throws GenericEvitaInternalError when a transaction is open on the current thread
	 */
	@Nonnull
	public MatchedBuckets getRecordsOfValueIdsMatching(
		@Nonnull int[] candidateValueIds,
		int candidateCount,
		@Nullable Predicate<Serializable> valuePredicate,
		@Nullable byte[] containsPatternUtf8
	) {
		Assert.isPremiseValid(
			valuePredicate != null || containsPatternUtf8 == null,
			"A byte pattern was offered together with a null predicate - the first asks for every candidate to be " +
				"verified and the second states that none needs to be. Pass the predicate the pattern stands in for, " +
				"or drop the pattern."
		);
		Assert.isPremiseValid(
			!Transaction.isTransactionAvailable(),
			"Value ids cannot be verified while a transaction is open on this thread - the directory addresses the " +
				"last published version of the tree while the leaves it reads are the transaction's own, so the " +
				"verification would silently under-report. Take the scan fallback instead."
		);
		// a tree that mints no ids can verify nothing, and answering EMPTY would be the silently wrong shape
		// `AbstractAttributeStringSearchTranslator#resolveFromIndex` warns about: handing a reduced index's own tree
		// the global candidate ids compiles, returns an empty result, and passes any test whose fixture is small
		// enough for that to look plausible. Candidates were resolved against a trigram index, and a trigram index
		// exists only where the global tree mints ids, so arriving here without an allocator is a wiring error
		Assert.isPremiseValid(
			this.valueIdAllocator != null || candidateCount == 0,
			"Value ids cannot be verified against a tree that mints none - the candidates were resolved against a " +
				"trigram index, so they belong to the global index's shared value tree and must be verified there. " +
				"Answering EMPTY here would silently narrow the result instead of reporting the mis-wiring."
		);
		final LeafVersionAccumulator leafVersions = new LeafVersionAccumulator();
		if (this.valueIdAllocator == null) {
			// no candidates and no ids: nothing to verify and nothing to mis-answer, so the empty answer is honest
			return new MatchedBuckets(MatchedBuckets.NO_RECORD_SETS, leafVersions.toTokenSet());
		}
		// catch up the warm-up path's writes, exactly as `getValueById` does and for the same reason - the premise
		// above has already established there is no transaction on this thread
		if (this.valueIdDirectoryStale) {
			refreshValueIdDirectory();
		}
		final List<Bitmap> bitmaps = new ArrayList<>(Math.min(candidateCount, 64));
		// the two adapters are hoisted out of the loop so the fused probe allocates nothing per candidate, and are
		// created inside this guard so that the provable-empty answer - the cheapest outcome this path produces, and
		// the one a pattern no value contains takes - allocates nothing at all
		if (candidateCount > 0) {
			// null is carried through rather than replaced by an always-true predicate: the point is not to skip a
			// cheap test but to skip DECODING the key that would be handed to it
			final Predicate<Comparable> matches = valuePredicate == null ?
				null : value -> valuePredicate.test((Serializable) value);
			final LongConsumer leafVersionSink = leafVersions::acceptUnordered;
			for (int i = 0; i < candidateCount; i++) {
				// ONE resolution of the bucket's location answers all three questions this loop used to ask
				// separately - what value the id names, which leaf page the answer depends on, and what records the
				// bucket holds. The leaf token still reaches the accumulator for MATCHES only; see the tree method
				final Bitmap records = this.buckets.recordsOfMatchingValueId(
					candidateValueIds[i], matches, containsPatternUtf8, leafVersionSink
				);
				if (records != null) {
					bitmaps.add(records);
				}
			}
		}
		return new MatchedBuckets(bitmaps.toArray(MatchedBuckets.NO_RECORD_SETS), leafVersions.toTokenSet());
	}

	/**
	 * Folds matched buckets into the natural ascending disjunction of their record ids — the EAGER assembly of
	 * {@link #getRecordsOfValueIdsMatching}'s answer, and the only part of the substring path that presupposes eager
	 * evaluation.
	 *
	 * `extraVersionIds` carries the staleness tokens of the structures the candidate set itself was derived from — the
	 * trigram index's own id — which the leaf tokens cannot express: a write that changed which values a pattern's
	 * postings nominate need not have touched any leaf this answer read.
	 *
	 * ## Why the result carries no search-term discriminator, and when that would change
	 *
	 * Because selection is EAGER, the formula is content-addressed: its hash is derived from the matched record sets,
	 * which *are* the answer. Two searches that hash equal therefore compute the same bitmap, so `contains("ab")` and
	 * `endsWith("ab")` sharing a cache entry is correct rather than a collision, and no search-term or constraint-kind
	 * discriminator is needed. That argument rests entirely on eagerness. Deferring selection would hash the QUESTION
	 * instead — see {@link io.evitadb.index.hierarchy.suppliers.HierarchyByParentBitmapSupplier}, the model for a
	 * {@link io.evitadb.core.query.algebra.deferred.BitmapSupplier} behind
	 * {@link io.evitadb.core.query.algebra.deferred.DeferredFormula}, with
	 * {@link io.evitadb.index.trigram.PatternPostings#candidateUpperBound} serving as its cheap cost estimate — and a
	 * discriminator would become mandatory. Deferring also costs invalidation granularity: the verified leaves are
	 * unknown until verification has run, so the staleness set collapses to whole-index ids, which is the difference
	 * between that supplier and {@link io.evitadb.index.invertedIndex.suppliers.HistogramBitmapSupplier}.
	 *
	 * @param matched         the verified buckets
	 * @param extraVersionIds staleness tokens to fold in beside the leaf tokens
	 * @return the disjunction over the matched buckets' record ids
	 */
	@Nonnull
	public Formula toFormula(@Nonnull MatchedBuckets matched, @Nonnull long[] extraVersionIds) {
		final long[] leafVersionIds = matched.leafVersionIds();
		final long[] tokenSet = new long[leafVersionIds.length + extraVersionIds.length];
		System.arraycopy(leafVersionIds, 0, tokenSet, 0, leafVersionIds.length);
		System.arraycopy(extraVersionIds, 0, tokenSet, leafVersionIds.length, extraVersionIds.length);
		Arrays.sort(tokenSet);
		return toSortedOrFormula(Arrays.asList(matched.recordSets()), tokenSet);
	}

	/**
	 * The buckets one candidate-verification pass matched, paired with the staleness tokens of the leaf pages they
	 * live in.
	 *
	 * @param recordSets     the matched buckets' record sets, in the order the candidates were nominated
	 * @param leafVersionIds the canonical (sorted, deduplicated) leaf-version token set of those buckets' leaves,
	 *                       collapsed to the single whole-index id when the leaf cap overflowed or nothing matched
	 */
	public record MatchedBuckets(@Nonnull Bitmap[] recordSets, @Nonnull long[] leafVersionIds) {

		/**
		 * Shared empty record-set array, for the answers that matched nothing.
		 */
		public static final Bitmap[] NO_RECORD_SETS = new Bitmap[0];

		/**
		 * @return whether no bucket matched
		 */
		public boolean isEmpty() {
			return this.recordSets.length == 0;
		}

	}

	/**
	 * Returns a formula over the record ids of the contiguous run of buckets that starts at the first bucket whose value
	 * sorts greater than or equal to `normalizedAnchor` and continues while `matchWhile` holds, stopping (early break) at
	 * the first bucket that fails it. Intended for orderings under which the matches form one contiguous run from the
	 * anchor - notably prefix search under the natural codepoint comparator (see
	 * `FilterIndex.getRecordsWhoseValuesStartWith`). Each matched bucket contributes a {@link ConstantFormula} read
	 * straight off the {@link BucketCursor}; no {@link ValueToRecord} flyweight or iterator wrapper is allocated.
	 *
	 * @param normalizedAnchor the already-normalized lower-bound value to anchor the forward scan at
	 * @param matchWhile       tests each bucket value; the scan stops at the first bucket that fails it
	 * @return OR formula of the matched run's record ids, or {@link EmptyFormula#INSTANCE} when nothing matches
	 */
	@Nonnull
	public Formula getRecordsStartingFromWhile(
		@Nonnull Serializable normalizedAnchor,
		@Nonnull Predicate<Serializable> matchWhile
	) {
		final List<Formula> formulas = new ArrayList<>();
		// anchor at the first bucket whose value sorts >= the anchor and walk forward while the predicate holds
		final BucketCursor cursor = this.buckets.cursor((Comparable) normalizedAnchor);
		while (cursor.next()) {
			if (matchWhile.test((Serializable) cursor.value())) {
				formulas.add(new ConstantFormula(cursor.records()));
			} else {
				// the matching buckets form a single contiguous run - stop at the first miss
				break;
			}
		}
		if (formulas.isEmpty()) {
			return EmptyFormula.INSTANCE;
		}
		return FormulaFactory.or(formulas.toArray(Formula.EMPTY_FORMULA_ARRAY));
	}

	/**
	 * Folds the collected record-set bitmaps into a single disjunction that orders its record ids by natural ascending
	 * value - the same shape the {@link #SORTED_AGGREGATION_LAMBDA} produces: an empty list yields
	 * {@link EmptyFormula#INSTANCE}, a single bitmap a bare {@link ConstantFormula}, and several bitmaps an
	 * {@link OrFormula} seeded with the leaf-version token set so the formula cache keys on the leaf pages the match
	 * actually read (collapsing to the whole-index id past the leaf cap - see {@link LeafVersionAccumulator}).
	 *
	 * @param bitmaps        the matched buckets' record-set bitmaps, in ascending bucket-value order
	 * @param leafVersionIds the canonical leaf-version token set of the leaves the matched buckets live in
	 * @return the disjunction formula over the bitmaps
	 */
	@Nonnull
	private Formula toSortedOrFormula(@Nonnull List<Bitmap> bitmaps, @Nonnull long[] leafVersionIds) {
		if (bitmaps.isEmpty()) {
			return EmptyFormula.INSTANCE;
		} else if (bitmaps.size() == 1) {
			return new ConstantFormula(bitmaps.get(0));
		} else {
			return new OrFormula(leafVersionIds, bitmaps.toArray(Bitmap[]::new));
		}
	}

	/**
	 * Returns an array of values associated with the specified record ID.
	 *
	 * @param recordId the ID of the record
	 * @return an array of values associated with the record ID
	 */
	@Nonnull
	public <S extends Serializable> S[] getValuesForRecord(int recordId, @Nonnull Class<S> type) {
		final CompositeObjectArray<S> result = new CompositeObjectArray<>(type);
		final BucketCursor cursor = this.buckets.cursor();
		while (cursor.next()) {
			// allocation-free membership test: a single bucket compares its lone id, a multi bucket probes its bitmap
			final boolean contains = cursor.isSingle()
				? cursor.singleRecordId() == recordId
				: cursor.records().contains(recordId);
			if (contains) {
				//noinspection unchecked
				result.add((S) cursor.value());
			}
		}
		return result.toArray();
	}

	/**
	 * Returns count of the buckets in the histogram.
	 */
	public int getBucketCount() {
		return this.buckets.bucketCount();
	}

	/**
	 * Returns count of all record ids in the histogram.
	 */
	public int getLength() {
		return this.buckets.recordCount();
	}

	/**
	 * Returns `true` when this inverted index has unflushed mutations. Exposed so a {@link io.evitadb.index.attribute.FilterIndex}
	 * VIEW can drive its persistence decision off the shared tree it wraps instead of its own (non-committed)
	 * dirty flag, which keeps the view free of transactional state.
	 */
	public boolean isDirty() {
		return this.dirty.isTrue();
	}

	@Override
	public void resetDirty() {
		this.dirty.setToFalse();
	}

	/**
	 * Returns the heap this index occupies, in bytes — its own object, its dirty flag and the whole bucket tree
	 * beneath it, keys and record bitmaps included.
	 *
	 * # What is charged, and what is not
	 *
	 * The **keys are this index's own** and are charged in full, priced by
	 * {@link IndexHeapSize#OWNED_KEY_SIZER}. No other index in the layer holds these instances: a
	 * {@code SortIndex} sources its ordering from this tree rather than copying values out, and a
	 * {@code FilterIndexView} holds a view object over this very tree — which is why both are priced without them.
	 *
	 * The remaining three references are the index's alone in name only and contribute their **slot** — `normalizer`
	 * and `comparator` are constructor-injected at every call site and shared with the owning `FilterIndex`, and
	 * `plainType` is a `Class`, owned by the JVM for the lifetime of its class loader.
	 *
	 * {@link #pageStreamRegistry} is excluded: it is single-writer flush bookkeeping carried by reference across
	 * commits, not index content.
	 *
	 * Like every tree walk this is `O(buckets / blockSize)` rather than `O(1)`, so it belongs to the index detail call
	 * and must never be called from a query path — see
	 * {@link BucketBPlusTree#getHeapSizeInBytes(java.util.function.ToLongFunction)}.
	 *
	 * @return the owned heap footprint in bytes, including alignment padding
	 */
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		// id + indexedDecimalPlaces + emittedNextValueId + valueIdDirectoryStale, then the dirty / buckets /
		// normalizer / comparator / plainType / pageStreamRegistry / valueIdAllocator / valueIdConsumers slots
		return layout.sizeOfObject(Long.BYTES + 2L * Integer.BYTES + 1L + 8L * layout.referenceSize())
			+ this.dirty.getHeapSizeInBytes()
			// the id COLUMNS are charged by the tree walk below; only the allocator object itself is charged here.
			// The consumer registry holds a handful of interned names and is not charged — it is diagnostics-sized,
			// bounded by the number of compiled-in subsystems rather than by the data.
			+ (this.valueIdAllocator == null ? 0L : this.valueIdAllocator.getHeapSizeInBytes())
			+ this.buckets.getHeapSizeInBytes(IndexHeapSize.OWNED_KEY_SIZER);
	}

	@Nonnull
	@Override
	public InvertedIndex createCopyWithMergedTransactionalMemory(
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		final boolean isDirty = transactionalLayer
			.getStateCopyWithCommittedChanges(this.dirty);
		if (isDirty) {
			final TransactionalBucketBPlusTree committedTree =
				(TransactionalBucketBPlusTree) transactionalLayer.getStateCopyWithCommittedChanges(this.buckets);
			// publish the page baseline staged by this commit's flush: the merge runs only AFTER the
			// flush has durably written the changed leaf pages + root, so the staged `pageSequence -> nodeId` map now
			// reflects what is on disk and becomes the live change-detection baseline for the next commit. The
			// registry is then carried BY REFERENCE into the committed copy, so the surviving owner keeps it. This is
			// the EARLIEST publish point on the transactional path only; it is not the only one — a staged set that
			// never reaches a merge (the warm-up path has no merge at all) is published by the next flush instead, see
			// `publishPreviousFlush`. (No discard counterpart is needed: a pre-flush abort never stages, and a failed
			// flush suspends this catalog's transaction processing — on the warm-up path it marks the catalog
			// unpublishable instead, the same invariant in another dress — so no later flush ever diffs against the baseline
			// a failed one left behind; restart rebuilds a clean registry from disk.)
			this.pageStreamRegistry.publishStaged();
			final InvertedIndex merged = new InvertedIndex(
				this.plainType,
				committedTree,
				this.normalizer,
				this.comparator,
				this.indexedDecimalPlaces,
				// carry the owner-resident page bookkeeping BY REFERENCE: the surviving committed owner keeps the
				// allocator + change-detection baseline the just-completed flush populated
				this.pageStreamRegistry
			);
			if (this.valueIdAllocator != null) {
				// the consumer registry is owner-resident bookkeeping and carries over by reference, exactly like the
				// page-stream registry above; the ALLOCATOR is transactional and is merged, then the surviving tree is
				// re-pointed at it. The committed tree already carries the minting operation of the pre-merge
				// allocator (`TransactionalBucketBPlusTree#createCopyWithMergedTransactionalMemory` carries it so the
				// leaves and the minter never disagree), so this re-point must happen before the merged index takes
				// any write — which it does, since nothing can reach `merged` until this method returns.
				merged.valueIdConsumers = this.valueIdConsumers;
				merged.valueIdAllocator =
					transactionalLayer.getStateCopyWithCommittedChanges(this.valueIdAllocator);
				// the emission baseline is owner-resident bookkeeping and must survive the commit, or every commit
				// would look like the high-water had changed and rewrite the root for nothing
				merged.emittedNextValueId = this.emittedNextValueId;
				committedTree.installValueIdMinter(merged.valueIdAllocator::allocate);
				// the directory belongs to the version it was built against: building it here, once, makes it
				// immutable for the lifetime of this committed index, so a reader holding an older version keeps
				// resolving against that version's own directory and MVCC needs no diff layer for it.
				//
				// WHICH rebuild is not a free choice. The incremental one reuses the entries of every leaf whose
				// version token the merge carried forward unchanged, and an in-place mutation - a warm-up write, the
				// only writer that reaches the published leaves directly - changes a leaf's content while keeping
				// that token. Taking it after such a write would silently leave the values it added out of the
				// directory, and the flag has just been cleared for the merged copy, so the lazy catch-up on the read
				// path would not repair them either: accelerated `attributeContains` would answer NO_LOCATION for
				// values the collection does hold, until a restart. The flag says exactly whether that happened
				// (see `markValueIdDirectoryStale`), so it is what chooses here
				if (this.valueIdDirectoryStale) {
					committedTree.rebuildValueIdDirectory();
				} else {
					committedTree.rebuildValueIdDirectoryAfterMerge();
				}
				merged.valueIdDirectoryStale = false;
			}
			return merged;
		} else {
			return this;
		}
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this.dirty);
		if (this.valueIdAllocator != null) {
			// an aborted transaction gives its minted ids back — see ValueIdAllocatorChanges for why that is sound
			this.valueIdAllocator.removeLayer(transactionalLayer);
		}
		this.buckets.removeLayer(transactionalLayer);
	}

	/**
	 * Returns whether this index's bucket tree spans more than one leaf and is therefore persisted in the granular
	 * `PAGED` shape (one record per leaf) rather than the inline `SINGLE` shape.
	 *
	 * @return true when the tree has an internal root (≥ 2 leaves)
	 */
	public boolean isPaged() {
		return this.buckets.isRootInternal();
	}

	/**
	 * Drops this index's page bookkeeping (allocator, high-water, baseline). Called when the index falls back to the
	 * inline `SINGLE` shape so a later regrow into `PAGED` starts from a clean baseline and re-emits every leaf. The
	 * caller is expected to have already issued removals for the prior `PAGED` leaf pages (see
	 * {@link #currentLeafPageSequences()})
	 * BEFORE calling this — once the bookkeeping is forgotten those page sequences can no longer be enumerated.
	 */
	public void forgetPageStream() {
		this.pageStreamRegistry.forget(BUCKET_PAGE_STREAM);
	}

	/**
	 * Promotes the page set staged by the PREVIOUS flush to the live change-detection baseline, so this flush's
	 * freed-page diff is taken against what disk actually holds.
	 *
	 * The registry's live set answers "which leaf pages does this stream have on disk", and everything the write path
	 * derives from it — which pages a leaf merge freed (so their records are REMOVED, the append-only OffsetIndex never
	 * reclaiming an unreferenced-but-never-removed record) and whether the ordered page list changed at all (so the
	 * `PAGED` root carrying it is re-emitted) — is only as good as that baseline. It advances solely by publishing,
	 * which {@link #createCopyWithMergedTransactionalMemory} does at the commit-merge.
	 *
	 * A WARM_UP (bulk) flush never reaches a commit-merge: it runs the very same collect pipeline
	 * ({@code DataStoreChanges.popTrappedUpdates} -> {@code getModifiedStorageParts}) but the merge that publishes only
	 * ever runs for a transaction. Left alone, the live set of a freshly re-indexed catalog would therefore stay EMPTY
	 * for the whole warm-up while disk moved on, making the freed-page diff of every warm-up flush vacuously empty. A
	 * leaf MERGE is the one structural event that drops a page without creating one — the survivor absorbs its sibling
	 * IN PLACE, keeping its own page and dirty flag, so nothing is allocated — which leaves the dropped page both
	 * unremoved on disk and still listed on a root that is skipped as "unchanged". The next cold load then assembles the
	 * survivor (holding the absorbed keys) followed by its stale, still-listed sibling, whose first key no longer sorts
	 * after the survivor's last — the overlapping-leaf-page corruption.
	 *
	 * Publishing a staged set HERE — rather than only at the merge — is correct for every path, because of one
	 * invariant: **a failed flush is never followed by another flush of the same data**. Note that this publish runs at
	 * COLLECT time, before this flush has written anything (the baseline-capture pass re-enters this pipeline), so it
	 * cannot and does not lean on the previous flush's bytes having landed by now. It does not need to: a flush that
	 * fails during trunk incorporation SUSPENDS the catalog's transaction processing ({@code TransactionManager.suspend}),
	 * and a flush that fails on the warm-up path makes the catalog UNPUBLISHABLE
	 * ({@code Catalog.markUnpublishable}), so every later flush of it refuses deterministically. Those two are
	 * the same invariant in different dresses: after a failed flush no later flush of that data ever runs, so nothing can
	 * ever diff against the baselines it left behind. A flush that does NOT fail leaves `staged` holding exactly the page
	 * set it wrote — the baseline the next flush must diff against — regardless of which path staged it, and regardless
	 * of whether a merge ever ran. (Should the process die instead, {@link #fromPersistedPages} rebuilds the registry
	 * from disk on restart — page allocation is advance-only, so a burnt id is harmless.) That is what makes this safe in
	 * its own right — not the fact that it happens to be a no-op on the transactional path (where the merge published
	 * first, leaving nothing staged). The commit handshake is untouched.
	 */
	private void publishPreviousFlush() {
		this.pageStreamRegistry.publishStaged();
	}

	/**
	 * Walks the bucket tree leaf-by-leaf and returns the granular write-path emission for this commit:
	 * the leaf pages that changed since the last flush (the ones the commit must (re)write), the
	 * full ordered list of live leaf-page sequences (the `PAGED` root's leaf list), and the stream high-water.
	 *
	 * For each leaf, a not-yet-paged (split-born or fresh) leaf is assigned a freshly allocated page sequence stamped
	 * onto the live node so the commit-merge carries it forward; each leaf's transaction-aware dirty flag decides
	 * whether it is re-emitted, and is cleared once its page is collected. The complete next live-page set is STAGED on
	 * the registry here and becomes live only when the commit is published (see the commit handshake). A clean
	 * (non-dirty) index must not call this — the caller gates on {@link #isDirty()}.
	 *
	 * Before staging, any set still staged by the PREVIOUS flush is promoted to live: see
	 * {@link #publishPreviousFlush()} for why that is both necessary and safe.
	 *
	 * @return the changed leaf pages, the ordered live page-sequence list, and the high-water
	 */
	@Nonnull
	public PageEmission<LeafPage> collectChangedPages() {
		publishPreviousFlush();
		// this.buckets is a raw tree, so the handle list and its cursors are raw too — bucket values are read as Object
		// and cast to Serializable exactly as the whole-tree materializer does
		final List<LeafPageHandle> handles = this.buckets.leafPageHandles();
		return this.pageStreamRegistry.collectChangedPages(
			BUCKET_PAGE_STREAM, handles,
			(pageSequence, handle) -> {
				final BucketCursor cursor = handle.cursor();
				final boolean withValueIds = carriesValueIds();
				final List<ValueToRecord> pageBuckets = new ArrayList<>();
				final CompositeIntArray pageValueIds = withValueIds ? new CompositeIntArray() : null;
				while (cursor.next()) {
					final Serializable value = (Serializable) cursor.value();
					pageBuckets.add(
						cursor.isSingle()
							? new ValueToRecordPrimitive(value, cursor.singleRecordId())
							: new ValueToRecordBitmap(value, (TransactionalBitmap) cursor.records())
					);
					if (pageValueIds != null) {
						pageValueIds.add(cursor.valueId());
					}
				}
				return new LeafPage(
					pageSequence, pageBuckets.toArray(ValueToRecord[]::new),
					pageValueIds == null ? null : pageValueIds.toArray()
				);
			}
		);
	}

	/**
	 * Returns the leaf-page sequences this inverted index WILL have on disk once the in-flight commit is durable (the
	 * staged set mid-flush, else the published live set), or an empty array when it is inline (SINGLE) / never paged.
	 * The published set alone lags a whole flush behind, so this reflects the CURRENT tree shape at any point
	 * of the flush, so the owning {@link io.evitadb.index.attribute.AttributeIndex} can snapshot "what disk holds after
	 * this commit" and, when the sub-index is later emptied and dropped from its map — after which this index's own
	 * flush never runs again — still reclaim the now-orphaned leaf pages instead of leaking them forever.
	 *
	 * @return the current on-disk leaf-page sequences, or an empty array for a SINGLE / never-paged index
	 */
	@Nonnull
	public int[] currentLeafPageSequences() {
		return this.pageStreamRegistry.pendingLivePageSequences(BUCKET_PAGE_STREAM);
	}

	/**
	 * One leaf page produced by the granular write path: its stable page sequence and its buckets in ascending value
	 * order.
	 *
	 * @param pageSequence the leaf's stable page sequence
	 * @param buckets the leaf's buckets in ascending value order
	 * @param valueIds the stable value id of each bucket, positionally aligned with `buckets`, or `null` when the tree
	 *                 carries no value ids
	 */
	public record LeafPage(int pageSequence, @Nonnull ValueToRecord[] buckets, @Nullable int[] valueIds) {
	}

	/**
	 * Content-based hash code over the logical bucket sequence `(value, recordIds)`, representation-independent and
	 * consistent with {@link #equals(Object)}. The single-record hash `31 + pk` matches a cardinality-1 bitmap's hash so
	 * a primitive and a one-element bitmap of the same id hash identically.
	 */
	@Override
	public int hashCode() {
		int result = 1;
		final BucketCursor cursor = this.buckets.cursor();
		while (cursor.next()) {
			result = 31 * result + cursor.value().hashCode();
			result = 31 * result + recordSetHashCode(cursor);
		}
		return result;
	}

	/**
	 * Content-based equality over the logical bucket sequence `(value, recordIds)`. Record-set comparison is
	 * representation-independent (see {@link ValueToRecord#recordSetEquals(ValueToRecord)}), so a primitive `{5}` bucket
	 * and a bitmap `{5}` bucket for the same value compare equal. The `dirty` flag and the `comparator` are intentionally
	 * excluded (mirroring the historical `@EqualsAndHashCode(exclude={"dirty","comparator"})`).
	 */
	@Override
	public boolean equals(@Nullable Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		final InvertedIndex that = (InvertedIndex) o;
		final BucketCursor thisCursor = this.buckets.cursor();
		final BucketCursor thatCursor = that.buckets.cursor();
		boolean thisHasNext = thisCursor.next();
		boolean thatHasNext = thatCursor.next();
		while (thisHasNext && thatHasNext) {
			if (!thisCursor.value().equals(thatCursor.value())
				|| !recordSetEquals(thisCursor, thatCursor)) {
				return false;
			}
			thisHasNext = thisCursor.next();
			thatHasNext = thatCursor.next();
		}
		return thisHasNext == thatHasNext;
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder(256);
		sb.append("InvertedIndex{points=[");
		final BucketCursor cursor = this.buckets.cursor();
		boolean first = true;
		while (cursor.next()) {
			if (!first) {
				sb.append(", ");
			}
			sb.append(materializeBucket(cursor));
			first = false;
		}
		sb.append("]}");
		return sb.toString();
	}

	/**
	 * Test-support: returns true when the bucket for the given `value` is stored in the compact single-record form (as
	 * opposed to the multi-record bitmap form). Package-private on purpose - it exposes the internal representation only
	 * to the inverted-index test suite.
	 */
	boolean isPrimitiveBucket(@Nonnull Serializable value) {
		final Comparable normalizedValue = (Comparable) this.normalizer.apply(value);
		final BucketCursor cursor = this.buckets.cursor(normalizedValue);
		return cursor.next() && cursor.value().equals(normalizedValue) && cursor.isSingle();
	}

	/*
		PRIVATE METHODS
	 */

	/**
	 * Returns subset that aggregates inner record ids by {@link ValueToRecord#getValue()} and thus the result may
	 * look unsorted on first look. Threads the slice's leaf-version token set into the produced
	 * {@link InvertedIndexSubSet} so a cached formula keys on the leaf pages the slice actually read.
	 */
	@Nonnull
	private static InvertedIndexSubSet convertToUnSortedResult(@Nonnull HistogramSlice slice) {
		return new InvertedIndexSubSet(
			slice.leafVersionIds(),
			slice.buckets(),
			UNSORTED_AGGREGATION_LAMBDA
		);
	}

	/**
	 * Returns subset that aggregates inner record ids by natural ascending ordering. Threads the slice's leaf-version
	 * token set into the produced {@link InvertedIndexSubSet} so a cached formula keys on the leaf pages the slice
	 * actually read.
	 */
	@Nonnull
	private static InvertedIndexSubSet convertToSortedResult(@Nonnull HistogramSlice slice) {
		return new InvertedIndexSubSet(
			slice.leafVersionIds(),
			slice.buckets(),
			SORTED_AGGREGATION_LAMBDA
		);
	}

	/**
	 * Searches histogram and select all buckets that fulfill the between `moreThanEq` and `lessThanEq` constraints.
	 * Returns a {@link HistogramSlice} pairing the in-range buckets (ascending value order) with the canonical
	 * leaf-version token set of the leaves the slice crossed (collected via {@link LeafVersionAccumulator}, capped to
	 * the whole-index id on overflow).
	 *
	 * The bounds are reproduced as key-bounded tree iteration (only the queried slice is streamed) instead of a binary
	 * search over a materialized array. The {@link BoundsHandling#INCLUSIVE} mode keeps buckets equal to a bound while
	 * the {@link BoundsHandling#EXCLUSIVE} mode drops them:
	 *
	 * - lower bound: the iterator anchors at the first bucket whose value is greater than or equal to the (normalized)
	 * `moreThanEq`; for the exclusive mode the bucket exactly equal to the bound is skipped,
	 * - upper bound: the iteration stops (early break) once a bucket value passes `lessThanEq` - strictly greater for the
	 * inclusive mode, greater than or equal for the exclusive mode.
	 */
	@Nonnull
	private HistogramSlice getRecordsInternal(
		@Nullable Serializable moreThanEq,
		@Nullable Serializable lessThanEq,
		@Nonnull BoundsHandling boundsHandling
	) {
		final Serializable normalizedMoreThanEq = this.normalizer.apply(moreThanEq);
		final Serializable normalizedLessThanEq = this.normalizer.apply(lessThanEq);
		//noinspection unchecked
		Assert.isTrue(
			normalizedMoreThanEq == null || normalizedLessThanEq == null
				|| this.comparator.compare(normalizedMoreThanEq, normalizedLessThanEq) <= 0,
			"From must be lower than to: " + normalizedMoreThanEq + " vs. " + normalizedLessThanEq
		);

		final List<ValueToRecord> result = new ArrayList<>(64);
		final LeafVersionAccumulator leafVersions = new LeafVersionAccumulator();
		// anchor the forward cursor at the first bucket >= lower bound (or at the very start when unbounded)
		final BucketCursor cursor = normalizedMoreThanEq == null
			? this.buckets.cursor()
			: this.buckets.cursor((Comparable) normalizedMoreThanEq);
		while (cursor.next()) {
			final Serializable value = (Serializable) cursor.value();
			// exclusive lower bound: skip the bucket exactly equal to the lower bound
			if (boundsHandling == BoundsHandling.EXCLUSIVE && normalizedMoreThanEq != null) {
				//noinspection unchecked
				if (this.comparator.compare(value, normalizedMoreThanEq) == 0) {
					continue;
				}
			}
			// upper bound check - stop iterating once past it (keys are ascending under the comparator)
			if (normalizedLessThanEq != null) {
				//noinspection unchecked
				final int cmp = this.comparator.compare(value, normalizedLessThanEq);
				if (cmp > 0 || (boundsHandling == BoundsHandling.EXCLUSIVE && cmp == 0)) {
					break;
				}
			}
			// record the leaf this in-range bucket lives in; skipped buckets above never reach here
			leafVersions.accept(cursor.currentLeafId());
			result.add(materializeBucket(cursor));
		}
		return new HistogramSlice(result.toArray(ValueToRecord[]::new), leafVersions.toTokenSet());
	}

	/**
	 * A slice of the histogram returned by {@link #getRecordsInternal}: the selected buckets in ascending value order,
	 * paired with the canonical leaf-version token set of the leaf pages the slice crossed (see
	 * {@link LeafVersionAccumulator}). The token set is what makes a cached formula over an untouched value range
	 * survive writes to other pages.
	 *
	 * @param buckets        the selected buckets in ascending value order
	 * @param leafVersionIds the canonical (sorted, deduplicated) leaf-version token set for the slice
	 */
	private record HistogramSlice(@Nonnull ValueToRecord[] buckets, @Nonnull long[] leafVersionIds) {
	}

	/**
	 * Accumulates the distinct version ids of the leaf pages a slice/scan crosses, in ascending traversal order, and
	 * caps the set at {@link TransactionalDataRelatedStructure#EXCESSIVE_HIGH_CARDINALITY} leaves. A forward
	 * {@link BucketCursor} visits every bucket of a leaf consecutively, so accepting an id only when it differs from the
	 * previous one (a consecutive-dedup) yields the distinct leaf set without a hash set. {@link #toTokenSet()} folds
	 * the gathered ids into the canonical staleness token, collapsing to the single whole-index id when the cap
	 * overflowed or no leaf was crossed (an empty slice, whose formula never reads the token).
	 *
	 * A reverse-lookup consumer meets its leaves in no particular order and so cannot use the consecutive-dedup - see
	 * {@link #acceptUnordered(long)}, which pays a bounded linear probe for the same result.
	 */
	private final class LeafVersionAccumulator {
		private final long[] leafIds = new long[TransactionalDataRelatedStructure.EXCESSIVE_HIGH_CARDINALITY];
		private int leafCount;
		private boolean overflow;
		private long lastLeafId;
		private boolean haveLast;

		/**
		 * Records the id of the leaf the cursor is currently positioned in. Cheap to call once per in-range bucket:
		 * ids repeat within a leaf and are dropped by the consecutive-dedup; the {@code EXCESSIVE_HIGH_CARDINALITY + 1}
		 * distinct leaf flips this accumulator to overflow and stops further collection.
		 *
		 * @param leafId the current bucket's leaf version id ({@link BucketCursor#currentLeafId()})
		 */
		void accept(long leafId) {
			if (this.overflow || (this.haveLast && leafId == this.lastLeafId)) {
				return;
			}
			this.lastLeafId = leafId;
			this.haveLast = true;
			if (this.leafCount == this.leafIds.length) {
				this.overflow = true;
			} else {
				this.leafIds[this.leafCount++] = leafId;
			}
		}

		/**
		 * Records a leaf version id met in ARBITRARY order - what a reverse lookup produces, since value ids are
		 * allocation-ordered and say nothing about which leaf their bucket ended up in.
		 *
		 * The consecutive-dedup {@link #accept(long)} relies on is kept as the cheap first test (runs of ids from one
		 * leaf are common enough to be worth it), backed by a linear probe over what has been gathered so far. That
		 * probe is bounded by {@link TransactionalDataRelatedStructure#EXCESSIVE_HIGH_CARDINALITY} entries and stops
		 * growing the instant the cap overflows, which is what keeps a large match set from paying a quadratic dedup:
		 * once the collection has overflowed, every further call returns on the first branch.
		 *
		 * @param leafId the matched bucket's leaf version id, or {@code 0} when the id resolved to nothing live
		 */
		void acceptUnordered(long leafId) {
			if (this.overflow || leafId == TransactionalBucketBPlusTree.NO_LEAF_VERSION
				|| (this.haveLast && leafId == this.lastLeafId)) {
				return;
			}
			this.lastLeafId = leafId;
			this.haveLast = true;
			for (int i = 0; i < this.leafCount; i++) {
				if (this.leafIds[i] == leafId) {
					return;
				}
			}
			if (this.leafCount == this.leafIds.length) {
				this.overflow = true;
			} else {
				this.leafIds[this.leafCount++] = leafId;
			}
		}

		/**
		 * @return the canonical (sorted, deduplicated) leaf-version token set, or the single whole-index id when the
		 * leaf cap overflowed or no leaf was crossed
		 */
		@Nonnull
		long[] toTokenSet() {
			if (this.overflow || this.leafCount == 0) {
				return new long[]{getId()};
			}
			final long[] tokenSet = Arrays.copyOf(this.leafIds, this.leafCount);
			Arrays.sort(tokenSet);
			return tokenSet;
		}
	}

	/**
	 * Represents search mode - i.e. whether records at the very bounds should be included in result or not.
	 */
	private enum BoundsHandling {

		EXCLUSIVE, INCLUSIVE

	}

}
