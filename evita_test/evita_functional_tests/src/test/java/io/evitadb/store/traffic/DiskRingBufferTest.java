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

import io.evitadb.spi.store.catalog.trafficRecorder.model.SessionFileLocation;
import io.evitadb.spi.store.catalog.trafficRecorder.model.SessionLocation;
import io.evitadb.store.shared.model.FileLocation;
import io.evitadb.store.traffic.OffHeapTrafficRecorder.MemoryNotAvailableException;
import io.evitadb.utils.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Deque;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static io.evitadb.store.traffic.DiskRingBuffer.LEAD_DESCRIPTOR_BYTE_SIZE;
import static io.evitadb.store.traffic.DiskRingBuffer.segmentsOverlap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies {@link DiskRingBuffer} functionality.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@DisplayName("DiskRingBuffer")
class DiskRingBufferTest {
	private DiskRingBuffer diskRingBuffer;
	private Path tempFile;

	@BeforeEach
	void setup() throws Exception {
		this.tempFile = Files.createTempFile("DiskRingBufferTest", ".tmp");
		this.diskRingBuffer = new DiskRingBuffer(this.tempFile, 1000);
	}

	@AfterEach
	void teardown() throws Exception {
		this.diskRingBuffer.close(FileUtils::deleteFileIfExists);
		Files.deleteIfExists(this.tempFile);
	}

	/**
	 * Tests for the static {@link DiskRingBuffer#segmentsOverlap(FileLocation, FileLocation)} method.
	 */
	@Nested
	@DisplayName("Segment overlap detection")
	class SegmentOverlapTest {

		@Test
		@DisplayName("Should handle all possible overlap and non-overlap scenarios")
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

	}

	/**
	 * Tests for {@link DiskRingBuffer#appendSession(int, int)} and
	 * {@link DiskRingBuffer#append(ByteBuffer)} writing mechanics.
	 */
	@Nested
	@DisplayName("Append operations")
	class AppendTest {

		@Test
		@DisplayName("Should append simple data and verify on-disk content")
		void shouldAppendSimpleCase() throws IOException {
			final int theFilledSize = 512;
			final ByteBuffer buffer = ByteBuffer.allocate(theFilledSize);
			for (int i = 0; i < theFilledSize; i++) {
				buffer.put((byte) i);
			}
			buffer.flip();
			final SessionLocation sessionLocation = diskRingBuffer.appendSession(theFilledSize, theFilledSize);
			diskRingBuffer.append(buffer);
			diskRingBuffer.sessionWritten(sessionLocation, UUID.randomUUID(), OffsetDateTime.now(), 0, Set.of(), Set.of(), 0, 0);
			assertEquals(theFilledSize + LEAD_DESCRIPTOR_BYTE_SIZE, diskRingBuffer.getRingBufferTail());

			final int totalSpace = (int) tempFile.toFile().length();
			assertEquals(1000, totalSpace);

			// verify the content on the disk
			final byte[] allContent = Files.readAllBytes(tempFile);
			final byte[] payloadContent = Arrays.copyOfRange(allContent, LEAD_DESCRIPTOR_BYTE_SIZE, LEAD_DESCRIPTOR_BYTE_SIZE + totalSpace);
			for (int i = 0; i < theFilledSize; i++) {
				assertEquals((byte) i, payloadContent[i]);
			}
			for (int i = theFilledSize; i < totalSpace; i++) {
				assertEquals((byte) 0, payloadContent[i]);
			}
		}

		@Test
		@DisplayName("Should fail when data exceeds buffer size via append()")
		void shouldFailToAppendWhenDataIsTooLarge() {
			assertThrows(
				MemoryNotAvailableException.class,
				() -> {
					final int theFilledSize = 1500;
					final ByteBuffer buffer = ByteBuffer.allocate(theFilledSize);
					for (int i = 0; i < theFilledSize; i++) {
						buffer.put((byte) i);
					}
					buffer.flip();
					diskRingBuffer.appendSession(theFilledSize, theFilledSize);
					diskRingBuffer.append(buffer);
				}
			);
		}

		@Test
		@DisplayName("Should wrap around the end of file when appending big data in multiple pieces")
		void shouldAppendBigDataWithWrappingAroundTheEndOfFile() throws IOException {
			final int theFilledSize = 1500;
			final ByteBuffer buffer = ByteBuffer.allocate(theFilledSize);
			for (int i = 0; i < theFilledSize; i++) {
				buffer.put((byte) i);
			}
			buffer.flip();

			final ByteBuffer bufferWithDescriptors = ByteBuffer.allocate(theFilledSize + 5 * LEAD_DESCRIPTOR_BYTE_SIZE);

			final BiConsumer<Integer, Integer> writer = (index, length) -> {
				final SessionLocation sessionLocation = diskRingBuffer.appendSession(0, length);
				diskRingBuffer.append(buffer.slice(index, length));
				diskRingBuffer.sessionWritten(sessionLocation, UUID.randomUUID(), OffsetDateTime.now(), 0, Set.of(), Set.of(), 0, 0);

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

			assertEquals(500 + 5 * LEAD_DESCRIPTOR_BYTE_SIZE, diskRingBuffer.getRingBufferTail());

			final long totalSpace = tempFile.toFile().length();
			assertEquals(1000, totalSpace);

			// verify the content on the disk
			final byte[] fileContent = Files.readAllBytes(tempFile);
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

		@Test
		@DisplayName("Should append session with zero records and zero body size")
		void shouldAppendSessionWithZeroRecords() throws IOException {
			final SessionLocation sessionLocation = diskRingBuffer.appendSession(0, 0);
			diskRingBuffer.sessionWritten(sessionLocation, UUID.randomUUID(), OffsetDateTime.now(), 0, Set.of(), Set.of(), 0, 0);

			// only descriptor was written (16 bytes)
			assertEquals(LEAD_DESCRIPTOR_BYTE_SIZE, diskRingBuffer.getRingBufferTail());
			assertEquals(1, sessionLocation.sequenceOrder());
			assertEquals(0, sessionLocation.sessionRecordsCount());
			assertEquals(LEAD_DESCRIPTOR_BYTE_SIZE, sessionLocation.location().recordLength());

			// verify descriptor content on disk
			final byte[] allContent = Files.readAllBytes(tempFile);
			final ByteBuffer descriptorBuffer = ByteBuffer.wrap(allContent, 0, LEAD_DESCRIPTOR_BYTE_SIZE);
			assertEquals(1L, descriptorBuffer.getLong());  // sequence order
			assertEquals(0, descriptorBuffer.getInt());    // session records count
			assertEquals(0, descriptorBuffer.getInt());    // total body size
		}

		@Test
		@DisplayName("Should reject session via appendSession() when totalSize + header exceeds buffer")
		void shouldRejectSessionLargerThanBufferViaAppendSession() {
			// buffer = 1000, body = 985, total = 985 + 16 = 1001 > 1000
			assertThrows(
				MemoryNotAvailableException.class,
				() -> diskRingBuffer.appendSession(1, 985)
			);
		}

		@Test
		@DisplayName("Should handle append that exactly fills buffer to last byte and wraps tail to 0")
		void shouldAppendExactlyFillingBufferToLastByte() {
			final int bodySize = 1000 - LEAD_DESCRIPTOR_BYTE_SIZE; // 984 bytes body, total = 1000
			final ByteBuffer body = createFilledBuffer(bodySize, (byte) 'X');
			final SessionLocation sessionA = diskRingBuffer.appendSession(1, bodySize);
			diskRingBuffer.append(body);
			diskRingBuffer.sessionWritten(sessionA, UUID.randomUUID(), OffsetDateTime.now(), 0, Set.of(), Set.of(), 0, 0);

			// tail should wrap exactly to 0
			assertEquals(0, diskRingBuffer.getRingBufferTail());

			// write another small session starting at position 0
			final int smallBodySize = 50;
			final ByteBuffer smallBody = createFilledBuffer(smallBodySize, (byte) 'Y');
			final SessionLocation sessionB = diskRingBuffer.appendSession(1, smallBodySize);
			diskRingBuffer.append(smallBody);
			diskRingBuffer.sessionWritten(sessionB, UUID.randomUUID(), OffsetDateTime.now(), 0, Set.of(), Set.of(), 0, 0);

			// tail should advance by the small session size
			assertEquals(smallBodySize + LEAD_DESCRIPTOR_BYTE_SIZE, diskRingBuffer.getRingBufferTail());
		}

	}

	/**
	 * Tests for session eviction logic in {@link DiskRingBuffer} when new writes overwrite
	 * existing sessions in the ring buffer.
	 */
	@Nested
	@DisplayName("Session eviction")
	class SessionEvictionTest {

		@Test
		@DisplayName("Should not evict adjacent session when writing new session after buffer wraps")
		void shouldNotEvictAdjacentSessionWhenWritingNewSessionAfterFullBuffer() throws Exception {
			// each session total size = 500 bytes (LEAD_DESCRIPTOR_BYTE_SIZE=16 + body=484)
			final int bodySize = 500 - LEAD_DESCRIPTOR_BYTE_SIZE;

			// Session A: occupies positions [0, 500), ringBufferTail becomes 500
			final ByteBuffer bodyA = createFilledBuffer(bodySize, (byte) 'A');
			final SessionLocation sessionA = diskRingBuffer.appendSession(1, bodySize);
			diskRingBuffer.append(bodyA);
			diskRingBuffer.sessionWritten(
				sessionA, UUID.randomUUID(), OffsetDateTime.now(),
				0, Set.of(), Set.of(), 0, 0
			);
			assertEquals(500, diskRingBuffer.getRingBufferTail());

			// Session B: occupies positions [500, 1000), ringBufferTail wraps to 0
			final ByteBuffer bodyB = createFilledBuffer(bodySize, (byte) 'B');
			final SessionLocation sessionB = diskRingBuffer.appendSession(1, bodySize);
			diskRingBuffer.append(bodyB);
			diskRingBuffer.sessionWritten(
				sessionB, UUID.randomUUID(), OffsetDateTime.now(),
				0, Set.of(), Set.of(), 0, 0
			);
			assertEquals(0, diskRingBuffer.getRingBufferTail());

			// verify 2 sessions exist before writing Session C
			final Deque<SessionLocation> locsBefore = getSessionLocations(diskRingBuffer);
			assertEquals(2, locsBefore.size(), "Should have 2 sessions (A and B) before writing C");

			// Session C: writes to [0, 500), should evict only Session A, NOT Session B
			final ByteBuffer bodyC = createFilledBuffer(bodySize, (byte) 'C');
			final SessionLocation sessionC = diskRingBuffer.appendSession(1, bodySize);
			diskRingBuffer.append(bodyC);
			diskRingBuffer.sessionWritten(
				sessionC, UUID.randomUUID(), OffsetDateTime.now(),
				0, Set.of(), Set.of(), 0, 0
			);

			// Session B should still be present; only Session A was overwritten
			final Deque<SessionLocation> locsAfter = getSessionLocations(diskRingBuffer);
			assertEquals(
				2, locsAfter.size(),
				"Session B should NOT be evicted because its area [500,999] does not overlap " +
					"with the write area [0,499]."
			);
			assertEquals(sessionB.sequenceOrder(), locsAfter.getFirst().sequenceOrder());
			assertEquals(sessionC.sequenceOrder(), locsAfter.getLast().sequenceOrder());
		}

		@Test
		@DisplayName("Should correctly evict only the overlapping session on exact-fit replacement")
		void shouldCorrectlyEvictOnlyOverlappingSessionOnExactFit() throws Exception {
			// Buffer size 300: 3 sessions of 100 bytes each
			final Path smallTempFile = Files.createTempFile("DiskRingBufferExactFitTest", ".tmp");
			final DiskRingBuffer smallBuffer = new DiskRingBuffer(smallTempFile, 300);
			try {
				final int bodySize = 100 - LEAD_DESCRIPTOR_BYTE_SIZE; // 84 bytes body per session

				// Session A: [0, 100)
				final ByteBuffer bufA = createFilledBuffer(bodySize, (byte) 'A');
				final SessionLocation sessionA = smallBuffer.appendSession(1, bodySize);
				smallBuffer.append(bufA);
				smallBuffer.sessionWritten(
					sessionA, UUID.randomUUID(), OffsetDateTime.now(),
					0, Set.of(), Set.of(), 0, 0
				);

				// Session B: [100, 200)
				final ByteBuffer bufB = createFilledBuffer(bodySize, (byte) 'B');
				final SessionLocation sessionB = smallBuffer.appendSession(1, bodySize);
				smallBuffer.append(bufB);
				smallBuffer.sessionWritten(
					sessionB, UUID.randomUUID(), OffsetDateTime.now(),
					0, Set.of(), Set.of(), 0, 0
				);

				// Session C: [200, 300)
				final ByteBuffer bufC = createFilledBuffer(bodySize, (byte) 'C');
				final SessionLocation sessionC = smallBuffer.appendSession(1, bodySize);
				smallBuffer.append(bufC);
				smallBuffer.sessionWritten(
					sessionC, UUID.randomUUID(), OffsetDateTime.now(),
					0, Set.of(), Set.of(), 0, 0
				);
				assertEquals(0, smallBuffer.getRingBufferTail(), "Tail should wrap to 0");

				final Deque<SessionLocation> locsBefore = getSessionLocations(smallBuffer);
				assertEquals(3, locsBefore.size(), "Should have 3 sessions before writing D");

				// Session D: writes to [0, 100), should evict only Session A
				final ByteBuffer bufD = createFilledBuffer(bodySize, (byte) 'D');
				final SessionLocation sessionD = smallBuffer.appendSession(1, bodySize);
				smallBuffer.append(bufD);
				smallBuffer.sessionWritten(
					sessionD, UUID.randomUUID(), OffsetDateTime.now(),
					0, Set.of(), Set.of(), 0, 0
				);

				// Only Session A should be evicted; B, C, and D should remain
				final Deque<SessionLocation> locsAfter = getSessionLocations(smallBuffer);
				assertEquals(
					3, locsAfter.size(),
					"Only Session A should be evicted. Sessions B, C, and D should remain."
				);

				final SessionLocation[] remaining = locsAfter.toArray(new SessionLocation[0]);
				assertEquals(sessionB.sequenceOrder(), remaining[0].sequenceOrder());
				assertEquals(sessionC.sequenceOrder(), remaining[1].sequenceOrder());
				assertEquals(sessionD.sequenceOrder(), remaining[2].sequenceOrder());
			} finally {
				smallBuffer.close(FileUtils::deleteFileIfExists);
				Files.deleteIfExists(smallTempFile);
			}
		}

		@Test
		@DisplayName("Should evict session when write wraps around buffer boundary overwriting last positions")
		void shouldEvictSessionWhenWriteWrapsAroundBufferBoundary() throws Exception {
			final Path smallTempFile = Files.createTempFile("DiskRingBufferWrapTest", ".tmp");
			final DiskRingBuffer smallBuffer = new DiskRingBuffer(smallTempFile, 200);
			try {
				// Session A: total size = 184 bytes (16 header + 168 body)
				// Occupies positions [0, 184)
				final int bodyA = 168;
				final ByteBuffer bufA = createFilledBuffer(bodyA, (byte) 'A');
				final SessionLocation sessionA = smallBuffer.appendSession(1, bodyA);
				smallBuffer.append(bufA);
				smallBuffer.sessionWritten(
					sessionA, UUID.randomUUID(), OffsetDateTime.now(),
					0, Set.of(), Set.of(), 0, 0
				);
				assertEquals(184, smallBuffer.getRingBufferTail());

				// Session B: total size = 16 bytes (16 header + 0 body)
				// Occupies positions [184, 200), ringBufferTail wraps to 0
				final SessionLocation sessionB = smallBuffer.appendSession(0, 0);
				smallBuffer.sessionWritten(
					sessionB, UUID.randomUUID(), OffsetDateTime.now(),
					0, Set.of(), Set.of(), 0, 0
				);
				assertEquals(0, smallBuffer.getRingBufferTail());

				final Deque<SessionLocation> locsBefore = getSessionLocations(smallBuffer);
				assertEquals(2, locsBefore.size(), "Should have Sessions A and B before writing C");

				// Session C: total size = 200 bytes (16 header + 184 body) - fills entire buffer
				// This should overwrite everything, evicting both A and B
				final int bodyC = 200 - LEAD_DESCRIPTOR_BYTE_SIZE;
				final ByteBuffer bufC = createFilledBuffer(bodyC, (byte) 'C');
				final SessionLocation sessionC = smallBuffer.appendSession(1, bodyC);
				smallBuffer.append(bufC);
				smallBuffer.sessionWritten(
					sessionC, UUID.randomUUID(), OffsetDateTime.now(),
					0, Set.of(), Set.of(), 0, 0
				);

				// Both Sessions A and B should be evicted, only Session C remains
				final Deque<SessionLocation> locsAfter = getSessionLocations(smallBuffer);
				assertEquals(
					1, locsAfter.size(),
					"Both Sessions A and B should be evicted when Session C overwrites the entire buffer."
				);
				assertEquals(sessionC.sequenceOrder(), locsAfter.getFirst().sequenceOrder());
			} finally {
				smallBuffer.close(FileUtils::deleteFileIfExists);
				Files.deleteIfExists(smallTempFile);
			}
		}

		@Test
		@DisplayName("Should evict multiple sessions when a single large write overlaps them")
		void shouldEvictMultipleSessionsWhenLargeWriteOverlaps() throws Exception {
			final Path smallTempFile = Files.createTempFile("DiskRingBufferMultiEvictTest", ".tmp");
			final DiskRingBuffer smallBuffer = new DiskRingBuffer(smallTempFile, 500);
			try {
				final int bodySize = 100 - LEAD_DESCRIPTOR_BYTE_SIZE;

				// Write 5 sessions of 100 bytes each: A[0,100), B[100,200), C[200,300), D[300,400), E[400,500)
				final SessionLocation[] sessions = new SessionLocation[5];
				for (int i = 0; i < 5; i++) {
					final ByteBuffer buf = createFilledBuffer(bodySize, (byte) ('A' + i));
					sessions[i] = smallBuffer.appendSession(1, bodySize);
					smallBuffer.append(buf);
					smallBuffer.sessionWritten(
						sessions[i], UUID.randomUUID(), OffsetDateTime.now(),
						0, Set.of(), Set.of(), 0, 0
					);
				}
				assertEquals(0, smallBuffer.getRingBufferTail(), "Tail should wrap to 0 after 5x100=500");

				// Write session F of 250 bytes: overwrites [0,250), should evict A, B, and C
				final int largeBodySize = 250 - LEAD_DESCRIPTOR_BYTE_SIZE;
				final ByteBuffer bufF = createFilledBuffer(largeBodySize, (byte) 'F');
				final SessionLocation sessionF = smallBuffer.appendSession(1, largeBodySize);
				smallBuffer.append(bufF);
				smallBuffer.sessionWritten(
					sessionF, UUID.randomUUID(), OffsetDateTime.now(),
					0, Set.of(), Set.of(), 0, 0
				);

				final Deque<SessionLocation> locsAfter = getSessionLocations(smallBuffer);
				assertEquals(
					3, locsAfter.size(),
					"Sessions A, B, C should be evicted. D, E, F should remain."
				);

				final SessionLocation[] remaining = locsAfter.toArray(new SessionLocation[0]);
				assertEquals(sessions[3].sequenceOrder(), remaining[0].sequenceOrder(), "First remaining should be D");
				assertEquals(sessions[4].sequenceOrder(), remaining[1].sequenceOrder(), "Second remaining should be E");
				assertEquals(sessionF.sequenceOrder(), remaining[2].sequenceOrder(), "Third remaining should be F");

				// head should point to the start of session D
				assertEquals(300, smallBuffer.getRingBufferHead(), "Head should be at start of session D");
			} finally {
				smallBuffer.close(FileUtils::deleteFileIfExists);
				Files.deleteIfExists(smallTempFile);
			}
		}

		@Test
		@DisplayName("Should update ring buffer head after session eviction")
		void shouldUpdateRingBufferHeadAfterEviction() throws Exception {
			final Path smallTempFile = Files.createTempFile("DiskRingBufferHeadTest", ".tmp");
			final DiskRingBuffer smallBuffer = new DiskRingBuffer(smallTempFile, 300);
			try {
				final int bodySize = 100 - LEAD_DESCRIPTOR_BYTE_SIZE;

				// A[0,100), B[100,200), C[200,300) -- tail wraps to 0
				writeSession(smallBuffer, bodySize, (byte) 'A');
				writeSession(smallBuffer, bodySize, (byte) 'B');
				writeSession(smallBuffer, bodySize, (byte) 'C');
				assertEquals(0, smallBuffer.getRingBufferTail());
				assertEquals(0, smallBuffer.getRingBufferHead(), "Head should be at start of A");

				// Write D[0,100) -- evicts A, head should move to B's start
				writeSession(smallBuffer, bodySize, (byte) 'D');
				assertEquals(100, smallBuffer.getRingBufferHead(), "Head should move to start of B after A is evicted");

				// Write E[100,200) -- evicts B, head should move to C's start
				writeSession(smallBuffer, bodySize, (byte) 'E');
				assertEquals(200, smallBuffer.getRingBufferHead(), "Head should move to start of C after B is evicted");
			} finally {
				smallBuffer.close(FileUtils::deleteFileIfExists);
				Files.deleteIfExists(smallTempFile);
			}
		}

		@Test
		@DisplayName("Should evict all sessions when buffer is completely overwritten")
		void shouldEvictAllSessionsWhenBufferIsCompletelyOverwritten() throws Exception {
			final Path smallTempFile = Files.createTempFile("DiskRingBufferFullOverwriteTest", ".tmp");
			final DiskRingBuffer smallBuffer = new DiskRingBuffer(smallTempFile, 200);
			try {
				final int bodySize = 100 - LEAD_DESCRIPTOR_BYTE_SIZE;

				// Write A[0,100) and B[100,200) -- tail wraps to 0
				writeSession(smallBuffer, bodySize, (byte) 'A');
				writeSession(smallBuffer, bodySize, (byte) 'B');
				assertEquals(0, smallBuffer.getRingBufferTail());

				final Deque<SessionLocation> locsBefore = getSessionLocations(smallBuffer);
				assertEquals(2, locsBefore.size());

				// Write C that fills entire 200 bytes -- should evict both A and B
				final int fullBodySize = 200 - LEAD_DESCRIPTOR_BYTE_SIZE;
				final ByteBuffer bufC = createFilledBuffer(fullBodySize, (byte) 'C');
				final SessionLocation sessionC = smallBuffer.appendSession(1, fullBodySize);
				smallBuffer.append(bufC);
				smallBuffer.sessionWritten(
					sessionC, UUID.randomUUID(), OffsetDateTime.now(),
					0, Set.of(), Set.of(), 0, 0
				);

				final Deque<SessionLocation> locsAfter = getSessionLocations(smallBuffer);
				assertEquals(1, locsAfter.size(), "Only session C should remain");
				assertEquals(sessionC.sequenceOrder(), locsAfter.getFirst().sequenceOrder());
			} finally {
				smallBuffer.close(FileUtils::deleteFileIfExists);
				Files.deleteIfExists(smallTempFile);
			}
		}

	}

	/**
	 * Tests for the private `isWasted()` method that determines whether a record's position
	 * overlaps with the erased area in the ring buffer.
	 */
	@Nested
	@DisplayName("isWasted() overlap detection")
	class IsWastedTest {

		@Test
		@DisplayName("Should not detect adjacent (non-overlapping) sessions as wasted")
		void shouldNotDetectAdjacentSessionsAsWasted() throws Exception {
			// Erased area covers bytes [0, 99] inclusive — single segment, no wrap
			// Record starting at position 100, length 50: occupies bytes [100, 149]
			final SessionFileLocation adjacentRecord = new SessionFileLocation(100, 50);
			final boolean result = invokeIsWasted(
				diskRingBuffer,
				0L, 99L, false, 0L, -1L,
				adjacentRecord
			);

			// erased [0,99] vs record [100,149] - no overlap
			assertFalse(
				result,
				"A session starting exactly after the erased area should NOT be considered wasted. " +
					"Erased bytes [0,99] do not overlap with record bytes [100,149]."
			);

			// Also verify that a truly overlapping record IS detected
			final SessionFileLocation overlappingRecord = new SessionFileLocation(99, 50);
			final boolean overlapResult = invokeIsWasted(
				diskRingBuffer,
				0L, 99L, false, 0L, -1L,
				overlappingRecord
			);
			assertTrue(
				overlapResult,
				"A session overlapping the erased area at position 99 should be detected as wasted."
			);
		}

		@Test
		@DisplayName("Should detect waste when record wraps around buffer boundary")
		void shouldDetectWasteWhenRecordWrapsAroundBuffer() throws Exception {
			// Record wraps: starts at 950, length 100 -> occupies [950,999] and [0,49]
			final SessionFileLocation wrappedRecord = new SessionFileLocation(950, 100);

			// Erase area [0,50] overlaps with [0,49] — single segment
			assertTrue(
				invokeIsWasted(
					diskRingBuffer,
					0L, 50L, false, 0L, -1L,
					wrappedRecord
				),
				"Erase area [0,50] should overlap with wrapped record occupying [950,999]+[0,49]."
			);

			// Erase area [51,100] does NOT overlap — single segment
			assertFalse(
				invokeIsWasted(
					diskRingBuffer,
					51L, 100L, false, 0L, -1L,
					wrappedRecord
				),
				"Erase area [51,100] should NOT overlap with wrapped record occupying [950,999]+[0,49]."
			);
		}

		@Test
		@DisplayName("Should detect waste when erase area wraps around buffer boundary")
		void shouldDetectWasteWhenEraseAreaWrapsAroundBuffer() throws Exception {
			// Erase area wraps: segment 1 = [950, 999], segment 2 = [0, 49]
			// Record at (960, 20) -> [960,979], overlaps with erase [950,999]
			final SessionFileLocation overlappingRecord = new SessionFileLocation(960, 20);
			assertTrue(
				invokeIsWasted(
					diskRingBuffer,
					950L, 999L, true, 0L, 49L,
					overlappingRecord
				),
				"Record [960,979] should overlap with erase area [950,999]."
			);

			// Record at (500, 20) -> [500,519], no overlap
			final SessionFileLocation nonOverlappingRecord = new SessionFileLocation(500, 20);
			assertFalse(
				invokeIsWasted(
					diskRingBuffer,
					950L, 999L, true, 0L, 49L,
					nonOverlappingRecord
				),
				"Record [500,519] should NOT overlap with erase area [950,999]+[0,49]."
			);
		}

		@Test
		@DisplayName("Should not detect waste when both erase area and record wrap but don't overlap")
		void shouldNotDetectWasteWhenBothWrapButDontOverlap() throws Exception {
			// Erase area wraps: segment 1 = [800, 999], segment 2 = [0, 49]
			// Record at (50, 100) -> [50,149], no overlap with [800,999] or [0,49]
			final SessionFileLocation disjointRecord = new SessionFileLocation(50, 100);
			assertFalse(
				invokeIsWasted(
					diskRingBuffer,
					800L, 999L, true, 0L, 49L,
					disjointRecord
				),
				"Record [50,149] should NOT overlap with erase area [800,999]+[0,49]."
			);
		}

	}

	/**
	 * Tests for the private `isSessionLocationStillInValidArea()` method that checks
	 * whether a session's file location is still within the ring buffer's valid data range.
	 */
	@Nested
	@DisplayName("isSessionLocationStillInValidArea() validation")
	class ValidAreaTest {

		@Test
		@DisplayName("Should consider wrapped session still in valid area")
		void shouldConsiderWrappedSessionStillInValidArea() throws Exception {
			// Set up a wrapped ring buffer state: head=800, tail=200
			// Valid data spans: [800, 1000) and [0, 200)
			setRingBufferHead(diskRingBuffer, 800L);
			setRingBufferTail(diskRingBuffer, 200L);

			// Session that wraps: starts at 900, length 200
			// Actual bytes: [900, 999] and [0, 99]
			// endPosition() = 900 + 200 = 1100 (raw, exceeds buffer size 1000)
			final SessionFileLocation wrappedSession = new SessionFileLocation(900, 200);
			final boolean result = invokeIsSessionLocationStillInValidArea(diskRingBuffer, wrappedSession);

			assertTrue(
				result,
				"A session wrapping around the buffer (start=900, end=1100 raw / 100 wrapped) " +
					"is within valid area [800..1000, 0..200] and should be reported as valid."
			);
		}

		@Test
		@DisplayName("Should consider session valid in non-wrapped buffer")
		void shouldConsiderSessionValidInNonWrappedBuffer() throws Exception {
			// head=100, tail=500 -> valid area: [100, 500]
			setRingBufferHead(diskRingBuffer, 100L);
			setRingBufferTail(diskRingBuffer, 500L);

			// Session at (200, 100) -> [200, 300), within [100, 500]
			final SessionFileLocation validSession = new SessionFileLocation(200, 100);
			assertTrue(
				invokeIsSessionLocationStillInValidArea(diskRingBuffer, validSession),
				"Session [200,299] should be valid within area [100,500]."
			);
		}

		@Test
		@DisplayName("Should reject session outside valid area in non-wrapped buffer")
		void shouldRejectSessionOutsideValidAreaInNonWrappedBuffer() throws Exception {
			// head=100, tail=500 -> valid area: [100, 500]
			setRingBufferHead(diskRingBuffer, 100L);
			setRingBufferTail(diskRingBuffer, 500L);

			// Session at (600, 50) -> [600, 650), outside [100, 500]
			final SessionFileLocation afterTail = new SessionFileLocation(600, 50);
			assertFalse(
				invokeIsSessionLocationStillInValidArea(diskRingBuffer, afterTail),
				"Session [600,649] should NOT be valid - it is after tail=500."
			);

			// Session at (0, 50) -> [0, 50), before head
			final SessionFileLocation beforeHead = new SessionFileLocation(0, 50);
			assertFalse(
				invokeIsSessionLocationStillInValidArea(diskRingBuffer, beforeHead),
				"Session [0,49] should NOT be valid - it is before head=100."
			);
		}

		@Test
		@DisplayName("Should reject session in the gap of a wrapped buffer")
		void shouldRejectSessionOutsideValidAreaInWrappedBuffer() throws Exception {
			// head=800, tail=200 -> valid area: [800, 1000) and [0, 200)
			// gap: [200, 800)
			setRingBufferHead(diskRingBuffer, 800L);
			setRingBufferTail(diskRingBuffer, 200L);

			// Session at (400, 50) -> [400, 450), in the gap
			final SessionFileLocation gapSession = new SessionFileLocation(400, 50);
			assertFalse(
				invokeIsSessionLocationStillInValidArea(diskRingBuffer, gapSession),
				"Session [400,449] should NOT be valid - it is in the gap [200,800)."
			);
		}

		@Test
		@DisplayName("Should handle session at exact head and tail boundaries")
		void shouldHandleSessionAtExactBoundaries() throws Exception {
			// head=100, tail=500
			setRingBufferHead(diskRingBuffer, 100L);
			setRingBufferTail(diskRingBuffer, 500L);

			// Session starting at head, ending at tail: (100, 400) -> endPosition=500
			final SessionFileLocation boundarySession = new SessionFileLocation(100, 400);
			assertTrue(
				invokeIsSessionLocationStillInValidArea(diskRingBuffer, boundarySession),
				"Session [100,499] at exact head/tail boundaries should be valid."
			);
		}

	}

	/**
	 * Tests for ring buffer internal state consistency: head/tail tracking,
	 * sequence ordering, and session locations deque management.
	 */
	@Nested
	@DisplayName("Ring buffer state tracking")
	class RingBufferStateTest {

		@Test
		@DisplayName("Should track monotonically increasing sequence order across appends")
		void shouldTrackMonotonicallyIncreasingSequenceOrder() {
			final int bodySize = 50;
			long previousSequenceOrder = 0;
			for (int i = 0; i < 10; i++) {
				final ByteBuffer body = createFilledBuffer(bodySize, (byte) ('0' + i));
				final SessionLocation sessionLocation = diskRingBuffer.appendSession(1, bodySize);
				diskRingBuffer.append(body);
				diskRingBuffer.sessionWritten(
					sessionLocation, UUID.randomUUID(), OffsetDateTime.now(),
					0, Set.of(), Set.of(), 0, 0
				);

				assertTrue(
					sessionLocation.sequenceOrder() > previousSequenceOrder,
					"Sequence order should be monotonically increasing"
				);
				assertEquals(i + 1, sessionLocation.sequenceOrder());
				previousSequenceOrder = sessionLocation.sequenceOrder();
			}
		}

		@Test
		@DisplayName("Should maintain ordered session locations deque after multiple wraps")
		void shouldMaintainSessionLocationsDequeOrderAfterMultipleWraps() throws Exception {
			final Path smallTempFile = Files.createTempFile("DiskRingBufferDequeOrderTest", ".tmp");
			final DiskRingBuffer smallBuffer = new DiskRingBuffer(smallTempFile, 300);
			try {
				final int bodySize = 100 - LEAD_DESCRIPTOR_BYTE_SIZE;

				// Write 15 sessions of 100 bytes each (5 full buffer rotations)
				for (int i = 0; i < 15; i++) {
					final ByteBuffer body = createFilledBuffer(bodySize, (byte) ('A' + (i % 26)));
					final SessionLocation session = smallBuffer.appendSession(1, bodySize);
					smallBuffer.append(body);
					smallBuffer.sessionWritten(
						session, UUID.randomUUID(), OffsetDateTime.now(),
						0, Set.of(), Set.of(), 0, 0
					);

					final Deque<SessionLocation> locs = getSessionLocations(smallBuffer);

					// buffer fits exactly 3 sessions of 100 bytes
					assertTrue(
						locs.size() <= 3,
						"Deque should never have more than 3 entries (buffer=300, session=100). " +
							"Actual: " + locs.size() + " after write #" + (i + 1)
					);

					// verify ordering: each sequence order should be greater than previous
					long prevOrder = 0;
					for (SessionLocation loc : locs) {
						assertTrue(
							loc.sequenceOrder() > prevOrder,
							"Deque should be ordered by ascending sequence order"
						);
						prevOrder = loc.sequenceOrder();
					}
				}
			} finally {
				smallBuffer.close(FileUtils::deleteFileIfExists);
				Files.deleteIfExists(smallTempFile);
			}
		}

		@Test
		@DisplayName("Should track head and tail correctly through multiple wraps")
		void shouldTrackHeadAndTailCorrectlyThroughMultipleWraps() throws Exception {
			final Path smallTempFile = Files.createTempFile("DiskRingBufferHeadTailTest", ".tmp");
			final DiskRingBuffer smallBuffer = new DiskRingBuffer(smallTempFile, 300);
			try {
				final int sessionTotalSize = 100;
				final int bodySize = sessionTotalSize - LEAD_DESCRIPTOR_BYTE_SIZE;

				for (int i = 0; i < 10; i++) {
					writeSession(smallBuffer, bodySize, (byte) ('A' + (i % 26)));

					// tail should be at (100 * (i+1)) % 300
					final long expectedTail = (sessionTotalSize * (long) (i + 1)) % 300;
					assertEquals(
						expectedTail, smallBuffer.getRingBufferTail(),
						"Tail should be at " + expectedTail + " after write #" + (i + 1)
					);

					// head should be at the start of the oldest surviving session
					final Deque<SessionLocation> locs = getSessionLocations(smallBuffer);
					assertFalse(locs.isEmpty());
					assertEquals(
						locs.peekFirst().location().startingPosition(),
						smallBuffer.getRingBufferHead(),
						"Head should point to the oldest session's start position"
					);
				}
			} finally {
				smallBuffer.close(FileUtils::deleteFileIfExists);
				Files.deleteIfExists(smallTempFile);
			}
		}

	}

	/**
	 * Tests for {@link DiskRingBuffer#close(java.util.function.Consumer)} resource cleanup.
	 */
	@Nested
	@DisplayName("Close and resource cleanup")
	class CloseTest {

		@Test
		@DisplayName("Should invoke file clean logic and release internal resources on close")
		void shouldInvokeFileCleanLogicAndReleaseResources() throws Exception {
			// write a session first
			final int bodySize = 50;
			final ByteBuffer body = createFilledBuffer(bodySize, (byte) 'Z');
			final SessionLocation session = diskRingBuffer.appendSession(1, bodySize);
			diskRingBuffer.append(body);
			diskRingBuffer.sessionWritten(
				session, UUID.randomUUID(), OffsetDateTime.now(),
				0, Set.of(), Set.of(), 0, 0
			);

			// verify session exists
			final Deque<SessionLocation> locsBefore = getSessionLocations(diskRingBuffer);
			assertEquals(1, locsBefore.size());

			// close with tracking consumer
			final AtomicReference<Path> cleanedPath = new AtomicReference<>();
			diskRingBuffer.close(cleanedPath::set);

			// verify file clean logic received the path
			assertNotNull(cleanedPath.get(), "File clean logic should have been invoked");
			assertEquals(tempFile, cleanedPath.get());

			// verify session locations were cleared
			final Deque<SessionLocation> locsAfter = getSessionLocations(diskRingBuffer);
			assertTrue(locsAfter.isEmpty(), "Session locations should be cleared after close");

			// prevent double-close in @AfterEach by creating a new dummy buffer
			diskRingBuffer = new DiskRingBuffer(
				Files.createTempFile("DiskRingBufferCloseTestReplacement", ".tmp"),
				100
			);
		}

	}

	// ---- Helper methods ----

	/**
	 * Creates a ByteBuffer of the specified size filled with the given byte value.
	 */
	private static ByteBuffer createFilledBuffer(int size, byte fillByte) {
		final ByteBuffer buffer = ByteBuffer.allocate(size);
		for (int i = 0; i < size; i++) {
			buffer.put(fillByte);
		}
		buffer.flip();
		return buffer;
	}

	/**
	 * Writes a complete session (appendSession + append + sessionWritten) and returns its location.
	 */
	private static SessionLocation writeSession(DiskRingBuffer buffer, int bodySize, byte fillByte) {
		final ByteBuffer body = createFilledBuffer(bodySize, fillByte);
		final SessionLocation sessionLocation = buffer.appendSession(1, bodySize);
		buffer.append(body);
		buffer.sessionWritten(
			sessionLocation, UUID.randomUUID(), OffsetDateTime.now(),
			0, Set.of(), Set.of(), 0, 0
		);
		return sessionLocation;
	}

	/**
	 * Accesses the private `sessionLocations` field of a DiskRingBuffer via reflection.
	 */
	@SuppressWarnings("unchecked")
	private static Deque<SessionLocation> getSessionLocations(DiskRingBuffer buffer) throws Exception {
		final Field field = DiskRingBuffer.class.getDeclaredField("sessionLocations");
		field.setAccessible(true);
		return (Deque<SessionLocation>) field.get(buffer);
	}

	/**
	 * Invokes the private `isWasted` method via reflection.
	 *
	 * @param buffer                 the DiskRingBuffer instance
	 * @param erased1From            start of the first erased segment (inclusive)
	 * @param erased1To              end of the first erased segment (inclusive)
	 * @param hasSecondErasedSegment true if a second erased segment exists (wrap-around)
	 * @param erased2From            start of the second erased segment (inclusive)
	 * @param erased2To              end of the second erased segment (inclusive)
	 * @param recordPosition         the record position to check
	 */
	private static boolean invokeIsWasted(
		DiskRingBuffer buffer,
		long erased1From, long erased1To,
		boolean hasSecondErasedSegment, long erased2From, long erased2To,
		SessionFileLocation recordPosition
	) throws Exception {
		final Method method = DiskRingBuffer.class.getDeclaredMethod(
			"isWasted",
			long.class, long.class, boolean.class, long.class, long.class,
			SessionFileLocation.class
		);
		method.setAccessible(true);
		return (boolean) method.invoke(
			buffer,
			erased1From, erased1To, hasSecondErasedSegment, erased2From, erased2To,
			recordPosition
		);
	}

	/**
	 * Invokes the private `isSessionLocationStillInValidArea` method via reflection.
	 */
	private static boolean invokeIsSessionLocationStillInValidArea(
		DiskRingBuffer buffer,
		SessionFileLocation fileLocation
	) throws Exception {
		final Method method = DiskRingBuffer.class.getDeclaredMethod(
			"isSessionLocationStillInValidArea", SessionFileLocation.class
		);
		method.setAccessible(true);
		return (boolean) method.invoke(buffer, fileLocation);
	}

	/**
	 * Sets the private `ringBufferHead` field via reflection.
	 */
	private static void setRingBufferHead(DiskRingBuffer buffer, long value) throws Exception {
		final Field field = DiskRingBuffer.class.getDeclaredField("ringBufferHead");
		field.setAccessible(true);
		field.setLong(buffer, value);
	}

	/**
	 * Sets the private `ringBufferTail` field via reflection.
	 */
	private static void setRingBufferTail(DiskRingBuffer buffer, long value) throws Exception {
		final Field field = DiskRingBuffer.class.getDeclaredField("ringBufferTail");
		field.setAccessible(true);
		field.setLong(buffer, value);
	}

}
