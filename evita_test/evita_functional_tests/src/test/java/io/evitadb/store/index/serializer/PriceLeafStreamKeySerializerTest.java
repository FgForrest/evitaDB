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
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.PriceLeafStreamKey;
import io.evitadb.store.catalog.CatalogHeaderKryoConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.store.shared.kryo.SharedClassesConfigurer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.util.Currency;

import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link PriceLeafStreamKeySerializer} and {@link PriceLeafStreamKey}'s identity/ordering contract — the
 * per-super-price-index page-stream key of the granular price layout. The key must survive the catalog-header Kryo
 * round-trip (it rides in the persisted {@code KeyCompressor} dictionary) and must have a restart-stable, deterministic
 * `equals`/`hashCode`/`compareTo` so the compressor assigns one stable, collision-free id per distinct super price
 * index. Unlike the FilterIndex sibling `LeafStreamKey`, a super price index owns exactly one page stream, so the key
 * carries no stream-kind discriminator and identity alone is unique.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Price leaf stream key serializer")
@Tag(STORAGE)
@Tag(SERIALIZATION)
class PriceLeafStreamKeySerializerTest {

	private Kryo kryo;
	private PriceLeafStreamKeySerializer serializer;

	/**
	 * Builds a price list / currency identity for the given attributes.
	 *
	 * @param priceList      the price list name
	 * @param currencyCode   the ISO 4217 currency code
	 * @param recordHandling the inner-record handling mode
	 * @return the price index key
	 */
	@Nonnull
	private static PriceIndexKey priceIndexKey(
		@Nonnull String priceList,
		@Nonnull String currencyCode,
		@Nonnull PriceInnerRecordHandling recordHandling
	) {
		return new PriceIndexKey(priceList, Currency.getInstance(currencyCode), recordHandling);
	}

	@BeforeEach
	void setUp() {
		this.serializer = new PriceLeafStreamKeySerializer();
		// mirror the production catalog-header Kryo: the delegated PriceIndexKey serializer writes the
		// PriceInnerRecordHandling enum, which is registered by SharedClassesConfigurer, not by the header configurer
		this.kryo = KryoFactory.createKryo(
			SharedClassesConfigurer.INSTANCE.andThen(CatalogHeaderKryoConfigurer.INSTANCE)
		);
	}

	/**
	 * Round-trips a key directly through the serializer under test (no serial-version prefix).
	 *
	 * @param key the key to round-trip
	 * @return the deserialized key
	 */
	@Nonnull
	private PriceLeafStreamKey roundTrip(@Nonnull PriceLeafStreamKey key) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(256);
		try (final Output output = new Output(os, 256)) {
			this.serializer.write(this.kryo, output, key);
		}
		try (final Input input = new Input(os.toByteArray())) {
			return this.serializer.read(this.kryo, input, PriceLeafStreamKey.class);
		}
	}

	@Nested
	@DisplayName("Round-trip")
	class RoundTrip {

		@Test
		@DisplayName("round-trips a key through the serializer under test")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldRoundTripViaSerializer() {
			final PriceLeafStreamKey key = new PriceLeafStreamKey(
				42, priceIndexKey("basic", "EUR", PriceInnerRecordHandling.NONE)
			);
			assertEquals(key, roundTrip(key), "Key must survive the serializer round-trip.");
		}

		@Test
		@DisplayName("round-trips a key whose identity carries inner-record handling")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldRoundTripKeyWithInnerRecordHandling() {
			final PriceLeafStreamKey key = new PriceLeafStreamKey(
				7, priceIndexKey("vip", "USD", PriceInnerRecordHandling.LOWEST_PRICE)
			);
			assertEquals(key, roundTrip(key), "Key must survive the serializer round-trip.");
		}

		@Test
		@DisplayName("round-trips through the registered header Kryo")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldRoundTripViaRegisteredKryo() {
			final PriceLeafStreamKey key = new PriceLeafStreamKey(
				9, priceIndexKey("reference", "CZK", PriceInnerRecordHandling.SUM)
			);
			final ByteArrayOutputStream os = new ByteArrayOutputStream(256);
			try (final Output output = new Output(os, 256)) {
				PriceLeafStreamKeySerializerTest.this.kryo.writeObject(output, key);
			}
			final PriceLeafStreamKey deserialized;
			try (final Input input = new Input(os.toByteArray())) {
				deserialized = PriceLeafStreamKeySerializerTest.this.kryo.readObject(input, PriceLeafStreamKey.class);
			}
			assertEquals(key, deserialized, "Key must survive the registered-Kryo round-trip.");
		}
	}

	@Nested
	@DisplayName("Identity and ordering")
	class IdentityAndOrdering {

		@Test
		@DisplayName("keys with the same identity are equal (so the compressor assigns one stable id)")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldTreatSameIdentityAsEqual() {
			final PriceLeafStreamKey a = new PriceLeafStreamKey(
				1, priceIndexKey("basic", "EUR", PriceInnerRecordHandling.NONE)
			);
			final PriceLeafStreamKey b = new PriceLeafStreamKey(
				1, priceIndexKey("basic", "EUR", PriceInnerRecordHandling.NONE)
			);
			assertEquals(a, b, "Same identity must be equal.");
			assertEquals(a.hashCode(), b.hashCode(), "Equal keys must share a hash code.");
			assertEquals(0, a.compareTo(b), "Equal keys must compare equal.");
		}

		@Test
		@DisplayName("the same price index in different entity indexes is a different stream")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldDistinguishByEntityIndexPrimaryKey() {
			final PriceIndexKey priceIndexKey = priceIndexKey("basic", "EUR", PriceInnerRecordHandling.NONE);
			final PriceLeafStreamKey inIndex1 = new PriceLeafStreamKey(1, priceIndexKey);
			final PriceLeafStreamKey inIndex2 = new PriceLeafStreamKey(2, priceIndexKey);
			assertNotEquals(inIndex1, inIndex2, "Same price index in different entity indexes must be distinct streams.");
			assertTrue(inIndex1.compareTo(inIndex2) < 0, "Ordering must sort by entity index pk first.");
		}

		@Test
		@DisplayName("different price lists in the same entity index are different streams")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldDistinguishByPriceList() {
			final PriceLeafStreamKey basic = new PriceLeafStreamKey(
				1, priceIndexKey("basic", "EUR", PriceInnerRecordHandling.NONE)
			);
			final PriceLeafStreamKey vip = new PriceLeafStreamKey(
				1, priceIndexKey("vip", "EUR", PriceInnerRecordHandling.NONE)
			);
			assertNotEquals(basic, vip, "Different price lists must be distinct streams.");
			assertTrue(basic.compareTo(vip) != 0, "Different price lists must not compare equal.");
		}

		@Test
		@DisplayName("different currencies in the same entity index are different streams")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldDistinguishByCurrency() {
			final PriceLeafStreamKey eur = new PriceLeafStreamKey(
				1, priceIndexKey("basic", "EUR", PriceInnerRecordHandling.NONE)
			);
			final PriceLeafStreamKey usd = new PriceLeafStreamKey(
				1, priceIndexKey("basic", "USD", PriceInnerRecordHandling.NONE)
			);
			assertNotEquals(eur, usd, "Different currencies must be distinct streams.");
			assertTrue(eur.compareTo(usd) != 0, "Different currencies must not compare equal.");
		}
	}
}
