/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.store.memTable.model;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.KryoDataInput;
import com.esotericsoftware.kryo.io.KryoDataOutput;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.store.kryo.ObservableInput;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.store.memTable.stream.RandomAccessFileInputStream;
import io.evitadb.store.model.FileLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.roaringbitmap.longlong.Roaring64Bitmap;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static io.evitadb.store.memTable.model.StorageRecord.isBitSet;
import static io.evitadb.store.memTable.model.StorageRecord.setBit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

	@BeforeEach
	void setUp() {
		tempFile = Path.of(System.getProperty("java.io.tmpdir")).resolve("evita_test_file.tmp").toFile();
		kryo = new Kryo();
		kryo.register(ByteChunk.class, new ByteChunkSerializer());
		kryo.register(LongSetChunk.class, new LongSetChunkSerializer());
		kryo.register(Roaring64Bitmap.class, new Roaring64BitmapSerializer());
	}

	@AfterEach
	void tearDown() {
		tempFile.delete();
		tempFile = null;
	}

	@Test
	void shouldWriteAndReadSpecificByte() {
		byte control = 0;
		final byte encoded = setBit(control, (byte) 0, true);
		assertTrue(isBitSet(encoded, (byte) 0));
		assertFalse(isBitSet(encoded, (byte) 1));
		assertFalse(isBitSet(encoded, (byte) 2));
		assertFalse(isBitSet(encoded, (byte) 3));
		assertFalse(isBitSet(encoded, (byte) 4));
		assertFalse(isBitSet(encoded, (byte) 5));
		assertFalse(isBitSet(encoded, (byte) 6));
	}

	@Test
	void shouldWriteAndReadSpecificByteInTheMiddle() {
		byte control = 0;
		final byte encoded = setBit(control, (byte) 5, true);
		assertFalse(isBitSet(encoded, (byte) 0));
		assertFalse(isBitSet(encoded, (byte) 1));
		assertFalse(isBitSet(encoded, (byte) 2));
		assertFalse(isBitSet(encoded, (byte) 3));
		assertFalse(isBitSet(encoded, (byte) 4));
		assertTrue(isBitSet(encoded, (byte) 5));
		assertFalse(isBitSet(encoded, (byte) 6));
	}

	@Test
	void shouldWriteAndReadSpecificByteConsequently() {
		byte control = 0;
		final byte encoded =
			setBit(
				setBit(
					setBit(control, (byte) 7, true),
					(byte) 5, true
				),
				(byte) 2, true
			);
		assertFalse(isBitSet(encoded, (byte) 0));
		assertFalse(isBitSet(encoded, (byte) 1));
		assertTrue(isBitSet(encoded, (byte) 2));
		assertFalse(isBitSet(encoded, (byte) 3));
		assertFalse(isBitSet(encoded, (byte) 4));
		assertTrue(isBitSet(encoded, (byte) 5));
		assertFalse(isBitSet(encoded, (byte) 6));

		assertFalse(
			isBitSet(
				setBit(encoded, (byte) 2, false), (byte) 2
			)
		);
	}

	@DisplayName("Single record should be written and read intact")
	@Test
	void shouldWriteAndReadRecord() throws IOException {
		final StorageRecord<ByteChunk> record;
		try (final ObservableOutput<?> output = new ObservableOutput<>(new FileOutputStream(tempFile), 16_384, 0)) {
			record = new StorageRecord<>(kryo, output, (byte) 1, 1L, false, generateBytes(256));
		}

		try (final ObservableInput<?> input = new ObservableInput<>(new FileInputStream(tempFile), 8_192)) {
			final StorageRecord<ByteChunk> loadedRecord = new StorageRecord<>(
				kryo, input,
				fl -> {
					assertEquals(record.getFileLocation(), fl);
					return ByteChunk.class;
				}
			);
			assertEquals(record, loadedRecord);
		}
	}

	@DisplayName("Single record should be written and read intact including CRC32 checksum")
	@Test
	void shouldWriteAndReadRecordWithCrc32Check() throws IOException {
		final StorageRecord<ByteChunk> record;
		try (final ObservableOutput<?> output = new ObservableOutput<>(new FileOutputStream(tempFile), 16_384, 0).computeCRC32()) {
			record = new StorageRecord<>(kryo, output, (byte) 1, 1L, false, generateBytes(256));
		}

		try (final ObservableInput<?> input = new ObservableInput<>(new FileInputStream(tempFile), 8_192).computeCRC32()) {
			final StorageRecord<ByteChunk> loadedRecord = new StorageRecord<>(
				kryo, input,
				fl -> {
					assertEquals(record.getFileLocation(), fl);
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
		try (final ObservableOutput<?> output = new ObservableOutput<>(new FileOutputStream(tempFile), 16_384, 0).computeCRC32()) {
			record = new StorageRecord<>(kryo, output, (byte) 1, 1L, false, generateBytes(256));
		}

		try (final ObservableInput<?> input = new ObservableInput<>(new FileInputStream(tempFile), 8_192)) {
			final StorageRecord<ByteChunk> loadedRecord = new StorageRecord<>(
				kryo, input,
				fl -> {
					assertEquals(record.getFileLocation(), fl);
					return ByteChunk.class;
				}
			);
			assertEquals(record, loadedRecord);
		}
	}

	@DisplayName("Multiple records of various random size should be written and read with checksum")
	@Test
	void shouldWriteAndReadMultipleDifferentRecordsOfVaryingSize() throws IOException {
		final int count = 256;
		final List<StorageRecord<ByteChunk>> records = new ArrayList<>(count);
		final Map<FileLocation, StorageRecord<ByteChunk>> index = generateAndWriteRandomRecords(count, records::add);

		try (final ObservableInput<?> input = new ObservableInput<>(new FileInputStream(tempFile), 16_384).computeCRC32()) {
			final AtomicReference<FileLocation> locRef = new AtomicReference<>();
			for (int i = 0; i < count; i++) {
				final StorageRecord<ByteChunk> loadedRecord = new StorageRecord<>(
					kryo, input,
					fl -> {
						locRef.set(fl);
						return ByteChunk.class;
					}
				);
				assertEquals(records.get(i), loadedRecord, "Record " + i + " doesn't match!");
				final StorageRecord<ByteChunk> originalRecord = index.get(locRef.get());
				assertNotNull(originalRecord);
				assertEquals(originalRecord, loadedRecord);
			}
		}
	}

	@DisplayName("Multiple records should be written and read in random order")
	@Test
	void shouldReadRandomRecords() throws FileNotFoundException {
		final Map<FileLocation, StorageRecord<ByteChunk>> index = new HashMap<>(8);
		try (final ObservableOutput<?> output = new ObservableOutput<>(new FileOutputStream(tempFile), 16_384, 0).computeCRC32()) {
			writeRandomRecord(index, output, 256);
			writeRandomRecord(index, output, 178);
			writeRandomRecord(index, output, 453);
		}

		try (final RandomAccessFileInputStream is = new RandomAccessFileInputStream(new RandomAccessFile(tempFile, "r"));
		     final ObservableInput<RandomAccessFileInputStream> input = new ObservableInput<>(is, 8_192).computeCRC32()) {
			index.forEach((key, expectedRecord) -> {
				final StorageRecord<ByteChunk> loadedRecord = new StorageRecord<>(input, key, (stream, length) -> kryo.readObject(stream, ByteChunk.class));
				assertEquals(expectedRecord, loadedRecord);
			});
		}
	}

	@DisplayName("Multiple records with random content size should be written and read in random order")
	@Test
	void shouldWriteAndReadRandomlyMultipleDifferentRecordsOfVaryingSize() throws IOException {
		final int count = 256;
		final int retrievalCount = 512;
		final Map<FileLocation, StorageRecord<ByteChunk>> index = generateAndWriteRandomRecords(count, rec -> {
		});
		final List<FileLocation> locations = new ArrayList<>(index.keySet());

		try (final RandomAccessFileInputStream is = new RandomAccessFileInputStream(new RandomAccessFile(tempFile, "r"));
		     final ObservableInput<RandomAccessFileInputStream> input = new ObservableInput<>(is, 8_192 * 2).computeCRC32()) {
			for (int i = 0; i < retrievalCount; i++) {
				final FileLocation randomLocation = locations.get(random.nextInt(locations.size()));
				final StorageRecord<ByteChunk> loadedRecord = new StorageRecord<>(input, randomLocation, (stream, length) -> kryo.readObject(stream, ByteChunk.class));
				final StorageRecord<ByteChunk> expectedRecord = index.get(randomLocation);
				assertEquals(expectedRecord, loadedRecord);
			}
		}
	}

	@DisplayName("Should persist long record spanning several records")
	@Test
	void shouldWriteAndReadLongRecordThatExceedsTheBufferButConsistsOfMultipleSmallItems() throws IOException {
		final StorageRecord<LongSetChunk> record;
		try (final ObservableOutput<?> output = new ObservableOutput<>(new FileOutputStream(tempFile), 512, 2_048, 0)) {
			record = new StorageRecord<>(kryo, output, (byte) 1, 1L, true, generateLongSetOfSize(5000));
		}

		try (final ObservableInput<?> input = new ObservableInput<>(new FileInputStream(tempFile), 8_192)) {
			final StorageRecord<LongSetChunk> loadedRecord = new StorageRecord<>(
				kryo, input,
				fl -> LongSetChunk.class
			);
			assertEquals(record.getFileLocation(), loadedRecord.getFileLocation());
			assertEquals(record, loadedRecord);
			assertTrue(loadedRecord.isClosesTransaction());
		}

		try (final ObservableInput<RandomAccessFileInputStream> input = new ObservableInput<>(new RandomAccessFileInputStream(new RandomAccessFile(tempFile, "r")), 8_192)) {
			final StorageRecord<LongSetChunk> loadedRecord = new StorageRecord<>(
				input, record.getFileLocation(),
				(stream, length) -> kryo.readObject(stream, LongSetChunk.class)
			);
			assertEquals(record.getFileLocation(), loadedRecord.getFileLocation());
			assertEquals(record, loadedRecord);
			assertTrue(loadedRecord.isClosesTransaction());
		}
	}

	@DisplayName("Should persist and read Roaring64Bitmap over multiple records")
	@Test
	void shouldWriteAndReadLongBitmapOverMultipleRecords() throws IOException {
		final int cardinality = 4065427;
		final Roaring64Bitmap bitmap = new Roaring64Bitmap();
		for (int i = 0; i < cardinality; i++) {
			bitmap.add(i);
		}

		final StorageRecord<Roaring64Bitmap> record;
		try (final ObservableOutput<?> output = new ObservableOutput<>(new FileOutputStream(tempFile), 512, 1024, 0).computeCRC32()) {
			record = new StorageRecord<>(kryo, output, (byte) 1, 1L, true, bitmap);
		}

		try (final ObservableInput<?> input = new ObservableInput<>(new FileInputStream(tempFile), 8_192).computeCRC32()) {
			final StorageRecord<Roaring64Bitmap> loadedRecord = new StorageRecord<>(
				kryo, input,
				fl -> Roaring64Bitmap.class
			);
			assertEquals(record.getFileLocation(), loadedRecord.getFileLocation());
			assertEquals(record, loadedRecord);

			final Iterator<Long> it = loadedRecord.getPayload().iterator();
			for (int i = 0; i < cardinality; i++) {
				assertEquals(Long.valueOf(i), it.next());
			}
		}

		try (final ObservableInput<RandomAccessFileInputStream> input = new ObservableInput<>(new RandomAccessFileInputStream(new RandomAccessFile(tempFile, "r")), 8_192)) {
			final StorageRecord<Roaring64Bitmap> loadedRecord = new StorageRecord<>(
				input, record.getFileLocation(),
				(stream, length) -> kryo.readObject(stream, Roaring64Bitmap.class)
			);
			assertEquals(record.getFileLocation(), loadedRecord.getFileLocation());
			assertEquals(record, loadedRecord);
			assertTrue(loadedRecord.isClosesTransaction());
		}
	}

	/*
		PRIVATE METHODS
	 */

	@Nonnull
	private Map<FileLocation, StorageRecord<ByteChunk>> generateAndWriteRandomRecords(int count, Consumer<StorageRecord<ByteChunk>> consumer) throws FileNotFoundException {
		final Map<FileLocation, StorageRecord<ByteChunk>> index = new HashMap<>(count);

		try (final ObservableOutput<?> output = new ObservableOutput<>(new FileOutputStream(tempFile), 16_384, 0).computeCRC32()) {
			for (int i = 0; i < count; i++) {
				final StorageRecord<ByteChunk> record = new StorageRecord<>(kryo, output, (byte) 1, 1L, false, generateBytes(random.nextInt(256)));
				index.put(record.getFileLocation(), record);
				consumer.accept(record);
			}
		}
		return index;
	}

	private LongSetChunk generateLongSetOfSize(int length) {
		final Long[] longSet = new Long[length];
		long incr = 0;
		for (int i = 0; i < length; i++) {
			longSet[i] = ++incr;
		}
		return new LongSetChunk(longSet);
	}

	private StorageRecord<ByteChunk> writeRandomRecord(Map<FileLocation, StorageRecord<ByteChunk>> index, ObservableOutput<?> output, int payloadSize) {
		final StorageRecord<ByteChunk> record = new StorageRecord<>(kryo, output, (byte) 1, 1L, false, generateBytes(payloadSize));
		index.put(record.getFileLocation(), record);
		return record;
	}

	private ByteChunk generateBytes(int count) {
		final byte[] data = new byte[count];
		random.nextBytes(data);
		return new ByteChunk(data);
	}

	private record ByteChunk(byte[] data) {

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ByteChunk byteChunk = (ByteChunk) o;
			return Arrays.equals(data, byteChunk.data);
		}

		@Override
		public int hashCode() {
			return Arrays.hashCode(data);
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
			return Arrays.equals(longSet, that.longSet);
		}

		@Override
		public int hashCode() {
			return Arrays.hashCode(longSet);
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