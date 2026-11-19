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

import io.evitadb.spike.vector.LuceneFootprintScenarios.MappedFloats;
import io.evitadb.spike.vector.OsqCodeFile.MappedOsqCodes;
import org.apache.lucene.codecs.hnsw.FlatVectorScorerUtil;
import org.apache.lucene.codecs.hnsw.FlatVectorsScorer;
import org.apache.lucene.codecs.lucene104.Lucene104ScalarQuantizedVectorScorer;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.util.hnsw.HnswGraphBuilder;
import org.apache.lucene.util.hnsw.HnswGraphSearcher;
import org.apache.lucene.util.hnsw.OnHeapHnswGraph;
import org.apache.lucene.util.hnsw.RandomVectorScorer;
import org.apache.lucene.util.quantization.QuantizedByteVectorValues.ScalarEncoding;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.evitadb.spike.vector.VectorMemoryProbe.*;

/**
 * Lucene used as a library, wired through the seams of `p6-implementation-proposal.md`: evitaDB owns
 * the files, Lucene computes and scores. The raw block is the extractor's float32 file, read through
 * {@link MappedFloats}; the codes are written by {@link OsqCodeFile} with Lucene's quantizer into a file of our
 * own layout and read back through {@link MappedOsqCodes}; the graph is built by `HnswGraphBuilder` over the raw
 * block and searched by `HnswGraphSearcher` with Lucene's quantized scorer over our code file. Every candidate
 * list then goes through the {@link OversamplingLadder}, so the rows compare with the full-index rows of
 * measurement 3 of `p6-measurement-results.md`.
 *
 * The graph stays on the heap: persisting it into a file of our own is the next step, not part of this one.
 *
 * @author Lukáš Hornych (hornych@fg.cz)
 */
public final class LuceneWiringScenario {
	/** Similarity used everywhere; the corpus vectors are unit length. */
	private static final VectorSimilarityFunction VSF = VectorSimilarityFunction.DOT_PRODUCT;
	/** Lucene's `M`, as in {@link LuceneFootprintScenarios}: level 0 gets 32 neighbours at most. */
	private static final int M = 16;
	/** Construction beam width, as in {@link LuceneFootprintScenarios}. */
	private static final int BEAM = 100;

	private LuceneWiringScenario() {
		throw new UnsupportedOperationException("Utility class, not to be instantiated.");
	}

	/**
	 * Writes the code file, maps it, builds the graph, searches through the quantized scorer and measures the
	 * memory of every step.
	 *
	 * @param base    mapped raw vectors
	 * @param truth   ground truth for recall
	 * @param workDir directory receiving the code file
	 * @param codec   `osq1`, `osq4` or `osq7`
	 * @throws IOException when the code file cannot be written or mapped
	 */
	public static void run(
		@Nonnull MappedVectors base,
		@Nonnull VectorGroundTruth truth,
		@Nonnull Path workDir,
		@Nonnull String codec
	) throws IOException {
		final ScalarEncoding encoding = switch (codec) {
			case "osq1" -> ScalarEncoding.SINGLE_BIT_QUERY_NIBBLE;
			case "osq4" -> ScalarEncoding.PACKED_NIBBLE;
			case "osq7" -> ScalarEncoding.SEVEN_BIT;
			default -> throw new IllegalArgumentException("Unknown codec `" + codec + "`.");
		};
		section("Lucene as a library, wired: own code file " + encoding + ", graph over the mapped raw block");
		final int n = base.count();
		final long heapStart = heapUsedAfterGc();

		// 1. codes: Lucene's quantizer computes them, the file is ours
		final Path codeFile = workDir.resolve("wired-lucene-" + codec + ".codes");
		resetHeapPeak();
		long start = System.nanoTime();
		OsqCodeFile.write(codeFile, base, encoding);
		line(
			"code file written in " + (System.nanoTime() - start) / 1_000_000 + " ms, heap pool peak incl. garbage "
				+ mb(heapPeak())
		);
		size("code file on disk", Files.size(codeFile), n);
		final MappedOsqCodes codes = OsqCodeFile.open(codeFile);
		size("JOL owned size of the code reader (centroid, scratch, quantizer)", ownedSize(codes), n);
		final long heapWithCodes = heapUsedAfterGc();
		size("heap delta after mapping the codes", heapWithCodes - heapStart, n);

		// 2. graph: built over the raw block through our adapter
		final FlatVectorsScorer flatScorer = FlatVectorScorerUtil.getLucene99FlatVectorsScorer();
		resetHeapPeak();
		start = System.nanoTime();
		final OnHeapHnswGraph graph = HnswGraphBuilder.create(
			flatScorer.getRandomVectorScorerSupplier(VSF, new MappedFloats(base)), M, BEAM, 42L
		).build(n);
		line(
			"graph built over float32 in " + (System.nanoTime() - start) / 1_000_000
				+ " ms (single thread), heap pool peak incl. garbage " + mb(heapPeak())
		);
		size("JOL owned size of the graph", ownedSize(graph), n);
		final long heapWithGraph = heapUsedAfterGc();
		size("heap delta after the build (graph, builder released)", heapWithGraph - heapWithCodes, n);

		// 3. search: Lucene's quantized scorer over our code file drives Lucene's searcher
		final Lucene104ScalarQuantizedVectorScorer osqScorer = new Lucene104ScalarQuantizedVectorScorer(flatScorer);
		OversamplingLadder.run("lucene-wired-" + codec, base, truth, q -> {
			try {
				final RandomVectorScorer scorer =
					osqScorer.getRandomVectorScorer(VSF, codes.copy(), truth.queries()[q]);
				return toOrdinals(
					HnswGraphSearcher.search(scorer, OversamplingLadder.CANDIDATES, graph, null, Integer.MAX_VALUE)
						.topDocs().scoreDocs
				);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});

		// 4. what the reading side costs once the queries have run
		size("heap delta after queries, from the start (codes reader + graph)", heapUsedAfterGc() - heapStart, n);
		line(bufferPools() + ", process RSS " + mb(processRss()));
		printMappings(workDir);
		printNativeMemory();
	}

	/**
	 * @param docs search hits; with no document mapping the doc id is the ordinal
	 * @return the ordinals in hit order
	 */
	@Nonnull
	private static int[] toOrdinals(@Nonnull ScoreDoc[] docs) {
		final int[] ordinals = new int[docs.length];
		for (int i = 0; i < docs.length; i++) {
			ordinals[i] = docs[i].doc;
		}
		return ordinals;
	}
}
