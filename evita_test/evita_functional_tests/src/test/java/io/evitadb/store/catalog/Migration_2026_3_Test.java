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

import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.cardinality.AttributeCardinalityIndex.AttributeCardinalityKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Covers the counter re-key transform that upgrades a catalog from storage protocol 6 to 7.
 *
 * The transform's whole job is to put a persisted {@link io.evitadb.index.cardinality.AttributeCardinalityIndex}
 * into the form the fixed engine would have built: keys canonicalized through the same normalizer the shared
 * value tree uses, and — the part that actually repairs the defect — the counts of keys that collapse onto one
 * key SUMMED rather than overwritten. A count left at one would drop the shared entry on the first owner's
 * departure, which is precisely the corruption the migration exists to stop.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Migration_2026_3 — attribute cardinality counter re-key (v6→v7)")
@Tag(STORAGE)
@Tag(SERIALIZATION)
class Migration_2026_3_Test {

	private static final int OWNER = 42;

	@Nonnull
	private static Map<AttributeCardinalityKey, Integer> counters(@Nonnull Object... valueThenCount) {
		final Map<AttributeCardinalityKey, Integer> result = new LinkedHashMap<>();
		for (int i = 0; i < valueThenCount.length; i += 2) {
			result.put(
				new AttributeCardinalityKey(OWNER, (Serializable) valueThenCount[i]),
				(Integer) valueThenCount[i + 1]
			);
		}
		return result;
	}

	@Test
	@DisplayName("returns null when every key is already canonical, so nothing is rewritten")
	void shouldReturnNullWhenKeysAreAlreadyCanonical() {
		final Function<Object, Serializable> normalizer = FilterIndex.getNormalizer(Integer.class, 0);
		assertNull(
			Migration_2026_3.rekeyCardinalities(counters(1, 3, 2, 1), normalizer),
			"an identity normalizer must leave the part untouched"
		);
	}

	@Test
	@DisplayName("scales BigDecimal keys to their order-preserving int at the schema's places")
	void shouldScaleBigDecimalKeys() {
		final Function<Object, Serializable> normalizer = FilterIndex.getNormalizer(BigDecimal.class, 2);
		final Map<AttributeCardinalityKey, Integer> rekeyed = Migration_2026_3.rekeyCardinalities(
			counters(new BigDecimal("1.25"), 1, new BigDecimal("3.50"), 2), normalizer
		);
		assertEquals(
			Map.of(
				new AttributeCardinalityKey(OWNER, 125), 1,
				new AttributeCardinalityKey(OWNER, 350), 2
			),
			rekeyed,
			"each BigDecimal must become its scaled int, counts carried across unchanged"
		);
	}

	@Test
	@DisplayName("SUMS the counts of two values that collapse onto one key — the repair itself")
	void shouldSumCountsOfCollidingBigDecimalKeys() {
		final Function<Object, Serializable> normalizer = FilterIndex.getNormalizer(BigDecimal.class, 0);
		final Map<AttributeCardinalityKey, Integer> rekeyed = Migration_2026_3.rekeyCardinalities(
			counters(new BigDecimal("1.2"), 1, new BigDecimal("1.4"), 1), normalizer
		);
		assertEquals(
			Map.of(new AttributeCardinalityKey(OWNER, 1), 2),
			rekeyed,
			"two owners over one shared entry must become one key counting two, or the entry is dropped early"
		);
	}

	@Test
	@DisplayName("folds canonically-equivalent strings onto one NFD key, summing their counts")
	void shouldFoldNfdEquivalentStrings() {
		final Function<Object, Serializable> normalizer = FilterIndex.getNormalizer(String.class, 0);
		final Map<AttributeCardinalityKey, Integer> rekeyed = Migration_2026_3.rekeyCardinalities(
			counters("café", 1, "café", 3), normalizer
		);
		assertEquals(
			1, rekeyed.size(),
			"the precomposed and decomposed spellings share one NFD key"
		);
		assertEquals(
			4, rekeyed.values().iterator().next(),
			"their counts must be summed, not overwritten"
		);
	}

	@Test
	@DisplayName("leaves an ASCII-only string counter untouched")
	void shouldLeaveAsciiStringsUntouched() {
		final Function<Object, Serializable> normalizer = FilterIndex.getNormalizer(String.class, 0);
		assertNull(
			Migration_2026_3.rekeyCardinalities(counters("code-1", 1, "code-2", 2), normalizer),
			"NFD is the identity on ASCII, so there is nothing to rewrite"
		);
	}

	@Test
	@DisplayName("folds two offsets denoting one instant onto a single key")
	void shouldFoldOffsetsDenotingOneInstant() {
		final Function<Object, Serializable> normalizer = FilterIndex.getNormalizer(OffsetDateTime.class, 0);
		final Map<AttributeCardinalityKey, Integer> rekeyed = Migration_2026_3.rekeyCardinalities(
			counters(
				OffsetDateTime.of(2026, 9, 21, 12, 0, 0, 0, ZoneOffset.ofHours(2)), 1,
				OffsetDateTime.of(2026, 9, 21, 10, 0, 0, 0, ZoneOffset.UTC), 1
			),
			normalizer
		);
		assertEquals(
			1, rekeyed.size(),
			"the index key discards the offset, so both spellings of one instant share a key"
		);
		assertEquals(
			2, rekeyed.values().iterator().next(),
			"their counts must be summed"
		);
	}

	@Test
	@DisplayName("keeps distinct owners apart even when their values collide")
	void shouldKeepDistinctOwnersApart() {
		final Function<Object, Serializable> normalizer = FilterIndex.getNormalizer(BigDecimal.class, 0);
		final Map<AttributeCardinalityKey, Integer> source = new LinkedHashMap<>();
		source.put(new AttributeCardinalityKey(1, new BigDecimal("1.2")), 1);
		source.put(new AttributeCardinalityKey(2, new BigDecimal("1.4")), 1);
		assertEquals(
			Map.of(
				new AttributeCardinalityKey(1, 1), 1,
				new AttributeCardinalityKey(2, 1), 1
			),
			Migration_2026_3.rekeyCardinalities(source, normalizer),
			"the record id is part of the key — two owners must not be merged into one counter"
		);
	}

}
