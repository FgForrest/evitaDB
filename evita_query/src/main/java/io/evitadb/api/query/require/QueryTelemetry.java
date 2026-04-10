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

package io.evitadb.api.query.require;

import io.evitadb.api.query.GenericConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * The `queryTelemetry` require constraint instructs the engine to measure and expose detailed query execution
 * metrics. When present, the response's extra-results section will contain an
 * {@link io.evitadb.api.requestResponse.extraResult.QueryTelemetry} DTO with a hierarchical breakdown of all
 * operations performed during query processing, including:
 *
 * - the query phase (`operation`) — e.g. parsing, filtering, ordering, fetching
 * - the wall-clock start time in nanoseconds (`start`)
 * - sub-steps (`steps`) that recursively decompose each phase into its constituent operations
 * - phase-specific arguments (`arguments`) — for example, the constraint name being evaluated
 * - total wall time spent in each phase (`spentTime`, nanoseconds)
 *
 * This information is invaluable for diagnosing slow queries: it reveals which phase dominates the execution time
 * and exposes the internal formula-tree evaluation hierarchy.
 *
 * The constraint takes no arguments and is never implicit — it must be explicitly included when telemetry is needed.
 * Because gathering telemetry adds measurable overhead (instrumentation at each formula evaluation), it should be
 * disabled in production hot paths and reserved for development, debugging, or profiling sessions.
 *
 * **Example**
 *
 * ```evitaql
 * queryTelemetry()
 * ```
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/debug#query-telemetry)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "queryTelemetry",
	shortDescription = "The constraint triggers computation of query telemetry (explaining what operations were performed and how long they took) in extra results of the response.",
	userDocsLink = "/documentation/query/requirements/debug#query-telemetry"
)
public class QueryTelemetry extends AbstractRequireConstraintLeaf implements GenericConstraint<RequireConstraint> {
	@Serial private static final long serialVersionUID = -5121347556508500340L;

	private QueryTelemetry(@Nonnull Serializable... arguments) {
		super(arguments);
	}

	@Creator
	public QueryTelemetry() {
		super();
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new QueryTelemetry(newArguments);
	}
}
