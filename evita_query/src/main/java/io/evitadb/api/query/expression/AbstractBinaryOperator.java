/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.api.query.expression;

import io.evitadb.dataType.expression.BinaryExpressionNode;
import io.evitadb.dataType.expression.ExpressionEvaluationContext;
import io.evitadb.dataType.expression.ExpressionNode;
import io.evitadb.dataType.expression.ExpressionNodeVisitor;
import io.evitadb.exception.ExpressionEvaluationException;
import lombok.EqualsAndHashCode;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * Shared base class for binary operators that combine exactly two operands.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@EqualsAndHashCode
public abstract class AbstractBinaryOperator implements BinaryExpressionNode {
	@Serial private static final long serialVersionUID = -4820759831007391842L;
	@Nonnull private final ExpressionNode leftOperator;
	@Nonnull private final ExpressionNode rightOperator;
	@EqualsAndHashCode.Exclude
	private final ExpressionNode[] children;

	protected AbstractBinaryOperator(@Nonnull ExpressionNode leftOperator, @Nonnull ExpressionNode rightOperator) {
		this.leftOperator = leftOperator;
		this.rightOperator = rightOperator;
		this.children = new ExpressionNode[]{this.leftOperator, this.rightOperator};
	}

	@Nonnull
	@Override
	public ExpressionNode getLeftOperand() {
		return this.leftOperator;
	}

	@Nonnull
	@Override
	public ExpressionNode getRightOperand() {
		return this.rightOperator;
	}

	@Nonnull
	@Override
	public ExpressionNode[] getChildren() {
		return this.children;
	}

	@Override
	public void accept(@Nonnull ExpressionNodeVisitor visitor) {
		visitor.visit(this);
	}

	/**
	 * Computes the left operand value, throwing if the result is null.
	 */
	@Nonnull
	protected <T extends Serializable> T computeLeft(
		@Nonnull ExpressionEvaluationContext context, @Nonnull Class<T> clazz
	) {
		final T result = getLeftOperand().compute(context, clazz);
		if (result == null) {
			throw new ExpressionEvaluationException("Left operand is required, but evaluated to null.");
		}
		return result;
	}

	/**
	 * Computes the right operand value, throwing if the result is null.
	 */
	@Nonnull
	protected <T extends Serializable> T computeRight(
		@Nonnull ExpressionEvaluationContext context, @Nonnull Class<T> clazz
	) {
		final T result = getRightOperand().compute(context, clazz);
		if (result == null) {
			throw new ExpressionEvaluationException("Right operand is required, but evaluated to null.");
		}
		return result;
	}

	/**
	 * Returns the symbol representing this binary operator (e.g. `+`, `-`, `*`).
	 */
	@Nonnull
	protected abstract String getOperatorSymbol();

	@Override
	public String toString() {
		return this.leftOperator + " " + getOperatorSymbol() + " " + this.rightOperator;
	}

}
