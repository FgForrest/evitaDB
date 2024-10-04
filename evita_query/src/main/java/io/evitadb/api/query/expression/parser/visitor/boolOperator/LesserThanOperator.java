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


import io.evitadb.api.query.expression.exception.ParserException;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.expression.ExpressionNode;
import io.evitadb.dataType.expression.PredicateEvaluationContext;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * Implementation of the LesserThanOperator that evaluates whether the result of the
 * leftOperator is lesser than or equal to the result of the rightOperator.
 *
 * The operands must be instances of {@link Comparable}.
 * The comparison is performed by converting the right operand to the type of the left operand.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class LesserThanOperator implements ExpressionNode {
	@Serial private static final long serialVersionUID = 2928332697121258800L;
	private final ExpressionNode leftOperator;
	private final ExpressionNode rightOperator;

	public LesserThanOperator(ExpressionNode leftOperator, ExpressionNode rightOperator) {
		this.leftOperator = leftOperator;
		this.rightOperator = rightOperator;
	}

	@Nonnull
	@Override
	public Boolean compute(@Nonnull PredicateEvaluationContext context) {
		final Serializable value1 = leftOperator.compute(context);
		Assert.isTrue(
			value1 instanceof Comparable,
			() -> new ParserException("Lesser than function operand must be comparable!")
		);
		final Serializable value2 = rightOperator.compute(context);
		Assert.isTrue(
			value2 instanceof Comparable,
			() -> new ParserException("Lesser than function operand must be comparable!")
		);
		final Serializable convertedValue2 = EvitaDataTypes.toTargetType(value2, value1.getClass());
		//noinspection rawtypes,unchecked
		return ((Comparable) value1).compareTo(convertedValue2) < 0;
	}

	@Override
	public String toString() {
		return leftOperator.toString() + " < " + rightOperator.toString();
	}
}
