/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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


import io.evitadb.api.requestResponse.cdc.ChangeCapture;
import io.evitadb.driver.cdc.ClientChangeCapturePublisher.ClientSubscription;
import io.evitadb.driver.exception.PublisherClosedByClientException;
import io.evitadb.utils.Assert;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.UUID;
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
 * @param <C> type of change capture that this subscriber handles
 * @param <REQ> type of request sent to the server
 * @param <RES> type of response received from the server
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Slf4j
@RequiredArgsConstructor
public class ClientChangeCaptureSubscriber<C extends ChangeCapture, REQ, RES>
	implements Flow.Subscriber<RES>, ClientResponseObserver<REQ, RES> {

	/**
	 * The delegate subscriber that will receive the deserialized change captures.
	 * This is the actual subscriber that the client code provided to receive the change events.
	 */
	private final Flow.Subscriber<? super C> delegate;

	/**
	 * Function that converts the raw gRPC response into an assigned UUID for acknowledging the subscription setup
	 * on the server side.
	 */
	private final Function<RES, UUID> deserializeAcknowledgeResponse;

	/**
	 * Function that converts the raw gRPC response into a typed change capture object.
	 * This function is provided by the publisher to handle the specific type of response.
	 */
	private final Function<RES, C> deserializeCaptureResponse;

	/**
	 * Flag indicating whether this subscriber has been closed.
	 * Used to prevent multiple close operations and ensure proper cleanup.
	 */
	private final AtomicBoolean closed = new AtomicBoolean(false);

	/**
	 * The gRPC observer that sends requests to and receives responses from the server.
	 * This is initialized in the beforeStart method and used to cancel the stream when closing.
	 */
	private ClientCallStreamObserver<REQ> serverObserver;

	/**
	 * The subscription that manages the flow control between this subscriber and the publisher.
	 * This is set in the onSubscribe method when the publisher creates a subscription for this subscriber.
	 */
	private ClientSubscription<C, REQ, RES> subscription;

	/**
	 * Called by gRPC before starting the stream to provide the observer for sending requests to the server.
	 *
	 * This method initializes the serverObserver field which is later used to cancel the stream when closing.
	 * It ensures that the subscriber can only be started once.
	 *
	 * @param observer the gRPC observer for sending requests to the server
	 * @throws IllegalArgumentException if the subscriber has already been started
	 */
	@Override
	public void beforeStart(ClientCallStreamObserver<REQ> observer) {
		Assert.isPremiseValid(
			this.serverObserver == null,
			"ClientChangeCaptureSubscriber can only be started once. It is already started."
		);

		// netty channel builder doesn't allow for manual flow control (it ignores calling request(n))
		// so we fallback to auto flow control
		this.serverObserver = observer;
	}

	/**
	 * Called when this subscriber is subscribed to a publisher.
	 *
	 * This method stores the subscription for later use in flow control and cancellation.
	 *
	 * @param subscription the subscription created by the publisher
	 */
	@SuppressWarnings("unchecked")
	@Override
	public void onSubscribe(Subscription subscription) {
		this.subscription = (ClientSubscription<C, REQ, RES>) subscription;
		this.delegate.onSubscribe(subscription);
	}

	/**
	 * Called when a new response is received from the server.
	 *
	 * This method deserializes the response into a change capture object and
	 * forwards it to the subscription for processing.
	 *
	 * @param itemResponse the response received from the server
	 */
	@Override
	public void onNext(RES itemResponse) {
		if (this.subscription.getSubscriptionId() == null) {
			// first item is always subscription acknowledge response
			this.subscription.setSubscriptionId(this.deserializeAcknowledgeResponse.apply(itemResponse));
		} else {
			this.subscription.produce(
				this.deserializeCaptureResponse.apply(itemResponse)
			);
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
		if (throwable.getCause() instanceof PublisherClosedByClientException) {
			// this is expected, we closed the publisher manually
			// apparently, gRPC server doesn't know if cancellation was initiated by the client or by some network error
			// in this case we don't call the on complete, nor on error methods on the delegate
			log.debug("Client change capture publisher was closed manually by the client.", throwable);
		} else if (!this.closed.get()) {
			log.error("Error occurred in the client change capture publisher.", throwable);
			// we notify the subscriber about the error
			this.delegate.onError(throwable);
			this.close();
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
		this.delegate.onComplete();
		this.subscription.cancel();
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
	public void close() {
		if (this.closed.compareAndSet(false, true)) {
			// this will eventually trigger the `onComplete` callback (through `onError` callback) and close this publisher
			this.serverObserver.cancel("Closed manually by the client.", new PublisherClosedByClientException());
		}
	}

	@Override
	public String toString() {
		return this.subscription == null || this.subscription.getSubscriptionId() == null ?
			"Change capture not yet started or acknowledged." :
			"Change capture: " + this.subscription.getSubscriptionId();
	}

}
