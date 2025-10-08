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

package io.evitadb.core.query.extraResult.translator.utils;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.FormulaVisitor;
import io.evitadb.core.query.algebra.base.NotFormula;
import io.evitadb.core.query.algebra.facet.UserFilterFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.utils.ArrayUtils;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.function.Predicate;

/**
 * This method reconstruct the input query into a form that returns the same result, but is more optimal regarding
 * memoization function so that the most of the joins are executed only once and reused for recurring computations.
 *
 * Giving this input:
 *
 * ROOT
 * - filter 1
 * - filter 2
 * - filter 3
 * - looked up formula
 *
 * This output is created:
 *
 * ROOT
 * - AND formula container (this result is computed once and reused)
 * -   - filter 1
 * -   - filter 2
 * -   - filter 3
 * - looked up formula
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class AbstractFormulaStructureOptimizeVisitor implements FormulaVisitor {
	/**
	 * Contains the logic that identifies the formula we optimize formula tree for.
	 */
	private final Predicate<Formula> matchingPredicate;
	/**
	 * Stack serves internally to collect the cloned tree of formulas.
	 */
	private final Deque<CompositeObjectArray<Formula>> levelStack = new ArrayDeque<>(16);
	/**
	 * Stack contains parent path that is valid for currently examined formula.
	 */
	private final Deque<Formula> parentStack = new ArrayDeque<>(16);
	/**
	 * Set contains all formula container, that should be optimized.
	 */
	private final Deque<Formula> optimizationSet = new ArrayDeque<>(16);
	/**
	 * Result optimized form of formula.
	 */
	@Getter private Formula result;

	@Override
	public void visit(@Nonnull Formula formula) {
		if (this.matchingPredicate.test(formula)) {
			// we found the formula - add formula parent stack to set of optimized formulas
			this.optimizationSet.addAll(this.parentStack);
		}

		final Formula[] updatedChildren;
		this.parentStack.push(formula);
		this.levelStack.push(new CompositeObjectArray<>(Formula.class));

		try {
			for (Formula innerFormula : formula.getInnerFormulas()) {
				innerFormula.accept(this);
			}
		} finally {
			this.parentStack.pop();
			updatedChildren = this.levelStack.pop().toArray();
		}

		// we found formula to optimize for - duplicate current container with separate sub container
		// for all other formulas except the one we optimize for
		if (this.optimizationSet.contains(formula) && !(formula instanceof NotFormula) && updatedChildren.length > 2) {

			final CompositeObjectArray<Formula> newDivertedFormulas = new CompositeObjectArray<>(Formula.class);
			final CompositeObjectArray<Formula> matchingFormulas = new CompositeObjectArray<>(Formula.class);
			for (Formula innerFormula : updatedChildren) {
				if (!(this.matchingPredicate.test(innerFormula)) && !this.optimizationSet.contains(innerFormula)) {
					newDivertedFormulas.add(innerFormula);
				} else {
					matchingFormulas.add(innerFormula);
				}
			}
			final Formula[] divertedFormulasResult = newDivertedFormulas.toArray();
			final Formula[] matchingFormulasResult = matchingFormulas.toArray();

			if (ArrayUtils.isEmpty(divertedFormulasResult) || ArrayUtils.isEmpty(matchingFormulasResult)) {
				if (isAnyChildrenExchanged(formula, updatedChildren)) {
					storeFormula(
						formula.getCloneWithInnerFormulas(updatedChildren)
					);
				} else {
					storeFormula(formula);
				}
			} else if (formula instanceof UserFilterFormula) {
				replaceFormula(
					formula.getCloneWithInnerFormulas(
						FormulaFactory.and(divertedFormulasResult),
						FormulaFactory.and(matchingFormulasResult)
					)
				);
			} else {
				replaceFormula(
					formula.getCloneWithInnerFormulas(
						formula.getCloneWithInnerFormulas(divertedFormulasResult),
						FormulaFactory.and(matchingFormulasResult)
					)
				);
			}
		} else if (isAnyChildrenExchanged(formula, updatedChildren)) {
			storeFormula(
				formula.getCloneWithInnerFormulas(updatedChildren)
			);
		} else {
			// use entire formula tree block
			storeFormula(formula);
		}
	}

	/*
		PRIVATE METHODS
	 */

	private boolean isAnyChildrenExchanged(Formula formula, Formula[] updatedChildren) {
		return updatedChildren.length != formula.getInnerFormulas().length ||
			Arrays.stream(formula.getInnerFormulas()).anyMatch(examinedFormula -> !ArrayUtils.contains(updatedChildren, examinedFormula));
	}

	private void storeFormula(Formula formula) {
		// store updated formula
		if (this.levelStack.isEmpty()) {
			this.result = formula;
		} else {
			this.levelStack.peek().add(formula);
		}
	}

	private void replaceFormula(Formula formula) {
		// store updated formula
		if (this.levelStack.isEmpty()) {
			this.result = formula;
		} else {
			this.levelStack.peek().add(formula);
			this.optimizationSet.add(formula);
		}
	}

}
