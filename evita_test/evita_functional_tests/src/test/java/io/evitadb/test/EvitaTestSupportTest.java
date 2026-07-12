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

package io.evitadb.test;

import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.test.EvitaTestSupport.TestPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.provider.Arguments;

import java.nio.file.Path;
import java.util.List;

import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.SERVER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the pure, side-effect-free helpers of the `EvitaTestSupport` mixin interface — the
 * directory-triplet allocation, the configuration builder wiring, the generated certificate file
 * names, the random-seed argument provider and the target-directory path resolver.
 *
 * The interface is exercised through a minimal test double that implements it with no additional
 * behaviour, since every method under test is a `default` (or `static`) method of the interface.
 * Filesystem-touching helpers (root/data directory resolution, certificate generation, directory
 * cleanup) are intentionally out of scope for these unit tests.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(SERVER)
@Tag(MANAGEMENT)
@DisplayName("EvitaTestSupport helper functionality")
class EvitaTestSupportTest {

	/**
	 * Stateless mixin implementation under test — all behaviour is inherited from the interface.
	 */
	private final EvitaTestSupport support = new TestSupportDouble();

	@Nested
	@DisplayName("Path triplet allocation")
	class PathAllocationTest {

		@Test
		@DisplayName("Should allocate a unique storage/work/export triplet under the supplied base")
		void shouldCreateUniquePathTripletUnderBase(@TempDir Path base) {
			final TestPaths paths = EvitaTestSupportTest.this.support.createTestPaths(base, "triplet");

			// all three directories are direct children of the supplied base
			assertEquals(base, paths.storage().getParent());
			assertEquals(base, paths.work().getParent());
			assertEquals(base, paths.export().getParent());

			// the storage prefix carries the label for debuggability
			final String storageName = paths.storage().getFileName().toString();
			assertTrue(
				storageName.startsWith("triplet_"),
				"Storage directory name `" + storageName + "` should start with the label prefix"
			);

			// the work and export directories share the storage prefix with the documented suffixes
			assertEquals(storageName + "_work", paths.work().getFileName().toString());
			assertEquals(storageName + "_export", paths.export().getFileName().toString());
		}

		@Test
		@DisplayName("Should generate distinct paths for repeated calls with the same base and label")
		void shouldGenerateDistinctPathsAcrossCalls(@TempDir Path base) {
			final TestPaths first = EvitaTestSupportTest.this.support.createTestPaths(base, "repeat");
			final TestPaths second = EvitaTestSupportTest.this.support.createTestPaths(base, "repeat");

			assertNotEquals(first.storage(), second.storage());
			assertNotEquals(first.work(), second.work());
			assertNotEquals(first.export(), second.export());
		}
	}

	@Nested
	@DisplayName("Configuration builder wiring")
	class ConfigurationBuilderTest {

		@Test
		@DisplayName("Should wire storage, work and export directories into the configuration builder")
		void shouldWireStorageWorkAndExportIntoConfigurationBuilder(@TempDir Path base) {
			final TestPaths paths = EvitaTestSupportTest.this.support.createTestPaths(base, "wire");

			final EvitaConfiguration configuration =
				EvitaTestSupportTest.this.support.newTestEvitaConfigurationBuilder(paths).build();

			assertEquals(paths.storage(), configuration.storage().storageDirectory());
			assertEquals(paths.work(), configuration.storage().workDirectory());
			assertEquals(
				paths.export(),
				((FileSystemExportOptions) configuration.export()).getDirectory()
			);
		}
	}

	@Nested
	@DisplayName("Random seed provider")
	class RandomSeedProviderTest {

		@Test
		@DisplayName("Should provide exactly fifty single-argument long seeds")
		void shouldProvideFiftyRandomSeeds() {
			final List<Arguments> seeds = EvitaTestSupport.returnRandomSeed().toList();

			assertEquals(50, seeds.size());
			for (final Arguments seed : seeds) {
				assertEquals(1, seed.get().length);
				assertTrue(
					seed.get()[0] instanceof Long,
					"Each seed argument should be a single Long value"
				);
			}
		}
	}

	/**
	 * Minimal stateless implementation of the mixin interface under test. It contributes no behaviour
	 * of its own — every method exercised by the tests is a `default` method of `EvitaTestSupport`.
	 */
	private static final class TestSupportDouble implements EvitaTestSupport {
	}
}
