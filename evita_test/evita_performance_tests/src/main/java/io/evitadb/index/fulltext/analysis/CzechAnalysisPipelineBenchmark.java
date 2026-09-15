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
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.StopFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.cz.CzechAnalyzer;
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
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Cost census of the M7 asymmetric fulltext proposal for Czech: what does the **full-hypothesis query chain**
 * — {@link HypothesisStemFilter} running every {@link FoldedCzechStemmer#allHypotheses()} configuration, 1,024
 * switch combinations plus the surface hypothesis — cost per analyzed query, against the **production Czech
 * chain** (`CzechAnalyzer` + diacritics folding) that both indexes and queries today and that would stay the
 * M7 *index* side unchanged?
 *
 * The number this exists to produce: the flat prototype filter is deliberately naive — per token it copies
 * the term buffer, runs the stem, and materializes a {@code String} once **per configuration** (1,025 times),
 * deduplicating through a {@code LinkedHashSet} whose result holds 1–4 distinct terms. The third pipeline is
 * the named remedy made real: {@link BranchingHypothesisStemFilter} over {@link BranchingFoldedCzechStemmer},
 * one walk per token that forks only where an ambiguous rule actually matches and allocates nothing in steady
 * state — provably set-equivalent to the flat union ({@code BranchingCzechStemmerEquivalenceTest} pins it over
 * the whole cs_CZ lexicon), so any measured difference is implementation cost alone. Whether the flat union's
 * simplicity is worth its price is a decision that wants bytes and microseconds, not intuition.
 *
 * Three measurements per pipeline, swept over a 3-token bare-typed query (the M7 target workload — queries
 * are analyzed once per search request) and a ~50-word accented product description (the index-side shape,
 * for scaling context):
 *
 * 1. **time** — average analysis time per call ({@code Mode.AverageTime});
 * 2. **allocation** — run with {@code -prof gc} and read {@code gc.alloc.rate.norm} (bytes per analyze call)
 *    plus {@code gc.count}/{@code gc.time} for collector pressure;
 * 3. **retained footprint** — the {@code main} below prints the JOL owned size of both analyzer object
 *    graphs, measured *unprimed* (see the comment in {@code main} for why): the configuration mass each
 *    chain retains for life, which for M7 is the 1,025 pre-built stemmer configurations — created once,
 *    not per query; the per-query churn is the scratch buffers and hypothesis strings. The emitted-term
 *    counts for both texts follow, so fan-out is visible next to the costs.
 *
 * Both pipelines are driven through {@link FulltextAnalyzer#analyze}, the exact production entry point,
 * so the NFC boundary normalization and the term-consumer path are included in every number.
 *
 * Run (footprint census first, then the JMH sweep; the add-opens are for JOL):
 * {@code java --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED
 * -cp evita_test/evita_performance_tests/target/benchmarks.jar
 * io.evitadb.index.fulltext.analysis.CzechAnalysisPipelineBenchmark -prof gc}.
 *
 * The M7 query chain construction mirrors {@code CzechAnalysisApproachMatrixTest#fullHypothesisQueryChain()}
 * (functional tests) — keep the two in step, the matrix row and this cost figure must describe one pipeline.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 4, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class CzechAnalysisPipelineBenchmark {

	/**
	 * The M7 target workload: a short bare-typed e-commerce query (3 tokens after tokenization), typed the
	 * way M7's whole reason to exist assumes — without diacritics.
	 */
	static final String QUERY_TEXT = "cerna kozena sedacka";

	/**
	 * The index-side shape for scaling context: a ~50-word accented Czech product description, including the
	 * stop words a real one carries.
	 */
	static final String DESCRIPTION_TEXT =
		"Luxusní rohová sedačka s pravým koženým potahem je vhodná do každého moderního obývacího pokoje. "
			+ "Konstrukce z masivního bukového dřeva a vysoce odolné studené pěny zaručuje dlouhou životnost "
			+ "i při každodenním používání. Sedací souprava nabízí prostorný úložný prostor na lůžkoviny, "
			+ "nastavitelné opěrky hlavy a rozkládací funkci pro pohodlné spaní dvou osob. Povrch čistěte "
			+ "měkkým vlhkým hadříkem bez agresivních čisticích prostředků.";

	/**
	 * Prints the JOL retained-footprint census of both pipelines plus their emitted-term counts, then hands
	 * over to the JMH runner for the time/allocation sweep.
	 *
	 * @param args JMH runner arguments (benchmark regex, {@code -prof gc}, ...)
	 * @throws Exception when the JMH runner fails
	 */
	public static void main(String[] args) throws Exception {
		final CharArraySet sharedStopSet = CzechAnalyzer.getDefaultStopSet();
		try (
			final FulltextAnalyzer baseline = buildBaseline();
			final FulltextAnalyzer m7Query = buildM7Query()
		) {
			// measure BEFORE the first analyze call: once a chain is used, Lucene's CloseableThreadLocal maps
			// the components under the Thread itself, and a graph walk from either analyzer then swallows the
			// whole thread-locals closure - both graphs collapse into one ~10 MB blob and the comparison is
			// meaningless. Unprimed, the walk sees exactly the configuration mass each chain retains for life
			// (for M7 the 1,025 pre-built stemmer configurations); the per-thread stream components created on
			// first use are excluded and are near-identical between the two chains anyway
			FootprintSpikeSupport.banner(
				"Czech analysis pipeline retained footprint (unprimed; shared stop set subtracted)"
			);
			FootprintSpikeSupport.observation(
				"production chain (CzechAnalyzer + fold)",
				FootprintSpikeSupport.ownedSize(baseline, sharedStopSet)
			);
			FootprintSpikeSupport.observation(
				"M7 flat query chain (1,025 stemmer configurations)",
				FootprintSpikeSupport.ownedSize(m7Query, sharedStopSet)
			);
			try (final FulltextAnalyzer m7Branching = buildM7BranchingQuery()) {
				FootprintSpikeSupport.observation(
					"M7 branching query chain (one branching stemmer)",
					FootprintSpikeSupport.ownedSize(m7Branching, sharedStopSet)
				);
				System.out.printf(
					"  emitted terms - QUERY: production %d, M7 flat %d, M7 branching %d | "
						+ "DESCRIPTION: production %d, M7 flat %d, M7 branching %d%n%n",
					baseline.getTerms(QUERY_TEXT).size(), m7Query.getTerms(QUERY_TEXT).size(),
					m7Branching.getTerms(QUERY_TEXT).size(),
					baseline.getTerms(DESCRIPTION_TEXT).size(), m7Query.getTerms(DESCRIPTION_TEXT).size(),
					m7Branching.getTerms(DESCRIPTION_TEXT).size()
				);
			}
		}
		org.openjdk.jmh.Main.main(args);
	}

	/**
	 * The production Czech pipeline — today's symmetric chain and the unchanged index side of an M7
	 * deployment, wrapped in the engine's {@link FulltextAnalyzer} holder exactly as
	 * {@code BuiltInAnalyzers} registers it.
	 *
	 * @return the wrapped production chain
	 */
	@Nonnull
	static FulltextAnalyzer buildBaseline() {
		return new FulltextAnalyzer(
			"czech-production", AnalysisMode.ALL, new DiacriticsFoldingAnalyzerWrapper(new CzechAnalyzer())
		);
	}

	/**
	 * The M7 query pipeline over the lexicon-verified full hypothesis set — fold first, then
	 * {@link HypothesisStemFilter} unioning all 1,025 {@link FoldedCzechStemmer} configurations. Mirrors
	 * {@code CzechAnalysisApproachMatrixTest#fullHypothesisQueryChain()}; the stop filter sits before the
	 * fold there and here alike, because `CzechAnalyzer`'s stop list is written with diacritics.
	 *
	 * @return the wrapped M7 query chain
	 */
	@Nonnull
	static FulltextAnalyzer buildM7Query() {
		final List<FoldedStemmer> stemmers = FoldedCzechStemmer.allHypotheses();
		return new FulltextAnalyzer(
			"czech-m7-query", AnalysisMode.ALL,
			new Analyzer() {
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
			}
		);
	}

	/**
	 * The M7 query pipeline over the same hypothesis set computed by the **branching** stemmer — identical
	 * chain up to the last filter, where {@link BranchingHypothesisStemFilter} replaces the flat union. The
	 * emitted term sets are identical by construction and by test ({@code BranchingCzechStemmerEquivalenceTest});
	 * only the way they are computed differs.
	 *
	 * @return the wrapped M7 branching query chain
	 */
	@Nonnull
	static FulltextAnalyzer buildM7BranchingQuery() {
		return new FulltextAnalyzer(
			"czech-m7-branching-query", AnalysisMode.ALL,
			new Analyzer() {
				@Override
				protected TokenStreamComponents createComponents(String fieldName) {
					final Tokenizer source = new StandardTokenizer();
					TokenStream stream = new StopFilter(
						new LowerCaseFilter(source), CzechAnalyzer.getDefaultStopSet()
					);
					stream = new ASCIIFoldingFilter(stream);
					stream = new BranchingHypothesisStemFilter(stream, new BranchingFoldedCzechStemmer());
					return new TokenStreamComponents(source, stream);
				}

				@Override
				protected TokenStream normalize(String fieldName, TokenStream in) {
					return new ASCIIFoldingFilter(new LowerCaseFilter(in));
				}
			}
		);
	}

	/**
	 * The three pipelines and the text being analyzed, built once per trial. The analyzers cache their stream
	 * components per thread, exactly as in production — so what the ops measure is the steady-state per-call
	 * cost, not chain construction.
	 */
	@State(Scope.Benchmark)
	public static class PipelineState {

		/** Which input the trial analyzes — the 3-token bare query or the ~50-word accented description. */
		@Param({"QUERY", "DESCRIPTION"})
		public String textKind;

		/** The production Czech chain. */
		FulltextAnalyzer baseline;
		/** The M7 flat full-hypothesis query chain. */
		FulltextAnalyzer m7Query;
		/** The M7 branching query chain — same hypothesis set, one walk per token. */
		FulltextAnalyzer m7Branching;
		/** The text every op analyzes. */
		String text;

		/**
		 * Builds the pipelines and resolves the trial's input text.
		 */
		@Setup(Level.Trial)
		public void setUp() {
			this.baseline = buildBaseline();
			this.m7Query = buildM7Query();
			this.m7Branching = buildM7BranchingQuery();
			this.text = switch (this.textKind) {
				case "QUERY" -> QUERY_TEXT;
				case "DESCRIPTION" -> DESCRIPTION_TEXT;
				default -> throw new IllegalStateException("Unknown text kind: " + this.textKind);
			};
		}

		/**
		 * Releases the chains' per-thread stream components.
		 */
		@TearDown(Level.Trial)
		public void tearDown() {
			this.baseline.close();
			this.m7Query.close();
			this.m7Branching.close();
		}

	}

	/**
	 * One production-chain analysis of the trial text — the cost every stored value pays at indexing and
	 * every query pays today, and the floor the M7 query chain is compared against.
	 *
	 * @param state     the pipelines and text
	 * @param blackhole consumes every emitted term
	 */
	@Benchmark
	public void baselineCzechAnalyzer(@Nonnull PipelineState state, @Nonnull Blackhole blackhole) {
		state.baseline.analyze(
			state.text,
			(term, surfaceForm, startOffset, endOffset, positionIncrement) -> blackhole.consume(term)
		);
	}

	/**
	 * One M7 full-hypothesis analysis of the trial text — the cost a query would pay under the M7 proposal
	 * with the current flat-union prototype filter.
	 *
	 * @param state     the pipelines and text
	 * @param blackhole consumes every emitted term
	 */
	@Benchmark
	public void m7FullHypothesisQuery(@Nonnull PipelineState state, @Nonnull Blackhole blackhole) {
		state.m7Query.analyze(
			state.text,
			(term, surfaceForm, startOffset, endOffset, positionIncrement) -> blackhole.consume(term)
		);
	}

	/**
	 * One M7 branching analysis of the trial text — the same hypothesis set as
	 * {@link #m7FullHypothesisQuery}, computed by one branching walk per token instead of 1,025 stemmer runs.
	 *
	 * @param state     the pipelines and text
	 * @param blackhole consumes every emitted term
	 */
	@Benchmark
	public void m7BranchingHypothesisQuery(@Nonnull PipelineState state, @Nonnull Blackhole blackhole) {
		state.m7Branching.analyze(
			state.text,
			(term, surfaceForm, startOffset, endOffset, positionIncrement) -> blackhole.consume(term)
		);
	}

}
