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

import io.github.jbellis.jvector.disk.BufferedRandomAccessWriter;
import io.github.jbellis.jvector.disk.ReaderSupplier;
import io.github.jbellis.jvector.disk.ReaderSupplierFactory;
import io.github.jbellis.jvector.graph.GraphIndexBuilder;
import io.github.jbellis.jvector.graph.GraphSearcher;
import io.github.jbellis.jvector.graph.ImmutableGraphIndex;
import io.github.jbellis.jvector.graph.ListRandomAccessVectorValues;
import io.github.jbellis.jvector.graph.RandomAccessVectorValues;
import io.github.jbellis.jvector.graph.SearchResult;
import io.github.jbellis.jvector.graph.disk.OnDiskGraphIndex;
import io.github.jbellis.jvector.graph.similarity.DefaultSearchScoreProvider;
import io.github.jbellis.jvector.quantization.BinaryQuantization;
import io.github.jbellis.jvector.quantization.CompressedVectors;
import io.github.jbellis.jvector.quantization.ProductQuantization;
import io.github.jbellis.jvector.util.Bits;
import io.github.jbellis.jvector.util.PhysicalCoreExecutor;
import io.github.jbellis.jvector.vector.VectorSimilarityFunction;
import io.github.jbellis.jvector.vector.VectorizationProvider;
import io.github.jbellis.jvector.vector.types.VectorFloat;
import io.github.jbellis.jvector.vector.types.VectorTypeSupport;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static io.evitadb.spike.vector.VectorMemoryProbe.*;

/**
 * jVector scenarios of the vector memory spike: raw vectors on the heap versus mapped during build, the on-heap
 * graph, PQ and BQ traversal codes, and the on-disk index reopened through the library's own reader factory.
 *
 * The builder is created through the deprecated explicit-parameter constructor on purpose: the non-deprecated
 * fluent builder reads `addHierarchy` and `refineFinalGraph` from the process-global, JMX-mutable
 * `GraphIndexBuilderConfig` (P6 specification §3.2), and a measurement must state its structural parameters.
 *
 * @author Lukáš Hornych (hornych@fg.cz)
 */
public final class JVectorFootprintScenarios {
	/** Vector factory of the selected vectorization provider. */
	private static final VectorTypeSupport VTS = VectorizationProvider.getInstance().getVectorTypeSupport();
	/** Similarity used everywhere; the corpus vectors are unit length. */
	private static final VectorSimilarityFunction VSF = VectorSimilarityFunction.DOT_PRODUCT;
	/** Base-layer maximum degree; Lucene's `M = 16` yields the same 32 on level 0. */
	private static final int DEGREE = 32;
	/** Construction beam width. */
	private static final int BEAM = 100;
	/** PQ subspaces: 256 subspaces of 8 dimensions give 256 B per 2048-dimensional vector. */
	private static final int PQ_SUBSPACES = 256;
	/** Candidate queue depth of every search (the `ef` of HNSW terms); approximate searches rerank all of it. */
	private static final int RERANK_K = 100;
	/** Name of the on-disk index file. */
	private static final String ON_DISK_FILE = "jvector-inline.idx";

	private JVectorFootprintScenarios() {
		throw new UnsupportedOperationException("Utility class, not to be instantiated.");
	}

	/**
	 * jVector `RandomAccessVectorValues` over the mapped file. Each instance owns one scratch vector and reports
	 * its values as shared, so the library's per-thread copies cost one vector per building thread.
	 */
	static final class MappedRavv implements RandomAccessVectorValues {
		/** The mapped raw block. */
		private final MappedVectors vectors;
		/** Scratch receiving the vector currently handed out. */
		private final float[] scratch;
		/** jVector view wrapping `scratch` without a copy. */
		private final VectorFloat<?> scratchVector;

		/**
		 * @param vectors the mapped raw block
		 */
		MappedRavv(@Nonnull MappedVectors vectors) {
			this.vectors = vectors;
			this.scratch = new float[vectors.dimension()];
			this.scratchVector = VTS.createFloatVector(this.scratch);
		}

		@Override
		public int size() {
			return this.vectors.count();
		}

		@Override
		public int dimension() {
			return this.vectors.dimension();
		}

		@Override
		public VectorFloat<?> getVector(int nodeId) {
			this.vectors.read(nodeId, this.scratch);
			return this.scratchVector;
		}

		@Override
		public boolean isValueShared() {
			return true;
		}

		@Override
		public RandomAccessVectorValues copy() {
			return new MappedRavv(this.vectors);
		}
	}

	/**
	 * Builds the on-heap graph and measures every component. With mapped raw vectors it also measures PQ and BQ
	 * codes and writes the on-disk index that {@link #openOnDisk} reopens in a fresh JVM.
	 *
	 * @param base       mapped raw vectors
	 * @param truth      ground truth used for the sanity recall check
	 * @param workDir    directory receiving the on-disk index
	 * @param mappedRaw  true to read raw vectors from the mapping, false to copy them onto the heap first
	 * @throws IOException when the on-disk index cannot be written
	 */
	public static void build(
		@Nonnull MappedVectors base,
		@Nonnull VectorGroundTruth truth,
		@Nonnull Path workDir,
		boolean mappedRaw
	) throws IOException {
		section(
			"jVector build, raw vectors "
				+ (mappedRaw ? "MAPPED (own RAVV over FileChannel.map)" : "ON HEAP (ListRandomAccessVectorValues)")
		);
		line(
			"provider=" + VectorizationProvider.getInstance().getClass().getSimpleName()
				+ ", vectorType=" + VTS.createFloatVector(1).getClass().getSimpleName()
				+ ", GraphIndexBuilder(M=" + DEGREE + ", beam=" + BEAM
				+ ", overflow=1.2, alpha=1.2, addHierarchy=true, refineFinalGraph=true)"
		);
		final int n = base.count();
		final long heapBefore = heapUsedAfterGc();

		final RandomAccessVectorValues ravv;
		if (mappedRaw) {
			ravv = new MappedRavv(base);
		} else {
			final List<VectorFloat<?>> list = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				list.add(VTS.createFloatVector(base.copy(i)));
			}
			ravv = new ListRandomAccessVectorValues(list, base.dimension());
		}
		final long heapWithVectors = heapUsedAfterGc();
		size("raw vectors on heap (heap delta)", heapWithVectors - heapBefore, n);

		resetHeapPeak();
		final long start = System.nanoTime();
		final GraphIndexBuilder builder = newBuilder(ravv);
		final ImmutableGraphIndex graph = builder.build(ravv);
		line(
			"build " + (System.nanoTime() - start) / 1_000_000 + " ms (parallel), heap pool peak incl. garbage "
				+ mb(heapPeak())
		);
		size("graph.ramBytesUsed() (library estimate)", graph.ramBytesUsed(), n);
		size("JOL owned size of graph", ownedSize(graph, ravv), n);
		printGraphStats(graph);
		size("builder.ramBytesUsed() (graph + build scratch)", builder.ramBytesUsed(), n);
		final long heapAfterBuild = heapUsedAfterGc();
		size("heap delta of build, builder alive", heapAfterBuild - heapWithVectors, n);
		line(bufferPools());
		printMappings(base.path().getParent());
		printNativeMemory();

		final double recall = truth.recall(q -> {
			final VectorFloat<?> query = VTS.createFloatVector(truth.queries()[q]);
			return toOrdinals(GraphSearcher.search(query, 10, RERANK_K, ravv, VSF, graph, Bits.ALL));
		});
		line(String.format("sanity recall@10 (ef=%d), exact scores during the walk = %.4f", RERANK_K, recall));

		if (mappedRaw) {
			measureCodes(base, truth, workDir, ravv, graph);
			final Path file = workDir.resolve(ON_DISK_FILE);
			Files.deleteIfExists(file);
			OnDiskGraphIndex.write(graph, ravv, file);
			size("on-disk index file (graph + inline float32 vectors)", Files.size(file), n);
		}
		builder.close();
	}

	/**
	 * Measures PQ and BQ codes built over the mapped vectors and searches with each of them, reranking from the
	 * mapped block.
	 *
	 * @param base    mapped raw vectors
	 * @param truth   ground truth used for the sanity recall check
	 * @param workDir directory receiving the serialized PQ codes
	 * @param ravv    mapped vector values
	 * @param graph   the built graph
	 * @throws IOException when the PQ file cannot be written
	 */
	private static void measureCodes(
		@Nonnull MappedVectors base,
		@Nonnull VectorGroundTruth truth,
		@Nonnull Path workDir,
		@Nonnull RandomAccessVectorValues ravv,
		@Nonnull ImmutableGraphIndex graph
	) throws IOException {
		final int n = base.count();
		final long heapBefore = heapUsedAfterGc();
		final ProductQuantization pq = ProductQuantization.compute(ravv, PQ_SUBSPACES, 256, false);
		final CompressedVectors pqVectors = pq.encodeAll(ravv, PhysicalCoreExecutor.pool());
		final long heapWithPq = heapUsedAfterGc();
		size("PQ(" + PQ_SUBSPACES + "x8) codebook ramBytesUsed", pq.ramBytesUsed(), n);
		size("PQ codes ramBytesUsed", pqVectors.ramBytesUsed(), n);
		size("PQ heap delta (codebook + codes)", heapWithPq - heapBefore, n);
		line(String.format(
			"sanity recall@10, PQ walk + exact rerank of %d from mmap = %.4f",
			RERANK_K, truth.recall(q -> searchApproximate(truth.queries()[q], pqVectors, ravv, graph))
					));
		OversamplingLadder.run(
			"jvector-pq", base, truth, q -> approximateCandidates(truth.queries()[q], pqVectors, graph)
		);
		final Path pqFile = workDir.resolve("jvector-pq.codes");
		try (final BufferedRandomAccessWriter writer = new BufferedRandomAccessWriter(pqFile)) {
			pqVectors.write(writer);
		}
		size("PQ codes + codebook serialized", Files.size(pqFile), n);

		final CompressedVectors bqVectors = new BinaryQuantization(base.dimension())
			.encodeAll(ravv, PhysicalCoreExecutor.pool());
		final long heapWithBq = heapUsedAfterGc();
		size("BQ codes ramBytesUsed", bqVectors.ramBytesUsed(), n);
		size("BQ heap delta", heapWithBq - heapWithPq, n);
		line(String.format(
			"sanity recall@10, BQ walk + exact rerank of %d from mmap = %.4f",
			RERANK_K, truth.recall(q -> searchApproximate(truth.queries()[q], bqVectors, ravv, graph))
					));
		OversamplingLadder.run(
			"jvector-bq", base, truth, q -> approximateCandidates(truth.queries()[q], bqVectors, graph)
		);
	}

	/**
	 * Reopens the on-disk index written by {@link #build} — run it in a fresh JVM so nothing of the build is on
	 * the heap — and measures its heap cost and page-cache residency before and after queries.
	 *
	 * @param truth   ground truth of the same corpus
	 * @param workDir directory containing the on-disk index
	 * @param n       number of indexed vectors for per-vector figures
	 * @throws IOException when the index cannot be opened
	 */
	public static void openOnDisk(@Nonnull VectorGroundTruth truth, @Nonnull Path workDir, int n) throws IOException {
		section("jVector OnDiskGraphIndex reopened via ReaderSupplierFactory");
		final Path file = workDir.resolve(ON_DISK_FILE);
		final long heapBefore = heapUsedAfterGc();
		final ReaderSupplier readerSupplier = ReaderSupplierFactory.open(file);
		final OnDiskGraphIndex graph = OnDiskGraphIndex.load(readerSupplier);
		final long heapAfterLoad = heapUsedAfterGc();
		line("readerSupplier=" + readerSupplier.getClass().getName() + ", file " + mb(Files.size(file)));
		size("heap delta after load", heapAfterLoad - heapBefore, n);
		size("graph.ramBytesUsed()", graph.ramBytesUsed(), n);
		size("JOL owned size of index (reader supplier excluded)", ownedSize(graph, readerSupplier), n);
		line("after load: " + bufferPools());
		printMappings(workDir);

		// every ReaderSupplier#get() of MappedChunkReader maps the file anew; look while one view is alive
		try (final GraphSearcher searcher = new GraphSearcher(graph)) {
			final OnDiskGraphIndex.View view = (OnDiskGraphIndex.View) searcher.getView();
			final VectorFloat<?> query = VTS.createFloatVector(truth.queries()[0]);
			searcher.search(DefaultSearchScoreProvider.exact(query, VSF, view), 10, RERANK_K, 0.0f, 0.0f, Bits.ALL);
			line("one view open, after one query: " + bufferPools());
			printMappings(workDir);
		}
		line("view closed, before GC: " + bufferPools());
		heapUsedAfterGc();
		line("view closed, after GC: " + bufferPools());
		// walks every node record, so it runs after the residency probe above
		printGraphStats(graph);

		final double recall = truth.recall(q -> {
			// a fresh searcher (and so a fresh view) per query, as the library recommends for on-disk indexes
			try (final GraphSearcher searcher = new GraphSearcher(graph)) {
				final OnDiskGraphIndex.View view = (OnDiskGraphIndex.View) searcher.getView();
				final VectorFloat<?> query = VTS.createFloatVector(truth.queries()[q]);
				return toOrdinals(searcher.search(
					DefaultSearchScoreProvider.exact(query, VSF, view), 10, RERANK_K, 0.0f, 0.0f, Bits.ALL
				));
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
		line(String.format(
			"sanity recall@10 (ef=%d), exact scores from the mapped inline vectors = %.4f", RERANK_K, recall
		));
		size("heap delta after queries", heapUsedAfterGc() - heapBefore, n);
		line("after queries: " + bufferPools() + ", process RSS " + mb(processRss()));
		printMappings(workDir);
		printNativeMemory();
		graph.close();
		readerSupplier.close();
	}

	/**
	 * Walks the graph scoring with compressed codes only, without any rerank, so the {@link OversamplingLadder}
	 * can rerank the candidates itself.
	 *
	 * @param query the query vector
	 * @param codes the compressed vectors driving the walk
	 * @param graph the graph to walk
	 * @return ordinals of the {@link OversamplingLadder#CANDIDATES} best candidates by approximate score
	 */
	@Nonnull
	private static int[] approximateCandidates(
		@Nonnull float[] query,
		@Nonnull CompressedVectors codes,
		@Nonnull ImmutableGraphIndex graph
	) {
		final VectorFloat<?> queryVector = VTS.createFloatVector(query);
		final DefaultSearchScoreProvider scoreProvider =
			new DefaultSearchScoreProvider(codes.precomputedScoreFunctionFor(queryVector, VSF));
		try (final GraphSearcher searcher = new GraphSearcher(graph)) {
			return toOrdinals(searcher.search(
				scoreProvider, OversamplingLadder.CANDIDATES, OversamplingLadder.CANDIDATES, 0.0f, 0.0f, Bits.ALL
			));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Walks the graph scoring with compressed codes and reranks the best {@link #RERANK_K} with exact scores read
	 * from the mapped raw block.
	 *
	 * @param query the query vector
	 * @param codes the compressed vectors driving the walk
	 * @param ravv  raw vectors used for reranking
	 * @param graph the graph to walk
	 * @return ordinals of the top 10, best first
	 */
	@Nonnull
	private static int[] searchApproximate(
		@Nonnull float[] query,
		@Nonnull CompressedVectors codes,
		@Nonnull RandomAccessVectorValues ravv,
		@Nonnull ImmutableGraphIndex graph
	) {
		final VectorFloat<?> queryVector = VTS.createFloatVector(query);
		final DefaultSearchScoreProvider scoreProvider = new DefaultSearchScoreProvider(
			codes.precomputedScoreFunctionFor(queryVector, VSF), ravv.copy().rerankerFor(queryVector, VSF)
		);
		try (final GraphSearcher searcher = new GraphSearcher(graph)) {
			return toOrdinals(searcher.search(scoreProvider, 10, RERANK_K, 0.0f, 0.0f, Bits.ALL));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Prints the number of nodes per level and the mean neighbour count on level 0.
	 *
	 * @param graph the graph to inspect
	 * @throws IOException when the view of an on-disk graph cannot be closed
	 */
	private static void printGraphStats(@Nonnull ImmutableGraphIndex graph) throws IOException {
		final StringBuilder levels = new StringBuilder(64);
		for (int level = 0; level <= graph.getMaxLevel(); level++) {
			levels.append(level == 0 ? "" : ", ").append(graph.size(level));
		}
		long edges = 0;
		try (final ImmutableGraphIndex.View view = graph.getView()) {
			for (int node = 0; node < graph.getIdUpperBound(); node++) {
				if (graph.containsNode(node)) {
					edges += view.getNeighborsIterator(0, node).size();
				}
			}
		}
		line(String.format(
			"graph: %d levels, nodes per level [%s], max degree %d, mean level-0 degree %.1f",
			graph.getMaxLevel() + 1, levels, graph.getDegree(0), edges / (double) graph.size(0)
		));
	}

	/**
	 * @param ravv the vectors the graph is built over
	 * @return a builder with explicit structural parameters, independent of the process-global configuration
	 */
	@Nonnull
	static GraphIndexBuilder newBuilder(@Nonnull RandomAccessVectorValues ravv) {
		return newBuilder(ravv, 1.2f, true);
	}

	/**
	 * @param ravv      the vectors the graph is built over
	 * @param alpha     the pruning aggressiveness: 1.0 keeps HNSW's diversity rule, above 1.0 Vamana's longer edges
	 * @param hierarchy whether the graph gets HNSW's upper levels or stays a flat Vamana graph
	 * @return a builder with explicit structural parameters, independent of the process-global configuration
	 */
	@SuppressWarnings("deprecation")
	@Nonnull
	static GraphIndexBuilder newBuilder(@Nonnull RandomAccessVectorValues ravv, float alpha, boolean hierarchy) {
		return newBuilder(ravv, alpha, hierarchy, true);
	}

	/**
	 * @param ravv      the vectors the graph is built over
	 * @param alpha     the pruning aggressiveness: 1.0 keeps HNSW's diversity rule, above 1.0 Vamana's longer edges
	 * @param hierarchy whether the graph gets HNSW's upper levels or stays a flat Vamana graph
	 * @param refine    whether `cleanup()` searches again from every upper-level node to improve its edges (jVector's
	 *                  `refineFinalGraph`, on by default); a builder copied by `rescore` inherits the flag
	 * @return a builder with explicit structural parameters, independent of the process-global configuration
	 */
	@SuppressWarnings("deprecation")
	@Nonnull
	static GraphIndexBuilder newBuilder(
		@Nonnull RandomAccessVectorValues ravv,
		float alpha,
		boolean hierarchy,
		boolean refine
	) {
		return new GraphIndexBuilder(ravv, VSF, DEGREE, BEAM, 1.2f, alpha, hierarchy, refine);
	}

	/**
	 * @param result a search result
	 * @return node ordinals in result order
	 */
	@Nonnull
	private static int[] toOrdinals(@Nonnull SearchResult result) {
		final SearchResult.NodeScore[] nodes = result.getNodes();
		final int[] ordinals = new int[nodes.length];
		for (int i = 0; i < nodes.length; i++) {
			ordinals[i] = nodes[i].node;
		}
		return ordinals;
	}
}
