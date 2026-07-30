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

package io.evitadb.externalApi.api.catalog.dataApi.dto;

import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.utils.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/**
 * External API DTO for {@link QueryTelemetry}.
 *
 * Unlike {@link QueryTelemetry#getStart()}, which carries the raw server side {@link System#nanoTime()} reading,
 * the `start` of this DTO is normalized to the number of nanoseconds elapsed since the root step began - the root
 * therefore always reports `0`. The raw reading has no defined epoch and is taken on the server, which makes it
 * meaningless to a remote client; only the offset is.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public record QueryTelemetryDto(@Nonnull String operation,
								long start,
                                @Nonnull List<QueryTelemetryDto> steps,
                                @Nonnull List<String> arguments,
                                long spentTime,
                                @Nonnull String formattedSpentTime,
                                @Nullable String startedAt) {

	/**
	 * Converts the passed telemetry tree to its DTO form.
	 *
	 * @param queryTelemetry **root** of the telemetry tree - the start of this very node becomes the zero point
	 *                       every other node in the tree is expressed against
	 */
	@Nonnull
	public static QueryTelemetryDto from(@Nonnull QueryTelemetry queryTelemetry) {
		return from(queryTelemetry, queryTelemetry.getStart());
	}

	/**
	 * Recursively converts a single node of the telemetry tree, normalizing its start against the root step.
	 *
	 * @param queryTelemetry node to be converted
	 * @param rootStart      raw `nanoTime` reading of the root step of the entire tree
	 */
	@Nonnull
	private static QueryTelemetryDto from(@Nonnull QueryTelemetry queryTelemetry, long rootStart) {
		return new QueryTelemetryDto(
			queryTelemetry.getOperation().toString(),
			queryTelemetry.getStart() - rootStart,
			queryTelemetry.getSteps().stream().map(it -> from(it, rootStart)).toList(),
			Arrays.stream(queryTelemetry.getArguments()).map(Object::toString).toList(),
			queryTelemetry.getSpentTime(),
			StringUtils.formatNano(queryTelemetry.getSpentTime()),
			// only the root step carries the wall-clock stamp that anchors the whole tree in time
			queryTelemetry.getStartedAt() == null ?
				null : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(queryTelemetry.getStartedAt())
		);
	}
}
