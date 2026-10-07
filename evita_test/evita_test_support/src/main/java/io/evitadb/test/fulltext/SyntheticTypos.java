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
import javax.annotation.Nullable;
import java.util.Random;

/**
 * Injects one synthetic typo into a word: a substitution, insertion, deletion or adjacent transposition at a given
 * position. The replacement and inserted letters are drawn from the folded Latin alphabet with a caller-supplied
 * {@link Random}, so a seeded run produces the same corpus every time.
 *
 * Typos are applied to the **folded** word: the query chain folds before it stems, so a typo on the accented word
 * and on its folded image reach the same terms, and a "substitution" of `a` for `á` is not a typo at all.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class SyntheticTypos {
	/**
	 * Letters a typo may introduce.
	 */
	private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz";

	private SyntheticTypos() {
	}

	/**
	 * The four edit operations of the restricted Damerau–Levenshtein metric.
	 */
	public enum TypoKind {
		/**
		 * One letter replaced by a different one.
		 */
		SUBSTITUTION,
		/**
		 * One letter inserted before the position.
		 */
		INSERTION,
		/**
		 * The letter at the position removed.
		 */
		DELETION,
		/**
		 * The letter at the position swapped with the following one.
		 */
		TRANSPOSITION
	}

	/**
	 * Applies one typo.
	 *
	 * @param word     the folded word
	 * @param kind     the edit operation
	 * @param position index of the affected letter (for an insertion: the letter the new one goes before)
	 * @param random   source of the introduced letter
	 * @return the mistyped word, or null when the typo is impossible or a no-op at that position (a transposition of
	 *         two equal letters, a deletion leaving the word empty)
	 */
	@Nullable
	public static String apply(@Nonnull String word, @Nonnull TypoKind kind, int position, @Nonnull Random random) {
		if (position < 0 || position >= word.length()) {
			return null;
		}
		final StringBuilder result = new StringBuilder(word.length() + 1);
		switch (kind) {
			case SUBSTITUTION -> {
				final char original = word.charAt(position);
				char replacement;
				do {
					replacement = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
				} while (replacement == original);
				result.append(word, 0, position).append(replacement).append(word, position + 1, word.length());
			}
			case INSERTION -> result.append(word, 0, position)
				.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())))
				.append(word, position, word.length());
			case DELETION -> {
				if (word.length() < 2) {
					return null;
				}
				result.append(word, 0, position).append(word, position + 1, word.length());
			}
			case TRANSPOSITION -> {
				if (position + 1 >= word.length() || word.charAt(position) == word.charAt(position + 1)) {
					return null;
				}
				result.append(word, 0, position)
					.append(word.charAt(position + 1))
					.append(word.charAt(position))
					.append(word, position + 2, word.length());
			}
			default -> throw new IllegalStateException("Unexpected typo kind " + kind);
		}
		final String mistyped = result.toString();
		return mistyped.equals(word) ? null : mistyped;
	}
}
