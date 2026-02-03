/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

import io.evitadb.store.checksum.Checksum;
import io.evitadb.store.checksum.Crc32CChecksum;
import io.evitadb.store.offsetIndex.io.OffHeapMemoryOutputStream.Mode;
import io.evitadb.utils.Crc32CWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies the behaviour of {@link OffHeapMemoryOutputStream}.
 *
 * Tests cover:
 *
 * - Basic write operations (single byte, byte array, offset/length writes)
 * - InputStream operations (reading via getInputStream(), available(), skip, etc.)
 * - Interleaved read/write operations
 * - Checksum calculation and verification
 * - State inspection (regionIndex, readPosition, writePosition, mode)
 * - Lifecycle operations (init, close, finalizer execution)
 * - FileChannel dump operations
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("OffHeapMemoryOutputStream comprehensive tests")
class OffHeapMemoryOutputStreamTest {
	private static final int DEFAULT_BUFFER_SIZE = 32;

	private OffHeapMemoryOutputStream outputStream;

	/**
	 * Creates a new OffHeapMemoryOutputStream with the specified checksum.
	 *
	 * @param checksum the checksum implementation to use
	 * @return the created output stream
	 */
	@Nonnull
	private static OffHeapMemoryOutputStream createOutputStream(@Nonnull Checksum checksum) {
		return new OffHeapMemoryOutputStream(checksum);
	}

	/**
	 * Initializes the given output stream with default settings.
	 *
	 * @param stream      the stream to initialize
	 * @param regionIndex the region index to assign
	 * @param bufferSize  the buffer size to use
	 */
	private static void initializeStream(
		@Nonnull OffHeapMemoryOutputStream stream,
		int regionIndex,
		int bufferSize
	) {
		stream.init(regionIndex, ByteBuffer.allocateDirect(bufferSize), (index, os) -> {});
	}

	@BeforeEach
	void setUp() {
		this.outputStream = createOutputStream(Checksum.NO_OP);
		initializeStream(this.outputStream, 1, DEFAULT_BUFFER_SIZE);
	}

	@AfterEach
	void tearDown() {
		this.outputStream.close();
	}

	/**
	 * Tests for basic write operations.
	 */
	@Nested
	@DisplayName("Basic write operations")
	class BasicWriteOperationsTests {

		@Test
		@DisplayName("Should write and read single bytes")
		void shouldWriteAndReadSingleByte() throws IOException {
			OffHeapMemoryOutputStreamTest.this.outputStream.write(1);
			OffHeapMemoryOutputStreamTest.this.outputStream.write(5);
			OffHeapMemoryOutputStreamTest.this.outputStream.write(10);

			OffHeapMemoryOutputStreamTest.this.outputStream.flush();

			final byte[] bytes = OffHeapMemoryOutputStreamTest.this.outputStream.getInputStream().readAllBytes();
			assertArrayEquals(new byte[]{1, 5, 10}, bytes);
			assertEquals(3, OffHeapMemoryOutputStreamTest.this.outputStream.getPeakDataWrittenLength());
		}

		@Test
		@DisplayName("Should write and read all bytes at once")
		void shouldWriteAndReadAllBytesAtOnce() throws IOException {
			OffHeapMemoryOutputStreamTest.this.outputStream.write(new byte[]{1, 5, 10});

			OffHeapMemoryOutputStreamTest.this.outputStream.flush();

			final byte[] bytes = OffHeapMemoryOutputStreamTest.this.outputStream.getInputStream().readAllBytes();
			assertArrayEquals(new byte[]{1, 5, 10}, bytes);
			assertEquals(3, OffHeapMemoryOutputStreamTest.this.outputStream.getPeakDataWrittenLength());
		}

		@Test
		@DisplayName("Should write and read bytes chunk using offset and length")
		void shouldWriteAndReadBytesChunkUsingOffsetAndLength() throws IOException {
			OffHeapMemoryOutputStreamTest.this.outputStream.write(
				new byte[]{1, 5, 10, 15, 20, 25, 30, 35, 40, 45}, 2, 5
			);

			OffHeapMemoryOutputStreamTest.this.outputStream.flush();

			final byte[] bytes = OffHeapMemoryOutputStreamTest.this.outputStream.getInputStream().readAllBytes();
			assertArrayEquals(new byte[]{10, 15, 20, 25, 30}, bytes);
			assertEquals(5, OffHeapMemoryOutputStreamTest.this.outputStream.getPeakDataWrittenLength());
		}

		@Test
		@DisplayName("Should handle empty write")
		void shouldHandleEmptyWrite() throws IOException {
			OffHeapMemoryOutputStreamTest.this.outputStream.write(new byte[]{});

			OffHeapMemoryOutputStreamTest.this.outputStream.flush();

			final byte[] bytes = OffHeapMemoryOutputStreamTest.this.outputStream.getInputStream().readAllBytes();
			assertArrayEquals(new byte[]{}, bytes);
			assertEquals(0, OffHeapMemoryOutputStreamTest.this.outputStream.getPeakDataWrittenLength());
		}

	}

	/**
	 * Tests for InputStream operations.
	 */
	@Nested
	@DisplayName("InputStream operations")
	class InputStreamOperationsTests {

		@Test
		@DisplayName("Should read single byte using provided InputStream")
		void shouldReadByteUsingProvidedInputStream() throws IOException {
			OffHeapMemoryOutputStreamTest.this.outputStream.write(new byte[]{1, 5, 10});
			final OffHeapMemoryInputStream inputStream =
				OffHeapMemoryOutputStreamTest.this.outputStream.getInputStream();

			assertEquals(3, inputStream.available());
			assertEquals((byte) 1, inputStream.read());
			assertEquals(2, inputStream.available());
			assertEquals((byte) 5, inputStream.read());
			assertEquals(1, inputStream.available());
			assertEquals((byte) 10, inputStream.read());
			assertEquals(0, inputStream.available());
			assertEquals((byte) -1, inputStream.read());
		}

		@Test
		@DisplayName("Should read bytes using provided InputStream to larger byte array")
		void shouldReadBytesUsingProvidedInputStreamToLargerByteArray() throws IOException {
			OffHeapMemoryOutputStreamTest.this.outputStream.write(new byte[]{1, 5, 10});
			final OffHeapMemoryInputStream inputStream =
				OffHeapMemoryOutputStreamTest.this.outputStream.getInputStream();

			final byte[] array = new byte[5];
			assertEquals(3, inputStream.read(array));
			assertArrayEquals(new byte[]{1, 5, 10, 0, 0}, array);
		}

		@Test
		@DisplayName("Should read all bytes using provided InputStream")
		void shouldReadAllBytesUsingProvidedInputStream() throws IOException {
			OffHeapMemoryOutputStreamTest.this.outputStream.write(new byte[]{1, 5, 10});
			final OffHeapMemoryInputStream inputStream =
				OffHeapMemoryOutputStreamTest.this.outputStream.getInputStream();

			assertArrayEquals(new byte[]{1, 5, 10}, inputStream.readAllBytes());
		}

		@Test
		@DisplayName("Should read N bytes using provided InputStream")
		void shouldReadNBytesUsingProvidedInputStream() throws IOException {
			OffHeapMemoryOutputStreamTest.this.outputStream.write(new byte[]{1, 5, 10});
			final OffHeapMemoryInputStream inputStream =
				OffHeapMemoryOutputStreamTest.this.outputStream.getInputStream();

			assertArrayEquals(new byte[]{1, 5}, inputStream.readNBytes(2));
		}

		@Test
		@DisplayName("Should read N bytes using offset and length provided InputStream")
		void shouldReadNBytesUsingOffsetAndLengthProvidedInputStream() throws IOException {
			OffHeapMemoryOutputStreamTest.this.outputStream.write(new byte[]{1, 5, 10});
			final OffHeapMemoryInputStream inputStream =
				OffHeapMemoryOutputStreamTest.this.outputStream.getInputStream();

			final byte[] array = new byte[5];
			assertEquals(2, inputStream.readNBytes(array, 1, 2));
			assertArrayEquals(new byte[]{0, 1, 5, 0, 0}, array);
		}

		@Test
		@DisplayName("Should read some bytes using provided InputStream and skipping a byte")
		void shouldReadSomeBytesUsingProvidedInputStreamAndSkippingAByte() throws IOException {
			OffHeapMemoryOutputStreamTest.this.outputStream.write(new byte[]{1, 5, 10});
			final OffHeapMemoryInputStream inputStream =
				OffHeapMemoryOutputStreamTest.this.outputStream.getInputStream();

			assertEquals(1, inputStream.skip(1));
			assertEquals(5, inputStream.read());
			assertEquals(10, inputStream.read());
			assertEquals(-1, inputStream.read());
		}

		@Test
		@DisplayName("Should read some bytes using provided InputStream and skipping N bytes")
		void shouldReadSomeBytesUsingProvidedInputStreamAndSkippingNBytes() throws IOException {
			OffHeapMemoryOutputStreamTest.this.outputStream.write(new byte[]{1, 5, 10});
			final OffHeapMemoryInputStream inputStream =
				OffHeapMemoryOutputStreamTest.this.outputStream.getInputStream();

			inputStream.skipNBytes(2);
			assertEquals(10, inputStream.read());
			assertEquals(-1, inputStream.read());
		}

		@Test
		@DisplayName("Should return same InputStream on multiple calls")
		void shouldReturnSameInputStreamOnMultipleCalls() throws IOException {
			OffHeapMemoryOutputStreamTest.this.outputStream.write(new byte[]{1, 5, 10});

			final OffHeapMemoryInputStream firstCall =
				OffHeapMemoryOutputStreamTest.this.outputStream.getInputStream();
			final OffHeapMemoryInputStream secondCall =
				OffHeapMemoryOutputStreamTest.this.outputStream.getInputStream();

			assertSame(firstCall, secondCall, "getInputStream should return the same instance");
		}

	}

	/**
	 * Tests for interleaved read/write operations.
	 */
	@Nested
	@DisplayName("Interleaved read/write operations")
	class InterleavedReadWriteOperationsTests {

		@Test
		@DisplayName("Should read and write interleaved")
		void shouldReadAndWriteInterleaved() throws IOException {
			OffHeapMemoryOutputStreamTest.this.outputStream.write(new byte[]{1, 5, 10});
			final OffHeapMemoryInputStream inputStream =
				OffHeapMemoryOutputStreamTest.this.outputStream.getInputStream();

			assertEquals(1, inputStream.read());
			assertEquals(5, inputStream.read());
			assertEquals(10, inputStream.read());
			assertEquals(-1, inputStream.read());

			OffHeapMemoryOutputStreamTest.this.outputStream.write(new byte[]{15, 20, 25});
			assertEquals(15, inputStream.read());
			assertEquals(20, inputStream.read());
			assertEquals(25, inputStream.read());
			assertEquals(-1, inputStream.read());

			inputStream.seek(0);
			assertArrayEquals(
				new byte[]{1, 5, 10, 15, 20, 25},
				inputStream.readAllBytes()
			);
		}

		@Test
		@DisplayName("Should handle write after multiple mode switches")
		void shouldHandleWriteAfterMultipleModeSwitch() throws IOException {
			// first write
			OffHeapMemoryOutputStreamTest.this.outputStream.write(new byte[]{1, 2, 3});

			// switch to read mode
			final OffHeapMemoryInputStream inputStream =
				OffHeapMemoryOutputStreamTest.this.outputStream.getInputStream();
			assertEquals(1, inputStream.read());

			// switch back to write
			OffHeapMemoryOutputStreamTest.this.outputStream.write(new byte[]{4, 5});

			// switch to read again
			assertEquals(2, inputStream.read());
			assertEquals(3, inputStream.read());
			assertEquals(4, inputStream.read());
			assertEquals(5, inputStream.read());
			assertEquals(-1, inputStream.read());

			assertEquals(5, OffHeapMemoryOutputStreamTest.this.outputStream.getPeakDataWrittenLength());
		}

	}

	/**
	 * Tests for state inspection methods.
	 */
	@Nested
	@DisplayName("State inspection")
	class StateInspectionTests {

		@Test
		@DisplayName("Should return correct region index after init")
		void shouldReturnCorrectRegionIndexAfterInit() {
			final OffHeapMemoryOutputStream stream = createOutputStream(Checksum.NO_OP);
			stream.init(42, ByteBuffer.allocateDirect(DEFAULT_BUFFER_SIZE), (i, s) -> {});

			assertEquals(42, stream.getRegionIndex());
			stream.close();
		}

		@Test
		@DisplayName("Should return write mode after init")
		void shouldReturnWriteModeAfterInit() {
			assertEquals(Mode.WRITE, OffHeapMemoryOutputStreamTest.this.outputStream.getMode());
		}

		@Test
		@DisplayName("Should return read mode after getting InputStream")
		void shouldReturnReadModeAfterGettingInputStream() throws IOException {
			OffHeapMemoryOutputStreamTest.this.outputStream.write(new byte[]{1, 2, 3});
			OffHeapMemoryOutputStreamTest.this.outputStream.getInputStream();

			assertEquals(Mode.READ, OffHeapMemoryOutputStreamTest.this.outputStream.getMode());
		}

		@Test
		@DisplayName("Should return correct write position after writing")
		void shouldReturnCorrectWritePositionAfterWriting() throws IOException {
			OffHeapMemoryOutputStreamTest.this.outputStream.write(new byte[]{1, 2, 3, 4, 5});

			// in write mode, writePosition is updated only on mode switch
			// so we need to get the input stream first to trigger the switch
			OffHeapMemoryOutputStreamTest.this.outputStream.getInputStream();

			assertEquals(5, OffHeapMemoryOutputStreamTest.this.outputStream.getWritePosition());
		}

		@Test
		@DisplayName("Should return correct read position after reading")
		void shouldReturnCorrectReadPositionAfterReading() throws IOException {
			OffHeapMemoryOutputStreamTest.this.outputStream.write(new byte[]{1, 2, 3, 4, 5});
			final OffHeapMemoryInputStream inputStream =
				OffHeapMemoryOutputStreamTest.this.outputStream.getInputStream();

			inputStream.read();
			inputStream.read();
			inputStream.read();

			// switch back to write mode to update readPosition
			OffHeapMemoryOutputStreamTest.this.outputStream.write(new byte[]{6});

			assertEquals(3, OffHeapMemoryOutputStreamTest.this.outputStream.getReadPosition());
		}

		@Test
		@DisplayName("Should return correct peak data written length in write mode")
		void shouldReturnCorrectPeakDataWrittenLengthInWriteMode() throws IOException {
			OffHeapMemoryOutputStreamTest.this.outputStream.write(new byte[]{1, 2, 3, 4, 5});

			assertEquals(5, OffHeapMemoryOutputStreamTest.this.outputStream.getPeakDataWrittenLength());
		}

		@Test
		@DisplayName("Should return correct peak data written length in read mode")
		void shouldReturnCorrectPeakDataWrittenLengthInReadMode() throws IOException {
			OffHeapMemoryOutputStreamTest.this.outputStream.write(new byte[]{1, 2, 3, 4, 5});
			OffHeapMemoryOutputStreamTest.this.outputStream.getInputStream();

			assertEquals(5, OffHeapMemoryOutputStreamTest.this.outputStream.getPeakDataWrittenLength());
		}

	}

	/**
	 * Tests for mode switching behavior.
	 */
	@Nested
	@DisplayName("Mode switching")
	class ModeSwitchingTests {

		@Test
		@DisplayName("Should switch from write to read mode when getting InputStream")
		void shouldSwitchFromWriteToReadModeWhenGettingInputStream() throws IOException {
			assertEquals(Mode.WRITE, OffHeapMemoryOutputStreamTest.this.outputStream.getMode());

			OffHeapMemoryOutputStreamTest.this.outputStream.write(new byte[]{1, 2, 3});
			OffHeapMemoryOutputStreamTest.this.outputStream.getInputStream();

			assertEquals(Mode.READ, OffHeapMemoryOutputStreamTest.this.outputStream.getMode());
		}

		@Test
		@DisplayName("Should switch from read to write mode when writing after reading")
		void shouldSwitchFromReadToWriteModeWhenWritingAfterReading() throws IOException {
			OffHeapMemoryOutputStreamTest.this.outputStream.write(new byte[]{1, 2, 3});
			OffHeapMemoryOutputStreamTest.this.outputStream.getInputStream();
			assertEquals(Mode.READ, OffHeapMemoryOutputStreamTest.this.outputStream.getMode());

			OffHeapMemoryOutputStreamTest.this.outputStream.write(4);
			assertEquals(Mode.WRITE, OffHeapMemoryOutputStreamTest.this.outputStream.getMode());
		}

		@Test
		@DisplayName("Should preserve positions across mode switch")
		void shouldPreservePositionsAcrossModeSwitch() throws IOException {
			// write some data
			OffHeapMemoryOutputStreamTest.this.outputStream.write(new byte[]{1, 2, 3, 4, 5});

			// switch to read mode and read some bytes
			final OffHeapMemoryInputStream inputStream =
				OffHeapMemoryOutputStreamTest.this.outputStream.getInputStream();
			assertEquals(1, inputStream.read());
			assertEquals(2, inputStream.read());

			// switch back to write mode
			OffHeapMemoryOutputStreamTest.this.outputStream.write(new byte[]{6, 7});

			// switch back to read mode
			assertEquals(3, inputStream.read());
			assertEquals(4, inputStream.read());
			assertEquals(5, inputStream.read());
			assertEquals(6, inputStream.read());
			assertEquals(7, inputStream.read());
			assertEquals(-1, inputStream.read());
		}

	}

	/**
	 * Tests for lifecycle operations.
	 */
	@Nested
	@DisplayName("Lifecycle operations")
	class LifecycleOperationsTests {

		@Test
		@DisplayName("Should execute finalizer on close")
		void shouldExecuteFinalizerOnClose() {
			final AtomicBoolean finalizerCalled = new AtomicBoolean(false);

			final OffHeapMemoryOutputStream stream = createOutputStream(Checksum.NO_OP);
			stream.init(1, ByteBuffer.allocateDirect(DEFAULT_BUFFER_SIZE), (index, os) -> {
				finalizerCalled.set(true);
			});

			stream.close();

			assertTrue(finalizerCalled.get(), "Finalizer should be called on close");
		}

		@Test
		@DisplayName("Should pass correct region index to finalizer")
		void shouldPassCorrectRegionIndexToFinalizer() {
			final AtomicInteger receivedIndex = new AtomicInteger(-1);

			final OffHeapMemoryOutputStream stream = createOutputStream(Checksum.NO_OP);
			stream.init(42, ByteBuffer.allocateDirect(DEFAULT_BUFFER_SIZE), (index, os) -> {
				receivedIndex.set(index);
			});

			stream.close();

			assertEquals(42, receivedIndex.get(), "Finalizer should receive correct region index");
		}

		@Test
		@DisplayName("Should allow multiple close calls")
		void shouldAllowMultipleCloseCalls() {
			final AtomicInteger closeCount = new AtomicInteger(0);

			final OffHeapMemoryOutputStream stream = createOutputStream(Checksum.NO_OP);
			stream.init(1, ByteBuffer.allocateDirect(DEFAULT_BUFFER_SIZE), (index, os) -> {
				closeCount.incrementAndGet();
			});

			stream.close();
			stream.close();
			stream.close();

			// finalizer should only be called once (subsequent closes should be no-op)
			assertEquals(1, closeCount.get(), "Finalizer should only be called once");
		}

		@Test
		@DisplayName("Should close InputStream when OutputStream is closed")
		void shouldCloseInputStreamWhenOutputStreamClosed() throws IOException {
			OffHeapMemoryOutputStreamTest.this.outputStream.write(new byte[]{1, 2, 3});
			final OffHeapMemoryInputStream inputStream =
				OffHeapMemoryOutputStreamTest.this.outputStream.getInputStream();

			assertNotNull(inputStream.getBuffer(), "Buffer should be present before close");

			OffHeapMemoryOutputStreamTest.this.outputStream.close();

			// after close, the buffer reference in InputStream should be null
			assertNull(inputStream.getBuffer(), "Buffer should be null after close");
		}

		@Test
		@DisplayName("Should reset checksum on init")
		void shouldResetChecksumOnInit() throws IOException {
			final Crc32CChecksum checksum = new Crc32CChecksum();
			final OffHeapMemoryOutputStream stream = createOutputStream(checksum);

			// first init and write
			stream.init(1, ByteBuffer.allocateDirect(DEFAULT_BUFFER_SIZE), (i, s) -> {});
			stream.write(new byte[]{1, 2, 3});
			final long firstChecksum = stream.getChecksum();
			stream.close();

			// second init with same stream - checksum should reset
			stream.init(2, ByteBuffer.allocateDirect(DEFAULT_BUFFER_SIZE), (i, s) -> {});
			// write same data
			stream.write(new byte[]{1, 2, 3});
			final long secondChecksum = stream.getChecksum();
			stream.close();

			assertEquals(
				firstChecksum, secondChecksum,
				"Checksum should be same after reset and writing same data"
			);
		}

	}

	/**
	 * Tests for checksum operations.
	 */
	@Nested
	@DisplayName("Checksum operations")
	class ChecksumOperationsTests {

		@Test
		@DisplayName("Should calculate correct checksum matching dumped file contents")
		void shouldCalculateCorrectChecksumMatchingDumpedFileContents() throws IOException {
			final Crc32CChecksum checksum = new Crc32CChecksum();
			final OffHeapMemoryOutputStream testOutputStream = createOutputStream(checksum);
			final ByteBuffer buffer = ByteBuffer.allocateDirect(64);
			testOutputStream.init(0, buffer, (i, s) -> {});

			final byte[] testData = "Test data for checksum verification".getBytes(StandardCharsets.UTF_8);
			testOutputStream.write(testData);

			final long streamChecksum = testOutputStream.getChecksum();

			final Path tempFile = Files.createTempFile("checksum-test", ".dat");
			try (FileChannel channel = FileChannel.open(tempFile,
				StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
				testOutputStream.dumpToChannel(channel);
			}

			final byte[] fileContents = Files.readAllBytes(tempFile);
			final Crc32CWrapper wrapper = new Crc32CWrapper();
			wrapper.withByteArray(fileContents);
			final long fileChecksum = wrapper.getValue();

			assertEquals(
				fileChecksum, streamChecksum,
				"Stream checksum should match checksum of dumped file contents"
			);

			Files.deleteIfExists(tempFile);
			testOutputStream.close();
		}

		@Test
		@DisplayName("Should return zero checksum with NO_OP checksum")
		void shouldReturnZeroChecksumWithNoOpChecksum() throws IOException {
			// outputStream is created with NO_OP checksum in setUp
			OffHeapMemoryOutputStreamTest.this.outputStream.write(new byte[]{1, 2, 3, 4, 5});

			assertEquals(
				0L, OffHeapMemoryOutputStreamTest.this.outputStream.getChecksum(),
				"NO_OP checksum should always return 0"
			);
		}

		@Test
		@DisplayName("Should calculate different checksum for different data")
		void shouldCalculateDifferentChecksumForDifferentData() throws IOException {
			final Crc32CChecksum checksum1 = new Crc32CChecksum();
			final OffHeapMemoryOutputStream stream1 = createOutputStream(checksum1);
			stream1.init(1, ByteBuffer.allocateDirect(DEFAULT_BUFFER_SIZE), (i, s) -> {});
			stream1.write(new byte[]{1, 2, 3});
			final long checksumValue1 = stream1.getChecksum();
			stream1.close();

			final Crc32CChecksum checksum2 = new Crc32CChecksum();
			final OffHeapMemoryOutputStream stream2 = createOutputStream(checksum2);
			stream2.init(1, ByteBuffer.allocateDirect(DEFAULT_BUFFER_SIZE), (i, s) -> {});
			stream2.write(new byte[]{4, 5, 6});
			final long checksumValue2 = stream2.getChecksum();
			stream2.close();

			assertTrue(
				checksumValue1 != checksumValue2,
				"Different data should produce different checksums"
			);
		}

	}

	/**
	 * Tests for FileChannel operations.
	 */
	@Nested
	@DisplayName("FileChannel operations")
	class FileChannelOperationsTests {

		@Test
		@DisplayName("Should dump all written data to channel")
		void shouldDumpAllWrittenDataToChannel() throws IOException {
			final byte[] testData = "Hello, FileChannel!".getBytes(StandardCharsets.UTF_8);
			OffHeapMemoryOutputStreamTest.this.outputStream.write(testData);

			final Path tempFile = Files.createTempFile("dump-test", ".dat");
			try (FileChannel channel = FileChannel.open(tempFile,
				StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
				OffHeapMemoryOutputStreamTest.this.outputStream.dumpToChannel(channel);
			}

			final byte[] fileContents = Files.readAllBytes(tempFile);
			assertArrayEquals(testData, fileContents, "Dumped data should match written data");

			Files.deleteIfExists(tempFile);
		}

		@Test
		@DisplayName("Should dump correct data after mode switch")
		void shouldDumpCorrectDataAfterModeSwitch() throws IOException {
			final byte[] testData = "Data before mode switch".getBytes(StandardCharsets.UTF_8);
			OffHeapMemoryOutputStreamTest.this.outputStream.write(testData);

			// switch to read mode
			final OffHeapMemoryInputStream inputStream =
				OffHeapMemoryOutputStreamTest.this.outputStream.getInputStream();
			inputStream.read(); // read one byte

			final Path tempFile = Files.createTempFile("dump-mode-switch-test", ".dat");
			try (FileChannel channel = FileChannel.open(tempFile,
				StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
				OffHeapMemoryOutputStreamTest.this.outputStream.dumpToChannel(channel);
			}

			final byte[] fileContents = Files.readAllBytes(tempFile);
			assertArrayEquals(testData, fileContents, "Dumped data should include all written data");

			Files.deleteIfExists(tempFile);
		}

		@Test
		@DisplayName("Should dump empty buffer without error")
		void shouldDumpEmptyBufferWithoutError() throws IOException {
			final Path tempFile = Files.createTempFile("dump-empty-test", ".dat");
			try (FileChannel channel = FileChannel.open(tempFile,
				StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
				OffHeapMemoryOutputStreamTest.this.outputStream.dumpToChannel(channel);
			}

			final byte[] fileContents = Files.readAllBytes(tempFile);
			assertEquals(0, fileContents.length, "Dumped empty buffer should result in empty file");

			Files.deleteIfExists(tempFile);
		}

	}

	/**
	 * Tests for edge cases.
	 */
	@Nested
	@DisplayName("Edge cases")
	class EdgeCaseTests {

		@Test
		@DisplayName("Should handle single byte write correctly")
		void shouldHandleSingleByteWriteCorrectly() throws IOException {
			OffHeapMemoryOutputStreamTest.this.outputStream.write(42);

			final byte[] bytes = OffHeapMemoryOutputStreamTest.this.outputStream.getInputStream().readAllBytes();
			assertArrayEquals(new byte[]{42}, bytes);
		}

		@Test
		@DisplayName("Should handle ByteBuffer with custom allocator")
		void shouldHandleByteBufferWithHeapAllocation() throws IOException {
			final OffHeapMemoryOutputStream stream = createOutputStream(Checksum.NO_OP);
			// use heap buffer instead of direct buffer
			stream.init(1, ByteBuffer.allocate(DEFAULT_BUFFER_SIZE), (i, s) -> {});

			stream.write(new byte[]{1, 2, 3});
			final byte[] bytes = stream.getInputStream().readAllBytes();

			assertArrayEquals(new byte[]{1, 2, 3}, bytes);
			stream.close();
		}

		@Test
		@DisplayName("Should handle zero-length array write")
		void shouldHandleZeroLengthArrayWrite() throws IOException {
			OffHeapMemoryOutputStreamTest.this.outputStream.write(new byte[]{1, 2, 3}, 0, 0);

			final byte[] bytes = OffHeapMemoryOutputStreamTest.this.outputStream.getInputStream().readAllBytes();
			assertArrayEquals(new byte[]{}, bytes);
		}

	}

}
