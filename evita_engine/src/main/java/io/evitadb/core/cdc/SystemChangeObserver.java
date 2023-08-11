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

package io.evitadb.core.cdc;

import io.evitadb.api.EvitaContract;
import io.evitadb.api.requestResponse.cdc.CaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureSubscriber;
import io.evitadb.api.requestResponse.cdc.NamedSubscription;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.mutation.Mutation;

import javax.annotation.Nonnull;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Main implementation class handling notification of all requested {@link ChangeSystemCaptureSubscriber}s.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class SystemChangeObserver {
	/**
	 * Index keeping all registered {@link ChangeSystemCaptureSubscriber}s.
	 */
	private final Map<UUID, SubscriberWithSubscriptionContract> systemObservers = new ConcurrentHashMap<>();

	/**
	 * Registers a new subscriber with a first request.
	 * @param subscriber {@link ChangeSystemCaptureSubscriber} to be registered
	 * @see EvitaContract#subscribe(Subscriber)
	 */
	public void registerObserver(@Nonnull ChangeSystemCaptureSubscriber subscriber) {
		final ServerSubscription subscription = new ServerSubscription(
			this::unregisterObserver,
			this::request
		);
		systemObservers.put(
			subscription.id(),
			new SubscriberWithSubscriptionTuple(
				subscriber,
				subscription,
				new CopyOnWriteArrayList<>(
					new ChangeSystemCaptureRequest[] {subscriber.initialSystemCaptureRequest()}
				)
			)
		);
		subscriber.onSubscribe(subscription);
	}

	/**
	 * Returns an existing subscription by its assigned id.
	 * 
	 * @param subscriptionId id of the subscription to be returned
	 * @return {@link Optional} of the subscription
	 */
	@Nonnull
	public Optional<NamedSubscription> getSubscriptionById(@Nonnull UUID subscriptionId) {
		return Optional.ofNullable(systemObservers.get(subscriptionId))
			.map(SubscriberWithSubscriptionContract::subscription);
	}

	/**
	 * Unregisters a subscriber by its assigned id.
	 *
	 * @param subscriptionId id of the subscriber to be unregistered
	 * @return {@code true} if the subscriber was successfully unregistered, {@code false} otherwise
	 * @see ServerSubscription#cancel()
	 */
	public boolean unregisterObserver(@Nonnull UUID subscriptionId) {
		return systemObservers.remove(subscriptionId) != null;
	}

	/**
	 * Requests a specific count of {@link ChangeSystemCapture} events for a given subscription. After the requested
	 * count is depleted, the subscription is automatically unregistered.
	 *
	 * @param subscriptionId id of the subscription to be requested
	 * @param messageCount count of {@link ChangeSystemCapture} events to be requested
	 * @return {@code true} if the request was successfully registered, {@code false} otherwise
	 * @see ServerSubscription#request(long)
	 */
	public boolean request(@Nonnull UUID subscriptionId, long messageCount) {
		final SubscriberWithSubscriptionContract subscriptionTuple = systemObservers.get(subscriptionId);
		if (subscriptionTuple instanceof SubscriberWithSubscriptionTuple newSubscriptionTuple) {
			systemObservers.put(
				newSubscriptionTuple.subscription().id(),
				new SubscriberWithSubscriptionWithCounterTuple(
					newSubscriptionTuple.subscriber(),
					newSubscriptionTuple.subscription(),
					newSubscriptionTuple.requests(),
					new AtomicLong(messageCount)
				)
			);
			return true;
		} else if (subscriptionTuple instanceof SubscriberWithSubscriptionWithCounterTuple counterTuple) {
			counterTuple.counter().addAndGet(messageCount);
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Extends a subscription with an additional {@link ChangeSystemCaptureRequest}.
	 * @param subscriptionId id of the subscription to be extended
	 * @param additionalRequest additional {@link ChangeSystemCaptureRequest} to be added to the subscription
	 * @return {@code true} if the subscription was successfully extended, {@code false} otherwise
	 * @see EvitaContract#extendSubscription(UUID, ChangeSystemCaptureRequest)
	 */
	public boolean extendSubscription(@Nonnull UUID subscriptionId, @Nonnull ChangeSystemCaptureRequest additionalRequest) {
		final SubscriberWithSubscriptionContract subscriptionTuple = systemObservers.get(subscriptionId);
		if (subscriptionTuple != null) {
			if (subscriptionTuple.requests().stream().noneMatch(it -> it.id().equals(additionalRequest.id()))) {
				subscriptionTuple.requests().add(additionalRequest);
				return true;
			}
		}
		return false;
	}

	/**
	 * Limits a subscription with an additional {@link ChangeSystemCaptureRequest}.
	 *
	 * @param subscriptionId id of the subscription to be extended
	 * @param cdcRequestId id of the {@link ChangeSystemCaptureRequest} to be removed from the subscription
	 * @return {@code true} if the subscription was successfully extended, {@code false} otherwise
	 * @see EvitaContract#limitSubscription(UUID, UUID)
	 */
	public boolean limitSubscription(@Nonnull UUID subscriptionId, @Nonnull UUID cdcRequestId) {
		final SubscriberWithSubscriptionContract subscriptionTuple = systemObservers.get(subscriptionId);
		if (subscriptionTuple != null) {
			final boolean removed = subscriptionTuple.requests().removeIf(request -> cdcRequestId.equals(request.id()));
			if (removed && subscriptionTuple.requests().isEmpty()) {
				subscriptionTuple.subscription().cancel();
			}
			return removed;
		}
		return false;
	}


	/**
	 * Notifies all registered {@link ChangeSystemCaptureSubscriber}s about a new {@link ChangeSystemCapture} event.
	 *
	 * @param catalog name of the catalog the event belongs to
	 * @param operation type of the operation the event represents
	 * @param eventSupplier {@link Supplier} of the {@link Mutation} event to be sent
	 */
	public void notifyObservers(@Nonnull String catalog, @Nonnull Operation operation, @Nonnull Supplier<Mutation> eventSupplier) {
		ChangeSystemCapture captureHeader = null;
		ChangeSystemCapture captureBody = null;
		LinkedList<NamedSubscription> toRemove = null;
		for (SubscriberWithSubscriptionContract subscriptionTuple : systemObservers.values()) {
			if (subscriptionTuple instanceof SubscriberWithSubscriptionWithCounterTuple counterTuple) {
				/* TOBEDONE JNO - this should be wrapped in a virtual thread */
				for (ChangeSystemCaptureRequest request : counterTuple.requests()) {
					if (request.content() == CaptureContent.BODY) {
						captureBody = captureBody == null ? new ChangeSystemCapture(request.id(), catalog, operation, eventSupplier.get()) : captureBody;
						counterTuple.subscriber().onNext(captureBody);
					} else {
						captureHeader = captureHeader == null ? new ChangeSystemCapture(request.id(), catalog, operation, null) : captureHeader;
						counterTuple.subscriber().onNext(captureHeader);
					}
					if (counterTuple.messageSent() == 0) {
						if (toRemove == null) {
							toRemove = new LinkedList<>();
						}
						toRemove.add(subscriptionTuple.subscription());
					}
				}
			}
		}

		// unregister all subscribers with depleted request counts
		if (toRemove != null) {
			toRemove.forEach(NamedSubscription::cancel);
		}
	}

	/**
	 * Interface shared between two internal implementation records that needs to be used interchangeably.
	 */
	private sealed interface SubscriberWithSubscriptionContract
		permits SubscriberWithSubscriptionTuple, SubscriberWithSubscriptionWithCounterTuple {

		/**
		 * Returns the {@link ChangeSystemCaptureSubscriber} of this tuple.
		 * @return {@link ChangeSystemCaptureSubscriber} of this tuple
		 */
		@Nonnull
		NamedSubscription subscription();

		/**
		 * Returns the list of all CDC requests for the {@link #subscription()}
		 * @return list of all CDC requests for the {@link #subscription()}
		 */
		@Nonnull
		List<ChangeSystemCaptureRequest> requests();
	}

	/**
	 * Initial tuple for all subscriptions whose {@link Subscription#request(long)} has not been called yet.
	 *
	 * @param subscriber subscriber
	 * @param subscription subscription
	 * @param requests list of all CDC requests for the {@link #subscription()}
	 */
	private record SubscriberWithSubscriptionTuple(
		@Nonnull ChangeSystemCaptureSubscriber subscriber,
		@Nonnull ServerSubscription subscription,
		@Nonnull CopyOnWriteArrayList<ChangeSystemCaptureRequest> requests
	) implements SubscriberWithSubscriptionContract {
	}

	/**
	 * Active tuple for all subscriptions whose {@link Subscription#request(long)} with defined requests and requested
	 * count of events to be sent.
	 *
	 * @param subscriber subscriber
	 * @param subscription subscription
	 * @param counter count of events to be sent (sum of all {@link Subscription#request(long)} calls)
	 * @param requests list of all CDC requests for the {@link #subscription()}
	 */
	private record SubscriberWithSubscriptionWithCounterTuple(
		@Nonnull ChangeSystemCaptureSubscriber subscriber,
		@Nonnull ServerSubscription subscription,
		@Nonnull CopyOnWriteArrayList<ChangeSystemCaptureRequest> requests,
		@Nonnull AtomicLong counter
	) implements SubscriberWithSubscriptionContract {

		/**
		 * Decrements the counter and returns the new value.
		 * @return new value of the counter
		 */
		long messageSent() {
			return counter.decrementAndGet();
		}

	}

}
