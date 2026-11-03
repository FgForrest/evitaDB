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
import io.evitadb.spi.analysis.FulltextTextAnalysisOperation;
import io.evitadb.spi.analysis.FulltextTextAnalysisStepFactory;
import org.apache.lucene.analysis.charfilter.HTMLStripCharFilter;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The `html-strip` step: Lucene's `HTMLStripCharFilter` in front of the tokenizer, so that tag names, attribute names
 * and values, comments, `script` and `style` bodies never become words and character entities are decoded. It acts on
 * the index side only - a stored value may be HTML, a query is what a user typed. Offsets are corrected back through
 * the removed runs, so they address the stored, markup-bearing value. What the filter changes in prose that is not
 * markup is described at {@link HtmlStrippingAnalyzerWrapper}, the wrapper this step replaces.
 */
public final class HtmlStripAnalysisStepFactory implements FulltextTextAnalysisStepFactory {
	/**
	 * The operation, stateless and shared: every call creates a new character filter.
	 */
	private static final FulltextTextAnalysisOperation OPERATION = (text, side) -> new HTMLStripCharFilter(text);

	/**
	 * Creates a stateless provider for JPMS and classpath ServiceLoader discovery.
	 */
	public HtmlStripAnalysisStepFactory() {
	}

	@Nonnull
	@Override
	public String getName() {
		return AnalysisSteps.HTML_STRIP;
	}

	@Nonnull
	@Override
	public List<AnalysisStepParameterDescriptor> getParameters() {
		return List.of();
	}

	@Override
	public void validate(@Nonnull Map<String, Serializable> parameters) {
		if (!parameters.isEmpty()) {
			throw new EvitaInvalidUsageException("Step `html-strip` accepts no parameters.");
		}
	}

	@Nonnull
	@Override
	public Set<FulltextAnalysisSide> getSides() {
		return EnumSet.of(FulltextAnalysisSide.INDEX);
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
	public FulltextTextAnalysisOperation create(
		@Nonnull Map<String, Serializable> parameters,
		@Nonnull FulltextAnalysisContext context
	) {
		validate(parameters);
		return OPERATION;
	}
}
