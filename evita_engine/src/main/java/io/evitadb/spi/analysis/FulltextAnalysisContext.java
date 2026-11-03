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


package io.evitadb.spi.analysis;

import io.evitadb.api.requestResponse.schema.analysis.FulltextResourceContent;
import io.evitadb.api.requestResponse.schema.analysis.WordList;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What a {@link FulltextAnalysisStepFactory} gets besides its own parameters when it creates an operation: the locale
 * the chain is built for, the resources its parameters may name and the preparation of their words.
 *
 * @param locale      the index locale of the field; its language decides language-dependent behaviour, and
 *                    lowercasing follows its rules
 * @param resources   the resources a step parameter may name, keyed by name - the built-in read-only ones and those
 *                    declared in the catalog schema
 * @param preparation runs a word of a resource through the steps of the words phase before the step being created,
 *                    on the side being compiled - see {@link #prepare(String)}
 */
public record FulltextAnalysisContext(
	@Nonnull Locale locale,
	@Nonnull Map<String, FulltextResourceContent> resources,
	@Nonnull FulltextWordPreparation preparation
) {

	/**
	 * Validates the arguments and keeps an immutable copy of the resources.
	 *
	 * @param locale      the index locale of the field
	 * @param resources   the resources a step parameter may name, keyed by name
	 * @param preparation runs a word of a resource through the steps before the step being created
	 */
	public FulltextAnalysisContext {
		Assert.notNull(locale, "Locale must not be null.");
		Assert.notNull(resources, "Resources must not be null.");
		Assert.notNull(preparation, "Preparation must not be null.");
		resources = Map.copyOf(resources);
	}

	/**
	 * Creates a context for a step that no step precedes: its words are compared as the resources hold them.
	 *
	 * @param locale    the index locale of the field
	 * @param resources the resources a step parameter may name, keyed by name
	 */
	public FulltextAnalysisContext(@Nonnull Locale locale, @Nonnull Map<String, FulltextResourceContent> resources) {
		this(locale, resources, FulltextWordPreparation.NONE);
	}

	/**
	 * Returns the same context with another preparation of the words - the one of the next step of a chain.
	 *
	 * @param preparation runs a word through the steps before the step being created
	 * @return the context for that step
	 */
	@Nonnull
	public FulltextAnalysisContext withPreparation(@Nonnull FulltextWordPreparation preparation) {
		return new FulltextAnalysisContext(this.locale, this.resources, preparation);
	}

	/**
	 * Returns the resource of the given name, its words as the resource holds them.
	 *
	 * The catalog schema checks the resource parameters of the built-in steps
	 * ({@link io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps#RESOURCE_PARAMETERS}) without a server, but
	 * which parameters of another provider's step name a resource only the step knows - for those, this is the check.
	 *
	 * @param name the name a step parameter holds
	 * @return the content of the resource
	 * @throws EvitaInvalidUsageException when no such resource exists
	 */
	@Nonnull
	public FulltextResourceContent getResource(@Nonnull String name) {
		final FulltextResourceContent content = this.resources.get(name);
		Assert.isTrue(content != null, () -> "Fulltext resource `" + name + "` does not exist.");
		return content;
	}

	/**
	 * Prepares a word of a resource the way the step being created compares it: runs it through the steps of the
	 * words phase before that step, on the side being compiled. A step reading words of its own resources calls it
	 * for each of them, so that a list written as the user types meets the words as the chain leaves them.
	 *
	 * @param word a word of a resource
	 * @return the words the preceding steps leave of it: none, one or several - see
	 *         {@link FulltextWordPreparation#prepare(String)}
	 */
	@Nonnull
	public List<String> prepare(@Nonnull String word) {
		return this.preparation.prepare(word);
	}

	/**
	 * Returns the words of a word list, each prepared by {@link #prepare(String)}.
	 *
	 * @param name the name of a word-list resource
	 * @return the prepared words, in the order of the list, without duplicates
	 * @throws EvitaInvalidUsageException when the resource is not a word list
	 */
	@Nonnull
	public Set<String> getPreparedWords(@Nonnull String name) {
		if (!(getResource(name) instanceof WordList wordList)) {
			throw new EvitaInvalidUsageException("Fulltext resource `" + name + "` is not a word list.");
		}
		final Set<String> words = CollectionUtils.createLinkedHashSet(wordList.words().size());
		for (final String word : wordList.words()) {
			words.addAll(this.preparation.prepare(word));
		}
		return Collections.unmodifiableSet(words);
	}

}
