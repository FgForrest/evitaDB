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
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.Require;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.externalApi.graphql.api.catalog.GraphQLContextKey;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.QueryHeaderArgumentsJoinType;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.UnknownEntityHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.EntityFetchRequireResolver;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.FilterConstraintResolver;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.OrderConstraintResolver;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.RequireConstraintResolver;
import io.evitadb.externalApi.graphql.api.resolver.SelectionSetAggregator;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import io.evitadb.externalApi.graphql.exception.GraphQLInvalidArgumentException;
import io.evitadb.externalApi.graphql.exception.GraphQLQueryResolvingInternalError;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.externalApi.api.ExternalApiNamingConventions.ARGUMENT_NAME_NAMING_CONVENTION;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Root data fetcher for fetching single entity (or its reference) of unknown collection.
 * Besides returning {@link EntityDecorator} or {@link EntityReference}, it also sets new {@link EntityQueryContext}
 * to be used by inner data fetchers.
 * Because we don't want to over-fetch data by combining different required data for each different collection. First, only
 * {@link EntityReference} is fetched, then depending on fetched entity, that entity is enriched only with
 * concrete data for that entity. *
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Slf4j
public class GetUnknownEntityDataFetcher implements DataFetcher<DataFetcherResult<EntityClassifier>> {

    /**
     * Schema of catalog to which this fetcher is mapped to.
     */
    @Nonnull
    private final CatalogSchemaContract catalogSchema;
    /**
     * All locales of all collections.
     */
    @Nonnull
    private final Set<Locale> allPossibleLocales;

    @Nonnull private final EntityFetchRequireResolver entityFetchRequireResolver;

    public GetUnknownEntityDataFetcher(@Nonnull CatalogSchemaContract catalogSchema,
                                       @Nonnull Set<Locale> allPossibleLocales) {
        this.catalogSchema = catalogSchema;
        this.allPossibleLocales = allPossibleLocales;

        final FilterConstraintResolver filterConstraintResolver = new FilterConstraintResolver(catalogSchema);
        final OrderConstraintResolver orderConstraintResolver = new OrderConstraintResolver(catalogSchema);
        final RequireConstraintResolver requireConstraintResolver = new RequireConstraintResolver(
            catalogSchema,
            new AtomicReference<>(filterConstraintResolver)
        );
        this.entityFetchRequireResolver = new EntityFetchRequireResolver(
            __ -> {
                throw new GraphQLInternalError("Global entity shouldn't need to fetch other entities. This should never happen.");
            },
            filterConstraintResolver,
            orderConstraintResolver,
            requireConstraintResolver
        );
    }

    @Nonnull
    @Override
    public DataFetcherResult<EntityClassifier> get(@Nonnull DataFetchingEnvironment environment) {
        final Arguments arguments = Arguments.from(environment, catalogSchema);

        final FilterBy filterBy = buildFilterBy(arguments);
        final Require require = buildRequire(environment);
        final Query query = query(
            filterBy,
            require
        );
        log.debug("Generated evitaDB query for single unknown entity fetch `{}`.", query);

        final EvitaSessionContract evitaSession = environment.getGraphQlContext().get(GraphQLContextKey.EVITA_SESSION);

        final DataFetcherResult.Builder<EntityClassifier> resultBuilder = DataFetcherResult.newResult();
        evitaSession.queryOne(query, EntityClassifier.class)
            .ifPresent(entity -> resultBuilder
                .data(entity)
                .localContext(EntityQueryContext.empty()));
        return resultBuilder.build();
    }

    @Nonnull
    private <A extends Serializable & Comparable<A>> FilterBy buildFilterBy(@Nonnull Arguments arguments) {
        final List<FilterConstraint> filterConstraints = new LinkedList<>();

        for (Map.Entry<GlobalAttributeSchemaContract, Object> attribute : arguments.globallyUniqueAttributes().entrySet()) {
            //noinspection unchecked
            filterConstraints.add(attributeEquals(attribute.getKey().getName(), (A) attribute.getValue()));
        }

        Optional.ofNullable(arguments.locale()).ifPresent(locale -> filterConstraints.add(entityLocaleEquals(locale)));

        if (arguments.join() == QueryHeaderArgumentsJoinType.AND) {
            return filterBy(and(filterConstraints.toArray(FilterConstraint[]::new)));
        } else if (arguments.join() == QueryHeaderArgumentsJoinType.OR) {
            return filterBy(or(filterConstraints.toArray(FilterConstraint[]::new)));
        } else {
            throw new GraphQLInternalError("Unsupported join type `" + arguments.join() + "`.");
        }
    }

    @Nonnull
    private Require buildRequire(@Nonnull DataFetchingEnvironment environment) {
        final EntityFetch entityFetch = entityFetchRequireResolver.resolveEntityFetch(
                SelectionSetAggregator.from(environment.getSelectionSet()),
                null,
                catalogSchema,
                allPossibleLocales
            )
            .orElse(null);

        return require(entityFetch);
    }

    /**
     * Holds parsed GraphQL query arguments relevant for single entity query
     */
    private record Arguments(@Nullable Locale locale,
                             @Nonnull QueryHeaderArgumentsJoinType join,
                             @Nonnull Map<GlobalAttributeSchemaContract, Object> globallyUniqueAttributes) {

        private static Arguments from(@Nonnull DataFetchingEnvironment environment, @Nonnull CatalogSchemaContract catalogSchema) {
            // left over arguments are globally unique attribute filters as defined by schema
            final Map<GlobalAttributeSchemaContract, Object> globallyUniqueAttributes = extractUniqueAttributesFromArguments(environment.getArguments(), catalogSchema);

            // validate that arguments contain at least one entity identifier
            if (globallyUniqueAttributes.isEmpty()) {
                throw new GraphQLInvalidArgumentException("Missing globally unique attribute to identify entity.");
            }

            final Locale locale = environment.getArgument(UnknownEntityHeaderDescriptor.LOCALE.name());
            if (locale == null &&
                globallyUniqueAttributes.keySet().stream().anyMatch(GlobalAttributeSchemaContract::isUniqueGloballyWithinLocale)) {
                throw new GraphQLInvalidArgumentException("Globally unique within locale attribute used but no locale was passed.");
            }
            final QueryHeaderArgumentsJoinType join = environment.getArgument(UnknownEntityHeaderDescriptor.JOIN.name());

            return new Arguments(locale, join, globallyUniqueAttributes);
        }
    }

    private static Map<GlobalAttributeSchemaContract, Object> extractUniqueAttributesFromArguments(
        @Nonnull Map<String, Object> arguments,
        @Nonnull CatalogSchemaContract catalogSchema
    ) {
        final Map<GlobalAttributeSchemaContract, Object> uniqueAttributes = createHashMap(arguments.size());

        for (Map.Entry<String, Object> argument : arguments.entrySet()) {
            final String attributeName = argument.getKey();
            final GlobalAttributeSchemaContract attributeSchema = catalogSchema
                .getAttributeByName(attributeName, ARGUMENT_NAME_NAMING_CONVENTION)
                .orElse(null);
            if (attributeSchema == null) {
                // not a attribute argument
                continue;
            }
            Assert.isPremiseValid(
                attributeSchema.isUniqueGlobally(),
                () -> new GraphQLQueryResolvingInternalError(
                    "Cannot find entity by non-unique attribute `" + attributeName + "`."
                )
            );

            final Object attributeValue = argument.getValue();
            if (attributeValue == null) {
                // ignore empty argument attributes
                continue;
            }

            uniqueAttributes.put(attributeSchema, attributeValue);
        }

        return uniqueAttributes;
    }

}
