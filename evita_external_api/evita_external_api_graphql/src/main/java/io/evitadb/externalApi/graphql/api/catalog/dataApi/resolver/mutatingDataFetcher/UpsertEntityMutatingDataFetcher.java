/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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
import io.evitadb.externalApi.graphql.api.catalog.GraphQLContextKey;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.UpsertEntityHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.EntityFetchRequireResolver;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.FilterConstraintResolver;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.OrderConstraintResolver;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.RequireConstraintResolver;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.EntityQueryContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.mutation.GraphQLEntityUpsertMutationFactory;
import io.evitadb.externalApi.graphql.api.resolver.SelectionSetAggregator;
import io.evitadb.externalApi.graphql.api.resolver.dataFetcher.WriteDataFetcher;
import io.evitadb.externalApi.graphql.metric.event.request.ExecutedEvent;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mutating data fetcher that firstly applies entity mutations to selected entity and then returns updated entity to client.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Slf4j
public class UpsertEntityMutatingDataFetcher implements DataFetcher<DataFetcherResult<EntityClassifier>>, WriteDataFetcher {

	/**
	 * Schema of collection to which this fetcher is mapped to.
	 */
	@Nonnull private final EntitySchemaContract entitySchema;

	@Nonnull private final GraphQLEntityUpsertMutationFactory entityUpsertMutationResolver;
	@Nonnull private final EntityFetchRequireResolver entityFetchRequireResolver;

	public UpsertEntityMutatingDataFetcher(@Nonnull ObjectMapper objectMapper,
										   @Nonnull CatalogSchemaContract catalogSchema,
	                                       @Nonnull EntitySchemaContract entitySchema) {
		this.entitySchema = entitySchema;
		this.entityUpsertMutationResolver = new GraphQLEntityUpsertMutationFactory(objectMapper, entitySchema);
		final FilterConstraintResolver filterConstraintResolver = new FilterConstraintResolver(catalogSchema);
		final OrderConstraintResolver orderConstraintResolver = new OrderConstraintResolver(
			catalogSchema,
			new AtomicReference<>(filterConstraintResolver)
		);
		final RequireConstraintResolver requireConstraintResolver = new RequireConstraintResolver(
			catalogSchema,
			new AtomicReference<>(filterConstraintResolver)
		);
		this.entityFetchRequireResolver = new EntityFetchRequireResolver(
			catalogSchema::getEntitySchemaOrThrowException,
			filterConstraintResolver,
			orderConstraintResolver,
			requireConstraintResolver
		);
	}

	@Nonnull
	@Override
	public DataFetcherResult<EntityClassifier> get(DataFetchingEnvironment environment) throws Exception {
		final Arguments arguments = Arguments.from(environment);
		final ExecutedEvent requestExecutedEvent = environment.getGraphQlContext().get(GraphQLContextKey.METRIC_EXECUTED_EVENT);

		final EntityMutation entityMutation = requestExecutedEvent.measureInternalEvitaDBInputReconstruction(() ->
			this.entityUpsertMutationResolver.createFromInput(arguments.primaryKey(), arguments.entityExistence(), arguments.mutations()));
		final EntityContentRequire[] contentRequires = requestExecutedEvent.measureInternalEvitaDBInputReconstruction(() ->
			buildEnrichingRequires(environment));

		final EvitaSessionContract evitaSession = environment.getGraphQlContext().get(GraphQLContextKey.EVITA_SESSION);
		log.debug("Upserting entity `{}` with PK {} and fetching new version with `{}`.", this.entitySchema.getName(), arguments.primaryKey(), Arrays.toString(contentRequires));
		final SealedEntity upsertedEntity = requestExecutedEvent.measureInternalEvitaDBExecution(() ->
			evitaSession.upsertAndFetchEntity(entityMutation, contentRequires));

		return DataFetcherResult.<EntityClassifier>newResult()
			.data(upsertedEntity)
			.localContext(EntityQueryContext.empty())
			.build();
	}

	@Nonnull
	private EntityContentRequire[] buildEnrichingRequires(@Nonnull DataFetchingEnvironment environment) {
		final Optional<EntityFetch> entityFetch = this.entityFetchRequireResolver.resolveEntityFetch(
			SelectionSetAggregator.from(environment.getSelectionSet()),
			null,
			this.entitySchema
		);
		return entityFetch
			.map(EntityFetch::getRequirements)
			.orElse(new EntityContentRequire[0]);
	}

	/**
	 * Holds parsed GraphQL query arguments relevant for upserting entity
	 */
	private record Arguments(@Nullable Integer primaryKey,
							 @Nonnull EntityExistence entityExistence,
	                         @Nonnull List<Map<String, Object>> mutations) {

		private static Arguments from(@Nonnull DataFetchingEnvironment environment) {
			final Integer primaryKey = environment.getArgument(UpsertEntityHeaderDescriptor.PRIMARY_KEY.name());
			final EntityExistence entityExistence = environment.getArgument(UpsertEntityHeaderDescriptor.ENTITY_EXISTENCE.name());
			final List<Map<String, Object>> mutations = environment.getArgumentOrDefault(UpsertEntityHeaderDescriptor.MUTATIONS.name(), List.of());

			return new Arguments(primaryKey, entityExistence, mutations);
		}
	}
}
