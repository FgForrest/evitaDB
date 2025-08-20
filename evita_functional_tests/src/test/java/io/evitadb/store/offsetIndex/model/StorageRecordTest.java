/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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
import io.evitadb.store.kryo.ObservableInput;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.store.model.FileLocation;
import io.evitadb.stream.RandomAccessFileInputStream;
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

/**
 * This test verifies the behaviour of {@link StorageRecord}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class StorageRecordTest {
	private final Random random = new Random();
	private File tempFile;
	private Kryo kryo;

	/**
	 * Generates a stream of arguments by combining all possible combinations
	 * of {@link Crc32Check} and {@link Compression} enum values.
	 *
	 * @return a {@link Stream} of {@link Arguments} containing every combination
	 *         of {@link Crc32Check} and {@link Compression}.
	 */
	@Nonnull
	private static Stream<Arguments> combineSettings() {
		return Stream.of(Crc32Check.values())
			.flatMap(crc32Check -> Stream.of(Compression.values())
				.map(compression -> Arguments.of(crc32Check, compression)));
	}

	/**
	 * Configures the given {@link ObservableOutput} by optionally computing a CRC32 checksum
	 * and/or applying compress based on the provided parameters.
	 *
	 * @param output      the {@link ObservableOutput} to be configured
	 * @param crc32Check  an enum indicating whether CRC32 checksum computation should be enabled
	 * @param compression an enum indicating whether compress should be applied
	 * @return the configured {@link ObservableOutput}
	 */
	private static <T extends OutputStream> ObservableOutput<T> configure(
		@Nonnull ObservableOutput<T> output,
		@Nonnull Crc32Check crc32Check,
		@Nonnull Compression compression
	) {
		if (crc32Check == Crc32Check.YES) {
			output.computeCRC32();
		}
		if (compression == Compression.YES) {
			output.compress();
		}
		return output;
	}

	/**
	 * Configures the given {@link ObservableOutput} by optionally computing a CRC32 checksum
	 * and/or applying compress based on the provided parameters.
	 *
	 * @param input       the {@link ObservableOutput} to be configured
	 * @param crc32Check  an enum indicating whether CRC32 checksum computation should be enabled
	 * @param compression an enum indicating whether compress should be applied
	 * @return the configured {@link ObservableOutput}
	 */
	private static <T extends InputStream> ObservableInput<T> configure(
		@Nonnull ObservableInput<T> input,
		@Nonnull Crc32Check crc32Check,
		@Nonnull Compression compression
	) {
		if (crc32Check == Crc32Check.YES) {
			input.computeCRC32();
		}
		if (compression == Compression.YES) {
			input.compress();
		}
		return input;
	}

	private static LongSetChunk generateLongSetOfSize(int length) {
		final Long[] longSet = new Long[length];
		long incr = 0;
		for (int i = 0; i < length; i++) {
			longSet[i] = ++incr;
		}
		return new LongSetChunk(longSet);
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
	void shouldWriteAndReadRecord(Crc32Check crc32Check, Compression compression) throws IOException {
		final StorageRecord<ByteChunk> record;
		try (final ObservableOutput<?> output = configure(new ObservableOutput<>(new FileOutputStream(this.tempFile), 16_384, 0), crc32Check, compression)) {
			record = new StorageRecord<>(this.kryo, output, 1L, false, generateBytes(256));
		}

		try (final ObservableInput<?> input = configure(new ObservableInput<>(new FileInputStream(this.tempFile), 8_192), crc32Check, compression)) {
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
		try (final ObservableOutput<?> output = new ObservableOutput<>(new FileOutputStream(this.tempFile), 16_384, 0)) {
			record = new StorageRecord<>(this.kryo, output, 1L, false, generateBytes(256));
		}

		try (final ObservableInput<?> input = new ObservableInput<>(new FileInputStream(this.tempFile), 8_192).computeCRC32()) {
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
		try (final ObservableOutput<?> output = new ObservableOutput<>(new FileOutputStream(this.tempFile), 16_384, 0).computeCRC32()) {
			record = new StorageRecord<>(this.kryo, output, 1L, false, generateBytes(256));
		}

		try (final ObservableInput<?> input = new ObservableInput<>(new FileInputStream(this.tempFile), 8_192)) {
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
	void shouldWriteAndReadMultipleDifferentRecordsOfVaryingSize(Crc32Check crc32Check, Compression compression) throws IOException {
		final int count = 256;
		final List<StorageRecord<ByteChunk>> records = new ArrayList<>(count);
		final Map<FileLocation, StorageRecord<ByteChunk>> index = generateAndWriteRandomRecords(count, records::add, crc32Check, compression);

		try (final ObservableInput<?> input = configure(new ObservableInput<>(new FileInputStream(this.tempFile), 16_384), crc32Check, compression)) {
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
	void shouldWriteAndReadFileLocationsOfMultipleDifferentRecordsOfVaryingSize(Crc32Check crc32Check, Compression compression) throws IOException {
		final int count = 256;
		final List<StorageRecord<ByteChunk>> records = new ArrayList<>(count);
		final Map<FileLocation, StorageRecord<ByteChunk>> index = generateAndWriteRandomRecords(count, records::add, crc32Check, compression);

		try (final ObservableInput<?> input = configure(new ObservableInput<>(new RandomAccessFileInputStream(new RandomAccessFile(this.tempFile, "r")), 16_384), crc32Check, compression)) {
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
	void shouldReadRandomRecords(Crc32Check crc32Check, Compression compression) throws FileNotFoundException {
		final Map<FileLocation, StorageRecord<ByteChunk>> index = new HashMap<>(8);
		try (final ObservableOutput<?> output = configure(new ObservableOutput<>(new FileOutputStream(this.tempFile), 16_384, 0), crc32Check, compression)) {
			writeRandomRecord(index, output, 256);
			writeRandomRecord(index, output, 178);
			writeRandomRecord(index, output, 453);
		}

		try (final RandomAccessFileInputStream is = new RandomAccessFileInputStream(new RandomAccessFile(this.tempFile, "r"));
		     final ObservableInput<RandomAccessFileInputStream> input = configure(new ObservableInput<>(is, 8_192), crc32Check, compression)) {
			index.forEach((key, expectedRecord) -> {
				final StorageRecord<ByteChunk> loadedRecord = StorageRecord.read(input, key, (stream, length, control) -> this.kryo.readObject(stream, ByteChunk.class));
				assertEquals(expectedRecord, loadedRecord);
			});
		}
	}

	@DisplayName("Multiple records with random content size should be written and read in random order")
	@ParameterizedTest
	@MethodSource("combineSettings")
	void shouldWriteAndReadRandomlyMultipleDifferentRecordsOfVaryingSize(Crc32Check crc32Check, Compression compression) throws IOException {
		final int count = 256;
		final int retrievalCount = 512;
		final Map<FileLocation, StorageRecord<ByteChunk>> index = generateAndWriteRandomRecords(
			count, rec -> {}, crc32Check, compression
		);
		final List<FileLocation> locations = new ArrayList<>(index.keySet());

		try (final RandomAccessFileInputStream is = new RandomAccessFileInputStream(new RandomAccessFile(this.tempFile, "r"));
		     final ObservableInput<RandomAccessFileInputStream> input = configure(new ObservableInput<>(is, 8_192 * 2), crc32Check, compression)) {
			for (int i = 0; i < retrievalCount; i++) {
				final FileLocation randomLocation = locations.get(this.random.nextInt(locations.size()));
				final StorageRecord<ByteChunk> loadedRecord = StorageRecord.read(input, randomLocation, (stream, length, control) -> this.kryo.readObject(stream, ByteChunk.class));
				final StorageRecord<ByteChunk> expectedRecord = index.get(randomLocation);
				assertEquals(expectedRecord, loadedRecord);
			}
		}
	}

	@DisplayName("Should persist long record spanning several records")
	@ParameterizedTest
	@MethodSource("combineSettings")
	void shouldWriteAndReadLongRecordThatExceedsTheBufferButConsistsOfMultipleSmallItems(Crc32Check crc32Check, Compression compression) throws IOException {
		final StorageRecord<LongSetChunk> record;
		try (final ObservableOutput<?> output = configure(new ObservableOutput<>(new FileOutputStream(this.tempFile), 512, 2_048, 0), crc32Check, compression)) {
			record = new StorageRecord<>(this.kryo, output, 1L, true, generateLongSetOfSize(5000));
		}

		try (final ObservableInput<?> input = configure(new ObservableInput<>(new FileInputStream(this.tempFile), 8_192), crc32Check, compression)) {
			final StorageRecord<LongSetChunk> loadedRecord = StorageRecord.read(
				this.kryo, input,
				fl -> LongSetChunk.class
			);
			assertEquals(record.fileLocation(), loadedRecord.fileLocation());
			assertEquals(record, loadedRecord);
			assertTrue(loadedRecord.closesGeneration());
		}

		try (final ObservableInput<RandomAccessFileInputStream> input = configure(new ObservableInput<>(new RandomAccessFileInputStream(new RandomAccessFile(this.tempFile, "r")), 8_192), crc32Check, compression)) {
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
	void shouldWriteAndReadLongBitmapOverMultipleRecords(Crc32Check crc32Check, Compression compression) throws IOException {
		final int cardinality = 4065427;
		final Roaring64Bitmap bitmap = new Roaring64Bitmap();
		for (int i = 0; i < cardinality; i++) {
			bitmap.add(i);
		}

		final StorageRecord<Roaring64Bitmap> record;
		try (final ObservableOutput<?> output = configure(new ObservableOutput<>(new FileOutputStream(this.tempFile), 512, 1024, 0), crc32Check, compression)) {
			record = new StorageRecord<>(this.kryo, output, 1L, true, bitmap);
		}

		try (final ObservableInput<?> input = configure(new ObservableInput<>(new FileInputStream(this.tempFile), 8_192), crc32Check, compression)) {
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

		try (final ObservableInput<RandomAccessFileInputStream> input = configure(new ObservableInput<>(new RandomAccessFileInputStream(new RandomAccessFile(this.tempFile, "r")), 8_192), crc32Check, compression)) {
			final StorageRecord<Roaring64Bitmap> loadedRecord = StorageRecord.read(
				input, record.fileLocation(),
				(stream, length, control) -> this.kryo.readObject(stream, Roaring64Bitmap.class)
			);
			assertEquals(record.fileLocation(), loadedRecord.fileLocation());
			assertEquals(record, loadedRecord);
			assertTrue(loadedRecord.closesGeneration());
		}
	}

	@Nonnull
	private Map<FileLocation, StorageRecord<ByteChunk>> generateAndWriteRandomRecords(
		int count,
		Consumer<StorageRecord<ByteChunk>> consumer,
		Crc32Check crc32Check,
		Compression compression
	) throws FileNotFoundException {
		final Map<FileLocation, StorageRecord<ByteChunk>> index = new HashMap<>(count);

		try (final ObservableOutput<?> output = configure(new ObservableOutput<>(new FileOutputStream(this.tempFile), 16_384, 0), crc32Check, compression)) {
			for (int i = 0; i < count; i++) {
				final StorageRecord<ByteChunk> record = new StorageRecord<>(this.kryo, output, 1L, false, generateBytes(this.random.nextInt(256)));
				index.put(record.fileLocation(), record);
				consumer.accept(record);
			}
		}
		return index;
	}

	private StorageRecord<ByteChunk> writeRandomRecord(Map<FileLocation, StorageRecord<ByteChunk>> index, ObservableOutput<?> output, int payloadSize) {
		final StorageRecord<ByteChunk> record = new StorageRecord<>(this.kryo, output, 1L, false, generateBytes(payloadSize));
		index.put(record.fileLocation(), record);
		return record;
	}

	private ByteChunk generateBytes(int count) {
		final byte[] data = new byte[count];
		this.random.nextBytes(data);
		return new ByteChunk(data);
	}

	private enum Crc32Check {
		YES, NO
	}

	private enum Compression {
		YES, NO
	}

	private record ByteChunk(byte[] data) {

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ByteChunk byteChunk = (ByteChunk) o;
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

	private record LongSetChunk(Long[] longSet) {

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			LongSetChunk that = (LongSetChunk) o;
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
