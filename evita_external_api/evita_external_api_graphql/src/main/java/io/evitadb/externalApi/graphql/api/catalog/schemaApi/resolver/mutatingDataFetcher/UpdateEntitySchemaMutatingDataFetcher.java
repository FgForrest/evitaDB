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

package io.evitadb.externalApi.graphql.api.catalog.schemaApi.resolver.mutatingDataFetcher;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectParser;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.EntitySchemaMutationAggregateConverter;
import io.evitadb.externalApi.graphql.api.catalog.GraphQLContextKey;
import io.evitadb.externalApi.graphql.api.catalog.resolver.mutation.GraphQLMutationResolvingExceptionFactory;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.model.UpdateEntitySchemaQueryHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.resolver.dataFetcher.WriteDataFetcher;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;

/**
 * Mutating data fetcher that is responsible for updating specific {@link EntitySchemaContract}.
 * <b>Note:</b> after this fetcher is finished, whole GraphQL instance is reloaded with new schema. This happens on Evita
 * session close call, thus clients must re-fetch new schema.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class UpdateEntitySchemaMutatingDataFetcher implements DataFetcher<EntitySchemaContract>, WriteDataFetcher {

	@Nonnull
	private final EntitySchemaContract entitySchema;

	@Nonnull
	private final EntitySchemaMutationAggregateConverter mutationAggregateResolver = new EntitySchemaMutationAggregateConverter(
		new PassThroughMutationObjectParser(),
		new GraphQLMutationResolvingExceptionFactory()
	);

	@Nonnull
	@Override
	public EntitySchemaContract get(@Nonnull DataFetchingEnvironment environment) throws Exception {
		final Arguments arguments = Arguments.from(environment);

		final EntitySchemaMutation[] schemaMutations = arguments.mutations()
			.stream()
			.flatMap(m -> mutationAggregateResolver.convert(m).stream())
			.toArray(EntitySchemaMutation[]::new);
		final ModifyEntitySchemaMutation entitySchemaMutation = new ModifyEntitySchemaMutation(entitySchema.getName(), schemaMutations);

		final EvitaSessionContract evitaSession = environment.getGraphQlContext().get(GraphQLContextKey.EVITA_SESSION);
		return evitaSession.updateAndFetchEntitySchema(entitySchemaMutation);
	}

	/**
	 * Holds parsed GraphQL query arguments relevant for updating entity schema
	 */
	private record Arguments(@Nonnull List<Map<String, Object>> mutations) {

		private static Arguments from(@Nonnull DataFetchingEnvironment environment) {
			final List<Map<String, Object>> mutations = environment.getArgumentOrDefault(UpdateEntitySchemaQueryHeaderDescriptor.MUTATIONS.name(), List.of());
			return new Arguments(mutations);
		}
	}
}
