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

package io.evitadb.store.index.serializer.util;

import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.util.Random;

import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip and invariant coverage for {@link SortedIntArrayCodec}. The two encoding forms are covered by a nested
 * group each: {@link WholeArrays} exercises the count-prefixed whole-array form (every shape - empty, single,
 * negative-first, large-gap, large random ascending - must survive a write/read cycle, the empty case must decode to a
 * non-null array, the encoding must be physically smaller than raw 4-byte ints for a dense ascending array, and a
 * decreasing input must fail loud on write), while {@link AscendingRuns} exercises the count-less run form whose length
 * is supplied by the caller.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("SortedIntArrayCodec round-trip and invariants")
@Tag(STORAGE)
@Tag(SERIALIZATION)
class SortedIntArrayCodecTest {

	/**
	 * Encodes the array, decodes it back and returns the decoded copy.
	 *
	 * @param ascending the array to round-trip
	 * @return the decoded array
	 */
	@Nonnull
	private static int[] roundTrip(@Nonnull int[] ascending) {
		return SortedIntArrayCodec.readAscendingInts(new Input(encode(ascending)));
	}

	/**
	 * Encodes the array into a byte buffer.
	 *
	 * @param ascending the array to encode
	 * @return the encoded bytes
	 */
	@Nonnull
	private static byte[] encode(@Nonnull int[] ascending) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(1_024);
		try (final Output output = new Output(os, 1_024)) {
			SortedIntArrayCodec.writeAscendingInts(output, ascending);
		}
		return os.toByteArray();
	}

	@Nested
	@DisplayName("Whole arrays (count-prefixed)")
	class WholeArrays {

		@Test
		@DisplayName("round-trips an empty array to a non-null empty array")
		void shouldRoundTripEmptyArray() {
			final int[] decoded = roundTrip(new int[0]);
			assertNotNull(decoded, "the decoded empty array must never be null");
			assertEquals(0, decoded.length);
		}

		@Test
		@DisplayName("round-trips a single-element array")
		void shouldRoundTripSingleElement() {
			assertArrayEquals(new int[]{42}, roundTrip(new int[]{42}));
		}

		@Test
		@DisplayName("round-trips a single negative element (zig-zag first)")
		void shouldRoundTripSingleNegativeElement() {
			assertArrayEquals(new int[]{-987_654}, roundTrip(new int[]{-987_654}));
		}

		@Test
		@DisplayName("round-trips an array whose smallest element is negative")
		void shouldRoundTripNegativeFirst() {
			final int[] ascending = {-100, -1, 0, 5, 5, 7, 1_000};
			assertArrayEquals(ascending, roundTrip(ascending));
		}

		@Test
		@DisplayName("round-trips an array with very large gaps including near Integer.MAX_VALUE")
		void shouldRoundTripLargeGaps() {
			final int[] ascending = {0, 1, 1_000_000, Integer.MAX_VALUE - 1, Integer.MAX_VALUE};
			assertArrayEquals(ascending, roundTrip(ascending));
		}

		@Test
		@DisplayName("round-trips an array spanning the full negative-to-positive range")
		void shouldRoundTripFullRange() {
			final int[] ascending = {Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE};
			assertArrayEquals(ascending, roundTrip(ascending));
		}

		@Test
		@DisplayName("round-trips two adjacent extremes whose gap overflows the signed int")
		void shouldRoundTripAdjacentFullIntRange() {
			// the single gap MAX_VALUE - MIN_VALUE overflows the signed int to -1 (unsigned 4294967295), the largest
			// representable gap; the reader must wrap it back via two's-complement addition (previous += gap) so that
			// MIN_VALUE + (-1) lands exactly on MAX_VALUE. Unlike shouldRoundTripFullRange, whose interior values cap the
			// widest single gap at Integer.MAX_VALUE, this two-element array exercises the wraparound itself.
			final int[] ascending = {Integer.MIN_VALUE, Integer.MAX_VALUE};
			assertArrayEquals(ascending, roundTrip(ascending));
		}

		@Test
		@DisplayName("round-trips a large dense ascending array")
		void shouldRoundTripLargeArray() {
			final int[] ascending = new int[10_000];
			final Random random = new Random(42);
			int running = -5_000;
			for (int i = 0; i < ascending.length; i++) {
				running += random.nextInt(7); // gaps 0..6 keep the array non-decreasing
				ascending[i] = running;
			}
			assertArrayEquals(ascending, roundTrip(ascending));
		}

		@Test
		@DisplayName("allows equal neighbouring values (non-decreasing, not strictly increasing)")
		void shouldAllowEqualNeighbours() {
			final int[] ascending = {3, 3, 3, 4, 4};
			assertArrayEquals(ascending, roundTrip(ascending));
		}

		@Test
		@DisplayName("the delta-varint encoding is smaller than raw 4-byte ints for a dense ascending array")
		void shouldBeSmallerThanRawForDenseArray() {
			final int[] ascending = new int[1_000];
			for (int i = 0; i < ascending.length; i++) {
				ascending[i] = i; // gaps of 1 -> one byte each
			}
			final int encodedLength = encode(ascending).length;
			final int rawLength = ascending.length * Integer.BYTES;
			assertTrue(
				encodedLength < rawLength,
				"delta-varint (" + encodedLength + " B) must beat raw 4-byte ints (" + rawLength + " B) for dense data"
			);
		}

		@Test
		@DisplayName("fails loud when the input is decreasing")
		void shouldFailOnDecreasingInput() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> encode(new int[]{1, 2, 1}),
				"a decreasing input must be rejected on write so a future unsorted caller is caught immediately"
			);
		}
	}

	@Nested
	@DisplayName("Runs (no count prefix, length supplied by caller)")
	class AscendingRuns {

		/**
		 * Encodes the sub-range `[from, from + len)` of {@code array} as a run and decodes it back into a freshly padded
		 * destination at the same offset, returning only the decoded `[from, from + len)` slice. The destination is
		 * deliberately larger than the run so that an over-write outside the run would be caught.
		 *
		 * @param array the backing array holding the run
		 * @param from  the inclusive start index
		 * @param len   the run length
		 * @return the decoded slice
		 */
		@Nonnull
		private static int[] roundTripRun(@Nonnull int[] array, int from, int len) {
			final ByteArrayOutputStream os = new ByteArrayOutputStream(1_024);
			try (final Output output = new Output(os, 1_024)) {
				SortedIntArrayCodec.writeAscendingRun(output, array, from, len);
			}
			final int[] dst = new int[from + len + 3];
			try (final Input input = new Input(os.toByteArray())) {
				SortedIntArrayCodec.readAscendingRun(input, dst, from, len);
			}
			final int[] slice = new int[len];
			System.arraycopy(dst, from, slice, 0, len);
			return slice;
		}

		@Test
		@DisplayName("round-trips a run located at a non-zero offset within a larger array")
		void shouldRoundTripRunAtOffset() {
			final int[] array = {99, 99, 1, 2, 5, 9, 77};
			assertArrayEquals(new int[]{1, 2, 5, 9}, roundTripRun(array, 2, 4));
		}

		@Test
		@DisplayName("a zero-length run writes nothing and reads as a no-op")
		void shouldHandleEmptyRun() {
			final ByteArrayOutputStream os = new ByteArrayOutputStream(16);
			try (final Output output = new Output(os, 16)) {
				SortedIntArrayCodec.writeAscendingRun(output, new int[]{1, 2, 3}, 1, 0);
			}
			assertEquals(0, os.toByteArray().length, "a zero-length run must write no bytes");
			final int[] dst = {7, 7, 7};
			try (final Input input = new Input(new byte[0])) {
				SortedIntArrayCodec.readAscendingRun(input, dst, 1, 0);
			}
			assertArrayEquals(new int[]{7, 7, 7}, dst, "a zero-length read must touch nothing");
		}

		@Test
		@DisplayName("round-trips a single-element run")
		void shouldRoundTripSingleElementRun() {
			assertArrayEquals(new int[]{42}, roundTripRun(new int[]{42}, 0, 1));
		}

		@Test
		@DisplayName("round-trips a run whose first element is large (>= 2^28, 5-byte zig-zag)")
		void shouldRoundTripHugeFirstElementRun() {
			final int[] array = {300_000_000, 300_000_005, 300_000_006};
			assertArrayEquals(array, roundTripRun(array, 0, 3));
		}

		@Test
		@DisplayName("allows equal neighbours within a run")
		void shouldAllowEqualNeighboursInRun() {
			final int[] array = {3, 3, 4, 4};
			assertArrayEquals(array, roundTripRun(array, 0, 4));
		}

		@Test
		@DisplayName("fails loud when a run is decreasing")
		void shouldFailOnDecreasingRun() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> roundTripRun(new int[]{5, 4, 3}, 0, 3),
				"a decreasing run must be rejected on write, matching the array codec"
			);
		}

		@Test
		@DisplayName("round-trips several runs written back-to-back into one stream at increasing offsets")
		void shouldRoundTripMultipleConcatenatedRunsAtIncreasingOffsets() {
			// mirrors the SortIndex usage where consecutive per-value runs share a single stream and the caller
			// supplies each run length out of band; each run is independently ascending but they are unrelated
			final int[][] runs = {
				{1, 2, 5},
				{10, 10, 11, 99},
				{-7, -3, 0},
				{1_000_000}
			};

			// write every run sequentially into ONE Output, with each run landing at an increasing destination offset
			final ByteArrayOutputStream os = new ByteArrayOutputStream(1_024);
			try (final Output output = new Output(os, 1_024)) {
				for (int i = 0; i < runs.length; i++) {
					SortedIntArrayCodec.writeAscendingRun(output, runs[i], 0, runs[i].length);
				}
			}

			// read every run back from ONE Input into ONE destination array, supplying each run length in order
			int totalLength = 0;
			for (int i = 0; i < runs.length; i++) {
				totalLength += runs[i].length;
			}
			final int[] dst = new int[totalLength];
			final int[] offsets = new int[runs.length];
			try (final Input input = new Input(os.toByteArray())) {
				int offset = 0;
				for (int i = 0; i < runs.length; i++) {
					offsets[i] = offset;
					SortedIntArrayCodec.readAscendingRun(input, dst, offset, runs[i].length);
					offset += runs[i].length;
				}
			}

			// every decoded run must match its source slice verbatim
			for (int i = 0; i < runs.length; i++) {
				final int[] slice = new int[runs[i].length];
				System.arraycopy(dst, offsets[i], slice, 0, runs[i].length);
				assertArrayEquals(runs[i], slice, "concatenated run #" + i);
			}
		}

		@Test
		@DisplayName("a run omits the count prefix the array codec writes")
		void shouldOmitCountPrefix() {
			// a single-element run is just the zig-zag first element; writeAscendingInts adds a leading count varint
			final ByteArrayOutputStream runOs = new ByteArrayOutputStream(16);
			try (final Output output = new Output(runOs, 16)) {
				SortedIntArrayCodec.writeAscendingRun(output, new int[]{42}, 0, 1);
			}
			assertEquals(
				encode(new int[]{42}).length - 1, runOs.toByteArray().length,
				"the run must be exactly one byte (the count varint) shorter than the count-prefixed array form"
			);
		}
	}
}
