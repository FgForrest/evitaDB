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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Comparator;
import java.util.Locale;

import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
	private static final String PRECOMPOSED = "\u00e9";
	/** NFD decomposition of `é` — `e` + combining acute accent (U+0065 U+0301). */
	private static final String DECOMPOSED = "e\u0301";

	/** Natural String ordering, the comparator a non-localized filter index uses. */
	private static final Comparator<Serializable> NATURAL = (a, b) -> ((String) a).compareTo((String) b);

	@Nonnull
	private static ValueToRecordBitmap point(@Nonnull String value, @Nonnull int... recordIds) {
		return new ValueToRecordBitmap(value, recordIds);
	}

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
}
