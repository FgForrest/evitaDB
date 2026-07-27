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

package io.evitadb.store.shared.serializer.dataType;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.store.shared.kryo.KryoFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Kryo round-trip coverage for {@link BigDecimalNumberRangeSerializer}, focused on open-ended ranges (a `null`
 * lower or upper bound, or the {@link BigDecimalNumberRange#INFINITE} constant). The storage Kryo runs with
 * `setReferences(false)`, so a closed range must keep its byte layout unchanged — the closed-range golden test
 * locks that contract in place.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("BigDecimalNumberRangeSerializer round-trip (open-ended bounds + closed-range byte compatibility)")
@Tag(STORAGE)
@Tag(SERIALIZATION)
@Tag(DATA_TYPE)
class BigDecimalNumberRangeSerializerTest {

	/**
	 * Golden on-disk bytes of `between(1.50, 9.90, 2)` produced by the serializer before the open-ended fix was
	 * applied. The closed-range layout must remain byte-for-byte identical so existing persisted data stays readable.
	 */
	private static final byte[] CLOSED_RANGE_GOLDEN = new byte[]{
		3, 0, -106, 4, 3, 3, -34, 4, 1, 4, -106, 0, 0, 0, 0, 0, 0, 0, -34, 3, 0, 0, 0, 0, 0, 0
	};

	private Kryo kryo;
	private BigDecimalNumberRangeSerializer serializer;

	@BeforeEach
	void setUp() {
		this.kryo = KryoFactory.createKryo();
		this.serializer = new BigDecimalNumberRangeSerializer();
	}

	@Nonnull
	private byte[] serialize(@Nonnull BigDecimalNumberRange range) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(64);
		try (final Output output = new Output(os, 64)) {
			this.serializer.write(this.kryo, output, range);
		}
		return os.toByteArray();
	}

	@Nonnull
	private BigDecimalNumberRange roundTrip(@Nonnull BigDecimalNumberRange range) {
		try (final Input input = new Input(serialize(range))) {
			return this.serializer.read(this.kryo, input, BigDecimalNumberRange.class);
		}
	}

	@Test
	@DisplayName("round-trips an open-ended range with only a lower bound")
	void shouldRoundTripOpenEndedFromRange() {
		final BigDecimalNumberRange original = BigDecimalNumberRange.from(new BigDecimal("3.14"));

		final BigDecimalNumberRange deserialized = roundTrip(original);

		assertEquals(new BigDecimal("3.14"), deserialized.getPreciseFrom());
		assertNull(deserialized.getPreciseTo());
		assertEquals(original.getFrom(), deserialized.getFrom());
		assertEquals(original.getTo(), deserialized.getTo());
		assertEquals(Long.MAX_VALUE, deserialized.getTo());
		assertEquals(original.getRetainedDecimalPlaces(), deserialized.getRetainedDecimalPlaces());
	}

	@Test
	@DisplayName("round-trips an open-ended range with only an upper bound")
	void shouldRoundTripOpenEndedToRange() {
		final BigDecimalNumberRange original = BigDecimalNumberRange.to(new BigDecimal("42.00"), 2);

		final BigDecimalNumberRange deserialized = roundTrip(original);

		assertNull(deserialized.getPreciseFrom());
		assertEquals(new BigDecimal("42.00"), deserialized.getPreciseTo());
		assertEquals(original.getFrom(), deserialized.getFrom());
		assertEquals(Long.MIN_VALUE, deserialized.getFrom());
		assertEquals(original.getTo(), deserialized.getTo());
		assertEquals(original.getRetainedDecimalPlaces(), deserialized.getRetainedDecimalPlaces());
	}

	@Test
	@DisplayName("round-trips the INFINITE range with both bounds open")
	void shouldRoundTripInfiniteRange() {
		final BigDecimalNumberRange deserialized = roundTrip(BigDecimalNumberRange.INFINITE);

		assertNull(deserialized.getPreciseFrom());
		assertNull(deserialized.getPreciseTo());
		assertEquals(Long.MIN_VALUE, deserialized.getFrom());
		assertEquals(Long.MAX_VALUE, deserialized.getTo());
	}

	@Test
	@DisplayName("round-trips a closed range built without explicit decimal places")
	void shouldRoundTripClosedRange() {
		final BigDecimalNumberRange original =
			BigDecimalNumberRange.between(new BigDecimal("1.50"), new BigDecimal("9.90"));

		final BigDecimalNumberRange deserialized = roundTrip(original);

		assertEquals(new BigDecimal("1.50"), deserialized.getPreciseFrom());
		assertEquals(new BigDecimal("9.90"), deserialized.getPreciseTo());
		assertEquals(original.getFrom(), deserialized.getFrom());
		assertEquals(original.getTo(), deserialized.getTo());
		assertEquals(original.getRetainedDecimalPlaces(), deserialized.getRetainedDecimalPlaces());
	}

	@Test
	@DisplayName("round-trips a closed range built with explicit retained decimal places")
	void shouldRoundTripClosedRangeWithRetainedDecimalPlaces() {
		final BigDecimalNumberRange original =
			BigDecimalNumberRange.between(new BigDecimal("1.50"), new BigDecimal("9.90"), 2);

		final BigDecimalNumberRange deserialized = roundTrip(original);

		assertEquals(new BigDecimal("1.50"), deserialized.getPreciseFrom());
		assertEquals(new BigDecimal("9.90"), deserialized.getPreciseTo());
		assertEquals(2, deserialized.getRetainedDecimalPlaces());
		assertEquals(original.getFrom(), deserialized.getFrom());
		assertEquals(original.getTo(), deserialized.getTo());
	}

	@Test
	@DisplayName("keeps the closed-range on-disk byte layout unchanged and reads it back")
	void shouldPreserveClosedRangeByteLayout() {
		final BigDecimalNumberRange original =
			BigDecimalNumberRange.between(new BigDecimal("1.50"), new BigDecimal("9.90"), 2);

		final byte[] actual = serialize(original);
		assertArrayEquals(
			CLOSED_RANGE_GOLDEN, actual,
			"closed-range bytes must stay identical to the pre-fix on-disk format"
		);

		try (final Input input = new Input(CLOSED_RANGE_GOLDEN)) {
			final BigDecimalNumberRange deserialized =
				this.serializer.read(this.kryo, input, BigDecimalNumberRange.class);
			assertEquals(new BigDecimal("1.50"), deserialized.getPreciseFrom());
			assertEquals(new BigDecimal("9.90"), deserialized.getPreciseTo());
			assertEquals(2, deserialized.getRetainedDecimalPlaces());
		}
	}
}
