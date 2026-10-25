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

import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.spi.analysis.AnalysisStepParameterDescriptor;
import io.evitadb.spi.analysis.NormalizingAnalysisStepFactory;
import io.evitadb.spi.analysis.StringNormalizationStep;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Removes every occurrence of a declared nonempty set of literal Unicode code points.
 * The compiled operation captures a sorted, distinct character set; supplementary characters are removed as whole
 * code points. The parameter is neither a regular expression nor a substring pattern.
 */
public final class RemoveCharactersAnalysisStepFactory implements NormalizingAnalysisStepFactory {
	/**
	 * Creates a stateless provider for JPMS and classpath ServiceLoader discovery.
	 * Per-declaration parameters belong to the operation returned by `create`, so this instance retains no catalog state.
	 */
	public RemoveCharactersAnalysisStepFactory() {
	}

	@Nonnull
	@Override
	public String getName() {
		return "remove-characters";
	}

	@Nonnull
	@Override
	public List<AnalysisStepParameterDescriptor> getParameters() {
		return List.of(new AnalysisStepParameterDescriptor(
			"characters", String.class, true, "Non-empty set of Unicode code points to remove."
		));
	}

	@Override
	public void validate(@Nonnull Map<String, Serializable> parameters) {
		if (parameters.size() != 1 || !(parameters.get("characters") instanceof String characters) ||
			characters.isEmpty()) {
			throw new EvitaInvalidUsageException(
				"Step `remove-characters` requires only parameter `characters`, a non-empty String of code points."
			);
		}
	}

	@Nonnull
	@Override
	public StringNormalizationStep create(@Nonnull Map<String, Serializable> parameters, @Nullable Locale foldLocale) {
		validate(parameters);
		final int[] characters = ((String) parameters.get("characters")).codePoints().sorted().distinct().toArray();
		return text -> {
			StringBuilder result = null;
			for (int offset = 0; offset < text.length();) {
				final int codePoint = text.codePointAt(offset);
				final boolean remove = Arrays.binarySearch(characters, codePoint) >= 0;
				if (remove && result == null) {
					result = new StringBuilder(text.length());
					result.append(text, 0, offset);
				}
				if (!remove && result != null) {
					result.appendCodePoint(codePoint);
				}
				offset += Character.charCount(codePoint);
			}
			return result == null ? text : result.toString();
		};
	}
}
