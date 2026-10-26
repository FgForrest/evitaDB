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

import io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.spi.analysis.AnalysisStepParameterDescriptor;
import io.evitadb.spi.analysis.NormalizingAnalysisStepFactory;
import io.evitadb.spi.analysis.StringNormalizationStep;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Removes leading and trailing Unicode whitespace while preserving all inner content.
 * Both JDK whitespace and space-character predicates are accepted, including non-breaking spaces commonly pasted
 * into attribute values. An all-whitespace input becomes an empty String, which remains a present value.
 */
public final class TrimAnalysisStepFactory implements NormalizingAnalysisStepFactory {
	/**
	 * Creates a stateless provider for JPMS and classpath ServiceLoader discovery.
	 * Per-declaration parameters belong to the operation returned by `create`, so this instance retains no catalog state.
	 */
	public TrimAnalysisStepFactory() {
	}

	@Nonnull
	@Override
	public String getName() {
		return AnalysisSteps.TRIM;
	}

	@Nonnull
	@Override
	public List<AnalysisStepParameterDescriptor> getParameters() {
		return List.of();
	}

	@Override
	public void validate(@Nonnull Map<String, Serializable> parameters) {
		if (!parameters.isEmpty()) {
			throw new EvitaInvalidUsageException("Step `trim` accepts no parameters.");
		}
	}

	@Nonnull
	@Override
	public StringNormalizationStep create(@Nonnull Map<String, Serializable> parameters, @Nullable Locale foldLocale) {
		validate(parameters);
		return text -> {
			int start = 0;
			int end = text.length();
			while (start < end && isSpace(text.codePointAt(start))) {
				start += Character.charCount(text.codePointAt(start));
			}
			while (end > start && isSpace(text.codePointBefore(end))) {
				end -= Character.charCount(text.codePointBefore(end));
			}
			return start == 0 && end == text.length() ? text : text.substring(start, end);
		};
	}

	/** Recognizes both Java whitespace and NBSP-class Unicode spaces. */
	private static boolean isSpace(int codePoint) {
		return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
	}
}
