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

package io.evitadb.core.cdc;


import io.evitadb.api.requestResponse.cdc.ChangeCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureSubscription;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.function.BiLongConsumer;
import io.evitadb.function.TriConsumer;
import io.evitadb.utils.IOUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

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
	 * Flag indicating whether {@link #releaseRegistration()} has already run. Distinct from {@link #finished}: a
	 * subscription is finished the moment a terminal signal wins the CAS, but its registration may be released
	 * later - or, when the capture executor refuses the deferred task, only by the publisher's periodic sweep.
	 */
	@Nonnull private final AtomicBoolean released = new AtomicBoolean(false);

	/**
	 * Flag indicating that the subscriber's {@code onSubscribe} callback returned normally. Delivery is gated on
	 * this state so no {@code onNext} can overlap the callback that hands the subscription to the subscriber.
	 */
	@Nonnull private final AtomicBoolean activated = new AtomicBoolean(false);

	/**
	 * Single host capture received before activation completes. Guarded by {@link #lock}. A newer capture replaces an
	 * older one because host events are live-tail signals whose version is only for correlation, and they are already
	 * allowed to be dropped when there is no demand.
	 */
	@Nullable private T pendingActivationCapture;

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
	 * Whether this subscription's single unit of version accounting has already been given back to the
	 * publisher. Guarded by {@link #accountingLock}, together with {@link #trackedVersion}.
	 */
	private boolean accountingReleased;

	/**
	 * Guards {@link #trackedVersion} and {@link #accountingReleased}, and nothing else.
	 *
	 * Deliberately not {@link #lock}. That one is held by {@link #consumeQueue()} across a queue fill - a
	 * write-ahead-log scan that keeps reading until it has a bufferful of *matching* captures, so a selective
	 * filter makes it unbounded by the buffer size - and across every `onNext`. Reclaiming the accounting from
	 * `unsubscribe` under that lock would make `cancel()` block for a whole delivery iteration, on threads that
	 * never blocked before it: the gRPC transport-termination handler, and the heartbeat and capture sweep that
	 * share the scheduler's service pool. Mutual exclusion is only ever needed between the two methods below,
	 * so it gets a lock of its own that is never held across I/O.
	 */
	@Nonnull private final ReentrantLock accountingLock = new ReentrantLock();

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
	}

	/**
	 * Hands this subscription to the subscriber through {@code Subscriber#onSubscribe}.
	 *
	 * Deliberately not done by the constructor. The constructor runs inside the publisher's
	 * {@code subscribers.computeIfAbsent(...)}, and a subscriber may legally cancel or {@link #request(long)}
	 * from `onSubscribe` - both of which call straight back into the publisher. That put user code inside a
	 * {@link java.util.concurrent.ConcurrentHashMap} mapping function, where removing the key being computed
	 * throws {@code IllegalStateException("Recursive update")}, and where any escape leaves the registration's
	 * bookkeeping applied with no map entry for the periodic sweep to ever find.
	 *
	 * Activating once the entry is published makes both safe: a re-entrant `cancel()` now unregisters through
	 * the ordinary path, and a throwing `onSubscribe` leaves a registration the caller can roll back.
	 *
	 * **Ordering.** The subscription is visible to concurrent publishers before this method runs. An immediate host
	 * capture arriving while {@code onSubscribe} is running is retained in a single pending slot rather than delivered
	 * or made to wait. Once the callback returns, this method takes the delivery lock, enables delivery, drains WAL
	 * captures for demand raised inside the callback, and only then flushes the pending host capture. Thus the host
	 * capture cannot overtake an earlier WAL capture or overlap {@code onSubscribe}.
	 */
	void activate() {
		boolean callbackCompleted = false;
		try {
			this.subscriber.onSubscribe(this);
			callbackCompleted = true;
		} finally {
			if (!callbackCompleted) {
				discardPendingActivationCapture();
			}
		}

		this.lock.lock();
		try {
			if (this.finished.get()) {
				this.pendingActivationCapture = null;
				return;
			}
			this.activated.set(true);
			final T pendingCapture = this.pendingActivationCapture;
			this.pendingActivationCapture = null;
			if (this.requested.get() > 0L) {
				consumeQueue();
			}
			if (pendingCapture != null) {
				deliverImmediate(pendingCapture);
			}
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * Notifies the subscriber about new catalog change events if there are any requested items.
	 * This method is called when new events become available in the system.
	 */
	public void notifySubscriber() {
		if (this.activated.get() && !this.finished.get()) {
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
	 * A non-positive {@code n} is a protocol violation on the subscriber's side, and it is reported rather than
	 * thrown: an {@link EvitaInvalidUsageException} is handed to {@link #onError(Throwable)}, which terminates
	 * the subscription. {@link Flow.Subscription#request(long)} must never throw, so this method does not.
	 *
	 * @param n the number of items to request; must be > 0
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
			if (this.activated.get() && this.lock.tryLock()) {
				try {
					consumeQueue();
				} finally {
					if (this.lock.isHeldByCurrentThread()) {
						this.lock.unlock();
					}
				}
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
			discardPendingActivationCapture();
			// Clear the queue to release memory
			this.queue.clear();
			// cancel() is invoked by whoever owns the subscription, from outside the delivery path, so the
			// release may run on the calling thread - unlike the two terminal signals, see
			// #releaseRegistrationLater()
			releaseRegistration();
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
		// Atomically set the finished flag to true if it was false
		if (this.finished.compareAndSet(false, true)) {
			discardPendingActivationCapture();
			// Clear the queue to release memory
			this.queue.clear();
			try {
				// Notify the subscriber that the publisher has completed
				this.subscriber.onComplete();
			} catch (Throwable onCompleteException) {
				// Log any errors that occur during completion notification
				log.error("Error while notifying the subscriber about the completion.", onCompleteException);
			}
			releaseRegistrationLater();
		}
	}

	/**
	 * Signals to the subscriber that an error has occurred in the publisher.
	 * This method should be called by the publisher when an error occurs during event processing.
	 *
	 * @param ex the exception that occurred
	 */
	public void onError(Throwable ex) {
		// Atomically set the finished flag to true if it was false
		if (this.finished.compareAndSet(false, true)) {
			discardPendingActivationCapture();
			// Clear the queue to release memory
			this.queue.clear();
			try {
				// Notify the subscriber about the error
				this.subscriber.onError(ex);
			} catch (Throwable onErrorException) {
				// Log any errors that occur during error notification
				log.error("Error while notifying the subscriber about the error.", onErrorException);
			}
			releaseRegistrationLater();
		}
	}

	/**
	 * Releases everything this subscription holds outside itself: its registration with the publisher - which is
	 * what keeps the WAL version it last read pinned in the publisher's ring buffer - and the subscriber, when
	 * that owns a closeable resource such as a transport stream.
	 *
	 * All three terminal signals must reach this, not only {@link #cancel()}. The `finished` CAS lets exactly one
	 * of `cancel()`, {@link #onComplete()} and {@link #onError(Throwable)} win, and whichever wins it is the last
	 * thing that ever runs for this subscription - a later `cancel()`, including the one the transport layer makes
	 * when it notices the stream is dead, returns immediately because `finished` is already set. So a terminal
	 * signal that skips the release leaves the entry in the publisher's subscribers map for the lifetime of the
	 * process: the shared publisher can never be retired, the ring buffer can never be trimmed past the dead
	 * subscriber's tracked version, and the subscriber statistics keep counting it.
	 */
	private void releaseRegistration() {
		// three callers can reach this - cancel(), the deferred task and the publisher's periodic sweep - and
		// two of them can reach it for the same subscription, because the sweep exists precisely for the case
		// where it cannot be known whether the deferred task ran. Releasing twice would unsubscribe an id the
		// publisher may since have reused and close the subscriber's transport a second time, so exactly one
		// caller is let through.
		if (this.released.compareAndSet(false, true)) {
			// The CAS is flipped before either half runs, so whatever happens here happens once and never
			// again - `cancel()`, the deferred task and the sweep all short-circuit on it afterwards. Closing
			// the subscriber therefore cannot sit after the deregistration and depend on it returning: a throw
			// from `onCancellation` - which reaches the publisher's `checkSubscribersLeft`, `close()` and the
			// observer's `onClose` lambda, none of them throw-free by construction - would strand the
			// subscriber's transport for the lifetime of the process, with no caller able to retry it.
			try {
				this.onCancellation.accept(this.subscriptionId);
			} finally {
				if (this.subscriber instanceof AutoCloseable closeable) {
					IOUtils.closeQuietly(closeable::close);
				}
			}
		}
	}

	/**
	 * Releases this subscription's registration if a terminal signal has already been raised for it, and reports
	 * whether it had been. Invoked by the shared publisher's periodic sweep - {@link #releaseRegistrationLater()}
	 * hands the release to the capture executor, which may refuse it, and once `finished` is set nothing else
	 * ever runs for this subscription to retry it.
	 *
	 * Safe to call repeatedly and from any thread: it releases at most once, and it is a no-op on a subscription
	 * that is still live. The sweep runs on the scheduler rather than on the delivery path, so it holds no
	 * subscription lock - the reason the terminal signals defer instead of releasing inline.
	 *
	 * @return {@code true} if this subscription had terminated - whether or not this call was the one that
	 *         released it
	 */
	boolean releaseIfTerminated() {
		if (this.finished.get()) {
			releaseRegistration();
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Performs {@link #releaseRegistration()} on the capture executor rather than on the calling thread, because
	 * the terminal signals are raised from {@link #consumeQueue()} and the immediate host-event path while this
	 * subscription's delivery lock is held. The release reaches the publisher's `checkSubscribersLeft()` and
	 * from there its retirement, which takes the publisher's own lock - so an inline release is the one thing
	 * that puts a subscription lock beneath a publisher lock. Nothing takes them the other way round (the
	 * publisher cancels its subscriptions only after letting go of its lock), so this is defence rather than a
	 * live deadlock, and deferring keeps it that way independently of that choice. Note it is not defence under
	 * the `ImmediateExecutorService` the functional suite boots with, which runs the deferred task inline: that
	 * is precisely the configuration that exercises the inline ordering.
	 *
	 * A second reason used to apply and no longer does: {@link #request(long)} reaches `consumeQueue()` from the
	 * subscriber's own `onSubscribe`, which once ran inside the `ConcurrentHashMap#computeIfAbsent` registering
	 * this subscription, so an inline release removed a key from within its own mapping function.
	 * {@link #activate()} now runs after that entry is published, which closes it at the source.
	 *
	 * It goes to the engine's own capture executor - the one {@link #notifySubscriber()} already submits to -
	 * and not to a shared pool: the release exists to stop a WAL version being pinned, so a release that waits
	 * behind unrelated work, or that is dropped at shutdown because nothing owns the thread running it,
	 * reproduces the very leak it is there to prevent. Queueing behind a `consumeQueue()` already running on
	 * that executor is safe, because nothing here waits for the submitted task.
	 *
	 * The release is therefore eventual rather than immediate: a subscriber observing its terminal signal cannot
	 * assume the publisher has already forgotten the subscription.
	 *
	 * This path is best-effort and must not be the only one. The capture executor rejects a submission both at
	 * shutdown and when its bounded queue is full, and by the time either happens `finished` is already set, so
	 * nothing else would ever run for this subscription to retry - which is the leak this method exists to
	 * prevent, reproduced under load. The guarantee therefore lives with the publisher's periodic sweep, which
	 * runs on the scheduler and cannot be starved by the capture executor; see {@link #releaseIfTerminated()}.
	 */
	private void releaseRegistrationLater() {
		try {
			this.executorService.submit(
				() -> {
					try {
						releaseRegistration();
					} catch (Throwable releaseException) {
						// nothing waits on the result, so an unlogged failure here would leave a permanently
						// pinned WAL version with nothing anywhere to say why
						log.error(
							"Failed to release the terminated capture subscription `{}`.",
							this.subscriptionId, releaseException
						);
					}
				}
			);
		} catch (RejectedExecutionException ex) {
			// NOT only shutdown: the capture executor is an ObservableThreadExecutor, whose
			// EvitaRejectingExecutorHandler throws this same exception when its bounded queue is full. Under load
			// the release is therefore refused rather than deferred - and `finished` is already set, so no later
			// cancel() can retry it. The publisher's periodic sweep is what makes that survivable; see
			// #releaseIfTerminated(). Logged at debug because the sweep picks it up within its interval.
			log.debug(
				"Capture subscription `{}` could not hand its release to the capture executor; it will be " +
					"released by the publisher's periodic sweep.",
				this.subscriptionId
			);
		}
	}

	/**
	 * Delivers a single capture to the subscriber out-of-band — without affecting the WAL pointer
	 * tracking (`lastVersion` / `lastIndex`) or interleaving with the ring-buffer-driven queue
	 * fill cycle. Used by the system stream to dispatch host events that
	 * are emitted independently of the engine WAL: they carry a snapshot engine version for
	 * correlation only and must NOT advance the subscriber's replay pointer.
	 *
	 * **Backpressure.** Honors the reactive-streams demand counter — if `requested == 0` at the
	 * moment of delivery the capture is silently dropped (host events are live-tail only and
	 * have no replay path). Otherwise the demand is decremented exactly once.
	 *
	 * **Ordering.** Acquires the same lock as `consumeQueue` so the host-event `onNext` cannot
	 * interleave with a queued mutation `onNext`, preserving the strict-ordering guarantee
	 * documented on the host-event contract. Before activation it only replaces the pending slot and returns without
	 * waiting for or invoking subscriber code. After activation it may wait behind a queue fill or another
	 * {@code onNext}, and its own delivery invokes the subscriber's {@code onNext} on the calling thread.
	 *
	 * @param capture the capture to deliver immediately; never `null`
	 */
	public void deliverImmediate(@Nonnull T capture) {
		this.lock.lock();
		try {
			if (this.finished.get()) {
				return;
			}
			if (!this.activated.get()) {
				this.pendingActivationCapture = capture;
				return;
			}
			final long demand = this.requested.get();
			if (demand <= 0L) {
				// No subscriber demand — drop the event (live-tail only, no replay).
				return;
			}
			this.requested.decrementAndGet();
			try {
				final T finalCapture = capture.as(this.content);
				this.onNextConsumer.accept(finalCapture);
				this.subscriber.onNext(finalCapture);
			} catch (Throwable onNextException) {
				onError(onNextException);
			}
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * Clears a capture retained for post-activation delivery. The terminal flag is set before this method is called,
	 * so a concurrent pre-activation delivery either observes termination and does nothing or publishes its capture
	 * under the lock before this method clears it. Once activated, the slot has already been moved to the activation
	 * thread and there is nothing here to clear.
	 */
	private void discardPendingActivationCapture() {
		if (!this.activated.get()) {
			this.lock.lock();
			try {
				this.pendingActivationCapture = null;
			} finally {
				this.lock.unlock();
			}
		}
	}

	/**
	 * Marks this subscription dead without signalling the subscriber in any way.
	 *
	 * For a registration the publisher is retracting before {@link #activate()} ever ran. The subscriber was
	 * never handed this subscription, so there is nothing it may legitimately be told - and telling it is not
	 * harmless: the gRPC subscriber is {@link AutoCloseable} and its `close()` sends the client
	 * `UNAVAILABLE`, which would end the very stream the caller is about to retry on a renewed publisher.
	 * Rolling back through {@code unsubscribe} does exactly that, so retraction must not go near it.
	 *
	 * Setting both flags is what makes the retraction final: every terminal signal and every release path
	 * short-circuits on them, so a publisher close racing this cannot signal the subscriber either.
	 */
	void retract() {
		this.finished.set(true);
		this.released.set(true);
		discardPendingActivationCapture();
		this.queue.clear();
	}

	/**
	 * Moves the version used for the last pull of catalog data, reporting the move so the publisher can
	 * follow it in the map that gates ring-buffer trimming.
	 *
	 * A subscription owns exactly one unit of that accounting, and exactly one code path may release it.
	 * This runs on the delivery thread, from {@link #consumeQueue()} by way of the queue filler, and the
	 * fill it follows reads the write-ahead log - so it can still be in flight long after another thread
	 * cancelled this subscription and {@link #releaseAccounting(LongConsumer)} gave the unit back. Moving it
	 * afterwards would hand a second subscriber's slot away: the decrement lands on a version this
	 * subscription no longer holds, and the publisher then trims the ring buffer past captures a live
	 * subscriber still needs. Both halves therefore run under the same lock and the flag settles which won.
	 *
	 * @param trackedVersion the version number representing this subscriber in the publisher's cache
	 * @param onChange       invoked with (previous, next) when the move is accepted; never after release
	 */
	void setTrackedVersion(long trackedVersion, @Nonnull BiLongConsumer onChange) {
		this.accountingLock.lock();
		try {
			if (!this.accountingReleased && this.trackedVersion != trackedVersion) {
				onChange.accept(this.trackedVersion, trackedVersion);
				this.trackedVersion = trackedVersion;
			}
		} finally {
			this.accountingLock.unlock();
		}
	}

	/**
	 * Gives back this subscription's single unit of version accounting, exactly once.
	 *
	 * Called by the publisher's {@code unsubscribe} once it has won the removal from the subscribers map.
	 * The release and {@link #setTrackedVersion(long, BiLongConsumer)} share {@link #accountingLock}, so the
	 * version reported here is the one the subscription actually held: an in-flight fill either completed its
	 * move before this ran - in which case the later version is reported - or finds the flag set and makes no
	 * move at all. A second call does nothing, which is what makes the sweep and an explicit cancel safe to
	 * race. It never takes the delivery lock, so `cancel()` stays non-blocking.
	 *
	 * @param onRelease invoked with the version this subscription held, and only on the first call
	 */
	void releaseAccounting(@Nonnull LongConsumer onRelease) {
		this.accountingLock.lock();
		try {
			if (!this.accountingReleased) {
				this.accountingReleased = true;
				onRelease.accept(this.trackedVersion);
			}
		} finally {
			this.accountingLock.unlock();
		}
	}

	/**
	 * Processes the queue of catalog change events and delivers them to the subscriber.
	 * This method is responsible for:
	 * 1. Fetching events from the queue
	 * 2. Filling the queue with new events when it's empty
	 * 3. Delivering events to the subscriber
	 * 4. Tracking the last processed version and index
	 * 5. Reporting a failure of either the fill or the delivery to the subscriber through
	 *    {@link #onError(Throwable)} and leaving the loop
	 *
	 * Neither failure may escape this method, and both entry points explain why. {@link #request(long)} calls it
	 * on the caller's thread, and {@link Flow.Subscription#request(long)} must never throw - so a throw would
	 * reach the gRPC producer loop or embedded application code. {@link #notifySubscriber()} submits it to the
	 * capture executor instead, where a throw would die inside the task with nothing to observe it, leaving the
	 * subscription registered with its publisher and the WAL version it tracks pinned for the lifetime of the
	 * process. Routing both to {@link #onError(Throwable)} terminates the subscription and releases that
	 * registration - see {@link #releaseRegistrationLater()} for why the release does not run on this thread.
	 */
	private void consumeQueue() {
		if (!this.activated.get()) {
			return;
		}
		// Synchronize consumption to ensure thread safety
		this.lock.lock();
		try {
			// Continue processing as long as there are requested items and the subscription is active
			while (this.requested.get() > 0 && !this.finished.get()) {
				// Try to get the next event from the queue
				T capture = this.queue.poll();
				if (capture == null) {
					// If the queue is empty, fill it with new events starting from the last processed position
					try {
						this.queueFiller.accept(new WalPointer(this.lastVersion, this.lastIndex + 1), this, this.queue);
					} catch (Throwable fillException) {
						// The filler reads the write-ahead log, so it can fail for reasons that have nothing to
						// do with the subscriber - and that failure has to reach the subscriber all the same.
						// Left uncaught it escapes this method entirely: `finished` stays false, so the
						// subscription is never removed from the publisher, the WAL version it pins is never
						// released, and the subscriber is told neither onError nor onComplete - it simply stops
						// receiving events for the lifetime of the process. On the `request(n)` path the same
						// throw also propagates out of Flow.Subscription#request(long), which reactive-streams
						// forbids from throwing, into the gRPC producer loop or into embedded caller code.
						onError(fillException);
						break;
					}
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
