/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.stream;

import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RandomAccessFileInputStream} verifying read, seek, skip, available,
 * close behavior and error handling over a {@link RandomAccessFile}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("RandomAccessFileInputStream")
class RandomAccessFileInputStreamTest implements EvitaTestSupport {

	/**
	 * 10-byte test content: bytes 0..9.
	 */
	private static final byte[] TEST_DATA = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};

	private Path tempFile;
	private RandomAccessFile raf;

	@BeforeEach
	void setUp() throws IOException {
		this.tempFile = Files.createTempFile("raf-test-", ".bin");
		Files.write(this.tempFile, TEST_DATA);
		this.raf = new RandomAccessFile(this.tempFile.toFile(), "r");
	}

	@AfterEach
	void tearDown() throws IOException {
		if (this.raf != null) {
			this.raf.close();
		}
		Files.deleteIfExists(this.tempFile);
	}

	@SuppressWarnings("EmptyTryBlock")
	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("Should reject null RandomAccessFile")
		void shouldRejectNullFile() {
			assertThrows(
				NullPointerException.class,
				() -> {
					try (var __ = new RandomAccessFileInputStream(null)) {
					}
				}
			);
		}

		@Test
		@DisplayName("Should reject null RandomAccessFile with closeOnClose flag")
		void shouldRejectNullFileWithFlag() {
			assertThrows(
				NullPointerException.class, () -> {
					try (var __ = new RandomAccessFileInputStream(null, true)) {
					}
				}
			);
		}

		@Test
		@DisplayName("Should construct with default closeOnClose=false")
		void shouldConstructWithDefaultCloseOnClose() {
			try (RandomAccessFileInputStream stream = new RandomAccessFileInputStream(
				RandomAccessFileInputStreamTest.this.raf)) {
				assertNotNull(stream);
				assertSame(RandomAccessFileInputStreamTest.this.raf, stream.getRandomAccessFile());
			}
		}

		@Test
		@DisplayName("Should construct with explicit closeOnClose=true")
		void shouldConstructWithCloseOnCloseTrue() throws IOException {
			final RandomAccessFile localRaf = new RandomAccessFile(
				RandomAccessFileInputStreamTest.this.tempFile.toFile(), "r"
			);
			try (RandomAccessFileInputStream stream = new RandomAccessFileInputStream(localRaf, true)) {
				assertNotNull(stream);
				assertSame(localRaf, stream.getRandomAccessFile());
			}
			// after close with closeOnClose=true, the RAF should be closed
			assertThrows(IOException.class, localRaf::read);
		}
	}

	@Nested
	@DisplayName("Single byte read")
	class SingleByteReadTest {

		@Test
		@DisplayName("Should read bytes sequentially")
		void shouldReadBytesSequentially() {
			try (RandomAccessFileInputStream stream = new RandomAccessFileInputStream(
				RandomAccessFileInputStreamTest.this.raf)) {
				assertEquals(0, stream.read());
				assertEquals(1, stream.read());
				assertEquals(2, stream.read());
			}
		}

		@Test
		@DisplayName("Should return -1 at EOF")
		void shouldReturnMinusOneAtEof() {
			try (RandomAccessFileInputStream stream = new RandomAccessFileInputStream(
				RandomAccessFileInputStreamTest.this.raf)) {
				stream.seek(TEST_DATA.length);
				assertEquals(-1, stream.read());
			}
		}

		@Test
		@DisplayName("Should return unsigned byte value (0..255)")
		void shouldReturnUnsignedByteValue() throws IOException {
			// write a file with byte value 0xFF (255 unsigned, -1 signed)
			final Path highByteFile = Files.createTempFile("raf-highbyte-", ".bin");
			try {
				Files.write(highByteFile, new byte[]{(byte) 0xFF});
				try (
					RandomAccessFile highRaf = new RandomAccessFile(highByteFile.toFile(), "r");
					RandomAccessFileInputStream stream = new RandomAccessFileInputStream(highRaf)
				) {
					assertEquals(255, stream.read());
				}
			} finally {
				Files.deleteIfExists(highByteFile);
			}
		}

		@Test
		@DisplayName("Should read all bytes until EOF")
		void shouldReadAllBytesUntilEof() {
			try (RandomAccessFileInputStream stream = new RandomAccessFileInputStream(
				RandomAccessFileInputStreamTest.this.raf)) {
				for (final byte testDatum : TEST_DATA) {
					assertEquals(testDatum, stream.read());
				}
				assertEquals(-1, stream.read());
			}
		}
	}

	@Nested
	@DisplayName("Byte array read")
	class ByteArrayReadTest {

		@Test
		@DisplayName("Should read full array")
		void shouldReadFullArray() {
			try (RandomAccessFileInputStream stream = new RandomAccessFileInputStream(
				RandomAccessFileInputStreamTest.this.raf)) {
				final byte[] buffer = new byte[TEST_DATA.length];
				final int bytesRead = stream.read(buffer);
				assertEquals(TEST_DATA.length, bytesRead);
				assertArrayEquals(TEST_DATA, buffer);
			}
		}

		@Test
		@DisplayName("Should read partial array at EOF")
		void shouldReadPartialAtEof() {
			try (RandomAccessFileInputStream stream = new RandomAccessFileInputStream(
				RandomAccessFileInputStreamTest.this.raf)) {
				stream.seek(7);
				final byte[] buffer = new byte[10];
				final int bytesRead = stream.read(buffer);
				assertEquals(3, bytesRead);
				assertEquals(7, buffer[0]);
				assertEquals(8, buffer[1]);
				assertEquals(9, buffer[2]);
			}
		}

		@Test
		@DisplayName("Should return -1 when reading from empty file")
		void shouldReturnMinusOneFromEmptyFile() throws IOException {
			final Path emptyFile = Files.createTempFile("raf-empty-", ".bin");
			try {
				try (
					RandomAccessFile emptyRaf = new RandomAccessFile(emptyFile.toFile(), "r");
					RandomAccessFileInputStream stream = new RandomAccessFileInputStream(emptyRaf)
				) {
					final byte[] buffer = new byte[10];
					assertEquals(-1, stream.read(buffer));
				}
			} finally {
				Files.deleteIfExists(emptyFile);
			}
		}

		@Test
		@DisplayName("Should return -1 when already at EOF")
		void shouldReturnMinusOneAtEof() {
			try (RandomAccessFileInputStream stream = new RandomAccessFileInputStream(
				RandomAccessFileInputStreamTest.this.raf)) {
				stream.seek(TEST_DATA.length);
				final byte[] buffer = new byte[5];
				assertEquals(-1, stream.read(buffer));
			}
		}
	}

	@Nested
	@DisplayName("Array read with offset")
	class ArrayReadWithOffsetTest {

		@Test
		@DisplayName("Should read into array at given offset and length")
		void shouldReadWithOffsetAndLength() {
			try (RandomAccessFileInputStream stream = new RandomAccessFileInputStream(
				RandomAccessFileInputStreamTest.this.raf)) {
				final byte[] buffer = new byte[20];
				final int bytesRead = stream.read(buffer, 5, 4);
				assertEquals(4, bytesRead);
				assertEquals(0, buffer[5]);
				assertEquals(1, buffer[6]);
				assertEquals(2, buffer[7]);
				assertEquals(3, buffer[8]);
			}
		}

		@Test
		@DisplayName("Should read partial at near-EOF")
		void shouldReadPartialNearEof() {
			try (RandomAccessFileInputStream stream = new RandomAccessFileInputStream(
				RandomAccessFileInputStreamTest.this.raf)) {
				stream.seek(8);
				final byte[] buffer = new byte[10];
				final int bytesRead = stream.read(buffer, 0, 5);
				assertEquals(2, bytesRead);
				assertEquals(8, buffer[0]);
				assertEquals(9, buffer[1]);
			}
		}

		@Test
		@DisplayName("Should return -1 when at EOF with offset read")
		void shouldReturnMinusOneAtEofWithOffset() {
			try (RandomAccessFileInputStream stream = new RandomAccessFileInputStream(
				RandomAccessFileInputStreamTest.this.raf)) {
				stream.seek(TEST_DATA.length);
				final byte[] buffer = new byte[10];
				assertEquals(-1, stream.read(buffer, 0, 5));
			}
		}

		@Test
		@DisplayName("Should throw on invalid offset or length")
		void shouldThrowOnInvalidOffsetOrLength() {
			try (RandomAccessFileInputStream stream = new RandomAccessFileInputStream(
				RandomAccessFileInputStreamTest.this.raf)) {
				final byte[] buffer = new byte[5];
				assertThrows(IndexOutOfBoundsException.class, () -> stream.read(buffer, -1, 3));
				assertThrows(IndexOutOfBoundsException.class, () -> stream.read(buffer, 0, 6));
				assertThrows(IndexOutOfBoundsException.class, () -> stream.read(buffer, 3, 3));
			}
		}
	}

	@Nested
	@DisplayName("Seek")
	class SeekTest {

		@SuppressWarnings("ResultOfMethodCallIgnored")
		@Test
		@DisplayName("Should seek to beginning")
		void shouldSeekToBeginning() {
			try (
				RandomAccessFileInputStream stream =
					new RandomAccessFileInputStream(RandomAccessFileInputStreamTest.this.raf)
			) {
				stream.read();
				stream.read();
				stream.seek(0);
				assertEquals(0, stream.read());
			}
		}

		@Test
		@DisplayName("Should seek to middle")
		void shouldSeekToMiddle() {
			try (
				RandomAccessFileInputStream stream =
					new RandomAccessFileInputStream(RandomAccessFileInputStreamTest.this.raf)
			) {
				stream.seek(5);
				assertEquals(5, stream.read());
			}
		}

		@Test
		@DisplayName("Should seek to EOF")
		void shouldSeekToEof() {
			try (RandomAccessFileInputStream stream = new RandomAccessFileInputStream(
				RandomAccessFileInputStreamTest.this.raf)) {
				stream.seek(TEST_DATA.length);
				assertEquals(-1, stream.read());
			}
		}

		@Test
		@DisplayName("Should seek back and forth")
		void shouldSeekBackAndForth() {
			try (RandomAccessFileInputStream stream = new RandomAccessFileInputStream(
				RandomAccessFileInputStreamTest.this.raf)) {
				stream.seek(8);
				assertEquals(8, stream.read());
				stream.seek(2);
				assertEquals(2, stream.read());
				stream.seek(9);
				assertEquals(9, stream.read());
			}
		}

		@Test
		@DisplayName("Should seek to 0 in empty file")
		void shouldSeekToZeroInEmptyFile() throws IOException {
			final Path emptyFile = Files.createTempFile("raf-empty-", ".bin");
			try {
				try (
					RandomAccessFile emptyRaf = new RandomAccessFile(emptyFile.toFile(), "r");
					RandomAccessFileInputStream stream = new RandomAccessFileInputStream(emptyRaf)
				) {
					stream.seek(0);
					assertEquals(-1, stream.read());
				}
			} finally {
				Files.deleteIfExists(emptyFile);
			}
		}
	}

	@Nested
	@DisplayName("Skip")
	class SkipTest {

		@Test
		@DisplayName("Should skip forward within file")
		void shouldSkipForward() {
			try (RandomAccessFileInputStream stream = new RandomAccessFileInputStream(
				RandomAccessFileInputStreamTest.this.raf)) {
				final long skipped = stream.skip(5);
				assertEquals(5, skipped);
				assertEquals(5, stream.read());
			}
		}

		@Test
		@DisplayName("Should return 0 when skipping zero bytes")
		void shouldReturnZeroWhenSkippingZero() {
			try (RandomAccessFileInputStream stream = new RandomAccessFileInputStream(
				RandomAccessFileInputStreamTest.this.raf)) {
				assertEquals(0, stream.skip(0));
				assertEquals(0, stream.read());
			}
		}

		@Test
		@DisplayName("Should return 0 when skipping negative count")
		void shouldReturnZeroWhenSkippingNegative() {
			try (RandomAccessFileInputStream stream = new RandomAccessFileInputStream(
				RandomAccessFileInputStreamTest.this.raf)) {
				stream.seek(5);
				assertEquals(0, stream.skip(-3));
				assertEquals(5, stream.read());
			}
		}

		@Test
		@DisplayName("Should return 0 when already at EOF")
		void shouldReturnZeroWhenAtEof() {
			try (RandomAccessFileInputStream stream = new RandomAccessFileInputStream(
				RandomAccessFileInputStreamTest.this.raf)) {
				stream.seek(TEST_DATA.length);
				assertEquals(0, stream.skip(5));
			}
		}

		@Test
		@DisplayName("Should skip exact remaining bytes to EOF")
		void shouldSkipExactRemainingToEof() {
			try (RandomAccessFileInputStream stream = new RandomAccessFileInputStream(
				RandomAccessFileInputStreamTest.this.raf)) {
				stream.seek(7);
				final long skipped = stream.skip(3);
				assertEquals(3, skipped);
				assertEquals(-1, stream.read());
			}
		}

		@Test
		@DisplayName("Should cap skip at EOF when overshooting")
		void shouldCapSkipAtEofWhenOvershooting() {
			try (RandomAccessFileInputStream stream = new RandomAccessFileInputStream(
				RandomAccessFileInputStreamTest.this.raf)) {
				stream.seek(7);
				// remaining = 3, requesting 10 -> should skip 3
				final long skipped = stream.skip(10);
				assertEquals(3, skipped);
				assertEquals(-1, stream.read());
			}
		}

		@Test
		@DisplayName("Should skip from beginning and overshoot")
		void shouldSkipFromBeginningAndOvershoot() {
			try (RandomAccessFileInputStream stream = new RandomAccessFileInputStream(
				RandomAccessFileInputStreamTest.this.raf)) {
				// remaining = 10, requesting 100 -> should skip 10
				final long skipped = stream.skip(100);
				assertEquals(10, skipped);
				assertEquals(-1, stream.read());
			}
		}

		@Test
		@DisplayName("Should skip past single byte file correctly")
		void shouldSkipPastSingleByteFile() throws IOException {
			final Path oneByteFile = Files.createTempFile("raf-one-", ".bin");
			try {
				Files.write(oneByteFile, new byte[]{42});
				try (
					RandomAccessFile oneRaf = new RandomAccessFile(oneByteFile.toFile(), "r");
					RandomAccessFileInputStream stream = new RandomAccessFileInputStream(oneRaf)
				) {
					// remaining = 1, requesting 5 -> should skip 1
					final long skipped = stream.skip(5);
					assertEquals(1, skipped);
					assertEquals(-1, stream.read());
				}
			} finally {
				Files.deleteIfExists(oneByteFile);
			}
		}

		@Test
		@DisplayName("Should skip 1 byte from position 0")
		void shouldSkipOneByte() {
			try (RandomAccessFileInputStream stream = new RandomAccessFileInputStream(
				RandomAccessFileInputStreamTest.this.raf)) {
				final long skipped = stream.skip(1);
				assertEquals(1, skipped);
				assertEquals(1, stream.read());
			}
		}
	}

	@Nested
	@DisplayName("Available")
	class AvailableTest {

		@Test
		@DisplayName("Should return full length at start")
		void shouldReturnFullLengthAtStart() {
			try (RandomAccessFileInputStream stream = new RandomAccessFileInputStream(
				RandomAccessFileInputStreamTest.this.raf)) {
				assertEquals(TEST_DATA.length, stream.available());
			}
		}

		@SuppressWarnings("ResultOfMethodCallIgnored")
		@Test
		@DisplayName("Should decrease after read")
		void shouldDecreaseAfterRead() {
			try (
				RandomAccessFileInputStream stream =
					new RandomAccessFileInputStream(RandomAccessFileInputStreamTest.this.raf)
			) {
				stream.read();
				stream.read();
				stream.read();
				assertEquals(TEST_DATA.length - 3, stream.available());
			}
		}

		@Test
		@DisplayName("Should return 0 at EOF")
		void shouldReturnZeroAtEof() {
			try (RandomAccessFileInputStream stream = new RandomAccessFileInputStream(
				RandomAccessFileInputStreamTest.this.raf)) {
				stream.seek(TEST_DATA.length);
				assertEquals(0, stream.available());
			}
		}

		@Test
		@DisplayName("Should return 0 for empty file")
		void shouldReturnZeroForEmptyFile() throws IOException {
			final Path emptyFile = Files.createTempFile("raf-empty-", ".bin");
			try {
				try (
					RandomAccessFile emptyRaf = new RandomAccessFile(emptyFile.toFile(), "r");
					RandomAccessFileInputStream stream = new RandomAccessFileInputStream(emptyRaf)
				) {
					assertEquals(0, stream.available());
				}
			} finally {
				Files.deleteIfExists(emptyFile);
			}
		}

		@Test
		@DisplayName("Should reflect position after seek")
		void shouldReflectPositionAfterSeek() {
			try (RandomAccessFileInputStream stream = new RandomAccessFileInputStream(
				RandomAccessFileInputStreamTest.this.raf)) {
				stream.seek(4);
				assertEquals(6, stream.available());
				stream.seek(9);
				assertEquals(1, stream.available());
			}
		}
	}

	@SuppressWarnings("ResultOfMethodCallIgnored")
	@Nested
	@DisplayName("GetLength")
	class GetLengthTest {

		@Test
		@DisplayName("Should return file length for non-empty file")
		void shouldReturnFileLengthForNonEmptyFile() {
			try (RandomAccessFileInputStream stream = new RandomAccessFileInputStream(
				RandomAccessFileInputStreamTest.this.raf)) {
				assertEquals(TEST_DATA.length, stream.getLength());
			}
		}

		@Test
		@DisplayName("Should return 0 for empty file")
		void shouldReturnZeroForEmptyFile() throws IOException {
			final Path emptyFile = Files.createTempFile("raf-empty-", ".bin");
			try {
				try (
					RandomAccessFile emptyRaf = new RandomAccessFile(emptyFile.toFile(), "r");
					RandomAccessFileInputStream stream = new RandomAccessFileInputStream(emptyRaf)
				) {
					assertEquals(0, stream.getLength());
				}
			} finally {
				Files.deleteIfExists(emptyFile);
			}
		}

		@Test
		@DisplayName("Should be independent of current position")
		void shouldBeIndependentOfPosition() {
			try (RandomAccessFileInputStream stream = new RandomAccessFileInputStream(
				RandomAccessFileInputStreamTest.this.raf)) {
				stream.seek(5);
				assertEquals(TEST_DATA.length, stream.getLength());
				stream.read();
				assertEquals(TEST_DATA.length, stream.getLength());
			}
		}
	}

	@SuppressWarnings("ResultOfMethodCallIgnored")
	@Nested
	@DisplayName("Close")
	class CloseTest {

		@Test
		@DisplayName("Should keep RAF open by default")
		void shouldKeepRafOpenByDefault() throws IOException {
			try (RandomAccessFileInputStream stream = new RandomAccessFileInputStream(
				RandomAccessFileInputStreamTest.this.raf)) {
				// just use the stream
				stream.read();
			}
			// RAF should still be usable after stream close (closeOnClose=false)
			RandomAccessFileInputStreamTest.this.raf.seek(0);
			assertNotEquals(-1, RandomAccessFileInputStreamTest.this.raf.read());
		}

		@Test
		@DisplayName("Should close RAF when closeOnClose=true")
		void shouldCloseRafWhenCloseOnCloseTrue() throws IOException {
			final RandomAccessFile localRaf = new RandomAccessFile(
				RandomAccessFileInputStreamTest.this.tempFile.toFile(), "r"
			);
			try (RandomAccessFileInputStream stream = new RandomAccessFileInputStream(localRaf, true)) {
				// just use the stream
				stream.read();
			}
			// try-with-resources called stream.close() which closed localRaf
			assertThrows(IOException.class, localRaf::read);
		}

		@Test
		@DisplayName("Should not close RAF when closeOnClose=false")
		void shouldNotCloseRafWhenCloseOnCloseFalse() throws IOException {
			try (RandomAccessFile localRaf = new RandomAccessFile(
				RandomAccessFileInputStreamTest.this.tempFile.toFile(), "r")) {
				try (RandomAccessFileInputStream stream = new RandomAccessFileInputStream(localRaf, false)) {
					// just use the stream
					stream.read();
				}
				// stream closed but localRaf should still be open
				localRaf.seek(0);
				assertNotEquals(-1, localRaf.read());
			}
		}

		@Test
		@DisplayName("Should handle double close gracefully")
		void shouldHandleDoubleClose() {
			try (RandomAccessFileInputStream stream = new RandomAccessFileInputStream(
				RandomAccessFileInputStreamTest.this.raf)) {
				// explicit first close inside try-with-resources
				assertDoesNotThrow(stream::close);
				// try-with-resources will call close() again — verifying idempotence
			}
		}
	}

	@Nested
	@DisplayName("Error handling")
	class ErrorHandlingTest {

		@Test
		@DisplayName("Should wrap IOException from read into UnexpectedIOException")
		void shouldWrapReadIoException() throws IOException {
			try (RandomAccessFileInputStream stream = new RandomAccessFileInputStream(
				RandomAccessFileInputStreamTest.this.raf)) {
				RandomAccessFileInputStreamTest.this.raf.close();
				assertThrows(UnexpectedIOException.class, stream::read);
			}
		}

		@Test
		@DisplayName("Should wrap IOException from seek into UnexpectedIOException")
		void shouldWrapSeekIoException() throws IOException {
			try (RandomAccessFileInputStream stream = new RandomAccessFileInputStream(
				RandomAccessFileInputStreamTest.this.raf)) {
				RandomAccessFileInputStreamTest.this.raf.close();
				assertThrows(UnexpectedIOException.class, () -> stream.seek(0));
			}
		}

		@Test
		@DisplayName("Should wrap IOException from skip into UnexpectedIOException")
		void shouldWrapSkipIoException() throws IOException {
			try (RandomAccessFileInputStream stream = new RandomAccessFileInputStream(
				RandomAccessFileInputStreamTest.this.raf)) {
				RandomAccessFileInputStreamTest.this.raf.close();
				assertThrows(UnexpectedIOException.class, () -> stream.skip(1));
			}
		}

		@Test
		@DisplayName("Should wrap IOException from availableLong into UnexpectedIOException")
		void shouldWrapAvailableLongIoException() throws IOException {
			try (RandomAccessFileInputStream stream = new RandomAccessFileInputStream(
				RandomAccessFileInputStreamTest.this.raf)) {
				RandomAccessFileInputStreamTest.this.raf.close();
				assertThrows(UnexpectedIOException.class, stream::available);
			}
		}
	}
}
