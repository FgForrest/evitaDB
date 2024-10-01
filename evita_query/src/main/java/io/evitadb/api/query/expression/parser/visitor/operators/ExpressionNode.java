/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.api.query.expression.parser.visitor.operators;


import io.evitadb.api.query.expression.Expression;
import io.evitadb.api.query.expression.parser.evaluate.PredicateEvaluationContext;
import io.evitadb.api.query.expression.parser.exception.ExpressionEvaluationException;
import io.evitadb.dataType.EvitaDataTypes;

import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * Atomic data structure for {@link Expression} evaluation. It represents a single node (operator or operand) in
 * the expression tree.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface ExpressionNode {

	/**
	 * Computes the result of evaluating this expression node within the given context.
	 *
	 * @param context the context in which the predicate is evaluated
	 * @return the result of the computation as a Serializable object
	 * @throws ExpressionEvaluationException if an error occurs during the evaluation of the expression
	 */
	@Nonnull
	Serializable compute(@Nonnull PredicateEvaluationContext context) throws ExpressionEvaluationException;

	/**
	 * Computes the result of evaluating this expression node within the given context and converts it to the specified
	 * class type.
	 *
	 * @param context the context in which the predicate is evaluated
	 * @param clazz the class to which the result should be converted
	 * @return the result of the computation as an object of the specified class
	 * @throws ExpressionEvaluationException if an error occurs during the evaluation of the expression
	 */
	@Nonnull
	default <T extends Serializable> T compute(@Nonnull PredicateEvaluationContext context, @Nonnull Class<T> clazz) throws ExpressionEvaluationException {
		return EvitaDataTypes.toTargetType(compute(context), clazz);
	}

}