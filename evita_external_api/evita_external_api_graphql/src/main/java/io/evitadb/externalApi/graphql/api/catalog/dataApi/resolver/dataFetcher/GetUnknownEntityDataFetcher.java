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
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.externalApi.graphql.api.catalog.GraphQLContextKey;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.EntityFetchRequireResolver;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.FilterConstraintResolver;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.OrderConstraintResolver;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.RequireConstraintResolver;
import io.evitadb.externalApi.graphql.api.resolver.SelectionSetWrapper;
import io.evitadb.externalApi.graphql.api.resolver.dataFetcher.ReadDataFetcher;
import io.evitadb.externalApi.graphql.exception.GraphQLInvalidArgumentException;
import io.evitadb.externalApi.graphql.exception.GraphQLQueryResolvingInternalError;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.externalApi.api.ExternalApiNamingConventions.ARGUMENT_NAME_NAMING_CONVENTION;
import static io.evitadb.externalApi.api.ExternalApiNamingConventions.TYPE_NAME_NAMING_CONVENTION;
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
public class GetUnknownEntityDataFetcher extends ReadDataFetcher<DataFetcherResult<EntityClassifier>> {

    /**
     * Schema of catalog to which this fetcher is mapped to.
     */
    @Nonnull
    private final CatalogSchemaContract catalogSchema;
    /**
     * Function to fetch specific entity schema based on its name.
     */
    @Nonnull
    private final Function<String, EntitySchemaContract> entitySchemaFetcher;
    @Nonnull
    private final Map<String, String> entityDtoObjectTypeNameByEntityType;

    @Nonnull private final EntityFetchRequireResolver entityFetchRequireResolver;

    public GetUnknownEntityDataFetcher(@Nullable Executor executor,
                                       @Nonnull CatalogSchemaContract catalogSchema,
                                       @Nonnull Set<EntitySchemaContract> allEntitySchemas) {
        super(executor);
        this.catalogSchema = catalogSchema;
        this.entitySchemaFetcher = catalogSchema::getEntitySchemaOrThrowException;
        this.entityDtoObjectTypeNameByEntityType = createHashMap(allEntitySchemas.size());
        allEntitySchemas.forEach(entitySchema -> this.entityDtoObjectTypeNameByEntityType.put(
            entitySchema.getName(),
            entitySchema.getNameVariant(TYPE_NAME_NAMING_CONVENTION)
        ));

        final FilterConstraintResolver filterConstraintResolver = new FilterConstraintResolver(catalogSchema);
        final OrderConstraintResolver orderConstraintResolver = new OrderConstraintResolver(catalogSchema);
        final RequireConstraintResolver requireConstraintResolver = new RequireConstraintResolver(
            catalogSchema,
            new AtomicReference<>(filterConstraintResolver)
        );
        this.entityFetchRequireResolver = new EntityFetchRequireResolver(
            entitySchemaFetcher,
            filterConstraintResolver,
            orderConstraintResolver,
            requireConstraintResolver
        );
    }

    @Nonnull
    @Override
    public DataFetcherResult<EntityClassifier> doGet(@Nonnull DataFetchingEnvironment environment) {
        final Arguments arguments = Arguments.from(environment, catalogSchema);

        final FilterBy filterBy = buildFilterBy(arguments);
        final Query query = query(
            filterBy,
            require(entityFetch())
        );
        log.debug("Generated evitaDB query for single unknown entity fetch `{}`.", query);

        final EvitaSessionContract evitaSession = environment.getGraphQlContext().get(GraphQLContextKey.EVITA_SESSION);
        final EntityClassifier loadedEntity = evitaSession.queryOne(query, SealedEntity.class)
            .map(it -> {
                final String entityType = it.getType();
                final EntityContentRequire[] contentRequires = buildEnrichingRequires(environment, entityType);
                log.debug("Enriching entity reference `{}` with `{}`.", it, Arrays.toString(contentRequires));
                return evitaSession.enrichEntity(it, contentRequires);
            })
            .orElse(null);

        final DataFetcherResult.Builder<EntityClassifier> resultBuilder = DataFetcherResult.newResult();
        if (loadedEntity != null) {
            resultBuilder
                .data(loadedEntity)
                .localContext(EntityQueryContext.builder().build());
        }
        return resultBuilder.build();
    }

    @Nonnull
    private <A extends Serializable & Comparable<A>> FilterBy buildFilterBy(@Nonnull Arguments arguments) {
        final List<FilterConstraint> filterConstraints = new LinkedList<>();

        for (Map.Entry<GlobalAttributeSchemaContract, Object> attribute : arguments.globallyUniqueAttributes().entrySet()) {
            //noinspection unchecked
            filterConstraints.add(attributeEquals(attribute.getKey().getName(), (A) attribute.getValue()));
        }

        return filterBy(
            and(
                filterConstraints.toArray(FilterConstraint[]::new)
            )
        );
    }

    @Nonnull
    private EntityContentRequire[] buildEnrichingRequires(@Nonnull DataFetchingEnvironment environment,
                                                          @Nonnull String entityType) {
        final Optional<EntityFetch> entityFetch = entityFetchRequireResolver.resolveEntityFetch(
            SelectionSetWrapper.from(
                environment.getSelectionSet(),
                entityDtoObjectTypeNameByEntityType.get(entityType)
            ),
            null,
            entitySchemaFetcher.apply(entityType)
        );

        return entityFetch
            .map(EntityFetch::getRequirements)
            .orElse(new EntityContentRequire[0]);
    }

    /**
     * Holds parsed GraphQL query arguments relevant for single entity query
     */
    private record Arguments(@Nonnull Map<GlobalAttributeSchemaContract, Object> globallyUniqueAttributes) {

        private static Arguments from(@Nonnull DataFetchingEnvironment environment, @Nonnull CatalogSchemaContract catalogSchema) {
            final HashMap<String, Object> arguments = new HashMap<>(environment.getArguments());

            // left over arguments are globally unique attribute filters as defined by schema
            final Map<GlobalAttributeSchemaContract, Object> globallyUniqueAttributes = extractUniqueAttributesFromArguments(arguments, catalogSchema);

            // validate that arguments contain at least one entity identifier
            if (globallyUniqueAttributes.isEmpty()) {
                throw new GraphQLInvalidArgumentException("Missing globally unique attribute to identify entity.");
            }

            return new Arguments(globallyUniqueAttributes);
        }
    }

    private static Map<GlobalAttributeSchemaContract, Object> extractUniqueAttributesFromArguments(
        @Nonnull HashMap<String, Object> arguments,
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
