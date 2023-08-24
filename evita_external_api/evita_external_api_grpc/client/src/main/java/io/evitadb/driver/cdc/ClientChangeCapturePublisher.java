/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.driver.cdc;

import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.api.requestResponse.cdc.ChangeCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.cdc.NamedSubscription;
import io.evitadb.driver.exception.PublisherClosedByClientException;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.atomic.AtomicLong;

import static io.evitadb.utils.CollectionUtils.createConcurrentHashMap;

/**
 * Client-side implementation of {@link ChangeCapturePublisher} that is used to publish {@link ChangeSystemCapture}s
 * received from the server using the {@link ClientCallStreamObserver}. It basically just delegates the bulk of received
 * captures to subscribers that requested them.
 *
 * <h3>How it works</h3>
 * <p>
 * It manages subscribers on its own (it doesn't send any info about them to the server), the server just knows about a single
 * observer (this publisher, of course, there may be multiple different concurrent publishers) which receives all captures
 * defined by an initial request.
 * <p>
 * The publisher doesn't have any buffer of captures (there is only buffer on the network side of the gRPC implementation),
 * therefore, if a client doesn't request another captures right away in the {@link Subscriber#onNext(Object)} callback,
 * it may not receive all captures in a consumed sequence of captures (there may be holes). On the other hand, subscriber
 * may stop requesting captures at any time, and start requesting them again later without publisher closing the subscriber.
 *
 * <h3>Back-pressure</h3>
 * <p>
 * Because the used underlying {@link io.grpc.netty.NettyChannelBuilder} doesn't allow for proper manual flow control
 * using {@link ClientCallStreamObserver#request(int)}, we have fallback to using auto flow control between a client and
 * server, and the back-pressure is handled on the client side by this publisher. This means that the server will always
 * send all captures to the backend of client, but if no subscriber requests more captures, the publisher will not just
 * throw them away. More specifically, the connection is using a back-pressure mechanism with the above-mentioned mechanism
 * but is not interconnected with the back-pressure used in the client side subscribers.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public abstract class ClientChangeCapturePublisher<C extends ChangeCapture, REQ, RES> implements ChangeCapturePublisher<C>, ClientResponseObserver<REQ, RES> {

    private State state = State.NEW;
    private final Map<UUID, ManagedSubscription<C>> subscriptions = createConcurrentHashMap(1);

    /**
     * Accepts captures from server.
     */
    private ClientCallStreamObserver<REQ> serverObserver;

    @Override
    public void subscribe(Subscriber<? super C> subscriber) {
        assertActive();

        Assert.notNull(subscriber, "Subscriber cannot be null.");
        Assert.isTrue(
            subscriptions.values().stream().noneMatch(managedSubscription -> managedSubscription.subscriber() == subscriber),
            "Subscriber is already subscribed to this publisher."
        );

        final NamedSubscription subscription = new NamedSubscription(
            this::cancelSubscription,
            this::requestCapturesForSubscription
        );
        subscriptions.put(
            subscription.id(),
            new ManagedSubscription<>(subscriber, new AtomicLong(0))
        );
        subscriber.onSubscribe(subscription);
    }

    @Override
    public void beforeStart(ClientCallStreamObserver<REQ> observer) {
        assertNew();
        // netty channel builder doesn't allow for manual flow control (it ignores calling request(n))
        // so we fallback to auto flow control
        this.serverObserver = observer;
        this.state = State.ACTIVE;
    }

    @Override
    public void onNext(RES itemResponse) {
        assertActive();

        final C capture = deserializeCaptureResponse(itemResponse);

        // multicast the capture to all subscribers that requested more captures
        for (ManagedSubscription<C> managedSubscription : subscriptions.values()) {
            if (managedSubscription.requestCounter().get() == 0) {
                // subscriber doesn't want to receive anymore at the moment, but may change its mind later
                continue;
            }
            managedSubscription.requestCounter().decrementAndGet();
            managedSubscription.subscriber().onNext(capture);
        }
    }

    @Override
    public void onError(Throwable throwable) {
        assertActive();
        if (throwable.getCause() instanceof PublisherClosedByClientException) {
            // this is expected, we closed the publisher manually
            // apparently, gRPC server doesn't know if cancellation was initiated by the client or by some network error
            onCompleted();
        } else {
            // on unknown error, proceed with standard error handling
            subscriptions.values().forEach(managedSubscription -> managedSubscription.subscriber().onError(throwable));
            subscriptions.clear();
            state = State.CLOSED;
        }
    }

    @Override
    public void onCompleted() {
        assertActive();
        subscriptions.values().forEach(managedSubscription -> managedSubscription.subscriber().onComplete());
        subscriptions.clear();
        state = State.CLOSED;
    }

    @Override
    public void close() {
        if (state != State.CLOSED) {
            // this will eventually trigger the `onComplete` callback (through `onError` callback) and close this publisher
            serverObserver.cancel("Closed manually by client.", new PublisherClosedByClientException());
        }
    }

    /**
     * Takes the response from the server representing a single capture and deserializes it into a specific {@link ChangeCapture}.
     */
    protected abstract C deserializeCaptureResponse(RES itemResponse);

    /**
     * Requests more captures for the given subscription.
     *
     * @param subscriptionId subscription id
     * @param n number of captures to request
     */
    private void requestCapturesForSubscription(@Nonnull UUID subscriptionId, long n) {
        assertActive();
        final ManagedSubscription<C> managedSubscription = subscriptions.get(subscriptionId);
        if (managedSubscription == null) {
            throw new EvitaInvalidUsageException("Subscription with id " + subscriptionId + " does not exist. The subscription may have been cancelled.");
        }
        managedSubscription.requestCounter().addAndGet(n);
    }

    /**
     * Cancels the given subscription. It will not receive any more captures.
     *
     * @param subscriptionId subscription id
     */
    private void cancelSubscription(@Nonnull UUID subscriptionId) {
        final ManagedSubscription<C> managedSubscription = subscriptions.get(subscriptionId);
        if (managedSubscription == null) {
            throw new EvitaInvalidUsageException("Subscription with id " + subscriptionId + " does not exist. The subscription may have been cancelled.");
        }
        managedSubscription.subscriber().onComplete();
        subscriptions.remove(subscriptionId);
    }

    /**
     * Verifies this instance is still new and not initialized.
     */
    private void assertNew() {
        if (state != State.NEW) {
            throw new EvitaInvalidUsageException("Client change capture publisher has been already initialized.");
        }
    }

    /**
     * Verifies this instance is still active.
     */
    private void assertActive() {
        if (state != State.ACTIVE) {
            throw new InstanceTerminatedException("client change capture publisher");
        }
    }

    /**
     * State of a publisher.
     */
    private enum State {
        /**
         * Initial state. Before it has been connected to the server.
         */
        NEW,
        /**
         * Active state. It can talk to the server.
         */
        ACTIVE,
        /**
         * Closed state. It cannot talk to the server anymore, all subscriptions have been cancelled.
         */
        CLOSED
    }

    /**
     * Holds a reference to a single subscriber. The {@link #requestCounter()} is used for back-pressure implementation
     * on the client side. The publisher will not send more captures to the subscriber if the counter is zero.
     */
    private record ManagedSubscription<T extends ChangeCapture>(@Nonnull Subscriber<? super T> subscriber,
                                                                @Nonnull AtomicLong requestCounter) {}
}
