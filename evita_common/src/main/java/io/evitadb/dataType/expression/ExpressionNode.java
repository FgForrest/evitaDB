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
 * Represents a single node in an {@link Expression} tree. A node can be either an operand (constant, variable)
 * or an operator (arithmetic, comparison, logical) that combines child nodes. Each node knows how to
 * {@link #compute(PredicateEvaluationContext) compute} its value within a given evaluation context and how to
 * {@link #determinePossibleRange() determine} the possible range of its output values.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface ExpressionNode extends Serializable {

	/**
	 * Combines two {@link BigDecimalNumberRange} instances into a new range by applying a combiner function
	 * to their respective bounds. If either bound of the input ranges is `null` (representing infinity),
	 * the corresponding bound of the result is also `null` (infinite).
	 *
	 * @param a        the first range to combine
	 * @param b        the second range to combine
	 * @param combiner function applied to corresponding bounds of both ranges
	 * @return a new range produced by combining the bounds of `a` and `b`
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
	 * Transforms a {@link BigDecimalNumberRange} by applying a transformer function to each of its bounds.
	 * If a bound is `null` (representing infinity), it remains `null` in the result.
	 *
	 * @param range       the range to transform
	 * @param transformer function applied to each non-null bound of the range
	 * @return a new range with transformed bounds
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
	 * @param context the evaluation context providing variable bindings and a random number generator
	 * @return the result of the computation as a Serializable value
	 * @throws ExpressionEvaluationException if an error occurs during expression evaluation
	 */
	@Nonnull
	Serializable compute(@Nonnull PredicateEvaluationContext context) throws ExpressionEvaluationException;

	/**
	 * Computes the result of evaluating this expression node within the given context and converts it to the specified
	 * target type using {@link EvitaDataTypes#toTargetType(Serializable, Class)}.
	 *
	 * @param context the evaluation context providing variable bindings and a random number generator
	 * @param clazz   the target type to which the computed result should be converted
	 * @return the computed result converted to the requested type
	 * @throws ExpressionEvaluationException if an error occurs during expression evaluation
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