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
import java.math.MathContext;

/**
 * The LogOperator class implements the {@link ExpressionNode} interface and represents the logarithmic function
 * in the expression tree. This class wraps another ExpressionNode and computes the natural logarithm of its
 * computed value.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@EqualsAndHashCode
public class LogOperator implements ExpressionNode {
	@Serial private static final long serialVersionUID = -5536690356448679153L;
	private final ExpressionNode operator;

	public LogOperator(@Nonnull ExpressionNode operator) {
		Assert.isTrue(
			operator != null,
			() -> new ParserException("Log function must have exactly one operand!")
		);
		this.operator = operator;
	}

	@Nonnull
	@Override
	public BigDecimal compute(@Nonnull PredicateEvaluationContext context) {
		final BigDecimal number = this.operator.compute(context, BigDecimal.class);
		return new BigDecimal(Math.log(number.doubleValue()), MathContext.DECIMAL64);
	}

	@Nonnull
	@Override
	public BigDecimalNumberRange determinePossibleRange() throws UnsupportedDataTypeException {
		return ExpressionNode.transform(
			this.operator.determinePossibleRange(),
			number -> new BigDecimal(Math.log(number.doubleValue()), MathContext.DECIMAL64)
		);
	}

	@Override
	public String toString() {
		return "log(" + this.operator.toString() + ")";
	}
}
