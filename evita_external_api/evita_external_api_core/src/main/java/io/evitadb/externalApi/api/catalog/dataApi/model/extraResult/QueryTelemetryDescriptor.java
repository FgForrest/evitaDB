/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.nonNullListRef;
import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.nullableRef;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Represents {@link io.evitadb.api.requestResponse.extraResult.QueryTelemetry}.
 *
 * Note: this descriptor has static structure.
 *
 * The constants below are the single definition of the `QueryTelemetry` object shared by all external APIs - the
 * GraphQL and REST schema builders derive their types from it, and the shape here is mirrored by
 * {@link io.evitadb.externalApi.api.catalog.dataApi.dto.QueryTelemetryDto}, which is what actually gets serialized.
 * Property names and descriptions are therefore published schema, not internal documentation: changing one changes
 * the API contract clients generate their code from. The properties are not a one-to-one copy of the engine object -
 * `start` is normalized and `selfTime` / the formatted durations are derived at this boundary; see the DTO for why.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface QueryTelemetryDescriptor {

	/**
	 * Query phase this step measured, by the name of the
	 * {@link io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase} constant. It is exposed as an
	 * enum type, so adding a phase to the engine widens this property for every API at once.
	 */
	PropertyDescriptor OPERATION = PropertyDescriptor.builder()
		.name("operation")
		.description("""
			Phase of the query processing.
			""")
		.type(nonNull(QueryPhase.class))
		.build();
	/**
	 * Start of this step, normalized to an offset from the root step. The engine records a raw
	 * {@link System#nanoTime()} reading, which has no epoch and is taken on the server - meaningless to a remote
	 * client - so only the offset is published here.
	 */
	PropertyDescriptor START = PropertyDescriptor.builder()
		.name("start")
		.description("""
			Number of nanoseconds elapsed since the root step of this telemetry tree began - the root
			step itself therefore always reports `0`. This is not a wall-clock timestamp and must not
			be rendered as a date.
			""")
		.type(nonNull(Long.class))
		.build();
	/**
	 * Child steps this phase decomposed into - the property that makes the object recursive, referencing
	 * {@link #THIS} lazily because a descriptor cannot refer to itself while it is still being built. The list is
	 * non-null but legitimately empty, both for leaf phases and for a root whose planning short-circuited.
	 */
	PropertyDescriptor STEPS = PropertyDescriptor.builder()
		.name("steps")
		.description("""
			Internal steps of this telemetry step (operation decomposition).
			""")
		.type(nonNullListRef(() -> QueryTelemetryDescriptor.THIS))
		.build();
	/**
	 * Human readable details of the phase - for example the index that was selected and its estimated cost. The list
	 * is non-null but frequently empty: a step is described either at push time or at pop time, and plenty of phases
	 * need no description at all.
	 */
	PropertyDescriptor ARGUMENTS = PropertyDescriptor.builder()
		.name("arguments")
		.description("""
			Arguments of the processing phase.
			""")
		.type(nonNull(String[].class))
		.build();
	/**
	 * Total duration of the step as the engine measured it. Inclusive of everything nested below it, which is what
	 * makes it unusable on its own for spotting where the time went - {@link #SELF_TIME} is that number.
	 */
	PropertyDescriptor SPENT_TIME = PropertyDescriptor.builder()
		.name("spentTime")
		.description("""
			Duration in nanoseconds, covering this step and everything nested below it.
			""")
		.type(nonNull(Long.class))
		.build();
	/**
	 * {@link #SPENT_TIME} pre-rendered on the server, so that every client shows the same units and rounding instead
	 * of each reinventing nanosecond formatting. The raw value stays available for clients that do their own math.
	 */
	PropertyDescriptor FORMATTED_SPENT_TIME = PropertyDescriptor.builder()
		.name("formattedSpentTime")
		.description("""
			`spentTime` rendered in a human readable form (e.g. `16.6 ms`).
			""")
		.type(nonNull(String.class))
		.build();
	/**
	 * Time the step spent on its own work, derived at this boundary rather than measured by the engine. It exists
	 * because the children do not tile the parent, so a client that subtracts them itself is doing the one
	 * computation everybody needs and some get wrong.
	 */
	PropertyDescriptor SELF_TIME = PropertyDescriptor.builder()
		.name("selfTime")
		.description("""
			Duration in nanoseconds this step spent on its own work - its `spentTime` less the time accounted
			for by its direct children. A parent's `spentTime` is not the sum of its children's, so this is the
			number that says how much of a phase is the phase itself rather than the phases inside it.
			""")
		.type(nonNull(Long.class))
		.build();
	/**
	 * {@link #SELF_TIME} pre-rendered on the server, the counterpart of {@link #FORMATTED_SPENT_TIME} and formatted
	 * identically, so the two can be shown side by side without a client normalizing them.
	 */
	PropertyDescriptor FORMATTED_SELF_TIME = PropertyDescriptor.builder()
		.name("formattedSelfTime")
		.description("""
			`selfTime` rendered in a human readable form (e.g. `5.6 ms`).
			""")
		.type(nonNull(String.class))
		.build();
	/**
	 * Typed numeric measurements for this step, published as a nested object rather than flattened into this one.
	 *
	 * Flattening would repeat eight null properties on every node of the tree to serve the one node that has
	 * numbers - today only the root does. A single nullable object states that once, and gives the metric vocabulary
	 * a schema of its own to grow in, which is what {@link QueryTelemetryMetricsDescriptor} is.
	 */
	PropertyDescriptor METRICS = PropertyDescriptor.builder()
		.name("metrics")
		.description("""
			Typed numeric measurements recorded for this step - cardinalities, costs and I/O counters the engine
			computed while answering the query. Unlike `arguments`, which is prose, these are values a client can
			compare and chart without parsing English. Null when nothing was measured for this step, which is the
			case for every step but the root.
			""")
		.type(nullableRef(QueryTelemetryMetricsDescriptor.THIS))
		.build();

	/**
	 * Structure of the formula this phase built or ran, published only for a query that asked for it with
	 * `queryTelemetry(PLAN)`. It is nullable twice over: null on every phase of a query that did not ask, and null
	 * on the phases of one that did but that own no formula.
	 */
	PropertyDescriptor PLAN = PropertyDescriptor.builder()
		.name("plan")
		.description("""
			Structure of the formula the query engine built for this phase - what it was computing, as opposed to
			how long that took. Present only when the query asked for it with `queryTelemetry(PLAN)`, and then only
			on the phases that own a formula: each index-selection alternative carries the candidate it costed
			(including the ones that lost), and the root carries the plan that actually ran.
			""")
		.type(nullableRef(FormulaPlanDescriptor.THIS))
		.build();

	/**
	 * The one value in the object that is an absolute point in time rather than a duration or an offset, which is
	 * why it is a string in ISO-8601 form and not a number. Nullable because only the root step carries it - every
	 * other node would be repeating the same instant with a known offset already published as {@link #START}.
	 */
	PropertyDescriptor STARTED_AT = PropertyDescriptor.builder()
		.name("startedAt")
		.description("""
			Wall-clock instant at which the query began, in the ISO-8601 offset date-time format. Set only on
			the root step - it anchors the whole tree in time, so the wall-clock position of any other node is
			`startedAt` plus that node's `start` offset.
			""")
		.type(nullable(String.class))
		.build();

	/**
	 * The `QueryTelemetry` object itself, assembled from the properties above. Its structure is static - the same
	 * for every entity type and every catalog - which is what lets a single instance be shared instead of one being
	 * built per schema, and what lets {@link #STEPS} reference it recursively.
	 *
	 * The listing below reads as identity first, then the timings, with the wall-clock anchor last - but that is for
	 * the human reader only. {@link ObjectDescriptor} normalizes the properties through a hash map (keeping the last
	 * occurrence of each name), so the declared sequence is a set, not an ordering, and nothing downstream may rely
	 * on it.
	 */
	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("QueryTelemetry")
		.description("""
			This DTO contains detailed information about query processing time and its decomposition to single operations.
			""")
		.staticProperties(
			List.of(
				OPERATION, START, STEPS, ARGUMENTS,
				SPENT_TIME, FORMATTED_SPENT_TIME, SELF_TIME, FORMATTED_SELF_TIME,
				METRICS, PLAN, STARTED_AT
			)
		)
		.build();
}
