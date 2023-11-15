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

package io.evitadb.test.client.query.graphql;

import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.FacetSummary;
import io.evitadb.api.query.require.FacetSummaryOfReference;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.externalApi.api.ExternalApiNamingConventions;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.EntityDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ExtraResultsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetGroupStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetRequestImpactDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetStatisticsDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.FacetGroupStatisticsHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.FacetStatisticsHeaderDescriptor;
import io.evitadb.test.client.query.graphql.GraphQLOutputFieldsBuilder.Argument;
import io.evitadb.test.client.query.graphql.GraphQLOutputFieldsBuilder.ArgumentSupplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

/**
 * Converts {@link FacetSummary} and {@link FacetSummaryOfReference}s require constraints from {@link io.evitadb.api.query.Query}
 * into GraphQL output fields.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class FacetSummaryConverter extends RequireConverter {

	private final EntityFetchConverter entityFetchBuilder;

	public FacetSummaryConverter(@Nonnull CatalogSchemaContract catalogSchema,
	                             @Nonnull GraphQLInputJsonPrinter inputJsonPrinter) {
		super(catalogSchema, inputJsonPrinter);
		this.entityFetchBuilder = new EntityFetchConverter(catalogSchema, inputJsonPrinter);
	}

	public void convert(@Nonnull GraphQLOutputFieldsBuilder fieldsBuilder,
	                    @Nonnull String entityType,
	                    @Nullable Locale locale,
	                    @Nullable FacetSummary facetSummary,
	                    @Nonnull List<FacetSummaryOfReference> facetSummaryOfReferences) {
		if (facetSummary == null && facetSummaryOfReferences.isEmpty()) {
			return;
		}

		final EntitySchemaContract entitySchema = catalogSchema.getEntitySchemaOrThrowException(entityType);

		fieldsBuilder.addObjectField(
			ExtraResultsDescriptor.FACET_SUMMARY,
			facetSummaryBuilder -> {
				final Map<String, FacetSummaryOfReference> facetSummaryRequests = facetSummaryOfReferences.stream()
					.collect(Collectors.toMap(FacetSummaryOfReference::getReferenceName, it -> it));

				entitySchema.getReferences()
					.values()
					.stream()
					.filter(ReferenceSchemaContract::isFaceted)
					.map(referenceSchema -> {
						final FacetSummaryOfReference facetSummaryOfReference = facetSummaryRequests.get(referenceSchema.getName());
						if (facetSummaryOfReference == null) {
							return null;
						}
						return getFacetSummaryOfReference(referenceSchema, facetSummaryOfReference, facetSummary);
					})
					.filter(Objects::nonNull)
					.forEach(facetSummaryOfReference -> {
						final ReferenceSchemaContract referenceSchema = entitySchema.getReference(facetSummaryOfReference.getReferenceName())
							.orElseThrow();

						facetSummaryBuilder.addObjectField(
							referenceSchema.getNameVariant(ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION),
							facetSummaryOfReferenceBuilder -> convertFacetSummaryOfReference(
								facetSummaryBuilder,
								locale,
								referenceSchema,
								facetSummaryOfReference
							),
							getFacetGroupStatisticsArgumentsBuilder(referenceSchema, facetSummaryOfReference)
						);
					});
			}
		);
	}

	@Nonnull
	private FacetSummaryOfReference getFacetSummaryOfReference(@Nonnull ReferenceSchemaContract referenceSchema,
	                                                           @Nullable FacetSummaryOfReference facetSummaryRequest,
	                                                           @Nullable FacetSummary defaultRequest) {
		if (facetSummaryRequest == null && defaultRequest == null) {
			throw new EvitaInternalError("Either facet summary request or default request must be present!");
		}
		return ofNullable(facetSummaryRequest)
			.map(referenceRequest -> {
				if (defaultRequest == null) {
					return referenceRequest;
				}
				return new FacetSummaryOfReference(
					referenceRequest.getReferenceName(),
					referenceRequest.getStatisticsDepth(),
					referenceRequest.getFilterBy().or(defaultRequest::getFilterBy).orElse(null),
					referenceRequest.getFilterGroupBy().or(defaultRequest::getFilterGroupBy).orElse(null),
					referenceRequest.getOrderBy().or(defaultRequest::getOrderBy).orElse(null),
					referenceRequest.getOrderGroupBy().or(defaultRequest::getOrderGroupBy).orElse(null),
					referenceRequest.getFacetEntityRequirement()
						.map(it -> it.combineWith(defaultRequest.getFacetEntityRequirement().orElse(null)))
						.orElse(defaultRequest.getFacetEntityRequirement().orElse(null)),
					referenceRequest.getGroupEntityRequirement()
						.map(it -> it.combineWith(defaultRequest.getGroupEntityRequirement().orElse(null)))
						.orElse(defaultRequest.getGroupEntityRequirement().orElse(null))
				);
			})
			.orElseGet(() -> new FacetSummaryOfReference(
				referenceSchema.getName(),
				defaultRequest.getStatisticsDepth(),
				defaultRequest.getFilterBy().orElse(null),
				defaultRequest.getFilterGroupBy().orElse(null),
				defaultRequest.getOrderBy().orElse(null),
				defaultRequest.getOrderGroupBy().orElse(null),
				defaultRequest.getFacetEntityRequirement().orElse(null),
				defaultRequest.getGroupEntityRequirement().orElse(null)
			));
	}

	@Nonnull
	private ArgumentSupplier[] getFacetGroupStatisticsArgumentsBuilder(@Nonnull ReferenceSchemaContract referenceSchema,
	                                                                   @Nonnull FacetSummaryOfReference facetSummaryOfReference) {
		if (facetSummaryOfReference.getFilterGroupBy().isEmpty() && facetSummaryOfReference.getOrderGroupBy().isEmpty()) {
			return new ArgumentSupplier[0];
		}

		final List<ArgumentSupplier> arguments = new ArrayList<>(2);

		if (facetSummaryOfReference.getFilterGroupBy().isPresent()) {
			arguments.add(
				offset -> new Argument(
					FacetGroupStatisticsHeaderDescriptor.FILTER_GROUP_BY,
					offset,
					convertFilterConstraint(
						new EntityDataLocator(referenceSchema.getReferencedGroupType()),
						facetSummaryOfReference.getFilterGroupBy().get()
					)
						.orElseThrow()
				)
			);
		}


		if (facetSummaryOfReference.getOrderGroupBy().isPresent()) {
			arguments.add(
				offset -> new Argument(
					FacetGroupStatisticsHeaderDescriptor.ORDER_GROUP_BY,
					offset,
					convertOrderConstraint(
						new EntityDataLocator(referenceSchema.getReferencedGroupType()),
						facetSummaryOfReference.getOrderGroupBy().get()
					)
						.orElseThrow()
				)
			);
		}

		return arguments.toArray(ArgumentSupplier[]::new);
	}

	private void convertFacetSummaryOfReference(@Nonnull GraphQLOutputFieldsBuilder facetSummaryOfReferenceBuilder,
	                                            @Nullable Locale locale,
	                                            @Nonnull ReferenceSchemaContract referenceSchema,
	                                            @Nonnull FacetSummaryOfReference facetSummaryOfReference) {

		facetSummaryOfReferenceBuilder
			.addPrimitiveField(FacetGroupStatisticsDescriptor.COUNT)
			.addObjectField(
				FacetGroupStatisticsDescriptor.GROUP_ENTITY,
				groupEntityBuilder -> entityFetchBuilder.convert(
					groupEntityBuilder,
					referenceSchema.getReferencedGroupType(),
					locale,
					facetSummaryOfReference.getGroupEntityRequirement().orElse(null)
				)
			)
			.addObjectField(
				FacetGroupStatisticsDescriptor.FACET_STATISTICS,
				facetStatisticsBuilder -> convertFacetStatistics(
					facetStatisticsBuilder,
					locale,
					referenceSchema,
					facetSummaryOfReference
				),
				getFacetStatisticsArgumentsBuilder(referenceSchema, facetSummaryOfReference)
			);
	}

	@Nonnull
	private ArgumentSupplier[] getFacetStatisticsArgumentsBuilder(@Nonnull ReferenceSchemaContract referenceSchema,
	                                                              @Nonnull FacetSummaryOfReference facetSummaryOfReference) {
		if (facetSummaryOfReference.getFilterBy().isEmpty() && facetSummaryOfReference.getOrderBy().isEmpty()) {
			return new ArgumentSupplier[0];
		}

		final List<ArgumentSupplier> arguments = new ArrayList<>(2);

		if (facetSummaryOfReference.getFilterBy().isPresent()) {
			arguments.add(
				offset -> new Argument(
					FacetStatisticsHeaderDescriptor.FILTER_BY,
					offset,
					convertFilterConstraint(
						new EntityDataLocator(referenceSchema.getReferencedEntityType()),
						facetSummaryOfReference.getFilterBy().get()
					)
						.orElseThrow()
				)
			);
		}

		if (facetSummaryOfReference.getOrderBy().isPresent()) {
			arguments.add(
				offset -> new Argument(
					FacetStatisticsHeaderDescriptor.ORDER_BY,
					offset,
					convertOrderConstraint(
						new EntityDataLocator(referenceSchema.getReferencedEntityType()),
						facetSummaryOfReference.getOrderBy().get()
					)
						.orElseThrow()
				)
			);
		}

		return arguments.toArray(ArgumentSupplier[]::new);
	}

	private void convertFacetStatistics(@Nonnull GraphQLOutputFieldsBuilder facetStatisticsBuilder,
										@Nullable Locale locale,
										@Nonnull ReferenceSchemaContract referenceSchema,
	                                    @Nonnull FacetSummaryOfReference facetSummaryOfReference) {
		facetStatisticsBuilder
			.addPrimitiveField(FacetStatisticsDescriptor.REQUESTED)
			.addPrimitiveField(FacetStatisticsDescriptor.COUNT);

		if (facetSummaryOfReference.getStatisticsDepth() == FacetStatisticsDepth.IMPACT) {
			facetStatisticsBuilder.addObjectField(
				FacetStatisticsDescriptor.IMPACT,
				impactBuilder -> impactBuilder
					.addPrimitiveField(FacetRequestImpactDescriptor.DIFFERENCE)
					.addPrimitiveField(FacetRequestImpactDescriptor.MATCH_COUNT)
					.addPrimitiveField(FacetRequestImpactDescriptor.HAS_SENSE)
			);
		}

		facetStatisticsBuilder.addObjectField(
			FacetStatisticsDescriptor.FACET_ENTITY,
			facetEntityBuilder -> entityFetchBuilder.convert(
				facetEntityBuilder,
				referenceSchema.getReferencedEntityType(),
				locale,
				facetSummaryOfReference.getFacetEntityRequirement().orElse(null)
			)
		);
	}
}
