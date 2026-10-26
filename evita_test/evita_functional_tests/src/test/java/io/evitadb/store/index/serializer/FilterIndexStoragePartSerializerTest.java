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
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.Locale;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Current-format roots and ordinary constructors require a concrete type. A versioned reader may create a transient
 * legacy carrier awaiting schema resolution, but the current writer refuses to persist that unresolved carrier.
 * These tests preserve the ordinary constructor's null refusal and the current wire's type and page metadata.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("FilterIndexStoragePartSerializer round-trip")
@Tag(STORAGE)
@Tag(INDEXING)
@Tag(SERIALIZATION)
class FilterIndexStoragePartSerializerTest {
	private Kryo kryo;
	private FilterIndexStoragePartSerializer serializer;

	/** Registers the current serializer against an isolated shared key dictionary. */
	@BeforeEach
	void setUp() {
		final ReadWriteKeyCompressor keyCompressor = new ReadWriteKeyCompressor(Collections.emptyMap());
		this.serializer = new FilterIndexStoragePartSerializer(keyCompressor);
		this.kryo = KryoFactory.createKryo(new IndexStoragePartConfigurer(keyCompressor));
	}

	/**
	 * Serializes the given storage part to bytes with the serializer directly, then deserializes it back. The same
	 * {@link ReadWriteKeyCompressor} instance backs both directions, so attribute keys round-trip by id.
	 *
	 */
	@Nonnull
	private FilterIndexStoragePart roundTrip(@Nonnull FilterIndexStoragePart storagePart) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
		try (final Output output = new Output(os, 4_096)) {
			this.serializer.write(this.kryo, output, storagePart);
		}
		try (final Input input = new Input(os.toByteArray())) {
			return this.serializer.read(this.kryo, input, FilterIndexStoragePart.class);
		}
	}

	/** Builds two inline buckets with the declared type and a stable persisted identity. */
	@Nonnull
	private static FilterIndexStoragePart part(
		@Nonnull AttributeIndexKey attributeIndexKey, @Nonnull Class<?> attributeType
	) {
		final ValueToRecordBitmap[] histogramPoints = {
			new ValueToRecordBitmap("apple", 1, 2),
			new ValueToRecordBitmap("banana", 3)
		};
		return new FilterIndexStoragePart(42, attributeIndexKey, attributeType, histogramPoints, null, 1L);
	}

	/** A current inline root preserves its declared type, identity and bucket postings. */
	@Test
	@DisplayName("round-trips a part with a concrete attributeType")
	void shouldRoundTripConcreteAttributeType() {
		final AttributeIndexKey key = new AttributeIndexKey(null, "code", null);
		final FilterIndexStoragePart deserialized = roundTrip(part(key, String.class));

		assertSame(String.class, deserialized.getAttributeType());
		assertEquals(42, deserialized.getEntityIndexPrimaryKey());
		assertEquals(key, deserialized.getAttributeIndexKey());
		assertEquals(2, deserialized.getHistogramPoints().length);
		assertEquals("apple", deserialized.getHistogramPoints()[0].getValue());
		assertArrayEquals(new int[]{1, 2}, deserialized.getHistogramPoints()[0].getRecordIds().getArray());
		assertEquals("banana", deserialized.getHistogramPoints()[1].getValue());
		assertArrayEquals(new int[]{3}, deserialized.getHistogramPoints()[1].getRecordIds().getArray());
	}

	/** Reference and locale identity survive dictionary compression together with the bucket postings. */
	@Test
	@DisplayName("round-trips a reference-scoped, localized part (exercises the key compressor)")
	void shouldRoundTripReferenceScopedLocalizedKey() {
		final AttributeIndexKey key = new AttributeIndexKey("BRAND", "name", Locale.ENGLISH);
		final FilterIndexStoragePart deserialized = roundTrip(part(key, String.class));

		assertSame(String.class, deserialized.getAttributeType());
		assertEquals(key, deserialized.getAttributeIndexKey());
		assertEquals(2, deserialized.getHistogramPoints().length);
		assertArrayEquals(new int[]{1, 2}, deserialized.getHistogramPoints()[0].getRecordIds().getArray());
	}

	/** The current inline wire retains integer bucket keys and the decimal scale defining their values. */
	@Test
	@DisplayName("round-trips scaled integer decimal keys together with the frozen scale")
	void shouldRoundTripScaledBigDecimalPart() {
		final AttributeIndexKey key = new AttributeIndexKey(null, "price", null);
		final ValueToRecordBitmap[] histogramPoints = {new ValueToRecordBitmap(150, 1)};
		// 150 == 1.50 at the frozen scale of 2 — the scale must survive the round-trip so the reloaded index keeps it
		final FilterIndexStoragePart part =
			new FilterIndexStoragePart(42, key, BigDecimal.class, histogramPoints, null, 2, 1L);

		final FilterIndexStoragePart deserialized = roundTrip(part);

		assertSame(BigDecimal.class, deserialized.getAttributeType());
		assertEquals(1, deserialized.getHistogramPoints().length);
		assertEquals(150, deserialized.getHistogramPoints()[0].getValue());
		assertEquals(2, deserialized.getIndexedDecimalPlaces(), "The frozen indexedDecimalPlaces must round-trip");
	}

	/** An inline root keeps all buckets in the root and advertises no external bucket pages. */
	@Test
	@DisplayName("a SINGLE part round-trips as not paged with an empty leaf-page list")
	void shouldRoundTripSinglePartAsNotPaged() {
		final AttributeIndexKey key = new AttributeIndexKey(null, "code", null);
		final FilterIndexStoragePart deserialized = roundTrip(part(key, String.class));

		assertFalse(deserialized.isPaged(), "A legacy/single part must not be paged.");
		assertEquals(0, deserialized.getLeafPageSequences().length, "A single part carries no leaf pages.");
		assertEquals(2, deserialized.getHistogramPoints().length, "A single part carries its buckets inline.");
	}

	/** A paged root preserves its ordered live pages and allocation high-water without embedding buckets. */
	@Test
	@DisplayName("a PAGED part round-trips its high-water and ordered leaf pages without inline buckets")
	void shouldRoundTripPagedPart() {
		final AttributeIndexKey key = new AttributeIndexKey("BRAND", "name", Locale.ENGLISH);
		final int[] leafPageSequences = {0, 1, 4, 5, 9};
		final FilterIndexStoragePart paged = FilterIndexStoragePart.paged(
			42, key, String.class, null, 0, 9, leafPageSequences, 1L
		);

		final FilterIndexStoragePart deserialized = roundTrip(paged);

		assertTrue(deserialized.isPaged(), "The part must round-trip as paged.");
		assertEquals(9, deserialized.getHighWaterPageSequence(), "High-water pageSequence must survive the round-trip.");
		assertArrayEquals(leafPageSequences, deserialized.getLeafPageSequences(),
			"Ordered leaf-page list must survive the round-trip.");
		assertEquals(0, deserialized.getHistogramPoints().length, "A paged part carries no inline buckets.");
		assertEquals(key, deserialized.getAttributeIndexKey());
		assertSame(String.class, deserialized.getAttributeType());
	}

	/** A paged decimal root retains both its numeric scale and page stream metadata. */
	@Test
	@DisplayName("a PAGED BigDecimal part round-trips the frozen scale alongside the page metadata")
	void shouldRoundTripPagedBigDecimalPart() {
		final AttributeIndexKey key = new AttributeIndexKey(null, "price", null);
		final FilterIndexStoragePart paged = FilterIndexStoragePart.paged(
			42, key, BigDecimal.class, null, 2, 0, new int[]{0}, 1L
		);

		final FilterIndexStoragePart deserialized = roundTrip(paged);

		assertTrue(deserialized.isPaged());
		assertEquals(2, deserialized.getIndexedDecimalPlaces(), "The frozen scale must round-trip on a paged part too.");
		assertEquals(0, deserialized.getHighWaterPageSequence());
		assertArrayEquals(new int[]{0}, deserialized.getLeafPageSequences());
	}

	/** Constructors reject a null type because support for the type-less 2024.5 format was dropped. */
	@Test
	@DisplayName("the constructor rejects a null attributeType (the dropped 2024.5 type-less format)")
	void shouldRejectNullAttributeTypeAtConstruction() {
		final AttributeIndexKey key = new AttributeIndexKey(null, "code", null);
		final ValueToRecordBitmap[] histogramPoints = {new ValueToRecordBitmap("apple", 1)};
		assertThrows(
			NullPointerException.class,
			() -> new FilterIndexStoragePart(42, key, null, histogramPoints, null, 1L),
			"a null attributeType must be rejected at construction — the type-less 2024.5 format is unsupported"
		);
	}
}
