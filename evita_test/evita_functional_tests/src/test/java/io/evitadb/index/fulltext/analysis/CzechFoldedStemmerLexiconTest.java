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

import org.apache.lucene.analysis.cz.CzechStemmer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Czech instance of the {@link LexiconCoverageSweep}: verifies the M7 correctness invariant of the
 * {@link FoldedCzechStemmer} hypothesis set against the whole `cs_CZ` Hunspell lexicon, the way the Slovak
 * sweep verified — and initially falsified — the Slovak port. The Czech M7 rows (A20–A22 of
 * {@link CzechAnalysisApproachMatrixTest}) were validated on the fixture only; this test is their
 * lexicon-scale counterpart.
 *
 * The index side is the production shape: Lucene's accented `CzechStemmer` followed by folding. The query
 * side forks all four fold-ambiguous rules of the port (palatalization, penultimate vowel shift, the neuter
 * `-at-` paradigm, the epenthetic `e` removal) — sixteen hypotheses, exactly what the A20 chain emits.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Czech folded stemmer — M7 coverage over the whole cs_CZ lexicon")
@Tag(ENGINE)
@Tag(FULLTEXT)
class CzechFoldedStemmerLexiconTest {

	@Test
	@DisplayName("The hypothesis set covers the folded index term for every dictionary word")
	void shouldCoverAccentedStemsOverWholeLexicon() throws IOException {
		final CzechStemmer accented = new CzechStemmer();
		final List<UnaryOperator<String>> hypotheses = new ArrayList<>(129);
		for (final FoldedStemmer stemmer : FoldedCzechStemmer.allHypotheses()) {
			hypotheses.add(word -> LexiconCoverageSweep.stem(word, stemmer));
		}

		final LexiconCoverageSweep.Result result = LexiconCoverageSweep.sweep(
			"/fulltext/hunspell/cs_CZ.dic",
			CzechFoldedStemmerLexiconTest::fold,
			word -> {
				final char[] buffer = word.toCharArray();
				final int length = accented.stem(buffer, buffer.length);
				return fold(new String(buffer, 0, length));
			},
			hypotheses
		);

		System.out.println(result.summary("cs_CZ"));
		assertTrue(
			result.tested() > 100_000,
			"The lexicon is supposed to provide six figures of test words; got " + result.tested() + "."
		);
		assertTrue(
			result.uncoveredCount() == 0,
			"Every uncovered word is a fold-ambiguity no current fork covers - the folded port needs a new "
				+ "switch for it:\n" + String.join("\n", result.uncovered())
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
