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


package io.evitadb.api.requestResponse.schema;

import io.evitadb.api.requestResponse.schema.analysis.FulltextAnalysisPhases;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A named fulltext analyzer declared in the catalog schema with
 * {@link CatalogSchemaEditor#withFulltextAnalyzer(String, java.util.function.Consumer)}: the steps that turn the
 * words of a searchable field into index terms, and the words of a query into the terms looked up. One analyzer
 * serves both sides; each step knows what it does on which side.
 *
 * An analyzer has two phases (see {@link FulltextAnalysisPhases}): the text phase before the language splits the text
 * into words, and the words phase after. It defines them for all languages, may override a whole phase for single
 * languages, and may be based on another analyzer, whose phases it inherits wherever it defines none. For a field in
 * language `L` and phase `P`, the steps come from the first definition found in this order:
 *
 * 1. this analyzer's override of `P` for `L`,
 * 2. this analyzer's all-languages `P`,
 * 3. the analyzer it is based on, resolved the same way,
 * 4. no steps.
 *
 * Two analyzers are built in and reserved: {@link #TEXT}, the default of every searchable field, and
 * {@link #HTML_TEXT}, which is `text` with markup stripped first. A catalog can base its own analyzers on them but
 * cannot declare analyzers of the same names.
 */
public interface FulltextAnalyzerSchemaContract extends Serializable {
	/**
	 * Name of the built-in analyzer every searchable field uses unless it names another one: per-language stop words
	 * and stemming for the languages that have them, lower-casing for the rest.
	 */
	String TEXT = "text";
	/**
	 * Name of the built-in analyzer for fields holding markup: {@link #TEXT} with `html-strip` as its text phase.
	 */
	String HTML_TEXT = "html-text";
	/**
	 * Names of all built-in analyzers - reserved, a catalog schema cannot declare an analyzer of the same name.
	 */
	Set<String> BUILT_IN_ANALYZERS = Set.of(TEXT, HTML_TEXT);

	/**
	 * Returns the name searchable fields refer to the analyzer by.
	 *
	 * @return the name, unique among the analyzers of the catalog
	 */
	@Nonnull
	String getName();

	/**
	 * Returns the name of the analyzer this one is based on: every phase this analyzer leaves undefined is taken from
	 * it, for every language.
	 *
	 * @return the name of a built-in or declared analyzer, or empty when this analyzer is based on none
	 */
	@Nonnull
	Optional<String> getBasedOn();

	/**
	 * Returns the phases this analyzer defines for every language.
	 *
	 * @return the all-languages phases; a `null` phase is left undefined
	 */
	@Nonnull
	FulltextAnalysisPhases getAllLanguages();

	/**
	 * Returns the per-language overrides: each replaces the phases it defines for one language.
	 *
	 * @return phases keyed by the ISO 639 language code ({@link java.util.Locale#getLanguage()}); a `null` phase is
	 * left undefined
	 */
	@Nonnull
	Map<String, FulltextAnalysisPhases> getLanguages();

}
