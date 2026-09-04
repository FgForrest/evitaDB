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
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of the full-text tokenization contract: what {@link FulltextAnalyzer} emits for a given text, that it
 * drives the Lucene stream protocol correctly, that it feeds the chain NFC-normalized input, and that the
 * built-in language analyzers stem the way their upstream Lucene expectations say they should.
 *
 * The language expectations are deliberately **taken from upstream Lucene's own analyzer tests** rather than
 * invented here: those are the best available specification of the chains' behaviour, and copying them buys
 * detection of a behaviour change between Lucene versions for free. Where evitaDB's analyzer adds diacritics
 * folding on top of the upstream chain (Czech, Slovak) the expectation is the folded form of the upstream one.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Full-text analyzer tokenization contract")
@Tag(ENGINE)
@Tag(FULLTEXT)
class FulltextAnalyzerTest {

	private static final String ENTITY_TYPE = "PRODUCT";
	private static final Locale CZECH = new Locale("cs", "CZ");
	private static final Locale ENGLISH = new Locale("en");
	private static final Locale GERMAN = new Locale("de");
	private static final Locale POLISH = new Locale("pl");
	private static final Locale SLOVAK = new Locale("sk");

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
	 * Analyses `text` with the index-time analyzer of the given locale and returns the produced terms.
	 *
	 * @param locale locale whose analyzer should analyse the text
	 * @param text   text to analyse
	 * @return terms produced by the chain
	 */
	@Nonnull
	private List<AnalyzedTerm> analyze(@Nonnull Locale locale, @Nonnull String text) {
		return this.registry.getIndexAnalyzer(ENTITY_TYPE, locale).getTerms(text);
	}

	/**
	 * Analyses `text` with the index-time analyzer of the given locale and returns only the terms themselves.
	 *
	 * @param locale locale whose analyzer should analyse the text
	 * @param text   text to analyse
	 * @return terms produced by the chain, without offsets and positions
	 */
	@Nonnull
	private List<String> terms(@Nonnull Locale locale, @Nonnull String text) {
		final List<AnalyzedTerm> analyzedTerms = analyze(locale, text);
		final List<String> result = new ArrayList<>(analyzedTerms.size());
		for (final AnalyzedTerm analyzedTerm : analyzedTerms) {
			result.add(analyzedTerm.term());
		}
		return result;
	}

	@Nested
	@DisplayName("Lucene stream protocol")
	class StreamProtocol {

		@Test
		@DisplayName("Emits every term with its offsets and position increment")
		void shouldEmitTermsWithOffsetsAndPositionIncrements() {
			// `Pokud` and `o` are Czech stop words, so both surviving terms are preceded by a dropped one
			final List<AnalyzedTerm> analyzedTerms = analyze(CZECH, "Pokud mluvime o volnem");
			assertEquals(2, analyzedTerms.size());

			final AnalyzedTerm first = analyzedTerms.get(0);
			assertEquals("mluvim", first.term());
			assertEquals("mluvime", first.surfaceForm());
			assertEquals(6, first.startOffset());
			assertEquals(13, first.endOffset());
			// a dropped stop word leaves a gap - this is the observable difference between "term removed" and
			// "term never there", and phrase queries depend on it
			assertEquals(2, first.positionIncrement());

			final AnalyzedTerm second = analyzedTerms.get(1);
			assertEquals("voln", second.term());
			assertEquals("volnem", second.surfaceForm());
			assertEquals(16, second.startOffset());
			assertEquals(22, second.endOffset());
			assertEquals(2, second.positionIncrement());
		}

		@Test
		@DisplayName("Surface form is exactly the input substring the offsets point at")
		void shouldEmitSurfaceFormMatchingInputSubstring() {
			final String text = "Černá pánská obuv";
			for (final AnalyzedTerm analyzedTerm : analyze(CZECH, text)) {
				assertEquals(
					text.substring(analyzedTerm.startOffset(), analyzedTerm.endOffset()),
					analyzedTerm.surfaceForm(),
					"Surface form of term `" + analyzedTerm.term() + "` does not match its own offsets."
				);
			}
		}

		@Test
		@DisplayName("Surface form survives the stemming and folding that produced the term")
		void shouldRetainSurfaceFormLostByTheChain() {
			final List<AnalyzedTerm> analyzedTerms = analyze(CZECH, "Černá");
			assertEquals(1, analyzedTerms.size());
			// the term is stemmed AND folded, the surface form is neither - it is the only place the accented
			// original survives, and recovering it after the chain has run is impossible
			assertEquals("cern", analyzedTerms.get(0).term());
			assertEquals("Černá", analyzedTerms.get(0).surfaceForm());
		}

		@Test
		@DisplayName("Streaming and collecting forms produce identical results")
		void shouldStreamSameTermsAsItCollects() {
			final String text = "Pokud mluvime o volnem case";
			final List<AnalyzedTerm> streamed = new ArrayList<>(8);
			czechAnalyzer().analyze(
				text,
				(term, surfaceForm, startOffset, endOffset, positionIncrement) -> streamed.add(
					new AnalyzedTerm(term, surfaceForm.get(), startOffset, endOffset, positionIncrement)
				)
			);
			assertIterableEquals(analyze(CZECH, text), streamed);
		}

		@Test
		@DisplayName("Repeated analysis with the same instance yields the same terms")
		void shouldProduceStableResultsOnRepeatedUse() {
			// guards the Lucene stream protocol: a missing reset() between two runs over one reused component
			// set yields empty or truncated output on the second run rather than an error
			final FulltextAnalyzer analyzer = czechAnalyzer();
			final List<AnalyzedTerm> firstRun = analyzer.getTerms("Česká Republika");
			final List<AnalyzedTerm> secondRun = analyzer.getTerms("Česká Republika");
			assertIterableEquals(firstRun, secondRun);
			assertEquals(2, secondRun.size());
		}

		@Test
		@DisplayName("Text made purely of stop words yields no terms")
		void shouldProduceNoTermsForStopWordsOnly() {
			assertTrue(analyze(ENGLISH, "the and of").isEmpty());
		}

		/**
		 * Shortcut to the Czech index-time analyzer for tests needing the instance rather than its output.
		 *
		 * @return Czech index-time analyzer
		 */
		@Nonnull
		private FulltextAnalyzer czechAnalyzer() {
			return FulltextAnalyzerTest.this.registry.getIndexAnalyzer(ENTITY_TYPE, CZECH);
		}

	}

	@Nested
	@DisplayName("Unicode normalization on the boundary")
	class UnicodeNormalization {

		@Test
		@DisplayName("NFD input produces the same terms as NFC input")
		void shouldProduceSameTermsForNfdAndNfcInput() {
			// THIS TEST IS THE ONLY GUARD of the NFC normalization inside FulltextAnalyzer#analyze. Remove that
			// one line and the Czech stemmer stops matching on precomposed characters - it does not fail, does
			// not throw, it simply stops stemming every word with a diacritic. Without this test, deleting the
			// normalization as apparent redundancy leaves the whole suite green.
			final String nfc = Normalizer.normalize("Česká Republika", Normalizer.Form.NFC);
			final String nfd = Normalizer.normalize("Česká Republika", Normalizer.Form.NFD);
			assertTrue(nfc.length() < nfd.length(), "The two forms are expected to differ in length.");

			assertIterableEquals(terms(CZECH, nfc), terms(CZECH, nfd));
			// and both must be the stemmed - not merely lowercased - form; comparing the two against each other
			// alone would also pass if the stemmer had stopped working for both
			assertIterableEquals(List.of("cesk", "republik"), terms(CZECH, nfd));
		}

		@Test
		@DisplayName("Offsets and surface forms of NFD input refer to its normalized form")
		void shouldReportOffsetsIntoNormalizedText() {
			final String nfd = Normalizer.normalize("Česká Republika", Normalizer.Form.NFD);
			final List<AnalyzedTerm> analyzedTerms = analyze(CZECH, nfd);
			assertEquals(2, analyzedTerms.size());
			// offsets index into the NFC form the analyzer was fed, not into the decomposed argument - which is
			// why `Česká` spans 5 characters here and 7 in the caller's string
			assertEquals(0, analyzedTerms.get(0).startOffset());
			assertEquals(5, analyzedTerms.get(0).endOffset());
			assertEquals("Česká", analyzedTerms.get(0).surfaceForm());
		}

	}

	@Nested
	@DisplayName("Czech language analyzer")
	class Czech {

		@Test
		@DisplayName("Stems and folds diacritics away")
		void shouldStemAndFoldDiacritics() {
			// upstream `TestCzechAnalyzer` expects `česk`, `republik`; evitaDB folds diacritics after the
			// stemmer, so the expectation is the folded form of it
			assertIterableEquals(List.of("cesk", "republik"), terms(CZECH, "Česká Republika"));
		}

		@Test
		@DisplayName("Drops stop words")
		void shouldDropStopWords() {
			assertIterableEquals(List.of("mluvim", "voln"), terms(CZECH, "Pokud mluvime o volnem"));
		}

		@Test
		@DisplayName("Converges declension forms of a masculine animate noun")
		void shouldConvergeDeclensionForms() {
			assertIterableEquals(List.of("pan", "pan", "pan", "pan"), terms(CZECH, "pán páni pánové pána"));
		}

		@Test
		@DisplayName("Converges declension forms of a masculine animate noun without diacritics")
		void shouldConvergeDeclensionFormsWithoutDiacritics() {
			assertIterableEquals(List.of("pan", "pan", "pan", "pan"), terms(CZECH, "pan pani panove pana"));
		}

		@Test
		@DisplayName("Converges declension forms of a hard-pattern noun")
		void shouldConvergeHardPatternForms() {
			assertIterableEquals(List.of("hrad", "hrad", "hrad"), terms(CZECH, "hrad hradu hradech"));
		}

		@Test
		@DisplayName("Rewrites the palatalized consonant, producing a non-word stem")
		void shouldRewritePalatalizedConsonant() {
			// the Czech stemmer is algorithmic, so some stems are not words at all - `muž` becomes `muh`. That
			// is fine for matching (forms converge consistently) but it is why the term dictionary is not
			// human-readable and why a suggester must never show a raw term to a user.
			assertIterableEquals(List.of("muh", "muh", "muh"), terms(CZECH, "muž muži muže"));
		}

		@Test
		@DisplayName("Matches an unaccented query against an accented value")
		void shouldMatchUnaccentedQueryAgainstAccentedValue() {
			// this is what the folding buys: without it the two would be two Levenshtein edits apart, i.e. the
			// entire typo budget spent on a keyboard rather than on a typo
			assertIterableEquals(terms(CZECH, "černá"), terms(CZECH, "cerna"));
		}

	}

	@Nested
	@DisplayName("English language analyzer")
	class English {

		@Test
		@DisplayName("Stems plurals, strips possessives and drops stop words")
		void shouldStemStripPossessivesAndDropStopWords() {
			// upstream `TestEnglishAnalyzer`
			assertIterableEquals(List.of("book"), terms(ENGLISH, "books"));
			assertIterableEquals(List.of("steven"), terms(ENGLISH, "steven's"));
			assertTrue(terms(ENGLISH, "the").isEmpty());
		}

	}

	@Nested
	@DisplayName("German language analyzer")
	class German {

		@Test
		@DisplayName("Converges declension forms")
		void shouldConvergeDeclensionForms() {
			// upstream `TestGermanAnalyzer`
			assertIterableEquals(List.of("tisch", "tisch", "tisch"), terms(GERMAN, "Tisch Tische Tischen"));
		}

		@Test
		@DisplayName("Folds umlauts through its own normalization filter, without an extra folding pass")
		void shouldFoldUmlautsItself() {
			// `GermanAnalyzer` runs `GermanNormalizationFilter`, which is why the German analyzer deliberately
			// does NOT get evitaDB's diacritics folding on top - the transliterated spelling already meets the
			// accented one here
			assertIterableEquals(
				List.of("schaltflach", "schaltflach"),
				terms(GERMAN, "Schaltflächen Schaltflaechen")
			);
		}

	}

	@Nested
	@DisplayName("Polish language analyzer")
	class Polish {

		@Test
		@DisplayName("Converges declension forms through the stempel stemmer")
		void shouldConvergeDeclensionForms() {
			// upstream `TestPolishAnalyzer`
			assertIterableEquals(List.of("student", "student"), terms(POLISH, "studenta studenci"));
		}

	}

	@Nested
	@DisplayName("Slovak language analyzer")
	class Slovak {

		@Test
		@DisplayName("Folds diacritics")
		void shouldFoldDiacritics() {
			assertIterableEquals(List.of("topanky"), terms(SLOVAK, "topánky"));
		}

		@Test
		@DisplayName("Does not converge word forms, and does not merge unrelated words either")
		void shouldNotConvergeWordForms() {
			// Lucene ships no Slovak analyzer and no Slovak stop-word list, so the analyzer is the no-stemmer
			// baseline. This test records that choice rather than guarding a behaviour: recall is worse (the two
			// forms below do not meet) but precision never suffers - nothing is collapsed by mistake, which in
			// an e-shop is the failure that costs more. If a Slovak stemmer is ever adopted, this expectation is
			// supposed to change.
			assertIterableEquals(List.of("topanky", "topanka"), terms(SLOVAK, "topánky topánka"));
		}

	}

	@Nested
	@DisplayName("Generic fallback analyzer")
	class GenericFallback {

		@Test
		@DisplayName("Tokenizes and lowercases an unknown language without stemming it")
		void shouldTokenizeAndLowercaseOnly() {
			final Locale finnish = new Locale("fi");
			assertEquals(
				BuiltInAnalyzers.GENERIC_ANALYZER_NAME,
				FulltextAnalyzerTest.this.registry.getIndexAnalyzer(ENTITY_TYPE, finnish).getAnalyzerName()
			);
			// no stemming - the two forms stay apart - and no folding either
			assertIterableEquals(List.of("kirjat", "kirja"), terms(finnish, "Kirjat kirja"));
		}

	}

}
