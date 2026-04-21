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

package io.evitadb.externalApi.grpc.services.subscriber;

import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.externalApi.grpc.generated.GrpcHeartBeat;
import io.evitadb.test.TestConstants;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCallStreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.InOrder;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the termination and cancellation-race semantics of
 * {@link AbstractChangeCaptureSubscriber}. Covers the single-shot finalisation CAS across every
 * termination path ({@link AbstractChangeCaptureSubscriber#onError},
 * {@link AbstractChangeCaptureSubscriber#onComplete}, {@link AbstractChangeCaptureSubscriber#close},
 * the heartbeat loop, and the transport-level
 * {@link ServerCallStreamObserver#setOnCancelHandler}/{@link ServerCallStreamObserver#setOnCloseHandler}
 * callbacks), the authoritative {@link ServerCallStreamObserver#isCancelled()} poll in the
 * heartbeat path, the deferred-cancel re-entrancy protection inside
 * {@link AbstractChangeCaptureSubscriber#onSubscribe}, and the Armeria request-timeout extension
 * on every successful emit.
 *
 * Tests drive a single heartbeat tick by calling the package-private
 * {@link AbstractChangeCaptureSubscriber#sendHeartbeat()} directly; the auto-schedule uses a
 * 5-minute heartbeat interval (via a 10-minute mocked request timeout) so the background scheduler
 * never fires during test execution.
 *
 * @author evitaDB
 */
@DisplayName("AbstractChangeCaptureSubscriber - lifecycle and cancellation races")
class AbstractChangeCaptureSubscriberTest implements TestConstants {

	/**
	 * Long enough that {@code heartBeatDelay} clamps to its 5-minute upper bound, so the
	 * auto-scheduled heartbeat never fires during a test.
	 */
	private static final long LONG_REQUEST_TIMEOUT_MILLIS = 600_000L;

	/**
	 * Matches the emitted ACK response produced by {@link TestSubscriber#buildAcknowledgementResponse}.
	 */
	private static final String ACK_PREFIX = "ACK:";
	/**
	 * Matches the emitted CHANGE response produced by {@link TestSubscriber#buildCaptureResponse}.
	 */
	private static final String MSG_PREFIX = "MSG:";
	/**
	 * Matches the emitted HEARTBEAT response produced by {@link TestSubscriber#buildHeartbeatResponse}.
	 */
	private static final String HB_PREFIX = "HB:";

	private Scheduler scheduler;
	@SuppressWarnings("unchecked")
	private final ServerCallStreamObserver<String> responseObserver = mock(ServerCallStreamObserver.class);
	private final Subscription subscription = mock(Subscription.class);
	private final ServiceRequestContext serviceContext = mock(ServiceRequestContext.class);
	private TestSubscriber subscriber;

	@BeforeEach
	void setUp() {
		this.scheduler = new Scheduler(
			ThreadPoolOptions.requestThreadPoolBuilder().minThreadCount(1).build()
		);
		when(this.serviceContext.requestTimeoutMillis()).thenReturn(LONG_REQUEST_TIMEOUT_MILLIS);
		this.subscriber = new TestSubscriber(this.scheduler, this.responseObserver, this.serviceContext);
	}

	@AfterEach
	void tearDown() {
		this.scheduler.shutdownNow();
	}

	@Nested
	@DisplayName("Finalization")
	class Finalization {

		@Test
		@DisplayName("close() emits UNAVAILABLE once and cancels subscription")
		void shouldEmitUnavailableOnceAndCancelSubscriptionWhenCloseCalled() {
			AbstractChangeCaptureSubscriberTest.this.subscriber.onSubscribe(AbstractChangeCaptureSubscriberTest.this.subscription);

			AbstractChangeCaptureSubscriberTest.this.subscriber.close();
			AbstractChangeCaptureSubscriberTest.this.subscriber.close();

			verify(AbstractChangeCaptureSubscriberTest.this.subscription, times(1)).cancel();
			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, times(1))
				.onError(argThat(AbstractChangeCaptureSubscriberTest::isUnavailableStatus));
		}

		@Test
		@DisplayName("close() before onSubscribe emits UNAVAILABLE and does not NPE")
		void shouldEmitUnavailableWhenCloseCalledBeforeOnSubscribe() {
			AbstractChangeCaptureSubscriberTest.this.subscriber.close();

			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, times(1))
				.onError(argThat(AbstractChangeCaptureSubscriberTest::isUnavailableStatus));
			verify(AbstractChangeCaptureSubscriberTest.this.subscription, never()).cancel();
		}

		@Test
		@DisplayName("onError emits the original throwable once and cancels subscription")
		void shouldEmitOriginalErrorOnceAndCancelSubscriptionWhenOnErrorCalled() {
			AbstractChangeCaptureSubscriberTest.this.subscriber.onSubscribe(AbstractChangeCaptureSubscriberTest.this.subscription);
			final RuntimeException cause = new RuntimeException("boom");

			AbstractChangeCaptureSubscriberTest.this.subscriber.onError(cause);
			AbstractChangeCaptureSubscriberTest.this.subscriber.onError(new RuntimeException("second"));
			AbstractChangeCaptureSubscriberTest.this.subscriber.close();

			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, times(1)).onError(cause);
			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, times(1)).onError(any());
			verify(AbstractChangeCaptureSubscriberTest.this.subscription, times(1)).cancel();
		}

		@Test
		@DisplayName("onComplete emits onCompleted once and cancels subscription")
		void shouldEmitOnCompletedOnceAndCancelSubscriptionWhenOnCompleteCalled() {
			AbstractChangeCaptureSubscriberTest.this.subscriber.onSubscribe(AbstractChangeCaptureSubscriberTest.this.subscription);

			AbstractChangeCaptureSubscriberTest.this.subscriber.onComplete();
			AbstractChangeCaptureSubscriberTest.this.subscriber.onComplete();
			AbstractChangeCaptureSubscriberTest.this.subscriber.close();

			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, times(1)).onCompleted();
			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, never()).onError(any());
			verify(AbstractChangeCaptureSubscriberTest.this.subscription, times(1)).cancel();
		}
	}

	@Nested
	@DisplayName("onNext")
	class OnNext {

		@Test
		@DisplayName("shortcircuits when stream already finalized")
		void shouldShortCircuitOnNextWhenStreamAlreadyFinalized() {
			AbstractChangeCaptureSubscriberTest.this.subscriber.onSubscribe(AbstractChangeCaptureSubscriberTest.this.subscription);
			AbstractChangeCaptureSubscriberTest.this.subscriber.close();

			AbstractChangeCaptureSubscriberTest.this.subscriber.onNext("ignored");

			// only the ACK from onSubscribe went through; nothing else was emitted
			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, times(1)).onNext(any());
		}

		@Test
		@DisplayName("finalizes subscriber when observer throws IllegalStateException")
		void shouldFinalizeWhenObserverThrowsIllegalStateOnOnNext() {
			AbstractChangeCaptureSubscriberTest.this.subscriber.onSubscribe(AbstractChangeCaptureSubscriberTest.this.subscription);
			doThrow(new IllegalStateException("call is closed"))
				.when(AbstractChangeCaptureSubscriberTest.this.responseObserver).onNext(argThat(hasPrefix(MSG_PREFIX)));

			AbstractChangeCaptureSubscriberTest.this.subscriber.onNext("item");

			verify(AbstractChangeCaptureSubscriberTest.this.subscription, times(1)).cancel();

			// subsequent onNext must be a no-op (finalized)
			AbstractChangeCaptureSubscriberTest.this.subscriber.onNext("later");
			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, times(2)).onNext(any());
		}

		@Test
		@DisplayName("finalizes subscriber when observer throws StatusRuntimeException(CANCELLED)")
		void shouldFinalizeWhenObserverThrowsStatusCancelledOnOnNext() {
			AbstractChangeCaptureSubscriberTest.this.subscriber.onSubscribe(AbstractChangeCaptureSubscriberTest.this.subscription);
			doThrow(Status.CANCELLED.asRuntimeException())
				.when(AbstractChangeCaptureSubscriberTest.this.responseObserver).onNext(argThat(hasPrefix(MSG_PREFIX)));

			AbstractChangeCaptureSubscriberTest.this.subscriber.onNext("item");

			verify(AbstractChangeCaptureSubscriberTest.this.subscription, times(1)).cancel();
		}

		@Test
		@DisplayName("finalizes subscriber and propagates StatusRuntimeException with non-CANCELLED status")
		void shouldFinalizeAndPropagateUnexpectedStatusRuntimeExceptionWhenOnNextFails() {
			AbstractChangeCaptureSubscriberTest.this.subscriber.onSubscribe(AbstractChangeCaptureSubscriberTest.this.subscription);
			doThrow(Status.INTERNAL.asRuntimeException())
				.when(AbstractChangeCaptureSubscriberTest.this.responseObserver).onNext(argThat(hasPrefix(MSG_PREFIX)));

			assertThrows(StatusRuntimeException.class, () -> AbstractChangeCaptureSubscriberTest.this.subscriber.onNext("item"));

			// emitOnNext finalizes the subscriber on any gRPC status error, not just CANCELLED —
			// the stream is broken and we must stop feeding a dead consumer even while the error
			// surfaces to the publisher.
			verify(AbstractChangeCaptureSubscriberTest.this.subscription, times(1)).cancel();

			// subsequent onNext must be a no-op (finalized)
			AbstractChangeCaptureSubscriberTest.this.subscriber.onNext("later");
			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, times(2)).onNext(any()); // only ACK + the failing MSG attempt
		}

		@Test
		@DisplayName("extends request timeout when item emit succeeds")
		void shouldExtendRequestTimeoutWhenItemEmitSucceeds() {
			AbstractChangeCaptureSubscriberTest.this.subscriber.onSubscribe(AbstractChangeCaptureSubscriberTest.this.subscription);

			AbstractChangeCaptureSubscriberTest.this.subscriber.onNext("item");

			verify(AbstractChangeCaptureSubscriberTest.this.serviceContext, times(1)).setRequestTimeout(
				eq(TimeoutMode.SET_FROM_NOW),
				eq(Duration.ofMillis(LONG_REQUEST_TIMEOUT_MILLIS))
			);
			// subscription.request(1) fires twice: once after ACK emits (onSubscribe),
			// once after MSG emits (onNext). Mockito's times(n) asserts the total count —
			// stating both times(1) and times(2) is contradictory, so assert the total only.
			verify(AbstractChangeCaptureSubscriberTest.this.subscription, times(2)).request(1);
		}

		@Test
		@DisplayName("does not extend request timeout when item emit fails")
		void shouldNotExtendRequestTimeoutWhenItemEmitFails() {
			AbstractChangeCaptureSubscriberTest.this.subscriber.onSubscribe(AbstractChangeCaptureSubscriberTest.this.subscription);
			doThrow(new IllegalStateException("call is closed"))
				.when(AbstractChangeCaptureSubscriberTest.this.responseObserver).onNext(argThat(hasPrefix(MSG_PREFIX)));

			AbstractChangeCaptureSubscriberTest.this.subscriber.onNext("item");

			verify(AbstractChangeCaptureSubscriberTest.this.serviceContext, never()).setRequestTimeout(any(), any());
		}
	}

	@Nested
	@DisplayName("Heartbeat")
	class Heartbeat {

		@Test
		@DisplayName("shortcircuits when stream already finalized without polling isCancelled()")
		void shouldShortCircuitHeartbeatWhenStreamAlreadyFinalized() {
			AbstractChangeCaptureSubscriberTest.this.subscriber.close();

			final long result = AbstractChangeCaptureSubscriberTest.this.subscriber.sendHeartbeat();

			assertEquals(-1L, result);
			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, never()).onNext(argThat(hasPrefix(HB_PREFIX)));
			// the streamFinalized shortcut must bypass the isCancelled() transport call —
			// avoids unnecessary work once the subscriber is already done
			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, never()).isCancelled();
		}

		@Test
		@DisplayName("stops and finalizes when observer.isCancelled() returns true")
		void shouldFinalizeWhenObserverIsCancelledBeforeEmit() {
			AbstractChangeCaptureSubscriberTest.this.subscriber.onSubscribe(AbstractChangeCaptureSubscriberTest.this.subscription);
			when(AbstractChangeCaptureSubscriberTest.this.responseObserver.isCancelled()).thenReturn(true);

			final long result = AbstractChangeCaptureSubscriberTest.this.subscriber.sendHeartbeat();

			assertEquals(-1L, result);
			verify(AbstractChangeCaptureSubscriberTest.this.subscription, times(1)).cancel();
			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, never()).onNext(argThat(hasPrefix(HB_PREFIX)));
		}

		@Test
		@DisplayName("stops and finalizes when emit throws IllegalStateException")
		void shouldFinalizeWhenHeartbeatEmitThrowsIllegalState() {
			AbstractChangeCaptureSubscriberTest.this.subscriber.onSubscribe(AbstractChangeCaptureSubscriberTest.this.subscription);
			doThrow(new IllegalStateException("call is closed"))
				.when(AbstractChangeCaptureSubscriberTest.this.responseObserver).onNext(argThat(hasPrefix(HB_PREFIX)));

			final long result = AbstractChangeCaptureSubscriberTest.this.subscriber.sendHeartbeat();

			assertEquals(-1L, result);
			verify(AbstractChangeCaptureSubscriberTest.this.subscription, times(1)).cancel();

			// subsequent onNext from the publisher must also be dropped (streamFinalized=true)
			AbstractChangeCaptureSubscriberTest.this.subscriber.onNext("late");
			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, never()).onNext(argThat(hasPrefix(MSG_PREFIX)));
		}

		@Test
		@DisplayName("reschedules at regular interval and extends request timeout on success")
		void shouldRescheduleAndExtendTimeoutWhenHeartbeatEmitSucceeds() {
			AbstractChangeCaptureSubscriberTest.this.subscriber.onSubscribe(AbstractChangeCaptureSubscriberTest.this.subscription);

			final long result = AbstractChangeCaptureSubscriberTest.this.subscriber.sendHeartbeat();

			assertEquals(0L, result);
			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, times(1)).onNext(argThat(hasPrefix(HB_PREFIX)));
			verify(AbstractChangeCaptureSubscriberTest.this.serviceContext, times(1)).setRequestTimeout(
				eq(TimeoutMode.SET_FROM_NOW),
				eq(Duration.ofMillis(LONG_REQUEST_TIMEOUT_MILLIS))
			);
		}

		@Test
		@DisplayName("does not extend request timeout when heartbeat emit fails")
		void shouldNotExtendRequestTimeoutWhenHeartbeatEmitFails() {
			AbstractChangeCaptureSubscriberTest.this.subscriber.onSubscribe(AbstractChangeCaptureSubscriberTest.this.subscription);
			doThrow(new IllegalStateException("call is closed"))
				.when(AbstractChangeCaptureSubscriberTest.this.responseObserver).onNext(argThat(hasPrefix(HB_PREFIX)));

			AbstractChangeCaptureSubscriberTest.this.subscriber.sendHeartbeat();

			verify(AbstractChangeCaptureSubscriberTest.this.serviceContext, never()).setRequestTimeout(any(), any());
		}

		@Test
		@DisplayName("heartbeat index increments monotonically across successive ticks")
		void shouldIncrementHeartbeatIndexMonotonicallyAcrossTicks() {
			AbstractChangeCaptureSubscriberTest.this.subscriber.onSubscribe(AbstractChangeCaptureSubscriberTest.this.subscription); // consumes index 0

			AbstractChangeCaptureSubscriberTest.this.subscriber.sendHeartbeat();
			AbstractChangeCaptureSubscriberTest.this.subscriber.sendHeartbeat();
			AbstractChangeCaptureSubscriberTest.this.subscriber.sendHeartbeat();

			final ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, times(4)).onNext(captor.capture());
			final List<String> heartbeats = captor.getAllValues().stream()
				.filter(r -> r.startsWith(HB_PREFIX))
				.toList();
			assertEquals(List.of("HB:1", "HB:2", "HB:3"), heartbeats);
		}
	}

	@Nested
	@DisplayName("onSubscribe")
	class OnSubscribe {

		@Test
		@DisplayName("emits ACK and requests one item on success")
		void shouldEmitAckAndRequestOneItemWhenOnSubscribeSucceeds() {
			AbstractChangeCaptureSubscriberTest.this.subscriber.onSubscribe(AbstractChangeCaptureSubscriberTest.this.subscription);

			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, times(1)).onNext(argThat(hasPrefix(ACK_PREFIX)));
			verify(AbstractChangeCaptureSubscriberTest.this.subscription, times(1)).request(1);
		}

		@Test
		@DisplayName("defers subscription cancel asynchronously when ACK emit fails")
		void shouldDeferSubscriptionCancelWhenAckEmitFailsWithClosedStream() throws Exception {
			final Thread callerThread = Thread.currentThread();
			final AtomicReference<Thread> cancelThread = new AtomicReference<>();
			final CountDownLatch cancelLatch = new CountDownLatch(1);
			doAnswer(inv -> {
				cancelThread.set(Thread.currentThread());
				cancelLatch.countDown();
				return null;
			}).when(AbstractChangeCaptureSubscriberTest.this.subscription).cancel();

			doThrow(new IllegalStateException("call is closed"))
				.when(AbstractChangeCaptureSubscriberTest.this.responseObserver).onNext(any());

			AbstractChangeCaptureSubscriberTest.this.subscriber.onSubscribe(AbstractChangeCaptureSubscriberTest.this.subscription);

			assertTrue(
				cancelLatch.await(2, TimeUnit.SECONDS),
				"subscription.cancel should fire asynchronously within 2 seconds"
			);
			// assertion runs on the outer test thread so diagnostic failure is clear;
			// re-entrancy protection requires the cancel to happen OFF the caller thread
			// (onSubscribe in production runs inside ConcurrentHashMap.computeIfAbsent).
			assertNotSame(
				callerThread, cancelThread.get(),
				"subscription.cancel must be deferred off the caller thread"
			);
		}

		@Test
		@DisplayName("does not request more items when ACK emit fails")
		void shouldNotRequestItemsWhenAckEmitFails() {
			doThrow(new IllegalStateException("call is closed"))
				.when(AbstractChangeCaptureSubscriberTest.this.responseObserver).onNext(any());

			AbstractChangeCaptureSubscriberTest.this.subscriber.onSubscribe(AbstractChangeCaptureSubscriberTest.this.subscription);

			verify(AbstractChangeCaptureSubscriberTest.this.subscription, never()).request(1);
		}

		@Test
		@DisplayName("second onSubscribe cancels the extraneous subscription and keeps the first active")
		void shouldCancelExtraneousSubscriptionOnSecondOnSubscribe() {
			final Subscription second = mock(Subscription.class);

			AbstractChangeCaptureSubscriberTest.this.subscriber.onSubscribe(AbstractChangeCaptureSubscriberTest.this.subscription);
			AbstractChangeCaptureSubscriberTest.this.subscriber.onSubscribe(second);

			// defensive guard (Rule 2.12) — extraneous subscription cancelled, original intact
			verify(second, times(1)).cancel();
			verify(AbstractChangeCaptureSubscriberTest.this.subscription, never()).cancel();
			// only one ACK was sent (from the first onSubscribe)
			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, times(1)).onNext(argThat(hasPrefix(ACK_PREFIX)));
			verify(AbstractChangeCaptureSubscriberTest.this.subscription, times(1)).request(1);
			verify(second, never()).request(1);
		}

		@Test
		@DisplayName("onSubscribe after transport close cancels the late subscription without emitting ACK")
		void shouldCancelLateSubscriptionWhenOnSubscribeArrivesAfterTransportClose() {
			// Transport closes BEFORE the publisher delivers onSubscribe.
			final Runnable onCloseHandler = captureOnCloseHandler();
			onCloseHandler.run();

			AbstractChangeCaptureSubscriberTest.this.subscriber.onSubscribe(AbstractChangeCaptureSubscriberTest.this.subscription);

			// The late subscription is cancelled; no ACK is pushed onto the already-dead stream.
			verify(AbstractChangeCaptureSubscriberTest.this.subscription, times(1)).cancel();
			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, never()).onNext(argThat(hasPrefix(ACK_PREFIX)));
			verify(AbstractChangeCaptureSubscriberTest.this.subscription, never()).request(1);
		}
	}

	@Nested
	@DisplayName("Transport handlers")
	class TransportHandlers {

		@Test
		@DisplayName("registers both transport handlers before scheduling the heartbeat")
		void shouldRegisterBothTransportHandlersInConstructor() {
			// setUp already created a fresh subscriber; assert both were set
			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, times(1)).setOnCancelHandler(any(Runnable.class));
			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, times(1)).setOnCloseHandler(any(Runnable.class));
		}

		@Test
		@DisplayName("registers onCancelHandler before onCloseHandler (pinned order)")
		void shouldRegisterOnCancelBeforeOnCloseHandler() {
			// pins the order documented in AbstractChangeCaptureSubscriber's constructor:
			// onCancel then onClose, so a change in registration order is flagged for review.
			final InOrder order = inOrder(AbstractChangeCaptureSubscriberTest.this.responseObserver);
			order.verify(AbstractChangeCaptureSubscriberTest.this.responseObserver).setOnCancelHandler(any(Runnable.class));
			order.verify(AbstractChangeCaptureSubscriberTest.this.responseObserver).setOnCloseHandler(any(Runnable.class));
		}

		@Test
		@DisplayName("onCancelHandler firing cancels subscription, finalizes the stream, and suppresses subsequent close()")
		void shouldFinalizeSubscriberWhenOnCancelHandlerFires() {
			final Runnable onCancelHandler = captureOnCancelHandler();
			AbstractChangeCaptureSubscriberTest.this.subscriber.onSubscribe(AbstractChangeCaptureSubscriberTest.this.subscription);

			onCancelHandler.run();

			verify(AbstractChangeCaptureSubscriberTest.this.subscription, times(1)).cancel();

			// subsequent onNext must be dropped
			AbstractChangeCaptureSubscriberTest.this.subscriber.onNext("dropped");
			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, never()).onNext(argThat(hasPrefix(MSG_PREFIX)));

			// subsequent close() must NOT emit UNAVAILABLE — stream is already finalized
			AbstractChangeCaptureSubscriberTest.this.subscriber.close();
			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, never()).onError(any());
		}

		@Test
		@DisplayName("onCloseHandler firing cancels subscription and finalizes the stream")
		void shouldFinalizeSubscriberWhenOnCloseHandlerFires() {
			final Runnable onCloseHandler = captureOnCloseHandler();
			AbstractChangeCaptureSubscriberTest.this.subscriber.onSubscribe(AbstractChangeCaptureSubscriberTest.this.subscription);

			onCloseHandler.run();

			verify(AbstractChangeCaptureSubscriberTest.this.subscription, times(1)).cancel();
			AbstractChangeCaptureSubscriberTest.this.subscriber.close();
			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, never()).onError(any());
		}

		@Test
		@DisplayName("subscription cancelled at most once when all handlers and close() fire")
		void shouldCancelSubscriptionAtMostOnceWhenAllHandlersAndCloseFire() {
			final Runnable onCancelHandler = captureOnCancelHandler();
			final Runnable onCloseHandler = captureOnCloseHandler();
			AbstractChangeCaptureSubscriberTest.this.subscriber.onSubscribe(AbstractChangeCaptureSubscriberTest.this.subscription);

			onCancelHandler.run();
			onCloseHandler.run();
			AbstractChangeCaptureSubscriberTest.this.subscriber.close();

			verify(AbstractChangeCaptureSubscriberTest.this.subscription, times(1)).cancel();
			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, never()).onError(any());
		}
	}

	@Nested
	@DisplayName("Post-terminal behaviour")
	class PostTerminal {

		@Test
		@DisplayName("onNext after onComplete is dropped")
		void shouldDropOnNextAfterOnComplete() {
			AbstractChangeCaptureSubscriberTest.this.subscriber.onSubscribe(AbstractChangeCaptureSubscriberTest.this.subscription);
			AbstractChangeCaptureSubscriberTest.this.subscriber.onComplete();

			AbstractChangeCaptureSubscriberTest.this.subscriber.onNext("late");

			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, times(1)).onNext(argThat(hasPrefix(ACK_PREFIX)));
			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, never()).onNext(argThat(hasPrefix(MSG_PREFIX)));
			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, times(1)).onCompleted();
		}

		@Test
		@DisplayName("onNext after onError is dropped")
		void shouldDropOnNextAfterOnError() {
			AbstractChangeCaptureSubscriberTest.this.subscriber.onSubscribe(AbstractChangeCaptureSubscriberTest.this.subscription);
			AbstractChangeCaptureSubscriberTest.this.subscriber.onError(new RuntimeException("boom"));

			AbstractChangeCaptureSubscriberTest.this.subscriber.onNext("late");

			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, never()).onNext(argThat(hasPrefix(MSG_PREFIX)));
			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, times(1)).onError(any());
		}

		@Test
		@DisplayName("onError after onComplete is suppressed")
		void shouldSuppressOnErrorAfterOnComplete() {
			AbstractChangeCaptureSubscriberTest.this.subscriber.onSubscribe(AbstractChangeCaptureSubscriberTest.this.subscription);
			AbstractChangeCaptureSubscriberTest.this.subscriber.onComplete();

			AbstractChangeCaptureSubscriberTest.this.subscriber.onError(new RuntimeException("boom"));

			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, times(1)).onCompleted();
			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, never()).onError(any());
		}

		@Test
		@DisplayName("onComplete after onError is suppressed")
		void shouldSuppressOnCompleteAfterOnError() {
			AbstractChangeCaptureSubscriberTest.this.subscriber.onSubscribe(AbstractChangeCaptureSubscriberTest.this.subscription);
			final RuntimeException cause = new RuntimeException("boom");
			AbstractChangeCaptureSubscriberTest.this.subscriber.onError(cause);

			AbstractChangeCaptureSubscriberTest.this.subscriber.onComplete();

			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, times(1)).onError(cause);
			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, never()).onCompleted();
		}

		@Test
		@DisplayName("heartbeat after onError short-circuits")
		void shouldShortCircuitHeartbeatAfterOnError() {
			AbstractChangeCaptureSubscriberTest.this.subscriber.onSubscribe(AbstractChangeCaptureSubscriberTest.this.subscription);
			AbstractChangeCaptureSubscriberTest.this.subscriber.onError(new RuntimeException("boom"));

			final long result = AbstractChangeCaptureSubscriberTest.this.subscriber.sendHeartbeat();

			assertEquals(-1L, result);
			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, never()).onNext(argThat(hasPrefix(HB_PREFIX)));
			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, never()).isCancelled();
		}
	}

	@Nested
	@DisplayName("Concurrent finalization")
	class Races {

		/**
		 * Deterministic variant: heartbeat emit is healthy, so {@code close()} always wins the
		 * CAS. Uses threads purely to exercise the concurrent code path; the outcome is stable.
		 */
		@RepeatedTest(10)
		@DisplayName("close wins CAS over healthy heartbeat and emits UNAVAILABLE exactly once")
		void shouldEmitUnavailableExactlyOnceWhenCloseRacesWithHealthyHeartbeat() throws Exception {
			AbstractChangeCaptureSubscriberTest.this.subscriber.onSubscribe(AbstractChangeCaptureSubscriberTest.this.subscription);

			runRace(AbstractChangeCaptureSubscriberTest.this.subscriber::close, AbstractChangeCaptureSubscriberTest.this.subscriber::sendHeartbeat);

			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, times(1))
				.onError(argThat(AbstractChangeCaptureSubscriberTest::isUnavailableStatus));
			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, never()).onCompleted();
			verify(AbstractChangeCaptureSubscriberTest.this.subscription, times(1)).cancel();
		}

		/**
		 * Invariant variant: heartbeat emit throws, so both paths try to finalize — only one
		 * may win the CAS. Asserts the invariant that holds under every scheduling.
		 */
		@RepeatedTest(10)
		@DisplayName("at most one terminal frame emits when close races with failing heartbeat")
		void shouldEmitAtMostOneTerminalFrameWhenCloseRacesWithFailingHeartbeat() throws Exception {
			AbstractChangeCaptureSubscriberTest.this.subscriber.onSubscribe(AbstractChangeCaptureSubscriberTest.this.subscription);
			doThrow(new IllegalStateException("call is closed"))
				.when(AbstractChangeCaptureSubscriberTest.this.responseObserver).onNext(argThat(hasPrefix(HB_PREFIX)));

			runRace(AbstractChangeCaptureSubscriberTest.this.subscriber::close, AbstractChangeCaptureSubscriberTest.this.subscriber::sendHeartbeat);

			// either close wins (one UNAVAILABLE) or the heartbeat wins (zero terminal frames);
			// never two, never onCompleted, and any onError must carry UNAVAILABLE status.
			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, atMost(1)).onError(any());
			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, atMost(1))
				.onError(argThat(AbstractChangeCaptureSubscriberTest::isUnavailableStatus));
			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, never())
				.onError(argThat(t -> !isUnavailableStatus(t)));
			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, never()).onCompleted();
			verify(AbstractChangeCaptureSubscriberTest.this.subscription, times(1)).cancel();
		}

		@RepeatedTest(10)
		@DisplayName("two concurrent close() calls emit UNAVAILABLE exactly once")
		void shouldEmitUnavailableExactlyOnceWhenTwoCloseCallsRace() throws Exception {
			AbstractChangeCaptureSubscriberTest.this.subscriber.onSubscribe(AbstractChangeCaptureSubscriberTest.this.subscription);

			runRace(AbstractChangeCaptureSubscriberTest.this.subscriber::close, AbstractChangeCaptureSubscriberTest.this.subscriber::close);

			verify(AbstractChangeCaptureSubscriberTest.this.responseObserver, times(1))
				.onError(argThat(AbstractChangeCaptureSubscriberTest::isUnavailableStatus));
			verify(AbstractChangeCaptureSubscriberTest.this.subscription, times(1)).cancel();
		}
	}

	/**
	 * Runs two runnables on separate threads rendezvoused via a {@link CyclicBarrier}. Asserts
	 * both threads terminate within 5 seconds, surfaces uncaught exceptions from either thread
	 * as an {@link AssertionError} on the caller, and guarantees hung-thread diagnostics rather
	 * than silent {@code join} returns.
	 */
	private static void runRace(@Nonnull Runnable a, @Nonnull Runnable b) throws InterruptedException {
		final CyclicBarrier barrier = new CyclicBarrier(2);
		final AtomicReference<Throwable> uncaught = new AtomicReference<>();
		final Thread.UncaughtExceptionHandler handler = (thread, ex) -> uncaught.compareAndSet(null, ex);

		final Thread t1 = new Thread(() -> { awaitQuietly(barrier); a.run(); }, "race-a");
		final Thread t2 = new Thread(() -> { awaitQuietly(barrier); b.run(); }, "race-b");
		t1.setUncaughtExceptionHandler(handler);
		t2.setUncaughtExceptionHandler(handler);

		t1.start();
		t2.start();
		t1.join(5_000);
		t2.join(5_000);

		assertFalse(t1.isAlive(), "race-a did not terminate within 5s");
		assertFalse(t2.isAlive(), "race-b did not terminate within 5s");
		final Throwable thrown = uncaught.get();
		if (thrown != null) {
			throw new AssertionError("race thread threw uncaught exception", thrown);
		}
	}

	/**
	 * Captures the {@link Runnable} registered via {@link ServerCallStreamObserver#setOnCancelHandler}.
	 */
	@Nonnull
	private Runnable captureOnCancelHandler() {
		final ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
		verify(this.responseObserver).setOnCancelHandler(captor.capture());
		return captor.getValue();
	}

	/**
	 * Captures the {@link Runnable} registered via {@link ServerCallStreamObserver#setOnCloseHandler}.
	 */
	@Nonnull
	private Runnable captureOnCloseHandler() {
		final ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
		verify(this.responseObserver).setOnCloseHandler(captor.capture());
		return captor.getValue();
	}

	/**
	 * Returns `true` if the given throwable carries a {@link Status#UNAVAILABLE} code.
	 */
	private static boolean isUnavailableStatus(@Nullable Throwable throwable) {
		return throwable instanceof StatusRuntimeException sre
			&& sre.getStatus() != null
			&& sre.getStatus().getCode() == Status.Code.UNAVAILABLE;
	}

	/**
	 * Mockito argument matcher: matches a non-null string starting with `prefix`. Returns
	 * {@link ArgumentMatcher} (not {@link java.util.function.Predicate}) so it is directly
	 * usable with {@link org.mockito.ArgumentMatchers#argThat}.
	 */
	@Nonnull
	private static ArgumentMatcher<String> hasPrefix(@Nonnull String prefix) {
		return s -> s != null && s.startsWith(prefix);
	}

	/**
	 * Barrier rendezvous that surfaces timeouts as a clear runtime failure.
	 */
	private static void awaitQuietly(@Nonnull CyclicBarrier barrier) {
		try {
			barrier.await(5, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new RuntimeException("barrier rendezvous failed", e);
		}
	}

	/**
	 * Minimal concrete subclass of {@link AbstractChangeCaptureSubscriber} used by the tests.
	 * Emits string payloads with distinct prefixes so Mockito matchers can distinguish ACK vs.
	 * CHANGE vs. HEARTBEAT responses without depending on protobuf internals.
	 */
	private static class TestSubscriber extends AbstractChangeCaptureSubscriber<String, String> {

		TestSubscriber(
			@Nonnull Scheduler scheduler,
			@Nonnull ServerCallStreamObserver<String> responseObserver,
			@Nonnull ServiceRequestContext serviceContext
		) {
			super(scheduler, TEST_CATALOG, "test-cdc-heartbeat", responseObserver, () -> 1L, serviceContext);
		}

		@Nonnull
		@Override
		protected String buildAcknowledgementResponse(
			@Nullable UUID subscriptionId,
			@Nonnull GrpcHeartBeat heartBeat
		) {
			return "ACK:" + subscriptionId;
		}

		@Nonnull
		@Override
		protected String buildCaptureResponse(@Nonnull String capture) {
			return "MSG:" + capture;
		}

		@Nonnull
		@Override
		protected String buildHeartbeatResponse(
			@Nullable UUID subscriptionId,
			@Nonnull GrpcHeartBeat heartBeat
		) {
			return "HB:" + heartBeat.getIndex();
		}
	}
}
