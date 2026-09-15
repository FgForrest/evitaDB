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
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.core.StopFilter;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.ro.RomanianAnalyzer;
import org.apache.lucene.analysis.snowball.SnowballFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.tartarus.snowball.ext.RomanianStemmer;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measures the Romanian analysis mechanisms proposed by the SK/PL/RO survey
 * (`documentation/adr/2026-08-24-fulltext-search-lucene-vs-inhouse/prototypes/p5-prior-art-sk-pl-ro.md`, §4)
 * against one Romanian vocabulary and the five metrics of {@link AnalysisApproachMeasurer}, mirroring
 * {@link CzechAnalysisApproachMatrixTest} row for row where the mechanisms correspond.
 *
 * | id  | survey | what is built here                                                                          |
 * |-----|--------|---------------------------------------------------------------------------------------------|
 * | R0  | step 0 | `RomanianAnalyzer` + folding after — the survey's proposed production wiring                  |
 * | R0n | —      | comma-below→cedilla normalization, then stem, then fold — the Lucene 10 analyzer shape backported |
 * | R0b | —      | bare `RomanianAnalyzer` — the EdeeCMS shape, and the closest thing to a Lucene default        |
 * | R1  | M3     | fold first, then the unmodified Snowball `RomanianStemmer` — the Vespa/Typesense default      |
 * | R2  | M1     | fold first, then {@link FoldedRomanianStemmer} with all three ambiguous rules **off**         |
 * | R3  | M1     | …with the `ţiune`→`t` rewrite on                                                             |
 * | R4  | M1     | …with the `ş`-spelled verb endings on                                                        |
 * | R5  | M1     | …with the unconditional folded `am` on                                                       |
 * | R6  | M1     | …with all three on, i.e. the fullest folded port                                             |
 * | R8  | M4     | no stemmer at all, folding only                                                              |
 * | R20 | M7     | asymmetric — R0 indexes, the query runs **every** folded-stemmer hypothesis (all four forks)  |
 * | R20n | M7    | R20 over the **normalized** index R0n — the M7 row the encoding break disqualifies R20 from   |
 * | R21n | M7    | R20n without the `tiune` and `am` forks                                                      |
 * | R22n | M7    | R20n with only the `ă`-verb-endings fork                                                     |
 *
 * R0, R2 and R8 are measured a second time under prefix-plus-typo matching, as in the Czech matrix.
 *
 * Note the pinned Lucene is 9.12.3: its `RomanianAnalyzer` has **no** normalization filter (that is Lucene 10)
 * and its Snowball tables and stop list are written in the **cedilla** orthography only — so the stemmer
 * effectively does not stem correctly-spelled (comma-below) Romanian at all. The first run showed this breaks
 * M7 outright — the folded query hypotheses cannot cover an index whose stemmer did not fire — which is why the
 * R0n/R20n rows exist: they backport Lucene 10's comma→cedilla normalization in front of the stemmer.
 *
 * **This class is an instrument, not a guard.** Its assertions pin only the findings that would change the
 * conclusion if they reversed; the numbers themselves belong in {@link #shouldReportApproachMatrix()}'s output,
 * which is the thing to read.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Romanian analysis approaches — full comparison matrix")
@Tag(ENGINE)
@Tag(FULLTEXT)
class RomanianAnalysisApproachMatrixTest {

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
		final FulltextAnalyzer production = chain(
			"R0 stem->fold (survey step 0)", new DiacriticsFoldingAnalyzerWrapper(new RomanianAnalyzer())
		);
		final FulltextAnalyzer normalizedProduction = chain(
			"R0n norm->stem->fold", normalizedProductionChain()
		);
		final FulltextAnalyzer bare = chain("R0b bare RomanianAnalyzer", new RomanianAnalyzer());
		final FulltextAnalyzer naiveFoldFirst = chain("R1 fold->stem (naive)", foldThenSnowballChain());
		final FulltextAnalyzer foldedConservative = chain(
			"R2 fold->foldedStem[---]", foldedStemChain(new FoldedRomanianStemmer(false, false, false))
		);
		final FulltextAnalyzer foldedTiune = chain(
			"R3 fold->foldedStem[tiune]", foldedStemChain(new FoldedRomanianStemmer(true, false, false))
		);
		final FulltextAnalyzer foldedSVerb = chain(
			"R4 fold->foldedStem[sverb]", foldedStemChain(new FoldedRomanianStemmer(false, true, false))
		);
		final FulltextAnalyzer foldedAm = chain(
			"R5 fold->foldedStem[am]", foldedStemChain(new FoldedRomanianStemmer(false, false, true))
		);
		final FulltextAnalyzer foldedAll = chain(
			"R6 fold->foldedStem[tiune,sverb,am]", foldedStemChain(new FoldedRomanianStemmer(true, true, true))
		);
		final FulltextAnalyzer foldOnly = chain("R8 fold only, no stemmer", foldOnlyChain());
		// M7: the production chain indexes, the query side folds and emits every hypothesis the forked
		// switches could produce - the ambiguity is absorbed by the query fan-out instead of being committed
		final FulltextAnalyzer hypothesisFull = chain(
			"R20 query hypotheses[all]", fullHypothesisQueryChain()
		);
		final FulltextAnalyzer hypothesisSAVerb = chain(
			"R21n query hypotheses[sverb,averb]", hypothesisQueryChain(false, true, false, true)
		);
		final FulltextAnalyzer hypothesisAVerbOnly = chain(
			"R22n query hypotheses[averb]", hypothesisQueryChain(false, false, false, true)
		);

		final List<Measurement> collected = new ArrayList<>(16);
		collected.add(measure(production, production, MatchStrategy.EXACT));
		collected.add(measure(normalizedProduction, normalizedProduction, MatchStrategy.EXACT));
		collected.add(measure(bare, bare, MatchStrategy.EXACT));
		collected.add(measure(naiveFoldFirst, naiveFoldFirst, MatchStrategy.EXACT));
		collected.add(measure(foldedConservative, foldedConservative, MatchStrategy.EXACT));
		collected.add(measure(foldedTiune, foldedTiune, MatchStrategy.EXACT));
		collected.add(measure(foldedSVerb, foldedSVerb, MatchStrategy.EXACT));
		collected.add(measure(foldedAm, foldedAm, MatchStrategy.EXACT));
		collected.add(measure(foldedAll, foldedAll, MatchStrategy.EXACT));
		collected.add(measure(foldOnly, foldOnly, MatchStrategy.EXACT));
		collected.add(
			RomanianAnalysisFixture.measure(
				"R20 R0-index/hypothesis query", production, hypothesisFull, MatchStrategy.EXACT
			)
		);
		collected.add(
			RomanianAnalysisFixture.measure(
				"R20n R0n-index/hypothesis query", normalizedProduction, hypothesisFull, MatchStrategy.EXACT
			)
		);
		collected.add(
			RomanianAnalysisFixture.measure(
				"R21n R0n-index/hypo[sverb,averb]", normalizedProduction, hypothesisSAVerb, MatchStrategy.EXACT
			)
		);
		collected.add(
			RomanianAnalysisFixture.measure(
				"R22n R0n-index/hypo[averb]", normalizedProduction, hypothesisAVerbOnly, MatchStrategy.EXACT
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
		return RomanianAnalysisFixture.measure(
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
	 * Builds the naive fold-before-stem chain — mechanism M3, the shape Vespa and Typesense ship: the
	 * unmodified Snowball stemmer is fed folded input its cedilla-spelled tables were not written for.
	 *
	 * Folding sits **after** the stop filter for the same reason as in the Czech matrix: the stop list is
	 * spelled with diacritics (cedilla ones here), and folding ahead of it would change a third property while
	 * two are being measured.
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
					new LowerCaseFilter(source), RomanianAnalyzer.getDefaultStopSet()
				);
				stream = new ASCIIFoldingFilter(stream);
				stream = new SnowballFilter(stream, new RomanianStemmer());
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
	private static Analyzer foldedStemChain(@Nonnull FoldedRomanianStemmer stemmer) {
		return new Analyzer() {
			@Override
			protected TokenStreamComponents createComponents(String fieldName) {
				final Tokenizer source = new StandardTokenizer();
				TokenStream stream = new StopFilter(
					new LowerCaseFilter(source), RomanianAnalyzer.getDefaultStopSet()
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
					new StopFilter(new LowerCaseFilter(source), RomanianAnalyzer.getDefaultStopSet())
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
	 * Builds the canonical M7 query chain over {@link FoldedRomanianStemmer#allHypotheses()} — every rule and
	 * step-gate combination plus the surface hypothesis, the exact set the ro_RO lexicon sweep
	 * ({@link RomanianFoldedStemmerLexiconTest}) verifies. The R20/R20n rows use this; R21n/R22n keep the
	 * two-fork construction below to show what dropping a fork costs.
	 *
	 * @return the Lucene chain
	 */
	@Nonnull
	private static Analyzer fullHypothesisQueryChain() {
		final List<FoldedStemmer> stemmers = FoldedRomanianStemmer.allHypotheses();
		return new Analyzer() {
			@Override
			protected TokenStreamComponents createComponents(String fieldName) {
				final Tokenizer source = new StandardTokenizer();
				TokenStream stream = new StopFilter(
					new LowerCaseFilter(source), RomanianAnalyzer.getDefaultStopSet()
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
	 * produce across the switch positions of its forked rules, as terms at one position. The index half is the
	 * unmodified R0 chain, so this chain never analyses a stored value — the folded ambiguities are absorbed by
	 * the query fan-out instead of being committed either way.
	 *
	 * @param forkTiune whether the `ţiune`→`t` rewrite is forked (both positions emitted)
	 * @param forkSVerb whether the `ş`-spelled verb endings are forked
	 * @param forkAm    whether the folded-`am` action is forked
	 * @param forkAVerb whether the partnerless `ă`-spelled verb endings are forked
	 * @return the Lucene chain
	 */
	@Nonnull
	private static Analyzer hypothesisQueryChain(
		boolean forkTiune,
		boolean forkSVerb,
		boolean forkAm,
		boolean forkAVerb
	) {
		// one stemmer per forked switch combination - the union of their outputs is exactly the set of stems a
		// branching stemmer would produce. Unforked switches stay off, i.e. at the conservative position.
		final List<FoldedRomanianStemmer> stemmers = new ArrayList<>(16);
		final boolean[] tiunePositions = forkTiune ? new boolean[]{false, true} : new boolean[]{false};
		final boolean[] sVerbPositions = forkSVerb ? new boolean[]{false, true} : new boolean[]{false};
		final boolean[] amPositions = forkAm ? new boolean[]{false, true} : new boolean[]{false};
		final boolean[] aVerbPositions = forkAVerb ? new boolean[]{false, true} : new boolean[]{false};
		for (final boolean tiuneRewrite : tiunePositions) {
			for (final boolean sVerbEndings : sVerbPositions) {
				for (final boolean amUnconditional : amPositions) {
					for (final boolean aVerbEndings : aVerbPositions) {
						stemmers.add(
							new FoldedRomanianStemmer(tiuneRewrite, sVerbEndings, amUnconditional, aVerbEndings)
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
					new LowerCaseFilter(source), RomanianAnalyzer.getDefaultStopSet()
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
	 * Builds the R0n chain: the Lucene 10 `RomanianAnalyzer` shape backported onto 9.12.3 — comma-below
	 * spellings are normalized to the cedilla ones **before** the stop filter and the stemmer, so that the
	 * cedilla-written stop list and Snowball tables see the text they were written for; folding is appended
	 * after the stemmer as in every production chain.
	 *
	 * @return the Lucene chain
	 */
	@Nonnull
	private static Analyzer normalizedProductionChain() {
		return new Analyzer() {
			@Override
			protected TokenStreamComponents createComponents(String fieldName) {
				final Tokenizer source = new StandardTokenizer();
				TokenStream stream = new CommaBelowNormalizationFilter(new LowerCaseFilter(source));
				stream = new StopFilter(stream, RomanianAnalyzer.getDefaultStopSet());
				stream = new SnowballFilter(stream, new RomanianStemmer());
				stream = new ASCIIFoldingFilter(stream);
				return new TokenStreamComponents(source, stream);
			}

			@Override
			protected TokenStream normalize(String fieldName, TokenStream in) {
				return new ASCIIFoldingFilter(new CommaBelowNormalizationFilter(new LowerCaseFilter(in)));
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
	@DisplayName("The folding wrapper is the prerequisite everything else builds on")
	void shouldShowFoldingWrapperIsPrerequisite() {
		// the survey's step 0: evitaDB currently maps `ro` to nothing at all, and the closest Lucene default
		// (the bare analyzer) is catastrophically worse than the analyzer plus the folding wrapper
		final Measurement production = measurementOf("R0 stem->fold (survey step 0)", MatchStrategy.EXACT);
		final Measurement bare = measurementOf("R0b bare RomanianAnalyzer", MatchStrategy.EXACT);

		assertTrue(
			production.accentTypingMisses().size() < bare.accentTypingMisses().size(),
			"The folding wrapper is supposed to rescue most accent-typed queries over the bare analyzer.\n"
				+ production.detail(40)
		);
	}

	@Test
	@DisplayName("The production candidate still leaves the bare-typed cross-form query broken")
	void shouldShowProductionShapeLeavesBareTypedGap() {
		// the same gap the Czech A0 row measured: fold-after-stem repairs same-form recall through the fold
		// lane, but a bare-typed query in another inflection meets neither lane
		final Measurement production = measurementOf("R0 stem->fold (survey step 0)", MatchStrategy.EXACT);

		assertFalse(
			production.bareTypedCrossFormMisses().isEmpty(),
			"R0 is not supposed to cover the bare-typed cross-form query - if it does, Romanian does not need "
				+ "any M7 work and the record must be rewritten.\n" + production.detail(40)
		);
	}

	@Test
	@DisplayName("Hypothesis-expanded queries close the accent gap at zero precision cost")
	void shouldShowHypothesisQueriesCloseTheGap() {
		// mechanism M7's claim, transferred from Czech: all of R0n's accent failures are query-side, so a
		// hypothesis fan-out over the fold-ambiguous rules must reach total accent-typed recall while the
		// single-lane index keeps merging nothing
		final Measurement production = measurementOf("R0 stem->fold (survey step 0)", MatchStrategy.EXACT);
		final Measurement hypothesis = measurementOf("R20n R0n-index/hypothesis query", MatchStrategy.EXACT);

		assertTrue(
			hypothesis.accentTypingMisses().isEmpty(),
			"The hypothesis query chain is supposed to make accent-typed recall total, but missed:\n"
				+ hypothesis.detail(40)
		);
		assertTrue(
			hypothesis.bareTypedCrossFormMisses().size() < production.bareTypedCrossFormMisses().size(),
			"The hypothesis query chain is supposed to cover more bare-typed cross-form pairs than R0.\n"
				+ hypothesis.detail(40)
		);
		assertTrue(
			hypothesis.falseMerges().isEmpty(),
			"Keeping the accented stemmer on the index is supposed to merge nothing on this vocabulary.\n"
				+ hypothesis.detail(40)
		);
	}

	@Test
	@DisplayName("The full verified hypothesis set beats every fork subset; the s-verb fork is required")
	void shouldShowWhichForksMatter() {
		// R21n/R22n keep the original two-fork construction so the value of individual forks stays visible;
		// R20n runs the full lexicon-verified set (rules, step gates, surface hypothesis) and must dominate
		final Measurement full = measurementOf("R20n R0n-index/hypothesis query", MatchStrategy.EXACT);
		final Measurement subset = measurementOf(
			"R21n R0n-index/hypo[sverb,averb]", MatchStrategy.EXACT
		);
		final Measurement aVerbOnly = measurementOf("R22n R0n-index/hypo[averb]", MatchStrategy.EXACT);

		assertTrue(
			full.accentTypingMisses().isEmpty()
				&& full.bareTypedCrossFormMisses().size() <= subset.bareTypedCrossFormMisses().size(),
			"The full hypothesis set is supposed to cover at least what any fork subset covers.\n"
				+ full.detail(40)
		);
		assertTrue(
			aVerbOnly.accentTypingMisses().size() > subset.accentTypingMisses().size(),
			"Dropping the s-verb fork is supposed to lose the -ești adjective plurals; it did not - which "
				+ "means the vocabulary no longer exercises that fork.\n" + aVerbOnly.detail(40)
		);
	}

	@Test
	@DisplayName("The hypothesis fan-out stays a handful of terms per token")
	void shouldKeepHypothesisFanOutSmall() {
		// the cost M7 pays instead of index precision is query fan-out - pin that it stays a handful of terms
		// per token rather than exploding with the number of forked rules (four forks = 16 stemmers here)
		final FulltextAnalyzer fanOutProbe = chain(
			"R20n fan-out probe", fullHypothesisQueryChain()
		);
		int analyzedFormCount = 0;
		int emittedTermCount = 0;
		int maxTermsPerForm = 0;
		final List<AnalysisApproachMeasurer.Lemma> allLemmas = new ArrayList<>(
			RomanianAnalysisFixture.VOCABULARY.size() + RomanianAnalysisFixture.CONFUSABLE_LEMMAS.size()
		);
		allLemmas.addAll(RomanianAnalysisFixture.VOCABULARY);
		allLemmas.addAll(RomanianAnalysisFixture.CONFUSABLE_LEMMAS);
		for (final AnalysisApproachMeasurer.Lemma lemma : allLemmas) {
			for (final String form : lemma.forms()) {
				final java.util.Set<String> terms = AnalysisApproachMeasurer.analyzeWord(
					fanOutProbe, AnalysisApproachMeasurer.stripAccents(form)
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


	/**
	 * Rewrites the comma-below letters `ș`/`ț` (U+0219/U+021B, the correct modern spellings) to the legacy
	 * cedilla ones `ş`/`ţ` (U+015F/U+0163) — the inverse direction of Lucene 10's `RomanianNormalizationFilter`,
	 * chosen because everything downstream in 9.12.3 (stop list, Snowball tables) is written in the cedilla
	 * orthography. Uppercase never reaches this filter; it sits behind the lowercase filter.
	 */
	private static final class CommaBelowNormalizationFilter extends TokenFilter {

		/**
		 * Term text of the current token.
		 */
		@Nonnull private final CharTermAttribute termAttribute = addAttribute(CharTermAttribute.class);

		/**
		 * Creates the filter.
		 *
		 * @param input stream to filter, already lowercased
		 */
		private CommaBelowNormalizationFilter(@Nonnull TokenStream input) {
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
				if (buffer[i] == '\u0219') {
					buffer[i] = '\u015F';
				} else if (buffer[i] == '\u021B') {
					buffer[i] = '\u0163';
				}
			}
			return true;
		}

	}

}
