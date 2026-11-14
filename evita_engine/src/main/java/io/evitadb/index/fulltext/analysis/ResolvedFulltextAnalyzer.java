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

import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.FulltextAnalyzerSchemaContract;
import io.evitadb.api.requestResponse.schema.FulltextLanguageSchemaContract;
import io.evitadb.api.requestResponse.schema.FulltextResourceSchemaContract;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps;
import io.evitadb.api.requestResponse.schema.analysis.FulltextAnalysisPhases;
import io.evitadb.api.requestResponse.schema.analysis.FulltextResourceContent;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.spi.analysis.FulltextAnalysisContext;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The steps one analyzer of a catalog applies in one locale, with `basedOn` and the per-language overrides resolved:
 * what {@link FulltextChainCompiler} compiles into the chain of either side.
 *
 * Each phase is resolved on its own, by the order {@link FulltextAnalyzerSchemaContract} documents: the analyzer's
 * override of the phase for the language, its all-languages phase, the analyzer it is based on resolved the same way,
 * and no steps when none of them defines the phase. An override that defines only one phase therefore still inherits
 * the other, and a phase defined as an empty list stops the search with no steps.
 *
 * @param analyzer  the name of the analyzer
 * @param text      the steps of the text phase in execution order, possibly empty
 * @param splitting the word splitting of the locale's language: the tokenizer, then any further splitting steps
 * @param words     the steps of the words phase in execution order, possibly empty
 * @param context   the locale and every resource a step may name - the built-in ones and those of the catalog
 */
public record ResolvedFulltextAnalyzer(
	@Nonnull String analyzer,
	@Nonnull List<AnalysisStep> text,
	@Nonnull List<AnalysisStep> splitting,
	@Nonnull List<AnalysisStep> words,
	@Nonnull FulltextAnalysisContext context
) {
	/**
	 * The word splitting of a language the catalog does not declare.
	 */
	private static final List<AnalysisStep> DEFAULT_SPLITTING = List.of(AnalysisSteps.standardTokenizer());

	/**
	 * Keeps immutable copies of the step lists.
	 *
	 * @param analyzer  the name of the analyzer
	 * @param text      the steps of the text phase in execution order
	 * @param splitting the word splitting of the locale's language
	 * @param words     the steps of the words phase in execution order
	 * @param context   the locale and every resource a step may name
	 */
	public ResolvedFulltextAnalyzer {
		text = List.copyOf(text);
		splitting = List.copyOf(splitting);
		words = List.copyOf(words);
	}

	/**
	 * Resolves an analyzer of a catalog for a locale.
	 *
	 * @param catalogSchema the catalog schema declaring the analyzers, the languages and the resources
	 * @param analyzer      the name of a built-in analyzer or one the catalog declares
	 * @param locale        the locale of the field; its language picks the overrides and the word splitting, and
	 *                      {@link Locale#ROOT} picks no override, which resolves the all-languages definition
	 * @return the resolved steps and the context to compile them with
	 * @throws EvitaInvalidUsageException when the analyzer, or one it is based on, is neither built in nor declared,
	 *                                    or when `basedOn` leads back to an analyzer already on the way
	 */
	@Nonnull
	public static ResolvedFulltextAnalyzer resolve(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull String analyzer,
		@Nonnull Locale locale
	) {
		final String language = locale.getLanguage();
		return new ResolvedFulltextAnalyzer(
			analyzer,
			resolvePhase(catalogSchema, analyzer, language, FulltextAnalysisPhases::text),
			catalogSchema.getFulltextLanguage(language)
				.map(FulltextLanguageSchemaContract::getWordSplitting)
				.orElse(DEFAULT_SPLITTING),
			resolvePhase(catalogSchema, analyzer, language, FulltextAnalysisPhases::words),
			new FulltextAnalysisContext(locale, collectResources(catalogSchema))
		);
	}

	/**
	 * Finds the steps of one phase: walks from the analyzer along `basedOn` and takes the first definition of the
	 * phase, the language's override before the all-languages phase of each analyzer.
	 *
	 * @param catalogSchema the catalog schema declaring the analyzers
	 * @param analyzer      the name of the analyzer being resolved
	 * @param language      the ISO 639 code of the language, empty for the all-languages definition
	 * @param phase         reads the phase being resolved from a definition, `null` when it leaves it undefined
	 * @return the steps of the phase, empty when no analyzer on the way defines it
	 * @throws EvitaInvalidUsageException when an analyzer on the way does not exist or `basedOn` forms a cycle
	 */
	@Nonnull
	private static List<AnalysisStep> resolvePhase(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull String analyzer,
		@Nonnull String language,
		@Nonnull Function<FulltextAnalysisPhases, List<AnalysisStep>> phase
	) {
		final Set<String> visited = CollectionUtils.createHashSet(4);
		String current = analyzer;
		String basedOnBy = null;
		while (current != null) {
			if (!visited.add(current)) {
				throw new EvitaInvalidUsageException(
					"Fulltext analyzer `" + analyzer + "` is based on itself through a cycle of `basedOn`."
				);
			}
			final FulltextAnalyzerSchemaContract definition = find(catalogSchema, current, basedOnBy);
			final FulltextAnalysisPhases override = definition.getLanguages().get(language);
			final List<AnalysisStep> overridden = override == null ? null : phase.apply(override);
			if (overridden != null) {
				return overridden;
			}
			final List<AnalysisStep> allLanguages = phase.apply(definition.getAllLanguages());
			if (allLanguages != null) {
				return allLanguages;
			}
			basedOnBy = current;
			current = definition.getBasedOn().orElse(null);
		}
		return List.of();
	}

	/**
	 * Finds an analyzer by name: a built-in one, or one the catalog declares.
	 *
	 * @param catalogSchema the catalog schema declaring the analyzers
	 * @param name          the name of the analyzer
	 * @param basedOnBy     the name of the analyzer based on this one, or `null` when a field names this one
	 * @return the analyzer
	 * @throws EvitaInvalidUsageException when no analyzer of the name exists
	 */
	@Nonnull
	static FulltextAnalyzerSchemaContract find(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull String name,
		@Nullable String basedOnBy
	) {
		final FulltextAnalyzerSchemaContract builtIn = BuiltInFulltextAnalyzers.getAnalyzers().get(name);
		if (builtIn != null) {
			return builtIn;
		}
		return catalogSchema.getFulltextAnalyzer(name)
			.orElseThrow(
				() -> new EvitaInvalidUsageException(
					basedOnBy == null ?
						"Fulltext analyzer `" + name + "` is neither built in nor declared in the catalog schema." :
						"Fulltext analyzer `" + basedOnBy + "` is based on analyzer `" + name +
							"`, which does not exist."
				)
			);
	}

	/**
	 * Collects every resource a step may name: those the catalog declares, then the built-in ones, so that a reserved
	 * name means the built-in list even in a catalog schema not validated yet.
	 *
	 * @param catalogSchema the catalog schema declaring the resources
	 * @return the contents of the resources keyed by name
	 */
	@Nonnull
	private static Map<String, FulltextResourceContent> collectResources(@Nonnull CatalogSchemaContract catalogSchema) {
		final Map<String, FulltextResourceContent> builtIn = BuiltInFulltextResources.getResources();
		final Map<String, FulltextResourceSchemaContract> declared = catalogSchema.getFulltextResources();
		final Map<String, FulltextResourceContent> resources =
			CollectionUtils.createHashMap(declared.size() + builtIn.size());
		for (final FulltextResourceSchemaContract resource : declared.values()) {
			resources.put(resource.getName(), resource.getContent());
		}
		resources.putAll(builtIn);
		return resources;
	}
}
