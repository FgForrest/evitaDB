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

import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.externalApi.grpc.exception.StalledGrpcStreamException;
import io.grpc.stub.ServerCallStreamObserver;
import io.netty.channel.EventLoop;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Back-pressure gate for server-streaming gRPC methods that produce messages faster than the client
 * consumes them.
 *
 * gRPC-Java's send path never blocks. `StreamObserver.onNext` marshals the message and hands it to
 * Armeria's outbound {@code StreamMessage}, whose queue has an initial capacity but **no bound** — its
 * `tryWrite` refuses a message only once the stream is closed, never because it is full. A producer
 * that ignores this materialises the whole payload in memory (as pooled, off-heap Netty buffers,
 * roughly twice the payload in reserved arena space) and eventually kills the RPC with a bare
 * `UNKNOWN` when the allocator gives up. Transport readiness is the only back-pressure signal there
 * is, and this class is what makes it usable from a straight-line producing loop:
 *
 * ```java
 * final GrpcOutboundGate gate = GrpcOutboundGate.attach(serverCallObserver, methodName);
 * executeWithClientContext(
 *     () -> {
 *         while ((bytesRead = inputStream.read(buffer)) != -1) {
 *             if (!gate.awaitWritable()) {
 *                 return;                     // client is gone — abandon without completing
 *             }
 *             responseObserver.onNext(chunk);
 *         }
 *         responseObserver.onCompleted();
 *     },
 *     ...
 * );
 * ```
 *
 * **`attach` must be called synchronously from the service method, before it returns.** gRPC's
 * {@code ServerCallStreamObserverImpl} freezes handler registration the moment the service method
 * returns and throws {@link IllegalStateException} for any later {@code setOnReadyHandler} /
 * {@code setOnCancelHandler} — which is exactly what would happen if the gate were built inside the
 * worker task that {@code executeWithClientContext} submits. Only the producing loop belongs on the
 * worker; the gate is wired up before it.
 *
 * The gate parks the producing worker thread rather than driving the loop from the on-ready callback.
 * The callback runs on the Armeria event loop, so a callback-driven pump would have to bounce every
 * chunk back onto a worker anyway; parking keeps the loop's `try`-with-resources, tracing scope and
 * error handling in one straight-line method, and pins no thread that the RPC did not already own for
 * its whole duration. What it does change is *how long* that thread is held — bounded by
 * {@link #DEFAULT_STALL_TIMEOUT_MILLIS} so that a client which stops reading without hanging up cannot
 * hold a request-executor thread forever.
 *
 * With Armeria, readiness means `pendingMessages == 0`, so a gated loop keeps exactly one message in
 * flight. Callers streaming large payloads should size their chunks accordingly — every chunk costs an
 * event-loop round trip.
 *
 * The one configuration the gate cannot help is an engine built with a direct (synchronous) executor,
 * where the "worker" the service method hands off to *is* the transport event loop. Waiting there would
 * deadlock rather than throttle, so the gate detects it, warns once and lets the producer run ungated -
 * see {@link #awaitWritable()}. That is a test-only configuration; a networked `EvitaServer` always runs
 * on real thread pools.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
public final class GrpcOutboundGate {

	/**
	 * How long the producer waits for the client to consume a single outstanding message before it
	 * gives up on it. This is a "the peer is alive but has stopped reading" backstop, not a transfer
	 * deadline: the wait restarts from zero every time the transport consumes anything, so even a very
	 * slow but progressing client never reaches it.
	 *
	 * Mirrors `GrpcOptions.DEFAULT_STREAMING_REQUEST_TIMEOUT_IN_MILLIS` - a deployment configures it as
	 * `api.endpoints.gRPC.streamingRequestTimeoutInMillis`, and this constant is only the fallback for
	 * callers that have no configuration in hand (the two- and three-argument `attach` overloads, used by
	 * tests and by the example above).
	 */
	public static final long DEFAULT_STALL_TIMEOUT_MILLIS = 300_000L;

	/**
	 * How much longer than the stall timeout the Armeria request deadline is re-armed for.
	 *
	 * The two mechanisms that can end a stalled stream - this gate's own wait and Armeria's request
	 * timeout - are driven from the same budget, but they do not start their clocks at the same instant:
	 * the deadline is re-armed when a message is *granted*, the gate begins waiting only after that
	 * message has been written. Armed identically, Armeria would therefore always fire first and the
	 * gate's stall detection would be unreachable - the client would get a bare `RST_STREAM` instead of
	 * `DEADLINE_EXCEEDED` naming the stalled method. The grace hands the race to the gate deliberately,
	 * leaving Armeria as the backstop for a producer that never reaches the gate at all.
	 */
	private static final long STALL_DETECTION_GRACE_MILLIS = 5_000L;

	private final ServerCallStreamObserver<?> responseObserver;
	private final String methodName;
	private final long stallTimeoutMillis;
	/**
	 * The call's Armeria context, captured at attach time (`null` outside a request context, i.e. in
	 * unit tests driving the gate against a mock observer).
	 */
	private final ServiceRequestContext serviceContext;
	/**
	 * The budget each re-arm restates, in milliseconds, or `<= 0` when the call has no request timeout
	 * at all (an explicit `grpc-timeout: 0`, or a deployment that disabled it).
	 *
	 * This is the *streaming* budget - {@link #stallTimeoutMillis} plus
	 * {@link #STALL_DETECTION_GRACE_MILLIS} - not the call's own `requestTimeout`. Re-arming to the
	 * latter is what made a 1 MiB chunk require a ~4 Mbit/s link before the client's own deadline
	 * mattered: `requestTimeout` is a whole-request budget sized for unary calls (1 s in code, 2 s
	 * shipped), and a gated stream needs one message to drain inside it. The distinction is the same one
	 * `EvitaClientChannel.TimeoutTier` draws on the driver side.
	 */
	private final long streamBudgetMillis;
	/**
	 * The event loop this call is bound to, captured at attach time (`null` outside a request
	 * context). Waiting on readiness *from* that loop would be a self-deadlock - see
	 * {@link #awaitWritable()}.
	 */
	private final EventLoop callEventLoop;
	private final ReentrantLock lock = new ReentrantLock();
	private final Condition transportStateChanged = this.lock.newCondition();

	/**
	 * Set from the transport lifecycle callbacks. Volatile so the fast path can read it without
	 * taking the lock.
	 */
	private volatile boolean transportTerminated;
	/**
	 * Ensures the "producer runs on the event loop" warning is emitted once per call rather than once
	 * per message.
	 */
	private final AtomicBoolean eventLoopProducerReported = new AtomicBoolean();

	/**
	 * Attaches a gate to the passed server-call observer using {@link #DEFAULT_STALL_TIMEOUT_MILLIS}.
	 *
	 * @param responseObserver observer of the server-streaming call to gate
	 * @param methodName       name of the gated RPC, used in log and error messages
	 * @return the attached gate
	 */
	@Nonnull
	public static GrpcOutboundGate attach(
		@Nonnull ServerCallStreamObserver<?> responseObserver,
		@Nonnull String methodName
	) {
		return attach(responseObserver, methodName, null, DEFAULT_STALL_TIMEOUT_MILLIS);
	}

	/**
	 * Attaches a gate to the passed server-call observer using {@link #DEFAULT_STALL_TIMEOUT_MILLIS},
	 * chaining an additional action onto client-initiated cancellation.
	 *
	 * @param responseObserver observer of the server-streaming call to gate
	 * @param methodName       name of the gated RPC, used in log and error messages
	 * @param onCancel         action to run when the client cancels the call - typically releasing a
	 *                         resource the producing loop holds. The gate owns the call's cancel
	 *                         handler (gRPC allows only one, last registration wins), so any cleanup
	 *                         the service method used to register itself must be passed in here
	 * @return the attached gate
	 */
	@Nonnull
	public static GrpcOutboundGate attach(
		@Nonnull ServerCallStreamObserver<?> responseObserver,
		@Nonnull String methodName,
		@Nullable Runnable onCancel
	) {
		return attach(responseObserver, methodName, onCancel, DEFAULT_STALL_TIMEOUT_MILLIS);
	}

	/**
	 * Attaches a gate to the passed server-call observer.
	 *
	 * @param responseObserver   observer of the server-streaming call to gate
	 * @param methodName         name of the gated RPC, used in log and error messages
	 * @param onCancel           action to run when the client cancels the call, or `null` for none
	 * @param stallTimeoutMillis how long to wait for the client to consume a single message before
	 *                           abandoning the stream with {@link StalledGrpcStreamException}
	 * @return the attached gate
	 */
	@Nonnull
	public static GrpcOutboundGate attach(
		@Nonnull ServerCallStreamObserver<?> responseObserver,
		@Nonnull String methodName,
		@Nullable Runnable onCancel,
		long stallTimeoutMillis
	) {
		final GrpcOutboundGate gate = new GrpcOutboundGate(responseObserver, methodName, stallTimeoutMillis);
		responseObserver.setOnReadyHandler(gate::signalTransportStateChanged);
		// both terminal transport edges wake the producer: onCancel for client-initiated termination
		// (RST_STREAM, deadline, dead connection), onClose for server-initiated close - an interceptor
		// closing the call, or our own onError/onCompleted reaching the client. Without the latter a
		// producer waiting on readiness would sit out the full stall timeout after an unrelated
		// failure closed the call underneath it.
		responseObserver.setOnCancelHandler(
			() -> {
				gate.markTransportTerminated();
				if (onCancel != null) {
					onCancel.run();
				}
			}
		);
		responseObserver.setOnCloseHandler(gate::markTransportTerminated);
		return gate;
	}

	private GrpcOutboundGate(
		@Nonnull ServerCallStreamObserver<?> responseObserver,
		@Nonnull String methodName,
		long stallTimeoutMillis
	) {
		this.responseObserver = responseObserver;
		this.methodName = methodName;
		this.stallTimeoutMillis = stallTimeoutMillis;
		this.serviceContext = ServiceRequestContext.currentOrNull();
		this.callEventLoop = this.serviceContext == null ? null : this.serviceContext.eventLoop();
		// Whether the call has a deadline at all is still the caller's decision - an explicit
		// `grpc-timeout: 0` disables it and must stay disabled. What the deadline is re-armed *to*,
		// however, is the streaming budget rather than the call's own request timeout. Read here rather
		// than in the loop because `attach` runs synchronously from the service method, before any
		// message and therefore before any re-arm has rewritten the stored value - see
		// `GrpcTimeoutUtil#captureRequestTimeoutMillis` for why that matters.
		this.streamBudgetMillis = this.serviceContext == null ?
			0L :
			GrpcTimeoutUtil.resolveStreamingBudgetMillis(
				this.serviceContext, stallTimeoutMillis + STALL_DETECTION_GRACE_MILLIS
			);
	}

	/**
	 * Blocks the calling thread until the transport can accept another message.
	 *
	 * The cancellation check comes first and stays first, in every branch including the ungated
	 * event-loop one. Attaching a gate registers a cancel handler, and gRPC stops throwing `CANCELLED`
	 * from `onNext` as soon as one exists - so this method is the only thing left that can stop a
	 * producing loop from reading its whole source and pushing it into a dead call. Reordering these
	 * checks silently reintroduces that.
	 *
	 * @return true when it is safe to push the next message; false when the RPC is over and the caller
	 *         must abandon the stream **without** calling `onCompleted` or `onError` - the transport is
	 *         already terminated and both would be no-ops at best
	 * @throws StalledGrpcStreamException when the client kept the stream open but consumed nothing
	 *                                    within the stall timeout
	 */
	public boolean awaitWritable() {
		// cancellation is checked first and unconditionally, before readiness is ever consulted:
		// Armeria increments its pending-message counter in `sendMessage` and only decrements it once
		// the payload is genuinely consumed, which never happens for a cancelled call because
		// `doSendMessage` returns early. The counter is therefore never unwound and `isReady()` answers
		// false forever - so a gate that trusted readiness here would park the producing worker for the
		// entire stall timeout every single time a client simply pressed cancel.
		if (isTransportGone()) {
			return false;
		}
		if (this.responseObserver.isReady()) {
			return grantNextMessageWindow();
		}
		if (this.callEventLoop != null && this.callEventLoop.inEventLoop()) {
			// The producing loop is running on the very event loop that has to drain the outbound
			// queue, so waiting here would block the only thread able to make readiness true again -
			// a guaranteed self-deadlock rather than back-pressure. This happens when the service
			// method's hand-off collapses into a direct (synchronous) executor, which evitaDB does
			// for embedded test runs (`Evita(..., directExecutor = true)`); a networked server always
			// uses real thread pools and never lands here. Proceed ungated, which is exactly the
			// behaviour that configuration had before the gate existed.
			if (this.eventLoopProducerReported.compareAndSet(false, true)) {
				log.warn(
					"`{}` is producing on the transport event loop, so outbound back-pressure cannot be " +
						"applied - the response is buffered without bound. This is expected only for an " +
						"embedded engine configured with a direct executor.",
					this.methodName
				);
			}
			return grantNextMessageWindow();
		}
		this.lock.lock();
		try {
			// re-check under the lock: the on-ready callback signals while holding it, and Armeria
			// decrements the pending counter *before* invoking the callback, so a readiness edge that
			// lands between the fast path above and the wait below cannot be missed
			long remainingNanos = TimeUnit.MILLISECONDS.toNanos(this.stallTimeoutMillis);
			while (!this.responseObserver.isReady()) {
				if (isTransportGone()) {
					return false;
				}
				if (remainingNanos <= 0L) {
					throw new StalledGrpcStreamException(this.methodName, this.stallTimeoutMillis);
				}
				remainingNanos = this.transportStateChanged.awaitNanos(remainingNanos);
			}
			return grantNextMessageWindow();
		} catch (InterruptedException ex) {
			// the request executor cancels its tasks when the Armeria context is cancelled, so an
			// interrupt here means the call is being torn down - treat it exactly like a dead transport
			Thread.currentThread().interrupt();
			log.debug("Producer of `{}` interrupted while waiting for the client to consume.", this.methodName);
			return false;
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * Re-arms the deadline one last time, for the terminal `onCompleted` and whatever is still queued
	 * behind it.
	 *
	 * Needed because {@link #awaitWritable()} grants a window *before* each message, so the final message
	 * of a stream is the one write no subsequent grant covers. Without this call the deadline stays
	 * frozen at the last grant while the transport is still draining the residual and the half-close is
	 * yet to land - and the residual is precisely what a slow client is slowest at. Gating bounds it to
	 * roughly one flow-control window, not to zero.
	 *
	 * Call it immediately before `onCompleted()`, and only on the success path - a producer abandoning a
	 * dead call has nothing left to protect.
	 */
	public void grantCompletionWindow() {
		grantNextMessageWindow();
	}

	/**
	 * Re-arms the call's Armeria request timeout and reports that the producer may send.
	 *
	 * This is the reason the gate touches deadlines at all. Armeria's request timeout measures the whole
	 * request, and gating a producer on readiness ties that request's lifetime to how fast the *client*
	 * consumes - so a healthy download of a large file over a slow link outlives any fixed budget, and
	 * without a re-arm the entire transfer has to fit inside one (1-2 s for a client that sends no
	 * `grpc-timeout`). What the deadline has to mean on a stream is "time without progress", and every
	 * grant of the gate is exactly one unit of progress.
	 *
	 * Doing it here rather than in each producing loop is deliberate: the re-arm was hand-written at six
	 * call sites and `fetchFile` - the one streaming the largest payloads - was missing it entirely, which
	 * is the failure mode a per-call-site convention has. A gated method now cannot forget, because the
	 * grant and the re-arm are the same act.
	 *
	 * Safe off the event loop: Armeria's `DefaultCancellationScheduler` guards `setTimeoutNanos` with a
	 * lock and re-schedules through `EventExecutor.schedule`.
	 *
	 * @return always `true` - written as a return so the callers read as a single decision
	 */
	private boolean grantNextMessageWindow() {
		if (this.serviceContext != null) {
			GrpcTimeoutUtil.reArmRequestTimeoutIfEnabled(this.serviceContext, this.streamBudgetMillis);
		}
		return true;
	}

	/**
	 * Tells whether the RPC is already over, from either transport lifecycle callback or from gRPC's
	 * own cancellation flag - the latter covers a cancellation that races us before the callback runs.
	 *
	 * @return true when nothing more can usefully be written to this call
	 */
	private boolean isTransportGone() {
		return this.transportTerminated || this.responseObserver.isCancelled();
	}

	/**
	 * Records that the call is over and wakes a producer waiting on readiness. Runs on the Armeria
	 * event loop, so it must stay non-blocking.
	 */
	private void markTransportTerminated() {
		this.transportTerminated = true;
		signalTransportStateChanged();
	}

	/**
	 * Wakes a producer waiting on readiness. Runs on the Armeria event loop, so it must stay
	 * non-blocking - the lock is only ever held for the duration of a state re-check.
	 */
	private void signalTransportStateChanged() {
		this.lock.lock();
		try {
			this.transportStateChanged.signalAll();
		} finally {
			this.lock.unlock();
		}
	}

}
