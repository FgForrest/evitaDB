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

package io.evitadb.api.query.expression.parser.visitor.boolOperator;


import io.evitadb.api.query.expression.exception.ParserException;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.exception.UnsupportedDataTypeException;
import io.evitadb.dataType.expression.ExpressionNode;
import io.evitadb.dataType.expression.PredicateEvaluationContext;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * A wrapper class for an {@link ExpressionNode} that represents a nested expression.
 * The class ensures that an {@link ExpressionNode} is properly encapsulated and can perform
 * evaluations and conversions consistently within its context.
 *
 * The nested operator is essentially an operator that takes another {@link ExpressionNode} as its operand.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@EqualsAndHashCode
public class NestedOperator implements ExpressionNode {
	@Serial private static final long serialVersionUID = 1706903340400175650L;
	private final ExpressionNode operator;

	public NestedOperator(ExpressionNode operator) {
		Assert.isTrue(
			operator != null,
			() -> new ParserException("Inversion function must have at least one operand!")
		);
		this.operator = operator;
	}

	@Nonnull
	@Override
	public Serializable compute(@Nonnull PredicateEvaluationContext context) {
		return this.operator.compute(context);
	}

	@Nonnull
	@Override
	public BigDecimalNumberRange determinePossibleRange() throws UnsupportedDataTypeException {
		return this.operator.determinePossibleRange();
	}

	@Override
	public String toString() {
		return "(" + this.operator.toString() + ")";
	}

}
