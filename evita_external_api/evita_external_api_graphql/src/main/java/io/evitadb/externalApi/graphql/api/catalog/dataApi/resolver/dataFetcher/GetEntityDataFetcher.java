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
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.Require;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.externalApi.graphql.api.catalog.GraphQLContextKey;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.GetEntityQueryHeaderDescriptor;
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
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
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
public class GetEntityDataFetcher extends ReadDataFetcher<DataFetcherResult<EntityClassifier>> {

	/**
	 * Schema of collection to which this fetcher is mapped to.
	 */
	@Nonnull private final EntitySchemaContract entitySchema;

	@Nonnull private final EntityFetchRequireResolver entityFetchRequireResolver;

	public GetEntityDataFetcher(@Nullable Executor executor,
	                            @Nonnull CatalogSchemaContract catalogSchema,
	                            @Nonnull EntitySchemaContract entitySchema) {
		super(executor);
		this.entitySchema = entitySchema;
		final FilterConstraintResolver filterConstraintResolver = new FilterConstraintResolver(catalogSchema);
		final OrderConstraintResolver orderConstraintResolver = new OrderConstraintResolver(catalogSchema);
		final RequireConstraintResolver requireConstraintResolver = new RequireConstraintResolver(catalogSchema, new AtomicReference<>(filterConstraintResolver));
		this.entityFetchRequireResolver = new EntityFetchRequireResolver(
			catalogSchema::getEntitySchemaOrThrowException,
			filterConstraintResolver,
			orderConstraintResolver,
			requireConstraintResolver
		);
	}

	@Nonnull
	@Override
	public DataFetcherResult<EntityClassifier> doGet(@Nonnull DataFetchingEnvironment environment) {
		final Arguments arguments = Arguments.from(environment, entitySchema);

		final FilterBy filterBy = buildFilterBy(arguments);
		final Require require = buildRequire(environment, arguments);
		final Query query = query(
			collection(entitySchema.getName()),
			filterBy,
			require
		);
		log.debug("Generated evitaDB query for single entity fetch of type `{}` is `{}`.", entitySchema.getName(), query);

		final EvitaSessionContract evitaSession = environment.getGraphQlContext().get(GraphQLContextKey.EVITA_SESSION);

		final DataFetcherResult.Builder<EntityClassifier> resultBuilder = DataFetcherResult.newResult();
		evitaSession.queryOne(query, EntityClassifier.class)
			.ifPresent(entity -> resultBuilder
				.data(entity)
				.localContext(buildResultContext(arguments))
			);
		return resultBuilder.build();
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

		return filterBy(
			and(
				filterConstraints.toArray(FilterConstraint[]::new)
			)
		);
	}

    @Nonnull
    private Require buildRequire(@Nonnull DataFetchingEnvironment environment, @Nonnull Arguments arguments) {
	    final EntityFetch entityFetch = entityFetchRequireResolver.resolveEntityFetch(
		    SelectionSetWrapper.from(environment.getSelectionSet()),
		    arguments.locale(),
		    entitySchema
	    )
		    .orElse(null);

	    return require(entityFetch);
    }

    @Nonnull
    private static EntityQueryContext buildResultContext(@Nonnull Arguments arguments) {
        return EntityQueryContext.builder()
            .desiredLocale(arguments.locale())
            .desiredPriceInCurrency(arguments.priceInCurrency())
            .desiredPriceValidIn(arguments.priceValidIn())
            .desiredpriceValidInNow(arguments.priceValidInNow())
            .desiredPriceInPriceLists(arguments.priceInPriceLists())
            .build();
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
                             @Nonnull Map<AttributeSchemaContract, Object> uniqueAttributes) {

		private static Arguments from(@Nonnull DataFetchingEnvironment environment, @Nonnull EntitySchemaContract entitySchema) {
			final HashMap<String, Object> arguments = new HashMap<>(environment.getArguments());

			final Integer primaryKey = (Integer) arguments.remove(GetEntityQueryHeaderDescriptor.PRIMARY_KEY.name());

			//noinspection DuplicatedCode
			final Locale locale = (Locale) arguments.remove(GetEntityQueryHeaderDescriptor.LOCALE.name());

            final Currency priceInCurrency = (Currency) arguments.remove(GetEntityQueryHeaderDescriptor.PRICE_IN_CURRENCY.name());
            //noinspection unchecked
            final List<String> priceInPriceLists = (List<String>) arguments.remove(GetEntityQueryHeaderDescriptor.PRICE_IN_PRICE_LISTS.name());
            final OffsetDateTime priceValidIn = (OffsetDateTime) arguments.remove(GetEntityQueryHeaderDescriptor.PRICE_VALID_IN.name());
            final boolean priceValidInNow = (boolean) Optional.ofNullable(arguments.remove(GetEntityQueryHeaderDescriptor.PRICE_VALID_NOW.name()))
                .orElse(false);

			// left over arguments are unique attribute filters as defined by schema
			final Map<AttributeSchemaContract, Object> uniqueAttributes = extractUniqueAttributesFromArguments(arguments, entitySchema);

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
                uniqueAttributes
            );
        }

        private static Map<AttributeSchemaContract, Object> extractUniqueAttributesFromArguments(
            @Nonnull HashMap<String, Object> arguments,
            @Nonnull EntitySchemaContract entitySchema
        ) {
            final Map<AttributeSchemaContract, Object> uniqueAttributes = createHashMap(arguments.size());

			for (Map.Entry<String, Object> argument : arguments.entrySet()) {
				final String attributeName = argument.getKey();
				final AttributeSchemaContract attributeSchema = entitySchema
					.getAttributeByName(attributeName, ARGUMENT_NAME_NAMING_CONVENTION)
					.orElse(null);
				if (attributeSchema == null) {
					// not a attribute argument
					continue;
				}
				Assert.isPremiseValid(
					attributeSchema.isUnique(),
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
