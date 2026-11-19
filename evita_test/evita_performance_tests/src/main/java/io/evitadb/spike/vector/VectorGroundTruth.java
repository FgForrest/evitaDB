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

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.function.IntFunction;

/**
 * Brute-force exact top-K by dot product (the vectors are unit length) and a tie-robust recall@K.
 *
 * A returned ordinal counts as a hit when its exact score is not below the K-th best exact score. The corpus holds
 * many exact duplicates (the same text in two locales), so recall by ordinal identity would punish correct answers
 * that merely picked another copy of a tied vector. Only a sanity check that a built graph is searchable — the
 * spike measures memory, not the recall/latency curves of the P6 specification §4.
 *
 * @author Lukáš Hornych (hornych@fg.cz)
 */
public final class VectorGroundTruth {
	/** The indexed corpus. */
	private final MappedVectors base;
	/** Query vectors, on the heap. */
	private final float[][] queries;
	/** Exact score of the K-th best corpus vector per query. */
	private final float[] kthScore;
	/** Depth of the recall measurement. */
	private final int k;

	/**
	 * Scans the whole corpus for every query.
	 *
	 * @param base    the indexed corpus
	 * @param queries query vectors
	 * @param k       depth of the recall measurement
	 */
	public VectorGroundTruth(@Nonnull MappedVectors base, @Nonnull float[][] queries, int k) {
		this.base = base;
		this.queries = queries;
		this.k = k;
		this.kthScore = new float[queries.length];
		final float[] vector = new float[base.dimension()];
		final float[] scores = new float[base.count()];
		for (int q = 0; q < queries.length; q++) {
			for (int i = 0; i < base.count(); i++) {
				base.read(i, vector);
				scores[i] = dot(queries[q], vector);
			}
			Arrays.sort(scores);
			this.kthScore[q] = scores[scores.length - k];
		}
	}

	/**
	 * @return the query vectors
	 */
	@Nonnull
	public float[][] queries() {
		return this.queries;
	}

	/**
	 * @param search maps a query index to the ordinals the index returned, best first
	 * @return mean recall@K over all queries
	 */
	public double recall(@Nonnull IntFunction<int[]> search) {
		final float[] vector = new float[this.base.dimension()];
		long hits = 0;
		for (int q = 0; q < this.queries.length; q++) {
			final int[] result = search.apply(q);
			for (int i = 0; i < Math.min(this.k, result.length); i++) {
				this.base.read(result[i], vector);
				if (dot(this.queries[q], vector) >= this.kthScore[q] - 1e-5f) {
					hits++;
				}
			}
		}
		return hits / (double) (this.queries.length * this.k);
	}

	/**
	 * @param a first vector
	 * @param b second vector of the same length
	 * @return scalar dot product
	 */
	private static float dot(@Nonnull float[] a, @Nonnull float[] b) {
		float sum = 0;
		for (int i = 0; i < a.length; i++) {
			sum += a[i] * b[i];
		}
		return sum;
	}
}
