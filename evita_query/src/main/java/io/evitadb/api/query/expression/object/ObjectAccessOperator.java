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

package io.evitadb.api.query.expression.object;

import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.exception.UnsupportedDataTypeException;
import io.evitadb.dataType.expression.ExpressionEvaluationContext;
import io.evitadb.dataType.expression.ExpressionNode;
import io.evitadb.exception.ExpressionEvaluationException;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;

/**
 * Expression node that evaluates an object access expression in EvitaEL. It combines a source
 * operand expression with a chain of {@link ObjectAccessStep}s to traverse nested data structures.
 *
 * For example, the expression `$entity.attributes['name']` is represented as an
 * {@link ObjectAccessOperator} where the operand is a variable reference to `$entity` and the
 * access chain consists of a {@link PropertyAccessStep} for `attributes` followed by an
 * {@link ElementAccessStep} for `'name'`.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor
public class ObjectAccessOperator implements ExpressionNode {

	@Serial private static final long serialVersionUID = 2269901980432598797L;

	@Nonnull private final ExpressionNode operandOperator;
	@Nonnull private final ObjectAccessStep accessChain;

	@Nullable
	@Override
	public Serializable compute(@Nonnull ExpressionEvaluationContext context) throws ExpressionEvaluationException {
		final Serializable operand = this.operandOperator.compute(context);
		return this.accessChain.compute(context, operand);
	}

	@Nonnull
	@Override
	public BigDecimalNumberRange determinePossibleRange() throws UnsupportedDataTypeException {
		return this.operandOperator.determinePossibleRange();
	}
}
