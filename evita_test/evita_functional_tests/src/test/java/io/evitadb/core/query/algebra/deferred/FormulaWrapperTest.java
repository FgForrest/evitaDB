/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.core.query.algebra.deferred;

import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.core.cache.NoCacheSupervisor;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.ENGINE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link FormulaWrapper}: the memoized computation with its nested-query telemetry, and the detached key
 * that separates transparent wrappers from custom computations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Formula wrapper")
@Tag(ENGINE)
@Tag(CACHE)
class FormulaWrapperTest {

	/**
	 * The wrapper computes its result once and reports a transparent computation as a nested query step.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Memoized computation")
	class MemoizedComputationTest {

		/** A transparent wrapper measures the delegate's computation as one nested query step. */
		@Test
		@DisplayName("Computes the delegate once inside one nested query step")
		void shouldComputeDelegateOnceInsideNestedQueryStep() {
			final FormulaWrapper wrapper = new FormulaWrapper(
				new ConstantFormula(new BaseBitmap(1, 2)), () -> "nested query");
			try (final RecordingContext context = new RecordingContext()) {
				wrapper.initialize(context);

				final Bitmap result = wrapper.get();

				assertArrayEquals(new int[]{1, 2}, result.getArray());
				assertSame(result, wrapper.get());
				assertEquals(List.of(QueryPhase.EXECUTION_FILTER_NESTED_QUERY), context.phases);
				assertEquals(List.of("nested query"), context.descriptions);
				assertEquals(1, context.popped);
			}
		}

		/** A custom computation decides the result itself and reports no step of its own. */
		@Test
		@DisplayName("Returns the custom computation result without a telemetry step")
		void shouldReturnCustomComputationResultWithoutStep() {
			final FormulaWrapper wrapper = FormulaWrapper.custom(
				new ConstantFormula(new BaseBitmap(1, 2)), (context, formula) -> new BaseBitmap(7));
			try (final RecordingContext context = new RecordingContext()) {
				wrapper.initialize(context);

				assertArrayEquals(new int[]{7}, wrapper.get().getArray());
				assertTrue(context.phases.isEmpty());
				assertEquals(0, context.popped);
			}
		}

		/** The computation needs the execution context, so it refuses to run before initialization. */
		@Test
		@DisplayName("Refuses to compute before it is initialized")
		void shouldRefuseComputationBeforeInitialization() {
			final FormulaWrapper wrapper = new FormulaWrapper(
				new ConstantFormula(new BaseBitmap(1)), () -> "nested query");

			assertThrows(GenericEvitaInternalError.class, wrapper::get);
		}
	}

	/**
	 * The detached key follows the delegate and binds a custom computation to its wrapper.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Detached key")
	class DetachedKeyTest {

		/** Wrappers carry their delegate identity. */
		@Test
		@DisplayName("Includes the wrapped formula")
		void shouldDistinguishDelegates() {
			final FormulaWrapper first = new FormulaWrapper(
				new ConstantFormula(new BaseBitmap(1)), () -> "nested query");
			final FormulaWrapper second = new FormulaWrapper(
				new ConstantFormula(new BaseBitmap(2)), () -> "nested query");
			assertNotEquals(first.getKey(), second.getKey());
			assertNotEquals(first.getKey().getHash64(), second.getKey().getHash64());
		}

		/** Telemetry descriptions have no effect on the computed bitmap. */
		@Test
		@DisplayName("Shares transparent wrappers despite different telemetry descriptions")
		void shouldIgnoreTelemetryDescription() {
			final FormulaWrapper first = new FormulaWrapper(
				new ConstantFormula(new BaseBitmap(1)), () -> "first");
			final FormulaWrapper second = new FormulaWrapper(
				new ConstantFormula(new BaseBitmap(1)), () -> "second");
			assertEquals(first.getKey(), second.getKey());
		}

		/** Arbitrary computation over the same delegate cannot be equated by that delegate. */
		@Test
		@DisplayName("Makes custom computations instance unique")
		void shouldBindCustomComputationToHolder() {
			final ConstantFormula delegate = new ConstantFormula(new BaseBitmap(1));
			final BiFunction<QueryExecutionContext, Formula, Bitmap> computation =
				(context, formula) -> formula.compute();
			final FormulaWrapper first = FormulaWrapper.custom(delegate, computation);
			final FormulaWrapper second = FormulaWrapper.custom(delegate, computation);
			assertTrue(first.getKey().isOpaque());
			assertTrue(first.getKey().isQueryLocal());
			assertSame(first.getKey(), first.getKey());
			assertNotEquals(first.getKey(), second.getKey());
			assertNotEquals(first.getKey().getHash64(), second.getKey().getHash64());
			assertNotEquals(first.getKey(), new FormulaWrapper(delegate, () -> "transparent").getKey());
		}
	}

	/**
	 * Execution context over a request-only planning context that records the telemetry steps pushed and popped
	 * through it; it never accesses a catalog, a session or an index.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class RecordingContext extends QueryExecutionContext {
		/** Phases of the pushed steps in push order. */
		private final List<QueryPhase> phases = new ArrayList<>(2);
		/** Descriptions of the pushed steps in push order. */
		private final List<String> descriptions = new ArrayList<>(2);
		/** Number of popped steps. */
		private int popped;

		/** Creates the context over a request that names a collection only. */
		private RecordingContext() {
			super(
				new QueryPlanningContext(
					null, null, null,
					new EvitaRequest(
						query(collection("product")), OffsetDateTime.parse("2026-01-01T00:00:00Z"),
						EntityReference.class, null
					),
					Map.of(), Map.of(), NoCacheSupervisor.INSTANCE
				),
				false, null, (type, value) -> value
			);
		}

		/** Records the phase and the resolved description of the pushed step. */
		@Override
		public void pushStep(@Nonnull QueryPhase phase, @Nonnull Supplier<String> messageSupplier) {
			this.phases.add(phase);
			this.descriptions.add(messageSupplier.get());
		}

		/** Counts the popped step. */
		@Override
		public void popStep() {
			this.popped++;
		}
	}
}
