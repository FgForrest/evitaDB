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
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.invertedIndex.InvertedIndex;
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
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Comparator;
import java.util.function.Function;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Migration coverage for `2026.1` filter indexes built over a `LocalDateTime` attribute.
 *
 * `2026.1` had no `LocalDateTime` branch in `FilterIndex.getNormalizer`, so it persisted the raw wall-clock value as
 * the bucket key. `2026.2` normalizes such an attribute to a UTC `Instant` so the B+ tree can store it as epoch-millis
 * in a single-`long` column — which means a legacy blob read verbatim would be fed `LocalDateTime` keys into a column
 * that hard-casts to `Instant`, and the catalog would fail to load with a `ClassCastException`.
 * {@link FilterIndexStoragePartSerializer_2026_1} therefore re-anchors those values on read.
 *
 * The rehydration assertion is the one that matters: it is the exact call `AttributeIndexLoader` makes, and it is what
 * fails without the conversion.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("2026.1 LocalDateTime filter index migrates to the UTC instant key space")
@Tag(STORAGE)
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@Tag(SERIALIZATION)
class FilterIndexLegacyLocalDateTimeSerializerTest {
	private static final AttributeIndexKey ATTRIBUTE_KEY = new AttributeIndexKey(null, "initialPublishedDate", null);
	private static final LocalDateTime FIRST = LocalDateTime.of(2026, 5, 20, 12, 19, 26);
	private static final LocalDateTime SECOND = LocalDateTime.of(2026, 5, 20, 14, 19, 26);
	private static final LocalDateTime THIRD = LocalDateTime.of(2026, 5, 21, 8, 5, 0);

	private Kryo kryo;
	private FilterIndexStoragePartSerializer_2026_1 legacySerializer;

	@BeforeEach
	void setUp() {
		final ReadWriteKeyCompressor keyCompressor = new ReadWriteKeyCompressor(Collections.emptyMap());
		this.kryo = KryoFactory.createKryo(new IndexStoragePartConfigurer(keyCompressor));
		this.legacySerializer = new FilterIndexStoragePartSerializer_2026_1(keyCompressor);
	}

	/**
	 * Round-trips a part through the legacy serializer, mimicking a blob written by `2026.1`.
	 */
	@Nonnull
	private FilterIndexStoragePart roundTrip(@Nonnull Class<?> attributeType, @Nonnull Serializable... values) {
		final ValueToRecordBitmap[] points = new ValueToRecordBitmap[values.length];
		for (int i = 0; i < values.length; i++) {
			points[i] = new ValueToRecordBitmap(values[i], i + 1);
		}
		final FilterIndexStoragePart legacyPart = new FilterIndexStoragePart(
			1, ATTRIBUTE_KEY, attributeType, points, null, 1L
		);

		final ByteArrayOutputStream buffer = new ByteArrayOutputStream(512);
		try (final Output output = new Output(buffer)) {
			this.legacySerializer.write(this.kryo, output, legacyPart);
		}
		try (final Input input = new Input(buffer.toByteArray())) {
			return this.legacySerializer.read(this.kryo, input, FilterIndexStoragePart.class);
		}
	}

	@Test
	@DisplayName("should re-anchor legacy LocalDateTime bucket keys at UTC on read")
	void shouldReAnchorLegacyLocalDateTimeKeys() {
		final FilterIndexStoragePart migrated = roundTrip(LocalDateTime.class, FIRST, SECOND, THIRD);

		final ValueToRecordBitmap[] points = migrated.getHistogramPoints();
		assertEquals(3, points.length);
		for (final ValueToRecordBitmap point : points) {
			assertInstanceOf(Instant.class, point.getValue());
		}
		assertEquals(FIRST.toInstant(ZoneOffset.UTC), points[0].getValue());
		assertEquals(SECOND.toInstant(ZoneOffset.UTC), points[1].getValue());
		assertEquals(THIRD.toInstant(ZoneOffset.UTC), points[2].getValue());
	}

	@Test
	@DisplayName("should rehydrate a migrated legacy part into an InvertedIndex and stay queryable")
	@SuppressWarnings({"unchecked", "rawtypes"})
	void shouldRehydrateMigratedLegacyPart() {
		final FilterIndexStoragePart migrated = roundTrip(LocalDateTime.class, FIRST, SECOND, THIRD);

		// exactly what AttributeIndexLoader#loadInvertedIndex does - this throws ClassCastException when the
		// legacy LocalDateTime keys are not re-anchored, because the tree keys the type by an Instant
		final InvertedIndex reloaded = new InvertedIndex(
			LocalDateTime.class,
			migrated.getHistogramPoints(),
			FilterIndex.getNormalizer(LocalDateTime.class, 0),
			(Comparator) Comparator.naturalOrder(),
			0
		);

		// `InvertedIndex#getRecordsEqualTo` takes an ALREADY-normalized probe (see its parameter name) - `FilterIndex`
		// applies the normalizer before delegating, so mirror that here. Passing a raw `LocalDateTime` would reach the
		// `Instant` column unconverted, which is a caller error rather than a migration defect
		final Function<Object, Serializable> normalizer = FilterIndex.getNormalizer(LocalDateTime.class, 0);
		assertArrayEquals(new int[]{1}, reloaded.getRecordsEqualTo(normalizer.apply(FIRST)).getArray());
		assertArrayEquals(new int[]{2}, reloaded.getRecordsEqualTo(normalizer.apply(SECOND)).getArray());
		assertArrayEquals(new int[]{3}, reloaded.getRecordsEqualTo(normalizer.apply(THIRD)).getArray());
	}

	@Test
	@DisplayName("should leave non-temporal legacy bucket keys untouched")
	void shouldLeaveOtherLegacyKeysUntouched() {
		final FilterIndexStoragePart migrated = roundTrip(String.class, "alpha", "beta");

		final ValueToRecordBitmap[] points = migrated.getHistogramPoints();
		assertEquals(2, points.length);
		assertEquals("alpha", points[0].getValue());
		assertEquals("beta", points[1].getValue());
	}

}
