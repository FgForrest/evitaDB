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

import io.evitadb.comparator.LocalizedStringComparator;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.store.catalog.Migration_2026_2.ScaledSortValues;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic, offline coverage of the delicate NFD filter re-key transform that the v5→v6 migration
 * ({@link Migration_2026_2#rekeyHistogramPointsToNfd}) performs. The end-to-end
 * {@code EvitaBackwardCompatibilityTest} downloads real old-version catalogs over the network and is the integration
 * oracle; this test pins the pure normalize / merge / re-order logic — the single most delicate migration step — so it
 * can be verified without network access.
 *
 * String forms are written with explicit escapes so precomposed and decomposed accents are unambiguous in source:
 * `"é"` is the precomposed `é` (one code point U+00E9), `"é"` is its NFD decomposition (`e` + combining
 * acute accent, U+0065 U+0301). Both render identically.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(STORAGE)
@Tag(SERIALIZATION)
@DisplayName("Migration_2026_2 — NFD filter re-key transform (v5→v6)")
@SuppressWarnings("removal") // the migration interface is @Deprecated(forRemoval); testing it is intentional
class Migration_2026_2_Test {

	/** Precomposed `é` — a single code point U+00E9 (the raw form a pre-v6 filter index would have stored). */
	private static final String PRECOMPOSED = "é";
	/** NFD decomposition of `é` — `e` + combining acute accent (U+0065 U+0301). */
	private static final String DECOMPOSED = "e\u0301";

	/** Natural String ordering, the comparator a non-localized filter index uses. */
	private static final Comparator<Serializable> NATURAL = (a, b) -> ((String) a).compareTo((String) b);

	@Test
	@DisplayName("returns null for ASCII-only keys (NFD is the identity, nothing to rewrite)")
	void shouldReturnNullForAsciiOnlyKeys() {
		final ValueToRecordBitmap[] points = {point("apple", 1), point("banana", 2, 3)};
		assertNull(Migration_2026_2.rekeyHistogramPointsToNfd(points, NATURAL));
	}

	@Test
	@DisplayName("decomposes a precomposed accent into its canonical NFD form")
	void shouldDecomposePrecomposedToNfd() {
		final ValueToRecordBitmap[] points = {point(PRECOMPOSED, 1)};
		final ValueToRecordBitmap[] rekeyed = Migration_2026_2.rekeyHistogramPointsToNfd(points, NATURAL);
		assertNotNull(rekeyed);
		assertEquals(1, rekeyed.length);
		assertEquals(DECOMPOSED, rekeyed[0].getValue());
		assertArrayEquals(new int[]{1}, rekeyed[0].getRecordIds().getArray());
	}

	@Test
	@DisplayName("merges precomposed and decomposed forms of the same value into one bucket, unioning records")
	void shouldMergeNfdCollidingBuckets() {
		final ValueToRecordBitmap[] points = {point(PRECOMPOSED, 1), point(DECOMPOSED, 2)};
		final ValueToRecordBitmap[] rekeyed = Migration_2026_2.rekeyHistogramPointsToNfd(points, NATURAL);
		assertNotNull(rekeyed);
		assertEquals(1, rekeyed.length, "the two canonically-equivalent buckets must collapse into one");
		assertEquals(DECOMPOSED, rekeyed[0].getValue());
		assertArrayEquals(new int[]{1, 2}, rekeyed[0].getRecordIds().getArray());
	}

	@Test
	@DisplayName("keeps the output strictly monotone under the comparator after NFD reorders codepoints")
	void shouldReSortUnderComparatorAfterNfd() {
		// raw order is "z" then precomposed 'é'; under NATURAL ordering the decomposed "é" sorts BEFORE "z"
		final ValueToRecordBitmap[] points = {point("z", 1), point(PRECOMPOSED, 2)};
		final ValueToRecordBitmap[] rekeyed = Migration_2026_2.rekeyHistogramPointsToNfd(points, NATURAL);
		assertNotNull(rekeyed);
		assertEquals(2, rekeyed.length);
		assertTrue(
			NATURAL.compare(rekeyed[0].getValue(), rekeyed[1].getValue()) < 0,
			"re-keyed points must stay strictly increasing under the index comparator"
		);
		assertEquals(DECOMPOSED, rekeyed[0].getValue());
		assertEquals("z", rekeyed[1].getValue());
	}

	@Test
	@DisplayName("works with a localized collator comparator (the localized-attribute path)")
	void shouldHandleLocalizedComparator() {
		@SuppressWarnings({"unchecked", "rawtypes"})
		final Comparator<Serializable> localized =
			(Comparator<Serializable>) (Comparator) new LocalizedStringComparator(new Locale("cs"));
		// both inputs normalize to the same NFD key, so they merge regardless of the comparator
		final ValueToRecordBitmap[] points = {point(PRECOMPOSED, 7), point(DECOMPOSED, 9)};
		final ValueToRecordBitmap[] rekeyed = Migration_2026_2.rekeyHistogramPointsToNfd(points, localized);
		assertNotNull(rekeyed);
		assertEquals(1, rekeyed.length);
		assertArrayEquals(new int[]{7, 9}, rekeyed[0].getRecordIds().getArray());
	}

	@Nonnull
	private static ValueToRecordBitmap point(@Nonnull Serializable value, @Nonnull int... recordIds) {
		return new ValueToRecordBitmap(value, recordIds);
	}

	/**
	 * Offline coverage of the `BigDecimal` filter histogram re-key transform
	 * ({@link Migration_2026_2#rekeyHistogramPointsToScaledInt}): scaling at varying decimal places, merge of values
	 * that collapse to the same scaled int, natural-Integer ordering, idempotency on already-scaled keys, and the loud
	 * failure on an int overflow.
	 */
	@Nested
	@DisplayName("BigDecimal filter re-key transform (v5→v6)")
	class BigDecimalFilterRekey {

		@Test
		@DisplayName("scales BigDecimal keys to their order-preserving int at places=2")
		void shouldScaleBigDecimalAtTwoPlaces() {
			final ValueToRecordBitmap[] points = {
				point(new BigDecimal("1.50"), 1),
				point(new BigDecimal("2.25"), 2, 3)
			};
			final ValueToRecordBitmap[] rekeyed = Migration_2026_2.rekeyHistogramPointsToScaledInt(points, 2);
			assertEquals(2, rekeyed.length);
			assertEquals(150, rekeyed[0].getValue());
			assertEquals(225, rekeyed[1].getValue());
			assertArrayEquals(new int[]{1}, rekeyed[0].getRecordIds().getArray());
			assertArrayEquals(new int[]{2, 3}, rekeyed[1].getRecordIds().getArray());
		}

		@Test
		@DisplayName("scales BigDecimal keys to a whole int at places=0")
		void shouldScaleBigDecimalAtZeroPlaces() {
			final ValueToRecordBitmap[] points = {
				point(new BigDecimal("3"), 5),
				point(new BigDecimal("10"), 6)
			};
			final ValueToRecordBitmap[] rekeyed = Migration_2026_2.rekeyHistogramPointsToScaledInt(points, 0);
			assertEquals(2, rekeyed.length);
			assertEquals(3, rekeyed[0].getValue());
			assertEquals(10, rekeyed[1].getValue());
		}

		@Test
		@DisplayName("merges two BigDecimals that collapse to the same scaled int into one bucket, unioning records")
		void shouldMergeCollidingScaledInts() {
			// at places=1 both 1.50 and 1.49 round HALF_UP to scaled int 15
			final ValueToRecordBitmap[] points = {
				point(new BigDecimal("1.49"), 1),
				point(new BigDecimal("1.50"), 2)
			};
			final ValueToRecordBitmap[] rekeyed = Migration_2026_2.rekeyHistogramPointsToScaledInt(points, 1);
			assertEquals(1, rekeyed.length, "the two values collapsing to the same scaled int must merge");
			assertEquals(15, rekeyed[0].getValue());
			assertArrayEquals(new int[]{1, 2}, rekeyed[0].getRecordIds().getArray());
		}

		@Test
		@DisplayName("keeps the rebuilt array strictly increasing under natural Integer order, including negatives")
		void shouldOrderUnderNaturalIntegerOrder() {
			final ValueToRecordBitmap[] points = {
				point(new BigDecimal("-2.00"), 1),
				point(new BigDecimal("0.00"), 2),
				point(new BigDecimal("3.50"), 3)
			};
			final ValueToRecordBitmap[] rekeyed = Migration_2026_2.rekeyHistogramPointsToScaledInt(points, 2);
			assertEquals(3, rekeyed.length);
			assertEquals(-200, rekeyed[0].getValue());
			assertEquals(0, rekeyed[1].getValue());
			assertEquals(350, rekeyed[2].getValue());
		}

		@Test
		@DisplayName("passes already-scaled Integer keys through unchanged (idempotent re-run)")
		void shouldBeIdempotentOnIntegerKeys() {
			final ValueToRecordBitmap[] points = {point(150, 1), point(225, 2)};
			final ValueToRecordBitmap[] rekeyed = Migration_2026_2.rekeyHistogramPointsToScaledInt(points, 2);
			assertEquals(2, rekeyed.length);
			assertEquals(150, rekeyed[0].getValue());
			assertEquals(225, rekeyed[1].getValue());
			assertArrayEquals(new int[]{1}, rekeyed[0].getRecordIds().getArray());
			assertArrayEquals(new int[]{2}, rekeyed[1].getRecordIds().getArray());
		}

		@Test
		@DisplayName("fails loudly when a value overflows int at the schema's decimal places")
		void shouldFailLoudlyOnOverflow() {
			// 21474836.48 scaled by 10^2 = 2_147_483_648 = Integer.MAX_VALUE + 1 → overflow
			final ValueToRecordBitmap[] points = {point(new BigDecimal("21474836.48"), 1)};
			assertThrows(
				ArithmeticException.class,
				() -> Migration_2026_2.rekeyHistogramPointsToScaledInt(points, 2)
			);
		}
	}

	/**
	 * Offline coverage of the sort-only `BigDecimal` re-key transform
	 * ({@link Migration_2026_2#rekeySortedValuesToScaledInt}): the distinct value side is re-scaled to `Integer`, the
	 * sparse cardinality map is re-bucketed (cardinalities of values that collapse to the same scaled int summed), the
	 * "cardinality 1 is implied" convention is preserved, the transform is idempotent on already-scaled keys, and an
	 * int overflow fails loudly.
	 */
	@Nested
	@DisplayName("BigDecimal sort re-key transform (v5→v6)")
	class BigDecimalSortRekey {

		@Test
		@DisplayName("re-scales distinct sort values and keeps the sparse cardinality map at places=2")
		void shouldScaleSortValues() {
			final Serializable[] values = {new BigDecimal("1.50"), new BigDecimal("2.25")};
			final Map<Serializable, Integer> cardinalities = new HashMap<>();
			cardinalities.put(new BigDecimal("2.25"), 3);
			final ScaledSortValues scaled = Migration_2026_2.rekeySortedValuesToScaledInt(values, cardinalities, 2);
			assertArrayEquals(new Serializable[]{150, 225}, scaled.sortedRecordValues());
			assertEquals(1, scaled.valueCardinalities().size());
			assertEquals(3, scaled.valueCardinalities().get(225));
		}

		@Test
		@DisplayName("merges values that collapse to the same scaled int, summing their cardinalities")
		void shouldMergeCollidingSortValues() {
			// at places=1 both 1.49 and 1.50 scale to 15; their cardinalities (2 + 3) sum to 5
			final Serializable[] values = {new BigDecimal("1.49"), new BigDecimal("1.50")};
			final Map<Serializable, Integer> cardinalities = new HashMap<>();
			cardinalities.put(new BigDecimal("1.49"), 2);
			cardinalities.put(new BigDecimal("1.50"), 3);
			final ScaledSortValues scaled = Migration_2026_2.rekeySortedValuesToScaledInt(values, cardinalities, 1);
			assertArrayEquals(new Serializable[]{15}, scaled.sortedRecordValues());
			assertEquals(5, scaled.valueCardinalities().get(15));
		}

		@Test
		@DisplayName("collapses an implicit-1 value with another into a >1 bucket")
		void shouldSumImplicitCardinalityOne() {
			// 1.49 has implicit cardinality 1 (absent from the map), 1.50 has 1; together 2 → must appear in the map
			final Serializable[] values = {new BigDecimal("1.49"), new BigDecimal("1.50")};
			final ScaledSortValues scaled = Migration_2026_2.rekeySortedValuesToScaledInt(values, new HashMap<>(), 1);
			assertArrayEquals(new Serializable[]{15}, scaled.sortedRecordValues());
			assertEquals(2, scaled.valueCardinalities().get(15));
		}

		@Test
		@DisplayName("passes already-scaled Integer sort values through unchanged (idempotent re-run)")
		void shouldBeIdempotentOnIntegerSortValues() {
			final Serializable[] values = {150, 225};
			final Map<Serializable, Integer> cardinalities = new HashMap<>();
			cardinalities.put(225, 2);
			final ScaledSortValues scaled = Migration_2026_2.rekeySortedValuesToScaledInt(values, cardinalities, 2);
			assertArrayEquals(new Serializable[]{150, 225}, scaled.sortedRecordValues());
			assertEquals(2, scaled.valueCardinalities().get(225));
		}

		@Test
		@DisplayName("fails loudly when a sort value overflows int at the schema's decimal places")
		void shouldFailLoudlyOnSortOverflow() {
			final Serializable[] values = {new BigDecimal("21474836.48")};
			assertThrows(
				ArithmeticException.class,
				() -> Migration_2026_2.rekeySortedValuesToScaledInt(values, new HashMap<>(), 2)
			);
		}
	}
}
