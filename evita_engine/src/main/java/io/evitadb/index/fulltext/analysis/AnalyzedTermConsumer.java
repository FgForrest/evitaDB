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
import java.util.function.Supplier;

/**
 * Callback invoked once per term produced by {@link FulltextAnalyzer#analyze(String, AnalyzedTermConsumer)}.
 *
 * This is the streaming half of the tokenization contract and exists so that the write path — which analyses
 * every stored value of every indexed attribute — never has to materialize a list it immediately walks and
 * throws away. The components are passed individually rather than as an {@link AnalyzedTerm} for the same
 * reason: a consumer that only needs the term does not pay for a record per token either.
 * {@link FulltextAnalyzer#getTerms(String)} is the convenience implementation that does collect them.
 *
 * The parameters mirror {@link AnalyzedTerm} exactly; see its javadoc for what each one means and why the
 * offsets and the surface form are handed out even though nothing consumes them yet.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@FunctionalInterface
public interface AnalyzedTermConsumer {

	/**
	 * Accepts a single analysed term.
	 *
	 * @param term              the analysed term
	 * @param surfaceForm       lazily provided substring of the NFC-normalized input the term was produced from
	 * @param startOffset       start offset of the surface form in the NFC-normalized input
	 * @param endOffset         end offset (exclusive) of the surface form in the NFC-normalized input
	 * @param positionIncrement distance from the preceding term's position
	 */
	void accept(
		@Nonnull String term,
		@Nonnull Supplier<String> surfaceForm,
		int startOffset,
		int endOffset,
		int positionIncrement
	);

}
