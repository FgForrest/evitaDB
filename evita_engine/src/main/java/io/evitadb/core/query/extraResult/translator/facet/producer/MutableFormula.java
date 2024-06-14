/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.core.query.extraResult.translator.facet.producer;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.FormulaVisitor;
import io.evitadb.core.query.algebra.facet.FacetGroupFormula;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * This formula is a HACK and don't use it!!!
 *
 * Ok, now let's talk about why is this a hack, why you shouldn't use it and why it's here. Formulas are meant to be
 * IMMUTABLE, and this one is mutable - thus breaking the law. It was introduced only because of the performance
 * reasons (see <a href="https://github.com/FgForrest/evitaDB/issues/464">issue 464</a>). This formula is used only
 * in facet summary computation logic where we need to generate specific formulas for each facet. The problem is that
 * there are usually a LOT of facets and generating a new formula for each of them is very expensive. So we use this
 * hack to generate a formula for the first facet of a specific kind, and then we just change the inner formula to
 * the next one. This way we avoid unnecessary objects allocation and repeated formula analysis, which (as proven by
 * profiling) is a significant performance improvement.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
class MutableFormula implements Formula {
	private boolean suppressPivot;
	private FacetGroupFormula pivot;
	private FacetGroupFormula delegate;
	private FacetGroupFormula result;

	public MutableFormula(@Nonnull FacetGroupFormula delegate) {
		this.delegate = delegate;
	}

	/**
	 * The pivot represents a formula found in original formula tree which cannot be ignored and must always be respected
	 * when the {@link #delegate} is set or exchanged.
	 *
	 * @param pivot the pivot formula
	 */
	public void setPivot(@Nonnull FacetGroupFormula pivot) {
		this.pivot = pivot;
		this.result = null;
	}

	/**
	 * The delegate is exchanged with different formula group delegate for each computation. This allows us to fully
	 * reuse original formula tree and yet calculate different result using an exchanged delegate in this formula.
	 * This approach is a HACK and should not be used in any other place than the facet summary computation logic.
	 *
	 * @param delegate the new delegate formula
	 */
	public void setDelegate(@Nonnull FacetGroupFormula delegate) {
		this.delegate = delegate;
		this.result = null;
	}

	@Override
	public void clearMemory() {
		this.result = null;
	}

	@Override
	public void initialize(@Nonnull CalculationContext calculationContext) {
		getInnerFormula().initialize(calculationContext);
	}

	@Override
	public long getHash() {
		return getInnerFormula().getHash();
	}

	@Override
	public long getTransactionalIdHash() {
		return getInnerFormula().getTransactionalIdHash();
	}

	@Override
	@Nonnull
	public long[] gatherTransactionalIds() {
		return getInnerFormula().gatherTransactionalIds();
	}

	@Override
	public long getEstimatedCost() {
		return getInnerFormula().getEstimatedCost();
	}

	@Override
	public long getCost() {
		return getInnerFormula().getCost();
	}

	@Override
	public long getOperationCost() {
		return getInnerFormula().getOperationCost();
	}

	@Override
	public long getCostToPerformanceRatio() {
		return getInnerFormula().getCostToPerformanceRatio();
	}

	@Override
	@Nonnull
	public String prettyPrint() {
		return getInnerFormula().prettyPrint();
	}

	@Override
	public void accept(@Nonnull FormulaVisitor visitor) {
		visitor.visit(this);
	}

	@Override
	@Nonnull
	public Bitmap compute() {
		return getInnerFormula().compute();
	}

	@Override
	@Nonnull
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		throw new UnsupportedOperationException("Clone cannot be performed on mutable formula!");
	}

	@Override
	@Nonnull
	public Formula[] getInnerFormulas() {
		return getInnerFormula().getInnerFormulas();
	}

	@Override
	public int getEstimatedCardinality() {
		return getInnerFormula().getEstimatedCardinality();
	}

	@Override
	public int hashCode() {
		return getInnerFormula().hashCode();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		MutableFormula that = (MutableFormula) o;

		return Objects.equals(delegate, that.delegate);
	}

	@Override
	public String toString() {
		return getInnerFormula().toString();
	}

	@Nonnull
	@Override
	public String toStringVerbose() {
		return getInnerFormula().toStringVerbose();
	}

	/**
	 * Method allows to calculate {@link Formula#compute()} result based only on {@link #delegate}, suppressing combination
	 * with {@link #pivot} formula even if this is set.
	 *
	 * @param lambda the lambda to be executed
	 * @return the result of the lambda
	 */
	public boolean suppressPivot(@Nonnull BooleanSupplier lambda) {
		try {
			Assert.isPremiseValid(this.result == null, "Cannot suppress pivot when the result is already computed!");
			this.suppressPivot = true;
			return lambda.getAsBoolean();
		} finally {
			this.suppressPivot = false;
		}
	}

	/**
	 * Returns the inner formula. If the pivot is set, the result is merged with the pivot.
	 * @return the inner formula
	 */
	@Nonnull
	private FacetGroupFormula getInnerFormula() {
		if (this.result == null) {
			if (this.pivot != null && !this.suppressPivot) {
				this.result = this.pivot.mergeWith(this.delegate);
			} else {
				this.result = this.delegate;
			}
		}
		return this.result;
	}
}
