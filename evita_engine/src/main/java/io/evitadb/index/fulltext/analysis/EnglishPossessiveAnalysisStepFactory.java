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

import io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.spi.analysis.AnalysisStepParameterDescriptor;
import io.evitadb.spi.analysis.FulltextAnalysisContext;
import io.evitadb.spi.analysis.FulltextAnalysisSide;
import io.evitadb.spi.analysis.FulltextWordsAnalysisOperation;
import io.evitadb.spi.analysis.FulltextWordsAnalysisStepFactory;
import org.apache.lucene.analysis.en.EnglishPossessiveFilter;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The `english-possessive` step: removes the possessive `'s` from the end of a word with Lucene's
 * `EnglishPossessiveFilter`, on both sides - the first filter of Lucene's `EnglishAnalyzer`, which the built-in English
 * line keeps in front of lowercasing.
 */
public final class EnglishPossessiveAnalysisStepFactory implements FulltextWordsAnalysisStepFactory {
	/**
	 * The operation, stateless and shared: every call creates a new filter.
	 */
	private static final FulltextWordsAnalysisOperation OPERATION =
		(words, side) -> new EnglishPossessiveFilter(words);

	/**
	 * Creates a stateless provider for JPMS and classpath ServiceLoader discovery.
	 */
	public EnglishPossessiveAnalysisStepFactory() {
	}

	@Nonnull
	@Override
	public String getName() {
		return AnalysisSteps.ENGLISH_POSSESSIVE;
	}

	@Nonnull
	@Override
	public List<AnalysisStepParameterDescriptor> getParameters() {
		return List.of();
	}

	@Override
	public void validate(@Nonnull Map<String, Serializable> parameters) {
		if (!parameters.isEmpty()) {
			throw new EvitaInvalidUsageException("Step `english-possessive` accepts no parameters.");
		}
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
		return OPERATION;
	}
}
