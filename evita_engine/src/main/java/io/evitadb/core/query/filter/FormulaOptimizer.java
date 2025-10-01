/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.NotFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.utils.visitor.FormulaCloner;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
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
				Arrays.stream(formula.getInnerFormulas()).allMatch(updatedChildren::contains);

			if (childrenHaveNotChanged) {
				// use entire formula tree block
				formulaToStore = this.mutator.apply(this, formula);
			} else if (formula instanceof NotFormula && updatedChildren.size() == 1) {
				formulaToStore = updatedChildren.iterator().next();
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
				return impactfulChild;
			}
			// No change for other cases – keep the node as is.
			return formula;
		}

	}
}
