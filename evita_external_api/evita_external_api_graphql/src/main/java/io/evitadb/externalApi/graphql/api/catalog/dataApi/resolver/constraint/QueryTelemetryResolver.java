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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.constraint;

import graphql.schema.SelectedField;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ExtraResultsDescriptor;
import io.evitadb.externalApi.graphql.api.resolver.SelectionSetAggregator;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Optional;

import static io.evitadb.api.query.QueryConstraints.queryTelemetry;

/**
 * Custom constraint resolver which resolves additional constraints from output fields defined by client, rather
 * than using main query.
 * Resolves {@link io.evitadb.api.query.require.QueryTelemetry} based on which extra result fields client specified.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class QueryTelemetryResolver {

	@Nonnull
	public Optional<RequireConstraint> resolve(@Nonnull SelectionSetAggregator extraResultsSelectionSet) {
		final List<SelectedField> queryTelemetryFields = extraResultsSelectionSet.getImmediateFields(ExtraResultsDescriptor.QUERY_TELEMETRY.name());
		if (queryTelemetryFields.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(queryTelemetry());
	}
}
