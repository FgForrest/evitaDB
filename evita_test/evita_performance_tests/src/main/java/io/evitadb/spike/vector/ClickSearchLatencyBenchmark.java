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

package io.evitadb.spike.vector;

import io.evitadb.spike.vector.ClickRecallScenario.Corpus;
import io.evitadb.spike.vector.ClickRecallScenario.ExactScorer;
import io.evitadb.spike.vector.ClickRecallScenario.Filter;
import io.evitadb.spike.vector.ClickRecallScenario.IoFunction;
import io.evitadb.spike.vector.ClickRecallScenario.WalkStats;
import io.evitadb.spike.vector.EvitaGraphSearcher.NodeScorer;
import io.evitadb.spike.vector.JVectorFootprintScenarios.MappedRavv;
import io.github.jbellis.jvector.graph.ImmutableGraphIndex;
import io.github.jbellis.jvector.quantization.CompressedVectors;
import io.github.jbellis.jvector.vector.VectorizationProvider;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.hnsw.OnHeapHnswGraph;
import org.apache.lucene.util.hnsw.RandomVectorScorer;
import org.apache.lucene.util.quantization.QuantizedByteVectorValues.ScalarEncoding;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Latency of the configurations of {@link ClickRecallScenario}, measured with JMH as spec §4.5 requires: the recall
 * harness times its queries with `System.nanoTime` in one JVM, this benchmark forks a JVM per configuration, warms
 * it up and samples every query. Recall is not computed here; it stays with the recall harness.
 *
 * One invocation is one production search query, taken round-robin from the embedded query set: the walk plus the
 * exact rerank of its first `4 × 10` candidates (the oversampling factor parts 5 and 9 report latency at), or the
 * exact scan for the `exact` configurations. Graphs, codes and the filter are the ones of the recall harness: the
 * Lucene graph with `M = 16`, beam 100 and seed 42 over the mapped raw block, the OSQ codes from the code files
 * the recall harness wrote into the work directory, and the jVector graph of {@link JVectorFootprintScenarios}
 * with PQ 256 × 8 and BQ codes. The `jvgraph-osq1|4` configurations are the library combination of measurement
 * 8: the same jVector graph walked by {@link EvitaGraphSearcher}, scored by the OSQ codes of the code files. The
 * filter is the collection of the clicked records.
 *
 * The configuration parameter names the code and the strategy, `<code>:<strategy>`; strategies are those of
 * {@link ClickRecallScenario}. The work directory is a {@link SpikeDataset}, whose descriptor names the filter
 * collection; it carries no default and is passed one dataset per run, because JMH crosses every parameter value:
 *
 * ```
 * java -cp <cp> io.evitadb.spike.vector.ClickSearchLatencyBenchmark ClickSearchLatencyBenchmark \
 *   -p workDir=<workDir> -rf json -rff <result.json>
 * ```
 *
 * The class-name argument is required: without it JMH runs every benchmark of the module.
 *
 * Every fork refuses to run unless jVector loaded its Panama provider; Lucene's provider is internal and announces
 * itself in the fork's log ("Java vector incubator API enabled"), which has to be checked before a number is used.
 *
 * @author Lukáš Hornych (hornych@fg.cz)
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 4)
@Threads(1)
@Fork(
	value = 1,
	jvmArgsAppend = {
		"-Xmx3g",
		"--add-modules", "jdk.incubator.vector",
		"--enable-native-access=ALL-UNNAMED",
		"-Djvector.vectorization_provider=panama",
		"-Djvector.management.backend=none"
	}
)
public class ClickSearchLatencyBenchmark {
	/** Oversampling factor the walk's candidates are reranked at. */
	private static final int FACTOR = 4;

	/**
	 * Runs the benchmark from the command line.
	 *
	 * @param args JMH arguments
	 * @throws Exception when JMH fails
	 */
	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}

	/**
	 * One configuration: the corpus, the index and the search under test.
	 */
	@State(Scope.Benchmark)
	public static class Configuration {
		/** The dataset: a work directory with its `dataset.properties`, which names the filter collection. */
		@Param({})
		public String workDir;

		/** The code and the strategy, `<code>:<strategy>`. */
		@Param({
			"exact:all", "exact:filtered",
			"lucene-float32:all", "lucene-float32:post-filter", "lucene-float32:in-walk", "lucene-float32:acorn",
			"lucene-float32:planner",
			"lucene-osq1:all", "lucene-osq1:post-filter", "lucene-osq1:in-walk", "lucene-osq1:acorn",
			"lucene-osq1:planner",
			"lucene-osq4:all", "lucene-osq4:post-filter", "lucene-osq4:in-walk", "lucene-osq4:acorn",
			"lucene-osq4:planner",
			"lucene-osq7:all", "lucene-osq7:post-filter", "lucene-osq7:in-walk", "lucene-osq7:acorn",
			"lucene-osq7:planner",
			"jvector-pq:all", "jvector-pq:post-filter", "jvector-pq:in-walk",
			"jvector-bq:all", "jvector-bq:post-filter", "jvector-bq:in-walk",
			"jvgraph-osq1:all", "jvgraph-osq1:post-filter", "jvgraph-osq1:in-walk", "jvgraph-osq1:planner",
			"jvgraph-osq4:all", "jvgraph-osq4:post-filter", "jvgraph-osq4:in-walk", "jvgraph-osq4:planner"
		})
		public String configuration;

		/** The embedded production queries. */
		float[][] queries;
		/** The search of one query. */
		Search search;
		/** Position of the next query. */
		int next;

		/**
		 * Loads the corpus and builds the index of the configuration.
		 *
		 * @throws IOException when an input cannot be read
		 */
		@Setup
		public void setUp() throws IOException {
			final String provider = VectorizationProvider.getInstance().getClass().getSimpleName();
			if (!"PanamaVectorizationProvider".equals(provider)) {
				throw new IllegalStateException("jVector runs on " + provider + ", not on its Panama provider.");
			}
			final SpikeDataset dataset = SpikeDataset.open(Path.of(this.workDir));
			final Path dir = dataset.workDir();
			final int dimension = dataset.dimension();
			final MappedVectors base = dataset.base();
			final Corpus corpus = Corpus.load(dir.resolve(VectorExportExtractor.TEXT_ORDINALS_FILE), base);
			// a dataset without a filter collection runs the `all` configurations only
			final Filter filter = dataset.collection() == null ? null : Filter.of(corpus, dataset.collection());
			final ExactScorer exact = new ExactScorer(base);
			this.queries = ClickRecallScenario.loadQueries(dir, dimension);
			this.search = createSearch(this.configuration, dir, base, filter, exact);
			System.out.println(
				"JVM " + Runtime.version() + ", jVector " + provider + ", corpus " + base.count() + " x " + dimension
					+ ", filter " + (filter == null ? "none" : filter.count()) + ", queries " + this.queries.length
			);
		}
	}

	/**
	 * Searches the next query of the set.
	 *
	 * @param configuration the configuration under test
	 * @return the ordinals returned, consumed by JMH
	 * @throws IOException when a scorer fails
	 */
	@Benchmark
	public int[] search(@Nonnull Configuration configuration) throws IOException {
		final float[] query = configuration.queries[configuration.next];
		configuration.next = (configuration.next + 1) % configuration.queries.length;
		return configuration.search.apply(query);
	}

	/**
	 * Builds the search of one configuration.
	 *
	 * @param configuration `<code>:<strategy>`
	 * @param workDir       the work directory holding the code files
	 * @param base          the mapped corpus
	 * @param filter        the collection filter
	 * @param exact         the exact scorer of the rerank and the exact scan
	 * @return the search
	 * @throws IOException when a code file cannot be read or written
	 */
	@Nonnull
	private static Search createSearch(
		@Nonnull String configuration,
		@Nonnull Path workDir,
		@Nonnull MappedVectors base,
		@Nullable Filter filter,
		@Nonnull ExactScorer exact
	) throws IOException {
		final int separator = configuration.indexOf(':');
		if (separator < 0) {
			throw new IllegalArgumentException("Configuration `" + configuration + "` is not `<code>:<strategy>`.");
		}
		final String code = configuration.substring(0, separator);
		final String strategy = configuration.substring(separator + 1);
		if (filter == null && !"all".equals(strategy)) {
			throw new IllegalArgumentException(
				"Configuration `" + configuration + "` needs a filter; the dataset declares no `collection`, pass "
					+ "only the `all` configurations with `-p configuration=...`."
			);
		}
		if ("exact".equals(code)) {
			return switch (strategy) {
				case "all" -> q -> exact.exactScan(q, null, ClickRecallScenario.EXACT_DEPTH);
				case "filtered" -> q -> exact.exactScan(q, filter, ClickRecallScenario.EXACT_DEPTH);
				default -> throw new IllegalArgumentException("Unknown exact strategy `" + strategy + "`.");
			};
		} else if (code.startsWith("lucene-")) {
			return luceneSearch(code, strategy, workDir, base, filter, exact);
		} else if (code.startsWith("jvector-")) {
			return jvectorSearch(code, strategy, base, filter, exact);
		} else if (code.startsWith("jvgraph-")) {
			return combinedSearch(code, strategy, workDir, base, filter, exact);
		} else {
			throw new IllegalArgumentException("Unknown code `" + code + "`.");
		}
	}

	/**
	 * Builds a Lucene search: the graph over the raw block, walked through one code.
	 *
	 * @param code     `lucene-float32` or `lucene-osq1|4|7`
	 * @param strategy `all`, `post-filter`, `in-walk`, `acorn` or `planner`
	 * @param workDir  the work directory holding the code files
	 * @param base     the mapped corpus
	 * @param filter   the collection filter, null when the dataset declares none (`all` only)
	 * @param exact    the exact scorer
	 * @return the search
	 * @throws IOException when a code file cannot be read or written
	 */
	@Nonnull
	private static Search luceneSearch(
		@Nonnull String code,
		@Nonnull String strategy,
		@Nonnull Path workDir,
		@Nonnull MappedVectors base,
		@Nullable Filter filter,
		@Nonnull ExactScorer exact
	) throws IOException {
		final IoFunction<float[], RandomVectorScorer> scorers = switch (code) {
			case "lucene-float32" -> SpikeIndexes.luceneFloat32(base);
			case "lucene-osq1" -> osqScorers(workDir, base, ScalarEncoding.SINGLE_BIT_QUERY_NIBBLE);
			case "lucene-osq4" -> osqScorers(workDir, base, ScalarEncoding.PACKED_NIBBLE);
			case "lucene-osq7" -> osqScorers(workDir, base, ScalarEncoding.SEVEN_BIT);
			default -> throw new IllegalArgumentException("Unknown Lucene code `" + code + "`.");
		};
		final OnHeapHnswGraph graph = SpikeIndexes.luceneGraph(base);
		final Bits accept = filter == null ? null : ClickRecallScenario.luceneBits(filter);
		final int depth = FACTOR * ClickRecallScenario.TOP_K;
		final WalkStats stats = new WalkStats();
		return switch (strategy) {
			case "all" -> q -> exact.rerank(q, walked(ClickRecallScenario.luceneWalk(
				scorers.apply(q), graph, null, 0, Integer.MAX_VALUE, 0, stats)), depth);
			case "post-filter" -> q -> exact.rerank(q, ClickRecallScenario.postFilter(ClickRecallScenario.luceneWalk(
				scorers.apply(q), graph, null, filter.count(), Integer.MAX_VALUE, 0, stats), filter, stats), depth);
			case "in-walk" -> q -> exact.rerank(q, walked(ClickRecallScenario.luceneWalk(
				scorers.apply(q), graph, accept, filter.count(), Integer.MAX_VALUE, 0, stats)), depth);
			case "acorn" -> q -> exact.rerank(q, walked(ClickRecallScenario.luceneWalk(
				scorers.apply(q), graph, accept, filter.count(), Integer.MAX_VALUE, 100, stats)), depth);
			case "planner" -> q -> exact.rerank(
				q, ClickRecallScenario.plannedLuceneWalk(scorers, graph, accept, filter, exact, q, stats), depth
			);
			default -> throw new IllegalArgumentException("Unknown Lucene strategy `" + strategy + "`.");
		};
	}

	/**
	 * @param workDir  the work directory holding the code files
	 * @param base     the mapped corpus
	 * @param encoding the OSQ encoding
	 * @return for a query, the scorer of the code from the recall harness's code file
	 * @throws IOException when the code file cannot be read or written
	 */
	@Nonnull
	private static IoFunction<float[], RandomVectorScorer> osqScorers(
		@Nonnull Path workDir,
		@Nonnull MappedVectors base,
		@Nonnull ScalarEncoding encoding
	) throws IOException {
		return SpikeIndexes.luceneOsq(SpikeIndexes.osqCodes(workDir, base, encoding));
	}

	/**
	 * Builds a jVector search: its graph over the raw block, walked through PQ or BQ codes.
	 *
	 * @param code     `jvector-pq` or `jvector-bq`
	 * @param strategy `all`, `post-filter` or `in-walk`, the only modes jVector's searcher has
	 * @param base     the mapped corpus
	 * @param filter   the collection filter, null when the dataset declares none (`all` only)
	 * @param exact    the exact scorer
	 * @return the search
	 * @throws IOException when the graph builder cannot be closed
	 */
	@Nonnull
	private static Search jvectorSearch(
		@Nonnull String code,
		@Nonnull String strategy,
		@Nonnull MappedVectors base,
		@Nullable Filter filter,
		@Nonnull ExactScorer exact
	) throws IOException {
		final MappedRavv ravv = new MappedRavv(base);
		final ImmutableGraphIndex graph = SpikeIndexes.jvectorGraph(ravv, SpikeIndexes.DEFAULT_ALPHA, true);
		final CompressedVectors codes = switch (code) {
			case "jvector-pq" -> SpikeIndexes.pq(ravv);
			case "jvector-bq" -> SpikeIndexes.bq(ravv);
			default -> throw new IllegalArgumentException("Unknown jVector code `" + code + "`.");
		};
		final io.github.jbellis.jvector.util.Bits accept = filter == null ? null : filter::contains;
		final io.github.jbellis.jvector.util.Bits all = io.github.jbellis.jvector.util.Bits.ALL;
		final int depth = FACTOR * ClickRecallScenario.TOP_K;
		final WalkStats stats = new WalkStats();
		return switch (strategy) {
			case "all" -> q -> exact.rerank(q, ClickRecallScenario.jvectorWalk(graph, codes, q, all, stats), depth);
			case "post-filter" -> q -> exact.rerank(q, ClickRecallScenario.postFilter(
				ClickRecallScenario.jvectorWalk(graph, codes, q, all, stats), filter, stats), depth);
			case "in-walk" -> q -> exact.rerank(
				q, ClickRecallScenario.jvectorWalk(graph, codes, q, accept, stats), depth
			);
			default -> throw new IllegalArgumentException("Unknown jVector strategy `" + strategy + "`.");
		};
	}

	/**
	 * Builds a search of the library combination of measurement 8: the jVector graph walked by our searcher, its
	 * walk scored by Lucene's OSQ codes from our code file, the candidates reranked exactly.
	 *
	 * @param code     `jvgraph-osq1` or `jvgraph-osq4`
	 * @param strategy `all`, `post-filter`, `in-walk` or `planner`
	 * @param workDir  the work directory holding the code files
	 * @param base     the mapped corpus
	 * @param filter   the collection filter, null when the dataset declares none (`all` only)
	 * @param exact    the exact scorer
	 * @return the search
	 * @throws IOException when a code file cannot be read or the graph builder cannot be closed
	 */
	@Nonnull
	private static Search combinedSearch(
		@Nonnull String code,
		@Nonnull String strategy,
		@Nonnull Path workDir,
		@Nonnull MappedVectors base,
		@Nullable Filter filter,
		@Nonnull ExactScorer exact
	) throws IOException {
		final IoFunction<float[], RandomVectorScorer> scorers = switch (code) {
			case "jvgraph-osq1" -> osqScorers(workDir, base, ScalarEncoding.SINGLE_BIT_QUERY_NIBBLE);
			case "jvgraph-osq4" -> osqScorers(workDir, base, ScalarEncoding.PACKED_NIBBLE);
			default -> throw new IllegalArgumentException("Unknown combined code `" + code + "`.");
		};
		final ImmutableGraphIndex graph = SpikeIndexes.jvectorGraph(
			new MappedRavv(base), SpikeIndexes.DEFAULT_ALPHA, true
		);
		// the graph is complete and never changed afterwards, so one view serves every query
		final GraphView view = GraphView.ofJVector(graph, graph.getView());
		final EvitaGraphSearcher searcher = new EvitaGraphSearcher();
		final Acceptor accept = filter == null
			? null : Acceptor.of(filter, Acceptor.Representation.PRIMARY_KEY_BITMAP);
		final int depth = FACTOR * ClickRecallScenario.TOP_K;
		final WalkStats stats = new WalkStats();
		return switch (strategy) {
			case "all" -> q -> exact.rerank(q, ownWalk(searcher, view, scorers.apply(q), null), depth);
			case "post-filter" -> q -> exact.rerank(q, ClickRecallScenario.postFilter(
				ownWalk(searcher, view, scorers.apply(q), null), filter, stats), depth);
			case "in-walk" -> q -> exact.rerank(q, ownWalk(searcher, view, scorers.apply(q), accept), depth);
			case "planner" -> q -> exact.rerank(q, GraphSeamScenarios.plannedOwnWalk(searcher, view,
				NodeScorer.ofLucene(scorers.apply(q)), accept, exact, q, filter, stats), depth);
			default -> throw new IllegalArgumentException("Unknown combined strategy `" + strategy + "`.");
		};
	}

	/**
	 * One walk of our searcher without a visit budget.
	 *
	 * @param searcher our searcher
	 * @param view     the graph
	 * @param scorer   the query's scorer over the codes
	 * @param accept   the filter, null for none
	 * @return candidate ordinals, best first
	 */
	@Nonnull
	private static int[] ownWalk(
		@Nonnull EvitaGraphSearcher searcher,
		@Nonnull GraphView view,
		@Nonnull RandomVectorScorer scorer,
		@Nullable Acceptor accept
	) {
		return walked(searcher.search(view, NodeScorer.ofLucene(scorer), accept, ClickRecallScenario.CANDIDATES,
			false, Integer.MAX_VALUE, EvitaGraphSearcher.NEVER_CANCELLED));
	}

	/**
	 * @param candidates the candidates of an unlimited walk
	 * @return the same candidates
	 * @throws IllegalStateException when the walk stopped at a visit limit it was not given
	 */
	@Nonnull
	private static int[] walked(@Nullable int[] candidates) {
		if (candidates == null) {
			throw new IllegalStateException("An unlimited walk cannot stop at its visit limit.");
		}
		return candidates;
	}

	/**
	 * The search of one query: the ordinals it returns, best first.
	 */
	@FunctionalInterface
	interface Search {
		/**
		 * @param query the query vector
		 * @return the returned ordinals, best first
		 * @throws IOException when a scorer fails
		 */
		@Nonnull
		int[] apply(@Nonnull float[] query) throws IOException;
	}
}
