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
import io.evitadb.spi.analysis.FulltextWordsAnalysisOperation;
import io.evitadb.spi.analysis.FulltextWordsAnalysisStepFactory;
import io.evitadb.spi.analysis.NormalizingAnalysisStepFactory;
import io.evitadb.spi.analysis.StringNormalizationStep;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;

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
 * Folds supported Unicode characters through Lucene's ASCII table without creating a token stream.
 * Folding can expand one character into several ASCII characters and does not perform lowercasing.
 * Characters outside the table are preserved, independently of the value tree's locale.
 *
 * In a fulltext analyzer the same table folds every word in place: Lucene's `ASCIIFoldingFilter` calls
 * `ASCIIFoldingFilter.foldToASCII` on the term buffer, without a `String` per word.
 */
public final class AsciiFoldingAnalysisStepFactory
	implements NormalizingAnalysisStepFactory, FulltextWordsAnalysisStepFactory {
	/**
	 * The fulltext operation, stateless and shared: every call wraps the words in a new folding filter.
	 */
	private static final FulltextWordsAnalysisOperation FOLDING = (words, side) -> new ASCIIFoldingFilter(words);

	/**
	 * Creates a stateless provider for JPMS and classpath ServiceLoader discovery.
	 * Per-declaration parameters belong to the operation returned by `create`, so this instance retains no catalog state.
	 */
	public AsciiFoldingAnalysisStepFactory() {
	}

	@Nonnull
	@Override
	public String getName() {
		return AnalysisSteps.ASCII_FOLDING;
	}

	@Nonnull
	@Override
	public List<AnalysisStepParameterDescriptor> getParameters() {
		return List.of();
	}

	@Override
	public void validate(@Nonnull Map<String, Serializable> parameters) {
		if (!parameters.isEmpty()) {
			throw new EvitaInvalidUsageException("Step `ascii-folding` accepts no parameters.");
		}
	}

	@Nonnull
	@Override
	public StringNormalizationStep create(@Nonnull Map<String, Serializable> parameters, @Nullable Locale foldLocale) {
		validate(parameters);
		return text -> {
			final char[] input = text.toCharArray();
			final char[] output = new char[input.length * 4];
			final int length = ASCIIFoldingFilter.foldToASCII(input, 0, output, 0, input.length);
			if (length == input.length && Arrays.equals(input, 0, length, output, 0, length)) {
				return text;
			}
			return new String(output, 0, length);
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
		return Set.of();
	}

	@Override
	public int getBehaviourVersion() {
		return 1;
	}

	@Nonnull
	@Override
	public FulltextWordsAnalysisOperation create(
		@Nonnull Map<String, Serializable> parameters,
		@Nonnull FulltextAnalysisContext context
	) {
		validate(parameters);
		return FOLDING;
	}
}
