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

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.AnalyzerWrapper;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.FlattenGraphFilter;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.core.StopFilter;
import org.apache.lucene.analysis.cz.CzechAnalyzer;
import org.apache.lucene.analysis.cz.CzechStemFilter;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.miscellaneous.WordDelimiterGraphFilter;
import org.apache.lucene.analysis.miscellaneous.WordDelimiterIterator;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the analysis behind the word/number split step of
 * `documentation/adr/2026-08-24-fulltext-search-lucene-vs-inhouse/prototypes/p5-analyzers.md` §4.6 point 2 —
 * splitting a mixed token such as `iPhone15` into its word and number parts so that `iphone 15` finds it —
 * before the step is wired into a built-in chain. The old client's own splitter is ported as
 * {@link LegacyWordWithNumberSplitFilter} so that its documented behaviour is measured here rather than
 * remembered; the written comparison is `p5-word-number-split-comparison.md` next to the plan. Three things are
 * established:
 *
 * 1. **The baseline.** Today's built-in chains keep a mixed token whole, so a two-token query and a one-token
 *    value never meet. That is the gap the step exists to close.
 * 2. **The wrapper placement is rejected.** The legacy client appended its splitter to the *finished* analyzer,
 *    and §4.6 originally described the step the same way. On a stemming chain that is wrong twice over: the word
 *    part comes out unstemmed and never meets the term the same word produces on its own (`boty42` yields
 *    `boty`, a query for `boty` yields `bot`), and once the stemmer has changed the token's length the parts'
 *    offsets cannot be adjusted, so every part reports the whole token as its surface form. The placement is
 *    harmless only on chains without a stemmer.
 * 3. **The accepted contract.** Lucene's `WordDelimiterGraphFilter` directly after the tokenizer — before
 *    lowercasing, stop words and the stemmer — with the original preserved and offsets adjusted. Parts are then
 *    stemmed and folded exactly like standalone words, each part's surface form is the part, and positions follow
 *    Lucene's graph convention, which is what lets the split value and the spaced-out query align.
 *
 * The chains are built here rather than in {@link BuiltInAnalyzers} on purpose: the accepted placement cannot
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
	/**
	 * The step's configuration: split where a letter meets a digit, emit both kinds of part, and keep the original
	 * token so that an exact query for the whole code still matches. No splitting on case change — `iPhone` is
	 * one word — and no catenation.
	 */
	private static final int SPLIT_FLAGS = WordDelimiterGraphFilter.GENERATE_WORD_PARTS
		| WordDelimiterGraphFilter.GENERATE_NUMBER_PARTS
		| WordDelimiterGraphFilter.SPLIT_ON_NUMERICS
		| WordDelimiterGraphFilter.PRESERVE_ORIGINAL;

	private final List<FulltextAnalyzer> builtChains = new ArrayList<>(4);
	private FulltextAnalyzerRegistry registry;
	/**
	 * The built-in Czech chain as the registry hands it out today.
	 */
	private FulltextAnalyzer production;
	/**
	 * The built-in Czech chain with the split step appended after it — the rejected placement.
	 */
	private FulltextAnalyzer splitAppended;
	/**
	 * The Czech chain recomposed with the split step between the tokenizer and the stemmer — the accepted one.
	 */
	private FulltextAnalyzer splitInChain;
	/**
	 * The built-in Czech chain wrapped by the old client's splitter, exactly as its `czech` analyzers were wired.
	 */
	private FulltextAnalyzer legacyOnCzech;
	/**
	 * The folding tokenizing chain (the built-in Slovak one, closest to the old client's `universal` analyzer)
	 * wrapped by the old client's splitter.
	 */
	private FulltextAnalyzer legacyOnGeneric;

	@BeforeEach
	void setUp() {
		this.registry = new FulltextAnalyzerRegistry();
		this.production = this.registry.getIndexAnalyzer(ENTITY_TYPE, CZECH);
		this.splitAppended = chain(
			"czech-split-appended",
			splitAppendedTo(new DiacriticsFoldingAnalyzerWrapper(new CzechAnalyzer()))
		);
		this.splitInChain = chain("czech-split-in-chain", splittingCzechChain());
		this.legacyOnCzech = chain(
			"czech-legacy-split",
			LegacyWordWithNumberSplitFilter.appendedTo(new DiacriticsFoldingAnalyzerWrapper(new CzechAnalyzer()))
		);
		this.legacyOnGeneric = chain(
			"generic-legacy-split",
			LegacyWordWithNumberSplitFilter.appendedTo(new DiacriticsFoldingAnalyzerWrapper(new TokenizingAnalyzer()))
		);
	}

	@AfterEach
	void tearDown() {
		this.builtChains.forEach(FulltextAnalyzer::close);
		this.builtChains.clear();
		this.registry.close();
	}

	/**
	 * Wraps a Lucene chain into the engine's analyzer holder — so that the terms come through exactly the code
	 * path production uses, NFC normalization included — and registers it for closing.
	 *
	 * @param name     name the chain is reported under
	 * @param analyzer the Lucene chain
	 * @return the wrapped analyzer
	 */
	@Nonnull
	private FulltextAnalyzer chain(@Nonnull String name, @Nonnull Analyzer analyzer) {
		final FulltextAnalyzer wrapped = new FulltextAnalyzer(name, AnalysisMode.ALL, analyzer);
		this.builtChains.add(wrapped);
		return wrapped;
	}

	/**
	 * Appends the split step to `in`.
	 *
	 * `adjustInternalOffsets` is on so that each part's offsets point at the part itself — that is what
	 * {@link AnalyzedTerm#surfaceForm()} is cut from. The graph the filter emits is flattened right away, because
	 * the term contract carries a position increment only and no position length.
	 *
	 * @param in stream to split
	 * @return the split stream
	 */
	@Nonnull
	private static TokenStream split(@Nonnull TokenStream in) {
		return new FlattenGraphFilter(
			new WordDelimiterGraphFilter(
				in, true, WordDelimiterIterator.DEFAULT_WORD_DELIM_TABLE, SPLIT_FLAGS, null
			)
		);
	}

	/**
	 * Builds the Czech chain from its components with the split step directly after the tokenizer. Apart from
	 * that step the chain is `CzechAnalyzer` plus the folding {@link BuiltInAnalyzers} puts after it.
	 *
	 * @return the Lucene chain
	 */
	@Nonnull
	private static Analyzer splittingCzechChain() {
		return new Analyzer() {
			@Override
			protected TokenStreamComponents createComponents(String fieldName) {
				final Tokenizer source = new StandardTokenizer();
				TokenStream stream = split(source);
				stream = new LowerCaseFilter(stream);
				stream = new StopFilter(stream, CzechAnalyzer.getDefaultStopSet());
				stream = new CzechStemFilter(stream);
				stream = new ASCIIFoldingFilter(stream);
				return new TokenStreamComponents(source, stream);
			}

			@Override
			protected TokenStream normalize(String fieldName, TokenStream in) {
				return new ASCIIFoldingFilter(new LowerCaseFilter(in));
			}
		};
	}

	/**
	 * Appends the split step to the end of a finished analyzer's chain — the only place a wrapper can put it.
	 *
	 * @param delegate the analyzer to wrap; closed together with the wrapper
	 * @return the wrapped analyzer
	 */
	@Nonnull
	private static Analyzer splitAppendedTo(@Nonnull Analyzer delegate) {
		return new AnalyzerWrapper(delegate.getReuseStrategy()) {
			@Override
			protected Analyzer getWrappedAnalyzer(String fieldName) {
				return delegate;
			}

			@Override
			protected TokenStreamComponents wrapComponents(String fieldName, TokenStreamComponents components) {
				return new TokenStreamComponents(components.getSource(), split(components.getTokenStream()));
			}

			@Override
			public void close() {
				super.close();
				delegate.close();
			}
		};
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
		return analyzer.getTerms(text).stream().map(AnalyzedTerm::term).toList();
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
		return analyzer.getTerms(text).stream().map(AnalyzedTerm::surfaceForm).toList();
	}

	@Nested
	@DisplayName("Built-in chains today")
	class BuiltInChainsToday {

		@Test
		@DisplayName("Keep a mixed word/number token whole")
		void shouldKeepMixedTokenWhole() {
			assertIterableEquals(List.of("iphone15"), terms(production, "iPhone15"));
			assertIterableEquals(List.of("xc90"), terms(production, "XC90"));
		}

		@Test
		@DisplayName("Cannot meet a two-token query with a one-token value")
		void shouldNotMeetTwoTokenQueryWithOneTokenValue() {
			// this is the gap the split step exists to close: the value indexes one term the query never produces
			final List<String> value = terms(production, "iPhone15");
			final List<String> query = terms(production, "iPhone 15");
			assertIterableEquals(List.of("iphon", "15"), query);
			assertTrue(query.stream().noneMatch(value::contains));
		}

		@Test
		@DisplayName("Mangle a code by stemming, but symmetrically")
		void shouldMangleCodeSymmetrically() {
			// the argument §4.6 point 1 rests on when it declines to protect codes from analysis: the stemmer does
			// damage a code, yet the query side suffers the same damage, so a code still finds itself
			assertIterableEquals(List.of("gtx1080t"), terms(production, "GTX1080Ti"));
			assertIterableEquals(terms(production, "GTX1080Ti"), terms(production, "gtx1080ti"));
		}

	}

	@Nested
	@DisplayName("Split step appended after the finished chain — rejected placement")
	class SplitAppendedAfterChain {

		@Test
		@DisplayName("Leaves the word part unstemmed, so it never meets the term the word produces alone")
		void shouldLeaveWordPartUnstemmed() {
			assertIterableEquals(List.of("boty42", "boty", "42"), terms(splitAppended, "boty42"));
			assertIterableEquals(List.of("bot"), terms(splitAppended, "boty"));
			assertIterableEquals(List.of("iphone15", "iphone", "15"), terms(splitAppended, "iPhone15"));
			assertIterableEquals(List.of("iphon"), terms(splitAppended, "iPhone"));
		}

		@Test
		@DisplayName("Cannot place the parts once the stemmer has changed the token's length")
		void shouldLoseOffsetsWhenStemmerChangedTokenLength() {
			// the stemmer shortened `gtx1080ti` to `gtx1080t`; the filter now sees a term shorter than the span
			// its offsets cover, gives up adjusting them and cuts the parts from the stemmed text - so every part
			// reports the whole token as its surface form, and the word part is `t`, a fragment of nothing
			final List<AnalyzedTerm> analyzed = splitAppended.getTerms("GTX1080Ti");
			assertIterableEquals(
				List.of("gtx1080t", "gtx", "1080", "t"),
				analyzed.stream().map(AnalyzedTerm::term).toList()
			);
			for (final AnalyzedTerm term : analyzed) {
				assertEquals("GTX1080Ti", term.surfaceForm());
			}
		}

		@Test
		@DisplayName("Still delivers the number part, which is why the old client got away with it")
		void shouldStillDeliverNumberPart() {
			// the documented use case - finding `UHD7800` by typing `7800` - works in either placement, because a
			// digit run has nothing for a stemmer to change; the placement decides the fate of the word half only
			assertIterableEquals(List.of("uhd7800", "uhd", "7800"), terms(splitAppended, "UHD7800"));
			assertTrue(terms(splitAppended, "UHD7800").containsAll(terms(splitAppended, "7800")));
		}

		@Test
		@DisplayName("Coincides with the in-chain placement only when the chain has no stemmer")
		void shouldMatchInChainPlacementOnNonStemmingChain() {
			// lowercasing and folding preserve length and never split, so around them the two placements are
			// the same chain - the wrapper is wrong for stemming chains specifically, not in general
			final FulltextAnalyzer appended = chain(
				"generic-split-appended", splitAppendedTo(new TokenizingAnalyzer())
			);
			final FulltextAnalyzer inChain = chain(
				"generic-split-in-chain",
				new Analyzer() {
					@Override
					protected TokenStreamComponents createComponents(String fieldName) {
						final Tokenizer source = new StandardTokenizer();
						return new TokenStreamComponents(source, new LowerCaseFilter(split(source)));
					}
				}
			);
			final String text = "iPhone15 GTX1080Ti boty42 8594001234567";
			assertIterableEquals(inChain.getTerms(text), appended.getTerms(text));
		}

	}

	@Nested
	@DisplayName("Split step between the tokenizer and the stemmer — accepted contract")
	class SplitBetweenTokenizerAndStemmer {

		@Test
		@DisplayName("Emits the original token and both of its parts")
		void shouldEmitOriginalAndParts() {
			assertIterableEquals(List.of("iphone15", "iphon", "15"), terms(splitInChain, "iPhone15"));
			assertIterableEquals(List.of("boty42", "bot", "42"), terms(splitInChain, "boty42"));
		}

		@Test
		@DisplayName("Stems and folds a part exactly like the same word standing alone")
		void shouldTreatPartLikeStandaloneWord() {
			// `xc` becomes `xk` through the stemmer's palatalization rewrite - a non-word, exactly as `XC` typed
			// on its own becomes, which is the point: the part lives in the same term space as ordinary words
			assertIterableEquals(List.of("xc90", "xk", "90"), terms(splitInChain, "XC90"));
			assertIterableEquals(List.of("xk"), terms(splitInChain, "XC"));
			for (final String[] tokenAndWord : new String[][]{
				{"boty42", "boty"}, {"iPhone15", "iPhone"}, {"Windows11", "Windows"}, {"XC90", "XC"}
			}) {
				assertTrue(
					terms(splitInChain, tokenAndWord[0]).containsAll(terms(splitInChain, tokenAndWord[1])),
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
			assertIterableEquals(List.of("uhd7800", "uhd", "7800"), terms(splitInChain, "UHD7800"));
			assertIterableEquals(List.of("7800"), terms(splitInChain, "7800"));
			// the documentation's own examples: the word halves are palatalized by the Czech stemmer exactly as
			// `xyz` and `abc` typed alone are, the number halves stay untouched
			assertIterableEquals(List.of("123xyh", "123", "xyh"), terms(splitInChain, "123xyz"));
			assertIterableEquals(List.of("abc789", "abk", "789"), terms(splitInChain, "abc789"));
		}

		@Test
		@DisplayName("Lets a two-token query and a one-token value meet, whichever side the split token is on")
		void shouldMeetAcrossTokenBoundary() {
			final List<String> oneToken = terms(splitInChain, "iPhone15");
			final List<String> twoTokens = terms(splitInChain, "iPhone 15");
			assertIterableEquals(List.of("iphon", "15"), twoTokens);
			// value `iPhone15`, query `iphone 15`: every query term is indexed; value `iPhone 15`, query
			// `iphone15`: every value term is among the query's - the same containment read both ways
			assertTrue(oneToken.containsAll(twoTokens));
		}

		@Test
		@DisplayName("Points each part's offsets at the part, so its surface form is the part")
		void shouldReportOffsetsOfTheParts() {
			final List<AnalyzedTerm> analyzed = splitInChain.getTerms("iPhone15");
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
				splitInChain.getTerms("boty42 kožené").stream().map(AnalyzedTerm::positionIncrement).toList()
			);
			assertIterableEquals(
				List.of(1, 1, 1),
				splitInChain.getTerms("boty 42 kožené").stream().map(AnalyzedTerm::positionIncrement).toList()
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
			final List<AnalyzedTerm> analyzed = splitInChain.getTerms("TV UHD7800");
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
			assertIterableEquals(List.of("gtx1080t", "gtx", "1080", "ti"), terms(splitInChain, "GTX1080Ti"));
			assertIterableEquals(
				List.of("GTX1080Ti", "GTX", "1080", "Ti"), surfaceForms(splitInChain, "GTX1080Ti")
			);
		}

		@Test
		@DisplayName("Leaves pure words, pure numbers and already separated tokens alone")
		void shouldLeaveHomogeneousTokensAlone() {
			// an EAN is digits only, so the step does nothing for it - the motivating case is a mixed code such
			// as `XC90`, not a barcode
			assertIterableEquals(List.of("8594001234567"), terms(splitInChain, "8594001234567"));
			assertIterableEquals(List.of("42"), terms(splitInChain, "42"));
			assertIterableEquals(List.of("bot"), terms(splitInChain, "boty"));
			// the tokenizer already split on the hyphen, so there is nothing left to split
			assertIterableEquals(terms(production, "AB-123 wi-fi"), terms(splitInChain, "AB-123 wi-fi"));
		}

		@Test
		@DisplayName("Splits on inner punctuation the tokenizer kept — a documented trade-off")
		void shouldSplitInnerPunctuationToo() {
			// `StandardTokenizer` keeps `3.5mm` as one token; the delimiter table treats the dot as a boundary,
			// so the value yields `3` and `5` rather than `3.5`. Symmetric analysis keeps it harmless - the query
			// is cut the same way - and the legacy client produced `3` and `.5mm` here, which was worse
			assertIterableEquals(List.of("3.5mm", "3", "5", "mm"), terms(splitInChain, "3.5mm"));
		}

		@Test
		@DisplayName("Registers and resolves through the registry like any other chain")
		void shouldComposeWithRegistry() {
			final String name = "czech-with-word-number-split";
			try (
				final FulltextAnalyzerRegistry custom = new FulltextAnalyzerRegistry(
					(entityType, locale) -> ENTITY_TYPE.equals(entityType) && "cs".equals(locale.getLanguage())
						? Optional.of(AnalyzerAssignment.uniform(name))
						: Optional.empty()
				)
			) {
				// stateless on both sides, hence no mode restriction: the value and the query must be split
				// identically, so the step belongs to every slot
				custom.register(name, WordNumberSplitAnalysisTest::splittingCzechChain);

				final FulltextAnalyzer index = custom.getIndexAnalyzer(ENTITY_TYPE, CZECH);
				assertEquals(name, index.getAnalyzerName());
				assertEquals(AnalysisMode.ALL, index.getMode());
				assertIterableEquals(List.of("iphone15", "iphon", "15"), terms(index, "iPhone15"));
				assertIterableEquals(
					terms(index, "iPhone15"),
					terms(custom.getSearchAnalyzer(ENTITY_TYPE, CZECH), "iPhone15")
				);
			}
		}

	}

	@Nested
	@DisplayName("The old client's splitter, ported and wired as it was there")
	class LegacyClientSplitter {

		@Test
		@DisplayName("Reproduces the documented examples on a chain without a stemmer")
		void shouldReproduceDocumentedExamples() {
			// the old client's documentation: "123xyz" yields 123xyz, 123, xyz and "abc789" yields abc789, abc,
			// 789. The parts come off a stack, hence the reversed order after the original - immaterial to an index
			assertIterableEquals(List.of("123xyz", "xyz", "123"), terms(legacyOnGeneric, "123xyz"));
			assertIterableEquals(List.of("abc789", "789", "abc"), terms(legacyOnGeneric, "abc789"));
		}

		@Test
		@DisplayName("Lets a code be found by its bare number - the documented use case")
		void shouldFindCodeByItsBareNumber() {
			// "UHD7800 and users are used to searching by the number only, i.e. 7800"
			assertIterableEquals(List.of("uhd7800", "7800", "uhd"), terms(legacyOnGeneric, "UHD7800"));
			assertIterableEquals(List.of("7800"), terms(legacyOnGeneric, "7800"));
			assertTrue(terms(legacyOnGeneric, "UHD7800").containsAll(terms(legacyOnGeneric, "7800")));
		}

		@Test
		@DisplayName("Splits once, at the leading digit run first, else at the trailing one")
		void shouldSplitOnlyOnce() {
			// the cases of the old client's own unit test: a number inside the token is ignored, and once a leading
			// run is found the trailing one is not looked at
			assertIterableEquals(List.of("abcd"), terms(legacyOnGeneric, "abcd"));
			assertIterableEquals(List.of("123456"), terms(legacyOnGeneric, "123456"));
			assertIterableEquals(
				List.of("123abc345xyz", "abc345xyz", "123"), terms(legacyOnGeneric, "123abc345xyz")
			);
			assertIterableEquals(
				List.of("123abc345xyz678", "abc345xyz678", "123"), terms(legacyOnGeneric, "123abc345xyz678")
			);
			// so the trailing run of a token that also starts with one is not searchable on its own
			assertTrue(terms(legacyOnGeneric, "123abc345xyz678").stream().noneMatch("678"::equals));
		}

		@Test
		@DisplayName("Puts both parts at the original's position and offsets")
		void shouldPutPartsAtOriginalPositionAndOffsets() {
			// position increment 0 makes the query parser OR the three terms together; the untouched offsets make
			// every part report the whole token as its surface form
			final List<AnalyzedTerm> analyzed = legacyOnGeneric.getTerms("UHD7800");
			assertEquals(new AnalyzedTerm("uhd7800", "UHD7800", 0, 7, 1), analyzed.get(0));
			assertEquals(new AnalyzedTerm("7800", "UHD7800", 0, 7, 0), analyzed.get(1));
			assertEquals(new AnalyzedTerm("uhd", "UHD7800", 0, 7, 0), analyzed.get(2));
		}

		@Test
		@DisplayName("Leaves the word part unstemmed on the Czech chain, as the Lucene filter does in that place")
		void shouldLeaveWordPartUnstemmedOnCzechChain() {
			// the old client wrapped its Czech analyzers the same way, so its word parts never met a stemmed
			// query either - the defect is the placement, not the algorithm
			assertIterableEquals(List.of("boty42", "42", "boty"), terms(legacyOnCzech, "boty42"));
			assertIterableEquals(List.of("bot"), terms(legacyOnCzech, "boty"));
		}

	}

	@Nested
	@DisplayName("Side-by-side report")
	class Report {

		@Test
		@DisplayName("Prints every chain's terms for the comparison inputs")
		void shouldReportSplitterComparison() {
			// an instrument, not a guard: the table is what `p5-word-number-split-comparison.md` quotes
			final List<FulltextAnalyzer> chains = List.of(
				production, legacyOnCzech, legacyOnGeneric, splitAppended, splitInChain
			);
			final StringBuilder report = new StringBuilder(2048);
			report.append("| input |");
			for (final FulltextAnalyzer analyzer : chains) {
				report.append(' ').append(analyzer.getAnalyzerName()).append(" |");
			}
			report.append('\n');
			for (final String input : List.of(
				"UHD7800", "123xyz", "abc789", "123abc345xyz678", "boty42", "iPhone15", "GTX1080Ti", "XC90",
				"3.5mm", "AB-123", "8594001234567", "boty", "iPhone 15"
			)) {
				report.append("| `").append(input).append("` |");
				for (final FulltextAnalyzer analyzer : chains) {
					report.append(' ').append(String.join(", ", terms(analyzer, input))).append(" |");
				}
				report.append('\n');
			}
			System.out.println(report);
			assertTrue(report.length() > 0);
		}

	}

}
