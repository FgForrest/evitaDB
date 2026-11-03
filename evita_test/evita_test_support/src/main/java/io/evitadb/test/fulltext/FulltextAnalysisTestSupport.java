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

package io.evitadb.test.fulltext;

import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.mutation.catalog.MutationEntitySchemaAccessor;
import io.evitadb.index.analysis.AnalysisStepRegistry;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.FulltextChainCompiler;
import io.evitadb.index.fulltext.analysis.ResolvedFulltextAnalyzer;
import io.evitadb.spi.analysis.FulltextAnalysisSide;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NamingConvention;

import javax.annotation.Nonnull;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Compiled built-in analysis for fixtures with no catalog, retained for the test JVM's lifetime. */
public final class FulltextAnalysisTestSupport {
	/** Empty real catalog whose reserved built-ins resolve exactly as in production. */
	public static final CatalogSchemaContract SCHEMA = CatalogSchema._internalBuild(
		"test", NamingConvention.generate("test"), null, EnumSet.allOf(CatalogEvolutionMode.class),
		MutationEntitySchemaAccessor.INSTANCE
	);
	/** Full query streams used by term/offset oracles and the dictionary fixtures. */
	private static final Map<Locale, FulltextAnalyzer> QUERY_CHAINS = CollectionUtils.createConcurrentHashMap(8);

	/** Prevents construction. */
	private FulltextAnalysisTestSupport() { }

	/**
	 * Returns a compiled built-in full query chain, including language splitting and query variants.
	 *
	 * @param locale the language of the query
	 * @return a shared test-scope chain; the fixture JVM owns its lifetime
	 */
	@Nonnull
	public static FulltextAnalyzer queryAnalyzer(@Nonnull Locale locale) {
		return QUERY_CHAINS.computeIfAbsent(locale, language -> new FulltextAnalyzer(
			"text", new FulltextChainCompiler(new AnalysisStepRegistry()).compile(
				ResolvedFulltextAnalyzer.resolve(SCHEMA, "text", language), FulltextAnalysisSide.QUERY)
		));
	}
	/**
	 * Collects the production streaming API into records for assertions and dictionary oracles.
	 *
	 * @param analyzer the compiled chain to drive
	 * @param text the input text, normalized by the production boundary
	 * @return terms with original surface forms, corrected offsets and position increments in stream order
	 */
	@Nonnull
	public static List<AnalyzedTerm> collectTerms(@Nonnull FulltextAnalyzer analyzer, @Nonnull String text) {
		final List<AnalyzedTerm> terms = new ArrayList<>(Math.max(1, text.length() / 6));
		analyzer.analyze(text, (term, surface, start, end, increment) ->
			terms.add(new AnalyzedTerm(term, surface.get(), start, end, increment)));
		return terms;
	}

}
