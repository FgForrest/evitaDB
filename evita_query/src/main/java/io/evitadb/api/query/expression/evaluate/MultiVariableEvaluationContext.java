/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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


import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A context for parsing operations that manages multiple variables. It stores variables in a map and provides
 * functionality to retrieve these variables by their names.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public final class MultiVariableEvaluationContext extends AbstractPredicateEvaluationContext {
	/**
	 * A map storing the variables by their names. The keys are the variable names and the values are the variable values.
	 */
	private final Map<String, Object> variables;

	public MultiVariableEvaluationContext(@Nonnull Map<String, Object> variables) {
		this.variables = variables;
	}

	public MultiVariableEvaluationContext(long seed, @Nonnull Map<String, Object> variables) {
		super(seed);
		this.variables = variables;
	}

	@Nonnull
	@Override
	public Optional<Object> getVariable(@Nonnull String variableName) {
		return Optional.ofNullable(this.variables.get(variableName));
	}

	@Nonnull
	@Override
	public Stream<String> getVariableNames() {
		return this.variables.keySet().stream();
	}
}
