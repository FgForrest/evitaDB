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
 * A single term produced by {@link FulltextAnalyzer} from one analysed text, together with everything about it
 * that cannot be reconstructed afterwards.
 *
 * Two of the five components exist for consumers that do not exist yet, and both are deliberate:
 *
 * - **offsets** are what highlighting needs. Highlighting is a re-analysis of the returned page at render time
 *   with no index support behind it, so the tokenization contract has to hand the positions out even while
 *   nobody reads them;
 * - **{@link #surfaceForm()}** is the slice of the analysed text the term came from, i.e. the word as it was
 *   written before stemming and diacritics folding collapsed it. Once the chain has run, the surface form is
 *   irrecoverable — and the open question of whether the term dictionary keeps surface forms next to stems
 *   (which decides how prefix scans and typo tolerance behave, because the Levenshtein budget is measured in
 *   whichever space the dictionary lives) cannot be answered later without rewriting the chain. Emitting it
 *   now costs nothing while no one consumes it.
 *
 * Note that the surface form is a slice of the **NFC-normalized** text (see {@link FulltextAnalyzer#analyze}),
 * not of the caller's original string; the offsets index into that same normalized text.
 *
 * @param term              the analysed term — a stem, lowercased and possibly diacritics-folded, and for
 *                          algorithmic stemmers not necessarily a word at all (Czech `muž` stems to `muh`)
 * @param surfaceForm       the substring of the NFC-normalized input the term was produced from
 * @param startOffset       start offset of the surface form in the NFC-normalized input
 * @param endOffset         end offset (exclusive) of the surface form in the NFC-normalized input
 * @param positionIncrement distance from the preceding term's position; `1` for consecutive terms, `0` for a
 *                          term sharing its position with the previous one (a synonym), and greater than `1`
 *                          when terms were dropped in between (a stop word)
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
public record AnalyzedTerm(
	@Nonnull String term,
	@Nonnull String surfaceForm,
	int startOffset,
	int endOffset,
	int positionIncrement
) {
}
