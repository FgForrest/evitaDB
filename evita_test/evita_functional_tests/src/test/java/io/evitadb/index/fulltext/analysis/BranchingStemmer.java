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

package io.evitadb.index.fulltext.analysis;

import javax.annotation.Nonnull;

/**
 * A stemmer that computes a whole M7 **hypothesis set** in one branching walk — every distinct stem the
 * corresponding flat union of {@link FoldedStemmer} configurations produces for a word, found by forking only
 * where a fold-ambiguous rule actually matches instead of running every switch combination. One implementation
 * per language, each shaped by its own structural analysis (see each class's javadoc); what they share is the
 * contract this interface names, which is what lets one {@link BranchingHypothesisStemFilter} drive any of
 * them, and one equivalence-test harness compare each against its flat union.
 *
 * Implementations are stateful scratch — the results of {@link #hypothesize(char[], int)} live in internal
 * reusable arrays until the next call — and are therefore **not thread-safe**: one instance per token stream,
 * exactly like a Lucene stemmer.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
interface BranchingStemmer {

	/**
	 * Computes every distinct hypothesis of the given diacritics-folded, lowercased word. The buffer is only
	 * read, never mutated; the results stay valid until the next call.
	 *
	 * @param word   input buffer, read only
	 * @param length length of the word in the buffer
	 * @return number of distinct hypotheses
	 */
	int hypothesize(@Nonnull char[] word, int length);

	/**
	 * Length of the given hypothesis of the last {@link #hypothesize(char[], int)} call.
	 *
	 * @param hypothesisIndex index of the hypothesis, `0` to `count - 1`
	 * @return length of the hypothesis
	 */
	int length(int hypothesisIndex);

	/**
	 * Writes the given hypothesis into the destination buffer, which must be at least {@link #length(int)}
	 * characters long.
	 *
	 * @param hypothesisIndex index of the hypothesis, `0` to `count - 1`
	 * @param originalWord    the exact buffer the last {@link #hypothesize(char[], int)} call read
	 * @param destination     buffer to write into
	 * @return length of the hypothesis written
	 */
	int materialize(int hypothesisIndex, @Nonnull char[] originalWord, @Nonnull char[] destination);

}
