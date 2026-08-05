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

package io.evitadb.driver.cdc;

import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.driver.exception.EvitaClientPoolSaturatedException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.grpc.requestResponse.cdc.HeartBeat;
import io.evitadb.test.TestConstants;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.nio.BufferOverflowException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.DRIVER;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.STREAM;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Verifies the client-side CDC backpressure contract of {@link ClientChangeCapturePublisher}:
 *
 * - inbound flow control is taken over manually before the stream starts,
 * - the flow window is primed to the queue capacity after the acknowledgement message,
 * - one gRPC credit is restored per delivered capture and per server-side heartbeat,
 * - if the queue still overflows (defensive path), the subscription dies with
 *   `BufferOverflowException` instead of parking the calling thread.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ClientChangeCapturePublisher backpressure and flow control")
@Tag(DRIVER)
@Tag(GRPC)
@Tag(CDC)
@Tag(STREAM)
class ClientChangeCapturePublisherTest implements TestConstants {
	private static final int QUEUE_SIZE = 4;
	private static final UUID SUBSCRIPTION_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final OffsetDateTime FIXED_TS =
		OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

	@Nested
	@DisplayName("Flow control")
	class FlowControl {

		@Test
		@DisplayName("Disables auto inbound flow control and primes the window after ACK")
		void shouldDisableAutoFlowControlAndPrimeWindowWhenAckReceived() {
			final TestHarness harness = new TestHarness();
			harness.start();

			verify(harness.observer, times(1)).disableAutoRequestWithInitial(1);

			// deliver the acknowledgement message — must prime the flow window to queue capacity
			harness.deliverAck();
			verify(harness.observer, times(1)).request(QUEUE_SIZE);
		}

		@Test
		@DisplayName("Restores a single credit when a server heartbeat arrives")
		void shouldRestoreCreditWhenServerHeartbeatArrives() {
			final TestHarness harness = new TestHarness();
			harness.start();
			harness.deliverAck();

			// a server-pushed heartbeat is not enqueued for the delegate
			// — credit must be restored eagerly; index 1 follows ACK index 0 so the
			// "Missed heartbeat" warn log stays quiet
			harness.deliverHeartbeat(1);
			// heartbeat top-up request(1) → exactly 1 single-credit call
			verify(harness.observer, times(1)).request(1);
		}

		@Test
		@DisplayName("Restores one credit per item delivered to the delegate")
		void shouldRestoreOneCreditWhenItemDeliveredToDelegate() {
			final TestHarness harness = new TestHarness(Long.MAX_VALUE);
			harness.start();
			harness.deliverAck();

			final int delivered = 3;
			for (int i = 0; i < delivered; i++) {
				harness.deliverCapture(i);
			}

			// one request(1) per item the delegate received
			verify(harness.observer, times(delivered)).request(1);
			// flow window primed once
			verify(harness.observer, times(1)).request(QUEUE_SIZE);
		}

		@Test
		@DisplayName("Fails the subscriber with BufferOverflowException when the queue overflows")
		void shouldFailSubscriberWithBufferOverflowExceptionWhenQueueOverflows() {
			// delegate doesn't request anything → items sit in the queue and
			// overflow on the (QUEUE_SIZE + 1)th
			final TestHarness harness = new TestHarness(0L);
			harness.start();
			harness.deliverAck();

			for (int i = 0; i < QUEUE_SIZE + 1; i++) {
				harness.deliverCapture(i);
			}

			// the safety-net must surface as `onError(BufferOverflowException)` on the delegate
			assertNotNull(
				harness.delegate.lastError.get(),
				"delegate must be terminated on overflow"
			);
			assertInstanceOf(
				BufferOverflowException.class,
				harness.delegate.lastError.get(),
				"overflow must report `BufferOverflowException`"
			);
			// no items must have been pushed to the delegate (it never requested any)
			assertTrue(
				harness.delegate.received.isEmpty(),
				"no items should leak to a zero-demand delegate"
			);
		}
	}

	@Nested
	@DisplayName("Edge cases")
	class EdgeCases {

		@Test
		@DisplayName("Cancels the gRPC stream when the client-side subscription fails")
		void shouldCancelGrpcStreamWhenSubscriptionFailsClientSide() {
			final TestHarness harness = new TestHarness(0L);
			harness.start();
			harness.deliverAck();

			for (int i = 0; i < QUEUE_SIZE + 1; i++) {
				harness.deliverCapture(i);
			}

			// overflow must terminate the delegate AND propagate cancellation to the gRPC
			// stream so the server stops pushing into a dead client
			assertInstanceOf(
				BufferOverflowException.class,
				harness.delegate.lastError.get(),
				"delegate must be terminated by the overflow"
			);
			verify(harness.observer, times(1)).cancel(
				org.mockito.ArgumentMatchers.anyString(),
				org.mockito.ArgumentMatchers.any()
			);
		}

		@Test
		@DisplayName("Handles ACK arriving before onSubscribe completes without NPE")
		void shouldHandleAckArrivingBeforeOnSubscribeCompletes() {
			// reproduce the race: deliver the ACK from INSIDE the stream initializer,
			// i.e. before `subscribe()` reaches `onSubscribe(subscription)`. The subscriber
			// must already have its subscription field wired so `onNext` does not NPE.
			final TestHarness harness = new TestHarness(0L, true);
			harness.start();
			// the ACK was delivered during init, so subscribe() has already returned on its thread
			harness.joinSubscribe();

			// the ACK was consumed during init — the subscription must have been assigned
			// its server-side id and the flow window must have been primed
			verify(harness.observer, times(1)).disableAutoRequestWithInitial(1);
			verify(harness.observer, times(1)).request(QUEUE_SIZE);
			// no error must have surfaced to the delegate
			assertNull(
				harness.delegate.lastError.get(),
				"delegate must not see an error from the ACK-before-onSubscribe race"
			);
		}

		@Test
		@DisplayName("Throws a descriptive error when the first message is not an acknowledgement")
		void shouldThrowDescriptiveErrorWhenFirstMessageIsNotAcknowledgement() {
			final TestHarness harness = new TestHarness(0L);
			harness.start();

			// first inbound is a capture, not the ACK — the publisher must reject the protocol
			// violation with a descriptive error instead of letting an NPE leak through
			final GenericEvitaInternalError thrown = assertThrows(
				GenericEvitaInternalError.class,
				() -> harness.deliverCapture(0)
			);
			assertEquals(
				"Expected ACKNOWLEDGEMENT as first message but got something else.",
				thrown.getPrivateMessage()
			);
			// the same protocol violation must release the blocked subscribe() rather than let it
			// wait out the streaming timeout
			harness.joinSubscribe();
			assertInstanceOf(
				GenericEvitaInternalError.class,
				harness.subscribeError.get(),
				"subscribe() must fail fast with the protocol-violation error"
			);
		}

		@Test
		@DisplayName("Terminates the consume scheduler after a cancelled overflow")
		void shouldTerminateConsumeSchedulerAfterCancelledOverflow() {
			// After an overflow the walking-dead exception is permanently set and stays
			// non-null for the lifetime of the subscription. The post-flag re-check
			// inside consume() must NOT loop on that condition once the subscription
			// has already been cancelled — otherwise the executor would be hammered
			// with no-op tasks forever (or, with a synchronous executor, stack-overflow
			// the calling thread). Verify the scheduler terminates by simply observing
			// that the overflow path returns cleanly and the delegate sees exactly one
			// `onError` notification.
			final TestHarness harness = new TestHarness(0L);
			harness.start();
			harness.deliverAck();

			// overflow the queue — this transitively triggers consume() → notify → cancel
			for (int i = 0; i < QUEUE_SIZE + 1; i++) {
				harness.deliverCapture(i);
			}

			// the delegate must have been terminated exactly once with the overflow error
			assertInstanceOf(
				BufferOverflowException.class,
				harness.delegate.lastError.get(),
				"delegate must receive the overflow error"
			);
			// any subsequent `deliverCapture` after cancellation must not resurrect the
			// scheduler — the subscription is dead and consume() must short-circuit
			harness.deliverCapture(99);
			assertInstanceOf(
				BufferOverflowException.class,
				harness.delegate.lastError.get(),
				"the delegate's error must remain the original overflow exception"
			);
		}
	}

	@Nested
	@DisplayName("Heartbeat delivery")
	class HeartbeatDelivery {

		@Test
		@DisplayName("Delivers heartbeats in order even on a multi-threaded pool")
		void shouldDeliverHeartbeatsInOrderOnMultiThreadedPool() throws InterruptedException {
			// `HeartBeatSensor` consumers detect missed heartbeats from index continuity — moving delivery
			// off the gRPC inbound thread must not cost that ordering, or a consumer sees a phantom gap.
			// A multi-threaded pool is what would expose an unserialized dispatch.
			final int heartbeats = 60;
			final ThreadPoolExecutor pool = new ThreadPoolExecutor(
				8, 8, 0L, TimeUnit.MILLISECONDS,
				new LinkedBlockingQueue<>(),
				r -> {
					final Thread thread = new Thread(r, "test-heartbeat-pool");
					thread.setDaemon(true);
					return thread;
				}
			);
			try {
				final HeartBeatSensingSubscriber delegate = new HeartBeatSensingSubscriber(0L, heartbeats);
				final TestHarness harness = new TestHarness(false, pool, delegate);
				harness.start();
				// index 0 is the acknowledgement, which is itself a heartbeat
				harness.deliverAck();
				for (long index = 1; index < heartbeats; index++) {
					harness.deliverHeartbeat(index);
				}

				// Await the batch rather than polling a fixed sleep budget: the tests run in parallel forks that
				// contend for CPU, so any wall-clock guess large enough to be safe there is pure waiting here.
				// A latch returns the moment the work is done and only trips when it genuinely never completes.
				assertTrue(
					delegate.allHeartBeatsDelivered.await(30, TimeUnit.SECONDS),
					"only " + delegate.observedIndices.size() + " of " + heartbeats + " heartbeats were delivered"
				);

				assertEquals(
					heartbeats, delegate.observedIndices.size(),
					"every heartbeat must reach the sensor exactly once"
				);
				final List<Long> expected = new java.util.ArrayList<>(heartbeats);
				for (long index = 0; index < heartbeats; index++) {
					expected.add(index);
				}
				assertEquals(
					expected, delegate.observedIndices,
					"heartbeats must reach the sensor in ascending index order - a consumer derives its " +
						"missed-heartbeat count from exactly this continuity"
				);
			} finally {
				pool.shutdownNow();
			}
		}
	}

	@Nested
	@DisplayName("Acknowledgement gate")
	class AcknowledgementGate {

		@Test
		@DisplayName("Blocks subscribe() until the server acknowledges the subscription")
		void shouldBlockSubscribeUntilAcknowledgementArrives() throws InterruptedException {
			// The whole session-call ordering contract rests on this gate: `registerChangeCatalogCapture`
			// hands the subscriber to the ASYNC stub and returns immediately, so nothing but this blocking
			// wait keeps a subsequent same-session call from racing the still-pending server-side
			// registration. It is also what makes routing CDC onto its own connection safe, since HTTP/2
			// orders frames only within a connection. See EvitaClientSession#registerChangeCatalogCapture.
			final TestHarness harness = new TestHarness(0L);
			harness.start();

			final Thread subscribeThread = harness.subscribeThread;
			assertNotNull(subscribeThread, "the subscribe thread must have been started");
			// The stream initializer has already run; give a regression that drops the gate ample time to let
			// subscribe() return before asserting it did not. This wait is safe under CPU contention *because*
			// the assertion is negative: a loaded machine can only make the thread more likely to still be
			// blocked, never less. `join` also returns the instant a regression lets the thread finish, so the
			// failing case is fast and the passing case costs one bounded wait.
			subscribeThread.join(500L);
			assertTrue(
				subscribeThread.isAlive(),
				"subscribe() must not return before the server acknowledges the subscription"
			);
			assertNull(harness.subscribeError.get(), "no error must have surfaced while waiting for the ACK");

			// the acknowledgement releases the gate — `deliverAck` joins the subscribe thread
			harness.deliverAck();
			assertNull(harness.subscribeError.get(), "subscribe() must complete cleanly once acknowledged");
		}
	}

	@Nested
	@DisplayName("Pool saturation")
	class PoolSaturation {

		@Test
		@DisplayName("Completes the subscription teardown when the client pool refuses the task")
		void shouldCompleteTeardownWhenPoolRefusesTheTask() {
			// A lost cleanup would leave the subscription registered with the publisher, so the publisher
			// never auto-closes and a consumer's "recreate if missing" guard keeps seeing a dead-but-present
			// subscriber — a silent, permanent CDC outage (issue #1387 §2).
			final TestHarness harness = new TestHarness(
				false, new RejectingExecutorService(), new RecordingSubscriber(0L)
			);
			harness.start();
			harness.deliverAck();

			final Flow.Subscription subscription = harness.delegate.subscription.get();
			assertNotNull(subscription, "the delegate must have received its subscription");

			// this is the call a consumer makes from its own error handler; it must not throw, because the
			// throw would abort the rest of the consumer's cleanup
			assertDoesNotThrow(subscription::cancel);

			// the subscription de-registered itself, which empties the collection and closes the publisher
			assertTrue(
				harness.publisher.isClosed(),
				"the publisher must auto-close once its last subscription is cancelled"
			);
			// the gRPC stream must have been released as well
			verify(harness.observer, times(1)).cancel(
				org.mockito.ArgumentMatchers.anyString(),
				org.mockito.ArgumentMatchers.any()
			);
		}

		@Test
		@DisplayName("Never closes a closeable delegate on the calling thread when the executor refuses the task")
		void shouldNotCloseDelegateOnCallingThreadWhenExecutorRefusesTheTask() throws InterruptedException {
			// The delegate's `close` is CONSUMER code and commonly re-subscribes. Running it in place — as
			// `CallerRunsPolicy` did, and as a naive "run the cleanup synchronously" fallback would — walks
			// straight back into subscribe() → awaitAcknowledgement() on the thread that must deliver the
			// acknowledgement. That is the exact re-entrance in the issue #1387 stack trace. A refused close is
			// therefore skipped, never relocated onto the caller.
			final CloseableRecordingSubscriber delegate = new CloseableRecordingSubscriber(0L);
			final TestHarness harness = new TestHarness(false, new RejectingExecutorService(), delegate);
			harness.start();
			harness.deliverAck();

			final Flow.Subscription subscription = delegate.subscription.get();
			assertNotNull(subscription, "the delegate must have received its subscription");

			assertDoesNotThrow(subscription::cancel);

			assertFalse(
				delegate.closed.await(250, TimeUnit.MILLISECONDS),
				"a refused delegate close must not be run at all - least of all on the calling thread"
			);
			// but the driver-internal half of the teardown still completed, so nothing leaks
			assertTrue(
				harness.publisher.isClosed(),
				"the publisher must auto-close even when the consumer-facing close could not be dispatched"
			);
		}

		@Test
		@DisplayName("Never delivers a stream error on the calling thread when the executor refuses the task")
		void shouldNotDeliverStreamErrorOnCallingThreadWhenExecutorRefusesTheTask() throws InterruptedException {
			// `onError` is the ordinary production teardown path, and the consumer handler it invokes is the
			// one that typically re-subscribes — the exact re-entrance that captured the event loop
			final RecordingSubscriber delegate = new RecordingSubscriber(0L);
			final TestHarness harness = new TestHarness(false, new RejectingExecutorService(), delegate);
			harness.start();
			harness.deliverAck();

			final IllegalStateException cause = new IllegalStateException("stream failed");
			assertDoesNotThrow(() -> harness.deliverStreamError(cause));

			assertFalse(
				delegate.errorDelivered.await(250, TimeUnit.MILLISECONDS),
				"a refused terminal error must not be delivered on the submitting thread"
			);
			// the driver-internal cancellation runs inline, so this is race-free
			assertTrue(harness.publisher.isClosed(), "the publisher must auto-close after the terminal error");
			// `onError` flips `serverSideClosed`, so re-cancelling a stream the server already closed is wrong
			verify(harness.observer, never()).cancel(anyString(), any());
		}

		@Test
		@DisplayName("Never delivers stream completion on the calling thread when the executor refuses the task")
		void shouldNotDeliverStreamCompletionOnCallingThreadWhenExecutorRefusesTheTask()
			throws InterruptedException {
			final RecordingSubscriber delegate = new RecordingSubscriber(0L);
			final TestHarness harness = new TestHarness(false, new RejectingExecutorService(), delegate);
			harness.start();
			harness.deliverAck();

			assertDoesNotThrow(harness::deliverStreamCompletion);

			assertFalse(
				delegate.completed.await(250, TimeUnit.MILLISECONDS),
				"a refused completion signal must not be delivered on the submitting thread"
			);
			assertTrue(harness.publisher.isClosed(), "the publisher must auto-close after completion");
			verify(harness.observer, never()).cancel(anyString(), any());
		}

		@Test
		@DisplayName("Fails the subscription when the executor refuses the capture drain")
		void shouldFailSubscriptionWhenExecutorRefusesTheCaptureDrain() throws InterruptedException {
			// the hot path: `consume()` is reached from `produce()` on the gRPC inbound thread for every
			// single capture, so a rejection here must not drain onto that thread either. Since the consumer
			// cannot be fed at all, the subscription is torn down rather than left silently stalled.
			final RecordingSubscriber delegate = new RecordingSubscriber(Long.MAX_VALUE);
			final TestHarness harness = new TestHarness(false, new RejectingExecutorService(), delegate);
			harness.start();
			harness.deliverAck();

			assertDoesNotThrow(() -> harness.deliverCapture(0));

			assertFalse(
				delegate.itemDelivered.await(250, TimeUnit.MILLISECONDS),
				"captures must never be drained onto the thread that submitted the drain"
			);
			assertTrue(
				harness.publisher.isClosed(),
				"a subscription whose captures cannot be delivered must be torn down, not left stalled"
			);
		}

		@Test
		@DisplayName("Fails the subscription when the executor refuses a heartbeat notification")
		void shouldFailSubscriptionWhenExecutorRefusesAHeartBeat() throws InterruptedException {
			// `HeartBeatSensor` exists so a consumer can notice a stale stream and re-establish it — running
			// it on the inbound thread reproduces issue #1387 on the dedicated CDC connection. Dropping it
			// silently is not an option either: the consumer reads index continuity, so a hidden gap would be
			// indistinguishable from missed *server* heartbeats.
			final HeartBeatSensingSubscriber delegate = new HeartBeatSensingSubscriber(0L);
			final TestHarness harness = new TestHarness(false, new RejectingExecutorService(), delegate);
			harness.start();
			harness.deliverAck();

			assertFalse(
				delegate.heartBeatDelivered.await(250, TimeUnit.MILLISECONDS),
				"the heartbeat notification must never run on the gRPC inbound thread that delivered it"
			);
			assertTrue(
				harness.publisher.isClosed(),
				"a heartbeat that cannot be delivered must fail the subscription rather than resume with a gap"
			);
		}

		@Test
		@DisplayName("Reports the pool's own refusal to the consumer, not a synthesized one")
		void shouldReportThePoolsOwnRefusalWhenExecutorRefusesTheCaptureDrain() throws InterruptedException {
			// The terminal `onError` is the consumer's only clue about *why* its subscription died, and the
			// saturation variant is the only one that names the `maxThreadCount`/`queueSize` knobs that would
			// fix it. Re-creating the exception here instead of forwarding what the pool threw handed the
			// consumer the shutdown wording ("the client is shutting down") during plain overload.
			final RecordingSubscriber delegate = new RecordingSubscriber(Long.MAX_VALUE);
			final RefuseOnceExecutorService executor = new RefuseOnceExecutorService();
			try {
				final TestHarness harness = new TestHarness(false, executor, delegate);
				harness.start();
				harness.deliverAck();

				// `onSubscribe` requested Long.MAX_VALUE, and every `request` dispatches a drain of its own - so
				// a drain task is already in flight on the fixture pool here, and joining the subscribe thread
				// does not wait for it. Arming the refusal while that drain still holds `currentlyConsuming`
				// asserts something the driver cannot produce: `produce()` would lose the CAS, return without
				// ever reaching the executor, and the in-flight drain would deliver the capture normally - no
				// refusal, therefore no terminal error, however long the wait below. Quiesce the pool first so
				// the drain under test is unambiguously the next submission. On an idle machine it always was,
				// which is why this only ever failed under CI's parallel load.
				executor.awaitIdle();

				// refuse exactly the drain, then accept again so the terminal notification it triggers gets
				// through - a permanently refusing executor would swallow the very signal under test
				executor.refuseNextSubmission();
				assertDoesNotThrow(() -> harness.deliverCapture(0));

				assertTrue(
					delegate.errorDelivered.await(30, TimeUnit.SECONDS),
					"a refused drain must terminate the subscription with an error the consumer actually receives"
				);
				final Throwable reported = delegate.lastError.get();
				assertInstanceOf(EvitaClientPoolSaturatedException.class, reported);
				assertTrue(
					reported.getMessage().contains("saturated"),
					"the consumer must be told the pool was saturated, not that the client is shutting down: " +
						reported.getMessage()
				);
				assertTrue(
					reported.getMessage().contains("maxThreadCount"),
					"the reported cause must keep naming the knobs that widen the pool: " + reported.getMessage()
				);
			} finally {
				executor.shutdownNow();
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Test fixtures
	// ---------------------------------------------------------------------------------------------

	/**
	 * Executor that refuses a single submission on demand and runs everything else on a real thread — a pool
	 * that is momentarily saturated rather than permanently dead. Refusing only once is what makes the
	 * *consequence* of the refusal observable: the terminal notification the driver dispatches in response
	 * still has somewhere to run.
	 */
	private static final class RefuseOnceExecutorService extends AbstractExecutorService {
		/**
		 * Daemon-threaded on purpose. `Executors.newSingleThreadExecutor()` produces **non-daemon** threads, so
		 * a fixture that forgot to shut it down would keep the surefire JVM alive and inflate the JVM-wide
		 * thread delta that `CdcCallbackDispatcherTest` asserts on in the same fork.
		 */
		private final ExecutorService delegate = Executors.newSingleThreadExecutor(
			runnable -> {
				final Thread thread = new Thread(runnable, "test-refuse-once-pool");
				thread.setDaemon(true);
				return thread;
			}
		);
		private volatile boolean refuseNext;

		/**
		 * Arms the refusal for the next submission only. Deliberately not a counter - the tests using this
		 * fixture care about one specific submission, and arming it immediately before that call keeps which
		 * one unambiguous.
		 */
		void refuseNextSubmission() {
			this.refuseNext = true;
		}

		/**
		 * Blocks until every task submitted so far has finished running.
		 *
		 * The delegate is single-threaded and therefore FIFO, so a barrier task that has run proves every
		 * earlier submission has completed - including the flag reset at the end of a capture drain. Call this
		 * before {@link #refuseNextSubmission()} whenever a test needs the refusal to land on a *specific*
		 * submission; see the call site for what an unnoticed in-flight drain turns the assertion into.
		 *
		 * Submitted straight to the delegate on purpose: the barrier must never become the submission a test
		 * armed its refusal for.
		 */
		void awaitIdle() throws InterruptedException {
			final CountDownLatch idle = new CountDownLatch(1);
			this.delegate.execute(idle::countDown);
			assertTrue(idle.await(30, TimeUnit.SECONDS), "the fixture pool never went idle");
		}

		@Override
		public void shutdown() {
			this.delegate.shutdown();
		}

		@Nonnull
		@Override
		public List<Runnable> shutdownNow() {
			return this.delegate.shutdownNow();
		}

		@Override
		public boolean isShutdown() {
			return this.delegate.isShutdown();
		}

		@Override
		public boolean isTerminated() {
			return this.delegate.isTerminated();
		}

		@Override
		public boolean awaitTermination(long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
			return this.delegate.awaitTermination(timeout, unit);
		}

		@Override
		public void execute(@Nonnull Runnable command) {
			if (this.refuseNext) {
				this.refuseNext = false;
				throw new EvitaClientPoolSaturatedException(4, 100);
			}
			this.delegate.execute(command);
		}
	}

	/**
	 * Synchronous executor — runs every submitted task on the calling thread so the test
	 * can assert against observable state without awaiting an async dispatcher.
	 */
	private static final class SynchronousExecutorService extends AbstractExecutorService {
		private volatile boolean shutdown;

		@Override
		public void shutdown() {
			this.shutdown = true;
		}

		@Nonnull
		@Override
		public List<Runnable> shutdownNow() {
			this.shutdown = true;
			return Collections.emptyList();
		}

		@Override
		public boolean isShutdown() {
			return this.shutdown;
		}

		@Override
		public boolean isTerminated() {
			return this.shutdown;
		}

		@Override
		public boolean awaitTermination(long timeout, @Nonnull TimeUnit unit) {
			return true;
		}

		@Override
		public void execute(@Nonnull Runnable command) {
			command.run();
		}
	}

	/**
	 * Executor that refuses every submission the way a saturated evitaDB client pool does — the shape the
	 * teardown paths must survive since `CallerRunsPolicy` was replaced by a fail-fast handler.
	 */
	private static final class RejectingExecutorService extends AbstractExecutorService {
		private volatile boolean shutdown;

		@Override
		public void shutdown() {
			this.shutdown = true;
		}

		@Nonnull
		@Override
		public List<Runnable> shutdownNow() {
			this.shutdown = true;
			return Collections.emptyList();
		}

		@Override
		public boolean isShutdown() {
			return this.shutdown;
		}

		@Override
		public boolean isTerminated() {
			return this.shutdown;
		}

		@Override
		public boolean awaitTermination(long timeout, @Nonnull TimeUnit unit) {
			return true;
		}

		@Override
		public void execute(@Nonnull Runnable command) {
			throw new EvitaClientPoolSaturatedException(4, 100);
		}
	}

	/**
	 * Recording delegate subscriber — captures `onSubscribe`/`onNext`/`onError`/`onComplete`
	 * invocations and requests a fixed number of items at subscription time.
	 */
	private static class RecordingSubscriber implements Flow.Subscriber<ChangeSystemCapture> {
		private final long initialRequest;
		/**
		 * Concurrent because the pool-saturation tests read it from a thread other than the one that wrote it.
		 */
		final List<ChangeSystemCapture> received = new CopyOnWriteArrayList<>();
		final AtomicReference<Throwable> lastError = new AtomicReference<>();
		/**
		 * The subscription handed to this delegate, so a test can drive `cancel()` the way a consumer would.
		 */
		final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
		/**
		 * Records the thread each downstream notification arrived on — the invariant under test is that it is
		 * never the thread that submitted the callback.
		 */
		final AtomicReference<Thread> notifyingThread = new AtomicReference<>();
		final CountDownLatch itemDelivered = new CountDownLatch(1);
		final CountDownLatch errorDelivered = new CountDownLatch(1);
		final CountDownLatch completed = new CountDownLatch(1);

		RecordingSubscriber(long initialRequest) {
			this.initialRequest = initialRequest;
		}

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			this.subscription.set(subscription);
			if (this.initialRequest > 0) {
				subscription.request(this.initialRequest);
			}
		}

		@Override
		public void onNext(ChangeSystemCapture item) {
			this.notifyingThread.set(Thread.currentThread());
			this.received.add(item);
			this.itemDelivered.countDown();
		}

		@Override
		public void onError(Throwable throwable) {
			this.notifyingThread.set(Thread.currentThread());
			this.lastError.set(throwable);
			this.errorDelivered.countDown();
		}

		@Override
		public void onComplete() {
			this.notifyingThread.set(Thread.currentThread());
			this.completed.countDown();
		}
	}

	/**
	 * Recording delegate that also implements {@link HeartBeatSensor}, so the heartbeat notification path is
	 * exercised. `onHeartBeat` is consumer code invoked from `onNext`, i.e. on the gRPC inbound thread, and the
	 * SPI exists precisely so a consumer can re-establish a stale stream — the re-entrant shape that must
	 * never run on the event loop.
	 */
	private static final class HeartBeatSensingSubscriber extends RecordingSubscriber implements HeartBeatSensor {
		final CountDownLatch heartBeatDelivered = new CountDownLatch(1);
		/**
		 * Counts down to zero once the expected number of heartbeats has arrived, so a test can await the
		 * completion of an asynchronous batch instead of sleeping for a fixed budget and hoping. A poll loop
		 * would be worse on both axes: slower than needed on an idle machine, and prone to expiring on a
		 * loaded one — which is a test failure that says nothing about the code.
		 */
		final CountDownLatch allHeartBeatsDelivered;
		final AtomicReference<Thread> heartBeatThread = new AtomicReference<>();
		/**
		 * Indices in the order the sensor observed them — `LongRunningCdcHeartbeatTest` derives its
		 * missed-heartbeat count from exactly this continuity, so reordering would manufacture a phantom gap.
		 */
		final List<Long> observedIndices = new CopyOnWriteArrayList<>();

		HeartBeatSensingSubscriber(long initialRequest) {
			this(initialRequest, 1);
		}

		HeartBeatSensingSubscriber(long initialRequest, int expectedHeartBeats) {
			super(initialRequest);
			this.allHeartBeatsDelivered = new CountDownLatch(expectedHeartBeats);
		}

		@Override
		public void onHeartBeat(@Nonnull HeartBeat heartBeat) {
			this.heartBeatThread.set(Thread.currentThread());
			this.observedIndices.add(heartBeat.index());
			this.heartBeatDelivered.countDown();
			this.allHeartBeatsDelivered.countDown();
		}
	}

	/**
	 * Recording delegate that is additionally `AutoCloseable`, so `ClientChangeCaptureSubscriber.close()`
	 * takes the branch that hands the delegate's own `close` to the client pool. Records **which thread**
	 * ran it — the invariant under test is that it is never the thread that submitted it.
	 */
	private static final class CloseableRecordingSubscriber extends RecordingSubscriber implements AutoCloseable {
		final CountDownLatch closed = new CountDownLatch(1);
		final AtomicReference<Thread> closingThread = new AtomicReference<>();

		CloseableRecordingSubscriber(long initialRequest) {
			super(initialRequest);
		}

		@Override
		public void close() {
			this.closingThread.set(Thread.currentThread());
			this.closed.countDown();
		}
	}

	/**
	 * Concrete publisher over `ChangeSystemCapture` captures with an `Object` request/response
	 * envelope so the test can drive heartbeats and captures by passing distinct java types.
	 */
	private static final class SystemCapturePublisher
		extends ClientChangeCapturePublisher<ChangeSystemCapture, Object, Object> {

		SystemCapturePublisher(
			int queueSize,
			@Nonnull ExecutorService executor,
			@Nonnull Consumer<ClientResponseObserver<Object, Object>> streamInitializer
		) {
			super(queueSize, Duration.ofSeconds(60), executor, streamInitializer, p -> {});
		}

		@Nonnull
		@Override
		protected Optional<HeartBeat> deserializeAcknowledgementResponse(Object itemResponse) {
			return itemResponse instanceof HeartBeat hb ? Optional.of(hb) : Optional.empty();
		}

		@Nonnull
		@Override
		protected Optional<ChangeSystemCapture> deserializeCaptureResponse(Object itemResponse) {
			return itemResponse instanceof ChangeSystemCapture cap ? Optional.of(cap) : Optional.empty();
		}
	}

	/**
	 * Wires the publisher, a mock gRPC observer and a recording delegate together so each
	 * test reads cleanly.
	 *
	 * `subscribe()` now blocks until the server acknowledges the subscription, so `start()` runs it
	 * on a background thread — mirroring production, where the acknowledgement arrives on a gRPC
	 * inbound thread distinct from the caller — and returns once the stream initializer has run so
	 * the test can assert against the primed subscriber and deliver the remaining messages (ACK,
	 * heartbeats, captures) from the main thread. `deliverAck()` joins the subscribe thread so the
	 * follow-up assertions run single-threaded once the acknowledgement gate has been released.
	 */
	private static final class TestHarness {
		final ClientCallStreamObserver<Object> observer;
		final RecordingSubscriber delegate;
		private final SystemCapturePublisher publisher;
		private final AtomicReference<ClientResponseObserver<Object, Object>> subscriberRef =
			new AtomicReference<>();
		/**
		 * Counted down by the stream initializer once the subscriber is wired (`beforeStart` done and,
		 * for the ack-during-init case, the ACK consumed), so `start()` can return with a fully primed
		 * subscriber even though `subscribe()` is still blocked on the acknowledgement.
		 */
		private final CountDownLatch initialized = new CountDownLatch(1);
		/**
		 * Captures any throwable the background `subscribe()` call surfaces (e.g. a protocol violation
		 * or an acknowledgement timeout) instead of letting it escape uncaught on the daemon thread.
		 */
		private final AtomicReference<Throwable> subscribeError = new AtomicReference<>();
		private volatile Thread subscribeThread;

		TestHarness() {
			this(0L);
		}

		TestHarness(long delegateInitialRequest) {
			this(delegateInitialRequest, false);
		}

		/**
		 * Builds a harness that optionally simulates the server ACK landing on the
		 * stream-initialization thread (i.e. before `subscribe()` returns control to
		 * `onSubscribe`). This exercises the race where `onNext` would dereference a
		 * still-null subscription field if the subscriber were not wired early.
		 */
		TestHarness(long delegateInitialRequest, boolean deliverAckDuringInit) {
			this(
				deliverAckDuringInit,
				new SynchronousExecutorService(), new RecordingSubscriber(delegateInitialRequest)
			);
		}

		/**
		 * Full-control variant used by the pool-saturation tests: the executor the publisher dispatches on
		 * and the delegate subscriber are both supplied by the caller.
		 *
		 * @param deliverAckDuringInit whether the ACK is fired from inside the stream initializer
		 * @param executor             executor the publisher hands its work to
		 * @param delegate             the downstream subscriber
		 */
		@SuppressWarnings("unchecked")
		TestHarness(
			boolean deliverAckDuringInit,
			@Nonnull ExecutorService executor,
			@Nonnull RecordingSubscriber delegate
		) {
			this.observer = (ClientCallStreamObserver<Object>) mock(ClientCallStreamObserver.class);
			this.delegate = delegate;
			this.publisher = new SystemCapturePublisher(
				QUEUE_SIZE,
				executor,
				subscriber -> {
					this.subscriberRef.set(subscriber);
					subscriber.beforeStart(this.observer);
					if (deliverAckDuringInit) {
						// fire the ACK from the initializer itself — must NOT NPE
						subscriber.onNext(buildHeartbeat(0));
					}
					// signal that the subscriber is fully wired so start() can return; subscribe()
					// keeps blocking on the acknowledgement on this background thread
					this.initialized.countDown();
				}
			);
		}

		void start() {
			this.subscribeThread = new Thread(
				() -> {
					try {
						this.publisher.subscribe(this.delegate);
					} catch (Throwable t) {
						this.subscribeError.set(t);
					}
				},
				"test-cdc-subscribe"
			);
			this.subscribeThread.setDaemon(true);
			this.subscribeThread.start();
			try {
				assertTrue(
					this.initialized.await(30, TimeUnit.SECONDS),
					"stream initializer did not run in time"
				);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError("interrupted while waiting for stream initialization", e);
			}
		}

		void deliverAck() {
			// first message: ACKNOWLEDGEMENT — its presence sets the subscription id and releases the
			// acknowledgement gate; join the subscribe thread so it has fully returned before the test
			// makes its (from here on single-threaded) assertions and further deliveries
			this.subscriberRef.get().onNext(buildHeartbeat(0));
			joinSubscribe();
		}

		void deliverHeartbeat(long index) {
			this.subscriberRef.get().onNext(buildHeartbeat(index));
		}

		/**
		 * Simulates the server-side stream failing, as gRPC would report it on the inbound thread.
		 *
		 * @param cause the failure the server (or transport) reported
		 */
		void deliverStreamError(@Nonnull Throwable cause) {
			this.subscriberRef.get().onError(cause);
		}

		/**
		 * Simulates the server completing the stream. Drives the gRPC-facing `onCompleted()` so the delegation
		 * to `onComplete()` is covered too.
		 */
		void deliverStreamCompletion() {
			this.subscriberRef.get().onCompleted();
		}

		void deliverCapture(int payload) {
			this.subscriberRef.get().onNext(
				new ChangeSystemCapture(payload, payload, FIXED_TS, Operation.UPSERT, null)
			);
		}

		/**
		 * Waits for the background `subscribe()` call to return so the test's remaining work runs
		 * without racing it.
		 */
		void joinSubscribe() {
			final Thread thread = this.subscribeThread;
			if (thread != null) {
				try {
					thread.join(TimeUnit.SECONDS.toMillis(30));
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new AssertionError("interrupted while joining the subscribe thread", e);
				}
				assertFalse(thread.isAlive(), "the subscribe thread did not return in time");
			}
		}

		private static HeartBeat buildHeartbeat(long index) {
			return new HeartBeat(SUBSCRIPTION_UUID, index, FIXED_TS, 0L, 5000L);
		}
	}
}
