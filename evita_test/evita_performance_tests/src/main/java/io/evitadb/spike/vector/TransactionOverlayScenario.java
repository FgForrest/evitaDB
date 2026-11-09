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
import io.evitadb.spike.vector.EvitaGraphSearcher.NodeScorer;
import io.evitadb.spike.vector.GraphSwapScenario.Meter;
import io.evitadb.spike.vector.GraphSwapScenario.Sample;
import io.evitadb.spike.vector.JVectorFootprintScenarios.MappedRavv;
import io.github.jbellis.jvector.graph.GraphIndexBuilder;
import io.github.jbellis.jvector.graph.ImmutableGraphIndex;
import io.github.jbellis.jvector.graph.OnHeapGraphIndex;
import io.github.jbellis.jvector.graph.similarity.BuildScoreProvider;
import io.github.jbellis.jvector.util.PhysicalCoreExecutor;
import org.apache.lucene.util.quantization.QuantizedByteVectorValues.ScalarEncoding;
import org.apache.lucene.util.VectorUtil;
import org.apache.lucene.util.hnsw.RandomVectorScorer;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.stream.IntStream;

import static io.evitadb.spike.vector.VectorMemoryProbe.line;
import static io.evitadb.spike.vector.VectorMemoryProbe.section;

/**
 * Two follow-ups of the copy-and-swap measurement, both about keeping a transaction's changes out of the shared
 * graph:
 *
 * - {@link #overlay}: one shared jVector graph and a transaction that has written `k` vectors of its own and deleted
 *   `d` records. The written vectors are not inserted into the graph: every search of the transaction walks the
 *   shared graph (our searcher, Lucene OSQ 4-bit, exact rerank at 4×) with the deleted records excluded by a bitmap,
 *   scans its own `k` vectors exactly, and merges both by exact score. Recall@10 is measured against the exact scan
 *   over what the transaction sees: the graph's records minus its deletes plus its own vectors.
 * - {@link #deleteRepair}: what jVector's `cleanup()` spends on deletes. On a `rescore` copy of the live graph, the
 *   deletes are repaired three ways: `cleanup()` of a builder with `refineFinalGraph` on (the library default, used
 *   by the swap measurement), `removeDeletedNodes()` alone on the same builder, and `cleanup()` of a builder with
 *   `refineFinalGraph` off, whose live graph is built separately.
 *
 * The graph starts from 90 % of the corpus (ordinals shuffled with the churn protocol's seed); the remaining 10 % are
 * the transaction's own vectors. Timings are taken in the harness on one thread with `System.nanoTime`, not by JMH,
 * the same basis as the churn readers' percentiles.
 *
 * @author Lukáš Hornych (hornych@fg.cz)
 */
public final class TransactionOverlayScenario {
	/** Sizes of the transaction's own written vectors, capped at the reserve. */
	private static final int[] OVERLAY_SIZES = {0, 1, 10, 100, 1_000, 10_000};
	/** Share of the graph's records the transaction deletes in the second half of the matrix. */
	private static final double DELETED_SHARE = 0.01;
	/** Deletes repaired by the repair variants. */
	private static final int[] REPAIR_DELETES = {1, 1_000, 10_000};
	/** Queries searched per cell. */
	private static final int QUERIES = 500;
	/** Queries of the recall check after a repair. */
	private static final int REPAIR_QUERIES = 100;
	/** Number of records the user sees. */
	private static final int TOP_K = ClickRecallScenario.TOP_K;
	/** Depth of the walk. */
	private static final int CANDIDATES = ClickRecallScenario.CANDIDATES;
	/** Candidates reranked exactly, as in the combination's measurements. */
	private static final int RERANK = 4 * TOP_K;

	private TransactionOverlayScenario() {
		throw new UnsupportedOperationException("Utility class, not to be instantiated.");
	}

	/**
	 * Searches of a transaction over the shared graph plus its own vectors and deletes.
	 *
	 * @param base    mapped corpus vectors
	 * @param workDir directory holding the click queries and the code files
	 * @throws Exception when an input cannot be read or a graph operation fails
	 */
	public static void overlay(@Nonnull MappedVectors base, @Nonnull Path workDir) throws Exception {
		section("Transaction overlay: shared jVector graph + own vectors scanned exactly + own deletes as a bitmap");
		final float[][] allQueries = ClickRecallScenario.loadQueries(workDir, base.dimension());
		final float[][] queries = Arrays.copyOf(allQueries, Math.min(QUERIES, allQueries.length));
		final int n = base.count();
		final int[] order = GraphSwapScenario.shuffledOrder(n);
		final int initial = n - n / 10;
		final int[] sizes = IntStream.of(OVERLAY_SIZES).filter(k -> k <= n - initial).toArray();
		final int deletes = (int) (initial * DELETED_SHARE);
		final IoFunction<float[], RandomVectorScorer> osq4 =
			SpikeIndexes.luceneOsq(SpikeIndexes.osqCodes(workDir, base, ScalarEncoding.PACKED_NIBBLE));
		line(String.format("corpus %d vectors; shared graph %d nodes; own vectors %s; own deletes 0 and %d; "
				+ "queries %d; walk OSQ 4-bit, exact rerank of %d; own vectors scored float32 by Lucene's "
				+ "VectorUtil.dotProduct on the heap", n, initial, Arrays.toString(sizes), deletes, queries.length,
			RERANK));
		final ExactScorer exact = new ExactScorer(base);
		try (final GraphIndexBuilder builder = buildLive(base, order, initial, true)) {
			final ImmutableGraphIndex graph = builder.getGraph();
			final int[] graphNodes = Arrays.copyOf(order, initial);
			final EvitaGraphSearcher own = new EvitaGraphSearcher();
			for (final int d : new int[]{0, deletes}) {
				final RoaringBitmap deleted = RoaringBitmap.bitmapOf(
					GraphSwapScenario.pick(graphNodes, d, new Random(45L)));
				deleted.runOptimize();
				@Nullable final Acceptor accept = d == 0 ? null : notIn(deleted, initial - d, n);
				for (final int k : sizes) {
					final int[] ownOrdinals = Arrays.copyOfRange(order, initial, initial + k);
					final float[][] ownVectors = new float[k][base.dimension()];
					for (int i = 0; i < k; i++) {
						base.read(ownOrdinals[i], ownVectors[i]);
					}
					final Filter visible = visible(n, graphNodes, deleted, ownOrdinals);
					try (final ImmutableGraphIndex.View view = graph.getView()) {
						final GraphView ours = GraphView.ofJVector(graph, view);
						final Cell cell = new Cell(queries.length);
						// a warm-up pass, then the measured one
						for (final boolean measured : new boolean[]{false, true}) {
							for (int q = 0; q < queries.length; q++) {
								final float[] query = queries[q];
								final long start = System.nanoTime();
								final int[] walked = exact.rerank(query, own.search(ours,
									NodeScorer.ofLucene(osq4.apply(query)), accept, CANDIDATES, false,
									Integer.MAX_VALUE, EvitaGraphSearcher.NEVER_CANCELLED), RERANK);
								final long walkEnd = System.nanoTime();
								final int[] ownBest = scanOwn(query, ownVectors, ownOrdinals);
								final long scanEnd = System.nanoTime();
								final int[] merged = merge(query, walked, ownBest, ownVectors, ownOrdinals, exact);
								final long end = System.nanoTime();
								if (measured) {
									cell.add(q, walkEnd - start, scanEnd - walkEnd, end - start);
									cell.recall += GraphSwapScenario.recall(query, merged, visible, exact);
								}
							}
						}
						line(String.format(
							"own deletes %d, own vectors %d: walk + rerank p50 %.3f / p95 %.3f ms, own scan p50 %.3f "
								+ "/ p95 %.3f ms, total p50 %.3f / p95 %.3f ms; recall@10 %.3f; transaction holds %s "
								+ "of vectors and a %s delete bitmap",
							d, k, Cell.ms(cell.walk, 50), Cell.ms(cell.walk, 95), Cell.ms(cell.scan, 50),
							Cell.ms(cell.scan, 95), Cell.ms(cell.total, 50), Cell.ms(cell.total, 95),
							cell.recall / queries.length,
							VectorMemoryProbe.mb((long) k * base.dimension() * Float.BYTES),
							VectorMemoryProbe.mb(deleted.getSizeInBytes())
						));
					}
				}
			}
		}
	}

	/**
	 * Repairs of jVector deletes on a private copy: `cleanup()` with and without the refinement pass, and
	 * `removeDeletedNodes()` alone.
	 *
	 * @param base    mapped corpus vectors
	 * @param workDir directory holding the click queries
	 * @throws Exception when an input cannot be read or a graph operation fails
	 */
	public static void deleteRepair(@Nonnull MappedVectors base, @Nonnull Path workDir) throws Exception {
		section("jVector delete repair on a rescore copy: cleanup() with and without refinement, "
			+ "removeDeletedNodes() alone");
		final float[][] allQueries = ClickRecallScenario.loadQueries(workDir, base.dimension());
		final float[][] queries = Arrays.copyOf(allQueries, Math.min(REPAIR_QUERIES, allQueries.length));
		final int n = base.count();
		final int[] order = GraphSwapScenario.shuffledOrder(n);
		final int initial = n - n / 10;
		final int[] graphNodes = Arrays.copyOf(order, initial);
		line(String.format("corpus %d vectors; live graph %d nodes; deletes %s; recall queries %d", n, initial,
			Arrays.toString(REPAIR_DELETES), queries.length));
		final ExactScorer exact = new ExactScorer(base);
		final MappedRavv ravv = new MappedRavv(base);
		final BuildScoreProvider scores = BuildScoreProvider.randomAccessScoreProvider(ravv, SpikeIndexes.JVSF);
		for (final boolean refine : new boolean[]{true, false}) {
			try (final GraphIndexBuilder builder = buildLive(base, order, initial, refine)) {
				line(String.format("refineFinalGraph=%s: live graph recall@10 %.3f", refine,
					GraphSwapScenario.jvectorRecall(base, ravv, builder.getGraph(), queries, exact)));
				// the first copy warms the code up and is not reported
				GraphIndexBuilder.rescore(builder, scores).close();
				for (final int d : REPAIR_DELETES) {
					for (final boolean cleanup : refine ? new boolean[]{true, false} : new boolean[]{true}) {
						final Meter copyMeter = Meter.start();
						final GraphIndexBuilder copy = GraphIndexBuilder.rescore(builder, scores);
						final Sample copySample = copyMeter.stop();
						for (final int node : GraphSwapScenario.pick(graphNodes, d, new Random(44L + d))) {
							copy.markNodeDeleted(node);
						}
						final Meter repairMeter = Meter.start();
						if (cleanup) {
							copy.cleanup();
						} else {
							copy.removeDeletedNodes();
						}
						final Sample repair = repairMeter.stop();
						line(String.format(
							"refineFinalGraph=%s, %d deletes, %s: copy %d ms (alloc %s), repair %d ms (alloc %s, "
								+ "GC %d / %d ms); recall@10 after %.3f",
							refine, d, cleanup ? "cleanup()" : "removeDeletedNodes()", copySample.millis(),
							VectorMemoryProbe.mb(copySample.allocated()), repair.millis(),
							VectorMemoryProbe.mb(repair.allocated()), repair.collections(), repair.collectionMillis(),
							GraphSwapScenario.jvectorRecall(base, ravv, copy.getGraph(), queries, exact)
						));
						copy.close();
					}
				}
			}
		}
	}

	/**
	 * Builds the live jVector graph over the first `initial` shuffled ordinals, on all cores, and cleans it up.
	 *
	 * @param base    the corpus
	 * @param order   the shuffled ordinals
	 * @param initial how many of them form the graph
	 * @param refine  jVector's `refineFinalGraph`
	 * @return the builder holding the graph
	 * @throws Exception when the build fails
	 */
	@Nonnull
	private static GraphIndexBuilder buildLive(
		@Nonnull MappedVectors base,
		@Nonnull int[] order,
		int initial,
		boolean refine
	) throws Exception {
		final long start = System.nanoTime();
		final GraphIndexBuilder builder = JVectorFootprintScenarios.newBuilder(
			new MappedRavv(base), SpikeIndexes.DEFAULT_ALPHA, true, refine
		);
		final ThreadLocal<MappedRavv> ravvs = ThreadLocal.withInitial(() -> new MappedRavv(base));
		PhysicalCoreExecutor.pool().submit(() -> IntStream.range(0, initial).parallel()
			.forEach(i -> builder.addGraphNode(order[i], ravvs.get()))).get();
		builder.cleanup();
		line(String.format("jVector graph of %d nodes, refineFinalGraph=%s, built on all cores in %d ms, %s",
			initial, refine, (System.nanoTime() - start) / 1_000_000,
			VectorMemoryProbe.mb(((OnHeapGraphIndex) builder.getGraph()).ramBytesUsed())));
		return builder;
	}

	/**
	 * @param deleted the transaction's deleted ordinals
	 * @param count   the records the walk may return
	 * @param length  the ordinal space
	 * @return an acceptor of every ordinal not in the bitmap: the walk tests the transaction's deletes only, the
	 * shared graph holds no other record
	 */
	@Nonnull
	private static Acceptor notIn(@Nonnull RoaringBitmap deleted, int count, int length) {
		return new Acceptor() {
			@Override
			public boolean accept(int ordinal) {
				return !deleted.contains(ordinal);
			}

			@Override
			public int count() {
				return count;
			}

			@Override
			public int length() {
				return length;
			}
		};
	}

	/**
	 * @param n           the ordinal space
	 * @param graphNodes  the graph's records
	 * @param deleted     the transaction's deletes
	 * @param ownOrdinals the corpus ordinals of the transaction's own vectors
	 * @return what the transaction sees, for the exact scan of the recall
	 */
	@Nonnull
	private static Filter visible(
		int n,
		@Nonnull int[] graphNodes,
		@Nonnull RoaringBitmap deleted,
		@Nonnull int[] ownOrdinals
	) {
		final boolean[] accept = new boolean[n];
		for (final int node : graphNodes) {
			accept[node] = !deleted.contains(node);
		}
		for (final int node : ownOrdinals) {
			accept[node] = true;
		}
		return Filter.of(accept, PrimaryKeyOrdinals.identity(n));
	}

	/**
	 * Exact scan of the transaction's own vectors.
	 *
	 * @param query       the query
	 * @param ownVectors  the transaction's vectors on the heap
	 * @param ownOrdinals their corpus ordinals
	 * @return indexes into `ownVectors` of the best {@link #TOP_K}, best first
	 */
	@Nonnull
	private static int[] scanOwn(@Nonnull float[] query, @Nonnull float[][] ownVectors, @Nonnull int[] ownOrdinals) {
		final int depth = Math.min(TOP_K, ownVectors.length);
		final int[] best = new int[depth];
		final float[] bestScores = new float[depth];
		int size = 0;
		for (int i = 0; i < ownVectors.length; i++) {
			final float score = VectorUtil.dotProduct(query, ownVectors[i]);
			if (size < depth) {
				size++;
			} else if (score <= bestScores[depth - 1]) {
				continue;
			}
			// insertion into the sorted array: it holds ten entries
			int j = size - 1;
			while (j > 0 && bestScores[j - 1] < score) {
				best[j] = best[j - 1];
				bestScores[j] = bestScores[j - 1];
				j--;
			}
			best[j] = i;
			bestScores[j] = score;
		}
		return best;
	}

	/**
	 * Merges the reranked walk with the transaction's own best vectors by exact score.
	 *
	 * @param query       the query
	 * @param walked      the walk's records reranked exactly, best first
	 * @param ownBest     indexes into `ownVectors` of the transaction's best, best first
	 * @param ownVectors  the transaction's vectors
	 * @param ownOrdinals their corpus ordinals
	 * @param exact       the exact scorer of the shared records
	 * @return the best {@link #TOP_K} corpus ordinals, best first
	 */
	@Nonnull
	private static int[] merge(
		@Nonnull float[] query,
		@Nonnull int[] walked,
		@Nonnull int[] ownBest,
		@Nonnull float[][] ownVectors,
		@Nonnull int[] ownOrdinals,
		@Nonnull ExactScorer exact
	) {
		final int[] merged = new int[Math.min(TOP_K, walked.length + ownBest.length)];
		int w = 0;
		int o = 0;
		float walkedScore = w < walked.length ? exact.score(query, walked[w]) : Float.NEGATIVE_INFINITY;
		float ownScore = o < ownBest.length ? VectorUtil.dotProduct(query, ownVectors[ownBest[o]])
			: Float.NEGATIVE_INFINITY;
		for (int i = 0; i < merged.length; i++) {
			if (walkedScore >= ownScore) {
				merged[i] = walked[w++];
				walkedScore = w < walked.length ? exact.score(query, walked[w]) : Float.NEGATIVE_INFINITY;
			} else {
				merged[i] = ownOrdinals[ownBest[o++]];
				ownScore = o < ownBest.length ? VectorUtil.dotProduct(query, ownVectors[ownBest[o]])
					: Float.NEGATIVE_INFINITY;
			}
		}
		return merged;
	}

	/**
	 * The timings and the recall sum of one cell of the matrix.
	 */
	private static final class Cell {
		/** Walk and rerank of each query, nanoseconds. */
		private final long[] walk;
		/** Scan of the own vectors of each query, nanoseconds. */
		private final long[] scan;
		/** Whole search of each query, nanoseconds. */
		private final long[] total;
		/** Sum of the queries' recall@10. */
		private double recall;

		/**
		 * @param queries number of queries
		 */
		Cell(int queries) {
			this.walk = new long[queries];
			this.scan = new long[queries];
			this.total = new long[queries];
		}

		/**
		 * @param query     the query index
		 * @param walkNanos walk and rerank
		 * @param scanNanos scan of the own vectors
		 * @param totalNanos whole search
		 */
		void add(int query, long walkNanos, long scanNanos, long totalNanos) {
			this.walk[query] = walkNanos;
			this.scan[query] = scanNanos;
			this.total[query] = totalNanos;
		}

		/**
		 * @param nanos      per-query timings
		 * @param percentile the percentile
		 * @return the percentile in milliseconds
		 */
		static double ms(@Nonnull long[] nanos, int percentile) {
			final long[] sorted = nanos.clone();
			Arrays.sort(sorted);
			final int index = Math.min(sorted.length - 1, (int) Math.ceil(percentile / 100.0 * sorted.length) - 1);
			return sorted[Math.max(0, index)] / 1e6;
		}
	}
}
