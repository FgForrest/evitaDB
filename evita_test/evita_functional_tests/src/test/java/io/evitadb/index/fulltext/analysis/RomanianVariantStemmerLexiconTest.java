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
import org.tartarus.snowball.ext.RomanianStemmer;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.text.Normalizer;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Romanian instance of the {@link LexiconCoverageSweep}: verifies over the whole `ro_RO` Hunspell lexicon
 * that the production {@link RomanianVariantStemmer} — the query half of the `romanian`/`romanian-search`
 * built-in pair — always emits the term the index half wrote.
 *
 * The index side is the production shape: comma-below spellings normalized to the cedilla ones the pinned
 * Lucene's Snowball tables are written in (see {@link CommaBelowNormalizationFilter}), then the accented
 * Snowball stemmer, then folding.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Romanian variant stemmer — index-term coverage over the whole ro_RO lexicon")
@Tag(ENGINE)
@Tag(FULLTEXT)
class RomanianVariantStemmerLexiconTest {

	@Test
	@DisplayName("The variant set covers the folded index term for every dictionary word")
	void shouldCoverAccentedStemsOverWholeLexicon() throws IOException {
		final RomanianStemmer accented = new RomanianStemmer();

		final LexiconCoverageSweep.Result result = LexiconCoverageSweep.sweep(
			"/fulltext/hunspell/ro_RO.dic",
			RomanianVariantStemmerLexiconTest::fold,
			word -> {
				accented.setCurrent(commaToCedilla(word));
				accented.stem();
				return fold(accented.getCurrent());
			},
			new RomanianVariantStemmer()
		);

		System.out.println(result.summary("ro_RO"));
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
	 * Rewrites the comma-below letters to the legacy cedilla ones the pinned Snowball tables expect — the
	 * {@link CommaBelowNormalizationFilter} of the index chain as a plain function.
	 *
	 * @param text lowercased text
	 * @return the same text in cedilla orthography
	 */
	@Nonnull
	private static String commaToCedilla(@Nonnull String text) {
		return text.replace('ș', 'ş').replace('ț', 'ţ');
	}

	/**
	 * Folds diacritics the way the production `ASCIIFoldingFilter` does for the Romanian alphabet — NFD
	 * decomposition covers every Romanian letter in both orthographies.
	 *
	 * @param text text to fold
	 * @return folded text
	 */
	@Nonnull
	private static String fold(@Nonnull String text) {
		return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
	}

}
