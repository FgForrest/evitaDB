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
import io.evitadb.api.query.require.QueryTelemetry;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ExtraResultsDescriptor;

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

		extraResultsBuilder.addPrimitiveField(ExtraResultsDescriptor.QUERY_TELEMETRY);
	}
}
