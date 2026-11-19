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
import io.evitadb.spi.analysis.FulltextTokenizerAnalysisOperation;
import io.evitadb.spi.analysis.FulltextTokenizerAnalysisStepFactory;
import org.apache.lucene.analysis.standard.StandardTokenizer;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The `standard` tokenizer: splits text into words at Unicode word breaks (UAX #29) with Lucene's
 * `StandardTokenizer`, on both sides. It is the splitting of every language the catalog does not declare.
 */
public final class StandardTokenizerAnalysisStepFactory implements FulltextTokenizerAnalysisStepFactory {
	/**
	 * The operation, stateless and shared: every call creates a new tokenizer.
	 */
	private static final FulltextTokenizerAnalysisOperation OPERATION = StandardTokenizer::new;

	/**
	 * Creates a stateless provider for JPMS and classpath ServiceLoader discovery.
	 */
	public StandardTokenizerAnalysisStepFactory() {
	}

	@Nonnull
	@Override
	public String getName() {
		return AnalysisSteps.STANDARD_TOKENIZER;
	}

	@Nonnull
	@Override
	public List<AnalysisStepParameterDescriptor> getParameters() {
		return List.of();
	}

	@Override
	public void validate(@Nonnull Map<String, Serializable> parameters) {
		if (!parameters.isEmpty()) {
			throw new EvitaInvalidUsageException("Step `standard` accepts no parameters.");
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
	public FulltextTokenizerAnalysisOperation create(
		@Nonnull Map<String, Serializable> parameters,
		@Nonnull FulltextAnalysisContext context
	) {
		validate(parameters);
		return OPERATION;
	}
}
