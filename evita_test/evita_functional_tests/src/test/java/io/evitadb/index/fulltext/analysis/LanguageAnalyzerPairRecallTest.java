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

import io.evitadb.index.fulltext.analysis.AnalysisApproachMeasurer.MatchStrategy;
import io.evitadb.index.fulltext.analysis.AnalysisApproachMeasurer.Measurement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins what the asymmetric analyzer pairs actually buy, per language: **a query typed without diacritics finds
 * every stored inflected form of the same word**, and does not thereby collapse words that mean different
 * things.
 *
 * Two metrics, both taken from the records the design was chosen on and computed by the same
 * {@link AnalysisApproachMeasurer} that produced those records. Every accented form of the language's fixture
 * is typed on a bare keyboard and run through the language's `*-search` chain; its terms then have to share at
 * least one term with the `*index*` chain's terms of
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
	 * Runs the language's built-in analyzer pair over its fixture under exact term matching — what the index
	 * does — and returns the measurement.
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
		return new Recall(
			AnalysisApproachMeasurer.measure(
				index.getAnalyzerName() + "/" + search.getAnalyzerName(),
				index, search, MatchStrategy.EXACT, vocabulary, confusableLemmas, bareTyper
			)
		);
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
			// all 24 merges sit inside three planted confusable pairs whose members fold onto the same string
			// before any stemmer runs - `łoś`/`los` and `skała`/`skala` (18, the stroked `ł`) and
			// `pączek`/`paczka` (6, the nasal `ą`). That is the price of having a fold lane at all, not a cost
			// of the Snowball switch: the merged terms are identical folded surfaces. The count is NOT
			// comparable to the 8 recorded for the old symmetric Stempel chain - the query side now also emits
			// the surface variant and every stem fork, so it reaches more of the same planted collisions.
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
	 * The three pinned numbers of one language's measurement, with the assertions that name every failing case
	 * when a pin moves.
	 *
	 * @param measurement the full measurement of the language's analyzer pair
	 */
	private record Recall(@Nonnull Measurement measurement) {

		/**
		 * Asserts the accent-typed recall - a bare-typed word finding its own stored form - is exactly the
		 * pinned score.
		 *
		 * @param expectedMatched pinned number of found forms
		 * @param expectedForms   pinned number of measured forms
		 */
		void assertAccentTypedMatched(int expectedMatched, int expectedForms) {
			assertEquals(
				expectedForms, this.measurement.accentedFormCount(),
				"The fixture no longer offers the pinned number of accented forms - the pinned recall below "
					+ "is not comparable until this is understood."
			);
			assertEquals(
				expectedMatched, this.measurement.accentTypedMatched(),
				"Accent-typed recall moved. Outstanding forms:\n"
					+ String.join("\n", this.measurement.accentTypingMisses())
			);
		}

		/**
		 * Asserts the bare-typed cross-form recall is exactly the pinned score.
		 *
		 * @param expectedMatched pinned number of found pairs
		 * @param expectedPairs   pinned number of measured pairs
		 */
		void assertMatched(int expectedMatched, int expectedPairs) {
			assertEquals(
				expectedPairs, this.measurement.bareTypedCrossFormPairCount(),
				"The fixture no longer offers the pinned number of measurable pairs - the pinned recall below "
					+ "is not comparable until this is understood."
			);
			assertEquals(
				expectedMatched, this.measurement.bareTypedCrossFormMatched(),
				"Bare-typed recall moved. Outstanding pairs:\n"
					+ String.join("\n", this.measurement.bareTypedCrossFormMisses())
			);
		}

		/**
		 * Asserts the chains merge at most the pinned number of unrelated form pairs.
		 *
		 * @param expected pinned upper bound on merged cross-lemma pairs
		 */
		void assertFalseMergesAtMost(int expected) {
			assertEquals(
				expected, this.measurement.falseMerges().size(),
				"False merges moved. Merged pairs:\n" + String.join("\n", this.measurement.falseMerges())
			);
		}

	}

}
