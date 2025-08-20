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
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.FilterByAwareFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.HeadAwareFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.OrderByAwareFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.RequireAwareFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.*;
import io.evitadb.externalApi.graphql.api.resolver.SelectionSetAggregator;
import io.evitadb.externalApi.graphql.api.resolver.dataFetcher.ReadDataFetcher;
import io.evitadb.externalApi.graphql.exception.GraphQLInvalidResponseUsageException;
import io.evitadb.externalApi.graphql.metric.event.request.ExecutedEvent;
import io.evitadb.externalApi.graphql.traffic.GraphQLQueryLabels;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Root data fetcher for fetching list of entities (or their references) of specified type. Besides returning {@link EntityDecorator}'s or
 * {@link EntityReference}s, it also sets new {@link EntityQueryContext} to be used by inner data fetchers.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Slf4j
public class QueryEntitiesDataFetcher implements DataFetcher<DataFetcherResult<EvitaResponse<EntityClassifier>>>, ReadDataFetcher {

	/**
	 * Schema of collection to which this fetcher is mapped to.
	 */
	@Nonnull private final EntitySchemaContract entitySchema;
	/**
	 * Entity schemas for references of {@link #entitySchema} by field-formatted names.
	 */
	@Nonnull private final Map<String, EntitySchemaContract> referencedEntitySchemas;
	/**
	 * Entity schemas of group types in references by field-formatted names.
	 */
	@Nonnull private final Map<String, EntitySchemaContract> referencedGroupEntitySchemas;

	@Nonnull private final HeadConstraintResolver headConstraintResolver;
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
		this.referencedGroupEntitySchemas = createHashMap(entitySchema.getReferences().size());
		entitySchema.getReferences()
			.values()
			.stream()
			.filter(ReferenceSchemaContract::isReferencedGroupTypeManaged)
			.forEach(referenceSchema -> {
				//noinspection DataFlowIssue
				final EntitySchemaContract referencedGroupEntitySchema = catalogSchema.getEntitySchemaOrThrowException(referenceSchema.getReferencedGroupType());
				this.referencedGroupEntitySchemas.put(referenceSchema.getName(), referencedGroupEntitySchema);
			});

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
		this.pagingRequireResolver = new PagingRequireResolver(entitySchema, this.requireConstraintResolver);
		this.entityFetchRequireResolver = new EntityFetchRequireResolver(
			catalogSchema::getEntitySchemaOrThrowException,
			this.filterConstraintResolver,
			this.orderConstraintResolver,
			this.requireConstraintResolver
		);
		this.attributeHistogramResolver = new AttributeHistogramResolver(entitySchema);
		this.priceHistogramResolver = new PriceHistogramResolver();
		this.facetSummaryResolver = new FacetSummaryResolver(
			entitySchema,
			this.referencedEntitySchemas,
			this.referencedGroupEntitySchemas,
			this.entityFetchRequireResolver,
			this.filterConstraintResolver,
			this.orderConstraintResolver
		);
		this.hierarchyExtraResultRequireResolver = new HierarchyExtraResultRequireResolver(
			entitySchema,
			catalogSchema::getEntitySchemaOrThrowException,
			this.entityFetchRequireResolver,
			this.orderConstraintResolver,
			this.requireConstraintResolver
		);
		this.queryTelemetryResolver = new QueryTelemetryResolver();
	}

    @Nonnull
    @Override
    public DataFetcherResult<EvitaResponse<EntityClassifier>> get(DataFetchingEnvironment environment) {
	    final GraphQLContext graphQlContext = environment.getGraphQlContext();

        final Arguments arguments = Arguments.from(environment);
	    final ExecutedEvent requestExecutedEvent = graphQlContext.get(GraphQLContextKey.METRIC_EXECUTED_EVENT);

	    final Query query = requestExecutedEvent.measureInternalEvitaDBInputReconstruction(() -> {
			final Head head = buildHead(environment, arguments);
		    final FilterBy filterBy = buildFilterBy(arguments);
			final OrderBy orderBy = buildOrderBy(arguments);
			final Require require = buildRequire(environment, arguments, extractDesiredLocale(filterBy));
			return query(head, filterBy, orderBy, require);
		});
		log.debug("Generated evitaDB query for entity query fetch of type `{}` is `{}`.", this.entitySchema.getName(), query);

		final EvitaSessionContract evitaSession = graphQlContext.get(GraphQLContextKey.EVITA_SESSION);
		final EvitaResponse<EntityClassifier> response = requestExecutedEvent.measureInternalEvitaDBExecution(() ->
			evitaSession.query(query, EntityClassifier.class));

		return DataFetcherResult.<EvitaResponse<EntityClassifier>>newResult()
			.data(response)
			.localContext(buildResultContext(query))
			.build();
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
	private OrderBy buildOrderBy(@Nonnull Arguments arguments) {
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
	                             @Nullable Locale desiredLocale) {
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

		final SelectionSetAggregator selectionSet = SelectionSetAggregator.from(environment.getSelectionSet());

		// build requires for returning records
		final List<SelectedField> recordFields = selectionSet.getImmediateFields(Set.of(ResponseDescriptor.RECORD_PAGE.name(), ResponseDescriptor.RECORD_STRIP.name()));
		Assert.isTrue(
			recordFields.size() <= 1,
			() -> new GraphQLInvalidResponseUsageException(
				"Entity response can have either `" + ResponseDescriptor.RECORD_PAGE.name() + "` or `" + ResponseDescriptor.RECORD_STRIP.name() + "`, not both."
			)
		);
		if (recordFields.isEmpty()) {
			requireConstraints.add(strip(0, 0));
		} else {
			final SelectedField recordField = recordFields.get(0);

			// build paging require
			requireConstraints.add(this.pagingRequireResolver.resolve(recordField));

			// build content requires
			final List<SelectedField> recordData = SelectionSetAggregator.getImmediateFields(DataChunkDescriptor.DATA.name(), recordField.getSelectionSet());
			if (!recordData.isEmpty()) {
				final SelectionSetAggregator selectionSetAggregator = SelectionSetAggregator.from(
					recordData
						.stream()
						.map(SelectedField::getSelectionSet)
						.toList()
				);

				final Optional<EntityFetch> entityFetch = this.entityFetchRequireResolver.resolveEntityFetch(
					selectionSetAggregator,
					desiredLocale,
					this.entitySchema
				);
				entityFetch.ifPresent(requireConstraints::add);
			}
		}

		// build extra result requires
		final List<SelectedField> extraResults = selectionSet.getImmediateFields(ResponseDescriptor.EXTRA_RESULTS.name());
		final SelectionSetAggregator extraResultsSelectionSet = SelectionSetAggregator.from(
			extraResults.stream()
				.map(SelectedField::getSelectionSet)
				.toList()
		);
		requireConstraints.addAll(this.attributeHistogramResolver.resolve(extraResultsSelectionSet));
		requireConstraints.add(this.priceHistogramResolver.resolve(extraResultsSelectionSet).orElse(null));
		requireConstraints.addAll(this.facetSummaryResolver.resolve(extraResultsSelectionSet, desiredLocale));
		requireConstraints.addAll(this.hierarchyExtraResultRequireResolver.resolve(extraResultsSelectionSet, desiredLocale));
		requireConstraints.add(this.queryTelemetryResolver.resolve(extraResultsSelectionSet).orElse(null));

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

	/**
	 * Holds parsed GraphQL query arguments relevant for entity query
	 */
	private record Arguments(@Nullable Object head,
	                         @Nullable Object filterBy,
	                         @Nullable Object orderBy,
	                         @Nullable Object require) {

		private static Arguments from(@Nonnull DataFetchingEnvironment environment) {
			final Object head = environment.getArgument(HeadAwareFieldHeaderDescriptor.HEAD.name());
			final Object filterBy = environment.getArgument(FilterByAwareFieldHeaderDescriptor.FILTER_BY.name());
			final Object orderBy = environment.getArgument(OrderByAwareFieldHeaderDescriptor.ORDER_BY.name());
			final Object require = environment.getArgument(RequireAwareFieldHeaderDescriptor.REQUIRE.name());

			return new Arguments(head, filterBy, orderBy, require);
		}
	}
}
