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

package io.evitadb.test.client.query.graphql;

import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.AttributeHistogram;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.externalApi.api.ExternalApiNamingConventions;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ExtraResultsDescriptor;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Converts {@link AttributeHistogram}s into GraphQL output fields.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class AttributeHistogramConverter extends HistogramConverter {

	public AttributeHistogramConverter(@Nonnull CatalogSchemaContract catalogSchema,
	                                   @Nonnull Query query) {
		super(catalogSchema, query);
	}

	public void convert(@Nonnull GraphQLOutputFieldsBuilder extraResultsBuilder,
						@Nonnull String entityType,
	                    @Nonnull List<AttributeHistogram> attributeHistograms) {
		if (attributeHistograms.isEmpty()) {
			return;
		}

		final EntitySchemaContract entitySchema = this.catalogSchema.getEntitySchemaOrThrowException(entityType);

		extraResultsBuilder.addObjectField(
			ExtraResultsDescriptor.ATTRIBUTE_HISTOGRAM,
			attributeHistogramsBuilder -> {
				attributeHistograms.stream()
					.flatMap(attributeHistogram -> Arrays.stream(attributeHistogram.getAttributeNames())
						.map(attributeName -> Map.entry(attributeName, new HistogramRequest(attributeHistogram.getRequestedBucketCount(), attributeHistogram.getBehavior()))))
					.forEach(histogramRequest -> attributeHistogramsBuilder.addObjectField(
						entitySchema.getAttribute(histogramRequest.getKey())
							.orElseThrow()
							.getNameVariant(ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION),
						getHistogramFieldsBuilder(histogramRequest.getValue())
					));
			}
		);
	}
}
