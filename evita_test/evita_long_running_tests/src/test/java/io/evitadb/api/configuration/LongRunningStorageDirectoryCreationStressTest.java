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

package io.evitadb.api.configuration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.Nonnull;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stress verification that configurations built at the same moment over one missing storage directory all start.
 *
 * A configuration with the default server name derives the name from the creation time of its storage directory,
 * so the {@link EvitaConfiguration} constructor creates that directory when it is missing. Functional test classes
 * run in parallel, and on a fresh checkout every one of them that keeps the default storage directory (`./data`)
 * builds its configuration over the same missing directory at once. Exactly one of them creates it; every other one
 * must accept the directory it finds instead of refusing to start with "Unable to create storage directory".
 *
 * Nothing can stop a thread between finding the directory missing and creating it, so `EvitaConfigurationTest` in
 * the functional module pins only the deterministic neighbours: a missing directory is created, an existing one is
 * accepted, a regular file in its place is refused. This test covers the concurrent case the only way it can be
 * covered: it releases several builders at once over a fresh missing directory, round after round.
 *
 * **Calibration (measured, not estimated).** With the directory created by a `File#exists` check followed by
 * `File#mkdirs`, two runs refused 12,071 and 980 of the 16,000 builds, the first in rounds 0 and 4, and failed within
 * 2 s. With `Files#createDirectories` all 2,000 rounds pass. Re-measure after changing how the constructor creates the
 * directory: if that counterfactual stops failing, the rounds no longer reach the window and a green run proves
 * nothing - raise `ROUNDS` or `BUILDERS` until it fails again.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(CONTRACT)
@Tag(MANAGEMENT)
@DisplayName("Long-running storage directory creation stress tests")
class LongRunningStorageDirectoryCreationStressTest {

	/**
	 * Number of independent races. Each round uses a directory of its own that does not exist yet, because only the
	 * first creation of a directory can collide.
	 */
	private static final int ROUNDS = 2_000;
	/**
	 * Builders released at once in every round - more builders put more of them between the check and the creation
	 * of the same directory.
	 */
	private static final int BUILDERS = 8;
	/**
	 * Bound of the wait for one builder. A healthy build takes microseconds, so it only has to exceed scheduling
	 * noise on a loaded machine.
	 */
	private static final long BUILD_TIMEOUT_SECONDS = 30L;

	@Test
	@DisplayName("Never refuses a storage directory another configuration creates at the same moment")
	void shouldNeverRefuseStorageDirectoryCreatedAtTheSameMoment(@TempDir Path tempDir) throws Exception {
		final ExecutorService builderPool = Executors.newFixedThreadPool(BUILDERS, daemonFactory());
		try {
			int refusedBuilds = 0;
			Throwable firstRefusal = null;
			for (int round = 0; round < ROUNDS; round++) {
				final Path storageDirectory = tempDir.resolve("data-" + round);
				final StorageOptions storageOptions = StorageOptions.builder()
					.storageDirectory(storageDirectory)
					.build();
				final CountDownLatch start = new CountDownLatch(1);
				final List<Future<EvitaConfiguration>> builds = new ArrayList<>(BUILDERS);
				for (int builder = 0; builder < BUILDERS; builder++) {
					builds.add(
						builderPool.submit(
							() -> {
								start.await();
								return EvitaConfiguration.builder().storage(storageOptions).build();
							}
						)
					);
				}
				start.countDown();
				for (Future<EvitaConfiguration> build : builds) {
					try {
						build.get(BUILD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
					} catch (ExecutionException ex) {
						refusedBuilds++;
						if (firstRefusal == null) {
							firstRefusal = ex.getCause();
						}
					}
				}
				assertTrue(Files.isDirectory(storageDirectory), "Round " + round + " left no storage directory.");
			}
			final Throwable reportedRefusal = firstRefusal;
			assertEquals(
				0, refusedBuilds,
				() -> "Builds refused over " + ROUNDS + " rounds of " + BUILDERS + " builders, the first with: " +
					reportedRefusal
			);
		} finally {
			builderPool.shutdownNow();
		}
	}

	/**
	 * Creates the factory of the builder threads. They are daemons, so a builder stuck past the test cannot keep the
	 * test JVM alive.
	 *
	 * @return factory of named daemon threads
	 */
	@Nonnull
	private static ThreadFactory daemonFactory() {
		final AtomicInteger counter = new AtomicInteger();
		return runnable -> {
			final Thread thread = new Thread(runnable, "storage-directory-builder-" + counter.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		};
	}

}
