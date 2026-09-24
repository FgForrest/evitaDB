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

import org.apache.lucene.analysis.cz.CzechStemmer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.text.Normalizer;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Czech instance of the {@link LexiconCoverageSweep}: verifies over the whole `cs_CZ` Hunspell lexicon that
 * the production {@link CzechVariantStemmer} — the query half of the `czech`/`czech-search` built-in pair —
 * always emits the term the index half wrote.
 *
 * The index side is the production shape: Lucene's accented `CzechStemmer` followed by folding. A word that
 * fails here is a bare-typed query that would silently fail to find its own document.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Czech variant stemmer — index-term coverage over the whole cs_CZ lexicon")
@Tag(ENGINE)
@Tag(FULLTEXT)
class CzechVariantStemmerLexiconTest {

	@Test
	@DisplayName("The variant set covers the folded index term for every dictionary word")
	void shouldCoverAccentedStemsOverWholeLexicon() throws IOException {
		final CzechStemmer accented = new CzechStemmer();

		final LexiconCoverageSweep.Result result = LexiconCoverageSweep.sweep(
			"/fulltext/hunspell/cs_CZ.dic",
			CzechVariantStemmerLexiconTest::fold,
			word -> {
				final char[] buffer = word.toCharArray();
				final int length = accented.stem(buffer, buffer.length);
				return fold(new String(buffer, 0, length));
			},
			new CzechVariantStemmer()
		);

		System.out.println(result.summary("cs_CZ"));
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

	/**
	 * Folds diacritics the way the production `ASCIIFoldingFilter` does for the Czech alphabet — NFD
	 * decomposition covers every Czech letter.
	 *
	 * @param text text to fold
	 * @return folded text
	 */
	@Nonnull
	private static String fold(@Nonnull String text) {
		return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
	}

}
