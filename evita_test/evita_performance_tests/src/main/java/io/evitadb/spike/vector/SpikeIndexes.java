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

import io.evitadb.spike.vector.ClickRecallScenario.IoFunction;
import io.evitadb.spike.vector.JVectorFootprintScenarios.MappedRavv;
import io.evitadb.spike.vector.LuceneFootprintScenarios.MappedFloats;
import io.evitadb.spike.vector.OsqCodeFile.MappedOsqCodes;
import io.github.jbellis.jvector.graph.GraphIndexBuilder;
import io.github.jbellis.jvector.graph.ImmutableGraphIndex;
import io.github.jbellis.jvector.graph.NodesIterator;
import io.github.jbellis.jvector.graph.similarity.ScoreFunction;
import io.github.jbellis.jvector.quantization.BinaryQuantization;
import io.github.jbellis.jvector.quantization.CompressedVectors;
import io.github.jbellis.jvector.quantization.ProductQuantization;
import io.github.jbellis.jvector.util.PhysicalCoreExecutor;
import io.github.jbellis.jvector.vector.VectorizationProvider;
import io.github.jbellis.jvector.vector.types.VectorTypeSupport;
import org.apache.lucene.codecs.hnsw.FlatVectorScorerUtil;
import org.apache.lucene.codecs.hnsw.FlatVectorsScorer;
import org.apache.lucene.codecs.lucene104.Lucene104ScalarQuantizedVectorScorer;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.util.hnsw.HnswGraphBuilder;
import org.apache.lucene.util.hnsw.OnHeapHnswGraph;
import org.apache.lucene.util.hnsw.RandomVectorScorer;
import org.apache.lucene.util.quantization.QuantizedByteVectorValues.ScalarEncoding;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

import static io.evitadb.spike.vector.VectorMemoryProbe.line;
import static io.evitadb.spike.vector.VectorMemoryProbe.mb;

/**
 * The indexes every scenario of `p6-measurement-results.md` builds, in one place, with the
 * structural parameters fixed here so two scenarios cannot drift apart:
 *
 * - **Lucene graph**: `HnswGraphBuilder`, `M = 16`, beam 100, seed 42, one thread, over the mapped raw block;
 *   deterministic, so its rows reproduce exactly between runs;
 * - **Lucene OSQ codes**: computed by Lucene's `OptimizedScalarQuantizer` into our `OsqCodeFile` in the work
 *   directory (written once, reused by every later run) and read by Lucene's scorer;
 * - **jVector graph**: degree 32, beam 100, overflow 1.2, refine on, `alpha` and hierarchy chosen by the caller
 *   (the scenarios use 1.2 with the hierarchy), built on all cores; varies by about ±0.02 of recall between
 *   runs;
 * - **jVector PQ** (256 subspaces × 8 bits, retrained per run) and **BQ** codes, on the heap.
 *
 * Every scorer factory returns a function of the query, because the scorers are per query. The parts 1–8 scenarios
 * (`JVectorFootprintScenarios`, `LuceneFootprintScenarios`, the wiring scenarios) keep their own construction: they
 * measure the memory of exactly that construction.
 *
 * @author Lukáš Hornych (hornych@fg.cz)
 */
public final class SpikeIndexes {
	/** Similarity used everywhere; corpus and query vectors are unit length. */
	public static final VectorSimilarityFunction VSF = VectorSimilarityFunction.DOT_PRODUCT;
	/** jVector's name of the same similarity. */
	public static final io.github.jbellis.jvector.vector.VectorSimilarityFunction JVSF =
		io.github.jbellis.jvector.vector.VectorSimilarityFunction.DOT_PRODUCT;
	/** jVector vector factory of the selected vectorization provider. */
	public static final VectorTypeSupport VTS = VectorizationProvider.getInstance().getVectorTypeSupport();
	/** Lucene's `M`: up to 16 neighbours on the upper levels and 32 on level 0. */
	public static final int LUCENE_M = 16;
	/** Construction beam of both libraries. */
	public static final int BEAM = 100;
	/** Seed of Lucene's level assignment. */
	public static final long LUCENE_SEED = 42L;
	/** PQ subspaces at 8 bits each. */
	public static final int PQ_SUBSPACES = 256;
	/** The jVector `alpha` of parts 9, 10, 12–14. */
	public static final float DEFAULT_ALPHA = 1.2f;

	private SpikeIndexes() {
		throw new UnsupportedOperationException("Utility class, not to be instantiated.");
	}

	/**
	 * @return Lucene's float32 scorer, which its quantized scorers delegate to
	 */
	@Nonnull
	public static FlatVectorsScorer flatScorer() {
		return FlatVectorScorerUtil.getLucene99FlatVectorsScorer();
	}

	/**
	 * @param base the mapped corpus
	 * @return a Lucene graph builder over the mapped raw block, nothing inserted yet
	 * @throws IOException when the scorer supplier cannot be created
	 */
	@Nonnull
	public static HnswGraphBuilder luceneBuilder(@Nonnull MappedVectors base) throws IOException {
		return HnswGraphBuilder.create(
			flatScorer().getRandomVectorScorerSupplier(VSF, new MappedFloats(base)), LUCENE_M, BEAM, LUCENE_SEED
		);
	}

	/**
	 * Builds the Lucene graph over the whole corpus and prints the build time.
	 *
	 * @param base the mapped corpus
	 * @return the graph
	 * @throws IOException when the graph cannot be built
	 */
	@Nonnull
	public static OnHeapHnswGraph luceneGraph(@Nonnull MappedVectors base) throws IOException {
		final long start = System.nanoTime();
		final OnHeapHnswGraph graph = luceneBuilder(base).build(base.count());
		line("Lucene graph built in " + (System.nanoTime() - start) / 1_000_000 + " ms on one thread");
		return graph;
	}

	/**
	 * @param base the mapped corpus
	 * @return for a query, Lucene's float32 scorer over the mapped raw block
	 */
	@Nonnull
	public static IoFunction<float[], RandomVectorScorer> luceneFloat32(@Nonnull MappedVectors base) {
		final FlatVectorsScorer scorer = flatScorer();
		return q -> scorer.getRandomVectorScorer(VSF, new MappedFloats(base), q);
	}

	/**
	 * @param workDir  the work directory holding the code files
	 * @param base     the mapped corpus
	 * @param encoding the OSQ encoding
	 * @return the codes of the code file `click-lucene-<encoding>.codes`, written when missing or outdated
	 * @throws IOException when the code file cannot be read or written
	 */
	@Nonnull
	public static MappedOsqCodes osqCodes(
		@Nonnull Path workDir,
		@Nonnull MappedVectors base,
		@Nonnull ScalarEncoding encoding
	) throws IOException {
		final Path codeFile = workDir.resolve("click-lucene-" + encoding + ".codes");
		final Path corpus = workDir.resolve(VectorExportExtractor.TEXT_FILE);
		// a code file older than the corpus block encodes an earlier extraction of it
		if (!Files.exists(codeFile)
			|| Files.getLastModifiedTime(codeFile).compareTo(Files.getLastModifiedTime(corpus)) < 0) {
			OsqCodeFile.write(codeFile, base, encoding);
		}
		return OsqCodeFile.open(codeFile);
	}

	/**
	 * @param codes OSQ codes from our code file
	 * @return for a query, Lucene's quantized scorer over the codes
	 */
	@Nonnull
	public static IoFunction<float[], RandomVectorScorer> luceneOsq(@Nonnull MappedOsqCodes codes) {
		final Lucene104ScalarQuantizedVectorScorer scorer = new Lucene104ScalarQuantizedVectorScorer(flatScorer());
		return q -> scorer.getRandomVectorScorer(VSF, codes.copy(), q);
	}

	/**
	 * Builds a jVector graph over the whole corpus on all cores and prints its shape.
	 *
	 * @param ravv      the mapped vectors
	 * @param alpha     the pruning aggressiveness: 1.0 keeps HNSW's diversity rule, above 1.0 Vamana's longer edges
	 * @param hierarchy whether the graph gets HNSW's upper levels
	 * @return the graph
	 * @throws IOException when the builder cannot be closed
	 */
	@Nonnull
	public static ImmutableGraphIndex jvectorGraph(@Nonnull MappedRavv ravv, float alpha, boolean hierarchy)
		throws IOException {
		final long start = System.nanoTime();
		final ImmutableGraphIndex graph;
		try (final GraphIndexBuilder builder = JVectorFootprintScenarios.newBuilder(ravv, alpha, hierarchy)) {
			graph = builder.build(ravv);
		}
		final long buildNs = System.nanoTime() - start;
		final StringBuilder levels = new StringBuilder(64);
		try (final ImmutableGraphIndex.View view = graph.getView()) {
			for (int level = 0; level <= graph.getMaxLevel(); level++) {
				long edges = 0;
				final NodesIterator nodes = graph.getNodes(level);
				final int count = nodes.size();
				while (nodes.hasNext()) {
					edges += view.getNeighborsIterator(level, nodes.nextInt()).size();
				}
				levels.append(level == 0 ? "" : ", ").append(count).append(" nodes / mean degree ")
					.append(String.format("%.1f", edges / (double) Math.max(1, count)));
			}
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
		line(String.format(
			"jVector graph alpha %.1f, hierarchy %s: built in %d ms on all cores, heap %s; levels: %s",
			alpha, hierarchy, buildNs / 1_000_000, mb(VectorMemoryProbe.ownedSize(graph)), levels
		));
		return graph;
	}

	/**
	 * @param ravv the mapped vectors
	 * @return PQ codes of every vector, 256 subspaces × 8 bits, trained on the corpus
	 */
	@Nonnull
	public static CompressedVectors pq(@Nonnull MappedRavv ravv) {
		return ProductQuantization.compute(ravv, PQ_SUBSPACES, 256, false)
			.encodeAll(ravv, PhysicalCoreExecutor.pool());
	}

	/**
	 * @param ravv the mapped vectors
	 * @return BQ codes of every vector
	 */
	@Nonnull
	public static CompressedVectors bq(@Nonnull MappedRavv ravv) {
		return new BinaryQuantization(ravv.dimension()).encodeAll(ravv, PhysicalCoreExecutor.pool());
	}

	/**
	 * @param ravv the mapped vectors
	 * @return for a query, jVector's exact float32 score function over the mapped raw block
	 */
	@Nonnull
	public static Function<float[], ScoreFunction> jvectorFloat32(@Nonnull MappedRavv ravv) {
		return q -> ravv.rerankerFor(VTS.createFloatVector(q), JVSF);
	}

	/**
	 * @param codes jVector's PQ or BQ codes
	 * @return for a query, the codes' approximate score function
	 */
	@Nonnull
	public static Function<float[], ScoreFunction> jvectorCodes(@Nonnull CompressedVectors codes) {
		return q -> codes.precomputedScoreFunctionFor(VTS.createFloatVector(q), JVSF);
	}
}
