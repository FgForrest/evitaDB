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
 * The **branching** form of the M7 Slovak hypothesis set: one walk over {@link FoldedSlovakStemmer}'s tables
 * that forks only at a fold-ambiguous rule whose pattern actually matches, producing the exact union of the
 * eight (2³) switch configurations the Slovak M7 chain and lexicon sweep run — **without** a surface
 * hypothesis, which the Slovak set alone does not carry (its sweep reached zero uncovered on the three rule
 * forks; see the SK/PL/RO record, §9.4/§9.8).
 *
 * The same two structural properties as the Czech walk hold ({@link BranchingFoldedCzechStemmer}): the case
 * and possessive stages never mutate the buffer, and every `normalize` mutation confines itself to the final
 * two characters of its result — including the one wrinkle Czech does not have, the **chained** rules: the
 * `ie`-shortening does not return but falls through into the epenthesis check, so one path can apply both
 * (`stoliciek` → `stolicek` → `stolick`). The composition still only rewrites the result's final two
 * characters over an untouched prefix, so hypotheses stay `(length, final-two-characters)` triples and the
 * walk allocates nothing. Flag consistency needs no bookkeeping here: `omEnding` guards one table entry,
 * `ieShortening` and `epenthesis` one `normalize` site each, and the chained lane evaluates each at most once.
 *
 * Bound: ≤ 2 case lengths (the `om` fork) × deterministic possessive × ≤ 4 `normalize` outcomes
 * (`ie`-on with/without epenthesis, `ie`-off with epenthesis, identity) = 8.
 *
 * **Prototype, test scope only.** Not thread-safe — one instance per stream.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
final class BranchingFoldedSlovakStemmer implements BranchingStemmer {

	/**
	 * Hard bound on distinct hypotheses per word — see the class javadoc for the derivation.
	 */
	private static final int MAX_HYPOTHESES = 8;

	/**
	 * Lengths of the deduplicated hypotheses of the current word.
	 */
	private final int[] lengths = new int[MAX_HYPOTHESES];
	/**
	 * Character at index `length - 2` of each hypothesis, `0` when the hypothesis is shorter.
	 */
	private final char[] penultimates = new char[MAX_HYPOTHESES];
	/**
	 * Character at index `length - 1` of each hypothesis, `0` when the hypothesis is empty.
	 */
	private final char[] lasts = new char[MAX_HYPOTHESES];
	/**
	 * Number of valid entries in the three hypothesis arrays.
	 */
	private int count;

	/**
	 * Distinct post-possessive stem lengths of the current word — the `om` fork at most doubles the single
	 * deterministic lane.
	 */
	private final int[] stemLengths = new int[2];
	/**
	 * Number of valid entries in {@link #stemLengths}.
	 */
	private int stemCount;

	@Override
	public int hypothesize(@Nonnull char[] s, int len) {
		this.count = 0;
		this.stemCount = 0;

		// stages 1+2 - case endings and the possessive; pure length computations, only `om` forks
		caseOutcomes(s, len);

		// stage 3 - normalization per surviving length; every mutation confines to the result's final two
		// characters, so each outcome is a (length, final-two-characters) triple over the original word
		for (int i = 0; i < this.stemCount; i++) {
			normalizeOutcomes(s, this.stemLengths[i]);
		}
		return this.count;
	}

	@Override
	public int length(int hypothesisIndex) {
		return this.lengths[hypothesisIndex];
	}

	@Override
	public int materialize(int hypothesisIndex, @Nonnull char[] originalWord, @Nonnull char[] destination) {
		final int length = this.lengths[hypothesisIndex];
		System.arraycopy(originalWord, 0, destination, 0, length);
		if (length >= 2) {
			destination[length - 2] = this.penultimates[hypothesisIndex];
		}
		if (length >= 1) {
			destination[length - 1] = this.lasts[hypothesisIndex];
		}
		return length;
	}

	/**
	 * Walks the case-ending table — an unguarded match ends the walk for every configuration, the guarded `om`
	 * records the stripped length (switch on) and falls through (switch off, landing on the final-vowel rule,
	 * which leaves the `m` alone) — and pushes each outcome through the unguarded possessive strip.
	 *
	 * @param s   input buffer, read only
	 * @param len length of the word
	 */
	private void caseOutcomes(@Nonnull char[] s, int len) {
		if (len > 6 && (endsWith(s, len, "iach") || endsWith(s, len, "ovia"))) {
			addStemLength(s, len - 4);
			return;
		}
		if (len > 5
			&& (endsWith(s, len, "ych")
			|| endsWith(s, len, "ymi")
			|| endsWith(s, len, "eho")
			|| endsWith(s, len, "emu")
			|| endsWith(s, len, "imi")
			|| endsWith(s, len, "ach")
			|| endsWith(s, len, "iam")
			|| endsWith(s, len, "ami")
			|| endsWith(s, len, "ovi")
			|| endsWith(s, len, "och"))) {
			addStemLength(s, len - 3);
			return;
		}
		if (len > 4) {
			if (endsWith(s, len, "am")
				|| endsWith(s, len, "ov")
				|| endsWith(s, len, "ou")
				|| endsWith(s, len, "ej")
				|| endsWith(s, len, "ia")
				|| endsWith(s, len, "ie")
				|| endsWith(s, len, "iu")
				|| endsWith(s, len, "ym")
				|| endsWith(s, len, "im")) {
				addStemLength(s, len - 2);
				return;
			}
			if (endsWith(s, len, "om")) {
				// omEnding on strips; off falls through - the final vowel rule cannot fire on the `m`
				addStemLength(s, len - 2);
			}
		}
		addStemLength(s, removeFinalVowel(s, len));
	}

	/**
	 * The unguarded final-vowel strip — mirror of `FoldedSlovakStemmer#removeCase`'s last tier.
	 *
	 * @param s   input buffer, read only
	 * @param len current length
	 * @return length after the vowel was stripped
	 */
	private static int removeFinalVowel(@Nonnull char[] s, int len) {
		if (len > 3 && isVowel(s[len - 1])) {
			return len - 1;
		}
		return len;
	}

	/**
	 * Applies the unguarded `ov` possessive strip to a case outcome and records the resulting stem length,
	 * deduplicated.
	 *
	 * @param s          input buffer, read only
	 * @param caseLength length after case-ending removal
	 */
	private void addStemLength(@Nonnull char[] s, int caseLength) {
		final int length = caseLength > 5 && endsWith(s, caseLength, "ov") ? caseLength - 2 : caseLength;
		for (int i = 0; i < this.stemCount; i++) {
			if (this.stemLengths[i] == length) {
				return;
			}
		}
		this.stemLengths[this.stemCount++] = length;
	}

	/**
	 * Records every reachable outcome of `FoldedSlovakStemmer#normalize` for a stem of the given length. The
	 * `i`-trim and the `c`→`k` rewrite are unguarded (the rewrite terminal); the `ie`-shortening forks and its
	 * applied branch **chains** into the epenthesis check on the shortened tail, while its skipped branch
	 * reaches the same check on the original tail — one guarded evaluation of each flag per path.
	 *
	 * @param s          input buffer, read only
	 * @param stemLength length of the stem after case and possessive stripping
	 */
	private void normalizeOutcomes(@Nonnull char[] s, int stemLength) {
		if (stemLength == 0) {
			// `stem()` skips normalize entirely on an empty stem
			addRaw(0, (char) 0, (char) 0);
			return;
		}

		// the unguarded i-trim, part of every configuration
		final int l = stemLength > 3 && s[stemLength - 1] == 'i' ? stemLength - 1 : stemLength;

		// the unguarded, terminal c->k rewrite - no configuration reaches the guarded rules past it
		if (s[l - 1] == 'c') {
			addRaw(l, l >= 2 ? s[l - 2] : (char) 0, 'k');
			return;
		}

		// the ie-shortening fork; its applied branch rewrites the tail to [.., 'e', old-last] at length l-1
		// and chains into the epenthesis check on that rewritten tail
		if (l > 4 && s[l - 3] == 'i' && s[l - 2] == 'e' && !isVowel(s[l - 1])) {
			if (l - 1 > 4 && s[l - 1] == 'k') {
				// the shortened tail is [.., 'e', 'k'], so the chained epenthesis pattern matches - fork on it
				addRaw(l - 2, s[l - 4], 'k');
				addRaw(l - 1, 'e', s[l - 1]);
			} else {
				addRaw(l - 1, 'e', s[l - 1]);
			}
			// fall through = the shortening switch off, reaching epenthesis on the original tail
		}

		// the epenthesis fork on the (unshortened) tail
		if (l > 4 && s[l - 1] == 'k' && (s[l - 2] == 'o' || s[l - 2] == 'e')) {
			addRaw(l - 1, s[l - 3], 'k');
		}

		// identity - every guarded rule off
		addRaw(l, l >= 2 ? s[l - 2] : (char) 0, s[l - 1]);
	}

	/**
	 * Tells whether the character is a folded Slovak vowel — mirror of `FoldedSlovakStemmer#isVowel`.
	 *
	 * @param c character to test
	 * @return true for `a`, `e`, `i`, `o`, `u`, `y`
	 */
	private static boolean isVowel(char c) {
		return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u' || c == 'y';
	}

	/**
	 * Records a hypothesis as its `(length, final-two-characters)` triple, deduplicated.
	 *
	 * @param length      hypothesis length
	 * @param penultimate character at `length - 2`, `0` when the hypothesis is shorter
	 * @param last        character at `length - 1`, `0` when the hypothesis is empty
	 */
	private void addRaw(int length, char penultimate, char last) {
		for (int i = 0; i < this.count; i++) {
			if (this.lengths[i] == length && this.penultimates[i] == penultimate && this.lasts[i] == last) {
				return;
			}
		}
		if (this.count == MAX_HYPOTHESES) {
			// unreachable by the bound in the class javadoc - a breach means the walk diverged from
			// FoldedSlovakStemmer and must fail loudly rather than drop a hypothesis
			throw new IllegalStateException(
				"More than " + MAX_HYPOTHESES + " hypotheses for one word - the branching walk has diverged "
					+ "from FoldedSlovakStemmer."
			);
		}
		this.lengths[this.count] = length;
		this.penultimates[this.count] = penultimate;
		this.lasts[this.count] = last;
		this.count++;
	}

}
