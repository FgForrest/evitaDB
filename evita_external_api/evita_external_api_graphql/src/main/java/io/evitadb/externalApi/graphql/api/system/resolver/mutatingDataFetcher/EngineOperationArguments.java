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

package io.evitadb.externalApi.graphql.api.system.resolver.mutatingDataFetcher;

import graphql.schema.DataFetchingEnvironment;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Objects;

/**
 * Reads the arguments of the engine mutations of the system API.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class EngineOperationArguments {

	/**
	 * Returns a mandatory string argument of the mutation.
	 *
	 * @param environment environment carrying the arguments of the mutation
	 * @param argument    descriptor of the argument
	 * @return value of the argument
	 */
	@Nonnull
	static String getStringArgument(
		@Nonnull DataFetchingEnvironment environment,
		@Nonnull PropertyDescriptor argument
	) {
		return Objects.requireNonNull(environment.getArgument(argument.name()));
	}
}
