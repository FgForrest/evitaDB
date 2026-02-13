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

import io.evitadb.api.query.expression.evaluate.PossibleRange;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.exception.UnsupportedDataTypeException;
import io.evitadb.exception.ExpressionEvaluationException;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Function processor for the `ceil` function that computes the ceiling value
 * (smallest integer greater than or equal to) of a single numeric argument.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public class CeilFunctionProcessor extends AbstractMathFunctionProcessor {

	@Nonnull
	@Override
	public String getName() {
		return "ceil";
	}

	@Nonnull
	@Override
	public Serializable process(@Nonnull List<Serializable> arguments) throws ExpressionEvaluationException {
		requireArgumentCount(arguments, 1);
		final BigDecimal number = toBigDecimal(arguments.get(0), "argument");
		return number.setScale(0, RoundingMode.CEILING);
	}

	@Nonnull
	@Override
	public BigDecimalNumberRange determinePossibleRange(
		@Nonnull List<BigDecimalNumberRange> argumentPossibleRanges
	) throws UnsupportedDataTypeException {
		return PossibleRange.transform(
			argumentPossibleRanges.get(0),
			bd -> bd.setScale(0, RoundingMode.CEILING)
		);
	}
}
