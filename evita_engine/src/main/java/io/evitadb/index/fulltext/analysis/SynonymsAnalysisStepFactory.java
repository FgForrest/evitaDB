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
import io.evitadb.api.requestResponse.schema.analysis.SynonymGroup;
import io.evitadb.api.requestResponse.schema.analysis.SynonymList;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.spi.analysis.AnalysisStepParameterDescriptor;
import io.evitadb.spi.analysis.FulltextAnalysisContext;
import io.evitadb.spi.analysis.FulltextAnalysisSide;
import io.evitadb.spi.analysis.FulltextWordsAnalysisOperation;
import io.evitadb.spi.analysis.FulltextWordsAnalysisStepFactory;
import io.evitadb.utils.CollectionUtils;
import org.apache.lucene.analysis.CharArrayMap;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The `synonyms` step: expands a query word to the other words of its synonym groups, all at the word's position, with
 * {@link SynonymExpansionFilter}. It acts on the query side only, so an edit of the lists applies to the next query
 * without reindexing. Only single words are supported. The words of a group are compared as the steps before this
 * one leave them - a word those steps remove leaves its group - and the words a query word expands to pass through the
 * steps after this one, so a stemmer placed after it stems them as it stems the query word.
 */
public final class SynonymsAnalysisStepFactory implements FulltextWordsAnalysisStepFactory {
	/**
	 * The only parameter: the names of the synonym-list resources whose groups the step expands to.
	 */
	private static final AnalysisStepParameterDescriptor SYNONYMS_PARAMETER = new AnalysisStepParameterDescriptor(
		AnalysisSteps.SYNONYM_LISTS, String[].class, true,
		"Names of the synonym-list resources whose groups a query word expands to, at least one."
	);
	/**
	 * The operation of lists without a single group: the words pass through unchanged.
	 */
	private static final FulltextWordsAnalysisOperation NO_SYNONYMS = (words, side) -> words;

	/**
	 * Creates a stateless provider for JPMS and classpath ServiceLoader discovery.
	 */
	public SynonymsAnalysisStepFactory() {
	}

	@Nonnull
	@Override
	public String getName() {
		return AnalysisSteps.SYNONYMS;
	}

	@Nonnull
	@Override
	public List<AnalysisStepParameterDescriptor> getParameters() {
		return List.of(SYNONYMS_PARAMETER);
	}

	@Override
	public void validate(@Nonnull Map<String, Serializable> parameters) {
		if (!(parameters.get(AnalysisSteps.SYNONYM_LISTS) instanceof String[] names) || names.length == 0) {
			throw new EvitaInvalidUsageException(
				"Step `synonyms` requires the parameter `synonyms` naming at least one synonym list."
			);
		}
		if (parameters.size() > 1) {
			throw new EvitaInvalidUsageException("Step `synonyms` accepts only the parameter `synonyms`.");
		}
	}

	@Nonnull
	@Override
	public Set<FulltextAnalysisSide> getSides() {
		return EnumSet.of(FulltextAnalysisSide.QUERY);
	}

	@Nonnull
	@Override
	public Set<String> getIndexSideParameters() {
		return Set.of();
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
		final Map<String, Set<String>> expansions = CollectionUtils.createLinkedHashMap(16);
		for (final String name : (String[]) parameters.get(AnalysisSteps.SYNONYM_LISTS)) {
			final FulltextResourceContent content = context.getResource(name);
			if (!(content instanceof SynonymList synonymList)) {
				throw new EvitaInvalidUsageException("Fulltext resource `" + name + "` is not a synonym list.");
			}
			for (final SynonymGroup group : synonymList.groups()) {
				addGroup(expansions, name, group.words(), context);
			}
		}
		if (expansions.isEmpty()) {
			return NO_SYNONYMS;
		}
		final CharArrayMap<String[]> synonyms = new CharArrayMap<>(expansions.size(), false);
		for (final Map.Entry<String, Set<String>> entry : expansions.entrySet()) {
			synonyms.put(entry.getKey(), entry.getValue().toArray(String[]::new));
		}
		final CharArrayMap<String[]> readOnly = CharArrayMap.unmodifiableMap(synonyms);
		return (words, side) -> new SynonymExpansionFilter(words, readOnly);
	}

	/**
	 * Adds a group to the expansions: every word of it, prepared by the steps before this one, expands to every other
	 * one. A word in several groups expands to the words of all of them.
	 *
	 * @param expansions the other words of each word, filled in
	 * @param list       the name of the list holding the group, for the error
	 * @param words      the words of the group, as the list holds them
	 * @param context    the context preparing the words
	 * @throws EvitaInvalidUsageException when a word of the group is not a single word
	 */
	private static void addGroup(
		@Nonnull Map<String, Set<String>> expansions,
		@Nonnull String list,
		@Nonnull List<String> words,
		@Nonnull FulltextAnalysisContext context
	) {
		for (final String word : words) {
			if (word.codePoints().anyMatch(Character::isWhitespace)) {
				throw new EvitaInvalidUsageException(
					"Synonym `" + word + "` of the list `" + list + "` is not a single word - only single words are " +
						"supported."
				);
			}
		}
		final Set<String> prepared = CollectionUtils.createLinkedHashSet(words.size());
		for (final String word : words) {
			prepared.addAll(context.prepare(word));
		}
		// a group the preparation leaves with less than two words has nothing to expand to
		if (prepared.size() < 2) {
			return;
		}
		for (final String word : prepared) {
			final Set<String> others = expansions.computeIfAbsent(
				word, key -> CollectionUtils.createLinkedHashSet(prepared.size() - 1)
			);
			for (final String synonym : prepared) {
				if (!word.equals(synonym)) {
					others.add(synonym);
				}
			}
		}
	}
}
