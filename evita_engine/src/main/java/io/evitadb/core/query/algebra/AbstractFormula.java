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

package io.evitadb.core.query.algebra;

import io.evitadb.core.cache.CacheSupervisor;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.utils.visitor.PrettyPrintingFormulaVisitor;
import io.evitadb.core.query.response.TransactionalDataRelatedStructure;
import io.evitadb.core.transaction.memory.TransactionalLayerCreator;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.Assert;
import lombok.Getter;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
	 * Execution context from initialization phase.
	 */
	protected QueryExecutionContext executionContext;
	/**
	 * Contains array of inner formulas.
	 */
	@Getter protected Formula[] innerFormulas;
	/**
	 * Contains memoized result once {@link #computeInternal()} is invoked for the first time. Additional calls of
	 * {@link Formula#compute()} will return this memoized result without paying the computational costs
	 */
	@Nullable protected Bitmap memoizedResult;
	/**
	 * Contains memoized value of {@link #getEstimatedCost()}  of this formula.
	 */
	private Long estimatedCost;
	/**
	 * Contains memoized value of {@link #getCost()}  of this formula.
	 */
	@Nullable private Long cost;
	/**
	 * Contains memoized value of {@link #getCostToPerformanceRatio()} of this formula.
	 */
	@Nullable private Long costToPerformance;
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

	/**
	 * Initializes the fields of this formula. This method is called from the constructor and should be used to
	 * initialize the fields of the formula. The method is called after the inner formulas are set.
	 *
	 * TOBEDONE when upgrading to Java 22 with https://openjdk.org/jeps/447, switch fields to final and do this in the constructor
	 *
	 * @param innerFormulas inner formulas of this formula
	 */
	protected void initFields(@Nonnull Formula... innerFormulas) {
		this.innerFormulas = innerFormulas;
		final LongStream formulaHashStream = Arrays.stream(innerFormulas)
			.mapToLong(TransactionalDataRelatedStructure::getHash);
		this.hash = HASH_FUNCTION.hashLongs(
			Stream.of(
					LongStream.of(getClassId()),
					isFormulaOrderSignificant() ? formulaHashStream : formulaHashStream.sorted(),
					LongStream.of(includeAdditionalHash(HASH_FUNCTION))
				)
				.flatMapToLong(it -> it)
				.toArray()
		);
		this.transactionalIds = gatherBitmapIdsInternal();
		this.transactionalIdHash = HASH_FUNCTION.hashLongs(
			Arrays.stream(this.transactionalIds)
				.distinct()
				.sorted()
				.toArray()
		);
		this.estimatedCost = getEstimatedCostInternal();
	}

	@Override
	public void initialize(@Nonnull QueryExecutionContext executionContext) {
		this.executionContext = executionContext;
		for (Formula innerFormula : this.innerFormulas) {
			innerFormula.initialize(executionContext);
		}
	}

	@Override
	public final long getHash() {
		Assert.isPremiseValid(this.hash != null, "The formula must be initialized prior to calling getHash().");
		return this.hash;
	}

	@Override
	public long getTransactionalIdHash() {
		Assert.isPremiseValid(this.transactionalIdHash != null, "The formula must be initialized prior to calling getTransactionalIdHash().");
		return this.transactionalIdHash;
	}

	@Nonnull
	@Override
	public final long[] gatherTransactionalIds() {
		Assert.isPremiseValid(this.transactionalIds != null, "The formula must be initialized prior to calling gatherTransactionalIds().");
		return this.transactionalIds;
	}

	@Override
	public long getEstimatedCost() {
		Assert.isPremiseValid(this.estimatedCost != null, "The formula must be initialized prior to calling getEstimatedCost().");
		return this.estimatedCost;
	}

	@Override
	public final long getCost() {
		if (this.cost == null) {
			if (this.memoizedResult == null) {
				return Long.MAX_VALUE;
			} else {
				this.cost = getCostInternal();
			}
		}
		return this.cost;
	}

	@Override
	public final long getCostToPerformanceRatio() {
		if (this.costToPerformance == null) {
			if (this.memoizedResult == null) {
				return Long.MAX_VALUE;
			} else {
				this.costToPerformance = getCostToPerformanceInternal();
			}
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
	public void clearMemory() {
		this.memoizedResult = null;
		this.cost = null;
		this.costToPerformance = null;
	}

	@Nonnull
	@Override
	public String prettyPrint() {
		return PrettyPrintingFormulaVisitor.toString(this);
	}

	@Nonnull
	@Override
	public String toStringVerbose() {
		return toString();
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
		return Arrays.stream(this.innerFormulas)
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
			for (Formula innerFormula : this.innerFormulas) {
				costs = Math.addExact(costs, innerFormula.getEstimatedCost());
			}
			return getEstimatedBaseCost() + getOperationCost() * getEstimatedCardinality() + costs;
		} catch (ArithmeticException ex) {
			return Long.MAX_VALUE;
		}
	}

	/**
	 * Returns estimated computation complexity cost for computation that covers all additional internal data that
	 * affect the output of {@link Formula#compute()} method and are not part {@link #getInnerFormulas()}.
	 * The {@link #getInnerFormulas()} are implicitly part of the hash and should not be covered by this method.
	 */
	protected long getEstimatedBaseCost() {
		return 0L;
	}

	/**
	 * Returns a long hash, that should be computed by {@link CacheSupervisor#createHashFunction()} and covers all
	 * additional internal data that affect the output of {@link Formula#compute()} method and are not part
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
		return Arrays.stream(this.innerFormulas).mapToLong(TransactionalDataRelatedStructure::getCost).sum() +
			Arrays.stream(this.innerFormulas)
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
		return Arrays.stream(this.innerFormulas)
			.mapToLong(TransactionalDataRelatedStructure::getCostToPerformanceRatio)
			.sum() + (getCost() / Math.max(1, compute().size()));
	}

	/**
	 * Internal (not cached) computation operation of this formula.
	 */
	@Nonnull
	protected abstract Bitmap computeInternal();

}
