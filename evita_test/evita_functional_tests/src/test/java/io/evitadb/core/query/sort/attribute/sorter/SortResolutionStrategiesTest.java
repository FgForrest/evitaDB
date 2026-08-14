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
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.ORDER;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Verifies the {@link SortResolutionStrategies} execution-time helpers shared by the merged sorters: mapping the
 * query's {@link DebugMode} overrides to a {@link ForcedSortResolution}, and recording the resolution strategies
 * actually used into the current {@link QueryTelemetry} step.
 *
 * Both helpers are exercised directly against mocked contexts rather than through a sorter, because the properties
 * at stake - what is *not* allocated, and where the tally is written - are invisible in a sorter's output.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(ENGINE)
@Tag(ORDER)
@DisplayName("Sort resolution strategy helpers (debug override mapping + telemetry)")
class SortResolutionStrategiesTest {

	/**
	 * Covers the debug overrides that let a test take the cost-based sorter selection out of the picture and pin one
	 * resolution family. The mapping is what the functional tests asserting "the same result whichever way it was
	 * sorted" stand on, so a silent change here would not fail those tests - it would make them stop testing the
	 * alternative they believe they are exercising.
	 */
	@Nested
	@DisplayName("Forced resolution mapping from DebugMode")
	class ForcedResolutionMappingTest {

		/**
		 * Pins one of the two mappings; separate from the array case because a transposed pair of `if` branches
		 * would still satisfy either test on its own.
		 */
		@Test
		@DisplayName("PREFER_TREE_SORT maps to the forced TREE resolution")
		void shouldMapPreferTreeSortToTree() {
			assertEquals(
				ForcedSortResolution.TREE,
				SortResolutionStrategies.resolveForcedResolution(contextWithDebugModes(DebugMode.PREFER_TREE_SORT))
			);
		}

		/**
		 * The counterpart of the tree mapping - together the two pin that each override reaches its own family.
		 */
		@Test
		@DisplayName("PREFER_PRESORT_ARRAYS maps to the forced ARRAY resolution")
		void shouldMapPreferPresortArraysToArray() {
			assertEquals(
				ForcedSortResolution.ARRAY,
				SortResolutionStrategies.resolveForcedResolution(contextWithDebugModes(DebugMode.PREFER_PRESORT_ARRAYS))
			);
		}

		/**
		 * The default path, and the one that matters most: `null` is not "no answer" here, it is the instruction to
		 * keep choosing by cost. A mapping that defaulted to a concrete family instead would silently pin every
		 * production query to one sorter without any test noticing.
		 */
		@Test
		@DisplayName("no sort override leaves the cost-based selector in charge (null)")
		void shouldReturnNullWhenNoSortOverride() {
			assertNull(SortResolutionStrategies.resolveForcedResolution(contextWithDebugModes()));
		}

		/**
		 * Setting both overrides is a contradiction, so the resolution is arbitrary - but it has to be *decided*
		 * rather than incidental. Pinning it here means the tie-break cannot flip unnoticed when the branches are
		 * reordered, which would otherwise change what a debug-mode functional test actually exercises.
		 */
		@Test
		@DisplayName("PREFER_TREE_SORT takes precedence over PREFER_PRESORT_ARRAYS when both are set")
		void shouldPreferTreeWhenBothOverridesSet() {
			assertEquals(
				ForcedSortResolution.TREE,
				SortResolutionStrategies.resolveForcedResolution(
					contextWithDebugModes(DebugMode.PREFER_TREE_SORT, DebugMode.PREFER_PRESORT_ARRAYS)
				)
			);
		}
	}

	/**
	 * Covers how the sorters report which resolution strategies they actually used. Two properties are at stake and
	 * neither is visible from the sorters themselves: that a query which did not ask for telemetry allocates nothing
	 * and executes no extra work, and that the tally is attached as an *argument of the sort step* rather than as a
	 * child step - the shape a previous implementation got wrong in a way that quietly corrupted flame charts.
	 */
	@Nested
	@DisplayName("Strategy telemetry tally + reporting")
	class StrategyTelemetryTest {

		/**
		 * Pins the "costs nothing" half at its source: with no current step there is no tally array, which is what
		 * lets every subsequent `tally` call be skipped by a null check instead of counting into a throwaway array.
		 */
		@Test
		@DisplayName("no tally is allocated when telemetry is not being collected")
		void shouldNotAllocateTallyWhenTelemetryOff() {
			// getCurrentStep() defaults to null on the mock -> telemetry off
			assertNull(SortResolutionStrategies.newStrategyTally(contextWithCurrentStep(null)));
		}

		/**
		 * The mirror case, and it also pins the tally's shape: one slot per {@link SortResolutionStrategy}, indexed
		 * by ordinal. Adding a strategy without widening the array would overflow it at the first tally of the new
		 * constant, which this catches immediately rather than in whichever sorter happens to use it first.
		 */
		@Test
		@DisplayName("a zeroed tally is allocated when telemetry is being collected")
		void shouldAllocateTallyWhenTelemetryOn() {
			final QueryTelemetry step = new QueryTelemetry(QueryPhase.EXECUTION_SORT_AND_SLICE);
			final int[] tally = SortResolutionStrategies.newStrategyTally(contextWithCurrentStep(step));
			assertEquals(SortResolutionStrategy.values().length, tally.length, "one slot per strategy");
			for (final int count : tally) {
				assertEquals(0, count, "tally starts zeroed");
			}
		}

		/**
		 * The end-to-end shape of the report: several tallies of two different strategies collapse into a single
		 * argument on the sort step, listing each used strategy once with its count. The exact string is asserted
		 * because it is what a developer reads in a profile - and because listing in ordinal order, with unused
		 * strategies omitted, is what keeps that string stable across runs of the same query.
		 */
		@Test
		@DisplayName("reporting annotates the sort step itself, listing the used strategies with their counts")
		void shouldReportUsedStrategiesAsArgumentOfTheSortStep() {
			final QueryTelemetry step = new QueryTelemetry(QueryPhase.EXECUTION_SORT_AND_SLICE);
			final QueryExecutionContext queryContext = contextWithCurrentStep(step);

			final int[] tally = SortResolutionStrategies.newStrategyTally(queryContext);
			SortResolutionStrategies.tally(tally, resolutionWith(SortResolutionStrategy.TREE_DENSE_WALK));
			SortResolutionStrategies.tally(tally, resolutionWith(SortResolutionStrategy.ARRAY_MERGE_WALK));
			SortResolutionStrategies.tally(tally, resolutionWith(SortResolutionStrategy.ARRAY_MERGE_WALK));
			SortResolutionStrategies.report(queryContext, tally);

			// strategies are listed in ordinal order (ARRAY_MERGE_WALK=0 before TREE_DENSE_WALK=2), unused ones omitted
			assertArrayEquals(
				new String[]{"sortResolution=ARRAY_MERGE_WALKx2,TREE_DENSE_WALKx1"}, step.getArguments()
			);
		}

		/**
		 * The regression this whole nested class exists for. It is not covered by the argument assertion above: an
		 * implementation could write the argument *and* still emit a child step, and every other test here would
		 * pass. The inline comment records what that child looked like in a profile.
		 */
		@Test
		@DisplayName("reporting never synthesises a child step")
		void shouldNotSynthesiseChildStepWhenReporting() {
			final QueryTelemetry step = new QueryTelemetry(QueryPhase.EXECUTION_SORT_AND_SLICE);
			final QueryExecutionContext queryContext = contextWithCurrentStep(step);

			final int[] tally = SortResolutionStrategies.newStrategyTally(queryContext);
			SortResolutionStrategies.tally(tally, resolutionWith(SortResolutionStrategy.TREE_DENSE_WALK));
			SortResolutionStrategies.report(queryContext, tally);

			// a child was how this used to be emitted; nothing ever finished it, so it reported a spentTime of 0 and
			// was indistinguishable from a span that genuinely took no time
			assertTrue(step.getSteps().isEmpty(), "the tally is an argument of the sort step, not a step of its own");
		}

		/**
		 * Pins that reporting *appends*. The distinction is invisible in the tests above, which all start from a
		 * step with no arguments, yet it is the whole reason reporting annotates instead of finishing the step:
		 * replacing the arguments would drop the description the step was pushed with, and for the nested-query
		 * sorter that description is the only thing identifying which nested sort the step belongs to.
		 */
		@Test
		@DisplayName("reporting preserves a description the step was pushed with")
		void shouldAppendTallyToExistingDescription() {
			// this is the NestedContextSorter shape - the step is described at push time and annotated later
			final QueryTelemetry step = new QueryTelemetry(
				QueryPhase.EXECUTION_SORT_AND_SLICE, "Nested query sort"
			);
			final QueryExecutionContext queryContext = contextWithCurrentStep(step);

			final int[] tally = SortResolutionStrategies.newStrategyTally(queryContext);
			SortResolutionStrategies.tally(tally, resolutionWith(SortResolutionStrategy.TREE_DENSE_WALK));
			SortResolutionStrategies.report(queryContext, tally);

			assertArrayEquals(
				new String[]{"Nested query sort", "sortResolution=TREE_DENSE_WALKx1"}, step.getArguments()
			);
		}

		/**
		 * Reporting has to tolerate a `null` tally rather than assume its caller checked, because the sorters call
		 * it unconditionally on the way out. Asserting on a step that *is* present makes this sharper than a
		 * no-exception check: nothing may be written even when there would be somewhere to write it.
		 */
		@Test
		@DisplayName("reporting is a no-op when the tally is null (telemetry off)")
		void shouldNotReportWhenTallyNull() {
			final QueryTelemetry step = new QueryTelemetry(QueryPhase.EXECUTION_SORT_AND_SLICE);
			SortResolutionStrategies.report(contextWithCurrentStep(step), null);
			assertTrue(step.getSteps().isEmpty(), "no marker is added when telemetry is off");
			assertEquals(0, step.getArguments().length, "no argument is added when telemetry is off");
		}

		/**
		 * The same tolerance on the counting side, which is the one that runs per resolved position rather than
		 * once per sort - it is called from the hottest loop in the sorters, so the null check living inside
		 * `tally` instead of at every call site is deliberate and has to keep working.
		 */
		@Test
		@DisplayName("tallying is a no-op when the tally is null (telemetry off)")
		void shouldNotTallyWhenTallyNull() {
			// must not throw when telemetry is off
			SortResolutionStrategies.tally(null, resolutionWith(SortResolutionStrategy.TREE_SPARSE_PROBE));
		}
	}

	/**
	 * Builds a {@link QueryExecutionContext} whose planning context reports the supplied debug modes as enabled (all
	 * others disabled) - the input to {@link SortResolutionStrategies#resolveForcedResolution}.
	 *
	 * @param enabledModes debug modes to report as enabled; pass none for a query with no overrides at all
	 * @return an execution context standing in for a query running under exactly those debug modes
	 */
	@Nonnull
	private static QueryExecutionContext contextWithDebugModes(@Nonnull DebugMode... enabledModes) {
		final QueryPlanningContext planningContext = Mockito.mock(QueryPlanningContext.class);
		// unstubbed isDebugModeEnabled defaults to false; enable only the supplied modes
		for (final DebugMode mode : enabledModes) {
			when(planningContext.isDebugModeEnabled(mode)).thenReturn(true);
		}
		final QueryExecutionContext queryContext = Mockito.mock(QueryExecutionContext.class);
		when(queryContext.getQueryContext()).thenReturn(planningContext);
		return queryContext;
	}

	/**
	 * Builds a {@link QueryExecutionContext} whose planning context returns `currentStep` from `getCurrentStep()` - the
	 * telemetry-collection probe used by {@link SortResolutionStrategies#newStrategyTally} / `report`.
	 *
	 * A real {@link QueryTelemetry} is handed over rather than a mock, because the assertions read the arguments and
	 * child steps the production code writes into it - mocking those away would leave nothing to assert on.
	 *
	 * @param currentStep the telemetry step to report as current, or `null` for a query without telemetry
	 * @return an execution context in exactly that telemetry state
	 */
	@Nonnull
	private static QueryExecutionContext contextWithCurrentStep(@Nullable QueryTelemetry currentStep) {
		final QueryPlanningContext planningContext = Mockito.mock(QueryPlanningContext.class);
		when(planningContext.getCurrentStep()).thenReturn(currentStep);
		final QueryExecutionContext queryContext = Mockito.mock(QueryExecutionContext.class);
		when(queryContext.getQueryContext()).thenReturn(planningContext);
		return queryContext;
	}

	/**
	 * A {@link PositionResolution} carrying only the given `strategy` (empty mask / not-found), for tally assertions.
	 * The bitmaps and the position are deliberately empty: tallying reads nothing but the strategy, so giving it
	 * realistic sort data would only invite the reader to think the rest matters.
	 *
	 * @param strategy the resolution strategy the returned resolution reports
	 * @return a minimal resolution usable as input to {@link SortResolutionStrategies#tally}
	 */
	@Nonnull
	private static PositionResolution resolutionWith(@Nonnull SortResolutionStrategy strategy) {
		final PersistentRoaringBitmap empty = RoaringBitmapBackedBitmap.getRoaringBitmap(EmptyBitmap.INSTANCE);
		return new PositionResolution(empty, empty, 0, strategy);
	}

}
