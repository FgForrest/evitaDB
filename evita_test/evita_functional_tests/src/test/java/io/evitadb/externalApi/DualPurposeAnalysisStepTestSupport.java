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


package io.evitadb.externalApi;

import io.evitadb.index.analysis.AnalysisStepRegistry;
import io.evitadb.spi.analysis.*;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Real extension provider admitted in normalization and the fulltext words phase. */
public final class DualPurposeAnalysisStepTestSupport {
	/** Prevents construction of the fixture holder. */
	private DualPurposeAnalysisStepTestSupport() {
	}

	/**
	 * Creates a validated registry whose `custom` provider declares the given parameter spellings, each distinct from
	 * its fulltext input name, so a test can check that the original spelling survives conversion.
	 */
	@Nonnull
	public static AnalysisStepRegistry registry(@Nonnull String... parameters) {
		final AnalysisStepRegistry installed = new AnalysisStepRegistry();
		final Map<String, AnalysisStepFactory> factories = new LinkedHashMap<>();
		installed.getNormalizingFactories().forEach(factory -> factories.put(factory.getName(), factory));
		installed.getFulltextFactories().forEach(factory -> factories.put(factory.getName(), factory));
		factories.put("custom", new DualPurposeProvider(List.of(parameters)));
		return new AnalysisStepRegistry(factories.values());
	}

	/** Identity operations keep the fixture focused on the provider's declared parameter contract. */
	private record DualPurposeProvider(@Nonnull List<String> parameters)
		implements NormalizingAnalysisStepFactory, FulltextWordsAnalysisStepFactory {
		/** Supplies the stable provider name. */
		@Nonnull
		@Override
		public String getName() {
			return "custom";
		}

		/** Supplies one required string parameter per declared spelling, keeping the original provider spelling. */
		@Nonnull
		@Override
		public List<AnalysisStepParameterDescriptor> getParameters() {
			return this.parameters.stream()
				.map(name -> new AnalysisStepParameterDescriptor(name, String.class, true, "Required value."))
				.toList();
		}

		/** Checks that the original parameter spellings survive input conversion. */
		@Override
		public void validate(@Nonnull Map<String, Serializable> parameters) {
			if (!parameters.keySet().equals(Set.copyOf(this.parameters))) {
				throw new IllegalArgumentException("Unexpected provider parameters.");
			}
		}

		/** Declares the parameters as part of the index contract. */
		@Nonnull
		@Override
		public Set<String> getIndexSideParameters() {
			return Set.copyOf(this.parameters);
		}

		/** Identifies the stable identity behavior. */
		@Override
		public int getBehaviourVersion() {
			return 1;
		}

		/** Admits the words step on both sides. */
		@Nonnull
		@Override
		public Set<FulltextAnalysisSide> getSides() {
			return Set.of(FulltextAnalysisSide.INDEX, FulltextAnalysisSide.QUERY);
		}

		/** Creates an identity normalizer. */
		@Nonnull
		@Override
		public StringNormalizationStep create(
			@Nonnull Map<String, Serializable> parameters, @Nullable Locale locale
		) {
			return text -> text;
		}

		/** Creates an identity words operation. */
		@Nonnull
		@Override
		public FulltextWordsAnalysisOperation create(
			@Nonnull Map<String, Serializable> parameters, @Nonnull FulltextAnalysisContext context
		) {
			return (words, side) -> words;
		}
	}
}
