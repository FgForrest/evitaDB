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

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.cardinality.AttributeCardinalityIndex;
import io.evitadb.index.cardinality.AttributeCardinalityIndex.AttributeCardinalityKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeCardinalityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Drives the v6→v7 counter repair over real bytes, end to end: a storage part written the way 2026.2 wrote it,
 * through the `serialVersionUID` dispatch that routes it to the backward-compatible reader, through
 * {@link Migration_2026_3#rekeyCardinalities}, out through the CURRENT serializer, and back in again.
 *
 * {@link Migration_2026_3_Test} pins the same summing as a pure transform, which is the right shape for the
 * merge arithmetic but proves nothing about the format the repaired counter has to survive. That format is the
 * whole reason this migration needed a storage-protocol bump rather than a loader-side fix: the repaired key of
 * a `BigDecimal`-typed counter is an `Integer`, and the 2026.2 layout wrote each value with no class of its own
 * and read it back as the index's DECLARED type — so it physically cannot carry the repaired state. Only a
 * round trip can show that the new layout can, and that the old bytes still reach the old reader.
 *
 * `EvitaBackwardCompatibilityTest` remains the integration oracle over real downloaded catalogs; it exercises
 * the migration's storage plumbing but holds no COLLIDING values, so the summing branch — the repair itself —
 * never runs there.
 *
 * @author Claude (defect A investigation), FG Forrest a.s. (c) 2026
 */
@DisplayName("Migration_2026_3 — counter repair across a real storage round trip (v6→v7)")
@Tag(STORAGE)
@Tag(INDEXING)
@Tag(SERIALIZATION)
@SuppressWarnings("removal") // the migration interface is @Deprecated(forRemoval); testing it is the point
class Migration_2026_3_RepairRoundTripTest {

	/**
	 * The `serialVersionUID` {@link AttributeCardinalityIndexStoragePart} carried in 2026.2, registered in
	 * {@link IndexStoragePartConfigurer} against `AttributeCardinalityIndexStoragePartSerializer_2026_2`.
	 */
	private static final long LEGACY_2026_2_UID = -929865952179187357L;
	/** Precomposed `é` — one code point, U+00E9: the form a pre-NFD counter would have stored. */
	private static final String PRECOMPOSED = "café";
	/** NFD decomposition of the same word — `e` + combining acute accent (U+0065 U+0301). */
	private static final String DECOMPOSED = "café";

	private static final AttributeIndexKey ATTRIBUTE_KEY = new AttributeIndexKey("variants", "size", null);
	private static final int ENTITY_INDEX_PK = 42;
	private static final long STORAGE_PART_PK = 7L;
	/** The record whose single shared index entry two owners were counting. */
	private static final int RECORD_ID = 100;

	private Kryo kryo;
	private ReadWriteKeyCompressor keyCompressor;

	@BeforeEach
	void setUp() {
		this.keyCompressor = new ReadWriteKeyCompressor(Collections.emptyMap());
		this.kryo = KryoFactory.createKryo(new IndexStoragePartConfigurer(this.keyCompressor));
	}

	@Test
	@DisplayName("two BigDecimals that share one index entry become one key counting two, and survive being stored")
	void shouldSumCollidingBigDecimalCountersAcrossAStorageRoundTrip() {
		// 1. the bytes a 2026.2 catalog holds: two raw keys, one per owner, over a single shared tree entry
		final AttributeCardinalityIndexStoragePart legacyPart = readPart(
			writeLegacyPart(
				BigDecimal.class,
				new Serializable[]{new BigDecimal("1.2"), new BigDecimal("1.4")},
				new int[]{1, 1}
			)
		);
		assertEquals(
			2, legacyPart.getCardinalityIndex().getCardinalities().size(),
			"the legacy reader must hand back both raw keys - that is the defective state being repaired"
		);

		// 2. the repair: at indexedDecimalPlaces 0 both values scale onto the int key 1
		final Map<AttributeCardinalityKey, Integer> repairedCounters = Migration_2026_3.rekeyCardinalities(
			legacyPart.getCardinalityIndex().getCardinalities(),
			FilterIndex.getNormalizer(BigDecimal.class, 0)
		);
		assertNotNull(repairedCounters, "both keys move, so the part must be rewritten");

		// 3. the repaired part goes back to storage through the current serializer and comes back intact -
		//    note the key class (Integer) is NOT the declared value type (BigDecimal), which is exactly what
		//    the 2026.2 layout could not express
		final AttributeCardinalityIndexStoragePart reloaded = roundTripThroughCurrentFormat(
			part(BigDecimal.class, repairedCounters)
		);

		final Map<AttributeCardinalityKey, Integer> counters = reloaded.getCardinalityIndex().getCardinalities();
		assertEquals(1, counters.size(), "the two owners share one entry, so one key must survive");
		assertEquals(
			Integer.valueOf(2), counters.get(new AttributeCardinalityKey(RECORD_ID, 1)),
			"the key must count TWO - a count of one drops the shared entry on the first owner's departure, " +
				"which is the defect this migration exists to repair"
		);
		assertEquals(
			BigDecimal.class, reloaded.getCardinalityIndex().getValueType(),
			"the declared value type stays what the schema says, only the KEYS are canonical"
		);
	}

	@Test
	@DisplayName("two spellings of one accented word become one NFD key counting their sum, and survive being stored")
	void shouldSumCollidingStringCountersAcrossAStorageRoundTrip() {
		final AttributeCardinalityIndexStoragePart legacyPart = readPart(
			writeLegacyPart(String.class, new Serializable[]{PRECOMPOSED, DECOMPOSED}, new int[]{1, 3})
		);
		assertEquals(2, legacyPart.getCardinalityIndex().getCardinalities().size());

		final Map<AttributeCardinalityKey, Integer> repairedCounters = Migration_2026_3.rekeyCardinalities(
			legacyPart.getCardinalityIndex().getCardinalities(),
			FilterIndex.getNormalizer(String.class, 0)
		);
		assertNotNull(repairedCounters, "the precomposed spelling moves, so the part must be rewritten");

		final Map<AttributeCardinalityKey, Integer> counters =
			roundTripThroughCurrentFormat(part(String.class, repairedCounters))
				.getCardinalityIndex().getCardinalities();
		assertEquals(1, counters.size(), "canonically equivalent spellings share one tree entry and one key");
		assertEquals(
			Integer.valueOf(4), counters.get(new AttributeCardinalityKey(RECORD_ID, DECOMPOSED)),
			"1 + 3 contributions over one entry - the entry must outlive the first three departures"
		);
	}

	@Test
	@DisplayName("the current serial version differs from the 2026.2 one, so old bytes take the legacy path")
	void shouldCarryASerialVersionDistinctFromTheLegacyOne() {
		assertNotEquals(
			LEGACY_2026_2_UID,
			ObjectStreamClass.lookup(AttributeCardinalityIndexStoragePart.class).getSerialVersionUID(),
			"the layout changed, so the uid must have changed with it - were they equal, a 2026.2 part would be " +
				"handed to the current reader, which expects a class token in front of every key"
		);
	}

	/**
	 * Builds a part carrying the given counters under the given declared value type.
	 */
	@Nonnull
	private static AttributeCardinalityIndexStoragePart part(
		@Nonnull Class<? extends Serializable> valueType,
		@Nonnull Map<AttributeCardinalityKey, Integer> counters
	) {
		return new AttributeCardinalityIndexStoragePart(
			ENTITY_INDEX_PK, ATTRIBUTE_KEY, new AttributeCardinalityIndex(valueType, counters), STORAGE_PART_PK
		);
	}

	/**
	 * Writes the bytes a 2026.2 catalog holds for one cardinality counter, reproducing that writer exactly: the
	 * old `serialVersionUID` ahead of the payload (`SerialVersionBasedSerializer` stamps it, and it is what routes
	 * the bytes to the backward-compatible reader), then each key's value written with NO class of its own — the
	 * defining trait of the layout, and the reason a normalized key cannot be stored in it.
	 *
	 * @param valueType     the counter's declared value type, which is also how every key is read back
	 * @param values        the raw key values, one per counter entry
	 * @param cardinalities the count of each, positionally aligned with `values`
	 * @return the serialized legacy part
	 */
	@Nonnull
	private byte[] writeLegacyPart(
		@Nonnull Class<? extends Serializable> valueType,
		@Nonnull Serializable[] values,
		@Nonnull int[] cardinalities
	) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(1_024);
		try (final Output output = new Output(os, 1_024)) {
			output.writeLong(LEGACY_2026_2_UID);
			output.writeInt(ENTITY_INDEX_PK);
			output.writeVarLong(STORAGE_PART_PK, true);
			output.writeVarInt(this.keyCompressor.getId(ATTRIBUTE_KEY), true);
			this.kryo.writeClass(output, valueType);
			output.writeVarInt(values.length, true);
			for (int i = 0; i < values.length; i++) {
				this.kryo.writeObject(output, values[i]);
				output.writeVarInt(RECORD_ID, false);
				output.writeVarInt(cardinalities[i], true);
			}
		}
		return os.toByteArray();
	}

	/**
	 * Reads a part the way storage does — through the registered `SerialVersionBasedSerializer`, so the uid in
	 * the stream decides which reader parses it.
	 */
	@Nonnull
	private AttributeCardinalityIndexStoragePart readPart(@Nonnull byte[] bytes) {
		try (final Input input = new Input(bytes)) {
			return this.kryo.readObject(input, AttributeCardinalityIndexStoragePart.class);
		}
	}

	/**
	 * Stores a part with the current serializer and reads it straight back, through the same registration the
	 * catalog uses, so the uid it stamps is the one the reader dispatches on.
	 */
	@Nonnull
	private AttributeCardinalityIndexStoragePart roundTripThroughCurrentFormat(
		@Nonnull AttributeCardinalityIndexStoragePart part
	) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(1_024);
		try (final Output output = new Output(os, 1_024)) {
			this.kryo.writeObject(output, part);
		}
		return readPart(os.toByteArray());
	}

}
