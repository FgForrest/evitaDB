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

import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.identity.FormulaKey;
import io.evitadb.core.query.algebra.identity.IdentityExempt;
import io.evitadb.core.query.algebra.identity.IdentityRecord;
import io.evitadb.core.query.algebra.utils.visitor.PrettyPrintingFormulaVisitor;
import io.evitadb.core.query.response.TransactionalDataRelatedStructure;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.Assert;
import lombok.Getter;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;

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
	 * Shared empty array returned by {@link #computeSortedConjunctionBitmaps(List)} on short-circuit.
	 */
	protected static final PersistentRoaringBitmap[] EMPTY_ROARING_BITMAP_ARRAY = new PersistentRoaringBitmap[0];
	/**
	 * Execution context from initialization phase.
	 */
	@IdentityExempt(IdentityExempt.Reason.STATE) protected QueryExecutionContext executionContext;
	/**
	 * Contains an array of inner formulas.
	 */
	@IdentityExempt(IdentityExempt.Reason.STATE) @Getter protected Formula[] innerFormulas;
	/**
	 * Contains a memoized result once {@link #computeInternal()} is invoked for the first time. Additional calls of
	 * {@link Formula#compute()} will return this memoized result without paying the computational costs
	 */
	@IdentityExempt(IdentityExempt.Reason.STATE) @Nullable protected Bitmap memoizedResult;
	/**
	 * Contains memoized value of {@link #getEstimatedCost()}  of this formula.
	 */
	@IdentityExempt(IdentityExempt.Reason.STATE) private long estimatedCost;
	/**
	 * Contains memoized value of {@link #getCost()} of this formula. Valid only when {@link #costComputed} is TRUE.
	 */
	@IdentityExempt(IdentityExempt.Reason.STATE) private long cost;
	/**
	 * TRUE once {@link #cost} holds a computed value. The flag is needed because {@link #getCostInternal()}
	 * accumulates inner costs without an overflow guard and may therefore produce any `long` - including one that
	 * would be indistinguishable from a sentinel.
	 */
	@IdentityExempt(IdentityExempt.Reason.STATE) private boolean costComputed;

	/**
	 * Detached identity, built on the first {@link #getKey()} call and never invalidated afterwards.
	 *
	 * The field is plain rather than volatile: threads that request the first key at the same time may each build one,
	 * and all of them are equal, because they are derived from the same inputs and {@link FormulaKey} publishes its
	 * state through final fields. {@link #clearMemory()} deliberately leaves the key in place - it drops the computed
	 * result and cost, while the key describes construction-time inputs and child keys. An ancestor therefore keeps
	 * the key it built first, even if a child mutated in place would describe itself differently afterwards.
	 */
	@IdentityExempt(IdentityExempt.Reason.STATE)
	@Nullable private FormulaKey memoizedKey;

	/** Returns the detached identity built from declared inputs and ordered child keys, memoized on the first call. */
	@Nonnull
	@Override
	public final FormulaKey getKey() {
		final FormulaKey key = this.memoizedKey;
		if (key != null) {
			return key;
		}
		final IdentityRecord identity = identity();
		final FormulaKey[] children = this.innerFormulas.length == 0 ?
			FormulaKey.NO_CHILDREN : new FormulaKey[this.innerFormulas.length];
		for (int i = 0; i < children.length; i++) {
			children[i] = this.innerFormulas[i].getKey();
		}
		final FormulaKey computedKey = FormulaKey.of(identity, getClassId(), children, this);
		this.memoizedKey = computedKey;
		return computedKey;
	}

	/** Returns the declared identity components. */
	@Nonnull
	protected abstract IdentityRecord identity();

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
	public long getEstimatedCost() {
		Assert.isPremiseValid(
			this.innerFormulas != null,
			"The formula must be initialized prior to calling getEstimatedCost()."
		);
		return this.estimatedCost;
	}

	@Override
	public final long getCost() {
		if (!this.costComputed) {
			if (this.memoizedResult == null) {
				return Long.MAX_VALUE;
			} else {
				this.cost = getCostInternal();
				this.costComputed = true;
			}
		}
		return this.cost;
	}

	@Nullable
	@Override
	public final Long getMemoizedCost() {
		// deliberately free of charge: this accessor must never fall through to getCostInternal(), which computes
		// inner formulas - including ones the query itself skipped. Null means "nobody has paid for this cost yet".
		// The emptiness has to be read from the flag rather than from `cost` itself: the field is a primitive, so an
		// unpriced node holds 0 and a bare field read would autobox it into a cost that was never paid.
		return this.costComputed ? this.cost : null;
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

	@Nullable
	@Override
	public Bitmap getMemoizedResult() {
		// deliberately just the field - the whole point of this accessor is that reading it can never trigger
		// `computeInternal()`, which is what lets a rejected plan be described without being executed
		return this.memoizedResult;
	}

	/** Restores a retained result and records that this node performed no computation. */
	protected final void restoreComputedResult(@Nonnull Bitmap result) {
		this.memoizedResult = result;
		this.cost = 0;
		this.costComputed = true;
	}

	@Override
	public void clearMemory() {
		this.memoizedResult = null;
		this.costComputed = false;
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
			return Math.addExact(
				Math.addExact(getEstimatedBaseCost(), Math.multiplyExact(getOperationCost(), getEstimatedCardinality())),
				costs
			);
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
	 * Returns a long constant, that uniquely distinguishes this class from the others. The number must not change in
	 * time for the same class. The number must not be inherited from the superclasses and must be implemented and return
	 * different numbers for each "leaf class". This number discriminates the class in its {@link FormulaKey}.
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
	 * Computes {@link PersistentRoaringBitmap} results from pre-sorted formulas, short-circuiting and returning an empty array
	 * as soon as any formula produces an empty result.
	 *
	 * @param sortedFormulas formulas sorted by ascending estimated cost
	 * @return array of computed bitmaps, or an empty array if any formula yields an empty result
	 */
	@Nonnull
	protected static PersistentRoaringBitmap[] computeSortedConjunctionBitmaps(@Nonnull List<Formula> sortedFormulas) {
		final int size = sortedFormulas.size();
		final PersistentRoaringBitmap[] theBitmaps = new PersistentRoaringBitmap[size];
		for (int i = 0; i < size; i++) {
			final Bitmap computedBitmap = sortedFormulas.get(i).compute();
			if (computedBitmap.isEmpty()) {
				return EMPTY_ROARING_BITMAP_ARRAY;
			}
			theBitmaps[i] = RoaringBitmapBackedBitmap.getRoaringBitmap(computedBitmap);
		}
		return theBitmaps;
	}

	/**
	 * Computes the conjunction (AND) of the given {@link PersistentRoaringBitmap} array, returning
	 * {@link EmptyBitmap#INSTANCE} if any bitmap is empty or if the array itself is empty.
	 *
	 * @param bitmaps the bitmaps to intersect
	 * @return the intersection result, or {@link EmptyBitmap#INSTANCE} if the result is empty
	 */
	@Nonnull
	protected static Bitmap computeConjunctionResult(@Nonnull PersistentRoaringBitmap[] bitmaps) {
		if (bitmaps.length == 0) {
			return EmptyBitmap.INSTANCE;
		}
		for (final PersistentRoaringBitmap bitmap : bitmaps) {
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
			if (innerResult.isEmpty()) {
				break;
			}
		}
		return cost;
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
