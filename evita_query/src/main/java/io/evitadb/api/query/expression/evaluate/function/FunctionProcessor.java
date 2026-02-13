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

package io.evitadb.api.query.expression.evaluate.function;

import io.evitadb.exception.ExpressionEvaluationException;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.List;

/**
 * Base interface for all EvitaEL (Evita Expression Language) function processors. Each implementation handles
 * a specific built-in function such as `abs`, `min`, `sqrt`, `round`, etc.
 *
 * Implementations are discovered via {@link java.util.ServiceLoader} and registered in
 * {@link FunctionProcessorRegistry}. The common base class for numeric functions is
 * {@link AbstractMathFunctionProcessor}.
 *
 * Custom implementations can be registered via a service file in `META-INF/services`, or via `module-info.java` file.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public interface FunctionProcessor {

	/**
	 * Returns the function identifier as used in EvitaEL expressions (e.g. `abs`, `min`, `sqrt`).
	 */
	@Nonnull
	String getName();

	/**
	 * Evaluates the function with the provided arguments and returns the result.
	 *
	 * @param arguments list of already-evaluated argument values
	 * @return the result of the function evaluation
	 * @throws ExpressionEvaluationException if the arguments are invalid or the function cannot be evaluated
	 */
	@Nonnull
	Serializable process(@Nonnull List<Serializable> arguments) throws ExpressionEvaluationException;
}
