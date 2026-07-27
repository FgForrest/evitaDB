/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2021-2026
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

package io.evitadb.store.checksum;

import io.evitadb.utils.Crc32CWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.zip.CRC32C;

import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test verifies contract of {@link Crc32CChecksum} class - the single {@link Checksum} implementation
 * returned by both {@link ChecksumFactory#createChecksum()} and {@link ChecksumFactory#createCumulativeChecksum(long)}.
 * Verifies plain streaming correctness, and that the lazy value-mode / stream-mode split (introduced to
 * eliminate {@code forceValue} calls wherever possible) remains bit-for-bit identical to the previous
 * always-live-object implementation across every operation and every interleaving of value-mode and
 * stream-mode operations - including the value-then-stream-without-reset pattern real WAL write/scan/
 * migration call sites exercise.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Test verifies contract of Crc32CChecksum class")
@Tag(STORAGE)
@Tag(SERIALIZATION)
class Crc32CChecksumTest {

	/**
	 * Re-implements the pre-refactor always-live, forceValue-seeded {@link Crc32CWrapper} object semantics -
	 * used as a reference oracle to cross-check the lazy-mode implementation across randomized operation
	 * sequences.
	 */
	private static final class LegacyChecksumOracle {
		private final Crc32CWrapper wrapper;

		LegacyChecksumOracle() {
			this.wrapper = new Crc32CWrapper();
		}

		void update(byte b) {
			this.wrapper.withByte(b);
		}

		void update(int b) {
			this.wrapper.withInt(b);
		}

		void update(long l) {
			this.wrapper.withLong(l);
		}

		void update(byte[] b) {
			this.wrapper.withByteArray(b);
		}

		void update(byte[] b, int off, int len) {
			this.wrapper.withByteArray(b, off, len);
		}

		void combine(long checksum, int contentLength) {
			this.wrapper.withAnotherChecksum(checksum, contentLength);
		}

		long getValue() {
			return this.wrapper.getValue();
		}

		void reset() {
			this.wrapper.reset();
		}

		void reset(long initialValue) {
			this.wrapper.reset(initialValue);
		}
	}

	private static long crc32c(byte[] bytes) {
		final CRC32C crc = new CRC32C();
		crc.update(bytes, 0, bytes.length);
		return crc.getValue();
	}

	@Test
	@DisplayName("Should start at zero with no-arg constructor")
	void shouldStartAtZero() {
		final Checksum checksum = new Crc32CChecksum();
		assertEquals(0L, checksum.getValue());
	}

	@Test
	@DisplayName("Should compute the same value as java.util.zip.CRC32C for a plain byte-array stream")
	void shouldComputeSameValueAsJdkCrc32ForByteArrayStream() {
		final byte[] data = "hello checksum world".getBytes(StandardCharsets.UTF_8);
		final Checksum checksum = new Crc32CChecksum();
		checksum.update(data);

		assertEquals(crc32c(data), checksum.getValue());
	}

	@Test
	@DisplayName("Should compute the same value for a byte-array slice")
	void shouldComputeSameValueForByteArraySlice() {
		final byte[] data = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
		final Checksum checksum = new Crc32CChecksum();
		checksum.update(data, 2, 10);

		final CRC32C expected = new CRC32C();
		expected.update(data, 2, 10);

		assertEquals(expected.getValue(), checksum.getValue());
	}

	@Test
	@DisplayName("Should stream single bytes correctly")
	void shouldStreamSingleBytesCorrectly() {
		final Checksum checksum = new Crc32CChecksum();
		checksum.update((byte) 1);
		checksum.update((byte) 2);
		checksum.update((byte) 3);

		final CRC32C expected = new CRC32C();
		expected.update(new byte[]{1, 2, 3});

		assertEquals(expected.getValue(), checksum.getValue());
	}

	@Test
	@DisplayName("Should verify equalsTo against the expected checksum")
	void shouldVerifyEqualsToAgainstExpectedChecksum() {
		final byte[] data = "verify me".getBytes(StandardCharsets.UTF_8);
		final Checksum checksum = new Crc32CChecksum();
		checksum.update(data);

		assertTrue(checksum.equalsTo(crc32c(data)));
	}

	@Test
	@DisplayName("Should reset back to zero and allow reuse")
	void shouldResetBackToZeroAndAllowReuse() {
		final Checksum checksum = new Crc32CChecksum();
		checksum.update("garbage".getBytes(StandardCharsets.UTF_8));
		checksum.reset();

		assertEquals(0L, checksum.getValue());

		final byte[] data = "fresh data".getBytes(StandardCharsets.UTF_8);
		checksum.update(data);
		assertEquals(crc32c(data), checksum.getValue());
	}

	@Test
	@DisplayName("Should reset with an initial value and preserve it when no new data is added")
	void shouldResetWithInitialValueAndPreserveIt() {
		final long initialChecksum = crc32c("some existing data".getBytes(StandardCharsets.UTF_8));

		final Checksum checksum = new Crc32CChecksum();
		checksum.update("discarded".getBytes(StandardCharsets.UTF_8));
		checksum.reset(initialChecksum);

		assertEquals(initialChecksum, checksum.getValue());
	}

	@Test
	@DisplayName("Should track value-mode combine/update(long)/update(int) without ever needing byte streaming")
	void shouldTrackValueModeOperationsCorrectly() {
		// mirrors ObservableInput's finalize block: reset() -> combine(header) -> combine(payload) -> update(trailer)
		final long headerChecksum = crc32c("header-bytes".getBytes(StandardCharsets.UTF_8));
		final long payloadChecksum = crc32c("payload-bytes-of-the-record".getBytes(StandardCharsets.UTF_8));
		final long trailerValue = 123456789L;

		final Checksum checksum = new Crc32CChecksum();
		checksum.reset();
		checksum.combine(headerChecksum, "header-bytes".length());
		checksum.combine(payloadChecksum, "payload-bytes-of-the-record".length());
		checksum.update(trailerValue);

		final LegacyChecksumOracle oracle = new LegacyChecksumOracle();
		oracle.reset();
		oracle.combine(headerChecksum, "header-bytes".length());
		oracle.combine(payloadChecksum, "payload-bytes-of-the-record".length());
		oracle.update(trailerValue);

		assertEquals(oracle.getValue(), checksum.getValue());
	}

	@Test
	@DisplayName("Should track the AbstractMutationSupplier WAL-file-open pattern (constructor(initial) + update(initial))")
	void shouldTrackWalFileOpenPatternCorrectly() {
		final long initialChecksum = 987654321L;

		final Checksum checksum = new Crc32CChecksum(initialChecksum);
		checksum.update(initialChecksum);

		final LegacyChecksumOracle oracle = new LegacyChecksumOracle();
		oracle.reset(initialChecksum);
		oracle.update(initialChecksum);

		assertEquals(oracle.getValue(), checksum.getValue());
	}

	@Test
	@DisplayName("Should track the AbstractMutationLog write-path pattern: value fold, then stream, then combine, then value fold, with no reset in between")
	void shouldTrackWalWritePathPatternCorrectly() {
		// mirrors AbstractMutationLog.appendTransaction: update(int) -> update(byte[]) [no reset!] -> combine -> update(long)
		final int contentLength = 4242;
		final byte[] transactionBytes = "serialized-transaction-mutation-bytes".getBytes(StandardCharsets.UTF_8);
		final long walReferenceChecksum = crc32c("wal-reference-payload".getBytes(StandardCharsets.UTF_8));
		final int walReferenceContentLength = "wal-reference-payload".length();

		final Checksum checksum = new Crc32CChecksum();
		checksum.update(contentLength);
		checksum.update(transactionBytes);
		checksum.combine(walReferenceChecksum, walReferenceContentLength);
		final long cumulativeChecksum = checksum.getValue();
		checksum.update(cumulativeChecksum);

		final LegacyChecksumOracle oracle = new LegacyChecksumOracle();
		oracle.update(contentLength);
		oracle.update(transactionBytes);
		oracle.combine(walReferenceChecksum, walReferenceContentLength);
		final long oracleCumulativeChecksum = oracle.getValue();
		oracle.update(oracleCumulativeChecksum);

		assertEquals(oracleCumulativeChecksum, cumulativeChecksum);
		assertEquals(oracle.getValue(), checksum.getValue());
	}

	@Test
	@DisplayName("Should transition from value mode to stream mode and continue correctly when a byte stream follows a value-mode operation")
	void shouldTransitionFromValueModeToStreamModeCorrectly() {
		final long cumulative = crc32c("prefix".getBytes(StandardCharsets.UTF_8));
		final byte[] suffix = "suffix-bytes".getBytes(StandardCharsets.UTF_8);

		final Checksum checksum = new Crc32CChecksum(cumulative);
		checksum.update(suffix);

		final LegacyChecksumOracle oracle = new LegacyChecksumOracle();
		oracle.reset(cumulative);
		oracle.update(suffix);

		assertEquals(oracle.getValue(), checksum.getValue());
	}

	@Test
	@DisplayName("Should transition from a zero value-mode value to stream mode identically to a forceValue(0)-seeded legacy wrapper")
	void shouldTransitionFromZeroValueModeToStreamModeIdenticallyToForceValueSeededWrapper() {
		// enterStreamMode() special-cases value == 0 to call the cheap Crc32CWrapper#reset() rather than
		// Crc32CWrapper#reset(0)/forceValue(0) - both must lead to the identical live register state, since
		// getValue() is a bijection of the CRC32C register: the one state that yields 0 is the same state
		// whether reached by a genuine reset or by forceValue(0). Cross-check directly against the legacy
		// forceValue(0)-seeded constructor path, not just a never-forced fresh wrapper.
		final byte[] suffix = "suffix-bytes-after-a-zero-cumulative-value".getBytes(StandardCharsets.UTF_8);

		final Checksum checksum = new Crc32CChecksum(0L);
		checksum.update(suffix);

		final long legacyForceValueZeroSeeded = new Crc32CWrapper(0L).withByteArray(suffix).getValue();

		assertEquals(legacyForceValueZeroSeeded, checksum.getValue());
	}

	@Test
	@DisplayName("Should match the legacy live-object implementation across a large randomized operation sequence")
	void shouldMatchLegacyImplementationAcrossRandomizedOperationSequence() {
		final Random random = new Random(2026_07_09L);

		for (int run = 0; run < 200; run++) {
			final Checksum checksum = new Crc32CChecksum();
			final LegacyChecksumOracle oracle = new LegacyChecksumOracle();

			for (int op = 0; op < 50; op++) {
				final int kind = random.nextInt(7);
				switch (kind) {
					case 0 -> {
						final byte value = (byte) random.nextInt();
						checksum.update(value);
						oracle.update(value);
					}
					case 1 -> {
						final int value = random.nextInt();
						checksum.update(value);
						oracle.update(value);
					}
					case 2 -> {
						final long value = random.nextLong();
						checksum.update(value);
						oracle.update(value);
					}
					case 3 -> {
						final byte[] data = new byte[1 + random.nextInt(32)];
						random.nextBytes(data);
						checksum.update(data);
						oracle.update(data);
					}
					case 4 -> {
						final byte[] data = new byte[4 + random.nextInt(32)];
						random.nextBytes(data);
						final int off = 1;
						final int len = data.length - 2;
						checksum.update(data, off, len);
						oracle.update(data, off, len);
					}
					case 5 -> {
						final byte[] chunk = new byte[1 + random.nextInt(16)];
						random.nextBytes(chunk);
						final long chunkChecksum = crc32c(chunk);
						checksum.combine(chunkChecksum, chunk.length);
						oracle.combine(chunkChecksum, chunk.length);
					}
					case 6 -> {
						if (random.nextBoolean()) {
							checksum.reset();
							oracle.reset();
						} else {
							final long initialValue = random.nextLong() & 0xFFFFFFFFL;
							checksum.reset(initialValue);
							oracle.reset(initialValue);
						}
					}
					default -> throw new IllegalStateException("Unexpected op kind: " + kind);
				}

				assertEquals(
					oracle.getValue(), checksum.getValue(),
					"Mismatch after op #" + op + " (kind=" + kind + ") in run #" + run
				);
			}
		}
	}

}
