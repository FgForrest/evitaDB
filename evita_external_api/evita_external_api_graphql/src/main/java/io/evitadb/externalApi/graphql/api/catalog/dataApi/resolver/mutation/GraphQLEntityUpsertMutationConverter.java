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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.mutation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.EntityUpsertMutationConverter;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectParser;
import io.evitadb.externalApi.graphql.api.catalog.resolver.mutation.GraphQLMutationResolvingExceptionFactory;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Implementation of {@link EntityUpsertMutationConverter} for input object structure specific to GraphQL.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class GraphQLEntityUpsertMutationConverter extends EntityUpsertMutationConverter<List<Map<String, Object>>> {

	public GraphQLEntityUpsertMutationConverter(@Nonnull ObjectMapper objectMapper, @Nonnull EntitySchemaContract entitySchema) {
		super(entitySchema, objectMapper, new PassThroughMutationObjectParser(), new GraphQLMutationResolvingExceptionFactory());
	}

	@Nonnull
	@Override
	protected List<Object> convertAggregates(@Nonnull List<Map<String, Object>> inputLocalMutationAggregates) {
		return new ArrayList<>(inputLocalMutationAggregates);
	}
}
