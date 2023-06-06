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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher;

import graphql.execution.DataFetcherResult;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.QueryUtils;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.PriceInCurrency;
import io.evitadb.api.query.filter.PriceInPriceLists;
import io.evitadb.api.query.filter.PriceValidIn;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.Require;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.externalApi.graphql.api.catalog.GraphQLContextKey;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.ListEntitiesQueryHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.EntityFetchRequireResolver;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.FilterConstraintResolver;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.OrderConstraintResolver;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.RequireConstraintResolver;
import io.evitadb.externalApi.graphql.api.resolver.SelectionSetWrapper;
import io.evitadb.externalApi.graphql.api.resolver.dataFetcher.ReadDataFetcher;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.strip;

/**
 * Root data fetcher for fetching list of entities (or their references) of specified type. Besides returning {@link EntityDecorator}'s or
 * {@link EntityReference}s, it also sets new {@link EntityQueryContext} to be used by inner data fetchers.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Slf4j
public class ListEntitiesDataFetcher extends ReadDataFetcher<DataFetcherResult<List<EntityClassifier>>> {

    /**
     * Entity type of collection to which this fetcher is mapped to.
     */
    @Nonnull private final EntitySchemaContract entitySchema;

    @Nonnull private final FilterConstraintResolver filterConstraintResolver;
    @Nonnull private final OrderConstraintResolver orderConstraintResolver;
    @Nonnull private final EntityFetchRequireResolver entityFetchRequireResolver;

    public ListEntitiesDataFetcher(@Nullable Executor executor,
                                   @Nonnull CatalogSchemaContract catalogSchema,
                                   @Nonnull EntitySchemaContract entitySchema) {
        super(executor);
        this.entitySchema = entitySchema;

        this.filterConstraintResolver = new FilterConstraintResolver(catalogSchema);
        this.orderConstraintResolver = new OrderConstraintResolver(catalogSchema);
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
    public DataFetcherResult<List<EntityClassifier>> doGet(@Nonnull DataFetchingEnvironment environment) {
        final Arguments arguments = Arguments.from(environment);

        final FilterBy filterBy = buildFilterBy(arguments);
        final OrderBy orderBy = buildOrderBy(arguments);
        final Require require = buildRequire(environment, arguments, filterBy);
        final Query query = query(
            collection(entitySchema.getName()),
            filterBy,
            orderBy,
            require
        );
        log.debug("Generated evitaDB query for entity list fetch of type `{}` is `{}`.", entitySchema.getName(), query);

        final EvitaSessionContract evitaSession = environment.getGraphQlContext().get(GraphQLContextKey.EVITA_SESSION);
        final List<EntityClassifier> entities = evitaSession.queryList(query, EntityClassifier.class);

        final DataFetcherResult.Builder<List<EntityClassifier>> resultBuilder = DataFetcherResult.<List<EntityClassifier>>newResult()
            .data(entities);
        if (!entities.isEmpty()) {
            resultBuilder.localContext(buildResultContext(query));
        }
        return resultBuilder.build();
    }

    @Nullable
    private <A extends Serializable & Comparable<A>> FilterBy buildFilterBy(@Nonnull Arguments arguments) {
        if (arguments.filterBy() == null) {
            return null;
        }
        return (FilterBy) filterConstraintResolver.resolve(
            entitySchema.getName(),
            ListEntitiesQueryHeaderDescriptor.FILTER_BY.name(),
            arguments.filterBy()
        );
    }

    @Nullable
    private OrderBy buildOrderBy(Arguments arguments) {
        if (arguments.orderBy() == null) {
            return null;
        }
        return (OrderBy) orderConstraintResolver.resolve(
            entitySchema.getName(),
            ListEntitiesQueryHeaderDescriptor.ORDER_BY.name(),
            arguments.orderBy()
        );
    }

    @Nonnull
    private Require buildRequire(@Nonnull DataFetchingEnvironment environment,
                                 @Nonnull Arguments arguments,
                                 @Nullable FilterBy filterBy) {

        final List<RequireConstraint> requireConstraints = new LinkedList<>();

        final Optional<EntityFetch> entityFetch = entityFetchRequireResolver.resolveEntityFetch(
            SelectionSetWrapper.from(environment.getSelectionSet()),
            extractDesiredLocale(filterBy),
            entitySchema
        );
        entityFetch.ifPresent(requireConstraints::add);

        if (arguments.limit() != null) {
            requireConstraints.add(strip(0, arguments.limit()));
        }

        return require(
            requireConstraints.toArray(RequireConstraint[]::new)
        );
    }

    @Nonnull
    private EntityQueryContext buildResultContext(@Nonnull Query query) {
        final Locale desiredLocale = Optional.ofNullable(QueryUtils.findFilter(query, EntityLocaleEquals.class))
            .map(EntityLocaleEquals::getLocale)
            .orElse(null);

        final Currency desiredPriceInCurrency = Optional.ofNullable(QueryUtils.findFilter(query, PriceInCurrency.class))
            .map(PriceInCurrency::getCurrency)
            .orElse(null);

        final Optional<PriceValidIn> priceValidInConstraint = Optional.ofNullable(QueryUtils.findFilter(query, PriceValidIn.class));
        final OffsetDateTime desiredPriceValidIn = priceValidInConstraint
            .map(PriceValidIn::getTheMoment)
            .orElse(null);
        final boolean desiredpriceValidInNow = priceValidInConstraint
            .map(it -> it.getTheMoment() == null)
            .orElse(false);

        final String[] desiredPriceInPriceLists = Optional.ofNullable(QueryUtils.findFilter(query, PriceInPriceLists.class))
            .map(PriceInPriceLists::getPriceLists)
            .orElse(null);

        return EntityQueryContext.builder()
            .desiredLocale(desiredLocale)
            .desiredPriceInCurrency(desiredPriceInCurrency)
            .desiredPriceValidIn(desiredPriceValidIn)
            .desiredpriceValidInNow(desiredpriceValidInNow)
            .desiredPriceInPriceLists(desiredPriceInPriceLists)
            .build();
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
    private record Arguments(@Nullable Object filterBy,
                             @Nullable Object orderBy,
                             @Nullable Integer limit) {

        private static Arguments from(@Nonnull DataFetchingEnvironment environment) {
            final Object filterBy = environment.getArgument(ListEntitiesQueryHeaderDescriptor.FILTER_BY.name());
            final Object orderBy = environment.getArgument(ListEntitiesQueryHeaderDescriptor.ORDER_BY.name());
            final Integer limit = environment.getArgument(ListEntitiesQueryHeaderDescriptor.LIMIT.name());

            return new Arguments(filterBy, orderBy, limit);
        }
    }

}
