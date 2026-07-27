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


import io.evitadb.api.query.expression.AbstractBinaryOperator;
import io.evitadb.api.query.expression.evaluate.PossibleRange;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.exception.UnsupportedDataTypeException;
import io.evitadb.dataType.expression.ExpressionEvaluationContext;
import io.evitadb.dataType.expression.ExpressionNode;

import lombok.EqualsAndHashCode;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.math.BigDecimal;

/**
 * The ModOperator class implements the ExpressionNode interface and represents a modulo operation
 * applied to two operands and performs the modulo computation with BigDecimal values.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@EqualsAndHashCode(callSuper = true)
public class ModuloOperator extends AbstractBinaryOperator {
	@Serial private static final long serialVersionUID = -3399862430415797098L;

	public ModuloOperator(@Nonnull ExpressionNode leftOperator, @Nonnull ExpressionNode rightOperator) {
		super(leftOperator, rightOperator);
	}

	@Nonnull
	@Override
	protected String getOperatorSymbol() {
		return "%";
	}

	@Nonnull
	@Override
	public BigDecimal compute(@Nonnull ExpressionEvaluationContext context) {
		final BigDecimal leftOperand = computeLeft(context, BigDecimal.class);
		final BigDecimal rightOperand = computeRight(context, BigDecimal.class);
		return modulo(leftOperand, rightOperand);
	}

	@Nonnull
	@Override
	public BigDecimalNumberRange determinePossibleRange() throws UnsupportedDataTypeException {
		return PossibleRange.combine(
			getLeftOperand().determinePossibleRange(),
			getRightOperand().determinePossibleRange(),
			ModuloOperator::modulo
		);
	}

	@Nonnull
	private static BigDecimal modulo(@Nonnull BigDecimal a, @Nonnull BigDecimal b) {
		if (b.equals(BigDecimal.ZERO)) {
			throw new ArithmeticException("Division by zero");
		}
		return a.remainder(b);
	}

}
