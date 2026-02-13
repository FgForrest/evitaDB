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

package io.evitadb.api.query.expression.evaluate;

import io.evitadb.dataType.exception.VariableNotDefinedException;
import io.evitadb.dataType.expression.ExpressionEvaluationContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public final class EmptyExpressionEvaluationContext extends AbstractExpressionEvaluationContext {

	@Nonnull
	@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
	private static final Optional<Object> EMPTY_VARIABLE = Optional.empty();

	@Nullable
	private static EmptyExpressionEvaluationContext INSTANCE;

	@Nonnull
	public static EmptyExpressionEvaluationContext getInstance() {
		if (INSTANCE == null) {
			INSTANCE = new EmptyExpressionEvaluationContext(null);
		}
		return INSTANCE;
	}

	private EmptyExpressionEvaluationContext(@Nullable Object thisObject) {
		super(thisObject);
	}

	@Override
	public ExpressionEvaluationContext withThis(@Nullable Object thisObject) {
		// todo lho this is incorrect, should the empty context even exist?
		return this;
	}

	@Nonnull
	@Override
	public Stream<String> getVariableNames() {
		return Stream.empty();
	}

	@Nonnull
	@Override
	public Optional<Object> getVariable(@Nonnull String variableName) {
		throw new VariableNotDefinedException(variableName);
	}
}
