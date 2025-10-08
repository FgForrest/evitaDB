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
import java.util.Arrays;

/**
 * The PowOperator class implements the ExpressionNode interface to perform
 * exponentiation operations. It requires at least two operands and
 * calculates the power by taking the first operand raised to the power
 * of the subsequent operands.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@EqualsAndHashCode
public class PowOperator implements ExpressionNode {
	@Serial private static final long serialVersionUID = -7741208376354800733L;
	private final ExpressionNode[] operator;

	public PowOperator(ExpressionNode[] operator) {
		Assert.isTrue(
			operator.length >= 2,
			() -> new ParserException("Power function must have at least two operands!")
		);
		this.operator = operator;
	}

	@Nonnull
	@Override
	public BigDecimal compute(@Nonnull PredicateEvaluationContext context) {
		final BigDecimal initial = this.operator[0].compute(context, BigDecimal.class);
		return Arrays.stream(this.operator, 1, this.operator.length)
			.map(op -> op.compute(context, BigDecimal.class))
			.reduce(initial, PowOperator::pow);
	}

	@Nonnull
	@Override
	public BigDecimalNumberRange determinePossibleRange() throws UnsupportedDataTypeException {
		return Arrays.stream(this.operator)
			.map(ExpressionNode::determinePossibleRange)
			.reduce((a, b) -> ExpressionNode.combine(a, b, PowOperator::pow))
			.orElseThrow();
	}

	@Nonnull
	private static BigDecimal pow(@Nonnull BigDecimal a, @Nonnull BigDecimal b) {
		return a.pow(b.intValueExact());
	}

	@Override
	public String toString() {
		return Arrays.stream(this.operator)
			.map(ExpressionNode::toString)
			.reduce((a, b) -> a + " ^ " + b)
			.orElseThrow();
	}

}
