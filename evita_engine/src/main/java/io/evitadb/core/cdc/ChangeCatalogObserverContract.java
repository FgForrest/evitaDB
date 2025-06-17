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
import io.evitadb.api.requestResponse.mutation.CatalogBoundMutation;
import io.evitadb.core.Catalog;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * This interface defines a specialized contract for observing and capturing changes specific to catalogs in the evitaDB system.
 * It extends the base {@link ChangeObserverContract} with functionality tailored for handling catalog-bound mutations.
 *
 * The ChangeCatalogObserverContract is responsible for:
 *
 * - Tracking and capturing mutations that are bound to specific catalogs
 * - Managing the lifecycle of catalog-specific change data
 * - Providing mechanisms to handle error recovery scenarios in the Write-Ahead Log (WAL) processing
 * - Discarding mutations that relate to older catalog versions and freeing up resources
 *
 * This contract plays a crucial role in maintaining data consistency across distributed systems by ensuring
 * that catalog changes are properly tracked, captured, and can be reliably processed even in failure scenarios.
 *
 * Implementations of this interface should ensure thread safety and proper handling of concurrent access
 * to catalog mutation data.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see ChangeObserverContract
 * @see CatalogBoundMutation
 */
public interface ChangeCatalogObserverContract extends ChangeObserverContract<ChangeCatalogCaptureRequest, ChangeCatalogCapture, CatalogBoundMutation> {

	/**
	 * No-op implementation of the {@link ChangeObserverContract} that does nothing.
	 */
	ChangeCatalogObserverContract NO_OP = new ChangeCatalogObserverContract() {

		@Override
		public void notifyCatalogPresentInLiveView(@Nonnull Catalog catalog) {
			// do nothing
		}

		@Override
		public void processMutation(@Nonnull CatalogBoundMutation mutation) {
			// do nothing
		}

		@Override
		public void forgetMutationsAfter(@Nonnull Catalog catalog, long catalogVersion) {
			// do nothing
		}

		@Nonnull
		@Override
		public ChangeCapturePublisher<ChangeCatalogCapture> registerObserver(@Nonnull ChangeCatalogCaptureRequest request) {
			throw new EvitaInvalidUsageException("Change data capture is not enabled in the configuration of this instance.");
		}

		@Override
		public boolean unregisterObserver(@Nonnull UUID uuid) {
			return false;
		}

		@Override
		public void close() {
			// do nothing
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
	 * Clears all CDC data related to catalog versions strictly greater than the specified version. This method is called
	 * in case there is an error in the WAL processing and the mutations will have to be reprocessed. If the events are
	 * not cleared, they'd be published to the subscribers again, which is not desired.
	 *
	 * @param catalog the catalog for which mutations should be forgotten
	 * @param catalogVersion the catalog version after which all mutations should be forgotten
	 */
	void forgetMutationsAfter(@Nonnull Catalog catalog, long catalogVersion);

}
