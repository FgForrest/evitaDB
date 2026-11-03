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
import io.evitadb.api.requestResponse.schema.analysis.FulltextResourceContent.FulltextResourceKind;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.spi.analysis.AnalysisStepParameterDescriptor;
import io.evitadb.spi.analysis.FulltextAnalysisContext;
import io.evitadb.spi.analysis.FulltextAnalysisSide;
import io.evitadb.spi.analysis.FulltextAnalysisTrait;
import io.evitadb.spi.analysis.FulltextWordsAnalysisOperation;
import io.evitadb.spi.analysis.FulltextWordsAnalysisStepFactory;
import io.evitadb.spi.analysis.FulltextWordsPlacement;
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
 * unstemmed. The words of the lists are compared as the steps before the stemmer leave them. A subclass supplies only
 * the stemming of the already marked words.
 *
 * Every stemmer declares {@link FulltextAnalysisTrait#STEMS} and refuses a step with that trait before it, because it
 * would stem a stem. The folding stemmers - Czech, Slovak, Polish and Romanian - also declare
 * {@link FulltextAnalysisTrait#FOLDS_DIACRITICS}, refuse a folding step before them, because they decide by the
 * diacritics, and on the query side insist on being the last words step, because {@link VariantStemFilter} emits the
 * variants of a word without capturing the token state a later step would change.
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
			"Names of the word-list resources whose words the stemmer leaves unstemmed.",
			FulltextResourceKind.WORD_LIST
		);
	/**
	 * The traits of a stemmer that keeps the diacritics.
	 */
	private static final Set<FulltextAnalysisTrait> PLAIN_TRAITS = EnumSet.of(FulltextAnalysisTrait.STEMS);
	/**
	 * The traits of a stemmer that folds the diacritics.
	 */
	private static final Set<FulltextAnalysisTrait> FOLDING_TRAITS =
		EnumSet.of(FulltextAnalysisTrait.STEMS, FulltextAnalysisTrait.FOLDS_DIACRITICS);

	/**
	 * Builds the stemming of a language that carries its whole diacritics fold, as Czech, Slovak, Polish and Romanian
	 * do. The stemmer of these languages decides by the accented characters, so the two sides differ:
	 *
	 * - index side: the stemmer stems the accented word, then `ASCIIFoldingFilter` folds the stem;
	 * - query side: `ASCIIFoldingFilter` folds the word, then {@link VariantStemFilter} emits every stem the folded
	 *   word may have come from, at one position - a user typing without accents cannot be stemmed exactly, because the
	 *   fold erased what the stemmer decides by.
	 *
	 * @param stepName       the name of the step as the analyzer names it, for the refusals
	 * @param indexStemmer   wraps the accented words with the stemmer of the index side
	 * @param variantStemmer creates the variant stemmer of the query side, one per stream
	 * @return the operation stemming the marked words
	 */
	@Nonnull
	static FulltextWordsAnalysisOperation foldingStemming(
		@Nonnull String stepName,
		@Nonnull UnaryOperator<TokenStream> indexStemmer,
		@Nonnull Supplier<VariantStemmer> variantStemmer
	) {
		return new StemmingOperation(
			stepName, FOLDING_TRAITS,
			(words, side) -> side == FulltextAnalysisSide.INDEX ?
				new ASCIIFoldingFilter(indexStemmer.apply(words)) :
				new VariantStemFilter(new ASCIIFoldingFilter(words), variantStemmer.get())
		);
	}

	/**
	 * Builds the stemming of a language whose stemmer works the same on both sides and keeps the diacritics, as English
	 * and German do.
	 *
	 * @param stepName the name of the step as the analyzer names it, for the refusals
	 * @param stemmer  wraps the words with the stemmer
	 * @return the operation stemming the marked words
	 */
	@Nonnull
	static FulltextWordsAnalysisOperation plainStemming(
		@Nonnull String stepName,
		@Nonnull UnaryOperator<TokenStream> stemmer
	) {
		return new StemmingOperation(stepName, PLAIN_TRAITS, (words, side) -> stemmer.apply(words));
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
		final FulltextWordsAnalysisOperation stemming = createStemming(getName(), context);
		if (protectedLists == null) {
			return stemming;
		}
		return new ProtectingOperation(
			stemming, StopWordsAnalysisStepFactory.readWordLists(protectedLists, context)
		);
	}

	/**
	 * Creates the stemming of the words, which arrive with the protected words already marked as keywords.
	 *
	 * @param stepName the name of the step as the analyzer names it - `stemming` passes its own on to the step it
	 *                 runs, so that a refusal names the step the user wrote
	 * @param context  the locale the chain is built for and the resources the parameters may name
	 * @return the operation stemming the marked words, immutable and shared by every chain compiled from it
	 */
	@Nonnull
	abstract FulltextWordsAnalysisOperation createStemming(
		@Nonnull String stepName,
		@Nonnull FulltextAnalysisContext context
	);

	/**
	 * A stemming with its traits and the checks of its place.
	 */
	private static final class StemmingOperation implements FulltextWordsAnalysisOperation {
		/**
		 * The name of the step as the analyzer names it, for the refusals.
		 */
		@Nonnull private final String stepName;
		/**
		 * The traits of the stemming, the same on both sides.
		 */
		@Nonnull private final Set<FulltextAnalysisTrait> traits;
		/**
		 * Wraps the marked words with the stemmer.
		 */
		@Nonnull private final FulltextWordsAnalysisOperation stemming;

		/**
		 * Creates the operation.
		 *
		 * @param stepName the name of the step as the analyzer names it, for the refusals
		 * @param traits   the traits of the stemming, the same on both sides
		 * @param stemming wraps the marked words with the stemmer
		 */
		StemmingOperation(
			@Nonnull String stepName,
			@Nonnull Set<FulltextAnalysisTrait> traits,
			@Nonnull FulltextWordsAnalysisOperation stemming
		) {
			this.stepName = stepName;
			this.traits = traits;
			this.stemming = stemming;
		}

		@Nonnull
		@Override
		public TokenStream wrap(@Nonnull TokenStream words, @Nonnull FulltextAnalysisSide side) {
			return this.stemming.wrap(words, side);
		}

		@Nonnull
		@Override
		public Set<FulltextAnalysisTrait> getTraits(@Nonnull FulltextAnalysisSide side) {
			return this.traits;
		}

		@Override
		public void verifyPlacement(@Nonnull FulltextWordsPlacement placement) {
			placement.findPreceding(FulltextAnalysisTrait.STEMS).ifPresent(stemmer -> {
				throw new EvitaInvalidUsageException(
					"Step `" + this.stepName + "` would stem the words step `" + stemmer.name() +
						"` has already stemmed."
				);
			});
			if (!this.traits.contains(FulltextAnalysisTrait.FOLDS_DIACRITICS)) {
				return;
			}
			placement.findPreceding(FulltextAnalysisTrait.FOLDS_DIACRITICS).ifPresent(folding -> {
				throw new EvitaInvalidUsageException(
					"Step `" + this.stepName + "` decides by the diacritics and folds them itself, so `" +
						folding.name() + "` cannot precede it."
				);
			});
			if (placement.side() == FulltextAnalysisSide.QUERY && !placement.isLast()) {
				throw new EvitaInvalidUsageException(
					"Step `" + this.stepName + "` emits the stem variants of a query word and must be the last " +
						"words step on the query side, but `" + placement.following().get(0).name() +
						"` follows it."
				);
			}
		}
	}

	/**
	 * Marks the protected words as keywords before the stemming it wraps, and otherwise is that stemming: the same
	 * traits, the same checks of its place.
	 */
	private static final class ProtectingOperation implements FulltextWordsAnalysisOperation {
		/**
		 * The stemming of the marked words.
		 */
		@Nonnull private final FulltextWordsAnalysisOperation stemming;
		/**
		 * The protected words, as the steps before the stemmer leave them.
		 */
		@Nonnull private final CharArraySet protectedWords;

		/**
		 * Creates the operation.
		 *
		 * @param stemming       the stemming of the marked words
		 * @param protectedWords the protected words, as the steps before the stemmer leave them
		 */
		ProtectingOperation(
			@Nonnull FulltextWordsAnalysisOperation stemming,
			@Nonnull CharArraySet protectedWords
		) {
			this.stemming = stemming;
			this.protectedWords = protectedWords;
		}

		@Nonnull
		@Override
		public TokenStream wrap(@Nonnull TokenStream words, @Nonnull FulltextAnalysisSide side) {
			return this.stemming.wrap(new SetKeywordMarkerFilter(words, this.protectedWords), side);
		}

		@Nonnull
		@Override
		public Set<FulltextAnalysisTrait> getTraits(@Nonnull FulltextAnalysisSide side) {
			return this.stemming.getTraits(side);
		}

		@Override
		public void verifyPlacement(@Nonnull FulltextWordsPlacement placement) {
			this.stemming.verifyPlacement(placement);
		}
	}

}
