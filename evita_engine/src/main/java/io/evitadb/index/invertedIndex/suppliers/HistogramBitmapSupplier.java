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
import io.evitadb.core.query.algebra.deferred.BitmapSupplier;
import io.evitadb.core.query.algebra.deferred.DeferredFormula;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.utils.Assert;

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
public class HistogramBitmapSupplier implements BitmapSupplier {
	private static final long CLASS_ID = 516692463222738021L;
	private final ValueToRecordBitmap[] histogramBuckets;
	/**
	 * Contains memoized result once {@link #get()} is invoked for the first time. Additional calls of
	 * {@link #get()} will return this memoized result without paying the computational costs
	 */
	protected Bitmap memoizedResult;
	/**
	 * Contains memoized value of {@link #getEstimatedCost()}  of this formula.
	 */
	private final Long estimatedCost;
	/**
	 * Contains memoized value of {@link #getCost()}  of this formula.
	 */
	private final Long cost;
	/**
	 * Contains memoized value of {@link #getCostToPerformanceRatio()} of this formula.
	 */
	private final Long costToPerformance;
	/**
	 * Contains memoized value of {@link #getEstimatedCardinality()} of this formula.
	 */
	private final Integer estimatedCardinality;
	/**
	 * Contains memoized value of {@link #getHash()} method.
	 */
	private final Long hash;
	/**
	 * Contains memoized value of {@link #gatherTransactionalIds()} method.
	 */
	private final long[] transactionalIds;
	/**
	 * Contains memoized value of {@link #gatherTransactionalIds()} computed hash.
	 */
	private final Long transactionalIdHash;

	public HistogramBitmapSupplier(@Nonnull ValueToRecordBitmap[] histogramBuckets) {
		this.histogramBuckets = histogramBuckets;
		this.hash = HASH_FUNCTION.hashLongs(
			Stream.of(
					LongStream.of(CLASS_ID),
					Arrays.stream(histogramBuckets).mapToLong(it -> it.getRecordIds().getId()).sorted()
				)
				.flatMapToLong(it -> it)
				.toArray()
		);
		this.estimatedCardinality = Arrays.stream(histogramBuckets)
			.mapToInt(it -> it.getRecordIds().size())
			.sum();
		this.estimatedCost = this.estimatedCardinality * getOperationCost();
		this.cost = this.estimatedCost;
		this.costToPerformance = getCost() / (get().size() * getOperationCost());
		this.transactionalIds = Arrays.stream(histogramBuckets)
			.mapToLong(it -> it.getRecordIds().getId())
			.toArray();
		this.transactionalIdHash = HASH_FUNCTION.hashLongs(
			Arrays.stream(this.transactionalIds)
				.distinct()
				.sorted()
				.toArray()
		);
	}

	@Override
	public void initialize(@Nonnull QueryExecutionContext executionContext) {
		// do nothing
	}

	@Override
	public long getHash() {
		Assert.isPremiseValid(this.hash != null, "The HistogramBitmapSupplier hasn't been initialized!");
		return this.hash;
	}

	@Override
	public long getTransactionalIdHash() {
		Assert.isPremiseValid(this.transactionalIdHash != null, "The HistogramBitmapSupplier hasn't been initialized!");
		return this.transactionalIdHash;
	}

	@Nonnull
	@Override
	public long[] gatherTransactionalIds() {
		Assert.isPremiseValid(this.transactionalIds != null, "The HistogramBitmapSupplier hasn't been initialized!");
		return this.transactionalIds;
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
	public long getCostToPerformanceRatio() {
		Assert.isPremiseValid(this.costToPerformance != null, "The HistogramBitmapSupplier hasn't been initialized!");
		return this.costToPerformance;
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
				.map(ValueToRecordBitmap::getRecordIds)
				.map(Bitmap::getArray)
				.forEach(it -> result.addAll(it, 0, it.length));
			this.memoizedResult = new ArrayBitmap(result);
		}
		return this.memoizedResult;
	}
}
