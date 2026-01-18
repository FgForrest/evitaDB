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
import io.evitadb.store.checksum.Crc32CChecksumCalculator;
import io.evitadb.store.checksum.Crc32CChecksumCalculatorFactory;
import io.evitadb.store.compression.CompressionFactory;
import io.evitadb.store.compression.ZipCompressionFactory;
import io.evitadb.store.kryo.ObservableInput;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.store.offsetIndex.model.StorageRecord.StorageRecordWithChecksum;
import io.evitadb.store.shared.model.FileLocation;
import io.evitadb.stream.RandomAccessFileInputStream;
import io.evitadb.utils.Crc32CWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * This test verifies the behaviour of {@link StorageRecord}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
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
	 * Configures the given {@link ObservableOutput} by optionally computing a CRC32 checksum
	 * and/or applying compress based on the provided parameters.
	 *
	 * @param outputStream the undelying {@link ObservableOutput} to be used
	 * @param crc32Check   an enum indicating whether CRC32 checksum computation should be enabled
	 * @param compression  an enum indicating whether compress should be applied
	 * @return the configured {@link ObservableOutput}
	 */
	@Nonnull
	private static <T extends OutputStream> ObservableOutput<T> create(
		@Nonnull T outputStream,
		int bufferSize,
		int currentFileSize,
		@Nonnull ChecksumCheck crc32Check,
		@Nonnull Compression compression
	) {
		return new ObservableOutput<>(
			outputStream,
			bufferSize,
			currentFileSize,
			crc32Check == ChecksumCheck.YES ?
				new Crc32CChecksumCalculator() : Checksum.NO_OP,
			(compression == Compression.YES ?
				new ZipCompressionFactory() : CompressionFactory.NO_COMPRESSION)
				.createCompressor()
				.orElse(null)
		);
	}

	/**
	 * Configures the given {@link ObservableOutput} by optionally computing a CRC32 checksum
	 * and/or applying compress based on the provided parameters.
	 *
	 * @param outputStream the undelying {@link ObservableOutput} to be used
	 * @param crc32Check   an enum indicating whether CRC32 checksum computation should be enabled
	 * @param compression  an enum indicating whether compress should be applied
	 * @return the configured {@link ObservableOutput}
	 */
	@Nonnull
	private static <T extends OutputStream> ObservableOutput<T> create(
		@Nonnull T outputStream,
		int flushSize,
		int bufferSize,
		int currentFileSize,
		@Nonnull ChecksumCheck crc32Check,
		@Nonnull Compression compression
	) {
		return new ObservableOutput<>(
			outputStream,
			flushSize,
			bufferSize,
			currentFileSize,
			crc32Check == ChecksumCheck.YES ?
				new Crc32CChecksumCalculator() : Checksum.NO_OP,
			(compression == Compression.YES ?
				new ZipCompressionFactory() : CompressionFactory.NO_COMPRESSION)
				.createCompressor()
				.orElse(null)
		);
	}

	/**
	 * Configures the given {@link ObservableOutput} by optionally computing a CRC32 checksum
	 * and/or applying compress based on the provided parameters.
	 *
	 * @param inputStream the undelying {@link InputStream} to be used
	 * @param crc32Check  an enum indicating whether CRC32 checksum computation should be enabled
	 * @param compression an enum indicating whether compress should be applied
	 * @return the configured {@link ObservableOutput}
	 */
	@Nonnull
	private static <T extends InputStream> ObservableInput<T> create(
		@Nonnull T inputStream,
		int bufferSize,
		@Nonnull ChecksumCheck crc32Check,
		@Nonnull Compression compression
	) {
		return new ObservableInput<>(
			inputStream,
			bufferSize,
			crc32Check == ChecksumCheck.YES ?
				new Crc32CChecksumCalculator() : Checksum.NO_OP,
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

	@DisplayName("Single record should be written and read intact")
	@ParameterizedTest
	@MethodSource("combineSettings")
	void shouldWriteAndReadRecord(ChecksumCheck crc32Check, Compression compression) throws IOException {
		final StorageRecord<ByteChunk> record;
		try (final ObservableOutput<?> output = create(
			new FileOutputStream(this.tempFile), 16_384, 0, crc32Check, compression)) {
			record = new StorageRecord<>(this.kryo, output, 1L, false, generateBytes(256));
		}

		try (final ObservableInput<?> input = create(
			new FileInputStream(this.tempFile), 8_192, crc32Check, compression)) {
			final StorageRecord<ByteChunk> loadedRecord = StorageRecord.read(
				this.kryo, input,
				fl -> {
					assertEquals(record.fileLocation(), fl);
					return ByteChunk.class;
				}
			);
			assertEquals(record, loadedRecord);
		}
	}

	@DisplayName("Should be able to read record written without CRC32 even if reader is configured to check CRC32")
	@Test
	void shouldWriteRecordWithoutCrc32CheckAndReadAvoidingCrc32EvenIfRequested() throws IOException {
		final StorageRecord<ByteChunk> record;
		try (
			final ObservableOutput<?> output = new ObservableOutput<>(
				new FileOutputStream(this.tempFile), 16_384, 0,
				Checksum.NO_OP,
				null
			)
		) {
			record = new StorageRecord<>(this.kryo, output, 1L, false, generateBytes(256));
		}

		try (
			final ObservableInput<?> input = new ObservableInput<>(
				new FileInputStream(this.tempFile),
				8_192,
				new Crc32CChecksumCalculator(),
				null
			)
		) {
			final StorageRecord<ByteChunk> loadedRecord = StorageRecord.read(
				this.kryo, input,
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
				new FileOutputStream(this.tempFile), 16_384, 0,
				Crc32CChecksumCalculatorFactory.INSTANCE.createChecksum(),
				null
			)
		) {
			record = new StorageRecord<>(this.kryo, output, 1L, false, generateBytes(256));
		}

		try (
			final ObservableInput<?> input = new ObservableInput<>(
				new FileInputStream(this.tempFile), 8_192, Checksum.NO_OP, null
			)
		) {
			final StorageRecord<ByteChunk> loadedRecord = StorageRecord.read(
				this.kryo, input,
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
	@MethodSource("combineSettings")
	void shouldWriteAndReadMultipleDifferentRecordsOfVaryingSize(
		ChecksumCheck crc32Check, Compression compression) throws IOException {
		final int count = 256;
		final List<StorageRecord<ByteChunk>> records = new ArrayList<>(count);
		final Map<FileLocation, StorageRecord<ByteChunk>> index = generateAndWriteRandomRecords(
			count, records::add, crc32Check, compression);

		try (
			final ObservableInput<?> input = create(
				new FileInputStream(this.tempFile), 16_384, crc32Check, compression
			)
		) {
			for (int i = 0; i < count; i++) {
				final StorageRecord<ByteChunk> loadedRecord = StorageRecord.read(
					this.kryo, input,
					fl -> ByteChunk.class
				);
				assertEquals(records.get(i), loadedRecord, "Record " + i + " doesn't match!");
				final StorageRecord<ByteChunk> originalRecord = index.get(loadedRecord.fileLocation());
				assertNotNull(originalRecord);
				assertEquals(originalRecord, loadedRecord);
			}
		}
	}

	@DisplayName("File locations of multiple records of various random size should be written and read with checksum")
	@ParameterizedTest
	@MethodSource("combineSettings")
	void shouldWriteAndReadFileLocationsOfMultipleDifferentRecordsOfVaryingSize(
		ChecksumCheck crc32Check, Compression compression) throws IOException {
		final int count = 256;

		final Map<FileLocation, StorageRecord<ByteChunk>> index = generateAndWriteRandomRecords(
			count, Functions.noOpConsumer(), crc32Check, compression
		);

		try (
			final ObservableInput<?> input = create(
				new RandomAccessFileInputStream(new RandomAccessFile(this.tempFile, "r")), 16_384,
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

	@DisplayName("Multiple records should be written and read in random order")
	@ParameterizedTest
	@MethodSource("combineSettings")
	void shouldReadRandomRecords(ChecksumCheck crc32Check, Compression compression) throws FileNotFoundException {
		final Map<FileLocation, StorageRecord<ByteChunk>> index = new HashMap<>(8);
		try (
			final ObservableOutput<?> output = create(
				new FileOutputStream(this.tempFile), 16_384, 0, crc32Check, compression
			)
		) {
			writeRandomRecord(index, output, 256);
			writeRandomRecord(index, output, 178);
			writeRandomRecord(index, output, 453);
		}

		try (
			final RandomAccessFileInputStream is =
				new RandomAccessFileInputStream(new RandomAccessFile(this.tempFile, "r"));
			final ObservableInput<RandomAccessFileInputStream> input =
				create(is, 8_192, crc32Check, compression)
		) {
			index.forEach(
				(key, expectedRecord) -> {
					final StorageRecord<ByteChunk> loadedRecord = StorageRecord.read(
						input, key, (stream, length, control) -> this.kryo.readObject(stream, ByteChunk.class));
					assertEquals(expectedRecord, loadedRecord);
				}
			);
		}
	}

	@DisplayName("Multiple records with random content size should be written and read in random order")
	@ParameterizedTest
	@MethodSource("combineSettings")
	void shouldWriteAndReadRandomlyMultipleDifferentRecordsOfVaryingSize(
		ChecksumCheck crc32Check, Compression compression) throws IOException {
		final int count = 256;
		final int retrievalCount = 512;
		final Map<FileLocation, StorageRecord<ByteChunk>> index = generateAndWriteRandomRecords(
			count, rec -> {}, crc32Check, compression
		);
		final List<FileLocation> locations = new ArrayList<>(index.keySet());

		try (final RandomAccessFileInputStream is =
			     new RandomAccessFileInputStream(new RandomAccessFile(this.tempFile, "r"));
		     final ObservableInput<RandomAccessFileInputStream> input =
			     create(is, 8_192 << 1, crc32Check, compression)
		) {
			for (int i = 0; i < retrievalCount; i++) {
				final FileLocation randomLocation = locations.get(this.random.nextInt(locations.size()));
				final StorageRecord<ByteChunk> loadedRecord = StorageRecord.read(
					input, randomLocation, (stream, length, control) -> this.kryo.readObject(stream, ByteChunk.class));
				final StorageRecord<ByteChunk> expectedRecord = index.get(randomLocation);
				assertEquals(expectedRecord, loadedRecord);
			}
		}
	}

	@DisplayName("Should persist long record spanning several records")
	@ParameterizedTest
	@MethodSource("combineSettings")
	void shouldWriteAndReadLongRecordThatExceedsTheBufferButConsistsOfMultipleSmallItems(
		ChecksumCheck crc32Check, Compression compression) throws IOException {
		final StorageRecord<LongSetChunk> record;
		try (
			final ObservableOutput<?> output = create(
				new FileOutputStream(this.tempFile), 512, 2_048, 0, crc32Check, compression
			)
		) {
			record = new StorageRecord<>(this.kryo, output, 1L, true, generateLongSetOfSize(5000));
		}

		try (
			final ObservableInput<?> input = create(
				new FileInputStream(this.tempFile), 8_192, crc32Check, compression
			)
		) {
			final StorageRecord<LongSetChunk> loadedRecord = StorageRecord.read(
				this.kryo, input,
				fl -> LongSetChunk.class
			);
			assertEquals(record.fileLocation(), loadedRecord.fileLocation());
			assertEquals(record, loadedRecord);
			assertTrue(loadedRecord.closesGeneration());
		}

		try (
			final ObservableInput<RandomAccessFileInputStream> input = create(
				new RandomAccessFileInputStream(new RandomAccessFile(this.tempFile, "r")), 8_192,
				crc32Check, compression
			)
		) {
			final StorageRecord<LongSetChunk> loadedRecord = StorageRecord.read(
				input, record.fileLocation(),
				(stream, length, control) -> this.kryo.readObject(stream, LongSetChunk.class)
			);
			assertEquals(record.fileLocation(), loadedRecord.fileLocation());
			assertEquals(record, loadedRecord);
			assertTrue(loadedRecord.closesGeneration());
		}
	}

	@DisplayName("Should persist and read Roaring64Bitmap over multiple records")
	@ParameterizedTest
	@MethodSource("combineSettings")
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
				create(new FileOutputStream(this.tempFile), 512, 1024, 0, crc32Check, compression)
		) {
			record = new StorageRecord<>(this.kryo, output, 1L, true, bitmap);
		}

		try (
			final ObservableInput<?> input =
				create(new FileInputStream(this.tempFile), 8_192, crc32Check, compression)
		) {
			final StorageRecord<Roaring64Bitmap> loadedRecord = StorageRecord.read(
				this.kryo, input,
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
			final ObservableInput<RandomAccessFileInputStream> input = create(
				new RandomAccessFileInputStream(new RandomAccessFile(this.tempFile, "r")),
				8_192, crc32Check, compression
			)
		) {
			final StorageRecord<Roaring64Bitmap> loadedRecord = StorageRecord.read(
				input, record.fileLocation(),
				(stream, length, control) -> this.kryo.readObject(stream, Roaring64Bitmap.class)
			);
			assertEquals(record.fileLocation(), loadedRecord.fileLocation());
			assertEquals(record, loadedRecord);
			assertTrue(loadedRecord.closesGeneration());
		}
	}

	@DisplayName("Single record should be read with correct cumulative checksum")
	@ParameterizedTest
	@MethodSource("combineSettings")
	void shouldReadSingleRecordWithCumulativeChecksum(
		ChecksumCheck crc32Check, Compression compression) throws IOException {
		// cumulative checksum only works when checksum computation is enabled
		assumeTrue(crc32Check == ChecksumCheck.YES, "Cumulative checksum requires checksum to be enabled");

		final StorageRecord<ByteChunk> record;
		try (final ObservableOutput<?> output = create(
			new FileOutputStream(this.tempFile), 16_384, 0, crc32Check, compression)) {
			record = new StorageRecord<>(this.kryo, output, 1L, false, generateBytes(256));
		}

		// read the entire file for manual checksum computation
		final byte[] fileBytes = Files.readAllBytes(this.tempFile.toPath());
		final long manualChecksum = computeManualCumulativeChecksum(fileBytes);

		try (final ObservableInput<?> input = create(
			new FileInputStream(this.tempFile), 8_192, crc32Check, compression)) {
			final StorageRecordWithChecksum<ByteChunk> result = StorageRecord.readWithChecksum(
				input,
				(stream, length) -> this.kryo.readObject(stream, ByteChunk.class)
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
	@MethodSource("combineSettings")
	void shouldReadMultipleRecordsWithIndividualCumulativeChecksums(
		ChecksumCheck crc32Check, Compression compression) throws IOException {
		// cumulative checksum only works when checksum computation is enabled
		assumeTrue(crc32Check == ChecksumCheck.YES, "Cumulative checksum requires checksum to be enabled");

		final int[] payloadSizes = {77, 256, 189};
		final List<StorageRecord<ByteChunk>> records = new ArrayList<>(payloadSizes.length);

		try (final ObservableOutput<?> output = create(
			new FileOutputStream(this.tempFile), 16_384, 0, crc32Check, compression)) {
			for (int payloadSize : payloadSizes) {
				final StorageRecord<ByteChunk> record = new StorageRecord<>(
					this.kryo, output, 1L, false, generateBytes(payloadSize)
				);
				records.add(record);
			}
		}

		// read the entire file for manual checksum computations
		final byte[] fileBytes = Files.readAllBytes(this.tempFile.toPath());

		try (final ObservableInput<?> input = create(
			new FileInputStream(this.tempFile), 8_192, crc32Check, compression)) {
			int startPosition = 0;
			for (int i = 0; i < payloadSizes.length; i++) {
				final StorageRecordWithChecksum<ByteChunk> result = StorageRecord.readWithChecksum(
					input,
					(stream, length) -> this.kryo.readObject(stream, ByteChunk.class)
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
	@MethodSource("combineSettings")
	void shouldReadContinuationRecordWithCumulativeChecksum(
		ChecksumCheck crc32Check, Compression compression) throws IOException {
		// cumulative checksum only works when checksum computation is enabled
		assumeTrue(crc32Check == ChecksumCheck.YES, "Cumulative checksum requires checksum to be enabled");

		final StorageRecord<LongSetChunk> record;
		// use small flush/buffer sizes to force record to span multiple physical records
		try (final ObservableOutput<?> output = create(
			new FileOutputStream(this.tempFile), 512, 2_048, 0, crc32Check, compression)) {
			record = new StorageRecord<>(this.kryo, output, 1L, true, generateLongSetOfSize(5000));
		}

		// read the entire file for manual checksum computation
		final byte[] fileBytes = Files.readAllBytes(this.tempFile.toPath());
		final long manualChecksum = computeManualCumulativeChecksum(fileBytes);

		try (final ObservableInput<?> input = create(
			new FileInputStream(this.tempFile), 8_192, crc32Check, compression)) {
			final StorageRecordWithChecksum<LongSetChunk> result = StorageRecord.readWithChecksum(
				input,
				(stream, length) -> this.kryo.readObject(stream, LongSetChunk.class)
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
	@MethodSource("combineSettings")
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
		try (final ObservableOutput<?> output = create(
			new FileOutputStream(this.tempFile), 512, 1024, 0, crc32Check, compression)) {
			record = new StorageRecord<>(this.kryo, output, 1L, true, bitmap);
		}

		// read the entire file for manual checksum computation
		final byte[] fileBytes = Files.readAllBytes(this.tempFile.toPath());
		final long manualChecksum = computeManualCumulativeChecksum(fileBytes);

		try (final ObservableInput<?> input = create(
			new FileInputStream(this.tempFile), 8_192, crc32Check, compression)) {
			final StorageRecordWithChecksum<Roaring64Bitmap> result = StorageRecord.readWithChecksum(
				input,
				(stream, length) -> this.kryo.readObject(stream, Roaring64Bitmap.class)
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
			final ObservableOutput<?> output = create(
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
