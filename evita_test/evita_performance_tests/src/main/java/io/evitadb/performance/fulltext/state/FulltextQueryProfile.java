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

package io.evitadb.performance.fulltext.state;

import javax.annotation.Nonnull;

/**
 * What one benchmarked query costs according to the P1 phase-1 cost model, computed from the index before anything is
 * measured.
 *
 * The model is the one the fulltext ADR records for the phase-1 budget:
 *
 * ```text
 * 2.4 ns × candidates + 32 ns × summed postings + 21 ns × matched documents
 * ```
 *
 * **Candidates mean something else on this path than they did in P1.** P1 scored a whole locale partition, so its
 * candidates were every document of the corpus. Through the planner, `FulltextFormula#getFulltextScores` scores the
 * final result of the filter, and for a bare `textMatches` query that is the strict match set itself - candidates
 * equal matched documents. The model therefore prices only the scoring; computing the match set (a union per token
 * and an intersection across tokens over the same posting lists) is a cost P1 never measured, and it is in the
 * measured time of both benchmark methods.
 *
 * @param text             the query text
 * @param frequencyClass   the class every word of the text was drawn from
 * @param tokenCount       how many tokens the text analyzes into
 * @param summedPostings   the length of every posting list the query's terms hold, summed over its tokens, their
 *                         variant stems and every searched field - the postings the scorer walks
 * @param matchedDocuments how many documents contain every token - the strict match set, which on this path is also
 *                         the candidate set the scorer receives
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record FulltextQueryProfile(
	@Nonnull String text,
	@Nonnull FulltextFrequencyClass frequencyClass,
	int tokenCount,
	long summedPostings,
	int matchedDocuments
) {

	/**
	 * The model's cost of one candidate, in nanoseconds.
	 */
	public static final double NANOS_PER_CANDIDATE = 2.4;
	/**
	 * The model's cost of one posting walked, in nanoseconds - the pessimistic end, taken whenever the candidate array
	 * spills L2.
	 */
	public static final double NANOS_PER_POSTING = 32.0;
	/**
	 * The model's cost of one matched document, in nanoseconds.
	 */
	public static final double NANOS_PER_MATCHED_DOCUMENT = 21.0;

	/**
	 * Returns the scoring cost the P1 model predicts for this query, with the match set as the candidate set.
	 *
	 * @return the predicted cost in nanoseconds
	 */
	public double getModelNanos() {
		return NANOS_PER_CANDIDATE * this.matchedDocuments
			+ NANOS_PER_POSTING * this.summedPostings
			+ NANOS_PER_MATCHED_DOCUMENT * this.matchedDocuments;
	}

	@Nonnull
	@Override
	public String toString() {
		return this.frequencyClass + " tokens=" + this.tokenCount + " postings=" + this.summedPostings
			+ " matched=" + this.matchedDocuments + " modelUs=" + Math.round(getModelNanos() / 1_000.0)
			+ " text=\"" + this.text + "\"";
	}

}
