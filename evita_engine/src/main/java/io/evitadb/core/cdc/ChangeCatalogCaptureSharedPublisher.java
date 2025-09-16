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


import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.mutation.CatalogBoundMutation;
import io.evitadb.api.requestResponse.mutation.MutationPredicate;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.core.Catalog;
import io.evitadb.core.cdc.ChangeCaptureRingBuffer.OutsideScopeException;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.UUIDUtil;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * A shared publisher implementation for Change Data Capture (CDC) functionality that efficiently distributes
 * catalog change events to multiple subscribers. This class implements the reactive streams pattern through
 * the {@link Flow.Publisher} interface.
 *
 * The publisher maintains a ring buffer of recent changes in memory for efficient access and falls back to
 * reading from the Write-Ahead Log (WAL) when requested changes are no longer in the buffer. It uses a shared
 * predicate to filter mutations that are relevant to the subscribers based on the provided criteria.
 *
 * This implementation optimizes memory usage by tracking the number of subscribers for each catalog version,
 * allowing it to clean up data that is no longer needed. It also provides thread-safe access to the change
 * catalog captures.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Slf4j
public class ChangeCatalogCaptureSharedPublisher implements Flow.Publisher<ChangeCatalogCapture>, AutoCloseable {
	/**
	 * Flag indicating whether this publisher has been closed. Once closed, no new subscriptions
	 * will be accepted and existing ones will be cancelled.
	 */
	private final AtomicBoolean closed = new AtomicBoolean(false);

	/**
	 * Lock for synchronizing close and subscription operations. This ensures that no new subscriptions
	 * are added when publisher is closed.
	 */
	private final ReentrantLock lock = new ReentrantLock();

	/**
	 * Executor service for handling CDC operations asynchronously. Used to process
	 * change catalog captures and deliver them to subscribers.
	 */
	private final ExecutorService cdcExecutor;

	/**
	 * Consumer that updates statistics of captures sent to the subscriber.
	 */
	@Nonnull private final Consumer<ChangeCatalogCapture> onNextConsumer;

	/**
	 * Consumer that is called when the publisher is closed. This can be used to perform
	 * cleanup operations or notify other components that the publisher is no longer active.
	 */
	@Nonnull private final Consumer<ChangeCatalogCriteriaBundle> onClose;

	/**
	 * Map of active subscriptions, keyed by their unique identifier. This allows
	 * efficient lookup and management of subscriptions.
	 */
	private final Map<UUID, DefaultChangeCaptureSubscription<ChangeCatalogCapture>> subscribers = CollectionUtils.createConcurrentHashMap(64);

	/**
	 * Reference to the current catalog. This is updated whenever a new catalog version
	 * becomes available in the live view.
	 */
	private final AtomicReference<Catalog> currentCatalog;

	/**
	 * Ring buffer that stores recent change catalog captures in memory for efficient access.
	 * When the buffer is full, older entries are evicted.
	 */
	private final ChangeCaptureRingBuffer<ChangeCatalogCapture> lastCaptures;

	/**
	 * The maximum number of change catalog captures that can be buffered for each subscriber.
	 * This helps prevent memory issues when subscribers process data slowly.
	 */
	private final int subscriberBufferSize;

	/**
	 * The criteria used to filter mutations. Only mutations that match these criteria
	 * will be converted to change catalog captures and published to subscribers.
	 */
	private final ChangeCatalogCriteriaBundle criteria;

	/**
	 * A shared predicate created from the criteria, used to efficiently filter mutations.
	 * This predicate is not thread-safe and should only be used from the publisher thread.
	 */
	private final MutationPredicate sharedPredicate;

	/**
	 * Map tracking the number of subscribers for each catalog version. This allows
	 * efficient cleanup of data that is no longer needed by any subscriber - i.e. lowering memory usage.
	 */
	private final ConcurrentSkipListMap<Long, Integer> versionSubscribersCount = new ConcurrentSkipListMap<>();

	/**
	 * Constructs a new shared publisher for change catalog captures.
	 *
	 * @param catalog              the initial catalog to use as a reference point
	 * @param cdcExecutor          the executor service for handling CDC operations asynchronously
	 * @param bufferSize           the size of the ring buffer for storing recent changes
	 * @param subscriberBufferSize the size of the buffer for each subscriber
	 * @param criteria             the criteria used to filter mutations for this publisher
	 */
	public ChangeCatalogCaptureSharedPublisher(
		@Nonnull Catalog catalog,
		@Nonnull ExecutorService cdcExecutor,
		int bufferSize,
		int subscriberBufferSize,
		@Nonnull ChangeCatalogCriteriaBundle criteria,
		@Nonnull Consumer<ChangeCatalogCapture> onNextConsumer,
		@Nonnull Consumer<ChangeCatalogCriteriaBundle> onClose
	) {
		this.currentCatalog = new AtomicReference<>(catalog);
		final long currentCatalogVersion = catalog.getVersion();
		this.cdcExecutor = cdcExecutor;
		this.onNextConsumer = onNextConsumer;
		this.onClose = onClose;
		// Initialize the ring buffer with the current catalog version
		this.lastCaptures = new ChangeCaptureRingBuffer<>(
			// we need to use the current catalog version plus one, because the current catalog version mutations will not
			// be in memory, but only in the WAL, this version will enforce reading them from WAL
			currentCatalogVersion + 1, 0,
			currentCatalogVersion, bufferSize,
			ChangeCatalogCapture.class
		);
		this.subscriberBufferSize = subscriberBufferSize;
		this.criteria = criteria;
		// Create a shared predicate that will be used to filter mutations
		this.sharedPredicate = this.criteria.createPredicate(null, null);
	}

	/**
	 * Notifies the publisher that the provided catalog is now present in the live view.
	 * Updates the currently active catalog, notifies all active subscriptions about the new catalog,
	 * and manages cleanup of expired data in the ring buffer and subscription statistics.
	 * If there are no active subscribers remaining, the publisher is closed.
	 *
	 * @param catalog the catalog instance now present in the live view; must not be null
	 */
	public void notifyCatalogPresentInLiveView(@Nonnull Catalog catalog) {
		// we don't actively check for non-closed condition here to speed up the process
		this.currentCatalog.set(catalog);
		this.lastCaptures.setEffectiveLastCatalogVersion(catalog.getVersion());
		// notify all subscriptions that the catalog is now present in the live view
		// each active subscription is notified to pull new data asynchronously from this instance
		for (DefaultChangeCaptureSubscription<ChangeCatalogCapture> subscription : this.subscribers.values()) {
			if (!subscription.isFinished()) {
				subscription.notifySubscriber();
			}
		}
	}

	/**
	 * Checks whether there is any subscriber left. If there are no subscribers, it closes the publisher.
	 */
	public void checkSubscribersLeft() {
		// if no subscriber is left, close this publisher
		if (this.subscribers.isEmpty()) {
			close();
		} else {
			clearUnusedDataInRingBuffer();
		}
	}

	/**
	 * Processes a mutation and adds it to the ring buffer if it matches the shared predicate.
	 * This method is called for each mutation that occurs in the catalog.
	 *
	 * @param mutation the mutation to process
	 */
	public void processMutation(@Nonnull CatalogBoundMutation mutation) {
		// we don't actively check for non-closed condition here to speed up the process
		// only process mutations that match our criteria
		// convert the mutation to change catalog captures (if any) and add them to the ring buffer
		mutation.toChangeCatalogCapture(this.sharedPredicate, ChangeCaptureContent.BODY)
			.forEach(this.lastCaptures::offer);
	}

	/**
	 * Forgets all mutations after the specified catalog version. This is used to clear
	 * the ring buffer and subscription statistics when a rollback or reprocessing is needed.
	 *
	 * @param catalogVersion the catalog version after which all mutations should be forgotten
	 */
	public void forgetMutationsAfter(long catalogVersion) {
		this.lastCaptures.clearAllAfter(catalogVersion);
	}

	/**
	 * Subscribes to receive change catalog captures starting from the next version after the current catalog version.
	 * This is the implementation of the {@link Flow.Publisher} interface.
	 *
	 * @param subscriber the subscriber that will receive the change catalog captures
	 * @throws InstanceTerminatedException if the publisher is closed
	 */
	@Override
	public void subscribe(Subscriber<? super ChangeCatalogCapture> subscriber) {
		this.lock.lock();
		try {
			assertActive();
			final long version = getCatalog().getVersion();
			// Subscribe starting from the next version after the current catalog version
			subscribe(
				subscriber,
				new WalPointerWithContent(version + 1, 0, ChangeCaptureContent.BODY)
			);
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * Unsubscribes a subscriber identified by the given subscription ID.
	 * If the subscription exists, it cancels the subscription, removes it
	 * from the internal subscribers map, and adjusts the corresponding
	 * version subscriber count. Returns whether the operation was successful.
	 *
	 * @param subscriptionId the unique identifier of the subscription to be unsubscribed
	 * @return {@code true} if the subscription was successfully found and unsubscribed;
	 * {@code false} if no subscription exists for the given ID
	 */
	public boolean unsubscribe(@Nonnull UUID subscriptionId) {
		final DefaultChangeCaptureSubscription<ChangeCatalogCapture> subscription = this.subscribers.get(subscriptionId);
		if (subscription != null) {
			if (!subscription.isFinished()) {
				subscription.cancel();
			}
			this.subscribers.remove(subscriptionId);
			// decrement the subscriber count for the version
			this.versionSubscribersCount.compute(
				subscription.getTrackedVersion(),
				(version, count) -> count == null || count == 1 ? null : count - 1
			);
			checkSubscribersLeft();
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Closes this publisher and cancels all active subscriptions.
	 * After closing, no new subscriptions will be accepted and any attempt to subscribe
	 * will result in a {@link InstanceTerminatedException}.
	 */
	@Override
	public void close() {
		this.lock.lock();
		try {
			// Atomically set the closed flag to true if it was false
			if (this.closed.compareAndSet(false, true)) {
				this.versionSubscribersCount.clear();
				this.lastCaptures.clearAll();
				// cancel all subscriptions
				for (DefaultChangeCaptureSubscription<ChangeCatalogCapture> subscription : this.subscribers.values()) {
					subscription.cancel();
				}
				// notify that the publisher is closed
				this.onClose.accept(this.criteria);
			}
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * Indicates whether the publisher is currently closed.
	 *
	 * @return {@code true} if the publisher has been closed; {@code false} otherwise
	 */
	public boolean isClosed() {
		return this.closed.get();
	}

	/**
	 * Retrieves the total number of active subscribers currently registered in the publisher.
	 *
	 * @return the count of active subscribers
	 */
	public int getSubscribersCount() {
		return this.subscribers.size();
	}

	/**
	 * Calculates and returns the count of lagging subscribers whose tracked catalog versions
	 * are less than the effective start catalog version.
	 *
	 * The method determines the count of such lagging subscribers by summing up
	 * their counts from the version subscriber mapping where the catalog version
	 * is smaller than the effective start catalog version.
	 *
	 * @return the total count of lagging subscribers
	 */
	public int getLaggingSubscribersCount() {
		final long startCatalogVersion = this.lastCaptures.getEffectiveStartCatalogVersion();
		// this method is not precise because it does not take index into the account, but is good enough and fast
		return this.versionSubscribersCount.entrySet()
			.stream()
			.takeWhile(it -> it.getKey() < startCatalogVersion)
			.mapToInt(Entry::getValue)
			.sum();
	}

	/**
	 * Returns the current catalog reference.
	 *
	 * @return the current catalog
	 */
	@Nonnull
	Catalog getCatalog() {
		return this.currentCatalog.get();
	}

	/**
	 * Subscribes to receive change catalog captures starting from the specified WAL pointer.
	 * This method creates a new subscription with a unique ID and registers it in the subscribers map.
	 *
	 * @param subscriber    the subscriber that will receive the change catalog captures
	 * @param specification the WAL pointer and content specification indicating where to start capturing changes
	 * @throws InstanceTerminatedException if the publisher is closed
	 */
	@Nonnull
	DefaultChangeCaptureSubscription<ChangeCatalogCapture> subscribe(@Nonnull Subscriber<? super ChangeCatalogCapture> subscriber, @Nonnull WalPointerWithContent specification) {
		assertActive();
		UUID subscriberId;
		DefaultChangeCaptureSubscription<ChangeCatalogCapture> subscription;
		final AtomicBoolean created = new AtomicBoolean(false);
		// Keep trying until we successfully create a subscription with a unique ID
		do {
			subscriberId = UUIDUtil.randomUUID();
			subscription = this.subscribers.computeIfAbsent(
				subscriberId,
				uuid -> {
					created.set(true);
					// optimization - we track number of subscribers for each version
					// to know when we can safely discard older versions
					this.versionSubscribersCount.compute(
						specification.version(),
						(version, count) -> count == null ? 1 : count + 1
					);
					// this is a costly operation since it allocates a buffer
					return new DefaultChangeCaptureSubscription<>(
						uuid,
						this.subscriberBufferSize,
						specification,
						subscriber,
						this.cdcExecutor,
						this::fillBuffer,
						this.onNextConsumer,
						this::unsubscribe
					);
				}
			);
		} while (!created.get());

		return subscription;
	}

	/**
	 * Reads change catalog captures from the Write-Ahead Log (WAL) starting from the specified WAL pointer.
	 * This method is called when the requested changes are no longer available in the ring buffer.
	 *
	 * @param walPointer            the WAL pointer indicating where to start reading from
	 * @param changeCatalogCaptures the queue to fill with change catalog captures
	 * @return the last change catalog capture that was added to the queue, or empty if none were added
	 * @throws InstanceTerminatedException if the publisher is closed
	 */
	@Nonnull
	Optional<ChangeCatalogCapture> readWal(
		@Nonnull WalPointer walPointer,
		@Nonnull Queue<ChangeCatalogCapture> changeCatalogCaptures
	) {
		assertActive();
		final Catalog catalog = getCatalog();
		final long lastPublishedCatalogVersion = catalog.getVersion();
		// we must not use the shared predicate, because this method is called from subscriber thread
		// and the shared predicate is not thread-safe
		final MutationPredicate localPredicate = this.criteria.createPredicate(
			walPointer.version(), walPointer.index()
		);
		try (
			// Get a stream of mutations starting from the specified WAL pointer
			final Stream<CatalogBoundMutation> committedMutationStream = getCatalog().getCommittedMutationStream(walPointer.version())
		) {
			// Track the last capture that was successfully added to the queue
			final AtomicReference<Optional<ChangeCatalogCapture>> lastCapture = new AtomicReference<>(Optional.empty());

			// Process the mutation stream and count the number of events sent
			final long sentEvents = committedMutationStream
				// Stop processing when we reach a mutation that is not yet visible in the live view
				.takeWhile(
					mutation -> !(mutation instanceof TransactionMutation txMutation) ||
						txMutation.getVersion() <= lastPublishedCatalogVersion
				)
				// Stop processing when the queue is full or when we encounter an error
				.takeWhile(
					// Convert each mutation to change catalog captures
					// We read mutation always with the body, the body is stripped at subscription if not needed
					mutation -> mutation.toChangeCatalogCapture(localPredicate, ChangeCaptureContent.BODY)
						// Skip captures that are before the requested WAL pointer
						.filter(cdc -> cdc.version() > walPointer.version() || (cdc.version() == walPointer.version() && cdc.index() >= walPointer.index()))
						.map(ccc -> {
							// Try to add the capture to the queue
							final boolean submitted = changeCatalogCaptures.offer(ccc);
							if (submitted) {
								// If successful, update the last capture reference
								lastCapture.set(Optional.of(ccc));
							}
							return submitted;
						})
						// Continue only if all captures were successfully added to the queue
						.reduce(Boolean::logicalAnd)
						.orElse(true)
				)
				.count();

			// Log debug information about the read operation
			final Optional<ChangeCatalogCapture> changeCatalogCapture = lastCapture.get();
			if (log.isDebugEnabled() && changeCatalogCapture.isPresent()) {
				log.debug(
					"Read {} CDC events since the catalog version {}/{}, until {}/{}.",
					sentEvents,
					walPointer.version(),
					walPointer.index(),
					changeCatalogCapture.get().version(),
					changeCatalogCapture.get().index()
				);
			} else if (log.isDebugEnabled()) {
				log.debug(
					"Read no new CDC events since the catalog version {}/{}, until end of visible catalog version {}.",
					walPointer.version(),
					walPointer.index(),
					lastPublishedCatalogVersion
				);
			}
			return lastCapture.get();
		}
	}

	/**
	 * Asserts that the publisher is active (not closed).
	 *
	 * @throws InstanceTerminatedException if the publisher is closed
	 */
	void assertActive() {
		Assert.isTrue(
			!this.closed.get(),
			() -> new InstanceTerminatedException("CDC shared publisher")
		);
	}

	/**
	 * Clears unused data from the ring buffer and updates subscription statistics.
	 *
	 * This method ensures memory efficiency by removing old data from the ring buffer
	 * or cleaning up outdated entries in the subscription version counts. The operation
	 * determines whether there is unnecessary data in the ring buffer by comparing the
	 * lowest available catalog version recorded in the ring buffer with the lowest catalog
	 * version still actively used by subscribers. If data in the ring buffer is no longer
	 * needed by any subscriber, it is cleared. If all necessary data in the ring buffer is
	 * still being actively used, subscription statistics for catalog versions that are no
	 * longer required are removed.
	 *
	 * Thread safety is ensured through the encapsulating context of this method's usage, so
	 * it is assumed that this method runs in a controlled synchronized context or with
	 * appropriate locks.
	 */
	private void clearUnusedDataInRingBuffer() {
		// clear unused data from the ring buffer / statistics
		final long lowestAvailableCatalogVersion = this.lastCaptures.getEffectiveStartCatalogVersion();
		if (!this.versionSubscribersCount.isEmpty()) {
			Long lowestUsedCatalogVersion = this.versionSubscribersCount.firstKey();
			// if the lowest available catalog version is lower than the lowest used catalog version
			if (lowestUsedCatalogVersion != null && lowestAvailableCatalogVersion < lowestUsedCatalogVersion) {
				// it means that we keep unnecessary data in the ring buffer and we may strip it
				this.lastCaptures.clearAllUntil(lowestAvailableCatalogVersion);
			} else {
				// otherwise we may clear the statistics
				while (lowestUsedCatalogVersion != null && lowestUsedCatalogVersion < lowestAvailableCatalogVersion) {
					this.versionSubscribersCount.remove(lowestUsedCatalogVersion);
					lowestUsedCatalogVersion = this.versionSubscribersCount.firstKey();
				}
			}
		}
	}

	/**
	 * Fills the subscriber's buffer with change catalog captures starting from the specified WAL pointer.
	 * This method is called by the subscription when it needs more data.
	 *
	 * It first tries to get data from the in-memory ring buffer for efficiency, and falls back to
	 * reading from the WAL if the requested data is no longer in the buffer.
	 *
	 * @param walPointer            the WAL pointer indicating where to start filling from
	 * @param changeCatalogCaptures the queue to fill with change catalog captures
	 */
	private void fillBuffer(
		@Nonnull WalPointer walPointer,
		@Nonnull DefaultChangeCaptureSubscription<ChangeCatalogCapture> subscription,
		@Nonnull Queue<ChangeCatalogCapture> changeCatalogCaptures
	) {
		Optional<ChangeCatalogCapture> lastCapture;

		// Check if the requested version is older than what we have in the ring buffer
		if (walPointer.version() < this.lastCaptures.getEffectiveStartCatalogVersion()) {
			// If so, we need to read the WAL from the disk and process manually
			lastCapture = readWal(walPointer, changeCatalogCaptures);
		} else {
			try {
				// Try to copy data from the ring buffer in synchronized block for efficiency
				lastCapture = this.lastCaptures.copyTo(walPointer, changeCatalogCaptures);
			} catch (OutsideScopeException e) {
				// We detected that we're outside the ring buffer in the locked scope
				// This can happen if the buffer was updated between our check and the actual copy
				lastCapture = readWal(walPointer, changeCatalogCaptures);
			}
		}

		// Update the subscriber count for the new version that the subscriber is now at
		lastCapture.ifPresentOrElse(
			capture -> subscription.setTrackedVersion(
				capture.version(),
				this::moveTrackedVersionsInCache
			),
			() -> {
				// Decrement the subscriber count for the previous version
				subscription.setTrackedVersion(
					walPointer.version() + 1,
					this::moveTrackedVersionsInCache
				);
			}
		);

		// clear the ring buffer if the first entry has no subscribers
		final Entry<Long, Integer> firstEntry = this.versionSubscribersCount.firstEntry();
		if (firstEntry != null && firstEntry.getValue() == 0) {
			// If the first entry has no subscribers, we can clear it
			this.versionSubscribersCount.remove(firstEntry.getKey());
			// and clear the ring buffer as well
			if (this.versionSubscribersCount.isEmpty()) {
				this.lastCaptures.clearAll();
			} else {
				this.lastCaptures.clearAllUntil(this.versionSubscribersCount.firstKey());
			}
		}
	}

	/**
	 * Updates the subscription version counts by decrementing the count for the previous version
	 * and incrementing the count for the next version. If the subscriber count for the previous
	 * version reaches zero, it is removed from the version subscription count map.
	 *
	 * @param previousVersion the catalog version from which a subscriber is moving; must be a positive long value
	 * @param nextVersion     the catalog version to which a subscriber is moving; must be a positive long value
	 */
	private void moveTrackedVersionsInCache(long previousVersion, long nextVersion) {
		// Decrement the subscriber count for the previous version
		// If this was the last subscriber for this version, remove it from the map
		this.versionSubscribersCount.compute(
			previousVersion,
			(version, count) -> count == null ? null : count - 1
		);
		// Increment the subscriber count for the new version
		this.versionSubscribersCount.compute(
			nextVersion,
			(version, count) -> count == null ? 1 : count + 1
		);
	}

}
