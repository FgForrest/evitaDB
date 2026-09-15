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

import io.evitadb.spike.footprint.FootprintSpikeSupport;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Cost census of the M7 hypothesis query chains for the three languages that joined the Czech prototype —
 * Slovak, Polish and Romanian: what does the **flat configuration union** ({@link HypothesisStemFilter} over
 * 8 / 129 / 513 stemmer configurations) cost per analyzed query against the **branching walk**
 * ({@link BranchingHypothesisStemFilter} over the language's {@link BranchingStemmer}), which the
 * per-language `Branching*StemmerEquivalenceTest`s prove produces the identical term set over each whole
 * lexicon? Same harness as {@code CzechAnalysisPipelineBenchmark}, which carries the Czech pair (its M7
 * chain additionally holds a stop filter — the three chains here mirror their matrix tests' minimal shape:
 * tokenize, lowercase, fold, hypothesize).
 *
 * The three languages sweep the flat union's size an order of magnitude in each direction of interest:
 * Slovak's 8 configurations are the cheapest flat union any port has (does branching still pay?), Polish's
 * 129 each carry a per-instance copy of the assembled ending table (what does the *retained* mass look
 * like?), and Romanian's 513 are the second-worst flat cost after Czech's 1,025 while its branching walk is
 * the only one that copies buffers (how much of the Czech-style win survives the heavier machinery?).
 *
 * Both pipelines run through {@link FulltextAnalyzer#analyze}, the production entry point, NFC boundary
 * included. Run (footprint census first, then the JMH sweep; the add-opens are for JOL, and
 * {@code -Djol.magicFieldOffset=true} is required because the Polish and Romanian stemmers hold their tables
 * in **record** classes, whose field offsets {@code Unsafe} refuses without it):
 * {@code java --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED
 * -Djol.magicFieldOffset=true -cp evita_test/evita_performance_tests/target/benchmarks.jar
 * io.evitadb.index.fulltext.analysis.SkPlRoAnalysisPipelineBenchmark 'SkPlRoAnalysisPipelineBenchmark'
 * -prof gc}.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 4, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class SkPlRoAnalysisPipelineBenchmark {

	/**
	 * The M7 target workload per language: a short bare-typed e-commerce query (3 tokens), typed without
	 * diacritics.
	 */
	static final String SLOVAK_QUERY = "cierna kozena sedacka";
	static final String POLISH_QUERY = "czarna skorzana sofa";
	static final String ROMANIAN_QUERY = "canapea neagra piele";

	/**
	 * Prints the JOL retained-footprint census of all six pipelines plus their emitted-term counts, then
	 * hands over to the JMH runner for the time/allocation sweep. The analyzers are measured **unprimed** —
	 * see {@code CzechAnalysisPipelineBenchmark#main} for the {@code CloseableThreadLocal} trap that makes a
	 * primed measurement meaningless.
	 *
	 * @param args JMH runner arguments (benchmark regex, {@code -prof gc}, ...)
	 * @throws Exception when the JMH runner fails
	 */
	public static void main(String[] args) throws Exception {
		FootprintSpikeSupport.banner("SK/PL/RO M7 query chain retained footprint (unprimed)");
		census("SLOVAK", SLOVAK_QUERY);
		census("POLISH", POLISH_QUERY);
		census("ROMANIAN", ROMANIAN_QUERY);
		org.openjdk.jmh.Main.main(args);
	}

	/**
	 * Prints one language's unprimed footprints, then its emitted-term parity (priming after measurement).
	 *
	 * @param language language key, as the {@code @Param} spells it
	 * @param query    the language's benchmark query
	 */
	private static void census(@Nonnull String language, @Nonnull String query) {
		try (
			final FulltextAnalyzer flat = buildFlat(language);
			final FulltextAnalyzer branching = buildBranching(language)
		) {
			FootprintSpikeSupport.observation(
				language + " flat union", FootprintSpikeSupport.ownedSize(flat)
			);
			FootprintSpikeSupport.observation(
				language + " branching walk", FootprintSpikeSupport.ownedSize(branching)
			);
			System.out.printf(
				"  emitted terms - %s QUERY: flat %d, branching %d%n",
				language, flat.getTerms(query).size(), branching.getTerms(query).size()
			);
		}
	}

	/**
	 * Builds the language's flat M7 query chain — the matrix tests' minimal shape with
	 * {@link HypothesisStemFilter} over the language's full configuration union.
	 *
	 * @param language language key
	 * @return the wrapped flat chain
	 */
	@Nonnull
	static FulltextAnalyzer buildFlat(@Nonnull String language) {
		final List<? extends FoldedStemmer> stemmers = switch (language) {
			case "SLOVAK" -> slovakUnion();
			case "POLISH" -> FoldedPolishStemmer.allHypotheses();
			case "ROMANIAN" -> FoldedRomanianStemmer.allHypotheses();
			default -> throw new IllegalStateException("Unknown language: " + language);
		};
		return new FulltextAnalyzer(
			language.toLowerCase(Locale.ROOT) + "-m7-flat", AnalysisMode.ALL,
			new Analyzer() {
				@Override
				protected TokenStreamComponents createComponents(String fieldName) {
					final Tokenizer source = new StandardTokenizer();
					final TokenStream folded = new ASCIIFoldingFilter(new LowerCaseFilter(source));
					return new TokenStreamComponents(source, new HypothesisStemFilter(folded, stemmers));
				}

				@Override
				protected TokenStream normalize(String fieldName, TokenStream in) {
					return new ASCIIFoldingFilter(new LowerCaseFilter(in));
				}
			}
		);
	}

	/**
	 * Builds the language's branching M7 query chain — the identical minimal shape with
	 * {@link BranchingHypothesisStemFilter} over a per-stream {@link BranchingStemmer}.
	 *
	 * @param language language key
	 * @return the wrapped branching chain
	 */
	@Nonnull
	static FulltextAnalyzer buildBranching(@Nonnull String language) {
		final Supplier<BranchingStemmer> stemmer = switch (language) {
			case "SLOVAK" -> BranchingFoldedSlovakStemmer::new;
			case "POLISH" -> BranchingFoldedPolishStemmer::new;
			case "ROMANIAN" -> BranchingFoldedRomanianStemmer::new;
			default -> throw new IllegalStateException("Unknown language: " + language);
		};
		return new FulltextAnalyzer(
			language.toLowerCase(Locale.ROOT) + "-m7-branching", AnalysisMode.ALL,
			new Analyzer() {
				@Override
				protected TokenStreamComponents createComponents(String fieldName) {
					final Tokenizer source = new StandardTokenizer();
					final TokenStream folded = new ASCIIFoldingFilter(new LowerCaseFilter(source));
					return new TokenStreamComponents(
						source, new BranchingHypothesisStemFilter(folded, stemmer.get())
					);
				}

				@Override
				protected TokenStream normalize(String fieldName, TokenStream in) {
					return new ASCIIFoldingFilter(new LowerCaseFilter(in));
				}
			}
		);
	}

	/**
	 * The Slovak flat union — every switch combination, exactly the list the Slovak M7 chain and lexicon
	 * sweep run; Slovak alone has no {@code allHypotheses()} and no surface hypothesis.
	 *
	 * @return the eight configurations
	 */
	@Nonnull
	static List<FoldedSlovakStemmer> slovakUnion() {
		final List<FoldedSlovakStemmer> stemmers = new ArrayList<>(8);
		for (final boolean omEnding : new boolean[]{true, false}) {
			for (final boolean ieShortening : new boolean[]{true, false}) {
				for (final boolean epenthesis : new boolean[]{true, false}) {
					stemmers.add(new FoldedSlovakStemmer(omEnding, ieShortening, epenthesis));
				}
			}
		}
		return stemmers;
	}

	/**
	 * The language's two pipelines and its query text, built once per trial; stream components are cached per
	 * thread exactly as in production, so the ops measure steady-state per-call cost.
	 */
	@State(Scope.Benchmark)
	public static class PipelineState {

		/** Which language's chains the trial measures. */
		@Param({"SLOVAK", "POLISH", "ROMANIAN"})
		public String language;

		/** The flat configuration-union chain. */
		FulltextAnalyzer flat;
		/** The branching-walk chain. */
		FulltextAnalyzer branching;
		/** The 3-token bare-typed query every op analyzes. */
		String text;

		/**
		 * Builds both pipelines and resolves the trial's query.
		 */
		@Setup(Level.Trial)
		public void setUp() {
			this.flat = buildFlat(this.language);
			this.branching = buildBranching(this.language);
			this.text = switch (this.language) {
				case "SLOVAK" -> SLOVAK_QUERY;
				case "POLISH" -> POLISH_QUERY;
				case "ROMANIAN" -> ROMANIAN_QUERY;
				default -> throw new IllegalStateException("Unknown language: " + this.language);
			};
		}

		/**
		 * Releases both chains' per-thread stream components.
		 */
		@TearDown(Level.Trial)
		public void tearDown() {
			this.flat.close();
			this.branching.close();
		}

	}

	/**
	 * One flat-union analysis of the language's query — the cost the M7 prototype chain pays today.
	 *
	 * @param state     the pipelines and text
	 * @param blackhole consumes every emitted term
	 */
	@Benchmark
	public void flatHypothesisQuery(@Nonnull PipelineState state, @Nonnull Blackhole blackhole) {
		state.flat.analyze(
			state.text,
			(term, surfaceForm, startOffset, endOffset, positionIncrement) -> blackhole.consume(term)
		);
	}

	/**
	 * One branching analysis of the same query — the same term set, one walk per token.
	 *
	 * @param state     the pipelines and text
	 * @param blackhole consumes every emitted term
	 */
	@Benchmark
	public void branchingHypothesisQuery(@Nonnull PipelineState state, @Nonnull Blackhole blackhole) {
		state.branching.analyze(
			state.text,
			(term, surfaceForm, startOffset, endOffset, positionIncrement) -> blackhole.consume(term)
		);
	}

}
