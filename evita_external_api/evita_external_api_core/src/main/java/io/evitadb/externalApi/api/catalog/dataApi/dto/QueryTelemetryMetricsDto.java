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

package io.evitadb.externalApi.api.catalog.dataApi.dto;

import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.StepMetric;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.OptionalLong;

/**
 * External API DTO for the typed numeric measurements a {@link QueryTelemetry} step carries.
 *
 * The engine stores metrics in a compact primitive array indexed by {@link StepMetric}, which is an internal
 * representation and deliberately not what gets published: clients generate their code from these schemas, so the
 * values have to be introspectable named fields rather than positions in an array.
 *
 * They are published as a nested object rather than flattened onto {@link QueryTelemetryDto} because metrics are the
 * exception, not the rule - a telemetry tree of forty nodes carries them on exactly one, the root. A single nullable
 * object says "nothing was measured here" once, where flattening would repeat it as eight nulls on every node.
 *
 * **Every component is nullable and absence is meaningful.** A metric is recorded where the engine happens to compute
 * the number, so "not measured for this phase" is a normal outcome - and it is deliberately distinct from a measured
 * `0`, which several of these can legitimately be. A client must not default the two together.
 *
 * @param estimatedCardinality how many records the planner expected the filter to match
 * @param actualCardinality    how many records the filter really matched, before paging
 * @param estimatedCost        cost the planner estimated for the filtering formula it chose; absent when the estimate
 *                             overflowed
 * @param actualCost           cost the filtering formula really incurred; absent when the formula was never computed
 * @param recordsReturned      how many records were handed back, i.e. the size of the requested page
 * @param ioFetchCount         how many times the storage was read while assembling the response
 * @param ioFetchedSizeBytes   how many bytes were read from the storage while assembling the response
 * @param prefetched           whether the planner filtered over prefetched entity bodies instead of consulting indexes
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public record QueryTelemetryMetricsDto(@Nullable Long estimatedCardinality,
									   @Nullable Long actualCardinality,
									   @Nullable Long estimatedCost,
									   @Nullable Long actualCost,
									   @Nullable Long recordsReturned,
									   @Nullable Long ioFetchCount,
									   @Nullable Long ioFetchedSizeBytes,
									   @Nullable Boolean prefetched) {

	/**
	 * Converts the metrics recorded on a single telemetry step to their DTO form.
	 *
	 * Returns `null` for a step that carries no measurement at all, which is the common case - it keeps the payload
	 * from growing an all-null object on every node of the tree just because one node has numbers.
	 *
	 * @param queryTelemetry the step whose metrics are converted
	 * @return the converted metrics, or `null` when the step recorded none
	 */
	@Nullable
	public static QueryTelemetryMetricsDto from(@Nonnull QueryTelemetry queryTelemetry) {
		if (!queryTelemetry.hasMetrics()) {
			return null;
		}
		return new QueryTelemetryMetricsDto(
			numberOf(queryTelemetry, StepMetric.ESTIMATED_CARDINALITY),
			numberOf(queryTelemetry, StepMetric.ACTUAL_CARDINALITY),
			numberOf(queryTelemetry, StepMetric.ESTIMATED_COST),
			numberOf(queryTelemetry, StepMetric.ACTUAL_COST),
			numberOf(queryTelemetry, StepMetric.RECORDS_RETURNED),
			numberOf(queryTelemetry, StepMetric.IO_FETCH_COUNT),
			numberOf(queryTelemetry, StepMetric.IO_FETCHED_SIZE_BYTES),
			flagOf(queryTelemetry, StepMetric.PREFETCHED)
		);
	}

	/**
	 * Reads a numeric metric, mapping "not measured" to `null` rather than to a value.
	 *
	 * @param queryTelemetry the step to read from
	 * @param metric         the measurement to read
	 * @return the recorded value, or `null` when the step carries no measurement for that metric
	 */
	@Nullable
	private static Long numberOf(@Nonnull QueryTelemetry queryTelemetry, @Nonnull StepMetric metric) {
		final OptionalLong value = queryTelemetry.getMetric(metric);
		return value.isPresent() ? value.getAsLong() : null;
	}

	/**
	 * Reads a flag-shaped metric, unpacking the `1`/`0` the engine stores back into a boolean.
	 *
	 * The engine packs flags into the same primitive array as the counts, because a second array for the handful of
	 * booleans would cost more than the packing does. That is an internal representation, so it is unpacked here -
	 * publishing a flag as `1` would push the same decoding onto every client.
	 *
	 * @param queryTelemetry the step to read from
	 * @param metric         the flag to read
	 * @return the recorded flag, or `null` when the step carries no measurement for it
	 */
	@Nullable
	private static Boolean flagOf(@Nonnull QueryTelemetry queryTelemetry, @Nonnull StepMetric metric) {
		final OptionalLong value = queryTelemetry.getMetric(metric);
		return value.isPresent() ? value.getAsLong() != 0L : null;
	}
}
