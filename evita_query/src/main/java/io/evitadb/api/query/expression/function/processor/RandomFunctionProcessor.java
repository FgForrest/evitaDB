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
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Function processor for the `random` function that generates random long values.
 * With no arguments, it generates an unbounded random long. With one argument,
 * it generates a random long in the range [0, bound).
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public class RandomFunctionProcessor extends AbstractMathFunctionProcessor {

	@Nonnull
	@Override
	public String getName() {
		return "random";
	}

	@Nonnull
	@Override
	public Serializable process(@Nonnull List<Serializable> arguments) throws ExpressionEvaluationException {
		requireArgumentCountBetween(arguments, 0, 1);
		if (arguments.isEmpty()) {
			return ThreadLocalRandom.current().nextLong();
		} else {
			final Long bound = toLong(arguments.get(0), "bound");
			return ThreadLocalRandom.current().nextLong(bound);
		}
	}

	@Nonnull
	@Override
	public BigDecimalNumberRange determinePossibleRange(
		@Nonnull List<BigDecimalNumberRange> argumentPossibleRanges
	) throws UnsupportedDataTypeException {
		return BigDecimalNumberRange.INFINITE;
	}
}
