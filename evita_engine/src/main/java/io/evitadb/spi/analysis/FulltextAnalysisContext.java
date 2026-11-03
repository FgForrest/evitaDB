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
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.Locale;
import java.util.Map;

/**
 * What a {@link FulltextAnalysisStepFactory} gets besides its own parameters when it creates an operation: the locale
 * the chain is built for and the resources its parameters may name.
 *
 * @param locale    the index locale of the field; its language decides language-dependent behaviour, and
 *                  lowercasing follows its rules
 * @param resources the resources a step parameter may name, keyed by name - the built-in read-only ones and those
 *                  declared in the catalog schema; for the resource parameters of the built-in steps
 *                  ({@link io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps#RESOURCE_PARAMETERS}) the
 *                  catalog schema has already checked that every name exists and is of the parameter's kind
 */
public record FulltextAnalysisContext(
	@Nonnull Locale locale,
	@Nonnull Map<String, FulltextResourceContent> resources
) {

	/**
	 * Validates the arguments and keeps an immutable copy of the resources.
	 *
	 * @param locale    the index locale of the field
	 * @param resources the resources a step parameter may name, keyed by name
	 */
	public FulltextAnalysisContext {
		Assert.notNull(locale, "Locale must not be null.");
		Assert.notNull(resources, "Resources must not be null.");
		resources = Map.copyOf(resources);
	}

	/**
	 * Returns the resource of the given name.
	 *
	 * @param name the name a step parameter holds
	 * @return the content of the resource
	 * @throws io.evitadb.exception.GenericEvitaInternalError when no such resource exists - the catalog schema refuses
	 *                                                        a built-in step's parameter naming a missing resource,
	 *                                                        so for a built-in step reaching it means that check was
	 *                                                        bypassed
	 */
	@Nonnull
	public FulltextResourceContent getResource(@Nonnull String name) {
		final FulltextResourceContent content = this.resources.get(name);
		Assert.isPremiseValid(content != null, "Fulltext resource `" + name + "` does not exist.");
		return content;
	}

}
