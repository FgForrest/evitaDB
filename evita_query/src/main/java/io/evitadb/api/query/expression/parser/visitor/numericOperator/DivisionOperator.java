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

package io.evitadb.api.query.expression.parser.visitor.numericOperator;


import io.evitadb.api.query.expression.exception.ParserException;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.exception.UnsupportedDataTypeException;
import io.evitadb.dataType.expression.ExpressionNode;
import io.evitadb.dataType.expression.PredicateEvaluationContext;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;

/**
 * Class that represents a division operation for multiple operands. It ensures there are at least
 * two operands and computes the result by sequentially dividing the operands.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@EqualsAndHashCode
public class DivisionOperator implements ExpressionNode {
	@Serial private static final long serialVersionUID = 2609645242654230184L;
	private final ExpressionNode[] operator;

	public DivisionOperator(ExpressionNode[] operator) {
		Assert.isTrue(
			operator.length >= 2,
			() -> new ParserException("Division function must have at least two operands!")
		);
		this.operator = operator;
	}

	@Nonnull
	@Override
	public BigDecimal compute(@Nonnull PredicateEvaluationContext context) {
		final BigDecimal initial = this.operator[0].compute(context, BigDecimal.class);
		return Arrays.stream(this.operator, 1, this.operator.length)
			.map(op -> op.compute(context, BigDecimal.class))
			.reduce(initial, DivisionOperator::divide);
	}

	@Nonnull
	@Override
	public BigDecimalNumberRange determinePossibleRange() throws UnsupportedDataTypeException {
		return Arrays.stream(this.operator)
			.map(ExpressionNode::determinePossibleRange)
			.reduce((a, b) -> ExpressionNode.combine(a, b, DivisionOperator::divide))
			.orElseThrow();
	}

	@Nonnull
	private static BigDecimal divide(@Nonnull BigDecimal a, @Nonnull BigDecimal b) {
		if (b.equals(BigDecimal.ZERO)) {
			throw new ArithmeticException("Division by zero");
		}
		// we need to automatically switch to float values when necessary
		return a.divide(b, 16, RoundingMode.HALF_UP).stripTrailingZeros();
	}

	@Override
	public String toString() {
		return Arrays.stream(this.operator)
			.map(ExpressionNode::toString)
			.reduce((a, b) -> a + " / " + b)
			.orElseThrow();
	}

}
