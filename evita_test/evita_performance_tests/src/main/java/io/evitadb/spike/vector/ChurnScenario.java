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

import io.evitadb.spike.vector.ClickRecallScenario.ExactScorer;
import io.evitadb.spike.vector.ClickRecallScenario.Filter;
import io.evitadb.spike.vector.ClickRecallScenario.IoFunction;
import io.evitadb.spike.vector.ClickRecallScenario.WalkStats;
import io.evitadb.spike.vector.EvitaGraphSearcher.NodeScorer;
import io.evitadb.spike.vector.JVectorFootprintScenarios.MappedRavv;
import io.evitadb.spike.vector.LuceneFootprintScenarios.MappedFloats;
import io.github.jbellis.jvector.graph.GraphIndexBuilder;
import io.github.jbellis.jvector.graph.GraphSearcher;
import io.github.jbellis.jvector.graph.ImmutableGraphIndex;
import io.github.jbellis.jvector.graph.SearchResult;
import io.github.jbellis.jvector.graph.similarity.DefaultSearchScoreProvider;
import io.github.jbellis.jvector.util.PhysicalCoreExecutor;
import io.github.jbellis.jvector.vector.VectorizationProvider;
import io.github.jbellis.jvector.vector.types.VectorTypeSupport;
import org.apache.lucene.codecs.hnsw.FlatVectorScorerUtil;
import org.apache.lucene.codecs.hnsw.FlatVectorsScorer;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.util.Bits;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.util.hnsw.HnswGraphBuilder;
import org.apache.lucene.util.hnsw.HnswGraphSearcher;
import org.apache.lucene.util.hnsw.OnHeapHnswGraph;
import org.apache.lucene.util.hnsw.RandomVectorScorer;
import org.apache.lucene.util.quantization.QuantizedByteVectorValues.ScalarEncoding;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static io.evitadb.spike.vector.VectorMemoryProbe.line;
import static io.evitadb.spike.vector.VectorMemoryProbe.section;

/**
 * The churn protocol of spec §4.7 on a real corpus: the graph starts from half of the corpus and is then changed
 * while it is searched.
 *
 * The ordinals are shuffled with a fixed seed: the first 50 % form the initial graph, the next 40 % are inserted in
 * four chunks of 10 %, the last 10 % are held out to replace deleted nodes. Recall@10 (float32 walk, no rerank) is
 * measured against the exact scan over the nodes live at that moment, with the writer stopped, after every step.
 *
 * - **jVector**: the initial half is inserted with `addGraphNode` on all cores and cleaned up. Each chunk is then
 *   inserted by one writer thread (evitaDB's single writer) while four reader threads search continuously; readers
 *   record their latency, every exception, and every returned node that was never inserted or was already deleted.
 *   Then 10 % of the live nodes are deleted (`markNodeDeleted`) and recall is measured before and after `cleanup()`,
 *   which runs while the readers keep searching and while one more `View` is held open, to test spec §3.2's claim
 *   that it needs a quiescent window. Then another 10 % is replaced: deleted and the held-out nodes inserted, under
 *   the readers again. Finally a graph built afresh over the same live set gives the recall the churned graph
 *   should be compared with.
 * - **Lucene**: `HnswGraphBuilder#addGraphNode` on one thread; its graph supports no concurrent search, which the
 *   readers here test rather than assume. Deletes exist only as liveness bits passed as the walk's accept bits; the
 *   recall drift at 10 / 20 / 30 % deleted is measured, then the cost of rebuilding the live set.
 *
 * @author Lukáš Hornych (hornych@fg.cz)
 */
public final class ChurnScenario {
	/** Oversampling factor the combination's candidates are reranked at, as in measurement 8. */
	private static final int COMBINED_FACTOR = 4;

	/** Number of records the user sees. */
	private static final int TOP_K = ClickRecallScenario.TOP_K;
	/** Depth of the walk. */
	private static final int CANDIDATES = ClickRecallScenario.CANDIDATES;
	/** The most queries a recall checkpoint runs. */
	private static final int MAX_QUERIES = 500;
	/** Concurrent reader threads. */
	private static final int READERS = 4;
	/** Node state: never inserted. */
	private static final int ABSENT = 0;
	/** Node state: insertion started or done. */
	private static final int INSERTED = 1;
	/** Node state: deleted. */
	private static final int DELETED = 2;
	/** Similarity used everywhere; corpus and query vectors are unit length. */
	private static final VectorSimilarityFunction VSF = VectorSimilarityFunction.DOT_PRODUCT;
	/** jVector's name of the same similarity. */
	private static final io.github.jbellis.jvector.vector.VectorSimilarityFunction JVSF =
		io.github.jbellis.jvector.vector.VectorSimilarityFunction.DOT_PRODUCT;
	/** jVector vector factory. */
	private static final VectorTypeSupport VTS = VectorizationProvider.getInstance().getVectorTypeSupport();

	private ChurnScenario() {
		throw new UnsupportedOperationException("Utility class, not to be instantiated.");
	}

	/**
	 * Runs the churn protocol on both libraries.
	 *
	 * @param base    mapped corpus vectors
	 * @param workDir directory holding the click queries
	 * @throws Exception when an input cannot be read or a thread fails
	 */
	public static void run(@Nonnull MappedVectors base, @Nonnull Path workDir) throws Exception {
		section("Churn: insert, delete and replace under concurrent search");
		final float[][] queries = queries(base, workDir);
		final int[] order = shuffledOrder(base.count());
		line("corpus " + base.count() + " vectors, queries " + queries.length + ", readers " + READERS);
		jvector(base, queries, order);
		lucene(base, queries, order);
	}

	/**
	 * Runs the jVector protocol of {@link #run} on the library combination of measurement 8: the jVector graph
	 * changed by jVector's builder, searched by our searcher scored by Lucene's OSQ codes from our code file, the
	 * candidates reranked exactly at 4×. The readers walk with the 4-bit code; each checkpoint measures the 1-bit
	 * and the 4-bit code. The codes of the inserted nodes come from the code file written over the whole corpus;
	 * appending a code to the file when a node is inserted is not part of the protocol.
	 *
	 * @param base    mapped corpus vectors
	 * @param workDir directory holding the click queries and the code files
	 * @throws Exception when an input cannot be read or a thread fails
	 */
	public static void runCombined(@Nonnull MappedVectors base, @Nonnull Path workDir) throws Exception {
		section("Churn of the library combination: jVector graph, our searcher, Lucene OSQ codes");
		final float[][] queries = queries(base, workDir);
		final int[] order = shuffledOrder(base.count());
		final IoFunction<float[], RandomVectorScorer> osq1 =
			SpikeIndexes.luceneOsq(SpikeIndexes.osqCodes(workDir, base, ScalarEncoding.SINGLE_BIT_QUERY_NIBBLE));
		final IoFunction<float[], RandomVectorScorer> osq4 =
			SpikeIndexes.luceneOsq(SpikeIndexes.osqCodes(workDir, base, ScalarEncoding.PACKED_NIBBLE));
		line("corpus " + base.count() + " vectors, queries " + queries.length + ", readers " + READERS
			+ " (OSQ 4-bit, rerank at " + COMBINED_FACTOR + "x)");
		final ExactScorer exact = new ExactScorer(base);
		jvector(
			"jVector graph + OSQ", base, queries, order,
			(builder, state) -> Readers.combined(builder, base, queries, state, osq4),
			(label, graph, state) -> combinedRecall(label, graph, queries, state, exact, osq1, osq4)
		);
	}

	/**
	 * @param base    mapped corpus vectors
	 * @param workDir directory holding the click queries
	 * @return the first {@link #MAX_QUERIES} queries
	 * @throws IOException when the queries cannot be read
	 */
	@Nonnull
	private static float[][] queries(@Nonnull MappedVectors base, @Nonnull Path workDir) throws IOException {
		final float[][] allQueries = ClickRecallScenario.loadQueries(workDir, base.dimension());
		return Arrays.copyOf(allQueries, Math.min(MAX_QUERIES, allQueries.length));
	}

	/**
	 * @param n number of ordinals
	 * @return the ordinals `0..n-1` shuffled with the fixed seed of the protocol
	 */
	@Nonnull
	private static int[] shuffledOrder(int n) {
		final int[] order = new int[n];
		Arrays.setAll(order, i -> i);
		final Random random = new Random(42L);
		for (int i = n - 1; i > 0; i--) {
			final int j = random.nextInt(i + 1);
			final int t = order[i];
			order[i] = order[j];
			order[j] = t;
		}
		return order;
	}

	/**
	 * The jVector half of the protocol.
	 *
	 * @param base    the corpus
	 * @param queries the queries
	 * @param order   the shuffled ordinals
	 * @throws Exception when a thread fails
	 */
	private static void jvector(@Nonnull MappedVectors base, @Nonnull float[][] queries, @Nonnull int[] order)
		throws Exception {
		final ExactScorer exact = new ExactScorer(base);
		jvector(
			"jVector", base, queries, order,
			(builder, state) -> Readers.jvector(builder, base, queries, state),
			(label, graph, state) -> jvectorRecall(label, graph, base, queries, state, exact)
		);
	}

	/**
	 * The jVector protocol with the given readers and recall probe.
	 *
	 * @param name    the name the output lines start with
	 * @param base    the corpus
	 * @param queries the queries of the held-view check
	 * @param order   the shuffled ordinals
	 * @param readers creates the readers searching the builder's graph
	 * @param probe   measures recall at a checkpoint, with the writer stopped
	 * @throws Exception when a thread fails
	 */
	private static void jvector(
		@Nonnull String name,
		@Nonnull MappedVectors base,
		@Nonnull float[][] queries,
		@Nonnull int[] order,
		@Nonnull ReaderFactory readers,
		@Nonnull RecallProbe probe
	) throws Exception {
		final int n = base.count();
		final int half = n / 2;
		final int tenth = n / 10;
		final AtomicIntegerArray state = new AtomicIntegerArray(n);
		final MappedRavv ravv = new MappedRavv(base);
		final GraphIndexBuilder builder = JVectorFootprintScenarios.newBuilder(ravv, SpikeIndexes.DEFAULT_ALPHA, true);
		long start = System.nanoTime();
		insertParallel(builder, base, order, 0, half, state);
		builder.cleanup();
		line(String.format(name + ": initial %d nodes inserted on all cores and cleaned up in %d ms", half,
			(System.nanoTime() - start) / 1_000_000));
		probe.measure(name + ", 50 %, quiescent", builder.getGraph(), state);
		final Readers idle = readers.create(builder, state);
		idle.runFor(3_000);
		idle.report(name + " readers, no writer");

		// insertion of 40 % in four chunks, one writer, four readers
		for (int chunk = 0; chunk < 4; chunk++) {
			final int from = half + chunk * tenth;
			final int to = from + tenth;
			final Readers chunkReaders = readers.create(builder, state);
			chunkReaders.start();
			final MappedRavv writerRavv = new MappedRavv(base);
			start = System.nanoTime();
			for (int i = from; i < to; i++) {
				state.set(order[i], INSERTED);
				builder.addGraphNode(order[i], writerRavv);
			}
			final long insertNs = System.nanoTime() - start;
			chunkReaders.stop();
			line(String.format(name + ": %d inserts by one writer under %d readers: %.3f ms per insert",
				to - from, READERS, insertNs / 1e6 / (to - from)));
			chunkReaders.report(name + " readers during inserts to " + (60 + chunk * 10) + " %");
			probe.measure(name + ", " + (60 + chunk * 10) + " %, quiescent", builder.getGraph(), state);
		}

		// delete 10 % of the live nodes
		final int[] live = liveNodes(state);
		final int[] deleted = pick(live, tenth, new Random(44L));
		for (final int node : deleted) {
			state.set(node, DELETED);
			builder.markNodeDeleted(node);
		}
		probe.measure(name + ", 10 % deleted, before cleanup", builder.getGraph(), state);
		final ImmutableGraphIndex.View heldView = builder.getGraph().getView();
		final Readers cleanupReaders = readers.create(builder, state);
		cleanupReaders.start();
		start = System.nanoTime();
		builder.cleanup();
		final long cleanupNs = System.nanoTime() - start;
		cleanupReaders.stop();
		line(String.format(name + ": cleanup() under %d readers and one held View took %d ms", READERS,
			cleanupNs / 1_000_000));
		cleanupReaders.report(name + " readers during cleanup");
		checkHeldView(heldView, builder.getGraph(), base, queries, state);
		probe.measure(name + ", 10 % deleted, after cleanup", builder.getGraph(), state);

		// replace 10 %: delete and insert the held-out nodes, under the readers
		final int[] replaced = pick(liveNodes(state), tenth, new Random(45L));
		final Readers replaceReaders = readers.create(builder, state);
		replaceReaders.start();
		final MappedRavv writerRavv = new MappedRavv(base);
		start = System.nanoTime();
		for (int i = 0; i < replaced.length; i++) {
			state.set(replaced[i], DELETED);
			builder.markNodeDeleted(replaced[i]);
			final int fresh = order[n - tenth + i];
			state.set(fresh, INSERTED);
			builder.addGraphNode(fresh, writerRavv);
		}
		builder.cleanup();
		final long replaceNs = System.nanoTime() - start;
		replaceReaders.stop();
		line(String.format(name + ": %d replacements and cleanup under %d readers in %d ms", replaced.length,
			READERS, replaceNs / 1_000_000));
		replaceReaders.report(name + " readers during replacement");
		probe.measure(name + ", after replacement", builder.getGraph(), state);

		// the same live set built afresh
		final AtomicIntegerArray freshState = new AtomicIntegerArray(n);
		final int[] finalLive = liveNodes(state);
		final GraphIndexBuilder fresh = JVectorFootprintScenarios.newBuilder(
			new MappedRavv(base), SpikeIndexes.DEFAULT_ALPHA, true
		);
		start = System.nanoTime();
		insertParallel(fresh, base, finalLive, 0, finalLive.length, freshState);
		fresh.cleanup();
		line(String.format(name + ": the final %d live nodes built afresh in %d ms", finalLive.length,
			(System.nanoTime() - start) / 1_000_000));
		probe.measure(name + ", same live set built afresh", fresh.getGraph(), freshState);
		builder.close();
		fresh.close();
	}

	/**
	 * The Lucene half of the protocol.
	 *
	 * @param base    the corpus
	 * @param queries the queries
	 * @param order   the shuffled ordinals
	 * @throws Exception when a thread fails
	 */
	private static void lucene(@Nonnull MappedVectors base, @Nonnull float[][] queries, @Nonnull int[] order)
		throws Exception {
		final int n = base.count();
		final int half = n / 2;
		final int tenth = n / 10;
		final AtomicIntegerArray state = new AtomicIntegerArray(n);
		final ExactScorer exact = new ExactScorer(base);
		final HnswGraphBuilder builder = SpikeIndexes.luceneBuilder(base);
		long start = System.nanoTime();
		for (int i = 0; i < half; i++) {
			state.set(order[i], INSERTED);
			builder.addGraphNode(order[i]);
		}
		line(String.format("Lucene: initial %d nodes inserted by one thread in %d ms", half,
			(System.nanoTime() - start) / 1_000_000));
		final OnHeapHnswGraph graph = builder.getGraph();
		luceneRecall("Lucene, 50 %", graph, base, queries, state, exact);
		for (int chunk = 0; chunk < 4; chunk++) {
			final int from = half + chunk * tenth;
			final int to = from + tenth;
			final Readers readers = Readers.lucene(graph, base, queries, state);
			readers.start();
			start = System.nanoTime();
			for (int i = from; i < to; i++) {
				state.set(order[i], INSERTED);
				builder.addGraphNode(order[i]);
			}
			final long insertNs = System.nanoTime() - start;
			readers.stop();
			line(String.format("Lucene: %d inserts by one writer under %d readers: %.3f ms per insert",
				to - from, READERS, insertNs / 1e6 / (to - from)));
			readers.report("Lucene readers during inserts to " + (60 + chunk * 10) + " % (unsupported)");
			luceneRecall("Lucene, " + (60 + chunk * 10) + " %", graph, base, queries, state, exact);
		}
		final Random random = new Random(44L);
		for (int step = 1; step <= 3; step++) {
			final int[] deleted = pick(liveNodes(state), tenth, random);
			for (final int node : deleted) {
				state.set(node, DELETED);
			}
			luceneRecall("Lucene, " + (step * 10) + " % deleted (liveness bits)", graph, base, queries, state,
				exact);
		}
		final int[] live = liveNodes(state);
		final HnswGraphBuilder rebuild = SpikeIndexes.luceneBuilder(base);
		start = System.nanoTime();
		for (final int node : live) {
			rebuild.addGraphNode(node);
		}
		line(String.format("Lucene: rebuild of the %d live nodes on one thread in %d ms", live.length,
			(System.nanoTime() - start) / 1_000_000));
		final AtomicIntegerArray rebuiltState = new AtomicIntegerArray(n);
		for (final int node : live) {
			rebuiltState.set(node, INSERTED);
		}
		luceneRecall("Lucene, live set rebuilt", rebuild.getGraph(), base, queries, rebuiltState, exact);
	}

	/**
	 * Inserts a range of the order on all cores.
	 *
	 * @param builder the builder
	 * @param base    the corpus
	 * @param order   the ordinals
	 * @param from    first index, inclusive
	 * @param to      last index, exclusive
	 * @param state   node states, set to inserted
	 * @throws Exception when an insertion fails
	 */
	private static void insertParallel(
		@Nonnull GraphIndexBuilder builder,
		@Nonnull MappedVectors base,
		@Nonnull int[] order,
		int from,
		int to,
		@Nonnull AtomicIntegerArray state
	) throws Exception {
		final ThreadLocal<MappedRavv> ravvs = ThreadLocal.withInitial(() -> new MappedRavv(base));
		PhysicalCoreExecutor.pool().submit(() -> IntStream.range(from, to).parallel().forEach(i -> {
			state.set(order[i], INSERTED);
			builder.addGraphNode(order[i], ravvs.get());
		})).get();
	}

	/**
	 * Measures recall of jVector's searcher and ours over the live nodes, and checks that no result is dead.
	 *
	 * @param label   checkpoint name
	 * @param graph   the graph
	 * @param base    the corpus
	 * @param queries the queries
	 * @param state   node states
	 * @param exact   the exact scorer
	 * @throws IOException when a searcher cannot be closed
	 */
	private static void jvectorRecall(
		@Nonnull String label,
		@Nonnull ImmutableGraphIndex graph,
		@Nonnull MappedVectors base,
		@Nonnull float[][] queries,
		@Nonnull AtomicIntegerArray state,
		@Nonnull ExactScorer exact
	) throws IOException {
		final Filter live = liveFilter(state);
		final MappedRavv ravv = new MappedRavv(base);
		final EvitaGraphSearcher own = new EvitaGraphSearcher();
		double libraryRecall = 0;
		double ownRecall = 0;
		int dead = 0;
		try (final ImmutableGraphIndex.View view = graph.getView()) {
			final GraphView ours = GraphView.ofJVector(graph, view);
			for (final float[] query : queries) {
				final int[] library = jvectorSearch(graph, ravv, query);
				final int[] mine = own.search(ours, NodeScorer.ofJVector(ravv.rerankerFor(VTS.createFloatVector(query),
					JVSF)), null, CANDIDATES, false, Integer.MAX_VALUE, EvitaGraphSearcher.NEVER_CANCELLED);
				dead += countDead(library, state) + countDead(mine, state);
				libraryRecall += recall(query, library, live, exact);
				ownRecall += recall(query, mine, live, exact);
			}
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
		line(String.format(
			"%s: %d live nodes, recall@10 GraphSearcher %.3f, our searcher %.3f; dead nodes returned %d",
			label, live.count(), libraryRecall / queries.length, ownRecall / queries.length, dead
		));
	}

	/**
	 * Measures recall of the library combination over the live nodes: our searcher over the jVector graph, scored by
	 * each OSQ code, the candidates reranked exactly at {@link #COMBINED_FACTOR}×; and checks that no result is dead.
	 *
	 * @param label   checkpoint name
	 * @param graph   the graph
	 * @param queries the queries
	 * @param state   node states
	 * @param exact   the exact scorer
	 * @param osq1    for a query, the scorer of the 1-bit code
	 * @param osq4    for a query, the scorer of the 4-bit code
	 * @throws IOException when a scorer fails
	 */
	private static void combinedRecall(
		@Nonnull String label,
		@Nonnull ImmutableGraphIndex graph,
		@Nonnull float[][] queries,
		@Nonnull AtomicIntegerArray state,
		@Nonnull ExactScorer exact,
		@Nonnull IoFunction<float[], RandomVectorScorer> osq1,
		@Nonnull IoFunction<float[], RandomVectorScorer> osq4
	) throws IOException {
		final Filter live = liveFilter(state);
		final EvitaGraphSearcher own = new EvitaGraphSearcher();
		final int depth = COMBINED_FACTOR * TOP_K;
		double recall1 = 0;
		double recall4 = 0;
		int dead = 0;
		try (final ImmutableGraphIndex.View view = graph.getView()) {
			final GraphView ours = GraphView.ofJVector(graph, view);
			for (final float[] query : queries) {
				final int[] one = exact.rerank(query, own.search(ours, NodeScorer.ofLucene(osq1.apply(query)), null,
					CANDIDATES, false, Integer.MAX_VALUE, EvitaGraphSearcher.NEVER_CANCELLED), depth);
				final int[] four = exact.rerank(query, own.search(ours, NodeScorer.ofLucene(osq4.apply(query)),
					null, CANDIDATES, false, Integer.MAX_VALUE, EvitaGraphSearcher.NEVER_CANCELLED), depth);
				dead += countDead(one, state) + countDead(four, state);
				recall1 += recall(query, one, live, exact);
				recall4 += recall(query, four, live, exact);
			}
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
		line(String.format(
			"%s: %d live nodes, recall@10 at %dx OSQ 1-bit %.3f, OSQ 4-bit %.3f; dead nodes returned %d",
			label, live.count(), COMBINED_FACTOR, recall1 / queries.length, recall4 / queries.length, dead
		));
	}

	/**
	 * Measures recall of Lucene's searcher over the live nodes, deleted ones excluded by the accept bits.
	 *
	 * @param label   checkpoint name
	 * @param graph   the graph
	 * @param base    the corpus
	 * @param queries the queries
	 * @param state   node states
	 * @param exact   the exact scorer
	 * @throws IOException when a scorer fails
	 */
	private static void luceneRecall(
		@Nonnull String label,
		@Nonnull OnHeapHnswGraph graph,
		@Nonnull MappedVectors base,
		@Nonnull float[][] queries,
		@Nonnull AtomicIntegerArray state,
		@Nonnull ExactScorer exact
	) throws IOException {
		final Filter live = liveFilter(state);
		final Bits accept = ClickRecallScenario.luceneBits(live);
		final FlatVectorsScorer flatScorer = FlatVectorScorerUtil.getLucene99FlatVectorsScorer();
		final WalkStats stats = new WalkStats();
		double sum = 0;
		int dead = 0;
		for (final float[] query : queries) {
			final int[] result = ClickRecallScenario.luceneWalk(
				flatScorer.getRandomVectorScorer(VSF, new MappedFloats(base), query), graph, accept, live.count(),
				Integer.MAX_VALUE, 0, stats
			);
			dead += countDead(result, state);
			sum += recall(query, result, live, exact);
		}
		line(String.format("%s: %d live nodes, recall@10 %.3f, visited %.0f; dead nodes returned %d", label,
			live.count(), sum / queries.length, stats.visited / (double) queries.length, dead));
	}

	/**
	 * Searches a held `View` after `cleanup()` removed nodes and reports what it sees.
	 *
	 * @param view    the view opened before the cleanup
	 * @param graph   the graph
	 * @param base    the corpus
	 * @param queries the queries
	 * @param state   node states
	 */
	private static void checkHeldView(
		@Nonnull ImmutableGraphIndex.View view,
		@Nonnull ImmutableGraphIndex graph,
		@Nonnull MappedVectors base,
		@Nonnull float[][] queries,
		@Nonnull AtomicIntegerArray state
	) {
		final MappedRavv ravv = new MappedRavv(base);
		final EvitaGraphSearcher own = new EvitaGraphSearcher();
		int errors = 0;
		int dead = 0;
		String firstError = "none";
		for (final float[] query : queries) {
			try {
				final int[] result = own.search(GraphView.ofJVector(graph, view),
					NodeScorer.ofJVector(ravv.rerankerFor(VTS.createFloatVector(query), JVSF)), null, CANDIDATES,
					false, Integer.MAX_VALUE, EvitaGraphSearcher.NEVER_CANCELLED);
				dead += countDead(result, state);
			} catch (RuntimeException e) {
				if (errors++ == 0) {
					firstError = e.toString();
				}
			}
		}
		line(String.format("jVector: a View held across cleanup(): %d of %d walks failed (first: %s), %d dead "
			+ "nodes returned", errors, queries.length, firstError, dead));
	}

	/**
	 * @param graph the graph
	 * @param ravv  the vectors of the calling thread
	 * @param query the query
	 * @return jVector's float32 result, best first
	 * @throws IOException when the searcher cannot be closed
	 */
	@Nonnull
	private static int[] jvectorSearch(@Nonnull ImmutableGraphIndex graph, @Nonnull MappedRavv ravv,
		@Nonnull float[] query) throws IOException {
		try (final GraphSearcher searcher = new GraphSearcher(graph)) {
			final SearchResult result = searcher.search(
				new DefaultSearchScoreProvider(ravv.rerankerFor(VTS.createFloatVector(query), JVSF)),
				CANDIDATES, CANDIDATES, 0.0f, 0.0f, io.github.jbellis.jvector.util.Bits.ALL
			);
			final SearchResult.NodeScore[] nodes = result.getNodes();
			final int[] ordinals = new int[nodes.length];
			for (int i = 0; i < nodes.length; i++) {
				ordinals[i] = nodes[i].node;
			}
			return ordinals;
		}
	}

	/**
	 * @param query  the query
	 * @param result returned ordinals, best first
	 * @param live   the live nodes
	 * @param exact  the exact scorer
	 * @return recall@10 of the first ten results against the exact scan over the live nodes, ties counted
	 */
	private static double recall(@Nonnull float[] query, @Nonnull int[] result, @Nonnull Filter live,
		@Nonnull ExactScorer exact) {
		final int[] truth = exact.exactScan(query, live, TOP_K);
		final float kth = exact.score(query, truth[truth.length - 1]);
		int hits = 0;
		for (int i = 0; i < Math.min(TOP_K, result.length); i++) {
			if (live.accept()[result[i]] && exact.score(query, result[i]) >= kth - 1e-5f) {
				hits++;
			}
		}
		return hits / (double) truth.length;
	}

	/**
	 * @param result returned ordinals
	 * @param state  node states
	 * @return how many of them are not live
	 */
	private static int countDead(@Nonnull int[] result, @Nonnull AtomicIntegerArray state) {
		int dead = 0;
		for (final int node : result) {
			if (state.get(node) != INSERTED) {
				dead++;
			}
		}
		return dead;
	}

	/**
	 * @param state node states
	 * @return the live nodes as a filter; the churn indexes vectors, so every node is its own record
	 */
	@Nonnull
	private static Filter liveFilter(@Nonnull AtomicIntegerArray state) {
		final boolean[] accept = new boolean[state.length()];
		for (int i = 0; i < accept.length; i++) {
			accept[i] = state.get(i) == INSERTED;
		}
		return Filter.of(accept, PrimaryKeyOrdinals.identity(accept.length));
	}

	/**
	 * @param state node states
	 * @return the live node ordinals, ascending
	 */
	@Nonnull
	private static int[] liveNodes(@Nonnull AtomicIntegerArray state) {
		final int[] nodes = new int[state.length()];
		int count = 0;
		for (int i = 0; i < nodes.length; i++) {
			if (state.get(i) == INSERTED) {
				nodes[count++] = i;
			}
		}
		return Arrays.copyOf(nodes, count);
	}

	/**
	 * @param from   the nodes to pick from
	 * @param count  how many to pick
	 * @param random the seeded generator
	 * @return a random subset of the nodes
	 */
	@Nonnull
	private static int[] pick(@Nonnull int[] from, int count, @Nonnull Random random) {
		final int[] copy = from.clone();
		for (int i = 0; i < count; i++) {
			final int j = i + random.nextInt(copy.length - i);
			final int t = copy[i];
			copy[i] = copy[j];
			copy[j] = t;
		}
		return Arrays.copyOf(copy, count);
	}

	/**
	 * Creates the readers of one phase of the jVector protocol.
	 */
	@FunctionalInterface
	private interface ReaderFactory {
		/**
		 * @param builder the builder whose graph the readers search
		 * @param state   node states, for the dead-result check
		 * @return the readers, not started
		 */
		@Nonnull
		Readers create(@Nonnull GraphIndexBuilder builder, @Nonnull AtomicIntegerArray state);
	}

	/**
	 * Measures recall at a checkpoint of the jVector protocol, with the writer stopped.
	 */
	@FunctionalInterface
	private interface RecallProbe {
		/**
		 * @param label checkpoint name
		 * @param graph the graph
		 * @param state node states
		 * @throws IOException when a searcher or scorer fails
		 */
		void measure(@Nonnull String label, @Nonnull ImmutableGraphIndex graph, @Nonnull AtomicIntegerArray state)
			throws IOException;
	}

	/**
	 * Reader threads that search continuously until stopped, recording latency, exceptions and dead results.
	 */
	private static final class Readers {
		/** One search of a query by one reader; returns the result ordinals. */
		@FunctionalInterface
		private interface ReaderSearch {
			/**
			 * @param query the query
			 * @return result ordinals
			 * @throws Exception when the search fails
			 */
			@Nonnull
			int[] search(@Nonnull float[] query) throws Exception;
		}

		/** Per reader, the factory of its search (each reader owns its scratch). */
		private final List<ReaderSearch> searches;
		/** The queries, cycled. */
		private final float[][] queries;
		/** Node states, for the dead-result check. */
		private final AtomicIntegerArray state;
		/** Set to stop the readers. */
		private final AtomicBoolean stop = new AtomicBoolean();
		/** Searches completed. */
		private final AtomicLong searchCount = new AtomicLong();
		/** Searches that threw. */
		private final AtomicLong errors = new AtomicLong();
		/** Results that named a node never inserted or already deleted. */
		private final AtomicLong dead = new AtomicLong();
		/** The first exception, for the report. */
		private volatile String firstError = "none";
		/** Per reader, its latencies in nanoseconds. */
		private final List<long[]> latencies = new ArrayList<>(READERS);
		/** Per reader, how many latencies it recorded. */
		private final int[] recorded = new int[READERS];
		/** The reader threads. */
		private final Thread[] threads = new Thread[READERS];
		/** Counted down when every reader has finished. */
		private final CountDownLatch finished = new CountDownLatch(READERS);

		/**
		 * @param searches per reader, its search
		 * @param queries  the queries
		 * @param state    node states
		 */
		private Readers(@Nonnull List<ReaderSearch> searches, @Nonnull float[][] queries,
			@Nonnull AtomicIntegerArray state) {
			this.searches = searches;
			this.queries = queries;
			this.state = state;
			for (int r = 0; r < READERS; r++) {
				this.latencies.add(new long[1 << 20]);
			}
		}

		/**
		 * @param builder the jVector builder whose graph is searched
		 * @param base    the corpus
		 * @param queries the queries
		 * @param state   node states
		 * @return readers searching with jVector's `GraphSearcher`, float32
		 */
		@Nonnull
		static Readers jvector(@Nonnull GraphIndexBuilder builder, @Nonnull MappedVectors base,
			@Nonnull float[][] queries, @Nonnull AtomicIntegerArray state) {
			final List<ReaderSearch> searches = new ArrayList<>(READERS);
			for (int r = 0; r < READERS; r++) {
				final MappedRavv ravv = new MappedRavv(base);
				searches.add(query -> jvectorSearch(builder.getGraph(), ravv, query));
			}
			return new Readers(searches, queries, state);
		}

		/**
		 * @param builder the jVector builder whose graph is searched
		 * @param base    the corpus
		 * @param queries the queries
		 * @param state   node states
		 * @param scorers for a query, the scorer of the OSQ code the walk is scored by
		 * @return readers searching with our searcher, a new `View` per search, reranked exactly
		 */
		@Nonnull
		static Readers combined(@Nonnull GraphIndexBuilder builder, @Nonnull MappedVectors base,
			@Nonnull float[][] queries, @Nonnull AtomicIntegerArray state,
			@Nonnull IoFunction<float[], RandomVectorScorer> scorers) {
			final List<ReaderSearch> searches = new ArrayList<>(READERS);
			for (int r = 0; r < READERS; r++) {
				final EvitaGraphSearcher own = new EvitaGraphSearcher();
				final ExactScorer exact = new ExactScorer(base);
				searches.add(query -> {
					final ImmutableGraphIndex graph = builder.getGraph();
					try (final ImmutableGraphIndex.View view = graph.getView()) {
						return exact.rerank(query, own.search(GraphView.ofJVector(graph, view),
							NodeScorer.ofLucene(scorers.apply(query)), null, CANDIDATES, false, Integer.MAX_VALUE,
							EvitaGraphSearcher.NEVER_CANCELLED), COMBINED_FACTOR * TOP_K);
					}
				});
			}
			return new Readers(searches, queries, state);
		}

		/**
		 * @param graph   the Lucene graph being built
		 * @param base    the corpus
		 * @param queries the queries
		 * @param state   node states
		 * @return readers searching with Lucene's `HnswGraphSearcher`, float32
		 */
		@Nonnull
		static Readers lucene(@Nonnull OnHeapHnswGraph graph, @Nonnull MappedVectors base,
			@Nonnull float[][] queries, @Nonnull AtomicIntegerArray state) {
			final FlatVectorsScorer flatScorer = FlatVectorScorerUtil.getLucene99FlatVectorsScorer();
			final List<ReaderSearch> searches = new ArrayList<>(READERS);
			for (int r = 0; r < READERS; r++) {
				// the entry point Lucene documents as thread-safe on an OnHeapHnswGraph: no shared seek cursor
				searches.add(query -> {
					final ScoreDoc[] docs = HnswGraphSearcher.search(
						flatScorer.getRandomVectorScorer(VSF, new MappedFloats(base), query), CANDIDATES, graph, null,
						Integer.MAX_VALUE
					).topDocs().scoreDocs;
					final int[] ordinals = new int[docs.length];
					for (int i = 0; i < docs.length; i++) {
						ordinals[i] = docs[i].doc;
					}
					return ordinals;
				});
			}
			return new Readers(searches, queries, state);
		}

		/**
		 * Starts the readers.
		 */
		void start() {
			for (int r = 0; r < READERS; r++) {
				final int reader = r;
				this.threads[r] = new Thread(() -> loop(reader), "churn-reader-" + r);
				this.threads[r].setDaemon(true);
				this.threads[r].start();
			}
		}

		/**
		 * Runs the readers for a while with no writer.
		 *
		 * @param millis how long
		 * @throws InterruptedException when interrupted
		 */
		void runFor(long millis) throws InterruptedException {
			start();
			Thread.sleep(millis);
			stop();
		}

		/**
		 * Stops the readers and waits for them.
		 *
		 * @throws InterruptedException when interrupted
		 */
		void stop() throws InterruptedException {
			this.stop.set(true);
			if (!this.finished.await(60, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Readers did not stop within 60 s.");
			}
		}

		/**
		 * The loop of one reader.
		 *
		 * @param reader the reader index
		 */
		private void loop(int reader) {
			try {
				final ReaderSearch search = this.searches.get(reader);
				final long[] own = this.latencies.get(reader);
				int q = reader;
				while (!this.stop.get()) {
					final float[] query = this.queries[q % this.queries.length];
					q += READERS;
					final long start = System.nanoTime();
					try {
						final int[] result = search.search(query);
						final long elapsed = System.nanoTime() - start;
						if (this.recorded[reader] < own.length) {
							own[this.recorded[reader]++] = elapsed;
						}
						this.dead.addAndGet(countDead(result, this.state));
						this.searchCount.incrementAndGet();
					} catch (Exception | AssertionError e) {
						if (this.errors.getAndIncrement() == 0) {
							this.firstError = e.toString();
						}
					}
				}
			} finally {
				this.finished.countDown();
			}
		}

		/**
		 * Prints what the readers saw.
		 *
		 * @param label phase name
		 */
		void report(@Nonnull String label) {
			int total = 0;
			for (final int count : this.recorded) {
				total += count;
			}
			final long[] all = new long[total];
			int at = 0;
			for (int r = 0; r < READERS; r++) {
				System.arraycopy(this.latencies.get(r), 0, all, at, this.recorded[r]);
				at += this.recorded[r];
			}
			Arrays.sort(all);
			line(String.format(
				"%s: %d searches, p50 %.2f ms, p95 %.2f ms, errors %d (first: %s), dead nodes returned %d",
				label, this.searchCount.get(), total == 0 ? 0 : all[total / 2] / 1e6,
				total == 0 ? 0 : all[(int) (total * 0.95)] / 1e6, this.errors.get(), this.firstError, this.dead.get()
			));
		}
	}
}
