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
import io.evitadb.core.transaction.memory.TransactionalLayerCreator;
import io.evitadb.core.query.response.TransactionalDataRelatedStructure;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.Assert;
import lombok.Getter;
import net.openhft.hashing.LongHashFunction;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

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
	 * Contains an array of inner formulas.
	 */
	@Getter protected Formula[] innerFormulas;
	/**
	 * Contains a memoized result once {@link #computeInternal()} is invoked for the first time. Additional calls of
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
	 * Contains memoized value of {@link #getTransactionalIdHash()} method.
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

		// build hash array: [classId, innerFormulaHashes..., additionalHash]
		final int formulaCount = innerFormulas.length;
		final long[] hashArray = new long[formulaCount + 2];
		hashArray[0] = getClassId();
		for (int i = 0; i < formulaCount; i++) {
			hashArray[i + 1] = innerFormulas[i].getHash();
		}
		hashArray[formulaCount + 1] = includeAdditionalHash(HASH_FUNCTION);
		if (!isFormulaOrderSignificant()) {
			// sort only the inner formula hash portion [1, formulaCount+1)
			Arrays.sort(hashArray, 1, formulaCount + 1);
		}
		this.hash = HASH_FUNCTION.hashLongs(hashArray);

		this.transactionalIds = gatherBitmapIdsInternal();
		this.transactionalIdHash = HASH_FUNCTION.hashLongs(
			sortAndDeduplicateLongArray(this.transactionalIds)
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
	 *
	 * @return `true` if the order of inner formulas affects the result, `false` otherwise
	 */
	protected boolean isFormulaOrderSignificant() {
		return false;
	}

	/**
	 * Returns {@link TransactionalLayerCreator#getId()} of all bitmaps used by this formula. Should any of those ids
	 * become obsolete the formula is also obsolete. The returned array may contain duplicates and may not be sorted.
	 *
	 * @return array of transactional bitmap ids collected from all inner formulas
	 */
	@Nonnull
	protected long[] gatherBitmapIdsInternal() {
		// pre-compute total length to avoid resizing
		int totalLength = 0;
		for (final Formula formula : this.innerFormulas) {
			totalLength += formula.gatherTransactionalIds().length;
		}
		final long[] result = new long[totalLength];
		int offset = 0;
		for (final Formula innerFormula : this.innerFormulas) {
			final long[] ids = innerFormula.gatherTransactionalIds();
			System.arraycopy(ids, 0, result, offset, ids.length);
			offset += ids.length;
		}
		return result;
	}

	/**
	 * Estimated cost of the operation based on formula structure without paying the price for real computation
	 * of the results. Default implementation computes:
	 * `getEstimatedBaseCost() + getOperationCost() * getEstimatedCardinality() + sum(innerFormula.getEstimatedCost())`
	 * — i.e. the base cost of this formula's own data, plus the per-element operation cost scaled by estimated output
	 * cardinality, plus the recursive cost of all inner formulas. Returns {@link Long#MAX_VALUE} on arithmetic overflow.
	 *
	 * @return estimated cost of this formula tree, or {@link Long#MAX_VALUE} on overflow
	 */
	protected long getEstimatedCostInternal() {
		try {
			long costs = 0L;
			for (Formula innerFormula : this.innerFormulas) {
				costs = Math.addExact(costs, innerFormula.getEstimatedCost());
			}
			return getEstimatedBaseCost() + getOperationCost() * getEstimatedCardinality() + costs;
		} catch (ArithmeticException ex) {
			return Long.MAX_VALUE;
		}
	}

	/**
	 * Returns estimated computation complexity cost that covers additional internal data affecting
	 * the output of {@link Formula#compute()} beyond {@link #getInnerFormulas()}. Inner formulas are accounted for
	 * separately in {@link #getEstimatedCostInternal()} and should not be included here. Defaults to zero.
	 *
	 * @return base cost contribution from this formula's own data (excluding inner formulas)
	 */
	protected long getEstimatedBaseCost() {
		return 0L;
	}

	/**
	 * Returns a long hash, that should be computed by {@link CacheSupervisor#createHashFunction()} and covers all
	 * additional internal data that affect the output of {@link Formula#compute()} method and are not part
	 * {@link #getInnerFormulas()}. The {@link #getInnerFormulas()} are implicitly part of the hash and should not be
	 * covered by this method.
	 *
	 * @param hashFunction the hash function to use for computing the hash
	 * @return hash value covering this formula's additional internal state
	 */
	protected abstract long includeAdditionalHash(@Nonnull LongHashFunction hashFunction);

	/**
	 * Returns a long constant, that uniquely distinguishes this class from the others. The number must not change in
	 * time for the same class. The number must not be inherited from the superclasses and must be implemented and return
	 * different numbers for each "leaf class". This number is important part of {@link #getHash()} method.
	 *
	 * @return unique class identifier constant
	 */
	protected abstract long getClassId();

	/**
	 * Actual cost of the operation based on computed results. Default implementation computes:
	 * `sum(innerFormula.getCost()) + sum(innerFormula.compute().size()) * getOperationCost()`
	 * — i.e. the recursive cost of all inner formulas plus the total number of elements processed scaled by
	 * this operation's cost. This method triggers formula computation.
	 *
	 * @return actual cost of this formula tree based on computed results
	 */
	protected long getCostInternal() {
		long costSum = 0;
		long sizeSum = 0;
		for (final Formula innerFormula : this.innerFormulas) {
			costSum += innerFormula.getCost();
			sizeSum += innerFormula.compute().size();
		}
		return costSum + sizeSum * getOperationCost();
	}

	/**
	 * Returns cost-to-performance ratio. Default implementation sums the cost-to-performance ratio of all inner
	 * formulas and adds the ratio of this operation computed as `getCost() / compute().size()`. When a large bitmap
	 * is greatly reduced to a small one, this ratio grows — caching such a formula saves more resources than caching
	 * formulas with a lower ratio.
	 *
	 * @return accumulated cost-to-performance ratio for this formula and its children
	 */
	protected long getCostToPerformanceInternal() {
		long sum = 0;
		for (final Formula innerFormula : this.innerFormulas) {
			sum += innerFormula.getCostToPerformanceRatio();
		}
		return sum + (getCost() / Math.max(1, compute().size()));
	}

	/**
	 * Sorts the given long array in place and removes duplicate values, returning the deduplicated result.
	 * The input array is sorted as a side effect. Callers that need the original order must clone before calling.
	 *
	 * @param input the array to sort and deduplicate (modified in place)
	 * @return the deduplicated array — either the same instance or a trimmed copy
	 */
	@Nonnull
	protected static long[] sortAndDeduplicateLongArray(@Nonnull long[] input) {
		if (input.length <= 1) {
			return input;
		}
		Arrays.sort(input);
		int unique = 1;
		for (int i = 1; i < input.length; i++) {
			if (input[i] != input[i - 1]) {
				input[unique++] = input[i];
			}
		}
		return unique == input.length ? input : Arrays.copyOf(input, unique);
	}

	/**
	 * Sorts the given formulas by their {@link TransactionalDataRelatedStructure#getEstimatedCost()} in ascending
	 * order, returning an immutable list. Used by conjunction formulas to evaluate cheapest formulas first and
	 * short-circuit on empty results.
	 *
	 * @param formulas the formulas to sort (not modified — a copy is made)
	 * @return immutable list of formulas sorted by ascending estimated cost
	 */
	@Nonnull
	protected static List<Formula> sortFormulasByComplexity(@Nonnull Formula[] formulas) {
		final Formula[] sorted = new Formula[formulas.length];
		System.arraycopy(formulas, 0, sorted, 0, formulas.length);
		Arrays.sort(sorted, Comparator.comparingLong(TransactionalDataRelatedStructure::getEstimatedCost));
		return List.of(sorted);
	}

	/**
	 * Computes {@link RoaringBitmap} results from pre-sorted formulas, short-circuiting and returning an empty array
	 * as soon as any formula produces an empty result.
	 *
	 * @param sortedFormulas formulas sorted by ascending estimated cost
	 * @return array of computed bitmaps, or an empty array if any formula yields an empty result
	 */
	@Nonnull
	protected static RoaringBitmap[] computeSortedConjunctionBitmaps(@Nonnull List<Formula> sortedFormulas) {
		final RoaringBitmap[] theBitmaps = new RoaringBitmap[sortedFormulas.size()];
		for (int i = 0; i < sortedFormulas.size(); i++) {
			final Bitmap computedBitmap = sortedFormulas.get(i).compute();
			if (computedBitmap.isEmpty()) {
				return new RoaringBitmap[0];
			}
			theBitmaps[i] = RoaringBitmapBackedBitmap.getRoaringBitmap(computedBitmap);
		}
		return theBitmaps;
	}

	/**
	 * Computes the conjunction (AND) of the given {@link RoaringBitmap} array, returning
	 * {@link EmptyBitmap#INSTANCE} if any bitmap is empty or if the array itself is empty.
	 *
	 * @param bitmaps the bitmaps to intersect
	 * @return the intersection result, or {@link EmptyBitmap#INSTANCE} if the result is empty
	 */
	@Nonnull
	protected static Bitmap computeConjunctionResult(@Nonnull RoaringBitmap[] bitmaps) {
		if (bitmaps.length == 0) {
			return EmptyBitmap.INSTANCE;
		}
		for (final RoaringBitmap bitmap : bitmaps) {
			if (bitmap.isEmpty()) {
				return EmptyBitmap.INSTANCE;
			}
		}
		final Bitmap theResult;
		if (bitmaps.length == 1) {
			theResult = new BaseBitmap(bitmaps[0]);
		} else {
			theResult = RoaringBitmapBackedBitmap.and(bitmaps);
		}
		return theResult.isEmpty() ? EmptyBitmap.INSTANCE : theResult;
	}

	/**
	 * Computes the cost of a conjunction operation over pre-sorted formulas, short-circuiting on empty results.
	 *
	 * @param sortedFormulas formulas sorted by ascending estimated cost
	 * @param operationCost  per-element cost of the conjunction operation
	 * @return accumulated cost, stopping early if any formula produces an empty result
	 */
	protected static long computeSortedConjunctionCost(@Nonnull List<Formula> sortedFormulas, long operationCost) {
		long cost = 0L;
		for (final Formula innerFormula : sortedFormulas) {
			final Bitmap innerResult = innerFormula.compute();
			cost += innerFormula.getCost() + innerResult.size() * operationCost;
			if (innerResult == EmptyBitmap.INSTANCE) {
				break;
			}
		}
		return cost;
	}

	/**
	 * Computes the accumulated cost-to-performance ratio of a conjunction operation over pre-sorted formulas,
	 * short-circuiting on empty results. Callers should add their own
	 * `getCost() / Math.max(1, compute().size())` tail term.
	 *
	 * @param sortedFormulas formulas sorted by ascending estimated cost
	 * @return accumulated cost-to-performance ratio from inner formulas
	 */
	protected static long computeSortedConjunctionCostToPerformance(@Nonnull List<Formula> sortedFormulas) {
		long costToPerformance = 0L;
		for (final Formula innerFormula : sortedFormulas) {
			final Bitmap innerResult = innerFormula.compute();
			if (innerResult == EmptyBitmap.INSTANCE) {
				break;
			}
			costToPerformance += innerFormula.getCostToPerformanceRatio();
		}
		return costToPerformance;
	}

	/**
	 * Returns the minimum {@link Formula#getEstimatedCardinality()} across the given inner formulas — the correct
	 * worst-case estimate for conjunction (AND) formulas, since an intersection cannot exceed the smallest input.
	 *
	 * @param innerFormulas the formulas to examine
	 * @return the smallest estimated cardinality, or zero if the array is empty
	 */
	protected static int getMinEstimatedCardinality(@Nonnull Formula[] innerFormulas) {
		if (innerFormulas.length == 0) {
			return 0;
		}
		int min = innerFormulas[0].getEstimatedCardinality();
		for (int i = 1; i < innerFormulas.length; i++) {
			final int cardinality = innerFormulas[i].getEstimatedCardinality();
			if (cardinality < min) {
				min = cardinality;
			}
		}
		return min;
	}

	/**
	 * Internal (not cached) computation operation of this formula.
	 *
	 * @return bitmap representing the raw computed result
	 */
	@Nonnull
	protected abstract Bitmap computeInternal();

}
