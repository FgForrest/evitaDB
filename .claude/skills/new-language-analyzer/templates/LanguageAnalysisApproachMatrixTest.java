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
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.StopFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
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
 * TEMPLATE — copy to `evita_test/evita_functional_tests/src/test/java/io/evitadb/index/fulltext/analysis/
 * {@code <Lang>AnalysisApproachMatrixTest.java}`, replace every `Language` with the language name, `X` with the
 * language's unused row prefix, and fill in the chains the survey proposed. Delete the class again once its
 * verdict is recorded — it is a decision instrument, not a regression guard.
 *
 * Measures the {@code Language} analysis mechanisms proposed by the prior-art survey
 * (`documentation/adr/2026-08-24-fulltext-search-lucene-vs-inhouse/prototypes/p5-prior-art-language.md`) against
 * one vocabulary ({@link LanguageAnalysisFixture}) and the five metrics of {@link AnalysisApproachMeasurer}.
 *
 * | id   | mechanism | what is built here                                                                    |
 * |------|-----------|---------------------------------------------------------------------------------------|
 * | X0   | step 0    | the Lucene analyzer + folding after — the zero-code wiring                            |
 * | X0b  | —         | the bare Lucene analyzer, no folding — what most engines ship                          |
 * | X1   | M3        | fold first, then the unmodified stemmer — the Vespa/Typesense shape                    |
 * | X2…  | M1        | fold first, then {@code FoldedLanguageStemmer} with its ambiguous rules switched off/on |
 * | X8   | M4        | folding only, no stemmer                                                              |
 * | X20  | M7        | asymmetric — X0 indexes, the query runs every folded-stemmer hypothesis               |
 *
 * X0, X2 and X8 are measured a second time under prefix-plus-typo matching.
 *
 * **This class is an instrument, not a guard.** Its assertions pin only the findings that would change the
 * conclusion if they reversed; the numbers themselves belong in {@link #shouldReportApproachMatrix()}'s output,
 * which is the thing to read — from `target/surefire-reports/*.xml`, never from the console, whose encoding
 * mangles diacritics.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Language analysis approaches — full comparison matrix")
@Tag(ENGINE)
@Tag(FULLTEXT)
class LanguageAnalysisApproachMatrixTest {

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
		// TEMPLATE: one chain per row; the names are the row IDs the record quotes
		final FulltextAnalyzer stepZero = chain("X0 stem->fold (step 0)", stepZeroChain());
		final FulltextAnalyzer bare = chain("X0b bare analyzer", bareChain());
		final FulltextAnalyzer naiveFoldFirst = chain("X1 fold->stem (naive)", foldThenStemChain());
		final FulltextAnalyzer foldOnly = chain("X8 fold only, no stemmer", foldOnlyChain());
		final FulltextAnalyzer hypothesisQuery = chain("X20 query hypotheses[all]", hypothesisQueryChain());

		final List<Measurement> collected = new ArrayList<>(16);
		collected.add(measure(stepZero, stepZero, MatchStrategy.EXACT));
		collected.add(measure(bare, bare, MatchStrategy.EXACT));
		collected.add(measure(naiveFoldFirst, naiveFoldFirst, MatchStrategy.EXACT));
		collected.add(measure(foldOnly, foldOnly, MatchStrategy.EXACT));
		// M7 is asymmetric: the step-0 chain indexes, the hypothesis chain queries
		collected.add(
			AnalysisApproachMeasurer.measure(
				"X20 X0-index/hypothesis query", stepZero, hypothesisQuery, MatchStrategy.EXACT,
				LanguageAnalysisFixture.VOCABULARY, LanguageAnalysisFixture.CONFUSABLE_LEMMAS
			)
		);
		collected.add(measure(stepZero, stepZero, MatchStrategy.PREFIX_FUZZY));
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
		// TEMPLATE: pass `LanguageAnalysisFixture::bareType` as the last argument when the language has letters
		// NFD stripping leaves alone (Polish `ł`, Nordic `ø`, …)
		return AnalysisApproachMeasurer.measure(
			indexAnalyzer.getAnalyzerName(), indexAnalyzer, queryAnalyzer, strategy,
			LanguageAnalysisFixture.VOCABULARY, LanguageAnalysisFixture.CONFUSABLE_LEMMAS
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
	 * X0 — the survey's step 0: the Lucene analyzer of the language with diacritics folding appended after the
	 * stemmer, i.e. the production baseline shape of every language that has a Lucene analyzer.
	 *
	 * @return the Lucene chain
	 */
	@Nonnull
	private static Analyzer stepZeroChain() {
		// TEMPLATE: return new DiacriticsFoldingAnalyzerWrapper(new LanguageAnalyzer());
		return new DiacriticsFoldingAnalyzerWrapper(bareChain());
	}

	/**
	 * X0b — the bare Lucene analyzer, no folding: what most engines ship for the language.
	 *
	 * @return the Lucene chain
	 */
	@Nonnull
	private static Analyzer bareChain() {
		// TEMPLATE: return new LanguageAnalyzer();
		return new TokenizingAnalyzer();
	}

	/**
	 * X1 — the naive fold-before-stem chain, mechanism M3: the unmodified stemmer is fed folded input its
	 * tables were not written for. Folding sits **after** the stop filter, because the stop list is spelled
	 * with diacritics and moving it would change a third property while two are being measured.
	 *
	 * @return the Lucene chain
	 */
	@Nonnull
	private static Analyzer foldThenStemChain() {
		return new Analyzer() {
			@Override
			protected TokenStreamComponents createComponents(String fieldName) {
				final Tokenizer source = new StandardTokenizer();
				TokenStream stream = new StopFilter(new LowerCaseFilter(source), stopSet());
				stream = new ASCIIFoldingFilter(stream);
				// TEMPLATE: stream = new SnowballFilter(stream, new LanguageStemmer());  (or the light stem filter)
				return new TokenStreamComponents(source, stream);
			}

			@Override
			protected TokenStream normalize(String fieldName, TokenStream in) {
				return new ASCIIFoldingFilter(new LowerCaseFilter(in));
			}
		};
	}

	/**
	 * X8 — no stemmer at all, mechanism M4's analysis half. Inflection is left entirely to the matching
	 * strategy, which is why this row is also measured under prefix-plus-typo matching.
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
					new StopFilter(new LowerCaseFilter(source), stopSet())
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
	 * X20 — the query half of mechanism M7: fold first, then emit **every** stem the folded stemmer could
	 * produce across the switch positions of its fork-able rules, as terms at one position. The index half is
	 * the unmodified X0 chain, so this chain never analyses a stored value.
	 *
	 * @return the Lucene chain
	 */
	@Nonnull
	private static Analyzer hypothesisQueryChain() {
		// TEMPLATE: final List<FoldedStemmer> stemmers = FoldedLanguageStemmer.allHypotheses();
		final List<FoldedStemmer> stemmers = List.of();
		return new Analyzer() {
			@Override
			protected TokenStreamComponents createComponents(String fieldName) {
				final Tokenizer source = new StandardTokenizer();
				TokenStream stream = new StopFilter(new LowerCaseFilter(source), stopSet());
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
	 * The stop set every chain of the matrix shares, so that stop-word handling is a constant across rows.
	 *
	 * @return the language's default stop set, or an empty set when Lucene ships none
	 */
	@Nonnull
	private static org.apache.lucene.analysis.CharArraySet stopSet() {
		// TEMPLATE: return LanguageAnalyzer.getDefaultStopSet();
		return org.apache.lucene.analysis.CharArraySet.EMPTY_SET;
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
		report.append('\n').append(AnalysisApproachMeasurer.matrixHeader());
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
	@DisplayName("The step-0 chain still leaves the bare-typed cross-form query broken")
	void shouldShowStepZeroLeavesBareTypedGap() {
		// the gap every stem-then-fold chain has measured so far: fold-after-stem repairs same-form recall
		// through the fold lane, but a bare-typed query in another inflection meets neither lane. If this
		// assertion fails, the language does not need any M7 work and the record must say so.
		final Measurement stepZero = measurementOf("X0 stem->fold (step 0)", MatchStrategy.EXACT);
		assertFalse(
			stepZero.bareTypedCrossFormMisses().isEmpty(),
			"X0 is not supposed to cover the bare-typed cross-form query.\n" + stepZero.detail(40)
		);
	}

	@Test
	@DisplayName("Hypothesis-expanded queries close the accent gap without new false merges")
	void shouldShowHypothesisQueriesCloseTheGap() {
		final Measurement stepZero = measurementOf("X0 stem->fold (step 0)", MatchStrategy.EXACT);
		final Measurement hypothesis = measurementOf("X20 X0-index/hypothesis query", MatchStrategy.EXACT);
		assertTrue(
			hypothesis.accentTypingMisses().isEmpty(),
			"The hypothesis query chain is supposed to make accent-typed recall total, but missed:\n"
				+ hypothesis.detail(40)
		);
		assertTrue(
			hypothesis.bareTypedCrossFormMisses().size() < stepZero.bareTypedCrossFormMisses().size(),
			"The hypothesis query chain is supposed to cover more bare-typed cross-form pairs than X0.\n"
				+ hypothesis.detail(40)
		);
	}

	@Test
	@DisplayName("The hypothesis fan-out stays a handful of terms per token")
	void shouldKeepHypothesisFanOutSmall() {
		// the cost M7 pays instead of index precision is query fan-out - pin that it stays a handful of terms
		// per token rather than exploding with the number of forked rules
		final FulltextAnalyzer fanOutProbe = chain("X20 fan-out probe", hypothesisQueryChain());
		int analyzedFormCount = 0;
		int emittedTermCount = 0;
		int maxTermsPerForm = 0;
		final List<Lemma> allLemmas = new ArrayList<>(
			LanguageAnalysisFixture.VOCABULARY.size() + LanguageAnalysisFixture.CONFUSABLE_LEMMAS.size()
		);
		allLemmas.addAll(LanguageAnalysisFixture.VOCABULARY);
		allLemmas.addAll(LanguageAnalysisFixture.CONFUSABLE_LEMMAS);
		for (final Lemma lemma : allLemmas) {
			for (final String form : lemma.forms()) {
				final Set<String> terms = AnalysisApproachMeasurer.analyzeWord(
					fanOutProbe, Lemma.stripAccents(form)
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
