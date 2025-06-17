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
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.executor.DelayedAsyncTask;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.metric.event.cdc.ChangeCatalogCaptureStatisticsEvent;
import io.evitadb.core.metric.event.cdc.ChangeSystemCaptureStatisticsEvent;
import jdk.jfr.FlightRecorder;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Main implementation class handling notification of all created {@link ChangeCapturePublisher}s.
 * TODO JNO - update documentation here
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@Slf4j
public class SystemChangeObserver implements ChangeObserverContract<ChangeSystemCaptureRequest, ChangeSystemCapture, EngineMutation> {
	/**
	 * The evita instance that this publisher is associated with. It provides access to the engine mutations.
	 * TODO JNO - covert to local?
	 */
	private final Evita evita;
	/**
	 * Options for change data capture.
	 */
	private final ChangeDataCaptureOptions cdcOptions;
	/**
	 * Executor to be used in new publishers.
	 */
	private final ExecutorService cdcExecutor;
	/**
	 * Single shared publisher to process engine mutations and notify all subscribers. There is always only one instance
	 * because it's at least used by internal subscribers and there are no complex request criteria that would require
	 * multiple publishers.
	 */
	private final ChangeSystemCaptureSharedPublisher sharedPublisher;
	/**
	 * Counter for the total number of events sent to subscribers. This is used for monitoring and performance analysis.
	 */
	private final AtomicLong sentEvents = new AtomicLong(0);
	/**
	 * Cleaning task that removes inactive publishers from the list of unique publishers once a while.
	 */
	@SuppressWarnings({"FieldCanBeLocal", "unused"})
	private final DelayedAsyncTask cleaner;
	/**
	 * Whether this observer is still active and can fire new events.
	 */
	private final AtomicBoolean active = new AtomicBoolean(true);

	public SystemChangeObserver(
		@Nonnull Evita evita,
		@Nonnull ChangeDataCaptureOptions cdcOptions,
		@Nonnull ExecutorService cdcExecutor,
		@Nonnull Scheduler scheduler
	) {
		this.evita = evita;
		this.cdcOptions = cdcOptions;
		this.cdcExecutor = cdcExecutor;
		this.sharedPublisher = new ChangeSystemCaptureSharedPublisher(
			this.evita,
			this.cdcExecutor,
			this.cdcOptions.recentEventsCacheLimit(),
			this.cdcOptions.subscriberBufferSize(),
			this::updateStatistics
		);
		this.cleaner = new DelayedAsyncTask(
			null,
			SystemChangeObserver.class.getSimpleName(),
			scheduler,
			this::cleanSubscribers,
			1, TimeUnit.MINUTES
		);
		FlightRecorder.addPeriodicEvent(
			ChangeCatalogCaptureStatisticsEvent.class,
			this::emitChangeCaptureStatistics
		);
	}

	@Override
	public void processMutation(@Nonnull EngineMutation mutation) {
		assertActive();
		this.sharedPublisher.processMutation(mutation);
	}

	@Nonnull
	@Override
	public ChangeCapturePublisher<ChangeSystemCapture> registerObserver(@Nonnull ChangeSystemCaptureRequest request) {
		assertActive();

		// Provide isolated publisher wrapping the shared one to the outside world.
		// This way we can reuse predicate and caching logic for all subscribers with the same criteria.
		// Specifics related to the start version and index, and provided content are handled in the isolated publisher.
		return new ChangeSystemCapturePublisher(
			this.sharedPublisher,
			request
		);
	}

	@Override
	public boolean unregisterObserver(@Nonnull UUID uuid) {
		assertActive();
		return this.sharedPublisher.unsubscribe(uuid);
	}

	/**
	 * Informs all {@link ChangeSystemCaptureSharedPublisher}s that the specified engine version is now present in the
	 * live view. This is used to notify publishers that they can now start sending mutations related to this engine
	 * version to their subscribers.
	 *
	 * @param engineVersion the engine version that is now present in the live view
	 */
	public void notifyVersionPresentInLiveView(long engineVersion) {
		assertActive();
		this.sharedPublisher.notifyVersionPresentInLiveView(engineVersion);
	}

	/**
	 * Discards all mutations that have been captured after the specified engine version from the memory of all
	 * {@link ChangeSystemCaptureSharedPublisher}s. This frees up memory. Lagging subscribers will read those
	 * mutations from the WAL until they catch up with the live view.
	 *
	 * @param engineVersion the engine version that marks version before which all mutations should be forgotten
	 */
	public void forgetMutationsAfter(long engineVersion) {
		assertActive();
		this.sharedPublisher.forgetMutationsAfter(engineVersion);
	}

	@Override
	public void close() {
		if (this.active.compareAndSet(false, true)) {
			this.sharedPublisher.close();
		}
	}

	/**
	 * Cleans up inactive or unnecessary subscribers from the shared publisher.
	 * It checks if there are any subscribers left using the shared publisher's
	 * checkSubscribersLeft method, and ensures proper cleanup when no subscribers remain.
	 *
	 * @return the milliseconds deviation to the next scheduled run (always zero)
	 */
	private long cleanSubscribers() {
		this.sharedPublisher.checkSubscribersLeft();
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
		final int subscriberCount = this.sharedPublisher.getSubscribersCount();
		final int laggingSubscriberCount = this.sharedPublisher.getLaggingSubscribersCount();
		new ChangeSystemCaptureStatisticsEvent(
			subscriberCount,
			laggingSubscriberCount,
			this.sentEvents.get()
		).commit();
	}

	/**
	 * Updates the internal statistics related to the processing of change catalog captures.
	 * This includes incrementing the total number of sent events, as well as updating
	 * counters for the events by specific areas and entity types.
	 *
	 * @param capture the change catalog capture being processed; must not be null
	 */
	private void updateStatistics(@Nonnull ChangeSystemCapture capture) {
		this.sentEvents.incrementAndGet();
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
