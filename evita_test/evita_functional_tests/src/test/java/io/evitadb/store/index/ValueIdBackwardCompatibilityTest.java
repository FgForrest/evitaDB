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

package io.evitadb.store.index;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.invertedIndex.ValueIdAllocator;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.invertedIndex.ValueToRecordPrimitive;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AbstractLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeKeyWithIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import io.evitadb.store.index.serializer.FilterIndexLeafPagePartSerializer;
import io.evitadb.store.index.serializer.FilterIndexStoragePartSerializer;
import io.evitadb.store.shared.kryo.KryoFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Collections;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that catalogs written **before** the shared value tree carried stable value ids still load — the storage
 * surface the value id column added is strictly appended, and both changed record types keep a backward-compatible
 * reader registered against the serial version they were written under.
 *
 * ## How the "old bytes" are produced
 *
 * There is no checked-in binary fixture. A legacy record is synthesized the way
 * `AcceleratorBackwardCompatibilityTest` synthesizes one: the pre-change serial version is written by hand,
 * followed by the payload rendered by the CURRENT serializer. That is a genuine old record precisely *because* the
 * new section is appended — the pre-change payload is a byte-exact prefix of the current one, so the registered
 * backward-compatible reader stops short of the trailing bytes and reproduces exactly what it would have read from
 * disk.
 *
 * The synthesis is therefore also the alignment witness: each test asserts that the field written immediately
 * **before** the appended section survives the round trip. If the value id section were ever inserted anywhere but at
 * the very end, that field would come back corrupted rather than merely absent.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Catalogs written before value ids still load")
@Tag(STORAGE)
@Tag(SERIALIZATION)
@Tag(ATTRIBUTE)
class ValueIdBackwardCompatibilityTest {

	/**
	 * `FilterIndexLeafPagePart.serialVersionUID` as shipped by 2026.2 — the shape without the parallel value id
	 * column. Registered against {@code FilterIndexLeafPagePartSerializer_2026_2}.
	 */
	private static final long LEAF_PAGE_PRE_VALUE_ID_UID = 8923174650293847561L;

	/**
	 * `FilterIndexStoragePart.serialVersionUID` as shipped by 2026.2 — the shape without the value id high-water mark
	 * and the inline id column. Registered against {@code FilterIndexStoragePartSerializer_2026_2}.
	 */
	private static final long ROOT_PART_PRE_VALUE_ID_UID = 3847290165472938104L;

	private Kryo kryo;
	private ReadWriteKeyCompressor keyCompressor;

	@BeforeEach
	void setUp() {
		this.keyCompressor = new ReadWriteKeyCompressor(Collections.emptyMap());
		this.kryo = KryoFactory.createKryo(new IndexStoragePartConfigurer(this.keyCompressor));
	}

	/**
	 * Renders `part` through `currentSerializer` behind a hand-written legacy serial version, then reads it back
	 * through the production Kryo dispatch — which routes it to the backward-compatible reader registered for that
	 * version.
	 *
	 * @param orphanedUid       the pre-change serial version the record is claimed to carry
	 * @param currentSerializer the current serializer, whose payload begins with the legacy payload verbatim
	 * @param part              the part to render
	 * @param type              the concrete storage-part class
	 * @param <T>               the storage-part type
	 * @return the part as the backward-compatible reader reconstructed it
	 */
	@Nonnull
	private <T> T readThroughBackwardCompatibleRoute(
		long orphanedUid,
		@Nonnull Serializer<T> currentSerializer,
		@Nonnull T part,
		@Nonnull Class<T> type
	) {
		final ByteArrayOutputStream baos = new ByteArrayOutputStream(1_024);
		try (final Output output = new Output(baos, 1_024)) {
			output.writeLong(orphanedUid);
			currentSerializer.write(this.kryo, output, part);
		}
		try (final Input input = new Input(new ByteArrayInputStream(baos.toByteArray()))) {
			return this.kryo.readObject(input, type);
		}
	}

	/**
	 * Builds a write-path leaf page and resolves its primary key (hence its stream id) through the test compressor,
	 * exactly as the persistence service does before writing.
	 *
	 * @param valueIds the id column to attach, or `null`
	 * @param buckets  the leaf's buckets
	 * @return the key-assigned write-path page
	 */
	@Nonnull
	private FilterIndexLeafPagePart leafPage(int[] valueIds, @Nonnull ValueToRecord... buckets) {
		final FilterIndexLeafPagePart page = new FilterIndexLeafPagePart(
			1,
			new AttributeKeyWithIndexType(null, "code", null, AttributeIndexType.FILTER),
			7,
			buckets,
			valueIds
		);
		page.computeUniquePartIdAndSet(this.keyCompressor);
		return page;
	}

	@Nested
	@DisplayName("The granular leaf page")
	class GranularLeafPage {

		@Test
		@DisplayName("a page written before value ids reads back with none, buckets intact")
		void shouldReadPreValueIdLeafPage() {
			final FilterIndexLeafPagePart page = leafPage(
				new int[]{11, 22},
				new ValueToRecordPrimitive("alpha", 100),
				new ValueToRecordBitmap("beta", 200, 201)
			);

			final FilterIndexLeafPagePart restored = readThroughBackwardCompatibleRoute(
				LEAF_PAGE_PRE_VALUE_ID_UID, new FilterIndexLeafPagePartSerializer(), page,
				FilterIndexLeafPagePart.class
			);

			assertNull(restored.getValueIds(), "A page written before value ids must come back carrying none.");
			// the buckets are the section written IMMEDIATELY BEFORE the appended ids — their survival is the
			// byte-alignment witness, not a lucky default
			assertEquals(2, restored.getBuckets().length);
			assertEquals("alpha", restored.getBuckets()[0].getValue());
			assertEquals(100, restored.getBuckets()[0].getRecordIds().getFirst());
			assertEquals("beta", restored.getBuckets()[1].getValue());
			assertArrayEquals(new int[]{200, 201}, restored.getBuckets()[1].getRecordIds().getArray());
			assertEquals(7, restored.getPageSequence());
			assertEquals(
				AbstractLeafPagePart.computeUniquePartId(restored.getStreamId(), 7), restored.getStoragePartPK()
			);
		}

		@Test
		@DisplayName("a page written today keeps its id column through the current reader")
		void shouldRoundTripValueIdColumn() {
			final FilterIndexLeafPagePart page = leafPage(
				new int[]{11, 22},
				new ValueToRecordPrimitive("alpha", 100),
				new ValueToRecordBitmap("beta", 200, 201)
			);

			final ByteArrayOutputStream baos = new ByteArrayOutputStream(1_024);
			try (final Output output = new Output(baos, 1_024)) {
				ValueIdBackwardCompatibilityTest.this.kryo.writeObject(output, page);
			}
			final FilterIndexLeafPagePart restored;
			try (final Input input = new Input(new ByteArrayInputStream(baos.toByteArray()))) {
				restored = ValueIdBackwardCompatibilityTest.this.kryo.readObject(input, FilterIndexLeafPagePart.class);
			}

			assertArrayEquals(new int[]{11, 22}, restored.getValueIds());
			assertEquals(2, restored.getBuckets().length);
		}

		@Test
		@DisplayName("a page with no id column round-trips as none rather than as an empty column")
		void shouldRoundTripAbsentValueIdColumn() {
			final FilterIndexLeafPagePart page = leafPage(null, new ValueToRecordPrimitive("alpha", 100));

			final ByteArrayOutputStream baos = new ByteArrayOutputStream(1_024);
			try (final Output output = new Output(baos, 1_024)) {
				ValueIdBackwardCompatibilityTest.this.kryo.writeObject(output, page);
			}
			final FilterIndexLeafPagePart restored;
			try (final Input input = new Input(new ByteArrayInputStream(baos.toByteArray()))) {
				restored = ValueIdBackwardCompatibilityTest.this.kryo.readObject(input, FilterIndexLeafPagePart.class);
			}

			assertNull(restored.getValueIds());
		}

		@Test
		@DisplayName("a page whose id column does not match its buckets never reaches the disk")
		void shouldRefuseLeafPageWhoseIdColumnDoesNotMatchItsBuckets() {
			final ValueToRecord[] buckets = {
				new ValueToRecordPrimitive("alpha", 100), new ValueToRecordBitmap("beta", 200, 201)
			};

			// the write-path constructor, which the flush builds its pages with
			final GenericEvitaInternalError writePathError = assertThrows(
				GenericEvitaInternalError.class, () -> leafPage(new int[]{11}, buckets)
			);
			assertTrue(
				writePathError.getMessage().contains("1") && writePathError.getMessage().contains("2"),
				"the refusal must name both counts, but was: " + writePathError.getMessage()
			);

			// and the read-path constructor the serializer rehydrates through, which checks separately - a misaligned
			// column arriving from disk misattributes every value past the divergence exactly as a freshly built one
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new FilterIndexLeafPagePart(1, 7, buckets, new int[]{11}, 42L)
			);
		}
	}

	@Nested
	@DisplayName("The filter index root part")
	class RootPart {

		@Nonnull
		private FilterIndexStoragePart pagedRoot(int nextValueId) {
			return new FilterIndexStoragePart(
				1, new AttributeIndexKey(null, "code", null), String.class,
				new ValueToRecordBitmap[0], null, 0,
				true, 4, new int[]{0, 1, 4},
				false, -1, new int[0],
				nextValueId, null, 1L
			);
		}

		@Test
		@DisplayName("a root written before value ids reads back with none, page metadata intact")
		void shouldReadPreValueIdRoot() {
			final FilterIndexStoragePart root = pagedRoot(500);

			final FilterIndexStoragePart restored = readThroughBackwardCompatibleRoute(
				ROOT_PART_PRE_VALUE_ID_UID,
				new FilterIndexStoragePartSerializer(ValueIdBackwardCompatibilityTest.this.keyCompressor),
				root, FilterIndexStoragePart.class
			);

			assertEquals(
				ValueIdAllocator.UNASSIGNED_VALUE_ID, restored.getNextValueId(),
				"A root written before value ids must come back claiming none."
			);
			assertNull(restored.getInlineValueIds());
			// the range page-stream metadata is the section written IMMEDIATELY BEFORE the appended value id
			// section — its survival is the byte-alignment witness
			assertTrue(restored.isPaged());
			assertEquals(4, restored.getHighWaterPageSequence());
			assertArrayEquals(new int[]{0, 1, 4}, restored.getLeafPageSequences());
			assertFalse(restored.isRangePaged());
			assertEquals(-1, restored.getRangeHighWaterPageSequence());
			assertArrayEquals(new int[0], restored.getRangeLeafPageSequences());
		}

		@Test
		@DisplayName("a root written today keeps its high-water mark")
		void shouldRoundTripHighWaterMark() {
			final FilterIndexStoragePart root = pagedRoot(500);

			final ByteArrayOutputStream baos = new ByteArrayOutputStream(1_024);
			try (final Output output = new Output(baos, 1_024)) {
				ValueIdBackwardCompatibilityTest.this.kryo.writeObject(output, root);
			}
			final FilterIndexStoragePart restored;
			try (final Input input = new Input(new ByteArrayInputStream(baos.toByteArray()))) {
				restored = ValueIdBackwardCompatibilityTest.this.kryo.readObject(input, FilterIndexStoragePart.class);
			}

			assertEquals(500, restored.getNextValueId());
			assertArrayEquals(new int[]{0, 1, 4}, restored.getLeafPageSequences());
		}

		@Test
		@DisplayName("an inline root keeps its id column aligned with its buckets")
		void shouldRoundTripInlineValueIdColumn() {
			final FilterIndexStoragePart root = new FilterIndexStoragePart(
				1, new AttributeIndexKey(null, "code", null), String.class,
				new ValueToRecordBitmap[]{
					new ValueToRecordBitmap("alpha", 100),
					new ValueToRecordBitmap("beta", 200)
				},
				null, 0,
				false, -1, new int[0],
				false, -1, new int[0],
				42, new int[]{7, 9}, 1L
			);

			final ByteArrayOutputStream baos = new ByteArrayOutputStream(1_024);
			try (final Output output = new Output(baos, 1_024)) {
				ValueIdBackwardCompatibilityTest.this.kryo.writeObject(output, root);
			}
			final FilterIndexStoragePart restored;
			try (final Input input = new Input(new ByteArrayInputStream(baos.toByteArray()))) {
				restored = ValueIdBackwardCompatibilityTest.this.kryo.readObject(input, FilterIndexStoragePart.class);
			}

			assertEquals(42, restored.getNextValueId());
			assertArrayEquals(new int[]{7, 9}, restored.getInlineValueIds());
			assertEquals(2, restored.getHistogramPoints().length);
		}

		@Test
		@DisplayName("an inline id column that does not match the inline buckets never reaches the disk")
		void shouldRefuseInlineIdColumnThatDoesNotMatchTheInlineBuckets() {
			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> new FilterIndexStoragePart(
					1, new AttributeIndexKey(null, "code", null), String.class,
					new ValueToRecordBitmap[]{
						new ValueToRecordBitmap("alpha", 100),
						new ValueToRecordBitmap("beta", 200)
					},
					null, 0,
					false, -1, new int[0],
					false, -1, new int[0],
					42, new int[]{7}, 1L
				)
			);

			assertTrue(
				error.getMessage().contains("1") && error.getMessage().contains("2"),
				"the refusal must name both counts, but was: " + error.getMessage()
			);
		}
	}
}
