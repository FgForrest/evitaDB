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

package io.evitadb.api.query.expression.parser.visitor.operand;


import io.evitadb.api.query.expression.ExpressionNode;
import io.evitadb.api.query.expression.evaluate.PredicateEvaluationContext;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
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
public class VariableOperand implements ExpressionNode {
	@Serial private static final long serialVersionUID = 1575684554715298743L;
	private final String variableName;

	@Nonnull
	@Override
	public Serializable compute(@Nonnull PredicateEvaluationContext context) {
		return context.getVariable(variableName)
			.map(it -> {
				Assert.isTrue(
					it instanceof Serializable && EvitaDataTypes.isSupportedType(it.getClass()),
					() -> new EvitaInvalidUsageException("Variable `" + variableName + "` has unsupported type: " + it.getClass().getSimpleName())
				);
				return (Serializable) it;
			})
			.orElseThrow(() -> new EvitaInvalidUsageException(
				"Variable `" + variableName + "` not found! Only these variables are available: " +
					context.getVariableNames().map(it -> "`" + it + "`").collect(Collectors.joining(", ")))
			);
	}

	@Override
	public String toString() {
		return "$" + variableName;
	}
}
