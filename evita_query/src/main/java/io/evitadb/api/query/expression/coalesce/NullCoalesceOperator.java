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

package io.evitadb.api.query.expression.coalesce;

import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.exception.UnsupportedDataTypeException;
import io.evitadb.dataType.expression.ExpressionEvaluationContext;
import io.evitadb.dataType.expression.ExpressionNode;
import io.evitadb.dataType.expression.ExpressionNodeVisitor;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;

/**
 * Expression node implementing the null coalescing operator (`??`). Evaluates the primary value
 * expression and returns its result if non-null, otherwise evaluates and returns the default value
 * expression.
 *
 * For example, the expression `a ?? b` returns the value of `a` if it is not null, otherwise
 * returns the value of `b`.

 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@EqualsAndHashCode
public class NullCoalesceOperator implements ExpressionNode {
	@Serial private static final long serialVersionUID = 4323373715682052501L;

	@Nonnull private final ExpressionNode valueOperator;
	@Nonnull private final ExpressionNode defaultValueOperator;
	@EqualsAndHashCode.Exclude
	@Getter
	private final ExpressionNode[] children;

	public NullCoalesceOperator(@Nonnull ExpressionNode valueOperator, @Nonnull ExpressionNode defaultValueOperator) {
		this.valueOperator = valueOperator;
		this.defaultValueOperator = defaultValueOperator;
		this.children = new ExpressionNode[]{this.valueOperator, this.defaultValueOperator};
	}

	@Nullable
	@Override
	public Serializable compute(@Nonnull ExpressionEvaluationContext context) {
		final Serializable value = this.valueOperator.compute(context);
		final Serializable defaultValue = this.defaultValueOperator.compute(context);
		return value != null ? value : defaultValue;
	}

	@Override
	public void accept(@Nonnull ExpressionNodeVisitor visitor) {
		visitor.visit(this);
	}

	@Nonnull
	@Override
	public BigDecimalNumberRange determinePossibleRange() throws UnsupportedDataTypeException {
		return BigDecimalNumberRange.union(
			this.valueOperator.determinePossibleRange(),
			this.defaultValueOperator.determinePossibleRange()
		);
	}

	@Override
	public String toString() {
		return this.valueOperator + " ?? " + this.defaultValueOperator;
	}
}
