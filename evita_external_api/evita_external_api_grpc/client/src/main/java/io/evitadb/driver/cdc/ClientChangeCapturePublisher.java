/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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
import io.evitadb.driver.exception.EvitaClientPoolSaturatedException;
import io.evitadb.externalApi.grpc.requestResponse.cdc.HeartBeat;
import io.evitadb.utils.Assert;
import io.evitadb.utils.IOUtils;
import io.grpc.stub.ClientResponseObserver;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.nio.BufferOverflowException;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
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
 * @param <C>   type of change capture that this publisher publishes
 * @param <REQ> type of request sent to the server
 * @param <RES> type of response received from the server
 * @author Jan Novotný, FG Forrest a.s. (c) 2025
 */
@Slf4j
@ThreadSafe
public abstract class ClientChangeCapturePublisher<C extends ChangeCapture, REQ, RES>
	implements ChangeCapturePublisher<C> {

	/**
	 * Maximum number of captures that can be buffered for each subscriber.
	 * If this limit is reached, an error is reported to the subscriber.
	 */
	private final int queueSize;

	/**
	 * Duration to extend the response timeout for each received message.
	 * This helps keep the streaming connection alive as long as messages are being received.
	 */
	private final Duration streamingTimeout;

	/**
	 * Executor service used to process captures asynchronously for each subscriber.
	 * This allows subscribers to consume captures at their own pace without blocking each other.
	 *
	 * The shared client pool has a bounded backlog and **fails fast** rather than running rejected tasks on
	 * the submitting thread, so a submission here can throw. Refusal is absorbed rather than propagated:
	 * driver-internal cleanup completes in place (see `ClientSubscription#cancel`) and consumer callbacks move
	 * off the submitting thread ({@link CdcCallbackDispatcher}).
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
		@Nonnull Duration streamingTimeout,
		@Nonnull ExecutorService executorService,
		@Nonnull Consumer<ClientResponseObserver<REQ, RES>> streamInitializer,
		@Nonnull Consumer<ClientChangeCapturePublisher<C, REQ, RES>> onCloseCallback
	) {
		this.queueSize = queueSize;
		this.streamingTimeout = streamingTimeout;
		this.executorService = executorService;
		this.streamInitializer = streamInitializer;
		this.onCloseCallback = onCloseCallback;
	}

	/**
	 * Registers a new subscriber to receive change captures from this publisher.
	 *
	 * The wiring order matters: the subscription must be attached to the internal
	 * subscriber and registered with this publisher **before** the gRPC stream
	 * initializer runs. The server ACK that primes the inbound credit window can
	 * land on a different thread, and any of the surrounding bookkeeping (cleanup
	 * on a synchronous init failure, `onNext` dereferencing the subscription
	 * field) must already be in place when it does.
	 *
	 * This call is **blocking**: it returns only once the server has acknowledged the
	 * subscription, so a subsequent call issued by the same thread on the same session is
	 * guaranteed to run after the server-side subscription is established. See
	 * {@link ClientChangeCaptureSubscriber#awaitAcknowledgement()} for the races this closes.
	 *
	 * @param subscriber the subscriber to register
	 * @throws IllegalStateException if the publisher has been closed
	 * @throws io.evitadb.exception.GenericEvitaInternalError if the server does not acknowledge the
	 *         subscription within the streaming timeout or the stream fails during setup
	 */
	@Override
	public void subscribe(Subscriber<? super C> subscriber) {
		assertActive();

		final ClientChangeCaptureSubscriber<C, REQ, RES> internalSubscriber = new ClientChangeCaptureSubscriber<>(
			subscriber,
			this::deserializeAcknowledgementResponse,
			this::deserializeCaptureResponse,
			this.streamingTimeout,
			this.queueSize
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

		// attach the subscription to the internal subscriber BEFORE the stream initializer
		// opens the inbound credit window — otherwise a server ACK that lands on a different
		// thread between `beforeStart` and `onSubscribe` would dereference a null field
		internalSubscriber.attachSubscription(subscription);
		// register the subscription with the publisher BEFORE the stream initializer runs so
		// a synchronous failure during initialization has the subscription available for cleanup
		this.subscriptions.add(subscription);
		// initialize the gRPC stream now that the subscription is wired and registered;
		// a synchronous failure here must remove the zombie subscription before rethrowing,
		// otherwise it would linger in `subscriptions` and keep the publisher from auto-closing
		try {
			this.streamInitializer.accept(internalSubscriber);
		} catch (Throwable ex) {
			this.subscriptions.remove(subscription);
			throw ex;
		}
		// notify the delegate that it has a subscription it can drive
		internalSubscriber.onSubscribe(subscription);
		// block until the server acknowledges the subscription so that a subsequent call issued on
		// the same session runs strictly after the server-side subscription is established —
		// otherwise it would race the still-pending registration offloaded onto the server request
		// pool (concurrent session access), or fire a mutation before this subscriber is wired into
		// the change observer (a missed event). See ClientChangeCaptureSubscriber#awaitAcknowledgement.
		try {
			internalSubscriber.awaitAcknowledgement();
		} catch (RuntimeException ex) {
			// the server never confirmed the subscription within the streaming timeout, or the
			// stream failed during setup — tear the half-open subscription down (which removes it
			// from `subscriptions` and cancels the gRPC stream) before rethrowing to the caller
			subscription.cancel();
			throw ex;
		}
	}

	/**
	 * Checks if the publisher is currently closed.
	 *
	 * @return true if the publisher is closed, false otherwise
	 */
	public boolean isClosed() {
		return !this.active.get();
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
	protected abstract Optional<HeartBeat> deserializeAcknowledgementResponse(RES itemResponse);

	/**
	 * Takes the response from the server representing a single capture and deserializes it into a specific {@link ChangeCapture}.
	 *
	 * This method must be implemented by subclasses to handle the specific type of response received from the server.
	 *
	 * @param itemResponse the response received from the server
	 * @return the deserialized change capture
	 */
	@Nonnull
	protected abstract Optional<C> deserializeCaptureResponse(RES itemResponse);

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
	 * Represents a subscription to the publisher for a specific subscriber.
	 *
	 * This class implements the Flow.Subscription interface and manages the flow control
	 * between the publisher and a subscriber. It maintains a queue of items to be delivered
	 * to the subscriber and processes them asynchronously when requested.
	 *
	 * @param <C>   type of change capture that this subscription handles
	 * @param <REQ> type of request sent to the server
	 * @param <RES> type of response received from the server
	 */
	@ThreadSafe
	static class ClientSubscription<C extends ChangeCapture, REQ, RES>
		implements Subscription, Comparable<ClientSubscription<C, REQ, RES>> {
		/**
		 * Unique identifier for this subscription.
		 * Used for ordering and equality comparisons.
		 */
		private final long id;
		/**
		 * Executor service used to process captures asynchronously. Bounded and fail-fast — every submission
		 * site must tolerate a rejection; see the publisher's field of the same name.
		 */
		@Getter(lombok.AccessLevel.PACKAGE)
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
		private final ArrayBlockingQueue<C> items;
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
		 * Flag indicating whether the subscription has been cancelled.
		 */
		private final AtomicBoolean cancelled = new AtomicBoolean(false);
		/**
		 * Id assigned to this subscription on the server side.
		 */
		@Getter
		@Setter
		private UUID subscriptionId;

		/**
		 * Creates a new subscription for the specified subscriber.
		 *
		 * @param id                 unique identifier for this subscription
		 * @param executorService    executor service used to process captures asynchronously
		 * @param internalSubscriber the internal subscriber that bridges between gRPC and Flow APIs
		 * @param queueSize          maximum number of captures that can be buffered for this subscription
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
			Assert.isPremiseValid(
				n > 0,
				"Number of requested items must be greater than zero."
			);
			// Use safe addition to handle overflow (cap at Long.MAX_VALUE per spec rule 3.17)
			this.requested.accumulateAndGet(
				n, (left, right) -> {
					try {
						return Math.addExact(left, right);
					} catch (ArithmeticException e) {
						return Long.MAX_VALUE;
					}
				}
			);
			consume();
		}

		/**
		 * Cancels the subscription.
		 *
		 * This method closes the internal subscriber, which will eventually
		 * lead to the removal of this subscription from the publisher.
		 *
		 * The cleanup is dispatched to the shared client pool, but **must not be lost when that pool refuses
		 * the task** — a dropped cleanup leaves this subscription in the publisher's `subscriptions`
		 * collection, so the publisher never auto-closes, and the consumer's own "recreate if missing" guard
		 * keeps seeing a dead-but-present subscriber and skips recovery forever. The pool fails fast rather
		 * than running rejected tasks on the caller (see `EvitaClientRejectingExecutorHandler`), so the
		 * rejection is caught here and the cleanup is completed in place.
		 *
		 * Running it in place is safe precisely because the runnable is **driver-internal**: closing the
		 * internal subscriber and de-registering from the publisher are local, non-blocking operations, not
		 * the re-entrant path back into {@link ClientChangeCapturePublisher#subscribe}. The consumer-facing
		 * work reachable from `internalSubscriber.close()` — the delegate's own `close` — is dispatched
		 * off-thread by {@link CdcCallbackDispatcher} at its own submission site.
		 */
		@Override
		public void cancel() {
			if (this.cancelled.compareAndSet(false, true)) {
				log.debug("Cancelling subscription with id {}", this.id);
				final Runnable runnable = () -> {
					// close the internal subscriber
					IOUtils.closeSafely(this.internalSubscriber::close);
					// notify the publisher that this subscription is closed
					this.onCloseCallback.accept(this);
				};
				try {
					this.executorService.execute(runnable);
				} catch (Throwable ex) {
					// the pool is saturated or already shut down — finish the driver-internal cleanup here
					// rather than leaking a zombie subscription
					log.debug(
						"The evitaDB client thread pool refused the cancellation of subscription {}; " +
							"completing it on the calling thread.",
						this.id, ex
					);
					runnable.run();
				}
			}
		}

		/**
		 * Checks if the subscription has been cancelled.
		 *
		 * @return true if the subscription is cancelled, false otherwise
		 */
		public boolean isCanceled() {
			return this.cancelled.get();
		}

		/**
		 * Adds a new capture to this subscription's queue.
		 *
		 * Uses a non-blocking `offer` so the calling thread — the dedicated change-data-capture
		 * event-loop thread, which reads every capture stream this client holds — can never be
		 * parked by a slow downstream consumer. With manual gRPC flow control in place (see
		 * `ClientChangeCaptureSubscriber.beforeStart`) the queue cannot fill under normal
		 * operation; if it ever does, the subscription is marked dead with a
		 * `BufferOverflowException` instead of stalling that event loop.
		 *
		 * @param item the capture to add
		 */
		public void produce(@Nonnull C item) {
			if (this.walkingDead.get() != null) {
				return;
			}
			if (this.items.offer(item)) {
				consume();
			} else {
				this.walkingDead.compareAndSet(
					null,
					new BufferOverflowException()
				);
				consume();
			}
		}

		/**
		 * Compares this subscription to another based on their IDs.
		 *
		 * This method is used to maintain order in the subscriptions collection.
		 *
		 * @param o the subscription to compare to
		 * @return a negative integer, zero, or a positive integer as this
		 * subscription's ID
		 * is less than, equal to, or greater than the specified subscription's
		 * ID
		 */
		@Override
		public int compareTo(@Nonnull ClientSubscription<C, REQ, RES> o) {
			return Long.compare(this.id, o.id);
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

		/**
		 * Checks if this subscription is equal to another object.
		 *
		 * Two subscriptions are considered equal if they have the same ID.
		 *
		 * @param o the object to compare to
		 * @return true if the objects are equal, false otherwise
		 */
		@Override
		public final boolean equals(@Nullable Object o) {
			if (!(o instanceof final ClientSubscription<?, ?, ?> that))
				return false;

			return this.id == that.id;
		}

		/**
		 * Drains buffered items into the delegate subscriber while honouring the
		 * outstanding request count.
		 *
		 * Uses a `currentlyConsuming` CAS so at most one executor task drains the
		 * queue at a time. After the draining loop releases the flag the method
		 * re-checks both the queue and the `walkingDead` slot: a producer (or
		 * `produce()` that raised the overflow signal) that lost the CAS while we
		 * were resetting could otherwise leave its item — or the terminal
		 * failure — stranded forever. The `!cancelled` guard prevents an
		 * infinite reschedule loop once the subscription is dead: `walkingDead`
		 * stays set after the terminal `onError`, but no further work is owed.
		 *
		 * If draining throws, the queue is cleared and the exception is parked
		 * in `walkingDead` so the fall-through delivers it to the subscriber and
		 * cancels the subscription.
		 *
		 * The drain runs consumer code (`Flow.Subscriber#onNext` on the delegate), so it is dispatched
		 * through {@link CdcCallbackDispatcher} and never executed on the calling thread: `consume()` is
		 * reached from `produce()`, i.e. from the gRPC inbound thread, and draining there would hand
		 * arbitrary consumer code to the event loop that has to stay free to read the connection.
		 * For the same reason a refused dispatch is never rethrown — it would escape into a gRPC inbound
		 * callback, which has no defined error path.
		 */
		private void consume() {
			// the walking-dead path still has to drain — schedule a tick to fire `onError`
			// even if the queue is currently empty; otherwise we'd silently stall a
			// subscription whose overflow happened before any consume() was triggered
			if (this.currentlyConsuming.compareAndSet(false, true)) {
				final boolean dispatched = CdcCallbackDispatcher.dispatch(
					this.executorService,
					() -> {
						try {
							while (this.walkingDead.get() == null
								&& !this.items.isEmpty()
								&& this.requested.getAndUpdate(
									counter -> counter > 0 ? counter - 1 : 0) > 0) {
								this.internalSubscriber.onDelegateNext(
									Objects.requireNonNull(this.items.poll())
								);
								// restore one gRPC credit per delivered item — keeps the in-flight
								// window bounded by `queueSize` without sacrificing throughput
								this.internalSubscriber.requestOneMore();
							}
						} catch (Throwable ex) {
							// if an error occurs during consumption, we need to report it to the subscriber
							// clear the items queue and set the walking dead exception
							this.items.clear();
							this.walkingDead.compareAndSet(null, ex);
						}
						// if the walking dead exception is set, notify the subscriber (which closes the
						// subscription); any unconsumed items are dropped because the stream is doomed.
						// `notifyClientFailureAndClose` keeps `serverSideClosed=false` so the subsequent
						// `cancel()` still propagates the cancellation to the gRPC stream — without it
						// the server would keep pushing into a dead client.
						if (this.walkingDead.get() != null) {
							this.items.clear();
							this.internalSubscriber.notifyClientFailureAndClose(
								this.walkingDead.get()
							);
							this.cancel();
						}
						// reset the consuming flag
						this.currentlyConsuming.set(false);
						// re-check after releasing the flag — a producer thread that lost the CAS
						// while we were resetting could otherwise stall its enqueued item or its
						// freshly raised walking-dead signal forever. The `!cancelled` guard
						// prevents an infinite reschedule loop once the subscription has already
						// been terminated (walkingDead stays set, but no further work is owed).
						if (!this.cancelled.get()
							&& (this.walkingDead.get() != null
								|| (!this.items.isEmpty() && this.requested.get() > 0))) {
							this.consume();
						}
					},
					"drain buffered captures to the delegate subscriber"
				);
				if (!dispatched) {
					// The capture callback executor refused the drain, and nothing may run it on this thread -
					// this is reached from `produce()`, i.e. from the gRPC inbound thread. Release the flag for
					// tidiness, but do not treat a later retry as the recovery: `produce()` early-returns once
					// `walkingDead` is set and never calls `consume()` again, and on the healthy path gRPC
					// credit is only restored from inside the drain loop, so the server stops pushing after at
					// most `queueSize` messages either way.
					this.currentlyConsuming.set(false);
					// So the subscription is failed here instead. Only the driver-internal half runs in place -
					// `cancel()` de-registers the subscription so the publisher can auto-close, and the
					// consumer-facing `onError` inside `notifyClientFailureAndClose` is itself dispatched
					// off-thread. Without this the subscription stalls forever with no terminal signal: the
					// silent, permanent outage this whole teardown path exists to prevent.
					if (!this.cancelled.get()) {
						this.walkingDead.compareAndSet(
							null,
							new EvitaClientPoolSaturatedException()
						);
						this.internalSubscriber.notifyClientFailureAndClose(this.walkingDead.get());
						this.cancel();
					}
				}
			}
		}
	}

}
