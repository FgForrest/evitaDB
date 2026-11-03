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
import org.apache.lucene.analysis.de.GermanLightStemFilter;
import org.apache.lucene.analysis.de.GermanNormalizationFilter;

import javax.annotation.Nonnull;

/**
 * The `german-stemming` step: Lucene's `GermanNormalizationFilter` (umlauts and `ß` normalized) followed by
 * `GermanLightStemFilter`, the same on both sides - the tail of Lucene's `GermanAnalyzer`. It expects lowercased
 * words. The words of the optional `protected-words` lists are left unstemmed; the normalization still applies to
 * them, as it does in `GermanAnalyzer`.
 */
public final class GermanStemmingAnalysisStepFactory extends AbstractStemmingAnalysisStepFactory {

	/**
	 * Creates a stateless provider for JPMS and classpath ServiceLoader discovery.
	 */
	public GermanStemmingAnalysisStepFactory() {
	}

	@Nonnull
	@Override
	public String getName() {
		return AnalysisSteps.GERMAN_STEMMING;
	}

	@Override
	public int getBehaviourVersion() {
		return 1;
	}

	@Nonnull
	@Override
	FulltextWordsAnalysisOperation createStemming(
		@Nonnull String stepName,
		@Nonnull FulltextAnalysisContext context
	) {
		return plainStemming(stepName, words -> new GermanLightStemFilter(new GermanNormalizationFilter(words)));
	}
}
