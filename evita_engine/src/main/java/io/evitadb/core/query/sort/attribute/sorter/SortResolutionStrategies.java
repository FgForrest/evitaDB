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

package io.evitadb.core.query.sort.attribute.sorter;

import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.ForcedSortResolution;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.PositionResolution;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortResolutionStrategy;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Execution-time helpers shared by the merged sorters for the positional resolution strategy: mapping the query's
 * {@link DebugMode} overrides to a {@link ForcedSortResolution}, and recording the
 * {@link SortResolutionStrategy strategies} actually used into the current query telemetry step. Both facilities are
 * no-ops on the common path (no debug override / telemetry not requested), so they add no measurable overhead there.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
final class SortResolutionStrategies {

	/**
	 * Cached {@link SortResolutionStrategy} constants (indexed by {@link SortResolutionStrategy#ordinal()}) to avoid the
	 * per-call array allocation of `values()`.
	 */
	private static final SortResolutionStrategy[] STRATEGIES = SortResolutionStrategy.values();

	/**
	 * This class is a holder of static helpers only and is never instantiated.
	 */
	private SortResolutionStrategies() {
		throw new UnsupportedOperationException("This class cannot be instantiated!");
	}

	/**
	 * Maps the query's debug overrides to a forced resolution family. {@link DebugMode#PREFER_TREE_SORT} takes
	 * precedence over {@link DebugMode#PREFER_PRESORT_ARRAYS} in the (erroneous) event both are present; returns `null`
	 * when neither is set, leaving the cost-based selector in charge.
	 *
	 * @param queryContext the execution context whose active debug modes are consulted
	 * @return the forced resolution family, or `null` for cost-based selection
	 */
	@Nullable
	static ForcedSortResolution resolveForcedResolution(@Nonnull QueryExecutionContext queryContext) {
		final QueryPlanningContext planningContext = queryContext.getQueryContext();
		if (planningContext.isDebugModeEnabled(DebugMode.PREFER_TREE_SORT)) {
			return ForcedSortResolution.TREE;
		}
		if (planningContext.isDebugModeEnabled(DebugMode.PREFER_PRESORT_ARRAYS)) {
			return ForcedSortResolution.ARRAY;
		}
		return null;
	}

	/**
	 * Allocates a per-sort tally of resolution strategies (indexed by {@link SortResolutionStrategy#ordinal()}), or
	 * `null` when no telemetry step is open - in which case the caller skips tallying entirely and pays no
	 * overhead.
	 *
	 * The probe is whether a telemetry step is currently open, which is the precondition {@link #report} actually
	 * needs - there is no point tallying into an array that would have nowhere to be reported to. Deciding it once
	 * here, up front, is what lets the per-record {@link #tally} collapse to a single null check.
	 *
	 * @param queryContext the execution context whose telemetry state is probed
	 * @return a zeroed tally array, or `null` when no step is open to report into
	 */
	@Nullable
	static int[] newStrategyTally(@Nonnull QueryExecutionContext queryContext) {
		return queryContext.getQueryContext().getCurrentStep() == null ? null : new int[STRATEGIES.length];
	}

	/**
	 * Records the strategy of `resolution` into `tally`; a no-op when `tally` is `null` (telemetry off).
	 *
	 * @param tally      the tally to increment, or `null` when telemetry is off
	 * @param resolution the resolution whose {@link PositionResolution#strategy()} is counted
	 */
	static void tally(@Nullable int[] tally, @Nonnull PositionResolution resolution) {
		if (tally != null) {
			tally[resolution.strategy().ordinal()]++;
		}
	}

	/**
	 * Annotates whichever telemetry step is currently open - normally
	 * {@link QueryPhase#EXECUTION_SORT_AND_SLICE} - with the accumulated `tally`
	 * (e.g. `sortResolution=TREE_DENSE_WALKx1,ARRAY_MERGE_WALKx2`). A no-op when `tally` is `null` or the current
	 * step is absent.
	 *
	 * It reads the step off the planning context rather than through
	 * {@link io.evitadb.core.query.QueryExecutionContext}, so unlike a pushed step this annotation is *not*
	 * suppressed during a plan-verification dry run, where the open step is `PLANNING` rather than the sort phase.
	 *
	 * The tally is an argument *of* the sort step, not a step of its own. It used to be emitted as a child step, which
	 * nothing ever finished - so it reported a `spentTime` of `0` and was indistinguishable from a span that genuinely
	 * took no time, sprouting zero-width children in flame charts and nesting `EXECUTION_SORT_AND_SLICE` under itself.
	 *
	 * It also cannot be expressed as one of the typed numeric metrics: it is a count per
	 * {@link PositionResolution#strategy()} value, keyed by an enum of the sorter's own, and lifting it into a shared
	 * metric vocabulary would couple that vocabulary to the sorter's internals.
	 *
	 * @param queryContext the execution context whose current telemetry step is annotated
	 * @param tally        the accumulated per-strategy counts, or `null` when telemetry is off
	 */
	static void report(@Nonnull QueryExecutionContext queryContext, @Nullable int[] tally) {
		if (tally == null) {
			return;
		}
		final QueryTelemetry currentStep = queryContext.getQueryContext().getCurrentStep();
		if (currentStep != null) {
			currentStep.annotate(formatTally(tally));
		}
	}

	/**
	 * Formats a strategy `tally` into a single telemetry argument, listing only the strategies that were used at least
	 * once with their counts (e.g. `sortResolution=TREE_SPARSE_PROBEx3`).
	 *
	 * @param tally the accumulated per-strategy counts
	 * @return the formatted telemetry argument
	 */
	@Nonnull
	private static String formatTally(@Nonnull int[] tally) {
		final StringBuilder sb = new StringBuilder(64);
		sb.append("sortResolution=");
		boolean first = true;
		for (int i = 0; i < tally.length; i++) {
			if (tally[i] > 0) {
				if (!first) {
					sb.append(',');
				}
				sb.append(STRATEGIES[i].name()).append('x').append(tally[i]);
				first = false;
			}
		}
		return sb.toString();
	}

}
