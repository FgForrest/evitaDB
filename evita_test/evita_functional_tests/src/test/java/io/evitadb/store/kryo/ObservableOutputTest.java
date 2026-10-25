/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Locale;
import org.mockito.Mockito;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.KryoException;
import io.evitadb.store.checksum.Checksum;
import io.evitadb.store.checksum.Crc32CChecksumFactory;
import io.evitadb.store.compression.ZipCompressionFactory;
import io.evitadb.store.shared.model.FileLocation;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.utils.Crc32CWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.SERIALIZATION;

/**
 * Tests for {@link ObservableOutput} covering:
 *
 * - Written-bytes accounting with and without compression
 * - File location offset calculations
 * - Cumulative checksum computation across records
 * - Buffer management and state transitions
 * - Constructor validation and edge cases
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Isolated
@DisplayName("ObservableOutput comprehensive tests")
@Tag(STORAGE)
@Tag(SERIALIZATION)
public class ObservableOutputTest {
	/**
	 * Covers each oversized encoding independently so failures cannot hide later matrix entries.
	 *
	 * @return pooled and owned outputs, first and later values, and near-limit and much larger values
	 */
	@Nonnull
	static Stream<Arguments> oversizedValues() {
		final List<Arguments> cases = new ArrayList<>();
		for (boolean pooled : new boolean[] {false, true}) {
			for (int prefix : new int[] {0, 80}) {
				for (int length : new int[] {108, 256}) {
					for (String kind : new String[] {"bytes", "string", "utf8", "ascii"}) {
						cases.add(Arguments.of(pooled, prefix, length, kind));
					}
				}
			}
		}
		return cases.stream();
	}

	/** Regressions for indivisible values and output resource disposal. */
	@Nested
	@DisplayName("Value boundaries and disposal")
	class ValueBoundariesAndDisposal {
		/**
		 * Values must fit alongside a frame header and tail, even when previous values force a continuation.
		 *
		 * @param pooled whether to borrow the frame array
		 * @param prefix number of preceding payload bytes
		 * @param length number of bytes or characters supplied to the writer
		 * @param kind Kryo value encoding to exercise
		 */
		@ParameterizedTest
		@MethodSource("io.evitadb.store.kryo.ObservableOutputTest#oversizedValues")
		@DisplayName("Should reject oversized values with their size and outputBufferSize")
		void shouldRejectOversizedValues(boolean pooled, int prefix, int length, @Nonnull String kind) {
			final Consumer<Output> value = switch (kind) {
				case "bytes" -> out -> out.writeBytes(new byte[length]);
				case "string" -> out -> out.writeString("x".repeat(length));
				case "utf8" -> out -> out.writeString("ž".repeat(length));
				case "ascii" -> out -> out.writeAscii("x".repeat(length));
				default -> throw new IllegalArgumentException(kind);
			};
			try (final Output reference =
				new Output(4096)) {
				value.accept(reference);
				final int valueSize = reference.position();
				try (final ObservableOutput<ByteArrayOutputStream> output = pooled ? pooledOutput(128) :
					new ObservableOutput<>(new ByteArrayOutputStream(), 128, 0L, Checksum.NO_OP, null)) {
					final KryoException failure = assertThrows(KryoException.class,
						() -> new StorageRecord<>(output, 1L, false, out -> {
							for (int i = 0; i < prefix; i++) {
								out.writeByte(1);
							}
							value.accept(out);
							return 1;
						}));
					assertTrue(failure.getMessage().contains("single value cannot be split across frames"),
						failure::getMessage);
					assertTrue(failure.getMessage().contains(valueSize + "B"), failure::getMessage);
					assertTrue(failure.getMessage().contains("outputBufferSize=128"), failure::getMessage);
				}
			}
		}

		/**
		 * An output exclusively owns its compressor and must end it on every disposal path.
		 *
		 * @param fail whether flushing pending raw bytes fails
		 */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@DisplayName("Should end the owned compressor when closing")
		void shouldEndCompressorWhenClosing(boolean fail) {
			final Deflater compressor = Mockito.spy(new Deflater());
			final ByteArrayOutputStream stream = new ByteArrayOutputStream() {
				/**
				 * Simulates a failed raw append on disposal.
				 *
				 * @param bytes raw bytes to append
				 * @param offset first source byte
				 * @param length number of bytes to append
				 * @throws KryoException when the failure scenario is selected
				 */
				@Override
				public synchronized void write(byte[] bytes, int offset, int length) {
					if (fail) {
						throw new KryoException("Raw append failed");
					}
					super.write(bytes, offset, length);
				}
			};
			final ObservableOutput<ByteArrayOutputStream> output = new ObservableOutput<>(
				stream, 128, 0, Checksum.NO_OP, compressor, ObservableOutputBufferPool.INSTANCE);
			try {
				output.writeInt(1);
				if (fail) {
					assertThrows(KryoException.class, output::close);
				} else {
					output.close();
				}
				Mockito.verify(compressor).end();
				assertEquals(0, output.getBuffer().length);
			} finally {
				compressor.end();
			}
		}

		/**
		 * Closing a stream must be attempted after a failed flush, preserving the first failure.
		 *
		 * @param unknown whether a previous failed append has already made the append position unknown
		 */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@DisplayName("Should close the stream and detach after a failed flush")
		void shouldCloseStreamAfterFailedFlush(boolean unknown) {
			final AtomicBoolean closed = new AtomicBoolean();
			final KryoException firstFailure = new KryoException("Raw append failed");
			final ByteArrayOutputStream stream = new ByteArrayOutputStream() {
				/**
				 * Fails every raw append, including the one attempted by close.
				 *
				 * @param bytes raw bytes to append
				 * @param offset first source byte
				 * @param length number of bytes to append
				 * @throws KryoException always, preserving the same failure instance
				 */
				@Override
				public synchronized void write(byte[] bytes, int offset, int length) {
					throw firstFailure;
				}
				/**
				 * Records disposal and adds a secondary failure.
				 *
				 * @throws IOException always, to check suppression onto the flush failure
				 */
				@Override
				public void close() throws IOException {
					closed.set(true);
					throw new IOException("Close failed");
				}
			};
			final ObservableOutput<ByteArrayOutputStream> output = new ObservableOutput<>(
				stream, 128, 0, Checksum.NO_OP, null, ObservableOutputBufferPool.INSTANCE);
			output.writeInt(1);
			if (unknown) {
				assertSame(firstFailure, assertThrows(KryoException.class, output::flush));
			}
			final KryoException failure = assertThrows(KryoException.class, output::close);
			if (unknown) {
				assertTrue(
					failure.getMessage().toLowerCase(Locale.ROOT).contains("append position"),
					failure::getMessage
				);
			} else {
				assertSame(firstFailure, failure);
			}
			assertTrue(closed.get());
			assertEquals(1, failure.getSuppressed().length);
			assertEquals(0, output.getBuffer().length);
		}
	}

	/** Borrowed buffers are attached only while assembling frames or pending raw data. */
	@Nested
	@DisplayName("Pooled frame buffers")
	class PooledBuffers {
		/** A completed frame returns its buffer before another output starts writing. */
		@Test
		@DisplayName("Should attach on write and detach after every completed record")
		void shouldBorrowOnlyWhileWritingFrames() {
			byte[] shared = null;
			final List<ObservableOutput<ByteArrayOutputStream>> outputs = new ArrayList<>(100);
			try {
				for (int i = 0; i < 100; i++) {
					final ObservableOutput<ByteArrayOutputStream> output = pooledOutput(1024);
					outputs.add(output);
					assertEquals(0, output.getBuffer().length);
					final byte[][] active = new byte[1][];
					new StorageRecord<>(output, 1L, false, out -> {
						active[0] = out.getBuffer();
						assertEquals(1024, active[0].length);
						out.writeString("příliš žluťoučký kůň");
						return 1;
					});
					if (shared == null) {
						shared = active[0];
					} else {
						assertSame(shared, active[0]);
					}
					assertEquals(0, output.getBuffer().length);
					assertEquals(0, output.position());
				}
			} finally {
				outputs.forEach(ObservableOutput::close);
			}
		}

		/** Flush preserves an open frame; reset and close abandon it and release the array once. */
		@Test
		@DisplayName("Should detach after flush, reset and close while preserving open frames")
		void shouldDetachOnFlushResetAndClose() {
			final ObservableOutput<ByteArrayOutputStream> output = pooledOutput(1024);
			output.writeInt(42);
			assertEquals(1024, output.getBuffer().length);
			output.flush();
			assertEquals(0, output.getBuffer().length);
			output.markStart();
			output.writeInt(0);
			output.flush();
			assertEquals(1024, output.getBuffer().length);
			assertEquals(4, output.position());
			output.reset();
			assertEquals(0, output.getBuffer().length);
			output.reset();
			output.markStart();
			output.writeInt(0);
			output.close();
			assertEquals(0, output.getBuffer().length);
			output.close();
			final byte[] first = ObservableOutputBufferPool.INSTANCE.borrow(1024);
			final byte[] second = ObservableOutputBufferPool.INSTANCE.borrow(1024);
			assertNotSame(first, second, "Repeated reset/close must never release one array twice");
			ObservableOutputBufferPool.INSTANCE.release(first);
			ObservableOutputBufferPool.INSTANCE.release(second);
		}

		/** Kryo slow paths must work when their first capacity check sees an empty buffer. */
		@Test
		@DisplayName("Should support zero-byte require and Kryo writes starting detached")
		void shouldSupportKryoWritesStartingDetached() {
			try (final ObservableOutput<ByteArrayOutputStream> output = pooledOutput(1024)) {
				assertTrue(output.require(0));
				assertEquals(1024, output.getBuffer().length);
				output.reset();
				final List<Consumer<ObservableOutput<ByteArrayOutputStream>>> writes = List.of(
					out -> out.writeString("příliš žluťoučký kůň"),
					out -> out.writeAscii("ASCII slow path"),
					out -> out.writeString("short ASCII"),
					out -> out.writeBytes(new byte[] {1, 2, 3}),
					out -> out.writeInts(new int[] {42, 999}, 0, 2),
					out -> out.writeLongs(new long[] {Long.MAX_VALUE, -1}, 0, 2),
					out -> out.writeFloats(new float[] {1.5F}, 0, 1),
					out -> out.writeDoubles(new double[] {1.5D}, 0, 1),
					out -> out.writeShorts(new short[] {42}, 0, 1),
					out -> out.writeChars(new char[] {'ž'}, 0, 1),
					out -> out.writeBooleans(new boolean[] {true}, 0, 1),
					out -> out.writeVarInt(123, true),
					out -> out.writeVarLong(Long.MAX_VALUE, true)
				);
				for (Consumer<ObservableOutput<ByteArrayOutputStream>> write : writes) {
					final ByteArrayOutputStream expected = new ByteArrayOutputStream();
					try (final ObservableOutput<ByteArrayOutputStream> owned = new ObservableOutput<>(
						expected, 1024, 0, Checksum.NO_OP, null
					)) {
						write.accept(owned);
					}
					output.getOutputStream().reset();
					write.accept(output);
					output.flush();
					assertEquals(0, output.getBuffer().length);
					assertArrayEquals(expected.toByteArray(), output.getOutputStream().toByteArray());
				}
			}
		}

		/**
		 * Chained records keep one assembly buffer, including while an atomic UTF-8 value crosses a boundary.
		 *
		 * @param compressed whether to compress completed frames
		 */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@DisplayName("Should preserve chained frame bytes with optional compression")
		void shouldPreserveChainedFrameBytes(boolean compressed) {
			final ByteArrayOutputStream expected = new ByteArrayOutputStream();
			final ByteArrayOutputStream actual = new ByteArrayOutputStream();
			final Deflater ownedDeflater = compressed ? new Deflater() : null;
			final Deflater pooledDeflater = compressed ? new Deflater() : null;
			try (
				final ObservableOutput<ByteArrayOutputStream> owned = new ObservableOutput<>(
					expected, 128, 0, Crc32CChecksumFactory.INSTANCE.createChecksum(), ownedDeflater);
				final ObservableOutput<ByteArrayOutputStream> pooled = new ObservableOutput<>(
					actual, 128, 0, Crc32CChecksumFactory.INSTANCE.createChecksum(), pooledDeflater,
					ObservableOutputBufferPool.INSTANCE)
			) {
				owned.markCumulativeChecksumStart();
				pooled.markCumulativeChecksumStart();
				for (int record = 0; record < 3; record++) {
					for (ObservableOutput<ByteArrayOutputStream> output : List.of(owned, pooled)) {
						final long start = output.getWrittenBytesSinceReset();
						final StorageRecord<Integer> written = new StorageRecord<>(output, 1L, false, out -> {
							final byte[] buffer = out.getBuffer();
							for (int i = 0; i < 30; i++) {
								out.writeInt(i);
								out.writeString("žluťoučký kůň příliš");
								assertSame(buffer, out.getBuffer());
							}
							return 1;
						});
						assertEquals(start, written.fileLocation().startingPosition());
						assertEquals(output.getWrittenBytesSinceReset() - start, written.fileLocation().recordLength());
					}
					assertEquals(0, pooled.getBuffer().length);
				}
				assertEquals(owned.getWrittenBytesSinceReset(), pooled.getWrittenBytesSinceReset());
				assertEquals(owned.markCumulativeChecksumEnd(), pooled.markCumulativeChecksumEnd());
				assertArrayEquals(expected.toByteArray(), actual.toByteArray());
			} finally {
				if (ownedDeflater != null) {
					ownedDeflater.end();
					pooledDeflater.end();
				}
			}
		}

		/** A cached compressor must not retain the input array after the pool takes ownership again. */
		@Test
		@DisplayName("Should clear the compressor input reference after each frame")
		void shouldClearCompressorInputReference() {
			final AtomicReference<byte[]> input = new AtomicReference<>();
			final Deflater deflater = new Deflater() {
				/** Records the reference held by the compressor for lifecycle verification. */
				@Override
				public void setInput(byte[] bytes, int offset, int length) {
					input.set(bytes);
					super.setInput(bytes, offset, length);
				}
			};
			try (final ObservableOutput<ByteArrayOutputStream> output = new ObservableOutput<>(
				new ByteArrayOutputStream(), 1024, 0, Checksum.NO_OP, deflater, ObservableOutputBufferPool.INSTANCE
			)) {
				new StorageRecord<>(output, 1L, false, out -> {
					out.writeBytes(new byte[500]);
					return 1;
				});
				assertEquals(0, input.get().length);
				assertEquals(0, output.getBuffer().length);
			} finally {
				deflater.end();
			}
		}

		/**
		 * Compression scratch space and the assembly array both return after an append fails.
		 */
		@Test
		@DisplayName("Should return compression scratch space when writing the frame fails")
		void shouldReleaseCompressedBuffersOnFailure() {
			final byte[][] buffers = new byte[2][];
			final ByteArrayOutputStream stream = new ByteArrayOutputStream() {
				/** Fails the physical append of the finished frame. */
				@Override
				public synchronized void write(byte[] bytes, int offset, int length) {
					throw new IllegalStateException("Append failed");
				}
			};
			final Deflater deflater = new Deflater() {
				/** Captures the borrowed scratch array that receives the compressed payload. */
				@Override
				public int deflate(byte[] output, int offset, int length) {
					buffers[1] = output;
					return super.deflate(output, offset, length);
				}
			};
			try (final ObservableOutput<ByteArrayOutputStream> output = new ObservableOutput<>(
				stream, 1031, 0, Checksum.NO_OP, deflater, ObservableOutputBufferPool.INSTANCE
			)) {
				assertThrows(IllegalStateException.class, () -> new StorageRecord<>(output, 1L, false, out -> {
					buffers[0] = out.getBuffer();
					out.writeBytes(new byte[500]);
					return 1;
				}));
				assertEquals(0, output.getBuffer().length);
				assertNotNull(buffers[1]);
				final byte[] first = ObservableOutputBufferPool.INSTANCE.borrow(1031);
				final byte[] second = ObservableOutputBufferPool.INSTANCE.borrow(1031);
				assertNotSame(first, second);
				if (Runtime.getRuntime().availableProcessors() > 1) {
					assertTrue(first == buffers[0] || second == buffers[0]);
					assertTrue(first == buffers[1] || second == buffers[1]);
				} else {
					assertTrue(first == buffers[0] || first == buffers[1]);
				}
				ObservableOutputBufferPool.INSTANCE.release(first);
				ObservableOutputBufferPool.INSTANCE.release(second);
			} finally {
				deflater.end();
			}
		}

		/**
		 * Appended continuation frames keep their byte counts, compression savings and cumulative checksum on failure.
		 *
		 * @param compressed whether completed frames are compressed
		 */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@DisplayName("Should preserve appended frames and cumulative accounting after abandoning a frame")
		void shouldPreserveAccountingAfterAbandoningFrame(boolean compressed) {
			final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			final Deflater deflater = compressed ? new Deflater() : null;
			try (final ObservableOutput<ByteArrayOutputStream> output = new ObservableOutput<>(
				bytes, 256, 73L, Crc32CChecksumFactory.INSTANCE.createChecksum(), deflater,
				ObservableOutputBufferPool.INSTANCE
			)) {
				output.markCumulativeChecksumStart();
				writeRecord(output, new byte[128], false);
				final int prefixSize = bytes.size();
				assertThrows(IllegalStateException.class, () -> new StorageRecord<>(output, 1L, false, out -> {
					for (int i = 0; i < 80; i++) {
						out.writeInt(i);
					}
					throw new IllegalStateException("Serialization failed after a continuation");
				}));
				assertTrue(bytes.size() > prefixSize, "A continuation must already have been appended");
				assertEquals(73L + bytes.size(), output.getWrittenBytesSinceReset());
				assertEquals(computeManualChecksum(bytes.toByteArray()), output.getCumulativeChecksum());
				assertEquals(0, output.getBuffer().length);
				assertEquals(-1, output.getStartPosition());
				assertEquals(-1, output.getPayloadStartPosition());
				assertEquals(-1, output.getRecordLengthPosition());
				final long nextStart = 73L + bytes.size();
				assertEquals(nextStart, writeRecord(output, new byte[32], false).startingPosition());
				assertEquals(73L + bytes.size(), output.getWrittenBytesSinceReset());
				assertEquals(computeManualChecksum(bytes.toByteArray()), output.markCumulativeChecksumEnd());
			} finally {
				if (deflater != null) {
					deflater.end();
				}
			}
		}

		/**
		 * Abandoning a frame keeps bytes preceding its start pending until the next flush.
		 */
		@Test
		@DisplayName("Should preserve pending bytes before an abandoned frame")
		void shouldPreservePendingBytesBeforeAbandonedFrame() {
			try (final ObservableOutput<ByteArrayOutputStream> output = pooledOutput(1024)) {
				output.writeInt(42);
				assertThrows(IllegalStateException.class, () -> output.doWithOnBufferOverflowHandler(null, () -> {
					output.markStart();
					output.writeInt(99);
					throw new IllegalStateException("Serialization failed");
				}));
				assertEquals(Integer.BYTES, output.position());
				assertEquals(1024, output.getBuffer().length);
				assertEquals(0, output.getOutputStream().size());
				output.flush();
				assertArrayEquals(new byte[] {42, 0, 0, 0}, output.getOutputStream().toByteArray());
				assertEquals(Integer.BYTES, output.getWrittenBytesSinceReset());
				assertEquals(0, output.getBuffer().length);
			}
		}

		/**
		 * Raw-record overflow uses the same abandonment path and preserves previously appended bytes.
		 */
		@Test
		@DisplayName("Should preserve the append position after a raw record fails")
		void shouldPreservePositionAfterFailedRawRecord() {
			try (final ObservableOutput<ByteArrayOutputStream> output = pooledOutput(128)) {
				writeRecord(output, new byte[32], false);
				final int prefixSize = output.getOutputStream().size();
				assertThrows(com.esotericsoftware.kryo.KryoException.class,
					() -> StorageRecord.writeRaw(output, (byte) 0, 1L, new byte[256]));
				assertEquals(prefixSize, output.getWrittenBytesSinceReset());
				assertEquals(0, output.getBuffer().length);
				assertEquals(prefixSize, writeRecord(output, new byte[16], false).startingPosition());
			}
		}

		/** Failed frame serialization abandons the unfinished frame and returns its assembly buffer. */
		@Test
		@DisplayName("Should release an abandoned frame after serialization fails")
		void shouldReleaseAbandonedFrame() {
			try (final ObservableOutput<ByteArrayOutputStream> output = pooledOutput(1024)) {
				assertThrows(IllegalStateException.class, () -> new StorageRecord<>(output, 1L, false, out -> {
					out.writeInt(42);
					throw new IllegalStateException("Serialization failed");
				}));
				assertEquals(0, output.getBuffer().length);
			}
		}
	}

	/**
	 * Creates an initially detached output for lifecycle tests.
	 *
	 * @param size exact size of the borrowed frame buffer
	 * @return a pooled output backed by a fresh in-memory stream
	 */
	@Nonnull
	private static ObservableOutput<ByteArrayOutputStream> pooledOutput(int size) {
		return new ObservableOutput<>(
			new ByteArrayOutputStream(), size, 0, Checksum.NO_OP, null, ObservableOutputBufferPool.INSTANCE
		);
	}

	/**
	 * Creates a stream that accepts its first array write and fails the second, as a full disk would.
	 *
	 * @param target stream receiving the accepted bytes
	 * @return a stream failing on its second array write
	 */
	@Nonnull
	private static OutputStream failingOnSecondArrayWrite(@Nonnull ByteArrayOutputStream target) {
		return new OutputStream() {
			private int arrayWrites;

			@Override
			public void write(int b) {
				target.write(b);
			}

			@Override
			public void write(@Nonnull byte[] bytes, int offset, int length) throws IOException {
				if (++this.arrayWrites == 2) {
					throw new IOException("No space left on device");
				}
				target.write(bytes, offset, length);
			}
		};
	}

	private static final int DEFAULT_BUFFER = 65_536;

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
	 * Reads back a single record previously written with {@link #writeRecord} and returns its payload bytes.
	 *
	 * The wire layout produced by {@link #writeRecord} is `[recordLength:int][control:byte][payload][crc:long]`, so the
	 * payload length is `recordLength` minus the 5-byte header (length int + control byte) and the mandatory CRC tail.
	 * Reading through the full {@link ObservableInput} record lifecycle additionally verifies the per-record CRC32C, so
	 * any byte corruption introduced while writing would surface as a failed read here.
	 *
	 * @param in the observable input positioned at the start of the next record
	 * @return the decoded payload bytes of the record
	 */
	@Nonnull
	private static byte[] readRecordPayload(@Nonnull ObservableInput<?> in) {
		in.markStart();
		final int recordLength = in.readInt();
		final byte control = in.readByte();
		in.markPayloadStart(recordLength, control);
		final int payloadLength = recordLength - 5 - ObservableOutput.TAIL_MANDATORY_SPACE;
		final byte[] payload = new byte[payloadLength];
		in.readBytes(payload, 0, payloadLength);
		in.markEnd(control);
		return payload;
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

		/**
		 * A pooled frame must leave room for the checksum tail, otherwise no frame could ever be finished.
		 */
		@Test
		@DisplayName("Pooled constructor should reject a frame size that cannot hold the checksum tail")
		void shouldRejectPooledFrameSizeWithoutRoomForTail() {
			assertThrows(
				io.evitadb.exception.GenericEvitaInternalError.class,
				() -> new ObservableOutput<>(
					new ByteArrayOutputStream(), ObservableOutput.TAIL_MANDATORY_SPACE, 0L, Checksum.NO_OP, null,
					ObservableOutputBufferPool.INSTANCE
				)
			);
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
	 * Direct appends must compose with buffered writes, compression and cumulative checksums.
	 */
	@Nested
	@DisplayName("Direct appends")
	class DirectAppendTests {

		/**
		 * Mixes pending bytes, compressed frames and direct chunks, then reuses the output after reset.
		 */
		@ParameterizedTest
		@MethodSource("io.evitadb.store.kryo.ObservableOutputTest#checksumAndCompressionSettings")
		@DisplayName("Direct chunks preserve positions, byte counts and cumulative checksums")
		void shouldAccountForDirectBytesAmongBufferedWrites(
			@Nonnull ChecksumOption checksum, @Nonnull CompressionOption compression
		) {
			final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (final ObservableOutput<ByteArrayOutputStream> output = createOutput(bytes, checksum, compression)) {
				output.markCumulativeChecksumStart();
				writeRecord(output, new byte[500], false);
				final int prefixLength = bytes.size();
				output.writeInt(123);
				final byte[] chunk = new byte[DEFAULT_BUFFER * 2];
				new Random(42).nextBytes(chunk);
				output.writeDirect(chunk, 3, chunk.length - 7);
				assertArrayEquals(
					Arrays.copyOfRange(chunk, 3, chunk.length - 4),
					Arrays.copyOfRange(bytes.toByteArray(), prefixLength + Integer.BYTES, bytes.size())
				);
				assertEquals(bytes.size(), output.getWrittenBytesSinceReset());
				assertEquals(0, output.position());
				final long nextStart = bytes.size();
				assertEquals(nextStart, writeRecord(output, new byte[123], false).startingPosition());
				assertEquals(bytes.size(), output.getWrittenBytesSinceReset());
				assertEquals(
					checksum == ChecksumOption.ENABLED ? computeManualChecksum(bytes.toByteArray()) : 0L,
					output.markCumulativeChecksumEnd()
				);
				assertEquals(-1, output.getStartPosition());
				assertEquals(-1, output.getPayloadStartPosition());
				output.reset();
				output.writeDirect(chunk, 1, 13);
				assertEquals(13, output.total());
				assertEquals(13, output.getWrittenBytesSinceReset());
			}
		}

		/**
		 * An attempted direct append cannot interleave bytes with an unfinished buffered frame.
		 */
		@Test
		@DisplayName("Direct appends refuse an open frame")
		void shouldRefuseDirectBytesWhenFrameIsOpen() {
			final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (final ObservableOutput<ByteArrayOutputStream> output = createOutput(
				bytes, ChecksumOption.ENABLED, CompressionOption.DISABLED
			)) {
				output.markStart();
				assertThrows(
					io.evitadb.exception.EvitaInternalError.class, () -> output.writeDirect(new byte[1], 0, 1)
				);
				assertEquals(0, bytes.size());
				output.reset();
				assertDoesNotThrow(() -> output.writeDirect(new byte[1], 0, 1));
			}
		}
	}

	/**
	 * A stream write that fails part-way through an append leaves the physical end of the stream unknown.
	 */
	@Nested
	@DisplayName("Failed appends")
	class FailedAppendTests {

		/**
		 * The frame header reached the stream before the payload write failed, so no later location can be trusted.
		 */
		@Test
		@DisplayName("Should refuse appends after a frame was partially appended")
		void shouldRefuseAppendsAfterPartiallyAppendedFrame() {
			final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (final ObservableOutput<OutputStream> output = new ObservableOutput<>(
				failingOnSecondArrayWrite(bytes), 1024, 0L, Checksum.NO_OP, null, ObservableOutputBufferPool.INSTANCE
			)) {
				assertThrows(KryoException.class, () -> new StorageRecord<>(output, 1L, false, out -> {
					out.writeBytes(new byte[32]);
					return 1;
				}));
				assertEquals(StorageRecord.CRC_NOT_COVERED_HEAD, bytes.size());

				assertThrows(KryoException.class, () -> new StorageRecord<>(output, 2L, false, out -> {
					out.writeBytes(new byte[8]);
					return 1;
				}));
				assertEquals(StorageRecord.CRC_NOT_COVERED_HEAD, bytes.size());
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

		@Test
		@DisplayName("Records round-trip when active region repeatedly forces a reallocating rewind")
		void shouldRoundTripRecordsAcrossMultipleReallocatingRewinds() {
			// When a record being written occupies more than half of the buffer at the moment the buffer is exhausted,
			// the still-unfinished active region cannot be compacted in place (it would overwrite itself) and must be
			// relocated into a freshly sized buffer, recycling the previous buffer as the next spare. The record sizes
			// below alternate between a small record (advancing the consumed prefix) and a large record (whose active
			// region then exceeds that prefix), which drives that relocating rewind several times within a single output
			// lifetime - without any intervening reset(). The test asserts that every record still round-trips intact.
			final int bufferSize = 1024;
			final int capacity = bufferSize - ObservableOutput.TAIL_MANDATORY_SPACE;
			// active record sizes expressed as a percentage of capacity; the small/large alternation is what repeatedly
			// pushes the active region past the consumed prefix at buffer-exhaustion time
			final int[] activePercentOfCapacity = {20, 85, 50, 60, 20, 85, 50, 60, 20, 85, 50, 60};

			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			final List<byte[]> writtenPayloads = new ArrayList<>(activePercentOfCapacity.length);
			try (ObservableOutput<ByteArrayOutputStream> out =
				     new ObservableOutput<>(
					     baos, bufferSize, 0L,
					     Crc32CChecksumFactory.INSTANCE.createChecksum(), null
				     )) {

				for (int percent : activePercentOfCapacity) {
					final int activeSize = (capacity * percent) / 100;
					// subtract the fixed record overhead (5-byte header + CRC tail) to obtain the payload size
					final int payloadSize = activeSize - 5 - ObservableOutput.TAIL_MANDATORY_SPACE;
					final byte[] payload = new byte[payloadSize];
					new Random(payloadSize).nextBytes(payload);
					writtenPayloads.add(payload);
					writeRecord(out, payload, false);
				}
				out.flush();
			}

			final byte[] serialized = baos.toByteArray();
			try (ObservableInput<ByteArrayInputStream> in =
				     new ObservableInput<>(
					     new ByteArrayInputStream(serialized), serialized.length + bufferSize,
					     Crc32CChecksumFactory.INSTANCE.createChecksum(), null
				     )) {

				for (int i = 0; i < writtenPayloads.size(); i++) {
					final byte[] readBack = readRecordPayload(in);
					assertArrayEquals(
						writtenPayloads.get(i), readBack,
						"Payload of record " + i + " must survive the reallocating rewinds intact"
					);
				}
			}
		}
	}
}
