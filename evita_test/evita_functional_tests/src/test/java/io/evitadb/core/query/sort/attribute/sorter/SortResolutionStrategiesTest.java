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
 * Verifies the {@link SortResolutionStrategies} execution-time helpers shared by the merged sorters: mapping the query's
 * {@link DebugMode} overrides to a {@link ForcedSortResolution}, and recording the resolution strategies actually used
 * into the current {@link QueryTelemetry} step.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(ENGINE)
@Tag(ORDER)
@DisplayName("Sort resolution strategy helpers (debug override mapping + telemetry)")
class SortResolutionStrategiesTest {

	@Nested
	@DisplayName("Forced resolution mapping from DebugMode")
	class ForcedResolutionMappingTest {

		@Test
		@DisplayName("PREFER_TREE_SORT maps to the forced TREE resolution")
		void shouldMapPreferTreeSortToTree() {
			assertEquals(
				ForcedSortResolution.TREE,
				SortResolutionStrategies.resolveForcedResolution(contextWithDebugModes(DebugMode.PREFER_TREE_SORT))
			);
		}

		@Test
		@DisplayName("PREFER_PRESORT_ARRAYS maps to the forced ARRAY resolution")
		void shouldMapPreferPresortArraysToArray() {
			assertEquals(
				ForcedSortResolution.ARRAY,
				SortResolutionStrategies.resolveForcedResolution(contextWithDebugModes(DebugMode.PREFER_PRESORT_ARRAYS))
			);
		}

		@Test
		@DisplayName("no sort override leaves the cost-based selector in charge (null)")
		void shouldReturnNullWhenNoSortOverride() {
			assertNull(SortResolutionStrategies.resolveForcedResolution(contextWithDebugModes()));
		}

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

	@Nested
	@DisplayName("Strategy telemetry tally + reporting")
	class StrategyTelemetryTest {

		@Test
		@DisplayName("no tally is allocated when telemetry is not being collected")
		void shouldNotAllocateTallyWhenTelemetryOff() {
			// getCurrentStep() defaults to null on the mock -> telemetry off
			assertNull(SortResolutionStrategies.newStrategyTally(contextWithCurrentStep(null)));
		}

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

		@Test
		@DisplayName("reporting adds a single labelled child listing the used strategies with their counts")
		void shouldReportUsedStrategiesAsChildStep() {
			final QueryTelemetry step = new QueryTelemetry(QueryPhase.EXECUTION_SORT_AND_SLICE);
			final QueryExecutionContext queryContext = contextWithCurrentStep(step);

			final int[] tally = SortResolutionStrategies.newStrategyTally(queryContext);
			SortResolutionStrategies.tally(tally, resolutionWith(SortResolutionStrategy.TREE_DENSE_WALK));
			SortResolutionStrategies.tally(tally, resolutionWith(SortResolutionStrategy.ARRAY_MERGE_WALK));
			SortResolutionStrategies.tally(tally, resolutionWith(SortResolutionStrategy.ARRAY_MERGE_WALK));
			SortResolutionStrategies.report(queryContext, tally);

			assertEquals(1, step.getSteps().size(), "exactly one strategy marker is added");
			final QueryTelemetry marker = step.getSteps().get(0);
			assertEquals(QueryPhase.EXECUTION_SORT_AND_SLICE, marker.getOperation());
			// strategies are listed in ordinal order (ARRAY_MERGE_WALK=0 before TREE_DENSE_WALK=2), unused ones omitted
			assertArrayEquals(
				new String[]{"sortResolution=ARRAY_MERGE_WALKx2,TREE_DENSE_WALKx1"}, marker.getArguments()
			);
		}

		@Test
		@DisplayName("reporting is a no-op when the tally is null (telemetry off)")
		void shouldNotReportWhenTallyNull() {
			final QueryTelemetry step = new QueryTelemetry(QueryPhase.EXECUTION_SORT_AND_SLICE);
			SortResolutionStrategies.report(contextWithCurrentStep(step), null);
			assertTrue(step.getSteps().isEmpty(), "no marker is added when telemetry is off");
		}

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
	 */
	@Nonnull
	private static PositionResolution resolutionWith(@Nonnull SortResolutionStrategy strategy) {
		final PersistentRoaringBitmap empty = RoaringBitmapBackedBitmap.getRoaringBitmap(EmptyBitmap.INSTANCE);
		return new PositionResolution(empty, empty, 0, strategy);
	}

}
