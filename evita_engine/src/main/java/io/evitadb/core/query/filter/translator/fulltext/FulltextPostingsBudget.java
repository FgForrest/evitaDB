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

package io.evitadb.core.query.filter.translator.fulltext;

import io.evitadb.core.query.algebra.fulltext.FulltextFormula;
import io.evitadb.core.query.algebra.fulltext.FulltextFormula.ExpandedTerm;
import io.evitadb.index.fulltext.FulltextScoringPolicy;
import io.evitadb.index.fulltext.FulltextTermExactness;

import javax.annotation.Nonnull;
import java.util.Arrays;

/**
 * Deterministic predicted-time budget over already collected fulltext expansions. This is independent of elapsed
 * time and the server's query timeout. Exact expansions are reserved first, even when they exceed the allowance;
 * the first overflowing inexact stem stops every later inexact expansion across the query. A word left empty still
 * participates in the formula, making the query match nothing, unless the caller withdraws it and prices the rest
 * again, as the translator does for a final word kept only for the completions the cut removed.
 *
 * Batches preserve collection order (word, profile group, part, leg, stem), with contiguous field views per stem.
 * Only posting cardinalities are read. `retain` shares unchanged arrays and allocates nothing when it drops nothing.
 */
final class FulltextPostingsBudget {
	/** Fixed query setup cost F, in ns, from the phase-1 cost model measured on the real query path. */
	private static final double QUERY_SETUP_NS = 12_090;
	/** Cost W per additional AND-ed word, in ns, from the phase-1 cost model measured on the real query path. */
	private static final double ADDITIONAL_WORD_NS = 4_460;
	/** Cost P per summed posting, in ns, from the phase-1 cost model measured on the real query path. */
	private static final double POSTING_NS = 6.97;
	/** Cost M per matched document, in ns, from the phase-1 cost model measured on the real query path. */
	private static final double MATCHED_DOCUMENT_NS = 13.53;
	/** Minimum predicted-time allowance, in ns, preserving ordinary searches in small catalogs. */
	private static final long MINIMUM_BUDGET_NS = 25_000_000;
	/** Predicted-time allowance per collection document in the searched locale and scope, in ns. */
	private static final long BUDGET_PER_DOCUMENT_NS = 25;

	/** Utility class. */
	private FulltextPostingsBudget() { }

	/**
	 * Finds the largest summed postings S whose predicted time `F + W(k-1) + P*S + M*min(S/k, N)` stays within
	 * `max(25 ms, 25 ns*N)`, once per scoped query, where k is the mandatory word count and N the document count.
	 * The smallest word's union cannot exceed `S/k`, so this closed form prices the worst case without reading
	 * postings or measuring time. Flooring the solution makes the integral allowance conservative.
	 * @param wordCount number of mandatory AND-ed words after stop-word removal, positive
	 * @param documentCount collection documents in the searched locale and scope, nonnegative
	 * @return nonnegative inclusive limit, in postings
	 */
	static long limit(int wordCount, int documentCount) {
		final double available = Math.max(MINIMUM_BUDGET_NS, BUDGET_PER_DOCUMENT_NS * documentCount) -
			QUERY_SETUP_NS - ADDITIONAL_WORD_NS * (wordCount - 1);
		final double sparseLimit = available / (POSTING_NS + MATCHED_DOCUMENT_NS / wordCount);
		final double result = sparseLimit / wordCount <= documentCount ? sparseLimit :
			(available - MATCHED_DOCUMENT_NS * documentCount) / POSTING_NS;
		return Math.max(0, (long) Math.floor(result));
	}

	/**
	 * Reserves all exacts and stops at the first overflowing inexact stem, keeping all its field views together.
	 * @param words words in query order, each containing group/part batches in collection order; field views of
	 *              each stem must be contiguous within a batch and share exactness
	 * @param limit nonnegative inclusive summed-postings allowance
	 * @return retained words; unchanged arrays are shared and inputs are never modified
	 */
	@Nonnull
	static ExpandedTerm[][][] retain(@Nonnull ExpandedTerm[][][] words, long limit) {
		long sum = exactPostings(words);
		boolean stopped = false;
		ExpandedTerm[][][] result = words;
		for (int w = 0; w < words.length; w++) {
			for (int b = 0; b < words[w].length; b++) {
				final ExpandedTerm[] batch = words[w][b];
				ExpandedTerm[] kept = null;
				int count = 0;
				for (int start = 0; start < batch.length;) {
					int end = start + 1;
					while (end < batch.length && batch[start].term().equals(batch[end].term())) {
						end++;
					}
					boolean keep = batch[start].exactness() == FulltextTermExactness.EXACT;
					if (!keep && !stopped) {
						long postings = 0;
						for (int v = start; v < end; v++) {
							postings += batch[v].postings().size();
						}
						keep = postings <= limit - sum;
						if (keep) {
							sum += postings;
						} else {
							stopped = true;
						}
					}
					if (keep) {
						if (kept != null) {
							System.arraycopy(batch, start, kept, count, end - start);
						}
						count += end - start;
					} else if (kept == null) {
						kept = new ExpandedTerm[batch.length];
						System.arraycopy(batch, 0, kept, 0, start);
					}
					start = end;
				}
				if (kept != null) {
					if (result == words) {
						result = words.clone();
					}
					if (result[w] == words[w]) {
						result[w] = words[w].clone();
					}
					result[w][b] = Arrays.copyOf(kept, count);
				}
			}
		}
		return result;
	}

	/**
	 * Sums the postings of every exact expansion, which the budget reserves before visiting any inexact one.
	 * @param words words in query order with their group/part batches
	 * @return summed exact postings, which may exceed the limit
	 */
	private static long exactPostings(@Nonnull ExpandedTerm[][][] words) {
		long sum = 0;
		for (final ExpandedTerm[][] word : words) {
			for (final ExpandedTerm[] batch : word) {
				for (final ExpandedTerm term : batch) {
					if (term.exactness() == FulltextTermExactness.EXACT) {
						sum += term.postings().size();
					}
				}
			}
		}
		return sum;
	}

	/**
	 * Flattens retained batches with their effective rank-time policy and recomputes split-word range ends.
	 * Empty words and empty part ranges stay present and count toward coverage. An empty mandatory word
	 * matches nothing.
	 * @param words retained words in group-major, part-minor batches
	 * @param partCounts number of ranges per word, including the original
	 * @param scoringPolicy effective weights and coefficient
	 * @return weighted fulltext formula
	 */
	@Nonnull
	static FulltextFormula toFormula(
		@Nonnull ExpandedTerm[][][] words, @Nonnull int[] partCounts, @Nonnull FulltextScoringPolicy scoringPolicy
	) {
		final ExpandedTerm[][] flat = new ExpandedTerm[words.length][];
		final int[][] coverage = new int[words.length][];
		boolean split = false;
		for (int w = 0; w < words.length; w++) {
			int size = 0;
			for (final ExpandedTerm[] batch : words[w]) {
				size += batch.length;
			}
			flat[w] = new ExpandedTerm[size];
			final int parts = partCounts[w];
			coverage[w] = new int[parts == 1 ? 0 : parts];
			split |= parts > 1;
			int end = 0;
			for (int part = 0; part < parts; part++) {
				for (int b = part; b < words[w].length; b += parts) {
					final ExpandedTerm[] batch = words[w][b];
					System.arraycopy(batch, 0, flat[w], end, batch.length);
					end += batch.length;
				}
				if (parts > 1) {
					coverage[w][part] = end;
				}
			}
		}
		return new FulltextFormula(flat, split ? coverage : null, scoringPolicy);
	}
}
