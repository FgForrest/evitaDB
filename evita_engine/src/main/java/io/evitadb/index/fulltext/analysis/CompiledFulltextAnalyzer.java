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

import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.spi.analysis.FulltextWordPreparation;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Shared compiled chains and persistent index identities of one resolved analyzer in one locale. The catalog cache
 * owns their lifecycle; splitting may be shared with other compiled analyzers in the same locale. Equality and
 * hashing use instance identity so grouping and cache bookkeeping never traverse the prepared stop-word set.
 *
 * @param word the query chain applied separately to each shared term
 * @param splitting the locale's shared word splitting
 * @param queryText the resolved query-side text steps; a query compares them by content across its field groups to
 *                  decide whether the shared split may apply them first
 * @param textSplitting the splitting chain preceded by queryText, with offsets corrected to the original input
 * @param textSplitWord the query chain applied to each term textSplitting produced: `word` without queryText, which
 *                      such a term has already been through; the very instance of `word` when queryText is empty
 * @param indexSurface the full index chain with a passive pre-stem capture: it builds the postings and, from the
 *                     capture, the surface pairs of the index's surface dictionary
 * @param surface the query lookup-key preparation: the query chain of one word up to its first stemming operation,
 *                ending with that operation's embedded normalization - the one that also prepares the folded
 *                component of the surface keys
 * @param textSplitSurface surface preparation without already applied query text operations; identical to
 *                         `surface` when the query text phase is empty
 * @param fragment the unfinished-word fragment preparation: the `surface` chain without the words steps whose
 *                 factory declines
 *                 {@link io.evitadb.spi.analysis.FulltextWordsAnalysisStepFactory#participatesInQueryFragment()},
 *                 the built-in stop filters and synonyms among them; it keeps the stemmer's surface normalization
 * @param textSplitFragment fragment preparation without already applied query text operations; identical to
 *                          `fragment` when the query text phase is empty
 * @param fingerprint the persistent index and word-phase identities
 * @param foldedStopWords prepared built-in index stop words, lower-cased and ASCII-folded
 * @param stopWordPreparations per-stop-step query surface preparation paired with its prepared index stop words
 */
public record CompiledFulltextAnalyzer(
	@Nonnull FulltextAnalyzer word,
	@Nonnull FulltextAnalyzer splitting,
	@Nonnull List<AnalysisStep> queryText,
	@Nonnull FulltextAnalyzer textSplitting,
	@Nonnull FulltextAnalyzer textSplitWord,
	@Nonnull FulltextAnalyzer indexSurface,
	@Nonnull FulltextAnalyzer surface,
	@Nonnull FulltextAnalyzer textSplitSurface,
	@Nonnull FulltextAnalyzer fragment,
	@Nonnull FulltextAnalyzer textSplitFragment,
	@Nonnull FulltextAnalyzerFingerprint fingerprint,
	@Nonnull Set<String> foldedStopWords,
	@Nonnull List<StopWordPreparation> stopWordPreparations
) {
	/**
	 * Freezes the prepared stop words.
	 *
	 * @param word the query word chain
	 * @param splitting the shared splitting chain
	 * @param queryText the query-side text steps
	 * @param textSplitting the text-aware splitting chain
	 * @param textSplitWord the query word chain for the terms of the text-aware splitting
	 * @param indexSurface the cached capture-equipped full index chain
	 * @param surface the cached query surface normalization
	 * @param textSplitSurface surface preparation for text-aware split terms
	 * @param fragment cached fragment preparation
	 * @param textSplitFragment fragment chain for text-aware split terms
	 * @param fingerprint the persistent identities
	 * @param foldedStopWords the prepared folded index stop words
	 * @param stopWordPreparations query preparation corresponding to each built-in index stop step
	 */
	public CompiledFulltextAnalyzer {
		queryText = List.copyOf(queryText);
		foldedStopWords = Set.copyOf(foldedStopWords);
		stopWordPreparations = List.copyOf(stopWordPreparations);
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
	 * Recognizes a folded stop word after the query steps preceding the corresponding stop filter have prepared
	 * its original surface. Later stemming and synonyms must not change this comparison.
	 *
	 * @param surface the original NFC query word, including any possessive or elided article
	 * @return true when a prepared surface form equals a folded index stop word of the same step
	 */
	public boolean isFoldedStopWord(@Nonnull String surface) {
		if (this.foldedStopWords.isEmpty()) {
			return false;
		}
		for (final StopWordPreparation stop : this.stopWordPreparations) {
			for (final String prepared : stop.preparation().prepare(surface)) {
				if (stop.foldedWords().contains(foldStopWord(prepared))) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * One built-in stop step's index words paired with preparation by its preceding query steps.
	 * Preparation creates and closes streams per call and owns no analyzer lifecycle.
	 *
	 * @param foldedWords immutable lower-cased and folded words prepared on the index side
	 * @param preparation query-side text and words operations preceding this stop step
	 */
	public record StopWordPreparation(
		@Nonnull Set<String> foldedWords, @Nonnull FulltextWordPreparation preparation
	) {
		/**
		 * Freezes the step's stop words independently of the compilation collector.
		 *
		 * @param foldedWords prepared index words
		 * @param preparation preceding query operations
		 */
		public StopWordPreparation {
			foldedWords = Set.copyOf(foldedWords);
		}
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
