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

package io.evitadb.core.query.algebra.utils.visitor;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.FormulaVisitor;
import io.evitadb.core.query.algebra.base.DisentangleFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.NotFormula;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * FormulaCloner creates deep duplicate of the original {@link Formula} instance. Cloner reuses all formulas with
 * memoized results and recreates only those that are modified by {@link #mutator}
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class FormulaCloner implements FormulaVisitor {
	/**
	 * This map keeps track of already mutated formulas so that they can be reused in cloned formula tree.
	 * This solves the situation that when there are duplicate instances in formulas in source tree, they will be
	 * appropriately imitated (in terms of formula identity) in the output tree.
	 */
	protected final IdentityHashMap<Formula, Formula> formulasProcessed = new IdentityHashMap<>();
	/**
	 * This function is applied to every {@link Formula} visited and may return:
	 *
	 * - original instance (it will keep it along with all memoized results)
	 * - new instance (it will use it and recreate all parent formulas which then lose their memoized results)
	 * - NULL (it will skip it and recreate all parent formulas which then lose their memoized results)
	 */
	protected final BiFunction<FormulaCloner, Formula, Formula> mutator;
	/**
	 * This stack contains list of parents for currently examined formula.
	 */
	protected final Deque<Formula> parents = new ArrayDeque<>(32);
	/**
	 * Stacks serves internally to collect the cloned tree of formulas.
	 */
	protected final Deque<SubTree> treeStack = new ArrayDeque<>(32);
	/**
	 * Result set of the clone operation.
	 */
	@Getter private Formula resultClone;

	/**
	 * Preferred way of invoking this visitor. Accepts formula (tree) and produces mutated clone of this formula in
	 * response. The result shares memoized results that can be shared.
	 */
	@Nullable
	public static Formula clone(@Nonnull Formula formulaToClone, @Nonnull UnaryOperator<Formula> mutator) {
		final FormulaCloner visitor = new FormulaCloner(mutator);
		formulaToClone.accept(visitor);
		return visitor.getResultClone();
	}

	/**
	 * Preferred way of invoking this visitor. Accepts formula (tree) and produces mutated clone of this formula in
	 * response. The result shares memoized results that can be shared.
	 */
	@Nullable
	public static Formula clone(@Nonnull Formula formulaToClone, @Nonnull BiFunction<FormulaCloner, Formula, Formula> mutator) {
		final FormulaCloner visitor = new FormulaCloner(mutator);
		formulaToClone.accept(visitor);
		return visitor.getResultClone();
	}

	protected FormulaCloner(@Nonnull UnaryOperator<Formula> mutator) {
		this.mutator = (formulaCloner, formula) -> mutator.apply(formula);
	}

	protected FormulaCloner(@Nonnull BiFunction<FormulaCloner, Formula, Formula> mutator) {
		this.mutator = mutator;
	}

	/**
	 * Returns true if there is at least single parent formula that matches passed predicate for currently visited
	 * formula.
	 */
	public boolean isWithin(@Nonnull Predicate<Formula> formulaTester) {
		for (Formula parent : this.parents) {
			if (formulaTester.test(parent)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns true if all parents match the passed predicate.
	 */
	public boolean allParentsMatch(@Nonnull Predicate<Formula> formulaTester) {
		for (Formula parent : this.parents) {
			if (!formulaTester.test(parent)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Returns true if there is at least single parent formula of passed `formulaType` for currently visited formula.
	 */
	public boolean isWithin(@Nonnull Class<? extends Formula> formulaType) {
		return isWithin(formulaType::isInstance);
	}

	@Override
	public void visit(@Nonnull Formula formula) {
		final Formula mutatedFormula = this.mutator.apply(this, formula);
		final Formula alreadyProcessedFormula = this.formulasProcessed.get(formula);
		if (alreadyProcessedFormula != null) {
			storeFormula(alreadyProcessedFormula);
		} else {
			final Formula formulaToStore;
			if (mutatedFormula == formula) {
				pushContext(this.treeStack, formula);
				this.parents.push(formula);
				for (Formula innerFormula : formula.getInnerFormulas()) {
					innerFormula.accept(this);
				}
				this.parents.pop();
				final SubTree subTree = popContext(this.treeStack);
				final Set<Formula> updatedChildren = subTree.getChildren();
				boolean childrenHaveNotChanged = updatedChildren.size() == formula.getInnerFormulas().length;
				if (childrenHaveNotChanged) {
					for (Formula innerFormula : formula.getInnerFormulas()) {
						if (!updatedChildren.contains(innerFormula)) {
							childrenHaveNotChanged = false;
							break;
						}
					}
				}

				if (childrenHaveNotChanged) {
					// use entire formula tree block
					formulaToStore = formula;
				} else if (formula instanceof NotFormula notFormula && updatedChildren.size() < 2) {
					if (updatedChildren.isEmpty()) {
						// both children stripped — nothing left to subtract from, drop the wrapper
						formulaToStore = null;
					} else {
						// Determine which child survived
						final Formula processedSubtracted = this.formulasProcessed.get(notFormula.getSubtractedFormula());
						final Formula processedSuperset = this.formulasProcessed.get(notFormula.getSupersetFormula());
						if (processedSubtracted != null && processedSubtracted == processedSuperset
							&& updatedChildren.contains(processedSuperset)) {
							// Both positional siblings post-process to the same formula instance
							// (typically due to FormulaDeduplicator collapsing two structurally
							// equivalent ConstantFormula children of `or(P, not(P))`). The single
							// Set entry hides what used to be two distinct positional siblings.
							// Mathematically `X \ X = ∅`, so collapse the wrapper to EmptyFormula
							// rather than mistakenly returning the surviving child as if subtracted
							// had been dropped.
							formulaToStore = EmptyFormula.INSTANCE;
						} else if (processedSuperset != null && updatedChildren.contains(processedSuperset)) {
							// Superset survived, subtracted was removed → S \ nothing = S
							formulaToStore = processedSuperset;
						} else {
							// Subtracted survived, superset was removed → nothing to subtract from → drop
							formulaToStore = null;
						}
					}
				} else if (formula instanceof DisentangleFormula disentangleFormula && updatedChildren.size() < 2) {
					// DisentangleFormula(main, control) requires two positional siblings — the same
					// dedup-collapse pattern that hits NotFormula above can drop one of them when
					// FormulaDeduplicator unifies structurally equivalent inputs. Without this guard
					// the fall-through would call `getCloneWithInnerFormulas([X])` and throw
					// ArrayIndexOutOfBoundsException on `innerFormulas[1]`.
					if (updatedChildren.isEmpty()) {
						// both children stripped — no operation possible, drop the wrapper
						formulaToStore = null;
					} else {
						// Determine which child survived
						final Formula processedMain = this.formulasProcessed.get(disentangleFormula.getInnerFormulas()[0]);
						final Formula processedControl = this.formulasProcessed.get(disentangleFormula.getInnerFormulas()[1]);
						if (processedMain != null && processedMain == processedControl
							&& updatedChildren.contains(processedMain)) {
							// Both positional siblings post-process to the same formula instance
							// (FormulaDeduplicator collapsing structurally equivalent inputs).
							// Mathematically `disentangle(X, X) = ∅`.
							formulaToStore = EmptyFormula.INSTANCE;
						} else if (processedMain != null && updatedChildren.contains(processedMain)) {
							// Main survived, control was removed → `disentangle(main, ∅) = main`
							// (matches RangeIndex.createDisentangleFormulaIfNecessary semantics).
							formulaToStore = processedMain;
						} else {
							// Control survived, main was removed → nothing to disentangle → drop
							formulaToStore = null;
						}
					}
				} else {
					// recreate parent formula with new children
					final Formula recreated = formula.getCloneWithInnerFormulas(
						updatedChildren.toArray(Formula[]::new)
					);
					if (updatedChildren.isEmpty() && recreated == EmptyFormula.INSTANCE) {
						// The wrapper signalled (via the EmptyFormula-on-empty convention shared by
						// AndFormula/OrFormula/UserFilterFormula/ScopeContainerFormula/etc.) that with no
						// children it has no meaningful representation. In a strip-clone context this means
						// "drop me as the identity element of my parent" rather than letting EmptyFormula
						// propagate up as the absorbing element through the surrounding AND/OR chain.
						// Wrappers that genuinely want to remain when stripped (e.g. FacetGroupOrFormula,
						// FacetGroupAndFormula) opt out simply by not returning EmptyFormula on empty input.
						formulaToStore = null;
					} else {
						formulaToStore = recreated;
					}
				}
			} else {
				formulaToStore = mutatedFormula;
			}

			if (formulaToStore != null) {
				this.formulasProcessed.put(formula, formulaToStore);
				storeFormula(formulaToStore);
			}
		}
	}

	/**
	 * Creates new level in the stack.
	 * The method is created just for the purpose of overloading it by descendants.
	 *
	 * @see io.evitadb.core.cache.FormulaCacheVisitor
	 */
	protected void pushContext(@Nonnull Deque<SubTree> stack, @Nonnull Formula formula) {
		stack.push(new DefaultSubTree());
	}

	/**
	 * Removes topmost level from the stack.
	 * The method is created just for the purpose of overloading it by descendants.
	 *
	 * @see io.evitadb.core.cache.FormulaCacheVisitor
	 */
	protected SubTree popContext(@Nonnull Deque<SubTree> stack) {
		return stack.pop();
	}

	/**
	 * Stores the given formula in the resultClone field if the treeStack is empty,
	 * otherwise adds the formula to the top element of the treeStack.
	 *
	 * @param formula the formula to be stored or added into the current tree structure, must not be null
	 */
	protected void storeFormula(@Nonnull Formula formula) {
		// store updated formula
		if (this.treeStack.isEmpty()) {
			this.resultClone = formula;
		} else {
			this.treeStack.peek().add(formula);
		}
	}

	/**
	 * This interface describes the minimal contract that needs to be fulfilled by a DTO that is used in {@link #treeStack}.
	 */
	protected interface SubTree {

		/**
		 * Adds new formula to the list of {@link #getChildren()}.
		 */
		void add(@Nonnull Formula formula);

		/**
		 * Returns the children registered by {@link #add(Formula)}
		 */
		@Nonnull
		Set<Formula> getChildren();

	}

	/**
	 * Default implementation of {@link SubTree} contract used in the formula cloner.
	 */
	private static class DefaultSubTree implements SubTree {
		@Getter private final Set<Formula> children = new LinkedHashSet<>(16);

		@Override
		public void add(@Nonnull Formula formula) {
			this.children.add(formula);
		}

	}

}
