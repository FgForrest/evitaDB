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
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.GrpcQueryTelemetry;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
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
	 * The raw {@link System#nanoTime()} reading held in {@link QueryTelemetry#getStart()} is taken on the server and
	 * has no defined epoch, which makes it meaningless to a remote client. It is therefore normalized here to the
	 * number of nanoseconds elapsed since the root step began - the root always reports `0`.
	 *
	 * @param queryTelemetry **root** of the telemetry tree to be converted
	 * @return built {@link GrpcQueryTelemetry}
	 */
	@Nonnull
	public static GrpcQueryTelemetry buildQueryTelemetry(@Nonnull QueryTelemetry queryTelemetry) {
		// the root step is the zero point every other node in the tree is expressed against
		final long rootStart = queryTelemetry.getStart();
		final List<GrpcQueryTelemetry> queryTelemetrySteps = new ArrayList<>();

		for (QueryTelemetry step : queryTelemetry.getSteps()) {
			queryTelemetrySteps.addAll(buildQueryTelemetrySteps(step, rootStart));
		}

		return buildSingleGrpcQueryTelemetry(
			queryTelemetry, queryTelemetrySteps, rootStart
		);
	}

	/**
	 * Recursive called method for building {@link GrpcQueryTelemetry} with all of its steps.
	 *
	 * @param queryTelemetry of which steps should be converted
	 * @param rootStart      raw `nanoTime` reading of the root step of the entire tree
	 */
	@Nonnull
	private static List<GrpcQueryTelemetry> buildQueryTelemetrySteps(@Nonnull QueryTelemetry queryTelemetry, long rootStart) {
		final List<GrpcQueryTelemetry> children = new LinkedList<>();
		final List<GrpcQueryTelemetry> steps = new LinkedList<>();
		if (!queryTelemetry.getSteps().isEmpty()) {
			for (QueryTelemetry step : queryTelemetry.getSteps()) {
				children.addAll(buildQueryTelemetrySteps(step, rootStart));
			}
		}

		steps.add(buildSingleGrpcQueryTelemetry(queryTelemetry, children, rootStart));

		return steps;
	}

	/**
	 * Method for creating {@link GrpcQueryTelemetry} from {@link QueryTelemetry}.
	 *
	 * @param queryTelemetry to be converted
	 * @param steps          of the query telemetry which were computed in {@link #buildQueryTelemetrySteps(QueryTelemetry, long)}
	 * @param rootStart      raw `nanoTime` reading of the root step of the entire tree
	 * @return built {@link GrpcQueryTelemetry}
	 */
	@Nonnull
	private static GrpcQueryTelemetry buildSingleGrpcQueryTelemetry(@Nonnull QueryTelemetry queryTelemetry, @Nonnull List<GrpcQueryTelemetry> steps, long rootStart) {
		final GrpcQueryTelemetry.Builder builder = GrpcQueryTelemetry.newBuilder()
			.setOperation(EvitaEnumConverter.toGrpcQueryPhase(queryTelemetry.getOperation()))
			.setStart(queryTelemetry.getStart() - rootStart)
			.addAllSteps(steps)
			.addAllArguments(Arrays.stream(queryTelemetry.getArguments()).map(Objects::toString).toList())
			.setSpentTime(queryTelemetry.getSpentTime());
		// only the root step carries the wall-clock stamp that anchors the whole tree in time
		final OffsetDateTime startedAt = queryTelemetry.getStartedAt();
		if (startedAt != null) {
			builder.setStartedAt(EvitaDataTypesConverter.toGrpcOffsetDateTime(startedAt));
		}
		return builder.build();
	}
}
