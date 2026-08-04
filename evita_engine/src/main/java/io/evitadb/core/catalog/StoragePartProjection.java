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

import io.evitadb.api.statistics.StoragePartUsage;
import io.evitadb.spi.store.catalog.persistence.StoragePartFootprint;

import javax.annotation.Nonnull;

/**
 * Translates the storage layer's {@link StoragePartFootprint} breakdown into the API-facing {@link StoragePartUsage}
 * one. Sibling of {@link StorageSizeProjection}: the persistence SPI knows record types and offset-index bookkeeping
 * and nothing about statistics components, so the mapping lives on this side of the seam.
 *
 * It is public rather than package-private because the catalog level and the collection level both project the same
 * breakdown, and {@link io.evitadb.core.collection.EntityCollection} sits in a different package.
 *
 * **The order is the SPI's, and is preserved here.** The breakdown arrives sorted largest-consumer-first
 * ({@link StoragePartFootprint#LARGEST_FIRST}); the records that carry it compare their arrays positionally, so
 * reordering it here would make two identical compositions unequal.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class StoragePartProjection {

	private StoragePartProjection() {
		// this class is a namespace for the projection below, never instantiated
	}

	/**
	 * Projects a measured storage-part breakdown onto the statistics component that reports it.
	 *
	 * @param footprints the breakdown as the persistence layer measured it, in its own order
	 * @return the same breakdown in the shape the statistics API publishes, in the same order
	 */
	@Nonnull
	public static StoragePartUsage[] toStoragePartUsage(@Nonnull StoragePartFootprint[] footprints) {
		final StoragePartUsage[] usage = new StoragePartUsage[footprints.length];
		for (int i = 0; i < footprints.length; i++) {
			final StoragePartFootprint footprint = footprints[i];
			usage[i] = new StoragePartUsage(
				footprint.storagePartType(),
				footprint.count(),
				footprint.totalBytes()
			);
		}
		return usage;
	}

}
