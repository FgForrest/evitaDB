/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.externalApi.graphql.api.resolver.dataFetcher;

import graphql.TypeResolutionEnvironment;
import graphql.schema.GraphQLObjectType;
import graphql.schema.TypeResolver;
import io.evitadb.externalApi.graphql.exception.GraphQLQueryResolvingInternalError;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Implementation of {@link TypeResolver} for interfaces that are not returned directly in queries, but rather act as
 * generalization interfaces for client code reusability. Thus, these interfaces don't need to be resolved to target
 * object types, because queries already return the object types that implement these interfaces.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class HelperInterfaceTypeResolver implements TypeResolver {

	@Nullable
	private static HelperInterfaceTypeResolver INSTANCE = null;

	@Nonnull
	public static HelperInterfaceTypeResolver getInstance() {
		if (INSTANCE == null) {
			INSTANCE = new HelperInterfaceTypeResolver();
		}
		return INSTANCE;
	}

	@Override
	public GraphQLObjectType getType(TypeResolutionEnvironment env) {
		throw new GraphQLQueryResolvingInternalError("Interface cannot be used independently.");
	}
}
