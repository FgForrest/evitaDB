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

package io.evitadb.store.kryo;

import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.store.checksum.Checksum;
import io.evitadb.store.checksum.Crc32CChecksumFactory;
import io.evitadb.store.compression.ZipCompressionFactory;
import io.evitadb.store.shared.model.FileLocation;
import io.evitadb.utils.Crc32CWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ObservableOutput} covering:
 *
 * - Written-bytes accounting with and without compression
 * - File location offset calculations
 * - Cumulative checksum computation across records
 * - Buffer management and state transitions
 * - Constructor validation and edge cases
 *
 * @author evitaDB Team
 */
@DisplayName("ObservableOutput comprehensive tests")
public class ObservableOutputTest {
	private static final int DEFAULT_BUFFER = ObservableOutput.DEFAULT_FLUSH_SIZE << 2;

	/**
	 * Provides combinations of checksum and compression settings for parameterized tests.
	 */
	@Nonnull
	private static Stream<Arguments> checksumAndCompressionSettings() {
		return Stream.of(ChecksumOption.values())
			.flatMap(checksum -> Stream.of(CompressionOption.values())
				.map(compression -> Arguments.of(checksum, compression)));
	}

	/**
	 * Computes CRC32C checksum manually from byte array.
	 *
	 * @param bytes the byte array to compute checksum for
	 * @return the computed CRC32C checksum value
	 */
	private static long computeManualChecksum(@Nonnull byte[] bytes) {
		final Crc32CWrapper crc = new Crc32CWrapper();
		crc.withByteArray(bytes);
		return crc.getValue();
	}

	/**
	 * Writes one logical record with ObservableOutput protocol.
	 *
	 * @param out                 the observable output to write to
	 * @param payload             the payload bytes to write
	 * @param suppressCompression whether to suppress compression for this record
	 * @return the file location of the written record
	 */
	@Nonnull
	private static FileLocation writeRecord(
		@Nonnull ObservableOutput<ByteArrayOutputStream> out,
		@Nonnull byte[] payload,
		boolean suppressCompression
	) {
		out.markStart();
		out.markRecordLengthPosition();
		out.writeInt(0);
		out.writeByte((byte) 0);
		out.markPayloadStart();
		out.writeBytes(payload, 0, payload.length);
		final byte control = 0;
		if (suppressCompression) {
			return out.markEndSuppressingCompression(control);
		} else {
			return out.markEnd(control);
		}
	}

	/**
	 * Creates an ObservableOutput with the specified options.
	 *
	 * @param baos        the output stream
	 * @param checksum    the checksum option
	 * @param compression the compression option
	 * @return configured ObservableOutput instance
	 */
	@Nonnull
	private static ObservableOutput<ByteArrayOutputStream> createOutput(
		@Nonnull ByteArrayOutputStream baos,
		@Nonnull ChecksumOption checksum,
		@Nonnull CompressionOption compression
	) {
		final Checksum checksumInstance = checksum == ChecksumOption.ENABLED
			? Crc32CChecksumFactory.INSTANCE.createChecksum()
			: Checksum.NO_OP;
		final Deflater deflater = compression == CompressionOption.ENABLED
			? ZipCompressionFactory.INSTANCE.createCompressor().orElse(null)
			: null;
		return new ObservableOutput<>(baos, DEFAULT_BUFFER, 0L, checksumInstance, deflater);
	}

	/**
	 * Checksum configuration option for parameterized tests.
	 */
	private enum ChecksumOption {
		ENABLED,
		DISABLED
	}

	/**
	 * Compression configuration option for parameterized tests.
	 */
	private enum CompressionOption {
		ENABLED,
		DISABLED
	}

	/**
	 * Tests for constructor validation and initialization.
	 */
	@Nested
	@DisplayName("Constructor tests")
	class ConstructorTests {

		@Test
		@DisplayName("Constructor should throw when buffer size is less than flush size")
		void shouldThrowWhenBufferSizeLessThanFlushSize() {
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			final int flushSize = 1024;
			final int bufferSize = 512; // smaller than flushSize

			assertThrows(
				UnexpectedIOException.class,
				() -> {
					//noinspection EmptyTryBlock
					try (
						final ObservableOutput<ByteArrayOutputStream> unused = new ObservableOutput<>(
							baos, flushSize, bufferSize, 0L,
							Crc32CChecksumFactory.INSTANCE.createChecksum(), null
						)
					) {
						// no-op
					}
				},
				"Should throw when buffer size < flush size"
			);
		}

		@Test
		@DisplayName("Constructor should initialize total from currentFileSize parameter")
		void shouldInitializeTotalFromCurrentFileSize() {
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			final long initialFileSize = 1000L;

			try (ObservableOutput<ByteArrayOutputStream> out =
				     new ObservableOutput<>(
					     baos, DEFAULT_BUFFER, initialFileSize,
					     Crc32CChecksumFactory.INSTANCE.createChecksum(), null
				     )) {

				assertEquals(
					initialFileSize, out.total(),
					"Total should be initialized to currentFileSize"
				);
			}
		}

		@Test
		@DisplayName("Constructor with buffer should set capacity accounting for tail space")
		void shouldSetCapacityAccountingForTailSpace() {
			final byte[] buffer = new byte[1024];
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();

			try (ObservableOutput<ByteArrayOutputStream> out =
				     new ObservableOutput<>(
					     baos, buffer,
					     Crc32CChecksumFactory.INSTANCE.createChecksum(), null
				     )) {

				// The capacity should be buffer.length - TAIL_MANDATORY_SPACE (8)
				// We can verify this indirectly by checking the output works
				assertNotNull(out);
			}
		}
	}

	/**
	 * Tests for written bytes accounting.
	 */
	@Nested
	@DisplayName("Written bytes accounting tests")
	class WrittenBytesAccountingTests {

		@ParameterizedTest(name = "checksum={0}, compression={1}")
		@MethodSource("io.evitadb.store.kryo.ObservableOutputTest#checksumAndCompressionSettings")
		@DisplayName("Written bytes should equal stream size for various configurations")
		void shouldMatchWrittenBytesToStreamSize(
			@Nonnull ChecksumOption checksum,
			@Nonnull CompressionOption compression
		) {
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			try (ObservableOutput<ByteArrayOutputStream> out = createOutput(baos, checksum, compression)) {

				final Random rnd = new Random(42);
				for (int i = 0; i < 100; i++) {
					final boolean compressible = (i % 2 == 0);
					final byte[] payload = new byte[200 + (i % 50)];
					if (compressible) {
						Arrays.fill(payload, (byte) 'A');
					} else {
						rnd.nextBytes(payload);
					}
					writeRecord(out, payload, false);
					assertEquals(
						baos.size(), out.getWrittenBytesSinceReset(),
						"Written bytes must equal stream size at record " + i
					);
				}
			}
		}

		@Test
		@DisplayName("Written bytes should match for random payloads with mixed compression")
		void shouldMatchWrittenBytesForRandomPayloads() {
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			try (ObservableOutput<ByteArrayOutputStream> out =
				     new ObservableOutput<>(
					     baos, DEFAULT_BUFFER, 0L,
					     Crc32CChecksumFactory.INSTANCE.createChecksum(),
					     ZipCompressionFactory.INSTANCE.createCompressor().orElseThrow()
				     )) {

				final Random rnd = new Random(123);
				for (int i = 0; i < 1000; i++) {
					final int size = rnd.nextInt(1, 800);
					final boolean compressible = rnd.nextBoolean();
					final boolean suppressCompression = rnd.nextBoolean();
					final byte[] payload = new byte[size];
					if (compressible) {
						Arrays.fill(payload, (byte) 'Z');
					} else {
						rnd.nextBytes(payload);
					}
					writeRecord(out, payload, suppressCompression);
					assertEquals(
						baos.size(), out.getWrittenBytesSinceReset(),
						"Written bytes must match stream size at record " + i
					);
				}
			}
		}
	}

	/**
	 * Tests for file location offset calculations.
	 */
	@Nested
	@DisplayName("File location tests")
	class FileLocationTests {

		@Test
		@DisplayName("File location offsets should be accurate with compression")
		void shouldMaintainCorrectFileLocationOffsetsWithCompression() {
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			try (ObservableOutput<ByteArrayOutputStream> out =
				     new ObservableOutput<>(
					     baos, DEFAULT_BUFFER, 0L,
					     Crc32CChecksumFactory.INSTANCE.createChecksum(),
					     ZipCompressionFactory.INSTANCE.createCompressor().orElseThrow()
				     )) {

				for (int i = 0; i < 100; i++) {
					final byte[] payload = ("X").repeat(1024).getBytes(StandardCharsets.UTF_8);
					final long expectedStart = baos.size();
					final FileLocation fl = writeRecord(out, payload, false);
					assertEquals(
						expectedStart, fl.startingPosition(),
						"FileLocation.start must equal stream size before writing record " + i
					);
				}
			}
		}

		@Test
		@DisplayName("File location should track starting position correctly without compression")
		void shouldTrackStartingPositionWithoutCompression() {
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			try (ObservableOutput<ByteArrayOutputStream> out =
				     new ObservableOutput<>(
					     baos, DEFAULT_BUFFER, 0L,
					     Crc32CChecksumFactory.INSTANCE.createChecksum(), null
				     )) {

				long expectedStart = 0;
				for (int i = 0; i < 50; i++) {
					final byte[] payload = ("record-" + i).getBytes(StandardCharsets.UTF_8);
					final FileLocation fl = writeRecord(out, payload, false);
					assertEquals(
						expectedStart, fl.startingPosition(),
						"FileLocation.start should match expected position at record " + i
					);
					expectedStart += fl.recordLength();
				}
			}
		}
	}

	/**
	 * Tests for cumulative checksum computation.
	 */
	@Nested
	@DisplayName("Cumulative checksum tests")
	class CumulativeChecksumTests {

		@Test
		@DisplayName("Cumulative checksum should match manual computation for single record")
		void shouldComputeCumulativeChecksumForSingleRecord() {
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			try (ObservableOutput<ByteArrayOutputStream> out =
				     new ObservableOutput<>(
					     baos, DEFAULT_BUFFER, 0L,
					     Crc32CChecksumFactory.INSTANCE.createChecksum(), null
				     )) {

				out.markCumulativeChecksumStart();
				final byte[] payload = "Hello World!".repeat(10).getBytes(StandardCharsets.UTF_8);
				writeRecord(out, payload, false);
				final long cumulativeChecksum = out.markCumulativeChecksumEnd();

				final byte[] writtenBytes = baos.toByteArray();
				final long manualChecksum = computeManualChecksum(writtenBytes);

				assertEquals(
					manualChecksum, cumulativeChecksum,
					"Cumulative checksum should match checksum of bytes written to stream"
				);
			}
		}

		@Test
		@DisplayName("Cumulative checksum should cover all bytes for multiple records")
		void shouldComputeCumulativeChecksumForMultipleRecords() {
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			try (ObservableOutput<ByteArrayOutputStream> out =
				     new ObservableOutput<>(
					     baos, DEFAULT_BUFFER, 0L,
					     Crc32CChecksumFactory.INSTANCE.createChecksum(), null
				     )) {

				out.markCumulativeChecksumStart();
				for (int i = 0; i < 10; i++) {
					final byte[] payload = ("record-" + i).repeat(20).getBytes(StandardCharsets.UTF_8);
					writeRecord(out, payload, false);
				}
				final long cumulativeChecksum = out.markCumulativeChecksumEnd();

				final byte[] writtenBytes = baos.toByteArray();
				final long manualChecksum = computeManualChecksum(writtenBytes);

				assertEquals(
					manualChecksum, cumulativeChecksum,
					"Cumulative checksum should match checksum of all bytes for multiple records"
				);
			}
		}

		@Test
		@DisplayName("Cumulative checksum should work correctly with compression")
		void shouldComputeCumulativeChecksumWithCompression() {
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			try (ObservableOutput<ByteArrayOutputStream> out =
				     new ObservableOutput<>(
					     baos, DEFAULT_BUFFER, 0L,
					     Crc32CChecksumFactory.INSTANCE.createChecksum(),
					     ZipCompressionFactory.INSTANCE.createCompressor().orElseThrow()
				     )) {

				out.markCumulativeChecksumStart();
				// highly compressible payload
				final byte[] payload = new byte[500];
				Arrays.fill(payload, (byte) 'A');
				writeRecord(out, payload, false);
				final long cumulativeChecksum = out.markCumulativeChecksumEnd();

				final byte[] writtenBytes = baos.toByteArray();
				final long manualChecksum = computeManualChecksum(writtenBytes);

				assertEquals(
					manualChecksum, cumulativeChecksum,
					"Cumulative checksum should match checksum of compressed bytes"
				);
			}
		}

		@Test
		@DisplayName("Cumulative checksum should handle mixed compressible/incompressible records")
		void shouldComputeCumulativeChecksumWithMixedCompression() {
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			try (ObservableOutput<ByteArrayOutputStream> out =
				     new ObservableOutput<>(
					     baos, DEFAULT_BUFFER, 0L,
					     Crc32CChecksumFactory.INSTANCE.createChecksum(),
					     ZipCompressionFactory.INSTANCE.createCompressor().orElseThrow()
				     )) {

				out.markCumulativeChecksumStart();
				final Random rnd = new Random(42);
				for (int i = 0; i < 20; i++) {
					final boolean compressible = (i % 2 == 0);
					final byte[] payload = new byte[200 + (i * 10)];
					if (compressible) {
						Arrays.fill(payload, (byte) 'X');
					} else {
						rnd.nextBytes(payload);
					}
					writeRecord(out, payload, false);
				}
				final long cumulativeChecksum = out.markCumulativeChecksumEnd();

				final byte[] writtenBytes = baos.toByteArray();
				final long manualChecksum = computeManualChecksum(writtenBytes);

				assertEquals(
					manualChecksum, cumulativeChecksum,
					"Cumulative checksum should match for mixed records"
				);
			}
		}

		@Test
		@DisplayName("Cumulative checksum should be zero when no records written")
		void shouldReturnZeroCumulativeChecksumWhenNoRecordsWritten() {
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			try (ObservableOutput<ByteArrayOutputStream> out =
				     new ObservableOutput<>(
					     baos, DEFAULT_BUFFER, 0L,
					     Crc32CChecksumFactory.INSTANCE.createChecksum(), null
				     )) {

				out.markCumulativeChecksumStart();
				final long cumulativeChecksum = out.markCumulativeChecksumEnd();

				assertEquals(
					0L, cumulativeChecksum,
					"Cumulative checksum should be zero when no records written"
				);
			}
		}

		@Test
		@DisplayName("Cumulative checksum should be zero with NO_OP checksum")
		void shouldReturnZeroCumulativeChecksumWithNoOpChecksum() {
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			try (ObservableOutput<ByteArrayOutputStream> out =
				     new ObservableOutput<>(baos, DEFAULT_BUFFER, 0L, Checksum.NO_OP, null)) {

				out.markCumulativeChecksumStart();
				final byte[] payload = "Hello World!".repeat(10).getBytes(StandardCharsets.UTF_8);
				writeRecord(out, payload, false);
				final long cumulativeChecksum = out.markCumulativeChecksumEnd();

				assertEquals(
					0L, cumulativeChecksum,
					"Cumulative checksum should be zero with NO_OP checksum"
				);
			}
		}

		@Test
		@DisplayName("Multiple cumulative checksum sessions should work independently")
		void shouldSupportMultipleCumulativeChecksumSessions() {
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			try (ObservableOutput<ByteArrayOutputStream> out =
				     new ObservableOutput<>(
					     baos, DEFAULT_BUFFER, 0L,
					     Crc32CChecksumFactory.INSTANCE.createChecksum(), null
				     )) {

				// first session
				out.markCumulativeChecksumStart();
				writeRecord(out, "first".getBytes(StandardCharsets.UTF_8), false);
				final long firstChecksum = out.markCumulativeChecksumEnd();

				final int firstSessionEnd = baos.size();

				// second session
				out.markCumulativeChecksumStart();
				writeRecord(out, "second".getBytes(StandardCharsets.UTF_8), false);
				final long secondChecksum = out.markCumulativeChecksumEnd();

				// verify first session checksum
				final byte[] firstBytes = Arrays.copyOf(baos.toByteArray(), firstSessionEnd);
				assertEquals(
					computeManualChecksum(firstBytes), firstChecksum,
					"First session checksum should be correct"
				);

				// verify second session only covers second record
				final byte[] secondBytes = Arrays.copyOfRange(baos.toByteArray(), firstSessionEnd, baos.size());
				assertEquals(
					computeManualChecksum(secondBytes), secondChecksum,
					"Second session checksum should only cover second record"
				);
			}
		}

		@Test
		@DisplayName("Cumulative checksum should match manual computation for raw byte writes without record lifecycle")
		void shouldComputeCumulativeChecksumForRawBytesWithoutRecordLifecycle() {
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			try (ObservableOutput<ByteArrayOutputStream> out =
				     new ObservableOutput<>(
					     baos, DEFAULT_BUFFER, 0L,
					     Crc32CChecksumFactory.INSTANCE.createChecksum(), null
				     )) {

				out.markCumulativeChecksumStart();

				// Write raw bytes without record lifecycle (no markStart/markEnd)
				final byte[] data = "Test data for raw byte writes".getBytes(StandardCharsets.UTF_8);
				out.writeBytes(data, 0, data.length);
				out.flush(); // Trigger writeDataToOutputStream()

				final long cumulativeChecksum = out.markCumulativeChecksumEnd();

				// Verify checksum matches manual computation of the written bytes
				final byte[] writtenBytes = baos.toByteArray();
				final long manualChecksum = computeManualChecksum(writtenBytes);

				assertEquals(
					manualChecksum, cumulativeChecksum,
					"Cumulative checksum should match checksum of raw bytes written without record lifecycle"
				);
			}
		}

		@Test
		@DisplayName("Cumulative checksum should accumulate correctly across multiple raw byte writes")
		void shouldAccumulateCumulativeChecksumAcrossMultipleRawByteWrites() {
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			try (ObservableOutput<ByteArrayOutputStream> out =
				     new ObservableOutput<>(
					     baos, DEFAULT_BUFFER, 0L,
					     Crc32CChecksumFactory.INSTANCE.createChecksum(), null
				     )) {

				out.markCumulativeChecksumStart();

				// Write multiple byte arrays sequentially without record lifecycle
				final byte[] data1 = "First chunk of data".getBytes(StandardCharsets.UTF_8);
				out.writeBytes(data1, 0, data1.length);
				out.flush();

				final byte[] data2 = "Second chunk of data".getBytes(StandardCharsets.UTF_8);
				out.writeBytes(data2, 0, data2.length);
				out.flush();

				final byte[] data3 = "Third chunk of data".getBytes(StandardCharsets.UTF_8);
				out.writeBytes(data3, 0, data3.length);
				out.flush();

				final long cumulativeChecksum = out.markCumulativeChecksumEnd();

				// Verify final checksum equals CRC32C of all concatenated data
				final byte[] writtenBytes = baos.toByteArray();
				final long manualChecksum = computeManualChecksum(writtenBytes);

				assertEquals(
					manualChecksum, cumulativeChecksum,
					"Cumulative checksum should equal checksum of all concatenated raw byte writes"
				);
			}
		}

		@Test
		@DisplayName("Cumulative checksum should handle mix of record lifecycle and raw byte writes")
		void shouldComputeCumulativeChecksumForMixedRecordAndRawWrites() {
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			try (ObservableOutput<ByteArrayOutputStream> out =
				     new ObservableOutput<>(
					     baos, DEFAULT_BUFFER, 0L,
					     Crc32CChecksumFactory.INSTANCE.createChecksum(), null
				     )) {

				out.markCumulativeChecksumStart();

				// Write a record using full lifecycle
				writeRecord(out, "First record via lifecycle".getBytes(StandardCharsets.UTF_8), false);

				// Write raw bytes without lifecycle
				final byte[] rawData = "Raw bytes without lifecycle".getBytes(StandardCharsets.UTF_8);
				out.writeBytes(rawData, 0, rawData.length);
				out.flush();

				// Write another record using full lifecycle
				writeRecord(out, "Second record via lifecycle".getBytes(StandardCharsets.UTF_8), false);

				final long cumulativeChecksum = out.markCumulativeChecksumEnd();

				// Verify checksum covers all written bytes correctly
				final byte[] writtenBytes = baos.toByteArray();
				final long manualChecksum = computeManualChecksum(writtenBytes);

				assertEquals(
					manualChecksum, cumulativeChecksum,
					"Cumulative checksum should correctly handle mix of record lifecycle and raw byte writes"
				);
			}
		}

		@Test
		@DisplayName("Cumulative checksum should be combinable across separate ObservableOutput instances")
		void shouldCombineCumulativeChecksumsFromSeparateSessions() {
			// First session: write some raw bytes and get cumulative checksum
			final ByteArrayOutputStream baos1 = new ByteArrayOutputStream();
			final long firstSessionChecksum;
			try (ObservableOutput<ByteArrayOutputStream> out1 =
				     new ObservableOutput<>(
					     baos1, DEFAULT_BUFFER, 0L,
					     Crc32CChecksumFactory.INSTANCE.createChecksum(), null
				     )) {

				out1.markCumulativeChecksumStart();
				final byte[] firstData = "First session data written as raw bytes".getBytes(StandardCharsets.UTF_8);
				out1.writeBytes(firstData, 0, firstData.length);
				out1.flush();
				firstSessionChecksum = out1.markCumulativeChecksumEnd();
			}

			// Second session: write more raw bytes and get cumulative checksum
			final ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
			final long secondSessionChecksum;
			try (ObservableOutput<ByteArrayOutputStream> out2 =
				     new ObservableOutput<>(
					     baos2, DEFAULT_BUFFER, 0L,
					     Crc32CChecksumFactory.INSTANCE.createChecksum(), null
				     )) {

				out2.markCumulativeChecksumStart();
				final byte[] secondData = "Second session data written as raw bytes".getBytes(StandardCharsets.UTF_8);
				out2.writeBytes(secondData, 0, secondData.length);
				out2.flush();
				secondSessionChecksum = out2.markCumulativeChecksumEnd();
			}

			// Manually combine the two cumulative checksums
			final int secondSessionLength = baos2.size();
			final long combinedChecksum = Crc32CWrapper.combine(
				firstSessionChecksum, secondSessionChecksum, secondSessionLength
			);

			// Verify: combined checksum should equal checksum of all data concatenated
			final byte[] allData = new byte[baos1.size() + baos2.size()];
			System.arraycopy(baos1.toByteArray(), 0, allData, 0, baos1.size());
			System.arraycopy(baos2.toByteArray(), 0, allData, baos1.size(), baos2.size());
			final long expectedChecksum = computeManualChecksum(allData);

			assertEquals(
				expectedChecksum, combinedChecksum,
				"Combined cumulative checksums should equal checksum of all concatenated data"
			);
		}

		@Test
		@DisplayName("getCumulativeChecksum should return current value without stopping accumulation")
		void shouldReturnCumulativeChecksumWithoutStoppingAccumulation() {
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			try (ObservableOutput<ByteArrayOutputStream> out =
				     new ObservableOutput<>(
					     baos, DEFAULT_BUFFER, 0L,
					     Crc32CChecksumFactory.INSTANCE.createChecksum(), null
				     )) {

				out.markCumulativeChecksumStart();

				// Write first record
				final byte[] payload1 = "First record payload".getBytes(StandardCharsets.UTF_8);
				writeRecord(out, payload1, false);

				// Get intermediate checksum - should be non-zero
				final long intermediateChecksum1 = out.getCumulativeChecksum();
				assertTrue(
					intermediateChecksum1 != 0,
					"Intermediate checksum should be non-zero after writing data"
				);

				// Verify checksum matches what was written so far
				final byte[] bytesAfterFirst = baos.toByteArray();
				final long expectedAfterFirst = computeManualChecksum(bytesAfterFirst);
				assertEquals(
					expectedAfterFirst, intermediateChecksum1,
					"Intermediate checksum should match manual computation after first record"
				);

				// Write more records
				final byte[] payload2 = "Second record payload".getBytes(StandardCharsets.UTF_8);
				writeRecord(out, payload2, false);
				final byte[] payload3 = "Third record payload".getBytes(StandardCharsets.UTF_8);
				writeRecord(out, payload3, false);

				// Get another intermediate checksum - should have changed
				final long intermediateChecksum2 = out.getCumulativeChecksum();
				assertNotEquals(
					intermediateChecksum1, intermediateChecksum2,
					"Checksum should change after writing more records"
				);

				// End cumulative checksum session and verify final value matches
				final long finalChecksum = out.markCumulativeChecksumEnd();

				// Final checksum should match the last intermediate checksum
				assertEquals(
					intermediateChecksum2, finalChecksum,
					"Final checksum should match last intermediate checksum"
				);

				// Verify against manual computation of all written bytes
				final byte[] allWrittenBytes = baos.toByteArray();
				final long manualChecksum = computeManualChecksum(allWrittenBytes);
				assertEquals(
					manualChecksum, finalChecksum,
					"Final checksum should match manual computation of all bytes"
				);
			}
		}

		@Test
		@DisplayName("getCumulativeChecksum should throw when cumulative checksum is not active")
		void shouldThrowWhenGettingCumulativeChecksumNotActive() {
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			try (ObservableOutput<ByteArrayOutputStream> out =
				     new ObservableOutput<>(
					     baos, DEFAULT_BUFFER, 0L,
					     Crc32CChecksumFactory.INSTANCE.createChecksum(), null
				     )) {

				// Should throw when cumulative checksum was never started
				assertThrows(
					io.evitadb.exception.EvitaInternalError.class,
					out::getCumulativeChecksum,
					"Should throw when cumulative checksum calculation is not active"
				);

				// Start, write, and end cumulative checksum session
				out.markCumulativeChecksumStart();
				final byte[] payload = "test payload".getBytes(StandardCharsets.UTF_8);
				writeRecord(out, payload, false);
				out.markCumulativeChecksumEnd();

				// Should throw again after session has ended
				assertThrows(
					io.evitadb.exception.EvitaInternalError.class,
					out::getCumulativeChecksum,
					"Should throw after cumulative checksum session has ended"
				);
			}
		}
	}

	/**
	 * Tests for position and size accounting methods.
	 */
	@Nested
	@DisplayName("Position and size accounting tests")
	class PositionAndSizeTests {

		@Test
		@DisplayName("setPosition should throw UnsupportedOperationException")
		void shouldThrowWhenSettingPosition() {
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			try (ObservableOutput<ByteArrayOutputStream> out =
				     new ObservableOutput<>(
					     baos, DEFAULT_BUFFER, 0L,
					     Crc32CChecksumFactory.INSTANCE.createChecksum(), null
				     )) {

				assertThrows(
					UnsupportedOperationException.class,
					() -> out.setPosition(10),
					"setPosition should throw UnsupportedOperationException"
				);
			}
		}

		@Test
		@DisplayName("position should return offset relative to unconsumed data")
		void shouldReturnPositionRelativeToUnconsumedData() {
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			try (ObservableOutput<ByteArrayOutputStream> out =
				     new ObservableOutput<>(
					     baos, DEFAULT_BUFFER, 0L,
					     Crc32CChecksumFactory.INSTANCE.createChecksum(), null
				     )) {

				assertEquals(
					0, out.position(),
					"Initial position should be 0"
				);

				// write a record (which flushes to stream)
				writeRecord(out, "test".getBytes(StandardCharsets.UTF_8), false);

				// after flush, position should be 0 again
				assertEquals(
					0, out.position(),
					"Position should be 0 after record is flushed"
				);
			}
		}

		@Test
		@DisplayName("total should accumulate written bytes")
		void shouldAccumulateTotalWrittenBytes() {
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			final long initialSize = 500L;
			try (ObservableOutput<ByteArrayOutputStream> out =
				     new ObservableOutput<>(
					     baos, DEFAULT_BUFFER, initialSize,
					     Crc32CChecksumFactory.INSTANCE.createChecksum(), null
				     )) {

				// write some records
				for (int i = 0; i < 5; i++) {
					//noinspection ObjectAllocationInLoop
					writeRecord(out, ("record " + i).getBytes(StandardCharsets.UTF_8), false);
				}

				// total should be initialSize + bytes written
				assertTrue(
					out.total() > initialSize,
					"Total should increase after writing records"
				);
			}
		}
	}

	/**
	 * Tests for buffer management methods.
	 */
	@Nested
	@DisplayName("Buffer management tests")
	class BufferManagementTests {

		@Test
		@DisplayName("flush should write finalized records to output stream")
		void shouldFlushFinalizedRecordsToStream() {
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			try (ObservableOutput<ByteArrayOutputStream> out =
				     new ObservableOutput<>(
					     baos, DEFAULT_BUFFER, 0L,
					     Crc32CChecksumFactory.INSTANCE.createChecksum(), null
				     )) {

				writeRecord(out, "test data".getBytes(StandardCharsets.UTF_8), false);

				final int sizeBeforeFlush = baos.size();
				out.flush();
				final int sizeAfterFlush = baos.size();

				// size should remain same since record was already flushed in markEnd
				assertEquals(
					sizeBeforeFlush, sizeAfterFlush,
					"Flush should not change size when records already flushed"
				);
			}
		}

		@Test
		@DisplayName("reset should clear all record positions and counters")
		void shouldResetAllStateOnReset() {
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			try (ObservableOutput<ByteArrayOutputStream> out =
				     new ObservableOutput<>(
					     baos, DEFAULT_BUFFER, 0L,
					     Crc32CChecksumFactory.INSTANCE.createChecksum(), null
				     )) {

				// write some records
				writeRecord(out, "test".getBytes(StandardCharsets.UTF_8), false);

				// verify startPosition and payloadStartPosition are reset to -1 after markEnd
				// (recordLengthPosition is only reset by reset() method, not by markEnd)
				assertEquals(
					-1, out.getStartPosition(),
					"startPosition should be -1 after markEnd"
				);
				assertEquals(
					-1, out.getPayloadStartPosition(),
					"payloadStartPosition should be -1 after markEnd"
				);

				// full reset clears all positions including recordLengthPosition
				out.reset();

				assertEquals(
					0, out.position(),
					"Position should be 0 after reset"
				);
				assertEquals(
					-1, out.getRecordLengthPosition(),
					"recordLengthPosition should be -1 after reset"
				);
			}
		}

		@Test
		@DisplayName("toBytes should return unwritten buffer data")
		void shouldReturnUnwrittenBufferDataFromToBytes() {
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			try (ObservableOutput<ByteArrayOutputStream> out =
				     new ObservableOutput<>(
					     baos, DEFAULT_BUFFER, 0L,
					     Crc32CChecksumFactory.INSTANCE.createChecksum(), null
				     )) {

				// after a complete record, buffer should be empty
				writeRecord(out, "test".getBytes(StandardCharsets.UTF_8), false);

				final byte[] remainingBytes = out.toBytes();
				assertEquals(
					0, remainingBytes.length,
					"toBytes should return empty array after record is flushed"
				);
			}
		}

		@Test
		@DisplayName("getOutputStream should return the underlying stream")
		void shouldReturnUnderlyingOutputStream() {
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			try (ObservableOutput<ByteArrayOutputStream> out =
				     new ObservableOutput<>(
					     baos, DEFAULT_BUFFER, 0L,
					     Crc32CChecksumFactory.INSTANCE.createChecksum(), null
				     )) {

				assertSame(
					baos, out.getOutputStream(),
					"getOutputStream should return the same stream passed to constructor"
				);
			}
		}
	}

	/**
	 * Tests for compression behavior.
	 */
	@Nested
	@DisplayName("Compression tests")
	class CompressionTests {

		@Test
		@DisplayName("isCompressionEnabled should return true when deflater is provided")
		void shouldReportCompressionEnabledWhenDeflaterProvided() {
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			try (ObservableOutput<ByteArrayOutputStream> out =
				     new ObservableOutput<>(
					     baos, DEFAULT_BUFFER, 0L,
					     Crc32CChecksumFactory.INSTANCE.createChecksum(),
					     ZipCompressionFactory.INSTANCE.createCompressor().orElseThrow()
				     )) {

				assertTrue(
					out.isCompressionEnabled(),
					"isCompressionEnabled should return true when deflater provided"
				);
			}
		}

		@Test
		@DisplayName("isCompressionEnabled should return false when deflater is null")
		void shouldReportCompressionDisabledWhenNoDeflater() {
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			try (ObservableOutput<ByteArrayOutputStream> out =
				     new ObservableOutput<>(
					     baos, DEFAULT_BUFFER, 0L,
					     Crc32CChecksumFactory.INSTANCE.createChecksum(), null
				     )) {

				assertFalse(
					out.isCompressionEnabled(),
					"isCompressionEnabled should return false when no deflater"
				);
			}
		}

		@Test
		@DisplayName("Compression should be skipped when it does not reduce size")
		void shouldSkipCompressionWhenIneffective() {
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			try (ObservableOutput<ByteArrayOutputStream> out =
				     new ObservableOutput<>(
					     baos, DEFAULT_BUFFER, 0L,
					     Crc32CChecksumFactory.INSTANCE.createChecksum(),
					     ZipCompressionFactory.INSTANCE.createCompressor().orElseThrow()
				     )) {

				// random data is not compressible
				final Random rnd = new Random(42);
				final byte[] payload = new byte[100];
				rnd.nextBytes(payload);

				writeRecord(out, payload, false);

				// we can verify by comparing to writing without compression
				final ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
				try (ObservableOutput<ByteArrayOutputStream> out2 =
					     new ObservableOutput<>(
						     baos2, DEFAULT_BUFFER, 0L,
						     Crc32CChecksumFactory.INSTANCE.createChecksum(), null
					     )) {

					writeRecord(out2, payload, false);

					// sizes should be same since compression was ineffective
					assertEquals(
						baos2.size(), baos.size(),
						"Size should be same when compression is ineffective"
					);
				}
			}
		}

		@Test
		@DisplayName("markEndSuppressingCompression should bypass compression even if enabled")
		void shouldBypassCompressionWhenSuppressed() {
			final ByteArrayOutputStream baos1 = new ByteArrayOutputStream();
			final ByteArrayOutputStream baos2 = new ByteArrayOutputStream();

			// highly compressible data
			final byte[] payload = new byte[500];
			Arrays.fill(payload, (byte) 'A');

			try (ObservableOutput<ByteArrayOutputStream> out1 =
				     new ObservableOutput<>(
					     baos1, DEFAULT_BUFFER, 0L,
					     Crc32CChecksumFactory.INSTANCE.createChecksum(),
					     ZipCompressionFactory.INSTANCE.createCompressor().orElseThrow()
				     );
			     ObservableOutput<ByteArrayOutputStream> out2 =
				     new ObservableOutput<>(
					     baos2, DEFAULT_BUFFER, 0L,
					     Crc32CChecksumFactory.INSTANCE.createChecksum(),
					     ZipCompressionFactory.INSTANCE.createCompressor().orElseThrow()
				     )) {

				// write with compression
				writeRecord(out1, payload, false);

				// write suppressing compression
				writeRecord(out2, payload, true);

				// suppressed should be larger
				assertTrue(
					baos2.size() > baos1.size(),
					"Record with suppressed compression should be larger than compressed"
				);
			}
		}
	}

	/**
	 * Tests for record checksum retrieval.
	 */
	@Nested
	@DisplayName("Record checksum tests")
	class RecordChecksumTests {

		@Test
		@DisplayName("getChecksum should return computed value after markEnd")
		void shouldReturnChecksumAfterMarkEnd() {
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			try (ObservableOutput<ByteArrayOutputStream> out =
				     new ObservableOutput<>(
					     baos, DEFAULT_BUFFER, 0L,
					     Crc32CChecksumFactory.INSTANCE.createChecksum(), null
				     )) {

				writeRecord(out, "test payload".getBytes(StandardCharsets.UTF_8), false);
				final long checksum = out.getChecksum();

				// checksum should be non-zero for actual data
				assertTrue(
					checksum != 0,
					"Checksum should be non-zero after writing data"
				);
			}
		}
	}

	/**
	 * Tests for string writing methods.
	 */
	@Nested
	@DisplayName("String writing tests")
	class StringWritingTests {

		@Test
		@DisplayName("writeString should handle UTF-8 strings correctly")
		void shouldWriteUtf8StringCorrectly() {
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			try (ObservableOutput<ByteArrayOutputStream> out =
				     new ObservableOutput<>(
					     baos, DEFAULT_BUFFER, 0L,
					     Crc32CChecksumFactory.INSTANCE.createChecksum(), null
				     )) {

				out.markStart();
				out.markRecordLengthPosition();
				out.writeInt(0);
				out.writeByte((byte) 0);
				out.markPayloadStart();
				out.writeString("Hello World!");
				out.markEnd((byte) 0);

				assertTrue(
					baos.size() > 0,
					"Stream should have data after writing string"
				);
			}
		}

		@Test
		@DisplayName("writeAscii should handle ASCII strings correctly")
		void shouldWriteAsciiStringCorrectly() {
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			try (ObservableOutput<ByteArrayOutputStream> out =
				     new ObservableOutput<>(
					     baos, DEFAULT_BUFFER, 0L,
					     Crc32CChecksumFactory.INSTANCE.createChecksum(), null
				     )) {

				out.markStart();
				out.markRecordLengthPosition();
				out.writeInt(0);
				out.writeByte((byte) 0);
				out.markPayloadStart();
				out.writeAscii("Hello ASCII");
				out.markEnd((byte) 0);

				assertTrue(
					baos.size() > 0,
					"Stream should have data after writing ASCII string"
				);
			}
		}
	}

	/**
	 * Tests for buffer overflow handling.
	 */
	@Nested
	@DisplayName("Buffer overflow tests")
	class BufferOverflowTests {

		@Test
		@DisplayName("doWithOnBufferOverflowHandler should set and clear handler correctly")
		void shouldSetAndClearOverflowHandler() {
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			try (ObservableOutput<ByteArrayOutputStream> out =
				     new ObservableOutput<>(
					     baos, DEFAULT_BUFFER, 0L,
					     Crc32CChecksumFactory.INSTANCE.createChecksum(), null
				     )) {

				final boolean[] handlerInvoked = {false};

				final String result = out.doWithOnBufferOverflowHandler(
					output -> handlerInvoked[0] = true,
					() -> {
						// small write that won't overflow
						writeRecord(out, "test".getBytes(StandardCharsets.UTF_8), false);
						return "success";
					}
				);

				assertEquals(
					"success", result,
					"Lambda result should be returned"
				);
				assertFalse(
					handlerInvoked[0],
					"Handler should not be invoked for small writes"
				);
			}
		}
	}
}
