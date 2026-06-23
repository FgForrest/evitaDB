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

package io.evitadb.store.index.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.PriceListAndCurrencyRefIndexStoragePart;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.Currency;

import static io.evitadb.test.TestTags.PRICE;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Round-trip and lazy-upgrade coverage for {@link PriceListAndCurrencyRefIndexStoragePartSerializer} (the strictly
 * ascending price ids array delta-varint encoded via the asserting codec) and the preserved
 * {@link PriceListAndCurrencyRefIndexStoragePartSerializer_2026_2} (raw fixed-int format). Covers empty, single and
 * large-gap price id arrays.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("PriceListAndCurrencyRefIndexStoragePartSerializer round-trip (delta-varint price ids)")
@Tag(STORAGE)
@Tag(PRICE)
@Tag(SERIALIZATION)
class PriceListAndCurrencyRefIndexStoragePartSerializerTest {
	private static final PriceIndexKey PRICE_INDEX_KEY = new PriceIndexKey(
		"basic", Currency.getInstance("EUR"), PriceInnerRecordHandling.NONE
	);
	/** The pre-slimming serial-version-uid of {@link PriceListAndCurrencyRefIndexStoragePart} (kept registered). */
	private static final long LEGACY_2026_2_UID = -1687563151524978160L;

	private Kryo kryo;
	private ReadWriteKeyCompressor keyCompressor;

	@BeforeEach
	void setUp() {
		this.keyCompressor = new ReadWriteKeyCompressor(Collections.emptyMap());
		this.kryo = KryoFactory.createKryo(new IndexStoragePartConfigurer(this.keyCompressor));
	}

	/**
	 * Builds a price-ref part with the given strictly-ascending price ids.
	 *
	 * @param priceIds the ascending price ids
	 * @return the price-ref storage part
	 */
	@Nonnull
	private static PriceListAndCurrencyRefIndexStoragePart part(@Nonnull int[] priceIds) {
		return new PriceListAndCurrencyRefIndexStoragePart(
			42, PRICE_INDEX_KEY, new RangeIndex(), priceIds, 7L
		);
	}

	/**
	 * Asserts two price-ref parts are equivalent on the round-tripped fields.
	 *
	 * @param expected the original part
	 * @param actual   the reconstructed part
	 */
	private static void assertPartEquals(
		@Nonnull PriceListAndCurrencyRefIndexStoragePart expected,
		@Nonnull PriceListAndCurrencyRefIndexStoragePart actual
	) {
		assertEquals(expected.getEntityIndexPrimaryKey(), actual.getEntityIndexPrimaryKey());
		assertEquals(expected.getStoragePartPK(), actual.getStoragePartPK());
		assertEquals(expected.getPriceIndexKey(), actual.getPriceIndexKey());
		assertArrayEquals(expected.getPriceIds(), actual.getPriceIds(), "price ids");
	}

	@Nested
	@DisplayName("Production dispatch round-trip")
	class RoundTrip {

		@Test
		@DisplayName("round-trips a part with large-gap ascending price ids")
		void shouldRoundTripLargeGapPriceIds() {
			final PriceListAndCurrencyRefIndexStoragePart original = part(
				new int[]{1, 2, 1_000, 1_000_000, Integer.MAX_VALUE}
			);

			final PriceListAndCurrencyRefIndexStoragePart deserialized = StoragePartSerializerTestSupport.roundTrip(
				PriceListAndCurrencyRefIndexStoragePartSerializerTest.this.kryo,
				original, PriceListAndCurrencyRefIndexStoragePart.class
			);

			assertPartEquals(original, deserialized);
		}

		@Test
		@DisplayName("round-trips a single-element price ids array")
		void shouldRoundTripSinglePriceId() {
			final PriceListAndCurrencyRefIndexStoragePart original = part(new int[]{77});

			final PriceListAndCurrencyRefIndexStoragePart deserialized = StoragePartSerializerTestSupport.roundTrip(
				PriceListAndCurrencyRefIndexStoragePartSerializerTest.this.kryo,
				original, PriceListAndCurrencyRefIndexStoragePart.class
			);

			assertPartEquals(original, deserialized);
		}

		@Test
		@DisplayName("round-trips an empty price ids array")
		void shouldRoundTripEmptyPriceIds() {
			final PriceListAndCurrencyRefIndexStoragePart original = part(new int[0]);

			final PriceListAndCurrencyRefIndexStoragePart deserialized = StoragePartSerializerTestSupport.roundTrip(
				PriceListAndCurrencyRefIndexStoragePartSerializerTest.this.kryo,
				original, PriceListAndCurrencyRefIndexStoragePart.class
			);

			assertPartEquals(original, deserialized);
		}
	}

	@Nested
	@DisplayName("Lazy upgrade from the pre-slimming format")
	class LazyUpgrade {

		@Test
		@DisplayName("reads a pre-slimming raw-int blob through the dispatcher")
		void shouldReadPreSlimmingFormat() {
			final PriceListAndCurrencyRefIndexStoragePart original = part(
				new int[]{1, 2, 1_000, 1_000_000, Integer.MAX_VALUE}
			);

			// the legacy bytes MUST be encoded against the dispatcher's key compressor so the price-index-key id assigned
			// here resolves on read
			final byte[] legacyBytes = encodePreSlimmingBytes(original);

			final PriceListAndCurrencyRefIndexStoragePart deserialized = StoragePartSerializerTestSupport.decode(
				PriceListAndCurrencyRefIndexStoragePartSerializerTest.this.kryo,
				legacyBytes, PriceListAndCurrencyRefIndexStoragePart.class
			);

			assertPartEquals(original, deserialized);
		}

		/**
		 * Hand-encodes the pre-slimming 2026.2 raw-int format for the given part (uid-prefixed), mirroring the dropped
		 * 2026.2 writer's wire exactly so the production dispatcher routes it to the registered 2026.2 reader. The
		 * preserved {@link PriceListAndCurrencyRefIndexStoragePartSerializer_2026_2} is the frozen prior-production
		 * reader; its write path deliberately throws, so the legacy blob is reproduced here by hand.
		 *
		 * @param part the storage part to encode in the pre-slimming format
		 * @return the legacy-format bytes (uid-prefixed)
		 */
		@Nonnull
		private byte[] encodePreSlimmingBytes(@Nonnull PriceListAndCurrencyRefIndexStoragePart part) {
			final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
			try (final Output output = new Output(os, 4_096)) {
				output.writeLong(LEGACY_2026_2_UID);
				output.writeInt(part.getEntityIndexPrimaryKey());
				output.writeVarLong(part.getStoragePartPK(), true);
				output.writeVarInt(
					PriceListAndCurrencyRefIndexStoragePartSerializerTest.this.keyCompressor.getId(
						part.getPriceIndexKey()
					), true
				);

				PriceListAndCurrencyRefIndexStoragePartSerializerTest.this.kryo.writeObject(
					output, part.getValidityIndex()
				);

				final int[] triples = part.getPriceIds();
				output.writeInt(triples.length, true);
				output.writeInts(triples, 0, triples.length);
			}
			return os.toByteArray();
		}
	}
}
