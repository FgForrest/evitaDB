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
import io.evitadb.api.requestResponse.cdc.ChangeCaptureSubscription;
import io.evitadb.core.executor.DelayedAsyncTask;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.externalApi.grpc.exception.ClosedGrpcStreamException;
import io.evitadb.externalApi.grpc.generated.GrpcHeartBeat;
import io.evitadb.utils.IOUtils;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toGrpcOffsetDateTime;

/**
 * Abstract base for {@link Subscriber} implementations that bridge an evitaDB
 * change-capture publisher (system-level or catalog-level) to a gRPC client via
 * a typed {@link StreamObserver}.
 *
 * Handles all shared concerns:
 * - Single-shot stream finalisation across every termination path
 *   ({@link #onError}, {@link #onComplete}, {@link #close}).
 * - Periodic heartbeat emission (kept alive by extending the Armeria request
 *   timeout after every successful send).
 * - Graceful handling of client-side cancellation races — both in
 *   {@link #onSubscribe} (where the subscription lives inside a
 *   {@link java.util.concurrent.ConcurrentHashMap#computeIfAbsent} callback,
 *   so cancellation has to be deferred) and in {@link #onNext} / the heartbeat
 *   loop.
 *
 * Subclasses only supply the three type-specific response-building methods.
 *
 * @param <CAPTURE>  capture event type consumed from the publisher
 * @param <RESPONSE> gRPC response message emitted to the client
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
public abstract class AbstractChangeCaptureSubscriber<CAPTURE, RESPONSE>
	implements Subscriber<CAPTURE>, AutoCloseable {

	/**
	 * Flag ensuring the gRPC stream is finalized (via `onError` or `onCompleted`) exactly once
	 * across all termination paths ({@link #onError}, {@link #onComplete}, {@link #close}).
	 */
	private final AtomicBoolean streamFinalized = new AtomicBoolean(false);
	/**
	 * The gRPC stream observer used to send responses to the client.
	 */
	private final StreamObserver<RESPONSE> responseObserver;
	/**
	 * Future completed when the subscription is established, allowing the gRPC cancel
	 * handler to obtain the subscription reference for cancellation.
	 */
	private final CompletableFuture<Subscription> subscriptionFuture;
	/**
	 * Supplier that provides the current version for heartbeat messages.
	 */
	private final LongSupplier versionSupplier;
	/**
	 * The Armeria service request context, used to extend the request timeout on activity.
	 */
	private final ServiceRequestContext serviceContext;
	/**
	 * The original response timeout in milliseconds, used to extend the timeout after each
	 * message or heartbeat.
	 */
	private final long responseTimeoutMillis;
	/**
	 * The delay between heartbeat messages in milliseconds — computed as 5 seconds less than
	 * the response timeout, clamped to [1 second, 5 minutes].
	 */
	private final long heartBeatDelay;
	/**
	 * Scheduled task that periodically sends heartbeat messages to keep the gRPC stream alive.
	 */
	private final DelayedAsyncTask heartBeatTask;
	/**
	 * Monotonically increasing counter for heartbeat message indexing. Atomic because it is
	 * accessed from both the publisher thread ({@link #onNext}) and the scheduler thread
	 * ({@link #sendHeartbeat}) via {@link #buildHeartBeatMessage()}.
	 */
	private final AtomicLong index = new AtomicLong(0L);
	/**
	 * The active subscription from the publisher, set during {@link #onSubscribe(Subscription)}.
	 */
	private Subscription subscription;

	protected AbstractChangeCaptureSubscriber(
		@Nonnull Scheduler scheduler,
		@Nullable String taskOwner,
		@Nonnull String taskName,
		@Nonnull StreamObserver<RESPONSE> responseObserver,
		@Nonnull CompletableFuture<Subscription> subscriptionFuture,
		@Nonnull LongSupplier versionSupplier,
		@Nonnull ServiceRequestContext serviceContext
	) {
		this.responseObserver = responseObserver;
		this.subscriptionFuture = subscriptionFuture;
		this.versionSupplier = versionSupplier;
		this.serviceContext = serviceContext;
		this.responseTimeoutMillis = this.serviceContext.requestTimeoutMillis();
		// heartbeat delay is 5 seconds less than request timeout,
		// clamped to [1 second, 5 minutes]
		this.heartBeatDelay = Math.min(Math.max(this.responseTimeoutMillis - 5000L, 1000L), 300000L);
		this.heartBeatTask = new DelayedAsyncTask(
			taskOwner,
			taskName,
			scheduler,
			this::sendHeartbeat,
			this.heartBeatDelay,
			TimeUnit.MILLISECONDS
		);
		this.heartBeatTask.schedule();
	}

	@Override
	public final void onSubscribe(Subscription subscription) {
		this.subscription = subscription;
		this.subscriptionFuture.complete(subscription);
		try {
			emitOnNext(buildAcknowledgementResponse(currentSubscriptionId(), buildHeartBeatMessage()));
		} catch (ClosedGrpcStreamException ex) {
			log.debug("CDC acknowledgement failed (stream already terminated): {}", ex.getMessage());
			if (this.streamFinalized.compareAndSet(false, true)) {
				IOUtils.closeQuietly(this.heartBeatTask::close);
			}
			// Defer cancellation — this method is called from the DefaultChangeCaptureSubscription
			// constructor inside ConcurrentHashMap.computeIfAbsent; synchronous cancel would
			// re-enter the map.
			CompletableFuture.runAsync(subscription::cancel);
			return;
		}
		subscription.request(1);
	}

	@Override
	public final void onNext(CAPTURE item) {
		if (this.streamFinalized.get()) {
			return;
		}
		try {
			emitOnNext(buildCaptureResponse(item));
		} catch (ClosedGrpcStreamException ex) {
			log.debug("CDC onNext failed (stream likely finalized concurrently): {}", ex.getMessage());
			this.subscription.cancel();
			return;
		}
		this.serviceContext.setRequestTimeout(TimeoutMode.SET_FROM_NOW, Duration.ofMillis(this.responseTimeoutMillis));
		this.subscription.request(1);
	}

	@Override
	public final void onError(Throwable throwable) {
		if (this.streamFinalized.compareAndSet(false, true)) {
			IOUtils.closeQuietly(this.heartBeatTask::close);
			this.subscriptionFuture.completeExceptionally(throwable);
			this.responseObserver.onError(throwable);
		}
	}

	@Override
	public final void onComplete() {
		if (this.streamFinalized.compareAndSet(false, true)) {
			IOUtils.closeQuietly(this.heartBeatTask::close);
			this.responseObserver.onCompleted();
		}
	}

	@Override
	public final void close() {
		// signal the gRPC client that the stream was forcibly terminated
		if (this.streamFinalized.compareAndSet(false, true)) {
			IOUtils.closeQuietly(this.heartBeatTask::close);
			try {
				this.responseObserver.onError(
					Status.UNAVAILABLE
						.withDescription("CDC stream has been terminated by the server.")
						.asRuntimeException()
				);
			} catch (Exception ex) {
				log.debug("Failed to send UNAVAILABLE error to CDC client: {}", ex.getMessage(), ex);
			}
		}
	}

	/**
	 * Builds the ACKNOWLEDGEMENT response emitted once from {@link #onSubscribe}.
	 *
	 * @param subscriptionId subscription identifier of the publisher's subscription, or
	 *                       `null` if the subscription does not implement
	 *                       {@link ChangeCaptureSubscription}
	 * @param heartBeat      the initial heartbeat payload to embed in the response
	 * @return the typed response to emit to the client
	 */
	@Nonnull
	protected abstract RESPONSE buildAcknowledgementResponse(
		@Nullable UUID subscriptionId,
		@Nonnull GrpcHeartBeat heartBeat
	);

	/**
	 * Builds the CHANGE response emitted per item received via {@link #onNext}.
	 *
	 * @param capture the change-capture event to encode
	 * @return the typed response to emit to the client
	 */
	@Nonnull
	protected abstract RESPONSE buildCaptureResponse(@Nonnull CAPTURE capture);

	/**
	 * Builds the HEARTBEAT response emitted periodically by the scheduled heartbeat task.
	 *
	 * @param subscriptionId subscription identifier of the publisher's subscription, or
	 *                       `null` if the subscription does not implement
	 *                       {@link ChangeCaptureSubscription}
	 * @param heartBeat      the heartbeat payload to embed in the response
	 * @return the typed response to emit to the client
	 */
	@Nonnull
	protected abstract RESPONSE buildHeartbeatResponse(
		@Nullable UUID subscriptionId,
		@Nonnull GrpcHeartBeat heartBeat
	);

	/**
	 * Sends a heartbeat response to the client and schedules the next heartbeat.
	 *
	 * @return `0` to reschedule at the regular interval, `-1` to stop the heartbeat task
	 */
	private long sendHeartbeat() {
		if (this.streamFinalized.get()) {
			return -1L;
		}
		try {
			emitOnNext(buildHeartbeatResponse(currentSubscriptionId(), buildHeartBeatMessage()));
		} catch (ClosedGrpcStreamException ex) {
			log.debug("Heartbeat send failed (stream likely finalized concurrently): {}", ex.getMessage());
			return -1L;
		}
		this.serviceContext.setRequestTimeout(TimeoutMode.SET_FROM_NOW, Duration.ofMillis(this.responseTimeoutMillis));
		// reschedule at the regular interval
		return 0L;
	}

	/**
	 * Emits `response` on {@link #responseObserver}. If the stream is no longer writable
	 * (aborted, completed, closed, or already cancelled by the client), the underlying gRPC
	 * exception is wrapped in a {@link ClosedGrpcStreamException} so callers can recognise
	 * the benign race and clean up quietly.
	 *
	 * `io.grpc.stub.ServerCalls$ServerCallStreamObserverImpl.onNext` (and the underlying
	 * `io.grpc.internal.ServerCallImpl.sendMessageInternal`) raise one of two things when
	 * the server tries to push onto a stream that is no longer writable:
	 *
	 * - an {@link IllegalStateException} when the stream has been aborted, already completed,
	 *   or its backing `ServerCall` is closed;
	 * - a {@link StatusRuntimeException} with {@link Status.Code#CANCELLED} when the client
	 *   has cancelled the RPC before the server wrote anything.
	 *
	 * Neither case is a server-side bug — both mean "this stream is done, stop writing." The
	 * catch is scoped to the single `onNext` call so we do not accidentally swallow genuine
	 * programmer errors (bad builder state, interceptor bugs, etc.) raised from response
	 * construction.
	 *
	 * @param response the response message to emit
	 * @throws ClosedGrpcStreamException if the stream is no longer writable
	 */
	private void emitOnNext(@Nonnull RESPONSE response) {
		try {
			this.responseObserver.onNext(response);
		} catch (IllegalStateException ex) {
			// every IllegalStateException reachable from ServerCallStreamObserverImpl.onNext
			// in gRPC 1.x means "stream is no longer writable" (aborted, completed, or the
			// backing ServerCall is closed) — treat them all as a stream-closed race
			throw new ClosedGrpcStreamException(ex);
		} catch (StatusRuntimeException ex) {
			// client-initiated cancellation surfaces here as Status.CANCELLED from
			// ServerCallStreamObserverImpl.onNext when the RPC was cancelled before the
			// server wrote anything
			if (ex.getStatus() != null && ex.getStatus().getCode() == Status.Code.CANCELLED) {
				throw new ClosedGrpcStreamException(ex);
			}
			throw ex;
		}
	}

	/**
	 * Returns the subscription identifier of the publisher's subscription if it implements
	 * {@link ChangeCaptureSubscription}, otherwise `null`.
	 */
	@Nullable
	private UUID currentSubscriptionId() {
		return this.subscription instanceof ChangeCaptureSubscription ccs ? ccs.getSubscriptionId() : null;
	}

	/**
	 * Constructs a new {@link GrpcHeartBeat} message with the current heartbeat data.
	 */
	@Nonnull
	private GrpcHeartBeat buildHeartBeatMessage() {
		return GrpcHeartBeat.newBuilder()
			.setIndex(this.index.getAndIncrement())
			.setTimestamp(toGrpcOffsetDateTime(OffsetDateTime.now()))
			.setLastObservedVersion(this.versionSupplier.getAsLong())
			.setMillisToNextHeartbeat(this.heartBeatDelay)
			.build();
	}
}
