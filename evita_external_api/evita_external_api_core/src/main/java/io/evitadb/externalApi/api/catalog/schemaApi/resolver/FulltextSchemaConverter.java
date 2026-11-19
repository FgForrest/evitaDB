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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver;

import io.evitadb.api.requestResponse.schema.*;
import io.evitadb.api.requestResponse.schema.analysis.*;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AnalysisStepInputDescriptors.Context;
import io.evitadb.externalApi.api.resolver.mutation.*;
import io.evitadb.utils.CollectionUtils;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Converts declarative fulltext values for schema reads and catalog mutations. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class FulltextSchemaConverter {
	/**
	 * Reads an optional phase, preserving omission separately from an explicit empty list.
	 *
	 * @param input enclosing declaration
	 * @param name phase property
	 * @param context permitted providers for the phase
	 * @param errors API-specific diagnostics
	 * @return ordered steps, or null when undefined
	 */
	@Nullable
	public static AnalysisStep[] readSteps(
		@Nonnull Input input, @Nonnull String name, @Nonnull Context context,
		@Nonnull MutationResolvingExceptionFactory errors
	) {
		return input.getOptionalProperty(name, new PropertyObjectListMapper<>(
			"Fulltext", errors, name, AnalysisStep.class,
			nested -> AnalysisStepConverter.fromInput(nested, errors, context)));
	}

	/**
	 * Reads one analyzer phase pair without resolving inheritance.
	 *
	 * @param input declaration containing optional text and words phases
	 * @param errors API-specific diagnostics
	 * @return phases retaining undefined and empty lists
	 */
	@Nonnull
	public static FulltextAnalysisPhases readPhases(
		@Nonnull Input input, @Nonnull MutationResolvingExceptionFactory errors
	) {
		final AnalysisStep[] text = readSteps(input, "text", Context.TEXT, errors);
		final AnalysisStep[] words = readSteps(input, "words", Context.WORDS, errors);
		return new FulltextAnalysisPhases(
			text == null ? null : Arrays.asList(text), words == null ? null : Arrays.asList(words));
	}

	/**
	 * Reads language overrides from the shared list shape used by GraphQL and REST.
	 *
	 * @param input analyzer mutation
	 * @param errors API-specific diagnostics, including duplicate language entries
	 * @return declared overrides keyed by ISO language code
	 */
	@Nonnull
	public static Map<String, FulltextAnalysisPhases> readLanguages(
		@Nonnull Input input, @Nonnull MutationResolvingExceptionFactory errors
	) {
		final Map<String, FulltextAnalysisPhases> result = CollectionUtils.createLinkedHashMap(8);
		input.getOptionalProperty("languages", new PropertyObjectListMapper<>(
			"Fulltext", errors, "languages", FulltextAnalysisPhases.class, nested -> {
				final String language = nested.getRequiredProperty("language", String.class);
				final FulltextAnalysisPhases phases = nested.getOptionalProperty("phases",
					new PropertyObjectMapper<>("Fulltext", errors, "phases", value -> readPhases(value, errors)),
					FulltextAnalysisPhases.UNDEFINED);
				if (result.putIfAbsent(language, phases) != null) {
					throw errors.createInvalidArgumentException("Duplicate fulltext language: " + language);
				}
				return phases;
			}));
		return result;
	}

	/**
	 * Reads exactly one resource kind. The field of the other kind is refused, and an omitted field of the declared
	 * kind is read as an empty word list or an empty synonym list.
	 *
	 * @param input resource content declaration
	 * @param errors API-specific diagnostics
	 * @return immutable declared word list or synonym list
	 */
	@Nonnull
	public static FulltextResourceContent readContent(
		@Nonnull Input input, @Nonnull MutationResolvingExceptionFactory errors
	) {
		final FulltextResourceContent.FulltextResourceKind kind = input.getRequiredProperty(
			"kind", FulltextResourceContent.FulltextResourceKind.class);
		if (kind == FulltextResourceContent.FulltextResourceKind.WORD_LIST) {
			if (input.getOptionalProperty("groups") != null) {
				throw errors.createInvalidArgumentException("A word list cannot contain synonym groups.");
			}
			return new WordList(Arrays.asList(input.getOptionalProperty("words", String[].class, new String[0])));
		}
		if (input.getOptionalProperty("words") != null) {
			throw errors.createInvalidArgumentException("A synonym list cannot contain a flat word list.");
		}
		final SynonymGroup[] groups = input.getOptionalProperty("groups", new PropertyObjectListMapper<>(
			"Fulltext", errors, "groups", SynonymGroup.class,
			group -> new SynonymGroup(Arrays.asList(group.getRequiredProperty("words", String[].class)))));
		return new SynonymList(groups == null ? List.of() : Arrays.asList(groups));
	}

	/**
	 * Serializes phase declarations before mutation reflection reaches the phase record.
	 *
	 * @param phases immutable declaration, with null meaning inheritance
	 * @return JSON-compatible fields preserving null and empty separately
	 */
	@Nonnull
	public static Map<String, Object> phases(@Nonnull FulltextAnalysisPhases phases) {
		final Map<String, Object> result = CollectionUtils.createLinkedHashMap(2);
		result.put("text", phases.text() == null ? null : AnalysisStepConverter.toOutput(phases.text()));
		result.put("words", phases.words() == null ? null : AnalysisStepConverter.toOutput(phases.words()));
		return result;
	}

	/**
	 * Serializes per-language overrides deterministically for typed output and capture bodies.
	 *
	 * @param languages declared overrides before inheritance
	 * @return entries in language-code order
	 */
	@Nonnull
	public static List<Map<String, Object>> languages(@Nonnull Map<String, FulltextAnalysisPhases> languages) {
		return languages.entrySet().stream().sorted(Map.Entry.comparingByKey())
			.map(entry -> Map.<String, Object>of("language", entry.getKey(), "phases", phases(entry.getValue())))
			.toList();
	}

	/**
	 * Serializes the sealed resource content without relying on reflective record construction.
	 *
	 * @param content declared words or synonym groups
	 * @return kind plus its matching content field
	 */
	@Nonnull
	public static Map<String, Object> content(@Nonnull FulltextResourceContent content) {
		if (content instanceof WordList words) {
			return Map.of("kind", content.kind().name(), "words", words.words());
		} else if (content instanceof SynonymList synonyms) {
			return Map.of("kind", content.kind().name(), "groups", synonyms.groups().stream()
				.map(group -> Map.of("words", group.words())).toList());
		}
		throw new IllegalArgumentException("Unknown fulltext resource content: " + content.getClass());
	}

	/**
	 * Serializes a catalog language declaration using the common step output shape.
	 *
	 * @param schema catalog language declaration
	 * @return ISO language code and ordered word splitting
	 */
	@Nonnull
	public static Map<String, Object> language(@Nonnull FulltextLanguageSchemaContract schema) {
		return Map.of("language", schema.getLanguage(),
			"wordSplitting", AnalysisStepConverter.toOutput(schema.getWordSplitting()));
	}

	/**
	 * Serializes a catalog resource declaration, excluding built-in resources.
	 *
	 * @param schema catalog resource declaration
	 * @return name and declared content
	 */
	@Nonnull
	public static Map<String, Object> resource(@Nonnull FulltextResourceSchemaContract schema) {
		return Map.of("name", schema.getName(), "content", content(schema.getContent()));
	}

	/**
	 * Serializes the analyzer contract before inheritance, preserving an absent base analyzer.
	 *
	 * @param schema catalog analyzer declaration
	 * @return name, base analyzer, declared phase overrides and author samples
	 */
	@Nonnull
	public static Map<String, Object> analyzer(@Nonnull FulltextAnalyzerSchemaContract schema) {
		final Map<String, Object> result = CollectionUtils.createLinkedHashMap(5);
		result.put("name", schema.getName());
		result.put("basedOn", schema.getBasedOn().orElse(null));
		result.put("allLanguages", phases(schema.getAllLanguages()));
		result.put("languages", languages(schema.getLanguages()));
		result.put("samples", schema.getSamples());
		return result;
	}
}
