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
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.dataType.ConsistencySensitiveDataStructure;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.bPlusTree.TransactionalObjectBPlusTree;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
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
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Histogram index is based on <a href="https://en.wikipedia.org/wiki/Histogram">Histogram data structure</a>. It's
 * organized as a set of "buckets" ordered from minimal to maximal {@link Comparable} value. Each bucket has assigned
 * bitmap (ordered distinct set of primitive integer values) that are assigned to bucket {@link ValueToRecord#getValue()}.
 *
 * Search in histogram is possible via. binary search with O(log n) complexity due its sorted nature. Set of records
 * are easily available as the set assigned to that value. Range look-ups are also available as boolean OR of all bitmaps
 * from / to looked up value threshold.
 *
 * The buckets are stored in a {@link TransactionalObjectBPlusTree} keyed by the (normalized) bucket value and ordered
 * by the supplied {@link Comparator}. A write therefore touches only the affected leaf and its ancestors (path-copying)
 * instead of reallocating the whole structure - this is the key write-latency improvement targeted by issue #760.
 *
 * Each bucket is a {@link ValueToRecord}: single-record buckets (the long tail of any filterable attribute) are stored
 * as the compact, immutable {@link ValueToRecordPrimitive} (a bare `int`, no {@link org.roaringbitmap.RoaringBitmap}),
 * while multi-record buckets use the mutable {@link ValueToRecordBitmap}. A primitive promotes to a bitmap when a second
 * distinct record id is added; there is no demotion back.
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
@SuppressWarnings("rawtypes")
@ThreadSafe
public class InvertedIndex implements
	IndexDataStructure,
	ConsistencySensitiveDataStructure,
	VoidTransactionMemoryProducer<InvertedIndex>,
	Serializable
{
	@Serial private static final long serialVersionUID = 3019703951858227807L;

	/**
	 * Wrapper that adapts a committed value coming out of the B+ tree commit into a {@link ValueToRecord}. Both
	 * implementations of {@link ValueToRecord} merge to a {@link ValueToRecord} (their
	 * {@link ValueToRecord#createCopyWithMergedTransactionalMemory} returns a {@link ValueToRecord}), therefore an
	 * identity cast is sufficient here - unlike value types that merge to a different class.
	 */
	private static final Function<Object, ValueToRecord> VALUE_TO_RECORD_WRAPPER = ValueToRecord.class::cast;

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
	 * This is internal flag that tracks whether the index contents became dirty and needs to be persisted.
	 */
	private final TransactionalBoolean dirty;
	/**
	 * The buckets contain ordered comparable values with bitmaps of all records with such value. The tree is keyed by
	 * the (normalized) bucket value and ordered by {@link #comparator}.
	 */
	private final TransactionalObjectBPlusTree buckets;
	/**
	 * Normalizer is used to convert objects to serializable form.
	 */
	@Nonnull private final Function<Object, Serializable> normalizer;
	/**
	 * Instance of comparator that should be used for values in {@link #buckets}
	 */
	@Nonnull @Getter private final Comparator comparator;

	/**
	 * This lambda lay out records by {@link ValueToRecord#getValue()} one after another.
	 */
	private static final BiFunction<Long, ValueToRecord[], Formula> UNSORTED_AGGREGATION_LAMBDA = (indexTransactionId, histogramBuckets) -> new DeferredFormula(
		new HistogramBitmapSupplier(histogramBuckets)
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
			return new OrFormula(new long[] {indexTransactionId}, bitmaps);
		}
	};

	/**
	 * Method verifies that {@link ValueToRecord#getValue()}s in passed set are monotonically increasing and contain
	 * no duplicities.
	 */
	@Nonnull
	private static ConsistencyReport checkConsistency(@Nonnull ValueToRecord[] points, @Nonnull Comparator comparator) {
		final StringBuilder report = new StringBuilder(256);
		Serializable previous = null;
		for (ValueToRecord bucket : points) {
			Serializable finalPrevious = previous;
			//noinspection unchecked
			if (!(previous == null || comparator.compare(previous, bucket.getValue()) < 0)) {
				report.append("Histogram values are not monotonic - ")
					.append("conflicting values: ")
					.append(finalPrevious).append(", ")
					.append(bucket.getValue()).append(".\n");
			}
			previous = bucket.getValue();
		}
		if (report.isEmpty()) {
			return new ConsistencyReport(ConsistencyState.CONSISTENT, null);
		} else {
			return new ConsistencyReport(ConsistencyState.BROKEN, report.toString());
		}
	}

	/**
	 * Creates a fresh, empty tree ordered by the passed comparator.
	 */
	@Nonnull
	private static TransactionalObjectBPlusTree createEmptyTree(@Nonnull Comparator comparator) {
		//noinspection unchecked
		return new TransactionalObjectBPlusTree<>(
			VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_INTERNAL_NODE_BLOCK_SIZE,
			Comparable.class,
			ValueToRecord.class,
			VALUE_TO_RECORD_WRAPPER,
			comparator
		);
	}

	/**
	 * Inserts or updates the bucket stored under the passed (already-normalized) key. The unchecked key cast required by
	 * the genuinely raw {@link Comparable} key type is confined to this single, documented place; the value side stays
	 * statically `ValueToRecord`-typed so the updater lambda is checked.
	 */
	@SuppressWarnings("unchecked")
	private void upsertBucket(@Nonnull Comparable key, @Nonnull UnaryOperator<ValueToRecord> updater) {
		this.buckets.upsert(key, updater);
	}

	/**
	 * Returns the bucket stored under the passed (already-normalized) key, or `null` when absent. Confines the unchecked
	 * key cast in the same way as {@link #upsertBucket}.
	 */
	@Nullable
	@SuppressWarnings("unchecked")
	private ValueToRecord searchBucket(@Nonnull Comparable key) {
		return (ValueToRecord) this.buckets.search(key).orElse(null);
	}

	/**
	 * Deletes the bucket stored under the passed (already-normalized) key.
	 */
	@SuppressWarnings("unchecked")
	private void deleteBucket(@Nonnull Comparable key) {
		this.buckets.delete(key);
	}

	/**
	 * Returns a transaction-aware iterator over all buckets ordered by {@link #comparator}.
	 */
	@Nonnull
	@SuppressWarnings("unchecked")
	private Iterator<ValueToRecord> bucketIterator() {
		return this.buckets.valueIterator();
	}

	/**
	 * Returns a transaction-aware iterator over the buckets whose value is greater than or equal to the passed
	 * (already-normalized) key.
	 */
	@Nonnull
	@SuppressWarnings("unchecked")
	private Iterator<ValueToRecord> bucketIteratorFrom(@Nonnull Comparable key) {
		return this.buckets.greaterOrEqualValueIterator(key);
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
		final TransactionalObjectBPlusTree tree = createEmptyTree(comparator);
		// rebuild the tree from the deserialized snapshot by inserting all buckets (values are unique & monotonic).
		// single-record buckets are normalized to the compact primitive form so the heap win survives a reload.
		for (final ValueToRecordBitmap bucket : buckets) {
			//noinspection unchecked
			tree.insert((Comparable) bucket.getValue(), toStoredForm(bucket));
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
		@Nonnull TransactionalObjectBPlusTree committedTree,
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
		return checkConsistency(materializeBuckets(), this.comparator);
	}

	/**
	 * Adds single record id into the bucket with specified `value`. If no bucket with this value exists, it is automatically
	 * created as a compact {@link ValueToRecordPrimitive}. A primitive bucket promotes to a {@link ValueToRecordBitmap}
	 * when a second distinct record id is added; an add of the id it already holds is a no-op. A bitmap bucket is mutated
	 * in place so its transactional diff layer is preserved.
	 */
	public void addRecord(@Nonnull Serializable value, int recordId) {
		final Serializable normalizedValue = this.normalizer.apply(value);
		upsertBucket(
			(Comparable) normalizedValue,
			bucket -> {
				if (bucket == null) {
					return new ValueToRecordPrimitive(normalizedValue, recordId);
				}
				if (bucket instanceof final ValueToRecordBitmap bitmapBucket) {
					bitmapBucket.addRecord(recordId);
					return bitmapBucket;
				}
				final ValueToRecordPrimitive primitiveBucket = (ValueToRecordPrimitive) bucket;
				if (primitiveBucket.getRecordId() == recordId) {
					// already the sole record - nothing to do, keep the compact form
					return primitiveBucket;
				}
				// second distinct record id - promote to the multi-record bitmap representation
				return new ValueToRecordBitmap(normalizedValue, primitiveBucket.getRecordId(), recordId);
			}
		);
		this.dirty.setToTrue();
	}

	/**
	 * Adds multiple records id into the bucket with specified `value`. If no bucket with this value exists, it is
	 * automatically created (a {@link ValueToRecordPrimitive} for a single id, a {@link ValueToRecordBitmap} otherwise).
	 * A primitive bucket promotes to a bitmap unless the only id being added is the one it already holds. A bitmap bucket
	 * is mutated in place so its transactional diff layer is preserved.
	 */
	public void addRecord(@Nonnull Serializable value, int... recordId) {
		Assert.isTrue(!ArrayUtils.isEmpty(recordId), "Record ids must be not null and non-empty!");
		final Serializable normalizedValue = this.normalizer.apply(value);
		upsertBucket(
			(Comparable) normalizedValue,
			bucket -> {
				if (bucket == null) {
					return recordId.length == 1
						? new ValueToRecordPrimitive(normalizedValue, recordId[0])
						: new ValueToRecordBitmap(normalizedValue, recordId);
				}
				if (bucket instanceof final ValueToRecordBitmap bitmapBucket) {
					bitmapBucket.addRecord(recordId);
					return bitmapBucket;
				}
				final ValueToRecordPrimitive primitiveBucket = (ValueToRecordPrimitive) bucket;
				if (recordId.length == 1 && recordId[0] == primitiveBucket.getRecordId()) {
					// the only id being added is the one already held - keep the compact form
					return primitiveBucket;
				}
				// promote to a bitmap holding the existing id plus all added ids (BaseBitmap dedupes & orders)
				final ValueToRecordBitmap promoted = new ValueToRecordBitmap(
					normalizedValue, primitiveBucket.getRecordId()
				);
				promoted.addRecord(recordId);
				return promoted;
			}
		);
		this.dirty.setToTrue();
	}

	/**
	 * Removes one or multiple record ids from the bucket with specified `value`. If no bucket with this value exists,
	 * nothing happens. If the bucket contains no record id that match passed record id, nothing happens. If removal
	 * of the record ids leaves the bucket empty, it's entirely removed (the tree releases the value's transactional
	 * layer). A bitmap bucket is mutated in place; the immutable primitive bucket can only be deleted (it holds a single
	 * id, so removing that id empties it). The dirty flag is always raised to mirror the historical behaviour.
	 */
	public void removeRecord(@Nonnull Serializable value, int... recordId) {
		Assert.isTrue(!ArrayUtils.isEmpty(recordId), "Record ids must be not null and non-empty!");
		this.dirty.setToTrue();
		final Comparable normalizedValue = (Comparable) this.normalizer.apply(value);
		final ValueToRecord bucket = searchBucket(normalizedValue);
		if (bucket == null) {
			return;
		}
		if (bucket instanceof final ValueToRecordBitmap bitmapBucket) {
			bitmapBucket.removeRecord(recordId);
			if (bitmapBucket.isEmpty()) {
				deleteBucket(normalizedValue);
			}
		} else {
			final ValueToRecordPrimitive primitiveBucket = (ValueToRecordPrimitive) bucket;
			// the immutable primitive holds exactly one id; removing it empties (and so deletes) the bucket
			for (final int id : recordId) {
				if (id == primitiveBucket.getRecordId()) {
					deleteBucket(normalizedValue);
					return;
				}
			}
			// none of the ids matched the sole record - silent no-op
		}
	}

	/**
	 * Method returns ture if histogram contains no records (i.e. no, or empty buckets).
	 */
	public boolean isEmpty() {
		final Iterator<ValueToRecord> it = bucketIterator();
		while (it.hasNext()) {
			final ValueToRecord bucket = it.next();
			if (!bucket.isEmpty()) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Returns true if there is a bucket related to passed `value`.
	 */
	public boolean contains(@Nullable Serializable value) {
		if (value == null) {
			return false;
		}
		return searchBucket((Comparable) this.normalizer.apply(value)) != null;
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
		if (normalizedValue == null) {
			return EmptyBitmap.INSTANCE;
		}
		final ValueToRecord bucket = searchBucket((Comparable) normalizedValue);
		return bucket == null ? EmptyBitmap.INSTANCE : bucket.getRecordIds();
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
		final List<ValueToRecordBitmap> result = new ArrayList<>(this.buckets.size());
		final Iterator<ValueToRecord> it = bucketIterator();
		while (it.hasNext()) {
			result.add(ValueToRecordBitmap.materialize(it.next()));
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
		return bucketIteratorFrom((Comparable) normalizedValue);
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
	public InvertedIndexSubSet getSortedRecordsExclusive(@Nullable Serializable moreThan, @Nullable Serializable lessThan) {
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
		final Iterator<ValueToRecord> it = bucketIterator();
		while (it.hasNext()) {
			final ValueToRecord bucket = it.next();
			if (valuePredicate.test(bucket.getValue()) && !bucket.isEmpty()) {
				result.add(bucket);
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
		final Iterator<ValueToRecord> it = bucketIterator();
		final CompositeObjectArray<S> result = new CompositeObjectArray<>(type);
		while (it.hasNext()) {
			final ValueToRecord bucket = it.next();
			if (bucket.getRecordIds().contains(recordId)) {
				//noinspection unchecked
				result.add((S) bucket.getValue());
			}
		}
		return result.toArray();
	}

	/**
	 * Returns count of the buckets in the histogram.
	 */
	public int getBucketCount() {
		return this.buckets.size();
	}

	/**
	 * Returns count of all record ids in the histogram.
	 */
	public int getLength() {
		int count = 0;
		final Iterator<ValueToRecord> it = bucketIterator();
		while (it.hasNext()) {
			count += it.next().size();
		}
		return count;
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder(256);
		sb.append("InvertedIndex{points=[");
		final Iterator<ValueToRecord> it = bucketIterator();
		boolean first = true;
		while (it.hasNext()) {
			if (!first) {
				sb.append(", ");
			}
			sb.append(it.next());
			first = false;
		}
		sb.append("]}");
		return sb.toString();
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
			@SuppressWarnings("unchecked") final TransactionalObjectBPlusTree committedTree =
				(TransactionalObjectBPlusTree) transactionalLayer.getStateCopyWithCommittedChanges(this.buckets);
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
		final Iterator<ValueToRecord> thisIt = bucketIterator();
		final Iterator<ValueToRecord> thatIt = that.bucketIterator();
		while (thisIt.hasNext() && thatIt.hasNext()) {
			final ValueToRecord thisBucket = thisIt.next();
			final ValueToRecord thatBucket = thatIt.next();
			if (!thisBucket.getValue().equals(thatBucket.getValue())
				|| !thisBucket.recordSetEquals(thatBucket)) {
				return false;
			}
		}
		return thisIt.hasNext() == thatIt.hasNext();
	}

	/**
	 * Content-based hash code over the logical bucket sequence `(value, recordIds)`, representation-independent and
	 * consistent with {@link #equals(Object)}.
	 */
	@Override
	public int hashCode() {
		int result = 1;
		final Iterator<ValueToRecord> it = bucketIterator();
		while (it.hasNext()) {
			final ValueToRecord bucket = it.next();
			result = 31 * result + bucket.getValue().hashCode();
			result = 31 * result + bucket.recordSetHashCode();
		}
		return result;
	}

	/**
	 * Test-support: returns true when the bucket for the given `value` is stored in the compact single-record
	 * {@link ValueToRecordPrimitive} form (as opposed to the {@link ValueToRecordBitmap} form). Package-private on
	 * purpose - it exposes the internal representation only to the inverted-index test suite.
	 */
	boolean isPrimitiveBucket(@Nonnull Serializable value) {
		return searchBucket((Comparable) this.normalizer.apply(value)) instanceof ValueToRecordPrimitive;
	}

	/*
		PRIVATE METHODS
	 */

	/**
	 * Returns the form in which a freshly inserted (deserialized) bucket should be stored: a single-record bucket is
	 * normalized to the compact {@link ValueToRecordPrimitive}, anything else keeps its {@link ValueToRecordBitmap}
	 * form. This is a one-time build-time normalization (not the runtime churn demotion that is out of scope), so it
	 * carries no 1↔2 oscillation risk.
	 */
	@Nonnull
	private static ValueToRecord toStoredForm(@Nonnull ValueToRecordBitmap bucket) {
		final Bitmap recordIds = bucket.getRecordIds();
		if (recordIds.size() == 1) {
			return new ValueToRecordPrimitive(bucket.getValue(), recordIds.getFirst());
		}
		return bucket;
	}

	/**
	 * Materializes the transactional view of all buckets into a positionally addressable array, ordered by the
	 * comparator. This is the same O(N) scan the array-backed implementation performed; used by consistency checks.
	 */
	@Nonnull
	private ValueToRecord[] materializeBuckets() {
		final List<ValueToRecord> result = new ArrayList<>(this.buckets.size());
		final Iterator<ValueToRecord> it = bucketIterator();
		while (it.hasNext()) {
			result.add(it.next());
		}
		return result.toArray(ValueToRecord[]::new);
	}

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
	 *   `moreThanEq`; for the exclusive mode the bucket exactly equal to the bound is skipped,
	 * - upper bound: the iteration stops (early break) once a bucket value passes `lessThanEq` - strictly greater for the
	 *   inclusive mode, greater than or equal for the exclusive mode.
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
		// anchor the forward iteration at the first bucket >= lower bound (or at the very start when unbounded)
		final Iterator<ValueToRecord> it = normalizedMoreThanEq == null
			? bucketIterator()
			: bucketIteratorFrom((Comparable) normalizedMoreThanEq);
		while (it.hasNext()) {
			final ValueToRecord bucket = it.next();
			final Serializable value = bucket.getValue();
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
			result.add(bucket);
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
