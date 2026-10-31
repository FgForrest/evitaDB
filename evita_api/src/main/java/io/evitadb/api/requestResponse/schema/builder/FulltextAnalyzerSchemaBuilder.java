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


package io.evitadb.api.requestResponse.schema.builder;

import io.evitadb.api.requestResponse.schema.FulltextAnalysisPhasesEditor;
import io.evitadb.api.requestResponse.schema.FulltextAnalyzerSchemaEditor;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.api.requestResponse.schema.analysis.FulltextAnalysisPhases;
import io.evitadb.api.requestResponse.schema.dto.FulltextAnalyzerSchema;
import io.evitadb.api.requestResponse.schema.dto.FulltextLanguageSchema;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Consumer;

/**
 * Collects the definition of a fulltext analyzer for
 * {@link InternalCatalogSchemaBuilder#withFulltextAnalyzer(String, Consumer)}. It starts empty.
 */
@NotThreadSafe
final class FulltextAnalyzerSchemaBuilder implements FulltextAnalyzerSchemaEditor {
	/**
	 * The name of the analyzer this one is based on, or `null` when it is based on none.
	 */
	@Nullable private String basedOn;
	/**
	 * The phases for every language.
	 */
	@Nonnull private final FulltextAnalysisPhasesBuilder allLanguages = new FulltextAnalysisPhasesBuilder();
	/**
	 * The per-language overrides in the order they were first declared.
	 */
	@Nonnull private final Map<String, FulltextAnalysisPhasesBuilder> languages = CollectionUtils.createLinkedHashMap(4);

	@Nonnull
	@Override
	public FulltextAnalyzerSchemaEditor basedOn(@Nonnull String analyzerName) {
		Assert.notNull(analyzerName, "Analyzer name must not be null.");
		this.basedOn = analyzerName;
		return this;
	}

	@Nonnull
	@Override
	public FulltextAnalyzerSchemaEditor text(@Nonnull AnalysisStep... steps) {
		this.allLanguages.text(steps);
		return this;
	}

	@Nonnull
	@Override
	public FulltextAnalyzerSchemaEditor words(@Nonnull AnalysisStep... steps) {
		this.allLanguages.words(steps);
		return this;
	}

	@Nonnull
	@Override
	public FulltextAnalyzerSchemaEditor allLanguages(@Nonnull Consumer<FulltextAnalysisPhasesEditor> phases) {
		Assert.notNull(phases, "Phases must not be null.");
		phases.accept(this.allLanguages);
		return this;
	}

	@Nonnull
	@Override
	public FulltextAnalyzerSchemaEditor language(
		@Nonnull String language,
		@Nonnull Consumer<FulltextAnalysisPhasesEditor> phases
	) {
		FulltextLanguageSchema.assertLanguageCode(language);
		Assert.notNull(phases, "Phases must not be null.");
		phases.accept(this.languages.computeIfAbsent(language, lang -> new FulltextAnalysisPhasesBuilder()));
		return this;
	}

	/**
	 * Returns the name of the analyzer this one is based on.
	 *
	 * @return the name, or `null` when the analyzer is based on none
	 */
	@Nullable
	String getBasedOn() {
		return this.basedOn;
	}

	/**
	 * Returns the collected phases for every language.
	 *
	 * @return the all-languages phases, undefined where never set
	 */
	@Nonnull
	FulltextAnalysisPhases getAllLanguages() {
		return this.allLanguages.toPhases();
	}

	/**
	 * Returns the collected per-language overrides.
	 *
	 * @return the overrides keyed by ISO 639 language code
	 */
	@Nonnull
	Map<String, FulltextAnalysisPhases> getLanguages() {
		final Map<String, FulltextAnalysisPhases> result = CollectionUtils.createLinkedHashMap(this.languages.size());
		for (final Entry<String, FulltextAnalysisPhasesBuilder> entry : this.languages.entrySet()) {
			result.put(entry.getKey(), entry.getValue().toPhases());
		}
		return result;
	}

	/**
	 * Builds the analyzer schema, validating it.
	 *
	 * @param name the name of the analyzer
	 * @return the analyzer schema
	 */
	@Nonnull
	FulltextAnalyzerSchema toSchema(@Nonnull String name) {
		return FulltextAnalyzerSchema._internalBuild(name, this.basedOn, getAllLanguages(), getLanguages());
	}

}
