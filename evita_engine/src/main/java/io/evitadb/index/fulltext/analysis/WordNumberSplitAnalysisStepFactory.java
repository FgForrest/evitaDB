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
import io.evitadb.spi.analysis.FulltextSplittingAnalysisOperation;
import io.evitadb.spi.analysis.FulltextSplittingAnalysisStepFactory;
import org.apache.lucene.analysis.core.FlattenGraphFilter;
import org.apache.lucene.analysis.miscellaneous.WordDelimiterGraphFilter;
import org.apache.lucene.analysis.miscellaneous.WordDelimiterIterator;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The `word-number-split` step: splits each word of the tokenizer also where a letter meets a digit and keeps the
 * original word, so `iPhone15` yields `iPhone15`, `iPhone` and `15` and the query `iphone 15` meets the value. It is
 * Lucene's `WordDelimiterGraphFilter` with word and number parts, the split on numerics and the original preserved -
 * no split on a case change (`iPhone` is one word) and no catenation. Each part's offsets point at the part itself,
 * and the graph is flattened right away, because a term carries a position increment only and no position length.
 * The configuration and the positions it produces are pinned by `WordNumberSplitAnalysisTest`.
 */
public final class WordNumberSplitAnalysisStepFactory implements FulltextSplittingAnalysisStepFactory {
	/**
	 * Where the filter splits and what it emits.
	 */
	private static final int SPLIT_FLAGS = WordDelimiterGraphFilter.GENERATE_WORD_PARTS
		| WordDelimiterGraphFilter.GENERATE_NUMBER_PARTS
		| WordDelimiterGraphFilter.SPLIT_ON_NUMERICS
		| WordDelimiterGraphFilter.PRESERVE_ORIGINAL;
	/**
	 * The operation, stateless and shared: every call creates new filters.
	 */
	private static final FulltextSplittingAnalysisOperation OPERATION = words -> new FlattenGraphFilter(
		new WordDelimiterGraphFilter(words, true, WordDelimiterIterator.DEFAULT_WORD_DELIM_TABLE, SPLIT_FLAGS, null)
	);

	/**
	 * Creates a stateless provider for JPMS and classpath ServiceLoader discovery.
	 */
	public WordNumberSplitAnalysisStepFactory() {
	}

	@Nonnull
	@Override
	public String getName() {
		return AnalysisSteps.WORD_NUMBER_SPLIT;
	}

	@Nonnull
	@Override
	public List<AnalysisStepParameterDescriptor> getParameters() {
		return List.of();
	}

	@Override
	public void validate(@Nonnull Map<String, Serializable> parameters) {
		if (!parameters.isEmpty()) {
			throw new EvitaInvalidUsageException("Step `word-number-split` accepts no parameters.");
		}
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
	public FulltextSplittingAnalysisOperation create(
		@Nonnull Map<String, Serializable> parameters,
		@Nonnull FulltextAnalysisContext context
	) {
		validate(parameters);
		return OPERATION;
	}
}
