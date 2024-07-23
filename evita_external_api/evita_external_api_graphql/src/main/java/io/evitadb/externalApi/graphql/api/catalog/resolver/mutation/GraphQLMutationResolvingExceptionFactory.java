/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.externalApi.graphql.api.catalog.resolver.mutation;

import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import io.evitadb.externalApi.graphql.exception.GraphQLInvalidArgumentException;

import javax.annotation.Nonnull;

/**
 * Implementation of {@link MutationResolvingExceptionFactory} for GraphQL-specific exceptions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class GraphQLMutationResolvingExceptionFactory implements MutationResolvingExceptionFactory {

	@SuppressWarnings("unchecked")
	@Nonnull
	@Override
	public <T extends EvitaInternalError> T createInternalError(@Nonnull String message) {
		return (T) new GraphQLInternalError(message);
	}

	@SuppressWarnings("unchecked")
	@Nonnull
	@Override
	public <T extends EvitaInternalError> T createInternalError(@Nonnull String message, @Nonnull Throwable cause) {
		return (T) new GraphQLInternalError(message, cause);
	}

	@SuppressWarnings("unchecked")
	@Nonnull
	@Override
	public <T extends EvitaInvalidUsageException> T createInvalidArgumentException(@Nonnull String message) {
		return (T) new GraphQLInvalidArgumentException(message);
	}
}
