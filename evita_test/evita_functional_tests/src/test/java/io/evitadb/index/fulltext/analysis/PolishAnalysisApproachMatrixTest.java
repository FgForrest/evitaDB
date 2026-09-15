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
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.core.StopFilter;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.morfologik.MorfologikFilter;
import org.apache.lucene.analysis.pl.PolishAnalyzer;
import org.apache.lucene.analysis.snowball.SnowballFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measures the Polish analysis mechanisms proposed by the SK/PL/RO survey
 * (`documentation/adr/2026-08-24-fulltext-search-lucene-vs-inhouse/prototypes/p5-prior-art-sk-pl-ro.md`, §5)
 * against one Polish vocabulary and the five metrics of {@link AnalysisApproachMeasurer}. Beyond the mechanism
 * comparison the Czech and Romanian matrices ran, this one also arbitrates the **index-side stemmer**: Stempel
 * (what evitaDB ships today) against the new official Snowball Polish stemmer — because mechanism M7 is only
 * constructible over a rule-based stemmer, and choosing it for Polish implies the Snowball switch.
 *
 * | id  | survey | what is built here                                                                          |
 * |-----|--------|---------------------------------------------------------------------------------------------|
 * | P0  | step 0 | Stempel `PolishAnalyzer` + folding after — today's chain plus the missing fold lane           |
 * | P0b | —      | bare Stempel `PolishAnalyzer` — **today's production**, no fold lane at all                   |
 * | P0s | —      | Snowball Polish + folding after — the M7-compatible index-side candidate                      |
 * | P0m | —      | Morfologik dictionary lemmatizer + folding after — the dictionary option (Czech-Hunspell analogue) |
 * | P1  | M3     | fold first, then the unmodified Snowball stemmer — the Vespa/Typesense default                |
 * | P2  | M1     | fold first, then {@link FoldedPolishStemmer} with all four ending groups **off**              |
 * | P3  | M1     | …with the `ł` past-tense endings on                                                          |
 * | P4  | M1     | …with the `ą`/`ę` endings on                                                                 |
 * | P5  | M1     | …with the `ś`/`ć` endings on                                                                 |
 * | P6  | M1     | …with the `ów` genitive on                                                                   |
 * | P7  | M1     | …with all four on, i.e. the fullest folded port                                              |
 * | P8  | M4     | no stemmer at all, folding only                                                              |
 * | P20 | M7     | asymmetric — P0s indexes, the query runs every folded-stemmer hypothesis (all four forks)     |
 * | P20m | M7    | the same hypothesis query over the **Morfologik** index — measures, rather than argues, the   |
 * |     |        | survey's claim that M7 is not constructible over a dictionary                                 |
 * | P21 | M7     | P20 without the `ów` fork                                                                    |
 * | P22 | M7     | P20 with only the `ł` fork                                                                   |
 *
 * P0, P7 and P8 are measured a second time under prefix-plus-typo matching, as in the other matrices.
 *
 * The Morfologik chain follows the Solr `analysis-extras` recipe the survey quotes: **lowercasing runs after
 * the lemmatizer**, because the Polish dictionary contains proper names whose case resolves ambiguities; the
 * stop filter and folding follow it. The Czech Hunspell rows (A10 at 74/348, A12 at 20/348 combined recall)
 * are the prior this row tests.
 *
 * **This class is an instrument, not a guard.** Its assertions pin only the findings that would change the
 * conclusion if they reversed; the numbers themselves belong in {@link #shouldReportApproachMatrix()}'s output,
 * which is the thing to read.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Polish analysis approaches — full comparison matrix")
@Tag(ENGINE)
@Tag(FULLTEXT)
class PolishAnalysisApproachMatrixTest {

	/**
	 * Every chain built for the class, so that each is closed exactly once — a Lucene analyzer that is never
	 * closed retains its per-thread stream components for the lifetime of the JVM.
	 */
	private static final List<FulltextAnalyzer> BUILT_CHAINS = new ArrayList<>(16);

	/**
	 * The measurements, computed once — the false-merge metric compares thousands of form pairs per approach.
	 */
	private static List<Measurement> measurements;

	@BeforeAll
	static void measureEveryApproach() {
		final FulltextAnalyzer stempelFolded = chain(
			"P0 stempel->fold (survey step 0)", new DiacriticsFoldingAnalyzerWrapper(new PolishAnalyzer())
		);
		final FulltextAnalyzer stempelBare = chain("P0b bare stempel (production)", new PolishAnalyzer());
		final FulltextAnalyzer snowballFolded = chain("P0s snowball->fold", snowballThenFoldChain());
		final FulltextAnalyzer morfologikFolded = chain("P0m morfologik->fold", morfologikThenFoldChain());
		final FulltextAnalyzer naiveFoldFirst = chain("P1 fold->snowball (naive)", foldThenSnowballChain());
		final FulltextAnalyzer foldedConservative = chain(
			"P2 fold->foldedStem[----]", foldedStemChain(new FoldedPolishStemmer(false, false, false, false))
		);
		final FulltextAnalyzer foldedL = chain(
			"P3 fold->foldedStem[l]", foldedStemChain(new FoldedPolishStemmer(true, false, false, false))
		);
		final FulltextAnalyzer foldedNasal = chain(
			"P4 fold->foldedStem[nasal]", foldedStemChain(new FoldedPolishStemmer(false, true, false, false))
		);
		final FulltextAnalyzer foldedSoft = chain(
			"P5 fold->foldedStem[soft]", foldedStemChain(new FoldedPolishStemmer(false, false, true, false))
		);
		final FulltextAnalyzer foldedOw = chain(
			"P6 fold->foldedStem[ow]", foldedStemChain(new FoldedPolishStemmer(false, false, false, true))
		);
		final FulltextAnalyzer foldedAll = chain(
			"P7 fold->foldedStem[l,nasal,soft,ow]",
			foldedStemChain(new FoldedPolishStemmer(true, true, true, true))
		);
		final FulltextAnalyzer foldOnly = chain("P8 fold only, no stemmer", foldOnlyChain());
		// M7: the Snowball chain indexes (Stempel has no rule table to fork over - see the survey §5), the
		// query side folds and emits every hypothesis the forked ending groups could produce
		final FulltextAnalyzer hypothesisFull = chain(
			"P20 query hypotheses[all]", fullHypothesisQueryChain()
		);
		final FulltextAnalyzer hypothesisNoOw = chain(
			"P21 query hypotheses[l,nasal,soft]", hypothesisQueryChain(true, true, true, false)
		);
		final FulltextAnalyzer hypothesisLOnly = chain(
			"P22 query hypotheses[l]", hypothesisQueryChain(true, false, false, false)
		);

		final List<Measurement> collected = new ArrayList<>(20);
		collected.add(measure(stempelFolded, stempelFolded, MatchStrategy.EXACT));
		collected.add(measure(stempelBare, stempelBare, MatchStrategy.EXACT));
		collected.add(measure(snowballFolded, snowballFolded, MatchStrategy.EXACT));
		collected.add(measure(morfologikFolded, morfologikFolded, MatchStrategy.EXACT));
		collected.add(measure(naiveFoldFirst, naiveFoldFirst, MatchStrategy.EXACT));
		collected.add(measure(foldedConservative, foldedConservative, MatchStrategy.EXACT));
		collected.add(measure(foldedL, foldedL, MatchStrategy.EXACT));
		collected.add(measure(foldedNasal, foldedNasal, MatchStrategy.EXACT));
		collected.add(measure(foldedSoft, foldedSoft, MatchStrategy.EXACT));
		collected.add(measure(foldedOw, foldedOw, MatchStrategy.EXACT));
		collected.add(measure(foldedAll, foldedAll, MatchStrategy.EXACT));
		collected.add(measure(foldOnly, foldOnly, MatchStrategy.EXACT));
		collected.add(
			PolishAnalysisFixture.measure(
				"P20 P0s-index/hypothesis query", snowballFolded, hypothesisFull, MatchStrategy.EXACT
			)
		);
		collected.add(
			PolishAnalysisFixture.measure(
				"P20m P0m-index/hypothesis query", morfologikFolded, hypothesisFull, MatchStrategy.EXACT
			)
		);
		collected.add(
			PolishAnalysisFixture.measure(
				"P21 P0s-index/hypo[-ow]", snowballFolded, hypothesisNoOw, MatchStrategy.EXACT
			)
		);
		collected.add(
			PolishAnalysisFixture.measure(
				"P22 P0s-index/hypo[l only]", snowballFolded, hypothesisLOnly, MatchStrategy.EXACT
			)
		);
		collected.add(measure(stempelFolded, stempelFolded, MatchStrategy.PREFIX_FUZZY));
		collected.add(measure(foldedAll, foldedAll, MatchStrategy.PREFIX_FUZZY));
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
		return PolishAnalysisFixture.measure(
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
	 * Builds the P0s chain: the official Snowball Polish stemmer with folding appended after it — the shape a
	 * `PolishSnowballAnalyzer`-based production chain would take, and the only M7-compatible index side.
	 *
	 * @return the Lucene chain
	 */
	@Nonnull
	private static Analyzer snowballThenFoldChain() {
		return new Analyzer() {
			@Override
			protected TokenStreamComponents createComponents(String fieldName) {
				final Tokenizer source = new StandardTokenizer();
				TokenStream stream = new StopFilter(
					new LowerCaseFilter(source), PolishAnalyzer.getDefaultStopSet()
				);
				stream = new SnowballFilter(stream, new PolishSnowballStemmer());
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
	 * Builds the P0m chain: the Morfologik dictionary lemmatizer with folding appended after it. The filter
	 * order follows the Solr `analysis-extras` recipe (see the class javadoc): the lemmatizer sees the
	 * original-case token, lowercasing, the stop filter and folding come after. Morfologik emits **every**
	 * candidate lemma at position increment zero, so this chain's terms-per-form exceeds one by design.
	 *
	 * @return the Lucene chain
	 */
	@Nonnull
	private static Analyzer morfologikThenFoldChain() {
		return new Analyzer() {
			@Override
			protected TokenStreamComponents createComponents(String fieldName) {
				final Tokenizer source = new StandardTokenizer();
				TokenStream stream = new MorfologikFilter(source);
				stream = new LowerCaseFilter(stream);
				stream = new StopFilter(stream, PolishAnalyzer.getDefaultStopSet());
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
	 * Builds the naive fold-before-stem chain — mechanism M3, the shape Vespa and Typesense ship: the
	 * unmodified Snowball stemmer is fed folded input its native-orthography tables were not written for.
	 *
	 * Folding sits **after** the stop filter for the same reason as in the other matrices: the stop list is
	 * spelled with diacritics, and folding ahead of it would change a third property while two are measured.
	 *
	 * @return the Lucene chain
	 */
	@Nonnull
	private static Analyzer foldThenSnowballChain() {
		return new Analyzer() {
			@Override
			protected TokenStreamComponents createComponents(String fieldName) {
				final Tokenizer source = new StandardTokenizer();
				TokenStream stream = new StopFilter(
					new LowerCaseFilter(source), PolishAnalyzer.getDefaultStopSet()
				);
				stream = new ASCIIFoldingFilter(stream);
				stream = new SnowballFilter(stream, new PolishSnowballStemmer());
				return new TokenStreamComponents(source, stream);
			}

			@Override
			protected TokenStream normalize(String fieldName, TokenStream in) {
				return new ASCIIFoldingFilter(new LowerCaseFilter(in));
			}
		};
	}

	/**
	 * Builds a chain that folds first and then applies the folded-space stemmer port — mechanism M1.
	 *
	 * @param stemmer the folded stemmer configuration to apply
	 * @return the Lucene chain
	 */
	@Nonnull
	private static Analyzer foldedStemChain(@Nonnull FoldedPolishStemmer stemmer) {
		return new Analyzer() {
			@Override
			protected TokenStreamComponents createComponents(String fieldName) {
				final Tokenizer source = new StandardTokenizer();
				TokenStream stream = new StopFilter(
					new LowerCaseFilter(source), PolishAnalyzer.getDefaultStopSet()
				);
				stream = new ASCIIFoldingFilter(stream);
				stream = new FoldedStemFilter(stream, stemmer);
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
					new StopFilter(new LowerCaseFilter(source), PolishAnalyzer.getDefaultStopSet())
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
	 * Builds the canonical M7 query chain over {@link FoldedPolishStemmer#allHypotheses()} — every switch
	 * combination plus the surface hypothesis, the exact set the pl_PL lexicon sweep
	 * ({@link PolishFoldedStemmerLexiconTest}) verifies. The P20 row uses this; P21/P22 keep the fork-subset
	 * construction below to show what dropping a fork costs.
	 *
	 * @return the Lucene chain
	 */
	@Nonnull
	private static Analyzer fullHypothesisQueryChain() {
		final List<FoldedStemmer> stemmers = FoldedPolishStemmer.allHypotheses();
		return new Analyzer() {
			@Override
			protected TokenStreamComponents createComponents(String fieldName) {
				final Tokenizer source = new StandardTokenizer();
				TokenStream stream = new StopFilter(
					new LowerCaseFilter(source), PolishAnalyzer.getDefaultStopSet()
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
	 * Builds the query half of mechanism M7: fold first, then emit **every** stem the folded stemmer could
	 * produce across the switch positions of its forked ending groups, as terms at one position. The index half
	 * is the P0s chain, so this chain never analyses a stored value.
	 *
	 * @param forkL     whether the `ł` past-tense group is forked (both positions emitted)
	 * @param forkNasal whether the `ą`/`ę` group is forked
	 * @param forkSoft  whether the `ś`/`ć` group is forked
	 * @param forkOw    whether the `ów` genitive is forked
	 * @return the Lucene chain
	 */
	@Nonnull
	private static Analyzer hypothesisQueryChain(
		boolean forkL,
		boolean forkNasal,
		boolean forkSoft,
		boolean forkOw
	) {
		// one stemmer per forked switch combination - the union of their outputs is exactly the set of stems a
		// branching stemmer would produce. Unforked switches stay off, i.e. at the conservative position.
		final List<FoldedPolishStemmer> stemmers = new ArrayList<>(16);
		final boolean[] lPositions = forkL ? new boolean[]{false, true} : new boolean[]{false};
		final boolean[] nasalPositions = forkNasal ? new boolean[]{false, true} : new boolean[]{false};
		final boolean[] softPositions = forkSoft ? new boolean[]{false, true} : new boolean[]{false};
		final boolean[] owPositions = forkOw ? new boolean[]{false, true} : new boolean[]{false};
		for (final boolean lEndings : lPositions) {
			for (final boolean nasalEndings : nasalPositions) {
				for (final boolean softEndings : softPositions) {
					for (final boolean owEnding : owPositions) {
						stemmers.add(new FoldedPolishStemmer(lEndings, nasalEndings, softEndings, owEnding));
					}
				}
			}
		}
		return new Analyzer() {
			@Override
			protected TokenStreamComponents createComponents(String fieldName) {
				final Tokenizer source = new StandardTokenizer();
				TokenStream stream = new StopFilter(
					new LowerCaseFilter(source), PolishAnalyzer.getDefaultStopSet()
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
	@DisplayName("Today's production chain cannot serve a single bare-typed query")
	void shouldShowTodaysProductionHasNoFoldLane() {
		// the survey's finding on the record: the bare Stempel chain has no fold lane, so a bare-typed query
		// misses even the same form - behind where Czech ever was
		final Measurement bare = measurementOf("P0b bare stempel (production)", MatchStrategy.EXACT);

		assertTrue(
			bare.accentTypingMisses().size() == bare.accentedFormCount(),
			"Bare Stempel is not supposed to serve any accent-typed query - if it does, the survey's `pl` row "
				+ "is wrong.\n" + bare.detail(40)
		);
	}

	@Test
	@DisplayName("A fold lane rescues Stempel only partially - the trie mangles bare-typed queries")
	void shouldShowStempelManglesBareTypedQueries() {
		// the measurement the survey could not predict: adding the folding wrapper to Stempel (step 0) buys far
		// less than it bought Czech or Romanian, because the statistical trie still stems the bare-typed query
		// text and produces garbage patches (`zolty` -> `zsc`) the folded index lane cannot meet - the
		// confidently-wrong failure mode of the Czech Hunspell rows, on the query side
		final Measurement stempelFolded = measurementOf("P0 stempel->fold (survey step 0)", MatchStrategy.EXACT);
		final Measurement bare = measurementOf("P0b bare stempel (production)", MatchStrategy.EXACT);
		final Measurement snowballFolded = measurementOf("P0s snowball->fold", MatchStrategy.EXACT);

		assertTrue(
			stempelFolded.accentTypingMisses().size() < bare.accentTypingMisses().size(),
			"The folding wrapper is supposed to rescue at least some accent-typed queries over bare Stempel.\n"
				+ stempelFolded.detail(40)
		);
		assertTrue(
			snowballFolded.accentTypingMisses().size() < stempelFolded.accentTypingMisses().size()
				&& snowballFolded.bareTypedCrossFormMisses().size()
				< stempelFolded.bareTypedCrossFormMisses().size()
				&& snowballFolded.convergenceMisses().size() < stempelFolded.convergenceMisses().size(),
			"The Snowball chain is supposed to dominate the Stempel chain on every recall metric - the "
				+ "index-side arbitration the survey asked for.\n" + snowballFolded.detail(40)
		);
	}

	@Test
	@DisplayName("The folded port with every ambiguous group off equals the real stemmer on folded input")
	void shouldShowFoldedPortFidelity() {
		// P1 feeds the real Snowball stemmer folded input, on which its diacritic-spelled entries simply never
		// fire; P2 is this port with every fold-ambiguous group disabled. Equality is the fidelity check of the
		// port - if they diverge, the port has a bug, not a finding
		final Measurement real = measurementOf("P1 fold->snowball (naive)", MatchStrategy.EXACT);
		final Measurement port = measurementOf("P2 fold->foldedStem[----]", MatchStrategy.EXACT);

		assertTrue(
			real.accentTypingMisses().size() == port.accentTypingMisses().size()
				&& real.bareTypedCrossFormMisses().size() == port.bareTypedCrossFormMisses().size()
				&& real.convergenceMisses().size() == port.convergenceMisses().size()
				&& real.falseMerges().size() == port.falseMerges().size(),
			"The folded port with all groups off must behave exactly like the real Snowball stemmer fed "
				+ "folded input.\n" + port.detail(40)
		);
	}

	@Test
	@DisplayName("Hypothesis-expanded queries close the accent gap; the ów fork is indispensable")
	void shouldShowHypothesisQueriesCloseTheGap() {
		final Measurement snowballFolded = measurementOf("P0s snowball->fold", MatchStrategy.EXACT);
		final Measurement hypothesis = measurementOf("P20 P0s-index/hypothesis query", MatchStrategy.EXACT);
		final Measurement withoutOw = measurementOf("P21 P0s-index/hypo[-ow]", MatchStrategy.EXACT);

		assertTrue(
			hypothesis.accentTypingMisses().isEmpty(),
			"The hypothesis query chain is supposed to make accent-typed recall total, but missed:\n"
				+ hypothesis.detail(40)
		);
		assertTrue(
			hypothesis.bareTypedCrossFormMisses().size() < snowballFolded.bareTypedCrossFormMisses().size(),
			"The hypothesis query chain is supposed to cover more bare-typed cross-form pairs than P0s.\n"
				+ hypothesis.detail(40)
		);
		// the fan-out costs a handful of extra merges over P0s, all of them inside the deliberately planted
		// stroke-collision confusables - pin that it stays in that family rather than growing new ones
		assertTrue(
			hypothesis.falseMerges().size() <= snowballFolded.falseMerges().size() + 4,
			"The hypothesis fan-out is supposed to cost at most the planted stroke-collision merges.\n"
				+ hypothesis.detail(40)
		);
		assertFalse(
			withoutOw.accentTypingMisses().isEmpty(),
			"Dropping the ów fork is supposed to lose the genitive plurals; it did not - which means the "
				+ "vocabulary no longer exercises that fork.\n" + withoutOw.detail(40)
		);
	}

	@Test
	@DisplayName("The hypothesis fan-out stays a handful of terms per token")
	void shouldKeepHypothesisFanOutSmall() {
		// four forked groups = 16 stemmer configurations, but distinct stems per token stay few
		final FulltextAnalyzer fanOutProbe = chain(
			"P20 fan-out probe", fullHypothesisQueryChain()
		);
		int analyzedFormCount = 0;
		int emittedTermCount = 0;
		int maxTermsPerForm = 0;
		final List<AnalysisApproachMeasurer.Lemma> allLemmas = new ArrayList<>(
			PolishAnalysisFixture.VOCABULARY.size() + PolishAnalysisFixture.CONFUSABLE_LEMMAS.size()
		);
		allLemmas.addAll(PolishAnalysisFixture.VOCABULARY);
		allLemmas.addAll(PolishAnalysisFixture.CONFUSABLE_LEMMAS);
		for (final AnalysisApproachMeasurer.Lemma lemma : allLemmas) {
			for (final String form : lemma.forms()) {
				final Set<String> terms = AnalysisApproachMeasurer.analyzeWord(
					fanOutProbe, PolishAnalysisFixture.bareType(form)
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

}
