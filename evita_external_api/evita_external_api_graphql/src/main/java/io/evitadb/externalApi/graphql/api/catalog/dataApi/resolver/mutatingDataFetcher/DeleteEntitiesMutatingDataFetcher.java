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

import graphql.execution.DataFetcherResult;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.HeadConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.QueryUtils;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.Require;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.externalApi.graphql.api.catalog.GraphQLContextKey;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.DeleteEntitiesMutationHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.EntityFetchRequireResolver;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.FilterConstraintResolver;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.OrderConstraintResolver;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.RequireConstraintResolver;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.EntityQueryContext;
import io.evitadb.externalApi.graphql.api.resolver.SelectionSetAggregator;
import io.evitadb.externalApi.graphql.api.resolver.dataFetcher.WriteDataFetcher;
import io.evitadb.externalApi.graphql.metric.event.request.ExecutedEvent;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.strip;

/**
 * Mutating data fetcher that deletes specified entity and returns it to client.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Slf4j
public class DeleteEntitiesMutatingDataFetcher implements DataFetcher<DataFetcherResult<List<SealedEntity>>>, WriteDataFetcher {

	/**
	 * Schema of collection to which this fetcher is mapped to.
	 */
	@Nonnull private final EntitySchemaContract entitySchema;

	@Nonnull private final FilterConstraintResolver filterConstraintResolver;
	@Nonnull private final OrderConstraintResolver orderConstraintResolver;
	@Nonnull private final EntityFetchRequireResolver entityFetchRequireResolver;

	public DeleteEntitiesMutatingDataFetcher(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaContract entitySchema) {
		this.entitySchema = entitySchema;

		this.filterConstraintResolver = new FilterConstraintResolver(catalogSchema);
		this.orderConstraintResolver = new OrderConstraintResolver(
			catalogSchema,
			new AtomicReference<>(this.filterConstraintResolver)
		);
		final RequireConstraintResolver requireConstraintResolver = new RequireConstraintResolver(
			catalogSchema,
			new AtomicReference<>(this.filterConstraintResolver)
		);
		this.entityFetchRequireResolver = new EntityFetchRequireResolver(
			catalogSchema::getEntitySchemaOrThrowException,
			this.filterConstraintResolver,
			this.orderConstraintResolver,
			requireConstraintResolver
		);
	}

	@Nonnull
	@Override
	public DataFetcherResult<List<SealedEntity>> get(DataFetchingEnvironment environment) throws Exception {
		final Arguments arguments = Arguments.from(environment);
		final ExecutedEvent requestExecutedEvent = environment.getGraphQlContext().get(GraphQLContextKey.METRIC_EXECUTED_EVENT);

		final Query query = requestExecutedEvent.measureInternalEvitaDBInputReconstruction(() -> {
			final HeadConstraint head = collection(this.entitySchema.getName());
			final FilterBy filterBy = buildFilterBy(arguments);
			final OrderBy orderBy = buildOrderBy(arguments);
			final Require require = buildRequire(environment, arguments, filterBy);
			return query(
				head,
				filterBy,
				orderBy,
				require
			);
		});
		log.debug("Generated evitaDB query for entity deletion of type `{}` is `{}`.", this.entitySchema.getName(), query);

		final EvitaSessionContract evitaSession = environment.getGraphQlContext().get(GraphQLContextKey.EVITA_SESSION);
		final SealedEntity[] deletedEntities = requestExecutedEvent.measureInternalEvitaDBExecution(() ->
			evitaSession.deleteSealedEntitiesAndReturnBodies(query));

		return DataFetcherResult.<List<SealedEntity>>newResult()
			.data(Arrays.asList(deletedEntities))
			.localContext(EntityQueryContext.empty())
			.build();
	}

	@Nullable
	private FilterBy buildFilterBy(@Nonnull Arguments arguments) {
		if (arguments.filterBy() == null) {
			return null;
		}
		return (FilterBy) this.filterConstraintResolver.resolve(
			this.entitySchema.getName(),
			DeleteEntitiesMutationHeaderDescriptor.FILTER_BY.name(),
			arguments.filterBy()
		);
	}

	@Nullable
	private OrderBy buildOrderBy(Arguments arguments) {
		if (arguments.orderBy() == null) {
			return null;
		}
		return (OrderBy) this.orderConstraintResolver.resolve(
			this.entitySchema.getName(),
			DeleteEntitiesMutationHeaderDescriptor.ORDER_BY.name(),
			arguments.orderBy()
		);
	}

	@Nonnull
	private Require buildRequire(@Nonnull DataFetchingEnvironment environment,
	                             @Nonnull Arguments arguments,
	                             @Nullable FilterBy filterBy) {

		final List<RequireConstraint> requireConstraints = new LinkedList<>();

		final Optional<EntityFetch> entityFetch = this.entityFetchRequireResolver.resolveEntityFetch(
			SelectionSetAggregator.from(environment.getSelectionSet()),
			extractDesiredLocale(filterBy),
			this.entitySchema
		);
		entityFetch.ifPresentOrElse(requireConstraints::add, () -> requireConstraints.add(entityFetch()));

		if (arguments.offset() != null && arguments.limit() != null) {
			requireConstraints.add(strip(arguments.offset(), arguments.limit()));
		} else if (arguments.offset() != null) {
			requireConstraints.add(strip(arguments.offset(), 20));
		} else if (arguments.limit() != null) {
			requireConstraints.add(strip(0, arguments.limit()));
		}

		return require(
			requireConstraints.toArray(RequireConstraint[]::new)
		);
	}

	@Nullable
	private static Locale extractDesiredLocale(@Nullable FilterBy filterBy) {
		return Optional.ofNullable(filterBy)
			.map(it -> QueryUtils.findConstraint(it, EntityLocaleEquals.class))
			.map(EntityLocaleEquals::getLocale)
			.orElse(null);
	}

	/**
	 * Holds parsed GraphQL query arguments relevant for deleting entity
	 */
	private record Arguments(@Nullable Object filterBy,
	                         @Nullable Object orderBy,
							 @Nullable Integer offset,
	                         @Nullable Integer limit) {

		private static Arguments from(@Nonnull DataFetchingEnvironment environment) {
			final Object filterBy = environment.getArgument(DeleteEntitiesMutationHeaderDescriptor.FILTER_BY.name());
			final Object orderBy = environment.getArgument(DeleteEntitiesMutationHeaderDescriptor.ORDER_BY.name());
			final Integer offset = environment.getArgument(DeleteEntitiesMutationHeaderDescriptor.OFFSET.name());
			final Integer limit = environment.getArgument(DeleteEntitiesMutationHeaderDescriptor.LIMIT.name());

			return new Arguments(filterBy, orderBy, offset, limit);
		}
	}
}
