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
 * Round-trip and invariant coverage for {@link SortedIntArrayCodec}: ascending arrays of every shape (empty, single,
 * negative-first, large-gap, large random ascending) must survive a write/read cycle unchanged, the empty case must
 * decode to a non-null array, the encoding must be physically smaller than raw 4-byte ints for a dense ascending array,
 * and a decreasing input must fail loud on write.
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
