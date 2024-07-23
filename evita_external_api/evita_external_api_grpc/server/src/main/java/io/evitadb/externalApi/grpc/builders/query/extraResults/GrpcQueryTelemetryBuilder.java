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

package io.evitadb.externalApi.grpc.builders.query.extraResults;

import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.externalApi.grpc.generated.GrpcQueryTelemetry;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

/**
 * This class builds is used for building gRPC representation in gRPC message types of {@link QueryTelemetry}.
 *
 * @author Tomáš Pozler, 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class GrpcQueryTelemetryBuilder {
	/**
	 * Converts {@link QueryTelemetry} to {@link GrpcQueryTelemetry}.
	 *
	 * @param queryTelemetry {@link QueryTelemetry} to be converted
	 * @return built {@link GrpcQueryTelemetry}
	 */
	@Nonnull
	public static GrpcQueryTelemetry buildQueryTelemetry(@Nonnull QueryTelemetry queryTelemetry) {
		final List<GrpcQueryTelemetry> queryTelemetrySteps = new ArrayList<>();

		for (QueryTelemetry step : queryTelemetry.getSteps()) {
			queryTelemetrySteps.addAll(buildQueryTelemetrySteps(step));
		}

		return buildSingleGrpcQueryTelemetry(
			queryTelemetry, queryTelemetrySteps
		);
	}

	/**
	 * Recursive called method for building {@link GrpcQueryTelemetry} with all of its steps.
	 *
	 * @param queryTelemetry of which steps should be converted
	 */
	@Nonnull
	private static List<GrpcQueryTelemetry> buildQueryTelemetrySteps(@Nonnull QueryTelemetry queryTelemetry) {
		final List<GrpcQueryTelemetry> children = new LinkedList<>();
		final List<GrpcQueryTelemetry> steps = new LinkedList<>();
		if (!queryTelemetry.getSteps().isEmpty()) {
			for (QueryTelemetry step : queryTelemetry.getSteps()) {
				children.addAll(buildQueryTelemetrySteps(step));
			}
		}

		steps.add(buildSingleGrpcQueryTelemetry(queryTelemetry, children));

		return steps;
	}

	/**
	 * Method for creating {@link GrpcQueryTelemetry} from {@link QueryTelemetry}.
	 *
	 * @param queryTelemetry to be converted
	 * @param steps          of the query telemetry which were computed in {@link #buildQueryTelemetrySteps(QueryTelemetry)}
	 * @return built {@link GrpcQueryTelemetry}
	 */
	@Nonnull
	private static GrpcQueryTelemetry buildSingleGrpcQueryTelemetry(@Nonnull QueryTelemetry queryTelemetry, @Nonnull List<GrpcQueryTelemetry> steps) {
		return GrpcQueryTelemetry.newBuilder()
			.setOperation(EvitaEnumConverter.toGrpcQueryPhase(queryTelemetry.getOperation()))
			.setStart(queryTelemetry.getStart())
			.addAllSteps(steps)
			.addAllArguments(Arrays.stream(queryTelemetry.getArguments()).map(Objects::toString).toList())
			.setSpentTime(queryTelemetry.getSpentTime())
			.build();
	}
}
