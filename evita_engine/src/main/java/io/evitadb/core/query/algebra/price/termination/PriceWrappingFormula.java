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

package io.evitadb.core.query.algebra.price.termination;

import io.evitadb.core.query.algebra.Formula;

import javax.annotation.Nonnull;

/**
 * This interface allows wrapping price related formula into a "recognized" container that is uniquely represented by
 * {@link PriceEvaluationContext} multiple price wrapping containers that share this evaluation context are
 * interchangeable one for another.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface PriceWrappingFormula extends Formula {

	/**
	 * Returns price evaluation context allowing to optimize formula tree in the such way, that terminating formula
	 * with same context will be replaced by single instance - taking advantage of result memoization.
	 */
	@Nonnull
	PriceEvaluationContext getPriceEvaluationContext();

}
