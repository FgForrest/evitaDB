/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
import io.evitadb.api.query.require.HistogramBehavior;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HistogramDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HistogramDescriptor.BucketDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.ResponseHeaderDescriptor.BucketsFieldHeaderDescriptor;
import io.evitadb.test.client.query.graphql.GraphQLOutputFieldsBuilder.Argument;
import io.evitadb.test.client.query.graphql.GraphQLOutputFieldsBuilder.ArgumentSupplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Ancestor of all histogram converters.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public abstract class HistogramConverter extends RequireConverter {

	protected HistogramConverter(@Nonnull CatalogSchemaContract catalogSchema,
	                             @Nonnull Query query) {
		super(catalogSchema, query);
	}

	@Nonnull
	protected static Consumer<GraphQLOutputFieldsBuilder> getHistogramFieldsBuilder(@Nonnull HistogramRequest histogramRequest) {
		final List<ArgumentSupplier> argumentSuppliers = new ArrayList<>(2);
		argumentSuppliers.add((offset, multipleArguments) -> new Argument(BucketsFieldHeaderDescriptor.REQUESTED_COUNT, offset, multipleArguments, histogramRequest.requestedBucketCount()));
		if (histogramRequest.behavior() != null && histogramRequest.behavior() != HistogramBehavior.STANDARD) {
			argumentSuppliers.add((offset, multipleArguments) -> new Argument(BucketsFieldHeaderDescriptor.BEHAVIOR, offset, multipleArguments, histogramRequest.behavior()));
		}

		return builder -> builder
			.addPrimitiveField(HistogramDescriptor.MIN)
			.addPrimitiveField(HistogramDescriptor.MAX)
			.addPrimitiveField(HistogramDescriptor.OVERALL_COUNT)
			.addObjectField(
				HistogramDescriptor.BUCKETS,
				bucketsBuilder -> bucketsBuilder
					.addPrimitiveField(BucketDescriptor.THRESHOLD)
					.addPrimitiveField(BucketDescriptor.OCCURRENCES)
					.addPrimitiveField(BucketDescriptor.REQUESTED),
				argumentSuppliers.toArray(ArgumentSupplier[]::new)
			);
	}

	protected record HistogramRequest(int requestedBucketCount, @Nullable HistogramBehavior behavior) {
	}
}
