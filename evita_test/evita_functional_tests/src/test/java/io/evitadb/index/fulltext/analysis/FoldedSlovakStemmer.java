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

import javax.annotation.Nonnull;

import static org.apache.lucene.analysis.util.StemmerUtil.endsWith;

/**
 * **Prototype, test scope only.** {@link SlovakStemmer} with its tables rewritten into **diacritics-folded
 * space**.
 *
 * Most of the rewrite is mechanical, and much of it folding performs outright: the rhythmic-law ending pairs
 * (`-ých`/`-ych`, `-ého`/`-eho`, …) are both real accented spellings with one action, so the folded table is
 * strictly smaller; the `ô`→`o` rewrite becomes the identity (`stôl` and `stol` are one folded string — where
 * Czech's `ů` folded onto ambiguous `u`, Slovak's alternation letter folds onto its own alternant); the
 * `c`/`č`→`k` rewrite folds onto `c`→`k`, exactly equivalent because the accented rule already mapped both
 * letters to one output; and there is no `št`→`sk` palatalization or neuter `-at-` entry to be ambiguous.
 *
 * **Three rules are genuinely ambiguous under folding — found by the sk_SK lexicon sweep, not by the fixture**
 * (the fixture's first runs reported the port as switch-free; a ~265k-word coverage test falsified that, which
 * is the strongest argument the record has for lexicon-scale property tests). Each is switchable so the cost of
 * each position can be measured, and the M7 hypothesis query forks them:
 *
 * - **{@link #omEnding}** — folded `om` is both the core instrumental/dative ending (`tričkom`) and the final
 *   syllable of the `-óm` nominals (`adenóm`, `agronóm`, `chróm`), which the accented table correctly leaves
 *   whole because `óm` is not `om`. Folded, the two are one string: stripping serves the declension and
 *   truncates the loanwords; not stripping protects the loanwords and abandons the ending.
 * - **{@link #ieShortening}** — the genitive-plural `ie`-shortening reads a literal short `ie` in accented
 *   space (`stoličiek`, `košieľ`) and never touches the `ié` loanwords (`bariéra`, `ateliér`, `premiéra`);
 *   folded, both are `ie` and the rule truncates the loanword class (`barier` → `barer`).
 * - **{@link #epenthesis}** — the epenthetic `e`-before-`k` removal reads a literal short `e` in accented space
 *   (`darček`, and the shortened `stoliček`) and never touches the `-téka`/`-ék` nominals (`bibliotéka`,
 *   `hypoték`); folded, both are `ek` and the rule truncates them (`bibliotek` → `bibliotk`).
 *
 * Two further folded hazards the sweep found were resolved outright rather than forked: the `in` possessive was
 * removed from both stemmers (its folded image ate the `-ín`/`-ína` loanword classes; see
 * {@link SlovakStemmer}), and the soft-plural `iam`/`iach` vs `i`-stem + `ám`/`ách` ambiguity **dissolved**
 * once both stemmers began trimming a stem-final `i` in `normalize()` — with the trim, stripping `iam` whole
 * (`ulic`) and stripping only the hard `ám` (`ulici` → `ulic`) converge on one stem, so the two segmentations
 * stop being distinguishable outcomes.
 *
 * **NOTE**: input is expected to be lowercased **and diacritics-folded**.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
final class FoldedSlovakStemmer implements FoldedStemmer {

	/**
	 * Whether folded `om` is stripped as the instrumental/dative ending. See the class javadoc — it buys the
	 * core declension and costs the `-óm` nominals their final syllable.
	 */
	private final boolean omEnding;
	/**
	 * Whether the genitive-plural `ie`-shortening is applied. See the class javadoc — it buys the lengthened
	 * genitive plurals and costs the `ié` loanwords a letter.
	 */
	private final boolean ieShortening;
	/**
	 * Whether the epenthetic `e`/`o`-before-`k` removal is applied. See the class javadoc — it buys the
	 * `-ok`/`-ek` masculines and costs the `-téka`/`-ék` nominals a letter.
	 */
	private final boolean epenthesis;

	/**
	 * Creates a stemmer with the three fold-ambiguous rules independently enabled or disabled. All on is the
	 * native-morphology-first position a symmetric production chain would pick.
	 *
	 * @param omEnding     whether folded `om` is stripped as an ending
	 * @param ieShortening whether the genitive-plural `ie`-shortening is applied
	 * @param epenthesis   whether the epenthetic-vowel removal before a final `k` is applied
	 */
	FoldedSlovakStemmer(boolean omEnding, boolean ieShortening, boolean epenthesis) {
		this.omEnding = omEnding;
		this.ieShortening = ieShortening;
		this.epenthesis = epenthesis;
	}

	/**
	 * Stems an input buffer of diacritics-folded, lowercased Slovak text.
	 *
	 * @param s   input buffer
	 * @param len length of the input buffer
	 * @return length of the buffer after stemming
	 */
	@Override
	public int stem(@Nonnull char[] s, int len) {
		int length = removeCase(s, len);
		length = removePossessives(s, length);
		if (length > 0) {
			length = normalize(s, length);
		}
		return length;
	}

	/**
	 * Strips a case ending. The folded image of {@link SlovakStemmer}'s table with the rhythmic-law duplicates
	 * collapsed, and the two fold-ambiguous groups gated by the switches.
	 *
	 * @param s   input buffer
	 * @param len current length
	 * @return length after the ending was stripped
	 */
	private int removeCase(@Nonnull char[] s, int len) {
		if (len > 6
			&& (endsWith(s, len, "iach")
			|| endsWith(s, len, "ovia"))) {
			return len - 4;
		}

		if (len > 5
			&& (endsWith(s, len, "ych")     // ých + ych
			|| endsWith(s, len, "ymi")      // ými + ymi
			|| endsWith(s, len, "eho")      // ého + eho
			|| endsWith(s, len, "emu")      // ému + emu
			|| endsWith(s, len, "imi")      // ími + imi
			|| endsWith(s, len, "ach")      // ách + ach
			|| endsWith(s, len, "iam")
			|| endsWith(s, len, "ami")
			|| endsWith(s, len, "ovi")
			|| endsWith(s, len, "och"))) {
			return len - 3;
		}

		if (len > 4
			&& ((this.omEnding && endsWith(s, len, "om"))
			|| endsWith(s, len, "am")       // ám + am
			|| endsWith(s, len, "ov")
			|| endsWith(s, len, "ou")
			|| endsWith(s, len, "ej")      // ej + éj
			|| endsWith(s, len, "ia")
			|| endsWith(s, len, "ie")
			|| endsWith(s, len, "iu")
			|| endsWith(s, len, "ym")       // ým + ym
			|| endsWith(s, len, "im"))) {   // ím + im
			return len - 2;
		}

		if (len > 3) {
			// the accented vowels all fold onto these six
			switch (s[len - 1]) {
				case 'a':
				case 'e':
				case 'i':
				case 'o':
				case 'u':
				case 'y':
					return len - 1;
				default:
					// not a case ending - fall through to returning the length unchanged
					break;
			}
		}

		return len;
	}

	/**
	 * Strips the possessive suffix `ov` — the `in` possessive was removed from both stemmers by decision, see
	 * {@link SlovakStemmer#removePossessives}.
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
	 * Normalizes the stem's final letters. The folded image of {@link SlovakStemmer#normalize}: the `c`→`k`
	 * rewrite is exactly equivalent (the original already mapped `c` and `č` to one output), the `ô`→`o`
	 * rewrite is dropped because folding already performed it, and the shortening and epenthetic rules read the
	 * same plain letters in both spaces.
	 *
	 * @param s   input buffer
	 * @param len current length
	 * @return length after normalization
	 */
	private int normalize(@Nonnull char[] s, int len) {
		// i-final stems are trimmed to the consonant, mirroring the accented stemmer - see
		// SlovakStemmer#normalize for why this dissolves the soft-plural fold-ambiguity
		if (len > 3 && s[len - 1] == 'i') {
			len--;
		}

		if (s[len - 1] == 'c') {
			s[len - 1] = 'k';
			return len;
		}

		// the genitive-plural stem lengthening (stolička/stoličiek, košeľa/košieľ, hodinky/hodiniek): the
		// lengthened ie shortens back to e before a final consonant, and the result chains into the epenthetic
		// rule below (stoličiek -> stoliček -> stoličk). In folded space the rule is ambiguous - see the
		// ieShortening switch. The length guard keeps `biel`-sized stems whole
		if (this.ieShortening && len > 4 && s[len - 3] == 'i' && s[len - 2] == 'e' && !isVowel(s[len - 1])) {
			s[len - 3] = 'e';
			s[len - 2] = s[len - 1];
			len--;
		}

		// the epenthetic o/e before a final k (náramok/náramku, darček/darčeka); ambiguous in folded space -
		// see the epenthesis switch. The length guard keeps small non-epenthetic words (rok, vlk) whole
		if (this.epenthesis && len > 4 && s[len - 1] == 'k' && (s[len - 2] == 'o' || s[len - 2] == 'e')) {
			s[len - 2] = 'k';
			return len - 1;
		}

		return len;
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
