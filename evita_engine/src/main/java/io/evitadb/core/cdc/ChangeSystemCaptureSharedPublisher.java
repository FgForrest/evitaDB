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
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.api.requestResponse.cdc.HostSystemEvent;
import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.api.requestResponse.mutation.MutationPredicate;
import io.evitadb.api.requestResponse.mutation.MutationPredicateContext;
import io.evitadb.api.requestResponse.mutation.StreamDirection;
import io.evitadb.api.requestResponse.mutation.infrastructure.TransactionMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.buffer.RingBuffer.OutsideScopeException;
import io.evitadb.core.cdc.predicate.MutationPredicateFactory;
import io.evitadb.core.cdc.predicate.MutationPredicateFactory.TruePredicate;
import io.evitadb.core.cdc.predicate.VersionAndIndexPredicate;
import io.evitadb.core.metric.event.cdc.ChangeSystemCaptureStatisticsEvent;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.UUIDUtil;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.AbstractQueue;
import java.util.Comparator;
import java.util.Iterator;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Predicate;
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
public class ChangeSystemCaptureSharedPublisher implements Flow.Publisher<ChangeSystemCapture>, AutoCloseable {
	/**
	 * The evita instance that this publisher is associated with. It provides access to the engine mutations.
	 */
	private final Evita evita;

	/**
	 * Observability statistics event.
	 */
	private final ChangeSystemCaptureStatisticsEvent statisticsEvent = new ChangeSystemCaptureStatisticsEvent();

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
	 * Current engine version this publisher is tracking.
	 */
	private final AtomicLong version;

	/**
	 * Consumer that updates statistics of captures sent to the subscriber.
	 */
	@Nonnull private final Consumer<ChangeSystemCapture> onNextConsumer;

	/**
	 * Map of active subscriptions, keyed by their unique identifier. This allows
	 * efficient lookup and management of subscriptions.
	 */
	private final Map<UUID, DefaultChangeCaptureSubscription<ChangeSystemCapture>> subscribers = CollectionUtils.createConcurrentHashMap(64);

	/**
	 * Ring buffer that stores recent change catalog captures in memory for efficient access.
	 * When the buffer is full, older entries are evicted.
	 */
	private final ChangeCaptureRingBuffer<ChangeSystemCapture> lastCaptures;

	/**
	 * The maximum number of change catalog captures that can be buffered for each subscriber.
	 * This helps prevent memory issues when subscribers process data slowly.
	 */
	private final int subscriberBufferSize;

	/**
	 * Predicate applied to engine mutations as they are admitted into the ring buffer. The shared
	 * publisher is — as the name implies — shared across all subscribers, so this predicate must
	 * remain "match anything any subscriber might want" (i.e. permit-all). Per-subscriber filtering
	 * (engine vs host area gating, version/index gating) happens downstream when the
	 * ring-buffer contents are fanned out to each subscriber.
	 *
	 * Not thread-safe — accessed only from the engine thread that owns `processMutation`.
	 */
	private final MutationPredicate sharedPredicate;

	/**
	 * Map tracking the number of subscribers for each catalog version. This allows
	 * efficient cleanup of data that is no longer needed by any subscriber - i.e. lowering memory usage.
	 */
	private final ConcurrentSkipListMap<Long, Integer> versionSubscribersCount = new ConcurrentSkipListMap<>();

	/**
	 * Per-subscription host-event filter. Populated at subscribe time from
	 * {@link MutationPredicateFactory#createHostEventPredicate(ChangeSystemCaptureRequest)} and
	 * consulted by {@link #processHostEvent(HostSystemEvent)} to decide which subscribers should
	 * receive a given {@link HostSystemEvent}. Subscribers that did not opt into the
	 * `HOST` area receive a constant `false` predicate and never see host events.
	 *
	 * Kept as a separate map — instead of adding system-stream-specific fields to the generic
	 * {@link DefaultChangeCaptureSubscription} — so the subscription class stays usable for the
	 * catalog stream too.
	 */
	private final Map<UUID, Predicate<HostSystemEvent>> hostEventFilters =
		CollectionUtils.createConcurrentHashMap(64);

	/**
	 * Per-subscription engine-mutation filter. Captures pulled from the ring buffer (which itself
	 * permits all engine mutations, see {@link #sharedPredicate}) are dropped on the way to a
	 * specific subscriber when this filter rejects them — for example, a HOST-only
	 * subscriber should not see engine mutations.
	 *
	 * Implemented as a separate map for the same reason as {@link #hostEventFilters}: keep the
	 * generic {@link DefaultChangeCaptureSubscription} agnostic to system-stream semantics.
	 */
	private final Map<UUID, Predicate<ChangeSystemCapture>> mutationFilters =
		CollectionUtils.createConcurrentHashMap(64);

	/**
	 * Constructs a new shared publisher for change catalog captures.
	 *
	 * @param evita 			  the evita instance that this publisher is associated with
	 * @param cdcExecutor          the executor service for handling CDC operations asynchronously
	 * @param bufferSize           the size of the ring buffer for storing recent changes
	 * @param subscriberBufferSize the size of the buffer for each subscriber
	 */
	public ChangeSystemCaptureSharedPublisher(
		@Nonnull Evita evita,
		@Nonnull ExecutorService cdcExecutor,
		int bufferSize,
		int subscriberBufferSize,
		@Nonnull Consumer<ChangeSystemCapture> onNextConsumer
	) {
		this.evita = evita;
		this.cdcExecutor = cdcExecutor;
		final long engineVersion = evita.getEngineState().version();
		this.version = new AtomicLong(engineVersion);
		this.onNextConsumer = onNextConsumer;
		// Initialize the ring buffer with the current catalog version
		this.lastCaptures = new ChangeCaptureRingBuffer<>(
			null,
			// we need to use current catalog version plus one, because the current catalog version mutations will not
			// be in memory, but only in the WAL, this version will enforce reading them from WAL
			engineVersion + 1L, 0,
			engineVersion, bufferSize,
			ChangeSystemCapture.class
		);
		this.subscriberBufferSize = subscriberBufferSize;
		// Create a shared predicate that will be used to filter mutations
		this.sharedPredicate = new TruePredicate(new MutationPredicateContext(StreamDirection.FORWARD));
	}

	/**
	 * Notifies the publisher that the provided catalog is now present in the live view.
	 * Updates the currently active catalog, notifies all active subscriptions about the new catalog,
	 * and manages cleanup of expired data in the ring buffer and subscription statistics.
	 * If there are no active subscribers remaining, the publisher is closed.
	 *
	 * @param version the version now present in the live view
	 */
	public void notifyVersionPresentInLiveView(long version) {
		this.version.set(version);
		// we don't actively check for non-closed condition here to speed up the process
		this.lastCaptures.setEffectiveLastCatalogVersion(version);
		// notify all subscriptions that the catalog is now present in the live view
		// each active subscription is notified to pull new data asynchronously from this instance
		for (DefaultChangeCaptureSubscription<ChangeSystemCapture> subscription : this.subscribers.values()) {
			if (!subscription.isFinished()) {
				subscription.notifySubscriber();
			}
		}
	}

	/**
	 * Retrieves the current version of the catalog tracked by the publisher.
	 *
	 * @return the current catalog version as a long value
	 */
	public long getVersion() {
		return this.version.get();
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
	public void processMutation(@Nonnull EngineMutation<?> mutation) {
		// we don't actively check for non-closed condition here to speed up the process
		// only process mutations that match our criteria
		// convert the mutation to change catalog captures (if any) and add them to the ring buffer
		mutation.toChangeSystemCapture(this.sharedPredicate, ChangeCaptureContent.BODY)
			.forEach(this.lastCaptures::offer);
	}

	/**
	 * Dispatches a host event directly to opted-in subscribers, bypassing the
	 * recent-events ring buffer entirely. Host events are transient and live-tail-only — they are
	 * emitted once, delivered to currently-attached subscribers that requested
	 * {@link io.evitadb.api.requestResponse.cdc.SystemCaptureArea#HOST}, and are NOT
	 * persisted for replay through `sinceVersion`.
	 *
	 * The event is wrapped into a {@link ChangeSystemCapture} via
	 * {@link ChangeSystemCapture#hostEventCapture} using a fresh {@link MutationPredicateContext}
	 * seeded with the current engine snapshot version. The capture's `version` therefore reflects
	 * the most recent engine mutation observed by this host at emission time — for correlation
	 * only; this method does NOT advance the engine version counter and does NOT push the capture
	 * through the per-subscriber WAL pointer / version tracking machinery.
	 *
	 * Subscribers that did not opt into the HOST area are skipped via the
	 * {@link #hostEventFilters} map populated at subscribe time.
	 *
	 * @param event the host event to dispatch; never `null`
	 */
	public void processHostEvent(@Nonnull HostSystemEvent event) {
		// no active-check here — match the speed-first contract of processMutation
		if (this.subscribers.isEmpty()) {
			// fast path — no one to dispatch to
			return;
		}

		// Snapshot the engine version with a fresh index 0 and now-timestamp; host events do not
		// advance the version counter. The capture's version is therefore the most recent engine
		// mutation observed by this host at emission time — for correlation only.
		final MutationPredicateContext context = new MutationPredicateContext(StreamDirection.FORWARD);
		final long engineVersion = this.version.get();
		context.setVersion(engineVersion, 0, OffsetDateTime.now());
		final ChangeSystemCapture capture = ChangeSystemCapture.hostEventCapture(context, event);

		for (final Map.Entry<UUID, DefaultChangeCaptureSubscription<ChangeSystemCapture>> entry : this.subscribers.entrySet()) {
			final UUID uuid = entry.getKey();
			final DefaultChangeCaptureSubscription<ChangeSystemCapture> subscription = entry.getValue();
			if (subscription.isFinished()) {
				continue;
			}
			final Predicate<HostSystemEvent> filter = this.hostEventFilters.get(uuid);
			// Subscribers without an explicit filter (defensive — should not normally happen) are
			// treated as engine-only per the divergence rule documented on SystemCaptureArea.
			if (filter == null || !filter.test(event)) {
				continue;
			}
			// Issue #1151 — submit the host-event delivery to the same `cdcExecutor` that drains
			// engine mutations via `consumeQueue`. Engine mutations are processed via a queued
			// `consumeQueue` task that the engine thread submitted earlier in this call sequence
			// (see `notifyVersionPresentInLiveView`). Submitting `deliverImmediate` on the same
			// executor preserves submission order, so a host event emitted after a mutation lands
			// strictly after that mutation in the subscriber's stream — without exposing the
			// engine thread to the per-subscription lock contention that synchronous
			// `deliverImmediate` would cause.
			try {
				this.cdcExecutor.submit(() -> {
					try {
						subscription.deliverImmediate(capture);
					} catch (Throwable t) {
						log.error("Failed to deliver host event to subscriber {}", uuid, t);
					}
				});
			} catch (Throwable submitFailure) {
				// Submission failure (e.g. executor saturated / shutting down) — best-effort fall
				// back to synchronous delivery on the engine thread so a saturated executor does
				// not silently drop the host event.
				log.warn(
					"cdcExecutor rejected host-event delivery task for subscriber {} — falling " +
						"back to synchronous deliverImmediate.",
					uuid, submitFailure
				);
				try {
					subscription.deliverImmediate(capture);
				} catch (Throwable t) {
					log.error("Failed to deliver host event to subscriber {}", uuid, t);
				}
			}
		}
	}

	/**
	 * Forgets all mutations after the specified catalog version. This is used to clear
	 * the ring buffer and subscription statistics when a rollback or reprocessing is needed.
	 *
	 * @param version the catalog version after which all mutations should be forgotten
	 */
	public void forgetMutationsAfter(long version) {
		this.lastCaptures.clearAllAfter(version);
	}

	/**
	 * Subscribes to receive change catalog captures starting from the next version after the current catalog version.
	 * This is the implementation of the {@link Flow.Publisher} interface.
	 *
	 * Such anonymous subscriptions never opt into host events — they fall under the default
	 * engine-only divergence (see {@link io.evitadb.api.requestResponse.cdc.SystemCaptureArea}).
	 *
	 * @param subscriber the subscriber that will receive the change catalog captures
	 * @throws InstanceTerminatedException if the publisher is closed
	 */
	@Override
	public void subscribe(Subscriber<? super ChangeSystemCapture> subscriber) {
		this.lock.lock();
		try {
			assertActive();
			final long version = this.version.get();
			// Subscribe starting from the next version after the current catalog version.
			// Anonymous subscriptions follow the engine-only default — they never opt into host
			// events and never reject engine mutations (mirrors the legacy default-criteria flow).
			subscribe(
				subscriber,
				new WalPointerWithContent(version + 1, 0, ChangeCaptureContent.BODY),
				event -> false,
				capture -> true
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
		final DefaultChangeCaptureSubscription<ChangeSystemCapture> subscription = this.subscribers.get(subscriptionId);
		if (subscription != null) {
			subscription.cancel();
			this.subscribers.remove(subscriptionId);
			// drop the per-subscriber filters — no further dispatch should happen
			this.hostEventFilters.remove(subscriptionId);
			this.mutationFilters.remove(subscriptionId);
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
			// Atomically set closed flag to true if it was false
			if (this.closed.compareAndSet(false, true)) {
				this.versionSubscribersCount.clear();
				this.hostEventFilters.clear();
				this.mutationFilters.clear();
				this.lastCaptures.clearAll();
				// cancel all subscriptions
				for (DefaultChangeCaptureSubscription<ChangeSystemCapture> subscription : this.subscribers.values()) {
					subscription.cancel();
				}
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
	 * Emits statistics related to change capture events if the current statistics event
	 * meets the criteria to commit. It includes the number of subscribers, lagging
	 * subscribers, and the total events sent.
	 *
	 * @param sentEvents the number of events that have been sent since the last statistics emission
	 */
	public void emitChangeCaptureStatistics(long sentEvents) {
		if (this.statisticsEvent.shouldCommit()) {
			this.statisticsEvent.emitEvent(
				getSubscribersCount(),
				getLaggingSubscribersCount(),
				sentEvents
			);
		}
		this.lastCaptures.emitObservabilityEvents();
	}

	/**
	 * Subscribes to receive change catalog captures starting from the specified WAL pointer.
	 * This method creates a new subscription with a unique ID and registers it in the subscribers map.
	 *
	 * @param subscriber        the subscriber that will receive the change catalog captures
	 * @param specification     the WAL pointer and content specification indicating where to start
	 *                          capturing changes
	 * @param hostEventFilter   the per-subscriber predicate deciding whether a given
	 *                          {@link HostSystemEvent} should be delivered to this subscriber.
	 *                          Built from the request's criteria — see
	 *                          {@link MutationPredicateFactory#createHostEventPredicate}.
	 * @param mutationFilter    the per-subscriber predicate applied to engine-mutation captures
	 *                          pulled from the ring buffer; rejected captures are dropped before
	 *                          being offered to the subscriber's queue. Used to honor an
	 *                          HOST-only criteria selection (where no engine mutation
	 *                          should land at the subscriber).
	 * @throws InstanceTerminatedException if the publisher is closed
	 */
	@Nonnull
	DefaultChangeCaptureSubscription<ChangeSystemCapture> subscribe(
		@Nonnull Subscriber<? super ChangeSystemCapture> subscriber,
		@Nonnull WalPointerWithContent specification,
		@Nonnull Predicate<HostSystemEvent> hostEventFilter,
		@Nonnull Predicate<ChangeSystemCapture> mutationFilter
	) {
		assertActive();
		UUID subscriberId;
		DefaultChangeCaptureSubscription<ChangeSystemCapture> subscription;
		final AtomicBoolean created = new AtomicBoolean(false);
		// Keep trying until we successfully create a subscription with a unique ID
		do {
			subscriberId = UUIDUtil.randomUUID();
			final UUID candidateId = subscriberId;
			subscription = this.subscribers.computeIfAbsent(
				subscriberId,
				uuid -> {
					created.set(true);
					// optimization - we track the number of subscribers for each version
					// to know when we can safely discard older versions
					this.versionSubscribersCount.compute(
						specification.version(),
						(version, count) -> count == null ? 1 : count + 1
					);
					// register the per-subscriber filters so dispatch / fanout can honor them
					this.hostEventFilters.put(candidateId, hostEventFilter);
					this.mutationFilters.put(candidateId, mutationFilter);
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
	Optional<ChangeSystemCapture> readWal(
		@Nonnull WalPointer walPointer,
		@Nonnull Queue<ChangeSystemCapture> changeCatalogCaptures
	) {
		assertActive();
		final long lastPublishedCatalogVersion = this.version.get();
		// we must not use the shared predicate, because this method is called from subscriber thread
		// and the shared predicate is not thread-safe
		final MutationPredicate localPredicate = new VersionAndIndexPredicate(
			new MutationPredicateContext(StreamDirection.FORWARD),
			walPointer.version(), walPointer.index(),
			Comparator.naturalOrder(),
			Comparator.naturalOrder()
		);
		try (
			// Get a stream of mutations starting from the specified WAL pointer
			final Stream<EngineMutation<?>> committedMutationStream = this.evita.getCommittedMutationStream(walPointer.version())
		) {
			// Track the last capture that was successfully added to the queue
			final AtomicReference<Optional<ChangeSystemCapture>> lastCapture = new AtomicReference<>(Optional.empty());

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
					mutation -> mutation.toChangeSystemCapture(localPredicate, ChangeCaptureContent.BODY)
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
			final Optional<ChangeSystemCapture> changeCatalogCapture = lastCapture.get();
			if (log.isDebugEnabled() && changeCatalogCapture.isPresent()) {
				log.debug(
					"Read {} CDC events since the engine version {}/{}, until {}/{}.",
					sentEvents,
					walPointer.version(),
					walPointer.index(),
					changeCatalogCapture.get().version(),
					changeCatalogCapture.get().index()
				);
			} else if (log.isDebugEnabled()) {
				log.debug(
					"Read no new CDC events since the catalog engine {}/{}, until end of visible engine version {}.",
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
	 * The subscriber's per-request engine-mutation predicate (registered in
	 * {@link #mutationFilters}) is applied as captures are transferred — captures rejected by the
	 * predicate (e.g. a HOST-only subscriber receiving an engine mutation) are silently
	 * dropped, but the underlying WAL pointer still advances past them so the subscriber's
	 * `lastVersion` / `lastIndex` tracking does not stall on filtered traffic.
	 *
	 * @param walPointer            the WAL pointer indicating where to start filling from
	 * @param changeCatalogCaptures the queue to fill with change catalog captures
	 */
	private void fillBuffer(
		@Nonnull WalPointer walPointer,
		@Nonnull DefaultChangeCaptureSubscription<ChangeSystemCapture> subscription,
		@Nonnull Queue<ChangeSystemCapture> changeCatalogCaptures
	) {
		Optional<ChangeSystemCapture> lastCapture;

		// Wrap the subscriber queue with the per-subscriber mutation predicate so rejected
		// captures (e.g. engine mutations for a HOST-only subscriber) are silently
		// dropped without breaking copyTo's "stop when offer returns false" semantics.
		final Predicate<ChangeSystemCapture> mutationFilter = this.mutationFilters.getOrDefault(
			subscription.getSubscriptionId(),
			capture -> true
		);
		final Queue<ChangeSystemCapture> filteringQueue = new FilteringQueueAdapter(
			changeCatalogCaptures, mutationFilter
		);

		// Check if the requested version is older than what we have in the ring buffer
		if (walPointer.version() < this.lastCaptures.getEffectiveStartCatalogVersion()) {
			// If so, we need to read the WAL from the disk and process manually
			lastCapture = readWal(walPointer, filteringQueue);
		} else {
			try {
				// Try to copy data from the ring buffer in synchronized block for efficiency
				lastCapture = this.lastCaptures.copyTo(walPointer, filteringQueue);
			} catch (OutsideScopeException e) {
				// We detected that we're outside the ring buffer in the locked scope
				// This can happen if the buffer was updated between our check and the actual copy
				lastCapture = readWal(walPointer, filteringQueue);
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

	/**
	 * Queue adapter that applies a {@link Predicate} on every {@link Queue#offer(Object)} call:
	 * accepted items are forwarded to the wrapped queue verbatim; rejected items are silently
	 * dropped while the adapter still reports `true` to the caller so the upstream copy loop
	 * keeps advancing past filtered entries instead of stalling.
	 *
	 * Only `offer` is supported — the ring buffer's `copyTo` and the WAL reader use exclusively
	 * `offer` to feed the subscriber's queue, and every other read/write/iteration delegates to
	 * the wrapped queue so the subscription's `consumeQueue` sees a normal queue afterwards.
	 */
	private static final class FilteringQueueAdapter
		extends AbstractQueue<ChangeSystemCapture> {

		@Nonnull private final Queue<ChangeSystemCapture> delegate;
		@Nonnull private final Predicate<ChangeSystemCapture> filter;

		FilteringQueueAdapter(
			@Nonnull Queue<ChangeSystemCapture> delegate,
			@Nonnull Predicate<ChangeSystemCapture> filter
		) {
			this.delegate = delegate;
			this.filter = filter;
		}

		@Override
		public boolean offer(ChangeSystemCapture capture) {
			if (!this.filter.test(capture)) {
				// Drop rejected captures but tell the upstream loop the offer succeeded so the
				// WAL pointer keeps advancing — otherwise a HOST-only subscriber would
				// stall the moment any engine mutation appears in the buffer.
				return true;
			}
			return this.delegate.offer(capture);
		}

		@Nullable
		@Override
		public ChangeSystemCapture poll() {
			return this.delegate.poll();
		}

		@Nullable
		@Override
		public ChangeSystemCapture peek() {
			return this.delegate.peek();
		}

		@Nonnull
		@Override
		public Iterator<ChangeSystemCapture> iterator() {
			return this.delegate.iterator();
		}

		@Override
		public int size() {
			return this.delegate.size();
		}
	}

}
