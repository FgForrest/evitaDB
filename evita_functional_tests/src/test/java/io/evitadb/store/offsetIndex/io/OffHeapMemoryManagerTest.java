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

package io.evitadb.store.offsetIndex.io;

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.test.TestConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.BufferOverflowException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CatalogOffHeapMemoryManager} focusing on:
 * - region acquisition and release lifecycle
 * - read/write semantics of paired off-heap streams
 * - boundary checks and region exhaustion behavior
 *
 * These tests exercise low-level off-heap memory management to ensure safety and correctness.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("CatalogOffHeapMemoryManager off-heap regions and stream semantics")
class OffHeapMemoryManagerTest {
	private final CatalogOffHeapMemoryManager memoryManager = new CatalogOffHeapMemoryManager(
		TestConstants.TEST_CATALOG, 1024, 16);

	/**
	 * Verifies that a region output stream can be acquired, written to, and read back
	 * via its paired input stream. Also checks the free region counter is decremented while
	 * the stream is held and restored once it is closed.
	 */
	@Test
	@DisplayName("should write to region and read it back via paired input stream")
	void shouldAcquireOutputStreamWriteOutputAndReadItAgain() throws IOException {
		try (final var outputStream = this.memoryManager.acquireRegionOutputStream().orElseThrow()) {
			assertEquals(15, this.memoryManager.getFreeRegions());

			final var expected = "Hello world!";
			// when
			outputStream.write(expected.getBytes());
			final var inputStream = outputStream.getInputStream();
			final var actual = new String(inputStream.readAllBytes());

			assertEquals(expected, actual);
		}
		assertEquals(16, this.memoryManager.getFreeRegions());
	}

	/**
	 * Ensures writing to the output stream after it has been explicitly closed
	 * results in a runtime failure.
	 */
	@Test
	@DisplayName("should fail to write after output stream is closed")
	void shouldFailToWriteToStreamAfterItWasClosed() {
		final var outputStream = this.memoryManager.acquireRegionOutputStream().orElseThrow();
		outputStream.close();
		assertThrows(RuntimeException.class, () -> outputStream.write("Hello world!".getBytes()));
	}

	/**
	 * Ensures reading from the input stream after the input stream itself is closed
	 * fails with a runtime exception.
	 */
	@Test
	@DisplayName("should fail to read after input stream is closed")
	void shouldFailToReadFromStreamAfterItWasClosed() {
		final var outputStream = this.memoryManager.acquireRegionOutputStream().orElseThrow();
		final OffHeapMemoryInputStream inputStream = outputStream.getInputStream();
		inputStream.close();
		assertThrows(RuntimeException.class, inputStream::read);
	}

	/**
	 * Ensures that closing the producing output stream invalidates the derived input stream.
	 */
	@Test
	@DisplayName("should fail to read when original output stream is closed")
	void shouldFailToReadFromStreamAfterOriginalOutputStreamWasClosed() {
		final var outputStream = this.memoryManager.acquireRegionOutputStream().orElseThrow();
		final var inputStream = outputStream.getInputStream();
		outputStream.close();
		assertThrows(RuntimeException.class, inputStream::read);
	}

	/**
	 * Validates that attempting to write beyond the fixed-size region throws BufferOverflowException
	 * and that bytes written within bounds affect only the allocated region while other memory
	 * remains zeroed.
	 */
	@Test
	@DisplayName("should prevent writes beyond region and not affect other regions")
	void shouFailToWriteBytesOutsideTheRegion() throws IOException {
		try (
			final var outputStream = this.memoryManager.acquireRegionOutputStream().orElseThrow()
		) {
			final int regionIndex = outputStream.getRegionIndex();
			for (int i = 0; i < 64; i++) {
				outputStream.write((byte) 1);
			}
			// writing byte over the limit should fail
			assertThrows(BufferOverflowException.class, () -> outputStream.write((byte) 1));
			// check only data that fit the region were set
			final byte[] fullMemoryBlockCopy = new byte[1024];
			this.memoryManager.memoryBlock.get().get(fullMemoryBlockCopy, 0, 1024);
			for (int i = 0; i < 1024; i++) {
				if (i >= regionIndex << 6 && i < (regionIndex << 6) + 64) {
					assertEquals(1, fullMemoryBlockCopy[i], "Byte at index " + i + " should be 1!");
				} else {
					assertEquals(0, fullMemoryBlockCopy[i], "Byte at index " + i + " should be 0!");
				}
			}
		}
	}

	/**
	 * Verifies that only a fixed number of regions can be acquired and that further acquisition
	 * attempts fail once the pool is exhausted. Also asserts the free region counter.
	 */
	@Test
	@DisplayName("should not acquire stream when free regions are exhausted")
	void shouldFailToAcquireOutputStreamWhenFreeRegionsAreExhausted() {
		for (int i = 0; i < 16; i++) {
			assertTrue(this.memoryManager.acquireRegionOutputStream().isPresent());
		}

		assertFalse(this.memoryManager.acquireRegionOutputStream().isPresent());
		assertEquals(0, this.memoryManager.getFreeRegions());
		this.memoryManager.close();
	}

	/**
	 * Ensures that releasing a region via the manager frees the region and invalidates
	 * both the output and its paired input stream so they can no longer be used.
	 */
	@Test
	@DisplayName("shouldReleaseRegionAndIncreaseFreeRegionsWhenStreamIsReleased")
	void shouldReleaseRegionAndIncreaseFreeRegionsWhenStreamIsReleased() {
		try (
			final CatalogOffHeapMemoryManager memoryManager = new CatalogOffHeapMemoryManager(
				TestConstants.TEST_CATALOG, 1024, 16
			);
			final OffHeapMemoryOutputStream outputStream = memoryManager.acquireRegionOutputStream().orElseThrow()
		) {
			final int regionIndex = outputStream.getRegionIndex();
			assertEquals(15, memoryManager.getFreeRegions());

			// prepare input stream as well to ensure it is closed by manager's release
			final OffHeapMemoryInputStream inputStream = outputStream.getInputStream();

			// when: release via manager
			memoryManager.releaseRegionStream(regionIndex);

			// then: region is freed
			assertEquals(16, memoryManager.getFreeRegions());

			// and streams are unusable after release
			assertThrows(RuntimeException.class, () -> outputStream.write(1));
			assertThrows(RuntimeException.class, inputStream::read);
		}
	}

	/**
	 * Ensures that releasing the same region twice is rejected with GenericEvitaInternalError
	 * and an explanatory message.
	 */
	@Test
	@DisplayName("shouldThrowExceptionWhenReleasingAlreadyReleasedRegion")
	void shouldThrowExceptionWhenReleasingAlreadyReleasedRegion() {
		try (
			final CatalogOffHeapMemoryManager memoryManager = new CatalogOffHeapMemoryManager(
				TestConstants.TEST_CATALOG, 1024, 16
			);
			final OffHeapMemoryOutputStream outputStream = memoryManager.acquireRegionOutputStream().orElseThrow()
		) {
			final int regionIndex = outputStream.getRegionIndex();

			// first release succeeds
			memoryManager.releaseRegionStream(regionIndex);

			// second release should fail with internal error per Assert.isPremiseValid
			final GenericEvitaInternalError ex = assertThrows(
				GenericEvitaInternalError.class,
				() -> memoryManager.releaseRegionStream(regionIndex)
			);
			// optional message check to ensure clarity
			assertEquals("Stream at index " + regionIndex + " is already released!", ex.getMessage());
		}
	}

}
