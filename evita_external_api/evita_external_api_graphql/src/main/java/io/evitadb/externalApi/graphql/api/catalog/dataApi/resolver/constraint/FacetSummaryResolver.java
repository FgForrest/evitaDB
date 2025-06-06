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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint;

import graphql.schema.SelectedField;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.FilterGroupBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.OrderGroupBy;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.EntityGroupFetch;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.DataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.EntityDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ExternalEntityTypePointer;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ManagedEntityTypePointer;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ExtraResultsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetGroupStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetStatisticsDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.FacetGroupStatisticsHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.FacetStatisticsHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.resolver.SelectionSetAggregator;
import io.evitadb.externalApi.graphql.exception.GraphQLInvalidResponseUsageException;
import io.evitadb.externalApi.graphql.exception.GraphQLQueryResolvingInternalError;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.AbstractMap.SimpleEntry;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.stream.Collectors;

import static io.evitadb.api.query.QueryConstraints.facetSummaryOfReference;
import static io.evitadb.externalApi.api.ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION;

/**
 * Custom constraint resolver which resolves additional constraints from output fields defined by client, rather
 * than using main query.
 * Resolves {@link io.evitadb.api.query.require.FacetSummary}s based on which extra result fields client specified.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class FacetSummaryResolver {

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
	public Collection<RequireConstraint> resolve(@Nonnull SelectionSetAggregator extraResultsSelectionSet,
	                                             @Nullable Locale desiredLocale) {
		final List<SelectedField> facetSummaryFields = extraResultsSelectionSet.getImmediateFields(ExtraResultsDescriptor.FACET_SUMMARY.name());
		if (facetSummaryFields.isEmpty()) {
			return List.of();
		}

		return facetSummaryFields.stream()
			.flatMap(f -> SelectionSetAggregator.getImmediateFields(f.getSelectionSet()).stream())
			.map(f -> resolveFacetSummaryOfReference(f, desiredLocale))
			.collect(Collectors.toMap(Entry::getKey, Entry::getValue, (c, c2) -> {
				throw new GraphQLInvalidResponseUsageException("Duplicate facet summaries for single reference.");
			}))
			.values();
	}

	@Nonnull
	private Entry<String, RequireConstraint> resolveFacetSummaryOfReference(@Nonnull SelectedField field,
	                                                                        @Nullable Locale desiredLocale) {
		final ReferenceSchemaContract referenceSchema = this.entitySchema.getReferenceByName(field.getName(), PROPERTY_NAME_NAMING_CONVENTION)
			.orElseThrow(() -> new GraphQLQueryResolvingInternalError("Could not find reference `" + field.getName() + "` in `" + this.entitySchema.getName() + "`."));
		final String referenceName = referenceSchema.getName();

		final FacetStatisticsDepth depth = resolveStatisticsDepth(field);

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

		final List<SelectedField> facetStatisticsFields = SelectionSetAggregator.getImmediateFields(FacetGroupStatisticsDescriptor.FACET_STATISTICS.name(), field.getSelectionSet());
		Assert.isTrue(
			facetStatisticsFields.size() <= 1,
			() -> new GraphQLInvalidResponseUsageException("There can be only one `" + FacetGroupStatisticsDescriptor.FACET_STATISTICS.name() + "` field for reference `" + referenceName + "`.")
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

		return new SimpleEntry<>(
			referenceName,
			facetSummaryOfReference(referenceName, depth, filterBy, filterGroupBy, orderBy, orderGroupBy, facetEntityFetch, groupEntityFetch)
		);
	}

	@Nonnull
	private FacetStatisticsDepth resolveStatisticsDepth(@Nonnull SelectedField field) {
		final boolean impactNeeded = SelectionSetAggregator.getImmediateFields(FacetGroupStatisticsDescriptor.FACET_STATISTICS.name(), field.getSelectionSet())
			.stream()
			.anyMatch(f2 -> SelectionSetAggregator.containsImmediate(FacetStatisticsDescriptor.IMPACT.name(), f2.getSelectionSet()));
		return impactNeeded ? FacetStatisticsDepth.IMPACT : FacetStatisticsDepth.COUNTS;
	}

	@Nonnull
	private Optional<FilterGroupBy> resolveGroupFilterBy(@Nonnull SelectedField field, @Nonnull DataLocator groupEntityDataLocator) {
		return Optional.ofNullable(field.getArguments().get(FacetGroupStatisticsHeaderDescriptor.FILTER_GROUP_BY.name()))
			.map(it -> (FilterGroupBy) this.filterConstraintResolver.resolve(groupEntityDataLocator, FacetGroupStatisticsHeaderDescriptor.FILTER_GROUP_BY.name(), it));
	}

	@Nonnull
	private Optional<OrderGroupBy> resolveGroupOrderBy(@Nonnull SelectedField field, @Nonnull DataLocator groupEntityDataLocator) {
		return Optional.ofNullable(field.getArguments().get(FacetGroupStatisticsHeaderDescriptor.ORDER_GROUP_BY.name()))
			.map(it -> (OrderGroupBy) this.orderConstraintResolver.resolve(groupEntityDataLocator, FacetGroupStatisticsHeaderDescriptor.ORDER_GROUP_BY.name(), it));
	}


	@Nonnull
	private Optional<FilterBy> resolveFacetFilterBy(@Nonnull SelectedField field, @Nonnull DataLocator facetEntityDataLocator) {
		return Optional.ofNullable(field.getArguments().get(FacetStatisticsHeaderDescriptor.FILTER_BY.name()))
			.map(it -> (FilterBy) this.filterConstraintResolver.resolve(facetEntityDataLocator, FacetStatisticsHeaderDescriptor.FILTER_BY.name(), it));
	}

	@Nonnull
	private Optional<OrderBy> resolveFacetOrderBy(@Nonnull SelectedField field, @Nonnull DataLocator facetEntityDataLocator) {
		return Optional.ofNullable(field.getArguments().get(FacetStatisticsHeaderDescriptor.ORDER_BY.name()))
			.map(it -> (OrderBy) this.orderConstraintResolver.resolve(facetEntityDataLocator, FacetStatisticsHeaderDescriptor.ORDER_BY.name(), it));
	}

	@Nonnull
	private Optional<EntityFetch> resolveFacetEntityFetch(@Nonnull SelectedField field,
	                                                      @Nullable Locale desiredLocale,
	                                                      @Nonnull String referenceName) {
		final List<SelectedField> facetStatisticsFields = SelectionSetAggregator.getImmediateFields(FacetGroupStatisticsDescriptor.FACET_STATISTICS.name(), field.getSelectionSet());
		Assert.isTrue(
			facetStatisticsFields.size() <= 1,
			() -> new GraphQLInvalidResponseUsageException("There can be only one `" + FacetGroupStatisticsDescriptor.FACET_STATISTICS.name() + "` field for reference `" + referenceName + "`.")
		);

		return facetStatisticsFields.stream()
			.findFirst() // we support only one facet statistics field
			.map(facetStatisticsField -> SelectionSetAggregator.getImmediateFields(FacetStatisticsDescriptor.FACET_ENTITY.name(), facetStatisticsField.getSelectionSet()))
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
		final List<SelectedField> groupEntityFields = SelectionSetAggregator.getImmediateFields(FacetGroupStatisticsDescriptor.GROUP_ENTITY.name(), field.getSelectionSet());
		Assert.isTrue(
			groupEntityFields.size() <= 1,
			() -> new GraphQLInvalidResponseUsageException("There can be only one `" + FacetGroupStatisticsDescriptor.GROUP_ENTITY.name() + "` field for reference `" + referenceName + "`.")
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
