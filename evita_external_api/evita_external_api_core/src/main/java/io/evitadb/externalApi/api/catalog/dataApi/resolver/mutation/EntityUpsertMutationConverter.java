/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationObjectParser;
import io.evitadb.externalApi.api.catalog.resolver.mutation.MutationResolvingExceptionFactory;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

/**
 * Resolves input local mutation aggregate objects parsed from JSON into a {@link EntityUpsertMutation}.
 *
 * @param <A> type of input object containing aggregates
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public abstract class EntityUpsertMutationConverter<A> {

	@Nonnull
	@Getter(AccessLevel.PROTECTED)
	private final EntitySchemaContract entitySchema;
	@Nonnull
	@Getter(AccessLevel.PROTECTED)
	private LocalMutationAggregateConverter localMutationAggregateResolver;

	protected EntityUpsertMutationConverter(@Nonnull EntitySchemaContract entitySchema,
	                                     @Nonnull ObjectMapper objectMapper,
	                                     @Nonnull MutationObjectParser objectParser,
	                                     @Nonnull MutationResolvingExceptionFactory exceptionFactory) {
		this.entitySchema = entitySchema;
		this.localMutationAggregateResolver = new LocalMutationAggregateConverter(objectMapper, entitySchema, objectParser, exceptionFactory);
	}

	@Nonnull
	public EntityUpsertMutation resolve(@Nullable Integer primaryKey,
	                                    @Nonnull EntityExistence entityExistence,
	                                    @Nonnull A inputLocalMutationAggregates) {
		final List<Object> rawInputLocalMutationAggregates = resolveAggregates(inputLocalMutationAggregates);
		final List<LocalMutation<?, ?>> localMutations = rawInputLocalMutationAggregates.stream()
			.flatMap(agg -> localMutationAggregateResolver.convert(agg).stream())
			.toList();

		return new EntityUpsertMutation(entitySchema.getName(), primaryKey, entityExistence, localMutations);
	}

	/**
	 * Resolvers input aggregates from input object into list of individual aggregates
	 */
	@Nonnull
	protected abstract List<Object> resolveAggregates(@Nonnull A inputLocalMutationAggregates);
}
