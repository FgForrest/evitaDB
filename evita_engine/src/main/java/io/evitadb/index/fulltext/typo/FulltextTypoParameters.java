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

import javax.annotation.Nonnull;
import javax.annotation.concurrent.Immutable;

/**
 * Submitted-search typo policy. Lengths refer to code points of the folded query term; each stem variant uses its
 * own length. The caller supplies text already normalized into the dictionary's analysis space.
 *
 * @param minLengthOneTypo shortest term permitted one edit, at least one
 * @param minLengthTwoTypos shortest term permitted two edits, at least `minLengthOneTypo`
 * @param nonFuzzyPrefix number of leading code points that cannot be edited, clamped to the term length by the walker
 * @param transpositions whether an adjacent swap counts as one edit
 * @param digitsExact whether any Unicode digit disables typo matching for the term
 */
@Immutable
public record FulltextTypoParameters(
	int minLengthOneTypo,
	int minLengthTwoTypos,
	int nonFuzzyPrefix,
	boolean transpositions,
	boolean digitsExact
) {
	/**
	 * Defaults for a submitted search: no edit below 4 code points, one edit from 4, two from 8; the first code point
	 * frozen, an adjacent swap counted as one edit, and terms containing a digit matched exactly.
	 */
	public static final FulltextTypoParameters DEFAULT = new FulltextTypoParameters(4, 8, 1, true, true);

	/**
	 * Validates the policy. Edit budgets are always bounded by two.
	 *
	 * @param minLengthOneTypo shortest term permitted one edit
	 * @param minLengthTwoTypos shortest term permitted two edits
	 * @param nonFuzzyPrefix number of initial code points to freeze
	 * @param transpositions whether adjacent swaps cost one edit
	 * @param digitsExact whether terms containing a digit must match exactly
	 * @throws IllegalArgumentException when `minLengthOneTypo` is below one, `minLengthTwoTypos` is below
	 * `minLengthOneTypo`, or `nonFuzzyPrefix` is negative
	 */
	public FulltextTypoParameters {
		if (minLengthOneTypo < 1) {
			throw new IllegalArgumentException("minLengthOneTypo must be positive.");
		}
		if (minLengthTwoTypos < minLengthOneTypo) {
			throw new IllegalArgumentException("minLengthTwoTypos must be at least minLengthOneTypo.");
		}
		if (nonFuzzyPrefix < 0) {
			throw new IllegalArgumentException("nonFuzzyPrefix must not be negative.");
		}
	}

	/**
	 * Computes the submitted-search edit budget, including the Unicode digit rule. The length counts every code point
	 * of the term, the frozen prefix included.
	 *
	 * @param foldedTerm the query term after its profile's folding, or one of its stem variants
	 * @return permitted edit distance, always zero, one or two
	 */
	public int maxEdits(@Nonnull String foldedTerm) {
		int length = 0;
		for (int offset = 0; offset < foldedTerm.length();) {
			final int codePoint = foldedTerm.codePointAt(offset);
			if (this.digitsExact && Character.isDigit(codePoint)) {
				return 0;
			}
			length++;
			offset += Character.charCount(codePoint);
		}
		if (length < this.minLengthOneTypo) {
			return 0;
		}
		if (length < this.minLengthTwoTypos) {
			return 1;
		}
		return 2;
	}
}
