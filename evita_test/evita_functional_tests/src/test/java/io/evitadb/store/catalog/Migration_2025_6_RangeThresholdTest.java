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
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.index.range.RangePoint;
import io.evitadb.index.range.TransactionalRangePoint;
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.index.serializer.StoragePartSerializerTestSupport;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The referenced-id rewrite must preserve the thresholds converted by the versioned 2025.5 reader.
 *
 * The legacy record is written into the storage of a real catalog and the migration step reads it from and writes
 * it back to that storage, so the test follows the real persistence path rather than a recording of its calls.
 */
@DisplayName("2025.5 range conversion survives the referenced-id rewrite")
@Tag(STORAGE)
@Tag(ATTRIBUTE)
class Migration_2025_6_RangeThresholdTest implements EvitaTestSupport {
	/** The serial-version-uid release 2025.5 shipped for `FilterIndexStoragePart`, which routes the legacy reader. */
	private static final long FILTER_2025_5_UID = -3363238752052021735L;
	/** Primary key of the reduced index the legacy record belongs to. */
	private static final int INDEX_PRIMARY_KEY = 7;

	/** The typed reader converts once, and the migration only remaps the record ids. */
	@Test
	@DisplayName("converts legacy inline thresholds exactly once while remapping record ids")
	void shouldPreserveReaderConvertedThresholdsWhileRemappingRecordIds() {
		final TestPaths paths = createTestPaths("Migration_2025_6_RangeThreshold");
		try (final Evita evita = new Evita(newTestEvitaConfigurationBuilder(paths).build())) {
			final AttributeIndexKey key = new AttributeIndexKey(null, "validity", null);
			evita.defineCatalog(TEST_CATALOG);
			// the filterable attribute and its stored value give the catalog's key compressor ids for the key
			evita.updateCatalog(TEST_CATALOG, session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withAttribute(key.attributeName(), DateTimeRange.class, AttributeSchemaEditor::filterable)
					.updateVia(session);
				session.upsertEntity(session.createNewEntity(Entities.PRODUCT, 1).setAttribute(
					key.attributeName(),
					DateTimeRange.since(OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC))
				));
			});
			final Catalog catalog = (Catalog) evita.getCatalogInstanceOrThrowException(TEST_CATALOG);
			final StoragePartPersistenceService<?> collectionParts =
				catalog.getCollectionForEntityOrThrowException(Entities.PRODUCT).getStoragePartPersistenceService();
			final OffsetIndexStoragePartPersistenceService parts =
				(OffsetIndexStoragePartPersistenceService) collectionParts;
			final KeyCompressor compressor = parts.getReadOnlyKeyCompressor();
			final Kryo kryo = KryoFactory.createKryo(new IndexStoragePartConfigurer(compressor));
			final long partId = AttributeIndexStoragePart.computeUniquePartId(
				INDEX_PRIMARY_KEY, AttributeIndexType.FILTER, key, compressor
			);
			final long version = catalog.getVersion();
			parts.putStoragePart(version, StoragePartSerializerTestSupport.decode(
				kryo, legacyFilterIndex(kryo, compressor, key, partId), FilterIndexStoragePart.class
			));
			final IntIntMap recordIds = new IntIntHashMap();
			recordIds.put(1, 101);

			Migration_2025_6.migrateFilterIndex(version, INDEX_PRIMARY_KEY, new AttributeIndexStorageKey(
				new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, "brand"),
				AttributeIndexType.FILTER, key), parts, recordIds);

			final FilterIndexStoragePart rewritten = parts.getStoragePart(
				version, partId, FilterIndexStoragePart.class
			);
			assertNotNull(rewritten);
			final FilterIndexStoragePart current = StoragePartSerializerTestSupport.roundTrip(
				kryo, rewritten, FilterIndexStoragePart.class
			);
			assertSame(DateTimeRange.class, current.getAttributeType());
			assertArrayEquals(new long[]{Long.MIN_VALUE, 10_000L, 20_000L, Long.MAX_VALUE},
				thresholds(current.getRangeIndex()));
			assertArrayEquals(new int[]{101}, current.getRangeIndex().getRecordsEnvelopingInclusive(15_000L)
				.compute().getArray());
		} finally {
			cleanupTestPaths(paths);
		}
	}

	/**
	 * Renders a filter index in the layout release 2025.5 wrote, with its range thresholds still inline.
	 *
	 * @param kryo kryo of the catalog's index storage parts
	 * @param compressor key compressor of the catalog the record is stored in
	 * @param key attribute the filter index belongs to
	 * @param partId unique part id the record is stored under
	 * @return serialized legacy record, stamped with the 2025.5 serial version uid
	 */
	private static byte[] legacyFilterIndex(
		Kryo kryo, KeyCompressor compressor, AttributeIndexKey key, long partId
	) {
		try (final Output output = new Output(4096)) {
			output.writeLong(FILTER_2025_5_UID);
			output.writeInt(INDEX_PRIMARY_KEY);
			output.writeVarLong(partId, true);
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
			return output.toBytes();
		}
	}

	/** Reads the numbers themselves to catch accidental repeated multiplication. */
	private static long[] thresholds(RangeIndex index) {
		return Arrays.stream(index.getRanges()).mapToLong(RangePoint::getThreshold).toArray();
	}
}
