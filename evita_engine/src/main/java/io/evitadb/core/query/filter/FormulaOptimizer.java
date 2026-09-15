/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

package io.evitadb.core.query.filter;

import io.evitadb.core.query.algebra.ChildrenDependentFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.FormulaPostProcessor;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.NotFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.NonCollapsibleFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.algebra.utils.visitor.FormulaCloner;
import io.evitadb.index.bitmap.Bitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import static io.evitadb.core.query.filter.FilterByVisitor.isConjunctiveFormula;

/**
 * Optimizes a {@link Formula} tree produced by the query planning phase.
 *
 * The optimizer performs two inexpensive structural rewrites:
 *
 * - removes an entire conjunctive container (AND-like node) if any of its children is
 *   {@link EmptyFormula} or replaces it with {@link EmptyFormula} depending on parent scope
 *   (the whole conjunction is unsatisfiable)
 * - unwraps an {@link OrFormula} that contains exactly one inner formula (no need for the container)
 *
 * The optimizer is implemented as a {@link FormulaPostProcessor} on top of {@link FormulaCloner} and is
 * safe to run repeatedly. It does not change the semantics of the tree; it only prunes dead branches and
 * redundant containers to reduce evaluation cost downstream.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class FormulaOptimizer extends FormulaCloner implements FormulaPostProcessor {

	/**
	 * Creates a new optimizer backed by the {@link Optimizer} strategy used by {@link FormulaCloner}.
	 */
	public FormulaOptimizer() {
		super(Optimizer.INSTANCE);
	}

	@Override
	public void visit(@Nonnull Formula formula) {
		final Formula alreadyProcessedFormula = this.formulasProcessed.get(formula);
		if (alreadyProcessedFormula != null) {
			storeFormula(alreadyProcessedFormula);
		} else {
			final Formula formulaToStore;
			pushContext(this.treeStack, formula);
			this.parents.push(formula);
			for (Formula innerFormula : formula.getInnerFormulas()) {
				innerFormula.accept(this);
			}
			this.parents.pop();
			final SubTree subTree = popContext(this.treeStack);
			final Set<Formula> updatedChildren = subTree.getChildren();
			final boolean childrenHaveNotChanged = updatedChildren.size() == formula.getInnerFormulas().length &&
				Arrays.stream(formula.getInnerFormulas())
					.allMatch(updatedChildren::contains);

			if (childrenHaveNotChanged) {
				// use entire formula tree block
				formulaToStore = this.mutator.apply(this, formula);
			} else if (formula instanceof NotFormula notFormula) {
				// Logic: NotFormula is (Superset \ Subtracted)
				// We must determine if the Superset survived optimization.
				Formula originalSuperset = notFormula.getSupersetFormula();
				Formula optimizedSuperset = this.formulasProcessed.get(originalSuperset);

				// Case 1: Superset became Empty or Null -> Result is Empty
				// the node must be replaced by EmptyFormula, never dropped - `null` instructs the cloner to
				// remove the child from its parent, which widens an enclosing conjunction (`A AND nothing`
				// would degrade to `A`) instead of emptying it; EmptyFormula is the absorbing element of
				// a conjunction and the identity element of a disjunction, so it is correct in both scopes
				if (optimizedSuperset == null || optimizedSuperset instanceof EmptyFormula) {
					formulaToStore = EmptyFormula.INSTANCE;
				}
				// Case 2: Superset exists, but Subtracted is gone (set size is 1) -> Result is Superset
				else if (updatedChildren.size() == 1) {
					formulaToStore = optimizedSuperset;
				}
				// Case 3: Both survived -> Reconstruct
				else {
					formulaToStore = this.mutator.apply(
						this,
						formula.getCloneWithInnerFormulas(updatedChildren.toArray(Formula[]::new))
					);
				}
			} else if (updatedChildren.isEmpty() && formula instanceof ChildrenDependentFormula) {
				// remove the formula if it has no children after optimization
				formulaToStore = null;
			} else {
				// recreate parent formula with new children
				formulaToStore = this.mutator.apply(
					this,
					formula.getCloneWithInnerFormulas(
						updatedChildren.toArray(Formula[]::new)
					)
				);
			}

			if (formulaToStore != null) {
				this.formulasProcessed.put(formula, formulaToStore);
				storeFormula(formulaToStore);
			}
		}
	}

	/**
	 * Memoizes, per node, whether its subtree holds a {@link NonCollapsibleFormula}.
	 *
	 * Keyed by identity - which is what a plain `HashMap` gives here, because
	 * {@link io.evitadb.core.query.algebra.AbstractFormula} overrides neither `equals` nor `hashCode`.
	 */
	private final Map<Formula, Boolean> nonCollapsibleSubTrees = new HashMap<>();

	/**
	 * Returns TRUE when `formula` is a {@link NonCollapsibleFormula} or holds one anywhere beneath it.
	 *
	 * **The check has to be transitive.** The carrier is rarely a direct child of the container whose collapse
	 * would destroy it: `userFilter(facetHaving(...), attributeIs(..., NULL))` plans as a `UserFilterFormula`
	 * wrapping a *single* `AndFormula`, because `UserFilterTranslator` folds every child into one conjunction via
	 * `FutureNotFormula#postProcess`. The facet carrier therefore sits two levels below the `userFilter`, and a
	 * rule that only inspected direct children would miss it.
	 *
	 * The optimizer walks bottom-up and memoizes each node as it is asked about, so the whole pass stays linear in
	 * the size of the tree rather than rescanning a subtree per conjunctive container.
	 *
	 * @param formula node to test, together with everything beneath it
	 * @return TRUE when the subtree carries information later query phases read off the tree structure
	 */
	boolean holdsNonCollapsibleFormula(@Nonnull Formula formula) {
		if (formula instanceof NonCollapsibleFormula) {
			return true;
		}
		final Boolean memoized = this.nonCollapsibleSubTrees.get(formula);
		if (memoized != null) {
			return memoized;
		}
		boolean result = false;
		for (final Formula innerFormula : formula.getInnerFormulas()) {
			if (holdsNonCollapsibleFormula(innerFormula)) {
				result = true;
				break;
			}
		}
		this.nonCollapsibleSubTrees.put(formula, result);
		return result;
	}

	/**
	 * Returns the optimized clone of the input formula tree.
	 *
	 * @return optimized formula tree, never {@code null}
	 */
	@Nonnull
	@Override
	public Formula getPostProcessedFormula() {
		final Formula resultClone = getResultClone();
		return resultClone == null ? EmptyFormula.INSTANCE : resultClone;
	}

	/**
	 * Strategy used by {@link FormulaCloner} to optionally replace nodes during cloning.
	 *
	 * The function may return:
	 *
	 * - {@code null}: signal to the cloner that the current node should be dropped from the output tree
	 * - a different {@link Formula}: replacement for the current node
	 * - the same {@link Formula}: no structural change
	 */
	private static class Optimizer implements BiFunction<FormulaCloner, Formula, Formula> {
		public static final Optimizer INSTANCE = new Optimizer();

		/**
		 * Applies local optimizations:
		 *
		 * - if the current node is a conjunctive container (e.g. AND) and any child is {@link EmptyFormula},
		 *   the action depends on the traversal scope managed by {@link FormulaOptimizer}:
		 *   - in conjunctive scope, replace the container with {@link EmptyFormula} (the whole conjunction
		 *     is unsatisfiable)
		 *   - in disjunctive scope, drop the entire container (return {@code null}) so the enclosing
		 *     disjunction can continue with other alternatives
		 * - if the current node is an {@link OrFormula} with a single child, unwrap the container
		 *
		 * @param formulaCloner the active cloner driving the traversal
		 * @param formula the current formula node being visited
		 * @return {@code null} to drop the node, a replacement formula, or the same instance if unchanged
		 */
		@Nullable
		@Override
		public Formula apply(final FormulaCloner formulaCloner, final Formula formula) {
			// If this is a conjunctive (AND-like) formula, check for an empty child.
			if (isConjunctiveFormula(formula.getClass())) {
				for (final Formula innerFormula : formula.getInnerFormulas()) {
					// Any EmptyFormula inside AND makes the whole conjunction unsatisfiable.
					if (innerFormula instanceof EmptyFormula) {
						// Collapsing the container is a pure win for filtering - same record set, smaller tree -
						// which is exactly why it is easy to miss that it destroys anything reading the tree's
						// *structure*: the `userFilter` marker extra-result planning relaxes against, a facet
						// selection, a histogram range carrier. Those consumers do not fail loudly when a carrier
						// disappears; a dropped facet selection simply reports `requested = false` for a facet the
						// user did request. So a container holding a carrier anywhere beneath it is returned
						// unchanged and left to compute empty on its own, which it still does - the `EmptyFormula`
						// child makes the conjunction unsatisfiable however many siblings it has, and
						// `AbstractFormula#computeSortedConjunctionBitmaps` reaches that child first (zero cost,
						// zero cardinality) and short-circuits without ever computing the carrier beside it.
						// The cast is safe: `Optimizer.INSTANCE` is installed only by this class's constructor.
						if (((FormulaOptimizer) formulaCloner).holdsNonCollapsibleFormula(formula)) {
							return formula;
						}
						// in conjunctive scope, replace the entire container with an empty formula
						return EmptyFormula.INSTANCE;
					}
				}
			// If this is an OR with a single child, the container is redundant – unwrap it.
			} else if (formula instanceof OrFormula orFormula && orFormula.getInnerFormulas().length > 0) {
				Formula impactfulChild = null;
				for (Formula innerFormula : orFormula.getInnerFormulas()) {
					if (!(innerFormula instanceof EmptyFormula)) {
						if (impactfulChild != null) {
							// more than one non-empty child – cannot optimize and OR must stay as is
							return formula;
						} else {
							impactfulChild = innerFormula;
						}
					}
				}
				// every child was empty, so the disjunction is empty - it is NOT absent. Returning `null` here would
				// instruct the cloner to remove this node from its parent, and an enclosing conjunction would then
				// widen to its surviving siblings instead of emptying (`A AND nothing` degrading to `A`) - the same
				// failure the `NotFormula` branch above guards against, and one this project has shipped once.
				// Today the branch is unreachable: every collapsed child is the one `EmptyFormula#INSTANCE`
				// (private constructor), `AbstractFormula` defines no `equals`/`hashCode`, so the cloner's
				// identity-keyed `LinkedHashSet` merges them into a single entry, `childrenHaveNotChanged` turns
				// false and the clone path rewrites the container instead. That is three separate facts holding it
				// up, none of them visible from here - so the case is answered rather than left to them.
				// `EmptyFormula` and not an exception: "every disjunct is empty" is a legitimate logical state.
				return impactfulChild == null ? EmptyFormula.INSTANCE : impactfulChild;
			} else if (formula instanceof NotFormula notFormula) {
				// DeMorgan's law: S \ (A OR B) -> (S \ A) AND (S \ B)
				Formula subtracted = notFormula.getSubtractedFormula();
				Formula superset = notFormula.getSupersetFormula();

				// We only optimize if the 'subtracted' part is an OR (the expensive operation)
				if (subtracted instanceof final OrFormula innerOr) {
					final Formula[] innerFormulas = innerOr.getInnerFormulas();
					final Bitmap[] bitmaps = innerOr.getBitmaps();
					final int totalChildren = innerFormulas.length + bitmaps.length;

					if (totalChildren > 0) {
						// Create AND of individual NOTs: AND(NOT(A, S), NOT(B, S))
						final Formula[] newAndChildren = new Formula[totalChildren];
						int idx = 0;

						// Handle formula-based children
						for (final Formula innerFormula : innerFormulas) {
							newAndChildren[idx++] = new NotFormula(innerFormula, superset);
						}

						// Handle bitmap-based children (wrap each in ConstantFormula)
						for (final Bitmap bitmap : bitmaps) {
							newAndChildren[idx++] = new NotFormula(
								new ConstantFormula(bitmap), superset
							);
						}

						// Return the CHEAP 'AndFormula' replacing the EXPENSIVE 'OrFormula'
						return FormulaFactory.and(newAndChildren);
					}
				}
			}
			// No change for other cases – keep the node as is.
			return formula;
		}

	}
}
