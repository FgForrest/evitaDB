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

import io.evitadb.core.query.filter.translator.FilteringConstraintTranslator;
import io.evitadb.core.query.response.TransactionalDataRelatedStructure;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.PrettyPrintable;

import javax.annotation.Nonnull;

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
 * There costs are derived from {@link #getOperationCost()} that was measured by performance tests on random numbers
 * and the amount of data processed by the formula.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface Formula extends TransactionalDataRelatedStructure, PrettyPrintable {

	Formula[] EMPTY_FORMULA_ARRAY = new Formula[0];

	/**
	 * Traverses formula tree with passed visitor.
	 */
	void accept(@Nonnull FormulaVisitor visitor);

	/**
	 * Computes product of this formula. The result is cached so multiple calls on this method will pay the cost only
	 * for the first time.
	 */
	@Nonnull
	Bitmap compute();

	/**
	 * Returns copy of this formula with replaced inner formulas.
	 */
	@Nonnull
	Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas);

	/**
	 * Returns inner formulas this formula {@link #compute()} builds upon.
	 */
	@Nonnull
	Formula[] getInnerFormulas();

	/**
	 * Returns the cardinality estimate of {@link #compute()} method without really computing the result. The estimate
	 * will not be precise but differs between AND/OR relations and helps us to compute {@link #getEstimatedCost()}.
	 */
	int getEstimatedCardinality();

	/**
	 * Clears the memoized results and hashes of the formula.
	 */
	void clearMemory();

	/**
	 * Prints information about the formula in a user-friendly way in verbose mode.
	 */
	@Nonnull
	String toStringVerbose();

}
