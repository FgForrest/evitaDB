/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

import io.evitadb.store.model.FileLocation;
import io.evitadb.store.spi.SessionLocation;
import io.evitadb.store.traffic.OffHeapTrafficRecorder.MemoryNotAvailableException;
import io.evitadb.utils.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

import static io.evitadb.store.traffic.DiskRingBuffer.LEAD_DESCRIPTOR_BYTE_SIZE;
import static io.evitadb.store.traffic.DiskRingBuffer.segmentsOverlap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
		this.tempFile = Files.createTempFile("DiskRingBufferTest", ".tmp");
		this.diskRingBuffer = new DiskRingBuffer(this.tempFile, 1000);
	}

	@Test
	void shouldOverlappingHandleAllPossibleScenarios() {
		assertTrue(segmentsOverlap(new FileLocation(0, 1000), new FileLocation(100, 900)));
		assertTrue(segmentsOverlap(new FileLocation(100, 900), new FileLocation(0, 1000)));
		assertTrue(segmentsOverlap(new FileLocation(0, 100), new FileLocation(100, 200)));
		assertTrue(segmentsOverlap(new FileLocation(0, 100), new FileLocation(90, 200)));
		assertTrue(segmentsOverlap(new FileLocation(100, 200), new FileLocation(0, 100)));
		assertTrue(segmentsOverlap(new FileLocation(90, 200), new FileLocation(0, 100)));
		assertTrue(segmentsOverlap(new FileLocation(100, 200), new FileLocation(0, 100)));
		assertTrue(segmentsOverlap(new FileLocation(100, 200), new FileLocation(0, 110)));
		assertTrue(segmentsOverlap(new FileLocation(0, 100), new FileLocation(100, 200)));
		assertTrue(segmentsOverlap(new FileLocation(0, 110), new FileLocation(100, 200)));

		assertFalse(segmentsOverlap(new FileLocation(0, 100), new FileLocation(101, 200)));
		assertFalse(segmentsOverlap(new FileLocation(101, 200), new FileLocation(0, 100)));
		assertFalse(segmentsOverlap(new FileLocation(900, 1000), new FileLocation(0, 200)));
		assertFalse(segmentsOverlap(new FileLocation(0, 200), new FileLocation(900, 1000)));
	}

	@Test
	void shouldAppendSimpleCase() throws IOException {
		final int theFilledSize = 512;
		ByteBuffer buffer = ByteBuffer.allocate(theFilledSize);
		for (int i = 0; i < theFilledSize; i++) {
			buffer.put((byte) i);
		}
		buffer.flip(); // Reset buffer position to 0 before writing
		final SessionLocation sessionLocation = this.diskRingBuffer.appendSession(theFilledSize, theFilledSize);
		this.diskRingBuffer.append(buffer);
		this.diskRingBuffer.sessionWritten(sessionLocation, UUID.randomUUID(), OffsetDateTime.now(), 0, Set.of(), Set.of(), 0, 0);
		assertEquals(theFilledSize + LEAD_DESCRIPTOR_BYTE_SIZE, this.diskRingBuffer.getRingBufferTail());

		final int totalSpace = (int) this.tempFile.toFile().length();
		assertEquals(1000, totalSpace);

		// verify the content on the disk
		final byte[] allContent = Files.readAllBytes(this.tempFile);
		byte[] payloadContent = Arrays.copyOfRange(allContent, LEAD_DESCRIPTOR_BYTE_SIZE, LEAD_DESCRIPTOR_BYTE_SIZE + totalSpace);
		for (int i = 0; i < theFilledSize; i++) {
			assertEquals((byte) i, payloadContent[i]);
		}
		for (int i = theFilledSize; i < totalSpace; i++) {
			assertEquals((byte) 0, payloadContent[i]);
		}
	}

	@Test
	void shouldFailToAppendWhenDataIsTooLarge() {
		assertThrows(
			MemoryNotAvailableException.class,
			() -> {
				final int theFilledSize = 1500;
				ByteBuffer buffer = ByteBuffer.allocate(theFilledSize);
				for (int i = 0; i < theFilledSize; i++) {
					buffer.put((byte) i);
				}
				buffer.flip(); // Reset buffer position to 0 before writing
				final SessionLocation sessionLocation = this.diskRingBuffer.appendSession(theFilledSize, theFilledSize);
				this.diskRingBuffer.append(buffer);
			}
		);
	}

	@Test
	void shouldAppendBigDataWithWrappingAroundTheEndOfFile() throws IOException {
		final int theFilledSize = 1500;
		ByteBuffer buffer = ByteBuffer.allocate(theFilledSize);
		for (int i = 0; i < theFilledSize; i++) {
			buffer.put((byte) i);
		}
		buffer.flip(); // Reset buffer position to 0 before writing

		ByteBuffer bufferWithDescriptors = ByteBuffer.allocate(theFilledSize + 5 * LEAD_DESCRIPTOR_BYTE_SIZE);

		BiConsumer<Integer, Integer> writer = (index, length) -> {
			final SessionLocation sessionLocation = this.diskRingBuffer.appendSession(0, length);
			this.diskRingBuffer.append(buffer.slice(index, length));
			this.diskRingBuffer.sessionWritten(sessionLocation, UUID.randomUUID(), OffsetDateTime.now(), 0, Set.of(), Set.of(), 0, 0);

			bufferWithDescriptors.putLong(sessionLocation.sequenceOrder());
			bufferWithDescriptors.putInt(0);
			bufferWithDescriptors.putInt(length);
			bufferWithDescriptors.put(buffer.slice(index, length));
		};

		// append by multiple pieces which are lesser than the file size
		writer.accept(0, 300);
		writer.accept(300, 300);
		writer.accept(600, 300);
		writer.accept(900, 300);
		writer.accept(1200, 300);

		assertEquals(500 + 5 * LEAD_DESCRIPTOR_BYTE_SIZE, this.diskRingBuffer.getRingBufferTail());

		final long totalSpace = this.tempFile.toFile().length();
		assertEquals(1000, totalSpace);

		// verify the content on the disk
		byte[] fileContent = Files.readAllBytes(this.tempFile);
		for (int i = 0; i < this.diskRingBuffer.getRingBufferTail(); i++) {
			final int index = (int) (theFilledSize - (theFilledSize % totalSpace)) + i;
			final byte expectedByte = bufferWithDescriptors.get(index);
			assertEquals(expectedByte, fileContent[i]);
		}
		final long theStart = this.diskRingBuffer.getRingBufferTail();
		for (int i = (int) theStart; i < totalSpace; i++) {
			final byte expectedByte = bufferWithDescriptors.get(i);
			assertEquals(expectedByte, fileContent[i]);
		}
	}

    @AfterEach
    void teardown() throws Exception {
	    this.diskRingBuffer.close(FileUtils::deleteFileIfExists);
        Files.deleteIfExists(this.tempFile);
    }

}