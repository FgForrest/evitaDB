/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.core.expression.proxy;

import io.evitadb.dataType.exception.VariableNotDefinedException;
import io.evitadb.dataType.expression.ExpressionEvaluationContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Stream;

/**
 * Expression evaluation context that binds entity and reference proxies as variables for expression evaluation.
 *
 * Variables are bound without the `$` prefix (e.g., `"entity"` not `"$entity"`), matching the
 * `VariableOperand` lookup and `AccessedDataFinder` path item naming.
 *
 * This class implements {@link ExpressionEvaluationContext} directly rather than extending
 * `AbstractExpressionEvaluationContext` (which is `sealed` to its own subclasses).
 */
public final class ExpressionVariableContext implements ExpressionEvaluationContext {

	@Nonnull private final Map<String, Object> variables;
	@Nullable private final Object thisObject;
	@Nonnull private final Random random;

	/**
	 * Creates a new context with the given variable bindings and no `this` object.
	 *
	 * @param variables map of variable names to values (e.g., "entity" → entity proxy)
	 */
	public ExpressionVariableContext(@Nonnull Map<String, Object> variables) {
		this.variables = variables;
		this.thisObject = null;
		this.random = new Random();
	}

	/**
	 * Creates a new context with the given variable bindings, `this` object, and random generator.
	 *
	 * @param variables  map of variable names to values
	 * @param thisObject the `this` object reference (nullable)
	 * @param random     the random number generator to use
	 */
	private ExpressionVariableContext(
		@Nonnull Map<String, Object> variables,
		@Nullable Object thisObject,
		@Nonnull Random random
	) {
		this.variables = variables;
		this.thisObject = thisObject;
		this.random = random;
	}

	@Nonnull
	@Override
	public Optional<Object> getThis() {
		return Optional.ofNullable(this.thisObject);
	}

	@Nonnull
	@Override
	public ExpressionEvaluationContext withThis(@Nullable Object thisObject) {
		return new ExpressionVariableContext(this.variables, thisObject, this.random);
	}

	@Nonnull
	@Override
	public Stream<String> getVariableNames() {
		return this.variables.keySet().stream();
	}

	@Nonnull
	@Override
	public Optional<Object> getVariable(@Nonnull String variableName) throws VariableNotDefinedException {
		if (!this.variables.containsKey(variableName)) {
			throw new VariableNotDefinedException(variableName);
		}
		return Optional.ofNullable(this.variables.get(variableName));
	}

	@Nonnull
	@Override
	public Random getRandom() {
		return this.random;
	}
}
