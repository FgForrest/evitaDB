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

/**
 * CeilOperator is an implementation of the ExpressionNode interface that computes the ceiling value of
 * a given numeric operand. The ceiling value is the smallest integer that is greater than or equal
 * to the operand.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@EqualsAndHashCode
public class CeilOperator implements ExpressionNode {
	@Serial private static final long serialVersionUID = 8956428057418706753L;
	private final ExpressionNode operator;

	public CeilOperator(@Nonnull ExpressionNode operator) {
		Assert.isTrue(
			operator != null,
			() -> new ParserException("Ceil function must have exactly one operand!")
		);
		this.operator = operator;
	}

	@Nonnull
	@Override
	public BigDecimal compute(@Nonnull PredicateEvaluationContext context) {
		final BigDecimal number = this.operator.compute(context, BigDecimal.class);
		return number.setScale(0, RoundingMode.CEILING);
	}

	@Nonnull
	@Override
	public BigDecimalNumberRange determinePossibleRange() throws UnsupportedDataTypeException {
		return ExpressionNode.transform(
			this.operator.determinePossibleRange(),
			bd -> bd.setScale(0, RoundingMode.CEILING)
		);
	}

	@Override
	public String toString() {
		return "ceil(" + this.operator.toString() + ")";
	}
}
