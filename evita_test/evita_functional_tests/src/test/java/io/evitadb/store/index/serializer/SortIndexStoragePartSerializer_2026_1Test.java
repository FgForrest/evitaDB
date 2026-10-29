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
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.index.attribute.SortIndex.ComparableArray;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexStoragePart;
import io.evitadb.store.entity.EntityStoragePartConfigurer;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.schema.SchemaKryoConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.store.shared.kryo.SharedClassesConfigurer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Reads real 2026.1.20 sort-part bytes through production UID dispatch, preserving each released block identity.
 * The older attribute-key bridge is checked against the same payload layout under its own registered UID.
 */
@DisplayName("Inherited released sort parts retain positional block lengths")
@Tag(STORAGE)
@Tag(INDEXING)
@Tag(SERIALIZATION)
class SortIndexStoragePartSerializer_2026_1Test {
	/** Verifies unequal colliding blocks even when the sparse map was written in reverse value order. */
	@Test
	void shouldDecodeUnequalTwinsFromReleasedBytes() throws IOException {
		final SortIndexStoragePart part = decode("unequal", false);
		assertBlocks(part, new int[]{1, 2, 3, 4, 5}, new int[]{2, 3});
		assertEquals(
			OffsetDateTime.parse("2026-01-01T11:00:00+02:00"),
			((DateTimeRange) part.getSortedRecordsValues()[0]).getPreciseTo()
		);
		assertEquals(
			OffsetDateTime.parse("2026-01-01T10:00:00+01:00"),
			((DateTimeRange) part.getSortedRecordsValues()[1]).getPreciseTo()
		);
	}

	/** Verifies the implied singleton is attributed independently of its longer, today-equal twin. */
	@Test
	void shouldDecodeSingletonBeforeLongerTwinFromReleasedBytes() throws IOException {
		assertBlocks(decode("singleton", false), new int[]{10, 20, 21, 22}, new int[]{1, 3});
	}

	/** Verifies recursive range identity and null elements in compounds with unordered sparse entries. */
	@Test
	void shouldDecodeCompoundTwinsFromReleasedBytes() throws IOException {
		final SortIndexStoragePart part = decode("compound", false);
		assertBlocks(part, new int[]{4, 5, 7, 3, 9, 12}, new int[]{2, 1, 3});
		final ComparableArray firstTwin = (ComparableArray) part.getSortedRecordsValues()[1];
		final ComparableArray secondTwin = (ComparableArray) part.getSortedRecordsValues()[2];
		assertEquals(firstTwin, secondTwin, "the released compound blocks are equal under current equality");
	}

	/** Verifies a same-vintage control whose ranges remain distinct retains every record and block. */
	@Test
	void shouldDecodeNonCollidingReleasedControl() throws IOException {
		final SortIndexStoragePart part = decode("control", false);
		assertArrayEquals(new int[]{1, 2, 3, 4, 5}, part.getSortedRecords());
		assertEquals(
			Map.of(part.getSortedRecordsValues()[0], 2, part.getSortedRecordsValues()[1], 3),
			part.getValueCardinalities()
		);
	}

	/** Verifies the registered 2025.5 reader also preserves unordered compound cardinalities and its key bridge. */
	@Test
	void shouldDecodeOlderAttributeKeyLayoutWithoutFoldingTwins() throws IOException {
		assertBlocks(decode("compound", true), new int[]{4, 5, 7, 3, 9, 12}, new int[]{2, 1, 3});
	}

	/**
	 * Decodes a released resource, optionally adapting only its UID and compressed attribute-key dictionary.
	 *
	 * @param fixture the resource name
	 * @param olderAttributeKey whether to exercise the otherwise identical 2025.5 payload layout
	 * @return the part decoded by the production registry
	 * @throws IOException when the resource cannot be read
	 */
	@Nonnull
	private static SortIndexStoragePart decode(@Nonnull String fixture, boolean olderAttributeKey) throws IOException {
		final byte[] bytes;
		try (final InputStream input = Objects.requireNonNull(
			SortIndexStoragePartSerializer_2026_1Test.class.getResourceAsStream(
				"/testData/released_sort_2026_1/" + fixture + ".bin"
			),
			"Sort part `" + fixture + "` is not on the class path; the build writes it in `generate-test-resources`."
		)) {
			bytes = input.readAllBytes();
		}
		final ByteBuffer uid = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		assertEquals(-7076092972784353868L, uid.getLong(0), "the actual released UID");
		final ReadWriteKeyCompressor compressor = new ReadWriteKeyCompressor(Collections.emptyMap());
		if (olderAttributeKey) {
			uid.putLong(0, 6163295675316818632L);
			compressor.getId(new AttributeKey("validity"));
		} else {
			compressor.getId(new AttributeIndexKey(null, "validity", null));
		}
		final Kryo kryo = KryoFactory.createKryo(
			SchemaKryoConfigurer.INSTANCE.andThen(SharedClassesConfigurer.INSTANCE)
				.andThen(SchemaKryoConfigurer.INSTANCE).andThen(new IndexStoragePartConfigurer(compressor))
				.andThen(new EntityStoragePartConfigurer(compressor))
		);
		return StoragePartSerializerTestSupport.decode(kryo, bytes, SortIndexStoragePart.class);
	}

	/**
	 * Checks the precise positional contract consumed by the migration, including header identities.
	 *
	 * @param part the decoded part
	 * @param records the expected record column
	 * @param lengths the expected block lengths
	 */
	private static void assertBlocks(
		@Nonnull SortIndexStoragePart part, @Nonnull int[] records, @Nonnull int[] lengths
	) {
		assertArrayEquals(records, part.getSortedRecords());
		assertArrayEquals(lengths, part.getCardinalities());
		assertSame(part.getSortedRecordsValues(), part.getCardinalityValues());
		assertEquals(1, part.getEntityIndexPrimaryKey());
		assertEquals(7L, part.getStoragePartPK());
		assertEquals(new AttributeIndexKey(null, "validity", null), part.getAttributeIndexKey());
		assertEquals(0, part.getIndexedDecimalPlaces());
	}
}
