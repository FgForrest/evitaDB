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

import org.apache.lucene.util.automaton.LevenshteinAutomata;

import javax.annotation.Nonnull;

/**
 * The threshold function of the typo-tolerance analysis (`typo-tolerance-fulltext-dictionary.md` §7.6): from the
 * length of a query word it decides how many edits the Levenshtein automaton may allow and how many leading
 * characters stay frozen. The decision is made once per word, before any expansion, and only these two numbers
 * reach `LevenshteinAutomata#toAutomaton(n, prefix)`.
 *
 * Lengths are counted in code points. Which string's length is passed in — the typed word or a stem hypothesis — is
 * the caller's choice, because that is exactly the open fork the measurements compare.
 *
 * @param minLengthOneTypo  the shortest word that may contain one typo
 * @param minLengthTwoTypos the shortest word that may contain two typos
 * @param nonFuzzyPrefix    number of leading characters in which no edit is allowed
 * @param digitsExact       whether a word containing a digit is matched exactly (codes, model numbers)
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
public record TypoThresholds(
	int minLengthOneTypo,
	int minLengthTwoTypos,
	int nonFuzzyPrefix,
	boolean digitsExact
) {
	/**
	 * The parameter set proposed in §7.1: no typo up to 3 characters, one from 4, two from 8, the first letter
	 * frozen, words with digits exact.
	 */
	public static final TypoThresholds PROPOSAL = new TypoThresholds(4, 8, 1, true);

	/**
	 * Validates the parameters.
	 *
	 * @param minLengthOneTypo  the shortest word that may contain one typo
	 * @param minLengthTwoTypos the shortest word that may contain two typos
	 * @param nonFuzzyPrefix    number of leading characters in which no edit is allowed
	 * @param digitsExact       whether a word containing a digit is matched exactly
	 */
	public TypoThresholds {
		if (minLengthOneTypo < 1) {
			throw new IllegalArgumentException("minLengthOneTypo must be positive, got " + minLengthOneTypo);
		}
		if (minLengthTwoTypos < minLengthOneTypo) {
			throw new IllegalArgumentException(
				"minLengthTwoTypos (" + minLengthTwoTypos + ") must not be below minLengthOneTypo (" +
					minLengthOneTypo + ")"
			);
		}
		if (nonFuzzyPrefix < 0) {
			throw new IllegalArgumentException("nonFuzzyPrefix must not be negative, got " + nonFuzzyPrefix);
		}
	}

	/**
	 * Decides the number of edits for a word of the given length.
	 *
	 * @param length    length of the word in code points
	 * @param hasDigit  whether the word contains a digit
	 * @return 0, 1 or 2 — never more than `LevenshteinAutomata` supports
	 */
	public int maxEdits(int length, boolean hasDigit) {
		if (hasDigit && this.digitsExact) {
			return 0;
		}
		final int edits = length < this.minLengthOneTypo ? 0 : length < this.minLengthTwoTypos ? 1 : 2;
		return Math.min(edits, LevenshteinAutomata.MAXIMUM_SUPPORTED_DISTANCE);
	}

	/**
	 * Decides the number of edits for a word.
	 *
	 * @param word the word, in the dictionary's normalization space
	 * @return 0, 1 or 2
	 */
	public int maxEdits(@Nonnull String word) {
		return maxEdits(word.codePointCount(0, word.length()), hasDigit(word));
	}

	/**
	 * Decides the frozen prefix for a word: the configured length, clamped to the word.
	 *
	 * @param word the word
	 * @return number of leading characters in which no edit is allowed
	 */
	public int frozenPrefix(@Nonnull String word) {
		return Math.min(this.nonFuzzyPrefix, word.codePointCount(0, word.length()));
	}

	/**
	 * Tells whether a word contains a digit.
	 *
	 * @param word the word
	 * @return true when any code point is a digit
	 */
	public static boolean hasDigit(@Nonnull String word) {
		for (int i = 0; i < word.length(); i++) {
			if (Character.isDigit(word.charAt(i))) {
				return true;
			}
		}
		return false;
	}
}
