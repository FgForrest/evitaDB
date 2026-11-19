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
import io.evitadb.spike.vector.ClickRecallScenario.Evaluator;
import io.evitadb.spike.vector.ClickRecallScenario.Filter;
import io.evitadb.spike.vector.ClickRecallScenario.IoFunction;
import io.evitadb.spike.vector.JVectorFootprintScenarios.MappedRavv;
import io.evitadb.spike.vector.LuceneFootprintScenarios.MappedFloats;
import io.github.jbellis.jvector.graph.ImmutableGraphIndex;
import io.github.jbellis.jvector.quantization.CompressedVectors;
import io.github.jbellis.jvector.quantization.ProductQuantization;
import io.github.jbellis.jvector.util.PhysicalCoreExecutor;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.hnsw.HnswGraph;
import org.apache.lucene.util.hnsw.HnswGraphBuilder;
import org.apache.lucene.util.hnsw.OnHeapHnswGraph;
import org.apache.lucene.util.hnsw.RandomVectorScorer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static io.evitadb.spike.vector.VectorMemoryProbe.line;
import static io.evitadb.spike.vector.VectorMemoryProbe.section;

/**
 * Two follow-ups of the filtered and recall measurements, both about how dense a graph or how good a code is:
 *
 * - {@link #acornDensity}: Lucene graphs with `M` 16, 32 and 64 (level-0 degree bound 32, 64 and 128), searched in
 *   walk and with ACORN under the real filter and under random filters of 50, 10 and 1 %. It tests whether ACORN's
 *   loss of recall over the Lucene graph comes from the graph's sparsity (mean level-0 degree 6.9–11 at `M = 16`).
 * - {@link #pqCentering}: jVector PQ 256 × 8 with and without global centering, BQ alongside, all on one jVector
 *   graph so that only the code differs.
 *
 * Both evaluate a subset of the queries ({@link #QUERIES}) against the exact scan; recall@10 after the exact rerank
 * at 1×, 2×, 4× and 8×, as everywhere in the harness.
 *
 * @author Lukáš Hornych (hornych@fg.cz)
 */
public final class GraphDensityScenarios {
	/** Queries evaluated: the in-walk rows under a 1 % filter are slow, and the comparison needs no more. */
	private static final int QUERIES = 200;
	/** The values of Lucene's `M` compared. */
	private static final int[] LUCENE_MS = {16, 32, 64};
	/** Densities of the random filters. */
	private static final double[] RANDOM_DENSITIES = {0.5, 0.1, 0.01};

	private GraphDensityScenarios() {
		throw new UnsupportedOperationException("Utility class, not to be instantiated.");
	}

	/**
	 * Lucene graphs of growing `M`, in-walk against ACORN under the real and random filters.
	 *
	 * @param base       mapped corpus vectors
	 * @param workDir    directory holding the corpus mapping and the queries
	 * @param collection collection code of the real filter, null when the dataset has none
	 * @throws IOException when an input cannot be read or a graph cannot be built
	 */
	public static void acornDensity(
		@Nonnull MappedVectors base,
		@Nonnull Path workDir,
		@Nullable String collection
	) throws IOException {
		section("ACORN over denser Lucene graphs: M " + Arrays.toString(LUCENE_MS));
		final Corpus corpus = Corpus.load(workDir.resolve(VectorExportExtractor.TEXT_ORDINALS_FILE), base);
		final float[][] queries = queries(workDir, base);
		final PrimaryKeyOrdinals ordinals = PrimaryKeyOrdinals.of(corpus.record(), corpus.recordCount());
		final List<String> names = new ArrayList<>(4);
		final List<Evaluator> evaluators = new ArrayList<>(4);
		if (collection != null) {
			final Filter real = Filter.of(corpus, collection);
			names.add(String.format("real filter %.2f %%", 100.0 * real.count() / base.count()));
			evaluators.add(new Evaluator(base, corpus, queries, null, real));
		}
		final Random random = new Random(7L);
		for (final double density : RANDOM_DENSITIES) {
			final boolean[] accept = new boolean[base.count()];
			for (int i = 0; i < accept.length; i++) {
				accept[i] = random.nextDouble() < density;
			}
			final Filter filter = Filter.of(accept, ordinals);
			names.add(String.format("random filter %.2f %%", 100.0 * filter.count() / base.count()));
			evaluators.add(new Evaluator(base, corpus, queries, null, filter));
		}
		line(String.format("corpus %d vectors, queries %d, filters %s", base.count(), queries.length, names));
		final IoFunction<float[], RandomVectorScorer> scorers = SpikeIndexes.luceneFloat32(base);
		for (final int m : LUCENE_MS) {
			final long start = System.nanoTime();
			final OnHeapHnswGraph graph = HnswGraphBuilder.create(
				SpikeIndexes.flatScorer().getRandomVectorScorerSupplier(SpikeIndexes.VSF, new MappedFloats(base)),
				m, SpikeIndexes.BEAM, SpikeIndexes.LUCENE_SEED
			).build(base.count());
			line(String.format("Lucene graph M=%d built in %d ms on one thread, %s, mean level-0 degree %.1f of %d",
				m, (System.nanoTime() - start) / 1_000_000, VectorMemoryProbe.mb(graph.ramBytesUsed()),
				meanLevel0Degree(graph), 2 * m));
			for (int f = 0; f < evaluators.size(); f++) {
				final Evaluator evaluator = evaluators.get(f);
				final Filter filter = evaluator.filter;
				final Bits accept = ClickRecallScenario.luceneBits(filter);
				final String label = "M=" + m + ", " + names.get(f);
				evaluator.approximate(label, "in-walk", filter, (q, stats) -> ClickRecallScenario.luceneWalk(
					scorers.apply(q), graph, accept, filter.count(), Integer.MAX_VALUE, 0, stats));
				evaluator.approximate(label, "acorn", filter, (q, stats) -> ClickRecallScenario.luceneWalk(
					scorers.apply(q), graph, accept, filter.count(), Integer.MAX_VALUE, 100, stats));
			}
		}
	}

	/**
	 * jVector PQ with and without global centering, and BQ, on one jVector graph.
	 *
	 * @param base       mapped corpus vectors
	 * @param workDir    directory holding the corpus mapping and the queries
	 * @param collection collection code of the real filter, null when the dataset has none; not used by the
	 *                   unfiltered rows, but the evaluator loads it
	 * @throws IOException when an input cannot be read or the graph cannot be built
	 */
	public static void pqCentering(
		@Nonnull MappedVectors base,
		@Nonnull Path workDir,
		@Nullable String collection
	) throws IOException {
		section("jVector PQ 256 x 8 with and without global centering, BQ alongside, one graph");
		final Corpus corpus = Corpus.load(workDir.resolve(VectorExportExtractor.TEXT_ORDINALS_FILE), base);
		final Evaluator evaluator = new Evaluator(base, corpus, queries(workDir, base), null, null);
		final MappedRavv ravv = new MappedRavv(base);
		final ImmutableGraphIndex graph = SpikeIndexes.jvectorGraph(ravv, SpikeIndexes.DEFAULT_ALPHA, true);
		final io.github.jbellis.jvector.util.Bits all = io.github.jbellis.jvector.util.Bits.ALL;
		for (final boolean center : new boolean[]{false, true}) {
			final long start = System.nanoTime();
			final CompressedVectors codes = ProductQuantization.compute(ravv, SpikeIndexes.PQ_SUBSPACES, 256, center)
				.encodeAll(ravv, PhysicalCoreExecutor.pool());
			line(String.format("PQ %d x 8, globallyCenter=%s: trained and encoded in %d ms", SpikeIndexes.PQ_SUBSPACES,
				center, (System.nanoTime() - start) / 1_000_000));
			evaluator.approximate("jvector pq 256, centered " + center, "all", null,
				(q, stats) -> ClickRecallScenario.jvectorWalk(graph, codes, q, all, stats));
		}
		final CompressedVectors bq = SpikeIndexes.bq(ravv);
		evaluator.approximate("jvector bq", "all", null,
			(q, stats) -> ClickRecallScenario.jvectorWalk(graph, bq, q, all, stats));
	}

	/**
	 * @param workDir directory holding the queries
	 * @param base    the corpus, for the dimension
	 * @return the first {@link #QUERIES} queries
	 * @throws IOException when the queries cannot be read
	 */
	@Nonnull
	private static float[][] queries(@Nonnull Path workDir, @Nonnull MappedVectors base) throws IOException {
		final float[][] all = ClickRecallScenario.loadQueries(workDir, base.dimension());
		return Arrays.copyOf(all, Math.min(QUERIES, all.length));
	}

	/**
	 * @param graph a Lucene graph
	 * @return the mean neighbour count of the level-0 nodes
	 * @throws IOException when the graph cannot be read
	 */
	private static double meanLevel0Degree(@Nonnull OnHeapHnswGraph graph) throws IOException {
		final HnswGraph.NodesIterator nodes = graph.getNodesOnLevel(0);
		long edges = 0;
		int count = 0;
		while (nodes.hasNext()) {
			edges += graph.getNeighbors(0, nodes.nextInt()).size();
			count++;
		}
		return count == 0 ? 0 : edges / (double) count;
	}
}
