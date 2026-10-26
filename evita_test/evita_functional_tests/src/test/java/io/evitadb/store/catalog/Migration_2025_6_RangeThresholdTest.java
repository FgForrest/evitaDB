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

package io.evitadb.store.catalog;

import com.carrotsearch.hppc.IntIntHashMap;
import com.carrotsearch.hppc.IntIntMap;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.index.range.RangePoint;
import io.evitadb.index.range.TransactionalRangePoint;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.index.serializer.StoragePartSerializerTestSupport;
import io.evitadb.store.shared.kryo.KryoFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Map;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The referenced-id rewrite must preserve the thresholds converted by the versioned 2025.5 reader. */
@DisplayName("2025.5 range conversion survives the referenced-id rewrite")
@Tag(STORAGE)
@Tag(ATTRIBUTE)
class Migration_2025_6_RangeThresholdTest {
	/** The serial-version-uid release 2025.5 shipped for `FilterIndexStoragePart`, which routes the legacy reader. */
	private static final long FILTER_2025_5_UID = -3363238752052021735L;

	/** The typed reader converts once, and the migration only remaps the record ids. */
	@Test
	@DisplayName("converts legacy inline thresholds exactly once while remapping record ids")
	void shouldPreserveReaderConvertedThresholdsWhileRemappingRecordIds() {
		final ReadWriteKeyCompressor compressor = new ReadWriteKeyCompressor(Map.of());
		final Kryo kryo = KryoFactory.createKryo(new IndexStoragePartConfigurer(compressor));
		final AttributeIndexKey key = new AttributeIndexKey(null, "validity", null);
		final byte[] bytes;
		try (final Output output = new Output(4096)) {
			output.writeLong(FILTER_2025_5_UID);
			output.writeInt(7);
			output.writeVarLong(3L, true);
			output.writeVarInt(compressor.getId(key), true);
			kryo.writeClass(output, DateTimeRange.class);
			output.writeInt(0);
			output.writeBoolean(true);
			kryo.writeObject(output, new RangeIndex(new TransactionalRangePoint[]{
				new TransactionalRangePoint(Long.MIN_VALUE),
				new TransactionalRangePoint(10L, new int[]{1}, new int[0]),
				new TransactionalRangePoint(20L, new int[0], new int[]{1}),
				new TransactionalRangePoint(Long.MAX_VALUE)
			}));
			bytes = output.toBytes();
		}
		final FilterIndexStoragePart decoded = StoragePartSerializerTestSupport.decode(kryo, bytes,
			FilterIndexStoragePart.class);
		final OffsetIndexStoragePartPersistenceService parts = mock(OffsetIndexStoragePartPersistenceService.class);
		when(parts.getReadOnlyKeyCompressor()).thenReturn(compressor);
		when(parts.getStoragePart(anyLong(), anyLong(), eq(FilterIndexStoragePart.class))).thenReturn(decoded);
		final IntIntMap recordIds = new IntIntHashMap();
		recordIds.put(1, 101);

		Migration_2025_6.migrateFilterIndex(1L, 7, new AttributeIndexStorageKey(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, "brand"),
			AttributeIndexType.FILTER, key), parts, recordIds);

		final ArgumentCaptor<FilterIndexStoragePart> rewritten = ArgumentCaptor.forClass(FilterIndexStoragePart.class);
		verify(parts).putStoragePart(eq(1L), rewritten.capture());
		final FilterIndexStoragePart current = StoragePartSerializerTestSupport.roundTrip(kryo, rewritten.getValue(),
			FilterIndexStoragePart.class);
		assertEquals(DateTimeRange.class, current.getAttributeType());
		assertArrayEquals(new long[]{Long.MIN_VALUE, 10_000L, 20_000L, Long.MAX_VALUE},
			thresholds(current.getRangeIndex()));
		assertArrayEquals(new int[]{101}, current.getRangeIndex().getRecordsEnvelopingInclusive(15_000L)
			.compute().getArray());
	}

	/** Reads the numbers themselves to catch accidental repeated multiplication. */
	private static long[] thresholds(RangeIndex index) {
		return Arrays.stream(index.getRanges()).mapToLong(RangePoint::getThreshold).toArray();
	}
}
