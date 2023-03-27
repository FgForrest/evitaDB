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
import io.evitadb.api.query.require.EntityGroupFetch;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.Require;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.DataChunkDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ResponseDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ExtraResultsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetGroupStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyParentsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyParentsDescriptor.ParentsOfEntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyParentsDescriptor.ParentsOfEntityDescriptor.ParentsOfReferenceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyStatisticsDescriptor.HierarchyStatisticsLevelInfoDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HistogramDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.GraphQLContextKey;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.QueryEntitiesQueryHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.ResponseHeaderDescriptor.RecordPageFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.ResponseHeaderDescriptor.RecordStripFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.FilterConstraintResolver;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.OrderConstraintResolver;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint.RequireConstraintResolver;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.extraResult.AttributeHistogramDataFetcher;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import io.evitadb.externalApi.graphql.exception.GraphQLInvalidResponseUsageException;
import io.evitadb.externalApi.graphql.exception.GraphQLQueryResolvingInternalError;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.externalApi.api.ExternalApiNamingConventions.FIELD_NAME_NAMING_CONVENTION;
import static io.evitadb.utils.CollectionUtils.createHashMap;
import static io.evitadb.utils.CollectionUtils.createHashSet;

/**
 * Root data fetcher for fetching list of entities (or their references) of specified type. Besides returning {@link EntityDecorator}'s or
 * {@link EntityReference}s, it also sets new {@link EntityQueryContext} to be used by inner data fetchers.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Slf4j
public class QueryEntitiesDataFetcher implements DataFetcher<DataFetcherResult<EvitaResponse<EntityClassifier>>> {

	/**
	 * Resolves {@link FilterBy} from client argument.
	 */
	private final FilterConstraintResolver filterByResolver;
	/**
	 * Resolves {@link OrderBy} from client argument.
	 */
	private final OrderConstraintResolver orderByResolver;
	/**
	 * Resolves {@link Require} from client argument.
	 */
	private final RequireConstraintResolver requireResolver;

	/**
	 * Entity type of collection to which this fetcher is mapped to.
	 */
	@Nonnull
	private final EntitySchemaContract entitySchema;
	/**
	 * Function to fetch specific entity schema based on its name.
	 */
	@Nonnull
	private final Function<String, EntitySchemaContract> entitySchemaFetcher;
	/**
	 * Entity schemas for references of {@link #entitySchema} by field-formatted names.
	 */
	@Nonnull
	private final Map<String, EntitySchemaContract> referencedEntitySchemas;

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
		this.entitySchemaFetcher = catalogSchema::getEntitySchemaOrThrowException;
		this.filterByResolver = new FilterConstraintResolver(catalogSchema, entitySchema.getName());
		this.orderByResolver = new OrderConstraintResolver(catalogSchema, entitySchema.getName());
		this.requireResolver = new RequireConstraintResolver(catalogSchema, entitySchema.getName());

		this.referencedEntitySchemas = createHashMap(entitySchema.getReferences().size());
		entitySchema.getReferences()
			.values()
			.stream()
			.filter(ReferenceSchemaContract::isReferencedEntityTypeManaged)
			.forEach(referenceSchema -> {
				final EntitySchemaContract referencedEntitySchema = catalogSchema.getEntitySchemaOrThrowException(referenceSchema.getReferencedEntityType());
				this.referencedEntitySchemas.put(referenceSchema.getName(), referencedEntitySchema);
			});
	}

	@Nonnull
	@Override
	public DataFetcherResult<EvitaResponse<EntityClassifier>> get(@Nonnull DataFetchingEnvironment environment) throws Exception {
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
		return (FilterBy) filterByResolver.resolve(QueryEntitiesQueryHeaderDescriptor.FILTER_BY.name(), arguments.filterBy());
	}

	@Nullable
	private OrderBy buildOrderBy(@Nonnull Arguments arguments) {
		if (arguments.orderBy() == null) {
			return null;
		}
		return (OrderBy) orderByResolver.resolve(QueryEntitiesQueryHeaderDescriptor.ORDER_BY.name(), arguments.orderBy());
	}

	@Nonnull
	private Require buildRequire(@Nonnull DataFetchingEnvironment environment,
	                             @Nonnull Arguments arguments,
	                             @Nullable FilterBy filterBy) {
		final List<RequireConstraint> requireConstraints = new LinkedList<>();

		// build explicit require container
		if (arguments.require() != null) {
			final Require explicitRequire = (Require) requireResolver.resolve(QueryEntitiesQueryHeaderDescriptor.REQUIRE.name(), arguments.require());
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
			if (recordField.getName().equals(ResponseDescriptor.RECORD_PAGE.name())) {
				final Integer pageNumber = (Integer) recordField.getArguments().getOrDefault(RecordPageFieldHeaderDescriptor.NUMBER.name(), 0);
				final Integer pageSize = (Integer) recordField.getArguments().getOrDefault(RecordPageFieldHeaderDescriptor.SIZE.name(), 20);
				requireConstraints.add(page(pageNumber, pageSize));
			} else if (recordField.getName().equals(ResponseDescriptor.RECORD_STRIP.name())) {
				final Integer offset = (Integer) recordField.getArguments().getOrDefault(RecordStripFieldHeaderDescriptor.OFFSET.name(), 0);
				final Integer limit = (Integer) recordField.getArguments().getOrDefault(RecordStripFieldHeaderDescriptor.LIMIT.name(), 20);
				requireConstraints.add(strip(offset, limit));
			} else {
				throw new GraphQLInternalError(
					"Expected field `" + ResponseDescriptor.RECORD_PAGE + "` or `" + ResponseDescriptor.RECORD_STRIP + "` but was `" + recordField.getName() + "`."
				);
			}

			// build content requires
			final List<SelectedField> recordData = recordField.getSelectionSet().getFields(DataChunkDescriptor.DATA.name());
			if (!recordData.isEmpty()) {
				final SelectionSetWrapper selectionSetWrapper = SelectionSetWrapper.from(
					recordData
						.stream()
						.map(SelectedField::getSelectionSet)
						.toList()
				);
				final Locale desiredLocale = extractDesiredLocale(filterBy);

				requireConstraints.add(
					EntityFetchRequireBuilder.buildEntityRequirement(
						selectionSetWrapper,
						desiredLocale,
						entitySchema,
						entitySchemaFetcher
					)
				);
			}
		}

		// build extra result requires
		final List<SelectedField> extraResults = selectionSet.getFields(ResponseDescriptor.EXTRA_RESULTS.name());
		final SelectionSetWrapper extraResultsSelectionSet = SelectionSetWrapper.from(
			extraResults.stream()
				.map(SelectedField::getSelectionSet)
				.toList()
		);
		requireConstraints.addAll(buildAttributeHistogramRequires(extraResultsSelectionSet));
		requireConstraints.add(buildPriceHistogramRequire(extraResultsSelectionSet));
		requireConstraints.addAll(buildFacetSummaryRequire(extraResultsSelectionSet, filterBy));
		requireConstraints.addAll(buildHierarchyParentsRequires(extraResultsSelectionSet, filterBy));
		requireConstraints.addAll(buildHierarchyStatisticsRequires(extraResultsSelectionSet, filterBy));
		requireConstraints.add(buildQueryTelemetryRequire(extraResultsSelectionSet));

		return require(
			requireConstraints.toArray(RequireConstraint[]::new)
		);
	}

	@Nonnull
	private List<RequireConstraint> buildAttributeHistogramRequires(@Nonnull SelectionSetWrapper extraResultsSelectionSet) {
		final List<SelectedField> attributeHistogramFields = extraResultsSelectionSet.getFields(ExtraResultsDescriptor.ATTRIBUTE_HISTOGRAM.name());
		// todo lho: remove after https://gitlab.fg.cz/hv/evita/-/issues/120 is implemented
		final List<SelectedField> attributeHistogramsFields = extraResultsSelectionSet.getFields("attributeHistograms");
		if (attributeHistogramFields.isEmpty() && attributeHistogramsFields.isEmpty()) {
			return List.of();
		}

		final Map<String, Integer> requestedAttributeHistograms = createHashMap(10);

		attributeHistogramFields.stream()
			.flatMap(f -> SelectionSetWrapper.from(f.getSelectionSet()).getFields("*").stream())
			.forEach(f -> {
				final AttributeSchemaContract attributeSchema = entitySchema
					.getAttributeByName(f.getName(), FIELD_NAME_NAMING_CONVENTION)
					.orElseThrow(() -> new GraphQLQueryResolvingInternalError("Missing attribute `" + f.getName() + "`."));
				final String originalAttributeName = attributeSchema.getName();

				final List<SelectedField> bucketsFields = f.getSelectionSet().getFields(HistogramDescriptor.BUCKETS.name());
				Assert.isTrue(
					!bucketsFields.isEmpty(),
					() -> new GraphQLInvalidResponseUsageException(
						"Attribute histogram for attribute `" + originalAttributeName + "` must have at least one `" + HistogramDescriptor.BUCKETS.name() + "` field."
					)
				);

				bucketsFields.forEach(bucketsField -> {
					final int requestedBucketCount = (int) bucketsField.getArguments().get(AttributeHistogramDataFetcher.REQUESTED_BUCKET_COUNT);
					final Integer alreadyRequestedBucketCount = requestedAttributeHistograms.put(originalAttributeName, requestedBucketCount);
					Assert.isTrue(
						alreadyRequestedBucketCount == null || alreadyRequestedBucketCount == requestedBucketCount,
						() -> new GraphQLInvalidResponseUsageException(
							"Attribute histogram for attribute `" + originalAttributeName + "` was already requested with bucket count `" + alreadyRequestedBucketCount + "`." +
								" Each attribute can have maximum number of one requested bucket count."
						)
					);
				});
			});

		// todo lho: remove after https://gitlab.fg.cz/hv/evita/-/issues/120 is implemented
		if (!attributeHistogramsFields.isEmpty()) {
			attributeHistogramsFields.forEach(f -> {
				//noinspection unchecked
				final List<String> attributes = ((List<String>) f.getArguments().get("attributes"))
					.stream()
					.map(a -> {
						final AttributeSchemaContract attributeSchema = entitySchema
							.getAttributeByName(a, FIELD_NAME_NAMING_CONVENTION)
							.orElseThrow(() -> new GraphQLQueryResolvingInternalError("Missing attribute `" + a + "`."));
						return attributeSchema.getName();
					})
					.toList();

				final List<SelectedField> bucketsFields = f.getSelectionSet().getFields(HistogramDescriptor.BUCKETS.name());
				Assert.isTrue(
					!bucketsFields.isEmpty(),
					() -> new GraphQLInvalidResponseUsageException(
						"Attribute histograms for attributes `" + String.join(",", attributes) + "` must have at least one `" + HistogramDescriptor.BUCKETS.name() + "` field."
					)
				);

				bucketsFields.forEach(bucketsField -> {
					final int requestedBucketCount = (int) bucketsField.getArguments().get(AttributeHistogramDataFetcher.REQUESTED_BUCKET_COUNT);
					attributes.forEach(attribute -> {
						final Integer alreadyRequestedBucketCount = requestedAttributeHistograms.put(attribute, requestedBucketCount);
						Assert.isTrue(
							alreadyRequestedBucketCount == null || alreadyRequestedBucketCount == requestedBucketCount,
							() -> new GraphQLInvalidResponseUsageException(
								"Attribute histogram for attribute `" + attribute + "` was already requested with bucket count `" + alreadyRequestedBucketCount + "`." +
									" Each attribute can have maximum number of one requested bucket count."
							)
						);
					});
				});
			});
		}

		// construct actual requires from gathered data
		//noinspection ConstantConditions
		return requestedAttributeHistograms.entrySet()
			.stream()
			.map(h -> (RequireConstraint) attributeHistogram(h.getValue(), h.getKey()))
			.toList();
	}

	@Nullable
	private RequireConstraint buildPriceHistogramRequire(@Nonnull SelectionSetWrapper extraResultsSelectionSet) {
		final List<SelectedField> priceHistogramFields = extraResultsSelectionSet.getFields(ExtraResultsDescriptor.PRICE_HISTOGRAM.name());
		if (priceHistogramFields.isEmpty()) {
			return null;
		}

		final Set<Integer> requestedBucketCounts = priceHistogramFields.stream()
			.flatMap(f -> f.getSelectionSet().getFields(HistogramDescriptor.BUCKETS.name()).stream())
			.map(f -> (int) f.getArguments().get(AttributeHistogramDataFetcher.REQUESTED_BUCKET_COUNT))
			.collect(Collectors.toSet());
		Assert.isTrue(
			!requestedBucketCounts.isEmpty(),
			() -> new GraphQLInvalidResponseUsageException(
				"Price histogram must have at least one `" + HistogramDescriptor.BUCKETS.name() + "` field."
			)
		);
		Assert.isTrue(
			requestedBucketCounts.size() == 1,
			() -> new GraphQLInvalidResponseUsageException(
				"Price histogram was requested with multiple different bucket counts. Only single count can be requested."
			)
		);

		return priceHistogram(requestedBucketCounts.iterator().next());
	}

	@Nonnull
	private List<RequireConstraint> buildFacetSummaryRequire(@Nonnull SelectionSetWrapper extraResultsSelectionSet,
	                                                         @Nullable FilterBy filterBy) {
		final List<SelectedField> facetSummaryFields = extraResultsSelectionSet.getFields(ExtraResultsDescriptor.FACET_SUMMARY.name());
		if (facetSummaryFields.isEmpty()) {
			return List.of();
		}

		final Set<String> references = createHashSet(facetSummaryFields.size());
		final Map<String, FacetStatisticsDepth> statisticsDepths = createHashMap(facetSummaryFields.size());
		final Map<String, List<DataFetchingFieldSelectionSet>> groupEntityContentFields = createHashMap(facetSummaryFields.size());
		final Map<String, List<DataFetchingFieldSelectionSet>> facetEntityContentFields = createHashMap(facetSummaryFields.size());
		facetSummaryFields.stream()
			.flatMap(f -> SelectionSetWrapper.from(f.getSelectionSet()).getFields("*").stream())
			.forEach(f -> {
				final String referenceName = f.getName();
				final ReferenceSchemaContract reference = entitySchema.getReferenceByName(referenceName, FIELD_NAME_NAMING_CONVENTION)
					.orElseThrow(() -> new GraphQLQueryResolvingInternalError("Could not find reference `" + referenceName + "` in `" + entitySchema.getName() + "`."));
				final String originalReferenceName = reference.getName();
				references.add(originalReferenceName);

				final boolean impactsNeeded = f.getSelectionSet()
					.getFields(FacetGroupStatisticsDescriptor.FACET_STATISTICS.name())
					.stream()
					.anyMatch(f2 -> f2.getSelectionSet().contains(FacetStatisticsDescriptor.IMPACT.name()));
				statisticsDepths.merge(
					originalReferenceName,
					impactsNeeded ? FacetStatisticsDepth.IMPACT : FacetStatisticsDepth.COUNTS,
					(statisticsDepth1, statisticsDepth2) -> {
						if (statisticsDepth1 == FacetStatisticsDepth.IMPACT || statisticsDepth2 == FacetStatisticsDepth.IMPACT) {
							return FacetStatisticsDepth.IMPACT;
						}
						return FacetStatisticsDepth.COUNTS;
					}
				);

				final List<DataFetchingFieldSelectionSet> groupEntityContentFieldsForReference = groupEntityContentFields.computeIfAbsent(originalReferenceName, k -> new LinkedList<>());
				groupEntityContentFieldsForReference.addAll(
					f.getSelectionSet()
						.getFields(FacetGroupStatisticsDescriptor.GROUP_ENTITY.name())
						.stream()
						.map(SelectedField::getSelectionSet)
						.toList()
				);

				final List<DataFetchingFieldSelectionSet> facetEntityContentFieldsForReference = facetEntityContentFields.computeIfAbsent(originalReferenceName, k -> new LinkedList<>());
				facetEntityContentFieldsForReference.addAll(
					f.getSelectionSet()
						.getFields(FacetGroupStatisticsDescriptor.FACET_STATISTICS.name())
						.stream()
						.flatMap(f2 -> f2.getSelectionSet().getFields(FacetStatisticsDescriptor.FACET_ENTITY.name()).stream())
						.map(SelectedField::getSelectionSet)
						.toList()
				);
			});

		// construct actual requires from gathered data
		final List<RequireConstraint> requestedFacetSummaries = new ArrayList<>(references.size());
		references.forEach(referenceName -> {
			final FacetStatisticsDepth statisticsDepth = statisticsDepths.get(referenceName);

			final EntityFetch facetEntityRequirement = EntityFetchRequireBuilder.buildEntityRequirement(
				SelectionSetWrapper.from(facetEntityContentFields.get(referenceName)),
				extractDesiredLocale(filterBy),
				referencedEntitySchemas.get(referenceName),
				entitySchemaFetcher
			);

			final EntityGroupFetch groupEntityRequirement = EntityFetchRequireBuilder.buildGroupEntityRequirement(
				SelectionSetWrapper.from(groupEntityContentFields.get(referenceName)),
				extractDesiredLocale(filterBy),
				referencedEntitySchemas.get(referenceName),
				entitySchemaFetcher
			);

			requestedFacetSummaries.add(facetSummaryOfReference(
				referenceName,
				statisticsDepth,
				facetEntityRequirement,
				groupEntityRequirement
			));
		});

		return requestedFacetSummaries;
	}

	@Nonnull
	private List<RequireConstraint> buildHierarchyParentsRequires(@Nonnull SelectionSetWrapper extraResultsSelectionSet,
	                                                              @Nullable FilterBy filterBy) {
		final List<SelectedField> parentsFields = extraResultsSelectionSet.getFields(ExtraResultsDescriptor.HIERARCHY_PARENTS.name());
		if (parentsFields.isEmpty()) {
			return List.of();
		}

		final Map<String, List<DataFetchingFieldSelectionSet>> parentsContentFields = createHashMap(parentsFields.size());
		parentsFields.stream()
			.flatMap(f -> SelectionSetWrapper.from(f.getSelectionSet()).getFields("*").stream())
			.forEach(f -> {
				final String referenceName = f.getName();
				final String originalReferenceName;
				if (referenceName.equals(HierarchyStatisticsDescriptor.SELF.name())) {
					originalReferenceName = HierarchyStatisticsDescriptor.SELF.name();
				} else {
					final ReferenceSchemaContract reference = entitySchema.getReferenceByName(referenceName, FIELD_NAME_NAMING_CONVENTION)
						.orElseThrow(() -> new GraphQLQueryResolvingInternalError("Could not find reference `" + referenceName + "` in `" + entitySchema.getName() + "`."));
					originalReferenceName = reference.getName();
				}

				final List<DataFetchingFieldSelectionSet> fields = parentsContentFields.computeIfAbsent(originalReferenceName, k -> new LinkedList<>());
				fields.addAll(
					f.getSelectionSet()
						.getFields(ParentsOfEntityDescriptor.PARENT_ENTITIES.name())
						.stream()
						.map(SelectedField::getSelectionSet)
						.toList()
				);
				fields.addAll(
					f.getSelectionSet()
						.getFields(ParentsOfEntityDescriptor.REFERENCES.name())
						.stream()
						.flatMap(f2 -> f2.getSelectionSet().getFields(ParentsOfReferenceDescriptor.PARENT_ENTITIES.name()).stream())
						.map(SelectedField::getSelectionSet)
						.toList()
				);
			});

		// construct actual requires from gathered data
		final List<RequireConstraint> requestedParents = new ArrayList<>(parentsContentFields.size());
		parentsContentFields.forEach((referenceName, contentFields) -> {
			if (referenceName.equals(HierarchyParentsDescriptor.SELF.name())) {
				requestedParents.add(hierarchyParentsOfSelf(
					EntityFetchRequireBuilder.buildEntityRequirement(
						SelectionSetWrapper.from(contentFields),
						extractDesiredLocale(filterBy),
						entitySchema,
						entitySchemaFetcher
					)
				));
			} else {
				requestedParents.add(hierarchyParentsOfReference(
					referenceName,
					EntityFetchRequireBuilder.buildEntityRequirement(
						SelectionSetWrapper.from(contentFields),
						extractDesiredLocale(filterBy),
						referencedEntitySchemas.get(referenceName),
						entitySchemaFetcher
					)
				));
			}
		});

		return requestedParents;
	}

	@Nonnull
	private List<RequireConstraint> buildHierarchyStatisticsRequires(@Nonnull SelectionSetWrapper extraResultsSelectionSet,
	                                                                 @Nullable FilterBy filterBy) {
		final List<SelectedField> hierarchyStatisticsFields = extraResultsSelectionSet.getFields(ExtraResultsDescriptor.HIERARCHY_STATISTICS.name());
		if (hierarchyStatisticsFields.isEmpty()) {
			return List.of();
		}

		final Map<String, List<DataFetchingFieldSelectionSet>> hierarchyStatisticsContentFields = createHashMap(hierarchyStatisticsFields.size());
		hierarchyStatisticsFields.stream()
			.flatMap(f -> SelectionSetWrapper.from(f.getSelectionSet()).getFields("*").stream())
			.forEach(f -> {
				final String referenceName = f.getName();
				final String originalReferenceName;
				if (referenceName.equals(HierarchyParentsDescriptor.SELF.name())) {
					originalReferenceName = HierarchyParentsDescriptor.SELF.name();
				} else {
					final ReferenceSchemaContract reference = entitySchema.getReferenceByName(referenceName, FIELD_NAME_NAMING_CONVENTION)
						.orElseThrow(() -> new GraphQLQueryResolvingInternalError("Could not find reference `" + referenceName + "` in `" + entitySchema.getName() + "`."));
					originalReferenceName = reference.getName();
				}

				final List<DataFetchingFieldSelectionSet> fields = hierarchyStatisticsContentFields.computeIfAbsent(originalReferenceName, k -> new LinkedList<>());
				fields.addAll(
					f.getSelectionSet()
						.getFields(HierarchyStatisticsLevelInfoDescriptor.ENTITY.name())
						.stream()
						.map(SelectedField::getSelectionSet)
						.toList()
				);
			});

		// construct actual requires from gathered data
		final List<RequireConstraint> requestedHierarchyStatistics = new ArrayList<>(hierarchyStatisticsContentFields.size());
		hierarchyStatisticsContentFields.forEach((referenceName, contentFields) -> {
			/* TODO LHO - reimplement please */
			/*
			if (referenceName.equals(HierarchyParentsDescriptor.SELF.name())) {
				requestedHierarchyStatistics.add(
					hierarchyOfSelf(
						EntityFetchRequireBuilder.buildEntityRequirement(
							SelectionSetWrapper.from(contentFields),
							extractDesiredLocale(filterBy),
							entitySchema,
							entitySchemaFetcher
						)
					)
				);
			} else {
				requestedHierarchyStatistics.add(
					hierarchyOfReference(
						referenceName,
						EntityFetchRequireBuilder.buildEntityRequirement(
							SelectionSetWrapper.from(contentFields),
							extractDesiredLocale(filterBy),
							referencedEntitySchemas.get(referenceName),
							entitySchemaFetcher
						)
					)
				);
			}
			 */
		});

		return requestedHierarchyStatistics;
	}

	@Nullable
	private RequireConstraint buildQueryTelemetryRequire(@Nonnull SelectionSetWrapper extraResultsSelectionSet) {
		final List<SelectedField> queryTelemetryFields = extraResultsSelectionSet.getFields(ExtraResultsDescriptor.QUERY_TELEMETRY.name());
		if (queryTelemetryFields.isEmpty()) {
			return null;
		}
		return queryTelemetry();
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
		final boolean desiredPriceValidNow = priceValidInConstraint
			.map(it -> it.getTheMoment() == null)
			.orElse(false);

		final String[] desiredPriceInPriceLists = Optional.ofNullable(QueryUtils.findFilter(query, PriceInPriceLists.class))
			.map(PriceInPriceLists::getPriceLists)
			.orElse(null);

		return EntityQueryContext.builder()
			.desiredLocale(desiredLocale)
			.desiredPriceInCurrency(desiredPriceInCurrency)
			.desiredPriceValidIn(desiredPriceValidIn)
			.desiredPriceValidNow(desiredPriceValidNow)
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
			final Object filterBy = environment.getArgument(QueryEntitiesQueryHeaderDescriptor.FILTER_BY.name());
			final Object orderBy = environment.getArgument(QueryEntitiesQueryHeaderDescriptor.ORDER_BY.name());
			final Object require = environment.getArgument(QueryEntitiesQueryHeaderDescriptor.REQUIRE.name());

			return new Arguments(filterBy, orderBy, require);
		}
	}
}
