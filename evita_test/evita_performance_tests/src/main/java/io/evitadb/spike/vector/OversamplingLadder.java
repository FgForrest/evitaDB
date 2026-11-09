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

import org.apache.lucene.util.VectorUtil;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.function.IntFunction;

import static io.evitadb.spike.vector.VectorMemoryProbe.line;

/**
 * The oversampling ladder of the specification's §3.5, shared by every codec of both libraries so that the only
 * variable between two rows is the code.
 *
 * The approximate search always walks with the same candidate depth ({@link #CANDIDATES}, the `ef` used everywhere
 * in the spike) and returns its candidates ordered by the code's own score. For an oversampling factor `os` the
 * first `os × 10` of them are rescored exactly against the mapped raw block and the best ten are kept. Fixing the
 * walk depth keeps the beam out of the comparison: a `KnnFloatVectorQuery` with `k = os × 10` would walk with a
 * beam of 10 at `os = 1` and mix the code's quality with the beam's.
 *
 * Recall is recorded twice per factor - **before** the rerank (the code's own top ten) and **after** it (what a
 * user would see). Latency covers the whole pipeline, walk plus rerank, per query on one thread.
 *
 * @author Lukáš Hornych (hornych@fg.cz)
 */
public final class OversamplingLadder {
	/** Depth of the approximate walk and the number of candidates it returns. */
	public static final int CANDIDATES = 100;
	/** Oversampling factors of the specification's §3.5. */
	private static final int[] FACTORS = {1, 2, 3, 4, 8};
	/** Number of results the user asks for. */
	private static final int TOP_K = 10;
	/** Timed passes over the query set per factor; the first, untimed pass warms the code paths. */
	private static final int TIMED_PASSES = 5;

	private OversamplingLadder() {
		throw new UnsupportedOperationException("Utility class, not to be instantiated.");
	}

	/**
	 * Measures recall before and after the exact rerank, and the latency of walk plus rerank, for every factor.
	 *
	 * @param label       codec name printed in every output line
	 * @param base        mapped raw vectors the reranker reads
	 * @param truth       ground truth of the corpus
	 * @param approximate for a query index, the ordinals of the {@link #CANDIDATES} best candidates ordered by the
	 *                    code's approximate score
	 */
	public static void run(
		@Nonnull String label,
		@Nonnull MappedVectors base,
		@Nonnull VectorGroundTruth truth,
		@Nonnull IntFunction<int[]> approximate
	) {
		final float[][] queries = truth.queries();
		final int[][] candidates = new int[queries.length][];
		for (int q = 0; q < queries.length; q++) {
			candidates[q] = approximate.apply(q);
		}
		final float[] scratch = new float[base.dimension()];
		line(String.format(
			"%s: approximate walk returns %d candidates; recall@10 before rerank = %.4f",
			label, CANDIDATES, truth.recall(q -> Arrays.copyOf(candidates[q], Math.min(TOP_K, candidates[q].length)))
		));
		for (final int factor : FACTORS) {
			final int depth = factor * TOP_K;
			final double recall = truth.recall(q -> rerank(base, queries[q], candidates[q], depth, scratch));
			final long[] latencies = new long[queries.length * TIMED_PASSES];
			for (int pass = -1; pass < TIMED_PASSES; pass++) {
				for (int q = 0; q < queries.length; q++) {
					final long start = System.nanoTime();
					rerank(base, queries[q], approximate.apply(q), depth, scratch);
					final long elapsed = System.nanoTime() - start;
					if (pass >= 0) {
						latencies[pass * queries.length + q] = elapsed;
					}
				}
			}
			Arrays.sort(latencies);
			line(String.format(
				"%s: oversample %dx (rerank %3d) recall@10 after rerank = %.4f, latency p50 %.3f ms, p95 %.3f ms",
				label, factor, depth, recall, percentile(latencies, 0.50), percentile(latencies, 0.95)
			));
		}
	}

	/**
	 * Rescores the first `depth` candidates exactly and returns the best ten ordinals.
	 *
	 * @param base       mapped raw vectors
	 * @param query      the query vector
	 * @param candidates candidate ordinals in approximate order
	 * @param depth      how many of the candidates to rescore
	 * @param scratch    buffer receiving one raw vector at a time
	 * @return the best {@link #TOP_K} ordinals by exact score, best first
	 */
	@Nonnull
	static int[] rerank(
		@Nonnull MappedVectors base,
		@Nonnull float[] query,
		@Nonnull int[] candidates,
		int depth,
		@Nonnull float[] scratch
	) {
		final int count = Math.min(depth, candidates.length);
		final float[] scores = new float[count];
		final int[] ordinals = new int[count];
		for (int i = 0; i < count; i++) {
			base.read(candidates[i], scratch);
			scores[i] = VectorUtil.dotProduct(query, scratch);
			ordinals[i] = candidates[i];
		}
		// partial selection sort: only the best ten positions are needed
		final int keep = Math.min(TOP_K, count);
		for (int i = 0; i < keep; i++) {
			int best = i;
			for (int j = i + 1; j < count; j++) {
				if (scores[j] > scores[best]) {
					best = j;
				}
			}
			final float score = scores[i];
			scores[i] = scores[best];
			scores[best] = score;
			final int ordinal = ordinals[i];
			ordinals[i] = ordinals[best];
			ordinals[best] = ordinal;
		}
		return Arrays.copyOf(ordinals, keep);
	}

	/**
	 * @param sorted   latencies in nanoseconds, sorted ascending
	 * @param fraction the percentile as a fraction, e.g. `0.95`
	 * @return the percentile in milliseconds
	 */
	static double percentile(@Nonnull long[] sorted, double fraction) {
		final int index = Math.min(sorted.length - 1, (int) Math.ceil(fraction * sorted.length) - 1);
		return sorted[Math.max(0, index)] / 1_000_000.0;
	}
}
