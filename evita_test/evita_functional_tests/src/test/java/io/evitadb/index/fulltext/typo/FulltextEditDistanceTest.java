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

package io.evitadb.index.fulltext.typo;

import io.evitadb.test.fulltext.EditDistances;
import org.junit.jupiter.api.*;
import java.util.Random;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.*;

/** Independent matrix oracle for the engine's three-row distance computation. */
@Tag(ENGINE)
@Tag(FULLTEXT)
@DisplayName("Fulltext restricted Damerau distance")
class FulltextEditDistanceTest {
	/** Oracle equivalence. */
	@Nested
	@DisplayName("Oracle equivalence")
	class Equivalence {
		/** Matches EditDistances on random Unicode pairs and every prefix with either swap policy. */
		@Test
		@DisplayName("Matches EditDistances on random Unicode pairs and every prefix with either swap policy")
		void shouldMatchOracle() {
			final Random random = new Random(187);
			for (int round = 0; round < 2000; round++) {
				final String query = word(random);
				final FulltextEditDistance workspace = new FulltextEditDistance(query.codePoints().toArray());
				final String key = word(random);
				for (final boolean transpositions : new boolean[]{true, false}) {
					assertEquals(EditDistances.distance(query, key, transpositions),
						workspace.distance(key, transpositions, false));
					assertEquals(prefixDistance(query, key, transpositions),
						workspace.distance(key, transpositions, true));
				}
			}
		}

		/**
		 * Optimal string alignment counts an adjacent swap as one edit but never edits inside a swapped pair, so `ca`
		 * to `abc` costs three where unrestricted Damerau distance would find two.
		 */
		@Test
		@DisplayName("Counts an adjacent swap as one edit but never edits inside a swapped pair")
		void shouldCountSwapAsOneEditButNeverEditInsideTheSwappedPair() {
			assertEquals(1, new FulltextEditDistance("ab".codePoints().toArray()).distance("ba", true, false));
			assertEquals(3, new FulltextEditDistance("ca".codePoints().toArray()).distance("abc", true, false));
		}
	}
	/** Query-bound row reuse and unfinished-word suffixes. */
	@Nested
	@DisplayName("Reusable distance workspace")
	class Workspace {
		/** Repeated calls must not inherit any earlier key's row state, including after an early exit. */
		@Test
		@DisplayName("One workspace agrees with the oracle across different lengths, modes and swap policies")
		void shouldReuseRowsAcrossDifferentLengths() {
			final String query = "b𐐨ack";
			final FulltextEditDistance workspace = new FulltextEditDistance(query.codePoints().toArray());
			final String[] keys = {"", "b", "b𐐨ack", "b𐐨cak", "x", "b𐐨ack" + "z".repeat(40), "b𐐨ac", "𐐨"};
			for (int repeat = 0; repeat < 4; repeat++) {
				for (final String key : keys) {
					for (final boolean transpositions : new boolean[]{false, true}) {
						assertEquals(EditDistances.distance(query, key, transpositions),
							workspace.distance(key, transpositions, false));
						assertEquals(prefixDistance(query, key, transpositions),
							workspace.distance(key, transpositions, true));
					}
				}
			}
		}

		/** Long completions preserve exact and fuzzy prefix distances, and finite calls still count their suffix. */
		@Test
		@DisplayName("A million-character continuation preserves zero and nonzero open-ended minima")
		void shouldHandleLongSuffix() {
			final String suffix = "b".repeat(1_000_000);
			final String exact = "black" + suffix;
			final String mistyped = "blaxk" + suffix;
			final FulltextEditDistance workspace = new FulltextEditDistance("black".codePoints().toArray());
			final FulltextEditDistance empty = new FulltextEditDistance(new int[0]);
			for (final boolean transpositions : new boolean[]{false, true}) {
				assertEquals(0, workspace.distance(exact, transpositions, true));
				assertEquals(1, workspace.distance(mistyped, transpositions, true));
				assertEquals(0, empty.distance(exact, transpositions, true));
				assertEquals(128, workspace.distance("black" + "b".repeat(128), transpositions, false));
				assertEquals(0, workspace.distance("blackberry", transpositions, true));
				assertEquals(2, workspace.distance("bla", transpositions, false));
			}
		}
	}

	/** Computes the independent minimum over every code-point prefix, including the empty prefix. */
	private static int prefixDistance(String query, String key, boolean transpositions) {
		int best = EditDistances.distance(query, key, transpositions);
		for (int end = 0; end < key.length();) {
			best = Math.min(best, EditDistances.distance(query, key.substring(0, end), transpositions));
			end += Character.charCount(key.codePointAt(end));
		}
		return best;
	}

	/** Produces short strings, including empty strings and supplementary letters. */
	private static String word(Random random) {
		final int[] alphabet = "abcě𐐨ａ".codePoints().toArray();
		final StringBuilder result = new StringBuilder(16);
		for (int i = random.nextInt(10); i > 0; i--) {
			result.appendCodePoint(alphabet[random.nextInt(alphabet.length)]);
		}
		return result.toString();
	}
}
