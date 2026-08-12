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

package io.evitadb.performance.setup;

import io.evitadb.api.configuration.CacheOptions;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.CatalogState;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.core.Evita;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.Assert;
import org.apache.commons.io.FileUtils;

import javax.annotation.Nonnull;
import java.io.File;
import java.time.Duration;

/**
 * Base implementation for InMemory tests that allow catalog recurring usage.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface EvitaCatalogReusableSetup extends EvitaCatalogSetup, EvitaTestSupport {

	@Override
	default Evita createEvitaInstanceFromExistingData(@Nonnull String catalogName) {
		// create new empty database
		final Evita evita = new Evita(
			EvitaConfiguration.builder()
				.storage(
					StorageOptions.builder()
						// must resolve through the same helper the empty-instance path uses, otherwise this cannot
						// find the catalog that path built - see EvitaCatalogSetup#benchmarkStorageDirectory
						.storageDirectory(benchmarkStorageDirectory(catalogName))
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
		awaitCatalogAlive(evita, catalogName);
		return evita;
	}

	/**
	 * Blocks until `catalogName` leaves its transitional state and becomes {@link CatalogState#ALIVE}.
	 *
	 * Boot-time WAL catch-up runs on a background thread, so the {@link Evita} constructor returns while the catalog is
	 * still {@link CatalogState#BEING_ACTIVATED}. Any benchmark state that opens a session immediately - which is what
	 * every reused-catalog state does in its trial setup - then fails with `CatalogTransitioningException`. JMH reports
	 * such a setup failure as a *finished* run with zero measured operations, so without this wait the whole suite
	 * "succeeds" while measuring nothing at all.
	 *
	 * @param evita       the freshly booted instance
	 * @param catalogName the catalog awaited
	 */
	private static void awaitCatalogAlive(@Nonnull Evita evita, @Nonnull String catalogName) {
		final long deadlineNanos = System.nanoTime() + Duration.ofMinutes(30).toNanos();
		while (true) {
			final CatalogState state = evita.getCatalogState(catalogName)
				.orElseThrow(() -> new IllegalStateException(
					"Catalog `" + catalogName + "` not found in the freshly booted Evita instance!"
				));
			if (state == CatalogState.ALIVE) {
				return;
			}
			Assert.isPremiseValid(
				state.isTransitional(),
				() -> "Catalog `" + catalogName + "` ended up in unexpected non-transitional state `" + state +
					"` after boot instead of `ALIVE`!"
			);
			Assert.isPremiseValid(
				System.nanoTime() <= deadlineNanos,
				() -> "Timed out waiting for catalog `" + catalogName + "` to become `ALIVE` (still `" + state + "`)!"
			);
			try {
				Thread.sleep(200);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while waiting for catalog activation!", e);
			}
		}
	}

	@Override
	default boolean isCatalogAvailable(@Nonnull String catalogName) {
		// the catalog lives inside the benchmark-owned storage root, one level deeper than it used to
		final File storageRoot = benchmarkStorageDirectory(catalogName).toFile();
		// A catalog no longer occupies a folder named after it: allocation produces `<name>_<generation>`, and
		// which generation a given run drew is not knowable from here. Testing only the bare name made
		// every reusable dataset report itself absent, so each benchmark regenerated what it was meant to reuse.
		// The bare name still has to be accepted - a folder adopted from an older layout keeps it.
		final File[] candidates = storageRoot.listFiles(
			(dir, name) -> name.equals(catalogName) || name.startsWith(catalogName + '_')
		);
		if (candidates == null) {
			return false;
		}
		for (final File candidate : candidates) {
			if (candidate.isDirectory() && FileUtils.sizeOfDirectory(candidate) > 0) {
				return true;
			}
		}
		return false;
	}

	@Override
	default boolean shouldStartFromScratch() {
		return false;
	}

}
