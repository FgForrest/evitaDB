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
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.fulltext.FieldLengthTable;
import io.evitadb.index.fulltext.FieldLengthTable.LengthBlock;
import io.evitadb.index.fulltext.FieldLengthTable.LengthBlockEmission;
import io.evitadb.index.fulltext.FulltextIndex;
import io.evitadb.index.fulltext.FulltextIndex.DictionaryPage;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzerRegistry;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.invertedIndex.ValueToRecordPrimitive;
import io.evitadb.index.page.PageEmission;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AbstractLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextDictionaryLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextDictionaryLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextFieldLengthBlockPart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextFieldLengthBlockRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextIndexStoragePart.FieldEntry;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextLeafStreamKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextLeafStreamKey.StreamKind;
import io.evitadb.store.catalog.CatalogHeaderKryoConfigurer;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.evitadb.index.fulltext.FulltextFieldKey.associatedData;
import static io.evitadb.index.fulltext.FulltextFieldKey.attribute;
import static io.evitadb.index.fulltext.FulltextFieldKey.referenceAttribute;
import static io.evitadb.store.index.serializer.StoragePartSerializerTestSupport.roundTrip;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the persisted form of a fulltext index: the {@link FulltextIndexStoragePart} root, the
 * {@link FulltextDictionaryLeafPagePart} pages with the impacts appended to their buckets, the
 * {@link FulltextDictionaryLeafPageRemoval}, and the two compressed keys - each through the Kryo instance it is
 * registered in. The end-to-end group writes the pages a real {@link FulltextIndex} flush emits through the
 * serializers and loads the index back from the bytes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Fulltext index storage parts")
@Tag(STORAGE)
@Tag(SERIALIZATION)
class FulltextIndexStoragePartSerializerTest {

	private static final Locale CZECH = Locale.forLanguageTag("cs");
	private static final Locale ENGLISH = Locale.ENGLISH;

	/**
	 * Registry providing the real Czech index-slot analyzer for the end-to-end group.
	 */
	private static FulltextAnalyzerRegistry registry;

	private ReadWriteKeyCompressor keyCompressor;
	private Kryo indexKryo;
	private Kryo headerKryo;

	@BeforeAll
	static void setUpRegistry() {
		registry = new FulltextAnalyzerRegistry();
	}

	@AfterAll
	static void closeRegistry() {
		registry.close();
	}

	@BeforeEach
	void setUp() {
		this.keyCompressor = new ReadWriteKeyCompressor(Collections.emptyMap());
		this.indexKryo = KryoFactory.createKryo(new IndexStoragePartConfigurer(this.keyCompressor));
		this.headerKryo = KryoFactory.createKryo(CatalogHeaderKryoConfigurer.INSTANCE);
	}

	/**
	 * Builds a write-path dictionary page and resolves its primary key, hence its stream id, the way the persistence
	 * service does before writing it.
	 *
	 * @param entityIndexPrimaryKey the owning entity index
	 * @param locale                the index's locale
	 * @param pageSequence          the page sequence
	 * @param buckets               the buckets
	 * @param impacts               the impacts of each bucket
	 * @return the key-assigned page
	 */
	@Nonnull
	private FulltextDictionaryLeafPagePart page(
		int entityIndexPrimaryKey,
		@Nonnull Locale locale,
		int pageSequence,
		@Nonnull ValueToRecord[] buckets,
		@Nonnull byte[][] impacts
	) {
		final FulltextDictionaryLeafPagePart page = new FulltextDictionaryLeafPagePart(
			entityIndexPrimaryKey, locale, pageSequence, buckets, impacts
		);
		page.computeUniquePartIdAndSet(this.keyCompressor);
		return page;
	}

	/**
	 * Writes an object through the passed Kryo and reads it back as the passed type.
	 *
	 * @param kryo   the Kryo instance the type is registered in
	 * @param object the object
	 * @param type   the type to read
	 * @param <T>    the type
	 * @return the object read back
	 */
	@Nonnull
	private static <T> T roundTripObject(@Nonnull Kryo kryo, @Nonnull T object, @Nonnull Class<T> type) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
		try (final Output output = new Output(os, 4_096)) {
			kryo.writeObject(output, object);
		}
		try (final Input input = new Input(os.toByteArray())) {
			return kryo.readObject(input, type);
		}
	}

	/**
	 * Asserts two pages hold the same identity, buckets - in the same representation - and impacts.
	 *
	 * @param expected the written page
	 * @param actual   the page read back
	 */
	private static void assertSamePage(
		@Nonnull FulltextDictionaryLeafPagePart expected, @Nonnull FulltextDictionaryLeafPagePart actual
	) {
		assertEquals(expected.getStreamId(), actual.getStreamId(), "The stream id must survive.");
		assertEquals(expected.getPageSequence(), actual.getPageSequence(), "The page sequence must survive.");
		assertEquals(expected.getStoragePartPK(), actual.getStoragePartPK(), "The primary key must survive.");
		assertEquals(expected.getBuckets().length, actual.getBuckets().length, "The bucket count must survive.");
		for (int i = 0; i < expected.getBuckets().length; i++) {
			final ValueToRecord expectedBucket = expected.getBuckets()[i];
			final ValueToRecord actualBucket = actual.getBuckets()[i];
			assertSame(expectedBucket.getClass(), actualBucket.getClass(), "Bucket representation at " + i);
			assertEquals(expectedBucket.getValue(), actualBucket.getValue(), "Bucket key at " + i);
			assertArrayEquals(
				expectedBucket.getRecordIds().getArray(), actualBucket.getRecordIds().getArray(), "Postings at " + i
			);
			assertArrayEquals(expected.getImpacts()[i], actual.getImpacts()[i], "Impacts at " + i);
		}
	}

	@Nested
	@DisplayName("Dictionary leaf page")
	class DictionaryLeafPage {

		@Test
		@DisplayName("round-trips single- and multi-posting buckets with their impacts")
		void shouldRoundTripBucketsWithImpacts() {
			final FulltextDictionaryLeafPagePart page = page(
				7, CZECH, 3,
				new ValueToRecord[]{
					new ValueToRecordPrimitive("\u0000apple", 100),
					new ValueToRecordBitmap("\u0000banana", 200, 201, 202),
					// postings in three roaring containers, so a bitmap bucket's impacts are one flat run
					new ValueToRecordBitmap("\u0001cherry", 5, 70_000, 140_000)
				},
				new byte[][]{{(byte) 255}, {1, (byte) 128, 64}, {9, 8, 7}}
			);
			final FulltextDictionaryLeafPagePart read = roundTrip(
				FulltextIndexStoragePartSerializerTest.this.indexKryo, page, FulltextDictionaryLeafPagePart.class
			);
			assertSamePage(page, read);
			assertInstanceOf(ValueToRecordPrimitive.class, read.getBuckets()[0]);
			assertInstanceOf(ValueToRecordBitmap.class, read.getBuckets()[1]);
			assertNull(read.getEntityIndexPrimaryKey(), "A page read back carries no write-path identity.");
			assertNull(read.getLocale(), "A page read back carries no write-path identity.");
		}

		@Test
		@DisplayName("round-trips the empty page of an empty dictionary")
		void shouldRoundTripEmptyPage() {
			final FulltextDictionaryLeafPagePart page = page(1, CZECH, 0, new ValueToRecord[0], new byte[0][]);
			final Kryo kryo = FulltextIndexStoragePartSerializerTest.this.indexKryo;
			assertSamePage(page, roundTrip(kryo, page, FulltextDictionaryLeafPagePart.class));
		}

		@Test
		@DisplayName("costs exactly one byte per posting for the impacts")
		void shouldSpendOneBytePerPostingOnImpacts() {
			final ValueToRecord[] buckets = {
				new ValueToRecordPrimitive("\u0000a", 1),
				new ValueToRecordBitmap("\u0000b", 2, 3, 4)
			};
			final Kryo kryo = FulltextIndexStoragePartSerializerTest.this.indexKryo;
			final FulltextDictionaryLeafPagePart page = page(1, CZECH, 0, buckets, new byte[][]{{1}, {2, 3, 4}});
			final int pageSize = StoragePartSerializerTestSupport.encodeCurrent(kryo, page).length;

			final ByteArrayOutputStream os = new ByteArrayOutputStream(256);
			try (final Output output = new Output(os, 256)) {
				BucketLeafPagePartSerializer.writeBuckets(kryo, output, buckets);
			}
			final int bucketsSize = os.toByteArray().length;
			// the version uid, the stream id and the page sequence frame the payload: an empty page is the frame plus
			// its one-byte zero bucket count
			final int frameSize = StoragePartSerializerTestSupport.encodeCurrent(
				kryo, page(1, CZECH, 0, new ValueToRecord[0], new byte[0][])
			).length - 1;
			assertEquals(frameSize + bucketsSize + 4, pageSize, "Four postings must cost four impact bytes.");
		}

		@Test
		@DisplayName("refuses an impact column out of step with the buckets")
		void shouldRefuseMisalignedImpacts() {
			final ValueToRecord[] buckets = {
				new ValueToRecordPrimitive("\u0000a", 1),
				new ValueToRecordBitmap("\u0000b", 2, 3)
			};
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new FulltextDictionaryLeafPagePart(1, CZECH, 0, buckets, new byte[][]{{1}}),
				"A bucket without impacts must be refused."
			);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new FulltextDictionaryLeafPagePart(1, CZECH, 0, buckets, new byte[][]{{1}, {2}}),
				"A bucket with fewer impacts than postings must be refused."
			);
		}

		@Test
		@DisplayName("resolves a distinct stream per entity index and locale, and joins it with the page sequence")
		void shouldResolveDistinctStreams() {
			final ValueToRecord[] buckets = {new ValueToRecordPrimitive("\u0000a", 1)};
			final byte[][] impacts = {{1}};
			final FulltextDictionaryLeafPagePart page = page(1, CZECH, 5, buckets, impacts);
			final FulltextDictionaryLeafPagePart otherIndex = page(2, CZECH, 5, buckets, impacts);
			final FulltextDictionaryLeafPagePart otherLocale = page(1, ENGLISH, 5, buckets, impacts);

			assertEquals(
				Long.valueOf(AbstractLeafPagePart.computeUniquePartId(page.getStreamId(), 5)), page.getStoragePartPK()
			);
			assertNotEquals(page.getStreamId(), otherIndex.getStreamId(), "Entity indexes must not share a stream.");
			assertNotEquals(page.getStreamId(), otherLocale.getStreamId(), "Locales must not share a stream.");
			assertEquals(
				page.getStreamId(),
				FulltextIndexStoragePartSerializerTest.this.keyCompressor.getId(
					new FulltextLeafStreamKey(1, CZECH, StreamKind.DICTIONARY)
				),
				"The page must resolve the dictionary stream of its index."
			);
		}

		@Test
		@DisplayName("a removal resolves to the primary key its page was written under")
		void shouldResolveRemovalToThePageKey() {
			final FulltextDictionaryLeafPagePart page = page(
				4, CZECH, 9, new ValueToRecord[]{new ValueToRecordPrimitive("\u0000a", 1)}, new byte[][]{{1}}
			);
			final FulltextDictionaryLeafPageRemoval removal = new FulltextDictionaryLeafPageRemoval(4, CZECH, 9);
			assertNull(removal.getStoragePartPK());
			assertEquals(
				page.getStoragePartPK().longValue(),
				removal.computeUniquePartIdAndSet(FulltextIndexStoragePartSerializerTest.this.keyCompressor)
			);
			assertEquals(page.getStoragePartPK(), removal.getStoragePartPK());
			assertSame(FulltextDictionaryLeafPagePart.class, removal.removedContainerType());
		}

	}

	@Nested
	@DisplayName("Field length block page")
	class FieldLengthBlockPage {

		/**
		 * Builds a write-path block page and resolves its primary key.
		 *
		 * @param fieldId the field id
		 * @param block   the block
		 * @return the key-assigned page
		 */
		@Nonnull
		private FulltextFieldLengthBlockPart blockPage(int fieldId, @Nonnull LengthBlock block) {
			final FulltextFieldLengthBlockPart page = new FulltextFieldLengthBlockPart(7, CZECH, fieldId, block);
			page.computeUniquePartIdAndSet(FulltextIndexStoragePartSerializerTest.this.keyCompressor);
			return page;
		}

		/**
		 * Builds a block of the passed entity count, the entities spread evenly over the block.
		 *
		 * @param blockKey the block key
		 * @param count    the entity count
		 * @return the block
		 */
		@Nonnull
		private static LengthBlock block(int blockKey, int count) {
			final char[] lows = new char[count];
			final byte[] lengths = new byte[count];
			final int step = (1 << 16) / count;
			for (int i = 0; i < count; i++) {
				lows[i] = (char) (i * step);
				lengths[i] = (byte) (1 + i % 255);
			}
			return new LengthBlock(blockKey, lows, lengths);
		}

		/**
		 * Asserts a block page survives the round-trip.
		 *
		 * @param page the written page
		 * @return the size of the page in bytes
		 */
		private int assertRoundTrips(@Nonnull FulltextFieldLengthBlockPart page) {
			final Kryo kryo = FulltextIndexStoragePartSerializerTest.this.indexKryo;
			final byte[] bytes = StoragePartSerializerTestSupport.encodeCurrent(kryo, page);
			final FulltextFieldLengthBlockPart read = StoragePartSerializerTestSupport.decode(
				kryo, bytes, FulltextFieldLengthBlockPart.class
			);
			assertEquals(page.getStreamId(), read.getStreamId());
			assertEquals(page.getPageSequence(), read.getPageSequence());
			assertEquals(page.getStoragePartPK(), read.getStoragePartPK());
			assertEquals(page.getFieldId(), read.getFieldId());
			assertEquals(page.getBlock().blockKey(), read.getBlock().blockKey());
			assertArrayEquals(page.getBlock().lows(), read.getBlock().lows());
			assertArrayEquals(page.getBlock().lengths(), read.getBlock().lengths());
			return bytes.length;
		}

		@Test
		@DisplayName("round-trips a sparse block at about three bytes per entity")
		void shouldRoundTripASparseBlock() {
			final int size = assertRoundTrips(blockPage(3, block(0x7FFF, 1_000)));
			assertTrue(size < 3_100, "A sparse block of 1,000 entities must stay near 3 bytes each: " + size);
		}

		@Test
		@DisplayName("round-trips a block past a third of its slots in the slot encoding")
		void shouldRoundTripABlockInTheSlotEncoding() {
			final int size = assertRoundTrips(blockPage(0, block(4, 30_000)));
			assertTrue(size > 65_536 && size < 65_600, "The slot encoding costs one byte per slot: " + size);
		}

		@Test
		@DisplayName("round-trips the blocks either side of the encoding threshold")
		void shouldRoundTripTheBlocksAroundTheThreshold() {
			assertRoundTrips(blockPage(1, block(2, 21_845)));
			assertRoundTrips(blockPage(1, block(2, 21_846)));
		}

		@Test
		@DisplayName("packs the field id and block key into the page sequence, and a removal resolves to its key")
		void shouldPackFieldAndBlockAndResolveTheRemoval() {
			final FulltextFieldLengthBlockPart page = blockPage(5, block(9, 3));
			assertEquals(5 << 16 | 9, page.getPageSequence());
			assertEquals(5, page.getFieldId());
			assertEquals(
				FulltextIndexStoragePartSerializerTest.this.keyCompressor.getId(
					new FulltextLeafStreamKey(7, CZECH, StreamKind.FIELD_LENGTHS)
				),
				page.getStreamId(),
				"The page must resolve the length stream of its index."
			);
			assertNotEquals(
				page.getStoragePartPK(), blockPage(6, block(9, 3)).getStoragePartPK(),
				"The same block of another field is another page."
			);
			final FulltextFieldLengthBlockRemoval removal = new FulltextFieldLengthBlockRemoval(7, CZECH, 5, 9);
			assertEquals(
				page.getStoragePartPK().longValue(),
				removal.computeUniquePartIdAndSet(FulltextIndexStoragePartSerializerTest.this.keyCompressor)
			);
			assertSame(FulltextFieldLengthBlockPart.class, removal.removedContainerType());
		}

		@Test
		@DisplayName("refuses a field id that does not fit the page sequence")
		void shouldRefuseAnOversizedFieldId() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> FulltextFieldLengthBlockPart.pageSequenceOf(FulltextFieldLengthBlockPart.MAX_FIELD_ID + 1, 0)
			);
			assertThrows(GenericEvitaInternalError.class, () -> FulltextFieldLengthBlockPart.pageSequenceOf(-1, 0));
		}

	}

	@Nested
	@DisplayName("Root part")
	class Root {

		/**
		 * Builds a root with a field of every kind, two of them with length blocks and one retired - its key
		 * resolving to the attribute registered after it.
		 *
		 * @return the root
		 */
		@Nonnull
		private FulltextIndexStoragePart root() {
			return new FulltextIndexStoragePart(
				12, CZECH, "czech", 25.0,
				new FieldEntry[]{
					new FieldEntry(attribute("title"), 8.5, true, new int[]{0, 1, 14}),
					new FieldEntry(associatedData("body"), 400.0, false, new int[0]),
					new FieldEntry(referenceAttribute("brand", "title"), 25.0, false, new int[]{3}),
					new FieldEntry(attribute("title"), 25.0, false, new int[0])
				},
				17, new int[]{3, 17, 0, 9}, null
			);
		}

		@Test
		@DisplayName("round-trips identity, configuration, fields and the dictionary page list")
		void shouldRoundTripTheRoot() {
			final FulltextIndexStoragePart root = root();
			final FulltextIndexStoragePart read = roundTrip(
				FulltextIndexStoragePartSerializerTest.this.indexKryo, root, FulltextIndexStoragePart.class
			);
			assertEquals(12, read.getEntityIndexPrimaryKey());
			assertEquals(CZECH, read.getLocale());
			assertEquals("czech", read.getAnalyzerName());
			assertEquals(25.0, read.getDefaultLengthPivot());
			assertEquals(4, read.getFields().length);
			for (int i = 0; i < 4; i++) {
				assertEquals(root.getFields()[i].key(), read.getFields()[i].key());
				assertEquals(root.getFields()[i].retired(), read.getFields()[i].retired());
				assertEquals(root.getFields()[i].lengthPivot(), read.getFields()[i].lengthPivot());
				assertArrayEquals(root.getFields()[i].lengthBlocks(), read.getFields()[i].lengthBlocks());
			}
			assertEquals(17, read.getDictionaryHighWaterPageSequence());
			assertArrayEquals(new int[]{3, 17, 0, 9}, read.getDictionaryPageSequences());
			assertEquals(root.getStoragePartPK(), read.getStoragePartPK(), "The write assigned the key; it survives.");
		}

		@Test
		@DisplayName("is keyed by the entity index and the locale")
		void shouldKeyTheRootByEntityIndexAndLocale() {
			final ReadWriteKeyCompressor compressor = FulltextIndexStoragePartSerializerTest.this.keyCompressor;
			final long key = root().computeUniquePartIdAndSet(compressor);
			assertEquals(FulltextIndexStoragePart.computeUniquePartId(12, CZECH, compressor), key);
			assertNotEquals(key, FulltextIndexStoragePart.computeUniquePartId(13, CZECH, compressor));
			assertNotEquals(key, FulltextIndexStoragePart.computeUniquePartId(12, ENGLISH, compressor));
		}

		@Test
		@DisplayName("refuses an empty dictionary page list and unordered or oversized length blocks")
		void shouldRefuseMalformedRoots() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new FulltextIndexStoragePart(1, CZECH, "czech", 25.0, new FieldEntry[0], 0, new int[0], null)
			);
			assertThrows(
				GenericEvitaInternalError.class, () -> new FieldEntry(attribute("title"), 1.0, false, new int[]{2, 1})
			);
			assertThrows(
				GenericEvitaInternalError.class, () -> new FieldEntry(attribute("title"), 1.0, false, new int[]{1, 1})
			);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new FieldEntry(attribute("title"), 1.0, false, new int[]{0x10000})
			);
		}

	}

	@Nested
	@DisplayName("Compressed keys")
	class Keys {

		@Test
		@DisplayName("round-trip through the catalog header Kryo")
		void shouldRoundTripTheKeys() {
			final Kryo kryo = FulltextIndexStoragePartSerializerTest.this.headerKryo;
			final FulltextIndexKey rootKey = new FulltextIndexKey(CZECH);
			assertEquals(rootKey, roundTripObject(kryo, rootKey, FulltextIndexKey.class));
			for (final StreamKind kind : StreamKind.values()) {
				final FulltextLeafStreamKey streamKey = new FulltextLeafStreamKey(42, ENGLISH, kind);
				assertEquals(streamKey, roundTripObject(kryo, streamKey, FulltextLeafStreamKey.class));
			}
		}

		@Test
		@DisplayName("order stream keys by entity index, then locale, then kind")
		void shouldOrderStreamKeys() {
			final List<FulltextLeafStreamKey> keys = new ArrayList<>(List.of(
				new FulltextLeafStreamKey(2, CZECH, StreamKind.DICTIONARY),
				new FulltextLeafStreamKey(1, ENGLISH, StreamKind.DICTIONARY),
				new FulltextLeafStreamKey(1, CZECH, StreamKind.FIELD_LENGTHS),
				new FulltextLeafStreamKey(1, CZECH, StreamKind.DICTIONARY)
			));
			Collections.sort(keys);
			assertEquals(
				List.of(
					new FulltextLeafStreamKey(1, CZECH, StreamKind.DICTIONARY),
					new FulltextLeafStreamKey(1, CZECH, StreamKind.FIELD_LENGTHS),
					new FulltextLeafStreamKey(1, ENGLISH, StreamKind.DICTIONARY),
					new FulltextLeafStreamKey(2, CZECH, StreamKind.DICTIONARY)
				),
				keys
			);
			assertTrue(new FulltextIndexKey(CZECH).compareTo(new FulltextIndexKey(ENGLISH)) < 0);
		}

	}

	@Nested
	@DisplayName("End to end")
	class EndToEnd {

		@Test
		@DisplayName("a flushed index loads back from the serialized pages with every posting, impact and length")
		void shouldLoadAFlushedIndexBackFromTheBytes() {
			final FulltextIndex index = new FulltextIndex(registry.getIndexAnalyzer("product", CZECH));
			final int title = index.getOrAssignFieldId(attribute("title"));
			final int body = index.getOrAssignFieldId(attribute("body"), 300.0);
			// enough terms for several leaves, postings across roaring containers, impacts spanning the byte range
			for (int i = 0; i < 1_000; i++) {
				final String term = String.format("t%04d", i);
				for (int j = 0; j <= i % 5; j++) {
					final int primaryKey = j * 70_000 + i;
					index.addPosting(i % 3 == 0 ? body : title, term, primaryKey, 1 + (i * 7 + j * 31) % 255);
				}
			}
			index.addValue(attribute("title"), 5, "Rychlá hnědá liška");
			// lengths: a body block past a third of its slots, which takes the slot encoding, and sparse title blocks
			for (int primaryKey = 1; primaryKey <= 22_000; primaryKey++) {
				index.addValue(attribute("body"), primaryKey, "slovo " + "dlouhé ".repeat(primaryKey % 40));
			}
			for (int primaryKey = 140_000; primaryKey < 140_050; primaryKey++) {
				index.addValue(attribute("title"), primaryKey, "krátký titulek " + primaryKey);
			}

			final PageEmission<DictionaryPage> emission = index.collectChangedPages();
			final LengthBlockEmission[] lengthEmissions = index.collectChangedLengthBlocks();
			assertTrue(emission.orderedPageSequences().length > 1, "The dictionary must span several pages.");

			// write path: every page through the registered serializer, keyed the way the store keys it
			final Kryo kryo = FulltextIndexStoragePartSerializerTest.this.indexKryo;
			final Map<Integer, byte[]> disk = CollectionUtils.createHashMap(emission.changedPages().size());
			for (final DictionaryPage page : emission.changedPages()) {
				final FulltextDictionaryLeafPagePart part = page(
					3, CZECH, page.pageSequence(), page.buckets(), page.impacts()
				);
				disk.put(page.pageSequence(), StoragePartSerializerTestSupport.encodeCurrent(kryo, part));
			}
			final Map<Integer, byte[]> lengthDisk = CollectionUtils.createHashMap(8);
			for (int fieldId = 0; fieldId < lengthEmissions.length; fieldId++) {
				for (final LengthBlock block : lengthEmissions[fieldId].changedBlocks()) {
					final FulltextFieldLengthBlockPart part =
						new FulltextFieldLengthBlockPart(3, CZECH, fieldId, block);
					part.computeUniquePartIdAndSet(FulltextIndexStoragePartSerializerTest.this.keyCompressor);
					lengthDisk.put(part.getPageSequence(), StoragePartSerializerTestSupport.encodeCurrent(kryo, part));
				}
			}
			final FulltextIndexStoragePart root = roundTrip(
				kryo,
				new FulltextIndexStoragePart(
					3, CZECH, "czech", FulltextIndex.DEFAULT_LENGTH_PIVOT,
					new FieldEntry[]{
						new FieldEntry(
							attribute("title"), index.getLengthPivot(title), false, lengthEmissions[title].blockKeys()
						),
						new FieldEntry(
							attribute("body"), index.getLengthPivot(body), false, lengthEmissions[body].blockKeys()
						)
					},
					emission.highWaterPageSequence(), emission.orderedPageSequences(), null
				),
				FulltextIndexStoragePart.class
			);

			// read path: the root's page list, each page decoded from its bytes
			final int[] pageSequences = root.getDictionaryPageSequences();
			final DictionaryPage[] pages = new DictionaryPage[pageSequences.length];
			for (int i = 0; i < pageSequences.length; i++) {
				final FulltextDictionaryLeafPagePart part = StoragePartSerializerTestSupport.decode(
					kryo, disk.get(pageSequences[i]), FulltextDictionaryLeafPagePart.class
				);
				pages[i] = new DictionaryPage(part.getPageSequence(), part.getBuckets(), part.getImpacts());
			}
			final List<FulltextIndex.Field> fields = new ArrayList<>(2);
			for (int fieldId = 0; fieldId < root.getFields().length; fieldId++) {
				final FieldEntry entry = root.getFields()[fieldId];
				final LengthBlock[] blocks = new LengthBlock[entry.lengthBlocks().length];
				for (int i = 0; i < blocks.length; i++) {
					final FulltextFieldLengthBlockPart part = StoragePartSerializerTestSupport.decode(
						kryo,
						lengthDisk.get(FulltextFieldLengthBlockPart.pageSequenceOf(fieldId, entry.lengthBlocks()[i])),
						FulltextFieldLengthBlockPart.class
					);
					assertEquals(fieldId, part.getFieldId());
					blocks[i] = part.getBlock();
				}
				fields.add(
					new FulltextIndex.Field(
						entry.key(), entry.lengthPivot(), entry.retired(), FieldLengthTable.fromPersistedBlocks(blocks)
					)
				);
			}
			final FulltextIndex reloaded = FulltextIndex.fromPersistedPages(
				registry.getIndexAnalyzer("product", CZECH), root.getDefaultLengthPivot(), fields,
				pageSequences, pages, root.getDictionaryHighWaterPageSequence()
			);

			assertEquals(index.getTermCount(), reloaded.getTermCount());
			assertEquals(300.0, reloaded.getLengthPivot(reloaded.getFieldId(attribute("body"))));
			for (final int fieldId : new int[]{title, body}) {
				assertEquals(terms(index, fieldId), terms(reloaded, fieldId), "Field " + fieldId + " must survive.");
			}
			assertArrayEquals(new int[]{0, 2}, root.getFields()[title].lengthBlocks());
			assertArrayEquals(new int[]{0}, root.getFields()[body].lengthBlocks());
			for (final int fieldId : new int[]{title, body}) {
				final FieldLengthTable expected = index.getFieldLengths(fieldId);
				final FieldLengthTable actual = reloaded.getFieldLengths(fieldId);
				assertEquals(expected.size(), actual.size(), "Field " + fieldId + " must keep every length.");
				for (int primaryKey = 0; primaryKey < 140_100; primaryKey++) {
					assertEquals(
						expected.getEncoded(primaryKey), actual.getEncoded(primaryKey), "Length of " + primaryKey
					);
				}
			}
			assertTrue(reloaded.collectChangedPages().changedPages().isEmpty(), "A reloaded index writes nothing.");
			for (final LengthBlockEmission lengths : reloaded.collectChangedLengthBlocks()) {
				assertTrue(lengths.changedBlocks().isEmpty(), "A reloaded length table writes nothing.");
			}
		}

		/**
		 * Lists every term of a field with its postings and impacts.
		 *
		 * @param index   the index
		 * @param fieldId the field
		 * @return one line per term
		 */
		@Nonnull
		private static List<String> terms(@Nonnull FulltextIndex index, int fieldId) {
			final List<String> lines = new ArrayList<>(1_024);
			index.forEachTerm(fieldId, "", (term, postings, impacts) -> {
				lines.add(term + " " + Arrays.toString(postings.getArray()) + " " + Arrays.toString(impacts.toArray()));
				return true;
			});
			assertFalse(lines.isEmpty(), "Every field of the fixture has terms.");
			return lines;
		}

	}

}
