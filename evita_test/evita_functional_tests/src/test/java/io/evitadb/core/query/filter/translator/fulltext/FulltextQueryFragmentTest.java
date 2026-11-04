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

import io.evitadb.index.analysis.AnalysisStepRegistry;
import io.evitadb.test.fulltext.FulltextAnalysisTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Locale;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * The raw final word prepared through the built-in stemming profiles. Result sets cannot observe this: the stem
 * variants reach the same dictionary terms through their own prefixes and typo walks.
 */
@Tag(ENGINE)
@Tag(FULLTEXT)
@DisplayName("Unfinished query word fragment")
class FulltextQueryFragmentTest {
	/** Installed step providers, resolved as for a catalog. */
	private final AnalysisStepRegistry registry = new AnalysisStepRegistry();

	/** Lowercasing and the fold a stemmer declares apply, the stemmer and the stop filter do not. */
	@ParameterizedTest
	@CsvSource({"en,Running,running", "en,The,the", "cs,Žluťoučký,zlutoucky", "cs,Není,neni"})
	@DisplayName("Normalizes and folds the fragment as its profile does, but neither stems nor stops it")
	void shouldNormalizeWithoutStemmingOrStopping(String language, String word, String fragment) {
		final String[] prepared = FulltextQueryFragment.prepare(this.registry, FulltextAnalysisTestSupport.SCHEMA,
			"text", Locale.forLanguageTag(language), new TextMatchesTranslator.QueryToken(word, new String[]{word}),
			false);
		assertArrayEquals(new String[]{fragment}, prepared);
	}
}
