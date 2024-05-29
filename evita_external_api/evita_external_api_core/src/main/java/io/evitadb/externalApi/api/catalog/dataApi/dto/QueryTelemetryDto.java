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
import java.util.Arrays;
import java.util.List;

/**
 * External API DTO for {@link QueryTelemetry}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public record QueryTelemetryDto(@Nonnull String operation,
								long start,
                                @Nonnull List<QueryTelemetryDto> steps,
                                @Nonnull List<String> arguments,
                                @Nonnull String spentTime) {

	public static QueryTelemetryDto from(@Nonnull QueryTelemetry queryTelemetry, boolean formatted) {
		return new QueryTelemetryDto(
			queryTelemetry.getOperation().toString(),
			queryTelemetry.getStart(),
			queryTelemetry.getSteps().stream().map(it -> QueryTelemetryDto.from(it, formatted)).toList(),
			Arrays.stream(queryTelemetry.getArguments()).map(Object::toString).toList(),
			formatted ? StringUtils.formatNano(queryTelemetry.getSpentTime()) : String.valueOf(queryTelemetry.getSpentTime())
		);
	}
}
