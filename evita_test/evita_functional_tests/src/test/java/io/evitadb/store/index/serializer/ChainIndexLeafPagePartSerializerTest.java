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
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeKeyWithIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ChainIndexLeafPagePart;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.util.Collections;

import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@link ChainIndexLeafPagePartSerializer} — the Kryo (de)serialization of one granular leaf page of a
 * {@link io.evitadb.index.attribute.ChainIndex} value tree. A write-path page carries its sub-index
 * `(entityIndexPrimaryKey, attributeKey)` identity and resolves the `streamId` store-side through the
 * {@link ReadWriteKeyCompressor} when its primary key is assigned; the serializer then writes the resolved
 * `(streamId, pageSequence)` pair and the non-derived page payload (ordered record ids, the head bitset and the aligned
 * head predecessor primary keys), recomputing the pack-derived primary key on read.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Chain index leaf-page serializer")
@Tag(STORAGE)
@Tag(SERIALIZATION)
class ChainIndexLeafPagePartSerializerTest {

	private static final AttributeIndexKey ATTRIBUTE_KEY = new AttributeIndexKey(null, "order", null);
	private static final AttributeKeyWithIndexType CHAIN_KEY =
		new AttributeKeyWithIndexType(ATTRIBUTE_KEY, AttributeIndexType.CHAIN);

	private Kryo kryo;
	private ChainIndexLeafPagePartSerializer serializer;
	private ReadWriteKeyCompressor keyCompressor;

	@BeforeEach
	void setUp() {
		this.keyCompressor = new ReadWriteKeyCompressor(Collections.emptyMap());
		this.serializer = new ChainIndexLeafPagePartSerializer();
		this.kryo = KryoFactory.createKryo(new IndexStoragePartConfigurer(this.keyCompressor));
	}

	/**
	 * Builds a write-path leaf page for the sub-index `(entityIndexPrimaryKey, CHAIN-typed attributeKey)` and resolves
	 * its primary key (hence its stream id) through the test compressor — exactly the store-side sequence the
	 * persistence service performs before writing.
	 */
	@Nonnull
	private ChainIndexLeafPagePart page(
		int entityIndexPrimaryKey,
		int pageSequence,
		@Nonnull int[] recordIds,
		@Nonnull long[] headWords,
		@Nonnull int[] headPredecessorPks
	) {
		final ChainIndexLeafPagePart page = new ChainIndexLeafPagePart(
			entityIndexPrimaryKey, CHAIN_KEY, pageSequence, recordIds, headWords, headPredecessorPks
		);
		page.computeUniquePartIdAndSet(this.keyCompressor);
		return page;
	}

	/**
	 * Builds the `ceil(recordCount / 64)`-word head bitset with the given head positions set.
	 *
	 * @param recordCount   the number of records the page carries
	 * @param headPositions the positions (into the record array) that are chain heads
	 * @return the head bitset words
	 */
	@Nonnull
	private static long[] headWords(int recordCount, int... headPositions) {
		final long[] words = new long[(recordCount + 63) >>> 6];
		for (final int position : headPositions) {
			words[position >>> 6] |= 1L << (position & 63);
		}
		return words;
	}

	@Nonnull
	private byte[] serialize(@Nonnull ChainIndexLeafPagePart part) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
		try (final Output output = new Output(os, 4_096)) {
			this.serializer.write(this.kryo, output, part);
		}
		return os.toByteArray();
	}

	@Nonnull
	private ChainIndexLeafPagePart roundTrip(@Nonnull ChainIndexLeafPagePart part) {
		try (final Input input = new Input(serialize(part))) {
			return this.serializer.read(this.kryo, input, ChainIndexLeafPagePart.class);
		}
	}

	/**
	 * Asserts the two leaf pages hold the same stream id, page sequence, primary key, record ids, head bitset and head
	 * predecessor primary keys.
	 */
	private static void assertSamePage(
		@Nonnull ChainIndexLeafPagePart expected, @Nonnull ChainIndexLeafPagePart actual
	) {
		assertEquals(expected.getStreamId(), actual.getStreamId(), "Stream id must survive the round-trip.");
		assertEquals(expected.getPageSequence(), actual.getPageSequence(), "Page sequence must survive the round-trip.");
		assertEquals(expected.getStoragePartPK(), actual.getStoragePartPK(), "Primary key must survive the round-trip.");
		assertArrayEquals(expected.getRecordIds(), actual.getRecordIds(), "Record ids must survive the round-trip.");
		assertArrayEquals(expected.getHeadWords(), actual.getHeadWords(), "Head bitset must survive the round-trip.");
		assertArrayEquals(
			expected.getHeadPredecessorPks(), actual.getHeadPredecessorPks(),
			"Head predecessor primary keys must survive the round-trip."
		);
	}

	@Test
	@DisplayName("round-trips a page with a few heads and their aligned predecessors")
	@Tag(STORAGE)
	@Tag(SERIALIZATION)
	void shouldRoundTripPageWithHeads() {
		// five records in tree order; positions 0 and 3 are chain heads, with predecessors HEAD_PK-like sentinels
		final int[] recordIds = {100, 101, 102, 200, 201};
		final long[] heads = headWords(recordIds.length, 0, 3);
		// aligned with the ascending set bits {0, 3}: head 100 points at -1 (a head sentinel), head 200 at external 99
		final int[] headPredecessorPks = {-1, 99};
		final ChainIndexLeafPagePart page = page(42, 3, recordIds, heads, headPredecessorPks);
		assertSamePage(page, roundTrip(page));
	}

	@Test
	@DisplayName("round-trips an empty leaf page")
	@Tag(STORAGE)
	@Tag(SERIALIZATION)
	void shouldRoundTripEmptyLeafPage() {
		final ChainIndexLeafPagePart page = page(1, 0, new int[0], headWords(0), new int[0]);
		assertSamePage(page, roundTrip(page));
	}

	@Test
	@DisplayName("round-trips a page spanning more than one head-bitset word")
	@Tag(STORAGE)
	@Tag(SERIALIZATION)
	void shouldRoundTripMultiWordHeadBitset() {
		// 130 records => a 3-word head bitset; mark heads straddling the 64-bit word boundaries
		final int recordCount = 130;
		final int[] recordIds = new int[recordCount];
		for (int i = 0; i < recordCount; i++) {
			recordIds[i] = 1_000 + i;
		}
		final long[] heads = headWords(recordCount, 0, 63, 64, 65, 127, 129);
		// six ascending head positions => six aligned predecessors
		final int[] headPredecessorPks = {-1, 500, 501, 502, 503, 504};
		final ChainIndexLeafPagePart page = page(7, 5, recordIds, heads, headPredecessorPks);
		final ChainIndexLeafPagePart deserialized = roundTrip(page);
		assertSamePage(page, deserialized);
		assertEquals(3, deserialized.getHeadWords().length, "130 records must reload a 3-word head bitset");
	}

	@Test
	@DisplayName("round-trips through the registered index Kryo")
	@Tag(STORAGE)
	@Tag(SERIALIZATION)
	void shouldRoundTripViaRegisteredKryo() {
		final int[] recordIds = {5, 6, 7};
		final long[] heads = headWords(recordIds.length, 0);
		final int[] headPredecessorPks = {-1};
		final ChainIndexLeafPagePart page = page(9, 2, recordIds, heads, headPredecessorPks);

		final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
		try (final Output output = new Output(os, 4_096)) {
			this.kryo.writeObject(output, page);
		}
		final ChainIndexLeafPagePart deserialized;
		try (final Input input = new Input(os.toByteArray())) {
			deserialized = this.kryo.readObject(input, ChainIndexLeafPagePart.class);
		}
		assertSamePage(page, deserialized);
	}
}
