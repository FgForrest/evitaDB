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
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.index.cardinality.AttributeCardinalityIndex.AttributeCardinalityKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeCardinalityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Map;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reads attribute cardinality counters in the formats releases 2026.2 and 2025.5 shipped through the composed Kryo
 * configuration, so each part reaches {@link AttributeCardinalityIndexStoragePartSerializer_2026_2} or
 * {@link AttributeCardinalityIndexStoragePartSerializer_2025_5} by the uid it carries.
 *
 * The shape both readers must survive: two counters of one record whose keys the release told apart — a
 * `DateTimeRange` open-ended at two offsets — and today's engine considers equal. Each counted a contribution to the
 * one index entry they now share, so they must be summed; keeping only the second makes the entry disappear when the
 * first contributor leaves while the second still holds it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Attribute cardinality counters written by releases 2026.2 and 2025.5")
@Tag(STORAGE)
@Tag(INDEXING)
@Tag(SERIALIZATION)
class AttributeCardinalityIndexStoragePartSerializer_2026_2Test {
	/** The serial-version-uid release 2026.2 shipped for `AttributeCardinalityIndexStoragePart`. */
	private static final long RELEASE_2026_2_UID = -929865952179187357L;
	/** The serial-version-uid release 2025.5 shipped for `AttributeCardinalityIndexStoragePart`. */
	private static final long RELEASE_2025_5_UID = 6163295675316818632L;
	/** The reference attribute under test. */
	private static final AttributeIndexKey ATTRIBUTE_KEY = new AttributeIndexKey("brand", "validity", null);
	/** The owning entity index. */
	private static final int ENTITY_INDEX_PK = 42;
	/** The storage part identifier. */
	private static final long STORAGE_PART_PK = 7L;
	/** Open-ended at +01:00. */
	private static final DateTimeRange UNTIL_PLUS_ONE =
		DateTimeRange.until(OffsetDateTime.of(2026, 1, 1, 10, 0, 0, 0, ZoneOffset.ofHours(1)));
	/** The same moment open-ended at +02:00 — a different key to the release, an equal one today. */
	private static final DateTimeRange UNTIL_PLUS_TWO =
		DateTimeRange.until(OffsetDateTime.of(2026, 1, 1, 11, 0, 0, 0, ZoneOffset.ofHours(2)));
	/** A value distinct from both twins, then and now. */
	private static final DateTimeRange LATER =
		DateTimeRange.until(OffsetDateTime.of(2027, 1, 1, 10, 0, 0, 0, ZoneOffset.ofHours(1)));

	/** The production Kryo configuration. */
	private Kryo kryo;
	/** The attribute key dictionary. */
	private ReadWriteKeyCompressor keyCompressor;

	/** Creates the production serializer registry. */
	@BeforeEach
	void setUp() {
		this.keyCompressor = new ReadWriteKeyCompressor(Collections.emptyMap());
		this.kryo = KryoFactory.createKryo(new IndexStoragePartConfigurer(this.keyCompressor));
	}

	/**
	 * Writes a counter part in a released format: the uid, the header, for 2025.5 the eight-byte uid of the removed
	 * cardinality-index serializer, the declared value type, then each key's value with no class of its own, its
	 * record id and its count.
	 *
	 * @param uid           the uid to stamp
	 * @param values        the key values, one per counter
	 * @param recordIds     the record id of each counter
	 * @param cardinalities the count of each counter
	 * @return the decoded part
	 */
	@Nonnull
	private AttributeCardinalityIndexStoragePart read(
		long uid,
		@Nonnull Serializable[] values,
		@Nonnull int[] recordIds,
		@Nonnull int[] cardinalities
	) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(1_024);
		try (final Output output = new Output(os, 1_024)) {
			output.writeLong(uid);
			output.writeInt(ENTITY_INDEX_PK);
			output.writeVarLong(STORAGE_PART_PK, true);
			output.writeVarInt(this.keyCompressor.getId(ATTRIBUTE_KEY), true);
			if (uid == RELEASE_2025_5_UID) {
				output.writeLong(0L);
			}
			this.kryo.writeClass(output, DateTimeRange.class);
			output.writeVarInt(values.length, true);
			for (int i = 0; i < values.length; i++) {
				this.kryo.writeObject(output, values[i]);
				output.writeVarInt(recordIds[i], false);
				output.writeVarInt(cardinalities[i], true);
			}
		}
		return StoragePartSerializerTestSupport.decode(
			this.kryo, os.toByteArray(), AttributeCardinalityIndexStoragePart.class
		);
	}

	/** Checks that the released twins compare equal today. */
	@Test
	@DisplayName("the two open-ended twins are equal today")
	void shouldTreatOpenEndedTwinsAsEqualToday() {
		// the premise of every scenario below - if it stops holding, the scenarios prove nothing
		assertEquals(UNTIL_PLUS_ONE, UNTIL_PLUS_TWO);
	}

	/** Tests counters written in the 2026.2 layout. */
	@Nested
	@DisplayName("the 2026.2 counter format")
	class Format2026_2 {

		/** Verifies counter contributions remain attributable after decoding. */
		@Test
		@DisplayName("two counters of one record whose keys are equal today are summed")
		void shouldSumCountersWhoseKeysAreEqualToday() {
			final AttributeCardinalityIndexStoragePart part = read(
				RELEASE_2026_2_UID,
				new Serializable[]{UNTIL_PLUS_ONE, UNTIL_PLUS_TWO, LATER}, new int[]{5, 5, 5}, new int[]{1, 2, 1}
			);

			assertEquals(
				Map.of(new AttributeCardinalityKey(5, UNTIL_PLUS_ONE), 3, new AttributeCardinalityKey(5, LATER), 1),
				part.getCardinalityIndex().getCardinalities()
			);
		}

		/** Verifies counter contributions remain attributable after decoding. */
		@Test
		@DisplayName("equal keys of different records stay separate counters")
		void shouldKeepCountersOfDifferentRecordsApart() {
			final AttributeCardinalityIndexStoragePart part = read(
				RELEASE_2026_2_UID,
				new Serializable[]{UNTIL_PLUS_ONE, UNTIL_PLUS_TWO}, new int[]{5, 6}, new int[]{1, 1}
			);

			assertEquals(
				Map.of(
					new AttributeCardinalityKey(5, UNTIL_PLUS_ONE), 1,
					new AttributeCardinalityKey(6, UNTIL_PLUS_TWO), 1
				),
				part.getCardinalityIndex().getCardinalities()
			);
		}
	}

	/** Tests counters written in the 2025.5 layout. */
	@Nested
	@DisplayName("the 2025.5 counter format")
	class Format2025_5 {

		/** Verifies counter contributions remain attributable after decoding. */
		@Test
		@DisplayName("two counters of one record whose keys are equal today are summed")
		void shouldSumCountersWhoseKeysAreEqualToday() {
			final AttributeCardinalityIndexStoragePart part = read(
				RELEASE_2025_5_UID,
				new Serializable[]{UNTIL_PLUS_ONE, UNTIL_PLUS_TWO}, new int[]{5, 5}, new int[]{1, 1}
			);

			assertEquals(
				Map.of(new AttributeCardinalityKey(5, UNTIL_PLUS_ONE), 2),
				part.getCardinalityIndex().getCardinalities()
			);
		}
	}
}
