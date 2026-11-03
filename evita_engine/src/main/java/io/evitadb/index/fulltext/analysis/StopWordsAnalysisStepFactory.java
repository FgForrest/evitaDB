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
import io.evitadb.api.requestResponse.schema.analysis.FulltextResourceContent;
import io.evitadb.api.requestResponse.schema.analysis.WordList;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.spi.analysis.AnalysisStepParameterDescriptor;
import io.evitadb.spi.analysis.FulltextAnalysisContext;
import io.evitadb.spi.analysis.FulltextAnalysisSide;
import io.evitadb.spi.analysis.FulltextWordsAnalysisOperation;
import io.evitadb.spi.analysis.FulltextWordsAnalysisStepFactory;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.StopFilter;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The `stop-words` step: removes the words of the named word lists, on both sides, with Lucene's `StopFilter`. A
 * removed word leaves a gap in the positions, as in the stop filters of the built-in chains. The words are compared
 * as the list holds them.
 */
public final class StopWordsAnalysisStepFactory implements FulltextWordsAnalysisStepFactory {
	/**
	 * The only parameter: the names of the word-list resources whose words are removed, at least one.
	 */
	private static final AnalysisStepParameterDescriptor WORDS_PARAMETER = new AnalysisStepParameterDescriptor(
		AnalysisSteps.WORDS, String[].class, true,
		"Names of the word-list resources whose words are removed from the text, at least one."
	);

	/**
	 * Creates a stateless provider for JPMS and classpath ServiceLoader discovery.
	 */
	public StopWordsAnalysisStepFactory() {
	}

	@Nonnull
	@Override
	public String getName() {
		return AnalysisSteps.STOP_WORDS;
	}

	@Nonnull
	@Override
	public List<AnalysisStepParameterDescriptor> getParameters() {
		return List.of(WORDS_PARAMETER);
	}

	@Override
	public void validate(@Nonnull Map<String, Serializable> parameters) {
		if (!(parameters.get(AnalysisSteps.WORDS) instanceof String[] names) || names.length == 0) {
			throw new EvitaInvalidUsageException(
				"Step `stop-words` requires the parameter `words` naming at least one word list."
			);
		}
		if (parameters.size() > 1) {
			throw new EvitaInvalidUsageException("Step `stop-words` accepts only the parameter `words`.");
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
		return Set.of(AnalysisSteps.WORDS);
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
		final CharArraySet stopWords = readWordLists((String[]) parameters.get(AnalysisSteps.WORDS), context);
		return (words, side) -> new StopFilter(words, stopWords);
	}

	/**
	 * Collects the words of the named word lists into one read-only set.
	 *
	 * @param names   names of word-list resources
	 * @param context the context resolving the names
	 * @return the words of all the lists, compared exactly
	 */
	@Nonnull
	static CharArraySet readWordLists(@Nonnull String[] names, @Nonnull FulltextAnalysisContext context) {
		final CharArraySet words = new CharArraySet(64, false);
		for (final String name : names) {
			final FulltextResourceContent content = context.getResource(name);
			if (!(content instanceof WordList wordList)) {
				throw new EvitaInvalidUsageException("Fulltext resource `" + name + "` is not a word list.");
			}
			words.addAll(wordList.words());
		}
		return CharArraySet.unmodifiableSet(words);
	}
}
