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

package io.evitadb.test.fulltext;

import io.evitadb.index.fulltext.analysis.FulltextAnalyzer;

import javax.annotation.Nonnull;

/**
 * A single term produced by {@link FulltextAnalyzer} from one analysed text, together with everything about it
 * that cannot be reconstructed afterwards.
 *
 * This test-support record materializes the production streaming callback for golden output, dictionary
 * oracles and assertions. Production consumers use the individual callback components directly.
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
