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
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.HeadConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.head.Head;
import io.evitadb.api.query.head.Label;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.query.require.Require;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.graphql.api.catalog.GraphQLContextKey;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.dto.QueryLabelDto;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.GetEntityHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.MetadataAwareFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.QueryLabelDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.ScopeAwareFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.EntityFetchRequireResolver;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.FilterConstraintResolver;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.OrderConstraintResolver;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.RequireConstraintResolver;
import io.evitadb.externalApi.graphql.api.resolver.SelectionSetAggregator;
import io.evitadb.externalApi.graphql.api.resolver.dataFetcher.ReadDataFetcher;
import io.evitadb.externalApi.graphql.exception.GraphQLInvalidArgumentException;
import io.evitadb.externalApi.graphql.exception.GraphQLQueryResolvingInternalError;
import io.evitadb.externalApi.graphql.metric.event.request.ExecutedEvent;
import io.evitadb.externalApi.graphql.traffic.GraphQLQueryLabels;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.externalApi.api.ExternalApiNamingConventions.ARGUMENT_NAME_NAMING_CONVENTION;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Root data fetcher for fetching single entity (or its reference) of specific collection.
 * Besides returning {@link EntityDecorator} or {@link EntityReference}, it also sets new {@link EntityQueryContext}
 * to be used by inner data fetchers.
 * Each entity collection should have its own fetcher because each entity collection has its own schema.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Slf4j
public class GetEntityDataFetcher implements DataFetcher<DataFetcherResult<EntityClassifier>>, ReadDataFetcher {

	/**
	 * Schema of collection to which this fetcher is mapped to.
	 */
	@Nonnull private final EntitySchemaContract entitySchema;

	@Nonnull private final EntityFetchRequireResolver entityFetchRequireResolver;

	public GetEntityDataFetcher(@Nonnull CatalogSchemaContract catalogSchema,
	                            @Nonnull EntitySchemaContract entitySchema) {
		this.entitySchema = entitySchema;
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
	public DataFetcherResult<EntityClassifier> get(DataFetchingEnvironment environment) {
		final Arguments arguments = Arguments.from(environment, this.entitySchema);
		final ExecutedEvent requestExecutedEvent = environment.getGraphQlContext().get(GraphQLContextKey.METRIC_EXECUTED_EVENT);

		final Query query = requestExecutedEvent.measureInternalEvitaDBInputReconstruction(() -> {
			final Head head = buildHead(environment, arguments);
			final FilterBy filterBy = buildFilterBy(arguments);
			final Require require = buildRequire(environment, arguments);
			return query(
				head,
				filterBy,
				require
			);
		});
		log.debug("Generated evitaDB query for single entity fetch of type `{}` is `{}`.", this.entitySchema.getName(), query);

		final EvitaSessionContract evitaSession = environment.getGraphQlContext().get(GraphQLContextKey.EVITA_SESSION);

		final DataFetcherResult.Builder<EntityClassifier> resultBuilder = DataFetcherResult.newResult();
		final Optional<EntityClassifier> entityClassifier = requestExecutedEvent.measureInternalEvitaDBExecution(() ->
			evitaSession.queryOne(query, EntityClassifier.class));

		entityClassifier.ifPresent(entity -> resultBuilder
			.data(entity)
			.localContext(buildResultContext(arguments)));
		return resultBuilder.build();
	}

	@Nullable
	private <LV extends Serializable & Comparable<LV>> Head buildHead(@Nonnull DataFetchingEnvironment environment, @Nonnull Arguments arguments) {
		final List<HeadConstraint> headConstraints = new LinkedList<>();
		headConstraints.add(collection(this.entitySchema.getName()));
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
	private <A extends Serializable & Comparable<A>> FilterBy buildFilterBy(@Nonnull Arguments arguments) {
		final List<FilterConstraint> filterConstraints = new LinkedList<>();

		if (arguments.primaryKey() != null) {
			filterConstraints.add(entityPrimaryKeyInSet(arguments.primaryKey()));
		}
		filterConstraints.add(entityLocaleEquals(arguments.locale()));

		for (Map.Entry<AttributeSchemaContract, Object> attribute : arguments.uniqueAttributes().entrySet()) {
			//noinspection unchecked
			filterConstraints.add(attributeEquals(attribute.getKey().getName(), (A) attribute.getValue()));
		}

        //noinspection DuplicatedCode
        if (arguments.priceInPriceLists() != null) {
            filterConstraints.add(priceInPriceLists(arguments.priceInPriceLists()));
        }
        filterConstraints.add(priceInCurrency(arguments.priceInCurrency()));
        if (arguments.priceValidIn() != null) {
            filterConstraints.add(priceValidIn(arguments.priceValidIn()));
        } else if (arguments.priceValidInNow()) {
            filterConstraints.add(priceValidInNow());
        }

		 filterConstraints.add(scope(arguments.scopes()));

		return filterBy(filterConstraints.toArray(FilterConstraint[]::new));
	}

    @Nonnull
    private Require buildRequire(@Nonnull DataFetchingEnvironment environment, @Nonnull Arguments arguments) {
		final List<RequireConstraint> requireConstraints = new LinkedList<>();

	    this.entityFetchRequireResolver.resolveEntityFetch(
		    SelectionSetAggregator.from(environment.getSelectionSet()),
		    arguments.locale(),
			    this.entitySchema
	    )
		    .ifPresent(requireConstraints::add);

		requireConstraints.add(priceType(arguments.priceType()));

	    return require(requireConstraints.toArray(RequireConstraint[]::new));
    }

    @Nonnull
    private static EntityQueryContext buildResultContext(@Nonnull Arguments arguments) {
        return new EntityQueryContext(
            arguments.locale()
        );
    }

    /**
     * Holds parsed GraphQL query arguments relevant for single entity query
     */
    private record Arguments(@Nullable Integer primaryKey,
                             @Nullable Locale locale,
                             @Nullable Currency priceInCurrency,
                             @Nullable String[] priceInPriceLists,
                             @Nullable OffsetDateTime priceValidIn,
                             boolean priceValidInNow,
							 @Nonnull QueryPriceMode priceType,
							 @Nonnull Scope[] scopes,
							 @Nullable List<QueryLabelDto> labels,
                             @Nonnull Map<AttributeSchemaContract, Object> uniqueAttributes) {

		private static Arguments from(@Nonnull DataFetchingEnvironment environment, @Nonnull EntitySchemaContract entitySchema) {
			final HashMap<String, Object> arguments = new HashMap<>(environment.getArguments());

			final Integer primaryKey = (Integer) arguments.remove(GetEntityHeaderDescriptor.PRIMARY_KEY.name());

			//noinspection DuplicatedCode
			final Locale locale = (Locale) arguments.remove(GetEntityHeaderDescriptor.LOCALE.name());

            final Currency priceInCurrency = (Currency) arguments.remove(GetEntityHeaderDescriptor.PRICE_IN_CURRENCY.name());
            //noinspection unchecked
            final List<String> priceInPriceLists = (List<String>) arguments.remove(GetEntityHeaderDescriptor.PRICE_IN_PRICE_LISTS.name());
            final OffsetDateTime priceValidIn = (OffsetDateTime) arguments.remove(GetEntityHeaderDescriptor.PRICE_VALID_IN.name());
            final boolean priceValidInNow = (boolean) Optional.ofNullable(arguments.remove(GetEntityHeaderDescriptor.PRICE_VALID_NOW.name()))
                .orElse(false);
			final QueryPriceMode priceType = (QueryPriceMode) Optional.ofNullable(arguments.remove(GetEntityHeaderDescriptor.PRICE_TYPE.name()))
				.orElse(QueryPriceMode.WITH_TAX);

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

			// left over arguments are unique attribute filters as defined by schema
			final Map<AttributeSchemaContract, Object> uniqueAttributes = extractUniqueAttributesFromArguments(scopes, arguments, entitySchema);

			// validate that arguments contain at least one entity identifier
			if (primaryKey == null && uniqueAttributes.isEmpty()) {
				throw new GraphQLInvalidArgumentException(
					"Missing entity identifying argument (e.g. primary key or unique attribute)."
				);
			}

            return new Arguments(
                primaryKey,
                locale,
                priceInCurrency,
                (priceInPriceLists != null ? priceInPriceLists.toArray(String[]::new) : null),
                priceValidIn,
                priceValidInNow,
				priceType,
	            scopes,
				labels,
                uniqueAttributes
            );
        }

        private static Map<AttributeSchemaContract, Object> extractUniqueAttributesFromArguments(
			@Nonnull Scope[] requestedScopes,
            @Nonnull HashMap<String, Object> remainingArguments,
            @Nonnull EntitySchemaContract entitySchema
        ) {
            final Map<AttributeSchemaContract, Object> uniqueAttributes = createHashMap(remainingArguments.size());

			for (Map.Entry<String, Object> argument : remainingArguments.entrySet()) {
				final String attributeName = argument.getKey();
				final AttributeSchemaContract attributeSchema = entitySchema
					.getAttributeByName(attributeName, ARGUMENT_NAME_NAMING_CONVENTION)
					.orElse(null);
				if (attributeSchema == null) {
					// not a attribute argument
					continue;
				}
				Assert.isPremiseValid(
					Arrays.stream(requestedScopes).anyMatch(attributeSchema::isUniqueInScope),
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

}
