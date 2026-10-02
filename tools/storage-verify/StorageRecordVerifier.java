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

package io.evitadb.tools.storage;

import io.evitadb.store.checksum.Crc32CChecksumFactory;
import io.evitadb.store.kryo.ObservableInput;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.shared.model.FileLocation;
import io.evitadb.stream.RandomAccessFileInputStream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import java.util.zip.CRC32C;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Reads every record of every WAL (`.wal`) and data file (`.collection`, `.catalog`) under the given storage roots
 * twice, and compares the two reads record by record.
 *
 * The first read is an **independent oracle**: plain byte parsing of the record framing, its own CRC32C check and its
 * own `Inflater`. The second read goes through {@link ObservableInput} and {@link StorageRecord} - the classes the
 * engine reads with - in the access patterns the engine uses:
 *
 * - `data-seq`: a data file read record after record from offset 0, without seeking,
 * - `data-seek`: a data file read with one seek per record, the engine's normal fetch path,
 * - `wal-seq`: a WAL file walked transaction by transaction the way the forward WAL supplier walks it - a length
 *   prefix, the leading transaction record, the mutation records and the cumulative checksum, without seeking.
 *
 * After every record the payload bytes must equal the oracle's and {@link ObservableInput#total()} must equal the
 * oracle's file offset; for a WAL transaction the measured size of the leading record must also match its framing,
 * which is the premise the WAL supplier checks. Each file is verified once per buffer size, because the buffer size
 * decides where records straddle the reader's buffer edges.
 *
 * Payloads are read in a deterministic mix of small and large reads, but never across the split point of a record
 * chained over several physical records - the writer splits between two values, so no serializer read crosses it
 * either. `-Dunaligned=true` drops that clipping; with it the reader misaligns on chained records by design.
 *
 * Usage: `StorageRecordVerifier [-Dthreads=N] [-Dunaligned=true] <bufferSizes> <storage root>...`, where
 * `bufferSizes` is a comma separated list such as `16384,4096`. The process exits with `0` when every file
 * verified, `1` when anything did not, and `2` on a usage error.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class StorageRecordVerifier {
	/**
	 * Bytes of a record that are not payload: the length (4), the control byte (1), the generation id (8) and
	 * the CRC32C tail (8).
	 */
	private static final int OVERHEAD = 4 + 1 + 8 + 8;
	/**
	 * Bit of the control byte marking a record whose payload continues in the next physical record.
	 */
	private static final int CONTINUATION_BIT = 2;
	/**
	 * Bit of the control byte marking a record carrying a CRC32C checksum.
	 */
	private static final int CRC32_BIT = 3;
	/**
	 * Bit of the control byte marking a record with a deflated payload.
	 */
	private static final int COMPRESSION_BIT = 4;
	/**
	 * Length of the tail a finished WAL file ends with: first version, last version and the file checksum.
	 */
	private static final int WAL_TAIL_LENGTH = 24;
	/**
	 * Size of the cumulative checksum that opens a WAL file and closes every transaction in it.
	 */
	private static final int CUMULATIVE_CHECKSUM_SIZE = 8;
	/**
	 * When false, payload reads are clipped at the split points of chained records.
	 */
	private static final boolean UNALIGNED = Boolean.getBoolean("unaligned");

	/**
	 * Entry point - see the class documentation for the arguments.
	 *
	 * @param args buffer sizes followed by storage roots
	 */
	public static void main(@Nonnull String[] args) throws Exception {
		if (args.length < 2) {
			System.err.println("usage: StorageRecordVerifier <bufferSizes> <storage root>...");
			System.exit(2);
		}
		final int[] bufferSizes = Arrays.stream(args[0].split(",")).mapToInt(Integer::parseInt).toArray();
		final int threads = Integer.getInteger("threads", Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
		final List<Path> files = new ArrayList<>(256);
		for (int i = 1; i < args.length; i++) {
			try (Stream<Path> walk = Files.walk(Path.of(args[i]))) {
				walk.filter(Files::isRegularFile)
					.filter(StorageRecordVerifier::isVerifiedFile)
					.forEach(files::add);
			}
		}
		// largest files first, so the pool drains evenly
		files.sort(Comparator.comparingLong((Path p) -> p.toFile().length()).reversed());
		System.out.println(
			"# records: " + files.size() + " files, buffer sizes " + Arrays.toString(bufferSizes) +
				", threads " + threads + (UNALIGNED ? ", UNALIGNED reads" : "")
		);

		final List<FileResult> results = new ArrayList<>(files.size() * bufferSizes.length * 2);
		final ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			final List<Future<List<FileResult>>> futures = new ArrayList<>(files.size() * bufferSizes.length);
			for (Path file : files) {
				if (file.toFile().length() == 0) {
					continue;
				}
				for (int bufferSize : bufferSizes) {
					futures.add(pool.submit(() -> verifyFile(file, bufferSize)));
				}
			}
			for (Future<List<FileResult>> future : futures) {
				results.addAll(future.get());
			}
		} finally {
			pool.shutdown();
		}

		results.sort(
			Comparator.comparing(FileResult::file)
				.thenComparing(FileResult::mode)
				.thenComparingInt(FileResult::bufferSize)
		);
		long records = 0, compressed = 0, chained = 0, transactions = 0, payloadBytes = 0, failed = 0, tornTails = 0;
		for (FileResult result : results) {
			records += result.records;
			compressed += result.compressed;
			chained += result.chained;
			transactions += result.transactions;
			payloadBytes += result.payloadBytes;
			if (!result.isOk()) {
				failed++;
			}
			if (result.tornTail != null) {
				tornTails++;
			}
			System.out.println(result.describe());
		}
		System.out.printf(
			"# SUMMARY records: runs=%d records=%d compressed=%d chained=%d walTransactions=%d payloadMB=%.0f " +
				"tornTails=%d failedRuns=%d status=%s%n",
			results.size(), records, compressed, chained, transactions, payloadBytes / 1048576.0, tornTails, failed,
			failed == 0 ? "OK" : "FAILED"
		);
		System.exit(failed == 0 ? 0 : 1);
	}

	/**
	 * Returns true for the file kinds this verifier understands.
	 *
	 * @param path file to test
	 * @return true for WAL and data files
	 */
	private static boolean isVerifiedFile(@Nonnull Path path) {
		final String name = path.getFileName().toString();
		return name.endsWith(".wal") || name.endsWith(".collection") || name.endsWith(".catalog");
	}

	/**
	 * Verifies one file with one buffer size in every access pattern that applies to its kind.
	 *
	 * @param file       file to verify
	 * @param bufferSize size of the reader's buffer
	 * @return one result per access pattern
	 */
	@Nonnull
	private static List<FileResult> verifyFile(@Nonnull Path file, int bufferSize) throws IOException {
		final List<FileResult> results = new ArrayList<>(2);
		if (file.getFileName().toString().endsWith(".wal")) {
			try (RecordSource source = new RecordSource(file)) {
				results.add(verifyWal(file, source, bufferSize));
			}
		} else {
			try (RecordSource source = new RecordSource(file)) {
				results.add(verifyDataSequentially(file, source, bufferSize));
			}
			try (RecordSource source = new RecordSource(file)) {
				results.add(verifyDataWithSeeks(file, source, bufferSize));
			}
		}
		return results;
	}

	/**
	 * Opens the reader under test exactly the way the WAL supplier does, with a configurable buffer size.
	 *
	 * @param file       file to read
	 * @param bufferSize size of the reader's buffer
	 * @return the reader
	 */
	@Nonnull
	private static ObservableInput<RandomAccessFileInputStream> openReader(@Nonnull File file, int bufferSize)
		throws IOException {
		return new ObservableInput<>(
			new RandomAccessFileInputStream(new RandomAccessFile(file, "r"), true),
			bufferSize,
			Crc32CChecksumFactory.INSTANCE.createCumulativeChecksum(0L),
			new Inflater(true)
		);
	}

	/**
	 * Walks a WAL file transaction by transaction without seeking, as the forward WAL supplier does.
	 *
	 * @param file       the WAL file
	 * @param source     the oracle's view of the file
	 * @param bufferSize size of the reader's buffer
	 * @return the result
	 */
	@Nonnull
	private static FileResult verifyWal(@Nonnull Path file, @Nonnull RecordSource source, int bufferSize) {
		final FileResult result = new FileResult(file.toString(), "wal-seq", bufferSize);
		final long fileLength = source.size();
		final Random random = new Random(file.getFileName().toString().hashCode() * 31L + bufferSize);
		long position = CUMULATIVE_CHECKSUM_SIZE;
		try (ObservableInput<RandomAccessFileInputStream> input = openReader(file.toFile(), bufferSize)) {
			input.seekWithUnknownLength(0);
			input.simpleLongRead();
			result.checkOffset(input, CUMULATIVE_CHECKSUM_SIZE, "after the seed checksum");
			// the same end-of-data rule the supplier applies: the finished file ends with its tail
			while (fileLength > position + WAL_TAIL_LENGTH) {
				final int contentLength = source.getInt(position);
				if (contentLength <= 0 || position + 4 + contentLength + CUMULATIVE_CHECKSUM_SIZE > fileLength) {
					result.tornTail = "incomplete transaction at " + position + " (declared content " +
						contentLength + " B, " + (fileLength - position) + " B left)";
					break;
				}
				final long contentEnd = position + 4 + contentLength;
				final List<OracleChain> chains = new ArrayList<>(16);
				long recordPosition = position + 4;
				while (recordPosition < contentEnd) {
					final OracleChain chain = OracleChain.read(source, recordPosition);
					chains.add(chain);
					recordPosition = chain.end();
				}
				if (recordPosition != contentEnd) {
					throw new OracleException("the records of the transaction at " + position + " overrun its content");
				}
				final long storedCumulativeChecksum = source.getLong(contentEnd);

				final long totalBefore = input.total();
				final int readContentLength = input.simpleIntRead();
				if (readContentLength != contentLength) {
					throw new IllegalStateException(
						"content length " + readContentLength + " read where " + contentLength + " is at " + position
					);
				}
				boolean leading = true;
				for (OracleChain chain : chains) {
					StorageRecord.readWithChecksum(input, (in, length) -> readPayload(in, chain, random));
					if (leading) {
						// the framing premise of the WAL supplier: the leading record measured from the length prefix
						if (input.total() - totalBefore != 4 + (chain.end() - chain.start())) {
							result.framingMismatches++;
						}
						leading = false;
					}
					result.checkOffset(input, chain.end(), "after the record at " + chain.start());
					result.count(chain);
				}
				final long cumulativeChecksum = input.simpleLongRead();
				if (cumulativeChecksum != storedCumulativeChecksum) {
					throw new IllegalStateException(
						"cumulative checksum read at " + contentEnd + " differs from the file"
					);
				}
				result.checkOffset(input, contentEnd + CUMULATIVE_CHECKSUM_SIZE, "after the checksum at " + contentEnd);
				result.transactions++;
				position = contentEnd + CUMULATIVE_CHECKSUM_SIZE;
			}
		} catch (OracleException ex) {
			result.classifyOracleFailure(ex, position, fileLength);
		} catch (Throwable ex) {
			result.failure = describe(ex) + " (transaction at " + position + ")";
		}
		return result;
	}

	/**
	 * Reads a data file record after record from offset 0, without seeking.
	 *
	 * @param file       the data file
	 * @param source     the oracle's view of the file
	 * @param bufferSize size of the reader's buffer
	 * @return the result
	 */
	@Nonnull
	private static FileResult verifyDataSequentially(@Nonnull Path file, @Nonnull RecordSource source, int bufferSize) {
		final FileResult result = new FileResult(file.toString(), "data-seq", bufferSize);
		final long fileLength = source.size();
		final Random random = new Random(file.getFileName().toString().hashCode() * 31L + bufferSize);
		long position = 0;
		try (ObservableInput<RandomAccessFileInputStream> input = openReader(file.toFile(), bufferSize)) {
			input.seekWithUnknownLength(0);
			while (position < fileLength) {
				final OracleChain chain = OracleChain.read(source, position);
				StorageRecord.read(input, (in, length) -> readPayload(in, chain, random));
				result.checkOffset(input, chain.end(), "after the record at " + chain.start());
				result.count(chain);
				position = chain.end();
			}
		} catch (OracleException ex) {
			result.classifyOracleFailure(ex, position, fileLength);
		} catch (Throwable ex) {
			result.failure = describe(ex) + " (record at " + position + ")";
		}
		return result;
	}

	/**
	 * Reads a data file with one seek per record, the way the engine fetches records through the offset index.
	 *
	 * @param file       the data file
	 * @param source     the oracle's view of the file
	 * @param bufferSize size of the reader's buffer
	 * @return the result
	 */
	@Nonnull
	private static FileResult verifyDataWithSeeks(@Nonnull Path file, @Nonnull RecordSource source, int bufferSize) {
		final FileResult result = new FileResult(file.toString(), "data-seek", bufferSize);
		final long fileLength = source.size();
		final Random random = new Random(file.getFileName().toString().hashCode() * 17L + bufferSize);
		long position = 0;
		try (ObservableInput<RandomAccessFileInputStream> input = openReader(file.toFile(), bufferSize)) {
			while (position < fileLength) {
				final OracleChain chain = OracleChain.read(source, position);
				final FileLocation location = new FileLocation(
					chain.start(), Math.toIntExact(chain.end() - chain.start())
				);
				StorageRecord.read(input, location, (in, length, control) -> readPayload(in, chain, random));
				// a seek starts the stream offset at the record, so it ends at the record's length
				result.checkOffset(input, chain.end() - chain.start(), "after the record at " + chain.start());
				result.count(chain);
				position = chain.end();
			}
		} catch (OracleException ex) {
			result.classifyOracleFailure(ex, position, fileLength);
		} catch (Throwable ex) {
			result.failure = describe(ex) + " (record at " + position + ")";
		}
		return result;
	}

	/**
	 * Reads the logical payload of a chain through the reader under test in a mix of small and large reads and
	 * compares it with the oracle's.
	 *
	 * @param input  the reader under test, positioned at the payload
	 * @param chain  the oracle's view of the record
	 * @param random source of the read sizes
	 * @return nothing - the signature matches the payload reader callbacks
	 */
	@Nullable
	private static Void readPayload(
		@Nonnull ObservableInput<?> input,
		@Nonnull OracleChain chain,
		@Nonnull Random random
	) {
		final byte[] expected = chain.payload();
		final int[] splits = chain.splitPoints();
		final byte[] read = new byte[expected.length];
		int offset = 0;
		int splitIndex = 0;
		while (offset < expected.length) {
			while (splits[splitIndex] <= offset) {
				splitIndex++;
			}
			// a read never crosses the split between two physical records of a chain, see the class documentation
			final int remaining = UNALIGNED ? expected.length - offset : splits[splitIndex] - offset;
			final int kind = random.nextInt(10);
			final int length;
			if (kind <= 2) {
				length = 1;
			} else if (kind <= 4 && remaining >= 4) {
				length = 4;
			} else if (kind == 5 && remaining >= 8) {
				length = 8;
			} else if (kind <= 8) {
				length = 1 + random.nextInt(Math.min(remaining, 64));
			} else {
				length = 1 + random.nextInt(Math.min(remaining, 50_000));
			}
			if (length == 1) {
				read[offset] = input.readByte();
			} else {
				input.readBytes(read, offset, length);
			}
			offset += length;
		}
		if (!Arrays.equals(read, expected)) {
			throw new IllegalStateException(
				"payload of the record at " + chain.start() + " differs from the file at payload byte " +
					Arrays.mismatch(read, expected)
			);
		}
		return null;
	}

	/**
	 * Formats an exception with its causes on one line.
	 *
	 * @param ex the exception
	 * @return the description
	 */
	@Nonnull
	private static String describe(@Nonnull Throwable ex) {
		final StringBuilder sb = new StringBuilder(256)
			.append(ex.getClass().getSimpleName()).append(": ").append(ex.getMessage());
		Throwable cause = ex.getCause();
		for (int depth = 0; cause != null && depth < 5; depth++) {
			sb.append(" <- ").append(cause.getClass().getSimpleName()).append(": ").append(cause.getMessage());
			cause = cause.getCause();
		}
		return sb.toString().replace('\n', ' ');
	}

	/**
	 * Thrown when the oracle itself cannot parse the file - a statement about the bytes, not about the reader.
	 */
	private static final class OracleException extends RuntimeException {
		private static final long serialVersionUID = -2717394187458230212L;

		/**
		 * @param message what the oracle could not parse
		 */
		OracleException(@Nonnull String message) {
			super(message);
		}
	}

	/**
	 * Little-endian random access to a file of any size through a sliding memory-mapped window.
	 */
	private static final class RecordSource implements AutoCloseable {
		/**
		 * Size of the mapped window.
		 */
		private static final long WINDOW = 512L * 1024 * 1024;
		private final FileChannel channel;
		private final long size;
		@Nullable private MappedByteBuffer window;
		private long windowStart = -1L;
		private long windowEnd = -1L;

		/**
		 * @param path file to open
		 */
		RecordSource(@Nonnull Path path) throws IOException {
			this.channel = FileChannel.open(path);
			this.size = this.channel.size();
		}

		/**
		 * @return the file size
		 */
		long size() {
			return this.size;
		}

		/**
		 * Maps the window so that it covers `length` bytes from `position` and returns the index of `position` in it.
		 */
		private int locate(long position, int length) {
			if (position < this.windowStart || position + length > this.windowEnd) {
				if (length > WINDOW / 2) {
					throw new OracleException("a record of " + length + " B exceeds the oracle's window");
				}
				try {
					this.windowStart = position;
					this.windowEnd = Math.min(this.size, position + WINDOW);
					this.window = this.channel.map(
						FileChannel.MapMode.READ_ONLY, this.windowStart, this.windowEnd - this.windowStart
					);
					this.window.order(ByteOrder.LITTLE_ENDIAN);
				} catch (IOException ex) {
					throw new OracleException("the file cannot be mapped at " + position + ": " + ex.getMessage());
				}
			}
			return Math.toIntExact(position - this.windowStart);
		}

		int getInt(long position) {
			final int index = locate(position, 4);
			return this.window.getInt(index);
		}

		long getLong(long position) {
			final int index = locate(position, 8);
			return this.window.getLong(index);
		}

		byte get(long position) {
			final int index = locate(position, 1);
			return this.window.get(index);
		}

		void get(long position, @Nonnull byte[] target) {
			final int index = locate(position, target.length);
			this.window.get(index, target, 0, target.length);
		}

		@Override
		public void close() throws IOException {
			this.channel.close();
		}
	}

	/**
	 * One logical record as the oracle reads it: a single physical record, or a chain of physical records joined by
	 * the continuation bit, with the payload inflated and concatenated.
	 *
	 * @param start       file offset of the first physical record
	 * @param end         file offset just after the last physical record
	 * @param partLengths length of the logical payload each physical record contributes
	 * @param payload     the logical payload
	 * @param compressed  number of compressed physical records in the chain
	 */
	private record OracleChain(long start, long end, int[] partLengths, byte[] payload, int compressed) {

		/**
		 * Reads the chain starting at `position`, verifying every physical record's checksum on the way.
		 *
		 * @param source the file
		 * @param position offset of the first physical record
		 * @return the chain
		 */
		@Nonnull
		static OracleChain read(@Nonnull RecordSource source, long position) {
			final List<byte[]> parts = new ArrayList<>(1);
			long recordPosition = position;
			int compressed = 0;
			byte control;
			do {
				if (recordPosition + OVERHEAD > source.size()) {
					throw new OracleException(
						"a record header at " + recordPosition + " runs past the end of the file"
					);
				}
				final int length = source.getInt(recordPosition);
				if (length < OVERHEAD) {
					throw new OracleException(
						"a record at " + recordPosition + " declares an impossible length " + length
					);
				}
				if (recordPosition + length > source.size()) {
					throw new OracleException(
						"a record at " + recordPosition + " of " + length + " B runs past the end of the file"
					);
				}
				control = source.get(recordPosition + 4);
				final byte[] raw = new byte[length - OVERHEAD];
				source.get(recordPosition + 13, raw);
				if (isBitSet(control, CRC32_BIT)) {
					final CRC32C crc = new CRC32C();
					crc.update(raw);
					crc.update(control);
					if (crc.getValue() != source.getLong(recordPosition + length - 8)) {
						throw new OracleException(
							"the checksum of the record at " + recordPosition + " does not match"
						);
					}
				}
				if (isBitSet(control, COMPRESSION_BIT)) {
					compressed++;
					parts.add(inflate(raw, recordPosition));
				} else {
					parts.add(raw);
				}
				recordPosition += length;
			} while (isBitSet(control, CONTINUATION_BIT));

			final int[] partLengths = new int[parts.size()];
			int total = 0;
			for (int i = 0; i < partLengths.length; i++) {
				partLengths[i] = parts.get(i).length;
				total += partLengths[i];
			}
			final byte[] payload;
			if (parts.size() == 1) {
				payload = parts.get(0);
			} else {
				payload = new byte[total];
				int offset = 0;
				for (byte[] part : parts) {
					System.arraycopy(part, 0, payload, offset, part.length);
					offset += part.length;
				}
			}
			return new OracleChain(position, recordPosition, partLengths, payload, compressed);
		}

		/**
		 * @return payload offsets where the physical records of the chain end
		 */
		@Nonnull
		int[] splitPoints() {
			final int[] splits = new int[this.partLengths.length];
			int sum = 0;
			for (int i = 0; i < splits.length; i++) {
				sum += this.partLengths[i];
				splits[i] = sum;
			}
			return splits;
		}

		/**
		 * Inflates one raw deflate stream completely.
		 */
		@Nonnull
		private static byte[] inflate(@Nonnull byte[] raw, long position) {
			final Inflater inflater = new Inflater(true);
			inflater.setInput(raw);
			final ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length * 4 + 16);
			final byte[] chunk = new byte[65_536];
			try {
				while (!inflater.finished()) {
					final int inflated = inflater.inflate(chunk);
					if (inflated == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
						throw new OracleException("the compressed record at " + position + " ends prematurely");
					}
					out.write(chunk, 0, inflated);
				}
				if (inflater.getRemaining() != 0) {
					throw new OracleException("the compressed record at " + position + " has bytes past its end");
				}
			} catch (DataFormatException ex) {
				throw new OracleException(
					"the compressed record at " + position + " cannot be inflated: " + ex.getMessage()
				);
			} finally {
				inflater.end();
			}
			return out.toByteArray();
		}

		private static boolean isBitSet(byte value, int bit) {
			return ((value & 0xff) & (1 << bit)) != 0;
		}
	}

	/**
	 * Outcome of verifying one file in one access pattern with one buffer size.
	 */
	private static final class FileResult {
		private final String file;
		private final String mode;
		private final int bufferSize;
		private long records;
		private long compressed;
		private long chained;
		private long transactions;
		private long payloadBytes;
		private long offsetMismatches;
		private long framingMismatches;
		@Nullable private String firstOffsetMismatch;
		@Nullable private String failure;
		@Nullable private String tornTail;

		FileResult(@Nonnull String file, @Nonnull String mode, int bufferSize) {
			this.file = file;
			this.mode = mode;
			this.bufferSize = bufferSize;
		}

		@Nonnull
		String file() {
			return this.file;
		}

		@Nonnull
		String mode() {
			return this.mode;
		}

		int bufferSize() {
			return this.bufferSize;
		}

		/**
		 * @return true when the reader agreed with the oracle on everything
		 */
		boolean isOk() {
			return this.failure == null && this.offsetMismatches == 0 && this.framingMismatches == 0;
		}

		/**
		 * Compares the reader's stream offset with the oracle's.
		 */
		void checkOffset(@Nonnull ObservableInput<?> input, long expected, @Nonnull String where) {
			final long actual = input.total();
			if (actual != expected) {
				this.offsetMismatches++;
				if (this.firstOffsetMismatch == null) {
					this.firstOffsetMismatch = where + ": total() " + actual + ", file offset " + expected;
				}
			}
		}

		/**
		 * Adds a verified chain to the counters.
		 */
		void count(@Nonnull OracleChain chain) {
			this.records++;
			this.compressed += chain.compressed();
			if (chain.partLengths().length > 1) {
				this.chained++;
			}
			this.payloadBytes += chain.payload().length;
		}

		/**
		 * Tells a torn tail - the last record runs past the end of the file, which a crash leaves behind and which
		 * nothing references - from bytes the oracle cannot parse in the middle of the file.
		 */
		void classifyOracleFailure(@Nonnull OracleException ex, long position, long fileLength) {
			if (ex.getMessage().contains("past the end of the file")) {
				this.tornTail = ex.getMessage() + " (" + (fileLength - position) + " B left)";
			} else {
				this.failure = "ORACLE: " + ex.getMessage();
			}
		}

		@Nonnull
		String describe() {
			return String.format(
				"%s|%s|%d|records=%d|compressed=%d|chained=%d|tx=%d|payloadMB=%.1f|offsetMismatches=%d|" +
					"framingMismatches=%d|status=%s|failure=%s|tornTail=%s|firstMismatch=%s",
				this.file, this.mode, this.bufferSize, this.records, this.compressed, this.chained, this.transactions,
				this.payloadBytes / 1048576.0, this.offsetMismatches, this.framingMismatches,
				isOk() ? "OK" : "FAILED", this.failure, this.tornTail, this.firstOffsetMismatch
			);
		}
	}
}
