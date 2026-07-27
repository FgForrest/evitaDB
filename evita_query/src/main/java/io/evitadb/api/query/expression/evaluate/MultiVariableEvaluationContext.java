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

package io.evitadb.api.query.expression.evaluate;


import io.evitadb.dataType.exception.VariableNotDefinedException;
import io.evitadb.dataType.expression.ExpressionEvaluationContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Stream;

/**
 * A context for parsing operations that manages multiple variables. It stores variables in a map and provides
 * functionality to retrieve these variables by their names.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public final class MultiVariableEvaluationContext extends AbstractExpressionEvaluationContext {
	/**
	 * A map storing the variables by their names. The keys are the variable names and the values are the variable values.
	 */
	private final Map<String, Object> variables;

	public MultiVariableEvaluationContext(@Nonnull Map<String, Object> variables) {
		super(null);
		this.variables = variables;
	}

	public MultiVariableEvaluationContext(long seed, @Nonnull Map<String, Object> variables) {
		super(null, seed);
		this.variables = variables;
	}

	private MultiVariableEvaluationContext(
		@Nullable Object thisObject,
		@Nonnull Random random,
		@Nonnull Map<String, Object> variables
	) {
		super(thisObject, random);
		this.variables = variables;
	}

	@Override
	public ExpressionEvaluationContext withThis(@Nullable Object thisObject) {
		return new MultiVariableEvaluationContext(thisObject, getRandom(), this.variables);
	}

	@Nonnull
	@Override
	public Optional<Object> getVariable(@Nonnull String variableName) {
		if (!this.variables.containsKey(variableName)) {
			throw new VariableNotDefinedException(variableName);
		}
		return Optional.ofNullable(this.variables.get(variableName));
	}

	@Nonnull
	@Override
	public Stream<String> getVariableNames() {
		return this.variables.keySet().stream();
	}
}
