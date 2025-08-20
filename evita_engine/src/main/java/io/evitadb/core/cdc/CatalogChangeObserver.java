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

package io.evitadb.core.cdc;

import io.evitadb.api.configuration.ChangeDataCaptureOptions;
import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.api.requestResponse.cdc.CaptureArea;
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.mutation.CatalogBoundMutation;
import io.evitadb.core.Catalog;
import io.evitadb.core.executor.DelayedAsyncTask;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.metric.event.cdc.ChangeCatalogCaptureStatisticsEvent;
import io.evitadb.core.metric.event.cdc.ChangeCatalogCaptureStatisticsPerAreaEvent;
import io.evitadb.core.metric.event.cdc.ChangeCatalogCaptureStatisticsPerEntityTypeEvent;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import jdk.jfr.FlightRecorder;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Implementation of the {@link ChangeObserverContract} that observes and captures changes to a catalog
 * in the evitaDB system. This class is responsible for managing the Change Data Capture (CDC) mechanism that
 * allows clients to subscribe to and receive notifications about catalog mutations.
 *
 * The observer maintains a collection of shared publishers, each associated with specific criteria for
 * filtering catalog changes. When a mutation occurs in the catalog, the observer notifies all registered
 * publishers, which in turn notify their subscribers based on the filtering criteria.
 *
 * Key responsibilities:
 * - Tracking the current catalog state
 * - Processing mutations and notifying publishers
 * - Managing the lifecycle of publishers (creation, reuse, and cleanup)
 * - Registering and unregistering observers
 *
 * The observer implements an optimization strategy where publishers with identical criteria are shared
 * among multiple subscribers to reduce resource usage and improve performance.
 *
 * TOBEDONE #879 - potential performance optimization if current one is slow
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see ChangeObserverContract
 * @see ChangeCatalogCapturePublisher
 * @see ChangeCatalogCaptureSharedPublisher
 */
public class CatalogChangeObserver implements ChangeCatalogObserverContract {
	/**
	 * Options for change data capture.
	 */
	private final ChangeDataCaptureOptions cdcOptions;
	/**
	 * Executor to be used in new publishers.
	 */
	private final ExecutorService cdcExecutor;
	/**
	 * Lambda function that provides
	 */
	private final AtomicReference<Catalog> currentCatalog;
	/**
	 * Map of all active publishers. A unique UUID identifies each publisher.
	 */
	private final Map<ChangeCatalogCriteriaBundle, ChangeCatalogCaptureSharedPublisher> uniquePublishers;
	/**
	 * Cleaning task that removes inactive publishers from the list of unique publishers once a while.
	 */
	@SuppressWarnings({"FieldCanBeLocal", "unused"})
	private final DelayedAsyncTask cleaner;
	/**
	 * Counter for the total number of events sent to subscribers. This is used for monitoring and performance analysis.
	 */
	private final AtomicLong sentEvents = new AtomicLong(0);
	/**
	 * Counter for the number of events sent to subscribers, categorized by capture area.
	 */
	private final Map<CaptureArea, AtomicLong> sentEventsByArea = CollectionUtils.createConcurrentHashMap(CaptureArea.values().length);
	/**
	 * Counter for the number of events sent to subscribers, categorized by entity type.
	 */
	private final Map<String, AtomicLong> sentEventsByEntityType = CollectionUtils.createConcurrentHashMap(32);

	/**
	 * Whether this observer is still active and can fire new events.
	 */
	private final AtomicBoolean active = new AtomicBoolean(true);

	public CatalogChangeObserver(
		@Nonnull ChangeDataCaptureOptions cdcOptions,
		@Nonnull ExecutorService cdcExecutor,
		@Nonnull Scheduler scheduler,
		@Nonnull Catalog currentCatalog
	) {
		this.cdcOptions = cdcOptions;
		this.cdcExecutor = cdcExecutor;
		this.currentCatalog = new AtomicReference<>(currentCatalog);
		this.uniquePublishers = CollectionUtils.createConcurrentHashMap(32);
		this.cleaner = new DelayedAsyncTask(
			currentCatalog.getName(),
			CatalogChangeObserver.class.getSimpleName(),
			scheduler,
			this::cleanInactivePublishers,
			1, TimeUnit.MINUTES
		);
		FlightRecorder.addPeriodicEvent(
			ChangeCatalogCaptureStatisticsEvent.class,
			this::emitChangeCaptureStatistics
		);
	}

	@Override
	public void notifyCatalogPresentInLiveView(@Nonnull Catalog catalog) {
		assertActive();
		this.currentCatalog.set(catalog);
		for (ChangeCatalogCaptureSharedPublisher sharedPublisher : this.uniquePublishers.values()) {
			sharedPublisher.notifyCatalogPresentInLiveView(catalog);
		}
	}

	@Override
	public void processMutation(@Nonnull CatalogBoundMutation mutation) {
		assertActive();
		for (ChangeCatalogCaptureSharedPublisher sharedPublisher : this.uniquePublishers.values()) {
			sharedPublisher.processMutation(mutation);
		}
	}

	@Override
	public void forgetMutationsAfter(@Nonnull Catalog catalog, long catalogVersion) {
		assertActive();
		for (ChangeCatalogCaptureSharedPublisher sharedPublisher : this.uniquePublishers.values()) {
			sharedPublisher.forgetMutationsAfter(catalogVersion);
		}
	}

	@Nonnull
	@Override
	public ChangeCapturePublisher<ChangeCatalogCapture> registerObserver(@Nonnull ChangeCatalogCaptureRequest request) {
		assertActive();
		final Catalog theCatalog = this.currentCatalog.get();
		Assert.isPremiseValid(
			theCatalog != null,
			"Catalog must be attached to the observer before registering a new publisher!"
		);

		// Provide isolated publisher wrapping the shared one to the outside world.
		// This way we can reuse predicate and caching logic for all subscribers with the same criteria.
		// Specifics related to the start version and index, and provided content are handled in the isolated publisher.
		return new ChangeCatalogCapturePublisher(
			// create or reuse the shared publisher
			criteriaBundle -> this.uniquePublishers.computeIfAbsent(
				criteriaBundle,
				cb -> new ChangeCatalogCaptureSharedPublisher(
					theCatalog, this.cdcExecutor,
					this.cdcOptions.recentEventsCacheLimit(),
					this.cdcOptions.subscriberBufferSize(),
					cb,
					this::updateStatistics,
					this.uniquePublishers::remove
				)
			),
			request
		);
	}

	/**
	 * Unregisters an observer with the specified UUID from all shared publishers.
	 * This method iterates through all shared publishers and attempts to unsubscribe
	 * the observer with the given UUID. If the observer is found and successfully
	 * unsubscribed from any publisher, the method returns true. Otherwise, it returns false.
	 *
	 * @param uuid the unique identifier of the observer to unregister
	 * @return true if the observer was found and unregistered, false otherwise
	 */
	@Override
	public boolean unregisterObserver(@Nonnull UUID uuid) {
		assertActive();
		for (ChangeCatalogCaptureSharedPublisher sharedPublisher : this.uniquePublishers.values()) {
			if (sharedPublisher.unsubscribe(uuid)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public void close() throws Exception {
		if (this.active.compareAndSet(false, true)) {
			this.uniquePublishers.values().forEach(ChangeCatalogCaptureSharedPublisher::close);
			this.uniquePublishers.clear();
			this.currentCatalog.set(null);
		}
	}

	/**
	 * Retrieves the count of unique publishers currently being tracked.
	 *
	 * @return the number of unique publishers
	 */
	public int getUniquePublishersCount() {
		return this.uniquePublishers.size();
	}

	/**
	 * Removes inactive publishers from the list of unique publishers by checking
	 * if they have been closed. A publisher is considered inactive if its `isClosed`
	 * method returns true. This method ensures that only active publishers remain in the
	 * collection for further processing.
	 *
	 * @return the milliseconds deviation to the next scheduled run (always zero)
	 */
	long cleanInactivePublishers() {
		this.uniquePublishers.values().removeIf(
			publisher -> {
				publisher.checkSubscribersLeft();
				return publisher.isClosed();
			});
		return 0L;
	}

	/**
	 * Collects and emits statistics related to change data capture (CDC) operations.
	 * This method creates a new instance of {@link ChangeCatalogCaptureStatisticsEvent} and populates
	 * it with the current catalog name, the count of unique publishers, the total number
	 * of subscribers, the count of lagging subscribers, and the total number of events published.
	 * After initializing the event with these metrics, it commits the event for further processing or logging.
	 *
	 * The statistical data are derived as follows:
	 * - The catalog name is retrieved from the current catalog instance.
	 * - The total number of unique publishers is calculated by determining the size of the unique publishers map.
	 * - The total subscriber count is computed by aggregating the subscriber counts of all shared publishers.
	 * - The total lagging subscriber count is computed by aggregating the lagging subscriber counts of all shared publishers.
	 * - The total number of events published is computed by summing the event counts from all shared publishers.
	 */
	void emitChangeCaptureStatistics() {
		int subscriberCount = 0;
		int laggingSubscriberCount = 0;
		for (ChangeCatalogCaptureSharedPublisher sharedPublisher : this.uniquePublishers.values()) {
			subscriberCount += sharedPublisher.getSubscribersCount();
			laggingSubscriberCount += sharedPublisher.getLaggingSubscribersCount();
		}
		final String catalogName = this.currentCatalog.get().getName();
		new ChangeCatalogCaptureStatisticsEvent(
			catalogName,
			this.uniquePublishers.size(),
			subscriberCount,
			laggingSubscriberCount,
			this.sentEvents.get()
		).commit();

		this.sentEventsByArea
			.forEach(
				(key, value) -> new ChangeCatalogCaptureStatisticsPerAreaEvent(
					catalogName, key, value.get()
				).commit()
			);

		this.sentEventsByEntityType
			.forEach(
				(key, value) -> new ChangeCatalogCaptureStatisticsPerEntityTypeEvent(
					catalogName, key, value.get()
				).commit()
			);
	}

	/**
	 * Updates the internal statistics related to the processing of change catalog captures.
	 * This includes incrementing the total number of sent events, as well as updating
	 * counters for the events by specific areas and entity types.
	 *
	 * @param capture the change catalog capture being processed; must not be null
	 */
	private void updateStatistics(@Nonnull ChangeCatalogCapture capture) {
		this.sentEvents.incrementAndGet();
		this.sentEventsByArea.computeIfAbsent(
			capture.area(),
			area -> new AtomicLong(0)
		).incrementAndGet();
		if (capture.entityType() != null) {
			this.sentEventsByEntityType.computeIfAbsent(
				capture.entityType(),
				entityType -> new AtomicLong(0)
			).incrementAndGet();
		}
	}

	/**
	 * Verifies this instance is still active.
	 */
	private void assertActive() {
		if (!this.active.get()) {
			throw new InstanceTerminatedException("system change observer");
		}
	}

}
