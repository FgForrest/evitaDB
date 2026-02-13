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
import java.util.Optional;
import java.util.Random;
import java.util.stream.Stream;

/**
 * Context for parsing operations that only holds a single variable. This implementation is useful for scenarios where
 * only one variable is necessary. This implementation is more efficient than {@link MultiVariableEvaluationContext} for such
 * cases.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public final class SingleVariableEvaluationContext extends AbstractExpressionEvaluationContext {

	@Nonnull private final String variableName;
	@Nonnull private final Object variableValue;

	public SingleVariableEvaluationContext(@Nonnull String variableName, @Nonnull Object variableValue) {
		super(null);
		this.variableName = variableName;
		this.variableValue = variableValue;
	}

	public SingleVariableEvaluationContext(long seed, @Nonnull String variableName, @Nonnull Object variableValue) {
		super(null, seed);
		this.variableName = variableName;
		this.variableValue = variableValue;
	}

	private SingleVariableEvaluationContext(
		@Nullable Object thisObject,
		@Nonnull Random random,
		@Nonnull String variableName,
		@Nonnull Object variableValue
	) {
		super(thisObject, random);
		this.variableName = variableName;
		this.variableValue = variableValue;
	}

	@Override
	public ExpressionEvaluationContext withThis(@Nullable Object thisObject) {
		return new SingleVariableEvaluationContext(thisObject, getRandom(), this.variableName, this.variableValue);
	}

	@Nonnull
	@Override
	public Optional<Object> getVariable(@Nonnull String variableName) {
		if (!this.variableName.equals(variableName)) {
			throw new VariableNotDefinedException(variableName);
		}
		return Optional.of(this.variableValue);
	}

	@Nonnull
	@Override
	public Stream<String> getVariableNames() {
		return Stream.of(this.variableName);
	}
}
