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


package io.evitadb.api.requestResponse.schema.dto;

import io.evitadb.api.requestResponse.schema.FulltextAnalyzerSchemaContract;
import io.evitadb.api.requestResponse.schema.FulltextResourceSchemaContract;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps;
import io.evitadb.api.requestResponse.schema.analysis.FulltextAnalysisPhases;
import io.evitadb.api.requestResponse.schema.analysis.FulltextResourceContent.FulltextResourceKind;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Checks the references between the fulltext parts of a catalog schema that need no server: the names an analyzer
 * is based on, the resources its steps read and their kinds, the analyzers the fields use, and the reserved built-in
 * names. What depends on the
 * installed steps - that a step exists, that its parameters are valid, that it sits in its phase - is checked by the
 * server when it adopts the schema.
 */
final class FulltextSchemaValidator {

	/**
	 * Prevents instantiation of this utility class.
	 */
	private FulltextSchemaValidator() {
	}

	/**
	 * Validates the fulltext resources and analyzers of one catalog schema against each other.
	 *
	 * @param resources the resources declared in the catalog, keyed by name
	 * @param analyzers the analyzers declared in the catalog, keyed by name
	 * @return one message per problem found, empty when the parts are consistent
	 */
	@Nonnull
	static Stream<String> validate(
		@Nonnull Map<String, FulltextResourceSchemaContract> resources,
		@Nonnull Map<String, FulltextAnalyzerSchemaContract> analyzers
	) {
		final List<String> errors = new ArrayList<>(4);
		for (final String name : resources.keySet()) {
			if (AnalysisSteps.BUILT_IN_RESOURCES.contains(name)) {
				errors.add("Fulltext resource `" + name + "` uses the name of a built-in resource.");
			}
		}
		for (final FulltextAnalyzerSchemaContract analyzer : analyzers.values()) {
			final String name = analyzer.getName();
			if (FulltextAnalyzerSchemaContract.BUILT_IN_ANALYZERS.contains(name)) {
				errors.add("Fulltext analyzer `" + name + "` uses the name of a built-in analyzer.");
			}
			analyzer.getBasedOn()
				.filter(basedOn -> !FulltextAnalyzerSchemaContract.BUILT_IN_ANALYZERS.contains(basedOn))
				.filter(basedOn -> !analyzers.containsKey(basedOn))
				.ifPresent(basedOn -> errors.add(
					"Fulltext analyzer `" + name + "` is based on analyzer `" + basedOn + "`, which does not exist."
				));
			if (isInBasedOnCycle(analyzer, analyzers)) {
				errors.add("Fulltext analyzer `" + name + "` is based on itself through a cycle of `basedOn`.");
			}
			validatePhases(name, "all languages", analyzer.getAllLanguages(), resources, errors);
			for (final Entry<String, FulltextAnalysisPhases> entry : analyzer.getLanguages().entrySet()) {
				validatePhases(name, "language `" + entry.getKey() + "`", entry.getValue(), resources, errors);
			}
		}
		return errors.stream();
	}

	/**
	 * Checks that the analyzer a field names is a built-in one or one declared in the catalog. A blank name is left to
	 * the field's own validation, which reports it.
	 *
	 * @param field     how the field is named in the message, e.g. "Attribute `title`"
	 * @param analyzer  the name of the analyzer the field uses
	 * @param analyzers the analyzers declared in the catalog, keyed by name
	 * @return the message when no such analyzer exists, empty otherwise
	 */
	@Nonnull
	static Stream<String> validateAnalyzerReference(
		@Nonnull String field,
		@Nonnull String analyzer,
		@Nonnull Map<String, FulltextAnalyzerSchemaContract> analyzers
	) {
		if (analyzer.isBlank() || FulltextAnalyzerSchemaContract.BUILT_IN_ANALYZERS.contains(analyzer) ||
			analyzers.containsKey(analyzer)) {
			return Stream.empty();
		}
		return Stream.of(
			field + " uses the fulltext analyzer `" + analyzer + "`, which is neither built in nor declared in the " +
				"catalog schema."
		);
	}

	/**
	 * Returns true when following `basedOn` from the analyzer through the declared analyzers comes back to it.
	 *
	 * @param analyzer  the analyzer to start from
	 * @param analyzers the declared analyzers, keyed by name
	 * @return true when the analyzer is part of a `basedOn` cycle
	 */
	private static boolean isInBasedOnCycle(
		@Nonnull FulltextAnalyzerSchemaContract analyzer,
		@Nonnull Map<String, FulltextAnalyzerSchemaContract> analyzers
	) {
		final Set<String> visited = CollectionUtils.createHashSet(analyzers.size());
		FulltextAnalyzerSchemaContract current = analyzer;
		while (current != null && visited.add(current.getName())) {
			final String basedOn = current.getBasedOn().orElse(null);
			if (analyzer.getName().equals(basedOn)) {
				return true;
			}
			current = basedOn == null ? null : analyzers.get(basedOn);
		}
		return false;
	}

	/**
	 * Validates the resource parameters of the steps of both phases of one definition.
	 *
	 * @param analyzer  the name of the analyzer, for the messages
	 * @param scope     which definition of the analyzer the phases belong to, for the messages
	 * @param phases    the phases to validate
	 * @param resources the resources declared in the catalog, keyed by name
	 * @param errors    the list the messages are added to
	 */
	private static void validatePhases(
		@Nonnull String analyzer,
		@Nonnull String scope,
		@Nonnull FulltextAnalysisPhases phases,
		@Nonnull Map<String, FulltextResourceSchemaContract> resources,
		@Nonnull List<String> errors
	) {
		validateSteps(analyzer, scope, phases.text(), resources, errors);
		validateSteps(analyzer, scope, phases.words(), resources, errors);
	}

	/**
	 * Validates that every parameter that reads resources names existing resources of the kind it reads.
	 *
	 * @param analyzer  the name of the analyzer, for the messages
	 * @param scope     which definition of the analyzer the steps belong to, for the messages
	 * @param steps     the steps of one phase, or `null` when the phase is undefined
	 * @param resources the resources declared in the catalog, keyed by name
	 * @param errors    the list the messages are added to
	 */
	private static void validateSteps(
		@Nonnull String analyzer,
		@Nonnull String scope,
		@Nullable List<AnalysisStep> steps,
		@Nonnull Map<String, FulltextResourceSchemaContract> resources,
		@Nonnull List<String> errors
	) {
		if (steps == null) {
			return;
		}
		for (final AnalysisStep step : steps) {
			for (final Entry<String, Serializable> parameter : step.parameters().entrySet()) {
				final FulltextResourceKind expectedKind = AnalysisSteps.RESOURCE_PARAMETERS.get(parameter.getKey());
				if (expectedKind == null) {
					continue;
				}
				final String where =
					"Step `" + step.name() + "` of fulltext analyzer `" + analyzer + "` (" + scope + ")";
				if (!(parameter.getValue() instanceof String[] names)) {
					errors.add(where + ": parameter `" + parameter.getKey() + "` must list resource names.");
					continue;
				}
				for (final String resourceName : names) {
					final FulltextResourceKind kind = resolveKind(resourceName, resources);
					if (kind == null) {
						errors.add(where + " reads resource `" + resourceName + "`, which does not exist.");
					} else if (kind != expectedKind) {
						errors.add(
							where + ": parameter `" + parameter.getKey() + "` reads a " + expectedKind +
								" resource, but `" + resourceName + "` is a " + kind + "."
						);
					}
				}
			}
		}
	}

	/**
	 * Returns the kind of a declared or built-in resource.
	 *
	 * @param name      the name of the resource
	 * @param resources the resources declared in the catalog, keyed by name
	 * @return the kind of the resource, or `null` when no resource of the name exists
	 */
	@Nullable
	private static FulltextResourceKind resolveKind(
		@Nonnull String name,
		@Nonnull Map<String, FulltextResourceSchemaContract> resources
	) {
		if (AnalysisSteps.BUILT_IN_RESOURCES.contains(name)) {
			// every built-in resource is a stop list
			return FulltextResourceKind.WORD_LIST;
		}
		final FulltextResourceSchemaContract resource = resources.get(name);
		return resource == null ? null : resource.getContent().kind();
	}

}
