/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.core.catalog;

import io.evitadb.api.statistics.StorageSizeStatistics;
import io.evitadb.spi.store.catalog.persistence.CatalogStorageFootprint;

import javax.annotation.Nonnull;

/**
 * Translates the storage layer's {@link CatalogStorageFootprint} into the API-facing
 * {@link StorageSizeStatistics}. The footprint is the wider record - it also carries the file counts and the
 * active-reader floor that {@link io.evitadb.api.statistics.HistoryStatistics} reports - and this is the seam that
 * keeps the two apart: the persistence SPI knows file names and offset-index bookkeeping and nothing about statistics
 * components, so the mapping lives here rather than on either record.
 *
 * It exists as a method rather than as two inline constructor calls because both {@link Catalog} and
 * {@link UnusableCatalog} need it, and a nine-argument positional record constructor written twice is exactly the
 * shape that silently transposes two fields when one of them is added or reordered.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class StorageSizeProjection {

	private StorageSizeProjection() {
		// this class is a namespace for the projection below, never instantiated
	}

	/**
	 * Projects a measured storage footprint onto the statistics component that reports it.
	 *
	 * @param footprint the decomposition as the persistence layer measured it
	 * @return the same decomposition in the shape the statistics API publishes
	 */
	@Nonnull
	static StorageSizeStatistics toStorageSizeStatistics(@Nonnull CatalogStorageFootprint footprint) {
		return new StorageSizeStatistics(
			footprint.totalBytes(),
			footprint.liveBytes(),
			footprint.wasteBytes(),
			footprint.catalogDataStoreLiveBytes(),
			footprint.catalogDataStoreWasteBytes(),
			footprint.walBytes(),
			footprint.awaitingDeletionBytes(),
			footprint.blockedByActiveReaderBytes(),
			footprint.purgeableBytes(),
			footprint.bootstrapBytes(),
			footprint.unaccountedBytes()
		);
	}
}
