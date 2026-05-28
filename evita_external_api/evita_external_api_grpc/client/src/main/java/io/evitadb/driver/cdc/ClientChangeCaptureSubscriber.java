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
import io.evitadb.api.requestResponse.cdc.ChangeCapture;
import io.evitadb.driver.cdc.ClientChangeCapturePublisher.ClientSubscription;
import io.evitadb.driver.exception.PublisherClosedByClientException;
import io.evitadb.exception.GenericEvitaInternalError;
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
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.atomic.AtomicBoolean;
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
	public void beforeStart(@Nonnull ClientCallStreamObserver<REQ> observer) {
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
		this.subscription = subscription;
	}

	/**
	 * Forwards the subscription to the delegate.
	 *
	 * The field-level binding happens in {@link #attachSubscription} and must
	 * precede the stream-initialization step that opens the inbound credit
	 * window; this method intentionally does no field assignment.
	 *
	 * @param subscription the subscription created by the publisher
	 */
	@Override
	public void onSubscribe(@Nonnull Subscription subscription) {
		this.delegate.onSubscribe(subscription);
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
					try {
						heartBeatSensor.onHeartBeat(heartBeat);
					} catch (Exception e) {
						log.error("Error occurred in HeartBeatSensor while processing heartbeat: {}", heartBeat, e);
					}
				}
			});
		if (activeSubscription.getSubscriptionId() == null) {
			// the very first message MUST be the acknowledgement — otherwise the
			// protocol is being violated and the heartbeat field is still null;
			// `isPremiseValid` is the project's idiomatic precondition check and
			// surfaces the violation as `GenericEvitaInternalError` with the descriptive message
			Assert.isPremiseValid(
				this.lastHeartBeat != null,
				"Expected ACKNOWLEDGEMENT as first message but got something else."
			);
			// IDE-friendly capture: the assert above already proved non-null, so this
			// `requireNonNull` is a no-op at runtime but tells static analyzers the dereference is safe
			final HeartBeat acknowledgement = Objects.requireNonNull(this.lastHeartBeat);
			activeSubscription.setSubscriptionId(acknowledgement.subscriptionId());
			// ACK consumed: open the full window so the server may start streaming captures
			observer.request(this.flowControlWindow);
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
	 * Restores one inbound flow-control credit with the server.
	 *
	 * Called by the owning {@link ClientSubscription} after each capture is delivered
	 * to the delegate. Guarded against post-close calls so a slow consumer that finishes
	 * draining the queue after the stream has been torn down cannot resurrect the channel.
	 */
	void requestOneMore() {
		if (this.serverObserver != null && !this.closed.get() && !this.serverSideClosed.get()) {
			this.serverObserver.request(1);
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
	 * @param throwable the error that occurred
	 */
	@Override
	public void onError(Throwable throwable) {
		this.serverSideClosed.set(true);
		final Throwable rootCause = ExceptionUtils.getRootCause(throwable);
		if (rootCause instanceof PublisherClosedByClientException) {
			// this is expected, we closed the publisher manually
			// apparently, gRPC server doesn't know if cancellation was initiated by the client or by some network error
			// in this case we don't call the on complete, nor on error methods on the delegate
			log.debug("Client change capture publisher was closed manually by the client.", throwable);
		} else if (!this.closed.get()) {
			if (rootCause instanceof TimeoutException) {
				// we don't log timeout exceptions as errors because we expect that the CDC is regularly timed out
				// and then re-established by the client
				log.debug("CDC stream timed out and will be re-established.", throwable);
			} else {
				log.error("Error occurred in the client change capture publisher.", throwable);
			}
			// the publisher always attaches the subscription before the stream initializer runs,
			// so by the time `onError` fires the subscription is guaranteed non-null
			final ClientSubscription<C, REQ, RES> activeSubscription = Objects.requireNonNull(
				this.subscription,
				"Subscription must be attached before `onError` is invoked."
			);
			// we notify the subscriber about the error
			try {
				activeSubscription.getExecutorService().execute(() -> this.delegate.onError(rootCause));
			} finally {
				// this handles cleanup and calling #close on this instance
				activeSubscription.cancel();
			}
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
		if (!this.closed.get()) {
			log.error("Client-side change capture subscription failed.", cause);
			// invoked from `ClientSubscription.consume`, which can only exist once the
			// publisher has attached this subscription, so the field is always non-null
			final ClientSubscription<C, REQ, RES> activeSubscription = Objects.requireNonNull(
				this.subscription,
				"Subscription must be attached before `notifyClientFailureAndClose` is invoked."
			);
			try {
				activeSubscription.getExecutorService().execute(() -> this.delegate.onError(cause));
			} finally {
				// triggers `close()` which still sees `serverSideClosed == false` and therefore
				// propagates the cancellation to the gRPC stream
				activeSubscription.cancel();
			}
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
		if (!this.closed.get()) {
			// gRPC calls `onComplete` only after `beforeStart` returned, by which point
			// the publisher has already attached the subscription
			final ClientSubscription<C, REQ, RES> activeSubscription = Objects.requireNonNull(
				this.subscription,
				"Subscription must be attached before `onComplete` is invoked."
			);
			try {
				activeSubscription.getExecutorService().execute(this.delegate::onComplete);
			} finally {
				// this handles cleanup and calling #close on this instance
				activeSubscription.cancel();
			}
		}
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
		// cancel the subscription if not already cancelled - this will call this close method again
		if (this.subscription != null && !this.subscription.isCanceled()) {
			this.subscription.cancel();
		} else if (this.closed.compareAndSet(false, true)) {
			// `close()` can legitimately fire before `beforeStart` (user-initiated abort during
			// stream setup), so the observer and the subscription may both still be null here.
			// snapshot the @Nullable fields and guard each dereference explicitly
			final ClientCallStreamObserver<REQ> observer = this.serverObserver;
			if (observer != null && !this.serverSideClosed.get()) {
				// this will eventually trigger the `onComplete` callback (through `onError` callback) and close this publisher
				observer.cancel("Closed manually by the client.", new PublisherClosedByClientException());
			}
			// if the delegate is closeable, close it quietly
			final ClientSubscription<C, REQ, RES> activeSubscription = this.subscription;
			if (activeSubscription != null && this.delegate instanceof AutoCloseable closeable) {
				activeSubscription.getExecutorService().execute(() -> IOUtils.closeQuietly(closeable::close));
			}
		}
	}

	@Override
	public String toString() {
		return this.subscription == null || this.subscription.getSubscriptionId() == null ?
			"Change capture not yet started or acknowledged." :
			"Change capture: " + this.subscription.getSubscriptionId();
	}

}
