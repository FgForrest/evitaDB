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

package io.evitadb.core.query;

import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.core.cache.CacheSupervisor;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.extraResult.translator.histogram.producer.PriceHistogramComputer;
import io.evitadb.core.session.EvitaSession;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.test.TestTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies supervisor analysis routing for formulas and extra result computers, including inherited debug policies.
 */
@DisplayName("Query planning cache analysis routing")
@Tag(TestTags.ENGINE)
@Tag(TestTags.QUERY)
@Tag(TestTags.CACHE)
class QueryPlanningContextCacheAnalysisTest {

	/**
	 * Checks the supervisor calls and returned objects in both a parent and a nested context.
	 *
	 * @param debugMode bitmap planning mode, or null for default planning
	 */
	@ParameterizedTest
	@NullSource
	@EnumSource(value = DebugMode.class, names = {"VERIFY_POSSIBLE_CACHING_TREES", "PREFER_INDEX_SCAN"})
	@DisplayName("Formula analysis reaches the supervisor while bitmap planning bypasses computer analysis")
	void shouldPreserveSupervisorRouting(@Nullable DebugMode debugMode) {
		final EvitaSession session = mock(EvitaSession.class);
		final CacheSupervisor supervisor = mock(CacheSupervisor.class);
		final EvitaRequest request = mock(EvitaRequest.class);
		when(request.getEntityType()).thenReturn("product");
		if (debugMode != null) {
			when(request.isDebugModeEnabled(debugMode)).thenReturn(true);
		}
		final QueryPlanningContext parent = context(null, session, request, supervisor);
		final EvitaRequest nestedRequest = mock(EvitaRequest.class);
		when(nestedRequest.getEntityType()).thenReturn("product");
		final QueryPlanningContext nested = context(parent, session, nestedRequest, supervisor);
		final Formula formula = mock(Formula.class);
		final Formula analysedFormula = mock(Formula.class);
		final PriceHistogramComputer computer = mock(PriceHistogramComputer.class);
		final PriceHistogramComputer analysedComputer = mock(PriceHistogramComputer.class);
		when(supervisor.analyse(session, "product", formula)).thenReturn(analysedFormula);
		if (debugMode == null) {
			when(supervisor.analyse(session, "product", computer)).thenReturn(analysedComputer);
		}

		for (final QueryPlanningContext context : new QueryPlanningContext[]{parent, nested}) {
			assertSame(analysedFormula, context.analyse(formula));
			assertSame(debugMode == null ? analysedComputer : computer, context.analyse(computer));
		}

		verify(supervisor, times(2)).analyse(session, "product", formula);
		verify(supervisor, debugMode == null ? times(2) : never()).analyse(session, "product", computer);
		verifyNoMoreInteractions(supervisor);
	}

	/**
	 * Checks that both seams bypass the supervisor when the analysis context is incomplete.
	 *
	 * @param missingSession true to omit the session, false to omit the target entity type
	 */
	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	@DisplayName("Analysis needs both a session and a target entity type")
	void shouldBypassSupervisorWithoutSessionOrEntityType(boolean missingSession) {
		final CacheSupervisor supervisor = mock(CacheSupervisor.class);
		final EvitaRequest request = mock(EvitaRequest.class);
		if (missingSession) {
			when(request.getEntityType()).thenReturn("product");
		}
		final QueryPlanningContext context = new QueryPlanningContext(
			mock(Catalog.class), null, missingSession ? null : mock(EvitaSession.class), request,
			Map.of(), Map.of(), supervisor
		);
		final Formula formula = mock(Formula.class);
		final PriceHistogramComputer computer = mock(PriceHistogramComputer.class);

		assertSame(formula, context.analyse(formula));
		assertSame(computer, context.analyse(computer));
		verifyNoInteractions(supervisor);
	}

	/**
	 * Creates a context with no telemetry or indexes and an optional parent whose planning policy it inherits.
	 *
	 * @param parent enclosing context, or null for a top-level context
	 * @param session session passed to the supervisor
	 * @param request query request carrying the target entity type and debug modes
	 * @param supervisor supervisor whose analysis calls are observed
	 * @return the planning context
	 */
	@Nonnull
	private static QueryPlanningContext context(
		@Nullable QueryPlanningContext parent,
		@Nonnull EvitaSession session,
		@Nonnull EvitaRequest request,
		@Nonnull CacheSupervisor supervisor
	) {
		final Map<EntityIndexKey, EntityIndex> indexes = Map.of();
		return new QueryPlanningContext(
			parent, mock(Catalog.class), null, session, request, null, indexes, Map.of(), supervisor
		);
	}

}
