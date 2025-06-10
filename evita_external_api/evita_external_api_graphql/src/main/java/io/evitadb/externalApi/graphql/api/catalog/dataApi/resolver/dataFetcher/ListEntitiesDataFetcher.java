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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher;

import graphql.GraphQLContext;
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
import io.evitadb.api.query.filter.PriceInCurrency;
import io.evitadb.api.query.filter.PriceInPriceLists;
import io.evitadb.api.query.filter.PriceValidIn;
import io.evitadb.api.query.head.Head;
import io.evitadb.api.query.head.Label;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.Require;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.externalApi.graphql.api.catalog.GraphQLContextKey;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.FilterByAwareFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.HeadAwareFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.ListEntitiesHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.OrderByAwareFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.RequireAwareFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.EntityFetchRequireResolver;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.FilterConstraintResolver;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.HeadConstraintResolver;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.OrderConstraintResolver;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.RequireConstraintResolver;
import io.evitadb.externalApi.graphql.api.resolver.SelectionSetAggregator;
import io.evitadb.externalApi.graphql.api.resolver.dataFetcher.ReadDataFetcher;
import io.evitadb.externalApi.graphql.metric.event.request.ExecutedEvent;
import io.evitadb.externalApi.graphql.traffic.GraphQLQueryLabels;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Currency;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;

/**
 * Root data fetcher for fetching list of entities (or their references) of specified type. Besides returning {@link EntityDecorator}'s or
 * {@link EntityReference}s, it also sets new {@link EntityQueryContext} to be used by inner data fetchers.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Slf4j
public class ListEntitiesDataFetcher implements DataFetcher<DataFetcherResult<List<EntityClassifier>>>, ReadDataFetcher {

    /**
     * Schema of collection to which this fetcher is mapped to.
     */
    @Nonnull private final EntitySchemaContract entitySchema;

    @Nonnull private final HeadConstraintResolver headConstraintResolver;
    @Nonnull private final FilterConstraintResolver filterConstraintResolver;
    @Nonnull private final OrderConstraintResolver orderConstraintResolver;
    @Nonnull private final RequireConstraintResolver requireConstraintResolver;
    @Nonnull private final EntityFetchRequireResolver entityFetchRequireResolver;

    public ListEntitiesDataFetcher(@Nonnull CatalogSchemaContract catalogSchema,
                                   @Nonnull EntitySchemaContract entitySchema) {
        this.entitySchema = entitySchema;

        this.headConstraintResolver = new HeadConstraintResolver(catalogSchema);
        this.filterConstraintResolver = new FilterConstraintResolver(catalogSchema);
        this.orderConstraintResolver = new OrderConstraintResolver(
            catalogSchema,
            new AtomicReference<>(this.filterConstraintResolver)
        );
	    this.requireConstraintResolver = new RequireConstraintResolver(
            catalogSchema,
            new AtomicReference<>(this.filterConstraintResolver)
        );
        this.entityFetchRequireResolver = new EntityFetchRequireResolver(
            catalogSchema::getEntitySchemaOrThrowException,
	        this.filterConstraintResolver,
	        this.orderConstraintResolver,
            this.requireConstraintResolver
        );
    }

    @Nonnull
    @Override
    public DataFetcherResult<List<EntityClassifier>> get(DataFetchingEnvironment environment) {
        final Arguments arguments = Arguments.from(environment);
        final ExecutedEvent requestExecutedEvent = environment.getGraphQlContext().get(GraphQLContextKey.METRIC_EXECUTED_EVENT);

        final Query query = requestExecutedEvent.measureInternalEvitaDBInputReconstruction(() -> {
            final Head head = buildHead(environment, arguments);
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
        log.debug("Generated evitaDB query for entity list fetch of type `{}` is `{}`.", this.entitySchema.getName(), query);

        final EvitaSessionContract evitaSession = environment.getGraphQlContext().get(GraphQLContextKey.EVITA_SESSION);
        final List<EntityClassifier> entities = requestExecutedEvent.measureInternalEvitaDBExecution(() ->
            evitaSession.queryList(query, EntityClassifier.class));

        final DataFetcherResult.Builder<List<EntityClassifier>> resultBuilder = DataFetcherResult.<List<EntityClassifier>>newResult()
            .data(entities);
        if (!entities.isEmpty()) {
            resultBuilder.localContext(buildResultContext(query));
        }
        return resultBuilder.build();
    }

    @Nullable
    private Head buildHead(@Nonnull DataFetchingEnvironment environment, @Nonnull Arguments arguments) {
        final List<HeadConstraint> headConstraints = new LinkedList<>();
        headConstraints.add(collection(this.entitySchema.getName()));
        headConstraints.add(label(Label.LABEL_SOURCE_TYPE, GraphQLQueryLabels.GRAPHQL_SOURCE_TYPE_VALUE));
        headConstraints.add(label(GraphQLQueryLabels.OPERATION_NAME, environment.getOperationDefinition().getName()));

        final GraphQLContext graphQlContext = environment.getGraphQlContext();
        final UUID sourceRecordingId = graphQlContext.get(GraphQLContextKey.TRAFFIC_SOURCE_QUERY_RECORDING_ID);
        if (sourceRecordingId != null) {
            headConstraints.add(label(Label.LABEL_SOURCE_QUERY, sourceRecordingId));
        }

        final Head userHeadConstraints = (Head) this.headConstraintResolver.resolve(
	        this.entitySchema.getName(),
	        HeadAwareFieldHeaderDescriptor.HEAD.name(),
	        arguments.head()
        );
        if (userHeadConstraints != null) {
            headConstraints.addAll(Arrays.asList(userHeadConstraints.getChildren()));
        }

        return head(headConstraints.toArray(HeadConstraint[]::new));
    }

    @Nullable
    private FilterBy buildFilterBy(@Nonnull Arguments arguments) {
        if (arguments.filterBy() == null) {
            return null;
        }
        return (FilterBy) this.filterConstraintResolver.resolve(
	        this.entitySchema.getName(),
	        FilterByAwareFieldHeaderDescriptor.FILTER_BY.name(),
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
	        OrderByAwareFieldHeaderDescriptor.ORDER_BY.name(),
	        arguments.orderBy()
        );
    }

    @Nonnull
    private Require buildRequire(@Nonnull DataFetchingEnvironment environment,
                                 @Nonnull Arguments arguments,
                                 @Nullable FilterBy filterBy) {

        final List<RequireConstraint> requireConstraints = new LinkedList<>();

        // build explicit require container
        if (arguments.require() != null) {
            final Require explicitRequire = (Require) this.requireConstraintResolver.resolve(
	            this.entitySchema.getName(),
	            RequireAwareFieldHeaderDescriptor.REQUIRE.name(),
	            arguments.require()
            );
            if (explicitRequire != null) {
                requireConstraints.addAll(Arrays.asList(explicitRequire.getChildren()));
            }
        }

        final Optional<EntityFetch> entityFetch = this.entityFetchRequireResolver.resolveEntityFetch(
            SelectionSetAggregator.from(environment.getSelectionSet()),
            extractDesiredLocale(filterBy),
	        this.entitySchema
        );
        entityFetch.ifPresent(requireConstraints::add);

        if (arguments.offset() != null || arguments.limit() != null) {
            requireConstraints.add(
                strip(
                    Optional.ofNullable(arguments.offset()).orElse(0),
                    Optional.ofNullable(arguments.limit()).orElse(20)
                )
            );
        }

        return require(
            requireConstraints.toArray(RequireConstraint[]::new)
        );
    }

    @Nonnull
    private static EntityQueryContext buildResultContext(@Nonnull Query query) {
        final Locale desiredLocale = Optional.ofNullable(QueryUtils.findFilter(query, EntityLocaleEquals.class))
            .map(EntityLocaleEquals::getLocale)
            .orElse(null);

        final Currency desiredPriceInCurrency = Optional.ofNullable(QueryUtils.findFilter(query, PriceInCurrency.class))
            .map(PriceInCurrency::getCurrency)
            .orElse(null);

        final Optional<PriceValidIn> priceValidInConstraint = Optional.ofNullable(QueryUtils.findFilter(query, PriceValidIn.class));
        final OffsetDateTime desiredPriceValidIn = priceValidInConstraint
            .map(it -> it.getTheMoment(() -> OffsetDateTime.MIN))
            .orElse(null);

        final String[] desiredPriceInPriceLists = Optional.ofNullable(QueryUtils.findFilter(query, PriceInPriceLists.class))
            .map(PriceInPriceLists::getPriceLists)
            .orElse(null);

        return new EntityQueryContext(
            desiredLocale,
            desiredPriceInCurrency,
            desiredPriceInPriceLists,
            desiredPriceValidIn == OffsetDateTime.MIN ? null : desiredPriceValidIn,
            desiredPriceValidIn == OffsetDateTime.MIN
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
     * Holds parsed GraphQL query arguments relevant for entity list query
     */
    private record Arguments(@Nullable Object head,
                             @Nullable Object filterBy,
                             @Nullable Object orderBy,
                             @Nullable Object require,
                             @Nullable Integer offset,
                             @Nullable Integer limit) {

        private static Arguments from(@Nonnull DataFetchingEnvironment environment) {
            final Object head = environment.getArgument(HeadAwareFieldHeaderDescriptor.HEAD.name());
            final Object filterBy = environment.getArgument(FilterByAwareFieldHeaderDescriptor.FILTER_BY.name());
            final Object orderBy = environment.getArgument(OrderByAwareFieldHeaderDescriptor.ORDER_BY.name());
            final Object require = environment.getArgument(RequireAwareFieldHeaderDescriptor.REQUIRE.name());
            final Integer offset = environment.getArgument(ListEntitiesHeaderDescriptor.OFFSET.name());
            final Integer limit = environment.getArgument(ListEntitiesHeaderDescriptor.LIMIT.name());

            return new Arguments(
                head,
                filterBy,
                orderBy,
                require,
                offset,
                limit
            );
        }
    }

}
