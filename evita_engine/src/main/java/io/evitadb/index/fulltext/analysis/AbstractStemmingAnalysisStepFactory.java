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
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.miscellaneous.SetKeywordMarkerFilter;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * What the stemming steps share: they act on both sides, take the optional `protected-words` parameter and mark the
 * words of its lists with Lucene's keyword attribute before stemming, so that every stemmer below leaves them
 * unstemmed. A subclass supplies only the stemming of the already marked words.
 */
abstract sealed class AbstractStemmingAnalysisStepFactory implements FulltextWordsAnalysisStepFactory
	permits CzechStemmingAnalysisStepFactory, SlovakStemmingAnalysisStepFactory, PolishStemmingAnalysisStepFactory,
	RomanianStemmingAnalysisStepFactory, EnglishStemmingAnalysisStepFactory, GermanStemmingAnalysisStepFactory,
	StemmingAnalysisStepFactory {
	/**
	 * The only parameter: the names of the word-list resources whose words stay unstemmed.
	 */
	private static final AnalysisStepParameterDescriptor PROTECTED_WORDS_PARAMETER =
		new AnalysisStepParameterDescriptor(
			AnalysisSteps.PROTECTED_WORDS, String[].class, false,
			"Names of the word-list resources whose words the stemmer leaves unstemmed."
		);

	/**
	 * Builds the stemming of a language that carries its whole diacritics fold, as Czech, Slovak, Polish and Romanian
	 * do. The stemmer of these languages decides by the accented characters, so the two sides differ:
	 *
	 * - index side: the stemmer stems the accented word, then `ASCIIFoldingFilter` folds the stem;
	 * - query side: `ASCIIFoldingFilter` folds the word, then {@link VariantStemFilter} emits every stem the folded
	 *   word may have come from, at one position - a user typing without accents cannot be stemmed exactly, because the
	 *   fold erased what the stemmer decides by.
	 *
	 * @param indexStemmer   wraps the accented words with the stemmer of the index side
	 * @param variantStemmer creates the variant stemmer of the query side, one per stream
	 * @return the operation stemming the marked words
	 */
	@Nonnull
	static FulltextWordsAnalysisOperation foldingStemming(
		@Nonnull UnaryOperator<TokenStream> indexStemmer,
		@Nonnull Supplier<VariantStemmer> variantStemmer
	) {
		return (words, side) -> side == FulltextAnalysisSide.INDEX ?
			new ASCIIFoldingFilter(indexStemmer.apply(words)) :
			new VariantStemFilter(new ASCIIFoldingFilter(words), variantStemmer.get());
	}

	@Nonnull
	@Override
	public final List<AnalysisStepParameterDescriptor> getParameters() {
		return List.of(PROTECTED_WORDS_PARAMETER);
	}

	@Override
	public final void validate(@Nonnull Map<String, Serializable> parameters) {
		for (final Map.Entry<String, Serializable> parameter : parameters.entrySet()) {
			if (!AnalysisSteps.PROTECTED_WORDS.equals(parameter.getKey())) {
				throw new EvitaInvalidUsageException(
					"Step `" + getName() + "` accepts only the parameter `protected-words`."
				);
			}
			if (!(parameter.getValue() instanceof String[] names) || names.length == 0) {
				throw new EvitaInvalidUsageException(
					"Parameter `protected-words` of step `" + getName() + "` must name at least one word list."
				);
			}
		}
	}

	@Nonnull
	@Override
	public final Set<FulltextAnalysisSide> getSides() {
		return EnumSet.allOf(FulltextAnalysisSide.class);
	}

	@Nonnull
	@Override
	public final Set<String> getIndexSideParameters() {
		return Set.of(AnalysisSteps.PROTECTED_WORDS);
	}

	@Nonnull
	@Override
	public final FulltextWordsAnalysisOperation create(
		@Nonnull Map<String, Serializable> parameters,
		@Nonnull FulltextAnalysisContext context
	) {
		validate(parameters);
		final String[] protectedLists = (String[]) parameters.get(AnalysisSteps.PROTECTED_WORDS);
		final FulltextWordsAnalysisOperation stemming = createStemming(context);
		if (protectedLists == null) {
			return stemming;
		}
		final CharArraySet protectedWords = StopWordsAnalysisStepFactory.readWordLists(protectedLists, context);
		return (words, side) -> stemming.wrap(new SetKeywordMarkerFilter(words, protectedWords), side);
	}

	/**
	 * Creates the stemming of the words, which arrive with the protected words already marked as keywords.
	 *
	 * @param context the locale the chain is built for and the resources the parameters may name
	 * @return the operation stemming the marked words, immutable and shared by every chain compiled from it
	 */
	@Nonnull
	abstract FulltextWordsAnalysisOperation createStemming(@Nonnull FulltextAnalysisContext context);

}
