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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.mutatingDataFetcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.execution.DataFetcherResult;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.UpsertEntityMutationHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.GraphQLContextKey;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.EntityFetchRequireBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.EntityQueryContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.SelectionSetWrapper;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.mutation.GraphQLEntityUpsertMutationConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Mutating data fetcher that firstly applies entity mutations to selected entity and then returns updated entity to client.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Slf4j
@RequiredArgsConstructor
public class UpsertEntityMutatingDataFetcher implements DataFetcher<DataFetcherResult<EntityClassifier>> {

	private final GraphQLEntityUpsertMutationConverter entityUpsertMutationResolver;

	@Nonnull
	private EntitySchemaContract entitySchema;
	/**
	 * Function to fetch specific entity schema based on its name.
	 */
	@Nonnull
	private final Function<String, EntitySchemaContract> entitySchemaFetcher;

	public UpsertEntityMutatingDataFetcher(@Nonnull ObjectMapper objectMapper,
										   @Nonnull CatalogSchemaContract catalogSchema,
	                                       @Nonnull EntitySchemaContract entitySchema) {
		this.entitySchema = entitySchema;
		this.entitySchemaFetcher = catalogSchema::getEntitySchemaOrThrowException;
		this.entityUpsertMutationResolver = new GraphQLEntityUpsertMutationConverter(objectMapper, entitySchema);
	}

	@Nonnull
	@Override
	public DataFetcherResult<EntityClassifier> get(@Nonnull DataFetchingEnvironment environment) throws Exception {
		final Arguments arguments = Arguments.from(environment);

		final EntityMutation entityMutation = entityUpsertMutationResolver.resolve(arguments.primaryKey(), arguments.entityExistence(), arguments.mutations());
		final EntityContentRequire[] contentRequires = buildEnrichingRequires(environment);

		final EvitaSessionContract evitaSession = environment.getGraphQlContext().get(GraphQLContextKey.EVITA_SESSION);
		log.debug("Upserting entity `{}` with PK {} and fetching new version with `{}`.",  entitySchema.getName(), arguments.primaryKey(), Arrays.toString(contentRequires));
		final SealedEntity upsertedEntity = evitaSession.upsertAndFetchEntity(entityMutation, contentRequires);

		return DataFetcherResult.<EntityClassifier>newResult()
			.data(upsertedEntity)
			.localContext(EntityQueryContext.builder().build())
			.build();
	}

	@Nonnull
	private EntityContentRequire[] buildEnrichingRequires(@Nonnull DataFetchingEnvironment environment) {
		final EntityFetch entityFetch = EntityFetchRequireBuilder.buildEntityRequirement(
			SelectionSetWrapper.from(environment.getSelectionSet()),
			null,
			entitySchema,
			entitySchemaFetcher
		);
		return entityFetch == null ? new EntityContentRequire[0] : entityFetch.getRequirements();
	}

	/**
	 * Holds parsed GraphQL query arguments relevant for upserting entity
	 */
	private record Arguments(@Nullable Integer primaryKey,
							 @Nonnull EntityExistence entityExistence,
	                         @Nonnull List<Map<String, Object>> mutations) {

		private static Arguments from(@Nonnull DataFetchingEnvironment environment) {
			final Integer primaryKey = environment.getArgument(UpsertEntityMutationHeaderDescriptor.PRIMARY_KEY.name());
			final EntityExistence entityExistence = environment.getArgument(UpsertEntityMutationHeaderDescriptor.ENTITY_EXISTENCE.name());
			final List<Map<String, Object>> mutations = environment.getArgumentOrDefault(UpsertEntityMutationHeaderDescriptor.MUTATIONS.name(), List.of());

			return new Arguments(primaryKey, entityExistence, mutations);
		}
	}
}
