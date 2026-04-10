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

import io.evitadb.api.query.expression.exception.ParserException;
import io.evitadb.dataType.expression.ExpressionEvaluationContext;
import io.evitadb.dataType.expression.ExpressionNode;
import io.evitadb.dataType.expression.ExpressionNodeVisitor;
import io.evitadb.dataType.expression.UnaryExpressionNode;
import io.evitadb.exception.ExpressionEvaluationException;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * Shared base class for single-operand expression operators. Caches the {@link #getChildren()} array
 * to avoid allocation on every call.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@EqualsAndHashCode
public abstract class AbstractUnaryOperator implements UnaryExpressionNode {
	@Serial private static final long serialVersionUID = 7293614850927345691L;
	@Nonnull private final ExpressionNode operand;
	@EqualsAndHashCode.Exclude
	private final ExpressionNode[] children;

	protected AbstractUnaryOperator(@Nonnull ExpressionNode operand) {
		Assert.isTrue(
			operand != null,
			() -> new ParserException("Unary operator must have an operand!")
		);
		this.operand = operand;
		this.children = new ExpressionNode[]{this.operand};
	}

	@Nonnull
	@Override
	public ExpressionNode getOperand() {
		return this.operand;
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
	 * Computes the operand value, throwing if the result is null.
	 */
	@Nonnull
	protected <T extends Serializable> T computeOperand(
		@Nonnull ExpressionEvaluationContext context, @Nonnull Class<T> clazz
	) {
		final T result = getOperand().compute(context, clazz);
		if (result == null) {
			throw new ExpressionEvaluationException("Operand is required, but evaluated to null.");
		}
		return result;
	}
}
