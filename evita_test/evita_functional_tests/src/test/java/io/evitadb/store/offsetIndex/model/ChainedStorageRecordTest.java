/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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
import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.store.checksum.Crc32CChecksum;
import io.evitadb.store.compression.ZipCompressionFactory;
import io.evitadb.store.kryo.ObservableInput;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.stream.RandomAccessFileInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests a single storage record the writer has to chain over several physical records because it does not fit into
 * the writer's output buffer.
 *
 * The writer splits the chain between two values, never inside one, and the reader must join the physical records at
 * exactly those points. The delicate case is a split followed by a single-byte read: Kryo's `readByte` and
 * `readBoolean` consult `require` only when `position` reaches `limit`, so unless the reader caps `limit` at the end of
 * the current record's payload, such a read is served from the record's CRC32C tail instead of from the next record.
 * Whether that happens depends on where the reader's buffer edges fall relative to the record boundaries, so the tests
 * write many differently shaped records with several combinations of writer and reader buffer sizes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Storage record chained over several physical records")
@Tag(STORAGE)
@Tag(SERIALIZATION)
class ChainedStorageRecordTest {
	/**
	 * Size of the header a physical record starts with: length (4), control byte (1) and generation id (8).
	 */
	private static final int RECORD_HEADER_SIZE = 4 + 1 + 8;
	/**
	 * Size of the CRC32C tail a physical record ends with.
	 */
	private static final int RECORD_TAIL_SIZE = 8;
	/**
	 * Bit of the control byte marking a record whose payload continues in the next physical record.
	 */
	private static final int CONTINUATION_BIT = 2;
	/**
	 * Bit of the control byte marking a record with a compressed payload.
	 */
	private static final int COMPRESSION_BIT = 4;
	/**
	 * Characters the generated strings are made of: ASCII, and multi-byte UTF-8.
	 */
	private static final String ASCII_ALPHABET = "the quick brown fox jumps over the lazy dog ";
	private static final String UTF8_ALPHABET = "příliš žluťoučký kůň úpěl ďábelské ódy ";

	@TempDir private Path tempDir;
	private Kryo kryo;

	/**
	 * Writer and reader buffer sizes the records are written and read with. Each one puts the reader's buffer edges
	 * differently against the physical records of the chain.
	 *
	 * @return arguments: description, flush size, writer buffer size, reader buffer size, records written, compression
	 */
	@Nonnull
	static Stream<Arguments> bufferConfigurations() {
		return Stream.of(
			// a whole physical part fits into the reader's buffer when its payload starts
			Arguments.of("2 KB writer, 8 KB reader", 512, 2_048, 8_192, 40),
			// the reader's buffer regularly ends inside the CRC32C tail of a physical part
			Arguments.of("16 KB writer, 16 KB reader", 16_384, 16_384, 16_384, 40),
			// a reader buffer much smaller than the parts
			Arguments.of("8 KB writer, 1 KB reader", 8_192, 8_192, 1_024, 40),
			// the writer's buffer far larger than the reader's, the proportion production storage has
			Arguments.of("64 KB writer, 16 KB reader", 32_768, 65_536, 16_384, 200)
		).flatMap(
			it -> Stream.of(false, true).map(
				compression -> Arguments.of(
					it.get()[0] + (compression ? ", compressed" : ""), it.get()[1], it.get()[2], it.get()[3],
					it.get()[4], compression
				)
			)
		);
	}

	@BeforeEach
	void setUp() {
		this.kryo = new Kryo();
		this.kryo.register(ValueSequence.class, new ValueSequenceSerializer());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("bufferConfigurations")
	@DisplayName("should read every value back, including single-byte reads right after a split")
	void shouldReadEveryValueOfAChainedRecordBack(
		@Nonnull String description,
		int flushSize,
		int writerBufferSize,
		int readerBufferSize,
		int recordCount,
		boolean compression
	) throws IOException {
		int splitsBeforeSingleByteRead = 0;
		int splits = 0;
		for (int sample = 0; sample < recordCount; sample++) {
			final ValueSequence written = generateValues(new Random(sample));
			final File file = this.tempDir.resolve("record-" + sample + ".bin").toFile();
			final StorageRecord<ValueSequence> record = write(file, written, flushSize, writerBufferSize, compression);

			final int[] splitPoints = readSplitPoints(file);
			final ValueLayout layout = ValueLayout.of(written);
			splits += splitPoints.length;
			for (int splitPoint : splitPoints) {
				if (layout.isSingleByteReadAt(splitPoint)) {
					splitsBeforeSingleByteRead++;
				}
			}

			assertEquals(
				written, readSequentially(file, readerBufferSize, compression),
				"Record " + sample + " read sequentially differs from what was written (" + description + ")."
			);
			assertEquals(
				written, readWithSeek(file, record, readerBufferSize, compression),
				"Record " + sample + " read with a seek differs from what was written (" + description + ")."
			);
		}
		// the premise: the configuration does produce the splits the reader is exercised on
		assertTrue(splits > 0, "No record was chained - the configuration tests nothing.");
		assertTrue(
			splitsBeforeSingleByteRead > 0,
			"No split fell right before a single-byte read - the configuration tests nothing."
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("bufferConfigurations")
	@DisplayName("should split a chained record only between two values, never inside one")
	void shouldNeverSplitAValueBetweenPhysicalRecords(
		@Nonnull String description,
		int flushSize,
		int writerBufferSize,
		int readerBufferSize,
		int recordCount,
		boolean compression
	) throws IOException {
		int splits = 0;
		for (int sample = 0; sample < recordCount; sample++) {
			final ValueSequence written = generateValues(new Random(sample));
			final File file = this.tempDir.resolve("record-" + sample + ".bin").toFile();
			write(file, written, flushSize, writerBufferSize, compression);

			final ValueLayout layout = ValueLayout.of(written);
			for (int splitPoint : readSplitPoints(file)) {
				splits++;
				final int valueIndex = layout.valueContaining(splitPoint);
				assertEquals(
					-1, valueIndex,
					"The writer split value " + valueIndex + " of record " + sample + " at logical offset " +
						splitPoint + " (" + description + ")."
				);
			}
		}
		assertTrue(splits > 0, "No record was chained - the configuration tests nothing.");
	}

	@Test
	@DisplayName("should refuse a string longer than the writer's buffer instead of splitting it")
	void shouldRefuseAStringLargerThanTheWriterBuffer() {
		final ValueSequence values = new ValueSequence(List.of("lead", text(new Random(1), 3 * 2_048, false)));
		final File file = this.tempDir.resolve("oversized-string.bin").toFile();
		final KryoException exception = assertThrows(
			KryoException.class, () -> write(file, values, 512, 2_048, false)
		);
		assertTrue(
			exception.getMessage().contains("exceeds buffer size"),
			"Unexpected failure: " + exception.getMessage()
		);
	}

	@Test
	@DisplayName("should refuse a byte array longer than the writer's buffer instead of splitting it")
	void shouldRefuseAByteArrayLargerThanTheWriterBuffer() {
		final byte[] oversized = new byte[3 * 2_048];
		new Random(1).nextBytes(oversized);
		final ValueSequence values = new ValueSequence(List.of("lead", oversized));
		final File file = this.tempDir.resolve("oversized-bytes.bin").toFile();
		final KryoException exception = assertThrows(
			KryoException.class, () -> write(file, values, 512, 2_048, false)
		);
		assertTrue(
			exception.getMessage().contains("exceeds buffer size"),
			"Unexpected failure: " + exception.getMessage()
		);
	}

	/**
	 * Writes the values as one storage record, chained by the writer wherever its buffer overflows.
	 */
	@Nonnull
	private StorageRecord<ValueSequence> write(
		@Nonnull File file,
		@Nonnull ValueSequence values,
		int flushSize,
		int writerBufferSize,
		boolean compression
	) throws IOException {
		try (
			final ObservableOutput<FileOutputStream> output = new ObservableOutput<>(
				new FileOutputStream(file), flushSize, writerBufferSize, 0, new Crc32CChecksum(),
				compression ? new ZipCompressionFactory().createCompressor().orElseThrow() : null
			)
		) {
			return new StorageRecord<>(this.kryo, output, 1L, true, values);
		}
	}

	/**
	 * Reads the record record after record, the way data files are scanned.
	 */
	@Nullable
	private ValueSequence readSequentially(@Nonnull File file, int readerBufferSize, boolean compression)
		throws IOException {
		try (
			final ObservableInput<FileInputStream> input = new ObservableInput<>(
				new FileInputStream(file), readerBufferSize, new Crc32CChecksum(), createInflater(compression)
			)
		) {
			return StorageRecord.read(this.kryo, input, location -> ValueSequence.class).payload();
		}
	}

	/**
	 * Reads the record with a seek to its location, the way the engine fetches records through the offset index.
	 */
	@Nullable
	private ValueSequence readWithSeek(
		@Nonnull File file,
		@Nonnull StorageRecord<ValueSequence> record,
		int readerBufferSize,
		boolean compression
	) throws IOException {
		try (
			final ObservableInput<RandomAccessFileInputStream> input = new ObservableInput<>(
				new RandomAccessFileInputStream(new RandomAccessFile(file, "r"), true), readerBufferSize,
				new Crc32CChecksum(), createInflater(compression)
			)
		) {
			return StorageRecord.read(
				input, record.fileLocation(), (in, length, control) -> this.kryo.readObject(in, ValueSequence.class)
			).payload();
		}
	}

	@Nullable
	private static Inflater createInflater(boolean compression) {
		return compression ? new ZipCompressionFactory().createDecompressor().orElseThrow() : null;
	}

	/**
	 * Parses the physical records of the file and returns the offsets in the logical payload where one physical record
	 * ends and the next continues - with compressed parts inflated, so the offsets match the serialized values.
	 */
	@Nonnull
	private static int[] readSplitPoints(@Nonnull File file) throws IOException {
		final byte[] bytes = Files.readAllBytes(file.toPath());
		final ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		final List<Integer> splitPoints = new ArrayList<>(64);
		int position = 0;
		int logicalOffset = 0;
		while (position < bytes.length) {
			final int length = buffer.getInt(position);
			final byte control = buffer.get(position + 4);
			final int payloadLength = length - RECORD_HEADER_SIZE - RECORD_TAIL_SIZE;
			logicalOffset += isBitSet(control, COMPRESSION_BIT) ?
				inflatedLength(bytes, position + RECORD_HEADER_SIZE, payloadLength) : payloadLength;
			if (isBitSet(control, CONTINUATION_BIT)) {
				splitPoints.add(logicalOffset);
			}
			position += length;
		}
		return splitPoints.stream().mapToInt(Integer::intValue).toArray();
	}

	private static int inflatedLength(@Nonnull byte[] bytes, int offset, int length) {
		final Inflater inflater = new Inflater(true);
		try {
			inflater.setInput(bytes, offset, length);
			final byte[] chunk = new byte[8_192];
			int total = 0;
			while (!inflater.finished()) {
				final int inflated = inflater.inflate(chunk);
				if (inflated == 0 && inflater.needsInput()) {
					throw new IllegalStateException("A compressed physical record ends prematurely.");
				}
				total += inflated;
			}
			return total;
		} catch (DataFormatException ex) {
			throw new IllegalStateException("A compressed physical record cannot be inflated.", ex);
		} finally {
			inflater.end();
		}
	}

	private static boolean isBitSet(byte value, int bit) {
		return ((value & 0xff) & (1 << bit)) != 0;
	}

	/**
	 * Generates a few thousand small values of every kind: ASCII and UTF-8 strings, byte arrays and booleans.
	 */
	@Nonnull
	private static ValueSequence generateValues(@Nonnull Random random) {
		final List<Object> values = new ArrayList<>(2_000);
		for (int i = 0; i < 2_000; i++) {
			final int kind = random.nextInt(7);
			if (kind <= 1) {
				values.add(text(random, 1 + random.nextInt(300), false));
			} else if (kind <= 3) {
				values.add(text(random, 1 + random.nextInt(300), true));
			} else if (kind <= 5) {
				final byte[] bytes = new byte[1 + random.nextInt(300)];
				random.nextBytes(bytes);
				values.add(bytes);
			} else {
				values.add(random.nextBoolean());
			}
		}
		return new ValueSequence(values);
	}

	@Nonnull
	private static String text(@Nonnull Random random, int length, boolean multiByte) {
		final String alphabet = multiByte ? UTF8_ALPHABET : ASCII_ALPHABET;
		final StringBuilder sb = new StringBuilder(length);
		for (int i = 0; i < length; i++) {
			sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
		}
		return sb.toString();
	}

	/**
	 * Values written one after another into one record. Each value is a one-byte tag followed by the value itself, so
	 * every value starts with a single-byte read.
	 *
	 * @param values strings, byte arrays and booleans
	 */
	private record ValueSequence(@Nonnull List<Object> values) {
		private static final byte STRING = 1;
		private static final byte BYTES = 2;
		private static final byte BOOLEAN = 3;

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof ValueSequence that) || that.values.size() != this.values.size()) {
				return false;
			}
			for (int i = 0; i < this.values.size(); i++) {
				final Object mine = this.values.get(i);
				final Object theirs = that.values.get(i);
				final boolean same = mine instanceof byte[] myBytes ?
					theirs instanceof byte[] theirBytes && Arrays.equals(myBytes, theirBytes) :
					Objects.equals(mine, theirs);
				if (!same) {
					return false;
				}
			}
			return true;
		}

		@Override
		public int hashCode() {
			return this.values.size();
		}

		@Nonnull
		@Override
		public String toString() {
			return "ValueSequence of " + this.values.size() + " values";
		}
	}

	/**
	 * Writes each value as a tag byte and the value: `writeString`, `writeVarInt` + `writeBytes`, or `writeBoolean`.
	 */
	private static class ValueSequenceSerializer extends Serializer<ValueSequence> {

		@Override
		public void write(Kryo kryo, Output output, ValueSequence object) {
			output.writeVarInt(object.values().size(), true);
			for (Object value : object.values()) {
				writeValue(output, value);
			}
		}

		@Override
		public ValueSequence read(Kryo kryo, Input input, Class<? extends ValueSequence> type) {
			final int count = input.readVarInt(true);
			final List<Object> values = new ArrayList<>(count);
			for (int i = 0; i < count; i++) {
				final byte tag = input.readByte();
				switch (tag) {
					case ValueSequence.STRING -> values.add(input.readString());
					case ValueSequence.BYTES -> values.add(input.readBytes(input.readVarInt(true)));
					case ValueSequence.BOOLEAN -> values.add(input.readBoolean());
					default -> throw new IllegalStateException("Unknown tag " + tag + " read for value " + i + ".");
				}
			}
			return new ValueSequence(values);
		}

		/**
		 * Writes a single value with its tag - the same calls {@link ValueLayout#of(ValueSequence)} measures.
		 */
		static void writeValue(@Nonnull Output output, @Nonnull Object value) {
			if (value instanceof String string) {
				output.writeByte(ValueSequence.STRING);
				output.writeString(string);
			} else if (value instanceof byte[] bytes) {
				output.writeByte(ValueSequence.BYTES);
				output.writeVarInt(bytes.length, true);
				output.writeBytes(bytes);
			} else if (value instanceof Boolean bool) {
				output.writeByte(ValueSequence.BOOLEAN);
				output.writeBoolean(bool);
			} else {
				throw new IllegalArgumentException("Unsupported value " + value);
			}
		}
	}

	/**
	 * Offsets of the values in the record's logical payload, measured by serializing the values on their own.
	 *
	 * @param singleByteReads offsets where the reader reads a single byte: every tag, and every boolean
	 * @param atomicStarts    for each value, where its multi-byte body starts (`writeString` / `writeBytes`), or -1
	 * @param atomicEnds      for each value, where its multi-byte body ends
	 */
	private record ValueLayout(@Nonnull int[] singleByteReads, @Nonnull int[] atomicStarts, @Nonnull int[] atomicEnds) {

		@Nonnull
		static ValueLayout of(@Nonnull ValueSequence values) {
			final int count = values.values().size();
			final Output output = new Output(1_024, -1);
			output.writeVarInt(count, true);
			final List<Integer> singleByteReads = new ArrayList<>(count * 2);
			final int[] atomicStarts = new int[count];
			final int[] atomicEnds = new int[count];
			for (int i = 0; i < count; i++) {
				final Object value = values.values().get(i);
				singleByteReads.add((int) output.total());
				if (value instanceof String string) {
					output.writeByte(ValueSequence.STRING);
					atomicStarts[i] = (int) output.total();
					output.writeString(string);
				} else if (value instanceof byte[] bytes) {
					output.writeByte(ValueSequence.BYTES);
					output.writeVarInt(bytes.length, true);
					atomicStarts[i] = (int) output.total();
					output.writeBytes(bytes);
				} else {
					output.writeByte(ValueSequence.BOOLEAN);
					singleByteReads.add((int) output.total());
					atomicStarts[i] = -1;
					output.writeBoolean((Boolean) value);
				}
				atomicEnds[i] = (int) output.total();
			}
			return new ValueLayout(
				singleByteReads.stream().mapToInt(Integer::intValue).toArray(), atomicStarts, atomicEnds
			);
		}

		/**
		 * @return true when the reader reads a single byte starting at the given logical offset
		 */
		boolean isSingleByteReadAt(int offset) {
			return Arrays.binarySearch(this.singleByteReads, offset) >= 0;
		}

		/**
		 * @return index of the value whose `writeString` / `writeBytes` body strictly contains the offset, or -1
		 */
		int valueContaining(int offset) {
			for (int i = 0; i < this.atomicStarts.length; i++) {
				if (this.atomicStarts[i] >= 0 && this.atomicStarts[i] < offset && offset < this.atomicEnds[i]) {
					return i;
				}
			}
			return -1;
		}
	}
}
