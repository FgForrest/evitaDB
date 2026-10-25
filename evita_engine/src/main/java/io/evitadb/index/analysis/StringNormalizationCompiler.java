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


package io.evitadb.index.analysis;

import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.spi.analysis.StringNormalizationStep;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Compiles ordered descriptions into immutable, thread-safe String key functions.
 * Cache entries contain no schema policy; the catalog-owned registry bounds their lifetime.
 * Nonempty chains enter in NFC, apply every step once in declared order and leave in NFD.
 * A compiled function accepts raw input; applying it again to a stored key may change that key.
 */
public final class StringNormalizationCompiler {
	/** Registry used only during compilation, never per value. */
	@Nonnull private final AnalysisStepRegistry registry;
	/** Concurrent publication of immutable functions keyed by defensively copied descriptions and explicit locale. */
	@Nonnull private final Map<CompilationKey, UnaryOperator<String>> functions =
		CollectionUtils.createConcurrentHashMap(16);

	/** Creates a compiler bound to one immutable provider registry. */
	public StringNormalizationCompiler(@Nonnull AnalysisStepRegistry registry) {
		this.registry = registry;
	}

	/**
	 * Resolves a shared function, treating null fold locale as root without consulting the JVM default.
	 *
	 * @param steps ordered descriptions, defensively copied into the cache key
	 * @param foldLocale locale of the key's value tree
	 * @return immutable function accepting null values
	 */
	@Nonnull
	public UnaryOperator<String> compile(@Nonnull List<AnalysisStep> steps, @Nullable Locale foldLocale) {
		return this.functions.computeIfAbsent(
			new CompilationKey(List.copyOf(steps), foldLocale == null ? Locale.ROOT : foldLocale), this::compileKey
		);
	}

	/** Resolves providers once and captures an immutable operation array in the compiled function. */
	@Nonnull
	private UnaryOperator<String> compileKey(@Nonnull CompilationKey key) {
		if (key.steps().isEmpty()) {
			return text -> text == null ? null : Normalizer.normalize(text, Normalizer.Form.NFD);
		}
		final StringNormalizationStep[] operations = new StringNormalizationStep[key.steps().size()];
		for (int index = 0; index < operations.length; index++) {
			final AnalysisStep description = key.steps().get(index);
			operations[index] = this.registry.resolve(description).create(description.parameters(), key.locale());
		}
		return text -> {
			if (text == null) {
				return null;
			}
			String result = Normalizer.normalize(text, Normalizer.Form.NFC);
			for (final StringNormalizationStep operation : operations) {
				result = operation.apply(result);
			}
			final String decomposed = Normalizer.normalize(result, Normalizer.Form.NFD);
			return text.equals(decomposed) ? text : decomposed;
		};
	}

	/** Immutable cache identity; null locales have already been replaced with root. */
	private record CompilationKey(@Nonnull List<AnalysisStep> steps, @Nonnull Locale locale) {
	}
}
