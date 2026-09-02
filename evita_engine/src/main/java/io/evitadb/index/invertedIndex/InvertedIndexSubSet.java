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
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.index.IndexHeapSize;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.VMLayout;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.function.BiFunction;
import java.util.function.ToLongFunction;

/**
 * Represents the immutable, query-time result of slicing an {@link InvertedIndex}: a contiguous run of
 * {@link ValueToRecord} buckets selected by a range or predicate lookup, together with the strategy that folds those
 * buckets into a single record-id {@link Formula}.
 *
 * Lookup methods on {@link InvertedIndex} (range, exclusive, predicate matching, sorted/unsorted) hand the matching
 * buckets to this class instead of materializing record ids eagerly. Consumers then either inspect the slice
 * statistically (min/max value, emptiness) or ask for the aggregated record ids - subsets covering different value
 * ranges of the same index can be combined downstream because they all carry the transactional identity of the leaf
 * pages they were sliced from.
 *
 * The aggregation is lazy and memoized: the {@link Formula} (and therefore the computed record ids) is built on first
 * access via {@link #getFormula()} and reused on every subsequent call, so an instance is intended to be short-lived
 * and consumed within a single query evaluation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class InvertedIndexSubSet {
	/**
	 * Transactional identity of the slice at the time it was taken, propagated into the aggregated {@link Formula} as
	 * its transactional id set so that formula-level caching can detect staleness across index mutations. This is the
	 * (canonical, sorted, deduplicated) set of version ids of the leaf pages the slice actually crossed — so a cached
	 * read over an untouched value range survives writes to other pages — capped to the single whole-index id when the
	 * slice spans more than {@link io.evitadb.core.query.response.TransactionalDataRelatedStructure#EXCESSIVE_HIGH_CARDINALITY}
	 * leaves (bounding the footprint). The aggregation lambda consumes it directly (the formula layer already keys on a
	 * `long[]`).
	 */
	private final long[] indexTransactionIds;
	/**
	 * The selected slice of buckets in their polymorphic form, ordered by ascending {@link ValueToRecord#getValue()}
	 * with no duplicate or gap relative to the source index. Each element may be either the multi-record
	 * {@link ValueToRecordBitmap} or the compact single-record {@link ValueToRecordPrimitive}; see
	 * {@link #getBuckets()} for the read-out.
	 */
	private final ValueToRecord[] histogramBuckets;
	/**
	 * Strategy that folds {@link #histogramBuckets} into one record-id {@link Formula}, parameterized by the
	 * {@link #indexTransactionIds}. The supplied implementation dictates the record ordering of the result (e.g. laid
	 * out bucket-by-bucket versus natural ascending order).
	 */
	private final BiFunction<long[], ValueToRecord[], Formula> aggregationLambda;
	/**
	 * Lazily computed and cached output of {@link #aggregationLambda}; `null` until the first {@link #getFormula()}
	 * call, then reused for the lifetime of this subset.
	 */
	private Formula memoizedResult;

	/**
	 * Returns the buckets of this subset in their polymorphic {@link ValueToRecord} form - each element is either a
	 * multi-record {@link ValueToRecordBitmap} or a compact single-record {@link ValueToRecordPrimitive}, exactly as
	 * held by the source index. No materialization happens: a single-record bucket stays a bare-`int` primitive and
	 * only allocates its lightweight {@link io.evitadb.index.bitmap.SingleRecordBitmap} view on demand if a consumer
	 * actually reads {@link ValueToRecord#getRecordIds()}. Consumers read this slice through the read-only
	 * {@link ValueToRecord} surface ({@link ValueToRecord#getValue()}, {@link ValueToRecord#getRecordIds()},
	 * {@link ValueToRecord#size()}); they key their own staleness on the leaf-page transactional ids the slice crossed,
	 * not on per-bucket bitmap ids.
	 *
	 * The returned array is the subset's internal, ascending-by-value backing array - it must be treated as
	 * read-only (never reordered or mutated), which is safe because this subset is a short-lived, single-query value.
	 */
	@Nonnull
	public ValueToRecord[] getBuckets() {
		return this.histogramBuckets;
	}

	/**
	 * Returns the heap this subset occupies, in bytes — its own object, the two arrays it carries and whatever
	 * `bucketSizer` decides its buckets are worth.
	 *
	 * The buckets are the caller's to price, because a subset is used two ways and only its holder can tell them
	 * apart. A slice taken off an {@link InvertedIndex} carries that index's **own** bucket flyweights, and pricing
	 * them here would charge the same buckets twice for an index that has answered one query; the range histogram of
	 * {@link io.evitadb.index.attribute.FilterIndex}, on the other hand, materializes a fresh
	 * {@link ValueToRecordBitmap} with a cloned active-set bitmap per range point, and nothing else in the catalog
	 * holds those. Return `0` for buckets this subset merely slices.
	 *
	 * The memoized {@link Formula} is charged as scaffolding through
	 * {@link io.evitadb.index.IndexHeapSize#memoizedFormulaSizeInBytes} plus the bitmap array the aggregation hands
	 * it, which is one reference per bucket. The bitmaps in that array are the buckets' own and are already priced by
	 * `bucketSizer` — or belong to the index this subset was sliced from.
	 *
	 * @param bucketSizer prices one bucket, or returns `0` when this subset does not own it
	 * @return the owned heap footprint in bytes, including alignment padding
	 */
	public long getHeapSizeInBytes(@Nonnull ToLongFunction<ValueToRecord> bucketSizer) {
		final VMLayout layout = VMLayout.current();
		// the indexTransactionIds / histogramBuckets / aggregationLambda / memoizedResult slots; the lambda is a
		// constant of the class that supplied it and contributes its slot alone
		long size = layout.sizeOfObject(4L * layout.referenceSize())
			+ layout.sizeOfArray(this.indexTransactionIds.length, Long.BYTES)
			+ layout.sizeOfArray(this.histogramBuckets.length, layout.referenceSize());
		for (final ValueToRecord bucket : this.histogramBuckets) {
			size += bucketSizer.applyAsLong(bucket);
		}
		if (this.memoizedResult != null) {
			size += IndexHeapSize.memoizedFormulaSizeInBytes(this.memoizedResult)
				// the aggregation folds one bitmap reference per bucket into the formula it builds
				+ layout.sizeOfArray(this.histogramBuckets.length, layout.referenceSize());
		}
		return size;
	}

	/**
	 * Returns record ids of all buckets in this histogram subset as single bitmap (ordered distinct array).
	 * For aggregation of record ids of different buckets {@link #aggregationLambda} is used. Result of this call
	 * is memoized so that additional calls are cheap and returns already computed result.
	 */
	public Bitmap getRecordIds() {
		return getFormula().compute();
	}

	/**
	 * Returns formula for computing record ids of all buckets in this histogram subset as single bitmap (ordered
	 * distinct array). For aggregation of record ids of different buckets {@link #aggregationLambda} is used.
	 * Result of this call is memoized so that additional calls are cheap and returns already computed result.
	 */
	public Formula getFormula() {
		if (this.memoizedResult == null) {
			this.memoizedResult = this.histogramBuckets.length == 0 ?
				EmptyFormula.INSTANCE : this.aggregationLambda.apply(this.indexTransactionIds, this.histogramBuckets);
		}
		return this.memoizedResult;
	}

	/**
	 * Returns true if this histogram subset contains no buckets / no record ids.
	 */
	public boolean isEmpty() {
		return ArrayUtils.isEmpty(this.histogramBuckets);
	}

	/**
	 * Returns minimal {@link ValueToRecordBitmap#getValue()} of buckets in this histogram subset.
	 */
	@Nullable
	public Serializable getMinimalValue() {
		return isEmpty() ? null : this.histogramBuckets[0].getValue();
	}

	/**
	 * Returns maximal {@link ValueToRecordBitmap#getValue()} of buckets in this histogram subset.
	 */
	@Nullable
	public Serializable getMaximalValue() {
		return isEmpty() ? null : this.histogramBuckets[this.histogramBuckets.length - 1].getValue();
	}
}
