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
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Applies code-point lowercasing using the value tree's fold locale.
 * Turkish and Azeri honor the contextual dot handling used by Lucene; other locales use the JDK code-point mapping.
 * This operation preserves the original String instance when no code point changes.
 */
public final class LowercaseAnalysisStepFactory implements NormalizingAnalysisStepFactory {
	/**
	 * Creates a stateless provider for JPMS and classpath ServiceLoader discovery.
	 * Per-declaration parameters belong to the operation returned by `create`, so this instance retains no catalog state.
	 */
	public LowercaseAnalysisStepFactory() {
	}

	@Nonnull
	@Override
	public String getName() {
		return "lowercase";
	}

	@Nonnull
	@Override
	public List<AnalysisStepParameterDescriptor> getParameters() {
		return List.of();
	}

	@Override
	public void validate(@Nonnull Map<String, Serializable> parameters) {
		if (!parameters.isEmpty()) {
			throw new EvitaInvalidUsageException("Step `lowercase` accepts no parameters.");
		}
	}

	@Nonnull
	@Override
	public StringNormalizationStep create(@Nonnull Map<String, Serializable> parameters, @Nullable Locale foldLocale) {
		validate(parameters);
		final String language = foldLocale == null ? "" : foldLocale.getLanguage();
		final boolean turkish = "tr".equals(language) || "az".equals(language);
		return text -> lowercase(text, turkish);
	}

	/** Applies code-point casing and the Turkish combining-dot context without tokenization. */
	@Nonnull
	private static String lowercase(@Nonnull String text, boolean turkish) {
		StringBuilder result = null;
		boolean afterDottedI = false;
		for (int offset = 0; offset < text.length();) {
			final int codePoint = text.codePointAt(offset);
			final int width = Character.charCount(codePoint);
			int mapped = Character.toLowerCase(codePoint);
			if (turkish) {
				if (codePoint == 'I') {
					afterDottedI = isBeforeDot(text, offset + width);
					mapped = afterDottedI ? 'i' : 0x0131;
				} else {
					afterDottedI = afterDottedI && Character.getType(codePoint) == Character.NON_SPACING_MARK;
					if (afterDottedI && codePoint == 0x0307) {
						mapped = -1;
					}
				}
			}
			if (mapped != codePoint && result == null) {
				result = new StringBuilder(text.length());
				result.append(text, 0, offset);
			}
			if (result != null && mapped >= 0) {
				result.appendCodePoint(mapped);
			}
			offset += width;
		}
		return result == null ? text : result.toString();
	}

	/** Looks for a combining dot after I, allowing intervening nonspacing marks as Lucene does. */
	private static boolean isBeforeDot(@Nonnull String text, int offset) {
		for (int index = offset; index < text.length();) {
			final int codePoint = text.codePointAt(index);
			if (Character.getType(codePoint) != Character.NON_SPACING_MARK) {
				return false;
			}
			if (codePoint == 0x0307) {
				return true;
			}
			index += Character.charCount(codePoint);
		}
		return false;
	}
}
