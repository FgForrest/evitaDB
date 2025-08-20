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
import java.math.RoundingMode;

/**
 * RoundOperator is an implementation of the ExpressionNode interface, responsible for rounding the result
 * of its contained ExpressionNode to a nearest long value.
 *
 *  @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@EqualsAndHashCode
public class RoundOperator implements ExpressionNode {
	@Serial private static final long serialVersionUID = -282278558116650459L;
	private static final MathContext MATH_CONTEXT = new MathContext(1, RoundingMode.HALF_UP);
	private final ExpressionNode operator;

	public RoundOperator(@Nonnull ExpressionNode operator) {
		Assert.isTrue(
			operator != null,
			() -> new ParserException("Round function must have exactly one operand!")
		);
		this.operator = operator;
	}

	@Nonnull
	@Override
	public BigDecimal compute(@Nonnull PredicateEvaluationContext context) {
		final BigDecimal number = this.operator.compute(context, BigDecimal.class);
		return number.round(MATH_CONTEXT);
	}

	@Nonnull
	@Override
	public BigDecimalNumberRange determinePossibleRange() throws UnsupportedDataTypeException {
		return ExpressionNode.transform(
			this.operator.determinePossibleRange(),
			number -> number.round(MATH_CONTEXT)
		);
	}

	@Override
	public String toString() {
		return "round(" + this.operator.toString() + ")";
	}
}
