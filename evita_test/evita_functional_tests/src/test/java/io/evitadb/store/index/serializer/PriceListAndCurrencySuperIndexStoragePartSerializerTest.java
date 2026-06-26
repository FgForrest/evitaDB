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
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.index.price.model.priceRecord.PriceRecordInnerRecordSpecific;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.PriceListAndCurrencySuperIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.PriceListAndCurrencySuperIndexStoragePart;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.index.serializer.util.PriceRecordCodec;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip and backward-compatibility coverage for {@link PriceListAndCurrencySuperIndexStoragePartSerializer} (the
 * `SINGLE`/`PAGED` discriminator appended after the inline records), the granular
 * {@link PriceListAndCurrencySuperIndexLeafPagePartSerializer} (one persisted leaf page) and the preserved
 * {@link PriceListAndCurrencySuperIndexStoragePartSerializer_2026_1} (the released 2026.1 pre-paged inline format).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("PriceListAndCurrencySuperIndexStoragePart / leaf-page serializer round-trip")
@Tag(STORAGE)
@Tag(PRICE)
@Tag(SERIALIZATION)
class PriceListAndCurrencySuperIndexStoragePartSerializerTest {
	private static final PriceIndexKey PRICE_INDEX_KEY = new PriceIndexKey(
		"basic", Currency.getInstance("EUR"), PriceInnerRecordHandling.NONE
	);
	/** The released 2026.1 serial-version-uid of the super-index part (kept registered for the pre-paged reader). */
	private static final long LEGACY_2026_1_UID = -7553613939380658772L;

	private Kryo kryo;
	private ReadWriteKeyCompressor keyCompressor;

	@BeforeEach
	void setUp() {
		this.keyCompressor = new ReadWriteKeyCompressor(Collections.emptyMap());
		this.kryo = KryoFactory.createKryo(new IndexStoragePartConfigurer(this.keyCompressor));
	}

	/**
	 * Builds a mixed price-record array — alternating compact {@link PriceRecord} and inner-record-specific
	 * {@link PriceRecordInnerRecordSpecific} entries — in ascending internal-price-id order.
	 *
	 * @param count the number of records
	 * @return the record array
	 */
	@Nonnull
	private static PriceRecordContract[] records(int count) {
		final PriceRecordContract[] records = new PriceRecordContract[count];
		for (int i = 0; i < count; i++) {
			final int ipId = i + 1;
			records[i] = i % 2 == 0
				? new PriceRecord(ipId, ipId + 1000, ipId / 3, ipId * 100 + 21, ipId * 100)
				: new PriceRecordInnerRecordSpecific(ipId, ipId + 1000, ipId / 3, ipId % 7, ipId * 100 + 21, ipId * 100);
		}
		return records;
	}

	@Nested
	@DisplayName("Super-index root part")
	class RootPart {

		@Test
		@DisplayName("round-trips a SINGLE part with inline records (trailing paged=false)")
		void shouldRoundTripSinglePart() {
			final PriceListAndCurrencySuperIndexStoragePart original = new PriceListAndCurrencySuperIndexStoragePart(
				42, PRICE_INDEX_KEY, new RangeIndex(), records(5), 7L
			);

			final PriceListAndCurrencySuperIndexStoragePart deserialized = StoragePartSerializerTestSupport.roundTrip(
				PriceListAndCurrencySuperIndexStoragePartSerializerTest.this.kryo,
				original, PriceListAndCurrencySuperIndexStoragePart.class
			);

			assertEquals(original.getEntityIndexPrimaryKey(), deserialized.getEntityIndexPrimaryKey());
			assertEquals(original.getStoragePartPK(), deserialized.getStoragePartPK());
			assertEquals(original.getPriceIndexKey(), deserialized.getPriceIndexKey());
			assertFalse(deserialized.isPaged(), "a SINGLE part must read back non-paged");
			assertArrayEquals(original.getPriceRecords(), deserialized.getPriceRecords(), "inline records");
			assertEquals(0, deserialized.getLeafPageSequences().length, "a SINGLE part has no leaf pages");
		}

		@Test
		@DisplayName("round-trips a PAGED part (no inline records, high-water + leaf-page list)")
		void shouldRoundTripPagedPart() {
			final PriceListAndCurrencySuperIndexStoragePart original = PriceListAndCurrencySuperIndexStoragePart.paged(
				42, PRICE_INDEX_KEY, new RangeIndex(), 9, new int[]{0, 3, 7, 9}
			);
			original.setStoragePartPK(7L);

			final PriceListAndCurrencySuperIndexStoragePart deserialized = StoragePartSerializerTestSupport.roundTrip(
				PriceListAndCurrencySuperIndexStoragePartSerializerTest.this.kryo,
				original, PriceListAndCurrencySuperIndexStoragePart.class
			);

			assertEquals(original.getEntityIndexPrimaryKey(), deserialized.getEntityIndexPrimaryKey());
			assertEquals(original.getStoragePartPK(), deserialized.getStoragePartPK());
			assertEquals(original.getPriceIndexKey(), deserialized.getPriceIndexKey());
			assertTrue(deserialized.isPaged(), "a PAGED part must read back paged");
			assertEquals(9, deserialized.getHighWaterPageSequence(), "high-water");
			assertArrayEquals(new int[]{0, 3, 7, 9}, deserialized.getLeafPageSequences(), "leaf-page sequences");
			assertEquals(0, deserialized.getPriceRecords().length, "a PAGED part carries no inline records");
		}

		@Test
		@DisplayName("reads a released 2026.1 pre-paged inline blob as a SINGLE part")
		void shouldReadLegacyInlineBlobAsSingle() {
			final PriceListAndCurrencySuperIndexStoragePart original = new PriceListAndCurrencySuperIndexStoragePart(
				42, PRICE_INDEX_KEY, new RangeIndex(), records(4), 7L
			);

			final byte[] legacyBytes = encodeLegacyInlineBytes(original);

			final PriceListAndCurrencySuperIndexStoragePart deserialized = StoragePartSerializerTestSupport.decode(
				PriceListAndCurrencySuperIndexStoragePartSerializerTest.this.kryo,
				legacyBytes, PriceListAndCurrencySuperIndexStoragePart.class
			);

			assertEquals(original.getEntityIndexPrimaryKey(), deserialized.getEntityIndexPrimaryKey());
			assertEquals(original.getStoragePartPK(), deserialized.getStoragePartPK());
			assertEquals(original.getPriceIndexKey(), deserialized.getPriceIndexKey());
			assertFalse(deserialized.isPaged(), "a legacy inline blob must read back as a non-paged SINGLE part");
			assertArrayEquals(original.getPriceRecords(), deserialized.getPriceRecords(), "inline records");
			assertEquals(-1, deserialized.getHighWaterPageSequence(), "SINGLE high-water sentinel");
		}

		/**
		 * Hand-encodes the released 2026.1 pre-paged format (uid-prefixed): the inline price records with NO trailing
		 * `SINGLE`/`PAGED` discriminator, mirroring the dropped 2026.1 writer's wire exactly so the production dispatcher
		 * routes it to the registered {@link PriceListAndCurrencySuperIndexStoragePartSerializer_2026_1}.
		 *
		 * @param part the storage part to encode in the legacy format
		 * @return the legacy-format bytes (uid-prefixed)
		 */
		@Nonnull
		private byte[] encodeLegacyInlineBytes(@Nonnull PriceListAndCurrencySuperIndexStoragePart part) {
			final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
			try (final Output output = new Output(os, 4_096)) {
				output.writeLong(LEGACY_2026_1_UID);
				output.writeInt(part.getEntityIndexPrimaryKey());
				output.writeVarLong(part.getStoragePartPK(), true);
				output.writeVarInt(
					PriceListAndCurrencySuperIndexStoragePartSerializerTest.this.keyCompressor.getId(
						part.getPriceIndexKey()
					), true
				);
				PriceListAndCurrencySuperIndexStoragePartSerializerTest.this.kryo.writeObject(
					output, part.getValidityIndex()
				);
				// the legacy format ends with the inline records — there is no trailing paged discriminator
				PriceRecordCodec.writePriceRecords(output, part.getPriceRecords());
			}
			return os.toByteArray();
		}
	}

	@Nested
	@DisplayName("Granular leaf page")
	class LeafPage {

		@Test
		@DisplayName("round-trips a leaf page (stream id + page sequence + records; PK recomputed on read)")
		void shouldRoundTripLeafPage() {
			final int streamId = 5;
			final int pageSequence = 3;
			final PriceListAndCurrencySuperIndexLeafPagePart original = new PriceListAndCurrencySuperIndexLeafPagePart(
				streamId, pageSequence, records(6),
				PriceListAndCurrencySuperIndexLeafPagePart.computeUniquePartId(streamId, pageSequence)
			);

			final PriceListAndCurrencySuperIndexLeafPagePart deserialized = StoragePartSerializerTestSupport.roundTrip(
				PriceListAndCurrencySuperIndexStoragePartSerializerTest.this.kryo,
				original, PriceListAndCurrencySuperIndexLeafPagePart.class
			);

			assertEquals(streamId, deserialized.getStreamId(), "stream id");
			assertEquals(pageSequence, deserialized.getPageSequence(), "page sequence");
			assertEquals(
				PriceListAndCurrencySuperIndexLeafPagePart.computeUniquePartId(streamId, pageSequence),
				deserialized.getStoragePartPK(), "recomputed PK"
			);
			assertArrayEquals(original.getPriceRecords(), deserialized.getPriceRecords(), "leaf records");
		}

		@Test
		@DisplayName("round-trips an empty leaf page")
		void shouldRoundTripEmptyLeafPage() {
			final PriceListAndCurrencySuperIndexLeafPagePart original = new PriceListAndCurrencySuperIndexLeafPagePart(
				1, 0, new PriceRecordContract[0],
				PriceListAndCurrencySuperIndexLeafPagePart.computeUniquePartId(1, 0)
			);

			final PriceListAndCurrencySuperIndexLeafPagePart deserialized = StoragePartSerializerTestSupport.roundTrip(
				PriceListAndCurrencySuperIndexStoragePartSerializerTest.this.kryo,
				original, PriceListAndCurrencySuperIndexLeafPagePart.class
			);

			assertEquals(1, deserialized.getStreamId());
			assertEquals(0, deserialized.getPageSequence());
			assertEquals(0, deserialized.getPriceRecords().length, "an empty leaf page reads back empty");
		}
	}

}
