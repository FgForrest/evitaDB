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

import io.evitadb.api.query.ConstraintWithDefaults;
import io.evitadb.api.query.GenericConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;

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
 * The constraint is never implicit — it must be explicitly included when telemetry is needed. Because gathering
 * telemetry adds measurable overhead (a clock reading and a node per query *phase*), it should be disabled in
 * production hot paths and reserved for development, debugging, or profiling sessions. Conversely, a query that does
 * not ask for telemetry pays nothing at all for it — not even the strings describing the steps that would have been
 * recorded.
 *
 * Passing {@link QueryTelemetryContent#PLAN} additionally exposes the internal formula-tree the planner built, as
 * {@link io.evitadb.api.requestResponse.extraResult.FormulaPlan} nodes attached to the phases that produced it —
 * including the alternatives the planner considered and rejected, with the costs it estimated for them. Where the
 * timings say *where* the query spent itself, the plan says *what it was doing*. It is opt-in because rendering it
 * costs something, and because doing so inside the measured query perturbs the very numbers being measured: a run
 * made with `PLAN` is not directly comparable with one made without it. Rendering never computes anything, so the
 * parts of the plan the engine chose not to run are reported as "not computed" rather than being executed to fill
 * the report in.
 *
 * **Example**
 *
 * ```evitaql
 * queryTelemetry()
 * queryTelemetry(PLAN)
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
public class QueryTelemetry extends AbstractRequireConstraintLeaf
	implements ConstraintWithDefaults<RequireConstraint>, GenericConstraint<RequireConstraint> {
	@Serial private static final long serialVersionUID = -5121347556508500340L;

	/**
	 * Rebuilds the constraint from a raw argument array. It exists to back
	 * {@link #cloneWithArguments(Serializable[])}, which every constraint has to offer so that the generic visitors
	 * rewriting constraint trees can reconstruct any node from the arguments it reports.
	 *
	 * It is private because the public surface is the two typed constructors below: the argument this constraint
	 * accepts is a {@link QueryTelemetryContent} constant, and nothing else. A raw array reaching here has already
	 * been produced by this very class.
	 *
	 * An empty array resolves to the {@link QueryTelemetryContent#TIMINGS} default, mirroring what the creator does
	 * with a `null` level. This is not theoretical: the generic visitors feed `cloneWithArguments` whatever they
	 * computed, and the string form of the default form carries no arguments at all - without this, such a rewrite
	 * would produce a constraint whose {@link #getContent()} has nothing to return.
	 *
	 * @param arguments arguments to rebuild the constraint from - a single {@link QueryTelemetryContent} constant
	 */
	private QueryTelemetry(@Nonnull Serializable... arguments) {
		super(arguments.length == 0 ? new Serializable[]{QueryTelemetryContent.TIMINGS} : arguments);
	}

	/**
	 * Creates the `queryTelemetry()` constraint at the default {@link QueryTelemetryContent#TIMINGS} level.
	 *
	 * @see #QueryTelemetry(QueryTelemetryContent)
	 */
	public QueryTelemetry() {
		super(QueryTelemetryContent.TIMINGS);
	}

	/**
	 * Creates the `queryTelemetry(...)` constraint at the requested level of detail.
	 *
	 * This is the constructor the constraint descriptor machinery reflects on - {@link Creator} marks it as *the*
	 * way to instantiate the constraint, which is what lets the EvitaQL parser, the query builders and the external
	 * API schemas derive the constraint's published signature from it. It is also the constructor everything that
	 * rebuilds the constraint from the wire ends up calling.
	 *
	 * The level is always stored, `null` resolving to {@link QueryTelemetryContent#TIMINGS}, so no call site has to
	 * distinguish "unspecified" from "the default". It is the {@link ConstraintWithDefaults} contract below that
	 * keeps the default out of the string form, so a bare `queryTelemetry()` still prints as `queryTelemetry()`.
	 *
	 * @param content level of detail to profile at; `null` for the {@link QueryTelemetryContent#TIMINGS} default
	 */
	@Creator
	public QueryTelemetry(@Nullable QueryTelemetryContent content) {
		super(content == null ? QueryTelemetryContent.TIMINGS : content);
	}

	/**
	 * Returns the level of detail this constraint asks the profile to be built at.
	 *
	 * @return the requested level, never null and {@link QueryTelemetryContent#TIMINGS} unless asked otherwise
	 */
	@Nonnull
	public QueryTelemetryContent getContent() {
		return (QueryTelemetryContent) getArguments()[0];
	}

	/**
	 * Returns TRUE when the formula plan was requested via {@link QueryTelemetryContent#PLAN}.
	 *
	 * It is a dedicated accessor rather than a `getContent() == ...` at each call site because it is the single
	 * guard that keeps plan rendering out of every query that did not ask for one, and a named predicate is what
	 * makes that guard recognisable at the sites where it matters.
	 *
	 * @return TRUE when the formula plan should be rendered into the telemetry
	 */
	public boolean isPlanRequested() {
		return getContent() == QueryTelemetryContent.PLAN;
	}

	@Nonnull
	@Override
	public Serializable[] getArgumentsExcludingDefaults() {
		return Arrays.stream(getArguments())
			.filter(it -> it != QueryTelemetryContent.TIMINGS)
			.toArray(Serializable[]::new);
	}

	@Override
	public boolean isArgumentImplicit(@Nonnull Serializable serializable) {
		return serializable == QueryTelemetryContent.TIMINGS;
	}

	/**
	 * Creates a copy of this constraint with the passed arguments, as every constraint is required to.
	 *
	 * @param newArguments the new arguments to use for the cloned constraint
	 * @return a new `queryTelemetry` constraint instance
	 */
	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new QueryTelemetry(newArguments);
	}
}
