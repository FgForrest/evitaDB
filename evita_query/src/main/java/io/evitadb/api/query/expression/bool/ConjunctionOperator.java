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

package io.evitadb.api.query.expression.bool;


import io.evitadb.api.query.expression.AbstractBinaryOperator;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.exception.UnsupportedDataTypeException;
import io.evitadb.dataType.expression.ExpressionEvaluationContext;
import io.evitadb.dataType.expression.ExpressionNode;
import lombok.EqualsAndHashCode;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * The ConjunctionOperator class represents a logical AND operation over two operators.
 * It computes both operator values and returns true only if both individual operator computations return true.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@EqualsAndHashCode(callSuper = true)
public class ConjunctionOperator extends AbstractBinaryOperator implements BooleanOperator {
	@Serial private static final long serialVersionUID = 8865132783193638404L;

	public ConjunctionOperator(@Nonnull ExpressionNode leftOperator, @Nonnull ExpressionNode rightOperator) {
		super(leftOperator, rightOperator);
	}

	@Nonnull
	@Override
	protected String getOperatorSymbol() {
		return "&&";
	}

	@Nonnull
	@Override
	public Boolean compute(@Nonnull ExpressionEvaluationContext context) {
		final Boolean leftOperand = computeLeft(context, Boolean.class);
		final Boolean rightOperand = computeRight(context, Boolean.class);
		return leftOperand && rightOperand;
	}

	@Nonnull
	@Override
	public BigDecimalNumberRange determinePossibleRange() throws UnsupportedDataTypeException {
		return BigDecimalNumberRange.intersect(
			getLeftOperand().determinePossibleRange(),
			getRightOperand().determinePossibleRange()
		);
	}

}
