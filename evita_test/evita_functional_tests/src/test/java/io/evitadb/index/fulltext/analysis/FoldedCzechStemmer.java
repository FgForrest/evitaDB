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
 * **Prototype, test scope only.** Lucene's `CzechStemmer` with its ending tables rewritten into
 * diacritics-folded space, so the stemmer can run **after** folding instead of before it.
 *
 * This is mechanism M1 of the prior-art survey
 * (`documentation/adr/2026-08-24-fulltext-search-lucene-vs-inhouse/prototypes/p5-prior-art-accent-vs-stemming.md`),
 * the pattern Lucene already uses for German, Spanish, French, Italian, Portuguese and Greek: a language
 * normalization filter runs first and the stemmer is written to expect its output. Czech is the outlier that has
 * no such filter and a stemmer whose tables are spelled with accents — which is what forces evitaDB to fold
 * last and lose every accent-typed query whose ending the stemmer can then no longer recognise.
 *
 * **The port is mostly mechanical.** Within each removal-length group the folded endings collide onto
 * themselves and the collisions are harmless because the folded pair always has the same length as the accented
 * one: `ích`/`ich` → `ich`, `ěmi`/`emi` → `emi`, `ých` → `ych`, `ém`/`em` → `em`. The final-vowel switch folds
 * cleanly (`a e i o u ů y á é í ý ě` → `a e i o u y`), and the two consonant rewrites `[cč]→k` and `[zž]→h`
 * become `c→k` and `z→h`, which is **exactly** equivalent — the accented and unaccented input were already
 * mapped to the same output by the original.
 *
 * **Two rules cannot be folded mechanically, and each is a genuine language judgment.** Both are therefore
 * switchable, so the cost of enabling them can be measured rather than argued:
 *
 * - **{@link #palatalizationRewrite}** — the original rewrites `čt`→`ck` and `št`→`sk` so that the Czech
 *   consonant alternation converges (`český`/`čeští`). Folded, `št` is indistinguishable from a genuine `st`,
 *   so applying the rule rewrites `cest` (*cesta*, road) to `cesk` and merges it with `česk` (*český*, Czech).
 * - **{@link #penultimateVowelShift}** — the original rewrites a penultimate `ů` to `o` so that `dům`/`domu`
 *   converge. Folded, `ů` is indistinguishable from `u`, so applying the rule rewrites `ruk` (*ruka*, hand) to
 *   `rok` (*rok*, year) and `buk` (*buk*, beech) to `bok` (*bok*, side).
 *
 * **NOTE**: input is expected to be lowercased **and diacritics-folded** — the exact opposite of
 * `CzechStemmer`'s contract. Fed accented text it silently stops recognising most endings, which is the same
 * failure mode in mirror image.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
final class FoldedCzechStemmer {

	/**
	 * Whether the `ct`→`ck` / `st`→`sk` consonant rewrite is applied. See the class javadoc — it buys the Czech
	 * stem-final consonant alternation and costs every word whose stem genuinely ends in `-st`.
	 *
	 * **On the name.** "Palatalization" here labels the *historical cause*, not a live process: palatalization
	 * stopped being productive in Czech centuries ago, and what the rule undoes is the fossilized
	 * **morphophonemic alternation** it left behind — `sk`↔`št` and `ck`↔`čt` before the soft endings, now
	 * conditioned by which ending is attached rather than by what sound follows. Czech grammars call these
	 * simply *alternace*. The word is kept because it is the standard Slavic-linguistics shorthand for this
	 * family of alternations (`k`~`c`~`č`, `h`~`z`~`ž`, `ch`~`š`~`s`) and because this codebase already spends
	 * it on the same family — see `FulltextAnalyzerTest#shouldRewritePalatalizedConsonant`, which names the
	 * `ž`→`h` rule that way. Note the alternation is genuinely palatal on both segments despite the spelling:
	 * `-št-` before `í` is `[ʃc]`, i.e. `š` plus `ť`.
	 */
	private final boolean palatalizationRewrite;
	/**
	 * Whether the penultimate `u`→`o` rewrite is applied. See the class javadoc — it buys `dům`/`domu` and
	 * costs every word with a penultimate `u` in its stem.
	 */
	private final boolean penultimateVowelShift;
	/**
	 * Whether the entries of the neuter `-at-` paradigm are kept: `atech`, `atum`, `ata`, `aty` and `at`.
	 *
	 * **This is a third ambiguity the survey did not predict** — found by measurement rather than by reading
	 * the tables. In the **original accented `CzechStemmer`** those five entries are spelled with a short `a`
	 * and exist for the neuter `-ata` paradigm (`kuřata` → `kuř`); words in accented `-át` never match them,
	 * because `endsWith` compares characters exactly and `á` is not `a` — `kabát`/`kabáty`/`kabátech` are
	 * stripped by the final-vowel and `ech` rules instead and converge on `kabát`. **Folded**, `-át` and `-at`
	 * are one string, so the same entries also eat the far more common `-át` masculines: `kabaty` is stripped
	 * to `kab` by the `aty` entry while `kabatu` stems to `kabat`, and that paradigm splits down the middle.
	 *
	 * **Both switch positions lose recall — this flag selects a trade, it does not fix one.** Keeping the
	 * entries splits `kabát` (8 ordered pairs on the fixture); dropping them splits the `-ata` neuters'
	 * singular from their plural — `rajče` → `rajk` while `rajčata`/`rajčat` → `rajcat` — because nothing
	 * strips the `at` any more (4 ordered pairs). Which loss is smaller is a corpus-frequency question; on
	 * the fixture's e-commerce vocabulary dropping wins by 4 pairs at equal precision. The first three
	 * measurement runs called dropping "free" only because the vocabulary then contained no `-ata` neuter to
	 * commit the drop's cost.
	 */
	private final boolean neuterAtParadigm;
	/**
	 * Whether the epenthetic `-e-` removal is applied (`sluchatek` → `sluchatk`).
	 *
	 * **This is a fourth folded ambiguity, found by the asymmetric mechanism M7 rather than by any symmetric
	 * chain.** In the original accented stemmer the rule keys on a literal `e`, so `dřevěn` (whose penultimate
	 * letter is `ě`) is left alone while `sluchátek` loses its genuinely epenthetic `e`. Folded, `ě` and `e`
	 * are one letter, so the rule also fires on `dreven` → `drevn`. A **symmetric** folded chain never shows
	 * this — both sides mis-stem identically and still converge — but the moment the index side stems accented
	 * text (M7), the folded query stem `drevn` misses the index term `dreven`. Switchable so that a
	 * hypothesis-emitting query chain can fork on it; every symmetric configuration keeps it enabled.
	 */
	private final boolean epentheticERemoval;

	/**
	 * Creates a stemmer with the three ambiguous rules independently enabled or disabled and the epenthetic
	 * `-e-` removal kept enabled, which is the behaviour every symmetric chain wants.
	 *
	 * @param palatalizationRewrite whether to apply `ct`→`ck` / `st`→`sk`
	 * @param penultimateVowelShift whether to apply penultimate `u`→`o`
	 * @param neuterAtParadigm      whether the `atech`/`atum`/`ata`/`aty`/`at` entries are stripped
	 */
	FoldedCzechStemmer(
		boolean palatalizationRewrite,
		boolean penultimateVowelShift,
		boolean neuterAtParadigm
	) {
		this(palatalizationRewrite, penultimateVowelShift, neuterAtParadigm, true);
	}

	/**
	 * Creates a stemmer with all four ambiguous rules independently enabled or disabled.
	 *
	 * @param palatalizationRewrite whether to apply `ct`→`ck` / `st`→`sk`
	 * @param penultimateVowelShift whether to apply penultimate `u`→`o`
	 * @param neuterAtParadigm      whether the `atech`/`atum`/`ata`/`aty`/`at` entries are stripped
	 * @param epentheticERemoval    whether the epenthetic `-e-` removal is applied
	 */
	FoldedCzechStemmer(
		boolean palatalizationRewrite,
		boolean penultimateVowelShift,
		boolean neuterAtParadigm,
		boolean epentheticERemoval
	) {
		this.palatalizationRewrite = palatalizationRewrite;
		this.penultimateVowelShift = penultimateVowelShift;
		this.neuterAtParadigm = neuterAtParadigm;
		this.epentheticERemoval = epentheticERemoval;
	}

	/**
	 * Stems an input buffer of diacritics-folded, lowercased Czech text.
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
	 * Strips a case ending. The folded image of `CzechStemmer#removeCase`, with duplicate entries collapsed.
	 *
	 * @param s   input buffer
	 * @param len current length
	 * @return length after the ending was stripped
	 */
	private int removeCase(@Nonnull char[] s, int len) {
		if (len > 7 && this.neuterAtParadigm && endsWith(s, len, "atech")) {
			return len - 5;
		}

		// "ětem"/"etem" fold together; "atům" folds to "atum"
		if (len > 6
			&& (endsWith(s, len, "etem") || (this.neuterAtParadigm && endsWith(s, len, "atum")))) {
			return len - 4;
		}

		if (len > 5
			&& (endsWith(s, len, "ech")
			|| endsWith(s, len, "ich")     // ich + ích
			|| endsWith(s, len, "eho")     // ého
			|| endsWith(s, len, "emi")     // ěmi + emi
			|| endsWith(s, len, "emu")     // ému
			|| endsWith(s, len, "ete")     // ěte + ete
			|| endsWith(s, len, "eti")     // ěti + eti
			|| endsWith(s, len, "iho")     // ího + iho
			|| endsWith(s, len, "imi")     // ími
			|| endsWith(s, len, "imu")     // ímu + imu
			|| endsWith(s, len, "ach")     // ách
			|| (this.neuterAtParadigm && endsWith(s, len, "ata"))
			|| (this.neuterAtParadigm && endsWith(s, len, "aty"))
			|| endsWith(s, len, "ych")     // ých
			|| endsWith(s, len, "ama")
			|| endsWith(s, len, "ami")
			|| endsWith(s, len, "ove")     // ové
			|| endsWith(s, len, "ovi")
			|| endsWith(s, len, "ymi"))) { // ými
			return len - 3;
		}

		if (len > 4
			&& (endsWith(s, len, "em")     // em + ém
			|| endsWith(s, len, "es")
			|| endsWith(s, len, "im")      // ím
			|| endsWith(s, len, "um")      // ům
			|| (this.neuterAtParadigm && endsWith(s, len, "at"))
			|| endsWith(s, len, "am")      // ám
			|| endsWith(s, len, "os")
			|| endsWith(s, len, "us")
			|| endsWith(s, len, "ym")      // ým
			|| endsWith(s, len, "mi")
			|| endsWith(s, len, "ou"))) {
			return len - 2;
		}

		if (len > 3) {
			// the accented vowels of the original switch all fold onto these five plus 'y'
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
	 * Strips a possessive suffix. The folded image of `CzechStemmer#removePossessives`; only `ův` changes,
	 * folding to `uv`.
	 *
	 * @param s   input buffer
	 * @param len current length
	 * @return length after the suffix was stripped
	 */
	private static int removePossessives(@Nonnull char[] s, int len) {
		if (len > 5 && (endsWith(s, len, "ov") || endsWith(s, len, "in") || endsWith(s, len, "uv"))) {
			return len - 2;
		}
		return len;
	}

	/**
	 * Normalizes the stem's final consonants and vowels. The folded image of `CzechStemmer#normalize`, with the
	 * two ambiguous rewrites gated on this instance's flags.
	 *
	 * @param s   input buffer
	 * @param len current length
	 * @return length after normalization
	 */
	private int normalize(@Nonnull char[] s, int len) {
		if (this.palatalizationRewrite) {
			// folded `čt` and a genuine `ct` are the same string here, likewise `št` and `st`
			if (endsWith(s, len, "ct")) {
				s[len - 2] = 'c';
				s[len - 1] = 'k';
				return len;
			}
			if (endsWith(s, len, "st")) {
				s[len - 2] = 's';
				s[len - 1] = 'k';
				return len;
			}
		}

		// exactly equivalent to the original: it mapped both `c` and `č` to `k`, and both `z` and `ž` to `h`
		switch (s[len - 1]) {
			case 'c':
				s[len - 1] = 'k';
				return len;
			case 'z':
				s[len - 1] = 'h';
				return len;
			default:
				// no final-consonant rewrite applies
				break;
		}

		// the epenthetic -e- rule. Folded input reaches it more often than accented input did, because `ě`
		// folds to `e` - which is what makes `dřevěný` and its bare typing `dreveny` converge in a SYMMETRIC
		// chain, and equally what makes the folded stem `drevn` miss an accented-stemmer index term `dreven`
		// in an asymmetric one - see the epentheticERemoval flag
		if (this.epentheticERemoval && len > 1 && s[len - 2] == 'e') {
			s[len - 2] = s[len - 1];
			return len - 1;
		}

		if (this.penultimateVowelShift && len > 2 && s[len - 2] == 'u') {
			s[len - 2] = 'o';
			return len;
		}

		return len;
	}

	/**
	 * Applies {@link FoldedCzechStemmer} to a token stream, honouring {@link KeywordAttribute} exactly as
	 * `CzechStemFilter` does so that it can sit behind a `KeywordRepeatFilter` in a two-lane chain.
	 */
	static final class FoldedCzechStemFilter extends TokenFilter {

		/**
		 * The stemmer applied to every non-keyword token.
		 */
		@Nonnull private final FoldedCzechStemmer stemmer;
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
		 * @param input   stream to filter
		 * @param stemmer stemmer to apply
		 */
		FoldedCzechStemFilter(@Nonnull TokenStream input, @Nonnull FoldedCzechStemmer stemmer) {
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

}
