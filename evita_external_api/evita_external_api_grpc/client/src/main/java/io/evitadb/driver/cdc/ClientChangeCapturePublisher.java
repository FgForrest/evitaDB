/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.driver.exception.ChangeDataCaptureClientCannotKeepUpException;
import io.evitadb.utils.Assert;
import io.evitadb.utils.IOUtils;
import io.grpc.stub.ClientResponseObserver;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Client-side implementation of {@link ChangeCapturePublisher} that is used to publish {@link ChangeCapture}s
 * received from the server using gRPC streaming. This publisher acts as a bridge between the gRPC streaming API
 * and the Java Flow API.
 *
 * The publisher maintains a collection of subscriptions and delegates received captures to all active subscribers.
 * It supports multiple concurrent subscribers and ensures that each subscriber receives the captures it has requested.
 *
 * The publisher uses a queue-based approach to buffer captures for each subscriber, allowing subscribers to consume
 * captures at their own pace. If a subscriber cannot keep up with the rate of incoming captures (queue becomes full),
 * an error is reported to that subscriber.
 *
 * @param <C> type of change capture that this publisher publishes
 * @param <REQ> type of request sent to the server
 * @param <RES> type of response received from the server
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2025
 */
@Slf4j
public abstract class ClientChangeCapturePublisher<C extends ChangeCapture, REQ, RES>
	implements ChangeCapturePublisher<C> {

	/**
	 * Maximum number of captures that can be buffered for each subscriber.
	 * If this limit is reached, an error is reported to the subscriber.
	 */
	private final int queueSize;

	/**
	 * Executor service used to process captures asynchronously for each subscriber.
	 * This allows subscribers to consume captures at their own pace without blocking each other.
	 */
	private final ExecutorService executorService;

	/**
	 * Counter used to generate unique IDs for subscriptions.
	 * Each new subscription gets an incremented value from this counter.
	 */
	private final AtomicLong sequence = new AtomicLong(0);

	/**
	 * Function that initializes the gRPC stream for a new subscriber.
	 * This is called when a new subscriber is registered to set up the connection to the server.
	 */
	private final Consumer<ClientResponseObserver<REQ, RES>> streamInitializer;

	/**
	 * Callback that is executed when the publisher is closed.
	 */
	private final Consumer<ClientChangeCapturePublisher<C, REQ, RES>> onCloseCallback;

	/**
	 * Collection of all active subscriptions managed by this publisher.
	 * Uses a concurrent skip list set to ensure thread safety and ordered iteration.
	 */
	private final Collection<ClientSubscription<C, REQ, RES>> subscriptions = new ConcurrentSkipListSet<>();

	/**
	 * Flag indicating whether this publisher is active.
	 * Set to false when the publisher is closed, preventing new subscriptions.
	 */
	private final AtomicBoolean active = new AtomicBoolean(true);

	public ClientChangeCapturePublisher(
		int queueSize,
		@Nonnull ExecutorService executorService,
		@Nonnull Consumer<ClientResponseObserver<REQ, RES>> streamInitializer,
		@Nonnull Consumer<ClientChangeCapturePublisher<C, REQ, RES>> onCloseCallback
	) {
		this.queueSize = queueSize;
		this.executorService = executorService;
		this.streamInitializer = streamInitializer;
		this.onCloseCallback = onCloseCallback;
	}

	/**
	 * Registers a new subscriber to receive change captures from this publisher.
	 *
	 * This method creates an internal subscriber that wraps the provided subscriber,
	 * initializes the gRPC stream, creates a new subscription, and registers it with
	 * the publisher. The subscriber will start receiving captures once it requests them
	 * through the subscription.
	 *
	 * @param subscriber the subscriber to register
	 * @throws IllegalStateException if the publisher has been closed
	 */
	@Override
	public void subscribe(Subscriber<? super C> subscriber) {
		final ClientChangeCaptureSubscriber<C, REQ, RES> internalSubscriber = new ClientChangeCaptureSubscriber<>(
			subscriber,
			this::deserializeAcknowledgementResponse,
			this::deserializeCaptureResponse
		);

		final ClientSubscription<C, REQ, RES> subscription = new ClientSubscription<>(
			this.sequence.incrementAndGet(),
			this.executorService,
			internalSubscriber,
			this.queueSize,
			theSubscription -> {
				// remove the subscription from the publisher when it's closed
				this.subscriptions.remove(theSubscription);
				if (this.subscriptions.isEmpty()) {
					this.close();
				}
			}
		);

		assertActive();
		// initialize the subscriber
		this.streamInitializer.accept(internalSubscriber);
		this.subscriptions.add(subscription);

		internalSubscriber.onSubscribe(subscription);
	}

	/**
	 * Checks if the publisher is currently closed.
	 * @return true if the publisher is closed, false otherwise
	 */
	public boolean isClosed() {
		return !this.active.get();
	}

	/**
	 * Verifies that the publisher is still active.
	 *
	 * @throws IllegalStateException if the publisher has been closed
	 */
	private void assertActive() {
		if (!this.active.get()) {
			throw new IllegalStateException("Publisher has been already closed.");
		}
	}

	/**
	 * Closes the publisher and all its subscriptions.
	 *
	 * Once closed, the publisher will not accept new subscribers and will not
	 * publish any more captures. All existing subscriptions are cancelled.
	 * This method is idempotent - calling it multiple times has no additional effect.
	 */
	@Override
	public void close() {
		if (this.active.compareAndSet(true, false)) {
			for (ClientSubscription<C, REQ, RES> subscription : this.subscriptions) {
				IOUtils.closeSafely(subscription::cancel);
			}
			this.subscriptions.clear();
			// execute the onClose callback to notify that the publisher is closed
			this.onCloseCallback.accept(this);
		}
	}

	/**
	 * Takes the response from the server representing a single capture and deserializes it into a UUID identification
	 * of the subscriber. The response must be of type acknowledgement, otherwise an exception is thrown.
	 *
	 * @param itemResponse the response received from the server
	 * @return the deserialized UUID of the subscriber
	 */
	@Nonnull
	protected abstract UUID deserializeAcknowledgementResponse(RES itemResponse);

	/**
	 * Takes the response from the server representing a single capture and deserializes it into a specific {@link ChangeCapture}.
	 *
	 * This method must be implemented by subclasses to handle the specific type of response received from the server.
	 *
	 * @param itemResponse the response received from the server
	 * @return the deserialized change capture
	 */
	@Nonnull
	protected abstract C deserializeCaptureResponse(RES itemResponse);

	/**
	 * Represents a subscription to the publisher for a specific subscriber.
	 *
	 * This class implements the Flow.Subscription interface and manages the flow control
	 * between the publisher and a subscriber. It maintains a queue of items to be delivered
	 * to the subscriber and processes them asynchronously when requested.
	 *
	 * @param <C> type of change capture that this subscription handles
	 * @param <REQ> type of request sent to the server
	 * @param <RES> type of response received from the server
	 */
	static class ClientSubscription<C extends ChangeCapture, REQ, RES> implements Subscription, Comparable<ClientSubscription<C, REQ, RES>> {
		/**
		 * Unique identifier for this subscription.
		 * Used for ordering and equality comparisons.
		 */
		private final long id;

		/**
		 * Id assigned to this subscription on the server side.
		 */
		@Getter @Setter private UUID subscriptionId;

		/**
		 * Executor service used to process captures asynchronously.
		 */
		private final ExecutorService executorService;

		/**
		 * The internal subscriber that bridges between gRPC and Flow APIs.
		 */
		private final ClientChangeCaptureSubscriber<C, REQ, RES> internalSubscriber;

		/**
		 * Counter tracking how many items the subscriber has requested but not yet received.
		 */
		private final AtomicLong requested = new AtomicLong(0);

		/**
		 * Queue of captures waiting to be delivered to the subscriber.
		 */
		private final Queue<C> items;

		/**
		 * Flag indicating whether the subscription is currently processing items.
		 * Prevents concurrent processing of items.
		 */
		private final AtomicBoolean currentlyConsuming = new AtomicBoolean(false);
		/**
		 * Callback to be executed when the subscription is closed.
		 */
		private final Consumer<ClientSubscription<C, REQ, RES>> onCloseCallback;
		/**
		 * This reference is used to hold an exception that will be thrown when the queue overflow occurs and contains
		 * exception that will be executed when the queue is depleted, after this fact - subscriber is closed.
		 */
		private final AtomicReference<Throwable> walkingDead = new AtomicReference<>(null);

		/**
		 * Creates a new subscription for the specified subscriber.
		 *
		 * @param id unique identifier for this subscription
		 * @param executorService executor service used to process captures asynchronously
		 * @param internalSubscriber the internal subscriber that bridges between gRPC and Flow APIs
		 * @param queueSize maximum number of captures that can be buffered for this subscription
		 */
		public ClientSubscription(
			long id,
			@Nonnull ExecutorService executorService,
			@Nonnull ClientChangeCaptureSubscriber<C, REQ, RES> internalSubscriber,
			int queueSize,
			@Nonnull Consumer<ClientSubscription<C, REQ, RES>> onCloseCallback
		) {
			this.id = id;
			this.executorService = executorService;
			this.internalSubscriber = internalSubscriber;
			this.items = new ArrayBlockingQueue<>(queueSize);
			this.onCloseCallback = onCloseCallback;
		}

		/**
		 * Called by the subscriber to request more items.
		 *
		 * This method increases the number of requested items and triggers
		 * consumption of any buffered items.
		 *
		 * @param n the number of items to request
		 */
		@Override
		public void request(long n) {
			this.requested.addAndGet(n);
			consume();
		}

		/**
		 * Cancels the subscription.
		 *
		 * This method closes the internal subscriber, which will eventually
		 * lead to the removal of this subscription from the publisher.
		 */
		@Override
		public void cancel() {
			// close the internal subscriber
			IOUtils.closeSafely(this.internalSubscriber::close);
			// notify the publisher that this subscription is closed
			this.onCloseCallback.accept(this);
		}

		/**
		 * Adds a new capture to this subscription's queue.
		 *
		 * If the queue is full, an error is reported to the subscriber.
		 * After adding the item, consumption is triggered if the subscriber
		 * has requested items.
		 *
		 * @param item the capture to add
		 * @throws ChangeDataCaptureClientCannotKeepUpException if the queue is full
		 */
		public void produce(@Nonnull C item) {
			if (this.walkingDead.get() != null || !this.items.offer(item)) {
				this.walkingDead.compareAndSet(
					null,
					new ChangeDataCaptureClientCannotKeepUpException(item.version(), item.index())
				);
			}
			consume();
		}

		/**
		 * Processes buffered items if the subscriber has requested them.
		 *
		 * This method is executed asynchronously to avoid blocking the publisher.
		 * It delivers items to the subscriber until either the queue is empty or
		 * the number of requested items is exhausted.
		 */
		private void consume() {
			if (this.currentlyConsuming.compareAndSet(false, true)) {
				this.executorService.execute(
					() -> {
						try {
							while (!this.items.isEmpty() && this.requested.getAndUpdate(
								counter -> counter > 0 ? counter - 1 : 0) > 0) {
								this.internalSubscriber.onDelegateNext(
									Objects.requireNonNull(this.items.poll())
								);
							}
						} catch (Throwable ex) {
							// if an error occurs during consumption, we need to report it to the subscriber
							// clear the items queue and set the walking dead exception
							this.items.clear();
							this.walkingDead.compareAndSet(null, ex);
						}
						// if there are no more items in the queue and the walking dead exception is set,
						// we need to notify the subscriber about the error (which effectively closes the subscription)
						if (this.items.isEmpty() && this.walkingDead.get() != null) {
							this.internalSubscriber.onError(
								this.walkingDead.get()
							);
						}
						Assert.isPremiseValid(
							this.currentlyConsuming.compareAndSet(true, false),
							"The currently consuming flag should be set to true when consuming items, but it was already set to false."
						);
					}
				);
			}
		}

		/**
		 * Compares this subscription to another based on their IDs.
		 *
		 * This method is used to maintain order in the subscriptions collection.
		 *
		 * @param o the subscription to compare to
		 * @return a negative integer, zero, or a positive integer as this subscription's ID
		 *         is less than, equal to, or greater than the specified subscription's ID
		 */
		@Override
		public int compareTo(ClientSubscription o) {
			return Long.compare(this.id, o.id);
		}

		/**
		 * Checks if this subscription is equal to another object.
		 *
		 * Two subscriptions are considered equal if they have the same ID.
		 *
		 * @param o the object to compare to
		 * @return true if the objects are equal, false otherwise
		 */
		@Override
		public final boolean equals(Object o) {
			if (!(o instanceof final ClientSubscription<?, ?, ?> that)) return false;

			return this.id == that.id;
		}

		/**
		 * Returns a hash code for this subscription based on its ID.
		 *
		 * @return a hash code value for this subscription
		 */
		@Override
		public int hashCode() {
			return Long.hashCode(this.id);
		}
	}

}
