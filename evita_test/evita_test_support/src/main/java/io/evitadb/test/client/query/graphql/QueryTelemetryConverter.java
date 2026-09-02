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
import io.evitadb.api.query.require.QueryTelemetry;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ExtraResultsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FormulaPlanDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.QueryTelemetryDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.QueryTelemetryMetricsDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.FormulaPlanNodeDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.QueryTelemetryNodeDescriptor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Converts {@link QueryTelemetry} into GraphQL output fields.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class QueryTelemetryConverter extends RequireConverter {

	public QueryTelemetryConverter(@Nonnull CatalogSchemaContract catalogSchema,
	                               @Nonnull Query query) {
		super(catalogSchema, query);
	}

	public void convert(@Nonnull GraphQLOutputFieldsBuilder extraResultsBuilder,
	                    @Nullable QueryTelemetry queryTelemetry) {
		if (queryTelemetry == null) {
			return;
		}

		// selecting `plan` is how this API opts into the formula plan - there is no field argument - so a
		// constraint that asked for it must be translated into the selection set, or the generated query would
		// silently come back without the plan the EvitaQL and REST tabs of the same sample do show
		final boolean planRequested = queryTelemetry.isPlanRequested();

		// the telemetry field is a typed object rather than an opaque scalar, so it needs an explicit selection
		// set; the tree arrives flattened, which is why `level` and `stepsCount` are selected in place of `steps`
		extraResultsBuilder.addObjectField(
			ExtraResultsDescriptor.QUERY_TELEMETRY,
			telemetryBuilder -> {
				telemetryBuilder
					.addPrimitiveField(QueryTelemetryNodeDescriptor.LEVEL)
					.addPrimitiveField(QueryTelemetryDescriptor.OPERATION)
					.addPrimitiveField(QueryTelemetryDescriptor.START)
					.addPrimitiveField(QueryTelemetryDescriptor.ARGUMENTS)
					.addPrimitiveField(QueryTelemetryDescriptor.SPENT_TIME)
					.addPrimitiveField(QueryTelemetryDescriptor.FORMATTED_SPENT_TIME)
					.addPrimitiveField(QueryTelemetryDescriptor.SELF_TIME)
					.addPrimitiveField(QueryTelemetryDescriptor.FORMATTED_SELF_TIME)
					.addPrimitiveField(QueryTelemetryNodeDescriptor.STEPS_COUNT)
					.addPrimitiveField(QueryTelemetryDescriptor.STARTED_AT)
					.addObjectField(
						QueryTelemetryDescriptor.METRICS,
						metricsBuilder -> metricsBuilder
							.addPrimitiveField(QueryTelemetryMetricsDescriptor.ESTIMATED_CARDINALITY)
							.addPrimitiveField(QueryTelemetryMetricsDescriptor.ACTUAL_CARDINALITY)
							.addPrimitiveField(QueryTelemetryMetricsDescriptor.ESTIMATED_COST)
							.addPrimitiveField(QueryTelemetryMetricsDescriptor.ACTUAL_COST)
							.addPrimitiveField(QueryTelemetryMetricsDescriptor.RECORDS_RETURNED)
							.addPrimitiveField(QueryTelemetryMetricsDescriptor.IO_FETCH_COUNT)
							.addPrimitiveField(QueryTelemetryMetricsDescriptor.IO_FETCHED_SIZE_BYTES)
							.addPrimitiveField(QueryTelemetryMetricsDescriptor.PREFETCHED)
					);
				if (planRequested) {
					telemetryBuilder.addObjectField(
						QueryTelemetryNodeDescriptor.PLAN,
						planBuilder -> planBuilder
							.addPrimitiveField(FormulaPlanNodeDescriptor.LEVEL)
							.addPrimitiveField(FormulaPlanDescriptor.ID)
							.addPrimitiveField(FormulaPlanDescriptor.REF_TO)
							.addPrimitiveField(FormulaPlanDescriptor.HASH)
							.addPrimitiveField(FormulaPlanDescriptor.DESCRIPTION)
							.addPrimitiveField(FormulaPlanDescriptor.ESTIMATED_COST)
							.addPrimitiveField(FormulaPlanDescriptor.ACTUAL_COST)
							.addPrimitiveField(FormulaPlanDescriptor.RESULT_COUNT)
							.addPrimitiveField(FormulaPlanNodeDescriptor.CHILDREN_COUNT)
					);
				}
			}
		);
	}
}
