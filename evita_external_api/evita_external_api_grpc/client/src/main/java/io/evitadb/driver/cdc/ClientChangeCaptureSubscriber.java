/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.TimeoutException;
import com.linecorp.armeria.common.util.TimeoutMode;
import io.evitadb.api.exception.ChangeCaptureResumePositionInvalidException;
import io.evitadb.api.exception.TemporalDataNotAvailableException;
import io.evitadb.api.requestResponse.cdc.ChangeCapture;
import io.evitadb.driver.cdc.ClientChangeCapturePublisher.ClientSubscription;
import io.evitadb.driver.exception.PublisherClosedByClientException;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.grpc.requestResponse.ErrorInfoConverter;
import io.evitadb.externalApi.grpc.requestResponse.cdc.HeartBeat;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ExceptionUtils;
import io.evitadb.utils.IOUtils;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Client-side implementation of a subscriber that bridges between gRPC streaming and Java Flow API.
 * This class acts as both a Flow.Subscriber and a ClientResponseObserver, allowing it to:
 *
 * 1. Receive change captures from the server via gRPC streaming
 * 2. Forward these captures to a delegate Flow.Subscriber
 *
 * The subscriber works in conjunction with {@link ClientChangeCapturePublisher} to provide
 * a reactive streaming interface for change data capture events coming from the evitaDB server.
 *
 * This class handles the lifecycle of the subscription, including error handling and graceful
 * shutdown when the client or server closes the connection.
 *
 * @param <C>   type of change capture that this subscriber handles
 * @param <REQ> type of request sent to the server
 * @param <RES> type of response received from the server
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Slf4j
@ThreadSafe
public class ClientChangeCaptureSubscriber<C extends ChangeCapture, REQ, RES>
	implements Flow.Subscriber<RES>, ClientResponseObserver<REQ, RES>, AutoCloseable {

	/**
	 * The delegate subscriber that will receive the deserialized change captures.
	 * This is the actual subscriber that the client code provided to receive the change events.
	 */
	private final Flow.Subscriber<? super C> delegate;

	/**
	 * Function that converts the raw gRPC response into an assigned UUID for acknowledging the subscription setup
	 * on the server side.
	 */
	private final Function<RES, Optional<HeartBeat>> deserializeAcknowledgeResponse;

	/**
	 * Function that converts the raw gRPC response into a typed change capture object.
	 * This function is provided by the publisher to handle the specific type of response.
	 */
	private final Function<RES, Optional<C>> deserializeCaptureResponse;

	/**
	 * Duration to extend the response timeout for each received message.
	 * This helps keep the streaming connection alive as long as messages are being received.
	 */
	private final Duration streamingTimeout;

	/**
	 * Size of the in-flight window the server may push without further acknowledgement.
	 * After the subscription is established this number of credits is requested from the
	 * server in a single batch and is then topped up by one for every message the client
	 * consumes — making HTTP/2 flow control the actual backpressure mechanism and preventing
	 * the bounded item queue from ever overflowing under normal operation.
	 */
	private final int flowControlWindow;

	/**
	 * Flag indicating whether this subscriber has been closed.
	 * Used to prevent multiple close operations and ensure proper cleanup.
	 */
	private final AtomicBoolean closed = new AtomicBoolean(false);
	/**
	 * Flag indicating whether the server side has closed the stream.
	 * This is used to differentiate between client-initiated and server-initiated closures.
	 */
	private final AtomicBoolean serverSideClosed = new AtomicBoolean(false);

	/**
	 * Completed when the server acknowledges the subscription (the first {@link #onNext} carrying the
	 * ACKNOWLEDGEMENT), or completed exceptionally if the stream fails before that acknowledgement
	 * arrives. {@link ClientChangeCapturePublisher#subscribe} blocks on this future via
	 * {@link #awaitAcknowledgement()} so it returns only once the server-side subscription is
	 * established — see that method for the ordering guarantees this provides.
	 *
	 * A terminal path that fails this future must read {@link #closed} — to decide whether the delegate is told —
	 * **before** it completes the future. The caller woken by the completion tears the half-open subscription down
	 * at once, which closes this subscriber; reading the flag afterwards lets that teardown win and swallows the
	 * terminal signal the consumer is owed. The terminal signal itself is queued before the future is completed
	 * but held until it is (see `lastDelegateSignal`), so a consumer whose handler cancels the subscription cannot
	 * replace the failure `subscribe()` surfaces with a {@link PublisherClosedByClientException}.
	 */
	private final CompletableFuture<Void> acknowledged = new CompletableFuture<>();

	/**
	 * Completed once {@link #onSubscribe} has handed the delegate its subscription. The signals queued for the
	 * delegate start from it (see `lastDelegateSignal`), so that the delegate never sees `onError` or `onComplete`
	 * before `onSubscribe` (Reactive Streams §1.9).
	 *
	 * The publisher hands the subscription over only after the stream initializer has started the RPC, and gRPC may
	 * fail or complete the stream on its inbound thread in between. Handing it over before the initializer instead
	 * would let a consumer cancel from `onSubscribe` a stream that does not exist yet, which `subscribe()` would then
	 * have to refuse to open. Captures need no gate: none is delivered before the delegate requests one, which it can
	 * only do from `onSubscribe` on.
	 */
	private final CompletableFuture<Void> delegateSubscribed = new CompletableFuture<>();

	/**
	 * The last signal queued for the delegate: a terminal `onError` / `onComplete`, or the `close` of a closeable
	 * delegate. Each runs on the executor only after its predecessor has run (see {@link #dispatchDelegateSignal}),
	 * so the delegate is closed after the terminal signal that precedes the close - two independent dispatches would
	 * leave the order to the pool. The chain starts once both of these are settled:
	 *
	 * - `delegateSubscribed`, so that no terminal signal precedes `onSubscribe`
	 * - `acknowledged`, so that a terminal path can queue its signal **before** it releases the caller blocked in
	 *   `subscribe()` - and thereby ahead of the delegate close that caller's teardown queues - while the signal
	 *   still runs only after the failure `subscribe()` surfaces is settled
	 */
	private final AtomicReference<CompletableFuture<Void>> lastDelegateSignal =
		new AtomicReference<>(CompletableFuture.allOf(this.delegateSubscribed, this.acknowledged));

	/**
	 * The gRPC observer that sends requests to and receives responses from the server.
	 * This is initialized in the beforeStart method and used to cancel the stream when closing.
	 */
	@Nullable
	private volatile ClientCallStreamObserver<REQ> serverObserver;

	/**
	 * The subscription that manages the flow control between this subscriber and the publisher.
	 * Set by {@link #attachSubscription} when the publisher creates a subscription for this
	 * subscriber — deliberately *before* the stream initializer runs so the gRPC inbound
	 * thread cannot observe a null field. {@link #onSubscribe} only forwards the subscription
	 * to the delegate; it does not assign this field.
	 *
	 * Declared `volatile` so writes from the `subscribe()` thread (via
	 * {@link #attachSubscription}) are visible to the gRPC inbound thread reading the
	 * field in {@link #onNext} without relying on transitive happens-before through
	 * gRPC stub internals.
	 */
	@Nullable
	private volatile ClientSubscription<C, REQ, RES> subscription;

	/**
	 * Serializing view over the shared client pool, used exclusively for {@link HeartBeatSensor} notifications.
	 *
	 * Heartbeat delivery has to satisfy two constraints at once. It must not run on the gRPC inbound thread —
	 * the SPI exists so a consumer can notice a stale stream and *re-establish* it, which re-enters
	 * {@link ClientChangeCapturePublisher#subscribe} and blocks in {@link #awaitAcknowledgement()} on a frame
	 * only that thread could deliver. And it must stay **ordered**, because a sensor detects missed heartbeats
	 * from the continuity of {@link HeartBeat#index()}; dispatching each notification independently onto
	 * a multi-threaded pool would let two reorder and manufacture a phantom gap.
	 *
	 * {@link SerialCdcExecutor} satisfies both: tasks run on the shared pool, never on the submitter, and at
	 * most one at a time in submission order.
	 *
	 * Assigned in {@link #attachSubscription} *before* the `subscription` field, so any thread that observes
	 * a non-null subscription also observes this executor.
	 */
	@Nullable
	private volatile Executor heartBeatExecutor;

	/**
	 * The last heartbeat received from the server, used to monitor the connection health.
	 *
	 * Declared `volatile` because {@link #toString} may be invoked from arbitrary threads
	 * (logging, diagnostics) and must observe the most recent value written by the gRPC
	 * inbound thread in {@link #onNext}.
	 */
	@Nullable
	private volatile HeartBeat lastHeartBeat;

	/**
	 * Creates a subscriber bound to a delegate `Flow.Subscriber` and the gRPC-side
	 * deserialization callbacks supplied by the owning publisher.
	 *
	 * @param delegate                       downstream subscriber that receives deserialized captures
	 * @param deserializeAcknowledgeResponse decodes ACK and heartbeat envelopes
	 * @param deserializeCaptureResponse     decodes capture payloads
	 * @param streamingTimeout               per-message response deadline applied after every onNext
	 * @param flowControlWindow              number of credits requested from the server after ACK and
	 *                                       the maximum number of in-flight messages allowed at any time
	 * @throws GenericEvitaInternalError if {@code flowControlWindow <= 0}
	 */
	public ClientChangeCaptureSubscriber(
		@Nonnull Flow.Subscriber<? super C> delegate,
		@Nonnull Function<RES, Optional<HeartBeat>> deserializeAcknowledgeResponse,
		@Nonnull Function<RES, Optional<C>> deserializeCaptureResponse,
		@Nonnull Duration streamingTimeout,
		int flowControlWindow
	) {
		Assert.isPremiseValid(
			flowControlWindow > 0,
			"Flow control window must be positive."
		);
		this.delegate = delegate;
		this.deserializeAcknowledgeResponse = deserializeAcknowledgeResponse;
		this.deserializeCaptureResponse = deserializeCaptureResponse;
		this.streamingTimeout = streamingTimeout;
		this.flowControlWindow = flowControlWindow;
	}

	/**
	 * Called by gRPC before starting the stream to provide the observer for sending requests to the server.
	 *
	 * This method initializes the serverObserver field which is later used to cancel the stream when closing.
	 * It ensures that the subscriber can only be started once.
	 *
	 * Inbound auto-flow-control is disabled so the server may only push messages this client
	 * has explicitly acknowledged. A single credit is requested for the ACK message; the
	 * `flowControlWindow` is primed after the ACK arrives and refilled one credit at a time
	 * as messages are drained — see {@link #requestOneMore()}.
	 *
	 * @param observer the gRPC observer for sending requests to the server
	 * @throws GenericEvitaInternalError if the subscriber has already been started
	 */
	@Override
	public void beforeStart(ClientCallStreamObserver<REQ> observer) {
		Assert.isPremiseValid(
			this.serverObserver == null,
			"ClientChangeCaptureSubscriber can only be started once. It is already started."
		);

		this.serverObserver = observer;
		// take over inbound flow control from gRPC defaults so the server cannot outpace us;
		// explicitly ask for the single ACK message that primes the credit window
		observer.disableAutoRequestWithInitial(1);
	}

	/**
	 * Wires the owning subscription into this subscriber **before** the stream
	 * initializer opens the inbound credit window.
	 *
	 * The gRPC ACK reply can land on a different thread between
	 * {@link #beforeStart} (which calls `disableAutoRequestWithInitial(1)`) and
	 * the publisher's call to {@link #onSubscribe}. Without an early field
	 * assignment {@link #onNext} would dereference a still-null `subscription`.
	 * {@link #onSubscribe} is reserved for notifying the downstream delegate.
	 *
	 * @param subscription the subscription created by the publisher
	 */
	void attachSubscription(@Nonnull ClientSubscription<C, REQ, RES> subscription) {
		// assign the heartbeat executor first — both fields are volatile, so a thread that observes
		// a non-null `subscription` is guaranteed to observe the executor too
		this.heartBeatExecutor = new SerialCdcExecutor(
			subscription.getExecutorService(),
			"deliver onHeartBeat to the delegate subscriber",
			// a heartbeat that cannot be delivered fails the whole subscription rather than being dropped:
			// the consumer derives missed-heartbeat counts from index continuity, so resuming after a silent
			// gap would read as *server* heartbeats being missed when the driver dropped them
			this::notifyClientFailureAndClose
		);
		this.subscription = subscription;
	}

	/**
	 * Forwards the subscription to the delegate and then releases any terminal signal the stream raised before it
	 * (see `delegateSubscribed`).
	 *
	 * The field-level binding happens in {@link #attachSubscription} and must
	 * precede the stream-initialization step that opens the inbound credit
	 * window; this method intentionally does no field assignment.
	 *
	 * @param subscription the subscription created by the publisher
	 */
	@Override
	public void onSubscribe(Subscription subscription) {
		try {
			this.delegate.onSubscribe(subscription);
		} finally {
			// released even when the delegate throws - a terminal signal already raised must not be stranded
			this.delegateSubscribed.complete(null);
		}
	}

	/**
	 * Blocks the calling thread until the server acknowledges the subscription — the first
	 * {@link #onNext} carrying the ACKNOWLEDGEMENT response — or until the stream fails or the
	 * streaming timeout elapses during setup.
	 *
	 * Gating {@link ClientChangeCapturePublisher#subscribe} on this acknowledgement makes the
	 * client-side `subscribe` return only once the server-side subscription is established. This
	 * closes two races that only surface when the caller immediately issues another call on the
	 * same session (the offloaded server-side registration otherwise runs on the request pool
	 * after `subscribe` has already returned):
	 *
	 * 1. a concurrent same-session call racing the still-pending registration on the server
	 *    request pool (rejected with a concurrent-session-access error), and
	 * 2. a mutation firing before this subscriber is wired into the change observer, so its event
	 *    is never delivered.
	 *
	 * The acknowledgement is delivered on the gRPC inbound thread — never on the caller thread that
	 * runs `subscribe` — so this wait cannot deadlock the delivery of the acknowledgement it awaits.
	 *
	 * **That is a load-bearing invariant, and it is the only thing that keeps this wait from being
	 * a deadlock.** Armeria delivers inbound frames on an event loop thread, and a client normally holds
	 * exactly one event loop per endpoint (`DefaultEventLoopScheduler.DEFAULT_MAX_NUM_EVENT_LOOPS` is 1,
	 * and `HttpChannelPool` is instantiated per event loop). If any mechanism ever causes driver or
	 * consumer work to run *on* that inbound thread while `subscribe` is parked here, the thread that
	 * would deliver the acknowledgement becomes the thread waiting for it, and the whole HTTP/2 connection
	 * dies — no inbound frames are read at all, so every outstanding and future call on it fails on
	 * timeout. Issue #1387 was exactly this: `ThreadPoolExecutor.CallerRunsPolicy` on the shared client
	 * pool ran rejected capture-teardown tasks inline on the submitting thread, which under saturation was
	 * the event loop. The pool now fails fast (`EvitaClientRejectingExecutorHandler`) and every consumer
	 * callback is dispatched off the submitting thread ({@link CdcCallbackDispatcher}) — do not
	 * reintroduce any "run it on the caller" fallback on these paths.
	 *
	 * This gate is also what serializes session-bound calls around
	 * `EvitaClientSession#registerChangeCatalogCapture` — see that method for the ordering contract that
	 * any new asynchronous session API has to respect.
	 *
	 * A failure the consumer is expected to react to - a {@link TemporalDataNotAvailableException}, including the
	 * {@link ChangeCaptureResumePositionInvalidException} that refuses a resume position, whether the server raised
	 * it or {@link #verifyAcknowledgement} did - is rethrown as itself, so that `subscribe()` fails with the very
	 * exception a consumer catches to rebuild its state, instead of a generic internal error it would have to
	 * unwrap.
	 *
	 * @throws TemporalDataNotAvailableException if the subscription was refused because the data it asks for is
	 *         not available - typically a resume position the catalog cannot serve
	 * @throws GenericEvitaInternalError if the server does not acknowledge the subscription within
	 *         the streaming timeout, the stream fails before the acknowledgement arrives for any other reason, or
	 *         the waiting thread is interrupted
	 */
	void awaitAcknowledgement() {
		try {
			this.acknowledged.get(this.streamingTimeout.toMillis(), TimeUnit.MILLISECONDS);
		} catch (java.util.concurrent.TimeoutException ex) {
			throw new GenericEvitaInternalError(
				"The evitaDB server did not acknowledge the change data capture subscription within " +
					this.streamingTimeout.toMillis() + " ms.",
				"The evitaDB server did not acknowledge the change data capture subscription in time.",
				ex
			);
		} catch (ExecutionException ex) {
			final Throwable cause = ex.getCause() == null ? ex : ex.getCause();
			if (cause instanceof TemporalDataNotAvailableException temporalDataNotAvailable) {
				throw temporalDataNotAvailable;
			}
			throw new GenericEvitaInternalError(
				"The change data capture subscription failed before it was acknowledged by the server: " +
					cause.getMessage(),
				"The change data capture subscription failed before it was acknowledged by the server.",
				cause
			);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new GenericEvitaInternalError(
				"Interrupted while waiting for the change data capture subscription acknowledgement.",
				"Interrupted while waiting for the change data capture subscription acknowledgement.",
				ex
			);
		}
	}

	/**
	 * Called when a new response is received from the server.
	 *
	 * The very first response on a stream must be the subscription
	 * acknowledgement — it carries the server-assigned subscription id and
	 * unlocks the full inbound flow-control window. Subsequent responses are
	 * deserialized into change captures (enqueued onto the subscription) or
	 * heartbeats (credit restored immediately so periodic heartbeats do not
	 * starve the capture window).
	 *
	 * @param itemResponse the response received from the server
	 * @throws GenericEvitaInternalError if the first received message is not an
	 *         acknowledgement
	 */
	@Override
	public void onNext(RES itemResponse) {
		// pin the @Nullable fields to non-null locals once: gRPC guarantees `beforeStart`
		// runs before any inbound callback, and the publisher's `subscribe` calls
		// `attachSubscription` before triggering the stream initializer — so both are
		// in practice non-null here. The `requireNonNull` calls double as a contract guard
		// (would surface a violated invariant as a descriptive NPE) and as a hint to the
		// IDE / static analyzers that the subsequent dereferences are safe.
		final ClientCallStreamObserver<REQ> observer = Objects.requireNonNull(
			this.serverObserver,
			"`serverObserver` must be initialized by `beforeStart` before `onNext` is invoked."
		);
		final ClientSubscription<C, REQ, RES> activeSubscription = Objects.requireNonNull(
			this.subscription,
			"Subscription must be attached by the publisher before `onNext` is invoked."
		);
		// restart the response deadline from now so a silent stream unblocks us within
		// `streamingTimeout` of the last event, regardless of how many have arrived;
		// `currentOrNull` keeps the subscriber safely callable outside an Armeria request scope
		final ClientRequestContext requestContext = ClientRequestContext.currentOrNull();
		if (requestContext != null) {
			requestContext.setResponseTimeout(TimeoutMode.SET_FROM_NOW, this.streamingTimeout);
		}
		// first item is always subscription acknowledge response
		this.deserializeAcknowledgeResponse.apply(itemResponse)
			.ifPresent(heartBeat -> {
				final HeartBeat previous = this.lastHeartBeat;
				if (previous != null && previous.index() + 1 != heartBeat.index()) {
					log.warn(
						"Missed heartbeat(s)! Last heartbeat index: {}, new heartbeat index: {}",
						previous.index(),
						heartBeat.index()
					);
				}
				this.lastHeartBeat = heartBeat;
				if (this.delegate instanceof HeartBeatSensor heartBeatSensor) {
					// `onHeartBeat` is consumer code, and the whole point of the SPI is to let a consumer
					// notice a stale stream and re-establish it — i.e. re-enter `subscribe()`, which blocks
					// in `awaitAcknowledgement()`. Running it here would park the gRPC inbound thread that
					// has to deliver that acknowledgement. The gap detection above stays inline: it is
					// driver-internal, non-blocking, and depends on the inbound thread's ordering.
					// `SerialCdcExecutor` guards the callback and never throws, so no dispatcher wrapper here
					Objects.requireNonNull(
						this.heartBeatExecutor,
						"`heartBeatExecutor` must be assigned by `attachSubscription` before `onNext`."
					).execute(() -> heartBeatSensor.onHeartBeat(heartBeat));
				}
			});
		if (activeSubscription.getSubscriptionId() == null) {
			// the very first message MUST be the acknowledgement — otherwise the protocol is being
			// violated and the heartbeat field is still null. Fail the acknowledgement future so a
			// caller blocked in `awaitAcknowledgement` is released immediately instead of waiting out
			// the streaming timeout, and surface the same descriptive `GenericEvitaInternalError` on
			// the gRPC delivery thread as before.
			if (this.lastHeartBeat == null) {
				final GenericEvitaInternalError protocolViolation = new GenericEvitaInternalError(
					"Expected ACKNOWLEDGEMENT as first message but got something else."
				);
				this.acknowledged.completeExceptionally(protocolViolation);
				throw protocolViolation;
			}
			// IDE-friendly capture: the null-check above already proved non-null, so this
			// `requireNonNull` is a no-op at runtime but tells static analyzers the dereference is safe
			final HeartBeat acknowledgement = Objects.requireNonNull(this.lastHeartBeat);
			try {
				verifyAcknowledgement(itemResponse, acknowledgement);
			} catch (RuntimeException refusal) {
				// the subscription is refused before it is established - neither the acknowledgement future nor
				// the capture window is opened. The failure is client-originated, so the server still believes
				// the stream is open and the teardown must cancel it; the ACK future fails with the refusal
				// itself, which is what `subscribe()` surfaces
				notifyClientFailureAndClose(refusal);
				return;
			}
			activeSubscription.setSubscriptionId(acknowledgement.subscriptionId());
			// ACK consumed: open the full window so the server may start streaming captures
			observer.request(this.flowControlWindow);
			// release any caller blocked in `awaitAcknowledgement` — the server-side subscription
			// is now established, so a subsequent same-session call is safe to issue
			this.acknowledged.complete(null);
		} else {
			final Optional<C> capture = this.deserializeCaptureResponse.apply(itemResponse);
			if (capture.isPresent()) {
				// enqueued capture: credit is restored from `consume()` after the delegate receives it
				activeSubscription.produce(capture.get());
			} else {
				// non-enqueued envelope (e.g. heartbeat): restore the consumed credit immediately
				// so the server can keep pushing captures despite the periodic heartbeat traffic
				observer.request(1);
			}
		}
	}

	/**
	 * Verifies the acknowledgement of the subscription before the subscription is considered established. Called
	 * on the gRPC inbound thread for the first response of the stream only, before the acknowledgement future is
	 * completed and before the server is allowed to push any capture - so a refusal here guarantees that the
	 * consumer receives nothing from a stream it must not consume.
	 *
	 * The default accepts every acknowledgement. A subclass throws to refuse the subscription; the exception
	 * fails `subscribe()` and is delivered to the delegate's `onError`, and the stream is cancelled.
	 *
	 * @param acknowledgement the raw acknowledgement response
	 * @param heartBeat       the heartbeat decoded from it
	 * @throws RuntimeException to refuse the subscription
	 */
	protected void verifyAcknowledgement(@Nonnull RES acknowledgement, @Nonnull HeartBeat heartBeat) {
		// accepted - this stream has nothing to verify
	}

	/**
	 * Restores one inbound flow-control credit with the server.
	 *
	 * Called by the owning {@link ClientSubscription} after each capture is delivered
	 * to the delegate. Guarded against post-close calls so a slow consumer that finishes
	 * draining the queue after the stream has been torn down cannot resurrect the channel.
	 */
	void requestOneMore() {
		final ClientCallStreamObserver<REQ> theServerRequest = this.serverObserver;
		if (theServerRequest != null && !this.closed.get() && !this.serverSideClosed.get()) {
			theServerRequest.request(1);
		}
	}

	/**
	 * Called when an error occurs in the gRPC stream.
	 *
	 * This method handles two types of errors:
	 * 1. Errors caused by manually closing the publisher (expected)
	 * 2. Other errors (unexpected)
	 *
	 * For expected errors, it completes the stream gracefully.
	 * For unexpected errors, it logs the error, notifies the delegate subscriber, and closes the stream.
	 *
	 * A server error that describes a recognised evitaDB exception (see {@link ErrorInfoConverter}) reaches the
	 * delegate - and a caller still blocked in `subscribe()` - as that exception, rebuilt with all its fields,
	 * rather than as the raw gRPC status. The client-close and timeout classification runs first and is never
	 * subject to the rebuild: neither carries a server error.
	 *
	 * @param throwable the error that occurred
	 */
	@Override
	public void onError(Throwable throwable) {
		this.serverSideClosed.set(true);
		final Throwable rootCause = ExceptionUtils.getRootCause(throwable);
		final EvitaInvalidUsageException typedFailure =
			rootCause instanceof PublisherClosedByClientException || rootCause instanceof TimeoutException ?
				null : ErrorInfoConverter.toTypedException(throwable);
		final Throwable failure = typedFailure == null ? rootCause : typedFailure;
		// read before completing the future - the caller it releases closes this subscriber, see `acknowledged`;
		// the publisher always attaches the subscription before the stream initializer runs, so by the time
		// `onError` fires the subscription is guaranteed non-null
		final ClientSubscription<C, REQ, RES> activeSubscription =
			rootCause instanceof PublisherClosedByClientException || this.closed.get() ?
				null :
				Objects.requireNonNull(this.subscription, "Subscription must be attached before `onError` is invoked.");
		if (activeSubscription != null) {
			// we notify the subscriber about the error — dispatched off this thread, which is the gRPC
			// inbound (event loop) thread; a consumer `onError` handler that re-subscribes would otherwise
			// block the very thread that has to deliver the acknowledgement it then waits for. Queued before the
			// future below is completed, so it precedes the delegate close of the teardown that completion releases
			dispatchDelegateSignal(
				activeSubscription.getExecutorService(),
				() -> this.delegate.onError(failure),
				"deliver onError to the delegate subscriber"
			);
		}
		// unblock a caller still waiting in `awaitAcknowledgement`: the stream failed before the
		// server acknowledged the subscription, so the subscribe() call must fail rather than wait
		// out the full streaming timeout (no-op once the ACK has already completed the future)
		this.acknowledged.completeExceptionally(failure);
		if (rootCause instanceof PublisherClosedByClientException) {
			// this is expected, we closed the publisher manually
			// apparently, gRPC server doesn't know if cancellation was initiated by the client or by some network error
			// in this case we don't call the on complete, nor on error methods on the delegate
			log.debug("Client change capture publisher was closed manually by the client.", throwable);
		} else if (activeSubscription != null) {
			if (rootCause instanceof TimeoutException) {
				// we don't log timeout exceptions as errors because we expect that the CDC is regularly timed out
				// and then re-established by the client
				log.debug("CDC stream timed out and will be re-established.", throwable);
			} else if (typedFailure != null) {
				// the server refused to continue for a reason the consumer is told about and has to act on (a resume
				// position it cannot serve, for example) - not a fault of the driver or the connection
				log.warn("The change capture stream was terminated by the server: {}", typedFailure.getMessage());
			} else {
				log.error("Error occurred in the client change capture publisher.", throwable);
			}
			// this handles cleanup and calling #close on this instance
			activeSubscription.cancel();
		}
	}

	/**
	 * Reports a client-internal failure (typically a queue overflow because the
	 * delegate cannot keep up) and tears the subscription down.
	 *
	 * Unlike {@link #onError} this method does **not** flip `serverSideClosed`
	 * — the server still believes the stream is open, so the follow-up
	 * {@link #close} must invoke `serverObserver.cancel(...)` to release the
	 * gRPC stream. Conflating server-originated and client-originated failures
	 * would short-circuit the close path and leave the server pushing into a
	 * dead client.
	 *
	 * @param cause exception describing the client-internal failure
	 */
	void notifyClientFailureAndClose(@Nonnull Throwable cause) {
		// read before completing the future - the caller it releases closes this subscriber, see `acknowledged`;
		// invoked from `ClientSubscription.consume`, which can only exist once the
		// publisher has attached this subscription, so the field is always non-null
		final ClientSubscription<C, REQ, RES> activeSubscription = this.closed.get() ?
			null :
			Objects.requireNonNull(
				this.subscription,
				"Subscription must be attached before `notifyClientFailureAndClose` is invoked."
			);
		if (activeSubscription != null) {
			// off-thread for the same reason as in `onError` — the caller is the drain task, and
			// a rejected dispatch must not strand the terminal notification; queued before the future
			// below is completed for the same reason as well
			dispatchDelegateSignal(
				activeSubscription.getExecutorService(),
				() -> this.delegate.onError(cause),
				"deliver onError (client-side failure) to the delegate subscriber"
			);
		}
		// unblock a caller still waiting in `awaitAcknowledgement` (no-op once the ACK completed it)
		this.acknowledged.completeExceptionally(cause);
		if (activeSubscription != null) {
			if (cause instanceof TemporalDataNotAvailableException) {
				// a refusal of the subscription by `verifyAcknowledgement` - the consumer is told and has to act on
				// it, nothing in the driver failed
				log.warn("The change capture subscription was refused: {}", cause.getMessage());
			} else {
				log.error("Client-side change capture subscription failed.", cause);
			}
			// triggers `close()` which still sees `serverSideClosed == false` and therefore
			// propagates the cancellation to the gRPC stream
			activeSubscription.cancel();
		}
	}

	/**
	 * Called when the gRPC stream completes normally.
	 *
	 * This method notifies the delegate subscriber that the stream has completed
	 * and cancels the subscription to clean up resources.
	 */
	@Override
	public void onComplete() {
		this.serverSideClosed.set(true);
		// read before completing the future - the caller it releases closes this subscriber, see `acknowledged`;
		// gRPC calls `onComplete` only after `beforeStart` returned, by which point
		// the publisher has already attached the subscription
		final ClientSubscription<C, REQ, RES> activeSubscription = this.closed.get() ?
			null :
			Objects.requireNonNull(this.subscription, "Subscription must be attached before `onComplete` is invoked.");
		if (activeSubscription != null) {
			// off-thread for the same reason as in `onError` — this runs on the gRPC inbound thread;
			// queued before the future below is completed for the same reason as well
			dispatchDelegateSignal(
				activeSubscription.getExecutorService(),
				this.delegate::onComplete,
				"deliver onComplete to the delegate subscriber"
			);
		}
		// unblock a caller still waiting in `awaitAcknowledgement`: the stream completed before the
		// server acknowledged the subscription, which is abnormal — fail the subscribe() rather than
		// wait out the streaming timeout (no-op once the ACK has already completed the future)
		this.acknowledged.completeExceptionally(
			new GenericEvitaInternalError("The change data capture stream completed before it was acknowledged.")
		);
		if (activeSubscription != null) {
			// this handles cleanup and calling #close on this instance
			activeSubscription.cancel();
		}
	}

	/**
	 * Queues a signal for the delegate behind the last one (see `lastDelegateSignal`) and dispatches it off the
	 * calling thread through {@link CdcCallbackDispatcher} once its predecessor has run. Until then the dispatch is
	 * parked, and submitted by the thread that settles the predecessor - which only submits it: the signal itself
	 * always runs on the executor, never on the gRPC inbound thread or on the thread blocked in `subscribe()`.
	 *
	 * The dispatch result is ignored, as on every terminal path: a refusal leaves nothing further to escalate to, and
	 * the dispatcher logs it. A refused signal never runs, so it releases its successor at once rather than strand it.
	 *
	 * @param executor    the executor that delivers the signal
	 * @param signal      the delegate callback to run
	 * @param description what the callback does, for the log of a refused dispatch
	 */
	private void dispatchDelegateSignal(
		@Nonnull Executor executor,
		@Nonnull Runnable signal,
		@Nonnull String description
	) {
		final CompletableFuture<Void> delivered = new CompletableFuture<>();
		final CompletableFuture<Void> predecessor = this.lastDelegateSignal.getAndSet(delivered);
		// `whenComplete` rather than `thenRun`: the head of the chain fails together with a failed acknowledgement,
		// and has to release the first signal all the same
		predecessor.whenComplete(
			(ignored, predecessorFailure) -> {
				final Throwable refusal = CdcCallbackDispatcher.dispatch(
					executor,
					() -> {
						try {
							signal.run();
						} finally {
							delivered.complete(null);
						}
					},
					description
				);
				if (refusal != null) {
					delivered.complete(null);
				}
			}
		);
	}

	/**
	 * Called by the subscription when a change capture is ready to be delivered to the delegate subscriber.
	 *
	 * This method forwards the deserialized change capture to the delegate subscriber.
	 *
	 * @param item the deserialized change capture
	 */
	public void onDelegateNext(@Nonnull C item) {
		this.delegate.onNext(item);
	}

	/**
	 * Called by gRPC when the server completes the stream.
	 *
	 * This method delegates to the onComplete method to ensure consistent behavior
	 * regardless of which completion method is called.
	 */
	@Override
	public void onCompleted() {
		this.onComplete();
	}

	/**
	 * Closes this subscriber and cancels the gRPC stream.
	 *
	 * This method is idempotent - calling it multiple times has no additional effect.
	 * It cancels the stream with a special exception that is recognized in the onError method
	 * to distinguish between client-initiated cancellation and other errors.
	 */
	@Override
	public void close() {
		// unblock a caller still waiting in `awaitAcknowledgement` if the subscriber is torn down
		// before the server acknowledged the subscription (no-op once the ACK completed the future)
		this.acknowledged.completeExceptionally(new PublisherClosedByClientException());
		// cancel the subscription if not already cancelled - this will call this close method again
		final ClientSubscription<C, REQ, RES> theSubscription = this.subscription;
		if (theSubscription != null && !theSubscription.isCanceled()) {
			theSubscription.cancel();
		} else if (this.closed.compareAndSet(false, true)) {
			// `close()` can legitimately fire before `beforeStart` (user-initiated abort during
			// stream setup), so the observer and the subscription may both still be null here.
			// snapshot the @Nullable fields and guard each dereference explicitly
			final ClientCallStreamObserver<REQ> observer = this.serverObserver;
			if (observer != null && !this.serverSideClosed.get()) {
				// this will eventually trigger the `onComplete` callback (through `onError` callback) and close this publisher
				observer.cancel("Closed manually by the client.", new PublisherClosedByClientException());
			}
			// if the delegate is closeable, close it quietly — always off this thread. This is the exact
			// submission the issue #1387 stack trace re-entered: the delegate's `close` callback is consumer
			// code that commonly re-subscribes, and running it in place (as `CallerRunsPolicy` did, and as
			// a naive "run the cleanup synchronously" fallback would) walks straight back into
			// `subscribe()` → `awaitAcknowledgement()` on the thread that must deliver the acknowledgement.
			// Driver-side teardown is already complete at this point (`observer.cancel` ran above), so
			// nothing driver-internal depends on this task. Queued behind the terminal signal, if any, so the
			// delegate is closed only after it has been told why.
			final ClientSubscription<C, REQ, RES> activeSubscription = this.subscription;
			if (activeSubscription != null && this.delegate instanceof AutoCloseable closeable) {
				dispatchDelegateSignal(
					activeSubscription.getExecutorService(),
					() -> IOUtils.closeQuietly(closeable::close),
					"close the delegate subscriber"
				);
			}
		}
	}

	@Override
	public String toString() {
		final ClientSubscription<C, REQ, RES> theSubscription = this.subscription;
		return theSubscription == null || theSubscription.getSubscriptionId() == null ?
			"Change capture not yet started or acknowledged." :
			"Change capture: " + theSubscription.getSubscriptionId();
	}

}
