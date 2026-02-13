/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.api.query.expression.function.processor;

import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.exception.ExpressionEvaluationException;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Abstract base class for math function processors providing common argument
 * validation and type conversion utilities.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
abstract class AbstractMathFunctionProcessor implements NumericFunctionProcessor {

	/**
	 * Validates that the argument list has exactly the expected number of arguments.
	 *
	 * @param arguments the list of arguments to validate
	 * @param expectedCount the expected number of arguments
	 * @throws ExpressionEvaluationException if the argument count does not match
	 */
	protected void requireArgumentCount(
		@Nonnull List<Serializable> arguments,
		int expectedCount
	) {
		if (arguments.size() != expectedCount) {
			throw new ExpressionEvaluationException(
				"Function `" + getName() + "` requires exactly " + expectedCount +
					" argument(s), but got " + arguments.size() + "."
			);
		}
	}

	/**
	 * Validates that the argument list has a number of arguments within the expected range.
	 *
	 * @param arguments the list of arguments to validate
	 * @param minCount the minimum number of arguments (inclusive)
	 * @param maxCount the maximum number of arguments (inclusive)
	 * @throws ExpressionEvaluationException if the argument count is outside the range
	 */
	protected void requireArgumentCountBetween(
		@Nonnull List<Serializable> arguments,
		int minCount,
		int maxCount
	) {
		if (arguments.size() < minCount || arguments.size() > maxCount) {
			throw new ExpressionEvaluationException(
				"Function `" + getName() + "` requires between " + minCount +
					" and " + maxCount + " argument(s), but got " + arguments.size() + "."
			);
		}
	}

	/**
	 * Converts the given argument to a {@link BigDecimal}, validating that it is non-null
	 * and a valid number.
	 *
	 * @param argument the argument to convert
	 * @param argumentName the name of the argument for error messages
	 * @return the converted BigDecimal value
	 * @throws ExpressionEvaluationException if the argument is null or not a number
	 */
	@Nonnull
	protected BigDecimal toBigDecimal(
		@Nonnull Serializable argument,
		@Nonnull String argumentName
	) {
		if (!(argument instanceof Number)) {
			throw new ExpressionEvaluationException(
				"Function `" + getName() + "` requires " + argumentName +
					" to be a number, but got `" + argument.getClass().getSimpleName() + "`."
			);
		}
		return Objects.requireNonNull(EvitaDataTypes.toTargetType(argument, BigDecimal.class));
	}

	/**
	 * Converts the given argument to a {@link Long}, validating that it is non-null
	 * and a valid number.
	 *
	 * @param argument the argument to convert
	 * @param argumentName the name of the argument for error messages
	 * @return the converted Long value
	 * @throws ExpressionEvaluationException if the argument is null or not a number
	 */
	@Nonnull
	protected Long toLong(
		@Nonnull Serializable argument,
		@Nonnull String argumentName
	) {
		if (!(argument instanceof Number)) {
			throw new ExpressionEvaluationException(
				"Function `" + getName() + "` requires " + argumentName +
					" to be a number, but got `" + argument.getClass().getSimpleName() + "`."
			);
		}
		return Objects.requireNonNull(EvitaDataTypes.toTargetType(argument, Long.class));
	}
}
