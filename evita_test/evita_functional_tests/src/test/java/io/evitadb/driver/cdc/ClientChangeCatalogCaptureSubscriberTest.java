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

import io.evitadb.api.exception.ChangeCaptureResumePositionInvalidException;
import io.evitadb.api.exception.ChangeCaptureResumePositionInvalidException.Reason;
import io.evitadb.api.exception.TemporalDataNotAvailableException;
import io.evitadb.api.requestResponse.cdc.CaptureArea;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.grpc.generated.GrpcCaptureResponseType;
import io.evitadb.externalApi.grpc.generated.GrpcHeartBeat;
import io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeCatalogCaptureRequest;
import io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeCatalogCaptureResponse;
import io.evitadb.externalApi.grpc.requestResponse.cdc.ChangeCaptureConverter;
import io.evitadb.externalApi.grpc.services.interceptors.GlobalExceptionHandlerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toGrpcOffsetDateTime;
import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toGrpcUuid;
import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.DRIVER;
import static io.evitadb.test.TestTags.GRPC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Verifies how a catalog change capture stream of the Java driver learns the catalog incarnation it is bound to
 * and keeps the consumer's resume position honest about it - without a server: the streams are fed hand-built
 * acknowledgements, captures and error statuses, the way gRPC delivers them on the inbound thread.
 *
 * The acknowledgement of a server that predates the identity carries none, and such a server ignored the
 * identity the request expected. The driver must then compare it with the catalog of the session it registered
 * the stream in, refuse the subscription before a single capture is granted, and otherwise stamp the captures with
 * that session's identity. And because one publisher serves several streams, each registered separately, the
 * identity is a property of the stream: two streams of one publisher registered in different incarnations must not
 * stamp each other's captures.
 *
 * Only the gRPC call observer is a mock - it stands for the transport, which these tests do not have.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Catalog change capture stream identity in the Java driver")
@Tag(DRIVER)
@Tag(GRPC)
@Tag(CDC)
class ClientChangeCatalogCaptureSubscriberTest {
	private static final int QUEUE_SIZE = 4;
	private static final UUID SUBSCRIPTION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID INCARNATION_A = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");
	private static final UUID INCARNATION_B = UUID.fromString("bbbbbbbb-0000-4000-8000-000000000002");
	private static final OffsetDateTime TIMESTAMP = OffsetDateTime.of(2026, 10, 8, 14, 0, 0, 0, ZoneOffset.UTC);
	/**
	 * Catalog version the acknowledgement heartbeat reports - a refusal by the driver reports it as the current
	 * version of the catalog.
	 */
	private static final long ACKNOWLEDGED_VERSION = 17L;

	/**
	 * Builds the acknowledgement a server sends, with or without the identity of the incarnation.
	 *
	 * @param catalogId the identity, `null` for a server that predates it
	 * @return the acknowledgement response
	 */
	@Nonnull
	private static GrpcRegisterChangeCatalogCaptureResponse acknowledgement(@Nullable UUID catalogId) {
		final GrpcRegisterChangeCatalogCaptureResponse.Builder builder = GrpcRegisterChangeCatalogCaptureResponse
			.newBuilder()
			.setResponseType(GrpcCaptureResponseType.ACKNOWLEDGEMENT)
			.setUuid(toGrpcUuid(SUBSCRIPTION_ID))
			.setHeartBeat(
				GrpcHeartBeat.newBuilder()
					.setIndex(0)
					.setTimestamp(toGrpcOffsetDateTime(TIMESTAMP))
					.setLastObservedVersion(ACKNOWLEDGED_VERSION)
					.setMillisToNextHeartbeat(5000L)
			);
		if (catalogId != null) {
			builder.setCatalogId(toGrpcUuid(catalogId));
		}
		return builder.build();
	}

	/**
	 * Builds a change response carrying a capture of the passed version - as on the wire, without any identity.
	 *
	 * @param version the catalog version of the capture
	 * @return the change response
	 */
	@Nonnull
	private static GrpcRegisterChangeCatalogCaptureResponse change(long version) {
		return GrpcRegisterChangeCatalogCaptureResponse
			.newBuilder()
			.setResponseType(GrpcCaptureResponseType.CHANGE)
			.setCapture(
				ChangeCaptureConverter.toGrpcChangeCatalogCapture(
					new ChangeCatalogCapture(
						null, version, 0, TIMESTAMP, CaptureArea.DATA, "product", 1, Operation.UPSERT, null
					),
					null
				)
			)
			.build();
	}

	/**
	 * Builds a request resuming after version 40 of the passed incarnation.
	 *
	 * @param expectedCatalogId the identity the request expects, `null` for none
	 * @return the request
	 */
	@Nonnull
	private static ChangeCatalogCaptureRequest resumeRequest(@Nullable UUID expectedCatalogId) {
		return ChangeCatalogCaptureRequest.builder()
			.catalogId(expectedCatalogId)
			.sinceVersion(40L)
			.sinceIndex(3)
			.build();
	}

	/**
	 * Returns the error a client receives when the server fails the stream with the passed exception - mapped
	 * exactly as the server maps it, and stripped of the server-side cause, which never crosses the wire. Keeping
	 * the cause would let a test pass by reading the original exception out of the cause chain instead of
	 * rebuilding it from the error details.
	 *
	 * @param exception the exception raised on the server
	 * @return the status exception the client observer receives
	 */
	@Nonnull
	private static Throwable sentByServer(@Nonnull Throwable exception) {
		final AtomicReference<Throwable> emitted = new AtomicReference<>();
		GlobalExceptionHandlerInterceptor.sendErrorToClient(
			exception,
			new StreamObserver<Object>() {
				@Override
				public void onNext(Object value) {
					throw new AssertionError("No message expected.");
				}

				@Override
				public void onError(Throwable throwable) {
					emitted.set(throwable);
				}

				@Override
				public void onCompleted() {
					throw new AssertionError("No completion expected.");
				}
			}
		);
		final StatusRuntimeException serverSide = assertInstanceOf(StatusRuntimeException.class, emitted.get());
		return new StatusRuntimeException(serverSide.getStatus().withCause(null), serverSide.getTrailers());
	}

	@Nested
	@DisplayName("Acknowledgement of a server that predates the identity")
	class OlderServer {

		@Test
		@DisplayName("should accept the stream and stamp captures with the registering session's identity")
		void shouldStampCapturesWithRegisteringSessionIdentityWhenExpectationMatches() {
			final Harness harness = new Harness(resumeRequest(INCARNATION_A));
			final Stream stream = harness.subscribe(INCARNATION_A);

			stream.deliver(acknowledgement(null));
			stream.awaitSubscribed();
			stream.deliver(change(41L));

			assertNull(stream.subscribeFailure.get());
			verify(stream.observer, times(1)).request(QUEUE_SIZE);
			assertEquals(1, stream.delegate.received.size());
			assertEquals(INCARNATION_A, stream.delegate.received.get(0).catalogId());
			assertEquals(41L, stream.delegate.received.get(0).version());
		}

		@Test
		@DisplayName("should stamp captures with the registering session's identity when none is expected")
		void shouldStampCapturesWithRegisteringSessionIdentityWhenNothingExpected() {
			final Harness harness = new Harness(resumeRequest(null));
			final Stream stream = harness.subscribe(INCARNATION_B);

			stream.deliver(acknowledgement(null));
			stream.awaitSubscribed();
			stream.deliver(change(41L));

			assertNull(stream.subscribeFailure.get());
			assertEquals(INCARNATION_B, stream.delegate.received.get(0).catalogId());
		}

		@Test
		@DisplayName("should refuse the stream before granting any capture when the expected identity differs")
		void shouldRefuseStreamWhenExpectedIdentityDiffersFromRegisteringSession() {
			final Harness harness = new Harness(resumeRequest(INCARNATION_A));
			final Stream stream = harness.subscribe(INCARNATION_B);

			stream.deliver(acknowledgement(null));
			stream.awaitSubscribed();

			final ChangeCaptureResumePositionInvalidException refusal = assertInstanceOf(
				ChangeCaptureResumePositionInvalidException.class, stream.subscribeFailure.get(),
				"subscribe() must fail with the refusal itself, not with a wrapped internal error"
			);
			assertEquals(Reason.DIFFERENT_INCARNATION, refusal.getReason());
			assertEquals(INCARNATION_B, refusal.getCatalogId());
			assertEquals(ACKNOWLEDGED_VERSION, refusal.getCurrentCatalogVersion());
			assertNull(refusal.getCatalogVersion(), "an older server does not report its retention floor");
			assertEquals(INCARNATION_A, refusal.getRequestedCatalogId());
			assertEquals(40L, refusal.getRequestedSinceVersion());
			assertEquals(3, refusal.getRequestedSinceIndex());
			// the consumer is told through its own channel as well, with the very same exception
			assertSame(refusal, stream.delegate.error.get());
			// no capture window was ever opened, and the stream the server still believes open is cancelled
			verify(stream.observer, never()).request(QUEUE_SIZE);
			verify(stream.observer, times(1)).cancel(anyString(), any());
		}

	}

	@Nested
	@DisplayName("Acknowledgement naming the identity")
	class NewerServer {

		@Test
		@DisplayName("should stamp captures with the identity the server acknowledged")
		void shouldStampCapturesWithAcknowledgedIdentity() {
			// the server checked the expectation itself and names the incarnation it bound the stream to - its word
			// is taken even over the driver's own view of the registering session
			final Harness harness = new Harness(resumeRequest(null));
			final Stream stream = harness.subscribe(INCARNATION_A);

			stream.deliver(acknowledgement(INCARNATION_B));
			stream.awaitSubscribed();
			stream.deliver(change(41L));

			assertNull(stream.subscribeFailure.get());
			assertEquals(INCARNATION_B, stream.delegate.received.get(0).catalogId());
		}

	}

	@Nested
	@DisplayName("Streams of one publisher")
	class SharedPublisher {

		@Test
		@DisplayName("should keep the identity of each stream to itself")
		void shouldKeepIdentityPerStream() {
			// the second stream is registered in a session re-opened by name after the catalog was replaced -
			// the same publisher, a different incarnation
			final Harness harness = new Harness(resumeRequest(null));
			final Stream first = harness.subscribe(INCARNATION_A);
			first.deliver(acknowledgement(null));
			first.awaitSubscribed();
			final Stream second = harness.subscribe(INCARNATION_B);
			second.deliver(acknowledgement(null));
			second.awaitSubscribed();

			first.deliver(change(41L));
			second.deliver(change(7L));
			first.deliver(change(42L));

			assertEquals(
				List.of(INCARNATION_A, INCARNATION_A),
				first.delegate.received.stream().map(ChangeCatalogCapture::catalogId).toList()
			);
			assertEquals(
				List.of(INCARNATION_B),
				second.delegate.received.stream().map(ChangeCatalogCapture::catalogId).toList()
			);
		}

	}

	@Nested
	@DisplayName("Errors sent by the server")
	class ServerErrors {

		@Test
		@DisplayName("should fail subscribe() and the consumer with the refusal the server sent before acknowledging")
		void shouldSurfaceTypedRefusalBeforeAcknowledgement() {
			final ChangeCaptureResumePositionInvalidException serverRefusal = new ChangeCaptureResumePositionInvalidException(
				Reason.AHEAD_OF_CATALOG, INCARNATION_A, 5L, 1L, null, 40L, 3
			);
			final Harness harness = new Harness(resumeRequest(null));
			final Stream stream = harness.subscribe(INCARNATION_A);

			stream.fail(sentByServer(serverRefusal));
			stream.awaitSubscribed();

			final ChangeCaptureResumePositionInvalidException surfaced = assertInstanceOf(
				ChangeCaptureResumePositionInvalidException.class, stream.subscribeFailure.get()
			);
			assertEquals(Reason.AHEAD_OF_CATALOG, surfaced.getReason());
			assertEquals(serverRefusal.getPublicMessage(), surfaced.getPublicMessage());
			assertSame(surfaced, stream.delegate.error.get());
		}

		@Test
		@DisplayName("should deliver the retention failure the server sent mid-stream to the consumer as itself")
		void shouldDeliverTypedFailureAfterAcknowledgement() {
			final Harness harness = new Harness(resumeRequest(null));
			final Stream stream = harness.subscribe(INCARNATION_A);
			stream.deliver(acknowledgement(INCARNATION_A));
			stream.awaitSubscribed();

			stream.fail(sentByServer(new TemporalDataNotAvailableException(9L)));

			final TemporalDataNotAvailableException delivered =
				assertInstanceOf(TemporalDataNotAvailableException.class, stream.delegate.error.get());
			assertEquals(9L, delivered.getCatalogVersion());
		}

		@Test
		@DisplayName("should keep wrapping a transport failure before the acknowledgement as before")
		void shouldKeepWrappingTransportFailure() {
			final Harness harness = new Harness(resumeRequest(null));
			final Stream stream = harness.subscribe(INCARNATION_A);

			stream.fail(Status.UNAVAILABLE.withDescription("connection lost").asRuntimeException());
			stream.awaitSubscribed();

			assertInstanceOf(GenericEvitaInternalError.class, stream.subscribeFailure.get());
			assertFalse(stream.delegate.error.get() instanceof TemporalDataNotAvailableException);
		}

	}

	/**
	 * A catalog change capture publisher whose stream initializer binds the identity it is told to - standing for
	 * the session the driver registers the stream in - and hands the stream over to the test instead of a server.
	 */
	private static final class Harness {
		private final ClientChangeCatalogCaptureProcessor publisher;
		private final AtomicReference<UUID> nextRegisteringCatalogId = new AtomicReference<>();
		private final AtomicReference<ClientChangeCatalogCaptureSubscriber> lastInitialized = new AtomicReference<>();
		private final AtomicReference<CountDownLatch> initialized = new AtomicReference<>();
		private final AtomicReference<ClientCallStreamObserver<GrpcRegisterChangeCatalogCaptureRequest>> nextObserver =
			new AtomicReference<>();

		Harness(@Nonnull ChangeCatalogCaptureRequest request) {
			this.publisher = new ClientChangeCatalogCaptureProcessor(
				QUEUE_SIZE,
				Duration.ofSeconds(30),
				new SynchronousExecutorService(),
				request,
				subscriber -> {
					// what the session does: bind its identity before starting the RPC
					subscriber.bindRegisteringCatalogId(this.nextRegisteringCatalogId.get());
					subscriber.beforeStart(this.nextObserver.get());
					this.lastInitialized.set(subscriber);
					this.initialized.get().countDown();
				},
				publisher -> {}
			);
		}

		/**
		 * Starts `subscribe()` on a background thread - it blocks until the acknowledgement, which the test
		 * delivers - and returns once the stream has been initialized.
		 *
		 * @param registeringCatalogId identity of the catalog of the session the stream is registered in
		 * @return the stream
		 */
		@SuppressWarnings("unchecked")
		@Nonnull
		Stream subscribe(@Nonnull UUID registeringCatalogId) {
			final ClientCallStreamObserver<GrpcRegisterChangeCatalogCaptureRequest> observer =
				mock(ClientCallStreamObserver.class);
			final RecordingSubscriber delegate = new RecordingSubscriber();
			final AtomicReference<Throwable> subscribeFailure = new AtomicReference<>();
			final CountDownLatch streamInitialized = new CountDownLatch(1);
			this.nextRegisteringCatalogId.set(registeringCatalogId);
			this.nextObserver.set(observer);
			this.initialized.set(streamInitialized);
			final Thread subscribeThread = new Thread(
				() -> {
					try {
						this.publisher.subscribe(delegate);
					} catch (Throwable ex) {
						subscribeFailure.set(ex);
					}
				},
				"test-catalog-cdc-subscribe"
			);
			subscribeThread.setDaemon(true);
			subscribeThread.start();
			try {
				assertTrue(streamInitialized.await(30, TimeUnit.SECONDS), "The stream was not initialized in time.");
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new AssertionError(ex);
			}
			return new Stream(this.lastInitialized.get(), observer, delegate, subscribeThread, subscribeFailure);
		}
	}

	/**
	 * One stream of the publisher, driven by the test the way gRPC drives it.
	 *
	 * @param subscriber       the internal subscriber of the stream
	 * @param observer         the mocked gRPC call observer of the stream
	 * @param delegate         the consumer's subscriber
	 * @param subscribeThread  the thread blocked in `subscribe()` until the acknowledgement
	 * @param subscribeFailure what `subscribe()` failed with, if anything
	 */
	private record Stream(
		@Nonnull ClientChangeCatalogCaptureSubscriber subscriber,
		@Nonnull ClientCallStreamObserver<GrpcRegisterChangeCatalogCaptureRequest> observer,
		@Nonnull RecordingSubscriber delegate,
		@Nonnull Thread subscribeThread,
		@Nonnull AtomicReference<Throwable> subscribeFailure
	) {

		void deliver(@Nonnull GrpcRegisterChangeCatalogCaptureResponse response) {
			this.subscriber.onNext(response);
		}

		void fail(@Nonnull Throwable failure) {
			this.subscriber.onError(failure);
		}

		void awaitSubscribed() {
			try {
				this.subscribeThread.join(TimeUnit.SECONDS.toMillis(30));
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new AssertionError(ex);
			}
			assertFalse(this.subscribeThread.isAlive(), "subscribe() did not return in time");
		}
	}

	/**
	 * The consumer's subscriber, requesting everything and recording what it receives.
	 */
	private static final class RecordingSubscriber implements Flow.Subscriber<ChangeCatalogCapture> {
		final List<ChangeCatalogCapture> received = new CopyOnWriteArrayList<>();
		final AtomicReference<Throwable> error = new AtomicReference<>();

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			subscription.request(Long.MAX_VALUE);
		}

		@Override
		public void onNext(ChangeCatalogCapture item) {
			this.received.add(item);
		}

		@Override
		public void onError(Throwable throwable) {
			this.error.set(throwable);
		}

		@Override
		public void onComplete() {
			// not expected by any test
		}
	}

	/**
	 * Runs every task on the submitting thread, so that the deliveries the tests drive are complete when the call
	 * driving them returns.
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

}
