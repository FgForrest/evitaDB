/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint;

import graphql.schema.DataFetchingFieldSelectionSet;
import graphql.schema.SelectedField;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.FilterGroupBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.OrderGroupBy;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.EntityGroupFetch;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.FacetSummaryOfReference;
import io.evitadb.api.query.require.HistogramBehavior;
import io.evitadb.api.query.require.ReferenceHistogramStatistics;
import io.evitadb.api.query.require.ReferenceSummaryOfReference;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.HistogramIndexDefinition;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.DataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.EntityDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ExternalEntityTypePointer;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ManagedEntityTypePointer;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ExtraResultsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HistogramDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ReferenceHistogramDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ReferenceSummaryDescriptor.FacetStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ReferenceSummaryDescriptor.ReferenceGroupStatisticsDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.BucketsFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.ReferenceGroupStatisticsHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.ReferenceStatisticsHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.resolver.SelectionSetAggregator;
import io.evitadb.externalApi.graphql.exception.GraphQLInvalidResponseUsageException;
import io.evitadb.externalApi.graphql.exception.GraphQLQueryResolvingInternalError;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static io.evitadb.api.query.QueryConstraints.facetSummaryOfReference;
import static io.evitadb.api.query.QueryConstraints.histogramStatistics;
import static io.evitadb.api.query.QueryConstraints.referenceSummaryOfReferenceWithHistograms;
import static io.evitadb.externalApi.api.ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * FacetSummaryResolver is a utility class responsible for resolving facet summary-related GraphQL query requirements.
 * It translates requested GraphQL selection sets into appropriate constraints and fetch requirements used for data retrieval.
 *
 * The primary objective of this class is to streamline the process of interpreting GraphQL queries
 * and transforming them into the internal constraints used within the data-fetching framework.
 *
 * This class primarily works with entity schemas, reference schemas, and various resolvers to handle
 * facets, filters, and ordering constraints for the requested fields. It operates within the context
 * of a specified entity schema and its references, aiming to resolve and construct the necessary requirements.
 *
 * General Responsibilities:
 * - Handles the resolution of extra result fields such as facet summaries and their scoped constraints.
 * - Processes reference fields and determines the corresponding constraints for filtering, ordering, and statistic depth.
 * - Resolves details related to grouped facets and facet statistics based on the provided selection set.
 */
@RequiredArgsConstructor
public class ReferenceSummaryResolver extends AbstractExtraResultConstraintResolver {

	@Nonnull private final EntitySchemaContract entitySchema;
	/**
	 * Entity schemas for references of {@link #entitySchema} by field-formatted names.
	 */
	@Nonnull private final Map<String, EntitySchemaContract> referencedEntitySchemas;
	@Nonnull private final Map<String, EntitySchemaContract> referencedGroupEntitySchemas;
	@Nonnull private final EntityFetchRequireResolver entityFetchRequireResolver;
	@Nonnull private final FilterConstraintResolver filterConstraintResolver;
	@Nonnull private final OrderConstraintResolver orderConstraintResolver;

	@Nonnull
	public Collection<RequireConstraint> resolve(
		@Nonnull SelectionSetAggregator extraResultsSelectionSet,
		@Nullable Locale desiredLocale
	) {
		final List<SelectedField> referenceSummaryFields = extraResultsSelectionSet.getImmediateFields(
			Set.of(
				ExtraResultsDescriptor.REFERENCE_SUMMARY.name(),
				ExtraResultsDescriptor.FACET_SUMMARY.name() // TOBEDONE: deprecated - remove when FacetSummary constraint is removed (https://github.com/FgForrest/evitaDB/issues/538)
			)
		);
		if (referenceSummaryFields.isEmpty()) {
			return List.of();
		}

		return referenceSummaryFields.stream()
			.flatMap(facetSummaryField -> {
				final Scope scope = resolveScope(facetSummaryField);
				final DataFetchingFieldSelectionSet nestedFields = facetSummaryField.getSelectionSet();

				return SelectionSetAggregator.getImmediateFields(nestedFields)
					.stream()
					.map(facetSummaryOfReferenceField ->
					     resolveFacetSummaryOfReference(
							facetSummaryOfReferenceField,
							scope,
							desiredLocale
						)
					);
			})
			.collect(Collectors.toMap(Entry::getKey, Entry::getValue, (c, c2) -> {
				throw new GraphQLInvalidResponseUsageException(
					"Duplicate facet summaries for single reference. For each reference name, there can be only one " +
						"facet summary definition. Even across different scopes."
				);
			}))
			.values();
	}

	@Nonnull
	private Entry<String, RequireConstraint> resolveFacetSummaryOfReference(@Nonnull SelectedField field,
																			@Nullable Scope scope,
	                                                                        @Nullable Locale desiredLocale) {
		final ReferenceSchemaContract referenceSchema = this.entitySchema.getReferenceByName(field.getName(), PROPERTY_NAME_NAMING_CONVENTION)
			.orElseThrow(() -> new GraphQLQueryResolvingInternalError("Could not find reference `" + field.getName() + "` in `" + this.entitySchema.getName() + "`."));
		final String referenceName = referenceSchema.getName();

		final FilterGroupBy filterGroupBy;
		final OrderGroupBy orderGroupBy;
		if (referenceSchema.getReferencedGroupType() != null) {
			final DataLocator groupEntityDataLocator = new EntityDataLocator(
				referenceSchema.isReferencedGroupTypeManaged()
					? new ManagedEntityTypePointer(referenceSchema.getReferencedGroupType())
					: new ExternalEntityTypePointer(referenceSchema.getReferencedGroupType())
			);

			filterGroupBy = resolveGroupFilterBy(field, groupEntityDataLocator).orElse(null);
			orderGroupBy = resolveGroupOrderBy(field, groupEntityDataLocator).orElse(null);
		} else {
			filterGroupBy = null;
			orderGroupBy = null;
		}

		final List<SelectedField> facetStatisticsFields = SelectionSetAggregator.getImmediateFields(ReferenceGroupStatisticsDescriptor.FACET_STATISTICS.name(), field.getSelectionSet());
		Assert.isTrue(
			facetStatisticsFields.size() <= 1,
			() -> new GraphQLInvalidResponseUsageException("There can be only one `" + ReferenceGroupStatisticsDescriptor.FACET_STATISTICS.name() + "` field for reference `" + referenceName + "`.")
		);
		final Optional<SelectedField> facetStatisticsField = facetStatisticsFields.stream().findFirst();
		final FilterBy filterBy;
		final OrderBy orderBy;
		if (facetStatisticsField.isPresent()) {
			final DataLocator facetEntityDataLocator = new EntityDataLocator(
				referenceSchema.isReferencedEntityTypeManaged()
					? new ManagedEntityTypePointer(referenceSchema.getReferencedEntityType())
					: new ExternalEntityTypePointer(referenceSchema.getReferencedEntityType())
			);

			filterBy = resolveFacetFilterBy(facetStatisticsField.get(), facetEntityDataLocator).orElse(null);
			orderBy = resolveFacetOrderBy(facetStatisticsField.get(), facetEntityDataLocator).orElse(null);
		} else {
			filterBy = null;
			orderBy = null;
		}

		final EntityFetch facetEntityFetch = resolveFacetEntityFetch(field, desiredLocale, referenceName).orElse(null);
		final EntityGroupFetch groupEntityFetch = resolveGroupEntityFetch(field, desiredLocale, referenceName).orElse(null);

		// detect requested histogram statistics fields — the schema indexes must be resolved for the effective scope
		// coming from the query tree (or the default scope when no `inScope` wrapper is present). Each histogram
		// statistics constraint carries its own EntityFetch synthesized from the anchor-entity selections on that
		// specific index, independent of the facet entity fetch.
		final List<ReferenceHistogramStatistics> histogramStatistics =
			resolveHistogramStatistics(field, referenceSchema, scope, desiredLocale);

		final FacetStatisticsDepth depth = resolveStatisticsDepth(field);

		final RequireConstraint summaryConstraint;
		if (histogramStatistics.isEmpty()) {
			final FacetSummaryOfReference facetSummaryOfReference =
				facetSummaryOfReference(referenceName, depth, filterBy, filterGroupBy, orderBy, orderGroupBy, facetEntityFetch, groupEntityFetch);
			Assert.isPremiseValid(
				facetSummaryOfReference != null,
				() -> new GraphQLQueryResolvingInternalError("Could not resolve facet summary of reference `" + referenceName + "`. It is null.")
			);
			summaryConstraint = facetSummaryOfReference;
		} else {
			// when histogram statistics are requested, we use the dedicated `referenceSummaryOfReferenceWithHistograms`
			// factory method which accepts histogram statistics as children alongside `EntityFetch` / `EntityGroupFetch`
			final ReferenceSummaryOfReference referenceSummaryOfReference = referenceSummaryOfReferenceWithHistograms(
				referenceName,
				depth,
				filterBy, filterGroupBy,
				orderBy, orderGroupBy,
				facetEntityFetch, groupEntityFetch,
				histogramStatistics.toArray(ReferenceHistogramStatistics[]::new)
			);
			Assert.isPremiseValid(
				referenceSummaryOfReference != null,
				() -> new GraphQLQueryResolvingInternalError("Could not resolve reference summary of reference `" + referenceName + "`. It is null.")
			);
			summaryConstraint = referenceSummaryOfReference;
		}
		return new SimpleEntry<>(
			referenceName,
			wrapInScopeConstraint(scope, summaryConstraint)
		);
	}

	/**
	 * Resolves histogram statistics constraints based on histogram index fields requested in the selection set.
	 * Follows the pattern of {@link AttributeHistogramResolver}: collects histogram requests keyed by index name
	 * (each entry carries its own bucket count and behavior), then groups entries sharing the same
	 * `(requestedBucketCount, behavior)` pair into a single {@link ReferenceHistogramStatistics} constraint.
	 *
	 * Histogram fields share the same bucket count and behavior when requested on the same reference via different
	 * GraphQL aliases — a common limitation since one physical histogram computation is triggered per constraint.
	 *
	 * Only histogram indexes defined on the reference schema in the effective `scope` parsed from the query tree are
	 * considered — any field referring to an index not present in that scope is ignored.
	 */
	@Nonnull
	private List<ReferenceHistogramStatistics> resolveHistogramStatistics(
		@Nonnull SelectedField field,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nullable Scope scope,
		@Nullable Locale desiredLocale
	) {
		// collect histogram index names defined on the reference schema in the effective scope (or the default scope
		// when no `inScope` wrapper is present)
		final Scope effectiveScope = scope == null ? Scope.DEFAULT_SCOPE : scope;
		if (!referenceSchema.isBucketedInScope(effectiveScope)) {
			return List.of();
		}
		final Set<String> histogramIndexNames = referenceSchema.getHistogramIndexDefinitions(effectiveScope).keySet();
		if (histogramIndexNames.isEmpty()) {
			return List.of();
		}

		// navigate into the `histogramStatistics` wrapper field (which contains the named histogram fields)
		final List<SelectedField> histogramStatisticsFields = SelectionSetAggregator.getImmediateFields(
			ReferenceGroupStatisticsDescriptor.HISTOGRAM_STATISTICS.name(), field.getSelectionSet()
		);
		if (histogramStatisticsFields.isEmpty()) {
			return List.of();
		}

		// collect histogram requests and their anchor entity selection sets keyed by index name
		final Map<String, HistogramRequest> requestedHistograms = createHashMap(10);
		final Map<String, List<DataFetchingFieldSelectionSet>> anchorEntitySelections = createHashMap(10);
		final Set<String> anchorFieldNames = Set.of(
			ReferenceHistogramDescriptor.MIN_REFERENCED_ENTITY.name(),
			ReferenceHistogramDescriptor.MAX_REFERENCED_ENTITY.name()
		);
		histogramStatisticsFields
			.forEach(histogramStatisticsField -> {
				// each named histogram index field lives directly under the wrapper
				for (final SelectedField histogramIndexField : SelectionSetAggregator.getImmediateFields(histogramStatisticsField.getSelectionSet())) {
					final HistogramIndexDefinition histogramIndexDefinition = referenceSchema
						.getHistogramIndexDefinitionByName(effectiveScope, histogramIndexField.getName(), PROPERTY_NAME_NAMING_CONVENTION)
						.orElseThrow(() -> new GraphQLQueryResolvingInternalError(
							"Missing histogram index definition for `" + histogramIndexField.getName() + "` in reference `" + referenceSchema.getName() + "`."
						));
					final String originalIndexName = histogramIndexDefinition.nameOfTheIndex();

					final List<SelectedField> bucketsFields = SelectionSetAggregator.getImmediateFields(
						HistogramDescriptor.BUCKETS.name(), histogramIndexField.getSelectionSet()
					);
					Assert.isTrue(
						!bucketsFields.isEmpty(),
						() -> new GraphQLInvalidResponseUsageException(
							"Histogram statistics for index `" + originalIndexName + "` must have at least one `" +
								HistogramDescriptor.BUCKETS.name() + "` field."
						)
					);

					bucketsFields.forEach(bucketsField -> {
						final int requestedBucketCount = (int) bucketsField.getArguments().get(BucketsFieldHeaderDescriptor.REQUESTED_COUNT.name());
						final HistogramBehavior behavior = (HistogramBehavior) bucketsField.getArguments().getOrDefault(BucketsFieldHeaderDescriptor.BEHAVIOR.name(), HistogramBehavior.STANDARD);
						final HistogramRequest newRequest = new HistogramRequest(scope, requestedBucketCount, behavior);
						final HistogramRequest existingRequest = requestedHistograms.put(originalIndexName, newRequest);
						Assert.isTrue(
							existingRequest == null || existingRequest.equals(newRequest),
							() -> new GraphQLInvalidResponseUsageException(
								"Histogram statistics for index `" + originalIndexName + "` was already requested with different " +
									"bucket count or behavior. Only a single histogram request for each index is allowed."
							)
						);
					});

					// collect selection sets of `minReferencedEntity` / `maxReferencedEntity` for this index so a
					// dedicated `EntityFetch` can be attached to its `ReferenceHistogramStatistics` constraint
					for (final SelectedField anchorEntityField : SelectionSetAggregator.getImmediateFields(anchorFieldNames, histogramIndexField.getSelectionSet())) {
						anchorEntitySelections
							.computeIfAbsent(originalIndexName, k -> new ArrayList<>(2))
							.add(anchorEntityField.getSelectionSet());
					}
				}
			});

		if (requestedHistograms.isEmpty()) {
			return List.of();
		}

		return requestedHistograms
			.entrySet()
			.stream()
			.map(h -> {
				final String indexName = h.getKey();
				final HistogramRequest request = h.getValue();

				final EntityFetch histogramEntityFetch = Optional
					.ofNullable(anchorEntitySelections.get(indexName))
					.filter(selections -> !selections.isEmpty())
					.flatMap(selections -> this.entityFetchRequireResolver.resolveEntityFetch(
						SelectionSetAggregator.from(selections),
						desiredLocale,
						this.referencedEntitySchemas.get(referenceSchema.getName())
					))
					.orElse(null);

				final ReferenceHistogramStatistics histogramStatistics = histogramStatistics(
					request.requestedBucketCount(),
					request.behavior(),
					histogramEntityFetch,
					indexName
				);
				Assert.isPremiseValid(
					histogramStatistics != null,
					() -> new GraphQLQueryResolvingInternalError("Could not resolve histogram statistics for index `" + indexName + "`. It is null.")
				);
				// we don't neet to wrap it into scope constraint because this wrapping is happening for parent reference summary already
				return histogramStatistics;
			})
			.toList();
	}

	@Nonnull
	private static FacetStatisticsDepth resolveStatisticsDepth(@Nonnull SelectedField field) {
		// When the caller didn't select any `facetStatistics` field, signal NONE so the engine
		// skips facet-group emission for histogram-only requests — otherwise faceted-only groups
		// (e.g. CHECKBOX parameters when only `histogramStatistics` was queried) leak into the
		// response with an empty histogramStatistics map.
		final List<SelectedField> facetStatisticsFields = SelectionSetAggregator.getImmediateFields(
			ReferenceGroupStatisticsDescriptor.FACET_STATISTICS.name(), field.getSelectionSet()
		);
		if (facetStatisticsFields.isEmpty()) {
			return FacetStatisticsDepth.NONE;
		}
		final boolean impactNeeded = facetStatisticsFields.stream()
			.anyMatch(f2 -> SelectionSetAggregator.containsImmediate(FacetStatisticsDescriptor.IMPACT.name(), f2.getSelectionSet()));
		return impactNeeded ? FacetStatisticsDepth.IMPACT : FacetStatisticsDepth.COUNTS;
	}

	@Nonnull
	private Optional<FilterGroupBy> resolveGroupFilterBy(@Nonnull SelectedField field, @Nonnull DataLocator groupEntityDataLocator) {
		return Optional.ofNullable(field.getArguments().get(ReferenceGroupStatisticsHeaderDescriptor.FILTER_GROUP_BY.name()))
			.map(it -> (FilterGroupBy) this.filterConstraintResolver.resolve(groupEntityDataLocator, ReferenceGroupStatisticsHeaderDescriptor.FILTER_GROUP_BY.name(), it));
	}

	@Nonnull
	private Optional<OrderGroupBy> resolveGroupOrderBy(@Nonnull SelectedField field, @Nonnull DataLocator groupEntityDataLocator) {
		return Optional.ofNullable(field.getArguments().get(ReferenceGroupStatisticsHeaderDescriptor.ORDER_GROUP_BY.name()))
			.map(it -> (OrderGroupBy) this.orderConstraintResolver.resolve(groupEntityDataLocator, ReferenceGroupStatisticsHeaderDescriptor.ORDER_GROUP_BY.name(), it));
	}


	@Nonnull
	private Optional<FilterBy> resolveFacetFilterBy(@Nonnull SelectedField field, @Nonnull DataLocator facetEntityDataLocator) {
		return Optional.ofNullable(field.getArguments().get(ReferenceStatisticsHeaderDescriptor.FILTER_BY.name()))
			.map(it -> (FilterBy) this.filterConstraintResolver.resolve(facetEntityDataLocator, ReferenceStatisticsHeaderDescriptor.FILTER_BY.name(), it));
	}

	@Nonnull
	private Optional<OrderBy> resolveFacetOrderBy(@Nonnull SelectedField field, @Nonnull DataLocator facetEntityDataLocator) {
		return Optional.ofNullable(field.getArguments().get(ReferenceStatisticsHeaderDescriptor.ORDER_BY.name()))
			.map(it -> (OrderBy) this.orderConstraintResolver.resolve(facetEntityDataLocator, ReferenceStatisticsHeaderDescriptor.ORDER_BY.name(), it));
	}

	@Nonnull
	private Optional<EntityFetch> resolveFacetEntityFetch(@Nonnull SelectedField field,
	                                                      @Nullable Locale desiredLocale,
	                                                      @Nonnull String referenceName) {
		final List<SelectedField> facetStatisticsFields = SelectionSetAggregator.getImmediateFields(ReferenceGroupStatisticsDescriptor.FACET_STATISTICS.name(), field.getSelectionSet());
		Assert.isTrue(
			facetStatisticsFields.size() <= 1,
			() -> new GraphQLInvalidResponseUsageException("There can be only one `" + ReferenceGroupStatisticsDescriptor.FACET_STATISTICS.name() + "` field for reference `" + referenceName + "`.")
		);

		return facetStatisticsFields.stream()
			.findFirst() // we support only one facet statistics field
			.map(facetStatisticsField -> SelectionSetAggregator.getImmediateFields(
				FacetStatisticsDescriptor.FACET_ENTITY.name(), facetStatisticsField.getSelectionSet()))
			.flatMap(facetEntityFields -> {
				Assert.isTrue(
					facetEntityFields.size() <= 1,
					() -> new GraphQLInvalidResponseUsageException("There can be only one `" + FacetStatisticsDescriptor.FACET_ENTITY.name() + "` field for reference `" + referenceName + "`.")
				);

				return facetEntityFields.stream()
					.findFirst() // we support only one facet entity field
					.flatMap(facetEntityField -> this.entityFetchRequireResolver.resolveEntityFetch(
						SelectionSetAggregator.from(facetEntityField.getSelectionSet()),
						desiredLocale,
						this.referencedEntitySchemas.get(referenceName)
					));
			});
	}

	@Nonnull
	private Optional<EntityGroupFetch> resolveGroupEntityFetch(@Nonnull SelectedField field,
	                                                           @Nullable Locale desiredLocale,
	                                                           @Nonnull String referenceName) {
		final List<SelectedField> groupEntityFields = SelectionSetAggregator.getImmediateFields(
			ReferenceGroupStatisticsDescriptor.GROUP_ENTITY.name(),
			field.getSelectionSet()
		);
		Assert.isTrue(
			groupEntityFields.size() <= 1,
			() -> new GraphQLInvalidResponseUsageException("There can be only one `" + ReferenceGroupStatisticsDescriptor.GROUP_ENTITY.name() + "` field for reference `" + referenceName + "`.")
		);

		return groupEntityFields.stream()
			.findFirst() // we support only one group entity field
			.flatMap(groupEntityField -> this.entityFetchRequireResolver.resolveGroupFetch(
				SelectionSetAggregator.from(groupEntityField.getSelectionSet()),
				desiredLocale,
				this.referencedGroupEntitySchemas.get(referenceName)
			));
	}
}
