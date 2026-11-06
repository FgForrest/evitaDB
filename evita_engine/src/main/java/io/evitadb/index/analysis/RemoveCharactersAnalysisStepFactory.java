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
import io.evitadb.spi.analysis.FulltextAnalysisContext;
import io.evitadb.spi.analysis.FulltextAnalysisSide;
import io.evitadb.spi.analysis.FulltextTextAnalysisOperation;
import io.evitadb.spi.analysis.FulltextTextAnalysisStepFactory;
import io.evitadb.spi.analysis.NormalizingAnalysisStepFactory;
import io.evitadb.spi.analysis.StringNormalizationStep;
import org.apache.lucene.analysis.charfilter.MappingCharFilter;
import org.apache.lucene.analysis.charfilter.NormalizeCharMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Removes every occurrence of a declared nonempty set of literal Unicode code points.
 * The compiled operation captures a sorted, distinct character set; supplementary characters are removed as whole
 * code points. The parameter is neither a regular expression nor a substring pattern.
 *
 * In a fulltext analyzer it is a step of the text phase and removes the same code points from the whole text before
 * the text is split into words, on both sides: Lucene's `MappingCharFilter` maps each of them to nothing and corrects
 * the offsets back through the removed characters, so they still address the original text.
 */
public final class RemoveCharactersAnalysisStepFactory
	implements NormalizingAnalysisStepFactory, FulltextTextAnalysisStepFactory {
	/**
	 * Creates a stateless provider for JPMS and classpath ServiceLoader discovery.
	 * Per-declaration parameters belong to the operation returned by `create`, so this instance retains no catalog state.
	 */
	public RemoveCharactersAnalysisStepFactory() {
	}

	@Nonnull
	@Override
	public String getName() {
		return AnalysisSteps.REMOVE_CHARACTERS;
	}

	@Nonnull
	@Override
	public List<AnalysisStepParameterDescriptor> getParameters() {
		return List.of(new AnalysisStepParameterDescriptor(
			AnalysisSteps.CHARACTERS, String.class, true, "Non-empty set of Unicode code points to remove."
		));
	}

	@Override
	public void validate(@Nonnull Map<String, Serializable> parameters) {
		if (parameters.size() != 1 || !(parameters.get(AnalysisSteps.CHARACTERS) instanceof String characters) ||
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
		final int[] characters = ((String) parameters.get(AnalysisSteps.CHARACTERS)).codePoints().sorted().distinct().toArray();
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

	@Nonnull
	@Override
	public Set<FulltextAnalysisSide> getSides() {
		return EnumSet.allOf(FulltextAnalysisSide.class);
	}

	@Nonnull
	@Override
	public Set<String> getIndexSideParameters() {
		return Set.of(AnalysisSteps.CHARACTERS);
	}

	@Override
	public int getBehaviourVersion() {
		return 1;
	}

	@Nonnull
	@Override
	public FulltextTextAnalysisOperation create(
		@Nonnull Map<String, Serializable> parameters,
		@Nonnull FulltextAnalysisContext context
	) {
		validate(parameters);
		final NormalizeCharMap.Builder removals = new NormalizeCharMap.Builder();
		// the filter matches UTF-16 chars, so a listed surrogate would remove half of every supplementary character
		// sharing it - a surrogate is no code point of a well-formed text, and one left unpaired in a malformed text
		// stays here, while the normalization above removes it
		((String) parameters.get(AnalysisSteps.CHARACTERS)).codePoints()
			.filter(codePoint -> codePoint < Character.MIN_SURROGATE || codePoint > Character.MAX_SURROGATE)
			.distinct()
			.forEach(codePoint -> removals.add(Character.toString(codePoint), ""));
		final NormalizeCharMap removalMap = removals.build();
		return (text, side) -> new MappingCharFilter(removalMap, text);
	}
}
