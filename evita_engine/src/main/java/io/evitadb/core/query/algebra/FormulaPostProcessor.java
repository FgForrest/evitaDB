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

package io.evitadb.core.query.algebra;

import javax.annotation.Nonnull;

/**
 * Formula post processor is used to transform final {@link Formula} tree constructed in {@link FormulaVisitor} before
 * computing the result. Post processors should analyze created tree and optimize it to achieve maximal impact
 * of memoization process or limit the scope of processed records as soon as possible. We may take advantage of
 * transitivity in boolean algebra to exchange formula placement the way it's most performant.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface FormulaPostProcessor extends FormulaVisitor {

	/**
	 * Returns processed - optimized formula tree. Prior to calling this method {@link Formula#accept(FormulaVisitor)}
	 * is required to be called with this visitor.
	 *
	 * Method execution nullifies the produced result.
	 */
	@Nonnull
	Formula getPostProcessedFormula();

}
