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

package io.evitadb.externalApi.api.catalog.dataApi.model.extraResult;

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Represents the typed numeric measurements carried by
 * {@link io.evitadb.api.requestResponse.extraResult.QueryTelemetry.StepMetric}.
 *
 * Note: this descriptor has static structure.
 *
 * These are the numbers that answer *why* a phase took the time it did, where the timings on
 * {@link QueryTelemetryDescriptor} only answer *where* the time went. The pair that matters most is
 * {@link #ESTIMATED_CARDINALITY} against {@link #ACTUAL_CARDINALITY} - a planner estimate that is orders of magnitude
 * off is how a bad plan is recognised, and no amount of timing data reveals it.
 *
 * **Every property is nullable and absence is meaningful.** A metric is recorded where the engine happens to compute
 * the number, so a missing one means "not measured for this phase" - which is deliberately different from a measured
 * `0`, a legitimate value for several of these. Clients must not collapse the two.
 *
 * As with {@link QueryTelemetryDescriptor}, property names and descriptions here are published schema rather than
 * internal documentation: they are what clients generate their code from. The shape is mirrored by
 * {@link io.evitadb.externalApi.api.catalog.dataApi.dto.QueryTelemetryMetricsDto}, which is what actually gets
 * serialized.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface QueryTelemetryMetricsDescriptor {

	/**
	 * What the planner *expected* the filter to match. Half of the estimate-versus-actual pair; on its own it says
	 * only what the planner believed, which is interesting precisely when it turns out to be wrong.
	 */
	PropertyDescriptor ESTIMATED_CARDINALITY = PropertyDescriptor.builder()
		.name("estimatedCardinality")
		.description("""
			How many records the planner expected the filtering formula to match. Compare it against
			`actualCardinality` - an estimate that is orders of magnitude off is why the engine chose the
			index it chose, and it is the usual explanation for a plan that looks wrong.
			""")
		.type(nullable(Long.class))
		.build();
	/**
	 * What the filter *really* matched, before paging. The other half of the estimate-versus-actual pair, and the
	 * number a client most often wants when it is deciding whether a query is slow because it is doing too much.
	 */
	PropertyDescriptor ACTUAL_CARDINALITY = PropertyDescriptor.builder()
		.name("actualCardinality")
		.description("""
			How many records the filtering formula really matched, before the requested page was cut out of
			them. This counts what the filter found, not what was returned - `recordsReturned` is the latter.
			""")
		.type(nullable(Long.class))
		.build();
	/**
	 * Cost the planner estimated for the formula it chose. Unitless and only comparable within one query - it is the
	 * scale the planner ranks candidate indexes on, not a duration.
	 */
	PropertyDescriptor ESTIMATED_COST = PropertyDescriptor.builder()
		.name("estimatedCost")
		.description("""
			Cost the planner estimated for the filtering formula it chose. This is the unitless scale candidate
			indexes are ranked on - comparable between plans of the same query, meaningless in absolute terms.
			Absent when the estimate overflowed.
			""")
		.type(nullable(Long.class))
		.build();
	/**
	 * Cost the formula really incurred once it ran. Absent rather than sentinel-valued when the formula was never
	 * computed, which is what keeps a nine-quintillion number off client dashboards.
	 */
	PropertyDescriptor ACTUAL_COST = PropertyDescriptor.builder()
		.name("actualCost")
		.description("""
			Cost the filtering formula really incurred, computed from the real cardinalities once it ran.
			Compare against `estimatedCost` on the same scale. Absent when the formula was never computed.
			""")
		.type(nullable(Long.class))
		.build();
	/**
	 * Size of the page actually handed back. Legitimately `0`, which is why it must not be confused with the metric
	 * being absent.
	 */
	PropertyDescriptor RECORDS_RETURNED = PropertyDescriptor.builder()
		.name("recordsReturned")
		.description("""
			How many records were actually handed back, i.e. the size of the page cut out of
			`actualCardinality`. Legitimately `0` for a query whose requested page lies past the end of the
			result.
			""")
		.type(nullable(Long.class))
		.build();
	/**
	 * How many storage reads the response cost. Reported next to {@link #IO_FETCHED_SIZE_BYTES} because count and
	 * volume answer different questions.
	 */
	PropertyDescriptor IO_FETCH_COUNT = PropertyDescriptor.builder()
		.name("ioFetchCount")
		.description("""
			How many times the storage was read while assembling the response. Legitimately `0` - a query
			answered entirely from indexes, or one returning bare primary keys, never touches storage.
			""")
		.type(nullable(Long.class))
		.build();
	/**
	 * How many bytes the storage reads moved. The volume half of the I/O picture; many small reads and one large
	 * read cost very differently and only the two numbers together tell them apart.
	 */
	PropertyDescriptor IO_FETCHED_SIZE_BYTES = PropertyDescriptor.builder()
		.name("ioFetchedSizeBytes")
		.description("""
			How many bytes were read from the storage while assembling the response. Reported alongside
			`ioFetchCount` because many small reads and one large read cost very differently.
			""")
		.type(nullable(Long.class))
		.build();
	/**
	 * Whether the planner filtered over prefetched bodies. It explains the shape of the rest of the profile rather
	 * than measuring anything, which is why it is a flag and not a count.
	 */
	PropertyDescriptor PREFETCHED = PropertyDescriptor.builder()
		.name("prefetched")
		.description("""
			Whether the planner prefetched entity bodies and filtered over them instead of consulting indexes.
			It explains the shape of the rest of the profile rather than measuring anything: a prefetched query
			spends its time in `EXECUTION_PREFETCH` and barely touches the index phases.
			""")
		.type(nullable(Boolean.class))
		.build();

	/**
	 * The `QueryTelemetryMetrics` object itself, assembled from the properties above. Like
	 * {@link QueryTelemetryDescriptor#THIS} its structure is static - the metric vocabulary is a closed enum in the
	 * engine and does not vary by catalog or entity type - so a single shared instance suffices.
	 */
	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("QueryTelemetryMetrics")
		.description("""
			Typed numeric measurements recorded for a query telemetry step. Every property is optional: a metric is
			recorded where the engine happens to compute it, so a missing value means it was not measured for this
			phase - which is not the same as it having been measured as zero.
			""")
		.staticProperties(
			List.of(
				ESTIMATED_CARDINALITY, ACTUAL_CARDINALITY,
				ESTIMATED_COST, ACTUAL_COST,
				RECORDS_RETURNED,
				IO_FETCH_COUNT, IO_FETCHED_SIZE_BYTES,
				PREFETCHED
			)
		)
		.build();
}
