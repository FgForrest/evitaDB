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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.store.offsetIndex.io;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.BufferOverflowException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies {@link OffHeapMemoryManager} functionality.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
class OffHeapMemoryManagerTest {
	private final OffHeapMemoryManager memoryManager = new OffHeapMemoryManager(1024, 16);

	@Test
	void shouldAcquireOutputStreamWriteOutputAndReadItAgain() throws IOException {
		try (final var outputStream = memoryManager.acquireRegionOutputStream().orElseThrow()) {
			assertEquals(15, memoryManager.getFreeRegions());

			final var expected = "Hello world!";
			// when
			outputStream.write(expected.getBytes());
			final var inputStream = outputStream.getInputStream();
			final var actual = new String(inputStream.readAllBytes());

			assertEquals(expected, actual);
		}
		assertEquals(16, memoryManager.getFreeRegions());
	}

	@Test
	void shouldFailToWriteToStreamAfterInputStreamWasRetrieved() throws IOException {
		try (final var outputStream = memoryManager.acquireRegionOutputStream().orElseThrow()) {
			outputStream.write("Hello world!".getBytes());
			outputStream.getInputStream();
			assertThrows(RuntimeException.class, () -> outputStream.write("Hello world!".getBytes()));
		}
	}

	@Test
	void shouldFailToWriteToStreamAfterItWasClosed() {
		final var outputStream = memoryManager.acquireRegionOutputStream().orElseThrow();
		outputStream.close();
		assertThrows(RuntimeException.class, () -> outputStream.write("Hello world!".getBytes()));
	}

	@Test
	void shouldFailToReadFromStreamAfterItWasClosed() throws IOException {
		final var outputStream = memoryManager.acquireRegionOutputStream().orElseThrow();
		final OffHeapMemoryInputStream inputStream = outputStream.getInputStream();
		inputStream.close();
		assertThrows(RuntimeException.class, inputStream::read);
	}

	@Test
	void shouldFailToReadFromStreamAfterOriginalOutputStreamWasClosed() {
		final var outputStream = memoryManager.acquireRegionOutputStream().orElseThrow();
		final var inputStream = outputStream.getInputStream();
		outputStream.close();
		assertThrows(RuntimeException.class, inputStream::read);
	}

	@Test
	void shouFailToWriteBytesOutsideTheRegion() throws IOException {
		final var outputStream = memoryManager.acquireRegionOutputStream().orElseThrow();
		final int regionIndex = outputStream.getRegionIndex();
		for (int i = 0; i < 64; i++) {
			outputStream.write((byte) 1);
		}
		// writing byte over the limit should fail
		assertThrows(BufferOverflowException.class, () -> outputStream.write((byte) 1));
		// check only data that fit the region were set
		final byte[] fullMemoryBlockCopy = new byte[1024];
		memoryManager.memoryBlock.get().get(fullMemoryBlockCopy, 0, 1024);
		for (int i = 0; i < 1024; i++) {
			if (i >= regionIndex * 64 && i < regionIndex * 64 + 64) {
				assertEquals(1, fullMemoryBlockCopy[i], "Byte at index " + i + " should be 1!");
			} else {
				assertEquals(0, fullMemoryBlockCopy[i], "Byte at index " + i + " should be 0!");
			}
		}
	}

	@Test
	void shouldFailToAcquireOutputStreamWhenFreeRegionsAreExhausted() {
		for (int i = 0; i < 16; i++) {
			assertTrue(memoryManager.acquireRegionOutputStream().isPresent());
		}

		assertFalse(memoryManager.acquireRegionOutputStream().isPresent());
		assertEquals(0, memoryManager.getFreeRegions());
		memoryManager.close();
	}
}