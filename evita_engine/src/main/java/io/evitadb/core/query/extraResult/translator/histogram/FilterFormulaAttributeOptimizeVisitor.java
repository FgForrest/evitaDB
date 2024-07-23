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

package io.evitadb.core.query.extraResult.translator.histogram;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.attribute.AttributeFormula;
import io.evitadb.core.query.extraResult.translator.utils.AbstractFormulaStructureOptimizeVisitor;

import javax.annotation.Nonnull;
import java.util.Set;

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
 * - attribute formula
 *
 * This output is created:
 *
 * ROOT
 * - AND formula container (this result is computed once and reused)
 * -   - filter 1
 * -   - filter 2
 * -   - filter 3
 * - attribute formula
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class FilterFormulaAttributeOptimizeVisitor extends AbstractFormulaStructureOptimizeVisitor {

	private FilterFormulaAttributeOptimizeVisitor(@Nonnull Set<String> attributeNames) {
		super(
			formula -> formula instanceof AttributeFormula &&
				attributeNames.contains(((AttributeFormula) formula).getAttributeKey())
		);
	}

	/**
	 * Preferred way of invoking this visitor. Accepts formula (tree) and produces optimized form of the formula where
	 * the base filter is pre-aggregated in separate container which result can be memoized.
	 */
	public static Formula optimize(@Nonnull Formula inputFormula, @Nonnull Set<String> attributeNames) {
		final FilterFormulaAttributeOptimizeVisitor visitor = new FilterFormulaAttributeOptimizeVisitor(attributeNames);
		inputFormula.accept(visitor);
		return visitor.getResult();
	}
}
