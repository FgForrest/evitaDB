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

package io.evitadb.core.query.filter.translator.fulltext;

import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps;
import io.evitadb.index.analysis.AnalysisStepRegistry;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.FulltextChainCompiler;
import io.evitadb.index.fulltext.analysis.ResolvedFulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.StopWordsAnalysisStepFactory;
import io.evitadb.index.fulltext.analysis.SynonymsAnalysisStepFactory;
import io.evitadb.spi.analysis.FulltextAnalysisSide;
import io.evitadb.spi.analysis.FulltextAnalysisTrait;
import io.evitadb.spi.analysis.FulltextWordsAnalysisStepFactory;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Prepares the raw final word in its profile's normalization space without losing it to a stop filter or stemmer. */
final class FulltextQueryFragment {
	/** Prevents construction of this query analysis utility. */
	private FulltextQueryFragment() { }

	/**
	 * Applies query normalization to the shared final split word. Stop filters and synonyms do not participate;
	 * stemming contributes only its declared folding. The ordinary word chain separately produces the variants.
	 * This short-lived chain owns no catalog resources and is closed before any dictionary expansion starts.
	 *
	 * @param registry the catalog's installed step providers
	 * @param schema the catalog schema used to compile the ordinary word chain
	 * @param analyzer the profile's declared analyzer name
	 * @param locale the query's dictionary locale
	 * @param word the raw final split word, before the group's word phase
	 * @param textSplit whether the shared split already applied the profile's text phase
	 * @return distinct normalized fragments; empty when a remaining profile operation drops the word
	 */
	@Nonnull
	static String[] prepare(
		@Nonnull AnalysisStepRegistry registry, @Nonnull CatalogSchemaContract schema, @Nonnull String analyzer,
		@Nonnull Locale locale, @Nonnull TextMatchesTranslator.QueryToken word, boolean textSplit
	) {
		final ResolvedFulltextAnalyzer resolved = ResolvedFulltextAnalyzer.resolve(schema, analyzer, locale);
		final List<AnalysisStep> words = new ArrayList<>(resolved.words().size());
		for (final AnalysisStep step : resolved.words()) {
			final FulltextWordsAnalysisStepFactory factory =
				(FulltextWordsAnalysisStepFactory) registry.resolveFulltext(step);
			if (!factory.getSides().contains(FulltextAnalysisSide.QUERY) ||
				factory instanceof StopWordsAnalysisStepFactory || factory instanceof SynonymsAnalysisStepFactory) {
				continue;
			}
			final Set<FulltextAnalysisTrait> traits = factory.create(step.parameters(), resolved.context())
				.getTraits(FulltextAnalysisSide.QUERY);
			if (traits.contains(FulltextAnalysisTrait.STEMS)) {
				if (traits.contains(FulltextAnalysisTrait.FOLDS_DIACRITICS)) {
					words.add(AnalysisSteps.asciiFolding());
				}
			} else {
				words.add(step);
			}
		}
		final ResolvedFulltextAnalyzer fragment = new ResolvedFulltextAnalyzer(
			analyzer, textSplit ? List.of() : resolved.text(), resolved.splitting(), words, resolved.context()
		);
		final FulltextChainCompiler compiler = new FulltextChainCompiler(registry);
		final Set<String> fragments = CollectionUtils.createLinkedHashSet(2);
		try (final FulltextAnalyzer chain = new FulltextAnalyzer(analyzer,
			compiler.compileWordChain(fragment, new ArrayList<>(0)))) {
			for (final String term : word.terms()) {
				chain.analyze(term, (value, surface, start, end, increment) -> {
					if (!value.isEmpty()) {
						fragments.add(value);
					}
				});
			}
		}
		return fragments.toArray(String[]::new);
	}
}
