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

import io.evitadb.api.query.expression.AbstractBinaryOperator;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.exception.UnsupportedDataTypeException;
import io.evitadb.dataType.expression.ExpressionEvaluationContext;
import io.evitadb.dataType.expression.ExpressionNode;
import lombok.EqualsAndHashCode;

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
@EqualsAndHashCode(callSuper = true)
public class NullCoalesceOperator extends AbstractBinaryOperator {
	@Serial private static final long serialVersionUID = 4323373715682052501L;

	public NullCoalesceOperator(@Nonnull ExpressionNode valueOperator, @Nonnull ExpressionNode defaultValueOperator) {
		super(valueOperator, defaultValueOperator);
	}

	@Nonnull
	@Override
	protected String getOperatorSymbol() {
		return "??";
	}

	@Nullable
	@Override
	public Serializable compute(@Nonnull ExpressionEvaluationContext context) {
		final Serializable value = getLeftOperand().compute(context);
		final Serializable defaultValue = getRightOperand().compute(context);
		return value != null ? value : defaultValue;
	}

	@Nonnull
	@Override
	public BigDecimalNumberRange determinePossibleRange() throws UnsupportedDataTypeException {
		return BigDecimalNumberRange.union(
			getLeftOperand().determinePossibleRange(),
			getRightOperand().determinePossibleRange()
		);
	}
}
