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

import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.utils.visitor.FormulaCloner;
import io.evitadb.core.query.filter.FormulaOptimizer;
import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.core.query.response.TransactionalDataRelatedStructure;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.PrettyPrintable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Formula is an atomic computational step that allows to compute result of the input {@link io.evitadb.api.query.Query}.
 * Dedicated {@link FilteringConstraintTranslator translators} generate set
 * of computational steps that lead to computation of correct response for the query.
 *
 * Formulas are composed in hierarchical tree that you can imagine as mathematical formula where parentheses represent
 * a tree node with child formulas within.
 *
 * Formula {@link #compute()} produces the result of the equation. Formula can estimate its computational cost by
 * calling {@link #getEstimatedCost()} or more exactly by calling {@link #getCost()} that involves result computation.
 * These costs are derived from {@link #getOperationCost()} that was measured by performance tests on random numbers
 * and the amount of data processed by the formula.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface Formula extends TransactionalDataRelatedStructure, PrettyPrintable {

	/**
	 * Reusable empty array of formulas to avoid unnecessary allocations.
	 */
	Formula[] EMPTY_FORMULA_ARRAY = new Formula[0];

	/**
	 * Traverses formula tree with passed visitor.
	 *
	 * @param visitor the visitor to accept
	 */
	void accept(@Nonnull FormulaVisitor visitor);

	/**
	 * Computes product of this formula. The result is cached so multiple calls on this method will pay the cost only
	 * for the first time.
	 *
	 * @return bitmap representing the computed result of this formula
	 */
	@Nonnull
	Bitmap compute();

	/**
	 * Returns the result of this formula **if it is already available without performing any computation**, and
	 * `null` otherwise. This method never computes anything and never memoizes anything - it is the read-only
	 * counterpart of {@link #compute()}.
	 *
	 * It exists so that a formula tree can be *described* without being *executed*. That distinction is not a
	 * performance nicety: the planner builds one formula per candidate index and computes only the winner
	 * (`QueryPlanner#createFilterFormula`), so anything that renders a rejected alternative by calling
	 * {@link #compute()} would make the query do work it had deliberately decided to skip - telemetry would stop
	 * observing the query and start changing it. The same hazard exists inside the winning plan, whose
	 * short-circuited branches are legitimately never computed.
	 *
	 * Read the return value as "a result is available for free", **not** as "this formula ran during this query":
	 * a formula served from the cache ({@link io.evitadb.core.cache.payload.FlattenedFormula}) carries its result
	 * from the outset and reports it here, having computed nothing.
	 *
	 * Note there is no supported way to derive this from the cost accessors. `getCost() != Long.MAX_VALUE` happens
	 * to correlate today, but {@link #getEstimatedCost()} returns the very same sentinel on arithmetic overflow,
	 * so the correlation is a coincidence rather than a contract.
	 *
	 * @return the already available result, or `null` when producing one would require computation
	 */
	@Nullable
	default Bitmap getMemoizedResult() {
		return null;
	}

	/**
	 * Returns the actual cost of this formula **if it is already known without performing any computation**, and
	 * `null` otherwise. This is the read-only counterpart of {@link #getCost()}, and the cost-side twin of
	 * {@link #getMemoizedResult()}.
	 *
	 * It is a separate accessor because {@link #getCost()} is *not* safe to call on a partially computed tree.
	 * A formula whose result is memoized but whose cost has never been asked for will compute that cost on demand,
	 * and the default {@link AbstractFormula#getCostInternal()} does so by calling {@link #compute()} on every
	 * inner formula - including inner formulas that this formula's own computation deliberately skipped.
	 * `DisentangleFormula`'s `X \ X` guard is exactly that shape: it returns empty without touching its children,
	 * while its cost path falls through to the computing default. So `getCost()` on a memoized node can execute
	 * branches the query never ran, which is precisely what anything that merely *describes* a formula tree must
	 * not do.
	 *
	 * Read the return value as "a cost is available for free". `null` does not mean the formula never ran - it
	 * means nobody has paid for its cost yet, and this caller is not willing to.
	 *
	 * @return the already known actual cost, or `null` when producing one would require computation
	 */
	@Nullable
	default Long getMemoizedCost() {
		return null;
	}

	/**
	 * Returns a copy of this formula with replaced inner formulas. The return value also encodes a behaviour
	 * contract used by both {@link FormulaOptimizer} and {@link FormulaCloner} when the wrapper has been emptied
	 * by removal of its children:
	 *
	 * - **Returning {@link EmptyFormula#INSTANCE}** declares this wrapper as the **identity element** of its
	 *   parent. `FormulaOptimizer` removes the entire wrapper from the tree; `FormulaCloner` (used during
	 *   strip-clone passes such as the hierarchy-statistics shortcut) drops the wrapper from its parent's
	 *   child list rather than letting the empty result propagate upward as the absorbing element through the
	 *   surrounding `AND`/`OR` chain. This is the right answer for wrappers that have no meaningful semantics
	 *   without children — `AndFormula`, `OrFormula`, `UserFilterFormula`, `ScopeContainerFormula`, etc.
	 * - **Returning anything other than {@link EmptyFormula#INSTANCE}** keeps the wrapper as the **absorbing
	 *   element** when emptied — its emptiness propagates up the conjunction normally and reduces the parent
	 *   to empty too. This is the right answer for wrappers whose presence carries semantics independently of
	 *   their children (e.g. `FacetGroupOrFormula` / `FacetGroupAndFormula` carriers that the FACET_IMPACT
	 *   relaxer must still find by type).
	 *
	 * Wrapper implementations must therefore choose deliberately: returning `EmptyFormula.INSTANCE` on empty
	 * input is an explicit opt-in to drop-on-strip semantics, not a defensive no-op.
	 *
	 * @param innerFormulas the new inner formulas to use in the cloned formula
	 * @return a new formula instance of the same type with the given inner formulas
	 */
	@Nonnull
	Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas);

	/**
	 * Returns inner formulas this formula {@link #compute()} builds upon.
	 *
	 * @return array of child formulas (may be empty for leaf formulas)
	 */
	@Nonnull
	Formula[] getInnerFormulas();

	/**
	 * Returns the cardinality estimate of {@link #compute()} method without really computing the result. The estimate
	 * will not be precise but differs between AND/OR relations and helps us to compute {@link #getEstimatedCost()}.
	 *
	 * @return estimated number of elements in the result bitmap
	 */
	int getEstimatedCardinality();

	/**
	 * Clears the memoized results and hashes of the formula.
	 */
	void clearMemory();

	/**
	 * Prints information about the formula in a user-friendly way in verbose mode.
	 *
	 * @return verbose human-readable representation of this formula
	 */
	@Nonnull
	String toStringVerbose();

}
