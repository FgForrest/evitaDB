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
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureCriteria;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.core.Catalog;
import io.evitadb.core.async.DelayedAsyncTask;
import io.evitadb.core.async.Scheduler;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class CatalogChangeObserver implements CatalogChangeObserverContract {
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
	private final DelayedAsyncTask cleaner;
	/* todo jno - METRIKY */
	/* todo jno - sem přenést predikáty, paměťová matice */

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
	}

	@Override
	public void notifyCatalogPresentInLiveView(@Nonnull Catalog catalog) {
		this.currentCatalog.set(catalog);
		for (ChangeCatalogCaptureSharedPublisher sharedPublisher : this.uniquePublishers.values()) {
			sharedPublisher.notifyCatalogPresentInLiveView(catalog);
		}
	}

	@Override
	public void processMutation(@Nonnull Mutation mutation) {
		for (ChangeCatalogCaptureSharedPublisher sharedPublisher : this.uniquePublishers.values()) {
			sharedPublisher.processMutation(mutation);
		}
	}

	@Override
	public void forgetMutationsAfter(long catalogVersion) {
		for (ChangeCatalogCaptureSharedPublisher sharedPublisher : this.uniquePublishers.values()) {
			sharedPublisher.forgetMutationsAfter(catalogVersion);
		}
	}

	@Nonnull
	@Override
	public ChangeCapturePublisher<ChangeCatalogCapture> registerObserver(@Nonnull ChangeCatalogCaptureRequest request) {
		final Catalog theCatalog = this.currentCatalog.get();
		Assert.isPremiseValid(
			theCatalog != null,
			"Catalog must be attached to the observer before registering a new publisher!"
		);
		final ChangeCatalogCaptureCriteria[] requestedCriteria = request.criteria();
		final ChangeCatalogCriteriaBundle criteriaBundle = requestedCriteria == null ?
			ChangeCatalogCriteriaBundle.CATCH_ALL : new ChangeCatalogCriteriaBundle(requestedCriteria);

		// create or reuse the shared publisher
		final ChangeCatalogCaptureSharedPublisher sharedPublisher = this.uniquePublishers.computeIfAbsent(
			criteriaBundle,
			cb -> new ChangeCatalogCaptureSharedPublisher(
				theCatalog, this.cdcExecutor,
				this.cdcOptions.recentEventsCacheLimit(),
				this.cdcOptions.subscriberBufferSize(),
				cb
			)
		);

		// Provide isolated publisher wrapping the shared one to the outside world.
		// This way we can reuse predicate and caching logic for all subscribers with the same criteria.
		// Specifics related to start version and index, and provided content are handled in the isolated publisher.
		return new ChangeCatalogCapturePublisher(sharedPublisher, request);
	}

	@Override
	public boolean unregisterObserver(@Nonnull UUID uuid) {
		for (ChangeCatalogCaptureSharedPublisher sharedPublisher : this.uniquePublishers.values()) {
			if (sharedPublisher.unsubscribe(uuid)) {
				 return true;
			}
		}
		return false;
	}

	/**
	 * Removes inactive publishers from the list of unique publishers by checking
	 * if they have been closed. A publisher is considered inactive if its `isClosed`
	 * method returns true. This method ensures that only active publishers remain in the
	 * collection for further processing.
	 *
	 * @return the milliseconds deviation to the next scheduled run (always zero)
	 */
	private long cleanInactivePublishers() {
		this.uniquePublishers.values().removeIf(ChangeCatalogCaptureSharedPublisher::isClosed);
		return 0L;
	}

}
