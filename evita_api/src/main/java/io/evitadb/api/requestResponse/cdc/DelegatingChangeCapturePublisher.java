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

package io.evitadb.api.requestResponse.cdc;

import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static io.evitadb.utils.CollectionUtils.createConcurrentHashMap;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, 2023
 */
@RequiredArgsConstructor
public class DelegatingChangeCapturePublisher<T extends ChangeCapture> implements ChangeCapturePublisher<T> {

	@Getter private final UUID id = UUID.randomUUID();
	@Getter private final ChangeSystemCaptureRequest request;
	private final Consumer<DelegatingChangeCapturePublisher<T>> terminationCallback;

	private boolean active = true;
	private final Map<UUID, ManagedSubscription<T>> subscriptions = new HashMap<>();

	public DelegatingChangeCapturePublisher(@Nonnull ChangeSystemCaptureRequest request) {
		this(request, it -> {});
	}

	@Override
	public void subscribe(Subscriber<? super T> subscriber) {
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
	public void close() {
		if (active) {
			active = false;
			terminationCallback.accept(this);
			subscriptions.values().forEach(managedSubscription -> managedSubscription.subscriber().onComplete());
			subscriptions.clear();
		}
	}

	/**
	 * Notifies all subscribers about a new {@link ChangeCapture} event.
	 *
	 * @param capture event to be sent to all subscribers that requested more captures
	 */
	public void notifySubscribers(@Nonnull T capture) {
		assertActive();
		// multicast the capture to all subscribers that requested more captures
		for (ManagedSubscription<T> managedSubscription : subscriptions.values()) {
			if (managedSubscription.requestCounter().get() == 0) {
				// subscriber doesn't want to receive anymore at the moment, but may change its mind later
				continue;
			}
			managedSubscription.requestCounter().decrementAndGet();
			managedSubscription.subscriber().onNext(capture);
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
		final ManagedSubscription<T> managedSubscription = subscriptions.get(subscriptionId);
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
		final ManagedSubscription<T> managedSubscription = subscriptions.get(subscriptionId);
		if (managedSubscription == null) {
			throw new EvitaInvalidUsageException("Subscription with id " + subscriptionId + " does not exist. The subscription may have been cancelled.");
		}
		managedSubscription.subscriber().onComplete();
		subscriptions.remove(subscriptionId);
	}

	/**
	 * Verifies this instance is still active.
	 */
	private void assertActive() {
		if (!active) {
			throw new InstanceTerminatedException("change capture publisher");
		}
	}

	private record ManagedSubscription<T extends ChangeCapture>(@Nonnull Subscriber<? super T> subscriber,
	                                                            @Nonnull AtomicLong requestCounter) {}
}
