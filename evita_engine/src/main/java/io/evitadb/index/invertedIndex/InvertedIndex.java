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
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.dataType.ConsistencySensitiveDataStructure;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BucketCursor;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.SingleRecordBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.invertedIndex.suppliers.HistogramBitmapSupplier;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.BiFunction;
import java.util.function.Function;
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
 * in a primitive `int` column (no {@link org.roaringbitmap.RoaringBitmap}), and multi-record buckets keep a mutable
 * {@link io.evitadb.index.bitmap.TransactionalBitmap} in a sparse overflow column. A single-record bucket promotes to a
 * bitmap when a second distinct record id is added; there is no demotion back. The {@link ValueToRecord} hierarchy
 * survives only as a transient flyweight materialized on demand (serializer DTO + iterator bridge); the tree never
 * stores a per-bucket {@link ValueToRecord} object.
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
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
@SuppressWarnings({"rawtypes", "unchecked"})
@ThreadSafe
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
	private static final BiFunction<Long, ValueToRecord[], Formula> UNSORTED_AGGREGATION_LAMBDA = (indexTransactionId, histogramBuckets) -> new DeferredFormula(
		new HistogramBitmapSupplier(indexTransactionId, histogramBuckets)
	);
	/**
	 * This lambda lay out records in natural ascending order.
	 */
	private static final BiFunction<Long, ValueToRecord[], Formula> SORTED_AGGREGATION_LAMBDA = (indexTransactionId, histogramBuckets) -> {
		final Bitmap[] bitmaps = new Bitmap[histogramBuckets.length];
		for (int i = 0; i < histogramBuckets.length; i++) {
			bitmaps[i] = histogramBuckets[i].getRecordIds();
		}
		if (bitmaps.length == 0) {
			return EmptyFormula.INSTANCE;
		} else if (bitmaps.length == 1) {
			return new ConstantFormula(bitmaps[0]);
		} else {
			return new OrFormula(new long[]{indexTransactionId}, bitmaps);
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
	private final TransactionalBucketBPlusTree buckets;
	/**
	 * Normalizer is used to convert objects to serializable form.
	 */
	@Nonnull private final Function<Object, Serializable> normalizer;
	/**
	 * Instance of comparator that should be used for values in {@link #buckets}
	 */
	@Nonnull @Getter private final Comparator comparator;

	/**
	 * Creates a fresh, empty tree ordered by the passed comparator.
	 */
	@Nonnull
	@SuppressWarnings("unchecked")
	private static TransactionalBucketBPlusTree createEmptyTree(@Nonnull Comparator comparator) {
		return new TransactionalBucketBPlusTree<>(
			VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_INTERNAL_NODE_BLOCK_SIZE,
			Comparable.class,
			comparator
		);
	}

	/**
	 * Materializes the bucket at the cursor's CURRENT position into a transient {@link ValueToRecord} flyweight. A
	 * single-record bucket becomes a compact {@link ValueToRecordPrimitive}; a multi-record bucket becomes a
	 * {@link ValueToRecordBitmap} sharing the very same {@link io.evitadb.index.bitmap.TransactionalBitmap} instance
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
	 * (merged) bitmap views and is representation-independent (a direct {@link io.evitadb.index.bitmap.TransactionalBitmap}
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

	public InvertedIndex(
		@Nonnull Function<Object, Serializable> normalizer,
		@Nonnull Comparator comparator
	) {
		this.buckets = createEmptyTree(comparator);
		this.normalizer = normalizer;
		this.comparator = comparator;
		this.dirty = new TransactionalBoolean(false);
	}

	public InvertedIndex(
		@Nonnull ValueToRecordBitmap[] buckets,
		@Nonnull Function<Object, Serializable> normalizer,
		@Nonnull Comparator comparator
	) {
		final TransactionalBucketBPlusTree tree = createEmptyTree(comparator);
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
		this.dirty = new TransactionalBoolean(false);
	}

	/**
	 * Private constructor used by {@link #createCopyWithMergedTransactionalMemory} to wrap an already committed tree.
	 *
	 * @param committedTree the tree obtained from the committed transactional state
	 * @param normalizer    the normalizer of the source index
	 * @param comparator    the comparator of the source index
	 */
	private InvertedIndex(
		@Nonnull TransactionalBucketBPlusTree committedTree,
		@Nonnull Function<Object, Serializable> normalizer,
		@Nonnull Comparator comparator
	) {
		this.buckets = committedTree;
		this.normalizer = normalizer;
		this.comparator = comparator;
		this.dirty = new TransactionalBoolean(false);
	}

	@Nonnull
	@Override
	public ConsistencyReport getConsistencyReport() {
		return this.buckets.getConsistencyReport();
	}

	/**
	 * Adds single record id into the bucket with specified `value`. If no bucket with this value exists, it is
	 * automatically created as a compact single-record column entry. A single-record bucket promotes to a multi-record
	 * bitmap when a second distinct record id is added; an add of the id it already holds is a no-op. A bitmap bucket is
	 * mutated in place so its transactional diff layer is preserved.
	 */
	public void addRecord(@Nonnull Serializable value, int recordId) {
		final Comparable normalizedValue = (Comparable) this.normalizer.apply(value);
		this.buckets.addRecord(normalizedValue, recordId);
		this.dirty.setToTrue();
	}

	/**
	 * Adds multiple records id into the bucket with specified `value`. If no bucket with this value exists, it is
	 * automatically created (a single-record column entry for a single id, a multi-record bitmap otherwise). A
	 * single-record bucket promotes to a bitmap unless the only id being added is the one it already holds. A bitmap
	 * bucket is mutated in place so its transactional diff layer is preserved.
	 */
	public void addRecord(@Nonnull Serializable value, int... recordId) {
		Assert.isTrue(!ArrayUtils.isEmpty(recordId), "Record ids must be not null and non-empty!");
		final Comparable normalizedValue = (Comparable) this.normalizer.apply(value);
		this.buckets.addRecord(normalizedValue, recordId);
		this.dirty.setToTrue();
	}

	/**
	 * Removes one or multiple record ids from the bucket with specified `value`. If no bucket with this value exists,
	 * nothing happens. If the bucket contains no record id that match passed record id, nothing happens. If removal
	 * of the record ids leaves the bucket empty, it's entirely removed (the tree releases the value's transactional
	 * layer). A bitmap bucket is mutated in place; a single-record bucket can only be deleted (it holds a single
	 * id, so removing that id empties it). The dirty flag is always raised to mirror the historical behaviour.
	 */
	public void removeRecord(@Nonnull Serializable value, int... recordId) {
		Assert.isTrue(!ArrayUtils.isEmpty(recordId), "Record ids must be not null and non-empty!");
		// historical quirk: the dirty flag is raised unconditionally BEFORE the lookup, even on a no-op remove
		this.dirty.setToTrue();
		final Comparable normalizedValue = (Comparable) this.normalizer.apply(value);
		this.buckets.removeRecord(normalizedValue, recordId);
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
		//TODO JNO (#760): materializes the whole bucket array on every commit only to feed the
		// serialization route; remove once StorageParts become granular (issue #760 part B — persist
		// only the changed parts of the index instead of rewriting the entire index per transaction).
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
	 * Returns a transaction-aware iterator over the sorted buckets starting from the first bucket whose value is greater
	 * than or equal to the passed already-normalized value (the value itself need not be present). The iterator walks the
	 * transactional view so callers inside an open transaction observe the in-progress state. Intended for prefix lookups
	 * that scan a contiguous run of buckets starting at an anchor (see `FilterIndex.getRecordsWhoseValuesStartWith`).
	 *
	 * @param normalizedValue the value already normalized by the caller (via FilterIndex normalizer) used as lower bound
	 */
	@Nonnull
	public Iterator<ValueToRecord> getValueIteratorFrom(@Nonnull Serializable normalizedValue) {
		return cursorAsValueToRecordIterator(this.buckets.cursor((Comparable) normalizedValue));
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
		final ValueToRecord[] records = getRecordsInternal(moreThanEq, lessThanEq, BoundsHandling.INCLUSIVE);
		return convertToUnSortedResult(records);
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
		final ValueToRecord[] records = getRecordsInternal(moreThanEq, lessThanEq, BoundsHandling.INCLUSIVE);
		return convertToSortedResult(records);
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
		final ValueToRecord[] records = getRecordsInternal(moreThan, lessThan, BoundsHandling.EXCLUSIVE);
		return convertToSortedResult(records);
	}

	/**
	 * Returns subset of this histogram with buckets whose values match the given predicate.
	 * Records returned by this {@link InvertedIndexSubSet} are sorted by record id value.
	 *
	 * @see #getSortedRecords()
	 */
	@Nonnull
	public InvertedIndexSubSet getSortedRecordsMatching(@Nonnull Predicate<Serializable> valuePredicate) {
		final List<ValueToRecord> result = new ArrayList<>(64);
		final BucketCursor cursor = this.buckets.cursor();
		while (cursor.next()) {
			if (valuePredicate.test((Serializable) cursor.value())) {
				// a bucket in the tree always holds at least one record, so the historical non-empty guard is implicit
				result.add(materializeBucket(cursor));
			}
		}
		return convertToSortedResult(result.toArray(ValueToRecord[]::new));
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

	@Nonnull
	@Override
	public InvertedIndex createCopyWithMergedTransactionalMemory(
		@Nullable Void layer,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		final boolean isDirty = transactionalLayer
			.getStateCopyWithCommittedChanges(this.dirty);
		if (isDirty) {
			final TransactionalBucketBPlusTree committedTree =
				(TransactionalBucketBPlusTree) transactionalLayer.getStateCopyWithCommittedChanges(this.buckets);
			return new InvertedIndex(
				committedTree,
				this.normalizer,
				this.comparator
			);
		} else {
			return this;
		}
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this.dirty);
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		this.buckets.removeLayer(transactionalLayer);
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
	 * look unsorted on first look.
	 */
	@Nonnull
	private InvertedIndexSubSet convertToUnSortedResult(@Nonnull ValueToRecord[] records) {
		return new InvertedIndexSubSet(
			getId(),
			records,
			UNSORTED_AGGREGATION_LAMBDA
		);
	}

	/**
	 * Returns subset that aggregates inner record ids by natural ascending ordering.
	 */
	@Nonnull
	private InvertedIndexSubSet convertToSortedResult(@Nonnull ValueToRecord[] records) {
		return new InvertedIndexSubSet(
			getId(),
			records,
			SORTED_AGGREGATION_LAMBDA
		);
	}

	/**
	 * Searches histogram and select all buckets that fulfill the between `moreThanEq` and `lessThanEq` constraints.
	 * Returns array of all {@link ValueToRecord} in the range.
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
	private ValueToRecord[] getRecordsInternal(
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
			result.add(materializeBucket(cursor));
		}
		return result.toArray(ValueToRecord[]::new);
	}

	/**
	 * Represents search mode - i.e. whether records at the very bounds should be included in result or not.
	 */
	private enum BoundsHandling {

		EXCLUSIVE, INCLUSIVE

	}

}
