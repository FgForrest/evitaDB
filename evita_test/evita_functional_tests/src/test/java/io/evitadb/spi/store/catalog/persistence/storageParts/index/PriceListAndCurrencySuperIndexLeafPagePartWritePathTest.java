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

package io.evitadb.spi.store.catalog.persistence.storageParts.index;

import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.utils.NumberUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Currency;
import java.util.HashMap;

import static io.evitadb.test.TestTags.PRICE;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Covers the store-side primary-key resolution of the granular super-price-index leaf-page parts —
 * {@link PriceListAndCurrencySuperIndexLeafPagePart} and {@link PriceListAndCurrencySuperIndexLeafPageRemoval}.
 *
 * The existing serializer coverage only exercises the read path (rehydrating an already-resolved `streamId`). The write
 * path is where the parts fold their `(entityIndexPrimaryKey, priceIndexKey)` sub-index identity into a single
 * {@code KeyCompressor} `streamId` and pack it with the page sequence into the storage-part primary key. The leaf page
 * and its removal must resolve to the SAME primary key against the same compressor so a freed page is removed under the
 * exact key it was written under.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Granular super-price-index leaf-page write-path PK resolution")
@Tag(STORAGE)
@Tag(PRICE)
class PriceListAndCurrencySuperIndexLeafPagePartWritePathTest {

	private static final PriceIndexKey PRICE_INDEX_KEY = new PriceIndexKey(
		"basic", Currency.getInstance("EUR"), PriceInnerRecordHandling.NONE
	);
	private static final int ENTITY_INDEX_PK = 42;

	/** The established hand-fake {@link io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor}. */
	private ReadWriteKeyCompressor keyCompressor;

	/**
	 * Builds a small price-record array in ascending internal-price-id order.
	 *
	 * @param count the number of records
	 * @return the record array
	 */
	@Nonnull
	private static PriceRecordContract[] records(int count) {
		final PriceRecordContract[] records = new PriceRecordContract[count];
		for (int i = 0; i < count; i++) {
			final int ipId = i + 1;
			records[i] = new PriceRecord(ipId, ipId + 1000, ipId / 3, ipId * 100 + 21, ipId * 100);
		}
		return records;
	}

	@BeforeEach
	void setUp() {
		this.keyCompressor = new ReadWriteKeyCompressor(new HashMap<>());
	}

	@Nested
	@DisplayName("Leaf page")
	class LeafPage {

		@Test
		@DisplayName("resolves the stream id from the sub-index identity and packs it with the page sequence")
		@Tag(STORAGE)
		@Tag(PRICE)
		void shouldResolveStreamIdFromSubIndexIdentity() {
			final int pageSequence = 3;
			final PriceListAndCurrencySuperIndexLeafPagePart page =
				new PriceListAndCurrencySuperIndexLeafPagePart(
					ENTITY_INDEX_PK, PRICE_INDEX_KEY, pageSequence, records(4)
				);

			final long resolvedPK = page.computeUniquePartIdAndSet(
				PriceListAndCurrencySuperIndexLeafPagePartWritePathTest.this.keyCompressor
			);

			// the stream id must be the compressor id of this sub-index's stream key, packed with the page sequence
			final int expectedStreamId = PriceListAndCurrencySuperIndexLeafPagePartWritePathTest.this.keyCompressor.getId(
				new PriceLeafStreamKey(ENTITY_INDEX_PK, PRICE_INDEX_KEY)
			);
			final long expectedPK = NumberUtils.join(expectedStreamId, pageSequence);

			assertEquals(expectedStreamId, page.getStreamId(), "stream id resolved from identity");
			assertEquals(expectedPK, resolvedPK, "PK packs the resolved stream id with the page sequence");
			assertEquals(expectedPK, page.getStoragePartPK(), "the resolved PK is cached on the part");
		}

		@Test
		@DisplayName("is idempotent across repeated resolutions")
		@Tag(STORAGE)
		@Tag(PRICE)
		void shouldBeIdempotentAcrossRepeatedResolutions() {
			final PriceListAndCurrencySuperIndexLeafPagePart page =
				new PriceListAndCurrencySuperIndexLeafPagePart(
					ENTITY_INDEX_PK, PRICE_INDEX_KEY, 5, records(2)
				);

			final long first = page.computeUniquePartIdAndSet(
				PriceListAndCurrencySuperIndexLeafPagePartWritePathTest.this.keyCompressor
			);
			final long second = page.computeUniquePartIdAndSet(
				PriceListAndCurrencySuperIndexLeafPagePartWritePathTest.this.keyCompressor
			);

			assertEquals(first, second, "a second resolution must return the same PK without throwing");
		}

		@Test
		@DisplayName("a read-path page returns its PK without consulting the compressor")
		@Tag(STORAGE)
		@Tag(PRICE)
		void shouldReturnPkWithoutCompressorForReadPathPage() {
			final int streamId = 11;
			final int pageSequence = 4;
			final long precomputedPK = PriceListAndCurrencySuperIndexLeafPagePart.computeUniquePartId(
				streamId, pageSequence
			);
			final PriceListAndCurrencySuperIndexLeafPagePart readPathPage =
				new PriceListAndCurrencySuperIndexLeafPagePart(streamId, pageSequence, records(3), precomputedPK);

			final int peakBefore = PriceListAndCurrencySuperIndexLeafPagePartWritePathTest.this.keyCompressor.getPeakId();
			final long resolvedPK = readPathPage.computeUniquePartIdAndSet(
				PriceListAndCurrencySuperIndexLeafPagePartWritePathTest.this.keyCompressor
			);
			final int peakAfter = PriceListAndCurrencySuperIndexLeafPagePartWritePathTest.this.keyCompressor.getPeakId();

			assertEquals(precomputedPK, resolvedPK, "an already-resolved page returns its precomputed PK");
			assertEquals(streamId, readPathPage.getStreamId(), "the already-resolved stream id is left untouched");
			assertEquals(peakBefore, peakAfter, "a read-path page must allocate no compressor id");
		}
	}

	@Nested
	@DisplayName("Leaf page removal")
	class LeafPageRemoval {

		@Test
		@DisplayName("resolves to the same PK as the leaf page it frees")
		@Tag(STORAGE)
		@Tag(PRICE)
		void shouldResolveToSamePkAsFreedLeafPage() {
			final int pageSequence = 7;
			final PriceListAndCurrencySuperIndexLeafPagePart page =
				new PriceListAndCurrencySuperIndexLeafPagePart(
					ENTITY_INDEX_PK, PRICE_INDEX_KEY, pageSequence, records(4)
				);
			final PriceListAndCurrencySuperIndexLeafPageRemoval removal =
				new PriceListAndCurrencySuperIndexLeafPageRemoval(ENTITY_INDEX_PK, PRICE_INDEX_KEY, pageSequence);

			// resolve the page first so the stream is registered, then the removal must target the very same key
			final long pagePK = page.computeUniquePartIdAndSet(
				PriceListAndCurrencySuperIndexLeafPagePartWritePathTest.this.keyCompressor
			);
			final long removalPK = removal.computeUniquePartIdAndSet(
				PriceListAndCurrencySuperIndexLeafPagePartWritePathTest.this.keyCompressor
			);

			assertEquals(pagePK, removalPK, "removal must target the same PK as the freed leaf page");
			assertEquals(pagePK, removal.getStoragePartPK(), "the resolved PK is cached on the removal");
		}

		@Test
		@DisplayName("removes the leaf-page container type")
		@Tag(STORAGE)
		@Tag(PRICE)
		void shouldRemoveLeafPageContainerType() {
			final PriceListAndCurrencySuperIndexLeafPageRemoval removal =
				new PriceListAndCurrencySuperIndexLeafPageRemoval(ENTITY_INDEX_PK, PRICE_INDEX_KEY, 1);

			assertSame(
				PriceListAndCurrencySuperIndexLeafPagePart.class, removal.removedContainerType(),
				"a removal must free the granular leaf-page container"
			);
		}
	}
}
