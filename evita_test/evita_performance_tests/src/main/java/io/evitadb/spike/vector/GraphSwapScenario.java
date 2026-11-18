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
import io.evitadb.spike.vector.EvitaGraphSearcher.NodeScorer;
import io.evitadb.spike.vector.JVectorFootprintScenarios.MappedRavv;
import io.evitadb.spike.vector.LuceneFootprintScenarios.MappedFloats;
import io.github.jbellis.jvector.graph.GraphIndexBuilder;
import io.github.jbellis.jvector.graph.ImmutableGraphIndex;
import io.github.jbellis.jvector.graph.OnHeapGraphIndex;
import io.github.jbellis.jvector.graph.similarity.BuildScoreProvider;
import io.github.jbellis.jvector.util.PhysicalCoreExecutor;
import io.github.jbellis.jvector.vector.VectorizationProvider;
import io.github.jbellis.jvector.vector.types.VectorTypeSupport;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.VectorEncoding;
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.hnsw.HnswGraph;
import org.apache.lucene.util.hnsw.HnswGraphBuilder;
import org.apache.lucene.util.hnsw.InitializedHnswGraphBuilder;
import org.apache.lucene.util.hnsw.OnHeapHnswGraph;
import org.apache.lucene.util.hnsw.RandomVectorScorerSupplier;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static io.evitadb.spike.vector.VectorMemoryProbe.line;
import static io.evitadb.spike.vector.VectorMemoryProbe.section;

/**
 * Prices the copy-and-swap maintenance of a graph that readers must never see being changed: a second instance is
 * created from the live graph, a batch of changes is applied to the private copy, and the copy replaces the live
 * graph with one reference write. Readers keep walking the old instance until they drop it.
 *
 * The graph starts from 90 % of the corpus (ordinals shuffled with the churn protocol's seed); the remaining 10 %
 * feed the inserts. Each library is measured in three shapes, for batches of 1, 100, 1,000 and 10,000 records
 * (capped at the reserve):
 *
 * - **copy only**: the price of a second instance alone, which a per-session copy would pay;
 * - **copy + inserts**: the batch inserted into the copy;
 * - **copy + deletes + inserts**: as many records deleted as inserted, the deletion applied while copying.
 *
 * Lucene copies with `InitializedHnswGraphBuilder#fromGraph`, which copies the neighbour lists without their
 * scores, drops deleted nodes through the ordinal map, repairs the nodes that lost neighbours and rebalances the
 * levels. It copies only a dense graph (ordinals `0..size-1` without holes), so the live graph is built over the
 * shuffled ordinals renumbered from 0; deleting compacts the ordinals again, so every swap with deletes renumbers
 * the vectors behind the graph. jVector copies with `GraphIndexBuilder#rescore`, which copies every edge and scores it
 * again; deletes are `markNodeDeleted` plus `cleanup()`, safe on a copy no reader sees, and keep the ordinals.
 *
 * Every phase reports its wall time, the bytes allocated by all threads (the copy of jVector runs on all cores),
 * and the collections and collection time of every collector. Memory is the `ramBytesUsed()` of both instances:
 * until the readers of the old instance finish, both are on the heap. Recall@10 of the swapped-in graph (float32
 * walk, no rerank, over the live records) checks that the copy still works.
 *
 * @author Lukáš Hornych (hornych@fg.cz)
 */
public final class GraphSwapScenario {
	/** Batch sizes, capped at the reserve of not-yet-inserted records. */
	private static final int[] BATCHES = {1, 100, 1_000, 10_000};
	/** Queries of the recall check of a swapped-in graph. */
	private static final int RECALL_QUERIES = 100;
	/** Number of records the user sees. */
	private static final int TOP_K = ClickRecallScenario.TOP_K;
	/** Depth of the walk. */
	private static final int CANDIDATES = ClickRecallScenario.CANDIDATES;
	/** jVector vector factory. */
	private static final VectorTypeSupport VTS = VectorizationProvider.getInstance().getVectorTypeSupport();

	private GraphSwapScenario() {
		throw new UnsupportedOperationException("Utility class, not to be instantiated.");
	}

	/**
	 * Runs the copy-and-swap measurement on both libraries.
	 *
	 * @param base    mapped corpus vectors
	 * @param workDir directory holding the click queries
	 * @throws Exception when an input cannot be read or a graph operation fails
	 */
	public static void run(@Nonnull MappedVectors base, @Nonnull Path workDir) throws Exception {
		section("Graph swap: copy the live graph, apply a batch, swap the copy in");
		final float[][] allQueries = ClickRecallScenario.loadQueries(workDir, base.dimension());
		final float[][] queries = Arrays.copyOf(allQueries, Math.min(RECALL_QUERIES, allQueries.length));
		final int n = base.count();
		final int[] order = shuffledOrder(n);
		final int initial = n - n / 10;
		final int[] batches = IntStream.of(BATCHES).filter(b -> b <= n - initial).toArray();
		line(String.format("corpus %d vectors; live graph %d nodes; reserve %d; batches %s; recall queries %d",
			n, initial, n - initial, Arrays.toString(batches), queries.length));
		final ExactScorer exact = new ExactScorer(base);
		lucene(base, order, initial, batches, queries, exact);
		jvector(base, order, initial, batches, queries, exact);
	}

	/**
	 * The Lucene half.
	 *
	 * @param base    the corpus
	 * @param order   the shuffled ordinals
	 * @param initial how many of them form the live graph
	 * @param batches the batch sizes
	 * @param queries the queries of the recall check
	 * @param exact   the exact scorer
	 * @throws IOException when a graph operation fails
	 */
	private static void lucene(
		@Nonnull MappedVectors base,
		@Nonnull int[] order,
		int initial,
		@Nonnull int[] batches,
		@Nonnull float[][] queries,
		@Nonnull ExactScorer exact
	) throws IOException {
		// Lucene copies only a dense graph: the live graph numbers its nodes 0..initial-1, node i is order[i]
		final HnswGraphBuilder builder = HnswGraphBuilder.create(
			SpikeIndexes.flatScorer().getRandomVectorScorerSupplier(
				SpikeIndexes.VSF, new RemappedFloats(base, order, initial)
			),
			SpikeIndexes.LUCENE_M, SpikeIndexes.BEAM, SpikeIndexes.LUCENE_SEED, initial
		);
		final long start = System.nanoTime();
		for (int i = 0; i < initial; i++) {
			builder.addGraphNode(i);
		}
		final OnHeapHnswGraph live = builder.getGraph();
		line(String.format("Lucene: live graph of %d nodes built on one thread in %d ms, %s", initial,
			(System.nanoTime() - start) / 1_000_000, VectorMemoryProbe.mb(live.ramBytesUsed())));
		final AtomicReference<OnHeapHnswGraph> current = new AtomicReference<>(live);
		// the first copy warms the code up and is not reported
		luceneCopy(base, live, order, initial, 0, false);
		for (final int batch : withZero(batches)) {
			for (final boolean deletes : batch == 0 ? new boolean[]{false} : new boolean[]{false, true}) {
				final Meter copyMeter = Meter.start();
				final LuceneCopy copy = luceneCopy(base, live, order, initial, batch, deletes);
				final Sample copySample = copyMeter.stop();
				final Meter insertMeter = Meter.start();
				for (int i = 0; i < batch; i++) {
					copy.builder().addGraphNode(copy.firstInsert() + i);
				}
				final Sample insertSample = insertMeter.stop();
				final OnHeapHnswGraph swapped = copy.builder().getCompletedGraph();
				final long swapStart = System.nanoTime();
				current.set(swapped);
				final long swapNs = System.nanoTime() - swapStart;
				final double recall = luceneRecall(base, swapped, copy.newToOld(), queries, exact);
				report("Lucene", batch, deletes, copySample, insertSample, swapNs, live.ramBytesUsed(),
					swapped.ramBytesUsed(), recall);
				current.set(live);
			}
		}
	}

	/**
	 * Copies the live Lucene graph into a graph sized for the batch, dropping `batch` live nodes when asked. The copy
	 * is dense like the live graph: the surviving live nodes keep their order and are renumbered from 0, the batch
	 * follows them.
	 *
	 * @param base    the corpus
	 * @param live    the live graph, node `i` being corpus ordinal `order[i]`
	 * @param order   the shuffled ordinals
	 * @param initial how many of them form the live graph
	 * @param batch   how many records the batch inserts
	 * @param deletes whether the batch also deletes `batch` live records
	 * @return the builder over the copy and its ordinal map
	 * @throws IOException when the copy fails
	 */
	@Nonnull
	private static LuceneCopy luceneCopy(
		@Nonnull MappedVectors base,
		@Nonnull OnHeapHnswGraph live,
		@Nonnull int[] order,
		int initial,
		int batch,
		boolean deletes
	) throws IOException {
		final boolean[] dropped = new boolean[initial];
		if (deletes) {
			final int[] liveOrdinals = new int[initial];
			Arrays.setAll(liveOrdinals, i -> i);
			for (final int node : pick(liveOrdinals, batch, new Random(44L + batch))) {
				dropped[node] = true;
			}
		}
		final int[] ordMap = new int[initial];
		final int[] newToOld = new int[initial + batch];
		int next = 0;
		for (int liveOrdinal = 0; liveOrdinal < initial; liveOrdinal++) {
			if (dropped[liveOrdinal]) {
				ordMap[liveOrdinal] = -1;
			} else {
				ordMap[liveOrdinal] = next;
				newToOld[next++] = order[liveOrdinal];
			}
		}
		final int copied = next;
		for (int i = 0; i < batch; i++) {
			newToOld[copied + i] = order[initial + i];
		}
		final int size = copied + batch;
		final FixedBitSet initialized = new FixedBitSet(size);
		initialized.set(0, copied);
		final RandomVectorScorerSupplier supplier = SpikeIndexes.flatScorer().getRandomVectorScorerSupplier(
			SpikeIndexes.VSF, new RemappedFloats(base, newToOld, size)
		);
		final InitializedHnswGraphBuilder builder = InitializedHnswGraphBuilder.fromGraph(
			supplier, SpikeIndexes.BEAM, SpikeIndexes.LUCENE_SEED, live, ordMap, initialized, size
		);
		return new LuceneCopy(builder, copied, Arrays.copyOf(newToOld, size));
	}

	/**
	 * Recall@10 of a swapped-in Lucene graph against the exact scan over its nodes.
	 *
	 * @param base     the corpus
	 * @param graph    the graph
	 * @param newToOld the corpus ordinal of each graph ordinal
	 * @param queries  the queries
	 * @param exact    the exact scorer
	 * @return the mean recall@10
	 * @throws IOException when a scorer fails
	 */
	private static double luceneRecall(
		@Nonnull MappedVectors base,
		@Nonnull OnHeapHnswGraph graph,
		@Nonnull int[] newToOld,
		@Nonnull float[][] queries,
		@Nonnull ExactScorer exact
	) throws IOException {
		final boolean[] present = new boolean[base.count()];
		final HnswGraph.NodesIterator nodes = graph.getNodesOnLevel(0);
		while (nodes.hasNext()) {
			present[newToOld[nodes.nextInt()]] = true;
		}
		final Filter liveFilter = Filter.of(present, PrimaryKeyOrdinals.identity(present.length));
		final FloatVectorValues values = new RemappedFloats(base, newToOld, newToOld.length);
		double sum = 0;
		for (final float[] query : queries) {
			final int[] walked = ClickRecallScenario.luceneWalk(
				SpikeIndexes.flatScorer().getRandomVectorScorer(SpikeIndexes.VSF, values.copy(), query), graph, null,
				0, Integer.MAX_VALUE, 0, new ClickRecallScenario.WalkStats()
			);
			final int[] result = new int[walked.length];
			for (int i = 0; i < walked.length; i++) {
				result[i] = newToOld[walked[i]];
			}
			sum += recall(query, result, liveFilter, exact);
		}
		return sum / queries.length;
	}

	/**
	 * The jVector half.
	 *
	 * @param base    the corpus
	 * @param order   the shuffled ordinals
	 * @param initial how many of them form the live graph
	 * @param batches the batch sizes
	 * @param queries the queries of the recall check
	 * @param exact   the exact scorer
	 * @throws Exception when a graph operation fails
	 */
	private static void jvector(
		@Nonnull MappedVectors base,
		@Nonnull int[] order,
		int initial,
		@Nonnull int[] batches,
		@Nonnull float[][] queries,
		@Nonnull ExactScorer exact
	) throws Exception {
		final MappedRavv ravv = new MappedRavv(base);
		final BuildScoreProvider scores = BuildScoreProvider.randomAccessScoreProvider(ravv, SpikeIndexes.JVSF);
		try (final GraphIndexBuilder builder = JVectorFootprintScenarios.newBuilder(
			ravv, SpikeIndexes.DEFAULT_ALPHA, true
		)) {
			final long start = System.nanoTime();
			final ThreadLocal<MappedRavv> ravvs = ThreadLocal.withInitial(() -> new MappedRavv(base));
			PhysicalCoreExecutor.pool().submit(() -> IntStream.range(0, initial).parallel()
				.forEach(i -> builder.addGraphNode(order[i], ravvs.get()))).get();
			builder.cleanup();
			final long liveBytes = ((OnHeapGraphIndex) builder.getGraph()).ramBytesUsed();
			line(String.format("jVector: live graph of %d nodes built on all cores in %d ms, %s", initial,
				(System.nanoTime() - start) / 1_000_000, VectorMemoryProbe.mb(liveBytes)));
			final AtomicReference<ImmutableGraphIndex> current = new AtomicReference<>(builder.getGraph());
			// the first copy warms the code up and is not reported
			GraphIndexBuilder.rescore(builder, scores).close();
			for (final int batch : withZero(batches)) {
				for (final boolean deletes : batch == 0 ? new boolean[]{false} : new boolean[]{false, true}) {
					final Meter copyMeter = Meter.start();
					final GraphIndexBuilder copy = GraphIndexBuilder.rescore(builder, scores);
					if (deletes) {
						for (final int node : pick(Arrays.copyOf(order, initial), batch, new Random(44L + batch))) {
							copy.markNodeDeleted(node);
						}
						// the copy is private, so the cleanup's need for a quiescent graph costs nothing here
						copy.cleanup();
					}
					final Sample copySample = copyMeter.stop();
					final Meter insertMeter = Meter.start();
					final MappedRavv writerRavv = new MappedRavv(base);
					for (int i = 0; i < batch; i++) {
						copy.addGraphNode(order[initial + i], writerRavv);
					}
					final Sample insertSample = insertMeter.stop();
					final ImmutableGraphIndex swapped = copy.getGraph();
					final long swapStart = System.nanoTime();
					current.set(swapped);
					final long swapNs = System.nanoTime() - swapStart;
					final double recall = jvectorRecall(base, ravv, swapped, queries, exact);
					report("jVector", batch, deletes, copySample, insertSample, swapNs, liveBytes,
						((OnHeapGraphIndex) swapped).ramBytesUsed(), recall);
					current.set(builder.getGraph());
					copy.close();
				}
			}
		}
	}

	/**
	 * Recall@10 of a swapped-in jVector graph against the exact scan over its live nodes.
	 *
	 * @param base    the corpus
	 * @param ravv    the vectors
	 * @param graph   the graph
	 * @param queries the queries
	 * @param exact   the exact scorer
	 * @return the mean recall@10
	 * @throws Exception when the view cannot be closed
	 */
	static double jvectorRecall(
		@Nonnull MappedVectors base,
		@Nonnull MappedRavv ravv,
		@Nonnull ImmutableGraphIndex graph,
		@Nonnull float[][] queries,
		@Nonnull ExactScorer exact
	) throws Exception {
		final EvitaGraphSearcher own = new EvitaGraphSearcher();
		double sum = 0;
		try (final ImmutableGraphIndex.View view = graph.getView()) {
			final GraphView ours = GraphView.ofJVector(graph, view);
			final boolean[] present = new boolean[base.count()];
			for (final int node : ours.nodesOnLevel(0)) {
				present[node] = ours.isLive(node);
			}
			final Filter liveFilter = Filter.of(present, PrimaryKeyOrdinals.identity(present.length));
			for (final float[] query : queries) {
				final int[] result = own.search(ours, NodeScorer.ofJVector(
					ravv.rerankerFor(VTS.createFloatVector(query), SpikeIndexes.JVSF)), null, CANDIDATES, false,
					Integer.MAX_VALUE, EvitaGraphSearcher.NEVER_CANCELLED);
				sum += recall(query, result, liveFilter, exact);
			}
		}
		return sum / queries.length;
	}

	/**
	 * Prints one measured shape.
	 *
	 * @param library     the library name
	 * @param batch       the batch size, 0 for the copy alone
	 * @param deletes     whether the batch also deleted records
	 * @param copy        the copy phase, deletion included
	 * @param inserts     the insertion phase
	 * @param swapNs      the reference write of the swap
	 * @param liveBytes   `ramBytesUsed()` of the live graph
	 * @param copyBytes   `ramBytesUsed()` of the swapped-in graph
	 * @param recall      recall@10 of the swapped-in graph
	 */
	private static void report(
		@Nonnull String library,
		int batch,
		boolean deletes,
		@Nonnull Sample copy,
		@Nonnull Sample inserts,
		long swapNs,
		long liveBytes,
		long copyBytes,
		double recall
	) {
		final String shape = batch == 0
			? "copy only" : deletes ? "copy + " + batch + " deletes + " + batch + " inserts"
			: "copy + " + batch + " inserts";
		line(String.format(
			"%s, %s: copy %d ms (alloc %s, GC %d / %d ms), inserts %d ms (%.3f ms each, alloc %s, GC %d / %d ms), "
				+ "swap %d ns; total %d ms; graphs on heap during the swap %s + %s; recall@10 %.3f",
			library, shape, copy.millis(), VectorMemoryProbe.mb(copy.allocated()), copy.collections(),
			copy.collectionMillis(), inserts.millis(), batch == 0 ? 0.0 : inserts.nanos() / 1e6 / batch,
			VectorMemoryProbe.mb(inserts.allocated()), inserts.collections(), inserts.collectionMillis(), swapNs,
			copy.millis() + inserts.millis(), VectorMemoryProbe.mb(liveBytes), VectorMemoryProbe.mb(copyBytes), recall
		));
	}

	/**
	 * @param query  the query
	 * @param result returned corpus ordinals, best first
	 * @param live   the live records
	 * @param exact  the exact scorer
	 * @return recall@10 of the result against the exact scan over the live records, tie-robust
	 */
	static double recall(@Nonnull float[] query, @Nonnull int[] result, @Nonnull Filter live,
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
	 * @param batches the batch sizes
	 * @return 0 (the copy alone) followed by the batch sizes
	 */
	@Nonnull
	private static int[] withZero(@Nonnull int[] batches) {
		final int[] result = new int[batches.length + 1];
		System.arraycopy(batches, 0, result, 1, batches.length);
		return result;
	}

	/**
	 * @param n number of ordinals
	 * @return the ordinals `0..n-1` shuffled with the churn protocol's seed
	 */
	@Nonnull
	static int[] shuffledOrder(int n) {
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
	 * @param from   the ordinals to pick from
	 * @param count  how many to pick
	 * @param random the random source
	 * @return `count` distinct ordinals of `from`
	 */
	@Nonnull
	static int[] pick(@Nonnull int[] from, int count, @Nonnull Random random) {
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
	 * A Lucene copy in progress.
	 *
	 * @param builder     the builder over the copy
	 * @param firstInsert the ordinal of the first inserted record in the copy
	 * @param newToOld    the corpus ordinal of each ordinal of the copy
	 */
	private record LuceneCopy(
		@Nonnull InitializedHnswGraphBuilder builder,
		int firstInsert,
		@Nonnull int[] newToOld
	) {
	}

	/**
	 * Lucene `FloatVectorValues` over the mapped corpus seen through an ordinal map, for a dense graph over a subset
	 * of the corpus.
	 */
	private static final class RemappedFloats extends FloatVectorValues {
		/** The mapped raw block. */
		private final MappedVectors vectors;
		/** The corpus ordinal of each graph ordinal. */
		private final int[] newToOld;
		/** Number of graph ordinals. */
		private final int size;
		/** Scratch receiving the vector currently handed out. */
		private final float[] scratch;

		/**
		 * @param vectors  the mapped raw block
		 * @param newToOld the corpus ordinal of each graph ordinal
		 * @param size     number of graph ordinals
		 */
		RemappedFloats(@Nonnull MappedVectors vectors, @Nonnull int[] newToOld, int size) {
			this.vectors = vectors;
			this.newToOld = newToOld;
			this.size = size;
			this.scratch = new float[vectors.dimension()];
		}

		@Override
		public int size() {
			return this.size;
		}

		@Override
		public int dimension() {
			return this.vectors.dimension();
		}

		@Override
		public FloatVectorValues copy() {
			return new RemappedFloats(this.vectors, this.newToOld, this.size);
		}

		@Override
		public float[] vectorValue(int targetOrd) {
			this.vectors.read(this.newToOld[targetOrd], this.scratch);
			return this.scratch;
		}

		@Override
		public VectorEncoding getEncoding() {
			return VectorEncoding.FLOAT32;
		}
	}

	/**
	 * One measured phase.
	 *
	 * @param nanos            wall time
	 * @param allocated        bytes allocated by all threads
	 * @param collections      collections of every collector
	 * @param collectionMillis collection time of every collector
	 */
	record Sample(long nanos, long allocated, long collections, long collectionMillis) {
		/**
		 * @return the wall time in milliseconds
		 */
		long millis() {
			return this.nanos / 1_000_000;
		}
	}

	/**
	 * The start of a measured phase: the clock, the allocation counters of all threads and the collector counters.
	 *
	 * @param nanos            the clock at the start
	 * @param allocated        bytes allocated by all threads so far
	 * @param collections      collections so far
	 * @param collectionMillis collection time so far
	 */
	record Meter(long nanos, long allocated, long collections, long collectionMillis) {
		/**
		 * @return a meter started now
		 */
		@Nonnull
		static Meter start() {
			final long[] gc = gcCounters();
			return new Meter(System.nanoTime(), allocatedByAllThreads(), gc[0], gc[1]);
		}

		/**
		 * @return the phase from the start until now
		 */
		@Nonnull
		Sample stop() {
			final long now = System.nanoTime();
			final long allocatedNow = allocatedByAllThreads();
			final long[] gc = gcCounters();
			return new Sample(now - this.nanos, allocatedNow - this.allocated, gc[0] - this.collections,
				gc[1] - this.collectionMillis);
		}

		/**
		 * @return bytes allocated so far by all live threads; the threads of the shared pools outlive the phases
		 */
		private static long allocatedByAllThreads() {
			final com.sun.management.ThreadMXBean threads =
				(com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
			long sum = 0;
			for (final long bytes : threads.getThreadAllocatedBytes(threads.getAllThreadIds())) {
				if (bytes > 0) {
					sum += bytes;
				}
			}
			return sum;
		}

		/**
		 * @return collections and collection milliseconds so far, summed over every collector
		 */
		@Nonnull
		private static long[] gcCounters() {
			final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
			long count = 0;
			long millis = 0;
			for (final GarbageCollectorMXBean collector : collectors) {
				count += Math.max(0, collector.getCollectionCount());
				millis += Math.max(0, collector.getCollectionTime());
			}
			return new long[]{count, millis};
		}
	}
}
