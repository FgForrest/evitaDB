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
import io.evitadb.index.fulltext.analysis.SlovakStemmer.SlovakStemFilter;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.hunspell.Dictionary;
import org.apache.lucene.analysis.hunspell.HunspellStemFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measures the Slovak analysis mechanisms proposed by the SK/PL/RO survey
 * (`documentation/adr/2026-08-24-fulltext-search-lucene-vs-inhouse/prototypes/p5-prior-art-sk-pl-ro.md`, §6)
 * against one Slovak vocabulary and the five metrics of {@link AnalysisApproachMeasurer}. Unlike the Czech,
 * Romanian and Polish matrices, there is no upstream stemmer to compare against — the survey found none
 * anywhere — so this matrix validates the **from-scratch** {@link SlovakStemmer} tables as much as the
 * deployment mechanisms.
 *
 * | id  | survey | what is built here                                                                          |
 * |-----|--------|---------------------------------------------------------------------------------------------|
 * | S0  | M4     | fold only, no stemmer — **today's production** (`sk` → `TokenizingAnalyzer` + folding)        |
 * | S1  | —      | {@link SlovakStemmer} then folding — the accented-index production shape (A0/R0 analogue)     |
 * | S1b | —      | bare {@link SlovakStemmer}, no folding — what the stemmer alone would do                      |
 * | S2  | M1     | fold first, then {@link FoldedSlovakStemmer} with all three ambiguous rules on — the symmetric chain |
 * | S3  | M1     | …with the `om` ending off                                                                    |
 * | S4  | M1     | …with the `ie`-shortening off                                                                |
 * | S5  | M1     | …with the epenthesis off                                                                     |
 * | S20 | M7     | asymmetric — S1 indexes, the query runs every folded-stemmer hypothesis (all three forks).    |
 * |     |        | The first three runs believed the port needed no forks; the sk_SK lexicon sweep              |
 * |     |        | ({@link SlovakFoldedStemmerLexiconTest}) falsified that and found the ambiguities             |
 * | S10 | —      | Hunspell `sk_SK` then folding — the adoptable-artifact check, mirroring the Czech A10 order   |
 * | S12 | —      | folding then Hunspell `sk_SK` — the other ordering, mirroring the Czech A12                   |
 *
 * S0, S1 and S2 are measured a second time under prefix-plus-typo matching, as in the other matrices.
 *
 * The Hunspell rows load the `sk_SK` dictionary pair from the test resources next to the Czech `cs_CZ` one;
 * their row IDs deliberately echo the Czech A10/A12 so the two languages' dictionary results read side by
 * side. The Czech numbers (A10 at 74/348, A12 at 20/348 combined recall) are the prior these rows test.
 *
 * The matrix chains use no stop filter: Lucene ships no Slovak stop list, today's production chain has none
 * either, and the vocabulary contains no stop-word-shaped forms — a production stop list remains an owned
 * artifact to source separately (see the measurement record).
 *
 * **This class is an instrument, not a guard.** Its assertions pin only the findings that would change the
 * conclusion if they reversed; the numbers themselves belong in {@link #shouldReportApproachMatrix()}'s output,
 * which is the thing to read.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Slovak analysis approaches — full comparison matrix")
@Tag(ENGINE)
@Tag(FULLTEXT)
class SlovakAnalysisApproachMatrixTest {

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
	static void measureEveryApproach() throws IOException {
		final Dictionary hunspellDictionary = loadHunspellDictionary();
		final FulltextAnalyzer foldOnly = chain(
			"S0 fold only (production)", new DiacriticsFoldingAnalyzerWrapper(new TokenizingAnalyzer())
		);
		final FulltextAnalyzer stemThenFold = chain("S1 stem->fold", stemThenFoldChain());
		final FulltextAnalyzer stemBare = chain("S1b stem, no fold", bareStemChain());
		final FulltextAnalyzer foldedStem = chain(
			"S2 fold->foldedStem[om,ie,ek]", foldedStemChain(new FoldedSlovakStemmer(true, true, true))
		);
		final FulltextAnalyzer foldedNoOm = chain(
			"S3 fold->foldedStem[ie,ek]", foldedStemChain(new FoldedSlovakStemmer(false, true, true))
		);
		final FulltextAnalyzer foldedNoIe = chain(
			"S4 fold->foldedStem[om,ek]", foldedStemChain(new FoldedSlovakStemmer(true, false, true))
		);
		final FulltextAnalyzer foldedNoEk = chain(
			"S5 fold->foldedStem[om,ie]", foldedStemChain(new FoldedSlovakStemmer(true, true, false))
		);
		// M7: S1 indexes, the query side folds and emits every hypothesis the three forked rules could produce
		final FulltextAnalyzer hypothesisQuery = chain(
			"S20 query hypotheses[om,ie,ek]", hypothesisQueryChain()
		);
		final FulltextAnalyzer hunspell = chain(
			"S10 hunspell->fold", hunspellChain(hunspellDictionary, false)
		);
		final FulltextAnalyzer hunspellFoldFirst = chain(
			"S12 fold->hunspell", hunspellChain(hunspellDictionary, true)
		);

		final List<Measurement> collected = new ArrayList<>(10);
		collected.add(measure(foldOnly, foldOnly, MatchStrategy.EXACT));
		collected.add(measure(stemThenFold, stemThenFold, MatchStrategy.EXACT));
		collected.add(measure(stemBare, stemBare, MatchStrategy.EXACT));
		collected.add(measure(foldedStem, foldedStem, MatchStrategy.EXACT));
		collected.add(measure(foldedNoOm, foldedNoOm, MatchStrategy.EXACT));
		collected.add(measure(foldedNoIe, foldedNoIe, MatchStrategy.EXACT));
		collected.add(measure(foldedNoEk, foldedNoEk, MatchStrategy.EXACT));
		collected.add(
			SlovakAnalysisFixture.measure(
				"S20 S1-index/hypothesis query", stemThenFold, hypothesisQuery, MatchStrategy.EXACT
			)
		);
		collected.add(measure(hunspell, hunspell, MatchStrategy.EXACT));
		collected.add(measure(hunspellFoldFirst, hunspellFoldFirst, MatchStrategy.EXACT));
		collected.add(measure(foldOnly, foldOnly, MatchStrategy.PREFIX_FUZZY));
		collected.add(measure(stemThenFold, stemThenFold, MatchStrategy.PREFIX_FUZZY));
		collected.add(measure(foldedStem, foldedStem, MatchStrategy.PREFIX_FUZZY));
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
		return SlovakAnalysisFixture.measure(
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
	 * Builds the S1 chain: the accented stemmer with folding appended after it — the production shape every
	 * language with an accented stemmer uses here.
	 *
	 * @return the Lucene chain
	 */
	@Nonnull
	private static Analyzer stemThenFoldChain() {
		return new Analyzer() {
			@Override
			protected TokenStreamComponents createComponents(String fieldName) {
				final Tokenizer source = new StandardTokenizer();
				TokenStream stream = new SlovakStemFilter(new LowerCaseFilter(source), new SlovakStemmer());
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
	 * Builds the S1b chain: the accented stemmer with no folding — the bare-analyzer analogue of the Romanian
	 * R0b row.
	 *
	 * @return the Lucene chain
	 */
	@Nonnull
	private static Analyzer bareStemChain() {
		return new Analyzer() {
			@Override
			protected TokenStreamComponents createComponents(String fieldName) {
				final Tokenizer source = new StandardTokenizer();
				final TokenStream stream = new SlovakStemFilter(
					new LowerCaseFilter(source), new SlovakStemmer()
				);
				return new TokenStreamComponents(source, stream);
			}

			@Override
			protected TokenStream normalize(String fieldName, TokenStream in) {
				return new LowerCaseFilter(in);
			}
		};
	}

	/**
	 * Builds an S2-family chain: fold first, then the folded-space stemmer — mechanism M1.
	 *
	 * @param stemmer the folded stemmer configuration to apply
	 * @return the Lucene chain
	 */
	@Nonnull
	private static Analyzer foldedStemChain(@Nonnull FoldedSlovakStemmer stemmer) {
		return new Analyzer() {
			@Override
			protected TokenStreamComponents createComponents(String fieldName) {
				final Tokenizer source = new StandardTokenizer();
				TokenStream stream = new ASCIIFoldingFilter(new LowerCaseFilter(source));
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
	 * Builds the query half of mechanism M7: fold first, then emit every stem the folded stemmer could produce
	 * across the switch positions of its two fold-ambiguous rules, as terms at one position.
	 *
	 * @return the Lucene chain
	 */
	@Nonnull
	private static Analyzer hypothesisQueryChain() {
		final List<FoldedSlovakStemmer> stemmers = new ArrayList<>(8);
		for (final boolean omEnding : new boolean[]{true, false}) {
			for (final boolean ieShortening : new boolean[]{true, false}) {
				for (final boolean epenthesis : new boolean[]{true, false}) {
					stemmers.add(new FoldedSlovakStemmer(omEnding, ieShortening, epenthesis));
				}
			}
		}
		return new Analyzer() {
			@Override
			protected TokenStreamComponents createComponents(String fieldName) {
				final Tokenizer source = new StandardTokenizer();
				TokenStream stream = new ASCIIFoldingFilter(new LowerCaseFilter(source));
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
	 * Builds a Hunspell chain over the `sk_SK` dictionary, in either ordering — the Czech A10/A12 chains
	 * without the stop filter (Slovak has no stop list, consistently with every other row of this matrix).
	 *
	 * @param dictionary        loaded `sk_SK` dictionary
	 * @param foldBeforeStemmer whether folding precedes the dictionary lookup
	 * @return the Lucene chain
	 */
	@Nonnull
	private static Analyzer hunspellChain(@Nonnull Dictionary dictionary, boolean foldBeforeStemmer) {
		return new Analyzer() {
			@Override
			protected TokenStreamComponents createComponents(String fieldName) {
				final Tokenizer source = new StandardTokenizer();
				TokenStream stream = new LowerCaseFilter(source);
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
	 * Loads the `sk_SK` Hunspell dictionary pair from the test resources, next to the Czech `cs_CZ` one.
	 *
	 * @return the loaded dictionary
	 * @throws IOException when a resource cannot be read or parsed
	 */
	@Nonnull
	private static Dictionary loadHunspellDictionary() throws IOException {
		try (
			final Directory tempDirectory = new ByteBuffersDirectory();
			final InputStream affixStream = resource("sk_SK.aff");
			final InputStream dictionaryStream = resource("sk_SK.dic")
		) {
			try {
				return new Dictionary(tempDirectory, "hunspell-sk", affixStream, dictionaryStream);
			} catch (ParseException e) {
				throw new IOException("Failed to parse the sk_SK Hunspell dictionary.", e);
			}
		}
	}

	/**
	 * Opens a Hunspell test resource by its file name.
	 *
	 * @param fileName name of the file inside the shared hunspell resource folder
	 * @return open stream over the resource
	 */
	@Nonnull
	private static InputStream resource(@Nonnull String fileName) {
		final InputStream stream = SlovakAnalysisApproachMatrixTest.class.getResourceAsStream(
			"/fulltext/hunspell/" + fileName
		);
		if (stream == null) {
			throw new IllegalStateException(
				"Test resource `/fulltext/hunspell/" + fileName + "` is missing from the classpath."
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
	@DisplayName("Fold-only production carries no inflection at all under exact matching")
	void shouldShowFoldOnlyCarriesNoInflection() {
		// the Czech A8 corner, measured for Slovak: today's production chain converges nothing cross-form -
		// the bar the survey said was on the floor, now on the record
		final Measurement foldOnly = measurementOf("S0 fold only (production)", MatchStrategy.EXACT);

		assertTrue(
			foldOnly.bareTypedCrossFormMisses().size() == foldOnly.bareTypedCrossFormPairCount(),
			"Fold-only is not supposed to converge any bare-typed cross-form pair under exact matching.\n"
				+ foldOnly.detail(40)
		);
	}

	@Test
	@DisplayName("The in-house stemmer closes the gap at zero precision cost")
	void shouldShowStemmerClosesTheGap() {
		// the symmetric folded chain carries the total-recall claim; S1's accented table on bare-typed input
		// over-strips the chróm/kategória classes the lexicon sweep added, which is M7's problem to solve
		final Measurement symmetric = measurementOf("S2 fold->foldedStem[om,ie,ek]", MatchStrategy.EXACT);

		assertTrue(
			symmetric.accentTypingMisses().isEmpty(),
			"The folded stemmer is supposed to make accent-typed recall total, but missed:\n"
				+ symmetric.detail(40)
		);
		assertTrue(
			symmetric.falseMerges().isEmpty(),
			"The Slovak tables are supposed to merge nothing - the Czech-style hazards (ruka/rok, buk/bok, "
				+ "cesta/český, forma/formát) have no Slovak rule to commit them.\n" + symmetric.detail(40)
		);
	}

	@Test
	@DisplayName("The forked hypothesis query dominates every symmetric folded configuration")
	void shouldShowHypothesisQueryDominates() {
		// the first three runs concluded the three deployments were identical; the lexicon sweep found the two
		// fold-ambiguities and the fixture now carries their probes (chróm, kategória) - since then, Slovak
		// looks like the other languages: each symmetric switch position loses one class, and only the M7
		// fork covers both at once
		final Measurement stemThenFold = measurementOf("S1 stem->fold", MatchStrategy.EXACT);
		final Measurement symmetric = measurementOf("S2 fold->foldedStem[om,ie,ek]", MatchStrategy.EXACT);
		final Measurement hypothesis = measurementOf("S20 S1-index/hypothesis query", MatchStrategy.EXACT);

		assertTrue(
			hypothesis.accentTypingMisses().isEmpty(),
			"The hypothesis query chain is supposed to make accent-typed recall total, but missed:\n"
				+ hypothesis.detail(40)
		);
		assertTrue(
			hypothesis.bareTypedCrossFormMisses().size() <= stemThenFold.bareTypedCrossFormMisses().size()
				&& hypothesis.bareTypedCrossFormMisses().size()
				<= symmetric.bareTypedCrossFormMisses().size(),
			"The forked hypothesis query is supposed to cover at least what either symmetric deployment "
				+ "covers.\n" + hypothesis.detail(40)
		);
		assertTrue(
			hypothesis.falseMerges().isEmpty(),
			"Keeping the accented stemmer on the index is supposed to merge nothing on this vocabulary.\n"
				+ hypothesis.detail(40)
		);
	}

	@Test
	@DisplayName("The hypothesis fan-out stays a handful of terms per token")
	void shouldKeepHypothesisFanOutSmall() {
		final FulltextAnalyzer fanOutProbe = chain("S20 fan-out probe", hypothesisQueryChain());
		int analyzedFormCount = 0;
		int emittedTermCount = 0;
		int maxTermsPerForm = 0;
		final List<AnalysisApproachMeasurer.Lemma> allLemmas = new ArrayList<>(
			SlovakAnalysisFixture.VOCABULARY.size() + SlovakAnalysisFixture.CONFUSABLE_LEMMAS.size()
		);
		allLemmas.addAll(SlovakAnalysisFixture.VOCABULARY);
		allLemmas.addAll(SlovakAnalysisFixture.CONFUSABLE_LEMMAS);
		for (final AnalysisApproachMeasurer.Lemma lemma : allLemmas) {
			for (final String form : lemma.forms()) {
				final Set<String> terms = AnalysisApproachMeasurer.analyzeWord(
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

}
