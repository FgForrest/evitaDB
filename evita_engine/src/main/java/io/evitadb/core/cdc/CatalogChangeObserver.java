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

import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.core.Catalog;
import io.evitadb.core.async.ObservableExecutorService;
import io.evitadb.core.cdc.predicate.MutationPredicateFactory;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.UUIDUtil;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * This observer is responsible for maintaining a list of active publishers that are interested in receiving updates
 * about changes in the catalog (capturing data changes). It allows for registering and unregistering observers, and
 * it manages the lifecycle of the publishers.
 *
 * Observer is expected to be a singleton passed from one catalog instance (version) to the next one. All the publishers
 * whose subscribers can keep up with the changes in the current catalog versions are registered
 * to the {@link #getUpToDateMutationReaderPublisher(long)}, lagging publishers have their own instances of
 * {@link MutationReaderPublisher} and consume changes in their own pace.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class CatalogChangeObserver {
	/**
	 * Executor to be used in new publishers.
	 */
	private final ObservableExecutorService executor;
	/**
	 * Lambda function that provides
	 */
	private final AtomicReference<Catalog> currentCatalog;
	/**
	 * Map of all active publishers. A unique UUID identifies each publisher.
	 */
	private final Map<UUID, CapturePublisher> catalogObservers;
	/**
	 * Subscribers following the up-to-date catalog version share a single publisher.
	 */
	private final AtomicReference<MutationReaderPublisher<CapturePublisher>> mutationReader;
	/**
	 * Lock to synchronize creating & removing current state mutation reader.
	 */
	private final ReentrantLock lock = new ReentrantLock();

	public CatalogChangeObserver(@Nonnull ObservableExecutorService executor) {
		this.executor = executor;
		this.currentCatalog = new AtomicReference<>();
		this.catalogObservers = CollectionUtils.createConcurrentHashMap(32);
		this.mutationReader = new AtomicReference<>();
	}

	/**
	 * Registers a new observer for capturing catalog changes based on the specified request.
	 * It creates a {@link ChangeCapturePublisher} that will multicast captured events to interested subscribers.
	 * The publisher's lifecycle is tied to the observer registration and will handle changes starting from the requested
	 * catalog version or the current catalog version if not specified.
	 *
	 * @param request the request specifying the configuration for what changes need to be captured
	 *                (e.g., starting catalog version, content type, and filters on mutations)
	 * @return an instance of {@link ChangeCapturePublisher} that can be used to subscribe and receive captured events
	 */
	@Nonnull
	public ChangeCapturePublisher<ChangeCatalogCapture> registerObserver(@Nonnull ChangeCatalogCaptureRequest request) {
		final Catalog theCatalog = this.currentCatalog.get();
		Assert.isPremiseValid(
			theCatalog != null,
			"Catalog must be attached to the observer before registering a new publisher!"
		);
		final UUID uuid = UUIDUtil.randomUUID();
		final long startCatalogVersion = request.sinceVersion() == null ? theCatalog.getVersion() : request.sinceVersion();
		final int startIndex = request.sinceIndex() == null ? 0 : request.sinceIndex();
		final CapturePublisher capturePublisher = new CapturePublisher(
			uuid,
			startCatalogVersion,
			startIndex,
			this.executor,
			MutationPredicateFactory.createChangeCatalogCapturePredicate(request),
			request.content(),
			this::registerLaggingPublisher,
			this.catalogObservers::remove
		);
		Assert.isPremiseValid(
			this.catalogObservers.putIfAbsent(uuid, capturePublisher) == null,
			"Generated UUID was not unique - unexpected in our spacetime plane."
		);
		if (startCatalogVersion == theCatalog.getVersion()) {
			registerUpToDatePublisher(capturePublisher);
		} else {
			registerLaggingPublisher(capturePublisher);
		}
		return capturePublisher;
	}

	/**
	 * Unregisters an observer associated with the specified UUID from the list of catalog observers.
	 * If the observer is successfully removed, any associated resources are released and any publishers
	 * tied to the observer are closed. If the catalog observer list becomes empty after the removal,
	 * the mutation reader publisher is also removed.
	 *
	 * @param uuid the unique identifier of the observer to be unregistered
	 * @return {@code true} if the observer was successfully unregistered, {@code false} if no observer
	 *         with the specified UUID was found
	 */
	public boolean unregisterObserver(@Nonnull UUID uuid) {
		final CapturePublisher removedPublisher = this.catalogObservers.remove(uuid);
		if (removedPublisher != null) {
			removedPublisher.close();
			if (this.catalogObservers.isEmpty()) {
				removeMutationReaderPublisher();
			}
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Notifies that the specified catalog is now present in the live view and updates the current catalog reference.
	 * If a mutation reader publisher is available, it initiates reading all mutations.
	 *
	 * @param catalog the catalog object representing the current state to be set as present in the live view
	 */
	public void notifyCatalogPresentInLiveView(@Nonnull Catalog catalog) {
		this.currentCatalog.set(catalog);
		final MutationReaderPublisher<CapturePublisher> mutationReaderPublisher = this.mutationReader.get();
		if (mutationReaderPublisher != null) {
			mutationReaderPublisher.readAll();
		}
	}

	/**
	 * Registers a publisher that is up-to-date with the current catalog version. The publisher will not react when its
	 * buffer is completely emptied and is subscribed to the mutation reader following the latest changes in the catalog.
	 *
	 * @param capturePublisher the instance of {@link CapturePublisher} to be registered and subscribed
	 *                         to the mutation reader publisher for processing updates
	 */
	private void registerUpToDatePublisher(@Nonnull CapturePublisher capturePublisher) {
		// we need to execute subscription in a locked block to avoid situation where the publisher is closed and removed
		this.lock.lock();
		try {
			final MutationReaderPublisher<CapturePublisher> mutationReaderPublisher = getUpToDateMutationReaderPublisher(
				capturePublisher.getContinueWithVersion()
			);
			mutationReaderPublisher.subscribe(capturePublisher);
		} finally {
			this.lock.unlock();
		}
	}

	private void registerLaggingPublisher(@Nonnull CapturePublisher capturePublisher) {
		final MutationReaderPublisher<CapturePublisher> newMutationReader = new MutationReaderPublisher<>(
			this.executor,
			capturePublisher.getContinueWithVersion(),
			capturePublisher.getContinueWithIndex(),
			() -> this.currentCatalog.get().getVersion(),
			catalogVersion -> this.currentCatalog.get().getCommittedMutationStream(catalogVersion),
			null,
			this::registerUpToDatePublisher
		);
		newMutationReader.subscribe(capturePublisher);
		// trigger initialization of the new publisher
		newMutationReader.readAll();
	}

	/**
	 * Retrieves or initializes a singleton instance of {@link MutationReaderPublisher} tied to the current version
	 * and thread executor. If a publisher instance already exists, it reuses the existing instance; otherwise,
	 * it creates and sets up a new one.
	 *
	 * @param sinceCatalogVersion the catalog version from which mutations will be read
	 * @return the singleton instance of the {@link MutationReaderPublisher} bound to the specified parameters
	 */
	@Nonnull
	private MutationReaderPublisher<CapturePublisher> getUpToDateMutationReaderPublisher(long sinceCatalogVersion) {
		MutationReaderPublisher<CapturePublisher> mutationReaderPublisher = this.mutationReader.get();
		if (mutationReaderPublisher == null) {
			// create a new publisher
			final MutationReaderPublisher<CapturePublisher> newMutationReaderPublisher = new MutationReaderPublisher<>(
				this.executor,
				sinceCatalogVersion + 1L,
				0,
				() -> this.currentCatalog.get().getVersion(),
				catalogVersion -> this.currentCatalog.get().getCommittedMutationStream(catalogVersion),
				this::registerLaggingPublisher,
				null
			);
			Assert.isPremiseValid(
				this.mutationReader.compareAndExchange(null, newMutationReaderPublisher) == null,
				"Publisher was already created by another thread (unexpected situation, we're in a locked block)."
			);
			mutationReaderPublisher = newMutationReaderPublisher;
		}
		return mutationReaderPublisher;
	}

	/**
	 * Releases and removes the currently active {@link MutationReaderPublisher} instance, ensuring proper cleanup
	 * and thread-safe operation.
	 *
	 * This method is used to manage the lifecycle of the {@link MutationReaderPublisher} by removing the existing publisher
	 * when it is no longer needed ensuring the resources are properly closed, avoiding potential memory leaks.
	 */
	private void removeMutationReaderPublisher() {
		if (this.mutationReader.get() != null) {
			this.lock.lock();
			try {
				final MutationReaderPublisher<CapturePublisher> mutationReaderPublisher = this.mutationReader.get();
				if (mutationReaderPublisher != null && !mutationReaderPublisher.hasSubscribers()) {
					mutationReaderPublisher.close();
					this.mutationReader.set(null);
				}
			} finally {
				this.lock.unlock();
			}
		}
	}

}
