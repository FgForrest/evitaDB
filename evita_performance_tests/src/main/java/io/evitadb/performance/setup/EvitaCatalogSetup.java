/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.performance.setup;

import io.evitadb.api.configuration.CacheOptions;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.core.Evita;
import io.evitadb.test.EvitaTestSupport;

import javax.annotation.Nonnull;

/**
 * Base implementation for InMemory tests that doesn't allow catalog recurring usage.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface EvitaCatalogSetup extends CatalogSetup, EvitaTestSupport {

	@Override
	default Evita createEmptyEvitaInstance(@Nonnull String catalogName) {
		cleanTestSubDirectoryWithRethrow(catalogName);
		// create new empty database
		final Evita evita = new Evita(
			EvitaConfiguration.builder()
				.server(
					ServerOptions.builder()
						.queryTimeoutInMilliseconds(60_000)
						.transactionTimeoutInMilliseconds(60_000)
						.closeSessionsAfterSecondsOfInactivity(60)
						.build()
				)
				.storage(
					StorageOptions.builder()
						.storageDirectory(getTestDirectory())
						.lockTimeoutSeconds(50)
						.waitOnCloseSeconds(50)
						.outputBufferSize(4_194_304)
						.maxOpenedReadHandles(12)
						.computeCRC32(true)
						.minimalActiveRecordShare(0.01)
						.build()
				)
				.cache(
					CacheOptions.builder()
						.enabled(true)
						.reevaluateEachSeconds(60)
						.anteroomRecordCount(100_000)
						.minimalComplexityThreshold(10_000)
						.minimalUsageThreshold(2)
						.cacheSizeInBytes(1_000_000_000L)
						.build()
				)
				.build()
		);
		evita.deleteCatalogIfExists(catalogName);
		evita.defineCatalog(catalogName);
		return evita;
	}

}
