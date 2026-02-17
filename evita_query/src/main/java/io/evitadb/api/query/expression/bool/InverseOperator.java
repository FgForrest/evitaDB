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


import io.evitadb.api.query.expression.exception.ParserException;
import io.evitadb.api.query.expression.operand.ConstantOperand;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.exception.UnsupportedDataTypeException;
import io.evitadb.dataType.expression.ExpressionEvaluationContext;
import io.evitadb.dataType.expression.ExpressionNode;
import io.evitadb.dataType.expression.ExpressionNodeVisitor;
import io.evitadb.exception.ExpressionEvaluationException;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * The InverseOperator class implements the ExpressionNode interface. This class is designed to perform a logical
 * inversion (negation) of the result computed by another ExpressionNode.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@EqualsAndHashCode
public class InverseOperator implements ExpressionNode {
	@Serial private static final long serialVersionUID = 4825500310430824808L;
	private final ExpressionNode operator;
	@EqualsAndHashCode.Exclude
	@Getter
	private final ExpressionNode[] children;

	public InverseOperator(ExpressionNode operator) {
		Assert.isTrue(
			operator != null,
			() -> new ParserException("Inversion function must have at least one operand!")
		);
		this.operator = operator;
		this.children = new ExpressionNode[]{this.operator};
	}

	@Nonnull
	@Override
	public Boolean compute(@Nonnull ExpressionEvaluationContext context) {
		final Boolean operand = this.operator.compute(context, Boolean.class);
		if (operand == null) {
			throw new ExpressionEvaluationException("Operand is required, but evaluated to null.");
		}
		return !operand;
	}

	@Nonnull
	@Override
	public BigDecimalNumberRange determinePossibleRange() throws UnsupportedDataTypeException {
		return this.operator.determinePossibleRange().inverse(16);
	}

	@Override
	public void accept(@Nonnull ExpressionNodeVisitor visitor) {
		visitor.visit(this);
	}

	@Override
	public String toString() {
		return this.operator instanceof ConstantOperand constantOperand && constantOperand.getValue() instanceof Boolean ?
			"!" + constantOperand.getValue() : "!(" + this.operator.toString() + ")";
	}
}
