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

package io.evitadb.dataType.expression;

import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.exception.UnsupportedDataTypeException;
import io.evitadb.exception.ExpressionEvaluationException;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

/**
 * Atomic data structure for {@link Expression} evaluation. It represents a single node (operator or operand) in
 * the expression tree.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface ExpressionNode extends Serializable {

	/**
	 * Combines two BigDecimalNumberRange instances into a new range using a specified combiner function.
	 *
	 * @param a the first BigDecimalNumberRange instance to be combined
	 * @param b the second BigDecimalNumberRange instance to be combined
	 * @param combiner a BinaryOperator to combine the BigDecimal values from each range
	 * @return a BigDecimalNumberRange instance resulting from the combination of the input ranges
	 */
	@Nonnull
	static BigDecimalNumberRange combine(
		@Nonnull BigDecimalNumberRange a,
		@Nonnull BigDecimalNumberRange b,
		@Nonnull BinaryOperator<BigDecimal> combiner
	) {
		final BigDecimal left = a.getPreciseFrom() == null || b.getPreciseFrom() == null
			? null
			: combiner.apply(a.getPreciseFrom(), b.getPreciseFrom());
		final BigDecimal right = a.getPreciseTo() == null || b.getPreciseTo() == null
			? null
			: combiner.apply(a.getPreciseTo(), b.getPreciseTo());
		if (left == null && right == null) {
			return BigDecimalNumberRange.INFINITE;
		} else if (left == null) {
			return BigDecimalNumberRange.to(right);
		} else if (right == null) {
			return BigDecimalNumberRange.from(left);
		} else {
			return BigDecimalNumberRange.between(left, right);
		}
	}

	/**
	 * Transforms the given BigDecimalNumberRange instance using a specified UnaryOperator.
	 *
	 * @param range the BigDecimalNumberRange instance to be transformed
	 * @param transformer a UnaryOperator to transform the BigDecimal values within the range
	 * @return a new BigDecimalNumberRange instance resulting from the transformation of the input range
	 */
	@Nonnull
	static BigDecimalNumberRange transform(
		@Nonnull BigDecimalNumberRange range,
		@Nonnull UnaryOperator<BigDecimal> transformer
	) {
		final BigDecimal left = range.getPreciseFrom() == null ? null : transformer.apply(range.getPreciseFrom());
		final BigDecimal right = range.getPreciseTo() == null ? null : transformer.apply(range.getPreciseTo());

		if (left == null && right == null) {
			return BigDecimalNumberRange.INFINITE;
		} else if (left == null) {
			return BigDecimalNumberRange.to(right);
		} else if (right == null) {
			return BigDecimalNumberRange.from(left);
		} else {
			return BigDecimalNumberRange.between(left, right);
		}
	}

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
		return Objects.requireNonNull(EvitaDataTypes.toTargetType(compute(context), clazz));
	}

	/**
	 * Determines the possible numeric range of values for this expression node. In other words tries to identify
	 * the minimum and maximum values that the expression can evaluate to (if it's possible to determine them).
	 * This method can be used to limit the range of tested variable values in order to optimize the evaluation
	 * of the expression.
	 *
	 * @return a BigDecimalNumberRange representing the possible range of values
	 * @throws UnsupportedDataTypeException if the data type is not supported
	 */
	@Nonnull
	BigDecimalNumberRange determinePossibleRange() throws UnsupportedDataTypeException;

}