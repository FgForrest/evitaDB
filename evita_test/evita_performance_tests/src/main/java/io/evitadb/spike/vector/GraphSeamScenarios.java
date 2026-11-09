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

import io.evitadb.spike.vector.ClickRecallScenario.Evaluator;
import io.evitadb.spike.vector.ClickRecallScenario.ExactScorer;
import io.evitadb.spike.vector.ClickRecallScenario.Filter;
import io.evitadb.spike.vector.ClickRecallScenario.IoFunction;
import io.evitadb.spike.vector.ClickRecallScenario.WalkStats;
import io.evitadb.spike.vector.EvitaGraphSearcher.NodeScorer;
import io.evitadb.spike.vector.JVectorFootprintScenarios.MappedRavv;
import io.github.jbellis.jvector.graph.GraphSearcher;
import io.github.jbellis.jvector.graph.ImmutableGraphIndex;
import io.github.jbellis.jvector.graph.SearchResult;
import io.github.jbellis.jvector.graph.similarity.DefaultSearchScoreProvider;
import io.github.jbellis.jvector.graph.similarity.ScoreFunction;
import org.apache.lucene.util.hnsw.HnswGraph;
import org.apache.lucene.util.hnsw.OnHeapHnswGraph;
import org.apache.lucene.util.hnsw.RandomVectorScorer;
import org.apache.lucene.util.quantization.QuantizedByteVectorValues.ScalarEncoding;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.function.Function;

import static io.evitadb.spike.vector.VectorMemoryProbe.line;
import static io.evitadb.spike.vector.VectorMemoryProbe.mb;
import static io.evitadb.spike.vector.VectorMemoryProbe.section;

/**
 * Scenarios that exercise the evitaDB-owned seams over the click query set of {@link ClickRecallScenario}: the same
 * corpus, queries, collection filter and evaluation (recall@10 against the exact scan, click metrics, latency of the
 * recall harness), with three different subjects:
 *
 * - `own-searcher`: {@link EvitaGraphSearcher} over jVector's graph (spec step 5, OP6-13), side by side with
 *   jVector's own `GraphSearcher`, driven by float32, PQ and BQ and, through the scorer seam, by Lucene's OSQ codes
 *   from our code file (spec §3.5: one graph, every code); plus a check that a cancelled walk stops;
 * - `graph-family`: jVector graphs with `alpha` 1.0 / 1.2 and the hierarchy on / off (spec step 4);
 * - `graph-file`: the Lucene and the jVector graph written into {@link MappedGraphFile} and walked from the mapping,
 *   against the same graph on the heap.
 *
 * @author Lukáš Hornych (hornych@fg.cz)
 */
public final class GraphSeamScenarios {
	/** Depth of the approximate walk. */
	private static final int CANDIDATES = ClickRecallScenario.CANDIDATES;

	private GraphSeamScenarios() {
		throw new UnsupportedOperationException("Utility class, not to be instantiated.");
	}

	/**
	 * Our searcher against jVector's over one jVector graph, every code, every strategy.
	 *
	 * @param base       mapped corpus vectors
	 * @param workDir    directory holding the corpus mapping, the click queries and the code files
	 * @param collection collection code of the filter (the clicked records' one with clicks), null for none
	 * @throws IOException when an input cannot be read
	 */
	public static void ownSearcher(
		@Nonnull MappedVectors base,
		@Nonnull Path workDir,
		@Nullable String collection
	) throws IOException {
		section("Our searcher over the jVector graph, against jVector's GraphSearcher");
		final Evaluator evaluator = ClickRecallScenario.evaluator(base, workDir, collection);
		final Filter filter = evaluator.filter;
		final MappedRavv ravv = new MappedRavv(base);
		final ImmutableGraphIndex graph = SpikeIndexes.jvectorGraph(ravv, SpikeIndexes.DEFAULT_ALPHA, true);
		final Function<float[], ScoreFunction> float32 = SpikeIndexes.jvectorFloat32(ravv);
		final Function<float[], ScoreFunction> pqScores = SpikeIndexes.jvectorCodes(SpikeIndexes.pq(ravv));
		final Function<float[], ScoreFunction> bqScores = SpikeIndexes.jvectorCodes(SpikeIndexes.bq(ravv));
		final GraphView view = GraphView.ofJVector(graph, graph.getView());
		final EvitaGraphSearcher searcher = new EvitaGraphSearcher();

		for (final Object[] code : new Object[][]{{"float32", float32}, {"pq 256", pqScores}, {"bq", bqScores}}) {
			@SuppressWarnings("unchecked") final Function<float[], ScoreFunction> scores =
				(Function<float[], ScoreFunction>) code[1];
			libraryModes(evaluator, "jvector " + code[0] + ", GraphSearcher", graph, scores, filter);
			ownModes(evaluator, "jvector " + code[0] + ", our searcher", view, searcher,
				q -> NodeScorer.ofJVector(scores.apply(q)), filter);
		}
		// spec §3.5: Lucene's OSQ codes from our code file drive the jVector graph through the scorer seam
		for (final ScalarEncoding encoding : new ScalarEncoding[]{
			ScalarEncoding.SINGLE_BIT_QUERY_NIBBLE, ScalarEncoding.PACKED_NIBBLE
		}) {
			final IoFunction<float[], RandomVectorScorer> scorers =
				SpikeIndexes.luceneOsq(SpikeIndexes.osqCodes(workDir, base, encoding));
			ownModes(evaluator, "jvector graph, lucene osq " + encoding.getBits() + "-bit, our searcher", view,
				searcher, q -> NodeScorer.ofLucene(unchecked(scorers, q)), filter);
		}
		checkCancellation(evaluator, view, float32, filter);
	}

	/**
	 * jVector graphs of the four structural variants, each with the same codes and strategies.
	 *
	 * @param base       mapped corpus vectors
	 * @param workDir    directory holding the corpus mapping and the click queries
	 * @param collection collection code of the filter (the clicked records' one with clicks), null for none
	 * @throws IOException when an input cannot be read
	 */
	public static void graphFamily(
		@Nonnull MappedVectors base,
		@Nonnull Path workDir,
		@Nullable String collection
	) throws IOException {
		section("Graph family: jVector alpha 1.0 / 1.2, hierarchy on / off");
		final Evaluator evaluator = ClickRecallScenario.evaluator(base, workDir, collection);
		final Filter filter = evaluator.filter;
		final MappedRavv ravv = new MappedRavv(base);
		final Function<float[], ScoreFunction> float32 = SpikeIndexes.jvectorFloat32(ravv);
		final Function<float[], ScoreFunction> pqScores = SpikeIndexes.jvectorCodes(SpikeIndexes.pq(ravv));
		final Function<float[], ScoreFunction> bqScores = SpikeIndexes.jvectorCodes(SpikeIndexes.bq(ravv));
		final EvitaGraphSearcher searcher = new EvitaGraphSearcher();
		for (final float alpha : new float[]{1.0f, 1.2f}) {
			for (final boolean hierarchy : new boolean[]{true, false}) {
				final String label = "jvector alpha " + alpha + (hierarchy ? ", hierarchy" : ", flat");
				final ImmutableGraphIndex graph = SpikeIndexes.jvectorGraph(ravv, alpha, hierarchy);
				final GraphView view = GraphView.ofJVector(graph, graph.getView());
				libraryModes(evaluator, label + ", float32", graph, float32, null);
				libraryModes(evaluator, label + ", pq 256", graph, pqScores, null);
				libraryModes(evaluator, label + ", bq", graph, bqScores, null);
				ownModes(evaluator, label + ", float32, our searcher", view, searcher,
					q -> NodeScorer.ofJVector(float32.apply(q)), filter);
				ownModes(evaluator, label + ", pq 256, our searcher", view, searcher,
					q -> NodeScorer.ofJVector(pqScores.apply(q)), filter);
			}
		}
	}

	/**
	 * The Lucene and the jVector graph written into our graph file and walked from the mapping.
	 *
	 * @param base       mapped corpus vectors
	 * @param workDir    directory holding the corpus mapping and the click queries; receives the graph files
	 * @param collection collection code of the filter (the clicked records' one with clicks), null for none
	 * @throws IOException when an input cannot be read or a graph file cannot be written
	 */
	public static void graphFile(
		@Nonnull MappedVectors base,
		@Nonnull Path workDir,
		@Nullable String collection
	) throws IOException {
		section("The graph in a file of our own, mapped");
		final Evaluator evaluator = ClickRecallScenario.evaluator(base, workDir, collection);
		final Filter filter = evaluator.filter;

		// Lucene: the graph built on the heap, written, mapped, walked by Lucene's searchers
		long start = System.nanoTime();
		final OnHeapHnswGraph luceneGraph = SpikeIndexes.luceneGraph(base);
		final long luceneBuild = System.nanoTime() - start;
		final Path luceneFile = workDir.resolve("graph-lucene.graph");
		final MappedGraphFile luceneMapped = writeAndOpen("Lucene", GraphView.ofLucene(luceneGraph), luceneFile,
			luceneBuild, luceneGraph);
		final IoFunction<float[], RandomVectorScorer> float32Scorers = SpikeIndexes.luceneFloat32(base);
		final IoFunction<float[], RandomVectorScorer> osq4Scorers =
			SpikeIndexes.luceneOsq(SpikeIndexes.osqCodes(workDir, base, ScalarEncoding.PACKED_NIBBLE));
		compareLucene(evaluator, luceneGraph, luceneMapped, float32Scorers);
		ClickRecallScenario.luceneModes(evaluator, "lucene float32, heap graph", luceneGraph, filter, float32Scorers);
		ClickRecallScenario.luceneModes(evaluator, "lucene float32, mapped graph", luceneMapped, filter,
			float32Scorers);
		ClickRecallScenario.luceneModes(evaluator, "lucene osq 4-bit, heap graph", luceneGraph, filter, osq4Scorers);
		ClickRecallScenario.luceneModes(evaluator, "lucene osq 4-bit, mapped graph", luceneMapped, filter,
			osq4Scorers);

		// jVector: the graph built on the heap, written, mapped, walked by our searcher
		final MappedRavv ravv = new MappedRavv(base);
		start = System.nanoTime();
		final ImmutableGraphIndex jvectorGraph = SpikeIndexes.jvectorGraph(ravv, SpikeIndexes.DEFAULT_ALPHA, true);
		final long jvectorBuild = System.nanoTime() - start;
		final GraphView jvectorView = GraphView.ofJVector(jvectorGraph, jvectorGraph.getView());
		final Path jvectorFile = workDir.resolve("graph-jvector.graph");
		final MappedGraphFile jvectorMapped = writeAndOpen("jVector", jvectorView, jvectorFile, jvectorBuild,
			jvectorGraph);
		final Function<float[], ScoreFunction> pqScores = SpikeIndexes.jvectorCodes(SpikeIndexes.pq(ravv));
		final Function<float[], NodeScorer> pqScorers = q -> NodeScorer.ofJVector(pqScores.apply(q));
		final EvitaGraphSearcher searcher = new EvitaGraphSearcher();
		compareOwn(evaluator, jvectorView, jvectorMapped, pqScorers, filter);
		ownModes(evaluator, "jvector pq 256, heap graph, our searcher", jvectorView, searcher, pqScorers, filter);
		ownModes(evaluator, "jvector pq 256, mapped graph, our searcher", jvectorMapped, searcher, pqScorers, filter);
	}

	/**
	 * Writes a graph, maps it back and prints the costs against the build.
	 *
	 * @param library   library name for the output
	 * @param graph     the graph to write
	 * @param file      the graph file
	 * @param buildNs   how long the graph took to build
	 * @param heapGraph the heap graph, for its owned size
	 * @return the mapped graph
	 * @throws IOException when the file cannot be written or mapped
	 */
	@Nonnull
	private static MappedGraphFile writeAndOpen(
		@Nonnull String library,
		@Nonnull GraphView graph,
		@Nonnull Path file,
		long buildNs,
		@Nonnull Object heapGraph
	) throws IOException {
		long start = System.nanoTime();
		final long bytes = MappedGraphFile.write(graph, file);
		final long writeNs = System.nanoTime() - start;
		start = System.nanoTime();
		final MappedGraphFile mapped = MappedGraphFile.open(file);
		final long openNs = System.nanoTime() - start;
		line(String.format(
			"%s graph: build %d ms, heap %s; file %s (%.1f B/node, %d levels, slot degree %d), written in %d ms, "
				+ "mapped in %.3f ms, reader heap %s",
			library, buildNs / 1_000_000, mb(VectorMemoryProbe.ownedSize(heapGraph)), mb(bytes),
			bytes / (double) graph.idUpperBound(), mapped.numLevels(), mapped.maxDegree(), writeNs / 1_000_000,
			openNs / 1e6, mb(VectorMemoryProbe.ownedSize(mapped))
		));
		return mapped;
	}

	/**
	 * Checks that Lucene's searcher returns the same candidates over the heap and the mapped graph.
	 *
	 * @param evaluator the evaluator, for the queries
	 * @param heap      the heap graph
	 * @param mapped    the mapped graph
	 * @param scorers   for a query, the scorer of the code
	 * @throws IOException when a scorer fails
	 */
	private static void compareLucene(
		@Nonnull Evaluator evaluator,
		@Nonnull HnswGraph heap,
		@Nonnull HnswGraph mapped,
		@Nonnull IoFunction<float[], RandomVectorScorer> scorers
	) throws IOException {
		final WalkStats stats = new WalkStats();
		int differ = 0;
		for (final float[] query : evaluator.queries()) {
			final int[] a = ClickRecallScenario.luceneWalk(scorers.apply(query), heap, null, 0,
				Integer.MAX_VALUE, 0, stats);
			final int[] b = ClickRecallScenario.luceneWalk(scorers.apply(query), mapped, null, 0,
				Integer.MAX_VALUE, 0, stats);
			if (!Arrays.equals(a, b)) {
				differ++;
			}
		}
		line("Lucene walk, heap against mapped graph: " + differ + " of " + evaluator.queries().length
			+ " queries return different candidates");
	}

	/**
	 * Checks that our searcher returns the same candidates over two graph views, unfiltered and in-walk.
	 *
	 * @param evaluator the evaluator, for the queries
	 * @param a         one view
	 * @param b         the other view
	 * @param scorers   for a query, the scorer of the code
	 * @param filter    the collection filter, null to compare unfiltered walks twice
	 */
	private static void compareOwn(
		@Nonnull Evaluator evaluator,
		@Nonnull GraphView a,
		@Nonnull GraphView b,
		@Nonnull Function<float[], NodeScorer> scorers,
		@Nullable Filter filter
	) {
		final EvitaGraphSearcher searcher = new EvitaGraphSearcher();
		final Acceptor accept = filter == null
			? null : Acceptor.of(filter, Acceptor.Representation.PRIMARY_KEY_BITMAP);
		int differ = 0;
		for (final float[] query : evaluator.queries()) {
			for (final Acceptor acceptor : new Acceptor[]{null, accept}) {
				final int[] x = searcher.search(a, scorers.apply(query), acceptor, CANDIDATES, false,
					Integer.MAX_VALUE, EvitaGraphSearcher.NEVER_CANCELLED);
				final int[] y = searcher.search(b, scorers.apply(query), acceptor, CANDIDATES, false,
					Integer.MAX_VALUE, EvitaGraphSearcher.NEVER_CANCELLED);
				if (!Arrays.equals(x, y)) {
					differ++;
				}
			}
		}
		line("Our searcher, heap against mapped graph: " + differ + " of " + 2 * evaluator.queries().length
			+ " walks (unfiltered and in-walk) return different candidates");
	}

	/**
	 * Runs jVector's `GraphSearcher` unfiltered and, with a filter, post-filtered and with in-walk acceptance.
	 *
	 * @param evaluator the evaluator
	 * @param label     configuration name
	 * @param graph     the graph
	 * @param scores    for a query, the score function driving the walk
	 * @param filter    the collection filter, null to run unfiltered only
	 */
	private static void libraryModes(
		@Nonnull Evaluator evaluator,
		@Nonnull String label,
		@Nonnull ImmutableGraphIndex graph,
		@Nonnull Function<float[], ScoreFunction> scores,
		@Nullable Filter filter
	) {
		final io.github.jbellis.jvector.util.Bits all = io.github.jbellis.jvector.util.Bits.ALL;
		evaluator.approximate(label, "all", null, (q, stats) -> libraryWalk(graph, scores.apply(q), all, stats));
		if (filter == null) {
			return;
		}
		final io.github.jbellis.jvector.util.Bits accept = filter::contains;
		evaluator.approximate(label, "post-filter", filter, (q, stats) -> ClickRecallScenario.postFilter(
			libraryWalk(graph, scores.apply(q), all, stats), filter, stats));
		evaluator.approximate(label, "in-walk", filter, (q, stats) -> libraryWalk(graph, scores.apply(q), accept,
			stats));
	}

	/**
	 * One walk of jVector's `GraphSearcher`.
	 *
	 * @param graph  the graph
	 * @param scores the score function of the query
	 * @param accept the filter
	 * @param stats  receives jVector's visited count (distinct scored nodes)
	 * @return candidate ordinals, best first by the score function
	 * @throws IOException when the searcher cannot be closed
	 */
	@Nonnull
	private static int[] libraryWalk(
		@Nonnull ImmutableGraphIndex graph,
		@Nonnull ScoreFunction scores,
		@Nonnull io.github.jbellis.jvector.util.Bits accept,
		@Nonnull WalkStats stats
	) throws IOException {
		try (final GraphSearcher searcher = new GraphSearcher(graph)) {
			final SearchResult result = searcher.search(
				new DefaultSearchScoreProvider(scores), CANDIDATES, CANDIDATES, 0.0f, 0.0f, accept
			);
			stats.visited += result.getVisitedCount();
			final SearchResult.NodeScore[] nodes = result.getNodes();
			final int[] ordinals = new int[nodes.length];
			for (int i = 0; i < nodes.length; i++) {
				ordinals[i] = nodes[i].node;
			}
			return ordinals;
		}
	}

	/**
	 * Runs our searcher unfiltered, post-filtered, with in-walk acceptance, with ACORN widening and through the
	 * planner of the implementation proposal.
	 *
	 * @param evaluator the evaluator
	 * @param label     configuration name
	 * @param graph     the graph
	 * @param searcher  our searcher
	 * @param scorers   for a query, the scorer of the code driving the walk
	 * @param filter    the collection filter, null to run unfiltered only
	 */
	static void ownModes(
		@Nonnull Evaluator evaluator,
		@Nonnull String label,
		@Nonnull GraphView graph,
		@Nonnull EvitaGraphSearcher searcher,
		@Nonnull Function<float[], NodeScorer> scorers,
		@Nullable Filter filter
	) {
		evaluator.approximate(label, "all", null, (q, stats) -> ownWalk(searcher, graph, scorers.apply(q), null,
			false, Integer.MAX_VALUE, stats));
		if (filter == null) {
			return;
		}
		final Acceptor accept = Acceptor.of(filter, Acceptor.Representation.PRIMARY_KEY_BITMAP);
		evaluator.approximate(label, "post-filter", filter, (q, stats) -> ClickRecallScenario.postFilter(
			ownWalk(searcher, graph, scorers.apply(q), null, false, Integer.MAX_VALUE, stats), filter, stats));
		evaluator.approximate(label, "in-walk", filter, (q, stats) -> ownWalk(searcher, graph, scorers.apply(q),
			accept, false, Integer.MAX_VALUE, stats));
		evaluator.approximate(label, "acorn", filter, (q, stats) -> ownWalk(searcher, graph, scorers.apply(q),
			accept, true, Integer.MAX_VALUE, stats));
		evaluator.approximate(label, "planner", filter, (q, stats) ->
			plannedOwnWalk(searcher, graph, scorers.apply(q), accept, evaluator, q, filter, stats));
	}

	/**
	 * The planner of the implementation proposal over our searcher.
	 *
	 * @param searcher  our searcher
	 * @param graph     the graph
	 * @param scorer    the query's scorer
	 * @param accept    the filter
	 * @param exact     the exact scan of the fallback
	 * @param query     the query vector
	 * @param filter    the filter, for the exact scan
	 * @param stats     receives the visited count and the fallbacks
	 * @return candidate ordinals, best first
	 */
	@Nonnull
	static int[] plannedOwnWalk(
		@Nonnull EvitaGraphSearcher searcher,
		@Nonnull GraphView graph,
		@Nonnull NodeScorer scorer,
		@Nonnull Acceptor accept,
		@Nonnull ExactScorer exact,
		@Nonnull float[] query,
		@Nonnull Filter filter,
		@Nonnull WalkStats stats
	) {
		if (accept.count() <= CANDIDATES) {
			stats.fallbacks++;
			return exact.exactScan(query, filter, CANDIDATES);
		}
		final int[] walked = searcher.search(graph, scorer, accept, CANDIDATES, false, accept.count() + 1,
			EvitaGraphSearcher.NEVER_CANCELLED);
		stats.visited += searcher.visitedCount();
		if (walked == null) {
			stats.fallbacks++;
			return exact.exactScan(query, filter, CANDIDATES);
		}
		return walked;
	}

	/**
	 * One walk of our searcher without a budget.
	 *
	 * @param searcher our searcher
	 * @param graph    the graph
	 * @param scorer   the query's scorer
	 * @param accept   the filter, null for none
	 * @param acorn    whether ACORN widening is on
	 * @param budget   the visit budget
	 * @param stats    receives the visited count
	 * @return candidate ordinals, best first
	 */
	@Nonnull
	private static int[] ownWalk(
		@Nonnull EvitaGraphSearcher searcher,
		@Nonnull GraphView graph,
		@Nonnull NodeScorer scorer,
		@Nullable Acceptor accept,
		boolean acorn,
		int budget,
		@Nonnull WalkStats stats
	) {
		final int[] result = searcher.search(graph, scorer, accept, CANDIDATES, acorn, budget,
			EvitaGraphSearcher.NEVER_CANCELLED);
		stats.visited += searcher.visitedCount();
		if (result == null) {
			throw new IllegalStateException("A walk without a budget cannot exceed it.");
		}
		return result;
	}

	/**
	 * Cancels walks after a fixed number of cancellation checks and verifies that they stop there.
	 *
	 * @param evaluator the evaluator, for the queries
	 * @param graph     the graph
	 * @param float32   for a query, the float32 score function
	 * @param filter    the collection filter, which makes the walks long; null walks unfiltered
	 */
	private static void checkCancellation(
		@Nonnull Evaluator evaluator,
		@Nonnull GraphView graph,
		@Nonnull Function<float[], ScoreFunction> float32,
		@Nullable Filter filter
	) {
		final EvitaGraphSearcher searcher = new EvitaGraphSearcher();
		final Acceptor accept = filter == null
			? null : Acceptor.of(filter, Acceptor.Representation.PRIMARY_KEY_BITMAP);
		final int cancelAt = 20;
		int cancelled = 0;
		int finishedEarlier = 0;
		long visitedUncancelled = 0;
		long checksAfterCancel = 0;
		for (final float[] query : evaluator.queries()) {
			searcher.search(graph, NodeScorer.ofJVector(float32.apply(query)), accept, CANDIDATES, false,
				Integer.MAX_VALUE, EvitaGraphSearcher.NEVER_CANCELLED);
			visitedUncancelled += searcher.visitedCount();
			final int[] checks = {0};
			try {
				searcher.search(graph, NodeScorer.ofJVector(float32.apply(query)), accept, CANDIDATES, false,
					Integer.MAX_VALUE, () -> ++checks[0] > cancelAt);
				finishedEarlier++;
			} catch (CancellationException e) {
				cancelled++;
				checksAfterCancel += checks[0] - (cancelAt + 1);
			}
		}
		line(String.format(
			"cancellation after %d checks: %d of %d %s walks cancelled, %d finished before; checks made after "
				+ "the flag turned true: %d; an uncancelled walk scores %.0f nodes on average",
			cancelAt, cancelled, evaluator.queries().length, accept == null ? "unfiltered" : "in-walk",
			finishedEarlier, checksAfterCancel,
			visitedUncancelled / (double) evaluator.queries().length
		));
	}

	/**
	 * @param scorers for a query, a Lucene scorer factory call
	 * @param query   the query
	 * @return the query's scorer
	 * @throws java.io.UncheckedIOException when the code cannot be read
	 */
	@Nonnull
	private static RandomVectorScorer unchecked(
		@Nonnull IoFunction<float[], RandomVectorScorer> scorers,
		@Nonnull float[] query
	) {
		try {
			return scorers.apply(query);
		} catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

}
