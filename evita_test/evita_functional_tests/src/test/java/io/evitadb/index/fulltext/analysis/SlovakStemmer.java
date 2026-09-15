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

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.KeywordAttribute;

import javax.annotation.Nonnull;
import java.io.IOException;

import static org.apache.lucene.analysis.util.StemmerUtil.endsWith;

/**
 * **Prototype, test scope only — an in-house Slovak light stemmer, built because nothing exists to adopt.**
 *
 * The SK/PL/RO survey (`documentation/adr/2026-08-24-fulltext-search-lucene-vs-inhouse/prototypes/
 * p5-prior-art-sk-pl-ro.md`, §6) found no Slovak stemmer anywhere: not in Lucene, not in Snowball, not in any
 * surveyed engine. This class is the survey's recommended answer — an accented-space light stemmer on the
 * architecture of Lucene's `CzechStemmer` (exact `endsWith` case-ending tables, a possessive pass, a small
 * `normalize()` for stem-final alternations), with tables authored fresh against Slovak declension paradigms.
 * Czech and Slovak are structurally parallel, but the tables are deliberately **not** the Czech ones:
 *
 * - adjective endings appear in **both rhythmic-law variants** (`-ých`/`-ych`, `-ého`/`-eho`, `-ým`/`-ym`, …)
 *   because both are correct Slovak spellings depending on the preceding syllable (`pekných` vs `krásnych`);
 * - the noun tables carry the Slovak plural set (`-och`, `-ov`, `-ám`/`-am`, `-ách`/`-ach`, `-iam`, `-iach`,
 *   `-ovia`) rather than the Czech one (`-ů`, `-ům`, `-ové`);
 * - `normalize()` rewrites the penultimate `ô` to `o` (`stôl`/`stola`, `kôň`/`koňa` — the Slovak analogue of
 *   Czech `ů`→`o`), rewrites a final `c`/`č` to `k` so the masculine-animate `k`→`c` alternation converges
 *   (`zákazník`/`zákazníci`), and removes the epenthetic `o`/`e` before a final `k` (`náramok`/`náramku`,
 *   `darček`/`darčeka`) — guarded by length so `rok` stays whole;
 * - the `in` possessive is absent — see {@link #removePossessives};
 * - the Czech `št`→`sk`/`čt`→`ck` palatalization rewrite is **absent**: Slovak declension does not commit that
 *   alternation (`český`→`českí`, not `*čeští`), so the rule — and the fold-ambiguity it caused in Czech — has
 *   nothing to buy here;
 * - the neuter `-at-` paradigm entries (`dievča`/`dievčatá`) are **deliberately omitted**: the paradigm is rare
 *   in e-commerce text and its entries were the worst fold-ambiguity of the Czech port; the omission's cost is
 *   measured by the fixture rather than guessed.
 *
 * **NOTE**: input is expected to be lowercased and **accented** — the `CzechStemmer` contract. The folded-space
 * counterpart is {@link FoldedSlovakStemmer}.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
final class SlovakStemmer {

	/**
	 * Stems an input buffer of lowercased, accented Slovak text.
	 *
	 * @param s   input buffer
	 * @param len length of the input buffer
	 * @return length of the buffer after stemming
	 */
	int stem(@Nonnull char[] s, int len) {
		int length = removeCase(s, len);
		length = removePossessives(s, length);
		if (length > 0) {
			length = normalize(s, length);
		}
		return length;
	}

	/**
	 * Strips a case ending, longest candidates first, with the `CzechStemmer` length guards (a stripped ending
	 * always leaves at least a three-character stem).
	 *
	 * @param s   input buffer
	 * @param len current length
	 * @return length after the ending was stripped
	 */
	private static int removeCase(@Nonnull char[] s, int len) {
		if (len > 6
			&& (endsWith(s, len, "iach")
			|| endsWith(s, len, "ovia"))) {
			return len - 4;
		}

		if (len > 5
			&& (endsWith(s, len, "ých")
			|| endsWith(s, len, "ych")
			|| endsWith(s, len, "ými")
			|| endsWith(s, len, "ymi")
			|| endsWith(s, len, "ého")
			|| endsWith(s, len, "eho")
			|| endsWith(s, len, "ému")
			|| endsWith(s, len, "emu")
			|| endsWith(s, len, "ími")
			|| endsWith(s, len, "imi")
			|| endsWith(s, len, "ách")
			|| endsWith(s, len, "ach")
			|| endsWith(s, len, "iam")
			|| endsWith(s, len, "ami")
			|| endsWith(s, len, "ámi")
			|| endsWith(s, len, "ovi")
			|| endsWith(s, len, "och")
			|| endsWith(s, len, "ôch"))) {
			return len - 3;
		}

		if (len > 4
			&& (endsWith(s, len, "om")
			|| endsWith(s, len, "ám")
			|| endsWith(s, len, "am")
			|| endsWith(s, len, "ov")
			|| endsWith(s, len, "ou")
			|| endsWith(s, len, "ej")
			|| endsWith(s, len, "éj")
			|| endsWith(s, len, "ia")
			|| endsWith(s, len, "ie")
			|| endsWith(s, len, "iu")
			|| endsWith(s, len, "ým")
			|| endsWith(s, len, "ym")
			|| endsWith(s, len, "ím")
			|| endsWith(s, len, "im"))) {
			return len - 2;
		}

		if (len > 3) {
			switch (s[len - 1]) {
				case 'a':
				case 'á':
				case 'ä':
				case 'e':
				case 'é':
				case 'i':
				case 'í':
				case 'o':
				case 'ó':
				case 'ô':
				case 'u':
				case 'ú':
				case 'y':
				case 'ý':
					return len - 1;
				default:
					// not a case ending - fall through to returning the length unchanged
					break;
			}
		}

		return len;
	}

	/**
	 * Strips the possessive suffix `ov` (`otcov`). The `in` possessive (`matkin`) the Czech architecture also
	 * carries is **deliberately absent**: the sk_SK lexicon sweep showed its folded image eats the enormous
	 * `-ín`/`-ína` loanword classes (`vitamín`, `vitrína` → `vitam`, `vitr`) while the possessive itself is
	 * marginal in e-commerce text — the rule was removed from both stemmers rather than made switchable,
	 * because its benefit could not justify a fork (see the measurement record's Slovak ledger).
	 *
	 * @param s   input buffer
	 * @param len current length
	 * @return length after the suffix was stripped
	 */
	private static int removePossessives(@Nonnull char[] s, int len) {
		if (len > 5 && endsWith(s, len, "ov")) {
			return len - 2;
		}
		return len;
	}

	/**
	 * Normalizes the stem's final letters so alternating paradigm members land on one stem. See the class
	 * javadoc for what each rewrite buys.
	 *
	 * @param s   input buffer
	 * @param len current length
	 * @return length after normalization
	 */
	private static int normalize(@Nonnull char[] s, int len) {
		// i-final stems (kategóri-a/kategóri-ám, chemikáli-a) are trimmed to the consonant so that both
		// segmentations the light stemmer cannot tell apart (ulic+iam vs kategóri+ám) land on one stem - the
		// discovery of the sk_SK lexicon sweep that dissolved the soft-plural fold-ambiguity outright: with the
		// trim, stripping `iam` whole and stripping only `ám` converge on the same string
		if (len > 3 && s[len - 1] == 'i') {
			len--;
		}

		// the masculine-animate k→c alternation (zákazník/zákazníci): both letters land on `k`. Mirrors the
		// CzechStemmer treatment of c/č - rewriting the whole paradigm consistently is what makes it converge
		if (s[len - 1] == 'c' || s[len - 1] == 'č') {
			s[len - 1] = 'k';
			return len;
		}

		// the ô diphthong alternation (stôl/stola, kôň/koňa): the nominative's ô is the oblique stems' o
		if (len > 2 && s[len - 2] == 'ô') {
			s[len - 2] = 'o';
			return len;
		}

		// the genitive-plural stem lengthening (stolička/stoličiek, košeľa/košieľ, hodinky/hodiniek): the
		// lengthened ie shortens back to e before a final consonant, and the result chains into the epenthetic
		// rule below (stoličiek -> stoliček -> stoličk). Added by run 2 of the Slovak matrix - run 1 showed
		// every residual convergence miss was this one class. The length guard keeps `biel`-sized stems whole
		if (len > 4 && s[len - 3] == 'i' && s[len - 2] == 'e' && !isVowel(s[len - 1])) {
			s[len - 3] = 'e';
			s[len - 2] = s[len - 1];
			len--;
		}

		// the epenthetic o/e before a final k (náramok/náramku, darček/darčeka); the length guard keeps small
		// non-epenthetic words (rok, vlk) whole
		if (len > 4 && s[len - 1] == 'k' && (s[len - 2] == 'o' || s[len - 2] == 'e')) {
			s[len - 2] = 'k';
			return len - 1;
		}

		return len;
	}

	/**
	 * Applies {@link SlovakStemmer} to a token stream, honouring {@link KeywordAttribute} exactly as the Lucene
	 * language stem filters do.
	 */
	static final class SlovakStemFilter extends TokenFilter {

		/**
		 * The stemmer applied to every non-keyword token.
		 */
		@Nonnull private final SlovakStemmer stemmer;
		/**
		 * Term text of the current token.
		 */
		@Nonnull private final CharTermAttribute termAttribute = addAttribute(CharTermAttribute.class);
		/**
		 * Marks tokens that must not be stemmed.
		 */
		@Nonnull private final KeywordAttribute keywordAttribute = addAttribute(KeywordAttribute.class);

		/**
		 * Creates the filter.
		 *
		 * @param input   stream to filter, already lowercased, accents kept
		 * @param stemmer stemmer to apply
		 */
		SlovakStemFilter(@Nonnull TokenStream input, @Nonnull SlovakStemmer stemmer) {
			super(input);
			this.stemmer = stemmer;
		}

		@Override
		public boolean incrementToken() throws IOException {
			if (this.input.incrementToken()) {
				if (!this.keywordAttribute.isKeyword()) {
					this.termAttribute.setLength(
						this.stemmer.stem(this.termAttribute.buffer(), this.termAttribute.length())
					);
				}
				return true;
			}
			return false;
		}

	}


	/**
	 * Tells whether the character is a folded Slovak vowel — the shortening rule must not fire on a stem whose
	 * last letter is vocalic, since a vowel there would have been stripped as an ending already.
	 *
	 * @param c character to test
	 * @return true for `a`, `e`, `i`, `o`, `u`, `y`
	 */
	private static boolean isVowel(char c) {
		return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u' || c == 'y';
	}

}
