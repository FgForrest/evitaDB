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

import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.core.cache.CacheSupervisor;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.session.EvitaSession;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndex;
import io.evitadb.test.TestTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the guarantee that query telemetry costs a query that did not ask for it *nothing* — not even the strings
 * describing the steps it would have recorded.
 *
 * The stack-empty check inside {@link QueryPlanningContext#pushStep} / {@link QueryPlanningContext#popStep} has always
 * made the *recording* free, but the arguments used to be built by the caller before that guard was ever reached. The
 * supplier overloads are what close that hole, and they only close it as long as every call site uses them — which is
 * why the eager `String` overloads no longer exist. This test asserts the property those overloads are there to
 * provide: with telemetry off, the supplier is never invoked.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Query telemetry costs nothing when it is not requested")
@Tag(TestTags.ENGINE)
@Tag(TestTags.QUERY)
class QueryPlanningContextTelemetryTest {

	/**
	 * The case that carries the guarantee: a context built without a telemetry root is what the overwhelming
	 * majority of production queries get, and none of them may pay for a description that will never be recorded.
	 * Each method here covers a different door into the recording API, because closing only one of them would leave
	 * the cost in place on every call site that uses the other.
	 */
	@Nested
	@DisplayName("Telemetry off")
	class TelemetryOffTest {

		/**
		 * Pins the push-time half: the description of a step that is about to start must stay unbuilt. This is the
		 * expensive direction in practice - push-time descriptions concatenate reference names, index names and
		 * costs, and they are produced deep inside loops.
		 */
		@Test
		@DisplayName("pushStep never resolves its message supplier")
		void shouldNotResolvePushMessageWhenTelemetryOff() {
			final QueryPlanningContext context = contextWithTelemetry(null);
			final CountingSupplier message = new CountingSupplier("Reference name: `brand`");

			context.pushStep(QueryPhase.FETCHING_REFERENCES, message);

			assertEquals(0, message.invocations(), "The step description must not be built when telemetry is off!");
			// not redundant with the invocation count: it catches a "fix" that seeds a telemetry root unconditionally,
			// which would make the supplier legitimately resolve and quietly reintroduce the cost on every query
			assertNull(context.getCurrentStep(), "No step may be recorded when telemetry is off!");
		}

		/**
		 * Pins the pop-time half, which is a genuinely separate hole: `popStep` is reached even for a step that was
		 * pushed without a description, so a guard that only covers `pushStep` would still let the outcome string be
		 * built on every closing call.
		 */
		@Test
		@DisplayName("popStep never resolves its message supplier")
		void shouldNotResolvePopMessageWhenTelemetryOff() {
			final QueryPlanningContext context = contextWithTelemetry(null);
			final CountingSupplier message = new CountingSupplier("Selected index: PRIMARY_KEY");

			context.pushStep(QueryPhase.PLANNING_FILTER);
			context.popStep(message);

			assertEquals(0, message.invocations(), "The step outcome must not be built when telemetry is off!");
		}

		/**
		 * Not redundant with the two above: the execution phase does not talk to the planning context directly but
		 * through {@link QueryExecutionContext}, which has its own `pushStep` / `popStep` pair. Those delegate today,
		 * and this pins that they keep delegating the supplier rather than resolving it on the way - which is where
		 * the fetching and sorting call sites, the hottest ones, would otherwise leak the cost back in.
		 */
		@Test
		@DisplayName("the execution context passes the laziness through unresolved")
		void shouldNotResolveMessageThroughExecutionContextWhenTelemetryOff() {
			final QueryExecutionContext executionContext = contextWithTelemetry(null).createExecutionContext();
			final CountingSupplier pushMessage = new CountingSupplier("Reference name: `brand`");
			final CountingSupplier popMessage = new CountingSupplier("done");

			executionContext.pushStep(QueryPhase.FETCHING_REFERENCES, pushMessage);
			executionContext.popStep(popMessage);

			assertEquals(0, pushMessage.invocations(), "The execution context must not resolve push-time arguments!");
			assertEquals(0, popMessage.invocations(), "The execution context must not resolve pop-time arguments!");
		}
	}

	/**
	 * The other half of the guarantee, and the reason the tests above are not satisfied by an implementation that
	 * simply never resolves anything: with a telemetry root present the supplier must be resolved, resolved
	 * **once**, and the resolved value must actually reach the recorded step. A second resolution would double the
	 * cost the lazy overloads exist to avoid, without failing any of the assertions in `TelemetryOffTest`.
	 */
	@Nested
	@DisplayName("Telemetry on")
	class TelemetryOnTest {

		/**
		 * Pins the push-time direction end to end - resolved exactly once, and landing on a step that carries the
		 * right phase and the resolved text as its argument.
		 */
		@Test
		@DisplayName("pushStep resolves its message supplier exactly once and records it")
		void shouldResolvePushMessageWhenTelemetryOn() {
			final QueryTelemetry root = QueryTelemetry.root(QueryPhase.OVERALL);
			final QueryPlanningContext context = contextWithTelemetry(root);
			final CountingSupplier message = new CountingSupplier("Reference name: `brand`");

			context.pushStep(QueryPhase.FETCHING_REFERENCES, message);

			assertEquals(1, message.invocations(), "The step description must be built exactly once!");
			final QueryTelemetry step = context.getCurrentStep();
			assertNotNull(step);
			assertEquals(QueryPhase.FETCHING_REFERENCES, step.getOperation());
			assertArrayEquals(new String[]{"Reference name: `brand`"}, step.getArguments());
		}

		/**
		 * Pins the pop-time direction, which lands somewhere else than the push-time one: by the time the supplier
		 * is resolved the step is no longer current, so the assertion has to reach for it through the root's
		 * children. That is exactly the wiring a refactor of the step stack would break silently.
		 */
		@Test
		@DisplayName("popStep resolves its message supplier exactly once and records it on the finished step")
		void shouldResolvePopMessageWhenTelemetryOn() {
			final QueryTelemetry root = QueryTelemetry.root(QueryPhase.OVERALL);
			final QueryPlanningContext context = contextWithTelemetry(root);
			final CountingSupplier message = new CountingSupplier("Selected index: PRIMARY_KEY");

			context.pushStep(QueryPhase.PLANNING_FILTER);
			context.popStep(message);

			assertEquals(1, message.invocations(), "The step outcome must be built exactly once!");
			assertEquals(1, root.getSteps().size());
			assertArrayEquals(
				new String[]{"Selected index: PRIMARY_KEY"}, root.getSteps().get(0).getArguments()
			);
		}
	}

	/**
	 * Builds a {@link QueryPlanningContext} seeded with `telemetry` — passing `null` reproduces the state of a query
	 * that did not ask for telemetry, which is how {@link Catalog} and
	 * {@link io.evitadb.core.collection.EntityCollection} construct the context for the overwhelming majority of
	 * production traffic.
	 *
	 * Everything the context needs beyond the telemetry root is mocked or empty — the recording decision is made
	 * from the root alone, so no catalog, session or index state can influence the outcome of these tests.
	 *
	 * @param telemetry root of the telemetry tree, or `null` for a query that did not request telemetry
	 * @return a planning context in exactly that state
	 */
	@Nonnull
	private static QueryPlanningContext contextWithTelemetry(@Nullable QueryTelemetry telemetry) {
		final Map<EntityIndexKey, EntityIndex> noIndexes = Map.of();
		return new QueryPlanningContext(
			null,
			Mockito.mock(Catalog.class),
			null,
			Mockito.mock(EvitaSession.class),
			Mockito.mock(EvitaRequest.class),
			telemetry,
			noIndexes,
			Map.of(),
			Mockito.mock(CacheSupervisor.class)
		);
	}

	/**
	 * A {@link Supplier} that records how many times it was asked for its value — the observable this test reads.
	 * Counting rather than merely flagging resolution is deliberate: it is what makes "resolved exactly once"
	 * assertable, and a supplier resolved twice is a real regression that a boolean would hide.
	 */
	private static class CountingSupplier implements Supplier<String> {
		/**
		 * The value handed out on every resolution — a realistic step description, so that a failure message shows
		 * what would have been recorded.
		 */
		private final String value;
		/**
		 * Number of times {@link #get()} was called. Atomic only for convenience; the tests are single threaded.
		 */
		private final AtomicInteger invocations = new AtomicInteger();

		/**
		 * @param value the step description this supplier would produce
		 */
		CountingSupplier(@Nonnull String value) {
			this.value = value;
		}

		/**
		 * Counts the call and returns the value.
		 *
		 * @return the step description this supplier was created with
		 */
		@Override
		public String get() {
			this.invocations.incrementAndGet();
			return this.value;
		}

		/**
		 * @return how many times this supplier has been resolved so far
		 */
		int invocations() {
			return this.invocations.get();
		}
	}
}
