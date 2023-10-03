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
import graphql.schema.DataFetchingFieldSelectionSet;
import graphql.schema.SelectedField;
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
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.DataChunkDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ResponseDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.GraphQLContextKey;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.QueryEntitiesHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.*;
import io.evitadb.externalApi.graphql.api.resolver.SelectionSetWrapper;
import io.evitadb.externalApi.graphql.exception.GraphQLInvalidResponseUsageException;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Currency;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.strip;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Root data fetcher for fetching list of entities (or their references) of specified type. Besides returning {@link EntityDecorator}'s or
 * {@link EntityReference}s, it also sets new {@link EntityQueryContext} to be used by inner data fetchers.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Slf4j
public class QueryEntitiesDataFetcher implements DataFetcher<DataFetcherResult<EvitaResponse<EntityClassifier>>> {

	/**
	 * Schema of collection to which this fetcher is mapped to.
	 */
	@Nonnull private final EntitySchemaContract entitySchema;
	/**
	 * Entity schemas for references of {@link #entitySchema} by field-formatted names.
	 */
	@Nonnull private final Map<String, EntitySchemaContract> referencedEntitySchemas;

	@Nonnull private final FilterConstraintResolver filterConstraintResolver;
	@Nonnull private final OrderConstraintResolver orderConstraintResolver;
	@Nonnull private final RequireConstraintResolver requireConstraintResolver;
	@Nonnull private final PagingRequireResolver pagingRequireResolver;
	@Nonnull private final EntityFetchRequireResolver entityFetchRequireResolver;
	@Nonnull private final AttributeHistogramResolver attributeHistogramResolver;
	@Nonnull private final PriceHistogramResolver priceHistogramResolver;
	@Nonnull private final FacetSummaryResolver facetSummaryResolver;
	@Nonnull private final HierarchyExtraResultRequireResolver hierarchyExtraResultRequireResolver;
	@Nonnull private final QueryTelemetryResolver queryTelemetryResolver;

	@Nullable
	private static Locale extractDesiredLocale(@Nullable FilterBy filterBy) {
		return Optional.ofNullable(filterBy)
			.map(it -> QueryUtils.findConstraint(it, EntityLocaleEquals.class))
			.map(EntityLocaleEquals::getLocale)
			.orElse(null);
	}

	public QueryEntitiesDataFetcher(@Nonnull CatalogSchemaContract catalogSchema,
	                                @Nonnull EntitySchemaContract entitySchema) {
		this.entitySchema = entitySchema;
		this.referencedEntitySchemas = createHashMap(entitySchema.getReferences().size());
		entitySchema.getReferences()
			.values()
			.stream()
			.filter(ReferenceSchemaContract::isReferencedEntityTypeManaged)
			.forEach(referenceSchema -> {
				final EntitySchemaContract referencedEntitySchema = catalogSchema.getEntitySchemaOrThrowException(referenceSchema.getReferencedEntityType());
				this.referencedEntitySchemas.put(referenceSchema.getName(), referencedEntitySchema);
			});

		this.filterConstraintResolver = new FilterConstraintResolver(catalogSchema);
		this.orderConstraintResolver = new OrderConstraintResolver(catalogSchema);
		this.requireConstraintResolver = new RequireConstraintResolver(
			catalogSchema,
			new AtomicReference<>(filterConstraintResolver)
		);
		this.pagingRequireResolver = new PagingRequireResolver();
		this.entityFetchRequireResolver = new EntityFetchRequireResolver(
			catalogSchema::getEntitySchemaOrThrowException,
			filterConstraintResolver,
			orderConstraintResolver,
			requireConstraintResolver
		);
		this.attributeHistogramResolver = new AttributeHistogramResolver(entitySchema);
		this.priceHistogramResolver = new PriceHistogramResolver();
		this.facetSummaryResolver = new FacetSummaryResolver(
			entitySchema,
			referencedEntitySchemas,
			entityFetchRequireResolver,
			filterConstraintResolver,
			orderConstraintResolver
		);
		this.hierarchyExtraResultRequireResolver = new HierarchyExtraResultRequireResolver(
			entitySchema,
			catalogSchema::getEntitySchemaOrThrowException,
			entityFetchRequireResolver,
			orderConstraintResolver,
			requireConstraintResolver
		);
		this.queryTelemetryResolver = new QueryTelemetryResolver();
	}

    @Nonnull
    @Override
    public DataFetcherResult<EvitaResponse<EntityClassifier>> get(@Nonnull DataFetchingEnvironment environment) {
        final Arguments arguments = Arguments.from(environment);

		final FilterBy filterBy = buildFilterBy(arguments);
		final OrderBy orderBy = buildOrderBy(arguments);
		final Require require = buildRequire(environment, arguments, extractDesiredLocale(filterBy));
		final Query query = query(
			collection(entitySchema.getName()),
			filterBy,
			orderBy,
			require
		);
		log.debug("Generated evitaDB query for entity query fetch of type `{}` is `{}`.", entitySchema.getName(), query);

		final EvitaSessionContract evitaSession = environment.getGraphQlContext().get(GraphQLContextKey.EVITA_SESSION);
		final EvitaResponse<EntityClassifier> response = evitaSession.query(query, EntityClassifier.class);

		return DataFetcherResult.<EvitaResponse<EntityClassifier>>newResult()
			.data(response)
			.localContext(buildResultContext(query))
			.build();
	}

	@Nullable
	private FilterBy buildFilterBy(@Nonnull Arguments arguments) {
		if (arguments.filterBy() == null) {
			return null;
		}
		return (FilterBy) filterConstraintResolver.resolve(
			entitySchema.getName(),
			QueryEntitiesHeaderDescriptor.FILTER_BY.name(),
			arguments.filterBy()
		);
	}

	@Nullable
	private OrderBy buildOrderBy(@Nonnull Arguments arguments) {
		if (arguments.orderBy() == null) {
			return null;
		}
		return (OrderBy) orderConstraintResolver.resolve(
			entitySchema.getName(),
			QueryEntitiesHeaderDescriptor.ORDER_BY.name(),
			arguments.orderBy()
		);
	}

	@Nonnull
	private Require buildRequire(@Nonnull DataFetchingEnvironment environment,
	                             @Nonnull Arguments arguments,
	                             @Nullable Locale desiredLocale) {
		final List<RequireConstraint> requireConstraints = new LinkedList<>();

		// build explicit require container
		if (arguments.require() != null) {
			final Require explicitRequire = (Require) requireConstraintResolver.resolve(
				entitySchema.getName(),
				QueryEntitiesHeaderDescriptor.REQUIRE.name(),
				arguments.require()
			);
			if (explicitRequire != null) {
				requireConstraints.addAll(Arrays.asList(explicitRequire.getChildren()));
			}
		}

		final DataFetchingFieldSelectionSet selectionSet = environment.getSelectionSet();

		// build requires for returning records
		final List<SelectedField> recordFields = selectionSet.getFields(ResponseDescriptor.RECORD_PAGE.name(), ResponseDescriptor.RECORD_STRIP.name());
		Assert.isTrue(
			recordFields.size() <= 1,
			() -> new GraphQLInvalidResponseUsageException(
				"Entity response can have either `" + ResponseDescriptor.RECORD_PAGE.name() + "` or `" + ResponseDescriptor.RECORD_STRIP.name() + "`, not both."
			)
		);
		if (recordFields.isEmpty()) {
			requireConstraints.add(strip(0, 0));
		} else {
			// build paging require
			final SelectedField recordField = recordFields.get(0);
			requireConstraints.add(pagingRequireResolver.resolve(recordField));

			// build content requires
			final List<SelectedField> recordData = recordField.getSelectionSet().getFields(DataChunkDescriptor.DATA.name());
			if (!recordData.isEmpty()) {
				final SelectionSetWrapper selectionSetWrapper = SelectionSetWrapper.from(
					recordData
						.stream()
						.map(SelectedField::getSelectionSet)
						.toList()
				);

				final Optional<EntityFetch> entityFetch = entityFetchRequireResolver.resolveEntityFetch(
					selectionSetWrapper,
					desiredLocale,
					entitySchema
				);
				entityFetch.ifPresent(requireConstraints::add);
			}
		}

		// build extra result requires
		final List<SelectedField> extraResults = selectionSet.getFields(ResponseDescriptor.EXTRA_RESULTS.name());
		final SelectionSetWrapper extraResultsSelectionSet = SelectionSetWrapper.from(
			extraResults.stream()
				.map(SelectedField::getSelectionSet)
				.toList()
		);
		requireConstraints.addAll(attributeHistogramResolver.resolve(extraResultsSelectionSet));
		requireConstraints.add(priceHistogramResolver.resolve(extraResultsSelectionSet).orElse(null));
		requireConstraints.addAll(facetSummaryResolver.resolve(extraResultsSelectionSet, desiredLocale));
		requireConstraints.addAll(hierarchyExtraResultRequireResolver.resolve(extraResultsSelectionSet, desiredLocale));
		requireConstraints.add(queryTelemetryResolver.resolve(extraResultsSelectionSet).orElse(null));

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

	/**
	 * Holds parsed GraphQL query arguments relevant for entity query
	 */
	private record Arguments(@Nullable Object filterBy,
	                         @Nullable Object orderBy,
	                         @Nullable Object require) {

		private static Arguments from(@Nonnull DataFetchingEnvironment environment) {
			final Object filterBy = environment.getArgument(QueryEntitiesHeaderDescriptor.FILTER_BY.name());
			final Object orderBy = environment.getArgument(QueryEntitiesHeaderDescriptor.ORDER_BY.name());
			final Object require = environment.getArgument(QueryEntitiesHeaderDescriptor.REQUIRE.name());

			return new Arguments(filterBy, orderBy, require);
		}
	}
}
