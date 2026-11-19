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


package io.evitadb.spi.analysis;

import org.apache.lucene.analysis.TokenStream;

import javax.annotation.Nonnull;

/**
 * What a {@link FulltextSplittingAnalysisStepFactory further splitting step} contributes to a chain: a filter splitting
 * the tokenizer's words again. Immutable and shared by every chain compiled from the same definition; the stream it
 * returns is created anew for every call.
 */
@FunctionalInterface
public interface FulltextSplittingAnalysisOperation {

	/**
	 * Wraps the stream of words - the same way on both sides.
	 *
	 * @param words the words, as the tokenizer and the preceding splitting steps left them
	 * @return the wrapped stream
	 */
	@Nonnull
	TokenStream wrap(@Nonnull TokenStream words);

}
