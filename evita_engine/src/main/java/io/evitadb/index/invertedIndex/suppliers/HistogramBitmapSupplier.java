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

package io.evitadb.index.invertedIndex.suppliers;

import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.identity.FormulaKey;
import io.evitadb.core.query.algebra.identity.IdentityExempt;
import io.evitadb.core.query.algebra.identity.IdentityRecord;
import io.evitadb.core.query.algebra.identity.KeyAccumulator;
import io.evitadb.core.query.algebra.identity.TokenList;
import io.evitadb.core.query.algebra.identity.ValueList;
import io.evitadb.core.query.algebra.deferred.BitmapSupplier;
import io.evitadb.core.query.algebra.deferred.DeferredFormula;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * Implementation of {@link BitmapSupplier} that provides access to the data stored in {@link InvertedIndex}
 * in a lazy fashion. The expensive computations happen in {@link #get()} method. This class is meant to be used in
 * combination with {@link DeferredFormula}.
 *
 * The detached key contains ordered bucket values and the version sequence of the leaves read by the slice.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class HistogramBitmapSupplier implements BitmapSupplier {
	private static final long CLASS_ID = 516692463222738021L;
	/** Detached key, built once on first demand. */
	@IdentityExempt(IdentityExempt.Reason.STATE)
	@Nullable private FormulaKey memoizedKey;

	/**
	 * Ordered bucket slice this supplier serves: the bucket *values* form the cache lookup key and the bucket record
	 * sets are unioned by {@link #get()}.
	 */
	@IdentityExempt(IdentityExempt.Reason.DECOMPOSED)
	private final ValueToRecord[] histogramBuckets;
	/**
	 * Contains memoized result once {@link #get()} is invoked for the first time. Additional calls of
	 * {@link #get()} will return this memoized result without paying the computational costs
	 */
	@IdentityExempt(IdentityExempt.Reason.STATE)
	protected Bitmap memoizedResult;
	/**
	 * Contains memoized value of {@link #getEstimatedCost()}  of this formula.
	 */
	@IdentityExempt(IdentityExempt.Reason.STATE)
	private final Long estimatedCost;
	/**
	 * Contains memoized value of {@link #getCost()}  of this formula.
	 */
	@IdentityExempt(IdentityExempt.Reason.STATE)
	private final Long cost;
	/**
	 * Contains memoized value of {@link #getEstimatedCardinality()} of this formula.
	 */
	@IdentityExempt(IdentityExempt.Reason.STATE)
	private final Integer estimatedCardinality;
	/**
	 * Version sequence of the leaf pages read by this slice.
	 */
	@IdentityExempt(IdentityExempt.Reason.DECOMPOSED)
	private final long[] transactionalIds;

	/** Returns the memoized detached computation key. */
	@Nonnull
	@Override
	public FormulaKey getKey() {
		if (this.memoizedKey == null) {
			this.memoizedKey = FormulaKey.of(identity(), getClassId(), FormulaKey.NO_CHILDREN, this);
		}
		return this.memoizedKey;
	}

	/** Returns this holder's stable class identifier. */
	public long getClassId() {
		return CLASS_ID;
	}

	/** Copies ordered bucket values and the leaf-version sequence into detached lists. */
	@Nonnull
	protected IdentityRecord identity() {
		final Object[] values = new Object[this.histogramBuckets.length];
		for (int i = 0; i < values.length; i++) {
			values[i] = this.histogramBuckets[i].getValue();
		}
		return new Identity(new ValueList(values), TokenList.ofProducerIds(this.transactionalIds, false));
	}

	/**
	 * Convenience constructor for a single whole-index staleness id — delegates to the leaf-granular
	 * {@link #HistogramBitmapSupplier(long[], ValueToRecord[])} with the one-element set `{indexTransactionId}`.
	 *
	 * @param indexTransactionId the owning {@link InvertedIndex}'s field-level transactional id, used as the sole
	 *                           staleness version of this histogram range
	 * @param histogramBuckets   the ordered bucket slice whose values form the lookup key and whose record sets
	 *                           {@link #get()} unions
	 */
	public HistogramBitmapSupplier(long indexTransactionId, @Nonnull ValueToRecord[] histogramBuckets) {
		this(new long[] {indexTransactionId}, histogramBuckets);
	}

	/**
	 * @param indexTransactionIds the canonical (sorted, deduplicated) set of version ids of the leaf pages this
	 *                            histogram slice crossed — so a cached range survives writes to other pages — or the
	 *                            single whole-index id when the slice spans too many leaves. This is the staleness
	 *                            version of the cached histogram range.
	 * @param histogramBuckets    the ordered bucket slice whose values form the lookup key and whose record sets
	 *                            {@link #get()} unions
	 */
	public HistogramBitmapSupplier(@Nonnull long[] indexTransactionIds, @Nonnull ValueToRecord[] histogramBuckets) {
		this.histogramBuckets = histogramBuckets;
		int cardinality = 0;
		for (final ValueToRecord bucket : histogramBuckets) {
			cardinality += bucket.size();
		}
		this.estimatedCardinality = cardinality;
		this.estimatedCost = this.estimatedCardinality * getOperationCost();
		// the cost is derived from the bucket sizes alone, which are exact and allocation-free, so it is known here
		// without unioning anything
		this.cost = this.estimatedCost;
		// STALENESS version: the set of leaf-page version ids the slice crossed. A commit that mutates a crossed page
		// mints a fresh id for that page, invalidating exactly the cached ranges that read it; ranges over untouched
		// pages stay valid. The set is capped to the single whole-index id for slices spanning too many leaves.
		this.transactionalIds = indexTransactionIds;
	}

	@Override
	public void initialize(@Nonnull QueryExecutionContext executionContext) {
		// do nothing
	}

	@Override
	public long getEstimatedCost() {
		Assert.isPremiseValid(this.estimatedCost != null, "The HistogramBitmapSupplier hasn't been initialized!");
		return this.estimatedCost;
	}

	@Override
	public long getCost() {
		Assert.isPremiseValid(this.cost != null, "The HistogramBitmapSupplier hasn't been initialized!");
		return this.cost;
	}

	@Override
	public long getOperationCost() {
		return 242;
	}

	@Override
	public int getEstimatedCardinality() {
		Assert.isPremiseValid(this.estimatedCardinality != null, "The HistogramBitmapSupplier hasn't been initialized!");
		return this.estimatedCardinality;
	}

	@Override
	public Bitmap get() {
		if (this.memoizedResult == null) {
			final CompositeIntArray result = new CompositeIntArray();
			Arrays.stream(this.histogramBuckets)
				.map(ValueToRecord::getRecordIds)
				.map(Bitmap::getArray)
				.forEach(it -> result.addAll(it, 0, it.length));
			this.memoizedResult = new ArrayBitmap(result);
		}
		return this.memoizedResult;
	}

	/**
	 * Detached computation inputs.
	 *
	 * @param histogramBuckets bucket values in index order
	 * @param transactionalIds leaf versions or the whole-index version in supplied order
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	public record Identity(
		@Nonnull ValueList histogramBuckets,
		@Nonnull TokenList transactionalIds
	) implements IdentityRecord {
		/** Returns the owning holder's identifier. */
		@Override
		public long classId() {
			return CLASS_ID;
		}

		/** Includes every component in declaration order. */
		@Override
		public void describeTo(@Nonnull KeyAccumulator accumulator) {
			accumulator.add(this.histogramBuckets);
			accumulator.add(this.transactionalIds);
		}
	}
}
