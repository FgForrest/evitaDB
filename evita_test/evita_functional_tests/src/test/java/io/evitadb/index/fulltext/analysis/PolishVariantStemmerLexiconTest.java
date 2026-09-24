/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _  *             |  __/\ V /| | || (_| | |_| | |_) |
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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Polish instance of the {@link LexiconCoverageSweep}: verifies over the whole `pl_PL` Hunspell lexicon
 * that the production {@link PolishVariantStemmer} — the query half of the `polish`/`polish-search` built-in
 * pair — always emits the term the index half wrote.
 *
 * The index side is the production shape: the vendored Snowball stemmer ({@link PolishSnowballStemmer})
 * followed by folding. Both sides fold through {@link PolishAnalysisFixture#bareType(String)}, because Polish
 * folding must map the stroked `ł`, which NFD decomposition leaves alone.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Polish variant stemmer — index-term coverage over the whole pl_PL lexicon")
@Tag(ENGINE)
@Tag(FULLTEXT)
class PolishVariantStemmerLexiconTest {

	@Test
	@DisplayName("The variant set covers the folded index term for every dictionary word")
	void shouldCoverAccentedStemsOverWholeLexicon() throws IOException {
		final PolishSnowballStemmer accented = new PolishSnowballStemmer();

		final LexiconCoverageSweep.Result result = LexiconCoverageSweep.sweep(
			"/fulltext/hunspell/pl_PL.dic",
			PolishAnalysisFixture::bareType,
			word -> {
				accented.setCurrent(word);
				accented.stem();
				return PolishAnalysisFixture.bareType(accented.getCurrent());
			},
			new PolishVariantStemmer()
		);

		System.out.println(result.summary("pl_PL"));
		assertTrue(
			result.tested() > 100_000,
			"The lexicon is supposed to provide six figures of test words; got " + result.tested() + "."
		);
		assertTrue(
			result.uncoveredCount() == 0,
			"Every uncovered word is a fold-ambiguity no current fork covers - the variant stemmer needs a new "
				+ "fork for it:\n" + String.join("\n", result.uncovered())
		);
	}

}
