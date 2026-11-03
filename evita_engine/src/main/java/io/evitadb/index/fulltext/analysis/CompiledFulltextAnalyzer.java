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

import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Set;

/**
 * Shared compiled chains and persistent index identities of one resolved analyzer in one locale. The catalog cache
 * owns their lifecycle; splitting may be shared with other compiled analyzers in the same locale. Equality and
 * hashing use instance identity so grouping and cache bookkeeping never traverse the prepared stop-word set.
 *
 * @param index the chain used to build postings
 * @param word the query chain applied separately to each shared term
 * @param splitting the locale's shared word splitting
 * @param fingerprint the persistent index and word-phase identities
 * @param foldedStopWords prepared built-in index stop words, lower-cased and ASCII-folded
 */
public record CompiledFulltextAnalyzer(
	@Nonnull FulltextAnalyzer index,
	@Nonnull FulltextAnalyzer word,
	@Nonnull FulltextAnalyzer splitting,
	@Nonnull FulltextAnalyzerFingerprint fingerprint,
	@Nonnull Set<String> foldedStopWords
) {
	/**
	 * Freezes the prepared stop words.
	 *
	 * @param index the index chain
	 * @param word the query word chain
	 * @param splitting the shared splitting chain
	 * @param fingerprint the persistent identities
	 * @param foldedStopWords the prepared folded index stop words
	 */
	public CompiledFulltextAnalyzer {
		foldedStopWords = Set.copyOf(foldedStopWords);
	}

	@Override
	public boolean equals(@Nullable Object other) {
		return this == other;
	}

	@Override
	public int hashCode() {
		return System.identityHashCode(this);
	}

	/**
	 * Lower-cases and folds a surface form with Lucene's ASCII folding table.
	 *
	 * @param word the surface form to compare with prepared stop words
	 * @return its lower-cased ASCII-folded form
	 */
	@Nonnull
	public static String foldStopWord(@Nonnull String word) {
		final char[] lowerCased = word.toLowerCase(Locale.ROOT).toCharArray();
		final char[] folded = new char[lowerCased.length * 4];
		final int length = ASCIIFoldingFilter.foldToASCII(lowerCased, 0, folded, 0, lowerCased.length);
		return new String(folded, 0, length);
	}

}
