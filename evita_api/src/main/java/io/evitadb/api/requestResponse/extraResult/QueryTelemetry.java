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

package io.evitadb.api.requestResponse.extraResult;

import io.evitadb.api.requestResponse.EvitaResponseExtraResult;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.time.OffsetDateTime;
import java.util.LinkedList;
import java.util.List;

/**
 * This DTO contains detailed information about query processing time and its decomposition to single operations.
 * It is produced only for a query that explicitly asked for it through the `queryTelemetry` require constraint -
 * the engine seeds the root only when {@link io.evitadb.api.requestResponse.EvitaRequest} reports telemetry as
 * requested, so a query that did not ask for it pays nothing at all, not even the strings describing the steps that
 * would have been recorded.
 *
 * An instance is a single node of a tree. The root is created once per query by {@link #root(QueryPhase, String...)}
 * and every nested operation becomes a child through {@link #addStep(QueryPhase, String...)}. Steps nest on a stack -
 * a child is always opened and closed inside its parent - so children never overlap and never outlast the parent.
 * They do **not** tile it, though: a parent's {@link #getSpentTime()} is *not* the sum of its children's, and the
 * difference is the time the phase spent on its own work rather than in the phases inside it. The engine deliberately
 * does not carry that difference; the external APIs derive it and expose it as `selfTime`.
 *
 * Two different clocks are involved and the distinction matters to anyone rendering the result:
 *
 * - {@link #getStart()} and {@link #getSpentTime()} are {@link System#nanoTime()} based, i.e. a monotonic counter
 *   with no defined epoch - meaningful only relative to another reading taken in the same JVM
 * - {@link #getStartedAt()} is a real wall-clock instant, captured once and only for the root step; it is what
 *   anchors the whole tree in time so a profile can be correlated with logs, traces or another query
 *
 * The set of phases that appears is not guaranteed - a query whose index selection short-circuits legitimately
 * yields a bare root with no steps at all - so consumers must tolerate {@link #getSteps()} being empty at any level.
 *
 * The instance is mutable while the query is being measured ({@link #addStep(QueryPhase, String...)},
 * {@link #annotate(String)} and {@link #finish(String...)} all write into it) and is not thread safe. That is safe
 * because it is written only by the thread driving the query and read only once the query has completed.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
@EqualsAndHashCode
@NotThreadSafe
public class QueryTelemetry implements EvitaResponseExtraResult {
	@Serial private static final long serialVersionUID = 4135633155110416711L;

	/**
	 * Phase of the query processing this step measures. It is the only description a step is guaranteed to carry -
	 * {@link #arguments} may legitimately stay empty for the whole lifetime of the step.
	 */
	@Getter private final QueryPhase operation;
	/**
	 * Start of this step in nanoseconds. It is never a wall-clock timestamp and must not be rendered as a date.
	 *
	 * The exact meaning depends on how the telemetry was obtained:
	 *
	 * - **embedded** - the raw {@link System#nanoTime()} reading, i.e. a monotonic counter with no defined epoch.
	 *   It is only meaningful relative to another `nanoTime` reading taken in the same JVM - either another `start`
	 *   from this tree, or one the caller took itself.
	 * - **through a remote client** (gRPC / REST / GraphQL) - the number of nanoseconds elapsed since the root step
	 *   of this tree began, so the root always reports `0`. The raw reading is taken on the server and carries no
	 *   epoch, which would make it meaningless to a remote client, so the external APIs normalize it.
	 */
	@Getter private final long start;
	/**
	 * Internal steps of this telemetry step (operation decomposition), in the order they were opened. A phase that
	 * was not decomposed any further keeps this list empty - which is a normal outcome, not a sign of lost data.
	 */
	@Getter private final List<QueryTelemetry> steps = new LinkedList<>();
	/**
	 * Human readable details of the processing phase - for example which index was selected and at what estimated
	 * cost. A step is described either at push time (what it is about to do) or at pop time through
	 * {@link #finish(String...)} (what it decided), and {@link #annotate(String)} appends to whatever is there.
	 */
	@Getter private String[] arguments;
	/**
	 * Duration of this phase in nanoseconds, covering the phase itself and everything nested below it. It stays `0`
	 * until the step is closed through {@link #finish()} / {@link #finish(String...)}, so an unfinished step is
	 * indistinguishable from one that genuinely took no time.
	 */
	@Getter private long spentTime;
	/**
	 * Wall-clock instant at which the **root** step of this tree began.
	 *
	 * Unlike {@link #start}, this one *is* a real timestamp and can be rendered as a date - it is what anchors the
	 * whole tree in time, so a telemetry profile can be correlated with logs, traces or another query. It is captured
	 * once per query and only for the root step; every other node reports `null` and its own wall-clock position is
	 * derived as `startedAt` plus that node's `start` offset.
	 */
	@Nullable @Getter private final OffsetDateTime startedAt;

	/**
	 * Creates the **root** of a telemetry tree, stamping it with the wall-clock instant at which the query began.
	 * Use this exactly once per query - inner steps are added through {@link #addStep(QueryPhase, String...)}.
	 *
	 * The wall-clock stamp is what separates a root from any other step: it is the single value that anchors the
	 * whole tree in absolute time, and every other node's position is derived from it plus that node's
	 * {@link #getStart()} offset.
	 *
	 * @param operation phase the root step measures - in practice always {@link QueryPhase#OVERALL}
	 * @param arguments optional description of the query the tree belongs to
	 * @return the freshly opened root step, already ticking
	 */
	@Nonnull
	public static QueryTelemetry root(@Nonnull QueryPhase operation, @Nonnull String... arguments) {
		return new QueryTelemetry(operation, OffsetDateTime.now(), arguments);
	}

	/**
	 * Creates a **non-root** step that starts ticking immediately, i.e. one whose {@link #getStartedAt()} stays
	 * `null`. Use it when the telemetry is built up from scratch - in production that happens through
	 * {@link #addStep(QueryPhase, String...)}, which delegates here. A tree assembled this way carries no wall-clock
	 * anchor at all, so use {@link #root(QueryPhase, String...)} for the node that represents the whole query.
	 *
	 * @param operation phase this step measures
	 * @param arguments push-time description of what the phase is about to do; leave empty when the step is to be
	 *                  described at pop time through {@link #finish(String...)}
	 */
	public QueryTelemetry(@Nonnull QueryPhase operation, @Nonnull String... arguments) {
		this(operation, null, arguments);
	}

	/**
	 * This constructor should be used for query telemetry deserialization. Unlike the measuring constructors it takes
	 * the timings as they were recorded elsewhere instead of reading the clock, and it therefore also accepts trees
	 * that could never arise from measuring - see {@link #getStart()} for what the timings mean on either side.
	 *
	 * @param operation phase this step measures
	 * @param start     recorded start of this step
	 * @param spentTime recorded duration of this step, covering everything nested below it
	 * @param arguments recorded description of the phase
	 * @param steps     already deserialized child steps, adopted in the given order
	 */
	public QueryTelemetry(@Nonnull QueryPhase operation, long start, long spentTime, @Nonnull String[] arguments, @Nonnull QueryTelemetry[] steps) {
		this(operation, start, spentTime, null, arguments, steps);
	}

	/**
	 * This constructor should be used for query telemetry deserialization of a tree whose root carries the wall-clock
	 * instant the query started at. It is the full form of
	 * {@link #QueryTelemetry(QueryPhase, long, long, String[], QueryTelemetry[])} - pass `startedAt` for the root
	 * step of the tree and `null` for every other node, mirroring how the measuring side stamps it.
	 *
	 * @param operation phase this step measures
	 * @param start     recorded start of this step
	 * @param spentTime recorded duration of this step, covering everything nested below it
	 * @param startedAt recorded wall-clock instant the query began, for the root step only
	 * @param arguments recorded description of the phase
	 * @param steps     already deserialized child steps, adopted in the given order
	 */
	public QueryTelemetry(@Nonnull QueryPhase operation, long start, long spentTime, @Nullable OffsetDateTime startedAt, @Nonnull String[] arguments, @Nonnull QueryTelemetry[] steps) {
		this.operation = operation;
		this.start = start;
		this.spentTime = spentTime;
		this.startedAt = startedAt;
		this.arguments = arguments;
		for (final QueryTelemetry step : steps) {
			addStep(step);
		}
	}

	/**
	 * Internal constructor allowing the root step to be stamped with the wall-clock instant of the query start. It is
	 * the single place where a measuring step starts ticking, and it is private because the `startedAt` argument is
	 * exactly the root/non-root distinction - callers reach it through {@link #root(QueryPhase, String...)} or
	 * {@link #QueryTelemetry(QueryPhase, String...)} and thereby say which of the two they mean.
	 *
	 * @param operation phase this step measures
	 * @param startedAt wall-clock instant the query began when this is the root step, `null` otherwise
	 * @param arguments push-time description of what the phase is about to do
	 */
	private QueryTelemetry(@Nonnull QueryPhase operation, @Nullable OffsetDateTime startedAt, @Nonnull String... arguments) {
		this.operation = operation;
		this.arguments = arguments;
		this.startedAt = startedAt;
		this.start = System.nanoTime();
	}

	/**
	 * Finalizes current step of the query telemetry and stores the time spent.
	 *
	 * The passed arguments *replace* the ones this step was created with, which is why the step must not carry any
	 * yet - a step is described either at push time (what it is about to do) or at pop time (what it decided), never
	 * both. {@link #annotate(String)} is the way to attach a value to a step that already has a description.
	 *
	 * The one-shot rule is asserted rather than resolved silently: a step that was already described and is finished
	 * with a second description would lose one of the two, and losing it quietly would leave a profile that looks
	 * complete but is not.
	 *
	 * @param arguments pop-time description of what the phase decided
	 * @return this step, so that closing it can be chained
	 * @throws io.evitadb.exception.EvitaInvalidUsageException when the step already carries arguments - use
	 *         {@link #finish()} for a step described at push time or annotated during it
	 */
	@Nonnull
	public QueryTelemetry finish(@Nonnull String... arguments) {
		this.spentTime = System.nanoTime() - this.start;
		Assert.isTrue(ArrayUtils.isEmpty(this.arguments), "Arguments have been already set!");
		this.arguments = arguments;
		return this;
	}

	/**
	 * Appends a single argument to this step, leaving the ones already present intact.
	 *
	 * This is how a value measured *during* a phase is attached to the node representing that phase. Unlike
	 * {@link #finish(String...)}, which replaces the arguments wholesale and therefore refuses to run twice, this
	 * method accumulates - so a step that was already described at push time can still be annotated with what it
	 * observed. It exists so that such a value does not have to be smuggled in as a synthetic zero-duration child
	 * step, which is structurally indistinguishable from a span that really did take no time.
	 *
	 * Note that annotating a step makes its arguments non-empty, so it can no longer be finished through
	 * {@link #finish(String...)} - only through {@link #finish()}. That is intentional: the two describe the same
	 * slot and a step claiming both would silently lose one of them.
	 *
	 * @param argument the value to append to this step's arguments
	 * @return this step, so that annotating can be chained
	 */
	@Nonnull
	public QueryTelemetry annotate(@Nonnull String argument) {
		this.arguments = ArrayUtils.insertRecordIntoArrayOnIndex(argument, this.arguments, this.arguments.length);
		return this;
	}

	/**
	 * Opens a new internal step of query processing nested in this phase - this is the measuring path, so the child
	 * starts ticking the moment it is created and the caller is responsible for closing it through
	 * {@link #finish()} / {@link #finish(String...)}. A step that is never finished reports a `spentTime` of `0`,
	 * which is indistinguishable from a phase that genuinely took no time.
	 *
	 * @param operation phase the new child step measures
	 * @param arguments push-time description of what the child phase is about to do
	 * @return the newly opened child step - **not** this step
	 */
	@Nonnull
	public QueryTelemetry addStep(@Nonnull QueryPhase operation, @Nonnull String... arguments) {
		final QueryTelemetry step = new QueryTelemetry(operation, arguments);
		this.steps.add(step);
		return step;
	}

	/**
	 * Adopts an already built step as an internal step of query processing in this phase. Unlike
	 * {@link #addStep(QueryPhase, String...)} nothing is measured here - the passed subtree keeps the timings it
	 * came with - which is what the deserializing constructors need when they rebuild a tree bottom up.
	 *
	 * @param step the step to append to this phase, together with everything nested below it
	 */
	public void addStep(@Nonnull QueryTelemetry step) {
		this.steps.add(step);
	}

	/**
	 * Finalizes current step of the query telemetry and stores the time spent, leaving the arguments untouched.
	 *
	 * This is the counterpart of {@link #finish(String...)} and the only way to close a step that was already
	 * described - at push time, or through {@link #annotate(String)} while it was running.
	 *
	 * @return this step, so that closing it can be chained
	 */
	@Nonnull
	public QueryTelemetry finish() {
		this.spentTime = System.nanoTime() - this.start;
		return this;
	}

	/**
	 * Renders the whole telemetry tree rooted at this step as an indented, multi line profile - one line per phase
	 * with its arguments and its {@link #getSpentTime()} in human readable form. Intended for logs and debugging;
	 * the remote APIs render the tree from their own DTOs instead.
	 *
	 * @return a string representation of this step and everything nested below it
	 */
	@Override
	public String toString() {
		return toString(0);
	}

	/**
	 * Returns a string representation of the QueryTelemetry object with an indentation level. Each nesting level is
	 * indented by five more spaces than its parent, so the depth of a phase is readable at a glance.
	 *
	 * @param indent the number of spaces to indent the string
	 * @return a string representation of the QueryTelemetry object
	 */
	public String toString(int indent) {
		final StringBuilder sb = new StringBuilder(128 + indent);
		sb.append(" ".repeat(indent));
		sb.append(this.operation);
		if (this.arguments.length > 0) {
			sb.append("(");
			for (int i = 0; i < this.arguments.length; i++) {
				if (i > 0) {
					sb.append(", ");
				}
				sb.append(this.arguments[i]);
			}
			sb.append(") ");
		}
		sb.append(": ").append(StringUtils.formatNano(this.spentTime)).append("\n");
		if (!this.steps.isEmpty()) {
			for (final QueryTelemetry step : this.steps) {
				sb.append(step.toString(indent + 5));
			}
		}
		return sb.toString();
	}

	/**
	 * Enum contains all query execution phases, that leads from request to response. The two top level ones are
	 * {@link #PLANNING} (cheap, decides *how* the query will be answered) and {@link #EXECUTION} (the work itself);
	 * everything else decomposes one of them.
	 *
	 * Which phases actually appear in a telemetry tree depends on the query - a phase that had nothing to do is not
	 * recorded at all, so consumers must never assume a fixed shape. The constants are part of the public API: they
	 * are mirrored into `GrpcQueryPhase` and exposed as an enum type by the JSON based APIs, so a new one has to be
	 * added to the protobuf definition and to both directions of `EvitaEnumConverter` as well.
	 */
	public enum QueryPhase {

		/**
		 * Entire query execution time - the root step of every telemetry tree, covering everything from planning to
		 * the assembled response. It is the only phase that carries the wall-clock
		 * {@link QueryTelemetry#getStartedAt()} stamp, and the only one guaranteed to be present.
		 */
		OVERALL,
		/**
		 * Entire planning phase of the query execution - index selection, formula and sorter construction, and extra
		 * result producer setup. No entity data is touched here; planning only builds the recipe that
		 * {@link #EXECUTION} then runs.
		 */
		PLANNING,
		/**
		 * Planning phase of the inner query execution, i.e. a full planning cycle run for a query nested inside
		 * another one - the sub-query that selects referenced entities, or the one behind a reference `having`
		 * filter. It repeats the whole decomposition {@link #PLANNING} does, which is why a query with nested
		 * queries shows several of these siblings.
		 */
		PLANNING_NESTED_QUERY,
		/**
		 * Determining which indexes should be used. The outcome is a set of candidate index combinations, each of
		 * which is then planned separately - that is where the `_ALTERNATIVE` phases below come from.
		 */
		PLANNING_INDEX_USAGE,
		/**
		 * Creating formula for filtering entities - covers all the candidate index combinations together.
		 */
		PLANNING_FILTER,
		/**
		 * Creating formula for nested query, i.e. planning the filter of a sub-query that some outer constraint
		 * (hierarchy, reference having, facet) needs computed.
		 */
		PLANNING_FILTER_NESTED_QUERY,
		/**
		 * Creating alternative formula for filtering entities. One such step is recorded for **each** candidate index
		 * combination, and its arguments carry the estimated cost the candidate was judged by - which makes these
		 * steps the place to look when the engine picked an index that seems wrong.
		 */
		PLANNING_FILTER_ALTERNATIVE,
		/**
		 * Creating formula for sorting result entities - covers all the surviving query plan candidates together.
		 */
		PLANNING_SORT,
		/**
		 * Creating alternative formula for sorting result entities. Recorded per query plan candidate, but only when
		 * more than one candidate survived filter planning - a query with a single plan reports its sort planning
		 * directly under {@link #PLANNING_SORT} with no intermediate step.
		 */
		PLANNING_SORT_ALTERNATIVE,
		/**
		 * Creating factories for requested extra results (histograms, hierarchies, facet summaries). Present only
		 * when the query actually requires extra results.
		 */
		PLANNING_EXTRA_RESULT_FABRICATION,
		/**
		 * Creating factories for requested extra results based on alternative indexes. As with
		 * {@link #PLANNING_SORT_ALTERNATIVE}, it is recorded per query plan candidate and only when more than one
		 * candidate survived filter planning.
		 */
		PLANNING_EXTRA_RESULT_FABRICATION_ALTERNATIVE,
		/**
		 * Entire query execution phase - running the plan {@link #PLANNING} produced. This is where the expensive
		 * work happens and therefore usually where a slow query spends its time.
		 */
		EXECUTION,
		/**
		 * Prefetching entities that should be examined instead of consulting indexes. Recorded only when the planner
		 * decided to prefetch at all, so the presence of this phase is itself informative.
		 */
		EXECUTION_PREFETCH,
		/**
		 * Computing entities that should be returned in output (filtering). This is where the filtering formula is
		 * evaluated and memoized, so a cache hit shows up here as a phase that took almost no time.
		 */
		EXECUTION_FILTER,
		/**
		 * Computing entities within nested query that should be returned in output (filtering) - the execution time
		 * counterpart of {@link #PLANNING_FILTER_NESTED_QUERY}.
		 */
		EXECUTION_FILTER_NESTED_QUERY,
		/**
		 * Sorting output entities and slicing requested page. Its arguments carry the sort resolution strategies the
		 * merged sorters actually used, with a count per strategy.
		 */
		EXECUTION_SORT_AND_SLICE,
		/**
		 * Fabricating requested extra results - the umbrella step covering every requested extra result producer.
		 */
		EXTRA_RESULTS_FABRICATION,
		/**
		 * Fabricating requested single extra result. One step per producer, named after it, so that a single
		 * expensive extra result can be told apart from many cheap ones.
		 */
		EXTRA_RESULT_ITEM_FABRICATION,
		/**
		 * Fetching rich data from the storage based on computed entity primary keys. Present only when the query
		 * asked for entity bodies rather than bare primary keys.
		 */
		FETCHING,
		/**
		 * Aggregate phase that covers the orchestration of loading all referenced entities for a result page —
		 * per-reference predicate setup, referenced primary key collection, deduplication, recursive dispatch
		 * into nested reference fetches, and the actual storage reads that show up nested as
		 * {@link #FETCHING_REFERENCES} children.
		 */
		FETCHING_REFERENCE_BODIES,
		/**
		 * Fetching referenced entities and entity groups from the storage based on referenced primary keys
		 * information. Recorded per reference name - the name is carried in the step arguments - and nested below
		 * the {@link #FETCHING_REFERENCE_BODIES} step that orchestrated it.
		 */
		FETCHING_REFERENCES,
		/**
		 * Fetching parent entities from the storage based on parent primary keys information. It is the hierarchy
		 * counterpart of {@link #FETCHING_REFERENCES} and shares the very same fetching implementation.
		 */
		FETCHING_PARENTS

	}
}
