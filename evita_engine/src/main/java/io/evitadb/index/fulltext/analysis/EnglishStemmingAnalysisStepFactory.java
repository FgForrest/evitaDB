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

import io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps;
import io.evitadb.spi.analysis.FulltextAnalysisContext;
import io.evitadb.spi.analysis.FulltextWordsAnalysisOperation;
import org.apache.lucene.analysis.en.PorterStemFilter;

import javax.annotation.Nonnull;

/**
 * The `english-stemming` step: Lucene's `PorterStemFilter`, the same on both sides - the stemmer of Lucene's
 * `EnglishAnalyzer`. It neither folds nor removes the possessive `'s` (that is `english-possessive`), and expects
 * lowercased words. The words of the optional `protected-words` lists are left unstemmed.
 */
public final class EnglishStemmingAnalysisStepFactory extends AbstractStemmingAnalysisStepFactory {
	/**
	 * The stemming, stateless and shared: every call creates a new filter.
	 */
	private static final FulltextWordsAnalysisOperation STEMMING = (words, side) -> new PorterStemFilter(words);

	/**
	 * Creates a stateless provider for JPMS and classpath ServiceLoader discovery.
	 */
	public EnglishStemmingAnalysisStepFactory() {
	}

	@Nonnull
	@Override
	public String getName() {
		return AnalysisSteps.ENGLISH_STEMMING;
	}

	@Override
	public int getBehaviourVersion() {
		return 1;
	}

	@Nonnull
	@Override
	FulltextWordsAnalysisOperation createStemming(@Nonnull FulltextAnalysisContext context) {
		return STEMMING;
	}
}
