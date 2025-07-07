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

package io.evitadb.core.cdc;


import io.evitadb.api.requestResponse.cdc.ChangeCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureSubscription;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.function.BiLongConsumer;
import io.evitadb.function.TriConsumer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Implementation of {@link Flow.Subscription} that manages the subscription between a publisher and a subscriber
 * in the Change Data Capture (CDC) system. This class is responsible for handling the flow control (backpressure)
 * by tracking requested items and filling a queue with catalog changes.
 *
 * The subscription maintains a buffer of {@link ChangeCatalogCapture} events and delivers them to the subscriber
 * based on demand. It tracks the last processed version and index to ensure continuity of the event stream.
 *
 * This class implements the reactive streams specification and provides mechanisms for:
 * - Flow control (backpressure) through the request mechanism
 * - Asynchronous processing of catalog change events
 * - Error handling and propagation
 * - Subscription lifecycle management (cancellation, completion)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Slf4j
public class DefaultChangeCaptureSubscription<T extends ChangeCapture> implements ChangeCaptureSubscription {
	/**
	 * Unique identifier for this subscription.
	 */
	@Nonnull @Getter private final UUID subscriptionId;

	/**
	 * Defines the content of the catalog change events.
	 */
	@Nonnull private final ChangeCaptureContent content;

	/**
	 * Executor service used for asynchronous processing of catalog change events.
	 */
	@Nonnull private final ExecutorService executorService;

	/**
	 * Function that fills the queue with catalog change events starting from a specific WAL pointer.
	 */
	@Nonnull private final TriConsumer<WalPointer, DefaultChangeCaptureSubscription<T>, Queue<T>> queueFiller;

	/**
	 * Consumer that is called when the subscription is cancelled.
	 */
	@Nonnull private final Consumer<UUID> onCancellation;

	/**
	 * The subscriber that receives catalog change events from this subscription.
	 */
	@Nonnull private final Subscriber<? super T> subscriber;

	/**
	 * Consumer that updates statistics of captures sent to the subscriber.
	 */
	@Nonnull private final Consumer<T> onNextConsumer;

	/**
	 * Queue that buffers catalog change events before they are delivered to the subscriber.
	 */
	@Nonnull private final Queue<T> queue;

	/**
	 * Flag indicating whether this subscription has been completed or cancelled.
	 */
	@Nonnull private final AtomicBoolean finished = new AtomicBoolean(false);

	/**
	 * Counter tracking the number of items requested by the subscriber but not yet delivered.
	 */
	@Nonnull private final AtomicLong requested = new AtomicLong(0L);

	/**
	 * Lock used to synchronize access to the queue during consumption.
	 */
	@Nonnull private final ReentrantLock lock = new ReentrantLock();

	/**
	 * The version used to track this subscriber in an external cache.
	 */
	private long trackedVersion;

	/**
	 * The version of the last delivered event.
	 */
	private long lastVersion;

	/**
	 * The index within the version of the last delivered event.
	 */
	private int lastIndex;

	/**
	 * Creates a new subscription for catalog change events.
	 *
	 * @param subscriptionId    unique identifier for this subscription
	 * @param bufferSize      size of the buffer queue for catalog change events
	 * @param specification   specification containing the starting point for the subscription and the requested content
	 * @param subscriber      the subscriber that will receive catalog change events
	 * @param queueFiller     function that fills the queue with catalog change events
	 * @param executorService executor service for asynchronous processing
	 */
	public DefaultChangeCaptureSubscription(
		@Nonnull UUID subscriptionId,
		int bufferSize,
		@Nonnull WalPointerWithContent specification,
		@Nonnull Subscriber<? super T> subscriber,
		@Nonnull ExecutorService executorService,
		@Nonnull TriConsumer<WalPointer, DefaultChangeCaptureSubscription<T>, Queue<T>> queueFiller,
		@Nonnull Consumer<T> onNextConsumer,
		@Nonnull Consumer<UUID> onCancellation
	) {
		this.subscriptionId = subscriptionId;
		this.subscriber = subscriber;
		this.queue = new ArrayBlockingQueue<>(bufferSize);
		this.trackedVersion = specification.version();
		this.lastVersion = specification.version();
		this.lastIndex = specification.index() - 1;
		this.content = specification.content();
		this.queueFiller = queueFiller;
		this.onNextConsumer = onNextConsumer;
		this.onCancellation = onCancellation;
		this.executorService = executorService;
		// Register this subscription with the subscriber
		this.subscriber.onSubscribe(this);
	}

	/**
	 * Notifies the subscriber about new catalog change events if there are any requested items.
	 * This method is called when new events become available in the system.
	 */
	public void notifySubscriber() {
		if (!this.finished.get()) {
			if (this.requested.get() > 0 && this.queue.isEmpty()) {
				// If the subscriber requests new CDC events and the queue is empty,
				// trigger asynchronous processing to fetch and deliver new events
				this.executorService.submit(this::consumeQueue);
			}
		}
	}

	/**
	 * Requests a specified number of catalog change events to be delivered to the subscriber.
	 * This method is part of the reactive streams specification for handling backpressure.
	 *
	 * @param n the number of items to request; must be > 0
	 * @throws EvitaInvalidUsageException if n <= 0
	 */
	@Override
	public void request(long n) {
		if (n <= 0) {
			onError(new EvitaInvalidUsageException("Non-positive request"));
		} else {
			// Safely add the requested count, handling potential overflow by capping at Long.MAX_VALUE
			this.requested.accumulateAndGet(
				n, (left, right) -> {
					try {
						return Math.addExact(left, right);
					} catch (ArithmeticException e) {
						return Long.MAX_VALUE;
					}
				});

			// If the subscriber requests new CDC events, trigger processing
			// But only if we're not already inside the onNext method (indicated by lock being held)
			if (!this.lock.isLocked()) {
				consumeQueue();
			}
		}
	}

	/**
	 * Cancels the subscription, preventing any further events from being delivered to the subscriber.
	 * This method is part of the reactive streams specification.
	 */
	@Override
	public void cancel() {
		// Atomically set the finished flag to true if it was false
		if (this.finished.compareAndSet(false, true)) {
			// Clear the queue to release memory
			this.queue.clear();
			this.subscriber.onComplete();
			this.onCancellation.accept(this.subscriptionId);
		}
	}

	/**
	 * Checks if this subscription has been completed or cancelled.
	 *
	 * @return true if the subscription is finished, false otherwise
	 */
	public boolean isFinished() {
		return this.finished.get();
	}

	/**
	 * Signals to the subscriber that the publisher has completed sending events.
	 * This method should be called by the publisher when there are no more events to deliver.
	 */
	public void onComplete() {
		try {
			// Atomically set the finished flag to true if it was false
			if (this.finished.compareAndSet(false, true)) {
				// Clear the queue to release memory
				this.queue.clear();
				// Notify the subscriber that the publisher has completed
				this.subscriber.onComplete();
			}
		} catch (Throwable onCompleteException) {
			// Log any errors that occur during completion notification
			log.error("Error while notifying the subscriber about the completion.", onCompleteException);
		}
	}

	/**
	 * Signals to the subscriber that an error has occurred in the publisher.
	 * This method should be called by the publisher when an error occurs during event processing.
	 *
	 * @param ex the exception that occurred
	 */
	public void onError(Throwable ex) {
		try {
			// Atomically set the finished flag to true if it was false
			if (this.finished.compareAndSet(false, true)) {
				// Clear the queue to release memory
				this.queue.clear();
				// Notify the subscriber about the error
				this.subscriber.onError(ex);
			}
		} catch (Throwable onErrorException) {
			// Log any errors that occur during error notification
			log.error("Error while notifying the subscriber about the error.", onErrorException);
		}
	}

	/**
	 * Retrieves the version used for the last pull of the data from the shared publisher.
	 *
	 * @return the version used for the last pull
	 */
	long getTrackedVersion() {
		return this.trackedVersion;
	}

	/**
	 * Updates the version used for the last pull of catalog data.
	 *
	 * @param trackedVersion the version number representing subscriber in external cache
	 */
	void setTrackedVersion(long trackedVersion, @Nonnull BiLongConsumer onChange) {
		if (this.trackedVersion != trackedVersion) {
			onChange.accept(this.trackedVersion, trackedVersion);
			this.trackedVersion = trackedVersion;
		}
	}

	/**
	 * Processes the queue of catalog change events and delivers them to the subscriber.
	 * This method is responsible for:
	 * 1. Fetching events from the queue
	 * 2. Filling the queue with new events when it's empty
	 * 3. Delivering events to the subscriber
	 * 4. Tracking the last processed version and index
	 * 5. Handling errors during delivery
	 */
	private void consumeQueue() {
		// Synchronize consumption to ensure thread safety
		this.lock.lock();
		try {
			// Continue processing as long as there are requested items and the subscription is active
			while (this.requested.get() > 0 && !this.finished.get()) {
				// Try to get the next event from the queue
				T capture = this.queue.poll();
				if (capture == null) {
					// If the queue is empty, fill it with new events starting from the last processed position
					this.queueFiller.accept(new WalPointer(this.lastVersion, this.lastIndex + 1), this, this.queue);
					// Try again to get an event from the now-filled queue
					capture = this.queue.poll();
					if (capture == null) {
						// If the queue is still empty, we've reached the end of available events
						// and need to wait for more to be generated in the system
						break;
					}
				}

				// Decrement the requested count as we're about to deliver an event
				this.requested.decrementAndGet();
				try {
					// Update tracking information for the last processed event
					this.lastVersion = capture.version();
					this.lastIndex = capture.index();

					// Deliver the event to the subscriber
					final T finalCapture = capture.as(this.content);
					this.onNextConsumer.accept(finalCapture);
					this.subscriber.onNext(finalCapture);
				} catch (Throwable onNextException) {
					// If the subscriber throws an exception during onNext, propagate it and stop processing
					onError(onNextException);
					break;
				}
			}
		} finally {
			// Always release the lock, even if an exception occurs
			this.lock.unlock();
		}
	}

}
