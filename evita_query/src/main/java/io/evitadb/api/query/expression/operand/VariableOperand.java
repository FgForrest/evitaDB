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

package io.evitadb.api.query.expression.operand;


import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.exception.UnsupportedDataTypeException;
import io.evitadb.dataType.exception.VariableNotDefinedException;
import io.evitadb.dataType.expression.ExpressionEvaluationContext;
import io.evitadb.dataType.expression.ExpressionNode;
import io.evitadb.dataType.expression.ExpressionNodeVisitor;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.stream.Collectors;

/**
 * The VariableOperand class implements the ExpressionNode interface and is responsible for
 * retrieving the value of a variable from the provided PredicateEvaluationContext.
 *
 * The variable value is expected to be of a supported type and serializable. If the variable
 * is not found or if its type is unsupported, an exception is thrown.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
@EqualsAndHashCode
public class VariableOperand implements ExpressionNode {
	@Serial private static final long serialVersionUID = 1575684554715298743L;
	@Nullable @Getter private final String variableName;

	public boolean isThis() {
		return this.variableName == null;
	}

	@Nullable
	@Override
	public Serializable compute(@Nonnull ExpressionEvaluationContext context) {
		if (this.variableName == null) {
			return context.getThis()
				.map(it -> {
					Assert.isTrue(
						it instanceof Serializable,
						() -> new EvitaInvalidUsageException("`this` has unsupported type `" + it.getClass().getSimpleName() + "`.")
					);
					return (Serializable) it;
				})
				.orElse(null);
		}

		try {
			return context.getVariable(this.variableName)
				.map(it -> {
					Assert.isTrue(
						it instanceof Serializable,
						() -> new EvitaInvalidUsageException("Variable `" + this.variableName + "` has unsupported type `" + it.getClass().getSimpleName() + "`.")
					);
					return (Serializable) it;
				})
				.orElse(null);
		} catch (VariableNotDefinedException e) {
			throw new EvitaInvalidUsageException(
				"Variable `" + this.variableName + "` not defined! Only these variables are available: " +
					context.getVariableNames().map(it -> "`" + it + "`").collect(Collectors.joining(", "))
			);
		}
	}

	@Nonnull
	@Override
	public BigDecimalNumberRange determinePossibleRange() throws UnsupportedDataTypeException {
		return BigDecimalNumberRange.INFINITE;
	}

	@Nullable
	@Override
	public ExpressionNode[] getChildren() {
		return null;
	}

	@Override
	public void accept(@Nonnull ExpressionNodeVisitor visitor) {
		visitor.visit(this);
	}

	@Override
	public String toString() {
		return "$" + (this.variableName != null ? this.variableName : "");
	}
}
