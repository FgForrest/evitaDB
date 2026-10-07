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

package io.evitadb.test.fulltext;

import javax.annotation.Nonnull;

/**
 * Reference edit-distance implementations for tests and spikes: the textbook dynamic programmes, written for
 * clarity and used as oracles, never as production code.
 *
 * Two metrics are offered, because the choice decides what a trigram index can guarantee (see the typo-tolerance
 * notes on `attributeContains`):
 *
 * - **Levenshtein** — insert, delete, substitute. An adjacent swap costs two.
 * - **Restricted Damerau–Levenshtein** (optimal string alignment) — the same plus an adjacent swap for one,
 *   which is what Lucene's `LevenshteinAutomata(..., withTranspositions = true)` implements.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class EditDistances {

	private EditDistances() {
	}

	/**
	 * Distance between two whole strings over code points.
	 *
	 * @param a              first string
	 * @param b              second string
	 * @param transpositions whether an adjacent swap counts as one edit (Damerau) or two (Levenshtein)
	 * @return the edit distance
	 */
	public static int distance(@Nonnull String a, @Nonnull String b, boolean transpositions) {
		final int[] x = a.codePoints().toArray();
		final int[] y = b.codePoints().toArray();
		final int[][] d = new int[x.length + 1][y.length + 1];
		for (int i = 0; i <= x.length; i++) {
			d[i][0] = i;
		}
		for (int j = 0; j <= y.length; j++) {
			d[0][j] = j;
		}
		for (int i = 1; i <= x.length; i++) {
			for (int j = 1; j <= y.length; j++) {
				final int cost = x[i - 1] == y[j - 1] ? 0 : 1;
				d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
				if (transpositions && i > 1 && j > 1 && x[i - 1] == y[j - 2] && x[i - 2] == y[j - 1]) {
					d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
				}
			}
		}
		return d[x.length][y.length];
	}

	/**
	 * Restricted Damerau–Levenshtein distance over code points; shorthand for `distance(a, b, true)`.
	 */
	public static int osaDistance(@Nonnull String a, @Nonnull String b) {
		return distance(a, b, true);
	}

	/**
	 * Approximate substring matching (Sellers 1980): true when some substring of `text` is within `maxEdits` of
	 * `pattern`. The dynamic programme runs with a free start and a free end in the text; three rows are kept so
	 * that the optional transposition can look two text positions back.
	 *
	 * @param text           the value searched, as code points
	 * @param pattern        the pattern, as code points
	 * @param maxEdits       the distance bound
	 * @param transpositions whether an adjacent swap counts as one edit
	 * @return whether a substring of `text` is within `maxEdits` of `pattern`
	 */
	public static boolean approximatelyContains(
		@Nonnull int[] text, @Nonnull int[] pattern, int maxEdits, boolean transpositions
	) {
		final int m = pattern.length;
		int[] twoBack = new int[m + 1];
		int[] previous = new int[m + 1];
		int[] current = new int[m + 1];
		for (int i = 0; i <= m; i++) {
			previous[i] = i;
		}
		// an empty text can only match by deleting the whole pattern
		if (previous[m] <= maxEdits) {
			return true;
		}
		for (int j = 0; j < text.length; j++) {
			final int character = text[j];
			current[0] = 0;
			for (int i = 1; i <= m; i++) {
				final int cost = pattern[i - 1] == character ? 0 : 1;
				int best = Math.min(Math.min(previous[i] + 1, current[i - 1] + 1), previous[i - 1] + cost);
				if (transpositions && i > 1 && j > 0
					&& pattern[i - 1] == text[j - 1] && pattern[i - 2] == character) {
					best = Math.min(best, twoBack[i - 2] + 1);
				}
				current[i] = best;
			}
			if (current[m] <= maxEdits) {
				return true;
			}
			final int[] recycled = twoBack;
			twoBack = previous;
			previous = current;
			current = recycled;
		}
		return false;
	}
}
