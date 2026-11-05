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

import io.evitadb.index.fulltext.analysis.CompiledFulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzer;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.util.Set;

/** Prepares the raw final word in its profile's normalization space without losing it to a stop filter or stemmer. */
final class FulltextQueryFragment {
	/** Prevents construction of this query analysis utility. */
	private FulltextQueryFragment() { }

	/**
	 * Applies the profile's cached fragment chain: query surface normalization without the steps whose factory
	 * declines {@link io.evitadb.spi.analysis.FulltextWordsAnalysisStepFactory#participatesInQueryFragment()} - the
	 * built-in stop filters and synonyms - so that they neither remove nor lengthen a word still being typed. The
	 * stemmer contributes its declared surface normalization only. Text-aware split terms use the chain without the
	 * text phase.
	 *
	 * @param analyzer cached profile preparations
	 * @param word the shared final split word
	 * @param textSplit whether shared splitting already applied query text operations
	 * @return distinct nonempty normalized fragments; empty when a participating step drops the word
	 */
	@Nonnull
	static String[] prepare(
		@Nonnull CompiledFulltextAnalyzer analyzer, @Nonnull TextMatchesTranslator.QueryToken word, boolean textSplit
	) {
		final FulltextAnalyzer chain = textSplit ? analyzer.textSplitFragment() : analyzer.fragment();
		final Set<String> fragments = CollectionUtils.createLinkedHashSet(2);
		for (final String term : word.terms()) {
			chain.analyze(term, (value, surface, start, end, increment) -> {
				if (!value.isEmpty()) {
					fragments.add(value);
				}
			});
		}
		return fragments.toArray(String[]::new);
	}
}
