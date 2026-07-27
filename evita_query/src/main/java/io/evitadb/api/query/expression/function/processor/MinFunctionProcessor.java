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

import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.exception.UnsupportedDataTypeException;
import io.evitadb.exception.ExpressionEvaluationException;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * Function processor for the `min` function that returns the smaller of two
 * numeric arguments.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public class MinFunctionProcessor extends AbstractMathFunctionProcessor {

	@Nonnull
	@Override
	public String getName() {
		return "min";
	}

	@Nonnull
	@Override
	public Serializable process(@Nonnull List<Serializable> arguments) throws ExpressionEvaluationException {
		requireArgumentCount(arguments, 2);
		final BigDecimal first = toBigDecimal(arguments.get(0), "first argument");
		final BigDecimal second = toBigDecimal(arguments.get(1), "second argument");
		return first.compareTo(second) < 0 ? first : second;
	}

	@Nonnull
	@Override
	public BigDecimalNumberRange determinePossibleRange(
		@Nonnull List<BigDecimalNumberRange> argumentPossibleRanges
	) throws UnsupportedDataTypeException {
		final BigDecimalNumberRange range1 = argumentPossibleRanges.get(0);
		final BigDecimalNumberRange range2 = argumentPossibleRanges.get(1);
		if (range1 == BigDecimalNumberRange.INFINITE || range2 == BigDecimalNumberRange.INFINITE) {
			return BigDecimalNumberRange.INFINITE;
		} else {
			return BigDecimalNumberRange.union(range1, range2);
		}
	}
}
