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

package io.evitadb.api.query.expression.parser.visitor.operand;


import io.evitadb.api.query.expression.exception.ParserException;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.exception.UnsupportedDataTypeException;
import io.evitadb.dataType.expression.ExpressionNode;
import io.evitadb.dataType.expression.PredicateEvaluationContext;
import io.evitadb.exception.ExpressionEvaluationException;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * Represents a positive number operand in an expression. This class encapsulates an operand
 * and ensures that it is not null during instantiation. It implements the ExpressionNode
 * interface and delegates the computation to its encapsulated operand.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@EqualsAndHashCode
public class PositiveNumberOperand implements ExpressionNode {
	@Serial private static final long serialVersionUID = 7806494928096151670L;
	private final ExpressionNode operator;

	public PositiveNumberOperand(ExpressionNode operator) {
		Assert.isTrue(
			operator != null,
			() -> new ParserException("Floor function must have at least one operand!")
		);
		this.operator = operator;
	}

	@Nonnull
	@Override
	public Serializable compute(@Nonnull PredicateEvaluationContext context) throws ExpressionEvaluationException {
		return this.operator.compute(context);
	}

	@Nonnull
	@Override
	public BigDecimalNumberRange determinePossibleRange() throws UnsupportedDataTypeException {
		return this.operator.determinePossibleRange();
	}

	@Override
	public String toString() {
		return "+" + this.operator.toString();
	}
}
