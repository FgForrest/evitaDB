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

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.ServiceLoader.Provider;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Singleton registry that discovers and caches all {@link FunctionProcessor} implementations via
 * {@link ServiceLoader}. It provides lookup by function name and is used by the expression visitor
 * during EvitaEL parsing to resolve function calls.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public class FunctionProcessorRegistry {

	private static FunctionProcessorRegistry INSTANCE;

	private final Map<String, FunctionProcessor> functionProcessors;

	/**
	 * Returns the shared singleton instance, creating it on first access.
	 */
	@Nonnull
	public static FunctionProcessorRegistry getInstance() {
		if (INSTANCE == null) {
			INSTANCE = new FunctionProcessorRegistry();
		}
		return INSTANCE;
	}

	private FunctionProcessorRegistry() {
		this.functionProcessors = ServiceLoader.load(FunctionProcessor.class)
			.stream()
			.map(Provider::get)
			.collect(Collectors.toMap(
				FunctionProcessor::getName,
				Function.identity()
			));
	}

	/**
	 * Looks up a function processor by its name.
	 *
	 * @param functionName the function identifier as used in EvitaEL expressions
	 * @return the matching processor, or empty if no function with the given name is registered
	 */
	@Nonnull
	public Optional<FunctionProcessor> getFunctionProcessor(@Nonnull String functionName) {
		return Optional.ofNullable(this.functionProcessors.get(functionName));
	}
}
