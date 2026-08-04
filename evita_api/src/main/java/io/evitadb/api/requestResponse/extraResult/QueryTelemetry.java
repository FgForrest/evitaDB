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
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.OptionalLong;

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
	 * Value stored in {@link #metrics} for a metric that was never recorded on this step.
	 *
	 * `Long.MIN_VALUE` is chosen because no {@link StepMetric} can legitimately take it - they are counts, sizes,
	 * costs and flags, none of which is ever negative - so the sentinel cannot collide with a real measurement. It
	 * has to be a sentinel rather than `0` because "not measured for this phase" and "measured, and the answer was
	 * zero" are different facts, and a client acting on the numbers must be able to tell them apart.
	 */
	private static final long UNSET = Long.MIN_VALUE;
	/**
	 * Number of slots a {@link #metrics} array needs. Cached because {@link StepMetric#values()} clones its backing
	 * array on every call and this is read on the allocation path.
	 */
	private static final int METRIC_COUNT = StepMetric.values().length;

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
	 * Typed numeric measurements recorded for this phase - the introspectable counterpart of {@link #arguments},
	 * which is prose. Indexed by {@link StepMetric#ordinal()} and filled with {@link #UNSET}, so a metric that was
	 * not measured for this phase stays distinguishable from one measured as `0`.
	 *
	 * The array is allocated on the **first** {@link #recordMetric(StepMetric, long)} call and stays `null` until
	 * then. That is what keeps the feature free for queries that did not ask for telemetry: recording is reachable
	 * only from inside the engine's telemetry guard, so on a query without telemetry nothing calls it and no array
	 * is ever created.
	 */
	@Nullable private long[] metrics;
	/**
	 * Structure of the formula this phase built or ran, recorded only when the query asked for it with
	 * {@link io.evitadb.api.query.require.QueryTelemetryContent#PLAN} - `null` on every phase otherwise, which is
	 * every phase of every query by default.
	 *
	 * Where {@link #metrics} says how much and {@link #spentTime} says how long, this says *what the engine was
	 * actually doing*. It is attached to the phases that own a formula: each `PLANNING_FILTER_ALTERNATIVE` carries
	 * the candidate it costed (including the ones that lost), and the root carries the plan that was executed.
	 *
	 * Like the metrics array it stays `null` until something records into it, so a query that did not ask for the
	 * plan allocates nothing and walks no formula tree.
	 */
	@Nullable @Getter private FormulaPlan plan;
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
	 * Records a typed numeric measurement on this step, replacing whatever was recorded for the same metric before.
	 *
	 * This is the typed sibling of {@link #annotate(String)}: prose describes what a phase did, a metric is a number
	 * a client can act on - compare, threshold, chart - without parsing English. In particular it is what lets the
	 * engine publish an estimate next to the actual outcome, which is how a bad plan is recognised.
	 *
	 * The backing array is allocated here, on the first call, and never before - see {@link #metrics} for why that
	 * matters. Recording is therefore the *only* thing telemetry metrics ever cost, and it happens only on a query
	 * that asked for telemetry.
	 *
	 * Unlike {@link #finish(String...)} this has no one-shot rule and is independent of the step's lifecycle: a step
	 * may be described at push time, annotated while running, measured, and finished, in any combination.
	 *
	 * @param metric the measurement being recorded
	 * @param value  the measured value; must not be `Long.MIN_VALUE`, which is reserved to mark a metric as unset
	 * @return this step, so that recording can be chained
	 */
	@Nonnull
	public QueryTelemetry recordMetric(@Nonnull StepMetric metric, long value) {
		if (this.metrics == null) {
			this.metrics = new long[METRIC_COUNT];
			Arrays.fill(this.metrics, UNSET);
		}
		this.metrics[metric.ordinal()] = value;
		return this;
	}

	/**
	 * Records a flag-shaped measurement on this step, storing it as `1` for `true` and `0` for `false`.
	 *
	 * Flags share the numeric container rather than getting one of their own because there are few of them and
	 * a second lazily allocated array would cost more than the packing does. Which metrics are flags is fixed and
	 * documented on {@link StepMetric}, so the external APIs can publish them as booleans rather than as `0`/`1`.
	 *
	 * @param metric the flag being recorded
	 * @param value  the measured value
	 * @return this step, so that recording can be chained
	 */
	@Nonnull
	public QueryTelemetry recordMetric(@Nonnull StepMetric metric, boolean value) {
		return recordMetric(metric, value ? 1L : 0L);
	}

	/**
	 * Returns the value recorded for the passed metric, or empty when the engine did not measure it on this step.
	 *
	 * Empty is the normal outcome for most metric/step combinations - metrics are recorded where the engine happens
	 * to compute them, not everywhere - and it is deliberately different from a recorded `0`. Callers rendering the
	 * value must preserve that distinction rather than defaulting the empty case to zero.
	 *
	 * {@link OptionalLong} is returned rather than a boxed `Long` so that reading a metric allocates nothing on the
	 * embedded path, where the caller usually only wants to test presence.
	 *
	 * @param metric the measurement to read
	 * @return the recorded value, or empty when this step carries no measurement for that metric
	 */
	@Nonnull
	public OptionalLong getMetric(@Nonnull StepMetric metric) {
		if (this.metrics == null) {
			return OptionalLong.empty();
		}
		final long value = this.metrics[metric.ordinal()];
		return value == UNSET ? OptionalLong.empty() : OptionalLong.of(value);
	}

	/**
	 * Returns true when at least one {@link StepMetric} was recorded on this step.
	 *
	 * It answers the question in one test instead of probing every metric, which is what the external APIs need in
	 * order to decide whether to emit a metrics object for this node at all - today only the root step carries any,
	 * so the answer is `false` for nearly every node of a tree.
	 *
	 * @return true when this step carries at least one recorded measurement
	 */
	public boolean hasMetrics() {
		return this.metrics != null;
	}

	/**
	 * Records the structure of the formula this phase built or ran.
	 *
	 * Recording is unrelated to the one-shot assert on {@link #finish(String...)}, so a step can carry a
	 * description *and* its plan - the same independence {@link #recordMetric(StepMetric, long)} has.
	 *
	 * @param plan structure of the formula, rendered without computing anything
	 * @return self, so recordings can be chained
	 */
	@Nonnull
	public QueryTelemetry recordPlan(@Nonnull FormulaPlan plan) {
		this.plan = plan;
		return this;
	}

	/**
	 * Returns true when a formula plan was recorded on this step, which happens only for a query that asked for it
	 * with {@link io.evitadb.api.query.require.QueryTelemetryContent#PLAN}.
	 *
	 * @return true when this step carries a formula plan
	 */
	public boolean hasPlan() {
		return this.plan != null;
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

	/**
	 * Enum contains the typed numeric measurements a step can carry, recorded through
	 * {@link QueryTelemetry#recordMetric(StepMetric, long)} and read back through
	 * {@link QueryTelemetry#getMetric(StepMetric)}.
	 *
	 * The vocabulary is deliberately closed. A `Map<String, Number>` would be the obvious shape and the wrong one:
	 * it would allocate a map and box every value on a path that must stay free, and it would let each recording
	 * site invent its own key, leaving clients to pattern-match strings. A closed enum indexes a primitive array
	 * instead, and gives the external APIs a fixed set of named fields to publish.
	 *
	 * **The set only ever grows by appending.** Ordinals index the backing array of a single query's telemetry and
	 * never outlive it, but the constants are published as named fields by every external API, so removing or
	 * reordering one is a wire break while adding one at the end is not.
	 *
	 * Which metrics appear on which step is not guaranteed and never will be - a metric is recorded where the engine
	 * happens to compute the number, so consumers must treat every one of them as optional. Today they are all
	 * recorded on the {@link QueryPhase#OVERALL} root, describing the query as a whole.
	 *
	 * All metrics are non-negative counts, sizes or costs, except where noted as a flag - flags are stored as `1`
	 * for `true` and `0` for `false` and are published as booleans by the external APIs.
	 */
	public enum StepMetric {

		/**
		 * How many records the planner *expected* to examine, i.e. the filtering formula's estimated cardinality.
		 *
		 * Its whole value is in the comparison with {@link #ACTUAL_CARDINALITY}: the two together are how a bad plan
		 * is identified. An estimate that is orders of magnitude off is the reason the engine picked the index it
		 * picked, and no amount of timing data reveals it.
		 */
		ESTIMATED_CARDINALITY,
		/**
		 * How many records the filtering formula *actually* matched - the total record count, before paging.
		 *
		 * Note this counts what the filter found, not what was returned to the client; {@link #RECORDS_RETURNED} is
		 * the size of the requested page cut out of it.
		 */
		ACTUAL_CARDINALITY,
		/**
		 * Cost the planner *estimated* for the filtering formula it chose - the same unitless number the planner
		 * compares candidate indexes by, so it is comparable across plans of the same query but means nothing in
		 * absolute terms.
		 *
		 * Recorded as unset when the estimate overflowed, which the engine reports as `Long.MAX_VALUE`.
		 */
		ESTIMATED_COST,
		/**
		 * Cost the filtering formula *actually* incurred, computed from the real cardinalities once the formula ran.
		 *
		 * Together with {@link #ESTIMATED_COST} this is the second half of the estimate-versus-actual pair. Recorded
		 * as unset when the formula was never computed - the engine reports `Long.MAX_VALUE` in that case, which a
		 * naive client would otherwise render as a nine-quintillion cost.
		 */
		ACTUAL_COST,
		/**
		 * How many records were actually handed back, i.e. the size of the page cut out of
		 * {@link #ACTUAL_CARDINALITY}. Legitimately `0` for a query whose page lies past the end of the result.
		 */
		RECORDS_RETURNED,
		/**
		 * How many times the storage was read while assembling the response. Legitimately `0` - a query answered
		 * entirely from indexes, or one returning bare primary keys, touches storage not at all.
		 */
		IO_FETCH_COUNT,
		/**
		 * How many bytes were read from the storage while assembling the response. Reported alongside
		 * {@link #IO_FETCH_COUNT} because the two answer different questions: many small reads and one large read
		 * cost very differently.
		 */
		IO_FETCHED_SIZE_BYTES,
		/**
		 * Flag - whether the planner prefetched entity bodies and filtered over them instead of consulting indexes.
		 *
		 * It explains the shape of the rest of the profile rather than measuring anything: a prefetched query spends
		 * its time in {@link QueryPhase#EXECUTION_PREFETCH} and barely touches the index phases, which looks like a
		 * different query altogether unless this flag is read.
		 */
		PREFETCHED

	}
}
