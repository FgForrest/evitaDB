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

import graphql.schema.SelectedField;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ExtraResultsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HistogramDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.extraResult.AttributeHistogramDataFetcher;
import io.evitadb.externalApi.graphql.api.resolver.SelectionSetWrapper;
import io.evitadb.externalApi.graphql.exception.GraphQLInvalidResponseUsageException;
import io.evitadb.externalApi.graphql.exception.GraphQLQueryResolvingInternalError;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;

import static io.evitadb.api.query.QueryConstraints.attributeHistogram;
import static io.evitadb.externalApi.api.ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION;
import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Custom constraint resolver which resolves additional constraints from output fields defined by client, rather
 * than using main query.
 * Resolves {@link io.evitadb.api.query.require.AttributeHistogram}s based on which extra result fields client specified.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class AttributeHistogramResolver {

	@Nonnull private final EntitySchemaContract entitySchema;

	@Nonnull
	public List<RequireConstraint> resolve(@Nonnull SelectionSetWrapper extraResultsSelectionSet) {
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
					.getAttributeByName(f.getName(), PROPERTY_NAME_NAMING_CONVENTION)
					.orElseThrow(() -> new GraphQLQueryResolvingInternalError("Missing attribute `" + f.getName() + "`."));
				final String originalAttributeName = attributeSchema.getName();

				final List<SelectedField> bucketsFields = SelectionSetWrapper.from(f.getSelectionSet()).getFields(HistogramDescriptor.BUCKETS.name());
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
							.getAttributeByName(a, PROPERTY_NAME_NAMING_CONVENTION)
							.orElseThrow(() -> new GraphQLQueryResolvingInternalError("Missing attribute `" + a + "`."));
						return attributeSchema.getName();
					})
					.toList();

				final List<SelectedField> bucketsFields = SelectionSetWrapper.from(f.getSelectionSet()).getFields(HistogramDescriptor.BUCKETS.name());
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
}
