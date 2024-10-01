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

package io.evitadb.api.query.expression.parser.visitor.operators.boolOperator;


import io.evitadb.api.query.expression.parser.evaluate.PredicateEvaluationContext;
import io.evitadb.api.query.expression.parser.exception.ParserException;
import io.evitadb.api.query.expression.parser.visitor.operators.ExpressionNode;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;

/**
 * The InverseOperator class implements the ExpressionNode interface. This class is designed to perform a logical
 * inversion (negation) of the result computed by another ExpressionNode.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class InverseOperator implements ExpressionNode {
	private final ExpressionNode operator;

	public InverseOperator(ExpressionNode operator) {
		Assert.isTrue(
			operator != null,
			() -> new ParserException("Inversion function must have at least one operand!")
		);
		this.operator = operator;
	}

	@Nonnull
	@Override
	public Boolean compute(@Nonnull PredicateEvaluationContext context) {
		return !operator.compute(context, Boolean.class);
	}

}
