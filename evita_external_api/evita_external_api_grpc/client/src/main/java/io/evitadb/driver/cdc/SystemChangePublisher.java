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

import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;

import javax.annotation.Nonnull;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.atomic.AtomicLong;

public class SystemChangePublisher implements Flow.Publisher<ChangeSystemCapture> {
    private final List<ManagedSubscription> subscriptions = new LinkedList<>();

    @Override
    public void subscribe(Flow.Subscriber<? super ChangeSystemCapture> subscriber) {
        subscriptions.add(new ManagedSubscription(
            subscriber,
            new AtomicLong(0)
        ));
        subscriber.onSubscribe(new ClientSubscription(
            null,
            this::cancel,
            this::request
        ));
    }

    public boolean submit(ChangeSystemCapture capture) {
        final Iterator<ManagedSubscription> subscribersIterator = subscriptions.iterator();
        while (subscribersIterator.hasNext()) {
            final ManagedSubscription subscription = subscribersIterator.next();
            if (subscription.requestCounter().get() == 0) {
                subscribersIterator.remove();
                continue;
            }
            subscription.requestCounter().decrementAndGet();
            subscription.subscriber().onNext(capture);
        }
        return subscriptions.stream()
            .map(ManagedSubscription::requestCounter)
            .mapToLong(AtomicLong::get)
            .sum() > 0;
    }

    private void request(@Nonnull UUID id, long n) {
        // todo lho
//        assertIsNotClosed();
//        subscriptions.get(id).requestCounter().addAndGet(n);
    }

    private void cancel(@Nonnull UUID id) {
        subscriptions.remove(id);
    }


    private record ManagedSubscription(@Nonnull Subscriber<? super ChangeSystemCapture> subscriber,
                                       @Nonnull AtomicLong requestCounter) {}
}
