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
 * The **branching** form of the M7 Czech hypothesis set: one walk over {@link FoldedCzechStemmer}'s ending
 * tables that forks only at a fold-ambiguous rule whose pattern **actually matches** the current word, instead
 * of running all 1,024 switch configurations of the flat union and deduplicating their outputs. Produces —
 * provably, see the equivalence test — the exact same set of distinct hypotheses as
 * {@link FoldedCzechStemmer#allHypotheses()} run through {@link HypothesisStemFilter}, surface hypothesis
 * included, at a small constant number of suffix comparisons per word and **zero allocation** after
 * construction.
 *
 * Two structural properties of the folded stemmer make this cheap and exact:
 *
 * 1. **`removeCase` and `removePossessives` never mutate the buffer** — they only compute a shorter length —
 *    and every `normalize` rule mutates at most the final two characters of the stem and then returns. Every
 *    hypothesis is therefore fully described by `(length, final-two-characters)` over the *unmodified* input,
 *    which is how this class stores them: three parallel arrays, no copies, no strings. Two hypotheses of the
 *    same word are equal **iff** these triples are equal, because equal lengths imply an identical untouched
 *    prefix — so deduplication is a linear scan over at most a handful of triples.
 * 2. **No configuration flag can decide twice on one word.** Every guarded ending-table entry family strips
 *    and returns when it fires, and the skip-branch (that flag off) can never reach a *second* entry of the
 *    same family — the surviving suffix never matches one (verified per family in {@code caseTableOutcomes};
 *    the possessive and `normalize` flags each guard a single site with mutually exclusive patterns). The
 *    union over all flag combinations therefore equals the set of outcomes of a single walk that takes both
 *    branches wherever a guarded pattern matches, which is what the flat prototype's javadoc asserted and the
 *    lexicon-scale equivalence test now proves word by word.
 *
 * The upper bound on distinct outcomes is small: the case walk yields at most 3 lengths (the
 * vowel-strip-only lane plus at most one fork in the full-table lane), a possessive fork at most doubles
 * them to 6, `normalize` yields at most 3 outcomes per length (its rules return on application and their
 * skip-chains exclude one another), plus the surface hypothesis — 19. The arrays are sized to that bound and
 * overflow throws, per the defensive-design rule.
 *
 * **Prototype, test scope only**, like the flat union it replaces for measurement purposes. Input is expected
 * lowercased and diacritics-folded; the instance is stateful scratch and **not thread-safe** — one per stream,
 * exactly like a Lucene stemmer.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
final class BranchingFoldedCzechStemmer {

	/**
	 * Hard bound on distinct hypotheses per word — see the class javadoc for the derivation (3 case lengths
	 * × 2 possessive × 3 normalize outcomes + surface).
	 */
	private static final int MAX_HYPOTHESES = 19;

	/**
	 * Lengths of the deduplicated hypotheses of the current word.
	 */
	private final int[] lengths = new int[MAX_HYPOTHESES];
	/**
	 * Character at index `length - 2` of each hypothesis, `0` when the hypothesis is shorter than two
	 * characters. Together with {@link #lasts} this is the only place a hypothesis may differ from the
	 * original word's prefix of its length.
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
	 * Distinct post-`removeCase` lengths of the current word — at most the vowel-strip-only lane plus two
	 * outcomes of the full-table lane.
	 */
	private final int[] caseLengths = new int[3];
	/**
	 * Number of valid entries in {@link #caseLengths}.
	 */
	private int caseCount;
	/**
	 * Distinct post-`removePossessives` lengths of the current word.
	 */
	private final int[] stemLengths = new int[6];
	/**
	 * Number of valid entries in {@link #stemLengths}.
	 */
	private int stemCount;

	/**
	 * Computes every distinct hypothesis of the given diacritics-folded, lowercased word — the union of all
	 * 1,024 {@link FoldedCzechStemmer} configurations plus the folded surface itself. The buffer is only read,
	 * never mutated; the results stay valid until the next call and are read through {@link #length(int)} and
	 * {@link #materialize(int, char[], char[])}.
	 *
	 * @param s   input buffer, read only
	 * @param len length of the word in the buffer
	 * @return number of distinct hypotheses
	 */
	int hypothesize(@Nonnull char[] s, int len) {
		this.count = 0;
		this.caseCount = 0;
		this.stemCount = 0;

		// stage 1 - case endings; pure length computation, so outcomes are just deduplicated lengths.
		// lane one: the caseEndings switch off, i.e. the vowel-strip-only hypothesis
		addCaseLength(removeFinalVowel(s, len));
		// lane two: the switch on - the full table walk, forking where a guarded entry matches
		caseTableOutcomes(s, len);

		// stage 2 - possessives; also pure lengths, evaluated per case-ending outcome
		for (int i = 0; i < this.caseCount; i++) {
			final int caseLength = this.caseLengths[i];
			if (caseLength > 5
				&& (endsWith(s, caseLength, "ov")
				|| endsWith(s, caseLength, "in")
				|| endsWith(s, caseLength, "uv"))) {
				// the matching possessive's switch forks: stripped when on, kept when off
				addStemLength(caseLength - 2);
			}
			addStemLength(caseLength);
		}

		// stage 3 - normalization; the only mutating stage, every rule touches at most the final two
		// characters and returns, so each outcome is (length, final two characters) over the original word
		for (int i = 0; i < this.stemCount; i++) {
			normalizeOutcomes(s, this.stemLengths[i]);
		}

		// stage 4 - the folded surface itself, the last hypothesis of FoldedCzechStemmer#allHypotheses()
		addIdentity(s, len);
		return this.count;
	}

	/**
	 * Length of the given hypothesis of the last {@link #hypothesize(char[], int)} call.
	 *
	 * @param hypothesisIndex index of the hypothesis, `0` to `count - 1`
	 * @return length of the hypothesis
	 */
	int length(int hypothesisIndex) {
		return this.lengths[hypothesisIndex];
	}

	/**
	 * Writes the given hypothesis into the destination buffer: the original word's prefix of the hypothesis
	 * length with the recorded final two characters applied.
	 *
	 * @param hypothesisIndex index of the hypothesis, `0` to `count - 1`
	 * @param originalWord    the exact buffer the last {@link #hypothesize(char[], int)} call read
	 * @param destination     buffer to write into, at least {@link #length(int)} characters long
	 * @return length of the hypothesis written
	 */
	int materialize(int hypothesisIndex, @Nonnull char[] originalWord, @Nonnull char[] destination) {
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
	 * Walks the full case-ending table — the `caseEndings`-on lane of `FoldedCzechStemmer#removeCase` — and
	 * records every reachable outcome length. An unguarded entry match ends the walk for every remaining
	 * configuration; a guarded entry match records the stripped length (that switch on) and continues with the
	 * switch committed off. No family can match twice on one word: after a guarded strip is skipped, the word
	 * still ends with the *same* suffix, and no shorter entry of the same family is a suffix of a longer one
	 * (`atech`/`atum`/`ata`/`aty`/`at` end pairwise incompatibly, likewise `emi`/`imi`/`ymi`), so the
	 * committed-off flags below can never be consulted again — they exist to make that visible, not to change
	 * the outcome.
	 *
	 * @param s   input buffer, read only
	 * @param len length of the word
	 */
	private void caseTableOutcomes(@Nonnull char[] s, int len) {
		// Both commitment booleans are provably inert against the CURRENT ending table: no word can match two
		// guarded entries of the same family on one walk. A word that matched `atech` still ends `ch` below, which
		// matches no later `at`-family entry; after `atum`/`ata`/`aty` the surviving two-character suffix is
		// `um`/`ta`/`ty`, never `at`; and `longMiUndecided` has a single read site, which its only write cannot
		// precede. They are kept because they encode the invariant the union-equals-branching equivalence rests
		// on: one flat-union configuration holds ONE value of each switch for the whole word, so once a walk takes
		// a family's off-branch, that family must stay off for the rest of that walk. Should a future table edit
		// make a second same-family match reachable, a walk without the commitment would fork twice and emit a
		// hypothesis NO configuration produces (were `mi` guarded by longMiEndings, `surimi` would gain a phantom
		// `suri` from the off-then-on path); with it, the walk stays equal to the union by construction, instead
		// of relying on BranchingCzechStemmerEquivalenceTest to flag the divergence after the fact.
		boolean neuterAtUndecided = true;
		boolean longMiUndecided = true;

		if (len > 7 && endsWith(s, len, "atech")) {
			// neuterAtParadigm on strips here; off continues down the table (and lands on the `ech` entry)
			addCaseLength(len - 5);
			neuterAtUndecided = false;
		}

		if (len > 6) {
			if (endsWith(s, len, "etem")) {
				addCaseLength(len - 4);
				return;
			}
			if (neuterAtUndecided && endsWith(s, len, "atum")) {
				addCaseLength(len - 4);
				neuterAtUndecided = false;
			}
		}

		if (len > 5) {
			if (endsWith(s, len, "ech")
				|| endsWith(s, len, "ich")
				|| endsWith(s, len, "eho")
				|| endsWith(s, len, "emu")
				|| endsWith(s, len, "ete")
				|| endsWith(s, len, "eti")
				|| endsWith(s, len, "iho")
				|| endsWith(s, len, "imu")
				|| endsWith(s, len, "ach")
				|| endsWith(s, len, "ych")
				|| endsWith(s, len, "ama")
				|| endsWith(s, len, "ami")
				|| endsWith(s, len, "ove")
				|| endsWith(s, len, "ovi")) {
				addCaseLength(len - 3);
				return;
			}
			if (longMiUndecided
				&& (endsWith(s, len, "emi") || endsWith(s, len, "imi") || endsWith(s, len, "ymi"))) {
				// longMiEndings on strips here; off continues and lands on the two-letter `mi` entry
				addCaseLength(len - 3);
				longMiUndecided = false;
			}
			if (neuterAtUndecided && (endsWith(s, len, "ata") || endsWith(s, len, "aty"))) {
				addCaseLength(len - 3);
				neuterAtUndecided = false;
			}
		}

		if (len > 4) {
			if (endsWith(s, len, "em")
				|| endsWith(s, len, "es")
				|| endsWith(s, len, "im")
				|| endsWith(s, len, "um")
				|| endsWith(s, len, "am")
				|| endsWith(s, len, "os")
				|| endsWith(s, len, "us")
				|| endsWith(s, len, "ym")
				|| endsWith(s, len, "mi")
				|| endsWith(s, len, "ou")) {
				addCaseLength(len - 2);
				return;
			}
			if (neuterAtUndecided && endsWith(s, len, "at")) {
				addCaseLength(len - 2);
				// the off-branch falls through to the final vowel below; no further `at` entry can match
			}
		}

		addCaseLength(removeFinalVowel(s, len));
	}

	/**
	 * The unguarded final-vowel strip — the whole of `removeCase` when the `caseEndings` switch is off, and
	 * its last tier when it is on. Mirror of `FoldedCzechStemmer#removeFinalVowel`.
	 *
	 * @param s   input buffer, read only
	 * @param len current length
	 * @return length after the vowel was stripped
	 */
	private static int removeFinalVowel(@Nonnull char[] s, int len) {
		if (len > 3) {
			switch (s[len - 1]) {
				case 'a':
				case 'e':
				case 'i':
				case 'o':
				case 'u':
				case 'y':
					return len - 1;
				default:
					// not a case ending - the length stays
					break;
			}
		}
		return len;
	}

	/**
	 * Records every reachable outcome of `FoldedCzechStemmer#normalize` for a stem of the given length. Every
	 * rule in `normalize` is switch-guarded and returns when applied, so the outcomes are: one per guarded
	 * rule whose pattern matches along the skip-chain, plus the identity (all switches off). The patterns
	 * exclude one another pairwise except `c`/`z` with a penultimate `e`/`u`, so at most three outcomes exist.
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

		// palatalization: applied it rewrites the final two characters and returns; its skip-branch can reach
		// no other rule (the word ends `ct`/`st`, so the final character is `t` and the penultimate `c`/`s`)
		if (endsWith(s, stemLength, "ct")) {
			addRaw(stemLength, 'c', 'k');
		} else if (endsWith(s, stemLength, "st")) {
			addRaw(stemLength, 's', 'k');
		}

		// final-consonant rewrite: applied it rewrites the last character and returns
		final char last = s[stemLength - 1];
		if (last == 'c') {
			addRaw(stemLength, stemLength >= 2 ? s[stemLength - 2] : (char) 0, 'k');
		} else if (last == 'z') {
			addRaw(stemLength, stemLength >= 2 ? s[stemLength - 2] : (char) 0, 'h');
		}

		// epenthetic -e- removal: shifts the last character one left and shortens by one, then returns
		if (stemLength > 1 && s[stemLength - 2] == 'e') {
			addRaw(
				stemLength - 1,
				stemLength >= 3 ? s[stemLength - 3] : (char) 0,
				s[stemLength - 1]
			);
		}

		// penultimate vowel shift: rewrites the penultimate character and returns; its pattern (`u`) excludes
		// the epenthetic one (`e`), so at most one of the two fires
		if (stemLength > 2 && s[stemLength - 2] == 'u') {
			addRaw(stemLength, 'o', s[stemLength - 1]);
		}

		// identity - every normalize switch off
		addIdentity(s, stemLength);
	}

	/**
	 * Records a case-ending outcome length, deduplicated — the vowel-strip-only lane and the full-table
	 * lane's fall-through frequently coincide.
	 *
	 * @param length outcome length
	 */
	private void addCaseLength(int length) {
		for (int i = 0; i < this.caseCount; i++) {
			if (this.caseLengths[i] == length) {
				return;
			}
		}
		this.caseLengths[this.caseCount++] = length;
	}

	/**
	 * Records a post-possessive outcome length, deduplicated.
	 *
	 * @param length outcome length
	 */
	private void addStemLength(int length) {
		for (int i = 0; i < this.stemCount; i++) {
			if (this.stemLengths[i] == length) {
				return;
			}
		}
		this.stemLengths[this.stemCount++] = length;
	}

	/**
	 * Records the identity hypothesis of the given length — the original word's prefix, unmodified.
	 *
	 * @param s      input buffer, read only
	 * @param length hypothesis length
	 */
	private void addIdentity(@Nonnull char[] s, int length) {
		addRaw(
			length,
			length >= 2 ? s[length - 2] : (char) 0,
			length >= 1 ? s[length - 1] : (char) 0
		);
	}

	/**
	 * Records a hypothesis as its `(length, final-two-characters)` triple, deduplicated. Equal triples mean
	 * equal hypotheses because every hypothesis of one word shares the word's untouched prefix.
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
			// unreachable by the bound derived in the class javadoc - a breach means the walk no longer
			// mirrors FoldedCzechStemmer and must fail loudly rather than drop a hypothesis
			throw new IllegalStateException(
				"More than " + MAX_HYPOTHESES + " hypotheses for one word - the branching walk has diverged "
					+ "from FoldedCzechStemmer."
			);
		}
		this.lengths[this.count] = length;
		this.penultimates[this.count] = penultimate;
		this.lasts[this.count] = last;
		this.count++;
	}

}
