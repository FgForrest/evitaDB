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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
	private static final Locale ROMANIAN = new Locale("ro");

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

	/**
	 * Analyses `text` with the **search**-time analyzer of the given locale and returns the produced terms.
	 *
	 * @param locale locale whose analyzer should analyse the text
	 * @param text   text to analyse
	 * @return terms produced by the query chain
	 */
	@Nonnull
	private List<AnalyzedTerm> analyzeQuery(@Nonnull Locale locale, @Nonnull String text) {
		return this.registry.getSearchAnalyzer(ENTITY_TYPE, locale).getTerms(text);
	}

	/**
	 * Analyses `text` with the **search**-time analyzer of the given locale and returns only the terms
	 * themselves. For the four languages with a variant-emitting query chain this is the whole set of stems the
	 * word could have had before diacritics were folded away.
	 *
	 * @param locale locale whose analyzer should analyse the text
	 * @param text   text to analyse
	 * @return terms produced by the query chain
	 */
	@Nonnull
	private List<String> queryTerms(@Nonnull Locale locale, @Nonnull String text) {
		final List<AnalyzedTerm> analyzedTerms = analyzeQuery(locale, text);
		final List<String> result = new ArrayList<>(analyzedTerms.size());
		for (final AnalyzedTerm analyzedTerm : analyzedTerms) {
			result.add(analyzedTerm.term());
		}
		return result;
	}

	/**
	 * Asserts that a bare-typed query word reaches the index term of every accented form it should match — the
	 * property the asymmetric analyzer pair exists for, checked at the chain level rather than at the stemmer's.
	 *
	 * @param locale      locale of both chains
	 * @param typed       what the user types, without diacritics
	 * @param storedForms accented forms stored in the index that `typed` has to find
	 */
	private void assertQueryMeetsIndex(
		@Nonnull Locale locale,
		@Nonnull String typed,
		@Nonnull String... storedForms
	) {
		final List<String> queryVariants = queryTerms(locale, typed);
		for (final String storedForm : storedForms) {
			final List<String> indexTerms = terms(locale, storedForm);
			assertFalse(indexTerms.isEmpty(), "Stored form `" + storedForm + "` produced no index term.");
			assertTrue(
				queryVariants.containsAll(indexTerms),
				"Query `" + typed + "` emits " + queryVariants + ", which does not contain the index terms "
					+ indexTerms + " of the stored form `" + storedForm + "`."
			);
		}
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
		@DisplayName("Does not converge a bare-typed value with its accented forms - the query chain does that")
		void shouldNotConvergeBareTypedValuesOnTheIndexSide() {
			// `CzechStemmer` reads the accented text, so its table holds `ové` and not `ove`: a value someone
			// stored without diacritics keeps a longer stem than the same word stored with them. This is not a
			// gap - closing it on the index side would mean stemming folded text, which the whole asymmetric
			// pair exists to avoid; it is closed on the query side instead, see `VariantChains`.
			assertIterableEquals(List.of("pan", "pan", "panov", "pan"), terms(CZECH, "pan pani panove pana"));
			assertIterableEquals(List.of("pan", "pan", "pan", "pan"), terms(CZECH, "pán páni pánové pána"));
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
		@DisplayName("Stems through the vendored Snowball stemmer and folds diacritics away")
		void shouldStemAndFoldDiacritics() {
			// the index chain stems the ACCENTED text and folds afterwards, so the stroked `ł` and the acute `ó`
			// still reach the stemmer's native-orthography tables
			assertIterableEquals(List.of("ksiazk"), terms(POLISH, "książki"));
			// a capital-letter, stroked-letter word - the lowercase-before-fold guard
			assertIterableEquals(List.of("lodz"), terms(POLISH, "Łódź"));
		}

		@Test
		@DisplayName("Converges declension forms whose endings the Snowball table carries")
		void shouldConvergeDeclensionForms() {
			assertIterableEquals(List.of("ksiazk", "ksiazk"), terms(POLISH, "książka książki"));
			// `studenci` keeps its own stem - Snowball is rule-based and the `i` plural of a masculine personal
			// noun is not one of its endings. Stempel, the statistical stemmer this chain replaced, merged the
			// two; it also had no rule table over which the query-side variants could be built at all.
			assertIterableEquals(List.of("student", "studenc"), terms(POLISH, "studenta studenci"));
		}

	}

	@Nested
	@DisplayName("Slovak language analyzer")
	class Slovak {

		@Test
		@DisplayName("Stems and folds diacritics away")
		void shouldStemAndFoldDiacritics() {
			// Lucene ships no Slovak stemmer, so this chain runs the in-house `SlovakStemmer` on the accented
			// text and folds after it - the same shape every other language here uses
			assertIterableEquals(List.of("topank"), terms(SLOVAK, "topánky"));
			// a capital-letter word carrying the `ô` the normalization rewrites to `o`
			assertIterableEquals(List.of("stol", "stol"), terms(SLOVAK, "Stôl stola"));
		}

		@Test
		@DisplayName("Converges declension forms across the masculine-animate k/c alternation")
		void shouldConvergeDeclensionForms() {
			assertIterableEquals(List.of("topank", "topank"), terms(SLOVAK, "topánky topánka"));
			// `zákazník`/`zákazníci` is the alternation the in-house `normalize()` was written for
			assertIterableEquals(List.of("zakaznik", "zakaznik"), terms(SLOVAK, "zákazník zákazníci"));
		}

	}

	@Nested
	@DisplayName("Romanian language analyzer")
	class Romanian {

		@Test
		@DisplayName("Stems and folds diacritics away")
		void shouldStemAndFoldDiacritics() {
			assertIterableEquals(List.of("bucur"), terms(ROMANIAN, "București"));
			assertIterableEquals(List.of("copii"), terms(ROMANIAN, "copiii"));
		}

		@Test
		@DisplayName("Both Romanian orthographies converge on one term")
		void shouldConvergeCommaBelowAndCedillaSpellings() {
			// `mașină` (modern comma-below, U+0219) and `maşină` (legacy cedilla, U+015F) are the same word; the
			// pinned Lucene's stop list and Snowball tables are written in cedilla only, so the index chain
			// normalizes the comma-below spellings into them before stemming
			assertIterableEquals(List.of("masin", "masin"), terms(ROMANIAN, "ma\u0219in\u0103 ma\u015Fin\u0103"));
		}

	}

	@Nested
	@DisplayName("Variant-emitting query chains")
	class VariantChains {

		@Test
		@DisplayName("Every variant of one word sits at one position")
		void shouldEmitEveryVariantAtOnePosition() {
			final List<AnalyzedTerm> analyzedTerms = analyzeQuery(CZECH, "formaty");
			assertTrue(analyzedTerms.size() > 1, "The word is supposed to fan out into several variants.");
			// the first variant carries the token's own increment, every further one carries zero - the synonym
			// shape. A query pipeline that does not OR the terms of one position asks the index for a word that
			// was never written and finds nothing.
			assertEquals(1, analyzedTerms.get(0).positionIncrement());
			for (int i = 1; i < analyzedTerms.size(); i++) {
				assertEquals(
					0, analyzedTerms.get(i).positionIncrement(),
					"Variant `" + analyzedTerms.get(i).term() + "` must share the first variant's position."
				);
			}
			// every variant reports the same surface form - they are readings of one typed word
			for (final AnalyzedTerm analyzedTerm : analyzedTerms) {
				assertEquals("formaty", analyzedTerm.surfaceForm());
			}
		}

		@Test
		@DisplayName("Czech fans a bare-typed word out into the stems it could have had")
		void shouldFanOutCzech() {
			assertIterableEquals(List.of("format", "form", "formaty"), queryTerms(CZECH, "formaty"));
			assertIterableEquals(List.of("pansk", "panst", "pansti"), queryTerms(CZECH, "pansti"));
			// what the index side could not converge on its own - see `Czech#shouldNotConvergeBareTypedValuesOnTheIndexSide`
			assertQueryMeetsIndex(CZECH, "panove", "pánové", "pán", "panove");
			assertQueryMeetsIndex(CZECH, "cerna", "černá", "černé");
			assertQueryMeetsIndex(CZECH, "pansti", "pánští", "pánská");
		}

		@Test
		@DisplayName("Slovak fans out without adding the surface form")
		void shouldFanOutSlovak() {
			// `stoličiek` indexes as `stolick`, and the bare-typed query has to reach it across two chained
			// fold-ambiguous rules (the `ie`-shortening and the epenthesis)
			assertTrue(queryTerms(SLOVAK, "stoliciek").contains("stolick"));
			assertQueryMeetsIndex(SLOVAK, "stoliciek", "stoličiek");
			assertQueryMeetsIndex(SLOVAK, "zakaznici", "zákazníci", "zákazník");
			// Slovak is the one language that does NOT add the unstemmed word as a variant: its rule forks
			// already cover the whole lexicon, so the surface form would only widen false merges
			assertIterableEquals(List.of("zakaznik"), queryTerms(SLOVAK, "zakaznici"));
		}

		@Test
		@DisplayName("Polish fans out and keeps the surface form as a variant")
		void shouldFanOutPolish() {
			assertIterableEquals(List.of("ksiazk", "ksiazki"), queryTerms(POLISH, "ksiazki"));
			assertQueryMeetsIndex(POLISH, "ksiazki", "książki", "książka");
			assertQueryMeetsIndex(POLISH, "lodz", "Łódź");
		}

		@Test
		@DisplayName("Romanian fans out and keeps the surface form as a variant")
		void shouldFanOutRomanian() {
			assertIterableEquals(List.of("masina", "masin"), queryTerms(ROMANIAN, "masina"));
			// both orthographies of the stored word are reachable from one bare-typed query
			assertQueryMeetsIndex(ROMANIAN, "masina", "ma\u0219in\u0103", "ma\u015Fin\u0103");
			assertQueryMeetsIndex(ROMANIAN, "bucuresti", "Bucure\u0219ti");
		}

		@Test
		@DisplayName("A language without a variant chain queries with its index analyzer")
		void shouldLeaveUniformLanguagesAlone() {
			assertIterableEquals(terms(ENGLISH, "books"), queryTerms(ENGLISH, "books"));
			assertIterableEquals(terms(GERMAN, "Tische"), queryTerms(GERMAN, "Tische"));
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
