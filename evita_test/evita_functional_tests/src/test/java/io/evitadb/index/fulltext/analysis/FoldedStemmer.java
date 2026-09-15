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
 * A stemmer whose ending tables are written in **diacritics-folded space**, i.e. one that expects its input to
 * be lowercased and stripped of accents already — the exact opposite of what the Lucene language stemmers
 * expect. The contract is buffer-in-place, mirroring the Lucene stemmer convention, so an implementation can be
 * driven by {@link FoldedStemFilter} in a symmetric chain or by {@link HypothesisStemFilter} in an asymmetric
 * (M7) one.
 *
 * Implementations are per-language prototype ports ({@link FoldedCzechStemmer} is the original) whose
 * fold-ambiguous rules are switchable per instance, so that the cost of each ambiguity can be measured rather
 * than argued — see the p5 measurement records under
 * `documentation/adr/2026-08-24-fulltext-search-lucene-vs-inhouse/prototypes/`.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
interface FoldedStemmer {

	/**
	 * Stems an input buffer of diacritics-folded, lowercased text in place.
	 *
	 * @param buffer input buffer, mutated in place
	 * @param length length of the input in the buffer
	 * @return length of the buffer after stemming
	 */
	int stem(@Nonnull char[] buffer, int length);

}
