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
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.externalApi.grpc.exception.StalledGrpcStreamException;
import io.grpc.stub.ServerCallStreamObserver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.STREAM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic guard for {@link GrpcOutboundGate}, the back-pressure gate every server-streaming RPC
 * paces itself with.
 *
 * This is the *only* fast-loop coverage the gate has, and that is a deliberate consequence of how the
 * engine is wired for tests: the default test engine collapses the request pool into a direct executor,
 * so a service method's hand-off lands back on the transport event loop, where the gate detects the
 * self-deadlock and runs ungated on purpose. Every functional test that streams therefore takes the
 * *ungated* branch, and the gated one is only reached by an end-to-end test that opts into real thread
 * pools - which lives in the long-running module behind `@Tag(SLOW)`. Driving the gate directly against
 * a fake observer is what keeps its contract checked on every build.
 *
 * The behaviours pinned here are the ones whose absence is invisible until production:
 *
 * - waiting must wake on the on-ready callback, or a slow client hangs forever rather than being paced;
 * - cancellation must be answered **without parking**. Armeria increments its pending-message counter
 *   in `sendMessage` and only unwinds it when the payload is genuinely consumed, which never happens
 *   for a cancelled call - so readiness stays false forever, and a gate that consulted it first would
 *   pin a request-executor thread for the full stall timeout on every client that simply pressed
 *   cancel;
 * - a client that keeps the stream open but stops reading must eventually be abandoned, or that same
 *   thread is pinned indefinitely with no transport event ever coming to release it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("gRPC outbound gate paces a producer against transport readiness")
@Tag(GRPC)
@Tag(EXTERNAL_API)
@Tag(STREAM)
class GrpcOutboundGateTest {
	/**
	 * Stall timeout used wherever the test must prove the gate does *not* run into it. Long enough that
	 * reaching it is unmistakably a failure rather than a slow machine.
	 */
	private static final long GENEROUS_STALL_TIMEOUT_MILLIS = 60_000L;
	/**
	 * Smallest grace beyond the stall timeout the gate's deadline re-arm must leave. Set below the
	 * gate's actual `STALL_DETECTION_GRACE_MILLIS` on purpose - the test pins that a grace *exists*,
	 * which is what stops the gate losing the stall race to Armeria, without freezing its value.
	 */
	private static final long MINIMUM_EXPECTED_GRACE_MILLIS = 4_000L;

	/**
	 * Starts a daemon thread that calls {@link GrpcOutboundGate#awaitWritable()} and records what came
	 * back - a returned verdict, or the exception it threw.
	 *
	 * @param gate     gate to wait on
	 * @param verdict  receives `TRUE`/`FALSE` as returned by the gate
	 * @param failure  receives the exception, if the call threw one
	 * @param finished counted down once the call has returned or thrown
	 * @return the started thread, so the caller can join it
	 */
	@Nonnull
	private static Thread awaitInBackground(
		@Nonnull GrpcOutboundGate gate,
		@Nonnull AtomicReference<Boolean> verdict,
		@Nonnull AtomicReference<Throwable> failure,
		@Nonnull CountDownLatch finished
	) {
		final Thread producer = new Thread(
			() -> {
				try {
					verdict.set(gate.awaitWritable());
				} catch (Throwable t) {
					failure.set(t);
				} finally {
					finished.countDown();
				}
			},
			"grpc-outbound-gate-test-producer"
		);
		producer.setDaemon(true);
		producer.start();
		return producer;
	}

	@Test
	@DisplayName("Granting a message re-arms the call deadline to the stall budget, not the call's own")
	void shouldReArmTheCallDeadlineToTheStallBudgetOnEveryGrant() {
		final long callTimeoutMillis = 1_000L;
		final long stallTimeoutMillis = 30_000L;
		final ServiceRequestContext serviceContext = ServiceRequestContext
			.builder(HttpRequest.of(HttpMethod.POST, "/test"))
			.build();
		serviceContext.setRequestTimeout(TimeoutMode.SET_FROM_START, Duration.ofMillis(callTimeoutMillis));

		final FakeServerCallObserver observer = new FakeServerCallObserver(true);
		// the gate reads the context at attach time, exactly as a service method would
		try (final SafeCloseable ignored = serviceContext.push()) {
			final GrpcOutboundGate gate = GrpcOutboundGate.attach(observer, "testMethod", null, stallTimeoutMillis);
			assertTrue(gate.awaitWritable(), "A ready transport must grant immediately.");
		}

		// The horizon must be the *streaming* budget, not the 1 s the call arrived with. Asserted as a
		// lower bound because Armeria stores the timeout relative to request start, so elapsed time only
		// pushes it further out - see `GrpcTimeoutUtilTest`.
		assertTrue(
			serviceContext.requestTimeoutMillis() >= stallTimeoutMillis,
			"A granted message must be re-armed to at least the stall budget, but the horizon was " +
				serviceContext.requestTimeoutMillis() + " ms against a " + stallTimeoutMillis +
				" ms stall timeout. Re-arming from the call's own budget is the defect this guards."
		);
		// ...and beyond it by a *material* margin, so the gate's own stall detection wins the race and the
		// client gets DEADLINE_EXCEEDED naming the method rather than a bare RST_STREAM.
		//
		// The margin is asserted rather than a bare `>` for a reason worth stating: Armeria stores the
		// timeout relative to request start, so even with the grace deleted the horizon would read
		// `elapsed + stall` and clear a strict `>` on elapsed time alone. That assertion would have passed
		// against the very regression this test exists to catch. `MINIMUM_EXPECTED_GRACE_MILLIS` is
		// deliberately below the gate's actual 5 s so the test pins the *existence* of a grace without
		// pinning its exact value.
		assertTrue(
			serviceContext.requestTimeoutMillis() >= stallTimeoutMillis + MINIMUM_EXPECTED_GRACE_MILLIS,
			"The deadline must sit materially beyond the stall timeout so the gate detects the stall " +
				"first, but the horizon was " + serviceContext.requestTimeoutMillis() + " ms against a " +
				stallTimeoutMillis + " ms stall timeout."
		);
	}

	@Test
	@DisplayName("A call that arrived without a deadline is not given one by the gate")
	void shouldLeaveADisabledDeadlineDisabled() {
		final ServiceRequestContext serviceContext = ServiceRequestContext
			.builder(HttpRequest.of(HttpMethod.POST, "/test"))
			.build();
		serviceContext.clearRequestTimeout();

		final FakeServerCallObserver observer = new FakeServerCallObserver(true);
		try (final SafeCloseable ignored = serviceContext.push()) {
			final GrpcOutboundGate gate = GrpcOutboundGate.attach(observer, "testMethod", null, 30_000L);
			assertTrue(gate.awaitWritable(), "A ready transport must grant immediately.");
		}

		// an open-ended stream that deliberately asked for no deadline must not acquire one here
		assertEquals(
			0L, serviceContext.requestTimeoutMillis(),
			"Granting a message must not arm a deadline the caller declined."
		);
	}

	@Test
	@DisplayName("A ready transport lets the producer straight through")
	void shouldNotWaitWhenTransportIsReady() {
		final FakeServerCallObserver observer = new FakeServerCallObserver(true);
		final GrpcOutboundGate gate = GrpcOutboundGate.attach(observer, "testMethod");

		assertTimeoutPreemptively(
			Duration.ofSeconds(5),
			() -> assertTrue(gate.awaitWritable(), "A ready transport must not make the producer wait.")
		);
	}

	@Test
	@DisplayName("The producer waits while the transport is busy and resumes on the on-ready callback")
	void shouldWaitUntilTransportSignalsReadiness() throws InterruptedException {
		final FakeServerCallObserver observer = new FakeServerCallObserver(false);
		final GrpcOutboundGate gate = GrpcOutboundGate.attach(observer, "testMethod");
		final AtomicReference<Boolean> verdict = new AtomicReference<>();
		final AtomicReference<Throwable> failure = new AtomicReference<>();
		final CountDownLatch finished = new CountDownLatch(1);
		final Thread producer = awaitInBackground(gate, verdict, failure, finished);

		// negative wait - a busy machine can only make the gate wait longer, never release early, so a
		// short window cannot produce a false failure here
		assertFalse(
			finished.await(250, TimeUnit.MILLISECONDS),
			"Producer sailed past a transport that cannot accept another message."
		);

		observer.becomeReady();

		assertTrue(finished.await(30, TimeUnit.SECONDS), "Producer never woke up after the transport drained.");
		producer.join();
		assertNull(failure.get(), "Producer failed unexpectedly.");
		assertEquals(Boolean.TRUE, verdict.get(), "Producer must be cleared to send once the transport is ready.");
	}

	@Test
	@DisplayName("A cancelled call is answered immediately, without ever parking the producer")
	void shouldRefuseImmediatelyWhenCallIsAlreadyCancelled() {
		// not ready *and* cancelled - which is exactly the state Armeria leaves a cancelled call in,
		// because the pending-message counter it increments in `sendMessage` is never unwound
		final FakeServerCallObserver observer = new FakeServerCallObserver(false);
		final GrpcOutboundGate gate = GrpcOutboundGate.attach(
			observer, "testMethod", null, GENEROUS_STALL_TIMEOUT_MILLIS
		);
		observer.cancel();

		assertTimeoutPreemptively(
			Duration.ofSeconds(5),
			() -> assertFalse(
				gate.awaitWritable(),
				"Gate must report a cancelled call as unwritable rather than waiting for a readiness " +
					"signal that can never arrive."
			)
		);
	}

	@Test
	@DisplayName("Cancellation releases a producer that is already waiting, and runs the chained cleanup")
	void shouldReleaseWaitingProducerOnCancellation() throws InterruptedException {
		final FakeServerCallObserver observer = new FakeServerCallObserver(false);
		final AtomicBoolean cleanedUp = new AtomicBoolean();
		final GrpcOutboundGate gate = GrpcOutboundGate.attach(
			observer, "testMethod", () -> cleanedUp.set(true), GENEROUS_STALL_TIMEOUT_MILLIS
		);
		final AtomicReference<Boolean> verdict = new AtomicReference<>();
		final AtomicReference<Throwable> failure = new AtomicReference<>();
		final CountDownLatch finished = new CountDownLatch(1);
		final Thread producer = awaitInBackground(gate, verdict, failure, finished);

		assertFalse(finished.await(250, TimeUnit.MILLISECONDS), "Producer did not wait for a busy transport.");

		observer.cancel();

		assertTrue(finished.await(30, TimeUnit.SECONDS), "Cancelling the call did not release the producer.");
		producer.join();
		assertNull(failure.get(), "Producer failed unexpectedly.");
		assertEquals(Boolean.FALSE, verdict.get(), "A cancelled call must be reported as unwritable.");
		assertTrue(
			cleanedUp.get(),
			"The gate owns the call's cancel handler, so the cleanup handed to it must still run."
		);
	}

	@Test
	@DisplayName("Closing the call releases a producer that is already waiting")
	void shouldReleaseWaitingProducerWhenCallCloses() throws InterruptedException {
		final FakeServerCallObserver observer = new FakeServerCallObserver(false);
		final GrpcOutboundGate gate = GrpcOutboundGate.attach(
			observer, "testMethod", null, GENEROUS_STALL_TIMEOUT_MILLIS
		);
		final AtomicReference<Boolean> verdict = new AtomicReference<>();
		final AtomicReference<Throwable> failure = new AtomicReference<>();
		final CountDownLatch finished = new CountDownLatch(1);
		final Thread producer = awaitInBackground(gate, verdict, failure, finished);

		assertFalse(finished.await(250, TimeUnit.MILLISECONDS), "Producer did not wait for a busy transport.");

		// a close the producer did not initiate - an interceptor failing the call, or the server
		// shutting the stream down underneath it
		observer.close();

		assertTrue(finished.await(30, TimeUnit.SECONDS), "Closing the call did not release the producer.");
		producer.join();
		assertNull(failure.get(), "Producer failed unexpectedly.");
		assertEquals(Boolean.FALSE, verdict.get(), "A closed call must be reported as unwritable.");
	}

	@Test
	@DisplayName("A client that holds the stream open but stops reading is abandoned")
	void shouldAbandonStreamWhenClientStopsConsuming() {
		final FakeServerCallObserver observer = new FakeServerCallObserver(false);
		// the transport never becomes ready and the client never hangs up - the only way out is the
		// stall timeout, which is what stops a vanished client from pinning a worker thread forever
		final GrpcOutboundGate gate = GrpcOutboundGate.attach(observer, "testMethod", null, 200L);

		assertThrows(
			StalledGrpcStreamException.class,
			gate::awaitWritable,
			"A stream nobody is reading must be abandoned once the stall timeout elapses."
		);
	}

	/**
	 * Stand-in for gRPC's `ServerCallStreamObserverImpl` that exposes the two transport transitions the
	 * gate reacts to - readiness and termination - as ordinary method calls.
	 *
	 * It deliberately mirrors gRPC's own dispatch order: {@link #cancel()} sets the cancellation flag
	 * *before* running the handler, which is what lets the test distinguish "the gate saw the flag"
	 * from "the gate was woken by the callback".
	 */
	private static class FakeServerCallObserver extends ServerCallStreamObserver<Object> {
		private final AtomicBoolean ready;
		private final AtomicBoolean cancelled = new AtomicBoolean();
		private Runnable onReadyHandler;
		private Runnable onCancelHandler;
		private Runnable onCloseHandler;

		FakeServerCallObserver(boolean ready) {
			this.ready = new AtomicBoolean(ready);
		}

		/**
		 * Simulates the transport draining its queue.
		 */
		void becomeReady() {
			this.ready.set(true);
			if (this.onReadyHandler != null) {
				this.onReadyHandler.run();
			}
		}

		/**
		 * Simulates a client-initiated cancellation.
		 */
		void cancel() {
			this.cancelled.set(true);
			if (this.onCancelHandler != null) {
				this.onCancelHandler.run();
			}
		}

		/**
		 * Simulates a server-initiated close of the call.
		 */
		void close() {
			if (this.onCloseHandler != null) {
				this.onCloseHandler.run();
			}
		}

		@Override
		public boolean isReady() {
			return this.ready.get();
		}

		@Override
		public boolean isCancelled() {
			return this.cancelled.get();
		}

		@Override
		public void setOnReadyHandler(Runnable onReadyHandler) {
			this.onReadyHandler = onReadyHandler;
		}

		@Override
		public void setOnCancelHandler(Runnable onCancelHandler) {
			this.onCancelHandler = onCancelHandler;
		}

		@Override
		public void setOnCloseHandler(Runnable onCloseHandler) {
			this.onCloseHandler = onCloseHandler;
		}

		@Override
		public void setCompression(String compression) {
		}

		@Override
		public void setMessageCompression(boolean enable) {
		}

		@Override
		public void disableAutoInboundFlowControl() {
		}

		@Override
		public void request(int count) {
		}

		@Override
		public void onNext(Object value) {
		}

		@Override
		public void onError(Throwable t) {
		}

		@Override
		public void onCompleted() {
		}
	}

}
