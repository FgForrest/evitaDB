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

package io.evitadb.store.traffic.stream;

import io.evitadb.stream.RandomAccessFileInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.STREAM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies the ring-buffer wrap-around semantics of {@link RingBufferInputStream}. The
 * stream sits on top of a fixed-size, physically circular region of a file: once a read reaches the
 * physical end of the region it must transparently continue from the beginning, so that a logical
 * record that straddles the wrap boundary reassembles correctly.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("RingBufferInputStream")
@Tag(STORAGE)
@Tag(STREAM)
class RingBufferInputStreamTest {
	/** Size of the physical ring-buffer region used across the tests. */
	private static final int BUFFER_SIZE = 16;
	private Path tempFile;
	private RandomAccessFile randomAccessFile;

	/**
	 * Creates a backing file of exactly {@link #BUFFER_SIZE} bytes, filled with a distinct,
	 * position-encoding pattern (`byte[i] = i + 1`) so that any read landing on the wrong physical
	 * offset (or past the end of the region) is immediately detectable.
	 */
	@BeforeEach
	void setUp() throws IOException {
		this.tempFile = Files.createTempFile("RingBufferInputStreamTest", ".bin");
		final byte[] pattern = new byte[BUFFER_SIZE];
		for (int i = 0; i < BUFFER_SIZE; i++) {
			pattern[i] = (byte) (i + 1);
		}
		Files.write(this.tempFile, pattern);
		this.randomAccessFile = new RandomAccessFile(this.tempFile.toFile(), "r");
	}

	@AfterEach
	void tearDown() throws IOException {
		if (this.randomAccessFile != null) {
			this.randomAccessFile.close();
		}
		Files.deleteIfExists(this.tempFile);
	}

	/**
	 * Expected byte value produced by an ideal ring buffer when reading the {@code k}-th byte
	 * (0-based) starting at {@code startPosition}.
	 */
	private static int expectedRingByte(int startPosition, int k) {
		final int offset = (startPosition + k) % BUFFER_SIZE;
		return (offset + 1) & 0xFF;
	}

	private RingBufferInputStream openStream(int startPosition) {
		return new RingBufferInputStream(
			new RandomAccessFileInputStream(this.randomAccessFile),
			BUFFER_SIZE,
			startPosition
		);
	}

	@Test
	@DisplayName("Should read single bytes across multiple wraps without hitting end of file")
	void shouldReadSingleBytesAcrossMultipleWraps() throws IOException {
		try (RingBufferInputStream stream = openStream(0)) {
			// read more than two full laps so a wrap-boundary off-by-one surfaces on the SECOND wrap
			final int totalToRead = (2 * BUFFER_SIZE) + 3;
			for (int k = 0; k < totalToRead; k++) {
				final int actual = stream.read();
				assertEquals(
					expectedRingByte(0, k), actual,
					"single-byte read #" + k + " returned the wrong ring byte (wrap boundary handling)"
				);
			}
		}
	}

	@Test
	@DisplayName("Should continue reading correctly after a bulk read that wraps the buffer end")
	void shouldContinueCorrectlyAfterWrappingBulkRead() throws IOException {
		final int startPosition = BUFFER_SIZE - 3;
		try (RingBufferInputStream stream = openStream(startPosition)) {

			// a bulk read whose range straddles the physical end of the region
			final byte[] bulk = new byte[6];
			readFully(stream, bulk);
			for (int i = 0; i < bulk.length; i++) {
				assertEquals(
					expectedRingByte(startPosition, i), bulk[i] & 0xFF,
					"wrapping bulk read produced the wrong byte at index " + i
				);
			}

			// continue reading a further two full laps; the bug leaves `position` desynced after the
			// wrapping bulk read, so subsequent reads wrap late and eventually read past the buffer end
			for (int k = bulk.length; k < bulk.length + (2 * BUFFER_SIZE); k++) {
				final int actual = stream.read();
				assertEquals(
					expectedRingByte(startPosition, k), actual,
					"read #" + k + " after a wrapping bulk read returned the wrong ring byte"
				);
			}
		}
	}

	@Test
	@DisplayName("Should read correctly through repeated wrapping bulk reads")
	void shouldReadCorrectlyThroughRepeatedWrappingBulkReads() throws IOException {
		try (RingBufferInputStream stream = openStream(0)) {
			final int chunk = 5; // does not divide BUFFER_SIZE, so wrap offsets shift every lap
			final int chunks = 8; // 40 bytes => 2.5 laps
			int produced = 0;
			for (int c = 0; c < chunks; c++) {
				final byte[] buffer = new byte[chunk];
				readFully(stream, buffer);
				for (int i = 0; i < chunk; i++) {
					assertEquals(
						expectedRingByte(0, produced), buffer[i] & 0xFF,
						"chunked bulk read produced the wrong byte at global index " + produced
					);
					produced++;
				}
			}
		}
	}

	@Test
	@DisplayName("Should seek the delegate when skipping within the buffer without wrapping")
	void shouldSeekDelegateWhenSkippingWithoutWrap() throws IOException {
		try (RingBufferInputStream stream = openStream(0)) {
			final long skipped = stream.skip(5L);
			assertEquals(5L, skipped, "skip should report the number of bytes skipped");
			// after skipping 5 bytes from offset 0 the next byte read must be the one at offset 5
			assertEquals(
				expectedRingByte(0, 5), stream.read(),
				"read after a non-wrapping skip must come from the skipped-to offset"
			);
		}
	}

	@Test
	@DisplayName("Should seek the delegate when skipNBytes moves within the buffer without wrapping")
	void shouldSeekDelegateWhenSkipNBytesWithoutWrap() throws IOException {
		try (RingBufferInputStream stream = openStream(0)) {
			stream.skipNBytes(5L);
			assertEquals(
				expectedRingByte(0, 5), stream.read(),
				"read after a non-wrapping skipNBytes must come from the skipped-to offset"
			);
		}
	}

	@Test
	@DisplayName("Should reset to the beginning when a bulk read starts exactly on the physical end")
	void shouldResetToBeginningWhenBulkReadStartsExactlyOnPhysicalEnd() throws IOException {
		try (RingBufferInputStream stream = openStream(0)) {
			// a first bulk read that fills the region exactly leaves `position` sitting on BUFFER_SIZE
			final byte[] firstLap = new byte[BUFFER_SIZE];
			readFully(stream, firstLap);
			// the next bulk read enters with `position == BUFFER_SIZE` and must transparently restart at 0
			final byte[] afterWrap = new byte[3];
			readFully(stream, afterWrap);
			for (int i = 0; i < afterWrap.length; i++) {
				assertEquals(
					expectedRingByte(0, i), afterWrap[i] & 0xFF,
					"bulk read entering exactly on the physical end must restart from offset 0 at index " + i
				);
			}
		}
	}

	@Test
	@DisplayName("Should wrap the position modulo the buffer size when skip runs past the end")
	void shouldWrapPositionWhenSkipExceedsBufferEnd() throws IOException {
		try (RingBufferInputStream stream = openStream(BUFFER_SIZE - 2)) {
			// skipping 5 bytes from offset BUFFER_SIZE-2 lands 3 bytes past the end -> wrapped offset 3
			final long skipped = stream.skip(5L);
			assertEquals(5L, skipped, "skip must report the requested number of bytes even when it wraps");
			assertEquals(
				expectedRingByte(BUFFER_SIZE - 2, 5), stream.read(),
				"read after a wrapping skip must come from the wrapped-to offset"
			);
		}
	}

	@Test
	@DisplayName("Should wrap the position modulo the buffer size when skipNBytes runs past the end")
	void shouldWrapPositionWhenSkipNBytesExceedsBufferEnd() throws IOException {
		try (RingBufferInputStream stream = openStream(BUFFER_SIZE - 2)) {
			// skipping 5 bytes from offset BUFFER_SIZE-2 lands 3 bytes past the end -> wrapped offset 3
			stream.skipNBytes(5L);
			assertEquals(
				expectedRingByte(BUFFER_SIZE - 2, 5), stream.read(),
				"read after a wrapping skipNBytes must come from the wrapped-to offset"
			);
		}
	}

	@Test
	@DisplayName("Should always report the full ring-buffer size as available")
	void shouldReportBufferSizeAsAvailable() throws IOException {
		try (RingBufferInputStream stream = openStream(BUFFER_SIZE - 1)) {
			// `available` reflects the region size, not the physical remainder to the end of the file
			assertEquals(BUFFER_SIZE, stream.available(), "available must report the whole ring-buffer region size");
		}
	}

	@Test
	@DisplayName("Should delegate mark/markSupported to the backing stream")
	void shouldDelegateMarkAndMarkSupportedToBackingStream() throws IOException {
		try (RingBufferInputStream stream = openStream(0)) {
			// the random-access backing stream inherits InputStream's default (mark unsupported); the ring
			// buffer must faithfully mirror that contract rather than claim mark support of its own
			assertFalse(stream.markSupported(), "mark support must be delegated to the backing stream");
			// mark is a no-op on the backing stream and must not throw
			stream.mark(8);
			assertEquals(
				expectedRingByte(0, 0), stream.read(),
				"a no-op mark must not disturb the read position"
			);
		}
	}

	@Test
	@DisplayName("Should throw when reset is not supported by the backing stream")
	void shouldThrowWhenResetIsNotSupportedByBackingStream() throws IOException {
		try (RingBufferInputStream stream = openStream(0)) {
			// reset is delegated; the backing stream does not support it, so the call must surface an IOException
			assertThrows(
				IOException.class, stream::reset,
				"reset must propagate the backing stream's unsupported-operation failure"
			);
		}
	}

	@Test
	@DisplayName("Should reject transferTo as an unsupported operation")
	void shouldRejectTransferTo() throws IOException {
		try (
			final RingBufferInputStream stream = openStream(0);
			final OutputStream sink = OutputStream.nullOutputStream();
		) {
			// transferTo would bypass the ring-buffer wrap logic, so it is explicitly refused
			assertThrows(
				UnsupportedEncodingException.class, () -> stream.transferTo(sink),
				"transferTo must be rejected because it cannot honour the ring-buffer wrap semantics"
			);
		}
	}

	@Test
	@DisplayName("Should return a short tail read when the backing file ends before the region end")
	void shouldReturnShortTailReadWhenBackingFileEndsBeforeRegionEnd() throws IOException {
		// a pathological region whose declared size (BUFFER_SIZE) overshoots the physical file length:
		// a straddling read must return the (short) tail segment the delegate actually produced rather
		// than fabricate bytes past the physical end of file
		final Path shortFile = Files.createTempFile("RingBufferInputStreamTest-short", ".bin");
		try (RandomAccessFile shortRandomAccessFile = new RandomAccessFile(shortFile.toFile(), "rw")) {
			shortRandomAccessFile.setLength(10L);
			try (
				RingBufferInputStream stream = new RingBufferInputStream(
					new RandomAccessFileInputStream(shortRandomAccessFile), BUFFER_SIZE, BUFFER_SIZE - 4
				)
			) {
				// position BUFFER_SIZE-4 (12) + len 6 straddles the region end; the head-of-region tail read
				// starts at physical offset 12 which is already past the 10-byte file -> delegate returns -1
				final byte[] buffer = new byte[6];
				final int read = stream.read(buffer, 0, buffer.length);
				assertTrue(
					read <= 0,
					"a straddling read whose tail begins past physical EOF must return the short delegate result"
				);
			}
		} finally {
			Files.deleteIfExists(shortFile);
		}
	}

	/**
	 * Reads exactly {@code buffer.length} bytes, looping over partial reads and failing the test if
	 * the stream reports end-of-file prematurely (which is itself a symptom of a wrap-handling bug).
	 */
	private static void readFully(RingBufferInputStream stream, byte[] buffer) throws IOException {
		int read = 0;
		while (read < buffer.length) {
			final int r = stream.read(buffer, read, buffer.length - read);
			assertTrue(
				r > 0,
				"stream reported end-of-file (" + r + ") before " + buffer.length + " bytes were read"
			);
			read += r;
		}
	}
}
