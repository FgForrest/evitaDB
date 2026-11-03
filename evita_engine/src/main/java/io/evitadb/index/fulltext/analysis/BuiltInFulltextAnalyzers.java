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

import io.evitadb.api.requestResponse.schema.FulltextAnalyzerSchemaContract;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.api.requestResponse.schema.analysis.FulltextAnalysisPhases;
import io.evitadb.api.requestResponse.schema.dto.FulltextAnalyzerSchema;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;

/**
 * The built-in fulltext analyzers under the reserved names of
 * {@link FulltextAnalyzerSchemaContract#BUILT_IN_ANALYZERS}, defined the way a catalog defines its own, so that they
 * resolve and compile through the same path:
 *
 * - `text` lowercases every language and replaces the words phase of the languages evitaDB stems with the line that
 *   produces the terms of the chain the language had before analyzers were configurable,
 * - `html-text` is `text` with `html-strip` as its text phase.
 */
final class BuiltInFulltextAnalyzers {
	/**
	 * The built-in analyzers keyed by their reserved names, `text` before `html-text`, which is based on it - so that
	 * whatever walks them meets them in the same order every time.
	 */
	private static final Map<String, FulltextAnalyzerSchemaContract> ANALYZERS = ordered(
		FulltextAnalyzerSchema._internalBuild(
			FulltextAnalyzerSchemaContract.TEXT,
			null,
			new FulltextAnalysisPhases(null, List.of(lowercase())),
			Map.of(
				"cs", words(lowercase(), stopWords(czechStopWords()), czechStemming()),
				"sk", words(lowercase(), slovakStemming()),
				"pl", words(lowercase(), stopWords(polishStopWords()), polishStemming()),
				"ro", words(lowercase(), romanianNormalization(), stopWords(romanianStopWords()), romanianStemming()),
				"en", words(englishPossessive(), lowercase(), stopWords(englishStopWords()), englishStemming()),
				"de", words(lowercase(), stopWords(germanStopWords()), germanStemming())
			)
		),
		FulltextAnalyzerSchema._internalBuild(
			FulltextAnalyzerSchemaContract.HTML_TEXT,
			FulltextAnalyzerSchemaContract.TEXT,
			new FulltextAnalysisPhases(List.of(htmlStrip()), null),
			Map.of()
		)
	);

	/**
	 * Prevents instantiation of this holder.
	 */
	private BuiltInFulltextAnalyzers() {
	}

	/**
	 * Returns the built-in analyzers keyed by their reserved names.
	 *
	 * @return the built-in analyzers, immutable
	 */
	@Nonnull
	static Map<String, FulltextAnalyzerSchemaContract> getAnalyzers() {
		return ANALYZERS;
	}

	/**
	 * Keys analyzers by their names in the given order.
	 *
	 * @param analyzers the analyzers in the order they are to be walked
	 * @return an unmodifiable map keeping the order
	 */
	@Nonnull
	private static Map<String, FulltextAnalyzerSchemaContract> ordered(
		@Nonnull FulltextAnalyzerSchemaContract... analyzers
	) {
		final Map<String, FulltextAnalyzerSchemaContract> map = CollectionUtils.createLinkedHashMap(analyzers.length);
		for (final FulltextAnalyzerSchemaContract analyzer : analyzers) {
			map.put(analyzer.getName(), analyzer);
		}
		return Collections.unmodifiableMap(map);
	}

	/**
	 * Creates the phases of a language override that replaces the words phase and inherits the text phase.
	 *
	 * @param steps the steps of the words phase in execution order
	 * @return the phases of the override
	 */
	@Nonnull
	private static FulltextAnalysisPhases words(@Nonnull AnalysisStep... steps) {
		return new FulltextAnalysisPhases(null, List.of(steps));
	}
}
