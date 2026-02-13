/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

package io.evitadb.api.query.expression.numeric;


import io.evitadb.api.query.expression.evaluate.PossibleRange;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.exception.UnsupportedDataTypeException;
import io.evitadb.dataType.expression.ExpressionEvaluationContext;
import io.evitadb.dataType.expression.ExpressionNode;
import io.evitadb.exception.ExpressionEvaluationException;
import lombok.EqualsAndHashCode;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.math.BigDecimal;

/**
 * The MultiplyOperator class represents an operation that performs multiplication on two operands.
 * It implements the ExpressionNode interface which defines the method to compute the result based on a given context.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@EqualsAndHashCode
public class MultiplicationOperator implements ExpressionNode {
	@Serial private static final long serialVersionUID = -1457151698730020222L;
	private final ExpressionNode leftOperator;
	private final ExpressionNode rightOperator;

	public MultiplicationOperator(@Nonnull ExpressionNode leftOperator, @Nonnull ExpressionNode rightOperator) {
		this.leftOperator = leftOperator;
		this.rightOperator = rightOperator;
	}

	@Nonnull
	@Override
	public BigDecimal compute(@Nonnull ExpressionEvaluationContext context) {
		final BigDecimal leftOperand = this.leftOperator.compute(context, BigDecimal.class);
		if (leftOperand == null) {
			throw new ExpressionEvaluationException("Left operand is required, but evaluated to null.");
		}
		final BigDecimal rightOperand = this.rightOperator.compute(context, BigDecimal.class);
		if (rightOperand == null) {
			throw new ExpressionEvaluationException("Right operand is required, but evaluated to null.");
		}
		return leftOperand.multiply(rightOperand);
	}

	@Nonnull
	@Override
	public BigDecimalNumberRange determinePossibleRange() throws UnsupportedDataTypeException {
		return PossibleRange.combine(
			this.leftOperator.determinePossibleRange(),
			this.rightOperator.determinePossibleRange(),
			BigDecimal::multiply
		);
	}

	@Override
	public String toString() {
		return this.leftOperator + " * " + this.rightOperator;
	}

}
