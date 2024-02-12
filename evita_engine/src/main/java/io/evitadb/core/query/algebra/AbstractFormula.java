/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.query.algebra;

import io.evitadb.core.cache.CacheSupervisor;
import io.evitadb.core.query.algebra.utils.visitor.PrettyPrintingFormulaVisitor;
import io.evitadb.index.bitmap.Bitmap;
import lombok.Getter;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.stream.LongStream;
import java.util.stream.Stream;

/**
 * This abstract {@link Formula} implementation contains shared logic for all formulas. All formulas are strongly advised
 * to inherit from this superclass.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public abstract class AbstractFormula implements Formula {
	/**
	 * Contains array of inner formulas.
	 */
	@Getter protected final Formula[] innerFormulas;
	/**
	 * Contains memoized result once {@link #computeInternal()} is invoked for the first time. Additional calls of
	 * {@link #compute()} will return this memoized result without paying the computational costs
	 */
	protected Bitmap memoizedResult;
	/**
	 * Contains memoized value of {@link #getEstimatedCostInternal(CalculationContext)}  of this formula.
	 */
	private Long estimatedCost;
	/**
	 * Contains memoized value of {@link #getCostInternal(CalculationContext)}  of this formula.
	 */
	private Long cost;
	/**
	 * Contains memoized value of {@link #getCostToPerformanceInternal(CalculationContext)}  of this formula.
	 */
	private Long costToPerformance;
	/**
	 * Contains memoized value of {@link #computeHash(LongHashFunction)} method.
	 */
	private Long memoizedHash;
	/**
	 * Contains memoized value of {@link #gatherTransactionalIds()} method.
	 */
	private long[] memoizedTransactionalIds;
	/**
	 * Contains memoized value of {@link #gatherTransactionalIds()} computed hash.
	 */
	private Long memoizedTransactionalIdHash;

	protected AbstractFormula(Formula... innerFormulas) {
		this.innerFormulas = innerFormulas;
	}

	@Override
	public final long computeHash(@Nonnull LongHashFunction hashFunction) {
		if (this.memoizedHash == null) {
			final LongStream formulaHashStream = Arrays.stream(innerFormulas).mapToLong(it -> it.computeHash(hashFunction));
			this.memoizedHash = hashFunction.hashLongs(
				Stream.of(
						LongStream.of(getClassId()),
						isFormulaOrderSignificant() ? formulaHashStream : formulaHashStream.sorted(),
						LongStream.of(includeAdditionalHash(hashFunction))
					)
					.flatMapToLong(it -> it)
					.toArray()
			);
		}
		return this.memoizedHash;
	}

	/**
	 * Method signalizes whether the {@link #innerFormulas} order is significant in this formula. Usually the order
	 * is not significant, and we want to order the hashes by their value to avoid different hashes for formula
	 * combinations like: A AND B, B AND A which produce same result, but would have different hashes if we dont reorder
	 * the inner formula hashes. On the contrary the formula A NOT B cannot be reordered to B NOT A, because these
	 * expressions produce different output.
	 */
	protected boolean isFormulaOrderSignificant() {
		return false;
	}

	@Override
	public long computeTransactionalIdHash(@Nonnull LongHashFunction hashFunction) {
		if (this.memoizedTransactionalIdHash == null) {
			this.memoizedTransactionalIdHash = hashFunction.hashLongs(
				Arrays.stream(gatherTransactionalIds())
					.distinct()
					.sorted()
					.toArray()
			);
		}
		return this.memoizedTransactionalIdHash;
	}

	@Nonnull
	@Override
	public final long[] gatherTransactionalIds() {
		if (this.memoizedTransactionalIds == null) {
			this.memoizedTransactionalIds = gatherBitmapIdsInternal();
		}
		return this.memoizedTransactionalIds;
	}

	@Override
	public final long getEstimatedCost(@Nonnull CalculationContext context) {
		if (this.estimatedCost == null) {
			if (context.visit(CalculationType.ESTIMATED_COST, this)) {
				this.estimatedCost = getEstimatedCostInternal(context);
			} else {
				this.estimatedCost = 0L;
			}
		}
		return this.estimatedCost;
	}

	@Override
	public final long getCost(@Nonnull CalculationContext context) {
		if (this.cost == null) {
			if (context.visit(CalculationType.COST, this)) {
				this.cost = getCostInternal(context);
			} else {
				this.cost = 0L;
			}
		}
		return this.cost;
	}

	@Override
	public final long getCostToPerformanceRatio(@Nonnull CalculationContext context) {
		if (this.costToPerformance == null) {
			this.costToPerformance = getCostToPerformanceInternal(context);
		}
		return this.costToPerformance;
	}

	@Override
	public void accept(@Nonnull FormulaVisitor visitor) {
		visitor.visit(this);
	}

	@Nonnull
	protected long[] gatherBitmapIdsInternal() {
		return Arrays.stream(innerFormulas)
			.flatMapToLong(it -> LongStream.of(it.gatherTransactionalIds()))
			.toArray();
	}

	/**
	 * Estimated cost of the operation based on formula structure without paying the price for real computation
	 * of the results. That's why the result number is only rough estimate. Default implementation is sum of bitmap
	 * sizes of referenced bitmaps multiplied by known {@link #getOperationCost()} of this operation.
	 * This method doesn't trigger formula computation.
	 */
	protected long getEstimatedCostInternal(@Nonnull CalculationContext context) {
		try {
			long costs = getEstimatedBaseCost();
			for (Formula innerFormula : innerFormulas) {
				costs = Math.addExact(costs, innerFormula.getEstimatedCost(context));
			}
			return getEstimatedBaseCost() + getOperationCost() * getEstimatedCardinality() + costs;
		} catch (ArithmeticException ex) {
			return Long.MAX_VALUE;
		}
	}

	/**
	 * Returns estimated computation complexity cost for computation that covers all additional internal data that
	 * affect the output of {@link #compute()} method and are not part {@link #getInnerFormulas()}.
	 * The {@link #getInnerFormulas()} are implicitly part of the hash and should not be covered by this method.
	 */
	protected long getEstimatedBaseCost() {
		return 0L;
	}

	/**
	 * Returns a long hash, that should be computed by {@link CacheSupervisor#createHashFunction()} and covers all
	 * additional internal data that affect the output of {@link #compute()} method and are not part
	 * {@link #getInnerFormulas()}. The {@link #getInnerFormulas()} are implicitly part of the hash and should not be
	 * covered by this method.
	 */
	protected abstract long includeAdditionalHash(@Nonnull LongHashFunction hashFunction);

	/**
	 * Returns a long constant, that uniquely distinguishes this class from the others. The number must not change in
	 * time for the same class. The number must not be inherited from the superclasses and must be implemented and return
	 * different numbers for each "leaf class". This number is important part of {@link #computeHash(LongHashFunction)} method.
	 */
	protected abstract long getClassId();

	/**
	 * Cost of the operation based on computation result. Default implementation is sum of bitmap sizes of referenced
	 * bitmaps multiplied by known {@link #getOperationCost()} of this operation. This method triggers formula computation.
	 */
	protected long getCostInternal(@Nonnull CalculationContext context) {
		return Arrays.stream(innerFormulas).mapToLong(it -> it.getCost(context)).sum() +
			Arrays.stream(innerFormulas)
				.map(Formula::compute)
				.mapToLong(Bitmap::size)
				.sum() * getOperationCost();
	}

	@Override
	@Nonnull
	public Bitmap compute() {
		if (this.memoizedResult == null) {
			this.memoizedResult = computeInternal();
		}
		return this.memoizedResult;
	}

	/**
	 * Clears the memoized results and hashes of the formula.
	 */
	public void clearMemoizedResult() {
		this.memoizedResult = null;
		this.memoizedHash = null;
		this.memoizedTransactionalIds = null;
		this.memoizedTransactionalIdHash = null;
		this.cost = null;
		this.estimatedCost = null;
		this.costToPerformance = null;
	}

	/**
	 * Returns cost to performance ratio. Default implementation is sums cost to performance ratio of all inner formulas
	 * and adds ratio of this operation that is computed as ration of its cost to output bitmap size. I.e. when large
	 * bitmap is greatly reduced to a small one, this ratio gets bigger and thus caching output of this formula saves
	 * more resources than caching outputs of formulas with lesser ratio.
	 */
	protected long getCostToPerformanceInternal(@Nonnull CalculationContext context) {
		return Arrays.stream(innerFormulas)
			.mapToLong(it -> it.getCostToPerformanceRatio(context))
			.sum() + (getCost(context) / Math.max(1, compute().size()));
	}

	/**
	 * Internal (not cached) computation operation of this formula.
	 */
	@Nonnull
	protected abstract Bitmap computeInternal();

	@Nonnull
	@Override
	public String prettyPrint() {
		return PrettyPrintingFormulaVisitor.toString(this);
	}

}
