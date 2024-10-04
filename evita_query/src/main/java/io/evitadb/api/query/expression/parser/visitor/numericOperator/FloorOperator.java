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
import io.evitadb.dataType.expression.ExpressionNode;
import io.evitadb.dataType.expression.PredicateEvaluationContext;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * This class represents a floor function in computations. The floor function
 * returns the largest integer value not greater than the specified decimal value.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class FloorOperator implements ExpressionNode {
	@Serial private static final long serialVersionUID = 5417005969808973172L;
	private final ExpressionNode operator;

	public FloorOperator(ExpressionNode operator) {
		Assert.isTrue(
			operator != null,
			() -> new ParserException("Floor function must have at least one operand!")
		);
		this.operator = operator;
	}

	@Nonnull
	@Override
	public BigDecimal compute(@Nonnull PredicateEvaluationContext context) {
		final BigDecimal number = operator.compute(context, BigDecimal.class);
		return number.setScale(0, RoundingMode.FLOOR);
	}

	@Override
	public String toString() {
		return "floor(" + operator.toString() + ")";
	}
}
