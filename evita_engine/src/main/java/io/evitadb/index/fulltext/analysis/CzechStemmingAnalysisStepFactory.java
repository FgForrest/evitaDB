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
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.spi.analysis.AnalysisStepParameterDescriptor;
import io.evitadb.spi.analysis.FulltextAnalysisContext;
import io.evitadb.spi.analysis.FulltextAnalysisSide;
import io.evitadb.spi.analysis.FulltextWordsAnalysisOperation;
import io.evitadb.spi.analysis.FulltextWordsAnalysisStepFactory;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.cz.CzechStemFilter;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.miscellaneous.SetKeywordMarkerFilter;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * lists hold them. The step must be the last one of the words phase on the query side, as {@link VariantStemFilter}
 * requires.
 */
public final class CzechStemmingAnalysisStepFactory implements FulltextWordsAnalysisStepFactory {
	/**
	 * The only parameter: the names of the word-list resources whose words stay unstemmed.
	 */
	private static final AnalysisStepParameterDescriptor PROTECTED_WORDS_PARAMETER = new AnalysisStepParameterDescriptor(
		AnalysisSteps.PROTECTED_WORDS, String[].class, false,
		"Names of the word-list resources whose words the stemmer leaves unstemmed."
	);

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

	@Nonnull
	@Override
	public List<AnalysisStepParameterDescriptor> getParameters() {
		return List.of(PROTECTED_WORDS_PARAMETER);
	}

	@Override
	public void validate(@Nonnull Map<String, Serializable> parameters) {
		for (final Map.Entry<String, Serializable> parameter : parameters.entrySet()) {
			if (!AnalysisSteps.PROTECTED_WORDS.equals(parameter.getKey())) {
				throw new EvitaInvalidUsageException(
					"Step `czech-stemming` accepts only the parameter `protected-words`."
				);
			}
			if (!(parameter.getValue() instanceof String[] names) || names.length == 0) {
				throw new EvitaInvalidUsageException(
					"Parameter `protected-words` of step `czech-stemming` must name at least one word list."
				);
			}
		}
	}

	@Nonnull
	@Override
	public Set<FulltextAnalysisSide> getSides() {
		return EnumSet.allOf(FulltextAnalysisSide.class);
	}

	@Nonnull
	@Override
	public Set<String> getIndexSideParameters() {
		return Set.of(AnalysisSteps.PROTECTED_WORDS);
	}

	@Override
	public int getBehaviourVersion() {
		return 1;
	}

	@Nonnull
	@Override
	public FulltextWordsAnalysisOperation create(
		@Nonnull Map<String, Serializable> parameters,
		@Nonnull FulltextAnalysisContext context
	) {
		validate(parameters);
		final String[] protectedLists = (String[]) parameters.get(AnalysisSteps.PROTECTED_WORDS);
		final CharArraySet protectedWords = protectedLists == null ?
			CharArraySet.EMPTY_SET : StopWordsAnalysisStepFactory.readWordLists(protectedLists, context);
		return (words, side) -> {
			final TokenStream marked = protectedWords.isEmpty() ?
				words : new SetKeywordMarkerFilter(words, protectedWords);
			return side == FulltextAnalysisSide.INDEX ?
				new ASCIIFoldingFilter(new CzechStemFilter(marked)) :
				new VariantStemFilter(new ASCIIFoldingFilter(marked), new CzechVariantStemmer());
		};
	}
}
