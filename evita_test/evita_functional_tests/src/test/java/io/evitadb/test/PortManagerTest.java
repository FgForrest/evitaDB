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

import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.SERVER;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the port book-keeping contract of {@link PortManager}: ports are handed out sequentially
 * from the fixed base port 5560, already-allocated ports are skipped across datasets, released
 * ports become reusable, double allocation of the same dataset is rejected, and pending
 * completion-triggered releases are drained on the next allocation. The peak counter is asserted
 * against its actual implementation, which samples the live-port count at the *start* of each
 * allocation call.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(SERVER)
@Tag(MANAGEMENT)
@DisplayName("PortManager port allocation contract")
class PortManagerTest {

	/**
	 * Base port from which {@link PortManager} starts handing out ports (mirrors the constant used by
	 * the implementation).
	 */
	private static final int BASE_PORT = 5560;

	private PortManager portManager;

	@BeforeEach
	void setUp() {
		// each test mutates the manager, so a fresh instance guarantees isolation
		this.portManager = new PortManager();
	}

	@Nested
	@Tag(SERVER)
	@Tag(MANAGEMENT)
	@DisplayName("Allocation")
	class Allocation {

		@Test
		@DisplayName("allocates sequential ports starting from the base port")
		void shouldAllocateSequentialPortsFromBase() {
			final int[] ports = portManager.allocatePorts("dataset", 3);

			assertArrayEquals(new int[]{BASE_PORT, BASE_PORT + 1, BASE_PORT + 2}, ports);
		}

		@Test
		@DisplayName("skips ports already allocated to another dataset")
		void shouldSkipAlreadyAllocatedPortsAcrossDatasets() {
			final int[] first = portManager.allocatePorts("first", 2);
			final int[] second = portManager.allocatePorts("second", 2);

			assertArrayEquals(new int[]{BASE_PORT, BASE_PORT + 1}, first);
			assertArrayEquals(new int[]{BASE_PORT + 2, BASE_PORT + 3}, second);
		}

		@Test
		@DisplayName("increments the counter by the number of requested ports")
		void shouldIncrementCounterByRequestedCount() {
			portManager.allocatePorts("first", 3);
			portManager.allocatePorts("second", 2);

			assertEquals(5, portManager.getCounter());
		}

		@Test
		@DisplayName("tracks the peak of live ports sampled at the start of each allocation")
		void shouldTrackPeakOfAllocatedPorts() {
			portManager.allocatePorts("first", 3);
			// peak is sampled before the just-requested ports are added, so it is still zero here
			assertEquals(0, portManager.getPeak());

			portManager.allocatePorts("second", 2);
			// the second call observes the 3 ports left live by the first allocation
			assertEquals(3, portManager.getPeak());
		}

		@Test
		@DisplayName("throws when the same dataset is allocated twice")
		void shouldThrowWhenDatasetAllocatedTwice() {
			portManager.allocatePorts("dataset", 1);

			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> portManager.allocatePorts("dataset", 1)
			);
			assertTrue(error.getMessage().contains("dataset"));
		}
	}

	@Nested
	@Tag(SERVER)
	@Tag(MANAGEMENT)
	@DisplayName("Release")
	class Release {

		@Test
		@DisplayName("releases ports making them available for a later allocation")
		void shouldReleasePortsMakingThemAvailableAgain() {
			portManager.allocatePorts("first", 2);
			portManager.releasePorts("first");

			final int[] reused = portManager.allocatePorts("second", 2);

			assertArrayEquals(new int[]{BASE_PORT, BASE_PORT + 1}, reused);
		}

		@Test
		@DisplayName("does nothing when releasing an unknown dataset")
		void shouldNoOpWhenReleasingUnknownDataset() {
			assertDoesNotThrow(() -> portManager.releasePorts("never-allocated"));

			final int[] ports = portManager.allocatePorts("dataset", 2);
			assertArrayEquals(new int[]{BASE_PORT, BASE_PORT + 1}, ports);
		}

		@Test
		@DisplayName("drains already-completed pending releases on the next allocation")
		void shouldDrainCompletedPendingReleasesOnNextAllocation() {
			portManager.allocatePorts("first", 2);
			// register a release keyed on an already-completed future - it must be drained
			// deterministically on the very next allocation, freeing the ports for reuse
			portManager.releasePortsOnCompletion("first", CompletableFuture.completedFuture(null));

			final int[] reused = portManager.allocatePorts("second", 2);

			assertArrayEquals(new int[]{BASE_PORT, BASE_PORT + 1}, reused);
		}
	}
}
