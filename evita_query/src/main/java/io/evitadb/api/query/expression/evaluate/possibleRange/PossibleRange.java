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

package io.evitadb.api.query.expression.evaluate.possibleRange;

import io.evitadb.dataType.BigDecimalNumberRange;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

/**
 * Helps with computing possible numeric ranges.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PossibleRange {

	/**
	 * Combines two BigDecimalNumberRange instances into a new range using a specified combiner function.
	 *
	 * @param a the first BigDecimalNumberRange instance to be combined
	 * @param b the second BigDecimalNumberRange instance to be combined
	 * @param combiner a BinaryOperator to combine the BigDecimal values from each range
	 * @return a BigDecimalNumberRange instance resulting from the combination of the input ranges
	 */
	@Nonnull
	public static BigDecimalNumberRange combine(
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
	public static BigDecimalNumberRange transform(
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
}
