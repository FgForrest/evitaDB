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

import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureSubscription;
import io.evitadb.core.executor.DelayedAsyncTask;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.externalApi.grpc.exception.ClosedGrpcStreamException;
import io.evitadb.externalApi.grpc.generated.GrpcHeartBeat;
import io.evitadb.externalApi.grpc.utils.GrpcTimeoutUtil;
import io.evitadb.utils.IOUtils;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCallStreamObserver;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
 * a typed {@link ServerCallStreamObserver}.
 *
 * Handles all shared concerns:
 * - Single-shot stream finalisation across every termination path
 *   ({@link #onError}, {@link #onComplete}, {@link #close}, transport-level
 *   cancel or close).
 * - Periodic heartbeat emission (kept alive by extending the Armeria request
 *   timeout after every successful send). The heartbeat polls
 *   {@link ServerCallStreamObserver#isCancelled()} as an authoritative
 *   transport-level check that bypasses the dispatch lag between the inner
 *   {@code call.cancelled} field and the listener-driven
 *   {@code observer.cancelled} field.
 * - Convergent cleanup via {@link #markStreamDead(Throwable)} — invoked by
 *   every path that discovers the stream is no longer writable (direct
 *   termination, client cancel, server-initiated close, or an emit that threw
 *   {@link ClosedGrpcStreamException}). Transport-level termination is delivered
 *   by the service layer through {@link #onTransportTerminated()}, which the
 *   service binds to both
 *   {@link ServerCallStreamObserver#setOnCancelHandler} (client-initiated
 *   cancellation: RST_STREAM, deadline expired, channel abort) and
 *   {@link ServerCallStreamObserver#setOnCloseHandler} (server-initiated close:
 *   an interceptor calling {@code ServerCall.close(...)}, framework shutdown,
 *   our own {@code onCompleted/onError} being transmitted). Handler registration
 *   must happen synchronously on the service method's invoking thread (gRPC's
 *   {@code ServerCallStreamObserverImpl} rejects late registration), which is
 *   why the subscriber exposes the callback rather than installing the handlers
 *   itself — the subscriber is typically constructed asynchronously on a worker
 *   thread via {@code executeWithClientContext}.
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
	 * Flag ensuring the gRPC stream is finalized (state-wise) exactly once across all termination
	 * paths. Flipping this to {@code true} means: heartbeat task is closed, the upstream
	 * subscription is cancelled, and subsequent {@link #onNext} calls are dropped. Callers that
	 * also need to push a terminal frame to the client (i.e. {@link #onError},
	 * {@link #onComplete}, {@link #close}) should use the return value of
	 * {@link #markStreamDead(Throwable)} to guard the emit.
	 */
	private final AtomicBoolean streamFinalized = new AtomicBoolean(false);
	/**
	 * The gRPC stream observer used to send responses to the client. Typed to
	 * {@link ServerCallStreamObserver} so we can register transport lifecycle handlers and poll
	 * {@link ServerCallStreamObserver#isCancelled()} for the authoritative cancellation signal.
	 */
	private final ServerCallStreamObserver<RESPONSE> responseObserver;
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
	 * the tighter of the request timeout and the server's connection idle timeout (whichever of
	 * the two would actually tear the stream down first), clamped to [1 second, 5 minutes]. A
	 * disabled (0) request timeout — the normal case for an open-ended CDC subscription, see the
	 * {@link #responseTimeoutMillis} guard in {@link #onNext} / {@link #sendHeartbeat} — no longer
	 * collapses this to the 1-second floor; the idle timeout (always positive, see
	 * {@link io.evitadb.externalApi.configuration.ApiOptions#idleTimeoutInMillis()}) takes over as
	 * the basis instead.
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
	 * Read by {@link #markStreamDead(Throwable)} from arbitrary threads (transport callbacks,
	 * scheduler, publisher thread), so writes happen-before reads via the
	 * {@link #streamFinalized} CAS.
	 */
	private volatile Subscription subscription;

	protected AbstractChangeCaptureSubscriber(
		@Nonnull Scheduler scheduler,
		@Nullable String taskOwner,
		@Nonnull String taskName,
		@Nonnull ServerCallStreamObserver<RESPONSE> responseObserver,
		@Nonnull LongSupplier versionSupplier,
		@Nonnull ServiceRequestContext serviceContext
	) {
		this.responseObserver = responseObserver;
		this.versionSupplier = versionSupplier;
		this.serviceContext = serviceContext;
		this.responseTimeoutMillis = this.serviceContext.requestTimeoutMillis();
		final long idleTimeoutMillis = this.serviceContext.config().server().config().idleTimeoutMillis();
		this.heartBeatDelay = resolveHeartBeatDelay(this.responseTimeoutMillis, idleTimeoutMillis);
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

	/**
	 * Computes the heartbeat delay in milliseconds: 5 seconds less than the tighter of
	 * `requestTimeoutMillis` and `idleTimeoutMillis`, clamped to [1 second, 5 minutes]. Either
	 * input may be `0` (disabled) — a disabled request timeout is the norm for an open-ended CDC
	 * subscription (see the class JavaDoc), and Armeria's {@code ServerConfig#idleTimeoutMillis()}
	 * itself permits `0` even though evitaDB's own {@code ApiOptions} never configures it that way.
	 * A disabled input is treated as "no bound from this source" rather than as `0`, since taking
	 * the raw minimum would collapse the delay to the 1-second floor regardless of the other input.
	 * If both are disabled, there is no natural bound and the result falls back to the 5-minute
	 * ceiling.
	 *
	 * @param requestTimeoutMillis the dynamic, per-call Armeria request timeout (0 = disabled)
	 * @param idleTimeoutMillis    the server's connection idle timeout (0 = disabled)
	 * @return the heartbeat delay in milliseconds
	 */
	private static long resolveHeartBeatDelay(long requestTimeoutMillis, long idleTimeoutMillis) {
		final long effectiveTimeoutMillis;
		if (requestTimeoutMillis > 0 && idleTimeoutMillis > 0) {
			effectiveTimeoutMillis = Math.min(requestTimeoutMillis, idleTimeoutMillis);
		} else if (requestTimeoutMillis > 0) {
			effectiveTimeoutMillis = requestTimeoutMillis;
		} else if (idleTimeoutMillis > 0) {
			effectiveTimeoutMillis = idleTimeoutMillis;
		} else {
			effectiveTimeoutMillis = Long.MAX_VALUE;
		}
		return Math.min(Math.max(effectiveTimeoutMillis - 5000L, 1000L), 300000L);
	}

	/**
	 * Called by the service layer's transport lifecycle handlers
	 * ({@link ServerCallStreamObserver#setOnCancelHandler} for client-initiated cancellation —
	 * RST_STREAM, deadline, channel abort — and {@link ServerCallStreamObserver#setOnCloseHandler}
	 * for server-initiated close: interceptors calling {@code ServerCall.close}, framework
	 * shutdown, our own {@code onError}/{@code onCompleted} reaching the client). The two
	 * lifecycle edges are mutually exclusive in gRPC-Java and together cover every terminal
	 * transport transition.
	 *
	 * Must be called from the service layer (not the subscriber constructor) because gRPC's
	 * {@code ServerCallStreamObserverImpl} requires handlers to be registered synchronously on
	 * the thread that invoked the service method, before that method returns. The subscriber is
	 * typically constructed asynchronously from {@code executeWithClientContext}, which runs on
	 * a worker thread — too late for {@code setOnCancelHandler}.
	 *
	 * Idempotent — safe to invoke repeatedly; internal CAS ensures single-shot finalisation.
	 */
	public final void onTransportTerminated() {
		markStreamDead(null);
	}

	@Override
	public final void onSubscribe(Subscription subscription) {
		// Reactive Streams Rule 1.3 / 2.12 forbids a second onSubscribe, but if a misbehaving
		// publisher calls us twice anyway, cancel the second subscription and keep the first
		// active. Cheap defensive guard; never triggered by compliant publishers.
		if (this.subscription != null) {
			subscription.cancel();
			return;
		}
		// If the stream has already been finalized (e.g. transport closed before the publisher
		// called onSubscribe), don't bother emitting the ACK — just cancel the late subscription.
		if (this.streamFinalized.get()) {
			subscription.cancel();
			return;
		}
		this.subscription = subscription;
		try {
			emitOnNext(buildAcknowledgementResponse(currentSubscriptionId(), buildHeartBeatMessage()));
		} catch (ClosedGrpcStreamException ex) {
			log.debug("CDC acknowledgement failed (stream already terminated): {}", ex.getMessage());
			// defer — this method runs inside DefaultChangeCaptureSubscription's constructor
			// which is itself inside ConcurrentHashMap.computeIfAbsent; a synchronous
			// subscription.cancel() would re-enter the map and deadlock.
			CompletableFuture.runAsync(() -> markStreamDead(ex));
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
			markStreamDead(ex);
			return;
		}
		GrpcTimeoutUtil.reArmRequestTimeoutIfEnabled(this.serviceContext, this.responseTimeoutMillis);
		this.subscription.request(1);
	}

	@Override
	public final void onError(Throwable throwable) {
		if (markStreamDead(throwable)) {
			try {
				this.responseObserver.onError(throwable);
			} catch (Exception ex) {
				log.debug("Failed to emit onError to CDC client: {}", ex.getMessage(), ex);
			}
		}
	}

	@Override
	public final void onComplete() {
		if (markStreamDead(null)) {
			try {
				this.responseObserver.onCompleted();
			} catch (Exception ex) {
				log.debug("Failed to emit onCompleted to CDC client: {}", ex.getMessage(), ex);
			}
		}
	}

	@Override
	public final void close() {
		// signal the gRPC client that the stream was forcibly terminated
		if (markStreamDead(null)) {
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
	 * Marks the stream as finalized exactly once and tears down the subscriber-owned resources
	 * (heartbeat task, upstream subscription). Does NOT emit any terminal frame on the observer —
	 * callers that need to push {@code onError}/{@code onCompleted} MUST gate that on the return
	 * value of this method so the emit happens only on the first finalisation. Safe to call from
	 * any thread; invoked from the publisher thread ({@link #onNext}), the scheduler thread
	 * ({@link #sendHeartbeat}), transport callbacks registered via
	 * {@link ServerCallStreamObserver#setOnCancelHandler}/{@link ServerCallStreamObserver#setOnCloseHandler},
	 * and direct termination paths ({@link #onError}, {@link #onComplete}, {@link #close}).
	 *
	 * @param cause optional cause for debug-logging (the exception that exposed the dead stream,
	 *              or {@code null} if the finaliser itself does not have one)
	 * @return {@code true} if this call was the first to finalize the stream (caller owns the
	 *         terminal frame emit), {@code false} if the stream was already finalized (caller
	 *         MUST NOT emit).
	 */
	private boolean markStreamDead(@Nullable Throwable cause) {
		if (this.streamFinalized.compareAndSet(false, true)) {
			if (cause != null) {
				log.debug("CDC stream finalized after exception: {}", cause.getMessage());
			}
			IOUtils.closeQuietly(this.heartBeatTask::close);
			final Subscription sub = this.subscription;
			if (sub != null) {
				try {
					sub.cancel();
				} catch (Exception ex) {
					log.debug("Subscription cancel threw during stream finalization: {}", ex.getMessage(), ex);
				}
			}
			return true;
		}
		return false;
	}

	/**
	 * Sends a heartbeat response to the client and schedules the next heartbeat.
	 * Package-private so tests can drive a single heartbeat tick without racing the scheduler.
	 *
	 * @return `0` to reschedule at the regular interval, `-1` to stop the heartbeat task
	 */
	long sendHeartbeat() {
		if (this.streamFinalized.get()) {
			return -1L;
		}
		// authoritative transport-level check — bypasses the dispatch lag between the
		// inner call.cancelled field (flipped synchronously on transport cancel) and the
		// listener-driven observer.cancelled field (flipped only after listener.onCancel is
		// dispatched, which may queue behind the serialized executor).
		if (this.responseObserver.isCancelled()) {
			markStreamDead(null);
			return -1L;
		}
		try {
			emitOnNext(buildHeartbeatResponse(currentSubscriptionId(), buildHeartBeatMessage()));
		} catch (ClosedGrpcStreamException ex) {
			log.debug("Heartbeat send failed (stream likely finalized concurrently): {}", ex.getMessage());
			markStreamDead(ex);
			return -1L;
		}
		GrpcTimeoutUtil.reArmRequestTimeoutIfEnabled(this.serviceContext, this.responseTimeoutMillis);
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
			// server wrote anything — wrap and let the caller handle it via markStreamDead.
			if (ex.getStatus() != null && ex.getStatus().getCode() == Status.Code.CANCELLED) {
				throw new ClosedGrpcStreamException(ex);
			}
			// any other gRPC status from the observer means the stream is broken too
			// (the call is either already closed or about to be); finalize before re-throwing
			// so we stop feeding a dead consumer, then surface the original error to the caller.
			markStreamDead(ex);
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
