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
import io.github.jbellis.jvector.graph.similarity.ScoreFunction;
import org.apache.lucene.util.VectorUtil;
import org.apache.lucene.util.hnsw.OnHeapHnswGraph;
import org.apache.lucene.util.hnsw.RandomVectorScorer;
import org.apache.lucene.util.quantization.QuantizedByteVectorValues.ScalarEncoding;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.function.Function;

import static io.evitadb.spike.vector.VectorMemoryProbe.line;
import static io.evitadb.spike.vector.VectorMemoryProbe.section;

/**
 * The filtered matrix of spec §4.4 on a real corpus with real production queries: every strategy of spec §3.4 over
 * the Lucene graph (Lucene's searchers) and the jVector graph ({@link EvitaGraphSearcher}), at fixed selectivities,
 * for three predicate shapes:
 *
 * - `random`: a seeded random subset of the ordinals, members spread evenly over the graph (an "in stock" flag);
 * - `correlated`: the `s·N` nearest neighbours of a seed vector, members gathered in one region of the embedding
 *   space (a category or a brand); ten seeds per cell, query `q` filtered by seed `q mod 10`;
 * - `excluded`: everything except the `(1 - s)·N` nearest neighbours of a seed vector, one region cut out (a
 *   segment of the assortment that is hidden); only at the dense selectivities, ten seeds as above.
 *
 * Per cell: recall@10 at 1× and 4× against the exact scan over the same filter (ordinals, ties at the tenth score
 * counted as hits; with fewer than ten members, against all of them), mean distinct nodes scored, starved queries
 * (fewer than ten results after the post-filter), planner fallbacks, and the latency of one timed pass after one
 * untimed pass (walk plus rerank, not JMH).
 *
 * A filter is what evitaDB would hand over: a bitmap of the members' primary keys, whole records, reached through
 * the ordinal map (spec §2.4): a member selected by one of its vectors brings all its vectors. Every strategy reads
 * it that way, and the exact scan iterates it. At 50 % the in-walk walk also runs over three materializations over
 * ordinals (`boolean[]`, flat bitset, `RoaringBitmap`) to show what the membership test costs.
 *
 * @author Lukáš Hornych (hornych@fg.cz)
 */
public final class FilteredMatrixScenario {
	/** Number of records the user sees. */
	private static final int TOP_K = ClickRecallScenario.TOP_K;
	/** Depth of the approximate walk. */
	private static final int CANDIDATES = ClickRecallScenario.CANDIDATES;
	/** Selectivities, the share of the corpus that qualifies. */
	private static final double[] SELECTIVITIES = {1.0, 0.95, 0.90, 0.85, 0.50, 0.10, 0.05, 0.01, 0.001, 0.0001};
	/** Number of seed vectors of the correlated shapes. */
	private static final int SEEDS = 10;
	/** The most queries a cell runs. */
	private static final int MAX_QUERIES = 500;

	private FilteredMatrixScenario() {
		throw new UnsupportedOperationException("Utility class, not to be instantiated.");
	}

	/**
	 * Runs the matrix.
	 *
	 * @param base    mapped corpus vectors
	 * @param workDir directory holding the click queries and the code files
	 * @throws IOException when an input cannot be read
	 */
	public static void run(@Nonnull MappedVectors base, @Nonnull Path workDir) throws IOException {
		section("Filtered matrix: selectivity x predicate shape x strategy");
		final float[][] allQueries = ClickRecallScenario.loadQueries(workDir, base.dimension());
		final float[][] queries = Arrays.copyOf(allQueries, Math.min(MAX_QUERIES, allQueries.length));
		final ExactScorer exact = new ExactScorer(base);
		final int n = base.count();
		final Corpus corpus = Corpus.load(workDir.resolve(VectorExportExtractor.TEXT_ORDINALS_FILE), base);
		final PrimaryKeyOrdinals ordinals = PrimaryKeyOrdinals.of(corpus.record(), corpus.recordCount());
		line("corpus " + n + " vectors / " + corpus.recordCount() + " records, queries " + queries.length
			+ ", walk depth " + CANDIDATES + "; filters are bitmaps of primary keys, whole records");

		// the graphs and codes, built once
		final OnHeapHnswGraph luceneGraph = SpikeIndexes.luceneGraph(base);
		final MappedRavv ravv = new MappedRavv(base);
		final ImmutableGraphIndex jvectorGraph = SpikeIndexes.jvectorGraph(ravv, SpikeIndexes.DEFAULT_ALPHA, true);
		final GraphView jvectorView = GraphView.ofJVector(jvectorGraph, jvectorGraph.getView());
		final Function<float[], ScoreFunction> float32 = SpikeIndexes.jvectorFloat32(ravv);
		final Function<float[], ScoreFunction> pq = SpikeIndexes.jvectorCodes(SpikeIndexes.pq(ravv));
		final EvitaGraphSearcher searcher = new EvitaGraphSearcher();

		final Configuration[] configurations = {
			Configuration.lucene("lucene float32", luceneGraph, SpikeIndexes.luceneFloat32(base)),
			Configuration.lucene("lucene osq 4-bit", luceneGraph,
				SpikeIndexes.luceneOsq(SpikeIndexes.osqCodes(workDir, base, ScalarEncoding.PACKED_NIBBLE))),
			Configuration.own("jvector float32", jvectorView, searcher, q -> NodeScorer.ofJVector(float32.apply(q))),
			Configuration.own("jvector pq 256", jvectorView, searcher, q -> NodeScorer.ofJVector(pq.apply(q)))
		};

		final int[] shuffled = shuffledOrdinals(n, new Random(42L));
		final int[][] seedOrders = seedOrders(base, exact, new Random(43L));
		line("shape | selectivity | members | configuration | strategy | recall@10 1x / 4x | visited | starved | "
			+ "fallbacks | p50 / p95 ms");
		for (final double selectivity : SELECTIVITIES) {
			final int members = Math.max(1, (int) Math.round(selectivity * n));
			runCell("random", selectivity, new Filter[]{prefix(shuffled, members, ordinals)}, queries, exact,
				configurations);
			if (selectivity < 1.0) {
				final Filter[] correlated = new Filter[SEEDS];
				for (int s = 0; s < SEEDS; s++) {
					correlated[s] = prefix(seedOrders[s], members, ordinals);
				}
				runCell("correlated", selectivity, correlated, queries, exact, configurations);
			}
			if (selectivity < 1.0 && selectivity >= 0.5) {
				final Filter[] excluded = new Filter[SEEDS];
				for (int s = 0; s < SEEDS; s++) {
					excluded[s] = complement(prefix(seedOrders[s], n - members, ordinals));
				}
				runCell("excluded", selectivity, excluded, queries, exact, configurations);
			}
		}
	}

	/**
	 * Runs every configuration and strategy of one cell.
	 *
	 * @param shape          predicate shape
	 * @param selectivity    nominal selectivity
	 * @param filters        the filters; query `q` uses `filters[q % filters.length]`
	 * @param queries        the queries
	 * @param exact          the exact scorer
	 * @param configurations graphs and codes
	 */
	private static void runCell(
		@Nonnull String shape,
		double selectivity,
		@Nonnull Filter[] filters,
		@Nonnull float[][] queries,
		@Nonnull ExactScorer exact,
		@Nonnull Configuration[] configurations
	) {
		final Cell cell = new Cell(shape, selectivity, filters, queries, exact);
		cell.report("exact", "scan", (q, filter, acceptor, stats) -> exact.exactScan(q, filter, CANDIDATES));
		for (final Configuration configuration : configurations) {
			cell.report(configuration.label, "post-filter", (q, filter, acceptor, stats) ->
				ClickRecallScenario.postFilter(configuration.walk(q, null, false, stats), filter, stats));
			cell.report(configuration.label, "in-walk", (q, filter, acceptor, stats) ->
				configuration.walk(q, acceptor, false, stats));
			cell.report(configuration.label, "acorn", (q, filter, acceptor, stats) ->
				configuration.walk(q, acceptor, true, stats));
			cell.report(configuration.label, "planner", (q, filter, acceptor, stats) ->
				configuration.planned(q, filter, acceptor, exact, stats));
			if (selectivity == 0.5 && configuration.label.endsWith("float32")) {
				for (final Acceptor.Representation representation : new Acceptor.Representation[]{
					Acceptor.Representation.BOOLEAN_ARRAY, Acceptor.Representation.FLAT_BITSET,
					Acceptor.Representation.ROARING
				}) {
					final Acceptor[] acceptors = new Acceptor[filters.length];
					for (int f = 0; f < filters.length; f++) {
						acceptors[f] = Acceptor.of(filters[f].accept(), representation);
					}
					cell.report(configuration.label, "in-walk, " + representation, (q, filter, acceptor, stats) ->
						configuration.walk(q, acceptors[cell.filterIndex], false, stats));
				}
			}
		}
	}

	/**
	 * @param n      corpus size
	 * @param random the seeded generator
	 * @return the ordinals in a random order
	 */
	@Nonnull
	private static int[] shuffledOrdinals(int n, @Nonnull Random random) {
		final int[] ordinals = new int[n];
		for (int i = 0; i < n; i++) {
			ordinals[i] = i;
		}
		for (int i = n - 1; i > 0; i--) {
			final int j = random.nextInt(i + 1);
			final int t = ordinals[i];
			ordinals[i] = ordinals[j];
			ordinals[j] = t;
		}
		return ordinals;
	}

	/**
	 * @param base   the corpus
	 * @param exact  the exact scorer
	 * @param random the seeded generator picking the seed vectors
	 * @return per seed vector, every ordinal ordered by similarity to it, nearest first
	 */
	@Nonnull
	private static int[][] seedOrders(@Nonnull MappedVectors base, @Nonnull ExactScorer exact, @Nonnull Random random) {
		final int n = base.count();
		final int[][] orders = new int[SEEDS][];
		final float[] seed = new float[base.dimension()];
		final float[] other = new float[base.dimension()];
		for (int s = 0; s < SEEDS; s++) {
			base.read(random.nextInt(n), seed);
			final float[] scores = new float[n];
			final Integer[] ordinals = new Integer[n];
			for (int i = 0; i < n; i++) {
				base.read(i, other);
				scores[i] = VectorUtil.dotProduct(seed, other);
				ordinals[i] = i;
			}
			Arrays.sort(ordinals, (a, b) -> Float.compare(scores[b], scores[a]));
			orders[s] = new int[n];
			for (int i = 0; i < n; i++) {
				orders[s][i] = ordinals[i];
			}
		}
		return orders;
	}

	/**
	 * @param order    ordinals in some order
	 * @param members  how many of the first ordinals select the filter's records
	 * @param ordinals the map between ordinals and primary keys
	 * @return the filter of the records of the first `members` ordinals, with all their vectors
	 */
	@Nonnull
	private static Filter prefix(@Nonnull int[] order, int members, @Nonnull PrimaryKeyOrdinals ordinals) {
		final boolean[] accept = new boolean[ordinals.ordinalCount()];
		for (int i = 0; i < members; i++) {
			accept[order[i]] = true;
		}
		return Filter.of(accept, ordinals);
	}

	/**
	 * @param filter a filter
	 * @return the filter of every record the given one rejects
	 */
	@Nonnull
	private static Filter complement(@Nonnull Filter filter) {
		final boolean[] accept = new boolean[filter.accept().length];
		for (int i = 0; i < accept.length; i++) {
			accept[i] = !filter.accept()[i];
		}
		return Filter.of(accept, filter.ordinals());
	}

	/**
	 * A filtered search of one query.
	 */
	@FunctionalInterface
	private interface FilteredWalk {
		/**
		 * @param query    the query vector
		 * @param filter   the query's filter
		 * @param acceptor the same filter as a `boolean[]` acceptor
		 * @param stats    receives the counters
		 * @return candidate ordinals, best first
		 * @throws IOException when a scorer fails
		 */
		@Nonnull
		int[] search(@Nonnull float[] query, @Nonnull Filter filter, @Nonnull Acceptor acceptor,
			@Nonnull WalkStats stats) throws IOException;
	}

	/**
	 * One cell of the matrix: its filters, the exact reference of every query, and the evaluation of a strategy.
	 */
	private static final class Cell {
		/** Predicate shape. */
		private final String shape;
		/** Nominal selectivity. */
		private final double selectivity;
		/** The filters. */
		private final Filter[] filters;
		/** The filters as `boolean[]` acceptors. */
		private final Acceptor[] acceptors;
		/** The queries. */
		private final float[][] queries;
		/** The exact scorer. */
		private final ExactScorer exact;
		/** Per query, the exact score of the last of the true top ten within its filter. */
		private final float[] kth;
		/** Per query, how many true results there are (ten, or fewer members). */
		private final int[] expected;
		/** Index of the filter of the query being searched. */
		int filterIndex;

		/**
		 * @param shape       predicate shape
		 * @param selectivity nominal selectivity
		 * @param filters     the filters
		 * @param queries     the queries
		 * @param exact       the exact scorer
		 */
		Cell(
			@Nonnull String shape,
			double selectivity,
			@Nonnull Filter[] filters,
			@Nonnull float[][] queries,
			@Nonnull ExactScorer exact
		) {
			this.shape = shape;
			this.selectivity = selectivity;
			this.filters = filters;
			this.queries = queries;
			this.exact = exact;
			this.acceptors = new Acceptor[filters.length];
			for (int f = 0; f < filters.length; f++) {
				this.acceptors[f] = Acceptor.of(filters[f], Acceptor.Representation.PRIMARY_KEY_BITMAP);
			}
			this.kth = new float[queries.length];
			this.expected = new int[queries.length];
			for (int q = 0; q < queries.length; q++) {
				final int[] truth = exact.exactScan(queries[q], filters[q % filters.length], TOP_K);
				this.expected[q] = truth.length;
				this.kth[q] = exact.score(queries[q], truth[truth.length - 1]);
			}
		}

		/**
		 * Runs a strategy over the queries and prints its row.
		 *
		 * @param label    configuration name
		 * @param strategy strategy name
		 * @param walk     the search
		 */
		void report(@Nonnull String label, @Nonnull String strategy, @Nonnull FilteredWalk walk) {
			try {
				final WalkStats stats = new WalkStats();
				final int[][] candidates = new int[this.queries.length][];
				for (int q = 0; q < this.queries.length; q++) {
					this.filterIndex = q % this.filters.length;
					candidates[q] = walk.search(this.queries[q], this.filters[this.filterIndex],
						this.acceptors[this.filterIndex], stats);
				}
				final long[] latencies = new long[this.queries.length];
				final WalkStats ignored = new WalkStats();
				for (int q = 0; q < this.queries.length; q++) {
					this.filterIndex = q % this.filters.length;
					final long start = System.nanoTime();
					final int[] walked = walk.search(this.queries[q], this.filters[this.filterIndex],
						this.acceptors[this.filterIndex], ignored);
					this.exact.rerank(this.queries[q], walked, 4 * TOP_K);
					latencies[q] = System.nanoTime() - start;
				}
				Arrays.sort(latencies);
				final int members = this.filters[0].count();
				line(String.format(
					"%s | %s %% | %d | %s | %s | %.3f / %.3f | %.0f | %d | %d | %.2f / %.2f",
					this.shape, percent(this.selectivity), members, label, strategy, recall(candidates, 1),
					recall(candidates, 4), stats.visited / (double) this.queries.length, stats.starved,
					stats.fallbacks, latencies[latencies.length / 2] / 1e6,
					latencies[(int) (latencies.length * 0.95)] / 1e6
				));
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
		}

		/**
		 * @param candidates per query, candidate ordinals in approximate order
		 * @param factor     oversampling factor of the rerank
		 * @return mean recall@10 after reranking `factor × 10` candidates
		 */
		private double recall(@Nonnull int[][] candidates, int factor) {
			double sum = 0;
			for (int q = 0; q < this.queries.length; q++) {
				final int[] ranked = this.exact.rerank(this.queries[q], candidates[q], factor * TOP_K);
				int hits = 0;
				for (int i = 0; i < Math.min(TOP_K, ranked.length); i++) {
					if (this.filters[q % this.filters.length].accept()[ranked[i]]
						&& this.exact.score(this.queries[q], ranked[i]) >= this.kth[q] - 1e-5f) {
						hits++;
					}
				}
				sum += Math.min(1.0, hits / (double) this.expected[q]);
			}
			return sum / this.queries.length;
		}

		/**
		 * @param selectivity a share
		 * @return the share as a percentage without needless decimals
		 */
		@Nonnull
		private static String percent(double selectivity) {
			final double p = selectivity * 100;
			return p >= 1 ? String.format("%.0f", p) : String.format("%.2f", p);
		}
	}

	/**
	 * A graph with the code driving its walk, searched by its library's searcher or ours.
	 */
	private static final class Configuration {
		/** Configuration name. */
		private final String label;
		/** The Lucene graph, null for a jVector configuration. */
		private final OnHeapHnswGraph luceneGraph;
		/** For a query, the Lucene scorer of the code. */
		private final IoFunction<float[], RandomVectorScorer> luceneScorers;
		/** The jVector graph as our contract, null for a Lucene configuration. */
		private final GraphView view;
		/** Our searcher. */
		private final EvitaGraphSearcher searcher;
		/** For a query, the node scorer of the code. */
		private final Function<float[], NodeScorer> nodeScorers;

		/**
		 * @param label         configuration name
		 * @param luceneGraph   the Lucene graph, or null
		 * @param luceneScorers the Lucene scorers, or null
		 * @param view          the graph of our searcher, or null
		 * @param searcher      our searcher, or null
		 * @param nodeScorers   the node scorers, or null
		 */
		private Configuration(
			@Nonnull String label,
			@Nullable OnHeapHnswGraph luceneGraph,
			@Nullable IoFunction<float[], RandomVectorScorer> luceneScorers,
			@Nullable GraphView view,
			@Nullable EvitaGraphSearcher searcher,
			@Nullable Function<float[], NodeScorer> nodeScorers
		) {
			this.label = label;
			this.luceneGraph = luceneGraph;
			this.luceneScorers = luceneScorers;
			this.view = view;
			this.searcher = searcher;
			this.nodeScorers = nodeScorers;
		}

		/**
		 * @param label   configuration name
		 * @param graph   the Lucene graph
		 * @param scorers for a query, the Lucene scorer of the code
		 * @return a configuration searched by Lucene's searchers
		 */
		@Nonnull
		static Configuration lucene(
			@Nonnull String label,
			@Nonnull OnHeapHnswGraph graph,
			@Nonnull IoFunction<float[], RandomVectorScorer> scorers
		) {
			return new Configuration(label, graph, scorers, null, null, null);
		}

		/**
		 * @param label    configuration name
		 * @param view     the graph
		 * @param searcher our searcher
		 * @param scorers  for a query, the node scorer of the code
		 * @return a configuration searched by our searcher
		 */
		@Nonnull
		static Configuration own(
			@Nonnull String label,
			@Nonnull GraphView view,
			@Nonnull EvitaGraphSearcher searcher,
			@Nonnull Function<float[], NodeScorer> scorers
		) {
			return new Configuration(label, null, null, view, searcher, scorers);
		}

		/**
		 * @param query    the query
		 * @param acceptor the filter, null for none
		 * @param acorn    whether ACORN widening is on
		 * @param stats    receives the counters
		 * @return candidate ordinals, best first
		 * @throws IOException when a scorer fails
		 */
		@Nonnull
		int[] walk(@Nonnull float[] query, @Nullable Acceptor acceptor, boolean acorn, @Nonnull WalkStats stats)
			throws IOException {
			final int[] result;
			if (this.luceneGraph != null) {
				result = ClickRecallScenario.luceneWalk(
					this.luceneScorers.apply(query), this.luceneGraph,
					acceptor == null ? null : acceptor.asLuceneBits(), acceptor == null ? 0 : acceptor.count(),
					Integer.MAX_VALUE, acorn ? 100 : 0, stats
				);
			} else {
				result = this.searcher.search(this.view, this.nodeScorers.apply(query), acceptor, CANDIDATES, acorn,
					Integer.MAX_VALUE, EvitaGraphSearcher.NEVER_CANCELLED);
				stats.visited += this.searcher.visitedCount();
			}
			if (result == null) {
				throw new IllegalStateException("A walk without a budget cannot exceed it.");
			}
			return result;
		}

		/**
		 * @param query    the query
		 * @param filter   the filter
		 * @param acceptor the filter as an acceptor
		 * @param exact    the exact scan of the fallback
		 * @param stats    receives the counters
		 * @return candidate ordinals of the planner of the implementation proposal, best first
		 * @throws IOException when a scorer fails
		 */
		@Nonnull
		int[] planned(
			@Nonnull float[] query,
			@Nonnull Filter filter,
			@Nonnull Acceptor acceptor,
			@Nonnull ExactScorer exact,
			@Nonnull WalkStats stats
		) throws IOException {
			if (this.luceneGraph != null) {
				return ClickRecallScenario.plannedLuceneWalk(this.luceneScorers, this.luceneGraph,
					acceptor.asLuceneBits(), filter, exact, query, stats);
			}
			return GraphSeamScenarios.plannedOwnWalk(this.searcher, this.view, this.nodeScorers.apply(query),
				acceptor, exact, query, filter, stats);
		}
	}
}
