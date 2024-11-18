/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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
 * The ModOperator class implements the ExpressionNode interface and represents a modulo operation
 * applied to a sequence of operands. The class ensures that at least two operands
 * are provided for the operation and performs the modulo computation with BigDecimal values.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@EqualsAndHashCode
public class ModOperator implements ExpressionNode {
	@Serial private static final long serialVersionUID = -3399862430415797098L;
	private final ExpressionNode[] operator;

	public ModOperator(ExpressionNode[] operator) {
		Assert.isTrue(
			operator.length >= 2,
			() -> new ParserException("Division function must have at least two operands!")
		);
		this.operator = operator;
	}

	@Nonnull
	@Override
	public BigDecimal compute(@Nonnull PredicateEvaluationContext context) {
		final BigDecimal initial = operator[0].compute(context, BigDecimal.class);
		return Arrays.stream(operator, 1, operator.length)
			.map(op -> op.compute(context, BigDecimal.class))
			.reduce(initial, ModOperator::modulo);
	}

	@Nonnull
	@Override
	public BigDecimalNumberRange determinePossibleRange() throws UnsupportedDataTypeException {
		return Arrays.stream(operator)
			.map(ExpressionNode::determinePossibleRange)
			.reduce((a, b) -> ExpressionNode.combine(a, b, ModOperator::modulo))
			.orElseThrow();
	}

	@Nonnull
	private static BigDecimal modulo(@Nonnull BigDecimal a, @Nonnull BigDecimal b) {
		if (b.equals(BigDecimal.ZERO)) {
			throw new ArithmeticException("Division by zero");
		}
		return a.remainder(b);
	}

	@Override
	public String toString() {
		return Arrays.stream(operator)
			.map(ExpressionNode::toString)
			.reduce((a, b) -> a + " % " + b)
			.orElseThrow();
	}

}
