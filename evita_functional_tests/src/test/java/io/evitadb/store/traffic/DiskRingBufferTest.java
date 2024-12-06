/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.store.traffic;

import io.evitadb.store.traffic.OffHeapTrafficRecorder.MemoryNotAvailableException;
import io.evitadb.store.traffic.data.SessionLocation;
import io.evitadb.utils.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.BiConsumer;

import static io.evitadb.store.traffic.DiskRingBuffer.LEAD_DESCRIPTOR_BYTE_SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * This test verifies {@link DiskRingBuffer} functionality.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
class DiskRingBufferTest {
	private DiskRingBuffer diskRingBuffer;
	private Path tempFile;

	@BeforeEach
	void setup() throws Exception {
		tempFile = Files.createTempFile("DiskRingBufferTest", ".tmp");
		diskRingBuffer = new DiskRingBuffer(tempFile, 1000);
	}

	@Test
	void testAppendSimpleCase() throws IOException {
		final int theFilledSize = 512;
		ByteBuffer buffer = ByteBuffer.allocate(theFilledSize);
		for (int i = 0; i < theFilledSize; i++) {
			buffer.put((byte) i);
		}
		buffer.flip(); // Reset buffer position to 0 before writing
		final SessionLocation sessionLocation = diskRingBuffer.appendSession(theFilledSize);
		diskRingBuffer.append(sessionLocation, buffer);
		diskRingBuffer.sessionWritten(sessionLocation, value -> null);
		assertEquals(theFilledSize + LEAD_DESCRIPTOR_BYTE_SIZE, diskRingBuffer.getRingBufferTail());

		final int totalSpace = (int) tempFile.toFile().length();
		assertEquals(1000, totalSpace);

		// verify the content on the disk
		final byte[] allContent = Files.readAllBytes(tempFile);
		byte[] payloadContent = Arrays.copyOfRange(allContent, LEAD_DESCRIPTOR_BYTE_SIZE, LEAD_DESCRIPTOR_BYTE_SIZE + totalSpace);
		for (int i = 0; i < theFilledSize; i++) {
			assertEquals((byte) i, payloadContent[i]);
		}
		for (int i = theFilledSize; i < totalSpace; i++) {
			assertEquals((byte) 0, payloadContent[i]);
		}
	}

	@Test
	void testAppendWithWrappingDataTooLarge() {
		assertThrows(
			MemoryNotAvailableException.class,
			() -> {
				final int theFilledSize = 1500;
				ByteBuffer buffer = ByteBuffer.allocate(theFilledSize);
				for (int i = 0; i < theFilledSize; i++) {
					buffer.put((byte) i);
				}
				buffer.flip(); // Reset buffer position to 0 before writing
				final SessionLocation sessionLocation = diskRingBuffer.appendSession(1500);
				diskRingBuffer.append(sessionLocation, buffer);
			}
		);
	}

	@Test
	void testAppendWithWrapping() throws IOException {
		final int theFilledSize = 1500;
		ByteBuffer buffer = ByteBuffer.allocate(theFilledSize);
		for (int i = 0; i < theFilledSize; i++) {
			buffer.put((byte) i);
		}
		buffer.flip(); // Reset buffer position to 0 before writing

		ByteBuffer bufferWithDescriptors = ByteBuffer.allocate(theFilledSize + 5 * LEAD_DESCRIPTOR_BYTE_SIZE);

		BiConsumer<Integer, Integer> writer = (index, length) -> {
			final SessionLocation sessionLocation = diskRingBuffer.appendSession(length);
			diskRingBuffer.append(sessionLocation, buffer.slice(index, length));
			diskRingBuffer.sessionWritten(sessionLocation, value -> null);

			bufferWithDescriptors.putLong(sessionLocation.sequenceOrder());
			bufferWithDescriptors.putInt(length);
			bufferWithDescriptors.put(buffer.slice(index, length));
		};

		// append by multiple pieces which are lesser than the file size
		writer.accept(0, 300);
		writer.accept(300, 300);
		writer.accept(600, 300);
		writer.accept(900, 300);
		writer.accept(1200, 300);

		assertEquals(500 + 5 * LEAD_DESCRIPTOR_BYTE_SIZE, diskRingBuffer.getRingBufferTail());

		final long totalSpace = tempFile.toFile().length();
		assertEquals(1000, totalSpace);

		// verify the content on the disk
		byte[] fileContent = Files.readAllBytes(tempFile);
		for (int i = 0; i < diskRingBuffer.getRingBufferTail(); i++) {
			final int index = (int) (theFilledSize - (theFilledSize % totalSpace)) + i;
			final byte expectedByte = bufferWithDescriptors.get(index);
			assertEquals(expectedByte, fileContent[i]);
		}
		final long theStart = diskRingBuffer.getRingBufferTail();
		for (int i = (int) theStart; i < totalSpace; i++) {
			final byte expectedByte = bufferWithDescriptors.get(i);
			assertEquals(expectedByte, fileContent[i]);
		}
	}

    @AfterEach
    void teardown() throws Exception {
        diskRingBuffer.close(FileUtils::deleteFileIfExists);
        Files.deleteIfExists(tempFile);
    }

}