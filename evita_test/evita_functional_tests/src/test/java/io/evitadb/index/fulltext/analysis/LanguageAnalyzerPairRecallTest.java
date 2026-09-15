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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins what the asymmetric analyzer pairs actually buy, per language: **a query typed without diacritics finds
 * every stored inflected form of the same word**, and does not thereby collapse words that mean different
 * things.
 *
 * Two metrics, both taken from the records the design was chosen on. Every accented form of the language's
 * fixture is typed on a bare keyboard and run through the language's `*-search` chain; its terms then have to
 * share at least one term with the `*index*` chain's terms of
 *
 * - **that same form** — accent-typed recall, the minimum a folding pair has to deliver;
 * - **every other form of the same lemma** — bare-typed cross-form recall, which additionally needs the
 *   stemmer to converge the paradigm.
 *
 * Forms that carry no diacritic are skipped rather than counted as free hits — their bare typing is the form
 * itself, so they would inflate both scores without testing anything.
 *
 * The counter-metric runs over the fixture's deliberately confusable lemmas: how many ordered cross-lemma form
 * pairs the pair merges. Recall bought by collapsing unrelated words is not recall, and an upper bound on this
 * number is the only thing that keeps the variant fan-out honest.
 *
 * **These are pins, not reports.** The numbers below are the measured behaviour of the shipped chains; a change
 * that moves one has changed what users find, and must be looked at rather than re-baselined. The four fixtures'
 * Slovak, Polish and Romanian vocabularies are still awaiting a native-speaker review, so their numbers may
 * legitimately move when that review lands — see each fixture's sourcing note.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Language analyzer pair — bare-typed recall and false merges")
@Tag(ENGINE)
@Tag(FULLTEXT)
class LanguageAnalyzerPairRecallTest {

	private static final String ENTITY_TYPE = "PRODUCT";

	private FulltextAnalyzerRegistry registry;

	@BeforeEach
	void setUp() {
		this.registry = new FulltextAnalyzerRegistry();
	}

	@AfterEach
	void tearDown() {
		this.registry.close();
	}

	/**
	 * Runs the language's analyzer pair over its fixture and returns the two measured numbers.
	 *
	 * @param locale           locale whose built-in analyzer pair is measured
	 * @param vocabulary       lemmas carrying the recall measurement
	 * @param confusableLemmas unrelated lemmas carrying the false-merge measurement
	 * @param bareTyper        what a user typing without the language's layout enters for a form
	 * @return the measurement
	 */
	@Nonnull
	private Recall measure(
		@Nonnull Locale locale,
		@Nonnull List<Lemma> vocabulary,
		@Nonnull List<Lemma> confusableLemmas,
		@Nonnull UnaryOperator<String> bareTyper
	) {
		final FulltextAnalyzer index = this.registry.getIndexAnalyzer(ENTITY_TYPE, locale);
		final FulltextAnalyzer search = this.registry.getSearchAnalyzer(ENTITY_TYPE, locale);

		final List<Lemma> allLemmas = new ArrayList<>(vocabulary.size() + confusableLemmas.size());
		allLemmas.addAll(vocabulary);
		allLemmas.addAll(confusableLemmas);

		// every form is analysed once per side and cached - the false-merge measurement alone compares tens of
		// thousands of pairs, and re-running a chain for each would dominate the run time
		final Map<String, Set<String>> indexTerms = new HashMap<>(256);
		final Map<String, Set<String>> queryTerms = new HashMap<>(256);
		for (final Lemma lemma : allLemmas) {
			for (final String form : lemma.forms()) {
				indexTerms.computeIfAbsent(form, f -> termsOf(index, f));
				queryTerms.computeIfAbsent(form, f -> termsOf(search, f));
				queryTerms.computeIfAbsent(bareTyper.apply(form), f -> termsOf(search, f));
			}
		}

		int accentTypedForms = 0;
		final List<String> accentTypedMisses = new ArrayList<>(32);
		int pairs = 0;
		final List<String> misses = new ArrayList<>(32);
		for (final Lemma lemma : vocabulary) {
			for (final String queryForm : lemma.forms()) {
				final String bareQueryForm = bareTyper.apply(queryForm);
				if (bareQueryForm.equals(queryForm)) {
					// a form spelled without diacritics is vacuous here - its bare typing IS the form
					continue;
				}
				// the simplest question first: does the bare typing of a form still find that very form?
				accentTypedForms++;
				if (!intersects(queryTerms.get(bareQueryForm), indexTerms.get(queryForm))) {
					accentTypedMisses.add(
						"`" + bareQueryForm + "` " + queryTerms.get(bareQueryForm) + " misses its own form `"
							+ queryForm + "` " + indexTerms.get(queryForm)
					);
				}
				for (final String valueForm : lemma.forms()) {
					if (queryForm.equals(valueForm)) {
						continue;
					}
					pairs++;
					if (!intersects(queryTerms.get(bareQueryForm), indexTerms.get(valueForm))) {
						misses.add(
							lemma.lemma() + ": query `" + bareQueryForm + "` "
								+ queryTerms.get(bareQueryForm) + " misses value `" + valueForm + "` "
								+ indexTerms.get(valueForm)
						);
					}
				}
			}
		}

		final List<String> falseMerges = new ArrayList<>(32);
		for (final Lemma queryLemma : allLemmas) {
			for (final Lemma valueLemma : allLemmas) {
				if (queryLemma == valueLemma) {
					continue;
				}
				for (final String queryForm : queryLemma.forms()) {
					for (final String valueForm : valueLemma.forms()) {
						if (intersects(queryTerms.get(queryForm), indexTerms.get(valueForm))) {
							falseMerges.add(
								"query `" + queryForm + "` (" + queryLemma.lemma() + ") "
									+ queryTerms.get(queryForm) + " matches value `" + valueForm + "` ("
									+ valueLemma.lemma() + ") " + indexTerms.get(valueForm)
							);
						}
					}
				}
			}
		}
		return new Recall(
			accentTypedForms - accentTypedMisses.size(), accentTypedForms, accentTypedMisses,
			pairs - misses.size(), pairs, misses, falseMerges
		);
	}

	/**
	 * Analyses one word and returns the distinct terms the chain emitted for it.
	 *
	 * @param analyzer chain to run
	 * @param word     word to analyse
	 * @return the emitted terms
	 */
	@Nonnull
	private static Set<String> termsOf(@Nonnull FulltextAnalyzer analyzer, @Nonnull String word) {
		final List<AnalyzedTerm> analyzedTerms = analyzer.getTerms(word);
		final Set<String> terms = new LinkedHashSet<>(analyzedTerms.size());
		for (final AnalyzedTerm analyzedTerm : analyzedTerms) {
			terms.add(analyzedTerm.term());
		}
		return terms;
	}

	/**
	 * Tells whether a query's terms reach a value's terms — an exact term match, which is what the index does.
	 *
	 * @param queryTerms terms the query text produced
	 * @param valueTerms terms the stored value produced
	 * @return true when the two share at least one term
	 */
	private static boolean intersects(@Nonnull Set<String> queryTerms, @Nonnull Set<String> valueTerms) {
		for (final String queryTerm : queryTerms) {
			if (valueTerms.contains(queryTerm)) {
				return true;
			}
		}
		return false;
	}

	@Nested
	@DisplayName("Czech")
	class Czech {

		@Test
		@DisplayName("A bare-typed query finds its own form and the other forms of its lemma")
		void shouldFindEveryStoredFormFromABareTypedQuery() {
			final Recall recall = measure(
				new Locale("cs"),
				CzechAnalysisFixture.VOCABULARY,
				CzechAnalysisFixture.CONFUSABLE_LEMMAS,
				Lemma::stripAccents
			);
			recall.assertAccentTypedMatched(119, 119);
			recall.assertMatched(348, 348);
			// 54 one-way merges, all of them inside the fixture's deliberately confusable pairs - the price of
			// the forks that make `pánští`/`pánská` and `stůl`/`stolu` converge, measured rather than assumed
			recall.assertFalseMergesAtMost(54);
		}

	}

	@Nested
	@DisplayName("Slovak")
	class Slovak {

		@Test
		@DisplayName("A bare-typed query finds its own form and the other forms of its lemma")
		void shouldFindEveryStoredFormFromABareTypedQuery() {
			final Recall recall = measure(
				new Locale("sk"),
				SlovakAnalysisFixture.VOCABULARY,
				SlovakAnalysisFixture.CONFUSABLE_LEMMAS,
				Lemma::stripAccents
			);
			// the four outstanding pairs are the documented cost of the in-house stemmer's deliberately omitted
			// paradigms; the chain this replaced scored 0 of 323 because it did not stem at all
			recall.assertAccentTypedMatched(125, 125);
			// the four outstanding pairs are the documented cost of the in-house stemmer's deliberately omitted
			// paradigms; the fold-only chain this replaced scored 0 of 323, because it did not stem at all
			recall.assertMatched(351, 355);
			// and it buys that recall at no precision cost whatsoever
			recall.assertFalseMergesAtMost(0);
		}

	}

	@Nested
	@DisplayName("Polish")
	class Polish {

		@Test
		@DisplayName("A bare-typed query finds its own form and the other forms of its lemma")
		void shouldFindEveryStoredFormFromABareTypedQuery() {
			final Recall recall = measure(
				new Locale("pl"),
				PolishAnalysisFixture.VOCABULARY,
				PolishAnalysisFixture.CONFUSABLE_LEMMAS,
				PolishAnalysisFixture::bareType
			);
			recall.assertAccentTypedMatched(62, 62);
			recall.assertMatched(148, 175);
			// the 24 merges come from the fixture's `ł`-probing pairs, which fold onto one another by
			// construction - folding `ł` to `l` is what makes a bare-typed Polish query possible at all
			recall.assertFalseMergesAtMost(24);
		}

	}

	@Nested
	@DisplayName("Romanian")
	class Romanian {

		@Test
		@DisplayName("A bare-typed query finds its own form and the other forms of its lemma")
		void shouldFindEveryStoredFormFromABareTypedQuery() {
			final Recall recall = measure(
				new Locale("ro"),
				RomanianAnalysisFixture.VOCABULARY,
				RomanianAnalysisFixture.CONFUSABLE_LEMMAS,
				Lemma::stripAccents
			);
			recall.assertAccentTypedMatched(49, 49);
			recall.assertMatched(99, 120);
			recall.assertFalseMergesAtMost(0);
		}

	}

	/**
	 * What one language's measurement produced.
	 *
	 * @param accentTypedMatched accented forms their own bare typing found
	 * @param accentTypedForms   accented forms measured
	 * @param accentTypedMisses  the forms that were not found, carried verbatim so a failure names them
	 * @param matched            ordered same-lemma pairs a bare-typed query found
	 * @param pairs              ordered same-lemma pairs measured
	 * @param misses             the pairs that were not found, carried verbatim so a failure names them
	 * @param falseMerges        ordered cross-lemma pairs the chains merged, carried verbatim
	 */
	private record Recall(
		int accentTypedMatched,
		int accentTypedForms,
		@Nonnull List<String> accentTypedMisses,
		int matched,
		int pairs,
		@Nonnull List<String> misses,
		@Nonnull List<String> falseMerges
	) {

		/**
		 * Asserts the accent-typed recall - a bare-typed word finding its own stored form - is exactly the
		 * pinned score.
		 *
		 * @param expectedMatched pinned number of found forms
		 * @param expectedForms   pinned number of measured forms
		 */
		void assertAccentTypedMatched(int expectedMatched, int expectedForms) {
			assertEquals(
				expectedForms, this.accentTypedForms,
				"The fixture no longer offers the pinned number of accented forms - the pinned recall below "
					+ "is not comparable until this is understood."
			);
			assertEquals(
				expectedMatched, this.accentTypedMatched,
				"Accent-typed recall moved. Outstanding forms:\n" + String.join("\n", this.accentTypedMisses)
			);
		}

		/**
		 * Asserts the bare-typed recall is exactly the pinned score.
		 *
		 * @param expectedMatched pinned number of found pairs
		 * @param expectedPairs   pinned number of measured pairs
		 */
		void assertMatched(int expectedMatched, int expectedPairs) {
			assertEquals(
				expectedPairs, this.pairs,
				"The fixture no longer offers the pinned number of measurable pairs - the pinned recall below "
					+ "is not comparable until this is understood."
			);
			assertEquals(
				expectedMatched, this.matched,
				"Bare-typed recall moved. Outstanding pairs:\n" + String.join("\n", this.misses)
			);
		}

		/**
		 * Asserts the chains merge at most the pinned number of unrelated form pairs.
		 *
		 * @param expected pinned upper bound on merged cross-lemma pairs
		 */
		void assertFalseMergesAtMost(int expected) {
			assertEquals(
				expected, this.falseMerges.size(),
				"False merges moved. Merged pairs:\n" + String.join("\n", this.falseMerges)
			);
		}

	}

}
