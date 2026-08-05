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

import io.evitadb.api.statistics.DataStoreVolatileState;
import io.evitadb.spi.store.catalog.persistence.VolatileDataFootprint;

import javax.annotation.Nonnull;

/**
 * Projects one data store's {@link VolatileDataFootprint} onto the {@link DataStoreVolatileState} that reports it.
 *
 * Public, and its own class, for the same reason {@link FragmentationProjection} is: the catalog's own data store and
 * an entity collection's are described by the same record but assembled in different packages, and a mapping written
 * out at each call site is a mapping that can drift. The catalog-wide *fold* is not here - that is
 * {@link VolatileDataFootprint#plus} in the persistence SPI, where the rule for each field lives.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class VolatileStateProjection {

	private VolatileStateProjection() {
		// this class is a namespace for the projection below, never instantiated
	}

	/**
	 * Projects one data store's volatile footprint onto the statistics record that reports it.
	 *
	 * @param footprint what the persistence layer measured for that store
	 * @return the record describing it
	 */
	@Nonnull
	public static DataStoreVolatileState toDataStoreVolatileState(@Nonnull VolatileDataFootprint footprint) {
		return new DataStoreVolatileState(
			footprint.totalSizeIncludingVolatileDataBytes(),
			footprint.nonFlushedRecordCount(),
			footprint.nonFlushedSizeBytes(),
			footprint.oldestRecordKeptTimestamp()
		);
	}

}
