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
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.core.Evita;
import io.evitadb.test.EvitaTestSupport;
import org.apache.commons.io.FileUtils;

import javax.annotation.Nonnull;
import java.io.File;

/**
 * Base implementation for InMemory tests that allow catalog recurring usage.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface EvitaCatalogReusableSetup extends EvitaCatalogSetup, EvitaTestSupport {

	@Override
	default Evita createEvitaInstanceFromExistingData(@Nonnull String catalogName) {
		// create new empty database
		return new Evita(
			EvitaConfiguration.builder()
				.storage(
					StorageOptions.builder()
						.storageDirectory(getTestDirectory())
						.build()
				)
				.cache(
					CacheOptions.builder()
						.enabled(true)
						.reevaluateEachSeconds(60)
						.anteroomRecordCount(10_000)
						.minimalComplexityThreshold(50_000)
						.minimalUsageThreshold(5)
						.cacheSizeInBytes(1_000_000_000L)
						.build()
				)
				.build()
		);
	}

	@Override
	default boolean isCatalogAvailable(@Nonnull String catalogName) {
		final File targetDirectory = getTestDirectory().resolve(catalogName).toFile();
		return targetDirectory.exists() && FileUtils.sizeOfDirectory(targetDirectory) > 0;
	}

	@Override
	default boolean shouldStartFromScratch() {
		return false;
	}

}
