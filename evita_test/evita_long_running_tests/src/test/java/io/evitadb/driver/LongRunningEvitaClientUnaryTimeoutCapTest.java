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

package io.evitadb.driver;

import com.google.protobuf.Empty;
import com.linecorp.armeria.client.retry.RetryConfig;
import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.grpc.GrpcService;
import io.evitadb.driver.config.ClientTimeoutOptions;
import io.evitadb.driver.config.ClientTlsOptions;
import io.evitadb.driver.config.EvitaClientConfiguration;
import io.evitadb.externalApi.grpc.generated.EvitaServiceGrpc.EvitaServiceImplBase;
import io.evitadb.externalApi.grpc.generated.GrpcCatalogNamesResponse;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.evitadb.test.TestTags.DRIVER;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.SLOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards the rule that a unary driver call is bounded by **evitaDB's own** configured timeout and never by
 * Armeria's global default. Before the fix this test guards, such a call was abandoned after roughly 15 s
 * however large a timeout the caller configured — so a legitimately slow write could not be waited out, and
 * {@link EvitaClient#executeWithExtendedTimeout} could not lift it.
 *
 * The three budgets are kept distinct on purpose — Armeria's default (15 s), the stub server's delay (25 s)
 * and the configured client timeout (45 s) — so that the interval the call actually lasts identifies which
 * one governed it.
 *
 * ## The defect
 *
 * {@code EvitaClient#createGrpcClientBuilder} decorates the **unary** channel with Armeria's
 * {@code RetryingClient}:
 *
 * <pre>
 * RetryingClient.builder(retryRule)
 *     .useRetryAfter(true)
 *     .newDecorator()
 * </pre>
 *
 * {@code RetryConfigBuilder}'s constructor seeds {@code responseTimeoutMillisForEachAttempt} from
 * {@link Flags#defaultResponseTimeoutMillis()} — 15 000 ms — rather than from `0`, and
 * {@code AbstractRetryingClient$State#responseTimeoutMillis()} then returns
 * {@code Math.min(perAttempt, remainingTotalBudget)}. The gRPC deadline the driver puts on every unary call
 * (`withDeadlineAfter`, see {@code EvitaClient#executeWithEvitaFutureService} and
 * {@code EvitaClientSession#executeWithBlockingEvitaSessionService}) is therefore floored at 15 s before it
 * ever reaches the scheduler that cancels the call.
 *
 * Two properties make this easy to miss:
 *
 * - **It does not depend on retries being enabled.** {@code EvitaClient#createRetryRule} never returns
 *   {@code null} — with retries off it still returns the always-safe {@code onUnprocessed()} rule — so the
 *   decorator, and with it the cap, is installed unconditionally. The configuration flag governs which
 *   conditions replay, not whether the decorator exists.
 * - **Streaming channels are exempt**, because they pass {@code retryRule == null} and get no decorator.
 *   That asymmetry is why large downloads can run for minutes while a unary write cannot.
 *
 * ## Why this test drives a stub server rather than the engine
 *
 * The defect is entirely client-side: it is decided by how the channel is decorated, before a byte reaches
 * the server. What the test needs from the far end is only *silence for longer than the cap*, and a real
 * engine cannot be made reliably slow for {@link #SERVER_DELAY_MILLIS} without tying the assertion to
 * machine speed — which is exactly how a timing test becomes flaky. The stub answers
 * {@code EvitaService/GetCatalogNames} after a fixed delay and nothing else, so the measured interval is the
 * client's own budget and nothing but.
 *
 * {@code GetCatalogNames} is chosen because it is the simplest unary call on the unary channel: no session,
 * no schema, no entity conversion — and it travels the same {@code executeWithEvitaFutureService} deadline
 * path as every other unary call.
 *
 * The stub deliberately implements nothing else — in particular not {@code EvitaManagementService/ServerStatus},
 * which {@link EvitaClient}'s constructor calls for its version check. Under test that check never reaches the
 * wire at all: the driver jar's manifest version is absent, so the constructor logs
 * {@code Client version `?` is not a valid semantic version} and returns before contacting the server.
 *
 * ## Calibration
 *
 * The fix passes {@code .responseTimeoutMillisForEachAttempt(0)} to the decorator above — making
 * {@code State#responseTimeoutMillis()} fall through to the remaining call budget so the gRPC deadline governs
 * again — and seeds the unary channel's own response timeout from the configured client timeout instead of
 * leaving {@code null}, so a call site that carries no deadline also lands on our value. With it, both tests
 * below go green. The regression guard is {@link #shouldNotAbandonUnaryCallBeforeConfiguredTimeout()};
 * {@link #shouldStillRequireExplicitPerAttemptOverride()} is a canary over the Armeria default the fix has to
 * override, and passes either way.
 *
 * Measured, on this branch. Unfixed: the call is abandoned after **15 148 ms** of a 300 s budget with
 * {@code GenericEvitaInternalError: DEADLINE_EXCEEDED: deadline exceeded after 15000000000ns} — the same
 * message a production loader logged while a full reindex was aborted mid-run. Fixed: the same call returns
 * normally after **25 137 ms**, having waited out {@link #SERVER_DELAY_MILLIS} inside its
 * {@link #CONFIGURED_TIMEOUT_SECONDS} budget.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
@DisplayName("Unary driver calls must honour the configured timeout, not Armeria's 15 s retry default")
@Tag(DRIVER)
@Tag(GRPC)
@Tag(MANAGEMENT)
@Tag(SLOW)
public class LongRunningEvitaClientUnaryTimeoutCapTest {
	/**
	 * Timeout the client is configured with — the value the driver must actually honour.
	 *
	 * Deliberately **not** a round multiple of anything Armeria uses, and deliberately not the 300 s a
	 * production loader would configure: the three budgets in play (15 s Armeria, 25 s server, 45 s ours) are
	 * far enough apart that the measured interval names which one ended the call, with no arithmetic.
	 */
	private static final long CONFIGURED_TIMEOUT_SECONDS = 45L;
	/**
	 * How long the stub server withholds its answer. Sits above the 15 s cap so the defect is reached, and well
	 * below {@link #CONFIGURED_TIMEOUT_SECONDS} so a driver honouring its own configuration returns normally
	 * with room to spare on a loaded box.
	 */
	private static final long SERVER_DELAY_MILLIS = 25_000L;
	/**
	 * The cap this test exists to catch — {@link Flags#defaultResponseTimeoutMillis()} at the time of writing.
	 * Read from the flag rather than hard-coded, so the test states the relationship rather than a number.
	 */
	private static final long ARMERIA_DEFAULT_RESPONSE_TIMEOUT_MILLIS = Flags.defaultResponseTimeoutMillis();
	/**
	 * Slack subtracted from {@link #SERVER_DELAY_MILLIS} when confirming the call really waited for the server.
	 * Guards only against a fixture that stopped withholding the answer - a loaded box makes the call longer,
	 * never shorter, so this cannot fail spuriously.
	 */
	private static final long COMPLETION_SLACK_MILLIS = 2_000L;

	/**
	 * Counts calls that reached the stub, so a failure can distinguish "the client gave up" from "the request
	 * never left".
	 */
	private static final AtomicInteger CALLS_RECEIVED = new AtomicInteger();

	private static Server server;

	@BeforeAll
	static void startStubServer() {
		server = Server.builder()
			.http(0)
			.service(
				GrpcService.builder()
					.addService(new SlowEvitaService())
					.build()
			)
			.build();
		server.start().join();
	}

	@AfterAll
	static void stopStubServer() {
		if (server != null) {
			server.stop().join();
		}
	}

	/**
	 * Explains, and keeps honest, the reason {@code EvitaClient#createGrpcClientBuilder} has to pass
	 * {@code responseTimeoutMillisForEachAttempt(0)} explicitly: Armeria seeds that per-attempt budget from its
	 * own global {@link Flags#defaultResponseTimeoutMillis()} rather than leaving it unbounded, and
	 * {@code AbstractRetryingClient$State#responseTimeoutMillis()} then returns
	 * {@code Math.min(perAttempt, remainingCallBudget)} — flooring every deadline the driver sets.
	 *
	 * **This is a canary, not the regression guard.** It asserts a property of the Armeria library, which the
	 * driver's fix cannot and should not change, so it passes on a broken tree as well as a fixed one — the
	 * guard against the defect itself is {@link #shouldNotAbandonUnaryCallBeforeConfiguredTimeout()}. What this
	 * one catches is the day an Armeria upgrade changes that default to {@code 0}, at which point the explicit
	 * override becomes redundant and both it and this test can go.
	 */
	@Test
	@DisplayName("Armeria still seeds a retrying client's per-attempt timeout, so the explicit override is still required")
	void shouldStillRequireExplicitPerAttemptOverride() {
		final RetryConfig<?> armeriaDefaults = RetryConfig.builder(
			// exactly the rule EvitaClient#createRetryRule returns when retries are disabled - the decorator,
			// and therefore this budget, is installed on the unary channel either way
			RetryRule.builder().onUnprocessed().thenBackoff()
		).build();

		assertEquals(
			ARMERIA_DEFAULT_RESPONSE_TIMEOUT_MILLIS,
			armeriaDefaults.responseTimeoutMillisForEachAttempt(),
			"""
				Armeria no longer seeds a retrying client's per-attempt response timeout from its global \
				default. If it now defaults to 0, the explicit `.responseTimeoutMillisForEachAttempt(0)` in \
				EvitaClient#createGrpcClientBuilder is redundant and this canary has done its job - remove both."""
		);
	}

	/**
	 * The same defect end to end: a call configured with {@link #CONFIGURED_TIMEOUT_SECONDS} must wait for the
	 * server, which answers well inside that budget. Today it is abandoned after roughly
	 * {@link #ARMERIA_DEFAULT_RESPONSE_TIMEOUT_MILLIS} instead, and the failure message quotes the measured
	 * interval so a reader sees the reproduction rather than only an assertion.
	 *
	 * Deliberately asserted in the direction of the **fix**, not of the bug: a test that passed while the cap
	 * was in force would go red the day the cap is removed, which inverts what a regression guard is for.
	 */
	@Test
	@DisplayName("a unary call configured for 300 s must not be abandoned after 15 s")
	void shouldNotAbandonUnaryCallBeforeConfiguredTimeout() {
		CALLS_RECEIVED.set(0);

		final EvitaClientConfiguration configuration = EvitaClientConfiguration.builder()
			.host("localhost")
			.port(server.activeLocalPort())
			.tls(ClientTlsOptions.builder().tlsEnabled(false).mtlsEnabled(false).build())
			.timeouts(
				ClientTimeoutOptions.builder()
					.timeout(CONFIGURED_TIMEOUT_SECONDS, TimeUnit.SECONDS)
					.build()
			)
			.build();

		try (final EvitaClient client = new EvitaClient(configuration)) {
			final long startedAt = System.nanoTime();
			Throwable failure = null;
			try {
				client.getCatalogNames();
			} catch (Throwable t) {
				failure = t;
			}
			final long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
			log.info("Unary call ended after {} ms (failure: {}).", elapsedMillis, failure != null);

			// distinguishes "the client gave up" from "the request never left" - without this a broken fixture
			// would read as the defect
			assertEquals(
				1, CALLS_RECEIVED.get(),
				"The request never reached the stub server, so the measured interval says nothing about the " +
					"client's call budget."
			);

			if (failure != null) {
				final long elapsed = elapsedMillis;
				final Throwable cause = failure;
				fail(
					"The call was abandoned after " + elapsed + " ms although it was configured for " +
						TimeUnit.SECONDS.toMillis(CONFIGURED_TIMEOUT_SECONDS) + " ms and the stub server was " +
						"holding the answer until " + SERVER_DELAY_MILLIS + " ms. That is Armeria's default " +
						"response timeout (" + ARMERIA_DEFAULT_RESPONSE_TIMEOUT_MILLIS + " ms), which " +
						"RetryConfigBuilder seeds responseTimeoutMillisForEachAttempt from - see " +
						"shouldStillRequireExplicitPerAttemptOverride for the Armeria default it inherits.\n" + stackTraceOf(cause)
				);
			}

			assertTrue(
				elapsedMillis >= SERVER_DELAY_MILLIS - COMPLETION_SLACK_MILLIS,
				() -> "The call returned after only " + elapsedMillis + " ms, but the stub server withholds its " +
					"answer for " + SERVER_DELAY_MILLIS + " ms - the fixture is not exercising the call budget."
			);
		}
	}

	/**
	 * Renders a throwable and its whole cause chain, so an assertion can look for the mechanism that ended the
	 * call without guessing which wrapper the driver applied.
	 *
	 * @param throwable throwable to render
	 * @return the printed stack trace including every cause
	 */
	@Nonnull
	private static String stackTraceOf(@Nonnull Throwable throwable) {
		final StringWriter writer = new StringWriter();
		throwable.printStackTrace(new PrintWriter(writer));
		return writer.toString();
	}

	/**
	 * Answers {@code GetCatalogNames} after {@link #SERVER_DELAY_MILLIS}, and implements nothing else. The
	 * delay is the entire point: it keeps the call alive long enough for the client's own budget to decide the
	 * outcome.
	 */
	private static class SlowEvitaService extends EvitaServiceImplBase {

		@Override
		public void getCatalogNames(Empty request, StreamObserver<GrpcCatalogNamesResponse> responseObserver) {
			CALLS_RECEIVED.incrementAndGet();
			try {
				Thread.sleep(SERVER_DELAY_MILLIS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				responseObserver.onError(e);
				return;
			}
			responseObserver.onNext(GrpcCatalogNamesResponse.newBuilder().build());
			responseObserver.onCompleted();
		}
	}
}
