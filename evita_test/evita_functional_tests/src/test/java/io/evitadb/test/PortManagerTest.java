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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;

import java.util.concurrent.CompletableFuture;

import static io.evitadb.test.PortManager.PORT_BASE_PROPERTY;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.SERVER;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the port book-keeping contract of {@link PortManager}: ports are handed out sequentially
 * from the configured base port (5560 by default), already-allocated ports are skipped across datasets, released
 * ports become reusable, double allocation of the same dataset is rejected, and pending
 * completion-triggered releases are drained on the next allocation. The peak counter is asserted
 * against its actual implementation, which samples the live-port count at the *start* of each
 * allocation call.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(SERVER)
@Tag(MANAGEMENT)
@Isolated("Changes the port base system property")
@DisplayName("PortManager port allocation contract")
class PortManagerTest {

	/**
	 * Default base port used when the system property is absent.
	 */
	private static final int BASE_PORT = 5560;

	private PortManager portManager;
	private String originalPortBase;

	/**
	 * Saves the caller's property and creates a fresh manager with the default base for each test.
	 */
	@BeforeEach
	void setUp() {
		this.originalPortBase = System.getProperty(PORT_BASE_PROPERTY);
		System.clearProperty(PORT_BASE_PROPERTY);
		// each test mutates the manager, so a fresh instance guarantees isolation
		this.portManager = new PortManager();
	}

	/**
	 * Restores the caller's property even when a test fails.
	 */
	@AfterEach
	void tearDown() {
		if (this.originalPortBase == null) {
			System.clearProperty(PORT_BASE_PROPERTY);
		} else {
			System.setProperty(PORT_BASE_PROPERTY, this.originalPortBase);
		}
	}

	/**
	 * Verifies property parsing, inclusive limits and the lifetime of the configured base.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@Tag(SERVER)
	@Tag(MANAGEMENT)
	@DisplayName("Base port configuration")
	class Configuration {

		/**
		 * Verifies the fallback when no property was provided.
		 */
		@Test
		@DisplayName("uses 5560 when the property is absent")
		void shouldUseDefaultBaseWhenPropertyIsAbsent() {
			assertArrayEquals(new int[]{BASE_PORT}, portManager.allocatePorts("default", 1));
		}

		/**
		 * Verifies a custom base and both inclusive boundaries.
		 */
		@ParameterizedTest
		@ValueSource(ints = {1024, 7000, 65000})
		@DisplayName("allocates sequential ports from a valid configured base")
		void shouldAllocateFromCustomBaseWhenPropertyIsSet(int base) {
			System.setProperty(PORT_BASE_PROPERTY, Integer.toString(base));
			final PortManager manager = new PortManager();

			assertArrayEquals(new int[]{base, base + 1}, manager.allocatePorts("custom", 2));
		}

		/**
		 * Verifies that later property changes cannot move an existing manager's allocation range.
		 */
		@Test
		@DisplayName("reads the property only at construction")
		void shouldKeepInitialBaseWhenPropertyChanges() {
			System.setProperty(PORT_BASE_PROPERTY, "7000");
			final PortManager manager = new PortManager();
			System.setProperty(PORT_BASE_PROPERTY, "8000");

			assertArrayEquals(new int[]{7000}, manager.allocatePorts("first", 1));
			assertArrayEquals(new int[]{7001}, manager.allocatePorts("second", 1));
		}

		/**
		 * Verifies that invalid configuration fails with the property name, value and accepted range.
		 */
		@ParameterizedTest
		@ValueSource(strings = {"1023", "65001", "not-an-integer", "", "2147483648"})
		@DisplayName("rejects invalid bases with a clear message")
		void shouldThrowExceptionWhenBaseIsInvalid(@Nonnull String base) {
			System.setProperty(PORT_BASE_PROPERTY, base);

			final IllegalArgumentException error = assertThrows(IllegalArgumentException.class, PortManager::new);
			assertEquals(
				"System property `" + PORT_BASE_PROPERTY +
					"` must be an integer within 1024..65000 (inclusive), but was `" + base + "`.",
				error.getMessage()
			);
		}
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

	/**
	 * Verifies that exhaustion never changes allocation state or returns a port above `65535`.
	 */
	@Nested
	@Tag(SERVER)
	@Tag(MANAGEMENT)
	@DisplayName("Port range exhaustion")
	class Exhaustion {

		/**
		 * Uses the highest configured base to exercise the end of the valid range.
		 */
		@BeforeEach
		void setHighBase() {
			System.setProperty(PORT_BASE_PROPERTY, "65000");
			portManager = new PortManager();
		}

		/**
		 * Rejects an oversized first allocation before changing counters or reserving ports.
		 */
		@Test
		@DisplayName("rejects 537 ports from base 65000 without changing state")
		void shouldRejectAllocationAboveValidRangeWithoutChangingState() {
			final IllegalStateException error = assertThrows(
				IllegalStateException.class, () -> portManager.allocatePorts("dataset", 537)
			);
			assertEquals(
				"Cannot allocate 537 ports for dataset dataset: only 536 ports remain in range 65000..65535.",
				error.getMessage()
			);
			assertEquals(0, portManager.getCounter());
			assertEquals(0, portManager.getPeak());
			final int[] ports = portManager.allocatePorts("dataset", 536);
			assertEquals(65000, ports[0]);
			assertEquals(65535, ports[535]);
		}

		/**
		 * Counts live allocations across datasets and preserves them after a rejected request.
		 */
		@Test
		@DisplayName("checks remaining capacity across several datasets")
		void shouldRejectExhaustionAcrossDatasetsWithoutChangingState() {
			portManager.allocatePorts("first", 200);
			portManager.allocatePorts("second", 335);
			assertThrows(IllegalStateException.class, () -> portManager.allocatePorts("third", 2));
			assertEquals(535, portManager.getCounter());
			assertEquals(200, portManager.getPeak());
			assertArrayEquals(new int[]{65535}, portManager.allocatePorts("third", 1));
			assertThrows(IllegalStateException.class, () -> portManager.allocatePorts("fourth", 1));
			portManager.releasePorts("first");
			final int[] reused = portManager.allocatePorts("fourth", 200);
			assertEquals(65000, reused[0]);
			assertEquals(65199, reused[199]);
		}

		/**
		 * Includes completed pending releases in available capacity without draining them on failure.
		 */
		@Test
		@DisplayName("reuses completed pending releases after an oversized request fails")
		void shouldAccountForCompletedReleasesBeforeCheckingCapacity() {
			portManager.allocatePorts("first", 536);
			portManager.releasePortsOnCompletion("first", CompletableFuture.completedFuture(null));
			assertThrows(IllegalStateException.class, () -> portManager.allocatePorts("second", 537));
			assertEquals(536, portManager.getCounter());
			assertEquals(0, portManager.getPeak());
			final int[] reused = portManager.allocatePorts("second", 536);
			assertEquals(65000, reused[0]);
			assertEquals(65535, reused[535]);
			assertEquals(0, portManager.getPeak());
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
