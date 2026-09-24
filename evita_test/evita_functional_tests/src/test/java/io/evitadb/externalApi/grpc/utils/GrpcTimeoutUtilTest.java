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

package io.evitadb.externalApi.grpc.utils;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.server.ServiceRequestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.time.Duration;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.STREAM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down how a streaming call's Armeria deadline must be rolled forward, and - more importantly -
 * how it must **not** be.
 *
 * Every long-lived producer in this module re-arms the request timeout per message, because Armeria's
 * request timeout bounds the whole request while a stream needs a bound on *silence*. The subtlety that
 * cost six call sites is where the budget for that re-arm comes from.
 * {@link ServiceRequestContext#requestTimeoutMillis()} reads like "the configured timeout" and is not:
 * Armeria stores the timeout relative to the request's start, and {@link TimeoutMode#SET_FROM_NOW}
 * writes `elapsed + newTimeout` into that same field. Reading it back inside the loop therefore feeds
 * each re-arm a budget that already contains every previous one.
 *
 * The second test below is a **characterisation test of Armeria**, deliberately. The fix depends on that
 * behaviour, it is not part of Armeria's documented contract, and an upgrade could change it - in which
 * case this test fails and says so, instead of the ratchet quietly coming back.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Streaming request timeouts must be re-armed from a captured budget")
@Tag(GRPC)
@Tag(EXTERNAL_API)
@Tag(STREAM)
class GrpcTimeoutUtilTest {
	/**
	 * Budget each context under test starts with. Small enough to keep the test quick, large enough that
	 * the two strategies below separate by far more than scheduling noise.
	 */
	private static final long CONFIGURED_TIMEOUT_MILLIS = 1_000L;
	/**
	 * Pause between successive re-arms. This is a *detection widener*, not a poll: the compounding the
	 * test is looking for is proportional to elapsed time, so a slower machine makes the two strategies
	 * diverge **further**. It cannot produce a false failure.
	 */
	private static final long REARM_SPACING_MILLIS = 120L;
	/**
	 * How many times each strategy re-arms. Three is enough for the ratchet to overtake the correct
	 * strategy by more than a spacing interval, while keeping the whole test under a second.
	 */
	private static final int REARM_COUNT = 3;
	/**
	 * Stand-in for `api.endpoints.gRPC.streamingRequestTimeoutInMillis`. Deliberately unlike
	 * {@link #CONFIGURED_TIMEOUT_MILLIS} so a test cannot pass by the two being confused.
	 */
	private static final long STREAMING_BUDGET_MILLIS = 300_000L;
	/**
	 * Tolerance absorbing millisecond truncation and the gap between the last re-arm and the reading. Far
	 * smaller than the ~360 ms by which a compounding implementation overshoots, so it costs the
	 * assertion no teeth.
	 */
	private static final long CLOCK_SLACK_MILLIS = 50L;

	/**
	 * Builds a service request context carrying a configured request timeout, the way a real gRPC call
	 * with a `grpc-timeout` header arrives.
	 *
	 * @return context whose request timeout is {@link #CONFIGURED_TIMEOUT_MILLIS}
	 */
	@Nonnull
	private static ServiceRequestContext contextWithConfiguredTimeout() {
		final ServiceRequestContext serviceContext = ServiceRequestContext
			.builder(HttpRequest.of(HttpMethod.POST, "/test"))
			.build();
		// SET_FROM_START is how a budget is established, as opposed to rolled forward
		serviceContext.setRequestTimeout(
			TimeoutMode.SET_FROM_START, Duration.ofMillis(CONFIGURED_TIMEOUT_MILLIS)
		);
		return serviceContext;
	}

	@Test
	@DisplayName("Capturing before the first message yields the configured budget")
	void shouldCaptureConfiguredRequestTimeout() {
		final ServiceRequestContext serviceContext = contextWithConfiguredTimeout();

		assertEquals(
			CONFIGURED_TIMEOUT_MILLIS,
			GrpcTimeoutUtil.captureRequestTimeoutMillis(serviceContext),
			"Captured before any re-arm, the budget must be the one the call was configured with."
		);
	}

	@Test
	@DisplayName("A disabled request timeout is left disabled rather than rejected")
	void shouldSkipReArmingWhenRequestTimeoutIsDisabled() {
		// no timeout configured at all - the normal state for a client that sends no `grpc-timeout`
		final ServiceRequestContext serviceContext = ServiceRequestContext
			.builder(HttpRequest.of(HttpMethod.POST, "/test"))
			.build();
		serviceContext.clearRequestTimeout();

		final long captured = GrpcTimeoutUtil.captureRequestTimeoutMillis(serviceContext);
		assertEquals(0L, captured, "A disabled request timeout must read as zero.");

		// `SET_FROM_NOW` is the one timeout mode that rejects a zero duration outright, so re-arming has
		// to be skipped rather than attempted - this call must not throw
		GrpcTimeoutUtil.reArmRequestTimeoutIfEnabled(serviceContext, captured);

		assertEquals(
			0L, serviceContext.requestTimeoutMillis(),
			"Re-arming a disabled timeout must leave it disabled."
		);
	}

	@Test
	@DisplayName("A streaming call is budgeted from the streaming timeout, not the call's own request timeout")
	void shouldResolveStreamingBudgetIndependentlyOfTheCallsOwnTimeout() {
		final ServiceRequestContext serviceContext = contextWithConfiguredTimeout();

		// The call asked for 1 s; a stream gets the deployment's streaming budget instead. Substituting
		// is safe because a gRPC deadline is enforced by the *client* - the caller still sees its own
		// deadline honoured, and only a live-but-silent peer occupies the server for longer.
		assertEquals(
			STREAMING_BUDGET_MILLIS,
			GrpcTimeoutUtil.resolveStreamingBudgetMillis(serviceContext, STREAMING_BUDGET_MILLIS),
			"A call that has a deadline must be re-armed from the streaming budget, not its own."
		);
	}

	@Test
	@DisplayName("A call with no deadline is not given one by the streaming budget")
	void shouldLeaveADisabledTimeoutDisabledWhenResolvingTheStreamingBudget() {
		final ServiceRequestContext serviceContext = ServiceRequestContext
			.builder(HttpRequest.of(HttpMethod.POST, "/test"))
			.build();
		serviceContext.clearRequestTimeout();

		// Whether a call has a deadline stays the caller's decision; only its *value* is substituted.
		// Returning the budget here would silently impose a 5-minute deadline on an open-ended stream
		// (a CDC subscription, say) that deliberately asked for none.
		assertEquals(
			0L,
			GrpcTimeoutUtil.resolveStreamingBudgetMillis(serviceContext, STREAMING_BUDGET_MILLIS),
			"A deliberately disabled deadline must stay disabled."
		);
	}

	@Test
	@DisplayName("Re-arming from a captured budget holds the horizon; re-reading the context ratchets it")
	void shouldNotCompoundTheBudgetWhenReArmingFromCapturedValue() throws InterruptedException {
		// the stopwatch starts before the contexts do: each context's own clock begins when it is built,
		// so measuring from here keeps `elapsedMillis` an upper bound on the scheduler's notion of elapsed
		final long startNanos = System.nanoTime();
		final ServiceRequestContext capturedContext = contextWithConfiguredTimeout();
		final ServiceRequestContext ratchetingContext = contextWithConfiguredTimeout();
		// captured once, before the first re-arm - this is what every producing loop must do
		final long capturedBudget = GrpcTimeoutUtil.captureRequestTimeoutMillis(capturedContext);

		for (int i = 0; i < REARM_COUNT; i++) {
			Thread.sleep(REARM_SPACING_MILLIS);
			GrpcTimeoutUtil.reArmRequestTimeoutIfEnabled(capturedContext, capturedBudget);
			// the mistake, reproduced side by side: the budget is read back from the context, so it
			// already contains everything the previous re-arms added to it
			GrpcTimeoutUtil.reArmRequestTimeoutIfEnabled(
				ratchetingContext, ratchetingContext.requestTimeoutMillis()
			);
		}
		final long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

		final long capturedHorizon = capturedContext.requestTimeoutMillis();
		final long ratchetingHorizon = ratchetingContext.requestTimeoutMillis();

		// Re-armed from a constant, the deadline sits one budget past the last message - which is exactly
		// what "the client has `timeout` to send the next one" means. Stated relative to the measured
		// elapsed time so a loaded machine cannot fail it.
		assertTrue(
			capturedHorizon <= elapsedMillis + CONFIGURED_TIMEOUT_MILLIS + CLOCK_SLACK_MILLIS,
			"Re-arming from a captured budget must leave the horizon at last-message + budget, but it " +
				"was " + capturedHorizon + " ms after " + elapsedMillis + " ms elapsed."
		);

		// Re-armed from the context, each step adds the elapsed time to a budget that already included
		// it. The gap grows with every message, so a stalled client is never caught.
		assertTrue(
			ratchetingHorizon > capturedHorizon + REARM_SPACING_MILLIS,
			"Reading the budget back from the context must be shown to compound it - the ratcheting " +
				"horizon was " + ratchetingHorizon + " ms against " + capturedHorizon + " ms for the " +
				"captured one. If these now agree, Armeria changed `requestTimeoutMillis()` semantics " +
				"and `GrpcTimeoutUtil#captureRequestTimeoutMillis` needs revisiting."
		);
	}

}
