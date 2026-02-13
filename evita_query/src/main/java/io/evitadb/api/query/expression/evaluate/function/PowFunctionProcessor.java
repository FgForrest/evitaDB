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

package io.evitadb.api.query.expression.evaluate.function;

import io.evitadb.api.query.expression.evaluate.possibleRange.PossibleRange;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.exception.UnsupportedDataTypeException;
import io.evitadb.exception.ExpressionEvaluationException;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * Function processor for the `pow` function that raises the first numeric argument
 * to the power of the second numeric argument (which must be an integer).
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public class PowFunctionProcessor extends AbstractMathFunctionProcessor {

	@Nonnull
	@Override
	public String getName() {
		return "pow";
	}

	@Nonnull
	@Override
	public Serializable process(@Nonnull List<Serializable> arguments) throws ExpressionEvaluationException {
		requireArgumentCount(arguments, 2);
		final BigDecimal base = toBigDecimal(arguments.get(0), "base");
		final BigDecimal exponent = toBigDecimal(arguments.get(1), "exponent");
		try {
			return base.pow(exponent.intValueExact());
		} catch (ArithmeticException e) {
			throw new ExpressionEvaluationException(
				"Function `pow` requires the exponent to be an integer, " +
					"but got `" + exponent + "`.",
				"Function `pow` requires the exponent to be an integer.",
				e
			);
		}
	}

	@Nonnull
	@Override
	public BigDecimalNumberRange determinePossibleRange(
		@Nonnull List<BigDecimalNumberRange> argumentPossibleRanges
	) throws UnsupportedDataTypeException {
		return PossibleRange.combine(
			argumentPossibleRanges.get(0),
			argumentPossibleRanges.get(1),
			(a, b) -> a.pow(b.intValueExact())
		);
	}
}
