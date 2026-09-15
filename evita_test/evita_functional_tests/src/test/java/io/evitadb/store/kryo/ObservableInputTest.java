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

package io.evitadb.store.kryo;

import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.store.checksum.Crc32CChecksumFactory;
import io.evitadb.store.compression.ZipCompressionFactory;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.stream.RandomAccessFileInputStream;
import io.evitadb.utils.BitUtils;
import io.evitadb.utils.Crc32CWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.SERIALIZATION;

/**
 * This test verifies {@link ObservableInput} behaviour.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("ObservableInput tests")
@SuppressWarnings("ResultOfMethodCallIgnored")
@Tag(STORAGE)
@Tag(SERIALIZATION)
class ObservableInputTest extends AbstractObservableInputOutputTest {
	private static final int REPETITIONS = 50;
	private static final int BIG_PAYLOAD_SIZE = PAYLOAD_SIZE * REPETITIONS;

	/**
	 * Computes manual cumulative CRC32C checksum over all record bytes.
	 *
	 * @param recordBytes the complete byte array containing one or more records
	 * @return the computed cumulative CRC32C checksum
	 */
	private static long computeManualCumulativeChecksum(@Nonnull byte[] recordBytes) {
		final Crc32CWrapper checksum = new Crc32CWrapper();
		checksum.withByteArray(recordBytes);
		return checksum.getValue();
	}

	/**
	 * Creates an ObservableInput instance from a byte array without compression support.
	 *
	 * @param bytes      the byte array containing record data
	 * @param bufferSize the size of the internal buffer for the input
	 * @return a new ObservableInput instance configured for reading the byte array
	 */
	@Nonnull
	private static ObservableInput<?> createObservableInputFromBytes(
		@Nonnull byte[] bytes,
		int bufferSize
	) {
		final ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
		return new ObservableInput<>(
			bais, bufferSize,
			Crc32CChecksumFactory.INSTANCE.createCumulativeChecksum(0L),
			null
		);
	}

	/**
	 * Creates an ObservableInput instance from a byte array using a {@link TrickleInputStream}
	 * that limits the number of bytes returned per read call. This forces partial buffer fills,
	 * causing `limit < capacity` in the internal buffer.
	 *
	 * @param bytes           the byte array containing record data
	 * @param bufferSize      the size of the internal buffer for the input
	 * @param maxBytesPerRead the maximum number of bytes the stream returns per read call
	 * @return a new ObservableInput instance configured for reading from a trickle stream
	 */
	@Nonnull
	private static ObservableInput<?> createObservableInputFromTrickleStream(
		@Nonnull byte[] bytes,
		int bufferSize,
		int maxBytesPerRead
	) {
		final TrickleInputStream trickle = new TrickleInputStream(
			new ByteArrayInputStream(bytes), maxBytesPerRead
		);
		return new ObservableInput<>(
			trickle, bufferSize,
			Crc32CChecksumFactory.INSTANCE.createCumulativeChecksum(0L),
			null
		);
	}

	/**
	 * Creates an ObservableInput instance from a byte array with compression support.
	 *
	 * @param bytes      the byte array containing compressed record data
	 * @param bufferSize the size of the internal buffer for the input
	 * @return a new ObservableInput instance configured for reading compressed data
	 */
	@Nonnull
	private static ObservableInput<?> createObservableInputFromBytesWithCompression(
		@Nonnull byte[] bytes,
		int bufferSize
	) {
		final ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
		return new ObservableInput<>(
			bais, bufferSize,
			Crc32CChecksumFactory.INSTANCE.createCumulativeChecksum(0L),
			ZipCompressionFactory.INSTANCE.createDecompressor().orElseThrow()
		);
	}

	/**
	 * Writes records to a ByteArrayOutputStream and returns the resulting byte array.
	 *
	 * @param bufferSize   the size of the output buffer
	 * @param recordWriter a consumer that writes records to the provided Output
	 * @return the byte array containing all written records
	 */
	@Nonnull
	private static byte[] writeRecordsAndGetBytes(
		int bufferSize,
		@Nonnull java.util.function.Consumer<Output> recordWriter
	) {
		final ByteArrayOutputStream baos = new ByteArrayOutputStream(bufferSize);
		final Output output = new Output(baos, bufferSize);
		recordWriter.accept(output);
		return baos.toByteArray();
	}

	/**
	 * Reads a compressed record from the input and verifies it matches the expected payload.
	 *
	 * @param input           the ObservableInput to read from
	 * @param expectedSize    the expected size of the uncompressed payload
	 * @param expectedPayload the expected payload bytes after decompression
	 */
	private static void readCompressedRecordAndVerify(
		@Nonnull ObservableInput<?> input,
		int expectedSize,
		@Nonnull byte[] expectedPayload
	) {
		input.markStart();
		final int length = input.readInt();
		final byte controlByte = input.readByte();
		input.markPayloadStart(length, controlByte);

		assertTrue(BitUtils.isBitSet(controlByte, StorageRecord.COMPRESSION_BIT));

		final byte[] readPayload = input.readBytes(expectedSize);
		input.markEnd(controlByte);

		assertArrayEquals(expectedPayload, readPayload);
	}

	/**
	 * Creates a compressible payload by repeating a pattern.
	 *
	 * @param totalLength the total length of the payload to create
	 * @return a byte array with highly compressible content (repeated pattern)
	 */
	private byte[] createCompressiblePayload(int totalLength) {
		final byte[] pattern = generateBytes(Math.min(PAYLOAD_SIZE, totalLength));
		final byte[] result = new byte[totalLength];
		for (int i = 0; i < totalLength; i++) {
			result[i] = pattern[i % pattern.length];
		}
		return result;
	}

	/**
	 * Verifies that the cumulative checksum for a single record matches the manually computed checksum.
	 *
	 * @param payloadSize the size of the payload in the record
	 * @param recordBytes the complete byte array containing the record
	 */
	private void verifyCumulativeChecksumForRecord(
		@SuppressWarnings("SameParameterValue") int payloadSize,
		@Nonnull byte[] recordBytes
	) {
		final ObservableInput<?> input = createObservableInputFromBytes(
			recordBytes, RECORD_SIZE
		);

		input.markCumulativeChecksumStart();
		readAndVerifyRecord(input, payloadSize);
		final long cumulativeChecksum = input.markCumulativeChecksumEnd();

		final long manualChecksum = computeManualCumulativeChecksum(recordBytes);
		assertEquals(manualChecksum, cumulativeChecksum);
	}

	/**
	 * Creates a compressed record with compressible payload and returns the byte array.
	 *
	 * @param payloadSize the size of the payload to compress
	 * @return the byte array containing the compressed record
	 */
	private byte[] createCompressedRecordBytes(@SuppressWarnings("SameParameterValue") int payloadSize) {
		final int bufferSize = payloadSize + OVERHEAD_SIZE + 128;
		final ByteArrayOutputStream baos = new ByteArrayOutputStream(bufferSize);
		final Output output = new Output(baos, bufferSize);
		final Deflater deflater = ZipCompressionFactory.INSTANCE.createCompressor().orElseThrow();

		final byte[] payload = createCompressiblePayload(payloadSize);
		writeCompressedRecord(output, payload, deflater);

		return baos.toByteArray();
	}

	@Nested
	@DisplayName("Uncompressed record tests")
	class UncompressedRecordTests {

		@DisplayName("Record written by standard Kryo output, should be read intact.")
		@Test
		void shouldReadRecord() {
			final byte[] recordBytes = writeRecordsAndGetBytes(
				RECORD_SIZE,
				output -> writeRandomRecord(output, PAYLOAD_SIZE)
			);

			final ObservableInput<?> input = createObservableInputFromBytes(recordBytes, 24);

			readAndVerifyRecord(input, PAYLOAD_SIZE);
		}

		@DisplayName("Multiple random records of same size written by standard Kryo output, should be read intact.")
		@Test
		void shouldReadMultipleRandomRecords() {
			final int count = 512;
			final byte[] recordBytes = writeRecordsAndGetBytes(
				RECORD_SIZE, output -> {
					for (int i = 0; i < count; i++) {
						writeRandomRecord(output, PAYLOAD_SIZE);
					}
				}
			);

			final ObservableInput<?> input = createObservableInputFromBytes(recordBytes, 24);

			for (int i = 0; i < count; i++) {
				readAndVerifyRecord(input, PAYLOAD_SIZE);
			}
		}

		@DisplayName("Multiple records of ransom size written by standard Kryo output, should be read intact.")
		@Test
		void shouldReadMultipleRandomRecordsOfDifferentSizes() {
			final byte[] recordBytes = writeRecordsAndGetBytes(
				256, output -> {
					writeRandomRecord(output, 77);
					writeRandomRecord(output, 256);
					writeRandomRecord(output, 189);
				}
			);

			final ObservableInput<?> input = createObservableInputFromBytes(recordBytes, 24);

			readAndVerifyRecord(input, 77);
			readAndVerifyRecord(input, 256);
			readAndVerifyRecord(input, 189);
		}

		@DisplayName("Records read by RandomAccessFile in random order should be intact.")
		@Test
		void shouldReadMultipleRandomRecordsInRandomFashionOfDifferentSizes() throws IOException {
			final Path targetFile = Path.of(System.getProperty("java.io.tmpdir") + File.separator + "test.kryo");
			final File targetFileDescription = targetFile.toFile();
			targetFileDescription.delete();

			try {
				// Write phase
				final long s1;
				final long s2;
				final long s3;
				try (final FileOutputStream fos = new FileOutputStream(targetFileDescription)) {
					final Output output = new Output(fos, 256);
					s1 = writeRandomRecord(output, 77);
					s2 = writeRandomRecord(output, 256);
					s3 = writeRandomRecord(output, 189);
					output.close();
				}

				// Read phase
				try (final RandomAccessFile raf = new RandomAccessFile(targetFileDescription, "r")) {
					final ObservableInput<RandomAccessFileInputStream> input = new ObservableInput<>(
						new RandomAccessFileInputStream(raf),
						24, Crc32CChecksumFactory.INSTANCE.createCumulativeChecksum(0L), null
					);

					seekReadAndVerifyRecord(input, s2, 256);
					seekReadAndVerifyRecord(input, s1, 77);
					seekReadAndVerifyRecord(input, s3, 189);
				}
			} finally {
				targetFileDescription.delete();
			}
		}

		@DisplayName("Multiple records written by standard Kryo output, should be read intact.")
		@Test
		void shouldReadMultipleRandomRecordsOfRandomSizes() {
			final int count = 512;
			final List<Integer> sizes = new ArrayList<>(count);
			final byte[] recordBytes = writeRecordsAndGetBytes(
				RECORD_SIZE, output -> {
					for (int i = 0; i < count; i++) {
						final int rndSize = ObservableInputTest.this.random.nextInt(9999) + 1;
						writeRandomRecord(output, rndSize);
						sizes.add(rndSize);
					}
				}
			);

			final ObservableInput<?> input = createObservableInputFromBytes(recordBytes, 24);

			for (Integer size : sizes) {
				readAndVerifyRecord(input, size);
			}
		}

		@DisplayName("Single large record written by standard Kryo output, should be read intact.")
		@Test
		void shouldReadRecordLargerThanBuffer() {
			final byte[] recordBytes = writeRecordsAndGetBytes(
				RECORD_SIZE << 2,
				output -> writeRandomRecord(output, RECORD_SIZE << 2)
			);

			final ObservableInput<?> input = createObservableInputFromBytes(recordBytes, 24);

			readAndVerifyRecord(input, RECORD_SIZE << 2);
		}

		@DisplayName("Cumulative checksum calculated for single record should match manually computed value")
		@Test
		void shouldCalculateCumulativeChecksumForSingleRecord() {
			final byte[] recordBytes = writeRecordsAndGetBytes(
				RECORD_SIZE,
				output -> writeRandomRecord(output, PAYLOAD_SIZE)
			);

			verifyCumulativeChecksumForRecord(PAYLOAD_SIZE, recordBytes);
		}

		@DisplayName("Cumulative checksum for multiple records should include all bytes")
		@Test
		void shouldCalculateCumulativeChecksumForMultipleRecords() {
			final int[] payloadSizes = {77, 256, 189};
			final byte[] allRecordBytes = writeRecordsAndGetBytes(
				256, output -> {
					for (int size : payloadSizes) {
						writeRandomRecord(output, size);
					}
				}
			);

			final ObservableInput<?> input = createObservableInputFromBytes(allRecordBytes, 24);

			// read all records with cumulative checksum enabled
			int start = 0;
			for (int size : payloadSizes) {
				input.markCumulativeChecksumStart();
				readAndVerifyRecord(input, size);
				final long cumulativeChecksum = input.markCumulativeChecksumEnd();

				// manually compute CRC32C over entire byte array
				final int nextStart = start + size + OVERHEAD_SIZE;
				final long manualChecksum = computeManualCumulativeChecksum(
					Arrays.copyOfRange(allRecordBytes, start, nextStart)
				);
				assertEquals(
					manualChecksum, cumulativeChecksum,
					"Cumulative checksum mismatch for record with payload size " + size
				);
				start = nextStart;
			}
		}

		@DisplayName("Should throw exception when marking cumulative checksum start while reading payload")
		@Test
		void shouldThrowWhenMarkCumulativeChecksumStartCalledWhileReadingPayload() {
			final ByteArrayOutputStream baos = new ByteArrayOutputStream(RECORD_SIZE);
			final Output output = new Output(baos, RECORD_SIZE);

			writeRandomRecord(output, PAYLOAD_SIZE);

			final ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
			try (
				final ObservableInput<?> input = new ObservableInput<>(
					bais, 24, Crc32CChecksumFactory.INSTANCE.createCumulativeChecksum(0L), null
				)
			) {
				// start reading a record
				input.markStart();
				final int length = input.readInt();
				final byte controlByte = input.readByte();
				input.markPayloadStart(length, controlByte);


				// now we are in the middle of reading payload - markCumulativeChecksumStart should throw
				assertThrows(
					GenericEvitaInternalError.class,
					input::markCumulativeChecksumStart
				);
			}
		}

		@DisplayName("Different record contents should produce different cumulative checksums")
		@Test
		void shouldReturnDifferentCumulativeChecksumsForDifferentContent() {
			final int payloadSize = PAYLOAD_SIZE;

			// create first record with specific payload
			final byte[] payload1 = new byte[payloadSize];
			for (int i = 0; i < payloadSize; i++) {
				payload1[i] = (byte) i;
			}
			final ByteArrayOutputStream baos1 = new ByteArrayOutputStream(RECORD_SIZE);
			final Output output1 = new Output(baos1, RECORD_SIZE);
			writeRecord(null, output1, payloadSize, payload1);

			// create second record with different payload
			final byte[] payload2 = new byte[payloadSize];
			for (int i = 0; i < payloadSize; i++) {
				payload2[i] = (byte) (payloadSize - i);
			}
			final ByteArrayOutputStream baos2 = new ByteArrayOutputStream(RECORD_SIZE);
			final Output output2 = new Output(baos2, RECORD_SIZE);
			writeRecord(null, output2, payloadSize, payload2);

			// read first record with cumulative checksum
			final ByteArrayInputStream bais1 = new ByteArrayInputStream(baos1.toByteArray());
			final ObservableInput<?> input1 = new ObservableInput<>(
				bais1, 24, Crc32CChecksumFactory.INSTANCE.createCumulativeChecksum(0L), null
			);
			input1.markCumulativeChecksumStart();
			readAndVerifyRecord(input1, payloadSize);
			final long checksum1 = input1.markCumulativeChecksumEnd();

			// read second record with cumulative checksum
			final ByteArrayInputStream bais2 = new ByteArrayInputStream(baos2.toByteArray());
			final ObservableInput<?> input2 = new ObservableInput<>(
				bais2, 24, Crc32CChecksumFactory.INSTANCE.createCumulativeChecksum(0L), null
			);
			input2.markCumulativeChecksumStart();
			readAndVerifyRecord(input2, payloadSize);
			final long checksum2 = input2.markCumulativeChecksumEnd();

			// checksums should be different
			assertNotEquals(checksum1, checksum2);
		}

		@DisplayName("Cumulative checksum should work correctly with RandomAccessFileInputStream")
		@Test
		void shouldCalculateCumulativeChecksumWithRandomAccessFileInputStream() throws IOException {
			final Path targetFile = Path.of(System.getProperty("java.io.tmpdir") + File.separator + "test_cumulative.kryo");
			final File targetFileDescr = targetFile.toFile();
			targetFileDescr.delete();

			try {
				final int[] payloadSizes = {77, 256, 189};

				// Write phase
				try (final FileOutputStream fos = new FileOutputStream(targetFileDescr)) {
					final Output output = new Output(fos, 256);
					for (int size : payloadSizes) {
						writeRandomRecord(output, size);
					}
					output.close();
				}

				// Read all bytes for manual checksum computation
				final byte[] allRecordBytes = Files.readAllBytes(targetFile);

				// Read phase with RandomAccessFileInputStream and cumulative checksum
				try (final RandomAccessFile raf = new RandomAccessFile(targetFileDescr, "r")) {
					final ObservableInput<RandomAccessFileInputStream> input = new ObservableInput<>(
						new RandomAccessFileInputStream(raf),
						24, Crc32CChecksumFactory.INSTANCE.createCumulativeChecksum(0L), null
					);

					input.markCumulativeChecksumStart();
					for (int size : payloadSizes) {
						readAndVerifyRecord(input, size);
					}
					final long cumulativeChecksum = input.markCumulativeChecksumEnd();

					// Manually compute CRC32C over entire byte array
					final long manualChecksum = computeManualCumulativeChecksum(allRecordBytes);

					assertEquals(manualChecksum, cumulativeChecksum);
				}
			} finally {
				targetFileDescr.delete();
			}
		}
	}

	@Nested
	@DisplayName("Compressed record tests")
	class CompressedRecordTests {

		@DisplayName("Single compressed record written with plain Kryo Output should be read intact")
		@Test
		void shouldReadSingleCompressedRecord() {
			final int bufferSize = BIG_PAYLOAD_SIZE + OVERHEAD_SIZE + 128;
			final byte[] originalPayload = createCompressiblePayload(BIG_PAYLOAD_SIZE);
			final byte[] recordBytes = writeRecordsAndGetBytes(
				bufferSize, output -> {
					final Deflater deflater = ZipCompressionFactory.INSTANCE.createCompressor().orElseThrow();
					writeCompressedRecord(output, originalPayload, deflater);
				}
			);

			final ObservableInput<?> input = createObservableInputFromBytesWithCompression(recordBytes, bufferSize);

			readCompressedRecordAndVerify(input, BIG_PAYLOAD_SIZE, originalPayload);
		}

		@DisplayName("Multiple compressed records of different sizes should be read intact sequentially")
		@Test
		void shouldReadMultipleCompressedRecordsOfDifferentSizes() {
			final int bufferSize = 8192;
			final int[] payloadSizes = {200, 500, 300};
			final byte[][] originalPayloads = new byte[payloadSizes.length][];

			final byte[] recordBytes = writeRecordsAndGetBytes(
				bufferSize, output -> {
					final Deflater deflater = ZipCompressionFactory.INSTANCE.createCompressor().orElseThrow();
					for (int i = 0; i < payloadSizes.length; i++) {
						originalPayloads[i] = createCompressiblePayload(payloadSizes[i]);
						writeCompressedRecord(output, originalPayloads[i], deflater);
					}
				}
			);

			final ObservableInput<?> input = createObservableInputFromBytesWithCompression(recordBytes, bufferSize);

			for (int i = 0; i < payloadSizes.length; i++) {
				readCompressedRecordAndVerify(input, payloadSizes[i], originalPayloads[i]);
			}
		}

		@DisplayName("Compressed records read in random order via RandomAccessFile should be intact")
		@Test
		void shouldReadCompressedRecordsInRandomOrderWithRandomAccessFile() throws IOException {
			final Path targetFile = Path.of(
				System.getProperty("java.io.tmpdir") + File.separator + "test_compressed_random_access.kryo");
			final File targetFileDescr = targetFile.toFile();
			targetFileDescr.delete();

			try {
				final int bufferSize = 8192;
				final int[] payloadSizes = {200, 500, 300};
				final byte[][] originalPayloads = new byte[payloadSizes.length][];
				final long[] startPositions = new long[payloadSizes.length];

				// Write phase
				try (final FileOutputStream fos = new FileOutputStream(targetFileDescr)) {
					final Output output = new Output(fos, bufferSize);
					final Deflater deflater = ZipCompressionFactory.INSTANCE.createCompressor().orElseThrow();

					for (int i = 0; i < payloadSizes.length; i++) {
						startPositions[i] = output.total();
						originalPayloads[i] = createCompressiblePayload(payloadSizes[i]);
						writeCompressedRecord(output, originalPayloads[i], deflater);
					}
					output.close();
				}

				// Read phase
				try (final RandomAccessFile raf = new RandomAccessFile(targetFileDescr, "r")) {
					final ObservableInput<RandomAccessFileInputStream> input = new ObservableInput<>(
						new RandomAccessFileInputStream(raf),
						bufferSize,
						Crc32CChecksumFactory.INSTANCE.createCumulativeChecksum(0L),
						ZipCompressionFactory.INSTANCE.createDecompressor().orElseThrow()
					);

					// read in reverse order (2, 0, 1)
					for (int idx : new int[]{2, 0, 1}) {
						input.seek(new io.evitadb.store.shared.model.FileLocation(startPositions[idx], payloadSizes[idx]));
						input.markStart();
						final int length = input.readInt();
						final byte controlByte = input.readByte();
						input.markPayloadStart(length, controlByte);
						final byte[] readPayload = input.readBytes(payloadSizes[idx]);
						input.markEnd(controlByte);

						assertArrayEquals(originalPayloads[idx], readPayload, "Payload at index " + idx + " mismatch");
					}
				}
			} finally {
				targetFileDescr.delete();
			}
		}

		@DisplayName("Cumulative checksum for single compressed record should match manually computed value")
		@Test
		void shouldCalculateCumulativeChecksumForSingleCompressedRecord() {
			final int bufferSize = BIG_PAYLOAD_SIZE + OVERHEAD_SIZE + 128;
			final byte[] rawBytes = createCompressedRecordBytes(BIG_PAYLOAD_SIZE);

			final long cumulativeChecksum;
			try (
				final ObservableInput<?> input = createObservableInputFromBytesWithCompression(rawBytes, bufferSize)
			) {

				input.markCumulativeChecksumStart();
				input.markStart();
				final int length = input.readInt();
				final byte controlByte = input.readByte();
				input.markPayloadStart(length, controlByte);
				input.readBytes(BIG_PAYLOAD_SIZE);
				input.markEnd(controlByte);
				cumulativeChecksum = input.markCumulativeChecksumEnd();
			}

			final long manualChecksum = computeManualCumulativeChecksum(rawBytes);
			assertEquals(manualChecksum, cumulativeChecksum);
		}

		@DisplayName("Cumulative checksum for multiple compressed records should include all raw bytes")
		@Test
		void shouldCalculateCumulativeChecksumForMultipleCompressedRecords() {
			final int bufferSize = 8192;
			final int[] payloadSizes = {200, 500, 300};
			final byte[][] originalPayloads = new byte[payloadSizes.length][];
			final int[] recordStartPositions = new int[payloadSizes.length + 1];

			final byte[] allBytes = writeRecordsAndGetBytes(
				bufferSize, output -> {
					final Deflater deflater = ZipCompressionFactory.INSTANCE.createCompressor().orElseThrow();
					for (int i = 0; i < payloadSizes.length; i++) {
						recordStartPositions[i] = (int) output.total();
						originalPayloads[i] = createCompressiblePayload(payloadSizes[i]);
						writeCompressedRecord(output, originalPayloads[i], deflater);
					}
					recordStartPositions[payloadSizes.length] = (int) output.total();
				}
			);

			try (
				final ObservableInput<?> input = createObservableInputFromBytesWithCompression(allBytes, bufferSize)
			) {
				for (int i = 0; i < payloadSizes.length; i++) {
					input.markCumulativeChecksumStart();
					input.markStart();
					final int length = input.readInt();
					final byte controlByte = input.readByte();
					input.markPayloadStart(length, controlByte);
					input.readBytes(payloadSizes[i]);
					input.markEnd(controlByte);
					final long cumulativeChecksum = input.markCumulativeChecksumEnd();

					final byte[] recordBytes = Arrays.copyOfRange(
						allBytes, recordStartPositions[i], recordStartPositions[i + 1]);
					final long manualChecksum = computeManualCumulativeChecksum(recordBytes);
					assertEquals(manualChecksum, cumulativeChecksum, "Checksum mismatch for record " + i);
				}
			}
		}

		@DisplayName("Single cumulative checksum spanning all compressed records should match total raw bytes checksum")
		@Test
		void shouldCalculateSingleCumulativeChecksumSpanningAllCompressedRecords() {
			final int bufferSize = 8192;
			final int[] payloadSizes = {200, 500, 300};

			final byte[] allBytes = writeRecordsAndGetBytes(
				bufferSize, output -> {
					final Deflater deflater = ZipCompressionFactory.INSTANCE.createCompressor().orElseThrow();
					final byte[][] originalPayloads = new byte[payloadSizes.length][];
					for (int i = 0; i < payloadSizes.length; i++) {
						originalPayloads[i] = createCompressiblePayload(payloadSizes[i]);
						writeCompressedRecord(output, originalPayloads[i], deflater);
					}
				}
			);

			try (
				final ObservableInput<?> input = createObservableInputFromBytesWithCompression(allBytes, bufferSize)
			) {

				input.markCumulativeChecksumStart();
				for (final int payloadSize : payloadSizes) {
					input.markStart();
					final int length = input.readInt();
					final byte controlByte = input.readByte();
					input.markPayloadStart(length, controlByte);
					input.readBytes(payloadSize);
					input.markEnd(controlByte);
				}
				final long cumulativeChecksum = input.markCumulativeChecksumEnd();

				final long manualChecksum = computeManualCumulativeChecksum(allBytes);
				assertEquals(manualChecksum, cumulativeChecksum);
			}
		}

		@DisplayName("Cumulative checksum with mixed compressed and uncompressed records should be correct")
		@Test
		void shouldCalculateCumulativeChecksumForMixedCompressedAndUncompressedRecords() {
			final int bufferSize = 8192;
			final byte[] allBytes = writeRecordsAndGetBytes(
				bufferSize, output -> {
					final Deflater deflater = ZipCompressionFactory.INSTANCE.createCompressor().orElseThrow();

					// compressible payload (repeated pattern) - will be compressed
					final byte[] compressiblePayload = createCompressiblePayload(500);
					writeCompressedRecord(output, compressiblePayload, deflater);

					// random small payload - uncompressed record
					final byte[] randomPayload = generateBytes(50);
					writeRecord(null, output, 50, randomPayload);

					// another compressible payload - compressed
					final byte[] compressiblePayload2 = createCompressiblePayload(400);
					writeCompressedRecord(output, compressiblePayload2, deflater);
				}
			);

			try (
				final ObservableInput<?> input = createObservableInputFromBytesWithCompression(allBytes, bufferSize)
			) {
				final int[] payloadSizes = {500, 50, 400};

				input.markCumulativeChecksumStart();
				for (int payloadSize : payloadSizes) {
					input.markStart();
					final int length = input.readInt();
					final byte controlByte = input.readByte();
					input.markPayloadStart(length, controlByte);
					input.readBytes(payloadSize);
					input.markEnd(controlByte);
				}
				final long cumulativeChecksum = input.markCumulativeChecksumEnd();

				final long manualChecksum = computeManualCumulativeChecksum(allBytes);
				assertEquals(manualChecksum, cumulativeChecksum);
			}
		}

		@DisplayName("Cumulative checksum with RandomAccessFileInputStream and compressed records should be correct")
		@Test
		void shouldCalculateCumulativeChecksumWithRandomAccessFileInputStreamAndCompression() throws IOException {
			final Path targetFile = Path.of(
				System.getProperty("java.io.tmpdir") + File.separator + "test_compressed_cumulative.kryo");
			final File targetFileDescr = targetFile.toFile();
			targetFileDescr.delete();

			try {
				final int bufferSize = 8192;
				final int[] payloadSizes = {200, 500, 300};

				// Write phase
				try (final FileOutputStream fos = new FileOutputStream(targetFileDescr)) {
					final Output output = new Output(fos, bufferSize);
					final Deflater deflater = ZipCompressionFactory.INSTANCE.createCompressor().orElseThrow();
					final byte[][] originalPayloads = new byte[payloadSizes.length][];

					for (int i = 0; i < payloadSizes.length; i++) {
						originalPayloads[i] = createCompressiblePayload(payloadSizes[i]);
						writeCompressedRecord(output, originalPayloads[i], deflater);
					}
					output.close();
				}

				final byte[] allRecordBytes = Files.readAllBytes(targetFile);

				// Read phase
				try (final RandomAccessFile raf = new RandomAccessFile(targetFileDescr, "r")) {
					final ObservableInput<RandomAccessFileInputStream> input = new ObservableInput<>(
						new RandomAccessFileInputStream(raf),
						bufferSize,
						Crc32CChecksumFactory.INSTANCE.createCumulativeChecksum(0L),
						ZipCompressionFactory.INSTANCE.createDecompressor().orElseThrow()
					);

					input.markCumulativeChecksumStart();
					for (final int payloadSize : payloadSizes) {
						input.markStart();
						final int length = input.readInt();
						final byte controlByte = input.readByte();
						input.markPayloadStart(length, controlByte);
						input.readBytes(payloadSize);
						input.markEnd(controlByte);
					}
					final long cumulativeChecksum = input.markCumulativeChecksumEnd();

					final long manualChecksum = computeManualCumulativeChecksum(allRecordBytes);
					assertEquals(manualChecksum, cumulativeChecksum);
				}
			} finally {
				targetFileDescr.delete();
			}
		}

		@DisplayName("Different compressed record contents should produce different cumulative checksums")
		@Test
		void shouldReturnDifferentCumulativeChecksumsForDifferentCompressedContent() {
			final int bufferSize = 4096;

			// create first record with compressible content A
			final byte[] payload1 = new byte[300];
			Arrays.fill(payload1, (byte) 'A');
			final byte[] recordBytes1 = writeRecordsAndGetBytes(
				bufferSize, output -> {
					final Deflater deflater = ZipCompressionFactory.INSTANCE.createCompressor().orElseThrow();
					writeCompressedRecord(output, payload1, deflater);
				}
			);

			// create second record with different compressible content B
			final byte[] payload2 = new byte[300];
			Arrays.fill(payload2, (byte) 'B');
			final byte[] recordBytes2 = writeRecordsAndGetBytes(
				bufferSize, output -> {
					final Deflater deflater = ZipCompressionFactory.INSTANCE.createCompressor().orElseThrow();
					writeCompressedRecord(output, payload2, deflater);
				}
			);

			// read first record with cumulative checksum
			final long checksum1;
			try (
				final ObservableInput<?> input1 = createObservableInputFromBytesWithCompression(recordBytes1, bufferSize)) {
				input1.markCumulativeChecksumStart();
				input1.markStart();
				final int length1 = input1.readInt();
				final byte controlByte1 = input1.readByte();
				input1.markPayloadStart(length1, controlByte1);
				input1.readBytes(300);
				input1.markEnd(controlByte1);
				checksum1 = input1.markCumulativeChecksumEnd();
			}

			// read second record with cumulative checksum
			final long checksum2;
			try (
				final ObservableInput<?> input2 = createObservableInputFromBytesWithCompression(recordBytes2, bufferSize)
			) {
				input2.markCumulativeChecksumStart();
				input2.markStart();
				final int length2 = input2.readInt();
				final byte controlByte2 = input2.readByte();
				input2.markPayloadStart(length2, controlByte2);
				input2.readBytes(300);
				input2.markEnd(controlByte2);
				checksum2 = input2.markCumulativeChecksumEnd();
			}

			assertNotEquals(checksum1, checksum2);
		}

		@DisplayName("Should throw exception when marking cumulative checksum start while reading compressed payload")
		@Test
		void shouldThrowWhenMarkCumulativeChecksumStartCalledWhileReadingCompressedPayload() {
			final int bufferSize = BIG_PAYLOAD_SIZE + OVERHEAD_SIZE + 128;
			final ByteArrayOutputStream baos = new ByteArrayOutputStream(bufferSize);
			final Output output = new Output(baos, bufferSize);
			final Deflater deflater = ZipCompressionFactory.INSTANCE.createCompressor().orElseThrow();

			final byte[] originalPayload = createCompressiblePayload(BIG_PAYLOAD_SIZE);
			writeCompressedRecord(output, originalPayload, deflater);

			final ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
			try (
				final ObservableInput<?> input = new ObservableInput<>(
					bais, bufferSize,
					Crc32CChecksumFactory.INSTANCE.createCumulativeChecksum(0L),
					ZipCompressionFactory.INSTANCE.createDecompressor().orElseThrow()
				)
			) {
				input.markStart();
				final int length = input.readInt();
				final byte controlByte = input.readByte();
				input.markPayloadStart(length, controlByte);

				// now we are in the middle of reading compressed payload - markCumulativeChecksumStart should throw
				assertThrows(
					GenericEvitaInternalError.class,
					input::markCumulativeChecksumStart
				);
			}
		}
	}

	@Nested
	@DisplayName("Trickle stream (partial buffer fill) tests")
	class TrickleStreamTests {

		@DisplayName("Per-record CRC should be correct when reading from a trickle stream with partial buffer fills")
		@Test
		void shouldReadRecordWithTrickleInputStream() {
			// payload larger than buffer forces multiple refills;
			// trickle stream forces partial fills (limit < capacity)
			final int payloadSize = 77;
			final int bufferSize = 24;
			final int maxBytesPerRead = 1;

			final byte[] recordBytes = writeRecordsAndGetBytes(
				payloadSize + OVERHEAD_SIZE,
				output -> writeRandomRecord(output, payloadSize)
			);

			try (
				final ObservableInput<?> input = createObservableInputFromTrickleStream(
					recordBytes, bufferSize, maxBytesPerRead
				)
			) {
				readAndVerifyRecord(input, payloadSize);
			}
		}

		@DisplayName("Cumulative checksum should be correct when reading from a trickle stream with partial buffer fills")
		@Test
		void shouldCalculateCumulativeChecksumWithTrickleInputStream() {
			// write multiple records, read with trickle stream and cumulative checksum
			final int payloadSize = PAYLOAD_SIZE;
			final int bufferSize = RECORD_SIZE;
			final int maxBytesPerRead = 1;
			final int recordCount = 5;

			final byte[] allBytes = writeRecordsAndGetBytes(
				bufferSize,
				output -> {
					for (int i = 0; i < recordCount; i++) {
						writeRandomRecord(output, payloadSize);
					}
				}
			);

			try (
				final ObservableInput<?> input = createObservableInputFromTrickleStream(
					allBytes, bufferSize, maxBytesPerRead
				)
			) {
				input.markCumulativeChecksumStart();
				for (int i = 0; i < recordCount; i++) {
					readAndVerifyRecord(input, payloadSize);
				}
				final long cumulativeChecksum = input.markCumulativeChecksumEnd();

				final long manualChecksum = computeManualCumulativeChecksum(allBytes);
				assertEquals(manualChecksum, cumulativeChecksum);
			}
		}

		@DisplayName("Multiple records with various payload sizes should be read correctly from a trickle stream")
		@Test
		void shouldReadMultipleRecordsWithTrickleInputStream() {
			// different payload sizes with maxBytesPerRead=3 to test varied partial-fill patterns
			final int[] payloadSizes = {PAYLOAD_SIZE, 37, 5, 64, 100};
			final int bufferSize = 24;
			final int maxBytesPerRead = 3;

			final byte[] allBytes = writeRecordsAndGetBytes(
				1024,
				output -> {
					for (final int size : payloadSizes) {
						writeRandomRecord(output, size);
					}
				}
			);

			try (
				final ObservableInput<?> input = createObservableInputFromTrickleStream(
					allBytes, bufferSize, maxBytesPerRead
				)
			) {
				input.markCumulativeChecksumStart();
				for (final int payloadSize : payloadSizes) {
					readAndVerifyRecord(input, payloadSize);
				}
				final long cumulativeChecksum = input.markCumulativeChecksumEnd();

				final long manualChecksum = computeManualCumulativeChecksum(allBytes);
				assertEquals(manualChecksum, cumulativeChecksum);
			}
		}
	}

	/**
	 * Reachability probe for the buffer-misalignment hazard `AbstractMutationLog` documented for years as *"there
	 * is probably some bug in our observable input implementation that is revealed by filling the buffer
	 * incompletely with the data from the stream"*, describing a 16k buffer, a 2k record, and a 4k record that
	 * then fails to read *"because the internal pointers are probably somehow misaligned"*.
	 *
	 * This sweep never reproduced it, and that is its result rather than a disappointment: whatever the buffer
	 * does internally, every record comes back byte-for-byte through every combination of a small buffer and
	 * sub-buffer records. The defect was not on the record path at all - it was in the bare numbers the WAL reads
	 * *between* records, which {@link BoundaryReadTests} covers. Keep this class as the standing negative
	 * control: if a future change to the fill or compaction bookkeeping does break the record path, this is what
	 * says so.
	 *
	 * Note what is deliberately NOT claimed to be new here. {@link TrickleStreamTests} already forces partial
	 * buffer fills (`limit < capacity`) on every single read, which is a harsher version of the same condition,
	 * and it passes. What neither that class nor the size sweep reproduces is the one property specific to a WAL
	 * being tailed: a stream that genuinely runs out of bytes and then, a moment later, has more - because the
	 * writer appended them. {@link #shouldReportBufferUnderflowWhenTheWriterHasNotCaughtUpYet()} covers that gap,
	 * and proves it reached the condition rather than assuming so: {@link GrowingInputStream} counts its own
	 * end-of-input reports and the test asserts the count is non-zero.
	 */
	@Nested
	@DisplayName("Partially-filled buffer: record integrity across buffer/record size combinations")
	class PartiallyFilledBufferProbeTests {

		@DisplayName("every combination of a small read buffer and sub-buffer record sizes must round-trip intact")
		@Test
		void shouldReadRecordsIntactAcrossPartiallyFillingSizeCombinations() {
			// deliberately small, and deliberately including non-power-of-two sizes so a fill can land at an
			// awkward offset rather than always on a tidy boundary
			final int[] readBufferSizes = {16, 24, 32, 48, 64, 96, 128, 250, 256};
			// the documented shape is "a record far smaller than the buffer, then one that does not fit in what
			// remains", scaled down here, plus the inverse orderings and runs long enough that a one-off skew
			// would accumulate into a visible corruption rather than cancelling out
			final int[][] recordPatterns = {
				{2, 4},
				{8, 16},
				{8, 200},
				{200, 8},
				{8, 8, 200},
				{100, 300, 50},
				{1, 1, 1, 1, 1, 1, 1, 1},
				{7, 13, 29, 61, 127},
				{300, 1, 300, 1, 300},
				{64, 64, 64, 64},
			};

			for (final int readBufferSize : readBufferSizes) {
				for (final int[] pattern : recordPatterns) {
					final byte[] recordBytes = writeRecordsAndGetBytes(
						512,
						output -> {
							for (final int payloadSize : pattern) {
								writeRandomRecord(output, payloadSize);
							}
						}
					);

					final ObservableInput<?> input = createObservableInputFromBytes(recordBytes, readBufferSize);
					for (int i = 0; i < pattern.length; i++) {
						final int recordIndex = i;
						assertDoesNotThrow(
							() -> readAndVerifyRecord(input, pattern[recordIndex]),
							() -> "Record " + recordIndex + " of " + Arrays.toString(pattern) +
								" could not be read back through a " + readBufferSize + "-byte buffer. " +
								"A read buffer that cannot be filled completely from the stream is exactly the " +
								"condition AbstractMutationLog's javadoc blamed for misaligned internal " +
								"pointers, and the record path is supposed to be immune to it."
						);
					}
				}
			}
		}

		@DisplayName("a read that outruns the writer must underflow rather than invent bytes, and the records " +
			"must still read back intact once the writer has caught up")
		@Test
		void shouldReportBufferUnderflowWhenTheWriterHasNotCaughtUpYet() {
			final int[] payloadSizes = {40, 120, 75};
			final byte[] recordBytes = writeRecordsAndGetBytes(
				512,
				output -> {
					for (final int payloadSize : payloadSizes) {
						writeRandomRecord(output, payloadSize);
					}
				}
			);

			// Only the first record is on the "disk" to begin with - the state a reader is in the instant it catches
			// up with a writer that has not yet appended the next transaction.
			final int firstRecordLength = payloadSizes[0] + OVERHEAD_SIZE;
			final GrowingInputStream growingStream = new GrowingInputStream(recordBytes, firstRecordLength);
			final ObservableInput<?> input = new ObservableInput<>(
				growingStream, 128,
				Crc32CChecksumFactory.INSTANCE.createCumulativeChecksum(0L),
				null
			);

			assertDoesNotThrow(
				() -> readAndVerifyRecord(input, payloadSizes[0]),
				"The record that was wholly visible could not be read back, even before the stream ran dry."
			);

			// Looking for the next record while the writer is still behind must surface as a buffer underflow. It
			// must NOT come back as a short read or as bytes the stream never handed over - a silent answer here is
			// what a WAL supplier would turn into a truncated transaction rather than into "nothing new yet".
			assertThrows(
				KryoException.class,
				() -> readAndVerifyRecord(input, payloadSizes[1]),
				"Reading past the bytes the stream had revealed did not underflow. Either the buffer answered from " +
					"bytes it never fetched, or the record read silently stopped short - both are worse than the " +
					"exception, because a caller cannot tell them from a genuine record."
			);
			assertTrue(
				growingStream.getEndOfInputReports() > 0,
				"The stream never actually reported end-of-input, so this test did not reach the condition it is " +
					"named for. Whatever made the read fail, it was not a reader outrunning a writer."
			);

			// A moment later the writer has appended the rest. Production discards the supplier and re-reads from
			// its own file position (AbstractMutationSupplier opens a fresh ObservableInput per supplier), so the
			// property that matters is that the bytes were never damaged - every record must still read back whole.
			growingStream.revealAll();
			final ObservableInput<?> reopenedInput = new ObservableInput<>(
				new GrowingInputStream(recordBytes, recordBytes.length), 128,
				Crc32CChecksumFactory.INSTANCE.createCumulativeChecksum(0L),
				null
			);
			for (int i = 0; i < payloadSizes.length; i++) {
				final int recordIndex = i;
				assertDoesNotThrow(
					() -> readAndVerifyRecord(reopenedInput, payloadSizes[recordIndex]),
					() -> "Record " + recordIndex + " could not be read back after the writer had caught up, even " +
						"though a fresh reader was opened over the completed data. An earlier reader running into " +
						"end-of-input must not leave anything behind that a later one can observe."
				);
			}
		}
	}

	/**
	 * The WAL does not only read records. Between them it reads bare numbers straight off the stream: the 8-byte
	 * cumulative checksum that separates transactions and the 4-byte content length that prefixes one
	 * (the seed-checksum read in {@code AbstractMutationSupplier}'s constructor,
	 * {@code AbstractMutationSupplier#readAndRecordTransactionMutation}, {@code MutationSupplier#get()}). Those
	 * bypass the {@link StorageRecord} lifecycle and go through {@link ObservableInput#simpleLongRead()} and
	 * {@link ObservableInput#simpleIntRead()} instead, which set the record counters up by hand and restore them
	 * in a `finally`.
	 *
	 * They are also the reads a tailing reader performs most often, because a reader that has caught up with a writer
	 * is by definition sitting on a transaction boundary - and that is the state in which its buffer holds a partial
	 * fill. {@link PartiallyFilledBufferProbeTests} drives records through that condition and passes; the boundary
	 * reads were never driven through it at all, which is the coverage gap this class closes.
	 *
	 * **Counterfactual.** This class guards `ObservableInput#restoreLimitAfterOffRecordRead`. Replacing its
	 * three-part guard with the unconditional `this.limit = this.actualLimit >= 0 ? this.actualLimit : this.limit`
	 * that {@link ObservableInput#simpleIntRead()} and {@link ObservableInput#simpleLongRead()} used to perform must
	 * make it fail - in {@link #shouldKeepTheBufferConsistentAcrossBoundaryReads()} on the small-buffer/small-chunk
	 * half of the matrix, where a boundary read has to refill or compact, and in
	 * {@link #shouldHandleTheLimitCapOnEveryPathThroughRequire()} on every combination that reaches the fill or the
	 * compaction branch. The matrix is therefore load-bearing and narrowing it silently narrows the guard: the
	 * combinations that matter are the ones where `bytesPerRead` is smaller than the 8-byte checksum, because only
	 * those force `require(8)` to go looking for more bytes mid-read.
	 */
	@Nested
	@DisplayName("Boundary reads: bare numbers read between records")
	class BoundaryReadTests {
		private static final long FIRST_CHECKSUM = 0x1122334455667788L;
		private static final int CONTENT_LENGTH = 0x0A0B0C0D;
		private static final long SECOND_CHECKSUM = 0x7FEEDDCCBBAA9988L;
		private static final int TRAILING_BYTES = 24;

		/**
		 * The byte sequence a WAL reader meets at a transaction boundary, in the order it meets it.
		 */
		@Nonnull
		private byte[] boundarySequence() {
			// little-endian, to match both Kryo's Input and the WAL file format (AbstractMutationLog:283)
			final ByteBuffer buffer = ByteBuffer.allocate(8 + 4 + 8 + TRAILING_BYTES)
				.order(ByteOrder.LITTLE_ENDIAN);
			buffer.putLong(FIRST_CHECKSUM);
			buffer.putInt(CONTENT_LENGTH);
			buffer.putLong(SECOND_CHECKSUM);
			for (int i = 0; i < TRAILING_BYTES; i++) {
				buffer.put((byte) (0xE0 | (i & 0x0F)));
			}
			return buffer.array();
		}

		@DisplayName("a boundary read must leave the buffer consistent and the following bytes readable")
		@Test
		void shouldKeepTheBufferConsistentAcrossBoundaryReads() throws Throwable {
			// capacities small enough that the sequence cannot be held whole, so a boundary read has to refill
			final int[] readBufferSizes = {16, 20, 24, 32, 48, 64};
			// how many bytes the stream yields per read call - anything below the buffer size leaves it partly
			// filled, which is the state a reader tailing a live WAL is in essentially all of the time
			final int[] bytesPerReadCall = {1, 2, 3, 5, 7, 8, 10, 16, 64};

			final List<String> failures = new ArrayList<>();
			// the sweep deliberately accumulates every combination rather than stopping at the first, but a bare
			// "threw KryoException: Buffer underflow." names no site - so the first Throwable is kept and rethrown
			// underneath the summary, which is the only copy that carries a stack
			Throwable firstThrowable = null;
			for (final int readBufferSize : readBufferSizes) {
				for (final int chunk : bytesPerReadCall) {
					final String combination = "buffer=" + readBufferSize + ", bytesPerRead=" + chunk;
					final ObservableInput<?> input = createObservableInputFromTrickleStream(
						boundarySequence(), readBufferSize, chunk
					);
					try {
						final long firstChecksum = input.simpleLongRead();
						assertBufferConsistent(input, failures, combination, "simpleLongRead #1");
						final int contentLength = input.simpleIntRead();
						assertBufferConsistent(input, failures, combination, "simpleIntRead");
						final long secondChecksum = input.simpleLongRead();
						assertBufferConsistent(input, failures, combination, "simpleLongRead #2");

						if (firstChecksum != FIRST_CHECKSUM) {
							failures.add(combination + ": first checksum read back as " + firstChecksum);
						}
						if (contentLength != CONTENT_LENGTH) {
							failures.add(combination + ": content length read back as " + contentLength);
						}
						if (secondChecksum != SECOND_CHECKSUM) {
							failures.add(combination + ": second checksum read back as " + secondChecksum);
						}

						// whatever follows the boundary must still be readable and correct - a boundary read that
						// leaves the buffer describing bytes it never fetched shows up here rather than above
						final byte[] trailing = input.readBytes(TRAILING_BYTES);
						for (int i = 0; i < TRAILING_BYTES; i++) {
							final byte expected = (byte) (0xE0 | (i & 0x0F));
							if (trailing[i] != expected) {
								failures.add(
									combination + ": trailing byte " + i + " read back as " + trailing[i] +
										" instead of " + expected
								);
								break;
							}
						}
					} catch (Throwable ex) {
						if (firstThrowable == null) {
							firstThrowable = ex;
						}
						failures.add(combination + ": threw " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
					}
				}
			}

			if (!failures.isEmpty()) {
				final AssertionError failure = new AssertionError(
					"Reading the numbers that sit between WAL transactions left the input inconsistent in " +
						failures.size() + " buffer/chunk combination(s). simpleIntRead and simpleLongRead cap the " +
						"limit for the duration of the read and restore the captured one in a finally block - but a " +
						"read that has to refill the buffer in between legitimately moves that limit, and the " +
						"restore puts the pre-refill value back. Failures:\n" + String.join("\n", failures)
				);
				if (firstThrowable != null) {
					failure.initCause(firstThrowable);
				}
				throw failure;
			}
		}

		/**
		 * Which branch of `ObservableInput#require(int)` a single boundary read took, inferred from what the read
		 * left behind. The three-part restore guard in `restoreLimitAfterOffRecordRead` has a different correct
		 * answer on each of them, and the sweep below asserts that every one of them was actually reached - a
		 * future change to buffer sizing that quietly stops reaching one would otherwise take its coverage with it.
		 */
		private enum RequirePath {
			/**
			 * The very first read on a fresh input: `limit` is still `0`, so the captured `actualLimit` is `-1` and
			 * the guard's first conjunct is the one that declines the restore.
			 */
			FRESH_INPUT,
			/**
			 * `require` returned from the bytes already buffered without touching anything. This is the only path
			 * on which the restore is *effective*, and the only one that proves it still happens at all.
			 */
			NO_FILL,
			/**
			 * `require` topped the buffer up in place, raising `limit` by the fill count and leaving `total` alone.
			 */
			FILL,
			/**
			 * `require` compacted the buffer - the remaining bytes were shifted to the front, `position` was zeroed
			 * and `total` advanced by the old `position`, so the captured cap is not even measured from the same
			 * origin any more.
			 */
			COMPACTION
		}

		@DisplayName("every path through require() must leave the boundary read's limit cap correctly handled")
		@Test
		void shouldHandleTheLimitCapOnEveryPathThroughRequire() {
			final int[] readBufferSizes = {16, 20, 24, 32, 48, 64};
			final int[] bytesPerReadCall = {1, 2, 3, 5, 7, 8, 10, 16, 64};

			final Set<RequirePath> pathsReached = EnumSet.noneOf(RequirePath.class);
			final List<String> failures = new ArrayList<>();
			for (final int readBufferSize : readBufferSizes) {
				for (final int chunk : bytesPerReadCall) {
					final String combination = "buffer=" + readBufferSize + ", bytesPerRead=" + chunk;
					final ObservableInput<?> input = createObservableInputFromTrickleStream(
						boundarySequence(), readBufferSize, chunk
					);
					pathsReached.add(
						readBoundaryNumber(input, 8, FIRST_CHECKSUM, combination + " / checksum #1", failures)
					);
					pathsReached.add(
						readBoundaryNumber(input, 4, CONTENT_LENGTH, combination + " / content length", failures)
					);
					pathsReached.add(
						readBoundaryNumber(input, 8, SECOND_CHECKSUM, combination + " / checksum #2", failures)
					);
				}
			}

			assertTrue(
				failures.isEmpty(),
				"A boundary read mishandled the limit cap it installs for the duration of the read in " +
					failures.size() + " case(s). The cap may only be put back when the read left the buffer " +
					"exactly as it found it; on every other path the value require() computed is the honest end " +
					"of the data and must stand. Failures:\n" + String.join("\n", failures)
			);
			assertEquals(
				EnumSet.allOf(RequirePath.class), pathsReached,
				"The buffer/chunk matrix no longer reaches every branch of require(), so the assertions above " +
					"stopped covering the branches it misses. Widen the matrix rather than narrowing the claim - " +
					"the combinations that reach the fill and compaction branches are the ones whose `bytesPerRead` " +
					"is smaller than the 8-byte checksum being read."
			);
		}

		/**
		 * Reads one bare number off the input, classifies which branch of `require(int)` it took, and records a
		 * failure when the limit the read left behind is not the one that branch mandates.
		 *
		 * @param requiredBytes 8 for a checksum, 4 for a content-length prefix
		 * @param expectedValue the value the number must read back as
		 * @param label         identifies the combination and the read in a failure message
		 * @param failures      collects every mismatch, so the whole matrix is swept rather than the first failure
		 * @return the branch this read took
		 */
		@Nonnull
		private RequirePath readBoundaryNumber(
			@Nonnull ObservableInput<?> input,
			int requiredBytes,
			long expectedValue,
			@Nonnull String label,
			@Nonnull List<String> failures
		) {
			final int limitBefore = input.limit();
			final int positionBefore = input.position();
			// Input#total() reports `total + position`, so the raw counter the restore guard compares - the one a
			// compaction advances - is recovered by subtracting the position back out
			final long rawTotalBefore = input.total() - positionBefore;

			final long readValue = requiredBytes == 8 ? input.simpleLongRead() : input.simpleIntRead();

			final int limitAfter = input.limit();
			final int positionAfter = input.position();
			final long rawTotalAfter = input.total() - positionAfter;

			if (readValue != expectedValue) {
				failures.add(label + ": read back as " + readValue + " instead of " + expectedValue);
			}
			if (positionAfter > limitAfter) {
				failures.add(label + ": position=" + positionAfter + " is past limit=" + limitAfter);
			}

			final RequirePath path;
			if (limitBefore == 0) {
				path = RequirePath.FRESH_INPUT;
			} else if (rawTotalAfter != rawTotalBefore) {
				path = RequirePath.COMPACTION;
			} else if (limitBefore - positionBefore >= requiredBytes) {
				path = RequirePath.NO_FILL;
			} else {
				path = RequirePath.FILL;
			}

			switch (path) {
				case FRESH_INPUT -> {
					// nothing was captured to put back, so whatever require() filled must survive the finally block
					if (limitAfter <= 0) {
						failures.add(label + ": fresh input left limit=" + limitAfter + " after a successful read");
					}
				}
				case NO_FILL -> {
					// the buffer never moved, so the cap MUST be undone - leaving it in place understates the
					// buffer and makes the next fill land at a stale offset
					if (limitAfter != limitBefore) {
						failures.add(
							label + ": require() returned from already-buffered bytes, so the cap had to be " +
								"undone - limit went from " + limitBefore + " to " + limitAfter
						);
					}
					if (positionAfter != positionBefore + requiredBytes) {
						failures.add(
							label + ": position moved from " + positionBefore + " to " + positionAfter +
								" on a read of " + requiredBytes + " byte(s)"
						);
					}
				}
				case FILL -> {
					// require() topped the buffer up in place; the raised limit is the honest end of the data
					if (limitAfter <= limitBefore) {
						failures.add(
							label + ": require() filled the buffer but limit did not rise - it went from " +
								limitBefore + " to " + limitAfter + ", i.e. the pre-fill cap was put back"
						);
					}
					if (positionAfter != positionBefore + requiredBytes) {
						failures.add(
							label + ": position moved from " + positionBefore + " to " + positionAfter +
								" on a read of " + requiredBytes + " byte(s)"
						);
					}
				}
				case COMPACTION -> {
					// the remaining bytes were shifted to the front: position restarts at 0 and the captured cap
					// is not measured from the same origin any more, so putting it back is meaningless
					if (rawTotalAfter - rawTotalBefore != positionBefore) {
						failures.add(
							label + ": compaction advanced the raw total by " + (rawTotalAfter - rawTotalBefore) +
								" instead of the " + positionBefore + " bytes it dropped from the front"
						);
					}
					if (positionAfter != requiredBytes) {
						failures.add(
							label + ": after a compaction the read should end at position " + requiredBytes +
								", not " + positionAfter
						);
					}
				}
			}
			return path;
		}

		@DisplayName("an underflow that compacted the buffer must not re-serve bytes already delivered")
		@Test
		void shouldNotServeAlreadyDeliveredBytesAfterACompactingUnderflow() {
			// only the leading checksum and the content-length prefix are on the "disk" - the boundary read that
			// follows them finds the buffer fully consumed, so require() skips its fill branch and goes straight
			// to compacting, which is the one branch that moves `position` back to the front of the buffer
			final byte[] sequence = boundarySequence();
			final GrowingInputStream growingStream = new GrowingInputStream(sequence, 12);
			final ObservableInput<?> input = new ObservableInput<>(
				growingStream, 16,
				Crc32CChecksumFactory.INSTANCE.createCumulativeChecksum(0L),
				null
			);

			assertEquals(FIRST_CHECKSUM, input.simpleLongRead(), "the visible checksum could not be read back");
			assertEquals(CONTENT_LENGTH, input.simpleIntRead(), "the visible content length could not be read back");

			assertThrows(
				KryoException.class,
				input::simpleLongRead,
				"Reading the next checksum before the writer had appended it did not underflow."
			);
			assertTrue(
				growingStream.getEndOfInputReports() > 0,
				"The stream never reported end-of-input, so this test did not reach the condition it is named for."
			);
			assertEquals(
				0, input.limit() - input.position(),
				"After the underflow the buffer claims to still hold " + (input.limit() - input.position()) +
					" readable byte(s), and every one of them has already been delivered. Compacting moved the " +
					"surviving bytes to the front and reset `position`, so a `limit` left describing the buffer " +
					"as it was before that no longer measures from the same origin - and the next require() " +
					"answers out of it without fetching anything."
			);

			// a moment later the writer has appended the rest, and the reader carries on where it stopped
			growingStream.revealAll();
			assertEquals(
				SECOND_CHECKSUM,
				input.simpleLongRead(),
				"The checksum read after the writer caught up came back as bytes the input had already delivered " +
					"once, not as the bytes that follow them. This is the silent half of an overstated limit: no " +
					"exception, no short read, just an earlier part of the stream served a second time."
			);
		}

		@DisplayName("the whole read sequence a WAL transaction boundary performs must round-trip intact")
		@Test
		void shouldReadTheWalTransactionBoundarySequenceIntact() {
			final int[] readBufferSizes = {16, 20, 24, 32, 48, 64};
			final int[] bytesPerReadCall = {1, 2, 3, 5, 7, 8, 10, 16, 64};
			final int payloadSize = 24;
			final byte[] recordBytes = writeRecordsAndGetBytes(
				128, output -> writeRandomRecord(output, payloadSize)
			);
			final byte[] sequence = walTransactionSequence(recordBytes);

			final List<String> failures = new ArrayList<>();
			Throwable firstThrowable = null;
			for (final int readBufferSize : readBufferSizes) {
				for (final int chunk : bytesPerReadCall) {
					final String combination = "buffer=" + readBufferSize + ", bytesPerRead=" + chunk;
					final ObservableInput<?> input = createObservableInputFromTrickleStream(
						sequence, readBufferSize, chunk
					);
					try {
						final long cumulativeChecksum = input.simpleLongRead();
						if (cumulativeChecksum != FIRST_CHECKSUM) {
							failures.add(combination + ": cumulative checksum read back as " + cumulativeChecksum);
						}
						final int contentLength = input.simpleIntRead();
						if (contentLength != recordBytes.length) {
							failures.add(combination + ": content length read back as " + contentLength);
						}
						// the record read is the part that verifies its own CRC32C - a limit left describing bytes
						// the buffer never fetched surfaces here as a checksum failure, which is exactly how the
						// production symptom presents rather than as a byte mismatch
						readAndVerifyRecord(input, payloadSize);
						final long trailingChecksum = input.simpleLongRead();
						if (trailingChecksum != SECOND_CHECKSUM) {
							failures.add(combination + ": trailing checksum read back as " + trailingChecksum);
						}
					} catch (Throwable ex) {
						if (firstThrowable == null) {
							firstThrowable = ex;
						}
						failures.add(combination + ": threw " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
					}
				}
			}

			if (!failures.isEmpty()) {
				final AssertionError failure = new AssertionError(
					"The order a WAL reader meets at a transaction boundary - cumulative checksum, content-length " +
						"prefix, the leading record with its own CRC32C, then the next cumulative checksum - did " +
						"not round-trip in " + failures.size() + " buffer/chunk combination(s). Neither of the " +
						"sibling sweeps covers this interleaving: one reads bare numbers with no record between " +
						"them, the other reads records with no bare numbers between them. Failures:\n" +
						String.join("\n", failures)
				);
				if (firstThrowable != null) {
					failure.initCause(firstThrowable);
				}
				throw failure;
			}
		}

		/**
		 * Assembles the byte sequence a WAL reader meets around one transaction: the cumulative checksum that
		 * precedes it, the 4-byte content-length prefix, the leading storage record itself, and the cumulative
		 * checksum that closes it.
		 *
		 * @param recordBytes the already-serialized storage record to place between the numbers
		 * @return the concatenated sequence
		 */
		@Nonnull
		private byte[] walTransactionSequence(@Nonnull byte[] recordBytes) {
			// little-endian, to match both Kryo's Input and the WAL file format
			final ByteBuffer buffer = ByteBuffer.allocate(8 + 4 + recordBytes.length + 8)
				.order(ByteOrder.LITTLE_ENDIAN);
			buffer.putLong(FIRST_CHECKSUM);
			buffer.putInt(recordBytes.length);
			buffer.put(recordBytes);
			buffer.putLong(SECOND_CHECKSUM);
			return buffer.array();
		}

		/**
		 * Records a failure when the input's position has moved past the end of the bytes the input claims to hold.
		 * `position > limit` is not a degraded state that later reads recover from - every subsequent `require`
		 * computes a negative number of remaining bytes from it.
		 */
		private void assertBufferConsistent(
			@Nonnull ObservableInput<?> input,
			@Nonnull List<String> failures,
			@Nonnull String combination,
			@Nonnull String afterCall
		) {
			if (input.position() > input.limit()) {
				failures.add(
					combination + ": after " + afterCall + " position=" + input.position() +
						" is past limit=" + input.limit()
				);
			}
		}
	}

}
