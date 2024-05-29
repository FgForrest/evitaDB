/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

import io.evitadb.core.query.algebra.deferred.BitmapSupplier;
import io.evitadb.core.query.algebra.deferred.DeferredFormula;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.stream.LongStream;
import java.util.stream.Stream;

/**
 * Implementation of {@link BitmapSupplier} that provides access to the data stored in {@link InvertedIndex}
 * in a lazy fashion. The expensive computations happen in {@link #get()} method. This class is meant to be used in
 * combination with {@link DeferredFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class HistogramBitmapSupplier<T extends Comparable<T>> implements BitmapSupplier {
	private static final long CLASS_ID = 516692463222738021L;
	private final ValueToRecordBitmap<T>[] histogramBuckets;
	/**
	 * Contains memoized result once {@link #get()} is invoked for the first time. Additional calls of
	 * {@link #get()} will return this memoized result without paying the computational costs
	 */
	protected Bitmap memoizedResult;
	/**
	 * Contains memoized value of {@link #getEstimatedCost()}  of this formula.
	 */
	private Long estimatedCost;
	/**
	 * Contains memoized value of {@link #getCost()}  of this formula.
	 */
	private Long cost;
	/**
	 * Contains memoized value of {@link #getCostToPerformanceRatio()} of this formula.
	 */
	private Long costToPerformance;
	/**
	 * Contains memoized value of {@link #getEstimatedCardinality()} of this formula.
	 */
	private Integer estimatedCardinality;
	/**
	 * Contains memoized value of {@link #getHash()} method.
	 */
	private Long hash;
	/**
	 * Contains memoized value of {@link #gatherTransactionalIds()} method.
	 */
	private long[] transactionalIds;
	/**
	 * Contains memoized value of {@link #gatherTransactionalIds()} computed hash.
	 */
	private Long transactionalIdHash;

	@Override
	public void initialize(@Nonnull CalculationContext calculationContext) {
		if (this.hash == null) {
			this.hash = calculationContext.getHashFunction().hashLongs(
				Stream.of(
						LongStream.of(CLASS_ID),
						Arrays.stream(histogramBuckets).mapToLong(it -> it.getRecordIds().getId()).sorted()
					)
					.flatMapToLong(it -> it)
					.toArray()
			);
		}
		if (this.estimatedCardinality == null) {
			this.estimatedCardinality = Arrays.stream(histogramBuckets)
				.mapToInt(it -> it.getRecordIds().size())
				.sum();
			if (calculationContext.visit(CalculationType.ESTIMATED_COST, this)) {
				this.estimatedCost = this.estimatedCardinality * getOperationCost();
			} else {
				this.estimatedCost = 0L;
			}
		}
		if (this.cost == null) {
			if (calculationContext.visit(CalculationType.COST, this)) {
				this.cost = this.estimatedCost;
			} else {
				this.cost = 0L;
			}
			this.costToPerformance = getCost() / (get().size() * getOperationCost());
		}
		if (this.transactionalIds == null) {
			this.transactionalIds = Arrays.stream(histogramBuckets)
				.mapToLong(it -> it.getRecordIds().getId())
				.toArray();
			this.transactionalIdHash = calculationContext.getHashFunction().hashLongs(
				Arrays.stream(this.transactionalIds)
					.distinct()
					.sorted()
					.toArray()
			);
		}
	}

	@Override
	public long getHash() {
		if (this.hash == null) {
			initialize(CalculationContext.NO_CACHING_INSTANCE);
		}
		return this.hash;
	}

	@Override
	public long getTransactionalIdHash() {
		if (this.transactionalIdHash == null) {
			initialize(CalculationContext.NO_CACHING_INSTANCE);
		}
		return this.transactionalIdHash;
	}

	@Nonnull
	@Override
	public long[] gatherTransactionalIds() {
		if (this.transactionalIds == null) {
			initialize(CalculationContext.NO_CACHING_INSTANCE);
		}
		return this.transactionalIds;
	}

	@Override
	public long getEstimatedCost() {
		if (this.estimatedCost == null) {
			initialize(CalculationContext.NO_CACHING_INSTANCE);
		}
		return this.estimatedCost;
	}

	@Override
	public long getCost() {
		if (this.cost == null) {
			initialize(CalculationContext.NO_CACHING_INSTANCE);
			Assert.isPremiseValid(this.cost != null, "Formula results haven't been computed!");
		}
		return this.cost;
	}

	@Override
	public long getOperationCost() {
		return 242;
	}

	@Override
	public long getCostToPerformanceRatio() {
		if (this.costToPerformance == null) {
			initialize(CalculationContext.NO_CACHING_INSTANCE);
			Assert.isPremiseValid(this.costToPerformance != null, "Formula results haven't been computed!");
		}
		return this.costToPerformance;
	}

	@Override
	public int getEstimatedCardinality() {
		if (this.estimatedCardinality == null) {
			initialize(CalculationContext.NO_CACHING_INSTANCE);
		}
		return this.estimatedCardinality;
	}

	@Override
	public Bitmap get() {
		if (this.memoizedResult == null) {
			final CompositeIntArray result = new CompositeIntArray();
			Arrays.stream(histogramBuckets)
				.map(ValueToRecordBitmap::getRecordIds)
				.map(Bitmap::getArray)
				.forEach(it -> result.addAll(it, 0, it.length));
			this.memoizedResult = new ArrayBitmap(result);
		}
		return this.memoizedResult;
	}
}
