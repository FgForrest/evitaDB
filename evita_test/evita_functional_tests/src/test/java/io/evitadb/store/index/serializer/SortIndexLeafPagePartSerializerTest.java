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
import io.evitadb.index.attribute.SortIndex.ComparableArray;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeKeyWithIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexLeafPagePart;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.io.Serializable;
import java.util.Collections;

import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@link SortIndexLeafPagePartSerializer} — the Kryo (de)serialization of one granular leaf page of an
 * OWNER-mode sort index value tree. A write-path page carries its sub-index `(entityIndexPrimaryKey, attributeKey)`
 * identity and resolves the `streamId` store-side through the {@link ReadWriteKeyCompressor} when its primary key is
 * assigned; the serializer then writes the resolved `(streamId, pageSequence)` pair, the comparator-base length and the
 * leaf's buckets, recomputing the pack-derived primary key on read. Unlike the FILTER leaf serializer, the (possibly
 * compound) bucket value is unwrapped component-by-component (a {@code ComparableArray} is registered nowhere in Kryo).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Sort index leaf-page serializer")
@Tag(STORAGE)
@Tag(SERIALIZATION)
class SortIndexLeafPagePartSerializerTest {

	private static final AttributeIndexKey ATTRIBUTE_KEY = new AttributeIndexKey(null, "code", null);
	private static final AttributeKeyWithIndexType SORT_KEY =
		new AttributeKeyWithIndexType(ATTRIBUTE_KEY, AttributeIndexType.SORT);

	private Kryo kryo;
	private SortIndexLeafPagePartSerializer serializer;
	private ReadWriteKeyCompressor keyCompressor;

	@BeforeEach
	void setUp() {
		this.keyCompressor = new ReadWriteKeyCompressor(Collections.emptyMap());
		this.serializer = new SortIndexLeafPagePartSerializer();
		this.kryo = KryoFactory.createKryo(new IndexStoragePartConfigurer(this.keyCompressor));
	}

	/**
	 * Builds a write-path leaf page for the sub-index `(entityIndexPrimaryKey, SORT-typed attributeKey)` and resolves its
	 * primary key (hence its stream id) through the test compressor — exactly the store-side sequence the persistence
	 * service performs before writing.
	 */
	@Nonnull
	private SortIndexLeafPagePart page(
		int entityIndexPrimaryKey, int pageSequence, int comparatorBaseLength, @Nonnull ValueToRecordBitmap[] buckets
	) {
		final SortIndexLeafPagePart page = new SortIndexLeafPagePart(
			entityIndexPrimaryKey, SORT_KEY, pageSequence, buckets, comparatorBaseLength
		);
		page.computeUniquePartIdAndSet(this.keyCompressor);
		return page;
	}

	@Nonnull
	private byte[] serialize(@Nonnull SortIndexLeafPagePart part) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
		try (final Output output = new Output(os, 4_096)) {
			this.serializer.write(this.kryo, output, part);
		}
		return os.toByteArray();
	}

	@Nonnull
	private SortIndexLeafPagePart roundTrip(@Nonnull SortIndexLeafPagePart part) {
		try (final Input input = new Input(serialize(part))) {
			return this.serializer.read(this.kryo, input, SortIndexLeafPagePart.class);
		}
	}

	/**
	 * Asserts the two leaf pages hold the same stream id, page sequence, primary key, comparator-base length and buckets
	 * (each bucket's value and its full ascending record-id set — {@link ValueToRecordBitmap#equals} compares the value
	 * only, so the record ids are checked explicitly).
	 */
	private static void assertSamePage(@Nonnull SortIndexLeafPagePart expected, @Nonnull SortIndexLeafPagePart actual) {
		assertEquals(expected.getStreamId(), actual.getStreamId(), "Stream id must survive the round-trip.");
		assertEquals(expected.getPageSequence(), actual.getPageSequence(), "Page sequence must survive the round-trip.");
		assertEquals(expected.getStoragePartPK(), actual.getStoragePartPK(), "Primary key must survive the round-trip.");
		assertEquals(
			expected.getComparatorBaseLength(), actual.getComparatorBaseLength(),
			"Comparator-base length must survive the round-trip."
		);
		final ValueToRecordBitmap[] expectedBuckets = expected.getBuckets();
		final ValueToRecordBitmap[] actualBuckets = actual.getBuckets();
		assertEquals(expectedBuckets.length, actualBuckets.length, "Bucket count must survive the round-trip.");
		for (int i = 0; i < expectedBuckets.length; i++) {
			assertEquals(expectedBuckets[i].getValue(), actualBuckets[i].getValue(), "Bucket value " + i + " must match.");
			assertArrayEquals(
				expectedBuckets[i].getRecordIds().getArray(), actualBuckets[i].getRecordIds().getArray(),
				"Bucket record ids " + i + " must match."
			);
		}
	}

	@Nested
	@DisplayName("Scalar (single-component) round-trip")
	class Scalar {

		@Test
		@DisplayName("round-trips scalar String buckets with single-record and multi-record bitmaps")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldRoundTripScalarBuckets() {
			final ValueToRecordBitmap[] buckets = {
				new ValueToRecordBitmap("apple", 7),
				new ValueToRecordBitmap("banana", 1, 2, 3),
				new ValueToRecordBitmap("cherry", 300_000_000)
			};
			final SortIndexLeafPagePart page = page(42, 3, 1, buckets);
			assertSamePage(page, roundTrip(page));
		}

		@Test
		@DisplayName("round-trips an empty leaf page")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldRoundTripEmptyLeafPage() {
			final SortIndexLeafPagePart page = page(1, 0, 1, new ValueToRecordBitmap[0]);
			assertSamePage(page, roundTrip(page));
		}

		@Test
		@DisplayName("round-trips a scaled-Integer value (a sort-only BigDecimal attribute's normalized key)")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldRoundTripScaledIntegerValue() {
			// a sort-only BigDecimal attribute stores the scaled Integer the normalizer produced, not a BigDecimal
			final ValueToRecordBitmap[] buckets = {
				new ValueToRecordBitmap(150, 1, 2, 3)
			};
			final SortIndexLeafPagePart page = page(9, 1, 1, buckets);
			assertSamePage(page, roundTrip(page));
		}
	}

	@Nested
	@DisplayName("Compound (multi-component) round-trip")
	class Compound {

		@Test
		@DisplayName("round-trips compound ComparableArray buckets unwrapped component-by-component")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldRoundTripCompoundBuckets() {
			final ComparableArray first = new ComparableArray(new Serializable[]{"a", 1});
			final ComparableArray second = new ComparableArray(new Serializable[]{"b", 2});
			final ValueToRecordBitmap[] buckets = {
				new ValueToRecordBitmap(first, 4, 5),
				new ValueToRecordBitmap(second, 9)
			};
			final SortIndexLeafPagePart page = page(9, 2, 2, buckets);
			final SortIndexLeafPagePart deserialized = roundTrip(page);
			assertSamePage(page, deserialized);
			// the unwrapped components must rebuild an equal ComparableArray (element-wise equality)
			assertEquals(first, deserialized.getBuckets()[0].getValue(), "First compound value must rebuild equal.");
			assertEquals(second, deserialized.getBuckets()[1].getValue(), "Second compound value must rebuild equal.");
		}

		@Test
		@DisplayName("round-trips a compound bucket carrying a null component")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldRoundTripCompoundBucketWithNullComponent() {
			// a compound element may be null (NULLS_FIRST / NULLS_LAST handling); the component-wise codec must carry it
			final ComparableArray value = new ComparableArray(new Serializable[]{"a", null});
			final ValueToRecordBitmap[] buckets = {new ValueToRecordBitmap(value, 1, 2)};
			final SortIndexLeafPagePart page = page(3, 0, 2, buckets);
			final SortIndexLeafPagePart deserialized = roundTrip(page);
			assertEquals(value, deserialized.getBuckets()[0].getValue(), "Compound value with a null component must rebuild equal.");
		}
	}

	@Nested
	@DisplayName("Kryo registration")
	class Registration {

		@Test
		@DisplayName("round-trips through the registered index Kryo")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldRoundTripViaRegisteredKryo() {
			final ValueToRecordBitmap[] buckets = {
				new ValueToRecordBitmap("x", 1, 2),
				new ValueToRecordBitmap("y", 5)
			};
			final SortIndexLeafPagePart page = page(9, 2, 1, buckets);

			final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
			try (final Output output = new Output(os, 4_096)) {
				SortIndexLeafPagePartSerializerTest.this.kryo.writeObject(output, page);
			}
			final SortIndexLeafPagePart deserialized;
			try (final Input input = new Input(os.toByteArray())) {
				deserialized = SortIndexLeafPagePartSerializerTest.this.kryo.readObject(input, SortIndexLeafPagePart.class);
			}
			assertSamePage(page, deserialized);
		}
	}
}
