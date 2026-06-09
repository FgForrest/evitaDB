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
import java.util.Collections;
import java.util.Locale;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Round-trip coverage for {@link FilterIndexStoragePartSerializer}. Since support for the type-less 2024.5 filter
 * format was dropped, the `attributeType` is a non-null invariant: a concrete type round-trips, and an attempt
 * to write a part with a null type fails fast at the write boundary rather than producing a part that cannot be read.
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
	 * @param storagePart the storage part to round-trip
	 * @return the deserialized copy
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

	@Nonnull
	private static FilterIndexStoragePart part(@Nonnull AttributeIndexKey attributeIndexKey, @Nonnull Class<?> attributeType) {
		final ValueToRecordBitmap[] histogramPoints = {
			new ValueToRecordBitmap("apple", 1, 2),
			new ValueToRecordBitmap("banana", 3)
		};
		return new FilterIndexStoragePart(42, attributeIndexKey, attributeType, histogramPoints, null, 1L);
	}

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
