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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.DRIVER;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.STREAM;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
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

	// ---------------------------------------------------------------------------------------------
	// Test fixtures
	// ---------------------------------------------------------------------------------------------

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
	 * Recording delegate subscriber — captures `onSubscribe`/`onNext`/`onError`/`onComplete`
	 * invocations and requests a fixed number of items at subscription time.
	 */
	private static final class RecordingSubscriber implements Flow.Subscriber<ChangeSystemCapture> {
		private final long initialRequest;
		final List<ChangeSystemCapture> received = new ArrayList<>();
		final AtomicReference<Throwable> lastError = new AtomicReference<>();

		RecordingSubscriber(long initialRequest) {
			this.initialRequest = initialRequest;
		}

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			if (this.initialRequest > 0) {
				subscription.request(this.initialRequest);
			}
		}

		@Override
		public void onNext(ChangeSystemCapture item) {
			this.received.add(item);
		}

		@Override
		public void onError(Throwable throwable) {
			this.lastError.set(throwable);
		}

		@Override
		public void onComplete() {
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
	 * test reads cleanly. `start()` performs `subscribe`, which synchronously triggers
	 * the supplied stream initializer.
	 */
	private static final class TestHarness {
		final ClientCallStreamObserver<Object> observer;
		final RecordingSubscriber delegate;
		private final SystemCapturePublisher publisher;
		private final AtomicReference<ClientResponseObserver<Object, Object>> subscriberRef =
			new AtomicReference<>();

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
		@SuppressWarnings("unchecked")
		TestHarness(long delegateInitialRequest, boolean deliverAckDuringInit) {
			this.observer = (ClientCallStreamObserver<Object>) mock(ClientCallStreamObserver.class);
			this.delegate = new RecordingSubscriber(delegateInitialRequest);
			this.publisher = new SystemCapturePublisher(
				QUEUE_SIZE,
				new SynchronousExecutorService(),
				subscriber -> {
					this.subscriberRef.set(subscriber);
					subscriber.beforeStart(this.observer);
					if (deliverAckDuringInit) {
						// fire the ACK from the initializer itself — must NOT NPE
						subscriber.onNext(buildHeartbeat(0));
					}
				}
			);
		}

		void start() {
			this.publisher.subscribe(this.delegate);
		}

		void deliverAck() {
			// first message: ACKNOWLEDGEMENT — its presence sets the subscription id
			this.subscriberRef.get().onNext(buildHeartbeat(0));
		}

		void deliverHeartbeat(long index) {
			this.subscriberRef.get().onNext(buildHeartbeat(index));
		}

		void deliverCapture(int payload) {
			this.subscriberRef.get().onNext(
				new ChangeSystemCapture(payload, payload, FIXED_TS, Operation.UPSERT, null)
			);
		}

		private static HeartBeat buildHeartbeat(long index) {
			return new HeartBeat(SUBSCRIPTION_UUID, index, FIXED_TS, 0L, 5000L);
		}
	}
}
