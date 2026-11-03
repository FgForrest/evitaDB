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

import io.evitadb.api.requestResponse.schema.analysis.TextAnalysisStep;
import io.evitadb.api.requestResponse.schema.analysis.WordsAnalysisStep;

import javax.annotation.Nonnull;
import java.util.function.Consumer;

/**
 * Defines a fulltext analyzer inside
 * {@link CatalogSchemaEditor#withFulltextAnalyzer(String, Consumer)}. The definition starts empty and the result
 * replaces the analyzer's previous definition as a whole. How the phases resolve per language is described on
 * {@link FulltextAnalyzerSchemaContract}.
 *
 * An analyzer either is {@link #basedOn(String) based on} another one or defines its all-languages phases
 * ({@link #allLanguages(Consumer)}, or {@link #text(TextAnalysisStep...)} / {@link #words(WordsAnalysisStep...)});
 * per-language overrides may follow either way. Examples:
 *
 * ```
 * catalog.withFulltextAnalyzer("html-title", a -> a.basedOn("text").text(htmlStrip()));
 * catalog.withFulltextAnalyzer("codes", a -> a.allLanguages(l -> l.words(lowercase())));
 * ```
 */
public interface FulltextAnalyzerSchemaEditor {

	/**
	 * Bases the analyzer on another one: every phase this analyzer leaves undefined is taken from it, for every
	 * language.
	 *
	 * @param analyzerName the name of a built-in or declared analyzer
	 * @return this editor
	 */
	@Nonnull
	FulltextAnalyzerSchemaEditor basedOn(@Nonnull String analyzerName);

	/**
	 * Replaces the text phase for all languages.
	 *
	 * @param steps the steps in execution order; none defines the phase as empty
	 * @return this editor
	 */
	@Nonnull
	FulltextAnalyzerSchemaEditor text(@Nonnull TextAnalysisStep... steps);

	/**
	 * Replaces the words phase for all languages.
	 *
	 * @param steps the steps in execution order; none defines the phase as empty
	 * @return this editor
	 */
	@Nonnull
	FulltextAnalyzerSchemaEditor words(@Nonnull WordsAnalysisStep... steps);

	/**
	 * Defines phases for all languages.
	 *
	 * @param phases defines the phases
	 * @return this editor
	 */
	@Nonnull
	FulltextAnalyzerSchemaEditor allLanguages(@Nonnull Consumer<FulltextAnalysisPhasesEditor> phases);

	/**
	 * Overrides phases for one language; a phase the override leaves undefined keeps the all-languages one.
	 *
	 * @param language the ISO 639 language code, as {@link java.util.Locale#getLanguage()} returns it
	 * @param phases   defines the overriding phases
	 * @return this editor
	 */
	@Nonnull
	FulltextAnalyzerSchemaEditor language(
		@Nonnull String language,
		@Nonnull Consumer<FulltextAnalysisPhasesEditor> phases
	);

}
