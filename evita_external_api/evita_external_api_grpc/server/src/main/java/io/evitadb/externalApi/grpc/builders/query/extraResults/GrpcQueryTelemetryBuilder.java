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

import io.evitadb.api.requestResponse.extraResult.FormulaPlan;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.StepMetric;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.GrpcFormulaPlan;
import io.evitadb.externalApi.grpc.generated.GrpcQueryTelemetry;
import io.evitadb.externalApi.grpc.generated.GrpcQueryTelemetryMetrics;
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
			.setSpentTime(queryTelemetry.getSpentTime())
			.setSelfTime(selfTimeOf(queryTelemetry));
		// only the root step carries the wall-clock stamp that anchors the whole tree in time
		final OffsetDateTime startedAt = queryTelemetry.getStartedAt();
		if (startedAt != null) {
			builder.setStartedAt(EvitaDataTypesConverter.toGrpcOffsetDateTime(startedAt));
		}
		// left unset for a step the engine measured nothing on, which today is every step but the root
		if (queryTelemetry.hasMetrics()) {
			builder.setMetrics(buildMetrics(queryTelemetry));
		}
		// left unset unless the query asked for the plan, in which case only the phases owning a formula carry one
		final FormulaPlan plan = queryTelemetry.getPlan();
		if (plan != null) {
			builder.setPlan(buildPlan(plan));
		}
		return builder.build();
	}

	/**
	 * Converts a formula plan node and everything below it to its gRPC representation.
	 *
	 * The recursion mirrors the plan's own shape, and stops of its own accord at back-reference nodes because they
	 * carry no children - which is the point of them: a sub-formula reachable by two paths is described once and
	 * pointed at thereafter, so a reader does not count a shared computation twice.
	 *
	 * Every field that is `null` on the source node is left **unset** rather than defaulted. `actualCost` and
	 * `resultCount` are null precisely when the formula was never computed, and a rejected plan alternative never
	 * is; defaulting them to `0` would report an unexecuted formula as one that matched nothing.
	 *
	 * @param plan node to convert
	 * @return built {@link GrpcFormulaPlan}
	 */
	@Nonnull
	private static GrpcFormulaPlan buildPlan(@Nonnull FormulaPlan plan) {
		final GrpcFormulaPlan.Builder builder = GrpcFormulaPlan.newBuilder()
			.setId(plan.id())
			.setHash(plan.hash())
			.setEstimatedCost(plan.estimatedCost());
		if (plan.refTo() != null) {
			builder.setRefTo(plan.refTo());
		}
		if (plan.description() != null) {
			builder.setDescription(plan.description());
		}
		if (plan.actualCost() != null) {
			builder.setActualCost(plan.actualCost());
		}
		if (plan.resultCount() != null) {
			builder.setResultCount(plan.resultCount());
		}
		for (final FormulaPlan child : plan.children()) {
			builder.addChildren(buildPlan(child));
		}
		return builder.build();
	}

	/**
	 * Converts the metrics recorded on a single step to their gRPC representation.
	 *
	 * The engine stores metrics in a compact primitive array indexed by {@link StepMetric}; that is an internal
	 * representation, so each one is unpacked into a named field here. Clients generate their code from the schema
	 * and have to be able to introspect the values, which a positional array would not allow.
	 *
	 * A metric the engine did not measure is left **unset** rather than defaulted. That is what the `optional`
	 * modifier on these fields is for: `recordsReturned`, `ioFetchCount`, `ioFetchedSizeBytes` and `prefetched` can
	 * all legitimately be `0`, so proto3 implicit presence could not distinguish "measured zero" from "not measured".
	 *
	 * @param queryTelemetry step whose metrics are converted
	 * @return built {@link GrpcQueryTelemetryMetrics}
	 */
	@Nonnull
	private static GrpcQueryTelemetryMetrics buildMetrics(@Nonnull QueryTelemetry queryTelemetry) {
		final GrpcQueryTelemetryMetrics.Builder builder = GrpcQueryTelemetryMetrics.newBuilder();
		queryTelemetry.getMetric(StepMetric.ESTIMATED_CARDINALITY).ifPresent(builder::setEstimatedCardinality);
		queryTelemetry.getMetric(StepMetric.ACTUAL_CARDINALITY).ifPresent(builder::setActualCardinality);
		queryTelemetry.getMetric(StepMetric.ESTIMATED_COST).ifPresent(builder::setEstimatedCost);
		queryTelemetry.getMetric(StepMetric.ACTUAL_COST).ifPresent(builder::setActualCost);
		queryTelemetry.getMetric(StepMetric.RECORDS_RETURNED).ifPresent(builder::setRecordsReturned);
		queryTelemetry.getMetric(StepMetric.IO_FETCH_COUNT).ifPresent(builder::setIoFetchCount);
		queryTelemetry.getMetric(StepMetric.IO_FETCHED_SIZE_BYTES).ifPresent(builder::setIoFetchedSizeBytes);
		// flags are packed into the same numeric container in the engine and unpacked back to booleans here, so
		// that clients are not handed a `1` to decode themselves
		queryTelemetry.getMetric(StepMetric.PREFETCHED).ifPresent(it -> builder.setPrefetched(it != 0L));
		return builder.build();
	}

	/**
	 * Returns the time this step spent on its own work - its `spentTime` less the time accounted for by its direct
	 * children.
	 *
	 * The engine object does not carry this; it is derived here so that every remote client gets it without the engine
	 * having to track it. Steps nest on a stack and therefore never overlap, so the result cannot legitimately be
	 * negative - it is clamped at zero regardless, because a hand-assembled tree carries no such guarantee.
	 *
	 * @param queryTelemetry node whose self time is computed
	 */
	private static long selfTimeOf(@Nonnull QueryTelemetry queryTelemetry) {
		long childrenTime = 0;
		for (final QueryTelemetry step : queryTelemetry.getSteps()) {
			childrenTime += step.getSpentTime();
		}
		return Math.max(0, queryTelemetry.getSpentTime() - childrenTime);
	}
}
