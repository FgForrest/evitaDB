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
import io.evitadb.api.requestResponse.cdc.SubscriptionImpl;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest;
import io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureResponse;
import io.evitadb.utils.Assert;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.atomic.AtomicLong;

import static io.evitadb.externalApi.grpc.dataType.ChangeDataCaptureConverter.toChangeSystemCapture;
import static io.evitadb.utils.CollectionUtils.createConcurrentHashMap;

/**
 * TODO LHO: docs
 */
@RequiredArgsConstructor
public class ClientChangeCaptureProcessor implements ChangeCapturePublisher<ChangeSystemCapture>, ClientResponseObserver<GrpcRegisterSystemChangeCaptureRequest, GrpcRegisterSystemChangeCaptureResponse> {

    private State state = State.NEW;
    private final Map<UUID, ManagedSubscription<ChangeSystemCapture>> subscriptions = createConcurrentHashMap(1);

    private ClientCallStreamObserver<GrpcRegisterSystemChangeCaptureRequest> observer;

    @Override
    public void subscribe(Subscriber<? super ChangeSystemCapture> subscriber) {
        assertActive();

        Assert.notNull(subscriber, "Subscriber cannot be null.");
        Assert.isTrue(
            subscriptions.values().stream().noneMatch(managedSubscription -> managedSubscription.subscriber() == subscriber),
            "Subscriber is already subscribed to this publisher."
        );

        final SubscriptionImpl subscription = new SubscriptionImpl(
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
    public void beforeStart(ClientCallStreamObserver<GrpcRegisterSystemChangeCaptureRequest> observer) {
        assertNew();
        this.observer = observer;
        observer.disableAutoRequestWithInitial(1);
        this.state = State.ACTIVE;
    }

    @Override
    public void onNext(GrpcRegisterSystemChangeCaptureResponse grpcRegisterSystemChangeCaptureResponse) {
        assertActive();

        final ChangeSystemCapture capture = toChangeSystemCapture(grpcRegisterSystemChangeCaptureResponse.getCapture());

        // multicast the capture to all subscribers that requested more captures
        for (ManagedSubscription<ChangeSystemCapture> managedSubscription : subscriptions.values()) {
            if (managedSubscription.requestCounter().get() == 0) {
                // subscriber doesn't want to receive anymore at the moment, but may change its mind later
                continue;
            }
            managedSubscription.requestCounter().decrementAndGet();
            managedSubscription.subscriber().onNext(capture);
        }
        // netty channel builder doesn't allow for manual flow control using these requests
        observer.request(1);
    }

    @Override
    public void onError(Throwable throwable) {
        assertActive();
        subscriptions.values().forEach(managedSubscription -> managedSubscription.subscriber().onError(throwable));
        subscriptions.clear();
        state = State.CLOSED;
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
            // this will trigger the `onComplete` callback and close this publisher
            observer.cancel("Closed manually by client.", null);
        }
    }

    /**
     * Requests more captures for the given subscription.
     *
     * @param subscriptionId subscription id
     * @param n number of captures to request
     */
    private void requestCapturesForSubscription(@Nonnull UUID subscriptionId, long n) {
        assertActive();
        subscriptions.get(subscriptionId).requestCounter().addAndGet(n);
        this.observer.request(Math.toIntExact(n));
    }

    /**
     * Cancels the given subscription. It will not receive any more captures.
     *
     * @param subscriptionId subscription id
     */
    private void cancelSubscription(@Nonnull UUID subscriptionId) {
        final ManagedSubscription<ChangeSystemCapture> managedSubscription = subscriptions.get(subscriptionId);
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

    private record ManagedSubscription<T extends ChangeCapture>(@Nonnull Subscriber<? super T> subscriber,
                                                                @Nonnull AtomicLong requestCounter) {}
}
