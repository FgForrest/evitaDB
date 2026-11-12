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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.HistogramBehavior;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.cache.CacheSupervisor;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.extraResult.CacheableEvitaResponseExtraResultComputer;
import io.evitadb.core.query.extraResult.EvitaResponseExtraResultComputer;
import io.evitadb.core.query.extraResult.translator.histogram.producer.PriceHistogramComputer;
import io.evitadb.core.session.EvitaSession;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.test.TestTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.debug;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.require;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Verifies that the planning context hands formulas and extra result computers to the cache supervisor whenever
 * the analysis has a session and a target entity type, in an outer and in a nested context alike.
 */
@DisplayName("Query planning cache analysis routing")
@Tag(TestTags.ENGINE)
@Tag(TestTags.QUERY)
@Tag(TestTags.CACHE)
class QueryPlanningContextCacheAnalysisTest {

	/**
	 * Checks the supervisor calls and the returned objects in both an outer and a nested context, whichever planning
	 * policy the debug mode of the outer request selects.
	 *
	 * @param debugMode debug mode selecting the bitmap-favouring policy, or null for the default policy
	 */
	@ParameterizedTest
	@NullSource
	@EnumSource(value = DebugMode.class, names = {"VERIFY_POSSIBLE_CACHING_TREES", "PREFER_INDEX_SCAN"})
	@DisplayName("Formulas and computers reach the supervisor under every planning policy")
	void shouldDelegateToSupervisorUnderEveryPlanningPolicy(@Nullable DebugMode debugMode) {
		final EvitaSession session = mock(EvitaSession.class);
		final Formula formula = new ConstantFormula(new BaseBitmap(1, 2));
		final Formula analysedFormula = new ConstantFormula(new BaseBitmap(1));
		final PriceHistogramComputer computer = priceHistogramComputer(10);
		final PriceHistogramComputer analysedComputer = priceHistogramComputer(20);
		final RecordingCacheSupervisor supervisor = new RecordingCacheSupervisor(analysedFormula, analysedComputer);
		final QueryPlanningContext parent = context(null, session, request(debugMode), supervisor);
		final QueryPlanningContext nested = context(parent, session, request(null), supervisor);

		for (final QueryPlanningContext context : new QueryPlanningContext[]{parent, nested}) {
			assertSame(analysedFormula, context.analyse(formula));
			assertSame(analysedComputer, context.analyse(computer));
		}

		final AnalysisCall formulaCall = new AnalysisCall(session, "product", formula);
		final AnalysisCall computerCall = new AnalysisCall(session, "product", computer);
		assertEquals(List.of(formulaCall, computerCall, formulaCall, computerCall), supervisor.getCalls());
	}

	/**
	 * Checks that both seams return their input untouched and never consult the supervisor when the analysis
	 * lacks the session or the target entity type the supervisor is keyed by.
	 *
	 * @param missingSession true to omit the session, false to omit the target entity type
	 */
	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	@DisplayName("Analysis needs both a session and a target entity type")
	void shouldReturnInputWithoutSessionOrEntityType(boolean missingSession) {
		final RecordingCacheSupervisor supervisor = new RecordingCacheSupervisor(
			new ConstantFormula(new BaseBitmap(1)), priceHistogramComputer(20)
		);
		final EvitaRequest request;
		if (missingSession) {
			request = request(null);
		} else {
			request = new EvitaRequest(
				query(filterBy(entityPrimaryKeyInSet(1))), OffsetDateTime.now(), EntityReference.class, null
			);
		}
		final QueryPlanningContext context = new QueryPlanningContext(
			mock(Catalog.class), null, missingSession ? null : mock(EvitaSession.class), request,
			Map.of(), Map.of(), supervisor
		);
		final Formula formula = new ConstantFormula(new BaseBitmap(1, 2));
		final PriceHistogramComputer computer = priceHistogramComputer(10);

		assertSame(formula, context.analyse(formula));
		assertSame(computer, context.analyse(computer));
		assertTrue(supervisor.getCalls().isEmpty());
	}

	/**
	 * Creates a request over the `product` collection.
	 *
	 * @param debugMode debug mode the request enables, or null for none
	 * @return the request
	 */
	@Nonnull
	private static EvitaRequest request(@Nullable DebugMode debugMode) {
		final Query productQuery;
		if (debugMode == null) {
			productQuery = query(collection("product"));
		} else {
			productQuery = query(collection("product"), require(debug(debugMode)));
		}
		return new EvitaRequest(productQuery, OffsetDateTime.now(), EntityReference.class, null);
	}

	/**
	 * Creates a price histogram computer over a constant filtering formula. The context only passes the computer
	 * through, so no price records are needed.
	 *
	 * @param bucketCount requested bucket count, which also tells two computers apart in a failure message
	 * @return the computer
	 */
	@Nonnull
	private static PriceHistogramComputer priceHistogramComputer(int bucketCount) {
		return new PriceHistogramComputer(
			bucketCount, HistogramBehavior.STANDARD, 0, QueryPriceMode.WITH_TAX,
			new ConstantFormula(new BaseBitmap(1, 2)), null, List.of(), null
		);
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

	/**
	 * One analysis request the supervisor received.
	 *
	 * @param session session the analysis ran in
	 * @param entityType entity type the analysis was keyed by
	 * @param analysed formula or extra result computer handed over for analysis
	 */
	private record AnalysisCall(
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		@Nonnull Object analysed
	) {
	}

	/**
	 * Cache supervisor that records every analysis request and answers each with a fixed replacement, so a test can
	 * tell whether the context consulted it and whether the context returned its answer.
	 */
	private static final class RecordingCacheSupervisor implements CacheSupervisor {
		/**
		 * Answer to every formula analysis.
		 */
		private final Formula formulaReplacement;
		/**
		 * Answer to every extra result computer analysis.
		 */
		private final EvitaResponseExtraResultComputer<?> computerReplacement;
		/**
		 * Analysis requests in the order they arrived.
		 */
		private final List<AnalysisCall> calls = new ArrayList<>(4);

		/**
		 * @param formulaReplacement answer to every formula analysis
		 * @param computerReplacement answer to every extra result computer analysis
		 */
		RecordingCacheSupervisor(
			@Nonnull Formula formulaReplacement,
			@Nonnull EvitaResponseExtraResultComputer<?> computerReplacement
		) {
			this.formulaReplacement = formulaReplacement;
			this.computerReplacement = computerReplacement;
		}

		/**
		 * Returns the analysis requests received so far.
		 *
		 * @return the requests in arrival order
		 */
		@Nonnull
		List<AnalysisCall> getCalls() {
			return this.calls;
		}

		@SuppressWarnings("unchecked")
		@Nonnull
		@Override
		public <T extends Formula> T analyse(
			@Nonnull EvitaSessionContract evitaSession,
			@Nonnull String entityType,
			@Nonnull T filterFormula
		) {
			this.calls.add(new AnalysisCall(evitaSession, entityType, filterFormula));
			return (T) this.formulaReplacement;
		}

		@SuppressWarnings("unchecked")
		@Nonnull
		@Override
		public <U, T extends CacheableEvitaResponseExtraResultComputer<U>> EvitaResponseExtraResultComputer<U> analyse(
			@Nonnull EvitaSessionContract evitaSession,
			@Nonnull String entityType,
			@Nonnull T extraResultComputer
		) {
			this.calls.add(new AnalysisCall(evitaSession, entityType, extraResultComputer));
			return (EvitaResponseExtraResultComputer<U>) this.computerReplacement;
		}

		@Override
		public void close() {
			// nothing to release
		}
	}

}
