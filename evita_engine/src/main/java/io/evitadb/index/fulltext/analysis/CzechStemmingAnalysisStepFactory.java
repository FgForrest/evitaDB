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
import org.apache.lucene.analysis.cz.CzechStemFilter;

import javax.annotation.Nonnull;

/**
 * The `czech-stemming` step. It carries the whole diacritics fold of the language, so the two sides differ:
 *
 * - index side: Lucene's `CzechStemFilter` stems the accented word, then `ASCIIFoldingFilter` folds the stem - the
 *   stemmer switches on accented characters and finds nothing in folded text (see
 *   {@link DiacriticsFoldingAnalyzerWrapper});
 * - query side: `ASCIIFoldingFilter` folds the word, then {@link VariantStemFilter} with {@link CzechVariantStemmer}
 *   emits every stem the folded word may have come from, at one position - a user typing without accents cannot be
 *   stemmed exactly, because the fold erased what the stemmer decides by.
 *
 * The words of the optional `protected-words` lists are left unstemmed on both sides (but folded), compared as the
 * steps before this one leave them. The step refuses a fold or another stemmer before it and, on the query side, any
 * words step after it, as {@link VariantStemFilter} requires.
 */
public final class CzechStemmingAnalysisStepFactory extends AbstractStemmingAnalysisStepFactory {

	/**
	 * Creates a stateless provider for JPMS and classpath ServiceLoader discovery.
	 */
	public CzechStemmingAnalysisStepFactory() {
	}

	@Nonnull
	@Override
	public String getName() {
		return AnalysisSteps.CZECH_STEMMING;
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
		return foldingStemming(stepName, CzechStemFilter::new, CzechVariantStemmer::new);
	}
}
