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

package io.evitadb.api.query.expression.parser.visitor.boolOperator;


import io.evitadb.api.query.expression.ExpressionNode;
import io.evitadb.api.query.expression.evaluate.PredicateEvaluationContext;
import io.evitadb.api.query.expression.exception.ParserException;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.Arrays;

/**
 * The ConjunctionOperator class represents a logical AND operation over multiple operators.
 * It aggregates multiple operator computations and returns true only if all individual operator computations return true.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class ConjunctionOperator implements ExpressionNode {
	@Serial private static final long serialVersionUID = 8865132783193638404L;
	private final ExpressionNode[] operator;

	public ConjunctionOperator(ExpressionNode[] operator) {
		Assert.isTrue(
			operator.length >= 2,
			() -> new ParserException("Conjunction function must have at least two operands!")
		);
		this.operator = operator;
	}

	@Nonnull
	@Override
	public Boolean compute(@Nonnull PredicateEvaluationContext context) {
		return Arrays.stream(operator)
			.map(op -> op.compute(context, Boolean.class))
			.reduce(true, (a, b) -> a && b);
	}

	@Override
	public String toString() {
		return Arrays.stream(operator)
			.map(ExpressionNode::toString)
			.reduce((a, b) -> a + " && " + b)
			.orElseThrow();
	}

}
