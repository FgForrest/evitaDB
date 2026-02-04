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

package io.evitadb.store.offsetIndex.model;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.KryoDataInput;
import com.esotericsoftware.kryo.io.KryoDataOutput;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.function.Functions;
import io.evitadb.store.checksum.Checksum;
import io.evitadb.store.checksum.Crc32CChecksum;
import io.evitadb.store.checksum.Crc32CChecksumFactory;
import io.evitadb.store.compression.CompressionFactory;
import io.evitadb.store.compression.ZipCompressionFactory;
import io.evitadb.store.kryo.ObservableInput;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.store.offsetIndex.exception.CorruptedRecordException;
import io.evitadb.store.offsetIndex.model.StorageRecord.RawRecord;
import io.evitadb.store.offsetIndex.model.StorageRecord.StorageRecordWithChecksum;
import io.evitadb.store.shared.model.FileLocation;
import io.evitadb.stream.RandomAccessFileInputStream;
import io.evitadb.utils.Crc32CWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.roaringbitmap.longlong.Roaring64Bitmap;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * This test verifies the behaviour of {@link StorageRecord}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("StorageRecord comprehensive tests")
@SuppressWarnings({"SameParameterValue", "ResultOfMethodCallIgnored"})
class StorageRecordTest {
	private final Random random = new Random();
	private File tempFile;
	private Kryo kryo;

	/**
	 * Generates a stream of arguments by combining all possible combinations
	 * of {@link ChecksumCheck} and {@link Compression} enum values.
	 *
	 * @return a {@link Stream} of {@link Arguments} containing every combination
	 * of {@link ChecksumCheck} and {@link Compression}.
	 */
	@Nonnull
	private static Stream<Arguments> combineSettings() {
		return Stream.of(ChecksumCheck.values())
			.flatMap(crc32Check -> Stream.of(Compression.values())
				.map(compression -> Arguments.of(crc32Check, compression)));
	}

	/**
	 * Creates an ObservableOutput with the specified settings.
	 *
	 * @param outputStream    the underlying output stream
	 * @param bufferSize      the buffer size (also used as flush size)
	 * @param currentFileSize the current file size offset
	 * @param checksumCheck   whether to enable CRC32 checksum computation
	 * @param compression     whether to enable compression
	 * @return the configured ObservableOutput
	 */
	@Nonnull
	private static <T extends OutputStream> ObservableOutput<T> createOutput(
		@Nonnull T outputStream,
		int bufferSize,
		int currentFileSize,
		@Nonnull ChecksumCheck checksumCheck,
		@Nonnull Compression compression
	) {
		return new ObservableOutput<>(
			outputStream,
			bufferSize,
			currentFileSize,
			checksumCheck == ChecksumCheck.YES ?
				new Crc32CChecksum() : Checksum.NO_OP,
			(compression == Compression.YES ?
				new ZipCompressionFactory() : CompressionFactory.NO_COMPRESSION)
				.createCompressor()
				.orElse(null)
		);
	}

	/**
	 * Creates an ObservableOutput with explicit flush size for continuation record tests.
	 *
	 * @param outputStream    the underlying output stream
	 * @param flushSize       the flush size (triggers buffer flush)
	 * @param bufferSize      the buffer size
	 * @param currentFileSize the current file size offset
	 * @param checksumCheck   whether to enable CRC32 checksum computation
	 * @param compression     whether to enable compression
	 * @return the configured ObservableOutput
	 */
	@Nonnull
	private static <T extends OutputStream> ObservableOutput<T> createOutputWithFlush(
		@Nonnull T outputStream,
		int flushSize,
		int bufferSize,
		int currentFileSize,
		@Nonnull ChecksumCheck checksumCheck,
		@Nonnull Compression compression
	) {
		return new ObservableOutput<>(
			outputStream,
			flushSize,
			bufferSize,
			currentFileSize,
			checksumCheck == ChecksumCheck.YES ?
				new Crc32CChecksum() : Checksum.NO_OP,
			(compression == Compression.YES ?
				new ZipCompressionFactory() : CompressionFactory.NO_COMPRESSION)
				.createCompressor()
				.orElse(null)
		);
	}

	/**
	 * Creates an ObservableInput with the specified settings.
	 *
	 * @param inputStream   the underlying input stream
	 * @param bufferSize    the buffer size
	 * @param checksumCheck whether to enable CRC32 checksum verification
	 * @param compression   whether to enable decompression
	 * @return the configured ObservableInput
	 */
	@Nonnull
	private static <T extends InputStream> ObservableInput<T> createInput(
		@Nonnull T inputStream,
		int bufferSize,
		@Nonnull ChecksumCheck checksumCheck,
		@Nonnull Compression compression
	) {
		return new ObservableInput<>(
			inputStream,
			bufferSize,
			checksumCheck == ChecksumCheck.YES ?
				new Crc32CChecksum() : Checksum.NO_OP,
			(compression == Compression.YES ?
				new ZipCompressionFactory() : CompressionFactory.NO_COMPRESSION)
				.createDecompressor()
				.orElse(null)
		);
	}

	/**
	 * Generates a LongSetChunk containing sequential long values from 1 to length.
	 *
	 * @param length the number of sequential long values to generate
	 * @return a new LongSetChunk containing sequential values
	 */
	@Nonnull
	private static LongSetChunk generateLongSetOfSize(int length) {
		final Long[] longSet = new Long[length];
		for (int i = 0; i < length; i++) {
			longSet[i] = (long) (i + 1);
		}
		return new LongSetChunk(longSet);
	}

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

	@BeforeEach
	void setUp() {
		this.tempFile = Path.of(System.getProperty("java.io.tmpdir")).resolve("evita_test_file.tmp").toFile();
		this.kryo = new Kryo();
		this.kryo.register(ByteChunk.class, new ByteChunkSerializer());
		this.kryo.register(LongSetChunk.class, new LongSetChunkSerializer());
		this.kryo.register(Roaring64Bitmap.class, new Roaring64BitmapSerializer());
	}

	@AfterEach
	void tearDown() {
		this.tempFile.delete();
		this.tempFile = null;
	}

	/**
	 * Generates and writes multiple storage records with random byte payloads to the temp file.
	 *
	 * @param count       the number of records to generate
	 * @param consumer    a consumer to receive each generated record
	 * @param crc32Check  whether to compute CRC32 checksums
	 * @param compression whether to apply compression
	 * @return a map of file locations to their corresponding records
	 * @throws FileNotFoundException if the temp file cannot be created
	 */
	@Nonnull
	private Map<FileLocation, StorageRecord<ByteChunk>> generateAndWriteRandomRecords(
		int count,
		@Nonnull Consumer<StorageRecord<ByteChunk>> consumer,
		@Nonnull ChecksumCheck crc32Check,
		@Nonnull Compression compression
	) throws FileNotFoundException {
		final Map<FileLocation, StorageRecord<ByteChunk>> index = new HashMap<>(count);

		try (
			final ObservableOutput<?> output = createOutput(
				new FileOutputStream(this.tempFile), 16_384, 0, crc32Check, compression)
		) {
			for (int i = 0; i < count; i++) {
				final StorageRecord<ByteChunk> record = new StorageRecord<>(
					this.kryo, output, 1L, false, generateBytes(this.random.nextInt(256)));
				index.put(record.fileLocation(), record);
				consumer.accept(record);
			}
		}
		return index;
	}

	/**
	 * Writes a single storage record with random byte payload and adds it to the index.
	 *
	 * @param index       the map to store the record by its file location
	 * @param output      the output stream to write to
	 * @param payloadSize the size of the random byte payload
	 * @return the created storage record
	 */
	@Nonnull
	private StorageRecord<ByteChunk> writeRandomRecord(
		@Nonnull Map<FileLocation, StorageRecord<ByteChunk>> index,
		@Nonnull ObservableOutput<?> output,
		int payloadSize
	) {
		final StorageRecord<ByteChunk> record = new StorageRecord<>(
			this.kryo, output, 1L, false, generateBytes(payloadSize));
		index.put(record.fileLocation(), record);
		return record;
	}

	/**
	 * Generates a ByteChunk with random byte data of the specified size.
	 *
	 * @param count the number of random bytes to generate
	 * @return a new ByteChunk containing the generated random bytes
	 */
	@Nonnull
	private ByteChunk generateBytes(int count) {
		final byte[] data = new byte[count];
		this.random.nextBytes(data);
		return new ByteChunk(data);
	}

	/**
	 * Tests for basic read and write operations of storage records.
	 */
	@Nested
	@DisplayName("Basic read/write operations")
	class BasicReadWriteTests {

		@DisplayName("Single record should be written and read intact")
		@ParameterizedTest
		@MethodSource("io.evitadb.store.offsetIndex.model.StorageRecordTest#combineSettings")
		void shouldWriteAndReadRecord(ChecksumCheck crc32Check, Compression compression) throws IOException {
			final StorageRecord<ByteChunk> record;
			try (final ObservableOutput<?> output = createOutput(
				new FileOutputStream(StorageRecordTest.this.tempFile), 16_384, 0, crc32Check, compression)) {
				record = new StorageRecord<>(StorageRecordTest.this.kryo, output, 1L, false, generateBytes(256));
			}

			try (final ObservableInput<?> input = createInput(
				new FileInputStream(StorageRecordTest.this.tempFile), 8_192, crc32Check, compression)) {
				final StorageRecord<ByteChunk> loadedRecord = StorageRecord.read(
					StorageRecordTest.this.kryo, input,
					fl -> {
						assertEquals(record.fileLocation(), fl);
						return ByteChunk.class;
					}
				);
				assertEquals(record, loadedRecord);
			}
		}

		@DisplayName("Multiple records of various random size should be written and read with checksum")
		@ParameterizedTest
		@MethodSource("io.evitadb.store.offsetIndex.model.StorageRecordTest#combineSettings")
		void shouldWriteAndReadMultipleDifferentRecordsOfVaryingSize(
			ChecksumCheck crc32Check, Compression compression) throws IOException {
			final int count = 256;
			final List<StorageRecord<ByteChunk>> records = new ArrayList<>(count);
			final Map<FileLocation, StorageRecord<ByteChunk>> index = generateAndWriteRandomRecords(
				count, records::add, crc32Check, compression);

			try (
				final ObservableInput<?> input = createInput(
					new FileInputStream(StorageRecordTest.this.tempFile), 16_384, crc32Check, compression
				)
			) {
				for (int i = 0; i < count; i++) {
					final StorageRecord<ByteChunk> loadedRecord = StorageRecord.read(
						StorageRecordTest.this.kryo, input,
						fl -> ByteChunk.class
					);
					assertEquals(records.get(i), loadedRecord, "Record " + i + " doesn't match!");
					final StorageRecord<ByteChunk> originalRecord = index.get(loadedRecord.fileLocation());
					assertNotNull(originalRecord);
					assertEquals(originalRecord, loadedRecord);
				}
			}
		}

		@DisplayName("Multiple records should be written and read in random order")
		@ParameterizedTest
		@MethodSource("io.evitadb.store.offsetIndex.model.StorageRecordTest#combineSettings")
		void shouldReadRandomRecords(ChecksumCheck crc32Check, Compression compression) throws FileNotFoundException {
			final Map<FileLocation, StorageRecord<ByteChunk>> index = new HashMap<>(8);
			try (
				final ObservableOutput<?> output = createOutput(
					new FileOutputStream(StorageRecordTest.this.tempFile), 16_384, 0, crc32Check, compression
				)
			) {
				writeRandomRecord(index, output, 256);
				writeRandomRecord(index, output, 178);
				writeRandomRecord(index, output, 453);
			}

			try (
				final RandomAccessFileInputStream is =
					new RandomAccessFileInputStream(new RandomAccessFile(StorageRecordTest.this.tempFile, "r"));
				final ObservableInput<RandomAccessFileInputStream> input =
					createInput(is, 8_192, crc32Check, compression)
			) {
				index.forEach(
					(key, expectedRecord) -> {
						final StorageRecord<ByteChunk> loadedRecord = StorageRecord.read(
							input, key, (stream, length, control) -> StorageRecordTest.this.kryo.readObject(stream, ByteChunk.class));
						assertEquals(expectedRecord, loadedRecord);
					}
				);
			}
		}

		@DisplayName("Multiple records with random content size should be written and read in random order")
		@ParameterizedTest
		@MethodSource("io.evitadb.store.offsetIndex.model.StorageRecordTest#combineSettings")
		void shouldWriteAndReadRandomlyMultipleDifferentRecordsOfVaryingSize(
			ChecksumCheck crc32Check, Compression compression) throws IOException {
			final int count = 256;
			final int retrievalCount = 512;
			final Map<FileLocation, StorageRecord<ByteChunk>> index = generateAndWriteRandomRecords(
				count, rec -> {}, crc32Check, compression
			);
			final List<FileLocation> locations = new ArrayList<>(index.keySet());

			try (final RandomAccessFileInputStream is =
				     new RandomAccessFileInputStream(new RandomAccessFile(StorageRecordTest.this.tempFile, "r"));
			     final ObservableInput<RandomAccessFileInputStream> input =
				     createInput(is, 8_192 << 1, crc32Check, compression)
			) {
				for (int i = 0; i < retrievalCount; i++) {
					final FileLocation randomLocation = locations.get(StorageRecordTest.this.random.nextInt(locations.size()));
					final StorageRecord<ByteChunk> loadedRecord = StorageRecord.read(
						input, randomLocation, (stream, length, control) -> StorageRecordTest.this.kryo.readObject(stream, ByteChunk.class));
					final StorageRecord<ByteChunk> expectedRecord = index.get(randomLocation);
					assertEquals(expectedRecord, loadedRecord);
				}
			}
		}

	}

	/**
	 * Tests for checksum configuration and behavior.
	 */
	@Nested
	@DisplayName("Checksum configuration")
	class ChecksumConfigurationTests {

		@DisplayName("Should be able to read record written without CRC32 even if reader is configured to check CRC32")
		@Test
		void shouldWriteRecordWithoutCrc32CheckAndReadAvoidingCrc32EvenIfRequested() throws IOException {
			final StorageRecord<ByteChunk> record;
			try (
				final ObservableOutput<?> output = new ObservableOutput<>(
					new FileOutputStream(StorageRecordTest.this.tempFile), 16_384, 0,
					Checksum.NO_OP,
					null
				)
			) {
				record = new StorageRecord<>(StorageRecordTest.this.kryo, output, 1L, false, generateBytes(256));
			}

			try (
				final ObservableInput<?> input = new ObservableInput<>(
					new FileInputStream(StorageRecordTest.this.tempFile),
					8_192,
					new Crc32CChecksum(),
					null
				)
			) {
				final StorageRecord<ByteChunk> loadedRecord = StorageRecord.read(
					StorageRecordTest.this.kryo, input,
					fl -> {
						assertEquals(record.fileLocation(), fl);
						return ByteChunk.class;
					}
				);

				assertEquals(record, loadedRecord);
			}
		}

		@DisplayName("Single record should be written with CRC32 checksum and read without checking the checksum")
		@Test
		void shouldWriteAndReadRecordWithComputingButNotVerifyingCrc32Check() throws IOException {
			final StorageRecord<ByteChunk> record;
			try (
				final ObservableOutput<?> output = new ObservableOutput<>(
					new FileOutputStream(StorageRecordTest.this.tempFile), 16_384, 0,
					Crc32CChecksumFactory.INSTANCE.createChecksum(),
					null
				)
			) {
				record = new StorageRecord<>(StorageRecordTest.this.kryo, output, 1L, false, generateBytes(256));
			}

			try (
				final ObservableInput<?> input = new ObservableInput<>(
					new FileInputStream(StorageRecordTest.this.tempFile), 8_192, Checksum.NO_OP, null
				)
			) {
				final StorageRecord<ByteChunk> loadedRecord = StorageRecord.read(
					StorageRecordTest.this.kryo, input,
					fl -> {
						assertEquals(record.fileLocation(), fl);
						return ByteChunk.class;
					}
				);
				assertEquals(record, loadedRecord);
			}
		}

	}

	/**
	 * Tests for continuation records that span multiple physical records.
	 */
	@Nested
	@DisplayName("Continuation records")
	class ContinuationRecordTests {

		@DisplayName("Should persist long record spanning several records")
		@ParameterizedTest
		@MethodSource("io.evitadb.store.offsetIndex.model.StorageRecordTest#combineSettings")
		void shouldWriteAndReadLongRecordThatExceedsTheBufferButConsistsOfMultipleSmallItems(
			ChecksumCheck crc32Check, Compression compression) throws IOException {
			final StorageRecord<LongSetChunk> record;
			try (
				final ObservableOutput<?> output = createOutputWithFlush(
					new FileOutputStream(StorageRecordTest.this.tempFile), 512, 2_048, 0, crc32Check, compression
				)
			) {
				record = new StorageRecord<>(StorageRecordTest.this.kryo, output, 1L, true, generateLongSetOfSize(5000));
			}

			try (
				final ObservableInput<?> input = createInput(
					new FileInputStream(StorageRecordTest.this.tempFile), 8_192, crc32Check, compression
				)
			) {
				final StorageRecord<LongSetChunk> loadedRecord = StorageRecord.read(
					StorageRecordTest.this.kryo, input,
					fl -> LongSetChunk.class
				);
				assertEquals(record.fileLocation(), loadedRecord.fileLocation());
				assertEquals(record, loadedRecord);
				assertTrue(loadedRecord.closesGeneration());
			}

			try (
				final ObservableInput<RandomAccessFileInputStream> input = createInput(
					new RandomAccessFileInputStream(new RandomAccessFile(StorageRecordTest.this.tempFile, "r")), 8_192,
					crc32Check, compression
				)
			) {
				final StorageRecord<LongSetChunk> loadedRecord = StorageRecord.read(
					input, record.fileLocation(),
					(stream, length, control) -> StorageRecordTest.this.kryo.readObject(stream, LongSetChunk.class)
				);
				assertEquals(record.fileLocation(), loadedRecord.fileLocation());
				assertEquals(record, loadedRecord);
				assertTrue(loadedRecord.closesGeneration());
			}
		}

		@DisplayName("Should persist and read Roaring64Bitmap over multiple records")
		@ParameterizedTest
		@MethodSource("io.evitadb.store.offsetIndex.model.StorageRecordTest#combineSettings")
		void shouldWriteAndReadLongBitmapOverMultipleRecords(
			ChecksumCheck crc32Check, Compression compression) throws IOException {
			final int cardinality = 4065427;
			final Roaring64Bitmap bitmap = new Roaring64Bitmap();
			for (int i = 0; i < cardinality; i++) {
				bitmap.add(i);
			}

			final StorageRecord<Roaring64Bitmap> record;
			try (
				final ObservableOutput<?> output =
					createOutputWithFlush(new FileOutputStream(StorageRecordTest.this.tempFile), 512, 1024, 0, crc32Check, compression)
			) {
				record = new StorageRecord<>(StorageRecordTest.this.kryo, output, 1L, true, bitmap);
			}

			try (
				final ObservableInput<?> input =
					createInput(new FileInputStream(StorageRecordTest.this.tempFile), 8_192, crc32Check, compression)
			) {
				final StorageRecord<Roaring64Bitmap> loadedRecord = StorageRecord.read(
					StorageRecordTest.this.kryo, input,
					fl -> Roaring64Bitmap.class
				);
				assertEquals(record.fileLocation(), loadedRecord.fileLocation());
				assertEquals(record, loadedRecord);

				final Iterator<Long> it = loadedRecord.payload().iterator();
				for (int i = 0; i < cardinality; i++) {
					assertEquals(Long.valueOf(i), it.next());
				}
			}

			try (
				final ObservableInput<RandomAccessFileInputStream> input = createInput(
					new RandomAccessFileInputStream(new RandomAccessFile(StorageRecordTest.this.tempFile, "r")),
					8_192, crc32Check, compression
				)
			) {
				final StorageRecord<Roaring64Bitmap> loadedRecord = StorageRecord.read(
					input, record.fileLocation(),
					(stream, length, control) -> StorageRecordTest.this.kryo.readObject(stream, Roaring64Bitmap.class)
				);
				assertEquals(record.fileLocation(), loadedRecord.fileLocation());
				assertEquals(record, loadedRecord);
				assertTrue(loadedRecord.closesGeneration());
			}
		}

	}

	/**
	 * Tests for file location operations.
	 */
	@Nested
	@DisplayName("File location operations")
	class FileLocationTests {

		@DisplayName("File locations of multiple records of various random size should be written and read with checksum")
		@ParameterizedTest
		@MethodSource("io.evitadb.store.offsetIndex.model.StorageRecordTest#combineSettings")
		void shouldWriteAndReadFileLocationsOfMultipleDifferentRecordsOfVaryingSize(
			ChecksumCheck crc32Check, Compression compression) throws IOException {
			final int count = 256;

			final Map<FileLocation, StorageRecord<ByteChunk>> index = generateAndWriteRandomRecords(
				count, Functions.noOpConsumer(), crc32Check, compression
			);

			try (
				final ObservableInput<?> input = createInput(
					new RandomAccessFileInputStream(new RandomAccessFile(StorageRecordTest.this.tempFile, "r")), 16_384,
					crc32Check, compression
				)
			) {
				long startPosition = 0;
				for (int i = 0; i < count; i++) {
					final FileLocation location = StorageRecord.readFileLocation(input, startPosition);
					assertTrue(
						index.containsKey(location),
						"Record " + i + " file position doesn't match!"
					);
					startPosition += location.recordLength();
				}
			}
		}

	}

	/**
	 * Tests for cumulative checksum operations.
	 */
	@Nested
	@DisplayName("Cumulative checksum operations")
	class CumulativeChecksumTests {

		@DisplayName("Single record should be read with correct cumulative checksum")
		@ParameterizedTest
		@MethodSource("io.evitadb.store.offsetIndex.model.StorageRecordTest#combineSettings")
		void shouldReadSingleRecordWithCumulativeChecksum(
			ChecksumCheck crc32Check, Compression compression) throws IOException {
			// cumulative checksum only works when checksum computation is enabled
			assumeTrue(crc32Check == ChecksumCheck.YES, "Cumulative checksum requires checksum to be enabled");

			final StorageRecord<ByteChunk> record;
			try (final ObservableOutput<?> output = createOutput(
				new FileOutputStream(StorageRecordTest.this.tempFile), 16_384, 0, crc32Check, compression)) {
				record = new StorageRecord<>(StorageRecordTest.this.kryo, output, 1L, false, generateBytes(256));
			}

			// read the entire file for manual checksum computation
			final byte[] fileBytes = Files.readAllBytes(StorageRecordTest.this.tempFile.toPath());
			final long manualChecksum = computeManualCumulativeChecksum(fileBytes);

			try (final ObservableInput<?> input = createInput(
				new FileInputStream(StorageRecordTest.this.tempFile), 8_192, crc32Check, compression)) {
				final StorageRecordWithChecksum<ByteChunk> result = StorageRecord.readWithChecksum(
					input,
					(stream, length) -> StorageRecordTest.this.kryo.readObject(stream, ByteChunk.class)
				);
				assertEquals(record, result.record());
				assertEquals(
					manualChecksum, result.checksum(),
					"Cumulative checksum should match manually computed checksum"
				);
			}
		}

		@DisplayName("Multiple records should be read with correct individual cumulative checksums")
		@ParameterizedTest
		@MethodSource("io.evitadb.store.offsetIndex.model.StorageRecordTest#combineSettings")
		void shouldReadMultipleRecordsWithIndividualCumulativeChecksums(
			ChecksumCheck crc32Check, Compression compression) throws IOException {
			// cumulative checksum only works when checksum computation is enabled
			assumeTrue(crc32Check == ChecksumCheck.YES, "Cumulative checksum requires checksum to be enabled");

			final int[] payloadSizes = {77, 256, 189};
			final List<StorageRecord<ByteChunk>> records = new ArrayList<>(payloadSizes.length);

			try (final ObservableOutput<?> output = createOutput(
				new FileOutputStream(StorageRecordTest.this.tempFile), 16_384, 0, crc32Check, compression)) {
				for (int payloadSize : payloadSizes) {
					final StorageRecord<ByteChunk> record = new StorageRecord<>(
						StorageRecordTest.this.kryo, output, 1L, false, generateBytes(payloadSize)
					);
					records.add(record);
				}
			}

			// read the entire file for manual checksum computations
			final byte[] fileBytes = Files.readAllBytes(StorageRecordTest.this.tempFile.toPath());

			try (final ObservableInput<?> input = createInput(
				new FileInputStream(StorageRecordTest.this.tempFile), 8_192, crc32Check, compression)) {
				int startPosition = 0;
				for (int i = 0; i < payloadSizes.length; i++) {
					final StorageRecordWithChecksum<ByteChunk> result = StorageRecord.readWithChecksum(
						input,
						(stream, length) -> StorageRecordTest.this.kryo.readObject(stream, ByteChunk.class)
					);

					// compute manual checksum for this record's byte range
					final int endPosition = startPosition + result.record().fileLocation().recordLength();
					final byte[] recordBytes = Arrays.copyOfRange(fileBytes, startPosition, endPosition);
					final long manualChecksum = computeManualCumulativeChecksum(recordBytes);

					assertEquals(records.get(i), result.record(), "Record " + i + " doesn't match!");
					assertEquals(
						manualChecksum, result.checksum(),
						"Cumulative checksum mismatch for record " + i
					);

					startPosition = endPosition;
				}
			}
		}

		@DisplayName("Continuation record spanning multiple physical records should have correct cumulative checksum")
		@ParameterizedTest
		@MethodSource("io.evitadb.store.offsetIndex.model.StorageRecordTest#combineSettings")
		void shouldReadContinuationRecordWithCumulativeChecksum(
			ChecksumCheck crc32Check, Compression compression) throws IOException {
			// cumulative checksum only works when checksum computation is enabled
			assumeTrue(crc32Check == ChecksumCheck.YES, "Cumulative checksum requires checksum to be enabled");

			final StorageRecord<LongSetChunk> record;
			// use small flush/buffer sizes to force record to span multiple physical records
			try (final ObservableOutput<?> output = createOutputWithFlush(
				new FileOutputStream(StorageRecordTest.this.tempFile), 512, 2_048, 0, crc32Check, compression)) {
				record = new StorageRecord<>(StorageRecordTest.this.kryo, output, 1L, true, generateLongSetOfSize(5000));
			}

			// read the entire file for manual checksum computation
			final byte[] fileBytes = Files.readAllBytes(StorageRecordTest.this.tempFile.toPath());
			final long manualChecksum = computeManualCumulativeChecksum(fileBytes);

			try (final ObservableInput<?> input = createInput(
				new FileInputStream(StorageRecordTest.this.tempFile), 8_192, crc32Check, compression)) {
				final StorageRecordWithChecksum<LongSetChunk> result = StorageRecord.readWithChecksum(
					input,
					(stream, length) -> StorageRecordTest.this.kryo.readObject(stream, LongSetChunk.class)
				);
				assertEquals(record.fileLocation(), result.record().fileLocation());
				assertEquals(record, result.record());
				assertTrue(result.record().closesGeneration());
				assertEquals(
					manualChecksum, result.checksum(),
					"Cumulative checksum should cover all physical continuation records"
				);
			}
		}

		@DisplayName("Large bitmap spanning multiple records should have correct cumulative checksum")
		@ParameterizedTest
		@MethodSource("io.evitadb.store.offsetIndex.model.StorageRecordTest#combineSettings")
		void shouldReadLargeBitmapWithCumulativeChecksum(
			ChecksumCheck crc32Check, Compression compression) throws IOException {
			// cumulative checksum only works when checksum computation is enabled
			assumeTrue(crc32Check == ChecksumCheck.YES, "Cumulative checksum requires checksum to be enabled");

			final int cardinality = 4065427;
			final Roaring64Bitmap bitmap = new Roaring64Bitmap();
			for (int i = 0; i < cardinality; i++) {
				bitmap.add(i);
			}

			final StorageRecord<Roaring64Bitmap> record;
			// use small flush/buffer sizes to force record to span multiple physical records
			try (final ObservableOutput<?> output = createOutputWithFlush(
				new FileOutputStream(StorageRecordTest.this.tempFile), 512, 1024, 0, crc32Check, compression)) {
				record = new StorageRecord<>(StorageRecordTest.this.kryo, output, 1L, true, bitmap);
			}

			// read the entire file for manual checksum computation
			final byte[] fileBytes = Files.readAllBytes(StorageRecordTest.this.tempFile.toPath());
			final long manualChecksum = computeManualCumulativeChecksum(fileBytes);

			try (final ObservableInput<?> input = createInput(
				new FileInputStream(StorageRecordTest.this.tempFile), 8_192, crc32Check, compression)) {
				final StorageRecordWithChecksum<Roaring64Bitmap> result = StorageRecord.readWithChecksum(
					input,
					(stream, length) -> StorageRecordTest.this.kryo.readObject(stream, Roaring64Bitmap.class)
				);
				assertEquals(record.fileLocation(), result.record().fileLocation());
				assertEquals(record, result.record());
				assertTrue(result.record().closesGeneration());
				assertEquals(
					manualChecksum, result.checksum(),
					"Cumulative checksum should cover all physical continuation records for bitmap"
				);

				// verify bitmap content
				final Iterator<Long> it = result.record().payload().iterator();
				for (int i = 0; i < cardinality; i++) {
					assertEquals(Long.valueOf(i), it.next());
				}
			}
		}

		@DisplayName("Single record should be read with correct cumulative checksum using random access")
		@ParameterizedTest
		@MethodSource("io.evitadb.store.offsetIndex.model.StorageRecordTest#combineSettings")
		void shouldReadSingleRecordWithCumulativeChecksumUsingRandomAccess(
			ChecksumCheck crc32Check, Compression compression) throws IOException {
			// cumulative checksum only works when checksum computation is enabled
			assumeTrue(crc32Check == ChecksumCheck.YES, "Cumulative checksum requires checksum to be enabled");

			final StorageRecord<ByteChunk> record;
			try (final ObservableOutput<?> output = createOutput(
				new FileOutputStream(StorageRecordTest.this.tempFile), 16_384, 0, crc32Check, compression)) {
				record = new StorageRecord<>(StorageRecordTest.this.kryo, output, 1L, false, generateBytes(256));
			}

			// read the entire file for manual checksum computation
			final byte[] fileBytes = Files.readAllBytes(StorageRecordTest.this.tempFile.toPath());
			final long manualChecksum = computeManualCumulativeChecksum(fileBytes);

			try (
				final RandomAccessFileInputStream is =
					new RandomAccessFileInputStream(new RandomAccessFile(StorageRecordTest.this.tempFile, "r"));
				final ObservableInput<RandomAccessFileInputStream> input =
					createInput(is, 8_192, crc32Check, compression)
			) {
				final StorageRecordWithChecksum<ByteChunk> result = StorageRecord.readWithChecksum(
					input,
					record.fileLocation(),
					(stream, length, control) -> StorageRecordTest.this.kryo.readObject(stream, ByteChunk.class)
				);
				assertEquals(record, result.record());
				assertEquals(
					manualChecksum, result.checksum(),
					"Cumulative checksum should match manually computed checksum"
				);
			}
		}

		@DisplayName("Single record should be written with correct cumulative checksum")
		@ParameterizedTest
		@MethodSource("io.evitadb.store.offsetIndex.model.StorageRecordTest#combineSettings")
		void shouldWriteSingleRecordWithCumulativeChecksum(
			ChecksumCheck crc32Check, Compression compression) throws IOException {
			assumeTrue(crc32Check == ChecksumCheck.YES, "Cumulative checksum requires checksum to be enabled");

			final long writeCumulativeChecksum;
			try (final ObservableOutput<?> output = createOutput(
				new FileOutputStream(StorageRecordTest.this.tempFile), 16_384, 0, crc32Check, compression)) {
				output.markCumulativeChecksumStart();
				//noinspection ResultOfObjectAllocationIgnored
				new StorageRecord<>(StorageRecordTest.this.kryo, output, 1L, false, generateBytes(256));
				writeCumulativeChecksum = output.markCumulativeChecksumEnd();
			}

			final byte[] fileBytes = Files.readAllBytes(StorageRecordTest.this.tempFile.toPath());
			final long manualChecksum = computeManualCumulativeChecksum(fileBytes);

			assertEquals(manualChecksum, writeCumulativeChecksum,
				"Write cumulative checksum should match file bytes checksum");
		}

		@DisplayName("Multiple records should be written with correct cumulative checksum")
		@ParameterizedTest
		@MethodSource("io.evitadb.store.offsetIndex.model.StorageRecordTest#combineSettings")
		void shouldWriteMultipleRecordsWithCumulativeChecksum(
			ChecksumCheck crc32Check, Compression compression) throws IOException {
			assumeTrue(crc32Check == ChecksumCheck.YES, "Cumulative checksum requires checksum to be enabled");

			final int[] payloadSizes = {77, 256, 189, 512};
			final long writeCumulativeChecksum;

			try (final ObservableOutput<?> output = createOutput(
				new FileOutputStream(StorageRecordTest.this.tempFile), 16_384, 0, crc32Check, compression)) {
				output.markCumulativeChecksumStart();
				for (int payloadSize : payloadSizes) {
					//noinspection ResultOfObjectAllocationIgnored
					new StorageRecord<>(StorageRecordTest.this.kryo, output, 1L, false, generateBytes(payloadSize));
				}
				writeCumulativeChecksum = output.markCumulativeChecksumEnd();
			}

			final byte[] fileBytes = Files.readAllBytes(StorageRecordTest.this.tempFile.toPath());
			final long manualChecksum = computeManualCumulativeChecksum(fileBytes);

			assertEquals(manualChecksum, writeCumulativeChecksum,
				"Write cumulative checksum should match file bytes checksum for multiple records");
		}

		@DisplayName("Write cumulative checksum should match read cumulative checksum")
		@ParameterizedTest
		@MethodSource("io.evitadb.store.offsetIndex.model.StorageRecordTest#combineSettings")
		void shouldMatchWriteAndReadCumulativeChecksums(
			ChecksumCheck crc32Check, Compression compression) throws IOException {
			assumeTrue(crc32Check == ChecksumCheck.YES, "Cumulative checksum requires checksum to be enabled");

			final long writeCumulativeChecksum;
			try (final ObservableOutput<?> output = createOutput(
				new FileOutputStream(StorageRecordTest.this.tempFile), 16_384, 0, crc32Check, compression)) {
				output.markCumulativeChecksumStart();
				//noinspection ResultOfObjectAllocationIgnored
				new StorageRecord<>(StorageRecordTest.this.kryo, output, 1L, false, generateBytes(256));
				writeCumulativeChecksum = output.markCumulativeChecksumEnd();
			}

			try (final ObservableInput<?> input = createInput(
				new FileInputStream(StorageRecordTest.this.tempFile), 8_192, crc32Check, compression)) {
				final StorageRecordWithChecksum<ByteChunk> result = StorageRecord.readWithChecksum(
					input, (stream, length) -> StorageRecordTest.this.kryo.readObject(stream, ByteChunk.class));

				assertEquals(writeCumulativeChecksum, result.checksum(),
					"Write cumulative checksum should match read cumulative checksum");
			}
		}

		@DisplayName("Cumulative checksum with initial value should match full file checksum")
		@ParameterizedTest
		@MethodSource("io.evitadb.store.offsetIndex.model.StorageRecordTest#combineSettings")
		void shouldCombineInitialChecksumWithRecordChecksums(
			ChecksumCheck crc32Check, Compression compression) throws IOException {
			// cumulative checksum only works when checksum computation is enabled
			assumeTrue(crc32Check == ChecksumCheck.YES, "Cumulative checksum requires checksum to be enabled");

			// Step 1: Write initial content to file and calculate its CRC32C
			final byte[] initialContent = new byte[256];
			StorageRecordTest.this.random.nextBytes(initialContent);
			try (final FileOutputStream fos = new FileOutputStream(StorageRecordTest.this.tempFile)) {
				fos.write(initialContent);
			}
			final long initialChecksum = computeManualCumulativeChecksum(initialContent);
			final int initialContentLength = initialContent.length;

			// Step 2: Create ObservableOutput in append mode with initial checksum
			final long writeCumulativeChecksum;
			try (final FileOutputStream fos = new FileOutputStream(StorageRecordTest.this.tempFile, true);
			     final ObservableOutput<?> output = createOutput(fos, 16_384, initialContentLength, crc32Check, compression)) {
				// Step 3: Start cumulative checksum with initial value
				output.markCumulativeChecksumStart(initialChecksum);

				// Step 4: Write a few records
				final int[] payloadSizes = {77, 256, 189};
				for (int payloadSize : payloadSizes) {
					//noinspection ResultOfObjectAllocationIgnored
					new StorageRecord<>(StorageRecordTest.this.kryo, output, 1L, false, generateBytes(payloadSize));
				}

				// Step 5: Mark cumulative end
				writeCumulativeChecksum = output.markCumulativeChecksumEnd();
			}

			// Step 6: Calculate CRC32C from entire file and verify match
			final byte[] fileBytes = Files.readAllBytes(StorageRecordTest.this.tempFile.toPath());
			final long manualChecksum = computeManualCumulativeChecksum(fileBytes);

			assertEquals(manualChecksum, writeCumulativeChecksum,
				"Cumulative checksum with initial value should match full file checksum");
		}

	}

	/**
	 * Tests for error handling scenarios.
	 */
	@Nested
	@DisplayName("Error handling")
	class ErrorHandlingTests {

		@DisplayName("Should throw CorruptedRecordException when CRC32 checksum is corrupted")
		@Test
		void shouldThrowCorruptedRecordExceptionWhenCrc32Mismatch() throws IOException {
			// write a valid record with CRC32
			try (
				final ObservableOutput<?> output = createOutput(
				new FileOutputStream(StorageRecordTest.this.tempFile),
				16_384, 0, ChecksumCheck.YES, Compression.NO
				)
			) {
				//noinspection ResultOfObjectAllocationIgnored
				new StorageRecord<>(StorageRecordTest.this.kryo, output, 1L, false, generateBytes(256));
			}

			// corrupt the CRC32 checksum (last 8 bytes of the file)
			final byte[] fileBytes = Files.readAllBytes(StorageRecordTest.this.tempFile.toPath());
			// flip some bits in the CRC32 area
			fileBytes[fileBytes.length - 1] ^= (byte) 0xFF;
			fileBytes[fileBytes.length - 2] ^= (byte) 0xFF;
			Files.write(StorageRecordTest.this.tempFile.toPath(), fileBytes);

			// try to read - should throw CorruptedRecordException
			try (final ObservableInput<?> input = createInput(
				new FileInputStream(StorageRecordTest.this.tempFile), 8_192, ChecksumCheck.YES, Compression.NO)) {
				assertThrows(
					CorruptedRecordException.class,
					() -> StorageRecord.read(
						StorageRecordTest.this.kryo, input,
						fl -> ByteChunk.class
					),
					"Should throw CorruptedRecordException when CRC32 is corrupted"
				);
			}
		}

		@DisplayName("Should handle null type resolver for obsolete records")
		@Test
		void shouldHandleNullTypeResolverForObsoleteRecord() throws IOException {
			// write a valid record
			final StorageRecord<ByteChunk> originalRecord;
			try (final ObservableOutput<?> output = createOutput(
				new FileOutputStream(StorageRecordTest.this.tempFile), 16_384, 0, ChecksumCheck.NO, Compression.NO)) {
				originalRecord = new StorageRecord<>(StorageRecordTest.this.kryo, output, 1L, false, generateBytes(256));
			}

			// read with type resolver returning null (simulating obsolete record)
			try (final ObservableInput<?> input = createInput(
				new FileInputStream(StorageRecordTest.this.tempFile), 8_192, ChecksumCheck.NO, Compression.NO)) {
				final StorageRecord<ByteChunk> loadedRecord = StorageRecord.read(
					StorageRecordTest.this.kryo, input,
					fl -> null  // returns null for obsolete record
				);

				// record should be returned with null payload
				assertNotNull(loadedRecord);
				assertNull(loadedRecord.payload());
				assertEquals(originalRecord.fileLocation(), loadedRecord.fileLocation());
				assertEquals(-1L, loadedRecord.generationId());
			}
		}

	}

	/**
	 * Tests for edge cases.
	 */
	@Nested
	@DisplayName("Edge cases")
	class EdgeCaseTests {

		@DisplayName("Should write and read record with empty payload")
		@ParameterizedTest
		@MethodSource("io.evitadb.store.offsetIndex.model.StorageRecordTest#combineSettings")
		void shouldWriteAndReadRecordWithEmptyPayload(ChecksumCheck crc32Check, Compression compression) throws IOException {
			final StorageRecord<ByteChunk> record;
			try (final ObservableOutput<?> output = createOutput(
				new FileOutputStream(StorageRecordTest.this.tempFile), 16_384, 0, crc32Check, compression)) {
				// write record with 0-byte payload
				record = new StorageRecord<>(StorageRecordTest.this.kryo, output, 1L, false, generateBytes(0));
			}

			try (final ObservableInput<?> input = createInput(
				new FileInputStream(StorageRecordTest.this.tempFile), 8_192, crc32Check, compression)) {
				final StorageRecord<ByteChunk> loadedRecord = StorageRecord.read(
					StorageRecordTest.this.kryo, input,
					fl -> ByteChunk.class
				);
				assertEquals(record, loadedRecord);
				assertEquals(0, loadedRecord.payload().data().length);
			}
		}

		@DisplayName("Should write and read record with closesGeneration flag set")
		@Test
		void shouldWriteAndReadRecordWithClosesGenerationFlag() throws IOException {
			final StorageRecord<ByteChunk> record;
			try (final ObservableOutput<?> output = createOutput(
				new FileOutputStream(StorageRecordTest.this.tempFile), 16_384, 0, ChecksumCheck.YES, Compression.NO)) {
				record = new StorageRecord<>(StorageRecordTest.this.kryo, output, 42L, true, generateBytes(100));
			}

			try (final ObservableInput<?> input = createInput(
				new FileInputStream(StorageRecordTest.this.tempFile), 8_192, ChecksumCheck.YES, Compression.NO)) {
				final StorageRecord<ByteChunk> loadedRecord = StorageRecord.read(
					StorageRecordTest.this.kryo, input,
					fl -> ByteChunk.class
				);
				assertEquals(record, loadedRecord);
				assertTrue(loadedRecord.closesGeneration());
				assertEquals(42L, loadedRecord.generationId());
			}
		}

	}

	/**
	 * Tests for raw record operations.
	 */
	@Nested
	@DisplayName("Raw record operations")
	class RawRecordTests {

		@DisplayName("Should read raw record preserving compressed data")
		@Test
		void shouldReadRawRecordPreservingCompressedData() throws IOException {
			// write a compressed record
			try (final ObservableOutput<?> output = createOutput(
				new FileOutputStream(StorageRecordTest.this.tempFile),
				16_384, 0, ChecksumCheck.YES, Compression.YES)
			) {
				//noinspection ResultOfObjectAllocationIgnored
				new StorageRecord<>(StorageRecordTest.this.kryo, output, 1L, false, generateBytes(256));
			}

			// read as raw record
			try (final ObservableInput<?> input = createInput(
				new FileInputStream(StorageRecordTest.this.tempFile), 8_192, ChecksumCheck.YES, Compression.YES)) {
				final RawRecord rawRecord = StorageRecord.readRaw(input);

				assertNotNull(rawRecord);
				assertNotNull(rawRecord.rawData());
				assertTrue(rawRecord.rawData().length > 0);
				assertEquals(1L, rawRecord.generationId());
			}
		}

		@DisplayName("Should round-trip raw record correctly")
		@Test
		void shouldRoundTripRawRecordCorrectly() throws IOException {
			// write original record
			final StorageRecord<ByteChunk> originalRecord;
			try (final ObservableOutput<?> output = createOutput(
				new FileOutputStream(StorageRecordTest.this.tempFile), 16_384, 0, ChecksumCheck.YES, Compression.NO)) {
				originalRecord = new StorageRecord<>(StorageRecordTest.this.kryo, output, 1L, false, generateBytes(256));
			}

			// read as raw
			final RawRecord rawRecord;
			try (final ObservableInput<?> input = createInput(
				new FileInputStream(StorageRecordTest.this.tempFile), 8_192, ChecksumCheck.YES, Compression.NO)) {
				rawRecord = StorageRecord.readRaw(input);
			}

			// write raw to new file
			final File tempFile2 = Path.of(System.getProperty("java.io.tmpdir")).resolve("evita_test_file2.tmp").toFile();
			try {
				try (final ObservableOutput<?> output = createOutput(
					new FileOutputStream(tempFile2), 16_384, 0, ChecksumCheck.YES, Compression.NO)) {
					StorageRecord.writeRaw(output, rawRecord.control(), rawRecord.generationId(), rawRecord.rawData());
				}

				// verify files are identical
				final byte[] originalBytes = Files.readAllBytes(StorageRecordTest.this.tempFile.toPath());
				final byte[] copiedBytes = Files.readAllBytes(tempFile2.toPath());
				assertArrayEquals(originalBytes, copiedBytes, "Raw round-trip should produce identical file");
			} finally {
				tempFile2.delete();
			}
		}

	}

	/**
	 * Tests for static utility methods.
	 */
	@Nested
	@DisplayName("Static utilities")
	class StaticUtilityTests {

		@DisplayName("Should return correct overhead size")
		@Test
		void shouldReturnCorrectOverheadSize() {
			// OVERHEAD_SIZE = CRC_NOT_COVERED_HEAD + TAIL_MANDATORY_SPACE = 13 + 8 = 21
			assertEquals(21, StorageRecord.getOverheadSize());
			assertEquals(StorageRecord.OVERHEAD_SIZE, StorageRecord.getOverheadSize());
		}

		@DisplayName("Should read file location at arbitrary position")
		@Test
		void shouldReadFileLocationAtArbitraryPosition() throws IOException {
			// write multiple records
			final List<StorageRecord<ByteChunk>> records = new ArrayList<>();
			try (final ObservableOutput<?> output = createOutput(
				new FileOutputStream(StorageRecordTest.this.tempFile), 16_384, 0, ChecksumCheck.YES, Compression.NO)) {
				records.add(new StorageRecord<>(StorageRecordTest.this.kryo, output, 1L, false, generateBytes(100)));
				records.add(new StorageRecord<>(StorageRecordTest.this.kryo, output, 2L, false, generateBytes(200)));
				records.add(new StorageRecord<>(StorageRecordTest.this.kryo, output, 3L, false, generateBytes(150)));
			}

			// read file location for second record
			try (final ObservableInput<?> input = createInput(
				new RandomAccessFileInputStream(new RandomAccessFile(StorageRecordTest.this.tempFile, "r")),
				8_192, ChecksumCheck.YES, Compression.NO)) {
				final long secondRecordStart = records.get(0).fileLocation().recordLength();
				final FileLocation location = StorageRecord.readFileLocation(input, secondRecordStart);

				assertEquals(records.get(1).fileLocation(), location);
			}
		}

	}

	private enum ChecksumCheck {
		YES, NO
	}

	private enum Compression {
		YES, NO
	}

	private record ByteChunk(@Nonnull byte[] data) {

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			final ByteChunk byteChunk = (ByteChunk) o;
			return Arrays.equals(this.data, byteChunk.data);
		}

		@Override
		public int hashCode() {
			return Arrays.hashCode(this.data);
		}

	}

	private static class ByteChunkSerializer extends Serializer<ByteChunk> {

		@Override
		public void write(Kryo kryo, Output output, ByteChunk object) {
			output.writeInt(object.data.length);
			output.writeBytes(object.data);
		}

		@Override
		public ByteChunk read(Kryo kryo, Input input, Class<? extends ByteChunk> type) {
			final int length = input.readInt();
			return new ByteChunk(input.readBytes(length));
		}

	}

	private record LongSetChunk(@Nonnull Long[] longSet) {

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			final LongSetChunk that = (LongSetChunk) o;
			return Arrays.equals(this.longSet, that.longSet);
		}

		@Override
		public int hashCode() {
			return Arrays.hashCode(this.longSet);
		}
	}

	private static class LongSetChunkSerializer extends Serializer<LongSetChunk> {

		@Override
		public void write(Kryo kryo, Output output, LongSetChunk object) {
			output.writeInt(object.longSet.length);
			for (Long aLong : object.longSet) {
				output.writeLong(aLong);
			}
		}

		@Override
		public LongSetChunk read(Kryo kryo, Input input, Class<? extends LongSetChunk> type) {
			final int length = input.readInt();
			final Long[] longSet = new Long[length];
			for (int i = 0; i < length; i++) {
				longSet[i] = input.readLong();
			}
			return new LongSetChunk(longSet);
		}

	}

	private static class Roaring64BitmapSerializer extends Serializer<Roaring64Bitmap> {

		@Override
		public void write(Kryo kryo, Output output, Roaring64Bitmap object) {
			try {
				object.serialize(new KryoDataOutput(output));
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
		}

		@Override
		public Roaring64Bitmap read(Kryo kryo, Input input, Class<? extends Roaring64Bitmap> type) {
			final Roaring64Bitmap bitmap = new Roaring64Bitmap();
			try {
				bitmap.deserialize(new KryoDataInput(input));
			} catch (IOException e) {
				throw new IllegalStateException("Cannot store bitmap!", e);
			}
			return bitmap;
		}

	}

}
