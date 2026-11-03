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
import org.apache.lucene.analysis.snowball.SnowballFilter;
import org.tartarus.snowball.ext.RomanianStemmer;

import javax.annotation.Nonnull;

/**
 * The `romanian-stemming` step, which works like `czech-stemming`: the index side stems the accented word with the
 * Snowball `RomanianStemmer` and folds the stem, the query side folds the word and emits every stem
 * {@link RomanianVariantStemmer} derives from it at one position. The Snowball tables of the pinned Lucene are written
 * with the cedilla letters, so a text spelt with comma below needs `romanian-normalization` before this step. The
 * words of the optional `protected-words` lists are left unstemmed on both sides (but folded). The step must be the
 * last one of the words phase on the query side, as {@link VariantStemFilter} requires.
 */
public final class RomanianStemmingAnalysisStepFactory extends AbstractStemmingAnalysisStepFactory {

	/**
	 * Creates a stateless provider for JPMS and classpath ServiceLoader discovery.
	 */
	public RomanianStemmingAnalysisStepFactory() {
	}

	@Nonnull
	@Override
	public String getName() {
		return AnalysisSteps.ROMANIAN_STEMMING;
	}

	@Override
	public int getBehaviourVersion() {
		return 1;
	}

	@Nonnull
	@Override
	FulltextWordsAnalysisOperation createStemming(@Nonnull FulltextAnalysisContext context) {
		return foldingStemming(words -> new SnowballFilter(words, new RomanianStemmer()), RomanianVariantStemmer::new);
	}
}
