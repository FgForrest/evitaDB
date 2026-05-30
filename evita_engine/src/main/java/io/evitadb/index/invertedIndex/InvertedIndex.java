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
import io.evitadb.exception.EvitaInternalError;
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
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Histogram index is based on <a href="https://en.wikipedia.org/wiki/Histogram">Histogram data structure</a>. It's
 * organized as a set of "buckets" ordered from minimal to maximal {@link Comparable} value. Each bucket has assigned
 * bitmap (ordered distinct set of primitive integer values) that are assigned to bucket {@link ValueToRecordBitmap#getValue()}.
 *
 * Search in histogram is possible via. binary search with O(log n) complexity due its sorted nature. Set of records
 * are easily available as the set assigned to that value. Range look-ups are also available as boolean OR of all bitmaps
 * from / to looked up value threshold.
 *
 * The buckets are stored in a {@link TransactionalObjectBPlusTree} keyed by the (normalized) bucket value and ordered
 * by the supplied {@link Comparator}. A write therefore touches only the affected leaf and its ancestors (path-copying)
 * instead of reallocating the whole structure - this is the key write-latency improvement targeted by issue #760.
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
	 * Wrapper that adapts a committed value coming out of the B+ tree commit into a {@link ValueToRecordBitmap}.
	 * A {@link ValueToRecordBitmap} merges to another {@link ValueToRecordBitmap} (its
	 * {@link ValueToRecordBitmap#createCopyWithMergedTransactionalMemory} returns a {@link ValueToRecordBitmap}),
	 * therefore an identity cast is sufficient here - unlike value types that merge to a different class.
	 */
	private static final Function<Object, ValueToRecordBitmap> VALUE_TO_RECORD_BITMAP_WRAPPER =
		o -> (ValueToRecordBitmap) o;

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
	 * This lambda lay out records by {@link ValueToRecordBitmap#getValue()} one after another.
	 */
	private static final BiFunction<Long, ValueToRecordBitmap[], Formula> UNSORTED_AGGREGATION_LAMBDA = (indexTransactionId, histogramBuckets) -> new DeferredFormula(
		new HistogramBitmapSupplier(histogramBuckets)
	);

	/**
	 * This lambda lay out records in natural ascending order.
	 */
	private static final BiFunction<Long, ValueToRecordBitmap[], Formula> SORTED_AGGREGATION_LAMBDA = (indexTransactionId, histogramBuckets) -> {
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
	 * Method verifies that {@link ValueToRecordBitmap#getValue()}s in passed set are monotonically increasing and contain
	 * no duplicities.
	 */
	@Nonnull
	private static ConsistencyReport checkConsistency(@Nonnull ValueToRecordBitmap[] points, @Nonnull Comparator comparator) {
		final StringBuilder report = new StringBuilder(256);
		Serializable previous = null;
		for (ValueToRecordBitmap bucket : points) {
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
			Comparable.class,
			ValueToRecordBitmap.class,
			VALUE_TO_RECORD_BITMAP_WRAPPER,
			comparator
		);
	}

	/**
	 * Inserts or updates the bucket stored under the passed (already-normalized) key. The unchecked key cast required by
	 * the genuinely raw {@link Comparable} key type is confined to this single, documented place; the value side stays
	 * statically `ValueToRecordBitmap`-typed so the updater lambda is checked.
	 */
	@SuppressWarnings("unchecked")
	private void upsertBucket(@Nonnull Comparable key, @Nonnull UnaryOperator<ValueToRecordBitmap> updater) {
		this.buckets.upsert(key, updater);
	}

	/**
	 * Returns the bucket stored under the passed (already-normalized) key, or `null` when absent. Confines the unchecked
	 * key cast in the same way as {@link #upsertBucket}.
	 */
	@Nullable
	@SuppressWarnings("unchecked")
	private ValueToRecordBitmap searchBucket(@Nonnull Comparable key) {
		return (ValueToRecordBitmap) this.buckets.search(key).orElse(null);
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
	private Iterator<ValueToRecordBitmap> bucketIterator() {
		return this.buckets.valueIterator();
	}

	/**
	 * Returns a transaction-aware iterator over the buckets whose value is greater than or equal to the passed
	 * (already-normalized) key.
	 */
	@Nonnull
	@SuppressWarnings("unchecked")
	private Iterator<ValueToRecordBitmap> bucketIteratorFrom(@Nonnull Comparable key) {
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
		// contract check
		final ConsistencyReport consistencyReport = checkConsistency(buckets, comparator);
		if (consistencyReport.state() != ConsistencySensitiveDataStructure.ConsistencyState.CONSISTENT) {
			throw new MonotonicRowCorruptedException(Objects.requireNonNull(consistencyReport.report()));
		}
		final TransactionalObjectBPlusTree tree = createEmptyTree(comparator);
		// rebuild the tree from the deserialized snapshot by inserting all buckets (values are unique & monotonic)
		for (final ValueToRecordBitmap bucket : buckets) {
			//noinspection unchecked
			tree.insert((Comparable) bucket.getValue(), bucket);
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
	 * created and first record id is assigned to it. The updater mutates and returns the SAME {@link ValueToRecordBitmap}
	 * instance (never swaps it) so the value's transactional diff layer is preserved.
	 */
	public void addRecord(@Nonnull Serializable value, int recordId) {
		final Serializable normalizedValue = this.normalizer.apply(value);
		upsertBucket(
			(Comparable) normalizedValue,
			bucket -> {
				if (bucket == null) {
					return new ValueToRecordBitmap(normalizedValue, recordId);
				}
				bucket.addRecord(recordId);
				return bucket;
			}
		);
		this.dirty.setToTrue();
	}

	/**
	 * Adds multiple records id into the bucket with specified `value`. If no bucket with this value exists, it is automatically
	 * created and first record ida are assigned to it. The updater mutates and returns the SAME {@link ValueToRecordBitmap}
	 * instance (never swaps it) so the value's transactional diff layer is preserved.
	 */
	public void addRecord(@Nonnull Serializable value, int... recordId) {
		Assert.isTrue(!ArrayUtils.isEmpty(recordId), "Record ids must be not null and non-empty!");
		final Serializable normalizedValue = this.normalizer.apply(value);
		upsertBucket(
			(Comparable) normalizedValue,
			bucket -> {
				if (bucket == null) {
					return new ValueToRecordBitmap(normalizedValue, recordId);
				}
				bucket.addRecord(recordId);
				return bucket;
			}
		);
		this.dirty.setToTrue();
	}

	/**
	 * Removes one or multiple record ids from the bucket with specified `value`. If no bucket with this value exists,
	 * nothing happens. If the bucket contains no record id that match passed record id, nothing happens. If removal
	 * of the record ids leaves the bucket empty, it's entirely removed (the tree releases the value's transactional
	 * layer). The bucket is mutated in place; the dirty flag is always raised to mirror the historical behaviour.
	 */
	public void removeRecord(@Nonnull Serializable value, int... recordId) {
		Assert.isTrue(!ArrayUtils.isEmpty(recordId), "Record ids must be not null and non-empty!");
		this.dirty.setToTrue();
		final Comparable normalizedValue = (Comparable) this.normalizer.apply(value);
		final ValueToRecordBitmap bucket = searchBucket(normalizedValue);
		if (bucket == null) {
			return;
		}
		bucket.removeRecord(recordId);
		if (bucket.isEmpty()) {
			deleteBucket(normalizedValue);
		}
	}

	/**
	 * Method returns ture if histogram contains no records (i.e. no, or empty buckets).
	 */
	public boolean isEmpty() {
		final Iterator<ValueToRecordBitmap> it = bucketIterator();
		while (it.hasNext()) {
			final ValueToRecordBitmap bucket = it.next();
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
		final ValueToRecordBitmap bucket = searchBucket((Comparable) normalizedValue);
		return bucket == null ? EmptyBitmap.INSTANCE : bucket.getRecordIds();
	}

	/**
	 * Returns array of "buckets" ordered by {@link ValueToRecordBitmap#getValue()} that contain record ids assigned in them.
	 */
	@Nonnull
	public ValueToRecordBitmap[] getValueToRecordBitmap() {
		//TODO JNO (#760): materializes the whole bucket array on every commit only to feed the
		// serialization route; remove once StorageParts become granular (issue #760 part B — persist
		// only the changed parts of the index instead of rewriting the entire index per transaction).
		return materializeBuckets();
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
	public Iterator<ValueToRecordBitmap> getValueIteratorFrom(@Nonnull Serializable normalizedValue) {
		return bucketIteratorFrom((Comparable) normalizedValue);
	}

	/**
	 * Returns entire content of this histogram as "subset" that allows easy access to the record ids inside.
	 * Records returned by this {@link InvertedIndexSubSet} are sorted by the order of the bucket
	 * {@link ValueToRecordBitmap#getValue()}.
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
	 * {@link ValueToRecordBitmap#getValue()}.
	 *
	 * @see #getRecords()
	 */
	public InvertedIndexSubSet getRecords(@Nullable Serializable moreThanEq, @Nullable Serializable lessThanEq) {
		final ValueToRecordBitmap[] records = getRecordsInternal(moreThanEq, lessThanEq, BoundsHandling.INCLUSIVE);
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
		final ValueToRecordBitmap[] records = getRecordsInternal(moreThanEq, lessThanEq, BoundsHandling.INCLUSIVE);
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
		final ValueToRecordBitmap[] records = getRecordsInternal(moreThan, lessThan, BoundsHandling.EXCLUSIVE);
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
		final List<ValueToRecordBitmap> result = new ArrayList<>(64);
		final Iterator<ValueToRecordBitmap> it = bucketIterator();
		while (it.hasNext()) {
			final ValueToRecordBitmap bucket = it.next();
			if (valuePredicate.test(bucket.getValue()) && !bucket.isEmpty()) {
				result.add(bucket);
			}
		}
		return convertToSortedResult(result.toArray(ValueToRecordBitmap[]::new));
	}

	/**
	 * Returns an array of values associated with the specified record ID.
	 *
	 * @param recordId the ID of the record
	 * @return an array of values associated with the record ID
	 */
	@Nonnull
	public <S extends Serializable> S[] getValuesForRecord(int recordId, @Nonnull Class<S> type) {
		final Iterator<ValueToRecordBitmap> it = bucketIterator();
		final CompositeObjectArray<S> result = new CompositeObjectArray<>(type);
		while (it.hasNext()) {
			final ValueToRecordBitmap bitmap = it.next();
			if (bitmap.getRecordIds().contains(recordId)) {
				//noinspection unchecked
				result.add((S) bitmap.getValue());
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
		final Iterator<ValueToRecordBitmap> it = bucketIterator();
		while (it.hasNext()) {
			count += it.next().getRecordIds().size();
		}
		return count;
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder(256);
		sb.append("InvertedIndex{points=[");
		final Iterator<ValueToRecordBitmap> it = bucketIterator();
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
			final TransactionalObjectBPlusTree committedTree =
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
	 * Content-based equality over the logical bucket sequence `(value, recordIds)`. The `dirty` flag and the
	 * `comparator` are intentionally excluded (mirroring the historical `@EqualsAndHashCode(exclude={"dirty","comparator"})`).
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
		final Iterator<ValueToRecordBitmap> thisIt = bucketIterator();
		final Iterator<ValueToRecordBitmap> thatIt = that.bucketIterator();
		while (thisIt.hasNext() && thatIt.hasNext()) {
			final ValueToRecordBitmap thisBucket = thisIt.next();
			final ValueToRecordBitmap thatBucket = thatIt.next();
			if (!thisBucket.getValue().equals(thatBucket.getValue())
				|| !thisBucket.getRecordIds().equals(thatBucket.getRecordIds())) {
				return false;
			}
		}
		return thisIt.hasNext() == thatIt.hasNext();
	}

	/**
	 * Content-based hash code over the logical bucket sequence `(value, recordIds)`.
	 */
	@Override
	public int hashCode() {
		int result = 1;
		final Iterator<ValueToRecordBitmap> it = bucketIterator();
		while (it.hasNext()) {
			final ValueToRecordBitmap bucket = it.next();
			result = 31 * result + bucket.getValue().hashCode();
			result = 31 * result + bucket.getRecordIds().hashCode();
		}
		return result;
	}

	/*
		PRIVATE METHODS
	 */

	/**
	 * Materializes the transactional view of all buckets into a positionally addressable array, ordered by the
	 * comparator. This is the same O(N) scan the array-backed implementation performed; used by consistency checks and
	 * by the commit-time serialization snapshot {@link #getValueToRecordBitmap()}.
	 */
	@Nonnull
	private ValueToRecordBitmap[] materializeBuckets() {
		final List<ValueToRecordBitmap> result = new ArrayList<>(this.buckets.size());
		final Iterator<ValueToRecordBitmap> it = bucketIterator();
		while (it.hasNext()) {
			result.add(it.next());
		}
		return result.toArray(ValueToRecordBitmap[]::new);
	}

	/**
	 * Returns subset that aggregates inner record ids by {@link ValueToRecordBitmap#getValue()} and thus the result may
	 * look unsorted on first look.
	 */
	@Nonnull
	private InvertedIndexSubSet convertToUnSortedResult(@Nonnull ValueToRecordBitmap[] records) {
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
	private InvertedIndexSubSet convertToSortedResult(@Nonnull ValueToRecordBitmap[] records) {
		return new InvertedIndexSubSet(
			getId(),
			records,
			SORTED_AGGREGATION_LAMBDA
		);
	}

	/**
	 * Searches histogram and select all buckets that fulfill the between `moreThanEq` and `lessThanEq` constraints.
	 * Returns array of all {@link ValueToRecordBitmap} in the range.
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
	private ValueToRecordBitmap[] getRecordsInternal(
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

		final List<ValueToRecordBitmap> result = new ArrayList<>(64);
		// anchor the forward iteration at the first bucket >= lower bound (or at the very start when unbounded)
		final Iterator<ValueToRecordBitmap> it = normalizedMoreThanEq == null
			? bucketIterator()
			: bucketIteratorFrom((Comparable) normalizedMoreThanEq);
		while (it.hasNext()) {
			final ValueToRecordBitmap bucket = it.next();
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
		return result.toArray(ValueToRecordBitmap[]::new);
	}

	/**
	 * Represents search mode - i.e. whether records at the very bounds should be included in result or not.
	 */
	private enum BoundsHandling {

		EXCLUSIVE, INCLUSIVE

	}

	/* TOBEDONE #538 - remove, the data should be correct by then */
	public static class MonotonicRowCorruptedException extends EvitaInternalError {
		@Serial private static final long serialVersionUID = -4632659049907667781L;

		public MonotonicRowCorruptedException(@Nonnull String message) {
			super(message);
		}
	}

}
