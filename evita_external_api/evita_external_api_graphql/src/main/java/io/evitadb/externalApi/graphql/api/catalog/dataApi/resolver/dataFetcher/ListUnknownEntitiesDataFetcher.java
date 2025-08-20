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
import graphql.schema.SelectedField;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.HeadConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.head.Head;
import io.evitadb.api.query.head.Label;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.Require;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.graphql.api.catalog.GraphQLContextKey;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.dto.QueryLabelDto;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.GlobalEntityDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.ListUnknownEntitiesHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.MetadataAwareFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.QueryHeaderArgumentsJoinType;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.QueryLabelDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.ScopeAwareFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.UnknownEntityHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.EntityFetchRequireResolver;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.FilterConstraintResolver;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.OrderConstraintResolver;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.RequireConstraintResolver;
import io.evitadb.externalApi.graphql.api.resolver.SelectionSetAggregator;
import io.evitadb.externalApi.graphql.api.resolver.dataFetcher.ReadDataFetcher;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import io.evitadb.externalApi.graphql.exception.GraphQLInvalidArgumentException;
import io.evitadb.externalApi.graphql.exception.GraphQLQueryResolvingInternalError;
import io.evitadb.externalApi.graphql.metric.event.request.ExecutedEvent;
import io.evitadb.externalApi.graphql.traffic.GraphQLQueryLabels;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
public class ListUnknownEntitiesDataFetcher implements DataFetcher<DataFetcherResult<List<SealedEntity>>>, ReadDataFetcher {

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

    @Nonnull
    private final Function<String, EntitySchemaContract> entitySchemaFetcher;
    @Nonnull
    private final Map<String, String> entityDtoObjectTypeNameByEntityType;

    @Nonnull private final EntityFetchRequireResolver entityFetchRequireResolver;

    public ListUnknownEntitiesDataFetcher(@Nonnull CatalogSchemaContract catalogSchema,
                                          @Nonnull Set<Locale> allPossibleLocales) {
        this.catalogSchema = catalogSchema;
        this.allPossibleLocales = allPossibleLocales;

        this.entitySchemaFetcher = catalogSchema::getEntitySchemaOrThrowException;
        this.entityDtoObjectTypeNameByEntityType = createHashMap(catalogSchema.getEntitySchemas().size());
        catalogSchema.getEntitySchemas().forEach(entitySchema -> this.entityDtoObjectTypeNameByEntityType.put(
            entitySchema.getName(),
            entitySchema.getNameVariant(TYPE_NAME_NAMING_CONVENTION)
        ));

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
	        this.entitySchemaFetcher,
            filterConstraintResolver,
            orderConstraintResolver,
            requireConstraintResolver
        );

    }

    @Nonnull
    @Override
    public DataFetcherResult<List<SealedEntity>> get(DataFetchingEnvironment environment) {
        final Arguments arguments = Arguments.from(environment, this.catalogSchema);
        final ExecutedEvent requestExecutedEvent = environment.getGraphQlContext().get(GraphQLContextKey.METRIC_EXECUTED_EVENT);

        final Query query = requestExecutedEvent.measureInternalEvitaDBInputReconstruction(() -> {
            final Head head = buildHead(environment, arguments);
            final FilterBy filterBy = buildFilterBy(arguments);
            final Require require = buildInitialRequire(environment, arguments);
            return query(
                head,
                filterBy,
                require
            );
        });
        log.debug("Generated evitaDB query for single unknown entity fetch `{}`.", query);

        final EvitaSessionContract evitaSession = environment.getGraphQlContext().get(GraphQLContextKey.EVITA_SESSION);

        final List<SealedEntity> loadedEntities = requestExecutedEvent.measureInternalEvitaDBExecution(() -> {
            final List<SealedEntity> entityReferences = evitaSession.queryList(query, SealedEntity.class);
            if (entityReferences.isEmpty()) {
                return List.of();
            } else {
                return entityReferences.stream()
                    .map(it -> {
                        final String entityType = it.getType();
                        final Optional<EntityContentRequire[]> contentRequires = buildEnrichingRequires(environment, entityType);
                        if (contentRequires.isEmpty()) {
                            log.debug("Skipping enriching entity reference `{}`. Target entity not requested.", it);
                            return it;
                        } else {
                            log.debug("Enriching entity reference `{}` with `{}`.", it, Arrays.toString(contentRequires.get()));
                            return evitaSession.enrichEntity(it, contentRequires.get());
                        }
                    })
                    .toList();
            }
        });

        final DataFetcherResult.Builder<List<SealedEntity>> resultBuilder = DataFetcherResult.<List<SealedEntity>>newResult()
            .data(loadedEntities);
        if (!loadedEntities.isEmpty()) {
            resultBuilder.localContext(buildResultContext(arguments));
        }

        return resultBuilder.build();
    }

    @Nullable
    private <LV extends Serializable & Comparable<LV>> Head buildHead(@Nonnull DataFetchingEnvironment environment, @Nonnull Arguments arguments) {
        final List<HeadConstraint> headConstraints = new LinkedList<>();
        headConstraints.add(label(Label.LABEL_SOURCE_TYPE, GraphQLQueryLabels.GRAPHQL_SOURCE_TYPE_VALUE));
        headConstraints.add(label(GraphQLQueryLabels.OPERATION_NAME, environment.getOperationDefinition().getName()));

        final GraphQLContext graphQlContext = environment.getGraphQlContext();
        final UUID sourceRecordingId = graphQlContext.get(GraphQLContextKey.TRAFFIC_SOURCE_QUERY_RECORDING_ID);
        if (sourceRecordingId != null) {
            headConstraints.add(label(Label.LABEL_SOURCE_QUERY, sourceRecordingId));
        }

        if (arguments.labels() != null) {
            for (final QueryLabelDto label : arguments.labels()) {
                //noinspection unchecked
                headConstraints.add(label(label.name(), (LV) label.value()));
            }
        }

        return head(headConstraints.toArray(HeadConstraint[]::new));
    }

    @Nonnull
    private static FilterBy buildFilterBy(@Nonnull Arguments arguments) {
        final List<FilterConstraint> filterConstraints = new LinkedList<>();

        if (arguments.locale() != null) {
            filterConstraints.add(entityLocaleEquals(arguments.locale()));
        }
        filterConstraints.add(scope(arguments.scopes()));
        filterConstraints.add(buildAttributeFilterContainer(arguments));

        return filterBy(filterConstraints.toArray(FilterConstraint[]::new));
    }

    @Nonnull
    private static <A extends Serializable & Comparable<A>> FilterConstraint buildAttributeFilterContainer(@Nonnull Arguments arguments) {
        final List<FilterConstraint> attributeConstraints = new LinkedList<>();
        for (Map.Entry<GlobalAttributeSchemaContract, List<Object>> attribute : arguments.globallyUniqueAttributes().entrySet()) {
            final GlobalAttributeSchemaContract attributeSchema = attribute.getKey();
            //noinspection unchecked,SuspiciousToArrayCall
            final A[] attributeValues = attribute.getValue().toArray(size -> (A[]) Array.newInstance(attributeSchema.getPlainType(), size));
            attributeConstraints.add(attributeInSet(attributeSchema.getName(), attributeValues));
        }

        final FilterConstraint composition;
        if (arguments.join() == QueryHeaderArgumentsJoinType.AND) {
            composition = and(attributeConstraints.toArray(FilterConstraint[]::new));
        } else if (arguments.join() == QueryHeaderArgumentsJoinType.OR) {
            composition = or(attributeConstraints.toArray(FilterConstraint[]::new));
        } else {
            throw new GraphQLInternalError("Unsupported join type `" + arguments.join() + "`.");
        }
        return composition;
    }

    @Nonnull
    private Require buildInitialRequire(@Nonnull DataFetchingEnvironment environment, @Nonnull Arguments arguments) {
        final List<RequireConstraint> requireConstraints = new LinkedList<>();

	    this.entityFetchRequireResolver.resolveEntityFetch(
                SelectionSetAggregator.from(environment.getSelectionSet()),
                null,
		        this.catalogSchema,
		        this.allPossibleLocales
            )
            .ifPresentOrElse(
                requireConstraints::add,
                // we need to have at least a body, so we can enrich it later if needed
                () -> requireConstraints.add(entityFetch())
            );

        if (arguments.limit() != null) {
            requireConstraints.add(strip(0, arguments.limit()));
        }

        return require(
            requireConstraints.toArray(RequireConstraint[]::new)
        );
    }

    @Nonnull
    private Optional<EntityContentRequire[]> buildEnrichingRequires(@Nonnull DataFetchingEnvironment environment,
                                                                    @Nonnull String entityType) {
        final List<SelectedField> targetEntityFields = SelectionSetAggregator.getImmediateFields(
            GlobalEntityDescriptor.TARGET_ENTITY.name(),
            environment.getSelectionSet()
        );
        if (targetEntityFields.isEmpty()) {
            return Optional.empty();
        }

        final Optional<EntityFetch> entityFetch = this.entityFetchRequireResolver.resolveEntityFetch(
            SelectionSetAggregator.from(
                targetEntityFields.stream().map(SelectedField::getSelectionSet).toList(),
	            this.entityDtoObjectTypeNameByEntityType.get(entityType)
            ),
            null,
	        this.entitySchemaFetcher.apply(entityType)
        );

        return Optional.of(
            entityFetch
                .map(EntityFetch::getRequirements)
                .orElse(new EntityContentRequire[0])
        );
    }

    @Nonnull
    private static EntityQueryContext buildResultContext(@Nonnull Arguments arguments) {
        return new EntityQueryContext(arguments.locale(), null, null, null, false);
    }

    /**
     * Holds parsed GraphQL query arguments relevant for single entity query
     */
    private record Arguments(@Nullable Locale locale,
                             @Nullable Integer limit,
                             @Nonnull QueryHeaderArgumentsJoinType join,
                             @Nonnull Scope[] scopes,
                             @Nullable List<QueryLabelDto> labels,
                             @Nonnull Map<GlobalAttributeSchemaContract, List<Object>> globallyUniqueAttributes) {

        private static Arguments from(@Nonnull DataFetchingEnvironment environment, @Nonnull CatalogSchemaContract catalogSchema) {
            final Map<String, Object> arguments = new HashMap<>(environment.getArguments());

            final Locale locale = (Locale) arguments.remove(UnknownEntityHeaderDescriptor.LOCALE.name());
            final Integer limit = (Integer) arguments.remove(ListUnknownEntitiesHeaderDescriptor.LIMIT.name());
            final QueryHeaderArgumentsJoinType join = (QueryHeaderArgumentsJoinType) arguments.remove(
	            UnknownEntityHeaderDescriptor.JOIN.name());
	        //noinspection unchecked
	        final Scope[] scopes = Optional.ofNullable((List<Scope>) arguments.remove(
		                                       ScopeAwareFieldHeaderDescriptor.SCOPE.name()))
                .map(it -> it.toArray(Scope[]::new))
                .orElse(Scope.DEFAULT_SCOPES);

            //noinspection unchecked
            final List<QueryLabelDto> labels = Optional.ofNullable((List<Map<String, Object>>) arguments.remove(
	                                                       MetadataAwareFieldHeaderDescriptor.LABELS.name()))
                .map(rawLabels -> rawLabels
                    .stream()
                    .map(rawLabel -> new QueryLabelDto(
                        (String) rawLabel.get(QueryLabelDescriptor.NAME.name()),
                        rawLabel.get(QueryLabelDescriptor.VALUE.name())
                    ))
                    .toList())
                .orElse(null);

            // left over arguments are globally unique attribute filters as defined by schema
            final Map<GlobalAttributeSchemaContract, List<Object>> globallyUniqueAttributes = extractUniqueAttributesFromArguments(scopes, arguments, catalogSchema);

            // validate that arguments contain at least one entity identifier
            if (globallyUniqueAttributes.isEmpty()) {
                throw new GraphQLInvalidArgumentException("Missing globally unique attribute to identify entity.");
            }

            if (locale == null &&
                Arrays.stream(scopes)
                    .anyMatch(scope ->
                        globallyUniqueAttributes.keySet()
                            .stream()
                            .anyMatch(attribute ->
                                attribute.isUniqueGloballyWithinLocaleInScope(scope)))) {
                throw new GraphQLInvalidArgumentException("Globally unique within locale attribute used but no locale was passed.");
            }

            return new Arguments(
                locale,
                limit,
                join,
                scopes,
                labels,
                globallyUniqueAttributes
            );
        }

        private static Map<GlobalAttributeSchemaContract, List<Object>> extractUniqueAttributesFromArguments(
            @Nonnull Scope[] requestedScopes,
            @Nonnull Map<String, Object> remainingArguments,
            @Nonnull CatalogSchemaContract catalogSchema
        ) {
            final Map<GlobalAttributeSchemaContract, List<Object>> uniqueAttributes = createHashMap(remainingArguments.size());

            for (Map.Entry<String, Object> argument : remainingArguments.entrySet()) {
                final String attributeName = argument.getKey();
                final GlobalAttributeSchemaContract attributeSchema = catalogSchema
                    .getAttributeByName(attributeName, ARGUMENT_NAME_NAMING_CONVENTION)
                    .orElse(null);
                if (attributeSchema == null) {
                    // not a attribute argument
                    continue;
                }
                Assert.isPremiseValid(
                    Arrays.stream(requestedScopes).anyMatch(attributeSchema::isUniqueGloballyInScope),
                    () -> new GraphQLQueryResolvingInternalError(
                        "Cannot filter list of entities by non-unique attribute `" + attributeName + "`."
                    )
                );

                //noinspection unchecked
                final List<Object> attributeValues = (List<Object>) argument.getValue();
                if (attributeValues == null || attributeValues.isEmpty()) {
                    // ignore empty argument attributes
                    continue;
                }

                uniqueAttributes.put(attributeSchema, attributeValues);
            }

            return uniqueAttributes;
        }
    }

}
