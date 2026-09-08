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

import io.evitadb.index.fulltext.analysis.CzechAnalysisFixture.MatchStrategy;
import io.evitadb.index.fulltext.analysis.CzechAnalysisFixture.Measurement;
import io.evitadb.index.fulltext.analysis.FoldedCzechStemmer.FoldedCzechStemFilter;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.WordlistLoader;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.core.StopFilter;
import org.apache.lucene.analysis.cz.CzechAnalyzer;
import org.apache.lucene.analysis.cz.CzechStemFilter;
import org.apache.lucene.analysis.hunspell.Dictionary;
import org.apache.lucene.analysis.hunspell.HunspellStemFilter;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.miscellaneous.KeywordRepeatFilter;
import org.apache.lucene.analysis.miscellaneous.RemoveDuplicatesTokenFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.AttributeSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measures **every mechanism** the prior-art survey proposed
 * (`documentation/adr/2026-08-24-fulltext-search-lucene-vs-inhouse/prototypes/p5-prior-art-accent-vs-stemming.md`,
 * §11) against one Czech vocabulary and four numbers, so that the choice between them is made on measurements
 * rather than on the plausibility of their descriptions.
 *
 * | id  | survey | what is built here                                                                        |
 * |-----|--------|-------------------------------------------------------------------------------------------|
 * | A0  | —      | the production chain: `CzechStemFilter` then folding. The baseline every row is read against |
 * | A1  | M3     | fold first, then `CzechStemFilter` unchanged — the Vespa/Typesense default                 |
 * | A2  | M1     | fold first, then {@link FoldedCzechStemmer} with both ambiguous rules **off**               |
 * | A3  | M1     | …with the palatalization rewrite on                                                        |
 * | A4  | M1     | …with the penultimate vowel shift on                                                       |
 * | A5  | M1     | …with both on, i.e. the fullest folded port of `CzechStemmer`                               |
 * | A6  | M2     | the production chain plus a second lane: `KeywordRepeatFilter` keeps the folded surface     |
 * | A7  | M5     | asymmetric — the production chain indexes, the two-lane chain queries                       |
 * | A8  | M4     | no stemmer at all, folding only                                                            |
 * | A9  | M6     | selective folding: the letters the Czech stemmer reads are exempted and never folded back   |
 * | A10 | —      | Hunspell `cs_CZ` then folding, carried over for comparison                                 |
 * | A11 | M1+M2  | the folded-space stemmer **and** a folded surface lane                                      |
 * | A12 | —      | folding then Hunspell `cs_CZ`, the dictionary approach's other ordering                     |
 * | A13 | M1     | A2 with the neuter `-at-` paradigm dropped — folding made those entries ambiguous           |
 * | A14 | M1     | A13 plus the penultimate vowel shift                                                        |
 * | A15 | M1+M2  | A13 plus a folded surface lane                                                              |
 * | A16 | M1     | A13 plus the palatalization rewrite                                                         |
 * | A17 | M1     | A13 plus both rewrites, i.e. the fullest folded port measured                               |
 * | A18 | M1     | both rewrites, `-at` kept, the folded stemmer applied **twice**                             |
 * | A19 | M1     | A18 without the vowel shift                                                                 |
 * | A20 | M7     | asymmetric — production indexes, the query runs **every** folded-stemmer hypothesis         |
 * | A21 | M7     | A20 without the vowel-shift hypothesis                                                      |
 * | A22 | M7     | A20 with only the `-at` fork, both rewrite hypotheses off                                   |
 *
 * A0, A2 and A8 are measured a second time under prefix-plus-typo matching, which is the retrieval model M4
 * depends on and the only way to see what a fuzzy lane actually rescues.
 *
 * **This class is an instrument, not a guard.** Its assertions pin only the findings that would change the
 * conclusion if they reversed; the numbers themselves belong in {@link #shouldReportApproachMatrix()}'s output,
 * which is the thing to read.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Czech analysis approaches — full comparison matrix")
@Tag(ENGINE)
@Tag(FULLTEXT)
class CzechAnalysisApproachMatrixTest {

	/**
	 * Classpath location of the Hunspell `cs_CZ` dictionary pair and the stop-word list accompanying it.
	 */
	private static final String HUNSPELL_RESOURCE_PATH = "/fulltext/hunspell/";

	/**
	 * The diacritics Lucene's `CzechStemmer` ending tables and rewrite rules actually read. Mechanism M6 folds
	 * everything **except** these, so that the stemmer keeps working — which is also why it cannot help the
	 * bare-typed query, whose problem is that it lacks exactly these letters.
	 */
	private static final String STEMMER_SIGNIFICANT_DIACRITICS = "áéíýěůčšž";

	/**
	 * Every chain built for the class, so that each is closed exactly once — a Lucene analyzer that is never
	 * closed retains its per-thread stream components for the lifetime of the JVM.
	 */
	private static final List<FulltextAnalyzer> BUILT_CHAINS = new ArrayList<>(16);

	/**
	 * The measurements, computed once. The false-merge metric alone compares roughly seventeen thousand form
	 * pairs per approach, so measuring per test method would multiply the run time by the number of tests.
	 */
	private static List<Measurement> measurements;

	@BeforeAll
	static void measureEveryApproach() throws IOException {
		final CharArraySet hunspellStopWords = loadStopWords();
		final Dictionary hunspellDictionary = loadHunspellDictionary();

		final FulltextAnalyzer production = chain(
			"A0 stem->fold (production)", new DiacriticsFoldingAnalyzerWrapper(new CzechAnalyzer())
		);
		final FulltextAnalyzer naiveFoldFirst = chain("A1 fold->stem (naive)", czechChain(true, false));
		final FulltextAnalyzer foldedConservative = chain(
			"A2 fold->foldedStem[--]", foldedStemChain(false, false, true, false)
		);
		final FulltextAnalyzer foldedPalatalization = chain(
			"A3 fold->foldedStem[palat]", foldedStemChain(true, false, true, false)
		);
		final FulltextAnalyzer foldedVowelShift = chain(
			"A4 fold->foldedStem[vowel]", foldedStemChain(false, true, true, false)
		);
		final FulltextAnalyzer foldedBothRules = chain(
			"A5 fold->foldedStem[palat+vowel]", foldedStemChain(true, true, true, false)
		);
		final FulltextAnalyzer twoLane = chain("A6 stem->fold + surface lane", czechChain(false, true));
		final FulltextAnalyzer foldOnly = chain("A8 fold only, no stemmer", foldOnlyChain());
		final FulltextAnalyzer selectiveFolding = chain("A9 selective folding (M6)", selectiveFoldingChain());
		final FulltextAnalyzer hunspell = chain(
			"A10 hunspell->fold", hunspellChain(hunspellDictionary, hunspellStopWords, false)
		);
		final FulltextAnalyzer hunspellFoldFirst = chain(
			"A12 fold->hunspell", hunspellChain(hunspellDictionary, hunspellStopWords, true)
		);
		final FulltextAnalyzer foldedPlusSurfaceLane = chain(
			"A11 foldedStem[--] + surface lane", foldedStemChain(false, false, true, true)
		);
		// A13/A14/A15 drop the neuter `-at-` paradigm, which folding made ambiguous - see
		// FoldedCzechStemmer#neuterAtParadigm
		final FulltextAnalyzer foldedNoAt = chain(
			"A13 fold->foldedStem[-at]", foldedStemChain(false, false, false, false)
		);
		final FulltextAnalyzer foldedNoAtVowel = chain(
			"A14 fold->foldedStem[vowel,-at]", foldedStemChain(false, true, false, false)
		);
		final FulltextAnalyzer foldedNoAtSurfaceLane = chain(
			"A15 foldedStem[-at] + surface lane", foldedStemChain(false, false, false, true)
		);
		// A16/A17 combine everything the isolated variants showed to be worth having: the palatalization
		// rewrite (which the fixture only started exercising once it gained `dětští`-type forms), the vowel
		// shift, and the neuter `-at-` paradigm dropped
		final FulltextAnalyzer foldedPalatNoAt = chain(
			"A16 fold->foldedStem[palat,-at]", foldedStemChain(true, false, false, false)
		);
		final FulltextAnalyzer foldedAllNoAt = chain(
			"A17 fold->foldedStem[palat,vowel,-at]", foldedStemChain(true, true, false, false)
		);
		// A18/A19 keep the `-at` entries and apply the whole stemmer TWICE, so that the oblique forms of the
		// `-át` masculines catch up with the truncated nominative (`kabatu` -> `kabat` -> `kab`) instead of
		// splitting from it - the recall side of both `-at` switch positions recovered at once. The price is
		// that every genuine `-at`-ending root is truncated too (`formát` -> `form` = `forma` -> `form`),
		// which is why the fixture carries the `forma`/`formát` confusable pair.
		final FulltextAnalyzer foldedDoubleAll = chain(
			"A18 fold->foldedStem[palat,vowel,+at]x2", doubleFoldedStemChain(true, true)
		);
		final FulltextAnalyzer foldedDoublePalat = chain(
			"A19 fold->foldedStem[palat,+at]x2", doubleFoldedStemChain(true, false)
		);
		// A20/A21/A22 are asymmetric - mechanism M7. The production chain indexes (the stored value is always
		// spelled correctly, so the accented stemmer never faces a folded ambiguity there), and the query side
		// folds first and then emits EVERY stem the folded stemmer could produce across the ambiguous-rule
		// switch positions, as OR'd terms. The ambiguity is absorbed by the query fan-out instead of being
		// committed into the index.
		final FulltextAnalyzer hypothesisQueryFull = chain(
			"A20 query hypotheses[palat,vowel,at]", hypothesisQueryChain(true, true)
		);
		final FulltextAnalyzer hypothesisQueryNoVowel = chain(
			"A21 query hypotheses[palat,at]", hypothesisQueryChain(true, false)
		);
		final FulltextAnalyzer hypothesisQueryAtOnly = chain(
			"A22 query hypotheses[at]", hypothesisQueryChain(false, false)
		);

		final List<Measurement> collected = new ArrayList<>(16);
		collected.add(measure(production, production, MatchStrategy.EXACT));
		collected.add(measure(naiveFoldFirst, naiveFoldFirst, MatchStrategy.EXACT));
		collected.add(measure(foldedConservative, foldedConservative, MatchStrategy.EXACT));
		collected.add(measure(foldedPalatalization, foldedPalatalization, MatchStrategy.EXACT));
		collected.add(measure(foldedVowelShift, foldedVowelShift, MatchStrategy.EXACT));
		collected.add(measure(foldedBothRules, foldedBothRules, MatchStrategy.EXACT));
		collected.add(measure(twoLane, twoLane, MatchStrategy.EXACT));
		// M5: the index holds one lane and only the query is expanded - the point is that this buys nothing
		collected.add(
			CzechAnalysisFixture.measure(
				"A7 asymmetric query expansion", production, twoLane, MatchStrategy.EXACT
			)
		);
		collected.add(measure(foldOnly, foldOnly, MatchStrategy.EXACT));
		collected.add(measure(selectiveFolding, selectiveFolding, MatchStrategy.EXACT));
		collected.add(measure(hunspell, hunspell, MatchStrategy.EXACT));
		collected.add(measure(hunspellFoldFirst, hunspellFoldFirst, MatchStrategy.EXACT));
		collected.add(measure(foldedPlusSurfaceLane, foldedPlusSurfaceLane, MatchStrategy.EXACT));
		collected.add(measure(foldedNoAt, foldedNoAt, MatchStrategy.EXACT));
		collected.add(measure(foldedNoAtVowel, foldedNoAtVowel, MatchStrategy.EXACT));
		collected.add(measure(foldedNoAtSurfaceLane, foldedNoAtSurfaceLane, MatchStrategy.EXACT));
		collected.add(measure(foldedPalatNoAt, foldedPalatNoAt, MatchStrategy.EXACT));
		collected.add(measure(foldedAllNoAt, foldedAllNoAt, MatchStrategy.EXACT));
		collected.add(measure(foldedDoubleAll, foldedDoubleAll, MatchStrategy.EXACT));
		collected.add(measure(foldedDoublePalat, foldedDoublePalat, MatchStrategy.EXACT));
		// M7: the production chain indexes, the multi-hypothesis folded stemmer queries
		collected.add(
			CzechAnalysisFixture.measure(
				"A20 A0-index/hypothesis query", production, hypothesisQueryFull, MatchStrategy.EXACT
			)
		);
		collected.add(
			CzechAnalysisFixture.measure(
				"A21 A0-index/hypo query[-vowel]", production, hypothesisQueryNoVowel, MatchStrategy.EXACT
			)
		);
		collected.add(
			CzechAnalysisFixture.measure(
				"A22 A0-index/hypo query[at only]", production, hypothesisQueryAtOnly, MatchStrategy.EXACT
			)
		);
		collected.add(measure(production, production, MatchStrategy.PREFIX_FUZZY));
		collected.add(measure(foldedConservative, foldedConservative, MatchStrategy.PREFIX_FUZZY));
		collected.add(measure(foldOnly, foldOnly, MatchStrategy.PREFIX_FUZZY));
		measurements = collected;
	}

	@AfterAll
	static void closeEveryChain() {
		for (final FulltextAnalyzer builtChain : BUILT_CHAINS) {
			builtChain.close();
		}
		BUILT_CHAINS.clear();
		measurements = null;
	}

	/**
	 * Measures a symmetric approach, taking the reported name from the chain itself.
	 *
	 * @param indexAnalyzer chain analysing stored values
	 * @param queryAnalyzer chain analysing query text
	 * @param strategy      how query terms are compared against value terms
	 * @return the measurement
	 */
	@Nonnull
	private static Measurement measure(
		@Nonnull FulltextAnalyzer indexAnalyzer,
		@Nonnull FulltextAnalyzer queryAnalyzer,
		@Nonnull MatchStrategy strategy
	) {
		return CzechAnalysisFixture.measure(
			indexAnalyzer.getAnalyzerName(), indexAnalyzer, queryAnalyzer, strategy
		);
	}

	/*
	 * ----------------------------------------------------------------------------------------------------
	 * chain construction
	 * ----------------------------------------------------------------------------------------------------
	 */

	/**
	 * Wraps a Lucene chain into the engine's analyzer holder — so that the measurement runs through exactly the
	 * code path production uses, including the NFC normalization on the boundary — and registers it for closing.
	 *
	 * @param name     name the approach is reported under
	 * @param analyzer Lucene chain
	 * @return the wrapped analyzer
	 */
	@Nonnull
	private static FulltextAnalyzer chain(@Nonnull String name, @Nonnull Analyzer analyzer) {
		final FulltextAnalyzer wrapped = new FulltextAnalyzer(name, AnalysisMode.ALL, analyzer);
		BUILT_CHAINS.add(wrapped);
		return wrapped;
	}

	/**
	 * Builds a chain around Lucene's unmodified `CzechStemFilter`.
	 *
	 * Folding always sits **after** the stop filter, never before it: `CzechAnalyzer`'s stop-word list is
	 * written with diacritics, so folding ahead of it would stop dropping stop words and silently change a
	 * third property while two are being measured.
	 *
	 * @param foldBeforeStemmer whether folding precedes the stemmer
	 * @param keepSurfaceLane   whether a `KeywordRepeatFilter` keeps the unstemmed surface form as a second term
	 * @return the Lucene chain
	 */
	@Nonnull
	private static Analyzer czechChain(boolean foldBeforeStemmer, boolean keepSurfaceLane) {
		return new Analyzer() {
			@Override
			protected TokenStreamComponents createComponents(String fieldName) {
				final Tokenizer source = new StandardTokenizer();
				TokenStream stream = new StopFilter(
					new LowerCaseFilter(source), CzechAnalyzer.getDefaultStopSet()
				);
				if (foldBeforeStemmer) {
					stream = new ASCIIFoldingFilter(stream);
				}
				if (keepSurfaceLane) {
					stream = new KeywordRepeatFilter(stream);
				}
				stream = new CzechStemFilter(stream);
				if (keepSurfaceLane) {
					stream = new RemoveDuplicatesTokenFilter(stream);
				}
				if (!foldBeforeStemmer) {
					stream = new ASCIIFoldingFilter(stream);
				}
				return new TokenStreamComponents(source, stream);
			}

			@Override
			protected TokenStream normalize(String fieldName, TokenStream in) {
				return new ASCIIFoldingFilter(new LowerCaseFilter(in));
			}
		};
	}

	/**
	 * Builds a chain that folds first and then applies the folded-space stemmer prototype — mechanism M1.
	 *
	 * @param palatalizationRewrite whether the `ct`→`ck` / `st`→`sk` rewrite is applied
	 * @param penultimateVowelShift whether the penultimate `u`→`o` rewrite is applied
	 * @param neuterAtParadigm      whether the neuter `-at-` paradigm entries are stripped
	 * @param keepSurfaceLane       whether a `KeywordRepeatFilter` keeps the folded surface form too
	 * @return the Lucene chain
	 */
	@Nonnull
	private static Analyzer foldedStemChain(
		boolean palatalizationRewrite,
		boolean penultimateVowelShift,
		boolean neuterAtParadigm,
		boolean keepSurfaceLane
	) {
		final FoldedCzechStemmer stemmer = new FoldedCzechStemmer(
			palatalizationRewrite, penultimateVowelShift, neuterAtParadigm
		);
		return new Analyzer() {
			@Override
			protected TokenStreamComponents createComponents(String fieldName) {
				final Tokenizer source = new StandardTokenizer();
				TokenStream stream = new StopFilter(
					new LowerCaseFilter(source), CzechAnalyzer.getDefaultStopSet()
				);
				stream = new ASCIIFoldingFilter(stream);
				if (keepSurfaceLane) {
					stream = new KeywordRepeatFilter(stream);
				}
				stream = new FoldedCzechStemFilter(stream, stemmer);
				if (keepSurfaceLane) {
					stream = new RemoveDuplicatesTokenFilter(stream);
				}
				return new TokenStreamComponents(source, stream);
			}

			@Override
			protected TokenStream normalize(String fieldName, TokenStream in) {
				return new ASCIIFoldingFilter(new LowerCaseFilter(in));
			}
		};
	}

	/**
	 * Builds a chain that folds first and applies the folded-space stemmer **twice**, with the neuter `-at-`
	 * paradigm entries kept. The second pass lets the oblique `-át` masculine forms catch up with their
	 * truncated nominative — `kabatu` stems to `kabat` in the first pass and to `kab` in the second, where a
	 * single pass leaves the paradigm split whichever way the `-at` switch is set. The trade moves to
	 * precision: every root genuinely ending in folded `-at` is truncated the same way (`formát → form`).
	 *
	 * @param palatalizationRewrite whether the `ct`→`ck` / `st`→`sk` rewrite is applied
	 * @param penultimateVowelShift whether the penultimate `u`→`o` rewrite is applied
	 * @return the Lucene chain
	 */
	@Nonnull
	private static Analyzer doubleFoldedStemChain(
		boolean palatalizationRewrite,
		boolean penultimateVowelShift
	) {
		final FoldedCzechStemmer stemmer = new FoldedCzechStemmer(
			palatalizationRewrite, penultimateVowelShift, true
		);
		return new Analyzer() {
			@Override
			protected TokenStreamComponents createComponents(String fieldName) {
				final Tokenizer source = new StandardTokenizer();
				TokenStream stream = new StopFilter(
					new LowerCaseFilter(source), CzechAnalyzer.getDefaultStopSet()
				);
				stream = new ASCIIFoldingFilter(stream);
				stream = new FoldedCzechStemFilter(stream, stemmer);
				stream = new FoldedCzechStemFilter(stream, stemmer);
				return new TokenStreamComponents(source, stream);
			}

			@Override
			protected TokenStream normalize(String fieldName, TokenStream in) {
				return new ASCIIFoldingFilter(new LowerCaseFilter(in));
			}
		};
	}

	/**
	 * Builds the query half of mechanism M7: fold first, then emit **every** stem the folded stemmer could
	 * produce across the switch positions of its ambiguous rules, as terms at one position. The index half is
	 * the unmodified production chain, so this chain never analyses a stored value — the folded ambiguities
	 * (`-at`/`-át`, `st`/`št`, `u`/`ů`) are absorbed by the query fan-out instead of being committed either way,
	 * and the index keeps the accented stemmer's unambiguous output.
	 *
	 * The rules that are *not* ambiguous run identically in every hypothesis, so the fan-out stays small: a
	 * token yields one term unless an ambiguous rule actually fires on it, and at most about four when several do.
	 *
	 * @param palatalizationHypothesis whether a `ct`→`ck` / `st`→`sk` hypothesis is emitted alongside the
	 *                                 unrewritten stem
	 * @param vowelShiftHypothesis     whether a penultimate `u`→`o` hypothesis is emitted alongside the
	 *                                 unshifted stem
	 * @return the Lucene chain
	 */
	@Nonnull
	private static Analyzer hypothesisQueryChain(
		boolean palatalizationHypothesis,
		boolean vowelShiftHypothesis
	) {
		// one stemmer per switch combination - the union of their outputs is exactly the set of stems a
		// branching stemmer would produce, because every fork point is controlled by one of the four flags.
		// The `-at` and epenthetic-e forks are not parameterized: both are needed for basic recall against an
		// accented-stemmer index (`kabát` and `dřevěný` respectively), so every M7 variant carries them.
		final List<FoldedCzechStemmer> stemmers = new ArrayList<>(16);
		final boolean[] palatalizationPositions = palatalizationHypothesis
			? new boolean[]{false, true} : new boolean[]{false};
		final boolean[] vowelShiftPositions = vowelShiftHypothesis
			? new boolean[]{false, true} : new boolean[]{false};
		for (final boolean neuterAtParadigm : new boolean[]{false, true}) {
			for (final boolean palatalizationRewrite : palatalizationPositions) {
				for (final boolean penultimateVowelShift : vowelShiftPositions) {
					for (final boolean epentheticERemoval : new boolean[]{false, true}) {
						stemmers.add(
							new FoldedCzechStemmer(
								palatalizationRewrite, penultimateVowelShift, neuterAtParadigm,
								epentheticERemoval
							)
						);
					}
				}
			}
		}
		return new Analyzer() {
			@Override
			protected TokenStreamComponents createComponents(String fieldName) {
				final Tokenizer source = new StandardTokenizer();
				TokenStream stream = new StopFilter(
					new LowerCaseFilter(source), CzechAnalyzer.getDefaultStopSet()
				);
				stream = new ASCIIFoldingFilter(stream);
				stream = new HypothesisStemFilter(stream, stemmers);
				return new TokenStreamComponents(source, stream);
			}

			@Override
			protected TokenStream normalize(String fieldName, TokenStream in) {
				return new ASCIIFoldingFilter(new LowerCaseFilter(in));
			}
		};
	}

	/**
	 * Builds a chain with no stemmer at all — mechanism M4's analysis half. Inflection is left entirely to the
	 * matching strategy.
	 *
	 * @return the Lucene chain
	 */
	@Nonnull
	private static Analyzer foldOnlyChain() {
		return new Analyzer() {
			@Override
			protected TokenStreamComponents createComponents(String fieldName) {
				final Tokenizer source = new StandardTokenizer();
				final TokenStream stream = new ASCIIFoldingFilter(
					new StopFilter(new LowerCaseFilter(source), CzechAnalyzer.getDefaultStopSet())
				);
				return new TokenStreamComponents(source, stream);
			}

			@Override
			protected TokenStream normalize(String fieldName, TokenStream in) {
				return new ASCIIFoldingFilter(new LowerCaseFilter(in));
			}
		};
	}

	/**
	 * Builds mechanism M6: a single folding pass that **exempts** the letters the stemmer's tables read, and no
	 * trailing full fold. This is the shape Elasticsearch ships as `icu_folding` plus `unicode_set_filter`, and
	 * measuring it faithfully means not folding the exempted letters back afterwards — doing so would make the
	 * chain identical to A0 and measure nothing.
	 *
	 * @return the Lucene chain
	 */
	@Nonnull
	private static Analyzer selectiveFoldingChain() {
		return new Analyzer() {
			@Override
			protected TokenStreamComponents createComponents(String fieldName) {
				final Tokenizer source = new StandardTokenizer();
				final TokenStream stream = new CzechStemFilter(
					new SelectiveFoldingFilter(
						new StopFilter(new LowerCaseFilter(source), CzechAnalyzer.getDefaultStopSet())
					)
				);
				return new TokenStreamComponents(source, stream);
			}

			@Override
			protected TokenStream normalize(String fieldName, TokenStream in) {
				return new SelectiveFoldingFilter(new LowerCaseFilter(in));
			}
		};
	}

	/**
	 * Builds a Hunspell chain over the `cs_CZ` dictionary with folding after the stemmer, carried over from the
	 * first measurement run so that the dictionary approach stays in the comparison.
	 *
	 * @param dictionary        loaded `cs_CZ` dictionary
	 * @param stopWords         stop-word list to apply before stemming
	 * @param foldBeforeStemmer whether folding precedes the dictionary lookup
	 * @return the Lucene chain
	 */
	@Nonnull
	private static Analyzer hunspellChain(
		@Nonnull Dictionary dictionary,
		@Nonnull CharArraySet stopWords,
		boolean foldBeforeStemmer
	) {
		return new Analyzer() {
			@Override
			protected TokenStreamComponents createComponents(String fieldName) {
				final Tokenizer source = new StandardTokenizer();
				TokenStream stream = new StopFilter(new LowerCaseFilter(source), stopWords);
				if (foldBeforeStemmer) {
					stream = new HunspellStemFilter(new ASCIIFoldingFilter(stream), dictionary);
				} else {
					stream = new ASCIIFoldingFilter(new HunspellStemFilter(stream, dictionary));
				}
				return new TokenStreamComponents(source, stream);
			}

			@Override
			protected TokenStream normalize(String fieldName, TokenStream in) {
				return new ASCIIFoldingFilter(new LowerCaseFilter(in));
			}
		};
	}

	/**
	 * Loads the `cs_CZ` Hunspell dictionary pair from the test resources.
	 *
	 * @return the loaded dictionary
	 * @throws IOException when a resource cannot be read or parsed
	 */
	@Nonnull
	private static Dictionary loadHunspellDictionary() throws IOException {
		try (
			final Directory tempDirectory = new ByteBuffersDirectory();
			final InputStream affixStream = resource("cs_CZ.aff");
			final InputStream dictionaryStream = resource("cs_CZ.dic")
		) {
			try {
				return new Dictionary(tempDirectory, "hunspell-cs", affixStream, dictionaryStream);
			} catch (ParseException e) {
				throw new IOException("Failed to parse the cs_CZ Hunspell dictionary.", e);
			}
		}
	}

	/**
	 * Loads the Czech stop-word list accompanying the Hunspell dictionary.
	 *
	 * @return stop words, lower-cased
	 * @throws IOException when the resource cannot be read
	 */
	@Nonnull
	private static CharArraySet loadStopWords() throws IOException {
		try (
			final InputStream stream = resource("stopwords.txt");
			final BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
		) {
			return WordlistLoader.getWordSet(reader);
		}
	}

	/**
	 * Opens a Hunspell test resource by its file name.
	 *
	 * @param fileName name of the file inside {@link #HUNSPELL_RESOURCE_PATH}
	 * @return open stream over the resource
	 */
	@Nonnull
	private static InputStream resource(@Nonnull String fileName) {
		final InputStream stream = CzechAnalysisApproachMatrixTest.class.getResourceAsStream(
			HUNSPELL_RESOURCE_PATH + fileName
		);
		if (stream == null) {
			throw new IllegalStateException(
				"Test resource `" + HUNSPELL_RESOURCE_PATH + fileName + "` is missing from the classpath."
			);
		}
		return stream;
	}

	/*
	 * ----------------------------------------------------------------------------------------------------
	 * tests
	 * ----------------------------------------------------------------------------------------------------
	 */

	@Test
	@DisplayName("Reports every approach's recall, convergence and false-merge count side by side")
	void shouldReportApproachMatrix() {
		final StringBuilder report = new StringBuilder(32768);
		report.append('\n');
		report.append("| approach                           | matching     | accent-typed | bare+crossform | ")
			.append("conv. pairs | conv. strict | false merges | terms/form |\n");
		report.append("|------------------------------------|--------------|--------------|----------------|")
			.append("-------------|--------------|--------------|------------|\n");
		for (final Measurement measurement : measurements) {
			report.append(measurement.matrixRow());
		}
		for (final Measurement measurement : measurements) {
			report.append('\n').append(measurement.detail(12));
		}
		System.out.println(report);

		for (final Measurement measurement : measurements) {
			assertTrue(
				measurement.accentedFormCount() > 0 && measurement.crossLemmaPairCount() > 0,
				"Approach `" + measurement.approachName() + "` measured nothing at all."
			);
		}
	}

	@Test
	@DisplayName("A two-lane chain leaves the bare-typed cross-form query broken, a folded stemmer does not")
	void shouldShowTwoLanesDoNotCoverTheCombinedCase() {
		// the finding this metric was added for: measurements of accent recall and of convergence can each be
		// satisfied by ONE lane of a two-lane chain acting alone, so a two-lane chain scores perfectly on both
		// while the query a Czech e-shop actually receives - typed bare AND in another inflection - still
		// misses. A lane that folds and then stems covers it; a union of two single-purpose lanes cannot.
		final Measurement twoLane = measurementOf("A6 stem->fold + surface lane", MatchStrategy.EXACT);
		final Measurement foldedStemmer = measurementOf(
			"A17 fold->foldedStem[palat,vowel,-at]", MatchStrategy.EXACT
		);

		assertFalse(
			twoLane.bareTypedCrossFormMisses().isEmpty(),
			"A two-lane chain is not supposed to cover the bare-typed cross-form query - if it does, the "
				+ "metric is wrong.\n" + twoLane.detail(40)
		);
		assertTrue(
			foldedStemmer.bareTypedCrossFormMisses().size() < twoLane.bareTypedCrossFormMisses().size(),
			"The folded-space stemmer must cover more of the bare-typed cross-form query than a two-lane "
				+ "chain does.\n" + foldedStemmer.detail(40)
		);
	}

	@Test
	@DisplayName("The folded-space stemmer closes the accent-typing gap without losing convergence")
	void shouldConfirmFoldedStemmerClosesTheGap() {
		// this is the finding the whole survey turns on: M1 is supposed to reach fold-before's accent recall
		// AND keep fold-after's convergence, which neither pure ordering can do
		final Measurement production = measurementOf("A0 stem->fold (production)", MatchStrategy.EXACT);
		final Measurement naiveFoldFirst = measurementOf("A1 fold->stem (naive)", MatchStrategy.EXACT);
		// A17 rather than A2: once the fixture gained palatalized plurals (`dětští`), the variant with both
		// rewrites off stopped being the representative M1 configuration - see the fixture's vocabulary javadoc
		final Measurement foldedStemmer = measurementOf(
			"A17 fold->foldedStem[palat,vowel,-at]", MatchStrategy.EXACT
		);

		assertTrue(
			foldedStemmer.accentTypingMisses().isEmpty(),
			"The folded-space stemmer is supposed to make accent-typed recall total, but missed:\n"
				+ foldedStemmer.detail(40)
		);
		// the +4 slack is exactly the `-ata` neuter pairs the `-at` drop surrenders (`rajče`/`rajčata`,
		// 4 ordered pairs) - the one recall loss no folded-space configuration avoids, since keeping the
		// entries would surrender `kabát`'s 8 pairs instead; see FoldedCzechStemmer#neuterAtParadigm
		assertTrue(
			foldedStemmer.convergenceMisses().size() <= production.convergenceMisses().size() + 4,
			"The fullest folded port converged worse than the production chain by more than the `-ata` "
				+ "neuter pairs the `-at` drop surrenders.\n" + foldedStemmer.detail(40)
		);
		assertTrue(
			foldedStemmer.convergenceMisses().size() < naiveFoldFirst.convergenceMisses().size(),
			"The folded-space stemmer must converge better than naive fold-before-stem, otherwise the port "
				+ "bought nothing.\n" + foldedStemmer.detail(40)
		);
	}

	@Test
	@DisplayName("The two ambiguous folded rules buy convergence by paying in false merges")
	void shouldShowAmbiguousRulesCostPrecision() {
		// the survey named these two rules as needing language judgment; this pins WHICH way the trade goes, so
		// that enabling either one later is a decision rather than an accident
		final Measurement conservative = measurementOf("A2 fold->foldedStem[--]", MatchStrategy.EXACT);
		final Measurement palatalization = measurementOf("A3 fold->foldedStem[palat]", MatchStrategy.EXACT);
		final Measurement vowelShift = measurementOf("A4 fold->foldedStem[vowel]", MatchStrategy.EXACT);

		assertTrue(
			palatalization.falseMerges().size() > conservative.falseMerges().size(),
			"The palatalization rewrite is expected to merge unrelated `-st` words; it did not.\n"
				+ palatalization.detail(40)
		);
		assertTrue(
			vowelShift.falseMerges().size() > conservative.falseMerges().size(),
			"The penultimate vowel shift is expected to merge unrelated `-u_` words; it did not.\n"
				+ vowelShift.detail(40)
		);
		// ...and each also buys recall, which is the half the first two runs could not see: the fixture then
		// contained no palatalized plural for the rewrite to converge and no second `stůl`-shaped paradigm
		assertTrue(
			palatalization.bareTypedCrossFormMisses().size() < conservative.bareTypedCrossFormMisses().size(),
			"The palatalization rewrite is expected to converge the `sk`/`št` alternation; it did not - which "
				+ "means the vocabulary contains no form that exercises it.\n" + palatalization.detail(40)
		);
		assertTrue(
			vowelShift.bareTypedCrossFormMisses().size() < conservative.bareTypedCrossFormMisses().size(),
			"The penultimate vowel shift is expected to converge `stůl`/`stolů`; it did not.\n"
				+ vowelShift.detail(40)
		);
	}

	@Test
	@DisplayName("Asymmetric query expansion alone buys nothing over the production chain")
	void shouldShowAsymmetricExpansionBuysNothing() {
		// M5's claim from the survey: expanding only the query, against a single-lane index, cannot help - the
		// extra query term has nothing to hit. Pinned because it is cheap to implement and therefore tempting.
		final Measurement production = measurementOf("A0 stem->fold (production)", MatchStrategy.EXACT);
		final Measurement asymmetric = measurementOf("A7 asymmetric query expansion", MatchStrategy.EXACT);

		// measurement refined the survey's "buys nothing" to "buys almost nothing": exactly one form is
		// rescued, `kabát`, and only by the accident that the query's folded surface equals the value's stem
		assertTrue(
			production.accentTypingMisses().size() - asymmetric.accentTypingMisses().size() <= 1,
			"Query-side expansion against a one-lane index is not supposed to rescue more than a coincidence.\n"
				+ asymmetric.detail(40)
		);
	}

	@Test
	@DisplayName("Hypothesis-expanded queries reach two-step recall at a fraction of its false merges")
	void shouldShowHypothesisQueriesDominateTwoStepStemming() {
		// mechanism M7's claim: all of A0's failures are query-side, so keeping the accented stemmer on the
		// index and absorbing the folded ambiguities in a query-term fan-out must reach two-step recall
		// (A18) while merging strictly less - the index never commits an ambiguity, so merges become
		// one-directional. If this reversed, the asymmetric mechanism would lose its reason to exist.
		final Measurement twoStep = measurementOf(
			"A18 fold->foldedStem[palat,vowel,+at]x2", MatchStrategy.EXACT
		);
		final Measurement hypothesis = measurementOf("A20 A0-index/hypothesis query", MatchStrategy.EXACT);

		assertTrue(
			hypothesis.accentTypingMisses().isEmpty(),
			"The hypothesis query chain is supposed to make accent-typed recall total, but missed:\n"
				+ hypothesis.detail(40)
		);
		assertTrue(
			hypothesis.bareTypedCrossFormMisses().isEmpty(),
			"The hypothesis query chain is supposed to cover every bare-typed cross-form pair, but missed:\n"
				+ hypothesis.detail(40)
		);
		assertTrue(
			hypothesis.falseMerges().size() < twoStep.falseMerges().size(),
			"Keeping the accented stemmer on the index is supposed to merge strictly less than committing "
				+ "the folded ambiguities into it; it did not.\n" + hypothesis.detail(40)
		);

		// the cost M7 pays instead of index precision is query fan-out - pin that it stays a handful of
		// terms per token rather than exploding combinatorially with the number of forked rules
		final FulltextAnalyzer fanOutProbe = chain(
			"A20 fan-out probe", hypothesisQueryChain(true, true)
		);
		int analyzedFormCount = 0;
		int emittedTermCount = 0;
		int maxTermsPerForm = 0;
		final List<CzechAnalysisFixture.Lemma> allLemmas = new ArrayList<>(
			CzechAnalysisFixture.VOCABULARY.size() + CzechAnalysisFixture.CONFUSABLE_LEMMAS.size()
		);
		allLemmas.addAll(CzechAnalysisFixture.VOCABULARY);
		allLemmas.addAll(CzechAnalysisFixture.CONFUSABLE_LEMMAS);
		for (final CzechAnalysisFixture.Lemma lemma : allLemmas) {
			for (final String form : lemma.forms()) {
				final Set<String> terms = CzechAnalysisFixture.analyzeWord(
					fanOutProbe, CzechAnalysisFixture.stripAccents(form)
				);
				analyzedFormCount++;
				emittedTermCount += terms.size();
				maxTermsPerForm = Math.max(maxTermsPerForm, terms.size());
			}
		}
		System.out.printf(
			"M7 query fan-out over %d bare-typed forms: %.2f terms/form on average, %d at most%n",
			analyzedFormCount, (double) emittedTermCount / analyzedFormCount, maxTermsPerForm
		);
		assertTrue(
			maxTermsPerForm <= 4,
			"The hypothesis fan-out is supposed to stay a handful of terms per token; it emitted "
				+ maxTermsPerForm + " for one form."
		);
	}

	@Test
	@DisplayName("Prefix and typo tolerance cannot substitute for a working stemmer")
	void shouldShowPrefixFuzzyIsNotASubstitute() {
		// M4's claim: length thresholds rather than grammar decide, so recall is erratic and precision pays
		final Measurement foldOnlyExact = measurementOf("A8 fold only, no stemmer", MatchStrategy.EXACT);
		final Measurement foldOnlyFuzzy = measurementOf("A8 fold only, no stemmer", MatchStrategy.PREFIX_FUZZY);

		assertTrue(
			foldOnlyFuzzy.convergenceMisses().size() < foldOnlyExact.convergenceMisses().size(),
			"Prefix and typo tolerance are supposed to recover at least some inflection.\n"
				+ foldOnlyFuzzy.detail(40)
		);
		assertFalse(
			foldOnlyFuzzy.convergenceMisses().isEmpty(),
			"Prefix and typo tolerance are not supposed to recover ALL inflection - if they did, the whole "
				+ "stemming question would be moot and this measurement would be wrong.\n"
				+ foldOnlyFuzzy.detail(40)
		);
		assertTrue(
			foldOnlyFuzzy.falseMerges().size() > foldOnlyExact.falseMerges().size(),
			"Prefix and typo tolerance are supposed to cost precision.\n" + foldOnlyFuzzy.detail(40)
		);
	}

	/**
	 * Finds a measurement by the approach name and the strategy it was measured under.
	 *
	 * @param approachName name of the approach
	 * @param strategy     strategy it was measured under
	 * @return the measurement
	 */
	@Nonnull
	private static Measurement measurementOf(@Nonnull String approachName, @Nonnull MatchStrategy strategy) {
		for (final Measurement measurement : measurements) {
			if (measurement.approachName().equals(approachName) && measurement.strategy() == strategy) {
				return measurement;
			}
		}
		throw new IllegalStateException(
			"No approach named `" + approachName + "` was measured under " + strategy + "."
		);
	}

	/**
	 * Emits every distinct stem a list of differently-configured {@link FoldedCzechStemmer}s produces for the
	 * current token, all at the same position — the query half of mechanism M7. The first hypothesis replaces
	 * the token, the rest are emitted as zero-position-increment followers, exactly the shape a synonym filter
	 * uses, so downstream consumers treat them as OR'd alternatives of one query word.
	 */
	private static final class HypothesisStemFilter extends TokenFilter {

		/**
		 * The stemmer configurations whose outputs are unioned per token.
		 */
		@Nonnull private final List<FoldedCzechStemmer> stemmers;
		/**
		 * Term text of the current token.
		 */
		@Nonnull private final CharTermAttribute termAttribute = addAttribute(CharTermAttribute.class);
		/**
		 * Position increment of the current token, set to zero for every hypothesis after the first.
		 */
		@Nonnull private final PositionIncrementAttribute positionIncrementAttribute =
			addAttribute(PositionIncrementAttribute.class);
		/**
		 * Hypotheses of the current token still waiting to be emitted.
		 */
		@Nonnull private final ArrayDeque<String> pendingHypotheses = new ArrayDeque<>(4);
		/**
		 * Attribute state of the token the pending hypotheses belong to, restored for each of them so that
		 * offsets and flags stay those of the original token.
		 */
		private AttributeSource.State currentTokenState;

		/**
		 * Creates the filter.
		 *
		 * @param input    stream to filter, already lowercased and diacritics-folded
		 * @param stemmers stemmer configurations to union
		 */
		private HypothesisStemFilter(@Nonnull TokenStream input, @Nonnull List<FoldedCzechStemmer> stemmers) {
			super(input);
			this.stemmers = stemmers;
		}

		@Override
		public boolean incrementToken() throws IOException {
			if (!this.pendingHypotheses.isEmpty()) {
				restoreState(this.currentTokenState);
				this.termAttribute.setEmpty().append(this.pendingHypotheses.poll());
				this.positionIncrementAttribute.setPositionIncrement(0);
				return true;
			}
			if (!this.input.incrementToken()) {
				return false;
			}
			final int length = this.termAttribute.length();
			final char[] scratch = new char[length];
			final Set<String> hypotheses = new LinkedHashSet<>(4);
			for (final FoldedCzechStemmer stemmer : this.stemmers) {
				System.arraycopy(this.termAttribute.buffer(), 0, scratch, 0, length);
				final int stemmedLength = stemmer.stem(scratch, length);
				hypotheses.add(new String(scratch, 0, stemmedLength));
			}
			final Iterator<String> hypothesisIterator = hypotheses.iterator();
			this.termAttribute.setEmpty().append(hypothesisIterator.next());
			while (hypothesisIterator.hasNext()) {
				this.pendingHypotheses.add(hypothesisIterator.next());
			}
			if (!this.pendingHypotheses.isEmpty()) {
				this.currentTokenState = captureState();
			}
			return true;
		}

		@Override
		public void reset() throws IOException {
			super.reset();
			this.pendingHypotheses.clear();
			this.currentTokenState = null;
		}

	}

	/**
	 * Folds only the diacritics Lucene's `CzechStemmer` does **not** read, leaving
	 * {@link #STEMMER_SIGNIFICANT_DIACRITICS} intact — mechanism M6's exemption alphabet, done with a plain
	 * character map because Lucene's `ICUFoldingFilter` and its `unicode_set_filter` live in the ICU module,
	 * which evitaDB does not depend on.
	 */
	private static final class SelectiveFoldingFilter extends TokenFilter {

		/**
		 * Term text of the current token.
		 */
		@Nonnull private final CharTermAttribute termAttribute = addAttribute(CharTermAttribute.class);

		/**
		 * Creates the filter.
		 *
		 * @param input stream to filter
		 */
		private SelectiveFoldingFilter(@Nonnull TokenStream input) {
			super(input);
		}

		@Override
		public boolean incrementToken() throws IOException {
			if (!this.input.incrementToken()) {
				return false;
			}
			final char[] buffer = this.termAttribute.buffer();
			final int length = this.termAttribute.length();
			for (int i = 0; i < length; i++) {
				final char character = buffer[i];
				if (STEMMER_SIGNIFICANT_DIACRITICS.indexOf(character) < 0) {
					buffer[i] = fold(character);
				}
			}
			return true;
		}

		/**
		 * Maps one Czech accented character to its ASCII base, leaving anything else alone.
		 *
		 * @param character character to fold
		 * @return the folded character, or the input when it carries no diacritic
		 */
		private static char fold(char character) {
			return switch (character) {
				case 'á', 'ä' -> 'a';
				case 'é', 'ě' -> 'e';
				case 'í' -> 'i';
				case 'ó', 'ö' -> 'o';
				case 'ú', 'ů', 'ü' -> 'u';
				case 'ý' -> 'y';
				case 'č' -> 'c';
				case 'ď' -> 'd';
				case 'ň' -> 'n';
				case 'ř' -> 'r';
				case 'š' -> 's';
				case 'ť' -> 't';
				case 'ž' -> 'z';
				default -> character;
			};
		}

	}

}
