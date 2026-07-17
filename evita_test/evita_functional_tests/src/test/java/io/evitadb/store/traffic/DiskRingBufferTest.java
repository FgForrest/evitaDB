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

import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.spi.store.catalog.trafficRecorder.model.SessionFileLocation;
import io.evitadb.spi.store.catalog.trafficRecorder.model.SessionLocation;
import io.evitadb.store.shared.model.FileLocation;
import io.evitadb.store.traffic.OffHeapTrafficRecorder.MemoryNotAvailableException;
import io.evitadb.utils.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static io.evitadb.store.traffic.DiskRingBuffer.LEAD_DESCRIPTOR_BYTE_SIZE;
import static io.evitadb.store.traffic.DiskRingBuffer.segmentsOverlap;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.TRAFFIC_ENGINE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link DiskRingBuffer} functionality.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@DisplayName("DiskRingBuffer")
@Tag(STORAGE)
@Tag(TRAFFIC_ENGINE)
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
			final SessionLocation sessionLocation = DiskRingBufferTest.this.diskRingBuffer.appendSession(theFilledSize, theFilledSize);
			DiskRingBufferTest.this.diskRingBuffer.append(buffer);
			DiskRingBufferTest.this.diskRingBuffer.sessionWritten(sessionLocation, UUID.randomUUID(), OffsetDateTime.now(), 0, Set.of(), Set.of(), 0, 0);
			assertEquals(theFilledSize + LEAD_DESCRIPTOR_BYTE_SIZE, DiskRingBufferTest.this.diskRingBuffer.getRingBufferTail());

			final int totalSpace = (int) DiskRingBufferTest.this.tempFile.toFile().length();
			assertEquals(1000, totalSpace);

			// verify the content on the disk
			final byte[] allContent = Files.readAllBytes(DiskRingBufferTest.this.tempFile);
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
					DiskRingBufferTest.this.diskRingBuffer.appendSession(theFilledSize, theFilledSize);
					DiskRingBufferTest.this.diskRingBuffer.append(buffer);
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
				final SessionLocation sessionLocation = DiskRingBufferTest.this.diskRingBuffer.appendSession(0, length);
				DiskRingBufferTest.this.diskRingBuffer.append(buffer.slice(index, length));
				DiskRingBufferTest.this.diskRingBuffer.sessionWritten(sessionLocation, UUID.randomUUID(), OffsetDateTime.now(), 0, Set.of(), Set.of(), 0, 0);

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

			assertEquals(500 + 5 * LEAD_DESCRIPTOR_BYTE_SIZE, DiskRingBufferTest.this.diskRingBuffer.getRingBufferTail());

			final long totalSpace = DiskRingBufferTest.this.tempFile.toFile().length();
			assertEquals(1000, totalSpace);

			// verify the content on the disk
			final byte[] fileContent = Files.readAllBytes(DiskRingBufferTest.this.tempFile);
			for (int i = 0; i < DiskRingBufferTest.this.diskRingBuffer.getRingBufferTail(); i++) {
				final int index = (int) (theFilledSize - (theFilledSize % totalSpace)) + i;
				final byte expectedByte = bufferWithDescriptors.get(index);
				assertEquals(expectedByte, fileContent[i]);
			}
			final long theStart = DiskRingBufferTest.this.diskRingBuffer.getRingBufferTail();
			for (int i = (int) theStart; i < totalSpace; i++) {
				final byte expectedByte = bufferWithDescriptors.get(i);
				assertEquals(expectedByte, fileContent[i]);
			}
		}

		@Test
		@DisplayName("Should append session with zero records and zero body size")
		void shouldAppendSessionWithZeroRecords() throws IOException {
			final SessionLocation sessionLocation = DiskRingBufferTest.this.diskRingBuffer.appendSession(0, 0);
			DiskRingBufferTest.this.diskRingBuffer.sessionWritten(sessionLocation, UUID.randomUUID(), OffsetDateTime.now(), 0, Set.of(), Set.of(), 0, 0);

			// only descriptor was written (16 bytes)
			assertEquals(LEAD_DESCRIPTOR_BYTE_SIZE, DiskRingBufferTest.this.diskRingBuffer.getRingBufferTail());
			assertEquals(1, sessionLocation.sequenceOrder());
			assertEquals(0, sessionLocation.sessionRecordsCount());
			assertEquals(LEAD_DESCRIPTOR_BYTE_SIZE, sessionLocation.location().recordLength());

			// verify descriptor content on disk
			final byte[] allContent = Files.readAllBytes(DiskRingBufferTest.this.tempFile);
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
				() -> DiskRingBufferTest.this.diskRingBuffer.appendSession(1, 985)
			);
		}

		@Test
		@DisplayName("Should handle append that exactly fills buffer to last byte and wraps tail to 0")
		void shouldAppendExactlyFillingBufferToLastByte() {
			final int bodySize = 1000 - LEAD_DESCRIPTOR_BYTE_SIZE; // 984 bytes body, total = 1000
			final ByteBuffer body = createFilledBuffer(bodySize, (byte) 'X');
			final SessionLocation sessionA = DiskRingBufferTest.this.diskRingBuffer.appendSession(1, bodySize);
			DiskRingBufferTest.this.diskRingBuffer.append(body);
			DiskRingBufferTest.this.diskRingBuffer.sessionWritten(sessionA, UUID.randomUUID(), OffsetDateTime.now(), 0, Set.of(), Set.of(), 0, 0);

			// tail should wrap exactly to 0
			assertEquals(0, DiskRingBufferTest.this.diskRingBuffer.getRingBufferTail());

			// write another small session starting at position 0
			final int smallBodySize = 50;
			final ByteBuffer smallBody = createFilledBuffer(smallBodySize, (byte) 'Y');
			final SessionLocation sessionB = DiskRingBufferTest.this.diskRingBuffer.appendSession(1, smallBodySize);
			DiskRingBufferTest.this.diskRingBuffer.append(smallBody);
			DiskRingBufferTest.this.diskRingBuffer.sessionWritten(sessionB, UUID.randomUUID(), OffsetDateTime.now(), 0, Set.of(), Set.of(), 0, 0);

			// tail should advance by the small session size
			assertEquals(smallBodySize + LEAD_DESCRIPTOR_BYTE_SIZE, DiskRingBufferTest.this.diskRingBuffer.getRingBufferTail());
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
			final SessionLocation sessionA = DiskRingBufferTest.this.diskRingBuffer.appendSession(1, bodySize);
			DiskRingBufferTest.this.diskRingBuffer.append(bodyA);
			DiskRingBufferTest.this.diskRingBuffer.sessionWritten(
				sessionA, UUID.randomUUID(), OffsetDateTime.now(),
				0, Set.of(), Set.of(), 0, 0
			);
			assertEquals(500, DiskRingBufferTest.this.diskRingBuffer.getRingBufferTail());

			// Session B: occupies positions [500, 1000), ringBufferTail wraps to 0
			final ByteBuffer bodyB = createFilledBuffer(bodySize, (byte) 'B');
			final SessionLocation sessionB = DiskRingBufferTest.this.diskRingBuffer.appendSession(1, bodySize);
			DiskRingBufferTest.this.diskRingBuffer.append(bodyB);
			DiskRingBufferTest.this.diskRingBuffer.sessionWritten(
				sessionB, UUID.randomUUID(), OffsetDateTime.now(),
				0, Set.of(), Set.of(), 0, 0
			);
			assertEquals(0, DiskRingBufferTest.this.diskRingBuffer.getRingBufferTail());

			// verify 2 sessions exist before writing Session C
			final Deque<SessionLocation> locsBefore = getSessionLocations(DiskRingBufferTest.this.diskRingBuffer);
			assertEquals(2, locsBefore.size(), "Should have 2 sessions (A and B) before writing C");

			// Session C: writes to [0, 500), should evict only Session A, NOT Session B
			final ByteBuffer bodyC = createFilledBuffer(bodySize, (byte) 'C');
			final SessionLocation sessionC = DiskRingBufferTest.this.diskRingBuffer.appendSession(1, bodySize);
			DiskRingBufferTest.this.diskRingBuffer.append(bodyC);
			DiskRingBufferTest.this.diskRingBuffer.sessionWritten(
				sessionC, UUID.randomUUID(), OffsetDateTime.now(),
				0, Set.of(), Set.of(), 0, 0
			);

			// Session B should still be present; only Session A was overwritten
			final Deque<SessionLocation> locsAfter = getSessionLocations(DiskRingBufferTest.this.diskRingBuffer);
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
				DiskRingBufferTest.this.diskRingBuffer,
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
				DiskRingBufferTest.this.diskRingBuffer,
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
					DiskRingBufferTest.this.diskRingBuffer,
					0L, 50L, false, 0L, -1L,
					wrappedRecord
				),
				"Erase area [0,50] should overlap with wrapped record occupying [950,999]+[0,49]."
			);

			// Erase area [51,100] does NOT overlap — single segment
			assertFalse(
				invokeIsWasted(
					DiskRingBufferTest.this.diskRingBuffer,
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
					DiskRingBufferTest.this.diskRingBuffer,
					950L, 999L, true, 0L, 49L,
					overlappingRecord
				),
				"Record [960,979] should overlap with erase area [950,999]."
			);

			// Record at (500, 20) -> [500,519], no overlap
			final SessionFileLocation nonOverlappingRecord = new SessionFileLocation(500, 20);
			assertFalse(
				invokeIsWasted(
					DiskRingBufferTest.this.diskRingBuffer,
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
					DiskRingBufferTest.this.diskRingBuffer,
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
			setRingBufferHead(DiskRingBufferTest.this.diskRingBuffer, 800L);
			setRingBufferTail(DiskRingBufferTest.this.diskRingBuffer, 200L);

			// Session that wraps: starts at 900, length 200
			// Actual bytes: [900, 999] and [0, 99]
			// endPosition() = 900 + 200 = 1100 (raw, exceeds buffer size 1000)
			final SessionFileLocation wrappedSession = new SessionFileLocation(900, 200);
			final boolean result = invokeIsSessionLocationStillInValidArea(DiskRingBufferTest.this.diskRingBuffer, wrappedSession);

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
			setRingBufferHead(DiskRingBufferTest.this.diskRingBuffer, 100L);
			setRingBufferTail(DiskRingBufferTest.this.diskRingBuffer, 500L);

			// Session at (200, 100) -> [200, 300), within [100, 500]
			final SessionFileLocation validSession = new SessionFileLocation(200, 100);
			assertTrue(
				invokeIsSessionLocationStillInValidArea(DiskRingBufferTest.this.diskRingBuffer, validSession),
				"Session [200,299] should be valid within area [100,500]."
			);
		}

		@Test
		@DisplayName("Should reject session outside valid area in non-wrapped buffer")
		void shouldRejectSessionOutsideValidAreaInNonWrappedBuffer() throws Exception {
			// head=100, tail=500 -> valid area: [100, 500]
			setRingBufferHead(DiskRingBufferTest.this.diskRingBuffer, 100L);
			setRingBufferTail(DiskRingBufferTest.this.diskRingBuffer, 500L);

			// Session at (600, 50) -> [600, 650), outside [100, 500]
			final SessionFileLocation afterTail = new SessionFileLocation(600, 50);
			assertFalse(
				invokeIsSessionLocationStillInValidArea(DiskRingBufferTest.this.diskRingBuffer, afterTail),
				"Session [600,649] should NOT be valid - it is after tail=500."
			);

			// Session at (0, 50) -> [0, 50), before head
			final SessionFileLocation beforeHead = new SessionFileLocation(0, 50);
			assertFalse(
				invokeIsSessionLocationStillInValidArea(DiskRingBufferTest.this.diskRingBuffer, beforeHead),
				"Session [0,49] should NOT be valid - it is before head=100."
			);
		}

		@Test
		@DisplayName("Should reject session in the gap of a wrapped buffer")
		void shouldRejectSessionOutsideValidAreaInWrappedBuffer() throws Exception {
			// head=800, tail=200 -> valid area: [800, 1000) and [0, 200)
			// gap: [200, 800)
			setRingBufferHead(DiskRingBufferTest.this.diskRingBuffer, 800L);
			setRingBufferTail(DiskRingBufferTest.this.diskRingBuffer, 200L);

			// Session at (400, 50) -> [400, 450), in the gap
			final SessionFileLocation gapSession = new SessionFileLocation(400, 50);
			assertFalse(
				invokeIsSessionLocationStillInValidArea(DiskRingBufferTest.this.diskRingBuffer, gapSession),
				"Session [400,449] should NOT be valid - it is in the gap [200,800)."
			);
		}

		@Test
		@DisplayName("Should handle session at exact head and tail boundaries")
		void shouldHandleSessionAtExactBoundaries() throws Exception {
			// head=100, tail=500
			setRingBufferHead(DiskRingBufferTest.this.diskRingBuffer, 100L);
			setRingBufferTail(DiskRingBufferTest.this.diskRingBuffer, 500L);

			// Session starting at head, ending at tail: (100, 400) -> endPosition=500
			final SessionFileLocation boundarySession = new SessionFileLocation(100, 400);
			assertTrue(
				invokeIsSessionLocationStillInValidArea(DiskRingBufferTest.this.diskRingBuffer, boundarySession),
				"Session [100,499] at exact head/tail boundaries should be valid."
			);
		}

		@Test
		@DisplayName("Should consider every session valid when the buffer is completely packed (head == tail)")
		void shouldConsiderAllSessionsValidWhenBufferIsCompletelyPacked() throws Exception {
			// head == tail is reachable in normal operation (e.g. right after a write exactly fills the
			// buffer for the first time, before any eviction) and represents "fully packed, no gap" -
			// NOT "empty" - the empty case only ever coincides with an empty `sessionLocations` deque.
			// A single modular head/tail pair otherwise cannot distinguish "0 valid bytes" from "all
			// valid bytes", so `sessionLocations` emptiness is what disambiguates the two.
			setRingBufferHead(DiskRingBufferTest.this.diskRingBuffer, 100L);
			setRingBufferTail(DiskRingBufferTest.this.diskRingBuffer, 100L);
			addSessionLocation(DiskRingBufferTest.this.diskRingBuffer, 1L, new SessionFileLocation(100, 100));

			final SessionFileLocation atHead = new SessionFileLocation(100, 100);
			assertTrue(
				invokeIsSessionLocationStillInValidArea(DiskRingBufferTest.this.diskRingBuffer, atHead),
				"Session [100,199] must be valid when head==tail==100 and the buffer holds live sessions " +
					"- head==tail here means the buffer is completely packed, not empty."
			);

			final SessionFileLocation elsewhere = new SessionFileLocation(200, 100);
			assertTrue(
				invokeIsSessionLocationStillInValidArea(DiskRingBufferTest.this.diskRingBuffer, elsewhere),
				"Session [200,299] must also be valid when head==tail==100 with live sessions present."
			);
		}

		@Test
		@DisplayName("Should consider nothing valid when head == tail and the buffer is genuinely empty")
		void shouldConsiderNothingValidWhenBufferIsGenuinelyEmpty() throws Exception {
			// freshly-constructed diskRingBuffer: head=tail=0, sessionLocations is empty
			final SessionFileLocation anyLocation = new SessionFileLocation(0, 100);
			assertFalse(
				invokeIsSessionLocationStillInValidArea(DiskRingBufferTest.this.diskRingBuffer, anyLocation),
				"No session should be considered valid in a genuinely empty buffer (head==tail==0, " +
					"no session locations tracked)."
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
				final SessionLocation sessionLocation = DiskRingBufferTest.this.diskRingBuffer.appendSession(1, bodySize);
				DiskRingBufferTest.this.diskRingBuffer.append(body);
				DiskRingBufferTest.this.diskRingBuffer.sessionWritten(
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
			final SessionLocation session = DiskRingBufferTest.this.diskRingBuffer.appendSession(1, bodySize);
			DiskRingBufferTest.this.diskRingBuffer.append(body);
			DiskRingBufferTest.this.diskRingBuffer.sessionWritten(
				session, UUID.randomUUID(), OffsetDateTime.now(),
				0, Set.of(), Set.of(), 0, 0
			);

			// verify session exists
			final Deque<SessionLocation> locsBefore = getSessionLocations(DiskRingBufferTest.this.diskRingBuffer);
			assertEquals(1, locsBefore.size());

			// close with tracking consumer
			final AtomicReference<Path> cleanedPath = new AtomicReference<>();
			DiskRingBufferTest.this.diskRingBuffer.close(cleanedPath::set);

			// verify file clean logic received the path
			assertNotNull(cleanedPath.get(), "File clean logic should have been invoked");
			assertEquals(DiskRingBufferTest.this.tempFile, cleanedPath.get());

			// verify session locations were cleared
			final Deque<SessionLocation> locsAfter = getSessionLocations(DiskRingBufferTest.this.diskRingBuffer);
			assertTrue(locsAfter.isEmpty(), "Session locations should be cleared after close");

			// prevent double-close in @AfterEach by creating a new dummy buffer
			DiskRingBufferTest.this.diskRingBuffer = new DiskRingBuffer(
				Files.createTempFile("DiskRingBufferCloseTestReplacement", ".tmp"),
				100
			);
		}

	}

	/**
	 * Regression tests for the concurrency hazards of the OS {@link java.nio.channels.FileLock}
	 * based locking scheme. These tests
	 * encode the *desired* (fixed) behaviour, so they fail against the pre-redesign {@link DiskRingBuffer}
	 * that relies on {@code FileChannel} region locks, and are expected to pass once the in-JVM span lock
	 * ({@code RingBufferSpanLock}) replaces them.
	 */
	@Nested
	@DisplayName("Concurrent lock hazards")
	class ConcurrentLockHazardsTest {

		@Test
		@Timeout(30)
		@DisplayName("Writer should wait (not crash) when a reader holds an overlapping shared lock")
		void shouldNotCrashWriterWhenReaderHoldsOverlappingLock() throws Exception {
			final CountDownLatch readerLockAcquired = new CountDownLatch(1);
			final CountDownLatch releaseReaderLock = new CountDownLatch(1);
			final AtomicReference<Throwable> unexpectedException = new AtomicReference<>();
			final AtomicReference<Throwable> writerException = new AtomicReference<>();
			final AtomicBoolean writerCompleted = new AtomicBoolean(false);

			final Thread readerThread = new Thread(() -> {
				try {
					invokeLockAndRead(
						DiskRingBufferTest.this.diskRingBuffer,
						new SessionFileLocation(0, 100),
						() -> {
							readerLockAcquired.countDown();
							try {
								// a defensive self-release, not the primary handoff: must comfortably outlast
								// awaitThreadState's 5s detection window below, or under CPU starvation this
								// wait can time out and release the lock before the writer ever contends with
								// it, silently turning the whole scenario into a non-conflicting sequential run
								releaseReaderLock.await(20, TimeUnit.SECONDS);
							} catch (InterruptedException e) {
								Thread.currentThread().interrupt();
							}
							return null;
						}
					);
				} catch (Exception e) {
					// the reader path itself must never throw - it always returns null on conflict
					unexpectedException.set(e);
				}
			}, "reader-thread");
			readerThread.start();
			assertTrue(readerLockAcquired.await(5, TimeUnit.SECONDS), "Reader should have acquired its lock first.");

			final Thread writerThread = new Thread(() -> {
				try {
					DiskRingBufferTest.this.diskRingBuffer.appendSession(1, 50);
					writerCompleted.set(true);
				} catch (Throwable t) {
					writerException.set(t);
				}
			}, "writer-thread");
			writerThread.start();

			// deterministically confirm the writer has actually reached the blocking wait inside
			// RingBufferSpanLock#acquireExclusive before releasing the reader - a fixed sleep would only
			// guess at this and could silently degrade to a non-conflicting sequential run under a
			// CPU-starved, highly parallel test run
			awaitThreadState(writerThread, 5000, Thread.State.WAITING, Thread.State.TIMED_WAITING);
			releaseReaderLock.countDown();

			awaitTermination(readerThread, 8000);
			awaitTermination(writerThread, 8000);

			assertNull(unexpectedException.get(), "Reader thread must never throw.");
			assertNull(
				writerException.get(),
				"Writer must not crash (e.g. with OverlappingFileLockException) when a reader holds an " +
					"overlapping shared lock - it should wait for the reader to release instead."
			);
			assertTrue(
				writerCompleted.get(),
				"Writer should complete the append once the conflicting reader releases its lock."
			);
		}

		@Test
		@Timeout(30)
		@DisplayName("Two concurrent readers on overlapping regions should both succeed, not silently drop")
		void shouldAllowConcurrentOverlappingReadersWithoutSilentDrop() throws Exception {
			final CountDownLatch reader1LockAcquired = new CountDownLatch(1);
			final CountDownLatch releaseReader1Lock = new CountDownLatch(1);
			final AtomicReference<String> reader1Result = new AtomicReference<>();

			final Thread reader1Thread = new Thread(() -> {
				final String result = invokeLockAndRead(
					DiskRingBufferTest.this.diskRingBuffer,
					new SessionFileLocation(0, 100),
					() -> {
						reader1LockAcquired.countDown();
						try {
							releaseReader1Lock.await(5, TimeUnit.SECONDS);
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
						}
						return "reader1-done";
					}
				);
				reader1Result.set(result);
			}, "reader1-thread");
			reader1Thread.start();
			assertTrue(reader1LockAcquired.await(5, TimeUnit.SECONDS), "Reader 1 should have acquired its lock first.");

			try {
				final String reader2Result = invokeLockAndRead(
					DiskRingBufferTest.this.diskRingBuffer,
					new SessionFileLocation(50, 100),
					() -> "reader2-done"
				);
				assertNotNull(
					reader2Result,
					"A second reader on an overlapping region should read concurrently with the first " +
						"reader (shared-vs-shared never conflicts), not silently return null because of an " +
						"uncaught OverlappingFileLockException."
				);
				assertEquals("reader2-done", reader2Result);
			} finally {
				releaseReader1Lock.countDown();
				awaitTermination(reader1Thread, 8000);
			}
			assertEquals("reader1-done", reader1Result.get());
		}

		@Test
		@DisplayName("ringBufferHead and ringBufferTail must be volatile for cross-thread visibility")
		void shouldDeclareRingBufferHeadAndTailAsVolatile() throws Exception {
			// A genuine JMM visibility race on these fields is not portably reproducible in a unit test
			// (attempted: a busy-spinning reader thread racing a writer thread mutating the field, relying
			// on the JIT hoisting a non-volatile field read out of the reader's loop - this did NOT reliably
			// reproduce on this JVM/hardware combination - such races
			// aren't deterministically reproducible). Pinning the actual fix instead: the field modifier
			// itself is the invariant volatile visibility requires, since the span lock's monitor only provides
			// happens-before for lock holders - unlocked reads (e.g. between per-record lock acquisitions)
			// still rely solely on volatile for visibility.
			final Field headField = DiskRingBuffer.class.getDeclaredField("ringBufferHead");
			final Field tailField = DiskRingBuffer.class.getDeclaredField("ringBufferTail");
			assertTrue(
				Modifier.isVolatile(headField.getModifiers()),
				"ringBufferHead must be volatile - plain reads outside the span lock have no other " +
					"happens-before edge with the writer thread."
			);
			assertTrue(
				Modifier.isVolatile(tailField.getModifiers()),
				"ringBufferTail must be volatile - plain reads outside the span lock have no other " +
					"happens-before edge with the writer thread."
			);
		}

		@Test
		@DisplayName("A failing write must propagate its IOException, never swallow it")
		void shouldPropagateIOExceptionFromFailingWrite() {
			final IOException boom = new IOException("simulated write failure");
			final Throwable propagated = assertThrows(
				Throwable.class,
				() -> invokeLockAndWrite(DiskRingBufferTest.this.diskRingBuffer, new FileLocation(0, 50), () -> { throw boom; })
			);
			assertSame(
				boom, propagated,
				"lockAndWrite must let the write lambda's IOException propagate to the caller (so the " +
					"session fails) instead of logging and swallowing it, which is what the old OS-FileLock " +
					"implementation did and would leave a registered but unbacked location behind."
			);
		}

		@Test
		@Timeout(10)
		@DisplayName("A failed write must release its exclusive span lock so later writes are not blocked")
		void shouldReleaseExclusiveSpanLockAfterFailedWrite() throws Throwable {
			// the first write over the span fails
			assertThrows(
				IOException.class,
				() -> invokeLockAndWrite(
					DiskRingBufferTest.this.diskRingBuffer, new FileLocation(0, 50), () -> {
					throw new IOException("boom");
				})
			);
			// the failed write must have released its exclusive span in the finally block, so an
			// overlapping write now runs instead of deadlocking on a leaked exclusive token
			final AtomicBoolean secondWriteRan = new AtomicBoolean(false);
			invokeLockAndWrite(DiskRingBufferTest.this.diskRingBuffer, new FileLocation(0, 50), () -> secondWriteRan.set(true));
			assertTrue(
				secondWriteRan.get(),
				"A subsequent write over the same span must run - proving the failed write released its " +
					"exclusive lock in the finally block rather than leaking it and blocking future writers."
			);
		}

		@Test
		@DisplayName("A session whose descriptor write fails must not be registered (no sessionWritten)")
		void shouldNotRegisterSessionWhenDescriptorWriteFails() throws Exception {
			// close the underlying file channel so the very next disk write fails with an IOException
			closeFileChannel(DiskRingBufferTest.this.diskRingBuffer);
			// appendSession must surface the failure (wrapped in UnexpectedIOException) rather than swallow
			// it and hand back a location the caller would then register via sessionWritten
			assertThrows(
				UnexpectedIOException.class,
				() -> DiskRingBufferTest.this.diskRingBuffer.appendSession(1, 50)
			);
			// and no session location may have been registered off the back of a write that never happened
			assertTrue(
				getSessionLocations(DiskRingBufferTest.this.diskRingBuffer).isEmpty(),
				"A failed descriptor write must not leave a registered (but unbacked) session location behind."
			);
		}

	}

	/**
	 * Tests for {@link DiskRingBuffer#exportSnapshot} - the pull-driven, wrap-aware raw-byte
	 * export walk. The "deterministic mid-export eviction" race scenario is deliberately NOT covered
	 * here - it belongs with the higher-level concurrency tests (round-trip acceptance test), where it
	 * can be exercised end-to-end through the real export task rather than re-invented at this unit level.
	 */
	@Nested
	@DisplayName("exportSnapshot()")
	class ExportSnapshotTest {

		@Test
		@DisplayName("Should export all sessions with byte-exact content and correct summary counts")
		void shouldExportAllSessionsExactly() throws Exception {
			final int bodySize = 50;
			final int sessionCount = 5;
			final SessionLocation[] sessions = new SessionLocation[sessionCount];
			final byte[][] bodies = new byte[sessionCount][];
			for (int i = 0; i < sessionCount; i++) {
				final byte fill = (byte) ('A' + i);
				final ByteBuffer body = createFilledBuffer(bodySize, fill);
				bodies[i] = new byte[bodySize];
				body.duplicate().get(bodies[i]);
				sessions[i] = DiskRingBufferTest.this.diskRingBuffer.appendSession(1, bodySize);
				DiskRingBufferTest.this.diskRingBuffer.append(body);
				DiskRingBufferTest.this.diskRingBuffer.sessionWritten(
					sessions[i], UUID.randomUUID(), OffsetDateTime.now(), 0, Set.of(), Set.of(), 0, 0
				);
			}

			final List<byte[]> exportedPayloads = new ArrayList<>();
			final DiskRingBuffer.ExportSummary summary = DiskRingBufferTest.this.diskRingBuffer.exportSnapshot(
				new byte[64],
				(location, byteSource) -> exportedPayloads.add(copyToByteArray(byteSource)),
				(processed, total) -> {
				}
			);

			assertEquals(sessionCount, summary.exportedSessionCount());
			assertEquals(0, summary.skippedSessionCount());
			assertEquals(sessionCount, summary.totalSessionCount());
			assertEquals((long) sessionCount * (bodySize + LEAD_DESCRIPTOR_BYTE_SIZE), summary.exportedByteCount());

			for (int i = 0; i < sessionCount; i++) {
				final byte[] raw = exportedPayloads.get(i);
				assertEquals(bodySize + LEAD_DESCRIPTOR_BYTE_SIZE, raw.length);

				final ByteBuffer descriptor = ByteBuffer.wrap(raw, 0, LEAD_DESCRIPTOR_BYTE_SIZE);
				assertEquals(sessions[i].sequenceOrder(), descriptor.getLong(), "Sequence order mismatch at session " + i);
				assertEquals(1, descriptor.getInt(), "Record count mismatch at session " + i);
				assertEquals(bodySize, descriptor.getInt(), "Body size mismatch at session " + i);

				for (int b = 0; b < bodySize; b++) {
					assertEquals(bodies[i][b], raw[LEAD_DESCRIPTOR_BYTE_SIZE + b], "Payload byte mismatch at session " + i + ", offset " + b);
				}
			}
		}

		@Test
		@DisplayName("Should export a session wrapping the physical buffer end with bytes reassembled in the correct order")
		void shouldExportWrappedSessionCorrectly() throws Exception {
			final Path smallTempFile = Files.createTempFile("DiskRingBufferExportWrapTest", ".tmp");
			final DiskRingBuffer smallBuffer = new DiskRingBuffer(smallTempFile, 250);
			try {
				// Padding session P: total size 20 (16 header + 4 body), occupies [0,20) - pushes session A
				// away from position 0, so session B's later wrap-around segment lands on P (discarded,
				// evicted), not on A (which the test asserts on).
				final SessionLocation sessionP = smallBuffer.appendSession(1, 4);
				smallBuffer.append(createFilledBuffer(4, (byte) 'P'));
				smallBuffer.sessionWritten(sessionP, UUID.randomUUID(), OffsetDateTime.now(), 0, Set.of(), Set.of(), 0, 0);
				assertEquals(20, smallBuffer.getRingBufferTail());

				// Session A: total size 150 (16 header + 134 body), occupies [20,170)
				final int bodyASize = 134;
				final SessionLocation sessionA = smallBuffer.appendSession(1, bodyASize);
				smallBuffer.append(createFilledBuffer(bodyASize, (byte) 'A'));
				smallBuffer.sessionWritten(sessionA, UUID.randomUUID(), OffsetDateTime.now(), 0, Set.of(), Set.of(), 0, 0);
				assertEquals(170, smallBuffer.getRingBufferTail());

				// Session B: total size 100 (16 header + 84 body), occupies [170,270) - wraps past 250, but
				// its wrapped segment [0,20) only overlaps P (evicted), never reaching into A
				final int bodyBSize = 84;
				final ByteBuffer bodyB = ByteBuffer.allocate(bodyBSize);
				for (int i = 0; i < bodyBSize; i++) {
					bodyB.put((byte) i);
				}
				bodyB.flip();
				final byte[] bodyBBytes = new byte[bodyBSize];
				bodyB.duplicate().get(bodyBBytes);

				final SessionLocation sessionB = smallBuffer.appendSession(1, bodyBSize);
				smallBuffer.append(bodyB);
				smallBuffer.sessionWritten(sessionB, UUID.randomUUID(), OffsetDateTime.now(), 0, Set.of(), Set.of(), 0, 0);
				assertTrue(sessionB.location().endPosition() > 250, "Session B must wrap the physical buffer end");

				final List<byte[]> exportedPayloads = new ArrayList<>();
				// deliberately smaller than either wrap segment, to also exercise the read-loop within copySegment
				final DiskRingBuffer.ExportSummary summary = smallBuffer.exportSnapshot(
					new byte[16],
					(location, byteSource) -> exportedPayloads.add(copyToByteArray(byteSource)),
					(processed, total) -> {
					}
				);

				assertEquals(2, summary.exportedSessionCount(), "P should have been evicted, A and B should export");
				assertEquals(0, summary.skippedSessionCount());

				final byte[] rawB = exportedPayloads.get(1);
				assertEquals(bodyBSize + LEAD_DESCRIPTOR_BYTE_SIZE, rawB.length);
				for (int i = 0; i < bodyBSize; i++) {
					assertEquals(bodyBBytes[i], rawB[LEAD_DESCRIPTOR_BYTE_SIZE + i], "Mismatch at payload byte " + i);
				}
			} finally {
				smallBuffer.close(FileUtils::deleteFileIfExists);
				Files.deleteIfExists(smallTempFile);
			}
		}

		@Test
		@DisplayName("Should skip and count a session whose span is exclusively held by the writer, without blocking")
		void shouldSkipSessionExclusivelyHeldByWriter() throws Exception {
			final int bodySize = 50;
			final SessionLocation session = DiskRingBufferTest.this.diskRingBuffer.appendSession(1, bodySize);
			DiskRingBufferTest.this.diskRingBuffer.append(createFilledBuffer(bodySize, (byte) 'A'));
			DiskRingBufferTest.this.diskRingBuffer.sessionWritten(session, UUID.randomUUID(), OffsetDateTime.now(), 0, Set.of(), Set.of(), 0, 0);

			final RingBufferSpanLock spanLock = getSpanLock(DiskRingBufferTest.this.diskRingBuffer);
			final RingBufferSpanLock.Token heldToken = spanLock.acquireExclusive(
				session.location().startingPosition(), session.location().recordLength()
			);
			try {
				final List<byte[]> exportedPayloads = new ArrayList<>();
				final DiskRingBuffer.ExportSummary summary = DiskRingBufferTest.this.diskRingBuffer.exportSnapshot(
					new byte[64],
					(location, byteSource) -> exportedPayloads.add(copyToByteArray(byteSource)),
					(processed, total) -> {
					}
				);

				assertEquals(0, summary.exportedSessionCount());
				assertEquals(1, summary.skippedSessionCount());
				assertEquals(1, summary.totalSessionCount());
				assertTrue(exportedPayloads.isEmpty());
			} finally {
				spanLock.release(heldToken);
			}
		}

		@Test
		@DisplayName("Should skip and count a session whose location became invalid (logically evicted) after the shared lock was acquired")
		void shouldSkipSessionThatBecameInvalidAfterLockAcquired() throws Exception {
			final int bodySize = 50;
			final SessionLocation session = DiskRingBufferTest.this.diskRingBuffer.appendSession(1, bodySize);
			DiskRingBufferTest.this.diskRingBuffer.append(createFilledBuffer(bodySize, (byte) 'A'));
			DiskRingBufferTest.this.diskRingBuffer.sessionWritten(
				session, UUID.randomUUID(), OffsetDateTime.now(), 0, Set.of(), Set.of(), 0, 0
			);

			// simulate a logical eviction that raced ahead of this session's turn in the export walk:
			// no writer holds a span lock (the shared token acquires cleanly), but the head/tail window
			// has moved past the session's location by the time validation runs
			final long endPosition = session.location().endPosition();
			setRingBufferHead(DiskRingBufferTest.this.diskRingBuffer, endPosition);
			setRingBufferTail(DiskRingBufferTest.this.diskRingBuffer, endPosition + 10);

			final List<byte[]> exportedPayloads = new ArrayList<>();
			final DiskRingBuffer.ExportSummary summary = DiskRingBufferTest.this.diskRingBuffer.exportSnapshot(
				new byte[64],
				(location, byteSource) -> exportedPayloads.add(copyToByteArray(byteSource)),
				(processed, total) -> {
				}
			);

			assertEquals(0, summary.exportedSessionCount());
			assertEquals(1, summary.skippedSessionCount());
			assertEquals(1, summary.totalSessionCount());
			assertTrue(exportedPayloads.isEmpty());
		}

		@Test
		@DisplayName("Should skip and count a session whose on-disk descriptor was reused by another session, even though its byte range is still inside the valid window")
		void shouldSkipSessionWhoseSlotWasReusedByAnotherSession() throws Exception {
			final int bodySize = 50;
			final SessionLocation session = DiskRingBufferTest.this.diskRingBuffer.appendSession(1, bodySize);
			DiskRingBufferTest.this.diskRingBuffer.append(createFilledBuffer(bodySize, (byte) 'A'));
			DiskRingBufferTest.this.diskRingBuffer.sessionWritten(
				session, UUID.randomUUID(), OffsetDateTime.now(), 0, Set.of(), Set.of(), 0, 0
			);

			// the session's byte range is still fully inside the live ring-buffer window (head/tail
			// untouched), so isSessionLocationStillInValidArea() passes and the shared token acquires
			// cleanly - yet the on-disk lead descriptor's sequence order no longer matches this session:
			// simulate a completed eviction+reuse by overwriting the descriptor's first 8 bytes (the
			// sequence order) with a *different* (newer) value, as a racing writer's append would have.
			final long reusedSequenceOrder = session.sequenceOrder() + 12345L;
			overwriteOnDiskSequenceOrder(session.location().startingPosition(), reusedSequenceOrder);

			final long mismatchSkipsBefore = DiskRingBufferTest.this.diskRingBuffer.getExportIdentityMismatchSkipCount();
			final List<byte[]> exportedPayloads = new ArrayList<>();
			final DiskRingBuffer.ExportSummary summary = DiskRingBufferTest.this.diskRingBuffer.exportSnapshot(
				new byte[64],
				(location, byteSource) -> exportedPayloads.add(copyToByteArray(byteSource)),
				(processed, total) -> {
				}
			);

			assertEquals(0, summary.exportedSessionCount(), "A reused slot must never be exported verbatim");
			assertEquals(1, summary.skippedSessionCount());
			assertEquals(1, summary.totalSessionCount());
			assertTrue(exportedPayloads.isEmpty());
			assertEquals(
				mismatchSkipsBefore + 1, DiskRingBufferTest.this.diskRingBuffer.getExportIdentityMismatchSkipCount(),
				"The skip must be attributed to the identity-mismatch path, not the eviction path"
			);
		}

		/**
		 * Overwrites the first 8 bytes (the sequence order, big-endian - see
		 * {@link DiskRingBuffer#appendSession}) of the on-disk lead descriptor at {@code startingPosition},
		 * simulating a completed eviction+reuse of that physical slot by a newer session.
		 */
		private void overwriteOnDiskSequenceOrder(long startingPosition, long newSequenceOrder) throws IOException {
			try (final java.io.RandomAccessFile raf = new java.io.RandomAccessFile(DiskRingBufferTest.this.tempFile.toFile(), "rw")) {
				raf.seek(startingPosition);
				raf.writeLong(newSequenceOrder);
			}
		}

		/**
		 * Drains a {@link DiskRingBuffer.SessionByteSource} into a plain byte array for assertions.
		 */
		private static byte[] copyToByteArray(DiskRingBuffer.SessionByteSource byteSource) throws IOException {
			final ByteArrayOutputStream out = new ByteArrayOutputStream();
			byteSource.copyTo(out);
			return out.toByteArray();
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
	 * Adds a synthetic session location directly into the private `sessionLocations` deque via reflection,
	 * without going through the normal append/write path - used to set up a specific state for validity-area
	 * tests without needing to physically write matching bytes to disk.
	 */
	private static void addSessionLocation(
		DiskRingBuffer buffer,
		long sequenceOrder,
		SessionFileLocation location
	) throws Exception {
		getSessionLocations(buffer).add(new SessionLocation(sequenceOrder, 0, location));
	}

	/**
	 * Accesses the private `spanLock` field of a DiskRingBuffer via reflection. Once fetched, its
	 * package-private API (`tryAcquireShared`/`acquireExclusive`/`release`) is directly callable since this
	 * test class shares the same package.
	 */
	private static RingBufferSpanLock getSpanLock(DiskRingBuffer buffer) throws Exception {
		final Field field = DiskRingBuffer.class.getDeclaredField("spanLock");
		field.setAccessible(true);
		return (RingBufferSpanLock) field.get(buffer);
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
	 * An action executed inside a reflectively-invoked `lockAndWrite`, allowed to throw a checked
	 * exception so a write failure can be simulated.
	 */
	@FunctionalInterface
	private interface ThrowingWriteAction {
		void run() throws Exception;
	}

	/**
	 * Invokes the private `lockAndWrite(FileLocation, IOExceptionThrowingLambda)` method via reflection,
	 * driving its (private) functional-interface parameter with a {@link Proxy} that runs the supplied
	 * action. Any exception thrown by the write action is unwrapped from the reflective
	 * {@link InvocationTargetException} and rethrown verbatim, so callers can assert on the original
	 * exception instance.
	 */
	private static void invokeLockAndWrite(
		DiskRingBuffer buffer,
		FileLocation writeSegment,
		ThrowingWriteAction action
	) throws Throwable {
		Method lockAndWrite = null;
		for (final Method method : DiskRingBuffer.class.getDeclaredMethods()) {
			if (method.getName().equals("lockAndWrite")) {
				lockAndWrite = method;
				break;
			}
		}
		assertNotNull(lockAndWrite, "lockAndWrite method must exist on DiskRingBuffer");
		lockAndWrite.setAccessible(true);
		// the second parameter is the private IOExceptionThrowingLambda interface - proxy it
		final Class<?> lambdaType = lockAndWrite.getParameterTypes()[1];
		final Object lambdaProxy = Proxy.newProxyInstance(
			lambdaType.getClassLoader(),
			new Class<?>[]{lambdaType},
			(proxy, method, args) -> {
				action.run();
				return null;
			}
		);
		try {
			lockAndWrite.invoke(buffer, writeSegment, lambdaProxy);
		} catch (InvocationTargetException ex) {
			throw ex.getTargetException();
		}
	}

	/**
	 * Closes the private `fileChannel` of a DiskRingBuffer via reflection so the next disk write fails.
	 */
	private static void closeFileChannel(DiskRingBuffer buffer) throws Exception {
		final Field field = DiskRingBuffer.class.getDeclaredField("fileChannel");
		field.setAccessible(true);
		((java.nio.channels.FileChannel) field.get(buffer)).close();
	}

	/**
	 * Invokes the private generic `lockAndRead` method via reflection.
	 */
	@SuppressWarnings("unchecked")
	private static <T> T invokeLockAndRead(
		DiskRingBuffer buffer,
		SessionFileLocation readSegment,
		Supplier<T> readLambda
	) {
		try {
			final Method method = DiskRingBuffer.class.getDeclaredMethod(
				"lockAndRead", SessionFileLocation.class, Supplier.class
			);
			method.setAccessible(true);
			return (T) method.invoke(buffer, readSegment, readLambda);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
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

	/**
	 * Polls the given thread's state until it reaches one of the target states (or terminates), instead of
	 * racing it with a fixed sleep - deterministically confirms the thread has actually reached (e.g.) a
	 * monitor {@code wait()} rather than guessing how long scheduling takes, which is unreliable under a
	 * CPU-starved, highly parallel test run where the thread may not even have been scheduled yet. Returns
	 * silently if the thread terminates before reaching a target state, leaving diagnosis of *why* to the
	 * caller's own assertions on the thread's recorded outcome.
	 *
	 * @param thread        the thread whose state to poll
	 * @param timeoutMillis the maximum time to wait for the thread to reach one of the target states
	 * @param targetStates  the states considered a successful match
	 */
	private static void awaitThreadState(Thread thread, long timeoutMillis, Thread.State... targetStates) throws InterruptedException {
		final Set<Thread.State> targets = EnumSet.copyOf(Arrays.asList(targetStates));
		final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
		while (System.nanoTime() < deadline) {
			final Thread.State state = thread.getState();
			if (targets.contains(state) || state == Thread.State.TERMINATED) {
				return;
			}
			Thread.sleep(5);
		}
		fail(
			"Thread `" + thread.getName() + "` did not reach state(s) " + targets + " within " + timeoutMillis +
				"ms (was " + thread.getState() + ") - either CPU-starved by a highly parallel test run, or a " +
				"genuine regression that stopped it from blocking as expected."
		);
	}

	/**
	 * Joins the given thread and then verifies it actually terminated, so a thread that merely outlived the
	 * join timeout (e.g. because it was CPU-starved by a highly parallel test run) fails loudly with a clear
	 * diagnosis instead of silently falling through to a downstream assertion on a result the thread never
	 * got to produce.
	 *
	 * @param thread        the thread to join
	 * @param timeoutMillis the maximum time to wait for the thread to terminate
	 */
	private static void awaitTermination(Thread thread, long timeoutMillis) throws InterruptedException {
		thread.join(timeoutMillis);
		assertFalse(
			thread.isAlive(),
			"Thread `" + thread.getName() + "` did not terminate within " + timeoutMillis + "ms - likely " +
				"CPU-starved by a highly parallel test run rather than a genuine hang."
		);
	}

}
