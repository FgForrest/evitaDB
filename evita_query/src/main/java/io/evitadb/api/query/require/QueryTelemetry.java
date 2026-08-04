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
 * - the query phase (`operation`) — e.g. planning, filtering, ordering, fetching
 * - when the step began (`start`, nanoseconds) — **not** a wall-clock timestamp and never renderable as a date.
 *   Embedded, it is a raw {@link System#nanoTime()} reading, i.e. a monotonic counter with no defined epoch; through
 *   a remote client it is normalized to the offset from the root step, so the root always reports `0`
 * - the wall-clock instant the query began (`startedAt`) — carried by the root step only, and the one value that
 *   anchors the whole tree in time so a profile can be correlated with logs or traces
 * - sub-steps (`steps`) that recursively decompose each phase into its constituent operations
 * - phase-specific arguments (`arguments`) — for example, which index was selected and at what estimated cost
 * - total time spent in each phase (`spentTime`, nanoseconds), covering that phase and everything nested below it
 *
 * This information is invaluable for diagnosing slow queries: it reveals which phase dominates the execution time.
 * Note that a parent's `spentTime` is *not* the sum of its children's — the remote APIs additionally expose the
 * difference as `selfTime`, which is how much of a phase is the phase itself rather than the phases inside it.
 *
 * The phase set is not guaranteed: a query whose index selection short-circuits, or a dry run, legitimately yields
 * a bare root with no steps at all. Clients must tolerate that.
 *
 * The constraint takes no arguments and is never implicit — it must be explicitly included when telemetry is needed.
 * Because gathering telemetry adds measurable overhead (a clock reading and a node per query *phase*), it should be
 * disabled in production hot paths and reserved for development, debugging, or profiling sessions. Conversely, a
 * query that does not ask for telemetry pays nothing at all for it — not even the strings describing the steps that
 * would have been recorded.
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

	/**
	 * Rebuilds the constraint from a raw argument array. It exists solely to back
	 * {@link #cloneWithArguments(Serializable[])}, which every constraint has to offer so that the generic visitors
	 * rewriting constraint trees can reconstruct any node from the arguments it reports - without knowing that this
	 * particular one reports none.
	 *
	 * It is private because that is the only legitimate use: this constraint takes no arguments, so a non-empty
	 * array describes a `queryTelemetry()` that cannot exist. Application code, the EvitaQL parser and Kryo
	 * deserialization all go through {@link #QueryTelemetry()} instead.
	 *
	 * @param arguments arguments to rebuild the constraint from - in practice always empty
	 */
	private QueryTelemetry(@Nonnull Serializable... arguments) {
		super(arguments);
	}

	/**
	 * Creates the argument-less `queryTelemetry()` constraint, which is the only form this constraint has.
	 *
	 * This is the constructor the constraint descriptor machinery reflects on - {@link Creator} marks it as *the*
	 * way to instantiate the constraint, which is what lets the EvitaQL parser, the query builders and the external
	 * API schemas derive a zero-parameter `queryTelemetry` from it. Adding a second `@Creator` here, or moving the
	 * annotation to the varargs constructor above, would change the constraint's published signature in all of
	 * them at once.
	 *
	 * It is also the constructor everything that rebuilds the constraint from the wire ends up calling: the
	 * constraint carries no state, so its Kryo serializer writes nothing and reads it back by calling this.
	 */
	@Creator
	public QueryTelemetry() {
		super();
	}

	/**
	 * Creates a copy of this constraint with the passed arguments, as every constraint is required to. The
	 * constraint carries no arguments, so a meaningful clone always passes an empty array; `newArguments` is handed
	 * over verbatim to the private constructor only because the contract is defined uniformly across all
	 * constraints and the generic visitors that rebuild constraint trees rely on it.
	 *
	 * @param newArguments the new arguments to use for the cloned constraint - always empty for this constraint
	 * @return a new `queryTelemetry` constraint instance
	 */
	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new QueryTelemetry(newArguments);
	}
}
