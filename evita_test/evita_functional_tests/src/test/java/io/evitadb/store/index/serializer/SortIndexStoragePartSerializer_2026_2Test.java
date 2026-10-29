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
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.attribute.SortIndex.ComparableArray;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeKeyWithIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexStoragePart;
import io.evitadb.store.entity.EntityStoragePartConfigurer;
import io.evitadb.store.schema.SchemaKryoConfigurer;
import io.evitadb.store.shared.kryo.SharedClassesConfigurer;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.index.serializer.util.SortedIntArrayCodec;
import io.evitadb.store.shared.kryo.KryoFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Map;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads sort-index parts in the format release 2026.2 shipped through the composed Kryo configuration
 * ({@link IndexStoragePartConfigurer} and the serial-version dispatch), so every part below reaches
 * the released-format reader exactly as a part of a 2026.2 catalog does: by the uid it carries.
 *
 * The bytes are written by a test-side writer of that format which stamps the released uid explicitly — the current
 * writer cannot produce them, because it looks up block lengths by today's `equals` and so mis-writes the very shapes
 * this reader exists for. The shapes are two `DateTimeRange` values open-ended at two offsets, which release 2026.2
 * told apart and today's engine considers equal.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Sort index parts written by release 2026.2")
@Tag(STORAGE)
@Tag(INDEXING)
@Tag(SERIALIZATION)
class SortIndexStoragePartSerializer_2026_2Test {
	/** The serial-version-uid release 2026.2 shipped for `SortIndexStoragePart`. */
	private static final long RELEASE_2026_2_UID = 5192847362910473829L;
	/** The sort attribute under test. */
	private static final AttributeIndexKey ATTRIBUTE_KEY = new AttributeIndexKey(null, "sortValidity", null);
	/** The owning entity index. */
	private static final int ENTITY_INDEX_PK = 42;
	/** The storage part identifier. */
	private static final long STORAGE_PART_PK = 7L;
	/** Open-ended at +01:00 — one of the two values release 2026.2 kept apart. */
	private static final DateTimeRange UNTIL_PLUS_ONE =
		DateTimeRange.until(OffsetDateTime.of(2026, 1, 1, 10, 0, 0, 0, ZoneOffset.ofHours(1)));
	/** The same moment open-ended at +02:00 — equal to {@link #UNTIL_PLUS_ONE} today. */
	private static final DateTimeRange UNTIL_PLUS_TWO =
		DateTimeRange.until(OffsetDateTime.of(2026, 1, 1, 11, 0, 0, 0, ZoneOffset.ofHours(2)));

	/** The production Kryo configuration. */
	private Kryo kryo;
	/** The attribute key dictionary. */
	private ReadWriteKeyCompressor keyCompressor;

	/**
	 * Returns a single-element comparator base of the given type, ascending with nulls last.
	 *
	 * @param type the element type
	 * @return the comparator base
	 */
	@Nonnull
	private static ComparatorSource[] single(@Nonnull Class<?> type) {
		return new ComparatorSource[]{new ComparatorSource(type, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)};
	}

	/**
	 * Returns the comparator base of a `(DateTimeRange, Integer)` compound.
	 *
	 * @return the comparator base
	 */
	@Nonnull
	private static ComparatorSource[] rangeAndInteger() {
		return new ComparatorSource[]{
			new ComparatorSource(DateTimeRange.class, OrderDirection.ASC, OrderBehaviour.NULLS_FIRST),
			new ComparatorSource(Integer.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
		};
	}

	/** Creates the production serializer registry. */
	@BeforeEach
	void setUp() {
		this.keyCompressor = new ReadWriteKeyCompressor(Collections.emptyMap());
		this.kryo = KryoFactory.createKryo(
			SchemaKryoConfigurer.INSTANCE
				.andThen(SharedClassesConfigurer.INSTANCE)
				.andThen(SchemaKryoConfigurer.INSTANCE)
				.andThen(new IndexStoragePartConfigurer(this.keyCompressor))
				.andThen(new EntityStoragePartConfigurer(this.keyCompressor))
		);
	}

	/**
	 * Writes the uid and the header every shape of the 2026.2 format opens with.
	 *
	 * @param output         the output to write to
	 * @param comparatorBase the comparator base of the part
	 */
	private void writeHeader(@Nonnull Output output, @Nonnull ComparatorSource[] comparatorBase) {
		output.writeLong(RELEASE_2026_2_UID);
		output.writeInt(ENTITY_INDEX_PK);
		output.writeVarLong(STORAGE_PART_PK, true);
		output.writeVarInt(this.keyCompressor.getId(ATTRIBUTE_KEY), true);
		output.writeVarInt(comparatorBase.length, true);
		for (final ComparatorSource source : comparatorBase) {
			this.kryo.writeClass(output, source.type());
			this.kryo.writeObject(output, source.orderDirection());
			this.kryo.writeObject(output, source.orderBehaviour());
		}
	}

	/**
	 * Writes one value as release 2026.2 did: self-describing, a compound element by element.
	 *
	 * @param output               the output to write to
	 * @param value                the value or compound value
	 * @param comparatorBaseLength the number of comparator elements
	 */
	private void writeValue(@Nonnull Output output, @Nonnull Serializable value, int comparatorBaseLength) {
		if (comparatorBaseLength == 1) {
			this.kryo.writeClassAndObject(output, value);
		} else {
			for (final Serializable element : ((ComparableArray) value).array()) {
				this.kryo.writeClassAndObject(output, element);
			}
		}
	}

	/**
	 * Writes an owner-`SINGLE` part in the 2026.2 format with explicitly given block lengths — the lengths the
	 * release derived from its own value tree, which today's writer cannot reproduce for values equal today.
	 *
	 * @param comparatorBase    the comparator base
	 * @param values            the distinct values in block order
	 * @param cardinalityValues the values of the sparse cardinality entries, in order
	 * @param cardinalities     their cardinalities
	 * @param sortedRecords     the record ids, blocked per value
	 * @param blockLengths      the block length of every value
	 * @param deltaEncoded      `false` to write the raw fallback the release used for non-ascending blocks
	 * @return the uid-prefixed bytes
	 */
	@Nonnull
	private byte[] ownerSingle(
		@Nonnull ComparatorSource[] comparatorBase,
		@Nonnull Serializable[] values,
		@Nonnull Serializable[] cardinalityValues,
		@Nonnull int[] cardinalities,
		@Nonnull int[] sortedRecords,
		@Nonnull int[] blockLengths,
		boolean deltaEncoded
	) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(1_024);
		try (final Output output = new Output(os, 1_024)) {
			writeHeader(output, comparatorBase);
			output.writeBoolean(true);
			output.writeVarInt(values.length, true);
			for (final Serializable value : values) {
				writeValue(output, value, comparatorBase.length);
			}
			output.writeVarInt(cardinalityValues.length, true);
			for (int i = 0; i < cardinalityValues.length; i++) {
				writeValue(output, cardinalityValues[i], comparatorBase.length);
				output.writeVarInt(cardinalities[i], true);
			}
			output.writeBoolean(deltaEncoded);
			output.writeVarInt(sortedRecords.length, true);
			if (deltaEncoded) {
				int offset = 0;
				for (final int blockLength : blockLengths) {
					SortedIntArrayCodec.writeAscendingRun(output, sortedRecords, offset, blockLength);
					offset += blockLength;
				}
			} else {
				output.writeInts(sortedRecords, 0, sortedRecords.length);
			}
			output.writeVarInt(0, true);
		}
		return os.toByteArray();
	}

	/**
	 * Decodes uid-prefixed bytes through the composed configuration.
	 *
	 * @param bytes the bytes
	 * @return the decoded part
	 */
	@Nonnull
	private SortIndexStoragePart decode(@Nonnull byte[] bytes) {
		return StoragePartSerializerTestSupport.decode(this.kryo, bytes, SortIndexStoragePart.class);
	}

	/**
	 * Asserts a decoded owner-`SINGLE` part carries the given records, its own value column as a dense cardinality
	 * column with the given block lengths.
	 *
	 * @param part          the decoded part
	 * @param sortedRecords the expected record ids
	 * @param blockLengths  the expected block length of every value
	 */
	private static void assertDenseReleasePart(
		@Nonnull SortIndexStoragePart part,
		@Nonnull int[] sortedRecords,
		@Nonnull int[] blockLengths
	) {
		assertArrayEquals(sortedRecords, part.getSortedRecords(), "every record id must decode exactly");
		assertArrayEquals(blockLengths, part.getCardinalities(), "every block length must sit at its value's position");
		assertSame(
			part.getSortedRecordsValues(), part.getCardinalityValues(),
			"the cardinality column must be the value column itself, so positions - not equality - pair them"
		);
		assertEquals(ENTITY_INDEX_PK, part.getEntityIndexPrimaryKey());
		assertEquals(STORAGE_PART_PK, part.getStoragePartPK());
		assertEquals(ATTRIBUTE_KEY, part.getAttributeIndexKey());
	}

	/** Checks that the released twins compare equal today. */
	@Test
	@DisplayName("the two open-ended twins this reader exists for are equal today")
	void shouldTreatOpenEndedTwinsAsEqualToday() {
		// the premise of every scenario below - if it stops holding, the scenarios prove nothing
		assertEquals(UNTIL_PLUS_ONE, UNTIL_PLUS_TWO);
	}

	/** Tests inline released sort parts. */
	@Nested
	@DisplayName("owner-SINGLE parts")
	class OwnerSingle {

		/** Verifies the released layout decodes without losing positional information. */
		@Test
		@DisplayName("twins with blocks of two and three decode to all five ids")
		void shouldDecodeEveryIdWhenTwinsHoldBlocksOfDifferentLength() {
			final SortIndexStoragePart part = decode(ownerSingle(
				single(DateTimeRange.class),
				new Serializable[]{UNTIL_PLUS_ONE, UNTIL_PLUS_TWO},
				new Serializable[]{UNTIL_PLUS_ONE, UNTIL_PLUS_TWO}, new int[]{2, 3},
				new int[]{1, 2, 3, 4, 5}, new int[]{2, 3}, true
			));

			assertDenseReleasePart(part, new int[]{1, 2, 3, 4, 5}, new int[]{2, 3});
			assertEquals(
				UNTIL_PLUS_TWO.getPreciseTo(),
				((DateTimeRange) part.getSortedRecordsValues()[1]).getPreciseTo(),
				"each twin keeps the offset it was written with"
			);
		}

		/** Verifies the released layout decodes without losing positional information. */
		@Test
		@DisplayName("a singleton twin ahead of its longer twin decodes to the exact ids")
		void shouldDecodeExactIdsWhenSingletonTwinPrecedesLongerTwin() {
			// matching by equality hands the first twin the second one's length and decodes 10, 50, 51, -1
			final SortIndexStoragePart part = decode(ownerSingle(
				single(DateTimeRange.class),
				new Serializable[]{UNTIL_PLUS_ONE, UNTIL_PLUS_TWO},
				new Serializable[]{UNTIL_PLUS_TWO}, new int[]{3},
				new int[]{10, 20, 21, 22}, new int[]{1, 3}, true
			));

			assertDenseReleasePart(part, new int[]{10, 20, 21, 22}, new int[]{1, 3});
		}

		/** Verifies the released layout decodes without losing positional information. */
		@Test
		@DisplayName("a compound whose twins differ in a range element only decodes by position")
		void shouldDecodeCompoundTwinsByPosition() {
			final SortIndexStoragePart part = decode(ownerSingle(
				rangeAndInteger(),
				new Serializable[]{
					new ComparableArray(new Serializable[]{null, 1}),
					new ComparableArray(new Serializable[]{UNTIL_PLUS_ONE, 1}),
					new ComparableArray(new Serializable[]{UNTIL_PLUS_TWO, 1})
				},
				new Serializable[]{
					new ComparableArray(new Serializable[]{null, 1}),
					new ComparableArray(new Serializable[]{UNTIL_PLUS_TWO, 1})
				},
				new int[]{2, 2},
				new int[]{4, 5, 7, 3, 9}, new int[]{2, 1, 2}, true
			));

			assertDenseReleasePart(part, new int[]{4, 5, 7, 3, 9}, new int[]{2, 1, 2});
		}

		/** Verifies the released layout decodes without losing positional information. */
		@Test
		@DisplayName("a part without twins decodes as the current reader decodes it")
		void shouldDecodePlainPartAsCurrentReaderDoes() {
			final SortIndexStoragePart part = decode(ownerSingle(
				single(String.class),
				new Serializable[]{"apple", "banana", "cherry"},
				new Serializable[]{"banana"}, new int[]{2},
				new int[]{5, 1, 2, 8}, new int[]{1, 2, 1}, true
			));

			assertDenseReleasePart(part, new int[]{5, 1, 2, 8}, new int[]{1, 2, 1});
			assertEquals(
				Map.of("apple", 1, "banana", 2, "cherry", 1), part.getValueCardinalities(),
				"the dense column reads back as a map whose entries of one mean the implied one"
			);
		}

		/** Verifies the released layout decodes without losing positional information. */
		@Test
		@DisplayName("the raw fallback for a non-ascending block decodes verbatim")
		void shouldDecodeRawBlocksVerbatim() {
			final SortIndexStoragePart part = decode(ownerSingle(
				single(String.class),
				new Serializable[]{"x", "y"},
				new Serializable[]{"x"}, new int[]{2},
				new int[]{9, 4, 6}, new int[]{2, 1}, false
			));

			assertDenseReleasePart(part, new int[]{9, 4, 6}, new int[]{2, 1});
		}

		/** Verifies that a cardinality entry naming no stored value fails the read instead of guessing a position. */
		@Test
		@DisplayName("a cardinality entry matching no value fails loud")
		void shouldFailWhenCardinalityEntryMatchesNoValue() {
			final byte[] bytes = ownerSingle(
				single(String.class),
				new Serializable[]{"x", "y"},
				new Serializable[]{"z"}, new int[]{2},
				new int[]{1, 2, 3}, new int[]{1, 2}, false
			);

			final GenericEvitaInternalError failure =
				assertThrows(GenericEvitaInternalError.class, () -> decode(bytes));
			assertTrue(failure.getMessage().contains("cardinality entries match a value"), failure.getMessage());
		}

		/** Verifies that block lengths not summing to the record count fail the read. */
		@Test
		@DisplayName("block lengths that do not cover the record array fail loud")
		void shouldFailWhenBlockLengthsDoNotCoverRecords() {
			final byte[] bytes = ownerSingle(
				single(String.class),
				new Serializable[]{"x", "y"},
				new Serializable[]{"x"}, new int[]{3},
				new int[]{1, 2, 3}, new int[]{3}, false
			);

			final GenericEvitaInternalError failure =
				assertThrows(GenericEvitaInternalError.class, () -> decode(bytes));
			assertTrue(failure.getMessage().contains("block lengths sum to"), failure.getMessage());
		}
	}

	/** Tests roots without inline value columns. */
	@Nested
	@DisplayName("owner-PAGED and view parts")
	class PagedAndView {

		/** Verifies that a paged root keeps its page stream and its leaf page keeps the twins in separate buckets. */
		@Test
		@DisplayName("a paged root keeps its page stream, and its leaf page keeps both twins apart")
		void shouldDecodePagedRootAndItsLeafPage() {
			final ByteArrayOutputStream os = new ByteArrayOutputStream(256);
			try (final Output output = new Output(os, 256)) {
				writeHeader(output, single(DateTimeRange.class));
				output.writeBoolean(false);
				output.writeBoolean(true);
				output.writeVarInt(3, true);
				output.writeVarInt(2, true);
				output.writeInts(new int[]{1, 3}, 0, 2);
				output.writeVarInt(0, true);
			}

			final SortIndexStoragePart root = decode(os.toByteArray());

			assertTrue(root.isPaged(), "the root must stay paged");
			assertEquals(3, root.getHighWaterPageSequence());
			assertArrayEquals(new int[]{1, 3}, root.getLeafPageSequencesOrThrowException());

			// the leaf page record kept its uid since the release, so the current serializer reads it
			final SortIndexLeafPagePart page = new SortIndexLeafPagePart(
				ENTITY_INDEX_PK, new AttributeKeyWithIndexType(ATTRIBUTE_KEY, AttributeIndexType.SORT), 1,
				new ValueToRecord[]{
					new ValueToRecordBitmap(UNTIL_PLUS_ONE, 1, 2),
					new ValueToRecordBitmap(UNTIL_PLUS_TWO, 3)
				},
				1
			);
			page.computeUniquePartIdAndSet(SortIndexStoragePartSerializer_2026_2Test.this.keyCompressor);
			final SortIndexLeafPagePart decodedPage = StoragePartSerializerTestSupport.roundTrip(
				SortIndexStoragePartSerializer_2026_2Test.this.kryo, page, SortIndexLeafPagePart.class
			);
			assertEquals(2, decodedPage.getBuckets().length, "both twins must stay separate buckets");
			assertEquals(
				UNTIL_PLUS_TWO.getPreciseTo(),
				((DateTimeRange) decodedPage.getBuckets()[1].getValue()).getPreciseTo()
			);
			assertArrayEquals(new int[]{3}, decodedPage.getBuckets()[1].getRecordIds().getArray());
		}

		/** Verifies that a view part keeps its raw record array and carries no value columns. */
		@Test
		@DisplayName("a view part keeps its raw record array and no value columns")
		void shouldDecodeViewPart() {
			final ByteArrayOutputStream os = new ByteArrayOutputStream(256);
			try (final Output output = new Output(os, 256)) {
				writeHeader(output, single(String.class));
				output.writeBoolean(false);
				output.writeBoolean(false);
				output.writeVarInt(3, true);
				output.writeInts(new int[]{3, 1, 2}, 0, 3);
				output.writeVarInt(0, true);
			}

			final SortIndexStoragePart part = decode(os.toByteArray());

			assertFalse(part.isPaged());
			assertArrayEquals(new int[]{3, 1, 2}, part.getSortedRecords());
			assertEquals(0, part.getSortedRecordsValues().length);
			assertEquals(0, part.getCardinalityValues().length);
		}
	}

	/** Tests serial version dispatch. */
	@Nested
	@DisplayName("dispatch by serial version")
	class Dispatch {

		/**
		 * Verifies that the current serial version differs from the released one, so released parts reach this reader.
		 */
		@Test
		@DisplayName("the current serial version differs from the released one")
		void shouldCarrySerialVersionDistinctFromRelease() {
			assertNotEquals(
				RELEASE_2026_2_UID,
				ObjectStreamClass.lookup(SortIndexStoragePart.class).getSerialVersionUID(),
				"were they equal, a 2026.2 part would be handed to the current reader"
			);
		}

		/** Verifies that a part written by the current serializer reads back through the current reader. */
		@Test
		@DisplayName("a part written by the current serializer reads back correctly")
		void shouldRoundTripCurrentPart() {
			final SortIndexStoragePart current = new SortIndexStoragePart(
				ENTITY_INDEX_PK, ATTRIBUTE_KEY, single(String.class),
				new int[]{1, 2, 3}, new Serializable[]{"a", "b"}, Map.of("a", 2), STORAGE_PART_PK
			);

			final SortIndexStoragePart decoded = StoragePartSerializerTestSupport.roundTrip(
				SortIndexStoragePartSerializer_2026_2Test.this.kryo, current, SortIndexStoragePart.class
			);

			assertArrayEquals(new int[]{1, 2, 3}, decoded.getSortedRecords());
			assertArrayEquals(new Serializable[]{"a", "b"}, decoded.getSortedRecordsValues());
			assertEquals(Map.of("a", 2), decoded.getValueCardinalities());
		}
	}

}
