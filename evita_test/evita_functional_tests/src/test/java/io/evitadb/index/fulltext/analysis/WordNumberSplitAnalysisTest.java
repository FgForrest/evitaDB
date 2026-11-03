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

import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalCatalogSchemaBuilder;
import io.evitadb.index.analysis.AnalysisStepRegistry;
import io.evitadb.test.fulltext.AnalyzedTerm;
import io.evitadb.test.fulltext.FulltextAnalysisTestSupport;
import org.apache.lucene.analysis.miscellaneous.WordDelimiterGraphFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import java.util.List;
import java.util.Locale;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.fulltext.FulltextAnalysisTestSupport.collectTerms;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the analysis behind the word/number split step of
 * `documentation/adr/2026-08-24-fulltext-search-lucene-vs-inhouse/prototypes/p5-analyzers.md` §4.6 point 2 —
 * splitting a mixed token such as `iPhone15` into its word and number parts so that `iphone 15` finds it —
 * before the step is wired into a built-in chain. Two things are established:
 *
 * 1. **The baseline.** Today's built-in chains keep a mixed token whole, so a two-token query and a one-token
 *    value never meet. That is the gap the step exists to close.
 * 2. **The accepted contract.** Lucene's `WordDelimiterGraphFilter` directly after the tokenizer — before
 *    lowercasing, stop words and the stemmer — with the original preserved and offsets adjusted. Parts are then
 *    stemmed and folded exactly like standalone words, each part's surface form is the part, and positions follow
 *    Lucene's graph convention, which is what lets the split value and the spaced-out query align.
 *
 * Appending the step to the *finished* analyzer instead — the shape a wrapper can reach, and the one the old
 * client used — was measured and rejected: on a stemming chain the word part comes out unstemmed and never
 * meets the term the same word produces on its own, and once the stemmer has changed the token's length the
 * parts' offsets cannot be adjusted. The comparison is recorded in `p5-word-number-split-comparison.md`.
 *
 * The split chain is compiled from a catalog language definition: the accepted placement cannot
 * be reached by wrapping a Lucene language analyzer, so wiring it means composing the built-in chains from their
 * components, and the per-attribute switch that turns the step on arrives with the analyzer parameters
 * (`schema-design.md` §6.5). Until both exist the filter has no production caller; these tests are the contract it
 * is wired against when it gets one.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Word/number split step of the full-text analysis chain")
@Tag(ENGINE)
@Tag(FULLTEXT)
class WordNumberSplitAnalysisTest {

	private static final String ENTITY_TYPE = "PRODUCT";
	private static final Locale CZECH = new Locale("cs");

	private FulltextAnalyzerRegistry registry;
	/**
	 * The built-in Czech chain as the registry hands it out today.
	 */
	private FulltextAnalyzer production;
	/**
	 * The Czech chain recomposed with the split step between the tokenizer and the stemmer — the accepted one.
	 */
	private FulltextAnalyzer splitInChain;

	@BeforeEach
	void setUp() {
		this.registry = new FulltextAnalyzerRegistry(new AnalysisStepRegistry());
		this.production = this.registry.getCompiledAnalyzer(
			FulltextAnalysisTestSupport.SCHEMA, "text", CZECH).index();
		final CatalogSchemaContract schema =
			new InternalCatalogSchemaBuilder(
				FulltextAnalysisTestSupport.SCHEMA)
				.withFulltextLanguage("cs", language -> language.wordNumberSplit()).toInstance();
		this.splitInChain = this.registry.getCompiledAnalyzer(schema, "text", CZECH).index();
	}

	@AfterEach
	void tearDown() {
		this.registry.close();
	}

	/**
	 * Analyses `text` and returns only the terms.
	 *
	 * @param analyzer chain to analyse with
	 * @param text     text to analyse
	 * @return terms produced by the chain, in order
	 */
	@Nonnull
	private static List<String> terms(@Nonnull FulltextAnalyzer analyzer, @Nonnull String text) {
		return collectTerms(analyzer, text).stream().map(AnalyzedTerm::term).toList();
	}

	/**
	 * Analyses `text` and returns only the surface forms.
	 *
	 * @param analyzer chain to analyse with
	 * @param text     text to analyse
	 * @return surface forms of the produced terms, in order
	 */
	@Nonnull
	private static List<String> surfaceForms(@Nonnull FulltextAnalyzer analyzer, @Nonnull String text) {
		return collectTerms(analyzer, text).stream().map(AnalyzedTerm::surfaceForm).toList();
	}

	@Nested
	@DisplayName("Built-in chains today")
	class BuiltInChainsToday {

		@Test
		@DisplayName("Keep a mixed word/number token whole")
		void shouldKeepMixedTokenWhole() {
			assertIterableEquals(List.of("iphone15"), terms(WordNumberSplitAnalysisTest.this.production, "iPhone15"));
			assertIterableEquals(List.of("xc90"), terms(WordNumberSplitAnalysisTest.this.production, "XC90"));
		}

		@Test
		@DisplayName("Cannot meet a two-token query with a one-token value")
		void shouldNotMeetTwoTokenQueryWithOneTokenValue() {
			// this is the gap the split step exists to close: the value indexes one term the query never produces
			final List<String> value = terms(WordNumberSplitAnalysisTest.this.production, "iPhone15");
			final List<String> query = terms(WordNumberSplitAnalysisTest.this.production, "iPhone 15");
			assertIterableEquals(List.of("iphon", "15"), query);
			assertTrue(query.stream().noneMatch(value::contains));
		}

		@Test
		@DisplayName("Mangle a code by stemming, but symmetrically")
		void shouldMangleCodeSymmetrically() {
			// the argument §4.6 point 1 rests on when it declines to protect codes from analysis: the stemmer does
			// damage a code, yet the query side suffers the same damage, so a code still finds itself
			assertIterableEquals(List.of("gtx1080t"), terms(WordNumberSplitAnalysisTest.this.production, "GTX1080Ti"));
			assertIterableEquals(terms(WordNumberSplitAnalysisTest.this.production, "GTX1080Ti"), terms(WordNumberSplitAnalysisTest.this.production, "gtx1080ti"));
		}

	}

	@Nested
	@DisplayName("Split step between the tokenizer and the stemmer — accepted contract")
	class SplitBetweenTokenizerAndStemmer {

		@Test
		@DisplayName("Emits the original token and both of its parts")
		void shouldEmitOriginalAndParts() {
			assertIterableEquals(List.of("iphone15", "iphon", "15"), terms(
				WordNumberSplitAnalysisTest.this.splitInChain, "iPhone15"));
			assertIterableEquals(List.of("boty42", "bot", "42"), terms(WordNumberSplitAnalysisTest.this.splitInChain, "boty42"));
		}

		@Test
		@DisplayName("Stems and folds a part exactly like the same word standing alone")
		void shouldTreatPartLikeStandaloneWord() {
			// `xc` becomes `xk` through the stemmer's palatalization rewrite - a non-word, exactly as `XC` typed
			// on its own becomes, which is the point: the part lives in the same term space as ordinary words
			assertIterableEquals(List.of("xc90", "xk", "90"), terms(WordNumberSplitAnalysisTest.this.splitInChain, "XC90"));
			assertIterableEquals(List.of("xk"), terms(WordNumberSplitAnalysisTest.this.splitInChain, "XC"));
			for (final String[] tokenAndWord : new String[][]{
				{"boty42", "boty"}, {"iPhone15", "iPhone"}, {"Windows11", "Windows"}, {"XC90", "XC"}
			}) {
				assertTrue(
					terms(WordNumberSplitAnalysisTest.this.splitInChain, tokenAndWord[0]).containsAll(terms(
						WordNumberSplitAnalysisTest.this.splitInChain, tokenAndWord[1])),
					"word part of `" + tokenAndWord[0] + "` must analyse like `" + tokenAndWord[1] + "`"
				);
			}
		}

		@Test
		@DisplayName("Lets a code be found by its bare number - the old client's documented use case")
		void shouldFindCodeByItsBareNumber() {
			// the old client's documentation motivates the step with exactly this: the data holds `UHD7800` and
			// users are used to typing `7800`. A digit run is never touched by any stemmer, so the number part is
			// the same term on both sides
			assertIterableEquals(List.of("uhd7800", "uhd", "7800"), terms(WordNumberSplitAnalysisTest.this.splitInChain, "UHD7800"));
			assertIterableEquals(List.of("7800"), terms(WordNumberSplitAnalysisTest.this.splitInChain, "7800"));
			// the documentation's own examples: the word halves are palatalized by the Czech stemmer exactly as
			// `xyz` and `abc` typed alone are, the number halves stay untouched
			assertIterableEquals(List.of("123xyh", "123", "xyh"), terms(WordNumberSplitAnalysisTest.this.splitInChain, "123xyz"));
			assertIterableEquals(List.of("abc789", "abk", "789"), terms(WordNumberSplitAnalysisTest.this.splitInChain, "abc789"));
		}

		@Test
		@DisplayName("Lets a two-token query and a one-token value meet, whichever side the split token is on")
		void shouldMeetAcrossTokenBoundary() {
			final List<String> oneToken = terms(WordNumberSplitAnalysisTest.this.splitInChain, "iPhone15");
			final List<String> twoTokens = terms(WordNumberSplitAnalysisTest.this.splitInChain, "iPhone 15");
			assertIterableEquals(List.of("iphon", "15"), twoTokens);
			// value `iPhone15`, query `iphone 15`: every query term is indexed; value `iPhone 15`, query
			// `iphone15`: every value term is among the query's - the same containment read both ways
			assertTrue(oneToken.containsAll(twoTokens));
		}

		@Test
		@DisplayName("Points each part's offsets at the part, so its surface form is the part")
		void shouldReportOffsetsOfTheParts() {
			final List<AnalyzedTerm> analyzed = collectTerms(WordNumberSplitAnalysisTest.this.splitInChain, "iPhone15");
			assertEquals(new AnalyzedTerm("iphone15", "iPhone15", 0, 8, 1), analyzed.get(0));
			assertEquals(new AnalyzedTerm("iphon", "iPhone", 0, 6, 0), analyzed.get(1));
			assertEquals(new AnalyzedTerm("15", "15", 6, 8, 1), analyzed.get(2));
		}

		@Test
		@DisplayName("Places the first part at the original's position and the following parts after it")
		void shouldFollowGraphPositionConvention() {
			// Lucene's graph convention rather than the legacy client's "every part at one position": the parts
			// occupy consecutive positions and the original spans them. The payoff is that the split value and
			// the spaced-out query align position for position, which a phrase query depends on
			assertIterableEquals(
				List.of(1, 0, 1, 1),
				collectTerms(WordNumberSplitAnalysisTest.this.splitInChain, "boty42 kožené")
					.stream().map(AnalyzedTerm::positionIncrement).toList()
			);
			assertIterableEquals(
				List.of(1, 1, 1),
				collectTerms(WordNumberSplitAnalysisTest.this.splitInChain, "boty 42 kožené")
					.stream().map(AnalyzedTerm::positionIncrement).toList()
			);
		}

		@Test
		@DisplayName("Lets a query builder rebuild the split group from offsets, so any query shape stays possible")
		void shouldLetQueryBuilderRebuildGroupFromOffsets() {
			// evitaDB does not use Lucene's query parser, so which query a split token becomes - the old client's
			// `uhd7800 OR uhd OR 7800`, Lucene's `uhd7800 OR (uhd AND 7800)`, or something ranked - is the query
			// builder's choice. What it needs from the analyzer is to know which terms belong to one original
			// token, and that is carried by the offsets: every part's span nests inside its original's span, and
			// the next term whose span does not nest opens a new group. Position increments alone would NOT do -
			// the flattened graph gives the second part an increment of 1, as if it were a new word
			final List<AnalyzedTerm> analyzed = collectTerms(
				WordNumberSplitAnalysisTest.this.splitInChain,
				"TV UHD7800");
			assertIterableEquals(List.of("tv", "uhd7800", "uhd", "7800"), analyzed.stream().map(AnalyzedTerm::term).toList());
			assertIterableEquals(List.of(1, 1, 0, 1), analyzed.stream().map(AnalyzedTerm::positionIncrement).toList());
			final AnalyzedTerm original = analyzed.get(1);
			for (final AnalyzedTerm part : analyzed.subList(2, 4)) {
				assertTrue(
					part.startOffset() >= original.startOffset() && part.endOffset() <= original.endOffset(),
					"part `" + part.term() + "` must lie inside the span of `" + original.term() + "`"
				);
			}
			final AnalyzedTerm unrelated = analyzed.get(0);
			assertTrue(unrelated.endOffset() <= original.startOffset());
		}

		@Test
		@DisplayName("Splits at every letter/digit transition, not just the outer one")
		void shouldSplitAtEveryTransition() {
			// the legacy client split once, at the leading or the trailing digit run, so `GTX1080Ti` gave `GTX`
			// and `1080Ti`; here every run becomes a part, each with its own surface form
			assertIterableEquals(List.of("gtx1080t", "gtx", "1080", "ti"), terms(
				WordNumberSplitAnalysisTest.this.splitInChain, "GTX1080Ti"));
			assertIterableEquals(
				List.of("GTX1080Ti", "GTX", "1080", "Ti"), surfaceForms(WordNumberSplitAnalysisTest.this.splitInChain, "GTX1080Ti")
			);
		}

		@Test
		@DisplayName("Leaves pure words, pure numbers and already separated tokens alone")
		void shouldLeaveHomogeneousTokensAlone() {
			// an EAN is digits only, so the step does nothing for it - the motivating case is a mixed code such
			// as `XC90`, not a barcode
			assertIterableEquals(List.of("8594001234567"), terms(WordNumberSplitAnalysisTest.this.splitInChain, "8594001234567"));
			assertIterableEquals(List.of("42"), terms(WordNumberSplitAnalysisTest.this.splitInChain, "42"));
			assertIterableEquals(List.of("bot"), terms(WordNumberSplitAnalysisTest.this.splitInChain, "boty"));
			// the tokenizer already split on the hyphen, so there is nothing left to split
			assertIterableEquals(terms(WordNumberSplitAnalysisTest.this.production, "AB-123 wi-fi"), terms(WordNumberSplitAnalysisTest.this.splitInChain, "AB-123 wi-fi"));
		}

		@Test
		@DisplayName("Splits on inner punctuation the tokenizer kept — a documented trade-off")
		void shouldSplitInnerPunctuationToo() {
			// `StandardTokenizer` keeps `3.5mm` as one token; the delimiter table treats the dot as a boundary,
			// so the value yields `3` and `5` rather than `3.5`. Symmetric analysis keeps it harmless - the query
			// is cut the same way - and the legacy client produced `3` and `.5mm` here, which was worse
			assertIterableEquals(List.of("3.5mm", "3", "5", "mm"), terms(WordNumberSplitAnalysisTest.this.splitInChain, "3.5mm"));
		}

	}

}
