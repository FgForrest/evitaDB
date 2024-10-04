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

package io.evitadb.dataType.expression;


import javax.annotation.Nonnull;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Stream;

/**
 * Interface defining a context for evaluating predicates. It provides access to variable names,
 * variable values, and a random number generator.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface PredicateEvaluationContext {
	/**
	 * Returns stream of variable names available.
	 *
	 * @return stream of variable names available
	 */
	@Nonnull
	Stream<String> getVariableNames();

	/**
	 * Returns variable value by its name.
	 *
	 * @param variableName variable name
	 * @return variable value or empty if variable with given name does not exist
	 */
	@Nonnull
	Optional<Object> getVariable(@Nonnull String variableName);

	/**
	 * Returns the random number generator.
	 *
	 * @return the random number generator
	 */
	@Nonnull
	Random getRandom();

}
