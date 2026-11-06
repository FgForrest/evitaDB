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
import io.evitadb.spi.analysis.FulltextAnalysisStepFactory;
import io.evitadb.spi.analysis.FulltextWordsAnalysisOperation;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The `stemming` step: runs the built-in stemming step of the language the chain is built for - `czech-stemming` for
 * Czech, `english-stemming` for English and so on - and leaves the words alone in a language without one. It lets an
 * analyzer defined for all languages stem each of them its own way. The optional `protected-words` lists are passed
 * on, so the words they hold stay unstemmed in every language; whatever the chosen step requires of its place (the
 * folding stemmers must be last on the query side) applies to this step in that language too.
 */
public final class StemmingAnalysisStepFactory extends AbstractStemmingAnalysisStepFactory {
	/**
	 * The stemming step of each language that has one, keyed by language code.
	 */
	private static final Map<String, AbstractStemmingAnalysisStepFactory> STEMMING_BY_LANGUAGE = Map.of(
		"cs", new CzechStemmingAnalysisStepFactory(),
		"sk", new SlovakStemmingAnalysisStepFactory(),
		"pl", new PolishStemmingAnalysisStepFactory(),
		"ro", new RomanianStemmingAnalysisStepFactory(),
		"en", new EnglishStemmingAnalysisStepFactory(),
		"de", new GermanStemmingAnalysisStepFactory()
	);
	/**
	 * The stemming of a language without a stemming step: the words pass through unchanged.
	 */
	private static final FulltextWordsAnalysisOperation NO_STEMMING = (words, side) -> words;

	/**
	 * Creates a stateless provider for JPMS and classpath ServiceLoader discovery.
	 */
	public StemmingAnalysisStepFactory() {
	}

	@Nonnull
	@Override
	public String getName() {
		return AnalysisSteps.STEMMING;
	}

	/**
	 * Returns the version of the dispatch itself. The terms in a language are those of the step it picks, so what
	 * the index side produces in that language depends on that step's version too.
	 *
	 * @return the behaviour version of the dispatch, starting at 1
	 */
	@Override
	public int getBehaviourVersion() {
		return 1;
	}

	@Nonnull
	@Override
	public List<FulltextAnalysisStepFactory> getDelegates(@Nonnull Locale locale) {
		final AbstractStemmingAnalysisStepFactory stemming = STEMMING_BY_LANGUAGE.get(locale.getLanguage());
		return stemming == null ? List.of() : List.of(stemming);
	}

	@Nonnull
	@Override
	FulltextWordsAnalysisOperation createStemming(
		@Nonnull String stepName,
		@Nonnull FulltextAnalysisContext context
	) {
		final AbstractStemmingAnalysisStepFactory stemming =
			STEMMING_BY_LANGUAGE.get(context.locale().getLanguage());
		return stemming == null ? NO_STEMMING : stemming.createStemming(stepName, context);
	}
}
