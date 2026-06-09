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
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexStoragePart;
import io.evitadb.store.entity.serializer.EnumNameSerializer;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.io.Serializable;
import java.util.Collections;
import java.util.Map;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip coverage for {@link SortIndexStoragePartSerializer}, focused on the slim format: a sort index that is
 * also filterable (view mode) re-derives its distinct values and per-value cardinalities from the shared
 * {@link io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart}, so its storage part omits
 * both sections behind a single boolean marker. Owner-mode parts (sort-only / compound) still carry them. The legacy
 * always-present layout is read by {@code SortIndexStoragePartSerializer_2026_1} (exercised by the end-to-end
 * backward-compatibility test on real old catalogs).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("SortIndexStoragePartSerializer round-trip (slim view-mode format)")
@Tag(STORAGE)
@Tag(INDEXING)
@Tag(SERIALIZATION)
class SortIndexStoragePartSerializerTest {

	private Kryo kryo;
	private SortIndexStoragePartSerializer serializer;

	@BeforeEach
	void setUp() {
		final ReadWriteKeyCompressor keyCompressor = new ReadWriteKeyCompressor(Collections.emptyMap());
		this.serializer = new SortIndexStoragePartSerializer(keyCompressor);
		this.kryo = KryoFactory.createKryo(new IndexStoragePartConfigurer(keyCompressor));
		// the comparator base persists OrderDirection / OrderBehaviour enums, registered by the schema configurer in
		// production; register them on this isolated test Kryo (one instance for both write and read, so ids are stable)
		this.kryo.register(OrderDirection.class, new EnumNameSerializer<>());
		this.kryo.register(OrderBehaviour.class, new EnumNameSerializer<>());
	}

	@Nonnull
	private byte[] serialize(@Nonnull SortIndexStoragePart part) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
		try (final Output output = new Output(os, 4_096)) {
			this.serializer.write(this.kryo, output, part);
		}
		return os.toByteArray();
	}

	@Nonnull
	private SortIndexStoragePart roundTrip(@Nonnull SortIndexStoragePart part) {
		try (final Input input = new Input(serialize(part))) {
			return this.serializer.read(this.kryo, input, SortIndexStoragePart.class);
		}
	}

	@Nonnull
	private static ComparatorSource[] singleStringAscending() {
		return new ComparatorSource[]{
			new ComparatorSource(String.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
		};
	}

	@Nonnull
	private static SortIndexStoragePart ownerPart() {
		final Map<Serializable, Integer> cardinalities = Map.of("apple", 2);
		return new SortIndexStoragePart(
			42, new AttributeIndexKey(null, "code", null), singleStringAscending(),
			new int[]{3, 1, 2}, new Serializable[]{"apple", "banana"}, cardinalities, 1L
		);
	}

	@Nonnull
	private static SortIndexStoragePart viewPart() {
		// slim view-mode part: same sortedRecords, but no distinct values / cardinalities
		return new SortIndexStoragePart(
			42, new AttributeIndexKey(null, "code", null), singleStringAscending(),
			new int[]{3, 1, 2}, new Serializable[0], Map.of(), 1L
		);
	}

	@Test
	@DisplayName("round-trips an owner-mode part carrying values + cardinalities")
	void shouldRoundTripOwnerModePart() {
		final SortIndexStoragePart deserialized = roundTrip(ownerPart());

		assertEquals(42, deserialized.getEntityIndexPrimaryKey());
		assertEquals(new AttributeIndexKey(null, "code", null), deserialized.getAttributeIndexKey());
		assertArrayEquals(new int[]{3, 1, 2}, deserialized.getSortedRecords());
		assertArrayEquals(new Serializable[]{"apple", "banana"}, deserialized.getSortedRecordsValues());
		assertEquals(1, deserialized.getValueCardinalities().size());
		assertEquals(2, deserialized.getValueCardinalities().get("apple"));
	}

	@Test
	@DisplayName("round-trips a slim view-mode part (no values / cardinalities)")
	void shouldRoundTripSlimViewModePart() {
		final SortIndexStoragePart deserialized = roundTrip(viewPart());

		assertEquals(42, deserialized.getEntityIndexPrimaryKey());
		assertArrayEquals(new int[]{3, 1, 2}, deserialized.getSortedRecords());
		assertEquals(0, deserialized.getSortedRecordsValues().length, "view-mode values must round-trip empty");
		assertTrue(deserialized.getValueCardinalities().isEmpty(), "view-mode cardinalities must round-trip empty");
	}

	@Test
	@DisplayName("the slim view-mode part is physically smaller than the equivalent owner-mode part")
	void shouldOmitValueSectionsForViewMode() {
		assertTrue(
			serialize(viewPart()).length < serialize(ownerPart()).length,
			"the slim part must omit the values + cardinalities sections, not serialize them as empty"
		);
	}
}
