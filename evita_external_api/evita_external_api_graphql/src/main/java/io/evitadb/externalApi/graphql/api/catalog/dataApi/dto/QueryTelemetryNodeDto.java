/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.dto;

import io.evitadb.externalApi.api.catalog.dataApi.dto.QueryTelemetryDto;
import io.evitadb.externalApi.api.catalog.dataApi.dto.QueryTelemetryMetricsDto;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * GraphQL DTO for one node of a {@link io.evitadb.api.requestResponse.extraResult.QueryTelemetry} tree, which this
 * API returns flattened into a pre-order list - see
 * {@link io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult.QueryTelemetryNodeDescriptor} for why.
 *
 * It is a plain record so that graphql-java resolves every field with its default property data fetcher; no field
 * needs one of its own. The values are taken verbatim from {@link QueryTelemetryDto}, which is where the `start`
 * normalization, the derived self time and the metric reshaping all happen - this record only restates them without
 * the nesting, and adds the two properties that replace it.
 *
 * @param level              depth of this node with the root at `1`, the property that carries the structure the
 *                           flattening removed
 * @param operation          {@link io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase} this step
 *                           measured, by its enum name
 * @param start              nanoseconds elapsed between the start of the root step and the start of this one
 * @param arguments          human readable details of the phase
 * @param spentTime          duration of this step in nanoseconds, covering the step and everything nested below it
 * @param formattedSpentTime `spentTime` rendered for humans (e.g. `16.6 ms`)
 * @param selfTime           duration in nanoseconds this step spent on its own work
 * @param formattedSelfTime  `selfTime` rendered for humans
 * @param stepsCount         number of direct sub-steps, so a leaf is recognizable without looking ahead in the list
 * @param metrics            typed numeric measurements recorded for this step, or `null` when it carries none
 * @param plan               the formula plan for this step, itself flattened into a pre-order list, or `null` when
 *                           the query did not ask for a plan or this step owns no formula
 * @param startedAt          wall-clock instant the query began, carried by the root step only
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public record QueryTelemetryNodeDto(
	int level,
	@Nonnull String operation,
	long start,
	@Nonnull List<String> arguments,
	long spentTime,
	@Nonnull String formattedSpentTime,
	long selfTime,
	@Nonnull String formattedSelfTime,
	int stepsCount,
	@Nullable QueryTelemetryMetricsDto metrics,
	@Nullable List<FormulaPlanNodeDto> plan,
	@Nullable String startedAt
) {

	/**
	 * Flattens a converted telemetry tree into the pre-order list this API publishes.
	 *
	 * @param root **root** of the already converted telemetry tree
	 * @return every node of the tree, parents before their children, each stamped with its depth
	 */
	@Nonnull
	public static List<QueryTelemetryNodeDto> flatten(@Nonnull QueryTelemetryDto root) {
		final List<QueryTelemetryNodeDto> flattened = new ArrayList<>();
		flatten(flattened, root, 1);
		return flattened;
	}

	/**
	 * Appends a single node and then everything below it, depth first, so that the resulting list is in pre-order -
	 * which is what makes "the parent is the closest preceding node with a lower level" hold.
	 *
	 * @param flattened list being accumulated into
	 * @param node      node to append
	 * @param level     depth of `node`, with the root of the whole tree at `1`
	 */
	private static void flatten(@Nonnull List<QueryTelemetryNodeDto> flattened,
	                            @Nonnull QueryTelemetryDto node,
	                            int level) {
		flattened.add(
			new QueryTelemetryNodeDto(
				level,
				node.operation(),
				node.start(),
				node.arguments(),
				node.spentTime(),
				node.formattedSpentTime(),
				node.selfTime(),
				node.formattedSelfTime(),
				node.steps().size(),
				node.metrics(),
				FormulaPlanNodeDto.flatten(node.plan()),
				node.startedAt()
			)
		);
		for (final QueryTelemetryDto step : node.steps()) {
			flatten(flattened, step, level + 1);
		}
	}
}
