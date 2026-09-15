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

package io.evitadb.index.fulltext.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the M7 correctness invariant of the Slovak stemmer pair against the whole `sk_SK` Hunspell lexicon
 * (~265k headwords) rather than against the fixture — because the fixture already produced one false positive:
 * the first three matrix runs concluded the folded port had **no** fold-ambiguity at all, and the first sweep
 * of this lexicon falsified that with three word classes the fixture never carried (`-ín`/`-ína` vs the
 * possessive `in` and `i`-stems vs the soft plurals — resolved by removing the possessive and by the
 * stem-final-`i` trim; `-óm` vs the `om` ending, `ié` loanwords vs the `ie`-shortening and `-téka` nominals vs
 * the `ek`-epenthesis — resolved as switches the M7 query forks).
 *
 * The invariant: for every dictionary word `w`, `fold(SlovakStemmer(w))` — the folded image of the index-side
 * term — must be **contained in** the union of `FoldedSlovakStemmer(fold(w))` over all four switch positions,
 * which is exactly the term set the M7 hypothesis query emits. A violating word is a fold-ambiguity not yet
 * covered by any fork.
 *
 * **What this does and does not cover.** The `.dic` file carries dictionary headwords (lemmas), not inflected
 * forms — Lucene's Hunspell support can stem but not expand affixes — so this test proves the *identity
 * property* over the full lexicon, while the recall/precision numbers of the matrix remain bound to the
 * fixture's inflected forms until a full-form list is obtained (see the measurement record).
 *
 * Entries containing non-letters (hyphenated compounds, abbreviations with dots) are skipped: the
 * `StandardTokenizer` would split them into multiple tokens and the per-word comparison would compare
 * apples to token soup. The skipped share is reported.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Slovak folded stemmer — fold-commutation over the whole sk_SK lexicon")
@Tag(io.evitadb.test.TestTags.ENGINE)
@Tag(io.evitadb.test.TestTags.FULLTEXT)
class SlovakFoldedStemmerLexiconTest {

	@Test
	@DisplayName("The hypothesis set covers the folded index term for every dictionary word")
	void shouldCoverAccentedStemsOverWholeLexicon() throws IOException {
		final SlovakStemmer accented = new SlovakStemmer();
		// every switch combination, exactly what the M7 hypothesis query emits
		final List<UnaryOperator<String>> hypotheses = new ArrayList<>(8);
		for (final boolean omEnding : new boolean[]{true, false}) {
			for (final boolean ieShortening : new boolean[]{true, false}) {
				for (final boolean epenthesis : new boolean[]{true, false}) {
					final FoldedSlovakStemmer stemmer =
						new FoldedSlovakStemmer(omEnding, ieShortening, epenthesis);
					hypotheses.add(word -> LexiconCoverageSweep.stem(word, stemmer));
				}
			}
		}

		final LexiconCoverageSweep.Result result = LexiconCoverageSweep.sweep(
			"/fulltext/hunspell/sk_SK.dic",
			SlovakFoldedStemmerLexiconTest::fold,
			word -> {
				final char[] buffer = word.toCharArray();
				final int length = accented.stem(buffer, buffer.length);
				return fold(new String(buffer, 0, length));
			},
			hypotheses
		);

		System.out.println(result.summary("sk_SK"));
		assertTrue(
			result.tested() > 100_000,
			"The lexicon is supposed to provide six figures of test words; got " + result.tested() + "."
		);
		assertTrue(
			result.uncoveredCount() == 0,
			"Every uncovered word is a fold-ambiguity no current fork covers - the folded port needs a new "
				+ "switch for it:\n" + String.join("\n", result.uncovered())
		);
	}

	/**
	 * Folds diacritics the way the production `ASCIIFoldingFilter` does for the Slovak alphabet — NFD
	 * decomposition covers every Slovak letter (unlike Polish `ł`, Slovak has no stroked letter).
	 *
	 * @param text text to fold
	 * @return folded text
	 */
	@Nonnull
	private static String fold(@Nonnull String text) {
		return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
	}

}
