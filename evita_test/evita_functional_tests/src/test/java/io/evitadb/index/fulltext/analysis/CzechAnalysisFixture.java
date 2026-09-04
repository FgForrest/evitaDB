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
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The measuring instrument shared by {@link CzechAccentTypingTest} and
 * {@link CzechAnalysisApproachMatrixTest}: one Czech vocabulary, one definition of "the query found the value",
 * and four numbers that together say whether a Czech analysis chain is usable.
 *
 * Keeping this out of the tests is what makes the two comparable — a mechanism that scores better on its own
 * vocabulary has not been shown to score better at all.
 *
 * **The four measurements, and why each is needed:**
 *
 * 1. **accent-typed recall** — a form typed without its diacritics must find the accented spelling. This is the
 *    property the production chain fails on, and the reason the whole exercise exists.
 * 2. **inflection convergence, pairwise** — every *ordered* pair of forms of one lemma must match, i.e. querying
 *    form A must find a value written as form B. Measured pairwise rather than as "all forms share one term"
 *    because prefix and fuzzy matching are directional: `bota` finds `botách` while `botách` does not find
 *    `bota`, and a metric that cannot express that would score the no-stemmer mechanisms wrong.
 * 3. **inflection convergence, strict** — the older "all forms of a lemma share at least one term" reading.
 *    Retained for comparability with the first measurement run and because it is the only form of the question
 *    that is meaningful for an exact-match term dictionary.
 * 4. **bare-typed cross-form recall** — the two above, *combined*: a form typed without diacritics must find a
 *    value written in a **different** inflection of the same lemma (`panskych` finding `pánská`). This is the
 *    case a Czech e-shop actually sees, and it is the one that separates the mechanisms: measurements 1 and 2
 *    can each be satisfied by a single lane of a two-lane chain acting alone — the folded surface lane carries
 *    accent recall, the stem lane carries convergence — while this one can only be satisfied by a lane that
 *    does **both** jobs at once. Added after the first matrix run, because without it a two-lane chain scores
 *    perfectly while leaving the real query broken.
 * 5. **false merges** — ordered pairs of forms belonging to **different** lemmas that match anyway. Without
 *    this, every mechanism could be made to score perfectly by collapsing the whole vocabulary onto one term.
 *    The first measurement run omitted it; the aggressive stemmer variants make it indispensable.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
final class CzechAnalysisFixture {

	/**
	 * A Czech e-commerce vocabulary: 32 lemmas, each with the inflected forms an e-shop actually stores or is
	 * queried by. The classes present here are deliberate, because a mechanism can only be measured against a
	 * failure it is given the chance to commit:
	 *
	 * - the adjective genitive/locative plural `-ých`/`-ích` and instrumental `-ým`, and the noun
	 *   dative/locative plural `-ám`/`-ách`/`-ům` — the classes the production folding order loses;
	 * - the `stůl`/`stolů` vowel shift, which only the `u`→`o` rewrite converges;
	 * - the **palatalized** nominative plural `dětští`, `pánští`, `kuchyňští`, `angličtí` — `sk`↔`št` and
	 *   `ck`↔`čt` alternation, which only the palatalization rewrite converges. These were **missing from the
	 *   first two measurement runs**, which is why those runs reported the palatalization rewrite as buying
	 *   nothing: the fixture never gave it a pair to converge. A rule measured against a vocabulary that
	 *   cannot exercise it scores zero for the wrong reason;
	 * - the neuter `-ata` paradigm `rajče`/`rajčata`, whose plural the `at`-family entries exist for. Missing
	 *   from the first three runs, which is why those runs reported dropping the entries as free: the drop's
	 *   cost — the singular splitting from the plural — needs an `-ata` neuter to commit, and `kabát` (the
	 *   drop's *benefit*) is a masculine. Same lesson as the palatalized plurals, opposite direction: a rule's
	 *   removal must also be measured against a vocabulary that exercises what the rule was for.
	 */
	static final List<Lemma> VOCABULARY = List.of(
		new Lemma("černý", List.of("černý", "černá", "černé", "černých", "černým")),
		new Lemma("bílý", List.of("bílý", "bílá", "bílé", "bílých", "bílým")),
		new Lemma("šedý", List.of("šedý", "šedá", "šedé", "šedých")),
		new Lemma("žlutý", List.of("žlutý", "žlutá", "žluté", "žlutých")),
		new Lemma("dámský", List.of("dámský", "dámská", "dámské", "dámských")),
		new Lemma("pánský", List.of("pánský", "pánská", "pánské", "pánských", "pánští")),
		new Lemma("dětský", List.of("dětský", "dětská", "dětské", "dětských", "dětští")),
		new Lemma("kožený", List.of("kožený", "kožená", "kožené", "kožených")),
		new Lemma("dřevěný", List.of("dřevěný", "dřevěná", "dřevěné", "dřevěných")),
		new Lemma("stříbrný", List.of("stříbrný", "stříbrná", "stříbrné", "stříbrných")),
		new Lemma("kuchyňský", List.of("kuchyňský", "kuchyňská", "kuchyňské", "kuchyňských", "kuchyňští")),
		new Lemma("anglický", List.of("anglický", "anglická", "anglické", "anglických", "angličtí")),
		new Lemma("velký", List.of("velký", "velká", "velké", "velkých")),
		new Lemma("malý", List.of("malý", "malá", "malé", "malých")),
		new Lemma("zahradní", List.of("zahradní", "zahradního", "zahradních")),
		new Lemma("stůl", List.of("stůl", "stolů")),
		new Lemma("židle", List.of("židle", "židli", "židlí")),
		new Lemma("tričko", List.of("tričko", "trička", "tričkem")),
		new Lemma("košile", List.of("košile", "košili", "košilí")),
		new Lemma("bota", List.of("botě", "botám", "botách")),
		new Lemma("kabát", List.of("kabát", "kabátu", "kabáty", "kabátů")),
		new Lemma("mikina", List.of("mikině", "mikinách")),
		new Lemma("hodinkář", List.of("hodinkář", "hodinkářů")),
		new Lemma("náramek", List.of("náramek", "náramku", "náramky", "náramků")),
		new Lemma("přívěsek", List.of("přívěsek", "přívěsku", "přívěsky", "přívěsků")),
		new Lemma("sluchátka", List.of("sluchátka", "sluchátek", "sluchátkům")),
		new Lemma("počítač", List.of("počítač", "počítače", "počítači", "počítačů")),
		new Lemma("nábytek", List.of("nábytek", "nábytku", "nábytkem")),
		new Lemma("skříň", List.of("skříň", "skříně", "skříni", "skříní")),
		new Lemma("dárek", List.of("dárek", "dárku", "dárky", "dárků")),
		new Lemma("kůže", List.of("kůže", "kůži", "kůží")),
		new Lemma("rajče", List.of("rajče", "rajčata", "rajčat"))
	);

	/**
	 * Pairs of **unrelated** lemmas chosen because the two risky rules of a folded-space Czech stemmer would
	 * collapse them onto one term. They exist purely to give the false-merge measurement something to find:
	 * a precision metric over a vocabulary containing no confusable words measures nothing.
	 *
	 * - `cesta`/`český` and `list`/`líska` probe the palatalization rule. `CzechStemmer` rewrites `št` to `sk`
	 *   to make `český`/`čeští` converge — hence the `čeští` form below, so that the rule's *benefit* is
	 *   measured on the same vocabulary as its cost; folded, `št` is indistinguishable from a genuine `st`, so
	 *   a blanket `st→sk` also rewrites `cest` and `list`.
	 * - `ruka`/`rok` and `buk`/`bok` probe the vowel shift. `CzechStemmer` rewrites a penultimate `ů` to `o` so
	 *   that `dům`/`domu` converge; folded, `ů` is indistinguishable from `u`, so a blanket rule also rewrites
	 *   `ruk` and `buk`.
	 * - `forma`/`formát` probe the `-at` family entries wherever they fire on a genuine `-át` root — under the
	 *   kept single-pass entries and under two-step stemming alike: `formát` truncated by the `at` entry lands
	 *   on the stem of the unrelated `-a` feminine, `formát → form` ≡ `forma → form`. Without this pair every
	 *   configuration that eats `-át` roots scores its precision cost as zero.
	 */
	static final List<Lemma> CONFUSABLE_LEMMAS = List.of(
		new Lemma("cesta", List.of("cesta", "cesty", "cestě", "cestách")),
		new Lemma("český", List.of("český", "česká", "české", "českých", "čeští")),
		new Lemma("list", List.of("list", "listu", "listy")),
		new Lemma("líska", List.of("líska", "lísky", "lísce")),
		new Lemma("ruka", List.of("ruka", "ruky", "ruce")),
		new Lemma("rok", List.of("rok", "roku", "roky")),
		new Lemma("buk", List.of("buk", "buku", "buky")),
		new Lemma("bok", List.of("bok", "boku", "boky")),
		new Lemma("forma", List.of("forma", "formy", "formě")),
		new Lemma("formát", List.of("formát", "formátu", "formáty"))
	);

	/**
	 * Length at or above which one edit of typo tolerance is granted, mirroring Meilisearch's
	 * `min_word_len_one_typo` default.
	 */
	private static final int ONE_TYPO_MIN_LENGTH = 5;
	/**
	 * Length at or above which two edits of typo tolerance are granted, mirroring Meilisearch's
	 * `min_word_len_two_typos` default.
	 */
	private static final int TWO_TYPOS_MIN_LENGTH = 9;

	private CzechAnalysisFixture() {
	}

	/**
	 * Strips every combining mark from `text`, i.e. reproduces what a user typing on a keyboard without Czech
	 * accents actually enters. Deliberately implemented over Unicode decomposition rather than through
	 * Lucene's folding filter, so that the measurement's input does not depend on the filter being measured.
	 *
	 * @param text accented text
	 * @return the same text with all diacritics removed
	 */
	@Nonnull
	static String stripAccents(@Nonnull String text) {
		return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
	}

	/**
	 * Analyses a single word and returns the set of terms produced for it. A set rather than a list because a
	 * multi-lane or dictionary-based chain emits several terms per word and their order carries no meaning.
	 *
	 * @param analyzer chain to analyse with
	 * @param word     single word to analyse
	 * @return terms produced, possibly empty when the word is a stop word
	 */
	@Nonnull
	static Set<String> analyzeWord(@Nonnull FulltextAnalyzer analyzer, @Nonnull String word) {
		final Set<String> terms = new LinkedHashSet<>(4);
		analyzer.analyze(
			word,
			(term, surfaceForm, startOffset, endOffset, positionIncrement) -> terms.add(term)
		);
		return terms;
	}

	/**
	 * Measures one approach over the whole vocabulary.
	 *
	 * @param approachName  name under which the approach is reported
	 * @param indexAnalyzer chain analysing the stored value
	 * @param queryAnalyzer chain analysing the query text; the same instance as `indexAnalyzer` for a symmetric
	 *                      approach, a different one for an asymmetric one
	 * @param strategy      how a query term set is decided to have found a value term set
	 * @return the measurement, carrying the failing cases themselves rather than only their counts
	 */
	@Nonnull
	static Measurement measure(
		@Nonnull String approachName,
		@Nonnull FulltextAnalyzer indexAnalyzer,
		@Nonnull FulltextAnalyzer queryAnalyzer,
		@Nonnull MatchStrategy strategy
	) {
		// every form is analysed once per side and cached - the false-merge measurement alone compares tens of
		// thousands of pairs, and re-running the chain for each would dominate the run time
		final List<Lemma> allLemmas = new ArrayList<>(VOCABULARY.size() + CONFUSABLE_LEMMAS.size());
		allLemmas.addAll(VOCABULARY);
		allLemmas.addAll(CONFUSABLE_LEMMAS);

		int indexedTermCount = 0;
		final Map<String, Set<String>> indexTerms = new HashMap<>(256);
		final Map<String, Set<String>> queryTerms = new HashMap<>(256);
		for (final Lemma lemma : allLemmas) {
			for (final String form : lemma.forms()) {
				indexedTermCount += indexTerms
					.computeIfAbsent(form, f -> analyzeWord(indexAnalyzer, f))
					.size();
				queryTerms.computeIfAbsent(form, f -> analyzeWord(queryAnalyzer, f));
				final String bare = stripAccents(form);
				queryTerms.computeIfAbsent(bare, f -> analyzeWord(queryAnalyzer, f));
			}
		}

		final List<String> accentTypingMisses = new ArrayList<>(32);
		final List<String> convergenceMisses = new ArrayList<>(64);
		final List<String> strictDivergentLemmas = new ArrayList<>(8);
		final List<String> bareTypedCrossFormMisses = new ArrayList<>(64);
		int accentedFormCount = 0;
		int convergencePairCount = 0;
		int bareTypedCrossFormPairCount = 0;

		for (final Lemma lemma : VOCABULARY) {
			Set<String> sharedTerms = null;
			for (final String form : lemma.forms()) {
				final Set<String> valueTerms = indexTerms.get(form);
				// a form spelled without diacritics is vacuous for the accent-typed measurement - its bare
				// typing IS the form - so it is excluded rather than counted as a free hit
				final String bare = stripAccents(form);
				if (!bare.equals(form)) {
					accentedFormCount++;
					if (!matches(queryTerms.get(bare), valueTerms, strategy)) {
						accentTypingMisses.add(
							form + " -> " + valueTerms + " but " + bare + " -> " + queryTerms.get(bare)
						);
					}
				}
				if (sharedTerms == null) {
					sharedTerms = new LinkedHashSet<>(valueTerms);
				} else {
					sharedTerms.retainAll(valueTerms);
				}
			}
			if (sharedTerms == null || sharedTerms.isEmpty()) {
				strictDivergentLemmas.add(lemma.lemma() + " " + lemma.forms());
			}
			// pairwise, ordered: querying by one form must find a value written as another
			for (final String queryForm : lemma.forms()) {
				for (final String valueForm : lemma.forms()) {
					if (queryForm.equals(valueForm)) {
						continue;
					}
					convergencePairCount++;
					if (!matches(queryTerms.get(queryForm), indexTerms.get(valueForm), strategy)) {
						convergenceMisses.add(
							lemma.lemma() + ": query " + queryForm + " " + queryTerms.get(queryForm)
								+ " misses value " + valueForm + " " + indexTerms.get(valueForm)
						);
					}
					// the same pair, but with the query typed without accents - the case a Czech e-shop
					// actually sees, and the one a single lane cannot cover by itself
					final String bareQueryForm = stripAccents(queryForm);
					if (bareQueryForm.equals(queryForm)) {
						continue;
					}
					bareTypedCrossFormPairCount++;
					if (!matches(queryTerms.get(bareQueryForm), indexTerms.get(valueForm), strategy)) {
						bareTypedCrossFormMisses.add(
							lemma.lemma() + ": query " + bareQueryForm + " " + queryTerms.get(bareQueryForm)
								+ " misses value " + valueForm + " " + indexTerms.get(valueForm)
						);
					}
				}
			}
		}

		final List<String> falseMerges = new ArrayList<>(32);
		int crossLemmaPairCount = 0;
		for (final Lemma queryLemma : allLemmas) {
			for (final Lemma valueLemma : allLemmas) {
				if (queryLemma == valueLemma) {
					continue;
				}
				for (final String queryForm : queryLemma.forms()) {
					for (final String valueForm : valueLemma.forms()) {
						crossLemmaPairCount++;
						if (matches(queryTerms.get(queryForm), indexTerms.get(valueForm), strategy)) {
							falseMerges.add(
								"query " + queryForm + " (" + queryLemma.lemma() + ") "
									+ queryTerms.get(queryForm) + " matches value " + valueForm
									+ " (" + valueLemma.lemma() + ") " + indexTerms.get(valueForm)
							);
						}
					}
				}
			}
		}

		return new Measurement(
			approachName, strategy, accentedFormCount, VOCABULARY.size(), convergencePairCount,
			crossLemmaPairCount, bareTypedCrossFormPairCount, indexTerms.size(), indexedTermCount,
			accentTypingMisses, strictDivergentLemmas, convergenceMisses, bareTypedCrossFormMisses, falseMerges
		);
	}

	/**
	 * Decides whether a query's terms found a value's terms.
	 *
	 * @param queryTerms terms the query text produced
	 * @param valueTerms terms the stored value produced
	 * @param strategy   matching rules to apply
	 * @return true when at least one query term matched at least one value term
	 */
	static boolean matches(
		@Nonnull Set<String> queryTerms,
		@Nonnull Set<String> valueTerms,
		@Nonnull MatchStrategy strategy
	) {
		for (final String queryTerm : queryTerms) {
			final int maxEdits = strategy == MatchStrategy.PREFIX_FUZZY ? maxEdits(queryTerm.length()) : 0;
			for (final String valueTerm : valueTerms) {
				if (queryTerm.equals(valueTerm)) {
					return true;
				}
				if (strategy == MatchStrategy.PREFIX_FUZZY) {
					if (valueTerm.startsWith(queryTerm)) {
						return true;
					}
					if (maxEdits > 0 && withinEditDistance(queryTerm, valueTerm, maxEdits)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * Returns the typo budget a query term of the given length earns. Mirrors Meilisearch's length thresholds,
	 * which is the shape every surveyed engine uses: the budget is decided by word length, never by grammar.
	 *
	 * @param length length of the query term
	 * @return number of edits allowed, 0 to 2
	 */
	private static int maxEdits(int length) {
		if (length >= TWO_TYPOS_MIN_LENGTH) {
			return 2;
		}
		return length >= ONE_TYPO_MIN_LENGTH ? 1 : 0;
	}

	/**
	 * Tells whether two terms are within `maxEdits` Levenshtein edits of each other. A bounded row-wise DP with
	 * an early bail-out — the full matrix is never needed, only the answer to the threshold question.
	 *
	 * @param left     first term
	 * @param right    second term
	 * @param maxEdits inclusive edit budget
	 * @return true when the distance is at most `maxEdits`
	 */
	private static boolean withinEditDistance(@Nonnull String left, @Nonnull String right, int maxEdits) {
		final int leftLength = left.length();
		final int rightLength = right.length();
		if (Math.abs(leftLength - rightLength) > maxEdits) {
			return false;
		}
		int[] previous = new int[rightLength + 1];
		int[] current = new int[rightLength + 1];
		for (int j = 0; j <= rightLength; j++) {
			previous[j] = j;
		}
		for (int i = 1; i <= leftLength; i++) {
			current[0] = i;
			int rowMinimum = current[0];
			for (int j = 1; j <= rightLength; j++) {
				final int substitution = previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
				final int deletion = previous[j] + 1;
				final int insertion = current[j - 1] + 1;
				current[j] = Math.min(substitution, Math.min(deletion, insertion));
				if (current[j] < rowMinimum) {
					rowMinimum = current[j];
				}
			}
			if (rowMinimum > maxEdits) {
				// no cell of any later row can drop back below the budget
				return false;
			}
			final int[] swap = previous;
			previous = current;
			current = swap;
		}
		return previous[rightLength] <= maxEdits;
	}

	/**
	 * How a query's terms are compared against a value's terms.
	 */
	enum MatchStrategy {

		/**
		 * A query term must equal a value term — what an exact-match term dictionary does, and what evitaDB's
		 * index does today.
		 */
		EXACT,
		/**
		 * A query term matches when it equals a value term, is a prefix of one, or is within the
		 * length-derived typo budget of one. This is the retrieval model the no-stemmer engines rely on to
		 * carry inflection; note that it is **directional** — a shorter query reaches a longer value, never
		 * the other way round.
		 */
		PREFIX_FUZZY

	}

	/**
	 * One lemma of the measured vocabulary together with the inflected forms it is measured through.
	 *
	 * @param lemma dictionary form, used only for reporting
	 * @param forms inflected forms
	 */
	record Lemma(@Nonnull String lemma, @Nonnull List<String> forms) {
	}

	/**
	 * Result of measuring one approach. The failing cases are carried rather than counted, because "77 % pass"
	 * says nothing while "every adjective genitive plural in `-ých` fails" says what the index has to do.
	 *
	 * @param approachName          name of the measured approach
	 * @param strategy              matching strategy the numbers were measured under
	 * @param accentedFormCount     number of forms carrying a diacritic, i.e. the denominator of accent-typed
	 *                              recall
	 * @param lemmaCount            number of lemmas measured
	 * @param convergencePairCount  number of ordered same-lemma form pairs, i.e. the denominator of pairwise
	 *                              convergence
	 * @param crossLemmaPairCount   number of ordered different-lemma form pairs, i.e. the denominator of the
	 *                              false-merge rate
	 * @param bareTypedCrossFormPairCount number of ordered same-lemma pairs whose query form carries a
	 *                              diacritic, i.e. the denominator of bare-typed cross-form recall
	 * @param indexedFormCount      number of distinct forms analysed on the index side
	 * @param indexedTermCount      number of terms those forms produced in total — the term-inflation cost of a
	 *                              multi-lane chain, measured over one-word inputs and therefore an upper bound
	 *                              on what real text would pay
	 * @param accentTypingMisses    forms unreachable when typed without diacritics
	 * @param strictDivergentLemmas lemmas whose forms produced no term in common
	 * @param convergenceMisses     ordered same-lemma pairs that did not match
	 * @param bareTypedCrossFormMisses ordered same-lemma pairs that did not match once the query was typed
	 *                              without its diacritics
	 * @param falseMerges           ordered different-lemma pairs that matched anyway
	 */
	record Measurement(
		@Nonnull String approachName,
		@Nonnull MatchStrategy strategy,
		int accentedFormCount,
		int lemmaCount,
		int convergencePairCount,
		int crossLemmaPairCount,
		int bareTypedCrossFormPairCount,
		int indexedFormCount,
		int indexedTermCount,
		@Nonnull List<String> accentTypingMisses,
		@Nonnull List<String> strictDivergentLemmas,
		@Nonnull List<String> convergenceMisses,
		@Nonnull List<String> bareTypedCrossFormMisses,
		@Nonnull List<String> falseMerges
	) {

		/**
		 * Renders this measurement as one row of the comparison matrix.
		 *
		 * @return single formatted table row, newline-terminated
		 */
		@Nonnull
		String matrixRow() {
			return String.format(
				"| %-34s | %-12s | %4d/%-4d | %4d/%-4d | %4d/%-4d | %3d/%-3d | %5d | %5.2f |%n",
				this.approachName, this.strategy == MatchStrategy.EXACT ? "exact" : "prefix+fuzzy",
				this.accentedFormCount - this.accentTypingMisses.size(), this.accentedFormCount,
				this.bareTypedCrossFormPairCount - this.bareTypedCrossFormMisses.size(),
				this.bareTypedCrossFormPairCount,
				this.convergencePairCount - this.convergenceMisses.size(), this.convergencePairCount,
				this.lemmaCount - this.strictDivergentLemmas.size(), this.lemmaCount,
				this.falseMerges.size(),
				(double) this.indexedTermCount / this.indexedFormCount
			);
		}

		/**
		 * Renders the failing cases of this measurement, capped so that one badly-scoring approach cannot bury
		 * the rest of the report.
		 *
		 * @param caseLimit maximum number of cases to print per category
		 * @return multi-line detail block
		 */
		@Nonnull
		String detail(int caseLimit) {
			final StringBuilder text = new StringBuilder(2048);
			text.append("=== ").append(this.approachName).append(" [").append(this.strategy).append("] ===\n");
			appendCases(text, "accent-typing misses", this.accentTypingMisses, caseLimit);
			appendCases(text, "convergence misses (ordered pairs)", this.convergenceMisses, caseLimit);
			appendCases(text, "bare-typed cross-form misses", this.bareTypedCrossFormMisses, caseLimit);
			appendCases(text, "strictly divergent lemmas", this.strictDivergentLemmas, caseLimit);
			appendCases(text, "false merges", this.falseMerges, caseLimit);
			return text.toString();
		}

		/**
		 * Appends one capped category of failing cases.
		 *
		 * @param text      builder to append to
		 * @param title     category heading
		 * @param cases     the failing cases
		 * @param caseLimit maximum number to print
		 */
		private static void appendCases(
			@Nonnull StringBuilder text,
			@Nonnull String title,
			@Nonnull List<String> cases,
			int caseLimit
		) {
			if (cases.isEmpty()) {
				return;
			}
			text.append(title).append(" (").append(cases.size()).append("):\n");
			for (int i = 0; i < Math.min(caseLimit, cases.size()); i++) {
				text.append("  ").append(cases.get(i)).append('\n');
			}
			if (cases.size() > caseLimit) {
				text.append("  ... ").append(cases.size() - caseLimit).append(" more\n");
			}
		}

	}

}
