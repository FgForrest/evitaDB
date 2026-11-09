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

import io.evitadb.spike.vector.JVectorFootprintScenarios.MappedRavv;
import io.github.jbellis.jvector.disk.BufferedRandomAccessWriter;
import io.github.jbellis.jvector.disk.RandomAccessReader;
import io.github.jbellis.jvector.disk.ReaderSupplier;
import io.github.jbellis.jvector.disk.ReaderSupplierFactory;
import io.github.jbellis.jvector.graph.GraphIndexBuilder;
import io.github.jbellis.jvector.graph.GraphSearcher;
import io.github.jbellis.jvector.graph.ImmutableGraphIndex;
import io.github.jbellis.jvector.graph.SearchResult;
import io.github.jbellis.jvector.graph.similarity.DefaultSearchScoreProvider;
import io.github.jbellis.jvector.graph.similarity.ScoreFunction;
import io.github.jbellis.jvector.quantization.BQVectors;
import io.github.jbellis.jvector.quantization.BinaryQuantization;
import io.github.jbellis.jvector.quantization.CompressedVectors;
import io.github.jbellis.jvector.quantization.PQVectors;
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

import static io.evitadb.spike.vector.VectorMemoryProbe.*;

/**
 * jVector used as a library, wired through the seams of `p6-implementation-proposal.md`: the graph is
 * built by `GraphIndexBuilder` over the raw block read through {@link MappedRavv}; the codes (PQ and BQ, the only
 * ones jVector has) are computed by jVector, written into a file of their own, dropped, and loaded back from the
 * file, which is the state a reopened index is in; the search drives jVector's searcher with the score function
 * of the loaded codes. Every candidate list goes through the {@link OversamplingLadder}.
 *
 * The graph stays on the heap, as in {@link LuceneWiringScenario}.
 *
 * @author Lukáš Hornych (hornych@fg.cz)
 */
public final class JVectorWiringScenario {
	/** Vector factory of the selected vectorization provider. */
	private static final VectorTypeSupport VTS = VectorizationProvider.getInstance().getVectorTypeSupport();
	/** Similarity used everywhere; the corpus vectors are unit length. */
	private static final VectorSimilarityFunction VSF = VectorSimilarityFunction.DOT_PRODUCT;
	/** PQ subspaces, as in {@link JVectorFootprintScenarios}: 256 B per 2048-dimensional vector. */
	private static final int PQ_SUBSPACES = 256;

	private JVectorWiringScenario() {
		throw new UnsupportedOperationException("Utility class, not to be instantiated.");
	}

	/**
	 * Builds the graph, writes and reloads both codes, searches through each and measures the memory of every
	 * step.
	 *
	 * @param base    mapped raw vectors
	 * @param truth   ground truth for recall
	 * @param workDir directory receiving the code files
	 * @throws IOException when a code file cannot be written or read
	 */
	public static void run(
		@Nonnull MappedVectors base,
		@Nonnull VectorGroundTruth truth,
		@Nonnull Path workDir
	) throws IOException {
		section("jVector as a library, wired: graph over the mapped raw block, codes written and reloaded");
		final int n = base.count();
		final MappedRavv ravv = new MappedRavv(base);
		final long heapStart = heapUsedAfterGc();

		// 1. graph: built over the raw block through our adapter
		resetHeapPeak();
		final long start = System.nanoTime();
		final ImmutableGraphIndex graph;
		try (final GraphIndexBuilder builder = JVectorFootprintScenarios.newBuilder(ravv)) {
			graph = builder.build(ravv);
		}
		line(
			"graph built over float32 in " + (System.nanoTime() - start) / 1_000_000
				+ " ms (parallel), heap pool peak incl. garbage " + mb(heapPeak())
		);
		size("JOL owned size of the graph", ownedSize(graph, ravv), n);
		final long heapWithGraph = heapUsedAfterGc();
		size("heap delta after the build (graph, builder closed)", heapWithGraph - heapStart, n);

		// 2. codes: computed by jVector, written to a file, reloaded from it
		final Path pqFile = workDir.resolve("wired-jvector-pq.codes");
		final ProductQuantization pq = ProductQuantization.compute(ravv, PQ_SUBSPACES, 256, false);
		writeCodes(pqFile, pq.encodeAll(ravv, PhysicalCoreExecutor.pool()), n);
		final CompressedVectors pqCodes = loadCodes(pqFile, n, true);
		search("jvector-wired-pq", base, truth, graph, pqCodes);

		final Path bqFile = workDir.resolve("wired-jvector-bq.codes");
		writeCodes(bqFile, new BinaryQuantization(base.dimension()).encodeAll(ravv, PhysicalCoreExecutor.pool()), n);
		final CompressedVectors bqCodes = loadCodes(bqFile, n, false);
		search("jvector-wired-bq", base, truth, graph, bqCodes);

		// 3. what the reading side costs once the queries have run
		size(
			"heap delta after queries, from the start (graph + both loaded codes)",
			heapUsedAfterGc() - heapStart, n
		);
		line(bufferPools() + ", process RSS " + mb(processRss()));
		printMappings(workDir);
		printNativeMemory();
	}

	/**
	 * Writes freshly computed codes to their file; the codes go out of scope when this returns.
	 *
	 * @param file  the code file to create
	 * @param codes the computed codes
	 * @param n     number of vectors, for per-vector figures
	 * @throws IOException when the file cannot be written
	 */
	private static void writeCodes(@Nonnull Path file, @Nonnull CompressedVectors codes, int n) throws IOException {
		Files.deleteIfExists(file);
		try (final BufferedRandomAccessWriter writer = new BufferedRandomAccessWriter(file)) {
			codes.write(writer);
		}
		size("code file " + file.getFileName() + " on disk", Files.size(file), n);
	}

	/**
	 * Loads codes back from their file and measures what the load puts on the heap.
	 *
	 * @param file    the code file
	 * @param n       number of vectors, for per-vector figures
	 * @param product true for PQ codes, false for BQ codes
	 * @return the loaded codes
	 * @throws IOException when the file cannot be read
	 */
	@Nonnull
	private static CompressedVectors loadCodes(@Nonnull Path file, int n, boolean product) throws IOException {
		final long heapBefore = heapUsedAfterGc();
		final CompressedVectors codes;
		try (
			final ReaderSupplier supplier = ReaderSupplierFactory.open(file);
			final RandomAccessReader reader = supplier.get()
		) {
			codes = product ? PQVectors.load(reader) : BQVectors.load(reader, 0L);
		}
		size("heap delta after loading " + file.getFileName(), heapUsedAfterGc() - heapBefore, n);
		size("JOL owned size of the loaded " + file.getFileName(), ownedSize(codes), n);
		return codes;
	}

	/**
	 * Searches the graph with the score function of the given codes and runs the oversampling ladder.
	 *
	 * @param label printed in every line
	 * @param base  mapped raw vectors, for the rerank
	 * @param truth ground truth for recall
	 * @param graph the graph to walk
	 * @param codes the codes scoring the walk
	 */
	private static void search(
		@Nonnull String label,
		@Nonnull MappedVectors base,
		@Nonnull VectorGroundTruth truth,
		@Nonnull ImmutableGraphIndex graph,
		@Nonnull CompressedVectors codes
	) {
		OversamplingLadder.run(label, base, truth, q -> {
			final VectorFloat<?> query = VTS.createFloatVector(truth.queries()[q]);
			// the scorer seam: a function from ordinal to similarity, made for this query
			final ScoreFunction.ApproximateScoreFunction scorer = codes.precomputedScoreFunctionFor(query, VSF);
			try (final GraphSearcher searcher = new GraphSearcher(graph)) {
				return toOrdinals(searcher.search(
					new DefaultSearchScoreProvider(scorer),
					OversamplingLadder.CANDIDATES, OversamplingLadder.CANDIDATES, 0.0f, 0.0f, Bits.ALL
				));
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
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
