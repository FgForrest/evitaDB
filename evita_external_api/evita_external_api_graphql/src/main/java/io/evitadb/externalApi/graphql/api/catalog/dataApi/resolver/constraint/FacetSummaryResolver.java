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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint;

import graphql.schema.DataFetchingFieldSelectionSet;
import graphql.schema.SelectedField;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.EntityGroupFetch;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ExtraResultsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetGroupStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetStatisticsDescriptor;
import io.evitadb.externalApi.graphql.api.resolver.SelectionSetWrapper;
import io.evitadb.externalApi.graphql.exception.GraphQLQueryResolvingInternalError;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.evitadb.api.query.QueryConstraints.facetSummaryOfReference;
import static io.evitadb.externalApi.api.ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION;
import static io.evitadb.utils.CollectionUtils.createHashMap;
import static io.evitadb.utils.CollectionUtils.createHashSet;

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
	@Nonnull private final EntityFetchRequireResolver entityFetchRequireResolver;

	@Nonnull
	public List<RequireConstraint> resolve(@Nonnull SelectionSetWrapper extraResultsSelectionSet,
	                                       @Nullable Locale desiredLocale) {
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
				final ReferenceSchemaContract reference = entitySchema.getReferenceByName(referenceName, PROPERTY_NAME_NAMING_CONVENTION)
					.orElseThrow(() -> new GraphQLQueryResolvingInternalError("Could not find reference `" + referenceName + "` in `" + entitySchema.getName() + "`."));
				final String originalReferenceName = reference.getName();
				references.add(originalReferenceName);

				final boolean impactsNeeded = SelectionSetWrapper.from(f.getSelectionSet())
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
					SelectionSetWrapper.from(f.getSelectionSet())
						.getFields(FacetGroupStatisticsDescriptor.GROUP_ENTITY.name())
						.stream()
						.map(SelectedField::getSelectionSet)
						.toList()
				);

				final List<DataFetchingFieldSelectionSet> facetEntityContentFieldsForReference = facetEntityContentFields.computeIfAbsent(originalReferenceName, k -> new LinkedList<>());
				facetEntityContentFieldsForReference.addAll(
					SelectionSetWrapper.from(f.getSelectionSet())
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

			final Optional<EntityFetch> facetEntityRequirement = entityFetchRequireResolver.resolveEntityFetch(
				SelectionSetWrapper.from(facetEntityContentFields.get(referenceName)),
				desiredLocale,
				referencedEntitySchemas.get(referenceName)
			);

			final Optional<EntityGroupFetch> groupEntityRequirement = entityFetchRequireResolver.resolveGroupFetch(
				SelectionSetWrapper.from(groupEntityContentFields.get(referenceName)),
				desiredLocale,
				referencedEntitySchemas.get(referenceName)
			);

			requestedFacetSummaries.add(facetSummaryOfReference(
				referenceName,
				statisticsDepth,
				facetEntityRequirement.orElse(null),
				groupEntityRequirement.orElse(null)
			));
		});

		return requestedFacetSummaries;
	}
}
