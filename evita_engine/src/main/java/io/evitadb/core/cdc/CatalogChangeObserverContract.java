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


import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.core.Catalog;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * This interface defines a contract for observing and capturing changes to a catalog in the evitaDB system.
 * It provides mechanisms for tracking mutations, managing catalog change data capture (CDC), and publishing
 * these changes to registered observers.
 *
 * <p>The contract supports the Change Data Capture pattern, allowing clients to subscribe to and receive
 * notifications about changes occurring in the catalog. This is particularly useful for maintaining
 * data consistency across distributed systems, implementing event-driven architectures, and enabling
 * real-time data synchronization.</p>
 *
 * Implementations of this interface are responsible for:
 *
 * <ul>
 *   <li>Tracking catalog state changes in the live view</li>
 *   <li>Processing and capturing mutations from the Write-Ahead Log (WAL)</li>
 *   <li>Managing observer registrations and subscriptions</li>
 *   <li>Publishing captured changes to interested subscribers</li>
 *   <li>Handling error scenarios and recovery mechanisms</li>
 * </ul>
 *
 * The interface provides a NOOP implementation that can be used when change data capture
 * functionality is not required or not enabled in the configuration.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see ChangeCapturePublisher
 * @see ChangeCatalogCapture
 * @see ChangeCatalogCaptureRequest
 */
public interface CatalogChangeObserverContract {

	/**
	 * No-op implementation of the {@link CatalogChangeObserverContract} that does nothing.
	 */
	CatalogChangeObserverContract NOOP = new CatalogChangeObserverContract() {

		@Override
		public void notifyCatalogPresentInLiveView(@Nonnull Catalog catalog) {
			// do nothing
		}

		@Override
		public void processMutation(@Nonnull Mutation mutation) {
			// do nothing
		}

		@Override
		public void forgetMutationsAfter(long catalogVersion) {
			// do nothing
		}

		@Nonnull
		@Override
		public ChangeCapturePublisher<ChangeCatalogCapture> registerObserver(@Nonnull ChangeCatalogCaptureRequest request) {
			throw new EvitaInvalidUsageException("Change data capture is not enabled in configuraiton of this instance.");
		}

		@Override
		public boolean unregisterObserver(@Nonnull UUID uuid) {
			return false;
		}
	};

	/**
	 * Notifies that the specified catalog is now present in the live view and updates the current catalog reference.
	 * This method reads all mutations from the WAL and publishes them to all subscribers that keep up with the live view.
	 *
	 * @param catalog the catalog that is now present in the live view
	 */
	void notifyCatalogPresentInLiveView(@Nonnull Catalog catalog);

	/**
	 * Registers a new mutation processed from WAL to be captured and published to all subscribers.
	 *
	 * @param mutation the mutation that has been processed and needs to be captured
	 */
	void processMutation(@Nonnull Mutation mutation);

	/**
	 * Clears all CDC data related to catalog versions strictly greater than the specified version. This method is called
	 * in case there is an error in the WAL processing and the mutations will have to be reprocessed. If the events are
	 * not cleared, they'd be published to the subscribers again, which is not desired.
	 *
	 * @param catalogVersion the catalog version after which all mutations should be forgotten
	 */
	void forgetMutationsAfter(long catalogVersion);

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
	ChangeCapturePublisher<ChangeCatalogCapture> registerObserver(@Nonnull ChangeCatalogCaptureRequest request);

	/**
	 * Unregisters an observer associated with the specified UUID from the list of catalog observers.
	 * If the observer is successfully removed, any associated resources are released and any publishers
	 * tied to the observer are closed. If the catalog observer list becomes empty after the removal,
	 * the mutation reader publisher is also removed.
	 *
	 * This method may not be called at all - the standard way to stop and unregister an observer in reactive
	 * Java Flow is to cancel the subscription from the subscriber side.
	 *
	 * @param uuid the unique identifier of the observer to be unregistered
	 * @return {@code true} if the observer was successfully unregistered, {@code false} if no observer
	 * with the specified UUID was found
	 */
	boolean unregisterObserver(@Nonnull UUID uuid);
}
