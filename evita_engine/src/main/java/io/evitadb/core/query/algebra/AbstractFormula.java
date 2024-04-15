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
import io.evitadb.core.query.response.TransactionalDataRelatedStructure;
import io.evitadb.core.transaction.memory.TransactionalLayerCreator;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.Assert;
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

	protected AbstractFormula(Formula... innerFormulas) {
		this.innerFormulas = innerFormulas;
	}

	@Override
	public void initialize(@Nonnull CalculationContext calculationContext) {
		initializeInternal(calculationContext, false);
	}

	@Override
	public final long getHash() {
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
	public final long[] gatherTransactionalIds() {
		if (this.transactionalIds == null) {
			initialize(CalculationContext.NO_CACHING_INSTANCE);
		}
		return this.transactionalIds;
	}

	@Override
	public final long getEstimatedCost() {
		if (this.estimatedCost == null) {
			initialize(CalculationContext.NO_CACHING_INSTANCE);
		}
		return this.estimatedCost;
	}

	@Override
	public final long getCost() {
		if (this.cost == null) {
			initialize(CalculationContext.NO_CACHING_INSTANCE);
			Assert.isPremiseValid(this.cost != null, "Formula results haven't been computed!");
		}
		return this.cost;
	}

	@Override
	public final long getCostToPerformanceRatio() {
		if (this.costToPerformance == null) {
			initialize(CalculationContext.NO_CACHING_INSTANCE);
			Assert.isPremiseValid(this.costToPerformance != null, "Formula results haven't been computed!");
		}
		return this.costToPerformance;
	}

	@Override
	public void accept(@Nonnull FormulaVisitor visitor) {
		visitor.visit(this);
	}

	@Override
	@Nonnull
	public Bitmap compute() {
		if (this.memoizedResult == null) {
			this.memoizedResult = computeInternal();
		}
		return this.memoizedResult;
	}

	@Override
	public void initializeAgain(@Nonnull CalculationContext calculationContext) {
		initializeInternal(calculationContext, true);
	}

	@Override
	public void clearMemory() {
		this.memoizedResult = null;
		this.hash = null;
		this.transactionalIds = null;
		this.transactionalIdHash = null;
		this.cost = null;
		this.estimatedCost = null;
		this.costToPerformance = null;
	}

	@Nonnull
	@Override
	public String prettyPrint() {
		return PrettyPrintingFormulaVisitor.toString(this);
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

	/**
	 * Returns {@link TransactionalLayerCreator#getId()} of all bitmaps used by this formula. Should any of those ids
	 * become obsolete the formula is also obsolete. The returned array may contain duplicates and may not be sorted.
	 */
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
	protected long getEstimatedCostInternal() {
		try {
			long costs = getEstimatedBaseCost();
			for (Formula innerFormula : innerFormulas) {
				costs = Math.addExact(costs, innerFormula.getEstimatedCost());
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
	 * different numbers for each "leaf class". This number is important part of {@link #getHash()} method.
	 */
	protected abstract long getClassId();

	/**
	 * Cost of the operation based on computation result. Default implementation is sum of bitmap sizes of referenced
	 * bitmaps multiplied by known {@link #getOperationCost()} of this operation. This method triggers formula computation.
	 */
	protected long getCostInternal() {
		return Arrays.stream(innerFormulas).mapToLong(TransactionalDataRelatedStructure::getCost).sum() +
			Arrays.stream(innerFormulas)
				.map(Formula::compute)
				.mapToLong(Bitmap::size)
				.sum() * getOperationCost();
	}

	/**
	 * Returns cost to performance ratio. Default implementation is sums cost to performance ratio of all inner formulas
	 * and adds ratio of this operation that is computed as ration of its cost to output bitmap size. I.e. when large
	 * bitmap is greatly reduced to a small one, this ratio gets bigger and thus caching output of this formula saves
	 * more resources than caching outputs of formulas with lesser ratio.
	 */
	protected long getCostToPerformanceInternal() {
		return Arrays.stream(innerFormulas)
			.mapToLong(TransactionalDataRelatedStructure::getCostToPerformanceRatio)
			.sum() + (getCost() / Math.max(1, compute().size()));
	}

	/**
	 * Internal (not cached) computation operation of this formula.
	 */
	@Nonnull
	protected abstract Bitmap computeInternal();

	/**
	 * Initializes the internal state of the formula by initializing inner formulas, calculating hash, transactional IDs,
	 * estimated cost, cost, and cost-to-performance ratio. If the resetMemoizedResults flag is true, the memoized results
	 * will be reset.
	 *
	 * @param calculationContext   the calculation context used to initialize the formula
	 * @param resetMemoizedResults flag indicating whether to reset the memoized results
	 */
	private void initializeInternal(@Nonnull CalculationContext calculationContext, boolean resetMemoizedResults) {
		for (Formula innerFormula : innerFormulas) {
			if (resetMemoizedResults) {
				innerFormula.initializeAgain(calculationContext);
			} else {
				innerFormula.initialize(calculationContext);
			}
		}
		if (this.hash == null || resetMemoizedResults) {
			final LongStream formulaHashStream = Arrays.stream(innerFormulas)
				.mapToLong(TransactionalDataRelatedStructure::getHash);
			this.hash = calculationContext.getHashFunction().hashLongs(
				Stream.of(
						LongStream.of(getClassId()),
						isFormulaOrderSignificant() ? formulaHashStream : formulaHashStream.sorted(),
						LongStream.of(includeAdditionalHash(calculationContext.getHashFunction()))
					)
					.flatMapToLong(it -> it)
					.toArray()
			);
		}
		if (this.transactionalIds == null || resetMemoizedResults) {
			this.transactionalIds = gatherBitmapIdsInternal();
			this.transactionalIdHash = calculationContext.getHashFunction().hashLongs(
				Arrays.stream(this.transactionalIds)
					.distinct()
					.sorted()
					.toArray()
			);
		}
		if (this.estimatedCost == null || resetMemoizedResults) {
			if (calculationContext.visit(CalculationType.ESTIMATED_COST, this)) {
				this.estimatedCost = getEstimatedCostInternal();
			} else {
				this.estimatedCost = 0L;
			}
		}
		if ((this.cost == null || resetMemoizedResults) && this.memoizedResult != null) {
			if (calculationContext.visit(CalculationType.COST, this)) {
				this.cost = getCostInternal();
				this.costToPerformance = getCostToPerformanceInternal();
			} else {
				this.cost = 0L;
				this.costToPerformance = Long.MAX_VALUE;
			}
		}
	}

}
