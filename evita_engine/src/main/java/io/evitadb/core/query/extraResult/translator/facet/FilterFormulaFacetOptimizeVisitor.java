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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.query.extraResult.translator.facet;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.facet.FacetGroupFormula;
import io.evitadb.core.query.algebra.facet.UserFilterFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.extraResult.translator.utils.AbstractFormulaStructureOptimizeVisitor;

import javax.annotation.Nonnull;

/**
 * This method reconstruct the input query into a form that returns the same result, but is more optimal regarding
 * memoization function so that the most of the joins are executed only once and reused for facet recurring computation.
 *
 * Giving this input:
 *
 * ROOT
 * - filter 1
 * - filter 2
 * - filter 3
 * - facet group formula
 *
 * This output is created:
 *
 * ROOT
 * - AND formula container (this result is computed once and reused)
 * -   - filter 1
 * -   - filter 2
 * -   - filter 3
 * - facet group formula
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class FilterFormulaFacetOptimizeVisitor extends AbstractFormulaStructureOptimizeVisitor {
	private boolean userFormulaFound;

	private FilterFormulaFacetOptimizeVisitor() {
		super(FacetGroupFormula.class::isInstance);
	}

	@Override
	public void visit(@Nonnull Formula formula) {
		if (formula instanceof UserFilterFormula) {
			this.userFormulaFound = true;
		}
		super.visit(formula);
	}

	/**
	 * Preferred way of invoking this visitor. Accepts formula (tree) and produces optimized form of the formula where
	 * the base filter is pre-aggregated in separate container which result can be memoized.
	 */
	public static Formula optimize(@Nonnull Formula inputFormula) {
		final FilterFormulaFacetOptimizeVisitor visitor = new FilterFormulaFacetOptimizeVisitor();
		inputFormula.accept(visitor);
		return visitor.userFormulaFound ?
			visitor.getResult() : FormulaFactory.and(visitor.getResult(), new UserFilterFormula());
	}
}
