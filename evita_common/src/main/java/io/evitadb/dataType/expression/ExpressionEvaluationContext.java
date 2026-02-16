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

package io.evitadb.dataType.expression;


import io.evitadb.dataType.exception.VariableNotDefinedException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Stream;

/**
 * Interface defining a context for evaluating expressions. It provides access to variable names,
 * variable values, and a random number generator.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface ExpressionEvaluationContext {

	// todo lho explain what `this` is
	/**
	 * Returns an object that represents `this` in expression.
	 *
	 * @return `this` object reference, if is any available in the context
	 */
	@Nonnull
	Optional<Object> getThis();

	/**
	 * Creates a copy of current context with different `this` object reference.
	 *
	 * @param thisObject new `this` object reference
	 * @return new context
	 */
	ExpressionEvaluationContext withThis(@Nullable Object thisObject);

	/**
	 * Returns the names of all variables available in this evaluation context.
	 *
	 * @return stream of available variable names
	 */
	@Nonnull
	Stream<String> getVariableNames();

	/**
	 * Returns the value of a variable identified by its name.
	 *
	 * @param variableName the name of the variable to look up
	 * @return the variable value, or empty if no variable with the given name exists in this context
	 */
	@Nonnull
	Optional<Object> getVariable(@Nonnull String variableName) throws VariableNotDefinedException;

	/**
	 * Returns the random number generator used by expression operators that require randomness
	 * (e.g. the `random()` function in expressions).
	 *
	 * @return the random number generator for this evaluation context
	 */
	@Nonnull
	Random getRandom();

}
