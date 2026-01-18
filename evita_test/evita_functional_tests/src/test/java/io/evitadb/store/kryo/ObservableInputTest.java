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

import com.esotericsoftware.kryo.io.Output;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.store.checksum.Crc32CChecksumCalculatorFactory;
import io.evitadb.store.compression.ZipCompressionFactory;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.stream.RandomAccessFileInputStream;
import io.evitadb.utils.BitUtils;
import io.evitadb.utils.Crc32CWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link ObservableInput} behaviour.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@SuppressWarnings("ResultOfMethodCallIgnored")
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
			Crc32CChecksumCalculatorFactory.INSTANCE.createChecksum(),
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
			Crc32CChecksumCalculatorFactory.INSTANCE.createChecksum(),
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
					24, Crc32CChecksumCalculatorFactory.INSTANCE.createChecksum(), null
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
					final int rndSize = this.random.nextInt(9999) + 1;
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
				bais, 24, Crc32CChecksumCalculatorFactory.INSTANCE.createChecksum(), null
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
			bais1, 24, Crc32CChecksumCalculatorFactory.INSTANCE.createChecksum(), null
		);
		input1.markCumulativeChecksumStart();
		readAndVerifyRecord(input1, payloadSize);
		final long checksum1 = input1.markCumulativeChecksumEnd();

		// read second record with cumulative checksum
		final ByteArrayInputStream bais2 = new ByteArrayInputStream(baos2.toByteArray());
		final ObservableInput<?> input2 = new ObservableInput<>(
			bais2, 24, Crc32CChecksumCalculatorFactory.INSTANCE.createChecksum(), null
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
					24, Crc32CChecksumCalculatorFactory.INSTANCE.createChecksum(), null
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
					Crc32CChecksumCalculatorFactory.INSTANCE.createChecksum(),
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
					Crc32CChecksumCalculatorFactory.INSTANCE.createChecksum(),
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
				Crc32CChecksumCalculatorFactory.INSTANCE.createChecksum(),
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

}
